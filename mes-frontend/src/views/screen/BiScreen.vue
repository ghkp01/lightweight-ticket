<script setup lang="ts">
/**
 * 经营 BI 大屏（老板大屏）
 *
 * 与车间大屏 /screen 的区别：
 * - 车间屏是公开的现场看板，只放产量/质量/安灯这类现场数据；
 * - 这里放销售额、库存、待发货等经营口径，所以要求登录，不对外公开。
 *
 * 数据源：
 * - /dashboard/summary（登录态）：销售额、订单、低库存、7 日趋势、工单状态
 * - /public/screen/{production|quality|andon|oee}：现场 KPI、工作中心负荷、不良 TOP、OEE
 *
 * 配置（显隐哪些指标卡/面板、列数、刷新间隔、主题色、标题）保存在 localStorage，
 * 同一台大屏浏览器刷新后仍然生效，不依赖后端改库。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { api, fmtMoney, fmtQty } from '../../api'

const router = useRouter()

/* ============================ 配置 ============================ */

type AccentKey = 'blue' | 'teal' | 'violet' | 'amber'

interface BiConfig {
  title: string
  accent: AccentKey
  columns: number
  refresh: number
  kpis: string[]
  panels: string[]
  targets: { monthSales: number; todayOutput: number; passRate: number }
}

const CONFIG_KEY = 'mes_bi_config_v1'

const DEFAULT_TARGETS = { monthSales: 0, todayOutput: 0, passRate: 97 }

const DEFAULT_CONFIG: BiConfig = {
  title: '经营驾驶舱',
  accent: 'blue',
  columns: 3,
  refresh: 60,
  kpis: [
    'rangeSales',
    'monthSales',
    'producingOrders',
    'pendingShip',
    'passRate',
    'lowStock',
    'pendingDefects',
    'todayOutput'
  ],
  panels: ['trend', 'orderStatus', 'centerLoad', 'defectTop', 'pendingShipments', 'andonCalls'],
  targets: { ...DEFAULT_TARGETS }
}

const ACCENTS: Record<AccentKey, { main: string; area: string; glow: string }> = {
  blue: { main: '#4ea8ff', area: 'rgba(78,168,255,.32)', glow: 'rgba(78,168,255,.18)' },
  teal: { main: '#35e0b0', area: 'rgba(53,224,176,.30)', glow: 'rgba(53,224,176,.16)' },
  violet: { main: '#a78bfa', area: 'rgba(167,139,250,.30)', glow: 'rgba(167,139,250,.16)' },
  amber: { main: '#ffb020', area: 'rgba(255,176,32,.30)', glow: 'rgba(255,176,32,.16)' }
}

const loadConfig = (): BiConfig => {
  try {
    const raw = JSON.parse(localStorage.getItem(CONFIG_KEY) || '{}')
    const savedKpis = Array.isArray(raw.kpis) ? raw.kpis : DEFAULT_CONFIG.kpis
    return {
      ...DEFAULT_CONFIG,
      ...raw,
      // 老配置里没有「区间销售额」时补在最前面，升级后不用手工再勾一次
      kpis: savedKpis.includes('rangeSales') ? savedKpis : ['rangeSales', ...savedKpis],
      panels: Array.isArray(raw.panels) ? raw.panels : DEFAULT_CONFIG.panels,
      targets: { ...DEFAULT_TARGETS, ...(raw.targets || {}) }
    }
  } catch {
    return { ...DEFAULT_CONFIG, targets: { ...DEFAULT_TARGETS } }
  }
}

const config = ref<BiConfig>(loadConfig())
const configOpen = ref(false)
const draft = reactive<BiConfig>({
  ...config.value,
  kpis: [...config.value.kpis],
  panels: [...config.value.panels],
  targets: { ...config.value.targets }
})

/* ---------- 经营区间：今日 / 本周 / 本月 / 上月 / 自定义 ---------- */

type RangeKey = 'today' | 'week' | 'month' | 'lastMonth' | 'custom'

const rangeKey = ref<RangeKey>('today')
const customFrom = ref('')
const customTo = ref('')
const RANGE_OPTIONS: { key: RangeKey; label: string }[] = [
  { key: 'today', label: '今日' },
  { key: 'week', label: '本周' },
  { key: 'month', label: '本月' },
  { key: 'lastMonth', label: '上月' },
  { key: 'custom', label: '自定义' }
]

