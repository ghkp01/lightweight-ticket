<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney, fmtQty, nextCode } from '../api'
import InspectionItemEditor from '../components/InspectionItemEditor.vue'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import ReferenceSelect from '../components/ReferenceSelect.vue'
import { useTableView } from '../composables/useTableView'

const rows = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const category = ref('')
const customFieldKey = ref('')
const customFilterValue = ref<any>('')
const customFieldDefs = ref<any[]>([])
const customValues = reactive<Record<string, any>>({})
const page = ref(1)
const size = ref(20)
const total = ref(0)
const router = useRouter()

const baseColumns = [
  { key: 'code', label: '编码', width: 95 },
  { key: 'name', label: '产品名称', width: 160, flex: true, ellipsis: true },
  { key: 'spec', label: '规格', width: 130, flex: true, ellipsis: true },
  { key: 'category', label: '类型', width: 90 },
  { key: 'source', label: '默认来源', width: 95 },
  { key: 'leadDays', label: '提前期', width: 80, align: 'right' as const },
  { key: 'unit', label: '单位', width: 60 },
  { key: 'price', label: '销售价', width: 95, align: 'right' as const },
  { key: 'stock', label: '库存', width: 100, align: 'right' as const },
  { key: 'safetyStock', label: '安全库存', width: 90, align: 'right' as const },
  { key: 'routing', label: '工艺路线', width: 100, prop: 'routingCount' },
  { key: 'actions', label: '操作', width: 300, fixed: 'right' as const }
]

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'products',
  columns: baseColumns,
  getFilters: () => ({
    keyword: keyword.value,
    category: category.value,
    customFieldKey: customFieldKey.value,
    customFilterValue: customFilterValue.value
  }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    category.value = filters.category || ''
    customFieldKey.value = filters.customFieldKey || ''
    customFilterValue.value = filters.customFilterValue ?? ''
  },
  getPageSize: () => size.value,
  setPageSize: (value) => { size.value = value }
})

const reload = () => {
  page.value = 1
  load()
}

const dialog = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive({
  code: '',
  name: '',
  spec: '',
  category: 'FINISHED',
  unit: '件',
  price: 0,
  cost: 0,
  safetyStock: 0,
  lossRate: 0,
  source: 'MAKE',
  leadDays: 0,
  traceMode: 'NONE',
  defaultSupplierId: null,
  supplierLabel: ''
})

const bomDrawer = ref(false)
const bomProduct = ref<any>(null)
const bomRows = ref<any[]>([])
const bomLoading = ref(false)
const bomRouting = ref<any[]>([])

const routingDrawer = ref(false)
const routingProduct = ref<any>(null)
const routingRows = ref<any[]>([])
const routingLoading = ref(false)
const routingSaving = ref(false)
const processOptions = ref<any[]>([])

// 工艺路线变更记录
const routingChanges = ref<any[]>([])
const routingChangesLoading = ref(false)
const routingChangesActive = ref<string[]>([])

// 复制其他产品路线
const copyDialog = ref(false)
const copySaving = ref(false)
const copyForm = reactive<{ sourceProductId: number | null; overwrite: boolean }>({
  sourceProductId: null,
  overwrite: false
})

const routingActionMap: Record<string, { label: string; cls: string }> = {
  INIT: { label: '初始化', cls: 'primary' },
  UPDATE: { label: '调整', cls: 'warning' },
  COPY: { label: '复制', cls: 'success' }
}

// SOP / SIP 等作业与检验指导书
const docDrawer = ref(false)
const docProduct = ref<any>(null)
const docRows = ref<any[]>([])
const docLoading = ref(false)
const docDialog = ref(false)
const docSaving = ref(false)
const editingDocId = ref<number | null>(null)
const docForm = reactive({
  docType: 'SOP',
  title: '',
  version: 'V1.0',
  processId: null as number | null,
  processLabel: '',
  content: '',
  fileUrl: '',
  enabled: true
})

const docTypeMap: Record<string, { label: string; cls: string }> = {
  SOP: { label: 'SOP 作业指导', cls: 'primary' },
  SIP: { label: 'SIP 检验指导', cls: 'success' },
  DRAWING: { label: '图纸', cls: 'info' },
  PACKING: { label: '包装规范', cls: 'warning' },
  OTHER: { label: '其他', cls: 'info' }
}
const docTypeOf = (type: any) => docTypeMap[String(type || '')] || { label: String(type || '-'), cls: 'info' }

const inspectionModeMap: Record<string, { label: string; cls: string }> = {
  NONE: { label: '不检验', cls: 'info' },
  ATTACHED: { label: '工序内检验点', cls: 'warning' },
  ROUTING: { label: '独立检验工序', cls: 'success' }
}
const modeOf = (processId: any) => {
  const proc = processOptions.value.find((item: any) => Number(item.id) === Number(processId))
  return inspectionModeMap[String(proc?.inspectionMode || 'NONE')] || inspectionModeMap.NONE
}

