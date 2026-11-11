import { http } from './request'
import { getToken } from './auth'

/**
 * 移动端配置读取：
 * - 配置存在 sys_config 表里，与 PC 端共用同一个开关
 * - 内存 + Storage 双层缓存，60 秒内不重复请求
 * - 未登录不请求（/system/configs/map 需要登录），直接用缓存兜底
 */
const CACHE_KEY = 'mes_configs'
const TTL = 60 * 1000

let memory = null
let fetchedAt = 0

const readStorage = () => {
  try {
    const raw = uni.getStorageSync(CACHE_KEY)
    return raw && typeof raw === 'object' ? raw : null
  } catch (e) {
    return null
  }
}

const writeStorage = (map) => {
  try {
    uni.setStorageSync(CACHE_KEY, map)
  } catch (e) {
    /* 存储失败不影响水印显示 */
  }
}

export const cachedConfigs = () => {
  if (memory) return memory
  memory = readStorage() || {}
  return memory
}

export const loadConfigs = async (force = false) => {
  const cached = cachedConfigs()
  const fresh = Date.now() - fetchedAt < TTL
  if (!force && fresh && Object.keys(cached).length) return cached
  if (!getToken()) return cached
  try {
    const map = await http.get('/system/configs/map', undefined, { silent: true })
    memory = map && typeof map === 'object' ? map : {}
    fetchedAt = Date.now()
    writeStorage(memory)
    return memory
  } catch (e) {
    return cached
  }
}

export const configValue = (key, fallback = '') => {
  const map = cachedConfigs()
  return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : fallback
}

export const watermarkEnabled = () => configValue('security.watermark.enabled', 'true') !== 'false'

export const clearConfigCache = () => {
  memory = null
  fetchedAt = 0
  try {
    uni.removeStorageSync(CACHE_KEY)
  } catch (e) {
    /* ignore */
  }
}
