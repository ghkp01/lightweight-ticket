<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'

/**
 * 低代码字段设计器：给业务表单加自定义字段。
 * 字段定义存 sys_custom_field，业务值存对应表的 JSONB 扩展列，不改主表结构。
 */

const loading = ref(false)
const saving = ref(false)
const entities = ref<any[]>([])
const factoryOptions = ref<any[]>([])
const roleOptions = ref<any[]>([])
const entityType = ref('sales_order')
const rows = ref<any[]>([])

const typeOptions = [
  { value: 'TEXT', label: '单行文本' },
  { value: 'TEXTAREA', label: '多行文本' },
  { value: 'NUMBER', label: '数字' },
  { value: 'DATE', label: '日期' },
  { value: 'SELECT', label: '下拉选择' },
  { value: 'SWITCH', label: '是 / 否' }
]

const typeMap: Record<string, string> = Object.fromEntries(
  typeOptions.map((item) => [item.value, item.label])
)

const dialog = ref(false)
const editing = ref<any>(null)
const form = reactive<any>({
  entityType: 'sales_order',
  fieldKey: '',
  label: '',
  fieldType: 'TEXT',
  required: false,
  optionsText: '',
  defaultValue: '',
  sortNo: 0,
  enabled: true,
  factoryOrgId: null,
  visibleRoles: [],
  editableRoles: []
})

const isEdit = computed(() => Boolean(editing.value))

const loadEntities = async () => {
  entities.value = await api.get('/custom-fields/entities')
  if (entities.value.length && !entities.value.some((item) => item.entityType === entityType.value)) {
    entityType.value = entities.value[0].entityType
  }
}

const loadFields = async () => {
  if (!entityType.value) return
  loading.value = true
  try {
    rows.value = await api.get('/custom-fields/definitions', {
      entityType: entityType.value
    })
  } finally {
    loading.value = false
  }
}

const loadOptions = async () => {
  const [factories, roles] = await Promise.all([
    api.get('/custom-fields/factories'),
    api.get('/system/roles')
  ])
  factoryOptions.value = factories || []
  roleOptions.value = roles || []
}

const factoryText = (id: any) => {
  if (!id) return '全局'
  const factory = factoryOptions.value.find((item) => Number(item.id) === Number(id))
  return factory ? factory.name : `工厂 #${id}`
}

const roleText = (roles: string[] | null | undefined) => {
  if (!roles || !roles.length) return '全部'
  return roles.map((code) => {
    const role = roleOptions.value.find((item) => item.code === code)
    return role ? role.name : code
  }).join('、')
}

const openCreate = () => {
  editing.value = null
  Object.assign(form, {
    entityType: entityType.value,
    fieldKey: '',
    label: '',
    fieldType: 'TEXT',
    required: false,
    optionsText: '',
    defaultValue: '',
    sortNo: rows.value.length ? Number(rows.value[rows.value.length - 1].sortNo || 0) + 10 : 10,
    enabled: true,
    factoryOrgId: null,
    visibleRoles: [],
    editableRoles: []
  })
  dialog.value = true
}

const openEdit = (row: any) => {
  editing.value = row
  Object.assign(form, {
    entityType: row.entityType,
    fieldKey: row.fieldKey,
    label: row.label,
    fieldType: row.fieldType,
    required: Boolean(row.required),
    optionsText: (row.options || []).join(','),
    defaultValue: row.defaultValue === null || row.defaultValue === undefined
      ? ''
      : String(row.defaultValue),
    sortNo: Number(row.sortNo || 0),
    enabled: Boolean(row.enabled),
    factoryOrgId: row.factoryOrgId ?? null,
    visibleRoles: [...(row.visibleRoles || [])],
    editableRoles: [...(row.editableRoles || [])]
  })
  dialog.value = true
}

const payload = () => ({
  entityType: form.entityType,
  fieldKey: form.fieldKey,
  label: form.label,
  fieldType: form.fieldType,
  required: Boolean(form.required),
  options: form.fieldType === 'SELECT'
    ? String(form.optionsText || '').split(/[,，\n]/).map((item) => item.trim()).filter(Boolean)
    : [],
  defaultValue: form.defaultValue === '' ? null : form.defaultValue,
  sortNo: Number(form.sortNo || 0),
  enabled: Boolean(form.enabled),
  factoryOrgId: form.factoryOrgId || null,
  visibleRoles: [...(form.visibleRoles || [])],
  editableRoles: [...(form.editableRoles || [])]
})

const submit = async () => {
  if (!form.label || !String(form.label).trim()) {
    ElMessage.warning('请填写字段名称')
    return
  }
  if (!form.fieldKey || !/^[a-z][a-z0-9_]{0,40}$/.test(String(form.fieldKey).trim())) {
    ElMessage.warning('字段键只能用小写字母、数字、下划线，且以字母开头')
    return
  }
  if (form.fieldType === 'SELECT' && !payload().options.length) {
    ElMessage.warning('下拉字段至少要配置一个选项')
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await api.put(`/custom-fields/${editing.value.id}`, payload())
    } else {
      await api.post('/custom-fields', payload())
    }
    ElMessage.success('字段已保存')
    dialog.value = false
    await loadFields()
  } finally {
    saving.value = false
  }
}

const toggleEnabled = async (row: any) => {
  const next = !row.enabled
  await api.put(`/custom-fields/${row.id}`, {
    entityType: row.entityType,
    fieldKey: row.fieldKey,
    label: row.label,
    fieldType: row.fieldType,
    required: Boolean(row.required),
    options: row.options || [],
    defaultValue: row.defaultValue ?? null,
    sortNo: Number(row.sortNo || 0),
    enabled: next,
    visibleRoles: [...(row.visibleRoles || [])],
    editableRoles: [...(row.editableRoles || [])]
  })
  ElMessage.success(next ? '字段已启用' : '字段已停用')
  await loadFields()
}

