<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../api'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import Attachments from '../components/Attachments.vue'
import ReferenceSelect from '../components/ReferenceSelect.vue'
import { useTableView } from '../composables/useTableView'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const isAdmin = computed(() => auth.user?.role === 'ADMIN')
const currentUsername = computed(() => auth.user?.username)

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const status = ref('')

const table = useTableView({
  pageKey: 'sales.quotes',
  columns: [
    { key: 'quoteNo', label: '报价单号', width: 165, fixed: 'left' as const },
    { key: 'customerName', label: '客户', width: 170, flex: true, ellipsis: true },
    { key: 'contact', label: '联系人', width: 90 },
    { key: 'deliveryDate', label: '期望交期', width: 110 },
    { key: 'validUntil', label: '有效期至', width: 110 },
    { key: 'qty', label: '数量', width: 90, align: 'right' as const },
    { key: 'amount', label: '报价金额', width: 120, align: 'right' as const },
    { key: 'status', label: '状态', width: 100 },
    { key: 'createdBy', label: '报价员', width: 90 },
    { key: 'actions', label: '操作', width: 240, fixed: 'right' as const }
  ],
  getFilters: () => ({ keyword: keyword.value, status: status.value }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    status.value = filters.status || ''
  },
  getPageSize: () => size.value,
  setPageSize: (value) => {
    size.value = value
  }
})

const reload = () => {
  page.value = 1
  load()
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/sales-quotes', {
      keyword: keyword.value,
      status: status.value,
      page: page.value,
      size: size.value
    })
    rows.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

const statusText: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待评审',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  CONVERTED: '已转订单',
  CANCELLED: '已取消'
}
const statusType: Record<string, string> = {
  DRAFT: 'info',
  SUBMITTED: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  CONVERTED: 'success',
  CANCELLED: 'info'
}

// ---------------------------------------------------------------- 新建 / 编辑
const drawer = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive<any>({
  customerId: null,
  contact: '',
  validUntil: '',
  deliveryDate: '',
  remark: '',
  currency: 'CNY',
  taxRatePercent: 13,
  priceIncludesTax: true,
  paymentTerms: '',
  items: []
})

const enteredTotal = computed(() =>
  form.items.reduce((sum: number, item: any) => sum + Number(item.qty || 0) * Number(item.price || 0), 0)
)

/** 金额口径：含税录入时价税合计=明细合计；未税录入时价税合计=未税+税额 */
const totals = computed(() => {
  const rate = Number(form.taxRatePercent || 0) / 100
  const entered = enteredTotal.value
  if (form.priceIncludesTax) {
    const net = rate === 0 ? entered : entered / (1 + rate)
    return { net, tax: entered - net, total: entered }
  }
  const tax = entered * rate
  return { net: entered, tax, total: entered + tax }
})

const currencySymbol = (code: string) => {
  if (code === 'USD') return '$'
  if (code === 'EUR') return '€'
  return '¥'
}

const resetForm = () => {
  Object.assign(form, {
    customerId: null,
    customerLabel: '',
    contact: '',
    validUntil: '',
    deliveryDate: '',
    remark: '',
    currency: 'CNY',
    taxRatePercent: 13,
    priceIncludesTax: true,
    paymentTerms: '',
    items: [{ productId: null, qty: 1, price: 0, remark: '', productName: '', spec: '', unit: '' }]
  })
}

const openCreate = () => {
  editingId.value = null
  resetForm()
  drawer.value = true
}

const openEdit = async (row: any) => {
  const data = await api.get(`/sales-quotes/${row.id}`)
  editingId.value = row.id
  Object.assign(form, {
    customerId: data.customerId,
    customerLabel: data.customerName || '',
    contact: data.contact || '',
    validUntil: data.validUntil || '',
    deliveryDate: data.deliveryDate || '',
    remark: data.remark || '',
    currency: data.currency || 'CNY',
    taxRatePercent: Number(data.taxRate || 0.13) * 100,
    priceIncludesTax: data.priceIncludesTax !== false,
    paymentTerms: data.paymentTerms || '',
    items: (data.items || []).map((item: any) => ({
      productId: item.productId,
      qty: Number(item.qty),
      price: Number(item.price),
      remark: item.remark || '',
      productCode: item.productCode,
      productName: item.productName,
      spec: item.spec,
      unit: item.unit
    }))
  })
  drawer.value = true
}

