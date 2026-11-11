<script setup>
import { computed, ref, watch } from 'vue'
import { masterDataApi } from '../api'
import { productCategoryName } from '../utils/format'

const props = defineProps({
  modelValue: { type: [Number, String], default: null },
  placeholder: { type: String, default: '选择产品' },
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'change'])

const products = ref([])
const visible = ref(false)
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
const keyword = ref('')

const selected = computed(() => {
  if (props.modelValue === null || props.modelValue === undefined || props.modelValue === '') return null
  return products.value.find((item) => String(item.id) === String(props.modelValue)) || null
})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return products.value
  return products.value.filter((item) =>
    `${item.code || ''} ${item.name || ''} ${item.spec || ''}`.toLowerCase().includes(kw)
  )
})

const load = async (force = false) => {
  if (loading.value) return
  if (loaded.value && !force) return
  loading.value = true
  error.value = ''
  try {
    products.value = (await masterDataApi.products()) || []
    loaded.value = true
  } catch (e) {
    error.value = '产品列表加载失败，请检查后端服务'
  } finally {
    loading.value = false
  }
}

const open = () => {
  if (props.disabled) return
  visible.value = true
  keyword.value = ''
  load()
}

const close = () => {
  visible.value = false
}

const choose = (item) => {
  emit('update:modelValue', item.id)
  emit('change', item)
  close()
}

watch(
  () => props.modelValue,
  () => {
    if (!loaded.value && props.modelValue) load()
  },
  { immediate: true }
)
</script>

<template>
  <view class="product-picker">
    <view class="picker-line" :class="{ disabled }" @tap="open">
      <text v-if="selected" class="selected ellipsis">
        {{ selected.code }} · {{ selected.name }}
      </text>
      <text v-else class="placeholder">{{ placeholder }}</text>
      <view class="chevron"></view>
    </view>

    <view v-if="visible" class="popup-mask" @tap="close">
      <view class="popup-panel picker-panel" @tap.stop>
        <view class="popup-head">
          <text class="popup-title">选择产品</text>
          <view class="popup-close" @tap="close">×</view>
        </view>

        <view class="input-box search-box">
          <input
            v-model="keyword"
            class="input"
            placeholder="搜索编码 / 名称 / 规格"
            confirm-type="search"
          />
        </view>

        <view v-if="loading" class="loading-block">正在加载产品…</view>
        <view v-else-if="error" class="picker-error">
          <text>{{ error }}</text>
          <view class="btn btn-soft btn-sm" @tap="load(true)">重新加载</view>
        </view>
        <scroll-view v-else scroll-y class="picker-list">
          <view
            v-for="item in filtered"
            :key="item.id"
            class="product-row"
            :class="{ active: String(item.id) === String(modelValue) }"
            @tap="choose(item)"
          >
            <view class="flex1">
              <view class="row gap8">
                <text class="mono product-code">{{ item.code }}</text>
                <text class="tag">{{ productCategoryName(item.category) }}</text>
              </view>
              <view class="product-name">{{ item.name }}</view>
              <view class="product-meta">
                {{ item.spec || '无规格' }} · 库存 {{ item.stock ?? 0 }} {{ item.unit || '' }}
              </view>
            </view>
            <view class="check">{{ String(item.id) === String(modelValue) ? '✓' : '' }}</view>
          </view>
          <view v-if="!filtered.length" class="empty">
            <view class="empty-mark">?</view>
            <text>没有匹配的产品</text>
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.picker-line {
  gap: 12rpx;
}

.picker-line.disabled {
  opacity: 0.6;
}

.selected {
  flex: 1;
  color: #0f172a;
  font-size: 27rpx;
}

.placeholder {
  flex: 1;
  color: #94a3b8;
}

.picker-panel {
  height: 78vh;
  display: flex;
  flex-direction: column;
}

.search-box {
  flex: none;
  margin-bottom: 18rpx;
}

.picker-list {
  flex: 1;
  min-height: 0;
}

.product-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 22rpx 18rpx;
  border-bottom: 1rpx solid #f1f5f9;
}

.product-row.active {
  background: #fff7ed;
}

.product-code {
  color: #334155;
  font-size: 25rpx;
}

.product-name {
  margin-top: 6rpx;
  color: #0f172a;
  font-size: 28rpx;
  font-weight: 600;
}

.product-meta {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 22rpx;
}

.check {
  width: 44rpx;
  color: #f97316;
  font-size: 30rpx;
  font-weight: 700;
  text-align: center;
}

.picker-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20rpx;
  padding: 60rpx 20rpx;
  color: #94a3b8;
  font-size: 24rpx;
}
</style>
