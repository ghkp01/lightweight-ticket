<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtQty } from '../../api'
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
const status = ref('PENDING')
const onlyMine = ref(false)

const table = useTableView({
  pageKey: 'planning.bom-tasks',
  columns: [
    { key: 'orderNo', label: '销售订单', width: 165, fixed: 'left' as const },
    { key: 'productCode', label: '产品编码', width: 120 },
    { key: 'productName', label: '产品', width: 180, flex: true, ellipsis: true },
    { key: 'spec', label: '规格', width: 130, ellipsis: true },
    { key: 'assigneeName', label: '技术员', width: 100 },
    { key: 'dueDate', label: '要求完成', width: 110 },
    { key: 'assignedBy', label: '派单人', width: 100 },
    { key: 'status', label: '状态', width: 90 },
    { key: 'remark', label: '备注', width: 180, ellipsis: true },
    { key: 'actions', label: '操作', width: 200, fixed: 'right' as const }
  ],
  getFilters: () => ({ keyword: keyword.value, status: status.value, onlyMine: onlyMine.value }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    status.value = filters.status || ''
    onlyMine.value = Boolean(filters.onlyMine)
  },
  getPageSize: () => size.value,
  setPageSize: (value) => {
    size.value = value
  }
})

const statusText: Record<string, string> = {
  PENDING: '待处理',
  DONE: '已完成',
  CANCELLED: '已取消'
}
const statusType: Record<string, string> = {
  PENDING: 'warning',
  DONE: 'success',
  CANCELLED: 'info'
}

const reload = () => {
  page.value = 1
  load()
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/bom-tasks', {
      keyword: keyword.value,
      status: status.value,
      onlyMine: onlyMine.value,
      page: page.value,
      size: size.value
    })
    rows.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

const changePage = (value: number) => {
  page.value = value
  load()
}

// ---------------------------------------------------------------- 派工
const assignDialog = ref(false)
const assigning = ref(false)
const orderItems = ref<any[]>([])
const orderLoading = ref(false)
const assigneeOptions = ref<any[]>([])
const assigneeLoading = ref(false)
const searchAssignees = async (keyword = '') => {
  assigneeLoading.value = true
  try {
    const rows = await api.get('/bom-tasks/assignees', {
      keyword: keyword || undefined,
      limit: 20
    })
    // 保留当前已选技术员，避免远程换关键字后标签丢失
    const keep = assigneeOptions.value.filter(
      (item) => item.id === assignForm.assigneeId || item.id === reassignForm.assigneeId
    )
    const merged = [...(rows || [])]
    keep.forEach((item) => {
      if (!merged.some((row) => row.id === item.id)) merged.push(item)
    })
    assigneeOptions.value = merged
  } finally {
    assigneeLoading.value = false
  }
}
const selectedItems = ref<any[]>([])
const assignForm = reactive<any>({ salesOrderId: null, assigneeId: null, dueDate: '', remark: '' })

const selectableItem = (row: any) => Boolean(row.make) && !row.bomReady && !row.cancelled

const openAssign = async () => {
  Object.assign(assignForm, { salesOrderId: null, assigneeId: null, dueDate: '', remark: '' })
  orderItems.value = []
  selectedItems.value = []
  await searchAssignees('')
  assignDialog.value = true
}

const loadOrderItems = async () => {
  if (!assignForm.salesOrderId) {
    orderItems.value = []
    return
  }
  orderLoading.value = true
  try {
    orderItems.value = await api.get(`/bom-tasks/orders/${assignForm.salesOrderId}/items`)
  } finally {
    orderLoading.value = false
  }
}

const onSelectionChange = (selection: any[]) => {
  selectedItems.value = selection
}

const submitAssign = async () => {
  if (!assignForm.assigneeId) {
    ElMessage.warning('请选择技术员')
    return
  }
  if (!selectedItems.value.length) {
    ElMessage.warning('请勾选要派工的订单明细')
    return
  }
  assigning.value = true
  try {
    await api.post('/bom-tasks', {
      orderItemIds: selectedItems.value.map((item) => item.itemId),
      assigneeId: assignForm.assigneeId,
      dueDate: assignForm.dueDate || null,
      remark: assignForm.remark || null
    })
    ElMessage.success(`已派 ${selectedItems.value.length} 条 BOM 任务`)
    assignDialog.value = false
    reload()
  } finally {
    assigning.value = false
  }
}

// ---------------------------------------------------------------- 完成 / 改派 / 取消
const complete = async (row: any) => {
  await ElMessageBox.confirm(
    `确认完成 ${row.orderNo} · ${row.productName} 的 BOM 任务？完成任务要求该产品已有生效 BOM。`,
    '完成 BOM 任务'
  )
  try {
    await api.post(`/bom-tasks/${row.id}/complete`)
    ElMessage.success('任务已完成')
    load()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '完成任务失败')
  }
}

const reassignDialog = ref(false)
const reassignForm = reactive<any>({ id: null, productName: '', assigneeId: null, dueDate: '' })

const openReassign = async (row: any) => {
  Object.assign(reassignForm, {
    id: row.id,
    productName: row.productName,
    assigneeId: row.assigneeId,
    dueDate: row.dueDate || ''
  })
  await searchAssignees(row.assigneeName || '')
  reassignDialog.value = true
}

const submitReassign = async () => {
  if (!reassignForm.assigneeId) {
    ElMessage.warning('请选择技术员')
    return
  }
  await api.put(`/bom-tasks/${reassignForm.id}/assignee`, {
    assigneeId: reassignForm.assigneeId,
    dueDate: reassignForm.dueDate || null
  })
  ElMessage.success('已改派')
  reassignDialog.value = false
  load()
}

