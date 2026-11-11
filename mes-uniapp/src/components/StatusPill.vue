<script setup>
import { computed } from 'vue'
import { statusMeta } from '../utils/format'

const props = defineProps({
  status: { type: String, default: '' },
  size: { type: String, default: 'normal' }
})

const meta = computed(() => statusMeta(props.status))
</script>

<template>
  <view class="pill" :class="[`tone-${meta.tone}`, size === 'small' ? 'pill-small' : '']">
    <view class="dot"></view>
    <text>{{ meta.label }}</text>
  </view>
</template>

<style scoped>
.pill {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  padding: 5rpx 16rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
  font-weight: 600;
  line-height: 1.4;
  white-space: nowrap;
}

.pill-small {
  padding: 3rpx 12rpx;
  font-size: 20rpx;
}

.dot {
  width: 8rpx;
  height: 8rpx;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.85;
}

.tone-slate {
  background: #f1f5f9;
  color: #64748b;
}

.tone-blue {
  background: #eff6ff;
  color: #2563eb;
}

.tone-orange {
  background: #fff7ed;
  color: #c2410c;
}

.tone-green {
  background: #f0fdf4;
  color: #15803d;
}

.tone-purple {
  background: #f5f3ff;
  color: #7c3aed;
}

.tone-red {
  background: #fef2f2;
  color: #b91c1c;
}
</style>