const openDocs = async (row: any) => {
  docProduct.value = row
  docDrawer.value = true
  await loadDocs()
}

const loadDocs = async () => {
  if (!docProduct.value) return
  docLoading.value = true
  try {
    docRows.value = await api.get(`/products/${docProduct.value.id}/documents`)
  } finally {
    docLoading.value = false
  }
}

const openDocCreate = () => {
  editingDocId.value = null
  Object.assign(docForm, {
    docType: 'SOP',
    title: '',
    version: 'V1.0',
    processId: null,
    processLabel: '',
    content: '',
    fileUrl: '',
    enabled: true
  })
  docDialog.value = true
}

const openDocEdit = (row: any) => {
  editingDocId.value = row.id
  Object.assign(docForm, {
    docType: row.docType,
    title: row.title,
    version: row.version,
    processId: row.processId,
    processLabel: row.processName || '',
    content: row.content || '',
    fileUrl: row.fileUrl || '',
    enabled: row.enabled !== false
  })
  docDialog.value = true
}

const saveDoc = async () => {
  if (!docForm.title.trim()) {
    ElMessage.warning('请填写文档标题')
    return
  }
  docSaving.value = true
  try {
    if (editingDocId.value) {
      await api.put(`/documents/${editingDocId.value}`, docForm)
    } else {
      await api.post(`/products/${docProduct.value.id}/documents`, docForm)
    }
    ElMessage.success('已保存')
    docDialog.value = false
    loadDocs()
  } finally {
    docSaving.value = false
  }
}

const removeDoc = async (row: any) => {
  await ElMessageBox.confirm(`确定删除文档「${row.title}」？`, '删除确认', { type: 'warning' })
  await api.delete(`/documents/${row.id}`)
  ElMessage.success('已删除')
  loadDocs()
}

const routingAction = (action: any) =>
  routingActionMap[String(action || '')] || { label: String(action || '-'), cls: 'info' }

const categoryMap: Record<string, { label: string; cls: string }> = {
  RAW: { label: '原料', cls: 'warning' },
  SEMI: { label: '半成品', cls: 'primary' },
  FINISHED: { label: '成品', cls: 'success' },
  PURCHASED: { label: '外购件', cls: 'info' }
}

const sourceMap: Record<string, { label: string; cls: string }> = {
  MAKE: { label: '自制', cls: 'success' },
  BUY: { label: '外购', cls: 'warning' }
}

const defaultSourceOf = (category: string) =>
  ['RAW', 'PURCHASED'].includes(category) ? 'BUY' : 'MAKE'

const onCategoryChange = () => {
  form.source = defaultSourceOf(form.category)
}

const hasBomRouting = computed(() => bomRouting.value.length > 0)

const activeCustomField = computed(() =>
  customFieldDefs.value.find((field: any) => field.fieldKey === customFieldKey.value) || null
)

const onCustomFieldChange = () => {
  customFilterValue.value = ''
}

const loadCustomFields = async () => {
  try {
    customFieldDefs.value = (await api.get('/custom-fields', { entityType: 'product' })) || []
  } catch {
    customFieldDefs.value = []
  }
  table.setColumns([
    ...baseColumns,
    ...customFieldDefs.value.map((field: any) => ({
      key: `cf_${field.fieldKey}`,
      label: field.label,
      width: 140,
      visible: false
    }))
  ])
}

const customFieldText = (field: any, value: any) => {
  if (value === null || value === undefined || value === '') return '-'
  if (field.fieldType === 'SWITCH') return value ? '是' : '否'
  return String(value)
}

const fieldFormLabel = (field: any) =>
  field.editable === false ? `${field.label}（只读）` : field.label

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

const flattenRecord = (row: any) => {
  const flat: any = { ...row }
  customFieldDefs.value.forEach((field: any) => {
    flat[`cf_${field.fieldKey}`] = customFieldText(field, row.customFields?.[field.fieldKey])
  })
  return flat
}

const flattenRecords = (list: any[]) => list.map(flattenRecord)

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/products', {
      keyword: keyword.value,
      category: category.value,
      customFieldKey: customFieldKey.value || undefined,
      customFieldValue: customFilterValue.value === '' || customFilterValue.value === null
        ? undefined : customFilterValue.value,
      page: page.value,
      size: size.value
    })
    rows.value = flattenRecords(data?.records || [])
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

const openCreate = async () => {
  editingId.value = null
  if (!customFieldDefs.value.length) await loadCustomFields()
  resetCustomValues()
  Object.assign(form, {
    code: '',
    name: '',
    spec: '',
    category: 'FINISHED',
    unit: '件',
    price: 0,
    cost: 0,
    safetyStock: 0,
    lossRate: 0,
    source: 'MAKE',
    leadDays: 0,
    traceMode: 'NONE',
    defaultSupplierId: null,
    supplierLabel: ''
  })
  try {
    form.code = await nextCode('PD')
  } catch {
    form.code = ''
  }
  dialog.value = true
}

