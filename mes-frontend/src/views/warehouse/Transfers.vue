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
  pageKey: 'warehouse.transfers',
  columns: [
    { key: 'transferNo', label: '调拨单号', width: 160 },
    { key: 'fromWarehouseName', label: '调出仓库', width: 120 },
    { key: 'arrow', label: '', width: 40, align: 'center', prop: '' },
    { key: 'toWarehouseName', label: '调入仓库', width: 120 },
    { key: 'itemCount', label: '明细数', width: 85, align: 'right' },
    { key: 'totalQty', label: '合计数量', width: 100, align: 'right' },
    { key: 'crossFactory', label: '跨厂', width: 80, align: 'center' },
    { key: 'status', label: '状态', width: 100 },
    { key: 'remark', label: '备注', width: 160, flex: true, ellipsis: true },
    { key: 'createdBy', label: '创建人', width: 90 },
    { key: 'createdAt', label: '创建时间', width: 140 },
    { key: 'actions', label: '操作', width: 200, fixed: 'right', prop: '' }
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

const drawer = ref(false)
const saving = ref(false)
const form = reactive<any>({
  fromWarehouseId: null,
  toWarehouseId: null,
  remark: '',
  items: []
})

const detailDrawer = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const totalQty = computed(() =>
  form.items.reduce((sum: number, item: any) => sum + Number(item.qty || 0), 0)
)

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/transfers', {
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

const openCreate = () => {
  Object.assign(form, {
    fromWarehouseId: null,
    toWarehouseId: null,
    remark: '',
    items: [{ productId: null, qty: 1 }]
  })
  drawer.value = true
}

const addItem = () => {
  form.items.push({ productId: null, qty: 1 })
}

const removeItem = (index: number) => {
  form.items.splice(index, 1)
}

const submit = async () => {
  if (!form.fromWarehouseId || !form.toWarehouseId) {
    ElMessage.warning('请选择调出与调入仓库')
    return
  }
  if (form.fromWarehouseId === form.toWarehouseId) {
    ElMessage.warning('调出仓库与调入仓库不能相同')
    return
  }
  if (!form.items.length || form.items.some((item: any) => !item.productId || !item.qty || item.qty <= 0)) {
    ElMessage.warning('请完整填写调拨明细')
    return
  }
  saving.value = true
  try {
    const id = await api.post('/transfers', {
      fromWarehouseId: form.fromWarehouseId,
      toWarehouseId: form.toWarehouseId,
      remark: form.remark,
      items: form.items.map((item: any) => ({ productId: item.productId, qty: Number(item.qty) }))
    })
    const created = await api.get(`/transfers/${id}`)
    if (created?.crossFactory) {
      ElMessage.success('跨厂调拨已提交审批，审批通过后才能执行调拨')
    } else {
      ElMessage.success('调拨单已保存')
    }
    drawer.value = false
    load()
  } finally {
    saving.value = false
  }
}

const completeTransfer = async (row: any) => {
  await ElMessageBox.confirm(
    `完成调拨单 ${row.transferNo}？将从「${row.fromWarehouseName}」出库并入库到「${row.toWarehouseName}」。`,
    '完成调拨'
  )
  await api.post(`/transfers/${row.id}/complete`)
  ElMessage.success('调拨完成，源仓已出库、目标仓已入库')
  load()
}

const removeTransfer = async (row: any) => {
  await ElMessageBox.confirm(`确认删除草稿调拨单 ${row.transferNo}？`, '删除确认', { type: 'warning' })
  await api.delete(`/transfers/${row.id}`)
  ElMessage.success('已删除')
  load()
}

const openDetail = async (row: any) => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await api.get(`/transfers/${row.id}`)
  } finally {
    detailLoading.value = false
  }
}

// 审批进度：跨厂调拨才有，展示每一级谁在什么时候处理、意见是什么
const approvalVisible = ref(false)
const approvalLoading = ref(false)
const approvalDetail = ref<any>(null)

