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
const category = ref('')
const enabled = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'purchase.suppliers',
  columns: [
    { key: 'code', label: '编码', width: 100 },
    { key: 'name', label: '供应商名称', width: 200, flex: true, ellipsis: true },
    { key: 'category', label: '类型', width: 85 },
    { key: 'contact', label: '联系人', width: 150 },
    { key: 'paymentTerm', label: '账期', width: 110 },
    { key: 'leadDays', label: '交期', width: 90 },
    { key: 'rating', label: '评级', width: 75 },
    { key: 'orderCount', label: '采购单', width: 110 },
    { key: 'enabled', label: '状态', width: 80 },
    { key: 'actions', label: '操作', width: 130, fixed: 'right' }
  ],
  getFilters: () => ({ keyword: keyword.value, category: category.value, enabled: enabled.value }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    category.value = filters.category || ''
    enabled.value = filters.enabled || ''
  },
  getPageSize: () => size.value,
  setPageSize: (value) => { size.value = value }
})

const reload = () => {
  page.value = 1
  load()
}

const dialog = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)

const form = reactive({
  code: '',
  name: '',
  category: 'MATERIAL',
  contact: '',
  phone: '',
  email: '',
  address: '',
  taxNo: '',
  paymentTerm: '',
  creditDays: 30,
  leadDays: 7,
  rating: 'B',
  enabled: true,
  remark: ''
})

const categoryMap: Record<string, { label: string; cls: string }> = {
  MATERIAL: { label: '物料', cls: 'primary' },
  OUTSOURCE: { label: '外协', cls: 'warning' },
  SERVICE: { label: '服务', cls: 'info' }
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/suppliers', {
      keyword: keyword.value || undefined,
      category: category.value || undefined,
      enabled: enabled.value || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = data?.records || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

const openCreate = async () => {
  editingId.value = null
  Object.assign(form, {
    code: '',
    name: '',
    category: 'MATERIAL',
    contact: '',
    phone: '',
    email: '',
    address: '',
    taxNo: '',
    paymentTerm: '',
    creditDays: 30,
    leadDays: 7,
    rating: 'B',
    enabled: true,
    remark: ''
  })
  try {
    form.code = await nextCode('SUP')
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
    category: row.category || 'MATERIAL',
    contact: row.contact || '',
    phone: row.phone || '',
    email: row.email || '',
    address: row.address || '',
    taxNo: row.taxNo || '',
    paymentTerm: row.paymentTerm || '',
    creditDays: Number(row.creditDays ?? 30),
    leadDays: Number(row.leadDays ?? 7),
    rating: row.rating || 'B',
    enabled: row.enabled !== false,
    remark: row.remark || ''
  })
  dialog.value = true
}