const ymd = (date: Date) => {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

const rangeParams = computed(() => {
  const now = new Date()
  if (rangeKey.value === 'week') {
    const day = now.getDay() || 7
    const monday = new Date(now)
    monday.setDate(now.getDate() - day + 1)
    return { from: ymd(monday), to: ymd(now) }
  }
  if (rangeKey.value === 'month') {
    return { from: ymd(new Date(now.getFullYear(), now.getMonth(), 1)), to: ymd(now) }
  }
  if (rangeKey.value === 'lastMonth') {
    return {
      from: ymd(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
      to: ymd(new Date(now.getFullYear(), now.getMonth(), 0))
    }
  }
  if (rangeKey.value === 'custom') {
    return {
      from: customFrom.value || ymd(now),
      to: customTo.value || customFrom.value || ymd(now)
    }
  }
  return { from: ymd(now), to: ymd(now) }
})

const rangeLabel = computed(() => {
  const labels: Record<RangeKey, string> = {
    today: '今日',
    week: '本周',
    month: '本月',
    lastMonth: '上月',
    custom: '自定义'
  }
  return labels[rangeKey.value]
})

const changeRange = (key: RangeKey) => {
  rangeKey.value = key
  countdown.value = Number(config.value.refresh) || 60
  // 不重连 SSE：保留当前连接，其他区间由轮询刷新，避免产生被中止的网络请求
  load()
}
const accent = computed(() => ACCENTS[config.value.accent] || ACCENTS.blue)
const accentStyle = computed(() => ({
  '--accent': accent.value.main,
  '--accent-glow': accent.value.glow
}))

const openConfig = () => {
  Object.assign(draft, {
    ...config.value,
    kpis: [...config.value.kpis],
    panels: [...config.value.panels],
    targets: { ...config.value.targets }
  })
  loadPushStatus()
  configOpen.value = true
}

const applyConfig = () => {
  config.value = {
    ...draft,
    refresh: Number(draft.refresh) || 60,
    columns: Number(draft.columns) || 3,
    kpis: [...draft.kpis],
    panels: [...draft.panels],
    targets: { ...draft.targets }
  }
  localStorage.setItem(CONFIG_KEY, JSON.stringify(config.value))
  configOpen.value = false
  startTimer()
  nextTick(() => {
    disposeAllCharts()
    renderCharts()
  })
}

const resetConfig = () => {
  Object.assign(draft, {
    ...DEFAULT_CONFIG,
    kpis: [...DEFAULT_CONFIG.kpis],
    panels: [...DEFAULT_CONFIG.panels],
    targets: { ...DEFAULT_TARGETS }
  })
}

/* ============================ 数据 ============================ */

const dash = ref<any>({})
const prod = ref<any>({})
const quality = ref<any>({})
const andon = ref<any>({})
const oee = ref<any>({})
const loading = ref(false)
const online = ref(true)
const updatedAt = ref('')
const clock = ref('')
const countdown = ref(config.value.refresh)

const hasPanel = (key: string) => config.value.panels.includes(key)
const panelWide = (key: string) =>
  !!PANEL_DEFS[key]?.wide && Number(config.value.columns) > 1

const load = async () => {
  if (loading.value) return
  loading.value = true
  try {
    const results = await Promise.allSettled([
      // SSE 正在推送当前区间时，轮询只补现场屏数据；否则轮询负责经营数据
      streamCurrent() ? Promise.resolve(null) : api.get('/dashboard/summary', { ...rangeParams.value }),
      api.get('/public/screen/production'),
      api.get('/public/screen/quality'),
      api.get('/public/screen/andon'),
      api.get('/public/screen/oee')
    ])
    if (results[0].status === 'fulfilled' && results[0].value) dash.value = results[0].value
    if (results[1].status === 'fulfilled' && results[1].value) prod.value = results[1].value
    if (results[2].status === 'fulfilled' && results[2].value) quality.value = results[2].value
    if (results[3].status === 'fulfilled' && results[3].value) andon.value = results[3].value
    if (results[4].status === 'fulfilled' && results[4].value) oee.value = results[4].value
    online.value = results.slice(1).some((item) => item.status === 'fulfilled')
    updatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    countdown.value = Number(config.value.refresh) || 60
    await nextTick()
    renderCharts()
  } finally {
    loading.value = false
  }
}

/* ============================ 指标卡 ============================ */

interface KpiDef {
  label: string
  value: string
  unit?: string
  foot?: string
  tone?: string
  progress?: number | null
  targetText?: string
}

const changeText = (value: any) => {
  if (value === null || value === undefined || value === '') return '—'
  const num = Number(value)
  return `${num >= 0 ? '+' : ''}${num.toFixed(1)}%`
}

const progressOf = (actual: number, target: number) => {
  if (!target || target <= 0) return null
  return Math.max(0, Math.min(100, (actual / target) * 100))
}

const kpiDefs = computed<Record<string, KpiDef>>(() => {
  const d = dash.value || {}
  const p = prod.value?.kpi || {}
  const o = oee.value?.kpi || {}
  const a = andon.value?.kpi || {}
  const rate = d.rangePassRate ?? d.passRate
  const monthTarget = Number(config.value.targets?.monthSales || 0)
  const outputTarget = Number(config.value.targets?.todayOutput || 0)
  const passTarget = Number(config.value.targets?.passRate || 0)
  const salesTarget = rangeKey.value === 'month' ? monthTarget : 0
  return {
    rangeSales: {
      label: `销售额 · ${rangeLabel.value}`,
      value: `¥${fmtMoney(d.rangeSales)}`,
      foot: `环比 ${changeText(d.salesMom)} · 同比 ${changeText(d.salesYoy)}`,
      progress: progressOf(Number(d.rangeSales || 0), salesTarget),
      targetText: salesTarget > 0 ? `目标 ¥${fmtMoney(salesTarget)}` : ''
    },
    todaySales: { label: '今日销售额', value: `¥${fmtMoney(d.todaySales)}` },
    monthSales: {
      label: '本月销售额',
      value: `¥${fmtMoney(d.monthSales)}`,
      progress: progressOf(Number(d.monthSales || 0), monthTarget),
      targetText: monthTarget > 0 ? `目标 ¥${fmtMoney(monthTarget)}` : ''
    },
    activeOrders: { label: '执行中订单', value: fmtQty(d.activeOrders), unit: '单' },
    producingOrders: { label: '在制工单', value: fmtQty(d.producingOrders), unit: '张' },
    pendingShip: { label: '待发货', value: fmtQty(d.pendingShip), unit: '单', tone: Number(d.pendingShip || 0) > 0 ? 'warn' : '' },
    passRate: {
      label: '一次合格率',
      value: rate === null || rate === undefined ? '—' : `${Number(rate).toFixed(1)}%`,
      tone: rate !== null && rate !== undefined && Number(rate) < (passTarget || 97) ? 'warn' : 'ok',
      progress: progressOf(Number(rate || 0), passTarget),
      targetText: passTarget > 0 ? `目标 ${passTarget}%` : ''
    },
    lowStock: { label: '低库存 SKU', value: fmtQty(d.lowStock), unit: '项', tone: Number(d.lowStock || 0) > 0 ? 'warn' : '' },
    pendingDefects: { label: '待处置不良', value: fmtQty(d.pendingDefects), unit: '笔', tone: Number(d.pendingDefects || 0) > 0 ? 'warn' : '' },
    pendingReturns: { label: '待处理退货', value: fmtQty(d.pendingReturns), unit: '单', tone: Number(d.pendingReturns || 0) > 0 ? 'warn' : '' },
    todayOutput: {
      label: '今日产出',
      value: fmtQty(p.todayOutput),
      unit: '件',
      progress: progressOf(Number(p.todayOutput || 0), outputTarget),
      targetText: outputTarget > 0 ? `目标 ${fmtQty(outputTarget)} 件` : ''
    },
    attainment: {
      label: '计划达成率',
      value: p.attainment === null || p.attainment === undefined ? '—' : `${(Number(p.attainment) * 100).toFixed(1)}%`,
      foot: `计划 ${fmtQty(p.todayPlanQty)} 件`,
      tone: Number(p.attainment || 0) < 0.8 ? 'warn' : 'ok'
    },
    shortageOrders: { label: '缺料工单', value: fmtQty(p.shortageOrders), unit: '张', tone: Number(p.shortageOrders || 0) > 0 ? 'alert' : '' },
    andonOpen: { label: '安灯待接单', value: fmtQty(a.open), unit: '条', tone: Number(a.open || 0) > 0 ? 'alert' : '' },
    oee: { label: '今日 OEE', value: o.oee === null || o.oee === undefined ? '—' : `${Number(o.oee).toFixed(1)}%` }
  }
})

const PANEL_DEFS: Record<string, { label: string; wide?: boolean }> = {
  trend: { label: '近 7 日销售趋势', wide: true },
  orderStatus: { label: '工单状态分布' },
  centerLoad: { label: '工作中心负荷' },
  defectTop: { label: '今日不良 TOP5' },
  oeeCenters: { label: '设备 OEE' },
  pendingShipments: { label: '待发货清单', wide: true },
  recentOrders: { label: '近期销售订单' },
  andonCalls: { label: '异常安灯（未关闭优先）', wide: true },
  outsourcing: { label: '外协在途', wide: true },
  lowStockList: { label: '低库存预警', wide: true }
}

/* ============================ 图表 ============================ */

const trendRef = ref<HTMLElement>()
const pieRef = ref<HTMLElement>()
const loadRef = ref<HTMLElement>()
const defectRef = ref<HTMLElement>()
const oeeRef = ref<HTMLElement>()

const charts: Record<string, echarts.ECharts> = {}

const disposeChart = (key: string) => {
  const chart = charts[key]
  if (chart && !chart.isDisposed()) chart.dispose()
  delete charts[key]
}
const disposeAllCharts = () => Object.keys(charts).forEach(disposeChart)

const ensure = (key: string, el?: HTMLElement) => {
  if (!el) {
    disposeChart(key)
    return null
  }
  let chart = charts[key]
  if (!chart || chart.isDisposed()) {
    chart = echarts.init(el)
    charts[key] = chart
  }
  return chart
}

const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  SCHEDULED: '已排产',
  RELEASED: '已下达',
  IN_PROGRESS: '生产中',
  FINISHED: '已完工',
  CLOSED: '已关闭',
  CANCELLED: '已作废'
}
const STATUS_COLOR: Record<string, string> = {
  DRAFT: '#8fa3c4',
  SCHEDULED: '#4ea8ff',
  RELEASED: '#5b8def',
  IN_PROGRESS: '#ffb020',
  FINISHED: '#35e0b0',
  CLOSED: '#5f728c',
  CANCELLED: '#ff6b6b'
}

const axisStyle = {
  axisLine: { lineStyle: { color: 'rgba(126,170,255,.22)' } },
  axisTick: { show: false },
  axisLabel: { color: '#8ea3c4', fontSize: 11 }
}
const splitStyle = { splitLine: { lineStyle: { color: 'rgba(126,170,255,.12)' } } }

const renderTrend = () => {
  const chart = ensure('trend', trendRef.value)
  if (!chart) return
  const rows = dash.value.rangeTrend || dash.value.trend || []
  chart.setOption({
    grid: { left: 10, right: 18, top: 28, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value: any) => `¥ ${fmtMoney(value)}`,
      backgroundColor: 'rgba(9,16,32,.92)',
      borderColor: 'rgba(126,170,255,.25)',
      textStyle: { color: '#dbe7f8' }
    },
    xAxis: { type: 'category', boundaryGap: false, data: rows.map((row: any) => row.date), ...axisStyle },
    yAxis: {
      type: 'value',
      ...splitStyle,
      axisLabel: {
        ...axisStyle.axisLabel,
        formatter: (value: number) => (value >= 10000 ? `${value / 10000}w` : value)
      }
    },
    series: [
      {
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 7,
        data: rows.map((row: any) => Number(row.amount)),
        lineStyle: { width: 2.6, color: accent.value.main },
        itemStyle: { color: accent.value.main, borderColor: '#0a1224', borderWidth: 2 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: accent.value.area },
            { offset: 1, color: 'rgba(0,0,0,0)' }
          ])
        }
      }
    ]
  }, true)
}

