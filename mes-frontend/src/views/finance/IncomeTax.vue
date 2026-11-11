<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney } from '../../api'

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const yearFilter = ref<number | null>(null)
const statusFilter = ref('')

const dialog = ref(false)
const saving = ref(false)
const lossInfo = ref<any>({})
const autoPrepaid = ref(0)
const prepaidTouched = ref(false)
const form = reactive<any>({
  year: new Date().getFullYear() - 1,
  taxRatePercent: 25,
  taxRelief: 0,
  prepaidTax: 0,
  lossCarryforward: 0,
  enterpriseType: 'GENERAL',
  employeeCount: null,
  totalAssets: null,
  remark: '',
  adjustments: []
})

const branchDialog = ref(false)
const branches = ref<any[]>([])
const branchForm = reactive<any>({ id: null, name: '', taxpayerId: '', shareRatioPercent: 0, enabled: true })

const ledgerDialog = ref(false)
const ledgerRows = ref<any[]>([])
const ledgerTotal = ref(0)
const ledgerPage = ref(1)
const ledgerRule = ref('')
const ledgerFrom = ref<number | null>(null)
const ledgerTo = ref<number | null>(null)

const detailDrawer = ref(false)
const detail = ref<any>(null)

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/finance/income-tax', {
      year: yearFilter.value || undefined,
      status: statusFilter.value || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

const loadYearMeta = async (autoFillPrepaid = true) => {
  if (!form.year) {
    lossInfo.value = {}
    autoPrepaid.value = 0
    return
  }
  const [loss, prepaid] = await Promise.all([
    api.get('/finance/income-tax/loss-carryforward', { year: form.year }),
    api.get('/finance/income-tax/prepaid-tax', { year: form.year })
  ])
  lossInfo.value = loss
  autoPrepaid.value = Number(prepaid.prepaidTax || 0)
  if (autoFillPrepaid && !prepaidTouched.value) {
    form.prepaidTax = autoPrepaid.value
  }
}

const onYearChange = async () => {
  prepaidTouched.value = false
  await loadYearMeta(true)
}

const openGenerate = async (row?: any) => {
  if (row) {
    const data = await api.get(`/finance/income-tax/${row.id}`)
    Object.assign(form, {
      year: data.taxYear,
      taxRatePercent: Number(data.taxRate) * 100,
      taxRelief: Number(data.taxRelief),
      prepaidTax: Number(data.prepaidTax),
      lossCarryforward: Number(data.lossCarryforward),
      enterpriseType: data.smallProfit ? 'SMALL' : 'GENERAL',
      employeeCount: data.employeeCount,
      totalAssets: data.totalAssets == null ? null : Number(data.totalAssets),
      remark: data.remark || '',
      adjustments: (data.adjustments || []).map((item: any) => ({
        type: item.type,
        item: item.item,
        amount: Number(item.amount),
        remark: item.remark || '',
        ruleCode: item.ruleCode || null
      }))
    })
  } else {
    Object.assign(form, {
      year: new Date().getFullYear() - 1,
      taxRatePercent: 25,
      taxRelief: 0,
      prepaidTax: 0,
      lossCarryforward: 0,
      enterpriseType: 'GENERAL',
      employeeCount: null,
      totalAssets: null,
      remark: '',
      adjustments: []
    })
  }
  await loadYearMeta(!row)
  dialog.value = true
}

const addAdjustment = () => form.adjustments.push({ type: 'ADD', item: '', amount: 0, remark: '' })
const removeAdjustment = (index: number) => form.adjustments.splice(index, 1)

// 按规则自动带出：业务招待费、广告费、罚款、研发加计扣除
const applyRules = async () => {
  if (!form.year) {
    ElMessage.warning('请先填写汇算年度')
    return
  }
  const rows = await api.get('/finance/income-tax/adjustment-preview', { year: form.year })
  const existing = new Set(
    form.adjustments.filter((item: any) => item.ruleCode).map((item: any) => item.ruleCode)
  )
  let added = 0
  for (const row of rows || []) {
    if (existing.has(row.ruleCode)) continue
    form.adjustments.push({
      type: row.type,
      item: row.item,
      amount: Number(row.amount || 0),
      remark: row.remark || '',
      ruleCode: row.ruleCode
    })
    added++
  }
  ElMessage.success(added ? `已带出 ${added} 条规则调整项` : '没有新的可带出调整项')
}

