<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../../api'
import StatusTag from '../../components/StatusTag.vue'
import DataTable from '../../components/DataTable.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import ReferenceSelect from '../../components/ReferenceSelect.vue'
import { useTableView } from '../../composables/useTableView'
import { useAuthStore } from '../../stores/auth'

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
const type = ref('')

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'warehouse.inbound',
  columns: [
    { key: 'inboundNo', label: '入库单号', width: 160, fixed: 'left' },
    { key: 'type', label: '类型', width: 100 },
    { key: 'source', label: '来源', width: 120 },
    { key: 'warehouse', label: '仓库', width: 110 },
    { key: 'purchaseOrderNo', label: '采购单', width: 150 },
    { key: 'supplier', label: '供应商', width: 160, flex: true, ellipsis: true },
    { key: 'itemCount', label: '明细数', width: 85, align: 'right' },
    { key: 'totalQty', label: '合计数量', width: 100, align: 'right' },
    { key: 'needInspection', label: '检验', width: 80 },
    { key: 'status', label: '状态', width: 100 },
    { key: 'createdBy', label: '创建人', width: 90 },
    { key: 'createdAt', label: '创建时间', width: 140 },
    { key: 'actions', label: '操作', width: 275, fixed: 'right' }
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
const locations = ref<any[]>([])
const summary = ref<any>({})

const drawer = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive<any>({
  type: 'OTHER',
  warehouseId: null,
  warehouseLabel: '',
  supplier: '',
  needInspection: true,
  remark: '',
  noOrderReason: '',
  items: []
})

const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const typeLabel: Record<string, string> = {
  PURCHASE: '采购入库',
  RETURN: '退货入库',
  OTHER: '其他入库'
}

const sourceLabel: Record<string, string> = {
  PO: '采购单收货',
  MANUAL: '无单入库',
  RETURN: '退货入库'
}

const totalQty = computed(() =>
  form.items.reduce((sum: number, item: any) => sum + Number(item.qty || 0), 0)
)

const canFreeInbound = computed(() => auth.can('warehouse:inbound_free'))

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/inbound-orders', {
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

const loadSummary = async () => {
  summary.value = await api.get('/inbound-orders/summary')
}

/** 库位下拉按当前仓库过滤并走服务端搜索，没有维护库位时允许手填 */
const loadLocations = async (keyword = '') => {
  if (!form.warehouseId) {
    locations.value = []
    return
  }
  locations.value = (await api.get('/locations/options', {
    warehouseId: form.warehouseId,
    keyword: keyword || undefined,
    limit: 20
  }).catch(() => [])) || []
}

const openCreate = async () => {
  editingId.value = null
  // 默认落到第一个原料仓，不再整表加载仓库
  const options = await api.get('/options/warehouses', { type: 'RAW', limit: 1 })
  const raw = options?.[0] || null
  Object.assign(form, {
    type: 'OTHER',
    warehouseId: raw ? raw.id : null,
    warehouseLabel: raw ? (raw.code ? `${raw.code} · ${raw.name}` : raw.name) : '',
    supplier: '',
    needInspection: true,
    remark: '',
    noOrderReason: '',
    items: [{ productId: null, productCode: '', productName: '', qty: 1, batchNo: '', unitPrice: 0, locationCode: '' }]
  })
  loadLocations()
  drawer.value = true
}

const openEdit = async (row: any) => {
  const data = await api.get(`/inbound-orders/${row.id}`)
  editingId.value = row.id
  Object.assign(form, {
    type: data.type,
    warehouseId: data.warehouseId,
    warehouseLabel: data.warehouseName || '',
    supplier: data.supplier || '',
    needInspection: data.needInspection,
    remark: data.remark || '',
    noOrderReason: data.noOrderReason || '',
    items: data.items.map((item: any) => ({
      productId: item.productId,
      productCode: item.productCode || '',
      productName: item.productName || '',
      qty: Number(item.qty),
      batchNo: item.batchNo || '',
      unitPrice: Number(item.unitPrice || 0),
      locationCode: item.locationCode || ''
    }))
  })
  loadLocations()
  drawer.value = true
}

const addItem = () => {
  form.items.push({ productId: null, productCode: '', productName: '', qty: 1, batchNo: '', unitPrice: 0, locationCode: '' })
}

const removeItem = (index: number) => {
  form.items.splice(index, 1)
}

const submit = async () => {
  if (!form.warehouseId) {
    ElMessage.warning('请选择入库仓库')
    return
  }
  if (!form.items.length || form.items.some((item: any) => !item.productId || !item.qty || item.qty <= 0)) {
    ElMessage.warning('请完整填写入库明细')
    return
  }
  if (!form.noOrderReason || form.noOrderReason.trim().length < 2) {
    ElMessage.warning('请填写无单入库的原因说明')
    return
  }
  saving.value = true
  try {
    const payload = {
      type: form.type,
      warehouseId: form.warehouseId,
      supplier: form.supplier,
      needInspection: form.needInspection,
      remark: form.remark,
      noOrderReason: form.noOrderReason,
      items: form.items.map((item: any) => ({
        productId: item.productId,
        qty: Number(item.qty),
        batchNo: item.batchNo || null,
        unitPrice: Number(item.unitPrice || 0),
        locationCode: item.locationCode || null
      }))
    }
    if (editingId.value) {
      await api.put(`/inbound-orders/${editingId.value}`, payload)
    } else {
      await api.post('/inbound-orders', payload)
    }
    ElMessage.success('入库单已保存')
    drawer.value = false
    load()
    loadSummary()
  } finally {
    saving.value = false
  }
}

const submitOrder = async (row: any) => {
  const tip = row.needInspection
    ? `提交入库单 ${row.inboundNo}？提交后进入待检状态，需来料检验合格后才入库。`
    : `提交入库单 ${row.inboundNo}？该单免检，提交后立即入库。`
  await ElMessageBox.confirm(tip, '提交确认')
  await api.post(`/inbound-orders/${row.id}/submit`)
  ElMessage.success(row.needInspection ? '已提交，等待来料检验' : '已提交并完成入库')
  load()
  loadSummary()
}

const cancelOrder = async (row: any) => {
  await ElMessageBox.confirm(`确认取消入库单 ${row.inboundNo}？`, '取消确认', { type: 'warning' })
  await api.post(`/inbound-orders/${row.id}/cancel`)
  ElMessage.success('入库单已取消')
  load()
  loadSummary()
}

const removeOrder = async (row: any) => {
  await ElMessageBox.confirm(`确认删除草稿入库单 ${row.inboundNo}？`, '删除确认', { type: 'warning' })
  await api.delete(`/inbound-orders/${row.id}`)
  ElMessage.success('已删除')
  load()
  loadSummary()
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await api.get(`/inbound-orders/${row.id}`)
  } finally {
    detailLoading.value = false
  }
}

