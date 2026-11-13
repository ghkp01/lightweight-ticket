<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import { api, fmtQty } from '../api'
import StatusTag from '../components/StatusTag.vue'
import PriorityTag from '../components/PriorityTag.vue'
import DataTable from '../components/DataTable.vue'
import ReferenceSelect from '../components/ReferenceSelect.vue'
import { useAuthStore } from '../stores/auth'
import { useRoute, useRouter } from 'vue-router'
import TableToolbar from '../components/TableToolbar.vue'
import { useTableView } from '../composables/useTableView'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const status = ref('')
const onlyShortage = ref(false)
const customFieldKey = ref('')
const customFilterValue = ref<any>('')
const customFieldDefs = ref<any[]>([])
const customValues = reactive<Record<string, any>>({})

const baseColumns = [
  { key: 'workNo', label: '工单号', width: 175, fixed: 'left' as const },
  { key: 'product', label: '产品', width: 170 },
  { key: 'qty', label: '计划 / 完成', width: 125, align: 'right' as const },
  { key: 'deliveryDate', label: '订单交期', width: 122 },
  { key: 'progress', label: '进度', width: 150 },
  { key: 'status', label: '状态', width: 95 },
  { key: 'kit', label: '齐套', width: 118, visible: false },
  { key: 'orderNo', label: '来源订单', width: 155, visible: false },
  { key: 'priority', label: '优先级', width: 80, visible: false },
  { key: 'scheduleDate', label: '排产日期', width: 100, visible: false },
  { key: 'planEnd', label: '计划完工', width: 105, visible: false },
  { key: 'actions', label: '操作', width: 250, fixed: 'right' as const }
]

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
// 默认只留 8 列（工单号/产品/数量/交期/进度/状态/操作），其余进列设置，
// 另外给两个内置视角：计划盯交期，车间盯进度。
const table = useTableView({
  pageKey: 'production.work-orders',
  columns: baseColumns,
  presets: [
    {
      key: 'plan',
      label: '计划视角',
      hint: '盯交期、齐套与进度：哪些单要追、哪些单缺料',
      visible: ['workNo', 'product', 'qty', 'deliveryDate', 'kit', 'progress', 'status', 'actions']
    },
    {
      key: 'shop',
      label: '车间视角',
      hint: '盯当前进度与状态：工人上工位、班组长排活',
      visible: ['workNo', 'product', 'qty', 'progress', 'status', 'actions']
    }
  ],
  getFilters: () => ({
    keyword: keyword.value,
    status: status.value,
    onlyShortage: onlyShortage.value,
    customFieldKey: customFieldKey.value,
    customFilterValue: customFilterValue.value
  }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    status.value = filters.status || ''
    onlyShortage.value = !!filters.onlyShortage
    customFieldKey.value = filters.customFieldKey || ''
    customFilterValue.value = filters.customFilterValue ?? ''
  },
  getPageSize: () => size.value,
  setPageSize: (value) => {
    size.value = value
  }
})

const hasFilter = computed(() =>
  Boolean(keyword.value || status.value || onlyShortage.value
    || customFieldKey.value || customFilterValue.value)
)
const emptyTitle = computed(() => (hasFilter.value ? '没有符合条件的工单' : '还没有生产工单'))
const emptyDesc = computed(() =>
  hasFilter.value
    ? '换个状态或关键词再查一次，也可以清掉筛选看全部工单。'
    : '工单从销售订单转生产自动生成，也可以在这里手工建单。'
)

const clearFilters = () => {
  keyword.value = ''
  status.value = ''
  onlyShortage.value = false
  customFieldKey.value = ''
  customFilterValue.value = ''
  reload()
}

const goSales = () => router.push('/sales')

const reload = () => {
  page.value = 1
  load()
}

const createDialog = ref(false)
const saving = ref(false)
const form = reactive<any>({
  productId: null,
  planQty: 1,
  planStart: '',
  planEnd: '',
  priority: 'NORMAL',
  traceMode: '',
  remark: ''
})

// 建单预览：选完产品就把「会生成哪些工序、要备哪些料」摆出来，
// 别让人建完再进详情页才发现路线没维护、BOM 是空的
const createPreview = ref<any>({ routing: [], bom: [], loading: false })
/** 建单预览要用的产品档案字段（规格/单位/库存/损耗率/追溯方式）由选择结果带出 */
const createProduct = ref<any>(null)

const loadCreatePreview = async () => {
  if (!form.productId) {
    createPreview.value = { routing: [], bom: [], loading: false }
    return
  }
  createPreview.value = { routing: [], bom: [], loading: true }
  try {
    const [routing, bom] = await Promise.all([
      api.get(`/products/${form.productId}/routing`),
      api.get(`/products/${form.productId}/bom`)
    ])
    // 每道工序预留「报工人 / 报工权限」，建单时就能派下去
    const rows = (routing || []).map((row: any) => ({
      ...row,
      assignUserId: null,
      reportPolicy: 'ANY'
    }))
    createPreview.value = { routing: rows, bom: bom || [], loading: false }
  } catch {
    createPreview.value = { routing: [], bom: [], loading: false }
  }
}

const onProductChange = async (product: any) => {
  createProduct.value = product
  // 追溯方式跟着产品默认值走，人可以在这里改（下单前快照到工单）
  form.traceMode = product?.traceMode || 'NONE'
  await loadCreatePreview()
}

/** 本单需求 = 单件用量 × 计划数量 ×（1 + 产品损耗率），与工单 BOM 快照同一口径 */
const previewNeed = (row: any) => {
  const qty = Number(row.qty || 0)
  const planQty = Number(form.planQty || 0)
  const lossRate = Number(createProduct.value?.lossRate || 0)
  return Math.round(qty * planQty * (1 + lossRate) * 10000) / 10000
}

const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)
/** 详情抽屉的分页签：概览 / 工序 / 物料 / 单件 / 记录，先把「现在该干什么」放第一屏 */
const detailTab = ref('overview')
/** 单件页签只在按件追溯的工单上出现，按数量追溯的工单不占屏 */
const hasUnitSection = computed(
  () =>
    detail.value?.traceMode === 'SERIAL' ||
    Number(unitData.value?.summary?.total || 0) > 0
)

// 作业指导书（SOP / SIP）与工序内检验
const docDrawer = ref(false)
const docLoading = ref(false)
const docData = ref<any>({ productDocuments: [], operations: [] })

const inspectionModeMap: Record<string, { label: string; cls: string }> = {
  NONE: { label: '不检验', cls: 'draft' },
  ATTACHED: { label: '工序内检验点', cls: 'warning' },
  ROUTING: { label: '独立检验工序', cls: 'success' }
}

const modeOf = (value: any) =>
  inspectionModeMap[String(value || 'NONE')] || inspectionModeMap.NONE

const openWorkDocs = async (work: any) => {
  docDrawer.value = true
  docLoading.value = true
  docData.value = { productDocuments: [], operations: [] }
  try {
    docData.value = await api.get(`/work-orders/${work.id}/documents`)
  } finally {
    docLoading.value = false
  }
}

const hasDocs = computed(
  () =>
    (docData.value.productDocuments || []).length > 0 ||
    (docData.value.operations || []).some((op: any) => (op.documents || []).length > 0)
)

const reportDialog = ref(false)
const reportWork = ref<any>(null)
const reportSaving = ref(false)
const reportForm = reactive<any>({
  operationId: null,
  qty: 0,
  defectQty: 0,
  worker: '',
  remark: '',
  inspectionResult: '',
  inspectionRemark: ''
})

/** 当前报工工序是否限定报工人：限定时操作工只读，由后端强校验 */
const reportLockedWorker = computed(() => {
  const ops = reportWork.value?.operations || []
  const op = ops.find((item: any) => item.id === reportForm.operationId)
  return op?.reportPolicy === 'ASSIGNED'
})

/** 当前报工工序的分配名单：有人名与各自剩余量，报工人从里面挑 */
const reportAssignments = computed(() => {
  const ops = reportWork.value?.operations || []
  const op = ops.find((item: any) => item.id === reportForm.operationId)
  return op?.assignments || []
})
const reportQualified = computed(() => Math.max(0, Number(reportForm.qty || 0) - Number(reportForm.defectQty || 0)))
const reportOperation = computed<any>(() =>
  (reportWork.value?.operations || []).find((op: any) => op.id === reportForm.operationId)
)
const reportNeedInspect = computed(() => reportOperation.value?.inspectionMode === 'ATTACHED')

const assignDialog = ref(false)
const assignSaving = ref(false)
const assignWork = ref<any>(null)
const assignSkillScoped = ref(false)
const assignProcessId = ref<number | null>(null)
const assignSkillHint = ref('')
const assignForm = reactive<any>({ operationId: null, reportPolicy: 'ANY', rows: [] })

const returnDialog = ref(false)
const returnSaving = ref(false)
const returnWork = ref<any>(null)
const returnRows = ref<any[]>([])
// 退料单：默认退回原领料仓（留空由后端按最近一次领料仓库回退），退料人默认当前用户
const returnForm = reactive({ warehouseId: null as any, warehouseLabel: '', returner: '', reason: '' })

const issueDialog = ref(false)
const issueSaving = ref(false)
const issueWork = ref<any>(null)
const issueRows = ref<any[]>([])
// 领料单信息：仓库、领料人、班组决定账实能否对上，提交前必须确认
const issueForm = reactive({ warehouseId: null as any, warehouseLabel: '', issuer: '', team: '', remark: '' })

const qrDialog = ref(false)
const qrLoading = ref(false)
const qrWork = ref<any>(null)
const qrWorkUrl = ref('')
const qrOps = ref<any[]>([])

const bomDialog = ref(false)
const bomSaving = ref(false)
const bomAdjust = reactive<any>({
  editing: false,
  materialId: null,
  materialLabel: '',
  planQty: 1,
  operationSeq: null,
  remark: '',
  reason: ''
})

const bomActionMap: Record<string, { label: string; cls: string }> = {
  ADD: { label: '新增', cls: 'success' },
  UPDATE: { label: '调整', cls: 'primary' },
  REMOVE: { label: '删除', cls: 'danger' },
  RESET: { label: '恢复标准', cls: 'warning' }
}

const bomSourceMap: Record<string, { label: string; cls: string }> = {
  STANDARD: { label: '标准', cls: 'info' },
  CUSTOM: { label: '非标', cls: 'warning' },
  SUBSTITUTE: { label: '替代料', cls: 'primary' }
}

const bomSource = (row: any) => bomSourceMap[String(row?.source || 'STANDARD')] || bomSourceMap.STANDARD
const isSubstituteLine = (row: any) => String(row?.source || '') === 'SUBSTITUTE'

// 按工序领料
const operationIssueLoading = ref<number | null>(null)

// 工单 BOM 与标准 BOM 对比
const bomDiffDialog = ref(false)
const bomDiffLoading = ref(false)
const bomDiffRows = ref<any[]>([])

const bomDiffTypeMap: Record<string, { label: string; cls: string }> = {
  ADDED: { label: '新增', cls: 'success' },
  REMOVED: { label: '删除', cls: 'danger' },
  CHANGED: { label: '改量', cls: 'warning' },
  SAME: { label: '一致', cls: 'info' }
}

const bomDiffType = (row: any) =>
  bomDiffTypeMap[String(row?.changeType || '')] || { label: String(row?.changeType || '-'), cls: 'info' }

const diffText = (value: any) => {
  const num = Number(value || 0)
  if (!num) return '0'
  return `${num > 0 ? '+' : ''}${fmtQty(num)}`
}

const diffClass = (value: any) => {
  const num = Number(value || 0)
  if (num > 0) return 'danger-text'
  if (num < 0) return 'success-text'
  return 'muted'
}

const bomDiffRowClass = ({ row }: any) =>
  Number(row?.diffQty || 0) === 0 ? 'bom-diff-same' : ''

const openBomDiff = async () => {
  if (!detail.value) return
  bomDiffDialog.value = true
  bomDiffLoading.value = true
  bomDiffRows.value = []
  try {
    bomDiffRows.value = (await api.get(`/work-orders/${detail.value.id}/bom-diff`)) || []
  } catch {
    bomDiffRows.value = []
  } finally {
    bomDiffLoading.value = false
  }
}

const canIssueOperation = (row: any) => {
  const statusText = String(detail.value?.status || '')
  return (
    (statusText === 'RELEASED' || statusText === 'IN_PROGRESS') &&
    Number(row?.materialPendingQty || 0) > 0
  )
}

const issueByOperation = async (row: any) => {
  if (!detail.value) return
  operationIssueLoading.value = Number(row.seq)
  try {
    const result = await api.post(`/work-orders/${detail.value.id}/issue-operation`, {
      operationSeq: Number(row.seq)
    })
    const lines = result?.lines || []
    const totalQty = Number(
      result?.totalQty ?? lines.reduce((sum: number, line: any) => sum + Number(line?.qty || 0), 0)
    )
    ElMessage.success(`已领料 ${fmtQty(totalQty)}`)
    const substitutes = lines.filter((line: any) => Number(line?.subQty) > 0)
    if (substitutes.length) {
      ElMessage.warning(
        substitutes
          .map((line: any) => `${line.materialName || '物料'} 用替代料补 ${fmtQty(line.subQty)}`)
          .join('；')
      )
    }
    load()
    await refreshDetail()
  } catch {
    // 后端业务提示（如该工序没有待领物料）已由接口层展示
  } finally {
    operationIssueLoading.value = null
  }
}

// ---------------------------------------------------------------- 外协（中途转外协 / 发出 / 收回）
const outsourceVisible = ref(false)
const outsourceMode = ref<'plan' | 'send'>('plan')
const outsourceSaving = ref(false)
const outsourceMaterials = ref<any[]>([])
const outsourceForm = reactive({
  seq: 0,
  operationName: '',
  supplierId: null as any,
  supplierLabel: '',
  price: 0,
  expectedAt: '',
  qty: 0,
  sender: '',
  remark: ''
})

/** 工序上的外协角标；空表示厂内做 */
const outsourceLabel = (row: any) => {
  const status = String(row?.outsourceStatus || '')
  if (status === 'PENDING_SEND') return '待发外协'
  if (status === 'SENT') return '外协中'
  if (status === 'RECEIVED') return '外协已回'
  return ''
}

const outsourceCls = (row: any) => {
  const status = String(row?.outsourceStatus || '')
  if (status === 'RECEIVED') return 'success'
  if (status) return 'warning'
  return 'draft'
}

