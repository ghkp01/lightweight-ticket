-- 默认审批流换成带 BPMN DI 的图：设计器（bpmn-js）需要图形信息才能画出来。
-- 这个流程同时演示条件分支（金额 ≥ 1000 走厂长复核）与并行分支（仓库确认 + 质量确认）。
update sys_approval_flow
   set bpmn_xml = $bpmn$<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                  xmlns:flowable="http://flowable.org/bpmn"
                  id="Definitions_Transfer" targetNamespace="http://mes.local/approval">
  <bpmn:process id="CROSS_FACTORY_TRANSFER" name="跨厂调拨审批" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1" name="发起">
      <bpmn:outgoing>Flow_start_plan</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:userTask id="Task_Plan" name="源厂计划审核" flowable:candidateGroups="PLANNER">
      <bpmn:incoming>Flow_start_plan</bpmn:incoming>
      <bpmn:outgoing>Flow_plan_gw</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:exclusiveGateway id="Gateway_Amount" name="金额 ≥ 1000" default="Flow_gw_merge">
      <bpmn:incoming>Flow_plan_gw</bpmn:incoming>
      <bpmn:outgoing>Flow_gw_big</bpmn:outgoing>
      <bpmn:outgoing>Flow_gw_merge</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:userTask id="Task_Big" name="厂长复核" flowable:assignee="${initiatorLeader2}">
      <bpmn:incoming>Flow_gw_big</bpmn:incoming>
      <bpmn:outgoing>Flow_big_merge</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:exclusiveGateway id="Gateway_Merge">
      <bpmn:incoming>Flow_big_merge</bpmn:incoming>
      <bpmn:incoming>Flow_gw_merge</bpmn:incoming>
      <bpmn:outgoing>Flow_merge_fork</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:parallelGateway id="Gateway_Fork">
      <bpmn:incoming>Flow_merge_fork</bpmn:incoming>
      <bpmn:outgoing>Flow_fork_wh</bpmn:outgoing>
      <bpmn:outgoing>Flow_fork_qc</bpmn:outgoing>
    </bpmn:parallelGateway>
    <bpmn:userTask id="Task_Warehouse" name="目标厂仓库确认" flowable:candidateGroups="WAREHOUSE">
      <bpmn:incoming>Flow_fork_wh</bpmn:incoming>
      <bpmn:outgoing>Flow_wh_join</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:userTask id="Task_Quality" name="质量确认" flowable:candidateGroups="QC">
      <bpmn:incoming>Flow_fork_qc</bpmn:incoming>
      <bpmn:outgoing>Flow_qc_join</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:inclusiveGateway id="Gateway_Join">
      <bpmn:incoming>Flow_wh_join</bpmn:incoming>
      <bpmn:incoming>Flow_qc_join</bpmn:incoming>
      <bpmn:outgoing>Flow_join_end</bpmn:outgoing>
    </bpmn:inclusiveGateway>
    <bpmn:endEvent id="EndEvent_1" name="结束">
      <bpmn:incoming>Flow_join_end</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_start_plan" sourceRef="StartEvent_1" targetRef="Task_Plan" />
    <bpmn:sequenceFlow id="Flow_plan_gw" sourceRef="Task_Plan" targetRef="Gateway_Amount" />
    <bpmn:sequenceFlow id="Flow_gw_big" name="金额达标" sourceRef="Gateway_Amount" targetRef="Task_Big">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression"><![CDATA[${amount >= 1000}]]></bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="Flow_gw_merge" name="金额较小" sourceRef="Gateway_Amount" targetRef="Gateway_Merge" />
    <bpmn:sequenceFlow id="Flow_big_merge" sourceRef="Task_Big" targetRef="Gateway_Merge" />
    <bpmn:sequenceFlow id="Flow_merge_fork" sourceRef="Gateway_Merge" targetRef="Gateway_Fork" />
    <bpmn:sequenceFlow id="Flow_fork_wh" sourceRef="Gateway_Fork" targetRef="Task_Warehouse" />
    <bpmn:sequenceFlow id="Flow_fork_qc" sourceRef="Gateway_Fork" targetRef="Task_Quality" />
    <bpmn:sequenceFlow id="Flow_wh_join" sourceRef="Task_Warehouse" targetRef="Gateway_Join" />
    <bpmn:sequenceFlow id="Flow_qc_join" sourceRef="Task_Quality" targetRef="Gateway_Join" />
    <bpmn:sequenceFlow id="Flow_join_end" sourceRef="Gateway_Join" targetRef="EndEvent_1" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="CROSS_FACTORY_TRANSFER">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1">
        <dc:Bounds x="152" y="202" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Task_Plan_di" bpmnElement="Task_Plan">
        <dc:Bounds x="232" y="180" width="120" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Gateway_Amount_di" bpmnElement="Gateway_Amount" isMarkerVisible="true">
        <dc:Bounds x="392" y="205" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Task_Big_di" bpmnElement="Task_Big">
        <dc:Bounds x="482" y="96" width="120" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Gateway_Merge_di" bpmnElement="Gateway_Merge" isMarkerVisible="true">
        <dc:Bounds x="662" y="205" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Gateway_Fork_di" bpmnElement="Gateway_Fork">
        <dc:Bounds x="752" y="205" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Task_Warehouse_di" bpmnElement="Task_Warehouse">
        <dc:Bounds x="832" y="96" width="120" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Task_Quality_di" bpmnElement="Task_Quality">
        <dc:Bounds x="832" y="296" width="120" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Gateway_Join_di" bpmnElement="Gateway_Join">
        <dc:Bounds x="1002" y="205" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="EndEvent_1_di" bpmnElement="EndEvent_1">
        <dc:Bounds x="1092" y="202" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Flow_start_plan_di" bpmnElement="Flow_start_plan">
        <di:waypoint x="188" y="220" />
        <di:waypoint x="232" y="220" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_plan_gw_di" bpmnElement="Flow_plan_gw">
        <di:waypoint x="352" y="220" />
        <di:waypoint x="392" y="230" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_gw_big_di" bpmnElement="Flow_gw_big">
        <di:waypoint x="417" y="205" />
        <di:waypoint x="417" y="136" />
        <di:waypoint x="482" y="136" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_gw_merge_di" bpmnElement="Flow_gw_merge">
        <di:waypoint x="442" y="230" />
        <di:waypoint x="662" y="230" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_big_merge_di" bpmnElement="Flow_big_merge">
        <di:waypoint x="602" y="136" />
        <di:waypoint x="687" y="136" />
        <di:waypoint x="687" y="205" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_merge_fork_di" bpmnElement="Flow_merge_fork">
        <di:waypoint x="712" y="230" />
        <di:waypoint x="752" y="230" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_fork_wh_di" bpmnElement="Flow_fork_wh">
        <di:waypoint x="777" y="205" />
        <di:waypoint x="777" y="136" />
        <di:waypoint x="832" y="136" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_fork_qc_di" bpmnElement="Flow_fork_qc">
        <di:waypoint x="777" y="255" />
        <di:waypoint x="777" y="336" />
        <di:waypoint x="832" y="336" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_wh_join_di" bpmnElement="Flow_wh_join">
        <di:waypoint x="952" y="136" />
        <di:waypoint x="1027" y="136" />
        <di:waypoint x="1027" y="205" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_qc_join_di" bpmnElement="Flow_qc_join">
        <di:waypoint x="952" y="336" />
        <di:waypoint x="1027" y="336" />
        <di:waypoint x="1027" y="255" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_join_end_di" bpmnElement="Flow_join_end">
        <di:waypoint x="1052" y="230" />
        <di:waypoint x="1092" y="220" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>$bpmn$
 where code = 'CROSS_FACTORY_TRANSFER';

-- 节点树在新设计器里不再需要（节点都画在 BPMN 里了）
delete from sys_approval_node
 where flow_id in (select id from sys_approval_flow where code = 'CROSS_FACTORY_TRANSFER');

-- 清掉部署指针，启动时按新的 BPMN 重新部署一次，保证「画布上看到的」和「引擎跑的」是同一张图
update sys_approval_flow
   set deployment_id = null, process_version = null
 where code = 'CROSS_FACTORY_TRANSFER';
