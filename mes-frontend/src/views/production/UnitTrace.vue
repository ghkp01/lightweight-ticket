<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../../api'
import DataTable from '../../components/DataTable.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import { useTableView } from '../../composables/useTableView'

/**
 * 单件追踪：不挂在某一张工单下，直接按件码 / 工单 / 产品找到「这件现在在哪」。
 * 车间与计划都从这里查件，工单详情里的那份分布只服务单张工单。
 */

const router = useRouter()
const route = useRoute()

const rows = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const status = ref('')
const operationSeq = ref<any>('')
const page = ref(1)
const total = ref(0)
const size = 20
const summary = ref<any>({ total: 0, pending: 0, inProgress: 0, finished: 0, scrapped: 0 })
const operations = ref<any[]>([])

const drawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const table = useTableView({
  pageKey: 'production.units',
  columns: [
    { key: 'unitNo', label: '件码', width: 180, fixed: 'left' },
    { key: 'status', label: '状态', width: 90 },
    { key: 'workNo', label: '工单号', width: 160 },
    { key: 'productName', label: '产品', width: 170, flex: true },
    { key: 'currentOperationName', label: '当前工序', width: 110 },
    { key: 'lastWorker', label: '最后操作人', width: 110 },
    { key: 'lastActionText', label: '最后操作时间', width: 120 },
    { key: 'actions', label: '操作', width: 120, fixed: 'right' }
  ],
  getFilters: () => ({
    keyword: keyword.value,
    status: status.value,
    operationSeq: operationSeq.value || ''
  }),
  setFilters: (filters: any) => {
    keyword.value = filters.keyword || ''
    status.value = filters.status || ''
    operationSeq.value = filters.operationSeq || ''
  }
})

const statusTabs = computed(() => [
  { key: '', label: '全部', value: summary.value.total, cls: '' },
  { key: 'PENDING', label: '待投产', value: summary.value.pending, cls: 'draft' },
  { key: 'IN_PROGRESS', label: '在制', value: summary.value.inProgress, cls: 'warning' },
  { key: 'FINISHED', label: '已完工', value: summary.value.finished, cls: 'success' },
  { key: 'SCRAPPED', label: '已报废', value: summary.value.scrapped, cls: 'danger' }
])

const statusLabel = (value: string) =>
  ({ PENDING: '待投产', IN_PROGRESS: '在制', FINISHED: '已完工', SCRAPPED: '已报废' }[value] || value)
const statusCls = (value: string) =>
  ({ PENDING: 'draft', IN_PROGRESS: 'warning', FINISHED: 'success', SCRAPPED: 'danger' }[value] || 'info')
const actionLabel = (value: string) => ({ START: '开工', FINISH: '完工', SCRAP: '报废' }[value] || value)

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/work-order-units', {
      keyword: keyword.value || undefined,
      status: status.value || undefined,
      operationSeq: operationSeq.value || undefined,
      page: page.value,
      size
    })
    rows.value = data?.records || []
    total.value = Number(data?.total || 0)
    if (data?.summary) summary.value = data.summary
  } finally {
    loading.value = false
  }
}

const loadOperations = async () => {
  try {
    operations.value = (await api.get('/work-order-units/operations')) || []
  } catch {
    operations.value = []
  }
}

const pickStatus = (key: string) => {
  status.value = key
  page.value = 1
  load()
}

const changePage = (value: number) => {
  page.value = value
  load()
}

const search = () => {
  page.value = 1
  load()
}

const reset = () => {
  keyword.value = ''
  status.value = ''
  operationSeq.value = ''
  page.value = 1
  load()
}

const openUnit = async (row: any) => {
  drawer.value = true
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await api.get(`/work-order-units/${encodeURIComponent(row.unitNo)}`)
  } finally {
    detailLoading.value = false
  }
}

const openWorkOrder = (workNo: string) => {
  router.push({ path: '/production', query: { keyword: workNo, open: '1' } })
}

const printLabel = (workNo: string) => {
  const target = { path: '/print/labels', query: { workNo } }
  const opened = window.open(router.resolve(target).href, '_blank')
  if (!opened) router.push(target)
}

