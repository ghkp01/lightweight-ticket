<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import BpmnModeler from 'bpmn-js/lib/Modeler'
import flowableModdle from '../bpmn/flowable-moddle.json'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import { api } from '../api'

const route = useRoute()
const router = useRouter()

const roleLabel: Record<string, string> = {
  ADMIN: '管理员',
  SALES: '销售',
  PLANNER: '计划',
  WAREHOUSE: '仓库',
  QC: '质检'
}

const flowId = ref<number | null>(route.params.id === 'new' ? null : Number(route.params.id))
const loading = ref(false)
const saving = ref(false)
const roles = ref<string[]>([])
const userSearchResults = ref<any[]>([])
const userLabels = ref<Record<string, any>>({})

/** 搜索结果直接替换列表；已选成员姓名单独缓存，避免下拉越搜越多 */
const rememberUserLabels = (rows: any[]) => {
  const next = { ...userLabels.value }
  ;(rows || []).forEach((item) => {
    if (item?.username) next[item.username] = item
  })
  userLabels.value = next
}

const searchUsers = async (keyword = '') => {
  try {
    const rows = await api.get('/system/users/options', { keyword: keyword || undefined, limit: 20 })
    userSearchResults.value = rows || []
    rememberUserLabels(rows || [])
  } catch {
    // 搜索失败保留当前结果
  }
}

/** 编辑既有流程时补全已选成员的姓名，避免下拉只显示用户名 */
const ensureUserLabels = async (names: string[]) => {
  const missing = names.filter((name) => !userLabels.value[name])
  await Promise.all(missing.map(async (name) => {
    try {
      const rows = await api.get('/system/users/options', { keyword: name, limit: 5 })
      rememberUserLabels(rows || [])
    } catch {
      // 回显失败时下拉退回显示用户名
    }
  }))
}

/** 下拉候选：当前搜索结果 + 已选成员的姓名缓存 */
const optionsFor = (selected: string[]) => {
  const list = [...userSearchResults.value]
  selected.forEach((name) => {
    const row = userLabels.value[name]
    if (row && !list.some((item: any) => String(item.username) === name)) list.push(row)
  })
  return list
}

const flow = reactive({
  code: '',
  name: '',
  bizType: 'TRANSFER',
  enabled: true,
  remark: ''
})

const containerRef = ref<HTMLElement | null>(null)
let modeler: any = null

const selected = ref<any>(null)
const elementType = ref('')
const approverSource = ref('ROLE')
const approverValue = ref('')
const allSign = ref(false)
const conditionText = ref('')
const timerDuration = ref('')
const serviceKind = ref('')
const customUrl = ref('')

const isUserTask = computed(() => elementType.value === 'bpmn:UserTask')
const isSequenceFlow = computed(() => elementType.value === 'bpmn:SequenceFlow')
const isBoundaryTimer = computed(() => elementType.value === 'bpmn:BoundaryEvent')
const isServiceTask = computed(() => elementType.value === 'bpmn:ServiceTask')
const hasSelection = computed(() => !!selected.value)

const EMPTY_XML = (code: string, name: string) => `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                  xmlns:flowable="http://flowable.org/bpmn"
                  id="Definitions_1" targetNamespace="http://mes.local/approval">
  <bpmn:process id="NEW_FLOW" name="新审批流程" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1" name="发起">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:userTask id="Task_1" name="第一级审批" flowable:candidateGroups="PLANNER">
      <bpmn:incoming>Flow_1</bpmn:incoming>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:endEvent id="EndEvent_1" name="结束">
      <bpmn:incoming>Flow_2</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="NEW_FLOW">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1">
        <dc:Bounds x="160" y="152" width="36" height="36" />
        <bpmndi:BPMNLabel><dc:Bounds x="172" y="195" width="22" height="14" /></bpmndi:BPMNLabel>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Task_1_di" bpmnElement="Task_1">
        <dc:Bounds x="260" y="130" width="120" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="EndEvent_1_di" bpmnElement="EndEvent_1">
        <dc:Bounds x="450" y="152" width="36" height="36" />
        <bpmndi:BPMNLabel><dc:Bounds x="458" y="195" width="22" height="14" /></bpmndi:BPMNLabel>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Flow_1_di" bpmnElement="Flow_1">
        <di:waypoint x="196" y="170" />
        <di:waypoint x="260" y="170" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_2_di" bpmnElement="Flow_2">
        <di:waypoint x="380" y="170" />
        <di:waypoint x="450" y="170" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`

