<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import jsQR from 'jsqr'
import { api, fmtQty } from '../../api'
import StatusTag from '../../components/StatusTag.vue'
import ReferenceSelect from '../../components/ReferenceSelect.vue'
import OperationReportPanel from './OperationReportPanel.vue'
import { useAuthStore } from '../../stores/auth'

type ScanKind = 'WO' | 'OP' | 'MAT' | 'SN'

interface ScanRecord {
  id: number
  raw: string
  kind: ScanKind
  title: string
  time: string
}

interface ParsedCode {
  kind: ScanKind
  workNo?: string
  seq?: number | null
  code?: string
  unitNo?: string
}

const auth = useAuthStore()

const videoRef = ref<HTMLVideoElement | null>(null)
const canvasRef = ref<HTMLCanvasElement | null>(null)
const cameraOn = ref(false)
const cameraStarting = ref(false)
const cameraError = ref('')

let mediaStream: MediaStream | null = null
let decodeTimer: number | null = null
let pausedUntil = 0

const scanInput = ref('')
const scanInputRef = ref<any>(null)
const recognizing = ref(false)
const recent = ref<ScanRecord[]>([])
const lastScan = ref<ScanRecord | null>(null)

// 扫码枪模式：USB / 蓝牙扫码枪都是键盘楔入，把焦点钉在扫码框上就能直接扫，
// 焦点被点到别处时用全局按键流兜底，扫码枪一次输入以回车结尾。
const SCANNER_MODE_KEY = 'mes:scanner-mode'
const scannerMode = ref(localStorage.getItem(SCANNER_MODE_KEY) !== 'off')
let gunBuffer = ''
let gunTimer: number | null = null

watch(scannerMode, (value) => {
  localStorage.setItem(SCANNER_MODE_KEY, value ? 'on' : 'off')
  if (value) focusScanInput()
})

const focusScanInput = () => {
  nextTick(() => {
    const input = scanInputRef.value
    if (!input) return
    if (typeof input.focus === 'function') input.focus()
    const el = input.input as HTMLInputElement | undefined
    if (el && typeof el.focus === 'function') el.focus()
  })
}

const onGlobalKeydown = (event: KeyboardEvent) => {
  if (!scannerMode.value) return
  const target = event.target as HTMLElement | null
  const tag = target?.tagName
  // 正在表单里打字时不动它，交回给输入框自己处理
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target?.isContentEditable) return

  if (event.key === 'Enter') {
    const code = gunBuffer.trim()
    gunBuffer = ''
    if (gunTimer) window.clearTimeout(gunTimer)
    if (code) {
      scanInput.value = code
      handleCode(code)
    }
    return
  }
  if (event.key.length === 1) {
    gunBuffer += event.key
    if (gunTimer) window.clearTimeout(gunTimer)
    // 超过 300ms 没有下一个字符就当误触，清掉
    gunTimer = window.setTimeout(() => {
      gunBuffer = ''
    }, 300)
  }
}

const mode = ref<ScanKind | ''>('')

// 页面按扫码类型拆成三个页签，默认停在「扫工单码」
const activeTab = ref<'WO' | 'OP' | 'SN'>('WO')
// 扫到某一类编码并识别成功后，自动切到对应页签，避免扫完看不到结果
watch(lastScan, (record) => {
  if (!record) return
  if (record.kind === 'WO' || record.kind === 'OP' || record.kind === 'SN') {
    activeTab.value = record.kind
  }
})

const currentWork = ref<any>(null)
const currentMaterial = ref<any>(null)
const currentUnit = ref<any>(null)
const unitLoading = ref(false)
const unitWorker = ref('')
const unitRemark = ref('')
const unitSubmitting = ref(false)
const workLoading = ref(false)
const materialLoading = ref(false)

const reportForm = reactive<any>({
  operationId: null,
  qty: 0,
  defectQty: 0,
  worker: '',
  remark: '',
  inspectionResult: '',
  inspectionRemark: ''
})
const submittingReport = ref(false)
const reportQualified = computed(() =>
  Math.max(0, Number(reportForm.qty || 0) - Number(reportForm.defectQty || 0))
)
const canReport = computed(() =>
  !!currentWork.value && ['RELEASED', 'IN_PROGRESS'].includes(String(currentWork.value.status))
)
const selectedOperation = computed(() =>
  (currentWork.value?.operations || []).find((op: any) => op.id === reportForm.operationId)
)
// 工序内检验点：报工必须给出合格 / 不合格结论
const needInspect = computed(() => selectedOperation.value?.inspectionMode === 'ATTACHED')

// 随工单下发的 SOP / SIP：当前工序的指导书 + 产品级通用文档
const workDocs = ref<any>({ productDocuments: [], operations: [] })
const selectedDocs = computed(() => {
  const seq = selectedOperation.value?.seq
  const opDocs = (workDocs.value.operations || []).find((op: any) => Number(op.seq) === Number(seq))
  return [...(workDocs.value.productDocuments || []), ...((opDocs && opDocs.documents) || [])]
})

const issueWorkOrderId = ref<number | null>(null)
const issueWorkOrderLabel = ref('')
const issueWorkDetail = ref<any>(null)
const issueWarehouseId = ref<number | null>(null)
const issueWarehouseLabel = ref('')
const issueIssuer = ref('')
const issueDetailLoading = ref(false)
const issueQty = ref<number>(1)
const submittingIssue = ref(false)

const issueBomLine = computed(() => {
  if (!currentMaterial.value || !issueWorkDetail.value) return null
  return (
    (issueWorkDetail.value.bom || []).find(
      (line: any) => Number(line.materialId) === Number(currentMaterial.value.id)
    ) || null
  )
})

const issueRemaining = computed(() => {
  if (!issueBomLine.value) return 0
  const need = Number(issueBomLine.value.needQty || 0)
  const issued = Number(issueBomLine.value.issuedQty || 0)
  const returned = Number(issueBomLine.value.returnedQty || 0)
  return Math.max(0, need - issued + returned)
})

const defaultWorker = () => auth.user?.realName || auth.user?.username || ''

let lastErrorText = ''
let lastErrorAt = 0
const notifyError = (err: any, fallback = '操作失败') => {
  const message = err?.response?.data?.message || err?.message || fallback
  const now = Date.now()
  // api.ts 的响应拦截器已经弹过后端 message，这里做一次去重，避免重复提示
  if (message === lastErrorText && now - lastErrorAt < 1500) return
  lastErrorText = message
  lastErrorAt = now
  ElMessage.error(message)
}

const kindLabel = (kind: ScanKind) => ({ WO: '工单', OP: '工序', MAT: '物料', SN: '件码' }[kind] || kind)

// 单件（SN）状态与动作
const unitStatusLabel = (status: string) =>
  ({ PENDING: '待投产', IN_PROGRESS: '在制', FINISHED: '已完工', SCRAPPED: '已报废' }[status] || status)
const unitStatusCls = (status: string) =>
  ({ PENDING: 'draft', IN_PROGRESS: 'warning', FINISHED: 'success', SCRAPPED: 'danger' }[status] || 'info')
const unitActionLabel = (action: string) => ({ START: '开工', FINISH: '完工', SCRAP: '报废' }[action] || action)
const unitOperationText = (operation: any) =>
  operation ? `${operation.seq}. ${operation.name}（完成 ${fmtQty(operation.doneQty)} / 计划 ${fmtQty(operation.planQty)}）` : ''

const addRecent = (raw: string, kind: ScanKind, title: string) => {
  const record: ScanRecord = {
    id: Date.now() + Math.random(),
    raw,
    kind,
    title,
    time: new Date().toLocaleTimeString('zh-CN', { hour12: false })
  }
  recent.value = [record, ...recent.value.filter((item) => item.raw !== raw)].slice(0, 10)
  lastScan.value = record
}

