<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { masterDataApi, shippingApi, workOrderApi } from '../../api'
import { ensureAuth } from '../../utils/auth'
import { queueCount } from '../../utils/offlineQueue'
import { STORAGE_KEYS } from '../../utils/config'
import MesWatermark from '../../components/Watermark.vue'

const code = ref('')
const scanning = ref(false)
const resolving = ref(false)
const history = ref([])
const lastResult = ref(null)
const offlinePending = ref(0)

const loadHistory = () => {
  const raw = uni.getStorageSync(STORAGE_KEYS.scanHistory)
  history.value = Array.isArray(raw) ? raw : []
}

const saveHistory = (item) => {
  const next = [item, ...history.value.filter((row) => row.code !== item.code)].slice(0, 12)
  history.value = next
  uni.setStorageSync(STORAGE_KEYS.scanHistory, next)
}

const clearHistory = () => {
  uni.showModal({
    title: '清空扫码记录',
    content: '仅清空本机记录，不影响业务数据。',
    confirmColor: '#f97316',
    success: (res) => {
      if (!res.confirm) return
      history.value = []
      uni.removeStorageSync(STORAGE_KEYS.scanHistory)
    }
  })
}

const navigate = (route) => {
  uni.navigateTo({
    url: route,
    fail: () => {
      uni.showToast({ title: '页面跳转失败', icon: 'none' })
    }
  })
}

const workOrderRoute = (workNo, opSeq) => {
  const seqPart = opSeq ? `&opSeq=${encodeURIComponent(opSeq)}` : ''
  return `/pages/workorder/detail?workNo=${encodeURIComponent(workNo)}${seqPart}`
}

/**
 * 解析 Web 端打印的条码：
 * - MES|WO|工单号
 * - MES|OP|工单号|工序序号
 * - MES|BATCH|批次号
 */
const parseMesCode = (raw) => {
  const parts = raw.split('|').map((part) => part.trim())
  if (parts.length < 3 || parts[0].toUpperCase() !== 'MES') return null
  const kind = parts[1].toUpperCase()
  if (kind === 'WO') {
    if (!parts[2]) return { invalid: '工单码缺少工单号' }
    return { kind: 'WO', workNo: parts[2], opSeq: '' }
  }
  if (kind === 'OP') {
    if (!parts[2]) return { invalid: '工序码缺少工单号' }
    if (!parts[3]) return { invalid: '工序码缺少工序序号' }
    return { kind: 'OP', workNo: parts[2], opSeq: parts[3] }
  }
  if (kind === 'BATCH') {
    if (!parts[2]) return { invalid: '批次码缺少批次号' }
    return { kind: 'BATCH', batchNo: parts[2] }
  }
  return null
}

const resolveMesCode = (raw, parsed) => {
  const isOperation = parsed.kind === 'OP'
  return {
    code: raw,
    type: isOperation ? '工序码' : '工单码',
    tone: 'orange',
    title: parsed.workNo,
    sub: isOperation
      ? `第 ${parsed.opSeq} 道工序 · 扫码直达报工`
      : '工单码 · 扫码直达工单详情',
    route: workOrderRoute(parsed.workNo, parsed.opSeq)
  }
}

const resolveWorkOrder = async (raw) => {
  const data = await workOrderApi.list({ keyword: raw, page: 1, size: 5 })
  const records = data?.records || []
  const hit = records.find((row) => String(row.workNo || '').toUpperCase() === raw.toUpperCase())
  if (!hit) return null
  return {
    code: raw,
    type: '生产工单',
    tone: 'orange',
    title: hit.workNo,
    sub: `${hit.productName} · ${hit.status} · 扫码直达报工`,
    route: workOrderRoute(hit.workNo)
  }
}

const resolveProduct = async (raw) => {
  const records = (await masterDataApi.products(raw)) || []
  const hit =
    records.find((row) => String(row.code || '').toUpperCase() === raw.toUpperCase()) || records[0]
  if (!hit) return null
  return {
    code: raw,
    type: '产品编码',
    tone: 'blue',
    title: `${hit.code} · ${hit.name}`,
    sub: `${hit.spec || '无规格'} · 库存 ${hit.stock ?? 0} ${hit.unit || ''}`,
    route: `/pages/product/detail?code=${encodeURIComponent(hit.code)}`
  }
}

