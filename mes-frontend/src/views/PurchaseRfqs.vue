<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../api'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import ReferenceSelect from '../components/ReferenceSelect.vue'
import { useTableView } from '../composables/useTableView'

const tab = ref('rfq')

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const status = ref('')
const summary = ref<any>({})

const requisitions = ref<any[]>([])

const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const createDialog = ref(false)
const creating = ref(false)
const createForm = reactive({
  requisitionId: null as any,
  deadline: '',
  remark: '',
  items: [] as any[]
})

const quoteDialog = ref(false)
const quoteSaving = ref(false)
const quoteMode = ref<'create' | 'edit'>('create')
const quoteForm = reactive({
  supplierId: null as any,
  supplierLabel: '',
  leadDays: 7,
  paymentTerm: '',
  validUntil: '',
  remark: '',
  items: [] as any[]
})

const priceLoading = ref(false)
const priceRows = ref<any[]>([])
const priceFilters = reactive({
  supplierId: null as any,
  productId: null as any,
  keyword: '',
  validOnly: true
})

// 询价单列表视图
const table = useTableView({
  pageKey: 'purchase.rfqs',
  columns: [
    { key: 'rfqNo', label: '询价单号', width: 165, fixed: 'left' },
    { key: 'reqNo', label: '来源申请', width: 160 },
    { key: 'purpose', label: '用途', width: 180, flex: true, ellipsis: true },
    { key: 'items', label: '明细 / 报价', width: 120 },
    { key: 'deadline', label: '截止日期', width: 110 },
    { key: 'selectedSupplier', label: '选定供应商', width: 160, flex: true, ellipsis: true },
    { key: 'status', label: '状态', width: 100 },
    { key: 'actions', label: '操作', width: 160, fixed: 'right' }
  ],
  getFilters: () => ({ keyword: keyword.value, status: status.value }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    status.value = filters.status || ''
  },
  getPageSize: () => size.value,
  setPageSize: (value) => { size.value = value }
})

// 供应商价格列表视图
const priceTable = useTableView({
  pageKey: 'purchase.rfq-prices',
  columns: [
    { key: 'supplier', label: '供应商', width: 190, flex: true, ellipsis: true },
    { key: 'product', label: '物料', width: 190, flex: true, ellipsis: true },
    { key: 'price', label: '价格', width: 110, align: 'right' },
    { key: 'leadDays', label: '交期', width: 85 },
    { key: 'valid', label: '有效期', width: 190 },
    { key: 'source', label: '来源', width: 150 },
    { key: 'status', label: '状态', width: 90 }
  ],
  getFilters: () => ({
    supplierId: priceFilters.supplierId,
    productId: priceFilters.productId,
    keyword: priceFilters.keyword,
    validOnly: priceFilters.validOnly
  }),
  setFilters: (filters) => {
    priceFilters.supplierId = filters.supplierId ?? null
    priceFilters.productId = filters.productId ?? null
    priceFilters.keyword = filters.keyword || ''
    priceFilters.validOnly = filters.validOnly !== false
  }
})

const reload = () => {
  page.value = 1
  load()
}

const statusMap: Record<string, { label: string; cls: string }> = {
  OPEN: { label: '待报价', cls: 'info' },
  QUOTED: { label: '已报价', cls: 'warning' },
  SELECTED: { label: '已选定', cls: 'success' },
  CANCELLED: { label: '已取消', cls: 'info' }
}

const statusOf = (value: any) =>
  statusMap[String(value || '')] || { label: String(value || '-'), cls: 'info' }

const quoteStatusLabel = (value: any) =>
  value === 'SELECTED' ? '已选定' : value === 'REJECTED' ? '未选中' : '已报价'

const quoteAmount = computed(() =>
  quoteForm.items.reduce((sum, item) => sum + Number(item.qty || 0) * Number(item.price || 0), 0)
)

const load = async () => {
  loading.value = true
  try {
    const [data, sum] = await Promise.all([
      api.get('/purchase-rfqs', {
        keyword: keyword.value || undefined,
        status: status.value || undefined,
        page: page.value,
        size: size.value
      }),
      api.get('/purchase-rfqs/summary')
    ])
    rows.value = data?.records || []
    total.value = data?.total || 0
    summary.value = sum || {}
  } finally {
    loading.value = false
  }
}

