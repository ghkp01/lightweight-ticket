<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtQty } from '../../api'
import ReferenceSelect from '../../components/ReferenceSelect.vue'

const router = useRouter()

const pad = (value: number) => String(value).padStart(2, '0')
const dateText = (date: Date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`

const today = new Date()
const end = new Date()
end.setDate(end.getDate() + 13)

const range = ref<[string, string]>([dateText(today), dateText(end)])
const loading = ref(false)
const data = ref<any>({ days: [], centers: [], summary: {} })

const simForm = reactive<any>({ productId: null, qty: 10, dueDate: '' })
const simulating = ref(false)
const simulation = ref<any>(null)

// 拖动模拟
const dragBar = ref<any>(null)
const moveLoading = ref(false)
const moveResult = ref<any>(null)
const moveDialog = ref(false)
const dragOverCell = ref<string | null>(null)
// 拖动视觉反馈：是否正在拖 / 悬停的目标行(centerId) / 悬停的目标日期
const isDragging = ref(false)
const dragOverRow = ref<number | null>(null)
const dragOverDate = ref<string | null>(null)

const days = computed(() => data.value.days || [])
const centers = computed(() => data.value.centers || [])
const summary = computed(() => data.value.summary || {})
// 只做了整单排产、还没细分到工序的工单数：>0 时图上会多一条灰条，并提示去跑有限产能排产
const orderLevelCount = computed(() => Number(summary.value.orderLevelCount || 0))

const goSchedulePlan = () => {
  router.push('/planning/schedule')
}

const load = async () => {
  loading.value = true
  try {
    data.value = (await api.get('/aps/gantt', {
      from: range.value?.[0],
      to: range.value?.[1]
    })) || { days: [], centers: [], summary: {} }
  } finally {
    loading.value = false
  }
}

const loadSimulation = async () => {
  if (!simForm.productId) {
    ElMessage.warning('请选择要插单的产品')
    return
  }
  simulating.value = true
  try {
    simulation.value = await api.post('/aps/simulate', {
      productId: simForm.productId,
      qty: Number(simForm.qty || 0),
      dueDate: simForm.dueDate || null
    })
  } finally {
    simulating.value = false
  }
}

// ---------- 拖动模拟：指针事件实现，鼠标 / 触摸通用，不依赖 HTML5 原生拖拽 ----------
const dragGhost = ref<{ x: number; y: number; text: string } | null>(null)
let pointerState: { bar: any; startX: number; startY: number; active: boolean } | null = null

/** 根据屏幕坐标找甘特单元格；整单行也返回，落点时再拒绝 */
const cellAt = (x: number, y: number) => {
  const target = document.elementFromPoint(x, y) as HTMLElement | null
  const cell = target?.closest('td.gantt-cell') as HTMLElement | null
  if (!cell) return null
  return {
    centerId: cell.dataset.centerId ? Number(cell.dataset.centerId) : null,
    orderLevel: cell.dataset.orderLevel === 'true',
    date: cell.dataset.date || ''
  }
}

const clearDragState = () => {
  pointerState = null
  dragBar.value = null
  dragGhost.value = null
  isDragging.value = false
  dragOverCell.value = null
  dragOverRow.value = null
  dragOverDate.value = null
  document.body.style.userSelect = ''
  document.body.style.cursor = ''
}

const simulateMoveTo = async (bar: any, targetDate: string, targetCenterId: number | null) => {
  if (!bar?.workOrderId) {
    ElMessage.warning('该条没有工单号，无法模拟')
    return
  }
  moveLoading.value = true
  moveResult.value = null
  try {
    moveResult.value = await api.post('/aps/simulate-move', {
      workOrderId: Number(bar.workOrderId),
      targetDate,
      targetCenterId
    })
    moveDialog.value = true
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '模拟失败')
  } finally {
    moveLoading.value = false
    dragBar.value = null
  }
}

const startPointerDrag = (bar: any, e: PointerEvent) => {
  if (e.button !== 0) return
  e.preventDefault()
  dragBar.value = bar
  const startX = e.clientX
  const startY = e.clientY
  pointerState = { bar, startX, startY, active: false }

  const move = (event: PointerEvent) => {
    if (!pointerState) return
    // 移动超过 4px 才算拖动，避免点一下就被当成拖拽
    if (!pointerState.active) {
      const distance = Math.max(Math.abs(event.clientX - startX), Math.abs(event.clientY - startY))
      if (distance < 4) return
      pointerState.active = true
      isDragging.value = true
      document.body.style.userSelect = 'none'
      document.body.style.cursor = 'grabbing'
    }
    event.preventDefault()
    dragGhost.value = {
      x: event.clientX,
      y: event.clientY,
      text: `${bar.workNo} · ${bar.operationName}`
    }
    const cell = cellAt(event.clientX, event.clientY)
    if (!cell) {
      dragOverCell.value = null
      dragOverRow.value = null
      dragOverDate.value = null
      return
    }
    dragOverRow.value = cell.orderLevel ? null : cell.centerId
    dragOverDate.value = cell.date
    dragOverCell.value = cell.orderLevel ? null : `${cell.centerId || 'ORDER'}-${cell.date}`
  }

  const finish = async (event: PointerEvent) => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', finish)
    window.removeEventListener('pointercancel', finish)
    const active = pointerState?.active
    const cell = cellAt(event.clientX, event.clientY)
    clearDragState()
    if (!active || !cell) return
    if (cell.orderLevel) {
      ElMessage.info('整单行不能作为落点，请拖到具体工作中心')
      return
    }
    await simulateMoveTo(bar, cell.date, cell.centerId)
  }

  window.addEventListener('pointermove', move, { passive: false })
  window.addEventListener('pointerup', finish)
  window.addEventListener('pointercancel', finish)
}

const onCellPointerDown = (cell: any, center: any, e: PointerEvent) => {
  const bars = (cell.bars || []).filter((bar: any) => bar.workOrderId)
  if (!bars.length) return
  const target = e.target as HTMLElement | null
  const chip = target?.closest('.bar-chip') as HTMLElement | null
  let bar = bars[0]
  if (chip && !chip.classList.contains('more')) {
    // 点中具体芯片时按顺序对应到该工单；点在格子空白处则默认拖第一条
    const siblings = Array.from(chip.parentElement?.querySelectorAll('.bar-chip:not(.more)') || []) as HTMLElement[]
    const index = siblings.indexOf(chip)
    if (index >= 0 && bars[index]) bar = bars[index]
  }
  startPointerDrag(bar, e)
}

// 浮动拖动提示：正在拖的工单 + 当前目标
const dragTargetCenterName = computed(() => {
  if (dragOverRow.value == null) return null
  const c = centers.value.find((x: any) => Number(x.centerId) === dragOverRow.value)
  return c ? c.name : null
})
const dragInfoText = computed(() => {
  const bar = dragBar.value
  if (!bar) return ''
  const target = dragOverDate.value
    ? `→ ${dragTargetCenterName.value || ''} ${dragOverDate.value}`
    : '（拖到工作中心的某一天上）'
  return `${bar.workNo} · ${bar.operationName} ${target}`
})

const moveFeasible = computed(() => moveResult.value?.summary?.feasible === true)
const moveDelayDays = computed(() => {
  const s = moveResult.value?.summary
  if (!s || !s.originalDate) return null
  const orig = new Date(s.originalDate).getTime()
  const finish = new Date(s.newFinishDate).getTime()
  return Math.round((finish - orig) / 86400000)
})

const cellClass = (rate: any) => {
  const value = Number(rate || 0)
  if (value > 100) return 'cell-over'
  if (value >= 80) return 'cell-high'
  if (value > 0) return 'cell-busy'
  return 'cell-idle'
}

const dayLabel = (day: any) => {
  const parts = String(day.date || '').split('-')
  return parts.length === 3 ? `${parts[1]}-${parts[2]}` : day.date
}

const delayText = computed(() => {
  const delay = simulation.value?.summary?.dueDelayDays
  if (delay === null || delay === undefined) return null
  return Number(delay)
})

onMounted(async () => {
  await load()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">排产甘特与插单模拟</h2>
        <p class="page-sub">
          按工作中心看每天的计划工时与负荷率；插单模拟按工艺路线逐道消耗剩余产能，估算最可能的完成日。
          <b>按住任意有色格子拖到其他工作中心或日期，松手即弹出移单影响。</b>
        </p>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :clearable="false"
          style="width: 250px"
        />
        <el-button type="primary" :loading="loading" @click="load">刷新甘特</el-button>
        <div class="spacer"></div>
        <span class="toolbar-tip">
          {{ summary.centers ?? 0 }} 个工作中心 · {{ summary.operations ?? 0 }} 道工序 ·
          超负荷格 {{ summary.overloadedCells ?? 0 }} 个
        </span>
      </div>

      <el-alert
        v-if="orderLevelCount > 0"
        class="order-level-tip"
        type="warning"
        :closable="false"
        show-icon
        :title="`有 ${orderLevelCount} 张工单只做了整单排产（下方灰条），还没细分到工序，看不到按工作中心的占用`"
      >
        <div class="order-level-tip-body">
          <span>想做工序级精细排产，去「排产计划」跑一次有限产能排产即可。</span>
          <el-button size="small" type="warning" plain @click="goSchedulePlan">去排产计划</el-button>
        </div>
      </el-alert>

      <div v-loading="loading" class="table-wrap gantt-wrap" :class="{ 'drag-active': isDragging }">
        <div v-if="isDragging" class="drag-float-tip">
          <span class="drag-float-icon">↔</span>
          <span class="drag-float-text">{{ dragInfoText }}</span>
        </div>
        <div
          v-if="dragGhost"
          class="drag-ghost"
          :style="{ left: `${dragGhost.x}px`, top: `${dragGhost.y}px` }"
        >
          {{ dragGhost.text }}
        </div>
        <table class="gantt">
          <thead>
            <tr>
              <th class="gantt-head">工作中心</th>
              <th v-for="day in days" :key="day.date" :class="{ today: day.today }">
                <div class="day-num">{{ dayLabel(day) }}</div>
                <div class="day-week">{{ ['一', '二', '三', '四', '五', '六', '日'][day.weekday - 1] }}</div>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(center, rowIndex) in centers"
              :key="rowIndex"
              :class="[
                { 'order-level-row': center.orderLevel },
                { 'row-drag-over': !center.orderLevel && dragOverRow === Number(center.centerId) },
                { 'no-drop-row': center.orderLevel && isDragging }
              ]"
            >
              <td class="gantt-name">
                <div>{{ center.name }}</div>
                <div v-if="center.orderLevel" class="sub">按工单整单落一天 · 未细分工序</div>
                <div v-else class="sub mono">{{ center.code }} · {{ center.dailyCapacity }} 分/天</div>
              </td>
              <td
                v-for="cell in center.cells"
                :key="cell.date"
                :class="[
                  'gantt-cell',
                  center.orderLevel ? 'cell-order-level' : cellClass(cell.rate),
                  {
                    'drop-target': dragOverCell === `${center.centerId || 'ORDER'}-${cell.date}`,
                    'valid-zone': isDragging && !center.orderLevel,
                    'no-drop-zone': isDragging && center.orderLevel
                  }
                ]"
                :data-center-id="center.centerId || ''"
                :data-order-level="center.orderLevel ? 'true' : 'false'"
                :data-date="cell.date"
                @pointerdown="onCellPointerDown(cell, center, $event)"
              >
                <div v-if="Number(cell.minutes) > 0" class="cell-body">
                  <div v-if="center.orderLevel" class="cell-rate">整单</div>
                  <div v-else class="cell-rate">{{ cell.rate }}%</div>
                  <div class="cell-min">{{ cell.minutes }} 分</div>
                  <div class="cell-bars">
                    <span
                      v-for="(bar, index) in cell.bars.slice(0, 3)"
                      :key="index"
                      :class="['bar-chip', { 'is-dragging': dragBar && bar.workOrderId === dragBar.workOrderId && bar.seq === dragBar.seq }]"
                      :title="`${bar.workNo} · ${bar.operationName} · 计划 ${bar.planQty}（按住拖到其他日期可模拟插单）`"
                    >
                      {{ bar.workNo.slice(-4) }}
                    </span>
                    <span v-if="cell.bars.length > 3" class="bar-chip more">+{{ cell.bars.length - 3 }}</span>
                  </div>
                </div>
                <span v-else class="cell-empty">-</span>
              </td>
            </tr>
            <tr v-if="!centers.length">
              <td :colspan="days.length + 1" class="empty-row">还没有启用工作中心</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="legend">
        <span class="dot idle"></span>空闲
        <span class="dot busy"></span>已排产
        <span class="dot high"></span>接近满负荷（≥80%）
        <span class="dot over"></span>超负荷（>100%）
        <span class="dot order"></span>整单排产（未到工序级）
      </div>
    </div>

    <div class="panel simulate-panel">
      <div class="card-head">
        <div class="card-title">插单影响模拟</div>
        <span class="toolbar-tip">只算不写库，确认可行再到「排产计划」里正式排</span>
      </div>
      <div class="filter-bar">
        <ReferenceSelect v-model="simForm.productId" url="/options/products"
                         placeholder="输入物料编码或名称搜索" style="width: 240px" />
        <el-input-number v-model="simForm.qty" :min="1" :precision="0" style="width: 140px" />
        <el-date-picker
          v-model="simForm.dueDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="客户交期（选填）"
          style="width: 170px"
        />
        <el-button type="primary" :loading="simulating" @click="loadSimulation">模拟</el-button>
      </div>

      <div v-if="simulation" class="simulate-body">
        <div class="kpi-grid kpi-4">
          <div class="kpi-card">
            <div class="kpi-label">预计完成</div>
            <div class="kpi-value small">{{ simulation.summary.finishDate }}</div>
            <div class="kpi-foot">从今天起按剩余产能推</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">总工时</div>
            <div class="kpi-value">{{ fmtQty(simulation.summary.totalMinutes) }}<small>分</small></div>
            <div class="kpi-foot">{{ simulation.summary.productCode }} × {{ fmtQty(simulation.summary.qty) }}</div>
          </div>
          <div class="kpi-card" :class="{ 'kpi-alert': delayText !== null && delayText > 0 }">
            <div class="kpi-label">对交期</div>
            <div class="kpi-value small">
              <template v-if="delayText === null">未填交期</template>
              <template v-else-if="delayText > 0">晚 {{ delayText }} 天</template>
              <template v-else>可满足</template>
            </div>
            <div class="kpi-foot">交期 {{ simulation.summary.dueDate || '-' }}</div>
          </div>
          <div class="kpi-card" :class="{ 'kpi-alert': Number(simulation.summary.affectedOrders || 0) > 0 }">
            <div class="kpi-label">受影响工单</div>
            <div class="kpi-value">{{ simulation.summary.affectedOrders ?? 0 }}<small>张</small></div>
            <div class="kpi-foot">同工作中心、计划日更早</div>
          </div>
        </div>

        <div class="table-wrap">
          <el-table :data="simulation.steps || []" size="small" empty-text="没有工序数据">
            <el-table-column prop="seq" label="序" width="60" />
            <el-table-column prop="processName" label="工序" min-width="140" />
            <el-table-column prop="centerName" label="工作中心" min-width="150" />
            <el-table-column label="工时" width="100" align="right">
              <template #default="{ row }"><span class="num">{{ fmtQty(row.minutes) }} 分</span></template>
            </el-table-column>
            <el-table-column label="完成日" width="120">
              <template #default="{ row }"><span class="mono">{{ row.finishDate }}</span></template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="(simulation.affected || []).length" class="section-head">
          <span>可能被挤压的工单</span>
          <span class="muted">同工作中心且计划日早于预计完成日</span>
        </div>
        <div v-if="(simulation.affected || []).length" class="table-wrap">
          <el-table :data="simulation.affected" size="small" max-height="260">
            <el-table-column prop="workNo" label="工单号" width="150">
              <template #default="{ row }"><span class="mono">{{ row.workNo }}</span></template>
            </el-table-column>
            <el-table-column label="产品" min-width="160">
              <template #default="{ row }">
                <div>{{ row.productName }}</div>
                <div class="sub mono">{{ row.productCode }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="planDate" label="计划 / 交期" width="130">
              <template #default="{ row }"><span class="mono">{{ row.planDate || '-' }}</span></template>
            </el-table-column>
            <el-table-column prop="deliveryDate" label="订单交期" width="130">
              <template #default="{ row }"><span class="mono muted">{{ row.deliveryDate || '-' }}</span></template>
            </el-table-column>
          </el-table>
        </div>
      </div>
      <div v-else class="muted note">选产品、填数量后点「模拟」，会按工艺路线逐道消耗剩余产能算出完成日。</div>
    </div>

    <el-dialog
      v-model="moveDialog"
      title="拖动模拟：移单影响"
      width="720px"
      :close-on-click-modal="false"
    >
      <div v-if="moveResult" class="move-result">
        <div class="kpi-grid kpi-4">
          <div class="kpi-card" :class="{ 'kpi-ok': moveFeasible, 'kpi-alert': !moveFeasible }">
            <div class="kpi-label">是否装得下</div>
            <div class="kpi-value small">{{ moveFeasible ? '可行' : '有冲突' }}</div>
            <div class="kpi-foot">{{ moveResult.summary.conflictCount }} 个产能冲突格</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">新预计完成</div>
            <div class="kpi-value small">{{ moveResult.summary.newFinishDate }}</div>
            <div class="kpi-foot">
              原排 {{ moveResult.summary.originalDate || '-' }}
              <template v-if="moveDelayDays !== null"> · 跨 {{ moveDelayDays }} 天</template>
            </div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">总工时</div>
            <div class="kpi-value">{{ fmtQty(moveResult.summary.totalMinutes) }}<small>分</small></div>
            <div class="kpi-foot">{{ moveResult.summary.workNo }} · {{ moveResult.summary.productCode }}</div>
          </div>
          <div class="kpi-card" :class="{ 'kpi-alert': Number(moveResult.summary.affectedOrders || 0) > 0 }">
            <div class="kpi-label">受影响工单</div>
            <div class="kpi-value">{{ moveResult.summary.affectedOrders ?? 0 }}<small>张</small></div>
            <div class="kpi-foot">同工作中心、计划日更早</div>
          </div>
        </div>

        <div class="section-head">
          <span>各工序新排程</span>
          <span class="muted">从目标日起逐道消耗剩余产能</span>
        </div>
        <el-table :data="moveResult.steps || []" size="small" empty-text="没有工序数据">
          <el-table-column prop="seq" label="序" width="60" />
          <el-table-column prop="processName" label="工序" min-width="140" />
          <el-table-column prop="centerName" label="工作中心" min-width="150" />
          <el-table-column label="工时" width="100" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.minutes) }} 分</span></template>
          </el-table-column>
          <el-table-column label="完成日" width="120">
            <template #default="{ row }"><span class="mono">{{ row.finishDate }}</span></template>
          </el-table-column>
        </el-table>

        <div v-if="(moveResult.conflicts || []).length" class="section-head">
          <span>产能冲突明细</span>
          <span class="muted">这些天已超负荷，本工单只能往后挤</span>
        </div>
        <div v-if="(moveResult.conflicts || []).length" class="table-wrap">
          <el-table :data="moveResult.conflicts" size="small" max-height="180">
            <el-table-column prop="centerName" label="工作中心" min-width="140" />
            <el-table-column prop="date" label="日期" width="120">
              <template #default="{ row }"><span class="mono">{{ row.date }}</span></template>
            </el-table-column>
            <el-table-column label="已用/产能" width="140" align="right">
              <template #default="{ row }"><span class="num">{{ row.used }} / {{ row.capacity }} 分</span></template>
            </el-table-column>
            <el-table-column label="超产能" width="120" align="right">
              <template #default="{ row }"><span class="num danger">+{{ row.overflow }} 分</span></template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="(moveResult.affected || []).length" class="section-head">
          <span>可能被挤压的工单</span>
          <span class="muted">同工作中心且计划日早于新完成日</span>
        </div>
        <div v-if="(moveResult.affected || []).length" class="table-wrap">
          <el-table :data="moveResult.affected" size="small" max-height="200">
            <el-table-column prop="workNo" label="工单号" width="150">
              <template #default="{ row }"><span class="mono">{{ row.workNo }}</span></template>
            </el-table-column>
            <el-table-column label="产品" min-width="160">
              <template #default="{ row }">
                <div>{{ row.productName }}</div>
                <div class="sub mono">{{ row.productCode }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="planDate" label="计划 / 交期" width="130">
              <template #default="{ row }"><span class="mono">{{ row.planDate || '-' }}</span></template>
            </el-table-column>
            <el-table-column prop="deliveryDate" label="订单交期" width="130">
              <template #default="{ row }"><span class="mono muted">{{ row.deliveryDate || '-' }}</span></template>
            </el-table-column>
          </el-table>
        </div>

        <el-alert
          class="move-tip"
          :type="moveFeasible ? 'success' : 'warning'"
          :closable="false"
          show-icon
          :title="moveFeasible
            ? '模拟可行：目标日产能够，确认后可到「排产计划」正式调整'
            : '目标日已超负荷：本工单会顺延，可能挤压其他工单'"
        />
      </div>
      <template #footer>
        <el-button @click="moveDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.gantt-wrap {
  overflow-x: auto;
  padding-bottom: 4px;
}

.gantt {
  border-collapse: separate;
  border-spacing: 2px;
  min-width: 100%;
}

.gantt th {
  font-size: 11px;
  font-weight: 500;
  color: var(--muted);
  padding: 4px 2px;
  text-align: center;
  white-space: nowrap;
}

.gantt th.today {
  color: #2456d8;
  font-weight: 700;
}

.gantt-head {
  text-align: left !important;
  min-width: 150px;
}

.day-num {
  font-family: Bahnschrift, monospace;
}

.day-week {
  font-size: 10px;
}

.gantt-name {
  padding: 6px 8px;
  border-radius: 6px;
  background: #f8fafc;
  font-size: 12px;
  min-width: 150px;
}

.gantt-cell {
  min-width: 76px;
  height: 62px;
  border-radius: 6px;
  padding: 4px 5px;
  vertical-align: top;
  font-size: 10px;
}

.cell-idle {
  background: #f8fafc;
  color: var(--muted);
}

.cell-busy {
  background: #eef3ff;
}

.cell-high {
  background: #fff7e6;
}

.cell-over {
  background: #fef3f2;
}

/* 整单排产：没有落到具体工作中心，用灰底与虚线边框区别于工序级负荷 */
.cell-order-level {
  background: repeating-linear-gradient(135deg, #f6f7f9, #f6f7f9 6px, #eef0f3 6px, #eef0f3 12px);
  border: 1px dashed #cbd5e1;
}

.order-level-row .gantt-name {
  background: #f6f7f9;
  border: 1px dashed #cbd5e1;
}

.order-level-tip {
  margin-bottom: 10px;
}

.order-level-tip-body {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.cell-rate {
  font-weight: 700;
  font-size: 11px;
}

.cell-min {
  color: var(--muted);
}

.cell-bars {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  margin-top: 2px;
}

.bar-chip {
  background: #2456d8;
  color: #fff;
  border-radius: 4px;
  padding: 2px 6px;
  min-width: 28px;
  font-size: 10px;
  line-height: 1.2;
  text-align: center;
  font-family: Bahnschrift, monospace;
  cursor: grab;
  user-select: none;
  /* 触摸屏上禁止把拖动识别成页面滚动 */
  touch-action: none;
  transition: opacity 0.15s ease, transform 0.15s ease, box-shadow 0.15s ease;
}

.bar-chip:hover {
  box-shadow: 0 1px 4px rgba(36, 86, 216, 0.45);
  transform: translateY(-1px);
}

.bar-chip:active {
  cursor: grabbing;
}

/* 正在被拖动的源条：变淡 + 虚线边框，表示"已拿起" */
.bar-chip.is-dragging {
  opacity: 0.35;
  outline: 1px dashed #2456d8;
  outline-offset: 1px;
}

/* 跟手浮层：拖动时显示工单号与工序，pointer-events 必须关掉，否则会挡住落点判定 */
.drag-ghost {
  position: fixed;
  z-index: 3000;
  transform: translate(14px, 14px);
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(36, 86, 216, 0.95);
  color: #fff;
  font-size: 12px;
  font-family: Bahnschrift, monospace;
  box-shadow: 0 8px 22px rgba(15, 23, 42, 0.35);
  pointer-events: none;
  white-space: nowrap;
}

/* 拖动中的甘特容器：给所有有效落点一个微弱的蓝色呼吸提示 */
.gantt-wrap.drag-active .gantt-cell.valid-zone {
  box-shadow: inset 0 0 0 1px rgba(36, 86, 216, 0.18);
}

.gantt-wrap.drag-active .gantt-cell.no-drop-zone {
  cursor: not-allowed;
  opacity: 0.55;
}

/* 目标单元格：更强的高亮 + 脉冲动画 */
.gantt-cell.drop-target {
  outline: 2px solid #2456d8;
  outline-offset: -2px;
  background: #eaf0ff !important;
  animation: drop-pulse 1s ease-in-out infinite;
}

@keyframes drop-pulse {
  0%, 100% { box-shadow: inset 0 0 0 0 rgba(36, 86, 216, 0.35); }
  50% { box-shadow: inset 0 0 10px 2px rgba(36, 86, 216, 0.25); }
}

/* 整行高亮：悬停的目标工作中心整行背景变色，左侧名称也跟着高亮 */
tr.row-drag-over .gantt-name {
  background: #eaf0ff !important;
  color: #2456d8;
  font-weight: 600;
}

tr.row-drag-over .gantt-cell {
  background: #f4f7ff;
}

tr.no-drop-row .gantt-name,
tr.no-drop-row .gantt-cell {
  cursor: not-allowed;
}

/* 浮动拖动提示条 */
.drag-float-tip {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  margin-bottom: 6px;
  background: linear-gradient(90deg, #2456d8, #4f7ce8);
  color: #fff;
  border-radius: 6px;
  font-size: 12px;
  box-shadow: 0 2px 8px rgba(36, 86, 216, 0.35);
}

.drag-float-icon {
  font-size: 14px;
  font-weight: 700;
}

.drag-float-text {
  font-family: Bahnschrift, monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bar-chip.more {
  background: #94a3b8;
  cursor: default;
}

.cell-empty {
  color: #cbd5e1;
}

.empty-row {
  text-align: center;
  color: var(--muted);
  padding: 20px;
}

.sub {
  font-size: 10px;
  color: var(--muted);
}

.legend {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--muted);
  padding-top: 8px;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  display: inline-block;
  margin-left: 8px;
}

.dot.idle {
  background: #f8fafc;
  border: 1px solid var(--line);
}

.dot.busy {
  background: #eef3ff;
}

.dot.high {
  background: #fff7e6;
}

.dot.over {
  background: #fef3f2;
}

.dot.order {
  background: repeating-linear-gradient(135deg, #f6f7f9, #f6f7f9 3px, #cbd5e1 3px, #cbd5e1 6px);
  border: 1px dashed #cbd5e1;
}

.simulate-panel {
  margin-top: 14px;
}

.kpi-grid.kpi-4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 12px 0;
}

@media (max-width: 1100px) {
  .kpi-grid.kpi-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.kpi-value.small {
  font-size: 18px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.section-head .muted {
  font-size: 12px;
  font-weight: 400;
}

.note {
  font-size: 12px;
  line-height: 1.8;
  padding: 8px 2px;
}
</style>
