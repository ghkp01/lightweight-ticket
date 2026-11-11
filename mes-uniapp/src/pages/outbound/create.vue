<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { masterDataApi, outboundApi } from '../../api'
import { ensureAuth } from '../../utils/auth'
import { isQueued } from '../../utils/offlineQueue'
import { fmtQty, OUTBOUND_TYPES, warehouseTypeName } from '../../utils/format'
import ProductPicker from '../../components/ProductPicker.vue'
import MesWatermark from '../../components/Watermark.vue'

const warehouses = ref([])
const loadingBase = ref(false)
const saving = ref(false)
const savingComplete = ref(false)
const shipmentNo = ref('')

const form = reactive({
  type: 'MANUAL',
  warehouseId: null,
  remark: ''
})

let itemSeed = 1
const items = ref([{ key: itemSeed, productId: null, productName: '', unit: '', qty: '1' }])

const warehouseIndex = computed(() => {
  const index = warehouses.value.findIndex((item) => String(item.id) === String(form.warehouseId))
  return index < 0 ? 0 : index
})

const warehouseLabels = computed(() =>
  warehouses.value.map((item) => `${item.name}${item.type ? ` · ${warehouseTypeName(item.type)}` : ''}`)
)

const totalQty = computed(() =>
  items.value.reduce((sum, item) => sum + (Number(item.qty) || 0), 0)
)

const loadBase = async () => {
  loadingBase.value = true
  try {
    warehouses.value = (await masterDataApi.warehouses()) || []
    if (!form.warehouseId && warehouses.value.length) {
      const preferred = warehouses.value.find((item) => item.type === (shipmentNo.value ? 'FINISHED' : 'RAW'))
      form.warehouseId = (preferred || warehouses.value[0]).id
    }
  } catch (e) {
    // 统一提示
  } finally {
    loadingBase.value = false
  }
}

const onWarehouseChange = (event) => {
  const target = warehouses.value[Number(event.detail.value)]
  if (target) form.warehouseId = target.id
}

const addItem = () => {
  itemSeed += 1
  items.value.push({ key: itemSeed, productId: null, productName: '', unit: '', qty: '1' })
}

const removeItem = (index) => {
  if (items.value.length <= 1) {
    uni.showToast({ title: '至少保留一条明细', icon: 'none' })
    return
  }
  items.value.splice(index, 1)
}

const onProductChange = (index, product) => {
  const target = items.value[index]
  if (!target || !product) return
  target.productName = product.name
  target.unit = product.unit
  if (!target.qty || Number(target.qty) <= 0) target.qty = '1'
}

const validate = () => {
  if (!form.warehouseId) {
    uni.showToast({ title: '请选择出库仓库', icon: 'none' })
    return false
  }
  for (let i = 0; i < items.value.length; i += 1) {
    const item = items.value[i]
    if (!item.productId) {
      uni.showToast({ title: `请选择第 ${i + 1} 行产品`, icon: 'none' })
      return false
    }
    if (!item.qty || Number(item.qty) <= 0) {
      uni.showToast({ title: `第 ${i + 1} 行数量必须大于 0`, icon: 'none' })
      return false
    }
  }
  return true
}

const buildPayload = () => ({
  type: form.type,
  warehouseId: form.warehouseId,
  remark: form.remark.trim(),
  items: items.value.map((item) => ({
    productId: item.productId,
    qty: Number(item.qty)
  }))
})

const save = async (thenComplete) => {
  if (!validate()) return
  if (saving.value || savingComplete.value) return
  if (thenComplete) savingComplete.value = true
  else saving.value = true
  try {
    const id = await outboundApi.create(buildPayload())
    if (isQueued(id)) {
      uni.showModal({
        title: '已离线保存',
        content: '当前网络不可用，出库申请已存到手机本地，联网后自动上传，届时请到出库列表确认完成。',
        showCancel: false,
        confirmColor: '#f97316',
        success: () => uni.navigateBack({ delta: 1, fail: () => uni.redirectTo({ url: '/pages/outbound/list' }) })
      })
      return
    }
    if (thenComplete && id) {
      await outboundApi.complete(id)
    }
    uni.showToast({
      title: thenComplete ? '出库已完成' : '草稿已保存',
      icon: 'success'
    })
    setTimeout(() => {
      uni.navigateBack({
        delta: 1,
        fail: () => uni.redirectTo({ url: '/pages/outbound/list' })
      })
    }, 650)
  } catch (e) {
    // 统一提示
  } finally {
    saving.value = false
    savingComplete.value = false
  }
}

