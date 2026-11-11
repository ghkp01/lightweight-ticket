package com.mes.production.api;

import com.mes.inventory.api.BatchMove;

import java.util.Collection;
import java.util.Map;

/**
 * 领料批次绑定的对外契约（只覆盖「工单号 ↔ 工单 id」与批次绑定两步）。
 *
 * <p>为什么单独一个接口、而不是塞进 {@link WorkOrderPort}：
 * 库存模块在扣减批次之后要把「这批料用在哪张工单上」记到 {@code work_order_material_batch}，
 * 这才是成品往上追溯原料的链路。而 {@link WorkOrderPort} 的实现在生产侧、它又依赖库存契约（扣减、查结存），
 * 两边互相注入会成构造环。所以把这两个动作拆成独立的小端口，由生产侧一个只带 JdbcTemplate 的
 * 薄实现发布 —— 依赖方向单向，将来拆服务时它就是「领料绑定」这个远程接口。</p>
 */
public interface WorkOrderIssuePort {

    /** 按工单号取工单 id，找不到返回 null */
    Long idByWorkNo(String workNo);

    /** 批量取工单号，返回 id → 工单号；找不到的不在结果里 */
    Map<Long, String> workNos(Collection<Long> workOrderIds);

    /** 把已扣减的批次绑定到工单上；工单号找不到时静默忽略（不阻断领料） */
    void bindIssuedBatches(String workNo, long productId, Collection<BatchMove> moves);
}
