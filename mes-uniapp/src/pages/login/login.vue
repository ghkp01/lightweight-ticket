<script setup>
import { reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { authApi } from '../../api'
import { DEMO_PASSWORD, DEMO_USERS } from '../../utils/config'
import { isLoggedIn, setAuth } from '../../utils/auth'
import { registerPush } from '../../utils/push'
import MesWatermark from '../../components/Watermark.vue'

const form = reactive({
  username: 'admin',
  password: '123456'
})
const loading = ref(false)
const showPassword = ref(false)

const pick = (user) => {
  form.username = user.username
  form.password = DEMO_PASSWORD
}

const submit = async () => {
  const username = form.username.trim()
  if (!username) {
    uni.showToast({ title: '请输入账号或手机号', icon: 'none' })
    return
  }
  if (!form.password) {
    uni.showToast({ title: '请输入密码', icon: 'none' })
    return
  }
  if (loading.value) return
  loading.value = true
  try {
    const data = await authApi.login(username, form.password)
    const user = {
      username: data.username || username,
      realName: data.realName || username,
      role: data.role || '',
      phone: data.phone || '',
      warehouseId: data.warehouseId || null,
      warehouseName: data.warehouseName || '',
      permissions: data.permissions || []
    }
    setAuth(data.token, user)
    // 登录后顺手登记推送设备（没接厂商推送时内部直接跳过，不影响登录）
    registerPush()
    uni.showToast({ title: '登录成功', icon: 'success', duration: 900 })
    setTimeout(() => {
      uni.switchTab({ url: '/pages/home/home' })
    }, 320)
  } catch (e) {
    // 错误提示由 request 统一处理
  } finally {
    loading.value = false
  }
}

onLoad(() => {
  if (isLoggedIn()) {
    uni.switchTab({ url: '/pages/home/home' })
  }
})
</script>

<template>
  <view class="login-page">
    <MesWatermark tone="dark" />
    <view class="login-glow"></view>
    <view class="login-top">
      <view class="brand">
        <view class="brand-mark">M</view>
        <view class="brand-copy">
          <text class="brand-name">智造工单</text>
          <text class="brand-sub">LIGHT MES · MOBILE</text>
        </view>
      </view>

      <view class="login-copy">
        <text class="login-eyebrow">WORKSHOP IN YOUR POCKET</text>
        <text class="login-title">把车间，\n装进口袋。</text>
        <text class="login-desc">工单执行、扫码领料、出入库与质量检验，现场一步到位。</text>
      </view>

      <view class="login-badges">
        <view class="badge"><text class="badge-num">7</text><text class="badge-label">业务场景</text></view>
        <view class="badge-line"></view>
        <view class="badge"><text class="badge-num">1</text><text class="badge-label">条交付主线</text></view>
        <view class="badge-line"></view>
        <view class="badge"><text class="badge-num">100%</text><text class="badge-label">库存可追溯</text></view>
      </view>
    </view>

    <view class="login-sheet">
      <view class="sheet-handle"></view>
      <text class="sheet-title">登录系统</text>
      <text class="sheet-sub">使用企业账号或已绑定手机号进入</text>

      <view class="login-field">
        <text class="login-label">账号 / 手机号</text>
        <view class="login-input">
          <view class="input-prefix">ID</view>
          <input
            v-model="form.username"
            class="input"
            placeholder="请输入账号或手机号"
            placeholder-class="input-placeholder"
            confirm-type="next"
          />
        </view>
      </view>

      <view class="login-field">
        <text class="login-label">密码</text>
        <view class="login-input">
          <view class="input-prefix">PW</view>
          <input
            v-model="form.password"
            class="input"
            type="text"
            :password="!showPassword"
            placeholder="请输入密码"
            placeholder-class="input-placeholder"
            confirm-type="done"
            @confirm="submit"
          />
          <view class="eye" @tap="showPassword = !showPassword">{{ showPassword ? '隐藏' : '显示' }}</view>
        </view>
      </view>

      <view class="login-btn" :class="{ disabled: loading }" @tap="submit">
        <text>{{ loading ? '正在登录…' : '进入工作台' }}</text>
        <text class="arrow">→</text>
      </view>

      <view class="demo-block">
        <view class="demo-head">
          <text class="demo-title">演示账号</text>
          <text class="demo-tip">密码均为 {{ DEMO_PASSWORD }}</text>
        </view>
        <view class="demo-grid">
          <view
            v-for="user in DEMO_USERS"
            :key="user.username"
            class="demo-card"
            :class="{ active: form.username === user.username }"
            @tap="pick(user)"
          >
            <text class="demo-role">{{ user.label }}</text>
            <text class="demo-user mono">{{ user.username }}</text>
            <text class="demo-desc">{{ user.desc }}</text>
          </view>
        </view>
      </view>

      <view class="login-foot">
        <text>API · localhost:8080</text>
        <text>v1.0.0</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background: #0b1220;
}

.login-glow {
  position: absolute;
  top: -180rpx;
  right: -160rpx;
  width: 560rpx;
  height: 560rpx;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(249, 115, 22, 0.4) 0%, rgba(249, 115, 22, 0) 70%);
}

.login-top {
  position: relative;
  z-index: 1;
  padding: calc(var(--status-bar-height) + 34rpx) 44rpx 46rpx;
}

