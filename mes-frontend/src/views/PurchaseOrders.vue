<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
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
const supplierFilter = ref<any>(null)
const summary = ref<any>({})

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'purchase.orders',
  columns: [
    { key: 'orderNo', label: '采购单号', width: 155, fixed: 'left' },
    { key: 'supplier', label: '供应商', width: 180 },
    { key: 'items', label: '物料', width: 95 },
    { key: 'progress', label: '到货进度', width: 130 },
    { key: 'amount', label: '金额', width: 120, align: 'right' },
    { key: 'dates', label: '下单 / 预计到货', width: 160 },
    { key: 'status', label: '状态', width: 100 },
    { key: 'actions', label: '操作', width: 190, fixed: 'right' }
  ],
  getFilters: () => ({
    keyword: keyword.value,
    status: status.value,
    supplierId: supplierFilter.value || ''
  }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    status.value = filters.status || ''
    supplierFilter.value = filters.supplierId || null
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
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive({
  supplierId: null as any,
  supplierLabel: '',
  warehouseId: null as any,
  warehouseLabel: '',
  orderDate: '',
  expectedDate: '',
  needInspection: true,
  remark: '',
  items: [] as any[]
})

const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const receiveDialog = ref(false)
const receiveLoading = ref(false)
const receiveOrder = ref<any>(null)
const receiveForm = reactive({
  needInspection: true,
  remark: '',
  items: [] as any[]
})

const statusMap: Record<string, { label: string; cls: string }> = {
  DRAFT: { label: '草稿', cls: 'info' },
  CONFIRMED: { label: '待到货', cls: 'primary' },
  PARTIAL: { label: '部分到货', cls: 'warning' },
  RECEIVED: { label: '已到货', cls: 'success' },
  CANCELLED: { label: '已取消', cls: 'info' }
}

const statusOf = (value: any) =>
  statusMap[String(value || '')] || { label: String(value || '-'), cls: 'info' }

const today = () => new Date().toISOString().slice(0, 10)

const totalAmount = computed(() =>
  form.items.reduce((sum, item) => sum + Number(item.qty || 0) * Number(item.price || 0), 0)
)

const load = async () => {
  loading.value = true
  try {
    const [data, sum] = await Promise.all([
      api.get('/purchase-orders', {
        keyword: keyword.value || undefined,
        status: status.value || undefined,
        supplierId: supplierFilter.value || undefined,
        page: page.value,
        size: size.value
      }),
      api.get('/purchase-orders/summary')
    ])
    rows.value = data?.records || []
    total.value = data?.total || 0
    summary.value = sum || {}
  } finally {
    loading.value = false
  }
}

const blankItem = () => ({ productId: null, qty: 1, price: 0, remark: '', unit: '', productCode: '', productName: '' })

const openCreate = () => {
  editingId.value = null
  Object.assign(form, {
    supplierId: null,
    supplierLabel: '',
    warehouseId: null,
    warehouseLabel: '',
    orderDate: today(),
    expectedDate: '',
    needInspection: true,
    remark: '',
    items: [blankItem()]
  })
  dialog.value = true
}

const openEdit = async (row: any) => {
  const data = await api.get(`/purchase-orders/${row.id}`)
  editingId.value = row.id
  Object.assign(form, {
    supplierId: data.supplierId,
    supplierLabel: data.supplierCode
      ? `${data.supplierCode} · ${data.supplierName}`
      : (data.supplierName || ''),
    warehouseId: data.warehouseId,
    warehouseLabel: data.warehouseName || '',
    orderDate: data.orderDate,
    expectedDate: data.expectedDate || '',
    needInspection: data.needInspection !== false,
    remark: data.remark || '',
    items: (data.items || []).map((item: any) => ({
      productId: item.productId,
      qty: Number(item.qty),
      price: Number(item.price),
      remark: item.remark || '',
      productCode: item.productCode || '',
      productName: item.productName || '',
      unit: item.unit || ''
    }))
  })
  dialog.value = true
}

const supplierChanged = (supplier: any) => {
  if (!supplier || !form.orderDate) return
  const date = new Date(form.orderDate)
  date.setDate(date.getDate() + Number(supplier.leadDays || 0))
  form.expectedDate = date.toISOString().slice(0, 10)
}

const productChanged = async (item: any, product: any) => {
  if (!product) return
  item.unit = product.unit || ''
  // 还没选供应商时先按物料的默认供应商带出，顺带把交期也按它的交期天数推一下
  if (!form.supplierId && product.defaultSupplierId) {
    const supplier = await api.get(`/suppliers/${product.defaultSupplierId}`)
    form.supplierId = supplier.id
    form.supplierLabel = supplier.code ? `${supplier.code} · ${supplier.name}` : supplier.name
    supplierChanged(supplier)
    ElMessage.info(`已带出默认供应商：${supplier.name}`)
  }
  if (!item.productId || !form.supplierId) return
  const reference = await api.get('/purchase-orders/last-price', {
    supplierId: form.supplierId,
    productId: item.productId
  })
  if (reference && Number(reference.price) > 0) {
    item.price = Number(reference.price)
  }
}

const addItem = () => form.items.push(blankItem())
const removeItem = (index: number) => {
  if (form.items.length <= 1) {
    ElMessage.warning('至少保留一条采购明细')
    return
  }
  form.items.splice(index, 1)
}

const submit = async () => {
  if (!form.supplierId) {
    ElMessage.warning('请选择供应商')
    return
  }
  if (!form.warehouseId) {
    ElMessage.warning('请选择收货仓库')
    return
  }
  for (const item of form.items) {
    if (!item.productId) {
      ElMessage.warning('请为每条明细选择物料')
      return
    }
    if (!item.qty || Number(item.qty) <= 0) {
      ElMessage.warning('采购数量必须大于 0')
      return
    }
  }
  saving.value = true
  try {
    const payload = {
      supplierId: form.supplierId,
      warehouseId: form.warehouseId,
      orderDate: form.orderDate || undefined,
      expectedDate: form.expectedDate || undefined,
      needInspection: form.needInspection,
      remark: form.remark,
      items: form.items.map((item) => ({
        productId: item.productId,
        qty: Number(item.qty),
        price: Number(item.price || 0),
        remark: item.remark
      }))
    }
    if (editingId.value) {
      await api.put(`/purchase-orders/${editingId.value}`, payload)
      ElMessage.success('已保存')
    } else {
      await api.post('/purchase-orders', payload)
      ElMessage.success('采购单已创建，确认后即可收货')
    }
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const confirmOrder = async (row: any) => {
  await ElMessageBox.confirm(
    `确认采购单 ${row.orderNo}？确认后才能收货，供应商「${row.supplierName}」。`,
    '确认采购单',
    { type: 'warning' }
  )
  await api.post(`/purchase-orders/${row.id}/confirm`)
  ElMessage.success('采购单已确认')
  load()
}

const cancelOrder = async (row: any) => {
  await ElMessageBox.confirm(`取消采购单 ${row.orderNo}？已到货或还有未完成入库单时不能取消。`, '取消采购单', {
    type: 'warning'
  })
  await api.post(`/purchase-orders/${row.id}/cancel`)
  ElMessage.success('已取消')
  load()
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(`删草稿采购单 ${row.orderNo}？`, '删除确认', { type: 'warning' })
  await api.delete(`/purchase-orders/${row.id}`)
  ElMessage.success('已删除')
  load()
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await api.get(`/purchase-orders/${row.id}`)
  } finally {
    detailLoading.value = false
  }
}

const openReceive = async (row: any) => {
  const data = await api.get(`/purchase-orders/${row.id}`)
  receiveOrder.value = data
  receiveForm.needInspection = data.needInspection !== false
  receiveForm.remark = ''
  receiveForm.items = (data.items || [])
    .filter((item: any) => Number(item.availableQty) > 0)
    .map((item: any) => ({
      itemId: item.id,
      productCode: item.productCode,
      productName: item.productName,
      unit: item.unit,
      availableQty: Number(item.availableQty),
      qty: Number(item.availableQty),
      batchNo: `${data.orderNo}-${item.id}`
    }))
  if (!receiveForm.items.length) {
    ElMessage.warning('没有待收明细，可能已全部收货或有在途入库单')
    return
  }
  receiveDialog.value = true
}

const submitReceive = async () => {
  if (!receiveOrder.value) return
  for (const item of receiveForm.items) {
    if (Number(item.qty) <= 0) {
      ElMessage.warning('收货数量必须大于 0')
      return
    }
    if (Number(item.qty) > Number(item.availableQty)) {
      ElMessage.warning(`${item.productName} 收货数量超过待收数量`)
      return
    }
  }
  receiveLoading.value = true
  try {
    const result = await api.post(`/purchase-orders/${receiveOrder.value.id}/receive`, {
      needInspection: receiveForm.needInspection,
      remark: receiveForm.remark,
      items: receiveForm.items.map((item) => ({
        itemId: item.itemId,
        qty: Number(item.qty),
        batchNo: item.batchNo
      }))
    })
    ElMessage.success(
      `已生成入库单 ${result.inboundNo}，${result.needInspection ? '待来料检验合格后入库' : '提交入库单即完成入库'}`
    )
    receiveDialog.value = false
    load()
  } finally {
    receiveLoading.value = false
  }
}

const reset = () => {
  keyword.value = ''
  status.value = ''
  supplierFilter.value = null
  page.value = 1
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
        <h2 class="page-title">采购订单</h2>
        <p class="page-sub">
          草稿建单 → 确认 → 收货生成入库单 → 入库完成回写实收；在途数量会从待收里扣掉，避免重复收货
        </p>
      </div>
      <el-button v-perm="'purchase:create'" type="primary" @click="openCreate">新建采购单</el-button>
    </div>

    <div class="po-strip">
      <div class="po-item">
        <span>草稿</span>
        <b>{{ summary.draft || 0 }}</b>
      </div>
      <div class="po-item">
        <span>待到货</span>
        <b class="primary-text">{{ summary.open || 0 }}</b>
      </div>
      <div class="po-item">
        <span>已到货</span>
        <b>{{ summary.received || 0 }}</b>
      </div>
      <div class="po-item">
        <span>在途金额</span>
        <b>{{ fmtMoney(summary.openAmount) }}</b>
      </div>
      <div class="po-item">
        <span>本月采购额</span>
        <b>{{ fmtMoney(summary.monthAmount) }}</b>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="采购单号 / 供应商 / 备注"
          clearable
          style="width: 230px"
          @keyup.enter="reload"
        />
        <ReferenceSelect v-model="supplierFilter" url="/suppliers/options"
                         placeholder="全部供应商" style="width: 180px" @select="reload" />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 130px" @change="reload">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待到货" value="CONFIRMED" />
          <el-option label="部分到货" value="PARTIAL" />
          <el-option label="已到货" value="RECEIVED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" plain @click="reload">查询</el-button>
        <el-button plain @click="reset">重置</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/purchase-orders" export-name="采购订单" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 张采购单</span>
      </div>

      <div class="table-wrap">
        <DataTable :table="table" :data="rows" :loading="loading">
          <template #cell-orderNo="{ row }">
            <div class="mono">{{ row.orderNo }}</div>
            <div v-if="row.requisitionNo" class="muted mono" style="font-size: 11px">申请 {{ row.requisitionNo }}</div>
          </template>
          <template #cell-supplier="{ row }">
            <div>{{ row.supplierName }}</div>
            <div class="muted mono" style="font-size: 11px">{{ row.supplierCode }}</div>
          </template>
          <template #cell-items="{ row }">
            <div>{{ row.itemCount }} 项</div>
            <div class="muted" style="font-size: 11px">{{ fmtQty(row.totalQty) }}</div>
          </template>
          <template #cell-progress="{ row }">
            <div class="mono">{{ fmtQty(row.receivedQty) }} / {{ fmtQty(row.totalQty) }}</div>
            <div class="muted" style="font-size: 11px">
              <template v-if="row.openInboundCount">在途 {{ row.openInboundCount }} 张入库单</template>
              <template v-else>无在途</template>
            </div>
          </template>
          <template #cell-amount="{ row }"><span class="mono">{{ fmtMoney(row.totalAmount) }}</span></template>
          <template #cell-dates="{ row }">
            <div>{{ row.orderDate }}</div>
            <div class="muted" style="font-size: 11px">预计 {{ row.expectedDate || '-' }}</div>
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
              v-perm="'purchase:confirm'"
              link
              type="success"
              @click="confirmOrder(row)"
            >
              确认
            </el-button>
            <el-button
              v-if="row.status === 'CONFIRMED' || row.status === 'PARTIAL'"
              v-perm="'purchase:receive'"
              link
              type="primary"
              @click="openReceive(row)"
            >
              收货
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              v-perm="'purchase:edit'"
              link
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-dropdown v-if="row.status === 'DRAFT' || row.status === 'CONFIRMED' || row.status === 'PARTIAL'">
              <el-button link>更多</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="row.status !== 'DRAFT'" v-perm="'purchase:cancel'" @click="cancelOrder(row)">
                    取消采购单
                  </el-dropdown-item>
                  <el-dropdown-item v-if="row.status === 'DRAFT'" v-perm="'purchase:delete'" @click="remove(row)">
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

    <el-dialog draggable append-to-body v-model="dialog" :title="editingId ? '编辑采购单' : '新建采购单'" width="900px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="供应商" required>
            <ReferenceSelect v-model="form.supplierId" url="/suppliers/options"
                             :initial-label="form.supplierLabel"
                             placeholder="输入供应商编码或名称搜索"
                             @select="supplierChanged" />
          </el-form-item>
          <el-form-item label="收货仓库" required>
            <ReferenceSelect v-model="form.warehouseId" url="/options/warehouses"
                             :initial-label="form.warehouseLabel"
                             placeholder="输入仓库编码或名称搜索" />
          </el-form-item>
          <el-form-item label="下单日期">
            <el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="预计到货">
            <el-date-picker v-model="form.expectedDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="到货检验">
            <el-switch v-model="form.needInspection" active-text="需来料检验" inactive-text="免检" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.remark" placeholder="如：十月原料补库" />
          </el-form-item>
        </div>

        <div class="item-head">
          <b>采购明细</b>
          <el-button size="small" plain @click="addItem">添加一行</el-button>
          <div class="spacer"></div>
          <span class="muted">合计 <b class="mono">{{ fmtMoney(totalAmount) }}</b></span>
        </div>

        <el-table :data="form.items" size="small">
          <el-table-column label="物料" min-width="220">
            <template #default="{ row }">
              <ReferenceSelect v-model="row.productId" url="/options/products"
                               :initial-label="row.productCode ? `${row.productCode} · ${row.productName}` : row.productName"
                               placeholder="输入物料编码或名称搜索"
                               @select="(product) => productChanged(row, product)" />
            </template>
          </el-table-column>
          <el-table-column label="单位" width="70">
            <template #default="{ row }">{{ row.unit || '-' }}</template>
          </el-table-column>
          <el-table-column label="数量" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="0.0001" :precision="2" :controls="false" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="单价" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.price" :min="0" :precision="2" :controls="false" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">
              <span class="mono">{{ fmtMoney(Number(row.qty || 0) * Number(row.price || 0)) }}</span>
            </template>
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
        <el-button type="primary" :loading="saving" @click="submit">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" size="860px" title="采购单详情" append-to-body>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <span class="mono strong">{{ detail.orderNo }}</span>
            <span class="status-tag" :class="statusOf(detail.status).cls">
              <i></i>{{ statusOf(detail.status).label }}
            </span>
            <span class="muted">{{ detail.supplierName }} · {{ detail.warehouseName }}</span>
            <span v-if="detail.needInspection" class="muted">需来料检验</span>
            <span v-else class="muted">免检</span>
            <span v-if="detail.requisitionNo" class="muted">来源申请 {{ detail.requisitionNo }}</span>
          </div>

          <div class="detail-grid">
            <div><span>下单日期</span><b>{{ detail.orderDate }}</b></div>
            <div><span>预计到货</span><b>{{ detail.expectedDate || '-' }}</b></div>
            <div><span>采购金额</span><b class="mono">{{ fmtMoney(detail.totalAmount) }}</b></div>
            <div><span>账期</span><b>{{ detail.paymentTerm || '-' }}</b></div>
            <div><span>创建人</span><b>{{ detail.createdBy || '-' }}</b></div>
            <div><span>确认人</span><b>{{ detail.confirmedBy || '-' }}</b></div>
          </div>

          <div class="section-title">采购明细</div>
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
            <el-table-column label="已收" width="80" align="right">
              <template #default="{ row }"><span class="mono">{{ fmtQty(row.receivedQty) }}</span></template>
            </el-table-column>
            <el-table-column label="在途" width="80" align="right">
              <template #default="{ row }"><span class="mono muted">{{ fmtQty(row.receivingQty) }}</span></template>
            </el-table-column>
            <el-table-column label="待收" width="80" align="right">
              <template #default="{ row }">
                <span :class="Number(row.availableQty) > 0 ? 'primary-text' : 'muted'">
                  {{ fmtQty(row.availableQty) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="单价" width="100" align="right">
              <template #default="{ row }"><span class="mono">{{ fmtMoney(row.price) }}</span></template>
            </el-table-column>
            <el-table-column label="金额" width="110" align="right">
              <template #default="{ row }"><span class="mono">{{ fmtMoney(row.amount) }}</span></template>
            </el-table-column>
          </el-table>

          <div class="section-title">关联入库单</div>
          <el-table :data="detail.inboundOrders || []" size="small" empty-text="还没有收货记录">
            <el-table-column label="入库单号" width="170">
              <template #default="{ row }"><span class="mono">{{ row.inboundNo }}</span></template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <span class="status-tag" :class="row.status === 'COMPLETED' ? 'success' : row.status === 'CANCELLED' ? 'info' : 'warning'">
                  <i></i>{{ row.status === 'COMPLETED' ? '已完成' : row.status === 'CANCELLED' ? '已取消' : row.status === 'PENDING_QC' ? '待检验' : '草稿' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="100" align="right">
              <template #default="{ row }">{{ fmtQty(row.totalQty) }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="150" />
            <el-table-column prop="completedAt" label="完成时间" width="150" />
          </el-table>

          <p class="detail-note">
            收货只是生成入库单，真正入库以入库单完成（或来料检验合格）为准；入库完成时这里会自动回写已收数量。
          </p>
        </template>
      </div>
    </el-drawer>

    <el-dialog draggable append-to-body v-model="receiveDialog" title="采购收货" width="820px">
      <div v-if="receiveOrder" class="receive-head">
        <b class="mono">{{ receiveOrder.orderNo }}</b>
        <span class="muted">{{ receiveOrder.supplierName }} · 入库到 {{ receiveOrder.warehouseName }}</span>
      </div>
      <el-form label-position="top">
        <el-table :data="receiveForm.items" size="small">
          <el-table-column label="物料" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <div>{{ row.productName }}</div>
              <div class="muted mono" style="font-size: 11px">{{ row.productCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="待收" width="90" align="right">
            <template #default="{ row }">{{ fmtQty(row.availableQty) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="本次收货" width="150">
            <template #default="{ row }">
              <el-input-number
                v-model="row.qty"
                :min="0"
                :max="row.availableQty"
                :precision="2"
                :controls="false"
                style="width: 100%"
              />
            </template>
          </el-table-column>
          <el-table-column label="批次号" min-width="170">
            <template #default="{ row }">
              <el-input v-model="row.batchNo" size="small" placeholder="默认按采购单号-行号生成" />
            </template>
          </el-table-column>
        </el-table>
        <div class="receive-options">
          <el-switch v-model="receiveForm.needInspection" active-text="到货需来料检验" inactive-text="免检直入" />
          <el-input v-model="receiveForm.remark" placeholder="收货备注（可选）" style="width: 320px" />
        </div>
        <p class="receive-tip">
          确认后生成一张草稿入库单，去「仓储管理 → 入库管理」提交；免检的提交即入库，需检验的先走「质量检验 → 来料检验」。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="receiveDialog = false">取消</el-button>
        <el-button type="primary" :loading="receiveLoading" @click="submitReceive">确认收货</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.po-strip {
  display: flex;
  align-items: flex-start;
  gap: 38px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 15px 20px;
  margin-bottom: 14px;
  box-shadow: var(--shadow);
  flex-wrap: wrap;
}

.po-item span {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.po-item b {
  display: block;
  margin-top: 6px;
  font-size: 20px;
  font-family: Bahnschrift, monospace;
}

.primary-text {
  color: #2563eb;
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
  margin-bottom: 6px;
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

.receive-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.receive-options {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-top: 12px;
}

.receive-tip {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.7;
}
</style>
