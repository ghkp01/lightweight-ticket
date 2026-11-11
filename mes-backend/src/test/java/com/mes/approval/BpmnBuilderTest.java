package com.mes.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节点树 → BPMN 编译：重点守住「画布上配的东西真的会生效」。
 *
 * <p>纯字符串测试，不起 Spring 上下文：编译结果是确定性的，跑起来也就几毫秒。</p>
 */
class BpmnBuilderTest {

    private static BpmnBuilder.FlowNode approver(String key, String name, String role,
                                                 String conditionField, String conditionOp,
                                                 String conditionValue) {
        return new BpmnBuilder.FlowNode(key, "APPROVER", name, null, null, "ROLE", "ANY",
                List.of(role), 1, null, conditionField, conditionOp, conditionValue);
    }

    @Test
    @DisplayName("审批节点上的本级条件编译成独立网关，不成立时直接跳过本节点")
    void approverNodeConditionCompilesToGateway() {
        String xml = BpmnBuilder.build("FLOW_X", "测试流程", List.of(
                approver("n1", "计划审核", "PLANNER", "QTY", ">=", "100"),
                approver("n2", "仓库确认", "WAREHOUSE", null, null, null)));

        assertTrue(xml.contains("<exclusiveGateway id=\"n1_cond\" default=\"n1_cond_skip\"/>"),
                "条件网关没有生成");
        assertTrue(xml.contains("${qty >= 100}"), "条件表达式没有编译进 BPMN");
        assertTrue(xml.contains("id=\"n1_cond_skip\""), "条件不成立时应跳到下游");
        // 条件成立后仍要过「有没有审批人」这道网关
        assertTrue(xml.contains("${approvalHooks.hasApprover('ROLE', 'PLANNER')}"),
                "审批人兜底判断丢了");
    }

    @Test
    @DisplayName("没配本级条件的审批节点不会多出条件网关")
    void plainApproverHasNoConditionGateway() {
        String xml = BpmnBuilder.build("FLOW_Y", "测试流程", List.of(
                approver("n1", "计划审核", "PLANNER", null, null, null)));

        assertFalse(xml.contains("id=\"n1_cond\""), "不该生成条件网关");
        assertTrue(xml.contains("id=\"n1_chk\""), "审批人网关应该在");
    }

    @Test
    @DisplayName("条件容器的表达式按字段名映射到流程变量")
    void conditionContainerMapsFieldToVariable() {
        BpmnBuilder.FlowNode condition = new BpmnBuilder.FlowNode("n1", "CONDITION", "金额分支",
                null, null, "ROLE", "ANY", List.of(), null, null, "AMOUNT", ">", "1000");
        String xml = BpmnBuilder.build("FLOW_Z", "测试流程", List.of(condition));

        assertTrue(xml.contains("${amount > 1000}"), xml);
    }
}
