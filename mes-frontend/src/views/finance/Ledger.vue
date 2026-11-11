<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney } from '../../api'

const tab = ref('vouchers')
const loading = ref(false)

const vouchers = ref<any[]>([])
const voucherTotal = ref(0)
const voucherPage = ref(1)
const voucherSize = ref(20)
const voucherPeriod = ref('')
const voucherBizType = ref('')
const voucherKeyword = ref('')

const detailDrawer = ref(false)
const detail = ref<any>(null)

const accounts = ref<any[]>([])
const periods = ref<any[]>([])
const rates = ref<any[]>([])

const trial = ref<any>({ rows: [] })
const trialFrom = ref(firstDayOfMonth())
const trialTo = ref(today())

const manualDialog = ref(false)
const manualSaving = ref(false)
const manualForm = reactive<any>({
  voucherDate: today(),
  summary: '',
  lines: [
    { accountCode: '', summary: '', debit: 0, credit: 0 },
    { accountCode: '', summary: '', debit: 0, credit: 0 }
  ]
})

const rateDialog = ref(false)
const rateSaving = ref(false)
const rateForm = reactive<any>({ currency: 'USD', rateDate: today(), rate: 0 })

const syncResult = ref<any>(null)
const syncing = ref(false)

function today() {
  return new Date().toISOString().slice(0, 10)
}
function firstDayOfMonth() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`
}

const loadVouchers = async () => {
  loading.value = true
  try {
    const data = await api.get('/finance/vouchers', {
      period: voucherPeriod.value || undefined,
      bizType: voucherBizType.value || undefined,
      keyword: voucherKeyword.value || undefined,
      page: voucherPage.value,
      size: voucherSize.value
    })
    vouchers.value = data.records || []
    voucherTotal.value = data.total || 0
  } finally {
    loading.value = false
  }
}
const loadAccounts = async () => {
  accounts.value = (await api.get('/finance/accounts')) || []
}
const loadPeriods = async () => {
  periods.value = (await api.get('/finance/periods')) || []
}
const loadRates = async () => {
  rates.value = (await api.get('/finance/exchange-rates')) || []
}
const loadTrial = async () => {
  loading.value = true
  try {
    trial.value = await api.get('/finance/trial-balance', {
      from: trialFrom.value,
      to: trialTo.value
    })
  } finally {
    loading.value = false
  }
}

const openDetail = async (row: any) => {
  detail.value = await api.get(`/finance/vouchers/${row.id}`)
  detailDrawer.value = true
}

const closePeriod = async (row: any) => {
  await ElMessageBox.confirm(
    `关账 ${row.period}？关账后该期间不能再记账，且不可撤销。`, '期间关账')
  const result = await api.post(`/finance/periods/${row.period}/close`)
  ElMessage.success(`已关账：${result.period}，凭证 ${result.voucherCount} 张`)
  await loadPeriods()
}

const openManualDialog = () => {
  Object.assign(manualForm, {
    voucherDate: today(),
    summary: '',
    lines: [
      { accountCode: '', summary: '', debit: 0, credit: 0 },
      { accountCode: '', summary: '', debit: 0, credit: 0 }
    ]
  })
  manualDialog.value = true
}
const addManualLine = () => manualForm.lines.push({ accountCode: '', summary: '', debit: 0, credit: 0 })
const removeManualLine = (index: number) => manualForm.lines.splice(index, 1)
const submitManual = async () => {
  if (manualForm.lines.some((line: any) => !line.accountCode)) {
    ElMessage.warning('请为每条分录选择科目')
    return
  }
  manualSaving.value = true
  try {
    await api.post('/finance/vouchers', {
      voucherDate: manualForm.voucherDate,
      summary: manualForm.summary,
      currency: 'CNY',
      exchangeRate: 1,
      lines: manualForm.lines.map((line: any) => ({
        accountCode: line.accountCode,
        summary: line.summary,
        debit: Number(line.debit || 0),
        credit: Number(line.credit || 0)
      }))
    })
    ElMessage.success('手工凭证已记账')
    manualDialog.value = false
    await Promise.all([loadVouchers(), loadPeriods()])
  } finally {
    manualSaving.value = false
  }
}

const submitRate = async () => {
  if (!rateForm.rate || Number(rateForm.rate) <= 0) {
    ElMessage.warning('请填写大于 0 的汇率')
    return
  }
  rateSaving.value = true
  try {
    await api.post('/finance/exchange-rates', {
      currency: rateForm.currency,
      rateDate: rateForm.rateDate,
      rate: Number(rateForm.rate)
    })
    ElMessage.success('汇率已保存')
    rateDialog.value = false
    await loadRates()
  } finally {
    rateSaving.value = false
  }
}

const syncAp = async () => {
  syncing.value = true
  try {
    syncResult.value = await api.post('/finance/ap/sync')
    ElMessage.success(`应付凭证同步完成：发票 ${syncResult.value.invoicePosted} 张，付款 ${syncResult.value.paymentPosted} 张`)
    await Promise.all([loadVouchers(), loadPeriods()])
  } finally {
    syncing.value = false
  }
}

const onTabChange = async (name: string) => {
  if (name === 'trial') await loadTrial()
  if (name === 'periods') await loadPeriods()
  if (name === 'rates') await loadRates()
}

const bizLabel: Record<string, string> = {
  AR_INVOICE: '销售发票',
  AR_INVOICE_REV: '销售发票红冲',
  AR_RECEIPT: '销售收款',
  AR_RECEIPT_REV: '销售收款红冲',
  AP_INVOICE: '采购发票',
  AP_PAYMENT: '采购付款',
  MANUAL: '手工凭证'
}
const accountTypeLabel: Record<string, string> = {
  ASSET: '资产',
  LIABILITY: '负债',
  EQUITY: '权益',
  REVENUE: '收入',
  EXPENSE: '费用'
}

onMounted(async () => {
  await Promise.all([loadVouchers(), loadAccounts(), loadPeriods(), loadRates()])
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2>总账</h2>
      <div class="toolbar-actions">
        <el-button v-perm="'finance:gl'" type="primary" @click="openManualDialog">手工凭证</el-button>
        <el-button v-perm="'finance:sync'" :loading="syncing" @click="syncAp">同步应付凭证</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="记账凭证" name="vouchers">
        <div class="toolbar">
          <el-input v-model="voucherPeriod" placeholder="期间，如 2026-11" clearable style="width: 150px" />
          <el-select v-model="voucherBizType" clearable placeholder="业务类型" style="width: 160px">
            <el-option label="销售发票" value="AR_INVOICE" />
            <el-option label="销售收款" value="AR_RECEIPT" />
            <el-option label="采购发票" value="AP_INVOICE" />
            <el-option label="采购付款" value="AP_PAYMENT" />
            <el-option label="手工凭证" value="MANUAL" />
          </el-select>
          <el-input v-model="voucherKeyword" placeholder="凭证号 / 单据号 / 摘要" clearable style="width: 240px"
                    @keyup.enter="voucherPage = 1; loadVouchers()" />
          <el-button @click="voucherPage = 1; loadVouchers()">查询</el-button>
        </div>
        <el-table v-loading="loading" :data="vouchers" size="small">
          <el-table-column prop="voucherNo" label="凭证号" width="160" />
          <el-table-column prop="voucherDate" label="凭证日期" width="110" />
          <el-table-column prop="period" label="期间" width="90" />
          <el-table-column label="业务类型" width="130">
            <template #default="{ row }">{{ bizLabel[row.bizType] || row.bizType }}</template>
          </el-table-column>
          <el-table-column prop="bizNo" label="业务单据" width="170" />
          <el-table-column prop="summary" label="摘要" min-width="180" show-overflow-tooltip />
          <el-table-column prop="currency" label="币种" width="70" />
          <el-table-column label="金额（本位币）" width="140" align="right">
            <template #default="{ row }"><span class="money">¥{{ fmtMoney(row.amount) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'REVERSED' ? 'info' : 'success'">
                {{ row.status === 'REVERSED' ? '已红冲' : '已记账' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdBy" label="制单人" width="90" />
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="voucherPage" v-model:page-size="voucherSize" :total="voucherTotal"
                       layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 12px"
                       @current-change="loadVouchers" />
      </el-tab-pane>

      <el-tab-pane label="试算平衡" name="trial">
        <div class="toolbar">
          <el-date-picker v-model="trialFrom" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
          <span class="muted">至</span>
          <el-date-picker v-model="trialTo" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
          <el-button type="primary" @click="loadTrial">试算</el-button>
          <el-tag v-if="trial.rows?.length" :type="trial.balanced ? 'success' : 'danger'">
            {{ trial.balanced ? '借贷平衡' : '借贷不平' }}
          </el-tag>
        </div>
        <el-table v-loading="loading" :data="trial.rows" size="small" show-summary
                  :summary-method="() => ['合计', '', '',
                    '¥' + fmtMoney(trial.debit), '¥' + fmtMoney(trial.credit), '']">
          <el-table-column prop="code" label="科目编码" width="110" />
          <el-table-column prop="name" label="科目名称" min-width="200" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ accountTypeLabel[row.type] || row.type }}</template>
          </el-table-column>
          <el-table-column label="本期借方" width="140" align="right">
            <template #default="{ row }">¥{{ fmtMoney(row.debit) }}</template>
          </el-table-column>
          <el-table-column label="本期贷方" width="140" align="right">
            <template #default="{ row }">¥{{ fmtMoney(row.credit) }}</template>
          </el-table-column>
          <el-table-column label="余额（借-贷）" width="150" align="right">
            <template #default="{ row }">
              <span class="money">¥{{ fmtMoney(row.balance) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="期间关账" name="periods">
        <div class="muted" style="margin-bottom: 8px">
          关账前会自动校验该期间借贷平衡；关账后该期间不能再记账，也不能撤销。
        </div>
        <el-table :data="periods" size="small">
          <el-table-column prop="period" label="期间" width="120" />
          <el-table-column prop="voucherCount" label="凭证数" width="100" align="right" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'CLOSED' ? 'info' : 'success'">
                {{ row.status === 'CLOSED' ? '已关账' : '开放中' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="closedBy" label="关账人" width="120" />
          <el-table-column prop="closedAt" label="关账时间" width="160" />
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button v-if="row.status !== 'CLOSED'" v-perm="'finance:period'"
                         link type="primary" @click="closePeriod(row)">关账</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="汇率" name="rates">
        <div class="toolbar">
          <span class="muted">外币发票按开票日期取最近一条汇率折算本位币；没有汇率不能开票。</span>
          <el-button v-perm="'finance:rate'" type="primary" @click="rateDialog = true">维护汇率</el-button>
        </div>
        <el-table :data="rates" size="small">
          <el-table-column prop="currency" label="币种" width="90" />
          <el-table-column prop="rateDate" label="生效日期" width="130" />
          <el-table-column label="汇率（1 外币 = ? 人民币）" width="220" align="right">
            <template #default="{ row }">{{ row.rate }}</template>
          </el-table-column>
          <el-table-column prop="createdBy" label="维护人" width="120" />
          <el-table-column prop="createdAt" label="维护时间" width="160" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="科目" name="accounts">
        <el-table :data="accounts" size="small">
          <el-table-column prop="code" label="科目编码" width="120" />
          <el-table-column prop="name" label="科目名称" min-width="220" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">{{ accountTypeLabel[row.type] || row.type }}</template>
          </el-table-column>
          <el-table-column prop="role" label="自动记账角色" width="150" />
          <el-table-column label="启用" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="manualDialog" title="手工凭证" width="820px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="凭证日期" required>
            <el-date-picker v-model="manualForm.voucherDate" type="date" value-format="YYYY-MM-DD"
                            style="width: 100%" />
          </el-form-item>
          <el-form-item label="摘要" class="full">
            <el-input v-model="manualForm.summary" placeholder="如：期初余额导入、调整分录" />
          </el-form-item>
        </div>
        <el-table :data="manualForm.lines" size="small">
          <el-table-column label="科目" min-width="240">
            <template #default="{ row }">
              <el-select v-model="row.accountCode" filterable style="width: 100%">
                <el-option v-for="item in accounts" :key="item.code"
                           :label="`${item.code} · ${item.name}`" :value="item.code" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="摘要" min-width="180">
            <template #default="{ row }"><el-input v-model="row.summary" /></template>
          </el-table-column>
          <el-table-column label="借方" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.debit" :min="0" :precision="2" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="贷方" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.credit" :min="0" :precision="2" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeManualLine($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top: 8px">
          <el-button link type="primary" @click="addManualLine">+ 添加分录</el-button>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="manualDialog = false">取消</el-button>
        <el-button type="primary" :loading="manualSaving" @click="submitManual">记账</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rateDialog" title="维护汇率" width="420px">
      <el-form label-position="top">
        <el-form-item label="币种">
          <el-select v-model="rateForm.currency" style="width: 100%">
            <el-option label="美元 USD" value="USD" />
            <el-option label="欧元 EUR" value="EUR" />
          </el-select>
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="rateForm.rateDate" type="date" value-format="YYYY-MM-DD"
                          style="width: 100%" />
        </el-form-item>
        <el-form-item label="汇率（1 外币 = ? 人民币）">
          <el-input-number v-model="rateForm.rate" :min="0" :precision="6" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rateDialog = false">取消</el-button>
        <el-button type="primary" :loading="rateSaving" @click="submitRate">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" size="680px" title="凭证详情">
      <div v-if="detail">
        <div class="detail-head">
          <div>
            <div class="detail-no mono">{{ detail.voucherNo }}</div>
            <div class="muted" style="margin-top: 4px">
              {{ bizLabel[detail.bizType] || detail.bizType }} · {{ detail.bizNo || '-' }}
            </div>
          </div>
          <el-tag :type="detail.status === 'REVERSED' ? 'info' : 'success'">
            {{ detail.status === 'REVERSED' ? '已红冲' : '已记账' }}
          </el-tag>
        </div>
        <div class="detail-grid">
          <div class="detail-item"><div class="label">凭证日期</div><div class="value">{{ detail.voucherDate }}</div></div>
          <div class="detail-item"><div class="label">期间</div><div class="value">{{ detail.period }}</div></div>
          <div class="detail-item"><div class="label">币种</div><div class="value">{{ detail.currency }}</div></div>
          <div class="detail-item"><div class="label">记账汇率</div><div class="value">{{ detail.exchangeRate }}</div></div>
          <div class="detail-item"><div class="label">制单人</div><div class="value">{{ detail.createdBy }}</div></div>
          <div class="detail-item"><div class="label">制单时间</div><div class="value">{{ detail.createdAt }}</div></div>
          <div class="detail-item" style="grid-column: span 3">
            <div class="label">摘要</div><div class="value">{{ detail.summary || '-' }}</div>
          </div>
        </div>
        <div class="drawer-section">
          <h4>分录</h4>
          <el-table :data="detail.lines" size="small" show-summary
                    :summary-method="() => ['合计', '', '', '',
                      '¥' + fmtMoney(detail.lines.reduce((s: number, r: any) => s + Number(r.debit || 0), 0)),
                      '¥' + fmtMoney(detail.lines.reduce((s: number, r: any) => s + Number(r.credit || 0), 0)),
                      '']">
            <el-table-column prop="lineNo" label="#" width="50" />
            <el-table-column prop="accountCode" label="科目" width="110" />
            <el-table-column prop="accountName" label="科目名称" min-width="170" />
            <el-table-column prop="summary" label="摘要" min-width="180" />
            <el-table-column label="借方" width="120" align="right">
              <template #default="{ row }">¥{{ fmtMoney(row.debit) }}</template>
            </el-table-column>
            <el-table-column label="贷方" width="120" align="right">
              <template #default="{ row }">¥{{ fmtMoney(row.credit) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-drawer>
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
</style>
