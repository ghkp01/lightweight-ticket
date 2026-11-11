<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../api'
import StatusTag from '../components/StatusTag.vue'
import PriorityTag from '../components/PriorityTag.vue'
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
const customFieldKey = ref('')
const customFilterValue = ref<any>('')
const customFieldDefs = ref<any[]>([])
const customValues = reactive<Record<string, any>>({})
const itemFieldDefs = ref<any[]>([])
const itemCustomValues = reactive<Record<string, any>>({})
const itemCustomDialog = ref(false)
const editingItemIndex = ref(-1)

const baseColumns = [
  { key: 'orderNo', label: '订单号', width: 165, fixed: 'left' as const },
  { key: 'quoteNo', label: '来源报价', width: 150 },
  { key: 'customerName', label: '客户', width: 180, flex: true, ellipsis: true },
  { key: 'orderDate', label: '下单日期', width: 105 },
  { key: 'deliveryDate', label: '交期', width: 105 },
  { key: 'priority', label: '优先级', width: 84 },
  { key: 'qty', label: '数量', width: 90, align: 'right' as const },
  { key: 'amount', label: '金额', width: 115, align: 'right' as const },
  { key: 'status', label: '状态', width: 100 },
  { key: 'createdBy', label: '业务员', width: 90 },
  { key: 'actions', label: '操作', width: 230, fixed: 'right' as const }
]

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'sales.orders',
  columns: baseColumns,
  getFilters: () => ({
    keyword: keyword.value,
    status: status.value,
    customFieldKey: customFieldKey.value,
    customFilterValue: customFilterValue.value
  }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    status.value = filters.status || ''
    customFieldKey.value = filters.customFieldKey || ''
    customFilterValue.value = filters.customFilterValue ?? ''
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

const hasFilter = computed(() =>
  Boolean(keyword.value || status.value || customFieldKey.value || customFilterValue.value)
)

// 明细字段用 item: 前缀，和表头字段在同一个筛选下拉里区分
const ITEM_FIELD_PREFIX = 'item:'

const activeCustomField = computed(() => {
  const key = customFieldKey.value
  if (!key) return null
  const itemField = key.startsWith(ITEM_FIELD_PREFIX)
  const fieldKey = itemField ? key.slice(ITEM_FIELD_PREFIX.length) : key
  const source = itemField ? itemFieldDefs.value : customFieldDefs.value
  return source.find((field: any) => field.fieldKey === fieldKey) || null
})

const onCustomFieldChange = () => {
  customFilterValue.value = ''
}

const clearFilters = () => {
  keyword.value = ''
  status.value = ''
  customFieldKey.value = ''
  customFilterValue.value = ''
  reload()
}

const drawer = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive<any>({
  customerId: null,
  customerLabel: '',
  customerPoNo: '',
  contact: '',
  deliveryAddress: '',
  salesOwner: '',
  orderDate: new Date().toISOString().slice(0, 10),
  deliveryDate: '',
  priority: 'NORMAL',
  currency: 'CNY',
  remark: '',
  items: []
})

const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const totalAmount = computed(() =>
  form.items.reduce((sum: number, item: any) => sum + Number(item.qty || 0) * Number(item.price || 0), 0)
)

const currencySymbol = (code: string) => {
  if (code === 'USD') return '$'
  if (code === 'EUR') return '€'
  return '¥'
}

const flattenRecord = (row: any) => {
  const flat: any = { ...row }
  customFieldDefs.value.forEach((field: any) => {
    flat[`cf_${field.fieldKey}`] = customFieldText(field, row.customFields?.[field.fieldKey])
  })
  // 明细行字段：后端已把多行值去重合并成「值1、值2」，这里只做列映射
  itemFieldDefs.value.forEach((field: any) => {
    flat[`icf_${field.fieldKey}`] = row.itemCustomFields?.[field.fieldKey] || '-'
  })
  return flat
}

const flattenRecords = (list: any[]) => list.map(flattenRecord)

const loadItemCustomFields = async () => {
  try {
    itemFieldDefs.value = (await api.get('/custom-fields', { entityType: 'sales_order_item' })) || []
  } catch {
    itemFieldDefs.value = []
  }
}

const resetItemCustomValues = (source: any = {}) => {
  Object.keys(itemCustomValues).forEach((key) => delete itemCustomValues[key])
  itemFieldDefs.value.forEach((field: any) => {
    if (Object.prototype.hasOwnProperty.call(source, field.fieldKey)) {
      itemCustomValues[field.fieldKey] = source[field.fieldKey]
    } else if (field.defaultValue !== null && field.defaultValue !== undefined) {
      itemCustomValues[field.fieldKey] = field.defaultValue
    } else {
      itemCustomValues[field.fieldKey] = field.fieldType === 'SWITCH' ? false : null
    }
  })
}

const openItemCustom = (index: number) => {
  if (!itemFieldDefs.value.length) {
    ElMessage.info('还没有配置订单明细自定义字段')
    return
  }
  editingItemIndex.value = index
  resetItemCustomValues(form.items[index]?.customFields || {})
  itemCustomDialog.value = true
}

const saveItemCustom = () => {
  const item = form.items[editingItemIndex.value]
  if (item) {
    item.customFields = { ...itemCustomValues }
  }
  itemCustomDialog.value = false
}

const itemCustomText = (item: any) => {
  const parts = itemFieldDefs.value
    .map((field: any) => {
      const value = item.customFields?.[field.fieldKey]
      if (value === null || value === undefined || value === '') return null
      return `${field.label}：${customFieldText(field, value)}`
    })
    .filter(Boolean)
  return parts.length ? parts.join('；') : '-'
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/sales-orders', {
      keyword: keyword.value,
      status: status.value,
      customFieldKey: customFieldKey.value || undefined,
      customFieldValue: customFilterValue.value === '' || customFilterValue.value === null
        ? undefined : customFilterValue.value,
      page: page.value,
      size: size.value
    })
    rows.value = flattenRecords(data.records)
    total.value = data.total
  } finally {
    loading.value = false
  }
}

