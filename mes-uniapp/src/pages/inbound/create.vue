<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { inboundApi, masterDataApi } from '../../api'
import { ensureAuth } from '../../utils/auth'
import { isQueued } from '../../utils/offlineQueue'
import { fmtQty, INBOUND_TYPES, warehouseTypeName } from '../../utils/format'
import ProductPicker from '../../components/ProductPicker.vue'
import MesWatermark from '../../components/Watermark.vue'

const warehouses = ref([])
const loadingBase = ref(false)
const saving = ref(false)
const savingSubmit = ref(false)

const form = reactive({
  type: 'PURCHASE',
  warehouseId: null,
  supplier: '',
  needInspection: true,
  remark: ''
})

let itemSeed = 1
const items = ref([{ key: itemSeed, productId: null, productName: '', unit: '', qty: '1', batchNo: '' }])

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

const typeLabel = computed(() => {
  const found = INBOUND_TYPES.find((item) => item.value === form.type)
  return found ? found.label : form.type
})

const loadBase = async () => {
  loadingBase.value = true
  try {
    warehouses.value = (await masterDataApi.warehouses()) || []
    if (!form.warehouseId && warehouses.value.length) {
      const raw = warehouses.value.find((item) => item.type === 'RAW')
      form.warehouseId = (raw || warehouses.value[0]).id
    }
  } catch (e) {
    // 统一提示
  } finally {
    loadingBase.value = false
  }
}

const onWarehouseChange = (event) => {
  const index = Number(event.detail.value)
  const target = warehouses.value[index]
  if (target) form.warehouseId = target.id
}

const onNeedInspectionChange = (event) => {
  form.needInspection = Boolean(event.detail.value)
}

const addItem = () => {
  itemSeed += 1
  items.value.push({ key: itemSeed, productId: null, productName: '', unit: '', qty: '1', batchNo: '' })
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
    uni.showToast({ title: '请选择入库仓库', icon: 'none' })
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
  supplier: form.supplier.trim(),
  needInspection: form.needInspection,
  remark: form.remark.trim(),
  items: items.value.map((item) => ({
    productId: item.productId,
    qty: Number(item.qty),
    batchNo: (item.batchNo || '').trim() || null
  }))
})

const save = async (thenSubmit) => {
  if (!validate()) return
  if (saving.value || savingSubmit.value) return
  if (thenSubmit) savingSubmit.value = true
  else saving.value = true
  try {
    const id = await inboundApi.create(buildPayload())
    if (isQueued(id)) {
      uni.showModal({
        title: '已离线保存',
        content: '当前网络不可用，入库申请已存到手机本地，联网后自动上传，届时请在列表里提交审核。',
        showCancel: false,
        confirmColor: '#f97316',
        success: () => uni.navigateBack({ delta: 1, fail: () => uni.redirectTo({ url: '/pages/inbound/list' }) })
      })
      return
    }
    if (thenSubmit && id) {
      await inboundApi.submit(id)
    }
    uni.showToast({
      title: thenSubmit ? '已提交入库申请' : '草稿已保存',
      icon: 'success'
    })
    setTimeout(() => {
      uni.navigateBack({
        delta: 1,
        fail: () => uni.redirectTo({ url: '/pages/inbound/list' })
      })
    }, 650)
  } catch (e) {
    // 统一提示
  } finally {
    saving.value = false
    savingSubmit.value = false
  }
}

const prefillProduct = async (options) => {
  const productId = options?.productId
  const productCode = options?.productCode ? decodeURIComponent(options.productCode) : ''
  if (productId) {
    items.value[0].productId = Number(productId)
    items.value[0].qty = options.qty || '1'
    if (productCode) items.value[0].batchNo = ''
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
  loadBase()
  prefillProduct(options)
})
</script>

