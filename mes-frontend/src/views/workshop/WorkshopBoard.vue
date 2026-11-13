<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import * as echarts from 'echarts'
import { api, fmtQty } from '../../api'
import PriorityTag from '../../components/PriorityTag.vue'

const REFRESH_MS = 30000
const PRIORITY_RANK: Record<string, number> = { RUSH: 0, URGENT: 1, NORMAL: 2 }
const STATUS_LABEL: Record<string, string> = {
  SCHEDULED: '已排产',
  RELEASED: '已下达',
  IN_PROGRESS: '生产中',
  REWORK: '返工中',
  FINISHED: '已完工'
}

const pad = (value: number) => String(value).padStart(2, '0')

const loading = ref(false)
const refreshing = ref(false)
const loaded = ref(false)
const keyword = ref('')
const orgId = ref<number | null>(null)
const workshopOptions = ref<any[]>([])
const lastUpdated = ref('')
const board = ref<any>({ kpi: {}, operations: [], locations: [] })
const chartRef = ref<HTMLElement>()
let chart: echarts.ECharts | null = null
let timer: number | undefined

const kpi = computed(() => board.value.kpi || {})
const operations = computed(() => board.value.operations || [])
const priorityRank = (priority: any) => PRIORITY_RANK[priority || 'NORMAL'] ?? 3

const isHit = (card: any) => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return false
  return (
    String(card.workNo || '').toLowerCase().includes(kw) ||
    String(card.productName || '').toLowerCase().includes(kw)
  )
}

const filteredLocations = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return (board.value.locations || []).filter((card: any) => {
    if (!kw) return true
    return (
      String(card.workNo || '').toLowerCase().includes(kw) ||
      String(card.productName || '').toLowerCase().includes(kw)
    )
  })
})

const columns = computed(() => {
  const orderMap = new Map<string, number>()
  operations.value.forEach((op: any, index: number) => orderMap.set(String(op.name), index))
  const groups = new Map<string, any[]>()
  for (const card of filteredLocations.value) {
    const key = card.currentOperation || '未开工'
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(card)
  }
  return [...groups.entries()]
    .sort((a, b) => {
      const rankA = orderMap.has(a[0]) ? orderMap.get(a[0])! : a[0] === '未开工' ? 9998 : 9999
      const rankB = orderMap.has(b[0]) ? orderMap.get(b[0])! : b[0] === '未开工' ? 9998 : 9999
      return rankA - rankB || a[0].localeCompare(b[0])
    })
    .map(([name, items]) => ({
      name,
      items: [...items].sort((a: any, b: any) => {
        const rank = priorityRank(a.priority) - priorityRank(b.priority)
        if (rank !== 0) return rank
        return String(a.scheduleDate || '9999-99-99').localeCompare(String(b.scheduleDate || '9999-99-99'))
      })
    }))
})

const pct = (card: any) => {
  const plan = Number(card.planQty || 0)
  if (!plan) return 0
  return Math.min(100, Math.round((Number(card.doneQty || 0) / plan) * 100))
}

const fmtTime = (value: any) => (value ? String(value).replace('T', ' ').slice(0, 16) : '暂无报工')

/** 缺料行数，0 表示已齐套（无 BOM 也按 0 处理，不打扰） */
const shortageLines = (card: any) => Number(card.shortageLines || 0)

/** 齐套率 = (需求 - 待领) / 需求 */
const kitPct = (card: any) => {
  const need = Number(card.materialNeedQty || 0)
  if (!need) return 100
  const pending = Number(card.materialPendingQty || 0)
  return Math.max(0, Math.min(100, Math.round(((need - pending) / need) * 100)))
}

const highlight = (value: any) => {
  const text = String(value ?? '')
  const kw = keyword.value.trim()
  if (!kw) return [{ text, hit: false }]
  const lowered = text.toLowerCase()
  const target = kw.toLowerCase()
  const parts: { text: string; hit: boolean }[] = []
  let cursor = 0
  while (cursor < text.length) {
    const index = lowered.indexOf(target, cursor)
    if (index < 0) {
      parts.push({ text: text.slice(cursor), hit: false })
      break
    }
    if (index > cursor) parts.push({ text: text.slice(cursor, index), hit: false })
    parts.push({ text: text.slice(index, index + target.length), hit: true })
    cursor = index + target.length
  }
  return parts
}

