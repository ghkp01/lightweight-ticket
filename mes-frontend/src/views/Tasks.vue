<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAuthStore } from '../stores/auth'
import { noticeTarget, openNoticeTarget } from '../utils/noticeLink'

/**
 * 我的任务：全流程每个交接点落在谁头上、做没做、什么时候到期。
 * 任务是系统在关键节点自动开的（订单转生产→算料、工单下达→派工/领料、完工检→发货），
 * 管理岗可以把角色池里的活指到具体某个人，指完对方会收到站内信。
 */
const auth = useAuthStore()
const router = useRouter()

const isManager = computed(() => ['ADMIN', 'PLANNER'].includes(String(auth.user?.role || '')))
const canAssign = computed(() => auth.can('task:assign'))

const tab = ref<'mine' | 'all'>('mine')
const status = ref('OPEN')
const type = ref('ALL')
const keyword = ref('')
const loading = ref(false)
const rows = ref<any[]>([])
const summary = ref({ open: 0, overdue: 0, unclaimed: 0 })
const users = ref<any[]>([])

const TYPES = [
  { value: 'ALL', label: '全部类型' },
  { value: 'MRP_RUN', label: '算料（MRP）' },
  { value: 'WO_ASSIGN', label: '派工' },
  { value: 'WO_PICK', label: '领料' },
  { value: 'SHIP_READY', label: '待发货' },
  { value: 'REQ_CONVERT', label: '转采购单' }
]

const STATUS = [
  { value: 'OPEN', label: '未完成' },
  { value: 'DONE', label: '已完成' },
  { value: 'ALL', label: '全部' }
]

const typeLabel = (value: any) => TYPES.find((item) => item.value === value)?.label || value || '任务'

const priorityMap: Record<string, { label: string; cls: string }> = {
  URGENT: { label: '紧急', cls: 'danger' },
  HIGH: { label: '高', cls: 'warning' },
  NORMAL: { label: '普通', cls: 'info' },
  LOW: { label: '低', cls: 'info' }
}

const priorityOf = (value: any) =>
  priorityMap[String(value || '')] || { label: '普通', cls: 'info' }

/** 角色名，任务没指到人时显示「计划（待认领）」这类提示 */
const roleName = (value: any) => {
  const map: Record<string, string> = {
    ADMIN: '管理员',
    SALES: '销售',
    PLANNER: '计划',
    WAREHOUSE: '仓库',
    QC: '质检'
  }
  return map[String(value || '')] || String(value || '-')
}

const load = async () => {
  loading.value = true
  try {
    const typeParam = type.value === 'ALL' ? undefined : type.value
    if (tab.value === 'mine') {
      rows.value = (await api.get('/tasks/my', { status: status.value, type: typeParam })) || []
    } else {
      rows.value =
        (await api.get('/tasks', {
          status: status.value,
          type: typeParam,
          keyword: keyword.value || undefined
        })) || []
    }
    summary.value = (await api.get('/tasks/summary')) || { open: 0, overdue: 0, unclaimed: 0 }
  } finally {
    loading.value = false
  }
}

const switchTab = async (value: string | number) => {
  tab.value = value === 'all' ? 'all' : 'mine'
  await load()
}

const openTarget = async (row: any) => {
  const jumped = await openNoticeTarget(row, router)
  if (!jumped) {
    ElMessage.info('这条任务没有可跳转的单据，请到对应模块按单号搜索')
  }
}

const finish = async (row: any) => {
  let note = ''
  try {
    const result = await ElMessageBox.prompt('写一句处理结果，后面接手的人能看懂', '完成任务', {
      inputPlaceholder: '例如：已生成采购申请，缺口 320kg 下周到货',
      confirmButtonText: '确认完成',
      cancelButtonText: '取消'
    })
    note = (result as any).value || ''
  } catch {
    return
  }
  await api.post(`/tasks/${row.id}/done`, { note })
  ElMessage.success('任务已完成')
  await load()
}

const assignDialog = ref(false)
const assignForm = ref<{ id: number; title: string; username: string }>({ id: 0, title: '', username: '' })

const openAssign = async (row: any) => {
  if (!users.value.length) {
    try {
      users.value = (await api.get('/system/users/options')) || []
    } catch {
      users.value = []
    }
  }
  assignForm.value = { id: row.id, title: row.title, username: row.assigneeUsername || '' }
  assignDialog.value = true
}

const submitAssign = async () => {
  await api.post(`/tasks/${assignForm.value.id}/assign`, {
    username: assignForm.value.username || null
  })
  assignDialog.value = false
  ElMessage.success(assignForm.value.username ? '已改派，并通知到本人' : '已退回角色池')
  await load()
}

const goMrp = () => router.push('/purchase/mrp?tab=workorder')

