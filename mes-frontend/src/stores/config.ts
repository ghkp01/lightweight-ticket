import { defineStore } from 'pinia'
import { api } from '../api'

/**
 * 系统配置：前端启动时读一次 key → value。
 * 水印开关这类全局开关放这里，保存后所有页面立即生效，无需刷新。
 */
export const useConfigStore = defineStore('config', {
  state: () => ({
    values: {} as Record<string, string>,
    loaded: false
  }),
  getters: {
    /** 默认开启：只有明确写成 false 才关闭水印 */
    watermarkEnabled: (state) => state.values['security.watermark.enabled'] !== 'false'
  },
  actions: {
    async load(force = false) {
      if (this.loaded && !force) return
      // 未登录时配置接口必然 401，直接跳过，避免在登录页刷出「登录已失效」
      if (!localStorage.getItem('mes_token')) return
      try {
        const data = await api.get('/system/configs/map')
        this.values = data || {}
        this.loaded = true
      } catch {
        // 未登录或接口异常时保持默认值，不影响页面使用
      }
    },
    async setValue(key: string, value: string) {
      await api.put(`/system/configs/${encodeURIComponent(key)}`, { value })
      this.values = { ...this.values, [key]: value }
    }
  }
})