const renderPie = () => {
  const chart = ensure('orderStatus', pieRef.value)
  if (!chart) return
  const rows = dash.value.workOrderStatus || []
  chart.setOption({
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(9,16,32,.92)',
      borderColor: 'rgba(126,170,255,.25)',
      textStyle: { color: '#dbe7f8' }
    },
    legend: {
      bottom: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: '#8ea3c4', fontSize: 11 }
    },
    series: [
      {
        type: 'pie',
        radius: ['52%', '74%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#0a1224', borderWidth: 3, borderRadius: 4 },
        label: { show: false },
        data: rows.map((row: any) => ({
          name: STATUS_TEXT[row.status] || row.status,
          value: Number(row.value),
          itemStyle: { color: STATUS_COLOR[row.status] || '#8fa3c4' }
        }))
      }
    ]
  }, true)
}

const renderCenterLoad = () => {
  const chart = ensure('centerLoad', loadRef.value)
  if (!chart) return
  const rows = prod.value.workCenters || []
  chart.setOption({
    grid: { left: 8, right: 42, top: 10, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      valueFormatter: (value: any) => `${Number(value).toFixed(0)}%`,
      backgroundColor: 'rgba(9,16,32,.92)',
      borderColor: 'rgba(126,170,255,.25)',
      textStyle: { color: '#dbe7f8' }
    },
    xAxis: { type: 'value', max: 120, ...splitStyle, axisLabel: { ...axisStyle.axisLabel, formatter: '{value}%' } },
    yAxis: { type: 'category', data: rows.map((row: any) => row.name), ...axisStyle },
    series: [
      {
        type: 'bar',
        barWidth: 12,
        data: rows.map((row: any) => ({
          value: Number(row.loadRate || 0) * 100,
          itemStyle: {
            color: Number(row.loadRate || 0) > 1 ? '#ff6b6b' : accent.value.main,
            borderRadius: [0, 6, 6, 0]
          }
        })),
        label: {
          show: true,
          position: 'right',
          formatter: (params: any) => `${Number(params.value).toFixed(0)}%`,
          color: '#8ea3c4',
          fontSize: 11
        }
      }
    ]
  }, true)
}

const renderDefect = () => {
  const chart = ensure('defectTop', defectRef.value)
  if (!chart) return
  const rows = quality.value.defectTop || []
  chart.setOption({
    grid: { left: 8, right: 30, top: 10, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      valueFormatter: (value: any) => `${fmtQty(value)} 件`,
      backgroundColor: 'rgba(9,16,32,.92)',
      borderColor: 'rgba(126,170,255,.25)',
      textStyle: { color: '#dbe7f8' }
    },
    xAxis: { type: 'value', ...splitStyle, axisLabel: axisStyle.axisLabel },
    yAxis: { type: 'category', data: rows.map((row: any) => row.reason), ...axisStyle },
    series: [
      {
        type: 'bar',
        barWidth: 12,
        data: rows.map((row: any) => Number(row.qty)),
        itemStyle: { color: '#ff8a5c', borderRadius: [0, 6, 6, 0] },
        label: { show: true, position: 'right', color: '#8ea3c4', fontSize: 11 }
      }
    ]
  }, true)
}

const renderOee = () => {
  const chart = ensure('oeeCenters', oeeRef.value)
  if (!chart) return
  const rows = oee.value.centers || []
  chart.setOption({
    grid: { left: 8, right: 12, top: 24, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      valueFormatter: (value: any) => `${Number(value).toFixed(1)}%`,
      backgroundColor: 'rgba(9,16,32,.92)',
      borderColor: 'rgba(126,170,255,.25)',
      textStyle: { color: '#dbe7f8' }
    },
    xAxis: { type: 'category', data: rows.map((row: any) => row.name), ...axisStyle },
    yAxis: { type: 'value', max: 100, ...splitStyle, axisLabel: { ...axisStyle.axisLabel, formatter: '{value}%' } },
    series: [
      {
        type: 'bar',
        barWidth: 22,
        data: rows.map((row: any) => ({
          value: Number(row.oee || 0),
          itemStyle: {
            color: Number(row.oee || 0) < 60 ? '#ff6b6b' : accent.value.main,
            borderRadius: [6, 6, 0, 0]
          }
        })),
        label: {
          show: true,
          position: 'top',
          formatter: (params: any) => `${Number(params.value).toFixed(0)}%`,
          color: '#8ea3c4',
          fontSize: 11
        }
      }
    ]
  }, true)
}

const renderCharts = () => {
  renderTrend()
  renderPie()
  renderCenterLoad()
  renderDefect()
  renderOee()
}

const resizeCharts = () => {
  Object.values(charts).forEach((chart) => {
    if (chart && !chart.isDisposed()) chart.resize()
  })
}

/* ============================ 下钻 / 导出 / 日报推送 ============================ */

const customRange = ref<[string, string] | null>(null)

const onCustomRange = (value: [string, string] | null) => {
  customRange.value = value
  customFrom.value = value?.[0] || ''
  customTo.value = value?.[1] || ''
  changeRange('custom')
}

interface DrillColumn {
  prop: string
  label: string
  money?: boolean
}

