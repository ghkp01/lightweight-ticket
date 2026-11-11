package com.mes.sales;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import com.mes.masterdata.api.MasterDataPort;
import com.mes.masterdata.api.MasterDataViews;
import com.mes.masterdata.api.ProductInfo;
import com.mes.notice.api.NoticeMessage;
import com.mes.notice.api.NoticePort;
import com.mes.platform.config.ConfigService;
import com.mes.sales.api.QuotationPort;
import com.mes.security.CurrentUser;
import com.mes.system.api.UserDirectoryPort;
import com.mes.task.api.TaskDraft;
import com.mes.task.api.TaskPort;
import com.mes.task.api.TaskTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 报价单：报价员报价 → 技术部会签评审 → 全部通过后转销售订单。
 *
 * <p>生产约束：评审人必须显式指定且不能是报价人本人；全部评审人通过才算通过，
 * 任一人驳回即结束本轮；转单使用行锁 + 唯一索引，避免并发重复转单；
 * 评审、转单都会关闭对应待办并给报价人发站内通知。</p>
 */
@Service
public class QuotationService implements QuotationPort {

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;
    private final MasterDataPort masterData;
    private final SalesService salesService;
    private final UserDirectoryPort users;
    private final TaskPort tasks;
    private final NoticePort notices;
    private final ConfigService configService;

