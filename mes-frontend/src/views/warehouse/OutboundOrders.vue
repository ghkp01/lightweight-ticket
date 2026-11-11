<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtQty } from '../../api'
import StatusTag from '../../components/StatusTag.vue'
import DataTable from '../../components/DataTable.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import ReferenceSelect from '../../components/ReferenceSelect.vue'
import { useTableView } from '../../composables/useTableView'

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const status = ref('')
const type = ref('')

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'warehouse.outbound',
  columns: [
    { key: 'outboundNo', label: '出库单号', width: 160, fixed: 'left' },
    { key: 'type', label: '类型', width: 100 },
    { key: 'warehouse', label: '仓库', width: 110 },
    { key: 'itemCount', label: '明细数', width: 85, align: 'right' },
    { key: 'totalQty', label: '合计数量', width: 100, align: 'right' },
    { key: 'status', label: '状态', width: 100 },
    { key: 'workOrderNo', label: '关联工单', width: 150, prop: 'workOrderNo' },
    { key: 'remark', label: '备注', width: 160, flex: true, ellipsis: true },
    { key: 'createdBy', label: '创建人', width: 90 },
    { key: 'createdAt', label: '创建时间', width: 140 },
    { key: 'actions', label: '操作', width: 300, fixed: 'right' }
  ],
  getFilters: () => ({ keyword: keyword.value, type: type.value, status: status.value }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    type.value = filters.type || ''
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
const drawer = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive<any>({
  type: 'MANUAL',
  warehouseId: null,
  warehouseLabel: '',
  workOrderId: null,
  remark: '',
  items: []
})

const workOrders = ref<any[]>([])

const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const typeLabel: Record<string, string> = {
  MANUAL: '手工出库',
  SCRAP: '报废出库',
  OTHER: '其他出库',
  ISSUE_OTHER: '车间领用'
}

const totalQty = computed(() =>
  form.items.reduce((sum: number, item: any) => sum + Number(item.qty || 0), 0)
)

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/outbound-orders', {
      keyword: keyword.value,
      status: status.value,
      type: type.value,
      page: page.value,
      size: size.value
    })
    rows.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

const loadWorkOrders = async () => {
  if (workOrders.value.length) return
  const [released, inProgress] = await Promise.all([
    api.get('/work-orders', { status: 'RELEASED', page: 1, size: 100 }),
    api.get('/work-orders', { status: 'IN_PROGRESS', page: 1, size: 100 })
  ])
  workOrders.value = [...(released?.records || []), ...(inProgress?.records || [])]
}

const openCreate = async () => {
  editingId.value = null
  // 默认落到第一个原料仓，没有原料仓时取任意一个，不再整表加载仓库
  let options = await api.get('/options/warehouses', { type: 'RAW', limit: 1 })
  if (!options?.length) options = await api.get('/options/warehouses', { limit: 1 })
  const first = options?.[0] || null
  Object.assign(form, {
    type: 'MANUAL',
    warehouseId: first ? first.id : null,
    warehouseLabel: first ? (first.code ? `${first.code} · ${first.name}` : first.name) : '',
    workOrderId: null,
    remark: '',
    items: [{ productId: null, productCode: '', productName: '', qty: 1 }]
  })
  loadWorkOrders()
  drawer.value = true
}

const openEdit = async (row: any) => {
  const data = await api.get(`/outbound-orders/${row.id}`)
  editingId.value = row.id
  Object.assign(form, {
    type: data.type,
    warehouseId: data.warehouseId,
    warehouseLabel: data.warehouseName || '',
    workOrderId: data.workOrderId || null,
    remark: data.remark || '',
    items: data.items.map((item: any) => ({
      productId: item.productId,
      productCode: item.productCode || '',
      productName: item.productName || '',
      qty: Number(item.qty)
    }))
  })
  loadWorkOrders()
  drawer.value = true
}

