package com.mes.warehouse.api;

/**
 * 入库完成的对外契约。
 *
 * <p>需要「把这张入库单整单入库」的不只仓储自己：来料检验合格后由质检触发。
 * 质检不该知道入库单的库存流水、库位上架、事件发布这些细节，
 * 所以只依赖这个接口。</p>
 *
 * <p>调用方必须在自己的事务里调用：入库成功与检验结论要么一起提交，要么一起回滚。</p>
 */
public interface InboundCompletionPort {

    /**
     * 整单入库：写库存流水、上架库位、置单据为已完成，并发布「入库完成」事件。
     *
     * @param inboundOrderId 入库单 id
     * @param inspectionNo   检验单号，免检场景传 null
     */
    void completeInbound(long inboundOrderId, String inspectionNo);
}
