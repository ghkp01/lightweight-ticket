<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../../api'
import { datedFileName, downloadCsv } from '../../utils/exportCsv'

const monthOptions = ref<any[]>([])
const month = ref('')
const activeTab = ref('stock')
const loading = ref(false)
const stock = ref<any>({ summary: {}, rows: [] })
const stockTotal = ref(0)
const stockPage = ref(1)
const stockSize = ref(20)
const settlement = ref<any>({})

const stockSummary = computed(() => stock.value.summary || {})
const stockRows = computed(() => stock.value.rows || [])

const loadMonths = async () => {
  monthOptions.value = (await api.get('/month-close/months', { limit: 24 })) || []
  if (!month.value && monthOptions.value.length) {
    month.value = monthOptions.value[0].month
  }
}

const loadStock = async () => {
  loading.value = true
  try {
    const data = await api.get('/month-close/stock', {
      month: month.value,
      page: stockPage.value,
      size: stockSize.value
    })
    stock.value = {
      summary: data?.summary || {},
      rows: data?.rows || []
    }
    stockTotal.value = data?.total ?? stock.value.rows.length
  } finally {
    loading.value = false
  }
}

const changeStockPage = (value: number) => {
  stockPage.value = value
  loadStock()
}

const changeStockSize = () => {
  stockPage.value = 1
  loadStock()
}

const changeMonth = () => {
  stockPage.value = 1
  reload()
}

const loadSettlement = async () => {
  loading.value = true
  try {
    settlement.value = (await api.get('/month-close/settlement', { month: month.value })) || {}
  } finally {
    loading.value = false
  }
}

const reload = async () => {
  if (!month.value) return
  if (activeTab.value === 'stock') {
    await loadStock()
  } else {
    await loadSettlement()
  }
}

const onTabChange = () => reload()

const exportStock = async () => {
  if (!stockTotal.value) {
    ElMessage.warning('没有可导出的库存月结数据')
    return
  }
  const data = await api.get('/month-close/stock', { month: month.value })
  const rows = data?.rows || []
  if (!rows.length) {
    ElMessage.warning('没有可导出的库存月结数据')
    return
  }
  const columns = [
    { key: 'warehouseName', label: '仓库', prop: 'warehouseName' },
    { key: 'productCode', label: '物料编码', prop: 'productCode' },
    { key: 'productName', label: '物料名称', prop: 'productName' },
    { key: 'unit', label: '单位', prop: 'unit' },
    { key: 'openQty', label: '期初数量', prop: 'openQty' },
    { key: 'openAmount', label: '期初金额', prop: 'openAmount' },
    { key: 'inQty', label: '本月入库', prop: 'inQty' },
    { key: 'inAmount', label: '入库金额', prop: 'inAmount' },
    { key: 'outQty', label: '本月出库', prop: 'outQty' },
    { key: 'outAmount', label: '出库金额', prop: 'outAmount' },
    { key: 'closeQty', label: '期末数量', prop: 'closeQty' },
    { key: 'closeAmount', label: '期末金额', prop: 'closeAmount' }
  ]
  const count = downloadCsv(null, rows, datedFileName(`库存月结_${month.value}`), columns)
  ElMessage.success(`已导出 ${count} 行库存月结`)
}

const exportSettlement = () => {
  const data = settlement.value
  if (!data || !data.month) {
    ElMessage.warning('还没有往来月结数据')
    return
  }
  const rows = [
    { item: '采购订单金额', value: data.purchase?.orderAmount, count: data.purchase?.orderCount, note: '不含已取消' },
    { item: '采购退货金额', value: data.purchaseReturn?.amount, count: data.purchaseReturn?.count, note: '已完成退货' },
    { item: '采购净额', value: data.purchaseNet, count: '', note: '采购 − 退货' },
    { item: '入库暂估金额', value: data.accrual?.amount, count: data.accrual?.count, note: '采购入库生成' },
    { item: '应付发票金额', value: data.invoice?.grossAmount, count: data.invoice?.count, note: '含红字冲减' },
    { item: '本月付款金额', value: data.payment?.amount, count: data.payment?.count, note: '' },
    { item: '销售订单金额', value: data.sales?.orderAmount, count: data.sales?.orderCount, note: '' },
    { item: '销售退货金额', value: data.salesReturn?.amount, count: data.salesReturn?.count, note: '' },
    { item: '销售净额', value: data.salesNet, count: '', note: '销售 − 退货' },
    { item: '发货金额', value: data.shipment?.amount, count: data.shipment?.count, note: '按订单价折算' }
  ]
  const columns = [
    { key: 'item', label: '项目', prop: 'item' },
    { key: 'value', label: '金额', prop: 'value' },
    { key: 'count', label: '笔数', prop: 'count' },
    { key: 'note', label: '口径', prop: 'note' }
  ]
  const count = downloadCsv(null, rows, datedFileName(`往来月结_${data.month}`), columns)
  ElMessage.success(`已导出 ${count} 行往来月结`)
}

