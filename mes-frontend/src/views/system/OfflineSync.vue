<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../../api'

const loading = ref(false)
const rows = ref<any[]>([])
const status = ref<any>({})
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const load = async () => {
  loading.value = true
  try {
    const [data, info] = await Promise.all([
      api.get('/offline/recent', {
        keyword: keyword.value.trim() || undefined,
        page: page.value,
        size: size.value
      }),
      api.get('/offline/status')
    ])
    rows.value = data?.records || []
    total.value = data?.total || 0
    status.value = info || {}
  } finally {
    loading.value = false
  }
}

const reload = () => {
  page.value = 1
  load()
}

const changePage = (value: number) => {
  page.value = value
  load()
}

const pathText = (value: any) => String(value || '').replace('/api', '')

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">离线重传记录</h2>
        <p class="page-sub">
          移动端断网时的报工与检验会先存本机，联网后按幂等键重传；这里能确认「那条到底传上来没有」
        </p>
      </div>
      <div class="head-actions">
        <el-input
          v-model="keyword"
          placeholder="按幂等键 / 账号 / 路径搜"
          clearable
          style="width: 240px"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="panel stat-row">
      <div class="stat">
        <span class="stat-num">{{ status.enabled ? '已开启' : '已关闭' }}</span>
        <span class="stat-label">离线队列</span>
      </div>
      <div class="stat">
        <span class="stat-num">{{ status.receivedKeys ?? 0 }}</span>
        <span class="stat-label">收到过的幂等键</span>
      </div>
      <div class="stat">
        <span class="stat-num">{{ status.retentionDays ?? '-' }} 天</span>
        <span class="stat-label">记录保留</span>
      </div>
      <div class="stat">
        <span class="stat-num">{{ status.serverTime || '-' }}</span>
        <span class="stat-label">服务端时间</span>
      </div>
    </div>

    <div class="panel">
      <div class="table-wrap">
        <el-table v-loading="loading" :data="rows" size="small" empty-text="还没有重传记录">
          <el-table-column prop="createdAt" label="首次执行时间" width="175">
            <template #default="{ row }"><span class="mono">{{ row.createdAt }}</span></template>
          </el-table-column>
          <el-table-column prop="userName" label="账号" width="130" />
          <el-table-column label="方法" width="80">
            <template #default="{ row }"><span class="mono">{{ row.method }}</span></template>
          </el-table-column>
          <el-table-column label="接口" min-width="220">
            <template #default="{ row }"><span class="mono">{{ pathText(row.path) }}</span></template>
          </el-table-column>
          <el-table-column prop="statusCode" label="状态码" width="90" />
          <el-table-column label="幂等键" min-width="200">
            <template #default="{ row }"><span class="mono muted">{{ row.key }}</span></template>
          </el-table-column>
        </el-table>
      </div>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="changePage"
          @size-change="load"
        />
      </div>

      <p class="tip">
        同一个幂等键只会真正执行一次，重复到达直接返回首次结果；超过保留天数的记录由「离线重传记录清理」任务删除。
      </p>
    </div>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  padding: 20px 24px;
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-num {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
}

.stat-label {
  font-size: 12px;
  color: #94a3b8;
}

.tip {
  margin: 14px 0 0;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.7;
}
</style>
