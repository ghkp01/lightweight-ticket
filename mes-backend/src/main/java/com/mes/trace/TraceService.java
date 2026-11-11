package com.mes.trace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次追溯：向上查这批用了哪些原料批次，向下查流到哪些工单、发给了哪些客户。
 * 原料批次来自入库单，成品批次约定为工单号，两者在领料时建立关联。
 */
@Service
public class TraceService {

    private final JdbcTemplate jdbcTemplate;

    public TraceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按编码自动分派：WO 开头查工单，SH 开头查发货，其余按批次查 */
    public Map<String, Object> query(String code) {
        String value = code == null ? "" : code.trim();
        if (value.isEmpty()) {
            return Map.of("kind", "EMPTY", "message", "请输入批次号、工单号或发货单号");
        }
        String upper = value.toUpperCase();
        if (upper.startsWith("WO")) {
            return traceWorkOrder(value);
        }
        if (upper.startsWith("SH")) {
            return traceShipment(value);
        }
        return traceBatch(value);
    }

    public Map<String, Object> traceBatch(String batchNo) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "BATCH");
        result.put("batchNo", batchNo);

        List<Map<String, Object>> stocks = jdbcTemplate.queryForList("""
                select b.product_id as "productId", p.code as "productCode", p.name as "productName",
                       p.unit, w.name as "warehouseName", w.type as "warehouseType",
                       b.qty, b.source, b.work_order_id as "workOrderId", wo.work_no as "workNo",
                       to_char(b.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from rm_inventory_batch b
                join rm_product p on p.id = b.product_id
                join rm_warehouse w on w.id = b.warehouse_id
                left join rm_work_order wo on wo.id = b.work_order_id
                where b.batch_no = ? and b.qty <> 0
                order by b.warehouse_id, b.id
                """, batchNo);
        result.put("stocks", stocks);
        if (stocks.isEmpty()) {
            result.put("message", "库存里没有这个批次，可能是已发完或批次号有误");
        }

        // 向上：该批次若是成品批次（= 工单号），能查到投产时消耗的原料批次
        result.put("materials", materialBatchesOfBatch(batchNo));

        // 向下：作为原料被哪些工单用掉
        result.put("usedIn", jdbcTemplate.queryForList("""
                select wo.id as "workOrderId", wo.work_no as "workNo", wo.status,
                       p.code as "productCode", p.name as "productName", mb.qty,
                       to_char(mb.created_at, 'YYYY-MM-DD HH24:MI') as "usedAt"
                from rm_work_order_material_batch mb
                join rm_work_order wo on wo.id = mb.work_order_id
                join rm_product p on p.id = wo.product_id
                where mb.batch_no = ?
                order by mb.id
                """, batchNo));

        result.put("shipments", shipmentsOfBatch(batchNo));
        result.put("derivedShipments", shipmentsOfDerivedBatches(batchNo));
        return result;
    }

    public Map<String, Object> traceWorkOrder(String workNo) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "WORK_ORDER");
        result.put("workNo", workNo);

        List<Map<String, Object>> works = jdbcTemplate.queryForList("""
                select wo.id, wo.work_no as "workNo", wo.status, wo.plan_qty as "planQty",
                       wo.qualified_qty as "qualifiedQty", p.code as "productCode", p.name as "productName",
                       p.unit, wo.work_type as "workType",
                       to_char(wo.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt",
                       to_char(wo.updated_at, 'YYYY-MM-DD HH24:MI') as "updatedAt"
                from rm_work_order wo join rm_product p on p.id = wo.product_id
                where wo.work_no = ?
                """, workNo);
        if (works.isEmpty()) {
            result.put("message", "没有这张工单");
            return result;
        }
        Map<String, Object> work = works.get(0);
        result.put("workOrder", work);
        result.put("finishedBatch", workNo);

        List<Map<String, Object>> stocks = jdbcTemplate.queryForList("""
                select w.name as "warehouseName", b.qty,
                       to_char(b.created_at, 'YYYY-MM-DD HH24:MI') as "createdAt"
                from rm_inventory_batch b join rm_warehouse w on w.id = b.warehouse_id
                where b.batch_no = ? and b.qty <> 0
                """, workNo);
        result.put("stocks", stocks);
        result.put("materials", materialBatchesOfWork(workNo));
        result.put("shipments", shipmentsOfBatch(workNo));
        return result;
    }

    public Map<String, Object> traceShipment(String shipmentNo) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "SHIPMENT");
        result.put("shipmentNo", shipmentNo);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select s.id, s.shipment_no as "shipmentNo", s.status, c.name as "customerName",
                       so.order_no as "orderNo", s.carrier, s.tracking_no as "trackingNo",
                       to_char(s.shipped_at, 'YYYY-MM-DD HH24:MI') as "shippedAt",
                       to_char(s.signed_at, 'YYYY-MM-DD HH24:MI') as "signedAt"
                from rm_shipment s
                join rm_customer c on c.id = s.customer_id
                left join rm_sales_order so on so.id = s.sales_order_id
                where s.shipment_no = ?
                """, shipmentNo);
        if (rows.isEmpty()) {
            result.put("message", "没有这张发货单");
            return result;
        }
        long shipmentId = ((Number) rows.get(0).get("id")).longValue();
        result.put("shipment", rows.get(0));

        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select si.id, p.code as "productCode", p.name as "productName", si.qty
                from rm_shipment_item si join rm_product p on p.id = si.product_id
                where si.shipment_id = ?
                order by si.id
                """, shipmentId);
        for (Map<String, Object> item : items) {
            long itemId = ((Number) item.get("id")).longValue();
            item.put("batches", jdbcTemplate.queryForList("""
                    select batch_no as "batchNo", qty from shipment_batch
                    where shipment_item_id = ? order by id
                    """, itemId));
        }
        result.put("items", items);

        // 发货涉及的所有成品批次，再各自往上找原料
        List<Map<String, Object>> batches = jdbcTemplate.queryForList("""
                select distinct sb.batch_no as "batchNo"
                from shipment_batch sb join rm_shipment_item si on si.id = sb.shipment_item_id
                where si.shipment_id = ?
                """, shipmentId);
        for (Map<String, Object> batch : batches) {
            String batchNo = String.valueOf(batch.get("batchNo"));
            batch.put("materials", materialBatchesOfBatch(batchNo));
        }
        result.put("batches", batches);
        return result;
    }

    private List<Map<String, Object>> materialBatchesOfBatch(String batchNo) {
        return materialBatchesOfWork(batchNo);
    }

    private List<Map<String, Object>> materialBatchesOfWork(String workNo) {
        return jdbcTemplate.queryForList("""
                select mb.batch_no as "batchNo", p.code as "productCode", p.name as "productName",
                       p.unit, mb.qty,
                       (select count(*) from rm_inventory_batch b where b.batch_no = mb.batch_no) as "stockRows",
                       to_char(mb.created_at, 'YYYY-MM-DD HH24:MI') as "issuedAt"
                from rm_work_order_material_batch mb
                join rm_work_order wo on wo.id = mb.work_order_id
                join rm_product p on p.id = mb.product_id
                where wo.work_no = ?
                order by mb.id
                """, workNo);
    }

    private List<Map<String, Object>> shipmentsOfBatch(String batchNo) {
        return jdbcTemplate.queryForList("""
                select s.shipment_no as "shipmentNo", c.name as "customerName", so.order_no as "orderNo",
                       sb.qty, to_char(s.shipped_at, 'YYYY-MM-DD HH24:MI') as "shippedAt", s.status
                from shipment_batch sb
                join rm_shipment_item si on si.id = sb.shipment_item_id
                join rm_shipment s on s.id = si.shipment_id
                join rm_customer c on c.id = s.customer_id
                left join rm_sales_order so on so.id = s.sales_order_id
                where sb.batch_no = ?
                order by s.id desc
                """, batchNo);
    }

    /** 原料批次 → 工单 → 成品批次 → 发货客户，用于回答「这批料最后到了谁手上」 */
    private List<Map<String, Object>> shipmentsOfDerivedBatches(String batchNo) {
        return jdbcTemplate.queryForList("""
                select s.shipment_no as "shipmentNo", c.name as "customerName",
                       wo.work_no as "workNo", sb.batch_no as "batchNo", sb.qty,
                       to_char(s.shipped_at, 'YYYY-MM-DD HH24:MI') as "shippedAt"
                from rm_work_order_material_batch mb
                join rm_work_order wo on wo.id = mb.work_order_id
                join shipment_batch sb on sb.batch_no = wo.work_no
                join rm_shipment_item si on si.id = sb.shipment_item_id
                join rm_shipment s on s.id = si.shipment_id
                join rm_customer c on c.id = s.customer_id
                where mb.batch_no = ?
                order by s.id desc
                """, batchNo);
    }
}
