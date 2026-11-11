<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../../api'
import ReferenceSelect from '../../components/ReferenceSelect.vue'

const loading = ref(false)
const summary = ref<any>({})
const tab = ref('invoices')

// ---------------- 发票
const invoices = ref<any[]>([])
const invoiceTotal = ref(0)
const invoicePage = ref(1)
const invoiceSize = ref(20)
const invoiceKeyword = ref('')
const invoiceStatus = ref('')

// ---------------- 待开票发货单
const shipments = ref<any[]>([])
const shipmentTotal = ref(0)
const shipmentPage = ref(1)
const shipmentSize = ref(20)
const shipmentKeyword = ref('')

// ---------------- 收款
const receipts = ref<any[]>([])
const receiptTotal = ref(0)
const receiptPage = ref(1)
const receiptSize = ref(20)
const receiptKeyword = ref('')

// ---------------- 账龄
const aging = ref<any>({ rows: [] })

const invoiceDialog = ref(false)
const invoiceSaving = ref(false)
const invoiceForm = reactive<any>({
  customerId: null,
  invoiceType: 'NORMAL',
  invoiceDate: new Date().toISOString().slice(0, 10),
  dueDate: '',
  currency: 'CNY',
  taxRatePercent: 13,
  priceIncludesTax: true,
  remark: '',
  items: []
})

const shipmentDialog = ref(false)
const shipmentTarget = ref<any>(null)
const shipmentForm = reactive<any>({ taxRatePercent: 13, creditDays: 30, remark: '' })

const receiptDialog = ref(false)
const receiptSaving = ref(false)
const receiptForm = reactive<any>({
  customerId: null,
  receiptDate: new Date().toISOString().slice(0, 10),
  currency: 'CNY',
  amount: 0,
  method: 'BANK',
  referenceNo: '',
  remark: '',
  applies: []
})
const openInvoices = ref<any[]>([])

const detailDrawer = ref(false)
const detail = ref<any>(null)

const currencySymbol = (code: string) => {
  if (code === 'USD') return '$'
  if (code === 'EUR') return '€'
  return '¥'
}

const invoiceAmount = computed(() =>
  invoiceForm.items.reduce((sum: number, item: any) =>
    sum + Number(item.qty || 0) * Number(item.price || 0), 0)
)
const invoiceTotals = computed(() => {
  const entered = invoiceAmount.value
  const rate = Number(invoiceForm.taxRatePercent || 0) / 100
  if (invoiceForm.priceIncludesTax) {
    const net = rate === 0 ? entered : entered / (1 + rate)
    return { net, tax: entered - net, total: entered }
  }
  const tax = entered * rate
  return { net: entered, tax, total: entered + tax }
})
const receiptApplied = computed(() =>
  receiptForm.applies.reduce((sum: number, row: any) => sum + Number(row.amount || 0), 0)
)

const loadSummary = async () => {
  summary.value = await api.get('/finance/receivables/summary')
}

const loadInvoices = async () => {
  loading.value = true
  try {
    const data = await api.get('/finance/receivables/invoices', {
      keyword: invoiceKeyword.value || undefined,
      status: invoiceStatus.value || undefined,
      page: invoicePage.value,
      size: invoiceSize.value
    })
    invoices.value = data.records || []
    invoiceTotal.value = data.total || 0
  } finally {
    loading.value = false
  }
}
const loadShipments = async () => {
  loading.value = true
  try {
    const data = await api.get('/finance/receivables/invoiceable-shipments', {
      keyword: shipmentKeyword.value || undefined,
      page: shipmentPage.value,
      size: shipmentSize.value
    })
    shipments.value = data.records || []
    shipmentTotal.value = data.total || 0
  } finally {
    loading.value = false
  }
}
const loadReceipts = async () => {
  loading.value = true
  try {
    const data = await api.get('/finance/receivables/receipts', {
      keyword: receiptKeyword.value || undefined,
      page: receiptPage.value,
      size: receiptSize.value
    })
    receipts.value = data.records || []
    receiptTotal.value = data.total || 0
  } finally {
    loading.value = false
  }
}
const loadAging = async () => {
  aging.value = await api.get('/finance/receivables/aging')
}

