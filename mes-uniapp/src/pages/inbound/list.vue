<script setup>
import { computed, ref } from 'vue'
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { inboundApi } from '../../api'
import { ensureAuth } from '../../utils/auth'
import { fmtQty, inboundTypeLabel, shortTime } from '../../utils/format'
import StatusPill from '../../components/StatusPill.vue'
import EmptyState from '../../components/EmptyState.vue'
import MesWatermark from '../../components/Watermark.vue'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 10
const loading = ref(false)
const finished = ref(false)
const loadedOnce = ref(false)
const error = ref('')
const keyword = ref('')
const status = ref('')
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const actionLoading = ref(false)

const statusOptions = [
  { value: '', label: '全部' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'PENDING_QC', label: '待检验' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELLED', label: '已取消' }
]

const stats = computed(() => {
  const list = rows.value
  return {
    pending: list.filter((row) => row.status === 'PENDING_QC').length,
    draft: list.filter((row) => row.status === 'DRAFT').length,
    done: list.filter((row) => row.status === 'COMPLETED').length
  }
})

const load = async (reset = false) => {
  if (loading.value) return
  if (!reset && finished.value) return
  loading.value = true
  error.value = ''
  try {
    const nextPage = reset ? 1 : page.value + 1
    const data = await inboundApi.list({
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
    error.value = '入库单加载失败，请确认后端单据接口已启用'
    if (reset) rows.value = []
    finished.value = true
  } finally {
    loading.value = false
    uni.stopPullDownRefresh()
  }
}

const refreshAll = async () => {
  finished.value = false
  await load(true)
}

const chooseStatus = (value) => {
  if (status.value === value) return
  status.value = value
  refreshAll()
}

const openDetail = async (row) => {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = row
  try {
    detail.value = await inboundApi.detail(row.id)
  } catch (e) {
    // 列表数据兜底展示
  } finally {
    detailLoading.value = false
  }
}

const confirmAction = (content, title) =>
  new Promise((resolve) => {
    uni.showModal({
      title: title || '操作确认',
      content,
      confirmColor: '#f97316',
      success: (res) => resolve(res.confirm),
      fail: () => resolve(false)
    })
  })

const submitOrder = async (row) => {
  const ok = await confirmAction(
    `提交入库单 ${row.inboundNo || row.no}？需要检验的单据将进入待检状态，否则直接入库。`,
    '提交入库'
  )
  if (!ok) return
  actionLoading.value = true
  try {
    await inboundApi.submit(row.id)
    uni.showToast({ title: '已提交', icon: 'success' })
    detailVisible.value = false
    await refreshAll()
  } catch (e) {
    // 统一提示
  } finally {
    actionLoading.value = false
  }
}

const cancelOrder = async (row) => {
  const ok = await confirmAction(`取消入库单 ${row.inboundNo || row.no}？取消后不可恢复。`, '取消入库单')
  if (!ok) return
  actionLoading.value = true
  try {
    await inboundApi.cancel(row.id)
    uni.showToast({ title: '已取消', icon: 'success' })
    detailVisible.value = false
    await refreshAll()
  } catch (e) {
    // 统一提示
  } finally {
    actionLoading.value = false
  }
}

const createOrder = () => {
  uni.navigateTo({ url: '/pages/inbound/create' })
}

onShow(() => {
  if (!ensureAuth()) return
  refreshAll()
})

onPullDownRefresh(() => {
  refreshAll()
})

onReachBottom(() => {
  load(false)
})
</script>

<template>
  <view class="mes-page list-page">
    <MesWatermark />
    <view class="hero mini-hero">
      <view class="hero-inner">
        <view class="row between">
          <view>
            <view class="hero-eyebrow">INBOUND REQUESTS</view>
            <view class="hero-title">入库申请</view>
          </view>
          <view class="hero-count">
            <text class="hero-count-num num">{{ total }}</text>
            <text class="hero-count-label">单据</text>
          </view>
        </view>
        <view class="hero-stats">
          <view class="stat"><text class="stat-num">{{ stats.draft }}</text><text class="stat-label">草稿</text></view>
          <view class="stat"><text class="stat-num text-orange">{{ stats.pending }}</text><text class="stat-label">待检验</text></view>
          <view class="stat"><text class="stat-num text-green">{{ stats.done }}</text><text class="stat-label">已完成</text></view>
        </view>
      </view>
    </view>

    <view class="filter-panel">
      <view class="search-box">
        <view class="search-icon"></view>
        <input
          v-model="keyword"
          class="search-input"
          placeholder="搜索入库单号 / 供应商 / 产品"
          placeholder-class="search-placeholder"
          confirm-type="search"
          @confirm="refreshAll"
        />
        <view v-if="keyword" class="clear" @tap="keyword = ''; refreshAll()">×</view>
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

    <view v-if="error" class="error-banner">
      <text>{{ error }}</text>
      <text class="retry" @tap="refreshAll">重试</text>
    </view>

    <view v-if="!loadedOnce && loading" class="loading-block">正在加载入库单…</view>

    <view v-else-if="!rows.length" class="empty-wrap">
      <EmptyState
        mark="入"
        text="暂无入库单"
        hint="点击右下角发起入库申请，可选择是否需要来料检验"
        action-text="新建入库申请"
        @action="createOrder"
      />
    </view>

    <view v-else class="list-wrap">
      <view v-for="row in rows" :key="row.id" class="list-card" @tap="openDetail(row)">
        <view class="row between">
          <text class="order-no mono">{{ row.inboundNo || row.no }}</text>
          <StatusPill :status="row.status" />
        </view>

        <view class="order-mid">
          <view class="flex1">
            <text class="order-name ellipsis">{{ row.supplier || '未填写供应商' }}</text>
            <text class="order-sub">{{ row.warehouseName || '仓库' }} · {{ inboundTypeLabel(row.type) }}</text>
          </view>
          <view class="order-qty">
            <text class="order-qty-num num">{{ fmtQty(row.totalQty ?? row.itemCount ?? 0) }}</text>
            <text class="order-qty-label">{{ row.totalQty !== undefined ? '总数量' : '明细数' }}</text>
          </view>
        </view>

        <view class="order-tags">
          <text class="tag" :class="row.needInspection ? 'tag-orange' : 'tag-dark'">
            {{ row.needInspection ? '需检验' : '免检' }}
          </text>
          <text class="tag">明细 {{ row.itemCount ?? (row.items || []).length }} 项</text>
          <view class="flex1"></view>
          <text class="order-time">{{ shortTime(row.createdAt) }}</text>
        </view>

        <view class="order-actions" @tap.stop>
          <view
            v-if="row.status === 'DRAFT'"
            class="btn btn-primary btn-sm"
            @tap="submitOrder(row)"
          >
            提交
          </view>
          <view
            v-if="row.status === 'DRAFT' || row.status === 'PENDING_QC'"
            class="btn btn-danger btn-sm"
            @tap="cancelOrder(row)"
          >
            取消
          </view>
          <view class="btn btn-ghost btn-sm" @tap="openDetail(row)">查看明细</view>
        </view>
      </view>

      <view class="load-more">
        <text v-if="loading">加载中…</text>
        <text v-else-if="finished">已加载全部 {{ total }} 张单据</text>
        <text v-else @tap="load(false)">加载更多</text>
      </view>
    </view>

    <view class="fab-text" @tap="createOrder">+ 入库申请</view>

    <view v-if="detailVisible" class="popup-mask" @tap="detailVisible = false">
      <view class="popup-panel" @tap.stop>
        <view class="popup-head">
          <view>
            <text class="popup-title">入库单详情</text>
            <text class="popup-sub mono">{{ detail?.inboundNo || detail?.no }}</text>
          </view>
          <view class="popup-close" @tap="detailVisible = false">×</view>
        </view>

        <view v-if="detailLoading" class="loading-block">正在加载明细…</view>

        <template v-else-if="detail">
          <view class="detail-status row between">
            <StatusPill :status="detail.status" />
            <text class="muted small">{{ shortTime(detail.createdAt) }}</text>
          </view>

          <view class="info-grid">
            <view class="info-item">
              <text class="info-label">入库仓库</text>
              <text class="info-value">{{ detail.warehouseName || '-' }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">入库类型</text>
              <text class="info-value">{{ inboundTypeLabel(detail.type) }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">供应商</text>
              <text class="info-value">{{ detail.supplier || '-' }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">是否检验</text>
              <text class="info-value">{{ detail.needInspection ? '需要检验' : '免检直接入库' }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">创建人</text>
              <text class="info-value">{{ detail.createdBy || '-' }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">完成时间</text>
              <text class="info-value">{{ shortTime(detail.completedAt) }}</text>
            </view>
          </view>

          <view class="detail-section-title">入库明细</view>
          <view v-for="item in detail.items || []" :key="item.id || item.productId" class="detail-item-row">
            <view class="flex1">
              <text class="detail-item-name">{{ item.productName }}</text>
              <text class="detail-item-sub">{{ item.productCode }} · {{ item.spec || '无规格' }}</text>
              <text v-if="item.batchNo" class="detail-item-batch">批次 {{ item.batchNo }}</text>
            </view>
            <view class="detail-item-qty">
              <text class="num">{{ fmtQty(item.qty) }}</text>
              <text class="detail-item-unit">{{ item.unit || '' }}</text>
            </view>
          </view>
          <view v-if="!(detail.items || []).length" class="empty detail-empty">
            <text>暂无明细数据</text>
          </view>

          <view v-if="detail.remark" class="remark-box">{{ detail.remark }}</view>

          <view v-if="detail.status === 'DRAFT' || detail.status === 'PENDING_QC'" class="popup-actions">
            <view
              v-if="detail.status === 'DRAFT'"
              class="btn btn-primary"
              @tap="submitOrder(detail)"
            >
              提交入库
            </view>
            <view class="btn btn-danger" @tap="cancelOrder(detail)">取消单据</view>
          </view>
        </template>
      </view>
    </view>
  </view>
</template>

<style scoped>
.list-page {
  padding-bottom: calc(180rpx + env(safe-area-inset-bottom));
}

.mini-hero {
  padding-top: 30rpx;
  padding-bottom: 30rpx;
}

.mini-hero .hero-title {
  margin-top: 6rpx;
  font-size: 40rpx;
}

.hero-count {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.hero-count-num {
  color: #ffffff;
  font-size: 48rpx;
  font-weight: 700;
  line-height: 1;
}

.hero-count-label {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 19rpx;
}

.hero-stats {
  display: flex;
  margin-top: 26rpx;
  padding-top: 22rpx;
  border-top: 1rpx solid rgba(148, 163, 184, 0.16);
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.stat-num {
  color: #ffffff;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 30rpx;
  font-weight: 700;
}

.stat-label {
  color: #94a3b8;
  font-size: 19rpx;
}

.filter-panel {
  position: sticky;
  top: 0;
  z-index: 20;
  padding: 20rpx 24rpx 12rpx;
  background: rgba(243, 244, 246, 0.96);
}

.search-box {
  display: flex;
  align-items: center;
  height: 76rpx;
  padding: 0 20rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 14rpx;
  background: #ffffff;
}

.search-icon {
  position: relative;
  width: 24rpx;
  height: 24rpx;
  margin-right: 14rpx;
  border: 3rpx solid #94a3b8;
  border-radius: 50%;
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

.status-scroll {
  width: 100%;
  margin-top: 14rpx;
  white-space: nowrap;
}

.chips {
  display: inline-flex;
  gap: 12rpx;
}

.error-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 4rpx 24rpx 14rpx;
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
  padding: 8rpx 24rpx 0;
}

.order-no {
  color: #0f172a;
  font-size: 28rpx;
  font-weight: 700;
}

.order-mid {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 18rpx;
}

.order-name {
  display: block;
  color: #1f2937;
  font-size: 27rpx;
  font-weight: 600;
}

.order-sub {
  display: block;
  margin-top: 3rpx;
  color: #94a3b8;
  font-size: 22rpx;
}

.order-qty {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.order-qty-num {
  color: #c2410c;
  font-size: 32rpx;
  font-weight: 700;
  line-height: 1.1;
}

.order-qty-label {
  color: #94a3b8;
  font-size: 18rpx;
}

.order-tags {
  display: flex;
  align-items: center;
  gap: 10rpx;
  margin-top: 16rpx;
}

.tag-dark {
  background: #111827;
  color: #fdba74;
}

.order-time {
  color: #b6c2d1;
  font-size: 19rpx;
}

.order-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12rpx;
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid #f1f5f9;
}

.load-more {
  padding: 18rpx 0 30rpx;
  color: #94a3b8;
  font-size: 22rpx;
  text-align: center;
}

.popup-sub {
  display: block;
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 21rpx;
}

.detail-status {
  margin-bottom: 20rpx;
}

.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20rpx;
  padding: 20rpx;
  border-radius: 12rpx;
  background: #f8fafc;
}

.info-item {
  display: flex;
  flex-direction: column;
}

.info-label {
  color: #94a3b8;
  font-size: 20rpx;
}

.info-value {
  margin-top: 2rpx;
  color: #1f2937;
  font-size: 24rpx;
  font-weight: 500;
}

.detail-section-title {
  margin: 24rpx 0 8rpx;
  color: #334155;
  font-size: 25rpx;
  font-weight: 700;
}

.detail-item-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 0;
  border-top: 1rpx solid #f1f5f9;
}

.detail-item-name {
  display: block;
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 500;
}

.detail-item-sub {
  display: block;
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 20rpx;
}

.detail-item-batch {
  display: block;
  margin-top: 2rpx;
  color: #c2410c;
  font-size: 19rpx;
}

.detail-item-qty {
  flex: none;
  display: flex;
  align-items: baseline;
  gap: 4rpx;
  color: #0f172a;
  font-size: 28rpx;
  font-weight: 700;
}

.detail-item-unit {
  color: #94a3b8;
  font-size: 19rpx;
  font-weight: 400;
}

.detail-empty {
  padding: 30rpx 0;
}

.remark-box {
  margin-top: 18rpx;
  padding: 16rpx 18rpx;
  border-radius: 10rpx;
  background: #f8fafc;
  color: #64748b;
  font-size: 22rpx;
  line-height: 1.6;
}

.popup-actions {
  display: flex;
  gap: 14rpx;
  margin-top: 24rpx;
}

.popup-actions .btn {
  flex: 1;
}
</style>
