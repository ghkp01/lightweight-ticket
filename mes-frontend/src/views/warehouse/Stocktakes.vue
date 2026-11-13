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

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'warehouse.stocktakes',
  columns: [
    { key: 'stocktakeNo', label: '盘点单号', width: 160 },
    { key: 'warehouse', label: '仓库', width: 120, prop: 'warehouseName' },
    { key: 'itemCount', label: '明细数', width: 85, align: 'right' },
    { key: 'diffCount', label: '差异行', width: 85, align: 'right' },
    { key: 'totalDiff', label: '差异合计', width: 100, align: 'right' },
    { key: 'status', label: '状态', width: 100 },
    { key: 'remark', label: '备注', width: 160, flex: true, ellipsis: true },
    { key: 'createdBy', label: '创建人', width: 90 },
    { key: 'createdAt', label: '创建时间', width: 140 },
    { key: 'actions', label: '操作', width: 180, fixed: 'right', prop: '' }
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
const form = reactive<any>({ warehouseId: null, warehouseLabel: '', remark: '' })

const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)
const actualSaving = ref(false)

const diffTotal = computed(() => {
  if (!detail.value) return 0
  return detail.value.items.reduce(
    (sum: number, item: any) => sum + Number(item.actualQty || 0) - Number(item.bookQty || 0),
    0
  )
})

const diffRows = computed(() => {
  if (!detail.value) return 0
  return detail.value.items.filter((item: any) => Number(item.actualQty || 0) !== Number(item.bookQty || 0)).length
})

const diffOf = (item: any) => Number(item.actualQty || 0) - Number(item.bookQty || 0)

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/stocktakes', {
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
  // 默认取第一个仓库，不再整表加载仓库
  const options = await api.get('/options/warehouses', { limit: 1 })
  const first = options?.[0] || null
  Object.assign(form, {
    warehouseId: first ? first.id : null,
    warehouseLabel: first ? (first.code ? `${first.code} · ${first.name}` : first.name) : '',
    remark: ''
  })
  dialog.value = true
}

const submit = async () => {
  if (!form.warehouseId) {
    ElMessage.warning('请选择盘点仓库')
    return
  }
  saving.value = true
  try {
    const id = await api.post('/stocktakes', {
      warehouseId: form.warehouseId,
      remark: form.remark
    })
    ElMessage.success('盘点单已创建，已按当前库存生成账面数量')
    dialog.value = false
    load()
    if (id) {
      await openDetail({ id })
    }
  } finally {
    saving.value = false
  }
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const data = await api.get(`/stocktakes/${row.id}`)
    data.items = (data.items || []).map((item: any) => ({
      ...item,
      bookQty: Number(item.bookQty),
      actualQty: Number(item.actualQty),
      diffQty: Number(item.diffQty)
    }))
    detail.value = data
  } finally {
    detailLoading.value = false
  }
}

const saveActual = async () => {
  if (!detail.value) return
  const invalid = detail.value.items.some((item: any) => item.actualQty === null || Number(item.actualQty) < 0)
  if (invalid) {
    ElMessage.warning('实盘数量不能为空且不能小于 0')
    return
  }
  actualSaving.value = true
  try {
    await api.put(`/stocktakes/${detail.value.id}`, {
      items: detail.value.items.map((item: any) => ({ id: item.id, actualQty: Number(item.actualQty) }))
    })
    ElMessage.success('实盘数量已保存')
    await openDetail({ id: detail.value.id })
    load()
  } finally {
    actualSaving.value = false
  }
}

const completeStocktake = async () => {
  if (!detail.value) return
  await ElMessageBox.confirm(
    `完成盘点单 ${detail.value.stocktakeNo}？系统将按差异（合计 ${fmtQty(diffTotal.value)}）调整库存并生成流水。`,
    '完成盘点'
  )
  await api.post(`/stocktakes/${detail.value.id}/complete`)
  ElMessage.success('盘点完成，差异已调整库存')
  await openDetail({ id: detail.value.id })
  load()
}

const completeFromList = async (row: any) => {
  await ElMessageBox.confirm(`直接完成盘点单 ${row.stocktakeNo}？未录入实盘数的行按账面数处理。`, '完成盘点')
  await api.post(`/stocktakes/${row.id}/complete`)
  ElMessage.success('盘点完成，差异已调整库存')
  load()
}

