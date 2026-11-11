package com.mes.inventory.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 库存模块对外契约。
 *
 * <p>这是「将来能拆成微服务」的第一个样板：以前生产、仓储、采购、发货、退货、质检、基础数据
 * 七个模块直接注入 {@code InventoryService} 这个实现类，等于把「库存怎么落库」焊死在调用方。
 * 现在调用方只依赖这个接口，实现可以换（本地实现 / 远程 Feign / 消息驱动），
 * 调用方一行不用改。</p>
 *
 * <p><b>约定</b></p>
 * <ul>
 *   <li>只暴露「改变库存」与「查库存数量」两类能力；库存自己的页面（列表、流水）不进契约，
 *       但别的模块要判断「货够不够」用的聚合读（{@link #totalStocks}、{@link #availableStocks}、
 *       {@link #stocksIn}）必须进 —— 否则每个调用方都会去写一条 {@code select ... from inventory}；</li>
 *   <li>库位是仓库与库存共同承担的能力（库位表归仓储、批次归属归库存），所以「按库位看批次」
 *       「移库时的批次拆分」这类读写也进契约，不然仓储只能自己去改 {@code inventory_batch}；</li>
 *   <li>入参与返回全是基础类型或 {@code api} 包里的 DTO，不出现实现类；
 *       不返回 {@code Map<String, Object>} 这种无类型行数据（{@code Map<Long, BigDecimal>}
 *       这类「键是 id、值有确定类型」的聚合结果除外，它能被序列化）</li>
 *   <li>所有方法都在调用方的事务里执行，调用方要么整体提交要么整体回滚。</li>
 * </ul>
 *
 * <p><b>拆出去时要注意</b>：这些方法现在依赖本地事务（{@code select ... for update} 行锁 + 同事务写流水）。
 * 改成远程调用后，要么由库存服务自己开事务并保证幂等（用 {@code refType + refNo} 做幂等键），
 * 要么改成「下单 → 消息 → 库存确认」的最终一致，届时调用方语义要从「同步成功」调整为「受理成功」。</p>
 */
public interface InventoryPort {

    /** 某产品在某仓的结存数量 */
    BigDecimal stock(long productId, long warehouseId);

    /**
     * 一批产品在**所有仓库**的结存合计，返回 productId → qty。
     *
     * <p>缺料预警、成本分析、看板这类判断只关心「总量够不够」，不关心在哪个仓；
     * 没有库存记录的产品不会出现在返回里，调用方用 {@code getOrDefault(id, ZERO)} 取。</p>
     */
    Map<Long, BigDecimal> totalStocks(Collection<Long> productIds);

    /** 同上，但返回可用量（结存 - 锁定），MRP 算净需求用 */
    Map<Long, BigDecimal> availableStocks(Collection<Long> productIds);

    /** 某仓的库存行（含结存为 0 的行），盘点与仓库概览用；要不要过滤 0 由调用方决定 */
    List<StockLine> stocksIn(long warehouseId);

    /** 全仓库存行，看板这类跨仓统计用 */
    List<StockLine> allStocks();

    /** 按仓库类型取仓库 id（例如 RAW / FIN / SEMI），找不到时抛业务异常 */
    long warehouseOfType(String type);

    /** 入库并累加批次库存，返回批次号 */
    String inboundWithBatch(long productId, long warehouseId, BigDecimal qty, String refType, String refNo,
                            String remark, String batchNo, String source, Long workOrderId);

    /** 带成本入库：采购收货传实际单价、工单完工传工单单位成本 */
    String inboundWithBatch(long productId, long warehouseId, BigDecimal qty, String refType, String refNo,
                            String remark, String batchNo, String source, Long workOrderId,
                            BigDecimal unitCost);

    /** 手工出库 */
    void outbound(long productId, long warehouseId, BigDecimal qty, String refType, String refNo, String remark);

    /** 兼容入口：正数入库、负数按批次先进先出出库 */
    void change(long productId, long warehouseId, BigDecimal delta, String type,
                String refType, String refNo, String remark);

    /** 按批次先进先出扣减，逐批次记流水 */
    List<BatchMove> consumeFifo(long productId, long warehouseId, BigDecimal qty, String type,
                                String refType, String refNo, String remark);

    /** issueId 非空时，写出的库存流水会回指生产领料单 */
    List<BatchMove> consumeFifo(long productId, long warehouseId, BigDecimal qty, String type,
                                String refType, String refNo, String remark, Long issueId);

    /** 生产领料：扣批次并把消耗的批次记到工单上，成品才能往上追溯到原料 */
    List<BatchMove> issueAndTrack(long productId, long warehouseId, BigDecimal qty, String workNo,
                                  String remark, Long issueId);

    /** 生产退料（按原批次）：沿用原领料批次号，追溯链不断 */
    String returnMaterialBatch(long productId, long warehouseId, BigDecimal qty, String workNo,
                               String batchNo, Long returnId);

    /** 仓库间调拨：源仓按批次扣，目标仓沿用同一批次入库 */
    List<BatchMove> transfer(long productId, long fromWarehouseId, long toWarehouseId,
                             BigDecimal qty, String transferNo);

    /** 销售发货：扣成品批次，返回实际扣减的批次明细 */
    List<BatchMove> ship(long productId, long warehouseId, BigDecimal qty, String shipmentNo);

    /** 退货入库 */
    void returnIn(long productId, long warehouseId, BigDecimal qty, String returnNo);

    // ---------------------------------------------------------------- 库位（批次上架 / 移库）

    /** 批次所在库位与 quantity：库位页展示用，库位编码仍在仓储模块自己拼 */
    record BatchAtLocation(long id, long productId, long warehouseId, String batchNo, BigDecimal qty,
                           String source, Long locationId, String createdAt) {
    }

    /** 某库位上的批次占用：批次数 + 正库存合计 */
    record LocationOccupancy(long batchCount, BigDecimal stockQty) {
    }

    /** 库位占用概览：已上架 / 未指定库位的数量与批次数 */
    record LocationUsage(BigDecimal assignedQty, BigDecimal unassignedQty,
                         long assignedBatches, long unassignedBatches) {
    }

    /** 批次定位结果：移库要先拿到 id、当前数量与所在库位 */
    record BatchRef(long id, long productId, long warehouseId, String batchNo, BigDecimal qty,
                    Long locationId) {
    }

    /**
     * 按库位列出批次（只返回 qty > 0 的）。
     *
     * @param warehouseId 为空表示不限仓库
     * @param productIds  物料过滤，空集表示不按物料过滤
     * @param batchNoLike 批次号模糊匹配，为空表示不过滤
     */
    List<BatchAtLocation> locationBatches(Long warehouseId, Collection<Long> productIds, String batchNoLike);

    /** 批量取库位占用 */
    Map<Long, LocationOccupancy> locationOccupancy(Collection<Long> locationIds);

    /** 库位占用概览；warehouseId 为空表示全仓合计 */
    LocationUsage locationUsage(Long warehouseId);

    /** 某库位上还有库存的批次数 */
    long batchesInLocation(long locationId);

    /** 清空某库位的批次归属（删库位前用） */
    void detachLocation(long locationId);

    /** 按「产品 + 仓库 + 批次号」定位批次并加行锁，找不到返回 null */
    BatchRef batchForUpdate(long productId, long warehouseId, String batchNo);

    /** 按「产品 + 仓库 + 批次号」把批次挂到某库位（入库上架用，不加行锁） */
    void assignLocation(long productId, long warehouseId, String batchNo, long locationId);

    /** 整批换库位 */
    void relocateBatch(long batchId, long toLocationId);

    /**
     * 部分移库：原批次扣减，目标库位建（或累加）一个新批次。
     *
     * @param splitBatchNo 新批次号（仓储按自己的命名规则给，通常原批次号加库位尾号）
     */
    void splitBatchToLocation(long batchId, String splitBatchNo, BigDecimal qty, long toLocationId);
}
