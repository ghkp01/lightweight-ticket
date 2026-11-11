<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, fmtQty } from '../api'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import ReferenceSelect from '../components/ReferenceSelect.vue'
import { useTableView } from '../composables/useTableView'

const router = useRouter()

const rows = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const warehouseId = ref<any>('')
const onlyWarning = ref(false)

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'warehouse.inventory',
  columns: [
    { key: 'productCode', label: '物料编码', width: 100, fixed: 'left' },
    { key: 'productName', label: '物料名称', width: 160 },
    { key: 'spec', label: '规格', width: 120 },
    { key: 'warehouse', label: '仓库', width: 110 },
    { key: 'qty', label: '结存', width: 100, align: 'right' },
    { key: 'locked', label: '锁定', width: 80, align: 'right' },
    { key: 'available', label: '可用', width: 100, align: 'right' },
    { key: 'safety', label: '安全库存', width: 95, align: 'right' },
    { key: 'status', label: '状态', width: 90 },
    { key: 'batch', label: '批次', width: 95 },
    { key: 'actions', label: '操作', width: 150, fixed: 'right' }
  ],
  getFilters: () => ({
    keyword: keyword.value,
    warehouseId: warehouseId.value || '',
    onlyWarning: onlyWarning.value
  }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    warehouseId.value = filters.warehouseId || ''
    onlyWarning.value = Boolean(filters.onlyWarning)
  }
})

const dialog = ref(false)
const dialogType = ref<'in' | 'out'>('in')
const saving = ref(false)
const form = reactive<any>({
  productId: null,
  productLabel: '',
  warehouseId: null,
  warehouseLabel: '',
  qty: 1,
  remark: '',
  batchNo: '',
  locationCode: ''
})
// 入库时可按库位上架：库位跟着所选仓库走，不选就留在「未指定库位」
const locations = ref<any[]>([])

const loadLocations = async () => {
  if (!form.warehouseId) {
    locations.value = []
    return
  }
  locations.value = (await api.get('/locations', { warehouseId: form.warehouseId })) || []
}

const txnDrawer = ref(false)
const txnLoading = ref(false)
const txnRows = ref<any[]>([])
const txnTotal = ref(0)
const txnPage = ref(1)
const txnProduct = ref<any>(null)

const batchDrawer = ref(false)
const batchLoading = ref(false)
const batchRows = ref<any[]>([])
const batchTarget = ref<any>(null)

const warningCount = computed(() => rows.value.filter((row) => row.warning).length)
const totalQty = computed(() => rows.value.reduce((sum, row) => sum + Number(row.qty || 0), 0))

const typeLabel: Record<string, string> = {
  IN: '采购/手工入库',
  OUT: '手工出库',
  ISSUE: '生产领料',
  SHIP: '销售发货',
  RETURN: '退货入库'
}

const sourceLabel: Record<string, string> = {
  INBOUND: '来料入库',
  PRODUCTION: '完工入库',
  RETURN: '退货入库',
  TRANSFER: '调拨',
  ADJUST: '调整/期初'
}

const rowClassName = ({ row }: any) => (row.warning ? 'warn-row' : '')

const load = async () => {
  loading.value = true
  try {
    rows.value = await api.get('/inventory', {
      keyword: keyword.value,
      warehouseId: warehouseId.value || undefined,
      onlyWarning: onlyWarning.value
    })
  } finally {
    loading.value = false
  }
}

const openStock = async (type: 'in' | 'out', row?: any) => {
  dialogType.value = type
  let warehouseId = row ? row.warehouseId : null
  let warehouseLabel = row ? (row.warehouseName || '') : ''
  if (!row) {
    // 默认落到第一个原料仓，不再整表加载仓库
    const options = await api.get('/options/warehouses', { type: 'RAW', limit: 1 })
    const first = options?.[0]
    if (first) {
      warehouseId = first.id
      warehouseLabel = first.code ? `${first.code} · ${first.name}` : first.name
    }
  }
  Object.assign(form, {
    productId: row ? row.productId : null,
    productLabel: row
      ? (row.productCode ? `${row.productCode} · ${row.productName}` : (row.productName || ''))
      : '',
    warehouseId,
    warehouseLabel,
    qty: 1,
    remark: '',
    batchNo: '',
    locationCode: ''
  })
  dialog.value = true
  loadLocations()
}

// 换仓库就把库位下拉一起换掉，避免选了 A 仓的库位去入 B 仓
watch(() => form.warehouseId, () => {
  form.locationCode = ''
  if (dialog.value) loadLocations()
})