const disableField = async (row: any) => {
  await ElMessageBox.confirm(
    `停用字段「${row.label}」？历史订单里的值会保留，只是新表单不再显示。`,
    '停用字段',
    { type: 'warning' }
  )
  await api.delete(`/custom-fields/${row.id}`)
  ElMessage.success('已停用')
  await loadFields()
}

const displayValue = (value: any) => {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'boolean') return value ? '是' : '否'
  return String(value)
}

onMounted(async () => {
  await loadEntities()
  await loadOptions()
  await loadFields()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">自定义字段</h2>
        <p class="page-sub">
          给业务表单加字段，不用改表结构；字段值存扩展 JSONB，停用字段后历史值仍保留
        </p>
      </div>
      <el-button type="primary" @click="openCreate">新增字段</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-select v-model="entityType" style="width: 220px" @change="loadFields">
          <el-option
            v-for="item in entities"
            :key="item.entityType"
            :label="item.label"
            :value="item.entityType"
          />
        </el-select>
        <span class="toolbar-tip">
          字段键与类型保存后不可修改，避免历史数据错位；删除动作是停用
        </span>
        <div class="spacer"></div>
        <el-button plain :loading="loading" @click="loadFields">刷新</el-button>
      </div>

      <div class="table-wrap">
        <el-table :data="rows" height="100%" size="small" v-loading="loading" empty-text="还没有自定义字段">
          <el-table-column label="排序" width="70" align="right" prop="sortNo" />
          <el-table-column label="字段名称" min-width="150">
            <template #default="{ row }"><b>{{ row.label }}</b></template>
          </el-table-column>
          <el-table-column label="字段键" width="170">
            <template #default="{ row }"><span class="mono">{{ row.fieldKey }}</span></template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">{{ typeMap[row.fieldType] || row.fieldType }}</template>
          </el-table-column>
          <el-table-column label="必填" width="80" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.required" type="warning" size="small">必填</el-tag>
              <span v-else class="muted">否</span>
            </template>
          </el-table-column>
          <el-table-column label="选项" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.options?.length">{{ row.options.join(' / ') }}</span>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="默认值" width="110" align="center">
            <template #default="{ row }">{{ displayValue(row.defaultValue) }}</template>
          </el-table-column>
          <el-table-column label="作用工厂" width="110">
            <template #default="{ row }">{{ factoryText(row.factoryOrgId) }}</template>
          </el-table-column>
          <el-table-column label="可见角色" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ roleText(row.visibleRoles) }}</template>
          </el-table-column>
          <el-table-column label="可编辑角色" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ roleText(row.editableRoles) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="primary" @click="toggleEnabled(row)">
                {{ row.enabled ? '停用' : '启用' }}
              </el-button>
              <el-button v-if="row.enabled" link type="danger" @click="disableField(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog append-to-body draggable v-model="dialog" :title="isEdit ? '编辑自定义字段' : '新增自定义字段'" width="560px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="业务表单" required>
            <el-select v-model="form.entityType" disabled style="width: 100%">
              <el-option
                v-for="item in entities"
                :key="item.entityType"
                :label="item.label"
                :value="item.entityType"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="作用工厂">
            <el-select
              v-model="form.factoryOrgId"
              :disabled="isEdit"
              clearable
              placeholder="不选表示全局字段"
              style="width: 100%"
            >
              <el-option
                v-for="factory in factoryOptions"
                :key="factory.id"
                :label="factory.name"
                :value="factory.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="字段名称" required>
            <el-input v-model="form.label" placeholder="显示给用户看的名称，如：项目编号" />
          </el-form-item>
          <el-form-item label="字段键" required>
            <el-input
              v-model="form.fieldKey"
              :disabled="isEdit"
              placeholder="英文小写，如 project_code"
            />
            <div class="field-tip">保存后不可修改，程序里用它取值</div>
          </el-form-item>
          <el-form-item label="控件类型" required>
            <el-select v-model="form.fieldType" :disabled="isEdit" style="width: 100%">
              <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.fieldType === 'SELECT'" label="下拉选项" class="full" required>
            <el-input v-model="form.optionsText" placeholder="用逗号分隔，如：华东,华南,华北" />
          </el-form-item>
          <el-form-item label="默认值">
            <el-input v-model="form.defaultValue" placeholder="选填" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="form.sortNo" :min="0" :step="10" style="width: 100%" />
          </el-form-item>
          <el-form-item label="必填">
            <el-switch v-model="form.required" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
          </el-form-item>
          <el-form-item label="可见角色" class="full">
            <el-select
              v-model="form.visibleRoles"
              multiple
              clearable
              placeholder="不选表示全部角色可见"
              style="width: 100%"
            >
              <el-option v-for="role in roleOptions" :key="role.id" :label="role.name" :value="role.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="可编辑角色" class="full">
            <el-select
              v-model="form.editableRoles"
              multiple
              clearable
              placeholder="不选表示所有可见角色都能编辑；只读角色在这里排除"
              style="width: 100%"
            >
              <el-option v-for="role in roleOptions" :key="role.id" :label="role.name" :value="role.code" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存字段</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.field-tip {
  margin-top: 4px;
  font-size: 11px;
  color: var(--muted);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.form-grid .full {
  grid-column: span 2;
}

@media (max-width: 700px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .form-grid .full {
    grid-column: span 1;
  }
}
</style>