const goScan = () => {
  router.push('/shopfloor/scan')
}

onMounted(async () => {
  const fromQuery = String(route.query.keyword || '').trim()
  if (fromQuery) keyword.value = fromQuery
  await table.load()
  await loadOperations()
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">单件追踪</h2>
        <p class="page-sub">
          一件一码，扫件码就能看到这件在哪道工序、谁最后动过；单件管理的产品在工单下达时自动生成件码
        </p>
      </div>
      <div class="head-actions">
        <el-button plain @click="goScan">去车间扫码</el-button>
        <el-button type="primary" plain @click="reset">重置</el-button>
      </div>
    </div>

    <div class="unit-strip">
      <button
        v-for="tab in statusTabs"
        :key="tab.key || 'all'"
        type="button"
        class="strip-item"
        :class="[{ active: status === tab.key }, tab.cls]"
        @click="pickStatus(tab.key)"
      >
        <span>{{ tab.label }}</span>
        <b class="num">{{ tab.value }}</b>
      </button>
      <div class="strip-tip">
        件在完工或报废后结束流转；已在制但停了很久的件，用「最后操作时间」倒序看最上面。
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="件码 / 工单号 / 产品"
          clearable
          style="width: 240px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select v-model="operationSeq" placeholder="全部工序" clearable style="width: 150px" @change="search">
          <el-option
            v-for="op in operations"
            :key="op.seq"
            :label="`${op.seq}. ${op.name}`"
            :value="op.seq"
          />
        </el-select>
        <el-button type="primary" plain @click="search">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/work-order-units" export-name="单件追踪" @applied="load" />
      </div>

      <div class="table-wrap">
        <DataTable
          :table="table"
          :data="rows"
          :loading="loading"
          :total="total"
          :page="page"
          :size="size"
          empty-text="没有件记录：产品打开「单件管理」并在工单下达后才会生成件码"
          @page-change="changePage"
        >
          <template #cell-unitNo="{ row }">
            <el-button link type="primary" class="mono" @click="openUnit(row)">{{ row.unitNo }}</el-button>
          </template>
          <template #cell-status="{ row }">
            <span class="status-tag" :class="statusCls(row.status)"><i></i>{{ statusLabel(row.status) }}</span>
          </template>
          <template #cell-workNo="{ row }">
            <el-button link type="primary" class="mono" @click="openWorkOrder(row.workNo)">
              {{ row.workNo }}
            </el-button>
          </template>
          <template #cell-currentOperationName="{ row }">
            <span v-if="row.currentOperationName">{{ row.currentOperationName }}</span>
            <span v-else class="muted">{{ row.status === 'FINISHED' ? '已走完' : '-' }}</span>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openUnit(row)">流转记录</el-button>
            <el-button link @click="printLabel(row.workNo)">标签</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <el-drawer append-to-body v-model="drawer" size="560px" title="单件流转">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-no mono">{{ detail.unitNo }}</div>
              <div class="detail-product">{{ detail.productName }}</div>
              <div class="muted detail-sub">
                {{ detail.productCode }} · 工单
                <el-button link type="primary" class="mono" @click="openWorkOrder(detail.workNo)">
                  {{ detail.workNo }}
                </el-button>
                · 第 {{ detail.seqNo }} 件
              </div>
            </div>
            <span class="status-tag" :class="statusCls(detail.status)">
              <i></i>{{ statusLabel(detail.status) }}
            </span>
          </div>

          <div class="flow">
            <div class="flow-item">
              <span>当前工序</span>
              <b v-if="detail.currentOperation">
                {{ detail.currentOperation.seq }}. {{ detail.currentOperation.name }}
              </b>
              <b v-else class="muted">{{ detail.status === 'FINISHED' ? '已走完全部工序' : '待投产' }}</b>
            </div>
            <div class="flow-arrow">→</div>
            <div class="flow-item">
              <span>下一道工序</span>
              <b v-if="detail.nextOperation">
                {{ detail.nextOperation.seq }}. {{ detail.nextOperation.name }}
              </b>
              <b v-else class="muted">本件末道工序</b>
            </div>
          </div>

          <h4 class="block-title">流转记录</h4>
          <div class="log-list">
            <div v-for="(log, index) in detail.logs || []" :key="index" class="log-row">
              <span class="log-dot" :class="String(log.action).toLowerCase()"></span>
              <div class="log-main">
                <b>{{ actionLabel(log.action) }}</b>
                <span v-if="log.operationName">{{ log.operationSeq }}. {{ log.operationName }}</span>
                <small v-if="log.remark">{{ log.remark }}</small>
              </div>
              <span class="log-worker">{{ log.worker || '-' }}</span>
              <span class="log-time mono">{{ log.createdAt }}</span>
            </div>
            <div v-if="!(detail.logs || []).length" class="log-empty">
              还没有流转记录，去车间扫码页扫这个件码开工
            </div>
          </div>

          <div class="drawer-actions">
            <el-button plain @click="printLabel(detail.workNo)">打印件码标签</el-button>
            <el-button type="primary" @click="goScan">去车间扫码</el-button>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.unit-strip {
  display: flex;
  align-items: stretch;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid var(--line);
}

.strip-item {
  flex: 0 0 auto;
  min-width: 96px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 7px 12px;
  border-radius: 8px;
  border: 1px solid var(--line);
  background: #f8fafc;
  cursor: pointer;
  transition: 0.15s;
}

.strip-item span {
  font-size: 11px;
  color: var(--muted);
}

.strip-item b {
  font-size: 17px;
  line-height: 1.1;
}

.strip-item:hover {
  border-color: var(--line-strong);
}

.strip-item.active {
  border-color: var(--primary);
  background: var(--primary-soft);
}

.strip-item.active span {
  color: var(--primary);
}

.strip-item.active b {
  color: var(--primary);
}

.strip-item.draft.active {
  border-color: #98a2b3;
  background: #f2f4f7;
}

.strip-item.warning.active {
  border-color: #fdb022;
  background: #fffaeb;
}

.strip-item.warning.active b,
.strip-item.warning.active span {
  color: #b54708;
}

.strip-item.success.active {
  border-color: #12b76a;
  background: #ecfdf3;
}

.strip-item.success.active b,
.strip-item.success.active span {
  color: #027a48;
}

.strip-item.danger.active {
  border-color: #f04438;
  background: #fef3f2;
}

.strip-item.danger.active b,
.strip-item.danger.active span {
  color: #b42318;
}

.strip-tip {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  font-size: 11px;
  line-height: 1.7;
  color: var(--muted);
  padding-left: 6px;
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding-bottom: 12px;
  border-bottom: 1px dashed var(--line);
}

.detail-no {
  font-size: 18px;
  font-weight: 700;
}

.detail-product {
  margin-top: 4px;
  font-size: 14px;
  font-weight: 600;
}

.detail-sub {
  margin-top: 4px;
  font-size: 11px;
  display: flex;
  align-items: center;
  gap: 2px;
  flex-wrap: wrap;
}

.flow {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  margin-top: 14px;
}

.flow-item {
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid var(--line);
  min-width: 0;
}

.flow-item span {
  display: block;
  font-size: 11px;
  color: var(--muted);
  margin-bottom: 4px;
}

.flow-item b {
  font-size: 14px;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.flow-arrow {
  color: var(--primary);
}

.block-title {
  margin: 18px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.log-list {
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}

.log-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto 82px;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-bottom: 1px solid #f2f4f7;
  font-size: 12px;
}

.log-row:last-child {
  border-bottom: none;
}

.log-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary);
}

.log-dot.finish {
  background: #12b76a;
}

.log-dot.scrap {
  background: #f04438;
}

.log-main {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.log-main b {
  flex: 0 0 auto;
}

.log-main span {
  color: var(--ink-2);
}

.log-main small {
  color: var(--muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.log-worker {
  color: var(--ink-2);
}

.log-time {
  font-size: 11px;
  color: var(--muted);
  text-align: right;
}

.log-empty {
  padding: 26px 16px;
  text-align: center;
  font-size: 12px;
  color: var(--muted);
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}
</style>
