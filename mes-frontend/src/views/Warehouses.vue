<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, nextCode } from '../api'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import { useTableView } from '../composables/useTableView'

const rows = ref<any[]>([])
const loading = ref(false)

// 表格视图：列显示 / 冻结 / 列宽按账号保存
const table = useTableView({
  pageKey: 'warehouses',
  columns: [
    { key: 'code', label: '仓库编码', width: 140 },
    { key: 'name', label: '仓库名称', width: 180, flex: true, ellipsis: true },
    { key: 'type', label: '仓库类型', width: 140 },
    { key: 'factoryName', label: '所属工厂', width: 160 },
    { key: 'enabled', label: '状态', width: 100 },
    { key: 'actions', label: '操作', width: 130 }
  ],
  getFilters: () => ({}),
  setFilters: () => {}
})

const reload = () => {
  load()
}

const dialog = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive({ code: '', name: '', type: 'GENERAL', factoryOrgId: null as number | null })
const plants = ref<any[]>([])

const typeLabel: Record<string, string> = { RAW: '原料仓', FINISHED: '成品仓', GENERAL: '通用仓' }

const load = async () => {
  loading.value = true
  try {
    rows.value = await api.get('/warehouses')
  } finally {
    loading.value = false
  }
}

// 多工厂：只让管理员把仓库挂到具体工厂，其余账号留空走默认工厂
const loadPlants = async () => {
  try {
    plants.value = (await api.get('/orgs', { type: 'PLANT' })) || []
  } catch (e) {
    plants.value = []
  }
}

const openCreate = async () => {
  editingId.value = null
  Object.assign(form, { code: '', name: '', type: 'GENERAL', factoryOrgId: null })
  try {
    form.code = await nextCode('WH')
  } catch {
    form.code = ''
  }
  dialog.value = true
}

const openEdit = (row: any) => {
  editingId.value = row.id
  Object.assign(form, { code: row.code, name: row.name, type: row.type, factoryOrgId: row.factoryOrgId || null })
  dialog.value = true
}

const save = async () => {
  if (!form.name) {
    ElMessage.warning('仓库名称不能为空')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await api.put(`/warehouses/${editingId.value}`, form)
    } else {
      await api.post('/warehouses', form)
    }
    ElMessage.success('保存成功')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(`确认删除仓库「${row.name}」？有库存的仓库无法删除。`, '删除确认', { type: 'warning' })
  await api.delete(`/warehouses/${row.id}`)
  ElMessage.success('已删除')
  load()
}

onMounted(async () => {
  await table.load()
  loadPlants()
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">仓库管理</h2>
        <p class="page-sub">原料仓用于生产领料，成品仓用于完工入库与发货</p>
      </div>
      <el-button type="primary" @click="openCreate">新建仓库</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/warehouses" export-name="仓库" @applied="reload" />
      </div>
      <div class="table-wrap" style="padding-top: 14px">
        <DataTable :table="table" :data="rows" :loading="loading">
          <template #cell-code="{ row }"><span class="mono">{{ row.code }}</span></template>
          <template #cell-type="{ row }">
            <span class="status-tag" :class="row.type === 'RAW' ? 'warning' : row.type === 'FINISHED' ? 'success' : 'info'">
              <i></i>{{ typeLabel[row.type] || row.type }}
            </span>
          </template>
          <template #cell-enabled="{ row }">
            <span class="status-tag success"><i></i>{{ row.enabled ? '启用' : '停用' }}</span>
          </template>
          <template #cell-factoryName="{ row }">
            <span v-if="row.factoryName">{{ row.factoryName }}</span>
            <span v-else class="muted">未指定</span>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </DataTable>
      </div>
      <div class="warehouse-tip">
        提示：每个类型至少保留一个启用仓库。生产领料固定从「原料仓」出库，完工入库与发货走「成品仓」。
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" :title="editingId ? '编辑仓库' : '新建仓库'" width="480px">
      <el-form label-position="top">
        <el-form-item label="仓库编码">
          <el-input v-model="form.code" placeholder="留空自动生成，如 WH001" />
        </el-form-item>
        <el-form-item label="仓库名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="仓库类型" required>
          <el-select v-model="form.type" style="width: 100%">
            <el-option label="原料仓" value="RAW" />
            <el-option label="成品仓" value="FINISHED" />
            <el-option label="通用仓" value="GENERAL" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="plants.length > 1" label="所属工厂">
          <el-select v-model="form.factoryOrgId" clearable placeholder="留空则落到当前账号所属工厂" style="width: 100%">
            <el-option v-for="plant in plants" :key="plant.id" :label="plant.name" :value="plant.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.warehouse-tip {
  margin: 0 16px 16px;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  color: var(--muted);
  font-size: 12px;
}
</style>
