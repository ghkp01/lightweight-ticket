<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { api } from '../api'
import { encryptPasswordPair } from '../utils/crypto'
import { noticeTarget, openNoticeTarget } from '../utils/noticeLink'
import { startDesktopNotice, stopDesktopNotice, desktopEnabled } from '../utils/desktopNotify'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

onMounted(() => {
  auth.refresh()
  loadNoticeSummary()
  timer = window.setInterval(loadNoticeSummary, 60000)
  // 开了桌面通知就在后台轮询，有新消息弹系统通知
  startDesktopNotice(router, () => {
    loadNoticeSummary()
    if (noticeOpen.value) openNotices()
  })
  window.addEventListener('keydown', onMenuHotkey)
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
  stopDesktopNotice()
  window.removeEventListener('keydown', onMenuHotkey)
})

// 站内通知：到工序待办、不良待处置、返修建单等节点由服务端推送
const notices = ref<any[]>([])
const noticeOpen = ref(false)
const noticeLoading = ref(false)
const noticeSummary = ref<{ unread: number; total: number }>({ unread: 0, total: 0 })
const desktopNoticeOn = ref(desktopEnabled())
let timer: number | undefined

const noticeCategoryMap: Record<string, { label: string; cls: string }> = {
  TASK: { label: '待办', cls: 'warning' },
  ALERT: { label: '预警', cls: 'danger' },
  INFO: { label: '通知', cls: 'info' }
}

const categoryOf = (value: any) =>
  noticeCategoryMap[String(value || '')] || { label: '通知', cls: 'info' }

const loadNoticeSummary = async () => {
  try {
    noticeSummary.value = await api.get('/notices/summary')
  } catch {
    // 通知接口异常不影响主流程
  }
}

const openNotices = async () => {
  noticeOpen.value = true
  noticeLoading.value = true
  desktopNoticeOn.value = desktopEnabled()
  try {
    notices.value = await api.get('/notices', { limit: 50 })
  } finally {
    noticeLoading.value = false
  }
  loadNoticeSummary()
}

const readNotice = async (item: any) => {
  if (!item.readAt) {
    await api.put(`/notices/${item.id}/read`)
    item.readAt = '已读'
    loadNoticeSummary()
  }
}

/** 点击通知：先标已读，再跳到能直接处理这张单据的页面 */
const handleNoticeClick = async (item: any) => {
  const jumped = await openNoticeTarget(item, router, readNotice)
  if (jumped) {
    noticeOpen.value = false
  } else {
    ElMessage.info('这条消息没有关联单据，已在列表里标记为已读')
  }
}

const readAllNotices = async () => {
  await api.put('/notices/read-all')
  notices.value = notices.value.map((item: any) => ({ ...item, readAt: item.readAt || '已读' }))
  loadNoticeSummary()
  ElMessage.success('已全部标记为已读')
}

const goNoticeCenter = () => {
  noticeOpen.value = false
  router.push('/notices')
}

