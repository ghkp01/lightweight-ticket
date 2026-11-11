<script setup>
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { dashboardApi, shippingApi, workOrderApi } from '../../api'
import { ensureAuth, getUser } from '../../utils/auth'
import { fmtMoney, fmtQty, fmtRate, greeting, progressOf, roleName, todayText } from '../../utils/format'
import MesWatermark from '../../components/Watermark.vue'

const user = ref(getUser() || {})
const summary = ref(null)
const workOrders = ref([])
const shipments = ref([])
const loading = ref(false)
const error = ref('')

const kpis = computed(() => {
  const data = summary.value || {}
  return [
    {
      label: '今日销售额',
      value: fmtMoney(data.todaySales),
      unit: '元',
      foot: `本月 ${fmtMoney(data.monthSales)} 元`
    },
    {
      label: '生产中工单',
      value: fmtQty(data.producingOrders, 0),
      unit: '张',
      foot: `执行中 ${fmtQty(data.activeOrders, 0)} 张订单`
    },
    {
      label: '待发货',
      value: fmtQty(data.pendingShip, 0),
      unit: '单',
      foot: `待处理退货 ${fmtQty(data.pendingReturns, 0)} 单`
    },
    {
      label: '低库存预警',
      value: fmtQty(data.lowStock, 0),
      unit: '项',
      foot: '低于安全库存'
    }
  ]
})

const passRate = computed(() => Number((summary.value || {}).passRate || 0))

const trend = computed(() => {
  const list = (summary.value || {}).trend || []
  const max = Math.max(...list.map((item) => Number(item.amount || 0)), 1)
  return list.map((item) => ({
    ...item,
    height: Math.max(8, Math.round((Number(item.amount || 0) / max) * 100))
  }))
})

const quickEntries = [
  { name: '扫码作业', char: '扫', url: '/pages/scan/scan', tab: true, accent: true },
  { name: '生产工单', char: '工', url: '/pages/workorder/list', tab: true },
  { name: '入库申请', char: '入', url: '/pages/inbound/list' },
  { name: '出库申请', char: '出', url: '/pages/outbound/list' },
  { name: '质量检验', char: '检', url: '/pages/quality/list' },
  { name: '不良处置', char: '良', url: '/pages/defect/list' },
  { name: '通知中心', char: '信', url: '/pages/notice/list' },
  { name: '我的', char: '我', url: '/pages/profile/profile', tab: true }
]

const todos = computed(() => {
  const list = []
  const lowStock = (summary.value || {}).lowStockList || []
  lowStock.slice(0, 2).forEach((item) => {
    list.push({
      key: `stock-${item.productCode}`,
      tone: 'red',
      tag: '库存',
      title: `${item.productName} 低于安全库存`,
      sub: `${item.productCode} · ${item.warehouseName} · 当前 ${fmtQty(item.qty)} / 安全 ${fmtQty(item.safetyStock)}`,
      url: `/pages/product/detail?code=${encodeURIComponent(item.productCode || '')}`
    })
  })
  workOrders.value.slice(0, 2).forEach((item) => {
    list.push({
      key: `work-${item.id}`,
      tone: 'orange',
      tag: '生产',
      title: `${item.workNo} 生产中`,
      sub: `${item.productName} · 进度 ${progressOf(item.doneQty, item.planQty)}%`,
      url: `/pages/workorder/detail?id=${item.id}`
    })
  })
  shipments.value.slice(0, 2).forEach((item) => {
    list.push({
      key: `ship-${item.id}`,
      tone: 'blue',
      tag: '发货',
      title: `${item.shipmentNo} 待发货`,
      sub: `${item.customerName} · ${item.carrier || '未指定承运商'}`,
      url: `/pages/shipment/detail?id=${item.id}`
    })
  })
  return list.slice(0, 5)
})

const load = async () => {
  if (loading.value) return
  loading.value = true
  error.value = ''
  user.value = getUser() || {}
  try {
    const [summaryResult, workResult, shipResult] = await Promise.allSettled([
      dashboardApi.summary(),
      workOrderApi.list({ status: 'IN_PROGRESS', page: 1, size: 4 }),
      shippingApi.list({ status: 'DRAFT', page: 1, size: 4 })
    ])
    if (summaryResult.status === 'fulfilled') {
      summary.value = summaryResult.value || {}
    } else {
      error.value = '看板数据加载失败，请确认后端服务与登录状态'
    }
    workOrders.value = workResult.status === 'fulfilled' ? workResult.value?.records || [] : []
    shipments.value = shipResult.status === 'fulfilled' ? shipResult.value?.records || [] : []
  } finally {
    loading.value = false
    uni.stopPullDownRefresh()
  }
}