const parseCode = (raw: string): ParsedCode | null => {
  const text = String(raw || '').trim()
  if (!text) return null
  const parts = text.split('|').map((part) => part.trim())
  if (parts.length > 1 && parts[0].toUpperCase() === 'MES') {
    const tag = (parts[1] || '').toUpperCase()
    if (tag === 'WO' && parts[2]) return { kind: 'WO', workNo: parts[2], seq: null }
    if (tag === 'OP' && parts[2]) {
      const seq = Number(parts[3])
      if (!parts[3] || Number.isNaN(seq)) return null
      return { kind: 'OP', workNo: parts[2], seq }
    }
    if (tag === 'MAT' && parts[2]) return { kind: 'MAT', code: parts[2] }
    if (tag === 'SN' && parts[2]) return { kind: 'SN', unitNo: parts[2] }
    return null
  }
  // 件码形如 工单号-001，末尾三位流水号与工单号区分
  if (/^\S+-\d{3}$/.test(text)) return { kind: 'SN', unitNo: text }
  return { kind: 'WO', workNo: text, seq: null }
}

const loadIssueDetail = async () => {
  if (!issueWorkOrderId.value) {
    issueWorkDetail.value = null
    return
  }
  issueDetailLoading.value = true
  try {
    issueWorkDetail.value = await api.get(`/work-orders/${issueWorkOrderId.value}`)
  } catch (err) {
    issueWorkDetail.value = null
    notifyError(err, '工单详情加载失败')
  } finally {
    issueDetailLoading.value = false
  }
}

/** 没有指定工单时默认落到第一张可领料工单，具体工单用远程搜索选择 */
const loadIssueWorkOrders = async () => {
  try {
    if (!issueWorkOrderId.value) {
      const options = await api.get('/work-orders/options', {
        status: 'RELEASED,IN_PROGRESS',
        limit: 1
      })
      const first = options?.[0] || null
      issueWorkOrderId.value = first ? first.id : null
      issueWorkOrderLabel.value = first ? `${first.workNo} · ${first.productName}` : ''
    }
    await loadIssueDetail()
  } catch (err) {
    notifyError(err, '可领料工单加载失败')
  }
}

const applyOperation = (operation: any) => {
  if (!operation) {
    reportForm.operationId = null
    reportForm.qty = 0
    return
  }
  reportForm.operationId = operation.id
  reportForm.qty = Math.max(0, Number(operation.planQty || 0) - Number(operation.doneQty || 0))
  reportForm.defectQty = 0
  reportForm.worker = operation.assignedWorker || defaultWorker()
  reportForm.inspectionResult = ''
  reportForm.inspectionRemark = ''
}

const onOperationChange = (operationId: number) => {
  const operation = (currentWork.value?.operations || []).find((op: any) => op.id === operationId)
  applyOperation(operation)
}

const selectOperation = (operation: any) => {
  if (!operation) return
  onOperationChange(operation.id)
}

const loadWorkDocs = async (workId: number) => {
  try {
    workDocs.value = await api.get(`/work-orders/${workId}/documents`)
  } catch {
    workDocs.value = { productDocuments: [], operations: [] }
  }
}

const loadWorkOrder = async (workNo: string, seq: number | null, raw: string, kind: ScanKind) => {
  workLoading.value = true
  try {
    const data = await api.get(`/work-orders/by-no/${encodeURIComponent(workNo)}`)
    currentWork.value = data
    currentMaterial.value = null
    currentUnit.value = null
    mode.value = kind
    loadWorkDocs(data.id)
    if (['RELEASED', 'IN_PROGRESS'].includes(String(data.status))) {
      issueWorkOrderId.value = data.id
      issueWorkOrderLabel.value = `${data.workNo} · ${data.productName}`
    }
    const operations = data.operations || []
    let operation = null
    if (seq !== null && seq !== undefined) {
      operation = operations.find((op: any) => Number(op.seq) === Number(seq))
      if (!operation) {
        ElMessage.warning(`工单 ${data.workNo} 下未找到工序序号 ${seq}，已默认选择当前工序`)
      }
    }
    if (!operation) {
      operation = operations.find((op: any) => Number(op.doneQty) < Number(op.planQty)) || operations[0]
    }
    applyOperation(operation)
    reportForm.remark = ''
    addRecent(raw, kind, `${data.workNo} · ${data.productName}`)
  } catch (err) {
    notifyError(err, `未找到工单 ${workNo}`)
  } finally {
    workLoading.value = false
  }
}

const loadMaterial = async (code: string, raw: string) => {
  materialLoading.value = true
  try {
    const list = await api.get('/products', { keyword: code })
    const target = (list || []).find(
      (item: any) => String(item.code || '').toLowerCase() === code.toLowerCase()
    )
    if (!target) {
      ElMessage.warning(`未找到物料编码 ${code}，请确认物料档案`)
      return
    }
    currentMaterial.value = target
    currentWork.value = null
    currentUnit.value = null
    mode.value = 'MAT'
    issueQty.value = 1
    addRecent(raw, 'MAT', `${target.code} · ${target.name}`)
    await loadIssueWorkOrders()
  } catch (err) {
    notifyError(err, '物料查询失败')
  } finally {
    materialLoading.value = false
  }
}

const loadUnit = async (unitNo: string, raw: string) => {
  unitLoading.value = true
  try {
    const data = await api.get(`/work-order-units/${encodeURIComponent(unitNo)}`)
    currentUnit.value = data
    currentWork.value = null
    currentMaterial.value = null
    mode.value = 'SN'
    unitRemark.value = ''
    if (!unitWorker.value.trim()) unitWorker.value = defaultWorker()
    addRecent(raw, 'SN', `${data.unitNo} · ${data.productName || ''}`)
  } catch (err) {
    currentUnit.value = null
    notifyError(err, `未找到件码 ${unitNo}`)
  } finally {
    unitLoading.value = false
  }
}

const refreshUnit = async () => {
  if (!currentUnit.value?.unitNo) return
  await loadUnit(currentUnit.value.unitNo, currentUnit.value.unitNo)
}

const submitUnitStart = async () => {
  if (!currentUnit.value) return
  if (!unitWorker.value.trim()) {
    ElMessage.warning('请填写操作工')
    return
  }
  unitSubmitting.value = true
  try {
    currentUnit.value = await api.post(
      `/work-order-units/${encodeURIComponent(currentUnit.value.unitNo)}/start`,
      { worker: unitWorker.value.trim() }
    )
    ElMessage.success(`${currentUnit.value.unitNo} 已开工`)
  } catch (err) {
    notifyError(err, '开工失败')
  } finally {
    unitSubmitting.value = false
  }
}

const submitUnitFinish = async (qualified: boolean) => {
  if (!currentUnit.value) return
  if (!unitWorker.value.trim()) {
    ElMessage.warning('请填写操作工')
    return
  }
  if (!qualified && !unitRemark.value.trim()) {
    ElMessage.warning('判不合格时请填写不良原因')
    return
  }
  unitSubmitting.value = true
  try {
    const unitNo = currentUnit.value.unitNo
    const data = await api.post(`/work-order-units/${encodeURIComponent(unitNo)}/finish`, {
      worker: unitWorker.value.trim(),
      qualified,
      remark: unitRemark.value.trim() || null
    })
    currentUnit.value = data
    unitRemark.value = ''
    if (!qualified) {
      ElMessage.warning(`${unitNo} 已判不合格，件转入报废`)
    } else if (data.status === 'FINISHED') {
      ElMessage.success(`${unitNo} 末道工序完成，整件已完工`)
    } else {
      ElMessage.success(`${unitNo} 本工序完工，已流转到 ${unitOperationText(data.currentOperation)}`)
    }
  } catch (err) {
    notifyError(err, '完工失败')
  } finally {
    unitSubmitting.value = false
  }
}

