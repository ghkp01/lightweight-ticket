<script setup>
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { noticeApi } from '../../api'
import { ensureAuth } from '../../utils/auth'
import { shortTime } from '../../utils/format'
import EmptyState from '../../components/EmptyState.vue'
import MesWatermark from '../../components/Watermark.vue'

const rows = ref([])
const summary = ref({ unread: 0, total: 0 })
const unreadOnly = ref(false)
const loading = ref(false)
const loadedOnce = ref(false)
const error = ref('')
const expanded = ref(null)

const CATEGORY = {
  ALERT: { label: '预警', tone: 'alert' },
  TASK: { label: '待办', tone: 'task' },
  INFO: { label: '通知', tone: 'info' }
}

const unread = computed(() => Number(summary.value.unread || 0))

const categoryOf = (value) => CATEGORY[value] || { label: value || '通知', tone: 'info' }

const load = async () => {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const [list, sum] = await Promise.all([
      noticeApi.list({ unreadOnly: unreadOnly.value ? true : undefined, limit: 100 }),
      noticeApi.summary()
    ])
    rows.value = list || []
    summary.value = sum || { unread: 0, total: 0 }
    loadedOnce.value = true
  } catch (e) {
    error.value = '通知加载失败，请稍后重试'
  } finally {
    loading.value = false
    uni.stopPullDownRefresh()
  }
}

const toggleFilter = () => {
  unreadOnly.value = !unreadOnly.value
  load()
}

const readOne = async (item) => {
  expanded.value = expanded.value === item.id ? null : item.id
  if (item.readAt) return
  try {
    await noticeApi.read(item.id)
    item.readAt = '已读'
    summary.value = { ...summary.value, unread: Math.max(0, unread.value - 1) }
    if (unreadOnly.value) {
      rows.value = rows.value.filter((row) => row.id !== item.id)
    }
  } catch (e) {
    // request 已统一提示
  }
}

const readAll = async () => {
  if (!unread.value) {
    uni.showToast({ title: '没有未读通知', icon: 'none' })
    return
  }
  try {
    await noticeApi.readAll()
    uni.showToast({ title: '已全部标记为已读', icon: 'none' })
    await load()
  } catch (e) {
    // request 已统一提示
  }
}

const REF_MODULE = {
  WORK_ORDER: '生产工单',
  DEFECT: '不良处置',
  INSPECTION: '质量检验',
  RETURN: '退货管理',
  INBOUND: '入库管理',
  ANDON: '异常安灯',
  REQUISITION: '采购申请',
  SHIPMENT: '发货管理'
}

const refModule = (item) => REF_MODULE[String(item.refType || '').toUpperCase()] || ''

const openRef = (item) => {
  const refType = String(item.refType || '').toUpperCase()
  const refNo = String(item.refNo || '').trim()
  if (!refNo) return
  // 带上单据号跳到对应列表，落地就是这一条，不用自己再搜
  if (refType === 'WORK_ORDER') {
    uni.setStorageSync('mes.workorder.keyword', refNo)
    uni.switchTab({ url: '/pages/workorder/list' })
    return
  }
  if (refType === 'DEFECT') {
    uni.setStorageSync('mes.defect.keyword', refNo)
    uni.navigateTo({ url: '/pages/defect/list' })
    return
  }
  if (refType === 'INSPECTION' || refType === 'RETURN') {
    uni.navigateTo({ url: '/pages/quality/list' })
    return
  }
  if (refType === 'INBOUND') {
    uni.navigateTo({ url: '/pages/inbound/list' })
    return
  }
  if (refType === 'ANDON' || refType === 'REQUISITION') {
    uni.showToast({ title: '该单据请在 PC 端处理', icon: 'none' })
    return
  }
  uni.showToast({ title: '该单据请在 PC 端查看', icon: 'none' })
}

onShow(() => {
  if (!ensureAuth()) return
  load()
})

onPullDownRefresh(() => {
  load()
})
</script>

