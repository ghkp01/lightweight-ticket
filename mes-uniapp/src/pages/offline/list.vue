<script setup>
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { clearFailed, clearQueue, flush, listFailed, listQueue, queueCount, removeEntry } from '../../utils/offlineQueue'
import { STORAGE_KEYS } from '../../utils/config'
import { ensureAuth } from '../../utils/auth'
import MesWatermark from '../../components/Watermark.vue'

const pending = ref([])
const failed = ref([])
const syncing = ref(false)

const lastSync = computed(() => uni.getStorageSync(STORAGE_KEYS.offlineLastSync) || '还没有同步过')
const lastSyncTime = computed(() => (lastSync.value === '还没有同步过' ? '—' : lastSync.value.slice(-8)))

const refresh = () => {
  pending.value = listQueue()
  failed.value = listFailed()
}

const sync = async () => {
  if (syncing.value) return
  syncing.value = true
  try {
    await flush({ manual: true })
  } finally {
    syncing.value = false
    refresh()
  }
}

const drop = (item) => {
  uni.showModal({
    title: '删除这条记录',
    content: `将放弃「${item.label}」这条待同步记录，删除后无法恢复。`,
    confirmColor: '#f97316',
    success: (res) => {
      if (!res.confirm) return
      removeEntry(item.id)
      refresh()
    }
  })
}

const clearFailedList = () => {
  clearFailed()
  refresh()
}

const clearAll = () => {
  if (!pending.value.length) return
  uni.showModal({
    title: '清空离线队列',
    content: `将放弃全部 ${pending.value.length} 条待同步记录，删除后无法恢复。`,
    confirmColor: '#f97316',
    success: (res) => {
      if (!res.confirm) return
      clearQueue()
      refresh()
      uni.showToast({ title: '已清空', icon: 'success' })
    }
  })
}

onShow(() => {
  if (!ensureAuth()) return
  refresh()
})
</script>

<template>
  <view class="mes-page">
    <MesWatermark />

    <view class="hero offline-hero">
      <view class="hero-inner">
        <text class="hero-eyebrow">OFFLINE QUEUE</text>
        <text class="hero-title">{{ pending.length }} 条待同步</text>
        <text class="hero-sub">断网时的报工、检验、出入库会先存在本机，联网后自动按顺序上传</text>

        <view class="hero-stats">
          <view class="h-stat">
            <text class="h-num">{{ pending.length }}</text>
            <text class="h-label">待上传</text>
          </view>
          <view class="h-line"></view>
          <view class="h-stat">
            <text class="h-num">{{ failed.length }}</text>
            <text class="h-label">同步失败</text>
          </view>
          <view class="h-line"></view>
          <view class="h-stat">
            <text class="h-num small">{{ lastSyncTime }}</text>
            <text class="h-label">最近同步</text>
          </view>
        </view>

        <view class="hero-actions">
          <view class="btn btn-primary flex1" :class="{ disabled: syncing }" @tap="sync">
            {{ syncing ? '正在同步…' : '立即同步' }}
          </view>
          <view class="btn btn-ghost" @tap="clearAll">清空</view>
        </view>
      </view>
    </view>

    <view class="content">
      <view class="panel">
        <view class="section-title">待同步记录</view>
        <view v-if="!pending.length" class="empty">
          <view class="empty-mark">✓</view>
          <text>没有待同步的记录</text>
          <text class="muted tiny">断网时提交的现场动作会自动排在这里</text>
        </view>
        <view v-for="item in pending" :key="item.id" class="queue-row">
          <view class="queue-icon">{{ item.label.slice(0, 1) }}</view>
          <view class="flex1">
            <text class="queue-title">{{ item.label }}</text>
            <text class="queue-sub mono ellipsis">{{ item.method }} {{ item.url }}</text>
            <text class="queue-time">{{ item.queuedAt }} · 键 {{ item.id.slice(-6) }}</text>
          </view>
          <text class="queue-drop" @tap="drop(item)">删除</text>
        </view>
      </view>

      <view v-if="failed.length" class="panel">
        <view class="panel-head">
          <view class="section-title tight">同步失败</view>
          <text class="link" @tap="clearFailedList">清空</text>
        </view>
        <text class="failed-hint">这些记录被服务端拒绝（例如工单已关闭），需要回页面重新录入</text>
        <view v-for="item in failed" :key="item.id + item.failedAt" class="queue-row failed">
          <view class="queue-icon red">{{ item.label.slice(0, 1) }}</view>
          <view class="flex1">
            <text class="queue-title">{{ item.label }}</text>
            <text class="queue-sub">{{ item.message }}</text>
            <text class="queue-time">{{ item.failedAt }}</text>
          </view>
        </view>
      </view>

      <view class="tip-card">
        <text class="tip-title">为什么可以放心离线操作</text>
        <text class="tip-line">1. 每条记录带唯一幂等键，重复上传不会重复计数；</text>
        <text class="tip-line">2. 网络恢复、回到前台时自动补传，也可在这里手动同步；</text>
        <text class="tip-line">3. 服务端按发生顺序处理，先报工后检验不会错乱。</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.offline-hero {
  padding-bottom: 30rpx;
}

