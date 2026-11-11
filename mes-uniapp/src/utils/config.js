/**
 * 移动端全局配置
 * 接口基址可通过 VITE_API_BASE_URL 覆盖，默认指向本机后端。
 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api'

export const REQUEST_TIMEOUT = 20000

export const STORAGE_KEYS = {
  token: 'mes_token',
  user: 'mes_user',
  scanHistory: 'mes_scan_history',
  offlineQueue: 'mes_offline_queue',
  offlineStatus: 'mes_offline_status',
  offlineFailed: 'mes_offline_failed',
  offlineLastSync: 'mes_offline_last_sync'
}

/** 允许断网排队的现场动作，其他写操作遇到断网直接报错 */
export const OFFLINE_ACTIONS = {
  report: '生产报工',
  inspection: '质量检验',
  defect: '不良处置',
  inbound: '入库申请',
  outbound: '出库申请'
}

export const DEMO_USERS = [
  { label: '管理员', username: 'admin', desc: '全模块' },
  { label: '计划', username: 'planner', desc: '工单/报工' },
  { label: '仓库', username: 'warehouse', desc: '出入库' },
  { label: '质检', username: 'qc', desc: '检验' }
]

export const DEMO_PASSWORD = '123456'

export const ROLE_NAMES = {
  ADMIN: '系统管理员',
  SALES: '销售',
  PLANNER: '计划',
  WAREHOUSE: '仓库',
  QC: '质检'
}

export const WAREHOUSE_TYPE_NAMES = {
  RAW: '原料仓',
  SEMI: '半成品仓',
  FINISHED: '成品仓',
  GENERAL: '通用仓'
}

export const PRODUCT_CATEGORY_NAMES = {
  RAW: '原材料',
  SEMI: '半成品',
  FINISHED: '成品',
  PACKAGING: '包材',
  OTHER: '其他'
}