const addItem = () => {
  form.items.push({ productId: null, qty: 1, price: 0, remark: '', productName: '', spec: '', unit: '' })
}

const removeItem = (index: number) => {
  form.items.splice(index, 1)
}

const onProductSelect = (item: any, product: any) => {
  if (product) {
    item.productName = product.name
    item.spec = product.spec
    item.unit = product.unit
    item.price = Number(product.price)
  }
}

const submit = async () => {
  if (!form.customerId) {
    ElMessage.warning('请选择客户')
    return
  }
  if (!form.items.length || form.items.some((item: any) => !item.productId || !item.qty || item.qty <= 0)) {
    ElMessage.warning('请完整填写报价明细')
    return
  }
  saving.value = true
  try {
    const payload = {
      customerId: form.customerId,
      contact: form.contact || null,
      validUntil: form.validUntil || null,
      deliveryDate: form.deliveryDate || null,
      remark: form.remark || null,
      currency: form.currency,
      taxRate: Number(form.taxRatePercent || 0) / 100,
      priceIncludesTax: form.priceIncludesTax,
      paymentTerms: form.paymentTerms || null,
      items: form.items.map((item: any) => ({
        productId: item.productId,
        qty: item.qty,
        price: item.price,
        remark: item.remark || null
      }))
    }
    if (editingId.value) {
      await api.put(`/sales-quotes/${editingId.value}`, payload)
    } else {
      await api.post('/sales-quotes', payload)
    }
    ElMessage.success('报价单已保存')
    drawer.value = false
    load()
  } finally {
    saving.value = false
  }
}

// ---------------------------------------------------------------- 状态操作
const submitDialog = ref(false)
const submitTarget = reactive<any>({ id: 0, quoteNo: '' })
const reviewerOptions = ref<any[]>([])
const reviewerLoading = ref(false)
const reviewerCache = ref(new Map<number, any>())
const selectedReviewers = ref<number[]>([])
const submitting = ref(false)

const searchReviewers = async (keyword = '') => {
  reviewerLoading.value = true
  try {
    const rows = await api.get('/sales-quotes/reviewers', {
      keyword: keyword || undefined,
      limit: 20
    })
    const merged = [...(rows || [])]
    for (const item of reviewerCache.value.values()) {
      if (!merged.some((row: any) => row.id === item.id)) merged.push(item)
    }
    reviewerOptions.value = merged
  } finally {
    reviewerLoading.value = false
  }
}

/** 记住已选评审人，远程换关键字后标签不丢 */
const rememberReviewers = () => {
  const ids = [addForm.reviewerId, transferForm.toId, ...selectedReviewers.value].filter(Boolean)
  for (const id of ids) {
    const found = reviewerOptions.value.find((item: any) => item.id === id)
    if (found) reviewerCache.value.set(Number(id), found)
  }
}

const openSubmit = async (row: any) => {
  submitTarget.id = row.id
  submitTarget.quoteNo = row.quoteNo
  selectedReviewers.value = []
  await searchReviewers('')
  submitDialog.value = true
}

const confirmSubmit = async () => {
  if (!selectedReviewers.value.length) {
    ElMessage.warning('请至少指定一名评审人')
    return
  }
  submitting.value = true
  try {
    await api.post(`/sales-quotes/${submitTarget.id}/submit`, {
      reviewerIds: selectedReviewers.value
    })
    ElMessage.success('已提交评审')
    submitDialog.value = false
    load()
  } finally {
    submitting.value = false
  }
}

const reviewDialog = ref(false)
const reviewForm = reactive({ id: 0, quoteNo: '', result: 'PASS', comment: '', reviewerId: null as number | null, reviewerName: '' })
const reviewing = ref(false)

