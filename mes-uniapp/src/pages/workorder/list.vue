<script setup>
import { ref } from 'vue'
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { workOrderApi } from '../../api'
import { ensureAuth } from '../../utils/auth'
import { fmtQty, progressOf } from '../../utils/format'
import StatusPill from '../../components/StatusPill.vue'
import EmptyState from '../../components/EmptyState.vue'
import MesWatermark from '../../components/Watermark.vue'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 10
const loading = ref(false)
const finished = ref(false)
const keyword = ref('')
const status = ref('')
const loadedOnce = ref(false)

const statusOptions = [
  { value: '', label: '全部' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'RELEASED', label: '已下达' },
  { value: 'IN_PROGRESS', label: '生产中' },
  { value: 'FINISHED', label: '已完工' },
  { value: 'CLOSED', label: '已关闭' }
]

const load = async (reset = false) => {
  if (loading.value) return
  if (!reset && finished.value) return
  loading.value = true
  try {
    const nextPage = reset ? 1 : page.value + 1
    const data = await workOrderApi.list({
      keyword: keyword.value.trim(),
      status: status.value,
      page: nextPage,
      size
    })
    const records = data?.records || []
    rows.value = reset ? records : rows.value.concat(records)
    total.value = Number(data?.total || 0)
    page.value = nextPage
    finished.value = rows.value.length >= total.value || records.length < size
    loadedOnce.value = true
  } catch (e) {
    finished.value = true
  } finally {
    loading.value = false
    uni.stopPullDownRefresh()
  }
}

const chooseStatus = (value) => {
  if (status.value === value) return
  status.value = value
  finished.value = false
  load(true)
}

const openDetail = (row) => {
  uni.navigateTo({ url: `/pages/workorder/detail?id=${row.id}` })
}

const scanWork = () => {
  uni.switchTab({ url: '/pages/scan/scan' })
}

onShow(() => {
  if (!ensureAuth()) return
  // 从通知中心跳过来会带上工单号，直接按它筛
  const pending = uni.getStorageSync('mes.workorder.keyword')
  if (pending) {
    keyword.value = String(pending)
    uni.removeStorageSync('mes.workorder.keyword')
  }
  finished.value = false
  load(true)
})

onPullDownRefresh(() => {
  finished.value = false
  load(true)
})

onReachBottom(() => {
  load(false)
})
</script>

<template>
  <view class="mes-page list-page">
    <MesWatermark />
    <view class="filter-panel">
      <view class="search-row">
        <view class="search-box">
          <view class="search-icon"></view>
          <input
            v-model="keyword"
            class="search-input"
            placeholder="搜索工单号 / 产品名称"
            placeholder-class="search-placeholder"
            confirm-type="search"
            @confirm="load(true)"
          />
          <view v-if="keyword" class="clear" @tap="keyword = ''; load(true)">×</view>
        </view>
        <view class="scan-mini" @tap="scanWork">
          <view class="scan-mini-icon">
            <view class="corner tl"></view>
            <view class="corner br"></view>
          </view>
        </view>
      </view>

      <scroll-view scroll-x class="status-scroll" :show-scrollbar="false">
        <view class="chips">
          <view
            v-for="item in statusOptions"
            :key="item.value"
            class="chip"
            :class="{ active: status === item.value }"
            @tap="chooseStatus(item.value)"
          >
            {{ item.label }}
          </view>
        </view>
      </scroll-view>
    </view>

    <view class="list-head">
      <text class="list-title">执行清单</text>
      <text class="muted small">共 {{ total }} 张</text>
    </view>

    <view v-if="!loadedOnce && loading" class="loading-block">正在加载工单…</view>

    <view v-else-if="!rows.length" class="panel empty-wrap">
      <EmptyState
        mark="工"
        text="没有符合条件的工单"
        hint="试试切换状态，或在 Web 管理端创建生产工单"
        action-text="重新加载"
        @action="load(true)"
      />
    </view>

    <view v-else>
      <view v-for="row in rows" :key="row.id" class="list-card work-card" @tap="openDetail(row)">
        <view class="row between">
          <view class="row gap8">
            <text class="work-no mono">{{ row.workNo }}</text>
            <text class="tag">工单</text>
          </view>
          <StatusPill :status="row.status" />
        </view>

        <view class="work-product">
          <text class="product-name ellipsis">{{ row.productName }}</text>
          <text class="product-meta">{{ row.productCode }} · {{ row.spec || '无规格' }}</text>
        </view>

        <view class="progress-row">
          <view class="progress flex1">
            <view class="progress-inner" :style="{ width: progressOf(row.doneQty, row.planQty) + '%' }"></view>
          </view>
          <text class="progress-pct">{{ progressOf(row.doneQty, row.planQty) }}%</text>
        </view>

        <view class="work-foot">
          <view class="qty-block">
            <text class="qty-label">计划</text>
            <text class="qty-value num">{{ fmtQty(row.planQty) }}</text>
            <text class="qty-unit">{{ row.unit || '' }}</text>
          </view>
          <view class="qty-divider"></view>
          <view class="qty-block">
            <text class="qty-label">完成</text>
            <text class="qty-value num text-orange">{{ fmtQty(row.doneQty) }}</text>
            <text class="qty-unit">{{ row.unit || '' }}</text>
          </view>
          <view class="flex1"></view>
          <view class="plan-end">
            <text class="qty-label">计划完工</text>
            <text class="plan-date">{{ row.planEnd || '-' }}</text>
          </view>
          <view class="chevron"></view>
        </view>
      </view>

      <view class="load-more">
        <text v-if="loading">加载中…</text>
        <text v-else-if="finished">已加载全部 {{ total }} 张工单</text>
        <text v-else @tap="load(false)">加载更多</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.list-page {
  padding-bottom: calc(30rpx + env(safe-area-inset-bottom));
}

