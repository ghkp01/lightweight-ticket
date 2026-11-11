package com.mes.finance;

import com.mes.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应付侧凭证同步：采购发票（已匹配）与付款单补记总账凭证。
 *
 * <p>采购模块不直接依赖财务，财务按只读模型 rm_ap_invoice / rm_ap_payment 扫描，
 * 通过 (biz_type, biz_id) 幂等补记，可以随时重复执行。</p>
 */
@Service
public class ApVoucherSyncService {

    private final JdbcTemplate jdbcTemplate;
    private final PostingService postingService;

    public ApVoucherSyncService(JdbcTemplate jdbcTemplate, PostingService postingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.postingService = postingService;
    }

    public Map<String, Object> sync() {
        int invoicePosted = 0;
        int paymentPosted = 0;
        List<String> skipped = new ArrayList<>();

        List<Map<String, Object>> invoices = jdbcTemplate.queryForList("""
                select i.id, i.invoice_no as "invoiceNo", i.invoice_date as "invoiceDate",
                       i.net_amount as "netAmount", i.tax_amount as "taxAmount",
                       i.gross_amount as "grossAmount"
                from rm_ap_invoice i
                where i.status in ('MATCHED', 'PARTIAL', 'PAID')
                  and not exists (select 1 from fin_voucher v
                                   where v.biz_type = 'AP_INVOICE' and v.biz_id = i.id)
                order by i.id
                """);
        for (Map<String, Object> invoice : invoices) {
            String invoiceNo = String.valueOf(invoice.get("invoiceNo"));
            try {
                BigDecimal net = money((BigDecimal) invoice.get("netAmount"));
                BigDecimal tax = money((BigDecimal) invoice.get("taxAmount"));
                BigDecimal gross = money((BigDecimal) invoice.get("grossAmount"));
                postingService.post(date(invoice.get("invoiceDate")), "AP_INVOICE",
                        ((Number) invoice.get("id")).longValue(), invoiceNo, "CNY", BigDecimal.ONE,
                        "采购发票 " + invoiceNo,
                        List.of(new PostingService.PostingLine("INVENTORY", "采购入库成本", net, BigDecimal.ZERO),
                                new PostingService.PostingLine("INPUT_TAX", "进项税额", tax, BigDecimal.ZERO),
                                new PostingService.PostingLine("AP", "采购应付", BigDecimal.ZERO, gross)));
                invoicePosted++;
            } catch (BizException error) {
                skipped.add("采购发票 " + invoiceNo + "：" + error.getMessage());
            }
        }

        List<Map<String, Object>> payments = jdbcTemplate.queryForList("""
                select p.id, p.payment_no as "paymentNo", p.pay_date as "payDate", p.amount
                from rm_ap_payment p
                where not exists (select 1 from fin_voucher v
                                   where v.biz_type = 'AP_PAYMENT' and v.biz_id = p.id)
                order by p.id
                """);
        for (Map<String, Object> payment : payments) {
            String paymentNo = String.valueOf(payment.get("paymentNo"));
            try {
                BigDecimal amount = money((BigDecimal) payment.get("amount"));
                postingService.post(date(payment.get("payDate")), "AP_PAYMENT",
                        ((Number) payment.get("id")).longValue(), paymentNo, "CNY", BigDecimal.ONE,
                        "采购付款 " + paymentNo,
                        List.of(new PostingService.PostingLine("AP", "采购付款", amount, BigDecimal.ZERO),
                                new PostingService.PostingLine("BANK", "采购付款", BigDecimal.ZERO, amount)));
                paymentPosted++;
            } catch (BizException error) {
                skipped.add("付款单 " + paymentNo + "：" + error.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invoicePosted", invoicePosted);
        result.put("paymentPosted", paymentPosted);
        result.put("skipped", skipped);
        result.put("skippedCount", skipped.size());
        return result;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private LocalDate date(Object value) {
        if (value == null) {
            return LocalDate.now();
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
