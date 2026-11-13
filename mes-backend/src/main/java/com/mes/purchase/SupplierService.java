package com.mes.purchase;

import com.mes.common.BizException;
import com.mes.common.CodeGenerator;
import com.mes.common.PageResult;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 供应商档案：物料 / 外协 / 服务三类。
 * 已被采购单引用的供应商不允许删除，只能停用，保证历史单据可追溯。
 */
@Service
public class SupplierService {

    private static final List<String> CATEGORIES = List.of("MATERIAL", "OUTSOURCE", "SERVICE");
    private static final List<String> RATINGS = List.of("A", "B", "C");

    private final JdbcTemplate jdbcTemplate;
    private final CodeGenerator codeGenerator;

    public SupplierService(JdbcTemplate jdbcTemplate, CodeGenerator codeGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.codeGenerator = codeGenerator;
    }

    public record SupplierRequest(String code,
                                  @NotBlank String name,
                                  String category,
                                  String contact,
                                  String phone,
                                  String email,
                                  String address,
                                  String taxNo,
                                  String paymentTerm,
                                  Integer creditDays,
                                  Integer leadDays,
                                  String rating,
                                  Boolean enabled,
                                  String remark) {
        public SupplierRequest(String code, String name, String category, String contact, String phone,
                               String email, String address, String taxNo, String paymentTerm,
                               Integer leadDays, String rating, Boolean enabled, String remark) {
            this(code, name, category, contact, phone, email, address, taxNo, paymentTerm, null,
                    leadDays, rating, enabled, remark);
        }
    }