const goReturn = () => {
  if (!detail.value) return
  router.push({ path: '/purchase/returns', query: { inbound: String(detail.value.id) } })
}

const changePage = (value: number) => {
  page.value = value
  load()
}

onMounted(async () => {
  await table.load()
  // 从消息中心跳过来：按入库单号定位，状态与类型清空以免已完成的单搜不到
  const fromQuery = String(route.query.keyword || '').trim()
  if (fromQuery) {
    keyword.value = fromQuery
    status.value = ''
    type.value = ''
    page.value = 1
  }
  load()
  loadSummary()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">入库管理</h2>
        <p class="page-sub">采购到货从采购订单收货生成，这里只处理无采购单的例外入库与来料待检</p>
      </div>
      <div class="head-actions">
        <span v-if="!canFreeInbound" class="toolbar-tip">采购入库请到采购订单收货，无单入库需要例外权限</span>
        <el-button
          v-if="canFreeInbound"
          v-perm="'warehouse:inbound'"
          type="primary"
          @click="openCreate"
        >新建无单入库</el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-label">待来料检验</div>
        <div class="stat-value">{{ summary.pendingQc || 0 }}<span class="unit">张</span></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">草稿入库单</div>
        <div class="stat-value">{{ summary.draft || 0 }}<span class="unit">张</span></div>
      </div>
      <div class="stat-card warn">
        <div class="stat-label">本月无单入库</div>
        <div class="stat-value">{{ summary.manualMonth || 0 }}<span class="unit">张</span></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">本月采购退货</div>
        <div class="stat-value">
          {{ summary.returnMonth || 0 }}<span class="unit">单</span>
          <small class="muted">¥{{ Number(summary.returnAmount || 0).toFixed(2) }}</small>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="入库单号 / 供应商" clearable style="width: 230px" @keyup.enter="reload" />
        <el-select v-model="type" placeholder="全部类型" clearable style="width: 140px" @change="reload">
          <el-option label="采购入库" value="PURCHASE" />
          <el-option label="退货入库" value="RETURN" />
          <el-option label="其他入库" value="OTHER" />
        </el-select>
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="reload">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待检" value="PENDING_QC" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" plain @click="reload">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/inbound-orders" export-name="入库单" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 张入库单</span>
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
          <template #cell-inboundNo="{ row }"><span class="mono">{{ row.inboundNo }}</span></template>
          <template #cell-type="{ row }">{{ typeLabel[row.type] || row.type }}</template>
          <template #cell-source="{ row }">
            <span class="source-tag" :class="row.source === 'MANUAL' ? 'manual' : 'po'">
              {{ sourceLabel[row.source] || row.source || '-' }}
            </span>
            <el-tooltip v-if="row.noOrderReason" :content="row.noOrderReason" placement="top">
              <span class="reason-dot">?</span>
            </el-tooltip>
          </template>
          <template #cell-purchaseOrderNo="{ row }">
            <span v-if="row.purchaseOrderNo" class="mono">{{ row.purchaseOrderNo }}</span>
            <span v-else class="muted">-</span>
          </template>
          <template #cell-itemCount="{ row }">{{ row.itemCount }}</template>
          <template #cell-totalQty="{ row }"><span class="num">{{ fmtQty(row.totalQty) }}</span></template>
          <template #cell-needInspection="{ row }">
            <span class="status-tag" :class="row.needInspection ? 'warning' : 'info'">
              <i></i>{{ row.needInspection ? '需检' : '免检' }}
            </span>
          </template>
          <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <template v-if="row.status === 'DRAFT'">
              <el-button
                v-if="row.source !== 'PO'"
                v-perm="'warehouse:inbound'"
                link
                type="primary"
                @click="openEdit(row)"
              >编辑</el-button>
              <el-button v-perm="'warehouse:inbound'" link type="success" @click="submitOrder(row)">提交</el-button>
              <el-button v-perm="'warehouse:inbound'" link type="warning" @click="cancelOrder(row)">取消</el-button>
              <el-button v-perm="'warehouse:inbound'" link type="danger" @click="removeOrder(row)">删除</el-button>
            </template>
            <template v-else-if="row.status === 'PENDING_QC'">
              <el-button v-perm="'warehouse:inbound'" link type="warning" @click="cancelOrder(row)">取消</el-button>
            </template>
          </template>
        </DataTable>
      </div>
    </div>

    <el-drawer append-to-body v-model="drawer" size="820px" :title="editingId ? '编辑入库单' : '新建入库单'">
      <el-form label-position="top">
        <div class="inbound-note" style="margin-bottom: 14px">
          采购到货请到「采购订单」里点收货生成入库单，这里只处理无采购单的例外入库（期初建账、赠品、退货返修等），
          必须填写原因说明，入库单会标记为「无单入库」，不参与发票三单匹配。
        </div>
        <div class="form-grid">
          <el-form-item label="入库类型" required>
            <el-select v-model="form.type" style="width: 100%">
              <el-option label="其他入库（无单例外）" value="OTHER" />
              <el-option label="退货入库" value="RETURN" />
            </el-select>
          </el-form-item>
          <el-form-item label="入库仓库" required>
            <ReferenceSelect v-model="form.warehouseId" url="/options/warehouses"
                             :initial-label="form.warehouseLabel"
                             placeholder="输入仓库编码或名称搜索"
                             @select="() => loadLocations()" />
          </el-form-item>
          <el-form-item label="供应商">
            <el-input v-model="form.supplier" placeholder="到货供应商名称（选填）" />
          </el-form-item>
          <el-form-item label="来料检验">
            <el-switch v-model="form.needInspection" active-text="需检验" inactive-text="免检" />
          </el-form-item>
          <el-form-item label="无单原因" required class="full">
            <el-input
              v-model="form.noOrderReason"
              maxlength="120"
              show-word-limit
              placeholder="必填，例如：期初盘点建账 / 供应商赠品 / 客户退回返修"
            />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" placeholder="到货批次、包装说明等" />
          </el-form-item>
        </div>

        <div class="drawer-section">
          <h4>
            <span>入库明细</span>
            <el-button size="small" @click="addItem">添加产品</el-button>
          </h4>
          <el-table :data="form.items" size="small">
            <el-table-column label="产品" min-width="230">
              <template #default="{ row }">
                <ReferenceSelect v-model="row.productId" url="/options/products"
                                 :initial-label="row.productCode ? `${row.productCode} · ${row.productName}` : row.productName"
                                 placeholder="输入物料编码或名称搜索"
                                 size="small" />
              </template>
            </el-table-column>
            <el-table-column label="数量" width="130">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="0.01" :precision="2" size="small" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="批次号" width="180">
              <template #default="{ row }">
                <el-input v-model="row.batchNo" size="small" placeholder="选填" />
              </template>
            </el-table-column>
            <el-table-column label="单价（元）" width="130">
              <template #default="{ row }">
                <el-input-number v-model="row.unitPrice" :min="0" :precision="2" size="small" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="库位" width="150">
              <template #default="{ row }">
                <el-select
                  v-model="row.locationCode"
                  filterable
                  remote
                  reserve-keyword
                  :remote-method="loadLocations"
                  allow-create
                  default-first-option
                  clearable
                  size="small"
                  placeholder="选填"
                  style="width: 100%"
                >
                  <el-option v-for="item in locations" :key="item.id" :label="item.code" :value="item.code" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeItem($index)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="order-total">
            <span>合计数量</span>
            <b class="num">{{ fmtQty(totalQty) }}</b>
          </div>
          <div class="inbound-note">
            提交后：需检验的入库单进入「待检」，由质检完成来料检验且合格后整单入库；免检入库单提交即入库并生成库存流水。
            单价留 0 表示按产品档案成本入库，填了会刷新该产品的移动平均成本。
          </div>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存入库单</el-button>
      </template>
    </el-drawer>

    <el-drawer append-to-body v-model="detailDrawer" size="760px" title="入库单详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-no mono">{{ detail.inboundNo }}</div>
              <div class="muted" style="margin-top: 4px">
                {{ typeLabel[detail.type] || detail.type }} · {{ detail.warehouseName }}
              </div>
            </div>
            <StatusTag :status="detail.status" />
          </div>

          <div class="detail-grid">
            <div class="detail-item">
              <div class="label">来源</div>
              <div class="value">{{ sourceLabel[detail.source] || detail.source || '-' }}</div>
            </div>
            <div class="detail-item">
              <div class="label">采购单</div>
              <div class="value mono">{{ detail.purchaseOrderNo || '-' }}</div>
            </div>
            <div class="detail-item"><div class="label">供应商</div><div class="value">{{ detail.supplier || '-' }}</div></div>
            <div class="detail-item">
              <div class="label">检验要求</div>
              <div class="value">{{ detail.needInspection ? '需来料检验' : '免检' }}</div>
            </div>
            <div class="detail-item"><div class="label">合计数量</div><div class="value num">{{ fmtQty(detail.totalQty) }}</div></div>
            <div class="detail-item"><div class="label">创建人</div><div class="value">{{ detail.createdBy || '-' }}</div></div>
            <div class="detail-item"><div class="label">创建时间</div><div class="value">{{ detail.createdAt }}</div></div>
            <div class="detail-item"><div class="label">完成时间</div><div class="value">{{ detail.completedAt || '-' }}</div></div>
            <div class="detail-item" v-if="detail.noOrderReason">
              <div class="label">无单原因</div>
              <div class="value">{{ detail.noOrderReason }}</div>
            </div>
            <div class="detail-item" style="grid-column: span 3">
              <div class="label">备注</div>
              <div class="value">{{ detail.remark || '-' }}</div>
            </div>
          </div>

          <div v-if="detail.source === 'PO' && detail.status === 'COMPLETED'" class="inbound-note">
            该入库单来自采购收货。来料不合格需要退回供应商时，走采购退货：扣库存、回退采购实收、冲减暂估。
            <el-button v-perm="'purchase:return'" link type="primary" @click="goReturn">发起采购退货</el-button>
          </div>

          <div class="drawer-section">
            <h4>入库明细</h4>
            <el-table :data="detail.items" size="small">
              <el-table-column prop="productCode" label="编码" width="90" />
              <el-table-column prop="productName" label="产品" min-width="150" />
              <el-table-column prop="spec" label="规格" min-width="110" />
              <el-table-column label="数量" width="90" align="right">
                <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
              </el-table-column>
              <el-table-column prop="batchNo" label="批次号" width="140">
                <template #default="{ row }">{{ row.batchNo || '-' }}</template>
              </el-table-column>
              <el-table-column label="单价" width="110" align="right">
                <template #default="{ row }">{{ fmtMoney(row.unitPrice) }}</template>
              </el-table-column>
              <el-table-column label="金额" width="110" align="right">
                <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="库位" width="120">
                <template #default="{ row }">
                  <span v-if="row.locationCode" class="mono">{{ row.locationCode }}</span>
                  <span v-else class="muted">未指定</span>
                </template>
              </el-table-column>
            </el-table>
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
  gap: 12px;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.stat-card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 14px 16px;
}

.stat-card.warn {
  border-color: #ffd8a8;
  background: #fffaf0;
}

.stat-label {
  font-size: 12px;
  color: var(--muted);
}

.stat-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 600;
  color: var(--ink);
}

.stat-value .unit {
  font-size: 12px;
  font-weight: 400;
  color: var(--muted);
  margin-left: 4px;
}

.stat-value small {
  margin-left: 8px;
}

.source-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  line-height: 18px;
  background: #eef4ff;
  color: #1d4ed8;
}

.source-tag.manual {
  background: #fff4e6;
  color: #b45309;
}

.reason-dot {
  display: inline-block;
  width: 14px;
  height: 14px;
  margin-left: 4px;
  border-radius: 50%;
  background: #ffd8a8;
  color: #b45309;
  font-size: 10px;
  line-height: 14px;
  text-align: center;
  cursor: help;
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

.inbound-note {
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