    public QuotationService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator,
                            MasterDataPort masterData, SalesService salesService,
                            UserDirectoryPort users, TaskPort tasks, NoticePort notices,
                            ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
        this.masterData = masterData;
        this.salesService = salesService;
        this.users = users;
        this.tasks = tasks;
        this.notices = notices;
        this.configService = configService;
    }

    public record QuoteItem(Long productId, BigDecimal qty, BigDecimal price, String remark) {
    }

    public record QuoteRequest(Long customerId, String contact, LocalDate validUntil,
                               LocalDate deliveryDate, String remark, List<QuoteItem> items,
                               String currency, BigDecimal taxRate, Boolean priceIncludesTax,
                               String paymentTerms) {

        /** 兼容旧调用：不传商务字段时按人民币、13%、含税 */
        public QuoteRequest(Long customerId, String contact, LocalDate validUntil,
                            LocalDate deliveryDate, String remark, List<QuoteItem> items) {
            this(customerId, contact, validUntil, deliveryDate, remark, items,
                    null, null, null, null);
        }
    }

    /** 提交评审时必须指定评审人 */
    public record SubmitRequest(List<Long> reviewerIds) {
    }

    /** reviewerId 仅管理员代审时使用：代表该评审人给出结论 */
    public record ReviewRequest(String result, String comment, Long reviewerId) {

        public ReviewRequest(String result, String comment) {
            this(result, comment, null);
        }
    }

    public PageResult list(String keyword, String status, int page, int size) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where (q.quote_no ilike ?");
        args.add(like);
        Set<Long> customerIds = masterData.searchCustomerIds(keyword);
        if (!customerIds.isEmpty()) {
            where.append(" or q.customer_id in (")
                    .append(String.join(", ", java.util.Collections.nCopies(customerIds.size(), "?")))
                    .append(")");
            args.addAll(customerIds);
        }
        where.append(") ");
        if (restrictedQuoter()) {
            where.append(" and q.created_by = ? ");
            args.add(CurrentUser.name());
        }
        if (status != null && !status.isBlank()) {
            where.append(" and q.status = ? ");
            args.add(status);
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from sales_quote q" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select q.id, q.quote_no as "quoteNo", q.customer_id as "customerId",
                       q.contact, q.valid_until as "validUntil", q.delivery_date as "deliveryDate",
                       q.status, q.total_qty as "totalQty", q.total_amount as "totalAmount",
                       q.currency, q.tax_rate as "taxRate",
                       q.price_includes_tax as "priceIncludesTax",
                       q.net_amount as "netAmount", q.tax_amount as "taxAmount",
                       q.payment_terms as "paymentTerms",
                       q.review_comment as "reviewComment", q.reviewed_by as "reviewedBy",
                       to_char(q.reviewed_at, 'YYYY-MM-DD HH24:MI') as "reviewedAt",
                       q.review_due_date as "reviewDueDate",
                       q.converted_order_id as "convertedOrderId",
                       q.created_by as "createdBy",
                       to_char(q.created_at, 'YYYY-MM-DD') as "createdAt"
                from sales_quote q
                """ + where + " order by q.id desc limit ? offset ?", pageArgs.toArray());
        MasterDataViews.fillCustomerNames(masterData, records);
        fillMyReviewStatus(records);
        return new PageResult(records, total == null ? 0 : total, page, size);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select q.id, q.quote_no as "quoteNo", q.customer_id as "customerId",
                       q.contact, q.valid_until as "validUntil", q.delivery_date as "deliveryDate",
                       q.status, q.total_qty as "totalQty", q.total_amount as "totalAmount",
                       q.currency, q.tax_rate as "taxRate",
                       q.price_includes_tax as "priceIncludesTax",
                       q.net_amount as "netAmount", q.tax_amount as "taxAmount",
                       q.payment_terms as "paymentTerms",
                       q.remark, q.review_comment as "reviewComment", q.reviewed_by as "reviewedBy",
                       to_char(q.reviewed_at, 'YYYY-MM-DD HH24:MI') as "reviewedAt",
                       q.review_due_date as "reviewDueDate",
                       q.converted_order_id as "convertedOrderId",
                       q.created_by as "createdBy",
                       to_char(q.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from sales_quote q where q.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "报价单不存在");
        }
        MasterDataViews.fillCustomerNames(masterData, rows);
        Map<String, Object> quote = new LinkedHashMap<>(rows.get(0));
        assertReadable(quote);
        quote.put("items", jdbcTemplate.queryForList("""
                select i.id, i.product_id as "productId", i.product_code as "productCode",
                       i.product_name as "productName", i.spec, i.unit,
                       i.qty, i.price, i.amount, i.remark
                from sales_quote_item i where i.quote_id = ? order by i.id
                """, id));

        Map<Long, UserDirectoryPort.UserRef> userMap = new LinkedHashMap<>();
        List<Map<String, Object>> reviewers = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select reviewer_id as "reviewerId", status, comment,
                       to_char(reviewed_at, 'YYYY-MM-DD HH24:MI') as "reviewedAt"
                from sales_quote_reviewer where quote_id = ? order by id
                """, id)) {
            long reviewerId = ((Number) row.get("reviewerId")).longValue();
            UserDirectoryPort.UserRef user = userMap.computeIfAbsent(reviewerId,
                    key -> users.find(key).orElse(null));
            Map<String, Object> reviewer = new LinkedHashMap<>(row);
            reviewer.put("reviewerName", user == null ? null : user.realName());
            reviewer.put("username", user == null ? null : user.username());
            reviewers.add(reviewer);
        }
        quote.put("reviewers", reviewers);
        quote.put("myReviewStatus", myReviewStatus(id));
        quote.put("reviews", jdbcTemplate.queryForList("""
                select id, round_no as "roundNo", reviewer, result, comment,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from sales_quote_review where quote_id = ? order by round_no, id
                """, id));
        return quote;
    }

    /** 可担任评审的账号：以 quotation:review 权限为准 */
    public List<UserDirectoryPort.UserRef> reviewers() {
        return users.byPermission("quotation:review");
    }

    @Override
    public List<QuotationPort.ReviewReminder> reviewReminders(int aheadDays) {
        LocalDate today = LocalDate.now();
        LocalDate until = today.plusDays(Math.max(aheadDays, 0));
        List<QuotationPort.ReviewReminder> result = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                select id, quote_no as "quoteNo", created_by as "createdBy",
                       review_due_date as "dueDate"
                from sales_quote
                where status = 'SUBMITTED' and review_due_date is not null
                  and review_due_date <= ?
                order by review_due_date, id
                """, until)) {
            LocalDate due = ((java.sql.Date) row.get("dueDate")).toLocalDate();
            List<String> pending = new ArrayList<>();
            for (Map<String, Object> reviewer : jdbcTemplate.queryForList("""
                    select reviewer_id from sales_quote_reviewer
                    where quote_id = ? and status = 'PENDING'
                    """, row.get("id"))) {
                users.find(((Number) reviewer.get("reviewer_id")).longValue())
                        .ifPresent(user -> pending.add(user.username()));
            }
            result.add(new QuotationPort.ReviewReminder(
                    String.valueOf(row.get("quoteNo")),
                    row.get("createdBy") == null ? null : String.valueOf(row.get("createdBy")),
                    due, due.isBefore(today), pending));
        }
        return result;
    }

    @Transactional
    public long create(QuoteRequest request) {
        validate(request);
        String quoteNo = codeGenerator.next("SQ");
        String currency = normalizeCurrency(request.currency());
        BigDecimal taxRate = normalizeTaxRate(request.taxRate());
        boolean includesTax = request.priceIncludesTax() == null || request.priceIncludesTax();
        Long id = jdbcTemplate.queryForObject("""
                insert into sales_quote (quote_no, customer_id, contact, valid_until, delivery_date,
                                         status, remark, created_by, currency, tax_rate,
                                         price_includes_tax, payment_terms)
                values (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, quoteNo, request.customerId(), blankToNull(request.contact()),
                request.validUntil(), request.deliveryDate(), blankToNull(request.remark()),
                CurrentUser.name(), currency, taxRate, includesTax,
                blankToNull(request.paymentTerms()));
        saveItems(id, request.items());
        refreshTotals(id, taxRate, includesTax);
        return id;
    }

    @Transactional
    public void update(long id, QuoteRequest request) {
        Map<String, Object> quote = requireQuoteForUpdate(id);
        assertOwner(quote);
        requireStatus(quote, "只有草稿或已驳回的报价单可以修改", "DRAFT", "REJECTED");
        validate(request);
        String currency = normalizeCurrency(request.currency());
        BigDecimal taxRate = normalizeTaxRate(request.taxRate());
        boolean includesTax = request.priceIncludesTax() == null || request.priceIncludesTax();
        jdbcTemplate.update("""
                update sales_quote set customer_id = ?, contact = ?, valid_until = ?, delivery_date = ?,
                       remark = ?, currency = ?, tax_rate = ?, price_includes_tax = ?,
                       payment_terms = ?, updated_at = now()
                 where id = ?
                """, request.customerId(), blankToNull(request.contact()), request.validUntil(),
                request.deliveryDate(), blankToNull(request.remark()), currency, taxRate,
                includesTax, blankToNull(request.paymentTerms()), id);
        jdbcTemplate.update("delete from sales_quote_item where quote_id = ?", id);
        saveItems(id, request.items());
        refreshTotals(id, taxRate, includesTax);
    }

    @Transactional
    public void submit(long id, SubmitRequest request) {
        Map<String, Object> quote = requireQuoteForUpdate(id);
        assertOwner(quote);
        requireStatus(quote, "只有草稿或已驳回的报价单可以提交评审", "DRAFT", "REJECTED");
        List<UserDirectoryPort.UserRef> reviewers = resolveReviewers(quote, request);
        String quoteNo = String.valueOf(quote.get("quoteNo"));
        LocalDate reviewDueDate = reviewDueDate(quote);

        jdbcTemplate.update("delete from sales_quote_reviewer where quote_id = ?", id);
        for (UserDirectoryPort.UserRef reviewer : reviewers) {
            jdbcTemplate.update("""
                    insert into sales_quote_reviewer (quote_id, reviewer_id) values (?, ?)
                    """, id, reviewer.id());
            openReviewTask(id, quoteNo, reviewDueDate, reviewer);
        }
        jdbcTemplate.update("""
                update sales_quote set status = 'SUBMITTED', review_comment = null,
                       reviewed_by = null, reviewed_at = null, review_due_date = ?,
                       updated_at = now()
                 where id = ?
                """, reviewDueDate, id);
    }

    /** 加签：报价人或管理员在评审过程中追加评审人 */
    @Transactional
    public void addReviewer(long id, long reviewerId) {
        Map<String, Object> quote = requireQuoteForUpdate(id);
        requireStatus(quote, "只有已提交的报价单可以加签", "SUBMITTED");
        assertCreatorOrAdmin(quote);
        UserDirectoryPort.UserRef reviewer = resolveOneReviewer(quote, reviewerId);
        int exists = count("""
                select count(*) from sales_quote_reviewer where quote_id = ? and reviewer_id = ?
                """, id, reviewerId);
        if (exists > 0) {
            throw new BizException(1002, "该评审人已在评审名单中");
        }
        jdbcTemplate.update("""
                insert into sales_quote_reviewer (quote_id, reviewer_id) values (?, ?)
                """, id, reviewerId);
        openReviewTask(id, String.valueOf(quote.get("quoteNo")), reviewDueOf(quote), reviewer);
    }

    /** 转办：待评审人（或管理员）把自己这票转给其他有评审权限的人 */
    @Transactional
    public void transferReviewer(long id, long fromReviewerId, long toReviewerId) {
        Map<String, Object> quote = requireQuoteForUpdate(id);
        requireStatus(quote, "只有已提交的报价单可以转办", "SUBMITTED");
        if (!isAdmin()) {
            UserDirectoryPort.UserRef current = users.findByUsername(CurrentUser.name())
                    .orElseThrow(() -> new BizException(1002, "当前账号不存在"));
            if (current.id() != fromReviewerId) {
                throw new BizException(1003, "只能转办自己的评审任务");
            }
        }
        UserDirectoryPort.UserRef target = resolveOneReviewer(quote, toReviewerId);
        int pending = count("""
                select count(*) from sales_quote_reviewer
                where quote_id = ? and reviewer_id = ? and status = 'PENDING'
                """, id, fromReviewerId);
        if (pending == 0) {
            throw new BizException(1002, "该评审人不是待评审状态，不能转办");
        }
        int exists = count("""
                select count(*) from sales_quote_reviewer where quote_id = ? and reviewer_id = ?
                """, id, toReviewerId);
        if (exists > 0) {
            throw new BizException(1002, "目标评审人已在评审名单中");
        }
        jdbcTemplate.update("""
                update sales_quote_reviewer set reviewer_id = ?
                 where quote_id = ? and reviewer_id = ? and status = 'PENDING'
                """, toReviewerId, id, fromReviewerId);
        String quoteNo = String.valueOf(quote.get("quoteNo"));
        tasks.close(TaskTypes.QUOTE_REVIEW, quoteNo + "-" + fromReviewerId,
                CurrentUser.name(), "报价评审转办");
        openReviewTask(id, quoteNo, reviewDueOf(quote), target);
    }

    /** 撤签：报价人或管理员撤回尚未评审的人；全部撤完则退回草稿 */
    @Transactional
    public void removeReviewer(long id, long reviewerId) {
        Map<String, Object> quote = requireQuoteForUpdate(id);
        requireStatus(quote, "只有已提交的报价单可以撤签", "SUBMITTED");
        assertCreatorOrAdmin(quote);
        int removed = jdbcTemplate.update("""
                delete from sales_quote_reviewer
                 where quote_id = ? and reviewer_id = ? and status = 'PENDING'
                """, id, reviewerId);
        if (removed == 0) {
            throw new BizException(1002, "该评审人不是待评审状态，不能撤签");
        }
        tasks.close(TaskTypes.QUOTE_REVIEW,
                String.valueOf(quote.get("quoteNo")) + "-" + reviewerId,
                CurrentUser.name(), "报价评审撤签");
        if (count("""
                select count(*) from sales_quote_reviewer where quote_id = ? and status = 'PENDING'
                """, id) == 0) {
            jdbcTemplate.update("""
                    update sales_quote set status = 'DRAFT', review_due_date = null,
                           updated_at = now()
                     where id = ?
                    """, id);
        }
    }

    /** 指定评审人评审：全部通过才算通过，任一人驳回即结束本轮 */
    @Transactional
    public void review(long id, ReviewRequest request) {
        Map<String, Object> quote = requireQuoteForUpdate(id);
        requireStatus(quote, "只有已提交的报价单可以评审", "SUBMITTED");
        String result = request.result() == null ? "" : request.result().trim().toUpperCase();
        if (!List.of("PASS", "REJECT").contains(result)) {
            throw new BizException(1002, "评审结果只能是 PASS 或 REJECT");
        }
        String comment = blankToNull(request.comment());
        if ("REJECT".equals(result) && comment == null) {
            throw new BizException(1002, "驳回时请填写评审意见");
        }
        UserDirectoryPort.UserRef current = users.findByUsername(CurrentUser.name())
                .orElseThrow(() -> new BizException(1002, "当前账号不存在"));
        boolean admin = isAdmin();
        long targetReviewerId;
        String auditName = CurrentUser.name();
        if (request.reviewerId() != null) {
            if (!admin) {
                throw new BizException(1003, "只有管理员可以代审");
            }
            targetReviewerId = request.reviewerId();
            UserDirectoryPort.UserRef target = users.find(targetReviewerId)
                    .orElseThrow(() -> new BizException(1002, "被代审人不存在"));
            auditName = CurrentUser.name() + "（代审 " + target.username() + "）";
        } else {
            targetReviewerId = current.id();
        }
        int updated = jdbcTemplate.update("""
                update sales_quote_reviewer
                   set status = ?, comment = ?, reviewed_at = now()
                 where quote_id = ? and reviewer_id = ? and status = 'PENDING'
                """, result, comment, id, targetReviewerId);
        if (updated == 0) {
            throw new BizException(1002, "该评审人不是本轮待评审人，或已经评审过了");
        }
        Integer round = jdbcTemplate.queryForObject(
                "select coalesce(max(round_no), 0) + 1 from sales_quote_review where quote_id = ?",
                Integer.class, id);
        jdbcTemplate.update("""
                insert into sales_quote_review (quote_id, round_no, reviewer, result, comment)
                values (?, ?, ?, ?, ?)
                """, id, round == null ? 1 : round, auditName, result, comment);

        String quoteNo = String.valueOf(quote.get("quoteNo"));
        tasks.close(TaskTypes.QUOTE_REVIEW, quoteNo + "-" + targetReviewerId,
                CurrentUser.name(), "已完成报价评审");

        int pending = count("""
                select count(*) from sales_quote_reviewer
                where quote_id = ? and status = 'PENDING'
                """, id);
        int rejected = count("""
                select count(*) from sales_quote_reviewer
                where quote_id = ? and status = 'REJECT'
                """, id);
        if (rejected > 0) {
            finish(id, quoteNo, "REJECTED", comment, "报价评审未通过",
                    "报价单 " + quoteNo + " 被驳回：" + (comment == null ? "未填原因" : comment));
        } else if (pending == 0) {
            finish(id, quoteNo, "APPROVED", comment, "报价评审已通过",
                    "报价单 " + quoteNo + " 已通过技术评审，可以转销售订单");
        }
    }

    /** 评审通过后转销售订单：行锁 + 唯一索引保证只转一次 */
    @Transactional
    public long convert(long id) {
        Map<String, Object> quote = requireQuoteForUpdate(id);
        assertOwner(quote);
        requireStatus(quote, "只有评审通过的报价单可以转销售订单", "APPROVED");
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select product_id as "productId", qty, price
                from sales_quote_item where quote_id = ? order by id
                """, id);
        if (items.isEmpty()) {
            throw new BizException(1002, "报价单没有明细，不能转订单");
        }
        List<SalesService.OrderItem> orderItems = items.stream()
                .map(item -> new SalesService.OrderItem(
                        ((Number) item.get("productId")).longValue(),
                        (BigDecimal) item.get("qty"),
                        grossUnitPrice((BigDecimal) item.get("price"), quote)))
                .toList();
        LocalDate deliveryDate = quote.get("deliveryDate") == null ? LocalDate.now().plusDays(7)
                : ((java.sql.Date) quote.get("deliveryDate")).toLocalDate();
        long orderId = salesService.create(new SalesService.OrderRequest(
                ((Number) quote.get("customerId")).longValue(), LocalDate.now(), deliveryDate,
                "由报价单 " + quote.get("quoteNo") + " 转入",
                orderItems, "NORMAL", null,
                quote.get("contact") == null ? null : String.valueOf(quote.get("contact")),
                null, null, null,
                quote.get("currency") == null ? null : String.valueOf(quote.get("currency"))));
        jdbcTemplate.update("""
                update sales_quote set status = 'CONVERTED', converted_order_id = ?, updated_at = now()
                 where id = ?
                """, orderId, id);
        jdbcTemplate.update("update sales_order set quote_id = ? where id = ?", id, orderId);
        notices.push(String.valueOf(quote.get("createdBy")), NoticeMessage.of("INFO",
                "报价已转订单", "报价单 " + quote.get("quoteNo") + " 已转销售订单",
                "SALES_ORDER", "ID " + orderId));
        return orderId;
    }

    @Transactional
    public void cancel(long id) {
        Map<String, Object> quote = requireQuoteForUpdate(id);
        assertOwner(quote);
        if ("CONVERTED".equals(String.valueOf(quote.get("status")))) {
            throw new BizException(1002, "已转订单的报价单不能取消");
        }
        jdbcTemplate.update("""
                update sales_quote set status = 'CANCELLED', updated_at = now() where id = ?
                """, id);
        closeReviewTasks(id, "报价单已取消");
    }

    @Transactional
    public void delete(long id) {
        Map<String, Object> quote = requireQuoteForUpdate(id);
        assertOwner(quote);
        requireStatus(quote, "只有草稿报价单可以删除", "DRAFT");
        jdbcTemplate.update("delete from sales_quote where id = ?", id);
    }

    // ---------------------------------------------------------------- 数据范围

    private boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(item -> authority.equals(item.getAuthority()));
    }

    private boolean isAdmin() {
        return hasAuthority("ROLE_ADMIN");
    }

    private void assertCreatorOrAdmin(Map<String, Object> quote) {
        if (isAdmin()) {
            return;
        }
        String creator = quote.get("createdBy") == null ? null
                : String.valueOf(quote.get("createdBy"));
        if (!CurrentUser.name().equals(creator)) {
            throw new BizException(1003, "只有报价人或管理员可以操作");
        }
    }

    private LocalDate reviewDueOf(Map<String, Object> quote) {
        Object value = quote.get("reviewDueDate");
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return value == null ? LocalDate.now().plusDays(3) : LocalDate.parse(String.valueOf(value));
    }

    private void openReviewTask(long quoteId, String quoteNo, LocalDate dueDate,
                                UserDirectoryPort.UserRef reviewer) {
        tasks.open(new TaskDraft(TaskTypes.QUOTE_REVIEW,
                "评审报价：" + quoteNo,
                "客户报价单 " + quoteNo + " 待技术评审",
                TaskTypes.REF_QUOTE, quoteId, quoteNo + "-" + reviewer.id(),
                "TECH", reviewer.username(), TaskTypes.PRIORITY_NORMAL, dueDate));
        notices.push(reviewer.username(), NoticeMessage.of("TASK",
                "报价评审待办", "报价单 " + quoteNo + " 等待你评审", "QUOTE", quoteNo));
    }

    private UserDirectoryPort.UserRef resolveOneReviewer(Map<String, Object> quote, long reviewerId) {
        UserDirectoryPort.UserRef reviewer = users.find(reviewerId)
                .orElseThrow(() -> new BizException(1002, "评审人不存在：" + reviewerId));
        boolean candidate = users.byPermission("quotation:review").stream()
                .anyMatch(user -> user.id() == reviewerId);
        if (!candidate) {
            throw new BizException(1002, "该账号没有报价评审权限：" + reviewer.username());
        }
        if (reviewer.username().equals(String.valueOf(quote.get("createdBy")))) {
            throw new BizException(1002, "报价人不能评审自己的报价单");
        }
        return reviewer;
    }

    /** 报价员只看自己的报价；有评审权限或管理员不受限制 */
    private boolean restrictedQuoter() {
        return hasAuthority("ROLE_QUOTER") && !hasAuthority("quotation:review")
                && !hasAuthority("ROLE_ADMIN");
    }

    private void assertReadable(Map<String, Object> quote) {
        if (restrictedQuoter()) {
            assertOwner(quote);
        }
    }

    private void assertOwner(Map<String, Object> quote) {
        if (hasAuthority("ROLE_ADMIN")) {
            return;
        }
        String creator = quote.get("createdBy") == null ? null
                : String.valueOf(quote.get("createdBy"));
        if (!CurrentUser.name().equals(creator)) {
            throw new BizException(1003, "只能操作自己创建的报价单");
        }
    }

    // ---------------------------------------------------------------- 内部

    private void finish(long id, String quoteNo, String status, String comment,
                        String title, String content) {
        jdbcTemplate.update("""
                update sales_quote set status = ?, review_comment = ?, reviewed_by = ?,
                       reviewed_at = now(), updated_at = now()
                 where id = ?
                """, status, comment, CurrentUser.name(), id);
        closeReviewTasks(id, status.equals("APPROVED") ? "报价评审通过" : "报价评审驳回");
        notices.push(creatorOf(id), NoticeMessage.of("TASK", title, content, "QUOTE", quoteNo));
    }

    private void closeReviewTasks(long quoteId, String note) {
        tasks.closeByRef(TaskTypes.QUOTE_REVIEW, TaskTypes.REF_QUOTE,
                List.of(quoteId), CurrentUser.name(), note);
    }

    private String creatorOf(long quoteId) {
        return jdbcTemplate.queryForObject(
                "select created_by from sales_quote where id = ?", String.class, quoteId);
    }

    private String myReviewStatus(long quoteId) {
        return users.findByUsername(CurrentUser.name()).map(current ->
                jdbcTemplate.query("""
                        select status from sales_quote_reviewer where quote_id = ? and reviewer_id = ?
                        """, (rs, rowNum) -> rs.getString("status"), quoteId, current.id())
                        .stream().findFirst().orElse(null)
        ).orElse(null);
    }

    /** 列表一次性带出「我的评审状态」，前端只给待评审人显示评审按钮 */
    private void fillMyReviewStatus(List<Map<String, Object>> records) {
        if (records.isEmpty()) {
            return;
        }
        Optional<UserDirectoryPort.UserRef> current = users.findByUsername(CurrentUser.name());
        Map<Long, String> statuses = new HashMap<>();
        if (current.isPresent()) {
            List<Long> ids = records.stream()
                    .map(record -> ((Number) record.get("id")).longValue()).toList();
            List<Object> args = new ArrayList<>();
            args.add(current.get().id());
            args.addAll(ids);
            String marks = String.join(", ",
                    java.util.Collections.nCopies(ids.size(), "?"));
            for (Map<String, Object> row : jdbcTemplate.queryForList("""
                    select quote_id, status from sales_quote_reviewer
                    where reviewer_id = ? and quote_id in (""" + marks + ")",
                    args.toArray())) {
                statuses.put(((Number) row.get("quote_id")).longValue(),
                        String.valueOf(row.get("status")));
            }
        }
        for (Map<String, Object> record : records) {
            record.put("myReviewStatus",
                    statuses.get(((Number) record.get("id")).longValue()));
        }
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private List<UserDirectoryPort.UserRef> resolveReviewers(Map<String, Object> quote,
                                                             SubmitRequest request) {
        if (request == null || request.reviewerIds() == null || request.reviewerIds().isEmpty()) {
            throw new BizException(1002, "请至少指定一名评审人");
        }
        Set<Long> candidates = new LinkedHashSet<>();
        for (UserDirectoryPort.UserRef user : users.byPermission("quotation:review")) {
            candidates.add(user.id());
        }
        String creator = String.valueOf(quote.get("createdBy"));
        Set<Long> ids = new LinkedHashSet<>(request.reviewerIds());
        List<UserDirectoryPort.UserRef> reviewers = new ArrayList<>();
        for (Long reviewerId : ids) {
            if (reviewerId == null || !candidates.contains(reviewerId)) {
                throw new BizException(1002, "评审人不存在或没有评审权限：" + reviewerId);
            }
            UserDirectoryPort.UserRef reviewer = users.find(reviewerId)
                    .orElseThrow(() -> new BizException(1002, "评审人不存在：" + reviewerId));
            if (reviewer.username().equals(creator)) {
                throw new BizException(1002, "报价人不能评审自己的报价单");
            }
            reviewers.add(reviewer);
        }
        return reviewers;
    }

    private void validate(QuoteRequest request) {
        if (request.customerId() == null) {
            throw new BizException(1002, "请选择客户");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BizException(1002, "请至少填写一条报价明细");
        }
        for (QuoteItem item : request.items()) {
            if (item.productId() == null) {
                throw new BizException(1002, "报价明细请选择产品");
            }
            if (item.qty() == null || item.qty().signum() <= 0) {
                throw new BizException(1002, "报价数量必须大于 0");
            }
            if (item.price() == null || item.price().signum() < 0) {
                throw new BizException(1002, "报价单价不能是负数");
            }
            if (masterData.product(item.productId()).isEmpty()) {
                throw new BizException(1002, "产品不存在：" + item.productId());
            }
        }
    }

    private void saveItems(long quoteId, List<QuoteItem> items) {
        for (QuoteItem item : items) {
            ProductInfo product = masterData.product(item.productId())
                    .orElseThrow(() -> new BizException(1002, "产品不存在：" + item.productId()));
            BigDecimal amount = item.qty().multiply(item.price()).setScale(2, RoundingMode.HALF_UP);
            jdbcTemplate.update("""
                    insert into sales_quote_item
                        (quote_id, product_id, product_code, product_name, spec, unit,
                         qty, price, amount, remark)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, quoteId, product.id(), product.code(), product.name(), product.spec(),
                    product.unit(), item.qty(), item.price(), amount, blankToNull(item.remark()));
        }
    }

    /**
     * 金额口径：明细合计按录入单价汇总。
     * 含税录入时，价税合计=明细合计，未税=合计/(1+税率)，税额=合计-未税；
     * 未税录入时，未税=明细合计，税额=未税×税率，价税合计=未税+税额。
     */
    private void refreshTotals(long quoteId, BigDecimal taxRate, boolean includesTax) {
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                select coalesce(sum(qty), 0) as qty, coalesce(sum(amount), 0) as amount
                from sales_quote_item where quote_id = ?
                """, quoteId);
        BigDecimal entered = new BigDecimal(String.valueOf(totals.get("amount")))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate = taxRate == null ? BigDecimal.ZERO : taxRate;
        BigDecimal net;
        BigDecimal tax;
        BigDecimal total;
        if (includesTax) {
            total = entered;
            net = rate.signum() == 0 ? total
                    : total.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
            tax = total.subtract(net);
        } else {
            net = entered;
            tax = net.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            total = net.add(tax);
        }
        jdbcTemplate.update("""
                update sales_quote set total_qty = ?, net_amount = ?, tax_amount = ?,
                       total_amount = ?, updated_at = now()
                 where id = ?
                """, totals.get("qty"), net, tax, total, quoteId);
    }

    private String normalizeCurrency(String currency) {
        String value = blankToNull(currency);
        if (value == null) {
            return "CNY";
        }
        String normalized = value.toUpperCase(java.util.Locale.ROOT);
        if (!List.of("CNY", "USD", "EUR").contains(normalized)) {
            throw new BizException(1002, "币种只支持 CNY / USD / EUR");
        }
        return normalized;
    }

    /** 转订单时统一按含税单价写入，保证订单金额等于报价价税合计 */
    private BigDecimal grossUnitPrice(BigDecimal price, Map<String, Object> quote) {
        boolean includesTax = !Boolean.FALSE.equals(quote.get("priceIncludesTax"));
        if (includesTax) {
            return price;
        }
        BigDecimal rate = quote.get("taxRate") == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(quote.get("taxRate")));
        return price.multiply(BigDecimal.ONE.add(rate)).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeTaxRate(BigDecimal taxRate) {
        if (taxRate == null) {
            return new BigDecimal("0.1300");
        }
        if (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(1002, "税率必须在 0 到 1 之间（如 13% 填 0.13）");
        }
        return taxRate;
    }

    private Map<String, Object> requireQuoteForUpdate(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, quote_no as "quoteNo", customer_id as "customerId", contact,
                       delivery_date as "deliveryDate", status, created_by as "createdBy",
                       review_due_date as "reviewDueDate", currency,
                       tax_rate as "taxRate", price_includes_tax as "priceIncludesTax"
                from sales_quote where id = ? for update
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(1002, "报价单不存在");
        }
        return rows.get(0);
    }

    private void requireStatus(Map<String, Object> quote, String message, String... allowed) {
        String status = String.valueOf(quote.get("status"));
        if (!List.of(allowed).contains(status)) {
            throw new BizException(1002, message + "（当前状态：" + status + "）");
        }
    }

    /**
     * 评审截止：公司时限（今天 + due_days）与客户交期前置（交期 - offset_days）同时生效，取更早的一个；
     * 计算结果早于今天时取今天，避免生成已过期的待办。
     */
    private LocalDate reviewDueDate(Map<String, Object> quote) {
        int dueDays = Math.max(configService.intValue("quotation.review.due_days", 3), 0);
        int offsetDays = Math.max(
                configService.intValue("quotation.review.delivery_offset_days", 2), 0);
        LocalDate today = LocalDate.now();
        LocalDate fixed = today.plusDays(dueDays);
        LocalDate delivery = quote.get("deliveryDate") == null ? null
                : ((java.sql.Date) quote.get("deliveryDate")).toLocalDate();
        LocalDate due = delivery == null ? fixed : delivery.minusDays(offsetDays);
        if (due.isAfter(fixed)) {
            due = fixed;
        }
        return due.isBefore(today) ? today : due;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