const modeling = () => modeler.get('modeling')
const commandStack = () => modeler.get('commandStack')
const canvas = () => modeler.get('canvas')
const elementRegistry = () => modeler.get('elementRegistry')

/** 读 flowable 扩展属性：不同版本的 moddle 可能放在属性里，也可能放在 $attrs 里 */
const flowableAttr = (bo: any, name: string) => {
  if (!bo) return undefined
  const key = 'flowable:' + name
  try {
    const direct = bo.get ? bo.get(key) : undefined
    if (direct !== undefined && direct !== null) return direct
  } catch (error) {
    // ignore
  }
  if (bo.$attrs && bo.$attrs[key] !== undefined) return bo.$attrs[key]
  return bo[name]
}

/** 读选中元素上的配置回填到右侧面板 */
const syncPanel = (element: any) => {
  selected.value = element
  const bo = element && element.businessObject
  if (!bo) {
    elementType.value = ''
    return
  }
  elementType.value = element.type
  approverValue.value = ''
  approverSource.value = 'ROLE'
  allSign.value = false
  conditionText.value = ''
  timerDuration.value = ''
  serviceKind.value = ''
  customUrl.value = ''
  const assigneeRaw = flowableAttr(bo, 'assignee')
  const candidateUsers = flowableAttr(bo, 'candidateUsers')
  const candidateGroups = flowableAttr(bo, 'candidateGroups')
  if (element.type === 'bpmn:UserTask') {
    if (bo.loopCharacteristics) {
      allSign.value = true
    }
    if (assigneeRaw) {
      const assignee = String(assigneeRaw)
      if (assignee.includes('initiatorLeader2')) approverSource.value = 'LEADER2'
      else if (assignee.includes('initiatorLeader')) approverSource.value = 'LEADER1'
      else if (assignee.includes('initiator')) approverSource.value = 'INITIATOR'
      else approverSource.value = 'USER'
      approverValue.value = assignee.includes('${') ? '' : assignee
    } else if (candidateUsers) {
      approverSource.value = 'USER'
      approverValue.value = String(candidateUsers)
    } else if (candidateGroups) {
      approverSource.value = 'ROLE'
      approverValue.value = String(candidateGroups)
    }
    if (approverSource.value === 'USER' && approverValue.value) {
      void ensureUserLabels(approverValue.value.split(',').map((item) => item.trim()).filter(Boolean))
    }
  }
  if (element.type === 'bpmn:SequenceFlow' && bo.conditionExpression) {
    const body = bo.conditionExpression.body || ''
    conditionText.value = String(body).replace(/\$\{|\}/g, '')
  }
  if (element.type === 'bpmn:ServiceTask') {
    const expression = String(flowableAttr(bo, 'expression') || '')
    serviceKind.value = expression.includes('notifyCc') ? 'CC'
      : expression.includes('autoPass') ? 'AUTO'
        : expression.includes('custom') ? 'CUSTOM' : ''
    const fields = bo.extensionElements && bo.extensionElements.values ? bo.extensionElements.values : []
    const urlField = fields.find((item: any) => item.name === 'url')
    customUrl.value = urlField ? String(urlField.stringValue || '') : ''
  }
  if (element.type === 'bpmn:BoundaryEvent') {
    const definition = bo.eventDefinitions && bo.eventDefinitions[0]
    if (definition && definition.timeDuration) {
      timerDuration.value = String(definition.timeDuration.body || '')
    }
  }
}

const applyName = (value: string) => {
  if (!selected.value) return
  modeling().updateProperties(selected.value, { name: value })
}

