<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api'

const loading = ref(false)
const jobs = ref<any[]>([])
const logs = ref<any[]>([])
const logsLoading = ref(false)
const runCode = ref('')
const logFilter = ref('')

const statusMap: Record<string, { label: string; cls: string }> = {
  SUCCESS: { label: '成功', cls: 'success' },
  FAILED: { label: '失败', cls: 'danger' },
  SKIPPED: { label: '已跳过', cls: 'warning' }
}

const statusOf = (value: any) => statusMap[String(value || '')] || null

const triggerText = (value: any) => (value === 'SCHEDULED' ? '定时触发' : value === 'MANUAL' ? '手工触发' : '-')

const load = async () => {
  loading.value = true
  try {
    jobs.value = (await api.get('/system/jobs')) || []
  } finally {
    loading.value = false
  }
}

const loadLogs = async () => {
  logsLoading.value = true
  try {
    logs.value = (await api.get('/system/jobs/logs', {
      code: logFilter.value || undefined,
      limit: 100
    })) || []
  } finally {
    logsLoading.value = false
  }
}

const run = async (job: any) => {
  runCode.value = job.code
  try {
    const result = await api.post(`/system/jobs/${job.code}/run`)
    if (result?.status === 'SUCCESS') {
      ElMessage.success(`${job.name} 执行完成，处理 ${result.affected ?? 0} 条，耗时 ${result.durationMs} ms`)
    } else if (result?.status === 'SKIPPED') {
      ElMessage.warning(result.message || '上一次执行还没结束')
    } else {
      ElMessage.error(`${job.name} 执行失败：${result?.message || '未知原因'}`)
    }
    await Promise.all([load(), loadLogs()])
  } finally {
    runCode.value = ''
  }
}

const durationText = (value: any) => {
  const ms = Number(value || 0)
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

const filterLogs = (code: string) => {
  logFilter.value = logFilter.value === code ? '' : code
  loadLogs()
}

onMounted(async () => {
  await load()
  await loadLogs()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">定时任务</h2>
        <p class="page-sub">
          交期预警、缺料巡检、成本补算与通知清理按计划自动执行，也可以在这里手工跑一次
        </p>
      </div>
    </div>

    <div v-loading="loading" class="job-grid">
      <div v-for="job in jobs" :key="job.code" class="panel job-card">
        <div class="job-head">
          <div>
            <div class="job-name">{{ job.name }}</div>
            <div class="job-code mono">{{ job.code }}</div>
          </div>
          <span v-if="statusOf(job.status)" class="status-tag" :class="statusOf(job.status)?.cls">
            <i></i>{{ statusOf(job.status)?.label }}
          </span>
          <span v-else class="status-tag info"><i></i>未执行</span>
        </div>
        <div class="job-desc">{{ job.description }}</div>
        <div class="job-meta">
          <span>计划：{{ job.cron }}</span>
          <span v-if="job.lastRunAt">最近：{{ job.lastRunAt }}</span>
          <span v-else>最近：暂无记录</span>
        </div>
        <div class="job-meta">
          <span v-if="job.lastAffected !== undefined && job.lastAffected !== null">
            处理 {{ job.lastAffected }} 条 · {{ durationText(job.lastDurationMs) }} ·
            {{ triggerText(job.lastTrigger) }}
          </span>
          <span v-else class="muted">还没有执行过</span>
        </div>
        <div v-if="job.lastMessage" class="job-error">{{ job.lastMessage }}</div>
        <div class="job-actions">
          <el-button
            type="primary"
            size="small"
            :loading="runCode === job.code"
            :disabled="Boolean(job.running)"
            @click="run(job)"
          >
            立即执行
          </el-button>
          <el-button link size="small" @click="filterLogs(job.code)">看记录</el-button>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="card-head">
        <div class="card-title">执行记录</div>
        <div class="head-actions-inline">
          <span v-if="logFilter" class="filter-chip" @click="filterLogs(logFilter)">
            {{ logFilter }} ✕
          </span>
          <el-button size="small" :loading="logsLoading" @click="loadLogs">刷新</el-button>
        </div>
      </div>
      <div v-loading="logsLoading" class="table-wrap">
        <el-table :data="logs" size="small" empty-text="还没有执行记录">
          <el-table-column prop="startedAt" label="开始时间" width="170">
            <template #default="{ row }"><span class="mono">{{ row.startedAt }}</span></template>
          </el-table-column>
          <el-table-column prop="jobName" label="任务" min-width="150" />
          <el-table-column label="触发方式" width="110">
            <template #default="{ row }">
              <span class="muted">{{ triggerText(row.triggerType) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <span v-if="statusOf(row.status)" class="status-tag" :class="statusOf(row.status)?.cls">
                <i></i>{{ statusOf(row.status)?.label }}
              </span>
              <span v-else class="muted">{{ row.status }}</span>
            </template>
          </el-table-column>
          <el-table-column label="处理" width="90" align="right">
            <template #default="{ row }"><span class="num">{{ row.affected ?? 0 }}</span></template>
          </el-table-column>
          <el-table-column label="耗时" width="100" align="right">
            <template #default="{ row }"><span class="num">{{ durationText(row.durationMs) }}</span></template>
          </el-table-column>
          <el-table-column label="执行人" width="120">
            <template #default="{ row }"><span class="mono muted">{{ row.operator || '-' }}</span></template>
          </el-table-column>
          <el-table-column label="说明" min-width="220">
            <template #default="{ row }">
              <span :class="row.status === 'FAILED' ? 'danger-text' : 'muted'">{{ row.message || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.job-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}

.job-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.job-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.job-name {
  font-size: 14px;
  font-weight: 600;
}

.job-code {
  font-size: 11px;
  color: var(--muted);
}

.job-desc {
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-2);
}

.job-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: var(--muted);
}

.job-error {
  font-size: 12px;
  color: var(--danger);
  background: #fef3f2;
  border-radius: 6px;
  padding: 6px 8px;
  line-height: 1.6;
}

.job-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: auto;
  padding-top: 6px;
}

.head-actions-inline {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filter-chip {
  font-size: 12px;
  color: var(--primary);
  cursor: pointer;
  background: var(--primary-soft);
  border-radius: 10px;
  padding: 2px 8px;
}
</style>
