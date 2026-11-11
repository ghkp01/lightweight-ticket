<script setup>
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { defectApi } from '../../api'
import { ensureAuth } from '../../utils/auth'
import { isQueued } from '../../utils/offlineQueue'
import { fmtQty, shortTime } from '../../utils/format'
import EmptyState from '../../components/EmptyState.vue'
import StatusPill from '../../components/StatusPill.vue'
import MesWatermark from '../../components/Watermark.vue'

const rows = ref([])
const summary = ref({})
const loading = ref(false)
const loadedOnce = ref(false)
const error = ref('')
const statusFilter = ref('PENDING')
const keyword = ref('')

const sheetVisible = ref(false)
const current = ref(null)
const disposition = ref('')
const remark = ref('')
const submitting = ref(false)

const DISPOSITIONS = [
  { value: 'REWORK', label: '返修', desc: '自动开返修工单，按起点工序重新生产' },
  { value: 'CONCESSION', label: '让步接收', desc: '合格数量入成品仓，不良单结案' },
  { value: 'SCRAP', label: '报废', desc: '直接报废，不进库存' },
  { value: 'RETURN_SUPPLIER', label: '退供应商', desc: '来料不良退回，联动采购退货' }
]

const statusOptions = [
  { value: 'PENDING', label: '待处置' },
  { value: 'DISPOSED', label: '已处置' },
  { value: '', label: '全部' }
]

const sourceLabel = (source) => ({
  FINAL_QC: '完工检',
  PROCESS_QC: '过程检',
  INCOMING_QC: '来料检',
  REWORK: '返修',
  RETURN: '退货'
}[source] || source || '-')

const dispositionLabel = (value) => {
  const found = DISPOSITIONS.find((item) => item.value === value)
  return found ? found.label : value || '-'
}

const pendingCount = computed(() => Number(summary.value.pending || 0))

const load = async () => {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const [list, sum] = await Promise.all([
      defectApi.list({
        status: statusFilter.value || undefined,
        keyword: keyword.value.trim() || undefined,
        limit: 100
      }),
      defectApi.summary()
    ])
    rows.value = list || []
    summary.value = sum || {}
    loadedOnce.value = true
  } catch (e) {
    error.value = '不良记录加载失败，请稍后重试'
  } finally {
    loading.value = false
    uni.stopPullDownRefresh()
  }
}

const chooseStatus = (value) => {
  if (statusFilter.value === value) return
  statusFilter.value = value
  load()
}

const openSheet = (row) => {
  current.value = row
  disposition.value = row.status === 'DISPOSED' ? row.disposition : ''
  remark.value = ''
  sheetVisible.value = true
}

const closeSheet = () => {
  sheetVisible.value = false
  current.value = null
}

const pickDisposition = (value) => {
  if (current.value && current.value.status === 'DISPOSED') return
  disposition.value = value
}

const submit = async () => {
  if (!current.value) return
  if (!disposition.value) {
    uni.showToast({ title: '请选择处置方式', icon: 'none' })
    return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    const result = await defectApi.dispose(current.value.id, {
      disposition: disposition.value,
      remark: remark.value.trim() || undefined
    })
    if (isQueued(result)) {
      closeSheet()
      uni.showModal({
        title: '处置已离线保存',
        content: '当前网络不可用，不良处置已存到手机本地，联网后自动上传。',
        showCancel: false,
        confirmColor: '#f97316'
      })
      return
    }
    const reworkNo = result && result.reworkWorkNo
    uni.showToast({
      title: reworkNo ? `已返修，工单 ${reworkNo}` : '处置完成',
      icon: 'none',
      duration: 2400
    })
    closeSheet()
    await load()
  } catch (e) {
    // request 已统一提示
  } finally {
    submitting.value = false
  }
}

onShow(() => {
  if (!ensureAuth()) return
  // 从通知中心跳过来会带上不良单号，直接按它筛
  const pending = uni.getStorageSync('mes.defect.keyword')
  if (pending) {
    keyword.value = String(pending)
    statusFilter.value = ''
    uni.removeStorageSync('mes.defect.keyword')
  }
  load()
})

onPullDownRefresh(() => {
  load()
})
</script>