const refreshAll = async () => {
  await Promise.all([loadSummary(), loadInvoices(), loadShipments(), loadReceipts(), loadAging()])
}

const onTabChange = async (name: string) => {
  if (name === 'shipments') await loadShipments()
  if (name === 'receipts') await loadReceipts()
  if (name === 'aging') await loadAging()
}

const openInvoiceDialog = () => {
  Object.assign(invoiceForm, {
    customerId: null,
    invoiceType: 'NORMAL',
    invoiceDate: new Date().toISOString().slice(0, 10),
    dueDate: '',
    currency: 'CNY',
    taxRatePercent: 13,
    priceIncludesTax: true,
    remark: '',
    items: [{ productId: null, qty: 1, price: 0 }]
  })
  invoiceDialog.value = true
}
const addInvoiceItem = () => invoiceForm.items.push({ productId: null, qty: 1, price: 0 })
const removeInvoiceItem = (index: number) => invoiceForm.items.splice(index, 1)
const onInvoiceProduct = (item: any, product: any) => {
  if (product) item.price = Number(product.price || 0)
}

const submitInvoice = async () => {
  if (!invoiceForm.customerId) {
    ElMessage.warning('请选择客户')
    return
  }
  if (!invoiceForm.items.length || invoiceForm.items.some((item: any) => !item.qty || item.qty <= 0)) {
    ElMessage.warning('请完整填写发票明细')
    return
  }
  invoiceSaving.value = true
  try {
    await api.post('/finance/receivables/invoices', {
      customerId: invoiceForm.customerId,
      invoiceType: invoiceForm.invoiceType,
      invoiceDate: invoiceForm.invoiceDate,
      dueDate: invoiceForm.dueDate || null,
      currency: invoiceForm.currency,
      taxRate: Number(invoiceForm.taxRatePercent || 0) / 100,
      priceIncludesTax: invoiceForm.priceIncludesTax,
      remark: invoiceForm.remark,
      items: invoiceForm.items.map((item: any) => ({
        productId: item.productId,
        qty: Number(item.qty),
        price: Number(item.price)
      }))
    })
    ElMessage.success('发票已开具并记账')
    invoiceDialog.value = false
    await refreshAll()
  } finally {
    invoiceSaving.value = false
  }
}

const openShipmentDialog = (row: any) => {
  shipmentTarget.value = row
  Object.assign(shipmentForm, { taxRatePercent: 13, creditDays: 30, remark: '' })
  shipmentDialog.value = true
}
const submitShipmentInvoice = async () => {
  if (!shipmentTarget.value) return
  invoiceSaving.value = true
  try {
    await api.post(`/finance/receivables/invoices/from-shipment/${shipmentTarget.value.id}`, {
      taxRate: Number(shipmentForm.taxRatePercent || 0) / 100,
      creditDays: Number(shipmentForm.creditDays || 30),
      remark: shipmentForm.remark
    })
    ElMessage.success('发货单已开票并记账')
    shipmentDialog.value = false
    await refreshAll()
  } finally {
    invoiceSaving.value = false
  }
}

const openDetail = async (row: any) => {
  detail.value = await api.get(`/finance/receivables/invoices/${row.id}`)
  detailDrawer.value = true
}

const cancelInvoice = async (row: any) => {
  await ElMessageBox.confirm(`作废发票 ${row.invoiceNo}？会同时生成红冲凭证。`, '作废发票')
  await api.post(`/finance/receivables/invoices/${row.id}/cancel`)
  ElMessage.success('发票已作废')
  await refreshAll()
}