const save = async () => {
  if (!form.name.trim()) {
    ElMessage.warning('供应商名称不能为空')
    return
  }
  saving.value = true
  try {
    const payload = { ...form, creditDays: Number(form.creditDays || 0), leadDays: Number(form.leadDays || 0) }
    if (editingId.value) {
      await api.put(`/suppliers/${editingId.value}`, payload)
    } else {
      await api.post('/suppliers', payload)
    }
    ElMessage.success('保存成功')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(
    `确认删除供应商「${row.name}」？已有采购单的供应商无法删除，可以改为停用。`,
    '删除确认',
    { type: 'warning' }
  )
  await api.delete(`/suppliers/${row.id}`)
  ElMessage.success('已删除')
  load()
}

const reset = () => {
  keyword.value = ''
  category.value = ''
  enabled.value = ''
  load()
}

const categoryOf = (value: any) =>
  categoryMap[String(value || '')] || { label: String(value || '-'), cls: 'info' }

onMounted(async () => {
  await table.load()
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">供应商档案</h2>
        <p class="page-sub">
          物料、外协、服务三类供应商，带账期、默认交期与评级；采购单只列启用的供应商
        </p>
      </div>
      <el-button v-perm="'supplier:save'" type="primary" @click="openCreate">新建供应商</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="编码 / 名称 / 联系人"
          clearable
          style="width: 220px"
          @keyup.enter="reload"
        />
        <el-select v-model="category" placeholder="全部类型" clearable style="width: 130px" @change="reload">
          <el-option label="物料" value="MATERIAL" />
          <el-option label="外协" value="OUTSOURCE" />
          <el-option label="服务" value="SERVICE" />
        </el-select>
        <el-select v-model="enabled" placeholder="全部状态" clearable style="width: 120px" @change="reload">
          <el-option label="启用" value="true" />
          <el-option label="停用" value="false" />
        </el-select>
        <el-button type="primary" plain @click="reload">查询</el-button>
        <el-button plain @click="reset">重置</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/suppliers" export-name="供应商" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 家供应商</span>
      </div>

      <div class="table-wrap">
        <DataTable :table="table" :data="rows" :loading="loading">
          <template #cell-code="{ row }"><span class="mono">{{ row.code }}</span></template>
          <template #cell-category="{ row }">
            <span class="status-tag" :class="categoryOf(row.category).cls">
              <i></i>{{ categoryOf(row.category).label }}
            </span>
          </template>
          <template #cell-contact="{ row }">
            <div>{{ row.contact || '-' }}</div>
            <div class="muted mono" style="font-size: 11px">{{ row.phone || '' }}</div>
          </template>
          <template #cell-paymentTerm="{ row }">
            <div>{{ row.paymentTerm || '-' }}</div>
            <div class="muted" style="font-size: 11px">{{ row.creditDays }} 天</div>
          </template>
          <template #cell-leadDays="{ row }">
            <span class="mono">{{ row.leadDays }}</span> 天
          </template>
          <template #cell-rating="{ row }">
            <span class="status-tag" :class="row.rating === 'A' ? 'success' : row.rating === 'C' ? 'danger' : 'info'">
              <i></i>{{ row.rating }} 级
            </span>
          </template>
          <template #cell-orderCount="{ row }">
            <div>{{ row.orderCount || 0 }} 张</div>
            <div class="muted" style="font-size: 11px">{{ row.lastOrderDate || '未合作' }}</div>
          </template>
          <template #cell-enabled="{ row }">
            <span class="status-tag" :class="row.enabled ? 'success' : 'info'">
              <i></i>{{ row.enabled ? '启用' : '停用' }}
            </span>
          </template>
          <template #cell-actions="{ row }">
            <el-button v-perm="'supplier:save'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-perm="'supplier:save'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </DataTable>
      </div>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load"
        />
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" :title="editingId ? '编辑供应商' : '新建供应商'" width="680px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="供应商编码">
            <el-input v-model="form.code" placeholder="留空自动生成，如 SUP001" />
          </el-form-item>
          <el-form-item label="供应商名称" required>
            <el-input v-model="form.name" placeholder="公司全称" />
          </el-form-item>
          <el-form-item label="供应商类型">
            <el-select v-model="form.category" style="width: 100%">
              <el-option label="物料供应商" value="MATERIAL" />
              <el-option label="外协加工" value="OUTSOURCE" />
              <el-option label="服务类" value="SERVICE" />
            </el-select>
          </el-form-item>
          <el-form-item label="评级">
            <el-select v-model="form.rating" style="width: 100%">
              <el-option label="A 级 · 优先" value="A" />
              <el-option label="B 级 · 常规" value="B" />
              <el-option label="C 级 · 备选" value="C" />
            </el-select>
          </el-form-item>
          <el-form-item label="联系人">
            <el-input v-model="form.contact" />
          </el-form-item>
          <el-form-item label="联系电话">
            <el-input v-model="form.phone" placeholder="11 位手机号" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="form.email" />
          </el-form-item>
          <el-form-item label="税号">
            <el-input v-model="form.taxNo" />
          </el-form-item>
          <el-form-item label="结算账期">
            <el-input v-model="form.paymentTerm" placeholder="如 月结 30 天" />
          </el-form-item>
          <el-form-item label="账期天数">
            <el-input-number v-model="form.creditDays" :min="0" :max="365" style="width: 100%" />
          </el-form-item>
          <el-form-item label="默认交期（天）">
            <el-input-number v-model="form.leadDays" :min="0" :max="365" style="width: 100%" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
          <el-form-item label="地址" class="full">
            <el-input v-model="form.address" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="如：来料需附材质证明" />
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
