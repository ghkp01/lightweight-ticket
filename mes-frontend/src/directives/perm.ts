import { watchEffect } from 'vue'
import type { Directive } from 'vue'
import { useAuthStore } from '../stores/auth'

const stops = new WeakMap<HTMLElement, () => void>()

/**
 * 按钮级权限：权限刷新后（如管理员刚给角色加了权限）自动显隐，无需重新进页面。
 *
 * <p>早先的实现在 mounted 时直接把元素从 DOM 里删掉，权限后来变宽松也回不来；
 * 这里改成响应式控制 display，元素始终留在 DOM 里，由 watchEffect 跟随权限变化。</p>
 */
export const perm: Directive<HTMLElement, string> = {
  mounted(el, binding) {
    const auth = useAuthStore()
    const stop = watchEffect(() => {
      const allowed = !binding.value || auth.can(binding.value)
      el.style.display = allowed ? '' : 'none'
    })
    stops.set(el, stop)
  },
  updated(el, binding) {
    const auth = useAuthStore()
    el.style.display = !binding.value || auth.can(binding.value) ? '' : 'none'
  },
  unmounted(el) {
    stops.get(el)?.()
    stops.delete(el)
  }
}