const submitUnitScrap = async () => {
  if (!currentUnit.value) return
  if (!unitRemark.value.trim()) {
    ElMessage.warning('报废必须填写原因')
    return
  }
  unitSubmitting.value = true
  try {
    const unitNo = currentUnit.value.unitNo
    currentUnit.value = await api.post(`/work-order-units/${encodeURIComponent(unitNo)}/scrap`, {
      reason: unitRemark.value.trim(),
      worker: unitWorker.value.trim() || defaultWorker()
    })
    unitRemark.value = ''
    ElMessage.warning(`${unitNo} 已报废`)
  } catch (err) {
    notifyError(err, '报废失败')
  } finally {
    unitSubmitting.value = false
  }
}

const handleCode = async (raw: string) => {
  const text = String(raw || '').trim()
  if (!text) {
    ElMessage.warning('请扫描或输入编码')
    return
  }
  const parsed = parseCode(text)
  if (!parsed) {
    ElMessage.error(`无法识别的编码：${text}`)
    // 扫码枪模式下清空，避免下一次扫码把新码拼在旧码后面
    if (scannerMode.value) {
      scanInput.value = ''
      focusScanInput()
    }
    return
  }
  recognizing.value = true
  try {
    if (parsed.kind === 'MAT' && parsed.code) {
      await loadMaterial(parsed.code, text)
    } else if (parsed.kind === 'SN' && parsed.unitNo) {
      await loadUnit(parsed.unitNo, text)
    } else if (parsed.workNo) {
      await loadWorkOrder(parsed.workNo, parsed.seq ?? null, text, parsed.kind)
    }
  } finally {
    recognizing.value = false
    if (scannerMode.value) {
      scanInput.value = ''
      focusScanInput()
    }
  }
}

const submitManual = async () => {
  await handleCode(scanInput.value)
}

const pickRecent = async (record: ScanRecord) => {
  scanInput.value = record.raw
  await handleCode(record.raw)
}

const refreshCurrentWork = async () => {
  if (!currentWork.value) return
  const operationId = reportForm.operationId
  const data = await api.get(`/work-orders/by-no/${encodeURIComponent(currentWork.value.workNo)}`)
  currentWork.value = data
  if (Number(issueWorkOrderId.value) === Number(data.id)) {
    issueWorkOrderLabel.value = `${data.workNo} · ${data.productName}`
  }
  const operations = data.operations || []
  const operation = operations.find((op: any) => op.id === operationId) || operations[0]
  applyOperation(operation)
}

const submitReport = async () => {
  if (!currentWork.value) return
  if (!canReport.value) {
    ElMessage.warning('当前工单状态不允许报工')
    return
  }
  if (!reportForm.operationId) {
    ElMessage.warning('请选择报工工序')
    return
  }
  if (!reportForm.qty || Number(reportForm.qty) <= 0) {
    ElMessage.warning('请填写本次报工数量')
    return
  }
  if (Number(reportForm.defectQty || 0) > Number(reportForm.qty)) {
    ElMessage.warning('不良数量不能大于报工数量')
    return
  }
  if (!String(reportForm.worker || '').trim()) {
    ElMessage.warning('请填写报工人')
    return
  }
  if (needInspect.value && !reportForm.inspectionResult) {
    ElMessage.warning('该工序是工序内检验点，请先判定合格 / 不合格')
    return
  }
  submittingReport.value = true
  try {
    const result = await api.post(`/work-orders/${currentWork.value.id}/report`, {
      operationId: reportForm.operationId,
      qty: Number(reportForm.qty),
      qualifiedQty: reportQualified.value,
      defectQty: Number(reportForm.defectQty || 0),
      worker: String(reportForm.worker).trim(),
      remark: reportForm.remark,
      inspectionResult: needInspect.value ? reportForm.inspectionResult : null,
      inspectionRemark: reportForm.inspectionRemark
    })
    ElMessage.success(result?.finished ? '报工成功，工单已完工' : '报工成功')
    await refreshCurrentWork()
  } catch (err) {
    notifyError(err, '报工失败')
  } finally {
    submittingReport.value = false
  }
}

const refreshMaterialStock = async () => {
  if (!currentMaterial.value) return
  try {
    const list = await api.get('/products', { keyword: currentMaterial.value.code })
    const target = (list || []).find(
      (item: any) =>
        String(item.code || '').toLowerCase() === String(currentMaterial.value.code || '').toLowerCase()
    )
    if (target) currentMaterial.value = target
  } catch (err) {
    notifyError(err, '库存刷新失败')
  }
}

const submitIssue = async () => {
  if (!currentMaterial.value) return
  if (!issueWorkOrderId.value) {
    ElMessage.warning('请选择领料工单')
    return
  }
  if (!issueQty.value || Number(issueQty.value) <= 0) {
    ElMessage.warning('请填写领料数量')
    return
  }
  if (!issueIssuer.value.trim()) {
    ElMessage.warning('请填写领料人')
    return
  }
  if (issueWorkDetail.value && !issueBomLine.value) {
    ElMessage.warning('该物料不在所选工单 BOM 中，无法领料')
    return
  }
  if (issueBomLine.value && issueRemaining.value <= 0) {
    ElMessage.warning('该物料已领足，无需继续领料')
    return
  }
  if (issueBomLine.value && Number(issueQty.value) > issueRemaining.value) {
    ElMessage.warning(`领料数量超过剩余需求，最多可领 ${fmtQty(issueRemaining.value)}`)
    return
  }
  if (Number(issueQty.value) > Number(currentMaterial.value.stock || 0)) {
    ElMessage.warning(`库存不足，当前可用 ${fmtQty(currentMaterial.value.stock)}`)
    return
  }
  submittingIssue.value = true
  try {
    const result = await api.post(`/work-orders/${issueWorkOrderId.value}/issue-item`, {
      productId: currentMaterial.value.id,
      qty: Number(issueQty.value),
      warehouseId: issueWarehouseId.value,
      issuer: issueIssuer.value.trim(),
      source: 'SCAN'
    })
    ElMessage.success(
      `领料成功，剩余可领 ${fmtQty(result?.remainingQty)} ${currentMaterial.value.unit || ''}`.trim()
    )
    await loadIssueDetail()
    await refreshMaterialStock()
  } catch (err) {
    notifyError(err, '领料失败')
  } finally {
    submittingIssue.value = false
  }
}

const stopMediaStream = () => {
  mediaStream?.getTracks().forEach((track) => track.stop())
  mediaStream = null
  if (videoRef.value) videoRef.value.srcObject = null
}

const stopDecodeLoop = () => {
  if (decodeTimer !== null) {
    window.clearInterval(decodeTimer)
    decodeTimer = null
  }
}

const decodeFrame = () => {
  if (!cameraOn.value || recognizing.value || workLoading.value || materialLoading.value || unitLoading.value) return
  if (Date.now() < pausedUntil) return
  const video = videoRef.value
  const canvas = canvasRef.value
  if (!video || !canvas || video.readyState < 2 || !video.videoWidth) return

  const maxSide = 720
  const scale = Math.min(1, maxSide / Math.max(video.videoWidth, video.videoHeight))
  const width = Math.max(1, Math.round(video.videoWidth * scale))
  const height = Math.max(1, Math.round(video.videoHeight * scale))
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d', { willReadFrequently: true })
  if (!context) return
  context.drawImage(video, 0, 0, width, height)
  const image = context.getImageData(0, 0, width, height)
  const result = jsQR(image.data, image.width, image.height, { inversionAttempts: 'attemptBoth' })
  if (result?.data) {
    pausedUntil = Date.now() + 1500
    scanInput.value = result.data
    handleCode(result.data)
  }
}

const startDecodeLoop = () => {
  stopDecodeLoop()
  decodeTimer = window.setInterval(decodeFrame, 300)
}

