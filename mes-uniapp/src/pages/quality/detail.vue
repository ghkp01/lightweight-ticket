<script setup>
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
import { qualityApi } from '../../api'
import { fmtQty, fmtRate, inspectionTypeLabel, shortTime, statusText } from '../../utils/format'
import StatusPill from '../../components/StatusPill.vue'
import MesWatermark from '../../components/Watermark.vue'

const id = ref(0)
const detail = ref(null)
const loading = ref(false)
const error = ref('')

const items = computed(() => detail.value?.items || [])
const failItems = computed(() => items.value.filter((item) => item.result === 'FAIL'))

const sampleText = computed(() => {
  const data = detail.value || {}
  if (!data.samplePlan) return '未记录'
  if (data.samplePlan === 'FULL') return '全检'
  return `抽检 AQL ${data.aql || '-'}，样本 ${fmtQty(data.sampleQty)}，Ac ${fmtQty(data.acceptQty)} / Re ${fmtQty(data.rejectQty)}`
})

const load = async () => {
  if (!id.value || loading.value) return
  loading.value = true
  error.value = ''
  try {
    detail.value = await qualityApi.detail(id.value)
  } catch (e) {
    error.value = '检验单加载失败，请稍后重试'
  } finally {
    loading.value = false
    uni.stopPullDownRefresh()
  }
}

const createAnother = () => {
  uni.redirectTo({ url: '/pages/quality/create' })
}

const backList = () => {
  uni.navigateBack({
    fail: () => uni.redirectTo({ url: '/pages/quality/list' })
  })
}

onLoad((options) => {
  id.value = Number((options && options.id) || 0)
  load()
})

onPullDownRefresh(() => {
  load()
})
</script>

<template>
  <view class="mes-page detail-page">
    <MesWatermark />

    <view v-if="loading && !detail" class="loading-block">正在加载检验单…</view>

    <view v-else-if="error" class="error-banner">
      <text>{{ error }}</text>
      <text class="retry" @tap="load">重试</text>
    </view>

    <template v-else-if="detail">
      <view class="hero detail-hero">
        <view class="hero-inner">
          <view class="row between">
            <view>
              <view class="hero-eyebrow">{{ inspectionTypeLabel(detail.type) }}</view>
              <view class="hero-title mono">{{ detail.inspectionNo }}</view>
            </view>
            <StatusPill :status="detail.result" />
          </view>
          <view class="hero-meta">
            <text class="hero-product">{{ detail.productName }}</text>
            <text class="hero-code">{{ detail.productCode }} · {{ detail.unit || '件' }}</text>
          </view>
        </view>
      </view>

      <view class="panel card">
        <view class="card-title">数量与结论</view>
        <view class="metric-row">
          <view class="metric">
            <text class="metric-label">检验数量</text>
            <text class="metric-value num">{{ fmtQty(detail.inspectQty) }}</text>
          </view>
          <view class="metric">
            <text class="metric-label">不良数量</text>
            <text class="metric-value num text-red">{{ fmtQty(detail.defectQty) }}</text>
          </view>
          <view class="metric">
            <text class="metric-label">合格率</text>
            <text class="metric-value num" :class="Number(detail.passRate) < 100 ? 'text-orange' : 'text-green'">
              {{ fmtRate(detail.passRate) }}
            </text>
          </view>
          <view class="metric">
            <text class="metric-label">结论</text>
            <text class="metric-value">{{ statusText(detail.result) }}</text>
          </view>
        </view>
      </view>

      <view class="panel card">
        <view class="card-title">基本信息</view>
        <view class="info-line"><text class="info-label">关联单据</text><text class="mono">{{ detail.refNo || '-' }}</text></view>
        <view class="info-line" v-if="detail.workNo">
          <text class="info-label">生产工单</text><text class="mono">{{ detail.workNo }}</text>
        </view>
        <view class="info-line" v-if="detail.operationName">
          <text class="info-label">检验工序</text>
          <text>第 {{ detail.operationSeq }} 道 · {{ detail.operationName }}</text>
        </view>
        <view class="info-line"><text class="info-label">抽样方式</text><text>{{ sampleText }}</text></view>
        <view class="info-line"><text class="info-label">检验员</text><text>{{ detail.inspector || '-' }}</text></view>
        <view class="info-line"><text class="info-label">检验时间</text><text>{{ shortTime(detail.inspectedAt) }}</text></view>
        <view class="info-line" v-if="detail.remark">
          <text class="info-label">备注</text><text>{{ detail.remark }}</text>
        </view>
      </view>

      <view class="panel card">
        <view class="row between">
          <view class="card-title">检验项</view>
          <text class="item-count">
            {{ items.length }} 项<text v-if="failItems.length" class="text-red"> · 不合格 {{ failItems.length }}</text>
          </text>
        </view>

        <view v-if="!items.length" class="items-empty">
          这张检验单没有检验项记录，可能是不良处置或人工判定的单据。
        </view>

        <view v-else class="item-list">
          <view v-for="item in items" :key="item.id" class="item-row">
            <view class="item-head">
              <text class="item-name ellipsis">{{ item.itemName }}</text>
              <text class="item-result" :class="item.result === 'FAIL' ? 'red' : 'green'">
                {{ item.result === 'FAIL' ? '不合格' : '合格' }}
              </text>
            </view>
            <view class="item-body">
              <text class="item-standard">标准：{{ item.standard || '-' }}</text>
              <text class="item-measured">实测：{{ item.measured || '-' }}</text>
              <text v-if="Number(item.defectQty) > 0" class="item-defect">不良 {{ fmtQty(item.defectQty) }}</text>
            </view>
            <text v-if="item.remark" class="item-remark">{{ item.remark }}</text>
          </view>
        </view>
      </view>

      <view class="detail-actions">
        <view class="btn btn-ghost" @tap="backList">返回列表</view>
        <view class="btn btn-primary" @tap="createAnother">再建一张</view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.detail-page {
  padding-bottom: calc(60rpx + env(safe-area-inset-bottom));
}

