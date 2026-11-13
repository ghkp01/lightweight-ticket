<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, nextCode } from '../../api'
import ReferenceSelect from '../../components/ReferenceSelect.vue'

type OrgNode = {
  id: number
  parentId: number | null
  code: string
  name: string
  type: string
  level: number
  sort?: number
  leader?: string | null
  phone?: string | null
  enabled?: boolean
  remark?: string | null
  parentName?: string
  memberCount?: number
  children?: OrgNode[]
}

const orgTypeMap: Record<string, string> = {
  PLANT: '工厂',
  WORKSHOP: '车间',
  TEAM: '班组',
  DEPT: '职能部门'
}

const orgTypeTag: Record<string, string> = {
  PLANT: 'primary',
  WORKSHOP: 'success',
  TEAM: 'warning',
  DEPT: 'info'
}

const orgTypeOptionsAll = [
  { value: 'PLANT', label: '工厂' },
  { value: 'WORKSHOP', label: '车间' },
  { value: 'TEAM', label: '班组' },
  { value: 'DEPT', label: '职能部门' }
]

const wcTypeMap: Record<string, string> = {
  MACHINE: '设备线',
  HAND: '手工工位',
  OUTSOURCE: '外协'
}

const wcTypeTag: Record<string, string> = {
  MACHINE: 'primary',
  HAND: 'success',
  OUTSOURCE: 'warning'
}

const wcTypeOptions = [
  { value: 'MACHINE', label: '设备线' },
  { value: 'HAND', label: '手工工位' },
  { value: 'OUTSOURCE', label: '外协' }
]

const activeTab = ref('org')

/* ------------------------------ 组织树 ------------------------------ */

const treeProps = { label: 'name', children: 'children' }
const treeRef = ref<any>(null)
const tree = ref<OrgNode[]>([])
const treeLoading = ref(false)
const orgIndex = new Map<number, OrgNode>()

const rebuildIndex = () => {
  orgIndex.clear()
  const walk = (nodes: OrgNode[]) => {
    nodes.forEach((node) => {
      orgIndex.set(node.id, node)
      if (node.children && node.children.length) walk(node.children)
    })
  }
  walk(tree.value)
}

const loadTree = async () => {
  treeLoading.value = true
  try {
    const data = await api.get('/orgs/tree')
    tree.value = Array.isArray(data) ? data : []
    rebuildIndex()
  } catch {
    tree.value = []
    orgIndex.clear()
  } finally {
    treeLoading.value = false
  }
}

/* ------------------------------ 组织详情 ------------------------------ */

const selectedId = ref<number | null>(null)
const detail = ref<any>(null)
const detailLoading = ref(false)
const memberRows = ref<any[]>([])
const memberTotal = ref(0)
const memberPage = ref(1)
const memberSize = ref(20)

const parentLabel = (row: any) => {
  if (!row) return '-'
  if (row.parentId === null || row.parentId === undefined) return '无（顶级组织）'
  return orgIndex.get(row.parentId)?.name || row.parentName || `#${row.parentId}`
}

const loadDetail = async (id: number) => {
  detailLoading.value = true
  try {
    const data = await api.get(`/orgs/${id}`)
    if (selectedId.value === id) detail.value = data
  } catch {
    if (selectedId.value === id) detail.value = null
  } finally {
    if (selectedId.value === id) detailLoading.value = false
  }
}

const loadMembers = async (id: number) => {
  try {
    const data = await api.get(`/orgs/${id}/members`, {
      page: memberPage.value,
      size: memberSize.value
    })
    if (selectedId.value !== id) return
    memberRows.value = data?.records || []
    memberTotal.value = data?.total || 0
  } catch {
    if (selectedId.value === id) {
      memberRows.value = []
      memberTotal.value = 0
    }
  }
}

const changeMemberPage = (value: number) => {
  memberPage.value = value
  if (selectedId.value) loadMembers(selectedId.value)
}

const changeMemberSize = () => {
  memberPage.value = 1
  if (selectedId.value) loadMembers(selectedId.value)
}

const selectOrg = async (id: number) => {
  selectedId.value = id
  treeRef.value?.setCurrentKey(id)
  memberPage.value = 1
  await Promise.all([loadDetail(id), loadMembers(id)])
}

const onNodeClick = (data: OrgNode) => {
  if (data && data.id !== selectedId.value) selectOrg(data.id)
}

