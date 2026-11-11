<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../api'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import ReferenceSelect from '../components/ReferenceSelect.vue'
import { useTableView } from '../composables/useTableView'

const tab = ref('invoice')

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const status = ref('')
const supplierFilter = ref<any>(null)
const summary = ref<any>({})

const dialog = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive({
  supplierId: null as any,
  supplierLabel: '',
  invoiceNo: '',
  invoiceType: 'VAT_SPECIAL',
  invoiceDate: '',
  taxRate: 13,
  purchaseOrderId: null as any,
  remark: '',
  items: [] as any[]
})

const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const payDialog = ref(false)
const paying = ref(false)
const payTarget = ref<any>(null)
const payForm = reactive({
  payDate: '',
  amount: 0,
  method: 'BANK',
  referenceNo: '',
  remark: ''
})

const accrualLoading = ref(false)
const accrualRows = ref<any[]>([])
const accrualTotal = ref(0)
const accrualPage = ref(1)
const accrualSize = ref(20)
const accrualKeyword = ref('')
const accrualStatus = ref('')
const accrualSupplier = ref<any>(null)

const aging = ref<any>({ buckets: [], overdueInvoices: [], refundableInvoices: [] })
const agingLoading = ref(false)

const creditDialog = ref(false)
const creditSaving = ref(false)
const creditCandidates = ref<any[]>([])
const creditForm = reactive({
  relatedInvoiceId: null as any,
  invoiceNo: '',
  invoiceDate: '',
  remark: '',
  immediate: true,
  items: [] as any[]
})

const refundDialog = ref(false)
const refundSaving = ref(false)
const refundTarget = ref<any>(null)
const refundForm = reactive({
  refundDate: '',
  amount: 0,
  method: 'BANK',
  referenceNo: '',
  remark: ''
})

const reconcile = ref<any>({ records: [], totals: {} })
const reconcileLoading = ref(false)
const reconcileSupplier = ref<any>(null)

const statusMap: Record<string, { label: string; cls: string }> = {
  DRAFT: { label: '待匹配', cls: 'info' },
  MATCHED: { label: '待付款', cls: 'warning' },
  PARTIAL: { label: '部分付款', cls: 'primary' },
  PAID: { label: '已付清', cls: 'success' },
  CANCELLED: { label: '已作废', cls: 'info' },
  RED_REVERSED: { label: '已全额红冲', cls: 'info' }
}

const typeMap: Record<string, string> = {
  VAT_SPECIAL: '增值税专票',
  VAT_NORMAL: '增值税普票',
  RECEIPT: '收据',
  CREDIT: '红字发票'
}

const statusOf = (value: any) =>
  statusMap[String(value || '')] || { label: String(value || '-'), cls: 'info' }

const today = () => new Date().toISOString().slice(0, 10)

// 发票列表视图
const table = useTableView({
  pageKey: 'purchase.payables.invoices',
  columns: [
    { key: 'invoiceNo', label: '发票号', width: 180, fixed: 'left' },
    { key: 'supplier', label: '供应商', width: 180, flex: true, ellipsis: true },
    { key: 'dates', label: '开票 / 到期', width: 150 },
    { key: 'grossAmount', label: '价税合计', width: 120, align: 'right' },
    { key: 'paid', label: '已付 / 未付', width: 130, align: 'right' },
    { key: 'difference', label: '差异', width: 100, align: 'right' },
    { key: 'status', label: '状态', width: 100 },
    { key: 'actions', label: '操作', width: 210, fixed: 'right' }
  ],
  getFilters: () => ({
    keyword: keyword.value,
    status: status.value,
    supplierId: supplierFilter.value
  }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    status.value = filters.status || ''
    supplierFilter.value = filters.supplierId ?? null
  },
  getPageSize: () => size.value,
  setPageSize: (value) => { size.value = value }
})

const reload = () => {
  page.value = 1
  load()
}

// 暂估列表视图
const accrualTable = useTableView({
  pageKey: 'purchase.payables.accruals',
  columns: [
    { key: 'accrualNo', label: '暂估号', width: 180 },
    { key: 'poInbound', label: '采购单 / 入库单', width: 220, flex: true, ellipsis: true },
    { key: 'supplierName', label: '供应商', width: 180, flex: true, ellipsis: true },
    { key: 'accrualDate', label: '暂估日期', width: 110 },
    { key: 'amount', label: '暂估金额', width: 120, align: 'right' },
    { key: 'matched', label: '已冲 / 未冲', width: 130, align: 'right' },
    { key: 'status', label: '状态', width: 100 }
  ],
  getFilters: () => ({
    keyword: accrualKeyword.value,
    supplierId: accrualSupplier.value,
    status: accrualStatus.value
  }),
  setFilters: (filters) => {
    accrualKeyword.value = filters.keyword || ''
    accrualSupplier.value = filters.supplierId ?? null
    accrualStatus.value = filters.status || ''
  },
  getPageSize: () => accrualSize.value,
  setPageSize: (value) => { accrualSize.value = value }
})

const reloadAccruals = () => {
  accrualPage.value = 1
  loadAccruals()
}

const grossAmount = computed(() =>
  form.items
    .filter((item) => item.selected)
    .reduce((sum, item) => sum + Number(item.qty || 0) * Number(item.price || 0), 0)
)

const netAmount = computed(() => {
  const rate = Number(form.taxRate || 0) / 100
  return grossAmount.value / (1 + rate)
})

const taxAmount = computed(() => grossAmount.value - netAmount.value)

const load = async () => {
  loading.value = true
  try {
    const [data, sum] = await Promise.all([
      api.get('/ap/invoices', {
        keyword: keyword.value || undefined,
        status: status.value || undefined,
        supplierId: supplierFilter.value || undefined,
        page: page.value,
        size: size.value
      }),
      api.get('/ap/summary')
    ])
    rows.value = data?.records || []
    total.value = data?.total || 0
    summary.value = sum || {}
  } finally {
    loading.value = false
  }
}

