<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../../api'
import StatusTag from '../../components/StatusTag.vue'

const pad = (value: number) => String(value).padStart(2, '0')
const dateText = (date: Date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`

const today = new Date()
const monthStart = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-01`

const activeTab = ref('output')

const range = ref<[string, string]>([monthStart, dateText(today)])
const prodLoading = ref(false)
const production = ref<any>({ summary: {}, daily: [], byOperation: [], byWorker: [] })

const month = ref(`${today.getFullYear()}-${pad(today.getMonth() + 1)}`)
const payrollLoading = ref(false)
const payroll = ref<any>({ summary: {}, rows: [] })

const deliveryLoading = ref(false)
const deliveryRows = ref<any[]>([])

const loadedTabs = ref<Record<string, boolean>>({})

const prodSummary = computed(() => production.value.summary || {})
const wageSummary = computed(() => payroll.value.summary || {})

const rateText = (value: any) => {
  if (value === null || value === undefined || value === '') return '0.00%'
  const num = Number(value)
  if (!Number.isFinite(num)) return '0.00%'
  const pct = num > 0 && num <= 1 ? num * 100 : num
  return `${pct.toFixed(2)}%`
}

const rowRate = (row: any) => {
  const qty = Number(row?.qty || 0)
  if (!qty) return '0.00%'
  return `${((Number(row?.defectQty || 0) / qty) * 100).toFixed(2)}%`
}

const isLate = (row: any) => {
  const flag = row?.late
  if (flag === true || flag === 'true' || Number(flag) === 1) return true
  return Number(row?.lateDays || 0) > 0
}

const deliveryRowClass = ({ row }: { row: any }) => (isLate(row) ? 'row-late' : '')

const loadProduction = async () => {
  if (!range.value || range.value.length !== 2 || !range.value[0] || !range.value[1]) {
    ElMessage.warning('请选择完整的统计日期区间')
    return
  }
  prodLoading.value = true
  try {
    const data = await api.get('/reports/production', {
      from: range.value[0],
      to: range.value[1]
    })
    production.value = {
      summary: data?.summary || {},
      daily: data?.daily || [],
      byOperation: data?.byOperation || [],
      byWorker: data?.byWorker || []
    }
    loadedTabs.value.output = true
  } finally {
    prodLoading.value = false
  }
}

const loadPayroll = async () => {
  if (!month.value) {
    ElMessage.warning('请选择工资月份')
    return
  }
  payrollLoading.value = true
  try {
    const data = await api.get('/reports/payroll', { month: month.value })
    payroll.value = {
      summary: data?.summary || {},
      rows: data?.rows || []
    }
    loadedTabs.value.payroll = true
  } finally {
    payrollLoading.value = false
  }
}

const loadDelivery = async () => {
  deliveryLoading.value = true
  try {
    const data = await api.get('/reports/delivery')
    deliveryRows.value = Array.isArray(data) ? data : data?.records || []
    loadedTabs.value.delivery = true
  } finally {
    deliveryLoading.value = false
  }
}

const onTabChange = (name: any) => {
  if (name === 'payroll' && !loadedTabs.value.payroll) loadPayroll()
  else if (name === 'delivery' && !loadedTabs.value.delivery) loadDelivery()
}

const csvCell = (value: any) => {
  const text = String(value ?? '')
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

const exportPayroll = () => {
  const rows = payroll.value.rows || []
  if (!rows.length) {
    ElMessage.warning('暂无可导出的计件工资数据')
    return
  }
  const lines = [
    ['人员', '涉及工单数', '合格数量', '计件工资（元）'],
    ...rows.map((row: any) => [
      row.worker || '未登记',
      row.workOrders ?? 0,
      Number(row.qualifiedQty || 0),
      Number(row.wage || 0).toFixed(2)
    ])
  ]
  const csv = '\ufeff' + lines.map((line) => line.map(csvCell).join(',')).join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `计件工资_${month.value}.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${rows.length} 条工资记录`)
}

onMounted(() => {
  loadProduction()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">生产报表</h2>
        <p class="page-sub">产量、计件工资与订单交期集中查看，工资按报工合格数与工序工价核算</p>
      </div>
    </div>

    <div class="panel report-panel">
      <el-tabs v-model="activeTab" class="report-tabs" @tab-change="onTabChange">
        <el-tab-pane label="产量报表" name="output">
          <div class="filter-bar tab-filter">
            <el-date-picker
              v-model="range"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              :clearable="false"
              style="width: 260px"
            />
            <el-button type="primary" :loading="prodLoading" @click="loadProduction">查询</el-button>
            <div class="spacer"></div>
            <span class="toolbar-tip">按报工时间统计，含不良与合格数量</span>
          </div>

          <div v-loading="prodLoading" class="tab-body">
            <div class="kpi-grid kpi-4 report-kpi">
              <div class="kpi-card">
                <div class="kpi-label">总产量</div>
                <div class="kpi-value">{{ fmtQty(prodSummary.totalQty) }}<small>件</small></div>
                <div class="kpi-foot">区间内报工总量</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">合格数量</div>
                <div class="kpi-value">{{ fmtQty(prodSummary.qualifiedQty) }}<small>件</small></div>
                <div class="kpi-foot">合格品累计</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">不良数量</div>
                <div class="kpi-value">{{ fmtQty(prodSummary.defectQty) }}<small>件</small></div>
                <div class="kpi-foot">报工登记的不良品</div>
              </div>
              <div class="kpi-card" :class="{ 'kpi-alert': Number(prodSummary.defectQty || 0) > 0 }">
                <div class="kpi-label">不良率</div>
                <div class="kpi-value">{{ rateText(prodSummary.defectRate) }}</div>
                <div class="kpi-foot">共 {{ prodSummary.reports ?? 0 }} 笔报工</div>
              </div>
            </div>

            <div class="panel sub-panel">
              <div class="card-head">
                <div class="card-title">按日产量</div>
                <span class="toolbar-tip">日汇总</span>
              </div>
              <div class="table-wrap">
                <el-table :data="production.daily" size="small" show-summary empty-text="所选区间暂无报工">
                  <el-table-column prop="date" label="日期" min-width="120">
                    <template #default="{ row }"><span class="mono">{{ row.date || '-' }}</span></template>
                  </el-table-column>
                  <el-table-column label="产量" min-width="110" align="right">
                    <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
                  </el-table-column>
                  <el-table-column label="合格" min-width="110" align="right">
                    <template #default="{ row }"><span class="num">{{ fmtQty(row.qualifiedQty) }}</span></template>
                  </el-table-column>
                  <el-table-column label="不良" min-width="110" align="right">
                    <template #default="{ row }">
                      <span class="num" :class="Number(row.defectQty) > 0 ? 'danger-text' : ''">
                        {{ fmtQty(row.defectQty) }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="不良率" width="110" align="right">
                    <template #default="{ row }">
                      <span class="num" :class="Number(row.defectQty) > 0 ? 'danger-text' : 'muted'">
                        {{ rowRate(row) }}
                      </span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>

            <div class="report-grid">
              <div class="panel sub-panel">
                <div class="card-head">
                  <div class="card-title">按工序</div>
                  <span class="toolbar-tip">按工艺路线工序统计</span>
                </div>
                <div class="table-wrap">
                  <el-table :data="production.byOperation" size="small" show-summary empty-text="暂无工序数据">
                    <el-table-column prop="name" label="工序" min-width="110" />
                    <el-table-column label="产量" width="90" align="right">
                      <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
                    </el-table-column>
                    <el-table-column label="合格" width="90" align="right">
                      <template #default="{ row }"><span class="num">{{ fmtQty(row.qualifiedQty) }}</span></template>
                    </el-table-column>
                    <el-table-column label="不良" width="90" align="right">
                      <template #default="{ row }">
                        <span class="num" :class="Number(row.defectQty) > 0 ? 'danger-text' : ''">
                          {{ fmtQty(row.defectQty) }}
                        </span>
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
              </div>

              <div class="panel sub-panel">
                <div class="card-head">
                  <div class="card-title">按人员</div>
                  <span class="toolbar-tip">按报工操作工汇总</span>
                </div>
                <div class="table-wrap">
                  <el-table :data="production.byWorker" size="small" show-summary empty-text="暂无人员数据">
                    <el-table-column label="人员" min-width="110">
                      <template #default="{ row }">
                        <span v-if="row.worker">{{ row.worker }}</span>
                        <span v-else class="muted">未登记</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="产量" width="90" align="right">
                      <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
                    </el-table-column>
                    <el-table-column label="合格" width="90" align="right">
                      <template #default="{ row }"><span class="num">{{ fmtQty(row.qualifiedQty) }}</span></template>
                    </el-table-column>
                    <el-table-column label="不良" width="90" align="right">
                      <template #default="{ row }">
                        <span class="num" :class="Number(row.defectQty) > 0 ? 'danger-text' : ''">
                          {{ fmtQty(row.defectQty) }}
                        </span>
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="计件工资" name="payroll">
          <div class="filter-bar tab-filter">
            <el-date-picker
              v-model="month"
              type="month"
              value-format="YYYY-MM"
              placeholder="选择月份"
              :clearable="false"
              style="width: 160px"
            />
            <el-button type="primary" :loading="payrollLoading" @click="loadPayroll">查询</el-button>
            <div class="spacer"></div>
            <el-button plain :disabled="!payroll.rows.length" @click="exportPayroll">
              <el-icon><Download /></el-icon>
              <span>导出 CSV</span>
            </el-button>
          </div>

          <div v-loading="payrollLoading" class="tab-body">
            <div class="kpi-grid kpi-3 report-kpi">
              <div class="kpi-card">
                <div class="kpi-label">总工资</div>
                <div class="kpi-value">{{ fmtMoney(wageSummary.totalWage) }}<small>元</small></div>
                <div class="kpi-foot">合格数量 × 工序工价</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">计件人数</div>
                <div class="kpi-value">{{ wageSummary.workers ?? 0 }}<small>人</small></div>
                <div class="kpi-foot">当月有报工的操作工</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">合格数量</div>
                <div class="kpi-value">{{ fmtQty(wageSummary.qualifiedQty) }}<small>件</small></div>
                <div class="kpi-foot">参与计件的合格品总数</div>
              </div>
            </div>

            <div class="panel sub-panel">
              <div class="card-head">
                <div class="card-title">人员工资明细</div>
                <span class="toolbar-tip">{{ month || '-' }} · 共 {{ payroll.rows.length }} 人</span>
              </div>
              <div class="table-wrap">
                <el-table :data="payroll.rows" size="small" show-summary empty-text="所选月份暂无报工">
                  <el-table-column label="人员" min-width="140">
                    <template #default="{ row }">
                      <span v-if="row.worker">{{ row.worker }}</span>
                      <span v-else class="muted">未登记</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="涉及工单" min-width="110" align="right">
                    <template #default="{ row }"><span class="num">{{ row.workOrders ?? 0 }}</span></template>
                  </el-table-column>
                  <el-table-column label="合格数量" min-width="120" align="right">
                    <template #default="{ row }"><span class="num">{{ fmtQty(row.qualifiedQty) }}</span></template>
                  </el-table-column>
                  <el-table-column label="计件工资（元）" min-width="140" align="right">
                    <template #default="{ row }">
                      <span class="money">{{ fmtMoney(row.wage) }}</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
            <div class="muted report-note">
              计件工资 = Σ（报工合格数量 × 对应工序工价），工价取工艺路线中的工序单价。
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="交期预警" name="delivery">
          <div class="filter-bar tab-filter">
            <el-button :loading="deliveryLoading" @click="loadDelivery">刷新</el-button>
            <div class="spacer"></div>
            <span class="toolbar-tip">未完成 / 未取消订单，逾期行标红</span>
          </div>

          <div v-loading="deliveryLoading" class="tab-body">
            <div class="panel sub-panel">
              <div class="card-head">
                <div class="card-title">订单交期</div>
                <span class="toolbar-tip">
                  共 {{ deliveryRows.length }} 单 · 逾期 {{ deliveryRows.filter(isLate).length }} 单
                </span>
              </div>
              <div class="table-wrap">
                <el-table :data="deliveryRows" size="small" :row-class-name="deliveryRowClass" empty-text="暂无待交付订单">
                  <el-table-column label="订单号" min-width="150">
                    <template #default="{ row }"><span class="mono">{{ row.orderNo || '-' }}</span></template>
                  </el-table-column>
                  <el-table-column prop="customerName" label="客户" min-width="160">
                    <template #default="{ row }">
                      <span v-if="row.customerName">{{ row.customerName }}</span>
                      <span v-else class="muted">-</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="交期" width="120">
                    <template #default="{ row }">
                      <span class="mono" :class="{ 'danger-text': isLate(row) }">{{ row.deliveryDate || '-' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="状态" width="110">
                    <template #default="{ row }"><StatusTag :status="row.status" /></template>
                  </el-table-column>
                  <el-table-column label="订单数量" width="110" align="right">
                    <template #default="{ row }"><span class="num">{{ fmtQty(row.totalQty) }}</span></template>
                  </el-table-column>
                  <el-table-column label="已发货" width="100" align="right">
                    <template #default="{ row }"><span class="num">{{ fmtQty(row.shippedQty) }}</span></template>
                  </el-table-column>
                  <el-table-column label="交期状态" width="130">
                    <template #default="{ row }">
                      <span v-if="isLate(row)" class="status-tag danger">
                        <i></i>逾期 {{ row.lateDays ?? 0 }} 天
                      </span>
                      <span v-else class="status-tag success"><i></i>正常</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.report-panel {
  padding: 4px 16px 16px;
}

.report-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.report-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--line);
}

.report-tabs :deep(.el-tabs__item) {
  height: 46px;
  font-size: 13px;
  font-weight: 500;
}

.tab-filter {
  padding: 14px 0;
  border-bottom: none;
}

.tab-body {
  min-height: 200px;
}

.report-kpi {
  margin-bottom: 14px;
}

.kpi-grid.kpi-4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.kpi-grid.kpi-3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

@media (max-width: 1100px) {
  .kpi-grid.kpi-4,
  .kpi-grid.kpi-3 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.sub-panel {
  box-shadow: none;
  margin-bottom: 14px;
}

.report-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

@media (max-width: 1200px) {
  .report-grid {
    grid-template-columns: 1fr;
  }
}

.report-note {
  font-size: 12px;
  line-height: 1.8;
  padding: 0 2px 4px;
}

:deep(.el-table__row.row-late) td.el-table__cell {
  background: #fef3f2 !important;
}

:deep(.el-table__row.row-late:hover > td.el-table__cell) {
  background: #fee4e2 !important;
}
</style>
