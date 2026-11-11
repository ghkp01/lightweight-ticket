package com.mes.sales;

import com.mes.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 报价单全流程：会签评审（全部通过才通过）、驳回重提、转销售订单。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QuotationTest {

    @Autowired
    private QuotationService quotes;

    @Autowired
    private SalesService sales;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("指定评审人通过后可转销售订单，并回链报价单")
    void quoteToOrder() {
        long customerId = customerId();
        long productId = productId();
        long techId = techUserId();
        login("admin", "ADMIN");
        long quoteId = createQuote(customerId, productId);
        Map<String, Object> detail = quotes.detail(quoteId);
        assertEquals("DRAFT", detail.get("status"));
        assertEquals(0, new BigDecimal("37.50").compareTo(
                new BigDecimal(String.valueOf(detail.get("totalAmount")))));
        // 未评审不能转订单
        assertThrows(BizException.class, () -> quotes.convert(quoteId));

        quotes.submit(quoteId, new QuotationService.SubmitRequest(List.of(techId)));
        assertEquals("SUBMITTED", quotes.detail(quoteId).get("status"));
        // 公司时限 3 天先于交期前置（10-2=8 天），取 3 天
        assertEquals(LocalDate.now().plusDays(3).toString(),
                String.valueOf(quotes.detail(quoteId).get("reviewDueDate")));

        login("tech", "TECH");
        assertEquals("PENDING", quotes.detail(quoteId).get("myReviewStatus"));
        quotes.review(quoteId, new QuotationService.ReviewRequest("PASS", "工艺可做"));
        Map<String, Object> approved = quotes.detail(quoteId);
        assertEquals("APPROVED", approved.get("status"));
        assertEquals(1, ((List<?>) approved.get("reviews")).size());

        login("admin", "ADMIN");
        long orderId = quotes.convert(quoteId);
        Map<String, Object> order = sales.detail(orderId);
        assertEquals(String.valueOf(approved.get("quoteNo")), order.get("quoteNo"));
        assertEquals("CNY", order.get("currency"));
        assertEquals("CONVERTED", quotes.detail(quoteId).get("status"));
    }

    @Test
    @DisplayName("外币报价转单把币种快照到订单，非系统币种直接拒绝")
    void foreignCurrencyCarriesToOrder() {
        long customerId = customerId();
        long productId = productId();
        long techId = techUserId();
        login("admin", "ADMIN");
        assertThrows(BizException.class, () -> quotes.create(new QuotationService.QuoteRequest(
                customerId, "王工", LocalDate.now().plusDays(30), LocalDate.now().plusDays(10),
                "非法币种",
                List.of(new QuotationService.QuoteItem(
                        productId, BigDecimal.ONE, new BigDecimal("10.00"), null)),
                "JPY", new BigDecimal("0.13"), true, null)));

        long quoteId = quotes.create(new QuotationService.QuoteRequest(
                customerId, "王工", LocalDate.now().plusDays(30), LocalDate.now().plusDays(10),
                "美元报价",
                List.of(new QuotationService.QuoteItem(
                        productId, new BigDecimal("3"), new BigDecimal("12.50"), null)),
                "USD", new BigDecimal("0.13"), false, null));
        quotes.submit(quoteId, new QuotationService.SubmitRequest(List.of(techId)));
        login("tech", "TECH");
        quotes.review(quoteId, new QuotationService.ReviewRequest("PASS", "美元单可做"));

        login("admin", "ADMIN");
        long orderId = quotes.convert(quoteId);
        Map<String, Object> order = sales.detail(orderId);
        assertEquals("USD", order.get("currency"));
        assertEquals(0, new BigDecimal("42.38").compareTo(
                new BigDecimal(String.valueOf(order.get("totalAmount")))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
        // 未税 12.50 转单后按 13% 换成含税单价，订单金额就是价税合计
        assertEquals(0, new BigDecimal("14.1250").compareTo(
                new BigDecimal(String.valueOf(items.get(0).get("price")))));
    }

    @Test
    @DisplayName("驳回必须写意见，修改后可重新提交")
    void rejectThenResubmit() {
        long customerId = customerId();
        long productId = productId();
        long techId = techUserId();
        login("admin", "ADMIN");
        long quoteId = createQuote(customerId, productId);
        quotes.submit(quoteId, new QuotationService.SubmitRequest(List.of(techId)));

        login("tech", "TECH");
        assertThrows(BizException.class,
                () -> quotes.review(quoteId, new QuotationService.ReviewRequest("REJECT", " ")));
        quotes.review(quoteId, new QuotationService.ReviewRequest("REJECT", "尺寸不满足"));
        assertEquals("REJECTED", quotes.detail(quoteId).get("status"));

        login("admin", "ADMIN");
        quotes.update(quoteId, new QuotationService.QuoteRequest(
                customerId, "李工", null, LocalDate.now().plusDays(5), "改小一档",
                List.of(new QuotationService.QuoteItem(
                        productId, BigDecimal.ONE, new BigDecimal("8.80"), null))));
        quotes.submit(quoteId, new QuotationService.SubmitRequest(List.of(techId)));
        assertEquals("SUBMITTED", quotes.detail(quoteId).get("status"));
    }

    @Test
    @DisplayName("会签：两个人全部通过才算通过；报价人不能评审自己")
    void countersign() {
        long customerId = customerId();
        long productId = productId();
        long techId = techUserId();
        long tech2Id = createTech("tech2");
        login("admin", "ADMIN");
        long quoteId = createQuote(customerId, productId);

        // 报价人（admin）不能作为评审人
        assertThrows(BizException.class,
                () -> quotes.submit(quoteId, new QuotationService.SubmitRequest(
                        List.of(userId("admin")))));

        quotes.submit(quoteId, new QuotationService.SubmitRequest(List.of(techId, tech2Id)));
        login("tech", "TECH");
        quotes.review(quoteId, new QuotationService.ReviewRequest("PASS", "第一人通过"));
        // 还有一人未评审，仍是待评审
        login("admin", "ADMIN");
        assertEquals("SUBMITTED", quotes.detail(quoteId).get("status"));

        login("tech2", "TECH");
        quotes.review(quoteId, new QuotationService.ReviewRequest("PASS", "第二人通过"));
        login("admin", "ADMIN");
        assertEquals("APPROVED", quotes.detail(quoteId).get("status"));
    }

    @Test
    @DisplayName("未指定的账号不能评审")
    void unassignedReviewerRejected() {
        login("admin", "ADMIN");
        long quoteId = createQuote(customerId(), productId());
        quotes.submit(quoteId, new QuotationService.SubmitRequest(List.of(techUserId())));
        login("planner", "PLANNER");
        assertThrows(BizException.class,
                () -> quotes.review(quoteId, new QuotationService.ReviewRequest("PASS", "越权评审")));
    }

    @Test
    @DisplayName("评审截止同时受公司时限与交期前置约束，取更早")
    void reviewDueDateRules() {
        login("admin", "ADMIN");
        long customerId = customerId();
        long productId = productId();
        long techId = techUserId();
        jdbc.update("update sys_config set config_value = '30' "
                + "where config_key = 'quotation.review.due_days'");

        // 交期 5 天后、提前 2 天 → 3 天后，早于公司时限 30 天
        long deliveryQuote = createQuote(customerId, productId, LocalDate.now().plusDays(5));
        quotes.submit(deliveryQuote, new QuotationService.SubmitRequest(List.of(techId)));
        assertEquals(LocalDate.now().plusDays(3).toString(),
                String.valueOf(quotes.detail(deliveryQuote).get("reviewDueDate")));

        // 没有交期时只用公司时限
        long noDelivery = createQuote(customerId, productId, null);
        quotes.submit(noDelivery, new QuotationService.SubmitRequest(List.of(techId)));
        assertEquals(LocalDate.now().plusDays(30).toString(),
                String.valueOf(quotes.detail(noDelivery).get("reviewDueDate")));
    }

    @Test
    @DisplayName("报价员只能看自己的报价")
    void quoterDataScope() {
        login("admin", "ADMIN");
        long quoteId = createQuote(customerId(), productId());
        String quoteNo = jdbc.queryForObject(
                "select quote_no from sales_quote where id = ?", String.class, quoteId);
        createUser("quotx", "QUOTER");

        login("quotx", "QUOTER");
        assertEquals(0, quotes.list(quoteNo, null, 1, 20).total());
        assertThrows(BizException.class, () -> quotes.detail(quoteId));
    }

    @Test
    @DisplayName("评审转办、加签、撤签与管理员代审")
    void reviewerOperations() {
        long customerId = customerId();
        long productId = productId();
        long techId = techUserId();
        long tech2Id = createTech("tech2x");
        login("admin", "ADMIN");
        long quoteId = createQuote(customerId, productId);
        quotes.submit(quoteId, new QuotationService.SubmitRequest(List.of(techId)));

        // 评审人本人可以把任务转给别人
        login("tech", "TECH");
        quotes.transferReviewer(quoteId, techId, tech2Id);

        login("admin", "ADMIN");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviewers =
                (List<Map<String, Object>>) quotes.detail(quoteId).get("reviewers");
        assertEquals(1, reviewers.size());
        assertEquals("PENDING", reviewers.get(0).get("status"));
        assertEquals(tech2Id, ((Number) reviewers.get(0).get("reviewerId")).longValue());

        // 加签后撤掉 tech2，只留 tech
        quotes.addReviewer(quoteId, techId);
        quotes.removeReviewer(quoteId, tech2Id);
        // 管理员代 tech 给出结论
        quotes.review(quoteId, new QuotationService.ReviewRequest("PASS", "代审通过", techId));
        Map<String, Object> approved = quotes.detail(quoteId);
        assertEquals("APPROVED", approved.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) approved.get("reviews");
        assertEquals(true, String.valueOf(reviews.get(0).get("reviewer")).contains("代审"));

        // 全部撤签后退回草稿
        long quote2 = createQuote(customerId, productId);
        quotes.submit(quote2, new QuotationService.SubmitRequest(List.of(techId)));
        quotes.removeReviewer(quote2, techId);
        assertEquals("DRAFT", quotes.detail(quote2).get("status"));
    }

    @Test
    @DisplayName("评审提醒：到期前提醒评审人")
    void reviewReminder() {
        long techId = techUserId();
        login("admin", "ADMIN");
        long quoteId = createQuote(customerId(), productId());
        String quoteNo = jdbc.queryForObject(
                "select quote_no from sales_quote where id = ?", String.class, quoteId);
        quotes.submit(quoteId, new QuotationService.SubmitRequest(List.of(techId)));
        boolean found = quotes.reviewReminders(3).stream()
                .anyMatch(item -> item.quoteNo().equals(quoteNo)
                        && !item.overdue()
                        && item.pendingReviewers().contains("tech"));
        assertEquals(true, found);
    }

    @Test
    @DisplayName("未税报价自动拆税额，金额口径正确")
    void exclusiveTax() {
        login("admin", "ADMIN");
        long quoteId = quotes.create(new QuotationService.QuoteRequest(
                customerId(), "王工", LocalDate.now().plusDays(30), LocalDate.now().plusDays(10),
                "未税报价",
                List.of(new QuotationService.QuoteItem(
                        productId(), new BigDecimal("3"), new BigDecimal("12.50"), null)),
                "CNY", new BigDecimal("0.13"), false, "月结 30 天"));
        Map<String, Object> detail = quotes.detail(quoteId);
        assertEquals("CNY", detail.get("currency"));
        assertEquals(0, new BigDecimal("0.1300").compareTo(
                new BigDecimal(String.valueOf(detail.get("taxRate")))));
        assertEquals(false, detail.get("priceIncludesTax"));
        assertEquals("月结 30 天", detail.get("paymentTerms"));
        assertEquals(0, new BigDecimal("37.50").compareTo(
                new BigDecimal(String.valueOf(detail.get("netAmount")))));
        assertEquals(0, new BigDecimal("4.88").compareTo(
                new BigDecimal(String.valueOf(detail.get("taxAmount")))));
        assertEquals(0, new BigDecimal("42.38").compareTo(
                new BigDecimal(String.valueOf(detail.get("totalAmount")))));
    }

    // ---------------------------------------------------------------- 工具

    private long createQuote(long customerId, long productId) {
        return createQuote(customerId, productId, LocalDate.now().plusDays(10));
    }

    private long createQuote(long customerId, long productId, LocalDate deliveryDate) {
        return quotes.create(new QuotationService.QuoteRequest(
                customerId, "王工", LocalDate.now().plusDays(30), deliveryDate, "报价测试",
                List.of(new QuotationService.QuoteItem(
                        productId, new BigDecimal("3"), new BigDecimal("12.50"), "含运费"))));
    }

    private long customerId() {
        return jdbc.queryForObject("select id from customer order by id limit 1", Long.class);
    }

    private long productId() {
        return jdbc.queryForObject("select id from product order by id limit 1", Long.class);
    }

    private long userId(String username) {
        return jdbc.queryForObject(
                "select id from sys_user where username = ?", Long.class, username);
    }

    /** 测试库可能没有 DataInitializer 建的 tech，缺失时补一个，保证用例稳定 */
    private long techUserId() {
        List<Long> ids = jdbc.queryForList(
                "select id from sys_user where username = 'tech'", Long.class);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        return jdbc.queryForObject("""
                insert into sys_user (username, password, real_name, role, enabled, must_change_password)
                values ('tech', 'x', '技术-吴工', 'TECH', true, true)
                returning id
                """, Long.class);
    }

    private long createTech(String username) {
        return createUser(username, "TECH");
    }

    private long createUser(String username, String role) {
        return jdbc.queryForObject("""
                insert into sys_user (username, password, real_name, role, enabled, must_change_password)
                values (?, 'x', ?, ?, true, true)
                returning id
                """, Long.class, username, "测试-" + username, role);
    }

    private void login(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
