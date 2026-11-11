<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, fmtMoney, fmtQty } from '../../api'

const pad = (value: number) => String(value).padStart(2, '0')
const dateText = (date: Date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`

const today = new Date()
const start = new Date()
start.setDate(start.getDate() - 90)

const range = ref<[string, string]>([dateText(start), dateText(today)])
const loading = ref(false)
const rows = ref<any[]>([])
const keyword = ref('')
const onlyRisk = ref(false)

const detail = ref<any>(null)
const drawer = ref(false)
const detailLoading = ref(false)

const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    if (onlyRisk.value) {
      const score = Number(row.score ?? 100)
      if (row.hasData && score >= 80) return false
      if (Number(row.onTimeRate ?? 100) >= 90 && Number(row.passRate ?? 100) >= 95) return false
    }
    if (!text) return true
    return [row.code, row.name, row.contact].some((value) =>
      String(value || '').toLowerCase().includes(text)
    )
  })
})

const summary = computed(() => {
  const active = filtered.value.filter((row) => row.hasData)
  const totalAmount = active.reduce((sum, row) => sum + Number(row.amount || 0), 0)
  const withReceipt = active.filter((row) => Number(row.judgedBatches || 0) > 0)
  const onTime = withReceipt.length
    ? withReceipt.reduce((sum, row) => sum + Number(row.onTimeRate || 0), 0) / withReceipt.length
    : 0
  const withInspection = active.filter((row) => Number(row.inspections || 0) > 0)
  const pass = withInspection.length
    ? withInspection.reduce((sum, row) => sum + Number(row.passRate || 0), 0) / withInspection.length
    : 0
  const risk = active.filter((row) => Number(row.score ?? 100) < 80).length
  return { count: active.length, totalAmount, onTime, pass, risk }
})

const gradeClass = (grade: any) => {
  const value = String(grade || '')
  if (value === 'A') return 'success'
  if (value === 'B') return 'primary'
  if (value === 'C') return 'warning'
  if (value === 'D') return 'danger'
  return 'info'
}

const rateClass = (value: any, good: number, bad: number) => {
  const num = Number(value || 0)
  if (num >= good) return 'success-text'
  if (num <= bad) return 'danger-text'
  return ''
}

const load = async () => {
  loading.value = true
  try {
    rows.value = (await api.get('/supplier-performance', {
      from: range.value?.[0] || undefined,
      to: range.value?.[1] || undefined
    })) || []
  } finally {
    loading.value = false
  }
}

const openDetail = async (row: any) => {
  detailLoading.value = true
  drawer.value = true
  try {
    detail.value = await api.get(`/supplier-performance/${row.id}`, {
      from: range.value?.[0] || undefined,
      to: range.value?.[1] || undefined
    })
  } finally {
    detailLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">供应商绩效</h2>
        <p class="page-sub">
          准时率按入库完成日与采购预计到货日比，合格率按来料检验数量算，退货率按退货金额占比
        </p>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
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
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
        <el-input v-model="keyword" placeholder="供应商编码 / 名称" clearable style="width: 200px" />
        <el-checkbox v-model="onlyRisk">只看待改进</el-checkbox>
        <div class="spacer"></div>
        <span class="toolbar-tip">得分 = 准时率×40% + 合格率×40% +（1−退货率）×20%</span>
      </div>

      <div v-loading="loading" class="body">
        <div class="kpi-grid kpi-4">
          <div class="kpi-card">
            <div class="kpi-label">有往来的供应商</div>
            <div class="kpi-value">{{ summary.count }}<small>家</small></div>
            <div class="kpi-foot">区间内有采购或收货</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">采购金额</div>
            <div class="kpi-value">{{ fmtMoney(summary.totalAmount) }}<small>元</small></div>
            <div class="kpi-foot">不含已取消采购单</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">平均准时率</div>
            <div class="kpi-value">{{ summary.onTime.toFixed(1) }}<small>%</small></div>
            <div class="kpi-foot">
              平均来料合格率 {{ summary.pass.toFixed(1) }}%
            </div>
          </div>
          <div class="kpi-card" :class="{ 'kpi-alert': summary.risk > 0 }">
            <div class="kpi-label">待改进供应商</div>
            <div class="kpi-value">{{ summary.risk }}<small>家</small></div>
            <div class="kpi-foot">综合得分低于 80</div>
          </div>
        </div>

        <div class="table-wrap">
          <el-table :data="filtered" size="small" empty-text="所选区间没有供应商往来数据">
            <el-table-column label="供应商" min-width="200">
              <template #default="{ row }">
                <div class="cell-main">{{ row.name }}</div>
                <div class="cell-sub mono">{{ row.code }}</div>
              </template>
            </el-table-column>
            <el-table-column label="等级" width="80">
              <template #default="{ row }">
                <span class="status-tag" :class="gradeClass(row.grade)"><i></i>{{ row.grade }}</span>
              </template>
            </el-table-column>
            <el-table-column label="综合得分" width="110" align="right">
              <template #default="{ row }">
                <span class="num" :class="Number(row.score ?? 100) < 80 ? 'danger-text' : ''">
                  {{ row.score === null || row.score === undefined ? '-' : row.score }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="采购金额" width="130" align="right">
              <template #default="{ row }"><span class="money">{{ fmtMoney(row.amount) }}</span></template>
            </el-table-column>
            <el-table-column label="采购单" width="90" align="right">
              <template #default="{ row }"><span class="num">{{ row.orders ?? 0 }}</span></template>
            </el-table-column>
            <el-table-column label="准时率" width="110" align="right">
              <template #default="{ row }">
                <span class="num" :class="rateClass(row.onTimeRate, 95, 80)">
                  {{ Number(row.judgedBatches || 0) > 0 ? `${row.onTimeRate}%` : '-' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="来料合格率" width="120" align="right">
              <template #default="{ row }">
                <span class="num" :class="rateClass(row.passRate, 98, 90)">
                  {{ Number(row.inspections || 0) > 0 ? `${row.passRate}%` : '-' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="退货率" width="100" align="right">
              <template #default="{ row }">
                <span class="num" :class="Number(row.returnRate || 0) > 0 ? 'danger-text' : 'muted'">
                  {{ row.returnRate ?? 0 }}%
                </span>
              </template>
            </el-table-column>
            <el-table-column label="收货批次" width="100" align="right">
              <template #default="{ row }">
                <span class="num">{{ row.batches ?? 0 }}</span>
                <span class="muted"> / {{ row.judgedBatches ?? 0 }} 有交期</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDetail(row)">明细</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>

    <el-drawer append-to-body v-model="drawer" :title="`供应商绩效 · ${detail?.summary?.name || ''}`" size="880px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="kpi-grid kpi-4 detail-kpi">
            <div class="kpi-card">
              <div class="kpi-label">综合得分</div>
              <div class="kpi-value">{{ detail.summary.score ?? '-' }}</div>
              <div class="kpi-foot">等级 {{ detail.summary.grade }}</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-label">准时率</div>
              <div class="kpi-value">{{ detail.summary.onTimeRate ?? 0 }}<small>%</small></div>
              <div class="kpi-foot">
                {{ detail.summary.onTimeBatches ?? 0 }} / {{ detail.summary.judgedBatches ?? 0 }} 批准时
              </div>
            </div>
            <div class="kpi-card">
              <div class="kpi-label">来料合格率</div>
              <div class="kpi-value">{{ detail.summary.passRate ?? 0 }}<small>%</small></div>
              <div class="kpi-foot">
                检验 {{ fmtQty(detail.summary.inspectQty) }}，不良 {{ fmtQty(detail.summary.defectQty) }}
              </div>
            </div>
            <div class="kpi-card">
              <div class="kpi-label">采购金额 / 退货</div>
              <div class="kpi-value">{{ fmtMoney(detail.summary.amount) }}</div>
              <div class="kpi-foot">
                退货 {{ fmtMoney(detail.summary.returnAmount) }} 元（{{ detail.summary.returnRate ?? 0 }}%）
              </div>
            </div>
          </div>

          <div class="section-head">
            <span>月度采购均价</span>
            <span class="muted">按采购单下单月份汇总，同一物料多单取加权均价</span>
          </div>
          <el-table :data="detail.priceTrend || []" size="small" max-height="260" empty-text="区间内没有采购记录">
            <el-table-column prop="month" label="月份" width="100">
              <template #default="{ row }"><span class="mono">{{ row.month }}</span></template>
            </el-table-column>
            <el-table-column label="物料" min-width="180">
              <template #default="{ row }">
                <div class="cell-main">{{ row.productName }}</div>
                <div class="cell-sub mono">{{ row.productCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="100" align="right">
              <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
            </el-table-column>
            <el-table-column label="加权均价" width="120" align="right">
              <template #default="{ row }"><span class="num">{{ fmtMoney(row.avgPrice) }}</span></template>
            </el-table-column>
            <el-table-column label="金额" width="120" align="right">
              <template #default="{ row }"><span class="money">{{ fmtMoney(row.amount) }}</span></template>
            </el-table-column>
          </el-table>

          <div class="section-head">
            <span>物料分布</span>
            <span class="muted">同一物料的最低价 / 最高价，便于发现价格波动</span>
          </div>
          <el-table :data="detail.materials || []" size="small" max-height="240" empty-text="暂无采购物料">
            <el-table-column label="物料" min-width="180">
              <template #default="{ row }">
                <div class="cell-main">{{ row.productName }}</div>
                <div class="cell-sub mono">{{ row.productCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="100" align="right">
              <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
            </el-table-column>
            <el-table-column label="加权均价" width="110" align="right">
              <template #default="{ row }"><span class="num">{{ fmtMoney(row.avgPrice) }}</span></template>
            </el-table-column>
            <el-table-column label="最低价" width="100" align="right">
              <template #default="{ row }"><span class="num muted">{{ fmtMoney(row.minPrice) }}</span></template>
            </el-table-column>
            <el-table-column label="最高价" width="100" align="right">
              <template #default="{ row }"><span class="num">{{ fmtMoney(row.maxPrice) }}</span></template>
            </el-table-column>
            <el-table-column label="金额" width="120" align="right">
              <template #default="{ row }"><span class="money">{{ fmtMoney(row.amount) }}</span></template>
            </el-table-column>
          </el-table>

          <div class="section-head">
            <span>最近收货</span>
            <span class="muted">完成日晚于预计到货日即逾期，负数为提前</span>
          </div>
          <el-table :data="detail.receipts || []" size="small" max-height="220" empty-text="区间内没有收货">
            <el-table-column prop="inboundNo" label="入库单" width="150">
              <template #default="{ row }"><span class="mono">{{ row.inboundNo }}</span></template>
            </el-table-column>
            <el-table-column prop="orderNo" label="采购单" width="150">
              <template #default="{ row }"><span class="mono">{{ row.orderNo || '-' }}</span></template>
            </el-table-column>
            <el-table-column prop="expectedDate" label="预计到货" width="120">
              <template #default="{ row }"><span class="mono">{{ row.expectedDate || '-' }}</span></template>
            </el-table-column>
            <el-table-column prop="completedDate" label="实际入库" width="120">
              <template #default="{ row }"><span class="mono">{{ row.completedDate }}</span></template>
            </el-table-column>
            <el-table-column label="准时" width="110">
              <template #default="{ row }">
                <span v-if="row.onTime === true" class="status-tag success"><i></i>准时</span>
                <span v-else-if="row.onTime === false" class="status-tag danger">
                  <i></i>逾期 {{ row.delayDays }} 天
                </span>
                <span v-else class="muted">无交期</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="section-head">
            <span>来料检验</span>
            <span class="muted">只统计来料检（INCOMING）</span>
          </div>
          <el-table :data="detail.inspections || []" size="small" max-height="220" empty-text="区间内没有来料检验">
            <el-table-column prop="inspectionNo" label="检验单" width="150">
              <template #default="{ row }"><span class="mono">{{ row.inspectionNo }}</span></template>
            </el-table-column>
            <el-table-column label="物料" min-width="160">
              <template #default="{ row }">
                <div class="cell-main">{{ row.productName }}</div>
                <div class="cell-sub mono">{{ row.productCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="检验 / 不良" width="120" align="right">
              <template #default="{ row }">
                <span class="num">{{ fmtQty(row.inspectQty) }} / {{ fmtQty(row.defectQty) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="结论" width="90">
              <template #default="{ row }">
                <span v-if="row.result === 'PASS'" class="status-tag success"><i></i>合格</span>
                <span v-else-if="row.result === 'FAIL'" class="status-tag danger"><i></i>不合格</span>
                <span v-else class="muted">待检</span>
              </template>
            </el-table-column>
            <el-table-column prop="inspectedAt" label="检验时间" width="150">
              <template #default="{ row }"><span class="mono muted">{{ row.inspectedAt || '-' }}</span></template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.body {
  min-height: 200px;
}

.kpi-grid.kpi-4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 14px;
}

.detail-kpi {
  margin-bottom: 16px;
}

@media (max-width: 1100px) {
  .kpi-grid.kpi-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
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

.cell-main {
  font-size: 13px;
}

.cell-sub {
  font-size: 11px;
  color: var(--muted);
}

.success-text {
  color: var(--success);
}
</style>
