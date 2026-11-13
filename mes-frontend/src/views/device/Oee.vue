<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, fmtQty } from '../../api'
import ReferenceSelect from '../../components/ReferenceSelect.vue'

const pad = (value: number) => String(value).padStart(2, '0')
const dateText = (date: Date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`

const today = new Date()
const start = new Date()
start.setDate(start.getDate() - 6)

const range = ref<[string, string]>([dateText(start), dateText(today)])
const workCenterId = ref<number | null>(null)
const loading = ref(false)
const data = ref<any>({ summary: {}, rows: [], faults: [] })

const summary = computed(() => data.value.summary || {})
const rows = computed(() => data.value.rows || [])
const faults = computed(() => data.value.faults || [])

const load = async () => {
  loading.value = true
  try {
    data.value = (await api.get('/oee', {
      workCenterId: workCenterId.value || undefined,
      from: range.value?.[0] || undefined,
      to: range.value?.[1] || undefined
    })) || { summary: {}, rows: [], faults: [] }
  } finally {
    loading.value = false
  }
}

const toneOf = (value: any, good: number, bad: number) => {
  const num = Number(value || 0)
  if (num >= good) return 'success-text'
  if (num < bad) return 'danger-text'
  return ''
}

const barWidth = (value: any) => `${Math.min(100, Math.max(0, Number(value || 0)))}%`

/* ---------- 设备采集补录：没有网关时人工登记产量与停机分钟 ---------- */

const telemetryOpen = ref(false)
const telemetrySaving = ref(false)
const telemetryForm = ref({
  deviceCode: '',
  workCenterId: null as number | null,
  outputQty: 0,
  downtimeMinutes: 0,
  recordedAt: ''
})

const nowText = () => {
  const now = new Date()
  const p = (value: number) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} ${p(now.getHours())}:${p(now.getMinutes())}:00`
}

const openTelemetry = () => {
  telemetryForm.value = {
    deviceCode: '',
    workCenterId: typeof workCenterId.value === 'number' ? workCenterId.value : null,
    outputQty: 0,
    downtimeMinutes: 0,
    recordedAt: nowText()
  }
  telemetryOpen.value = true
}

const saveTelemetry = async () => {
  if (!telemetryForm.value.deviceCode.trim()) {
    ElMessage.warning('请填写设备编号')
    return
  }
  if (!telemetryForm.value.workCenterId) {
    ElMessage.warning('请选择工作中心')
    return
  }
  telemetrySaving.value = true
  try {
    await api.post('/device/telemetry', {
      deviceCode: telemetryForm.value.deviceCode.trim(),
      workCenterId: telemetryForm.value.workCenterId,
      outputQty: telemetryForm.value.outputQty,
      downtimeMinutes: telemetryForm.value.downtimeMinutes,
      recordedAt: telemetryForm.value.recordedAt.replace(' ', 'T'),
      source: 'MANUAL'
    })
    ElMessage.success('采集数据已登记，OEE 会把它计入停机分钟')
    telemetryOpen.value = false
    await load()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '登记失败')
  } finally {
    telemetrySaving.value = false
  }
}

