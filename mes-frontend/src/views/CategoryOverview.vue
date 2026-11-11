<script setup lang="ts">
/**
 * 分组工作台：点击左侧菜单分组进入，一屏看该业务域的本月指标与预警。
 *
 * 统一口径：
 * - 指标按「月份」统计；
 * - 预警按「预警天数」看即将到期 / 已逾期 / 缺口类事项。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, fmtMoney, fmtQty } from '../api'

const route = useRoute()
const router = useRouter()

const CATEGORIES = [
  { key: 'sales', label: '销售与交付' },
  { key: 'planning', label: '计划与排产' },
  { key: 'production', label: '生产执行' },
  { key: 'purchase', label: '采购与应付' },
  { key: 'warehouse', label: '仓储管理' },
  { key: 'quality', label: '质量与设备' },
  { key: 'report', label: '报表分析' },
  { key: 'base', label: '基础数据' },
  { key: 'system', label: '系统管理' }
]

const category = computed(() => String(route.params.category || 'sales'))
const month = ref(new Date().toISOString().slice(0, 7))
const warningDays = ref(7)
const loading = ref(false)
const data = ref<any>({ kpis: [], alerts: [] })

const load = async () => {
  loading.value = true
  try {
    data.value = await api.get(`/overview/${category.value}`, {
      month: month.value,
      warningDays: warningDays.value
    })
  } finally {
    loading.value = false
  }
}

const switchCategory = (key: string) => {
  if (key === category.value) return
  router.push(`/overview/${key}`)
}

watch(category, load)
onMounted(load)

const kpiText = (kpi: any) => {
  if (kpi.value === null || kpi.value === undefined) return '—'
  if (kpi.unit === '元') return `¥${fmtMoney(kpi.value)}`
  if (kpi.unit === '%') return `${Number(kpi.value).toFixed(1)}%`
  return fmtQty(kpi.value)
}

const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '已提交', APPROVED: '已审批', CONFIRMED: '已确认',
  PARTIAL: '部分完成', RECEIVED: '已收货', COMPLETED: '已完成', CANCELLED: '已取消',
  RELEASED: '已下达', IN_PROGRESS: '生产中', REWORK: '返修中', FINISHED: '已完工',
  CLOSED: '已关闭', READY_TO_SHIP: '待发货', PENDING: '待处理', OPEN: '待处理',
  ACKED: '处理中', RESOLVED: '已解决', PASS: '合格', FAIL: '不合格',
  SENT: '已发出', LOCKED: '已锁定', LOW: '低库存',
  RAW: '原料', SEMI: '半成品', FINISHED_GOODS: '成品'
}

const statusLabel = (value: any) => STATUS_TEXT[String(value || '')] || String(value || '-')

const statusClass = (value: any) => {
  const code = String(value || '')
  if (['CANCELLED', 'FAIL', 'LOCKED', 'LOW', 'OPEN', 'PENDING'].includes(code)) return 'danger'
  if (['FINISHED', 'CLOSED', 'COMPLETED', 'PASS', 'RESOLVED', 'RECEIVED'].includes(code)) return 'success'
  if (['DRAFT', 'SUBMITTED', 'IN_PROGRESS', 'REWORK', 'PARTIAL', 'SENT'].includes(code)) return 'warning'
  return 'info'
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">{{ data.title || '分组工作台' }}</h2>
        <p class="page-sub">
          指标按月份统计，预警按预警天数看即将到期与已逾期事项；点上方分组可切换到其他业务域
        </p>
      </div>
    </div>

    <div class="cat-tabs">
      <button
        v-for="item in CATEGORIES"
        :key="item.key"
        class="cat-tab"
        :class="{ active: category === item.key }"
        @click="switchCategory(item.key)"
      >
        {{ item.label }}
      </button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-date-picker
          v-model="month"
          type="month"
          value-format="YYYY-MM"
          :clearable="false"
          style="width: 150px"
        />
        <span class="muted">预警天数</span>
        <el-input-number v-model="warningDays" :min="1" :max="90" controls-position="right" style="width: 120px" />
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
        <div class="spacer"></div>
        <span class="toolbar-tip">
          {{ data.month || month }} · 预警窗口 {{ data.warningDays || warningDays }} 天
        </span>
      </div>

      <div v-loading="loading">
        <div class="kpi-grid kpi-4">
          <div
            v-for="kpi in data.kpis || []"
            :key="kpi.key"
            class="kpi-card"
            :class="{ 'kpi-alert': kpi.tone === 'alert', 'kpi-ok': kpi.tone === 'ok', 'kpi-warn': kpi.tone === 'warn' }"
          >
            <div class="kpi-label">{{ kpi.label }}</div>
            <div class="kpi-value">
              {{ kpiText(kpi) }}
              <small v-if="kpi.unit && kpi.unit !== '元' && kpi.unit !== '%'">{{ kpi.unit }}</small>
            </div>
            <div class="kpi-foot">{{ kpi.foot || ' ' }}</div>
          </div>
        </div>

        <div v-if="!(data.alerts || []).length" class="alert-empty">
          <el-empty description="当前月份与预警天数下没有预警" />
        </div>

        <div
          v-for="alert in data.alerts || []"
          :key="alert.key"
          class="alert-block"
          :class="alert.level"
        >
          <div class="alert-head">
            <div class="alert-title">
              <span class="alert-dot"></span>
              {{ alert.title }}
              <span class="alert-count">{{ alert.count }}</span>
            </div>
            <span class="alert-hint">{{ alert.hint }}</span>
          </div>
          <el-table :data="alert.items" size="small" max-height="320">
            <el-table-column prop="no" label="单号 / 编码" width="170">
              <template #default="{ row }"><span class="mono">{{ row.no }}</span></template>
            </el-table-column>
            <el-table-column prop="name" label="名称 / 对象" min-width="180" show-overflow-tooltip />
            <el-table-column prop="date" label="日期" width="120" />
            <el-table-column prop="note" label="说明" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <span class="status-tag" :class="statusClass(row.status)">
                  <i></i>{{ statusLabel(row.status) }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cat-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}

.cat-tab {
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: #fff;
  color: var(--ink-2);
  font-size: 12px;
  cursor: pointer;
  transition: 0.16s ease;
}

.cat-tab:hover {
  border-color: #b5c9f5;
  color: var(--primary);
}

.cat-tab.active {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
  font-weight: 600;
}

.alert-block {
  margin-top: 16px;
  border: 1px solid var(--line);
  border-left: 4px solid var(--primary);
  border-radius: 10px;
  padding: 12px 14px;
  background: #fff;
}

.alert-block.danger {
  border-left-color: var(--danger);
}

.alert-block.warning {
  border-left-color: #f79009;
}

.alert-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.alert-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 13px;
}

.alert-block.danger .alert-title {
  color: var(--danger);
}

.alert-block.warning .alert-title {
  color: #b54708;
}

.alert-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
}

.alert-count {
  min-width: 20px;
  padding: 0 7px;
  border-radius: 999px;
  background: #f2f4f7;
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 600;
  text-align: center;
}

.alert-hint {
  font-size: 12px;
  color: var(--muted);
}

.alert-empty {
  padding: 26px 0 8px;
}

.kpi-warn .kpi-value {
  color: #f79009;
}
</style>