const drillOpen = ref(false)
const drillTitle = ref('')
const drillLoading = ref(false)
const drillRows = ref<any[]>([])
const drillColumns = ref<DrillColumn[]>([])

const DRILLABLE = new Set([
  'rangeSales', 'todaySales', 'monthSales', 'activeOrders', 'producingOrders',
  'pendingShip', 'lowStock', 'pendingDefects', 'todayOutput', 'attainment',
  'shortageOrders', 'andonOpen', 'oee'
])
const drillable = (key: string) => DRILLABLE.has(key)

const DRILL_TITLES: Record<string, string> = {
  rangeSales: '销售额明细', todaySales: '今日销售额明细', monthSales: '本月销售额明细',
  activeOrders: '执行中订单', producingOrders: '在制工单', pendingShip: '待发货清单',
  lowStock: '低库存明细', pendingDefects: '不良 / 检验明细', todayOutput: '今日产出',
  attainment: '今日排产工单', shortageOrders: '在制工单', andonOpen: '异常安灯', oee: '工作中心 OEE'
}

const openDrill = async (key: string) => {
  if (!drillable(key)) return
  drillTitle.value = DRILL_TITLES[key] || '明细'
  drillRows.value = []
  drillColumns.value = []
  drillOpen.value = true
  drillLoading.value = true
  try {
    if (['rangeSales', 'todaySales', 'monthSales', 'activeOrders'].includes(key)) {
      const data = await api.get('/sales-orders', { page: 1, size: 50 })
      drillRows.value = data?.records || []
      drillColumns.value = [
        { prop: 'orderNo', label: '订单号' },
        { prop: 'customerName', label: '客户' },
        { prop: 'totalAmount', label: '金额', money: true },
        { prop: 'status', label: '状态' },
        { prop: 'createdAt', label: '创建时间' }
      ]
    } else if (['producingOrders', 'todayOutput', 'attainment', 'shortageOrders'].includes(key)) {
      drillRows.value = (prod.value.orders || []).slice()
      drillColumns.value = [
        { prop: 'workNo', label: '工单号' },
        { prop: 'productName', label: '产品' },
        { prop: 'planQty', label: '计划量' },
        { prop: 'currentOperation', label: '当前工序' },
        { prop: 'scheduleDate', label: '排产日' },
        { prop: 'status', label: '状态' }
      ]
    } else if (key === 'pendingShip') {
      drillRows.value = (dash.value.pendingShipments || []).slice()
      drillColumns.value = [
        { prop: 'shipmentNo', label: '发货单' },
        { prop: 'customerName', label: '客户' },
        { prop: 'carrier', label: '承运' },
        { prop: 'status', label: '状态' },
        { prop: 'createdAt', label: '创建' }
      ]
    } else if (key === 'lowStock') {
      drillRows.value = (dash.value.lowStockList || []).slice()
      drillColumns.value = [
        { prop: 'productCode', label: '产品编码' },
        { prop: 'productName', label: '产品' },
        { prop: 'warehouseName', label: '仓库' },
        { prop: 'qty', label: '结存' },
        { prop: 'safetyStock', label: '安全库存' }
      ]
    } else if (key === 'pendingDefects') {
      drillRows.value = (quality.value.inspections || []).slice()
      drillColumns.value = [
        { prop: 'inspectionNo', label: '检验单' },
        { prop: 'productName', label: '产品' },
        { prop: 'inspectQty', label: '检验数' },
        { prop: 'defectQty', label: '不良数' },
        { prop: 'result', label: '结果' },
        { prop: 'inspectedAt', label: '时间' }
      ]
    } else if (key === 'andonOpen') {
      drillRows.value = (andon.value.calls || []).slice()
      drillColumns.value = [
        { prop: 'callNo', label: '呼叫单' },
        { prop: 'type', label: '类型' },
        { prop: 'workOrderNo', label: '工单' },
        { prop: 'description', label: '描述' },
        { prop: 'elapsedMinutes', label: '持续(分)' },
        { prop: 'status', label: '状态' }
      ]
    } else if (key === 'oee') {
      drillRows.value = (oee.value.centers || []).slice()
      drillColumns.value = [
        { prop: 'name', label: '工作中心' },
        { prop: 'oee', label: 'OEE%' },
        { prop: 'availability', label: '可用率%' },
        { prop: 'performance', label: '性能%' },
        { prop: 'quality', label: '质量%' },
        { prop: 'downtimeMinutes', label: '停机(分)' }
      ]
    }
  } finally {
    drillLoading.value = false
  }
}

const downloadBlob = (content: string, filename: string) => {
  const blob = new Blob(['\ufeff' + content], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

const csvLine = (cells: any[]) =>
  cells.map((cell) => {
    const text = cell === null || cell === undefined ? '' : String(cell)
    return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }).join(',')

const exportCsv = (kind: string) => {
  const stamp = ymd(new Date())
  if (kind === 'kpi') {
    const lines = [csvLine(['指标', '数值', '说明'])]
    config.value.kpis.forEach((key) => {
      const def = kpiDefs.value[key]
      if (def) lines.push(csvLine([def.label, def.value + (def.unit || ''), def.foot || '']))
    })
    downloadBlob(lines.join('\r\n'), `经营指标_${stamp}.csv`)
    return
  }
  if (kind === 'trend') {
    const rows = dash.value.rangeTrend || dash.value.trend || []
    const lines = [csvLine(['日期', '销售额'])]
    rows.forEach((row: any) => lines.push(csvLine([row.date, row.amount])))
    downloadBlob(lines.join('\r\n'), `销售趋势_${stamp}.csv`)
    return
  }
  if (kind === 'shipments') {
    const lines = [csvLine(['发货单', '客户', '承运', '状态', '创建'])]
    ;(dash.value.pendingShipments || []).forEach((row: any) =>
      lines.push(csvLine([row.shipmentNo, row.customerName, row.carrier, row.status, row.createdAt])))
    downloadBlob(lines.join('\r\n'), `待发货_${stamp}.csv`)
    return
  }
  if (kind === 'lowStock') {
    const lines = [csvLine(['产品编码', '产品', '仓库', '结存', '安全库存'])]
    ;(dash.value.lowStockList || []).forEach((row: any) =>
      lines.push(csvLine([row.productCode, row.productName, row.warehouseName, row.qty, row.safetyStock])))
    downloadBlob(lines.join('\r\n'), `低库存_${stamp}.csv`)
  }
}

const saveChartPng = (key: string, name: string) => {
  const chart = charts[key]
  if (!chart || chart.isDisposed()) return
  const url = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#0a1224' })
  const link = document.createElement('a')
  link.href = url
  link.download = `${name}_${ymd(new Date())}.png`
  link.click()
}

/* ---------- 日报推送设置 ---------- */

const pushStatus = ref<any>({})
const pushForm = reactive({ type: 'FEISHU', url: '', enabled: false })
const pushSaving = ref(false)
const pushSending = ref(false)

const loadPushStatus = async () => {
  try {
    const data = await api.get('/report-push/status')
    pushStatus.value = data || {}
    pushForm.type = data?.type || 'FEISHU'
    pushForm.url = ''
    pushForm.enabled = !!data?.enabled
  } catch {
    pushStatus.value = {}
  }
}

const savePush = async () => {
  pushSaving.value = true
  try {
    pushStatus.value = await api.put('/report-push/config', { ...pushForm })
    pushForm.url = ''
    ElMessage.success('推送设置已保存')
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '保存推送设置失败')
  } finally {
    pushSaving.value = false
  }
}

const sendReport = async () => {
  pushSending.value = true
  try {
    const result = await api.post('/report-push/send')
    ElMessage.success(result?.message || '经营日报已推送')
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '推送失败，请检查 Webhook 配置')
  } finally {
    pushSending.value = false
  }
}