const openReview = (row: any, reviewer: any = null) => {
  reviewForm.id = row.id
  reviewForm.quoteNo = row.quoteNo || row.quoteNo
  reviewForm.result = 'PASS'
  reviewForm.comment = ''
  reviewForm.reviewerId = reviewer?.reviewerId || null
  reviewForm.reviewerName = reviewer?.reviewerName || reviewer?.username || ''
  reviewDialog.value = true
}

const submitReviewResult = async () => {
  if (reviewForm.result === 'REJECT' && !reviewForm.comment.trim()) {
    ElMessage.warning('驳回时请填写评审意见')
    return
  }
  reviewing.value = true
  try {
    await api.post(`/sales-quotes/${reviewForm.id}/review`, {
      result: reviewForm.result,
      comment: reviewForm.comment.trim() || null,
      reviewerId: reviewForm.reviewerId
    })
    ElMessage.success(reviewForm.result === 'PASS' ? '评审已通过' : '已驳回')
    reviewDialog.value = false
    load()
    refreshDetail()
  } finally {
    reviewing.value = false
  }
}

const convert = async (row: any) => {
  await ElMessageBox.confirm(
    `报价单 ${row.quoteNo} 将转为销售订单，转单后报价单锁定。确认继续？`,
    '转销售订单'
  )
  const orderId = await api.post(`/sales-quotes/${row.id}/convert`)
  ElMessage.success(`已转销售订单（ID ${orderId}）`)
  load()
}

const cancelQuote = async (row: any) => {
  await ElMessageBox.confirm(`确认取消报价单 ${row.quoteNo}？`, '取消报价', { type: 'warning' })
  await api.post(`/sales-quotes/${row.id}/cancel`)
  ElMessage.success('报价单已取消')
  load()
}

const removeQuote = async (row: any) => {
  await ElMessageBox.confirm(`确认删除草稿报价单 ${row.quoteNo}？`, '删除确认', { type: 'warning' })
  await api.delete(`/sales-quotes/${row.id}`)
  ElMessage.success('已删除')
  load()
}

// ---------------------------------------------------------------- 详情
const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await api.get(`/sales-quotes/${row.id}`)
  } finally {
    detailLoading.value = false
  }
}

const refreshDetail = async () => {
  if (detailDrawer.value && detail.value?.id) {
    detail.value = await api.get(`/sales-quotes/${detail.value.id}`)
  }
}

const ensureReviewers = async () => {
  await searchReviewers('')
}

const availableReviewers = computed(() => {
  const used = new Set((detail.value?.reviewers || []).map((item: any) => item.reviewerId))
  return reviewerOptions.value.filter((item: any) => !used.has(item.id))
})

// 加签
const addDialog = ref(false)
const addForm = reactive({ quoteId: 0, reviewerId: null as number | null })
const openAdd = async () => {
  await ensureReviewers()
  addForm.quoteId = detail.value.id
  addForm.reviewerId = null
  addDialog.value = true
}
const confirmAdd = async () => {
  if (!addForm.reviewerId) {
    ElMessage.warning('请选择评审人')
    return
  }
  await api.post(`/sales-quotes/${addForm.quoteId}/reviewers`, { reviewerId: addForm.reviewerId })
  ElMessage.success('已加签')
  addDialog.value = false
  load()
  refreshDetail()
}

// 转办
const transferDialog = ref(false)
const transferForm = reactive({
  quoteId: 0,
  fromId: 0,
  fromName: '',
  toId: null as number | null
})
const openTransfer = async (reviewer: any) => {
  await ensureReviewers()
  transferForm.quoteId = detail.value.id
  transferForm.fromId = reviewer.reviewerId
  transferForm.fromName = reviewer.reviewerName || reviewer.username
  transferForm.toId = null
  transferDialog.value = true
}
const confirmTransfer = async () => {
  if (!transferForm.toId) {
    ElMessage.warning('请选择转办给谁')
    return
  }
  await api.post(
    `/sales-quotes/${transferForm.quoteId}/reviewers/${transferForm.fromId}/transfer`,
    { toReviewerId: transferForm.toId }
  )
  ElMessage.success('已转办')
  transferDialog.value = false
  load()
  refreshDetail()
}

