<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, fmtQty } from '../api'
import StatusTag from '../components/StatusTag.vue'
import MesAttachments from '../components/Attachments.vue'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import ReferenceSelect from '../components/ReferenceSelect.vue'
import { useTableView } from '../composables/useTableView'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const type = ref('')
const result = ref('')
const keyword = ref('')

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'quality.inspections',
  columns: [
    { key: 'inspectionNo', label: '检验单号', width: 165, fixed: 'left' },
    { key: 'type', label: '检验类型', width: 110 },
    { key: 'refNo', label: '来源单据', width: 160 },
    { key: 'productName', label: '产品', width: 150 },
    { key: 'inspectQty', label: '检验数', width: 85, align: 'right' },
    { key: 'defectQty', label: '不良数', width: 85, align: 'right' },
    { key: 'passRate', label: '合格率', width: 95, align: 'right' },
    { key: 'result', label: '结果', width: 95 },
    { key: 'itemCount', label: '检验项', width: 90, align: 'center' },
    { key: 'inspector', label: '检验员', width: 85 },
    { key: 'inspectedAt', label: '检验时间', width: 140 },
    { key: 'remark', label: '备注', width: 150, flex: true, ellipsis: true },
    { key: 'actions', label: '操作', width: 80, fixed: 'right' }
  ],
  getFilters: () => ({ keyword: keyword.value, type: type.value, result: result.value }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    type.value = filters.type || ''
    result.value = filters.result || ''
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
const summary = ref<any>({ rate: 100, inspectQty: 0, defectQty: 0, total: 0 })
const finishedWorks = ref<any[]>([])
const inboundOrders = ref<any[]>([])

const dialog = ref(false)
const saving = ref(false)
const form = reactive<any>({
  type: 'FINAL',
  workOrderId: null,
  inboundOrderId: null,
  productId: null,
  productLabel: '',
  refType: 'WORK_ORDER',
  refNo: '',
  inspectQty: 1,
  defectQty: 0,
  inspector: auth.user?.realName || '',
  remark: '',
  reworkFromSeq: null
})
// 完工检不合格默认整件返修；也可以指定从哪道工序起返修
const workOperations = ref<any[]>([])

// 检验项与抽样
const inspectItems = ref<any[]>([])
const samplePlan = ref<'FULL' | 'SAMPLE'>('FULL')
const aql = ref<number>(2.5)
const samplePreview = ref<any>(null)
const itemTop = ref<any[]>([])
const detailDialog = ref(false)
const detailLoading = ref(false)
const detailData = ref<any>(null)

const itemDefectTotal = computed(() =>
  inspectItems.value.reduce((sum, item) => sum + Number(item.defectQty || 0), 0)
)

const productLabel = computed(() => form.productLabel || '')

const typeLabel: Record<string, string> = {
  INCOMING: '来料检验',
  PROCESS: '过程检验',
  FINAL: '完工检验'
}

const load = async () => {
  loading.value = true
  try {
    const [data, summaryData, topData] = await Promise.all([
      api.get('/inspections', {
        type: type.value,
        result: result.value,
        keyword: keyword.value,
        page: page.value,
        size: size.value
      }),
      api.get('/inspections/summary'),
      api.get('/inspections/item-defects', { days: 30 })
    ])
    rows.value = data.records
    total.value = data.total
    summary.value = summaryData
    itemTop.value = topData || []
  } finally {
    loading.value = false
  }
}

const openCreate = async () => {
  const [workData, inboundData] = await Promise.all([
    api.get('/work-orders', { status: 'FINISHED', page: 1, size: 100 }),
    api.get('/inbound-orders', { status: 'PENDING_QC', page: 1, size: 100 })
  ])
  finishedWorks.value = workData.records
  inboundOrders.value = inboundData.records
  Object.assign(form, {
    type: 'FINAL',
    workOrderId: null,
    inboundOrderId: null,
    productId: null,
    productLabel: '',
    refType: 'WORK_ORDER',
    refNo: '',
    inspectQty: 1,
    defectQty: 0,
    inspector: auth.user?.realName || '',
    remark: '',
    reworkFromSeq: null
  })
  workOperations.value = []
  dialog.value = true
}

const onTypeChange = () => {
  if (form.type === 'FINAL') {
    form.refType = 'WORK_ORDER'
  } else if (form.type === 'INCOMING') {
    form.refType = 'INBOUND'
    form.workOrderId = null
    form.inboundOrderId = null
    form.productId = null
    form.productLabel = ''
    form.refNo = ''
    form.inspectQty = 1
  } else {
    form.refType = 'WORK_ORDER'
    form.inboundOrderId = null
  }
}

const onInboundChange = async (id: number) => {
  const data = await api.get(`/inbound-orders/${id}`)
  form.refNo = data.inboundNo
  form.inspectQty = Number(data.totalQty || 1)
  if (data.items?.length) {
    const first = data.items[0]
    form.productId = first.productId
    form.productLabel = first.productCode
      ? `${first.productCode} · ${first.productName}`
      : (first.productName || '')
  }
}

const onWorkChange = async (id: number) => {
  const work = finishedWorks.value.find((item) => item.id === id)
  if (work) {
    // 列表接口老版本没带 productId 时保留原值，下面用详情再补一次
    form.productId = work.productId || form.productId
    form.productLabel = work.productCode
      ? `${work.productCode} · ${work.productName}`
      : (work.productName || form.productLabel)
    form.refNo = work.workNo
    form.inspectQty = Number(work.planQty)
    form.refType = 'WORK_ORDER'
  }
  workOperations.value = []
  form.reworkFromSeq = null
  if (!id) return
  try {
    const detail = await api.get(`/work-orders/${id}`)
    if (!form.productId && detail.productId) {
      form.productId = detail.productId
      form.productLabel = detail.productCode
        ? `${detail.productCode} · ${detail.productName}`
        : (detail.productName || '')
    }
    workOperations.value = detail.operations || []
    // 默认整件返修（从第一道工序起），需要时再改成从中间某道工序起
    form.reworkFromSeq = workOperations.value.length ? workOperations.value[0].seq : null
  } catch {
    // 工序加载失败时不影响提交，后端按整件返修处理
  }
}

const loadTemplates = async () => {
  if (!form.productId) {
    inspectItems.value = []
    return
  }
  try {
    const templates = await api.get('/inspections/items', { productId: form.productId })
    inspectItems.value = (templates || []).map((item: any) => ({
      itemId: item.id,
      itemName: item.itemName,
      standard: item.standard,
      method: item.method,
      valueType: item.valueType,
      unit: item.unit,
      required: item.required,
      measured: '',
      defectQty: 0,
      result: 'PASS',
      remark: ''
    }))
  } catch {
    inspectItems.value = []
  }
}

const refreshSamplePlan = async () => {
  const lotQty = Number(form.inspectQty || 0)
  if (!lotQty) {
    samplePreview.value = null
    return
  }
  try {
    samplePreview.value = await api.get('/inspections/sample-plan', {
      lotQty,
      plan: samplePlan.value,
      aql: samplePlan.value === 'SAMPLE' ? aql.value : undefined
    })
  } catch {
    samplePreview.value = null
  }
}

watch(
  () => [form.productId, form.inspectQty, samplePlan.value, aql.value],
  () => {
    refreshSamplePlan()
  }
)

watch(
  () => form.productId,
  () => {
    loadTemplates()
  }
)

const openDetail = async (row: any) => {
  detailDialog.value = true
  detailLoading.value = true
  detailData.value = null
  try {
    detailData.value = await api.get(`/inspections/${row.id}`)
  } finally {
    detailLoading.value = false
  }
}

const submit = async () => {
  if (form.type === 'FINAL' && !form.workOrderId) {
    ElMessage.warning('完工检验必须选择生产工单')
    return
  }
  if (form.type === 'INCOMING' && !form.inboundOrderId) {
    ElMessage.warning('来料检验必须选择待检入库单')
    return
  }
  if (!form.inspectQty || form.inspectQty <= 0) {
    ElMessage.warning('检验数量必须大于 0')
    return
  }
  if (!form.productId) {
    ElMessage.warning('没有带出产品，请重新选择生产工单或待检入库单')
    return
  }
  saving.value = true
  try {
    const itemPayload = inspectItems.value
      .filter((item) => item.itemId)
      .map((item) => ({
        itemId: item.itemId,
        itemName: item.itemName,
        standard: item.standard,
        sampleQty: samplePreview.value?.sampleQty ?? form.inspectQty,
        defectQty: Number(item.defectQty || 0),
        measured: item.measured || null,
        result: Number(item.defectQty || 0) > 0 ? 'FAIL' : item.result,
        remark: item.remark || null
      }))
    const defectQty = itemPayload.length
      ? itemPayload.reduce((sum, item) => sum + Number(item.defectQty || 0), 0)
      : Number(form.defectQty || 0)
    const data = await api.post('/inspections', {
      type: form.type,
      refType: form.refType,
      refNo: form.refNo,
      workOrderId: form.workOrderId,
      productId: form.productId,
      inspectQty: Number(form.inspectQty),
      defectQty,
      inspector: form.inspector,
      remark: form.remark,
      reworkFromSeq: form.type === 'FINAL' ? form.reworkFromSeq : null,
      items: itemPayload,
      samplePlan: samplePlan.value,
      aql: samplePlan.value === 'SAMPLE' ? aql.value : null
    })
    if (data?.reworkWorkNo) {
      ElMessage.success(`检验不合格，已生成返工工单 ${data.reworkWorkNo}`)
    } else if (data?.workOrderStatus === 'CLOSED') {
      ElMessage.success('检验合格，工单已关闭并入库')
    } else {
      ElMessage.success('检验单已提交')
    }
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await table.load()
  await load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">质量检验</h2>
        <p class="page-sub">完工检验合格自动入库并关单，不合格则转返工并自动生成返工工单</p>
      </div>
      <el-button v-perm="'quality:create'" type="primary" @click="openCreate">新建检验单</el-button>
    </div>

    <div class="quality-strip">
      <div class="quality-item">
        <span>近 30 天检验单</span>
        <b>{{ summary.total || 0 }}</b>
      </div>
      <div class="quality-item">
        <span>检验数量</span>
        <b>{{ fmtQty(summary.inspectQty) }}</b>
      </div>
      <div class="quality-item">
        <span>不良数量</span>
        <b class="danger-text">{{ fmtQty(summary.defectQty) }}</b>
      </div>
      <div class="quality-item">
        <span>一次合格率（报工口径）</span>
        <b>{{ summary.firstPassRate ?? 100 }}<small style="font-size: 12px">%</small></b>
      </div>
      <div class="quality-item">
        <span>待处置不良</span>
        <b class="danger-text">{{ summary.pending || 0 }}</b>
      </div>
      <div class="quality-rate">
        <span>检验合格率</span>
        <b>{{ summary.rate }}<small>%</small></b>
        <div class="rate-bar"><i :style="{ width: Math.min(100, Number(summary.rate)) + '%' }"></i></div>
      </div>
    </div>

    <div v-if="itemTop.length" class="panel item-top-panel">
      <div class="card-head">
        <div class="card-title">不良项 Top（近 30 天）</div>
        <span class="toolbar-tip">按检验项统计不合格次数，用来反推工序与供应商问题</span>
      </div>
      <div class="item-top-list">
        <div v-for="item in itemTop" :key="item.itemName" class="item-top-row">
          <span class="item-name">{{ item.itemName }}</span>
          <span class="item-fail">不合格 {{ item.failCount }} 次</span>
          <span class="muted">不良 {{ fmtQty(item.defectQty) }} · 不合格率 {{ item.failRate }}%</span>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="检验单号 / 产品 / 来源单" clearable style="width: 240px" @keyup.enter="load" />
        <el-select v-model="type" placeholder="全部类型" clearable style="width: 140px" @change="load">
          <el-option label="来料检验" value="INCOMING" />
          <el-option label="过程检验" value="PROCESS" />
          <el-option label="完工检验" value="FINAL" />
        </el-select>
        <el-select v-model="result" placeholder="全部结果" clearable style="width: 130px" @change="load">
          <el-option label="合格" value="PASS" />
          <el-option label="不合格" value="FAIL" />
        </el-select>
        <el-button type="primary" plain @click="load">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/inspections" export-name="质量检验" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 张检验单</span>
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
          <template #cell-inspectionNo="{ row }"><span class="mono">{{ row.inspectionNo }}</span></template>
          <template #cell-type="{ row }">
            <span class="status-tag" :class="row.type === 'FINAL' ? 'primary' : row.type === 'PROCESS' ? 'warning' : 'info'">
              <i></i>{{ typeLabel[row.type] || row.type }}
            </span>
          </template>
          <template #cell-refNo="{ row }"><span class="mono">{{ row.refNo || '-' }}</span></template>
          <template #cell-inspectQty="{ row }">{{ fmtQty(row.inspectQty) }}</template>
          <template #cell-defectQty="{ row }">
            <span :class="Number(row.defectQty) > 0 ? 'danger-text' : ''">{{ fmtQty(row.defectQty) }}</span>
          </template>
          <template #cell-passRate="{ row }"><span class="num">{{ row.passRate }}%</span></template>
          <template #cell-result="{ row }"><StatusTag :status="row.result" /></template>
          <template #cell-itemCount="{ row }">
            <span class="muted">{{ row.itemCount ? `${row.itemCount} 项` : '-' }}</span>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" title="新建检验单" width="600px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="检验类型" required>
            <el-select v-model="form.type" style="width: 100%" @change="onTypeChange">
              <el-option label="完工检验" value="FINAL" />
              <el-option label="过程检验" value="PROCESS" />
              <el-option label="来料检验" value="INCOMING" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.type === 'FINAL'" label="生产工单" required>
            <el-select v-model="form.workOrderId" filterable style="width: 100%" @change="onWorkChange">
              <el-option
                v-for="item in finishedWorks"
                :key="item.id"
                :label="`${item.workNo} · ${item.productName} × ${fmtQty(item.planQty)}`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.type === 'FINAL'" label="受检产品">
            <el-input :model-value="productLabel" disabled placeholder="选择工单后自动带出" />
          </el-form-item>
          <el-form-item v-else-if="form.type === 'INCOMING'" label="待检入库单" required>
            <el-select v-model="form.inboundOrderId" filterable style="width: 100%" @change="onInboundChange">
              <el-option
                v-for="item in inboundOrders"
                :key="item.id"
                :label="`${item.inboundNo} · ${item.supplier || '无供应商'} · ${fmtQty(item.totalQty)}`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-else label="产品" required>
            <ReferenceSelect v-model="form.productId" url="/options/products"
                             :initial-label="form.productLabel"
                             placeholder="输入物料编码或名称搜索"
                             @select="(product) => (form.productLabel = product ? `${product.code} · ${product.name}` : '')" />
          </el-form-item>
          <el-form-item label="来源单号">
            <el-input v-model="form.refNo" :placeholder="form.type === 'INCOMING' ? '采购单号' : '工单号'" />
          </el-form-item>
          <el-form-item label="检验数量" required>
            <el-input-number v-model="form.inspectQty" :min="0.01" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="抽样方式">
            <el-select v-model="samplePlan" style="width: 100%">
              <el-option label="全检" value="FULL" />
              <el-option label="抽检（按批量分档）" value="SAMPLE" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="samplePlan === 'SAMPLE'" label="AQL（%）">
            <el-select v-model="aql" style="width: 100%">
              <el-option v-for="level in [0.65, 1.0, 1.5, 2.5, 4.0]" :key="level" :label="String(level)" :value="level" />
            </el-select>
          </el-form-item>
          <el-form-item :label="inspectItems.length ? '不良数量（按检验项汇总）' : '不良数量'">
            <div v-if="inspectItems.length" class="defect-total">{{ fmtQty(itemDefectTotal) }}</div>
            <el-input-number v-else v-model="form.defectQty" :min="0" :max="form.inspectQty" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="检验员">
            <el-input v-model="form.inspector" />
          </el-form-item>
          <el-form-item v-if="form.type === 'FINAL'" label="返修起点工序">
            <el-select v-model="form.reworkFromSeq" clearable placeholder="整件返修（从第一道起）" style="width: 100%">
              <el-option
                v-for="op in workOperations"
                :key="op.id"
                :label="`第 ${op.seq} 道 · ${op.name}`"
                :value="op.seq"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="不良现象、隔离措施等" />
          </el-form-item>
        </div>
        <div v-if="samplePreview" class="sample-line">
          抽样方案：样本量 {{ fmtQty(samplePreview.sampleQty) }}，接收数 Ac {{ samplePreview.acceptQty }}，拒收数 Re {{ samplePreview.rejectQty }}
          <span v-if="samplePlan === 'SAMPLE'" class="muted">（AQL {{ samplePreview.aql }}%，简化规则）</span>
        </div>
        <div v-if="inspectItems.length" class="item-block">
          <div class="card-head">
            <div class="card-title">检验项（{{ inspectItems.length }} 项）</div>
            <span class="toolbar-tip">必检项需逐项给结果，任一项不合格整单不合格</span>
          </div>
          <el-table :data="inspectItems" size="small" max-height="260">
            <el-table-column prop="itemName" label="检验项" min-width="110" show-overflow-tooltip />
            <el-table-column prop="standard" label="标准" min-width="140" show-overflow-tooltip />
            <el-table-column label="实测值" width="115">
              <template #default="{ row }"><el-input v-model="row.measured" size="small" /></template>
            </el-table-column>
            <el-table-column label="不良数" width="105">
              <template #default="{ row }">
                <el-input-number v-model="row.defectQty" :min="0" size="small" controls-position="right" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="判定" width="100">
              <template #default="{ row }">
                <el-select v-model="row.result" size="small">
                  <el-option label="合格" value="PASS" />
                  <el-option label="不合格" value="FAIL" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="110">
              <template #default="{ row }"><el-input v-model="row.remark" size="small" /></template>
            </el-table-column>
          </el-table>
        </div>
        <div v-else-if="form.productId" class="quality-note">
          该产品还没配置检验项，先按检验数量与不良数量判定；需要逐项记录时，到「工序管理 / 物料主数据」里维护检验项。
        </div>
        <div v-if="form.type === 'FINAL'" class="quality-note">
          完工检验不良数为 0 时判定合格，合格数量自动入成品仓；有不良则判定不合格，不产生入库，
          并按「返修起点工序」生成返修工单：整件返修从第一道起，也可指定从出问题的那道工序起做。
        </div>
        <div v-else-if="form.type === 'INCOMING'" class="quality-note">
          选择待检入库单后，合格将立即完成整单入库并写入库存流水；不合格则保持待检，需重新检验。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">提交检验</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable append-to-body v-model="detailDialog" title="检验单详情" width="720px">
      <div v-loading="detailLoading">
        <template v-if="detailData">
          <div class="detail-head">
            <span class="mono strong">{{ detailData.inspectionNo }}</span>
            <StatusTag :status="detailData.result" />
            <span class="muted">{{ detailData.productName }} · {{ typeLabel[detailData.type] || detailData.type }}</span>
            <span class="muted">
              检验 {{ fmtQty(detailData.inspectQty) }} · 不良 {{ fmtQty(detailData.defectQty) }} · 合格率 {{ detailData.passRate }}%
            </span>
            <span v-if="detailData.samplePlan === 'SAMPLE'" class="muted">
              抽检：样本 {{ fmtQty(detailData.sampleQty) }}，Ac {{ detailData.acceptQty }} / Re {{ detailData.rejectQty }}
            </span>
            <span v-else class="muted">全检</span>
          </div>
          <el-table :data="detailData.items || []" size="small">
            <el-table-column prop="itemName" label="检验项" min-width="120" show-overflow-tooltip />
            <el-table-column prop="standard" label="标准" min-width="140" show-overflow-tooltip />
            <el-table-column prop="measured" label="实测值" width="100" />
            <el-table-column label="不良数" width="90" align="right">
              <template #default="{ row }">
                <span :class="Number(row.defectQty) > 0 ? 'danger-text' : ''">{{ fmtQty(row.defectQty) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="判定" width="90">
              <template #default="{ row }"><StatusTag :status="row.result" /></template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
          </el-table>
          <div v-if="!(detailData.items || []).length" class="quality-note">
            这张检验单没有逐项记录，按检验数量与不良数量判定。
          </div>

          <el-divider content-position="left">检验附件</el-divider>
          <MesAttachments
            :key="detailData.id"
            biz-type="INSPECTION"
            :biz-id="detailData.id"
            :biz-no="detailData.inspectionNo"
            title="检验报告 / 现场照片"
            hint="首件检验可以传首件照片，来料检验可以传供应商报告，单个不超过 20MB"
          />
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.quality-strip {
  display: flex;
  align-items: center;
  gap: 40px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 15px 20px;
  margin-bottom: 14px;
  box-shadow: var(--shadow);
}

.quality-item span {
  color: var(--muted);
  font-size: 12px;
  display: block;
}

.quality-item b {
  display: block;
  margin-top: 6px;
  font-size: 20px;
  font-family: Bahnschrift, monospace;
}

.quality-rate {
  margin-left: auto;
  width: 220px;
}

.quality-rate span {
  color: var(--muted);
  font-size: 12px;
}

.quality-rate b {
  float: right;
  font-size: 22px;
  font-family: Bahnschrift, monospace;
  color: var(--success);
}

.quality-rate small {
  font-size: 12px;
  margin-left: 2px;
}

.rate-bar {
  clear: both;
  margin-top: 8px;
  height: 6px;
  border-radius: 4px;
  background: #eef2f6;
  overflow: hidden;
}

.rate-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #12b76a, #66d9a3);
}

.quality-note {
  padding: 10px 12px;
  background: #f0f6ff;
  border: 1px dashed #b2ccff;
  border-radius: 6px;
  color: #1d4ed8;
  font-size: 12px;
  line-height: 1.7;
}

.item-top-panel {
  padding: 14px 18px;
}

.item-top-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.item-top-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 12px;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  background: #fff7ed;
  font-size: 12px;
}

.item-top-row .item-name {
  color: #c2410c;
  font-weight: 600;
}

.item-top-row .item-fail {
  color: #dc2626;
}

.sample-line {
  margin: 2px 0 10px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #f8fafc;
  color: #334155;
  font-size: 12px;
}

.defect-total {
  display: flex;
  align-items: center;
  height: 32px;
  padding: 0 12px;
  border-radius: 6px;
  background: #fef2f2;
  color: #b91c1c;
  font-weight: 600;
}

.item-block {
  margin-bottom: 12px;
}

.detail-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  font-size: 13px;
}

@media (max-width: 1100px) {
  .quality-strip {
    flex-wrap: wrap;
    gap: 20px;
  }
  .quality-rate {
    margin-left: 0;
    width: 100%;
  }
}
</style>