/* ============================ 时钟 / 刷新 ============================ */

/* ---------- 实时推送：带 Authorization 的 fetch 读取 SSE，避免令牌出现在 URL ---------- */

const streaming = ref(false)
let streamAbort: AbortController | null = null
let streamReader: ReadableStreamDefaultReader<Uint8Array> | null = null
/** 连接建立时的区间：切到其他区间时保留连接但忽略其推送，改走轮询，避免反复中止请求 */
let streamRangeKey = ''
let streamGeneration = 0

const streamCurrent = () => streaming.value && streamRangeKey === rangeKey.value

const stopStream = () => {
  streaming.value = false
  const reader = streamReader
  streamReader = null
  if (reader) {
    // 优雅关闭：主动 cancel 读流，不用 abort，避免浏览器控制台留下 ERR_ABORTED
    reader.cancel().catch(() => {})
  } else if (streamAbort) {
    streamAbort.abort()
  }
  streamAbort = null
}

const startStream = async () => {
  stopStream()
  const token = localStorage.getItem('mes_token')
  if (!token) return
  const controller = new AbortController()
  streamAbort = controller
  streaming.value = true
  streamRangeKey = rangeKey.value
  const generation = ++streamGeneration
  try {
    const query = new URLSearchParams(rangeParams.value).toString()
    const response = await fetch(`/api/dashboard/stream?${query}`, {
      headers: { Authorization: `Bearer ${token}` },
      signal: controller.signal
    })
    if (!response.ok || !response.body) {
      streaming.value = false
      return
    }
    const reader = response.body.getReader()
    streamReader = reader
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      if (streamGeneration !== generation) break
      buffer += decoder.decode(value, { stream: true })
      const chunks = buffer.split('\n\n')
      buffer = chunks.pop() || ''
      for (const chunk of chunks) {
        const dataLine = chunk.split('\n').find((line) => line.startsWith('data:'))
        if (!dataLine) continue
        // 已经切到其他区间：这条推送属于旧区间，忽略，由轮询负责新区间
        if (streamRangeKey !== rangeKey.value) continue
        try {
          dash.value = JSON.parse(dataLine.slice(5).trim())
          updatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
          await nextTick()
          renderCharts()
        } catch {
          // 半包数据跳过，等下一个事件边界
        }
      }
    }
  } catch (error: any) {
    if (error?.name !== 'AbortError') streaming.value = false
  } finally {
    if (streamAbort === controller) streaming.value = false
  }
}

let pollTimer: number | undefined
let clockTimer: number | undefined

const tickClock = () => {
  clock.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

const startTimer = () => {
  if (pollTimer) window.clearInterval(pollTimer)
  countdown.value = Number(config.value.refresh) || 60
  pollTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      countdown.value = Number(config.value.refresh) || 60
      load()
    }
  }, 1000)
}

const fullscreen = ref(false)
const toggleFullscreen = async () => {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
      fullscreen.value = false
    } else {
      await document.documentElement.requestFullscreen()
      fullscreen.value = true
    }
  } catch {
    fullscreen.value = !!document.fullscreenElement
  }
}

const goBack = () => router.push('/dashboard')

onMounted(() => {
  tickClock()
  clockTimer = window.setInterval(tickClock, 1000)
  load()
  startTimer()
  startStream()
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  if (clockTimer) window.clearInterval(clockTimer)
  stopStream()
  window.removeEventListener('resize', resizeCharts)
  disposeAllCharts()
})
</script>

