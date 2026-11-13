<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import ReferenceSelect from '../../components/ReferenceSelect.vue'

const REFRESH_MS = 30000
const OVERDUE_MINUTES = 30

const TYPE_MAP: Record<string, { label: string; cls: string }> = {
  MATERIAL: { label: '缺料', cls: 'type-material' },
  EQUIPMENT: { label: '设备', cls: 'type-equipment' },
  QUALITY: { label: '质量', cls: 'type-quality' },
  OTHER: { label: '其他', cls: 'type-other' }
}

const STATUS_MAP: Record<string, { label: string; cls: string }> = {
  OPEN: { label: '待响应', cls: 'danger' },
  ACKED: { label: '处理中', cls: 'warning' },
  RESOLVED: { label: '已解决', cls: 'success' }
}

const pad = (value: number) => String(value).padStart(2, '0')

const loading = ref(false)
const refreshing = ref(false)
const loaded = ref(false)
const rows = ref<any[]>([])
const summary = ref<any>({})
const summaryReady = ref(true)
const statusFilter = ref('')
const keyword = ref('')
const lastUpdated = ref('')
const actingId = ref<number | null>(null)
const route = useRoute()
let timer: number | undefined

const dialogVisible = ref(false)
const saving = ref(false)
const operationOptions = ref<any[]>([])
const operationLoading = ref(false)
const form = reactive<any>({ workOrderId: null, operationName: '', type: 'MATERIAL', description: '' })

const typeOf = (type: any) => TYPE_MAP[String(type || '')] || { label: type || '-', cls: 'type-other' }
const statusOf = (status: any) =>
  STATUS_MAP[String(status || '')] || { label: status || '-', cls: 'info' }

const stats = computed(() => {
  const data = summary.value || {}
  const list = rows.value
  const countOf = (status: string) => list.filter((row) => row.status === status).length
  return {
    open: data.open ?? countOf('OPEN'),
    acked: data.acked ?? countOf('ACKED'),
    resolvedToday: data.resolvedToday ?? 0,
    avgAckMinutes: data.avgAckMinutes ?? 0
  }
})

const avgAckText = computed(() => {
  const value = Number(stats.value.avgAckMinutes || 0)
  if (!value) return '0'
  return value >= 10 ? String(Math.round(value)) : value.toFixed(1)
})

const elapsedOf = (row: any) => {
  const value = row?.elapsedMinutes
  if (value !== null && value !== undefined && value !== '') return Number(value)
  if (!row?.createdAt) return 0
  const end = row.resolvedAt ? new Date(String(row.resolvedAt).replace(' ', 'T')) : new Date()
  const start = new Date(String(row.createdAt).replace(' ', 'T'))
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return 0
  return Math.max(0, Math.floor((end.getTime() - start.getTime()) / 60000))
}

const fmtElapsed = (row: any) => {
  const minutes = elapsedOf(row)
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
}

const isOverdue = (row: any) =>
  String(row?.status || '') === 'OPEN' && elapsedOf(row) >= OVERDUE_MINUTES

const rowClass = ({ row }: { row: any }) => (isOverdue(row) ? 'row-overdue' : '')

const fmtTime = (value: any) => (value ? String(value).replace('T', ' ').slice(0, 16) : '-')

const loadSummary = async () => {
  if (!summaryReady.value) return
  try {
    summary.value = (await api.get('/andon/summary')) || {}
  } catch {
    summaryReady.value = false
  }
}

const load = async () => {
  if (refreshing.value) return
  refreshing.value = true
  if (!loaded.value) loading.value = true
  try {
    const data = await api.get('/andon/calls', {
      status: statusFilter.value || undefined,
      keyword: keyword.value.trim() || undefined
    })
    rows.value = Array.isArray(data) ? data : data?.records || []
    loaded.value = true
    await loadSummary()
    const now = new Date()
    lastUpdated.value = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
  } finally {
    refreshing.value = false
    loading.value = false
  }
}

const openCreate = async () => {
  Object.assign(form, { workOrderId: null, operationName: '', type: 'MATERIAL', description: '' })
  operationOptions.value = []
  dialogVisible.value = true
}

