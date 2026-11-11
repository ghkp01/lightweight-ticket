<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { shippingApi } from '../../api'
import { ensureAuth } from '../../utils/auth'
import { fmtQty, shortTime } from '../../utils/format'
import EmptyState from '../../components/EmptyState.vue'
import StatusPill from '../../components/StatusPill.vue'
import MesWatermark from '../../components/Watermark.vue'

const id = ref(null)
const detail = ref(null)
const loading = ref(true)
const error = ref('')

const totalQty = computed(() =>
  (detail.value?.items || []).reduce((sum, item) => sum + Number(item.qty || 0), 0)
)

const load = async () => {
  if (!id.value) return
  loading.value = true
  error.value = ''
  try {
    detail.value = await shippingApi.detail(id.value)
    uni.setNavigationBarTitle({ title: detail.value?.shipmentNo || '发货单' })
  } catch (e) {
    error.value = '发货单加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

const copyNo = () => {
  if (!detail.value?.shipmentNo) return
  uni.setClipboardData({
    data: detail.value.shipmentNo,
    success: () => uni.showToast({ title: '单号已复制', icon: 'none' })
  })
}

const createOutbound = () => {
  const no = detail.value?.shipmentNo || ''
  uni.navigateTo({
    url: `/pages/outbound/create?shipmentNo=${encodeURIComponent(no)}`
  })
}

onLoad((options) => {
  if (!ensureAuth()) return
  id.value = options?.id || null
  if (!id.value) {
    error.value = '缺少发货单参数'
    loading.value = false
    return
  }
  load()
})
</script>

<template>
  <view class="mes-page" :class="{ 'page-with-bar': detail }">
    <MesWatermark />
    <view v-if="loading" class="loading-block">正在读取发货单…</view>

    <view v-else-if="!detail" class="error-state">
      <EmptyState mark="发" text="发货单加载失败" :hint="error" action-text="重新加载" @action="load" />
    </view>

    <template v-else>
      <view class="hero shipment-hero">
        <view class="hero-inner">
          <view class="row between">
            <text class="hero-no mono">{{ detail.shipmentNo }}</text>
            <StatusPill :status="detail.status" />
          </view>
          <view class="customer">{{ detail.customerName }}</view>
          <view class="order-no">来源订单 {{ detail.orderNo || '-' }}</view>

          <view class="ship-stats">
            <view class="ship-stat">
              <text class="ship-stat-num num">{{ detail.items?.length || 0 }}</text>
              <text class="ship-stat-label">明细行</text>
            </view>
            <view class="ship-line"></view>
            <view class="ship-stat">
              <text class="ship-stat-num num">{{ fmtQty(totalQty) }}</text>
              <text class="ship-stat-label">发货数量</text>
            </view>
            <view class="ship-line"></view>
            <view class="ship-stat">
              <text class="ship-stat-num">{{ detail.carrier || '未指定' }}</text>
              <text class="ship-stat-label">承运商</text>
            </view>
          </view>
        </view>
      </view>

      <view class="content">
        <view class="panel">
          <view class="panel-head">
            <view class="section-title tight">物流信息</view>
            <text class="copy" @tap="copyNo">复制单号</text>
          </view>
          <view class="info-grid">
            <view class="info-item">
              <text class="info-label">运单号</text>
              <text class="info-value mono">{{ detail.trackingNo || '-' }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">创建时间</text>
              <text class="info-value">{{ shortTime(detail.createdAt) }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">发货时间</text>
              <text class="info-value">{{ shortTime(detail.shippedAt) }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">签收时间</text>
              <text class="info-value">{{ shortTime(detail.signedAt) }}</text>
            </view>
          </view>
          <view class="address-box">
            <text class="info-label">收货地址</text>
            <text class="address">{{ detail.address || '未填写收货地址' }}</text>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head">
            <view class="section-title tight">发货明细</view>
            <text class="muted tiny">{{ detail.items?.length || 0 }} 行</text>
          </view>
          <view v-for="item in detail.items || []" :key="item.id" class="item-row">
            <view class="item-mark">{{ (item.productName || '品').slice(0, 1) }}</view>
            <view class="flex1">
              <text class="item-name">{{ item.productName }}</text>
              <text class="item-sub">{{ item.productCode }} · {{ item.spec || '无规格' }}</text>
            </view>
            <view class="item-qty">
              <text class="num">{{ fmtQty(item.qty) }}</text>
              <text class="item-unit">{{ item.unit || '' }}</text>
            </view>
          </view>
          <view v-if="!(detail.items || []).length" class="empty item-empty">
            <text>暂无发货明细</text>
          </view>
        </view>
      </view>

      <view class="action-bar">
        <view class="btn btn-ghost" @tap="copyNo">复制单号</view>
        <view class="btn btn-primary" @tap="createOutbound">创建出库申请</view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.shipment-hero {
  padding-bottom: 34rpx;
}

.hero-no {
  color: #ffffff;
  font-size: 31rpx;
  font-weight: 700;
}

.customer {
  margin-top: 20rpx;
  color: #ffffff;
  font-size: 38rpx;
  font-weight: 700;
}

.order-no {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 22rpx;
}

.ship-stats {
  display: flex;
  align-items: center;
  margin-top: 26rpx;
  padding-top: 22rpx;
  border-top: 1rpx solid rgba(148, 163, 184, 0.16);
}

.ship-stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.ship-stat-num {
  overflow: hidden;
  color: #fdba74;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 30rpx;
  font-weight: 700;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.ship-stat-label {
  color: #94a3b8;
  font-size: 19rpx;
}

.ship-line {
  width: 1rpx;
  height: 40rpx;
  margin: 0 16rpx;
  background: rgba(148, 163, 184, 0.16);
}

.copy {
  color: #c2410c;
  font-size: 22rpx;
  font-weight: 600;
}

.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20rpx;
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
  margin-top: 3rpx;
  color: #1f2937;
  font-size: 24rpx;
  font-weight: 500;
}

.address-box {
  display: flex;
  flex-direction: column;
  margin-top: 20rpx;
  padding: 16rpx 18rpx;
  border-radius: 10rpx;
  background: #f8fafc;
}

.address {
  margin-top: 4rpx;
  color: #334155;
  font-size: 24rpx;
  line-height: 1.6;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 16rpx 0;
  border-top: 1rpx solid #f1f5f9;
}

.item-row:first-of-type {
  border-top: none;
}

.item-mark {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56rpx;
  height: 56rpx;
  border-radius: 12rpx;
  background: #f8fafc;
  color: #94a3b8;
  font-size: 22rpx;
  font-weight: 700;
}

.item-name {
  display: block;
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 500;
}

.item-sub {
  display: block;
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 19rpx;
}

.item-qty {
  display: flex;
  align-items: baseline;
  gap: 4rpx;
  color: #0f172a;
  font-size: 27rpx;
  font-weight: 700;
}

.item-unit {
  color: #94a3b8;
  font-size: 18rpx;
  font-weight: 400;
}

.item-empty {
  padding: 30rpx 0 16rpx;
  font-size: 22rpx;
}

.error-state {
  padding-top: 80rpx;
}
</style>
