package com.mes.warehouse.api;

import com.mes.platform.event.DomainEvent;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 入库单完成入库。
 *
 * <p>发布方是仓储模块，订阅方是采购（回写采购明细实收、刷新采购单状态）。
 * 以前是仓储直接注入 {@code PurchaseOrderService} 调用，两个模块编译期绑死；
 * 改成事件后仓储只依赖这个事件类，采购反向订阅，依赖方向变成单向，拆服务时不用改仓储。</p>
 *
 * @param inboundOrderId 入库单 id
 * @param inboundNo      入库单号
 * @param itemCount      明细行数，排查用
 * @param completedAt    入库完成时间
 */
public record InboundCompleted(long inboundOrderId, String inboundNo, int itemCount,
                               OffsetDateTime completedAt) implements DomainEvent {

    public static final String TYPE = "INBOUND_COMPLETED";

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "INBOUND_ORDER";
    }

    @Override
    public String aggregateId() {
        return inboundNo == null ? String.valueOf(inboundOrderId) : inboundNo;
    }

    @Override
    public Map<String, Object> payload() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inboundOrderId", inboundOrderId);
        data.put("inboundNo", inboundNo);
        data.put("itemCount", itemCount);
        data.put("completedAt", completedAt == null ? null : completedAt.toString());
        return data;
    }
}
