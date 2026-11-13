<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtQty } from '../../api'
import ReferenceSelect from '../../components/ReferenceSelect.vue'

const activeTab = ref('locations')
const warehouseId = ref<number | ''>('')

// ---------------- 库位
const locations = ref<any[]>([])
const locationLoading = ref(false)
const locPage = ref(1)
const locSize = ref(20)
const locTotal = ref(0)
const locationDrawer = ref(false)
const editingLocationId = ref<number | null>(null)
const locationForm = reactive<any>({
  warehouseId: null, warehouseLabel: '', code: '', name: '', zone: '', enabled: true, remark: ''
})

// ---------------- 批次分布
const batches = ref<any[]>([])
const batchLoading = ref(false)
const batchKeyword = ref('')
const batchPage = ref(1)
const batchSize = ref(20)
const batchTotal = ref(0)

// ---------------- 移库
const moveDialog = ref(false)
const moveRow = ref<any>(null)
const moveForm = reactive<any>({ qty: 0, toLocationId: null, remark: '' })
const moves = ref<any[]>([])
const moveLoading = ref(false)
const movePage = ref(1)
const moveSize = ref(20)
const moveTotal = ref(0)
// 已上架 / 未指定库位的概览：库位只细化到批次，没上架的量仍在仓库库存里
const stats = ref<any>({})

const loadLocations = async () => {
  locationLoading.value = true
  try {
    const data = await api.get('/locations', {
      warehouseId: warehouseId.value || undefined,
      page: locPage.value,
      size: locSize.value
    })
    locations.value = data?.records || []
    locTotal.value = data?.total || 0
  } finally {
    locationLoading.value = false
  }
}

const loadBatches = async () => {
  batchLoading.value = true
  try {
    const data = await api.get('/locations/batches', {
      warehouseId: warehouseId.value || undefined,
      keyword: batchKeyword.value || undefined,
      page: batchPage.value,
      size: batchSize.value
    })
    batches.value = data?.records || []
    batchTotal.value = data?.total || 0
  } finally {
    batchLoading.value = false
  }
}

const reloadBatches = () => {
  batchPage.value = 1
  loadBatches()
}

const loadMoves = async () => {
  moveLoading.value = true
  try {
    const data = await api.get('/locations/moves', {
      warehouseId: warehouseId.value || undefined,
      page: movePage.value,
      size: moveSize.value
    })
    moves.value = data?.records || []
    moveTotal.value = data?.total || 0
  } finally {
    moveLoading.value = false
  }
}

const loadStats = async () => {
  stats.value = (await api.get('/locations/stats', {
    warehouseId: warehouseId.value || undefined
  })) || {}
}

const reload = async () => {
  locPage.value = 1
  batchPage.value = 1
  movePage.value = 1
  await Promise.all([loadLocations(), loadBatches(), loadMoves(), loadStats()])
}

const openLocation = async (row?: any) => {
  editingLocationId.value = row?.id ?? null
  let targetWarehouseId = row?.warehouseId ?? (warehouseId.value || null)
  if (!targetWarehouseId) {
    // 新增时默认落到第一个仓库，不再整表加载仓库
    const options = await api.get('/options/warehouses', { limit: 1 })
    targetWarehouseId = options?.[0]?.id ?? null
  }
  Object.assign(locationForm, {
    warehouseId: targetWarehouseId,
    warehouseLabel: row?.warehouseName || '',
    code: row?.code || '',
    name: row?.name || '',
    zone: row?.zone || '',
    enabled: row?.enabled ?? true,
    remark: row?.remark || ''
  })
  locationDrawer.value = true
}

const saveLocation = async () => {
  if (!locationForm.warehouseId || !locationForm.code.trim()) {
    ElMessage.warning('请选择仓库并填写库位编码')
    return
  }
  const payload: any = { ...locationForm }
  delete payload.warehouseLabel
  await api.post('/locations', { id: editingLocationId.value, ...payload })
  ElMessage.success('库位已保存')
  locationDrawer.value = false
  await loadLocations()
}

const removeLocation = async (row: any) => {
  await ElMessageBox.confirm(`删除库位「${row.code}」？还有库存的库位不能删。`, '删除库位', { type: 'warning' })
  await api.delete(`/locations/${row.id}`)
  ElMessage.success('已删除')
  await loadLocations()
}

const openMove = async (row: any) => {
  moveRow.value = row
  moveForm.qty = Number(row.qty || 0)
  moveForm.toLocationId = null
  moveForm.remark = ''
  moveDialog.value = true
}

