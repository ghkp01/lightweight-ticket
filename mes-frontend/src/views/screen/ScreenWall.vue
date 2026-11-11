<script setup lang="ts">
/**
 * 车间大屏：三屏轮播（生产 / 质量 / 异常）。
 *
 * 不走登录态、不套后台框架 —— 车间电视打开 `/#/screen` 就一直在刷。
 * 数据来自 `/api/public/screen/*`（后端对 GET 的 /api/public/** 放行），
 * 每 10 秒拉一次当前屏，每 15 秒切下一屏；地址带 `?rotate=30` 可改切屏秒数，
 * 带 `?only=quality` 可锁定单屏（比如质量部只要那一屏）。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../../api'

const SCREENS = [
  { key: 'production', label: '生产' },
  { key: 'quality', label: '质量' },
  { key: 'andon', label: '异常' },
  { key: 'oee', label: '设备' }
] as const

type ScreenKey = (typeof SCREENS)[number]['key']

const POLL_MS = 10000
const query = new URLSearchParams(location.search)
const only = query.get('only') as ScreenKey | null
const rotateMs = Math.max(Number(query.get('rotate') || 15), 5) * 1000

const pool = only && SCREENS.some((item) => item.key === only)
  ? SCREENS.filter((item) => item.key === only)
  : SCREENS

const index = ref(0)
const clock = ref('')
const fullscreen = ref(false)
const online = ref(true)
const loading = ref(false)
const boards = ref<Record<string, any>>({ production: null, quality: null, andon: null, oee: null })

let pollTimer: number | undefined
let rotateTimer: number | undefined
let clockTimer: number | undefined

const current = computed(() => pool[index.value] || pool[0])
const board = computed(() => boards.value[current.value.key] || {})
const kpi = computed(() => board.value.kpi || {})
const title = computed(() => board.value.title || '车间大屏')
const updatedAt = computed(() => board.value.updatedAt || '')

const pct = (value: any, digits = 1) => {
  if (value === null || value === undefined || value === '') return '—'
  return `${(Number(value) * 100).toFixed(digits)}%`
}
/** OEE 类指标后端已经是 0~100 的数，原样加 %，不能再乘 100 */
const pct100 = (value: any) => {
  if (value === null || value === undefined || value === '') return '—'
  return `${Number(value).toFixed(1)}%`
}
const num = (value: any) => {
  const parsed = Number(value ?? 0)
  return parsed.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}
const clamp = (value: any) => Math.max(0, Math.min(Number(value || 0), 1))

/**
 * 「外协在途」直接消费生产屏同一个响应对象（顶层 outsourcingOrders），
 * 不额外发请求 —— load() 刷新 board 时这张卡片会跟着一起更新。
 */
const outsourcingList = computed<any[]>(() => {
  const list = board.value.outsourcingOrders
  return Array.isArray(list) ? list : []
})
/** 大屏只展示最多 8 条，避免撑高卡片 */
const outsourcingRows = computed(() => outsourcingList.value.slice(0, 8))
const outsourcingOverdue = computed(() => outsourcingList.value.filter((row: any) => !!row?.overdue).length)
const receivedPct = (row: any) => clamp(Number(row?.receivedQty || 0) / Math.max(Number(row?.qty || 0), 1))
const overdueText = (row: any) => {
  const days = Number(row?.overdueDays || 0)
  return days > 0 ? `超期 ${days} 天` : '超期'
}

const STATUS_TEXT: Record<string, string> = {
  SCHEDULED: '已排产', RELEASED: '已下达', IN_PROGRESS: '生产中',
  REWORK: '返修中', FINISHED: '已完工', CLOSED: '已关闭'
}
const ANDON_TYPE: Record<string, string> = {
  MATERIAL: '缺料', EQUIPMENT: '设备', QUALITY: '质量', OTHER: '其他'
}
const ANDON_STATUS: Record<string, string> = {
  OPEN: '待接单', ACKED: '处理中', RESOLVED: '已解决'
}
const INSPECT_TYPE: Record<string, string> = {
  INCOMING: '来料', PROCESS: '过程', FINAL: '完工'
}