/** 审批人来源 → 写 flowable 的 assignee / candidate 属性 */
const applyApproverSource = () => {
  if (!selected.value) return
  const value = approverValue.value.trim()
  const props: any = {
    'flowable:candidateGroups': undefined,
    'flowable:candidateUsers': undefined,
    'flowable:assignee': undefined
  }
  if (approverSource.value === 'ROLE') {
    props['flowable:candidateGroups'] = value || 'PLANNER'
  } else if (approverSource.value === 'USER') {
    props['flowable:candidateUsers'] = value
  } else if (approverSource.value === 'INITIATOR') {
    props['flowable:assignee'] = '${initiator}'
  } else if (approverSource.value === 'LEADER1') {
    props['flowable:assignee'] = '${initiatorLeader}'
  } else if (approverSource.value === 'LEADER2') {
    props['flowable:assignee'] = '${initiatorLeader2}'
  }
  modeling().updateProperties(selected.value, props)
}

const applyApproverValue = () => {
  if (!selected.value) return
  applyApproverSource()
}

/** 会签：给用户任务加/去多实例，集合变量由后端在提交时按审批人填好 */
const applyAllSign = () => {
  if (!selected.value) return
  const bo = selected.value.businessObject
  if (allSign.value) {
    const loop = modeler.get('moddle').create('bpmn:MultiInstanceLoopCharacteristics', {
      isSequential: false
    })
    loop.$attrs = {
      'flowable:collection': '${mi_' + bo.id + '}',
      'flowable:elementVariable': 'assignee_' + bo.id
    }
    const completion = modeler.get('moddle').create('bpmn:FormalExpression', {
      body: '${nrOfCompletedInstances == nrOfInstances}'
    })
    loop.completionCondition = completion
    modeling().updateProperties(selected.value, { loopCharacteristics: loop })
  } else {
    modeling().updateProperties(selected.value, { loopCharacteristics: undefined })
  }
}

const applyCondition = () => {
  if (!selected.value) return
  const text = conditionText.value.trim()
  if (!text) {
    modeling().updateProperties(selected.value, { conditionExpression: undefined })
    return
  }
  const expression = modeler.get('moddle').create('bpmn:FormalExpression', {
    body: '${' + text + '}'
  })
  modeling().updateProperties(selected.value, { conditionExpression: expression })
}

const applyServiceKind = () => {
  if (!selected.value) return
  const map: Record<string, string> = {
    CC: '${approvalHooks.notifyCc(execution)}',
    AUTO: '${approvalHooks.autoPass(execution)}',
    CUSTOM: '${approvalHooks.custom(execution)}'
  }
  const expression = map[serviceKind.value]
  const props: any = { 'flowable:expression': expression }
  if (serviceKind.value === 'CUSTOM') {
    const extension = modeler.get('moddle').create('bpmn:ExtensionElements', { values: [] })
    extension.values.push(modeler.get('moddle').create('flowable:Field', {
      name: 'url',
      stringValue: customUrl.value.trim()
    }))
    props.extensionElements = extension
  }
  modeling().updateProperties(selected.value, props)
}

const applyTimer = () => {
  if (!selected.value) return
  const bo = selected.value.businessObject
  const definition = bo.eventDefinitions && bo.eventDefinitions[0]
  if (!definition) return
  const duration = timerDuration.value.trim()
  modeling().updateProperties(definition, {
    timeDuration: duration ? modeler.get('moddle').create('bpmn:FormalExpression', { body: duration }) : undefined
  })
}

const load = async () => {
  loading.value = true
  try {
    roles.value = (await api.get('/approvals/roles')) || []
    void searchUsers('')
    let xml = ''
    if (flowId.value) {
      const detail = await api.get(`/approvals/flows/${flowId.value}`)
      Object.assign(flow, {
        code: detail.code,
        name: detail.name,
        bizType: detail.bizType,
        enabled: detail.enabled,
        remark: detail.remark || ''
      })
      xml = detail.bpmnXml || ''
    } else {
      Object.assign(flow, { code: '', name: '', bizType: 'TRANSFER', enabled: true, remark: '' })
    }
    if (!xml || !xml.includes('BPMNDiagram')) {
      // 老流程只有节点表、没有图形信息，设计器画不出来，先用空白模板起步
      xml = EMPTY_XML(flow.code, flow.name)
      if (flowId.value) {
        ElMessage.warning('这条流程没有图形信息，已载入空白模板，保存后会重新部署')
      }
    }
    await modeler.importXML(xml)
    try {
      canvas().zoom('fit-viewport')
    } catch (error) {
      // 画布尺寸还没算出来时忽略
    }
  } finally {
    loading.value = false
  }
}