const addItem = () => {
  form.items.push({ productId: null, productCode: '', productName: '', qty: 1 })
}

const removeItem = (index: number) => {
  form.items.splice(index, 1)
}

const submit = async () => {
  if (!form.warehouseId) {
    ElMessage.warning('请选择出库仓库')
    return
  }
  if (form.type === 'ISSUE_OTHER' && !form.workOrderId) {
    ElMessage.warning('车间领用必须选择工单')
    return
  }
  if (!form.items.length || form.items.some((item: any) => !item.productId || !item.qty || item.qty <= 0)) {
    ElMessage.warning('请完整填写出库明细')
    return
  }
  saving.value = true
  try {
    const payload = {
      type: form.type,
      warehouseId: form.warehouseId,
      workOrderId: form.workOrderId || null,
      remark: form.remark,
      items: form.items.map((item: any) => ({ productId: item.productId, qty: Number(item.qty) }))
    }
    if (editingId.value) {
      await api.put(`/outbound-orders/${editingId.value}`, payload)
    } else {
      await api.post('/outbound-orders', payload)
    }
    ElMessage.success('出库单已保存')
    drawer.value = false
    load()
  } finally {
    saving.value = false
  }
}

const completeOrder = async (row: any) => {
  await ElMessageBox.confirm(
    `完成出库单 ${row.outboundNo}？系统将按明细扣减 ${row.warehouseName} 库存并生成流水。`,
    '完成出库'
  )
  await api.post(`/outbound-orders/${row.id}/complete`)
  ElMessage.success('出库完成，库存已扣减')
  load()
}

const cancelOrder = async (row: any) => {
  await ElMessageBox.confirm(`确认取消出库单 ${row.outboundNo}？`, '取消确认', { type: 'warning' })
  await api.post(`/outbound-orders/${row.id}/cancel`)
  ElMessage.success('出库单已取消')
  load()
}