const openEdit = async (row: any) => {
  editingId.value = row.id
  resetCustomValues(row.customFields || {})
  let supplierLabel = ''
  if (row.defaultSupplierId) {
    try {
      const supplier = await api.get(`/suppliers/${row.defaultSupplierId}`)
      supplierLabel = supplier.code ? `${supplier.code} · ${supplier.name}` : (supplier.name || '')
    } catch {
      supplierLabel = ''
    }
  }
  Object.assign(form, {
    code: row.code,
    name: row.name,
    spec: row.spec,
    category: row.category,
    unit: row.unit,
    price: Number(row.price),
    cost: Number(row.cost),
    safetyStock: Number(row.safetyStock),
    lossRate: Number(row.lossRate),
    source: row.source || defaultSourceOf(row.category),
    leadDays: Number(row.leadDays || 0),
    // 老数据没有 traceMode 时按单件开关兜底
    traceMode: row.traceMode || (row.serialManaged ? 'SERIAL' : 'NONE'),
    defaultSupplierId: row.defaultSupplierId ?? null,
    supplierLabel
  })
  dialog.value = true
}

const save = async () => {
  if (!form.name) {
    ElMessage.warning('产品名称不能为空')
    return
  }
  saving.value = true
  try {
    const payload: any = { ...form, customFields: { ...customValues } }
    delete payload.supplierLabel
    if (editingId.value) {
      await api.put(`/products/${editingId.value}`, payload)
    } else {
      await api.post('/products', payload)
    }
    ElMessage.success('保存成功')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(`确认删除产品「${row.name}」？已被订单或 BOM 引用的产品无法删除。`, '删除确认', {
    type: 'warning'
  })
  await api.delete(`/products/${row.id}`)
  ElMessage.success('已删除')
  load()
}

/** 已选工序缓存：只为当前产品路线和本次选中的工序保留标签/检验方式，不再整表加载 */
const rememberProcess = (process: any) => {
  if (!process) return
  if (!processOptions.value.some((item: any) => Number(item.id) === Number(process.id))) {
    processOptions.value = [...processOptions.value, process]
  }
}

const mergeRoutingProcesses = (data: any[]) => {
  const exists = new Set(processOptions.value.map((item: any) => item.id))
  const extra: any[] = []
  data.forEach((item: any) => {
    if (!item.processId || exists.has(item.processId)) return
    exists.add(item.processId)
    extra.push({
      id: item.processId,
      code: item.processCode,
      name: item.processName,
      defaultMinutes: item.minutesPerPiece,
      defaultPrice: item.unitPrice,
      needInspection: item.needInspection,
      inspectionMode: item.inspectionMode
    })
  })
  if (extra.length) {
    processOptions.value = [...processOptions.value, ...extra]
  }
}

const loadRoutingChanges = async (productId: number) => {
  routingChangesLoading.value = true
  try {
    routingChanges.value = (await api.get(`/products/${productId}/routing-changes`)) || []
  } catch {
    routingChanges.value = []
  } finally {
    routingChangesLoading.value = false
  }
}

const loadRoutingRows = async (productId: number) => {
  const data = (await api.get(`/products/${productId}/routing`)) || []
  mergeRoutingProcesses(data)
  routingRows.value = data.map((item: any, index: number) => ({
    seq: index + 1,
    processId: item.processId,
    processCode: item.processCode || '',
    processName: item.processName || '',
    minutesPerPiece: Number(item.minutesPerPiece || 0),
    unitPrice: Number(item.unitPrice || 0),
    needInspection: !!item.needInspection,
    remark: item.remark || ''
  }))
}

const itemDrawer = ref(false)
const itemTarget = ref<any>(null)

const openItems = (row: any) => {
  itemTarget.value = row
  itemDrawer.value = true
}

const openRouting = async (row: any) => {
  routingProduct.value = row
  routingDrawer.value = true
  routingLoading.value = true
  routingChangesActive.value = []
  try {
    await Promise.all([loadRoutingRows(row.id), loadRoutingChanges(row.id)])
  } finally {
    routingLoading.value = false
  }
}

const openCopyRouting = () => {
  if (!routingProduct.value?.id) return
  copyForm.sourceProductId = null
  copyForm.overwrite = false
  copyDialog.value = true
}

const submitCopyRouting = async () => {
  if (!copyForm.sourceProductId) {
    ElMessage.warning('请选择源产品')
    return
  }
  copySaving.value = true
  try {
    const result = await api.post(`/products/${routingProduct.value.id}/routing/copy`, {
      sourceProductId: Number(copyForm.sourceProductId),
      overwrite: !!copyForm.overwrite
    })
    ElMessage.success(`已复制 ${Number(result?.copied || 0)} 道工序`)
    copyDialog.value = false
    routingLoading.value = true
    try {
      await Promise.all([
        loadRoutingRows(routingProduct.value.id),
        loadRoutingChanges(routingProduct.value.id)
      ])
    } finally {
      routingLoading.value = false
    }
    load()
  } catch {
    // 后端业务错误（如目标已有路线）已由接口层提示
  } finally {
    copySaving.value = false
  }
}

const addRoutingLine = () => {
  routingRows.value.push({
    seq: routingRows.value.length + 1,
    processId: null,
    processCode: '',
    processName: '',
    minutesPerPiece: 0,
    unitPrice: 0,
    needInspection: false,
    remark: ''
  })
}

const removeRoutingLine = (index: number) => {
  routingRows.value.splice(index, 1)
}

const moveRouting = (index: number, offset: number) => {
  const target = index + offset
  if (target < 0 || target >= routingRows.value.length) return
  const list = [...routingRows.value]
  const [item] = list.splice(index, 1)
  list.splice(target, 0, item)
  routingRows.value = list
}

const onRoutingProcessChange = (line: any, process: any) => {
  if (!process) return
  line.processCode = process.code || ''
  line.processName = process.name || ''
  rememberProcess(process)
  if (!Number(line.minutesPerPiece)) line.minutesPerPiece = Number(process.defaultMinutes || 0)
  if (!Number(line.unitPrice)) line.unitPrice = Number(process.defaultPrice || 0)
  line.needInspection = !!process.needInspection
}

const saveRouting = async () => {
  if (!routingRows.value.length) {
    ElMessage.warning('工艺路线至少需要一道工序')
    return
  }
  const invalid = routingRows.value.find((line: any) => !line.processId)
  if (invalid) {
    ElMessage.warning('请为每一道工序选择具体工序')
    return
  }
  routingSaving.value = true
  try {
    await api.put(`/products/${routingProduct.value.id}/routing`, {
      items: routingRows.value.map((line: any, index: number) => ({
        seq: index + 1,
        processId: line.processId,
        minutesPerPiece: Number(line.minutesPerPiece || 0),
        unitPrice: Number(line.unitPrice || 0),
        needInspection: !!line.needInspection,
        remark: line.remark || ''
      }))
    })
    ElMessage.success('工艺路线已保存')
    routingDrawer.value = false
    load()
  } finally {
    routingSaving.value = false
  }
}

const openBom = async (row: any) => {
  bomProduct.value = row
  bomDrawer.value = true
  bomLoading.value = true
  try {
    bomRouting.value = []
    try {
      bomRouting.value = (await api.get(`/products/${row.id}/routing`)) || []
    } catch {
      bomRouting.value = []
    }
    const data = await api.get(`/products/${row.id}/bom`)
    bomRows.value = data.map((item: any) => ({
      materialId: item.materialId,
      materialCode: item.materialCode,
      materialName: item.materialName,
      unit: item.unit,
      qty: Number(item.qty),
      operationSeq: item.operationSeq ? Number(item.operationSeq) : null,
      substituteGroup: item.substituteGroup || '',
      substituteMaterialId: item.substituteMaterialId ?? null,
      substituteMaterialCode: item.substituteMaterialCode || '',
      substituteMaterialName: item.substituteMaterialName || '',
      isKey: !!item.isKey,
      remark: item.remark || ''
    }))
  } finally {
    bomLoading.value = false
  }
}

const gotoBomManagement = () => {
  const code = bomProduct.value?.code
  bomDrawer.value = false
  router.push({ path: '/boms', query: code ? { keyword: code } : {} })
}

onMounted(async () => {
  await table.load()
  await loadCustomFields()
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">物料主数据</h2>
        <p class="page-sub">
          成品、半成品和原料统一编码；工艺路线决定工单工序，BOM 在「BOM 管理」里按版本维护
        </p>
      </div>
      <el-button type="primary" @click="openCreate">新建产品</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="产品编码 / 名称" clearable style="width: 240px" @keyup.enter="reload" />
        <el-select v-model="category" placeholder="全部类型" clearable style="width: 140px" @change="reload">
          <el-option label="成品" value="FINISHED" />
          <el-option label="半成品" value="SEMI" />
          <el-option label="原料" value="RAW" />
        </el-select>
        <el-select
          v-model="customFieldKey"
          placeholder="自定义字段"
          clearable
          style="width: 150px"
          @change="onCustomFieldChange"
        >
          <el-option
            v-for="field in customFieldDefs"
            :key="field.fieldKey"
            :label="field.label"
            :value="field.fieldKey"
          />
        </el-select>
        <template v-if="activeCustomField">
          <el-select
            v-if="activeCustomField.fieldType === 'SELECT' || activeCustomField.fieldType === 'SWITCH'"
            v-model="customFilterValue"
            :placeholder="`${activeCustomField.label}的值`"
            clearable
            style="width: 150px"
            @change="reload"
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
            @change="reload"
          />
          <el-input
            v-else
            v-model="customFilterValue"
            :placeholder="`${activeCustomField.label}的值`"
            clearable
            style="width: 150px"
            @keyup.enter="reload"
          />
        </template>
        <el-button type="primary" plain @click="reload">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar
          :table="table"
          export-api="/products"
          export-name="物料主数据"
          :export-transform="flattenRecords"
          @applied="reload"
        />
        <span class="toolbar-tip">共 {{ total }} 个产品</span>
      </div>
      <div class="table-wrap">
        <DataTable :table="table" :data="rows" :loading="loading">
          <template #cell-code="{ row }"><span class="mono">{{ row.code }}</span></template>
          <template #cell-category="{ row }">
            <span class="status-tag" :class="categoryMap[row.category]?.cls">
              <i></i>{{ categoryMap[row.category]?.label || row.category }}
            </span>
          </template>
          <template #cell-source="{ row }">
            <span class="status-tag" :class="sourceMap[row.source || defaultSourceOf(row.category)]?.cls">
              <i></i>{{ sourceMap[row.source || defaultSourceOf(row.category)]?.label || row.source }}
            </span>
          </template>
          <template #cell-leadDays="{ row }">
            <span class="num">{{ Number(row.leadDays || 0) }} 天</span>
          </template>
          <template #cell-price="{ row }"><span class="money">¥{{ fmtMoney(row.price) }}</span></template>
          <template #cell-stock="{ row }">
            <span :class="Number(row.stock) < Number(row.safetyStock) ? 'danger-text' : ''">
              {{ fmtQty(row.stock) }} {{ row.unit }}
            </span>
          </template>
          <template #cell-safetyStock="{ row }"><span class="muted">{{ fmtQty(row.safetyStock) }}</span></template>
          <template #cell-routing="{ row }">
            <span v-if="Number(row.routingCount) > 0" class="status-tag primary"><i></i>{{ row.routingCount }} 道工序</span>
            <span v-else class="danger-text" style="font-size: 12px">未配置</span>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openRouting(row)">工艺</el-button>
            <el-button link type="primary" @click="openBom(row)">BOM</el-button>
            <el-button link type="primary" @click="openDocs(row)">文档</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </DataTable>
      </div>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load"
        />
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" :title="editingId ? '编辑产品' : '新建产品'" width="640px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="产品编码">
            <el-input v-model="form.code" placeholder="留空自动生成，如 PD0001" />
          </el-form-item>
          <el-form-item label="产品名称" required>
            <el-input v-model="form.name" />
          </el-form-item>
          <el-form-item label="规格型号">
            <el-input v-model="form.spec" />
          </el-form-item>
          <el-form-item label="产品类型" required>
            <el-select v-model="form.category" style="width: 100%" @change="onCategoryChange">
              <el-option label="成品" value="FINISHED" />
              <el-option label="半成品" value="SEMI" />
              <el-option label="原料" value="RAW" />
              <el-option label="外购件" value="PURCHASED" />
            </el-select>
          </el-form-item>
          <el-form-item label="默认来源" required>
            <el-select v-model="form.source" style="width: 100%">
              <el-option label="自制" value="MAKE" />
              <el-option label="外购" value="BUY" />
            </el-select>
            <span class="form-tip">MRP 按这里决定生成工单还是采购申请，BOM 不写来源。</span>
          </el-form-item>
          <el-form-item label="单位" required>
            <el-input v-model="form.unit" />
          </el-form-item>
          <el-form-item label="销售单价">
            <el-input-number v-model="form.price" :min="0" :precision="2" :step="1" style="width: 100%" />
          </el-form-item>
          <el-form-item label="标准成本">
            <el-input-number v-model="form.cost" :min="0" :precision="2" :step="1" style="width: 100%" />
          </el-form-item>
          <el-form-item label="安全库存">
            <el-input-number v-model="form.safetyStock" :min="0" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="提前期（天）">
            <el-input-number v-model="form.leadDays" :min="0" :precision="0" style="width: 100%" />
            <span class="form-tip">采购提前期或自制生产周期，MRP 用它倒排建议下单/开工日期。</span>
          </el-form-item>
          <el-form-item label="损耗率">
            <el-input-number v-model="form.lossRate" :min="0" :max="1" :precision="3" :step="0.01" style="width: 100%" />
          </el-form-item>
          <el-form-item label="默认供应商" class="full">
            <ReferenceSelect v-model="form.defaultSupplierId" url="/suppliers/options"
                             :initial-label="form.supplierLabel"
                             placeholder="不指定"
                             style="width: 240px"
                             @select="(supplier) => (form.supplierLabel = supplier ? `${supplier.code} · ${supplier.name}` : '')" />
            <span class="muted" style="margin-left: 12px; font-size: 12px">
              采购下单时没选供应商，会按明细物料带出这一家；多物料指向不同供应商时会让你手工选。
            </span>
          </el-form-item>
          <el-form-item label="追溯方式" class="full">
            <el-select v-model="form.traceMode" style="width: 200px">
              <el-option label="按数量（不逐件追溯）" value="NONE" />
              <el-option label="单件追溯（每件一码）" value="SERIAL" />
              <el-option label="按批次追溯（预留）" value="LOT" disabled />
            </el-select>
            <span class="muted" style="margin-left: 12px; font-size: 12px">
              这里是默认值：工单建单时快照过去，下达前还能在工单上单独改。
              选单件追溯后，该产品的工单下达时按计划数生成件码，车间逐件扫码，工序进度按件累加。
            </span>
          </el-form-item>
        </div>

        <div v-if="customFieldDefs.length" class="drawer-section" style="margin-top: 8px">
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
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer append-to-body v-model="routingDrawer" size="900px" :title="`工艺路线 · ${routingProduct?.name || ''}`">
      <div v-loading="routingLoading">
        <div class="bom-head">
          <div>
            <span class="mono">{{ routingProduct?.code }}</span>
            <span class="muted"> · {{ routingProduct?.spec || '无规格' }} · 工序按顺序执行</span>
          </div>
          <div class="head-actions">
            <el-button
              v-perm="'routing:save'"
              size="small"
              :type="routingRows.length ? 'default' : 'primary'"
              @click="openCopyRouting"
            >
              复制其他产品路线
            </el-button>
            <el-button size="small" :type="routingRows.length ? 'primary' : 'default'" @click="addRoutingLine">
              添加工序
            </el-button>
          </div>
        </div>

        <el-alert
          v-if="!routingRows.length && !routingLoading"
          class="routing-empty-tip"
          type="info"
          :closable="false"
          show-icon
          title="该产品还没有工艺路线，可以从其他产品复制或手动添加"
        />

        <el-table :data="routingRows" size="small" empty-text="尚未配置工艺路线">
          <el-table-column label="顺序" width="112">
            <template #default="{ $index }">
              <div class="seq-cell">
                <span class="num">{{ $index + 1 }}</span>
                <el-button link type="primary" :disabled="$index === 0" @click="moveRouting($index, -1)">
                  <el-icon><ArrowUp /></el-icon>
                </el-button>
                <el-button
                  link
                  type="primary"
                  :disabled="$index === routingRows.length - 1"
                  @click="moveRouting($index, 1)"
                >
                  <el-icon><ArrowDown /></el-icon>
                </el-button>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="工序" min-width="190">
            <template #default="{ row }">
              <ReferenceSelect
                v-model="row.processId"
                url="/processes/options"
                :initial-label="row.processCode ? `${row.processCode} · ${row.processName}` : row.processName"
                placeholder="输入工序编码或名称搜索"
                @select="(process) => onRoutingProcessChange(row, process)"
              />
            </template>
          </el-table-column>
          <el-table-column label="工时/件" width="115">
            <template #default="{ row }">
              <el-input-number v-model="row.minutesPerPiece" :min="0" :precision="2" :step="0.5" size="small" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="工价" width="115">
            <template #default="{ row }">
              <el-input-number v-model="row.unitPrice" :min="0" :precision="2" :step="0.5" size="small" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="检验方式" width="130" align="center">
            <template #default="{ row }">
              <span class="status-tag" :class="modeOf(row.processId).cls">
                <i></i>{{ modeOf(row.processId).label }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.remark" size="small" placeholder="可选" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="65">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeRoutingLine($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="bom-note">
          工单工序将按此路线生成，变更不影响已生成工单。工序工时与工价缺省时按工序主数据补全。
        </div>

        <el-collapse v-model="routingChangesActive" class="routing-history">
          <el-collapse-item name="changes">
            <template #title>
              <span class="history-title">变更记录<span class="muted">（{{ routingChanges.length }}）</span></span>
            </template>
            <div v-loading="routingChangesLoading">
              <el-table :data="routingChanges" size="small" empty-text="暂无变更记录">
                <el-table-column prop="changedAt" label="时间" width="150" />
                <el-table-column label="动作" width="82">
                  <template #default="{ row }">
                    <span class="status-tag" :class="routingAction(row.action).cls">
                      <i></i>{{ routingAction(row.action).label }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="路线变化" min-width="230">
                  <template #default="{ row }">
                    <span class="mono" :class="row.oldSummary ? '' : 'muted'">{{ row.oldSummary || '-' }}</span>
                    <span class="muted"> → </span>
                    <span class="mono" :class="row.newSummary ? '' : 'muted'">{{ row.newSummary || '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="备注" min-width="130" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span :class="row.remark ? '' : 'muted'">{{ row.remark || '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="changedBy" label="操作人" width="90">
                  <template #default="{ row }">
                    <span :class="row.changedBy ? '' : 'muted'">{{ row.changedBy || '-' }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <template #footer>
        <el-button v-perm="'routing:save'" @click="openCopyRouting">复制其他产品路线</el-button>
        <el-button @click="routingDrawer = false">取消</el-button>
        <el-button v-perm="'routing:save'" type="primary" :loading="routingSaving" @click="saveRouting">保存路线</el-button>
      </template>
    </el-drawer>

    <el-dialog draggable append-to-body v-model="copyDialog" title="复制其他产品路线" width="560px">
      <div class="copy-head">
        <span class="muted">目标产品</span>
        <b>{{ routingProduct?.code }} · {{ routingProduct?.name }}</b>
        <span class="muted">（当前 {{ routingRows.length }} 道工序）</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="源产品" required>
          <ReferenceSelect
            v-model="copyForm.sourceProductId"
            url="/options/products"
            :params="{ routingOnly: true }"
            placeholder="输入产品编码或名称搜索（仅含已配置路线的产品）"
          />
        </el-form-item>
        <el-form-item label="覆盖已有路线">
          <el-switch v-model="copyForm.overwrite" active-text="覆盖" inactive-text="不覆盖" />
        </el-form-item>
      </el-form>
      <div class="bom-note">
        复制会把源产品的工序顺序、工时、工价、检验要求与备注完整带到当前产品。
        <template v-if="routingRows.length">
          <br />
          <span class="danger-text">当前产品已有 {{ routingRows.length }} 道工序，未勾选覆盖时后端会拒绝复制。</span>
        </template>
        <template v-else>
          当前产品还没有工艺路线，复制后会直接生成工序并记录一条「复制」变更。
        </template>
      </div>
      <template #footer>
        <el-button @click="copyDialog = false">取消</el-button>
        <el-button v-perm="'routing:save'" type="primary" :loading="copySaving" @click="submitCopyRouting">
          开始复制
        </el-button>
      </template>
    </el-dialog>

    <el-drawer append-to-body v-model="bomDrawer" size="980px" :title="`BOM 快改 · ${bomProduct?.name || ''}`">
      <div v-loading="bomLoading">
        <div class="bom-head">
          <div>
            <span class="mono">{{ bomProduct?.code }}</span>
            <span class="muted"> · {{ bomProduct?.spec || '无规格' }} · 单位 {{ bomProduct?.unit }}</span>
          </div>
          <el-button size="small" type="primary" plain @click="gotoBomManagement">去 BOM 管理变更</el-button>
        </div>
        <div class="bom-tip">
          BOM 明细只读。需要调整请到「BOM 管理」复制新版本、维护后提交审批，通过后自动生效。
        </div>

        <el-table :data="bomRows" size="small" empty-text="尚未维护 BOM">
          <el-table-column label="物料" min-width="200">
            <template #default="{ row }">
              <ReferenceSelect
                v-model="row.materialId"
                url="/options/products"
                :initial-label="row.materialCode ? `${row.materialCode} · ${row.materialName}` : row.materialName"
                disabled
              />
            </template>
          </el-table-column>
          <el-table-column label="单位" width="60">
            <template #default="{ row }">{{ row.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="单耗" width="115">
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="0.0001" :precision="4" disabled size="small" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="投料工序" width="150">
            <template #default="{ row }">
              <el-select
                v-model="row.operationSeq"
                clearable
                disabled
                :placeholder="hasBomRouting ? '选择工序' : '请先配置工艺路线'"
                size="small"
                style="width: 100%"
              >
                <el-option
                  v-for="item in bomRouting"
                  :key="item.seq"
                  :label="`${item.seq}. ${item.processName}`"
                  :value="item.seq"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="替代组" width="105">
            <template #default="{ row }">
              <el-input v-model="row.substituteGroup" disabled size="small" placeholder="可选" />
            </template>
          </el-table-column>
          <el-table-column label="关键件" width="72" align="center">
            <template #default="{ row }">
              <el-switch v-model="row.isKey" disabled size="small" />
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="120">
            <template #default="{ row }">
              <el-input v-model="row.remark" disabled size="small" placeholder="可选" />
            </template>
          </el-table-column>
          <el-table-column label="替代料" width="175">
            <template #default="{ row }">
              <ReferenceSelect
                v-model="row.substituteMaterialId"
                url="/options/products"
                :initial-label="row.substituteMaterialCode
                  ? `${row.substituteMaterialCode} · ${row.substituteMaterialName}`
                  : row.substituteMaterialName"
                placeholder="无替代料"
                disabled
                size="small"
              />
              <div v-if="row.substituteMaterialName" class="substitute-name">
                替代 {{ row.substituteMaterialName }}
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div class="bom-note">
          单耗按「每生产 1 个成品需要的物料数量」展示。领料时系统按 BOM 单耗 × 工单计划数量自动计算。
          <template v-if="!hasBomRouting">
            <br />
            <span class="danger-text">当前产品尚未配置工艺路线，投料工序不可选，请先在上方「工艺」中维护工艺路线。</span>
          </template>
          <template v-else>
            投料工序用于指示该物料在哪道工序领用；替代组用于标记可互相替代的物料组合；「替代料」表示主料库存不足时自动补足的物料。
          </template>
        </div>
      </div>

      <template #footer>
        <el-button @click="bomDrawer = false">关闭</el-button>
      </template>
    </el-drawer>

    <el-drawer
      append-to-body
      v-model="docDrawer"
      size="980px"
      :title="`作业与检验文档 · ${docProduct?.name || ''}`"
    >
      <div class="doc-head">
        <div class="doc-tip">
          这里维护 SOP 作业指导书、SIP 检验指导书、图纸与包装规范。绑定工序的文档会随工单下发，
          工人在「车间扫码」、质检在检验时能直接看到对应版本的指导书。
        </div>
        <el-button v-perm="'document:save'" type="primary" size="small" @click="openDocCreate">新增文档</el-button>
      </div>

      <el-table v-loading="docLoading" :data="docRows" size="small" empty-text="还没有文档，点右上角新增">
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <span class="status-tag" :class="docTypeOf(row.docType).cls">
              <i></i>{{ docTypeOf(row.docType).label }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column label="绑定工序" width="110">
          <template #default="{ row }">
            <span :class="row.processName ? '' : 'muted'">{{ row.processName || '产品级' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="内容摘要" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="muted">{{ (row.content || '').split('\n')[0] || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <span class="status-tag" :class="row.enabled ? 'success' : 'info'">
              <i></i>{{ row.enabled ? '启用' : '停用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="createdBy" label="维护人" width="90" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDocEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="removeDoc(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="docDrawer = false">关闭</el-button>
      </template>
    </el-drawer>

    <el-dialog draggable
      append-to-body
      v-model="docDialog"
      :title="editingDocId ? '编辑文档' : '新增文档'"
      width="760px"
    >
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="文档类型" required>
            <el-select v-model="docForm.docType" style="width: 100%">
              <el-option label="SOP 作业指导书" value="SOP" />
              <el-option label="SIP 检验指导书" value="SIP" />
              <el-option label="图纸" value="DRAWING" />
              <el-option label="包装规范" value="PACKING" />
              <el-option label="其他" value="OTHER" />
            </el-select>
          </el-form-item>
          <el-form-item label="版本">
            <el-input v-model="docForm.version" placeholder="如 V1.0" />
          </el-form-item>
        </div>
        <el-form-item label="标题" required>
          <el-input v-model="docForm.title" placeholder="如 铝合金外壳 下料作业指导书" />
        </el-form-item>
        <el-form-item label="绑定工序（留空表示产品级通用文档）">
          <ReferenceSelect
            v-model="docForm.processId"
            url="/processes/options"
            :initial-label="docForm.processLabel"
            placeholder="产品级（输入工序编码或名称搜索）"
            @select="(process) => {
              docForm.processLabel = process ? `${process.code} · ${process.name}` : ''
              rememberProcess(process)
            }"
          />
        </el-form-item>
        <el-form-item label="正文（工人与质检在车间扫码页直接阅读）">
          <el-input
            v-model="docForm.content"
            type="textarea"
            :rows="9"
            placeholder="1. 核对物料批次；&#10;2. 按图号加工，公差 ±0.5mm；&#10;3. 首件自检合格后再批量生产。"
          />
        </el-form-item>
        <el-form-item label="附件地址（可选，PDF / 图片链接）">
          <el-input v-model="docForm.fileUrl" placeholder="https://… 或 /files/sop-p001.pdf" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="docForm.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="docDialog = false">取消</el-button>
        <el-button type="primary" :loading="docSaving" @click="saveDoc">保存</el-button>
      </template>
    </el-dialog>

    <InspectionItemEditor
      v-model="itemDrawer"
      :product-id="itemTarget?.id"
      :title="`产品检验项 · ${itemTarget?.name || ''}`"
    />
  </div>
</template>

<style scoped>
.doc-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 0 0 14px;
}

.doc-tip {
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.7;
  background: #f7f9fc;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  flex: 1;
}

.bom-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 0;
}

.bom-tip {
  margin-bottom: 12px;
  padding: 9px 12px;
  background: #f8fafc;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  color: var(--muted);
  font-size: 12px;
}

.form-tip {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.6;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.routing-empty-tip {
  margin-bottom: 12px;
}

.routing-history {
  margin-top: 16px;
}

.routing-history .history-title {
  font-size: 13px;
  font-weight: 600;
}

.routing-history :deep(.el-collapse-item__header) {
  padding: 0 4px;
  background: #f8fafc;
  border-radius: 6px;
}

.copy-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 14px;
  padding: 10px 12px;
  background: #f8fafc;
  border-radius: 6px;
  font-size: 13px;
}

.bom-note {
  margin-top: 14px;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}

.seq-cell {
  display: flex;
  align-items: center;
  gap: 2px;
}

.seq-cell .num {
  width: 18px;
}

.substitute-name {
  margin-top: 2px;
  font-size: 11px;
  color: var(--muted);
  line-height: 1.5;
}
</style>