const startCamera = async () => {
  if (cameraOn.value || cameraStarting.value) return
  if (!navigator.mediaDevices?.getUserMedia) {
    cameraError.value = '当前环境不支持摄像头，请使用 HTTPS / localhost，或改用下方手动输入'
    ElMessage.error(cameraError.value)
    return
  }
  cameraStarting.value = true
  cameraError.value = ''
  try {
    mediaStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'environment', width: { ideal: 1280 }, height: { ideal: 720 } },
      audio: false
    })
    await nextTick()
    if (!videoRef.value) {
      stopMediaStream()
      return
    }
    videoRef.value.srcObject = mediaStream
    await videoRef.value.play().catch(() => undefined)
    cameraOn.value = true
    pausedUntil = 0
    startDecodeLoop()
  } catch (err: any) {
    stopMediaStream()
    const name = err?.name
    if (name === 'NotAllowedError' || name === 'SecurityError') {
      cameraError.value = '摄像头权限被拒绝，请在浏览器地址栏允许摄像头后重试'
    } else if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
      cameraError.value = '未检测到摄像头设备，请使用手动输入或 USB 扫码枪'
    } else if (name === 'NotReadableError') {
      cameraError.value = '摄像头被其他程序占用，请关闭占用后重试'
    } else {
      cameraError.value = err?.message || '摄像头启动失败'
    }
    ElMessage.error(cameraError.value)
  } finally {
    cameraStarting.value = false
  }
}

const stopCamera = () => {
  stopDecodeLoop()
  stopMediaStream()
  cameraOn.value = false
  pausedUntil = 0
}

const loadIssueWarehouses = async () => {
  try {
    // 默认落到第一个原料仓，不再整表加载仓库
    let options = await api.get('/options/warehouses', { type: 'RAW', limit: 1 })
    if (!options?.length) options = await api.get('/options/warehouses', { limit: 1 })
    const raw = options?.[0] || null
    issueWarehouseId.value = raw ? raw.id : null
    issueWarehouseLabel.value = raw ? (raw.code ? `${raw.code} · ${raw.name}` : raw.name) : ''
  } catch (err) {
    // 仓库加载失败不阻断扫码，提交时后端会回退到默认原料仓
  }
}

onMounted(async () => {
  if (scannerMode.value) focusScanInput()
  window.addEventListener('keydown', onGlobalKeydown)
  await loadIssueWarehouses()
  issueIssuer.value = auth.user?.realName || auth.user?.username || ''
})

onBeforeUnmount(() => {
  stopCamera()
  window.removeEventListener('keydown', onGlobalKeydown)
  if (gunTimer) window.clearTimeout(gunTimer)
})
</script>