const removeOrder = async (row: any) => {
  await ElMessageBox.confirm(`确认删除草稿出库单 ${row.outboundNo}？`, '删除确认', { type: 'warning' })
  await api.delete(`/outbound-orders/${row.id}`)
  ElMessage.success('已删除')
  load()
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await api.get(`/outbound-orders/${row.id}`)
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
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">出库管理</h2>
        <p class="page-sub">按单出库，完成即扣减库存，报废与领用都可追溯</p>
      </div>
      <el-button v-perm="'warehouse:outbound'" type="primary" @click="openCreate">新建出库单</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="出库单号" clearable style="width: 230px" @keyup.enter="load" />
        <el-select v-model="type" placeholder="全部类型" clearable style="width: 140px" @change="load">
          <el-option label="手工出库" value="MANUAL" />
          <el-option label="报废出库" value="SCRAP" />
          <el-option label="其他出库" value="OTHER" />
          <el-option label="车间领用" value="ISSUE_OTHER" />
        </el-select>
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" plain @click="load">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/outbound-orders" export-name="出库单" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 张出库单</span>
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
          <template #cell-outboundNo="{ row }"><span class="mono">{{ row.outboundNo }}</span></template>
          <template #cell-type="{ row }">{{ typeLabel[row.type] || row.type }}</template>
          <template #cell-itemCount="{ row }">{{ row.itemCount }}</template>
          <template #cell-totalQty="{ row }"><span class="num">{{ fmtQty(row.totalQty) }}</span></template>
          <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <template v-if="row.status === 'DRAFT'">
              <el-button v-perm="'warehouse:outbound'" link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-perm="'warehouse:outbound'" link type="success" @click="completeOrder(row)">完成出库</el-button>
              <el-button v-perm="'warehouse:outbound'" link type="danger" @click="cancelOrder(row)">取消</el-button>
              <el-button v-perm="'warehouse:outbound'" link type="danger" @click="removeOrder(row)">删除</el-button>
            </template>
          </template>
        </DataTable>
      </div>
    </div>

    <el-drawer append-to-body v-model="drawer" size="800px" :title="editingId ? '编辑出库单' : '新建出库单'">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="出库类型" required>
            <el-select v-model="form.type" style="width: 100%">
              <el-option label="手工出库" value="MANUAL" />
              <el-option label="报废出库" value="SCRAP" />
              <el-option label="其他出库" value="OTHER" />
              <el-option label="车间领用（非 BOM 物料）" value="ISSUE_OTHER" />
            </el-select>
          </el-form-item>
          <el-form-item label="出库仓库" required>
            <ReferenceSelect v-model="form.warehouseId" url="/options/warehouses"
                             :initial-label="form.warehouseLabel"
                             placeholder="输入仓库编码或名称搜索" />
          </el-form-item>
          <el-form-item label="关联工单" :required="form.type === 'ISSUE_OTHER'" class="full">
            <el-select
              v-model="form.workOrderId"
              filterable
              clearable
              placeholder="车间领用非 BOM 物料时必选，用于归集工单成本"
              style="width: 100%"
            >
              <el-option
                v-for="item in workOrders"
                :key="item.id"
                :label="`${item.workNo} · ${item.productName}`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" placeholder="领用部门、报废原因等" />
          </el-form-item>
        </div>

        <div class="drawer-section">
          <h4>
            <span>出库明细</span>
            <el-button size="small" @click="addItem">添加产品</el-button>
          </h4>
          <el-table :data="form.items" size="small">
            <el-table-column label="产品" min-width="260">
              <template #default="{ row }">
                <ReferenceSelect v-model="row.productId" url="/options/products"
                                 :initial-label="row.productCode ? `${row.productCode} · ${row.productName}` : row.productName"
                                 placeholder="输入物料编码或名称搜索"
                                 size="small" />
              </template>
            </el-table-column>
            <el-table-column label="数量" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="0.01" :precision="2" size="small" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="70">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeItem($index)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="order-total">
            <span>合计数量</span>
            <b class="num">{{ fmtQty(totalQty) }}</b>
          </div>
          <div class="outbound-note">保存后为草稿，点击「完成出库」才会扣减库存；库存不足时整单失败并回滚。</div>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存出库单</el-button>
      </template>
    </el-drawer>

    <el-drawer append-to-body v-model="detailDrawer" size="720px" title="出库单详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-no mono">{{ detail.outboundNo }}</div>
              <div class="muted" style="margin-top: 4px">
                {{ typeLabel[detail.type] || detail.type }} · {{ detail.warehouseName }}
              </div>
            </div>
            <StatusTag :status="detail.status" />
          </div>

          <div class="detail-grid">
            <div class="detail-item"><div class="label">合计数量</div><div class="value num">{{ fmtQty(detail.totalQty) }}</div></div>
            <div class="detail-item"><div class="label">创建人</div><div class="value">{{ detail.createdBy || '-' }}</div></div>
            <div class="detail-item"><div class="label">创建时间</div><div class="value">{{ detail.createdAt }}</div></div>
            <div class="detail-item"><div class="label">完成时间</div><div class="value">{{ detail.completedAt || '-' }}</div></div>
            <div class="detail-item" style="grid-column: span 2">
              <div class="label">备注</div>
              <div class="value">{{ detail.remark || '-' }}</div>
            </div>
          </div>

          <div class="drawer-section">
            <h4>出库明细</h4>
            <el-table :data="detail.items" size="small">
              <el-table-column prop="productCode" label="编码" width="90" />
              <el-table-column prop="productName" label="产品" min-width="150" />
              <el-table-column prop="spec" label="规格" min-width="110" />
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

.outbound-note {
  margin-top: 12px;
  padding: 10px 12px;
  background: #fffaf0;
  border: 1px dashed #fedf89;
  border-radius: 6px;
  color: #b54708;
  font-size: 12px;
  line-height: 1.7;
}
</style>