const resolveShipment = async (raw) => {
  const data = await shippingApi.list({ keyword: raw, page: 1, size: 5 })
  const records = data?.records || []
  const hit =
    records.find((row) => String(row.shipmentNo || '').toUpperCase() === raw.toUpperCase()) ||
    records[0]
  if (!hit) return null
  return {
    code: raw,
    type: '发货单号',
    tone: 'green',
    title: hit.shipmentNo,
    sub: `${hit.customerName} · ${hit.carrier || '未指定承运商'}`,
    route: `/pages/shipment/detail?id=${hit.id}`
  }
}

const showBatchTrace = async (batchNo) => {
  const data = await traceApi.query(batchNo)
  const lines = []
  if (data) {
    const first = (data.stocks || [])[0]
    if (first) {
      lines.push(`产品：${first.productName}（${first.productCode}）`)
    }
    const stocks = (data.stocks || []).map((item) => `${item.warehouseName} ${item.qty}`).join('、')
    lines.push(stocks ? `库存：${stocks}` : '库存：已无余量')
    const materials = (data.materials || []).map((item) => `${item.productName} ${item.batchNo}×${item.qty}`)
    lines.push(
      materials.length
        ? `上游原料：${materials.slice(0, 4).join('、')}${materials.length > 4 ? ` 等 ${materials.length} 批` : ''}`
        : '上游原料：没有领料记录'
    )
    const usedIn = (data.usedIn || []).map((item) => item.workNo)
    lines.push(usedIn.length ? `用于工单：${usedIn.slice(0, 4).join('、')}` : '尚未被工单消耗')
    const shipments = (data.shipments || []).concat(data.derivedShipments || [])
      .map((item) => `${item.shipmentNo} ${item.customerName || ''}`.trim())
    lines.push(shipments.length ? `发货流向：${shipments.slice(0, 3).join('；')}` : '尚未发货给客户')
  }
  const item = {
    code: batchNo,
    type: '批次码',
    tone: 'blue',
    title: batchNo,
    sub: lines[1] || '扫码查看批次追溯',
    time: new Date().toLocaleString('zh-CN', { hour12: false })
  }
  lastResult.value = item
  saveHistory(item)
  uni.showModal({
    title: `批次 ${batchNo}`,
    content: lines.join('\n') || '没有查到该批次的记录',
    showCancel: false,
    confirmColor: '#f97316'
  })
}

const resolveCode = async (rawValue) => {
  const raw = String(rawValue || '').trim()
  if (!raw) {
    uni.showToast({ title: '请输入或扫描条码内容', icon: 'none' })
    return
  }
  if (resolving.value) return
  resolving.value = true
  try {
    let result = null
    const upper = raw.toUpperCase()
    const parsed = parseMesCode(raw)

    if (parsed?.invalid) {
      uni.showModal({
        title: '条码格式有误',
        content: `${parsed.invalid}，标准格式为 MES|WO|工单号、MES|OP|工单号|工序序号 或 MES|BATCH|批次号。`,
        showCancel: false,
        confirmColor: '#f97316'
      })
      return
    }

    if (parsed?.kind === 'BATCH') {
      code.value = raw
      await showBatchTrace(parsed.batchNo)
      return
    }

    if (parsed) {
      result = resolveMesCode(raw, parsed)
    } else if (upper.startsWith('WO')) {
      result = await resolveWorkOrder(raw)
    } else if (upper.startsWith('SH')) {
      result = await resolveShipment(raw)
    } else {
      result = (await resolveProduct(raw)) || (await resolveWorkOrder(raw)) || (await resolveShipment(raw))
    }

    if (!result) {
      uni.showModal({
        title: '未识别到业务单据',
        content: `编码「${raw}」未匹配到工单、产品编码或发货单。请核对后在 Web 端确认基础数据。`,
        showCancel: false,
        confirmColor: '#f97316'
      })
      return
    }

    const item = {
      ...result,
      time: new Date().toLocaleString('zh-CN', { hour12: false })
    }
    lastResult.value = item
    saveHistory(item)
    code.value = raw
    uni.showToast({ title: `识别为${item.type}`, icon: 'none', duration: 700 })
    setTimeout(() => navigate(item.route), 520)
  } catch (e) {
    // 断网时先把编码留在本机，别让工人白扫一次
    if (e && e.code === 'NETWORK') {
      const item = {
        code: raw,
        type: '离线扫码',
        tone: 'blue',
        title: raw,
        sub: '已存本机记录，联网后可再识别',
        time: new Date().toLocaleString('zh-CN', { hour12: false })
      }
      lastResult.value = item
      saveHistory(item)
    }
    // 其余网络与业务错误已由 request 统一提示
  } finally {
    resolving.value = false
  }
}

