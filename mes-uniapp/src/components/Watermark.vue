<template>
  <view v-if="visible" class="wm-layer" :class="tone === 'dark' ? 'wm-on-dark' : 'wm-on-light'">
    <view class="wm-grid">
      <view v-for="i in tiles" :key="i" class="wm-tile">
        <text class="wm-line1">{{ line1 }}</text>
        <text class="wm-line2">{{ line2 }}</text>
      </view>
    </view>
  </view>
</template>

<script>
import { getUser } from '../utils/auth'
import { loadConfigs, watermarkEnabled } from '../utils/appConfig'

const WEEK = ['日', '一', '二', '三', '四', '五', '六']

/**
 * 全站水印：固定在视口上的一层平铺文字，pointer-events: none 不挡点击。
 * 开关与 PC 端同一个配置项 security.watermark.enabled，关掉后所有页面一起消失。
 */
export default {
  name: 'MesWatermark',
  props: {
    // light: 浅色背景页面（默认）；dark: 深色背景（登录页）
    tone: { type: String, default: 'light' }
  },
  data() {
    const now = new Date()
    const pad = (n) => (n < 10 ? `0${n}` : `${n}`)
    return {
      enabled: watermarkEnabled(),
      user: getUser(),
      today: `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`,
      tiles: 48
    }
  },
  computed: {
    visible() {
      return this.enabled
    },
    line1() {
      const u = this.user
      if (!u) return '内部资料 禁止外传'
      return `${u.realName || u.username} · ${u.username}`
    },
    line2() {
      if (!this.user) return '智造工单 MES'
      const now = new Date()
      return `智造工单 ${this.today} 周${WEEK[now.getDay()]}`
    }
  },
  mounted() {
    this.resize()
    this.sync()
    uni.$on('mes:config-changed', this.sync)
  },
  beforeUnmount() {
    uni.$off('mes:config-changed', this.sync)
  },
  methods: {
    resize() {
      let width = 375
      let height = 667
      try {
        const info = uni.getSystemInfoSync()
        width = info.windowWidth || width
        height = info.windowHeight || height
      } catch (e) {
        /* 取不到尺寸就按默认值铺 */
      }
      // 每个图块 150×90（px），栅格放大到 170% 以覆盖旋转后的四角
      const cols = Math.ceil((width * 1.7) / 150)
      const rows = Math.ceil((height * 1.7) / 90)
      this.tiles = Math.min(Math.max(cols * rows, 24), 120)
    },
    async sync() {
      // 强制刷新一次：PC 端刚改完开关时，移动端切个页面就能跟上
      await loadConfigs(true)
      this.enabled = watermarkEnabled()
      this.user = getUser()
    }
  }
}
</script>

<style scoped>
.wm-layer {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 9000;
  overflow: hidden;
  pointer-events: none;
  user-select: none;
}

.wm-grid {
  position: absolute;
  left: -35%;
  top: -35%;
  width: 170%;
  height: 170%;
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  align-content: flex-start;
  transform: rotate(-18deg);
}

.wm-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 300rpx;
  height: 180rpx;
}

.wm-line1 {
  font-size: 22rpx;
  font-weight: 600;
  letter-spacing: 1rpx;
}

.wm-line2 {
  margin-top: 6rpx;
  font-size: 18rpx;
  letter-spacing: 0.5rpx;
}

.wm-on-light .wm-line1 {
  color: rgba(15, 23, 42, 0.12);
}

.wm-on-light .wm-line2 {
  color: rgba(15, 23, 42, 0.08);
}

.wm-on-dark .wm-line1 {
  color: rgba(255, 255, 255, 0.18);
}

.wm-on-dark .wm-line2 {
  color: rgba(255, 255, 255, 0.1);
}
</style>
