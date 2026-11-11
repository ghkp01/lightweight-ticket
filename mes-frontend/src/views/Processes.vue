<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney, fmtQty, nextCode } from '../api'
import InspectionItemEditor from '../components/InspectionItemEditor.vue'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import { useTableView } from '../composables/useTableView'

const rows = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'processes',
  columns: [
    { key: 'code', label: '工序编码', width: 120 },
    { key: 'name', label: '工序名称', width: 140, flex: true, ellipsis: true },
    { key: 'category', label: '类型', width: 100 },
    { key: 'defaultMinutes', label: '默认工时', width: 110, align: 'right' },
    { key: 'defaultPrice', label: '默认工价', width: 100, align: 'right' },
    { key: 'workCenter', label: '工作中心', width: 150, prop: 'workCenterName' },
    { key: 'inspectionMode', label: '检验方式', width: 130 },
    { key: 'enabled', label: '状态', width: 90 },
    { key: 'usage', label: '被引用', width: 180, prop: '' },
    { key: 'actions', label: '操作', width: 235, fixed: 'right' }
  ],
  getFilters: () => ({ keyword: keyword.value }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
  }
})

const reload = () => {
  load()
}

const dialog = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const centers = ref<any[]>([])
const form = reactive<any>({
  code: '',
  name: '',
  category: 'MACHINING',
  defaultMinutes: 0,
  defaultPrice: 0,
  inspectionMode: 'NONE',
  enabled: true,
  workCenterId: null,
  remark: ''
})

const inspectionModeMap: Record<string, { label: string; cls: string }> = {
  NONE: { label: '不检验', cls: 'draft' },
  ATTACHED: { label: '工序内检验点', cls: 'warning' },
  ROUTING: { label: '独立检验工序', cls: 'success' }
}

const inspectionModeOptions = [
  {
    value: 'NONE',
    label: '不检验',
    desc: '只报数量，质检在末端或抽检环节处理'
  },
  {
    value: 'ATTACHED',
    label: '工序内检验点',
    desc: '不单独成工序：报工时必须选择合格 / 不合格，不合格要填不良数量'
  },
  {
    value: 'ROUTING',
    label: '独立检验工序',
    desc: '单独成一道工序：在工艺路线里排在加工工序之后，由质检专门报检'
  }
]

const categoryMap: Record<string, { label: string; cls: string }> = {
  CUTTING: { label: '下料', cls: 'warning' },
  MACHINING: { label: '机加工', cls: 'primary' },
  BENDING: { label: '折弯', cls: 'info' },
  ASSEMBLY: { label: '装配', cls: 'success' },
  SPRAYING: { label: '喷涂', cls: 'warning' },
  INSPECTION: { label: '检验', cls: 'danger' },
  PACKING: { label: '包装', cls: 'info' },
  OUTSOURCE: { label: '外协', cls: 'draft' }
}

const categoryOptions = Object.keys(categoryMap).map((value) => ({ value, label: categoryMap[value].label }))

const load = async () => {
  loading.value = true
  try {
    rows.value = await api.get('/processes', { keyword: keyword.value })
  } finally {
    loading.value = false
  }
}

const itemDrawer = ref(false)
const itemTarget = ref<any>(null)

const openItems = (row: any) => {
  itemTarget.value = row
  itemDrawer.value = true
}

const openCreate = async () => {
  editingId.value = null
  Object.assign(form, {
    code: '',
    name: '',
    category: 'MACHINING',
    defaultMinutes: 0,
    defaultPrice: 0,
    inspectionMode: 'NONE',
    enabled: true,
    workCenterId: null,
    remark: ''
  })
  try {
    form.code = await nextCode('OP')
  } catch {
    form.code = ''
  }
  dialog.value = true
}

const openEdit = (row: any) => {
  editingId.value = row.id
  Object.assign(form, {
    code: row.code,
    name: row.name,
    category: row.category || 'MACHINING',
    defaultMinutes: Number(row.defaultMinutes || 0),
    defaultPrice: Number(row.defaultPrice || 0),
    inspectionMode: row.inspectionMode || (row.needInspection ? 'ROUTING' : 'NONE'),
    enabled: row.enabled !== false,
    workCenterId: row.workCenterId || null,
    remark: row.remark || ''
  })
  dialog.value = true
}

const payload = () => ({
  code: form.code,
  name: form.name,
  category: form.category,
  defaultMinutes: Number(form.defaultMinutes || 0),
  defaultPrice: Number(form.defaultPrice || 0),
  inspectionMode: form.inspectionMode || 'NONE',
  enabled: !!form.enabled,
  workCenterId: form.workCenterId || null,
  remark: form.remark || ''
})