<template>
  <view class="mes-page defect-page">
    <MesWatermark />
    <view class="hero defect-hero">
      <view class="hero-inner">
        <view class="row between">
          <view>
            <view class="hero-eyebrow">DEFECT DISPOSAL</view>
            <view class="hero-title">不良处置</view>
          </view>
          <view class="pending-block">
            <text class="pending-value">{{ pendingCount }}</text>
            <text class="pending-label">待处置</text>
          </view>
        </view>
        <view class="defect-stats">
          <view class="d-stat">
            <text class="d-num num">{{ fmtQty((summary || {}).rework) }}</text>
            <text class="d-label">返修</text>
          </view>
          <view class="d-stat">
            <text class="d-num num">{{ fmtQty((summary || {}).concession) }}</text>
            <text class="d-label">让步</text>
          </view>
          <view class="d-stat">
            <text class="d-num num">{{ fmtQty((summary || {}).scrap) }}</text>
            <text class="d-label">报废</text>
          </view>
          <view class="d-stat">
            <text class="d-num num">{{ fmtQty((summary || {}).returnSupplier) }}</text>
            <text class="d-label">退供应商</text>
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
          placeholder="搜索不良单号 / 工单 / 产品"
          placeholder-class="search-placeholder"
          confirm-type="search"
          @confirm="load"
        />
        <view v-if="keyword" class="clear" @tap="keyword = ''; load()">×</view>
      </view>
      <view class="chips filter-chips">
        <view
          v-for="item in statusOptions"
          :key="item.value"
          class="chip"
          :class="{ active: statusFilter === item.value }"
          @tap="chooseStatus(item.value)"
        >
          {{ item.label }}
        </view>
      </view>
    </view>

    <view v-if="error" class="error-banner">
      <text>{{ error }}</text>
      <text class="retry" @tap="load">重试</text>
    </view>

    <view v-if="!loadedOnce && loading" class="loading-block">正在加载不良记录…</view>

    <view v-else-if="!rows.length" class="empty-wrap">
      <EmptyState mark="良" text="没有不良记录" hint="报工不良、检验不合格、退货判定都会进这里" />
    </view>

    <view v-else class="list-wrap">
      <view v-for="row in rows" :key="row.id" class="list-card" @tap="openSheet(row)">
        <view class="row between">
          <view class="row gap8">
            <text class="defect-no mono">{{ row.defectNo }}</text>
            <text class="tag">{{ sourceLabel(row.source) }}</text>
          </view>
          <StatusPill :status="row.status" />
        </view>

        <view class="defect-product">
          <text class="product-name ellipsis">{{ row.productName || '-' }}</text>
          <text class="product-meta">
            {{ row.productCode || '-' }}
            <text v-if="row.workNo"> · {{ row.workNo }}</text>
            <text v-if="row.operationName"> · 第 {{ row.operationSeq }} 道 {{ row.operationName }}</text>
          </text>
        </view>

        <view class="defect-metrics">
          <view class="metric">
            <text class="metric-label">不良数量</text>
            <text class="metric-value num text-red">{{ fmtQty(row.qty) }}</text>
          </view>
          <view class="metric flex1">
            <text class="metric-label">原因</text>
            <text class="metric-value reason ellipsis">{{ row.reason || '-' }}</text>
          </view>
          <view class="metric">
            <text class="metric-label">处置</text>
            <text class="metric-value">{{ row.disposition ? dispositionLabel(row.disposition) : '待定' }}</text>
          </view>
        </view>

        <view class="defect-foot">
          <text>{{ row.handledBy ? `处理人 ${row.handledBy}` : '待处理' }}</text>
          <text class="time">{{ shortTime(row.handledAt || row.createdAt) }}</text>
        </view>
      </view>
    </view>

    <view v-if="sheetVisible" class="popup-mask" @tap="closeSheet">
      <view class="popup-panel" @tap.stop>
        <view class="popup-head">
          <text class="popup-title">{{ current && current.defectNo }}</text>
          <text class="popup-close" @tap="closeSheet">×</text>
        </view>

        <view v-if="current" class="sheet-body">
          <view class="sheet-info">
            <view class="info-line"><text class="info-label">产品</text><text>{{ current.productName }}（{{ current.productCode }}）</text></view>
            <view class="info-line"><text class="info-label">来源</text><text>{{ sourceLabel(current.source) }}</text></view>
            <view class="info-line" v-if="current.workNo">
              <text class="info-label">工单</text><text>{{ current.workNo }}</text>
            </view>
            <view class="info-line" v-if="current.operationName">
              <text class="info-label">工序</text><text>第 {{ current.operationSeq }} 道 · {{ current.operationName }}</text>
            </view>
            <view class="info-line"><text class="info-label">不良数</text><text class="text-red">{{ fmtQty(current.qty) }}</text></view>
            <view class="info-line"><text class="info-label">原因</text><text>{{ current.reason || '-' }}</text></view>
            <view class="info-line" v-if="current.reworkWorkNo">
              <text class="info-label">返修单</text><text>{{ current.reworkWorkNo }}</text>
            </view>
          </view>

          <view v-if="current.status === 'DISPOSED'" class="disposed-tip">
            已处置为「{{ dispositionLabel(current.disposition) }}」，处理人 {{ current.handledBy || '-' }}
          </view>

          <template v-else>
            <view class="sheet-title">选择处置方式</view>
            <view class="dispose-grid">
              <view
                v-for="item in DISPOSITIONS"
                :key="item.value"
                class="dispose-item"
                :class="{ active: disposition === item.value }"
                @tap="pickDisposition(item.value)"
              >
                <text class="dispose-name">{{ item.label }}</text>
                <text class="dispose-desc">{{ item.desc }}</text>
              </view>
            </view>
            <view class="input-box sheet-remark">
              <input v-model="remark" class="input" placeholder="处置备注（选填）" placeholder-class="scan-placeholder" />
            </view>
            <view class="btn btn-primary btn-block" :class="{ 'btn-hover': submitting }" @tap="submit">
              {{ submitting ? '提交中…' : '确认处置' }}
            </view>
          </template>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.defect-page {
  padding-bottom: calc(60rpx + env(safe-area-inset-bottom));
}

