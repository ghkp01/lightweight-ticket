<script setup>
import { computed, ref } from 'vue'
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { qualityApi } from '../../api'
import { ensureAuth } from '../../utils/auth'
import { fmtQty, fmtRate, INSPECTION_TYPES, inspectionTypeLabel, shortTime } from '../../utils/format'
import { parseScanCode, toInspectionQuery } from '../../utils/scanCode'
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
const type = ref('')
const result = ref('')
const summary = ref(null)

const typeOptions = [{ value: '', label: '全部类型' }, ...INSPECTION_TYPES.map((item) => ({ value: item.value, label: item.short }))]
const resultOptions = [
  { value: '', label: '全部结果' },
  { value: 'PASS', label: '合格' },
  { value: 'FAIL', label: '不合格' }
]

const rate = computed(() => Number((summary.value || {}).rate || 0))

const load = async (reset = false) => {
  if (loading.value) return
  if (!reset && finished.value) return
  loading.value = true
  error.value = ''
  try {
    const nextPage = reset ? 1 : page.value + 1
    const data = await qualityApi.list({
      keyword: keyword.value.trim(),
      type: type.value,
      result: result.value,
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
    error.value = '检验单加载失败，请稍后重试'
    if (reset) rows.value = []
    finished.value = true
  } finally {
    loading.value = false
    uni.stopPullDownRefresh()
  }
}

const loadSummary = async () => {
  try {
    summary.value = await qualityApi.summary()
  } catch (e) {
    summary.value = null
  }
}

const refreshAll = async () => {
  finished.value = false
  await Promise.all([load(true), loadSummary()])
}

const chooseType = (value) => {
  if (type.value === value) return
  type.value = value
  refreshAll()
}

const chooseResult = (value) => {
  if (result.value === value) return
  result.value = value
  refreshAll()
}

const openDetail = (row) => {
  uni.navigateTo({ url: `/pages/quality/detail?id=${row.id}` })
}

const createInspection = () => {
  uni.navigateTo({ url: '/pages/quality/create' })
}

// 扫码检验：摄像头扫、扫码枪扫（键盘楔入）都走同一个解析入口
const scanSheet = ref(false)
const scanInput = ref('')
const scanFocus = ref(false)
const scanning = ref(false)

const openScanSheet = () => {
  scanInput.value = ''
  scanSheet.value = true
  scanFocus.value = true
}

const closeScanSheet = () => {
  scanSheet.value = false
  scanFocus.value = false
}

const inspectByCode = (raw) => {
  const parsed = parseScanCode(raw)
  if (parsed.invalid) {
    uni.showToast({ title: parsed.invalid, icon: 'none' })
    return
  }
  const query = toInspectionQuery(parsed)
  if (!query) {
    uni.showToast({ title: '请扫工单码或工序码', icon: 'none' })
    return
  }
  closeScanSheet()
  uni.navigateTo({ url: `/pages/quality/create?${query}` })
}

const scanByCamera = () => {
  if (scanning.value) return
  if (!uni.scanCode) {
    uni.showToast({ title: '当前环境调不起相机，请用扫码枪或手动输入', icon: 'none' })
    return
  }
  scanning.value = true
  uni.scanCode({
    onlyFromCamera: false,
    scanType: ['qrCode', 'barCode', 'datamatrix', 'pdf417'],
    success: (res) => inspectByCode(res.result),
    fail: () => {},
    complete: () => {
      scanning.value = false
    }
  })
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
    <view class="hero quality-hero">
      <view class="hero-inner">
        <view class="row between">
          <view>
            <view class="hero-eyebrow">QUALITY CONTROL</view>
            <view class="hero-title">质量检验</view>
          </view>
          <view class="rate-block">
            <text class="rate-value">{{ fmtRate(rate) }}</text>
            <text class="rate-label">30 天合格率</text>
          </view>
        </view>
        <view class="quality-bars">
          <view class="q-stat">
            <text class="q-num num">{{ fmtQty((summary || {}).inspectQty) }}</text>
            <text class="q-label">检验数量</text>
          </view>
          <view class="q-stat">
            <text class="q-num num text-orange">{{ fmtQty((summary || {}).defectQty) }}</text>
            <text class="q-label">不良数量</text>
          </view>
          <view class="q-stat">
            <text class="q-num num">{{ fmtQty((summary || {}).total, 0) }}</text>
            <text class="q-label">检验批次</text>
          </view>
        </view>
      </view>
    </view>

    <view class="filter-panel">
      <view class="search-box">
        <view class="search-icon"></view>
        <input
          v-model="keyword"
          class="search-input"
          placeholder="搜索检验单号 / 产品 / 关联单据"
          placeholder-class="search-placeholder"
          confirm-type="search"
          @confirm="refreshAll"
        />
        <view v-if="keyword" class="clear" @tap="keyword = ''; refreshAll()">×</view>
      </view>
      <view class="filter-row">
        <scroll-view scroll-x class="type-scroll" :show-scrollbar="false">
          <view class="chips">
            <view
              v-for="item in typeOptions"
              :key="item.value"
              class="chip"
              :class="{ active: type === item.value }"
              @tap="chooseType(item.value)"
            >
              {{ item.label }}
            </view>
          </view>
        </scroll-view>
        <view class="result-toggle">
          <text
            v-for="item in resultOptions"
            :key="item.value"
            class="result-chip"
            :class="{ active: result === item.value }"
            @tap="chooseResult(item.value)"
          >
            {{ item.label }}
          </text>
        </view>
      </view>
    </view>

    <view v-if="error" class="error-banner">
      <text>{{ error }}</text>
      <text class="retry" @tap="refreshAll">重试</text>
    </view>

    <view v-if="!loadedOnce && loading" class="loading-block">正在加载检验记录…</view>

    <view v-else-if="!rows.length" class="empty-wrap">
      <EmptyState
        mark="检"
        text="暂无检验记录"
        hint="来料、过程与完工检验都会沉淀在这里"
        action-text="新建检验单"
        @action="createInspection"
      />
    </view>

    <view v-else class="list-wrap">
      <view v-for="row in rows" :key="row.id" class="list-card inspection-card" @tap="openDetail(row)">
        <view class="row between">
          <view class="row gap8">
            <text class="inspection-no mono">{{ row.inspectionNo }}</text>
            <text class="tag">{{ inspectionTypeLabel(row.type) }}</text>
          </view>
          <StatusPill :status="row.result" />
        </view>

        <view class="inspection-product">
          <text class="product-name ellipsis">{{ row.productName }}</text>
          <text class="product-meta">{{ row.productCode }} · {{ row.spec || '无规格' }}</text>
        </view>

        <view class="inspection-metrics">
          <view class="metric">
            <text class="metric-label">检验数</text>
            <text class="metric-value num">{{ fmtQty(row.inspectQty) }}</text>
          </view>
          <view class="metric">
            <text class="metric-label">不良数</text>
            <text class="metric-value num" :class="{ 'text-red': Number(row.defectQty) > 0 }">
              {{ fmtQty(row.defectQty) }}
            </text>
          </view>
          <view class="metric">
            <text class="metric-label">合格率</text>
            <text class="metric-value num" :class="Number(row.passRate) < 100 ? 'text-orange' : 'text-green'">
              {{ fmtRate(row.passRate) }}
            </text>
          </view>
          <view class="metric flex1">
            <text class="metric-label">关联单据</text>
            <text class="metric-value mono ref-no ellipsis">{{ row.refNo || '-' }}</text>
          </view>
        </view>

        <view class="inspection-foot">
          <text class="inspector">检验员 {{ row.inspector || '-' }}</text>
          <text class="time">{{ shortTime(row.inspectedAt) }}</text>
        </view>
      </view>

      <view class="load-more">
        <text v-if="loading">加载中…</text>
        <text v-else-if="finished">已加载全部 {{ total }} 条记录</text>
        <text v-else @tap="load(false)">加载更多</text>
      </view>
    </view>

    <view class="fab-scan" @tap="openScanSheet">扫码检验</view>
    <view class="fab-text" @tap="createInspection">+ 新建检验</view>

    <view v-if="scanSheet" class="popup-mask" @tap="closeScanSheet">
      <view class="popup-panel" @tap.stop>
        <view class="popup-head">
          <text class="popup-title">扫码检验</text>
          <text class="popup-close" @tap="closeScanSheet">×</text>
        </view>
        <text class="scan-tip">
          扫工单码做完工检（未完工的自动按过程检），扫工序码直接检该工序；扫码枪可以直接扫进下面的输入框，带回车即识别。
        </text>
        <view class="scan-row">
          <view class="input-box flex1">
            <input
              v-model="scanInput"
              class="input"
              :focus="scanFocus"
              placeholder="MES|WO|工单号 或 MES|OP|工单号|工序序号"
              placeholder-class="scan-placeholder"
              confirm-type="done"
              @confirm="inspectByCode(scanInput)"
            />
          </view>
          <view class="btn btn-dark scan-btn" @tap="inspectByCode(scanInput)">识别</view>
        </view>
        <view class="btn btn-primary btn-block scan-camera" @tap="scanByCamera">
          {{ scanning ? '正在调起相机…' : '打开摄像头扫码' }}
        </view>
        <view class="scan-guide">
          <text class="scan-guide-item"><text class="rule-dot orange"></text>工单码 MES|WO|工单号</text>
          <text class="scan-guide-item"><text class="rule-dot blue"></text>工序码 MES|OP|工单号|序号</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.list-page {
  padding-bottom: calc(180rpx + env(safe-area-inset-bottom));
}

.quality-hero {
  padding-top: 30rpx;
  padding-bottom: 32rpx;
}

.rate-block {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.rate-value {
  color: #fdba74;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 52rpx;
  font-weight: 700;
  line-height: 1;
}

.rate-label {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 19rpx;
}

.quality-bars {
  display: flex;
  margin-top: 28rpx;
  padding-top: 22rpx;
  border-top: 1rpx solid rgba(148, 163, 184, 0.16);
}

.q-stat {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.q-num {
  color: #ffffff;
  font-size: 32rpx;
  font-weight: 700;
}

.q-label {
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

.filter-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 14rpx;
}

.type-scroll {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
}

.chips {
  display: inline-flex;
  gap: 10rpx;
}

.chip {
  height: 52rpx;
  padding: 0 18rpx;
  font-size: 22rpx;
}

.result-toggle {
  flex: none;
  display: flex;
  padding: 4rpx;
  border-radius: 10rpx;
  background: #e9edf3;
}

.result-chip {
  padding: 6rpx 12rpx;
  border-radius: 8rpx;
  color: #64748b;
  font-size: 20rpx;
}

.result-chip.active {
  background: #ffffff;
  color: #111827;
  font-weight: 600;
  box-shadow: 0 2rpx 6rpx rgba(15, 23, 42, 0.08);
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

.inspection-no {
  color: #0f172a;
  font-size: 27rpx;
  font-weight: 700;
}

.inspection-product {
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

.inspection-metrics {
  display: flex;
  gap: 14rpx;
  margin-top: 18rpx;
  padding: 16rpx 18rpx;
  border-radius: 12rpx;
  background: #f8fafc;
}

.metric {
  display: flex;
  flex-direction: column;
  min-width: 96rpx;
}

.metric-label {
  color: #94a3b8;
  font-size: 19rpx;
}

.metric-value {
  margin-top: 2rpx;
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 700;
}

.ref-no {
  max-width: 190rpx;
  font-size: 21rpx;
  font-weight: 500;
}

.inspection-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14rpx;
  color: #94a3b8;
  font-size: 20rpx;
}

.load-more {
  padding: 18rpx 0 30rpx;
  color: #94a3b8;
  font-size: 22rpx;
  text-align: center;
}

.fab-scan {
  position: fixed;
  right: 32rpx;
  bottom: calc(250rpx + env(safe-area-inset-bottom));
  z-index: 50;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 80rpx;
  padding: 0 28rpx;
  border-radius: 999rpx;
  background: #111827;
  color: #fdba74;
  font-size: 25rpx;
  font-weight: 600;
  box-shadow: 0 14rpx 30rpx rgba(15, 23, 42, 0.28);
}

.scan-tip {
  display: block;
  margin-bottom: 20rpx;
  color: #64748b;
  font-size: 23rpx;
  line-height: 1.6;
}

.scan-row {
  display: flex;
  align-items: center;
  gap: 14rpx;
}

.scan-btn {
  flex: none;
  height: 82rpx;
  padding: 0 30rpx;
  font-size: 26rpx;
}

.scan-placeholder {
  color: #94a3b8;
  font-size: 22rpx;
}

.scan-camera {
  margin-top: 20rpx;
}

.scan-guide {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  margin-top: 20rpx;
}

.scan-guide-item {
  display: flex;
  align-items: center;
  color: #94a3b8;
  font-size: 21rpx;
}

.rule-dot {
  width: 10rpx;
  height: 10rpx;
  margin-right: 10rpx;
  border-radius: 50%;
  background: #94a3b8;
}

.rule-dot.orange {
  background: #f97316;
}

.rule-dot.blue {
  background: #3b82f6;
}
</style>