.filter-panel {
  position: sticky;
  top: 0;
  z-index: 20;
  padding: 22rpx 24rpx 14rpx;
  background: rgba(243, 244, 246, 0.96);
  backdrop-filter: blur(10rpx);
}

.search-row {
  display: flex;
  align-items: center;
  gap: 14rpx;
}

.search-box {
  flex: 1;
  display: flex;
  align-items: center;
  height: 76rpx;
  padding: 0 20rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 14rpx;
  background: #ffffff;
}

.search-icon {
  width: 24rpx;
  height: 24rpx;
  margin-right: 14rpx;
  border: 3rpx solid #94a3b8;
  border-radius: 50%;
  position: relative;
}

.search-icon::after {
  content: '';
  position: absolute;
  right: -9rpx;
  bottom: -6rpx;
  width: 12rpx;
  height: 3rpx;
  background: #94a3b8;
  transform: rotate(45deg);
}

.search-input {
  flex: 1;
  height: 74rpx;
  font-size: 26rpx;
}

.search-placeholder {
  color: #b6c2d1;
}

.clear {
  width: 40rpx;
  color: #94a3b8;
  font-size: 30rpx;
  text-align: center;
}

.scan-mini {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 76rpx;
  height: 76rpx;
  border-radius: 14rpx;
  background: #111827;
}

.scan-mini-icon {
  position: relative;
  width: 34rpx;
  height: 34rpx;
}

.scan-mini-icon .corner {
  position: absolute;
  width: 12rpx;
  height: 12rpx;
  border-color: #fdba74;
  border-style: solid;
}

.scan-mini-icon .tl {
  top: 0;
  left: 0;
  border-width: 3rpx 0 0 3rpx;
}

.scan-mini-icon .br {
  right: 0;
  bottom: 0;
  border-width: 0 3rpx 3rpx 0;
}

.status-scroll {
  width: 100%;
  margin-top: 16rpx;
  white-space: nowrap;
}

.chips {
  display: inline-flex;
  gap: 12rpx;
  padding-bottom: 4rpx;
}

.list-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 8rpx 28rpx 16rpx;
}

.list-title {
  color: #0f172a;
  font-size: 28rpx;
  font-weight: 700;
}

.empty-wrap {
  margin: 8rpx 24rpx;
}

.work-card {
  margin: 0 24rpx 18rpx;
}

.work-no {
  color: #0f172a;
  font-size: 29rpx;
  font-weight: 700;
}

.work-product {
  display: flex;
  flex-direction: column;
  margin-top: 18rpx;
}

.product-name {
  color: #1f2937;
  font-size: 27rpx;
  font-weight: 600;
}

.product-meta {
  margin-top: 3rpx;
  color: #94a3b8;
  font-size: 22rpx;
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 18rpx;
}

.progress-pct {
  flex: none;
  color: #c2410c;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 23rpx;
  font-weight: 700;
}

.work-foot {
  display: flex;
  align-items: flex-end;
  gap: 14rpx;
  margin-top: 20rpx;
  padding-top: 18rpx;
  border-top: 1rpx solid #f1f5f9;
}

.qty-block {
  display: flex;
  align-items: baseline;
  gap: 6rpx;
}

.qty-label {
  color: #94a3b8;
  font-size: 20rpx;
}

.qty-value {
  color: #1f2937;
  font-size: 28rpx;
  font-weight: 700;
}

.qty-unit {
  color: #94a3b8;
  font-size: 19rpx;
}

.qty-divider {
  width: 1rpx;
  height: 28rpx;
  margin: 0 4rpx;
  background: #e2e8f0;
}

.plan-end {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2rpx;
}

.plan-date {
  color: #475569;
  font-size: 22rpx;
}

.load-more {
  padding: 18rpx 0 30rpx;
  color: #94a3b8;
  font-size: 22rpx;
  text-align: center;
}
</style>
