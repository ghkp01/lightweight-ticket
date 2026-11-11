import { defineStore } from 'pinia'
import { api } from '../api'
import { encryptPassword } from '../utils/crypto'
import { openPasswordGate } from '../utils/passwordGate'

interface UserInfo {
  username: string
  realName: string
  role: string
  roleName?: string
  phone?: string
  email?: string
  orgId?: number
  orgName?: string
  orgPath?: string
  orgType?: string
  position?: string
  employeeNo?: string
  mustChangePassword?: boolean
  permissions: string[]
}

const toUser = (data: any): UserInfo => ({
  username: data.username,
  realName: data.realName,
  role: data.role,
  roleName: data.roleName,
  phone: data.phone,
  email: data.email,
  orgId: data.orgId,
  orgName: data.orgName,
  orgPath: data.orgPath,
  orgType: data.orgType,
  position: data.position,
  employeeNo: data.employeeNo,
  mustChangePassword: !!data.mustChangePassword,
  permissions: data.permissions || []
})

const readUser = (): UserInfo | null => {
  try {
    const parsed = JSON.parse(localStorage.getItem('mes_user') || 'null')
    if (!parsed) return null
    return { ...parsed, permissions: parsed.permissions || [] }
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('mes_token') || '',
    user: readUser() as UserInfo | null
  }),
  getters: {
    isLogin: (state) => !!state.token,
    can: (state) => (permission: string) => {
      const permissions = state.user?.permissions || []
      return permissions.length === 0 || permissions.includes(permission)
    },
    roleName: (state) => {
      const map: Record<string, string> = {
        ADMIN: '管理员',
        SALES: '销售',
        PLANNER: '计划',
        WAREHOUSE: '仓库',
        QC: '质检'
      }
      if (!state.user) return ''
      return state.user.roleName || map[state.user.role] || state.user.role
    },
    /** 侧边栏展示的当前组织：优先车间，其次工厂 */
    orgLabel: (state) => state.user?.orgName || '未分配组织'
  },
  actions: {
    async login(username: string, password: string) {
      const secret = await encryptPassword(password)
      const data = await api.post('/auth/login', { username, ...secret })
      this.token = data.token
      this.user = toUser(data)
      localStorage.setItem('mes_token', data.token)
      localStorage.setItem('mes_user', JSON.stringify(this.user))
      if (data.refreshToken) {
        localStorage.setItem('mes_refresh_token', data.refreshToken)
      }
      // 还在用初始口令：进系统前先把密码改掉，业务接口后端本来就会拦
      if (data.mustChangePassword) {
        openPasswordGate()
      }
    },
    /** 刷新当前用户与权限：角色权限调整后无需重新登录即可看到新按钮 */
    async refresh() {
      if (!this.token) return
      try {
        const data = await api.get('/auth/me')
        this.user = toUser(data)
        localStorage.setItem('mes_user', JSON.stringify(this.user))
      } catch (error) {
        // 刷新失败不阻断页面，保留本地缓存权限
      }
    },
    logout() {
      const refreshToken = localStorage.getItem('mes_refresh_token')
      if (refreshToken) {
        // 尽力通知后端作废刷新令牌，失败不影响本地退出
        api.post('/auth/logout', { refreshToken }).catch(() => undefined)
      }
      this.token = ''
      this.user = null
      localStorage.removeItem('mes_token')
      localStorage.removeItem('mes_user')
      localStorage.removeItem('mes_refresh_token')
    }
  }
})