const goto = (url, tab = false) => {
  if (tab) {
    uni.switchTab({ url })
  } else {
    uni.navigateTo({ url })
  }
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
  <view class="mes-page home-page">
    <MesWatermark />
    <view class="hero home-hero">
      <view class="hero-inner">
        <view class="row between">
          <view class="hero-eyebrow">SHOP FLOOR · LIVE</view>
          <view class="role-chip">{{ roleName(user.role) }}</view>
        </view>

        <view class="hello-row">
          <view class="flex1">
            <view class="hero-title">{{ greeting() }}，{{ user.realName || user.username }}</view>
            <view class="hero-sub">{{ todayText() }} · 扫工单码/工序码可直接报工</view>
          </view>
          <view class="scan-orb" @tap="goto('/pages/scan/scan', true)">
            <view class="scan-frame">
              <view class="corner tl"></view>
              <view class="corner tr"></view>
              <view class="corner bl"></view>
              <view class="corner br"></view>
              <view class="scan-line"></view>
            </view>
            <text class="scan-text">扫码</text>
          </view>
        </view>

        <view class="hero-strip">
          <view class="strip-item">
            <text class="strip-label">一次合格率</text>
            <text class="strip-value">{{ fmtRate(passRate) }}</text>
          </view>
          <view class="strip-line"></view>
          <view class="strip-item">
            <text class="strip-label">今日销售</text>
            <text class="strip-value">{{ fmtMoney((summary || {}).todaySales) }}</text>
          </view>
          <view class="strip-line"></view>
          <view class="strip-item">
            <text class="strip-label">生产工单</text>
            <text class="strip-value">{{ fmtQty((summary || {}).producingOrders, 0) }}</text>
          </view>
        </view>
      </view>
    </view>

    <view class="content">
      <view v-if="loading && !summary" class="panel">
        <view class="skeleton sk-title"></view>
        <view class="skeleton sk-line"></view>
        <view class="skeleton sk-line short"></view>
      </view>

      <view v-else>
        <view class="kpi-grid">
          <view v-for="item in kpis" :key="item.label" class="kpi-card">
            <view class="kpi-label">{{ item.label }}</view>
            <view class="kpi-value">
              {{ item.value }}<text class="kpi-unit">{{ item.unit }}</text>
            </view>
            <view class="kpi-foot">{{ item.foot }}</view>
          </view>
        </view>

        <view v-if="error" class="error-banner">
          <text>{{ error }}</text>
          <text class="retry" @tap="load">重试</text>
        </view>

        <view class="panel trend-panel">
          <view class="panel-head">
            <view class="section-title tight">近 7 日销售</view>
            <view class="trend-total">
              本月 <text class="text-orange strong">{{ fmtMoney((summary || {}).monthSales) }}</text>
            </view>
          </view>
          <view class="trend-chart">
            <view v-for="item in trend" :key="item.date" class="trend-col">
              <view class="trend-bar-wrap">
                <view class="trend-bar" :style="{ height: item.height + '%' }"></view>
              </view>
              <text class="trend-label">{{ item.date }}</text>
            </view>
            <view v-if="!trend.length" class="trend-empty">暂无趋势数据</view>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head">
            <view class="section-title tight">快捷入口</view>
            <view class="muted tiny">7 个现场场景</view>
          </view>
          <view class="quick-grid">
            <view
              v-for="item in quickEntries"
              :key="item.name"
              class="quick-item"
              :class="{ accent: item.accent }"
              @tap="goto(item.url, item.tab)"
            >
              <view class="quick-icon">{{ item.char }}</view>
              <text class="quick-name">{{ item.name }}</text>
            </view>
          </view>
        </view>

        <view class="panel todo-panel">
          <view class="panel-head">
            <view class="section-title tight">现场待办</view>
            <view class="muted tiny">{{ todos.length }} 项</view>
          </view>

          <view v-if="!todos.length" class="empty todo-empty">
            <view class="empty-mark">✓</view>
            <text>当前没有紧急待办</text>
            <text class="muted tiny">库存、工单与发货状态都正常</text>
          </view>

          <view
            v-for="item in todos"
            :key="item.key"
            class="todo-row"
            @tap="goto(item.url)"
          >
            <view class="todo-rail" :class="`rail-${item.tone}`"></view>
            <view class="flex1">
              <view class="row gap8">
                <text class="todo-tag" :class="`tag-${item.tone}`">{{ item.tag }}</text>
                <text class="todo-title ellipsis">{{ item.title }}</text>
              </view>
              <view class="todo-sub ellipsis">{{ item.sub }}</view>
            </view>
            <view class="chevron"></view>
          </view>
        </view>

        <view class="home-foot">
          <text>智造工单 MES · 移动端</text>
          <text>数据来自 8080 生产服务</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.home-page {
  padding-bottom: calc(40rpx + env(safe-area-inset-bottom));
}

.home-hero {
  padding-bottom: 34rpx;
}

.role-chip {
  padding: 5rpx 16rpx;
  border: 1rpx solid rgba(253, 186, 116, 0.35);
  border-radius: 999rpx;
  background: rgba(249, 115, 22, 0.12);
  color: #fdba74;
  font-size: 21rpx;
}

.hello-row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-top: 18rpx;
}

