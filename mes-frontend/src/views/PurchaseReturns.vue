<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../api'
import StatusTag from '../components/StatusTag.vue'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import { useTableView } from '../composables/useTableView'

const route = useRoute()
const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const status = ref('')

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'purchase.returns',
  columns: [
    { key: 'returnNo', label: '退货单号', width: 170 },
    { key: 'purchaseOrderNo', label: '采购单', width: 160 },
    { key: 'inboundNo', label: '入库单', width: 160 },
    { key: 'supplier', label: '供应商', width: 170, flex: true, ellipsis: true, prop: 'supplierName' },
    { key: 'warehouse', label: '仓库', width: 110, prop: 'warehouseName' },
    { key: 'returnDate', label: '退货日期', width: 110 },
    { key: 'totalQty', label: '退货数量', width: 100, align: 'right' },
    { key: 'totalAmount', label: '退货金额', width: 120, align: 'right' },
    { key: 'status', label: '状态', width: 100 },
    { key: 'redInvoiceNo', label: '红字发票', width: 170 },
    { key: 'reason', label: '退货原因', width: 180, flex: true, ellipsis: true },
    { key: 'createdBy', label: '创建人', width: 90 },
    { key: 'actions', label: '操作', width: 230, fixed: 'right', prop: '' }
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
const inbounds = ref<any[]>([])
const lines = ref<any[]>([])
const form = reactive<any>({
  inboundOrderId: null,
  returnDate: '',
  reason: '',
  items: []
})

const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/purchase-returns', {
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

const loadInbounds = async (search = '') => {
  inbounds.value = await api.get('/purchase-returns/returnable-inbounds', { keyword: search })
}

const today = () => {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

const openCreate = async () => {
  Object.assign(form, { inboundOrderId: null, returnDate: today(), reason: '', items: [] })
  lines.value = []
  await loadInbounds()
  dialog.value = true
  const preset = Number(route.query.inbound || 0)
  if (preset) {
    form.inboundOrderId = preset
    await pickInbound()
  }
}

const pickInbound = async () => {
  if (!form.inboundOrderId) {
    return
  }
  lines.value = await api.get(`/purchase-returns/inbound-orders/${form.inboundOrderId}/returnable`)
  form.items = lines.value.map((line: any) => ({
    inboundItemId: line.inboundItemId,
    productName: line.productName,
    productCode: line.productCode,
    unit: line.unit,
    batchNo: line.batchNo || '',
    price: Number(line.price || 0),
    available: Number(line.availableQty || 0),
    qty: 0
  }))
}

const lineAmount = (line: any) => Number(line.qty || 0) * Number(line.price || 0)

const totalAmount = () =>
  form.items.reduce((sum: number, item: any) => sum + lineAmount(item), 0)

const save = async () => {
  if (!form.inboundOrderId) {
    ElMessage.warning('请选择要退货的采购入库单')
    return
  }
  const picked = form.items.filter((item: any) => Number(item.qty || 0) > 0)
  if (!picked.length) {
    ElMessage.warning('请至少填写一条退货数量')
    return
  }
  if (picked.some((item: any) => Number(item.qty) > Number(item.available))) {
    ElMessage.warning('退货数量不能超过可退数量')
    return
  }
  if (!form.reason || form.reason.trim().length < 2) {
    ElMessage.warning('请填写退货原因')
    return
  }
  saving.value = true
  try {
    await api.post('/purchase-returns', {
      inboundOrderId: form.inboundOrderId,
      returnDate: form.returnDate || null,
      reason: form.reason,
      items: picked.map((item: any) => ({
        inboundItemId: item.inboundItemId,
        qty: Number(item.qty),
        batchNo: item.batchNo || null
      }))
    })
    ElMessage.success('退货单已保存为草稿，确认后扣减库存')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const complete = async (row: any) => {
  await ElMessageBox.confirm(
    `提交退货单 ${row.returnNo}？提交后会扣减库存、回退采购实收数量，已开票部分自动开红字发票红冲。`,
    '提交退货',
    { type: 'warning' }
  )
  const result = await api.post(`/purchase-returns/${row.id}/complete`)
  ElMessage.success(
    result && Number(result.redInvoiceCount) > 0
      ? `退货已提交，同步生成 ${result.redInvoiceCount} 张红字发票`
      : '退货已提交'
  )
  load()
}

const cancel = async (row: any) => {
  await ElMessageBox.confirm(`确认取消退货单 ${row.returnNo}？`, '取消确认', { type: 'warning' })
  await api.post(`/purchase-returns/${row.id}/cancel`)
  ElMessage.success('退货单已取消')
  load()
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(`确认删除草稿退货单 ${row.returnNo}？`, '删除确认', { type: 'warning' })
  await api.delete(`/purchase-returns/${row.id}`)
  ElMessage.success('已删除')
  load()
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await api.get(`/purchase-returns/${row.id}`)
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
  if (route.query.inbound) {
    openCreate()
  }
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">采购退货</h2>
        <p class="page-sub">从已完成的采购入库单退货，扣库存、回退实收、冲减暂估应付</p>
      </div>
      <el-button v-perm="'purchase:return'" type="primary" @click="openCreate">新建退货单</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="退货单号 / 采购单 / 入库单 / 供应商"
          clearable
          style="width: 280px"
          @keyup.enter="load"
        />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" plain @click="load">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/purchase-returns" export-name="采购退货" @applied="reload" />
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
          <template #cell-purchaseOrderNo="{ row }"><span class="mono">{{ row.purchaseOrderNo }}</span></template>
          <template #cell-inboundNo="{ row }"><span class="mono">{{ row.inboundNo }}</span></template>
          <template #cell-totalQty="{ row }"><span class="num">{{ fmtQty(row.totalQty) }}</span></template>
          <template #cell-totalAmount="{ row }"><span class="num">¥{{ fmtMoney(row.totalAmount) }}</span></template>
          <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
          <template #cell-redInvoiceNo="{ row }">
            <span v-if="row.redInvoiceNo" class="mono">{{ row.redInvoiceNo }}</span>
            <span v-else class="muted">-</span>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <template v-if="row.status === 'DRAFT'">
              <el-button v-perm="'purchase:return'" link type="success" @click="complete(row)">提交退货</el-button>
              <el-button v-perm="'purchase:return'" link type="warning" @click="cancel(row)">取消</el-button>
              <el-button v-perm="'purchase:return'" link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </template>
        </DataTable>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" title="新建采购退货单" width="920px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="采购入库单" required class="full">
            <el-select
              v-model="form.inboundOrderId"
              filterable
              remote
              :remote-method="loadInbounds"
              placeholder="只列出已完成、还有可退数量的采购入库单"
              style="width: 100%"
              @change="pickInbound"
            >
              <el-option
                v-for="item in inbounds"
                :key="item.inboundOrderId"
                :label="`${item.inboundNo} · ${item.purchaseOrderNo} · ${item.supplierName}`"
                :value="item.inboundOrderId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="退货日期">
            <el-input v-model="form.returnDate" placeholder="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="退货原因" required>
            <el-input v-model="form.reason" maxlength="120" placeholder="来料不合格 / 数量多发 / 规格错误" />
          </el-form-item>
        </div>

        <div class="drawer-section">
          <h4>
            <span>退货明细</span>
            <small class="muted">按先进先出扣减库存，批次信息仅作记录</small>
          </h4>
          <el-table :data="form.items" size="small" empty-text="请先选择入库单">
            <el-table-column prop="productCode" label="编码" width="90" />
            <el-table-column prop="productName" label="物料" min-width="150" />
            <el-table-column prop="batchNo" label="入库批次" width="130">
              <template #default="{ row }">{{ row.batchNo || '-' }}</template>
            </el-table-column>
            <el-table-column label="入库数量" width="100" align="right">
              <template #default="{ row }">
                {{ fmtQty(Number(row.available || 0) + Number(row.qty || 0)) }}
              </template>
            </el-table-column>
            <el-table-column label="可退数量" width="100" align="right">
              <template #default="{ row }"><span class="num">{{ fmtQty(row.available) }}</span></template>
            </el-table-column>
            <el-table-column label="本次退货" width="130">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.qty"
                  :min="0"
                  :max="Number(row.available || 0)"
                  :precision="2"
                  size="small"
                  style="width: 100%"
                />
              </template>
            </el-table-column>
            <el-table-column label="单价" width="100" align="right">
              <template #default="{ row }"><span class="num">¥{{ fmtMoney(row.price) }}</span></template>
            </el-table-column>
            <el-table-column label="金额" width="110" align="right">
              <template #default="{ row }"><span class="num">¥{{ fmtMoney(lineAmount(row)) }}</span></template>
            </el-table-column>
          </el-table>

          <div class="order-total">
            <span>退货金额合计</span>
            <b class="num">¥{{ fmtMoney(totalAmount()) }}</b>
          </div>
          <div class="hint">
            提交退货后会同时做四件事：扣减仓库库存、把采购单实收数量退回、冲减未开票暂估，
            已经开票匹配的部分自动开红字发票红冲；已付款超出红冲后应付的差额转为「待供应商退款」，
            到「发票与应付」里登记退款即可。
          </div>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-drawer append-to-body v-model="detailDrawer" size="760px" title="采购退货详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-no mono">{{ detail.returnNo }}</div>
              <div class="muted" style="margin-top: 4px">
                {{ detail.supplierName }} · {{ detail.warehouseName }}
              </div>
            </div>
            <StatusTag :status="detail.status" />
          </div>

          <div class="detail-grid">
            <div class="detail-item"><div class="label">采购单</div><div class="value mono">{{ detail.purchaseOrderNo }}</div></div>
            <div class="detail-item"><div class="label">入库单</div><div class="value mono">{{ detail.inboundNo }}</div></div>
            <div class="detail-item"><div class="label">退货日期</div><div class="value">{{ detail.returnDate }}</div></div>
            <div class="detail-item"><div class="label">退货数量</div><div class="value num">{{ fmtQty(detail.totalQty) }}</div></div>
            <div class="detail-item"><div class="label">退货金额</div><div class="value num">¥{{ fmtMoney(detail.totalAmount) }}</div></div>
            <div class="detail-item" v-if="detail.redInvoiceNo">
              <div class="label">红字发票</div>
              <div class="value mono">{{ detail.redInvoiceNo }}</div>
            </div>
            <div class="detail-item"><div class="label">创建人</div><div class="value">{{ detail.createdBy || '-' }}</div></div>
            <div class="detail-item"><div class="label">创建时间</div><div class="value">{{ detail.createdAt }}</div></div>
            <div class="detail-item"><div class="label">提交时间</div><div class="value">{{ detail.completedAt || '-' }}</div></div>
            <div class="detail-item"><div class="label">提交人</div><div class="value">{{ detail.completedBy || '-' }}</div></div>
            <div class="detail-item" style="grid-column: span 3">
              <div class="label">退货原因</div>
              <div class="value">{{ detail.reason }}</div>
            </div>
          </div>

          <div class="drawer-section">
            <h4>退货明细</h4>
            <el-table :data="detail.items" size="small">
              <el-table-column prop="productCode" label="编码" width="90" />
              <el-table-column prop="productName" label="物料" min-width="150" />
              <el-table-column prop="spec" label="规格" min-width="110" />
              <el-table-column label="数量" width="100" align="right">
                <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
              </el-table-column>
              <el-table-column prop="batchNo" label="批次" width="130">
                <template #default="{ row }">{{ row.batchNo || '-' }}</template>
              </el-table-column>
              <el-table-column label="单价" width="100" align="right">
                <template #default="{ row }">¥{{ fmtMoney(row.price) }}</template>
              </el-table-column>
              <el-table-column label="金额" width="110" align="right">
                <template #default="{ row }">¥{{ fmtMoney(row.amount) }}</template>
              </el-table-column>
            </el-table>
          </div>

          <div v-if="(detail.redInvoices || []).length" class="drawer-section">
            <h4>红字发票</h4>
            <el-table :data="detail.redInvoices" size="small">
              <el-table-column prop="invoiceNo" label="红字发票号" width="200">
                <template #default="{ row }"><span class="mono">{{ row.invoiceNo }}</span></template>
              </el-table-column>
              <el-table-column prop="invoiceDate" label="开票日期" width="120" />
              <el-table-column label="红冲金额" width="130" align="right">
                <template #default="{ row }"><span class="num">¥{{ fmtMoney(row.grossAmount) }}</span></template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }"><StatusTag :status="row.status" /></template>
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

.hint {
  margin-top: 12px;
  padding: 10px 12px;
  background: #f0f6ff;
  border: 1px dashed #b2ccff;
  border-radius: 6px;
  color: #1d4ed8;
  font-size: 12px;
  line-height: 1.7;
}
</style>
