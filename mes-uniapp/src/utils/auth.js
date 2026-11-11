import { STORAGE_KEYS } from './config'

export const getToken = () => uni.getStorageSync(STORAGE_KEYS.token) || ''

export const getUser = () => {
  const raw = uni.getStorageSync(STORAGE_KEYS.user)
  if (!raw) return null
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(raw)
  } catch (e) {
    return null
  }
}

export const setAuth = (token, user) => {
  uni.setStorageSync(STORAGE_KEYS.token, token || '')
  uni.setStorageSync(STORAGE_KEYS.user, user || null)
}

export const updateUser = (patch) => {
  const current = getUser() || {}
  const next = { ...current, ...patch }
  uni.setStorageSync(STORAGE_KEYS.user, next)
  return next
}

export const clearAuth = () => {
  uni.removeStorageSync(STORAGE_KEYS.token)
  uni.removeStorageSync(STORAGE_KEYS.user)
}

export const isLoggedIn = () => !!getToken()

/**
 * 页面级登录守卫：未登录时回到登录页，返回 false。
 */
export const ensureAuth = () => {
  if (isLoggedIn()) return true
  uni.reLaunch({ url: '/pages/login/login' })
  return false
}

export const goLogin = () => {
  uni.reLaunch({ url: '/pages/login/login' })
}