onMounted(load)
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的任务</h2>
        <p class="page-sub">
          订单转生产后谁算料、工单下达后谁派工谁领料、完工检合格后谁发货，都落在这里，一条一条有人负责
        </p>
      </div>
      <div class="head-actions">
        <span class="stat">未完成 <b>{{ summary.open }}</b></span>
        <span class="stat" :class="{ hot: summary.overdue > 0 }">逾期 <b>{{ summary.overdue }}</b></span>
        <span class="stat">未认领 <b>{{ summary.unclaimed }}</b></span>
        <el-button plain @click="goMrp">去算料</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-radio-group :model-value="tab" size="small" @change="switchTab">
          <el-radio-button label="mine">我的任务</el-radio-button>
          <el-radio-button v-if="isManager" label="all">全部任务</el-radio-button>
        </el-radio-group>
        <el-select v-model="type" size="small" style="width: 150px" @change="load">
          <el-option v-for="item in TYPES" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="status" size="small" style="width: 120px" @change="load">
          <el-option v-for="item in STATUS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-input
          v-if="tab === 'all'"
          v-model="keyword"
          size="small"
          placeholder="任务标题 / 单据号"
          clearable
          style="width: 200px"
          @keyup.enter="load"
        />
        <el-button size="small" @click="load">刷新</el-button>
        <div class="spacer"></div>
        <span class="toolbar-tip">
          任务由系统在交接点自动生成；角色池里没人认领的活，全体同角色可见
        </span>
      </div>

      <el-table v-loading="loading" :data="rows" size="small" empty-text="没有需要处理的任务">
        <el-table-column prop="taskNo" label="任务号" width="110">
          <template #default="{ row }"><span class="mono">{{ row.taskNo }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <span class="type-tag">{{ typeLabel(row.taskType) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="要做什么" min-width="260">
          <template #default="{ row }">
            <div class="task-title">{{ row.title }}</div>
            <div v-if="row.content" class="task-text">{{ row.content }}</div>
            <div v-if="row.doneNote" class="task-done">完成说明：{{ row.doneNote }}</div>
          </template>
        </el-table-column>
        <el-table-column label="单据" width="170">
          <template #default="{ row }">
            <span v-if="noticeTarget(row)" class="link" @click="openTarget(row)">
              {{ row.refNo }} →
            </span>
            <span v-else class="muted">{{ row.refNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="负责人" width="140">
          <template #default="{ row }">
            <span v-if="row.assigneeName">{{ row.assigneeName }}</span>
            <span v-else class="unclaimed">{{ roleName(row.assigneeRole) }}（待认领）</span>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="90">
          <template #default="{ row }">
            <span class="status-tag" :class="priorityOf(row.priority).cls">
              <i></i>{{ priorityOf(row.priority).label }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="要求完成" width="130">
          <template #default="{ row }">
            <span :class="{ overdue: row.overdue }">{{ row.dueDate || '-' }}</span>
            <span v-if="row.overdue" class="overdue-tip">已逾期</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'OPEN'">
              <el-button link type="primary" @click="finish(row)">完成</el-button>
              <el-button v-if="canAssign" link @click="openAssign(row)">
                {{ row.assigneeUsername ? '改派' : '指派' }}
              </el-button>
            </template>
            <span v-else class="muted">{{ row.doneAt || '已完成' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="assignDialog" title="指派任务" width="420px">
      <div class="assign-tip">{{ assignForm.title }}</div>
      <el-select v-model="assignForm.username" placeholder="选一个人，留空则退回角色池" clearable filterable style="width: 100%">
        <el-option
          v-for="user in users"
          :key="user.id"
          :label="user.realName ? `${user.realName}（${user.username}）` : user.username"
          :value="user.username"
        />
      </el-select>
      <div class="assign-hint">指派后对方会收到一条站内信，逾期时每天 09:00 还会提醒一次。</div>
      <template #footer>
        <el-button @click="assignDialog = false">取消</el-button>
        <el-button type="primary" @click="submitAssign">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.stat {
  font-size: 12px;
  color: var(--muted);
}

.stat b {
  color: var(--text);
  font-size: 15px;
  margin-left: 2px;
}

.stat.hot b {
  color: #d92d20;
}

.type-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 20px;
  background: #eef3ff;
  color: #2456d8;
  font-size: 12px;
}

.task-title {
  font-weight: 600;
  color: var(--text);
}

.task-text {
  margin-top: 2px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.task-done {
  margin-top: 2px;
  color: #067647;
  font-size: 12px;
}

.link {
  color: #2456d8;
  cursor: pointer;
  font-weight: 600;
}

.unclaimed {
  color: #b54708;
}

.overdue {
  color: #d92d20;
  font-weight: 600;
}

.overdue-tip {
  margin-left: 4px;
  padding: 0 6px;
  border-radius: 10px;
  background: #fee4e2;
  color: #d92d20;
  font-size: 11px;
}

.assign-tip {
  margin-bottom: 10px;
  color: var(--text);
  font-size: 13px;
}

.assign-hint {
  margin-top: 10px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>