// ---------------- 分支机构
const loadBranches = async () => {
  branches.value = (await api.get('/finance/income-tax/branches')) || []
}
const openBranches = async () => {
  await loadBranches()
  Object.assign(branchForm, { id: null, name: '', taxpayerId: '', shareRatioPercent: 0, enabled: true })
  branchDialog.value = true
}
const editBranch = (row: any) => {
  Object.assign(branchForm, {
    id: row.id,
    name: row.name,
    taxpayerId: row.taxpayerId || '',
    shareRatioPercent: Number(row.shareRatio) * 100,
    enabled: row.enabled
  })
}
const saveBranch = async () => {
  if (!branchForm.name) {
    ElMessage.warning('请填写分支机构名称')
    return
  }
  await api.post('/finance/income-tax/branches', {
    id: branchForm.id,
    name: branchForm.name,
    taxpayerId: branchForm.taxpayerId || null,
    shareRatio: Number(branchForm.shareRatioPercent || 0) / 100,
    enabled: branchForm.enabled
  })
  ElMessage.success('分支机构已保存')
  Object.assign(branchForm, { id: null, name: '', taxpayerId: '', shareRatioPercent: 0, enabled: true })
  await loadBranches()
}
const deleteBranch = async (row: any) => {
  await ElMessageBox.confirm(`删除分支机构 ${row.name}？`, '删除确认')
  await api.delete(`/finance/income-tax/branches/${row.id}`)
  ElMessage.success('已删除')
  await loadBranches()
}

// ---------------- 跨年台账
const loadLedger = async () => {
  const data = await api.get('/finance/income-tax/adjustment-ledger', {
    ruleCode: ledgerRule.value || undefined,
    yearFrom: ledgerFrom.value || undefined,
    yearTo: ledgerTo.value || undefined,
    page: ledgerPage.value,
    size: 20
  })
  ledgerRows.value = data.records || []
  ledgerTotal.value = data.total || 0
}
const openLedger = async () => {
  ledgerPage.value = 1
  await loadLedger()
  ledgerDialog.value = true
}

