<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, downloadFile } from '../../api'
import DataTable from '../../components/DataTable.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import { useTableView } from '../../composables/useTableView'

const loading = ref(false)
const testing = ref(false)
const rows = ref<any[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const storageInfo = ref<any>({})

const filters = reactive({
  bizType: '',
  storageType: '',
  keyword: '',
  range: [] as string[]
})

const bizOptions = [
  { value: 'DEFECT', label: '不良处置' },
  { value: 'INSPECTION', label: '质量检验' },
  { value: 'WORK_ORDER', label: '生产工单' },
  { value: 'SHIPMENT', label: '发货单' },
  { value: 'RETURN', label: '退货单' }
]

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'system.files',
  columns: [
    { key: 'fileNo', label: '台账号', width: 150 },
    { key: 'fileName', label: '文件名', prop: 'originalName', width: 220, flex: true, ellipsis: true },
    { key: 'sizeText', label: '大小', width: 95, align: 'right' },
    { key: 'storageType', label: '存储', width: 90 },
    { key: 'biz', label: '所属单据', prop: 'bizType', width: 170, flex: true, ellipsis: true },
    { key: 'upload', label: '上传人 / 时间', prop: 'uploadedBy', width: 160 },
    { key: 'sha256', label: '摘要', width: 130, ellipsis: true },
    { key: 'actions', label: '操作', width: 130, fixed: 'right', prop: '' }
  ],
  getFilters: () => ({
    bizType: filters.bizType,
    storageType: filters.storageType,
    keyword: filters.keyword,
    range: filters.range
  }),
  setFilters: (payload) => {
    filters.bizType = payload.bizType || ''
    filters.storageType = payload.storageType || ''
    filters.keyword = payload.keyword || ''
    filters.range = Array.isArray(payload.range) ? payload.range : []
  }
})

const reload = () => {
  page.value = 1
  load()
}

const bizLabel = (value: any) =>
  bizOptions.find((item) => item.value === value)?.label || (value ? String(value) : '-')

const totalSize = computed(() => rows.value.reduce((sum, row) => sum + Number(row.sizeBytes || 0), 0))

const sizeText = (bytes: number) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/files', {
      bizType: filters.bizType || undefined,
      storageType: filters.storageType || undefined,
      keyword: filters.keyword || undefined,
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

const changePage = (value: number) => {
  page.value = value
  load()
}

const loadStorage = async () => {
  try {
    storageInfo.value = await api.get('/files/storage')
  } catch {
    storageInfo.value = {}
  }
}

const testStorage = async () => {
  testing.value = true
  try {
    const result = await api.post('/files/storage/test')
    ElMessage.success(result?.message || '存储可用')
    loadStorage()
  } finally {
    testing.value = false
  }
}

const download = (row: any) => downloadFile(row.id, row.originalName)

const remove = async (row: any) => {
  await ElMessageBox.confirm(
    `确定删除「${row.originalName}」？台账会保留删除记录，文件本体一并清理。`,
    '删除附件',
    { type: 'warning' }
  )
  await api.delete(`/files/${row.id}`)
  ElMessage.success('已删除')
  load()
}

onMounted(async () => {
  await table.load()
  load()
  loadStorage()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">文件台账</h2>
        <p class="page-sub">
          所有附件都从统一接口进来，这里能看到文件写到了本地还是 S3、挂在哪个单据上、谁在什么时候传的
        </p>
      </div>
      <div class="head-actions">
        <el-button v-perm="'config:save'" plain :loading="testing" @click="testStorage">测试存储</el-button>
        <el-button plain @click="load">刷新</el-button>
      </div>
    </div>

    <div class="storage-strip">
      <div class="storage-item">
        <span>当前存储</span>
        <b :class="storageInfo.provider === 'S3' ? 's3-text' : ''">
          {{ storageInfo.provider === 'S3' ? 'S3 对象存储' : '本地目录' }}
        </b>
      </div>
      <div class="storage-item wide">
        <span>{{ storageInfo.provider === 'S3' ? 'S3 地址' : '本地目录' }}</span>
        <b class="mono small">
          <template v-if="storageInfo.provider === 'S3'">
            {{ storageInfo.s3Endpoint || '未配置' }} / {{ storageInfo.s3Bucket || '未配置' }}
          </template>
          <template v-else>{{ storageInfo.localRoot || '-' }}</template>
        </b>
      </div>
      <div class="storage-item">
        <span>当前列表</span>
        <b>共 {{ total }} 个 · 本页 {{ sizeText(totalSize) }}</b>
      </div>
      <div class="storage-item">
        <span>单文件上限</span>
        <b>{{ storageInfo.maxSizeMb || 20 }} MB</b>
      </div>
      <div class="storage-item">
        <span>切换影响</span>
        <b class="small">{{ storageInfo.description || '切换只影响新上传，老文件仍按原位置读取' }}</b>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-select v-model="filters.bizType" placeholder="全部业务" clearable style="width: 140px" @change="reload">
          <el-option v-for="item in bizOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="filters.storageType" placeholder="全部存储" clearable style="width: 130px" @change="reload">
          <el-option label="本地" value="LOCAL" />
          <el-option label="S3" value="S3" />
        </el-select>
        <el-input
          v-model="filters.keyword"
          placeholder="文件名 / 台账号 / 单号 / 上传人"
          clearable
          style="width: 240px"
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
        <TableToolbar :table="table" export-api="/files" export-name="文件台账" @applied="reload" />
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
        >
          <template #cell-fileNo="{ row }"><span class="mono">{{ row.fileNo }}</span></template>
          <template #cell-fileName="{ row }">
            <el-button link type="primary" @click="download(row)">{{ row.originalName }}</el-button>
          </template>
          <template #cell-storageType="{ row }">
            <span class="status-tag" :class="row.storageType === 'S3' ? 'primary' : 'info'">
              <i></i>{{ row.storageType === 'S3' ? 'S3' : '本地' }}
            </span>
          </template>
          <template #cell-biz="{ row }">
            <div>{{ bizLabel(row.bizType) }}</div>
            <div class="muted mono" style="font-size: 11px">{{ row.bizNo || '-' }}</div>
          </template>
          <template #cell-upload="{ row }">
            <div>{{ row.uploadedBy || '-' }}</div>
            <div class="muted" style="font-size: 11px">{{ row.uploadedAt }}</div>
          </template>
          <template #cell-sha256="{ row }"><span class="mono muted small">{{ row.sha256?.slice(0, 12) }}</span></template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="download(row)">下载</el-button>
            <el-button v-perm="'file:delete'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <p class="page-note">
      台账里存的是文件的存储类型与对象 key，所以中途从本地切到 S3 或者换桶，历史附件仍然能下载。
      删除只做台账标记并清掉文件本体，记录不物理删除，便于审计。
    </p>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  gap: 8px;
}

.storage-strip {
  display: flex;
  align-items: flex-start;
  gap: 34px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 15px 20px;
  margin-bottom: 14px;
  box-shadow: var(--shadow);
  flex-wrap: wrap;
}

.storage-item span {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.storage-item b {
  display: block;
  margin-top: 6px;
  font-size: 16px;
  font-family: Bahnschrift, monospace;
}

.storage-item.wide b {
  font-size: 12px;
}

.storage-item b.small {
  font-size: 12px;
  font-family: inherit;
  font-weight: 400;
  color: var(--ink-3);
  max-width: 300px;
  line-height: 1.5;
}

.s3-text {
  color: #2563eb;
}

.page-note {
  margin: 0;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}
</style>