const save = async () => {
  if (!flow.code.trim() || !flow.name.trim()) {
    ElMessage.warning('流程编码与名称不能为空')
    return
  }
  saving.value = true
  try {
    const { xml } = await modeler.saveXML({ format: true })
    const payload = {
      code: flow.code.trim(),
      name: flow.name.trim(),
      bizType: flow.bizType,
      enabled: flow.enabled,
      remark: flow.remark,
      bpmnXml: xml
    }
    if (flowId.value) {
      await api.put(`/approvals/flows/${flowId.value}`, payload)
    } else {
      await api.post('/approvals/flows', payload)
    }
    ElMessage.success('BPMN 已保存并部署到 Flowable')
    router.push('/approvals')
  } finally {
    saving.value = false
  }
}

const undo = () => commandStack().undo()
const redo = () => commandStack().redo()
const fitViewport = () => canvas().zoom('fit-viewport')
const zoomIn = () => canvas().zoom(canvas().zoom() + 0.1)
const zoomOut = () => canvas().zoom(canvas().zoom() - 0.1)

const countElements = () => (elementRegistry() ? elementRegistry().getAll().length : 0)
const elementCount = ref(0)

onMounted(async () => {
  modeler = new BpmnModeler({
    container: containerRef.value,
    moddleExtensions: { flowable: flowableModdle }
  })
  modeler.on('selection.changed', (event: any) => {
    syncPanel(event.newSelection && event.newSelection[0])
  })
  modeler.on('element.changed', () => {
    elementCount.value = countElements()
    if (selected.value) {
      syncPanel(selected.value)
    }
  })
  await load()
  elementCount.value = countElements()
})

onBeforeUnmount(() => {
  if (modeler) {
    modeler.destroy()
    modeler = null
  }
})
</script>