const canOutsource = (row: any) => {
  const statusText = String(detail.value?.status || '')
  if (statusText !== 'RELEASED' && statusText !== 'IN_PROGRESS') return false
  if (Number(row?.doneQty || 0) >= Number(row?.planQty || 0)) return false
  const status = String(row?.outsourceStatus || '')
  return status !== 'SENT' && status !== 'RECEIVED'
}

const fillOutsourceForm = (row: any) => {
  outsourceForm.seq = Number(row.seq)
  outsourceForm.operationName = String(row.name || '')
  outsourceForm.supplierId = row.outsourceSupplierId || null
  outsourceForm.supplierLabel = row.outsourceSupplierName || ''
  outsourceForm.price = Number(row.outsourcePrice || 0)
  outsourceForm.expectedAt = row.outsourceExpectedAt || ''
  outsourceForm.sender = ''
  outsourceForm.remark = ''
}

const openOutsourcePlan = async (row: any) => {
  fillOutsourceForm(row)
  outsourceMode.value = 'plan'
  outsourceMaterials.value = []
  outsourceVisible.value = true
}

const openOutsourceSend = async (row: any) => {
  if (!detail.value) return
  fillOutsourceForm(row)
  outsourceMode.value = 'send'
  outsourceForm.qty = Number(row.planQty || 0) - Number(row.doneQty || 0)
  const data = await api.get(
    `/outsource/work-orders/${detail.value.id}/operations/${row.seq}/materials`
  )
  outsourceMaterials.value = data?.lines || []
  outsourceVisible.value = true
}

const submitOutsource = async () => {
  if (!detail.value) return
  if (!outsourceForm.supplierId) {
    ElMessage.warning('请选择外协供应商')
    return
  }
  outsourceSaving.value = true
  try {
    if (outsourceMode.value === 'plan') {
      await api.post(
        `/outsource/work-orders/${detail.value.id}/operations/${outsourceForm.seq}/plan`,
        {
          supplierId: outsourceForm.supplierId,
          price: Number(outsourceForm.price || 0),
          expectedAt: outsourceForm.expectedAt || null,
          remark: outsourceForm.remark || null
        }
      )
      ElMessage.success('已转外协，可以继续发出')
    } else {
      const items = outsourceMaterials.value.map((line: any) => ({
        materialId: line.materialId,
        qty: Number(line.sendQty || 0)
      }))
      const result = await api.post(
        `/outsource/work-orders/${detail.value.id}/operations/${outsourceForm.seq}/send`,
        {
          qty: Number(outsourceForm.qty || 0),
          supplierId: outsourceForm.supplierId,
          price: Number(outsourceForm.price || 0),
          expectedAt: outsourceForm.expectedAt || null,
          sender: outsourceForm.sender || null,
          remark: outsourceForm.remark || null,
          items
        }
      )
      ElMessage.success(`已发出，外协单 ${result?.orderNo || ''}`)
    }
    outsourceVisible.value = false
    load()
    await refreshDetail()
  } catch {
    // 业务提示由接口层统一弹出
  } finally {
    outsourceSaving.value = false
  }
}

const cancelOutsourcePlan = async (row: any) => {
  if (!detail.value) return
  await ElMessageBox.confirm(
    `撤销第 ${row.seq} 道工序的外协标记？撤销后这道工序回到厂内做。`,
    '撤销转外协'
  )
  await api.delete(`/outsource/work-orders/${detail.value.id}/operations/${row.seq}/plan`)
  ElMessage.success('已撤销外协标记')
  load()
  await refreshDetail()
}

const openOutsourceOrder = (row: any) => {
  if (!row?.id) return
  router.push({ path: '/production/outsource', query: { keyword: row.orderNo } })
}

// ---------------------------------------------------------------- 追溯方式（下达前可改）
const TRACE_TEXT: Record<string, string> = {
  NONE: '按数量',
  SERIAL: '单件追溯',
  LOT: '按批次'
}
const traceText = (mode: any) => TRACE_TEXT[String(mode || 'NONE')] || String(mode)
const canEditTrace = computed(() => {
  const status = String(detail.value?.status || '')
  return status === 'DRAFT' || status === 'SCHEDULED'
})

const changeTraceMode = async (value: string) => {
  if (!detail.value) return
  const previous = detail.value.traceMode
  try {
    await api.post(`/work-orders/${detail.value.id}/trace-mode`, { traceMode: value })
    ElMessage.success(
      value === 'SERIAL'
        ? '已改为单件追溯，下达时按计划数量生成件码'
        : '已改为按数量报工，不再生成件码'
    )
    load()
    await refreshDetail()
  } catch {
    // 失败时回滚下拉显示，业务提示由接口层弹出
    if (detail.value) detail.value.traceMode = previous
  }
}

/** 调整 BOM 用料：远程搜索后兜底，不能把工单产品本身当成投料 */
const onBomMaterialSelect = (product: any) => {
  if (!product) return
  if (product.id === detail.value?.productId) {
    bomAdjust.materialId = null
    bomAdjust.materialLabel = ''
    ElMessage.warning('不能用工单产品本身作为投料')
  }
}

const CLOSED_STATUS = ['FINISHED', 'CLOSED']

const activeCustomField = computed(() =>
  customFieldDefs.value.find((field: any) => field.fieldKey === customFieldKey.value) || null
)

const onCustomFieldChange = () => {
  customFilterValue.value = ''
}

const loadCustomFields = async () => {
  try {
    customFieldDefs.value = (await api.get('/custom-fields', { entityType: 'work_order' })) || []
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
    const data = await api.get('/work-orders', {
      keyword: keyword.value,
      status: status.value,
      page: page.value,
      size: size.value,
      onlyShortage: onlyShortage.value,
      customFieldKey: customFieldKey.value || undefined,
      customFieldValue: customFilterValue.value === '' || customFilterValue.value === null
        ? undefined : customFilterValue.value
    })
    rows.value = flattenRecords(data.records)
    total.value = data.total
  } finally {
    loading.value = false
  }
}

const openCreate = async () => {
  if (!customFieldDefs.value.length) await loadCustomFields()
  resetCustomValues()
  Object.assign(form, {
    productId: null,
    planQty: 1,
    planStart: new Date().toISOString().slice(0, 10),
    planEnd: '',
    priority: 'NORMAL',
    traceMode: '',
    remark: ''
  })
  createPreview.value = { routing: [], bom: [], loading: false }
  createProduct.value = null
  createDialog.value = true
}

const create = async () => {
  if (!form.productId || !form.planQty || form.planQty <= 0) {
    ElMessage.warning('请选择产品并填写计划数量')
    return
  }
  saving.value = true
  try {
    const assignments = (createPreview.value.routing || [])
      .map((row: any, index: number) => ({
        seq: row.seq || index + 1,
        userId: row.assignUserId || null,
        reportPolicy: row.reportPolicy || 'ANY'
      }))
      .filter((item: any) => item.userId || item.reportPolicy === 'ASSIGNED')
    await api.post('/work-orders', {
      productId: form.productId,
      planQty: Number(form.planQty),
      planStart: form.planStart || null,
      planEnd: form.planEnd || null,
      priority: form.priority || 'NORMAL',
      traceMode: form.traceMode || null,
      remark: form.remark || null,
      assignments,
      customFields: { ...customValues }
    })
    ElMessage.success('工单已创建')
    createDialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const unitData = ref<any>(null)
const unitLoading = ref(false)
const unitGenerating = ref(false)
const unitPage = ref(1)
const unitPageSize = 20

const loadUnits = async (workOrderId: number, page = 1) => {
  unitLoading.value = true
  try {
    unitPage.value = page
    unitData.value = await api.get('/work-order-units', {
      workOrderId,
      page,
      size: unitPageSize
    })
  } catch (err) {
    unitData.value = null
  } finally {
    unitLoading.value = false
  }
}

const changeUnitPage = (value: number) => {
  if (!detail.value) return
  loadUnits(detail.value.id, value)
}

const generateUnits = async () => {
  if (!detail.value) return
  unitGenerating.value = true
  try {
    const count = await api.post(`/work-orders/${detail.value.id}/units/generate`)
    ElMessage.success(`已生成 ${count} 个件码`)
    await loadUnits(detail.value.id, 1)
  } catch (err) {
    // 错误提示由 api 拦截器统一弹出
  } finally {
    unitGenerating.value = false
  }
}

const unitStatusLabel = (status: string) =>
  ({ PENDING: '待投产', IN_PROGRESS: '在制', FINISHED: '已完工', SCRAPPED: '已报废' } as Record<string, string>)[status] || status

const unitStatusCls = (status: string) =>
  ({ PENDING: 'info', IN_PROGRESS: 'warning', FINISHED: 'success', SCRAPPED: 'danger' } as Record<string, string>)[status] || 'info'

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detailTab.value = 'overview'
  unitData.value = null
  try {
    detail.value = await api.get(`/work-orders/${row.id}`)
    await loadUnits(row.id)
  } finally {
    detailLoading.value = false
  }
}

const refreshDetail = async () => {
  if (detail.value) {
    detail.value = await api.get(`/work-orders/${detail.value.id}`)
    await loadUnits(detail.value.id)
  }
}

/** 产品没维护工艺路线时，工单可以先空着建单，再在这里逐道补；完工/收尾/作废后不再允许 */
const canAddOperation = computed(
  () => !!detail.value && !['FINISHED', 'CLOSED', 'CANCELLED'].includes(String(detail.value.status || ''))
)
const addOpDialog = ref(false)
const addOpSaving = ref(false)
const addOpForm = reactive<any>({
  processId: null,
  planQty: null,
  minutesPerPiece: null,
  price: null,
  needInspection: false
})

const openAddOperation = async () => {
  // 数量默认跟工单一致，工时/工价选工序后带出工序主数据的默认值
  Object.assign(addOpForm, {
    processId: null,
    planQty: Number(detail.value?.planQty || 0) || null,
    minutesPerPiece: null,
    price: null,
    needInspection: false
  })
  addOpDialog.value = true
}

const onAddOpProcessChange = (process: any) => {
  if (!process) return
  addOpForm.minutesPerPiece = Number(process.defaultMinutes || 0)
  addOpForm.price = Number(process.defaultPrice || 0)
  addOpForm.needInspection = !!process.needInspection
}

const saveAddOperation = async () => {
  if (!addOpForm.processId) {
    ElMessage.warning('请选择工序')
    return
  }
  addOpSaving.value = true
  try {
    await api.post(`/work-orders/${detail.value.id}/operations`, {
      processId: addOpForm.processId,
      planQty: addOpForm.planQty,
      minutesPerPiece: addOpForm.minutesPerPiece,
      price: addOpForm.price,
      needInspection: addOpForm.needInspection
    })
    ElMessage.success('工序已添加')
    addOpDialog.value = false
    await refreshDetail()
  } finally {
    addOpSaving.value = false
  }
}

const canAdjustBom = computed(
  () => !!detail.value && !CLOSED_STATUS.includes(String(detail.value.status || ''))
)

const bomOperationText = (row: any) => {
  if (!row?.operationSeq) return '-'
  const op = (detail.value?.operations || []).find(
    (item: any) => Number(item.seq) === Number(row.operationSeq)
  )
  return op ? `${row.operationSeq}. ${op.name}` : `${row.operationSeq}`
}

const openBomAdjust = (line?: any) => {
  if (!canAdjustBom.value) {
    ElMessage.warning('工单已完工或已关闭，不能再调整 BOM')
    return
  }
  Object.assign(bomAdjust, {
    editing: !!line,
    materialId: line?.materialId ?? null,
    materialLabel: line?.materialCode
      ? `${line.materialCode} · ${line.materialName}`
      : (line?.materialName || ''),
    planQty: line ? Number(line.needQty || 0) : 1,
    operationSeq: line?.operationSeq ?? null,
    remark: line?.remark || '',
    reason: ''
  })
  bomDialog.value = true
}

const submitBomAdjust = async () => {
  if (!bomAdjust.materialId) {
    ElMessage.warning('请选择物料')
    return
  }
  if (!bomAdjust.planQty || Number(bomAdjust.planQty) <= 0) {
    ElMessage.warning('请填写大于 0 的用量')
    return
  }
  if (!String(bomAdjust.reason || '').trim()) {
    ElMessage.warning('请填写调整原因')
    return
  }
  bomSaving.value = true
  try {
    const result = await api.post(`/work-orders/${detail.value.id}/bom`, {
      materialId: bomAdjust.materialId,
      planQty: Number(bomAdjust.planQty),
      operationSeq: bomAdjust.operationSeq || null,
      remark: bomAdjust.remark || '',
      reason: String(bomAdjust.reason).trim()
    })
    ElMessage.success('BOM 已调整')
    bomDialog.value = false
    await refreshDetail()
    const needIssueQty = Number(result?.needIssueQty || 0)
    if (needIssueQty > 0) {
      const line = (detail.value?.bom || []).find(
        (item: any) => Number(item.materialId) === Number(bomAdjust.materialId)
      )
      const unitText = line?.unit ? ` ${line.unit}` : ''
      ElMessage.warning(`调整后还需领料 ${fmtQty(needIssueQty)}${unitText}`)
    }
  } finally {
    bomSaving.value = false
  }
}

const removeBomLine = async (line: any) => {
  try {
    const { value } = await ElMessageBox.prompt(
      `删除物料「${line.materialName}」需要填写原因，已领用的物料不能删除。`,
      '删除 BOM 行',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        inputPlaceholder: '如：客户临时改配',
        inputValidator: (input: string) => (input && String(input).trim() ? true : '请填写删除原因')
      }
    )
    await api.delete(
      `/work-orders/${detail.value.id}/bom/${line.bomId}?reason=${encodeURIComponent(String(value).trim())}`
    )
    ElMessage.success('BOM 行已删除')
    await refreshDetail()
  } catch {
    // 取消删除或后端拒绝时，接口层已给出提示
  }
}

const resetBom = async () => {
  try {
    await ElMessageBox.confirm(
      '恢复标准 BOM 会丢弃本工单的全部非标调整，已领料时系统将拒绝恢复。确认继续？',
      '恢复标准 BOM',
      { type: 'warning' }
    )
    await api.post(`/work-orders/${detail.value.id}/bom/reset`)
    ElMessage.success('已恢复标准 BOM')
    bomDialog.value = false
    await refreshDetail()
  } catch {
    // 取消或后端拒绝
  }
}