const openApproval = async (row: any) => {
  approvalVisible.value = true
  approvalLoading.value = true
  approvalDetail.value = null
  try {
    approvalDetail.value = await api.get(`/approvals/instances/${row.approvalInstanceId}`)
  } finally {
    approvalLoading.value = false
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
        <h2 class="page-title">库存调拨</h2>
        <p class="page-sub">仓库之间转移物料，完成调拨时源仓出库、目标仓入库同步记账</p>
      </div>
      <el-button v-perm="'warehouse:transfer'" type="primary" @click="openCreate">新建调拨单</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="调拨单号" clearable style="width: 230px" @keyup.enter="load" />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待审批" value="PENDING_APPROVAL" />
          <el-option label="已批准" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button type="primary" plain @click="load">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/transfers" export-name="库存调拨" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 张调拨单</span>
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
          <template #cell-transferNo="{ row }"><span class="mono">{{ row.transferNo }}</span></template>
          <template #cell-arrow><span class="muted">→</span></template>
          <template #cell-itemCount="{ row }">{{ row.itemCount }}</template>
          <template #cell-totalQty="{ row }"><span class="num">{{ fmtQty(row.totalQty) }}</span></template>
          <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
          <template #cell-crossFactory="{ row }">
            <span v-if="row.crossFactory" class="status-tag warning"><i></i>跨厂</span>
            <span v-else class="muted">-</span>
          </template>
          <template #cell-remark="{ row }">{{ row.remark || '-' }}</template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <template v-if="['DRAFT', 'APPROVED'].includes(row.status)">
              <el-button v-perm="'warehouse:transfer'" link type="success" @click="completeTransfer(row)">完成调拨</el-button>
            </template>
            <template v-if="['DRAFT', 'REJECTED'].includes(row.status)">
              <el-button v-perm="'warehouse:transfer'" link type="danger" @click="removeTransfer(row)">删除</el-button>
            </template>
            <el-button v-if="row.approvalInstanceId" link @click="openApproval(row)">审批</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <el-drawer append-to-body v-model="drawer" size="800px" title="新建调拨单">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="调出仓库" required>
            <ReferenceSelect v-model="form.fromWarehouseId" url="/options/warehouses"
                             placeholder="输入仓库编码或名称搜索" />
          </el-form-item>
          <el-form-item label="调入仓库" required>
            <ReferenceSelect v-model="form.toWarehouseId" url="/options/warehouses"
                             placeholder="输入仓库编码或名称搜索" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" placeholder="调拨原因、领用车间等" />
          </el-form-item>
        </div>

        <div class="drawer-section">
          <h4>
            <span>调拨明细</span>
            <el-button size="small" @click="addItem">添加产品</el-button>
          </h4>
          <el-table :data="form.items" size="small">
            <el-table-column label="产品" min-width="260">
              <template #default="{ row }">
                <ReferenceSelect v-model="row.productId" url="/options/products"
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
          <div class="transfer-note">保存后为草稿，完成调拨时源仓库存不足会整单失败并回滚，不会产生部分调拨。</div>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存调拨单</el-button>
      </template>
    </el-drawer>

    <el-drawer append-to-body v-model="detailDrawer" size="720px" title="调拨单详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <div>
              <div class="detail-no mono">{{ detail.transferNo }}</div>
              <div class="muted" style="margin-top: 4px">
                {{ detail.fromWarehouseName }} → {{ detail.toWarehouseName }}
              </div>
            </div>
            <StatusTag :status="detail.status" />
          </div>

          <div class="detail-grid">
            <div class="detail-item"><div class="label">调出仓库</div><div class="value">{{ detail.fromWarehouseName }}</div></div>
            <div class="detail-item"><div class="label">调入仓库</div><div class="value">{{ detail.toWarehouseName }}</div></div>
            <div class="detail-item"><div class="label">合计数量</div><div class="value num">{{ fmtQty(detail.totalQty) }}</div></div>
            <div class="detail-item"><div class="label">创建人</div><div class="value">{{ detail.createdBy || '-' }}</div></div>
            <div class="detail-item"><div class="label">创建时间</div><div class="value">{{ detail.createdAt }}</div></div>
            <div class="detail-item"><div class="label">完成时间</div><div class="value">{{ detail.completedAt || '-' }}</div></div>
            <div class="detail-item"><div class="label">跨厂调拨</div><div class="value">{{ detail.crossFactory ? '是（需审批）' : '否' }}</div></div>
            <div class="detail-item"><div class="label">审批状态</div>
              <div class="value">
                <template v-if="detail.approvalStatus">
                  {{ detail.approvalStatus }}<span v-if="detail.approvalNode"> · {{ detail.approvalNode }}</span>
                </template>
                <span v-else class="muted">-</span>
              </div>
            </div>
            <div class="detail-item" style="grid-column: span 3">
              <div class="label">备注</div>
              <div class="value">{{ detail.remark || '-' }}</div>
            </div>
          </div>

          <div class="drawer-section">
            <h4>调拨明细</h4>
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

    <el-dialog append-to-body v-model="approvalVisible" title="审批进度" width="640px">
      <div v-loading="approvalLoading">
        <template v-if="approvalDetail">
          <div class="detail-head">
            <div>
              <div class="detail-no">{{ approvalDetail.title }}</div>
              <div class="muted" style="margin-top: 4px">
                申请人 {{ approvalDetail.applicant }} · {{ approvalDetail.submittedAt }}
                <span v-if="approvalDetail.currentNode">· 当前节点 {{ approvalDetail.currentNode }}</span>
              </div>
            </div>
            <StatusTag :status="approvalDetail.status" />
          </div>
          <el-table :data="approvalDetail.tasks" size="small" style="margin-top: 12px">
            <el-table-column prop="seq" label="顺序" width="70" />
            <el-table-column prop="nodeName" label="节点" min-width="130" />
            <el-table-column label="审批人" width="130">
              <template #default="{ row }">{{ row.assignee || row.approverRole || '-' }}</template>
            </el-table-column>
            <el-table-column label="方式" width="70">
              <template #default="{ row }">{{ row.multiMode === 'ALL' ? '会签' : '或签' }}</template>
            </el-table-column>
            <el-table-column label="结果" width="100">
              <template #default="{ row }"><StatusTag :status="row.status" /></template>
            </el-table-column>
            <el-table-column label="处理人" width="110">
              <template #default="{ row }">{{ row.approver || '-' }}</template>
            </el-table-column>
            <el-table-column label="意见" min-width="150">
              <template #default="{ row }">{{ row.comment || '-' }}</template>
            </el-table-column>
            <el-table-column label="时间" width="140">
              <template #default="{ row }">{{ row.actedAt || '-' }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-dialog>
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

.transfer-note {
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