<template>
  <div class="page scan-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">车间扫码</h2>
        <p class="page-sub">
          支持件码 MES|SN|件码（或直接扫工单号-001）、工单码 MES|WO|工单号、工序码 MES|OP|工单号|工序序号、物料码 MES|MAT|物料编码，识别后可直接开工完工或报工领料
        </p>
      </div>
      <div class="head-actions">
        <span class="status-tag" :class="cameraOn ? 'success' : 'draft'">
          <i></i>{{ cameraOn ? '摄像头运行中' : '摄像头未启动' }}
        </span>
        <el-button
          :type="cameraOn ? 'danger' : 'primary'"
          plain
          :loading="cameraStarting"
          @click="cameraOn ? stopCamera() : startCamera()"
        >
          {{ cameraOn ? '停止摄像头' : '启动摄像头' }}
        </el-button>
      </div>
    </div>

    <div class="scan-layout">
      <div class="left-col">
        <div class="panel">
          <div class="card-head">
            <div class="card-title">扫码通道</div>
            <span class="toolbar-tip">每 300ms 解码一次 · 成功后暂停 1.5 秒</span>
          </div>
          <div class="card-body">
            <div class="camera-stage" :class="{ live: cameraOn }">
              <video ref="videoRef" autoplay muted playsinline></video>
              <div v-if="!cameraOn" class="camera-mask">
                <el-icon :size="32"><Camera /></el-icon>
                <p>{{ cameraError || '摄像头未启动' }}</p>
                <span>桌面浏览器请允许摄像头权限；USB 扫码枪可直接在下方输入框扫码</span>
              </div>
              <template v-else>
                <div class="scan-frame">
                  <span class="corner tl"></span>
                  <span class="corner tr"></span>
                  <span class="corner bl"></span>
                  <span class="corner br"></span>
                </div>
                <div class="laser"></div>
                <div class="live-badge"><span></span>LIVE</div>
              </template>
              <canvas ref="canvasRef" class="hidden-canvas"></canvas>
            </div>

            <div class="manual-row">
              <el-input
                ref="scanInputRef"
                v-model="scanInput"
                placeholder="扫描或输入编码，如 MES|WO|WO20251020001"
                clearable
                @keyup.enter="submitManual"
              >
                <template #prefix>
                  <el-icon><Aim /></el-icon>
                </template>
              </el-input>
              <el-button type="primary" :loading="recognizing" @click="submitManual">识别</el-button>
            </div>
            <div class="scanner-row">
              <el-switch v-model="scannerMode" size="small" />
              <span class="scanner-label">扫码枪模式</span>
              <span class="muted scanner-hint">
                焦点常驻扫码框，USB / 蓝牙扫码枪扫完自动识别；手动敲字也可以，重进页面仍保持上次设置
              </span>
            </div>
            <div class="format-hint">
              <span class="mono">MES|SN|件码</span>
              <span class="mono">MES|WO|工单号</span>
              <span class="mono">MES|OP|工单号|工序序号</span>
              <span class="mono">MES|MAT|物料编码</span>
              <span class="muted">裸件码按件识别，裸工单号按工单识别</span>
            </div>
            <div v-if="lastScan" class="last-scan">
              <span class="kind" :class="lastScan.kind.toLowerCase()">{{ kindLabel(lastScan.kind) }}</span>
              <b>{{ lastScan.title }}</b>
              <span class="mono muted">{{ lastScan.raw }}</span>
            </div>
          </div>
        </div>

        <div class="panel recent-panel">
          <div class="card-head">
            <div class="card-title">最近识别</div>
            <span class="toolbar-tip">{{ recent.length }} / 10 · 点击可回填</span>
          </div>
          <div class="recent-list">
            <button v-for="item in recent" :key="item.id" type="button" class="recent-item" @click="pickRecent(item)">
              <span class="kind" :class="item.kind.toLowerCase()">{{ kindLabel(item.kind) }}</span>
              <span class="recent-main">
                <b>{{ item.title }}</b>
                <small class="mono">{{ item.raw }}</small>
              </span>
              <span class="recent-time mono">{{ item.time }}</span>
            </button>
            <div v-if="!recent.length" class="recent-empty">暂无识别记录，扫码或手动识别后保留最近 10 条</div>
          </div>
        </div>
      </div>

      <div
        v-loading="workLoading || materialLoading || unitLoading"
        class="right-col"
        element-loading-text="正在查询编码..."
      >
        <el-tabs v-model="activeTab" class="scan-tabs">
          <el-tab-pane label="扫工单码" name="WO">
            <p class="tab-intro">
              扫工单码 MES|WO|工单号：识别后查看工单进度与工序列表，并直接报工。
            </p>
            <template v-if="currentWork">
              <div class="result-grid">
                <div class="panel">
                  <div class="card-head">
                    <div class="card-title">工单信息</div>
                    <StatusTag :status="currentWork.status" />
                  </div>
                  <div class="card-body">
                    <div class="wo-head">
                      <div>
                        <div class="wo-no mono">{{ currentWork.workNo }}</div>
                        <div class="wo-product">{{ currentWork.productName }}</div>
                        <div class="muted wo-sub">
                          {{ currentWork.productCode }} · {{ currentWork.spec || '无规格' }} ·
                          {{ currentWork.orderNo || '手动建单' }}
                        </div>
                      </div>
                      <div class="wo-plan">
                        <div class="plan-value num">{{ fmtQty(currentWork.planQty) }}</div>
                        <div class="muted">{{ currentWork.unit || '计划数量' }}</div>
                      </div>
                    </div>

                    <div class="wo-stats">
                      <div class="wo-stat">
                        <span>已完成</span>
                        <b class="num">{{ fmtQty(currentWork.doneQty) }}</b>
                      </div>
                      <div class="wo-stat">
                        <span>合格数量</span>
                        <b class="num">{{ fmtQty(currentWork.qualifiedQty) }}</b>
                      </div>
                      <div class="wo-stat">
                        <span>计划完工</span>
                        <b>{{ currentWork.planEnd || '-' }}</b>
                      </div>
                    </div>

                    <div class="mini-progress wo-progress">
                      <div class="bar">
                        <span
                          :style="{
                            width:
                              Math.min(
                                100,
                                (Number(currentWork.doneQty || 0) / Number(currentWork.planQty || 1)) * 100
                              ) + '%'
                          }"
                        ></span>
                      </div>
                      <span class="pct">
                        {{
                          Math.min(
                            100,
                            Math.round(
                              (Number(currentWork.doneQty || 0) / Number(currentWork.planQty || 1)) * 100
                            )
                          )
                        }}%
                      </span>
                    </div>

                    <div class="op-list">
                      <div class="op-list-head">
                        <span>工序</span>
                        <span>计划 / 完成</span>
                        <span>状态</span>
                      </div>
                      <button
                        v-for="op in currentWork.operations || []"
                        :key="op.id"
                        type="button"
                        class="op-row"
                        :class="{ active: reportForm.operationId === op.id }"
                        @click="selectOperation(op)"
                      >
                        <span class="op-name">
                          <b>{{ op.seq }}</b>{{ op.name }}
                          <i v-if="op.inspectionMode === 'ATTACHED'" class="op-flag">检</i>
                        </span>
                        <span class="mono">{{ fmtQty(op.planQty) }} / {{ fmtQty(op.doneQty) }}</span>
                        <StatusTag :status="op.status" />
                      </button>
                    </div>
                  </div>
                </div>

                <OperationReportPanel
                  :operations="currentWork.operations || []"
                  :form="reportForm"
                  :operation="selectedOperation"
                  :docs="selectedDocs"
                  :qualified="reportQualified"
                  :need-inspect="needInspect"
                  :can-report="canReport"
                  :submitting="submittingReport"
                  :unit="currentWork.unit || ''"
                  @operation-change="onOperationChange"
                  @submit="submitReport"
                />
              </div>
            </template>
            <div v-else class="panel empty-panel">
              <el-icon :size="36"><FullScreen /></el-icon>
              <p>等待扫工单码</p>
              <span>扫描 MES|WO|工单号，或在左侧手动输入工单号后点「识别」</span>
              <div class="empty-formats">
                <span class="mono">MES|WO|工单号</span>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="扫工序码" name="OP" lazy>
            <p class="tab-intro">
              扫工序码 MES|OP|工单号|工序序号：识别后自动定位到该工序并直接报工。
            </p>
            <template v-if="currentWork">
              <div class="result-grid">
                <div class="panel">
                  <div class="card-head">
                    <div class="card-title">工序定位</div>
                    <StatusTag :status="currentWork.status" />
                  </div>
                  <div class="card-body">
                    <div class="wo-head">
                      <div>
                        <div class="wo-no mono">{{ currentWork.workNo }}</div>
                        <div class="wo-product">{{ currentWork.productName }}</div>
                        <div class="muted wo-sub">
                          {{ currentWork.productCode }} · {{ currentWork.spec || '无规格' }} · 计划
                          {{ fmtQty(currentWork.planQty) }} {{ currentWork.unit || '' }}
                        </div>
                      </div>
                      <div class="wo-plan">
                        <div class="plan-value num">
                          {{ selectedOperation ? selectedOperation.seq : '-' }}
                        </div>
                        <div class="muted">定位工序序号</div>
                      </div>
                    </div>
                    <div class="op-list">
                      <div class="op-list-head">
                        <span>工序</span>
                        <span>计划 / 完成</span>
                        <span>状态</span>
                      </div>
                      <button
                        v-for="op in currentWork.operations || []"
                        :key="op.id"
                        type="button"
                        class="op-row"
                        :class="{ active: reportForm.operationId === op.id }"
                        @click="selectOperation(op)"
                      >
                        <span class="op-name">
                          <b>{{ op.seq }}</b>{{ op.name }}
                          <i v-if="op.inspectionMode === 'ATTACHED'" class="op-flag">检</i>
                        </span>
                        <span class="mono">{{ fmtQty(op.planQty) }} / {{ fmtQty(op.doneQty) }}</span>
                        <StatusTag :status="op.status" />
                      </button>
                    </div>
                  </div>
                </div>

                <OperationReportPanel
                  :operations="currentWork.operations || []"
                  :form="reportForm"
                  :operation="selectedOperation"
                  :docs="selectedDocs"
                  :qualified="reportQualified"
                  :need-inspect="needInspect"
                  :can-report="canReport"
                  :submitting="submittingReport"
                  :unit="currentWork.unit || ''"
                  @operation-change="onOperationChange"
                  @submit="submitReport"
                />
              </div>
            </template>
            <div v-else class="panel empty-panel">
              <el-icon :size="36"><FullScreen /></el-icon>
              <p>等待扫工序码</p>
              <span>扫描 MES|OP|工单号|工序序号，或在左侧手动输入后点「识别」</span>
              <div class="empty-formats">
                <span class="mono">MES|OP|工单号|工序序号</span>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="扫件码" name="SN" lazy>
            <p class="tab-intro">
              扫件码 MES|SN|件码（或 工单号-001）：对单件开工、完工、判不合格或报废。
            </p>
            <template v-if="currentUnit">
              <div class="result-grid">
                <div class="panel">
                  <div class="card-head">
                    <div class="card-title">件码信息</div>
                    <span class="status-tag" :class="unitStatusCls(currentUnit.status)">
                      <i></i>{{ unitStatusLabel(currentUnit.status) }}
                    </span>
                  </div>
                  <div class="card-body">
                    <div class="wo-head">
                      <div>
                        <div class="wo-no mono">{{ currentUnit.unitNo }}</div>
                        <div class="wo-product">{{ currentUnit.productName }}</div>
                        <div class="muted wo-sub">
                          {{ currentUnit.productCode }} · 工单 {{ currentUnit.workNo }} · 第
                          {{ currentUnit.seqNo }} 件
                        </div>
                      </div>
                      <div class="wo-plan">
                        <div class="plan-value num">{{ currentUnit.seqNo }}</div>
                        <div class="muted">件序号</div>
                      </div>
                    </div>

                    <div class="unit-flow">
                      <div class="unit-flow-item">
                        <span>当前工序</span>
                        <b v-if="currentUnit.currentOperation">
                          {{ currentUnit.currentOperation.seq }}. {{ currentUnit.currentOperation.name }}
                        </b>
                        <b v-else class="muted-text">
                          {{ currentUnit.status === 'FINISHED' ? '已走完全部工序' : '待投产' }}
                        </b>
                        <small v-if="currentUnit.currentOperation" class="mono">
                          完成 {{ fmtQty(currentUnit.currentOperation.doneQty) }} / 计划
                          {{ fmtQty(currentUnit.currentOperation.planQty) }}
                        </small>
                      </div>
                      <div class="unit-flow-arrow">
                        <el-icon><Right /></el-icon>
                      </div>
                      <div class="unit-flow-item">
                        <span>下一道工序</span>
                        <b v-if="currentUnit.nextOperation">
                          {{ currentUnit.nextOperation.seq }}. {{ currentUnit.nextOperation.name }}
                        </b>
                        <b v-else class="muted-text">本件末道工序</b>
                        <small v-if="currentUnit.nextOperation" class="mono">
                          待做 {{ fmtQty(currentUnit.nextOperation.planQty) }}
                        </small>
                      </div>
                    </div>

                    <div class="unit-logs">
                      <div class="op-list-head">
                        <span>流转记录</span>
                        <span>操作工</span>
                        <span>时间</span>
                      </div>
                      <div v-for="(log, index) in currentUnit.logs || []" :key="index" class="unit-log-row">
                        <span class="unit-log-action">
                          <i class="kind" :class="String(log.action).toLowerCase() === 'scrap' ? 'mat' : 'op'">
                            {{ unitActionLabel(log.action) }}
                          </i>
                          <b v-if="log.operationName">{{ log.operationSeq }}. {{ log.operationName }}</b>
                          <small v-if="log.remark" class="muted">{{ log.remark }}</small>
                        </span>
                        <span>{{ log.worker || '-' }}</span>
                        <span class="mono muted">{{ log.createdAt }}</span>
                      </div>
                      <div v-if="!(currentUnit.logs || []).length" class="unit-log-empty">
                        尚无流转记录，扫件码后点「开工」开始第一道工序
                      </div>
                    </div>
                  </div>
                </div>

                <div class="panel">
                  <div class="card-head">
                    <div class="card-title">单件流转</div>
                    <span class="toolbar-tip">每次操作实时回写工序完成数与报工流水</span>
                  </div>
                  <div class="card-body">
                    <el-form label-position="top">
                      <el-form-item label="操作工" required>
                        <el-input v-model="unitWorker" maxlength="40" placeholder="默认当前登录用户" />
                      </el-form-item>
                      <el-form-item label="不良原因 / 报废原因">
                        <el-input
                          v-model="unitRemark"
                          maxlength="200"
                          placeholder="判不合格或报废时必填，例如：孔位偏移"
                        />
                      </el-form-item>

                      <div class="unit-action-hint">
                        <template v-if="currentUnit.canStart">
                          该件当前为待投产，扫到工位先点「开工」；开工后才可完工。
                        </template>
                        <template v-else-if="currentUnit.canFinish">
                          该件在 {{ unitOperationText(currentUnit.currentOperation) }} 加工中，完工后自动流转到下一道。
                        </template>
                        <template v-else-if="currentUnit.status === 'FINISHED'">
                          该件已走完全部工序，无需再操作，可直接入库或发货。
                        </template>
                        <template v-else>
                          该件已报废，不能再开工或完工。
                        </template>
                      </div>

                      <div class="unit-actions">
                        <el-button
                          type="primary"
                          :loading="unitSubmitting"
                          :disabled="!currentUnit.canStart"
                          @click="submitUnitStart"
                        >
                          开工
                        </el-button>
                        <el-button
                          type="success"
                          :loading="unitSubmitting"
                          :disabled="!currentUnit.canFinish"
                          @click="submitUnitFinish(true)"
                        >
                          完工合格
                        </el-button>
                        <el-button
                          type="warning"
                          plain
                          :loading="unitSubmitting"
                          :disabled="!currentUnit.canFinish"
                          @click="submitUnitFinish(false)"
                        >
                          判不合格
                        </el-button>
                        <el-button
                          type="danger"
                          plain
                          :loading="unitSubmitting"
                          :disabled="!currentUnit.canScrap"
                          @click="submitUnitScrap"
                        >
                          报废
                        </el-button>
                        <el-button text :loading="unitLoading" @click="refreshUnit">刷新</el-button>
                      </div>

                      <div class="submit-row">
                        <span class="muted">件码唯一，重复扫码得到的是同一件的状态，不会重复计数</span>
                        <span class="mono muted">{{ currentUnit.workNo }}</span>
                      </div>
                    </el-form>
                  </div>
                </div>
              </div>
            </template>
            <div v-else class="panel empty-panel">
              <el-icon :size="36"><FullScreen /></el-icon>
              <p>等待扫件码</p>
              <span>扫描 MES|SN|件码，或在左侧手动输入件码后点「识别」</span>
              <div class="empty-formats">
                <span class="mono">MES|SN|件码</span>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>

        <section v-if="currentMaterial" class="material-section">
          <div class="section-head">
            <span class="section-title">物料码 · 领料</span>
            <span class="toolbar-tip">物料码没有单独页签，识别后固定显示在这里</span>
          </div>
          <div class="result-grid material-grid">
            <div class="panel">
              <div class="card-head">
                <div class="card-title">物料信息</div>
                <span class="status-tag primary"><i></i>物料码</span>
              </div>
              <div class="card-body">
                <div class="material-head">
                  <div class="material-icon">
                    <el-icon :size="24"><Box /></el-icon>
                  </div>
                  <div>
                    <div class="wo-product">{{ currentMaterial.name }}</div>
                    <div class="mono muted">{{ currentMaterial.code }}</div>
                  </div>
                </div>

                <div class="stock-grid">
                  <div class="stock-item">
                    <span>当前库存</span>
                    <b
                      class="num"
                      :class="
                        Number(currentMaterial.stock || 0) < Number(currentMaterial.safetyStock || 0)
                          ? 'danger-text'
                          : 'success-text'
                      "
                    >
                      {{ fmtQty(currentMaterial.stock) }}
                    </b>
                    <small>{{ currentMaterial.unit || '' }}</small>
                  </div>
                  <div class="stock-item">
                    <span>安全库存</span>
                    <b class="num">{{ fmtQty(currentMaterial.safetyStock) }}</b>
                    <small>{{ currentMaterial.unit || '' }}</small>
                  </div>
                  <div class="stock-item">
                    <span>规格</span>
                    <b>{{ currentMaterial.spec || '-' }}</b>
                  </div>
                </div>

                <div
                  class="stock-hint"
                  :class="{
                    warn: Number(currentMaterial.stock || 0) < Number(currentMaterial.safetyStock || 0)
                  }"
                >
                  <template
                    v-if="Number(currentMaterial.stock || 0) < Number(currentMaterial.safetyStock || 0)"
                  >
                    库存低于安全库存，领料前请确认补货计划。
                  </template>
                  <template v-else>库存充足，可按 BOM 需求领料。</template>
                </div>
              </div>
            </div>

            <div class="panel">
              <div class="card-head">
                <div class="card-title">按物料领料</div>
                <span class="toolbar-tip">仅已下达 / 生产中工单</span>
              </div>
              <div class="card-body">
                <el-form label-position="top">
                  <el-form-item label="领料工单" required>
                    <ReferenceSelect
                      v-model="issueWorkOrderId"
                      url="/work-orders/options"
                      :params="{ status: 'RELEASED,IN_PROGRESS' }"
                      :initial-label="issueWorkOrderLabel"
                      code-field="workNo"
                      label-field="productName"
                      placeholder="输入工单号或产品搜索（已下达/生产中）"
                      @select="(work) => {
                        issueWorkOrderLabel = work ? `${work.workNo} · ${work.productName}` : ''
                        loadIssueDetail()
                      }"
                    />
                  </el-form-item>

                  <div v-loading="issueDetailLoading" class="bom-line">
                    <template v-if="issueBomLine">
                      <div class="bom-item">
                        <span>BOM 需求</span>
                        <b class="num">{{ fmtQty(issueBomLine.needQty) }}</b>
                      </div>
                      <div class="bom-item">
                        <span>已领数量</span>
                        <b class="num">{{ fmtQty(issueBomLine.issuedQty) }}</b>
                      </div>
                      <div class="bom-item">
                        <span>已退数量</span>
                        <b class="num">{{ fmtQty(issueBomLine.returnedQty) }}</b>
                      </div>
                      <div class="bom-item">
                        <span>剩余可领</span>
                        <b class="num success-text">{{ fmtQty(issueRemaining) }}</b>
                      </div>
                    </template>
                    <div v-else-if="issueWorkDetail" class="bom-warn">
                      该物料不在工单 BOM 中，提交将被后端拒绝
                    </div>
                    <div v-else class="muted bom-empty">选择工单后显示该物料的 BOM 需求与可领数量</div>
                  </div>

                  <el-form-item label="领料数量" required>
                    <el-input-number
                      v-model="issueQty"
                      :min="0.01"
                      :precision="2"
                      :max="issueBomLine ? issueRemaining : undefined"
                      style="width: 100%"
                    />
                  </el-form-item>

                  <el-form-item label="领料仓库" required>
                    <ReferenceSelect v-model="issueWarehouseId" url="/options/warehouses"
                                     :initial-label="issueWarehouseLabel"
                                     placeholder="输入仓库编码或名称搜索" />
                  </el-form-item>

                  <el-form-item label="领料人" required>
                    <el-input v-model="issueIssuer" maxlength="40" placeholder="填写实际领料人" />
                  </el-form-item>

                  <div class="submit-row">
                    <span class="muted">提交后按批次从所选仓库扣减，并生成一张领料单</span>
                    <el-button
                      type="primary"
                      :loading="submittingIssue"
                      :disabled="!issueWorkOrderId || (!!issueBomLine && issueRemaining <= 0)"
                      @click="submitIssue"
                    >
                      提交领料
                    </el-button>
                  </div>
                </el-form>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scan-page {
  max-width: 1440px;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.scan-layout {
  display: grid;
  grid-template-columns: 420px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.left-col,
.right-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.camera-stage {
  position: relative;
  aspect-ratio: 16 / 10;
  border-radius: 9px;
  overflow: hidden;
  background: #0b1220;
  border: 1px solid #1d2940;
}

.camera-stage video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.hidden-canvas {
  display: none;
}

.camera-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px;
  text-align: center;
  color: #93a4c0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.04) 1px, transparent 1px);
  background-size: 34px 34px;
}

.camera-mask p {
  margin: 2px 0 0;
  color: #d6e2f5;
  font-size: 14px;
  font-weight: 600;
}

.camera-mask span {
  font-size: 11px;
  line-height: 1.7;
  max-width: 280px;
}

.scan-frame {
  position: absolute;
  inset: 16% 18%;
  border-radius: 8px;
  box-shadow: 0 0 0 999px rgba(4, 9, 18, 0.45);
}

.corner {
  position: absolute;
  width: 22px;
  height: 22px;
  border: 2px solid #6d9bff;
}

.corner.tl {
  top: -2px;
  left: -2px;
  border-right: none;
  border-bottom: none;
  border-radius: 6px 0 0 0;
}

.corner.tr {
  top: -2px;
  right: -2px;
  border-left: none;
  border-bottom: none;
  border-radius: 0 6px 0 0;
}

.corner.bl {
  bottom: -2px;
  left: -2px;
  border-right: none;
  border-top: none;
  border-radius: 0 0 0 6px;
}

.corner.br {
  bottom: -2px;
  right: -2px;
  border-left: none;
  border-top: none;
  border-radius: 0 0 6px 0;
}

.laser {
  position: absolute;
  left: 19%;
  right: 19%;
  height: 2px;
  background: linear-gradient(90deg, transparent, #60a5fa, transparent);
  box-shadow: 0 0 12px rgba(96, 165, 250, 0.9);
  animation: laser-scan 2.1s ease-in-out infinite;
}

@keyframes laser-scan {
  0%,
  100% {
    top: 18%;
  }
  50% {
    top: 80%;
  }
}

.live-badge {
  position: absolute;
  top: 10px;
  left: 10px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  border-radius: 20px;
  background: rgba(11, 18, 32, 0.68);
  color: #e2e8f0;
  font-size: 10px;
  letter-spacing: 1px;
}

.live-badge span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #f04438;
  animation: blink 1.2s infinite;
}

@keyframes blink {
  50% {
    opacity: 0.25;
  }
}

.manual-row {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.manual-row .el-input {
  flex: 1;
}

.scanner-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 11px;
}

.scanner-label {
  color: var(--ink);
  font-weight: 600;
}

.scanner-hint {
  flex: 1;
  min-width: 0;
  line-height: 1.5;
}

.format-hint {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-top: 8px;
  font-size: 11px;
  color: var(--ink-2);
}

.format-hint span {
  padding: 1px 6px;
  border-radius: 4px;
  background: #f8fafc;
  border: 1px solid var(--line);
}

.format-hint .muted {
  border: none;
  background: transparent;
  padding: 1px 0;
}

.last-scan {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--primary-soft);
  border: 1px solid #d1e0ff;
  font-size: 12px;
  min-width: 0;
}

