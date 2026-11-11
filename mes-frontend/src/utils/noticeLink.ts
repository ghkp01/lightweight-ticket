import type { Router } from 'vue-router'

/**
 * 站内通知的跳转映射：refType + refNo → 目标页面 + 定位关键字。
 * 后端只存「哪类单据 + 单据号」，具体去哪个页面由前端决定，
 * 新增业务只要在这里补一行，消息中心、铃铛抽屉、移动端共用同一套规则。
 */
export type NoticeTarget = {
  path: string
  query: Record<string, string>
  module: string
  hint: string
}

const MAP: Record<string, { path: string; module: string; hint: string }> = {
  WORK_ORDER: { path: '/production', module: '生产工单', hint: '打开工单详情' },
  SALES_ORDER: { path: '/sales', module: '销售订单', hint: '定位到这张订单' },
  DEFECT: { path: '/quality/defects', module: '不良处置', hint: '定位到这条不良单' },
  RETURN: { path: '/returns', module: '退货管理', hint: '定位到这张退货单' },
  ANDON: { path: '/workshop/andon', module: '异常安灯', hint: '定位到这次安灯呼叫' },
  REQUISITION: { path: '/purchase/requisitions', module: '采购申请', hint: '定位到这张申请单' },
  INBOUND: { path: '/warehouse/inbound', module: '入库管理', hint: '定位到这张入库单' },
  INSPECTION: { path: '/quality', module: '质量检验', hint: '定位到这张检验单' },
  SHIPMENT: { path: '/shipments', module: '发货管理', hint: '定位到这张发货单' },
  APPROVAL: { path: '/approvals', module: '审批中心', hint: '打开审批中心处理待办' }
}

export const noticeTarget = (item: any): NoticeTarget | null => {
  const refType = String(item?.refType || '').toUpperCase()
  const refNo = String(item?.refNo || '').trim()
  const meta = MAP[refType]
  if (!meta || !refNo) return null
  // 工单支持带上工单号直接打开详情抽屉
  if (refType === 'WORK_ORDER') {
    return { ...meta, query: { keyword: refNo, open: '1' } }
  }
  return { ...meta, query: { keyword: refNo } }
}

export const noticeModule = (item: any) => noticeTarget(item)?.module || '站内通知'

/** 点击通知：标记已读 → 跳转到能直接处理该单据的页面 */
export const openNoticeTarget = async (item: any, router: Router, markRead?: (item: any) => Promise<void> | void) => {
  const target = noticeTarget(item)
  if (markRead) {
    try {
      await markRead(item)
    } catch {
      // 已读失败不挡跳转
    }
  }
  if (!target) {
    return false
  }
  await router.push({ path: target.path, query: target.query })
  return true
}