.scan-orb {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 128rpx;
  height: 128rpx;
  border: 1rpx solid rgba(253, 186, 116, 0.32);
  border-radius: 26rpx;
  background: linear-gradient(145deg, rgba(249, 115, 22, 0.22), rgba(249, 115, 22, 0.06));
}

.scan-frame {
  position: relative;
  width: 48rpx;
  height: 48rpx;
}

.corner {
  position: absolute;
  width: 14rpx;
  height: 14rpx;
  border-color: #fdba74;
  border-style: solid;
}

.corner.tl {
  top: 0;
  left: 0;
  border-width: 3rpx 0 0 3rpx;
}

.corner.tr {
  top: 0;
  right: 0;
  border-width: 3rpx 3rpx 0 0;
}

.corner.bl {
  bottom: 0;
  left: 0;
  border-width: 0 0 3rpx 3rpx;
}

.corner.br {
  bottom: 0;
  right: 0;
  border-width: 0 3rpx 3rpx 0;
}

.scan-line {
  position: absolute;
  top: 50%;
  left: 6rpx;
  right: 6rpx;
  height: 2rpx;
  background: #f97316;
  box-shadow: 0 0 10rpx rgba(249, 115, 22, 0.8);
}

.scan-text {
  margin-top: 8rpx;
  color: #fdba74;
  font-size: 21rpx;
}

.hero-strip {
  display: flex;
  align-items: center;
  margin-top: 30rpx;
  padding-top: 24rpx;
  border-top: 1rpx solid rgba(148, 163, 184, 0.16);
}

.strip-item {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.strip-label {
  color: #94a3b8;
  font-size: 20rpx;
}

.strip-value {
  margin-top: 2rpx;
  color: #ffffff;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 32rpx;
  font-weight: 700;
}

.strip-line {
  width: 1rpx;
  height: 40rpx;
  margin: 0 18rpx;
  background: rgba(148, 163, 184, 0.16);
}

.content {
  padding-top: 22rpx;
}

.kpi-unit {
  margin-left: 6rpx;
  color: #94a3b8;
  font-size: 21rpx;
  font-weight: 500;
}

.sk-title {
  width: 40%;
  height: 36rpx;
  margin-bottom: 24rpx;
}

.sk-line {
  height: 24rpx;
  margin-bottom: 14rpx;
}

.sk-line.short {
  width: 62%;
  margin-bottom: 0;
}

.error-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20rpx;
  padding: 18rpx 22rpx;
  border: 1rpx solid #fed7aa;
  border-radius: 12rpx;
  background: #fff7ed;
  color: #c2410c;
  font-size: 23rpx;
}

.retry {
  color: #9a3412;
  font-weight: 700;
}

.trend-panel {
  padding-bottom: 16rpx;
}

.trend-total {
  color: #94a3b8;
  font-size: 22rpx;
}

.trend-chart {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 12rpx;
  height: 190rpx;
  padding-top: 10rpx;
}

.trend-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
}

.trend-bar-wrap {
  flex: 1;
  display: flex;
  align-items: flex-end;
  width: 100%;
  max-width: 46rpx;
}

.trend-bar {
  width: 100%;
  min-height: 8rpx;
  border-radius: 8rpx 8rpx 3rpx 3rpx;
  background: linear-gradient(180deg, #fdba74 0%, #f97316 60%, #ea580c 100%);
  box-shadow: 0 4rpx 10rpx rgba(249, 115, 22, 0.22);
}

.trend-label {
  margin-top: 10rpx;
  color: #94a3b8;
  font-size: 18rpx;
}

.trend-empty {
  position: absolute;
  left: 0;
  right: 0;
  top: 40%;
  color: #94a3b8;
  font-size: 23rpx;
  text-align: center;
}

.todo-empty {
  padding: 40rpx 20rpx 30rpx;
  gap: 4rpx;
}

.todo-row {
  display: flex;
  align-items: center;
  gap: 18rpx;
  padding: 20rpx 4rpx;
  border-top: 1rpx solid #f1f5f9;
}

.todo-row:first-of-type {
  border-top: none;
}

.todo-rail {
  flex: none;
  width: 6rpx;
  height: 56rpx;
  border-radius: 999rpx;
}

.rail-red {
  background: #ef4444;
}

.rail-orange {
  background: #f97316;
}

.rail-blue {
  background: #3b82f6;
}

.todo-tag {
  flex: none;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
  font-size: 18rpx;
  font-weight: 600;
}

.tag-red {
  background: #fef2f2;
  color: #b91c1c;
}

.tag-orange {
  background: #fff7ed;
  color: #c2410c;
}

.tag-blue {
  background: #eff6ff;
  color: #1d4ed8;
}

.todo-title {
  flex: 1;
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 600;
}

.todo-sub {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 21rpx;
}

.home-foot {
  display: flex;
  justify-content: space-between;
  padding: 14rpx 8rpx 24rpx;
  color: #b6c2d1;
  font-size: 19rpx;
}
</style>