const prefillProduct = async (options) => {
  const productId = options?.productId
  const productCode = options?.productCode ? decodeURIComponent(options.productCode) : ''
  if (productId) {
    items.value[0].productId = Number(productId)
    items.value[0].qty = options?.qty || '1'
    return
  }
  if (productCode) {
    try {
      const records = (await masterDataApi.products(productCode)) || []
      const hit =
        records.find((row) => String(row.code || '').toUpperCase() === productCode.toUpperCase()) ||
        records[0]
      if (hit) {
        items.value[0].productId = hit.id
        items.value[0].productName = hit.name
        items.value[0].unit = hit.unit
      }
    } catch (e) {
      // 统一提示
    }
  }
}

onLoad((options) => {
  if (!ensureAuth()) return
  if (options?.shipmentNo) {
    shipmentNo.value = decodeURIComponent(options.shipmentNo)
    form.remark = `发货单 ${shipmentNo.value} 出库`
  }
  loadBase()
  prefillProduct(options)
})
</script>

<template>
  <view class="mes-page page-with-bar create-page">
    <MesWatermark />
    <view class="form-hero">
      <view class="hero-eyebrow">NEW OUTBOUND</view>
      <view class="form-title">新建出库申请</view>
      <view class="form-sub">选择仓库与物料，完成出库时统一扣减库存并留痕</view>
      <view v-if="shipmentNo" class="shipment-hint">
        <text class="shipment-tag">发货单</text>
        <text class="mono">{{ shipmentNo }}</text>
      </view>
      <view class="form-summary">
        <view class="sum-item"><text class="sum-num">{{ items.length }}</text><text class="sum-label">物料行</text></view>
        <view class="sum-line"></view>
        <view class="sum-item"><text class="sum-num">{{ fmtQty(totalQty) }}</text><text class="sum-label">合计数量</text></view>
        <view class="sum-line"></view>
        <view class="sum-item"><text class="sum-num">{{ OUTBOUND_TYPES.find((t) => t.value === form.type)?.label }}</text><text class="sum-label">出库类型</text></view>
      </view>
    </view>

    <view class="form-card">
      <view class="section-title">基本信息</view>

      <view class="field">
        <view class="field-label"><text class="req">*</text><text>出库类型</text></view>
        <view class="chips type-chips">
          <view
            v-for="item in OUTBOUND_TYPES"
            :key="item.value"
            class="chip"
            :class="{ active: form.type === item.value }"
            @tap="form.type = item.value"
          >
            {{ item.label }}
          </view>
        </view>
      </view>

      <view class="field">
        <view class="field-label"><text class="req">*</text><text>出库仓库</text></view>
        <picker
          :range="warehouseLabels"
          :value="warehouseIndex"
          :disabled="loadingBase || !warehouses.length"
          @change="onWarehouseChange"
        >
          <view class="picker-line">
            <text v-if="form.warehouseId" class="ellipsis">{{ warehouseLabels[warehouseIndex] }}</text>
            <text v-else class="placeholder">{{ loadingBase ? '加载仓库中…' : '请选择仓库' }}</text>
            <view class="chevron"></view>
          </view>
        </picker>
      </view>

      <view class="field">
        <view class="field-label"><text>备注 / 用途</text></view>
        <view class="input-box textarea-box">
          <textarea
            v-model="form.remark"
            class="textarea"
            maxlength="200"
            placeholder="如：发货单 SH... 出库、报废处理、样品领用"
          />
        </view>
        <view class="remark-count">{{ form.remark.length }}/200</view>
      </view>
    </view>

    <view class="form-card items-card">
      <view class="panel-head">
        <view class="section-title tight">出库明细</view>
        <view class="add-item" @tap="addItem">+ 添加物料</view>
      </view>

      <view v-for="(item, index) in items" :key="item.key" class="item-block">
        <view class="item-head">
          <view class="row gap8">
            <view class="item-seq">{{ index + 1 }}</view>
            <text class="item-title">物料行</text>
          </view>
          <text class="item-remove" @tap="removeItem(index)">移除</text>
        </view>

        <view class="field">
          <view class="field-label"><text class="req">*</text><text>产品</text></view>
          <ProductPicker
            v-model="item.productId"
            placeholder="选择出库产品"
            @change="(product) => onProductChange(index, product)"
          />
        </view>

        <view class="field">
          <view class="field-label"><text class="req">*</text><text>出库数量</text></view>
          <view class="input-box">
            <input v-model="item.qty" class="input" type="digit" placeholder="0" />
            <text class="unit-text">{{ item.unit || '单位' }}</text>
          </view>
        </view>
      </view>
    </view>

    <view class="tip-card">
      <text class="tip-title">出库提示</text>
      <text class="tip-line">· 单号按 OUT 前缀自动生成，状态从草稿开始。</text>
      <text class="tip-line">· 保存并完成会立即扣减库存；库存不足时后端会拒绝。</text>
      <text class="tip-line">· 发货场景建议在 Web 端发货单执行发货，本页用于仓库补录。</text>
    </view>

    <view class="action-bar">
      <view class="btn btn-ghost" :class="{ disabled: saving }" @tap="save(false)">
        {{ saving ? '保存中…' : '保存草稿' }}
      </view>
      <view class="btn btn-primary" :class="{ disabled: savingComplete }" @tap="save(true)">
        {{ savingComplete ? '处理中…' : '保存并完成' }}
      </view>
    </view>
  </view>
