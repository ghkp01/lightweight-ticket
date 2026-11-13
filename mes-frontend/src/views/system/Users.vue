<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import { encryptPassword } from '../../utils/crypto'
import DataTable from '../../components/DataTable.vue'
import ReferenceSelect from '../../components/ReferenceSelect.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import { useTableView } from '../../composables/useTableView'

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const roles = ref<any[]>([])

// 表格视图：列显示 / 冻结 / 列宽与默认筛选按账号保存
const table = useTableView({
  pageKey: 'system.users',
  columns: [
    { key: 'username', label: '登录账号', width: 130 },
    { key: 'realName', label: '姓名', width: 120 },
    { key: 'phone', label: '手机号', width: 140 },
    { key: 'email', label: '邮箱', width: 170, flex: true, ellipsis: true },
    { key: 'roleName', label: '角色', width: 130 },
    { key: 'orgName', label: '所属组织 / 岗位', width: 170 },
    { key: 'employeeNo', label: '工号', width: 90 },
    { key: 'warehouseName', label: '所属仓库', width: 120 },
    { key: 'enabled', label: '状态', width: 110 },
    { key: 'lockState', label: '登录安全', width: 150, prop: '' },
    { key: 'lastLoginAt', label: '最近登录', width: 145 },
    { key: 'createdAt', label: '创建时间', width: 145 },
    { key: 'actions', label: '操作', width: 250, fixed: 'right', prop: '' }
  ],
  getFilters: () => ({ keyword: keyword.value }),
  setFilters: (payload) => {
    keyword.value = payload.keyword || ''
  },
  getPageSize: () => size.value,
  setPageSize: (value) => {
    size.value = value
  }
})

const reload = () => {
  page.value = 1
  load()
}

const drawer = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive<any>({
  username: '',
  password: '',
  realName: '',
  phone: '',
  email: '',
  role: '',
  warehouseId: null,
  warehouseName: '',
  orgId: null,
  orgName: '',
  position: '',
  employeeNo: '',
  enabled: true
})

const resetVisible = ref(false)
const resetRow = ref<any>(null)
const resetForm = reactive({ password: '', confirm: '' })
const resetting = ref(false)

// 工序技能：派工时只把持证人员列出来
const skillDrawer = ref(false)
const skillUser = ref<any>(null)
const skillSaving = ref(false)
const skillLoading = ref(false)
const skillRows = ref<any[]>([])
const skillKeyword = ref('')
const skillPage = ref(1)
const skillSize = ref(20)
const skillTotal = ref(0)
/** 已勾选的工序：跨页保留，保存时按它提交，不依赖当前页数据 */
const skillOwned = ref(new Map<number, string>())

const levelOptions = [
  { value: 'JUNIOR', label: '初级' },
  { value: 'NORMAL', label: '中级' },
  { value: 'SENIOR', label: '高级' }
]

const loadSkillRows = async () => {
  skillLoading.value = true
  try {
    const data = await api.get('/processes', {
      enabled: true,
      keyword: skillKeyword.value || undefined,
      page: skillPage.value,
      size: skillSize.value
    })
    const processes = Array.isArray(data) ? data : (data?.records || [])
    skillTotal.value = Array.isArray(data) ? processes.length : (data?.total || 0)
    skillRows.value = processes.map((process: any) => ({
      processId: process.id,
      code: process.code,
      name: process.name,
      inspectionMode: process.inspectionMode,
      checked: skillOwned.value.has(Number(process.id)),
      level: skillOwned.value.get(Number(process.id)) || 'NORMAL'
    }))
  } finally {
    skillLoading.value = false
  }
}

/** 勾选 / 取消勾选时同步到跨页选择表 */
const syncSkill = (row: any) => {
  if (row.checked) skillOwned.value.set(Number(row.processId), row.level || 'NORMAL')
  else skillOwned.value.delete(Number(row.processId))
}

