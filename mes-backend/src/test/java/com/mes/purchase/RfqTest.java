package com.mes.purchase;

import com.mes.common.BizException;
import com.mes.purchase.api.RequisitionDraft;
import com.mes.purchase.api.RequisitionLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 询价比价：从申请生成询价、多家报价与覆盖、选定后写价格库并回填申请、
 * 选定后禁止改价重选、取消与重复询价护栏。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RfqTest {

    @Autowired
    private RequisitionService requisitionService;

    @Autowired
    private RfqService rfqService;

    @Autowired
    private JdbcTemplate jdbc;

    private long supplierA;
    private long supplierB;

    @BeforeEach
    void setUp() {
        supplierA = jdbc.queryForObject("select id from supplier where code = 'S001'", Long.class);
        supplierB = jdbc.queryForObject("select id from supplier where code = 'S002'", Long.class);
    }

    @Test
    @DisplayName("从申请生成询价单：只带需询价且有剩余的行，重复发起被拒")
    void createFromRequisition() {
        long reqId = approvedRequisition(5L, "100", "0.8", true);
        long rfqId = rfqService.createFromRequisition(reqId,
                new RfqService.CreateRequest(null, LocalDate.now().plusDays(5), "询价测试"));

        Map<String, Object> detail = rfqService.detail(rfqId);
        assertEquals("OPEN", detail.get("status"));
        assertEquals(1, ((List<?>) detail.get("items")).size());
        assertEquals(reqId, ((Number) detail.get("requisitionId")).longValue());

        BizException duplicate = assertThrows(BizException.class,
                () -> rfqService.createFromRequisition(reqId, new RfqService.CreateRequest(null, null, null)));
        assertEquals(1002, duplicate.getCode());
        assertTrue(duplicate.getMessage().contains("没有可询价"));
    }

    @Test
    @DisplayName("两家报价：合计正确，同一供应商重新报价覆盖旧价")
    void quotesAndOverwrite() {
        long reqId = approvedRequisition(5L, "100", "0.8", true);
        long rfqId = rfqService.createFromRequisition(reqId, new RfqService.CreateRequest(null, null, null));
        long itemId = itemId(rfqService.detail(rfqId));

        Map<String, Object> first = rfqService.upsertQuote(rfqId, quote(supplierA, itemId, "0.78", 7));
        assertEquals(0, new BigDecimal(String.valueOf(first.get("totalAmount"))).compareTo(new BigDecimal("78")));
        rfqService.upsertQuote(rfqId, quote(supplierB, itemId, "0.82", 5));

        Map<String, Object> detail = rfqService.detail(rfqId);
        assertEquals("QUOTED", detail.get("status"));
        assertEquals(2, ((List<?>) detail.get("quotes")).size());
        assertNotNull(detail.get("bestQuoteId"));

        rfqService.upsertQuote(rfqId, quote(supplierA, itemId, "0.75", 7));
        Map<String, Object> after = rfqService.detail(rfqId);
        assertEquals(2, ((List<?>) after.get("quotes")).size());
        assertEquals(0, new BigDecimal(String.valueOf(after.get("bestTotal"))).compareTo(new BigDecimal("75")));
    }

    @Test
    @DisplayName("选定报价：价格写入价格库，申请行目标价与建议供应商回填")
    void selectQuoteWritesPriceBook() {
        long reqId = approvedRequisition(5L, "100", "0.8", true);
        long rfqId = rfqService.createFromRequisition(reqId, new RfqService.CreateRequest(null, null, null));
        long itemId = itemId(rfqService.detail(rfqId));
        long quoteId = ((Number) rfqService.upsertQuote(rfqId, quote(supplierA, itemId, "0.78", 7))
                .get("quoteId")).longValue();
        rfqService.upsertQuote(rfqId, quote(supplierB, itemId, "0.82", 5));

        Map<String, Object> result = rfqService.selectQuote(rfqId,
                new RfqService.SelectRequest(quoteId));
        assertEquals(1, ((Number) result.get("priceCount")).intValue());

        Map<String, Object> detail = rfqService.detail(rfqId);
        assertEquals("SELECTED", detail.get("status"));
        assertEquals(quoteId, ((Number) detail.get("selectedQuoteId")).longValue());

        Map<String, Object> reqDetail = requisitionService.detail(reqId);
        Map<String, Object> reqItem = ((List<Map<String, Object>>) reqDetail.get("items")).get(0);
        assertEquals(0, new BigDecimal(String.valueOf(reqItem.get("targetPrice"))).compareTo(new BigDecimal("0.7800")));
        assertEquals(supplierA, ((Number) reqItem.get("suggestSupplierId")).longValue());

        List<Map<String, Object>> prices = rfqService.prices(supplierA, 5L, null, true, 10);
        assertFalse(prices.isEmpty());
        assertEquals(0, new BigDecimal(String.valueOf(prices.get(0).get("price")))
                .compareTo(new BigDecimal("0.7800")));
        assertEquals(true, prices.get(0).get("valid"));

        BizException reselect = assertThrows(BizException.class,
                () -> rfqService.selectQuote(rfqId, new RfqService.SelectRequest(quoteId)));
        assertEquals(2014, reselect.getCode());
        BizException quoteAgain = assertThrows(BizException.class,
                () -> rfqService.upsertQuote(rfqId, quote(supplierA, itemId, "0.7", 7)));
        assertEquals(2014, quoteAgain.getCode());
        BizException cancel = assertThrows(BizException.class, () -> rfqService.cancel(rfqId));
        assertEquals(2014, cancel.getCode());
    }

    @Test
    @DisplayName("已选定不能删除报价，未选定时删除最后一条报价回到待报价")
    void deleteQuoteHandling() {
        long reqId = approvedRequisition(6L, "50", "1.2", true);
        long rfqId = rfqService.createFromRequisition(reqId, new RfqService.CreateRequest(null, null, null));
        long itemId = itemId(rfqService.detail(rfqId));
        long quoteId = ((Number) rfqService.upsertQuote(rfqId, quote(supplierA, itemId, "1.1", 5))
                .get("quoteId")).longValue();

        rfqService.deleteQuote(rfqId, quoteId);
        assertEquals("OPEN", rfqService.detail(rfqId).get("status"));

        long again = ((Number) rfqService.upsertQuote(rfqId, quote(supplierA, itemId, "1.1", 5))
                .get("quoteId")).longValue();
        rfqService.selectQuote(rfqId, new RfqService.SelectRequest(again));
        BizException deleteAfterSelect = assertThrows(BizException.class,
                () -> rfqService.deleteQuote(rfqId, again));
        assertEquals(2014, deleteAfterSelect.getCode());
    }

    @Test
    @DisplayName("取消询价后不能再报价")
    void cancelStopsQuotes() {
        long reqId = approvedRequisition(6L, "20", "1.2", true);
        long rfqId = rfqService.createFromRequisition(reqId, new RfqService.CreateRequest(null, null, null));
        long itemId = itemId(rfqService.detail(rfqId));
        rfqService.cancel(rfqId);
        assertEquals("CANCELLED", rfqService.detail(rfqId).get("status"));
        BizException error = assertThrows(BizException.class,
                () -> rfqService.upsertQuote(rfqId, quote(supplierA, itemId, "1.1", 5)));
        assertEquals(2014, error.getCode());
    }

    private long approvedRequisition(long productId, String qty, String price, boolean needQuote) {
        long id = requisitionService.create(new RequisitionDraft(
                "STOCK", "计划部", null, LocalDate.now().plusDays(3), "询价测试", null,
                List.of(new RequisitionLine(productId, new BigDecimal(qty),
                        new BigDecimal(price), supplierA, needQuote, null)))).id();
        requisitionService.submit(id);
        requisitionService.approve(id);
        return id;
    }

    private RfqService.QuoteRequest quote(long supplierId, long rfqItemId, String price, int leadDays) {
        return new RfqService.QuoteRequest(supplierId, leadDays, "月结 30 天",
                LocalDate.now().plusDays(30), null,
                List.of(new RfqService.QuoteItem(rfqItemId, new BigDecimal(price), null)));
    }

    private static long itemId(Map<String, Object> detail) {
        return ((Number) ((List<Map<String, Object>>) detail.get("items")).get(0).get("id")).longValue();
    }
}
