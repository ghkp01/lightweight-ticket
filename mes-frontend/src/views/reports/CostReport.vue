<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../../api'
import StatusTag from '../../components/StatusTag.vue'

const pad = (value: number) => String(value).padStart(2, '0')
const dateText = (date: Date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`

const today = new Date()
const monthStart = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-01`

const activeTab = ref('workOrders')
const range = ref<[string, string]>([monthStart, dateText(today)])
const keyword = ref('')
const status = ref('')
const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const summary = ref<any>({})
const detail = ref<any>(null)
const drawer = ref(false)
const recalcLoading = ref(false)

const productLoading = ref(false)
const productKeyword = ref('')
const products = ref<any[]>([])

const statusOptions = [
  { value: 'RELEASED', label: '已下达' },
  { value: 'IN_PROGRESS', label: '生产中' },
  { value: 'FINISHED', label: '已完工' },
  { value: 'CLOSED', label: '已关闭' }
]

const materialTotal = computed(() =>
  (detail.value?.materials || []).reduce((sum: number, row: any) => sum + Number(row.amount || 0), 0)
)
const laborTotal = computed(() =>
  (detail.value?.labors || []).reduce((sum: number, row: any) => sum + Number(row.amount || 0), 0)
)

const loadSummary = async () => {
  try {
    summary.value =
      (await api.get('/cost/summary', {
        from: range.value?.[0] || undefined,
        to: range.value?.[1] || undefined
      })) || {}
  } catch {
    // 概览失败不影响明细列表
  }
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/cost/work-orders', {
      keyword: keyword.value || undefined,
      status: status.value || undefined,
      from: range.value?.[0] || undefined,
      to: range.value?.[1] || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = data?.records || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

const reload = async () => {
  page.value = 1
  await Promise.all([load(), loadSummary()])
}

const changePage = (value: number) => {
  page.value = value
  load()
}

const loadProducts = async () => {
  productLoading.value = true
  try {
    const data = await api.get('/cost/products', { keyword: productKeyword.value || undefined })
    products.value = Array.isArray(data) ? data : data?.records || []
  } finally {
    productLoading.value = false
  }
}

const openDetail = async (row: any) => {
  detail.value = await api.get(`/cost/work-orders/${row.id}`)
  drawer.value = true
}

const recalc = async (row: any) => {
  recalcLoading.value = true
  try {
    const result = await api.post(`/cost/work-orders/${row.id}/recalc`)
    ElMessage.success(
      `工单 ${result?.workNo || row.workNo} 已重算，单位成本 ${fmtMoney(result?.unitCost)} 元`
    )
    await Promise.all([load(), loadSummary()])
  } finally {
    recalcLoading.value = false
  }
}

const recalcAll = async () => {
  recalcLoading.value = true
  try {
    const result = await api.post('/cost/recalc', { status: status.value || undefined })
    ElMessage.success(`已重算 ${result?.count ?? 0} 张工单成本`)
    await reload()
  } finally {
    recalcLoading.value = false
  }
}

const marginClass = (value: any) => {
  const num = Number(value)
  if (!Number.isFinite(num)) return 'muted'
  return num < 0 ? 'danger-text' : ''
}

const csvCell = (value: any) => {
  const text = String(value ?? '')
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

const exportCsv = async () => {
  const data = await api.get('/cost/work-orders', {
    keyword: keyword.value || undefined,
    status: status.value || undefined,
    from: range.value?.[0] || undefined,
    to: range.value?.[1] || undefined,
    page: 1,
    size: 1000
  })
  const list = data?.records || []
  if (!list.length) {
    ElMessage.warning('暂无可导出的成本数据')
    return
  }
  const lines = [
    ['工单号', '产品编码', '产品名称', '状态', '计划数量', '合格数量', '材料成本', '人工成本',
      '制造费用', '总成本', '单位成本', '售价', '毛利率(%)', '结算时间'],
    ...list.map((row: any) => [
      row.workNo,
      row.productCode,
      row.productName,
      row.status,
      row.planQty,
      row.qualifiedQty,
      Number(row.materialCost || 0).toFixed(2),
      Number(row.laborCost || 0).toFixed(2),
      Number(row.overheadCost || 0).toFixed(2),
      Number(row.totalCost || 0).toFixed(2),
      Number(row.unitCost || 0).toFixed(4),
      Number(row.price || 0).toFixed(2),
      row.grossMargin ?? '',
      row.costedAt || ''
    ])
  ]
  const csv = '\ufeff' + lines.map((line) => line.map(csvCell).join(',')).join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `工单成本_${range.value?.[0] || ''}_${range.value?.[1] || ''}.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${list.length} 张工单成本`)
}

const onTabChange = (name: any) => {
  if (name === 'products' && !products.value.length) loadProducts()
}

onMounted(() => {
  load()
  loadSummary()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">成本核算</h2>
        <p class="page-sub">
          工单成本按「料 + 工 + 费」结算：料取领料流水金额，工取报工量 × 工序单价，费按人工比例摊
        </p>
      </div>
    </div>

    <div class="panel report-panel">
      <el-tabs v-model="activeTab" class="report-tabs" @tab-change="onTabChange">
        <el-tab-pane label="工单成本" name="workOrders">
          <div class="filter-bar tab-filter">
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
            <el-select v-model="status" placeholder="全部状态" clearable style="width: 130px">
              <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-input
              v-model="keyword"
              placeholder="工单号 / 产品编码 / 名称"
              clearable
              style="width: 220px"
              @keyup.enter="reload"
            />
            <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
            <div class="spacer"></div>
            <el-button plain :loading="recalcLoading" @click="recalcAll">
              <el-icon><Refresh /></el-icon>
              <span>批量重算</span>
            </el-button>
            <el-button plain :disabled="!rows.length" @click="exportCsv">
              <el-icon><Download /></el-icon>
              <span>导出 CSV</span>
            </el-button>
          </div>

          <div v-loading="loading" class="tab-body">
            <div class="kpi-grid kpi-4 report-kpi">
              <div class="kpi-card">
                <div class="kpi-label">已结算工单</div>
                <div class="kpi-value">{{ summary.workOrders ?? 0 }}<small>张</small></div>
                <div class="kpi-foot">区间内已结算成本</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">总成本</div>
                <div class="kpi-value">{{ fmtMoney(summary.totalCost) }}<small>元</small></div>
                <div class="kpi-foot">
                  料 {{ summary.materialRate ?? 0 }}% · 工 {{ summary.laborRate ?? 0 }}% · 费
                  {{ summary.overheadRate ?? 0 }}% · 外协 {{ summary.outsourceRate ?? 0 }}%
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">平均单位成本</div>
                <div class="kpi-value">{{ fmtMoney(summary.avgUnitCost) }}<small>元/件</small></div>
                <div class="kpi-foot">总成本 ÷ 合格数量</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">制造费用比例</div>
                <div class="kpi-value">{{ summary.overheadRatio ?? 0 }}<small>%</small></div>
                <div class="kpi-foot">按人工成本摊，可在系统配置修改</div>
              </div>
            </div>

            <div class="panel sub-panel">
              <div class="card-head">
                <div class="card-title">工单成本明细</div>
                <span class="toolbar-tip">共 {{ total }} 张 · 毛利率按产品售价对比单位成本</span>
              </div>
              <div class="table-wrap">
                <el-table :data="rows" size="small" empty-text="所选区间暂无工单">
                  <el-table-column label="工单号" min-width="140">
                    <template #default="{ row }">
                      <span class="mono link-text" @click="openDetail(row)">{{ row.workNo }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="产品" min-width="180">
                    <template #default="{ row }">
                      <div class="cell-main">{{ row.productName }}</div>
                      <div class="cell-sub mono">{{ row.productCode }}</div>
                    </template>
                  </el-table-column>
                  <el-table-column label="状态" width="100">
                    <template #default="{ row }"><StatusTag :status="row.status" /></template>
                  </el-table-column>
                  <el-table-column label="合格 / 计划" width="120" align="right">
                    <template #default="{ row }">
                      <span class="num">{{ fmtQty(row.qualifiedQty) }}</span>
                      <span class="muted"> / {{ fmtQty(row.planQty) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="材料" width="110" align="right">
                    <template #default="{ row }"><span class="money">{{ fmtMoney(row.materialCost) }}</span></template>
                  </el-table-column>
                  <el-table-column label="人工" width="110" align="right">
                    <template #default="{ row }"><span class="money">{{ fmtMoney(row.laborCost) }}</span></template>
                  </el-table-column>
                  <el-table-column label="制造费用" width="110" align="right">
                    <template #default="{ row }"><span class="money">{{ fmtMoney(row.overheadCost) }}</span></template>
                  </el-table-column>
                  <el-table-column label="外协费" width="110" align="right">
                    <template #default="{ row }">
                      <span v-if="Number(row.outsourceCost) > 0" class="money">{{ fmtMoney(row.outsourceCost) }}</span>
                      <span v-else class="muted">-</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="总成本" width="120" align="right">
                    <template #default="{ row }">
                      <span class="money strong">{{ fmtMoney(row.totalCost) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="单位成本" width="110" align="right">
                    <template #default="{ row }"><span class="num">{{ fmtMoney(row.unitCost) }}</span></template>
                  </el-table-column>
                  <el-table-column label="售价" width="100" align="right">
                    <template #default="{ row }"><span class="num">{{ fmtMoney(row.price) }}</span></template>
                  </el-table-column>
                  <el-table-column label="毛利率" width="100" align="right">
                    <template #default="{ row }">
                      <span class="num" :class="marginClass(row.grossMargin)">
                        {{ row.grossMargin === null || row.grossMargin === undefined ? '-' : `${row.grossMargin}%` }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="结算时间" width="140">
                    <template #default="{ row }">
                      <span class="mono muted">{{ row.costedAt || '未结算' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="130" fixed="right">
                    <template #default="{ row }">
                      <el-button link type="primary" size="small" @click="openDetail(row)">构成</el-button>
                      <el-button link type="primary" size="small" @click="recalc(row)">重算</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
              <div class="pager">
                <el-pagination
                  layout="total, prev, pager, next"
                  :total="total"
                  :page-size="size"
                  :current-page="page"
                  @current-change="changePage"
                />
              </div>
            </div>
            <div class="muted report-note">
              材料成本 = 领料出库金额 - 退料金额；人工成本 = 报工数量 × 工序单价（不良品同样耗工）；
              制造费用 = 人工成本 × 配置比例；单位成本 = 总成本 ÷ 合格数量。
              工单完工时自动结算，也可以在列表里手动重算。
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="产品成本" name="products">
          <div class="filter-bar tab-filter">
            <el-input
              v-model="productKeyword"
              placeholder="产品编码 / 名称"
              clearable
              style="width: 220px"
              @keyup.enter="loadProducts"
            />
            <el-button type="primary" :loading="productLoading" @click="loadProducts">查询</el-button>
            <div class="spacer"></div>
            <span class="toolbar-tip">移动平均成本随入库刷新，与档案成本差异过大建议核对单价</span>
          </div>

          <div v-loading="productLoading" class="tab-body">
            <div class="panel sub-panel">
              <div class="card-head">
                <div class="card-title">产品库存成本</div>
                <span class="toolbar-tip">共 {{ products.length }} 个产品</span>
              </div>
              <div class="table-wrap">
                <el-table :data="products" size="small" empty-text="暂无产品成本数据">
                  <el-table-column label="编码" width="130">
                    <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
                  </el-table-column>
                  <el-table-column prop="name" label="名称" min-width="180" />
                  <el-table-column prop="spec" label="规格" min-width="120" />
                  <el-table-column prop="unit" label="单位" width="70" />
                  <el-table-column label="库存数量" width="110" align="right">
                    <template #default="{ row }"><span class="num">{{ fmtQty(row.stockQty) }}</span></template>
                  </el-table-column>
                  <el-table-column label="移动平均成本" width="130" align="right">
                    <template #default="{ row }"><span class="num">{{ fmtMoney(row.avgCost) }}</span></template>
                  </el-table-column>
                  <el-table-column label="档案成本" width="110" align="right">
                    <template #default="{ row }"><span class="num muted">{{ fmtMoney(row.cost) }}</span></template>
                  </el-table-column>
                  <el-table-column label="成本差异" width="120" align="right">
                    <template #default="{ row }">
                      <span class="num" :class="Number(row.costDiff || 0) !== 0 ? 'danger-text' : 'muted'">
                        {{ fmtMoney(row.costDiff) }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="库存金额" width="130" align="right">
                    <template #default="{ row }"><span class="money">{{ fmtMoney(row.stockAmount) }}</span></template>
                  </el-table-column>
                  <el-table-column label="售价" width="100" align="right">
                    <template #default="{ row }"><span class="num">{{ fmtMoney(row.price) }}</span></template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-drawer v-model="drawer" :title="`成本构成 · ${detail?.workNo || ''}`" size="720px">
      <div v-if="detail" class="cost-detail">
        <div class="kpi-grid kpi-4 detail-kpi">
          <div class="kpi-card">
            <div class="kpi-label">材料成本</div>
            <div class="kpi-value">{{ fmtMoney(materialTotal) }}</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">人工成本</div>
            <div class="kpi-value">{{ fmtMoney(laborTotal) }}</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">制造费用</div>
            <div class="kpi-value">{{ fmtMoney(detail.overheadCost) }}</div>
            <div class="kpi-foot">外协费另计 {{ fmtMoney(detail.outsourceCost) }} 元</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">单位成本</div>
            <div class="kpi-value">{{ fmtMoney(detail.unitCost) }}</div>
          </div>
        </div>

        <div class="detail-head">
          <span>材料明细</span>
          <span class="muted">按物料汇总，退料已抵减</span>
        </div>
        <el-table :data="detail.materials || []" size="small" empty-text="该工单还没有领料流水">
          <el-table-column label="物料" min-width="200">
            <template #default="{ row }">
              <div class="cell-main">{{ row.productName }}</div>
              <div class="cell-sub mono">{{ row.productCode }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="70" />
          <el-table-column label="数量" width="110" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="right">
            <template #default="{ row }"><span class="money">{{ fmtMoney(row.amount) }}</span></template>
          </el-table-column>
        </el-table>

        <div class="detail-head">
          <span>人工明细</span>
          <span class="muted">报工数量 × 工序单价</span>
        </div>
        <el-table :data="detail.labors || []" size="small" empty-text="该工单还没有报工">
          <el-table-column prop="seq" label="序" width="60" />
          <el-table-column prop="processName" label="工序" min-width="140" />
          <el-table-column label="报工量" width="100" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column label="合格量" width="100" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.qualifiedQty) }}</span></template>
          </el-table-column>
          <el-table-column label="工序单价" width="110" align="right">
            <template #default="{ row }"><span class="num">{{ fmtMoney(row.unitPrice) }}</span></template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="right">
            <template #default="{ row }"><span class="money">{{ fmtMoney(row.amount) }}</span></template>
          </el-table-column>
        </el-table>

        <div class="detail-head">
          <span>外协明细</span>
          <span class="muted">收回合格数 × 外协单价，外协干的活不计入人工</span>
        </div>
        <el-table :data="detail.outsources || []" size="small" empty-text="该工单没有外协">
          <el-table-column label="外协单号" width="150">
            <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          </el-table-column>
          <el-table-column label="工序" width="110">
            <template #default="{ row }">{{ row.operationSeq }} · {{ row.operationName }}</template>
          </el-table-column>
          <el-table-column prop="supplierName" label="外协厂" min-width="140" show-overflow-tooltip />
          <el-table-column label="发出 / 收回" width="120" align="right">
            <template #default="{ row }">
              <span class="num">{{ fmtQty(row.qty) }} / {{ fmtQty(row.receivedQty) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单价" width="95" align="right">
            <template #default="{ row }"><span class="num">{{ fmtMoney(row.price) }}</span></template>
          </el-table-column>
          <el-table-column label="外协费" width="115" align="right">
            <template #default="{ row }"><span class="money">{{ fmtMoney(row.amount) }}</span></template>
          </el-table-column>
        </el-table>

        <div class="muted report-note">
          制造费用按人工成本 × {{ detail.overheadRate ?? 0 }}% 摊入，比例在「系统管理 → 系统配置」里的
          manufacturing.overhead.rate 调整。外协费单列，不进人工也不摊制造费用。
        </div>
      </div>
    </el-drawer>
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

.report-kpi,
.detail-kpi {
  margin-bottom: 14px;
}

.kpi-grid.kpi-4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

@media (max-width: 1100px) {
  .kpi-grid.kpi-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.sub-panel {
  box-shadow: none;
  margin-bottom: 14px;
}

.report-note {
  font-size: 12px;
  line-height: 1.8;
  padding: 0 2px 4px;
}

.cell-main {
  font-size: 13px;
}

.cell-sub {
  font-size: 11px;
  color: var(--muted);
}

.link-text {
  color: var(--primary);
  cursor: pointer;
}

.strong {
  font-weight: 600;
}

.detail-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.detail-head .muted {
  font-size: 12px;
  font-weight: 400;
}
</style>
