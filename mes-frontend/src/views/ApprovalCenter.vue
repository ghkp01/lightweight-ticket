<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import StatusTag from '../components/StatusTag.vue'
import ReferenceSelect from '../components/ReferenceSelect.vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const canConfig = computed(() => auth.can('system_approval'))

const tab = ref('tasks')
const loading = ref(false)
const tasks = ref<any[]>([])
const tasksTotal = ref(0)
const tasksPage = ref(1)
const tasksSize = ref(20)
const mine = ref<any[]>([])
const mineTotal = ref(0)
const minePage = ref(1)
const mineSize = ref(20)
const flows = ref<any[]>([])

const actionDialog = ref(false)
const actionMode = ref<'approve' | 'reject'>('approve')
const actionTask = ref<any>(null)
const actionComment = ref('')
const acting = ref(false)

const forwardDialog = ref(false)
const forwardMode = ref<'transfer' | 'addSign'>('transfer')
const forwardTask = ref<any>(null)
const forwardUser = ref('')
const forwardComment = ref('')
const forwarding = ref(false)

const openForward = async (task: any, mode: 'transfer' | 'addSign' | 'rollback') => {
  if (mode === 'rollback') {
    await openRollback(task)
    return
  }
  forwardTask.value = task
  forwardMode.value = mode
  forwardUser.value = ''
  forwardComment.value = ''
  forwardDialog.value = true
}

const submitForward = async () => {
  if (!forwardTask.value || !forwardUser.value) {
    ElMessage.warning('请选择处理人')
    return
  }
  forwarding.value = true
  try {
    const path = forwardMode.value === 'transfer' ? 'transfer' : 'add-sign'
    const result: any = await api.post(`/approvals/tasks/${forwardTask.value.id}/${path}`, {
      targetUser: forwardUser.value,
      comment: forwardComment.value.trim() || undefined
    })
    ElMessage.success(result?.message || '已提交')
    forwardDialog.value = false
    await refreshTasks()
  } finally {
    forwarding.value = false
  }
}

/** 加签待办的确认 / 不同意 */
const openErrorAction = async (row: any, kind: 'approveExtra' | 'rejectExtra') => {
  const isReject = kind === 'rejectExtra'
  try {
    const { value } = await ElMessageBox.prompt(
      isReject ? '不同意的原因（会驳回整单）' : '确认这条加签',
      isReject ? '加签不同意' : '确认加签',
      {
        inputType: 'textarea',
        inputPlaceholder: isReject ? '必须填写原因' : '意见，可留空',
        inputValidator: (input: string) => (isReject && !String(input || '').trim() ? '必须填写原因' : true)
      }
    )
    await api.post(`/approvals/extra-tasks/${row.id}/${isReject ? 'reject' : 'approve'}`, {
      comment: String(value || '').trim() || undefined
    })
    ElMessage.success(isReject ? '已退回，审批结束' : '已确认，等原审批人继续')
    await refreshTasks()
  } catch (error) {
    // 取消输入
  }
}

const urge = async (row: any) => {
  const result: any = await api.post(`/approvals/instances/${row.id}/urge`)
  ElMessage.success(result?.message || '已催办')
}

const roleLabel: Record<string, string> = {
  ADMIN: '管理员',
  SALES: '销售',
  PLANNER: '计划',
  WAREHOUSE: '仓库',
  QC: '质检'
}

const bizLabel: Record<string, string> = {
  TRANSFER: '跨厂调拨'
}

const fieldLabel: Record<string, string> = {
  QTY: '数量',
  AMOUNT: '金额',
  APPLICANT_ROLE: '发起人角色',
  BIZ_TYPE: '业务类型'
}

const nodeSummary = (node: any) => {
  const values = node.approverValues ? String(node.approverValues).split(',') : []
  const approver = node.approverType === 'USER'
    ? '指定 ' + values.join('、')
    : '角色 ' + values.map((role: string) => roleLabel[role] || role).join('、')
  const mode = node.multiMode === 'ALL' ? '会签' : '或签'
  const condition = node.conditionField
    ? ` · ${fieldLabel[node.conditionField] || node.conditionField} ${node.conditionOp} ${node.conditionValue}`
    : ''
  return `${approver} · ${mode}${condition}`
}