.last-scan b {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.last-scan .mono {
  margin-left: auto;
  color: var(--muted);
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 130px;
}

.kind {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.4px;
  color: #1d4ed8;
  background: #eff4ff;
  border: 1px solid #d1e0ff;
}

.kind.op {
  color: #b54708;
  background: #fffaeb;
  border-color: #fedf89;
}

.kind.mat {
  color: #027a48;
  background: #ecfdf3;
  border-color: #abefc6;
}

.kind.sn {
  color: #5b21b6;
  background: #f4f3ff;
  border-color: #d9d6fe;
}

/* ---------- 单件（SN）流转 ---------- */
.unit-flow {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  margin-top: 16px;
}

.unit-flow-item {
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid var(--line);
  min-width: 0;
}

.unit-flow-item > span {
  display: block;
  font-size: 11px;
  color: var(--muted);
  margin-bottom: 4px;
}

.unit-flow-item b {
  display: block;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.unit-flow-item b.muted-text {
  color: var(--muted);
  font-weight: 500;
}

.unit-flow-item small {
  display: block;
  margin-top: 3px;
  font-size: 11px;
  color: var(--muted);
}

.unit-flow-arrow {
  color: var(--primary);
  display: grid;
  place-items: center;
}

.unit-logs {
  margin-top: 14px;
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
  max-height: 260px;
  overflow-y: auto;
}

.unit-logs .op-list-head {
  grid-template-columns: minmax(0, 1fr) auto 82px;
  position: sticky;
  top: 0;
  z-index: 1;
}

.unit-log-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 82px;
  gap: 12px;
  align-items: center;
  padding: 8px 10px;
  border-bottom: 1px solid #f2f4f7;
  font-size: 12px;
}

.unit-log-row:last-child {
  border-bottom: none;
}

.unit-log-action {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.unit-log-action .kind {
  font-style: normal;
}

.unit-log-action b {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.unit-log-action small {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.unit-log-empty {
  padding: 22px 16px;
  text-align: center;
  font-size: 12px;
  color: var(--muted);
}

.unit-action-hint {
  margin-bottom: 12px;
  padding: 9px 11px;
  border-radius: 6px;
  background: var(--primary-soft);
  border: 1px solid #d1e0ff;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-2);
}

.unit-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 4px;
}

.unit-actions .el-button + .el-button {
  margin-left: 0;
}

.recent-list {
  padding: 6px;
  max-height: 330px;
  overflow-y: auto;
}

.recent-list::-webkit-scrollbar {
  width: 5px;
}

.recent-list::-webkit-scrollbar-thumb {
  background: var(--line-strong);
  border-radius: 4px;
}

.recent-item {
  width: 100%;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 8px 9px;
  border: none;
  border-radius: 7px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: 0.15s;
  color: var(--ink);
}

.recent-item:hover {
  background: #f7f9fc;
}

.recent-main {
  min-width: 0;
}

.recent-main b {
  display: block;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.recent-main small {
  display: block;
  margin-top: 2px;
  font-size: 10px;
  color: var(--muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.recent-time {
  font-size: 10px;
  color: var(--muted);
}

.recent-empty {
  padding: 26px 18px;
  text-align: center;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.8;
}

.result-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.wo-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.wo-no {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.4px;
}

.wo-product {
  margin-top: 5px;
  font-size: 14px;
  font-weight: 600;
}

.wo-sub {
  margin-top: 3px;
  font-size: 11px;
}

.wo-plan {
  text-align: right;
  flex: 0 0 auto;
}

.plan-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1;
  color: var(--primary);
}

.wo-plan .muted {
  font-size: 11px;
  margin-top: 4px;
}

.wo-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 14px;
}

.wo-stat {
  padding: 9px 10px;
  border-radius: 7px;
  background: #f8fafc;
  border: 1px solid var(--line);
}

.wo-stat span {
  display: block;
  font-size: 11px;
  color: var(--muted);
  margin-bottom: 4px;
}

.wo-stat b {
  font-size: 14px;
}

.wo-progress {
  margin-top: 14px;
}

.op-list {
  margin-top: 14px;
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}

.op-list-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 12px;
  align-items: center;
  padding: 7px 10px;
  background: #f8fafc;
  font-size: 11px;
  color: var(--muted);
  border-bottom: 1px solid var(--line);
}

.op-row {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 12px;
  align-items: center;
  padding: 8px 10px;
  border: none;
  border-bottom: 1px solid #f2f4f7;
  background: #fff;
  text-align: left;
  cursor: pointer;
  font-size: 12px;
  color: var(--ink);
  transition: 0.15s;
}

.op-row:last-child {
  border-bottom: none;
}

.op-row:hover {
  background: #f7f9fc;
}

.op-row.active {
  background: var(--primary-soft);
  box-shadow: inset 3px 0 0 var(--primary);
}

.op-name {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-weight: 500;
}

.op-name b {
  width: 18px;
  height: 18px;
  border-radius: 4px;
  display: inline-grid;
  place-items: center;
  background: #eef2f6;
  color: var(--ink-2);
  font-size: 10px;
}

.op-row.active .op-name b {
  background: var(--primary);
  color: #fff;
}

.op-flag {
  display: inline-block;
  margin-left: 6px;
  padding: 0 5px;
  font-style: normal;
  font-size: 10px;
  line-height: 15px;
  border-radius: 4px;
  color: #b54708;
  background: #fef0c7;
  vertical-align: 1px;
}

.submit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
  font-size: 11px;
}

.material-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.material-icon {
  width: 46px;
  height: 46px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid #d1e0ff;
}

.stock-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.stock-item {
  padding: 10px;
  border-radius: 7px;
  border: 1px solid var(--line);
  background: #f8fafc;
}

.stock-item span {
  display: block;
  font-size: 11px;
  color: var(--muted);
  margin-bottom: 4px;
}

.stock-item b {
  font-size: 16px;
}

.stock-item small {
  font-size: 10px;
  color: var(--muted);
  margin-left: 3px;
}

.stock-hint {
  margin-top: 14px;
  padding: 9px 11px;
  border-radius: 6px;
  font-size: 12px;
  color: #027a48;
  background: #ecfdf3;
  border: 1px solid #abefc6;
}

.stock-hint.warn {
  color: #b54708;
  background: #fffaeb;
  border-color: #fedf89;
}

.bom-line {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  padding: 10px;
  margin-bottom: 14px;
  border-radius: 7px;
  background: #f8fafc;
  border: 1px dashed var(--line-strong);
  min-height: 74px;
}

.bom-item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 3px 6px;
  font-size: 12px;
}

.bom-item span {
  color: var(--muted);
}

.bom-warn {
  grid-column: span 2;
  color: #b54708;
  font-size: 12px;
  line-height: 1.7;
  padding: 6px;
}

.bom-empty {
  grid-column: span 2;
  font-size: 12px;
  line-height: 1.7;
  padding: 6px;
}

.empty-panel {
  min-height: 440px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 30px;
  text-align: center;
  color: var(--muted);
  border-style: dashed;
}

.empty-panel p {
  margin: 2px 0 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-2);
}

