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
const form = reactive<any>({
  year: new Date().getFullYear(),
  quarter: Math.floor(new Date().getMonth() / 3) + 1,
  taxRatePercent: 25,
  remark: ''
})

const detailDrawer = ref(false)
const detail = ref<any>(null)

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/finance/tax-prepayments', {
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

const openGenerate = (row?: any) => {
  if (row) {
    Object.assign(form, {
      year: row.taxYear,
      quarter: row.quarter,
      taxRatePercent: Number(row.taxRate) * 100,
      remark: row.remark || ''
    })
  } else {
    const now = new Date()
    Object.assign(form, {
      year: now.getFullYear(),
      quarter: Math.floor(now.getMonth() / 3) + 1,
      taxRatePercent: 25,
      remark: ''
    })
  }
  dialog.value = true
}

const submit = async () => {
  saving.value = true
  try {
    const result = await api.post('/finance/tax-prepayments/generate', {
      year: form.year,
      quarter: form.quarter,
      taxRate: Number(form.taxRatePercent || 0) / 100,
      remark: form.remark
    })
    ElMessage.success(`${result.taxYear} 年 Q${result.quarter} 本期应预缴 ¥${fmtMoney(result.currentPrepaid)}`)
    dialog.value = false
    await load()
    await openDetail(result)
  } finally {
    saving.value = false
  }
}

const filePrepayment = async (row: any) => {
  await ElMessageBox.confirm(
    `申报 ${row.taxYear} 年 Q${row.quarter} 预缴？会按本期应预缴额计提所得税费用，申报后不能重算。`, '确认申报')
  await api.post(`/finance/tax-prepayments/${row.id}/file`)
  ElMessage.success('预缴申报完成')
  await load()
}

const payPrepayment = async (row: any) => {
  await ElMessageBox.confirm(
    `登记缴纳 ${row.taxYear} 年 Q${row.quarter} 预缴税款 ¥${fmtMoney(row.currentPrepaid)}？`, '确认缴款')
  await api.post(`/finance/tax-prepayments/${row.id}/pay`, { remark: '登记缴款' })
  ElMessage.success('缴款已登记')
  await load()
}

const openDetail = async (row: any) => {
  detail.value = await api.get(`/finance/tax-prepayments/${row.id}`)
  detailDrawer.value = true
}

const statusLabel: Record<string, string> = {
  DRAFT: '草稿',
  FILED: '已申报',
  PAID: '已缴款'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2>企业所得税季度预缴</h2>
      <div class="toolbar-actions">
        <el-button v-perm="'finance:tax'" type="primary" @click="openGenerate()">生成预缴表</el-button>
      </div>
    </div>

    <div class="toolbar">
      <el-input-number v-model="yearFilter" :min="2000" :max="2100" placeholder="年度"
                       controls-position="right" style="width: 140px" />
      <el-select v-model="statusFilter" clearable placeholder="状态" style="width: 120px">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已申报" value="FILED" />
        <el-option label="已缴款" value="PAID" />
      </el-select>
      <el-button @click="page = 1; load()">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column prop="taxYear" label="年度" width="90" />
      <el-table-column label="季度" width="80">
        <template #default="{ row }">Q{{ row.quarter }}</template>
      </el-table-column>
      <el-table-column label="累计利润" width="140" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.ytdProfit) }}</template>
      </el-table-column>
      <el-table-column label="累计应纳税额" width="140" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.ytdTax) }}</template>
      </el-table-column>
      <el-table-column label="以前季度已预缴" width="150" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.prepaidBefore) }}</template>
      </el-table-column>
      <el-table-column label="本期应预缴" width="140" align="right">
        <template #default="{ row }"><b class="money">¥{{ fmtMoney(row.currentPrepaid) }}</b></template>
      </el-table-column>
      <el-table-column label="税率" width="80" align="right">
        <template #default="{ row }">{{ (Number(row.taxRate) * 100).toFixed(2) }}%</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'PAID' ? 'success'
            : row.status === 'FILED' ? 'warning' : 'info'">
            {{ statusLabel[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="filedAt" label="申报时间" width="150" />
      <el-table-column prop="paidAt" label="缴款时间" width="150" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'DRAFT'" v-perm="'finance:tax'"
                     link type="primary" @click="openGenerate(row)">编辑</el-button>
          <el-button v-if="row.status === 'DRAFT'" v-perm="'finance:tax'"
                     link type="warning" @click="filePrepayment(row)">申报</el-button>
          <el-button v-if="row.status === 'FILED'" v-perm="'finance:tax'"
                     link type="success" @click="payPrepayment(row)">缴款</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
                   layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 12px"
                   @current-change="load" />

    <el-dialog v-model="dialog" title="生成季度预缴表" width="460px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="预缴年度" required>
            <el-input-number v-model="form.year" :min="2000" :max="2100" style="width: 100%" />
          </el-form-item>
          <el-form-item label="季度" required>
            <el-select v-model="form.quarter" style="width: 100%">
              <el-option label="第 1 季度" :value="1" />
              <el-option label="第 2 季度" :value="2" />
              <el-option label="第 3 季度" :value="3" />
              <el-option label="第 4 季度" :value="4" />
            </el-select>
          </el-form-item>
          <el-form-item label="所得税税率（%）">
            <el-input-number v-model="form.taxRatePercent" :min="0" :max="100" :precision="2"
                             style="width: 100%" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" />
          </el-form-item>
        </div>
      </el-form>
      <div class="muted">
        按年初至本季末累计会计利润计税，扣除本年以前季度已预缴；纳税调整与亏损弥补在年度汇算处理。
      </div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">生成 / 重算</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" size="640px" title="预缴详情">
      <div v-if="detail">
        <div class="detail-head">
          <div>
            <div class="detail-no mono">{{ detail.taxYear }} 年 Q{{ detail.quarter }}</div>
            <div class="muted" style="margin-top: 4px">本期应预缴 ¥{{ fmtMoney(detail.currentPrepaid) }}</div>
          </div>
          <el-tag :type="detail.status === 'PAID' ? 'success'
            : detail.status === 'FILED' ? 'warning' : 'info'">
            {{ statusLabel[detail.status] || detail.status }}
          </el-tag>
        </div>
        <div class="detail-grid">
          <div class="detail-item"><div class="label">累计利润</div><div class="value money">¥{{ fmtMoney(detail.ytdProfit) }}</div></div>
          <div class="detail-item"><div class="label">累计应纳税额</div><div class="value money">¥{{ fmtMoney(detail.ytdTax) }}</div></div>
          <div class="detail-item"><div class="label">以前季度已预缴</div><div class="value money">¥{{ fmtMoney(detail.prepaidBefore) }}</div></div>
          <div class="detail-item"><div class="label">本期应预缴</div><div class="value money"><b>¥{{ fmtMoney(detail.currentPrepaid) }}</b></div></div>
          <div class="detail-item"><div class="label">税率</div><div class="value">{{ (Number(detail.taxRate) * 100).toFixed(2) }}%</div></div>
          <div class="detail-item"><div class="label">所属期间</div><div class="value">{{ detail.periodStart }} ~ {{ detail.periodEnd }}</div></div>
          <div class="detail-item"><div class="label">申报时间</div><div class="value">{{ detail.filedAt || '-' }}</div></div>
          <div class="detail-item"><div class="label">缴款时间</div><div class="value">{{ detail.paidAt || '-' }}</div></div>
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
  </div>
</template>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 14px;
}
.form-grid .full {
  grid-column: span 3;
}
</style>