const groups = [
  {
    title: '概览',
    key: 'dashboard',
    items: [
      { name: '工作台', path: '/dashboard', icon: 'Odometer', perm: 'dashboard' },
      { name: '经营大屏', path: '/screen/bi', icon: 'Monitor', perm: 'dashboard' },
      { name: '审批中心', path: '/approvals', icon: 'Stamp' },
      { name: '流程配置', path: '/approvals/flows', icon: 'Share' },
      { name: '消息中心', path: '/notices', icon: 'ChatDotSquare' },
      { name: '我的任务', path: '/tasks', icon: 'List' }
    ]
  },
  {
    title: '销售与交付',
    key: 'sales',
    items: [
      { name: '销售订单', path: '/sales', icon: 'Tickets', perm: 'sales' },
      { name: '报价管理', path: '/sales/quotes', icon: 'PriceTag', perm: 'quotation' },
      { name: '发货管理', path: '/shipments', icon: 'Van', perm: 'shipments' },
      { name: '退货管理', path: '/returns', icon: 'RefreshLeft', perm: 'returns' }
    ]
  },
  {
    title: '计划与排产',
    key: 'planning',
    items: [
      { name: '排产计划', path: '/planning/schedule', icon: 'Calendar', perm: 'production:schedule' },
      { name: 'BOM 任务', path: '/planning/bom-tasks', icon: 'DocumentChecked', perm: 'bom_task' },
      { name: '排产甘特', path: '/planning/aps', icon: 'Grid', perm: 'production:schedule' }
    ]
  },
  {
    title: '生产执行',
    key: 'production',
    items: [
      { name: '生产工单', path: '/production', icon: 'SetUp', perm: 'production' },
      { name: '单件追踪', path: '/production/units', icon: 'Postcard', perm: 'production' },
      { name: '外协管理', path: '/production/outsource', icon: 'Van', perm: 'production' },
      { name: '车间看板', path: '/workshop', icon: 'DataBoard', perm: 'production' },
      { name: '异常安灯', path: '/workshop/andon', icon: 'Bell', perm: 'production' },
      { name: '车间扫码', path: '/shopfloor/scan', icon: 'Aim', perm: 'production:report' }
    ]
  },
  {
    title: '采购与应付',
    key: 'purchase',
    items: [
      { name: '采购申请', path: '/purchase/requisitions', icon: 'DocumentAdd', perm: 'requisition' },
      { name: '物料需求', path: '/purchase/mrp', icon: 'DataAnalysis', perm: 'requisition' },
      { name: '询价比价', path: '/purchase/rfqs', icon: 'TrendCharts', perm: 'rfq' },
      { name: '供应商档案', path: '/purchase/suppliers', icon: 'OfficeBuilding', perm: 'supplier' },
      { name: '供应商绩效', path: '/purchase/performance', icon: 'Medal', perm: 'supplier' },
      { name: '采购订单', path: '/purchase/orders', icon: 'ShoppingCart', perm: 'purchase' },
      { name: '发票与应付', path: '/purchase/payables', icon: 'Money', perm: 'ap' },
      { name: '采购退货', path: '/purchase/returns', icon: 'RefreshLeft', perm: 'preturn' }
    ]
  },
  {
    title: '财务',
    key: 'finance',
    items: [
      { name: '应收管理', path: '/finance/receivables', icon: 'Wallet', perm: 'finance' },
      { name: '总账凭证', path: '/finance/ledger', icon: 'Memo', perm: 'finance' },
      { name: '固定资产', path: '/finance/fixed-assets', icon: 'OfficeBuilding', perm: 'finance' },
      { name: '税务申报', path: '/finance/tax', icon: 'Document', perm: 'finance' },
      { name: '季度预缴', path: '/finance/tax-prepayments', icon: 'Calendar', perm: 'finance' },
      { name: '企业所得税', path: '/finance/income-tax', icon: 'Files', perm: 'finance' },
      { name: '研发辅助账', path: '/finance/rd-projects', icon: 'DataAnalysis', perm: 'finance' }
    ]
  },
  {
    title: '仓储管理',
    key: 'warehouse',
    items: [
      { name: '库存管理', path: '/inventory', icon: 'Box', perm: 'inventory' },
      { name: '入库管理', path: '/warehouse/inbound', icon: 'Download', perm: 'inbound' },
      { name: '出库管理', path: '/warehouse/outbound', icon: 'Upload', perm: 'outbound' },
      { name: '库存调拨', path: '/warehouse/transfers', icon: 'Sort', perm: 'transfer' },
      { name: '库存盘点', path: '/warehouse/stocktakes', icon: 'List', perm: 'stocktake' },
      { name: '库位管理', path: '/warehouse/locations', icon: 'Coordinate', perm: 'inventory' },
      { name: '批次追溯', path: '/trace', icon: 'Search', perm: 'trace' }
    ]
  },
  {
    title: '质量与设备',
    key: 'quality',
    items: [
      { name: '质量检验', path: '/quality', icon: 'CircleCheck', perm: 'quality' },
      { name: '不良处置', path: '/quality/defects', icon: 'Warning', perm: 'quality' },
      { name: '过程能力 SPC', path: '/quality/spc', icon: 'Histogram', perm: 'quality' },
      { name: '设备点检保养', path: '/device/maintenance', icon: 'Tools', perm: 'device' },
      { name: '设备 OEE', path: '/device/oee', icon: 'Odometer', perm: 'device' }
    ]
  },
  {
    title: '报表分析',
    key: 'report',
    items: [
      { name: '生产报表', path: '/reports/production', icon: 'TrendCharts', perm: 'production' },
      { name: '成本核算', path: '/reports/cost', icon: 'Money', perm: 'cost' },
      { name: '绩效核算', path: '/reports/performance', icon: 'Medal', perm: 'perf' },
      { name: '月结报表', path: '/reports/month-close', icon: 'Calendar', perm: 'cost' }
    ]
  },
  {
    title: '基础数据',
    key: 'base',
    items: [
      { name: '客户管理', path: '/customers', icon: 'User', perm: 'customers' },
      { name: '物料主数据', path: '/products', icon: 'Goods', perm: 'products' },
      { name: 'BOM 管理', path: '/boms', icon: 'Files', perm: 'products' },
      { name: '工序管理', path: '/processes', icon: 'Operation', perm: 'products' },
      { name: '仓库管理', path: '/warehouses', icon: 'House', perm: 'warehouses' }
    ]
  },
  {
    title: '系统管理',
    key: 'system',
    items: [
      { name: '组织架构', path: '/system/orgs', icon: 'OfficeBuilding', perm: 'system_org' },
      { name: '用户管理', path: '/system/users', icon: 'UserFilled', perm: 'system_user' },
      { name: '角色权限', path: '/system/roles', icon: 'Lock', perm: 'system_role' },
      { name: '编码规则', path: '/system/code-rules', icon: 'Document', perm: 'system_code' },
      { name: '自定义字段', path: '/system/custom-fields', icon: 'Grid', perm: 'system_custom_field' },
      { name: '系统配置', path: '/system/config', icon: 'Setting', perm: 'system_config' },
      { name: '通知管理', path: '/system/notices', icon: 'Bell', perm: 'system_notice' },
      { name: '基础数据导入', path: '/system/data', icon: 'UploadFilled', perm: 'system_data' },
      { name: '定时任务', path: '/system/jobs', icon: 'AlarmClock', perm: 'system_job' },
      { name: '对外接口令牌', path: '/system/api-tokens', icon: 'Key', perm: 'system_api' },
      { name: '离线重传记录', path: '/system/offline', icon: 'Refresh', perm: 'system_offline' },
      { name: '文件台账', path: '/system/files', icon: 'FolderOpened', perm: 'system_file' },
      { name: '操作日志', path: '/system/logs', icon: 'Memo', perm: 'system_log' }
    ]
  }
]