const submitStock = async () => {
  if (!form.productId || !form.warehouseId || !form.qty || form.qty <= 0) {
    ElMessage.warning('请选择产品、仓库并填写大于 0 的数量')
    return
  }
  saving.value = true
  try {
    await api.post(`/inventory/${dialogType.value === 'in' ? 'inbound' : 'outbound'}`, {
      productId: form.productId,
      warehouseId: form.warehouseId,
      qty: Number(form.qty),
      remark: form.remark,
      batchNo: dialogType.value === 'in' ? form.batchNo : undefined,
      locationCode: dialogType.value === 'in' ? form.locationCode || undefined : undefined
    })
    ElMessage.success(dialogType.value === 'in' ? '入库成功' : '出库成功')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const openBatches = async (row: any) => {
  batchTarget.value = row
  batchDrawer.value = true
  batchLoading.value = true
  try {
    batchRows.value = await api.get(`/inventory/${row.productId}/batches`, {
      warehouseId: row.warehouseId
    })
  } finally {
    batchLoading.value = false
  }
}

const goTrace = (batchNo: string) => {
  router.push({ path: '/trace', query: { code: batchNo } })
}

const openTxns = async (row: any) => {
  txnProduct.value = row
  txnDrawer.value = true
  txnPage.value = 1
  await loadTxns()
}

const loadTxns = async () => {
  txnLoading.value = true
  try {
    const data = await api.get('/stock-txns', { productId: txnProduct.value.productId, page: txnPage.value, size: 20 })
    txnRows.value = data.records
    txnTotal.value = data.total
  } finally {
    txnLoading.value = false
  }
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
        <h2 class="page-title">库存管理</h2>
        <p class="page-sub">结存、可用量与安全库存实时对照，任何变动都有流水可查</p>
      </div>
      <div>
        <el-button @click="openStock('out')">出库</el-button>
        <el-button type="primary" @click="openStock('in')">入库</el-button>
      </div>
    </div>

    <div class="stock-strip">
      <div class="strip-item">
        <span>库存记录</span>
        <b>{{ rows.length }}</b>
      </div>
      <div class="strip-item">
        <span>结存总量</span>
        <b>{{ fmtQty(totalQty) }}</b>
      </div>
      <div class="strip-item" :class="{ alert: warningCount > 0 }">
        <span>预警物料</span>
        <b>{{ warningCount }}</b>
      </div>
      <div class="strip-tip">低于安全库存的物料会整行标红，建议优先补货或调整排产。</div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="产品编码 / 名称" clearable style="width: 230px" @keyup.enter="load" />
        <ReferenceSelect v-model="warehouseId" url="/options/warehouses"
                         placeholder="全部仓库" style="width: 140px" @select="load" />
        <el-checkbox v-model="onlyWarning" @change="load">只看预警</el-checkbox>
        <el-button type="primary" plain @click="load">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/inventory" export-name="库存结存" @applied="load" />
      </div>

      <div class="table-wrap">
        <DataTable
          :table="table"
          :data="rows"
          :loading="loading"
          :row-class-name="rowClassName"
        >
          <template #cell-productCode="{ row }"><span class="mono">{{ row.productCode }}</span></template>
          <template #cell-qty="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
          <template #cell-locked="{ row }"><span class="muted">{{ fmtQty(row.lockedQty) }}</span></template>
          <template #cell-available="{ row }"><span class="num">{{ fmtQty(row.available) }}</span></template>
          <template #cell-safety="{ row }"><span class="muted">{{ fmtQty(row.safetyStock) }}</span></template>
          <template #cell-status="{ row }">
            <span class="status-tag" :class="row.warning ? 'danger' : 'success'">
              <i></i>{{ row.warning ? '预警' : '正常' }}
            </span>
          </template>
          <template #cell-batch="{ row }">
            <el-button link type="primary" @click="openBatches(row)">{{ row.batchCount || 0 }} 批</el-button>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openStock('in', row)">入库</el-button>
            <el-button link type="warning" @click="openStock('out', row)">出库</el-button>
            <el-button link @click="openTxns(row)">流水</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" :title="dialogType === 'in' ? '库存入库' : '库存出库'" width="520px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="物料" required class="full">
            <ReferenceSelect v-model="form.productId" url="/options/products"
                             :initial-label="form.productLabel"
                             placeholder="输入物料编码或名称搜索" />
          </el-form-item>
          <el-form-item label="仓库" required>
            <ReferenceSelect v-model="form.warehouseId" url="/options/warehouses"
                             :initial-label="form.warehouseLabel"
                             placeholder="输入仓库编码或名称搜索" />
          </el-form-item>
          <el-form-item :label="dialogType === 'in' ? '入库数量' : '出库数量'" required>
            <el-input-number v-model="form.qty" :min="0.01" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item v-if="dialogType === 'in'" label="批次号" class="full">
            <el-input v-model="form.batchNo" placeholder="来料批次 / 合格证号，留空按入库单号生成" />
          </el-form-item>
          <el-form-item v-if="dialogType === 'in'" label="上架库位" class="full">
            <el-select
              v-model="form.locationCode"
              clearable
              filterable
              placeholder="不选则留在「未指定库位」，之后可在库位页移库"
              style="width: 100%"
            >
              <el-option
                v-for="item in locations"
                :key="item.id"
                :label="`${item.code} · ${item.name}`"
                :value="item.code"
              />
            </el-select>
            <div v-if="!locations.length" class="muted" style="font-size: 12px; margin-top: 4px">
              当前仓库还没有库位，可先到「库位管理」建好再来入库。
            </div>
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" :placeholder="dialogType === 'in' ? '采购到货、盘盈等' : '领用、盘亏等'" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitStock">确认{{ dialogType === 'in' ? '入库' : '出库' }}</el-button>
      </template>
    </el-dialog>

    <el-drawer append-to-body v-model="txnDrawer" size="680px" :title="`库存流水 · ${txnProduct?.productName || ''}`">
      <el-table v-loading="txnLoading" :data="txnRows" size="small">
        <el-table-column prop="txnNo" label="流水号" width="150">
          <template #default="{ row }"><span class="mono">{{ row.txnNo }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="115">
          <template #default="{ row }">{{ typeLabel[row.txnType] || row.txnType }}</template>
        </el-table-column>
        <el-table-column label="数量" width="90" align="right">
          <template #default="{ row }">
            <span class="num" :class="Number(row.qty) >= 0 ? 'success-text' : 'danger-text'">
              {{ Number(row.qty) > 0 ? '+' : '' }}{{ fmtQty(row.qty) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="warehouseName" label="仓库" width="95" />
        <el-table-column prop="refNo" label="来源单据" min-width="150" show-overflow-tooltip />
        <el-table-column prop="operator" label="操作人" width="85" />
        <el-table-column prop="createdAt" label="时间" width="130" />
      </el-table>
      <div class="pager" style="padding-right: 0">
        <el-pagination
          layout="total, prev, pager, next"
          :total="txnTotal"
          :page-size="20"
          :current-page="txnPage"
          @current-change="changeTxnPage"
        />
      </div>
    </el-drawer>
    <el-drawer append-to-body v-model="batchDrawer" size="640px" :title="`批次库存 · ${batchTarget?.productName || ''}`">
      <el-table v-loading="batchLoading" :data="batchRows" size="small">
        <el-table-column label="批次号" min-width="170">
          <template #default="{ row }"><span class="mono">{{ row.batchNo }}</span></template>
        </el-table-column>
        <el-table-column label="数量" width="100" align="right">
          <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
        </el-table-column>
        <el-table-column prop="warehouseName" label="仓库" width="100" />
        <el-table-column label="库位" width="110">
          <template #default="{ row }">
            <span v-if="row.locationCode" class="mono">{{ row.locationCode }}</span>
            <span v-else class="muted">未指定</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="110">
          <template #default="{ row }">{{ sourceLabel[row.source] || row.source || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="入库时间" width="140" />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goTrace(row.batchNo)">追溯</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="batch-tip">按先进先出排列，出库、领料、发货都从最早的批次开始扣。点「追溯」看这批的来料与流向。</div>
    </el-drawer>
  </div>
</template>

<style scoped>
.batch-tip {
  margin-top: 12px;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.6;
}

.stock-strip {
  display: flex;
  align-items: center;
  gap: 34px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px 20px;
  margin-bottom: 14px;
  box-shadow: var(--shadow);
}

.strip-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.strip-item span {
  color: var(--muted);
  font-size: 12px;
}

.strip-item b {
  font-size: 20px;
  font-family: Bahnschrift, monospace;
}

.strip-item.alert b {
  color: var(--danger);
}

.strip-tip {
  margin-left: auto;
  color: var(--muted);
  font-size: 11px;
}

:deep(.el-table .warn-row) {
  --el-table-tr-bg-color: #fffbfa;
}

@media (max-width: 1100px) {
  .strip-tip {
    display: none;
  }
}
</style>