const loadOptions = async () => {
  const [approved, partial] = await Promise.all([
    api.get('/purchase-requisitions', { status: 'APPROVED', size: 100 }),
    api.get('/purchase-requisitions', { status: 'PARTIAL', size: 100 })
  ])
  requisitions.value = [...(approved?.records || []), ...(partial?.records || [])]
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await api.get(`/purchase-rfqs/${row.id}`)
  } finally {
    detailLoading.value = false
  }
}

const reloadDetail = async () => {
  if (!detail.value) return
  detail.value = await api.get(`/purchase-rfqs/${detail.value.id}`)
}

const quoteLine = (quote: any, rfqItemId: any) =>
  (quote.items || []).find((line: any) => line.rfqItemId === rfqItemId) || null

const quotePrice = (quote: any, rfqItemId: any) => {
  const line = quoteLine(quote, rfqItemId)
  return line ? Number(line.price) : null
}

const isLowest = (quote: any, rfqItemId: any) => quoteLine(quote, rfqItemId)?.lowest === true

const openCreate = async () => {
  await loadOptions()
  Object.assign(createForm, { requisitionId: null, deadline: '', remark: '', items: [] })
  createDialog.value = true
}

const requisitionChanged = async () => {
  createForm.items = []
  if (!createForm.requisitionId) return
  const data = await api.get(`/purchase-requisitions/${createForm.requisitionId}`)
  createForm.items = (data.items || [])
    .filter((item: any) => item.needQuote === true && Number(item.remainingQty) > 0)
    .map((item: any) => ({
      itemId: item.id,
      productCode: item.productCode,
      productName: item.productName,
      unit: item.unit,
      qty: Number(item.remainingQty),
      targetPrice: Number(item.targetPrice || 0),
      selected: true
    }))
  if (!createForm.items.length) {
    ElMessage.warning('这张申请没有需要询价且还有剩余数量的明细')
  }
}

const submitCreate = async () => {
  if (!createForm.requisitionId) {
    ElMessage.warning('请选择采购申请')
    return
  }
  const itemIds = createForm.items.filter((item) => item.selected).map((item) => item.itemId)
  if (!itemIds.length) {
    ElMessage.warning('请至少勾选一条询价明细')
    return
  }
  creating.value = true
  try {
    const id = await api.post(`/purchase-rfqs/from-requisition/${createForm.requisitionId}`, {
      itemIds,
      deadline: createForm.deadline || undefined,
      remark: createForm.remark
    })
    ElMessage.success('询价单已创建，可以开始录入报价')
    createDialog.value = false
    load()
    const created = await api.get(`/purchase-rfqs/${id}`)
    detailDrawer.value = true
    detail.value = created
  } finally {
    creating.value = false
  }
}

const openQuote = (quote?: any) => {
  if (!detail.value) return
  quoteMode.value = quote ? 'edit' : 'create'
  quoteForm.supplierId = quote ? quote.supplierId : null
  quoteForm.supplierLabel = quote
    ? (quote.supplierCode ? `${quote.supplierCode} · ${quote.supplierName}` : (quote.supplierName || ''))
    : ''
  quoteForm.leadDays = quote ? Number(quote.leadDays) : 7
  quoteForm.paymentTerm = quote?.paymentTerm || ''
  quoteForm.validUntil = quote?.validUntil || ''
  quoteForm.remark = quote?.remark || ''
  quoteForm.items = (detail.value.items || []).map((item: any) => {
    const line = quote ? quoteLine(quote, item.id) : null
    return {
      rfqItemId: item.id,
      productCode: item.productCode,
      productName: item.productName,
      unit: item.unit,
      qty: Number(item.qty),
      price: line ? Number(line.price) : 0
    }
  })
  quoteDialog.value = true
}

