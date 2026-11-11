<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtQty } from '../../api'
import PriorityTag from '../../components/PriorityTag.vue'
import StatusTag from '../../components/StatusTag.vue'

const pad = (value: number) => String(value).padStart(2, '0')
const toDateStr = (date: Date) =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
const todayStr = toDateStr(new Date())
const WEEK = ['日', '一', '二', '三', '四', '五', '六']
const PRIORITY_RANK: Record<string, number> = { RUSH: 0, URGENT: 1, NORMAL: 2 }

const loading = ref(false)
const actingId = ref<number | null>(null)
const planDate = ref(todayStr)
const board = ref<any>({ unscheduled: [], scheduled: [] })

const autoDialog = ref(false)
const autoRunning = ref(false)
const autoResult = ref<any>(null)
const autoForm = reactive<any>({ startDate: todayStr, days: 14, dailyCapacity: 480 })

const openAuto = () => {
  autoResult.value = null
  autoForm.startDate = planDate.value || todayStr
  autoForm.days = 14
  autoForm.dailyCapacity = 480
  autoDialog.value = true
}

const runAuto = async () => {
  autoRunning.value = true
  try {
    const data = await api.post('/scheduling/auto', {
      startDate: autoForm.startDate,
      days: Number(autoForm.days),
      dailyCapacity: Number(autoForm.dailyCapacity)
    })
    autoResult.value = data
    await load()
    ElMessage.success(`已智能排产 ${data.scheduledCount} 张工单`)
  } finally {
    autoRunning.value = false
  }
}

const scheduleDialog = ref(false)
const scheduleSaving = ref(false)
const scheduleTarget = ref<any>(null)
const scheduleForm = reactive<any>({ date: todayStr, sequence: null })

const apsDialog = ref(false)
const apsRunning = ref(false)
const apsResult = ref<any>(null)
const apsForm = reactive<any>({ startDate: todayStr, days: 7 })

const openAps = () => {
  apsResult.value = null
  apsForm.startDate = planDate.value || todayStr
  apsForm.days = 7
  apsDialog.value = true
}

const runAps = async () => {
  if (!apsForm.startDate) {
    ElMessage.warning('请选择开始日期')
    return
  }
  apsRunning.value = true
  try {
    const data = await api.post('/scheduling/aps', {
      startDate: apsForm.startDate,
      days: Number(apsForm.days)
    })
    apsResult.value = data || {}
    await load()
    ElMessage.success(`有限产能排产完成，已排 ${data?.scheduledCount ?? 0} 张工单`)
  } finally {
    apsRunning.value = false
  }
}

const apsAssignments = computed(() => apsResult.value?.assignments || [])
const apsDailyLoad = computed(() => apsResult.value?.dailyLoad || [])

const timeText = (value: any) => {
  const text = String(value || '')
  if (!text) return '-'
  const index = text.indexOf('T')
  if (index >= 0) return text.slice(index + 1, index + 6)
  if (text.length >= 16) return text.slice(11, 16)
  return text
}

const timeRange = (row: any) => `${timeText(row?.startAt)}–${timeText(row?.endAt)}`

const loadRate = (row: any) => {
  const capacity = Number(row?.capacity || 0)
  if (!capacity) return 0
  return Math.round((Number(row?.minutes || 0) / capacity) * 100)
}

const overdueLoad = (row: any) => loadRate(row) > 100

const priorityRank = (priority: any) => PRIORITY_RANK[priority || 'NORMAL'] ?? 3

const dateLabel = computed(() => {
  if (!planDate.value) return '未选择日期'
  const day = new Date(`${planDate.value}T00:00:00`)
  return `${planDate.value} 周${WEEK[day.getDay()]}`
})

const unscheduled = computed(() =>
  [...(board.value.unscheduled || [])].sort((a: any, b: any) => {
    const rank = priorityRank(a.priority) - priorityRank(b.priority)
    if (rank !== 0) return rank
    const due = String(a.deliveryDate || '9999-99-99').localeCompare(String(b.deliveryDate || '9999-99-99'))
    if (due !== 0) return due
    return String(a.createdAt || '').localeCompare(String(b.createdAt || ''))
  })
)