const onWorkOrderChange = async (id: any) => {
  form.operationName = ''
  operationOptions.value = []
  if (!id) return
  operationLoading.value = true
  try {
    const detail = await api.get(`/work-orders/${id}`)
    operationOptions.value = (detail?.operations || []).map((op: any) => ({
      name: op.name,
      seq: op.seq
    }))
    const current = (detail?.operations || []).find(
      (op: any) => Number(op.doneQty) < Number(op.planQty)
    )
    if (current) form.operationName = current.name
  } catch {
    operationOptions.value = []
  } finally {
    operationLoading.value = false
  }
}

const submit = async () => {
  if (!form.workOrderId) {
    ElMessage.warning('请选择工单')
    return
  }
  if (!form.type) {
    ElMessage.warning('请选择异常类型')
    return
  }
  if (!String(form.description || '').trim()) {
    ElMessage.warning('请填写异常描述')
    return
  }
  saving.value = true
  try {
    await api.post('/andon/calls', {
      workOrderId: form.workOrderId,
      operationName: form.operationName ? String(form.operationName).trim() : null,
      type: form.type,
      description: String(form.description).trim()
    })
    ElMessage.success('异常已呼叫，等待处理')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

const ack = async (row: any) => {
  actingId.value = row.id
  try {
    await api.post(`/andon/calls/${row.id}/ack`)
    ElMessage.success(`已接单：${row.callNo || ''}`)
    await load()
  } finally {
    actingId.value = null
  }
}

const resolve = async (row: any) => {
  actingId.value = row.id
  try {
    await api.post(`/andon/calls/${row.id}/resolve`)
    ElMessage.success(`已解决：${row.callNo || ''}`)
    await load()
  } finally {
    actingId.value = null
  }
}

onMounted(() => {
  // 从消息中心跳过来：带上安灯号，直接定位到这一条
  const fromQuery = String(route.query.keyword || '').trim()
  if (fromQuery) {
    keyword.value = fromQuery
    statusFilter.value = ''
  }
  load()
  timer = window.setInterval(() => {
    if (!document.hidden) load()
  }, REFRESH_MS)
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">异常安灯</h2>
        <p class="page-sub">
          缺料、设备、质量异常一键呼叫，30 分钟内未响应自动标红
          <span v-if="lastUpdated">· 更新于 {{ lastUpdated }}</span>
          · 每 30 秒自动刷新
        </p>
      </div>
      <div class="head-actions">
        <el-button :loading="refreshing" @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon><Bell /></el-icon>
          <span>呼叫异常</span>
        </el-button>
      </div>
    </div>

    <div class="kpi-grid kpi-4">
      <div class="kpi-card" :class="{ 'kpi-alert': Number(stats.open) > 0 }">
        <div class="kpi-label">待响应</div>
        <div class="kpi-value">{{ stats.open }}<small>单</small></div>
        <div class="kpi-foot">OPEN，尚未有人接单</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">处理中</div>
        <div class="kpi-value">{{ stats.acked }}<small>单</small></div>
        <div class="kpi-foot">ACKED，已接单未解决</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">今日已解决</div>
        <div class="kpi-value">{{ stats.resolvedToday }}<small>单</small></div>
        <div class="kpi-foot">今日 RESOLVED 的呼叫</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">平均响应</div>
        <div class="kpi-value">{{ avgAckText }}<small>分钟</small></div>
        <div class="kpi-foot">呼叫做单到接单的平均耗时</div>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-select
          v-model="statusFilter"
          placeholder="全部状态"
          clearable
          style="width: 140px"
          @change="load"
        >
          <el-option label="待响应" value="OPEN" />
          <el-option label="处理中" value="ACKED" />
          <el-option label="已解决" value="RESOLVED" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="安灯号 / 工单号 / 异常描述"
          clearable
          style="width: 240px"
          @keyup.enter="load"
          @clear="load"
        />
        <el-button type="primary" plain @click="load">查询</el-button>
        <div class="spacer"></div>
        <span class="toolbar-tip">共 {{ rows.length }} 条呼叫记录</span>
      </div>

      <div class="table-wrap">
        <el-table :data="rows" stripe :row-class-name="rowClass" empty-text="暂无安灯呼叫">
          <el-table-column prop="callNo" label="呼叫单号" width="140">
            <template #default="{ row }"><span class="mono">{{ row.callNo || '-' }}</span></template>
          </el-table-column>
          <el-table-column label="工单 / 工序" min-width="150">
            <template #default="{ row }">
              <span class="mono">{{ row.workOrderNo || '-' }}</span>
              <div class="muted" style="font-size: 11px">
                {{ row.operationName || '未指定工序' }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="80">
            <template #default="{ row }">
              <span class="type-tag" :class="typeOf(row.type).cls">{{ typeOf(row.type).label }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <span class="status-tag" :class="statusOf(row.status).cls">
                <i></i>{{ statusOf(row.status).label }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="呼叫 / 时间" width="110">
            <template #default="{ row }">
              <span>{{ row.caller || '-' }}</span>
              <div class="muted" style="font-size: 11px">
                {{ fmtTime(row.createdAt) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="处理人" width="92">
            <template #default="{ row }">
              <span v-if="row.handler">{{ row.handler }}</span>
              <span v-else class="muted">待接单</span>
            </template>
          </el-table-column>
          <el-table-column label="已耗时" width="110" align="right">
            <template #default="{ row }">
              <span class="elapsed" :class="{ 'elapsed-overdue': isOverdue(row) }">
                {{ fmtElapsed(row) }}
              </span>
              <div v-if="isOverdue(row)" class="muted" style="font-size: 11px">超 30 分钟未响应</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'OPEN'"
                link
                type="primary"
                :loading="actingId === row.id"
                @click="ack(row)"
              >接单</el-button>
              <el-button
                v-if="row.status === 'OPEN' || row.status === 'ACKED'"
                link
                type="success"
                :loading="actingId === row.id"
                @click="resolve(row)"
              >解决</el-button>
              <span v-if="row.status === 'RESOLVED'" class="muted">已完成</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialogVisible" title="呼叫异常" width="560px">
      <el-form label-position="top">
        <el-form-item label="工单" required>
          <ReferenceSelect
            v-model="form.workOrderId"
            url="/work-orders/options"
            :params="{ status: 'SCHEDULED,RELEASED,IN_PROGRESS' }"
            code-field="workNo"
            label-field="productName"
            placeholder="输入工单号或产品搜索（已排产/已下达/生产中）"
            @select="(work) => onWorkOrderChange(work ? work.id : null)"
          />
        </el-form-item>
        <el-form-item label="工序">
          <el-select
            v-model="form.operationName"
            filterable
            allow-create
            default-first-option
            clearable
            :loading="operationLoading"
            placeholder="可不选，或直接输入工序名称"
            style="width: 100%"
          >
            <el-option
              v-for="op in operationOptions"
              :key="op.name"
              :label="`${op.seq}. ${op.name}`"
              :value="op.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="异常类型" required>
          <el-radio-group v-model="form.type">
            <el-radio-button label="MATERIAL">缺料</el-radio-button>
            <el-radio-button label="EQUIPMENT">设备</el-radio-button>
            <el-radio-button label="QUALITY">质量</el-radio-button>
            <el-radio-button label="OTHER">其他</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="异常描述" required>
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="如：3 号机床主轴异响，需维修处理"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">提交呼叫</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.kpi-grid.kpi-4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

@media (max-width: 1100px) {
  .kpi-grid.kpi-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.type-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 9px;
  border-radius: 20px;
  font-size: 12px;
  line-height: 19px;
  border: 1px solid transparent;
  white-space: nowrap;
}

.type-tag::before {
  content: "";
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}

.type-material {
  color: #b54708;
  background: #fffaeb;
  border-color: #fedf89;
}

.type-equipment {
  color: #b42318;
  background: #fef3f2;
  border-color: #fecdca;
}

.type-quality {
  color: #5925dc;
  background: #f4f3ff;
  border-color: #d9d6fe;
}

.type-other {
  color: #475467;
  background: #f2f4f7;
  border-color: #e4e7ec;
}

.elapsed {
  font-family: Bahnschrift, "DIN Alternate", monospace;
  font-variant-numeric: tabular-nums;
}

.elapsed-overdue {
  color: var(--danger);
  font-weight: 600;
}

:deep(.el-table__row.row-overdue) td.el-table__cell {
  background: #fef3f2 !important;
}

:deep(.el-table__row.row-overdue:hover > td.el-table__cell) {
  background: #fee4e2 !important;
}
</style>