/** 权限过滤后的菜单（含「经营大屏」入口） */
const menuGroups = computed(() =>
  groups
    .map((group) => ({
      ...group,
      items: group.items.filter(
        (item) =>
          !item.perm ||
          !auth.user?.permissions?.length ||
          auth.user.permissions.includes(item.perm)
      )
    }))
    .filter((group) => group.items.length > 0)
)

// 菜单搜索：菜单多的时候靠滚动找人太慢，支持按菜单名 / 分组名 / 路径过滤
const menuQuery = ref('')
const menuSearchRef = ref<any>()

const filteredGroups = computed(() => {
  const keyword = menuQuery.value.trim().toLowerCase()
  if (!keyword) return menuGroups.value
  return menuGroups.value
    .map((group) => ({
      ...group,
      items: group.items.filter(
        (item) =>
          item.name.toLowerCase().includes(keyword) ||
          group.title.toLowerCase().includes(keyword) ||
          item.path.toLowerCase().includes(keyword)
      )
    }))
    .filter((group) => group.items.length > 0)
})

const noMenuMatch = computed(() => !!menuQuery.value.trim() && !filteredGroups.value.length)

/* ---------- 侧栏折叠与分组展开：状态记在本地，下次进来保持一致 ---------- */

const COLLAPSED_KEY = 'mes_menu_collapsed'
const CLOSED_KEY = 'mes_menu_closed_v1'