.defect-hero {
  padding-top: 30rpx;
  padding-bottom: 30rpx;
}

.pending-block {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.pending-value {
  color: #fca5a5;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 52rpx;
  font-weight: 700;
  line-height: 1;
}

.pending-label {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 19rpx;
}

.defect-stats {
  display: flex;
  margin-top: 26rpx;
  padding-top: 22rpx;
  border-top: 1rpx solid rgba(148, 163, 184, 0.16);
}

.d-stat {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.d-num {
  color: #ffffff;
  font-size: 30rpx;
  font-weight: 700;
}

.d-label {
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

.filter-chips {
  margin-top: 14rpx;
}

.chip {
  height: 52rpx;
  padding: 0 18rpx;
  font-size: 22rpx;
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

.defect-no {
  color: #0f172a;
  font-size: 27rpx;
  font-weight: 700;
}

.defect-product {
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
  font-size: 21rpx;
}

.defect-metrics {
  display: flex;
  gap: 14rpx;
  margin-top: 16rpx;
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

.metric-value.reason {
  max-width: 300rpx;
  font-size: 22rpx;
  font-weight: 500;
}

.defect-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14rpx;
  color: #94a3b8;
  font-size: 20rpx;
}

.sheet-body {
  padding-bottom: 10rpx;
}

.sheet-info {
  padding: 18rpx 20rpx;
  border-radius: 12rpx;
  background: #f8fafc;
}

.info-line {
  display: flex;
  align-items: flex-start;
  gap: 14rpx;
  padding: 6rpx 0;
  color: #1f2937;
  font-size: 24rpx;
  line-height: 1.5;
}

.info-label {
  flex: none;
  width: 96rpx;
  color: #94a3b8;
  font-size: 22rpx;
}

.disposed-tip {
  margin-top: 18rpx;
  padding: 16rpx 20rpx;
  border: 1rpx solid #bbf7d0;
  border-radius: 12rpx;
  background: #f0fdf4;
  color: #15803d;
  font-size: 23rpx;
}

.sheet-title {
  margin: 22rpx 0 12rpx;
  color: #1f2937;
  font-size: 24rpx;
  font-weight: 600;
}

.dispose-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
}

.dispose-item {
  width: calc(50% - 7rpx);
  padding: 16rpx 18rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 12rpx;
  background: #ffffff;
}

.dispose-item.active {
  border-color: #f97316;
  background: #fff7ed;
}

.dispose-name {
  display: block;
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 600;
}

.dispose-desc {
  display: block;
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 19rpx;
  line-height: 1.4;
}

.sheet-remark {
  margin-top: 18rpx;
}
</style>