    public List<Map<String, Object>> list(String keyword, String category, String enabled) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        StringBuilder where = where(keyword, category, enabled, args);
        return jdbcTemplate.queryForList(supplierSelect() + where + " order by s.enabled desc, s.id",
                args.toArray());
    }

    /** 供应商列表分页：供应商会持续增加，列表页改为后端分页 */
    public PageResult page(String keyword, String category, String enabled, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        java.util.List<Object> countArgs = new java.util.ArrayList<>();
        StringBuilder where = where(keyword, category, enabled, countArgs);
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from supplier s " + where, Long.class, countArgs.toArray());
        java.util.List<Object> args = new java.util.ArrayList<>();
        StringBuilder pageWhere = where(keyword, category, enabled, args);
        args.add(safeSize);
        args.add((safePage - 1) * safeSize);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                supplierSelect() + pageWhere + " order by s.enabled desc, s.id limit ? offset ?",
                args.toArray());
        return new PageResult(records, total == null ? 0 : total, safePage, safeSize);
    }

    private String supplierSelect() {
        return """
                select s.id, s.code, s.name, s.category, s.contact, s.phone, s.email, s.address,
                       s.tax_no as "taxNo", s.payment_term as "paymentTerm", s.credit_days as "creditDays",
                       s.lead_days as "leadDays",
                       s.rating, s.enabled, s.remark,
                       (select count(*) from purchase_order o where o.supplier_id = s.id) as "orderCount",
                       (select max(o.order_date) from purchase_order o
                          where o.supplier_id = s.id and o.status <> 'CANCELLED') as "lastOrderDate",
                       to_char(s.created_at, 'YYYY-MM-DD') as "createdAt"
                from supplier s
                """;
    }

    private StringBuilder where(String keyword, String category, String enabled, java.util.List<Object> args) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        StringBuilder where = new StringBuilder(
                " where (s.code ilike ? or s.name ilike ? or coalesce(s.contact, '') ilike ?) ");
        args.add(like);
        args.add(like);
        args.add(like);
        if (category != null && !category.isBlank()) {
            where.append(" and s.category = ?");
            args.add(category.trim().toUpperCase(Locale.ROOT));
        }
        if ("true".equalsIgnoreCase(enabled) || "false".equalsIgnoreCase(enabled)) {
            where.append(" and s.enabled = ?");
            args.add(Boolean.parseBoolean(enabled));
        }
        return where;
    }

    public List<Map<String, Object>> options() {
        return options(null, 0);
    }

    /** 引用选择专用：关键字过滤 + 条数上限；limit <= 0 时不限条数（兼容脚本与老调用） */
    public List<Map<String, Object>> options(String keyword, int limit) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        String limitClause = limit > 0 ? " limit " + Math.min(limit, 50) : "";
        return jdbcTemplate.queryForList("""
                select id, code, name, category, lead_days as "leadDays", rating
                from supplier
                where enabled = true and (code ilike ? or name ilike ?)
                order by code
                """ + limitClause, like, like);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, code, name, category, contact, phone, email, address,
                       tax_no as "taxNo", payment_term as "paymentTerm", credit_days as "creditDays",
                       lead_days as "leadDays",
                       rating, enabled, remark
                from supplier where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException(2006, "供应商不存在");
        }
        return rows.get(0);
    }

    @Transactional
    public long create(SupplierRequest request) {
        validate(request);
        String code = request.code() == null || request.code().isBlank()
                ? codeGenerator.next("SUP") : request.code().trim();
        Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from supplier where code = ?", Integer.class, code);
        if (exists != null && exists > 0) {
            throw new BizException(2006, "供应商编码已存在");
        }
        return jdbcTemplate.queryForObject("""
                insert into supplier (code, name, category, contact, phone, email, address, tax_no,
                                      payment_term, credit_days, lead_days, rating, enabled, remark)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, code, request.name().trim(),
                categoryOf(request.category()), blankToNull(request.contact()), blankToNull(request.phone()),
                blankToNull(request.email()), blankToNull(request.address()), blankToNull(request.taxNo()),
                blankToNull(request.paymentTerm()), creditDaysOf(request.creditDays()),
                leadDaysOf(request.leadDays()), ratingOf(request.rating()),
                request.enabled() == null || request.enabled(), blankToNull(request.remark()));
    }

    @Transactional
    public void update(long id, SupplierRequest request) {
        validate(request);
        if (request.code() == null || request.code().isBlank()) {
            throw new BizException(1002, "供应商编码不能为空");
        }
        Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from supplier where code = ? and id <> ?", Integer.class,
                request.code().trim(), id);
        if (exists != null && exists > 0) {
            throw new BizException(2006, "供应商编码已存在");
        }
        int rows = jdbcTemplate.update("""
                update supplier set code = ?, name = ?, category = ?, contact = ?, phone = ?, email = ?,
                       address = ?, tax_no = ?, payment_term = ?, credit_days = ?, lead_days = ?, rating = ?,
                       enabled = ?, remark = ?
                where id = ?
                """, request.code().trim(), request.name().trim(), categoryOf(request.category()),
                blankToNull(request.contact()), blankToNull(request.phone()), blankToNull(request.email()),
                blankToNull(request.address()), blankToNull(request.taxNo()), blankToNull(request.paymentTerm()),
                creditDaysOf(request.creditDays()), leadDaysOf(request.leadDays()), ratingOf(request.rating()),
                request.enabled() == null || request.enabled(), blankToNull(request.remark()), id);
        if (rows == 0) {
            throw new BizException(2006, "供应商不存在");
        }
    }

    @Transactional
    public void delete(long id) {
        Integer used = jdbcTemplate.queryForObject(
                "select count(*) from purchase_order where supplier_id = ?", Integer.class, id);
        if (used != null && used > 0) {
            throw new BizException(2004, "该供应商已有采购订单，不能删除，可改为停用");
        }
        jdbcTemplate.update("delete from supplier where id = ?", id);
    }

    private void validate(SupplierRequest request) {
        if (request.name() == null || request.name().trim().isEmpty()) {
            throw new BizException(1002, "供应商名称不能为空");
        }
        String category = categoryOf(request.category());
        if (!CATEGORIES.contains(category)) {
            throw new BizException(1002, "供应商类型只能是 MATERIAL / OUTSOURCE / SERVICE");
        }
        if (!RATINGS.contains(ratingOf(request.rating()))) {
            throw new BizException(1002, "供应商评级只能是 A / B / C");
        }
        if (request.leadDays() != null && request.leadDays() < 0) {
            throw new BizException(1002, "交期天数不能为负数");
        }
        if (request.phone() != null && !request.phone().trim().isEmpty()
                && !request.phone().trim().matches("1[3-9]\\d{9}")) {
            throw new BizException(1002, "联系电话格式不正确");
        }
    }

    private static String categoryOf(String category) {
        if (category == null || category.isBlank()) {
            return "MATERIAL";
        }
        return category.trim().toUpperCase(Locale.ROOT);
    }

    private static String ratingOf(String rating) {
        if (rating == null || rating.isBlank()) {
            return "B";
        }
        return rating.trim().toUpperCase(Locale.ROOT);
    }

    private static int leadDaysOf(Integer leadDays) {
        return leadDays == null ? 7 : leadDays;
    }

    private static int creditDaysOf(Integer creditDays) {
        return creditDays == null ? 30 : creditDays;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
