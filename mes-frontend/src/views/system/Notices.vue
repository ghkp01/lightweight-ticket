<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import DataTable from '../../components/DataTable.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import { useTableView } from '../../composables/useTableView'

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const summary = ref<any>({ total: 0, unread: 0, today: 0, task: 0, alert: 0 })
const detail = ref<any>(null)
const drawer = ref(false)

const filters = reactive({
  keyword: '',
  receiver: '',
  category: '',
  readStatus: '',
  range: [] as string[]
})

const categoryMap: Record<string, { label: string; cls: string }> = {
  TASK: { label: '待办', cls: 'warning' },
  ALERT: { label: '预警', cls: 'danger' },
  INFO: { label: '通知', cls: 'info' }
}

const categoryOf = (value: any) =>
  categoryMap[String(value || '')] || { label: '通知', cls: 'info' }

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'system.notices',
  columns: [
    { key: 'createdAt', label: '时间', width: 150 },
    { key: 'receiver', label: '收件人', width: 160 },
    { key: 'category', label: '分类', width: 80 },
    { key: 'title', label: '标题', width: 260, flex: true, ellipsis: true },
    { key: 'content', label: '内容', width: 260, flex: true, ellipsis: true },
    { key: 'refNo', label: '单据号', width: 160, ellipsis: true },
    { key: 'readAt', label: '已读', width: 80, prop: '' },
    { key: 'actions', label: '操作', width: 90, fixed: 'right', prop: '' }
  ],
  getFilters: () => ({
    keyword: filters.keyword,
    receiver: filters.receiver,
    category: filters.category,
    readStatus: filters.readStatus,
    range: filters.range
  }),
  setFilters: (payload) => {
    filters.keyword = payload.keyword || ''
    filters.receiver = payload.receiver || ''
    filters.category = payload.category || ''
    filters.readStatus = payload.readStatus || ''
    filters.range = Array.isArray(payload.range) ? payload.range : []
  }
})

