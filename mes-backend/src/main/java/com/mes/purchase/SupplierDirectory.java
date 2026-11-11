package com.mes.purchase;

import com.mes.purchase.api.SupplierDirectoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 供应商档案的薄实现：只碰 {@code supplier}，不带任何依赖。
 *
 * <p>给基础数据的导入导出用。刻意不复用 {@code SupplierService}（那边带着编码规则、
 * 停用校验等业务判断），这里只做「按编码读 / 按编码写」，避免基础数据反向依赖采购的业务类。</p>
 */
@Component
public class SupplierDirectory implements SupplierDirectoryPort {

    private final JdbcTemplate jdbcTemplate;

    public SupplierDirectory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SupplierRow> all() {
        List<SupplierRow> rows = new ArrayList<>();
        jdbcTemplate.query("""
                select id, code, name, category, contact, phone, email, address, tax_no,
                       payment_term, credit_days, lead_days, rating
                from supplier order by code
                """, rs -> {
            rows.add(new SupplierRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                    rs.getString("category"), rs.getString("contact"), rs.getString("phone"),
                    rs.getString("email"), rs.getString("address"), rs.getString("tax_no"),
                    rs.getString("payment_term"),
                    rs.getObject("credit_days") == null ? null : rs.getInt("credit_days"),
                    rs.getObject("lead_days") == null ? null : rs.getInt("lead_days"),
                    rs.getString("rating")));
        });
        return rows;
    }

    @Override
    public Long idByCode(String code) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from supplier where code = ?", Long.class, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Override
    public void upsert(SupplierRow row) {
        jdbcTemplate.update("""
                insert into supplier (code, name, category, contact, phone, email, address, tax_no,
                                      payment_term, credit_days, lead_days, rating, enabled)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)
                on conflict (code) do update set
                    name = excluded.name, category = excluded.category, contact = excluded.contact,
                    phone = excluded.phone, email = excluded.email, address = excluded.address,
                    tax_no = excluded.tax_no, payment_term = excluded.payment_term,
                    credit_days = excluded.credit_days, lead_days = excluded.lead_days,
                    rating = excluded.rating
                """, row.code(), row.name(), row.category(), row.contact(), row.phone(), row.email(),
                row.address(), row.taxNo(), row.paymentTerm(), row.creditDays(), row.leadDays(), row.rating());
    }
}