const renderChart = () => {
  if (!chartRef.value) {
    chart?.dispose()
    chart = null
    return
  }
  if (!chart) chart = echarts.init(chartRef.value)
  const rows = operations.value
  chart.setOption({
    color: ['#d0d5dd', '#2456d8', '#12b76a'],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      textStyle: { fontFamily: 'Microsoft YaHei' }
    },
    legend: {
      right: 4,
      top: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: '#667085', fontSize: 11 },
      data: ['待加工', '加工中', '已完成']
    },
    grid: { left: 8, right: 20, top: 32, bottom: 6, containLabel: true },
    xAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f2f4f7' } },
      axisLabel: { color: '#98a2b3', fontSize: 11 }
    },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rows.map((row: any) => row.name),
      axisLine: { lineStyle: { color: '#e4e7ec' } },
      axisTick: { show: false },
      axisLabel: { color: '#475467', fontSize: 12 }
    },
    series: [
      {
        name: '待加工',
        type: 'bar',
        stack: 'total',
        barWidth: 14,
        itemStyle: { color: '#d0d5dd', borderRadius: [3, 0, 0, 3] },
        emphasis: { focus: 'series' },
        data: rows.map((row: any) => Number(row.pending || 0))
      },
      {
        name: '加工中',
        type: 'bar',
        stack: 'total',
        itemStyle: { color: '#2456d8' },
        emphasis: { focus: 'series' },
        data: rows.map((row: any) => Number(row.inProgress || 0))
      },
      {
        name: '已完成',
        type: 'bar',
        stack: 'total',
        itemStyle: { color: '#12b76a', borderRadius: [0, 3, 3, 0] },
        emphasis: { focus: 'series' },
        data: rows.map((row: any) => Number(row.done || 0))
      }
    ]
  })
}

const load = async () => {
  if (refreshing.value) return
  refreshing.value = true
  if (!loaded.value) loading.value = true
  try {
    const data = await api.get('/workshop/board', { orgId: orgId.value || undefined })
    board.value = {
      kpi: data?.kpi || {},
      operations: data?.operations || [],
      locations: data?.locations || []
    }
    loaded.value = true
    const now = new Date()
    lastUpdated.value = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
    await nextTick()
    renderChart()
  } finally {
    refreshing.value = false
    loading.value = false
  }
}

const resize = () => chart?.resize()

const loadWorkshops = async () => {
  workshopOptions.value = await api.get('/orgs/options', { type: 'WORKSHOP', limit: 50 })
}

const changeScope = () => {
  load()
}

/** 投屏：车间大屏是独立的免登录页面，新窗口打开，不动当前页面 */
const openWall = () => {
  window.open(`${location.origin}${location.pathname}#/screen`, '_blank')
}