<template>
  <div class="page designer">
    <div class="page-head">
      <div class="head-left">
        <el-button link @click="router.push('/approvals')">返回审批中心</el-button>
        <h2 class="page-title">{{ flowId ? '编辑审批流' : '新建审批流' }}</h2>
      </div>
      <div class="head-right">
        <span class="muted">画完直接编译成 BPMN 2.0 部署到 Flowable</span>
        <el-button type="primary" :loading="saving" @click="save">保存并部署</el-button>
      </div>
    </div>

    <div class="meta-bar">
      <el-input v-model="flow.name" placeholder="流程名称" style="width: 220px" />
      <el-input v-model="flow.code" placeholder="流程编码，如 CROSS_FACTORY_TRANSFER" style="width: 300px" />
      <el-select v-model="flow.bizType" style="width: 140px">
        <el-option label="跨厂调拨" value="TRANSFER" />
      </el-select>
      <el-switch v-model="flow.enabled" active-text="启用" />
      <span class="muted">元素 {{ elementCount }} 个 · 左侧工具栏画节点，右侧配置审批人</span>
    </div>

    <div class="workspace" v-loading="loading">
      <div ref="containerRef" class="canvas"></div>

      <aside class="props">
        <div class="toolbar">
          <el-button size="small" @click="undo">撤销</el-button>
          <el-button size="small" @click="redo">重做</el-button>
          <el-button size="small" @click="zoomOut">-</el-button>
          <el-button size="small" @click="zoomIn">+</el-button>
          <el-button size="small" @click="fitViewport">适应</el-button>
        </div>

        <template v-if="hasSelection">
          <div class="props-head">{{ elementType }}</div>

          <el-form label-position="top" size="default">
            <el-form-item label="名称">
              <el-input :model-value="selected?.businessObject?.name || ''" @update:model-value="applyName" />
            </el-form-item>

            <template v-if="isUserTask">
              <el-form-item label="审批人来源">
                <el-select v-model="approverSource" style="width: 100%" @change="applyApproverSource">
                  <el-option label="按角色" value="ROLE" />
                  <el-option label="指定成员" value="USER" />
                  <el-option label="发起人自己" value="INITIATOR" />
                  <el-option label="发起人主管" value="LEADER1" />
                  <el-option label="主管的上级" value="LEADER2" />
                </el-select>
              </el-form-item>

              <el-form-item v-if="approverSource === 'ROLE'" label="角色（多个用逗号）">
                <el-select :model-value="approverValue.split(',').filter(Boolean)" multiple style="width: 100%"
                           @update:model-value="(value: string[]) => { approverValue = value.join(','); applyApproverValue() }">
                  <el-option v-for="role in roles" :key="role" :label="roleLabel[role] || role" :value="role" />
                </el-select>
              </el-form-item>

              <el-form-item v-else-if="approverSource === 'USER'" label="成员（多个用逗号）">
                <el-select :model-value="approverValue.split(',').filter(Boolean)" multiple filterable remote
                           reserve-keyword :remote-method="searchUsers"
                           style="width: 100%"
                           placeholder="输入姓名或账号搜索"
                           @update:model-value="(value: string[]) => { approverValue = value.join(','); applyApproverValue() }">
                  <el-option v-for="user in optionsFor(approverValue.split(',').filter(Boolean))" :key="user.username"
                             :label="`${user.realName || user.username}（${user.roleName || user.username}）`"
                             :value="user.username" />
                </el-select>
              </el-form-item>

              <div v-else class="hint">
                已写成表达式：{{ approverSource === 'INITIATOR' ? '${initiator}' : approverSource === 'LEADER1' ? '${initiatorLeader}' : '${initiatorLeader2}' }}
              </div>

              <div class="switch-row">
                <el-switch v-model="allSign" size="small" @change="applyAllSign" />
                <span>会签（本节点所有人都要通过）</span>
              </div>
            </template>

            <template v-if="isSequenceFlow">
              <el-form-item label="流转条件（表达式）">
                <el-input v-model="conditionText" placeholder="如 qty &gt;= 100" @blur="applyCondition" />
              </el-form-item>
              <div class="hint">用在排他网关出来的连线上，留空表示无条件。</div>
            </template>

            <template v-if="isServiceTask">
              <el-form-item label="动作">
                <el-select v-model="serviceKind" style="width: 100%" @change="applyServiceKind">
                  <el-option label="不设置" value="" />
                  <el-option label="抄送（推通知）" value="CC" />
                  <el-option label="自动通过（只留痕）" value="AUTO" />
                  <el-option label="自定义逻辑（HTTP 回调）" value="CUSTOM" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="serviceKind === 'CUSTOM'" label="回调地址">
                <el-input v-model="customUrl" placeholder="https://example.com/hooks/approval" @blur="applyServiceKind" />
              </el-form-item>
              <div class="hint">
                自定义逻辑会 POST 一份 JSON（单号、金额、数量、申请人、时间）到你的接口，失败不影响审批。
              </div>
            </template>

            <template v-if="isBoundaryTimer">
              <el-form-item label="超时（ISO 时长）">
                <el-input v-model="timerDuration" placeholder="PT4H = 4 小时" @blur="applyTimer" />
              </el-form-item>
              <div class="hint">到时间走边界的出口，接一条连线指向提醒或自动通过的节点。</div>
            </template>
          </el-form>
        </template>

        <div v-else class="props-empty">
          点画布上的元素开始配置。<br />
          左侧工具栏：手型平移、连接线、用户任务、网关、事件。
        </div>

        <div class="flow-remark">
          <div class="props-head">可用表达式</div>
          <div class="hint">
            审批人：${initiator} 发起人、${initiatorLeader} 直接主管、${initiatorLeader2} 上级主管；<br />
            条件：qty / amount / applicantRole / bizType 都是流程变量。
          </div>
          <div class="props-head" style="margin-top: 10px">流程说明</div>
          <el-input v-model="flow.remark" type="textarea" :rows="3" placeholder="这条流程管什么场景" />
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.designer .page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}

.meta-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  padding: 10px 12px;
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 10px;
  font-size: 12px;
}

.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 12px;
  align-items: stretch;
}

.canvas {
  height: 640px;
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
}

.props {
  padding: 14px;
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 10px;
  max-height: 640px;
  overflow-y: auto;
}

.toolbar {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.props-head {
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 500;
  color: #0f172a;
}

.props-empty {
  padding: 20px 0;
  text-align: center;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.8;
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 12px;
  color: #475569;
}

.hint {
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.6;
}

.flow-remark {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
}
</style>

<style>
/* bpmn-js 的画布要撑满容器 */
.canvas .djs-container,
.canvas .bjs-container {
  width: 100%;
  height: 100%;
}
</style>
