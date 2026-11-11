/**
 * 通用列表导出。
 * 列定义直接取当前表格视图：列顺序、显隐与页面上看到的一致，操作列不导出。
 * 值按「人看的样子」输出：布尔转是否、状态码转中文、日期与金额保持原样，
 * 空值留空而不是写 null，方便 Excel 里直接透视。
 */

export interface ExportColumn {
  key: string
  label: string
  prop: string
}

/** 业务状态码 → 中文，导出时比原值好读 */
const VALUE_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  RELEASED: '已下达',
  SCHEDULED: '已排产',
  IN_PROGRESS: '生产中',
  COMPLETED: '已完成',
  FINISHED: '已完工',
  CLOSED: '已关闭',
  CANCELLED: '已取消',
  PENDING: '待处理',
  PENDING_QC: '待检',
  OPEN: '待处理',
  RESOLVED: '已处理',
  QUOTED: '已报价',
  SELECTED: '已选定',
  MATCHED: '已匹配',
  PARTIAL: '部分完成',
  PAID: '已付清',
  SHIPPED: '已发货',
  SIGNED: '已签收',
  READY_TO_SHIP: '待发货',
  PASS: '合格',
  FAIL: '不合格',
  NORMAL: '普通',
  URGENT: '加急',
  CRITICAL: '特急',
  RAW: '原料',
  SEMI: '半成品',
  FINISHED_GOODS: '成品',
  ISSUE: '生产领料',
  RETURN_MAT: '生产退料',
  RETURN: '退货',
  IN: '入库',
  OUT: '出库',
  ADJUST: '盘点调整',
  MANUAL: '手工',
  PO: '采购',
  SUCCESS: '成功',
  FAILED: '失败',
  SKIPPED: '已跳过',
  SCHEDULED_JOB: '定时触发',
  TASK: '待办',
  ALERT: '预警',
  INFO: '通知'
}

/** 状态类字段才做码值翻译，避免把产品编码之类的值误伤 */
const LABEL_KEYS = new Set([
  'status', 'type', 'result', 'category', 'priority', 'source', 'txnType',
  'triggerType', 'workType', 'level', 'disposal', 'storageType'
])

/** 当前视图里可见、按视图顺序排好的列 */
export const exportColumns = (table: any): ExportColumn[] => {
  const defs = table?.orderedDefs?.value ?? table?.defs ?? []
  return defs
    .filter((def: any) => table?.show ? table.show(def.key) : true)
    .filter((def: any) => def.key !== 'actions')
    .map((def: any) => ({
      key: def.key,
      label: def.label,
      prop: def.prop === undefined ? def.key : def.prop
    }))
    .filter((column: ExportColumn) => Boolean(column.prop))
}

const cellText = (value: any, key: string): string => {
  if (value === null || value === undefined) return ''
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (Array.isArray(value)) return value.map((item) => cellText(item, key)).join('、')
  if (typeof value === 'object') return JSON.stringify(value)
  const text = String(value)
  if (LABEL_KEYS.has(key) && VALUE_LABELS[text]) {
    return VALUE_LABELS[text]
  }
  return text
}

const csvCell = (text: string) => (/[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text)

/** 组装并下载 CSV，返回导出行数 */
export const downloadCsv = (
  table: any,
  rows: any[],
  filename: string,
  columns?: ExportColumn[]
): number => {
  const cols = columns && columns.length ? columns : exportColumns(table)
  if (!cols.length || !rows.length) return 0
  const header = cols.map((column) => csvCell(column.label)).join(',')
  const lines = rows.map((row) =>
    cols.map((column) => csvCell(cellText(row?.[column.prop], column.key))).join(',')
  )
  // BOM 让 Excel 直接按 UTF-8 打开，中文不乱码
  const csv = '\ufeff' + [header, ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename.endsWith('.csv') ? filename : `${filename}.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
  return rows.length
}

/** 文件名里的非法字符替换掉，避免某些浏览器拒绝保存 */
export const safeFileName = (name: string) =>
  name.replace(/[\\/:*?"<>|]/g, '_').trim() || '导出数据'

/** 带日期的默认文件名，例如「工单列表_20261023.csv」 */
export const datedFileName = (name: string) => {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${safeFileName(name)}_${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
}
