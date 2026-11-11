<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { inboundApi, qualityApi, workOrderApi } from '../../api'
import { ensureAuth, getUser } from '../../utils/auth'
import { isQueued } from '../../utils/offlineQueue'
import { fmtQty, fmtRate, INSPECTION_TYPES } from '../../utils/format'
import ProductPicker from '../../components/ProductPicker.vue'
import MesWatermark from '../../components/Watermark.vue'

const user = getUser() || {}

const form = reactive({
  type: 'INCOMING',
  productId: null,
  inspectQty: '',
  defectQty: '0',
  inspector: user.realName || user.username || '',
  remark: '',
  refType: 'INBOUND',
  refNo: '',
  inboundOrderId: null,
  workOrderId: null
})

const inboundOrders = ref([])
const workOrders = ref([])
const inboundLoading = ref(false)
const workLoading = ref(false)
const inboundError = ref('')
const workError = ref('')
const inboundDetail = ref(null)
const selectedInboundIndex = ref(0)
const selectedWorkIndex = ref(0)
const saving = ref(false)
const scanContext = ref(null)
const scanLoading = ref(false)

// 检验项与抽样
const inspectItems = ref([])
const samplePlan = ref('FULL')
const aql = ref(2.5)
const sampleInfo = ref(null)
const itemDefectTotal = computed(() =>
  inspectItems.value.reduce((sum, item) => sum + Number(item.defectQty || 0), 0)
)

const setItemResult = (index, result) => {
  const item = inspectItems.value[index]
  if (!item) return
  item.result = result
  if (result === 'FAIL' && Number(item.defectQty || 0) === 0) {
    item.defectQty = 1
  }
  if (result === 'PASS') {
    item.defectQty = 0
  }
}

const typeLabel = computed(() => {
  const found = INSPECTION_TYPES.find((item) => item.value === form.type)
  return found ? found.label : form.type
})

const inspectNum = computed(() => Number(form.inspectQty) || 0)
const defectNum = computed(() => Number(form.defectQty) || 0)

const passRate = computed(() => {
  if (!inspectNum.value) return 0
  return Math.max(0, ((inspectNum.value - defectNum.value) / inspectNum.value) * 100)
})

const resultText = computed(() => (defectNum.value > 0 ? '不合格' : '合格'))
const resultTone = computed(() => (defectNum.value > 0 ? 'fail' : 'pass'))

const inboundLabels = computed(() =>
  inboundOrders.value.map(
    (item) => `${item.inboundNo || item.no} · ${item.warehouseName || '仓库'} · ${item.itemCount ?? (item.items || []).length} 项`
  )
)

const workLabels = computed(() =>
  workOrders.value.map((item) => `${item.workNo} · ${item.productName}`)
)

const currentInbound = computed(() => inboundOrders.value[selectedInboundIndex.value] || null)

const loadInboundOrders = async () => {
  inboundLoading.value = true
  inboundError.value = ''
  try {
    const data = await inboundApi.list({ status: 'PENDING_QC', page: 1, size: 50 })
    inboundOrders.value = data?.records || []
    selectedInboundIndex.value = 0
    if (inboundOrders.value.length) {
      await chooseInbound({ detail: { value: 0 } })
    }
  } catch (e) {
    inboundError.value = '待检入库单加载失败，请确认入库单接口已启用'
    inboundOrders.value = []
  } finally {
    inboundLoading.value = false
  }
}

const loadFinishedWorkOrders = async () => {
  workLoading.value = true
  workError.value = ''
  try {
    const data = await workOrderApi.list({ status: 'FINISHED', page: 1, size: 50 })
    workOrders.value = data?.records || []
    selectedWorkIndex.value = 0
    if (workOrders.value.length) {
      await chooseWorkOrder({ detail: { value: 0 } })
    }
  } catch (e) {
    workError.value = '已完工工单加载失败'
    workOrders.value = []
  } finally {
    workLoading.value = false
  }
}

