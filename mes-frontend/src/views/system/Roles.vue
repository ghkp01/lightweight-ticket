<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import DataTable from '../../components/DataTable.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import { useTableView } from '../../composables/useTableView'

const loading = ref(false)
const rows = ref<any[]>([])
const catalog = ref<any[]>([])

// 表格视图：列显示 / 冻结 / 列宽按账号保存
const table = useTableView({
  pageKey: 'system.roles',
  columns: [
    { key: 'code', label: '角色编码', width: 120 },
    { key: 'name', label: '角色名称', width: 130 },
    { key: 'description', label: '说明', width: 220, flex: true, ellipsis: true },
    { key: 'dataScope', label: '数据范围', width: 110 },
    { key: 'permissionCount', label: '权限数', width: 85, align: 'right', prop: '' },
    { key: 'userCount', label: '用户数', width: 85, align: 'right' },
    { key: 'builtin', label: '类型', width: 90 },
    { key: 'actions', label: '操作', width: 130, fixed: 'right', prop: '' }
  ]
})

const reload = () => load()

const drawer = ref(false)
const editingId = ref<number | null>(null)
const editingCode = ref('')
const saving = ref(false)
const form = reactive<any>({
  code: '',
  name: '',
  description: '',
  dataScope: 'ALL',
  permissions: []
})

const scopeOptions = [
  { value: 'ALL', label: '全部数据', tip: '可查看所有业务数据' },
  { value: 'SELF', label: '仅本人', tip: '销售订单只看本人创建' },
  { value: 'WAREHOUSE', label: '本仓库', tip: '库存与流水只看所属仓库' }
]

const scopeMap: Record<string, string> = {
  ALL: '全部数据',
  SELF: '仅本人',
  WAREHOUSE: '本仓库'
}

const load = async () => {
  loading.value = true
  try {
    rows.value = await api.get('/system/roles')
  } finally {
    loading.value = false
  }
}

const loadCatalog = async () => {
  catalog.value = await api.get('/system/permissions')
}

const openCreate = () => {
  editingId.value = null
  editingCode.value = ''
  Object.assign(form, { code: '', name: '', description: '', dataScope: 'ALL', permissions: [] })
  drawer.value = true
}

const openEdit = (row: any) => {
  editingId.value = row.id
  editingCode.value = row.code
  Object.assign(form, {
    code: row.code,
    name: row.name,
    description: row.description || '',
    dataScope: row.dataScope || 'ALL',
    permissions: [...(row.permissions || [])]
  })
  drawer.value = true
}

const groupCheckedCount = (group: any) =>
  group.items.filter((item: any) => form.permissions.includes(item.code)).length

const isGroupChecked = (group: any) => groupCheckedCount(group) === group.items.length

const isGroupIndeterminate = (group: any) => {
  const count = groupCheckedCount(group)
  return count > 0 && count < group.items.length
}

const toggleGroup = (group: any, checked: any) => {
  const codes = group.items.map((item: any) => item.code)
  if (checked) {
    form.permissions = Array.from(new Set([...form.permissions, ...codes]))
  } else {
    form.permissions = form.permissions.filter((code: string) => !codes.includes(code))
  }
}

const submit = async () => {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('角色编码和名称不能为空')
    return
  }
  saving.value = true
  try {
    const payload = {
      code: form.code.trim(),
      name: form.name.trim(),
      description: form.description,
      dataScope: form.dataScope,
      permissions: form.permissions
    }
    if (editingId.value) {
      await api.put(`/system/roles/${editingId.value}`, payload)
    } else {
      await api.post('/system/roles', payload)
    }
    ElMessage.success('角色已保存')
    drawer.value = false
    load()
  } finally {
    saving.value = false
  }
}

const remove = async (row: any) => {
  if (row.builtin) {
    ElMessage.warning('内置角色不允许删除')
    return
  }
  await ElMessageBox.confirm(`确认删除角色「${row.name}」？删除后该角色的权限配置将一并移除。`, '删除确认', {
    type: 'warning'
  })
  await api.delete(`/system/roles/${row.id}`)
  ElMessage.success('角色已删除')
  load()
}