const saveQuote = async () => {
  if (!quoteForm.supplierId) {
    ElMessage.warning('请选择报价供应商')
    return
  }
  if (quoteForm.items.every((item) => Number(item.price || 0) <= 0)) {
    ElMessage.warning('请至少填写一条大于 0 的报价')
    return
  }
  quoteSaving.value = true
  try {
    await api.post(`/purchase-rfqs/${detail.value.id}/quotes`, {
      supplierId: quoteForm.supplierId,
      leadDays: Number(quoteForm.leadDays || 0),
      paymentTerm: quoteForm.paymentTerm,
      validUntil: quoteForm.validUntil || undefined,
      remark: quoteForm.remark,
      items: quoteForm.items.map((item) => ({
        rfqItemId: item.rfqItemId,
        price: Number(item.price || 0)
      }))
    })
    ElMessage.success(quoteMode.value === 'edit' ? '报价已更新' : '报价已录入')
    quoteDialog.value = false
    await reloadDetail()
    load()
  } finally {
    quoteSaving.value = false
  }
}

const removeQuote = async (quote: any) => {
  await ElMessageBox.confirm(`删除 ${quote.supplierName} 的报价？`, '删除报价', { type: 'warning' })
  await api.delete(`/purchase-rfqs/${detail.value.id}/quotes/${quote.id}`)
  ElMessage.success('已删除')
  await reloadDetail()
  load()
}

const selectQuote = async (quote: any) => {
  await ElMessageBox.confirm(
    `选定 ${quote.supplierName} 的报价，合计 ${fmtMoney(quote.totalAmount)}？选定后价格会写进价格库，并回填申请行目标价。`,
    '选定报价',
    { type: 'warning' }
  )
  const result = await api.post(`/purchase-rfqs/${detail.value.id}/select`, { quoteId: quote.id })
  ElMessage.success(`已选定，${result.priceCount} 条价格写入价格库`)
  await reloadDetail()
  load()
}

const cancelRfq = async (row: any) => {
  await ElMessageBox.confirm(`取消询价单 ${row.rfqNo}？`, '取消询价', { type: 'warning' })
  await api.post(`/purchase-rfqs/${row.id}/cancel`)
  ElMessage.success('已取消')
  load()
}

const reset = () => {
  keyword.value = ''
  status.value = ''
  page.value = 1
  load()
}

const loadPrices = async () => {
  priceLoading.value = true
  try {
    priceRows.value = await api.get('/supplier-prices', {
      supplierId: priceFilters.supplierId || undefined,
      productId: priceFilters.productId || undefined,
      keyword: priceFilters.keyword || undefined,
      validOnly: priceFilters.validOnly,
      limit: 200
    })
  } finally {
    priceLoading.value = false
  }
}

const changeTab = async (name: any) => {
  if (name === 'price') {
    await loadPrices()
  } else {
    await load()
  }
}