const loadInvoiceable = async () => {
  if (!form.supplierId) {
    form.items = []
    return
  }
  const lines = (await api.get('/ap/invoiceable-lines', { supplierId: form.supplierId })) || []
  form.items = lines.map((line: any) => ({
    ...line,
    selected: true,
    qty: Number(line.invoiceableQty),
    price: Number(line.price)
  }))
  if (!lines.length) {
    ElMessage.warning('该供应商没有已入库未开票的采购行')
  }
}

const openCreate = async () => {
  editingId.value = null
  Object.assign(form, {
    supplierId: null,
    supplierLabel: '',
    invoiceNo: '',
    invoiceType: 'VAT_SPECIAL',
    invoiceDate: today(),
    taxRate: 13,
    purchaseOrderId: null,
    remark: '',
    items: []
  })
  dialog.value = true
}

const openEdit = async (row: any) => {
  const data = await api.get(`/ap/invoices/${row.id}`)
  editingId.value = row.id
  Object.assign(form, {
    supplierId: data.supplierId,
    supplierLabel: data.supplierCode
      ? `${data.supplierCode} · ${data.supplierName}`
      : (data.supplierName || ''),
    invoiceNo: data.invoiceNo,
    invoiceType: data.invoiceType,
    invoiceDate: data.invoiceDate,
    taxRate: Number(data.taxRate),
    purchaseOrderId: data.purchaseOrderId,
    remark: data.remark || '',
    items: (data.items || []).map((item: any) => ({
      purchaseOrderItemId: item.purchaseOrderItemId,
      orderNo: item.orderNo,
      productCode: item.productCode,
      productName: item.productName,
      unit: item.unit,
      invoiceableQty: Number(item.qty),
      qty: Number(item.qty),
      price: Number(item.price),
      selected: true
    }))
  })
  dialog.value = true
}