.brand {
  display: flex;
  align-items: center;
  gap: 18rpx;
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 78rpx;
  height: 78rpx;
  border-radius: 18rpx;
  background: linear-gradient(135deg, #fb923c, #ea580c);
  color: #ffffff;
  font-size: 42rpx;
  font-weight: 800;
  box-shadow: 0 14rpx 30rpx rgba(249, 115, 22, 0.35);
}

.brand-copy {
  display: flex;
  flex-direction: column;
}

.brand-name {
  color: #ffffff;
  font-size: 32rpx;
  font-weight: 700;
  letter-spacing: 2rpx;
}

.brand-sub {
  margin-top: 2rpx;
  color: #64748b;
  font-size: 19rpx;
  letter-spacing: 3rpx;
}

.login-copy {
  display: flex;
  flex-direction: column;
  margin-top: 60rpx;
}

.login-eyebrow {
  color: #fdba74;
  font-size: 19rpx;
  letter-spacing: 4rpx;
}

.login-title {
  margin-top: 18rpx;
  color: #ffffff;
  font-size: 62rpx;
  font-weight: 800;
  line-height: 1.18;
  letter-spacing: 1rpx;
  white-space: pre-line;
}

.login-desc {
  margin-top: 18rpx;
  color: #94a3b8;
  font-size: 24rpx;
  line-height: 1.7;
}

.login-badges {
  display: flex;
  align-items: center;
  margin-top: 42rpx;
  padding: 22rpx 26rpx;
  border: 1rpx solid rgba(148, 163, 184, 0.16);
  border-radius: 16rpx;
  background: rgba(30, 41, 59, 0.45);
}

.badge {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.badge-num {
  color: #ffffff;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 34rpx;
  font-weight: 700;
}

.badge-label {
  margin-top: 2rpx;
  color: #64748b;
  font-size: 19rpx;
}

.badge-line {
  width: 1rpx;
  height: 44rpx;
  margin: 0 10rpx;
  background: rgba(148, 163, 184, 0.16);
}

.login-sheet {
  position: relative;
  z-index: 2;
  min-height: 56vh;
  padding: 20rpx 40rpx calc(30rpx + env(safe-area-inset-bottom));
  border-radius: 40rpx 40rpx 0 0;
  background: #ffffff;
  box-shadow: 0 -24rpx 60rpx rgba(0, 0, 0, 0.28);
}

.sheet-handle {
  width: 72rpx;
  height: 8rpx;
  margin: 0 auto 24rpx;
  border-radius: 999rpx;
  background: #e2e8f0;
}

.sheet-title {
  display: block;
  color: #0f172a;
  font-size: 38rpx;
  font-weight: 700;
}

.sheet-sub {
  display: block;
  margin-top: 6rpx;
  margin-bottom: 30rpx;
  color: #94a3b8;
  font-size: 23rpx;
}

.login-field {
  margin-bottom: 22rpx;
}

.login-label {
  display: block;
  margin-bottom: 10rpx;
  color: #475569;
  font-size: 23rpx;
}

.login-input {
  display: flex;
  align-items: center;
  height: 92rpx;
  padding: 0 20rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 14rpx;
  background: #f8fafc;
}

.input-prefix {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52rpx;
  height: 52rpx;
  margin-right: 16rpx;
  border-radius: 10rpx;
  background: #111827;
  color: #fdba74;
  font-size: 19rpx;
  font-weight: 700;
  letter-spacing: 1rpx;
}

.login-input .input {
  flex: 1;
  height: 90rpx;
  font-size: 29rpx;
}

.input-placeholder {
  color: #b6c2d1;
}

.eye {
  padding: 12rpx 4rpx 12rpx 18rpx;
  color: #94a3b8;
  font-size: 21rpx;
}

.login-btn {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 96rpx;
  margin-top: 8rpx;
  padding: 0 30rpx;
  border-radius: 14rpx;
  background: linear-gradient(135deg, #fb923c 0%, #f97316 55%, #ea580c 100%);
  color: #ffffff;
  font-size: 30rpx;
  font-weight: 700;
  box-shadow: 0 16rpx 36rpx rgba(249, 115, 22, 0.34);
}

.login-btn.disabled {
  opacity: 0.7;
}

.arrow {
  font-size: 34rpx;
  font-weight: 400;
}

.demo-block {
  margin-top: 34rpx;
}

.demo-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14rpx;
}

.demo-title {
  color: #334155;
  font-size: 23rpx;
  font-weight: 600;
}

.demo-tip {
  color: #94a3b8;
  font-size: 20rpx;
}

.demo-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14rpx;
}

.demo-card {
  display: flex;
  flex-direction: column;
  padding: 16rpx 18rpx;
  border: 1rpx solid #eef2f7;
  border-radius: 12rpx;
  background: #f8fafc;
}

.demo-card.active {
  border-color: #fdba74;
  background: #fff7ed;
}

.demo-role {
  color: #0f172a;
  font-size: 23rpx;
  font-weight: 600;
}

.demo-user {
  margin-top: 2rpx;
  color: #c2410c;
  font-size: 21rpx;
}

.demo-desc {
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 19rpx;
}

.login-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 32rpx;
  color: #cbd5e1;
  font-size: 18rpx;
  letter-spacing: 1rpx;
}
</style>
