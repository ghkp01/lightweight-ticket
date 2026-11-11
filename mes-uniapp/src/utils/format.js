import { ROLE_NAMES, WAREHOUSE_TYPE_NAMES } from './config'

export const fmtQty = (value, digits = 2) => {
  const num = Number(value)
  if (!Number.isFinite(num)) return '0'
  return num.toLocaleString('zh-CN', {
    maximumFractionDigits: digits,
    minimumFractionDigits: 0
  })
}

export const fmtMoney = (value) => {
  const num = Number(value)
  if (!Number.isFinite(num)) return '0.00'
  return num.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })
}

export const fmtRate = (value) => {
  const num = Number(value)
  if (!Number.isFinite(num)) return '0'
  return `${num.toFixed(num % 1 === 0 ? 0 : 1)}%`
}

export const progressOf = (done, plan) => {
  const p = Number(plan || 0)
  if (!p) return 0
  return Math.max(0, Math.min(100, Math.round((Number(done || 0) / p) * 100)))
}

export const roleName = (role) => ROLE_NAMES[role] || role || '成员'

export const warehouseTypeName = (type) => WAREHOUSE_TYPE_NAMES[type] || type || '仓库'

export const productCategoryName = (category) =>
  PRODUCT_CATEGORY_NAMES[category] || category || '物料'

export const STATUS_MAP = {
  DRAFT: { label: '草稿', tone: 'slate' },
  RELEASED: { label: '已下达', tone: 'blue' },
  IN_PROGRESS: { label: '生产中', tone: 'orange' },
  FINISHED: { label: '已完工', tone: 'green' },
  CLOSED: { label: '已关闭', tone: 'slate' },
  PENDING_QC: { label: '待检验', tone: 'purple' },
  COMPLETED: { label: '已完成', tone: 'green' },
  CANCELLED: { label: '已取消', tone: 'slate' },
  PASS: { label: '合格', tone: 'green' },
  FAIL: { label: '不合格', tone: 'red' },
  PENDING: { label: '待处理', tone: 'orange' },
  SHIPPED: { label: '已发货', tone: 'blue' },
  SIGNED: { label: '已签收', tone: 'green' },
  READY_TO_SHIP: { label: '待发货', tone: 'orange' },
  IN_PRODUCTION: { label: '生产中', tone: 'blue' }
}

export const statusMeta = (status) => STATUS_MAP[status] || { label: status || '未知', tone: 'slate' }

export const statusText = (status) => statusMeta(status).label

export const INSPECTION_TYPES = [
  { value: 'INCOMING', label: '来料检验', short: '来料' },
  { value: 'PROCESS', label: '过程检验', short: '过程' },
  { value: 'FINAL', label: '完工检验', short: '完工' }
]

export const INBOUND_TYPES = [
  { value: 'PURCHASE', label: '采购入库' },
  { value: 'RETURN', label: '退货入库' },
  { value: 'OTHER', label: '其他入库' }
]

export const OUTBOUND_TYPES = [
  { value: 'MANUAL', label: '手工出库' },
  { value: 'SCRAP', label: '报废出库' },
  { value: 'OTHER', label: '其他出库' }
]

export const inspectionTypeLabel = (type) => {
  const found = INSPECTION_TYPES.find((item) => item.value === type)
  return found ? found.label : type || '-'
}

export const inboundTypeLabel = (type) => {
  const found = INBOUND_TYPES.find((item) => item.value === type)
  return found ? found.label : type || '-'
}

export const outboundTypeLabel = (type) => {
  const found = OUTBOUND_TYPES.find((item) => item.value === type)
  return found ? found.label : type || '-'
}

export const greeting = () => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜班辛苦了'
  if (hour < 9) return '早上好'
  if (hour < 12) return '上午好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
}

export const todayText = () => {
  const now = new Date()
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][now.getDay()]
  const pad = (n) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${week}`
}

export const shortTime = (value) => {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 16)
}

export const maskPhone = (phone) => {
  if (!phone) return '未绑定'
  const text = String(phone)
  if (text.length < 7) return text
  return `${text.slice(0, 3)}****${text.slice(-4)}`
}
