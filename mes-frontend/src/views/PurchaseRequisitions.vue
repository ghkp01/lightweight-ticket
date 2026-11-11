<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../api'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import ReferenceSelect from '../components/ReferenceSelect.vue'
import { useTableView } from '../composables/useTableView'

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const status = ref('')
const sourceType = ref('')
const summary = ref<any>({})
const route = useRoute()

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'purchase.requisitions',
  columns: [
    { key: 'reqNo', label: '申请单号', width: 155 },
    { key: 'sourceType', label: '来源', width: 105 },
    { key: 'requester', label: '申请人', width: 150, prop: 'requesterName' },
    { key: 'purpose', label: '用途', width: 180, flex: true, ellipsis: true },
    { key: 'needDate', label: '需求日期', width: 110 },
    { key: 'itemsQty', label: '明细 / 数量', width: 115, prop: '' },
    { key: 'totalAmount', label: '金额', width: 120, align: 'right' },
    { key: 'progress', label: '转单进度', width: 115, prop: '' },
    { key: 'status', label: '状态', width: 100 },
    { key: 'actions', label: '操作', width: 220, fixed: 'right', prop: '' }
  ],
  getFilters: () => ({ keyword: keyword.value, status: status.value, sourceType: sourceType.value }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    status.value = filters.status || ''
    sourceType.value = filters.sourceType || ''
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

const workOrders = ref<any[]>([])

const dialog = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive({
  sourceType: 'MANUAL',
  department: '',
  workOrderId: null as any,
  needDate: '',
  purpose: '',
  remark: '',
  // 采购类别与紧急程度是审批分流的依据，建单时就要定
  category: 'MATERIAL',
  urgent: false,
  budgetNo: '',
  items: [] as any[]
})

const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const convertDialog = ref(false)
const convertLoading = ref(false)
const convertSource = ref<any>(null)
const convertForm = reactive({
  supplierId: null as any,
  supplierLabel: '',
  warehouseId: null as any,
  warehouseLabel: '',
  expectedDate: '',
  needInspection: true,
  remark: '',
  items: [] as any[]
})
// 还有几行在审批中、这次不能转
const convertBlocked = ref(0)

const statusMap: Record<string, { label: string; cls: string }> = {
  DRAFT: { label: '草稿', cls: 'info' },
  SUBMITTED: { label: '待审批', cls: 'warning' },
  APPROVED: { label: '已批准', cls: 'primary' },
  PARTIAL: { label: '部分转单', cls: 'primary' },
  CONVERTED: { label: '已转单', cls: 'success' },
  REJECTED: { label: '已驳回', cls: 'danger' },
  CANCELLED: { label: '已取消', cls: 'info' }
}

const sourceMap: Record<string, { label: string; cls: string }> = {
  PRODUCTION: { label: '工单缺料', cls: 'warning' },
  STOCK: { label: '库存补货', cls: 'primary' },
  MANUAL: { label: '非生产', cls: 'info' },
  URGENT: { label: '紧急采购', cls: 'danger' }
}

const statusOf = (value: any) =>
  statusMap[String(value || '')] || { label: String(value || '-'), cls: 'info' }
const sourceOf = (value: any) =>
  sourceMap[String(value || '')] || { label: String(value || '-'), cls: 'info' }

const today = () => new Date().toISOString().slice(0, 10)

const totalAmount = computed(() =>
  form.items.reduce((sum, item) => sum + Number(item.qty || 0) * Number(item.targetPrice || 0), 0)
)

const convertAmount = computed(() =>
  convertForm.items.reduce((sum, item) => sum + Number(item.qty || 0) * Number(item.price || 0), 0)
)

const load = async () => {
  loading.value = true
  try {
    const [data, sum] = await Promise.all([
      api.get('/purchase-requisitions', {
        keyword: keyword.value || undefined,
        status: status.value || undefined,
        sourceType: sourceType.value || undefined,
        page: page.value,
        size: size.value
      }),
      api.get('/purchase-requisitions/summary')
    ])
    rows.value = data?.records || []
    total.value = data?.total || 0
    summary.value = sum || {}
  } finally {
    loading.value = false
  }
}

const loadOptions = async () => {
  const workData = await api.get('/work-orders', { page: 1, size: 100 })
  workOrders.value = workData?.records || []
}

const blankItem = () => ({
  productId: null,
  productCode: '',
  productName: '',
  unit: '',
  qty: 1,
  targetPrice: 0,
  suggestSupplierId: null,
  suggestSupplierName: '',
  needQuote: false,
  // 行级采购类别：留空表示跟随单据头，用于「同一张单里外协件与标准件各走各的审批链」
  category: '',
  remark: ''
})

const openCreate = () => {
  editingId.value = null
  Object.assign(form, {
    sourceType: 'MANUAL',
    department: '',
    workOrderId: null,
    needDate: '',
    purpose: '',
    remark: '',
    category: 'MATERIAL',
    urgent: false,
    budgetNo: '',
    items: [blankItem()]
  })
  dialog.value = true
}

const openEdit = async (row: any) => {
  const data = await api.get(`/purchase-requisitions/${row.id}`)
  editingId.value = row.id
  Object.assign(form, {
    sourceType: data.sourceType,
    department: data.department || '',
    workOrderId: data.workOrderId || null,
    needDate: data.needDate || '',
    purpose: data.purpose || '',
    remark: data.remark || '',
    category: data.category || 'MATERIAL',
    urgent: data.urgent === true,
    budgetNo: data.budgetNo || '',
    items: (data.items || []).map((item: any) => ({
      productId: item.productId,
      productCode: item.productCode || '',
      productName: item.productName || '',
      unit: item.unit || '',
      qty: Number(item.qty),
      targetPrice: Number(item.targetPrice),
      suggestSupplierId: item.suggestSupplierId || null,
      suggestSupplierName: item.suggestSupplierName || '',
      needQuote: item.needQuote === true,
      category: item.category || '',
      remark: item.remark || ''
    }))
  })
  dialog.value = true
}

const productSelected = (item: any, product: any) => {
  if (!product) return
  item.unit = product.unit || ''
  item.productCode = product.code || ''
  item.productName = product.name || ''
}

const supplierSelected = (item: any, supplier: any) => {
  item.suggestSupplierName = supplier ? supplier.name : ''
}

const addItem = () => form.items.push(blankItem())
const removeItem = (index: number) => {
  if (form.items.length <= 1) {
    ElMessage.warning('至少保留一条申请明细')
    return
  }
  form.items.splice(index, 1)
}

const submitForm = async () => {
  if (form.sourceType === 'PRODUCTION' && !form.workOrderId) {
    ElMessage.warning('工单缺料请选择关联工单')
    return
  }
  for (const item of form.items) {
    if (!item.productId) {
      ElMessage.warning('请为每条明细选择物料')
      return
    }
    if (!item.qty || Number(item.qty) <= 0) {
      ElMessage.warning('申请数量必须大于 0')
      return
    }
  }
  saving.value = true
  try {
    const payload = {
      sourceType: form.sourceType,
      department: form.department,
      workOrderId: form.workOrderId || undefined,
      needDate: form.needDate || undefined,
      purpose: form.purpose,
      remark: form.remark,
      category: form.category,
      urgent: form.urgent,
      budgetNo: form.budgetNo || undefined,
      items: form.items.map((item) => ({
        productId: item.productId,
        qty: Number(item.qty),
        targetPrice: Number(item.targetPrice || 0),
        suggestSupplierId: item.suggestSupplierId || undefined,
        needQuote: item.needQuote,
        category: item.category || undefined,
        remark: item.remark
      }))
    }
    if (editingId.value) {
      await api.put(`/purchase-requisitions/${editingId.value}`, payload)
      ElMessage.success('已保存')
    } else {
      await api.post('/purchase-requisitions', payload)
      ElMessage.success('申请单已创建，提交后进入审批')
    }
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const submit = async (row: any) => {
  await ElMessageBox.confirm(`提交采购申请 ${row.reqNo} 进入审批？`, '提交申请', { type: 'warning' })
  await api.post(`/purchase-requisitions/${row.id}/submit`)
  ElMessage.success('已提交，等待审批')
  load()
}

const approve = async (row: any) => {
  await ElMessageBox.confirm(
    `审批通过 ${row.reqNo}，金额 ${fmtMoney(row.totalAmount)}？超过阈值的申请只有管理员能批。`,
    '审批通过',
    { type: 'warning' }
  )
  await api.post(`/purchase-requisitions/${row.id}/approve`)
  ElMessage.success('已审批通过')
  load()
}

const reject = async (row: any) => {
  const result = await ElMessageBox.prompt(`驳回 ${row.reqNo}，请填写原因`, '驳回申请', {
    inputPlaceholder: '如：预算不足，下月再提',
    inputValidator: (value: string) => (value && value.trim() ? true : '驳回原因不能为空')
  })
  await api.post(`/purchase-requisitions/${row.id}/reject`, { reason: result.value })
  ElMessage.success('已驳回')
  load()
}

const cancel = async (row: any) => {
  await ElMessageBox.confirm(`取消采购申请 ${row.reqNo}？已转采购单的申请不能取消。`, '取消申请', {
    type: 'warning'
  })
  await api.post(`/purchase-requisitions/${row.id}/cancel`)
  ElMessage.success('已取消')
  load()
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(`删除草稿申请 ${row.reqNo}？`, '删除确认', { type: 'warning' })
  await api.delete(`/purchase-requisitions/${row.id}`)
  ElMessage.success('已删除')
  load()
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await api.get(`/purchase-requisitions/${row.id}`)
  } finally {
    detailLoading.value = false
  }
}

const openConvert = async (row: any) => {
  const data = await api.get(`/purchase-requisitions/${row.id}`)
  convertSource.value = data
  const suggested = (data.items || []).find((item: any) => item.suggestSupplierId) || null
  convertForm.supplierId = suggested ? suggested.suggestSupplierId : null
  convertForm.supplierLabel = suggested ? (suggested.suggestSupplierName || '') : ''
  // 默认落到第一个原料仓，不再整表加载仓库
  const warehouseOptions = await api.get('/options/warehouses', { type: 'RAW', limit: 1 })
  const rawWarehouse = warehouseOptions?.[0] || null
  convertForm.warehouseId = rawWarehouse ? rawWarehouse.id : null
  convertForm.warehouseLabel = rawWarehouse
    ? (rawWarehouse.code ? `${rawWarehouse.code} · ${rawWarehouse.name}` : rawWarehouse.name)
    : ''
  convertForm.expectedDate = ''
  convertForm.needInspection = true
  convertForm.remark = ''
  // 只有审批通过（或无需审批）且还有余量的行能转，审批中的行先留着
  const pending = (data.items || []).filter((item: any) => Number(item.remainingQty) > 0)
  const convertable = pending.filter((item: any) =>
    !item.approvalStatus || item.approvalStatus === 'APPROVED' || item.approvalStatus === 'SKIPPED'
  )
  convertBlocked.value = pending.length - convertable.length
  convertForm.items = convertable.map((item: any) => ({
    requisitionItemId: item.id,
    productCode: item.productCode,
    productName: item.productName,
    unit: item.unit,
    remainingQty: Number(item.remainingQty),
    qty: Number(item.remainingQty),
    price: Number(item.targetPrice || 0)
  }))
  if (!convertForm.items.length) {
    ElMessage.warning(convertBlocked.value ? '还没有审批通过、可转的明细' : '这张申请没有待转数量')
    return
  }
  convertDialog.value = true
}

const submitConvert = async () => {
  if (!convertForm.supplierId || !convertForm.warehouseId) {
    ElMessage.warning('请选择供应商与收货仓库')
    return
  }
  for (const item of convertForm.items) {
    if (Number(item.qty) <= 0) {
      ElMessage.warning('转单数量必须大于 0')
      return
    }
    if (Number(item.qty) > Number(item.remainingQty)) {
      ElMessage.warning(`${item.productName} 转单数量超过申请剩余数量`)
      return
    }
  }
  convertLoading.value = true
  try {
    const result = await api.post(`/purchase-requisitions/${convertSource.value.id}/to-order`, {
      supplierId: convertForm.supplierId,
      warehouseId: convertForm.warehouseId,
      expectedDate: convertForm.expectedDate || undefined,
      needInspection: convertForm.needInspection,
      remark: convertForm.remark,
      items: convertForm.items.map((item) => ({
        requisitionItemId: item.requisitionItemId,
        qty: Number(item.qty),
        price: Number(item.price || 0)
      }))
    })
    ElMessage.success(`已生成采购单 ${result.orderNo}，确认后即可收货`)
    convertDialog.value = false
    load()
  } finally {
    convertLoading.value = false
  }
}

const reset = () => {
  keyword.value = ''
  status.value = ''
  sourceType.value = ''
  page.value = 1
  load()
}

onMounted(async () => {
  await table.load()
  await loadOptions()
  // 从消息中心跳过来：按申请单号定位，状态清空避免已处理的申请搜不到
  const fromQuery = String(route.query.keyword || '').trim()
  if (fromQuery) {
    keyword.value = fromQuery
    status.value = ''
    sourceType.value = ''
    page.value = 1
  }
  await load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">采购申请</h2>
        <p class="page-sub">
          需求先立单、按金额分级审批，批准后可以分次转采购单；已转单数量会从申请剩余里扣掉
        </p>
      </div>
      <el-button v-perm="'purchase:request'" type="primary" @click="openCreate">新建申请</el-button>
    </div>

    <div class="req-strip">
      <div class="req-item">
        <span>草稿</span>
        <b>{{ summary.draft || 0 }}</b>
      </div>
      <div class="req-item">
        <span>待审批</span>
        <b class="warn-text">{{ summary.pending || 0 }}</b>
      </div>
      <div class="req-item">
        <span>已批准待转</span>
        <b class="primary-text">{{ summary.toConvert || 0 }}</b>
      </div>
      <div class="req-item">
        <span>本月申请金额</span>
        <b>{{ fmtMoney(summary.monthAmount) }}</b>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="申请单号 / 用途 / 申请人"
          clearable
          style="width: 230px"
          @keyup.enter="load"
        />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待审批" value="SUBMITTED" />
          <el-option label="已批准" value="APPROVED" />
          <el-option label="部分转单" value="PARTIAL" />
          <el-option label="已转单" value="CONVERTED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-select v-model="sourceType" placeholder="全部来源" clearable style="width: 130px" @change="load">
          <el-option label="工单缺料" value="PRODUCTION" />
          <el-option label="库存补货" value="STOCK" />
          <el-option label="非生产" value="MANUAL" />
          <el-option label="紧急采购" value="URGENT" />
        </el-select>
        <el-button type="primary" plain @click="load">查询</el-button>
        <el-button plain @click="reset">重置</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/purchase-requisitions" export-name="采购申请" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 张申请</span>
      </div>

      <div class="table-wrap">
        <DataTable :table="table" :data="rows" :loading="loading">
          <template #cell-reqNo="{ row }"><span class="mono">{{ row.reqNo }}</span></template>
          <template #cell-sourceType="{ row }">
            <span class="status-tag" :class="sourceOf(row.sourceType).cls">
              <i></i>{{ sourceOf(row.sourceType).label }}
            </span>
          </template>
          <template #cell-requester="{ row }">
            <div>{{ row.requesterName }}</div>
            <div class="muted" style="font-size: 11px">{{ row.department || '-' }}</div>
          </template>
          <template #cell-purpose="{ row }">
            <div>{{ row.purpose || '-' }}</div>
            <div v-if="row.workNo" class="muted mono" style="font-size: 11px">{{ row.workNo }}</div>
          </template>
          <template #cell-needDate="{ row }">{{ row.needDate || '-' }}</template>
          <template #cell-itemsQty="{ row }">
            <div>{{ row.itemCount }} 项</div>
            <div class="muted" style="font-size: 11px">{{ fmtQty(row.totalQty) }}</div>
          </template>
          <template #cell-totalAmount="{ row }"><span class="mono">{{ fmtMoney(row.totalAmount) }}</span></template>
          <template #cell-progress="{ row }">
            <div class="mono">{{ fmtQty(row.convertedQty) }} / {{ fmtQty(row.totalQty) }}</div>
          </template>
          <template #cell-status="{ row }">
            <span class="status-tag" :class="statusOf(row.status).cls">
              <i></i>{{ statusOf(row.status).label }}
            </span>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              v-perm="'purchase:request'"
              link
              type="success"
              @click="submit(row)"
            >
              提交
            </el-button>
            <el-button
              v-if="row.status === 'SUBMITTED'"
              v-perm="'purchase:approve'"
              link
              type="success"
              @click="approve(row)"
            >
              通过
            </el-button>
            <el-button
              v-if="row.status === 'SUBMITTED'"
              v-perm="'purchase:approve'"
              link
              type="danger"
              @click="reject(row)"
            >
              驳回
            </el-button>
            <el-button
              v-if="['APPROVED', 'PARTIAL', 'SUBMITTED'].includes(row.status)"
              v-perm="'purchase:create'"
              link
              type="primary"
              @click="openConvert(row)"
            >
              转采购单
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              v-perm="'purchase:request'"
              link
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-dropdown
              v-if="['DRAFT', 'SUBMITTED', 'APPROVED'].includes(row.status)"
            >
              <el-button link>更多</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-perm="'purchase:request'" @click="cancel(row)">
                    取消申请
                  </el-dropdown-item>
                  <el-dropdown-item v-if="row.status === 'DRAFT'" v-perm="'purchase:request'" @click="remove(row)">
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

    <el-dialog draggable append-to-body v-model="dialog" :title="editingId ? '编辑采购申请' : '新建采购申请'" width="960px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="申请来源">
            <el-select v-model="form.sourceType" style="width: 100%">
              <el-option label="工单缺料" value="PRODUCTION" />
              <el-option label="库存补货" value="STOCK" />
              <el-option label="非生产采购" value="MANUAL" />
              <el-option label="紧急采购" value="URGENT" />
            </el-select>
          </el-form-item>
          <el-form-item label="申请部门">
            <el-input v-model="form.department" placeholder="如 计划部 / 生产部" />
          </el-form-item>
          <el-form-item v-if="form.sourceType === 'PRODUCTION'" label="关联工单">
            <el-select v-model="form.workOrderId" filterable clearable style="width: 100%">
              <el-option
                v-for="item in workOrders"
                :key="item.id"
                :label="`${item.workNo} · ${item.productName}`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="采购类别">
            <el-select v-model="form.category" style="width: 100%">
              <el-option label="原辅料" value="MATERIAL" />
              <el-option label="外协加工" value="OUTSOURCE" />
              <el-option label="设备备件" value="SPARE" />
              <el-option label="设备" value="EQUIPMENT" />
              <el-option label="服务" value="SERVICE" />
            </el-select>
          </el-form-item>
          <el-form-item label="是否紧急">
            <el-switch v-model="form.urgent" active-text="紧急" inactive-text="常规" inline-prompt />
            <span class="muted" style="margin-left: 8px; font-size: 12px">紧急单会按紧急链分流</span>
          </el-form-item>
          <el-form-item label="需求日期">
            <el-date-picker v-model="form.needDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="预算科目">
            <el-input v-model="form.budgetNo" placeholder="选填，如：设备维修-2026" />
          </el-form-item>
          <el-form-item label="用途 / 说明" class="full">
            <el-input v-model="form.purpose" placeholder="如：标准件低于安全库存，先补一批" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" type="textarea" :rows="2" />
          </el-form-item>
        </div>

        <div class="item-head">
          <b>申请明细</b>
          <el-button size="small" plain @click="addItem">添加一行</el-button>
          <div class="spacer"></div>
          <span class="muted">预估金额 <b class="mono">{{ fmtMoney(totalAmount) }}</b></span>
        </div>

        <el-table :data="form.items" size="small">
          <el-table-column label="物料" min-width="200">
            <template #default="{ row }">
              <ReferenceSelect v-model="row.productId" url="/options/products"
                               :initial-label="row.productCode ? `${row.productCode} · ${row.productName}` : row.productName"
                               placeholder="输入物料编码或名称搜索"
                               size="small"
                               @select="(product) => productSelected(row, product)" />
            </template>
          </el-table-column>
          <el-table-column label="单位" width="65">
            <template #default="{ row }">{{ row.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="类别" width="120">
            <template #default="{ row }">
              <el-select v-model="row.category" size="small" placeholder="跟随单据" clearable style="width: 100%">
                <el-option label="原辅料" value="MATERIAL" />
                <el-option label="外协" value="OUTSOURCE" />
                <el-option label="备件" value="SPARE" />
                <el-option label="设备" value="EQUIPMENT" />
                <el-option label="服务" value="SERVICE" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="120">
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="0.0001" :precision="2" :controls="false" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="目标单价" width="120">
            <template #default="{ row }">
              <el-input-number v-model="row.targetPrice" :min="0" :precision="2" :controls="false" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="建议供应商" min-width="170">
            <template #default="{ row }">
              <ReferenceSelect v-model="row.suggestSupplierId" url="/suppliers/options"
                               :initial-label="row.suggestSupplierName"
                               placeholder="可不填"
                               size="small"
                               @select="(supplier) => supplierSelected(row, supplier)" />
            </template>
          </el-table-column>
          <el-table-column label="需询价" width="80" align="center">
            <template #default="{ row }"><el-switch v-model="row.needQuote" size="small" /></template>
          </el-table-column>
          <el-table-column label="备注" min-width="120">
            <template #default="{ row }"><el-input v-model="row.remark" size="small" /></template>
          </el-table-column>
          <el-table-column width="60">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeItem($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" size="820px" title="采购申请详情" append-to-body>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <span class="mono strong">{{ detail.reqNo }}</span>
            <span class="status-tag" :class="statusOf(detail.status).cls">
              <i></i>{{ statusOf(detail.status).label }}
            </span>
            <span class="status-tag" :class="sourceOf(detail.sourceType).cls">
              <i></i>{{ sourceOf(detail.sourceType).label }}
            </span>
            <span class="muted">{{ detail.requesterName }} · {{ detail.department || '未填部门' }}</span>
          </div>

          <div class="detail-grid">
            <div><span>需求日期</span><b>{{ detail.needDate || '-' }}</b></div>
            <div><span>预估金额</span><b class="mono">{{ fmtMoney(detail.totalAmount) }}</b></div>
            <div><span>关联工单</span><b class="mono">{{ detail.workNo || '-' }}</b></div>
            <div><span>提交时间</span><b>{{ detail.submitAt || '-' }}</b></div>
            <div><span>审批人</span><b>{{ detail.approvedBy || '-' }}</b></div>
            <div><span>审批时间</span><b>{{ detail.approvedAt || '-' }}</b></div>
          </div>

          <p v-if="detail.purpose" class="detail-purpose">用途：{{ detail.purpose }}</p>
          <p v-if="detail.rejectReason" class="detail-reject">驳回原因：{{ detail.rejectReason }}</p>

          <div class="section-title">申请明细</div>
          <el-table :data="detail.items || []" size="small">
            <el-table-column label="物料" min-width="190" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ row.productName }}</div>
                <div class="muted mono" style="font-size: 11px">{{ row.productCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="90" align="right">
              <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column label="已转" width="80" align="right">
              <template #default="{ row }"><span class="mono">{{ fmtQty(row.convertedQty) }}</span></template>
            </el-table-column>
            <el-table-column label="剩余" width="80" align="right">
              <template #default="{ row }">
                <span :class="Number(row.remainingQty) > 0 ? 'primary-text' : 'muted'">
                  {{ fmtQty(row.remainingQty) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="目标单价" width="100" align="right">
              <template #default="{ row }"><span class="mono">{{ fmtMoney(row.targetPrice) }}</span></template>
            </el-table-column>
            <el-table-column label="建议供应商" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.suggestSupplierName || '-' }}</template>
            </el-table-column>
            <el-table-column label="询价" width="70" align="center">
              <template #default="{ row }">{{ row.needQuote ? '需要' : '不需要' }}</template>
            </el-table-column>
          </el-table>

          <div class="section-title">转出的采购单</div>
          <el-table :data="detail.orders || []" size="small" empty-text="还没有转采购单">
            <el-table-column label="采购单号" width="170">
              <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">{{ row.status }}</template>
            </el-table-column>
            <el-table-column label="金额" width="120" align="right">
              <template #default="{ row }"><span class="mono">{{ fmtMoney(row.totalAmount) }}</span></template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="160" />
          </el-table>

          <p class="detail-note">
            审批通过后才能转采购单；可以分多次转，剩余数量保留。采购单取消或删除时，已转数量会自动退回这张申请。
          </p>
        </template>
      </div>
    </el-drawer>

    <el-dialog draggable append-to-body v-model="convertDialog" title="转采购单" width="900px">
      <div v-if="convertSource" class="convert-head">
        <b class="mono">{{ convertSource.reqNo }}</b>
        <span class="muted">可按行改本次转单数量，剩余数量会保留在申请上</span>
      </div>
      <el-alert
        v-if="convertBlocked"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
        :title="`还有 ${convertBlocked} 行在审批中，这次只转已通过的明细`"
      />
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="供应商" required>
            <ReferenceSelect v-model="convertForm.supplierId" url="/suppliers/options"
                             :initial-label="convertForm.supplierLabel"
                             placeholder="输入供应商编码或名称搜索" />
          </el-form-item>
          <el-form-item label="收货仓库" required>
            <ReferenceSelect v-model="convertForm.warehouseId" url="/options/warehouses"
                             :initial-label="convertForm.warehouseLabel"
                             placeholder="输入仓库编码或名称搜索" />
          </el-form-item>
          <el-form-item label="预计到货">
            <el-date-picker v-model="convertForm.expectedDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="到货检验">
            <el-switch v-model="convertForm.needInspection" active-text="需来料检验" inactive-text="免检" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="convertForm.remark" placeholder="可不填，默认记录来源申请单号" />
          </el-form-item>
        </div>

        <el-table :data="convertForm.items" size="small">
          <el-table-column label="物料" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">
              <div>{{ row.productName }}</div>
              <div class="muted mono" style="font-size: 11px">{{ row.productCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="申请剩余" width="100" align="right">
            <template #default="{ row }">{{ fmtQty(row.remainingQty) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="本次转单" width="140">
            <template #default="{ row }">
              <el-input-number
                v-model="row.qty"
                :min="0"
                :max="row.remainingQty"
                :precision="2"
                :controls="false"
                style="width: 100%"
              />
            </template>
          </el-table-column>
          <el-table-column label="采购单价" width="140">
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
        <div class="convert-total">
          本次转单金额 <b class="mono">{{ fmtMoney(convertAmount) }}</b>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="convertDialog = false">取消</el-button>
        <el-button type="primary" :loading="convertLoading" @click="submitConvert">生成采购单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.req-strip {
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

.req-item span {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.req-item b {
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

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 4px 2px;
}

.item-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 4px 0 8px;
  font-size: 13px;
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

.detail-purpose,
.detail-reject {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.7;
}

.detail-reject {
  color: #b91c1c;
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

.convert-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.convert-total {
  margin-top: 10px;
  text-align: right;
  font-size: 13px;
  color: var(--ink-3);
}
</style>