const chooseType = (value) => {
  if (form.type === value) return
  form.type = value
  form.inboundOrderId = null
  form.workOrderId = null
  form.refNo = ''
  form.productId = null
  form.inspectQty = ''
  form.defectQty = '0'
  scanContext.value = null
  inboundDetail.value = null
  if (value === 'INCOMING') {
    form.refType = 'INBOUND'
    if (inboundOrders.value.length) chooseInbound({ detail: { value: 0 } })
    else loadInboundOrders()
  } else if (value === 'FINAL') {
    form.refType = 'WORK_ORDER'
    if (workOrders.value.length) chooseWorkOrder({ detail: { value: 0 } })
    else loadFinishedWorkOrders()
  } else {
    form.refType = 'MANUAL'
  }
}

const selectInboundItem = (item) => {
  form.productId = item.productId
  form.inspectQty = String(item.qty ?? '')
  form.refNo = inboundDetail.value?.inboundNo || currentInbound.value?.inboundNo || form.refNo
}

const chooseInbound = async (event) => {
  const index = Number(event.detail.value)
  const order = inboundOrders.value[index]
  selectedInboundIndex.value = index
  if (!order) return
  form.inboundOrderId = order.id
  form.refType = 'INBOUND'
  form.refNo = order.inboundNo || order.no || ''
  inboundDetail.value = null
  try {
    const detail = await inboundApi.detail(order.id)
    inboundDetail.value = detail
    const items = detail?.items || []
    if (items.length === 1) selectInboundItem(items[0])
  } catch (e) {
    inboundDetail.value = null
  }
}

const chooseWorkOrder = async (event) => {
  const index = Number(event.detail.value)
  const order = workOrders.value[index]
  selectedWorkIndex.value = index
  if (!order) return
  form.workOrderId = order.id
  form.refType = 'WORK_ORDER'
  form.refNo = order.workNo || ''
  try {
    const detail = await workOrderApi.detail(order.id)
    if (detail) {
      form.productId = detail.productId
      form.inspectQty = String(detail.qualifiedQty || detail.doneQty || detail.planQty || '')
    }
  } catch (e) {
    // 使用列表数据兜底
  }
}

/**
 * 扫码带入：工序码直接检该工序，工单码在已完工时走完工检、否则走过程检
 */
const applyScan = async (workNo, opSeq) => {
  scanLoading.value = true
  try {
    const detail = await workOrderApi.detailByNo(workNo)
    const operations = (detail && detail.operations) || []
    let operation = null
    let type = 'PROCESS'

    if (opSeq) {
      operation = operations.find((op) => Number(op.seq) === Number(opSeq)) || null
      if (!operation) {
        uni.showModal({
          title: '工序不存在',
          content: `工单 ${detail.workNo} 下没有第 ${opSeq} 道工序，请确认二维码是否属于这张工单`,
          showCancel: false,
          confirmColor: '#f97316'
        })
        return
      }
    } else if (String(detail.status) === 'FINISHED') {
      type = 'FINAL'
    } else {
      operation = operations.find((op) => Number(op.doneQty) < Number(op.planQty)) || operations[0] || null
    }

    form.type = type
    form.refType = 'WORK_ORDER'
    form.refNo = detail.workNo || workNo
    form.workOrderId = detail.id
    form.productId = detail.productId
    form.inspectQty = String(
      (type === 'FINAL'
        ? detail.qualifiedQty || detail.doneQty || detail.planQty
        : (operation ? operation.doneQty || operation.planQty : detail.planQty)) || ''
    )
    form.defectQty = '0'
    scanContext.value = {
      workNo: detail.workNo,
      productName: detail.productName,
      status: detail.status,
      type,
      opSeq: operation ? operation.seq : null,
      opName: operation ? operation.name : '',
      operationId: operation ? operation.id : null
    }
    uni.showToast({ title: '已扫码带入', icon: 'none' })
  } catch (e) {
    uni.showModal({
      title: '扫码带入失败',
      content: `未找到工单 ${workNo}，请确认工单号或二维码是否属于当前环境`,
      showCancel: false,
      confirmColor: '#f97316'
    })
  } finally {
    scanLoading.value = false
  }
}