const tick = () => {
  const now = new Date()
  clock.value = now.toLocaleTimeString('zh-CN', { hour12: false })
}

const load = async () => {
  if (loading.value) return
  loading.value = true
  try {
    const data = await api.get(`/public/screen/${current.value.key}`)
    boards.value = { ...boards.value, [current.value.key]: data }
    online.value = true
  } catch (error) {
    online.value = false
  } finally {
    loading.value = false
  }
}

const select = (target: number) => {
  index.value = target
  load()
}

const next = () => {
  if (pool.length <= 1) return
  index.value = (index.value + 1) % pool.length
  load()
}

const startRotation = () => {
  if (rotateTimer) window.clearInterval(rotateTimer)
  if (pool.length > 1) rotateTimer = window.setInterval(next, rotateMs)
}

const toggleFullscreen = async () => {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
      fullscreen.value = false
    } else {
      await document.documentElement.requestFullscreen()
      fullscreen.value = true
    }
  } catch (error) {
    fullscreen.value = !!document.fullscreenElement
  }
}

const onKey = (event: KeyboardEvent) => {
  if (event.key === 'ArrowRight') next()
  if (event.key === 'ArrowLeft') {
    index.value = (index.value - 1 + pool.length) % pool.length
    load()
  }
  if (event.key.toLowerCase() === 'f') toggleFullscreen()
}

