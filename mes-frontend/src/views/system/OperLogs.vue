<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../../api'
import DataTable from '../../components/DataTable.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import { useTableView } from '../../composables/useTableView'

const loading = ref(false)
const rows = ref<any[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const options = ref<any>({ modules: [], operators: [], total: 0, failed: 0, latest: '' })
const detail = ref<any>(null)
const drawer = ref(false)

const filters = reactive({
  module: '',
  operator: '',
  keyword: '',
  success: '',
  range: [] as string[]
})

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'system.logs',
  columns: [
    { key: 'createdAt', label: '时间', width: 160 },
    { key: 'module', label: '模块', width: 105 },
    { key: 'action', label: '动作', width: 80 },
    { key: 'summary', label: '摘要', width: 280, flex: true, ellipsis: true },
    { key: 'bizNo', label: '单据', width: 150, ellipsis: true },
    { key: 'operator', label: '操作人', width: 150 },
    { key: 'ip', label: 'IP', width: 120 },
    { key: 'result', label: '结果', width: 110, prop: '' }
  ],
  getFilters: () => ({
    module: filters.module,
    operator: filters.operator,
    keyword: filters.keyword,
    success: filters.success,
    range: filters.range
  }),
  setFilters: (payload) => {
    filters.module = payload.module || ''
    filters.operator = payload.operator || ''
    filters.keyword = payload.keyword || ''
    filters.success = payload.success || ''
    filters.range = Array.isArray(payload.range) ? payload.range : []
  }
})

const reload = () => {
  page.value = 1
  load()
}

const changePage = (value: number) => {
  page.value = value
  load()
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/system/logs', {
      module: filters.module || undefined,
      operator: filters.operator || undefined,
      keyword: filters.keyword || undefined,
      success: filters.success || undefined,
      from: filters.range?.[0] || undefined,
      to: filters.range?.[1] || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = data?.records || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

const loadOptions = async () => {
  try {
    options.value = await api.get('/system/logs/options')
  } catch {
    options.value = { modules: [], operators: [] }
  }
}

const open = (row: any) => {
  detail.value = row
  drawer.value = true
}

onMounted(async () => {
  await table.load()
  load()
  loadOptions()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">操作日志</h2>
        <p class="page-sub">
          所有写操作自动留痕：谁、什么时候、对哪张单、做了什么，成功失败都记；查询默认不记，附件下载单独记一条
        </p>
      </div>
      <el-button plain @click="load(); loadOptions()">刷新</el-button>
    </div>

    <div class="log-strip">
      <div class="log-item">
        <span>累计记录</span>
        <b>{{ options.total || 0 }}</b>
      </div>
      <div class="log-item">
        <span>失败次数</span>
        <b class="danger-text">{{ options.failed || 0 }}</b>
      </div>
      <div class="log-item">
        <span>最近一条</span>
        <b class="small">{{ options.latest || '-' }}</b>
      </div>
      <div class="log-item">
        <span>当前筛选</span>
        <b class="small">{{ rows.length }} 条</b>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-select v-model="filters.module" placeholder="全部模块" clearable style="width: 140px" @change="reload">
          <el-option v-for="item in options.modules || []" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select
          v-model="filters.operator"
          placeholder="全部操作人"
          clearable
          filterable
          allow-create
          default-first-option
          style="width: 150px"
          @change="reload"
        >
          <el-option v-for="item in options.operators || []" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select v-model="filters.success" placeholder="全部结果" clearable style="width: 120px" @change="reload">
          <el-option label="成功" value="true" />
          <el-option label="失败" value="false" />
        </el-select>
        <el-input
          v-model="filters.keyword"
          placeholder="摘要 / 单号 / 路径"
          clearable
          style="width: 220px"
          @keyup.enter="reload"
        />
        <el-date-picker
          v-model="filters.range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 240px"
        />
        <el-button type="primary" plain @click="reload">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/system/logs" export-name="操作日志" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 条日志</span>
      </div>

      <div class="table-wrap">
        <DataTable
          :table="table"
          :data="rows"
          :loading="loading"
          :total="total"
          :page="page"
          :size="size"
          @page-change="changePage"
          @row-click="open"
        >
          <template #cell-createdAt="{ row }"><span class="mono">{{ row.createdAt }}</span></template>
          <template #cell-bizNo="{ row }"><span class="mono muted">{{ row.bizNo || '-' }}</span></template>
          <template #cell-operator="{ row }">
            <div>{{ row.operator || '-' }}</div>
            <div class="muted" style="font-size: 11px">{{ row.operatorRole }}</div>
          </template>
          <template #cell-ip="{ row }"><span class="mono muted small">{{ row.ip }}</span></template>
          <template #cell-result="{ row }">
            <span class="status-tag" :class="row.success ? 'success' : 'danger'">
              <i></i>{{ row.success ? '成功' : '失败' }}
            </span>
            <span class="muted" style="font-size: 11px"> {{ row.durationMs }}ms</span>
          </template>
        </DataTable>
      </div>
    </div>

    <el-drawer v-model="drawer" size="460px" :title="detail ? detail.module + ' · ' + detail.action : '日志详情'">
      <div v-if="detail" class="log-detail">
        <div class="detail-row"><span>时间</span><b class="mono">{{ detail.createdAt }}</b></div>
        <div class="detail-row"><span>摘要</span><b>{{ detail.summary }}</b></div>
        <div class="detail-row"><span>操作人</span><b>{{ detail.operator }}（{{ detail.operatorRole }}）</b></div>
        <div class="detail-row"><span>请求</span><b class="mono small">{{ detail.httpMethod }} {{ detail.requestPath }}</b></div>
        <div class="detail-row"><span>来源 IP</span><b class="mono small">{{ detail.ip }}</b></div>
        <div class="detail-row"><span>耗时</span><b>{{ detail.durationMs }} ms</b></div>
        <div class="detail-row"><span>结果</span>
          <b :class="detail.success ? '' : 'danger-text'">{{ detail.success ? '成功' : '失败' }}</b>
        </div>
        <div v-if="detail.bizNo" class="detail-row"><span>业务单号</span><b class="mono">{{ detail.bizNo }}</b></div>
        <div v-if="detail.detail" class="detail-row"><span>附加信息</span><b class="small">{{ detail.detail }}</b></div>
        <div v-if="detail.errorMsg" class="detail-row"><span>失败原因</span><b class="danger-text small">{{ detail.errorMsg }}</b></div>
        <div v-if="detail.userAgent" class="detail-row"><span>客户端</span><b class="small muted">{{ detail.userAgent }}</b></div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.log-strip {
  display: flex;
  align-items: flex-start;
  gap: 40px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 15px 20px;
  margin-bottom: 14px;
  box-shadow: var(--shadow);
  flex-wrap: wrap;
}

.log-item span {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.log-item b {
  display: block;
  margin-top: 6px;
  font-size: 20px;
  font-family: Bahnschrift, monospace;
}

.log-item b.small {
  font-size: 13px;
  font-family: inherit;
  font-weight: 500;
  color: var(--ink-3);
}

.log-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.detail-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-bottom: 10px;
  border-bottom: 1px dashed var(--line);
}

.detail-row span {
  font-size: 12px;
  color: var(--muted);
}

.detail-row b {
  font-size: 13px;
  color: var(--ink);
  font-weight: 500;
  line-height: 1.6;
  word-break: break-all;
}

.detail-row b.small {
  font-size: 12px;
  font-weight: 400;
  color: var(--ink-3);
}
</style>