/* ------------------------------ 组织弹窗 ------------------------------ */

const orgDialog = ref(false)
const orgMode = ref<'create' | 'edit'>('create')
const editingOrgId = ref<number | null>(null)
const orgSaving = ref(false)
const orgParentId = ref<number | null>(null)
const orgParentType = ref('')
const orgParentName = ref('无（顶级组织）')
const orgForm = reactive<any>({
  code: '',
  name: '',
  type: 'PLANT',
  sort: 0,
  leader: '',
  phone: '',
  enabled: true,
  remark: ''
})

const childTypes = (parentType: string) => {
  if (parentType === 'PLANT') return ['WORKSHOP', 'DEPT']
  if (parentType === 'WORKSHOP') return ['TEAM', 'DEPT']
  return []
}

const canCreateChild = computed(() => !!detail.value && childTypes(detail.value.type).length > 0)

const childTip = computed(() => {
  if (!detail.value) return '请选择左侧组织'
  if (!canCreateChild.value) return '班组与职能部门为末级，不能再新建下级'
  const labels = childTypes(detail.value.type).map((type) => orgTypeMap[type]).join(' / ')
  return `可在「${detail.value.name}」下新建：${labels}`
})

const orgTypeOptions = computed(() => {
  let allowed: string[] = []
  if (orgParentId.value === null) {
    allowed = ['PLANT']
  } else {
    allowed = childTypes(orgParentType.value)
  }
  if (editingOrgId.value !== null && orgForm.type && !allowed.includes(orgForm.type)) {
    allowed = [orgForm.type, ...allowed]
  }
  if (!allowed.length && orgForm.type) allowed = [orgForm.type]
  return orgTypeOptionsAll.filter((item) => allowed.includes(item.value))
})

const orgTypeLocked = computed(() => orgMode.value !== 'edit' || orgTypeOptions.value.length <= 1)

const orgParentLabel = computed(() => {
  if (orgParentId.value === null) return '无（顶级组织）'
  return orgIndex.get(orgParentId.value)?.name || orgParentName.value || `#${orgParentId.value}`
})

const openCreatePlant = () => {
  orgMode.value = 'create'
  editingOrgId.value = null
  orgParentId.value = null
  orgParentType.value = ''
  orgParentName.value = '无（顶级组织）'
  Object.assign(orgForm, {
    code: '',
    name: '',
    type: 'PLANT',
    sort: 0,
    leader: '',
    phone: '',
    enabled: true,
    remark: ''
  })
  orgDialog.value = true
}

const openCreateChild = () => {
  if (!detail.value) {
    ElMessage.warning('请先选择上级组织')
    return
  }
  const types = childTypes(detail.value.type)
  if (!types.length) {
    ElMessage.warning('该组织为末级，不能再新建下级')
    return
  }
  orgMode.value = 'create'
  editingOrgId.value = null
  orgParentId.value = detail.value.id
  orgParentType.value = detail.value.type
  orgParentName.value = detail.value.name
  Object.assign(orgForm, {
    code: '',
    name: '',
    type: types[0],
    sort: 0,
    leader: '',
    phone: '',
    enabled: true,
    remark: ''
  })
  orgDialog.value = true
}

const openEditOrg = () => {
  if (!detail.value) return
  const row = detail.value
  orgMode.value = 'edit'
  editingOrgId.value = row.id
  orgParentId.value = row.parentId ?? null
  orgParentType.value = row.parentId ? orgIndex.get(row.parentId)?.type || '' : ''
  orgParentName.value = row.parentId
    ? orgIndex.get(row.parentId)?.name || row.parentName || ''
    : '无（顶级组织）'
  Object.assign(orgForm, {
    code: row.code,
    name: row.name,
    type: row.type,
    sort: row.sort ?? 0,
    leader: row.leader || '',
    phone: row.phone || '',
    enabled: row.enabled !== false,
    remark: row.remark || ''
  })
  orgDialog.value = true
}

