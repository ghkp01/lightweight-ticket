<script setup>
defineProps({
  mark: { type: String, default: '—' },
  text: { type: String, default: '暂无数据' },
  hint: { type: String, default: '' },
  actionText: { type: String, default: '' }
})

const emit = defineEmits(['action'])
</script>

<template>
  <view class="empty">
    <view class="empty-mark">{{ mark }}</view>
    <text class="empty-text">{{ text }}</text>
    <text v-if="hint" class="empty-hint">{{ hint }}</text>
    <view v-if="actionText" class="btn btn-soft btn-sm empty-action" @tap="emit('action')">
      {{ actionText }}
    </view>
  </view>
</template>

<style scoped>
.empty-text {
  color: #64748b;
  font-size: 27rpx;
  font-weight: 500;
}

.empty-hint {
  margin-top: 8rpx;
  color: #94a3b8;
  font-size: 22rpx;
  text-align: center;
  line-height: 1.6;
}

.empty-action {
  margin-top: 24rpx;
}
</style>
