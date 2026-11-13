<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtQty } from '../api'
import StatusTag from '../components/StatusTag.vue'
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

const createDialog = ref(false)
const saving = ref(false)
const form = reactive<any>({
  salesOrderId: null,
  carrier: '顺丰速运',
  trackingNo: '',
  address: '',
  items: []
})
const orderDetail = ref<any>(null)

const detailDrawer = ref(false)
const detailLoading = ref(false)
const detail = ref<any>(null)

const table = useTableView({
  pageKey: 'shipping.shipments',
  columns: [
    { key: 'shipmentNo', label: '发货单号', width: 165, fixed: 'left' },
    { key: 'orderNo', label: '销售订单', width: 160 },
    { key: 'customerName', label: '客户', width: 170, flex: true, ellipsis: true },
    { key: 'totalQty', label: '发货数量', width: 95, align: 'right' },
    { key: 'carrier', label: '承运商', width: 100 },
    { key: 'trackingNo', label: '运单号', width: 150 },
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
  setPageSize: (value) => { size.value = value }
})

const reload = () => { page.value = 1; load() }

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/shipments', {
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
  Object.assign(form, { salesOrderId: null, carrier: '顺丰速运', trackingNo: '', address: '', items: [] })
  orderDetail.value = null
  createDialog.value = true
}

const onOrderChange = async (id: number) => {
  if (!id) {
    orderDetail.value = null
    form.items = []
    return
  }
  const data = await api.get(`/sales-orders/${id}`)
  orderDetail.value = data
  form.address = data.address || ''
  form.items = data.items
    .filter((item: any) => Number(item.remainingQty) > 0)
    .map((item: any) => ({
      orderItemId: item.id,
      productCode: item.productCode,
      productName: item.productName,
      unit: item.unit,
      remainingQty: Number(item.remainingQty),
      qty: Number(item.remainingQty),
      checked: true
    }))
}

const submit = async () => {
  if (!form.salesOrderId) {
    ElMessage.warning('请选择销售订单')
    return
  }
  const items = form.items.filter((item: any) => item.checked && Number(item.qty) > 0)
  if (!items.length) {
    ElMessage.warning('请至少选择一条发货明细')
    return
  }
  saving.value = true
  try {
    await api.post('/shipments', {
      salesOrderId: form.salesOrderId,
      carrier: form.carrier,
      trackingNo: form.trackingNo,
      address: form.address,
      items: items.map((item: any) => ({ orderItemId: item.orderItemId, qty: Number(item.qty) }))
    })
    ElMessage.success('发货单已创建')
    createDialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const ship = async (row: any) => {
  await ElMessageBox.confirm(
    `确认发货单 ${row.shipmentNo} 出库？系统将扣减成品库存并回写订单已发数量。`,
    '发货确认',
    { type: 'warning' }
  )
  await api.post(`/shipments/${row.id}/ship`)
  ElMessage.success('已发货，成品库存已扣减')
  load()
}

const sign = async (row: any) => {
  await ElMessageBox.confirm(`确认客户已签收 ${row.shipmentNo}？`, '签收确认')
  await api.post(`/shipments/${row.id}/sign`)
  ElMessage.success('已签收')
  load()
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(`确认删除草稿发货单 ${row.shipmentNo}？`, '删除确认', { type: 'warning' })
  await api.delete(`/shipments/${row.id}`)
  ElMessage.success('已删除')
  load()
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  try {
    detail.value = await api.get(`/shipments/${row.id}`)
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
  await load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">发货管理</h2>
        <p class="page-sub">只允许对未发数量发货，发货即扣成品库存，签收后订单自动完成</p>
      </div>
      <el-button v-perm="'shipping:create'" type="primary" @click="openCreate">新建发货单</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="发货单 / 订单号 / 客户" clearable style="width: 250px" @keyup.enter="reload" />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="reload">
          <el-option label="待发货" value="DRAFT" />
          <el-option label="已发货" value="SHIPPED" />
          <el-option label="已签收" value="SIGNED" />
        </el-select>
        <el-button type="primary" plain @click="reload">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/shipments" export-name="发货单" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 张发货单</span>
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
          <template #cell-shipmentNo="{ row }"><span class="mono">{{ row.shipmentNo }}</span></template>
          <template #cell-orderNo="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          <template #cell-totalQty="{ row }"><span class="num">{{ fmtQty(row.totalQty) }}</span></template>
          <template #cell-trackingNo="{ row }"><span class="mono">{{ row.trackingNo || '-' }}</span></template>
          <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 'DRAFT'" v-perm="'shipping:ship'" link type="success" @click="ship(row)">发货</el-button>
            <el-button v-if="row.status === 'SHIPPED'" v-perm="'shipping:sign'" link type="success" @click="sign(row)">签收</el-button>
            <el-button v-if="row.status === 'DRAFT'" v-perm="'shipping:delete'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="createDialog" title="新建发货单" width="760px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="销售订单" required class="full">
            <ReferenceSelect
              v-model="form.salesOrderId"
              url="/sales-orders/options"
              :params="{ status: 'READY_TO_SHIP' }"
              code-field="orderNo"
              label-field="customerName"
              placeholder="输入订单号或客户搜索（仅待发货）"
              @select="(order) => onOrderChange(order ? order.id : null)"
            />
          </el-form-item>
          <el-form-item label="承运商">
            <el-input v-model="form.carrier" />
          </el-form-item>
          <el-form-item label="运单号">
            <el-input v-model="form.trackingNo" placeholder="可稍后补充" />
          </el-form-item>
          <el-form-item label="收货地址" class="full">
            <el-input v-model="form.address" />
          </el-form-item>
        </div>

        <div class="drawer-section">
          <h4>发货明细</h4>
          <el-table :data="form.items" size="small" empty-text="请选择销售订单">
            <el-table-column label="" width="45">
              <template #default="{ row }"><el-checkbox v-model="row.checked" /></template>
            </el-table-column>
            <el-table-column prop="productCode" label="编码" width="80" />
            <el-table-column prop="productName" label="产品" min-width="150" />
            <el-table-column label="未发数量" width="100" align="right">
              <template #default="{ row }">{{ fmtQty(row.remainingQty) }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column label="本次发货" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="0.01" :max="row.remainingQty" :precision="2" size="small" style="width: 100%" />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">创建发货单</el-button>
      </template>
    </el-dialog>

    <el-drawer append-to-body v-model="detailDrawer" size="680px" title="发货单详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-no mono">{{ detail.shipmentNo }}</div>
              <div class="muted" style="margin-top: 4px">{{ detail.customerName }} · {{ detail.orderNo }}</div>
            </div>
            <StatusTag :status="detail.status" />
          </div>
          <div class="detail-grid">
            <div class="detail-item"><div class="label">承运商</div><div class="value">{{ detail.carrier || '-' }}</div></div>
            <div class="detail-item"><div class="label">运单号</div><div class="value mono">{{ detail.trackingNo || '-' }}</div></div>
            <div class="detail-item"><div class="label">创建时间</div><div class="value">{{ detail.createdAt }}</div></div>
            <div class="detail-item"><div class="label">发货时间</div><div class="value">{{ detail.shippedAt || '-' }}</div></div>
            <div class="detail-item"><div class="label">签收时间</div><div class="value">{{ detail.signedAt || '-' }}</div></div>
            <div class="detail-item" style="grid-column: span 3">
              <div class="label">收货地址</div><div class="value">{{ detail.address || '-' }}</div>
            </div>
          </div>
          <div class="drawer-section">
            <h4>发货明细</h4>
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
</style>