const save = async () => {
  if (!form.name) {
    ElMessage.warning('工序名称不能为空')
    return
  }
  if (!form.category) {
    ElMessage.warning('请选择工序类型')
    return
  }
  if (Number(form.defaultMinutes || 0) < 0 || Number(form.defaultPrice || 0) < 0) {
    ElMessage.warning('默认工时和默认工价不能为负数')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await api.put(`/processes/${editingId.value}`, payload())
    } else {
      await api.post('/processes', payload())
    }
    ElMessage.success('保存成功')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const toggleEnabled = async (row: any) => {
  try {
    await api.put(`/processes/${row.id}`, {
      code: row.code,
      name: row.name,
      category: row.category,
      defaultMinutes: Number(row.defaultMinutes || 0),
      defaultPrice: Number(row.defaultPrice || 0),
      inspectionMode: row.inspectionMode || 'NONE',
      enabled: !row.enabled,
      workCenterId: row.workCenterId || null,
      remark: row.remark || ''
    })
    ElMessage.success(row.enabled ? '工序已停用' : '工序已启用')
    load()
  } catch {
    load()
  }
}

const remove = async (row: any) => {
  try {
    await ElMessageBox.confirm(
      `确认删除工序「${row.name}」？已被工艺路线或工单引用的工序无法删除，请改为停用。`,
      '删除确认',
      { type: 'warning' }
    )
    await api.delete(`/processes/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch {
    // 取消删除或后端返回 1409 时，接口层已给出提示
  }
}

const loadCenters = async () => {
  centers.value = await api.get('/work-centers/options')
}

onMounted(async () => {
  await table.load()
  await load()
  await loadCenters()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">工序管理</h2>
        <p class="page-sub">统一维护工序主数据，工艺路线和工单工序都按这里的默认工时、工价与检验要求生成</p>
      </div>
      <el-button v-perm="'process:save'" type="primary" @click="openCreate">新建工序</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="工序编码 / 名称" clearable style="width: 240px" @keyup.enter="load" />
        <el-button type="primary" plain @click="load">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/processes" export-name="工序" @applied="reload" />
        <span class="toolbar-tip">共 {{ rows.length }} 道工序</span>
      </div>
      <div class="table-wrap">
        <DataTable :table="table" :data="rows" :loading="loading">
          <template #cell-code="{ row }"><span class="mono">{{ row.code }}</span></template>
          <template #cell-category="{ row }">
            <span class="status-tag" :class="categoryMap[row.category]?.cls || 'info'">
              <i></i>{{ categoryMap[row.category]?.label || row.category }}
            </span>
          </template>
          <template #cell-defaultMinutes="{ row }">
            <span class="num">{{ fmtQty(row.defaultMinutes) }}</span>
            <span class="muted" style="font-size: 11px"> 分钟/件</span>
          </template>
          <template #cell-defaultPrice="{ row }"><span class="money">¥{{ fmtMoney(row.defaultPrice) }}</span></template>
          <template #cell-workCenter="{ row }">
            <div>{{ row.workCenterName || '未绑定' }}</div>
            <div v-if="row.workCenterCapacity" class="muted" style="font-size: 11px">
              日产能 {{ row.workCenterCapacity }} 分钟
            </div>
          </template>
          <template #cell-inspectionMode="{ row }">
            <span class="status-tag" :class="inspectionModeMap[row.inspectionMode || 'NONE']?.cls || 'draft'">
              <i></i>{{ inspectionModeMap[row.inspectionMode || 'NONE']?.label || '不检验' }}
            </span>
          </template>
          <template #cell-enabled="{ row }">
            <span class="status-tag" :class="row.enabled ? 'success' : 'draft'">
              <i></i>{{ row.enabled ? '启用' : '停用' }}
            </span>
          </template>
          <template #cell-usage="{ row }">
            <span class="muted">
              {{ row.routingCount || 0 }} 条路线 / {{ row.operationCount || 0 }} 道工单工序
            </span>
          </template>
          <template #cell-actions="{ row }">
            <el-button v-perm="'process:save'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-perm="'quality:item'" link type="primary" @click="openItems(row)">检验项</el-button>
            <el-button v-perm="'process:save'" link type="primary" @click="toggleEnabled(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button v-perm="'process:save'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </DataTable>
      </div>
      <div class="process-tip">
        提示：工序被工艺路线或工单引用后无法删除，请改为停用；停用后新建工艺路线时不再显示该工序。
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" :title="editingId ? '编辑工序' : '新建工序'" width="560px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="工序编码">
            <el-input v-model="form.code" placeholder="留空自动生成，如 OP001" />
          </el-form-item>
          <el-form-item label="工序名称" required>
            <el-input v-model="form.name" placeholder="如 激光下料" />
          </el-form-item>
          <el-form-item label="工序类型" required>
            <el-select v-model="form.category" style="width: 100%">
              <el-option v-for="item in categoryOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="默认工时（分钟/件）">
            <el-input-number v-model="form.defaultMinutes" :min="0" :precision="2" :step="0.5" style="width: 100%" />
          </el-form-item>
          <el-form-item label="默认工价（元/件）">
            <el-input-number v-model="form.defaultPrice" :min="0" :precision="2" :step="0.5" style="width: 100%" />
          </el-form-item>
          <el-form-item label="执行工作中心">
            <el-select v-model="form.workCenterId" clearable placeholder="不绑定" style="width: 100%">
              <el-option
                v-for="item in centers"
                :key="item.id"
                :label="`${item.name}（日产能 ${item.dailyCapacityMinutes} 分钟）`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="检验方式" class="full">
            <el-radio-group v-model="form.inspectionMode">
              <el-radio v-for="item in inspectionModeOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </el-radio>
            </el-radio-group>
            <div class="mode-hint">
              {{ inspectionModeOptions.find((item) => item.value === form.inspectionMode)?.desc }}
            </div>
          </el-form-item>
          <el-form-item label="启用状态">
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="设备、外协厂家或工艺说明" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <InspectionItemEditor
      v-model="itemDrawer"
      :process-id="itemTarget?.id"
      :title="`检验项 · ${itemTarget?.name || ''}`"
    />
  </div>
</template>

<style scoped>
.mode-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
}

.process-tip {
  margin: 0 16px 16px;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  color: var(--muted);
  font-size: 12px;
}
</style>