const submitOrg = async () => {
  if (!orgForm.code.trim()) {
    ElMessage.warning('请填写组织编码')
    return
  }
  if (!orgForm.name.trim()) {
    ElMessage.warning('请填写组织名称')
    return
  }
  orgSaving.value = true
  try {
    const payload = {
      code: orgForm.code.trim(),
      name: orgForm.name.trim(),
      type: orgForm.type,
      parentId: orgParentId.value,
      sort: Number(orgForm.sort) || 0,
      leader: orgForm.leader.trim(),
      phone: orgForm.phone.trim(),
      enabled: orgForm.enabled,
      remark: orgForm.remark
    }
    let createdId: number | null = null
    if (editingOrgId.value !== null) {
      await api.put(`/orgs/${editingOrgId.value}`, payload)
    } else {
      const id = await api.post('/orgs', payload)
      createdId = id === null || id === undefined ? null : Number(id)
    }
    ElMessage.success('组织已保存')
    orgDialog.value = false
    await loadTree()
    const target = createdId ?? editingOrgId.value ?? selectedId.value
    if (target !== null) {
      await nextTick()
      await selectOrg(target)
    }
  } finally {
    orgSaving.value = false
  }
}

const removeOrg = async () => {
  const row = detail.value
  if (!row) return
  try {
    await ElMessageBox.confirm(
      `确认删除组织「${row.name}」？存在下级组织、成员或工作中心时将无法删除。`,
      '删除确认',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.delete(`/orgs/${row.id}`)
  } catch {
    return
  }
  ElMessage.success('组织已删除')
  detail.value = null
  selectedId.value = null
  await loadTree()
  const first = tree.value[0]
  if (first) {
    await nextTick()
    await selectOrg(first.id)
  }
}

/* ------------------------------ 工作中心 ------------------------------ */

const wcLoading = ref(false)
const wcRows = ref<any[]>([])
const wcTotal = ref(0)
const wcPage = ref(1)
const wcSize = ref(20)
const wcDialog = ref(false)
const editingWcId = ref<number | null>(null)
const wcSaving = ref(false)
const wcForm = reactive<any>({
  code: '',
  name: '',
  orgId: null,
  orgName: '',
  type: 'MACHINE',
  dailyCapacityMinutes: 480,
  enabled: true,
  remark: ''
})

const loadWorkCenters = async () => {
  wcLoading.value = true
  try {
    const data = await api.get('/work-centers', {
      page: wcPage.value,
      size: wcSize.value
    })
    wcRows.value = data?.records || []
    wcTotal.value = data?.total || 0
  } catch {
    wcRows.value = []
    wcTotal.value = 0
  } finally {
    wcLoading.value = false
  }
}

const changeWcPage = (value: number) => {
  wcPage.value = value
  loadWorkCenters()
}

const changeWcSize = () => {
  wcPage.value = 1
  loadWorkCenters()
}

/** 删除后刷新：当前页空了就回上一页 */
const refreshWorkCenters = async () => {
  await loadWorkCenters()
  if (!wcRows.value.length && wcPage.value > 1) {
    wcPage.value -= 1
    await loadWorkCenters()
  }
}

const openCreateWc = async () => {
  editingWcId.value = null
  Object.assign(wcForm, {
    code: '',
    name: '',
    orgId: null,
    orgName: '',
    type: 'MACHINE',
    dailyCapacityMinutes: 480,
    enabled: true,
    remark: ''
  })
  try {
    wcForm.code = await nextCode('WC')
  } catch {
    wcForm.code = ''
  }
  wcDialog.value = true
}

const openEditWc = async (row: any) => {
  editingWcId.value = row.id
  Object.assign(wcForm, {
    code: row.code,
    name: row.name,
    orgId: row.orgId ?? null,
    orgName: row.orgName || '',
    type: row.type || 'MACHINE',
    dailyCapacityMinutes: row.dailyCapacityMinutes ?? 480,
    enabled: row.enabled !== false,
    remark: row.remark || ''
  })
  wcDialog.value = true
}

const submitWc = async () => {
  if (!wcForm.name.trim()) {
    ElMessage.warning('请填写工作中心名称')
    return
  }
  const capacity = Number(wcForm.dailyCapacityMinutes)
  if (!Number.isFinite(capacity) || capacity < 1 || capacity > 1440) {
    ElMessage.warning('日产能需在 1 到 1440 分钟之间')
    return
  }
  wcSaving.value = true
  try {
    const payload = {
      code: wcForm.code.trim(),
      name: wcForm.name.trim(),
      orgId: wcForm.orgId || null,
      type: wcForm.type,
      dailyCapacityMinutes: capacity,
      enabled: wcForm.enabled,
      remark: wcForm.remark
    }
    if (editingWcId.value !== null) {
      await api.put(`/work-centers/${editingWcId.value}`, payload)
    } else {
      await api.post('/work-centers', payload)
    }
    ElMessage.success('工作中心已保存')
    wcDialog.value = false
    loadWorkCenters()
  } finally {
    wcSaving.value = false
  }
}

const removeWc = async (row: any) => {
  try {
    await ElMessageBox.confirm(
      `确认删除工作中心「${row.name}」？已被工序引用的工作中心无法删除。`,
      '删除确认',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.delete(`/work-centers/${row.id}`)
  } catch {
    return
  }
  ElMessage.success('工作中心已删除')
  refreshWorkCenters()
}

const onTabChange = (name: any) => {
  if (name === 'workcenter') loadWorkCenters()
}

/* ------------------------------ 设备台账 ------------------------------ */

const deviceDrawer = ref(false)
const deviceCenter = ref<any>(null)
const deviceRows = ref<any[]>([])
const deviceLoading = ref(false)
const deviceDialog = ref(false)
const editingDeviceId = ref<number | null>(null)
const deviceSaving = ref(false)
const deviceForm = reactive<any>({
  code: '',
  name: '',
  capacityMinutes: 480,
  status: 'RUNNING',
  enabled: true,
  remark: ''
})

const deviceStatusMap: Record<string, { label: string; cls: string }> = {
  RUNNING: { label: '运行', cls: 'success' },
  IDLE: { label: '待机', cls: 'info' },
  MAINTENANCE: { label: '维修', cls: 'warning' },
  OFFLINE: { label: '停机', cls: 'danger' }
}

const deviceStatusOptions = Object.keys(deviceStatusMap).map((value) => ({
  value,
  label: deviceStatusMap[value].label
}))

const openDevices = async (row: any) => {
  deviceCenter.value = row
  deviceDrawer.value = true
  await loadDevices()
}

const loadDevices = async () => {
  if (!deviceCenter.value) return
  deviceLoading.value = true
  try {
    const data = await api.get(`/work-centers/${deviceCenter.value.id}/devices`)
    deviceRows.value = Array.isArray(data) ? data : []
  } catch {
    deviceRows.value = []
  } finally {
    deviceLoading.value = false
  }
}

const openCreateDevice = () => {
  editingDeviceId.value = null
  Object.assign(deviceForm, {
    code: '',
    name: '',
    capacityMinutes: 480,
    status: 'RUNNING',
    enabled: true,
    remark: ''
  })
  deviceDialog.value = true
}

const openEditDevice = (row: any) => {
  editingDeviceId.value = row.id
  Object.assign(deviceForm, {
    code: row.code,
    name: row.name,
    capacityMinutes: Number(row.capacityMinutes ?? 480),
    status: row.status || 'RUNNING',
    enabled: row.enabled !== false,
    remark: row.remark || ''
  })
  deviceDialog.value = true
}

const submitDevice = async () => {
  if (!deviceForm.code.trim() || !deviceForm.name.trim()) {
    ElMessage.warning('请填写设备编码与名称')
    return
  }
  const capacity = Number(deviceForm.capacityMinutes)
  if (!Number.isFinite(capacity) || capacity < 0 || capacity > 1440) {
    ElMessage.warning('设备日产能需在 0 到 1440 分钟之间')
    return
  }
  deviceSaving.value = true
  try {
    const payload = {
      code: deviceForm.code.trim(),
      name: deviceForm.name.trim(),
      capacityMinutes: capacity,
      status: deviceForm.status,
      enabled: deviceForm.enabled,
      remark: deviceForm.remark
    }
    if (editingDeviceId.value !== null) {
      await api.put(`/devices/${editingDeviceId.value}`, payload)
    } else {
      await api.post(`/work-centers/${deviceCenter.value.id}/devices`, payload)
    }
    ElMessage.success('设备已保存')
    deviceDialog.value = false
    await loadDevices()
    loadWorkCenters()
  } finally {
    deviceSaving.value = false
  }
}

const removeDevice = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确认删除设备「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.delete(`/devices/${row.id}`)
  } catch {
    return
  }
  ElMessage.success('设备已删除')
  await loadDevices()
  loadWorkCenters()
}

