<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { noticeTarget, openNoticeTarget } from '../utils/noticeLink'
import {
  desktopEnabled,
  desktopPermission,
  desktopPermissionText,
  disableDesktopNotice,
  enableDesktopNotice,
  testDesktopNotice
} from '../utils/desktopNotify'

const router = useRouter()

const loading = ref(false)
const rows = ref<any[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const summary = ref<{ unread: number; total: number }>({ unread: 0, total: 0 })
const category = ref('ALL')
const unreadOnly = ref(false)
const pushInfo = ref<any>({ provider: 'NONE', enabled: false, hint: '' })

// 浏览器桌面通知（在线时）
const desktopOn = ref(desktopEnabled())
const desktopState = ref(desktopPermission())
const desktopHint = ref(desktopPermissionText())

const refreshDesktopState = () => {
  desktopState.value = desktopPermission()
  desktopOn.value = desktopEnabled()
  desktopHint.value = desktopPermissionText()
}

const toggleDesktop = async (value: boolean | string | number) => {
  if (value) {
    const result = await enableDesktopNotice()
    if (!result.ok) {
      ElMessage.warning(result.message)
    } else {
      ElMessage.success(result.message)
    }
  } else {
    disableDesktopNotice()
    ElMessage.info('已关闭桌面通知')
  }
  refreshDesktopState()
}

const sendTestNotice = () => {
  if (!testDesktopNotice()) {
    ElMessage.warning('桌面通知还没开启，请先打开开关')
    refreshDesktopState()
  }
}

const CATEGORY = [
  { value: 'ALL', label: '全部' },
  { value: 'TASK', label: '待办' },
  { value: 'ALERT', label: '预警' },
  { value: 'INFO', label: '通知' }
]

const categoryMap: Record<string, { label: string; cls: string }> = {
  TASK: { label: '待办', cls: 'warning' },
  ALERT: { label: '预警', cls: 'danger' },
  INFO: { label: '通知', cls: 'info' }
}

const categoryOf = (value: any) =>
  categoryMap[String(value || '')] || { label: '通知', cls: 'info' }

const emptyText = computed(() => {
  if (unreadOnly.value) return '当前没有未读消息'
  return category.value === 'ALL' ? '暂无消息' : '这个分类下还没有消息'
})

const reload = () => {
  page.value = 1
  load()
}

const changePage = (value: number) => {
  page.value = value
  load()
}

const load = async () => {
  loading.value = true
  try {
    const [data, sum] = await Promise.all([
      api.get('/notices', {
        category: category.value === 'ALL' ? undefined : category.value,
        unreadOnly: unreadOnly.value || undefined,
        page: page.value,
        size: size.value
      }),
      api.get('/notices/summary')
    ])
    rows.value = data?.records || []
    total.value = data?.total || 0
    summary.value = sum || { unread: 0, total: 0 }
  } finally {
    loading.value = false
  }
}

const readNotice = async (item: any) => {
  if (item.readAt) return
  await api.put(`/notices/${item.id}/read`)
  item.readAt = '已读'
  summary.value = { ...summary.value, unread: Math.max(0, Number(summary.value.unread || 0) - 1) }
  // 只看未读时读掉一条要从当前页补位，避免出现空行与总数不一致
  if (unreadOnly.value) {
    await load()
  }
}

const handleClick = async (item: any) => {
  const jumped = await openNoticeTarget(item, router, readNotice)
  if (!jumped) {
    ElMessage.info('这条消息没有关联单据，已标记为已读')
  }
}

const readAll = async () => {
  if (!summary.value.unread) {
    ElMessage.info('没有未读消息')
    return
  }
  await api.put('/notices/read-all')
  ElMessage.success('已全部标记为已读')
  await load()
}

onMounted(async () => {
  refreshDesktopState()
  await load()
  try {
    pushInfo.value = (await api.get('/push/config')) || pushInfo.value
  } catch {
    // 推送配置取不到不影响消息列表
  }
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">消息中心</h2>
        <p class="page-sub">
          待办、预警、处理结果都在这里；点一条消息直接跳到对应单据，能处理的地方不用再找
        </p>
      </div>
      <el-button :disabled="!summary.unread" @click="readAll">全部标为已读</el-button>
    </div>

    <div class="panel">
      <div class="desktop-bar">
        <div class="desktop-text">
          <b>桌面通知</b>
          <span class="muted">{{ desktopHint }}</span>
        </div>
        <div class="desktop-actions">
          <el-button v-if="desktopOn" size="small" @click="sendTestNotice">发一条测试</el-button>
          <el-switch
            v-model="desktopOn"
            :disabled="desktopState === 'unsupported'"
            active-text="开启"
            @change="toggleDesktop"
          />
        </div>
      </div>

      <div class="filter-bar">
        <el-radio-group v-model="category" size="small" @change="reload">
          <el-radio-button v-for="item in CATEGORY" :key="item.value" :label="item.value">
            {{ item.label }}
          </el-radio-button>
        </el-radio-group>
        <el-checkbox v-model="unreadOnly" label="只看未读" border size="small" @change="reload" />
        <el-button size="small" @click="load">刷新</el-button>
        <div class="spacer"></div>
        <span class="toolbar-tip">未读 {{ summary.unread }} 条 · 当前筛选 {{ total }} 条</span>
      </div>

      <div v-if="pushInfo && !pushInfo.enabled" class="push-tip">
        当前没有接入手机推送，消息只到站内；手机端可登记设备，接入厂商推送或公众号后同一条消息会顺带推到手机。
      </div>

      <div v-loading="loading" class="notice-list">
        <div
          v-for="item in rows"
          :key="item.id"
          class="notice-row"
          :class="{ unread: !item.readAt, jumpable: !!noticeTarget(item) }"
          @click="handleClick(item)"
        >
          <span class="status-tag" :class="categoryOf(item.category).cls">
            <i></i>{{ categoryOf(item.category).label }}
          </span>
          <div class="notice-main">
            <div class="notice-title">{{ item.title }}</div>
            <div v-if="item.content" class="notice-text">{{ item.content }}</div>
            <div class="notice-meta">
              <span v-if="item.refNo" class="mono">{{ item.refNo }}</span>
              <span class="muted">{{ item.createdAt }}</span>
            </div>
          </div>
          <div class="notice-side">
            <span v-if="noticeTarget(item)" class="notice-jump">
              去处理 · {{ noticeTarget(item)!.module }} →
            </span>
            <span v-else class="muted">无关联单据</span>
            <span v-if="!item.readAt" class="dot"></span>
          </div>
        </div>
        <el-empty v-if="!loading && !rows.length" :description="emptyText" />
      </div>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="changePage"
          @size-change="load"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.desktop-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 14px 16px 0;
  padding: 10px 12px;
  border: 1px solid #e4e7ec;
  border-radius: 10px;
  background: #fbfcfe;
}

.desktop-text {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  min-width: 0;
}

.desktop-text b {
  flex: none;
  color: var(--text);
}

.desktop-text .muted {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.desktop-actions {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
}

.push-tip {
  margin: 0 16px 12px;
  padding: 8px 12px;
  border: 1px dashed #d5e3fb;
  border-radius: 8px;
  background: #f8fbff;
  color: #475467;
  font-size: 12px;
}

.notice-list {
  padding: 0 16px 16px;
  min-height: 160px;
}

.notice-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  margin-bottom: 10px;
  background: #fff;
  transition: 0.16s ease;
}

.notice-row.jumpable {
  cursor: pointer;
}

.notice-row.jumpable:hover {
  border-color: #c3d4f7;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.05);
}

.notice-row.unread {
  background: #f8fbff;
  border-color: #d5e3fb;
}

.notice-main {
  flex: 1;
  min-width: 0;
}

.notice-title {
  font-weight: 600;
  color: var(--text);
}

.notice-text {
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.notice-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
}

.notice-side {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
}

.notice-jump {
  padding: 2px 9px;
  border-radius: 20px;
  background: #eef3ff;
  color: #2456d8;
  font-weight: 600;
}

.notice-row.jumpable:hover .notice-jump {
  background: #2456d8;
  color: #fff;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #2456d8;
}
</style>
