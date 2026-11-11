<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { workOrderApi } from '../../api'
import { ensureAuth, getUser } from '../../utils/auth'
import { isQueued } from '../../utils/offlineQueue'
import { fmtQty, progressOf } from '../../utils/format'
import StatusPill from '../../components/StatusPill.vue'
import EmptyState from '../../components/EmptyState.vue'
import MesWatermark from '../../components/Watermark.vue'

const id = ref(null)
const workNo = ref('')
const opSeq = ref('')
const detail = ref(null)
const loading = ref(true)
const error = ref('')
const actionLoading = ref(false)
const reportVisible = ref(false)
const reportSaving = ref(false)
let autoOpened = false

const reportForm = reactive({
  operationId: null,
  qty: '',
  defectQty: '',
  worker: '',
  remark: '',
  inspectionResult: ''
})

/** 随工单下发的 SOP / SIP：当前工序的指导书 + 产品级通用文档 */
const docs = ref({ productDocuments: [], operations: [] })
const docExpanded = ref(false)

const status = computed(() => detail.value?.status || '')
const progress = computed(() => progressOf(detail.value?.doneQty, detail.value?.planQty))
const currentUser = getUser() || {}

const isRework = computed(() => {
  const type = String(detail.value?.workType || '').toUpperCase()
  return type === 'REWORK' || Boolean(detail.value?.parentWorkOrderId)
})

const assignedWorkerText = computed(() => {
  const names = (detail.value?.operations || [])
    .map((op) => String(op.assignedWorker || '').trim())
    .filter(Boolean)
  const unique = [...new Set(names)]
  return unique.length ? unique.join('、') : '未派工'
})

const canRelease = computed(() => status.value === 'DRAFT')
const canIssue = computed(
  () => ['RELEASED', 'IN_PROGRESS'].includes(status.value) && detail.value && !detail.value.issued
)
const canReport = computed(() => ['RELEASED', 'IN_PROGRESS'].includes(status.value))
const canClose = computed(() => status.value === 'FINISHED')
const hasActions = computed(() => canRelease.value || canIssue.value || canReport.value || canClose.value)

const selectedOperation = computed(
  () => (detail.value?.operations || []).find((op) => String(op.id) === String(reportForm.operationId)) || null
)

/** 工序内检验点：报工必须给合格 / 不合格结论 */
const needInspect = computed(() => selectedOperation.value?.inspectionMode === 'ATTACHED')

const selectedDocs = computed(() => {
  const seq = selectedOperation.value?.seq
  const matched = (docs.value.operations || []).find((op) => String(op.seq) === String(seq))
  return [...(docs.value.productDocuments || []), ...((matched && matched.documents) || [])]
})

const loadDocs = async (workOrderId) => {
  if (!workOrderId) return
  try {
    docs.value = await workOrderApi.documents(workOrderId)
  } catch (e) {
    docs.value = { productDocuments: [], operations: [] }
  }
}

const remainingQty = computed(() => {
  if (!selectedOperation.value) return 0
  return Math.max(0, Number(selectedOperation.value.planQty || 0) - Number(selectedOperation.value.doneQty || 0))
})

/** 合格数默认「报工数 - 不良数」，随两项输入实时联动 */
const autoQualifiedQty = computed(() => {
  const qty = Number(reportForm.qty)
  if (!Number.isFinite(qty) || qty <= 0) return 0
  const defect = Number(reportForm.defectQty || 0)
  const safeDefect = Number.isFinite(defect) && defect > 0 ? defect : 0
  return Math.max(0, qty - safeDefect)
})

