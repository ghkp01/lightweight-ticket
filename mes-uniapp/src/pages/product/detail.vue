<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { masterDataApi } from '../../api'
import { ensureAuth } from '../../utils/auth'
import { fmtMoney, fmtQty, productCategoryName } from '../../utils/format'
import EmptyState from '../../components/EmptyState.vue'
import MesWatermark from '../../components/Watermark.vue'

const product = ref(null)
const inventory = ref([])
const bom = ref([])
const loading = ref(true)
const error = ref('')
const code = ref('')

const totalStock = computed(() => {
  const rows = inventory.value
  if (rows.length) {
    return rows.reduce((sum, row) => sum + Number(row.qty || 0), 0)
  }
  return Number(product.value?.stock || 0)
})

const lowStock = computed(
  () => product.value && Number(totalStock.value) < Number(product.value.safetyStock || 0)
)

const load = async () => {
  if (!code.value) return
  loading.value = true
  error.value = ''
  try {
    const records = (await masterDataApi.products(code.value)) || []
    const hit =
      records.find((row) => String(row.code || '').toUpperCase() === code.value.toUpperCase()) ||
      records[0]
    if (!hit) {
      error.value = `未找到编码为 ${code.value} 的产品`
      loading.value = false
      return
    }
    product.value = hit
    uni.setNavigationBarTitle({ title: hit.code || '产品档案' })
    const [inventoryRows, bomRows] = await Promise.allSettled([
      masterDataApi.inventory({ keyword: hit.code }),
      masterDataApi.bom(hit.id)
    ])
    inventory.value = inventoryRows.status === 'fulfilled' ? inventoryRows.value || [] : []
    bom.value = bomRows.status === 'fulfilled' ? bomRows.value || [] : []
  } catch (e) {
    error.value = '产品信息加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

const goInbound = () => {
  uni.navigateTo({
    url: `/pages/inbound/create?productId=${product.value.id}&productCode=${encodeURIComponent(product.value.code)}`
  })
}

const goOutbound = () => {
  uni.navigateTo({
    url: `/pages/outbound/create?productId=${product.value.id}&productCode=${encodeURIComponent(product.value.code)}`
  })
}

onLoad((options) => {
  if (!ensureAuth()) return
  code.value = options?.code ? decodeURIComponent(options.code) : ''
  if (!code.value) {
    error.value = '缺少产品编码参数'
    loading.value = false
    return
  }
  load()
})
</script>

<template>
  <view class="mes-page" :class="{ 'page-with-bar': product }">
    <MesWatermark />
    <view v-if="loading" class="loading-block">正在读取产品档案…</view>

    <view v-else-if="!product" class="error-state">
      <EmptyState mark="料" text="产品未找到" :hint="error" action-text="重新加载" @action="load" />
    </view>

    <template v-else>
      <view class="hero product-hero">
        <view class="hero-inner">
          <view class="row between">
            <text class="hero-code mono">{{ product.code }}</text>
            <text class="tag product-tag">{{ productCategoryName(product.category) }}</text>
          </view>
          <view class="product-name">{{ product.name }}</view>
          <view class="product-spec">{{ product.spec || '无规格' }} · 单位 {{ product.unit || '-' }}</view>

          <view class="stock-row">
            <view class="stock-main">
              <text class="stock-value num" :class="{ 'stock-low': lowStock }">{{ fmtQty(totalStock) }}</text>
              <text class="stock-unit">{{ product.unit || '件' }}</text>
            </view>
            <view class="stock-meta">
              <text class="stock-label">合计库存</text>
              <text class="stock-safety">安全库存 {{ fmtQty(product.safetyStock) }}</text>
            </view>
            <view class="flex1"></view>
            <view v-if="lowStock" class="warning-pill">低于安全库存</view>
            <view v-else class="ok-pill">库存健康</view>
          </view>
        </view>
      </view>

      <view class="content">
        <view class="panel">
          <view class="section-title">产品信息</view>
          <view class="info-grid">
            <view class="info-item">
              <text class="info-label">产品编码</text>
              <text class="info-value mono">{{ product.code }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">产品分类</text>
              <text class="info-value">{{ productCategoryName(product.category) }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">销售单价</text>
              <text class="info-value">¥ {{ fmtMoney(product.price) }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">成本价</text>
              <text class="info-value">¥ {{ fmtMoney(product.cost) }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">规格</text>
              <text class="info-value">{{ product.spec || '-' }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">损耗率</text>
              <text class="info-value">{{ product.lossRate ?? 0 }}%</text>
            </view>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head">
            <view class="section-title tight">分仓库存</view>
            <text class="muted tiny">{{ inventory.length }} 个仓库</text>
          </view>

          <view v-for="row in inventory" :key="row.id" class="stock-line">
            <view class="warehouse-mark">{{ (row.warehouseName || '仓').slice(0, 1) }}</view>
            <view class="flex1">
              <text class="warehouse-name">{{ row.warehouseName }}</text>
              <text class="warehouse-sub">可用 {{ fmtQty(row.available) }} · 锁定 {{ fmtQty(row.lockedQty) }}</text>
            </view>
            <view class="warehouse-qty">
              <text class="num" :class="{ 'text-red': row.warning }">{{ fmtQty(row.qty) }}</text>
              <text class="warehouse-unit">{{ row.unit || product.unit || '' }}</text>
            </view>
            <text v-if="row.warning" class="warning-text">预警</text>
          </view>

          <view v-if="!inventory.length" class="empty stock-empty">
            <text>暂无库存记录</text>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head">
            <view class="section-title tight">BOM 组成</view>
            <text class="muted tiny">{{ bom.length }} 种物料</text>
          </view>
          <view v-for="item in bom" :key="item.id" class="bom-line">
            <view class="bom-mark">{{ (item.materialName || '料').slice(0, 1) }}</view>
            <view class="flex1">
              <text class="bom-name">{{ item.materialName }}</text>
              <text class="bom-code">{{ item.materialCode }} · {{ item.spec || '无规格' }}</text>
            </view>
            <text class="bom-qty">{{ fmtQty(item.qty) }} {{ item.unit || '' }}/件</text>
          </view>
          <view v-if="!bom.length" class="empty stock-empty">
            <text>该产品未维护 BOM</text>
          </view>
        </view>
      </view>

      <view class="action-bar">
        <view class="btn btn-ghost" @tap="goOutbound">发起出库</view>
        <view class="btn btn-primary" @tap="goInbound">发起入库</view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.product-hero {
  padding-bottom: 34rpx;
}

.hero-code {
  color: #fdba74;
  font-size: 30rpx;
  font-weight: 700;
}

.product-tag {
  background: rgba(148, 163, 184, 0.16);
  color: #cbd5e1;
}

.product-name {
  margin-top: 18rpx;
  color: #ffffff;
  font-size: 40rpx;
  font-weight: 700;
}

.product-spec {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 22rpx;
}

.stock-row {
  display: flex;
  align-items: flex-end;
  gap: 14rpx;
  margin-top: 28rpx;
  padding-top: 22rpx;
  border-top: 1rpx solid rgba(148, 163, 184, 0.16);
}

.stock-main {
  display: flex;
  align-items: baseline;
  gap: 6rpx;
}

.stock-value {
  color: #ffffff;
  font-size: 56rpx;
  font-weight: 700;
  line-height: 1;
}

.stock-value.stock-low {
  color: #fca5a5;
}

.stock-unit {
  color: #94a3b8;
  font-size: 21rpx;
}

.stock-meta {
  display: flex;
  flex-direction: column;
  padding-bottom: 4rpx;
}

.stock-label {
  color: #cbd5e1;
  font-size: 19rpx;
}

.stock-safety {
  color: #64748b;
  font-size: 18rpx;
}

.warning-pill,
.ok-pill {
  padding: 5rpx 14rpx;
  border-radius: 999rpx;
  font-size: 19rpx;
}

.warning-pill {
  background: rgba(239, 68, 68, 0.18);
  color: #fca5a5;
}

.ok-pill {
  background: rgba(34, 197, 94, 0.16);
  color: #86efac;
}

.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 22rpx 18rpx;
}

.info-item {
  display: flex;
  flex-direction: column;
}

.info-label {
  color: #94a3b8;
  font-size: 20rpx;
}

.info-value {
  margin-top: 3rpx;
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 500;
}

.stock-line {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 16rpx 0;
  border-top: 1rpx solid #f1f5f9;
}

.stock-line:first-of-type {
  border-top: none;
}

.warehouse-mark {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56rpx;
  height: 56rpx;
  border-radius: 12rpx;
  background: #f1f5f9;
  color: #64748b;
  font-size: 22rpx;
  font-weight: 700;
}

.warehouse-name {
  display: block;
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 500;
}

.warehouse-sub {
  display: block;
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 20rpx;
}

.warehouse-qty {
  display: flex;
  align-items: baseline;
  gap: 4rpx;
  color: #1f2937;
  font-size: 27rpx;
  font-weight: 700;
}

.warehouse-unit {
  color: #94a3b8;
  font-size: 18rpx;
  font-weight: 400;
}

.warning-text {
  flex: none;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 18rpx;
}

.bom-line {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 14rpx 0;
  border-top: 1rpx solid #f1f5f9;
}

.bom-line:first-of-type {
  border-top: none;
}

.bom-mark {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56rpx;
  height: 56rpx;
  border-radius: 12rpx;
  background: #f8fafc;
  color: #94a3b8;
  font-size: 22rpx;
  font-weight: 700;
}

.bom-name {
  display: block;
  color: #1f2937;
  font-size: 24rpx;
  font-weight: 500;
}

.bom-code {
  display: block;
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 19rpx;
}

.bom-qty {
  flex: none;
  color: #c2410c;
  font-size: 23rpx;
  font-weight: 600;
}

.stock-empty {
  padding: 30rpx 0 16rpx;
  font-size: 22rpx;
}

.error-state {
  padding-top: 80rpx;
}
</style>