const loadTasks = async () => {
  loading.value = true
  try {
    const data = await api.get('/approvals/tasks', {
      page: tasksPage.value,
      size: tasksSize.value
    })
    tasks.value = data?.records || []
    tasksTotal.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

const changeTasksPage = (value: number) => {
  tasksPage.value = value
  loadTasks()
}

const changeTasksSize = () => {
  tasksPage.value = 1
  loadTasks()
}

/** 处理完一条待办后刷新：当前页空了就回上一页，避免停在空列表 */
const refreshTasks = async () => {
  await loadTasks()
  if (!tasks.value.length && tasksPage.value > 1) {
    tasksPage.value -= 1
    await loadTasks()
  }
}

const loadMine = async () => {
  loading.value = true
  try {
    const data = await api.get('/approvals/instances', {
      mine: true,
      page: minePage.value,
      size: mineSize.value
    })
    mine.value = data.records || []
    mineTotal.value = data.total || 0
  } finally {
    loading.value = false
  }
}

const changeMinePage = (value: number) => {
  minePage.value = value
  loadMine()
}

const loadFlows = async () => {
  loading.value = true
  try {
    flows.value = (await api.get('/approvals/flows')) || []
  } finally {
    loading.value = false
  }
}

const formFields = ref<any[]>([])
const formValues = reactive<Record<string, string>>({})
const signatureRequired = ref(false)
const signCanvas = ref<HTMLCanvasElement | null>(null)
const signed = ref(false)
let signing = false
let hasInk = false

const initSign = () => {
  signed.value = false
  hasInk = false
  const canvas = signCanvas.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.lineWidth = 2.2
  ctx.lineCap = 'round'
  ctx.strokeStyle = '#0f172a'
}

const pointerPos = (event: PointerEvent) => {
  const canvas = signCanvas.value!
  const rect = canvas.getBoundingClientRect()
  return {
    x: (event.clientX - rect.left) * (canvas.width / rect.width),
    y: (event.clientY - rect.top) * (canvas.height / rect.height)
  }
}

const startSign = (event: PointerEvent) => {
  const ctx = signCanvas.value?.getContext('2d')
  if (!ctx) return
  signing = true
  const point = pointerPos(event)
  ctx.beginPath()
  ctx.moveTo(point.x, point.y)
}

const drawSign = (event: PointerEvent) => {
  if (!signing) return
  const ctx = signCanvas.value?.getContext('2d')
  if (!ctx) return
  const point = pointerPos(event)
  ctx.lineTo(point.x, point.y)
  ctx.stroke()
  hasInk = true
  signed.value = true
}

const endSign = () => {
  signing = false
}

const clearSign = () => {
  initSign()
}

const openAction = async (task: any, mode: 'approve' | 'reject') => {
  actionTask.value = task
  actionMode.value = mode
  actionComment.value = ''
  formFields.value = []
  formValues.value = {}
  Object.keys(formValues).forEach((key) => delete formValues[key])
  signatureRequired.value = false
  signed.value = false
  actionDialog.value = true
  if (mode !== 'approve' || task.extra) {
    return
  }
  try {
    const form: any = await api.get(`/approvals/tasks/${task.id}/form`)
    formFields.value = form?.fields || []
    signatureRequired.value = !!form?.signatureRequired
    formFields.value.forEach((field: any) => {
      if (!field.readOnly) {
        formValues.value[field.key] = field.value == null ? '' : String(field.value)
      }
    })
    await nextTick()
    initSign()
  } catch (error) {
    // 单据不支持在审批里改字段时忽略
  }
}

const submitAction = async () => {
  if (!actionTask.value) return
  if (!actionTask.value.extra && actionMode.value === 'reject' && !actionComment.value.trim()) {
    ElMessage.warning('驳回必须填写原因')
    return
  }
  acting.value = true
  try {
    const taskId = actionTask.value.id
    const signature = signed.value && signCanvas.value
      ? signCanvas.value.toDataURL('image/png')
      : undefined
    if (!actionTask.value.extra && actionMode.value === 'approve') {
      if (signatureRequired.value && !signature) {
        ElMessage.warning('该流程要求手写签名，请先签名')
        return
      }
      if (Object.keys(formValues.value).length) {
        await api.put(`/approvals/tasks/${taskId}/form`, { ...formValues.value })
      }
    }
    const result: any = await api.post(`/approvals/tasks/${taskId}/${actionMode.value}`, {
      comment: actionComment.value.trim() || undefined,
      signature
    })
    if (result?.message) {
      ElMessage.info(result.message)
    } else {
      ElMessage.success(actionMode.value === 'approve' ? '已通过' : '已驳回')
    }
    actionDialog.value = false
    await refreshTasks()
  } finally {
    acting.value = false
  }
}

// ---- 回退
const rollbackDialog = ref(false)
const rollbackTask = ref<any>(null)
const rollbackTargets = ref<any[]>([])
const rollbackTarget = ref('')
const rollbackComment = ref('')
const rollingBack = ref(false)

const openRollback = async (task: any) => {
  rollbackTask.value = task
  rollbackTarget.value = ''
  rollbackComment.value = ''
  rollbackTargets.value = (await api.get(`/approvals/tasks/${task.id}/rollback-targets`)) || []
  if (!rollbackTargets.value.length) {
    ElMessage.info('这张单还没有走过的其他节点，不能回退')
    return
  }
  rollbackDialog.value = true
}

const submitRollback = async () => {
  if (!rollbackTask.value || !rollbackTarget.value) {
    ElMessage.warning('请选择要回退到的节点')
    return
  }
  rollingBack.value = true
  try {
    const result: any = await api.post(`/approvals/tasks/${rollbackTask.value.id}/rollback`, {
      targetNodeKey: rollbackTarget.value,
      comment: rollbackComment.value.trim() || undefined
    })
    ElMessage.success(result?.message || '已回退')
    rollbackDialog.value = false
    await refreshTasks()
  } finally {
    rollingBack.value = false
  }
}

const cancelInstance = async (row: any) => {
  await ElMessageBox.confirm(`撤回审批「${row.title}」？撤回后业务单据回到草稿状态。`, '撤回审批')
  await api.post(`/approvals/instances/${row.id}/cancel`)
  ElMessage.success('已撤回')
  loadMine()
}

const design = (flow: any) => {
  router.push(`/approvals/design/${flow ? flow.id : 'new'}`)
}

const switchTab = (name: string) => {
  tab.value = name
  if (name === 'tasks') loadTasks()
  else if (name === 'mine') loadMine()
  else loadFlows()
}

const pendingCount = computed(() => tasksTotal.value)

onMounted(async () => {
  await loadTasks()
  await loadMine()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">审批中心</h2>
        <p class="page-sub">审批流可视化配置，支持角色或指定人、或签会签、条件与抄送</p>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="switchTab">
      <el-tab-pane name="tasks">
        <template #label>待我审批<span v-if="pendingCount" class="tab-badge">{{ pendingCount }}</span></template>
        <div v-loading="loading" class="panel">
          <el-table :data="tasks" size="small" empty-text="没有需要你处理的审批">
            <el-table-column prop="title" label="事项" min-width="220" />
            <el-table-column label="业务" width="110">
              <template #default="{ row }">{{ bizLabel[row.bizType] || row.bizType }}</template>
            </el-table-column>
            <el-table-column prop="bizNo" label="单据号" width="170">
              <template #default="{ row }"><span class="mono">{{ row.bizNo }}</span></template>
            </el-table-column>
            <el-table-column prop="applicant" label="申请人" width="110" />
            <el-table-column label="当前节点" width="170">
              <template #default="{ row }">
                {{ row.nodeName }}
                <span v-if="row.extra" class="chip warm">加签</span>
                <span v-else-if="row.multiMode === 'ALL'" class="chip">会签</span>
              </template>
            </el-table-column>
            <el-table-column label="我的身份" width="120">
              <template #default="{ row }">
                <template v-if="row.extra">加签人</template>
                <template v-else>{{ row.assignee ? '指定审批人' : (roleLabel[row.approverRole] || row.approverRole) }}</template>
              </template>
            </el-table-column>
            <el-table-column label="提交时间" width="150">
              <template #default="{ row }">{{ row.submittedAt }}</template>
            </el-table-column>
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <template v-if="row.extra">
                  <el-button link type="success" @click="openErrorAction(row, 'approveExtra')">确认</el-button>
                  <el-button link type="danger" @click="openErrorAction(row, 'rejectExtra')">不同意</el-button>
                </template>
                <template v-else>
                  <el-button link type="success" @click="openAction(row, 'approve')">通过</el-button>
                  <el-button link type="danger" @click="openAction(row, 'reject')">驳回</el-button>
                  <el-dropdown trigger="click" @command="(cmd: any) => openForward(row, cmd)">
                    <el-button link type="primary">更多</el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="transfer">转交给他人</el-dropdown-item>
                        <el-dropdown-item command="addSign">加签</el-dropdown-item>
                        <el-dropdown-item command="rollback">回退到指定节点</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <el-pagination
              v-model:current-page="tasksPage"
              v-model:page-size="tasksSize"
              :total="tasksTotal"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @current-change="changeTasksPage"
              @size-change="changeTasksSize"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane name="mine">
        <template #label>我发起的</template>
        <div v-loading="loading" class="panel">
          <el-table :data="mine" size="small" empty-text="你还没有发起过审批">
            <el-table-column prop="title" label="事项" min-width="220" />
            <el-table-column label="业务" width="110">
              <template #default="{ row }">{{ bizLabel[row.bizType] || row.bizType }}</template>
            </el-table-column>
            <el-table-column prop="bizNo" label="单据号" width="170">
              <template #default="{ row }"><span class="mono">{{ row.bizNo }}</span></template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }"><StatusTag :status="row.status" /></template>
            </el-table-column>
            <el-table-column label="当前节点" width="150">
              <template #default="{ row }">{{ row.currentNode || '-' }}</template>
            </el-table-column>
            <el-table-column label="提交时间" width="150">
              <template #default="{ row }">{{ row.submittedAt }}</template>
            </el-table-column>
            <el-table-column label="结束时间" width="150">
              <template #default="{ row }">{{ row.finishedAt || '-' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING'">
                  <el-button link type="primary" @click="urge(row)">催办</el-button>
                  <el-button link type="danger" @click="cancelInstance(row)">撤回</el-button>
                </template>
                <span v-else class="muted">-</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="table-tip">撤回后业务单据回到草稿状态。</div>
          <div class="pager">
            <el-pagination
              v-model:current-page="minePage"
              v-model:page-size="mineSize"
              :total="mineTotal"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @current-change="changeMinePage"
              @size-change="loadMine"
            />
          </div>
        </div>
      </el-tab-pane>

    </el-tabs>

    <el-dialog append-to-body v-model="actionDialog" :title="actionMode === 'approve' ? '审批通过' : '审批驳回'" width="520px">
      <p class="dialog-sub">{{ actionTask?.title }}（{{ actionTask?.bizNo }}）</p>

      <div v-if="actionMode === 'approve' && formFields.length" class="form-block">
        <div v-for="field in formFields" :key="field.key" class="form-row">
          <span class="form-label">{{ field.label }}</span>
          <el-input v-if="!field.readOnly" v-model="formValues[field.key]" size="small" />
          <span v-else class="form-value">{{ field.value ?? '-' }}</span>
        </div>
        <div class="form-tip">灰色字段为只读；隐藏字段不会出现在这里。</div>
      </div>

      <div v-if="actionMode === 'approve' && signatureRequired" class="sign-block">
        <div class="form-label">手写签名（本流程必签）</div>
        <canvas
          ref="signCanvas"
          class="sign-canvas"
          width="460"
          height="140"
          @pointerdown.prevent="startSign"
          @pointermove.prevent="drawSign"
          @pointerup="endSign"
          @pointerleave="endSign"
        ></canvas>
        <el-button size="small" @click="clearSign">清除重签</el-button>
      </div>

      <el-input
        v-model="actionComment"
        type="textarea"
        :rows="3"
        :placeholder="actionMode === 'reject' ? '请填写驳回原因（必填）' : '审批意见（可选）'"
      />
      <template #footer>
        <el-button @click="actionDialog = false">取消</el-button>
        <el-button
          :type="actionMode === 'approve' ? 'primary' : 'danger'"
          :loading="acting"
          @click="submitAction"
        >
          确认{{ actionMode === 'approve' ? '通过' : '驳回' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      append-to-body
      v-model="forwardDialog"
      :title="forwardMode === 'transfer' ? '转交审批' : '加签（会签）'"
      width="460px"
    >
      <p class="dialog-sub">{{ forwardTask?.title }}（{{ forwardTask?.bizNo }}）· {{ forwardTask?.nodeName }}</p>
      <p class="dialog-tip">
        {{ forwardMode === 'transfer'
          ? '转交后这条待办只由对方处理，你不再收到该节点待办。'
          : '加签后你这条待办要等对方确认后才能通过；对方不同意则整单驳回。' }}
      </p>
      <ReferenceSelect
        v-model="forwardUser"
        url="/approvals/assignees"
        value-field="username"
        code-field="username"
        label-field="realName"
        placeholder="输入姓名或账号搜索"
      />
      <el-input
        v-model="forwardComment"
        type="textarea"
        :rows="3"
        style="margin-top: 10px"
        placeholder="说明（可选）"
      />
      <template #footer>
        <el-button @click="forwardDialog = false">取消</el-button>
        <el-button type="primary" :loading="forwarding" @click="submitForward">
          确认{{ forwardMode === 'transfer' ? '转交' : '加签' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog append-to-body v-model="rollbackDialog" title="回退到指定节点" width="460px">
      <p class="dialog-sub">{{ rollbackTask?.title }}（{{ rollbackTask?.bizNo }}）· 当前在「{{ rollbackTask?.nodeName }}」</p>
      <p class="dialog-tip">回退后这张单会重新停在所选节点，那一级的审批人要重新处理。</p>
      <el-select v-model="rollbackTarget" placeholder="选择要回退到的节点" style="width: 100%">
        <el-option
          v-for="item in rollbackTargets"
          :key="item.nodeKey"
          :label="item.nodeName"
          :value="item.nodeKey"
        />
      </el-select>
      <el-input
        v-model="rollbackComment"
        type="textarea"
        :rows="3"
        style="margin-top: 10px"
        placeholder="回退原因（可选）"
      />
      <template #footer>
        <el-button @click="rollbackDialog = false">取消</el-button>
        <el-button type="primary" :loading="rollingBack" @click="submitRollback">确认回退</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.form-block {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.form-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.form-label {
  flex: none;
  width: 76px;
  font-size: 12px;
  color: #475569;
}

.form-value {
  font-size: 12px;
  color: #0f172a;
}

.form-tip {
  font-size: 11px;
  color: #94a3b8;
}

.sign-block {
  margin-bottom: 12px;
}

.sign-canvas {
  display: block;
  width: 100%;
  height: 140px;
  margin: 6px 0;
  background: #ffffff;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  cursor: crosshair;
  touch-action: none;
}

.tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 999px;
  background: #f97316;
  color: #ffffff;
  font-size: 11px;
}

.panel {
  padding: 14px 16px;
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 10px;
}

.flow-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  gap: 12px;
}

.node-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 2px 0;
  font-size: 12px;
  color: #475569;
}

.node-seq {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 999px;
  background: #eef2ff;
  color: #4f46e5;
  font-size: 11px;
  flex: none;
}

.node-name {
  color: #0f172a;
  font-weight: 500;
}

.node-meta {
  color: #94a3b8;
}

.chip {
  padding: 0 6px;
  border-radius: 999px;
  background: #eef2ff;
  color: #4f46e5;
  font-size: 11px;
  line-height: 18px;
}

.table-tip {
  margin-top: 10px;
  color: #94a3b8;
  font-size: 12px;
}

.dialog-sub {
  margin: 0 0 10px;
  color: #475569;
  font-size: 13px;
}

.dialog-tip {
  margin: 0 0 10px;
  color: #94a3b8;
  font-size: 12px;
}
</style>
