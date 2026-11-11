<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { api, fmtMoney, fmtQty } from '../api'
import StatusTag from '../components/StatusTag.vue'

const router = useRouter()
const loading = ref(false)
const data = ref<any>({})
const trendRef = ref<HTMLElement>()
const pieRef = ref<HTMLElement>()
let trendChart: echarts.ECharts | null = null
let pieChart: echarts.ECharts | null = null

/** 今天的活儿：待报工、待领料、外协待收回、逾期在制单，点一下进对应列表 */
const tasks = computed<any[]>(() => data.value.tasks || [])
const goBoard = (path: string) => router.push(path)

// 经营统计按本位币口径，只汇总人民币订单；外币订单只在列表/详情按原币展示
const currencySymbol = (code: string) => {
  if (code === 'USD') return '$'
  if (code === 'EUR') return '€'
  return '¥'
}

const statusLabel: Record<string, string> = {
  DRAFT: '草稿',
  RELEASED: '已下达',
  IN_PROGRESS: '生产中',
  FINISHED: '已完工',
  CLOSED: '已关闭'
}

const load = async () => {
  loading.value = true
  try {
    data.value = await api.get('/dashboard/summary')
    await nextTick()
    renderCharts()
  } finally {
    loading.value = false
  }
}

const renderCharts = () => {
  if (trendRef.value) {
    if (!trendChart) trendChart = echarts.init(trendRef.value)
    const trend = data.value.trend || []
    trendChart.setOption({
      grid: { left: 8, right: 16, top: 24, bottom: 4, containLabel: true },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (value: number) => `¥ ${fmtMoney(value)}`,
        textStyle: { fontFamily: 'Microsoft YaHei' }
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: trend.map((item: any) => item.date),
        axisLine: { lineStyle: { color: '#e4e7ec' } },
        axisTick: { show: false },
        axisLabel: { color: '#98a2b3', fontSize: 11 }
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: '#f2f4f7' } },
        axisLabel: { color: '#98a2b3', fontSize: 11, formatter: (value: number) => (value >= 10000 ? `${value / 10000}w` : value) }
      },
      series: [
        {
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          data: trend.map((item: any) => Number(item.amount)),
          lineStyle: { width: 2.4, color: '#2456d8' },
          itemStyle: { color: '#2456d8', borderColor: '#fff', borderWidth: 2 },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(36,86,216,0.26)' },
              { offset: 1, color: 'rgba(36,86,216,0.02)' }
            ])
          }
        }
      ]
    })
  }
  if (pieRef.value) {
    if (!pieChart) pieChart = echarts.init(pieRef.value)
    const colors: Record<string, string> = {
      DRAFT: '#98a2b3',
      RELEASED: '#5b8def',
      IN_PROGRESS: '#f79009',
      FINISHED: '#12b76a',
      CLOSED: '#d0d5dd'
    }
    const rows = data.value.workOrderStatus || []
    pieChart.setOption({
      tooltip: { trigger: 'item', textStyle: { fontFamily: 'Microsoft YaHei' } },
      legend: {
        bottom: 0,
        icon: 'circle',
        itemWidth: 7,
        itemHeight: 7,
        textStyle: { color: '#667085', fontSize: 11 }
      },
      series: [
        {
          type: 'pie',
          radius: ['52%', '72%'],
          center: ['50%', '44%'],
          avoidLabelOverlap: true,
          itemStyle: { borderColor: '#fff', borderWidth: 3, borderRadius: 4 },
          label: { show: false },
          data: rows.map((row: any) => ({
            name: statusLabel[row.status] || row.status,
            value: row.value,
            itemStyle: { color: colors[row.status] || '#98a2b3' }
          }))
        }
      ]
    })
  }
}

const resize = () => {
  trendChart?.resize()
  pieChart?.resize()
}

onMounted(() => {
  load()
  window.addEventListener('resize', resize)
})

