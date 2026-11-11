<script>
import { loadConfigs } from './utils/appConfig'
import { flush, loadRemoteStatus, queueCount, watchNetwork } from './utils/offlineQueue'

export default {
  onLaunch() {
    // 登录态由 utils/auth.js 统一维护，页面 onShow 时做轻量守卫
    this.refreshConfig()
    // 离线队列：启动读一次服务端开关，监听网络恢复，顺手把上次没传完的补上
    loadRemoteStatus()
    watchNetwork()
    if (queueCount() > 0) {
      setTimeout(() => flush(), 3000)
    }
  },
  onShow() {
    // 回到前台时同步一次系统配置，水印开关改完不用杀进程
    this.refreshConfig(true)
    // 熄屏或切后台回来，先补传离线记录
    if (queueCount() > 0) {
      setTimeout(() => flush(), 1500)
    }
  },
  onHide() {},
  methods: {
    async refreshConfig(notify) {
      await loadConfigs()
      if (notify) uni.$emit('mes:config-changed')
    }
  }
}
</script>

<style>
page {
  --ink: #0f172a;
  --navy: #111827;
  --navy-soft: #1f2937;
  --orange: #f97316;
  --orange-dark: #ea580c;
  --bg: #f3f4f6;
  --line: #e5e7eb;
  --muted: #94a3b8;
  background: var(--bg);
  color: var(--ink);
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', 'Microsoft YaHei', sans-serif;
  font-size: 27rpx;
  line-height: 1.55;
  -webkit-font-smoothing: antialiased;
}

view,
text,
image,
button,
input,
textarea,
scroll-view,
picker {
  box-sizing: border-box;
}

button {
  margin: 0;
  padding: 0;
  border-radius: 0;
  background: transparent;
  color: inherit;
  font-size: inherit;
  line-height: 1.2;
}

button::after {
  border: none;
}

input,
textarea {
  color: var(--ink);
  font-size: 28rpx;
}

.mes-page {
  min-height: 100vh;
  padding-bottom: calc(40rpx + env(safe-area-inset-bottom));
}

.page-with-bar {
  padding-bottom: calc(190rpx + env(safe-area-inset-bottom));
}

.hero {
  position: relative;
  overflow: hidden;
  padding: 38rpx 32rpx 40rpx;
  background: linear-gradient(155deg, #0b1220 0%, #111827 48%, #1f2937 100%);
  color: #ffffff;
}

.hero::before {
  content: '';
  position: absolute;
  top: -160rpx;
  right: -120rpx;
  width: 420rpx;
  height: 420rpx;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(249, 115, 22, 0.32) 0%, rgba(249, 115, 22, 0) 68%);
}

.hero::after {
  content: '';
  position: absolute;
  left: 32rpx;
  right: 32rpx;
  bottom: 0;
  height: 2rpx;
  background: linear-gradient(90deg, rgba(249, 115, 22, 0.9), rgba(249, 115, 22, 0));
}

.hero-inner {
  position: relative;
  z-index: 1;
}

.hero-eyebrow {
  display: flex;
  align-items: center;
  gap: 12rpx;
  color: #fdba74;
  font-size: 21rpx;
  letter-spacing: 3rpx;
  text-transform: uppercase;
}

.hero-eyebrow::before {
  content: '';
  width: 28rpx;
  height: 4rpx;
  background: var(--orange);
}

.hero-title {
  margin-top: 16rpx;
  font-size: 44rpx;
  font-weight: 700;
  letter-spacing: 1rpx;
  line-height: 1.25;
}

.hero-sub {
  margin-top: 10rpx;
  color: #cbd5e1;
  font-size: 25rpx;
}

.content {
  padding: 24rpx 24rpx 0;
}

.panel {
  padding: 28rpx;
  margin-bottom: 20rpx;
  background: #ffffff;
  border: 1rpx solid rgba(15, 23, 42, 0.05);
  border-radius: 20rpx;
  box-shadow: 0 10rpx 30rpx rgba(15, 23, 42, 0.05);
}

.panel-flat {
  box-shadow: none;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20rpx;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 14rpx;
  margin-bottom: 18rpx;
  color: var(--ink);
  font-size: 30rpx;
  font-weight: 700;
}