const openReceiptDialog = async () => {
  Object.assign(receiptForm, {
    customerId: null,
    receiptDate: new Date().toISOString().slice(0, 10),
    currency: 'CNY',
    amount: 0,
    method: 'BANK',
    referenceNo: '',
    remark: '',
    applies: []
  })
  openInvoices.value = []
  receiptDialog.value = true
}
const loadOpenInvoices = async () => {
  if (!receiptForm.customerId) {
    openInvoices.value = []
    return
  }
  const data = await api.get('/finance/receivables/invoices', {
    customerId: receiptForm.customerId,
    page: 1,
    size: 100
  })
  openInvoices.value = (data.records || [])
    .filter((row: any) => row.invoiceType === 'NORMAL'
      && ['ISSUED', 'PARTIAL'].includes(row.status)
      && Number(row.remaining) > 0)
    .map((row: any) => ({ ...row, applyAmount: 0, checked: false }))
  // 默认用同币种发票，跨币种核销在发票上就会被后端拒绝
  receiptForm.currency = 'CNY'
}
const toggleApply = (row: any) => {
  if (!row.checked) row.applyAmount = 0
  else row.applyAmount = Number(row.remaining || 0)
  const first = openInvoices.value.find((item: any) => item.checked)
  if (first) receiptForm.currency = first.currency
}
const submitReceipt = async () => {
  if (!receiptForm.customerId) {
    ElMessage.warning('请选择客户')
    return
  }
  if (!receiptForm.amount || Number(receiptForm.amount) <= 0) {
    ElMessage.warning('请填写收款金额')
    return
  }
  if (receiptApplied.value > Number(receiptForm.amount)) {
    ElMessage.warning('核销金额不能超过收款金额')
    return
  }
  receiptSaving.value = true
  try {
    await api.post('/finance/receivables/receipts', {
      customerId: receiptForm.customerId,
      receiptDate: receiptForm.receiptDate,
      currency: receiptForm.currency,
      amount: Number(receiptForm.amount),
      method: receiptForm.method,
      referenceNo: receiptForm.referenceNo,
      remark: receiptForm.remark,
      applies: openInvoices.value
        .filter((row: any) => row.checked && Number(row.applyAmount) > 0)
        .map((row: any) => ({ invoiceId: row.id, amount: Number(row.applyAmount) }))
    })
    ElMessage.success('收款已登记并记账')
    receiptDialog.value = false
    await refreshAll()
  } finally {
    receiptSaving.value = false
  }
}

const cancelReceipt = async (row: any) => {
  await ElMessageBox.confirm(`取消收款单 ${row.receiptNo}？会红冲凭证并释放核销。`, '取消收款')
  await api.post(`/finance/receivables/receipts/${row.id}/cancel`)
  ElMessage.success('收款单已取消')
  await refreshAll()
}

// ---------------- 预收核销
const advanceDialog = ref(false)
const advanceTarget = ref<any>(null)
const advanceInvoices = ref<any[]>([])
const advanceSaving = ref(false)

const openAdvance = async (row: any) => {
  advanceTarget.value = row
  const data = await api.get('/finance/receivables/invoices', {
    customerId: row.customerId,
    page: 1,
    size: 100
  })
  advanceInvoices.value = (data.records || [])
    .filter((item: any) => item.invoiceType === 'NORMAL'
      && ['ISSUED', 'PARTIAL'].includes(item.status)
      && item.currency === row.currency
      && Number(item.remaining) > 0)
    .map((item: any) => ({ ...item, applyAmount: 0 }))
  advanceDialog.value = true
}
const submitAdvance = async () => {
  const applies = advanceInvoices.value
    .filter((row: any) => Number(row.applyAmount || 0) > 0)
    .map((row: any) => ({ invoiceId: row.id, amount: Number(row.applyAmount) }))
  if (!applies.length) {
    ElMessage.warning('请填写核销金额')
    return
  }
  advanceSaving.value = true
  try {
    const result = await api.post(`/finance/receivables/receipts/${advanceTarget.value.id}/apply`, { applies })
    ElMessage.success(`核销完成，预收余额 ${fmtMoney(result.remaining)}`)
    advanceDialog.value = false
    await refreshAll()
  } finally {
    advanceSaving.value = false
  }
}

const statusLabel: Record<string, string> = {
  ISSUED: '已开票',
  PARTIAL: '部分收款',
  PAID: '已收清',
  CANCELLED: '已作废',
  POSTED: '已登记'
}

