<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, nextCode } from '../api'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import { useTableView } from '../composables/useTableView'

const loading = ref(false)
const rows = ref<any[]>([])
const keyword = ref('')

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'customers',
  columns: [
    { key: 'code', label: '客户编码', width: 110 },
    { key: 'name', label: '客户名称', width: 200, flex: true, ellipsis: true },
    { key: 'contact', label: '联系人', width: 90 },
    { key: 'phone', label: '联系电话', width: 130 },
    { key: 'address', label: '地址', width: 220, flex: true, ellipsis: true },
    { key: 'level', label: '等级', width: 80 },
    { key: 'createdAt', label: '创建日期', width: 110 },
    { key: 'actions', label: '操作', width: 120, fixed: 'right' }
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
const form = reactive({ code: '', name: '', contact: '', phone: '', address: '', level: 'A' })
const saving = ref(false)

const load = async () => {
  loading.value = true
  try {
    rows.value = await api.get('/customers', { keyword: keyword.value })
  } finally {
    loading.value = false
  }
}

const openCreate = async () => {
  editingId.value = null
  Object.assign(form, { code: '', name: '', contact: '', phone: '', address: '', level: 'A' })
  try {
    form.code = await nextCode('CUS')
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
    contact: row.contact,
    phone: row.phone,
    address: row.address,
    level: row.level || 'A'
  })
  dialog.value = true
}

const save = async () => {
  if (!form.name) {
    ElMessage.warning('客户名称不能为空')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await api.put(`/customers/${editingId.value}`, form)
    } else {
      await api.post('/customers', form)
    }
    ElMessage.success('保存成功')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(`确认删除客户「${row.name}」？已产生订单的客户无法删除。`, '删除确认', {
    type: 'warning'
  })
  await api.delete(`/customers/${row.id}`)
  ElMessage.success('已删除')
  load()
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
        <h2 class="page-title">客户管理</h2>
        <p class="page-sub">维护下单客户与联系方式，销售订单会引用这里的资料</p>
      </div>
      <el-button type="primary" :icon="'Plus'" @click="openCreate">新建客户</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="客户编码 / 名称 / 联系人" clearable style="width: 260px" @keyup.enter="load" />
        <el-button type="primary" plain @click="load">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/customers" export-name="客户列表" @applied="reload" />
        <span class="toolbar-tip">共 {{ rows.length }} 家客户</span>
      </div>
      <div class="table-wrap">
        <DataTable :table="table" :data="rows" :loading="loading">
          <template #cell-code="{ row }"><span class="mono">{{ row.code }}</span></template>
          <template #cell-phone="{ row }"><span class="mono">{{ row.phone }}</span></template>
          <template #cell-level="{ row }">
            <span class="status-tag primary">{{ row.level || 'A' }} 级</span>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" :title="editingId ? '编辑客户' : '新建客户'" width="540px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="客户编码">
            <el-input v-model="form.code" placeholder="留空自动生成，如 CUS001" />
          </el-form-item>
          <el-form-item label="客户名称" required>
            <el-input v-model="form.name" placeholder="公司全称" />
          </el-form-item>
          <el-form-item label="联系人">
            <el-input v-model="form.contact" />
          </el-form-item>
          <el-form-item label="联系电话">
            <el-input v-model="form.phone" />
          </el-form-item>
          <el-form-item label="客户等级">
            <el-select v-model="form.level" style="width: 100%">
              <el-option label="A 级 · 重点客户" value="A" />
              <el-option label="B 级 · 常规客户" value="B" />
              <el-option label="C 级 · 零散客户" value="C" />
            </el-select>
          </el-form-item>
          <el-form-item label="地址" class="full">
            <el-input v-model="form.address" type="textarea" :rows="2" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
