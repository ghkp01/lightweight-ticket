-- BOM 变更审批：新版本提交审批通过后才生效，生效/审批中的版本只读
alter table bom add column if not exists approval_instance_id bigint;

alter table bom drop constraint if exists ck_bom_status;
alter table bom add constraint ck_bom_status
    check (status in ('DRAFT', 'PENDING', 'ACTIVE', 'INACTIVE'));

comment on column bom.approval_instance_id is '提交审批后关联的审批实例；通过或驳回后清空';
comment on column bom.status is 'DRAFT 草稿 / PENDING 审批中 / ACTIVE 生效 / INACTIVE 停用';

-- 审批开关登记：默认走审批流，可在审批设置里改成免审批
insert into sys_approval_biz (biz_type, name, menu_path, mode, remark) values
('BOM', 'BOM 变更', '/boms', 'FLOW', 'BOM 新版本提交后审批通过才生效')
on conflict (biz_type) do nothing;

-- 默认审批流（BPMN 是唯一事实来源）：管理员单节点审核
insert into sys_approval_flow (code, name, biz_type, enabled, remark, created_by, process_key, bpmn_xml)
values ('BOM_CHANGE', 'BOM 变更审批', 'BOM', true,
        'BOM 新版本审核用料、用量与生效日期，通过后自动替换同物料旧版本', 'system', 'BOM_CHANGE',
        $bpmn$<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                  xmlns:flowable="http://flowable.org/bpmn"
                  id="Definitions_Bom" targetNamespace="http://mes.local/approval">
  <bpmn:process id="BOM_CHANGE" name="BOM 变更审批" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1" name="发起">
      <bpmn:outgoing>Flow_start</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:userTask id="Task_BomReview" name="BOM 变更审核" flowable:candidateGroups="ADMIN">
      <bpmn:incoming>Flow_start</bpmn:incoming>
      <bpmn:outgoing>Flow_end</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:endEvent id="EndEvent_1" name="结束">
      <bpmn:incoming>Flow_end</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_start" sourceRef="StartEvent_1" targetRef="Task_BomReview" />
    <bpmn:sequenceFlow id="Flow_end" sourceRef="Task_BomReview" targetRef="EndEvent_1" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="BOM_CHANGE">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1">
        <dc:Bounds x="152" y="102" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Task_BomReview_di" bpmnElement="Task_BomReview">
        <dc:Bounds x="240" y="80" width="120" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="EndEvent_1_di" bpmnElement="EndEvent_1">
        <dc:Bounds x="420" y="102" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Flow_start_di" bpmnElement="Flow_start">
        <di:waypoint x="188" y="120" />
        <di:waypoint x="240" y="120" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_end_di" bpmnElement="Flow_end">
        <di:waypoint x="360" y="120" />
        <di:waypoint x="420" y="120" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>$bpmn$)
on conflict (code) do nothing;