.section-title::before {
  content: '';
  width: 8rpx;
  height: 30rpx;
  border-radius: 8rpx;
  background: linear-gradient(180deg, var(--orange), #fb923c);
}

.section-title.tight {
  margin-bottom: 0;
}

.muted {
  color: var(--muted);
}

.small {
  font-size: 23rpx;
}

.tiny {
  font-size: 21rpx;
}

.mono {
  font-family: 'SFMono-Regular', Menlo, Consolas, 'Liberation Mono', monospace;
  letter-spacing: 0.4rpx;
}

.strong {
  font-weight: 700;
}

.num {
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-weight: 700;
  letter-spacing: 0.5rpx;
}

.row {
  display: flex;
  align-items: center;
}

.between {
  justify-content: space-between;
}

.center {
  justify-content: center;
}

.wrap {
  flex-wrap: wrap;
}

.gap8 {
  gap: 8rpx;
}

.gap12 {
  gap: 12rpx;
}

.gap16 {
  gap: 16rpx;
}

.flex1 {
  flex: 1;
  min-width: 0;
}

.ellipsis {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.divider {
  height: 1rpx;
  margin: 22rpx 0;
  background: var(--line);
}

.tag {
  display: inline-flex;
  align-items: center;
  padding: 4rpx 14rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  color: #475569;
  font-size: 21rpx;
  line-height: 1.5;
}

.tag-orange {
  background: rgba(249, 115, 22, 0.12);
  color: #c2410c;
}

.tag-dark {
  background: rgba(17, 24, 39, 0.08);
  color: #1f2937;
}

.btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 84rpx;
  padding: 0 30rpx;
  border-radius: 14rpx;
  font-size: 29rpx;
  font-weight: 600;
  letter-spacing: 0.5rpx;
}

.btn-primary {
  background: linear-gradient(135deg, #fb923c 0%, #f97316 55%, #ea580c 100%);
  color: #ffffff;
  box-shadow: 0 10rpx 24rpx rgba(249, 115, 22, 0.3);
}

.btn-dark {
  background: #111827;
  color: #ffffff;
}

.btn-ghost {
  background: #ffffff;
  border: 1rpx solid #d7dde6;
  color: #334155;
}

.btn-soft {
  background: #fff7ed;
  color: #c2410c;
  border: 1rpx solid #fed7aa;
}

.btn-danger {
  background: #fef2f2;
  color: #b91c1c;
  border: 1rpx solid #fecaca;
}

.btn-sm {
  height: 58rpx;
  padding: 0 22rpx;
  border-radius: 10rpx;
  font-size: 24rpx;
  font-weight: 500;
}

.btn-block {
  width: 100%;
}

.btn[disabled] {
  opacity: 0.55;
}

.btn-hover {
  opacity: 0.86;
}

.form-card {
  padding: 30rpx 28rpx;
  margin: 24rpx;
  background: #ffffff;
  border-radius: 20rpx;
  box-shadow: 0 10rpx 30rpx rgba(15, 23, 42, 0.05);
}

.field {
  margin-bottom: 28rpx;
}

.field:last-child {
  margin-bottom: 0;
}

.field-label {
  display: flex;
  align-items: center;
  gap: 6rpx;
  margin-bottom: 12rpx;
  color: #475569;
  font-size: 25rpx;
}

.field-label .req {
  color: var(--orange);
}

.input-box {
  display: flex;
  align-items: center;
  min-height: 82rpx;
  padding: 0 22rpx;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 12rpx;
}

.input-box:focus-within {
  border-color: #fdba74;
  background: #fffdfa;
}

.input-box .input {
  flex: 1;
  width: 100%;
  height: 80rpx;
  background: transparent;
}

.textarea-box {
  min-height: 148rpx;
  padding: 20rpx 22rpx;
}

.textarea-box .textarea {
  width: 100%;
  min-height: 108rpx;
  background: transparent;
  line-height: 1.6;
}

.picker-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 82rpx;
  padding: 0 22rpx;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 12rpx;
}

.picker-line .placeholder {
  color: #94a3b8;
}

.chevron {
  width: 14rpx;
  height: 14rpx;
  margin-left: 14rpx;
  border-right: 3rpx solid #94a3b8;
  border-bottom: 3rpx solid #94a3b8;
  transform: rotate(-45deg);
}

.chips {
  display: flex;
  gap: 14rpx;
  overflow-x: auto;
  white-space: nowrap;
}

.chip {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 58rpx;
  padding: 0 24rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 999rpx;
  background: #ffffff;
  color: #64748b;
  font-size: 24rpx;
}

.chip.active {
  border-color: #111827;
  background: #111827;
  color: #ffffff;
}

.chip.active-orange {
  border-color: #fdba74;
  background: #fff7ed;
  color: #c2410c;
  font-weight: 600;
}

.list-card {
  padding: 26rpx;
  margin-bottom: 18rpx;
  background: #ffffff;
  border: 1rpx solid rgba(15, 23, 42, 0.05);
  border-radius: 18rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.045);
}

.card-title {
  color: var(--ink);
  font-size: 29rpx;
  font-weight: 700;
}

.card-sub {
  margin-top: 6rpx;
  color: #64748b;
  font-size: 24rpx;
}

.progress {
  height: 12rpx;
  overflow: hidden;
  border-radius: 999rpx;
  background: #eef2f7;
}