const clearScan = () => {
  scanContext.value = null
  form.workOrderId = null
  form.refNo = ''
  form.productId = null
  form.inspectQty = ''
  form.defectQty = '0'
  uni.navigateBack({
    delta: 1,
    fail: () => uni.redirectTo({ url: '/pages/quality/list' })
  })
}

const loadTemplates = async () => {
  if (!form.productId) {
    inspectItems.value = []
    return
  }
  try {
    const templates = await qualityApi.items({ productId: form.productId })
    inspectItems.value = (templates || []).map((item) => ({
      itemId: item.id,
      itemName: item.itemName,
      standard: item.standard,
      method: item.method,
      valueType: item.valueType,
      unit: item.unit,
      measured: '',
      defectQty: 0,
      result: 'PASS',
      remark: ''
    }))
  } catch (e) {
    inspectItems.value = []
  }
}

const refreshSamplePlan = async () => {
  const lotQty = Number(form.inspectQty || 0)
  if (!lotQty) {
    sampleInfo.value = null
    return
  }
  try {
    sampleInfo.value = await qualityApi.samplePlan({
      lotQty,
      plan: samplePlan.value,
      aql: samplePlan.value === 'SAMPLE' ? aql.value : undefined
    })
  } catch (e) {
    sampleInfo.value = null
  }
}

watch(
  () => form.productId,
  () => {
    loadTemplates()
  }
)

watch(
  () => [form.inspectQty, samplePlan.value, aql.value],
  () => {
    refreshSamplePlan()
  }
)

const validate = () => {
  if (!form.productId) {
    uni.showToast({ title: '请选择检验产品', icon: 'none' })
    return false
  }
  if (!inspectNum.value || inspectNum.value <= 0) {
    uni.showToast({ title: '请输入大于 0 的检验数量', icon: 'none' })
    return false
  }
  if (defectNum.value < 0 || defectNum.value > inspectNum.value) {
    uni.showToast({ title: '不良数量必须在 0 到检验数量之间', icon: 'none' })
    return false
  }
  if (form.type === 'INCOMING' && !form.inboundOrderId) {
    uni.showToast({ title: '请选择待检入库单', icon: 'none' })
    return false
  }
  if (form.type === 'FINAL' && !form.workOrderId) {
    uni.showToast({ title: '请选择已完工工单', icon: 'none' })
    return false
  }
  return true
}

const submit = async () => {
  if (!validate() || saving.value) return
  saving.value = true
  try {
    const itemPayload = inspectItems.value
      .filter((item) => item.itemId)
      .map((item) => ({
        itemId: item.itemId,
        itemName: item.itemName,
        standard: item.standard,
        sampleQty: (sampleInfo.value && sampleInfo.value.sampleQty) || inspectNum.value,
        defectQty: Number(item.defectQty || 0),
        measured: item.measured || null,
        result: Number(item.defectQty || 0) > 0 ? 'FAIL' : item.result,
        remark: item.remark || null
      }))
    const defectQty = itemPayload.length
      ? itemPayload.reduce((sum, item) => sum + Number(item.defectQty || 0), 0)
      : defectNum.value
    const saved = await qualityApi.create({
      type: form.type,
      refType: form.refType,
      refNo: form.refNo.trim(),
      workOrderId: form.type === 'FINAL' ? form.workOrderId : null,
      inboundOrderId: form.type === 'INCOMING' ? form.inboundOrderId : null,
      productId: form.productId,
      inspectQty: inspectNum.value,
      defectQty,
      inspector: form.inspector.trim(),
      remark: form.remark.trim(),
      operationId: scanContext.value ? scanContext.value.operationId : null,
      operationSeq: scanContext.value ? scanContext.value.opSeq : null,
      items: itemPayload,
      samplePlan: samplePlan.value,
      aql: samplePlan.value === 'SAMPLE' ? aql.value : null
    })
    if (isQueued(saved)) {
      uni.showModal({
        title: '检验单已离线保存',
        content: '当前网络不可用，检验记录已存到手机本地，联网后自动上传，届时请到检验列表确认结果。',
        showCancel: false,
        confirmColor: '#f97316',
        success: () => uni.navigateBack({ delta: 1, fail: () => uni.redirectTo({ url: '/pages/quality/list' }) })
      })
      return
    }
    uni.showToast({
      title: form.type === 'FINAL' && defectNum.value === 0 ? '检验合格，成品已入库' : '检验单已提交',
      icon: 'success'
    })
    setTimeout(() => {
      uni.navigateBack({
        delta: 1,
        fail: () => uni.redirectTo({ url: '/pages/quality/list' })
      })
    }, 700)
  } catch (e) {
    // 统一提示
  } finally {
    saving.value = false
  }
}