const release = async (row: any) => {
  await ElMessageBox.confirm(`下达工单 ${row.workNo}？下达后可以领料和报工。`, '下达确认')
  await api.post(`/work-orders/${row.id}/release`)
  ElMessage.success('工单已下达')
  load()
  refreshDetail()
}

const goSchedule = () => {
  router.push('/planning/schedule')
}

const openIssue = async (row: any) => {
  const data = row?.bom ? row : await api.get(`/work-orders/${row.id}`)
  issueWork.value = data
  issueRows.value = (data.bom || [])
    .filter((line: any) => String(line.source || '') !== 'SUBSTITUTE')
    .map((line: any) => {
      const remaining = Math.max(
        0,
        Number(line.needQty || 0) - Number(line.issuedQty || 0) + Number(line.returnedQty || 0)
      )
      return { ...line, remainingQty: remaining, qty: remaining }
    })
  if (!issueRows.value.some((line: any) => line.remainingQty > 0)) {
    ElMessage.warning('该工单物料已领足，无需继续领料')
    return
  }
  // 默认落到第一个原料仓，不再整表加载仓库
  let warehouseOptions = await api.get('/options/warehouses', { type: 'RAW', limit: 1 })
  if (!warehouseOptions?.length) warehouseOptions = await api.get('/options/warehouses', { limit: 1 })
  const rawWarehouse = warehouseOptions?.[0] || null
  issueForm.warehouseId = rawWarehouse ? rawWarehouse.id : null
  issueForm.warehouseLabel = rawWarehouse
    ? (rawWarehouse.code ? `${rawWarehouse.code} · ${rawWarehouse.name}` : rawWarehouse.name)
    : ''
  issueForm.issuer = auth.user?.realName || auth.user?.username || ''
  issueForm.team = ''
  issueForm.remark = ''
  issueDialog.value = true
}

const fillAllIssue = () => {
  issueRows.value.forEach((line: any) => {
    line.qty = line.remainingQty
  })
}

const submitIssue = async () => {
  if (!issueForm.warehouseId) {
    ElMessage.warning('请选择领料仓库')
    return
  }
  if (!issueForm.issuer || !issueForm.issuer.trim()) {
    ElMessage.warning('请填写领料人')
    return
  }
  const invalid = issueRows.value.find((line: any) => Number(line.qty) > Number(line.remainingQty))
  if (invalid) {
    ElMessage.warning(`${invalid.materialName} 领料数量超过剩余需求`)
    return
  }
  const items = issueRows.value.filter((line: any) => Number(line.qty) > 0)
  if (!items.length) {
    ElMessage.warning('请填写领料数量')
    return
  }
  issueSaving.value = true
  try {
    // 一次提交多行，后端在同一个事务里生成一张领料单，避免部分成功
    const result = await api.post(`/work-orders/${issueWork.value.id}/issue-batch`, {
      warehouseId: issueForm.warehouseId,
      issuer: issueForm.issuer.trim(),
      team: issueForm.team?.trim() || '',
      remark: issueForm.remark?.trim() || '',
      source: 'PC',
      items: items.map((line: any) => ({ productId: line.materialId, qty: Number(line.qty) }))
    })
    const alternatives = (result?.lines || [])
      .filter((line: any) => Number(line.alternativeQty || 0) > 0)
      .map((line: any) => `主料不足，已用替代料 ${line.alternativeMaterialName} 补 ${fmtQty(line.alternativeQty)}`)
    ElMessage.success(`领料单 ${result?.issueNo || ''} 已生成，共 ${items.length} 项`)
    if (alternatives.length) {
      ElMessage.warning(alternatives.join('；'))
    }
    issueDialog.value = false
    load()
    if (detailDrawer.value) refreshDetail()
  } finally {
    issueSaving.value = false
  }
}

const openLabels = (row: any) => {
  // 标签打印是脱离主框架的独立页，优先单开一个标签页；被浏览器拦截时退回当前页跳转
  const target = { path: '/print/labels', query: { workNo: row.workNo } }
  const opened = window.open(router.resolve(target).href, '_blank')
  if (!opened) router.push(target)
}

const openUnits = async (row: any) => {
  await openDetail(row)
  await nextTick()
  document.querySelector('.unit-section')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const openUnitsPage = (workNo: string) => {
  router.push({ path: '/production/units', query: { keyword: workNo } })
}

const openReport = async (row: any) => {
  const data = await api.get(`/work-orders/${row.id}`)
  reportWork.value = data
  const nextOp = (data.operations || []).find((op: any) => Number(op.doneQty) < Number(op.planQty))
  const op = nextOp || data.operations?.[0]
  Object.assign(reportForm, {
    operationId: op ? op.id : null,
    qty: nextOp ? Number(nextOp.planQty) - Number(nextOp.doneQty) : 0,
    defectQty: 0,
    // 报工人：有分配名单就取第一个还有剩余量的人，否则取派工人员，再否则当前登录用户
    worker: firstOpenAssignee(op) || auth.user?.realName || auth.user?.username || '',
    remark: '',
    inspectionResult: '',
    inspectionRemark: ''
  })
  reportDialog.value = true
}

/** 分配名单里第一个还有剩余量的人（都报满了就退回第一个人） */
const firstOpenAssignee = (op: any) => {
  const rows = op?.assignments || []
  if (!rows.length) return null
  const open = rows.find((row: any) => Number(row.remainingQty) > 0)
  return (open || rows[0]).workerName
}

/** 切工序时按新工序的剩余量重新预填，避免沿用上一道工序的数量 */
const onReportOperationChange = (operationId: number) => {
  const op = (reportWork.value?.operations || []).find((item: any) => item.id === operationId)
  if (!op) return
  reportForm.qty = Math.max(0, Number(op.planQty) - Number(op.doneQty))
  reportForm.defectQty = 0
  reportForm.inspectionResult = ''
  reportForm.inspectionRemark = ''
  // 有分配名单就跟着名单走，否则沿用派工人员
  reportForm.worker = firstOpenAssignee(op) || (reportForm.worker ? reportForm.worker : op.assignedWorker || '')
}

/** 把报工数量填回本工序剩余量，改坏了点一下就好 */
const fillReportRemain = () => {
  const op = reportOperation.value
  if (!op) return
  reportForm.qty = Math.max(0, Number(op.planQty) - Number(op.doneQty))
  reportForm.defectQty = 0
}

const submitReport = async () => {
  if (!reportForm.operationId || !reportForm.qty || reportForm.qty <= 0) {
    ElMessage.warning('请选择工序并填写报工数量')
    return
  }
  if (Number(reportForm.defectQty || 0) > Number(reportForm.qty)) {
    ElMessage.warning('不良数量不能大于报工数量')
    return
  }
  if (reportNeedInspect.value && !reportForm.inspectionResult) {
    ElMessage.warning('该工序是工序内检验点，请选择检验结论')
    return
  }
  reportSaving.value = true
  try {
    const result = await api.post(`/work-orders/${reportWork.value.id}/report`, {
      operationId: reportForm.operationId,
      qty: Number(reportForm.qty),
      qualifiedQty: reportQualified.value,
      defectQty: Number(reportForm.defectQty || 0),
      worker: reportForm.worker,
      remark: reportForm.remark,
      inspectionResult: reportNeedInspect.value ? reportForm.inspectionResult : null,
      inspectionRemark: reportForm.inspectionRemark
    })
    ElMessage.success(result?.finished ? '报工成功，工单已完工' : '报工成功')
    reportDialog.value = false
    load()
    if (detailDrawer.value) refreshDetail()
  } finally {
    reportSaving.value = false
  }
}

/** 打开派工弹窗时把现有分配名单填进去：没有分配就按「一个人、整单计划量」预填 */
const fillAssignRows = () => {
  const op = (assignWork.value?.operations || []).find((item: any) => item.id === assignForm.operationId)
  const current = op?.assignments || []
  if (current.length) {
    assignForm.rows = current.map((row: any) => ({
      userId: row.userId,
      workerName: row.workerName || '',
      quotaQty: Number(row.quotaQty)
    }))
    return
  }
  assignForm.rows = [{
    userId: op?.assignedUserId || null,
    workerName: op?.assignedWorker || '',
    quotaQty: Number(op?.planQty || 0)
  }]
}

const openAssign = async (row: any) => {
  const data = row?.operations ? row : await api.get(`/work-orders/${row.id}`)
  assignWork.value = data
  const nextOp = (data.operations || []).find((op: any) => Number(op.doneQty) < Number(op.planQty))
  const op = nextOp || data.operations?.[0]
  await loadAssignUsers(op?.processId)
  Object.assign(assignForm, {
    operationId: op ? op.id : null,
    reportPolicy: op?.reportPolicy || 'ANY',
    rows: []
  })
  fillAssignRows()
  assignDialog.value = true
}

const openAssignFor = async (op: any) => {
  assignWork.value = detail.value
  await loadAssignUsers(op.processId)
  Object.assign(assignForm, {
    operationId: op.id,
    reportPolicy: op.reportPolicy || 'ANY',
    rows: []
  })
  fillAssignRows()
  assignDialog.value = true
}

/** 派工候选人：工序启用了持证管控就只列持证人员，两者都走远程搜索，避免一次拉全量用户。
 * 没配置技能时放开所有人；配置了技能但当前关键字没匹配到时仍只在持证范围内检索。
 */
const loadAssignUsers = async (processId?: number) => {
  assignProcessId.value = processId || null
  if (!processId) {
    assignSkillScoped.value = false
    assignSkillHint.value = '该工序没有绑定工序主数据，可选任意人员'
    return
  }
  try {
    const skilled = await api.get(`/processes/${processId}/skill-users`, { limit: 20 })
    assignSkillScoped.value = Array.isArray(skilled) && skilled.length > 0
    assignSkillHint.value = assignSkillScoped.value
      ? '该工序启用了持证管控，只能派给持证人员；输入姓名或账号搜索'
      : '该工序还没配置技能，可选任意人员；到「用户管理 → 技能」可以限定持证范围'
  } catch {
    assignSkillScoped.value = false
    assignSkillHint.value = '技能信息加载失败，已放开全部人员'
  }
}

const onAssignOpChange = async () => {
  const op = (assignWork.value?.operations || []).find((item: any) => item.id === assignForm.operationId)
  await loadAssignUsers(op?.processId)
  assignForm.reportPolicy = op?.reportPolicy || 'ANY'
  fillAssignRows()
}

const addAssignRow = () => {
  assignForm.rows.push({ userId: null, workerName: '', quotaQty: null })
}

const removeAssignRow = (index: number) => {
  assignForm.rows.splice(index, 1)
  if (!assignForm.rows.length) {
    assignForm.rows.push({ userId: null, workerName: '', quotaQty: null })
  }
}

const assignPlanQty = computed(() => {
  const op = (assignWork.value?.operations || []).find((item: any) => item.id === assignForm.operationId)
  return Number(op?.planQty || 0)
})

const assignQuotaTotal = computed(() =>
  (assignForm.rows || []).reduce((sum: number, row: any) => sum + Number(row.quotaQty || 0), 0)
)

const submitAssign = async () => {
  if (!assignForm.operationId) {
    ElMessage.warning('请选择工序')
    return
  }
  const items = (assignForm.rows || [])
    .filter((row: any) => row.userId)
    .map((row: any) => ({ userId: row.userId, quotaQty: Number(row.quotaQty || 0) || null }))
  if (!items.length) {
    ElMessage.warning('请至少选一个派工人员')
    return
  }
  assignSaving.value = true
  try {
    // 新接口整体替换分配名单：一人也行，多人时每人带自己的分配量
    await api.post(`/work-orders/${assignWork.value.id}/operations/${assignForm.operationId}/assignments`, {
      items,
      reportPolicy: assignForm.reportPolicy || 'ANY'
    })
    ElMessage.success('派工已保存')
    assignDialog.value = false
    load()
    if (detailDrawer.value) refreshDetail()
  } finally {
    assignSaving.value = false
  }
}

const openReturn = async (row: any) => {
  returnWork.value = row
  const [work, lines] = await Promise.all([
    api.get(`/work-orders/${row.id}`),
    api.get(`/work-orders/${row.id}/returnable`)
  ])
  returnWork.value = work
  returnRows.value = (lines || [])
    .filter((line: any) => Number(line.returnableQty) > 0)
    .map((line: any) => ({
      ...line,
      qty: Number(line.suggestReturnQty || 0),
      warehouseId: null
    }))
  if (!returnRows.value.length) {
    ElMessage.warning('当前工单没有可退物料，请先完成领料')
    return
  }
  returnForm.warehouseId = null
  returnForm.warehouseLabel = ''
  returnForm.returner = auth.user?.realName || auth.user?.username || ''
  returnForm.reason = ''
  returnDialog.value = true
}

const fillSuggestReturn = () => {
  let count = 0
  for (const line of returnRows.value) {
    if (Number(line.suggestReturnQty) > 0) {
      line.qty = Number(line.suggestReturnQty)
      count += 1
    }
  }
  if (!count) {
    ElMessage.info('这批料没有超领，按可退数量手动填写即可')
  }
}

const submitReturn = async () => {
  const invalid = returnRows.value.find((line: any) => Number(line.qty) > Number(line.returnableQty))
  if (invalid) {
    ElMessage.warning(`${invalid.materialName} 退料数量超过可退数量 ${fmtQty(invalid.returnableQty)}`)
    return
  }
  const items = returnRows.value
    .filter((line: any) => Number(line.qty) > 0)
    .map((line: any) => ({
      productId: line.materialId,
      qty: Number(line.qty),
      warehouseId: line.warehouseId || undefined
    }))
  if (!items.length) {
    ElMessage.warning('请填写退料数量')
    return
  }
  returnSaving.value = true
  try {
    const result = await api.post(`/work-orders/${returnWork.value.id}/return-material`, {
      warehouseId: returnForm.warehouseId || undefined,
      returner: returnForm.returner,
      reason: returnForm.reason || undefined,
      source: 'PC',
      items
    })
    ElMessage.success(`退料单 ${result?.returnNo || ''} 已生成，物料已回库`)
    returnDialog.value = false
    load()
    if (detailDrawer.value) refreshDetail()
  } finally {
    returnSaving.value = false
  }
}

const openQr = async (row: any) => {
  qrDialog.value = true
  qrLoading.value = true
  qrWork.value = null
  qrWorkUrl.value = ''
  qrOps.value = []
  try {
    const data = row?.operations ? row : await api.get(`/work-orders/${row.id}`)
    qrWork.value = data
    qrWorkUrl.value = await QRCode.toDataURL(`MES|WO|${data.workNo}`, { width: 240, margin: 1 })
    const cards: any[] = []
    for (const op of data.operations || []) {
      cards.push({
        seq: op.seq,
        name: op.name,
        url: await QRCode.toDataURL(`MES|OP|${data.workNo}|${op.seq}`, { width: 190, margin: 1 })
      })
    }
    qrOps.value = cards
  } finally {
    qrLoading.value = false
  }
}

const printQr = () => {
  if (!qrWork.value) return
  const win = window.open('', '_blank', 'width=820,height=920')
  if (!win) {
    ElMessage.warning('浏览器阻止了打印窗口，请允许本站弹出窗口')
    return
  }
  const work = qrWork.value
  const opsHtml = qrOps.value
    .map(
      (op: any) => `
    <div class="card">
      <img src="${op.url}" alt="工序码" />
      <div class="name">${op.seq}. ${op.name}</div>
      <div class="code">MES|OP|${work.workNo}|${op.seq}</div>
    </div>`
    )
    .join('')
  win.document.write(`<!doctype html>
<html><head><meta charset="utf-8" /><title>${work.workNo} 工单二维码</title>
<style>
  * { box-sizing: border-box; }
  body { margin: 24px; font-family: "Microsoft YaHei", sans-serif; color: #101828; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  .sub { color: #667085; font-size: 12px; margin-bottom: 18px; }
  .grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; }
  .card { border: 1px dashed #98a2b3; border-radius: 8px; padding: 12px; text-align: center; break-inside: avoid; }
  .card.main { grid-column: span 2; }
  .card img { width: 190px; height: 190px; }
  .card.main img { width: 240px; height: 240px; }
  .name { font-size: 13px; font-weight: 600; margin-top: 6px; }
  .code { font-family: Consolas, monospace; font-size: 11px; color: #475467; word-break: break-all; margin-top: 2px; }
</style></head>
<body>
  <h1>工单 ${work.workNo}</h1>
  <div class="sub">${work.productName || ''} · ${work.productCode || ''} · 计划 ${fmtQty(work.planQty)} ${work.unit || ''}</div>
  <div class="grid">
    <div class="card main">
      <img src="${qrWorkUrl.value}" alt="工单码" />
      <div class="name">工单码</div>
      <div class="code">MES|WO|${work.workNo}</div>
    </div>
    ${opsHtml}
  </div>
</body></html>`)
  win.document.close()
  win.focus()
  setTimeout(() => win.print(), 600)
}

const handleMore = (command: string, row: any) => {
  if (command === 'assign') openAssign(row)
  else if (command === 'units') openUnits(row)
  else if (command === 'labels') openLabels(row)
  else if (command === 'qr') openQr(row)
  else if (command === 'cancel') cancelWorkOrder(row)
  else if (command === 'close') closeWork(row)
}

/** 还没开工的工单可以作废：客户取消、下单错误时走这条路，已领料/已报工的会被后端拦住 */
const canCancelWork = (row: any) =>
  ['DRAFT', 'SCHEDULED', 'RELEASED'].includes(String(row?.status || '')) && auth.can('production:cancel')

const cancelWorkOrder = async (row: any) => {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `作废工单 ${row.workNo}？作废后不能再报工、领料，排产占用会释放；已领料、已报工或有在途外协的工单不允许作废。`,
      '作废工单',
      {
        type: 'warning',
        inputPlaceholder: '作废原因（选填），例如：客户取消订单',
        inputValue: '',
        confirmButtonText: '确认作废',
        cancelButtonText: '再想想'
      }
    )
    reason = String(value || '').trim()
  } catch {
    return
  }
  await api.post(`/work-orders/${row.id}/cancel?reason=${encodeURIComponent(reason)}`)
  ElMessage.success('工单已作废')
  if (detailDrawer.value && detail.value?.id === row.id) {
    detailDrawer.value = false
  }
  load()
}

