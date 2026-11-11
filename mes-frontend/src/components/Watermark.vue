<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useConfigStore } from '../stores/config'

const auth = useAuthStore()
const config = useConfigStore()
const route = useRoute()

const background = ref('')
const visible = computed(() => config.watermarkEnabled && !!background.value)

/** 深色底（登录页）用浅色水印，浅色页面用深色水印 */
const isDarkSurface = computed(() => route.path === '/login')

const buildWatermark = () => {
  const canvas = document.createElement('canvas')
  const tileWidth = 300
  const tileHeight = 180
  const ratio = Math.min(window.devicePixelRatio || 1, 2)
  canvas.width = tileWidth * ratio
  canvas.height = tileHeight * ratio
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  ctx.scale(ratio, ratio)
  ctx.translate(tileWidth / 2, tileHeight / 2)
  ctx.rotate((-18 * Math.PI) / 180)
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'

  const dark = isDarkSurface.value
  const primary = dark ? 'rgba(255, 255, 255, 0.16)' : 'rgba(15, 23, 42, 0.10)'
  const secondary = dark ? 'rgba(255, 255, 255, 0.10)' : 'rgba(15, 23, 42, 0.07)'

  const user = auth.user
  const line1 = user
    ? `${user.realName || user.username} · ${user.username}`
    : '内部资料 禁止外传'
  const today = new Date().toLocaleDateString('sv-SE')
  const line2 = user ? `智造工单 ${today}` : '智造工单 MES'

  ctx.font = '600 13px "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillStyle = primary
  ctx.fillText(line1, 0, -9)
  ctx.font = '11px "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillStyle = secondary
  ctx.fillText(line2, 0, 12)

  background.value = canvas.toDataURL('image/png')
}

/**
 * 没登录时（登录页）配置接口本来就会 401，没必要发这个请求，
 * 水印直接用默认文案「内部资料 禁止外传」。
 * 登录 / 退出后重新取配置并换水印文字，保证内容跟着当前账号走。
 */
const refreshConfig = async (force = false) => {
  if (auth.token) {
    await config.load(force)
  }
  buildWatermark()
}

onMounted(() => refreshConfig())

watch(
  () => auth.token,
  () => refreshConfig(true)
)

watch(
  () => [auth.user?.username, auth.user?.realName, isDarkSurface.value],
  () => buildWatermark()
)
</script>

<template>
  <div
    v-if="visible"
    class="watermark-layer"
    :style="{ backgroundImage: `url(${background})` }"
    aria-hidden="true"
  ></div>
</template>

<style scoped>
.watermark-layer {
  position: fixed;
  inset: 0;
  z-index: 99999;
  pointer-events: none;
  background-repeat: repeat;
  background-size: 300px 180px;
  user-select: none;
}
</style>
