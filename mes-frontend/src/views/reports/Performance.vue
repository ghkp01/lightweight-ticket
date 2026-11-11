<script setup lang="ts">
/**
 * 绩效核算：按月度期间生成计件绩效单，录入实际工时，提交后审核。
 *
 * 口径与后端 PerformanceService 一致：系统只算「报工量 × 工序单价」的计件工资与标准工时，
 * 效率要除以实际工时 —— 而工时系统里没有考勤数据，所以这一列留给人填。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../../api'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()

const today = new Date()
const currentPeriod = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`

const period = ref(currentPeriod)
const status = ref('')
const keyword = ref('')
const loading = ref(false)
const generating = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const summary = ref<any>({})

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive<{ id: number | null; worker: string; standardMinutes: any; actualMinutes: any; remark: string }>({
  id: null,
  worker: '',
  standardMinutes: 0,
  actualMinutes: null,
  remark: ''
})

const statusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' }
]

const canApprove = computed(() => auth.can('perf:approve'))
// 撤回重算会把已通过的工资数据退回草稿，只给管理员
const canReopen = computed(() => auth.user?.role === 'ADMIN')
const missingMinutes = computed(() => Number(summary.value?.missingMinutes || 0))

// ---------------------------------------------------------------- 工时填报
const hoursVisible = ref(false)
const hoursLoading = ref(false)
const hourSaving = ref(false)
const hoursSummary = ref<any>({ rows: [] })
const hoursRows = ref<any[]>([])
const hourForm = reactive<{ workDate: string; worker: string; minutes: any; remark: string }>({
  workDate: new Date().toISOString().slice(0, 10),
  worker: '',
  minutes: 480,
  remark: ''
})
// 批量模式：同一天、同样时长，一次给一个班组各记一条
const hourBatch = ref(false)
const batchWorkers = ref<string[]>([])

const workerOptions = computed(() =>
  (hoursSummary.value?.rows || []).map((row: any) => String(row.worker))
)
const workerToFill = computed(() =>
  (hoursSummary.value?.rows || [])
    .filter((row: any) => Number(row.minutes || 0) <= 0)
    .map((row: any) => String(row.worker))
)

const loadHours = async () => {
  hoursLoading.value = true
  try {
    const [summaryData, detailData] = await Promise.all([
      api.get('/performance/hours/summary', { period: period.value || undefined }),
      api.get('/performance/hours', { period: period.value || undefined })
    ])
    hoursSummary.value = summaryData || { rows: [] }
    hoursRows.value = detailData?.rows || []
  } finally {
    hoursLoading.value = false
  }
}

const openHours = async () => {
  hoursVisible.value = true
  await loadHours()
}

const pickWorker = (worker: string) => {
  hourBatch.value = false
  batchWorkers.value = []
  hourForm.worker = worker
}

const saveHour = async () => {
  if (!(Number(hourForm.minutes) > 0)) {
    ElMessage.warning('工时分钟数必须大于 0')
    return
  }
  if (hourBatch.value) {
    if (!batchWorkers.value.length) {
      ElMessage.warning('请选择要填报的员工')
      return
    }
  } else if (!hourForm.worker) {
    ElMessage.warning('请填写员工')
    return
  }
  hourSaving.value = true
  try {
    if (hourBatch.value) {
      const result = await api.post('/performance/hours/batch', {
        workDate: hourForm.workDate,
        minutes: hourForm.minutes,
        workers: batchWorkers.value,
        remark: hourForm.remark
      })
      ElMessage.success(`已给 ${result?.count ?? batchWorkers.value.length} 人记工时，重新生成绩效即可生效`)
      batchWorkers.value = []
    } else {
      await api.post('/performance/hours', {
        workDate: hourForm.workDate,
        worker: hourForm.worker,
        minutes: hourForm.minutes,
        remark: hourForm.remark
      })
      ElMessage.success('工时已记录，重新生成绩效即可生效')
    }
    hourForm.remark = ''
    await loadHours()
  } finally {
    hourSaving.value = false
  }
}

const removeHour = async (row: any) => {
  await ElMessageBox.confirm(`删除「${row.worker}」${row.workDate} 的 ${fmtQty(row.minutes)} 分钟工时？`, '删除工时', {
    type: 'warning'
  })
  await api.delete(`/performance/hours/${row.id}`)
  ElMessage.success('已删除')
  await loadHours()
}

const sourceLabel = (value: string) => {
  if (value === 'HOUR_SHEET') return '工时表'
  if (value === 'MANUAL') return '手工'
  return '—'
}

const pct = (value: any) => {
  if (value === null || value === undefined) return '—'
  return `${(Number(value) * 100).toFixed(1)}%`
}

const statusTag = (value: string) => {
  const map: Record<string, string> = {
    DRAFT: 'info', SUBMITTED: 'warning', APPROVED: 'success', REJECTED: 'danger'
  }
  return map[value] || 'info'
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/performance/sheets', {
      period: period.value || undefined,
      status: status.value || undefined,
      keyword: keyword.value || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = data?.records || []
    total.value = Number(data?.total || 0)
    summary.value = await api.get('/performance/summary', { period: period.value || undefined })
  } finally {
    loading.value = false
  }
}

const generate = async () => {
  generating.value = true
  try {
    const data = await api.post('/performance/generate', { period: period.value })
    ElMessage.success(
      `已生成 ${data.period}：新增 ${data.created} 人、刷新 ${data.refreshed} 人` +
        (Number(data.skipped) > 0 ? `、跳过已锁定 ${data.skipped} 人` : '')
    )
    await load()
  } finally {
    generating.value = false
  }
}

const openMinutes = (row: any) => {
  form.id = row.id
  form.worker = row.worker
  form.standardMinutes = row.standardMinutes
  form.actualMinutes = row.actualMinutes
  form.remark = row.remark || ''
  dialogVisible.value = true
}

const saveMinutes = async () => {
  if (form.id == null) return
  saving.value = true
  try {
    await api.put(`/performance/sheets/${form.id}/minutes`, {
      actualMinutes: form.actualMinutes === '' ? null : form.actualMinutes,
      remark: form.remark
    })
    ElMessage.success('工时已保存，效率与绩效工资已重算')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

const submit = async (row: any) => {
  await ElMessageBox.confirm(`确认提交「${row.worker}」的 ${row.period} 绩效单？提交后不能再改工时。`, '提交绩效', {
    type: 'warning'
  })
  await api.post(`/performance/sheets/${row.id}/submit`)
  ElMessage.success('已提交，等待审核')
  await load()
}

const approve = async (row: any) => {
  await ElMessageBox.confirm(
    `确认通过「${row.worker}」的绩效单？绩效工资 ${fmtMoney(row.wage)} 元。`,
    '审核通过',
    { type: 'info' }
  )
  await api.post(`/performance/sheets/${row.id}/approve`)
  ElMessage.success('已审核通过')
  await load()
}

const reject = async (row: any) => {
  const result = await ElMessageBox.prompt(`驳回「${row.worker}」的绩效单，请填写原因`, '驳回绩效', {
    inputPlaceholder: '例如：实际工时填错，请重新核对',
    inputValidator: (value) => (value && value.trim() ? true : '请填写驳回原因')
  })
  await api.post(`/performance/sheets/${row.id}/reject`, { reason: result.value })
  ElMessage.success('已驳回')
  await load()
}

const changePage = (value: number) => {
  page.value = value
  load()
}

// 算错了要能退回重算：已提交/已通过的都能撤，撤完状态回草稿，工时不丢
const reopen = async (row: any) => {
  const result = await ElMessageBox.prompt(
    `把「${row.worker}」的 ${row.period} 绩效单撤回成草稿？撤回后可以重新核算再提交。`,
    '撤回重算',
    {
      inputPlaceholder: '撤回原因，例如：工时漏填，需要重算',
      inputValidator: (value) => (value && value.trim() ? true : '请填写撤回原因'),
      type: 'warning'
    }
  )
  await api.post(`/performance/sheets/${row.id}/reopen`, { reason: result.value })
  ElMessage.success('已撤回成草稿，可以重新核算')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">绩效核算</h2>
        <p class="page-sub">
          计件工资 × 绩效系数（效率 × 良率）。实际工时由车间录入，未录入时不奖惩。
        </p>
      </div>
      <div class="page-actions">
        <el-date-picker
          v-model="period"
          type="month"
          value-format="YYYY-MM"
          placeholder="选择期间"
          style="width: 150px"
          @change="load"
        />
        <el-select v-model="status" clearable placeholder="全部状态" style="width: 130px" @change="load">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-input
          v-model="keyword"
          clearable
          placeholder="按员工搜索"
          style="width: 170px"
          @keyup.enter="load"
          @clear="load"
        />
        <el-button :loading="generating" type="primary" @click="generate">生成本期绩效</el-button>
        <el-button @click="openHours">工时填报</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-label">参与人数</div>
        <div class="kpi-value">{{ summary.workers || 0 }}<small>人</small></div>
        <div class="kpi-foot">本期有报工记录的人员</div>
      </div>
      <div class="kpi-card" :class="{ 'kpi-alert': missingMinutes > 0 }">
        <div class="kpi-label">未录工时</div>
        <div class="kpi-value">{{ missingMinutes }}<small>人</small></div>
        <div class="kpi-foot">未录时效率为空、系数按 1.0</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">计件工资合计</div>
        <div class="kpi-value">{{ fmtMoney(summary.pieceWage) }}<small>元</small></div>
        <div class="kpi-foot">合格数 × 工序单价</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">绩效工资合计</div>
        <div class="kpi-value">{{ fmtMoney(summary.wage) }}<small>元</small></div>
        <div class="kpi-foot">已通过 {{ summary.approved || 0 }} / 待审 {{ summary.submitted || 0 }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">平均效率</div>
        <div class="kpi-value">{{ pct(summary.avgEfficiency) }}</div>
        <div class="kpi-foot">标准工时 ÷ 实际工时</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">平均良率</div>
        <div class="kpi-value">{{ pct(summary.avgYieldRate) }}</div>
        <div class="kpi-foot">合格量 ÷ 报工量</div>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" stripe height="calc(100vh - 420px)">
      <el-table-column prop="worker" label="员工" min-width="110" fixed />
      <el-table-column prop="period" label="期间" width="100" />
      <el-table-column label="报工/合格" width="130">
        <template #default="{ row }">
          <span class="num">{{ fmtQty(row.reportQty) }}</span>
          <span class="muted"> / {{ fmtQty(row.qualifiedQty) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="良率" width="90">
        <template #default="{ row }">{{ pct(row.yieldRate) }}</template>
      </el-table-column>
      <el-table-column label="标准工时" width="110">
        <template #default="{ row }"><span class="num">{{ fmtQty(row.standardMinutes) }}</span></template>
      </el-table-column>
      <el-table-column label="实际工时" width="120">
        <template #default="{ row }">
          <span v-if="row.actualMinutes !== null && row.actualMinutes !== undefined" class="num">
            {{ fmtQty(row.actualMinutes) }}
          </span>
          <span v-else class="muted">未录入</span>
          <el-tag v-if="row.minutesSource" size="small" effect="plain" class="source-tag">
            {{ sourceLabel(row.minutesSource) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="效率" width="90">
        <template #default="{ row }">{{ pct(row.efficiency) }}</template>
      </el-table-column>
      <el-table-column label="系数" width="80">
        <template #default="{ row }"><span class="num">{{ Number(row.score || 0).toFixed(2) }}</span></template>
      </el-table-column>
      <el-table-column label="计件工资" width="120">
        <template #default="{ row }"><span class="num">{{ fmtMoney(row.pieceWage) }}</span></template>
      </el-table-column>
      <el-table-column label="绩效工资" width="120">
        <template #default="{ row }"><span class="num strong">{{ fmtMoney(row.wage) }}</span></template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" effect="light">{{ row.statusLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="rejectReason" label="驳回原因" min-width="150" show-overflow-tooltip />
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'DRAFT' || row.status === 'REJECTED'"
            link
            type="primary"
            @click="openMinutes(row)"
          >
            录工时
          </el-button>
          <el-button
            v-if="row.status === 'DRAFT' || row.status === 'REJECTED'"
            link
            type="warning"
            @click="submit(row)"
          >
            提交
          </el-button>
          <template v-if="row.status === 'SUBMITTED' && canApprove">
            <!-- 进了审批流的单子只能在审批中心批，这里给个提示而不是让按钮报错 -->
            <el-button v-if="!row.approvalInstanceId" link type="success" @click="approve(row)">通过</el-button>
            <el-button v-if="!row.approvalInstanceId" link type="danger" @click="reject(row)">驳回</el-button>
            <el-tooltip v-else content="已进入审批流，请到审批中心处理">
              <el-tag size="small" type="info">审批中</el-tag>
            </el-tooltip>
          </template>
          <el-button
            v-if="(row.status === 'SUBMITTED' || row.status === 'APPROVED') && canReopen"
            link
            type="info"
            @click="reopen(row)"
          >
            撤回
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        layout="total, prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="changePage"
      />
    </div>

    <el-dialog v-model="hoursVisible" title="工时填报" width="880px" append-to-body>
      <p class="dialog-tip">
        按天填，一天可以填多条（上午 / 下午 / 加班）；绩效生成时按月汇总成「实际工时」，
        效率 = 标准工时 ÷ 实际工时。填完回到列表点「生成本期绩效」才生效。
        同一天同样时长要记给一个班组时，打开「按班组批量」一次选多人。
      </p>
      <div class="hour-form">
        <el-date-picker v-model="hourForm.workDate" type="date" value-format="YYYY-MM-DD"
                        placeholder="工时日期" style="width: 150px" />
        <el-switch v-model="hourBatch" active-text="按班组批量" />
        <el-select
          v-if="hourBatch"
          v-model="batchWorkers"
          multiple
          filterable
          allow-create
          default-first-option
          placeholder="选择员工（可输入新增）"
          style="width: 260px"
        >
          <el-option v-for="name in workerOptions" :key="name" :label="name" :value="name" />
        </el-select>
        <el-input v-else v-model="hourForm.worker" placeholder="员工" style="width: 140px" />
        <el-input v-model="hourForm.minutes" placeholder="分钟" style="width: 110px">
          <template #append>分钟</template>
        </el-input>
        <el-input v-model="hourForm.remark" placeholder="备注（选填）" style="width: 180px" />
        <el-button type="primary" :loading="hourSaving" @click="saveHour">
          {{ hourBatch ? `批量添加 ${batchWorkers.length} 人` : '添加' }}
        </el-button>
        <el-button
          v-if="hourBatch && workerToFill.length"
          link
          type="primary"
          @click="batchWorkers = workerToFill"
        >
          选中本期未填的 {{ workerToFill.length }} 人
        </el-button>
      </div>

      <el-row :gutter="16" v-loading="hoursLoading">
        <el-col :span="10">
          <div class="block-title">
            本期工时合计
            <span class="muted">
              已填 {{ hoursSummary.filled || 0 }} 人 / 待填 {{ hoursSummary.missing || 0 }} 人
            </span>
          </div>
          <el-table :data="hoursSummary.rows || []" size="small" height="240">
            <el-table-column prop="worker" label="员工" min-width="90" />
            <el-table-column label="合计工时" width="110">
              <template #default="{ row }">
                <span class="num">{{ fmtQty(row.minutes) }}</span>
                <span class="muted"> 分钟</span>
              </template>
            </el-table-column>
            <el-table-column label="天数" width="70">
              <template #default="{ row }">{{ row.days || 0 }}</template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button link type="primary" @click="pickWorker(row.worker)">填一条</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-col>
        <el-col :span="14">
          <div class="block-title">
            本期工时明细
            <span class="muted">合计 {{ fmtQty(hoursSummary.totalMinutes) }} 分钟</span>
          </div>
          <el-table :data="hoursRows" size="small" height="240">
            <el-table-column prop="workDate" label="日期" width="105" />
            <el-table-column prop="worker" label="员工" width="90" />
            <el-table-column label="时长" width="95">
              <template #default="{ row }"><span class="num">{{ fmtQty(row.minutes) }}</span></template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
            <el-table-column label="操作" width="70">
              <template #default="{ row }">
                <el-button link type="danger" @click="removeHour(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-col>
      </el-row>
      <template #footer>
        <el-button @click="hoursVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="录入实际工时" width="440px" append-to-body>
      <el-form label-width="96px">
        <el-form-item label="员工">
          <span>{{ form.worker }}</span>
        </el-form-item>
        <el-form-item label="标准工时">
          <span class="num">{{ fmtQty(form.standardMinutes) }} 分钟</span>
        </el-form-item>
        <el-form-item label="实际工时">
          <el-input v-model="form.actualMinutes" placeholder="按分钟填，例如 4800" clearable>
            <template #append>分钟</template>
          </el-input>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填，例如：含加班 6 小时" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMinutes">保存并重算</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  padding: 20px 24px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.page-title {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 600;
}
.page-sub {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.kpi-card {
  padding: 12px 16px;
  border-radius: 10px;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}
.kpi-card.kpi-alert {
  border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
}
.kpi-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.kpi-value {
  margin: 6px 0 2px;
  font-size: 24px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.kpi-value small {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.kpi-foot {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.num {
  font-variant-numeric: tabular-nums;
}
.num.strong {
  font-weight: 600;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.dialog-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.hour-form {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}
.block-title {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
}
.source-tag {
  margin-left: 6px;
  transform: scale(0.9);
}
</style>
