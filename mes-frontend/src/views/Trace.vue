<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, fmtQty } from '../api'

const route = useRoute()
const code = ref('')
const loading = ref(false)
const searched = ref(false)
const result = ref<any>(null)
const inputRef = ref<any>(null)

const kindLabel: Record<string, string> = {
  BATCH: '批次',
  WORK_ORDER: '生产工单',
  SHIPMENT: '发货单'
}

const sourceLabel: Record<string, string> = {
  INBOUND: '来料入库',
  PRODUCTION: '完工入库',
  RETURN: '退货入库',
  TRANSFER: '调拨',
  ADJUST: '调整/期初'
}

/** 扫码枪可能扫的是完整条码，这里把 MES|BATCH|xxx 拆出批次号 */
const normalize = (raw: string) => {
  const text = String(raw || '').trim()
  const parts = text.split('|').map((part) => part.trim())
  if (parts.length >= 3 && parts[0].toUpperCase() === 'MES') {
    return parts[2]
  }
  return text
}

const search = async () => {
  const value = normalize(code.value)
  if (!value) {
    ElMessage.warning('请输入批次号、工单号或发货单号')
    return
  }
  code.value = value
  loading.value = true
  try {
    result.value = await api.get('/trace/query', { code: value })
    searched.value = true
  } finally {
    loading.value = false
  }
}

