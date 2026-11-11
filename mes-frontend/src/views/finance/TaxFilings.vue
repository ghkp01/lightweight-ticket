<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney } from '../../api'

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const period = ref('')
const status = ref('')

const generateDialog = ref(false)
const generating = ref(false)
const generateForm = reactive<any>({
  period: new Date().toISOString().slice(0, 7),
  cityRatePercent: 7,
  eduRatePercent: 3,
  localEduPercent: 2
})

const detailDrawer = ref(false)
const detail = ref<any>(null)

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/finance/tax-filings', {
      period: period.value || undefined,
      status: status.value || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

const openGenerate = () => {
  Object.assign(generateForm, {
    period: new Date().toISOString().slice(0, 7),
    cityRatePercent: 7,
    eduRatePercent: 3,
    localEduPercent: 2
  })
  generateDialog.value = true
}
const submitGenerate = async () => {
  generating.value = true
  try {
    const result = await api.post('/finance/tax-filings/generate', {
      period: generateForm.period,
      cityRate: Number(generateForm.cityRatePercent || 0) / 100,
      eduRate: Number(generateForm.eduRatePercent || 0) / 100,
      localEduRate: Number(generateForm.localEduPercent || 0) / 100
    })
    ElMessage.success(`已生成 ${result.period} 申报表，应纳税额 ¥${fmtMoney(result.totalPayable)}`)
    generateDialog.value = false
    await load()
    await openDetail(result)
  } finally {
    generating.value = false
  }
}

const fileFiling = async (row: any) => {
  await ElMessageBox.confirm(
    `申报 ${row.period}？会结转销项/进项并计提附加税，申报后不能重新生成。`, '确认申报')
  await api.post(`/finance/tax-filings/${row.id}/file`)
  ElMessage.success('申报完成')
  await load()
}
const payFiling = async (row: any) => {
  await ElMessageBox.confirm(
    `登记缴纳 ${row.period} 税款 ¥${fmtMoney(row.totalPayable)}？会生成缴款凭证。`, '确认缴款')
  await api.post(`/finance/tax-filings/${row.id}/pay`, { remark: '登记缴款' })
  ElMessage.success('缴款已登记')
  await load()
}

const openDetail = async (row: any) => {
  detail.value = await api.get(`/finance/tax-filings/${row.id}`)
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
      <h2>税务申报</h2>
      <div class="toolbar-actions">
        <el-button v-perm="'finance:tax'" type="primary" @click="openGenerate">生成申报表</el-button>
      </div>
    </div>

    <div class="toolbar">
      <el-date-picker v-model="period" type="month" value-format="YYYY-MM" placeholder="申报期间"
                      clearable style="width: 150px" />
      <el-select v-model="status" clearable placeholder="状态" style="width: 120px">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已申报" value="FILED" />
        <el-option label="已缴款" value="PAID" />
      </el-select>
      <el-button @click="page = 1; load()">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column prop="period" label="所属期间" width="110" />
      <el-table-column prop="taxType" label="税种" width="90" />
      <el-table-column label="销项税额" width="130" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.outputTax) }}</template>
      </el-table-column>
      <el-table-column label="进项税额" width="130" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.inputTax) }}</template>
      </el-table-column>
      <el-table-column label="应交增值税" width="130" align="right">
        <template #default="{ row }"><b class="money">¥{{ fmtMoney(row.vatPayable) }}</b></template>
      </el-table-column>
      <el-table-column label="附加税" width="120" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.totalSurtax) }}</template>
      </el-table-column>
      <el-table-column label="应缴合计" width="130" align="right">
        <template #default="{ row }"><b class="money">¥{{ fmtMoney(row.totalPayable) }}</b></template>
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
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'DRAFT'" v-perm="'finance:tax'"
                     link type="warning" @click="fileFiling(row)">申报</el-button>
          <el-button v-if="row.status === 'FILED'" v-perm="'finance:tax'"
                     link type="success" @click="payFiling(row)">缴款</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
                   layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 12px"
                   @current-change="load" />

    <el-dialog v-model="generateDialog" title="生成税务申报表" width="460px">
      <el-form label-position="top">
        <el-form-item label="所属期间">
          <el-date-picker v-model="generateForm.period" type="month" value-format="YYYY-MM"
                          style="width: 100%" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="城建税（%）">
            <el-input-number v-model="generateForm.cityRatePercent" :min="0" :max="100"
                             :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="教育费附加（%）">
            <el-input-number v-model="generateForm.eduRatePercent" :min="0" :max="100"
                             :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="地方教育（%）">
            <el-input-number v-model="generateForm.localEduPercent" :min="0" :max="100"
                             :precision="2" style="width: 100%" />
          </el-form-item>
        </div>
      </el-form>
      <div class="muted">
        销项/进项取自总账已记账凭证；进项大于销项时形成留抵，应交增值税为 0。
      </div>
      <template #footer>
        <el-button @click="generateDialog = false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="submitGenerate">生成</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" size="680px" title="申报详情">
      <div v-if="detail">
        <div class="detail-head">
          <div>
            <div class="detail-no mono">{{ detail.period }} 增值税及附加</div>
            <div class="muted" style="margin-top: 4px">应缴合计 ¥{{ fmtMoney(detail.totalPayable) }}</div>
          </div>
          <el-tag :type="detail.status === 'PAID' ? 'success'
            : detail.status === 'FILED' ? 'warning' : 'info'">
            {{ statusLabel[detail.status] || detail.status }}
          </el-tag>
        </div>
        <div class="detail-grid">
          <div class="detail-item"><div class="label">销项税额</div><div class="value money">¥{{ fmtMoney(detail.outputTax) }}</div></div>
          <div class="detail-item"><div class="label">进项税额</div><div class="value money">¥{{ fmtMoney(detail.inputTax) }}</div></div>
          <div class="detail-item"><div class="label">应交增值税</div><div class="value money">¥{{ fmtMoney(detail.vatPayable) }}</div></div>
          <div class="detail-item"><div class="label">城建税（{{ (Number(detail.cityRate) * 100).toFixed(2) }}%）</div><div class="value money">¥{{ fmtMoney(detail.cityTax) }}</div></div>
          <div class="detail-item"><div class="label">教育费附加</div><div class="value money">¥{{ fmtMoney(detail.eduTax) }}</div></div>
          <div class="detail-item"><div class="label">地方教育附加</div><div class="value money">¥{{ fmtMoney(detail.localEduTax) }}</div></div>
          <div class="detail-item"><div class="label">附加税合计</div><div class="value money">¥{{ fmtMoney(detail.totalSurtax) }}</div></div>
          <div class="detail-item"><div class="label">应缴合计</div><div class="value money"><b>¥{{ fmtMoney(detail.totalPayable) }}</b></div></div>
          <div class="detail-item"><div class="label">申报时间</div><div class="value">{{ detail.filedAt || '-' }}</div></div>
          <div class="detail-item"><div class="label">缴款时间</div><div class="value">{{ detail.paidAt || '-' }}</div></div>
        </div>
        <div class="drawer-section">
          <h4>相关凭证</h4>
          <div v-if="!detail.vouchers?.length" class="muted">尚未生成申报或缴款凭证</div>
          <el-table v-else :data="detail.vouchers" size="small">
            <el-table-column prop="voucherNo" label="凭证号" width="160" />
            <el-table-column prop="voucherDate" label="凭证日期" width="110" />
            <el-table-column prop="bizType" label="类型" width="170" />
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
  gap: 0 12px;
}
</style>