.progress-inner {
  height: 100%;
  border-radius: 999rpx;
  background: linear-gradient(90deg, #fb923c, #f97316);
  transition: width 0.4s ease;
}

.kpi-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18rpx;
}

.kpi-card {
  position: relative;
  overflow: hidden;
  padding: 26rpx 24rpx;
  background: #ffffff;
  border: 1rpx solid rgba(15, 23, 42, 0.05);
  border-radius: 18rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.045);
}

.kpi-card::after {
  content: '';
  position: absolute;
  right: -26rpx;
  bottom: -40rpx;
  width: 120rpx;
  height: 120rpx;
  border-radius: 50%;
  background: rgba(249, 115, 22, 0.06);
}

.kpi-label {
  color: #64748b;
  font-size: 23rpx;
}

.kpi-value {
  margin-top: 8rpx;
  color: #111827;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 46rpx;
  font-weight: 700;
  line-height: 1.15;
}

.kpi-value small {
  margin-left: 4rpx;
  color: #94a3b8;
  font-size: 21rpx;
  font-weight: 500;
}

.kpi-foot {
  margin-top: 6rpx;
  color: #94a3b8;
  font-size: 21rpx;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16rpx;
}

.quick-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24rpx 8rpx;
  border-radius: 16rpx;
  background: #f8fafc;
  border: 1rpx solid #eef2f7;
}

.quick-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72rpx;
  height: 72rpx;
  margin-bottom: 12rpx;
  border-radius: 18rpx;
  background: #111827;
  color: #fdba74;
  font-size: 28rpx;
  font-weight: 700;
}

.quick-item.accent .quick-icon {
  background: linear-gradient(135deg, #fb923c, #ea580c);
  color: #ffffff;
}

.quick-name {
  color: #334155;
  font-size: 24rpx;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80rpx 40rpx;
  color: #94a3b8;
}

.empty-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100rpx;
  height: 100rpx;
  margin-bottom: 18rpx;
  border-radius: 50%;
  background: #f1f5f9;
  color: #cbd5e1;
  font-size: 40rpx;
  font-weight: 700;
}

.loading-block {
  padding: 60rpx 0;
  color: #94a3b8;
  font-size: 24rpx;
  text-align: center;
}

.action-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 60;
  display: flex;
  gap: 16rpx;
  padding: 18rpx 24rpx calc(18rpx + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.97);
  border-top: 1rpx solid var(--line);
  box-shadow: 0 -10rpx 30rpx rgba(15, 23, 42, 0.06);
}

.action-bar .btn {
  flex: 1;
}

.popup-mask {
  position: fixed;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  z-index: 900;
  display: flex;
  align-items: flex-end;
  background: rgba(15, 23, 42, 0.55);
}

.popup-panel {
  width: 100%;
  max-height: 88vh;
  padding: 30rpx 28rpx calc(30rpx + env(safe-area-inset-bottom));
  overflow-y: auto;
  background: #ffffff;
  border-radius: 28rpx 28rpx 0 0;
  animation: mes-slide-up 0.22s ease-out;
}

.popup-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24rpx;
}

.popup-title {
  font-size: 32rpx;
  font-weight: 700;
}

.popup-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56rpx;
  height: 56rpx;
  border-radius: 50%;
  background: #f1f5f9;
  color: #64748b;
  font-size: 30rpx;
}

@keyframes mes-slide-up {
  from {
    transform: translateY(30%);
    opacity: 0.6;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

.skeleton {
  height: 32rpx;
  border-radius: 8rpx;
  background: linear-gradient(90deg, #eef2f7 25%, #f8fafc 37%, #eef2f7 63%);
  background-size: 400% 100%;
  animation: mes-shimmer 1.4s ease infinite;
}

@keyframes mes-shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

.fab {
  position: fixed;
  right: 32rpx;
  bottom: calc(140rpx + env(safe-area-inset-bottom));
  z-index: 50;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100rpx;
  height: 100rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #fb923c, #ea580c);
  color: #ffffff;
  font-size: 46rpx;
  font-weight: 300;
  box-shadow: 0 16rpx 34rpx rgba(249, 115, 22, 0.38);
}

.fab-text {
  position: fixed;
  right: 32rpx;
  bottom: calc(140rpx + env(safe-area-inset-bottom));
  z-index: 50;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 88rpx;
  padding: 0 32rpx;
  border-radius: 999rpx;
  background: linear-gradient(135deg, #fb923c, #ea580c);
  color: #ffffff;
  font-size: 27rpx;
  font-weight: 600;
  box-shadow: 0 16rpx 34rpx rgba(249, 115, 22, 0.36);
}

.safe-bottom {
  height: calc(30rpx + env(safe-area-inset-bottom));
}

.text-orange {
  color: var(--orange-dark);
}

.text-green {
  color: #15803d;
}

.text-red {
  color: #b91c1c;
}

.bg-soft {
  background: #f8fafc;
}
</style>