// ---------------- 打印申报表
const printSettlement = () => {
  if (!detail.value) return
  const d = detail.value
  const money = (value: any) => Number(value || 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })
  const esc = (value: any) => String(value == null ? '' : value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const rows = (d.adjustments || []).map((row: any, index: number) => `
    <tr><td>${index + 1}</td><td>${esc(row.item)}</td>
    <td>${row.type === 'ADD' ? '调增' : '调减'}</td><td class="num">${money(row.amount)}</td>
    <td>${esc(row.remark)}</td></tr>`).join('')
  const allocationRows = (d.allocations || []).map((row: any) => `
    <tr><td>${esc(row.branchName)}</td><td class="num">${(Number(row.shareRatio) * 100).toFixed(2)}%</td>
    <td class="num">${money(row.amount)}</td></tr>`).join('')
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>${d.taxYear} 年度企业所得税汇算清缴</title>
    <style>
      body{font-family:"Microsoft YaHei",sans-serif;padding:28px;color:#1d2939}
      h1{font-size:20px;text-align:center;margin:0 0 6px}
      .sub{text-align:center;color:#667085;font-size:12px;margin-bottom:18px}
      table{width:100%;border-collapse:collapse;margin-bottom:14px}
      th,td{border:1px solid #d0d5dd;padding:6px 8px;font-size:12px;text-align:left}
      th{background:#f9fafb}
      .num{text-align:right;font-family:Consolas,monospace}
      h2{font-size:14px;margin:18px 0 8px}
      .sign{display:flex;justify-content:space-between;margin-top:40px;font-size:12px;color:#475467}
    </style></head><body>
    <h1>企业所得税年度汇算清缴表</h1>
    <div class="sub">税款所属年度：${d.taxYear} 年 · 企业类型：${d.smallProfit ? '小型微利企业' : '一般企业'}</div>
    <table>
      <tr><th>会计利润</th><td class="num">${money(d.accountingProfit)}</td><th>纳税调增</th><td class="num">${money(d.adjustmentAdd)}</td></tr>
      <tr><th>纳税调减</th><td class="num">${money(d.adjustmentDeduct)}</td><th>弥补以前年度亏损</th><td class="num">${money(d.lossCarryforward)}</td></tr>
      <tr><th>应纳税所得额</th><td class="num">${money(d.taxableIncome)}</td><th>税率</th><td class="num">${(Number(d.taxRate) * 100).toFixed(2)}%</td></tr>
      <tr><th>应纳税额</th><td class="num">${money(d.taxPayable)}</td><th>减免税额</th><td class="num">${money(d.taxRelief)}</td></tr>
      <tr><th>其中：小型微利减免</th><td class="num">${money(d.smallRelief)}</td><th>已预缴税额</th><td class="num">${money(d.prepaidTax)}</td></tr>
      <tr><th>应补（退）税额</th><td class="num"><b>${money(d.payableTax)}</b></td><th>状态</th><td>${esc(statusLabel[d.status] || d.status)}</td></tr>
    </table>
    <h2>纳税调整明细</h2>
    <table><thead><tr><th>序号</th><th>项目</th><th>方向</th><th>金额</th><th>说明</th></tr></thead>
      <tbody>${rows || '<tr><td colspan="5">无</td></tr>'}</tbody></table>
    ${allocationRows ? `<h2>分支机构分摊</h2>
      <table><thead><tr><th>机构</th><th>分摊比例</th><th>分摊税额</th></tr></thead>
      <tbody>${allocationRows}</tbody></table>` : ''}
    <div class="sign"><span>制表：${esc(d.createdBy || '')}</span>
      <span>申报日期：${esc(d.filedAt || '')}</span><span>财务负责人：__________</span></div>
    </body></html>`
  const win = window.open('', '_blank', 'width=900,height=700')
  if (!win) {
    ElMessage.warning('浏览器拦截了打印窗口，请允许弹窗')
    return
  }
  win.document.write(html)
  win.document.close()
  win.focus()
  win.print()
}

const submit = async () => {
  saving.value = true
  try {
    const result = await api.post('/finance/income-tax/generate', {
      year: form.year,
      taxRate: Number(form.taxRatePercent || 0) / 100,
      taxRelief: Number(form.taxRelief || 0),
      prepaidTax: Number(form.prepaidTax || 0),
      lossCarryforward: Number(form.lossCarryforward || 0),
      enterpriseType: form.enterpriseType,
      employeeCount: form.employeeCount,
      totalAssets: form.totalAssets,
      remark: form.remark,
      adjustments: form.adjustments
        .filter((item: any) => item.ruleCode || (item.item && Number(item.amount) > 0))
        .map((item: any) => ({
          type: item.type,
          item: item.item,
          amount: Number(item.amount || 0),
          remark: item.remark,
          ruleCode: item.ruleCode || null
        }))
    })
    ElMessage.success(`已生成 ${result.taxYear} 年度汇算表，应补（退）税额 ¥${fmtMoney(result.payableTax)}`)
    dialog.value = false
    await load()
    await openDetail(result)
  } finally {
    saving.value = false
  }
}

const fileSettlement = async (row: any) => {
  await ElMessageBox.confirm(
    `申报 ${row.taxYear} 年度企业所得税？会按应纳税额计提所得税费用，申报后不能重算。`, '确认申报')
  await api.post(`/finance/income-tax/${row.id}/file`)
  ElMessage.success('申报完成')
  await load()
}

const paySettlement = async (row: any) => {
  const amount = Number(row.payableTax)
  const action = amount >= 0 ? '补税' : '退税'
  await ElMessageBox.confirm(
    `登记${action} ¥${fmtMoney(Math.abs(amount))}？会生成${action}凭证。`, `确认${action}`)
  await api.post(`/finance/income-tax/${row.id}/pay`, { remark: `登记${action}` })
  ElMessage.success(`${action}已登记`)
  await load()
}

const openDetail = async (row: any) => {
  detail.value = await api.get(`/finance/income-tax/${row.id}`)
  detailDrawer.value = true
}

const statusLabel: Record<string, string> = {
  DRAFT: '草稿',
  FILED: '已申报',
  PAID: '已完成'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2>企业所得税汇算清缴</h2>
      <div class="toolbar-actions">
        <el-button v-perm="'finance:tax'" @click="openLedger">跨年台账</el-button>
        <el-button v-perm="'finance:tax'" @click="openBranches">分支机构</el-button>
        <el-button v-perm="'finance:tax'" type="primary" @click="openGenerate()">生成汇算表</el-button>
      </div>
    </div>

    <div class="toolbar">
      <el-input-number v-model="yearFilter" :min="2000" :max="2100" placeholder="汇算年度"
                       controls-position="right" style="width: 140px" />
      <el-select v-model="statusFilter" clearable placeholder="状态" style="width: 120px">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已申报" value="FILED" />
        <el-option label="已完成" value="PAID" />
      </el-select>
      <el-button @click="page = 1; load()">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column prop="taxYear" label="年度" width="90" />
      <el-table-column label="会计利润" width="130" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.accountingProfit) }}</template>
      </el-table-column>
      <el-table-column label="调增" width="110" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.adjustmentAdd) }}</template>
      </el-table-column>
      <el-table-column label="调减" width="110" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.adjustmentDeduct) }}</template>
      </el-table-column>
      <el-table-column label="弥补亏损" width="120" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.lossCarryforward) }}</template>
      </el-table-column>
      <el-table-column label="应纳税所得额" width="140" align="right">
        <template #default="{ row }"><b class="money">¥{{ fmtMoney(row.taxableIncome) }}</b></template>
      </el-table-column>
      <el-table-column label="税率" width="80" align="right">
        <template #default="{ row }">{{ (Number(row.taxRate) * 100).toFixed(2) }}%</template>
      </el-table-column>
      <el-table-column label="应纳税额" width="130" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.taxPayable) }}</template>
      </el-table-column>
      <el-table-column label="已预缴" width="120" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.prepaidTax) }}</template>
      </el-table-column>
      <el-table-column label="应补（退）" width="140" align="right">
        <template #default="{ row }">
          <b :class="{ 'danger-text': Number(row.payableTax) > 0, 'success-text': Number(row.payableTax) < 0 }">
            ¥{{ fmtMoney(row.payableTax) }}
          </b>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'PAID' ? 'success'
            : row.status === 'FILED' ? 'warning' : 'info'">
            {{ statusLabel[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'DRAFT'" v-perm="'finance:tax'"
                     link type="primary" @click="openGenerate(row)">编辑</el-button>
          <el-button v-if="row.status === 'DRAFT'" v-perm="'finance:tax'"
                     link type="warning" @click="fileSettlement(row)">申报</el-button>
          <el-button v-if="row.status === 'FILED'" v-perm="'finance:tax'"
                     link type="success" @click="paySettlement(row)">
            {{ Number(row.payableTax) >= 0 ? '补税' : '退税' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
                   layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 12px"
                   @current-change="load" />

    <el-dialog v-model="dialog" title="企业所得税汇算表" width="860px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="汇算年度" required>
            <el-input-number v-model="form.year" :min="2000" :max="2100" style="width: 100%"
                             @change="onYearChange" />
          </el-form-item>
          <el-form-item label="所得税税率（%）">
            <el-input-number v-model="form.taxRatePercent" :min="0" :max="100" :precision="2"
                             style="width: 100%" />
          </el-form-item>
          <el-form-item label="企业类型">
            <el-select v-model="form.enterpriseType" style="width: 100%">
              <el-option label="一般企业（25%）" value="GENERAL" />
              <el-option label="小型微利企业（实际 5%）" value="SMALL" />
            </el-select>
          </el-form-item>
          <el-form-item label="从业人数">
            <el-input-number v-model="form.employeeCount" :min="0" :max="100000"
                             style="width: 100%" placeholder="小型微利需 ≤300" />
          </el-form-item>
          <el-form-item label="资产总额">
            <el-input-number v-model="form.totalAssets" :min="0" :precision="2"
                             style="width: 100%" placeholder="小型微利需 ≤5000 万" />
          </el-form-item>
          <el-form-item label="减免税额">
            <el-input-number v-model="form.taxRelief" :min="0" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="已预缴税额">
            <el-input-number v-model="form.prepaidTax" :min="0" :precision="2" style="width: 100%"
                             @change="prepaidTouched = true" />
            <div class="muted" style="font-size: 12px; line-height: 1.4">
              按季度预缴自动带出 ¥{{ fmtMoney(autoPrepaid) }}，可手工调整
            </div>
          </el-form-item>
          <el-form-item label="弥补以前年度亏损">
            <el-input-number v-model="form.lossCarryforward" :min="0" :precision="2"
                             :max="Number(lossInfo.available || 0)" style="width: 100%" />
          </el-form-item>
          <el-form-item label="可弥补亏损余额">
            <div class="loss-tip">¥{{ fmtMoney(lossInfo.available) }}（{{ lossInfo.fromYear }}-{{ form.year - 1 }} 年）</div>
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" placeholder="汇算说明、调整依据等" />
          </el-form-item>
        </div>

        <div class="drawer-section">
          <div class="toolbar" style="margin-bottom: 8px">
            <h4 style="margin: 0">纳税调整</h4>
            <el-button v-perm="'finance:tax'" link type="primary" @click="applyRules">
              按规则自动带出
            </el-button>
          </div>
          <el-table :data="form.adjustments" size="small">
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <el-select v-model="row.type" :disabled="!!row.ruleCode" style="width: 100%">
                  <el-option label="调增" value="ADD" />
                  <el-option label="调减" value="DEDUCT" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="调整项目" min-width="200">
              <template #default="{ row }">
                <div style="display: flex; align-items: center; gap: 6px">
                  <el-tag v-if="row.ruleCode" size="small" type="warning">规则</el-tag>
                  <el-input v-model="row.item" :disabled="!!row.ruleCode" />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="金额" width="150">
              <template #default="{ row }">
                <el-input-number v-model="row.amount" :min="0" :precision="2"
                                 :disabled="!!row.ruleCode" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="140">
              <template #default="{ row }"><el-input v-model="row.remark" /></template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeAdjustment($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div style="margin-top: 8px">
            <el-button link type="primary" @click="addAdjustment">+ 添加手工调整项</el-button>
          </div>
        </div>

        <div class="muted">
          会计利润由总账收入类与费用类科目全年发生额自动计算；应纳税所得额 = 会计利润 + 调增 − 调减 − 弥补亏损；
          应补（退）= 应纳税额 − 减免 − 已预缴，负数表示应退。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">生成 / 重算</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" size="700px" title="汇算清缴详情">
      <div v-if="detail">
        <div class="detail-head">
          <div>
            <div class="detail-no mono">{{ detail.taxYear }} 年度企业所得税</div>
            <div class="muted" style="margin-top: 4px">
              应补（退）税额 ¥{{ fmtMoney(detail.payableTax) }}
            </div>
          </div>
          <div style="display: flex; align-items: center; gap: 8px">
            <el-button size="small" @click="printSettlement">打印申报表</el-button>
            <el-tag :type="detail.status === 'PAID' ? 'success'
              : detail.status === 'FILED' ? 'warning' : 'info'">
              {{ statusLabel[detail.status] || detail.status }}
            </el-tag>
          </div>
        </div>
        <div class="detail-grid">
          <div class="detail-item"><div class="label">会计利润</div><div class="value money">¥{{ fmtMoney(detail.accountingProfit) }}</div></div>
          <div class="detail-item"><div class="label">纳税调增</div><div class="value money">¥{{ fmtMoney(detail.adjustmentAdd) }}</div></div>
          <div class="detail-item"><div class="label">纳税调减</div><div class="value money">¥{{ fmtMoney(detail.adjustmentDeduct) }}</div></div>
          <div class="detail-item"><div class="label">弥补亏损</div><div class="value money">¥{{ fmtMoney(detail.lossCarryforward) }}</div></div>
          <div class="detail-item"><div class="label">应纳税所得额</div><div class="value money">¥{{ fmtMoney(detail.taxableIncome) }}</div></div>
          <div class="detail-item"><div class="label">税率</div><div class="value">{{ (Number(detail.taxRate) * 100).toFixed(2) }}%</div></div>
          <div class="detail-item"><div class="label">应纳税额</div><div class="value money">¥{{ fmtMoney(detail.taxPayable) }}</div></div>
          <div class="detail-item"><div class="label">减免税额</div><div class="value money">¥{{ fmtMoney(detail.taxRelief) }}</div></div>
          <div class="detail-item"><div class="label">其中：小型微利减免</div><div class="value money">¥{{ fmtMoney(detail.smallRelief) }}</div></div>
          <div class="detail-item"><div class="label">已预缴</div><div class="value money">¥{{ fmtMoney(detail.prepaidTax) }}</div></div>
          <div class="detail-item"><div class="label">应补（退）</div><div class="value money"><b>¥{{ fmtMoney(detail.payableTax) }}</b></div></div>
          <div class="detail-item"><div class="label">企业类型</div><div class="value">{{ detail.smallProfit ? '小型微利企业' : '一般企业' }}</div></div>
          <div class="detail-item"><div class="label">从业人数</div><div class="value">{{ detail.employeeCount ?? '-' }}</div></div>
          <div class="detail-item"><div class="label">资产总额</div><div class="value">{{ detail.totalAssets == null ? '-' : '¥' + fmtMoney(detail.totalAssets) }}</div></div>
          <div class="detail-item"><div class="label">申报时间</div><div class="value">{{ detail.filedAt || '-' }}</div></div>
          <div class="detail-item"><div class="label">完成时间</div><div class="value">{{ detail.paidAt || '-' }}</div></div>
        </div>

        <div class="drawer-section">
          <h4>纳税调整明细</h4>
          <div v-if="!detail.adjustments?.length" class="muted">没有纳税调整</div>
          <el-table v-else :data="detail.adjustments" size="small">
            <el-table-column label="类型" width="80">
              <template #default="{ row }">{{ row.type === 'ADD' ? '调增' : '调减' }}</template>
            </el-table-column>
            <el-table-column label="调整项目" min-width="180">
              <template #default="{ row }">
                <el-tag v-if="row.ruleCode" size="small" type="warning" style="margin-right: 6px">规则</el-tag>
                {{ row.item }}
              </template>
            </el-table-column>
            <el-table-column label="计算基数" width="120" align="right">
              <template #default="{ row }">{{ row.baseAmount ? '¥' + fmtMoney(row.baseAmount) : '-' }}</template>
            </el-table-column>
            <el-table-column label="限额" width="120" align="right">
              <template #default="{ row }">{{ row.limitAmount ? '¥' + fmtMoney(row.limitAmount) : '-' }}</template>
            </el-table-column>
            <el-table-column label="调整金额" width="120" align="right">
              <template #default="{ row }">¥{{ fmtMoney(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="结转余额" width="110" align="right">
              <template #default="{ row }">{{ Number(row.carryforwardBalance) ? '¥' + fmtMoney(row.carryforwardBalance) : '-' }}</template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="detail.allocations?.length" class="drawer-section">
          <h4>分支机构分摊</h4>
          <el-table :data="detail.allocations" size="small">
            <el-table-column prop="branchName" label="机构" min-width="180" />
            <el-table-column label="分摊比例" width="120" align="right">
              <template #default="{ row }">{{ (Number(row.shareRatio) * 100).toFixed(2) }}%</template>
            </el-table-column>
            <el-table-column label="分摊税额" width="140" align="right">
              <template #default="{ row }">¥{{ fmtMoney(row.amount) }}</template>
            </el-table-column>
          </el-table>
        </div>

        <div class="drawer-section">
          <h4>相关凭证</h4>
          <div v-if="!detail.vouchers?.length" class="muted">尚未生成计提或缴款凭证</div>
          <el-table v-else :data="detail.vouchers" size="small">
            <el-table-column prop="voucherNo" label="凭证号" width="160" />
            <el-table-column prop="voucherDate" label="凭证日期" width="110" />
            <el-table-column prop="bizType" label="类型" width="150" />
            <el-table-column prop="summary" label="摘要" min-width="180" />
          </el-table>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="ledgerDialog" title="纳税调整跨年台账" width="960px">
      <div class="toolbar">
        <el-input-number v-model="ledgerFrom" :min="2000" :max="2100" placeholder="起始年度"
                         controls-position="right" style="width: 130px" />
        <span class="muted">至</span>
        <el-input-number v-model="ledgerTo" :min="2000" :max="2100" placeholder="结束年度"
                         controls-position="right" style="width: 130px" />
        <el-select v-model="ledgerRule" clearable placeholder="规则" style="width: 180px">
          <el-option label="业务招待费" value="ENTERTAINMENT" />
          <el-option label="广告费和业务宣传费" value="ADVERTISING" />
          <el-option label="罚款、滞纳金" value="FINE" />
          <el-option label="研发费用加计扣除" value="RD_SUPER" />
        </el-select>
        <el-button @click="ledgerPage = 1; loadLedger()">查询</el-button>
      </div>
      <el-table :data="ledgerRows" size="small">
        <el-table-column prop="taxYear" label="年度" width="80" />
        <el-table-column label="规则" width="150">
          <template #default="{ row }">
            {{ row.ruleCode === 'ENTERTAINMENT' ? '业务招待费'
              : row.ruleCode === 'ADVERTISING' ? '广告费'
              : row.ruleCode === 'FINE' ? '罚款滞纳金'
              : row.ruleCode === 'RD_SUPER' ? '研发加计' : (row.ruleCode || '手工') }}
          </template>
        </el-table-column>
        <el-table-column prop="item" label="调整项目" min-width="190" />
        <el-table-column label="方向" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.type === 'ADD' ? 'warning' : 'success'">
              {{ row.type === 'ADD' ? '调增' : '调减' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="调整金额" width="120" align="right">
          <template #default="{ row }">¥{{ fmtMoney(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="计算基数" width="120" align="right">
          <template #default="{ row }">{{ Number(row.baseAmount) ? '¥' + fmtMoney(row.baseAmount) : '-' }}</template>
        </el-table-column>
        <el-table-column label="限额" width="120" align="right">
          <template #default="{ row }">{{ Number(row.limitAmount) ? '¥' + fmtMoney(row.limitAmount) : '-' }}</template>
        </el-table-column>
        <el-table-column label="结转余额" width="120" align="right">
          <template #default="{ row }">
            <span :class="{ 'success-text': Number(row.carryforwardBalance) > 0 }">
              ¥{{ fmtMoney(row.carryforwardBalance) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="ledgerPage" :page-size="20" :total="ledgerTotal"
                     layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 10px"
                     @current-change="loadLedger" />
      <div class="muted" style="margin-top: 8px">
        台账在申报时落账：广告费超限部分结转到以后年度，下一年度先用当年额度、再用结转额。
      </div>
      <template #footer>
        <el-button @click="ledgerDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="branchDialog" title="分支机构分摊" width="720px">
      <div class="muted" style="margin-bottom: 8px">
        各分支机构分摊比例合计不能超过 100%，剩余比例归总机构；申报时按应补（退）税额自动分摊。
      </div>
      <el-table :data="branches" size="small" style="margin-bottom: 12px">
        <el-table-column prop="name" label="机构名称" min-width="160" />
        <el-table-column prop="taxpayerId" label="纳税人识别号" width="160" />
        <el-table-column label="分摊比例" width="110" align="right">
          <template #default="{ row }">{{ (Number(row.shareRatio) * 100).toFixed(2) }}%</template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="primary" @click="editBranch(row)">编辑</el-button>
            <el-button link type="danger" @click="deleteBranch(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="机构名称" required>
            <el-input v-model="branchForm.name" />
          </el-form-item>
          <el-form-item label="纳税人识别号">
            <el-input v-model="branchForm.taxpayerId" />
          </el-form-item>
          <el-form-item label="分摊比例（%）">
            <el-input-number v-model="branchForm.shareRatioPercent" :min="0" :max="100"
                             :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="branchForm.enabled" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="branchDialog = false">关闭</el-button>
        <el-button type="primary" @click="saveBranch">
          {{ branchForm.id ? '保存修改' : '新增分支机构' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}
.form-grid .full {
  grid-column: span 3;
}
.loss-tip {
  height: 32px;
  line-height: 32px;
  color: #475467;
}
.danger-text {
  color: #d92d20;
}
.success-text {
  color: #039855;
}
</style>
