<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import DataTable from '../../components/DataTable.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import { useTableView } from '../../composables/useTableView'

const loading = ref(false)
const rows = ref<any[]>([])

// 表格视图：列显示 / 冻结 / 列宽按账号保存
const table = useTableView({
  pageKey: 'system.code-rules',
  columns: [
    { key: 'biz', label: '业务类型', width: 150, flex: true, ellipsis: true, prop: '' },
    { key: 'prefix', label: '前缀', width: 110 },
    { key: 'datePattern', label: '日期格式', width: 120 },
    { key: 'separator', label: '分隔符', width: 90 },
    { key: 'seqLength', label: '流水位数', width: 95, align: 'right' },
    { key: 'resetCycle', label: '重置周期', width: 105 },
    { key: 'sample', label: '示例', width: 160, flex: true, ellipsis: true },
    { key: 'enabled', label: '启用', width: 100 },
    { key: 'actions', label: '操作', width: 90, fixed: 'right', prop: '' }
  ]
})

const reload = () => load()

const dialog = ref(false)
const editing = ref<any>(null)
const saving = ref(false)

const form = reactive<any>({
  prefix: '',
  datePattern: 'yyyyMMdd',
  separator: '',
  seqLength: 4,
  resetCycle: 'DAY',
  enabled: true
})

const datePatternOptions = [
  { value: 'yyyyMMdd', label: 'yyyyMMdd（年月日）' },
  { value: 'yyyyMM', label: 'yyyyMM（年月）' },
  { value: 'yyyy', label: 'yyyy（年）' },
  { value: 'NONE', label: '不使用日期' }
]

const resetCycleOptions = [
  { value: 'DAY', label: '按日重置' },
  { value: 'MONTH', label: '按月重置' },
  { value: 'YEAR', label: '按年重置' },
  { value: 'NEVER', label: '不重置' }
]

const resetCycleMap: Record<string, string> = {
  DAY: '按日',
  MONTH: '按月',
  YEAR: '按年',
  NEVER: '不重置'
}

const load = async () => {
  loading.value = true
  try {
    rows.value = await api.get('/system/code-rules')
  } finally {
    loading.value = false
  }
}

const openEdit = (row: any) => {
  editing.value = row
  Object.assign(form, {
    prefix: row.prefix || '',
    datePattern: row.datePattern || 'NONE',
    separator: row.separator || '',
    seqLength: Number(row.seqLength || 4),
    resetCycle: row.resetCycle || 'DAY',
    enabled: !!row.enabled
  })
  dialog.value = true
}

const pad = (value: number) => String(value).padStart(2, '0')

const preview = computed(() => {
  const now = new Date()
  const date = ({
    yyyyMMdd: `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`,
    yyyyMM: `${now.getFullYear()}${pad(now.getMonth() + 1)}`,
    yyyy: `${now.getFullYear()}`
  } as Record<string, string>)[form.datePattern] || ''
  const separator = (form.separator || '').trim()
  const seq = String(1).padStart(Number(form.seqLength) || 4, '0')
  return `${(form.prefix || '').trim()}${date ? separator + date : ''}${separator}${seq}`
})