// 撤签
const removeReviewer = async (reviewer: any) => {
  await ElMessageBox.confirm(
    `确认撤回 ${reviewer.reviewerName || reviewer.username} 的评审任务？`,
    '撤签',
    { type: 'warning' }
  )
  await api.delete(`/sales-quotes/${detail.value.id}/reviewers/${reviewer.reviewerId}`)
  ElMessage.success('已撤签')
  load()
  refreshDetail()
}

/** 生成可打印的报价单，避免把整个后台布局打进纸张 */
const printQuote = () => {
  const data = detail.value
  if (!data) return
  const symbol = currencySymbol(data.currency)
  const escape = (value: any) =>
    String(value ?? '').replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    } as Record<string, string>)[ch])
  const itemRows = (data.items || []).map((item: any) => `
    <tr>
      <td>${escape(item.productCode)}</td>
      <td>${escape(item.productName)}</td>
      <td>${escape(item.spec || '-')}</td>
      <td class="num">${escape(fmtQty(item.qty))} ${escape(item.unit || '')}</td>
      <td class="num">${symbol}${escape(fmtMoney(item.price))}</td>
      <td class="num">${symbol}${escape(fmtMoney(item.amount))}</td>
    </tr>`).join('')
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>报价单 ${escape(data.quoteNo)}</title>
    <style>
      body{font-family:"Microsoft YaHei",Arial,sans-serif;color:#111;margin:32px;font-size:13px}
      h1{font-size:22px;text-align:center;margin:0 0 6px}
      .sub{text-align:center;color:#555;margin-bottom:20px}
      .meta{display:grid;grid-template-columns:repeat(3,1fr);gap:6px 18px;margin-bottom:16px}
      .meta span{color:#666}
      table{width:100%;border-collapse:collapse;margin:12px 0}
      th,td{border:1px solid #bbb;padding:6px 8px;text-align:left}
      th{background:#f2f2f2}
      .num{text-align:right}
      .totals{width:320px;margin-left:auto;margin-top:10px}
      .totals div{display:flex;justify-content:space-between;padding:4px 0}
      .totals .grand{font-weight:bold;border-top:1px solid #333;margin-top:4px;padding-top:6px}
      .blocks{margin-top:16px;line-height:1.8}
      .sign{display:flex;justify-content:space-between;margin-top:48px}
    </style></head><body>
    <h1>报价单</h1>
    <div class="sub">${escape(data.quoteNo)}　${escape(data.createdAt || '')}</div>
    <div class="meta">
      <div><span>客户：</span>${escape(data.customerName || '-')}</div>
      <div><span>联系人：</span>${escape(data.contact || '-')}</div>
      <div><span>报价员：</span>${escape(data.createdBy || '-')}</div>
      <div><span>期望交期：</span>${escape(data.deliveryDate || '-')}</div>
      <div><span>有效期至：</span>${escape(data.validUntil || '-')}</div>
      <div><span>币种：</span>${escape(data.currency || 'CNY')}</div>
    </div>
    <table>
      <thead><tr><th>编码</th><th>产品</th><th>规格</th><th class="num">数量</th><th class="num">单价</th><th class="num">金额</th></tr></thead>
      <tbody>${itemRows}</tbody>
    </table>
    <div class="totals">
      <div><span>未税金额</span><span>${symbol}${escape(fmtMoney(data.netAmount))}</span></div>
      <div><span>税率</span><span>${escape((Number(data.taxRate || 0) * 100).toFixed(2))}%</span></div>
      <div><span>税额</span><span>${symbol}${escape(fmtMoney(data.taxAmount))}</span></div>
      <div class="grand"><span>价税合计</span><span>${symbol}${escape(fmtMoney(data.totalAmount))}</span></div>
    </div>
    <div class="blocks">
      <div><b>付款条件：</b>${escape(data.paymentTerms || '-')}</div>
      <div><b>价格口径：</b>${data.priceIncludesTax ? '单价含税' : '单价未税'}</div>
      <div><b>备注：</b>${escape(data.remark || '-')}</div>
    </div>
    <div class="sign"><div>客户确认：____________________</div><div>报价方：____________________</div></div>
    </body></html>`
  const win = window.open('', '_blank', 'width=960,height=720')
  if (!win) {
    ElMessage.warning('浏览器拦截了打印窗口，请允许弹窗后重试')
    return
  }
  win.document.write(html)
  win.document.close()
  win.focus()
  setTimeout(() => win.print(), 300)
}

const changePage = (value: number) => {
  page.value = value
  load()
}

onMounted(async () => {
  await table.load()
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">报价管理</h2>
        <p class="page-sub">报价员报价 → 技术部评审 → 通过后转销售订单</p>
      </div>
      <el-button v-perm="'quotation:create'" type="primary" @click="openCreate">新建报价</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="报价单号 / 客户名称" clearable style="width: 240px" @keyup.enter="reload" />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="reload">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待评审" value="SUBMITTED" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="已转订单" value="CONVERTED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" plain @click="reload">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/sales-quotes" export-name="报价单" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 张报价单</span>
      </div>

      <div class="table-wrap">
        <DataTable
          :table="table"
          :data="rows"
          :loading="loading"
          :total="total"
          :page="page"
          :size="size"
          @page-change="changePage"
        >
          <template #cell-quoteNo="{ row }"><span class="mono">{{ row.quoteNo }}</span></template>
          <template #cell-qty="{ row }"><span class="num">{{ fmtQty(row.totalQty) }}</span></template>
          <template #cell-amount="{ row }"><span class="money">{{ currencySymbol(row.currency) }}{{ fmtMoney(row.totalAmount) }}</span></template>
          <template #cell-status="{ row }">
            <el-tag :type="statusType[row.status]" size="small">{{ statusText[row.status] || row.status }}</el-tag>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <template v-if="row.status === 'DRAFT' || row.status === 'REJECTED'">
              <el-button v-perm="'quotation:update'" link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-perm="'quotation:submit'" link type="primary" @click="openSubmit(row)">提交评审</el-button>
              <el-button v-if="row.status === 'DRAFT'" v-perm="'quotation:delete'" link type="danger" @click="removeQuote(row)">删除</el-button>
            </template>
            <template v-else-if="row.status === 'SUBMITTED'">
              <el-button
                v-if="row.myReviewStatus === 'PENDING'"
                v-perm="'quotation:review'"
                link
                type="warning"
                @click="openReview(row)"
              >评审</el-button>
              <span v-else class="muted">待他人评审</span>
            </template>
            <template v-else-if="row.status === 'APPROVED'">
              <el-button v-perm="'quotation:convert'" link type="success" @click="convert(row)">转订单</el-button>
            </template>
            <el-button
              v-if="['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'].includes(row.status)"
              v-perm="'quotation:cancel'"
              link
              type="danger"
              @click="cancelQuote(row)"
            >取消</el-button>
          </template>
          <template #empty>
            <div class="empty-state">
              <div class="empty-state-icon">价</div>
              <div class="empty-state-title">还没有报价单</div>
              <div class="empty-state-desc">新建报价并提交技术评审，通过后一键转成销售订单。</div>
              <div class="empty-state-actions">
                <el-button v-perm="'quotation:create'" type="primary" @click="openCreate">新建报价</el-button>
              </div>
            </div>
          </template>
        </DataTable>
      </div>
    </div>

    <el-drawer append-to-body v-model="drawer" size="820px" :title="editingId ? '编辑报价单' : '新建报价单'">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="客户" required>
            <ReferenceSelect v-model="form.customerId" url="/options/customers"
                             :initial-label="form.customerLabel"
                             placeholder="输入客户编码或名称搜索" />
          </el-form-item>
          <el-form-item label="联系人">
            <el-input v-model="form.contact" placeholder="客户对接人" />
          </el-form-item>
          <el-form-item label="期望交期">
            <el-date-picker v-model="form.deliveryDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="报价有效期至">
            <el-date-picker v-model="form.validUntil" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="币种">
            <el-select v-model="form.currency" style="width: 100%">
              <el-option label="人民币 CNY" value="CNY" />
              <el-option label="美元 USD" value="USD" />
              <el-option label="欧元 EUR" value="EUR" />
            </el-select>
          </el-form-item>
          <el-form-item label="税率（%）">
            <el-input-number v-model="form.taxRatePercent" :min="0" :max="100" :precision="2" :step="1" style="width: 100%" />
          </el-form-item>
          <el-form-item label="价格口径">
            <el-switch
              v-model="form.priceIncludesTax"
              active-text="单价含税"
              inactive-text="单价未税"
            />
          </el-form-item>
          <el-form-item label="付款条件">
            <el-input v-model="form.paymentTerms" placeholder="如：月结 30 天、预付 30%" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" placeholder="报价说明、付款条件等" />
          </el-form-item>
        </div>

        <div class="drawer-section">
          <h4>
            <span>报价明细</span>
            <el-button size="small" @click="addItem">添加产品</el-button>
          </h4>
          <el-table :data="form.items" size="small">
            <el-table-column label="产品" min-width="220">
              <template #default="{ row }">
                <ReferenceSelect v-model="row.productId" url="/options/products"
                                 :initial-label="row.productCode ? `${row.productCode} · ${row.productName}` : row.productName"
                                 placeholder="输入物料编码或名称搜索"
                                 @select="(product) => onProductSelect(row, product)" />
              </template>
            </el-table-column>
            <el-table-column label="规格" min-width="120">
              <template #default="{ row }"><span class="muted">{{ row.spec || '-' }}</span></template>
            </el-table-column>
            <el-table-column label="数量" width="110">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="0.01" :precision="2" size="small" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column :label="form.priceIncludesTax ? '含税单价' : '未税单价'" width="120">
              <template #default="{ row }">
                <el-input-number v-model="row.price" :min="0" :precision="2" size="small" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="金额" width="110" align="right">
              <template #default="{ row }">
                <span class="money">¥{{ fmtMoney(Number(row.qty || 0) * Number(row.price || 0)) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeItem($index)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="order-total">
            <div><span>未税金额</span><b class="money">{{ currencySymbol(form.currency) }}{{ fmtMoney(totals.net) }}</b></div>
            <div><span>税额（{{ form.taxRatePercent }}%）</span><b class="money">{{ currencySymbol(form.currency) }}{{ fmtMoney(totals.tax) }}</b></div>
            <div><span>价税合计</span><b class="money">{{ currencySymbol(form.currency) }}{{ fmtMoney(totals.total) }}</b></div>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存报价单</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="submitDialog" title="提交技术评审" width="520px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="报价单">
          <span class="mono">{{ submitTarget.quoteNo }}</span>
        </el-form-item>
        <el-form-item label="评审人" required>
          <el-select
            v-model="selectedReviewers"
            multiple
            filterable
            remote
            :remote-method="searchReviewers"
            :loading="reviewerLoading"
            style="width: 100%"
            placeholder="至少选择一名评审人"
            @change="rememberReviewers"
          >
            <el-option
              v-for="item in reviewerOptions"
              :key="item.id"
              :label="`${item.realName}（${item.username}）`"
              :value="item.id"
            />
          </el-select>
          <div class="muted" style="margin-top: 6px; font-size: 12px">
            全部评审人通过才算评审通过；报价人本人不能作为评审人。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="confirmSubmit">提交评审</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="addDialog" title="加签评审人" width="460px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="追加评审人" required>
          <el-select
            v-model="addForm.reviewerId"
            filterable
            remote
            :remote-method="searchReviewers"
            :loading="reviewerLoading"
            style="width: 100%"
            placeholder="选择评审人"
            @change="rememberReviewers"
          >
            <el-option
              v-for="item in availableReviewers"
              :key="item.id"
              :label="`${item.realName}（${item.username}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmAdd">加签</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transferDialog" title="转办评审" width="460px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="转出">
          <span>{{ transferForm.fromName }}</span>
        </el-form-item>
        <el-form-item label="转给" required>
          <el-select
            v-model="transferForm.toId"
            filterable
            remote
            :remote-method="searchReviewers"
            :loading="reviewerLoading"
            style="width: 100%"
            placeholder="选择评审人"
            @change="rememberReviewers"
          >
            <el-option
              v-for="item in availableReviewers"
              :key="item.id"
              :label="`${item.realName}（${item.username}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmTransfer">转办</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewDialog" title="技术评审" width="480px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="报价单">
          <span class="mono">{{ reviewForm.quoteNo }}</span>
        </el-form-item>
        <el-form-item v-if="reviewForm.reviewerId" label="代审对象">
          <span>{{ reviewForm.reviewerName }}（以该评审人身份给出结论，审计会记录代审人）</span>
        </el-form-item>
        <el-form-item label="评审结论" required>
          <el-radio-group v-model="reviewForm.result">
            <el-radio value="PASS">通过</el-radio>
            <el-radio value="REJECT">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="评审意见">
          <el-input v-model="reviewForm.comment" type="textarea" :rows="3" placeholder="驳回时必须填写原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewDialog = false">取消</el-button>
        <el-button type="primary" :loading="reviewing" @click="submitReviewResult">提交评审</el-button>
      </template>
    </el-dialog>

    <el-drawer append-to-body v-model="detailDrawer" size="720px" title="报价单详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-no mono">{{ detail.quoteNo }}</div>
              <div class="muted" style="margin-top: 4px">{{ detail.customerName }}</div>
            </div>
            <el-tag :type="statusType[detail.status]" size="small">{{ statusText[detail.status] }}</el-tag>
            <el-button size="small" @click="printQuote">打印报价单</el-button>
          </div>
          <div class="detail-grid">
            <div class="detail-item"><div class="label">联系人</div><div class="value">{{ detail.contact || '-' }}</div></div>
            <div class="detail-item"><div class="label">期望交期</div><div class="value">{{ detail.deliveryDate || '-' }}</div></div>
            <div class="detail-item"><div class="label">有效期至</div><div class="value">{{ detail.validUntil || '-' }}</div></div>
            <div class="detail-item"><div class="label">评审截止</div><div class="value">{{ detail.reviewDueDate || '-' }}</div></div>
            <div class="detail-item"><div class="label">合计数量</div><div class="value num">{{ fmtQty(detail.totalQty) }}</div></div>
            <div class="detail-item"><div class="label">币种</div><div class="value">{{ detail.currency || 'CNY' }}</div></div>
            <div class="detail-item"><div class="label">价格口径</div><div class="value">{{ detail.priceIncludesTax ? '单价含税' : '单价未税' }}</div></div>
            <div class="detail-item"><div class="label">税率</div><div class="value">{{ (Number(detail.taxRate || 0) * 100).toFixed(2) }}%</div></div>
            <div class="detail-item"><div class="label">未税金额</div><div class="value money">{{ currencySymbol(detail.currency) }}{{ fmtMoney(detail.netAmount) }}</div></div>
            <div class="detail-item"><div class="label">税额</div><div class="value money">{{ currencySymbol(detail.currency) }}{{ fmtMoney(detail.taxAmount) }}</div></div>
            <div class="detail-item"><div class="label">价税合计</div><div class="value money">{{ currencySymbol(detail.currency) }}{{ fmtMoney(detail.totalAmount) }}</div></div>
            <div class="detail-item"><div class="label">付款条件</div><div class="value">{{ detail.paymentTerms || '-' }}</div></div>
            <div class="detail-item"><div class="label">报价员</div><div class="value">{{ detail.createdBy || '-' }}</div></div>
            <div class="detail-item" style="grid-column: span 3">
              <div class="label">备注</div><div class="value">{{ detail.remark || '-' }}</div>
            </div>
          </div>

          <div class="drawer-section">
            <h4>报价明细</h4>
            <el-table :data="detail.items" size="small">
              <el-table-column prop="productCode" label="编码" width="100" />
              <el-table-column prop="productName" label="产品" min-width="150" />
              <el-table-column prop="spec" label="规格" min-width="110" />
              <el-table-column label="数量" width="90" align="right">
                <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
              </el-table-column>
              <el-table-column label="单价" width="90" align="right">
                <template #default="{ row }">¥{{ fmtMoney(row.price) }}</template>
              </el-table-column>
              <el-table-column label="金额" width="100" align="right">
                <template #default="{ row }"><span class="money">¥{{ fmtMoney(row.amount) }}</span></template>
              </el-table-column>
            </el-table>
          </div>

          <div v-if="detail.reviewers?.length" class="drawer-section">
            <h4>
              <span>评审人</span>
              <el-button
                v-if="detail.status === 'SUBMITTED' && (isAdmin || detail.createdBy === currentUsername)"
                size="small"
                @click="openAdd"
              >加签</el-button>
            </h4>
            <div v-for="item in detail.reviewers" :key="item.reviewerId" class="reviewer-row">
              <span class="reviewer-name">{{ item.reviewerName || item.username }}</span>
              <el-tag
                size="small"
                :type="item.status === 'PASS' ? 'success' : (item.status === 'REJECT' ? 'danger' : 'warning')"
              >
                {{ item.status === 'PASS' ? '已通过' : (item.status === 'REJECT' ? '已驳回' : '待评审') }}
              </el-tag>
              <span class="muted">{{ item.reviewedAt || '' }}</span>
              <span class="review-comment">{{ item.comment || '' }}</span>
              <template v-if="item.status === 'PENDING' && detail.status === 'SUBMITTED'">
                <el-button
                  v-if="isAdmin"
                  link
                  type="warning"
                  @click="openReview(detail, item)"
                >代审</el-button>
                <el-button
                  v-if="isAdmin || item.username === currentUsername"
                  link
                  type="primary"
                  @click="openTransfer(item)"
                >转办</el-button>
                <el-button
                  v-if="isAdmin || detail.createdBy === currentUsername"
                  link
                  type="danger"
                  @click="removeReviewer(item)"
                >撤签</el-button>
              </template>
            </div>
          </div>

          <div class="drawer-section">
            <Attachments
              biz-type="QUOTATION"
              :biz-id="detail.id"
              :biz-no="detail.quoteNo"
              title="图纸 / 报价附件"
              hint="可上传客户图纸、技术协议、报价函等，单个不超过 20MB"
            />
          </div>

          <div class="drawer-section">
            <h4>评审记录</h4>
            <div v-if="!detail.reviews?.length" class="muted">还没有评审记录</div>
            <div v-for="item in detail.reviews" :key="item.id" class="review-row">
              <span class="review-round">第 {{ item.roundNo }} 轮</span>
              <el-tag :type="item.result === 'PASS' ? 'success' : 'danger'" size="small">
                {{ item.result === 'PASS' ? '通过' : '驳回' }}
              </el-tag>
              <span class="mono">{{ item.reviewer }}</span>
              <span class="muted">{{ item.createdAt }}</span>
              <span class="review-comment">{{ item.comment || '-' }}</span>
            </div>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.review-row,
.reviewer-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--line);
  font-size: 12px;
}

.reviewer-name {
  min-width: 90px;
  font-weight: 600;
}

.review-round {
  font-weight: 600;
}

.review-comment {
  color: var(--muted);
  word-break: break-all;
}

.order-total {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 320px;
  margin: 10px 0 0 auto;
  font-size: 13px;
}

.order-total > div {
  display: flex;
  justify-content: space-between;
}
</style>
