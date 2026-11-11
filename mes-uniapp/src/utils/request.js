import { API_BASE_URL, REQUEST_TIMEOUT, STORAGE_KEYS } from './config'
import { enqueue, newKey, offlineEnabled } from './offlineQueue'

let redirecting = false

const toast = (title, duration) => {
  uni.showToast({
    title: title || '操作失败',
    icon: 'none',
    duration: duration || 2200
  })
}

const clearLoginState = () => {
  uni.removeStorageSync(STORAGE_KEYS.token)
  uni.removeStorageSync(STORAGE_KEYS.user)
}

const handleUnauthorized = () => {
  clearLoginState()
  if (redirecting) return
  redirecting = true
  toast('登录已失效，请重新登录')
  setTimeout(() => {
    redirecting = false
    uni.reLaunch({ url: '/pages/login/login' })
  }, 500)
}

const MUTATING = ['POST', 'PUT', 'PATCH', 'DELETE']

/**
 * 断网时把写请求存进本地队列，返回一个带 __offline 标记的结果，
 * 页面看到这个标记就提示「已离线保存」，而不是当成失败。
 */
const queueOffline = ({ url, method, data, key, label }) => {
  const queued = enqueue({ key, method, url, data, label })
  if (!queued) {
    toast('离线队列已满，请连网后先同步')
    return null
  }
  toast('网络不可用，已存离线队列，联网后自动同步', 2600)
  return { __offline: true, queued: true, idempotencyKey: key, label }
}

/**
 * 统一请求封装：
 * - 自动携带 Bearer token
 * - 解包 ApiResponse { code, message, data }
 * - 业务错误 / 网络错误统一 toast
 * - 401 清理登录态并回到登录页
 * - 写请求自动带 X-Idempotency-Key；offline=true 时断网自动入离线队列
 */
export const request = (options = {}) => {
  const {
    url,
    method = 'GET',
    data,
    header = {},
    silent = false,
    timeout = REQUEST_TIMEOUT,
    offline = false,
    label = ''
  } = options

  const upperMethod = method.toUpperCase()
  const mutating = MUTATING.includes(upperMethod)
  // 写请求都带幂等键：即使这次没走离线队列，重试也不会重复落库
  const idemKey = mutating ? newKey() : null
  const token = uni.getStorageSync(STORAGE_KEYS.token)

  return new Promise((resolve, reject) => {
    uni.request({
      url: `${API_BASE_URL}${url}`,
      method: upperMethod,
      data,
      timeout,
      header: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(idemKey ? { 'X-Idempotency-Key': idemKey } : {}),
        ...header
      },
      success(res) {
        const status = res.statusCode

        if (status === 401) {
          handleUnauthorized()
          reject(new Error('UNAUTHORIZED'))
          return
        }

        const body = res.data

        if (status >= 200 && status < 300) {
          if (body && typeof body === 'object' && typeof body.code === 'number') {
            if (body.code === 0) {
              resolve(body.data)
            } else {
              if (!silent) toast(body.message || '操作失败')
              reject(new Error(body.message || 'BUSINESS_ERROR'))
            }
            return
          }
          resolve(body)
          return
        }

        // 服务端 5xx：可能是重启或超时，写请求进队列再试一次（幂等键保证不重复）
        if (status >= 500 && offline && mutating && offlineEnabled()) {
          const queued = queueOffline({ url, method: upperMethod, data, key: idemKey, label })
          if (queued) {
            resolve(queued)
            return
          }
        }

        const message =
          (body && (body.message || body.error)) ||
          (status === 404 ? '接口不存在或服务未启用' : `请求失败（${status}）`)
        if (!silent) toast(message)
        reject(new Error(message))
      },
      fail(error) {
        const timeoutHit = error && error.errMsg && error.errMsg.includes('timeout')
        if (offline && mutating && offlineEnabled()) {
          const queued = queueOffline({ url, method: upperMethod, data, key: idemKey, label })
          if (queued) {
            resolve(queued)
            return
          }
        }
        const message = timeoutHit ? '请求超时，请检查网络' : '网络异常，请确认后端服务已启动'
        if (!silent) toast(message)
        const err = new Error(message)
        err.code = 'NETWORK'
        reject(err)
      }
    })
  })
}

export const http = {
  get(url, params, options = {}) {
    return request({ url, method: 'GET', data: params, ...options })
  },
  post(url, data, options = {}) {
    return request({ url, method: 'POST', data, ...options })
  },
  put(url, data, options = {}) {
    return request({ url, method: 'PUT', data, ...options })
  },
  delete(url, data, options = {}) {
    return request({ url, method: 'DELETE', data, ...options })
  }
}
