import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('./views/Login.vue'), meta: { public: true, title: '登录' } },
  // 标签打印必须脱离主框架：主框架内容是内部滚动的，打印时只会输出可视区，标签会打成空白
  {
    path: '/print/labels',
    component: () => import('./views/production/WorkOrderLabels.vue'),
    meta: { title: '标签打印' }
  },
  // 车间大屏：不套后台框架、不要求登录（数据走 /api/public/screen/**）
  {
    path: '/screen',
    component: () => import('./views/screen/ScreenWall.vue'),
    meta: { public: true, title: '车间大屏' }
  },
  // 经营 BI 大屏：含销售额、库存等经营数据，保留登录态，不套后台框架
  {
    path: '/screen/bi',
    component: () => import('./views/screen/BiScreen.vue'),
    meta: { title: '经营驾驶舱' }
  },
  {
    path: '/',
    component: () => import('./layout/AppLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: () => import('./views/Dashboard.vue'), meta: { title: '工作台' } },
      { path: 'overview/:category', component: () => import('./views/CategoryOverview.vue'), meta: { title: '分组工作台' } },
      { path: 'notices', component: () => import('./views/NoticeCenter.vue'), meta: { title: '消息中心' } },
      { path: 'tasks', component: () => import('./views/Tasks.vue'), meta: { title: '我的任务' } },
      { path: 'sales', component: () => import('./views/SalesOrders.vue'), meta: { title: '销售订单' } },
      { path: 'sales/quotes', component: () => import('./views/SalesQuotes.vue'), meta: { title: '报价管理' } },
      { path: 'production', component: () => import('./views/WorkOrders.vue'), meta: { title: '生产工单' } },
      { path: 'production/units', component: () => import('./views/production/UnitTrace.vue'), meta: { title: '单件追踪' } },
      { path: 'production/outsource', component: () => import('./views/outsource/OutsourceOrders.vue'), meta: { title: '外协管理' } },
      { path: 'purchase/requisitions', component: () => import('./views/PurchaseRequisitions.vue'), meta: { title: '采购申请' } },
      { path: 'purchase/mrp', component: () => import('./views/purchase/Mrp.vue'), meta: { title: '物料需求' } },
      { path: 'purchase/rfqs', component: () => import('./views/PurchaseRfqs.vue'), meta: { title: '询价比价' } },
      { path: 'purchase/suppliers', component: () => import('./views/Suppliers.vue'), meta: { title: '供应商档案' } },
      { path: 'purchase/orders', component: () => import('./views/PurchaseOrders.vue'), meta: { title: '采购订单' } },
      { path: 'purchase/payables', component: () => import('./views/Payables.vue'), meta: { title: '发票与应付' } },
      { path: 'finance/receivables', component: () => import('./views/finance/Receivables.vue'), meta: { title: '应收管理' } },
      { path: 'finance/ledger', component: () => import('./views/finance/Ledger.vue'), meta: { title: '总账凭证' } },
      { path: 'finance/fixed-assets', component: () => import('./views/finance/FixedAssets.vue'), meta: { title: '固定资产' } },
      { path: 'finance/tax', component: () => import('./views/finance/TaxFilings.vue'), meta: { title: '税务申报' } },
      { path: 'finance/tax-prepayments', component: () => import('./views/finance/TaxPrepayments.vue'), meta: { title: '季度预缴' } },
      { path: 'finance/income-tax', component: () => import('./views/finance/IncomeTax.vue'), meta: { title: '企业所得税' } },
      { path: 'finance/rd-projects', component: () => import('./views/finance/RdProjects.vue'), meta: { title: '研发辅助账' } },
      { path: 'purchase/returns', component: () => import('./views/PurchaseReturns.vue'), meta: { title: '采购退货' } },
      { path: 'planning/schedule', component: () => import('./views/planning/SchedulePlan.vue'), meta: { title: '排产计划' } },
      { path: 'planning/bom-tasks', component: () => import('./views/planning/BomTasks.vue'), meta: { title: 'BOM 任务' } },
      { path: 'planning/aps', component: () => import('./views/planning/ApsGantt.vue'), meta: { title: '排产甘特与插单模拟' } },
      { path: 'workshop', component: () => import('./views/workshop/WorkshopBoard.vue'), meta: { title: '车间看板' } },
      { path: 'workshop/andon', component: () => import('./views/workshop/AndonBoard.vue'), meta: { title: '异常安灯' } },
      { path: 'shopfloor/scan', component: () => import('./views/shopfloor/ScanWorkstation.vue'), meta: { title: '车间扫码' } },
      { path: 'reports/production', component: () => import('./views/reports/ProductionReport.vue'), meta: { title: '生产报表' } },
      { path: 'reports/cost', component: () => import('./views/reports/CostReport.vue'), meta: { title: '成本核算' } },
      { path: 'reports/performance', component: () => import('./views/reports/Performance.vue'), meta: { title: '绩效核算' } },
      { path: 'reports/month-close', component: () => import('./views/reports/MonthClose.vue'), meta: { title: '月结报表' } },
      { path: 'purchase/performance', component: () => import('./views/purchase/SupplierPerformance.vue'), meta: { title: '供应商绩效' } },
      { path: 'purchase/mrp-multi', redirect: { path: '/purchase/mrp', query: { tab: 'multi' } } },
      { path: 'shipments', component: () => import('./views/Shipments.vue'), meta: { title: '发货管理' } },
      { path: 'inventory', component: () => import('./views/Inventory.vue'), meta: { title: '库存管理' } },
      { path: 'trace', component: () => import('./views/Trace.vue'), meta: { title: '批次追溯' } },
      { path: 'warehouse/inbound', component: () => import('./views/warehouse/InboundOrders.vue'), meta: { title: '入库管理' } },
      { path: 'warehouse/outbound', component: () => import('./views/warehouse/OutboundOrders.vue'), meta: { title: '出库管理' } },
      { path: 'warehouse/transfers', component: () => import('./views/warehouse/Transfers.vue'), meta: { title: '库存调拨' } },
      { path: 'warehouse/stocktakes', component: () => import('./views/warehouse/Stocktakes.vue'), meta: { title: '库存盘点' } },
      { path: 'warehouse/locations', component: () => import('./views/warehouse/Locations.vue'), meta: { title: '库位管理' } },
      { path: 'quality', component: () => import('./views/Inspections.vue'), meta: { title: '质量检验' } },
      { path: 'quality/defects', component: () => import('./views/quality/Defects.vue'), meta: { title: '不良处置' } },
      { path: 'device/maintenance', component: () => import('./views/device/Maintenance.vue'), meta: { title: '设备点检保养' } },
      { path: 'device/oee', component: () => import('./views/device/Oee.vue'), meta: { title: '设备 OEE' } },
      { path: 'quality/spc', component: () => import('./views/quality/Spc.vue'), meta: { title: '过程能力 SPC' } },
      { path: 'returns', component: () => import('./views/Returns.vue'), meta: { title: '退货管理' } },
      { path: 'customers', component: () => import('./views/Customers.vue'), meta: { title: '客户管理' } },
      { path: 'products', component: () => import('./views/Products.vue'), meta: { title: '物料主数据' } },
      { path: 'boms', component: () => import('./views/Boms.vue'), meta: { title: 'BOM 管理' } },
      { path: 'processes', component: () => import('./views/Processes.vue'), meta: { title: '工序管理' } },
      { path: 'warehouses', component: () => import('./views/Warehouses.vue'), meta: { title: '仓库管理' } },
      { path: 'system/orgs', component: () => import('./views/system/Orgs.vue'), meta: { title: '组织架构' } },
      { path: 'system/users', component: () => import('./views/system/Users.vue'), meta: { title: '用户管理' } },
      { path: 'system/roles', component: () => import('./views/system/Roles.vue'), meta: { title: '角色权限' } },
      { path: 'system/code-rules', component: () => import('./views/system/CodeRules.vue'), meta: { title: '编码规则' } },
      { path: 'system/custom-fields', component: () => import('./views/system/CustomFields.vue'), meta: { title: '自定义字段' } },
      { path: 'system/config', component: () => import('./views/system/Config.vue'), meta: { title: '系统配置' } },
      { path: 'system/files', component: () => import('./views/system/Files.vue'), meta: { title: '文件台账' } },
      { path: 'system/logs', component: () => import('./views/system/OperLogs.vue'), meta: { title: '操作日志' } },
      { path: 'system/notices', component: () => import('./views/system/Notices.vue'), meta: { title: '通知管理' } },
      { path: 'system/data', component: () => import('./views/system/DataExchange.vue'), meta: { title: '基础数据导入' } },
      { path: 'system/jobs', component: () => import('./views/system/Jobs.vue'), meta: { title: '定时任务' } },
      { path: 'system/api-tokens', component: () => import('./views/system/ApiTokens.vue'), meta: { title: '对外接口令牌' } },
      { path: 'system/offline', component: () => import('./views/system/OfflineSync.vue'), meta: { title: '离线重传记录' } },
      { path: 'approvals', component: () => import('./views/ApprovalCenter.vue'), meta: { title: '审批中心' } },
      { path: 'approvals/flows', component: () => import('./views/ApprovalFlows.vue'), meta: { title: '流程配置' } },
      { path: 'approvals/design/:id', component: () => import('./views/ApprovalDesigner.vue'), meta: { title: '审批流设计' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('mes_token')
  if (!to.meta.public && !token) {
    return '/login'
  }
  if (to.path === '/login' && token) {
    return '/dashboard'
  }
  return true
})

export default router
