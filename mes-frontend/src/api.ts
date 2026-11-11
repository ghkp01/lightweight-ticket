import axios from 'axios'
import { ElMessage } from 'element-plus'
import { openPasswordGate, shouldNotifyGate } from './utils/passwordGate'

const http = axios.create({
  baseURL: '/api',
  timeout: 20000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('mes_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

let refreshing: Promise<string | null> | null = null

/**
 * 用刷新令牌换新的访问令牌。
 * 走裸 axios 而不是 http 实例，避免刷新失败时又被 401 拦截器抓一遍造成递归。
 * 多个请求同时 401 时共用同一个刷新 Promise。
 */
const refreshAccessToken = async (): Promise<string | null> => {
  const refreshToken = localStorage.getItem('mes_refresh_token')
  if (!refreshToken) return null
  if (!refreshing) {
    refreshing = axios
      .post('/api/auth/refresh', { refreshToken })
      .then((res) => {
        const body = res.data
        if (!body || body.code !== 0 || !body.data?.token) return null
        localStorage.setItem('mes_token', body.data.token)
        if (body.data.refreshToken) {
          localStorage.setItem('mes_refresh_token', body.data.refreshToken)
        }
        return body.data.token as string
      })
      .catch(() => null)
      .finally(() => {
        refreshing = null
      })
  }
  return refreshing
}

/** 已经在登录页时不再提示「登录已失效」：用户本来就要登录，再弹只是噪音 */
const onLoginPage = () => location.hash.startsWith('#/login')

/** 一次会话过期只提示一次：并发请求同时 401 时不刷屏 */
let unauthorizedHandled = false

/**
 * 会话失效统一处理：清本地令牌、回登录页。
 *
 * 之前在登录页也会弹「登录已失效，请重新登录」，而且每个失败的请求各弹一条，
 * 刷新页面时配置、用户信息等请求一起 401，右上角就会叠出三四条重复提示。
 * 现在没登录态 / 已在登录页都静默处理，真过期时也只提示一次。
 */
const handleUnauthorized = () => {
  const hadSession = Boolean(localStorage.getItem('mes_token') || localStorage.getItem('mes_refresh_token'))
  localStorage.removeItem('mes_token')
  localStorage.removeItem('mes_user')
  localStorage.removeItem('mes_refresh_token')
  const alreadyOnLogin = onLoginPage()
  if (!alreadyOnLogin) {
    location.hash = '#/login'
  }
  if (!hadSession || alreadyOnLogin || unauthorizedHandled) return
  unauthorizedHandled = true
  ElMessage.warning({ message: '登录已失效，请重新登录', grouping: true })
  window.setTimeout(() => {
    unauthorizedHandled = false
  }, 3000)
}

http.interceptors.response.use(
  (response) => {
    // 附件下载走 blob，不能按统一 {code,message,data} 拆包
    if (response.config.responseType === 'blob') {
      return response.data
    }
    const body = response.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      // 后端对未登录请求返回 200 + code 401，按会话过期处理，不要在登录页弹红条
      if (body.code === 401) {
        handleUnauthorized()
        return Promise.reject(new Error('UNAUTHENTICATED'))
      }
      // 4023：初始口令没改，后端已经拦住业务接口，这里把强制改密弹窗打开
      if (body.code === 4023) {
        openPasswordGate()
        if (shouldNotifyGate()) {
          ElMessage.warning('首次登录请先修改密码')
        }
        return Promise.reject(new Error('PASSWORD_CHANGE_REQUIRED'))
      }
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body ? body.data : null
  },
  async (error) => {
    const status = error.response?.status
    const config = error.config || {}
    const url: string = config.url || ''
    const canRefresh = status === 401
      && !config._retried
      && !url.includes('/auth/login')
      && !url.includes('/auth/refresh')
    if (canRefresh) {
      const token = await refreshAccessToken()
      if (token) {
        config._retried = true
        config.headers = config.headers || {}
        config.headers.Authorization = 'Bearer ' + token
        return http(config)
      }
    }
    if (status === 401) {
      handleUnauthorized()
    } else {
      ElMessage.error(error.response?.data?.message || error.message || '网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export const api = {
  get: (url: string, params?: any): Promise<any> => http.get(url, { params }),
  post: (url: string, data?: any): Promise<any> => http.post(url, data),
  put: (url: string, data?: any): Promise<any> => http.put(url, data),
  delete: (url: string): Promise<any> => http.delete(url)
}

/** 主数据编码自动生成：取一个未占用的编码，bizType 支持 WC/OP/WH/PD/CUS/SUP */
export const nextCode = async (bizType: string): Promise<string> => {
  const data = await api.get('/system/code-rules/next', { bizType })
  return data?.code || ''
}

export const fmtMoney = (value: any) => {
  const num = Number(value || 0)
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export const fmtQty = (value: any) => {
  const num = Number(value || 0)
  return num.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

/** 统一附件上传：业务单据只传 bizType / bizId / bizNo */
export const uploadFile = (file: File, params?: { bizType?: string; bizId?: number | string; bizNo?: string }) => {
  const form = new FormData()
  form.append('file', file)
  return http.post('/files', form, { params })
}

/** 统一附件下载：后端返回 blob，这里负责落盘 */
export const downloadFile = async (id: number, fileName = '附件') => {
  const blob: Blob = await http.get(`/files/${id}/download`, { responseType: 'blob' })
  if (blob instanceof Blob && blob.type.includes('application/json')) {
    const text = await blob.text()
    try {
      const body = JSON.parse(text)
      ElMessage.error(body.message || '下载失败')
    } catch {
      ElMessage.error('下载失败')
    }
    return
  }
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