<template>
  <view class="mes-page notice-page">
    <MesWatermark />
    <view class="hero notice-hero">
      <view class="hero-inner">
        <view class="row between">
          <view>
            <view class="hero-eyebrow">NOTIFICATIONS</view>
            <view class="hero-title">通知中心</view>
          </view>
          <view class="unread-block">
            <text class="unread-value">{{ unread }}</text>
            <text class="unread-label">未读</text>
          </view>
        </view>
        <view class="notice-actions">
          <view class="filter-toggle" :class="{ active: unreadOnly }" @tap="toggleFilter">
            {{ unreadOnly ? '只看未读' : '全部通知' }}
          </view>
          <view class="read-all" @tap="readAll">全部已读</view>
        </view>
      </view>
    </view>

    <view v-if="error" class="error-banner">
      <text>{{ error }}</text>
      <text class="retry" @tap="load">重试</text>
    </view>

    <view v-if="!loadedOnce && loading" class="loading-block">正在加载通知…</view>

    <view v-else-if="!rows.length" class="empty-wrap">
      <EmptyState
        mark="信"
        text="暂无通知"
        :hint="unreadOnly ? '当前没有未读通知' : '报工不良、检验不合格、退货与返修都会推送到这里'"
      />
    </view>

    <view v-else class="list-wrap">
      <view
        v-for="item in rows"
        :key="item.id"
        class="list-card notice-card"
        :class="{ unread: !item.readAt }"
        @tap="readOne(item)"
      >
        <view class="row between">
          <view class="row gap8">
            <text class="notice-tag" :class="categoryOf(item.category).tone">
              {{ categoryOf(item.category).label }}
            </text>
            <text class="notice-title ellipsis">{{ item.title }}</text>
          </view>
          <text v-if="!item.readAt" class="dot"></text>
        </view>

        <view class="notice-content" :class="{ clamp: expanded !== item.id }">
          <text>{{ item.content }}</text>
        </view>

        <view class="notice-foot">
          <text v-if="item.refNo" class="ref-link" @tap.stop="openRef(item)">
            {{ item.refNo }}<text v-if="refModule(item)"> · {{ refModule(item) }} →</text>
          </text>
          <text v-else class="muted">系统通知</text>
          <text class="time">{{ shortTime(item.createdAt) }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.notice-page {
  padding-bottom: calc(60rpx + env(safe-area-inset-bottom));
}

.notice-hero {
  padding-top: 30rpx;
  padding-bottom: 26rpx;
}

.unread-block {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.unread-value {
  color: #fdba74;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 52rpx;
  font-weight: 700;
  line-height: 1;
}

.unread-label {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 19rpx;
}

.notice-actions {
  display: flex;
  align-items: center;
  gap: 14rpx;
  margin-top: 24rpx;
}

.filter-toggle {
  padding: 8rpx 20rpx;
  border: 1rpx solid rgba(148, 163, 184, 0.4);
  border-radius: 999rpx;
  color: #cbd5e1;
  font-size: 21rpx;
}

.filter-toggle.active {
  border-color: #f97316;
  background: rgba(249, 115, 22, 0.16);
  color: #fdba74;
}

.read-all {
  margin-left: auto;
  color: #fdba74;
  font-size: 22rpx;
  font-weight: 600;
}

.error-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16rpx 24rpx 0;
  padding: 16rpx 20rpx;
  border: 1rpx solid #fed7aa;
  border-radius: 10rpx;
  background: #fff7ed;
  color: #c2410c;
  font-size: 22rpx;
}

.retry {
  font-weight: 700;
}

.empty-wrap {
  padding: 20rpx 24rpx;
}

.list-wrap {
  padding: 18rpx 24rpx 0;
}

.notice-card {
  position: relative;
}

.notice-card.unread {
  border-left: 6rpx solid #f97316;
}

.notice-tag {
  flex: none;
  padding: 2rpx 12rpx;
  border-radius: 8rpx;
  font-size: 19rpx;
}

.notice-tag.alert {
  background: #fef2f2;
  color: #dc2626;
}

.notice-tag.task {
  background: #fff7ed;
  color: #c2410c;
}

.notice-tag.info {
  background: #eff6ff;
  color: #1d4ed8;
}

.notice-title {
  max-width: 400rpx;
  color: #0f172a;
  font-size: 26rpx;
  font-weight: 600;
}

.dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 50%;
  background: #f97316;
}

.notice-content {
  margin-top: 14rpx;
  color: #475569;
  font-size: 23rpx;
  line-height: 1.6;
}

.notice-content.clamp {
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.notice-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14rpx;
  font-size: 20rpx;
}

.ref-link {
  color: #2563eb;
  font-weight: 600;
}

.time {
  color: #94a3b8;
}
</style>