onLoad((options = {}) => {
  if (!ensureAuth()) return
  if (options.workNo) {
    applyScan(options.workNo, options.opSeq ? Number(options.opSeq) : null)
    return
  }
  loadInboundOrders()
})
</script>

<template>
  <view class="mes-page page-with-bar create-page">
    <MesWatermark />
    <view class="form-hero">
      <view class="hero-eyebrow">NEW INSPECTION</view>
      <view class="form-title">新建检验单</view>
      <view class="form-sub">录入检验数量与不良数，系统自动判定合格结论并联动入库</view>

      <view class="result-preview" :class="`preview-${resultTone}`">
        <view class="preview-left">
          <text class="preview-label">判定预览</text>
          <text class="preview-result">{{ resultText }}</text>
        </view>
        <view class="preview-rate">
          <text class="preview-rate-num">{{ fmtRate(passRate) }}</text>
          <text class="preview-rate-label">合格率</text>
        </view>
      </view>
    </view>

    <view v-if="scanLoading" class="scan-banner">
      <text class="scan-banner-title">正在按二维码读取工单…</text>
    </view>
    <view v-else-if="scanContext" class="scan-banner">
      <view class="scan-banner-main">
        <text class="scan-banner-title">已扫码：{{ scanContext.workNo }}</text>
        <text class="scan-banner-sub">
          {{ scanContext.productName }}
          <text v-if="scanContext.opSeq"> · 第 {{ scanContext.opSeq }} 道 {{ scanContext.opName }}</text>
          · {{ scanContext.type === 'FINAL' ? '完工检（合格自动入成品仓）' : '过程检' }}
        </text>
      </view>
      <text class="scan-banner-clear" @tap="clearScan">重新扫码</text>
    </view>

    <view class="form-card">
      <view class="section-title">检验类型</view>
      <view class="type-grid">
        <view
          v-for="item in INSPECTION_TYPES"
          :key="item.value"
          class="type-card"
          :class="{ active: form.type === item.value }"
          @tap="chooseType(item.value)"
        >
          <text class="type-name">{{ item.label }}</text>
          <text class="type-desc">
            {{ item.value === 'INCOMING' ? '来料 / 待检入库' : item.value === 'PROCESS' ? '制程抽检' : '完工入库前检验' }}
          </text>
        </view>
      </view>
    </view>

    <view v-if="form.type === 'INCOMING'" class="form-card">
      <view class="section-title">关联待检入库单</view>
      <view v-if="inboundLoading" class="loading-block inline-loading">正在加载待检单据…</view>
      <view v-else-if="inboundError" class="pick-error">
        <text>{{ inboundError }}</text>
        <text class="retry" @tap="loadInboundOrders">重试</text>
      </view>
      <template v-else-if="inboundOrders.length">
        <picker :range="inboundLabels" :value="selectedInboundIndex" @change="chooseInbound">
          <view class="picker-line">
            <text class="ellipsis">{{ inboundLabels[selectedInboundIndex] }}</text>
            <view class="chevron"></view>
          </view>
        </picker>
        <view v-if="inboundDetail" class="inbound-items">
          <view class="inbound-hint">
            <text>选择要检验的物料行，合格后整单入库</text>
          </view>
          <view
            v-for="item in inboundDetail.items || []"
            :key="item.id || item.productId"
            class="inbound-item"
            :class="{ active: String(form.productId) === String(item.productId) }"
            @tap="selectInboundItem(item)"
          >
            <view class="flex1">
              <text class="inbound-item-name">{{ item.productName }}</text>
              <text class="inbound-item-sub">{{ item.productCode }} · 批次 {{ item.batchNo || '-' }}</text>
            </view>
            <view class="inbound-item-qty">
              <text class="num">{{ fmtQty(item.qty) }}</text>
              <text class="unit">{{ item.unit || '' }}</text>
            </view>
            <view class="radio" :class="{ checked: String(form.productId) === String(item.productId) }"></view>
          </view>
        </view>
      </template>
      <view v-else class="empty pick-empty">
        <text>暂无待检入库单</text>
        <text class="muted tiny">请先在入库申请中提交需要检验的单据</text>
      </view>
    </view>

    <view v-if="form.type === 'FINAL' && !scanContext" class="form-card">
      <view class="section-title">关联已完工工单</view>
      <view v-if="workLoading" class="loading-block inline-loading">正在加载完工工单…</view>
      <view v-else-if="workError" class="pick-error">
        <text>{{ workError }}</text>
        <text class="retry" @tap="loadFinishedWorkOrders">重试</text>
      </view>
      <template v-else-if="workOrders.length">
        <picker :range="workLabels" :value="selectedWorkIndex" @change="chooseWorkOrder">
          <view class="picker-line">
            <text class="ellipsis">{{ workLabels[selectedWorkIndex] }}</text>
            <view class="chevron"></view>
          </view>
        </picker>
        <view class="field-note">完工检验合格后，合格数量将自动入成品仓。</view>
      </template>
      <view v-else class="empty pick-empty">
        <text>暂无已完工工单</text>
        <text class="muted tiny">工单完成全部工序报工后才可执行完工检验</text>
      </view>
    </view>

    <view class="form-card">
      <view class="section-title">检验信息</view>

      <view class="field">
        <view class="field-label"><text class="req">*</text><text>检验产品</text></view>
        <ProductPicker v-model="form.productId" placeholder="选择检验产品" />
      </view>

      <view class="qty-grid">
        <view class="field">
          <view class="field-label"><text class="req">*</text><text>检验数量</text></view>
          <view class="input-box">
            <input v-model="form.inspectQty" class="input" type="digit" placeholder="0" />
          </view>
        </view>
        <view class="field">
          <view class="field-label"><text>不良数量</text></view>
          <view class="input-box">
            <input v-if="!inspectItems.length" v-model="form.defectQty" class="input" type="digit" placeholder="0" />
            <text v-else class="defect-total-text">{{ fmtQty(itemDefectTotal) }}</text>
          </view>
        </view>
      </view>

      <view class="field">
        <view class="field-label"><text>抽样方式</text></view>
        <view class="chips">
          <view class="chip" :class="{ active: samplePlan === 'FULL' }" @tap="samplePlan = 'FULL'">全检</view>
          <view class="chip" :class="{ active: samplePlan === 'SAMPLE' }" @tap="samplePlan = 'SAMPLE'">抽检</view>
        </view>
        <view v-if="sampleInfo" class="sample-tip">
          样本量 {{ fmtQty(sampleInfo.sampleQty) }} · 接收数 Ac {{ sampleInfo.acceptQty }} / 拒收数 Re {{ sampleInfo.rejectQty }}
          <text v-if="samplePlan === 'SAMPLE'"> · AQL {{ sampleInfo.aql }}%（简化规则）</text>
        </view>
      </view>

      <view v-if="inspectItems.length" class="field">
        <view class="field-label"><text>检验项（{{ inspectItems.length }} 项）</text></view>
        <view v-for="(item, index) in inspectItems" :key="item.itemId" class="check-item">
          <view class="check-head">
            <text class="check-name">{{ item.itemName }}</text>
            <view class="chips">
              <view class="chip" :class="{ active: item.result === 'PASS' }" @tap="setItemResult(index, 'PASS')">合格</view>
              <view class="chip" :class="{ active: item.result === 'FAIL' }" @tap="setItemResult(index, 'FAIL')">不合格</view>
            </view>
          </view>
          <text class="check-std">
            {{ item.standard || '未写标准' }}{{ item.method ? ' · ' + item.method : '' }}
          </text>
          <view class="check-inputs">
            <input v-model="item.measured" class="check-input" placeholder="实测值" />
            <input v-model="item.defectQty" class="check-input" type="digit" placeholder="不良数" />
          </view>
        </view>
        <view class="sample-tip">合计不良 {{ fmtQty(itemDefectTotal) }}，任一项不合格整单判不合格</view>
      </view>
      <view v-else-if="form.productId" class="field-note">
        该产品还没配置检验项，先按检验数量与不良数量判定。
      </view>

      <view class="field">
        <view class="field-label"><text>关联单据号</text></view>
        <view class="input-box">
          <input
            v-model="form.refNo"
            class="input"
            :disabled="form.type !== 'PROCESS'"
            :placeholder="form.type === 'PROCESS' ? '可选，如工序流转卡号' : '选择上方单据后自动带入'"
          />
        </view>
      </view>

      <view class="field">
        <view class="field-label"><text>检验员</text></view>
        <view class="input-box">
          <input v-model="form.inspector" class="input" placeholder="默认当前账号" />
        </view>
      </view>

      <view class="field">
        <view class="field-label"><text>备注</text></view>
        <view class="input-box textarea-box">
          <textarea
            v-model="form.remark"
            class="textarea"
            maxlength="200"
            placeholder="记录缺陷描述、处理意见或抽检说明"
          />
        </view>
      </view>
    </view>

    <view class="tip-card">
      <text class="tip-title">检验规则</text>
      <text class="tip-line">· 不良数量为 0 判定合格，大于 0 判定不合格。</text>
      <text class="tip-line">· 来料检验合格后，系统会自动完成对应入库单整单入库。</text>
      <text class="tip-line">· 完工检验合格后，合格品自动进入成品仓，并回写工单合格数。</text>
    </view>

    <view class="action-bar">
      <view class="btn btn-primary btn-block" :class="{ disabled: saving }" @tap="submit">
        {{ saving ? '提交中…' : `提交${typeLabel}` }}
      </view>
    </view>
  </view>