.hero-stats {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-top: 26rpx;
}

.h-stat {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.h-num {
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 44rpx;
  font-weight: 700;
  line-height: 1.1;
}

.h-num.small {
  font-size: 30rpx;
  line-height: 44rpx;
}

.h-label {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 21rpx;
}

.h-line {
  width: 1rpx;
  height: 52rpx;
  background: rgba(148, 163, 184, 0.24);
}

.hero-actions {
  display: flex;
  gap: 14rpx;
  margin-top: 28rpx;
}

.hero-actions .btn-ghost {
  background: rgba(148, 163, 184, 0.12);
  border-color: rgba(148, 163, 184, 0.24);
  color: #cbd5e1;
}

.queue-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 20rpx 0;
  border-top: 1rpx solid #f1f5f9;
}

.queue-row:first-of-type {
  border-top: none;
}

.queue-icon {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64rpx;
  height: 64rpx;
  border-radius: 14rpx;
  background: #fff7ed;
  color: #c2410c;
  font-size: 26rpx;
  font-weight: 700;
}

.queue-icon.red {
  background: #fef2f2;
  color: #b91c1c;
}

.queue-title {
  display: block;
  color: #1f2937;
  font-size: 26rpx;
  font-weight: 600;
}

.queue-sub {
  display: block;
  margin-top: 2rpx;
  color: #64748b;
  font-size: 21rpx;
}

.queue-time {
  display: block;
  margin-top: 4rpx;
  color: #b6c2d1;
  font-size: 19rpx;
}

.queue-drop {
  flex: none;
  padding: 6rpx 14rpx;
  border-radius: 8rpx;
  background: #f8fafc;
  color: #94a3b8;
  font-size: 21rpx;
}

.failed-hint {
  display: block;
  margin: 6rpx 0 10rpx;
  color: #94a3b8;
  font-size: 21rpx;
  line-height: 1.6;
}

.tip-card {
  padding: 26rpx;
  margin-bottom: 20rpx;
  border: 1rpx solid #fed7aa;
  border-radius: 18rpx;
  background: linear-gradient(135deg, #fff7ed 0%, #ffffff 70%);
}

.tip-title {
  display: block;
  margin-bottom: 10rpx;
  color: #9a3412;
  font-size: 27rpx;
  font-weight: 700;
}

.tip-line {
  display: block;
  color: #7c2d12;
  font-size: 22rpx;
  line-height: 1.7;
}

.link {
  color: #c2410c;
  font-size: 22rpx;
}

.empty {
  padding: 50rpx 20rpx 30rpx;
  gap: 6rpx;
}
</style>