const submitMove = async () => {
  if (!moveForm.toLocationId) {
    ElMessage.warning('请选择目标库位')
    return
  }
  if (Number(moveForm.qty) <= 0) {
    ElMessage.warning('移库数量必须大于 0')
    return
  }
  await api.post('/locations/move', {
    productId: moveRow.value.productId,
    warehouseId: moveRow.value.warehouseId,
    batchNo: moveRow.value.batchNo,
    qty: Number(moveForm.qty),
    toLocationId: moveForm.toLocationId,
    remark: moveForm.remark
  })
  ElMessage.success('移库完成')
  moveDialog.value = false
  await reload()
}

const onTabChange = (name: any) => {
  if (name === 'batches') loadBatches()
  if (name === 'moves') loadMoves()
}

onMounted(async () => {
  await reload()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">库位管理</h2>
        <p class="page-sub">
          库位只细化到批次：库存总量口径不变，但能回答「这批货在哪个货位」，移库不动总库存
        </p>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <ReferenceSelect v-model="warehouseId" url="/options/warehouses"
                         placeholder="全部仓库" style="width: 180px" @select="reload" />
        <el-button :loading="locationLoading" @click="reload">刷新</el-button>
      </div>

      <div class="loc-strip">
        <div class="strip-cell">
          <span>已上架批次</span>
          <b>{{ stats.assignedBatches ?? 0 }}</b>
        </div>
        <div class="strip-cell">
          <span>已上架数量</span>
          <b>{{ fmtQty(stats.assignedQty) }}</b>
        </div>
        <div class="strip-cell" :class="{ warn: Number(stats.unassignedBatches || 0) > 0 }">
          <span>未指定库位</span>
          <b>{{ stats.unassignedBatches ?? 0 }} 批 / {{ fmtQty(stats.unassignedQty) }}</b>
        </div>
        <div class="strip-note">
          库位只细化到批次：没上架的货仍算在仓库库存里，去「批次分布」移一次库就落到具体货位。
        </div>
      </div>

      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane label="库位" name="locations">
          <div class="filter-bar">
            <span class="toolbar-tip">库位编码在仓库内唯一；有库存的库位不能删除</span>
            <div class="spacer"></div>
            <el-button type="primary" @click="openLocation()">新增库位</el-button>
          </div>
          <div v-loading="locationLoading" class="table-wrap">
            <el-table :data="locations" size="small" empty-text="还没有维护库位">
              <el-table-column prop="warehouseName" label="仓库" width="140" />
              <el-table-column label="库位编码" width="150">
                <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
              </el-table-column>
              <el-table-column prop="name" label="库位名称" min-width="160" />
              <el-table-column prop="zone" label="库区" width="120">
                <template #default="{ row }">{{ row.zone || '-' }}</template>
              </el-table-column>
              <el-table-column label="在库批次" width="110" align="right">
                <template #default="{ row }"><span class="num">{{ row.batchCount ?? 0 }}</span></template>
              </el-table-column>
              <el-table-column label="在库数量" width="120" align="right">
                <template #default="{ row }"><span class="num">{{ fmtQty(row.stockQty) }}</span></template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <span class="status-tag" :class="row.enabled ? 'success' : 'info'">
                    <i></i>{{ row.enabled ? '启用' : '停用' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="130" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openLocation(row)">编辑</el-button>
                  <el-button link type="danger" size="small" @click="removeLocation(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div class="pager">
            <el-pagination
              v-model:current-page="locPage"
              v-model:page-size="locSize"
              :total="locTotal"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @current-change="loadLocations"
              @size-change="loadLocations"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="批次分布" name="batches">
          <div class="filter-bar">
            <el-input
              v-model="batchKeyword"
              placeholder="物料编码 / 名称 / 批次号"
              clearable
              style="width: 240px"
              @keyup.enter="reloadBatches"
            />
            <el-button type="primary" :loading="batchLoading" @click="reloadBatches">查询</el-button>
            <div class="spacer"></div>
            <span class="toolbar-tip">未上架批次显示「未指定」</span>
          </div>
          <div v-loading="batchLoading" class="table-wrap">
            <el-table :data="batches" size="small" empty-text="没有在库批次">
              <el-table-column label="物料" min-width="180">
                <template #default="{ row }">
                  <div>{{ row.productName }}</div>
                  <div class="sub mono">{{ row.productCode }}</div>
                </template>
              </el-table-column>
              <el-table-column prop="warehouseName" label="仓库" width="120" />
              <el-table-column label="批次号" width="170">
                <template #default="{ row }"><span class="mono">{{ row.batchNo }}</span></template>
              </el-table-column>
              <el-table-column label="数量" width="110" align="right">
                <template #default="{ row }">
                  <span class="num">{{ fmtQty(row.qty) }} {{ row.unit }}</span>
                </template>
              </el-table-column>
              <el-table-column label="库位" width="140">
                <template #default="{ row }">
                  <span v-if="row.locationCode" class="mono">{{ row.locationCode }}</span>
                  <span v-else class="muted">未指定</span>
                </template>
              </el-table-column>
              <el-table-column label="来源" width="100">
                <template #default="{ row }"><span class="muted">{{ row.source || '-' }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openMove(row)">移库</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div class="pager">
            <el-pagination
              v-model:current-page="batchPage"
              v-model:page-size="batchSize"
              :total="batchTotal"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @current-change="loadBatches"
              @size-change="loadBatches"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="移库记录" name="moves">
          <div v-loading="moveLoading" class="table-wrap">
            <el-table :data="moves" size="small" empty-text="还没有移库记录">
              <el-table-column label="移库单" width="150">
                <template #default="{ row }"><span class="mono">{{ row.moveNo }}</span></template>
              </el-table-column>
              <el-table-column label="物料" min-width="180">
                <template #default="{ row }">
                  <div>{{ row.productName }}</div>
                  <div class="sub mono">{{ row.productCode }}</div>
                </template>
              </el-table-column>
              <el-table-column label="批次号" width="170">
                <template #default="{ row }"><span class="mono">{{ row.batchNo }}</span></template>
              </el-table-column>
              <el-table-column label="数量" width="100" align="right">
                <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
              </el-table-column>
              <el-table-column label="从 → 到" width="180">
                <template #default="{ row }">
                  <span class="mono">{{ row.fromLocation || '未指定' }}</span>
                  <span class="muted"> → </span>
                  <span class="mono">{{ row.toLocation }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="operator" label="操作人" width="120" />
              <el-table-column prop="createdAt" label="时间" width="150">
                <template #default="{ row }"><span class="mono muted">{{ row.createdAt }}</span></template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="150">
                <template #default="{ row }">{{ row.remark || '-' }}</template>
              </el-table-column>
            </el-table>
          </div>
          <div class="pager">
            <el-pagination
              v-model:current-page="movePage"
              v-model:page-size="moveSize"
              :total="moveTotal"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @current-change="loadMoves"
              @size-change="loadMoves"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-drawer append-to-body v-model="locationDrawer" :title="editingLocationId ? '编辑库位' : '新增库位'" size="460px">
      <el-form label-position="top">
        <el-form-item label="仓库" required>
          <ReferenceSelect v-model="locationForm.warehouseId" url="/options/warehouses"
                           :initial-label="locationForm.warehouseLabel"
                           placeholder="输入仓库编码或名称搜索" />
        </el-form-item>
        <el-form-item label="库位编码" required>
          <el-input v-model="locationForm.code" placeholder="例如 A-01-03" />
        </el-form-item>
        <el-form-item label="库位名称">
          <el-input v-model="locationForm.name" placeholder="留空取编码" />
        </el-form-item>
        <el-form-item label="库区">
          <el-input v-model="locationForm.zone" placeholder="例如 A 区" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="locationForm.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="locationForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="locationDrawer = false">取消</el-button>
        <el-button type="primary" @click="saveLocation">保存</el-button>
      </template>
    </el-drawer>

    <el-dialog append-to-body draggable v-model="moveDialog" title="批次移库" width="460px">
      <el-form label-position="top">
        <el-form-item label="批次">
          <el-input
            :model-value="`${moveRow?.productCode || ''} · ${moveRow?.batchNo || ''} · 现有 ${fmtQty(moveRow?.qty)}`"
            disabled
          />
        </el-form-item>
        <el-form-item label="移库数量" required>
          <el-input-number v-model="moveForm.qty" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="目标库位" required>
          <ReferenceSelect
            :key="moveRow?.warehouseId"
            v-model="moveForm.toLocationId"
            url="/locations/options"
            :params="{ warehouseId: moveRow?.warehouseId }"
            placeholder="输入库位编码或名称搜索"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="moveForm.remark" placeholder="选填，例如整理货位" />
        </el-form-item>
        <div class="dialog-tip">
          移库不改变库存总量；部分移库会在目标库位生成尾号批次，原批次保留剩余数量。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="moveDialog = false">取消</el-button>
        <el-button type="primary" @click="submitMove">确认移库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sub {
  font-size: 11px;
  color: var(--muted);
}

.dialog-tip {
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

.loc-strip {
  display: flex;
  align-items: center;
  gap: 26px;
  padding: 12px 16px;
  margin-bottom: 4px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface-2, #fafbfc);
  flex-wrap: wrap;
}

.strip-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 96px;
}

.strip-cell span {
  font-size: 12px;
  color: var(--muted);
}

.strip-cell b {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

.strip-cell.warn b {
  color: #d97706;
}

.strip-note {
  flex: 1;
  min-width: 240px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted);
}
</style>