const collapsed = ref(localStorage.getItem(COLLAPSED_KEY) === '1')
const closedGroups = ref<string[]>((() => {
  try {
    const raw = JSON.parse(localStorage.getItem(CLOSED_KEY) || '[]')
    return Array.isArray(raw) ? raw : []
  } catch {
    return []
  }
})())

const toggleCollapsed = () => {
  collapsed.value = !collapsed.value
  localStorage.setItem(COLLAPSED_KEY, collapsed.value ? '1' : '0')
}

/** 搜索时强制展开所有分组 */
const isGroupOpen = (title: string) =>
  !!menuQuery.value.trim() || !closedGroups.value.includes(title)

const toggleGroup = (title: string) => {
  closedGroups.value = closedGroups.value.includes(title)
    ? closedGroups.value.filter((item) => item !== title)
    : [...closedGroups.value, title]
  localStorage.setItem(CLOSED_KEY, JSON.stringify(closedGroups.value))
}

/** 点击分组标题进入该业务域工作台；概览分组直接回工作台 */
const openGroupWorkspace = (group: any) => {
  router.push(group.key === 'dashboard' ? '/dashboard' : `/overview/${group.key}`)
}

const focusMenuSearch = () => {
  const input = menuSearchRef.value
  if (input?.focus) input.focus()
}

/** Ctrl/Cmd+K 聚焦菜单搜索，Esc 清空关键词 */
const onMenuHotkey = (event: KeyboardEvent) => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    focusMenuSearch()
  }
  if (event.key === 'Escape' && menuQuery.value) {
    menuQuery.value = ''
  }
}

