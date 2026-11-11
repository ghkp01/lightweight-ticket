<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ priority?: string | null }>()

const MAP: Record<string, { label: string; type: string }> = {
  NORMAL: { label: '普通', type: 'normal' },
  URGENT: { label: '加急', type: 'urgent' },
  RUSH: { label: '特急', type: 'rush' }
}

const info = computed(() => MAP[props.priority || 'NORMAL'] || MAP.NORMAL)
</script>

<template>
  <span class="priority-tag" :class="info.type">{{ info.label }}</span>
</template>

<style scoped>
.priority-tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 20px;
  border: 1px solid transparent;
  font-size: 11px;
  line-height: 18px;
  white-space: nowrap;
}

.priority-tag.normal {
  color: #475467;
  background: #f2f4f7;
  border-color: #e4e7ec;
}

.priority-tag.urgent {
  color: #b54708;
  background: #fffaeb;
  border-color: #fedf89;
}

.priority-tag.rush {
  color: #fff;
  background: #f04438;
  border-color: #f04438;
  font-weight: 600;
}
</style>