onUnmounted(() => {
  window.removeEventListener('resize', resize)
  trendChart?.dispose()
  pieChart?.dispose()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">生产运营概览</h2>
        <p class="page-sub">订单、工单、库存与质量的实时状态，异常项会直接标红</p>
      </div>
      <el-button @click="load">刷新数据</el-button>
    </div>

    <div v-if="tasks.length" class="task-panel">
      <div class="task-panel-head">
        <span class="task-panel-title">今天的活儿</span>
        <span class="muted">都是「还没做完」的口径，点一下就进对应列表</span>
      </div>
      <div class="task-grid">
        <div
          v-for="item in tasks"
          :key="item.key"
          class="task-card"
          :class="['tone-' + item.tone, { 'task-zero': !item.value }]"
          @click="goBoard(item.path)"
        >
          <div class="task-value">{{ item.value || 0 }}</div>
          <div class="task-label">{{ item.label }}</div>
          <div class="task-hint">{{ item.value ? item.hint : '当前没有待办' }}</div>
        </div>
      </div>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-label">本月销售额（人民币）</div>
        <div class="kpi-value"><small style="font-size: 14px; margin: 0">¥</small>{{ fmtMoney(data.monthSales) }}</div>
        <div class="kpi-foot">今日 ¥{{ fmtMoney(data.todaySales) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">进行中订单</div>
        <div class="kpi-value">{{ data.activeOrders || 0 }}<small>单</small></div>
        <div class="kpi-foot">覆盖确认、生产、待发、发货</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">在制工单</div>
        <div class="kpi-value">{{ data.producingOrders || 0 }}<small>张</small></div>
        <div class="kpi-foot">已下达与生产中</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">待发货订单</div>
        <div class="kpi-value">{{ data.pendingShip || 0 }}<small>单</small></div>
        <div class="kpi-foot">完工检验后进入待发</div>
      </div>
      <div class="kpi-card" :class="{ 'kpi-alert': data.lowStock > 0 }">
        <div class="kpi-label">库存预警</div>
        <div class="kpi-value">{{ data.lowStock || 0 }}<small>项</small></div>
        <div class="kpi-foot">低于安全库存的物料</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">近 30 天一次合格率</div>
        <div class="kpi-value">{{ data.passRate ?? 100 }}<small>%</small></div>
        <div class="kpi-foot">
          报工 {{ fmtQty(data.reportQty) }} 件 · 待处置不良 {{ data.pendingDefects || 0 }} 笔
        </div>
      </div>
    </div>

    <div class="section-grid">
      <div class="panel">
        <div class="card-head">
          <div class="card-title">近 7 日销售额</div>
          <span class="toolbar-tip">按订单创建日期统计</span>
        </div>
        <div class="card-body">
          <div ref="trendRef" class="chart chart-lg"></div>
        </div>
      </div>
      <div class="panel">
        <div class="card-head">
          <div class="card-title">工单状态分布</div>
          <span class="toolbar-tip">全部工单</span>
        </div>
        <div class="card-body">
          <div ref="pieRef" class="chart chart-lg"></div>
        </div>
      </div>
    </div>

    <div class="section-grid" style="margin-top: 16px">
      <div class="panel">
        <div class="card-head">
          <div class="card-title">最近订单</div>
          <el-button link type="primary" @click="router.push('/sales')">查看全部</el-button>
        </div>
        <div class="table-wrap" style="padding-top: 0">
          <el-table :data="data.recentOrders || []" size="small">
            <el-table-column prop="orderNo" label="订单号" min-width="160">
              <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
            </el-table-column>
            <el-table-column prop="customerName" label="客户" min-width="160" show-overflow-tooltip />
            <el-table-column label="金额" width="110" align="right">
              <template #default="{ row }"><span class="money">{{ currencySymbol(row.currency) }}{{ fmtMoney(row.totalAmount) }}</span></template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }"><StatusTag :status="row.status" /></template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="110" />
          </el-table>
        </div>
      </div>

      <div class="panel">
        <div class="card-head">
          <div class="card-title">库存预警</div>
          <el-button link type="primary" @click="router.push('/inventory')">去补货</el-button>
        </div>
        <div class="card-body" style="padding-top: 6px">
          <div v-if="!(data.lowStockList || []).length" class="empty-tip">库存健康，暂无预警物料</div>
          <div v-for="item in data.lowStockList || []" :key="item.productCode" class="warn-row">
            <div class="warn-main">
              <b>{{ item.productName }}</b>
              <span>{{ item.productCode }} · {{ item.warehouseName }}</span>
            </div>
            <div class="warn-num">
              <b class="danger-text">{{ fmtQty(item.qty) }}{{ item.unit }}</b>
              <span>安全 {{ fmtQty(item.safetyStock) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 今天的活儿：四块待办，数字大、颜色带语义，手机上一行两个 */
.task-panel {
  margin-bottom: 16px;
  padding: 14px 16px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
}

.task-panel-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}

.task-panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.task-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.task-card {
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid var(--line);
  background: #fbfcfe;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease;
}

.task-card:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px -14px rgba(16, 24, 40, 0.35);
}

.task-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

.task-label {
  margin-top: 2px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-2);
}

.task-hint {
  margin-top: 2px;
  font-size: 11px;
  color: var(--muted);
  line-height: 1.5;
}

.task-card.tone-warning {
  border-color: var(--state-warning-line);
  background: var(--state-warning-bg);
}

.task-card.tone-warning .task-value {
  color: var(--state-warning-fg);
}

.task-card.tone-danger {
  border-color: var(--state-danger-line);
  background: var(--state-danger-bg);
}

.task-card.tone-danger .task-value {
  color: var(--state-danger-fg);
}

.task-card.tone-primary {
  border-color: var(--state-primary-line);
  background: var(--state-primary-bg);
}

.task-card.tone-primary .task-value {
  color: var(--state-primary-fg);
}

/* 0 件待办不抢眼：数字压回中性色，一眼能跳过 */
.task-card.task-zero {
  background: #fbfcfe;
  border-color: var(--line);
}

.task-card.task-zero .task-value {
  color: var(--muted);
}

@media (max-width: 900px) {
  .task-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.chart {
  width: 100%;
  height: 250px;
}

.empty-tip {
  color: var(--muted);
  font-size: 12px;
  padding: 46px 0;
  text-align: center;
}

.warn-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px dashed #eef1f5;
}

.warn-row:last-child {
  border-bottom: none;
}

.warn-main b {
  display: block;
  font-size: 12.5px;
  font-weight: 500;
}

.warn-main span {
  display: block;
  color: var(--muted);
  font-size: 11px;
  margin-top: 2px;
}

.warn-num {
  text-align: right;
}

.warn-num b {
  display: block;
  font-family: Bahnschrift, monospace;
  font-size: 13px;
}

.warn-num span {
  display: block;
  color: var(--muted);
  font-size: 10px;
  margin-top: 2px;
}
</style>