const today = computed(() => {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} 周${week}`
})

const logout = () => {
  auth.logout()
  router.push('/login')
}

// 个人中心：资料自助修改 + 修改密码
const profileOpen = ref(false)
const profileSaving = ref(false)
const pwdSaving = ref(false)
const profileForm = reactive({ realName: '', phone: '', email: '' })
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '' })

const openProfile = () => {
  profileForm.realName = auth.user?.realName || ''
  profileForm.phone = auth.user?.phone || ''
  profileForm.email = auth.user?.email || ''
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirm = ''
  profileOpen.value = true
}

const saveProfile = async () => {
  if (!profileForm.realName.trim()) {
    ElMessage.warning('姓名不能为空')
    return
  }
  profileSaving.value = true
  try {
    await api.put('/auth/profile', { ...profileForm })
    await auth.refresh()
    ElMessage.success('个人资料已更新')
    profileOpen.value = false
  } finally {
    profileSaving.value = false
  }
}

const changePassword = async () => {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('请填写原密码与新密码')
    return
  }
  if (pwdForm.newPassword.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  pwdSaving.value = true
  try {
    const secret = await encryptPasswordPair(pwdForm.oldPassword, pwdForm.newPassword)
    const data = await api.put('/auth/password', secret)
    // 改密成功后后端换发新令牌（不再带「待改密」标记），顺手落到本地
    if (data?.token) {
      localStorage.setItem('mes_token', data.token)
    }
    ElMessage.success('密码已修改，下次登录请使用新密码')
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirm = ''
  } finally {
    pwdSaving.value = false
  }
}
</script>

<template>
  <div class="shell">
    <aside class="sidebar" :class="{ collapsed }">
      <div class="brand">
        <div v-if="!collapsed" class="mark">M</div>
        <div v-if="!collapsed" class="brand-text">
          <b>智造工单</b>
          <span>LIGHT MES</span>
        </div>
        <button
          class="collapse-btn"
          :title="collapsed ? '展开菜单' : '收起菜单'"
          @click="toggleCollapsed"
        >
          <el-icon><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
        </button>
      </div>

      <div v-if="!collapsed" class="nav-search">
        <el-input
          ref="menuSearchRef"
          v-model="menuQuery"
          size="small"
          clearable
          placeholder="搜索菜单"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
          <template #suffix>
            <span class="kbd">Ctrl K</span>
          </template>
        </el-input>
      </div>

      <nav class="nav" :class="{ 'nav-collapsed': collapsed }">
        <template v-for="group in filteredGroups" :key="group.title">
          <div
            v-if="!collapsed"
            class="nav-group nav-group-link"
            :title="`打开「${group.title}」工作台`"
            @click="openGroupWorkspace(group)"
          >
            <span>{{ group.title }}</span>
            <button
              class="group-toggle"
              :title="isGroupOpen(group.title) ? '收起' : '展开'"
              @click.stop="toggleGroup(group.title)"
            >
              <el-icon :class="{ rotated: !isGroupOpen(group.title) }"><ArrowDown /></el-icon>
            </button>
          </div>
          <template v-if="collapsed || isGroupOpen(group.title)">
            <template v-for="item in group.items" :key="item.path">
              <a
                v-if="item.path === '/screen/bi'"
                href="#/screen/bi"
                target="_blank"
                rel="noopener"
                class="nav-item"
                :class="{ 'nav-item-icon': collapsed }"
                :title="collapsed ? item.name : undefined"
              >
                <el-icon :size="16"><component :is="item.icon" /></el-icon>
                <span v-if="!collapsed">{{ item.name }}</span>
                <span v-if="!collapsed" class="nav-badge">BI</span>
              </a>
              <router-link
                v-else
                :to="item.path"
                class="nav-item"
                :class="{ 'nav-item-icon': collapsed }"
                active-class="active"
                :title="collapsed ? item.name : undefined"
                @click="menuQuery = ''"
              >
                <el-icon :size="16"><component :is="item.icon" /></el-icon>
                <span v-if="!collapsed">{{ item.name }}</span>
              </router-link>
            </template>
          </template>
        </template>
        <div v-if="noMenuMatch && !collapsed" class="nav-empty">没有匹配的菜单</div>
      </nav>

      <div class="sidebar-foot" :class="{ 'foot-collapsed': collapsed }">
        <div class="plant">
          <span class="dot"></span>
          <div v-if="!collapsed">
            <b>{{ auth.orgLabel }}</b>
            <span>{{ auth.user?.position || '生产运行正常' }}</span>
          </div>
        </div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span>智造工单</span>
          <i>/</i>
          <b>{{ route.meta.title }}</b>
        </div>
        <div class="top-right">
          <span class="today">{{ today }}</span>
          <el-badge :value="noticeSummary.unread" :max="99" :hidden="!noticeSummary.unread" class="bell-badge">
            <div class="bell" title="站内通知" @click="openNotices">
              <el-icon :size="17"><Bell /></el-icon>
            </div>
          </el-badge>
          <el-dropdown trigger="click">
            <div class="user">
              <div class="avatar">{{ (auth.user?.realName || 'U').slice(0, 1) }}</div>
              <div class="user-meta">
                <b>{{ auth.user?.realName || auth.user?.username }}</b>
                <span>{{ auth.roleName }}</span>
              </div>
              <el-icon><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ auth.user?.username }}</el-dropdown-item>
                <el-dropdown-item @click="openProfile">个人中心</el-dropdown-item>
                <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>

    <el-drawer append-to-body v-model="noticeOpen" title="站内通知" size="460px">
      <div class="notice-actions">
        <span class="muted">
          未读 {{ noticeSummary.unread }} 条 · 共 {{ noticeSummary.total }} 条
        </span>
        <el-button link type="primary" :disabled="!noticeSummary.unread" @click="readAllNotices">
          全部标为已读
        </el-button>
      </div>
      <div v-loading="noticeLoading" class="notice-list">
        <div
          v-for="item in notices"
          :key="item.id"
          class="notice-item"
          :class="{ unread: !item.readAt, jumpable: !!noticeTarget(item) }"
          @click="handleNoticeClick(item)"
        >
          <div class="notice-top">
            <span class="status-tag" :class="categoryOf(item.category).cls">
              <i></i>{{ categoryOf(item.category).label }}
            </span>
            <span class="muted">{{ item.createdAt }}</span>
          </div>
          <b>{{ item.title }}</b>
          <p v-if="item.content">{{ item.content }}</p>
          <div class="notice-ref-row">
            <span v-if="item.refNo" class="mono muted notice-ref">{{ item.refNo }}</span>
            <span v-if="noticeTarget(item)" class="notice-jump">
              去处理 · {{ noticeTarget(item)!.module }} →
            </span>
          </div>
        </div>
        <el-empty v-if="!noticeLoading && !notices.length" description="暂无通知" />
      </div>
      <div class="notice-footer">
        <el-button link type="primary" @click="goNoticeCenter">查看全部消息 →</el-button>
        <span v-if="!desktopNoticeOn" class="muted">可在消息中心开启桌面通知</span>
      </div>
    </el-drawer>

    <el-drawer append-to-body v-model="profileOpen" title="个人中心" size="460px">
      <div class="profile-head">
        <div class="avatar big">{{ (auth.user?.realName || 'U').slice(0, 1) }}</div>
        <div>
          <b>{{ auth.user?.realName || auth.user?.username }}</b>
          <span>{{ auth.user?.username }} · {{ auth.roleName }}</span>
        </div>
      </div>

      <div class="drawer-section">
        <h4>基本资料</h4>
        <el-form label-position="top">
          <el-form-item label="姓名" required>
            <el-input v-model="profileForm.realName" placeholder="真实姓名" />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input v-model="profileForm.phone" placeholder="用于手机号登录" maxlength="11" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="profileForm.email" placeholder="name@example.com" />
          </el-form-item>
          <el-form-item label="账号 / 角色">
            <div class="readonly-line">
              <span class="mono">{{ auth.user?.username }}</span>
              <span class="muted">{{ auth.roleName }}（由管理员分配，不能自行修改）</span>
            </div>
          </el-form-item>
          <el-form-item label="所属组织 / 岗位">
            <div class="readonly-line">
              <span>{{ auth.user?.orgName || '未分配组织' }}<template v-if="auth.user?.position"> · {{ auth.user?.position }}</template></span>
              <span class="muted">
                工号 {{ auth.user?.employeeNo || '-' }}，组织与岗位由管理员在「组织架构 / 用户管理」中调整
              </span>
            </div>
          </el-form-item>
        </el-form>
        <el-button type="primary" :loading="profileSaving" @click="saveProfile">保存资料</el-button>
      </div>

      <div class="drawer-section">
        <h4>修改密码</h4>
        <el-form label-position="top">
          <el-form-item label="原密码">
            <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="当前密码" />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少 6 位" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="pwdForm.confirm" type="password" show-password placeholder="再输入一次" />
          </el-form-item>
        </el-form>
        <el-button :loading="pwdSaving" @click="changePassword">修改密码</el-button>
        <p class="hint-line">密码在浏览器内用 RSA 公钥加密后再提交，服务端只保存不可逆哈希。</p>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 桌面端把滚动容器收回内容区：侧边栏与顶栏固定，列表页只有表格体滚动 */
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  width: 218px;
  flex: 0 0 218px;
  background: var(--navy);
  color: #cbd5e1;
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
  border-right: 1px solid #0a0f1a;
  transition: width 0.18s ease, flex-basis 0.18s ease;
}

.sidebar.collapsed {
  width: 68px;
  flex: 0 0 68px;
}

.collapse-btn {
  width: 26px;
  height: 26px;
  border: none;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  display: grid;
  place-items: center;
  border-radius: 6px;
  transition: 0.16s ease;
}

.collapse-btn:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.06);
}

.brand {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 18px;
  border-bottom: 1px solid var(--navy-line);
}

.sidebar.collapsed .brand {
  justify-content: center;
  padding: 0;
}

.mark {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #2456d8, #6d9bff);
  color: #fff;
  font-weight: 800;
  display: grid;
  place-items: center;
  font-size: 15px;
  box-shadow: 0 6px 16px rgba(36, 86, 216, 0.45);
}

.brand-text b {
  display: block;
  color: #fff;
  font-size: 14px;
  letter-spacing: 1px;
}

.brand-text span {
  display: block;
  font-size: 9px;
  letter-spacing: 2.4px;
  color: #64748b;
  margin-top: 2px;
}

/* 侧边栏菜单搜索：深色底上单独收一套输入框样式 */
.nav-search {
  padding: 12px 12px 4px;
}

.nav-search :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.06);
  box-shadow: none;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
}

.nav-search :deep(.el-input__wrapper.is-focus) {
  border-color: #5b8def;
  box-shadow: 0 0 0 2px rgba(91, 141, 239, 0.2);
}

/* 键盘 Ctrl+K 聚焦时也给出明显焦点圈 */
.nav-search :deep(.el-input__wrapper:focus-within) {
  border-color: #5b8def;
  box-shadow: 0 0 0 2px rgba(91, 141, 239, 0.2);
}

.nav-search :deep(.el-input__inner) {
  color: #e2e8f0;
  font-size: 12px;
}

.nav-search :deep(.el-input__inner::placeholder) {
  color: #5c6b84;
}

.nav-search :deep(.el-input__prefix),
.nav-search :deep(.el-input__suffix) {
  color: #64748b;
}

.kbd {
  font-size: 9px;
  padding: 1px 4px;
  border-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  color: #64748b;
  letter-spacing: 0.5px;
}

.nav-badge {
  margin-left: auto;
  padding: 0 5px;
  border-radius: 4px;
  font-size: 9px;
  font-weight: 700;
  color: #06131a;
  background: #5b8def;
  letter-spacing: 0.5px;
}

.nav-empty {
  padding: 18px 10px;
  text-align: center;
  font-size: 12px;
  color: #4b5b73;
}

.nav {
  flex: 1;
  overflow-y: auto;
  padding: 10px 10px 16px;
}

.nav::-webkit-scrollbar {
  width: 4px;
}

.nav::-webkit-scrollbar-thumb {
  background: #1e293b;
  border-radius: 4px;
}

.nav-group {
  padding: 14px 10px 7px;
  font-size: 10px;
  letter-spacing: 1.6px;
  color: #4b5b73;
  text-transform: uppercase;
}

/* 分组标题可点击进入该业务域工作台，右侧箭头只负责展开/收起 */
.nav-group-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  cursor: pointer;
  user-select: none;
  border-radius: 6px;
  transition: 0.16s ease;
}

.nav-group-link:hover {
  color: #9fb2d0;
  background: rgba(255, 255, 255, 0.04);
}

.group-toggle {
  width: 20px;
  height: 20px;
  border: none;
  background: transparent;
  color: #4b5b73;
  cursor: pointer;
  display: grid;
  place-items: center;
  border-radius: 4px;
  padding: 0;
  transition: 0.16s ease;
}

.group-toggle:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}

.group-toggle .el-icon {
  transition: transform 0.16s ease;
}

.group-toggle .rotated {
  transform: rotate(-90deg);
}

.nav-item-icon {
  justify-content: center;
  padding: 9px 0;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 11px;
  border-radius: 7px;
  color: #96a5bd;
  font-size: 13px;
  margin-bottom: 2px;
  transition: 0.16s ease;
  position: relative;
}

.nav-item:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.05);
}

.nav-item.active {
  color: #fff;
  background: linear-gradient(90deg, rgba(36, 86, 216, 0.28), rgba(36, 86, 216, 0.08));
}

.nav-item.active::before {
  content: "";
  position: absolute;
  left: -10px;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #5b8def;
}

.sidebar-foot {
  border-top: 1px solid var(--navy-line);
  padding: 14px 16px 16px;
}

.sidebar-foot.foot-collapsed {
  padding: 14px 0 16px;
  display: flex;
  justify-content: center;
}

.sidebar-foot.foot-collapsed .plant {
  gap: 0;
}

.plant {
  display: flex;
  gap: 9px;
  align-items: flex-start;
}

.plant .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #12b76a;
  margin-top: 5px;
  box-shadow: 0 0 0 4px rgba(18, 183, 106, 0.15);
}

.plant b {
  display: block;
  color: #e2e8f0;
  font-size: 12px;
  font-weight: 500;
}

.plant span {
  display: block;
  color: #64748b;
  font-size: 10px;
  margin-top: 2px;
}

.main {
  flex: 1;
  min-width: 0;
  height: 100vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 64px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  position: sticky;
  top: 0;
  z-index: 20;
}

.crumb {
  font-size: 12px;
  color: var(--muted);
  display: flex;
  align-items: center;
  gap: 8px;
}

.crumb i {
  font-style: normal;
  color: #cbd5e1;
}

.crumb b {
  color: var(--ink);
  font-size: 13px;
  font-weight: 600;
}

.top-right {
  display: flex;
  align-items: center;
  gap: 20px;
}

.today {
  font-size: 12px;
  color: var(--muted);
  font-family: Bahnschrift, monospace;
}

.user {
  display: flex;
  align-items: center;
  gap: 9px;
  cursor: pointer;
  padding: 4px 2px;
  color: var(--ink-2);
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2456d8, #7ba2f8);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 600;
}

.bell-badge {
  display: flex;
  align-items: center;
}

.bell {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: var(--ink-2);
  background: #f4f6fa;
  border: 1px solid var(--line);
  cursor: pointer;
  transition: 0.16s ease;
}

.bell:hover {
  color: var(--primary);
  border-color: #b5c9f5;
}

.notice-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0 12px;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}

.notice-list {
  padding-top: 10px;
  min-height: 120px;
}

.notice-item {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  margin-bottom: 10px;
  cursor: pointer;
  background: #fff;
  transition: 0.16s ease;
}

.notice-item:hover {
  border-color: #c3d4f7;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.05);
}

.notice-item.unread {
  background: #f8fbff;
  border-color: #d5e3fb;
}

.notice-ref-row {
  margin-top: 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.notice-jump {
  flex: none;
  padding: 1px 8px;
  border-radius: 20px;
  background: #eef3ff;
  color: #2456d8;
  font-size: 11px;
  font-weight: 600;
}

.notice-item.jumpable:hover .notice-jump {
  background: #2456d8;
  color: #fff;
}

.notice-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
  font-size: 12px;
}

.notice-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  margin-bottom: 6px;
}

.notice-item b {
  display: block;
  font-size: 13px;
  line-height: 1.5;
}

.notice-item p {
  margin: 5px 0 0;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.6;
}

.notice-ref {
  display: inline-block;
  margin-top: 6px;
  font-size: 11px;
}

.profile-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #f7f9fc;
  border: 1px solid var(--line);
  border-radius: 12px;
}

.profile-head .avatar.big {
  width: 46px;
  height: 46px;
  font-size: 18px;
}

.profile-head b {
  display: block;
  font-size: 15px;
}

.profile-head span {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  color: var(--ink-3);
}

.readonly-line {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}

.hint-line {
  margin: 10px 0 0;
  font-size: 11px;
  color: var(--ink-3);
  line-height: 1.6;
}

.user-meta b {
  display: block;
  font-size: 12px;
  line-height: 1.3;
}

.user-meta span {
  display: block;
  font-size: 10px;
  color: var(--muted);
}

.content {
  padding: 20px 24px 20px;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

@media (max-width: 900px) {
  .sidebar {
    display: none;
  }

  /* 窄屏回到整页滚动，避免看板类页面被锁死高度裁掉内容 */
  .shell {
    height: auto;
    min-height: 100vh;
    overflow: visible;
  }

  .main {
    height: auto;
    overflow: visible;
  }

  .content {
    display: block;
    overflow: visible;
  }
}
</style>
