package com.mes.approval.api;

import java.util.Map;

/**
 * 审批流对外契约：其它模块只做三件事 —— 提单、问状态、催超时。
 *
 * <p>审批内部（流程部署、节点策略、任务分配、BPMN 变量）一概不暴露：
 * 那些东西改一次就得连带改调用方，正是这次收口要避免的。</p>
 */
public interface ApprovalPort {

    /**
     * 提交一张单据进审批。
     *
     * @param context 条件分支用的业务上下文（数量、金额、调出调入工厂…）。
     *                这里刻意保留 {@code Map} 而不是 record —— 它是「给流程表达式求值的变量袋」，
     *                不同单据键不同，类型化反而要为一个通用机制写 N 个 record。
     * @return 审批实例 id；没有匹配到流程时返回 {@code null}（调用方按「无需审批」处理）
     */
    Long submit(String bizType, long bizId, String bizNo, String title, Map<String, Object> context);

    /**
     * 业务类型当前的审批强度：{@code NONE} 免审批 / {@code THRESHOLD} 只按金额阈值 /
     * {@code FLOW} 走配置的审批流。
     *
     * <p>调用方据此决定「无需审批」时该直接生效还是走自己的兜底规则（例如金额阈值），
     * 而不是一律当成放行。</p>
     */
    String modeOf(String bizType);

    /**
     * 只按适用条件算一次「会命中哪条链」，不启动流程。
     *
     * <p>行级分流要先逐行选链、再按链分组，所以需要这个「只算不跑」的入口；
     * 命中结果会写进匹配日志，没命中返回 {@code null}。</p>
     */
    Long matchFlow(String bizType, long bizId, String bizNo, Map<String, Object> variables);

    /**
     * 按指定流程发起审批（跳过错链匹配）。
     *
     * <p>行级分流在分组时已经选过链了，这里不能再选一次 —— 否则组内变量一改
     * （比如金额变成组内合计），就可能跑到另一条链上，出现「选的是 A 跑的是 B」。</p>
     *
     * @return 审批实例 id；开关关闭或流程不可用时返回 {@code null}
     */
    Long submitWithFlow(String bizType, long bizId, String bizNo, String title,
                        Long flowId, Map<String, Object> context);

    /** 审批实例状态：PENDING / APPROVED / REJECTED / CANCELED */
    String statusOf(long instanceId);

    /**
     * 这张单据当前有没有在途审批实例。
     *
     * <p>行级分流时一张单可能有多个实例，单据头上记不住单一实例，所以业务侧要拦
     * 「绕过审批直接批」得问这里。</p>
     */
    boolean hasPending(String bizType, long bizId);

    /**
     * 业务侧撤回：单据要退回草稿重算时调用。
     *
     * <p>只有还没人审批通过的实例能撤回（已有节点签过的要另走驳回），
     * 撤回后审批侧会回调业务把单据写回草稿，调用方不必自己改状态。</p>
     */
    void cancel(long instanceId);

    /** 扫超时：提醒或自动通过，返回本轮处理量 */
    TimeoutScan scanTimeouts();

    /** 一轮超时扫描的结果，定时任务用它算「影响了多少单」 */
    record TimeoutScan(int scanned, int reminded, int autoPassed) {
    }
}