onMounted(async () => {
  await load()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">设备综合效率 OEE</h2>
        <p class="page-sub">
          OEE = 可用率 × 性能 × 质量；性能用「标准工时 ÷ 计划开动时间」近似，用来定位停得多、做得慢、返工多
        </p>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <ReferenceSelect
          v-model="workCenterId"
          url="/work-centers/options"
          clearable
          placeholder="全部工作中心"
          style="width: 200px"
        />
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :clearable="false"
          style="width: 250px"
        />
        <el-button type="primary" :loading="loading" @click="load">计算</el-button>
        <el-button @click="openTelemetry">采集补录</el-button>
        <div class="spacer"></div>
        <span class="toolbar-tip">统计 {{ summary.days ?? 0 }} 天 · {{ summary.centers ?? 0 }} 个工作中心</span>
      </div>

      <div v-loading="loading">
        <div class="kpi-grid kpi-4">
          <div class="kpi-card" :class="{ 'kpi-alert': Number(summary.oee || 0) < 60 }">
            <div class="kpi-label">综合 OEE</div>
            <div class="kpi-value">{{ summary.oee ?? 0 }}<small>%</small></div>
            <div class="kpi-foot">可用 × 性能 × 质量</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">可用率 A</div>
            <div class="kpi-value">{{ summary.availability ?? 0 }}<small>%</small></div>
            <div class="kpi-foot">停机 {{ fmtQty(summary.downtimeMinutes) }} / 计划 {{ fmtQty(summary.plannedMinutes) }} 分</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">性能 P</div>
            <div class="kpi-value">{{ summary.performance ?? 0 }}<small>%</small></div>
            <div class="kpi-foot">标准工时 ÷ 计划开动时间</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">质量 Q</div>
            <div class="kpi-value">{{ summary.quality ?? 0 }}<small>%</small></div>
            <div class="kpi-foot">
              报工 {{ fmtQty(summary.reportQty) }}，合格 {{ fmtQty(summary.qualifiedQty) }}
            </div>
          </div>
        </div>

        <div class="table-wrap">
          <el-table :data="rows" size="small" empty-text="区间内没有工作中心数据">
            <el-table-column label="工作中心" min-width="180">
              <template #default="{ row }">
                <div>{{ row.name }}</div>
                <div class="sub mono">{{ row.code }} · {{ row.type }}</div>
              </template>
            </el-table-column>
            <el-table-column label="OEE" width="150">
              <template #default="{ row }">
                <div class="bar-cell">
                  <div class="bar"><span :style="{ width: barWidth(row.oee) }"></span></div>
                  <b class="num" :class="toneOf(row.oee, 65, 50)">{{ row.oee }}%</b>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="可用率" width="110" align="right">
              <template #default="{ row }">
                <span class="num" :class="toneOf(row.availability, 90, 75)">{{ row.availability }}%</span>
              </template>
            </el-table-column>
            <el-table-column label="性能" width="100" align="right">
              <template #default="{ row }"><span class="num" :class="toneOf(row.performance, 85, 65)">{{ row.performance }}%</span></template>
            </el-table-column>
            <el-table-column label="质量" width="100" align="right">
              <template #default="{ row }"><span class="num" :class="toneOf(row.quality, 98, 92)">{{ row.quality }}%</span></template>
            </el-table-column>
            <el-table-column label="计划 / 停机" width="150" align="right">
              <template #default="{ row }">
                <span class="num">{{ fmtQty(row.plannedMinutes) }} / {{ fmtQty(row.downtimeMinutes) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="标准工时 / 开动" width="160" align="right">
              <template #default="{ row }">
                <span class="num muted">{{ fmtQty(row.standardMinutes) }} / {{ fmtQty(row.runMinutes) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="报工 / 合格" width="140" align="right">
              <template #default="{ row }">
                <span class="num">{{ fmtQty(row.reportQty) }} / {{ fmtQty(row.qualifiedQty) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="section-head">
          <span>停机明细</span>
          <span class="muted">区间内报修的设备故障，未关闭的按当前时间计</span>
        </div>
        <div class="table-wrap">
          <el-table :data="faults" size="small" max-height="260" empty-text="区间内没有设备故障">
            <el-table-column label="故障单" width="150">
              <template #default="{ row }"><span class="mono">{{ row.faultNo }}</span></template>
            </el-table-column>
            <el-table-column label="设备" min-width="160">
              <template #default="{ row }">{{ row.deviceName }}（{{ row.deviceCode }}）</template>
            </el-table-column>
            <el-table-column prop="workCenterName" label="工作中心" width="150" />
            <el-table-column prop="symptom" label="现象" min-width="200" show-overflow-tooltip />
            <el-table-column label="停机分钟" width="110" align="right">
              <template #default="{ row }"><span class="num">{{ row.minutes }}</span></template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <span class="status-tag" :class="row.status === 'OPEN' ? 'danger' : 'success'">
                  <i></i>{{ row.status === 'OPEN' ? '待处理' : '已关闭' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="occurredAt" label="报修时间" width="150">
              <template #default="{ row }"><span class="mono muted">{{ row.occurredAt }}</span></template>
            </el-table-column>
          </el-table>
        </div>

        <div class="muted note">
          口径：计划时间 = 工作中心日产能 × 天数；可用率 =（计划 − 停机）÷ 计划；
          性能 = 标准工时达成量 ÷ 计划开动时间；质量 = 合格 ÷ 报工。系统没有采集设备实时开动信号，
          性能项是近似值，用于横向比较与趋势观察。
        </div>
      </div>
    </div>

    <el-dialog v-model="telemetryOpen" title="设备采集补录" width="480px" append-to-body>
      <el-form label-width="100px">
        <el-form-item label="设备编号" required>
          <el-input v-model="telemetryForm.deviceCode" placeholder="如 CNC-01 / PRESS-02" />
        </el-form-item>
        <el-form-item label="工作中心" required>
          <ReferenceSelect v-model="telemetryForm.workCenterId" url="/work-centers/options" placeholder="选择工作中心" />
        </el-form-item>
        <el-form-item label="产量（件）">
          <el-input-number v-model="telemetryForm.outputQty" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="停机（分钟）">
          <el-input-number v-model="telemetryForm.downtimeMinutes" :min="0" :precision="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="采集时间">
          <el-date-picker
            v-model="telemetryForm.recordedAt"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <div class="muted" style="font-size: 12px; line-height: 1.6">
        没有采集网关时用这里人工补录；已接网关的场景由网关按同一接口上报（source=GATEWAY）。
        登记后停机分钟会并入 OEE 的可用率计算。
      </div>
      <template #footer>
        <el-button @click="telemetryOpen = false">取消</el-button>
        <el-button type="primary" :loading="telemetrySaving" @click="saveTelemetry">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kpi-grid.kpi-4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 12px 0;
}

@media (max-width: 1100px) {
  .kpi-grid.kpi-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.bar-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bar {
  flex: 1;
  height: 6px;
  border-radius: 4px;
  background: #eef2f6;
  overflow: hidden;
}

.bar span {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #2456d8, #4f7cf7);
}

.sub {
  font-size: 11px;
  color: var(--muted);
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 18px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.section-head .muted {
  font-size: 12px;
  font-weight: 400;
}

.success-text {
  color: var(--success);
}

.note {
  font-size: 12px;
  line-height: 1.8;
  padding: 10px 2px 0;
}
</style>