const loadRefs = async () => {
  // 客户、产品改为 ReferenceSelect 远程搜索，不再整表加载
}

const loadCustomFields = async () => {
  try {
    customFieldDefs.value = (await api.get('/custom-fields', { entityType: 'sales_order' })) || []
  } catch {
    customFieldDefs.value = []
  }
  // 明细字段同时用于订单明细弹窗、详情展示和列表可配置列
  await loadItemCustomFields()
  table.setColumns([
    ...baseColumns,
    ...customFieldDefs.value.map((field: any) => ({
      key: `cf_${field.fieldKey}`,
      label: field.label,
      width: 140,
      // 自定义列默认收起，用户在「列设置」里勾选后显示
      visible: false
    })),
    ...itemFieldDefs.value.map((field: any) => ({
      key: `icf_${field.fieldKey}`,
      label: `${field.label}（明细）`,
      width: 160,
      visible: false
    }))
  ])
}

const resetCustomValues = (source: any = {}) => {
  Object.keys(customValues).forEach((key) => delete customValues[key])
  customFieldDefs.value.forEach((field: any) => {
    if (Object.prototype.hasOwnProperty.call(source, field.fieldKey)) {
      customValues[field.fieldKey] = source[field.fieldKey]
    } else if (field.defaultValue !== null && field.defaultValue !== undefined) {
      customValues[field.fieldKey] = field.defaultValue
    } else {
      customValues[field.fieldKey] = field.fieldType === 'SWITCH' ? false : null
    }
  })
}

const customFieldText = (field: any, value: any) => {
  if (value === null || value === undefined || value === '') return '-'
  if (field.fieldType === 'SWITCH') return value ? '是' : '否'
  return String(value)
}

const fieldFormLabel = (field: any) =>
  field.editable === false ? `${field.label}（只读）` : field.label

const openCreate = async () => {
  editingId.value = null
  if (!customFieldDefs.value.length) await loadCustomFields()
  resetCustomValues()
  Object.assign(form, {
    customerId: null,
    customerLabel: '',
    customerPoNo: '',
    contact: '',
    deliveryAddress: '',
    salesOwner: '',
    orderDate: new Date().toISOString().slice(0, 10),
    deliveryDate: '',
    priority: 'NORMAL',
    currency: 'CNY',
    remark: '',
    items: [{ productId: null, qty: 1, price: 0, productName: '', unit: '' }]
  })
  drawer.value = true
}

const openEdit = async (row: any) => {
  const data = await api.get(`/sales-orders/${row.id}`)
  editingId.value = row.id
  if (!customFieldDefs.value.length) await loadCustomFields()
  resetCustomValues(data.customFields || {})
  Object.assign(form, {
    customerId: data.customerId,
    customerLabel: data.customerName || '',
    customerPoNo: data.customerPoNo || '',
    contact: data.contact || '',
    deliveryAddress: data.deliveryAddress || '',
    salesOwner: data.salesOwner || '',
    orderDate: data.orderDate,
    deliveryDate: data.deliveryDate || '',
    priority: data.priority || 'NORMAL',
    currency: data.currency || 'CNY',
    remark: data.remark || '',
    items: data.items.map((item: any) => ({
      productId: item.productId,
      qty: Number(item.qty),
      price: Number(item.price),
      productCode: item.productCode,
      productName: item.productName,
      unit: item.unit,
      customFields: item.customFields || {}
    }))
  })
  drawer.value = true
}