const changeSkillLevel = (row: any) => {
  if (row.checked) skillOwned.value.set(Number(row.processId), row.level || 'NORMAL')
}

const searchSkills = () => {
  skillPage.value = 1
  loadSkillRows()
}

const changeSkillPage = (value: number) => {
  skillPage.value = value
  loadSkillRows()
}

const changeSkillSize = () => {
  skillPage.value = 1
  loadSkillRows()
}

const openSkills = async (row: any) => {
  skillUser.value = row
  skillDrawer.value = true
  skillKeyword.value = ''
  skillPage.value = 1
  skillRows.value = []
  skillOwned.value = new Map()
  skillLoading.value = true
  try {
    const owned = await api.get(`/system/users/${row.id}/skills`)
    skillOwned.value = new Map<number, string>(
      (owned || []).map((item: any) => [Number(item.processId), item.level || 'NORMAL'])
    )
  } finally {
    skillLoading.value = false
  }
  await loadSkillRows()
}

const saveSkills = async () => {
  if (!skillUser.value) return
  skillSaving.value = true
  try {
    await api.put(`/system/users/${skillUser.value.id}/skills`, {
      items: [...skillOwned.value.entries()].map(([processId, level]) => ({ processId, level }))
    })
    ElMessage.success('技能已保存')
    skillDrawer.value = false
  } finally {
    skillSaving.value = false
  }
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/system/users', {
      keyword: keyword.value,
      page: page.value,
      size: size.value
    })
    rows.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

const search = () => {
  page.value = 1
  load()
}

const loadRefs = async () => {
  roles.value = await api.get('/system/roles')
}

const openCreate = () => {
  editingId.value = null
  Object.assign(form, {
    username: '',
    password: '',
    realName: '',
    phone: '',
    email: '',
    role: roles.value[0]?.code || '',
    warehouseId: null,
    warehouseName: '',
    orgId: null,
    orgName: '',
    position: '',
    employeeNo: '',
    enabled: true
  })
  drawer.value = true
}

const openEdit = (row: any) => {
  editingId.value = row.id
  Object.assign(form, {
    username: row.username,
    password: '',
    realName: row.realName || '',
    phone: row.phone || '',
    email: row.email || '',
    role: row.role,
    warehouseId: row.warehouseId || null,
    warehouseName: row.warehouseName || '',
    orgId: row.orgId || null,
    orgName: row.orgName || '',
    position: row.position || '',
    employeeNo: row.employeeNo || '',
    enabled: row.enabled
  })
  drawer.value = true
}

const submit = async () => {
  if (!form.realName || !form.role) {
    ElMessage.warning('姓名和角色不能为空')
    return
  }
  if (!editingId.value) {
    if (!form.username.trim() || !form.password) {
      ElMessage.warning('请填写登录账号和初始密码')
      return
    }
  }
  saving.value = true
  try {
    const payload = {
      realName: form.realName,
      phone: form.phone,
      email: form.email,
      role: form.role,
      warehouseId: form.warehouseId || null,
      orgId: form.orgId || null,
      position: form.position,
      employeeNo: form.employeeNo,
      enabled: form.enabled
    }
    if (editingId.value) {
      await api.put(`/system/users/${editingId.value}`, payload)
    } else {
      const secret = await encryptPassword(form.password)
      await api.post('/system/users', { ...payload, username: form.username, ...secret })
    }
    ElMessage.success('用户已保存')
    drawer.value = false
    load()
  } finally {
    saving.value = false
  }
}

const toggleEnabled = async (row: any) => {
  const enabled = row.enabled
  try {
    await api.put(`/system/users/${row.id}`, {
      realName: row.realName,
      phone: row.phone,
      email: row.email,
      role: row.role,
      warehouseId: row.warehouseId || null,
      enabled
    })
    ElMessage.success(enabled ? `已启用 ${row.username}` : `已停用 ${row.username}`)
  } catch {
    row.enabled = !enabled
  }
}