const submitInvoice = async () => {
  const picked = form.items.filter((item) => item.selected && Number(item.qty) > 0)
  if (!form.supplierId) {
    ElMessage.warning('请选择供应商')
    return
  }
  if (!form.invoiceNo.trim()) {
    ElMessage.warning('请填写发票号')
    return
  }
  if (!picked.length) {
    ElMessage.warning('请至少勾选一条发票明细')
    return
  }
  saving.value = true
  try {
    const payload = {
      invoiceNo: form.invoiceNo.trim(),
      invoiceType: form.invoiceType,
      supplierId: form.supplierId,
      purchaseOrderId: form.purchaseOrderId || undefined,
      invoiceDate: form.invoiceDate || undefined,
      taxRate: Number(form.taxRate || 0),
      remark: form.remark,
      items: picked.map((item) => ({
        purchaseOrderItemId: item.purchaseOrderItemId,
        qty: Number(item.qty),
        price: Number(item.price)
      }))
    }
    if (editingId.value) {
      await api.put(`/ap/invoices/${editingId.value}`, payload)
      ElMessage.success('发票已保存')
    } else {
      await api.post('/ap/invoices', payload)
      ElMessage.success('发票已登记，下一步做三单匹配')
    }
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const matchInvoice = async (row: any) => {
  await ElMessageBox.confirm(
    `对发票 ${row.invoiceNo} 做三单匹配？数量不能超已入库未开票，单价容差 0.5% 或 1 元。`,
    '三单匹配',
    { type: 'warning' }
  )
  try {
    const result = await api.post(`/ap/invoices/${row.id}/match`, { force: false })
    ElMessage.success(`匹配完成，冲暂估 ${result.matched || 0} 条，差异 ${fmtMoney(result.differenceAmount || 0)}`)
  } catch (error: any) {
    if (!String(error?.message || '').includes('差异')) {
      throw error
    }
    await ElMessageBox.confirm(`${error.message}。是否强制匹配？`, '存在差异', { type: 'warning' })
    await api.post(`/ap/invoices/${row.id}/match`, { force: true })
    ElMessage.success('已强制匹配，差异金额已记录在发票上')
  }
  load()
}

const cancelInvoice = async (row: any) => {
  await ElMessageBox.confirm(
    `作废发票 ${row.invoiceNo}？已匹配的会同步回退暂估，已有付款的不能作废。`,
    '作废发票',
    { type: 'warning' }
  )
  await api.post(`/ap/invoices/${row.id}/cancel`)
  ElMessage.success('已作废')
  load()
}

const removeInvoice = async (row: any) => {
  await ElMessageBox.confirm(`删除待匹配发票 ${row.invoiceNo}？`, '删除发票', { type: 'warning' })
  await api.delete(`/ap/invoices/${row.id}`)
  ElMessage.success('已删除')
  load()
}

const openPay = (row: any) => {
  payTarget.value = row
  payForm.payDate = today()
  payForm.amount = Number(row.remainingAmount || 0)
  payForm.method = 'BANK'
  payForm.referenceNo = ''
  payForm.remark = ''
  payDialog.value = true
}

const submitPay = async () => {
  if (!payTarget.value) return
  if (!payForm.amount || Number(payForm.amount) <= 0) {
    ElMessage.warning('付款金额必须大于 0')
    return
  }
  paying.value = true
  try {
    const result = await api.post(`/ap/invoices/${payTarget.value.id}/payments`, {
      payDate: payForm.payDate || undefined,
      amount: Number(payForm.amount),
      method: payForm.method,
      referenceNo: payForm.referenceNo,
      remark: payForm.remark
    })
    ElMessage.success(`付款已登记，发票状态 ${result.status === 'PAID' ? '已付清' : '部分付款'}`)
    payDialog.value = false
    load()
  } finally {
    paying.value = false
  }
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await api.get(`/ap/invoices/${row.id}`)
  } finally {
    detailLoading.value = false
  }
}

const openCredit = async () => {
  creditForm.relatedInvoiceId = null
  creditForm.invoiceNo = 'RC' + Date.now().toString().slice(-8)
  creditForm.invoiceDate = today()
  creditForm.remark = ''
  creditForm.immediate = true
  creditForm.items = []
  creditCandidates.value = (await api.get('/ap/credit-candidates')) || []
  creditDialog.value = true
}

const pickCreditInvoice = () => {
  const blue = creditCandidates.value.find((item) => item.id === creditForm.relatedInvoiceId)
  creditForm.items = ((blue && blue.items) || []).map((item: any) => ({
    ...item,
    invoiceQty: Number(item.qty || 0),
    qty: 0
  }))
}

const creditAmount = computed(() =>
  creditForm.items.reduce((sum: number, item: any) => sum + Number(item.qty || 0) * Number(item.price || 0), 0)
)

const submitCredit = async () => {
  if (!creditForm.relatedInvoiceId) {
    ElMessage.warning('请选择要红冲的蓝字发票')
    return
  }
  const picked = creditForm.items.filter((item: any) => Number(item.qty || 0) > 0)
  if (!picked.length) {
    ElMessage.warning('请至少填写一条红冲数量')
    return
  }
  if (!creditForm.invoiceNo.trim()) {
    ElMessage.warning('请填写红字发票号')
    return
  }
  creditSaving.value = true
  try {
    const id = await api.post('/ap/credit-invoices', {
      invoiceNo: creditForm.invoiceNo.trim(),
      relatedInvoiceId: creditForm.relatedInvoiceId,
      invoiceDate: creditForm.invoiceDate || undefined,
      remark: creditForm.remark,
      lines: picked.map((item: any) => ({ invoiceItemId: item.invoiceItemId, qty: Number(item.qty) }))
    })
    if (creditForm.immediate) {
      const res = await api.post(`/ap/credit-invoices/${id}/match`)
      ElMessage.success(
        `红冲完成，蓝票剩余应付 ${fmtMoney(res.blueGrossAmount)}` +
          (Number(res.refundableAmount) > 0 ? `，待供应商退款 ${fmtMoney(res.refundableAmount)}` : '')
      )
    } else {
      ElMessage.success('红字发票已保存为草稿，确认后点「红冲」')
    }
    creditDialog.value = false
    load()
  } finally {
    creditSaving.value = false
  }
}

const matchCreditInvoice = async (row: any) => {
  await ElMessageBox.confirm(
    `确认红冲 ${row.invoiceNo}？蓝票金额会同步减少，暂估匹配回退，已付款超出部分转为待供应商退款。`,
    '红冲确认',
    { type: 'warning' }
  )
  const res = await api.post(`/ap/credit-invoices/${row.id}/match`)
  ElMessage.success(`红冲完成，蓝票剩余应付 ${fmtMoney(res.blueGrossAmount)}`)
  load()
}

const openRefund = (row: any) => {
  refundTarget.value = row
  refundForm.refundDate = today()
  refundForm.amount = Number(row.refundableAmount || 0)
  refundForm.method = 'BANK'
  refundForm.referenceNo = ''
  refundForm.remark = ''
  refundDialog.value = true
}

const submitRefund = async () => {
  if (!refundTarget.value) return
  if (!refundForm.amount || Number(refundForm.amount) <= 0) {
    ElMessage.warning('退款金额必须大于 0')
    return
  }
  refundSaving.value = true
  try {
    await api.post(`/ap/invoices/${refundTarget.value.id}/refunds`, {
      refundDate: refundForm.refundDate || undefined,
      amount: Number(refundForm.amount),
      method: refundForm.method,
      referenceNo: refundForm.referenceNo,
      remark: refundForm.remark
    })
    ElMessage.success('供应商退款已登记')
    refundDialog.value = false
    load()
  } finally {
    refundSaving.value = false
  }
}

const loadReconcile = async () => {
  reconcileLoading.value = true
  try {
    reconcile.value =
      (await api.get('/ap/reconciliation', { supplierId: reconcileSupplier.value || undefined })) || {
        records: [],
        totals: {}
      }
  } finally {
    reconcileLoading.value = false
  }
}

const loadAccruals = async () => {
  accrualLoading.value = true
  try {
    const data = await api.get('/ap/accruals', {
      keyword: accrualKeyword.value || undefined,
      status: accrualStatus.value || undefined,
      supplierId: accrualSupplier.value || undefined,
      page: accrualPage.value,
      size: accrualSize.value
    })
    accrualRows.value = data?.records || []
    accrualTotal.value = data?.total || 0
  } finally {
    accrualLoading.value = false
  }
}

const loadAging = async () => {
  agingLoading.value = true
  try {
    aging.value = await api.get('/ap/aging')
  } finally {
    agingLoading.value = false
  }
}

const changeTab = async (name: any) => {
  if (name === 'accrual') {
    await loadAccruals()
  } else if (name === 'aging') {
    await loadAging()
  } else if (name === 'reconcile') {
    await loadReconcile()
  } else {
    await load()
  }
}

const reset = () => {
  keyword.value = ''
  status.value = ''
  supplierFilter.value = null
  page.value = 1
  load()
}

onMounted(async () => {
  await table.load()
  await accrualTable.load()
  await load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">发票与应付</h2>
        <p class="page-sub">
          采购入库自动暂估，发票登记后做三单匹配冲暂估，再按账期付款；只看业务台账，不做总账凭证
        </p>
      </div>
      <div class="head-actions">
        <el-button v-perm="'ap:credit'" @click="openCredit">开红字发票</el-button>
        <el-button v-perm="'ap:invoice'" type="primary" @click="openCreate">登记发票</el-button>
      </div>
    </div>

    <div class="ap-strip">
      <div class="ap-item">
        <span>暂估未冲</span>
        <b>{{ fmtMoney(summary.accrualOpen) }}</b>
      </div>
      <div class="ap-item">
        <span>应付余额</span>
        <b class="primary-text">{{ fmtMoney(summary.invoiceOpen) }}</b>
      </div>
      <div class="ap-item">
        <span>逾期金额</span>
        <b class="danger-text">{{ fmtMoney(summary.overdue) }}</b>
      </div>
      <div class="ap-item">
        <span>本月已付</span>
        <b>{{ fmtMoney(summary.monthPaid) }}</b>
      </div>
      <div class="ap-item">
        <span>待匹配发票</span>
        <b>{{ summary.pendingMatch || 0 }}</b>
      </div>
      <div class="ap-item">
        <span>待供应商退款</span>
        <b class="danger-text">{{ fmtMoney(summary.refundable) }}</b>
      </div>
      <div class="ap-item">
        <span>本月红冲</span>
        <b>{{ fmtMoney(summary.monthCredit) }}</b>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="changeTab">
      <el-tab-pane label="发票" name="invoice">
        <div class="panel">
          <div class="filter-bar">
            <el-input
              v-model="keyword"
              placeholder="发票号 / 供应商 / 采购单号"
              clearable
              style="width: 250px"
              @keyup.enter="load"
            />
            <ReferenceSelect v-model="supplierFilter" url="/suppliers/options"
                             placeholder="全部供应商" style="width: 180px" @select="load" />
            <el-select v-model="status" placeholder="全部状态" clearable style="width: 130px" @change="load">
              <el-option label="待匹配" value="DRAFT" />
              <el-option label="待付款" value="MATCHED" />
              <el-option label="部分付款" value="PARTIAL" />
              <el-option label="已付清" value="PAID" />
              <el-option label="已作废" value="CANCELLED" />
            </el-select>
            <el-button type="primary" plain @click="load">查询</el-button>
            <el-button plain @click="reset">重置</el-button>
            <div class="spacer"></div>
            <TableToolbar :table="table" export-api="/ap/invoices" export-name="发票与应付" @applied="reload" />
            <span class="toolbar-tip">共 {{ total }} 张发票</span>
          </div>

          <div class="table-wrap">
            <DataTable :table="table" :data="rows" :loading="loading">
              <template #cell-invoiceNo="{ row }">
                <div class="mono">
                  {{ row.invoiceNo }}
                  <span v-if="row.invoiceType === 'CREDIT'" class="credit-flag">红字</span>
                </div>
                <div class="muted" style="font-size: 11px">
                  <template v-if="row.relatedInvoiceNo">冲 {{ row.relatedInvoiceNo }}</template>
                  <template v-else>{{ typeMap[row.invoiceType] || row.invoiceType }}</template>
                </div>
              </template>
              <template #cell-supplier="{ row }">
                <div>{{ row.supplierName }}</div>
                <div class="muted mono" style="font-size: 11px">{{ row.purchaseOrderNo || '无采购单' }}</div>
              </template>
              <template #cell-dates="{ row }">
                <div>{{ row.invoiceDate }}</div>
                <div class="muted" :class="row.overdue ? 'danger-text' : ''" style="font-size: 11px">
                  到期 {{ row.dueDate || '-' }}
                </div>
              </template>
              <template #cell-grossAmount="{ row }">
                <span class="mono" :class="Number(row.grossAmount) < 0 ? 'danger-text' : ''">
                  {{ fmtMoney(row.grossAmount) }}
                </span>
              </template>
              <template #cell-paid="{ row }">
                <div class="mono">{{ fmtMoney(row.paidAmount) }}</div>
                <div class="muted" style="font-size: 11px">
                  <template v-if="Number(row.refundableAmount) > 0">
                    待退 {{ fmtMoney(row.refundableAmount) }}
                  </template>
                  <template v-else>未付 {{ fmtMoney(row.remainingAmount) }}</template>
                </div>
              </template>
              <template #cell-difference="{ row }">
                <span :class="Number(row.differenceAmount) > 0 ? 'danger-text' : 'muted'">
                  {{ Number(row.differenceAmount) > 0 ? fmtMoney(row.differenceAmount) : '-' }}
                </span>
              </template>
              <template #cell-status="{ row }">
                <span class="status-tag" :class="statusOf(row.status).cls">
                  <i></i>{{ statusOf(row.status).label }}
                </span>
              </template>
              <template #cell-actions="{ row }">
                <el-button link type="primary" @click="openDetail(row)">详情</el-button>
                <el-button
                  v-if="row.status === 'DRAFT' && row.invoiceType !== 'CREDIT'"
                  v-perm="'ap:match'"
                  link
                  type="success"
                  @click="matchInvoice(row)"
                >
                  匹配
                </el-button>
                <el-button
                  v-if="row.status === 'DRAFT' && row.invoiceType === 'CREDIT'"
                  v-perm="'ap:credit'"
                  link
                  type="danger"
                  @click="matchCreditInvoice(row)"
                >
                  红冲
                </el-button>
                <el-button
                  v-if="(row.status === 'MATCHED' || row.status === 'PARTIAL') && row.invoiceType !== 'CREDIT'"
                  v-perm="'ap:payment'"
                  link
                  type="primary"
                  @click="openPay(row)"
                >
                  付款
                </el-button>
                <el-button
                  v-if="Number(row.refundableAmount) > 0"
                  v-perm="'ap:refund'"
                  link
                  type="warning"
                  @click="openRefund(row)"
                >
                  退款
                </el-button>
                <el-button
                  v-if="row.status === 'DRAFT' && row.invoiceType !== 'CREDIT'"
                  v-perm="'ap:invoice'"
                  link
                  @click="openEdit(row)"
                >
                  编辑
                </el-button>
                <el-dropdown
                  v-if="row.status === 'DRAFT' || row.status === 'MATCHED'"
                >
                  <el-button link>更多</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-perm="'ap:invoice'" @click="cancelInvoice(row)">
                        {{ row.invoiceType === 'CREDIT' ? '作废红字发票' : '作废发票' }}
                      </el-dropdown-item>
                      <el-dropdown-item v-if="row.status === 'DRAFT'" v-perm="'ap:invoice'" @click="removeInvoice(row)">
                        删除草稿
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
            </DataTable>
          </div>

          <div class="pager">
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="size"
              :total="total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              @current-change="load"
              @size-change="load"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="暂估" name="accrual">
        <div class="panel">
          <div class="filter-bar">
            <el-input
              v-model="accrualKeyword"
              placeholder="暂估号 / 入库单 / 采购单 / 供应商"
              clearable
              style="width: 270px"
              @keyup.enter="loadAccruals"
            />
            <ReferenceSelect v-model="accrualSupplier" url="/suppliers/options"
                             placeholder="全部供应商" style="width: 180px" @select="loadAccruals" />
            <el-select v-model="accrualStatus" placeholder="全部状态" clearable style="width: 130px" @change="loadAccruals">
              <el-option label="未冲" value="OPEN" />
              <el-option label="部分冲" value="PARTIAL" />
              <el-option label="已冲完" value="MATCHED" />
            </el-select>
            <el-button type="primary" plain @click="loadAccruals">查询</el-button>
            <div class="spacer"></div>
            <TableToolbar :table="accrualTable" export-api="/ap/accruals" export-name="采购暂估" @applied="reloadAccruals" />
            <span class="toolbar-tip">共 {{ accrualTotal }} 条暂估</span>
          </div>

          <div class="table-wrap">
            <DataTable :table="accrualTable" :data="accrualRows" :loading="accrualLoading">
              <template #cell-accrualNo="{ row }"><span class="mono">{{ row.accrualNo }}</span></template>
              <template #cell-poInbound="{ row }">
                <div class="mono">{{ row.purchaseOrderNo }}</div>
                <div class="muted mono" style="font-size: 11px">{{ row.inboundNo }}</div>
              </template>
              <template #cell-accrualDate="{ row }">{{ row.accrualDate }}</template>
              <template #cell-amount="{ row }"><span class="mono">{{ fmtMoney(row.amount) }}</span></template>
              <template #cell-matched="{ row }">
                <div class="mono">{{ fmtMoney(row.matchedAmount) }}</div>
                <div class="muted" style="font-size: 11px">未冲 {{ fmtMoney(row.openAmount) }}</div>
              </template>
              <template #cell-status="{ row }">
                <span class="status-tag" :class="row.status === 'MATCHED' ? 'success' : row.status === 'PARTIAL' ? 'warning' : 'info'">
                  <i></i>{{ row.status === 'MATCHED' ? '已冲完' : row.status === 'PARTIAL' ? '部分冲' : '未冲' }}
                </span>
              </template>
            </DataTable>
          </div>

          <div class="pager">
            <el-pagination
              v-model:current-page="accrualPage"
              v-model:page-size="accrualSize"
              :total="accrualTotal"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              @current-change="loadAccruals"
              @size-change="loadAccruals"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="退货对账" name="reconcile">
        <div v-loading="reconcileLoading">
          <div class="ap-strip">
            <div class="ap-item">
              <span>已开票</span>
              <b>{{ fmtMoney(reconcile.totals && reconcile.totals.invoiced) }}</b>
            </div>
            <div class="ap-item">
              <span>已红冲</span>
              <b class="danger-text">{{ fmtMoney(reconcile.totals && reconcile.totals.credited) }}</b>
            </div>
            <div class="ap-item">
              <span>已退货</span>
              <b>{{ fmtMoney(reconcile.totals && reconcile.totals.returned) }}</b>
            </div>
            <div class="ap-item">
              <span>未开票暂估</span>
              <b>{{ fmtMoney(reconcile.totals && reconcile.totals.openAccrual) }}</b>
            </div>
            <div class="ap-item">
              <span>待供应商退款</span>
              <b class="danger-text">{{ fmtMoney(reconcile.totals && reconcile.totals.refundable) }}</b>
            </div>
          </div>
          <div class="panel">
            <div class="filter-bar">
              <ReferenceSelect
                v-model="reconcileSupplier"
                url="/suppliers/options"
                placeholder="全部供应商"
                style="width: 180px"
                @select="loadReconcile"
              />
              <el-button type="primary" plain @click="loadReconcile">查询</el-button>
              <div class="spacer"></div>
              <span class="toolbar-tip">按入库单维度对账：暂估 / 开票 / 红冲 / 退货</span>
            </div>
            <div class="table-wrap">
              <el-table height="100%" :data="reconcile.records || []" stripe empty-text="暂无暂估数据">
                <el-table-column label="入库单" width="170">
                  <template #default="{ row }"><span class="mono">{{ row.inboundNo }}</span></template>
                </el-table-column>
                <el-table-column label="采购单" width="160">
                  <template #default="{ row }"><span class="mono">{{ row.purchaseOrderNo }}</span></template>
                </el-table-column>
                <el-table-column prop="supplierName" label="供应商" min-width="170" show-overflow-tooltip />
                <el-table-column prop="completedDate" label="入库日期" width="110" />
                <el-table-column label="暂估" width="110" align="right">
                  <template #default="{ row }"><span class="mono">{{ fmtMoney(row.accrualAmount) }}</span></template>
                </el-table-column>
                <el-table-column label="已开票" width="110" align="right">
                  <template #default="{ row }"><span class="mono">{{ fmtMoney(row.invoicedAmount) }}</span></template>
                </el-table-column>
                <el-table-column label="已红冲" width="110" align="right">
                  <template #default="{ row }">
                    <span class="mono" :class="Number(row.creditedAmount) > 0 ? 'danger-text' : 'muted'">
                      {{ fmtMoney(row.creditedAmount) }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="已退货" width="110" align="right">
                  <template #default="{ row }"><span class="mono">{{ fmtMoney(row.returnAmount) }}</span></template>
                </el-table-column>
                <el-table-column label="未开票暂估" width="120" align="right">
                  <template #default="{ row }"><span class="mono">{{ fmtMoney(row.openAccrual) }}</span></template>
                </el-table-column>
                <el-table-column label="红字发票" width="180">
                  <template #default="{ row }">
                    <span v-if="row.redInvoiceNo" class="mono">{{ row.redInvoiceNo }}</span>
                    <span v-else class="muted">-</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="账龄" name="aging">
        <div v-loading="agingLoading">
          <div class="aging-strip">
            <div v-for="bucket in aging.buckets || []" :key="bucket.bucket" class="aging-card">
              <span>{{ bucket.bucket }}</span>
              <b>{{ fmtMoney(bucket.amount) }}</b>
              <em>{{ bucket.count }} 张</em>
            </div>
          </div>
          <div class="panel">
            <div class="table-wrap">
              <el-table height="100%" :data="aging.overdueInvoices || []" stripe empty-text="没有逾期发票">
                <el-table-column prop="invoiceNo" label="发票号" width="180">
                  <template #default="{ row }"><span class="mono">{{ row.invoiceNo }}</span></template>
                </el-table-column>
                <el-table-column prop="supplierName" label="供应商" min-width="200" show-overflow-tooltip />
                <el-table-column prop="dueDate" label="到期日" width="120" />
                <el-table-column label="逾期天数" width="110" align="right">
                  <template #default="{ row }"><span class="danger-text">{{ row.overdueDays }} 天</span></template>
                </el-table-column>
                <el-table-column label="未付金额" width="140" align="right">
                  <template #default="{ row }"><span class="mono">{{ fmtMoney(row.remainingAmount) }}</span></template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog draggable append-to-body v-model="dialog" :title="editingId ? '编辑发票' : '登记发票'" width="940px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="供应商" required>
            <ReferenceSelect
              v-model="form.supplierId"
              url="/suppliers/options"
              :initial-label="form.supplierLabel"
              :disabled="editingId !== null"
              placeholder="输入供应商编码或名称搜索"
              @select="loadInvoiceable"
            />
          </el-form-item>
          <el-form-item label="发票号" required>
            <el-input v-model="form.invoiceNo" placeholder="供应商开票号码" />
          </el-form-item>
          <el-form-item label="发票类型">
            <el-select v-model="form.invoiceType" style="width: 100%">
              <el-option label="增值税专票" value="VAT_SPECIAL" />
              <el-option label="增值税普票" value="VAT_NORMAL" />
              <el-option label="收据" value="RECEIPT" />
            </el-select>
          </el-form-item>
          <el-form-item label="开票日期">
            <el-date-picker v-model="form.invoiceDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="税率（%）">
            <el-input-number v-model="form.taxRate" :min="0" :max="100" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.remark" placeholder="如：含运费，票到付款" />
          </el-form-item>
        </div>

        <div class="section-title">发票明细（只列已入库未开票）</div>
        <el-table :data="form.items" size="small" empty-text="选择供应商后带出可开票的采购行">
          <el-table-column width="50">
            <template #default="{ row }"><el-checkbox v-model="row.selected" /></template>
          </el-table-column>
          <el-table-column label="采购单" width="150">
            <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          </el-table-column>
          <el-table-column label="物料" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <div>{{ row.productName }}</div>
              <div class="muted mono" style="font-size: 11px">{{ row.productCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="可开票" width="100" align="right">
            <template #default="{ row }">{{ fmtQty(row.invoiceableQty) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="发票数量" width="120">
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="0" :max="row.invoiceableQty" :precision="2" :controls="false" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="发票单价" width="120">
            <template #default="{ row }">
              <el-input-number v-model="row.price" :min="0" :precision="2" :controls="false" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">
              <span class="mono">{{ fmtMoney(Number(row.qty || 0) * Number(row.price || 0)) }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="invoice-total">
          <span>价税合计 <b class="mono">{{ fmtMoney(grossAmount) }}</b></span>
          <span>不含税 <b class="mono">{{ fmtMoney(netAmount) }}</b></span>
          <span>税额 <b class="mono">{{ fmtMoney(taxAmount) }}</b></span>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitInvoice">保存发票</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="payDialog" title="付款登记" width="560px">
      <div v-if="payTarget" class="pay-head">
        <b class="mono">{{ payTarget.invoiceNo }}</b>
        <span class="muted">{{ payTarget.supplierName }} · 未付 {{ fmtMoney(payTarget.remainingAmount) }}</span>
      </div>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="付款日期">
            <el-date-picker v-model="payForm.payDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="付款金额">
            <el-input-number v-model="payForm.amount" :min="0" :max="Number(payTarget?.remainingAmount || 0)" :precision="2" :controls="false" style="width: 100%" />
          </el-form-item>
          <el-form-item label="付款方式">
            <el-select v-model="payForm.method" style="width: 100%">
              <el-option label="银行转账" value="BANK" />
              <el-option label="现金" value="CASH" />
              <el-option label="其他" value="OTHER" />
            </el-select>
          </el-form-item>
          <el-form-item label="流水号">
            <el-input v-model="payForm.referenceNo" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="payForm.remark" type="textarea" :rows="2" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="payDialog = false">取消</el-button>
        <el-button type="primary" :loading="paying" @click="submitPay">确认付款</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="creditDialog" title="开红字发票" width="900px">
      <div class="invoice-total" style="justify-content: flex-start; border: none; padding: 0 0 10px">
        红字发票挂在蓝字发票上，按蓝票行填退货或折让数量，单价沿用蓝票；提交后蓝票金额减少、暂估匹配回退，
        已付款超出部分转为待供应商退款。
      </div>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="蓝字发票" required class="full">
            <el-select
              v-model="creditForm.relatedInvoiceId"
              filterable
              placeholder="只列出已匹配的蓝字发票"
              style="width: 100%"
              @change="pickCreditInvoice"
            >
              <el-option
                v-for="item in creditCandidates"
                :key="item.id"
                :label="`${item.invoiceNo} · ${item.supplierName} · ${fmtMoney(item.grossAmount)}`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="红字发票号" required>
            <el-input v-model="creditForm.invoiceNo" placeholder="红字发票号码" />
          </el-form-item>
          <el-form-item label="开票日期">
            <el-date-picker
              v-model="creditForm.invoiceDate"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="creditForm.remark" placeholder="如：来料不合格折让" />
          </el-form-item>
        </div>

        <div class="section-title">红冲明细</div>
        <el-table :data="creditForm.items" size="small" empty-text="选择蓝票后带出可红冲明细">
          <el-table-column prop="productCode" label="编码" width="90" />
          <el-table-column prop="productName" label="物料" min-width="150" />
          <el-table-column label="开票数量" width="110" align="right">
            <template #default="{ row }">{{ fmtQty(row.invoiceQty) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="可红冲" width="100" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.creditableQty) }}</span></template>
          </el-table-column>
          <el-table-column label="本次红冲" width="130">
            <template #default="{ row }">
              <el-input-number
                v-model="row.qty"
                :min="0"
                :max="Number(row.creditableQty || 0)"
                :precision="2"
                size="small"
                style="width: 100%"
              />
            </template>
          </el-table-column>
          <el-table-column label="单价" width="100" align="right">
            <template #default="{ row }">¥{{ fmtMoney(row.price) }}</template>
          </el-table-column>
          <el-table-column label="红冲金额" width="110" align="right">
            <template #default="{ row }">
              <span class="mono danger-text">-{{ fmtMoney(Number(row.qty || 0) * Number(row.price || 0)) }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="invoice-total">
          <span>红冲合计 <b class="mono danger-text">-{{ fmtMoney(creditAmount) }}</b></span>
          <el-checkbox v-model="creditForm.immediate">保存后立即红冲</el-checkbox>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="creditDialog = false">取消</el-button>
        <el-button type="primary" :loading="creditSaving" @click="submitCredit">
          {{ creditForm.immediate ? '保存并红冲' : '保存草稿' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="refundDialog" title="登记供应商退款" width="560px">
      <div v-if="refundTarget" class="pay-head">
        <b class="mono">{{ refundTarget.invoiceNo }}</b>
        <span class="muted">
          {{ refundTarget.supplierName }} · 待供应商退款 {{ fmtMoney(refundTarget.refundableAmount) }}
        </span>
      </div>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="退款日期">
            <el-date-picker
              v-model="refundForm.refundDate"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="退款金额">
            <el-input-number
              v-model="refundForm.amount"
              :min="0"
              :max="Number(refundTarget?.refundableAmount || 0)"
              :precision="2"
              :controls="false"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="退款方式">
            <el-select v-model="refundForm.method" style="width: 100%">
              <el-option label="银行转账" value="BANK" />
              <el-option label="现金" value="CASH" />
              <el-option label="其他" value="OTHER" />
            </el-select>
          </el-form-item>
          <el-form-item label="流水号">
            <el-input v-model="refundForm.referenceNo" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="refundForm.remark" type="textarea" :rows="2" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="refundDialog = false">取消</el-button>
        <el-button type="primary" :loading="refundSaving" @click="submitRefund">确认退款</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" size="900px" title="发票详情" append-to-body>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <span class="mono strong">{{ detail.invoiceNo }}</span>
            <span class="status-tag" :class="statusOf(detail.status).cls">
              <i></i>{{ statusOf(detail.status).label }}
            </span>
            <span class="muted">{{ detail.supplierName }} · {{ typeMap[detail.invoiceType] || detail.invoiceType }}</span>
            <span v-if="detail.purchaseOrderNo" class="muted mono">采购单 {{ detail.purchaseOrderNo }}</span>
          </div>

          <div class="detail-grid">
            <div><span>开票日期</span><b>{{ detail.invoiceDate }}</b></div>
            <div><span>到期日</span><b>{{ detail.dueDate || '-' }}（账期 {{ detail.creditDays }} 天）</b></div>
            <div><span>价税合计</span><b class="mono">{{ fmtMoney(detail.grossAmount) }}</b></div>
            <div><span>不含税 / 税额</span><b class="mono">{{ fmtMoney(detail.netAmount) }} / {{ fmtMoney(detail.taxAmount) }}</b></div>
            <div><span>已付 / 未付</span><b class="mono">{{ fmtMoney(detail.paidAmount) }} / {{ fmtMoney(detail.remainingAmount) }}</b></div>
            <div v-if="Number(detail.refundableAmount) > 0">
              <span>待供应商退款</span><b class="mono danger-text">{{ fmtMoney(detail.refundableAmount) }}</b>
            </div>
            <div v-if="detail.relatedInvoiceNo">
              <span>红冲蓝票</span><b class="mono">{{ detail.relatedInvoiceNo }}</b>
            </div>
            <div><span>匹配人</span><b>{{ detail.matchedBy || '-' }}</b></div>
          </div>

          <p v-if="detail.differenceNote" class="detail-reject">匹配差异：{{ detail.differenceNote }}</p>

          <div class="section-title">发票明细</div>
          <el-table :data="detail.items || []" size="small">
            <el-table-column label="采购单" width="150">
              <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
            </el-table-column>
            <el-table-column label="物料" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ row.productName }}</div>
                <div class="muted mono" style="font-size: 11px">{{ row.productCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="100" align="right">
              <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column label="单价" width="100" align="right">
              <template #default="{ row }"><span class="mono">{{ fmtMoney(row.price) }}</span></template>
            </el-table-column>
            <el-table-column label="金额" width="110" align="right">
              <template #default="{ row }"><span class="mono">{{ fmtMoney(row.amount) }}</span></template>
            </el-table-column>
            <el-table-column label="已匹配" width="100" align="right">
              <template #default="{ row }"><span class="mono">{{ fmtQty(row.matchedQty) }}</span></template>
            </el-table-column>
          </el-table>

          <div class="section-title">暂估匹配</div>
          <el-table :data="detail.matches || []" size="small" empty-text="还没有匹配记录">
            <el-table-column prop="accrualNo" label="暂估号" width="200">
              <template #default="{ row }"><span class="mono">{{ row.accrualNo }}</span></template>
            </el-table-column>
            <el-table-column label="匹配数量" width="110" align="right">
              <template #default="{ row }">{{ fmtQty(row.qty) }}</template>
            </el-table-column>
            <el-table-column label="冲暂估金额" width="140" align="right">
              <template #default="{ row }"><span class="mono">{{ fmtMoney(row.amount) }}</span></template>
            </el-table-column>
            <el-table-column prop="createdAt" label="匹配时间" width="160" />
          </el-table>

          <div class="section-title">付款记录</div>
          <el-table :data="detail.payments || []" size="small" empty-text="还没有付款">
            <el-table-column prop="paymentNo" label="付款单号" width="160">
              <template #default="{ row }"><span class="mono">{{ row.paymentNo }}</span></template>
            </el-table-column>
            <el-table-column prop="payDate" label="付款日期" width="110" />
            <el-table-column label="金额" width="120" align="right">
              <template #default="{ row }"><span class="mono">{{ fmtMoney(row.amount) }}</span></template>
            </el-table-column>
            <el-table-column label="方式" width="90">
              <template #default="{ row }">
                {{ row.method === 'BANK' ? '银行' : row.method === 'CASH' ? '现金' : '其他' }}
              </template>
            </el-table-column>
            <el-table-column prop="referenceNo" label="流水号" width="150" />
            <el-table-column prop="createdBy" label="登记人" width="110" />
          </el-table>

          <template v-if="(detail.creditInvoices || []).length">
            <div class="section-title">红字发票</div>
            <el-table :data="detail.creditInvoices" size="small">
              <el-table-column prop="invoiceNo" label="红字发票号" width="190">
                <template #default="{ row }"><span class="mono">{{ row.invoiceNo }}</span></template>
              </el-table-column>
              <el-table-column prop="invoiceDate" label="开票日期" width="120" />
              <el-table-column label="红冲金额" width="130" align="right">
                <template #default="{ row }">
                  <span class="mono danger-text">{{ fmtMoney(row.grossAmount) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <span class="status-tag" :class="statusOf(row.status).cls">
                    <i></i>{{ statusOf(row.status).label }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <template v-if="(detail.refunds || []).length">
            <div class="section-title">供应商退款</div>
            <el-table :data="detail.refunds" size="small">
              <el-table-column prop="refundNo" label="退款单号" width="170">
                <template #default="{ row }"><span class="mono">{{ row.refundNo }}</span></template>
              </el-table-column>
              <el-table-column prop="refundDate" label="退款日期" width="120" />
              <el-table-column label="金额" width="120" align="right">
                <template #default="{ row }"><span class="mono">{{ fmtMoney(row.amount) }}</span></template>
              </el-table-column>
              <el-table-column prop="referenceNo" label="流水号" width="150" />
              <el-table-column prop="createdBy" label="登记人" width="110" />
            </el-table>
          </template>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.credit-flag {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 8px;
  background: #fff1f0;
  color: #cf1322;
  font-size: 10px;
  line-height: 16px;
}

.ap-strip {
  display: flex;
  align-items: flex-start;
  gap: 38px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 15px 20px;
  margin-bottom: 14px;
  box-shadow: var(--shadow);
  flex-wrap: wrap;
}

.ap-item span {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.ap-item b {
  display: block;
  margin-top: 6px;
  font-size: 20px;
  font-family: Bahnschrift, monospace;
}

.primary-text {
  color: #2563eb;
}

.danger-text {
  color: #b91c1c;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 4px 2px;
}

.section-title {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}

.invoice-total {
  display: flex;
  justify-content: flex-end;
  gap: 28px;
  margin-top: 12px;
  font-size: 13px;
  color: var(--ink-3);
}

.pay-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px 20px;
  padding: 12px 14px;
  background: #f8fafc;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.detail-grid span {
  display: block;
  font-size: 12px;
  color: var(--muted);
}

.detail-grid b {
  display: block;
  margin-top: 4px;
  font-size: 13px;
  font-weight: 500;
}

.detail-reject {
  margin: 12px 0 0;
  font-size: 12px;
  color: #b91c1c;
  line-height: 1.7;
}

.aging-strip {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}

.aging-card {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px 16px;
  box-shadow: var(--shadow);
}

.aging-card span {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.aging-card b {
  display: block;
  margin-top: 6px;
  font-size: 18px;
  font-family: Bahnschrift, monospace;
}

.aging-card em {
  display: block;
  margin-top: 4px;
  font-style: normal;
  font-size: 11px;
  color: var(--muted);
}
</style>