onMounted(async () => {
  await loadOptions()
  await table.load()
  await priceTable.load()
  await load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">询价比价</h2>
        <p class="page-sub">
          申请里勾了「需询价」的行生成询价单，录入多家报价后按价格、交期、账期比较，选定结果沉淀到价格库
        </p>
      </div>
      <el-button v-perm="'purchase:rfq'" type="primary" @click="openCreate">发起询价</el-button>
    </div>

    <el-tabs v-model="tab" @tab-change="changeTab">
      <el-tab-pane label="询价单" name="rfq">
        <div class="rfq-strip">
          <div class="rfq-item">
            <span>待报价</span>
            <b>{{ summary.open || 0 }}</b>
          </div>
          <div class="rfq-item">
            <span>已报价</span>
            <b class="warn-text">{{ summary.quoted || 0 }}</b>
          </div>
          <div class="rfq-item">
            <span>已选定</span>
            <b class="primary-text">{{ summary.selected || 0 }}</b>
          </div>
          <div class="rfq-item">
            <span>累计报价</span>
            <b>{{ summary.quotes || 0 }}</b>
          </div>
          <div class="rfq-item">
            <span>价格库</span>
            <b>{{ summary.prices || 0 }}</b>
          </div>
        </div>

        <div class="panel">
          <div class="filter-bar">
            <el-input
              v-model="keyword"
              placeholder="询价单号 / 申请单号 / 用途"
              clearable
              style="width: 240px"
              @keyup.enter="load"
            />
            <el-select v-model="status" placeholder="全部状态" clearable style="width: 130px" @change="load">
              <el-option label="待报价" value="OPEN" />
              <el-option label="已报价" value="QUOTED" />
              <el-option label="已选定" value="SELECTED" />
              <el-option label="已取消" value="CANCELLED" />
            </el-select>
            <el-button type="primary" plain @click="load">查询</el-button>
            <el-button plain @click="reset">重置</el-button>
            <div class="spacer"></div>
            <TableToolbar :table="table" export-api="/purchase-rfqs" export-name="询价比价" @applied="reload" />
            <span class="toolbar-tip">共 {{ total }} 张询价单</span>
          </div>

          <div class="table-wrap">
            <DataTable :table="table" :data="rows" :loading="loading">
              <template #cell-rfqNo="{ row }"><span class="mono">{{ row.rfqNo }}</span></template>
              <template #cell-reqNo="{ row }">
                <span v-if="row.reqNo" class="mono">{{ row.reqNo }}</span>
                <span v-else class="muted">-</span>
              </template>
              <template #cell-purpose="{ row }">{{ row.purpose || '-' }}</template>
              <template #cell-items="{ row }">
                <div>{{ row.itemCount }} 项</div>
                <div class="muted" style="font-size: 11px">{{ row.quoteCount }} 家报价</div>
              </template>
              <template #cell-deadline="{ row }">{{ row.deadline || '-' }}</template>
              <template #cell-selectedSupplier="{ row }">{{ row.selectedSupplierName || '-' }}</template>
              <template #cell-status="{ row }">
                <span class="status-tag" :class="statusOf(row.status).cls">
                  <i></i>{{ statusOf(row.status).label }}
                </span>
              </template>
              <template #cell-actions="{ row }">
                <el-button link type="primary" @click="openDetail(row)">比价</el-button>
                <el-button
                  v-if="row.status === 'OPEN' || row.status === 'QUOTED'"
                  v-perm="'purchase:rfq'"
                  link
                  type="danger"
                  @click="cancelRfq(row)"
                >
                  取消
                </el-button>
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

      <el-tab-pane label="供应商价格" name="price">
        <div class="panel">
          <div class="filter-bar">
            <ReferenceSelect
              v-model="priceFilters.supplierId"
              url="/suppliers/options"
              placeholder="全部供应商"
              style="width: 190px"
              @select="loadPrices"
            />
            <ReferenceSelect
              v-model="priceFilters.productId"
              url="/options/products"
              placeholder="全部物料"
              style="width: 190px"
              @select="loadPrices"
            />
            <el-input
              v-model="priceFilters.keyword"
              placeholder="供应商 / 物料 / 来源单号"
              clearable
              style="width: 220px"
              @keyup.enter="loadPrices"
            />
            <el-checkbox v-model="priceFilters.validOnly" @change="loadPrices">只看有效价格</el-checkbox>
            <el-button type="primary" plain @click="loadPrices">查询</el-button>
            <div class="spacer"></div>
            <TableToolbar :table="priceTable" export-api="/supplier-prices" export-name="供应商价格" @applied="loadPrices" />
          </div>

          <div class="table-wrap">
            <DataTable :table="priceTable" :data="priceRows" :loading="priceLoading">
              <template #cell-supplier="{ row }">
                <div>{{ row.supplierName }}</div>
                <div class="muted mono" style="font-size: 11px">{{ row.supplierCode }}</div>
              </template>
              <template #cell-product="{ row }">
                <div>{{ row.productName }}</div>
                <div class="muted mono" style="font-size: 11px">{{ row.productCode }}</div>
              </template>
              <template #cell-price="{ row }"><span class="mono">{{ fmtMoney(row.price) }}</span></template>
              <template #cell-leadDays="{ row }">{{ row.leadDays }} 天</template>
              <template #cell-valid="{ row }">
                <span class="mono">{{ row.validFrom }} ~ {{ row.validTo || '长期' }}</span>
              </template>
              <template #cell-source="{ row }">
                <span class="mono">{{ row.sourceNo || row.source }}</span>
              </template>
              <template #cell-status="{ row }">
                <span class="status-tag" :class="row.valid ? 'success' : 'info'">
                  <i></i>{{ row.valid ? '有效' : '过期' }}
                </span>
              </template>
            </DataTable>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="detailDrawer" size="960px" title="询价比价" append-to-body>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <span class="mono strong">{{ detail.rfqNo }}</span>
            <span class="status-tag" :class="statusOf(detail.status).cls">
              <i></i>{{ statusOf(detail.status).label }}
            </span>
            <span v-if="detail.reqNo" class="muted mono">来源 {{ detail.reqNo }}</span>
            <span class="muted">截止 {{ detail.deadline || '未设置' }}</span>
            <span v-if="detail.selectedSupplierName" class="muted">已选 {{ detail.selectedSupplierName }}</span>
            <div class="spacer"></div>
            <el-button
              v-if="detail.status === 'OPEN' || detail.status === 'QUOTED'"
              v-perm="'purchase:rfq'"
              type="primary"
              plain
              size="small"
              @click="openQuote()"
            >
              录入报价
            </el-button>
          </div>

          <div class="section-title">询价明细</div>
          <el-table :data="detail.items || []" size="small">
            <el-table-column label="物料" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ row.productName }}</div>
                <div class="muted mono" style="font-size: 11px">{{ row.productCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="110" align="right">
              <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column label="最低报价" width="120" align="right">
              <template #default="{ row }">
                <span v-if="row.lowestPrice" class="mono best-text">{{ fmtMoney(row.lowestPrice) }}</span>
                <span v-else class="muted">暂无</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="section-title">报价对比</div>
          <el-table :data="detail.items || []" size="small" border>
            <el-table-column label="物料" min-width="170" fixed>
              <template #default="{ row }">
                <div>{{ row.productName }}</div>
                <div class="muted mono" style="font-size: 11px">{{ row.productCode }}</div>
              </template>
            </el-table-column>
            <el-table-column
              v-for="quote in detail.quotes || []"
              :key="quote.id"
              :label="`${quote.supplierName}（${quote.leadDays}天）`"
              width="170"
              align="right"
            >
              <template #default="{ row }">
                <span
                  v-if="quotePrice(quote, row.id) !== null"
                  class="mono"
                  :class="isLowest(quote, row.id) ? 'best-text' : ''"
                >
                  {{ fmtMoney(quotePrice(quote, row.id)) }}
                  <template v-if="isLowest(quote, row.id)"> 最低</template>
                </span>
                <span v-else class="muted">未报</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="section-title">供应商报价</div>
          <el-table :data="detail.quotes || []" size="small" empty-text="还没有报价">
            <el-table-column label="供应商" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ row.supplierName }}</div>
                <div class="muted mono" style="font-size: 11px">{{ row.supplierCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="交期" width="80">
              <template #default="{ row }">{{ row.leadDays }} 天</template>
            </el-table-column>
            <el-table-column label="账期" width="110">
              <template #default="{ row }">{{ row.paymentTerm || '-' }}</template>
            </el-table-column>
            <el-table-column label="有效期" width="110">
              <template #default="{ row }">{{ row.validUntil || '-' }}</template>
            </el-table-column>
            <el-table-column label="含税总价" width="130" align="right">
              <template #default="{ row }">
                <span class="mono" :class="row.id === detail.bestQuoteId ? 'best-text' : ''">
                  {{ fmtMoney(row.totalAmount) }}
                </span>
                <div v-if="row.id === detail.bestQuoteId" class="best-tag">总价最低</div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <span class="status-tag" :class="row.status === 'SELECTED' ? 'success' : row.status === 'REJECTED' ? 'info' : 'warning'">
                  <i></i>{{ quoteStatusLabel(row.status) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="detail.status === 'OPEN' || detail.status === 'QUOTED'"
                  v-perm="'purchase:select_quote'"
                  link
                  type="success"
                  @click="selectQuote(row)"
                >
                  选定
                </el-button>
                <el-button
                  v-if="detail.status === 'OPEN' || detail.status === 'QUOTED'"
                  v-perm="'purchase:rfq'"
                  link
                  @click="openQuote(row)"
                >
                  改报价
                </el-button>
                <el-button
                  v-if="detail.status === 'OPEN' || detail.status === 'QUOTED'"
                  v-perm="'purchase:rfq'"
                  link
                  type="danger"
                  @click="removeQuote(row)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <p class="detail-note">
            选定后会把该供应商每个物料的价格写入供应商价格库，并把申请行目标价、建议供应商一起回填；
            后续转采购单会默认带出这个价格。已选定的询价单不能改价或重选。
          </p>
        </template>
      </div>
    </el-drawer>

    <el-dialog draggable append-to-body v-model="createDialog" title="发起询价" width="760px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="采购申请" required>
            <el-select
              v-model="createForm.requisitionId"
              filterable
              placeholder="选择已批准或部分转单的申请"
              style="width: 100%"
              @change="requisitionChanged"
            >
              <el-option
                v-for="item in requisitions"
                :key="item.id"
                :label="`${item.reqNo} · ${item.purpose || '采购申请'}`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="报价截止">
            <el-date-picker v-model="createForm.deadline" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="createForm.remark" placeholder="如：含税到厂价，报价有效期 30 天" />
          </el-form-item>
        </div>

        <div class="section-title">需要询价的明细</div>
        <el-table :data="createForm.items" size="small" empty-text="选择申请后带出勾选需询价的行">
          <el-table-column width="50">
            <template #default="{ row }"><el-checkbox v-model="row.selected" /></template>
          </el-table-column>
          <el-table-column label="物料" min-width="200">
            <template #default="{ row }">
              <div>{{ row.productName }}</div>
              <div class="muted mono" style="font-size: 11px">{{ row.productCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="申请剩余" width="120" align="right">
            <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="目标单价" width="120" align="right">
            <template #default="{ row }"><span class="mono">{{ fmtMoney(row.targetPrice) }}</span></template>
          </el-table-column>
        </el-table>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">生成询价单</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable
      append-to-body
      v-model="quoteDialog"
      :title="quoteMode === 'edit' ? '修改报价' : '录入报价'"
      width="820px"
    >
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="供应商" required>
            <ReferenceSelect v-model="quoteForm.supplierId" url="/suppliers/options"
                             :initial-label="quoteForm.supplierLabel"
                             :disabled="quoteMode === 'edit'"
                             placeholder="输入供应商编码或名称搜索" />
          </el-form-item>
          <el-form-item label="交期（天）">
            <el-input-number v-model="quoteForm.leadDays" :min="0" :max="365" style="width: 100%" />
          </el-form-item>
          <el-form-item label="账期">
            <el-input v-model="quoteForm.paymentTerm" placeholder="如 月结 30 天" />
          </el-form-item>
          <el-form-item label="报价有效期">
            <el-date-picker v-model="quoteForm.validUntil" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="quoteForm.remark" placeholder="如：含税含运费，交期可提前" />
          </el-form-item>
        </div>

        <el-table :data="quoteForm.items" size="small">
          <el-table-column label="物料" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">
              <div>{{ row.productName }}</div>
              <div class="muted mono" style="font-size: 11px">{{ row.productCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="110" align="right">
            <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="报价单价" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.price" :min="0" :precision="2" :controls="false" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="right">
            <template #default="{ row }">
              <span class="mono">{{ fmtMoney(Number(row.qty || 0) * Number(row.price || 0)) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="quote-total">
          报价合计 <b class="mono">{{ fmtMoney(quoteAmount) }}</b>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="quoteDialog = false">取消</el-button>
        <el-button type="primary" :loading="quoteSaving" @click="saveQuote">保存报价</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rfq-strip {
  display: flex;
  align-items: flex-start;
  gap: 40px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 15px 20px;
  margin-bottom: 14px;
  box-shadow: var(--shadow);
  flex-wrap: wrap;
}

.rfq-item span {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.rfq-item b {
  display: block;
  margin-top: 6px;
  font-size: 20px;
  font-family: Bahnschrift, monospace;
}

.primary-text {
  color: #2563eb;
}

.warn-text {
  color: #b45309;
}

.best-text {
  color: #047857;
  font-weight: 600;
}

.best-tag {
  font-size: 11px;
  color: #047857;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 4px 2px;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.section-title {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}

.detail-note {
  margin: 14px 0 0;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.7;
}

.quote-total {
  margin-top: 10px;
  text-align: right;
  font-size: 13px;
  color: var(--ink-3);
}
</style>
