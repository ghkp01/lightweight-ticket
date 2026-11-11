/**
 * 离线队列：车间信号不好时，扫码 / 报工 / 检验这些"现场动作"不能丢。
 *
 * 做法：
 * 1. 每个写请求本地生成一个幂等键（X-Idempotency-Key），随请求一起带走；
 * 2. 网络失败（断网、超时、5xx）就把请求体存进 uni.storage 队列，页面照常往下走；
 * 3. 网络恢复 / 回到前台 / 用户点「立即同步」时，按发生顺序重传；
 * 4. 服务端认这个幂等键：同一键只真正执行一次，重放直接返回上次结果，
 *    所以"其实传上去了但没收到响应"也不会重复报工。
 */
import { API_BASE_URL, REQUEST_TIMEOUT, STORAGE_KEYS } from './config'

const DEFAULT_LIMIT = 200
const MAX_KEPT_FAILED = 20

let flushing = false

const readQueue = () => {
  const raw = uni.getStorageSync(STORAGE_KEYS.offlineQueue)
  return Array.isArray(raw) ? raw : []
}

const writeQueue = (list) => {
  uni.setStorageSync(STORAGE_KEYS.offlineQueue, list)
  uni.$emit('mes:offline-changed', { count: list.length })
}

const readStatus = () => {
  const raw = uni.getStorageSync(STORAGE_KEYS.offlineStatus)
  return raw && typeof raw === 'object' ? raw : { enabled: true, queueLimit: DEFAULT_LIMIT }
}

/** 服务端下发的开关与上限，读一次缓存起来；断网时用上次的缓存 */
export const loadRemoteStatus = async () => {
  try {
    const res = await new Promise((resolve, reject) => {
      uni.request({
        url: `${API_BASE_URL}/offline/status`,
        method: 'GET',
        timeout: 8000,
        header: {
          Accept: 'application/json',
          ...(uni.getStorageSync(STORAGE_KEYS.token)
            ? { Authorization: `Bearer ${uni.getStorageSync(STORAGE_KEYS.token)}` }
            : {})
        },
        success: (r) => resolve(r),
        fail: reject
      })
    })
    const body = res.data
    if (body && body.code === 0 && body.data) {
      const status = {
        enabled: body.data.enabled !== false,
        queueLimit: Number(body.data.queueLimit) || DEFAULT_LIMIT,
        retentionDays: Number(body.data.retentionDays) || 7,
        checkedAt: new Date().toISOString()
      }
      uni.setStorageSync(STORAGE_KEYS.offlineStatus, status)
      return status
    }
  } catch (e) {
    // 断网：沿用上次缓存
  }
  return readStatus()
}

export const offlineEnabled = () => readStatus().enabled !== false

export const queueLimit = () => {
  const limit = Number(readStatus().queueLimit)
  return limit >= 20 ? limit : DEFAULT_LIMIT
}

/** 幂等键：时间 + 随机串，够唯一又便于人工排查 */
export const newKey = () =>
  `m${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`

export const listQueue = () => readQueue()

export const queueCount = () => readQueue().length

export const isQueued = (result) => Boolean(result && result.__offline)

/**
 * 入队。返回 false 表示队列已满或离线功能被关闭，调用方应提示用户稍后再试。
 */
export const enqueue = (entry) => {
  if (!offlineEnabled()) return false
  const list = readQueue()
  if (list.length >= queueLimit()) return false
  list.unshift({
    id: entry.key || newKey(),
    method: entry.method || 'POST',
    url: entry.url,
    data: entry.data === undefined ? null : entry.data,
    label: entry.label || entry.url,
    queuedAt: new Date().toLocaleString('zh-CN', { hour12: false })
  })
  writeQueue(list.slice(0, queueLimit()))
  return true
}

export const removeEntry = (id) => {
  writeQueue(readQueue().filter((item) => item.id !== id))
}

export const clearQueue = () => writeQueue([])