onMounted(async () => {
  await loadTree()
  const first = tree.value[0]
  if (first) {
    await nextTick()
    await selectOrg(first.id)
  }
  loadWorkCenters()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">组织架构</h2>
        <p class="page-sub">维护工厂、车间、班组与职能部门，并按组织配置工作中心的日产能</p>
      </div>
    </div>

    <div class="panel org-panel">
      <el-tabs v-model="activeTab" class="org-tabs" @tab-change="onTabChange">
        <el-tab-pane label="组织架构" name="org">
          <div class="tab-toolbar">
            <el-button v-perm="'org:save'" type="primary" @click="openCreatePlant">新建工厂</el-button>
            <el-button v-perm="'org:save'" :disabled="!canCreateChild" @click="openCreateChild">新建下级</el-button>
            <el-button v-perm="'org:save'" :disabled="!detail" @click="openEditOrg">编辑</el-button>
            <el-button v-perm="'org:save'" type="danger" plain :disabled="!detail" @click="removeOrg">删除</el-button>
            <div class="spacer"></div>
            <span class="toolbar-tip">{{ childTip }}</span>
          </div>

          <div class="org-layout">
            <div v-loading="treeLoading" class="org-tree">
              <el-tree
                ref="treeRef"
                :data="tree"
                node-key="id"
                default-expand-all
                highlight-current
                :expand-on-click-node="false"
                :props="treeProps"
                @node-click="onNodeClick"
              >
                <template #default="{ data }">
                  <span class="tree-node">
                    <span class="tree-name">{{ data.name }}</span>
                    <span class="tree-type" :class="orgTypeTag[data.type] || 'info'">
                      {{ orgTypeMap[data.type] || data.type }}
                    </span>
                  </span>
                </template>
              </el-tree>
              <el-empty v-if="!treeLoading && !tree.length" description="暂无组织数据" :image-size="60" />
            </div>

            <div v-loading="detailLoading" class="org-detail">
              <template v-if="detail">
                <div class="detail-head">
                  <div>
                    <div class="detail-title">
                      {{ detail.name }}
                      <span class="mono muted">{{ detail.code }}</span>
                    </div>
                    <div class="detail-sub">{{ orgTypeMap[detail.type] || detail.type }} · 第 {{ detail.level }} 层</div>
                  </div>
                  <div class="detail-tags">
                    <span class="status-tag" :class="detail.enabled === false ? 'info' : 'success'">
                      <i></i>{{ detail.enabled === false ? '已停用' : '启用中' }}
                    </span>
                  </div>
                </div>

                <div class="detail-grid org-detail-grid">
                  <div class="detail-item">
                    <div class="label">组织编码</div>
                    <div class="value mono">{{ detail.code || '-' }}</div>
                  </div>
                  <div class="detail-item">
                    <div class="label">组织类型</div>
                    <div class="value">{{ orgTypeMap[detail.type] || detail.type }}</div>
                  </div>
                  <div class="detail-item">
                    <div class="label">层级</div>
                    <div class="value">第 {{ detail.level }} 层</div>
                  </div>
                  <div class="detail-item">
                    <div class="label">上级组织</div>
                    <div class="value">{{ parentLabel(detail) }}</div>
                  </div>
                  <div class="detail-item">
                    <div class="label">负责人</div>
                    <div class="value">{{ detail.leader || '-' }}</div>
                  </div>
                  <div class="detail-item">
                    <div class="label">联系电话</div>
                    <div class="value mono">{{ detail.phone || '-' }}</div>
                  </div>
                  <div class="detail-item">
                    <div class="label">成员数</div>
                    <div class="value num">{{ detail.memberCount ?? memberTotal }}</div>
                  </div>
                  <div class="detail-item">
                    <div class="label">排序</div>
                    <div class="value num">{{ detail.sort ?? 0 }}</div>
                  </div>
                  <div class="detail-item full">
                    <div class="label">备注</div>
                    <div class="value">{{ detail.remark || '-' }}</div>
                  </div>
                </div>

                <div class="members-head">
                  <h4>组织成员</h4>
                  <span class="toolbar-tip">共 {{ detail.memberCount ?? memberTotal }} 人，仅显示本组织直属成员</span>
                </div>

                <el-table :data="memberRows" stripe empty-text="该组织暂无成员" max-height="340">
                  <el-table-column prop="realName" label="姓名" width="100">
                    <template #default="{ row }">{{ row.realName || '-' }}</template>
                  </el-table-column>
                  <el-table-column prop="username" label="登录账号" width="120">
                    <template #default="{ row }"><span class="mono">{{ row.username }}</span></template>
                  </el-table-column>
                  <el-table-column prop="employeeNo" label="工号" width="110">
                    <template #default="{ row }"><span class="mono">{{ row.employeeNo || '-' }}</span></template>
                  </el-table-column>
                  <el-table-column prop="position" label="岗位" width="110">
                    <template #default="{ row }">{{ row.position || '-' }}</template>
                  </el-table-column>
                  <el-table-column prop="roleName" label="角色" width="130">
                    <template #default="{ row }">
                      <span class="status-tag primary"><i></i>{{ row.roleName || '-' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="phone" label="手机号" width="140">
                    <template #default="{ row }"><span class="mono">{{ row.phone || '-' }}</span></template>
                  </el-table-column>
                  <el-table-column label="状态" width="90">
                    <template #default="{ row }">
                      <span class="status-tag" :class="row.enabled === false ? 'info' : 'success'">
                        <i></i>{{ row.enabled === false ? '停用' : '启用' }}
                      </span>
                    </template>
                  </el-table-column>
                </el-table>
                <div class="pager">
                  <el-pagination
                    v-model:current-page="memberPage"
                    v-model:page-size="memberSize"
                    :total="memberTotal"
                    :page-sizes="[10, 20, 50, 100]"
                    layout="total, sizes, prev, pager, next"
                    @current-change="changeMemberPage"
                    @size-change="changeMemberSize"
                  />
                </div>
              </template>
              <el-empty v-else description="请选择左侧组织查看详情" :image-size="80" />
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="工作中心" name="workcenter">
          <div class="tab-toolbar">
            <el-button v-perm="'workcenter:save'" type="primary" @click="openCreateWc">新建工作中心</el-button>
            <div class="spacer"></div>
            <span class="toolbar-tip">共 {{ wcTotal }} 个工作中心</span>
          </div>

          <div class="wc-table-wrap">
            <el-table v-loading="wcLoading" :data="wcRows" stripe empty-text="暂无工作中心">
              <el-table-column prop="code" label="编码" width="130">
                <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
              </el-table-column>
              <el-table-column prop="name" label="名称" width="150" show-overflow-tooltip />
              <el-table-column prop="orgName" label="所属组织" width="130">
                <template #default="{ row }">{{ row.orgName || '-' }}</template>
              </el-table-column>
              <el-table-column label="类型" width="120">
                <template #default="{ row }">
                  <span class="status-tag" :class="wcTypeTag[row.type] || 'info'">
                    <i></i>{{ wcTypeMap[row.type] || row.type }}
                  </span>
                  <div v-if="row.type === 'OUTSOURCE'" class="muted wc-sub">不占厂内产能</div>
                </template>
              </el-table-column>
              <el-table-column label="日产能" width="140" align="right">
                <template #default="{ row }">
                  <span class="num">{{ row.dailyCapacityMinutes }}</span>
                  <span class="muted"> 分钟</span>
                  <div v-if="row.deviceCount > 0" class="muted wc-sub">
                    可用设备 {{ row.deviceCapacityMinutes }} 分钟
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="设备" width="100">
                <template #default="{ row }">
                  <span v-if="row.deviceCount > 0" class="num">{{ row.activeDeviceCount }} / {{ row.deviceCount }}</span>
                  <span v-else class="muted">未维护</span>
                </template>
              </el-table-column>
              <el-table-column prop="processNames" label="关联工序" min-width="210" show-overflow-tooltip>
                <template #default="{ row }">{{ row.processNames || '-' }}</template>
              </el-table-column>
              <el-table-column label="工序数" width="90" align="right">
                <template #default="{ row }"><span class="num">{{ row.processCount ?? 0 }}</span></template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <span class="status-tag" :class="row.enabled === false ? 'info' : 'success'">
                    <i></i>{{ row.enabled === false ? '停用' : '启用' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="180" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openDevices(row)">设备</el-button>
                  <el-button v-perm="'workcenter:save'" link type="primary" @click="openEditWc(row)">编辑</el-button>
                  <el-button v-perm="'workcenter:save'" link type="danger" @click="removeWc(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div class="pager">
            <el-pagination
              v-model:current-page="wcPage"
              v-model:page-size="wcSize"
              :total="wcTotal"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @current-change="changeWcPage"
              @size-change="changeWcSize"
            />
          </div>

          <div class="wc-note">
            工作中心是承接产能的执行单元：同一中心的工序共享日产能；维护了设备的中心，排产按「运行 / 待机」设备的产能之和折算，
            但不超过中心日产能。外协中心不占厂内产能。
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog draggable append-to-body v-model="orgDialog" :title="editingOrgId !== null ? '编辑组织' : '新建组织'" width="620px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="组织编码" required>
            <el-input v-model="orgForm.code" maxlength="32" placeholder="如 WS01" />
          </el-form-item>
          <el-form-item label="组织名称" required>
            <el-input v-model="orgForm.name" maxlength="64" placeholder="如 一号车间" />
          </el-form-item>
          <el-form-item label="组织类型" required>
            <el-select v-model="orgForm.type" :disabled="orgTypeLocked" style="width: 100%">
              <el-option
                v-for="item in orgTypeOptions"
                :key="item.value"
                :label="`${item.label}（${item.value}）`"
                :value="item.value"
              />
            </el-select>
            <span v-if="orgTypeLocked" class="form-tip">组织类型按上级组织自动锁定</span>
          </el-form-item>
          <el-form-item label="上级组织">
            <el-input :model-value="orgParentLabel" disabled placeholder="无（顶级组织）" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number
              v-model="orgForm.sort"
              :min="0"
              :max="9999"
              :precision="0"
              controls-position="right"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="负责人">
            <el-input v-model="orgForm.leader" maxlength="64" placeholder="负责人姓名" />
          </el-form-item>
          <el-form-item label="联系电话">
            <el-input v-model="orgForm.phone" maxlength="20" placeholder="手机号或座机" />
          </el-form-item>
          <el-form-item label="启用状态">
            <el-switch v-model="orgForm.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input
              v-model="orgForm.remark"
              type="textarea"
              :rows="2"
              maxlength="255"
              show-word-limit
              placeholder="组织职责或补充说明"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="orgDialog = false">取消</el-button>
        <el-button type="primary" :loading="orgSaving" @click="submitOrg">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog draggable
      append-to-body
      v-model="wcDialog"
      :title="editingWcId !== null ? '编辑工作中心' : '新建工作中心'"
      width="560px"
    >
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="工作中心编码">
            <el-input v-model="wcForm.code" maxlength="32" placeholder="留空自动生成，如 WC001" />
          </el-form-item>
          <el-form-item label="工作中心名称" required>
            <el-input v-model="wcForm.name" maxlength="64" placeholder="如 下料中心" />
          </el-form-item>
          <el-form-item label="所属组织" class="full">
            <ReferenceSelect
              v-model="wcForm.orgId"
              url="/orgs/options"
              :initial-label="wcForm.orgName"
              clearable
              placeholder="输入组织编码或名称搜索"
              @select="(item) => wcForm.orgName = item ? item.name : ''"
            />
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="wcForm.type" style="width: 100%">
              <el-option v-for="item in wcTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="日产能（分钟）" required>
            <el-input-number
              v-model="wcForm.dailyCapacityMinutes"
              :min="1"
              :max="1440"
              :step="30"
              :precision="0"
              controls-position="right"
              style="width: 100%"
            />
            <span class="form-tip">取值范围 1-1440 分钟，默认 480 分钟</span>
          </el-form-item>
          <el-form-item label="启用状态" class="full">
            <el-switch v-model="wcForm.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input
              v-model="wcForm.remark"
              type="textarea"
              :rows="2"
              maxlength="255"
              show-word-limit
              placeholder="设备、工位或外协说明"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="wcDialog = false">取消</el-button>
        <el-button type="primary" :loading="wcSaving" @click="submitWc">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer
      append-to-body
      v-model="deviceDrawer"
      size="880px"
      :title="`设备台账 · ${deviceCenter?.name || ''}`"
    >
      <div class="device-head">
        <div class="device-tip">
          设备是工作中心内部的产能单元：运行与待机计入可用产能，维修与停机不计。中心排产容量取
          「可用设备产能之和」与「中心日产能」的较小值；没有维护设备时直接用中心日产能。
        </div>
        <el-button v-perm="'device:save'" type="primary" size="small" @click="openCreateDevice">新增设备</el-button>
      </div>

      <el-table v-loading="deviceLoading" :data="deviceRows" size="small" empty-text="还没有设备，可先不维护">
        <el-table-column prop="code" label="设备编码" width="120">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="设备名称" min-width="150" show-overflow-tooltip />
        <el-table-column label="日产能" width="110" align="right">
          <template #default="{ row }"><span class="num">{{ row.capacityMinutes }}</span> 分钟</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="status-tag" :class="deviceStatusMap[row.status]?.cls || 'info'">
              <i></i>{{ deviceStatusMap[row.status]?.label || row.status }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <span class="status-tag" :class="row.enabled === false ? 'info' : 'success'">
              <i></i>{{ row.enabled === false ? '停用' : '启用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-perm="'device:save'" link type="primary" @click="openEditDevice(row)">编辑</el-button>
            <el-button v-perm="'device:save'" link type="danger" @click="removeDevice(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="deviceDrawer = false">关闭</el-button>
      </template>
    </el-drawer>

    <el-dialog draggable
      append-to-body
      v-model="deviceDialog"
      :title="editingDeviceId !== null ? '编辑设备' : '新增设备'"
      width="560px"
    >
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="设备编码" required>
            <el-input v-model="deviceForm.code" maxlength="32" placeholder="如 CNC-01" />
          </el-form-item>
          <el-form-item label="设备名称" required>
            <el-input v-model="deviceForm.name" maxlength="64" placeholder="如 加工中心 01" />
          </el-form-item>
          <el-form-item label="日产能（分钟）">
            <el-input-number
              v-model="deviceForm.capacityMinutes"
              :min="0"
              :max="1440"
              :step="30"
              :precision="0"
              controls-position="right"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="设备状态">
            <el-select v-model="deviceForm.status" style="width: 100%">
              <el-option v-for="item in deviceStatusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="启用状态">
            <el-switch v-model="deviceForm.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="deviceForm.remark" maxlength="128" placeholder="型号、供应商或点检说明" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="deviceDialog = false">取消</el-button>
        <el-button type="primary" :loading="deviceSaving" @click="submitDevice">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.org-panel {
  padding: 4px 16px 16px;
}

.org-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.org-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--line);
}

.org-tabs :deep(.el-tabs__item) {
  height: 46px;
  font-size: 13px;
  font-weight: 500;
}

.tab-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 14px 0;
  border-bottom: 1px solid var(--line);
}

.tab-toolbar .spacer {
  flex: 1;
}

.org-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 16px;
  padding-top: 16px;
}

.org-tree {
  min-height: 460px;
  max-height: 620px;
  overflow: auto;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfcfe;
}

.org-tree :deep(.el-tree) {
  background: transparent;
}

.org-tree :deep(.el-tree-node__content) {
  height: 30px;
  border-radius: 6px;
}

.tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding-right: 4px;
}

.tree-name {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.tree-type {
  flex: 0 0 auto;
  padding: 0 6px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--muted);
  font-size: 10px;
  line-height: 16px;
}

.tree-type.primary {
  color: #1d4ed8;
  border-color: #d1e0ff;
  background: #eff4ff;
}

.tree-type.success {
  color: #027a48;
  border-color: #abefc6;
  background: #ecfdf3;
}

.tree-type.warning {
  color: #b54708;
  border-color: #fedf89;
  background: #fffaeb;
}

.org-detail {
  min-width: 0;
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
}

.detail-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
}

.detail-title .mono {
  font-size: 12px;
  font-weight: 400;
}

.detail-sub {
  margin-top: 6px;
  color: var(--muted);
  font-size: 11px;
}

.detail-tags {
  display: flex;
  gap: 8px;
}

.org-detail-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  padding: 14px 0 4px;
}

.org-detail-grid .detail-item.full {
  grid-column: 1 / -1;
}

.members-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 8px 0 10px;
}

.members-head h4 {
  margin: 0;
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 600;
}

.wc-table-wrap {
  padding-top: 4px;
}

.wc-sub {
  font-size: 11px;
  margin-top: 2px;
}

.wc-note {
  margin-top: 12px;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}

.device-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 14px;
}

.device-tip {
  flex: 1;
  padding: 10px 12px;
  background: #f7f9fc;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.7;
}

.form-tip {
  display: block;
  width: 100%;
  margin-top: 6px;
  color: var(--muted);
  font-size: 11px;
}

@media (max-width: 1100px) {
  .org-layout {
    grid-template-columns: 1fr;
  }

  .org-tree {
    min-height: 220px;
    max-height: 320px;
  }

  .org-detail-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