</template>

<style scoped>
.defect-total-text {
  display: flex;
  align-items: center;
  height: 80rpx;
  color: #b91c1c;
  font-size: 30rpx;
  font-weight: 600;
}

.sample-tip {
  margin-top: 10rpx;
  color: #64748b;
  font-size: 21rpx;
  line-height: 1.6;
}

.check-item {
  margin-top: 14rpx;
  padding: 18rpx 20rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 14rpx;
  background: #f8fafc;
}

.check-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
}

.check-name {
  flex: 1;
  color: #0f172a;
  font-size: 26rpx;
  font-weight: 600;
}

.check-std {
  display: block;
  margin-top: 6rpx;
  color: #64748b;
  font-size: 21rpx;
}

.check-inputs {
  display: flex;
  gap: 12rpx;
  margin-top: 12rpx;
}

.check-input {
  flex: 1;
  height: 64rpx;
  padding: 0 18rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 10rpx;
  background: #ffffff;
  font-size: 24rpx;
}

.create-page {
  padding-bottom: calc(190rpx + env(safe-area-inset-bottom));
}

.scan-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 20rpx 24rpx 0;
  padding: 20rpx 22rpx;
  border: 1rpx solid #fed7aa;
  border-radius: 16rpx;
  background: #fff7ed;
}

