<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney } from '../../api'
import ReferenceSelect from '../../components/ReferenceSelect.vue'

const pad = (value: number) => String(value).padStart(2, '0')
const dateText = (date: Date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`

const activeTab = ref('checks')
const summary = ref<any>({})

// ---------------- 点检任务
const checkDate = ref(dateText(new Date()))
const checkDevice = ref<number | ''>('')
const checkStatus = ref('')
const checks = ref<any[]>([])
const checkPage = ref(1)
const checkSize = ref(20)
const checkTotal = ref(0)
const checkLoading = ref(false)
const taskDrawer = ref(false)
const taskDetail = ref<any>(null)
const results = ref<any[]>([])
const submitRemark = ref('')
const submitting = ref(false)

// ---------------- 点检项
const items = ref<any[]>([])
const itemsLoading = ref(false)
const itemDrawer = ref(false)
const editingItemId = ref<number | null>(null)
const itemForm = reactive<any>({ deviceId: null, deviceLabel: '', name: '', method: '目视', standard: '', sort: 0, enabled: true })

// ---------------- 保养计划
const plans = ref<any[]>([])
const dueOnly = ref(false)
const planLoading = ref(false)
const planDrawer = ref(false)
const editingPlanId = ref<number | null>(null)
const planForm = reactive<any>({
  deviceId: null, deviceLabel: '', name: '', cycleDays: 30, lastDate: '', nextDate: '', standard: '', enabled: true
})
const executeDialog = ref(false)
const executeRow = ref<any>(null)
const executeForm = reactive<any>({ doneDate: dateText(new Date()), worker: '', cost: 0, remark: '' })
const logs = ref<any[]>([])
const logsLoading = ref(false)

// ---------------- 故障
const faults = ref<any[]>([])
const faultPage = ref(1)
const faultSize = ref(20)
const faultTotal = ref(0)
const faultStatus = ref('')
const faultLoading = ref(false)
const faultDialog = ref(false)
const faultForm = reactive<any>({ deviceId: null, symptom: '' })
const closeDialog = ref(false)
const closeRow = ref<any>(null)
const closeForm = reactive<any>({ cause: '', action: '', handler: '' })

const statusText: Record<string, { label: string; cls: string }> = {
  PENDING: { label: '待点检', cls: 'warning' },
  DONE: { label: '已点检', cls: 'success' },
  SKIPPED: { label: '已跳过', cls: 'info' },
  OPEN: { label: '待处理', cls: 'danger' },
  CLOSED: { label: '已关闭', cls: 'success' }
}
const statusOf = (value: any) => statusText[String(value || '')] || { label: String(value || '-'), cls: 'info' }

const loadSummary = async () => {
  try {
    summary.value = (await api.get('/device-maintenance/summary')) || {}
  } catch {
    // 概览失败不影响列表
  }
}

const loadChecks = async () => {
  checkLoading.value = true
  try {
    const data = await api.get('/device-maintenance/checks', {
      date: checkDate.value,
      deviceId: checkDevice.value || undefined,
      status: checkStatus.value || undefined,
      page: checkPage.value,
      size: checkSize.value
    })
    checks.value = data?.records || []
    checkTotal.value = data?.total || 0
  } finally {
    checkLoading.value = false
  }
}

const reloadChecks = () => {
  checkPage.value = 1
  loadChecks()
}

const changeCheckPage = (value: number) => {
  checkPage.value = value
  loadChecks()
}

const generateTasks = async () => {
  const result = await api.post(
    `/device-maintenance/checks/generate?date=${encodeURIComponent(checkDate.value)}`
  )
  ElMessage.success(`已生成 ${result?.created ?? 0} 张点检任务`)
  await reloadChecks()
  await loadSummary()
}

const openTask = async (row: any) => {
  taskDetail.value = await api.get(`/device-maintenance/checks/${row.id}`)
  const existing = taskDetail.value.results || []
  const source = existing.length ? existing : taskDetail.value.items || []
  results.value = source.map((item: any) => ({
    itemId: item.itemId ?? item.id,
    itemName: item.itemName ?? item.name,
    method: item.method,
    standard: item.standard,
    result: item.result || 'OK',
    remark: item.remark || ''
  }))
  submitRemark.value = taskDetail.value.remark || ''
  taskDrawer.value = true
}

const submitCheck = async () => {
  if (!results.value.length) {
    ElMessage.warning('没有可提交的点检项，请先在「点检项」里维护该设备的项目')
    return
  }
  submitting.value = true
  try {
    const result = await api.post(`/device-maintenance/checks/${taskDetail.value.id}/submit`, {
      results: results.value,
      remark: submitRemark.value
    })
    ElMessage.success(
      `点检已提交，共 ${result?.items ?? 0} 项${Number(result?.ng || 0) > 0 ? `，异常 ${result.ng} 项已通知` : ''}`
    )
    taskDrawer.value = false
    await loadChecks()
    await loadSummary()
  } finally {
    submitting.value = false
  }
}

const resetCheck = async (row: any) => {
  await ElMessageBox.confirm(`撤销 ${row.deviceCode} 的点检结果，重新填写？`, '撤销点检', { type: 'warning' })
  await api.post(`/device-maintenance/checks/${row.id}/reset`)
  ElMessage.success('已撤销，可以重新点检')
  await loadChecks()
  await loadSummary()
}

// ---------------- 点检项
const loadItems = async () => {
  itemsLoading.value = true
  try {
    items.value = (await api.get('/device-maintenance/check-items')) || []
  } finally {
    itemsLoading.value = false
  }
}

const openItem = (row?: any) => {
  editingItemId.value = row?.id ?? null
  Object.assign(itemForm, {
    deviceId: row?.deviceId ?? null,
    deviceLabel: row?.deviceName || '',
    name: row?.name || '',
    method: row?.method || '目视',
    standard: row?.standard || '',
    sort: row?.sort ?? 0,
    enabled: row?.enabled ?? true
  })
  itemDrawer.value = true
}

const saveItem = async () => {
  if (!itemForm.name.trim()) {
    ElMessage.warning('请填写点检项名称')
    return
  }
  const payload: any = { ...itemForm }
  delete payload.deviceLabel
  await api.post('/device-maintenance/check-items', { id: editingItemId.value, ...payload })
  ElMessage.success('点检项已保存')
  itemDrawer.value = false
  await loadItems()
}

const removeItem = async (row: any) => {
  await ElMessageBox.confirm(`删除点检项「${row.name}」？已有历史结果不受影响。`, '删除点检项', {
    type: 'warning'
  })
  await api.delete(`/device-maintenance/check-items/${row.id}`)
  ElMessage.success('已删除')
  await loadItems()
}

// ---------------- 保养
const loadPlans = async () => {
  planLoading.value = true
  try {
    plans.value = (await api.get('/device-maintenance/plans', { dueOnly: dueOnly.value })) || []
  } finally {
    planLoading.value = false
  }
}

const loadLogs = async (planId?: number) => {
  logsLoading.value = true
  try {
    logs.value = (await api.get('/device-maintenance/plan-logs', { planId })) || []
  } finally {
    logsLoading.value = false
  }
}

const openPlan = (row?: any) => {
  editingPlanId.value = row?.id ?? null
  Object.assign(planForm, {
    deviceId: row?.deviceId ?? null,
    deviceLabel: row?.deviceCode ? `${row.deviceCode} · ${row.deviceName}` : (row?.deviceName || ''),
    name: row?.name || '',
    cycleDays: row?.cycleDays ?? 30,
    lastDate: row?.lastDate || '',
    nextDate: row?.nextDate || '',
    standard: row?.standard || '',
    enabled: row?.enabled ?? true
  })
  planDrawer.value = true
}

const savePlan = async () => {
  if (!planForm.deviceId || !planForm.name.trim()) {
    ElMessage.warning('请选择设备并填写保养项目')
    return
  }
  await api.post('/device-maintenance/plans', {
    id: editingPlanId.value,
    deviceId: planForm.deviceId,
    name: planForm.name,
    cycleDays: planForm.cycleDays,
    lastDate: planForm.lastDate || null,
    nextDate: planForm.nextDate || null,
    standard: planForm.standard,
    enabled: planForm.enabled
  })
  ElMessage.success('保养计划已保存')
  planDrawer.value = false
  await loadPlans()
  await loadSummary()
}

const removePlan = async (row: any) => {
  await ElMessageBox.confirm(`删除「${row.deviceCode} · ${row.name}」的保养计划？`, '删除保养计划', {
    type: 'warning'
  })
  await api.delete(`/device-maintenance/plans/${row.id}`)
  ElMessage.success('已删除')
  await loadPlans()
}

const openExecute = (row: any) => {
  executeRow.value = row
  Object.assign(executeForm, {
    doneDate: dateText(new Date()),
    worker: '',
    cost: 0,
    remark: ''
  })
  executeDialog.value = true
}

const submitExecute = async () => {
  const result = await api.post(`/device-maintenance/plans/${executeRow.value.id}/execute`, {
    doneDate: executeForm.doneDate,
    worker: executeForm.worker,
    cost: Number(executeForm.cost || 0),
    remark: executeForm.remark
  })
  ElMessage.success(`保养已完成，下次 ${result?.nextDate || '-'}`)
  executeDialog.value = false
  await loadPlans()
  await loadLogs()
  await loadSummary()
}

// ---------------- 故障
const loadFaults = async () => {
  faultLoading.value = true
  try {
    const data = await api.get('/device-maintenance/faults', {
      status: faultStatus.value || undefined,
      page: faultPage.value,
      size: faultSize.value
    })
    faults.value = data?.records || []
    faultTotal.value = data?.total || 0
  } finally {
    faultLoading.value = false
  }
}

const reloadFaults = () => {
  faultPage.value = 1
  loadFaults()
}

const changeFaultPage = (value: number) => {
  faultPage.value = value
  loadFaults()
}

const openFault = () => {
  Object.assign(faultForm, { deviceId: null, symptom: '' })
  faultDialog.value = true
}

const submitFault = async () => {
  if (!faultForm.deviceId || !faultForm.symptom.trim()) {
    ElMessage.warning('请选择设备并填写故障现象')
    return
  }
  await api.post('/device-maintenance/faults', { ...faultForm })
  ElMessage.success('已报修，设备置为停机')
  faultDialog.value = false
  await Promise.all([reloadFaults(), loadSummary()])
}

const openClose = (row: any) => {
  closeRow.value = row
  Object.assign(closeForm, { cause: row.cause || '', action: row.action || '', handler: '' })
  closeDialog.value = true
}

const submitClose = async () => {
  const result = await api.post(`/device-maintenance/faults/${closeRow.value.id}/close`, { ...closeForm })
  ElMessage.success(`故障已关闭，停机 ${result?.downtimeMinutes ?? 0} 分钟`)
  closeDialog.value = false
  await Promise.all([loadFaults(), loadSummary()])
}

const onTabChange = (name: any) => {
  if (name === 'items' && !items.value.length) loadItems()
  if (name === 'plans' && !plans.value.length) {
    loadPlans()
    loadLogs()
  }
  if (name === 'faults' && !faults.value.length) loadFaults()
}

onMounted(async () => {
  await Promise.all([loadSummary(), loadChecks()])
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">设备点检与保养</h2>
        <p class="page-sub">
          每天按设备生成点检任务，异常项自动提醒；保养按周期滚动，报修即置停机并记录停机时长
        </p>
      </div>
    </div>

    <div class="panel kpi-panel">
      <div class="kpi-grid kpi-4">
        <div class="kpi-card">
          <div class="kpi-label">今日点检</div>
          <div class="kpi-value">{{ summary.todayDone ?? 0 }}<small>/ {{ summary.todayTasks ?? 0 }}</small></div>
          <div class="kpi-foot">共 {{ summary.devices ?? 0 }} 台启用设备</div>
        </div>
        <div class="kpi-card" :class="{ 'kpi-alert': Number(summary.todayNg || 0) > 0 }">
          <div class="kpi-label">今日异常项</div>
          <div class="kpi-value">{{ summary.todayNg ?? 0 }}<small>项</small></div>
          <div class="kpi-foot">点检判 NG 的项目</div>
        </div>
        <div class="kpi-card" :class="{ 'kpi-alert': Number(summary.maintainOverdue || 0) > 0 }">
          <div class="kpi-label">保养到期</div>
          <div class="kpi-value">{{ summary.maintainDue ?? 0 }}<small>项</small></div>
          <div class="kpi-foot">
            {{ summary.maintainNoticeDays ?? 7 }} 天内到期，逾期 {{ summary.maintainOverdue ?? 0 }} 项
          </div>
        </div>
        <div class="kpi-card" :class="{ 'kpi-alert': Number(summary.openFaults || 0) > 0 }">
          <div class="kpi-label">未关闭故障</div>
          <div class="kpi-value">{{ summary.openFaults ?? 0 }}<small>单</small></div>
          <div class="kpi-foot">报修后设备置为停机</div>
        </div>
      </div>
    </div>

    <div class="panel">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane label="点检任务" name="checks">
          <div class="filter-bar">
            <el-date-picker v-model="checkDate" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 150px" @change="reloadChecks" />
            <ReferenceSelect v-model="checkDevice" url="/devices/options"
                             placeholder="全部设备" style="width: 180px" @select="reloadChecks" />
            <el-select v-model="checkStatus" placeholder="全部状态" clearable style="width: 130px" @change="reloadChecks">
              <el-option label="待点检" value="PENDING" />
              <el-option label="已点检" value="DONE" />
            </el-select>
            <el-button type="primary" :loading="checkLoading" @click="reloadChecks">查询</el-button>
            <div class="spacer"></div>
            <el-button plain @click="generateTasks">生成当日任务</el-button>
          </div>
          <div v-loading="checkLoading" class="table-wrap">
            <el-table :data="checks" size="small" empty-text="当天还没有点检任务，点「生成当日任务」">
              <el-table-column label="任务号" width="150">
                <template #default="{ row }"><span class="mono">{{ row.taskNo }}</span></template>
              </el-table-column>
              <el-table-column label="设备" min-width="180">
                <template #default="{ row }">
                  <div>{{ row.deviceName }}</div>
                  <div class="sub mono">{{ row.deviceCode }} · {{ row.workCenterName }}</div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <span class="status-tag" :class="statusOf(row.status).cls"><i></i>{{ statusOf(row.status).label }}</span>
                </template>
              </el-table-column>
              <el-table-column label="点检项" width="100" align="right">
                <template #default="{ row }">
                  <span class="num">{{ row.ngCount ?? 0 }} NG / {{ row.itemCount ?? 0 }}</span>
                </template>
              </el-table-column>
              <el-table-column label="点检人" width="120">
                <template #default="{ row }">{{ row.checker || '-' }}</template>
              </el-table-column>
              <el-table-column label="点检时间" width="150">
                <template #default="{ row }"><span class="mono muted">{{ row.checkedAt || '-' }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="140" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openTask(row)">
                    {{ row.status === 'DONE' ? '查看' : '点检' }}
                  </el-button>
                  <el-button v-if="row.status === 'DONE'" link type="danger" size="small" @click="resetCheck(row)">
                    撤销
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div class="pager">
            <el-pagination
              v-model:current-page="checkPage"
              v-model:page-size="checkSize"
              :total="checkTotal"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @current-change="changeCheckPage"
              @size-change="loadChecks"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="点检项" name="items">
          <div class="filter-bar">
            <span class="toolbar-tip">设备留空表示所有设备通用；点检时按「设备项 + 通用项」合并</span>
            <div class="spacer"></div>
            <el-button type="primary" @click="openItem()">新增点检项</el-button>
          </div>
          <div v-loading="itemsLoading" class="table-wrap">
            <el-table :data="items" size="small" empty-text="还没有维护点检项">
              <el-table-column label="适用设备" min-width="160">
                <template #default="{ row }">
                  <span v-if="row.deviceName">{{ row.deviceName }}</span>
                  <span v-else class="muted">所有设备通用</span>
                </template>
              </el-table-column>
              <el-table-column prop="name" label="点检项" min-width="180" />
              <el-table-column prop="method" label="方法" width="90" />
              <el-table-column prop="standard" label="标准" min-width="200">
                <template #default="{ row }">{{ row.standard || '-' }}</template>
              </el-table-column>
              <el-table-column prop="sort" label="排序" width="80" align="right" />
              <el-table-column label="启用" width="90">
                <template #default="{ row }">
                  <span class="status-tag" :class="row.enabled ? 'success' : 'info'">
                    <i></i>{{ row.enabled ? '启用' : '停用' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openItem(row)">编辑</el-button>
                  <el-button link type="danger" size="small" @click="removeItem(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="保养计划" name="plans">
          <div class="filter-bar">
            <el-checkbox v-model="dueOnly" @change="loadPlans">只看到期</el-checkbox>
            <el-button :loading="planLoading" @click="loadPlans">刷新</el-button>
            <div class="spacer"></div>
            <el-button type="primary" @click="openPlan()">新增保养计划</el-button>
          </div>
          <div v-loading="planLoading" class="table-wrap">
            <el-table :data="plans" size="small" empty-text="还没有保养计划">
              <el-table-column label="设备" min-width="180">
                <template #default="{ row }">
                  <div>{{ row.deviceName }}</div>
                  <div class="sub mono">{{ row.deviceCode }} · {{ row.workCenterName }}</div>
                </template>
              </el-table-column>
              <el-table-column prop="name" label="保养项目" min-width="150" />
              <el-table-column label="周期" width="90" align="right">
                <template #default="{ row }"><span class="num">{{ row.cycleDays }} 天</span></template>
              </el-table-column>
              <el-table-column label="上次" width="120">
                <template #default="{ row }"><span class="mono">{{ row.lastDate || '-' }}</span></template>
              </el-table-column>
              <el-table-column label="下次" width="120">
                <template #default="{ row }"><span class="mono">{{ row.nextDate }}</span></template>
              </el-table-column>
              <el-table-column label="剩余" width="110">
                <template #default="{ row }">
                  <span v-if="row.overdue" class="status-tag danger"><i></i>逾期 {{ -row.daysLeft }} 天</span>
                  <span v-else class="num">{{ row.daysLeft }} 天</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="170" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openExecute(row)">登记保养</el-button>
                  <el-button link size="small" @click="openPlan(row)">编辑</el-button>
                  <el-button link type="danger" size="small" @click="removePlan(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="section-head">
            <span>保养记录</span>
            <span class="muted">最近 200 条</span>
          </div>
          <div v-loading="logsLoading" class="table-wrap">
            <el-table :data="logs" size="small" empty-text="还没有保养记录">
              <el-table-column label="设备" min-width="160">
                <template #default="{ row }">{{ row.deviceName }}（{{ row.deviceCode }}）</template>
              </el-table-column>
              <el-table-column prop="doneDate" label="保养日期" width="120">
                <template #default="{ row }"><span class="mono">{{ row.doneDate }}</span></template>
              </el-table-column>
              <el-table-column prop="worker" label="执行人" width="120" />
              <el-table-column label="费用" width="110" align="right">
                <template #default="{ row }"><span class="num">{{ fmtMoney(row.cost) }}</span></template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="200">
                <template #default="{ row }">{{ row.remark || '-' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="故障记录" name="faults">
          <div class="filter-bar">
            <el-select v-model="faultStatus" placeholder="全部状态" clearable style="width: 130px" @change="reloadFaults">
              <el-option label="待处理" value="OPEN" />
              <el-option label="已关闭" value="CLOSED" />
            </el-select>
            <el-button :loading="faultLoading" @click="loadFaults">刷新</el-button>
            <div class="spacer"></div>
            <el-button type="danger" plain @click="openFault">设备报修</el-button>
          </div>
          <div v-loading="faultLoading" class="table-wrap">
            <el-table :data="faults" size="small" empty-text="没有故障记录">
              <el-table-column label="故障单" width="150">
                <template #default="{ row }"><span class="mono">{{ row.faultNo }}</span></template>
              </el-table-column>
              <el-table-column label="设备" min-width="170">
                <template #default="{ row }">
                  <div>{{ row.deviceName }}</div>
                  <div class="sub mono">{{ row.deviceCode }} · {{ row.workCenterName }}</div>
                </template>
              </el-table-column>
              <el-table-column prop="symptom" label="故障现象" min-width="200" show-overflow-tooltip />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <span class="status-tag" :class="statusOf(row.status).cls"><i></i>{{ statusOf(row.status).label }}</span>
                </template>
              </el-table-column>
              <el-table-column label="停机" width="100" align="right">
                <template #default="{ row }">
                  <span class="num">{{ row.downtimeMinutes === null ? '-' : `${row.downtimeMinutes} 分` }}</span>
                </template>
              </el-table-column>
              <el-table-column label="报修时间" width="150">
                <template #default="{ row }"><span class="mono muted">{{ row.occurredAt }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="100" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="row.status === 'OPEN'" link type="primary" size="small" @click="openClose(row)">
                    关闭
                  </el-button>
                  <span v-else class="muted">已关闭</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div class="pager">
            <el-pagination
              v-model:current-page="faultPage"
              v-model:page-size="faultSize"
              :total="faultTotal"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @current-change="changeFaultPage"
              @size-change="loadFaults"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-drawer append-to-body v-model="taskDrawer" :title="`点检 · ${taskDetail?.deviceName || ''}`" size="640px">
      <template v-if="taskDetail">
        <div class="task-meta">
          <span>任务号 {{ taskDetail.taskNo }}</span>
          <span>{{ taskDetail.planDate }}</span>
          <span>{{ taskDetail.workCenterName }}</span>
        </div>
        <el-table :data="results" size="small" empty-text="该设备没有点检项，请先到「点检项」维护">
          <el-table-column prop="itemName" label="点检项" min-width="150" />
          <el-table-column label="方法" width="80">
            <template #default="{ row }">
              <span class="muted">{{ row.method || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="标准" min-width="140">
            <template #default="{ row }">
              <span class="muted">{{ row.standard || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="130">
            <template #default="{ row }">
              <el-radio-group v-model="row.result" size="small">
                <el-radio-button value="OK">正常</el-radio-button>
                <el-radio-button value="NG">异常</el-radio-button>
              </el-radio-group>
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.remark" size="small" placeholder="选填" />
            </template>
          </el-table-column>
        </el-table>
        <el-form-item label="点检说明" class="task-remark">
          <el-input v-model="submitRemark" type="textarea" :rows="2" placeholder="选填，例如异响位置、处理方式" />
        </el-form-item>
      </template>
      <template #footer>
        <el-button @click="taskDrawer = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCheck">提交点检</el-button>
      </template>
    </el-drawer>

    <el-drawer append-to-body v-model="itemDrawer" :title="editingItemId ? '编辑点检项' : '新增点检项'" size="480px">
      <el-form label-position="top">
        <el-form-item label="适用设备">
          <ReferenceSelect v-model="itemForm.deviceId" url="/devices/options"
                           :initial-label="itemForm.deviceLabel"
                           placeholder="留空表示所有设备通用" />
        </el-form-item>
        <el-form-item label="点检项名称" required>
          <el-input v-model="itemForm.name" placeholder="例如 液压油位" />
        </el-form-item>
        <el-form-item label="点检方法">
          <el-select v-model="itemForm.method" style="width: 100%">
            <el-option label="目视" value="目视" />
            <el-option label="听音" value="听音" />
            <el-option label="手感" value="手感" />
            <el-option label="测量" value="测量" />
          </el-select>
        </el-form-item>
        <el-form-item label="判定标准">
          <el-input v-model="itemForm.standard" placeholder="例如 油位在刻度 1/2 以上" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="itemForm.sort" :min="0" :max="999" style="width: 100%" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="itemForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDrawer = false">取消</el-button>
        <el-button type="primary" @click="saveItem">保存</el-button>
      </template>
    </el-drawer>

    <el-drawer append-to-body v-model="planDrawer" :title="editingPlanId ? '编辑保养计划' : '新增保养计划'" size="480px">
      <el-form label-position="top">
        <el-form-item label="设备" required>
          <ReferenceSelect v-model="planForm.deviceId" url="/devices/options"
                           :initial-label="planForm.deviceLabel"
                           placeholder="输入设备编码或名称搜索" />
        </el-form-item>
        <el-form-item label="保养项目" required>
          <el-input v-model="planForm.name" placeholder="例如 更换液压油" />
        </el-form-item>
        <el-form-item label="周期（天）">
          <el-input-number v-model="planForm.cycleDays" :min="1" :max="3650" style="width: 100%" />
        </el-form-item>
        <el-form-item label="上次保养日期">
          <el-date-picker v-model="planForm.lastDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="下次日期（留空按上次 + 周期自动算）">
          <el-date-picker v-model="planForm.nextDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="保养标准">
          <el-input v-model="planForm.standard" placeholder="选填，例如油品型号与用量" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="planForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDrawer = false">取消</el-button>
        <el-button type="primary" @click="savePlan">保存</el-button>
      </template>
    </el-drawer>

    <el-dialog append-to-body draggable v-model="executeDialog" title="登记保养" width="460px">
      <el-form label-position="top">
        <el-form-item label="设备">
          <el-input :model-value="`${executeRow?.deviceName || ''} · ${executeRow?.name || ''}`" disabled />
        </el-form-item>
        <el-form-item label="保养日期" required>
          <el-date-picker v-model="executeForm.doneDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="执行人">
          <el-input v-model="executeForm.worker" placeholder="留空取当前登录人" />
        </el-form-item>
        <el-form-item label="费用（元）">
          <el-input-number v-model="executeForm.cost" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="executeForm.remark" type="textarea" :rows="2" placeholder="换了什么、用量多少" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="executeDialog = false">取消</el-button>
        <el-button type="primary" @click="submitExecute">确认完成</el-button>
      </template>
    </el-dialog>

    <el-dialog append-to-body draggable v-model="faultDialog" title="设备报修" width="460px">
      <el-form label-position="top">
        <el-form-item label="设备" required>
          <ReferenceSelect v-model="faultForm.deviceId" url="/devices/options"
                           placeholder="输入设备编码或名称搜索" />
        </el-form-item>
        <el-form-item label="故障现象" required>
          <el-input v-model="faultForm.symptom" type="textarea" :rows="3" placeholder="例如主轴异响、无法启动" />
        </el-form-item>
        <div class="dialog-tip">报修后设备会置为「停机」，不计入可用产能；修复后在故障记录里关闭并填写停机时长。</div>
      </el-form>
      <template #footer>
        <el-button @click="faultDialog = false">取消</el-button>
        <el-button type="danger" @click="submitFault">确认报修</el-button>
      </template>
    </el-dialog>

    <el-dialog append-to-body draggable v-model="closeDialog" title="关闭故障" width="480px">
      <el-form label-position="top">
        <el-form-item label="故障单">
          <el-input :model-value="`${closeRow?.faultNo || ''} · ${closeRow?.deviceName || ''}`" disabled />
        </el-form-item>
        <el-form-item label="故障原因">
          <el-input v-model="closeForm.cause" placeholder="例如液压泵密封老化" />
        </el-form-item>
        <el-form-item label="处理措施">
          <el-input v-model="closeForm.action" type="textarea" :rows="2" placeholder="换了什么件、谁处理的" />
        </el-form-item>
        <el-form-item label="处理人">
          <el-input v-model="closeForm.handler" placeholder="留空取当前登录人" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeDialog = false">取消</el-button>
        <el-button type="primary" @click="submitClose">确认关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kpi-panel {
  padding: 16px;
  margin-bottom: 14px;
}

.kpi-grid.kpi-4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

@media (max-width: 1100px) {
  .kpi-grid.kpi-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.sub {
  font-size: 11px;
  color: var(--muted);
}

.task-meta {
  display: flex;
  gap: 16px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--muted);
}

.task-remark {
  margin-top: 14px;
  display: block;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 18px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.section-head .muted {
  font-size: 12px;
  font-weight: 400;
}

.dialog-tip {
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}
</style>