<template>
  <div class="bi" :style="accentStyle">
    <header class="bi-head">
      <div class="brand">
        <span class="brand-dot" />
        <div class="brand-text">
          <b>{{ config.title }}</b>
          <span>智造工单 MES · 经营驾驶舱</span>
        </div>
      </div>

      <div class="range-bar">
        <button
          v-for="item in RANGE_OPTIONS"
          :key="item.key"
          class="range-tab"
          :class="{ active: rangeKey === item.key }"
          @click="changeRange(item.key)"
        >
          {{ item.label }}
        </button>
        <el-date-picker
          v-if="rangeKey === 'custom'"
          v-model="customRange"
          type="daterange"
          size="small"
          value-format="YYYY-MM-DD"
          start-placeholder="开始"
          end-placeholder="结束"
          range-separator="至"
          style="width: 236px"
          @change="onCustomRange"
        />
      </div>

      <div class="head-meta">
        <span class="stamp">{{ updatedAt || '等待数据…' }}</span>
        <span class="clock">{{ clock }}</span>
        <span class="countdown" :class="{ refreshing: loading }">
          {{ loading ? '刷新中…' : streamCurrent() ? '实时推送中' : `${countdown}s 后刷新` }}
        </span>
        <el-dropdown trigger="click" @command="exportCsv">
          <button class="ghost">导出</button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="kpi">经营指标 CSV</el-dropdown-item>
              <el-dropdown-item command="trend">销售趋势 CSV</el-dropdown-item>
              <el-dropdown-item command="shipments">待发货 CSV</el-dropdown-item>
              <el-dropdown-item command="lowStock">低库存 CSV</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <button class="ghost" @click="openConfig">配置</button>
        <button class="ghost" @click="toggleFullscreen">{{ fullscreen ? '退出全屏' : '全屏' }}</button>
        <button class="ghost" @click="goBack">返回工作台</button>
      </div>
    </header>

    <main class="bi-body">
      <section class="kpi-row">
        <article
          v-for="key in config.kpis"
          :key="key"
          class="kpi"
          :class="[kpiDefs[key]?.tone, { clickable: drillable(key) }]"
          @click="openDrill(key)"
        >
          <span class="kpi-label">{{ kpiDefs[key]?.label || key }}</span>
          <span class="kpi-value">
            {{ kpiDefs[key]?.value || '—' }}
            <small v-if="kpiDefs[key]?.unit">{{ kpiDefs[key]?.unit }}</small>
          </span>
          <span v-if="kpiDefs[key]?.foot" class="kpi-foot">{{ kpiDefs[key]?.foot }}</span>
          <div v-if="kpiDefs[key]?.targetText" class="kpi-target">
            <div class="kpi-target-line">
              <span>{{ kpiDefs[key]?.targetText }}</span>
              <span>{{ Math.round(kpiDefs[key]?.progress || 0) }}%</span>
            </div>
            <div class="kpi-track"><i :style="{ width: `${kpiDefs[key]?.progress || 0}%` }" /></div>
          </div>
        </article>
        <article v-if="!config.kpis.length" class="kpi empty-kpi">
          还没有选择指标卡，点右上角「配置」勾选
        </article>
      </section>

      <section class="panels" :style="{ '--cols': config.columns }">
        <article v-if="hasPanel('trend')" class="panel" :class="{ wide: panelWide('trend') }">
          <header class="panel-head">
            <h3>近 7 日销售趋势</h3>
            <span class="panel-sub">单位：元 <button class="mini" @click="saveChartPng('trend', '销售趋势')">PNG</button></span>
          </header>
          <div ref="trendRef" class="chart" />
        </article>

        <article v-if="hasPanel('orderStatus')" class="panel" :class="{ wide: panelWide('orderStatus') }">
          <header class="panel-head">
            <h3>工单状态分布</h3>
            <span class="panel-sub">全部工单 <button class="mini" @click="saveChartPng('orderStatus', '工单状态')">PNG</button></span>
          </header>
          <div ref="pieRef" class="chart" />
        </article>

        <article v-if="hasPanel('centerLoad')" class="panel" :class="{ wide: panelWide('centerLoad') }">
          <header class="panel-head">
            <h3>工作中心今日负荷</h3>
            <span class="panel-sub">超 100% 标红 <button class="mini" @click="saveChartPng('centerLoad', '工作中心负荷')">PNG</button></span>
          </header>
          <div ref="loadRef" class="chart" />
        </article>

        <article v-if="hasPanel('defectTop')" class="panel" :class="{ wide: panelWide('defectTop') }">
          <header class="panel-head">
            <h3>今日不良 TOP5</h3>
            <span class="panel-sub">按数量 <button class="mini" @click="saveChartPng('defectTop', '不良TOP5')">PNG</button></span>
          </header>
          <div ref="defectRef" class="chart" />
        </article>

        <article v-if="hasPanel('oeeCenters')" class="panel" :class="{ wide: panelWide('oeeCenters') }">
          <header class="panel-head">
            <h3>设备 OEE</h3>
            <span class="panel-sub">低于 60% 标红 <button class="mini" @click="saveChartPng('oeeCenters', '设备OEE')">PNG</button></span>
          </header>
          <div ref="oeeRef" class="chart" />
        </article>

        <article v-if="hasPanel('pendingShipments')" class="panel" :class="{ wide: panelWide('pendingShipments') }">
          <header class="panel-head">
            <h3>待发货清单</h3>
            <span class="panel-sub">草稿 / 已发货</span>
          </header>
          <div class="table-wrap">
            <table class="grid">
              <thead>
                <tr>
                  <th>发货单</th>
                  <th>客户</th>
                  <th>承运</th>
                  <th>状态</th>
                  <th>创建</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in dash.pendingShipments || []" :key="row.shipmentNo">
                  <td class="mono">{{ row.shipmentNo }}</td>
                  <td class="ellipsis">{{ row.customerName || '—' }}</td>
                  <td>{{ row.carrier || '—' }}</td>
                  <td>
                    <span class="pill" :class="row.status === 'SHIPPED' ? 'ok' : 'wait'">
                      {{ row.status === 'SHIPPED' ? '已发货' : '草稿' }}
                    </span>
                  </td>
                  <td class="mono">{{ row.createdAt || '—' }}</td>
                </tr>
                <tr v-if="!(dash.pendingShipments || []).length">
                  <td colspan="5" class="empty">暂无待发货单据</td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>

        <article v-if="hasPanel('recentOrders')" class="panel" :class="{ wide: panelWide('recentOrders') }">
          <header class="panel-head">
            <h3>近期销售订单</h3>
            <span class="panel-sub">最近 5 单</span>
          </header>
          <div class="table-wrap">
            <table class="grid">
              <thead>
                <tr>
                  <th>订单号</th>
                  <th>客户</th>
                  <th class="right">金额</th>
                  <th>状态</th>
                  <th>创建</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in dash.recentOrders || []" :key="row.orderNo">
                  <td class="mono">{{ row.orderNo }}</td>
                  <td class="ellipsis">{{ row.customerName || '—' }}</td>
                  <td class="right mono">¥{{ fmtMoney(row.totalAmount) }}</td>
                  <td><span class="pill wait">{{ row.status }}</span></td>
                  <td class="mono">{{ row.createdAt || '—' }}</td>
                </tr>
                <tr v-if="!(dash.recentOrders || []).length">
                  <td colspan="5" class="empty">暂无销售订单</td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>

        <article v-if="hasPanel('andonCalls')" class="panel" :class="{ wide: panelWide('andonCalls') }">
          <header class="panel-head">
            <h3>异常安灯</h3>
            <span class="panel-sub">待接单 {{ fmtQty(andon.kpi?.open) }} · 处理中 {{ fmtQty(andon.kpi?.acked) }}</span>
          </header>
          <div class="table-wrap">
            <table class="grid">
              <thead>
                <tr>
                  <th>呼叫单</th>
                  <th>类型</th>
                  <th>工单</th>
                  <th>描述</th>
                  <th class="right">持续</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in (andon.calls || []).slice(0, 10)"
                  :key="row.callNo"
                  :class="{ danger: row.status === 'OPEN' }"
                >
                  <td class="mono">{{ row.callNo }}</td>
                  <td>{{ { MATERIAL: '缺料', EQUIPMENT: '设备', QUALITY: '质量', OTHER: '其他' }[row.type] || row.type }}</td>
                  <td class="mono">{{ row.workOrderNo || '—' }}</td>
                  <td class="ellipsis">{{ row.description || '—' }}</td>
                  <td class="right mono">{{ fmtQty(row.elapsedMinutes) }} 分</td>
                  <td>
                    <span class="pill" :class="row.status === 'OPEN' ? 'bad' : row.status === 'ACKED' ? 'warn' : 'ok'">
                      {{ { OPEN: '待接单', ACKED: '处理中', RESOLVED: '已解决' }[row.status] || row.status }}
                    </span>
                  </td>
                </tr>
                <tr v-if="!(andon.calls || []).length">
                  <td colspan="6" class="empty">当前没有异常呼叫</td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>

        <article v-if="hasPanel('outsourcing')" class="panel" :class="{ wide: panelWide('outsourcing') }">
          <header class="panel-head">
            <h3>外协在途</h3>
            <span class="panel-sub">共 {{ fmtQty((prod.outsourcingOrders || []).length) }} 单</span>
          </header>
          <div class="table-wrap">
            <table class="grid">
              <thead>
                <tr>
                  <th>外协单 / 工单</th>
                  <th>产品</th>
                  <th class="right">工序</th>
                  <th>外协厂</th>
                  <th>约定回厂</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in (prod.outsourcingOrders || []).slice(0, 10)"
                  :key="row.orderNo || row.workNo"
                  :class="{ danger: row.overdue }"
                >
                  <td class="mono">
                    {{ row.orderNo || '—' }}
                    <span class="muted-code">{{ row.workNo || '' }}</span>
                  </td>
                  <td class="ellipsis">{{ row.productName || '—' }}</td>
                  <td class="right mono">{{ row.operationSeq || '—' }}</td>
                  <td class="ellipsis">{{ row.supplierName || '—' }}</td>
                  <td class="mono">{{ row.expectedAt || '—' }}</td>
                  <td>
                    <span v-if="row.overdue" class="pill bad">超期</span>
                    <span v-else class="pill outsource">在途</span>
                  </td>
                </tr>
                <tr v-if="!(prod.outsourcingOrders || []).length">
                  <td colspan="6" class="empty">暂无在途外协</td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>

        <article v-if="hasPanel('lowStockList')" class="panel" :class="{ wide: panelWide('lowStockList') }">
          <header class="panel-head">
            <h3>低库存预警</h3>
            <span class="panel-sub">结存低于安全库存</span>
          </header>
          <div class="table-wrap">
            <table class="grid">
              <thead>
                <tr>
                  <th>产品</th>
                  <th>仓库</th>
                  <th class="right">结存</th>
                  <th class="right">安全库存</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in dash.lowStockList || []" :key="`${row.productCode}-${row.warehouseName}`">
                  <td class="ellipsis">
                    {{ row.productName || '—' }}
                    <span class="muted-code">{{ row.productCode }}</span>
                  </td>
                  <td>{{ row.warehouseName || '—' }}</td>
                  <td class="right mono danger-text">{{ fmtQty(row.qty) }} {{ row.unit || '' }}</td>
                  <td class="right mono">{{ fmtQty(row.safetyStock) }} {{ row.unit || '' }}</td>
                </tr>
                <tr v-if="!(dash.lowStockList || []).length">
                  <td colspan="4" class="empty">所有物料库存充足</td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>

        <article v-if="!config.panels.length" class="panel wide panel-empty">
          还没有选择数据面板，点右上角「配置」勾选
        </article>
      </section>
    </main>

    <footer class="bi-foot">
      <span>
        每 {{ config.refresh }} 秒自动刷新 · 数据仅登录用户可见（含销售额与库存）
      </span>
      <span v-if="!online" class="offline">现场数据源连接中断，正在重试…</span>
    </footer>

    <el-drawer v-model="drillOpen" :title="drillTitle" size="760px" append-to-body class="drill-drawer">
      <div v-loading="drillLoading" class="drill-body">
        <table class="grid">
          <thead>
            <tr>
              <th v-for="col in drillColumns" :key="col.prop">{{ col.label }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in drillRows" :key="index">
              <td v-for="col in drillColumns" :key="col.prop">
                <span v-if="col.money" class="mono">¥{{ fmtMoney(row[col.prop]) }}</span>
                <span v-else>{{ row[col.prop] ?? '—' }}</span>
              </td>
            </tr>
            <tr v-if="!drillRows.length">
              <td :colspan="Math.max(drillColumns.length, 1)" class="empty">暂无明细</td>
            </tr>
          </tbody>
        </table>
      </div>
    </el-drawer>

    <el-drawer v-model="configOpen" title="大屏配置" size="420px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="大屏标题">
          <el-input v-model="draft.title" maxlength="24" show-word-limit placeholder="经营驾驶舱" />
        </el-form-item>
        <el-form-item label="主题色">
          <el-radio-group v-model="draft.accent">
            <el-radio-button value="blue">深海蓝</el-radio-button>
            <el-radio-button value="teal">翡翠青</el-radio-button>
            <el-radio-button value="violet">星紫</el-radio-button>
            <el-radio-button value="amber">琥珀</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="每行列数">
          <el-radio-group v-model="draft.columns">
            <el-radio-button :value="2">2 列</el-radio-button>
            <el-radio-button :value="3">3 列</el-radio-button>
            <el-radio-button :value="4">4 列</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="刷新间隔">
          <el-select v-model="draft.refresh" style="width: 100%">
            <el-option :value="10" label="10 秒" />
            <el-option :value="30" label="30 秒" />
            <el-option :value="60" label="1 分钟" />
            <el-option :value="300" label="5 分钟" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标值（0 表示不设目标）">
          <div class="target-grid">
            <div class="target-item">
              <span>月度销售额</span>
              <el-input-number v-model="draft.targets.monthSales" :min="0" :step="10000" :precision="0" size="small" />
            </div>
            <div class="target-item">
              <span>今日产出（件）</span>
              <el-input-number v-model="draft.targets.todayOutput" :min="0" :step="10" :precision="0" size="small" />
            </div>
            <div class="target-item">
              <span>一次合格率（%）</span>
              <el-input-number v-model="draft.targets.passRate" :min="0" :max="100" :step="1" :precision="1" size="small" />
            </div>
          </div>
        </el-form-item>
        <el-form-item label="指标卡（可多选）">
          <el-checkbox-group v-model="draft.kpis" class="opt-grid">
            <el-checkbox v-for="(def, key) in kpiDefs" :key="key" :value="key">{{ def.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="数据面板（可多选）">
          <el-checkbox-group v-model="draft.panels" class="opt-grid">
            <el-checkbox v-for="(def, key) in PANEL_DEFS" :key="key" :value="key">{{ def.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <el-divider content-position="left">经营日报推送</el-divider>
        <div class="push-block">
          <div class="push-hint">
            {{ pushStatus.hint || '配置飞书 / 钉钉 / 企业微信的群机器人 Webhook 后，每天 08:30 自动推送经营摘要。' }}
          </div>
          <div class="push-row">
            <span class="push-label">群机器人</span>
            <el-select v-model="pushForm.type" size="small" style="width: 100%">
              <el-option value="FEISHU" label="飞书" />
              <el-option value="DINGTALK" label="钉钉" />
              <el-option value="WECOM" label="企业微信" />
            </el-select>
          </div>
          <div class="push-row">
            <span class="push-label">Webhook 地址</span>
            <el-input
              v-model="pushForm.url"
              size="small"
              :placeholder="pushStatus.hasUrl ? `已配置：${pushStatus.urlMask}（留空不改）` : 'https://...'"
            />
          </div>
          <div class="push-row push-switch">
            <span class="push-label">每天 08:30 自动推送</span>
            <el-switch v-model="pushForm.enabled" />
          </div>
          <div class="push-actions">
            <el-button size="small" :loading="pushSaving" @click="savePush">保存推送设置</el-button>
            <el-button size="small" type="primary" plain :loading="pushSending" @click="sendReport">立即推送</el-button>
          </div>
        </div>
      </el-form>
      <template #footer>
        <div class="config-foot">
          <el-button @click="resetConfig">恢复默认</el-button>
          <el-button type="primary" @click="applyConfig">应用</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.bi {
  position: fixed;
  inset: 0;
  display: flex;
  flex-direction: column;
  padding: 16px 22px 12px;
  box-sizing: border-box;
  background:
    radial-gradient(1100px 600px at 10% -12%, var(--accent-glow), transparent 60%),
    radial-gradient(900px 520px at 92% -4%, rgba(91, 141, 239, 0.14), transparent 55%),
    linear-gradient(160deg, #060b18 0%, #0a1224 55%, #070c18 100%);
  color: #e8f1ff;
  font-family: 'Noto Sans CJK SC', 'Microsoft YaHei', system-ui, sans-serif;
  overflow: hidden;
}

.bi-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(126, 170, 255, 0.16);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 14px var(--accent);
}

.brand-text b {
  display: block;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 1px;
}

.brand-text span {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  letter-spacing: 2px;
  color: #7d8fae;
}

.head-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 13px;
  color: #8ea3c4;
}

.stamp {
  font-variant-numeric: tabular-nums;
}

.clock {
  font-size: 21px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #e8f1ff;
}

.countdown {
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid rgba(126, 170, 255, 0.22);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.countdown.refreshing {
  color: var(--accent);
  border-color: var(--accent);
}

/* 经营区间切换 */
.range-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.range-tab {
  padding: 5px 13px;
  border-radius: 999px;
  border: 1px solid rgba(126, 170, 255, 0.22);
  background: transparent;
  color: #9fb2d0;
  font-size: 12px;
  cursor: pointer;
  transition: 0.16s ease;
}

.range-tab:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.range-tab.active {
  color: #06131a;
  background: var(--accent);
  border-color: var(--accent);
  font-weight: 700;
  box-shadow: 0 0 14px var(--accent-glow);
}

.range-bar :deep(.el-date-editor) {
  --el-date-editor-width: 236px;
}

.ghost {
  padding: 5px 12px;
  border-radius: 8px;
  border: 1px solid rgba(126, 170, 255, 0.24);
  background: rgba(255, 255, 255, 0.04);
  color: #b9c9e2;
  cursor: pointer;
  font-size: 12px;
  transition: 0.16s ease;
}

.ghost:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.bi-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.bi-body::-webkit-scrollbar,
.table-wrap::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

.bi-body::-webkit-scrollbar-thumb,
.table-wrap::-webkit-scrollbar-thumb {
  background: rgba(126, 170, 255, 0.22);
  border-radius: 999px;
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(168px, 1fr));
  gap: 12px;
}

.kpi {
  position: relative;
  padding: 12px 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(126, 170, 255, 0.14);
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow: hidden;
}

.kpi::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--accent);
  opacity: 0.75;
}

.kpi.warn {
  border-color: rgba(255, 196, 60, 0.42);
}

.kpi.warn::after {
  background: #ffc94a;
}

.kpi.alert {
  border-color: rgba(255, 92, 92, 0.5);
  background: rgba(255, 92, 92, 0.07);
}

.kpi.alert::after {
  background: #ff5c5c;
}

.kpi.ok::after {
  background: #35e0b0;
}

.kpi-label {
  font-size: 12px;
  letter-spacing: 1px;
  color: #8ea3c4;
}

.kpi-value {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.12;
  font-variant-numeric: tabular-nums;
}

.kpi-value small {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 400;
  color: #8ea3c4;
}

.kpi-foot {
  font-size: 11px;
  color: #7d8fae;
}

.kpi.clickable {
  cursor: pointer;
  transition: 0.16s ease;
}

.kpi.clickable:hover {
  border-color: var(--accent);
  transform: translateY(-1px);
  box-shadow: 0 8px 22px rgba(4, 10, 24, 0.55);
}

.kpi-target {
  margin-top: 2px;
}

.kpi-target-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 10px;
  color: #7d8fae;
}

.kpi-track {
  margin-top: 3px;
  height: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.kpi-track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--accent), rgba(255, 255, 255, 0.7));
  transition: width 0.5s ease;
}