onMounted(() => {
  loadWorkshops()
  load()
  timer = window.setInterval(() => {
    if (!document.hidden) load()
  }, REFRESH_MS)
  window.addEventListener('resize', resize)
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">车间看板</h2>
        <p class="page-sub">
          在制工单、工序负载与工位分布实时汇总
          <span v-if="lastUpdated">· 更新于 {{ lastUpdated }}</span>
          · 每 30 秒自动刷新
        </p>
      </div>
      <div class="page-actions">
        <el-select
          v-model="orgId"
          clearable
          filterable
          placeholder="全厂"
          style="width: 150px"
          @change="changeScope"
        >
          <el-option v-for="item in workshopOptions" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-button :loading="refreshing" @click="load">刷新</el-button>
        <el-button type="primary" plain @click="openWall">投屏大屏</el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-label">在制工单</div>
        <div class="kpi-value">{{ kpi.inProgress || 0 }}<small>张</small></div>
        <div class="kpi-foot">状态为生产中的工单</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">已排产</div>
        <div class="kpi-value">{{ kpi.scheduled || 0 }}<small>张</small></div>
        <div class="kpi-foot">等待下达车间</div>
      </div>
      <div class="kpi-card" :class="{ 'kpi-alert': Number(kpi.dueToday || 0) > 0 }">
        <div class="kpi-label">今日交期</div>
        <div class="kpi-value">{{ kpi.dueToday || 0 }}<small>单</small></div>
        <div class="kpi-foot">关联订单今天到期</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">在制数量</div>
        <div class="kpi-value">{{ fmtQty(kpi.wipQty) }}<small>件</small></div>
        <div class="kpi-foot">在制工单计划减完成</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">今日产出</div>
        <div class="kpi-value">{{ fmtQty(kpi.todayOutput) }}<small>件</small></div>
        <div class="kpi-foot">今日报工合格数量</div>
      </div>
      <div
        class="kpi-card kpi-link"
        :class="{ 'kpi-alert': Number(kpi.openAndon || 0) > 0 }"
        @click="router.push('/workshop/andon')"
      >
        <div class="kpi-label">待响应安灯</div>
        <div class="kpi-value">{{ kpi.openAndon || 0 }}<small>单</small></div>
        <div class="kpi-foot">点击进入异常安灯处理</div>
      </div>
      <div
        class="kpi-card kpi-link"
        :class="{ 'kpi-alert': Number(kpi.shortageOrders || 0) > 0 }"
        @click="router.push('/production?shortage=1')"
      >
        <div class="kpi-label">缺料预警</div>
        <div class="kpi-value">{{ kpi.shortageOrders || 0 }}<small>张</small></div>
        <div class="kpi-foot">物料没领齐的待产 / 在制工单</div>
      </div>
    </div>

    <div class="panel ops-panel">
      <div class="card-head">
        <div class="card-title">工序分布</div>
        <span class="toolbar-tip">待加工 / 加工中 / 已完成</span>
      </div>
      <div class="card-body">
        <div v-if="operations.length" ref="chartRef" class="op-chart"></div>
        <div v-else class="empty-block">暂无工序数据</div>
      </div>
    </div>

    <div class="panel board-panel">
      <div class="card-head">
        <div class="card-title">
          在制看板
          <span class="lane-total">{{ filteredLocations.length }} 张</span>
        </div>
        <el-input
          v-model="keyword"
          class="board-search"
          placeholder="按工单号 / 产品定位"
          clearable
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>
      <div v-if="columns.length" class="lane-board">
        <div v-for="col in columns" :key="col.name" class="lane">
          <div class="lane-head">
            <span class="lane-name">{{ col.name }}</span>
            <span class="lane-count">{{ col.items.length }}</span>
          </div>
          <div class="lane-body">
            <div
              v-for="card in col.items"
              :key="card.workNo || card.id"
              class="wo-card"
              :class="[`p-${String(card.priority || 'NORMAL').toLowerCase()}`, { hit: isHit(card) }]"
            >
              <div class="wo-top">
                <span class="wo-no mono">
                  <span
                    v-for="(seg, index) in highlight(card.workNo)"
                    :key="index"
                    :class="{ hl: seg.hit }"
                  >{{ seg.text }}</span>
                </span>
                <PriorityTag :priority="card.priority" />
              </div>
              <div class="wo-product">
                <span
                  v-for="(seg, index) in highlight(card.productName)"
                  :key="index"
                  :class="{ hl: seg.hit }"
                >{{ seg.text }}</span>
              </div>
              <div class="wo-qty">
                <span class="num done">{{ fmtQty(card.doneQty) }}</span>
                <span class="muted">/ {{ fmtQty(card.planQty) }}</span>
                <span class="wo-pct">{{ pct(card) }}%</span>
              </div>
              <div class="mini-progress">
                <div class="bar"><span :style="{ width: pct(card) + '%' }"></span></div>
              </div>
              <div v-if="shortageLines(card) > 0" class="wo-shortage">
                <i></i>缺料 {{ shortageLines(card) }} 项 · 齐套 {{ kitPct(card) }}%
              </div>
              <div class="wo-meta">
                <span>派工：{{ card.assignedWorker || '未派工' }}</span>
                <span>{{ card.workCenterName || '未绑定工作中心' }}</span>
                <span>{{ fmtTime(card.lastReportAt) }}</span>
              </div>
              <div class="wo-foot">
                <span>{{ STATUS_LABEL[card.status] || card.status || '-' }}</span>
                <span>排产 {{ card.scheduleDate || '-' }}</span>
                <span>序 {{ card.currentSeq ?? '-' }}</span>
              </div>
            </div>
            <div v-if="!col.items.length" class="lane-empty">暂无工单</div>
          </div>
        </div>
      </div>
      <div v-else class="empty-block">
        {{ keyword ? '没有匹配的在制工单' : '当前没有在制或在排产工单' }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ops-panel {
  margin-bottom: 16px;
}

.op-chart {
  width: 100%;
  height: 248px;
}

.empty-block {
  padding: 42px 0;
  text-align: center;
  color: var(--muted);
  font-size: 12px;
}

.board-search {
  width: 240px;
}

.lane-total {
  margin-left: 6px;
  padding: 0 8px;
  height: 20px;
  display: inline-grid;
  place-items: center;
  border-radius: 20px;
  background: #eef3ff;
  color: #1d4ed8;
  font-size: 11px;
  font-weight: 600;
  font-family: Bahnschrift, monospace;
}

.card-title {
  display: flex;
  align-items: center;
}

.lane-board {
  display: flex;
  gap: 12px;
  padding: 14px 16px 16px;
  overflow-x: auto;
  align-items: flex-start;
}

.lane {
  flex: 0 0 252px;
  width: 252px;
  background: #f8fafc;
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}

.lane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 12px;
  background: #f2f4f7;
  border-bottom: 1px solid var(--line);
}

.lane-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-2);
}