.scan-banner-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.scan-banner-title {
  color: #c2410c;
  font-size: 25rpx;
  font-weight: 700;
}

.scan-banner-sub {
  margin-top: 4rpx;
  color: #9a3412;
  font-size: 21rpx;
}

.scan-banner-clear {
  flex: none;
  margin-left: 16rpx;
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  background: #ffffff;
  color: #c2410c;
  font-size: 21rpx;
  border: 1rpx solid #fed7aa;
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
  line-height: 1.6;
}

.result-preview {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 24rpx;
  padding: 18rpx 22rpx;
  border-radius: 14rpx;
  border: 1rpx solid rgba(148, 163, 184, 0.2);
  background: rgba(15, 23, 42, 0.34);
}

.preview-pass {
  border-color: rgba(34, 197, 94, 0.32);
}

.preview-fail {
  border-color: rgba(239, 68, 68, 0.4);
}

.preview-left {
  display: flex;
  flex-direction: column;
}

.preview-label {
  color: #94a3b8;
  font-size: 19rpx;
}

.preview-result {
  margin-top: 2rpx;
  color: #86efac;
  font-size: 34rpx;
  font-weight: 700;
}

.preview-fail .preview-result {
  color: #fca5a5;
}

.preview-rate {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.preview-rate-num {
  color: #fdba74;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 36rpx;
  font-weight: 700;
}

.preview-rate-label {
  color: #94a3b8;
  font-size: 18rpx;
}

.form-card {
  margin: 22rpx 24rpx 0;
}

.type-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12rpx;
}