const load = async () => {
  if (!id.value && !workNo.value) return
  loading.value = true
  error.value = ''
  try {
    const data = workNo.value
      ? await workOrderApi.detailByNo(workNo.value)
      : await workOrderApi.detail(id.value)
    detail.value = data
    if (data?.id) id.value = data.id
    uni.setNavigationBarTitle({ title: detail.value?.workNo || '工单详情' })
    loadDocs(data?.id)
    tryOpenScannedOperation()
  } catch (e) {
    error.value = workNo.value
      ? `未找到工单 ${workNo.value}，请确认工单号或重新扫码`
      : '工单加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

const confirmAction = (content, title) =>
  new Promise((resolve) => {
    uni.showModal({
      title: title || '操作确认',
      content,
      confirmColor: '#f97316',
      success: (res) => resolve(res.confirm),
      fail: () => resolve(false)
    })
  })

const release = async () => {
  if (actionLoading.value) return
  const ok = await confirmAction(`下达工单 ${detail.value.workNo}？下达后可以领料和报工。`, '下达工单')
  if (!ok) return
  actionLoading.value = true
  try {
    await workOrderApi.release(id.value)
    uni.showToast({ title: '工单已下达', icon: 'success' })
    await load()
  } catch (e) {
    // 统一提示
  } finally {
    actionLoading.value = false
  }
}

const issue = async () => {
  if (actionLoading.value) return
  const ok = await confirmAction(
    `按 BOM 为工单 ${detail.value.workNo} 领料？系统将从原料仓扣减物料并写入库存流水。`,
    '领料确认'
  )
  if (!ok) return
  actionLoading.value = true
  try {
    await workOrderApi.issue(id.value)
    uni.showToast({ title: '领料完成', icon: 'success' })
    await load()
  } catch (e) {
    // 统一提示
  } finally {
    actionLoading.value = false
  }
}

const closeWork = async () => {
  if (actionLoading.value) return
  const ok = await confirmAction(`关闭工单 ${detail.value.workNo}？关闭后不再允许报工。`, '关闭工单')
  if (!ok) return
  actionLoading.value = true
  try {
    await workOrderApi.close(id.value)
    uni.showToast({ title: '工单已关闭', icon: 'success' })
    await load()
  } catch (e) {
    // 统一提示
  } finally {
    actionLoading.value = false
  }
}

const openReport = (op) => {
  if (!op || !op.id) {
    uni.showToast({ title: '当前没有可报工的工序', icon: 'none' })
    return
  }
  const remaining = Math.max(0, Number(op.planQty || 0) - Number(op.doneQty || 0))
  if (remaining <= 0) {
    uni.showToast({ title: '该工序已报满', icon: 'none' })
    return
  }
  reportForm.operationId = op.id
  reportForm.qty = String(remaining)
  reportForm.defectQty = ''
  // 报工人优先用派工人员，没派工才回落到当前登录人，工人不用每次填
  reportForm.worker = op.assignedWorker || currentUser.realName || currentUser.username || ''
  reportForm.remark = ''
  reportForm.inspectionResult = ''
  reportVisible.value = true
}

const chooseOperation = (op) => {
  reportForm.operationId = op.id
  const remaining = Math.max(0, Number(op.planQty || 0) - Number(op.doneQty || 0))
  reportForm.qty = String(remaining)
  reportForm.defectQty = ''
  reportForm.inspectionResult = ''
  if (!String(reportForm.worker || '').trim()) {
    reportForm.worker = op.assignedWorker || currentUser.realName || currentUser.username || ''
  }
}

const findOperationBySeq = (seq) => {
  const target = String(seq ?? '').trim()
  if (!target) return null
  const operations = detail.value?.operations || []
  return (
    operations.find((op) => String(op.seq ?? '') === target) ||
    operations.find((op) => Number(op.seq) === Number(target)) ||
    operations[Number(target) - 1] ||
    null
  )
}

/** 工序码扫码进入时，自动选中对应工序并打开报工弹层 */
const tryOpenScannedOperation = () => {
  if (autoOpened || !opSeq.value || !detail.value) return
  autoOpened = true
  const op = findOperationBySeq(opSeq.value)
  if (!op) {
    uni.showToast({ title: `未找到第 ${opSeq.value} 道工序`, icon: 'none' })
    return
  }
  if (!canReport.value) {
    uni.showToast({ title: '当前工单状态不可报工', icon: 'none' })
    return
  }
  const remaining = Math.max(0, Number(op.planQty || 0) - Number(op.doneQty || 0))
  if (remaining <= 0) {
    uni.showToast({ title: `${op.name || '该工序'}已报满`, icon: 'none' })
    return
  }
  openReport(op)
}

const submitReport = async () => {
  const qty = Number(reportForm.qty)
  const defect = Number(reportForm.defectQty || 0)
  const qualified = Math.max(0, qty - (Number.isFinite(defect) && defect > 0 ? defect : 0))
  if (!reportForm.operationId) {
    uni.showToast({ title: '请选择报工工序', icon: 'none' })
    return
  }
  if (!qty || qty <= 0) {
    uni.showToast({ title: '请输入大于 0 的报工数量', icon: 'none' })
    return
  }
  if (qty > remainingQty.value) {
    uni.showToast({ title: `最多可报 ${fmtQty(remainingQty.value)}`, icon: 'none' })
    return
  }
  if (!Number.isFinite(defect) || defect < 0) {
    uni.showToast({ title: '不良数量不能小于 0', icon: 'none' })
    return
  }
  if (defect > qty) {
    uni.showToast({ title: '不良数量不能大于报工数量', icon: 'none' })
    return
  }
  if (needInspect.value && !reportForm.inspectionResult) {
    uni.showToast({ title: '该工序是工序内检验点，请判定合格 / 不合格', icon: 'none' })
    return
  }
  if (reportSaving.value) return
  reportSaving.value = true
  try {
    const result = await workOrderApi.report(id.value, {
      operationId: reportForm.operationId,
      qty,
      defectQty: defect,
      qualifiedQty: qualified,
      worker: reportForm.worker.trim(),
      remark: reportForm.remark.trim(),
      inspectionResult: needInspect.value ? reportForm.inspectionResult : null
    })
    if (isQueued(result)) {
      reportVisible.value = false
      uni.showModal({
        title: '报工已离线保存',
        content: `当前网络不可用，${fmtQty(qty)} 件报工已存到手机本地，联网后自动上传（重复上传不会重复计数）。`,
        showCancel: false,
        confirmColor: '#f97316'
      })
      return
    }
    uni.showToast({
      title: result?.finished ? '报工成功，工单已完工' : '报工成功',
      icon: 'success'
    })
    reportVisible.value = false
    await load()
  } catch (e) {
    // 统一提示
  } finally {
    reportSaving.value = false
  }
}

onLoad((options) => {
  if (!ensureAuth()) return
  id.value = options?.id || null
  workNo.value = decodeParam(options?.workNo)
  opSeq.value = decodeParam(options?.opSeq)
  if (!id.value && !workNo.value) {
    error.value = '缺少工单参数'
    loading.value = false
    return
  }
  load()
})
</script>

<template>
  <view class="mes-page" :class="{ 'page-with-bar': hasActions && detail }">
    <MesWatermark />
    <view v-if="loading" class="loading-block">正在读取工单…</view>

    <view v-else-if="error || !detail" class="error-state">
      <EmptyState mark="!" text="工单加载失败" :hint="error" action-text="重新加载" @action="load" />
    </view>

    <template v-else>
      <view class="hero detail-hero">
        <view class="hero-inner">
          <view class="row between">
            <view class="row gap8">
              <text class="hero-no mono">{{ detail.workNo }}</text>
              <text class="tag hero-tag">生产工单</text>
            </view>
            <StatusPill :status="detail.status" />
          </view>

          <view class="hero-product">{{ detail.productName }}</view>
          <view class="hero-meta">
            {{ detail.productCode }} · {{ detail.spec || '无规格' }} · 单位 {{ detail.unit || '-' }}
          </view>

          <view class="hero-progress">
            <view class="row between">
              <text class="hero-progress-label">整体进度</text>
              <text class="hero-progress-pct">{{ progress }}%</text>
            </view>
            <view class="progress hero-track">
              <view class="progress-inner" :style="{ width: progress + '%' }"></view>
            </view>
            <view class="hero-qty-row">
              <view class="hero-qty">
                <text class="hero-qty-value num">{{ fmtQty(detail.doneQty) }}</text>
                <text class="hero-qty-label">已完成</text>
              </view>
              <text class="hero-slash">/</text>
              <view class="hero-qty">
                <text class="hero-qty-value num">{{ fmtQty(detail.planQty) }}</text>
                <text class="hero-qty-label">计划数量</text>
              </view>
              <view class="flex1"></view>
              <view class="hero-qty right">
                <text class="hero-qty-value num">{{ fmtQty(detail.qualifiedQty) }}</text>
                <text class="hero-qty-label">合格数量</text>
              </view>
            </view>
          </view>
        </view>
      </view>

      <view class="content">
        <view class="panel info-panel">
          <view class="section-title">工单信息</view>
          <view class="info-grid">
            <view class="info-item">
              <text class="info-label">来源订单</text>
              <text class="info-value mono">{{ detail.orderNo || '手动建单' }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">计划开始</text>
              <text class="info-value">{{ detail.planStart || '-' }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">计划完工</text>
              <text class="info-value">{{ detail.planEnd || '-' }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">创建时间</text>
              <text class="info-value">{{ detail.createdAt || '-' }}</text>
            </view>
            <view class="info-item">
              <text class="info-label">工单类型</text>
              <view class="info-value">
                <text class="tag" :class="isRework ? 'tag-orange' : 'tag-green'">
                  {{ isRework ? '返工工单' : '正常生产' }}
                </text>
              </view>
            </view>
            <view class="info-item">
              <text class="info-label">派工人员</text>
              <text class="info-value">{{ assignedWorkerText }}</text>
            </view>
            <view v-if="isRework" class="info-item">
              <text class="info-label">原工单</text>
              <text class="info-value mono">
                {{ detail.parentWorkNo || detail.parentWorkOrderId || '-' }}
              </text>
            </view>
            <view class="info-item">
              <text class="info-label">领料状态</text>
              <view class="info-value">
                <text class="tag" :class="detail.issued ? 'tag-green' : 'tag-orange'">
                  {{ detail.issued ? '已领料' : '未领料' }}
                </text>
              </view>
            </view>
            <view class="info-item">
              <text class="info-label">当前工序</text>
              <text class="info-value">
                {{ (detail.operations || []).find((op) => Number(op.doneQty) < Number(op.planQty))?.name || '全部完成' }}
              </text>
            </view>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head">
            <view class="section-title tight">工序进度</view>
            <text class="muted tiny">{{ (detail.operations || []).length }} 道工序</text>
          </view>

          <view v-for="(op, index) in detail.operations || []" :key="op.id" class="op-row">
            <view class="op-seq" :class="{ done: Number(op.doneQty) >= Number(op.planQty) }">
              {{ index + 1 }}
            </view>
            <view class="flex1">
              <view class="row between">
                <text class="op-name">{{ op.name }}</text>
                <StatusPill :status="op.status" size="small" />
              </view>
              <view class="row gap12 op-progress-row">
                <view class="progress flex1">
                  <view
                    class="progress-inner"
                    :style="{ width: progressOf(op.doneQty, op.planQty) + '%' }"
                  ></view>
                </view>
                <text class="op-qty">
                  {{ fmtQty(op.doneQty) }} / {{ fmtQty(op.planQty) }}
                </text>
              </view>
              <text class="op-worker">派工 {{ op.assignedWorker || '未派工' }}</text>
            </view>
            <view
              v-if="canReport && Number(op.doneQty) < Number(op.planQty)"
              class="btn btn-soft btn-sm op-action"
              @tap="openReport(op)"
            >
              报工
            </view>
          </view>

          <view v-if="!(detail.operations || []).length" class="empty op-empty">
            <text>暂无工序数据</text>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head">
            <view class="section-title tight">BOM 需求</view>
            <text class="muted tiny">{{ (detail.bom || []).length }} 种物料</text>
          </view>

          <view v-for="item in detail.bom || []" :key="item.materialId" class="bom-row">
            <view class="bom-mark">{{ (item.materialName || '料').slice(0, 1) }}</view>
            <view class="flex1">
              <view class="row gap8">
                <text class="bom-name ellipsis">{{ item.materialName }}</text>
                <text class="bom-code mono">{{ item.materialCode }}</text>
              </view>
              <text class="bom-unit">单位 {{ item.unit || '-' }}</text>
            </view>
            <view class="bom-qty">
              <text class="bom-need num">{{ fmtQty(item.needQty) }}</text>
              <text class="bom-need-label">需求</text>
            </view>
          </view>

          <view v-if="!(detail.bom || []).length" class="empty op-empty">
            <text>该产品未维护 BOM</text>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head">
            <view class="section-title tight">报工记录</view>
            <text class="muted tiny">{{ (detail.reports || []).length }} 条</text>
          </view>

          <view v-for="item in detail.reports || []" :key="item.id" class="report-row">
            <view class="report-dot"></view>
            <view class="flex1">
              <view class="row between">
                <text class="report-op">{{ item.operationName }}</text>
                <text class="report-time">{{ item.reportedAt }}</text>
              </view>
              <view class="report-meta">
                报工 <text class="strong">{{ fmtQty(item.qty) }}</text> · 合格
                <text class="text-green strong">{{ fmtQty(item.qualifiedQty) }}</text>
                <text v-if="item.defectQty > 0" class="report-defect">
                  · 不良 <text class="text-red strong">{{ fmtQty(item.defectQty) }}</text>
                </text>
                · {{ item.worker || '-' }}
              </view>
              <view v-if="item.remark" class="report-remark">{{ item.remark }}</view>
            </view>
          </view>

          <view v-if="!(detail.reports || []).length" class="empty op-empty">
            <text>暂无报工记录</text>
          </view>
        </view>
      </view>

      <view v-if="hasActions" class="action-bar">
        <view v-if="canRelease" class="btn btn-primary" @tap="release">下达工单</view>
        <view v-if="canIssue" class="btn btn-soft" @tap="issue">按 BOM 领料</view>
        <view
          v-if="canReport"
          class="btn btn-primary"
          @tap="openReport((detail.operations || []).find((op) => Number(op.doneQty) < Number(op.planQty)) || {})"
        >
          工序报工
        </view>
        <view v-if="canClose" class="btn btn-ghost" @tap="closeWork">关闭工单</view>
      </view>

      <view v-if="reportVisible" class="popup-mask" @tap="reportVisible = false">
        <view class="popup-panel" @tap.stop>
          <view class="popup-head">
            <view>
              <text class="popup-title">工序报工</text>
              <text class="report-head-sub">{{ detail.workNo }} · {{ detail.productName }}</text>
            </view>
            <view class="popup-close" @tap="reportVisible = false">×</view>
          </view>

          <scroll-view scroll-x class="op-picker" :show-scrollbar="false">
            <view class="op-picker-inner">
              <view
                v-for="op in detail.operations || []"
                :key="op.id"
                class="op-option"
                :class="{
                  active: String(reportForm.operationId) === String(op.id),
                  disabled: Number(op.doneQty) >= Number(op.planQty)
                }"
                @tap="chooseOperation(op)"
              >
                <text class="op-option-name">{{ op.name }}</text>
                <text class="op-option-qty">
                  剩 {{ fmtQty(Number(op.planQty || 0) - Number(op.doneQty || 0)) }}
                </text>
              </view>
            </view>
          </scroll-view>

          <view class="report-hint">
            <text>该工序剩余可报</text>
            <text class="report-remain num">{{ fmtQty(remainingQty) }}</text>
          </view>

          <view class="field">
            <view class="field-label"><text class="req">*</text><text>本次报工数量</text></view>
            <view class="input-box">
              <input v-model="reportForm.qty" class="input" type="digit" placeholder="请输入报工数量" />
            </view>
          </view>

          <view class="field">
            <view class="field-label"><text>其中不良数量</text></view>
            <view class="input-box">
              <input
                v-model="reportForm.defectQty"
                class="input"
                type="digit"
                placeholder="默认 0，不能大于报工数量"
              />
            </view>
          </view>

          <view class="field">
            <view class="field-label">
              <text>合格数量</text>
              <text class="auto-tag">自动计算</text>
            </view>
            <view class="qualified-box">
              <text class="qualified-value num">{{ fmtQty(autoQualifiedQty) }}</text>
              <text class="qualified-hint">报工数 - 不良数</text>
            </view>
          </view>

          <view v-if="needInspect" class="field">
            <view class="field-label">
              <text class="req">*</text><text>检验结论</text>
              <text class="auto-tag">工序内检验点</text>
            </view>
            <view class="judge-row">
              <view
                class="judge-btn"
                :class="{ pass: reportForm.inspectionResult === 'PASS' }"
                @tap="reportForm.inspectionResult = 'PASS'"
              >
                <text>合格</text>
              </view>
              <view
                class="judge-btn"
                :class="{ fail: reportForm.inspectionResult === 'FAIL' }"
                @tap="reportForm.inspectionResult = 'FAIL'"
              >
                <text>不合格</text>
              </view>
            </view>
            <text class="judge-tip">判不合格时不良数量必须大于 0，系统会生成过程检验记录与待处置不良。</text>
          </view>

          <view v-if="selectedDocs.length" class="field">
            <view class="doc-head" @tap="docExpanded = !docExpanded">
              <text class="doc-head-title">作业指导书（{{ selectedDocs.length }}）</text>
              <text class="doc-head-toggle">{{ docExpanded ? '收起' : '展开' }}</text>
            </view>
            <view v-if="docExpanded" class="doc-list">
              <view v-for="doc in selectedDocs" :key="doc.id" class="doc-item">
                <view class="doc-item-head">
                  <text class="doc-tag">{{ doc.docType }}</text>
                  <text class="doc-title">{{ doc.title }}</text>
                  <text class="doc-version">{{ doc.version }}</text>
                </view>
                <text class="doc-content">{{ doc.content || '（无正文，见附件）' }}</text>
                <text v-if="doc.fileUrl" class="doc-link">附件：{{ doc.fileUrl }}</text>
              </view>
            </view>
          </view>

          <view class="field">
            <view class="field-label"><text>操作工</text></view>
            <view class="input-box">
              <input v-model="reportForm.worker" class="input" placeholder="默认当前账号" />
            </view>
          </view>

          <view class="field">
            <view class="field-label"><text>备注</text></view>
            <view class="input-box textarea-box">
              <textarea v-model="reportForm.remark" class="textarea" placeholder="可选，记录异常或交接信息" />
            </view>
          </view>

          <view class="popup-actions">
            <view class="btn btn-ghost" @tap="reportVisible = false">取消</view>
            <view class="btn btn-primary" :class="{ disabled: reportSaving }" @tap="submitReport">
              {{ reportSaving ? '提交中…' : '提交报工' }}
            </view>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.detail-hero {
  padding-bottom: 36rpx;
}

.hero-no {
  color: #ffffff;
  font-size: 32rpx;
  font-weight: 700;
}

.hero-tag {
  background: rgba(148, 163, 184, 0.16);
  color: #cbd5e1;
}

.hero-product {
  margin-top: 24rpx;
  color: #ffffff;
  font-size: 38rpx;
  font-weight: 700;
}

.hero-meta {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 22rpx;
}

.hero-progress {
  margin-top: 30rpx;
}

.hero-progress-label {
  color: #94a3b8;
  font-size: 22rpx;
}

.hero-progress-pct {
  color: #fdba74;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 30rpx;
  font-weight: 700;
}

.hero-track {
  margin-top: 12rpx;
  background: rgba(148, 163, 184, 0.22);
}

.hero-qty-row {
  display: flex;
  align-items: flex-end;
  gap: 12rpx;
  margin-top: 22rpx;
}

.hero-qty {
  display: flex;
  flex-direction: column;
}

.hero-qty.right {
  align-items: flex-end;
}

.hero-qty-value {
  color: #ffffff;
  font-size: 40rpx;
  font-weight: 700;
  line-height: 1.1;
}

.hero-qty-label {
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 19rpx;
}

.hero-slash {
  padding-bottom: 4rpx;
  color: #475569;
  font-size: 30rpx;
}

.info-panel {
  padding-bottom: 12rpx;
}

.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24rpx 18rpx;
}

.info-item {
  display: flex;
  flex-direction: column;
}

.info-label {
  color: #94a3b8;
  font-size: 21rpx;
}

.info-value {
  margin-top: 4rpx;
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 500;
  word-break: break-all;
}

.tag-green {
  background: #f0fdf4;
  color: #15803d;
}

.tag-orange {
  background: #fff7ed;
  color: #c2410c;
}

.op-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 20rpx 0;
  border-top: 1rpx solid #f1f5f9;
}

.op-row:first-of-type {
  border-top: none;
}

.op-seq {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52rpx;
  height: 52rpx;
  border-radius: 12rpx;
  background: #f1f5f9;
  color: #64748b;
  font-family: 'DIN Alternate', 'SFMono-Regular', Menlo, Consolas, sans-serif;
  font-size: 24rpx;
  font-weight: 700;
}

.op-seq.done {
  background: #111827;
  color: #fdba74;
}

.op-name {
  color: #1f2937;
  font-size: 26rpx;
  font-weight: 600;
}

.op-progress-row {
  margin-top: 10rpx;
}

.op-qty {
  flex: none;
  color: #64748b;
  font-size: 21rpx;
}

.op-action {
  flex: none;
}

.op-empty {
  padding: 40rpx 0 20rpx;
  font-size: 23rpx;
}

.bom-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 0;
  border-top: 1rpx solid #f1f5f9;
}

.bom-row:first-of-type {
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
  font-size: 23rpx;
  font-weight: 700;
}

.bom-name {
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 500;
}

.bom-code {
  color: #94a3b8;
  font-size: 20rpx;
}

.bom-unit {
  color: #94a3b8;
  font-size: 20rpx;
}

.bom-qty {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.bom-need {
  color: #c2410c;
  font-size: 28rpx;
  font-weight: 700;
}

.bom-need-label {
  color: #94a3b8;
  font-size: 18rpx;
}

.report-row {
  display: flex;
  gap: 16rpx;
  padding: 16rpx 0;
  border-top: 1rpx solid #f1f5f9;
}

.report-row:first-of-type {
  border-top: none;
}

.report-dot {
  flex: none;
  width: 12rpx;
  height: 12rpx;
  margin-top: 10rpx;
  border-radius: 50%;
  background: #f97316;
  box-shadow: 0 0 0 6rpx rgba(249, 115, 22, 0.12);
}

.report-op {
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 600;
}

.report-time {
  color: #94a3b8;
  font-size: 20rpx;
}

.report-meta {
  margin-top: 4rpx;
  color: #64748b;
  font-size: 22rpx;
}

.report-remark {
  margin-top: 6rpx;
  padding: 10rpx 14rpx;
  border-radius: 8rpx;
  background: #f8fafc;
  color: #64748b;
  font-size: 21rpx;
}

.report-defect {
  color: #b91c1c;
}

.auto-tag {
  margin-left: 10rpx;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
  background: #f1f5f9;
  color: #64748b;
  font-size: 18rpx;
}

.qualified-box {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  min-height: 82rpx;
  padding: 0 22rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 12rpx;
  background: #f8fafc;
}

.qualified-value {
  color: #15803d;
  font-size: 34rpx;
}

.qualified-hint {
  color: #94a3b8;
  font-size: 21rpx;
}

.error-state {
  padding-top: 80rpx;
}

.report-head-sub {
  display: block;
  margin-top: 6rpx;
  color: #94a3b8;
  font-size: 22rpx;
}

.op-picker {
  width: 100%;
  white-space: nowrap;
  margin-bottom: 18rpx;
}

.op-picker-inner {
  display: inline-flex;
  gap: 12rpx;
}

.op-option {
  display: flex;
  flex-direction: column;
  min-width: 150rpx;
  padding: 16rpx 20rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 12rpx;
  background: #f8fafc;
}

.op-option.active {
  border-color: #fdba74;
  background: #fff7ed;
}

.op-option.disabled {
  opacity: 0.45;
}

.op-option-name {
  color: #1f2937;
  font-size: 25rpx;
  font-weight: 600;
}

.op-option-qty {
  margin-top: 2rpx;
  color: #94a3b8;
  font-size: 20rpx;
}

.report-hint {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 22rpx;
  padding: 16rpx 20rpx;
  border-radius: 10rpx;
  background: #f8fafc;
  color: #64748b;
  font-size: 23rpx;
}

.report-remain {
  color: #c2410c;
  font-size: 30rpx;
  font-weight: 700;
}

.popup-actions {
  display: flex;
  gap: 16rpx;
  margin-top: 12rpx;
}

.popup-actions .btn {
  flex: 1;
}

.judge-row {
  display: flex;
  gap: 16rpx;
}

.judge-btn {
  flex: 1;
  height: 72rpx;
  border-radius: 12rpx;
  border: 1rpx solid #e2e8f0;
  background: #fff;
  color: #475569;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26rpx;
}

.judge-btn.pass {
  border-color: #12b76a;
  background: #ecfdf3;
  color: #067647;
  font-weight: 600;
}

.judge-btn.fail {
  border-color: #f04438;
  background: #fef3f2;
  color: #b42318;
  font-weight: 600;
}

.judge-tip {
  display: block;
  margin-top: 10rpx;
  color: #94a3b8;
  font-size: 21rpx;
  line-height: 1.6;
}

.doc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 20rpx;
  border-radius: 12rpx;
  background: #f1f5f9;
}

.doc-head-title {
  color: #1f2937;
  font-size: 24rpx;
  font-weight: 600;
}

.doc-head-toggle {
  color: #2456d8;
  font-size: 22rpx;
}

.doc-list {
  margin-top: 12rpx;
}

.doc-item {
  padding: 16rpx 20rpx;
  margin-bottom: 12rpx;
  border: 1rpx solid #e5e9f0;
  border-radius: 12rpx;
  background: #fff;
}

.doc-item-head {
  display: flex;
  align-items: center;
  gap: 10rpx;
}

.doc-tag {
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
  background: #e8efff;
  color: #2456d8;
  font-size: 19rpx;
}

.doc-title {
  flex: 1;
  color: #1f2937;
  font-size: 24rpx;
  font-weight: 600;
}

.doc-version {
  color: #94a3b8;
  font-size: 20rpx;
}

.doc-content {
  display: block;
  margin-top: 10rpx;
  padding: 12rpx 14rpx;
  border-radius: 10rpx;
  background: #f8fafc;
  color: #475569;
  font-size: 22rpx;
  line-height: 1.8;
  white-space: pre-wrap;
}

.doc-link {
  display: block;
  margin-top: 8rpx;
  color: #2456d8;
  font-size: 21rpx;
  word-break: break-all;
}
</style>