.detail-hero {
  padding-top: 30rpx;
  padding-bottom: 28rpx;
}

.hero-meta {
  display: flex;
  flex-direction: column;
  margin-top: 18rpx;
}

.hero-product {
  color: #ffffff;
  font-size: 28rpx;
  font-weight: 600;
}

.hero-code {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 21rpx;
}

.card {
  margin: 18rpx 24rpx 0;
  padding: 22rpx 22rpx;
}

.metric-row {
  display: flex;
  flex-wrap: wrap;
  gap: 18rpx;
  margin-top: 8rpx;
}

.metric {
  flex: 1;
  min-width: 130rpx;
  display: flex;
  flex-direction: column;
}

.metric-label {
  color: #94a3b8;
  font-size: 20rpx;
}

.metric-value {
  margin-top: 4rpx;
  color: #1f2937;
  font-size: 30rpx;
  font-weight: 700;
}

.info-line {
  display: flex;
  align-items: flex-start;
  gap: 16rpx;
  padding: 10rpx 0;
  border-bottom: 1rpx solid #f1f5f9;
  color: #1f2937;
  font-size: 24rpx;
  line-height: 1.5;
}

.info-line:last-child {
  border-bottom: none;
}

.info-label {
  flex: none;
  width: 130rpx;
  color: #94a3b8;
  font-size: 22rpx;
}

.item-count {
  color: #94a3b8;
  font-size: 21rpx;
}

.items-empty {
  padding: 24rpx 0;
  color: #94a3b8;
  font-size: 22rpx;
  line-height: 1.6;
}

.item-list {
  margin-top: 10rpx;
}

.item-row {
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f1f5f9;
}

.item-row:last-child {
  border-bottom: none;
}

.item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.item-name {
  max-width: 400rpx;
  color: #0f172a;
  font-size: 25rpx;
  font-weight: 600;
}

.item-result {
  font-size: 21rpx;
  font-weight: 600;
}

.item-result.red {
  color: #dc2626;
}

.item-result.green {
  color: #15803d;
}

.item-body {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 6rpx;
  color: #64748b;
  font-size: 21rpx;
}

.item-defect {
  color: #c2410c;
}

.item-remark {
  display: block;
  margin-top: 6rpx;
  color: #94a3b8;
  font-size: 21rpx;
}

.detail-actions {
  display: flex;
  gap: 16rpx;
  padding: 28rpx 24rpx 0;
}

.detail-actions .btn {
  flex: 1;
  height: 84rpx;
  font-size: 26rpx;
}

.error-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 20rpx 24rpx;
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
</style>
