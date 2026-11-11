<script setup>
import { computed, reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { authApi } from '../../api'
import { clearAuth, ensureAuth, getUser, updateUser } from '../../utils/auth'
import { API_BASE_URL, STORAGE_KEYS } from '../../utils/config'
import { flush, queueCount } from '../../utils/offlineQueue'
import { roleName } from '../../utils/format'
import MesWatermark from '../../components/Watermark.vue'

const user = ref(getUser() || {})
const phoneVisible = ref(false)
const phoneSaving = ref(false)
const phoneInput = ref('')
const passwordVisible = ref(false)
const passwordSaving = ref(false)
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const displayName = computed(() => user.value.realName || user.value.username || 'MES 用户')
const avatarText = computed(() => String(displayName.value).slice(0, 1).toUpperCase())
const roleText = computed(() => roleName(user.value.role))
const phoneText = computed(() => user.value.phone || '未绑定')
const permissionCount = computed(() => (user.value.permissions || []).length)

const offlineCount = ref(queueCount())
const syncing = ref(false)

const refreshOffline = () => {
  offlineCount.value = queueCount()
}

const syncNow = async () => {
  if (syncing.value) return
  syncing.value = true
  try {
    await flush({ manual: true })
  } finally {
    syncing.value = false
    refreshOffline()
  }
}

const syncUser = async () => {
  user.value = getUser() || {}
  try {
    const remote = await authApi.me()
    if (remote && typeof remote === 'object') {
      user.value = updateUser(remote)
    }
  } catch (e) {
    // 当前后端 /auth/me 可能未返回用户信息，使用本地缓存
  }
}

const openPhone = () => {
  phoneInput.value = user.value.phone || ''
  phoneVisible.value = true
}

const bindPhone = async () => {
  const phone = phoneInput.value.trim()
  if (!/^1[3-9]\d{9}$/.test(phone)) {
    uni.showToast({ title: '请输入正确的 11 位手机号', icon: 'none' })
    return
  }
  if (phoneSaving.value) return
  phoneSaving.value = true
  try {
    await authApi.bindPhone(phone)
    user.value = updateUser({ phone })
    uni.showToast({ title: '手机号已绑定', icon: 'success' })
    phoneVisible.value = false
  } catch (e) {
    // 统一提示
  } finally {
    phoneSaving.value = false
  }
}

const openPassword = () => {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  passwordVisible.value = true
}

const changePassword = async () => {
  if (!passwordForm.oldPassword) {
    uni.showToast({ title: '请输入原密码', icon: 'none' })
    return
  }
  if (passwordForm.newPassword.length < 6) {
    uni.showToast({ title: '新密码至少 6 位', icon: 'none' })
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    uni.showToast({ title: '两次输入的新密码不一致', icon: 'none' })
    return
  }
  if (passwordSaving.value) return
  passwordSaving.value = true
  try {
    await authApi.changePassword(passwordForm.oldPassword, passwordForm.newPassword)
    passwordVisible.value = false
    uni.showModal({
      title: '密码已修改',
      content: '请使用新密码重新登录。',
      showCancel: false,
      confirmColor: '#f97316',
      success: () => {
        clearAuth()
        uni.reLaunch({ url: '/pages/login/login' })
      }
    })
  } catch (e) {
    // 统一提示
  } finally {
    passwordSaving.value = false
  }
}

const clearCache = () => {
  uni.showModal({
    title: '清除本地缓存',
    content: '将清除扫码记录等本地缓存，登录状态与业务数据不受影响。',
    confirmColor: '#f97316',
    success: (res) => {
      if (!res.confirm) return
      uni.removeStorageSync(STORAGE_KEYS.scanHistory)
      uni.showToast({ title: '缓存已清除', icon: 'success' })
    }
  })
}

const logout = () => {
  uni.showModal({
    title: '退出登录',
    content: '确定要退出当前账号吗？',
    confirmColor: '#f97316',
    success: (res) => {
      if (!res.confirm) return
      clearAuth()
      uni.reLaunch({ url: '/pages/login/login' })
    }
  })
}

const goto = (url) => {
  uni.navigateTo({ url })
}

const entries = [
  { char: '入', name: '入库申请', desc: '到货、退货入库', url: '/pages/inbound/list' },
  { char: '出', name: '出库申请', desc: '领用、报废出库', url: '/pages/outbound/list' },
  { char: '检', name: '质量检验', desc: '来料、过程、完工', url: '/pages/quality/list' },
  { char: '良', name: '不良处置', desc: '返修、让步、报废', url: '/pages/defect/list' },
  { char: '信', name: '通知中心', desc: '不良预警与待办', url: '/pages/notice/list' },
  { char: '工', name: '生产工单', desc: '下达、领料、报工', url: '/pages/workorder/list', tab: true }
]

const openEntry = (item) => {
  if (item.tab) uni.switchTab({ url: item.url })
  else uni.navigateTo({ url: item.url })
}

onShow(() => {
  if (!ensureAuth()) return
  refreshOffline()
  syncUser()
  // 回到「我的」顺手补传一次，车间用户不用特意去点同步
  if (offlineCount.value > 0) flush()
})
</script>

<template>
  <view class="mes-page profile-page">
    <MesWatermark />
    <view class="hero profile-hero">
      <view class="hero-inner">
        <view class="profile-top">
          <view class="avatar">
            <text class="avatar-text">{{ avatarText }}</text>
            <view class="avatar-online"></view>
          </view>
          <view class="flex1">
            <view class="profile-name">{{ displayName }}</view>
            <view class="profile-account mono">@{{ user.username || '-' }}</view>
            <view class="row gap8 profile-tags">
              <text class="profile-role">{{ roleText }}</text>
              <text class="profile-phone">{{ phoneText }}</text>
            </view>
          </view>
        </view>

        <view class="profile-stats">
          <view class="p-stat">
            <text class="p-stat-num">{{ roleText }}</text>
            <text class="p-stat-label">当前角色</text>
          </view>
          <view class="p-line"></view>
          <view class="p-stat">
            <text class="p-stat-num">{{ user.warehouseName || '全部仓库' }}</text>
            <text class="p-stat-label">所属仓库</text>
          </view>
          <view class="p-line"></view>
          <view class="p-stat">
            <text class="p-stat-num">{{ permissionCount || '—' }}</text>
            <text class="p-stat-label">菜单权限</text>
          </view>
        </view>
      </view>
    </view>

    <view class="content">
      <view class="panel security-panel">
        <view class="section-title">账号与安全</view>

        <view class="menu-row" @tap="openPhone">
          <view class="menu-icon dark">手</view>
          <view class="flex1">
            <text class="menu-title">手机号</text>
            <text class="menu-desc">支持手机号登录，用于账号找回</text>
          </view>
          <text class="menu-value" :class="{ muted: !user.phone }">{{ phoneText }}</text>
          <view class="chevron"></view>
        </view>

        <view class="menu-row" @tap="openPassword">
          <view class="menu-icon">密</view>
          <view class="flex1">
            <text class="menu-title">登录密码</text>
            <text class="menu-desc">建议定期更换，至少 6 位</text>
          </view>
          <text class="menu-value muted">修改</text>
          <view class="chevron"></view>
        </view>

        <view class="menu-row">
          <view class="menu-icon">账</view>
          <view class="flex1">
            <text class="menu-title">登录账号</text>
            <text class="menu-desc">账号由系统管理员分配</text>
          </view>
          <text class="menu-value mono">{{ user.username || '-' }}</text>
        </view>
      </view>

      <view class="panel">
        <view class="section-title">现场入口</view>
        <view class="entry-grid">
          <view v-for="item in entries" :key="item.name" class="entry-card" @tap="openEntry(item)">
            <view class="entry-icon">{{ item.char }}</view>
            <text class="entry-name">{{ item.name }}</text>
            <text class="entry-desc">{{ item.desc }}</text>
          </view>
        </view>
      </view>

      <view class="panel">
        <view class="section-title">离线与同步</view>
        <view class="menu-row" @tap="goto('/pages/offline/list')">
          <view class="menu-icon" :class="{ warn: offlineCount > 0 }">离</view>
          <view class="flex1">
            <text class="menu-title">离线待同步</text>
            <text class="menu-desc">断网时的报工、检验、出入库先存在本机</text>
          </view>
          <text class="menu-value" :class="{ warn: offlineCount > 0 }">
            {{ offlineCount ? offlineCount + ' 条' : '已同步' }}
          </text>
          <view class="chevron"></view>
        </view>
        <view class="menu-row" @tap="syncNow">
          <view class="menu-icon">同</view>
          <view class="flex1">
            <text class="menu-title">立即同步</text>
            <text class="menu-desc">按发生顺序补传，重复上传不会重复计数</text>
          </view>
          <text class="menu-value muted">{{ syncing ? '同步中…' : '手动同步' }}</text>
          <view class="chevron"></view>
        </view>
      </view>

      <view class="panel">
        <view class="section-title">系统信息</view>
        <view class="info-row">
          <text class="info-key">接口地址</text>
          <text class="info-val mono ellipsis">{{ API_BASE_URL }}</text>
        </view>
        <view class="info-row">
          <text class="info-key">客户端版本</text>
          <text class="info-val">1.0.0 · uni-app</text>
        </view>
        <view class="info-row" @tap="clearCache">
          <text class="info-key">本地缓存</text>
          <text class="info-val link">清除</text>
        </view>
      </view>

      <view class="logout-btn" @tap="logout">退出登录</view>
      <view class="profile-foot">
        <text>智造工单 · LIGHT MES</text>
        <text>Workshop Operating System</text>
      </view>
    </view>

    <view v-if="phoneVisible" class="popup-mask" @tap="phoneVisible = false">
      <view class="popup-panel" @tap.stop>
        <view class="popup-head">
          <view>
            <text class="popup-title">绑定手机号</text>
            <text class="popup-sub">绑定后可使用手机号 + 密码登录</text>
          </view>
          <view class="popup-close" @tap="phoneVisible = false">×</view>
        </view>

        <view class="field">
          <view class="field-label"><text class="req">*</text><text>手机号</text></view>
          <view class="input-box">
            <input
              v-model="phoneInput"
              class="input"
              type="number"
              maxlength="11"
              placeholder="请输入 11 位手机号"
            />
          </view>
        </view>

        <view class="popup-actions">
          <view class="btn btn-ghost" @tap="phoneVisible = false">取消</view>
          <view class="btn btn-primary" :class="{ disabled: phoneSaving }" @tap="bindPhone">
            {{ phoneSaving ? '绑定中…' : '确认绑定' }}
          </view>
        </view>
      </view>
    </view>

    <view v-if="passwordVisible" class="popup-mask" @tap="passwordVisible = false">
      <view class="popup-panel" @tap.stop>
        <view class="popup-head">
          <view>
            <text class="popup-title">修改密码</text>
            <text class="popup-sub">修改成功后需要重新登录</text>
          </view>
          <view class="popup-close" @tap="passwordVisible = false">×</view>
        </view>

        <view class="field">
          <view class="field-label"><text class="req">*</text><text>原密码</text></view>
          <view class="input-box">
            <input v-model="passwordForm.oldPassword" class="input" password placeholder="请输入原密码" />
          </view>
        </view>

        <view class="field">
          <view class="field-label"><text class="req">*</text><text>新密码</text></view>
          <view class="input-box">
            <input v-model="passwordForm.newPassword" class="input" password placeholder="至少 6 位" />
          </view>
        </view>

        <view class="field">
          <view class="field-label"><text class="req">*</text><text>确认新密码</text></view>
          <view class="input-box">
            <input
              v-model="passwordForm.confirmPassword"
              class="input"
              password
              placeholder="再次输入新密码"
            />
          </view>
        </view>

        <view class="popup-actions">
          <view class="btn btn-ghost" @tap="passwordVisible = false">取消</view>
          <view class="btn btn-primary" :class="{ disabled: passwordSaving }" @tap="changePassword">
            {{ passwordSaving ? '提交中…' : '确认修改' }}
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.profile-hero {
  padding-top: 34rpx;
  padding-bottom: 34rpx;
}

.profile-top {
  display: flex;
  align-items: center;
  gap: 22rpx;
}

.avatar {
  position: relative;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 118rpx;
  height: 118rpx;
  border-radius: 28rpx;
  background: linear-gradient(145deg, #fb923c, #ea580c);
  box-shadow: 0 12rpx 28rpx rgba(249, 115, 22, 0.32);
}

.avatar-text {
  color: #ffffff;
  font-size: 52rpx;
  font-weight: 800;
}

.avatar-online {
  position: absolute;
  right: -2rpx;
  bottom: -2rpx;
  width: 24rpx;
  height: 24rpx;
  border: 4rpx solid #111827;
  border-radius: 50%;
  background: #22c55e;
}

.profile-name {
  color: #ffffff;
  font-size: 38rpx;
  font-weight: 700;
}

.profile-account {
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 21rpx;
}

.profile-tags {
  margin-top: 12rpx;
}

.profile-role,
.profile-phone {
  padding: 4rpx 14rpx;
  border-radius: 999rpx;
  font-size: 19rpx;
}

.profile-role {
  background: rgba(249, 115, 22, 0.15);
  color: #fdba74;
}

.profile-phone {
  background: rgba(148, 163, 184, 0.12);
  color: #cbd5e1;
}

.profile-stats {
  display: flex;
  align-items: center;
  margin-top: 30rpx;
  padding-top: 24rpx;
  border-top: 1rpx solid rgba(148, 163, 184, 0.16);
}

.p-stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.p-stat-num {
  overflow: hidden;
  color: #ffffff;
  font-size: 24rpx;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.p-stat-label {
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 18rpx;
}

.p-line {
  width: 1rpx;
  height: 36rpx;
  margin: 0 14rpx;
  background: rgba(148, 163, 184, 0.16);
}

.security-panel {
  padding-bottom: 10rpx;
}

.menu-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 20rpx 0;
  border-top: 1rpx solid #f1f5f9;
}

.menu-row:first-of-type {
  border-top: none;
}

.menu-icon {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 60rpx;
  height: 60rpx;
  border-radius: 14rpx;
  background: #f1f5f9;
  color: #475569;
  font-size: 23rpx;
  font-weight: 700;
}

.menu-icon.dark {
  background: #111827;
  color: #fdba74;
}

.menu-icon.warn {
  background: #fff7ed;
  color: #c2410c;
}

.menu-value.warn {
  color: #c2410c;
  font-weight: 600;
}

.menu-title {
  display: block;
  color: #1f2937;
  font-size: 26rpx;
  font-weight: 600;
}

.menu-desc {
  display: block;
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 20rpx;
}

.menu-value {
  flex: none;
  max-width: 220rpx;
  overflow: hidden;
  color: #334155;
  font-size: 23rpx;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.entry-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14rpx;
}

.entry-card {
  display: flex;
  flex-direction: column;
  padding: 20rpx;
  border: 1rpx solid #eef2f7;
  border-radius: 14rpx;
  background: #f8fafc;
}

.entry-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52rpx;
  height: 52rpx;
  margin-bottom: 12rpx;
  border-radius: 12rpx;
  background: #111827;
  color: #fdba74;
  font-size: 22rpx;
  font-weight: 700;
}

.entry-name {
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 600;
}

.entry-desc {
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 19rpx;
}

.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  padding: 18rpx 0;
  border-top: 1rpx solid #f1f5f9;
}

.info-row:first-of-type {
  border-top: none;
}

.info-key {
  flex: none;
  color: #64748b;
  font-size: 24rpx;
}

.info-val {
  color: #334155;
  font-size: 22rpx;
  text-align: right;
}

.info-val.link {
  color: #c2410c;
  font-weight: 600;
}

.logout-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 86rpx;
  margin: 24rpx 0 0;
  border: 1rpx solid #fecaca;
  border-radius: 14rpx;
  background: #ffffff;
  color: #b91c1c;
  font-size: 27rpx;
  font-weight: 600;
}

.profile-foot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;
  padding: 30rpx 0 20rpx;
  color: #b6c2d1;
  font-size: 19rpx;
  letter-spacing: 1rpx;
}

.popup-sub {
  display: block;
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 21rpx;
}

.popup-actions {
  display: flex;
  gap: 14rpx;
  margin-top: 12rpx;
}

.popup-actions .btn {
  flex: 1;
}
</style>