const closeWork = async (row: any) => {
  await ElMessageBox.confirm(`关闭工单 ${row.workNo}？关闭后不再允许报工。`, '关闭确认')
  await api.post(`/work-orders/${row.id}/close`)
  ElMessage.success('工单已关闭')
  load()
  refreshDetail()
}

const progress = (row: any) => {
  const plan = Number(row.planQty || 0)
  if (!plan) return 0
  return Math.min(100, Math.round((Number(row.doneQty || 0) / plan) * 100))
}

/** 齐套率 = 已领 / 需求，班长不用点开就知道能不能开工 */
const kitRate = (row: any) => {
  const need = Number(row.materialNeedQty || 0)
  if (!need) return null
  const pending = Number(row.materialPendingQty || 0)
  return Math.max(0, Math.min(100, Math.round(((need - pending) / need) * 100)))
}

const kitState = (row: any) => {
  const rate = kitRate(row)
  if (rate === null) return { text: '无 BOM', cls: 'muted', rate: null as number | null }
  const lines = Number(row.shortageLines || 0)
  if (rate >= 100) return { text: '齐套', cls: 'success', rate }
  return { text: `缺 ${lines} 项`, cls: rate >= 60 ? 'warning' : 'danger', rate }
}

const lateDays = (row: any) => {
  const value = row?.deliveryDate
  if (!value) return null
  const target = new Date(`${String(value).slice(0, 10)}T00:00:00`)
  if (Number.isNaN(target.getTime())) return null
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.floor((today.getTime() - target.getTime()) / 86400000)
}

const isLateOrder = (row: any) => {
  if (CLOSED_STATUS.includes(String(row?.status || ''))) return false
  const days = lateDays(row)
  return days !== null && days > 0
}