const startScan = () => {
  if (scanning.value) return
  if (!uni.scanCode) {
    uni.showToast({ title: '当前环境不支持扫码，请手动输入', icon: 'none' })
    return
  }
  scanning.value = true
  uni.scanCode({
    onlyFromCamera: false,
    scanType: ['barCode', 'qrCode', 'datamatrix', 'pdf417'],
    success: (res) => {
      resolveCode(res.result || res.path)
    },
    fail: (err) => {
      const message = String(err?.errMsg || '')
      if (!message.includes('cancel')) {
        uni.showToast({ title: '扫码失败，可手动输入编码', icon: 'none' })
      }
    },
    complete: () => {
      scanning.value = false
    }
  })
}

const useHistory = (item) => {
  code.value = item.code
  resolveCode(item.code)
}

const openResult = () => {
  if (lastResult.value && lastResult.value.route) navigate(lastResult.value.route)
}

onShow(() => {
  if (!ensureAuth()) return
  loadHistory()
  offlinePending.value = queueCount()
})
</script>

<template>
  <view class="mes-page scan-page">
    <MesWatermark />
    <view class="scan-stage">
      <view class="scan-stage-glow"></view>
      <view class="stage-head">
        <view>
          <text class="stage-eyebrow">SCAN TO EXECUTE</text>
          <text class="stage-title">扫码作业</text>
        </view>
        <view class="stage-tag">工单 / 产品 / 发货单</view>
      </view>

      <view class="scanner" @tap="startScan">
        <view class="scanner-frame">
          <view class="frame-corner tl"></view>
          <view class="frame-corner tr"></view>
          <view class="frame-corner bl"></view>
          <view class="frame-corner br"></view>
          <view class="scanner-line"></view>
          <view class="scanner-cross">
            <view class="cross-h"></view>
            <view class="cross-v"></view>
          </view>
          <text class="scanner-tip">{{ resolving ? '正在识别…' : '点击开始扫码' }}</text>
        </view>
      </view>

      <view class="stage-actions">
        <view class="scan-btn" :class="{ disabled: resolving }" @tap="startScan">
          <text>{{ resolving ? '识别中…' : '启动相机扫码' }}</text>
          <text class="scan-btn-mark">⌗</text>
        </view>
        <text class="stage-hint">扫工单码直达报工，扫工序码自动打开对应工序</text>
      </view>
    </view>

    <view class="content">
      <view v-if="lastResult" class="result-card" :class="`result-${lastResult.tone}`" @tap="openResult">
        <view class="result-mark">{{ lastResult.type.slice(0, 1) }}</view>
        <view class="flex1">
          <view class="row gap8">
            <text class="result-type">{{ lastResult.type }}</text>
            <text class="muted tiny">最近识别</text>
          </view>
          <text class="result-title ellipsis">{{ lastResult.title }}</text>
          <text class="result-sub ellipsis">{{ lastResult.sub }}</text>
        </view>
        <view class="result-go" v-if="lastResult.route">前往</view>
      </view>

      <view v-if="offlinePending" class="offline-banner" @tap="navigate('/pages/offline/list')">
        <view class="offline-mark">离</view>
        <view class="flex1">
          <text class="offline-title">有 {{ offlinePending }} 条离线记录待同步</text>
          <text class="offline-sub">联网后会自动上传，也可以点这里手动同步</text>
        </view>
        <text class="offline-go">查看</text>
      </view>

      <view class="scan-guide">
        <view class="guide-head">
          <text class="guide-title">扫工单报工</text>
          <text class="guide-badge">现场直达</text>
        </view>
        <text class="guide-desc">
          扫描 Web 端打印的工单码进入工单详情；扫描工序码自动打开该工序报工弹层并带入数量。
        </text>
        <view class="guide-codes">
          <view class="guide-code-row">
            <text class="guide-code-label">工单码</text>
            <text class="guide-code mono">MES|WO|工单号</text>
          </view>
          <view class="guide-code-row">
            <text class="guide-code-label">工序码</text>
            <text class="guide-code mono">MES|OP|工单号|工序序号</text>
          </view>
          <view class="guide-code-row">
            <text class="guide-code-label">批次码</text>
            <text class="guide-code mono">MES|BATCH|批次号</text>
          </view>
        </view>
      </view>

      <view class="panel manual-panel">
        <view class="section-title">手动输入编码</view>
        <view class="manual-row">
          <view class="input-box flex1">
            <input
              v-model="code"
              class="input"
              placeholder="例如 WO202610200001"
              placeholder-class="manual-placeholder"
              confirm-type="search"
              @confirm="resolveCode(code)"
            />
          </view>
          <view class="btn btn-dark manual-btn" :class="{ disabled: resolving }" @tap="resolveCode(code)">
            识别
          </view>
        </view>
        <view class="rule-row">
          <text class="rule-item"><text class="rule-dot orange"></text>WO 开头 · 工单</text>
          <text class="rule-item"><text class="rule-dot orange"></text>MES|WO / MES|OP · 工单码 / 工序码</text>
          <text class="rule-item"><text class="rule-dot blue"></text>产品编码 · 物料</text>
          <text class="rule-item"><text class="rule-dot green"></text>SH 开头 · 发货单</text>
        </view>
      </view>

      <view class="panel history-panel">
        <view class="panel-head">
          <view class="section-title tight">扫码记录</view>
          <text v-if="history.length" class="clear-history" @tap="clearHistory">清空</text>
        </view>

        <view v-if="!history.length" class="empty history-empty">
          <view class="empty-mark">⌗</view>
          <text>还没有扫码记录</text>
          <text class="muted tiny">识别成功后会自动保留最近 12 条</text>
        </view>

        <view v-for="item in history" :key="item.code + item.time" class="history-row" @tap="useHistory(item)">
          <view class="history-type" :class="`history-${item.tone}`">{{ item.type.slice(0, 2) }}</view>
          <view class="flex1">
            <text class="history-title ellipsis">{{ item.title }}</text>
            <text class="history-sub ellipsis mono">{{ item.code }}</text>
          </view>
          <text class="history-time">{{ (item.time || '').slice(-8) }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.scan-page {
  padding-bottom: calc(40rpx + env(safe-area-inset-bottom));
}

.scan-stage {
  position: relative;
  overflow: hidden;
  padding: 32rpx 32rpx 38rpx;
  background: linear-gradient(160deg, #0b1220 0%, #111827 55%, #1b2433 100%);
  color: #ffffff;
}

.scan-stage-glow {
  position: absolute;
  top: -120rpx;
  left: 50%;
  width: 560rpx;
  height: 560rpx;
  margin-left: -280rpx;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(249, 115, 22, 0.24) 0%, rgba(249, 115, 22, 0) 68%);
}

.stage-head {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.stage-eyebrow {
  display: block;
  color: #fdba74;
  font-size: 18rpx;
  letter-spacing: 4rpx;
}

.stage-title {
  display: block;
  margin-top: 8rpx;
  font-size: 42rpx;
  font-weight: 700;
}

.stage-tag {
  padding: 6rpx 16rpx;
  border: 1rpx solid rgba(148, 163, 184, 0.24);
  border-radius: 999rpx;
  color: #cbd5e1;
  font-size: 19rpx;
}

.scanner {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 34rpx 0 26rpx;
}

.scanner-frame {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 380rpx;
  height: 380rpx;
  border-radius: 24rpx;
  background: rgba(15, 23, 42, 0.5);
  box-shadow: inset 0 0 60rpx rgba(0, 0, 0, 0.4);
}

.frame-corner {
  position: absolute;
  width: 52rpx;
  height: 52rpx;
  border-color: #f97316;
  border-style: solid;
}

.frame-corner.tl {
  top: -2rpx;
  left: -2rpx;
  border-width: 6rpx 0 0 6rpx;
  border-radius: 16rpx 0 0 0;
}

.frame-corner.tr {
  top: -2rpx;
  right: -2rpx;
  border-width: 6rpx 6rpx 0 0;
  border-radius: 0 16rpx 0 0;
}

.frame-corner.bl {
  bottom: -2rpx;
  left: -2rpx;
  border-width: 0 0 6rpx 6rpx;
  border-radius: 0 0 0 16rpx;
}

.frame-corner.br {
  right: -2rpx;
  bottom: -2rpx;
  border-width: 0 6rpx 6rpx 0;
  border-radius: 0 0 16rpx 0;
}

.scanner-line {
  position: absolute;
  left: 22rpx;
  right: 22rpx;
  top: 50%;
  height: 3rpx;
  background: linear-gradient(90deg, rgba(249, 115, 22, 0), #fb923c, rgba(249, 115, 22, 0));
  box-shadow: 0 0 18rpx rgba(249, 115, 22, 0.9);
  animation: scan-move 2.2s ease-in-out infinite;
}

@keyframes scan-move {
  0% {
    transform: translateY(-120rpx);
    opacity: 0.3;
  }
  50% {
    transform: translateY(120rpx);
    opacity: 1;
  }
  100% {
    transform: translateY(-120rpx);
    opacity: 0.3;
  }
}

.scanner-cross {
  position: relative;
  width: 60rpx;
  height: 60rpx;
  opacity: 0.7;
}

.cross-h,
.cross-v {
  position: absolute;
  background: #94a3b8;
}

.cross-h {
  top: 28rpx;
  left: 0;
  width: 60rpx;
  height: 2rpx;
}

.cross-v {
  top: 0;
  left: 29rpx;
  width: 2rpx;
  height: 60rpx;
}

.scanner-tip {
  position: absolute;
  bottom: 26rpx;
  color: #94a3b8;
  font-size: 21rpx;
}

.stage-actions {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.scan-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
  width: 100%;
  height: 92rpx;
  border-radius: 14rpx;
  background: linear-gradient(135deg, #fb923c, #f97316 55%, #ea580c);
  color: #ffffff;
  font-size: 29rpx;
  font-weight: 700;
  box-shadow: 0 14rpx 30rpx rgba(249, 115, 22, 0.3);
}

.scan-btn.disabled {
  opacity: 0.7;
}

.scan-btn-mark {
  font-size: 34rpx;
  font-weight: 400;
}

.stage-hint {
  margin-top: 14rpx;
  color: #64748b;
  font-size: 20rpx;
}

.result-card {
  display: flex;
  align-items: center;
  gap: 18rpx;
  margin-bottom: 20rpx;
  padding: 22rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 16rpx;
  background: #ffffff;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.05);
}

.result-orange {
  border-left: 8rpx solid #f97316;
}

.result-blue {
  border-left: 8rpx solid #3b82f6;
}

.result-green {
  border-left: 8rpx solid #22c55e;
}

.result-mark {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64rpx;
  height: 64rpx;
  border-radius: 14rpx;
  background: #111827;
  color: #fdba74;
  font-size: 26rpx;
  font-weight: 700;
}

.result-type {
  color: #c2410c;
  font-size: 21rpx;
  font-weight: 600;
}

.result-title {
  display: block;
  margin-top: 2rpx;
  color: #0f172a;
  font-size: 27rpx;
  font-weight: 700;
}

.result-sub {
  display: block;
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 21rpx;
}

.result-go {
  flex: none;
  padding: 8rpx 16rpx;
  border-radius: 8rpx;
  background: #fff7ed;
  color: #c2410c;
  font-size: 22rpx;
  font-weight: 600;
}

.offline-banner {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 20rpx;
  padding: 20rpx 22rpx;
  border: 1rpx solid #fde68a;
  border-radius: 16rpx;
  background: linear-gradient(135deg, #fffbeb 0%, #ffffff 70%);
}

.offline-mark {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56rpx;
  height: 56rpx;
  border-radius: 12rpx;
  background: #111827;
  color: #fbbf24;
  font-size: 24rpx;
  font-weight: 700;
}

.offline-title {
  display: block;
  color: #92400e;
  font-size: 25rpx;
  font-weight: 600;
}

.offline-sub {
  display: block;
  margin-top: 2rpx;
  color: #a16207;
  font-size: 20rpx;
}

.offline-go {
  flex: none;
  color: #b45309;
  font-size: 22rpx;
  font-weight: 600;
}

.scan-guide {
  margin-bottom: 20rpx;
  padding: 24rpx;
  border: 1rpx solid #fed7aa;
  border-radius: 16rpx;
  background: linear-gradient(135deg, #fff7ed 0%, #ffffff 68%);
  box-shadow: 0 8rpx 24rpx rgba(249, 115, 22, 0.08);
}

.guide-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.guide-title {
  color: #9a3412;
  font-size: 28rpx;
  font-weight: 700;
}

.guide-badge {
  padding: 2rpx 12rpx;
  border-radius: 999rpx;
  background: #f97316;
  color: #ffffff;
  font-size: 18rpx;
}

.guide-desc {
  display: block;
  margin-top: 10rpx;
  color: #7c2d12;
  font-size: 22rpx;
  line-height: 1.6;
}

.guide-codes {
  display: flex;
  flex-direction: column;
  gap: 10rpx;
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: 1rpx dashed #fdba74;
}

.guide-code-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.guide-code-label {
  flex: none;
  color: #c2410c;
  font-size: 20rpx;
}

.guide-code {
  color: #431407;
  font-size: 21rpx;
  word-break: break-all;
}

.manual-row {
  display: flex;
  align-items: center;
  gap: 14rpx;
}

.manual-placeholder {
  color: #b6c2d1;
}

.manual-btn {
  flex: none;
  width: 140rpx;
  height: 82rpx;
  padding: 0;
  font-size: 26rpx;
}

.rule-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx 24rpx;
  margin-top: 20rpx;
}

.rule-item {
  display: flex;
  align-items: center;
  color: #94a3b8;
  font-size: 20rpx;
}

.rule-dot {
  width: 10rpx;
  height: 10rpx;
  margin-right: 8rpx;
  border-radius: 50%;
}

.rule-dot.orange {
  background: #f97316;
}

.rule-dot.blue {
  background: #3b82f6;
}

.rule-dot.green {
  background: #22c55e;
}

.clear-history {
  color: #94a3b8;
  font-size: 21rpx;
}

.history-empty {
  padding: 40rpx 20rpx 24rpx;
  gap: 4rpx;
}

.history-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 18rpx 0;
  border-top: 1rpx solid #f1f5f9;
}

.history-row:first-of-type {
  border-top: none;
}

.history-type {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64rpx;
  height: 64rpx;
  border-radius: 12rpx;
  font-size: 20rpx;
  font-weight: 600;
}

.history-orange {
  background: #fff7ed;
  color: #c2410c;
}

.history-blue {
  background: #eff6ff;
  color: #1d4ed8;
}

.history-green {
  background: #f0fdf4;
  color: #15803d;
}

.history-title {
  display: block;
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 500;
}

.history-sub {
  display: block;
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 20rpx;
}

.history-time {
  flex: none;
  color: #b6c2d1;
  font-size: 19rpx;
}
</style>