.empty-panel > span {
  font-size: 12px;
  line-height: 1.8;
  max-width: 380px;
}

.empty-formats {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 8px;
}

.empty-formats span {
  padding: 2px 8px;
  border-radius: 4px;
  background: #f8fafc;
  border: 1px solid var(--line);
  font-size: 11px;
  color: var(--ink-2);
}

@media (max-width: 1280px) {
  .scan-layout {
    grid-template-columns: 360px minmax(0, 1fr);
  }
}

@media (max-width: 1120px) {
  .scan-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .camera-stage {
    aspect-ratio: 16 / 9;
  }
}

@media (max-width: 1400px) {
  .result-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* ---------- 扫码类型页签 ---------- */
.scan-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.scan-tabs :deep(.el-tabs__item) {
  height: 40px;
  font-size: 13px;
  font-weight: 600;
}

.scan-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--line);
}

.scan-tabs :deep(.el-tabs__content) {
  overflow: visible;
}

.tab-intro {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.material-section {
  margin-top: 0;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-2);
}

/* ---------- 窄屏（平板 / 手机）适配 ---------- */
@media (max-width: 900px) {
  .scan-page {
    max-width: none;
  }

  .page-head {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
    margin-bottom: 12px;
  }

  /* 帮助说明在窄屏折叠成两行，把首屏留给扫码 */
  .page-sub {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  .head-actions {
    flex-wrap: wrap;
  }

  .head-actions .el-button {
    width: 100%;
  }

  .scan-layout {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
  }

  .camera-stage {
    aspect-ratio: 4 / 3;
  }

  /* 输入框、按钮整行铺满，按钮最小高度 44px 便于戴手套点按 */
  .manual-row {
    flex-direction: column;
  }

  .manual-row .el-input,
  .manual-row .el-button {
    width: 100%;
  }

  /* 纵向排列后取消 flex: 1，避免 flex-basis: 0 把输入框高度压扁 */
  .manual-row .el-input {
    flex: none;
  }

  .scan-page :deep(.el-button) {
    min-height: 44px;
  }

  .right-col :deep(.el-input),
  .right-col :deep(.el-select),
  .right-col :deep(.el-input-number) {
    width: 100%;
  }

  .right-col :deep(.el-input__wrapper),
  .right-col :deep(.el-select__wrapper) {
    min-height: 40px;
  }

  /* 横向多列布局统一收敛为单列 */
  .result-grid,
  .form-grid,
  .wo-stats,
  .stock-grid,
  .bom-line,
  .unit-flow {
    grid-template-columns: minmax(0, 1fr);
  }

  .form-grid .full,
  .bom-warn,
  .bom-empty {
    grid-column: span 1;
  }

  .unit-flow-arrow {
    transform: rotate(90deg);
  }

  .wo-head {
    flex-direction: column;
    gap: 8px;
  }

  .wo-plan {
    text-align: left;
  }

  .submit-row {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
  }

  .submit-row .el-button,
  .unit-actions .el-button {
    width: 100%;
    margin-left: 0;
  }

  /* 折叠非关键信息，减少窄屏滚动距离 */
  .format-hint,
  .scanner-hint,
  .toolbar-tip,
  .tab-intro,
  .wo-sub,
  .submit-row .muted,
  .empty-formats,
  .last-scan .mono {
    display: none;
  }

  /* 最近识别在窄屏收起：最近一次结果已由扫码通道下方的 last-scan 条承担，
     收起后页签结果紧跟扫码框，减少上下翻找 */
  .recent-panel {
    display: none;
  }

  /* 工序 / 流转记录是表格型布局：允许横向滚动，字号不小于 13px */
  .op-list,
  .unit-logs {
    overflow-x: auto;
  }

  .op-list-head,
  .unit-log-row,
  .unit-logs .op-list-head {
    font-size: 13px;
  }

  .op-list-head,
  .op-row {
    min-width: 400px;
  }

  .unit-logs .op-list-head,
  .unit-log-row {
    min-width: 400px;
  }

  .unit-logs {
    max-height: none;
  }

  .unit-actions {
    flex-direction: column;
  }

  .scan-tabs :deep(.el-tabs__item) {
    height: 44px;
    padding: 0 12px;
    font-size: 14px;
  }

  .empty-panel {
    min-height: 240px;
    padding: 22px 16px;
  }
}
</style>