const queue = computed(() =>
  (board.value.scheduled || [])
    .filter((row: any) => row.scheduleDate === planDate.value)
    .sort((a: any, b: any) => Number(a.scheduleSeq || 0) - Number(b.scheduleSeq || 0))
)

const isOverdue = (date: any) => !!date && String(date) < todayStr

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/scheduling/board')
    board.value = {
      unscheduled: data?.unscheduled || [],
      scheduled: data?.scheduled || []
    }
  } finally {
    loading.value = false
  }
}

const openSchedule = (row: any) => {
  scheduleTarget.value = row
  scheduleForm.date = planDate.value || todayStr
  scheduleForm.sequence = null
  scheduleDialog.value = true
}

const submitSchedule = async () => {
  if (!scheduleForm.date) {
    ElMessage.warning('请选择排产日期')
    return
  }
  if (!scheduleTarget.value?.id) {
    ElMessage.warning('工单信息不完整，请刷新后重试')
    return
  }
  scheduleSaving.value = true
  try {
    await api.post(`/scheduling/${scheduleTarget.value.id}/schedule`, {
      date: scheduleForm.date,
      sequence:
        scheduleForm.sequence === null || scheduleForm.sequence === undefined || scheduleForm.sequence === ''
          ? null
          : Number(scheduleForm.sequence)
    })
    ElMessage.success('排产成功')
    scheduleDialog.value = false
    if (scheduleForm.date !== planDate.value) planDate.value = scheduleForm.date
    await load()
  } finally {
    scheduleSaving.value = false
  }
}

const runAction = async (row: any, action: () => Promise<any>, message: string) => {
  if (actingId.value !== null) return
  actingId.value = row.id
  try {
    await action()
    ElMessage.success(message)
    await load()
  } finally {
    actingId.value = null
  }
}

const move = (row: any, direction: 'UP' | 'DOWN', index: number) => {
  if (direction === 'UP' && index === 0) return
  if (direction === 'DOWN' && index === queue.value.length - 1) return
  return runAction(row, () => api.post(`/scheduling/${row.id}/move`, { direction }), '顺序已调整')
}

const rush = async (row: any) => {
  try {
    await ElMessageBox.confirm(
      `将工单 ${row.workNo} 加急插队到 ${dateLabel.value} 的最前？`,
      '加急插队',
      { type: 'warning', confirmButtonText: '确认插队' }
    )
  } catch {
    return
  }
  await runAction(row, () => api.post(`/scheduling/${row.id}/rush`, { date: planDate.value }), '已加急插队')
}

const unschedule = async (row: any) => {
  try {
    await ElMessageBox.confirm(`撤销工单 ${row.workNo} 的排产？撤销后回到待排产。`, '撤销排产', {
      type: 'warning'
    })
  } catch {
    return
  }
  await runAction(row, () => api.post(`/scheduling/${row.id}/unschedule`), '已撤销排产')
}