const removeStocktake = async (row: any) => {
  await ElMessageBox.confirm(`确认删除草稿盘点单 ${row.stocktakeNo}？`, '删除确认', { type: 'warning' })
  await api.delete(`/stocktakes/${row.id}`)
  ElMessage.success('已删除')
  load()
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
        <h2 class="page-title">库存盘点</h2>
        <p class="page-sub">按仓库快照账面数量，录入实盘数后一键调整差异</p>
      </div>
      <el-button v-perm="'warehouse:stocktake'" type="primary" @click="openCreate">新建盘点单</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="盘点单号" clearable style="width: 230px" @keyup.enter="reload" />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="reload">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" plain @click="reload">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/stocktakes" export-name="库存盘点" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 张盘点单</span>
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
          <template #cell-stocktakeNo="{ row }"><span class="mono">{{ row.stocktakeNo }}</span></template>
          <template #cell-itemCount="{ row }">{{ row.itemCount }}</template>
          <template #cell-diffCount="{ row }">
            <span :class="Number(row.diffCount) > 0 ? 'danger-text' : 'muted'">{{ row.diffCount }}</span>
          </template>
          <template #cell-totalDiff="{ row }">
            <span class="num" :class="Number(row.totalDiff) === 0 ? '' : Number(row.totalDiff) > 0 ? 'success-text' : 'danger-text'">
              {{ Number(row.totalDiff) > 0 ? '+' : '' }}{{ fmtQty(row.totalDiff) }}
            </span>
          </template>
          <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
          <template #cell-remark="{ row }">{{ row.remark || '-' }}</template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">
              {{ row.status === 'DRAFT' ? '盘点' : '详情' }}
            </el-button>
            <template v-if="row.status === 'DRAFT'">
              <el-button v-perm="'warehouse:stocktake'" link type="success" @click="completeFromList(row)">完成</el-button>
              <el-button v-perm="'warehouse:stocktake'" link type="danger" @click="removeStocktake(row)">删除</el-button>
            </template>
          </template>
        </DataTable>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" title="新建盘点单" width="520px">
      <el-form label-position="top">
        <el-form-item label="盘点仓库" required>
          <ReferenceSelect v-model="form.warehouseId" url="/options/warehouses"
                           :initial-label="form.warehouseLabel"
                           placeholder="输入仓库编码或名称搜索" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="盘点范围、盘点人说明等" />
        </el-form-item>
      </el-form>
      <div class="stocktake-note">创建时按该仓库当前库存快照账面数量，仅包含结存不为 0 的物料。</div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">创建并录入</el-button>
      </template>
    </el-dialog>

    <el-drawer append-to-body v-model="detailDrawer" size="860px" title="盘点单详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-no mono">{{ detail.stocktakeNo }}</div>
              <div class="muted" style="margin-top: 4px">{{ detail.warehouseName }}</div>
            </div>
            <StatusTag :status="detail.status" />
          </div>

          <div class="detail-grid">
            <div class="detail-item"><div class="label">创建人</div><div class="value">{{ detail.createdBy || '-' }}</div></div>
            <div class="detail-item"><div class="label">创建时间</div><div class="value">{{ detail.createdAt }}</div></div>
            <div class="detail-item"><div class="label">完成时间</div><div class="value">{{ detail.completedAt || '-' }}</div></div>
            <div class="detail-item"><div class="label">差异行</div><div class="value">{{ diffRows }}</div></div>
            <div class="detail-item">
              <div class="label">差异合计</div>
              <div class="value num">{{ diffTotal > 0 ? '+' : '' }}{{ fmtQty(diffTotal) }}</div>
            </div>
            <div class="detail-item"><div class="label">备注</div><div class="value">{{ detail.remark || '-' }}</div></div>
          </div>

          <div class="drawer-section">
            <h4>
              <span>盘点明细（实盘数量）</span>
              <el-button
                v-if="detail.status === 'DRAFT'"
                size="small"
                type="primary"
                :loading="actualSaving"
                @click="saveActual"
              >
                保存实盘数
              </el-button>
            </h4>
            <el-table :data="detail.items" size="small" max-height="calc(70vh - 260px)">
              <el-table-column prop="productCode" label="编码" width="95" />
              <el-table-column prop="productName" label="产品" min-width="150" show-overflow-tooltip />
              <el-table-column prop="spec" label="规格" min-width="110" show-overflow-tooltip />
              <el-table-column label="账面数" width="95" align="right">
                <template #default="{ row }">{{ fmtQty(row.bookQty) }}</template>
              </el-table-column>
              <el-table-column label="实盘数" width="130">
                <template #default="{ row }">
                  <el-input-number
                    v-if="detail.status === 'DRAFT'"
                    v-model="row.actualQty"
                    :min="0"
                    :precision="2"
                    size="small"
                    controls-position="right"
                    style="width: 100%"
                  />
                  <span v-else class="num">{{ fmtQty(row.actualQty) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="差异" width="95" align="right">
                <template #default="{ row }">
                  <span
                    class="num"
                    :class="diffOf(row) === 0 ? 'muted' : diffOf(row) > 0 ? 'success-text' : 'danger-text'"
                  >
                    {{ diffOf(row) > 0 ? '+' : '' }}{{ fmtQty(diffOf(row)) }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div v-if="detail.status === 'DRAFT'" class="stocktake-actions">
            <span class="muted">完成后将按差异调用库存调整，盘盈入库、盘亏出库，操作不可撤销。</span>
            <el-button v-perm="'warehouse:stocktake'" type="success" @click="completeStocktake">完成盘点</el-button>
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

.stocktake-note {
  padding: 10px 12px;
  background: #f0f6ff;
  border: 1px dashed #b2ccff;
  border-radius: 6px;
  color: #1d4ed8;
  font-size: 12px;
  line-height: 1.7;
}

.stocktake-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
  font-size: 12px;
}
</style>