const opPrice = (op: any) => {
  const value = op?.unitPrice ?? op?.unit_price
  if (value === null || value === undefined || value === '') return null
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

const priceText = (op: any) => {
  const value = opPrice(op)
  return value === null ? '-' : `¥${value.toFixed(2)}`
}

const changePage = (value: number) => {
  page.value = value
  load()
}

onMounted(async () => {
  await table.load()
  await loadCustomFields()
  // 车间看板的「缺料预警」跳过来时，直接勾上只看缺料
  if (route.query.shortage === '1') onlyShortage.value = true
  // 支持从单件追踪、看板等页面带工单号跳过来，直接定位到这张工单
  const fromQuery = String(route.query.keyword || '').trim()
  if (fromQuery) {
    keyword.value = fromQuery
    status.value = ''
  }
  page.value = 1
  await load()
  if (fromQuery && route.query.open === '1' && rows.value.length) {
    await openDetail(rows.value[0])
  }
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">生产工单</h2>
        <p class="page-sub">下达、领料、派工、报工、退料与完工检验，车间执行都落在这张工单上</p>
      </div>
      <el-button v-perm="'production:create'" type="primary" @click="openCreate">新建工单</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="工单号 / 产品名称" clearable style="width: 240px" @keyup.enter="reload" />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="reload">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已排产" value="SCHEDULED" />
          <el-option label="已下达" value="RELEASED" />
          <el-option label="生产中" value="IN_PROGRESS" />
          <el-option label="返工中" value="REWORK" />
          <el-option label="已完工" value="FINISHED" />
          <el-option label="已关闭" value="CLOSED" />
          <el-option label="已作废" value="CANCELLED" />
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
        <el-checkbox v-model="onlyShortage" label="只看缺料" border size="small" @change="reload" />
        <div class="spacer"></div>
        <TableToolbar
          :table="table"
          export-api="/work-orders"
          export-name="生产工单"
          :export-transform="flattenRecords"
          @applied="reload"
        />
        <span class="toolbar-tip">共 {{ total }} 张工单</span>
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
          <template #cell-workNo="{ row }">
            <span class="mono">{{ row.workNo }}</span>
            <span v-if="row.workType === 'REWORK'" class="status-tag danger rework-tag"><i></i>返工</span>
          </template>
          <template #cell-product="{ row }">
            <div>{{ row.productName }}</div>
            <div class="muted" style="font-size: 11px">{{ row.productCode }} · {{ row.spec || '无规格' }}</div>
          </template>
          <template #cell-orderNo="{ row }"><span class="mono">{{ row.orderNo || '手动建单' }}</span></template>
          <template #cell-deliveryDate="{ row }">
            <template v-if="row.deliveryDate">
              <div class="mono" :class="{ 'danger-text': isLateOrder(row) }">{{ row.deliveryDate }}</div>
              <span v-if="isLateOrder(row)" class="status-tag danger late-tag">
                <i></i>逾期 {{ lateDays(row) }} 天
              </span>
              <span v-else class="muted" style="font-size: 11px">正常</span>
            </template>
            <span v-else class="muted">无交期</span>
          </template>
          <template #cell-priority="{ row }"><PriorityTag :priority="row.priority" /></template>
          <template #cell-qty="{ row }">
            <span class="num">{{ fmtQty(row.planQty) }} / {{ fmtQty(row.doneQty) }}</span>
          </template>
          <template #cell-kit="{ row }">
            <template v-if="kitState(row).rate !== null">
              <div class="mini-progress">
                <div class="bar"><span :style="{ width: kitState(row).rate + '%' }"></span></div>
                <span class="pct">{{ kitState(row).rate }}%</span>
              </div>
              <span class="status-tag" :class="kitState(row).cls"><i></i>{{ kitState(row).text }}</span>
            </template>
            <span v-else class="muted">无 BOM</span>
          </template>
          <template #cell-progress="{ row }">
            <div class="mini-progress">
              <div class="bar"><span :style="{ width: progress(row) + '%' }"></span></div>
              <span class="pct">{{ progress(row) }}%</span>
            </div>
          </template>
          <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
          <template #cell-scheduleDate="{ row }">
            <span v-if="row.scheduleDate" class="mono">{{ row.scheduleDate }}</span>
            <span v-else class="muted">未排产</span>
          </template>
          <template #cell-actions="{ row }">
            <!-- 操作顺序固定：详情 · 报工 · 领料 · 退料 · 下达/排产 · 更多。
                 按钮随状态只增不减位置，工人靠肌肉记忆也不会点错。 -->
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'RELEASED' || row.status === 'IN_PROGRESS'"
              v-perm="'production:report'"
              link
              type="primary"
              @click="openReport(row)"
            >
              报工
            </el-button>
            <el-button
              v-if="row.status === 'RELEASED' || row.status === 'IN_PROGRESS'"
              v-perm="'production:issue'"
              link
              type="warning"
              @click="openIssue(row)"
            >
              领料
            </el-button>
            <el-button
              v-if="['RELEASED', 'IN_PROGRESS', 'FINISHED'].includes(row.status)"
              v-perm="'production:return'"
              link
              type="warning"
              @click="openReturn(row)"
            >
              退料
            </el-button>
            <el-button v-if="row.status === 'DRAFT'" v-perm="'production:schedule'" link type="primary" @click="goSchedule">
              去排产
            </el-button>
            <el-button v-if="row.status === 'SCHEDULED'" v-perm="'production:release'" link type="success" @click="release(row)">
              下达
            </el-button>
            <el-dropdown trigger="click" class="more-actions" @command="(command) => handleMore(command, row)">
              <el-button link type="primary">更多</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="auth.can('production:assign')" command="assign">派工</el-dropdown-item>
                  <el-dropdown-item v-if="row.serialManaged" command="units">件码追踪</el-dropdown-item>
                  <el-dropdown-item command="labels">打印标签</el-dropdown-item>
                  <el-dropdown-item command="qr">工单二维码</el-dropdown-item>
                  <el-dropdown-item v-if="canCancelWork(row)" command="cancel" divided>作废工单</el-dropdown-item>
                  <el-dropdown-item
                    v-if="row.status === 'FINISHED' && auth.can('production:close')"
                    command="close"
                    divided
                  >
                    关闭工单
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>

          <template #empty>
            <div class="empty-state">
              <div class="empty-state-icon">工</div>
              <div class="empty-state-title">{{ emptyTitle }}</div>
              <div class="empty-state-desc">{{ emptyDesc }}</div>
              <div class="empty-state-actions">
                <el-button v-if="hasFilter" @click="clearFilters">清空筛选条件</el-button>
                <template v-else>
                  <el-button type="primary" @click="goSales">去销售订单转生产</el-button>
                  <el-button v-perm="'production:create'" @click="openCreate">手工建单</el-button>
                </template>
              </div>
            </div>
          </template>
        </DataTable>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="createDialog" title="新建生产工单" width="820px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="生产产品" required class="full">
            <ReferenceSelect v-model="form.productId" url="/options/products"
                             placeholder="输入物料编码或名称搜索"
                             @select="onProductChange" />
          </el-form-item>
          <el-form-item label="计划数量" required>
            <el-input-number v-model="form.planQty" :min="0.01" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="优先级">
            <el-select v-model="form.priority" style="width: 100%">
              <el-option label="普通" value="NORMAL" />
              <el-option label="加急" value="URGENT" />
              <el-option label="特急" value="RUSH" />
            </el-select>
          </el-form-item>
          <el-form-item label="计划开始">
            <el-date-picker v-model="form.planStart" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="计划完工">
            <el-date-picker v-model="form.planEnd" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="追溯方式">
            <el-select v-model="form.traceMode" style="width: 100%">
              <el-option label="不追溯（按数量报工）" value="NONE" />
              <el-option label="单件追溯（每件一个码）" value="SERIAL" />
              <el-option label="批次追溯（按批次流转）" value="LOT" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" placeholder="客户特殊要求、工艺说明等，会带到工单详情" />
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

        <div v-loading="createPreview.loading" class="create-preview">
          <template v-if="form.productId">
            <div class="preview-head">
              <span class="preview-title">建单后会自动生成</span>
              <span class="muted" v-if="createProduct">
                {{ createProduct.spec || '无规格' }} · 单位 {{ createProduct.unit }} · 当前库存
                {{ fmtQty(createProduct.stock) }} · 损耗率
                {{ Number(createProduct.lossRate || 0) > 0 ? (Number(createProduct.lossRate) * 100).toFixed(2) + '%' : '0' }}
              </span>
            </div>

            <div class="preview-block">
              <div class="preview-label">
                工序路线（{{ createPreview.routing.length }} 道）· 可以直接把报工人定下来
              </div>
              <el-table
                v-if="createPreview.routing.length"
                :data="createPreview.routing"
                size="small"
                max-height="230"
              >
                <el-table-column label="#" width="44" align="center">
                  <template #default="{ $index }">{{ $index + 1 }}</template>
                </el-table-column>
                <el-table-column prop="processName" label="工序" min-width="110" />
                <el-table-column label="工时" width="95" align="right">
                  <template #default="{ row }">{{ fmtQty(row.minutesPerPiece) }} 分/件</template>
                </el-table-column>
                <el-table-column label="检验" width="72" align="center">
                  <template #default="{ row }">
                    <span :class="row.needInspection ? '' : 'muted'">{{ row.needInspection ? '需检验' : '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="报工人" width="170">
                  <template #default="{ row }">
                    <ReferenceSelect
                      v-model="row.assignUserId"
                      url="/system/users/options"
                      code-field="username"
                      label-field="realName"
                      size="small"
                      placeholder="建完再派"
                      @select="(user) => { if (!user) row.reportPolicy = 'ANY' }"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="报工权限" width="140">
                  <template #default="{ row }">
                    <el-select v-model="row.reportPolicy" size="small" style="width: 100%">
                      <el-option label="所有人可报" value="ANY" />
                      <el-option label="仅他一人可报" value="ASSIGNED" :disabled="!row.assignUserId" />
                    </el-select>
                  </template>
                </el-table-column>
              </el-table>
              <div v-else class="preview-empty">
                这个产品还没维护工艺路线。建单后工序是空的，要先去工单详情的「工序」页签逐道添加，加完才能下达。
              </div>
            </div>

            <div class="preview-block">
              <div class="preview-label">
                用料清单（{{ createPreview.bom.length }} 项，需求已含损耗率放大）
              </div>
              <el-table
                v-if="createPreview.bom.length"
                :data="createPreview.bom"
                size="small"
                max-height="190"
              >
                <el-table-column prop="materialCode" label="编码" width="95" />
                <el-table-column prop="materialName" label="物料" min-width="140" />
                <el-table-column label="单件用量" width="110" align="right">
                  <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
                </el-table-column>
                <el-table-column label="本单需求" width="120" align="right">
                  <template #default="{ row }">
                    <span class="num">{{ fmtQty(previewNeed(row)) }} {{ row.unit }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="投料工序" width="95" align="center">
                  <template #default="{ row }">{{ row.operationSeq ? '第 ' + row.operationSeq + ' 道' : '不限' }}</template>
                </el-table-column>
                <el-table-column label="替代料" min-width="120">
                  <template #default="{ row }">
                    <span class="muted">{{ row.substituteMaterialCode ? row.substituteMaterialCode + ' · ' + row.substituteMaterialName : '-' }}</span>
                  </template>
                </el-table-column>
              </el-table>
              <div v-else class="preview-empty">
                这个产品没有 BOM。建单后不会生成用料需求，领料也就没得领 —— 确认是免料工单再往下走。
              </div>
            </div>
          </template>
          <div v-else class="muted" style="font-size: 12px; line-height: 1.8">
            选好产品后，这里会带出它会生成哪些工序、要备哪些料，先看清楚再建单。
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">创建工单</el-button>
      </template>
    </el-dialog>

    <el-drawer append-to-body v-model="detailDrawer" size="940px" title="工单详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-no mono">{{ detail.workNo }}</div>
              <div class="muted" style="margin-top: 4px">
                {{ detail.productName }} · {{ detail.productCode }} · 计划 {{ fmtQty(detail.planQty) }} {{ detail.unit }}
              </div>
            </div>
            <div class="detail-tags">
              <span v-if="detail.workType === 'REWORK'" class="status-tag danger"><i></i>返工工单</span>
              <StatusTag :status="detail.status" />
            </div>
          </div>

          <div class="detail-actions">
            <!-- 与列表保持一致的操作顺序：先干活（报工/领料/退料），再状态动作，最后辅助入口 -->
            <el-button
              v-if="detail.status === 'RELEASED' || detail.status === 'IN_PROGRESS'"
              v-perm="'production:report'"
              size="small"
              type="primary"
              @click="openReport(detail)"
            >
              报工
            </el-button>
            <el-button
              v-if="detail.status === 'RELEASED' || detail.status === 'IN_PROGRESS'"
              v-perm="'production:issue'"
              size="small"
              plain
              type="warning"
              @click="openIssue(detail)"
            >
              按物料领料
            </el-button>
            <el-button v-perm="'production:return'" size="small" plain type="warning" @click="openReturn(detail)">生产退料</el-button>
            <el-button
              v-if="canCancelWork(detail)"
              v-perm="'production:cancel'"
              size="small"
              plain
              type="danger"
              @click="cancelWorkOrder(detail)"
            >
              作废工单
            </el-button>
            <el-button v-if="detail.status === 'DRAFT'" v-perm="'production:schedule'" size="small" plain @click="goSchedule">去排产</el-button>
            <el-button v-if="detail.status === 'SCHEDULED'" v-perm="'production:release'" size="small" type="success" @click="release(detail)">下达</el-button>
            <el-button v-perm="'production:assign'" size="small" plain type="primary" @click="openAssign(detail)">工序派工</el-button>
            <el-button v-if="canAdjustBom" v-perm="'production:bom'" size="small" plain type="warning" @click="openBomAdjust()">调整 BOM</el-button>
            <el-button size="small" plain type="success" @click="openWorkDocs(detail)">作业指导书</el-button>
            <el-button size="small" plain @click="openQr(detail)">工单二维码</el-button>
          </div>

          <el-tabs v-model="detailTab" class="detail-tabs">
            <el-tab-pane label="概览" name="overview">
          <div class="detail-grid">
            <div class="detail-item"><div class="label">来源订单</div><div class="value mono">{{ detail.orderNo || '手动建单' }}</div></div>
            <div v-if="detail.predecessors?.length" class="detail-item">
              <div class="label">前置工单（完工后才能下达）</div>
              <div class="value">
                <el-tag v-for="pre in detail.predecessors" :key="pre.id" size="small" style="margin: 0 6px 6px 0">
                  {{ pre.workNo }} · {{ pre.productName }}（{{ pre.status }}）
                </el-tag>
              </div>
            </div>
            <div v-if="detail.successors?.length" class="detail-item">
              <div class="label">后置工单</div>
              <div class="value">
                <el-tag v-for="next in detail.successors" :key="next.id" size="small" type="info" style="margin: 0 6px 6px 0">
                  {{ next.workNo }} · {{ next.productName }}（{{ next.status }}）
                </el-tag>
              </div>
            </div>
            <div v-if="detail.status === 'CANCELLED'" class="detail-item">
              <div class="label">作废信息</div>
              <div class="value">
                <span class="danger-text">{{ detail.cancelledAt || '-' }} · {{ detail.cancelledBy || '-' }}</span>
                <div class="muted" style="font-size: 11px; margin-top: 2px">{{ detail.cancelReason || '未填原因' }}</div>
              </div>
            </div>
            <div class="detail-item">
              <div class="label">订单交期</div>
              <div class="value">
                <template v-if="detail.deliveryDate">
                  <span class="mono" :class="{ 'danger-text': isLateOrder(detail) }">{{ detail.deliveryDate }}</span>
                  <span v-if="isLateOrder(detail)" class="status-tag danger detail-late">
                    <i></i>逾期 {{ lateDays(detail) }} 天
                  </span>
                  <span v-else class="success-text" style="margin-left: 6px; font-size: 11px">正常</span>
                </template>
                <span v-else class="muted">无交期</span>
              </div>
            </div>
            <div class="detail-item">
              <div class="label">工单类型</div>
              <div class="value">
                <template v-if="detail.workType === 'REWORK'">
                  返工工单 · 原工单 {{ detail.parentWorkNo || '-' }}
                  <div class="muted" style="font-size: 11px; margin-top: 2px">
                    {{
                      detail.reworkFromSeq
                        ? `从第 ${detail.reworkFromSeq} 道工序起返修，前面工序不再重复加工`
                        : '整件返修，从第一道工序起'
                    }}
                  </div>
                </template>
                <template v-else>普通工单</template>
              </div>
            </div>
            <div class="detail-item">
              <div class="label">优先级</div>
              <div class="value"><PriorityTag :priority="detail.priority" /></div>
            </div>
            <div class="detail-item"><div class="label">计划开始</div><div class="value">{{ detail.planStart || '-' }}</div></div>
            <div class="detail-item"><div class="label">计划完工</div><div class="value">{{ detail.planEnd || '-' }}</div></div>
            <div v-if="detail.remark" class="detail-item" style="grid-column: span 3">
              <div class="label">备注</div>
              <div class="value">{{ detail.remark }}</div>
            </div>
            <div class="detail-item">
              <div class="label">排产日期</div>
              <div class="value">
                <span v-if="detail.scheduleDate" class="mono">{{ detail.scheduleDate }}</span>
                <span v-else class="muted">未排产</span>
              </div>
            </div>
            <div class="detail-item"><div class="label">已完成</div><div class="value num">{{ fmtQty(detail.doneQty) }}</div></div>
            <div class="detail-item"><div class="label">合格数量</div><div class="value num">{{ fmtQty(detail.qualifiedQty) }}</div></div>
            <div class="detail-item">
              <div class="label">领料状态</div>
              <div class="value">
                <span class="status-tag" :class="detail.issued ? 'success' : 'draft'">
                  <i></i>{{ detail.issued ? '已领料' : '未领料' }}
                </span>
              </div>
            </div>
            <div class="detail-item">
              <div class="label">追溯方式</div>
              <div class="value">
                <el-select
                  v-if="canEditTrace"
                  :model-value="detail.traceMode || 'NONE'"
                  size="small"
                  style="width: 150px"
                  @change="changeTraceMode"
                >
                  <el-option label="按数量" value="NONE" />
                  <el-option label="单件追溯" value="SERIAL" />
                </el-select>
                <template v-else>
                  <span>{{ traceText(detail.traceMode) }}</span>
                  <span class="muted" style="font-size: 11px; margin-left: 6px">下达后不可改</span>
                </template>
              </div>
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
            </el-tab-pane>

            <el-tab-pane label="工序" name="routing">
          <div class="drawer-section">
            <h4>
              工序进度
              <el-button
                v-if="canAddOperation"
                v-perm="'production:create'"
                size="small"
                type="primary"
                plain
                @click="openAddOperation"
              >
                添加工序
              </el-button>
            </h4>
            <el-alert
              v-if="!(detail.operations || []).length"
              type="info"
              :closable="false"
              show-icon
              style="margin-bottom: 8px"
              title="该产品还没维护工艺路线。可以在这里逐道添加工序，加完再下达；也可以去「物料主数据 → 工艺路线」一次性配好"
            />
            <el-table :data="detail.operations" size="small" empty-text="暂无工序">
              <el-table-column prop="seq" label="序" width="45" />
              <el-table-column label="工序" min-width="130">
                <template #default="{ row }">
                  <div>{{ row.name }}</div>
                </template>
              </el-table-column>
              <el-table-column label="齐套" width="98">
                <template #default="{ row }">
                  <span v-if="row.materialRequired === false" class="status-tag draft"><i></i>无需领料</span>
                  <span v-else-if="row.materialReady === true" class="status-tag success"><i></i>料已齐</span>
                  <span v-else class="status-tag warning"><i></i>待领 {{ fmtQty(row.materialPendingQty) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="检验方式" width="126">
                <template #default="{ row }">
                  <span class="status-tag" :class="modeOf(row.inspectionMode).cls">
                    <i></i>{{ modeOf(row.inspectionMode).label }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="计划 / 完成" width="115" align="right">
                <template #default="{ row }">{{ fmtQty(row.planQty) }} / {{ fmtQty(row.doneQty) }}</template>
              </el-table-column>
              <el-table-column label="派工人员" width="170">
                <template #default="{ row }">
                  <el-tooltip
                    v-if="(row.assignments || []).length"
                    placement="top"
                    :content="(row.assignments || []).map((a: any) => `${a.workerName} ${fmtQty(a.quotaQty)}`).join('，')"
                  >
                    <span>
                      {{ row.assignedWorker }}
                      <span v-if="row.assignments.length > 1" class="muted">等 {{ row.assignments.length }} 人</span>
                    </span>
                  </el-tooltip>
                  <span v-else-if="row.assignedWorker">{{ row.assignedWorker }}</span>
                  <span v-else class="muted">未派工</span>
                  <el-tooltip
                    v-if="row.reportPolicy === 'ASSIGNED'"
                    content="报工权限：只有分配名单里的人能报这道工序"
                    placement="top"
                  >
                    <span class="status-tag" style="margin-left: 4px"><i></i>限名单</span>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="外协" width="112">
                <template #default="{ row }">
                  <template v-if="row.outsourceStatus">
                    <span class="status-tag" :class="outsourceCls(row)">
                      <i></i>{{ outsourceLabel(row) }}
                    </span>
                    <div v-if="row.outsourceSupplierName" class="muted" style="font-size: 11px">
                      {{ row.outsourceSupplierName }}
                    </div>
                    <div v-if="Number(row.outsourceReceivedQty) > 0" class="muted" style="font-size: 11px">
                      已回 {{ fmtQty(row.outsourceReceivedQty) }}
                    </div>
                  </template>
                  <span v-else class="muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="工价" width="90" align="right">
                <template #default="{ row }">
                  <span :class="opPrice(row) === null ? 'muted' : 'money'">{{ priceText(row) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="进度" min-width="120">
                <template #default="{ row }">
                  <div class="mini-progress">
                    <div class="bar">
                      <span :style="{ width: Math.min(100, (Number(row.doneQty) / Number(row.planQty || 1)) * 100) + '%' }"></span>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }"><StatusTag :status="row.status" /></template>
              </el-table-column>
              <el-table-column label="操作" width="300" fixed="right">
                <template #default="{ row }">
                  <el-button v-perm="'production:assign'" link type="primary" @click="openAssignFor(row)">派工</el-button>
                  <el-button
                    v-if="canIssueOperation(row)"
                    v-perm="'production:issue'"
                    link
                    type="warning"
                    :loading="operationIssueLoading === Number(row.seq)"
                    @click="issueByOperation(row)"
                  >
                    按工序领料
                  </el-button>
                  <el-button
                    v-if="canOutsource(row) && !row.outsourceStatus"
                    v-perm="'production:outsource'"
                    link
                    type="info"
                    @click="openOutsourcePlan(row)"
                  >
                    转外协
                  </el-button>
                  <el-button
                    v-if="canOutsource(row) && row.outsourceStatus === 'PENDING_SEND'"
                    v-perm="'production:outsource'"
                    link
                    type="info"
                    @click="openOutsourceSend(row)"
                  >
                    外协发出
                  </el-button>
                  <el-button
                    v-if="canOutsource(row) && row.outsourceStatus === 'PENDING_SEND'"
                    v-perm="'production:outsource'"
                    link
                    @click="cancelOutsourcePlan(row)"
                  >
                    撤销外协
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="drawer-section" v-if="(detail.outsourceOrders || []).length">
            <h4>外协记录</h4>
            <el-table :data="detail.outsourceOrders" size="small">
              <el-table-column label="外协单号" width="160">
                <template #default="{ row }">
                  <el-button link type="primary" class="mono" @click="openOutsourceOrder(row)">{{ row.orderNo }}</el-button>
                </template>
              </el-table-column>
              <el-table-column label="工序" width="110">
                <template #default="{ row }">{{ row.operationSeq }} · {{ row.operationName }}</template>
              </el-table-column>
              <el-table-column prop="supplierName" label="外协厂" min-width="140" show-overflow-tooltip />
              <el-table-column label="发出 / 收回" width="130" align="right">
                <template #default="{ row }">
                  {{ fmtQty(row.qty) }} / {{ fmtQty(row.receivedQty) }}
                  <span v-if="Number(row.defectQty) > 0" class="muted">（不良 {{ fmtQty(row.defectQty) }}）</span>
                </template>
              </el-table-column>
              <el-table-column label="约定回厂" width="110">
                <template #default="{ row }">
                  <span :class="row.overdue ? 'danger-text' : ''">{{ row.expectedAt || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="95">
                <template #default="{ row }">
                  <span class="status-tag" :class="row.overdue ? 'danger' : row.status === 'RECEIVED' ? 'success' : 'warning'">
                    <i></i>{{ row.status === 'SENT' ? '已发出' : row.status === 'PARTIAL' ? '部分收回' : '已收回' }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
            <div class="section-note">
              外协发出的料不再走本厂领料；收回在「生产 → 外协管理」里登记，数量和工序完成数一起推进。
            </div>
          </div>
            </el-tab-pane>

            <el-tab-pane label="物料" name="material">
          <div class="drawer-section">
            <h4>
              <span>工单 BOM</span>
              <el-button size="small" plain @click="openBomDiff">对比标准 BOM</el-button>
            </h4>
            <el-table :data="detail.bom" size="small" empty-text="未维护 BOM">
              <el-table-column label="物料" min-width="180">
                <template #default="{ row }">
                  <div>{{ row.materialName }}</div>
                  <div class="muted" style="font-size: 11px">{{ row.materialCode }} · {{ row.spec || '无规格' }}</div>
                  <div v-if="isSubstituteLine(row)" class="substitute-tip">替代料行 · 随主料领用</div>
                  <div v-else-if="row.substituteMaterialName" class="substitute-tip">
                    替代料：{{ row.substituteMaterialName }}<template v-if="row.substituteStock !== null && row.substituteStock !== undefined">（库存 {{ fmtQty(row.substituteStock) }}）</template>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="需求数量" width="90" align="right">
                <template #default="{ row }">
                  <span v-if="isSubstituteLine(row)" class="muted" style="font-size: 11px">随主料</span>
                  <template v-else>
                    <span class="num">{{ fmtQty(row.needQty) }}</span>
                    <span class="muted" style="font-size: 11px"> {{ row.unit }}</span>
                  </template>
                </template>
              </el-table-column>
              <el-table-column label="已领" width="75" align="right">
                <template #default="{ row }">
                  {{ fmtQty(row.issuedQty) }}
                  <div v-if="Number(row.outsourceQty) > 0" class="substitute-tip">
                    含外协 {{ fmtQty(row.outsourceQty) }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="可退" width="75" align="right">
                <template #default="{ row }">
                  <span :class="Number(row.returnableQty) > 0 ? 'success-text' : 'muted'">{{ fmtQty(row.returnableQty) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="投料工序" width="105">
                <template #default="{ row }">
                  <span v-if="row.operationSeq">{{ bomOperationText(row) }}</span>
                  <span v-else class="muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="来源" width="80">
                <template #default="{ row }">
                  <span class="status-tag" :class="bomSource(row).cls">
                    <i></i>{{ bomSource(row).label }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="备注" min-width="110" show-overflow-tooltip>
                <template #default="{ row }">
                  <span :class="row.remark ? '' : 'muted'">{{ row.remark || '-' }}</span>
                </template>
              </el-table-column>
            </el-table>
            <div class="section-note">
              物料需求 = 标准 BOM 单耗 × 工单数量 × (1 + 损耗率)，来源为「非标」的行是在本工单内手工调整的结果；来源为「替代料」的行随主料自动补足，不单独维护需求量。
            </div>
          </div>

          <div class="drawer-section">
            <h4>
              <span>领料记录</span>
              <span class="toolbar-tip">共 {{ (detail.issues || []).length }} 张领料单</span>
            </h4>
            <el-table :data="detail.issues || []" size="small" empty-text="还没有领料记录">
              <el-table-column label="领料单号" width="150">
                <template #default="{ row }"><span class="mono">{{ row.issueNo }}</span></template>
              </el-table-column>
              <el-table-column prop="warehouseName" label="仓库" width="105" />
              <el-table-column prop="issuer" label="领料人" width="90" />
              <el-table-column label="班组" width="100">
                <template #default="{ row }">{{ row.team || '-' }}</template>
              </el-table-column>
              <el-table-column label="数量" width="90" align="right">
                <template #default="{ row }"><span class="num">{{ fmtQty(row.totalQty) }}</span></template>
              </el-table-column>
              <el-table-column label="明细" width="70" align="center">
                <template #default="{ row }">{{ row.itemCount }} 项</template>
              </el-table-column>
              <el-table-column label="来源" width="80">
                <template #default="{ row }">{{ row.source === 'SCAN' ? '扫码' : 'PC' }}</template>
              </el-table-column>
              <el-table-column prop="createdAt" label="领料时间" width="140" />
            </el-table>
            <div class="section-note">
              每张领料单对应一次领料动作，库存流水通过单号回指；替代料单独记行，并标注被替代的主料。
            </div>
          </div>

          <div v-if="(detail.returns || []).length" class="drawer-section">
            <h4>
              <span>退料记录</span>
              <span class="toolbar-tip">共 {{ (detail.returns || []).length }} 张退料单</span>
            </h4>
            <el-table :data="detail.returns || []" size="small" empty-text="还没有退料记录">
              <el-table-column label="退料单号" width="150">
                <template #default="{ row }"><span class="mono">{{ row.returnNo }}</span></template>
              </el-table-column>
              <el-table-column prop="warehouseName" label="退回仓库" width="105" />
              <el-table-column prop="returner" label="退料人" width="90" />
              <el-table-column label="数量" width="90" align="right">
                <template #default="{ row }"><span class="num">{{ fmtQty(row.totalQty) }}</span></template>
              </el-table-column>
              <el-table-column label="明细" width="70" align="center">
                <template #default="{ row }">{{ row.itemCount }} 项</template>
              </el-table-column>
              <el-table-column label="原因" min-width="120">
                <template #default="{ row }">{{ row.reason || '-' }}</template>
              </el-table-column>
              <el-table-column prop="createdAt" label="退料时间" width="140" />
            </el-table>
            <div class="section-note">
              退料按原领料批次回库，批次号不变，成品到原料的追溯链不会断；一张退料单内多行物料可一次提交。
            </div>
          </div>
            </el-tab-pane>

            <el-tab-pane v-if="hasUnitSection" label="单件" name="unit">
          <div
            v-if="detail.traceMode === 'SERIAL' && unitData && unitData.summary && unitData.summary.total === 0"
            class="drawer-section"
          >
            <h4>
              <span>单件追踪</span>
              <span class="toolbar-tip">这张工单按单件追溯，还没有件码</span>
            </h4>
            <div class="section-note">
              件码通常在下达工单时按计划数量自动生成；这单还没生成，可以在这里补生成后再打印件码标签。
            </div>
            <el-button type="primary" plain size="small" :loading="unitGenerating" @click="generateUnits">
              生成件码
            </el-button>
          </div>

          <div
            v-if="unitData && unitData.summary && unitData.summary.total > 0"
            v-loading="unitLoading"
            class="drawer-section unit-section"
          >
            <h4>
              <span>单件追踪</span>
              <span class="toolbar-tip">
                共 {{ unitData.summary.total }} 件 ·
                <el-button link type="primary" @click="openUnitsPage(detail.workNo)">在单件追踪里查</el-button>
              </span>
            </h4>
            <div class="unit-summary">
              <span class="unit-chip">待投产 {{ unitData.summary.pending }}</span>
              <span class="unit-chip warn">在制 {{ unitData.summary.inProgress }}</span>
              <span class="unit-chip success">已完工 {{ unitData.summary.finished }}</span>
              <span class="unit-chip danger">已报废 {{ unitData.summary.scrapped }}</span>
            </div>
            <div class="unit-dist">
              <div v-for="bucket in unitData.distribution" :key="bucket.key" class="unit-dist-item">
                <div class="unit-dist-head">
                  <span>{{ bucket.label }}</span>
                  <b class="num">{{ bucket.count }}</b>
                </div>
                <div class="unit-dist-codes">
                  {{ (bucket.units || []).join('、') }}
                  <span v-if="bucket.count > (bucket.units || []).length"> 等 {{ bucket.count }} 件</span>
                </div>
              </div>
            </div>
            <el-table :data="unitData.units" size="small" max-height="280">
              <el-table-column label="件码" width="180">
                <template #default="{ row }"><span class="mono">{{ row.unitNo }}</span></template>
              </el-table-column>
              <el-table-column label="状态" width="95">
                <template #default="{ row }">
                  <span class="status-tag" :class="unitStatusCls(row.status)">
                    <i></i>{{ unitStatusLabel(row.status) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="当前工序" width="110">
                <template #default="{ row }">{{ row.currentOperationName || '-' }}</template>
              </el-table-column>
              <el-table-column label="最后操作人" width="100">
                <template #default="{ row }">{{ row.lastWorker || '-' }}</template>
              </el-table-column>
              <el-table-column label="最后操作时间" width="140">
                <template #default="{ row }">{{ row.lastActionText || '-' }}</template>
              </el-table-column>
            </el-table>
            <el-pagination
              v-if="(unitData.total || unitData.summary?.total || 0) > unitPageSize"
              size="small"
              layout="total, prev, pager, next"
              :total="Number(unitData.total || unitData.summary?.total || 0)"
              :page-size="unitPageSize"
              :current-page="unitPage"
              style="margin-top: 8px; justify-content: flex-end"
              @current-change="changeUnitPage"
            />
            <div class="section-note">
              每件的开工、完工、报废都会留痕；车间扫件码可继续流转。同一道工序内的多件状态相同，件码是区分它们的最小单位。
            </div>
          </div>
            </el-tab-pane>

            <el-tab-pane label="记录" name="records">
          <div class="drawer-section">
            <h4>BOM 变更</h4>
            <el-table :data="detail.bomChanges || []" size="small" empty-text="暂无 BOM 变更">
              <el-table-column prop="changedAt" label="时间" width="150" />
              <el-table-column label="动作" width="90">
                <template #default="{ row }">
                  <span class="status-tag" :class="bomActionMap[row.action]?.cls || 'info'">
                    <i></i>{{ bomActionMap[row.action]?.label || row.action }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column prop="materialName" label="物料" min-width="130" show-overflow-tooltip />
              <el-table-column label="原数量 → 新数量" width="135" align="right">
                <template #default="{ row }">
                  <span class="num">
                    {{ row.oldQty === null || row.oldQty === undefined ? '-' : fmtQty(row.oldQty) }}
                    →
                    {{ row.newQty === null || row.newQty === undefined ? '-' : fmtQty(row.newQty) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="原因" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">
                  <span :class="row.reason ? '' : 'muted'">{{ row.reason || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="changedBy" label="操作人" width="90" />
            </el-table>
          </div>

          <div class="drawer-section">
            <h4>报工记录</h4>
            <el-table :data="detail.reports" size="small" empty-text="暂无报工">
              <el-table-column prop="operationName" label="工序" width="95" />
              <el-table-column prop="qty" label="报工数" width="80" align="right" />
              <el-table-column label="合格数" width="80" align="right">
                <template #default="{ row }">{{ fmtQty(row.qualifiedQty) }}</template>
              </el-table-column>
              <el-table-column label="不良数" width="80" align="right">
                <template #default="{ row }">
                  <span :class="Number(row.defectQty) > 0 ? 'danger-text' : ''">{{ fmtQty(row.defectQty) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="worker" label="操作工" width="90" />
              <el-table-column prop="reportedAt" label="时间" width="125" />
              <el-table-column prop="remark" label="备注" min-width="110" show-overflow-tooltip />
            </el-table>
          </div>
            </el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </el-drawer>

    <el-drawer append-to-body v-model="docDrawer" size="820px" title="作业指导书（SOP / SIP）">
      <div v-loading="docLoading">
        <div class="doc-tip">
          文档随产品维护，工单按当前工艺路线下发。工人按工序翻看对应版本的作业指导书，质检按 SIP 判定。
        </div>

        <div v-if="!docLoading && !hasDocs" class="muted doc-empty">
          该产品还没有维护 SOP / SIP，请到「物料主数据 → 文档」里补充。
        </div>

        <div v-if="(docData.productDocuments || []).length" class="drawer-section">
          <h4>产品级通用文档</h4>
          <div v-for="doc in docData.productDocuments" :key="doc.id" class="doc-card">
            <div class="doc-card-head">
              <span class="status-tag primary"><i></i>{{ doc.docType }}</span>
              <b>{{ doc.title }}</b>
              <span class="muted">{{ doc.version }}</span>
            </div>
            <pre class="doc-body">{{ doc.content || '（无正文，见附件）' }}</pre>
            <a v-if="doc.fileUrl" :href="doc.fileUrl" target="_blank" class="doc-link">查看附件</a>
          </div>
        </div>

        <div
          v-for="op in docData.operations || []"
          :key="op.seq"
          class="drawer-section"
        >
          <h4>
            {{ op.seq }} · {{ op.operationName }}
            <span class="status-tag" :class="modeOf(op.inspectionMode).cls" style="margin-left: 8px">
              <i></i>{{ modeOf(op.inspectionMode).label }}
            </span>
          </h4>
          <div v-if="!(op.documents || []).length" class="muted doc-empty">该工序暂无指导书</div>
          <div v-for="doc in op.documents || []" :key="doc.id" class="doc-card">
            <div class="doc-card-head">
              <span class="status-tag" :class="doc.docType === 'SIP' ? 'success' : 'primary'">
                <i></i>{{ doc.docType }}
              </span>
              <b>{{ doc.title }}</b>
              <span class="muted">{{ doc.version }}</span>
            </div>
            <pre class="doc-body">{{ doc.content || '（无正文，见附件）' }}</pre>
            <a v-if="doc.fileUrl" :href="doc.fileUrl" target="_blank" class="doc-link">查看附件</a>
          </div>
        </div>
      </div>

      <template #footer>
        <el-button @click="docDrawer = false">关闭</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="addOpDialog" title="添加工序" width="480px" append-to-body>
      <el-form label-width="92px">
        <el-form-item label="工序" required>
          <ReferenceSelect v-model="addOpForm.processId" url="/processes/options"
                           placeholder="从工序档案里选择"
                           @select="onAddOpProcessChange" />
        </el-form-item>
        <el-form-item label="计划数量">
          <el-input-number v-model="addOpForm.planQty" :min="0" :precision="4" style="width: 100%" />
        </el-form-item>
        <el-form-item label="工时/件">
          <el-input-number
            v-model="addOpForm.minutesPerPiece"
            :min="0"
            :precision="2"
            :step="0.5"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="工价">
          <el-input-number v-model="addOpForm.price" :min="0" :precision="2" :step="0.5" style="width: 100%" />
        </el-form-item>
        <el-form-item label="需要检验">
          <el-switch v-model="addOpForm.needInspection" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addOpDialog = false">取消</el-button>
        <el-button type="primary" :loading="addOpSaving" @click="saveAddOperation">添加</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="reportDialog" title="工序报工" width="540px">
      <div v-if="reportWork" class="report-head">
        <b class="mono">{{ reportWork.workNo }}</b>
        <span class="muted">{{ reportWork.productName }}</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="报工工序" required>
          <el-select v-model="reportForm.operationId" style="width: 100%" @change="onReportOperationChange">
            <el-option
              v-for="op in reportWork?.operations || []"
              :key="op.id"
              :label="`${op.seq}. ${op.name}（已报 ${fmtQty(op.doneQty)} / 计划 ${fmtQty(op.planQty)}${op.assignedWorker ? ' · ' + op.assignedWorker : ''}）`"
              :value="op.id"
            />
          </el-select>
        </el-form-item>
        <div class="report-tip">
          已按本工序剩余量预填报工数量、合格数自动算；只有出现不良时才需要改不良数。
          <el-button link type="primary" @click="fillReportRemain">按剩余填满</el-button>
        </div>
        <div class="form-grid">
          <el-form-item label="本次报工数量" required>
            <el-input-number v-model="reportForm.qty" :min="0.01" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="其中不良数量">
            <el-input-number
              v-model="reportForm.defectQty"
              :min="0"
              :max="Number(reportForm.qty || 0)"
              :precision="2"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="合格数量（自动计算）">
            <el-input-number :model-value="reportQualified" :min="0" :precision="2" disabled style="width: 100%" />
          </el-form-item>
          <el-form-item :label="reportAssignments.length ? '报工人（按分配名单）' : '操作工'">
            <el-select
              v-if="reportAssignments.length"
              v-model="reportForm.worker"
              style="width: 100%"
              placeholder="从分配名单里选"
            >
              <el-option
                v-for="item in reportAssignments"
                :key="item.userId"
                :label="`${item.workerName} · 分 ${fmtQty(item.quotaQty)} / 已报 ${fmtQty(item.reportedQty)} / 剩 ${fmtQty(item.remainingQty)}`"
                :value="item.workerName"
              />
            </el-select>
            <el-input
              v-else
              v-model="reportForm.worker"
              :disabled="reportLockedWorker"
              :placeholder="reportLockedWorker ? '该工序限定报工人' : '默认当前账号'"
            />
            <div v-if="reportAssignments.length" class="mode-hint">
              这道工序按人分配了数量：报谁的名字就记在谁头上，超过他自己的剩余量会被拦下。
            </div>
            <div v-else-if="reportLockedWorker" class="mode-hint">
              这道工序设了「只有名单内的人可报」，操作工固定为 {{ reportForm.worker }}，换人请先改派工。
            </div>
          </el-form-item>
          <el-form-item v-if="reportNeedInspect" label="检验结论" required class="full">
            <el-radio-group v-model="reportForm.inspectionResult">
              <el-radio value="PASS">合格</el-radio>
              <el-radio value="FAIL">不合格</el-radio>
            </el-radio-group>
            <div class="mode-hint">
              该工序设置了「工序内检验点」，报工必须给出结论；判不合格时不良数量必须大于 0，
              系统会同时生成过程检验记录与待处置不良。
            </div>
          </el-form-item>
          <el-form-item v-if="reportNeedInspect" label="检验说明" class="full">
            <el-input v-model="reportForm.inspectionRemark" placeholder="不良现象、处理建议（可选）" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="reportForm.remark" />
          </el-form-item>
        </div>
        <div class="muted" style="font-size: 12px">合格数量按「报工数量 - 不良数量」自动生成并随单提交。</div>
      </el-form>
      <template #footer>
        <el-button @click="reportDialog = false">取消</el-button>
        <el-button type="primary" :loading="reportSaving" @click="submitReport">提交报工</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="assignDialog" title="工序派工" width="680px">
      <div v-if="assignWork" class="report-head">
        <b class="mono">{{ assignWork.workNo }}</b>
        <span class="muted">{{ assignWork.productName }}</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="派工工序" required>
          <el-select v-model="assignForm.operationId" style="width: 100%" @change="onAssignOpChange">
            <el-option
              v-for="op in assignWork?.operations || []"
              :key="op.id"
              :label="`${op.seq}. ${op.name}（${op.assignedWorker || '未派工'}）`"
              :value="op.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="分配名单" required>
          <el-table :data="assignForm.rows" size="small" style="width: 100%">
            <el-table-column label="人员" min-width="200">
              <template #default="{ row }">
                <ReferenceSelect
                  :key="assignSkillScoped ? `skill-${assignProcessId}` : 'all-users'"
                  v-model="row.userId"
                  :url="assignSkillScoped ? `/processes/${assignProcessId}/skill-users` : '/system/users/options'"
                  code-field="username"
                  label-field="realName"
                  :initial-label="row.workerName"
                  clearable
                  placeholder="输入姓名或账号搜索"
                  @select="(user) => (row.workerName = user ? (user.realName || user.username) : '')"
                />
              </template>
            </el-table-column>
            <el-table-column label="分配数量" width="140">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.quotaQty"
                  :min="0"
                  :precision="2"
                  :placeholder="String(assignPlanQty)"
                  style="width: 100%"
                />
              </template>
            </el-table-column>
            <el-table-column label="" width="52" align="center">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeAssignRow($index)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="assign-foot">
            <el-button link type="primary" @click="addAssignRow">+ 添加人员</el-button>
            <span class="muted">
              工序计划 {{ fmtQty(assignPlanQty) }}，已分配 {{ fmtQty(assignQuotaTotal) }}
              <template v-if="assignQuotaTotal > assignPlanQty">
                ，超出计划 <b class="danger-text">{{ fmtQty(assignQuotaTotal - assignPlanQty) }}</b>
              </template>
              <template v-else-if="assignQuotaTotal < assignPlanQty">
                ，还剩 {{ fmtQty(assignPlanQty - assignQuotaTotal) }} 没分到人
              </template>
            </span>
          </div>
          <div class="mode-hint">
            多人一起做一道工序时，把每人分多少填上：报工时按人卡配额，超了就报不进去；
            不填数量按工序计划量算。{{ assignSkillHint }}
          </div>
        </el-form-item>
        <el-form-item label="报工权限">
          <el-radio-group v-model="assignForm.reportPolicy">
            <el-radio value="ANY">所有人可报</el-radio>
            <el-radio value="ASSIGNED">只有他能报</el-radio>
          </el-radio-group>
          <div class="mode-hint">
            选「只有他能报」后，其他人提交报工会被拦下；管理员仍可代办。默认所有人可报，车间代填工时不至于卡住。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignDialog = false">取消</el-button>
        <el-button type="primary" :loading="assignSaving" @click="submitAssign">保存派工</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="issueDialog" title="按物料领料" width="800px">
      <div class="report-head">
        工单 {{ issueWork?.workNo }} · {{ issueWork?.productName }}
        <span class="muted">可分批领料，数量不能超过剩余需求；主料库存不足时自动用替代料补足。提交后生成一张领料单</span>
      </div>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="领料仓库" required>
            <ReferenceSelect v-model="issueForm.warehouseId" url="/options/warehouses"
                             :initial-label="issueForm.warehouseLabel"
                             placeholder="输入仓库编码或名称搜索" />
          </el-form-item>
          <el-form-item label="领料人" required>
            <el-input v-model="issueForm.issuer" maxlength="40" placeholder="实际到仓库领料的人" />
          </el-form-item>
          <el-form-item label="班组">
            <el-input v-model="issueForm.team" maxlength="40" placeholder="选填，例如装配一组" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="issueForm.remark" maxlength="200" placeholder="选填" />
          </el-form-item>
        </div>
      </el-form>
      <el-table :data="issueRows" size="small" max-height="300">
        <el-table-column prop="materialCode" label="物料编码" width="110" />
        <el-table-column label="物料" min-width="160">
          <template #default="{ row }">
            <div>{{ row.materialName }}</div>
            <div v-if="row.substituteMaterialName" class="substitute-tip">
              替代料：{{ row.substituteMaterialName }}<template v-if="row.substituteStock !== null && row.substituteStock !== undefined">（库存 {{ fmtQty(row.substituteStock) }}）</template>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="需求" width="90" align="right">
          <template #default="{ row }">{{ fmtQty(row.needQty) }}</template>
        </el-table-column>
        <el-table-column label="已领" width="90" align="right">
          <template #default="{ row }">{{ fmtQty(row.issuedQty) }}</template>
        </el-table-column>
        <el-table-column label="可领" width="90" align="right">
          <template #default="{ row }">{{ fmtQty(row.remainingQty) }}</template>
        </el-table-column>
        <el-table-column label="本次领料" width="150">
          <template #default="{ row }">
            <el-input-number
              v-model="row.qty"
              :min="0"
              :max="Number(row.remainingQty)"
              :disabled="!Number(row.remainingQty)"
              size="small"
              controls-position="right"
              style="width: 115px"
            />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="issueDialog = false">取消</el-button>
        <el-button @click="fillAllIssue">全部领足</el-button>
        <el-button type="primary" :loading="issueSaving" @click="submitIssue">提交领料</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="returnDialog" title="生产退料" width="760px">
      <div v-if="returnWork" class="report-head">
        <b class="mono">{{ returnWork.workNo }}</b>
        <span class="muted">{{ returnWork.productName }}</span>
      </div>
      <el-table :data="returnRows" size="small" max-height="320" empty-text="没有可退物料">
        <el-table-column prop="materialCode" label="物料编码" width="90" />
        <el-table-column prop="materialName" label="物料名称" min-width="130" />
        <el-table-column label="需求" width="75" align="right">
          <template #default="{ row }">{{ fmtQty(row.planQty) }}</template>
        </el-table-column>
        <el-table-column label="已领" width="75" align="right">
          <template #default="{ row }">{{ fmtQty(row.issuedQty) }}</template>
        </el-table-column>
        <el-table-column label="已退" width="70" align="right">
          <template #default="{ row }">{{ fmtQty(row.returnedQty) }}</template>
        </el-table-column>
        <el-table-column label="可退" width="75" align="right">
          <template #default="{ row }"><span class="success-text">{{ fmtQty(row.returnableQty) }}</span></template>
        </el-table-column>
        <el-table-column label="超领" width="75" align="right">
          <template #default="{ row }">
            <span v-if="Number(row.suggestReturnQty) > 0" class="danger-text">
              {{ fmtQty(row.suggestReturnQty) }}
            </span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="本次退料" width="132">
          <template #default="{ row }">
            <el-input-number
              v-model="row.qty"
              :min="0"
              :max="Number(row.returnableQty)"
              :precision="2"
              size="small"
              style="width: 100%"
            />
          </template>
        </el-table-column>
      </el-table>
      <div class="form-grid" style="margin-top: 12px">
        <el-form-item label="退回仓库">
          <ReferenceSelect v-model="returnForm.warehouseId" url="/options/warehouses"
                           :initial-label="returnForm.warehouseLabel"
                           placeholder="默认退回原领料仓" />
        </el-form-item>
        <el-form-item label="退料人">
          <el-input v-model="returnForm.returner" maxlength="40" placeholder="默认当前登录用户" />
        </el-form-item>
        <el-form-item label="退料原因" class="full">
          <el-input v-model="returnForm.reason" maxlength="200" placeholder="如：领多、图纸变更、订单减量" />
        </el-form-item>
      </div>
      <div class="muted" style="font-size: 12px; line-height: 1.8">
        提交后生成一张退料单（MR 开头），物料按原领料批次退回仓库并写入 RETURN_MAT 库存流水，工单已领量同步减少。
      </div>
      <template #footer>
        <el-button @click="returnDialog = false">取消</el-button>
        <el-button @click="fillSuggestReturn">按超领量填充</el-button>
        <el-button type="primary" :loading="returnSaving" @click="submitReturn">提交退料</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="qrDialog" title="工单二维码" width="740px">
      <div v-loading="qrLoading" class="qr-body">
        <template v-if="qrWork">
          <div class="qr-sub muted">
            {{ qrWork.productName }} · {{ qrWork.productCode }} · 计划 {{ fmtQty(qrWork.planQty) }} {{ qrWork.unit }}
          </div>
          <div class="qr-board">
            <div class="qr-card qr-main">
              <img v-if="qrWorkUrl" :src="qrWorkUrl" alt="工单码" />
              <div class="qr-title">工单码</div>
              <div class="qr-code mono">MES|WO|{{ qrWork.workNo }}</div>
            </div>
            <div v-for="op in qrOps" :key="op.seq" class="qr-card">
              <img :src="op.url" :alt="`工序 ${op.seq}`" />
              <div class="qr-title">{{ op.seq }}. {{ op.name }}</div>
              <div class="qr-code mono">MES|OP|{{ qrWork.workNo }}|{{ op.seq }}</div>
            </div>
          </div>
          <div class="muted" style="font-size: 12px; margin-top: 12px">
            移动端扫码识别 MES|WO|工单号 进入工单，识别 MES|OP|工单号|工序序号 直接打开对应工序报工。
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="qrDialog = false">关闭</el-button>
        <el-button type="primary" :disabled="!qrWork" @click="printQr">打印二维码</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="bomDiffDialog" title="工单 BOM 与标准 BOM 对比" width="860px">
      <div class="report-head">
        工单 {{ detail?.workNo }} · {{ detail?.productName }}
        <span class="muted">差异 = 当前需求 - 标准需求</span>
      </div>
      <el-table
        v-loading="bomDiffLoading"
        :data="bomDiffRows"
        size="small"
        empty-text="暂无 BOM 差异数据"
        :row-class-name="bomDiffRowClass"
        max-height="calc(70vh - 300px)"
      >
        <el-table-column label="物料" min-width="190">
          <template #default="{ row }">
            <div>{{ row.materialName }}</div>
            <div class="muted" style="font-size: 11px">{{ row.materialCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="单位" width="65">
          <template #default="{ row }"><span class="muted">{{ row.unit || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="标准需求" width="105" align="right">
          <template #default="{ row }">{{ fmtQty(row.standardQty) }}</template>
        </el-table-column>
        <el-table-column label="当前需求" width="105" align="right">
          <template #default="{ row }">{{ fmtQty(row.currentQty) }}</template>
        </el-table-column>
        <el-table-column label="差异" width="100" align="right">
          <template #default="{ row }">
            <span class="num" :class="diffClass(row.diffQty)">{{ diffText(row.diffQty) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="88">
          <template #default="{ row }">
            <span class="status-tag" :class="bomDiffType(row).cls">
              <i></i>{{ bomDiffType(row).label }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="已领" width="95" align="right">
          <template #default="{ row }">{{ fmtQty(row.issuedQty) }}</template>
        </el-table-column>
      </el-table>
      <div class="section-note">
        标准需求 = 产品 BOM 单耗 × 工单数量 × (1 + 损耗率)；当前需求含本工单的非标调整与替代料行，差异为 0 的行表示与标准一致。
      </div>
      <template #footer>
        <el-button @click="bomDiffDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="bomDialog" title="非标 BOM 调整" width="1000px">
      <div v-if="detail" class="bom-adjust">
        <div class="bom-form">
          <div class="report-head">
            <b class="mono">{{ detail.workNo }}</b>
            <span class="muted">{{ detail.productName }}</span>
          </div>
          <el-form label-position="top">
            <el-form-item label="物料" required>
              <ReferenceSelect v-model="bomAdjust.materialId" url="/options/products"
                               :initial-label="bomAdjust.materialLabel"
                               :disabled="bomAdjust.editing"
                               placeholder="输入物料编码或名称搜索"
                               @select="onBomMaterialSelect" />
            </el-form-item>
            <el-form-item label="用量" required>
              <el-input-number v-model="bomAdjust.planQty" :min="0.0001" :precision="4" style="width: 100%" />
            </el-form-item>
            <el-form-item label="投料工序">
              <el-select v-model="bomAdjust.operationSeq" clearable placeholder="选择工序" style="width: 100%">
                <el-option
                  v-for="op in detail.operations || []"
                  :key="op.seq"
                  :label="`${op.seq}. ${op.name}`"
                  :value="op.seq"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="bomAdjust.remark" placeholder="可选" />
            </el-form-item>
            <el-form-item label="调整原因" required>
              <el-input v-model="bomAdjust.reason" type="textarea" :rows="3" placeholder="如：客户临时改配，增加一套支架" />
            </el-form-item>
          </el-form>
          <div class="muted" style="font-size: 12px; line-height: 1.7">
            同一物料再次提交会覆盖用量；用量不能小于已领数量，已领用物料不能删除。
          </div>
        </div>

        <div class="bom-lines">
          <div class="lines-head">
            <b>当前工单 BOM</b>
            <div class="lines-actions">
              <span class="toolbar-tip">共 {{ (detail.bom || []).length }} 行</span>
              <el-button size="small" @click="openBomAdjust()">新增物料</el-button>
            </div>
          </div>
          <el-table :data="detail.bom || []" size="small" empty-text="暂无 BOM" max-height="calc(70vh - 300px)">
            <el-table-column label="物料" min-width="150">
              <template #default="{ row }">
                <div>{{ row.materialName }}</div>
                <div class="muted" style="font-size: 11px">{{ row.materialCode }}</div>
                <div v-if="isSubstituteLine(row)" class="substitute-tip">替代料行 · 随主料领用</div>
                <div v-else-if="row.substituteMaterialName" class="substitute-tip">
                  替代料：{{ row.substituteMaterialName }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="需求" width="80" align="right">
              <template #default="{ row }">
                <span v-if="isSubstituteLine(row)" class="muted" style="font-size: 11px">随主料</span>
                <span v-else>{{ fmtQty(row.needQty) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="已领" width="70" align="right">
              <template #default="{ row }">{{ fmtQty(row.issuedQty) }}</template>
            </el-table-column>
            <el-table-column label="来源" width="76">
              <template #default="{ row }">
                <span class="status-tag" :class="bomSource(row).cls">
                  <i></i>{{ bomSource(row).label }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="105">
              <template #default="{ row }">
                <span v-if="isSubstituteLine(row)" class="muted" style="font-size: 11px">随主料</span>
                <template v-else>
                  <el-button link type="primary" @click="openBomAdjust(row)">调整</el-button>
                  <el-button link type="danger" @click="removeBomLine(row)">删除</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
      <template #footer>
        <el-button type="warning" plain :disabled="!canAdjustBom" @click="resetBom">恢复标准 BOM</el-button>
        <el-button @click="bomDialog = false">取消</el-button>
        <el-button type="primary" :loading="bomSaving" @click="submitBomAdjust">保存调整</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="outsourceVisible"
      :title="outsourceMode === 'plan' ? '转外协' : '外协发出'"
      width="720px"
      append-to-body
    >
      <div class="section-note" style="margin-bottom: 12px">
        第 {{ outsourceForm.seq }} 道工序「{{ outsourceForm.operationName }}」。
        {{ outsourceMode === 'plan'
          ? '先标成外协，料还没动；下一步「外协发出」才扣库存发料。'
          : '发出会从原料仓按批次扣减这工序的料，并把工单 BOM 的已领量回写，后续整单领料不再要这些料。' }}
      </div>
      <el-form label-width="96px">
          <el-form-item label="外协厂" required>
            <ReferenceSelect v-model="outsourceForm.supplierId" url="/suppliers/options"
                             :initial-label="outsourceForm.supplierLabel"
                             placeholder="输入供应商编码或名称搜索" />
          </el-form-item>
        <el-form-item label="外协单价">
          <el-input-number v-model="outsourceForm.price" :min="0" :precision="2" :step="1" style="width: 160px" />
          <span class="muted" style="margin-left: 8px">元/件（不含料），外协费 = 收回合格数 × 单价</span>
        </el-form-item>
        <el-form-item label="约定回厂">
          <el-date-picker v-model="outsourceForm.expectedAt" type="date" value-format="YYYY-MM-DD" placeholder="超期在看板报警" />
        </el-form-item>
        <template v-if="outsourceMode === 'send'">
          <el-form-item label="外协数量" required>
            <el-input-number v-model="outsourceForm.qty" :min="0" :precision="0" style="width: 160px" />
            <span class="muted" style="margin-left: 8px">本次发出去做的成品件数</span>
          </el-form-item>
          <el-form-item label="发料人">
            <el-input v-model="outsourceForm.sender" placeholder="默认当前登录人" style="width: 200px" />
          </el-form-item>
          <el-form-item label="发料清单">
            <el-table :data="outsourceMaterials" size="small" style="width: 100%" empty-text="这道工序没有 BOM 用料">
              <el-table-column label="物料" min-width="180">
                <template #default="{ row }">
                  <div>{{ row.materialName }}</div>
                  <div class="muted" style="font-size: 11px">{{ row.materialCode }} · {{ row.spec || '无规格' }}</div>
                </template>
              </el-table-column>
              <el-table-column label="发出" width="105" align="right">
                <template #default="{ row }">
                  <span :class="row.fromStock ? '' : 'muted'">{{ fmtQty(row.sendQty) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="本厂库存" width="100" align="right">
                <template #default="{ row }">{{ fmtQty(row.stockQty) }}</template>
              </el-table-column>
              <el-table-column label="来源" width="130">
                <template #default="{ row }">
                  <span v-if="row.fromStock" class="status-tag" :class="row.stockEnough ? 'success' : 'danger'">
                    <i></i>{{ row.stockEnough ? '仓库发出' : '库存不足' }}
                  </span>
                  <span v-else class="status-tag draft"><i></i>已领料，随件转出</span>
                </template>
              </el-table-column>
            </el-table>
          </el-form-item>
        </template>
        <el-form-item label="备注">
          <el-input v-model="outsourceForm.remark" type="textarea" :rows="2" placeholder="例如：本批喷粉外协，客户指定供应商" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="outsourceVisible = false">取消</el-button>
        <el-button type="primary" :loading="outsourceSaving" @click="submitOutsource">
          {{ outsourceMode === 'plan' ? '确认转外协' : '确认发出' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 建单预览：把会生成的工序与用料先摆出来，避免建完才发现主数据没维护 */
.create-preview {
  border-top: 1px dashed var(--line);
  padding-top: 12px;
  margin-top: 4px;
  min-height: 64px;
}

.preview-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.preview-title {
  font-size: 13px;
  font-weight: 600;
}

.preview-block {
  margin-bottom: 12px;
}

.preview-label {
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 6px;
}

.preview-empty {
  font-size: 12px;
  line-height: 1.7;
  color: #b45309;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 6px;
  padding: 7px 10px;
}

.routing-flow {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.routing-step {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #f6f8fb;
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 3px 10px 3px 4px;
  font-size: 12px;
}

.step-seq {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #2456d8;
  color: #fff;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.step-name {
  font-weight: 600;
}

.step-meta {
  color: var(--muted);
  font-size: 11px;
}

.step-tag {
  background: #eef3ff;
  color: #2456d8;
  border-radius: 4px;
  padding: 0 4px;
  font-size: 11px;
}

.step-arrow {
  color: var(--muted);
  margin-left: 4px;
}

/* 派工名单：添加人员按钮与「已分配 / 计划」对账 */
.assign-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  margin-top: 6px;
  font-size: 12px;
}

.user-option {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
  padding: 2px 0;
}

.user-option-name {
  font-size: 13px;
  color: var(--ink);
}

.user-option-meta {
  font-size: 11px;
  color: var(--muted);
}

.doc-tip {
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.7;
  background: #f7f9fc;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 4px;
}

.doc-empty {
  font-size: 12px;
  padding: 8px 0;
}

.doc-card {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 10px;
  background: #fff;
}

.doc-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.doc-card-head b {
  flex: 1;
}

.doc-body {
  margin: 10px 0 0;
  padding: 10px 12px;
  background: #f8fafc;
  border-radius: 8px;
  font-family: inherit;
  font-size: 12px;
  line-height: 1.8;
  color: var(--ink-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.doc-link {
  display: inline-block;
  margin-top: 8px;
  font-size: 12px;
  color: var(--primary);
}

.mode-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
}

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

.detail-tags {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 0 2px;
}

/* 详情分页签：把概览/工序/物料/单件/记录分开，首屏只留「现在该干什么」 */
.detail-tabs {
  margin-top: 6px;
}

.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}

.detail-tabs :deep(.el-tabs__item) {
  font-size: 13px;
  font-weight: 600;
}

.detail-tabs :deep(.el-tabs__nav-wrap::after) {
  background-color: var(--line);
}

.report-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 10px 12px;
  background: #f8fafc;
  border-radius: 6px;
}

.rework-tag {
  margin-left: 6px;
  font-size: 11px;
  padding: 1px 6px;
}

.late-tag {
  margin-top: 2px;
  padding: 0 6px;
  font-size: 10px;
  line-height: 17px;
}

.detail-late {
  margin-left: 6px;
  padding: 0 6px;
  font-size: 10px;
  line-height: 17px;
}

.more-actions {
  margin-left: 8px;
  vertical-align: middle;
}

.qr-body {
  min-height: 160px;
}

.qr-sub {
  font-size: 12px;
  margin-bottom: 12px;
}

.qr-board {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.qr-card {
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  padding: 12px;
  text-align: center;
  background: #fff;
}

.qr-card.qr-main {
  grid-column: span 2;
}

.qr-card img {
  width: 180px;
  height: 180px;
}

.qr-card.qr-main img {
  width: 230px;
  height: 230px;
}

.qr-title {
  margin-top: 6px;
  font-size: 13px;
  font-weight: 600;
}

.qr-code {
  margin-top: 2px;
  font-size: 11px;
  color: var(--muted);
  word-break: break-all;
}

:deep(.bom-diff-same) {
  color: var(--muted);
  background: #fbfcfd;
}

.substitute-tip {
  margin-top: 2px;
  font-size: 11px;
  color: var(--muted);
  line-height: 1.5;
}

.section-note {
  margin-top: 10px;
  padding: 8px 10px;
  background: #f8fafc;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}

.bom-adjust {
  display: grid;
  grid-template-columns: 330px minmax(0, 1fr);
  gap: 18px;
}

.bom-form {
  border-right: 1px solid var(--line);
  padding-right: 18px;
}

.bom-lines {
  min-width: 0;
}

.lines-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 13px;
}

.lines-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.unit-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.unit-chip {
  padding: 3px 10px;
  border-radius: 999px;
  background: #f3f5f9;
  color: #475467;
  font-size: 12px;
}

.unit-chip.warn {
  background: #fff7e6;
  color: #b45309;
}

.unit-chip.success {
  background: #ecfdf3;
  color: #067647;
}

.unit-chip.danger {
  background: #fef3f2;
  color: #b42318;
}

.unit-dist {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.unit-dist-item {
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfcfe;
}

.unit-dist-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 12px;
  color: #475467;
  margin-bottom: 4px;
}

.unit-dist-head b {
  font-size: 15px;
  color: var(--text);
}

.unit-dist-codes {
  font-size: 11px;
  color: var(--muted);
  line-height: 1.5;
  word-break: break-all;
}
</style>