const openReset = (row: any) => {
  resetRow.value = row
  resetForm.password = ''
  resetForm.confirm = ''
  resetVisible.value = true
}

/** 解锁被登录失败锁定的账号 */
const unlock = async (row: any) => {
  await api.post(`/system/users/${row.id}/unlock`)
  ElMessage.success(`已解锁 ${row.username}`)
  load()
}

const submitReset = async () => {
  if (!resetForm.password) {
    ElMessage.warning('请输入新密码')
    return
  }
  if (resetForm.password !== resetForm.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  resetting.value = true
  try {
    const secret = await encryptPassword(resetForm.password)
    await api.post(`/system/users/${resetRow.value.id}/reset-password`, secret)
    ElMessage.success('密码已重置')
    resetVisible.value = false
  } finally {
    resetting.value = false
  }
}

const changePage = (value: number) => {
  page.value = value
  load()
}

onMounted(async () => {
  await table.load()
  await loadRefs()
  load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-sub">维护登录账号、手机号、角色与所属仓库，账号支持用手机号登录</p>
      </div>
      <el-button type="primary" @click="openCreate">新建用户</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="账号 / 姓名 / 手机号"
          clearable
          style="width: 260px"
          @keyup.enter="reload"
        />
        <el-button type="primary" plain @click="reload">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/system/users" export-name="用户" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 个账号</span>
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
          <template #cell-username="{ row }"><span class="mono">{{ row.username }}</span></template>
          <template #cell-phone="{ row }">
            <span class="mono">{{ row.phone || '-' }}</span>
          </template>
          <template #cell-email="{ row }">{{ row.email || '-' }}</template>
          <template #cell-roleName="{ row }">
            <span class="status-tag primary"><i></i>{{ row.roleName }}</span>
          </template>
          <template #cell-orgName="{ row }">
            <div>{{ row.orgName || '未分配' }}</div>
            <div class="muted" style="font-size: 11px">{{ row.position || '—' }}</div>
          </template>
          <template #cell-employeeNo="{ row }">{{ row.employeeNo || '-' }}</template>
          <template #cell-warehouseName="{ row }">{{ row.warehouseName || '-' }}</template>
          <template #cell-enabled="{ row }">
            <el-switch v-model="row.enabled" inline-prompt active-text="启用" inactive-text="停用" @change="toggleEnabled(row)" />
          </template>
          <template #cell-lockState="{ row }">
            <span v-if="row.locked" class="status-tag danger"><i></i>锁定至 {{ row.lockedUntil }}</span>
            <span v-else-if="Number(row.failedAttempts || 0) > 0" class="status-tag warning">
              <i></i>失败 {{ row.failedAttempts }} 次
            </span>
            <span v-else class="muted">正常</span>
          </template>
          <template #cell-lastLoginAt="{ row }">
            <span class="mono muted">{{ row.lastLoginAt || '从未登录' }}</span>
          </template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="openSkills(row)">技能</el-button>
            <el-button link @click="openReset(row)">重置密码</el-button>
            <el-button v-if="row.locked" link type="danger" @click="unlock(row)">解锁</el-button>
          </template>
        </DataTable>
      </div>
    </div>

    <el-drawer append-to-body v-model="drawer" size="560px" :title="editingId ? '编辑用户' : '新建用户'">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="登录账号" required>
            <el-input v-model="form.username" :disabled="!!editingId" placeholder="如 zhangsan" />
          </el-form-item>
          <el-form-item v-if="!editingId" label="初始密码" required>
            <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位，含大小写 / 数字 / 符号三类" />
          </el-form-item>
          <el-form-item label="姓名" required>
            <el-input v-model="form.realName" placeholder="真实姓名" />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input v-model="form.phone" placeholder="可用于手机号登录" />
          </el-form-item>
          <el-form-item label="邮箱" class="full">
            <el-input v-model="form.email" placeholder="name@company.com" />
          </el-form-item>
          <el-form-item label="角色" required>
            <el-select v-model="form.role" filterable style="width: 100%">
              <el-option v-for="item in roles" :key="item.code" :label="`${item.name}（${item.code}）`" :value="item.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="所属组织">
            <ReferenceSelect
              v-model="form.orgId"
              url="/orgs/options"
              :initial-label="form.orgName"
              clearable
              placeholder="选择工厂 / 车间 / 班组"
              @select="(item) => form.orgName = item ? item.name : ''"
            />
          </el-form-item>
          <el-form-item label="岗位">
            <el-input v-model="form.position" placeholder="如 操作工 / 班组长 / 计划员" />
          </el-form-item>
          <el-form-item label="工号">
            <el-input v-model="form.employeeNo" placeholder="如 E0013，可留空" />
          </el-form-item>
          <el-form-item label="所属仓库">
            <ReferenceSelect
              v-model="form.warehouseId"
              url="/options/warehouses"
              :initial-label="form.warehouseName"
              clearable
              placeholder="不限定，输入编码或名称搜索"
              @select="(item) => form.warehouseName = item ? item.name : ''"
            />
          </el-form-item>
          <el-form-item label="账号状态" class="full">
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
            <span class="form-tip">仓库角色绑定仓库后，库存与流水仅显示该仓库数据。</span>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存用户</el-button>
      </template>
    </el-drawer>

    <el-dialog draggable append-to-body v-model="resetVisible" title="重置密码" width="440px">
      <el-form label-position="top">
        <el-form-item label="用户">
          <el-input :model-value="`${resetRow?.realName || ''}（${resetRow?.username || ''}）`" disabled />
        </el-form-item>
        <el-form-item label="新密码" required>
          <el-input v-model="resetForm.password" type="password" show-password placeholder="至少 8 位，含大小写 / 数字 / 符号三类" />
        </el-form-item>
        <el-form-item label="确认新密码" required>
          <el-input v-model="resetForm.confirm" type="password" show-password placeholder="再次输入新密码" @keyup.enter="submitReset" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="submitReset">确认重置</el-button>
      </template>
    </el-dialog>

    <el-drawer
      append-to-body
      v-model="skillDrawer"
      size="620px"
      :title="`工序技能 · ${skillUser?.realName || ''}`"
    >
      <div class="skill-tip">
        勾选的工序代表这个人能做。工序一旦有人配了技能，派工时就只允许派给持证人员；
        一道工序谁都没配，视为不限制，任何人都能派。
      </div>

      <div class="filter-bar">
        <el-input
          v-model="skillKeyword"
          placeholder="工序编码 / 名称"
          clearable
          style="width: 220px"
          @keyup.enter="searchSkills"
        />
        <el-button type="primary" plain @click="searchSkills">查询</el-button>
        <div class="spacer"></div>
        <span class="toolbar-tip">共 {{ skillTotal }} 道工序</span>
      </div>

      <el-table v-loading="skillLoading" :data="skillRows" size="small" max-height="60vh">
        <el-table-column label="可做" width="70" align="center">
          <template #default="{ row }">
            <el-checkbox v-model="row.checked" @change="syncSkill(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="code" label="工序编码" width="110">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="工序名称" min-width="140" />
        <el-table-column label="熟练度" width="130">
          <template #default="{ row }">
            <el-select
              v-model="row.level"
              size="small"
              :disabled="!row.checked"
              style="width: 100%"
              @change="changeSkillLevel(row)"
            >
              <el-option v-for="item in levelOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="skillPage"
          v-model:page-size="skillSize"
          :total="skillTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="changeSkillPage"
          @size-change="changeSkillSize"
        />
      </div>

      <template #footer>
        <el-button @click="skillDrawer = false">取消</el-button>
        <el-button type="primary" :loading="skillSaving" @click="saveSkills">保存技能</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.form-tip {
  margin-left: 12px;
  color: var(--muted);
  font-size: 11px;
}

.skill-tip {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: #f7f9fc;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.7;
}
</style>