const addItem = () => {
  form.items.push({ productId: null, qty: 1, price: 0, productName: '', unit: '', customFields: {} })
}

const removeItem = (index: number) => {
  form.items.splice(index, 1)
}

const onProductSelect = (item: any, product: any) => {
  if (product) {
    item.productName = product.name
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
    ElMessage.warning('请完整填写订单明细')
    return
  }
  saving.value = true
  try {
    const payload = {
      customerId: form.customerId,
      customerPoNo: form.customerPoNo || null,
      contact: form.contact || null,
      deliveryAddress: form.deliveryAddress || null,
      salesOwner: form.salesOwner || null,
      orderDate: form.orderDate,
      deliveryDate: form.deliveryDate || null,
      priority: form.priority || 'NORMAL',
      currency: form.currency || 'CNY',
      remark: form.remark,
      customFields: { ...customValues },
      items: form.items.map((item: any) => ({
        productId: item.productId,
        qty: Number(item.qty),
        price: Number(item.price),
        customFields: item.customFields || {}
      }))
    }
    if (editingId.value) {
      await api.put(`/sales-orders/${editingId.value}`, payload)
    } else {
      await api.post('/sales-orders', payload)
    }
    ElMessage.success('订单已保存')
    drawer.value = false
    load()
  } finally {
    saving.value = false
  }
}

const confirmOrder = async (row: any) => {
  await ElMessageBox.confirm(`确认审核订单 ${row.orderNo}？审核后可转生产。`, '审核确认')
  await api.post(`/sales-orders/${row.id}/confirm`)
  ElMessage.success('订单已审核')
  load()
}

const cancelOrder = async (row: any) => {
  // 已转生产的订单要先撤工单，这里先说清楚后果，避免点了才被后端拦回来
  const inProduction = String(row.status) === 'IN_PRODUCTION'
  const tip = inProduction
    ? `订单 ${row.orderNo} 已转生产。取消前要求该订单下的工单全部已作废或已关闭，否则会被拦回。确认取消？`
    : `确认取消订单 ${row.orderNo}？`
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(tip, '取消订单', {
      type: 'warning',
      inputPlaceholder: '取消原因（选填），例如：客户临时改需求',
      inputValue: '',
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想'
    })
    reason = String(value || '').trim()
  } catch {
    return
  }
  await api.post(`/sales-orders/${row.id}/cancel?reason=${encodeURIComponent(reason)}`)
  ElMessage.success('订单已取消')
  load()
}

// ---------------------------------------------------------------- 明细级撤单
const itemDialog = ref(false)
const itemLoading = ref(false)
const itemPreview = ref<any>(null)
const itemReason = ref('')
const itemCancelQty = ref<number | null>(null)

const workStatusText: Record<string, string> = {
  DRAFT: '草稿',
  SCHEDULED: '已排产',
  RELEASED: '已下达',
  IN_PROGRESS: '生产中',
  REWORK: '返修中',
  FINISHED: '已完工',
  CLOSED: '已关闭',
  CANCELLED: '已作废'
}

const loadItemPreview = async (orderId: number, itemId: number) => {
  itemLoading.value = true
  try {
    const query = itemCancelQty.value === null ? '' : `?qty=${itemCancelQty.value}`
    itemPreview.value = await api.get(`/sales-orders/${orderId}/items/${itemId}/cancel-preview${query}`)
  } finally {
    itemLoading.value = false
  }
}

const openItemCancel = async (row: any) => {
  itemDialog.value = true
  itemPreview.value = null
  itemReason.value = ''
  itemCancelQty.value = Number(row.qty || 0)
  try {
    await loadItemPreview(detail.value.id, row.id)
  } catch {
    itemDialog.value = false
  }
}

// 改取消数量后重算预检：整行取消是「作废工单」，只砍一部分是「工单缩量」
const onCancelQtyChange = async () => {
  if (!itemPreview.value || itemCancelQty.value === null) return
  if (itemCancelQty.value <= 0) return
  await loadItemPreview(itemPreview.value.orderId, itemPreview.value.itemId)
}