onMounted(async () => {
  await loadMonths()
  await reload()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">月结报表</h2>
        <p class="page-sub">
          库存月结按流水算期初 / 入 / 出 / 期末，与库存台账天然自洽；往来月结汇总采购应付与销售应收
        </p>
      </div>
      <div class="head-actions">
        <el-button
          plain
          :disabled="activeTab === 'stock' ? !stockTotal : !settlement.month"
          @click="activeTab === 'stock' ? exportStock() : exportSettlement()"
        >
          导出 CSV
        </el-button>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-select v-model="month" placeholder="选择月份" style="width: 160px" @change="changeMonth">
          <el-option
            v-for="item in monthOptions"
            :key="item.month"
            :label="item.current ? `${item.month}（本月）` : item.month"
            :value="item.month"
          />
        </el-select>
        <el-button :loading="loading" @click="reload">刷新</el-button>
        <div class="spacer"></div>
        <span class="toolbar-tip">月结报表实时计算，不做结账锁定；调整历史单据后重新查看即更新</span>
      </div>

      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane label="库存月结" name="stock">
          <div v-loading="loading" class="tab-body">
            <div class="kpi-grid kpi-4">
              <div class="kpi-card">
                <div class="kpi-label">期初数量</div>
                <div class="kpi-value">{{ fmtQty(stockSummary.openQty) }}</div>
                <div class="kpi-foot">{{ stock.month || '-' }} 月初结存</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">本月入库</div>
                <div class="kpi-value">{{ fmtQty(stockSummary.inQty) }}</div>
                <div class="kpi-foot">含采购入库、退料、盘盈</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">本月出库</div>
                <div class="kpi-value">{{ fmtQty(stockSummary.outQty) }}</div>
                <div class="kpi-foot">含领料、发货、盘亏</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">期末金额</div>
                <div class="kpi-value">{{ fmtMoney(stockSummary.closeAmount) }}<small>元</small></div>
                <div class="kpi-foot">期末数量 {{ fmtQty(stockSummary.closeQty) }}</div>
              </div>
            </div>

            <div class="table-wrap">
              <el-table :data="stockRows" size="small" max-height="520" empty-text="所选月份没有库存流水">
                <el-table-column label="物料" min-width="190">
                  <template #default="{ row }">
                    <div>{{ row.productName }}</div>
                    <div class="sub mono">{{ row.productCode }}</div>
                  </template>
                </el-table-column>
                <el-table-column prop="warehouseName" label="仓库" width="110" />
                <el-table-column label="期初" width="130" align="right">
                  <template #default="{ row }">
                    <span class="num">{{ fmtQty(row.openQty) }}</span>
                    <span class="muted"> / {{ fmtMoney(row.openAmount) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="入库" width="130" align="right">
                  <template #default="{ row }">
                    <span class="num">{{ fmtQty(row.inQty) }}</span>
                    <span class="muted"> / {{ fmtMoney(row.inAmount) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="出库" width="130" align="right">
                  <template #default="{ row }">
                    <span class="num">{{ fmtQty(row.outQty) }}</span>
                    <span class="muted"> / {{ fmtMoney(row.outAmount) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="期末" width="130" align="right">
                  <template #default="{ row }">
                    <b class="num">{{ fmtQty(row.closeQty) }}</b>
                    <span class="muted"> / {{ fmtMoney(row.closeAmount) }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <div class="pager">
              <el-pagination
                v-model:current-page="stockPage"
                v-model:page-size="stockSize"
                :total="stockTotal"
                :page-sizes="[10, 20, 50, 100]"
                layout="total, sizes, prev, pager, next"
                @current-change="changeStockPage"
                @size-change="changeStockSize"
              />
            </div>
            <div class="muted note">
              期初取该月 1 日之前的流水累计，期末 = 期初 + 入库 − 出库；数量与金额都来自库存流水
              （出库按移动平均成本计价），不做手工调整分录。
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="往来月结" name="settlement">
          <div v-loading="loading" class="tab-body">
            <div class="kpi-grid kpi-4">
              <div class="kpi-card">
                <div class="kpi-label">采购净额</div>
                <div class="kpi-value">{{ fmtMoney(settlement.purchaseNet) }}<small>元</small></div>
                <div class="kpi-foot">采购 {{ fmtMoney(settlement.purchase?.orderAmount) }} − 退货</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">入库暂估</div>
                <div class="kpi-value">{{ fmtMoney(settlement.accrual?.amount) }}<small>元</small></div>
                <div class="kpi-foot">采购入库自动生成</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">应付发票</div>
                <div class="kpi-value">{{ fmtMoney(settlement.invoice?.grossAmount) }}<small>元</small></div>
                <div class="kpi-foot">含红字冲减，已付 {{ fmtMoney(settlement.payment?.amount) }}</div>
              </div>
              <div class="kpi-card">
                <div class="kpi-label">销售净额</div>
                <div class="kpi-value">{{ fmtMoney(settlement.salesNet) }}<small>元</small></div>
                <div class="kpi-foot">发货 {{ fmtMoney(settlement.shipment?.amount) }}</div>
              </div>
            </div>

            <div class="table-wrap">
              <el-table
                :data="[
                  { item: '采购订单金额', value: settlement.purchase?.orderAmount, count: settlement.purchase?.orderCount, note: '不含已取消' },
                  { item: '采购退货金额', value: settlement.purchaseReturn?.amount, count: settlement.purchaseReturn?.count, note: '已完成退货' },
                  { item: '采购净额', value: settlement.purchaseNet, count: '', note: '采购 − 退货' },
                  { item: '入库暂估金额', value: settlement.accrual?.amount, count: settlement.accrual?.count, note: '采购入库生成暂估' },
                  { item: '应付发票金额', value: settlement.invoice?.grossAmount, count: settlement.invoice?.count, note: '含红字发票冲减' },
                  { item: '本月付款金额', value: settlement.payment?.amount, count: settlement.payment?.count, note: '按付款日期' },
                  { item: '销售订单金额', value: settlement.sales?.orderAmount, count: settlement.sales?.orderCount, note: '不含已取消' },
                  { item: '销售退货金额', value: settlement.salesReturn?.amount, count: settlement.salesReturn?.count, note: '按订单价折算' },
                  { item: '销售净额', value: settlement.salesNet, count: '', note: '销售 − 退货' },
                  { item: '发货金额', value: settlement.shipment?.amount, count: settlement.shipment?.count, note: '按订单单价折算' }
                ]"
                size="small"
                max-height="480"
              >
                <el-table-column prop="item" label="项目" min-width="200" />
                <el-table-column label="金额（元）" width="160" align="right">
                  <template #default="{ row }"><span class="money">{{ fmtMoney(row.value) }}</span></template>
                </el-table-column>
                <el-table-column label="笔数" width="100" align="right">
                  <template #default="{ row }"><span class="num muted">{{ row.count ?? '-' }}</span></template>
                </el-table-column>
                <el-table-column prop="note" label="口径" min-width="220">
                  <template #default="{ row }"><span class="muted">{{ row.note || '-' }}</span></template>
                </el-table-column>
              </el-table>
            </div>
            <div class="muted note">
              往来月结只做业务台账口径的汇总，不生成记账凭证；金额都取单据上的价税合计或暂估金额，
              退货按原订单单价折算。
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

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

.sub {
  font-size: 11px;
  color: var(--muted);
}

.note {
  font-size: 12px;
  line-height: 1.8;
  padding: 10px 2px 0;
}
</style>
