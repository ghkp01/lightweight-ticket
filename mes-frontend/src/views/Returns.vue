<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtQty } from '../api'
import StatusTag from '../components/StatusTag.vue'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import { useTableView } from '../composables/useTableView'

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const status = ref('')
const orders = ref<any[]>([])
const route = useRoute()

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'returns',
  columns: [
    { key: 'returnNo', label: '退货单号', width: 165 },
    { key: 'orderNo', label: '销售订单', width: 160 },
    { key: 'customerName', label: '客户', width: 170, flex: true, ellipsis: true },
    { key: 'totalQty', label: '退货数量', width: 95, align: 'right' },
    { key: 'disposition', label: '处理方式', width: 110 },
    { key: 'reason', label: '退货原因', width: 170, flex: true, ellipsis: true },
    { key: 'reworkWorkNo', label: '返修工单', width: 150 },
    { key: 'status', label: '状态', width: 100 },
    { key: 'createdAt', label: '创建日期', width: 105 },
    { key: 'actions', label: '操作', width: 180, fixed: 'right' }
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

const dialog = ref(false)
const saving = ref(false)
const form = reactive<any>({
  salesOrderId: null,
  disposition: 'RESTOCK',
  reason: '',
  items: []
})

const detailDrawer = ref(false)
const detailLoading = ref(false)
const detail = ref<any>(null)

const dispositionMap: Record<string, { label: string; cls: string }> = {
  RESTOCK: { label: '重新入库', cls: 'success' },
  REWORK: { label: '返修', cls: 'warning' },
  SCRAP: { label: '报废', cls: 'danger' }
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/returns', {
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

const openCreate = async () => {
  const data = await api.get('/sales-orders', { page: 1, size: 100 })
  orders.value = data.records.filter((item: any) => ['SHIPPED', 'COMPLETED'].includes(item.status))
  Object.assign(form, { salesOrderId: null, disposition: 'RESTOCK', reason: '', items: [] })
  dialog.value = true
}

const onOrderChange = async (id: number) => {
  const data = await api.get(`/sales-orders/${id}`)
  form.items = data.items
    .filter((item: any) => Number(item.shippedQty) > 0)
    .map((item: any) => ({
      productId: item.productId,
      productCode: item.productCode,
      productName: item.productName,
      unit: item.unit,
      shippedQty: Number(item.shippedQty),
      qty: 1,
      checked: false
    }))
}

const submit = async () => {
  if (!form.salesOrderId) {
    ElMessage.warning('请选择销售订单')
    return
  }
  const items = form.items.filter((item: any) => item.checked && Number(item.qty) > 0)
  if (!items.length) {
    ElMessage.warning('请选择退货产品并填写数量')
    return
  }
  if (!form.reason) {
    ElMessage.warning('请填写退货原因')
    return
  }
  saving.value = true
  try {
    await api.post('/returns', {
      salesOrderId: form.salesOrderId,
      disposition: form.disposition,
      reason: form.reason,
      items: items.map((item: any) => ({ productId: item.productId, qty: Number(item.qty) }))
    })
    ElMessage.success('退货单已创建')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const process = async (row: any) => {
  const tip = row.disposition === 'RESTOCK'
    ? '处理后将把退货数量重新入成品仓，可再次发货。'
    : row.disposition === 'REWORK'
      ? '处理后会按原工艺路线开一张返修工单，返修完走完工检再入库。'
      : '处理后会写进不良台账并标记报废，不回库存。'
  await ElMessageBox.confirm(`确认处理退货单 ${row.returnNo}？${tip}`, '处理确认', { type: 'warning' })
  await api.post(`/returns/${row.id}/process`)
  ElMessage.success('退货已处理')
  load()
}

const closeReturn = async (row: any) => {
  await ElMessageBox.confirm(`确认关闭退货单 ${row.returnNo}？`, '关闭确认')
  await api.post(`/returns/${row.id}/close`)
  ElMessage.success('退货单已关闭')
  load()
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  try {
    detail.value = await api.get(`/returns/${row.id}`)
  } finally {
    detailLoading.value = false
  }
}

const changePage = (value: number) => {
  page.value = value
  load()
}

onMounted(async () => {
  await table.load()
  // 从消息中心跳过来：按退货单号定位，状态清空避免已处理单据搜不到
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
        <h2 class="page-title">退货管理</h2>
        <p class="page-sub">按已发货数量控制退货，处理方式决定是否回补成品库存</p>
      </div>
      <el-button v-perm="'returns:create'" type="primary" @click="openCreate">新建退货单</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="退货单 / 订单号 / 客户" clearable style="width: 250px" @keyup.enter="load" />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option label="待处理" value="PENDING" />
          <el-option label="已处理" value="PROCESSED" />
          <el-option label="已关闭" value="CLOSED" />
        </el-select>
        <el-button type="primary" plain @click="load">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/returns" export-name="退货单" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 张退货单</span>
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
          <template #cell-returnNo="{ row }"><span class="mono">{{ row.returnNo }}</span></template>
          <template #cell-orderNo="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          <template #cell-totalQty="{ row }"><span class="num">{{ fmtQty(row.totalQty) }}</span></template>
          <template #cell-disposition="{ row }">
            <span class="status-tag" :class="dispositionMap[row.disposition]?.cls">
              <i></i>{{ dispositionMap[row.disposition]?.label || row.disposition }}
            </span>
          </template>
          <template #cell-reworkWorkNo="{ row }">
            <span v-if="row.reworkWorkNo" class="mono">{{ row.reworkWorkNo }}</span>
            <span v-else class="muted">-</span>
          </template>
          <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 'PENDING'" v-perm="'returns:process'" link type="success" @click="process(row)">处理</el-button>
            <el-button v-if="row.status === 'PROCESSED'" v-perm="'returns:close'" link type="info" @click="closeReturn(row)">关闭</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" title="新建退货单" width="720px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="销售订单" required class="full">
            <el-select v-model="form.salesOrderId" filterable placeholder="仅显示已发货或已完成订单" style="width: 100%" @change="onOrderChange">
              <el-option
                v-for="item in orders"
                :key="item.id"
                :label="`${item.orderNo} · ${item.customerName}`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="处理方式" required>
            <el-select v-model="form.disposition" style="width: 100%">
              <el-option label="重新入库" value="RESTOCK" />
              <el-option label="返修" value="REWORK" />
              <el-option label="报废" value="SCRAP" />
            </el-select>
          </el-form-item>
          <el-form-item label="退货原因" required>
            <el-input v-model="form.reason" placeholder="外观、尺寸、功能等" />
          </el-form-item>
        </div>

        <div class="drawer-section">
          <h4>退货明细</h4>
          <el-table :data="form.items" size="small" empty-text="请选择销售订单">
            <el-table-column label="" width="45">
              <template #default="{ row }"><el-checkbox v-model="row.checked" /></template>
            </el-table-column>
            <el-table-column prop="productCode" label="编码" width="80" />
            <el-table-column prop="productName" label="产品" min-width="160" />
            <el-table-column label="已发数量" width="100" align="right">
              <template #default="{ row }">{{ fmtQty(row.shippedQty) }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column label="退货数量" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="0.01" :max="row.shippedQty" :precision="2" size="small" style="width: 100%" />
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div class="return-note">
          「重新入库」处理时增加成品库存；「返修」按原工艺路线开返修工单（带 BOM 与工价），返修后走完工检入库；
          「报废」写入不良台账留痕，不回库存。处理结果都会推送给质检与计划。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">创建退货单</el-button>
      </template>
    </el-dialog>

    <el-drawer append-to-body v-model="detailDrawer" size="640px" title="退货单详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-no mono">{{ detail.returnNo }}</div>
              <div class="muted" style="margin-top: 4px">{{ detail.customerName }} · {{ detail.orderNo }}</div>
            </div>
            <StatusTag :status="detail.status" />
          </div>
          <div class="detail-grid">
            <div class="detail-item">
              <div class="label">处理方式</div>
              <div class="value">
                <span class="status-tag" :class="dispositionMap[detail.disposition]?.cls">
                  <i></i>{{ dispositionMap[detail.disposition]?.label }}
                </span>
              </div>
            </div>
            <div class="detail-item"><div class="label">退货数量</div><div class="value num">{{ fmtQty(detail.totalQty) }}</div></div>
            <div class="detail-item"><div class="label">创建时间</div><div class="value">{{ detail.createdAt }}</div></div>
            <div class="detail-item" style="grid-column: span 3">
              <div class="label">退货原因</div><div class="value">{{ detail.reason || '-' }}</div>
            </div>
            <div class="detail-item"><div class="label">处理时间</div><div class="value">{{ detail.processedAt || '-' }}</div></div>
            <div class="detail-item">
              <div class="label">返修工单</div>
              <div class="value">
                <span v-if="detail.reworkWorkNo" class="mono">{{ detail.reworkWorkNo }}</span>
                <span v-else class="muted">无（返修处理时自动生成）</span>
                <StatusTag v-if="detail.reworkStatus" :status="detail.reworkStatus" style="margin-left: 6px" />
              </div>
            </div>
          </div>
          <div class="drawer-section">
            <h4>退货明细</h4>
            <el-table :data="detail.items" size="small">
              <el-table-column prop="productCode" label="编码" width="85" />
              <el-table-column prop="productName" label="产品" min-width="160" />
              <el-table-column prop="spec" label="规格" min-width="120" />
              <el-table-column label="数量" width="100" align="right">
                <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </div>
    </el-drawer>
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

.return-note {
  margin-top: 14px;
  padding: 10px 12px;
  background: #fffaeb;
  border: 1px dashed #fedf89;
  border-radius: 6px;
  color: #b54708;
  font-size: 12px;
  line-height: 1.7;
}
</style>