const submitItemCancel = async () => {
  const preview = itemPreview.value
  if (!preview?.cancellable) return
  const whole = preview.mode === 'WHOLE'
  const works = (preview.workOrders || []).filter((item: any) => item.action !== 'NONE')
  let tip = ''
  if (whole) {
    tip = works.length
      ? `取消「${preview.productName}」这一行，并连带作废 ${works.length} 张工单：${works.map((w: any) => w.workNo).join('、')}。确认？`
      : `取消「${preview.productName}」这一行？`
  } else {
    const resize = works.length
      ? `工单 ${works.map((w: any) => w.workNo).join('、')} 的计划量会缩到 ${fmtQty(preview.remainingQty)}，`
      : ''
    tip = `「${preview.productName}」取消 ${fmtQty(preview.cancelQty)} 件、保留 ${fmtQty(preview.remainingQty)} 件。${resize}确认？`
  }
  try {
    await ElMessageBox.confirm(tip, whole ? '取消单行' : '取消部分数量', {
      type: 'warning',
      confirmButtonText: '确认取消'
    })
  } catch {
    return
  }
  const params = new URLSearchParams({ reason: itemReason.value.trim() })
  if (!whole) {
    params.set('qty', String(preview.cancelQty))
  }
  await api.post(
    `/sales-orders/${preview.orderId}/items/${preview.itemId}/cancel?${params.toString()}`
  )
  ElMessage.success(whole ? '已取消该行' : '已按新数量调整这一行')
  itemDialog.value = false
  const orderId = detail.value.id
  await openDetail({ id: orderId })
  load()
}

const removeOrder = async (row: any) => {
  await ElMessageBox.confirm(`确认删除草稿订单 ${row.orderNo}？`, '删除确认', { type: 'warning' })
  await api.delete(`/sales-orders/${row.id}`)
  ElMessage.success('已删除')
  load()
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await api.get(`/sales-orders/${row.id}`)
  } finally {
    detailLoading.value = false
  }
}

const changePage = (value: number) => {
  page.value = value
  load()
}