const openCode = async (value: any) => {
  const target = String(value || '').trim()
  if (!target) return
  code.value = target
  await search()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

const focusInput = () => {
  nextTick(() => {
    const input = inputRef.value
    if (!input) return
    if (typeof input.focus === 'function') input.focus()
    input.input?.focus?.()
  })
}

const summary = computed(() => {
  const data = result.value
  if (!data) return null
  if (data.kind === 'WORK_ORDER' && data.workOrder) {
    return {
      title: data.workOrder.workNo,
      sub: `${data.workOrder.productName}（${data.workOrder.productCode}） · 计划 ${fmtQty(data.workOrder.planQty)} ${data.workOrder.unit || ''}`,
      extra: `完工批次 ${data.finishedBatch || data.workOrder.workNo} · 创建 ${data.workOrder.createdAt || '-'}`,
      status: data.workOrder.status
    }
  }
  if (data.kind === 'SHIPMENT' && data.shipment) {
    return {
      title: data.shipment.shipmentNo,
      sub: `${data.shipment.customerName} · 订单 ${data.shipment.orderNo || '-'}`,
      extra: `发货 ${data.shipment.shippedAt || '-'} · 签收 ${data.shipment.signedAt || '未签收'}`,
      status: data.shipment.status
    }
  }
  if (data.kind === 'BATCH') {
    const first = (data.stocks || [])[0]
    return {
      title: data.batchNo,
      sub: first ? `${first.productName}（${first.productCode}） · ${first.unit || ''}` : '库存里没有这个批次的余量',
      extra: first ? `最近入库 ${first.createdAt} · 来源 ${sourceLabel[first.source] || first.source || '-'}` : '',
      status: ''
    }
  }
  return { title: normalize(code.value), sub: '', extra: '', status: '' }
})

const hasResult = computed(() => {
  const data = result.value
  if (!data) return false
  return Boolean(
    (data.stocks && data.stocks.length) ||
    (data.materials && data.materials.length) ||
    (data.usedIn && data.usedIn.length) ||
    (data.shipments && data.shipments.length) ||
    (data.derivedShipments && data.derivedShipments.length) ||
    (data.items && data.items.length) ||
    data.workOrder ||
    data.shipment
  )
})

onMounted(() => {
  const preset = String(route.query.code || '')
  if (preset) {
    code.value = preset
    search()
  }
  focusInput()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">批次追溯</h2>
        <p class="page-sub">从原料批次查到成品批次、再查到发货客户；扫码枪可直接扫 MES|BATCH|批次号</p>
      </div>
    </div>

    <div class="panel search-panel">
      <div class="search-row">
        <el-input
          ref="inputRef"
          v-model="code"
          size="large"
          placeholder="批次号 / 工单号（WO 开头）/ 发货单号（SH 开头），回车即查"
          clearable
          @keyup.enter="search"
        >
          <template #prefix>
            <el-icon><Aim /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" size="large" :loading="loading" @click="search">追溯</el-button>
      </div>
      <div class="search-hint">
        批次来自入库单填写的批次号，成品批次固定为工单号；领料与发货都按批次先进先出，所以这条链是真实发生的，不是事后补录的。
      </div>
    </div>

    <template v-if="searched && summary">
      <div class="panel summary-panel">
        <div class="summary-main">
          <div class="summary-kind">{{ kindLabel[result?.kind] || '追溯结果' }}</div>
          <div class="summary-title mono">{{ summary.title }}</div>
          <div class="summary-sub">{{ summary.sub }}</div>
          <div class="summary-extra">{{ summary.extra }}</div>
        </div>
        <div class="summary-side">
          <span class="count-chip">库存 {{ (result?.stocks || []).length }} 条</span>
          <span class="count-chip">上游 {{ (result?.materials || []).length }} 批</span>
          <span class="count-chip">下游 {{ (result?.usedIn || []).length }} 单</span>
          <span class="count-chip">发货 {{ ((result?.shipments || []).length + (result?.derivedShipments || []).length) }} 次</span>
        </div>
      </div>

      <div v-if="!hasResult" class="panel empty-panel">
        <div class="empty-title">没有查到记录</div>
        <div class="empty-sub">{{ result?.message || '确认批次号是否正确，或该批次已经全部发出' }}</div>
      </div>

      <div v-if="(result?.stocks || []).length" class="panel">
        <div class="block-head">
          <div class="block-title">当前库存</div>
          <span class="block-tip">按仓库与入库时间排列</span>
        </div>
        <el-table :data="result.stocks" size="small" stripe>
          <el-table-column prop="warehouseName" label="仓库" width="120" />
          <el-table-column label="数量" width="110" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column prop="productCode" label="物料编码" width="110">
            <template #default="{ row }"><span class="mono">{{ row.productCode }}</span></template>
          </el-table-column>
          <el-table-column prop="productName" label="物料名称" min-width="150" show-overflow-tooltip />
          <el-table-column label="来源" width="110">
            <template #default="{ row }">{{ sourceLabel[row.source] || row.source || '-' }}</template>
          </el-table-column>
          <el-table-column label="入库时间" width="150">
            <template #default="{ row }">{{ row.createdAt }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="(result?.materials || []).length" class="panel">
        <div class="block-head">
          <div class="block-title">向上：这批用了哪些原料批次</div>
          <span class="block-tip">点批次号继续往上查</span>
        </div>
        <el-table :data="result.materials" size="small" stripe>
          <el-table-column label="原料批次" width="180">
            <template #default="{ row }">
              <el-button link type="primary" class="mono" @click="openCode(row.batchNo)">{{ row.batchNo }}</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="productCode" label="物料编码" width="110">
            <template #default="{ row }"><span class="mono">{{ row.productCode }}</span></template>
          </el-table-column>
          <el-table-column prop="productName" label="物料名称" min-width="160" show-overflow-tooltip />
          <el-table-column label="领用数量" width="110" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column label="领用时间" width="150">
            <template #default="{ row }">{{ row.issuedAt }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="(result?.usedIn || []).length" class="panel">
        <div class="block-head">
          <div class="block-title">向下：这批料被哪些工单用掉</div>
          <span class="block-tip">点工单号查看该工单的用料与成品</span>
        </div>
        <el-table :data="result.usedIn" size="small" stripe>
          <el-table-column label="工单" width="180">
            <template #default="{ row }">
              <el-button link type="primary" class="mono" @click="openCode(row.workNo)">{{ row.workNo }}</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="productName" label="产出产品" min-width="160" show-overflow-tooltip />
          <el-table-column prop="status" label="工单状态" width="110" />
          <el-table-column label="消耗数量" width="110" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column label="领料时间" width="150">
            <template #default="{ row }">{{ row.usedAt }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="(result?.shipments || []).length" class="panel">
        <div class="block-head">
          <div class="block-title">向下：这个批次发给了谁</div>
          <span class="block-tip">发货按批次先进先出，实际发走的批次就是这里</span>
        </div>
        <el-table :data="result.shipments" size="small" stripe>
          <el-table-column label="发货单" width="170">
            <template #default="{ row }">
              <el-button link type="primary" class="mono" @click="openCode(row.shipmentNo)">{{ row.shipmentNo }}</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="customerName" label="客户" min-width="150" show-overflow-tooltip />
          <el-table-column prop="orderNo" label="销售订单" width="150">
            <template #default="{ row }"><span class="mono">{{ row.orderNo || '-' }}</span></template>
          </el-table-column>
          <el-table-column label="数量" width="100" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column label="发货时间" width="150">
            <template #default="{ row }">{{ row.shippedAt }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="(result?.derivedShipments || []).length" class="panel">
        <div class="block-head">
          <div class="block-title">向下：做成成品后发给了谁</div>
          <span class="block-tip">原料批次 → 工单 → 成品批次 → 客户</span>
        </div>
        <el-table :data="result.derivedShipments" size="small" stripe>
          <el-table-column label="发货单" width="170">
            <template #default="{ row }">
              <el-button link type="primary" class="mono" @click="openCode(row.shipmentNo)">{{ row.shipmentNo }}</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="customerName" label="客户" min-width="150" show-overflow-tooltip />
          <el-table-column label="成品批次 / 工单" width="190">
            <template #default="{ row }">
              <el-button link type="primary" class="mono" @click="openCode(row.batchNo)">{{ row.batchNo }}</el-button>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="100" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column label="发货时间" width="150">
            <template #default="{ row }">{{ row.shippedAt }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="(result?.items || []).length" class="panel">
        <div class="block-head">
          <div class="block-title">发货明细与批次</div>
          <span class="block-tip">同一明细可能跨多个成品批次</span>
        </div>
        <el-table :data="result.items" size="small" stripe>
          <el-table-column prop="productCode" label="物料编码" width="110">
            <template #default="{ row }"><span class="mono">{{ row.productCode }}</span></template>
          </el-table-column>
          <el-table-column prop="productName" label="产品" min-width="160" show-overflow-tooltip />
          <el-table-column label="发货数量" width="110" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column label="实际批次" min-width="220">
            <template #default="{ row }">
              <el-button
                v-for="batch in row.batches || []"
                :key="batch.batchNo"
                link
                type="primary"
                class="mono batch-link"
                @click="openCode(batch.batchNo)"
              >
                {{ batch.batchNo }}（{{ fmtQty(batch.qty) }}）
              </el-button>
              <span v-if="!(row.batches || []).length" class="muted">未记录批次</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="(result?.batches || []).length" class="panel">
        <div class="block-head">
          <div class="block-title">这张发货单涉及的成品批次及用料</div>
        </div>
        <div v-for="batch in result.batches" :key="batch.batchNo" class="batch-block">
          <div class="batch-head">
            <el-button link type="primary" class="mono" @click="openCode(batch.batchNo)">{{ batch.batchNo }}</el-button>
            <span class="muted">用料 {{ (batch.materials || []).length }} 批</span>
          </div>
          <div class="material-line">
            <span v-for="material in batch.materials || []" :key="material.batchNo" class="material-chip">
              {{ material.productName }} · {{ material.batchNo }}（{{ fmtQty(material.qty) }}）
            </span>
            <span v-if="!(batch.materials || []).length" class="muted">这批没有领料记录</span>
          </div>
        </div>
      </div>
    </template>

    <div v-else-if="searched && !loading" class="panel empty-panel">
      <div class="empty-title">没有查到记录</div>
      <div class="empty-sub">换个批次号、工单号或发货单号再试一次</div>
    </div>
  </div>
</template>

<style scoped>
.search-panel {
  padding: 18px 20px;
}

.search-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.search-row .el-input {
  flex: 1;
}

.search-hint {
  margin-top: 10px;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.6;
}

.summary-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 22px;
  background: linear-gradient(135deg, #0f172a 0%, #1f2937 100%);
  color: #ffffff;
}

.summary-kind {
  color: #fdba74;
  font-size: 12px;
  letter-spacing: 2px;
}

.summary-title {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 700;
}

.summary-sub {
  margin-top: 6px;
  color: #cbd5e1;
  font-size: 13px;
}

.summary-extra {
  margin-top: 4px;
  color: #94a3b8;
  font-size: 12px;
}

.summary-side {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
  max-width: 320px;
}

.count-chip {
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.1);
  color: #e2e8f0;
  font-size: 12px;
}

.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}

.block-title {
  color: var(--ink);
  font-size: 14px;
  font-weight: 600;
}

.block-tip {
  color: var(--ink-2);
  font-size: 12px;
}

.empty-panel {
  padding: 34px 20px;
  text-align: center;
}

.empty-title {
  color: var(--ink);
  font-size: 15px;
  font-weight: 600;
}

.empty-sub {
  margin-top: 6px;
  color: var(--ink-2);
  font-size: 12px;
}

.batch-block {
  padding: 12px 0;
  border-top: 1px dashed var(--line);
}

.batch-block:first-of-type {
  border-top: none;
}

.batch-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}

.material-line {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.material-chip {
  padding: 3px 10px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #475569;
  font-size: 12px;
}

.batch-link {
  margin-right: 10px;
}
</style>