.type-card {
  display: flex;
  flex-direction: column;
  padding: 18rpx 14rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 12rpx;
  background: #f8fafc;
}

.type-card.active {
  border-color: #fdba74;
  background: #fff7ed;
  box-shadow: 0 4rpx 12rpx rgba(249, 115, 22, 0.12);
}

.type-name {
  color: #1f2937;
  font-size: 24rpx;
  font-weight: 600;
}

.type-desc {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 18rpx;
  line-height: 1.45;
}

.type-card.active .type-name {
  color: #c2410c;
}

.inline-loading {
  padding: 20rpx 0;
}

.pick-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 18rpx;
  border-radius: 10rpx;
  background: #fff7ed;
  color: #c2410c;
  font-size: 22rpx;
}

.retry {
  font-weight: 700;
}

.pick-empty {
  padding: 30rpx 10rpx 16rpx;
  gap: 4rpx;
}

.inbound-items {
  margin-top: 16rpx;
  border: 1rpx solid #eef2f7;
  border-radius: 12rpx;
  overflow: hidden;
}

.inbound-hint {
  padding: 10rpx 18rpx;
  background: #f8fafc;
  color: #94a3b8;
  font-size: 19rpx;
}

.inbound-item {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 16rpx 18rpx;
  border-top: 1rpx solid #f1f5f9;
}

.inbound-item.active {
  background: #fff7ed;
}

.inbound-item-name {
  display: block;
  color: #1f2937;
  font-size: 24rpx;
  font-weight: 500;
}

.inbound-item-sub {
  display: block;
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 19rpx;
}

.inbound-item-qty {
  display: flex;
  align-items: baseline;
  gap: 4rpx;
  color: #c2410c;
  font-size: 26rpx;
  font-weight: 700;
}

.inbound-item-qty .unit {
  color: #94a3b8;
  font-size: 18rpx;
  font-weight: 400;
}

.radio {
  flex: none;
  width: 30rpx;
  height: 30rpx;
  border: 2rpx solid #cbd5e1;
  border-radius: 50%;
}

.radio.checked {
  border-color: #f97316;
  background: #f97316;
  box-shadow: inset 0 0 0 6rpx #fff7ed;
}

.field-note {
  margin-top: 12rpx;
  color: #94a3b8;
  font-size: 20rpx;
}

.qty-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16rpx;
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