/* 图表 PNG 导出的小按钮 */
.mini {
  margin-left: 4px;
  padding: 0 5px;
  border-radius: 4px;
  border: 1px solid rgba(126, 170, 255, 0.24);
  background: transparent;
  color: #6e8099;
  font-size: 9px;
  cursor: pointer;
}

.mini:hover {
  color: var(--accent);
  border-color: var(--accent);
}

.empty-kpi {
  color: #7d8fae;
  font-size: 13px;
  align-items: flex-start;
  justify-content: center;
}

.panels {
  display: grid;
  grid-template-columns: repeat(var(--cols, 3), minmax(0, 1fr));
  gap: 14px;
  padding-bottom: 4px;
}

.panel {
  min-height: 262px;
  display: flex;
  flex-direction: column;
  padding: 12px 16px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(126, 170, 255, 0.14);
  overflow: hidden;
}

.panel.wide {
  grid-column: span 2;
}

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.panel-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 1px;
  color: #cfe0f7;
}

.panel-sub {
  font-size: 11px;
  color: #6e8099;
}

.chart {
  flex: 1;
  min-height: 190px;
}

.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.grid {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.grid thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 7px 10px;
  text-align: left;
  font-weight: 500;
  font-size: 11px;
  letter-spacing: 1px;
  color: #7d8fae;
  background: #0a1224;
  border-bottom: 1px solid rgba(126, 170, 255, 0.16);
}