const keepFailed = (entry, message) => {
  const raw = uni.getStorageSync(STORAGE_KEYS.offlineFailed)
  const failed = Array.isArray(raw) ? raw : []
  failed.unshift({ ...entry, message, failedAt: new Date().toLocaleString('zh-CN', { hour12: false }) })
  uni.setStorageSync(STORAGE_KEYS.offlineFailed, failed.slice(0, MAX_KEPT_FAILED))
}

export const listFailed = () => {
  const raw = uni.getStorageSync(STORAGE_KEYS.offlineFailed)
  return Array.isArray(raw) ? raw : []
}

export const clearFailed = () => uni.removeStorageSync(STORAGE_KEYS.offlineFailed)

const send = (entry) =>
  new Promise((resolve, reject) => {
    uni.request({
      url: `${API_BASE_URL}${entry.url}`,
      method: (entry.method || 'POST').toUpperCase(),
      data: entry.data,
      timeout: REQUEST_TIMEOUT,
      header: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'X-Idempotency-Key': entry.id,
        ...(uni.getStorageSync(STORAGE_KEYS.token)
          ? { Authorization: `Bearer ${uni.getStorageSync(STORAGE_KEYS.token)}` }
          : {})
      },
      success: (res) => resolve(res),
      fail: (error) => reject(error)
    })
  })

/**
 * 按发生顺序重传。
 * - 401：登录失效，整个队列停下等重新登录；
 * - 断网 / 5xx：停下保留，下次再来；
 * - 业务错误（4xx 或 code≠0）：这条重试也没用，移出队列并记到「同步失败」里给用户看。
 */
export const flush = async ({ manual = false } = {}) => {
  if (flushing) return { synced: 0, failed: 0, pending: queueCount(), busy: true }
  const pending = readQueue()
  if (!pending.length) {
    if (manual) uni.showToast({ title: '没有待同步的记录', icon: 'none' })
    return { synced: 0, failed: 0, pending: 0 }
  }
  if (!offlineEnabled()) return { synced: 0, failed: 0, pending: pending.length }
  flushing = true
  let synced = 0
  let failed = 0
  let stopped = false
  try {
    // 队列头部是最新的，重传要按发生顺序，所以倒着跑
    for (const entry of [...pending].reverse()) {
      try {
        const res = await send(entry)
        const status = res.statusCode
        const body = res.data
        if (status === 401) {
          stopped = true
          break
        }
        if (status >= 200 && status < 300 && (!body || typeof body.code !== 'number' || body.code === 0)) {
          removeEntry(entry.id)
          synced++
          continue
        }
        if (status >= 500) {
          stopped = true
          break
        }
        removeEntry(entry.id)
        keepFailed(entry, (body && body.message) || `服务端返回 ${status}`)
        failed++
      } catch (e) {
        // 还是没网：保留这条以及后面的，等下次
        stopped = true
        break
      }
    }
  } finally {
    flushing = false
  }
  if (synced > 0) {
    uni.setStorageSync(STORAGE_KEYS.offlineLastSync, new Date().toLocaleString('zh-CN', { hour12: false }))
    if (manual) {
      uni.showToast({ title: `已同步 ${synced} 条${failed ? `，${failed} 条失败` : ''}`, icon: 'none' })
    }
  } else if (manual && failed > 0) {
    uni.showToast({ title: `${failed} 条同步失败，请查看明细`, icon: 'none' })
  } else if (manual && stopped) {
    uni.showToast({ title: '网络仍不可用，稍后自动重试', icon: 'none' })
  }
  return { synced, failed, pending: queueCount(), stopped }
}

/** 监听网络恢复与回到前台，自动补传；重复调用不会重复注册 */
let watching = false
export const watchNetwork = () => {
  if (watching) return
  watching = true
  uni.onNetworkStatusChange((res) => {
    if (res.isConnected && queueCount() > 0) {
      setTimeout(() => flush(), 1200)
    }
  })
}
