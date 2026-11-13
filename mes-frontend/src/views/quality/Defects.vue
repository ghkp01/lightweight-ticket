<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, fmtQty } from '../../api'
import MesAttachments from '../../components/Attachments.vue'
import DataTable from '../../components/DataTable.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import { useTableView } from '../../composables/useTableView'

const loading = ref(false)
const rows = ref<any[]>([])
const status = ref('PENDING')
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const route = useRoute()
const summary = ref<any>({
  pending: 0,
  pendingQty: 0,
  rework: 0,
  concession: 0,
  scrap: 0,
  returnSupplier: 0
})

const disposeDialog = ref(false)
const disposing = ref(false)
const current = ref<any>(null)
const form = reactive({ disposition: 'REWORK', remark: '' })

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'quality.defects',
  columns: [
    { key: 'defectNo', label: '不良单号', width: 150 },
    { key: 'source', label: '来源', width: 120 },
    { key: 'product', label: '产品', width: 140, flex: true, ellipsis: true, prop: 'productName' },
    { key: 'work', label: '工单 / 工序', width: 170, flex: true, ellipsis: true, prop: '' },
    { key: 'qty', label: '不良数量', width: 95, align: 'right' },
    { key: 'reason', label: '不良原因', width: 150, flex: true, ellipsis: true },
    { key: 'disposition', label: '处置', width: 120 },
    { key: 'reworkWorkNo', label: '返修工单', width: 150 },
    { key: 'createdAt', label: '发现时间', width: 140 },
    { key: 'handled', label: '处置人 / 时间', width: 160, prop: '' },
    { key: 'actions', label: '操作', width: 100, fixed: 'right', prop: '' }
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

const sourceMap: Record<string, { label: string; cls: string }> = {
  REPORT: { label: '报工不良', cls: 'warning' },
  PROCESS_QC: { label: '工序检不合格', cls: 'warning' },
  FINAL_QC: { label: '完工检不合格', cls: 'danger' },
  INCOMING: { label: '来料不合格', cls: 'info' },
  RETURN: { label: '退货不良', cls: 'primary' }
}

const dispositionMap: Record<string, { label: string; cls: string }> = {
  REWORK: { label: '返修', cls: 'warning' },
  CONCESSION: { label: '让步接收', cls: 'primary' },
  SCRAP: { label: '报废', cls: 'danger' },
  RETURN_SUPPLIER: { label: '退供应商', cls: 'info' }
}

const dispositionOptions = [
  {
    value: 'REWORK',
    label: '返修',
    desc: '按原 BOM 与工艺路线开一张返修工单，走完工检后重新入库'
  },
  {
    value: 'CONCESSION',
    label: '让步接收',
    desc: '客户或工程同意让步，数量直接入成品仓并计入工单合格数'
  },
  {
    value: 'SCRAP',
    label: '报废',
    desc: '只记台账，不进库存、不计入合格数，用于统计损失'
  },
  {
    value: 'RETURN_SUPPLIER',
    label: '退供应商',
    desc: '来料或外协不良退回供应商，只记台账不冲库存'
  }
]

const load = async () => {
  loading.value = true
  try {
    const [data, sum] = await Promise.all([
      api.get('/defects', {
        status: status.value,
        keyword: keyword.value,
        page: page.value,
        size: size.value
      }),
      api.get('/defects/summary')
    ])
    rows.value = data?.records || []
    total.value = data?.total || 0
    summary.value = sum
  } finally {
    loading.value = false
  }
}

const changePage = (value: number) => {
  page.value = value
  load()
}

const sourceOf = (value: any) =>
  sourceMap[String(value || '')] || { label: String(value || '-'), cls: 'info' }

const dispositionOf = (value: any) =>
  dispositionMap[String(value || '')] || { label: String(value || '-'), cls: 'info' }

const openDispose = (row: any) => {
  current.value = row
  form.disposition = 'REWORK'
  form.remark = ''
  disposeDialog.value = true
}

const submitDispose = async () => {
  if (!current.value) return
  disposing.value = true
  try {
    const data = await api.post(`/defects/${current.value.id}/dispose`, {
      disposition: form.disposition,
      remark: form.remark
    })
    if (data?.reworkWorkNo) {
      const fromSeq = data?.reworkFromSeq
      ElMessage.success(
        fromSeq
          ? `已处置为返修，返修工单 ${data.reworkWorkNo}（从第 ${fromSeq} 道工序起）`
          : `已处置为返修，返修工单 ${data.reworkWorkNo}（整件返修）`
      )
    } else {
      ElMessage.success(`已处置为${dispositionOf(form.disposition).label}`)
    }
    disposeDialog.value = false
    load()
  } finally {
    disposing.value = false
  }
}

const currentTip = computed(
  () => dispositionOptions.find((item) => item.value === form.disposition)?.desc || ''
)

onMounted(async () => {
  await table.load()
  // 从消息中心跳过来：按不良单号定位，状态清空以免已处置的单搜不到
  const fromQuery = String(route.query.keyword || '').trim()
  if (fromQuery) {
    keyword.value = fromQuery
    status.value = ''
  }
  page.value = 1
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">不良品处置</h2>
        <p class="page-sub">
          报工不良、完工检不合格、来料不合格与退货都会进这张台账，质检在这里给出返修 / 让步 / 报废 / 退供应商的结论
        </p>
      </div>
      <el-button plain @click="load">刷新</el-button>
    </div>

    <div class="defect-strip">
      <div class="defect-item">
        <span>待处置笔数</span>
        <b class="danger-text">{{ summary.pending || 0 }}</b>
      </div>
      <div class="defect-item">
        <span>待处置数量</span>
        <b>{{ fmtQty(summary.pendingQty) }}</b>
      </div>
      <div class="defect-item">
        <span>已返修</span>
        <b>{{ summary.rework || 0 }}</b>
      </div>
      <div class="defect-item">
        <span>让步接收</span>
        <b>{{ summary.concession || 0 }}</b>
      </div>
      <div class="defect-item">
        <span>报废</span>
        <b>{{ summary.scrap || 0 }}</b>
      </div>
      <div class="defect-item">
        <span>退供应商</span>
        <b>{{ summary.returnSupplier || 0 }}</b>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="不良单号 / 工单号 / 产品"
          clearable
          style="width: 240px"
          @keyup.enter="reload"
        />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="reload">
          <el-option label="待处置" value="PENDING" />
          <el-option label="已处置" value="DISPOSED" />
        </el-select>
        <el-button type="primary" plain @click="reload">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/defects" export-name="不良台账" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 条不良记录</span>
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
          <template #cell-defectNo="{ row }"><span class="mono">{{ row.defectNo }}</span></template>
          <template #cell-source="{ row }">
            <span class="status-tag" :class="sourceOf(row.source).cls">
              <i></i>{{ sourceOf(row.source).label }}
            </span>
          </template>
          <template #cell-product="{ row }">
            <div>{{ row.productName || '-' }}</div>
            <div class="muted" style="font-size: 11px">{{ row.productCode }}</div>
          </template>
          <template #cell-work="{ row }">
            <div class="mono">{{ row.workNo || '-' }}</div>
            <div class="muted" style="font-size: 11px">
              <template v-if="row.operationSeq">{{ row.operationSeq }} · {{ row.operationName }}</template>
              <template v-else>{{ row.processName || '无工序' }}</template>
            </div>
          </template>
          <template #cell-qty="{ row }"><span class="danger-text">{{ fmtQty(row.qty) }}</span></template>
          <template #cell-disposition="{ row }">
            <span v-if="row.disposition" class="status-tag" :class="dispositionOf(row.disposition).cls">
              <i></i>{{ dispositionOf(row.disposition).label }}
            </span>
            <span v-else class="muted">待处理</span>
          </template>
          <template #cell-reworkWorkNo="{ row }">
            <span v-if="row.reworkWorkNo" class="mono">{{ row.reworkWorkNo }}</span>
            <span v-else class="muted">-</span>
          </template>
          <template #cell-createdAt="{ row }"><span class="muted">{{ row.createdAt }}</span></template>
          <template #cell-handled="{ row }">
            <div>{{ row.handledBy || '-' }}</div>
            <div class="muted" style="font-size: 11px">{{ row.handledAt || '' }}</div>
          </template>
          <template #cell-actions="{ row }">
            <el-button
              v-if="row.status === 'PENDING'"
              v-perm="'defect:handle'"
              link
              type="primary"
              @click="openDispose(row)"
            >
              处置
            </el-button>
            <span v-else class="muted">已结案</span>
          </template>
        </DataTable>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="disposeDialog" title="不良处置" width="680px">
      <div v-if="current" class="defect-card">
        <div>
          <b>{{ current.defectNo }}</b>
          <span class="muted"> · {{ sourceOf(current.source).label }}</span>
        </div>
        <div class="defect-meta">
          {{ current.productName }} × {{ fmtQty(current.qty) }}
          <template v-if="current.workNo"> · {{ current.workNo }}</template>
        </div>
        <div v-if="current.reason" class="defect-meta">原因：{{ current.reason }}</div>
      </div>

      <el-form label-position="top">
        <el-form-item label="处置方式" required>
          <el-radio-group v-model="form.disposition">
            <el-radio v-for="item in dispositionOptions" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio>
          </el-radio-group>
          <div class="dispose-tip">{{ currentTip }}</div>
          <div v-if="form.disposition === 'REWORK' && current?.operationSeq" class="dispose-tip">
            该不良是在第 {{ current.operationSeq }} 道工序（{{ current.operationName }}）发现的，
            返修工单会从这道工序起做，前面的工序不再重复加工。
          </div>
        </el-form-item>
        <el-form-item label="处置说明">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
            placeholder="如：客户同意让步、返修内容、报废原因等"
          />
        </el-form-item>

        <el-divider content-position="left">现场照片 / 佐证</el-divider>
        <MesAttachments
          v-if="current"
          :key="current.id"
          biz-type="DEFECT"
          :biz-id="current.id"
          :biz-no="current.defectNo"
          title="不良照片"
          hint="可直接用手机拍完传上来，图片、PDF、Excel 都支持，单个不超过 20MB"
        />
      </el-form>

      <template #footer>
        <el-button @click="disposeDialog = false">取消</el-button>
        <el-button type="primary" :loading="disposing" @click="submitDispose">确认处置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.defect-strip {
  display: flex;
  align-items: center;
  gap: 36px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 15px 20px;
  margin-bottom: 14px;
  box-shadow: var(--shadow);
  flex-wrap: wrap;
}

.defect-item span {
  color: var(--muted);
  font-size: 12px;
  display: block;
}

.defect-item b {
  display: block;
  margin-top: 6px;
  font-size: 20px;
  font-family: Bahnschrift, monospace;
}

.defect-card {
  background: #f8fafc;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 14px;
}

.defect-meta {
  margin-top: 5px;
  font-size: 12px;
  color: var(--ink-3);
}

.dispose-tip {
  margin-top: 6px;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
}
</style>