const submit = async () => {
  const prefix = (form.prefix || '').trim()
  const separator = (form.separator || '').trim()
  if (!prefix) {
    ElMessage.warning('前缀不能为空')
    return
  }
  if (prefix.length > 16) {
    ElMessage.warning('前缀长度不能超过 16 位')
    return
  }
  const seqLength = Number(form.seqLength)
  if (!Number.isInteger(seqLength) || seqLength < 3 || seqLength > 8) {
    ElMessage.warning('流水位数只能是 3-8 位')
    return
  }
  if (separator.length > 4) {
    ElMessage.warning('分隔符最多 4 位')
    return
  }
  saving.value = true
  try {
    await api.put(`/system/code-rules/${editing.value.id}`, {
      prefix,
      datePattern: form.datePattern === 'NONE' ? '' : form.datePattern,
      separator,
      seqLength,
      resetCycle: form.resetCycle,
      enabled: form.enabled
    })
    ElMessage.success('编码规则已保存')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const toggleEnabled = async (row: any) => {
  const enabled = row.enabled
  try {
    await api.put(`/system/code-rules/${row.id}`, {
      prefix: row.prefix,
      datePattern: row.datePattern || '',
      separator: row.separator || '',
      seqLength: row.seqLength,
      resetCycle: row.resetCycle,
      enabled
    })
    ElMessage.success(enabled ? `已启用「${row.bizName}」编码规则` : `已停用「${row.bizName}」，将回退默认编码`)
    load()
  } catch {
    row.enabled = !enabled
  }
}

onMounted(async () => {
  await table.load()
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">编码规则</h2>
        <p class="page-sub">按业务类型配置单号前缀、日期格式、分隔符与流水位数，保存后新生成的单号立即生效</p>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <span class="toolbar-tip">
          单号 = 前缀 + 分隔符 + 日期 + 分隔符 + 补零流水；停用的业务回退为「业务类型 + yyyyMMdd + 4 位流水」。
        </span>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/system/code-rules" export-name="编码规则" @applied="reload" />
        <span class="toolbar-tip">共 {{ rows.length }} 条规则</span>
      </div>

      <div class="table-wrap">
        <DataTable :table="table" :data="rows" :loading="loading">
          <template #cell-biz="{ row }">
            <div class="biz-cell">
              <span class="biz-name">{{ row.bizName }}</span>
              <span class="biz-code mono">{{ row.bizType }}</span>
            </div>
          </template>
          <template #cell-prefix="{ row }">
            <span class="mono">{{ row.prefix }}</span>
          </template>
          <template #cell-datePattern="{ row }">
            <span class="mono">{{ row.datePattern || '不使用' }}</span>
          </template>
          <template #cell-separator="{ row }">
            <span class="mono">{{ row.separator || '无' }}</span>
          </template>
          <template #cell-seqLength="{ row }"><span class="num">{{ row.seqLength }}</span></template>
          <template #cell-resetCycle="{ row }">
            <span class="status-tag" :class="row.resetCycle === 'NEVER' ? 'info' : 'primary'">
              <i></i>{{ resetCycleMap[row.resetCycle] || row.resetCycle }}
            </span>
          </template>
          <template #cell-sample="{ row }">
            <span class="mono sample">{{ row.sample }}</span>
          </template>
          <template #cell-enabled="{ row }">
            <el-switch v-model="row.enabled" inline-prompt active-text="启用" inactive-text="停用" @change="toggleEnabled(row)" />
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" title="编辑编码规则" width="520px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="业务类型">
            <el-input :model-value="editing ? `${editing.bizName}（${editing.bizType}）` : ''" disabled />
          </el-form-item>
          <el-form-item label="前缀" required>
            <el-input v-model="form.prefix" maxlength="16" show-word-limit placeholder="如 SO" />
          </el-form-item>
          <el-form-item label="日期格式">
            <el-select v-model="form.datePattern" style="width: 100%">
              <el-option v-for="item in datePatternOptions" :key="item.label" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="分隔符">
            <el-input v-model="form.separator" maxlength="4" placeholder="如 -，留空则直接拼接" />
          </el-form-item>
          <el-form-item label="流水位数">
            <el-input-number v-model="form.seqLength" :min="3" :max="8" :step="1" step-strictly style="width: 100%" />
          </el-form-item>
          <el-form-item label="重置周期">
            <el-select v-model="form.resetCycle" style="width: 100%">
              <el-option v-for="item in resetCycleOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="启用状态" class="full">
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
            <span class="form-tip">停用后该业务回退为默认编码格式。</span>
          </el-form-item>
        </div>
        <div class="preview-bar">
          <span class="toolbar-tip">示例</span>
          <span class="mono sample">{{ preview }}</span>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存规则</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.biz-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.biz-name {
  font-weight: 600;
  color: var(--ink);
}

.biz-code {
  font-size: 11px;
  color: var(--muted);
  background: #f2f4f7;
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 0 5px;
  line-height: 17px;
}

.sample {
  font-weight: 600;
  color: var(--primary);
}

.form-tip {
  margin-left: 12px;
  color: var(--muted);
  font-size: 11px;
}

.preview-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  padding: 10px 12px;
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  background: #fbfcfe;
}
</style>
