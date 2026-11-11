import type { Router } from 'vue-router'
import { api } from '../api'
import { noticeTarget } from './noticeLink'

/**
 * 浏览器桌面通知（在线时）：
 * 用 Notification API 在右下角弹系统通知，点一下回到页面并跳到对应单据。
 * 前提：Chrome / Edge 等支持通知的浏览器 + https 或 localhost（http 局域网地址拿不到权限）。
 * 只在用户主动开启后才会申请权限与轮询；页面关掉就不再弹（要离线也能收，走 Web Push）。
 */
const ENABLED_KEY = 'mes.desktop-notice.enabled'
const LAST_ID_KEY = 'mes.desktop-notice.lastId'
const POLL_MS = 30000

export type DesktopPermission = 'unsupported' | 'default' | 'granted' | 'denied'

let timer: number | undefined

export const desktopSupported = () =>
  typeof window !== 'undefined' &&
  'Notification' in window &&
  (window.isSecureContext !== false || ['localhost', '127.0.0.1'].includes(window.location.hostname))

export const desktopPermission = (): DesktopPermission => {
  if (!desktopSupported()) return 'unsupported'
  return Notification.permission as DesktopPermission
}

export const desktopEnabled = () =>
  desktopSupported() && Notification.permission === 'granted' && localStorage.getItem(ENABLED_KEY) === '1'

export const desktopPermissionText = () => {
  const state = desktopPermission()
  if (state === 'unsupported') return '当前浏览器或用 http 局域网地址访问时不能用桌面通知，请用 Chrome / Edge 打开 localhost 或 HTTPS 地址'
  if (state === 'denied') return '浏览器已拒绝通知权限，点地址栏左侧的站点设置把「通知」改成允许'
  if (state === 'granted') return desktopEnabled() ? '已开启，新消息会在桌面右下角弹出' : '浏览器已授权，打开开关即可开始接收'
  return '开启后，新的待办与预警会在桌面右下角弹出提醒'
}

const readLatestId = async () => {
  try {
    const rows = (await api.get('/notices', { limit: 1 })) as any[]
    const latest = Array.isArray(rows) && rows.length ? Number(rows[0].id) || 0 : 0
    localStorage.setItem(LAST_ID_KEY, String(latest))
    return latest
  } catch {
    return 0
  }
}

export const enableDesktopNotice = async (): Promise<{ ok: boolean; message: string }> => {
  if (!desktopSupported()) {
    return { ok: false, message: desktopPermissionText() }
  }
  let permission = Notification.permission
  if (permission === 'default') {
    permission = await Notification.requestPermission()
  }
  if (permission !== 'granted') {
    return { ok: false, message: desktopPermissionText() }
  }
  localStorage.setItem(ENABLED_KEY, '1')
  await readLatestId()
  try {
    new Notification('桌面通知已开启', {
      body: '有新的待办或预警时，会在桌面右下角弹出提醒，点一下直接去处理',
      tag: 'mes-desktop-on'
    })
  } catch {
    // 个别浏览器在无图标/无 SW 时创建失败，权限已拿到，不影响后续
  }
  return { ok: true, message: '已开启桌面通知' }
}

export const disableDesktopNotice = () => {
  localStorage.removeItem(ENABLED_KEY)
  stopDesktopNotice()
}

const showNotification = (item: any, router: Router) => {
  try {
    const notification = new Notification(String(item.title || '新的消息'), {
      body: String(item.content || ''),
      tag: 'mes-notice-' + item.id,
      // 同一条消息只弹一次，多标签页也不会重复
      silent: false
    })
    notification.onclick = () => {
      window.focus()
      api.put(`/notices/${item.id}/read`).catch(() => undefined)
      const target = noticeTarget(item)
      if (target) {
        router.push({ path: target.path, query: target.query })
      }
      notification.close()
    }
  } catch {
    // 通知创建失败不影响业务
  }
}

const poll = async (router: Router, onNew?: () => void) => {
  try {
    const since = Number(localStorage.getItem(LAST_ID_KEY) || 0)
    const rows = (await api.get('/notices/pending', { since, limit: 10 })) as any[]
    if (!Array.isArray(rows) || !rows.length) return
    const maxId = rows.reduce((max, row) => Math.max(max, Number(row.id) || 0), since)
    localStorage.setItem(LAST_ID_KEY, String(maxId))
    // 第一次只记基线，不把历史未读一次性弹出来
    if (!since) return
    ;[...rows].reverse().forEach((item) => showNotification(item, router))
    onNew?.()
  } catch {
    // 轮询失败静默，下一轮继续
  }
}

export const startDesktopNotice = (router: Router, onNew?: () => void) => {
  stopDesktopNotice()
  if (!desktopEnabled()) return
  poll(router, onNew)
  timer = window.setInterval(() => poll(router, onNew), POLL_MS)
}

export const stopDesktopNotice = () => {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

/** 手动发一条测试通知，确认浏览器设置是否生效 */
export const testDesktopNotice = () => {
  if (!desktopEnabled()) return false
  try {
    new Notification('测试通知', {
      body: '看到这条说明桌面通知已生效',
      tag: 'mes-desktop-test'
    })
    return true
  } catch {
    return false
  }
}

/** 重新对齐基线（比如刚点过全部已读），避免下次把旧消息当成新的弹出来 */
export const resetDesktopBaseline = readLatestId