const release = async (row: any) => {
  try {
    await ElMessageBox.confirm(`下达工单 ${row.workNo}？下达后可以领料和报工。`, '下达确认')
  } catch {
    return
  }
  await runAction(row, () => api.post(`/work-orders/${row.id}/release`), '工单已下达')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">排产计划</h2>
        <p class="page-sub">按交期与优先级把待排工单放进日计划，加急插队、调整顺序后即可下达车间；有限产能排产按工序占用工作中心日产能自动排期</p>
      </div>
      <div class="head-actions">
        <el-button type="primary" @click="openAuto">一键智能排产</el-button>
        <el-button v-perm="'production:schedule'" type="primary" plain @click="openAps">有限产能排产</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="panel plan-toolbar">
      <span class="toolbar-label">排产日期</span>
      <el-date-picker
        v-model="planDate"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择日期"
        style="width: 160px"
      />
      <span class="toolbar-tip">{{ dateLabel }}</span>
      <div class="spacer"></div>
      <span class="toolbar-tip">待排 {{ unscheduled.length }} 张 · 当日已排 {{ queue.length }} 张</span>
    </div>

    <div class="plan-grid">
      <div class="panel plan-panel">
        <div class="card-head">
          <div class="card-title">待排产</div>
          <span class="count-pill draft">{{ unscheduled.length }}</span>
        </div>
        <div class="table-wrap">
          <el-table v-loading="loading" :data="unscheduled" stripe max-height="calc(100vh - 300px)" empty-text="暂无待排产工单">
            <el-table-column label="工单号" width="138">
              <template #default="{ row }">
                <div class="mono">{{ row.workNo }}</div>
                <div class="row-sub">{{ row.orderNo || '手动建单' }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="productName" label="产品" min-width="112" show-overflow-tooltip />
            <el-table-column label="数量" width="64" align="right">
              <template #default="{ row }"><span class="num">{{ fmtQty(row.planQty) }}</span></template>
            </el-table-column>
            <el-table-column label="优先级" width="76">
              <template #default="{ row }"><PriorityTag :priority="row.priority" /></template>
            </el-table-column>
            <el-table-column label="订单交期" width="98">
              <template #default="{ row }">
                <span v-if="row.deliveryDate" class="mono" :class="{ 'danger-text': isOverdue(row.deliveryDate) }">
                  {{ row.deliveryDate }}
                </span>
                <span v-else class="muted">-</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="64" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="actingId !== null" @click="openSchedule(row)">排产</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <div class="panel plan-panel">
        <div class="card-head">
          <div class="card-title">当日队列</div>
          <div class="queue-head-right">
            <span class="queue-date">{{ dateLabel }}</span>
            <span class="count-pill primary">{{ queue.length }}</span>
          </div>
        </div>
        <div class="table-wrap">
          <el-table v-loading="loading" :data="queue" stripe max-height="calc(100vh - 300px)" empty-text="当日还没有排产工单">
            <el-table-column label="序号" width="58">
              <template #default="{ row }">
                <span class="seq-badge mono">{{ row.scheduleSeq ?? '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="工单" width="132">
              <template #default="{ row }">
                <div class="mono">{{ row.workNo }}</div>
                <div class="row-sub">{{ row.orderNo || '手动建单' }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="productName" label="产品" min-width="104" show-overflow-tooltip />
            <el-table-column label="数量" width="64" align="right">
              <template #default="{ row }"><span class="num">{{ fmtQty(row.planQty) }}</span></template>
            </el-table-column>
            <el-table-column label="优先级" width="76">
              <template #default="{ row }"><PriorityTag :priority="row.priority" /></template>
            </el-table-column>
            <el-table-column label="状态" width="86">
              <template #default="{ row }"><StatusTag :status="row.status" /></template>
            </el-table-column>
            <el-table-column label="操作" width="212" fixed="right">
              <template #default="{ row, $index }">
                <div class="queue-actions">
                  <template v-if="row.status === 'SCHEDULED'">
                    <el-button link type="primary" :disabled="actingId !== null || $index === 0" @click="move(row, 'UP', $index)">上移</el-button>
                    <el-button link type="primary" :disabled="actingId !== null || $index === queue.length - 1" @click="move(row, 'DOWN', $index)">下移</el-button>
                    <el-button link type="danger" :disabled="actingId !== null" @click="rush(row)">加急</el-button>
                    <el-button link type="warning" :disabled="actingId !== null" @click="unschedule(row)">撤销</el-button>
                    <el-button link type="success" :disabled="actingId !== null" @click="release(row)">下达</el-button>
                  </template>
                  <span v-else class="muted">已进入车间执行</span>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="scheduleDialog" title="排产工单" width="480px">
      <div v-if="scheduleTarget" class="schedule-head">
        <div>
          <b class="mono">{{ scheduleTarget.workNo }}</b>
          <span class="muted">{{ scheduleTarget.productName }} × {{ fmtQty(scheduleTarget.planQty) }}</span>
        </div>
        <PriorityTag :priority="scheduleTarget.priority" />
      </div>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="排产日期" required>
            <el-date-picker v-model="scheduleForm.date" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="顺序（留空自动追加）">
            <el-input-number v-model="scheduleForm.sequence" :min="1" :precision="0" placeholder="自动" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="muted" style="font-size: 12px">
          订单交期 {{ scheduleTarget?.deliveryDate || '未填写' }}，留空顺序时系统按当日最大序号 +10 追加。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="scheduleDialog = false">取消</el-button>
        <el-button type="primary" :loading="scheduleSaving" @click="submitSchedule">确认排产</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="autoDialog" title="一键智能排产" width="720px">
      <template v-if="!autoResult">
        <el-form label-width="110px">
          <el-form-item label="开始日期">
            <el-date-picker v-model="autoForm.startDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="排产天数">
            <el-input-number v-model="autoForm.days" :min="1" :max="60" />
          </el-form-item>
          <el-form-item label="每日工时">
            <el-input-number v-model="autoForm.dailyCapacity" :min="60" :max="1440" :step="30" />
            <span class="auto-unit">分钟/天</span>
          </el-form-item>
        </el-form>
        <div class="auto-note">
          系统按「特急 → 加急 → 普通」、订单交期、创建时间排序，把待排工单依次放进每日可用工时，已排产工单占用的工时也会扣除。超过交期的工单会在结果里标红。
        </div>
      </template>
      <template v-else>
        <div class="auto-summary">
          <span>已排产 <b>{{ autoResult.scheduledCount }}</b> 张</span>
          <span>延期风险 <b class="danger">{{ autoResult.lateCount }}</b> 张</span>
          <span>未排下 <b>{{ autoResult.skippedCount }}</b> 张</span>
          <span>每日工时 {{ autoResult.dailyCapacity }} 分钟</span>
        </div>
        <el-table :data="autoResult.days" size="small" max-height="calc(70vh - 320px)">
          <el-table-column prop="date" label="日期" width="120" />
          <el-table-column prop="workOrders" label="工单数" width="90" align="right" />
          <el-table-column label="工时占用" min-width="180" align="right">
            <template #default="{ row }">
              {{ Math.round(row.usedMinutes) }} / {{ row.capacityMinutes }} 分钟
            </template>
          </el-table-column>
          <el-table-column label="负载" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.overloaded" type="danger" size="small">超负荷</el-tag>
              <el-tag v-else type="success" size="small">正常</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-table :data="autoResult.assignments" size="small" max-height="calc(70vh - 380px)" style="margin-top: 12px">
          <el-table-column prop="workNo" label="工单号" width="170" />
          <el-table-column prop="productName" label="产品" min-width="130" />
          <el-table-column label="优先级" width="90">
            <template #default="{ row }"><PriorityTag :priority="row.priority" /></template>
          </el-table-column>
          <el-table-column prop="scheduleDate" label="排产日期" width="110" />
          <el-table-column prop="deliveryDate" label="订单交期" width="110" />
          <el-table-column label="风险" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.late" type="danger" size="small">延期</el-tag>
              <span v-else class="muted">正常</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="autoDialog = false">关闭</el-button>
        <el-button v-if="!autoResult" type="primary" :loading="autoRunning" @click="runAuto">开始排产</el-button>
        <el-button v-else type="primary" @click="openAuto">再排一次</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="apsDialog" title="有限产能排产" width="1000px">
      <template v-if="!apsResult">
        <el-form label-width="110px">
          <el-form-item label="开始日期">
            <el-date-picker v-model="apsForm.startDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
          </el-form-item>
          <el-form-item label="排产天数">
            <el-input-number v-model="apsForm.days" :min="1" :max="60" />
            <span class="auto-unit">天（1–60）</span>
          </el-form-item>
        </el-form>
        <div class="auto-note">
          有限产能按工序占用工作中心日产能（下料 / 装配 480 分钟、机加工 360 分钟、终检 240 分钟），单道工序不跨天，超过日产能顺延到次日。系统按交期与优先级排队，把每道工序排到对应工作中心的可用日期。
        </div>
      </template>
      <template v-else>
        <div class="auto-summary">
          <span>已排工单 <b>{{ apsResult.scheduledCount ?? 0 }}</b> 张</span>
          <span>交期风险 <b class="danger">{{ apsResult.lateCount ?? 0 }}</b> 张</span>
          <span>未排入 <b>{{ apsResult.skippedCount ?? 0 }}</b> 张</span>
          <span>排产天数 <b>{{ apsResult.days ?? apsForm.days }}</b> 天</span>
        </div>

        <div class="aps-section-title">工序级计划</div>
        <el-table :data="apsAssignments" size="small" max-height="calc(70vh - 320px)" empty-text="没有可排产的工序">
          <el-table-column prop="workNo" label="工单号" width="150" />
          <el-table-column prop="productName" label="产品" min-width="110" show-overflow-tooltip />
          <el-table-column label="工序" width="135">
            <template #default="{ row }">{{ row.seq }}. {{ row.operationName || row.processName }}</template>
          </el-table-column>
          <el-table-column prop="workCenterName" label="工作中心" width="110" />
          <el-table-column prop="scheduleDate" label="排产日期" width="105" />
          <el-table-column label="开始–结束" width="110">
            <template #default="{ row }"><span class="mono">{{ timeRange(row) }}</span></template>
          </el-table-column>
          <el-table-column label="标准工时" width="95" align="right">
            <template #default="{ row }">{{ Math.round(Number(row.minutes || 0)) }} 分钟</template>
          </el-table-column>
          <el-table-column label="日产能" width="85" align="right">
            <template #default="{ row }">{{ Math.round(Number(row.capacity || 0)) }}</template>
          </el-table-column>
        </el-table>

        <div class="aps-section-title">每日负荷</div>
        <el-table :data="apsDailyLoad" size="small" max-height="calc(70vh - 360px)" empty-text="暂无负荷数据">
          <el-table-column prop="date" label="日期" width="120" />
          <el-table-column prop="workCenterName" label="工作中心" width="120" />
          <el-table-column label="占用分钟" min-width="100" align="right">
            <template #default="{ row }">{{ Math.round(Number(row.minutes || 0)) }}</template>
          </el-table-column>
          <el-table-column label="日产能" width="100" align="right">
            <template #default="{ row }">{{ Math.round(Number(row.capacity || 0)) }}</template>
          </el-table-column>
          <el-table-column label="负载率" width="110" align="right">
            <template #default="{ row }">
              <span :class="overdueLoad(row) ? 'danger-text' : ''">{{ loadRate(row) }}%</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="apsDialog = false">关闭</el-button>
        <el-button v-if="!apsResult" type="primary" :loading="apsRunning" @click="runAps">开始排产</el-button>
        <el-button v-else type="primary" @click="openAps">再排一次</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.plan-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  margin-bottom: 16px;
}

.plan-toolbar .spacer {
  flex: 1;
}

.toolbar-label {
  font-size: 12px;
  color: var(--ink-2);
  font-weight: 600;
}

.plan-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.86fr) minmax(0, 1.14fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 1180px) {
  .plan-grid {
    grid-template-columns: 1fr;
  }
}

.plan-panel {
  overflow: hidden;
}

.count-pill {
  min-width: 24px;
  height: 20px;
  padding: 0 8px;
  border-radius: 20px;
  display: inline-grid;
  place-items: center;
  font-size: 11px;
  font-weight: 600;
  font-family: Bahnschrift, monospace;
}

.count-pill.draft {
  color: #475467;
  background: #f2f4f7;
  border: 1px solid #e4e7ec;
}

.count-pill.primary {
  color: #1d4ed8;
  background: #eff4ff;
  border: 1px solid #d1e0ff;
}

.queue-head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.queue-date {
  font-size: 11px;
  color: var(--muted);
}

.row-sub {
  font-size: 11px;
  color: var(--muted);
  line-height: 1.4;
}

.seq-badge {
  width: 24px;
  height: 22px;
  border-radius: 6px;
  background: #eef3ff;
  color: #2456d8;
  display: inline-grid;
  place-items: center;
  font-size: 12px;
  font-weight: 600;
}

.queue-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px;
}

.queue-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.schedule-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  margin-bottom: 14px;
  background: #f8fafc;
  border-radius: 6px;
}

.schedule-head b {
  margin-right: 10px;
}
.auto-note {
  padding: 10px 12px;
  border-radius: 8px;
  background: #f6f8fb;
  color: #5b677a;
  font-size: 12px;
  line-height: 1.7;
}

.auto-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  padding: 12px 14px;
  margin-bottom: 12px;
  border-radius: 8px;
  background: #f6f8fb;
  font-size: 13px;
}

.auto-summary b {
  color: #1f2d3d;
  font-size: 16px;
}

.auto-summary .danger {
  color: #d94a4a;
}

.auto-unit {
  margin-left: 8px;
  color: #8a94a6;
  font-size: 12px;
}

.aps-section-title {
  margin: 14px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-2);
}

.head-actions {
  display: flex;
  gap: 8px;
}
</style>
