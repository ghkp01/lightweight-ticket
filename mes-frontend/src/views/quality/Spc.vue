<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../../api'
import ReferenceSelect from '../../components/ReferenceSelect.vue'

const pad = (value: number) => String(value).padStart(2, '0')
const dateText = (date: Date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`

const today = new Date()
const start = new Date()
start.setDate(start.getDate() - 30)

const activeTab = ref('chart')
const range = ref<[string, string]>([dateText(start), dateText(today)])
const productId = ref<number | ''>('')
const items = ref<any[]>([])
const itemId = ref<number | ''>('')

const loading = ref(false)
const chart = ref<any>({ points: [], stats: {} })
const pChart = ref<any>({ points: [], stats: {} })
const capability = ref<any[]>([])

const loadItems = async () => {
  items.value = (await api.get('/spc/items', { productId: productId.value || undefined })) || []
  if (!items.value.some((item) => item.id === itemId.value)) {
    itemId.value = items.value[0]?.id || ''
  }
}

const loadChart = async () => {
  loading.value = true
  try {
    chart.value = (await api.get('/spc/chart', {
      itemId: itemId.value || undefined,
      productId: productId.value || undefined,
      from: range.value?.[0] || undefined,
      to: range.value?.[1] || undefined
    })) || { points: [], stats: {} }
  } finally {
    loading.value = false
  }
}

const loadPChart = async () => {
  loading.value = true
  try {
    pChart.value = (await api.get('/spc/p-chart', {
      productId: productId.value || undefined,
      from: range.value?.[0] || undefined,
      to: range.value?.[1] || undefined
    })) || { points: [], stats: {} }
  } finally {
    loading.value = false
  }
}

const loadCapability = async () => {
  loading.value = true
  try {
    capability.value = (await api.get('/spc/capability', { productId: productId.value || undefined })) || []
  } finally {
    loading.value = false
  }
}

const reload = async () => {
  if (activeTab.value === 'chart') {
    if (!itemId.value && !productId.value) {
      await loadItems()
    }
    await loadChart()
  } else if (activeTab.value === 'pchart') {
    await loadPChart()
  } else {
    await loadCapability()
  }
}

const onTabChange = (name: any) => {
  reload()
}

watch(productId, async () => {
  itemId.value = ''
  await loadItems()
  await reload()
})

// ---------------- 计量型控制图（纯 SVG，不引图表库）
const chartWidth = 900
const chartHeight = 240
const padding = { left: 48, right: 16, top: 16, bottom: 28 }

const scale = computed(() => {
  const points = chart.value?.points || []
  const stats = chart.value?.stats || {}
  const values: number[] = points.map((point: any) => Number(point.value))
  if (stats.ucl !== null && stats.ucl !== undefined) values.push(Number(stats.ucl))
  if (stats.lcl !== null && stats.lcl !== undefined) values.push(Number(stats.lcl))
  if (!values.length) return null
  const rawMin = Math.min(...values)
  const rawMax = Math.max(...values)
  const span = rawMax - rawMin || Math.abs(rawMax) * 0.1 || 1
  const min = rawMin - span * 0.1
  const max = rawMax + span * 0.1
  const innerWidth = chartWidth - padding.left - padding.right
  const innerHeight = chartHeight - padding.top - padding.bottom
  const x = (index: number, total: number) =>
    total <= 1 ? padding.left + innerWidth / 2 : padding.left + (innerWidth * index) / (total - 1)
  const y = (value: number) => padding.top + innerHeight - ((value - min) / (max - min)) * innerHeight
  return { min, max, x, y, innerWidth, innerHeight }
})

const linePoints = computed(() => {
  const points = chart.value?.points || []
  const s = scale.value
  if (!s || !points.length) return ''
  return points
    .map((point: any, index: number) => `${s.x(index, points.length)},${s.y(Number(point.value))}`)
    .join(' ')
})

const limitLine = (value: any) => {
  const s = scale.value
  if (!s || value === null || value === undefined) return null
  const y = s.y(Number(value))
  return { y, label: Number(value).toFixed(3) }
}

// ---------------- 不良率 P 图
const pScale = computed(() => {
  const points = pChart.value?.points || []
  if (!points.length) return null
  const values: number[] = []
  points.forEach((point: any) => {
    values.push(Number(point.rate), Number(point.ucl), Number(point.lcl), Number(point.center))
  })
  const rawMax = Math.max(...values, 1)
  const max = Math.max(1, rawMax * 1.2)
  const innerWidth = chartWidth - padding.left - padding.right
  const innerHeight = chartHeight - padding.top - padding.bottom
  const x = (index: number, total: number) =>
    total <= 1 ? padding.left + innerWidth / 2 : padding.left + (innerWidth * index) / (total - 1)
  const y = (value: number) => padding.top + innerHeight - (value / max) * innerHeight
  return { max, x, y, innerWidth, innerHeight }
})

const pLinePoints = computed(() => {
  const points = pChart.value?.points || []
  const s = pScale.value
  if (!s || !points.length) return ''
  return points
    .map((point: any, index: number) => `${s.x(index, points.length)},${s.y(Number(point.rate))}`)
    .join(' ')
})

const pUclPoints = computed(() => {
  const points = pChart.value?.points || []
  const s = pScale.value
  if (!s || !points.length) return ''
  return points
    .map((point: any, index: number) => `${s.x(index, points.length)},${s.y(Number(point.ucl))}`)
    .join(' ')
})

const pLclPoints = computed(() => {
  const points = pChart.value?.points || []
  const s = pScale.value
  if (!s || !points.length) return ''
  return points
    .map((point: any, index: number) => `${s.x(index, points.length)},${s.y(Number(point.lcl))}`)
    .join(' ')
})

const chartStats = computed(() => chart.value?.stats || {})
const pStats = computed(() => pChart.value?.stats || {})

const cpkClass = (value: any) => {
  const num = Number(value)
  if (!Number.isFinite(num)) return 'muted'
  if (num >= 1.33) return 'success-text'
  if (num >= 1) return 'warning-text'
  return 'danger-text'
}

const levelClass = (level: any) => {
  const value = String(level || '')
  if (value === '充足') return 'success'
  if (value === '偏低') return 'warning'
  if (value === '不足') return 'danger'
  return 'info'
}

onMounted(async () => {
  await loadItems()
  await loadChart()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">过程能力与 SPC</h2>
        <p class="page-sub">
          计量型检验项给 X̄ 控制图与 Cpk，计数型给不良率 P 控制图；数据来自检验单逐项实测值与不良数
        </p>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <ReferenceSelect v-model="productId" url="/options/products"
                         placeholder="全部产品" style="width: 200px" />
        <el-select
          v-if="activeTab === 'chart'"
          v-model="itemId"
          placeholder="选择检验项"
          clearable
          filterable
          style="width: 220px"
        >
          <el-option
            v-for="item in items"
            :key="item.id"
            :label="`${item.itemName}（${item.valueType === 'MEASURE' ? '计量' : '计数'}）`"
            :value="item.id"
          />
        </el-select>
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
        <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
        <div class="spacer"></div>
        <span class="toolbar-tip">Cpk ≥ 1.33 视为过程能力充足</span>
      </div>

      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane label="控制图（计量）" name="chart">
          <div v-loading="loading" class="tab-body">
            <div class="kpi-grid kpi-4">
              <div class="kpi-card">
                <div class="kpi-label">样本数</div>
                <div class="kpi-value">{{ chartStats.count ?? 0 }}</div>
                <div class="kpi-foot">{{ chart.itemName || '未指定检验项' }}</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">均值 / σ</div>
                <div class="kpi-value small">
                  {{ chartStats.mean ?? '-' }} <small>/ {{ chartStats.sigma ?? '-' }}</small>
                </div>
                <div class="kpi-foot">
                  控制限 {{ chartStats.lcl ?? '-' }} ~ {{ chartStats.ucl ?? '-' }}
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">Cpk</div>
                <div class="kpi-value" :class="cpkClass(chartStats.cpk)">
                  {{ chartStats.cpk ?? '-' }}
                </div>
                <div class="kpi-foot">
                  规格 {{ chartStats.lowerLimit ?? '-' }} ~ {{ chartStats.upperLimit ?? '-' }}
                </div>
              </div>
              <div class="kpi-card" :class="{ 'kpi-alert': Number(chartStats.breaches || 0) > 0 }">
                <div class="kpi-label">越限点</div>
                <div class="kpi-value">{{ chartStats.breaches ?? 0 }}</div>
                <div class="kpi-foot">超出 ±3σ 的检验单数</div>
              </div>
            </div>

            <div class="chart-wrap">
              <svg v-if="scale && (chart.points || []).length" :viewBox="`0 0 ${chartWidth} ${chartHeight}`" class="chart">
                <line
                  v-if="limitLine(chartStats.ucl)"
                  :x1="padding.left"
                  :x2="chartWidth - padding.right"
                  :y1="limitLine(chartStats.ucl).y"
                  :y2="limitLine(chartStats.ucl).y"
                  stroke="#f79009"
                  stroke-dasharray="6 4"
                  stroke-width="1"
                />
                <line
                  v-if="limitLine(chartStats.lcl)"
                  :x1="padding.left"
                  :x2="chartWidth - padding.right"
                  :y1="limitLine(chartStats.lcl).y"
                  :y2="limitLine(chartStats.lcl).y"
                  stroke="#f79009"
                  stroke-dasharray="6 4"
                  stroke-width="1"
                />
                <line
                  v-if="limitLine(chartStats.mean)"
                  :x1="padding.left"
                  :x2="chartWidth - padding.right"
                  :y1="limitLine(chartStats.mean).y"
                  :y2="limitLine(chartStats.mean).y"
                  stroke="#94a3b8"
                  stroke-dasharray="4 4"
                  stroke-width="1"
                />
                <polyline :points="linePoints" fill="none" stroke="#2456d8" stroke-width="1.6" />
                <circle
                  v-for="(point, index) in chart.points"
                  :key="index"
                  :cx="scale.x(index, chart.points.length)"
                  :cy="scale.y(Number(point.value))"
                  :r="point.breach ? 4 : 2.6"
                  :fill="point.breach ? '#f04438' : '#2456d8'"
                >
                  <title>{{ point.label }} · {{ point.value }}</title>
                </circle>
              </svg>
              <div v-else class="empty-chart">所选区间没有可用的实测值（需要检验项填了数字型实测值）</div>
            </div>

            <div class="table-wrap">
              <el-table :data="chart.points || []" size="small" max-height="260" empty-text="没有实测值">
                <el-table-column prop="label" label="时间" width="130">
                  <template #default="{ row }"><span class="mono">{{ row.label }}</span></template>
                </el-table-column>
                <el-table-column prop="inspectionNo" label="检验单" min-width="160">
                  <template #default="{ row }"><span class="mono">{{ row.inspectionNo }}</span></template>
                </el-table-column>
                <el-table-column label="实测值" width="120" align="right">
                  <template #default="{ row }"><span class="num">{{ row.value }}</span></template>
                </el-table-column>
                <el-table-column label="判定" width="110">
                  <template #default="{ row }">
                    <span v-if="row.breach" class="status-tag danger"><i></i>越限</span>
                    <span v-else class="status-tag success"><i></i>受控</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="不良率 P 图" name="pchart">
          <div v-loading="loading" class="tab-body">
            <div class="kpi-grid kpi-4">
              <div class="kpi-card">
                <div class="kpi-label">检验单数</div>
                <div class="kpi-value">{{ pStats.count ?? 0 }}</div>
                <div class="kpi-foot">区间内所有检验类型</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">中心线 p̄</div>
                <div class="kpi-value">{{ pStats.centerRate ?? 0 }}<small>%</small></div>
                <div class="kpi-foot">
                  检验 {{ pStats.inspectQty ?? 0 }}，不良 {{ pStats.defectQty ?? 0 }}
                </div>
              </div>
              <div class="kpi-card" :class="{ 'kpi-alert': Number(pStats.breaches || 0) > 0 }">
                <div class="kpi-label">越限点</div>
                <div class="kpi-value">{{ pStats.breaches ?? 0 }}</div>
                <div class="kpi-foot">超出控制限，需查原因</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">控制限口径</div>
                <div class="kpi-value small">±3σ</div>
                <div class="kpi-foot">随每单样本量变化</div>
              </div>
            </div>

            <div class="chart-wrap">
              <svg v-if="pScale && (pChart.points || []).length" :viewBox="`0 0 ${chartWidth} ${chartHeight}`" class="chart">
                <polyline :points="pUclPoints" fill="none" stroke="#f79009" stroke-dasharray="6 4" stroke-width="1" />
                <polyline :points="pLclPoints" fill="none" stroke="#f79009" stroke-dasharray="6 4" stroke-width="1" />
                <polyline :points="pLinePoints" fill="none" stroke="#2456d8" stroke-width="1.6" />
                <circle
                  v-for="(point, index) in pChart.points"
                  :key="index"
                  :cx="pScale.x(index, pChart.points.length)"
                  :cy="pScale.y(Number(point.rate))"
                  :r="point.breach ? 4 : 2.6"
                  :fill="point.breach ? '#f04438' : '#2456d8'"
                >
                  <title>{{ point.label }} · {{ point.rate }}%</title>
                </circle>
              </svg>
              <div v-else class="empty-chart">所选区间没有检验单</div>
            </div>

            <div class="table-wrap">
              <el-table :data="pChart.points || []" size="small" max-height="260" empty-text="没有检验单">
                <el-table-column prop="label" label="时间" width="130">
                  <template #default="{ row }"><span class="mono">{{ row.label }}</span></template>
                </el-table-column>
                <el-table-column prop="inspectionNo" label="检验单" min-width="160">
                  <template #default="{ row }"><span class="mono">{{ row.inspectionNo }}</span></template>
                </el-table-column>
                <el-table-column label="样本 / 不良" width="140" align="right">
                  <template #default="{ row }">
                    <span class="num">{{ row.sampleQty }} / {{ row.defectQty }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="不良率" width="110" align="right">
                  <template #default="{ row }">
                    <span class="num" :class="row.breach ? 'danger-text' : ''">{{ row.rate }}%</span>
                  </template>
                </el-table-column>
                <el-table-column label="控制限" width="150" align="right">
                  <template #default="{ row }">
                    <span class="num muted">{{ row.lcl }}% ~ {{ row.ucl }}%</span>
                  </template>
                </el-table-column>
                <el-table-column label="判定" width="100">
                  <template #default="{ row }">
                    <span v-if="row.breach" class="status-tag danger"><i></i>越限</span>
                    <span v-else class="status-tag success"><i></i>受控</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="过程能力" name="capability">
          <div v-loading="loading" class="tab-body">
            <div class="muted note">
              只统计配了上 / 下限的检验项，取最近 200 条实测值算 σ；实测值必须是数字，文字类结论不参与。
            </div>
            <div class="table-wrap">
              <el-table :data="capability" size="small" empty-text="没有配置上下限的检验项">
                <el-table-column label="产品" min-width="170">
                  <template #default="{ row }">
                    <div>{{ row.productName }}</div>
                    <div class="sub mono">{{ row.productCode }}</div>
                  </template>
                </el-table-column>
                <el-table-column prop="itemName" label="检验项" min-width="150" />
                <el-table-column label="规格" width="150">
                  <template #default="{ row }">
                    <span class="num muted">{{ row.lowerLimit ?? '-' }} ~ {{ row.upperLimit ?? '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="样本" width="80" align="right">
                  <template #default="{ row }"><span class="num">{{ row.sampleCount ?? 0 }}</span></template>
                </el-table-column>
                <el-table-column label="均值" width="100" align="right">
                  <template #default="{ row }"><span class="num">{{ row.mean ?? '-' }}</span></template>
                </el-table-column>
                <el-table-column label="σ" width="90" align="right">
                  <template #default="{ row }"><span class="num">{{ row.sigma ?? '-' }}</span></template>
                </el-table-column>
                <el-table-column label="Cp" width="80" align="right">
                  <template #default="{ row }"><span class="num">{{ row.cp ?? '-' }}</span></template>
                </el-table-column>
                <el-table-column label="Cpk" width="80" align="right">
                  <template #default="{ row }">
                    <span class="num" :class="cpkClass(row.cpk)">{{ row.cpk ?? '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="超差" width="80" align="right">
                  <template #default="{ row }">
                    <span class="num" :class="Number(row.outOfSpec || 0) > 0 ? 'danger-text' : 'muted'">
                      {{ row.outOfSpec ?? 0 }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="能力" width="100">
                  <template #default="{ row }">
                    <span class="status-tag" :class="levelClass(row.level)"><i></i>{{ row.level }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.tab-body {
  min-height: 200px;
}

.kpi-grid.kpi-4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 12px 0;
}

@media (max-width: 1100px) {
  .kpi-grid.kpi-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.kpi-value.small {
  font-size: 18px;
}

.chart-wrap {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 8px;
  margin-bottom: 12px;
  background: #fcfcfd;
}

.chart {
  width: 100%;
  height: 240px;
}

.empty-chart {
  height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--muted);
}

.sub {
  font-size: 11px;
  color: var(--muted);
}

.success-text {
  color: var(--success);
}

.warning-text {
  color: var(--accent);
}

.note {
  font-size: 12px;
  line-height: 1.8;
  padding: 4px 2px 10px;
}
</style>