</template>

<style scoped>
.create-page {
  padding-bottom: calc(190rpx + env(safe-area-inset-bottom));
}

.form-hero {
  padding: 30rpx 32rpx 34rpx;
  background: linear-gradient(155deg, #111827 0%, #1f2937 100%);
  color: #ffffff;
}

.form-title {
  margin-top: 8rpx;
  font-size: 40rpx;
  font-weight: 700;
}

.form-sub {
  margin-top: 6rpx;
  color: #94a3b8;
  font-size: 22rpx;
}

.shipment-hint {
  display: inline-flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 18rpx;
  padding: 8rpx 16rpx;
  border: 1rpx solid rgba(253, 186, 116, 0.35);
  border-radius: 8rpx;
  background: rgba(249, 115, 22, 0.12);
  color: #fdba74;
  font-size: 22rpx;
}

.shipment-tag {
  padding: 2rpx 10rpx;
  border-radius: 4rpx;
  background: #f97316;
  color: #ffffff;
  font-size: 18rpx;
}

.form-summary {
  display: flex;
  align-items: center;
  margin-top: 26rpx;
  padding-top: 22rpx;
  border-top: 1rpx solid rgba(148, 163, 184, 0.16);
}

.sum-item {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.sum-num {
  color: #fdba74;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 30rpx;
  font-weight: 700;
}

.sum-label {
  color: #94a3b8;
  font-size: 19rpx;
}

.sum-line {
  width: 1rpx;
  height: 40rpx;
  margin: 0 16rpx;
  background: rgba(148, 163, 184, 0.16);
}

.form-card {
  margin: 22rpx 24rpx 0;
}

.type-chips {
  flex-wrap: wrap;
  overflow: visible;
}

.items-card {
  padding-bottom: 6rpx;
}

.add-item {
  padding: 6rpx 14rpx;
  border: 1rpx solid #fed7aa;
  border-radius: 8rpx;
  background: #fff7ed;
  color: #c2410c;
  font-size: 22rpx;
  font-weight: 600;
}

.item-block {
  margin-top: 16rpx;
  padding: 20rpx;
  border: 1rpx solid #eef2f7;
  border-radius: 14rpx;
  background: #fcfdff;
}

.item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18rpx;
}

.item-seq {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40rpx;
  height: 40rpx;
  border-radius: 8rpx;
  background: #111827;
  color: #fdba74;
  font-size: 20rpx;
  font-weight: 700;
}

.item-title {
  color: #334155;
  font-size: 24rpx;
  font-weight: 600;
}

.item-remove {
  color: #b91c1c;
  font-size: 22rpx;
}

.unit-text {
  flex: none;
  color: #94a3b8;
  font-size: 21rpx;
}

.textarea-box {
  min-height: 140rpx;
}

.remark-count {
  margin-top: 8rpx;
  color: #b6c2d1;
  font-size: 19rpx;
  text-align: right;
}

.tip-card {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
  margin: 20rpx 24rpx 0;
  padding: 22rpx 24rpx;
  border: 1rpx dashed #dbe3ec;
  border-radius: 14rpx;
  background: rgba(255, 255, 255, 0.6);
}

.tip-title {
  color: #475569;
  font-size: 23rpx;
  font-weight: 700;
}

.tip-line {
  color: #94a3b8;
  font-size: 20rpx;
  line-height: 1.6;
}
</style>