<template>
  <view class="mes-page page-with-bar create-page">
    <MesWatermark />
    <view class="form-hero">
      <view class="hero-eyebrow">NEW INBOUND</view>
      <view class="form-title">新建入库申请</view>
      <view class="form-sub">填写到货信息，提交后按检验策略进入待检或直接入库</view>
      <view class="form-summary">
        <view class="sum-item"><text class="sum-num">{{ items.length }}</text><text class="sum-label">物料行</text></view>
        <view class="sum-line"></view>
        <view class="sum-item"><text class="sum-num">{{ fmtQty(totalQty) }}</text><text class="sum-label">合计数量</text></view>
        <view class="sum-line"></view>
        <view class="sum-item"><text class="sum-num">{{ form.needInspection ? '待检' : '免检' }}</text><text class="sum-label">入库策略</text></view>
      </view>
    </view>

    <view class="form-card">
      <view class="section-title">基本信息</view>

      <view class="field">
        <view class="field-label"><text class="req">*</text><text>入库类型</text></view>
        <view class="chips type-chips">
          <view
            v-for="item in INBOUND_TYPES"
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
        <view class="field-label"><text class="req">*</text><text>入库仓库</text></view>
        <picker
          :range="warehouseLabels"
          :value="warehouseIndex"
          :disabled="loadingBase || !warehouses.length"
          @change="onWarehouseChange"
        >
          <view class="picker-line">
            <text v-if="form.warehouseId" class="ellipsis">
              {{ warehouseLabels[warehouseIndex] }}
            </text>
            <text v-else class="placeholder">{{ loadingBase ? '加载仓库中…' : '请选择仓库' }}</text>
            <view class="chevron"></view>
          </view>
        </picker>
      </view>

      <view class="field">
        <view class="field-label"><text>供应商 / 来源</text></view>
        <view class="input-box">
          <input v-model="form.supplier" class="input" placeholder="如：江苏铝业有限公司" />
        </view>
      </view>

      <view class="field">
        <view class="inspect-row">
          <view class="flex1">
            <view class="inspect-title">需要来料检验</view>
            <view class="inspect-desc">
              {{ form.needInspection ? '提交后进入待检验，质检合格后整单入库' : '提交后立即完成入库并写入库存流水' }}
            </view>
          </view>
          <switch
            :checked="form.needInspection"
            color="#f97316"
            style="transform: scale(0.85)"
            @change="onNeedInspectionChange"
          />
        </view>
      </view>
    </view>

    <view class="form-card items-card">
      <view class="panel-head">
        <view class="section-title tight">入库明细</view>
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
            placeholder="选择入库产品"
            @change="(product) => onProductChange(index, product)"
          />
        </view>

        <view class="item-grid">
          <view class="field">
            <view class="field-label"><text class="req">*</text><text>数量</text></view>
            <view class="input-box">
              <input v-model="item.qty" class="input" type="digit" placeholder="0" />
              <text class="unit-text">{{ item.unit || '单位' }}</text>
            </view>
          </view>
          <view class="field">
            <view class="field-label"><text>批次号</text></view>
            <view class="input-box">
              <input v-model="item.batchNo" class="input" placeholder="可选" />
            </view>
          </view>
        </view>
      </view>
    </view>

    <view class="form-card">
      <view class="section-title">备注</view>
      <view class="input-box textarea-box">
        <textarea
          v-model="form.remark"
          class="textarea"
          maxlength="200"
          placeholder="到货说明、质检要求或交接信息"
        />
      </view>
      <view class="remark-count">{{ form.remark.length }}/200</view>
    </view>

    <view class="tip-card">
      <text class="tip-title">入库规则</text>
      <text class="tip-line">· 单号由系统按 IN 前缀自动生成，无需手工填写。</text>
      <text class="tip-line">· 需检验单据提交后状态为「待检验」，由质检完成来料检验。</text>
      <text class="tip-line">· 免检单据提交即完成入库，库存变动写入统一流水。</text>
    </view>

    <view class="action-bar">
      <view class="btn btn-ghost" :class="{ disabled: saving }" @tap="save(false)">
        {{ saving ? '保存中…' : '保存草稿' }}
      </view>
      <view class="btn btn-primary" :class="{ disabled: savingSubmit }" @tap="save(true)">
        {{ savingSubmit ? '提交中…' : '保存并提交' }}
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

.inspect-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 18rpx 20rpx;
  border: 1rpx solid #eef2f7;
  border-radius: 12rpx;
  background: #f8fafc;
}

.inspect-title {
  color: #1f2937;
  font-size: 26rpx;
  font-weight: 600;
}

.inspect-desc {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 21rpx;
  line-height: 1.5;
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

.item-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16rpx;
}

.item-grid .field {
  margin-bottom: 0;
}

.unit-text {
  flex: none;
  color: #94a3b8;
  font-size: 21rpx;
}

.textarea-box {
  position: relative;
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