onMounted(() => {
  tick()
  load()
  clockTimer = window.setInterval(tick, 1000)
  pollTimer = window.setInterval(load, POLL_MS)
  startRotation()
  window.addEventListener('keydown', onKey)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  if (rotateTimer) window.clearInterval(rotateTimer)
  if (clockTimer) window.clearInterval(clockTimer)
  window.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div class="wall" :class="`theme-${current.key}`">
    <header class="wall-head">
      <div class="brand">
        <span class="brand-dot" />
        <span class="brand-title">{{ title }}</span>
        <span class="brand-sub">智造工单 MES</span>
      </div>
      <nav v-if="pool.length > 1" class="tabs">
        <button
          v-for="(item, i) in pool"
          :key="item.key"
          class="tab"
          :class="{ active: i === index }"
          @click="select(i)"
        >
          {{ item.label }}
        </button>
      </nav>
      <div class="meta">
        <span class="updated">{{ updatedAt || '等待数据…' }}</span>
        <span class="clock">{{ clock }}</span>
        <button class="ghost" @click="toggleFullscreen">{{ fullscreen ? '退出全屏' : '全屏' }}</button>
      </div>
    </header>

    <main class="wall-body">
      <!-- 生产屏 -->
      <section v-if="current.key === 'production'" class="screen production">
        <div class="kpi-row">
          <div class="kpi">
            <span class="kpi-label">在制工单</span>
            <span class="kpi-value">{{ num(kpi.inProgress) }}<small>张</small></span>
          </div>
          <div class="kpi">
            <span class="kpi-label">今日产出</span>
            <span class="kpi-value">{{ num(kpi.todayOutput) }}<small>件</small></span>
          </div>
          <div class="kpi">
            <span class="kpi-label">今日合格</span>
            <span class="kpi-value">{{ num(kpi.todayQualified) }}<small>件</small></span>
          </div>
          <div class="kpi" :class="{ warn: Number(kpi.attainment || 0) < 0.8 }">
            <span class="kpi-label">计划达成</span>
            <span class="kpi-value">{{ pct(kpi.attainment) }}</span>
            <span class="kpi-foot">计划 {{ num(kpi.todayPlanQty) }} 件</span>
          </div>
          <div class="kpi" :class="{ alert: Number(kpi.dueToday || 0) > 0 }">
            <span class="kpi-label">今日交期</span>
            <span class="kpi-value">{{ num(kpi.dueToday) }}<small>单</small></span>
          </div>
          <div class="kpi" :class="{ alert: Number(kpi.shortageOrders || 0) > 0 }">
            <span class="kpi-label">缺料工单</span>
            <span class="kpi-value">{{ num(kpi.shortageOrders) }}<small>张</small></span>
          </div>
        </div>

        <div class="two-col">
          <div class="panel">
            <h3 class="panel-title">工作中心今日负荷</h3>
            <ul class="load-list">
              <li v-for="center in board.workCenters || []" :key="center.id" class="load-item">
                <div class="load-head">
                  <span class="load-name">{{ center.name }}</span>
                  <span class="load-value" :class="{ over: Number(center.loadRate || 0) > 1 }">
                    {{ pct(center.loadRate, 0) }}
                  </span>
                </div>
                <div class="bar">
                  <i :style="{ width: `${clamp(center.loadRate) * 100}%` }" :class="{ over: Number(center.loadRate || 0) > 1 }" />
                </div>
                <div class="load-foot">
                  已排 {{ num(center.plannedMinutes) }} / {{ num(center.dailyCapacityMinutes) }} 分钟
                </div>
              </li>
              <li v-if="!(board.workCenters || []).length" class="empty">今日没有排产工序</li>
            </ul>
          </div>

          <div class="panel">
            <h3 class="panel-title">
              在制工单进度
              <span class="muted-code" v-if="Number(kpi.outsourcing || 0) > 0">
                外协中 {{ num(kpi.outsourcing) }} 道
                <template v-if="Number(kpi.outsourcingOverdue || 0) > 0">（超期 {{ num(kpi.outsourcingOverdue) }}）</template>
              </span>
            </h3>
            <table class="grid">
              <thead>
                <tr>
                  <th>工单</th>
                  <th>产品</th>
                  <th class="right">数量</th>
                  <th>当前工序</th>
                  <th class="progress-col">工序进度</th>
                  <th>排产日</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="order in board.orders || []" :key="order.workNo" :class="order.status.toLowerCase()">
                  <td class="mono">{{ order.workNo }}</td>
                  <td class="ellipsis">{{ order.productName || order.productCode || '—' }}</td>
                  <td class="right mono">{{ num(order.planQty) }}</td>
                  <td class="ellipsis">
                    {{ order.currentOperation || '—' }}
                    <span v-if="Number(order.outsourcingOps || 0) > 0" class="pill outsource">外协</span>
                  </td>
                  <td class="progress-col">
                    <div class="bar thin">
                      <i :style="{ width: `${clamp(order.progress) * 100}%` }" />
                    </div>
                    <span class="progress-text">{{ num(order.opDone) }} / {{ num(order.opTotal) }}</span>
                  </td>
                  <td class="mono">{{ order.scheduleDate || '—' }}</td>
                  <td><span class="pill" :class="order.status.toLowerCase()">{{ STATUS_TEXT[order.status] || order.status }}</span></td>
                </tr>
                <tr v-if="!(board.orders || []).length">
                  <td colspan="7" class="empty">暂无在制工单</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 外协在途：与生产屏同一个响应对象，最多 8 条，超期整行标红 -->
        <div class="panel outsourcing-panel">
          <h3 class="panel-title">
            外协在途
            <span class="muted-code">
              共 {{ num(outsourcingList.length) }} 单
              <template v-if="outsourcingOverdue > 0"> · 超期 {{ num(outsourcingOverdue) }} 单</template>
            </span>
          </h3>
          <table class="grid outsourcing-grid">
            <thead>
              <tr>
                <th>外协单 / 工单</th>
                <th>产品</th>
                <th class="right">工序</th>
                <th class="progress-col">收回进度</th>
                <th>外协厂</th>
                <th>约定回厂</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in outsourcingRows" :key="row.orderNo || row.workNo" :class="{ overdue: !!row.overdue }">
                <td class="mono">
                  {{ row.orderNo || '—' }}
                  <span class="muted-code">{{ row.workNo || '—' }}</span>
                </td>
                <td class="ellipsis">{{ row.productName || '—' }}</td>
                <td class="right mono">{{ row.operationSeq || '—' }}</td>
                <td class="progress-col">
                  <div class="bar thin">
                    <i :style="{ width: `${receivedPct(row) * 100}%` }" />
                  </div>
                  <span class="progress-text">{{ num(row.receivedQty) }} / {{ num(row.qty) }}</span>
                </td>
                <td class="ellipsis">{{ row.supplierName || '—' }}</td>
                <td class="mono">{{ row.expectedAt || '—' }}</td>
                <td>
                  <span v-if="row.overdue" class="pill bad">{{ overdueText(row) }}</span>
                  <span v-else class="pill outsource">在途</span>
                </td>
              </tr>
              <tr v-if="!outsourcingRows.length">
                <td colspan="7" class="empty">暂无在途外协</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 质量屏 -->
      <section v-else-if="current.key === 'quality'" class="screen">
        <div class="kpi-row">
          <div class="kpi">
            <span class="kpi-label">今日检验</span>
            <span class="kpi-value">{{ num(kpi.todayInspections) }}<small>单</small></span>
          </div>
          <div class="kpi">
            <span class="kpi-label">检验数量</span>
            <span class="kpi-value">{{ num(kpi.todayInspectQty) }}<small>件</small></span>
          </div>
          <div class="kpi" :class="{ alert: Number(kpi.todayDefectQty || 0) > 0 }">
            <span class="kpi-label">不良数量</span>
            <span class="kpi-value">{{ num(kpi.todayDefectQty) }}<small>件</small></span>
          </div>
          <div class="kpi" :class="{ warn: kpi.passRate !== null && Number(kpi.passRate) < 0.97 }">
            <span class="kpi-label">一次合格率</span>
            <span class="kpi-value">{{ pct(kpi.passRate) }}</span>
          </div>
          <div class="kpi" :class="{ alert: Number(kpi.openDefects || 0) > 0 }">
            <span class="kpi-label">待处置不良</span>
            <span class="kpi-value">{{ num(kpi.openDefects) }}<small>笔</small></span>
          </div>
          <div class="kpi">
            <span class="kpi-label">返修在制</span>
            <span class="kpi-value">{{ num(kpi.reworkOrders) }}<small>张</small></span>
          </div>
        </div>

        <div class="two-col">
          <div class="panel">
            <h3 class="panel-title">今日不良 TOP5</h3>
            <ul class="load-list">
              <li v-for="item in board.defectTop || []" :key="item.reason" class="load-item">
                <div class="load-head">
                  <span class="load-name ellipsis">{{ item.reason }}</span>
                  <span class="load-value">{{ num(item.qty) }} 件</span>
                </div>
                <div class="bar">
                  <i :style="{ width: `${clamp(Number(item.qty) / Math.max(Number((board.defectTop || [{}])[0]?.qty || 1), 1)) * 100}%` }" />
                </div>
              </li>
              <li v-if="!(board.defectTop || []).length" class="empty">今日没有不良记录</li>
            </ul>
          </div>

          <div class="panel">
            <h3 class="panel-title">最近检验</h3>
            <table class="grid">
              <thead>
                <tr>
                  <th>检验单</th>
                  <th>类型</th>
                  <th>产品</th>
                  <th class="right">检验/不良</th>
                  <th>结果</th>
                  <th>时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in board.inspections || []" :key="item.inspectionNo">
                  <td class="mono">{{ item.inspectionNo }}</td>
                  <td>{{ INSPECT_TYPE[item.type] || item.type }}</td>
                  <td class="ellipsis">{{ item.productName || '—' }}</td>
                  <td class="right mono">{{ num(item.inspectQty) }} / {{ num(item.defectQty) }}</td>
                  <td>
                    <span class="pill" :class="item.result === 'PASS' ? 'ok' : item.result === 'FAIL' ? 'bad' : 'wait'">
                      {{ item.result === 'PASS' ? '合格' : item.result === 'FAIL' ? '不合格' : '待判' }}
                    </span>
                  </td>
                  <td class="mono">{{ item.inspectedAt || '—' }}</td>
                </tr>
                <tr v-if="!(board.inspections || []).length">
                  <td colspan="6" class="empty">今日还没有检验记录</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <!-- 异常屏 -->
      <section v-else-if="current.key === 'andon'" class="screen">
        <div class="kpi-row">
          <div class="kpi" :class="{ alert: Number(kpi.open || 0) > 0 }">
            <span class="kpi-label">待接单</span>
            <span class="kpi-value">{{ num(kpi.open) }}<small>条</small></span>
          </div>
          <div class="kpi" :class="{ warn: Number(kpi.acked || 0) > 0 }">
            <span class="kpi-label">处理中</span>
            <span class="kpi-value">{{ num(kpi.acked) }}<small>条</small></span>
          </div>
          <div class="kpi">
            <span class="kpi-label">今日已解决</span>
            <span class="kpi-value">{{ num(kpi.resolvedToday) }}<small>条</small></span>
          </div>
          <div class="kpi">
            <span class="kpi-label">今日呼叫</span>
            <span class="kpi-value">{{ num(kpi.todayTotal) }}<small>条</small></span>
          </div>
          <div class="kpi">
            <span class="kpi-label">平均响应</span>
            <span class="kpi-value">{{ num(kpi.avgAckMinutes) }}<small>分钟</small></span>
          </div>
        </div>

        <div class="panel full">
          <h3 class="panel-title">异常清单（未关闭优先）</h3>
          <table class="grid">
            <thead>
              <tr>
                <th>呼叫单</th>
                <th>类型</th>
                <th>工单</th>
                <th>工序</th>
                <th>描述</th>
                <th class="right">持续</th>
                <th>状态</th>
                <th>呼叫时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="call in board.calls || []" :key="call.callNo" :class="String(call.status).toLowerCase()">
                <td class="mono">{{ call.callNo }}</td>
                <td><span class="pill" :class="String(call.type).toLowerCase()">{{ ANDON_TYPE[call.type] || call.type }}</span></td>
                <td class="mono">{{ call.workOrderNo || '—' }}</td>
                <td>{{ call.operationName || '—' }}</td>
                <td class="ellipsis">{{ call.description }}</td>
                <td class="right mono" :class="{ over: Number(call.elapsedMinutes) >= 30 }">{{ num(call.elapsedMinutes) }} 分</td>
                <td><span class="pill" :class="String(call.status).toLowerCase()">{{ ANDON_STATUS[call.status] || call.status }}</span></td>
                <td class="mono">{{ call.createdAt }}</td>
              </tr>
              <tr v-if="!(board.calls || []).length">
                <td colspan="8" class="empty">当前没有异常呼叫</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 设备屏（OEE） -->
      <section v-else class="screen">
        <div class="kpi-row">
          <div class="kpi">
            <span class="kpi-label">今日 OEE</span>
            <span class="kpi-value">{{ pct100(kpi.oee) }}</span>
            <span class="kpi-foot">可用率 × 性能 × 质量</span>
          </div>
          <div class="kpi">
            <span class="kpi-label">可用率 A</span>
            <span class="kpi-value">{{ pct100(kpi.availability) }}</span>
          </div>
          <div class="kpi">
            <span class="kpi-label">性能 P</span>
            <span class="kpi-value">{{ pct100(kpi.performance) }}</span>
          </div>
          <div class="kpi">
            <span class="kpi-label">质量 Q</span>
            <span class="kpi-value">{{ pct100(kpi.quality) }}</span>
          </div>
          <div class="kpi">
            <span class="kpi-label">今日报工</span>
            <span class="kpi-value">{{ num(kpi.reportQty) }}<small>件</small></span>
          </div>
          <div class="kpi">
            <span class="kpi-label">统计中心</span>
            <span class="kpi-value">{{ num(kpi.centers) }}<small>个</small></span>
          </div>
        </div>

        <div class="panel full">
          <h3 class="panel-title">分工作中心 OEE（今日 · 按 OEE 降序）</h3>
          <table class="grid">
            <thead>
              <tr>
                <th>工作中心</th>
                <th class="progress-col">OEE</th>
                <th class="right">可用率</th>
                <th class="right">性能</th>
                <th class="right">质量</th>
                <th class="right">停机</th>
                <th class="right">报工量</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="center in board.centers || []" :key="center.code || center.name">
                <td>{{ center.name }}<span class="muted-code">{{ center.code }}</span></td>
                <td class="progress-col">
                  <div class="bar thin">
                    <i :style="{ width: `${clamp(Number(center.oee || 0) / 100) * 100}%` }"
                       :class="{ over: Number(center.oee || 0) < 60 }" />
                  </div>
                  <span class="progress-text">{{ pct100(center.oee) }}</span>
                </td>
                <td class="right mono">{{ pct100(center.availability) }}</td>
                <td class="right mono">{{ pct100(center.performance) }}</td>
                <td class="right mono">{{ pct100(center.quality) }}</td>
                <td class="right mono">{{ num(center.downtimeMinutes) }} 分</td>
                <td class="right mono">{{ num(center.reportQty) }}</td>
              </tr>
              <tr v-if="!(board.centers || []).length">
                <td colspan="7" class="empty">还没有工作中心数据</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </main>

    <footer class="wall-foot">
      <span>每 {{ POLL_MS / 1000 }} 秒刷新 · 每 {{ rotateMs / 1000 }} 秒切屏 · ←/→ 手动切屏 · F 全屏</span>
      <span v-if="!online" class="offline">连接中断，正在重试…</span>
      <div v-if="pool.length > 1" class="dots">
        <i v-for="(item, i) in pool" :key="item.key" :class="{ on: i === index }" />
      </div>
    </footer>
  </div>
</template>

<style scoped>
.wall {
  --accent: #35e0b0;
  --accent-soft: rgba(53, 224, 176, 0.14);
  /* 告警色：和 .kpi.alert / .pill.bad / tr.open 上的红保持一致 */
  --alert: #ff5c5c;
  --alert-soft: rgba(255, 92, 92, 0.1);
  position: fixed;
  inset: 0;
  display: flex;
  flex-direction: column;
  padding: 18px 26px 12px;
  box-sizing: border-box;
  background:
    radial-gradient(1100px 600px at 12% -10%, rgba(53, 224, 176, 0.16), transparent 60%),
    radial-gradient(900px 520px at 92% 0%, rgba(78, 168, 255, 0.14), transparent 55%),
    #060b18;
  color: #e8f1ff;
  font-family: 'Noto Sans CJK SC', 'Microsoft YaHei', system-ui, sans-serif;
  overflow: hidden;
}
/* 车间电视上默认滚动条是亮色的，在暗底上很扎眼，统一成细窄的暗色条 */
.grid::-webkit-scrollbar,
.load-list::-webkit-scrollbar,
.panel::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.grid::-webkit-scrollbar-track,
.load-list::-webkit-scrollbar-track,
.panel::-webkit-scrollbar-track {
  background: transparent;
}
.grid::-webkit-scrollbar-thumb,
.load-list::-webkit-scrollbar-thumb,
.panel::-webkit-scrollbar-thumb {
  background: rgba(126, 170, 255, 0.22);
  border-radius: 999px;
}
.wall.theme-quality {
  --accent: #4ea8ff;
  --accent-soft: rgba(78, 168, 255, 0.16);
  background:
    radial-gradient(1100px 600px at 12% -10%, rgba(78, 168, 255, 0.2), transparent 60%),
    radial-gradient(900px 520px at 92% 0%, rgba(150, 120, 255, 0.16), transparent 55%),
    #060b18;
}
.wall.theme-andon {
  --accent: #ffa62b;
  --accent-soft: rgba(255, 166, 43, 0.16);
  background:
    radial-gradient(1100px 600px at 12% -10%, rgba(255, 120, 80, 0.18), transparent 60%),
    radial-gradient(900px 520px at 92% 0%, rgba(255, 196, 60, 0.14), transparent 55%),
    #0a0710;
}
.wall.theme-oee {
  --accent: #9b8cff;
  --accent-soft: rgba(155, 140, 255, 0.16);
  background:
    radial-gradient(1100px 600px at 12% -10%, rgba(155, 140, 255, 0.2), transparent 60%),
    radial-gradient(900px 520px at 92% 0%, rgba(56, 200, 255, 0.14), transparent 55%),
    #070a16;
}
.muted-code {
  margin-left: 8px;
  font-size: 12px;
  color: #6e8099;
}

.wall-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(126, 170, 255, 0.16);
}
.brand {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.brand-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 14px var(--accent);
  transform: translateY(-2px);
}
.brand-title {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 1px;
}
.brand-sub {
  font-size: 13px;
  color: #7d8fae;
  letter-spacing: 2px;
}
.tabs {
  display: flex;
  gap: 8px;
}
.tab {
  padding: 7px 20px;
  border-radius: 999px;
  border: 1px solid rgba(126, 170, 255, 0.22);
  background: transparent;
  color: #9fb2d0;
  font-size: 15px;
  cursor: pointer;
  transition: all 0.2s;
}
.tab.active {
  color: #06131a;
  background: var(--accent);
  border-color: var(--accent);
  font-weight: 700;
  box-shadow: 0 0 18px var(--accent-soft);
}
.meta {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 14px;
  color: #8ea3c4;
}
.clock {
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #e8f1ff;
}
.ghost {
  padding: 5px 12px;
  border-radius: 8px;
  border: 1px solid rgba(126, 170, 255, 0.24);
  background: rgba(255, 255, 255, 0.04);
  color: #b9c9e2;
  cursor: pointer;
}
.ghost:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.wall-body {
  flex: 1;
  min-height: 0;
  padding-top: 14px;
}
.screen {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
/* 生产屏多了「外协在途」一块：高度吃紧时整屏可纵向滚动，
   两栏保住最小高度，外协卡只在自己的表格里滚动，不会撑破 fixed 大屏 */
.screen.production {
  overflow-y: auto;
  overflow-x: hidden;
}
.screen.production .two-col {
  flex: 1 1 auto;
  min-height: 260px;
}
.outsourcing-panel {
  flex: 0 0 auto;
  max-height: 36%;
}
.outsourcing-grid {
  flex: 1 1 auto;
  min-height: 0;
}
/* 超期：沿用大屏告警红（--alert / --alert-soft），整行标红 */
.outsourcing-grid tbody tr.overdue td {
  background: var(--alert-soft);
  color: #ffc7c7;
}
.outsourcing-grid tbody tr.overdue td:first-child {
  box-shadow: inset 3px 0 0 var(--alert);
}
.outsourcing-grid tbody tr.overdue td.mono {
  color: #ff9f9f;
}
/* .pill.outsource 原本跟在工序名后面，这里单独占一格，去掉左侧间距 */
.outsourcing-grid .pill.outsource {
  margin-left: 0;
}
@media (max-height: 900px) {
  .screen.production .two-col {
    min-height: 220px;
  }
  .outsourcing-grid {
    font-size: 13px;
  }
}
@media (max-width: 1440px) {
  .outsourcing-panel {
    max-height: 42%;
  }
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
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
  opacity: 0.7;
}
.kpi.warn {
  border-color: rgba(255, 196, 60, 0.45);
}
.kpi.alert {
  border-color: rgba(255, 92, 92, 0.5);
  background: rgba(255, 92, 92, 0.07);
}
.kpi-label {
  font-size: 13px;
  color: #8ea3c4;
  letter-spacing: 1px;
}
.kpi-value {
  font-size: 32px;
  font-weight: 700;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.kpi-value small {
  margin-left: 4px;
  font-size: 13px;
  font-weight: 400;
  color: #8ea3c4;
}
.kpi-foot {
  font-size: 12px;
  color: #7d8fae;
}

.two-col {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr);
  gap: 14px;
}
.panel {
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 14px 18px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(126, 170, 255, 0.14);
  overflow: hidden;
}
.panel.full {
  flex: 1;
}
.panel-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: #cfe0f7;
  letter-spacing: 1px;
}

.load-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.load-head {
  display: flex;
  justify-content: space-between;
  font-size: 15px;
  margin-bottom: 6px;
}
.load-name {
  color: #dbe7f8;
}
.load-value {
  font-variant-numeric: tabular-nums;
  color: var(--accent);
}
.load-value.over {
  color: #ff6b6b;
}
.load-foot {
  margin-top: 5px;
  font-size: 12px;
  color: #7d8fae;
}
.bar {
  height: 9px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.07);
  overflow: hidden;
}
.bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--accent), rgba(255, 255, 255, 0.65));
  transition: width 0.6s ease;
}
.bar i.over {
  background: linear-gradient(90deg, #ff7b54, #ff4d4f);
}
.bar.thin {
  height: 6px;
}

.grid {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
  overflow: auto;
  display: block;
}
.grid thead th {
  position: sticky;
  top: 0;
  padding: 8px 10px;
  text-align: left;
  font-weight: 500;
  font-size: 12px;
  letter-spacing: 1px;
  color: #7d8fae;
  background: #070d1c;
  border-bottom: 1px solid rgba(126, 170, 255, 0.16);
}
.grid tbody td {
  padding: 9px 10px;
  border-bottom: 1px solid rgba(126, 170, 255, 0.08);
  color: #d7e4f7;
  white-space: nowrap;
}
.grid tbody tr.rework td,
.grid tbody tr.open td {
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
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.grid .progress-col {
  width: 130px;
}
.progress-text {
  display: inline-block;
  margin-top: 3px;
  font-size: 11px;
  color: #7d8fae;
  font-variant-numeric: tabular-nums;
}
.empty {
  padding: 16px 10px;
  color: #6e8099;
  text-align: center;
}

.pill {
  display: inline-block;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 12px;
  border: 1px solid rgba(126, 170, 255, 0.24);
  color: #a9bcd8;
}
.pill.in_progress,
.pill.ok,
.pill.resolved {
  border-color: rgba(53, 224, 176, 0.5);
  color: #58e8c0;
  background: rgba(53, 224, 176, 0.08);
}
.pill.rework,
.pill.open,
.pill.bad {
  border-color: rgba(255, 92, 92, 0.55);
  color: #ff7b7b;
  background: rgba(255, 92, 92, 0.1);
}
.pill.acked,
.pill.warn,
.pill.material {
  border-color: rgba(255, 196, 60, 0.55);
  color: #ffc94a;
  background: rgba(255, 196, 60, 0.1);
}
.pill.quality {
  border-color: rgba(78, 168, 255, 0.55);
  color: #77bcff;
  background: rgba(78, 168, 255, 0.1);
}
.pill.equipment {
  border-color: rgba(180, 130, 255, 0.55);
  color: #c19bff;
  background: rgba(180, 130, 255, 0.1);
}
/* 外协中：工序在外部做，用橙色区分厂内在制 */
.pill.outsource {
  margin-left: 6px;
  border-color: rgba(255, 148, 64, 0.55);
  color: #ffa657;
  background: rgba(255, 148, 64, 0.12);
}

.wall-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 10px;
  font-size: 12px;
  color: #6e8099;
}
.offline {
  color: #ff9f43;
}
.dots {
  display: flex;
  gap: 8px;
}
.dots i {
  width: 22px;
  height: 4px;
  border-radius: 999px;
  background: rgba(126, 170, 255, 0.25);
}
.dots i.on {
  background: var(--accent);
}
</style>
