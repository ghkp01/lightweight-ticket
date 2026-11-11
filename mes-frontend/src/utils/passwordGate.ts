import { ref } from 'vue'

/**
 * 强制改密闸门。
 *
 * 后端在令牌里带了「还没改初始密码」的标记时，除了 /api/auth/** 之外一律返回 4023。
 * 前端拿到 4023 就把这个闸门打开，全局弹不可关闭的改密框；
 * 改成功换了新令牌后闸门关闭，用户正常继续操作。
 *
 * 放在模块级而不是 store 里：api.ts 的响应拦截器要用它，而 store 会反过来引 api，容易循环依赖。
 */
export const passwordGateOpen = ref(false)

/** 只提示一次，避免一屏请求同时 4023 弹出十几条提示 */
let notified = false

export const openPasswordGate = () => {
  passwordGateOpen.value = true
  notified = false
}

export const closePasswordGate = () => {
  passwordGateOpen.value = false
  notified = false
}

/** 返回 true 表示本次需要弹提示（同一次闸门只提示一次） */
export const shouldNotifyGate = () => {
  if (notified) return false
  notified = true
  return true
}