.lane-count {
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  display: inline-grid;
  place-items: center;
  border-radius: 20px;
  background: #fff;
  border: 1px solid var(--line-strong);
  color: var(--muted);
  font-size: 10px;
  font-family: Bahnschrift, monospace;
}

.lane-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  max-height: calc(100vh - 470px);
  min-height: 90px;
  overflow-y: auto;
}

.wo-card {
  background: #fff;
  border: 1px solid var(--line);
  border-left: 3px solid #d0d5dd;
  border-radius: 7px;
  padding: 10px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.04);
  transition: 0.16s ease;
}

.wo-card.p-urgent {
  border-left-color: #f79009;
}

.wo-card.p-rush {
  border-left-color: #f04438;
}

.wo-card.hit {
  border-color: #f79009;
  background: #fffdf5;
  box-shadow: 0 0 0 2px rgba(247, 144, 9, 0.18);
}

.wo-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.wo-no {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
  word-break: break-all;
}

.wo-product {
  margin-top: 3px;
  font-size: 12px;
  color: var(--ink-2);
  line-height: 1.45;
  word-break: break-all;
}

.wo-qty {
  margin-top: 7px;
  font-size: 11px;
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.wo-qty .done {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.wo-pct {
  margin-left: auto;
  color: var(--muted);
  font-family: Bahnschrift, monospace;
}

.wo-card .mini-progress {
  margin-top: 5px;
  min-width: 0;
}

.wo-shortage {
  margin-top: 6px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 7px;
  border-radius: 20px;
  background: #fef3f2;
  border: 1px solid #fecdca;
  color: #b42318;
  font-size: 10px;
  font-weight: 600;
}

.wo-shortage i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #f04438;
}

.wo-meta {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  font-size: 10px;
  color: var(--muted);
}

.wo-foot {
  margin-top: 5px;
  padding-top: 6px;
  border-top: 1px dashed var(--line);
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 10px;
  color: var(--muted);
  font-family: Bahnschrift, "Microsoft YaHei", monospace;
}

.hl {
  background: #fff3c4;
  color: #b54708;
  border-radius: 2px;
}

.lane-empty {
  padding: 18px 0;
  text-align: center;
  color: #98a2b3;
  font-size: 11px;
}
</style>