const reload = () => {
  page.value = 1
  load()
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/system/notices', {
      keyword: filters.keyword || undefined,
      receiver: filters.receiver || undefined,
      category: filters.category || undefined,
      readStatus: filters.readStatus || undefined,
      from: filters.range?.[0] || undefined,
      to: filters.range?.[1] || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = data?.records || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

const loadSummary = async () => {
  try {
    summary.value = (await api.get('/system/notices/summary')) || summary.value
  } catch {
    // 统计失败不影响列表
  }
}

const changePage = (value: number) => {
  page.value = value
  load()
}

const openDetail = (row: any) => {
  detail.value = row
  drawer.value = true
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(
    `删除后这条消息不会再出现在收件人的消息中心，确定删除「${row.title}」？`,
    '删除消息',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  )
  await api.delete(`/system/notices/${row.id}`)
  ElMessage.success('已删除')
  await Promise.all([load(), loadSummary()])
}

const purge = async (days: number) => {
  const label = days > 0 ? `清理 ${days} 天前的已读消息` : '清空全部已读消息'
  await ElMessageBox.confirm(
    `${label}，未读消息不受影响。清理后不可恢复，确定继续？`,
    '清理已读消息',
    { type: 'warning', confirmButtonText: '清理', cancelButtonText: '取消' }
  )
  const removed = await api.post('/system/notices/purge-read', { days })
  ElMessage.success(`已清理 ${removed || 0} 条已读消息`)
  await Promise.all([load(), loadSummary()])
}

onMounted(async () => {
  await table.load()
  await Promise.all([load(), loadSummary()])
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">通知管理</h2>
        <p class="page-sub">
          所有人的站内消息都在这里：按收件人 / 分类 / 已读状态 / 时间查，看谁收到了什么、有没有点开，过期已读可以批量清理
        </p>
      </div>
      <div class="page-actions">
        <el-dropdown trigger="click" @command="purge">
          <el-button plain>清理已读 ▾</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item :command="30">清理 30 天前的已读</el-dropdown-item>
              <el-dropdown-item :command="7">清理 7 天前的已读</el-dropdown-item>
              <el-dropdown-item :command="0">清空全部已读</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button plain @click="load(); loadSummary()">刷新</el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-label">消息总数</div>
        <div class="kpi-value">{{ summary.total || 0 }}<small>条</small></div>
        <div class="kpi-foot">全部收件人的站内消息</div>
      </div>
      <div class="kpi-card" :class="{ 'kpi-alert': Number(summary.unread || 0) > 0 }">
        <div class="kpi-label">未读</div>
        <div class="kpi-value">{{ summary.unread || 0 }}<small>条</small></div>
        <div class="kpi-foot">还没被点开的消息</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">今日新增</div>
        <div class="kpi-value">{{ summary.today || 0 }}<small>条</small></div>
        <div class="kpi-foot">今天落库的消息</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">待办</div>
        <div class="kpi-value">{{ summary.task || 0 }}<small>条</small></div>
        <div class="kpi-foot">TASK 类，需要人处理</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">预警</div>
        <div class="kpi-value">{{ summary.alert || 0 }}<small>条</small></div>
        <div class="kpi-foot">ALERT 类，异常提醒</div>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input
          v-model="filters.keyword"
          placeholder="标题 / 内容 / 单据号"
          clearable
          style="width: 220px"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-input
          v-model="filters.receiver"
          placeholder="收件人账号 / 姓名"
          clearable
          style="width: 170px"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-select v-model="filters.category" placeholder="全部分类" clearable style="width: 120px" @change="reload">
          <el-option label="待办" value="TASK" />
          <el-option label="预警" value="ALERT" />
          <el-option label="通知" value="INFO" />
        </el-select>
        <el-select v-model="filters.readStatus" placeholder="全部状态" clearable style="width: 120px" @change="reload">
          <el-option label="未读" value="UNREAD" />
          <el-option label="已读" value="READ" />
        </el-select>
        <el-date-picker
          v-model="filters.range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 240px"
          @change="reload"
        />
        <el-button type="primary" plain @click="reload">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/system/notices" export-name="消息台账" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 条</span>
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
          <template #cell-createdAt="{ row }"><span class="mono">{{ row.createdAt }}</span></template>
          <template #cell-receiver="{ row }">
            <div>{{ row.receiverName || row.receiver }}</div>
            <div class="muted" style="font-size: 11px">{{ row.receiver }}</div>
          </template>
          <template #cell-category="{ row }">
            <span class="status-tag" :class="categoryOf(row.category).cls">
              <i></i>{{ categoryOf(row.category).label }}
            </span>
          </template>
          <template #cell-content="{ row }">
            <span class="muted">{{ row.content || '-' }}</span>
          </template>
          <template #cell-refNo="{ row }">
            <span v-if="row.refNo" class="mono">{{ row.refNo }}</span>
            <span v-else class="muted">-</span>
          </template>
          <template #cell-readAt="{ row }">
            <span class="status-tag" :class="row.readAt ? 'success' : 'warning'">
              <i></i>{{ row.readAt ? '已读' : '未读' }}
            </span>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <el-drawer v-model="drawer" title="消息详情" size="440px">
      <template v-if="detail">
        <div class="detail-head">
          <span class="status-tag" :class="categoryOf(detail.category).cls">
            <i></i>{{ categoryOf(detail.category).label }}
          </span>
          <span class="muted">{{ detail.createdAt }}</span>
        </div>
        <h3 class="detail-title">{{ detail.title }}</h3>
        <p class="detail-content">{{ detail.content || '无正文' }}</p>
        <div class="detail-grid">
          <div><span class="muted">收件人</span><b>{{ detail.receiverName || detail.receiver }}</b></div>
          <div><span class="muted">账号</span><b class="mono">{{ detail.receiver }}</b></div>
          <div><span class="muted">单据类型</span><b>{{ detail.refType || '-' }}</b></div>
          <div><span class="muted">单据号</span><b class="mono">{{ detail.refNo || '-' }}</b></div>
          <div><span class="muted">已读时间</span><b>{{ detail.readAt || '未读' }}</b></div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}

.detail-title {
  margin: 12px 0 8px;
  font-size: 16px;
  color: var(--text);
}

.detail-content {
  margin: 0 0 16px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  font-size: 12px;
}

.detail-grid > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