onMounted(async () => {
  await loadCustomFields()
  await table.load()
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">销售订单</h2>
        <p class="page-sub">从接单到转生产，订单状态与履约进度都在这里</p>
      </div>
      <el-button v-perm="'sales:create'" type="primary" @click="openCreate">新建订单</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="订单号 / 客户名称" clearable style="width: 240px" @keyup.enter="load" />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="生产中" value="IN_PRODUCTION" />
          <el-option label="待发货" value="READY_TO_SHIP" />
          <el-option label="已发货" value="SHIPPED" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-select
          v-model="customFieldKey"
          placeholder="自定义字段"
          clearable
          style="width: 160px"
          @change="onCustomFieldChange"
        >
          <el-option-group v-if="customFieldDefs.length" label="订单字段">
            <el-option
              v-for="field in customFieldDefs"
              :key="field.fieldKey"
              :label="field.label"
              :value="field.fieldKey"
            />
          </el-option-group>
          <el-option-group v-if="itemFieldDefs.length" label="明细字段">
            <el-option
              v-for="field in itemFieldDefs"
              :key="`item:${field.fieldKey}`"
              :label="`${field.label}（明细）`"
              :value="`item:${field.fieldKey}`"
            />
          </el-option-group>
        </el-select>
        <template v-if="activeCustomField">
          <el-select
            v-if="activeCustomField.fieldType === 'SELECT' || activeCustomField.fieldType === 'SWITCH'"
            v-model="customFilterValue"
            :placeholder="`${activeCustomField.label}的值`"
            clearable
            style="width: 150px"
            @change="load"
          >
            <template v-if="activeCustomField.fieldType === 'SWITCH'">
              <el-option label="是" value="true" />
              <el-option label="否" value="false" />
            </template>
            <el-option
              v-for="option in activeCustomField.options || []"
              :key="option"
              :label="option"
              :value="option"
            />
          </el-select>
          <el-date-picker
            v-else-if="activeCustomField.fieldType === 'DATE'"
            v-model="customFilterValue"
            type="date"
            value-format="YYYY-MM-DD"
            :placeholder="activeCustomField.label"
            style="width: 150px"
            @change="load"
          />
          <el-input
            v-else
            v-model="customFilterValue"
            :placeholder="`${activeCustomField.label}的值`"
            clearable
            style="width: 150px"
            @keyup.enter="load"
          />
        </template>
        <el-button type="primary" plain @click="load">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar
          :table="table"
          export-api="/sales-orders"
          export-name="销售订单"
          :export-transform="flattenRecords"
          @applied="reload"
        />
        <span class="toolbar-tip">共 {{ total }} 张订单</span>
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
          <template #cell-orderNo="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          <template #cell-deliveryDate="{ row }"><span class="mono">{{ row.deliveryDate || '-' }}</span></template>
          <template #cell-priority="{ row }"><PriorityTag :priority="row.priority" /></template>
          <template #cell-qty="{ row }"><span class="num">{{ fmtQty(row.totalQty) }}</span></template>
          <template #cell-amount="{ row }"><span class="money">{{ currencySymbol(row.currency) }}{{ fmtMoney(row.totalAmount) }}</span></template>
          <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <template v-if="row.status === 'DRAFT'">
              <el-button v-perm="'sales:update'" link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-perm="'sales:confirm'" link type="success" @click="confirmOrder(row)">审核</el-button>
              <el-button v-perm="'sales:delete'" link type="danger" @click="removeOrder(row)">删除</el-button>
            </template>
            <template v-else-if="row.status === 'CONFIRMED'">
              <el-button v-perm="'sales:cancel'" link type="danger" @click="cancelOrder(row)">取消</el-button>
            </template>
            <template v-else-if="row.status === 'IN_PRODUCTION'">
              <!-- 已转生产也能撤回：工单全部作废/关闭后订单才真正取消 -->
              <el-button v-perm="'sales:cancel'" link type="danger" @click="cancelOrder(row)">取消订单</el-button>
            </template>
          </template>

          <template #empty>
            <div class="empty-state">
              <div class="empty-state-icon">单</div>
              <div class="empty-state-title">{{ hasFilter ? '没有符合条件的销售订单' : '还没有销售订单' }}</div>
              <div class="empty-state-desc">
                {{
                  hasFilter
                    ? '换个状态或关键词再查一次，也可以清掉筛选看全部订单。'
                    : '一张订单从这里开始：建单 → 审核 → 转生产，后面领料、报工、发货都跟着它走。'
                }}
              </div>
              <div class="empty-state-actions">
                <el-button v-if="hasFilter" @click="clearFilters">清空筛选条件</el-button>
                <el-button v-else v-perm="'sales:create'" type="primary" @click="openCreate">新建销售订单</el-button>
              </div>
            </div>
          </template>
        </DataTable>
      </div>
    </div>

    <el-drawer append-to-body v-model="drawer" size="820px" :title="editingId ? '编辑销售订单' : '新建销售订单'">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="客户" required>
            <ReferenceSelect v-model="form.customerId" url="/options/customers"
                             :initial-label="form.customerLabel"
                             placeholder="输入客户编码或名称搜索" />
          </el-form-item>
          <el-form-item label="客户 PO 号">
            <el-input v-model="form.customerPoNo" placeholder="客户方单号，对账与打印用" />
          </el-form-item>
          <el-form-item label="下单日期" required>
            <el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="交付日期">
            <el-date-picker v-model="form.deliveryDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="优先级">
            <el-select v-model="form.priority" style="width: 100%">
              <el-option label="普通" value="NORMAL" />
              <el-option label="加急" value="URGENT" />
              <el-option label="特急" value="RUSH" />
            </el-select>
          </el-form-item>
          <el-form-item label="币种">
            <el-select v-model="form.currency" style="width: 100%">
              <el-option label="人民币 CNY" value="CNY" />
              <el-option label="美元 USD" value="USD" />
              <el-option label="欧元 EUR" value="EUR" />
            </el-select>
          </el-form-item>
          <el-form-item label="跟单人">
            <el-input v-model="form.salesOwner" placeholder="负责这张单的人" />
          </el-form-item>
          <el-form-item label="客户联系人">
            <el-input v-model="form.contact" placeholder="收货对接人 / 电话" />
          </el-form-item>
          <el-form-item label="交货地址" class="full">
            <el-input v-model="form.deliveryAddress" placeholder="送货到哪里，发货单可直接带出" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" placeholder="交期要求、包装说明等" />
          </el-form-item>
        </div>

        <div v-if="customFieldDefs.length" class="drawer-section">
          <h4>扩展信息</h4>
          <div class="form-grid">
            <el-form-item
              v-for="field in customFieldDefs"
              :key="field.fieldKey"
              :label="fieldFormLabel(field)"
              :required="field.required && field.editable !== false"
              :class="{ full: field.fieldType === 'TEXTAREA' }"
            >
              <el-input
                v-if="field.fieldType === 'TEXT'"
                v-model="customValues[field.fieldKey]"
                :disabled="field.editable === false"
                clearable
              />
              <el-input
                v-else-if="field.fieldType === 'TEXTAREA'"
                v-model="customValues[field.fieldKey]"
                type="textarea"
                :rows="3"
                :disabled="field.editable === false"
              />
              <el-input-number
                v-else-if="field.fieldType === 'NUMBER'"
                v-model="customValues[field.fieldKey]"
                :precision="2"
                :disabled="field.editable === false"
                style="width: 100%"
              />
              <el-date-picker
                v-else-if="field.fieldType === 'DATE'"
                v-model="customValues[field.fieldKey]"
                type="date"
                value-format="YYYY-MM-DD"
                :disabled="field.editable === false"
                style="width: 100%"
              />
              <el-select
                v-else-if="field.fieldType === 'SELECT'"
                v-model="customValues[field.fieldKey]"
                clearable
                :disabled="field.editable === false"
                style="width: 100%"
              >
                <el-option v-for="option in field.options || []" :key="option" :label="option" :value="option" />
              </el-select>
              <el-switch
                v-else-if="field.fieldType === 'SWITCH'"
                v-model="customValues[field.fieldKey]"
                :disabled="field.editable === false"
              />
            </el-form-item>
          </div>
        </div>

        <div class="drawer-section">
          <h4>
            <span>订单明细</span>
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
            <el-table-column label="数量" width="120">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="0.01" :precision="2" size="small" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="单价" width="130">
              <template #default="{ row }">
                <el-input-number v-model="row.price" :min="0" :precision="2" size="small" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="金额" width="110" align="right">
              <template #default="{ row }">
                <span class="money">{{ currencySymbol(form.currency) }}{{ fmtMoney(Number(row.qty || 0) * Number(row.price || 0)) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ $index }">
                <el-button v-if="itemFieldDefs.length" link type="primary" @click="openItemCustom($index)">扩展</el-button>
                <el-button link type="danger" @click="removeItem($index)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="order-total">
            <span>合计</span>
            <b class="money">{{ currencySymbol(form.currency) }}{{ fmtMoney(totalAmount) }}</b>
          </div>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存订单</el-button>
      </template>
    </el-drawer>

    <el-drawer append-to-body v-model="detailDrawer" size="760px" title="订单详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-no mono">{{ detail.orderNo }}</div>
              <div class="muted" style="margin-top: 4px">{{ detail.customerName }}</div>
            </div>
            <StatusTag :status="detail.status" />
          </div>

          <div class="detail-grid">
            <div class="detail-item"><div class="label">客户 PO 号</div><div class="value mono">{{ detail.customerPoNo || '-' }}</div></div>
            <div class="detail-item"><div class="label">来源报价单</div><div class="value mono">{{ detail.quoteNo || '-' }}</div></div>
            <div class="detail-item"><div class="label">下单日期</div><div class="value">{{ detail.orderDate }}</div></div>
            <div class="detail-item"><div class="label">交付日期</div><div class="value">{{ detail.deliveryDate || '-' }}</div></div>
            <div class="detail-item"><div class="label">跟单人</div><div class="value">{{ detail.salesOwner || detail.createdBy || '-' }}</div></div>
            <div class="detail-item">
              <div class="label">联系人</div>
              <div class="value">{{ detail.orderContact || detail.contact || '-' }} {{ detail.phone || '' }}</div>
            </div>
            <div class="detail-item"><div class="label">合计数量</div><div class="value num">{{ fmtQty(detail.totalQty) }}</div></div>
            <div class="detail-item"><div class="label">币种</div><div class="value">{{ detail.currency || 'CNY' }}</div></div>
            <div class="detail-item"><div class="label">合计金额</div><div class="value money">{{ currencySymbol(detail.currency) }}{{ fmtMoney(detail.totalAmount) }}</div></div>
            <div class="detail-item" style="grid-column: span 3">
              <div class="label">交货地址</div>
              <div class="value">{{ detail.deliveryAddress || detail.address || '-' }}</div>
            </div>
            <div class="detail-item" style="grid-column: span 3">
              <div class="label">备注</div>
              <div class="value">{{ detail.remark || '-' }}</div>
            </div>
            <div v-if="detail.status === 'CANCELLED'" class="detail-item" style="grid-column: span 3">
              <div class="label">取消原因</div>
              <div class="value danger-text">{{ detail.cancelReason || '未填原因' }}</div>
            </div>
          </div>

          <div v-if="customFieldDefs.length && detail.customFields" class="drawer-section">
            <h4>扩展信息</h4>
            <div class="detail-grid">
              <div v-for="field in customFieldDefs" :key="field.fieldKey" class="detail-item">
                <div class="label">{{ field.label }}</div>
                <div class="value">{{ customFieldText(field, detail.customFields[field.fieldKey]) }}</div>
              </div>
            </div>
          </div>

          <div class="drawer-section">
            <h4>订单明细</h4>
            <el-table :data="detail.items" size="small">
              <el-table-column prop="productCode" label="编码" width="80" />
              <el-table-column prop="productName" label="产品" min-width="150" />
              <el-table-column prop="spec" label="规格" min-width="110" />
              <el-table-column v-if="itemFieldDefs.length" label="扩展信息" min-width="160">
                <template #default="{ row }"><span class="muted">{{ itemCustomText(row) }}</span></template>
              </el-table-column>
              <el-table-column label="数量" width="80" align="right">
                <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
              </el-table-column>
              <el-table-column label="单价" width="90" align="right">
                <template #default="{ row }">{{ currencySymbol(detail.currency) }}{{ fmtMoney(row.price) }}</template>
              </el-table-column>
              <el-table-column label="金额" width="100" align="right">
                <template #default="{ row }"><span class="money">{{ currencySymbol(detail.currency) }}{{ fmtMoney(row.amount) }}</span></template>
              </el-table-column>
              <el-table-column label="已发 / 未发" width="110" align="right">
                <template #default="{ row }">
                  <span class="num">{{ fmtQty(row.shippedQty) }} / {{ fmtQty(row.remainingQty) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tooltip v-if="row.status === 'CANCELLED'" :content="row.cancelReason || '未填原因'" placement="top">
                    <el-tag type="info" size="small">已取消</el-tag>
                  </el-tooltip>
                  <el-tag v-else type="success" size="small">正常</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="96" align="center">
                <template #default="{ row }">
                  <el-button
                    v-if="row.status !== 'CANCELLED' && ['CONFIRMED', 'IN_PRODUCTION'].includes(detail.status)"
                    v-perm="'sales:cancel'"
                    link
                    type="danger"
                    @click="openItemCancel(row)"
                  >取消该行</el-button>
                  <span v-else class="muted">-</span>
                </template>
              </el-table-column>
            </el-table>
            <div class="muted" style="margin-top: 8px; font-size: 12px">
              客户只砍其中几行时用「取消该行」：系统会连带作废这一行自己的工单，并把订单合计改成剩余有效行的金额。
            </div>
          </div>
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="itemCustomDialog" title="订单明细扩展信息" width="560px" append-to-body>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item
            v-for="field in itemFieldDefs"
            :key="field.fieldKey"
            :label="fieldFormLabel(field)"
            :required="field.required && field.editable !== false"
            :class="{ full: field.fieldType === 'TEXTAREA' }"
          >
            <el-input
              v-if="field.fieldType === 'TEXT'"
              v-model="itemCustomValues[field.fieldKey]"
              :disabled="field.editable === false"
              clearable
            />
            <el-input
              v-else-if="field.fieldType === 'TEXTAREA'"
              v-model="itemCustomValues[field.fieldKey]"
              type="textarea"
              :rows="3"
              :disabled="field.editable === false"
            />
            <el-input-number
              v-else-if="field.fieldType === 'NUMBER'"
              v-model="itemCustomValues[field.fieldKey]"
              :precision="2"
              :disabled="field.editable === false"
              style="width: 100%"
            />
            <el-date-picker
              v-else-if="field.fieldType === 'DATE'"
              v-model="itemCustomValues[field.fieldKey]"
              type="date"
              value-format="YYYY-MM-DD"
              :disabled="field.editable === false"
              style="width: 100%"
            />
            <el-select
              v-else-if="field.fieldType === 'SELECT'"
              v-model="itemCustomValues[field.fieldKey]"
              clearable
              :disabled="field.editable === false"
              style="width: 100%"
            >
              <el-option v-for="option in field.options || []" :key="option" :label="option" :value="option" />
            </el-select>
            <el-switch
              v-else-if="field.fieldType === 'SWITCH'"
              v-model="itemCustomValues[field.fieldKey]"
              :disabled="field.editable === false"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="itemCustomDialog = false">取消</el-button>
        <el-button type="primary" @click="saveItemCustom">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="itemDialog" title="取消订单明细" width="760px" append-to-body>
      <div v-loading="itemLoading" style="min-height: 120px">
        <template v-if="itemPreview">
          <div class="detail-grid" style="margin-bottom: 14px">
            <div class="detail-item">
              <div class="label">产品</div>
              <div class="value">{{ itemPreview.productCode }} · {{ itemPreview.productName }}</div>
            </div>
            <div class="detail-item">
              <div class="label">数量 / 金额</div>
              <div class="value">{{ fmtQty(itemPreview.qty) }} / {{ currencySymbol(form.currency) }}{{ fmtMoney(itemPreview.amount) }}</div>
            </div>
            <div class="detail-item">
              <div class="label">已发货</div>
              <div class="value">{{ fmtQty(itemPreview.shippedQty) }}</div>
            </div>
          </div>

          <el-alert
            v-if="!itemPreview.cancellable"
            type="error"
            :closable="false"
            show-icon
            title="这一行现在还不能取消"
          >
            <div v-for="(msg, idx) in itemPreview.blockers" :key="idx" style="line-height: 1.7">{{ msg }}</div>
          </el-alert>
          <el-alert
            v-else
            type="warning"
            :closable="false"
            show-icon
            title="确认后会连带作废这一行的工单，动作不可撤销"
          />

          <div
            v-if="itemPreview.cancellable"
            style="display: flex; align-items: center; gap: 12px; margin: 14px 0 4px"
          >
            <span>本次取消数量</span>
            <el-input-number
              v-model="itemCancelQty"
              :min="0"
              :max="Number(itemPreview.qty)"
              :step="1"
              :precision="0"
              @change="onCancelQtyChange"
            />
            <span class="muted">
              这一行共 {{ fmtQty(itemPreview.qty) }}，取消后保留 {{ fmtQty(itemPreview.remainingQty) }}；
              减到 0 就是整行取消
            </span>
          </div>

          <div v-if="(itemPreview.workOrders || []).length" class="drawer-section">
            <h4>关联工单</h4>
            <el-table :data="itemPreview.workOrders" size="small">
              <el-table-column prop="workNo" label="工单号" width="170" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">{{ workStatusText[row.status] || row.status }}</template>
              </el-table-column>
              <el-table-column label="本次动作" width="110">
                <template #default="{ row }">
                  <el-tag :type="row.cancellable ? 'warning' : 'danger'" size="small">
                    {{ row.action === 'CANCEL' ? '作废工单' : row.action === 'RESIZE' ? '缩量' : '不处理' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="卡在哪">
                <template #default="{ row }">
                  <span :class="row.cancellable ? 'muted' : 'danger-text'">
                    {{ (row.blockers || []).join('；') || '-' }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div v-if="(itemPreview.pendingReturns || []).length" class="drawer-section">
            <h4>待退料清单</h4>
            <el-table :data="itemPreview.pendingReturns" size="small">
              <el-table-column prop="workNo" label="工单号" width="170" />
              <el-table-column prop="materialCode" label="物料编码" width="100" />
              <el-table-column prop="materialName" label="物料" min-width="140" />
              <el-table-column label="已领" width="90" align="right">
                <template #default="{ row }">{{ fmtQty(row.issuedQty) }} {{ row.unit }}</template>
              </el-table-column>
              <el-table-column label="已退" width="90" align="right">
                <template #default="{ row }">{{ fmtQty(row.returnedQty) }}</template>
              </el-table-column>
              <el-table-column label="待退" width="90" align="right">
                <template #default="{ row }">
                  <span class="num danger-text">{{ fmtQty(row.returnableQty) }}</span>
                </template>
              </el-table-column>
            </el-table>
            <div class="muted" style="margin-top: 8px; font-size: 12px">
              这些料已领未退。先到「生产工单 → 退料」做完退料，再回来取消这一行，库存与成本才对得上。
            </div>
          </div>

          <el-input
            v-if="itemPreview.cancellable"
            v-model="itemReason"
            maxlength="120"
            placeholder="取消原因（选填），例如：客户取消这一种货"
            style="margin-top: 16px"
          />
        </template>
      </div>
      <template #footer>
        <el-button @click="itemDialog = false">关闭</el-button>
        <el-button
          v-if="itemPreview?.cancellable"
          type="danger"
          :loading="itemLoading"
          @click="submitItemCancel"
        >确认取消该行</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<style scoped>
.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line);
}

.detail-no {
  font-size: 18px;
  font-weight: 600;
}

.order-total {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  padding: 14px 4px 0;
  border-top: 1px dashed var(--line);
  margin-top: 10px;
  font-size: 12px;
  color: var(--muted);
}

.order-total b {
  font-size: 20px;
  color: var(--ink);
}
</style>