onMounted(async () => {
  await refreshAll()
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2>应收管理</h2>
      <div class="toolbar-actions">
        <el-button v-perm="'finance:invoice'" type="primary" @click="openInvoiceDialog">手工开票</el-button>
        <el-button v-perm="'finance:receipt'" @click="openReceiptDialog">登记收款</el-button>
      </div>
    </div>

    <div class="summary">
      <div class="summary-card">
        <div class="label">应收余额</div>
        <div class="value money">¥{{ fmtMoney(summary.outstanding) }}</div>
      </div>
      <div class="summary-card">
        <div class="label">逾期金额</div>
        <div class="value money danger-text">¥{{ fmtMoney(summary.overdue) }}</div>
      </div>
      <div class="summary-card">
        <div class="label">本月收款</div>
        <div class="value money success-text">¥{{ fmtMoney(summary.receivedThisMonth) }}</div>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="销售发票" name="invoices">
        <div class="toolbar">
          <el-input v-model="invoiceKeyword" placeholder="发票号 / 客户 / 发货单号" clearable style="width: 260px"
                    @keyup.enter="invoicePage = 1; loadInvoices()" />
          <el-select v-model="invoiceStatus" clearable placeholder="状态" style="width: 130px">
            <el-option label="已开票" value="ISSUED" />
            <el-option label="部分收款" value="PARTIAL" />
            <el-option label="已收清" value="PAID" />
            <el-option label="已作废" value="CANCELLED" />
          </el-select>
          <el-button @click="invoicePage = 1; loadInvoices()">查询</el-button>
        </div>
        <el-table v-loading="loading" :data="invoices" size="small">
          <el-table-column prop="invoiceNo" label="发票号" width="150" />
          <el-table-column prop="customerName" label="客户" min-width="160" show-overflow-tooltip />
          <el-table-column prop="invoiceDate" label="开票日期" width="105" />
          <el-table-column prop="dueDate" label="到期日" width="105" />
          <el-table-column label="类型" width="80">
            <template #default="{ row }">
              <el-tag :type="row.invoiceType === 'CREDIT' ? 'warning' : 'info'" size="small">
                {{ row.invoiceType === 'CREDIT' ? '红字' : '蓝字' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="价税合计" width="130" align="right">
            <template #default="{ row }">
              <span class="money">{{ currencySymbol(row.currency) }}{{ fmtMoney(row.totalAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="已收" width="120" align="right">
            <template #default="{ row }">{{ currencySymbol(row.currency) }}{{ fmtMoney(row.receivedAmount) }}</template>
          </el-table-column>
          <el-table-column label="未收" width="120" align="right">
            <template #default="{ row }">{{ currencySymbol(row.currency) }}{{ fmtMoney(row.remaining) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'PAID' ? 'success'
                : row.status === 'CANCELLED' ? 'info' : row.status === 'PARTIAL' ? 'warning' : 'primary'">
                {{ statusLabel[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="shipmentNo" label="来源发货" width="150" />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button v-if="row.status !== 'CANCELLED' && Number(row.receivedAmount) === 0"
                         v-perm="'finance:invoice'" link type="danger" @click="cancelInvoice(row)">作废</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="invoicePage" v-model:page-size="invoiceSize" :total="invoiceTotal"
                       layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 12px"
                       @current-change="loadInvoices" />
      </el-tab-pane>

      <el-tab-pane label="待开票发货单" name="shipments">
        <div class="toolbar">
          <el-input v-model="shipmentKeyword" placeholder="发货单号 / 客户 / 订单号" clearable style="width: 260px"
                    @keyup.enter="shipmentPage = 1; loadShipments()" />
          <el-button @click="shipmentPage = 1; loadShipments()">查询</el-button>
        </div>
        <el-table v-loading="loading" :data="shipments" size="small">
          <el-table-column prop="shipmentNo" label="发货单号" width="170" />
          <el-table-column prop="customerName" label="客户" min-width="160" />
          <el-table-column prop="orderNo" label="销售订单" width="160" />
          <el-table-column prop="currency" label="币种" width="80" />
          <el-table-column prop="shippedAt" label="发货日期" width="110" />
          <el-table-column label="数量" width="90" align="right">
            <template #default="{ row }">{{ fmtQty(row.qty) }}</template>
          </el-table-column>
          <el-table-column label="金额" width="130" align="right">
            <template #default="{ row }">
              <span class="money">{{ currencySymbol(row.currency) }}{{ fmtMoney(row.amount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button v-perm="'finance:invoice'" link type="primary" @click="openShipmentDialog(row)">开票</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="shipmentPage" v-model:page-size="shipmentSize" :total="shipmentTotal"
                       layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 12px"
                       @current-change="loadShipments" />
      </el-tab-pane>

      <el-tab-pane label="收款单" name="receipts">
        <div class="toolbar">
          <el-input v-model="receiptKeyword" placeholder="收款单号 / 客户 / 流水号" clearable style="width: 260px"
                    @keyup.enter="receiptPage = 1; loadReceipts()" />
          <el-button @click="receiptPage = 1; loadReceipts()">查询</el-button>
        </div>
        <el-table v-loading="loading" :data="receipts" size="small">
          <el-table-column prop="receiptNo" label="收款单号" width="160" />
          <el-table-column prop="customerName" label="客户" min-width="160" />
          <el-table-column prop="receiptDate" label="收款日期" width="110" />
          <el-table-column prop="method" label="方式" width="80" />
          <el-table-column label="收款金额" width="130" align="right">
            <template #default="{ row }">
              <span class="money">{{ currencySymbol(row.currency) }}{{ fmtMoney(row.amount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="本位币金额" width="130" align="right">
            <template #default="{ row }">¥{{ fmtMoney(row.baseAmount) }}</template>
          </el-table-column>
          <el-table-column label="未核销（预收）" width="140" align="right">
            <template #default="{ row }">
              <span :class="{ 'success-text': Number(row.unappliedAmount) > 0 }">
                {{ currencySymbol(row.currency) }}{{ fmtMoney(row.unappliedAmount) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="referenceNo" label="流水号" width="140" />
          <el-table-column label="核销发票" width="90" align="center">
            <template #default="{ row }">{{ row.applyCount }} 张</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'CANCELLED' ? 'info' : 'success'">
                {{ row.status === 'CANCELLED' ? '已取消' : '已登记' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'POSTED' && Number(row.unappliedAmount) > 0"
                         v-perm="'finance:receipt'" link type="primary" @click="openAdvance(row)">核销预收</el-button>
              <el-button v-if="row.status === 'POSTED' && !row.appliedLater"
                         v-perm="'finance:receipt'" link type="danger" @click="cancelReceipt(row)">取消</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="receiptPage" v-model:page-size="receiptSize" :total="receiptTotal"
                       layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 12px"
                       @current-change="loadReceipts" />
      </el-tab-pane>

      <el-tab-pane label="账龄分析" name="aging">
        <div class="muted" style="margin-bottom: 8px">按客户和币种统计，金额为本位币（人民币）</div>
        <el-table :data="aging.rows || []" size="small">
          <el-table-column prop="customerName" label="客户" min-width="180" />
          <el-table-column prop="currency" label="币种" width="80" />
          <el-table-column label="未到期" width="120" align="right">
            <template #default="{ row }">¥{{ fmtMoney(row.notDue) }}</template>
          </el-table-column>
          <el-table-column label="逾期 1-30 天" width="130" align="right">
            <template #default="{ row }">¥{{ fmtMoney(row.d1) }}</template>
          </el-table-column>
          <el-table-column label="31-60 天" width="120" align="right">
            <template #default="{ row }">¥{{ fmtMoney(row.d31) }}</template>
          </el-table-column>
          <el-table-column label="61-90 天" width="120" align="right">
            <template #default="{ row }">¥{{ fmtMoney(row.d61) }}</template>
          </el-table-column>
          <el-table-column label="90 天以上" width="120" align="right">
            <template #default="{ row }">¥{{ fmtMoney(row.d90) }}</template>
          </el-table-column>
          <el-table-column label="合计" width="130" align="right">
            <template #default="{ row }"><b class="money">¥{{ fmtMoney(row.total) }}</b></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="invoiceDialog" title="手工开票" width="880px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="客户" required>
            <ReferenceSelect v-model="invoiceForm.customerId" url="/options/customers"
                             placeholder="输入客户编码或名称搜索" />
          </el-form-item>
          <el-form-item label="发票类型">
            <el-select v-model="invoiceForm.invoiceType" style="width: 100%">
              <el-option label="蓝字（正常销售）" value="NORMAL" />
              <el-option label="红字（退货冲减）" value="CREDIT" />
            </el-select>
          </el-form-item>
          <el-form-item label="开票日期" required>
            <el-date-picker v-model="invoiceForm.invoiceDate" type="date" value-format="YYYY-MM-DD"
                            style="width: 100%" />
          </el-form-item>
          <el-form-item label="到期日">
            <el-date-picker v-model="invoiceForm.dueDate" type="date" value-format="YYYY-MM-DD"
                            style="width: 100%" placeholder="默认开票日 + 30 天" />
          </el-form-item>
          <el-form-item label="币种">
            <el-select v-model="invoiceForm.currency" style="width: 100%">
              <el-option label="人民币 CNY" value="CNY" />
              <el-option label="美元 USD" value="USD" />
              <el-option label="欧元 EUR" value="EUR" />
            </el-select>
          </el-form-item>
          <el-form-item label="税率（%）">
            <el-input-number v-model="invoiceForm.taxRatePercent" :min="0" :max="100" :precision="2"
                             style="width: 100%" />
          </el-form-item>
          <el-form-item label="价格口径">
            <el-switch v-model="invoiceForm.priceIncludesTax" active-text="含税价" inactive-text="未税价" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="invoiceForm.remark" />
          </el-form-item>
        </div>

        <el-table :data="invoiceForm.items" size="small">
          <el-table-column label="产品" min-width="220">
            <template #default="{ row }">
              <ReferenceSelect v-model="row.productId" url="/options/products"
                               placeholder="输入物料编码或名称搜索"
                               @select="(product) => onInvoiceProduct(row, product)" />
            </template>
          </el-table-column>
          <el-table-column label="数量" width="130">
            <template #default="{ row }"><el-input-number v-model="row.qty" :min="0.0001" :precision="4" style="width: 100%" /></template>
          </el-table-column>
          <el-table-column :label="invoiceForm.priceIncludesTax ? '含税单价' : '未税单价'" width="140">
            <template #default="{ row }"><el-input-number v-model="row.price" :min="0" :precision="4" style="width: 100%" /></template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="right">
            <template #default="{ row }">
              {{ currencySymbol(invoiceForm.currency) }}{{ fmtMoney(Number(row.qty || 0) * Number(row.price || 0)) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeInvoiceItem($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top: 8px">
          <el-button link type="primary" @click="addInvoiceItem">+ 添加明细</el-button>
        </div>

        <div class="invoice-total">
          <span>未税 {{ currencySymbol(invoiceForm.currency) }}{{ fmtMoney(invoiceTotals.net) }}</span>
          <span>税额 {{ currencySymbol(invoiceForm.currency) }}{{ fmtMoney(invoiceTotals.tax) }}</span>
          <b>价税合计 {{ currencySymbol(invoiceForm.currency) }}{{ fmtMoney(invoiceTotals.total) }}</b>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="invoiceDialog = false">取消</el-button>
        <el-button type="primary" :loading="invoiceSaving" @click="submitInvoice">开票并记账</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="shipmentDialog" title="从发货单开票" width="480px">
      <div v-if="shipmentTarget" class="muted" style="margin-bottom: 12px">
        发货单 {{ shipmentTarget.shipmentNo }} · {{ shipmentTarget.customerName }} ·
        {{ currencySymbol(shipmentTarget.currency) }}{{ fmtMoney(shipmentTarget.amount) }}
      </div>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="税率（%）">
            <el-input-number v-model="shipmentForm.taxRatePercent" :min="0" :max="100" :precision="2"
                             style="width: 100%" />
          </el-form-item>
          <el-form-item label="账期（天）">
            <el-input-number v-model="shipmentForm.creditDays" :min="0" :max="365" style="width: 100%" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="shipmentForm.remark" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="shipmentDialog = false">取消</el-button>
        <el-button type="primary" :loading="invoiceSaving" @click="submitShipmentInvoice">确认开票</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="receiptDialog" title="登记收款" width="760px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="客户" required>
            <ReferenceSelect v-model="receiptForm.customerId" url="/options/customers"
                             placeholder="输入客户编码或名称搜索"
                             @select="loadOpenInvoices" />
          </el-form-item>
          <el-form-item label="收款日期" required>
            <el-date-picker v-model="receiptForm.receiptDate" type="date" value-format="YYYY-MM-DD"
                            style="width: 100%" />
          </el-form-item>
          <el-form-item label="收款金额" required>
            <el-input-number v-model="receiptForm.amount" :min="0" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="币种">
            <el-select v-model="receiptForm.currency" style="width: 100%" disabled>
              <el-option label="人民币 CNY" value="CNY" />
              <el-option label="美元 USD" value="USD" />
              <el-option label="欧元 EUR" value="EUR" />
            </el-select>
          </el-form-item>
          <el-form-item label="收款方式">
            <el-select v-model="receiptForm.method" style="width: 100%">
              <el-option label="银行转账" value="BANK" />
              <el-option label="现金" value="CASH" />
              <el-option label="其他" value="OTHER" />
            </el-select>
          </el-form-item>
          <el-form-item label="银行流水号">
            <el-input v-model="receiptForm.referenceNo" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="receiptForm.remark" />
          </el-form-item>
        </div>

        <div class="drawer-section">
          <h4>核销发票</h4>
          <div v-if="!openInvoices.length" class="muted">该客户没有待收款发票</div>
          <el-table v-else :data="openInvoices" size="small">
            <el-table-column width="50">
              <template #default="{ row }">
                <el-checkbox v-model="row.checked" @change="toggleApply(row)" />
              </template>
            </el-table-column>
            <el-table-column prop="invoiceNo" label="发票号" width="150" />
            <el-table-column prop="invoiceDate" label="开票日期" width="105" />
            <el-table-column label="未收金额" width="120" align="right">
              <template #default="{ row }">
                {{ currencySymbol(row.currency) }}{{ fmtMoney(row.remaining) }}
              </template>
            </el-table-column>
            <el-table-column label="本次核销" width="150">
              <template #default="{ row }">
                <el-input-number v-model="row.applyAmount" :min="0" :max="Number(row.remaining)"
                                 :precision="2" :disabled="!row.checked" style="width: 100%" />
              </template>
            </el-table-column>
          </el-table>
          <div class="invoice-total" style="margin-top: 8px">
            <span>已核销 {{ currencySymbol(receiptForm.currency) }}{{ fmtMoney(receiptApplied) }}</span>
            <b>未核销 {{ currencySymbol(receiptForm.currency) }}{{ fmtMoney(Number(receiptForm.amount || 0) - receiptApplied) }}</b>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="receiptDialog = false">取消</el-button>
        <el-button type="primary" :loading="receiptSaving" @click="submitReceipt">确认收款</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="advanceDialog" title="预收核销" width="700px">
      <div v-if="advanceTarget" class="muted" style="margin-bottom: 10px">
        收款单 {{ advanceTarget.receiptNo }} · 可核销
        {{ currencySymbol(advanceTarget.currency) }}{{ fmtMoney(advanceTarget.unappliedAmount) }}
      </div>
      <el-table :data="advanceInvoices" size="small">
        <el-table-column prop="invoiceNo" label="发票号" width="150" />
        <el-table-column prop="invoiceDate" label="开票日期" width="105" />
        <el-table-column prop="dueDate" label="到期日" width="105" />
        <el-table-column label="未收金额" width="120" align="right">
          <template #default="{ row }">
            {{ currencySymbol(row.currency) }}{{ fmtMoney(row.remaining) }}
          </template>
        </el-table-column>
        <el-table-column label="本次核销" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.applyAmount" :min="0" :max="Number(row.remaining)"
                             :precision="2" style="width: 100%" />
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!advanceInvoices.length" class="muted">该客户没有可核销的发票</div>
      <template #footer>
        <el-button @click="advanceDialog = false">取消</el-button>
        <el-button type="primary" :loading="advanceSaving" @click="submitAdvance">确认核销</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" size="720px" title="发票详情">
      <div v-if="detail">
        <div class="detail-head">
          <div>
            <div class="detail-no mono">{{ detail.invoiceNo }}</div>
            <div class="muted" style="margin-top: 4px">{{ detail.customerName }}</div>
          </div>
          <el-tag :type="detail.status === 'PAID' ? 'success' : detail.status === 'CANCELLED' ? 'info' : 'primary'">
            {{ statusLabel[detail.status] || detail.status }}
          </el-tag>
        </div>
        <div class="detail-grid">
          <div class="detail-item"><div class="label">开票日期</div><div class="value">{{ detail.invoiceDate }}</div></div>
          <div class="detail-item"><div class="label">到期日</div><div class="value">{{ detail.dueDate }}</div></div>
          <div class="detail-item"><div class="label">币种</div><div class="value">{{ detail.currency }}</div></div>
          <div class="detail-item"><div class="label">记账汇率</div><div class="value">{{ detail.exchangeRate }}</div></div>
          <div class="detail-item"><div class="label">税率</div><div class="value">{{ (Number(detail.taxRate) * 100).toFixed(2) }}%</div></div>
          <div class="detail-item"><div class="label">价格口径</div><div class="value">{{ detail.priceIncludesTax ? '含税单价' : '未税单价' }}</div></div>
          <div class="detail-item"><div class="label">未税金额</div><div class="value money">{{ currencySymbol(detail.currency) }}{{ fmtMoney(detail.netAmount) }}</div></div>
          <div class="detail-item"><div class="label">税额</div><div class="value money">{{ currencySymbol(detail.currency) }}{{ fmtMoney(detail.taxAmount) }}</div></div>
          <div class="detail-item"><div class="label">价税合计</div><div class="value money"><b>{{ currencySymbol(detail.currency) }}{{ fmtMoney(detail.totalAmount) }}</b></div></div>
          <div class="detail-item"><div class="label">本位币合计</div><div class="value money">¥{{ fmtMoney(detail.baseTotal) }}</div></div>
          <div class="detail-item"><div class="label">已收款</div><div class="value money">{{ currencySymbol(detail.currency) }}{{ fmtMoney(detail.receivedAmount) }}</div></div>
          <div class="detail-item"><div class="label">未收款</div><div class="value money">{{ currencySymbol(detail.currency) }}{{ fmtMoney(detail.remaining) }}</div></div>
          <div class="detail-item"><div class="label">来源发货单</div><div class="value mono">{{ detail.shipmentNo || '-' }}</div></div>
          <div class="detail-item"><div class="label">记账凭证</div><div class="value mono">{{ detail.voucherNo || '-' }}</div></div>
          <div class="detail-item" style="grid-column: span 3">
            <div class="label">备注</div><div class="value">{{ detail.remark || '-' }}</div>
          </div>
        </div>

        <div class="drawer-section">
          <h4>发票明细</h4>
          <el-table :data="detail.items" size="small">
            <el-table-column prop="productCode" label="编码" width="90" />
            <el-table-column prop="productName" label="产品" min-width="160" />
            <el-table-column label="数量" width="100" align="right">
              <template #default="{ row }">{{ fmtQty(row.qty) }}</template>
            </el-table-column>
            <el-table-column label="单价" width="120" align="right">
              <template #default="{ row }">{{ currencySymbol(detail.currency) }}{{ fmtMoney(row.price) }}</template>
            </el-table-column>
            <el-table-column label="金额" width="130" align="right">
              <template #default="{ row }"><span class="money">{{ currencySymbol(detail.currency) }}{{ fmtMoney(row.amount) }}</span></template>
            </el-table-column>
          </el-table>
        </div>

        <div class="drawer-section">
          <h4>收款核销记录</h4>
          <div v-if="!detail.applies?.length" class="muted">还没有收款记录</div>
          <el-table v-else :data="detail.applies" size="small">
            <el-table-column prop="receiptNo" label="收款单号" width="160" />
            <el-table-column prop="receiptDate" label="收款日期" width="110" />
            <el-table-column label="核销金额" width="130" align="right">
              <template #default="{ row }">{{ currencySymbol(detail.currency) }}{{ fmtMoney(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="本位币" width="130" align="right">
              <template #default="{ row }">¥{{ fmtMoney(row.baseAmount) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}
.summary-card {
  background: #fff;
  border: 1px solid #e4e7ec;
  border-radius: 10px;
  padding: 14px 16px;
}
.summary-card .label {
  color: #667085;
  font-size: 13px;
}
.summary-card .value {
  font-size: 22px;
  font-weight: 600;
  margin-top: 6px;
}
.invoice-total {
  display: flex;
  justify-content: flex-end;
  gap: 18px;
  padding: 10px 0 0;
  color: #475467;
}
.danger-text {
  color: #d92d20;
}
.success-text {
  color: #039855;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}
.form-grid .full {
  grid-column: span 3;
}
</style>