onMounted(async () => {
  await table.load()
  await loadCatalog()
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">角色权限</h2>
        <p class="page-sub">按分组勾选菜单与操作按钮权限，再用数据范围控制可见的业务数据</p>
      </div>
      <el-button type="primary" @click="openCreate">新建角色</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <span class="toolbar-tip">内置角色不可删除；接口权限保存后立即生效，页面按钮需刷新或重新登录</span>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/system/roles" export-name="角色" @applied="reload" />
        <span class="toolbar-tip">共 {{ rows.length }} 个角色</span>
      </div>

      <div class="table-wrap">
        <DataTable :table="table" :data="rows" :loading="loading">
          <template #cell-code="{ row }"><span class="mono">{{ row.code }}</span></template>
          <template #cell-description="{ row }">{{ row.description || '-' }}</template>
          <template #cell-dataScope="{ row }">
            <span class="status-tag" :class="row.dataScope === 'ALL' ? 'success' : row.dataScope === 'SELF' ? 'warning' : 'primary'">
              <i></i>{{ scopeMap[row.dataScope] || row.dataScope }}
            </span>
          </template>
          <template #cell-permissionCount="{ row }"><span class="num">{{ (row.permissions || []).length }}</span></template>
          <template #cell-userCount="{ row }"><span class="num">{{ row.userCount }}</span></template>
          <template #cell-builtin="{ row }">
            <el-tag v-if="row.builtin" size="small" type="info">内置</el-tag>
            <el-tag v-else size="small" type="success">自定义</el-tag>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openEdit(row)">配置</el-button>
            <el-button link type="danger" :disabled="row.builtin" @click="remove(row)">删除</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <el-drawer append-to-body v-model="drawer" size="760px" :title="editingId ? '配置角色' : '新建角色'">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="角色编码" required>
            <el-input v-model="form.code" :disabled="editingId !== null && editingCode === 'ADMIN'" placeholder="如 SALES" />
          </el-form-item>
          <el-form-item label="角色名称" required>
            <el-input v-model="form.name" placeholder="如 销售" />
          </el-form-item>
          <el-form-item label="数据范围" class="full">
            <el-select v-model="form.dataScope" style="width: 260px">
              <el-option v-for="item in scopeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <div class="scope-tip">
              {{ scopeOptions.find((item) => item.value === form.dataScope)?.tip }}
            </div>
          </el-form-item>
          <el-form-item label="说明" class="full">
            <el-input v-model="form.description" type="textarea" :rows="2" placeholder="角色职责说明" />
          </el-form-item>
        </div>

        <div class="drawer-section">
          <h4>
            <span>菜单与操作权限</span>
            <span class="toolbar-tip">已选 {{ form.permissions.length }} 项</span>
          </h4>

          <div class="permission-grid">
            <div v-for="group in catalog" :key="group.group" class="permission-card">
              <div class="permission-head">
                <b>{{ group.group }}</b>
                <el-checkbox
                  :model-value="isGroupChecked(group)"
                  :indeterminate="isGroupIndeterminate(group)"
                  @change="toggleGroup(group, $event)"
                >
                  全选
                </el-checkbox>
              </div>
              <el-checkbox-group v-model="form.permissions" class="permission-items">
                <el-checkbox v-for="item in group.items" :key="item.code" :value="item.code">
                  {{ item.label }}
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存角色</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.scope-tip {
  width: 100%;
  margin-top: 6px;
  color: var(--muted);
  font-size: 11px;
}

.permission-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.permission-card {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfcfe;
  padding: 10px 12px 12px;
}

.permission-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 6px;
  border-bottom: 1px dashed var(--line);
  margin-bottom: 8px;
}

.permission-head b {
  font-size: 12px;
  color: var(--ink-2);
}

.permission-items {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 8px;
}

:deep(.permission-items .el-checkbox) {
  margin-right: 0;
  height: 24px;
}
</style>
