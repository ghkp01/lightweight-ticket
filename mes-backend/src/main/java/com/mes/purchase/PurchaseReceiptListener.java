package com.mes.purchase;

import com.mes.platform.event.DomainEvent;
import com.mes.platform.event.DomainEventHandler;
import com.mes.warehouse.api.InboundCompleted;
import org.springframework.stereotype.Component;

/**
 * 采购订阅「入库完成」：回写明细实收数量并刷新采购单状态。
 *
 * <p>这段逻辑原来写在仓储的 {@code WarehouseService} 里（直接调 {@code PurchaseOrderService}），
 * 现在改成事件订阅：仓储不知道采购的存在，采购反过来依赖仓储的 api 包。
 * 将来把仓储拆成独立服务，这里只需要把「本地事件」换成「消息订阅」，业务代码不变。</p>
 */
@Component
public class PurchaseReceiptListener implements DomainEventHandler {

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseReceiptListener(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    @Override
    public String eventType() {
        return InboundCompleted.TYPE;
    }

    @Override
    public void handle(DomainEvent event) {
        if (event instanceof InboundCompleted inbound) {
            purchaseOrderService.applyReceipt(inbound.inboundOrderId());
        }
    }
}