const cancelTask = async (row: any) => {
  await ElMessageBox.confirm(`确认取消 ${row.orderNo} · ${row.productName} 的 BOM 任务？`, '取消任务', {
    type: 'warning'
  })
  await api.post(`/bom-tasks/${row.id}/cancel`)
  ElMessage.success('任务已取消')
  load()
}

const pendingCount = computed(() => rows.value.filter((row) => row.status === 'PENDING').length)

onMounted(async () => {
  await table.load()
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">BOM 任务</h2>
        <p class="page-sub">计划员把订单明细派给技术员做 BOM，BOM 生效后 MRP 才算这一单</p>
      </div>
      <el-button v-perm="'bom_task:assign'" type="primary" @click="openAssign">派 BOM 任务</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="订单号 / 产品编码 / 名称" clearable style="width: 240px" @keyup.enter="reload" />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 130px" @change="reload">
          <el-option label="待处理" value="PENDING" />
          <el-option label="已完成" value="DONE" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-checkbox v-model="onlyMine" @change="reload">只看我的</el-checkbox>
        <el-button type="primary" plain @click="reload">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/bom-tasks" export-name="BOM任务" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 条，本页待处理 {{ pendingCount }} 条</span>
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
          <template #cell-orderNo="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          <template #cell-dueDate="{ row }">
            <span :class="{ 'danger-text': row.overdue }">{{ row.dueDate || '-' }}</span>
            <el-tag v-if="row.overdue" type="danger" size="small" style="margin-left: 4px">逾期</el-tag>
          </template>
          <template #cell-status="{ row }">
            <el-tag :type="statusType[row.status]" size="small">{{ statusText[row.status] || row.status }}</el-tag>
          </template>
          <template #cell-actions="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button v-perm="'bom_task:complete'" link type="success" @click="complete(row)">完成</el-button>
              <el-button v-perm="'bom_task:assign'" link type="primary" @click="openReassign(row)">改派</el-button>
              <el-button v-perm="'bom_task:cancel'" link type="danger" @click="cancelTask(row)">取消</el-button>
            </template>
            <span v-else class="muted">-</span>
          </template>
          <template #empty>
            <div class="empty-state">
              <div class="empty-state-icon">BOM</div>
              <div class="empty-state-title">没有 BOM 任务</div>
              <div class="empty-state-desc">销售订单确认后，计划员在这里把明细派给技术员编制 BOM。</div>
              <div class="empty-state-actions">
                <el-button v-perm="'bom_task:assign'" type="primary" @click="openAssign">派 BOM 任务</el-button>
              </div>
            </div>
          </template>
        </DataTable>
      </div>
    </div>

    <el-dialog v-model="assignDialog" title="派 BOM 任务" width="860px" append-to-body>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="销售订单" required>
            <ReferenceSelect
              v-model="assignForm.salesOrderId"
              url="/sales-orders/options"
              :params="{ status: 'CONFIRMED' }"
              code-field="orderNo"
              label-field="customerName"
              placeholder="输入订单号或客户搜索（仅已确认）"
              @select="loadOrderItems"
            />
          </el-form-item>
          <el-form-item label="技术员" required>
            <el-select
              v-model="assignForm.assigneeId"
              filterable
              remote
              :remote-method="searchAssignees"
              :loading="assigneeLoading"
              placeholder="选择技术员"
              style="width: 100%"
            >
              <el-option
                v-for="item in assigneeOptions"
                :key="item.id"
                :label="`${item.realName}（${item.username}）`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="要求完成日期">
            <el-date-picker v-model="assignForm.dueDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="assignForm.remark" placeholder="图纸、工艺要求等" />
          </el-form-item>
        </div>

        <div v-loading="orderLoading" class="drawer-section">
          <h4>订单明细</h4>
          <el-table
            :data="orderItems"
            size="small"
            row-key="itemId"
            @selection-change="onSelectionChange"
          >
            <el-table-column type="selection" width="46" :selectable="selectableItem" />
            <el-table-column prop="productCode" label="产品编码" width="110" />
            <el-table-column prop="productName" label="产品" min-width="150" />
            <el-table-column prop="spec" label="规格" min-width="110" />
            <el-table-column label="数量" width="90" align="right">
              <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit || '' }}</template>
            </el-table-column>
            <el-table-column label="BOM 状态" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.cancelled" type="info" size="small">明细已取消</el-tag>
                <el-tag v-else-if="!row.make" type="info" size="small">外购件</el-tag>
                <el-tag v-else-if="row.bomReady" type="success" size="small">已有生效 BOM</el-tag>
                <el-tag v-else-if="row.taskStatus === 'PENDING'" type="warning" size="small">已派待处理</el-tag>
                <el-tag v-else type="danger" size="small">待补 BOM</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="assignDialog = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="submitAssign">派工</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reassignDialog" title="改派 BOM 任务" width="460px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="产品">
          <span>{{ reassignForm.productName }}</span>
        </el-form-item>
        <el-form-item label="技术员" required>
          <el-select
            v-model="reassignForm.assigneeId"
            filterable
            remote
            :remote-method="searchAssignees"
            :loading="assigneeLoading"
            style="width: 100%"
          >
            <el-option
              v-for="item in assigneeOptions"
              :key="item.id"
              :label="`${item.realName}（${item.username}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="要求完成日期">
          <el-date-picker v-model="reassignForm.dueDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reassignDialog = false">取消</el-button>
        <el-button type="primary" @click="submitReassign">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