.grid tbody td {
  padding: 8px 10px;
  border-bottom: 1px solid rgba(126, 170, 255, 0.08);
  color: #d7e4f7;
  white-space: nowrap;
}

.grid tbody tr.danger td {
  background: rgba(255, 92, 92, 0.08);
}

.grid .right {
  text-align: right;
}

.grid .mono {
  font-variant-numeric: tabular-nums;
  color: #a9bcd8;
}

.grid .ellipsis {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.muted-code {
  margin-left: 6px;
  font-size: 11px;
  color: #6e8099;
}

.danger-text {
  color: #ff9f9f;
}

.empty {
  padding: 18px 10px;
  text-align: center;
  color: #6e8099;
}

.pill {
  display: inline-block;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11px;
  border: 1px solid rgba(126, 170, 255, 0.24);
  color: #a9bcd8;
}

.pill.ok {
  border-color: rgba(53, 224, 176, 0.5);
  color: #58e8c0;
  background: rgba(53, 224, 176, 0.08);
}

.pill.warn,
.pill.wait {
  border-color: rgba(255, 196, 60, 0.5);
  color: #ffc94a;
  background: rgba(255, 196, 60, 0.08);
}

.pill.bad {
  border-color: rgba(255, 92, 92, 0.55);
  color: #ff7b7b;
  background: rgba(255, 92, 92, 0.1);
}

.pill.outsource {
  border-color: rgba(255, 148, 64, 0.55);
  color: #ffa657;
  background: rgba(255, 148, 64, 0.12);
}

.panel-empty {
  align-items: center;
  justify-content: center;
  color: #7d8fae;
  font-size: 13px;
}

.bi-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  font-size: 11px;
  color: #6e8099;
}

.offline {
  color: #ff9f43;
}

.opt-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 8px;
  width: 100%;
}

.config-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* 目标值输入 */
.target-grid {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.target-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
  color: #606266;
}

/* 日报推送 */
.push-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.push-hint {
  padding: 8px 10px;
  border-radius: 8px;
  background: #f4f7ff;
  color: #5b6b85;
  font-size: 12px;
  line-height: 1.6;
}

.push-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.push-row.push-switch {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
}

.push-label {
  font-size: 12px;
  color: #606266;
}

.push-actions {
  display: flex;
  gap: 8px;
}

/* 下钻明细抽屉：跟大屏保持同一套深色 */
:global(.drill-drawer .el-drawer__header) {
  margin-bottom: 0;
  padding: 16px 20px;
  background: #0a1224;
  color: #e8f1ff;
}

:global(.drill-drawer .el-drawer__body) {
  padding: 0;
  background: #0a1224;
}

.drill-body {
  min-height: 100%;
  padding: 12px;
  background: #0a1224;
}

@media (max-width: 1180px) {
  .bi-head {
    flex-wrap: wrap;
  }

  .range-bar {
    order: 3;
    width: 100%;
  }
}

@media (max-width: 900px) {
  .panels {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .panel.wide {
    grid-column: span 2;
  }
}

@media (max-width: 640px) {
  .panels {
    grid-template-columns: minmax(0, 1fr);
  }
  .panel.wide {
    grid-column: span 1;
  }
  .head-meta .stamp,
  .head-meta .countdown {
    display: none;
  }
}
</style>
