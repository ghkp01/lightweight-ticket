import { http } from './request'

const STORAGE_PUSH_TOKEN = 'mes.push.token'

/**
 * 移动端推送登记：
 * App 端拿到厂商推送的 clientId（极光 / 个推，uni-app 里是 uni.getPushClientId）后，
 * 调后端 /push/devices 登记，之后站内通知会顺带推到手机。
 * 没接厂商推送（后端 mes.push.provider = none）时这里直接跳过，不影响任何功能。
 * 微信小程序 / 公众号走的是另一种通道：把 openid 当 token，platform 传 WECHAT-MP。
 */
const detectPlatform = () => {
  try {
    const info = typeof uni.getSystemInfoSync === 'function' ? uni.getSystemInfoSync() : {}
    const os = String(info.osName || info.platform || '').toLowerCase()
    if (os.includes('ios')) return 'APP-IOS'
    if (os.includes('harmony') || os.includes('ohos')) return 'APP-HARMONY'
    if (os.includes('android')) return 'APP-ANDROID'
    return ''
  } catch (e) {
    return ''
  }
}

const getClientId = () =>
  new Promise((resolve) => {
    if (typeof uni.getPushClientId !== 'function') {
      resolve('')
      return
    }
    uni.getPushClientId({
      success: (res) => resolve((res && res.cid) || ''),
      fail: () => resolve('')
    })
  })

export const registerPush = async () => {
  try {
    const platform = detectPlatform()
    if (!platform) return ''
    const config = await http.get('/push/config', undefined, { silent: true })
    if (!config || !config.enabled) return ''
    const token = await getClientId()
    if (!token) return ''
    await http.post(
      '/push/devices',
      { platform, provider: config.provider, token },
      { silent: true }
    )
    uni.setStorageSync(STORAGE_PUSH_TOKEN, token)
    return token
  } catch (e) {
    // 推送登记失败不能影响登录与业务
    return ''
  }
}

export const unregisterPush = async () => {
  const token = uni.getStorageSync(STORAGE_PUSH_TOKEN)
  if (!token) return
  try {
    await http.delete('/push/devices', { token }, { silent: true })
  } catch (e) {
    // 忽略
  }
  uni.removeStorageSync(STORAGE_PUSH_TOKEN)
}
