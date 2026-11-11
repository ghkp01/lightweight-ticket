<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status: string }>()

const MAP: Record<string, { label: string; type: string }> = {
  DRAFT: { label: '草稿', type: 'draft' },
  CONFIRMED: { label: '已确认', type: 'primary' },
  IN_PRODUCTION: { label: '生产中', type: 'warning' },
  READY_TO_SHIP: { label: '待发货', type: 'warning' },
  SHIPPED: { label: '已发货', type: 'success' },
  COMPLETED: { label: '已完成', type: 'success' },
  CANCELLED: { label: '已取消', type: 'info' },
  SCHEDULED: { label: '已排产', type: 'primary' },
  RELEASED: { label: '已下达', type: 'primary' },
  IN_PROGRESS: { label: '进行中', type: 'warning' },
  REWORK: { label: '返工中', type: 'danger' },
  FINISHED: { label: '已完工', type: 'success' },
  CLOSED: { label: '已关闭', type: 'info' },
  SIGNED: { label: '已签收', type: 'success' },
  PASS: { label: '合格', type: 'success' },
  FAIL: { label: '不合格', type: 'danger' },
  PENDING: { label: '待处理', type: 'warning' },
  PENDING_QC: { label: '待检', type: 'warning' },
  PENDING_APPROVAL: { label: '待审批', type: 'warning' },
  APPROVED: { label: '已批准', type: 'primary' },
  REJECTED: { label: '已驳回', type: 'danger' },
  SKIPPED: { label: '已跳过', type: 'info' },
  PROCESSED: { label: '已处理', type: 'success' },
  DONE: { label: '已完成', type: 'success' }
}

const info = computed(() => MAP[props.status] || { label: props.status || '-', type: 'info' })
</script>

<template>
  <span class="status-tag" :class="info.type"><i></i>{{ info.label }}</span>
</template>
