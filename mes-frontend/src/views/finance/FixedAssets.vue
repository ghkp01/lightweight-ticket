<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney } from '../../api'

const loading = ref(false)
const summary = ref<any>({})
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const status = ref('')

const drawer = ref(false)
const saving = ref(false)
const form = reactive<any>({
  name: '',
  category: '',
  location: '',
  department: '',
  acquisitionDate: new Date().toISOString().slice(0, 10),
  originalValue: 0,
  residualRatePercent: 5,
  usefulLifeMonths: 60,
  remark: '',
  postVoucher: true
})

const disposeDialog = ref(false)
const disposeTarget = ref<any>(null)
const disposeForm = reactive<any>({
  disposalDate: new Date().toISOString().slice(0, 10),
  amount: 0,
  remark: ''
})

const depreciateDialog = ref(false)
const depreciatePeriod = ref(new Date().toISOString().slice(0, 7))
const depreciating = ref(false)

const detailDrawer = ref(false)
const detailAsset = ref<any>(null)
const depreciations = ref<any[]>([])
const depreciationTotal = ref(0)
const depreciationPage = ref(1)

const loadSummary = async () => {
  summary.value = await api.get('/finance/fixed-assets/summary')
}
const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/finance/fixed-assets', {
      keyword: keyword.value || undefined,
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

const openCreate = () => {
  Object.assign(form, {
    name: '',
    category: '',
    location: '',
    department: '',
    acquisitionDate: new Date().toISOString().slice(0, 10),
    originalValue: 0,
    residualRatePercent: 5,
    usefulLifeMonths: 60,
    remark: '',
    postVoucher: true
  })
  drawer.value = true
}
const submit = async () => {
  if (!form.name) {
    ElMessage.warning('请填写资产名称')
    return
  }
  if (!form.originalValue || Number(form.originalValue) <= 0) {
    ElMessage.warning('请填写资产原值')
    return
  }
  saving.value = true
  try {
    await api.post('/finance/fixed-assets', {
      name: form.name,
      category: form.category,
      location: form.location,
      department: form.department,
      acquisitionDate: form.acquisitionDate,
      originalValue: Number(form.originalValue),
      residualRate: Number(form.residualRatePercent || 0) / 100,
      usefulLifeMonths: Number(form.usefulLifeMonths),
      remark: form.remark,
      postVoucher: form.postVoucher
    })
    ElMessage.success('资产卡片已创建')
    drawer.value = false
    await Promise.all([load(), loadSummary()])
  } finally {
    saving.value = false
  }
}

const runDepreciation = async () => {
  depreciating.value = true
  try {
    const result = await api.post('/finance/fixed-assets/depreciate', { period: depreciatePeriod.value })
    if (Number(result.assets) === 0) {
      ElMessage.info('该期间没有需要计提的资产（可能已计提过）')
    } else {
      ElMessage.success(`计提完成：${result.assets} 台，金额 ¥${fmtMoney(result.amount)}，凭证 ${result.voucherNo}`)
    }
    depreciateDialog.value = false
    await Promise.all([load(), loadSummary()])
  } finally {
    depreciating.value = false
  }
}

const openDispose = (row: any) => {
  disposeTarget.value = row
  Object.assign(disposeForm, {
    disposalDate: new Date().toISOString().slice(0, 10),
    amount: Number(row.netValue || 0),
    remark: ''
  })
  disposeDialog.value = true
}
const submitDispose = async () => {
  await ElMessageBox.confirm(
    `处置资产 ${disposeTarget.value.assetNo}？会生成处置凭证，且不可撤销。`, '资产处置')
  saving.value = true
  try {
    const result = await api.post(`/finance/fixed-assets/${disposeTarget.value.id}/dispose`, {
      disposalDate: disposeForm.disposalDate,
      amount: Number(disposeForm.amount || 0),
      remark: disposeForm.remark
    })
    ElMessage.success(`处置完成，${Number(result.gainLoss) >= 0 ? '收益' : '损失'} ¥${fmtMoney(Math.abs(Number(result.gainLoss)))}`)
    disposeDialog.value = false
    await Promise.all([load(), loadSummary()])
  } finally {
    saving.value = false
  }
}

const openDepreciations = async (row: any) => {
  detailAsset.value = row
  depreciationPage.value = 1
  await loadDepreciations()
  detailDrawer.value = true
}
const loadDepreciations = async () => {
  const data = await api.get('/finance/fixed-assets/depreciations', {
    assetId: detailAsset.value.id,
    page: depreciationPage.value,
    size: 12
  })
  depreciations.value = data.records || []
  depreciationTotal.value = data.total || 0
}

onMounted(async () => {
  await Promise.all([load(), loadSummary()])
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2>固定资产</h2>
      <div class="toolbar-actions">
        <el-button v-perm="'finance:asset:depreciate'" @click="depreciateDialog = true">计提折旧</el-button>
        <el-button v-perm="'finance:asset'" type="primary" @click="openCreate">新增资产</el-button>
      </div>
    </div>

    <div class="summary">
      <div class="summary-card">
        <div class="label">在用资产</div>
        <div class="value">{{ summary.inUse || 0 }} 台</div>
      </div>
      <div class="summary-card">
        <div class="label">资产原值</div>
        <div class="value money">¥{{ fmtMoney(summary.originalValue) }}</div>
      </div>
      <div class="summary-card">
        <div class="label">累计折旧</div>
        <div class="value money">¥{{ fmtMoney(summary.accumulated) }}</div>
      </div>
      <div class="summary-card">
        <div class="label">资产净值</div>
        <div class="value money">¥{{ fmtMoney(summary.netValue) }}</div>
      </div>
    </div>

    <div class="toolbar">
      <el-input v-model="keyword" placeholder="资产编号 / 名称 / 部门 / 类别" clearable style="width: 280px"
                @keyup.enter="page = 1; load()" />
      <el-select v-model="status" clearable placeholder="状态" style="width: 130px">
        <el-option label="在用" value="IN_USE" />
        <el-option label="已处置" value="DISPOSED" />
      </el-select>
      <el-button @click="page = 1; load()">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column prop="assetNo" label="资产编号" width="150" />
      <el-table-column prop="name" label="资产名称" min-width="160" show-overflow-tooltip />
      <el-table-column prop="category" label="类别" width="110" />
      <el-table-column prop="department" label="部门" width="110" />
      <el-table-column prop="acquisitionDate" label="购置日期" width="110" />
      <el-table-column label="原值" width="120" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.originalValue) }}</template>
      </el-table-column>
      <el-table-column label="累计折旧" width="120" align="right">
        <template #default="{ row }">¥{{ fmtMoney(row.accumulatedDepreciation) }}</template>
      </el-table-column>
      <el-table-column label="净值" width="120" align="right">
        <template #default="{ row }"><span class="money">¥{{ fmtMoney(row.netValue) }}</span></template>
      </el-table-column>
      <el-table-column label="使用月数" width="95" align="right">
        <template #default="{ row }">{{ row.usefulLifeMonths }}</template>
      </el-table-column>
      <el-table-column label="开始折旧" width="110">
        <template #default="{ row }">{{ row.depreciationStart }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'DISPOSED' ? 'info' : 'success'">
            {{ row.status === 'DISPOSED' ? '已处置' : '在用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDepreciations(row)">折旧明细</el-button>
          <el-button v-if="row.status === 'IN_USE'" v-perm="'finance:asset'"
                     link type="danger" @click="openDispose(row)">处置</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
                   layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 12px"
                   @current-change="load" />

    <el-dialog v-model="drawer" title="新增资产" width="680px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="资产名称" required>
            <el-input v-model="form.name" />
          </el-form-item>
          <el-form-item label="资产类别">
            <el-input v-model="form.category" placeholder="如：生产设备 / 办公设备" />
          </el-form-item>
          <el-form-item label="使用部门">
            <el-input v-model="form.department" />
          </el-form-item>
          <el-form-item label="存放位置">
            <el-input v-model="form.location" />
          </el-form-item>
          <el-form-item label="购置日期" required>
            <el-date-picker v-model="form.acquisitionDate" type="date" value-format="YYYY-MM-DD"
                            style="width: 100%" />
          </el-form-item>
          <el-form-item label="资产原值" required>
            <el-input-number v-model="form.originalValue" :min="0" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="残值率（%）">
            <el-input-number v-model="form.residualRatePercent" :min="0" :max="99" :precision="2"
                             style="width: 100%" />
          </el-form-item>
          <el-form-item label="使用月数" required>
            <el-input-number v-model="form.usefulLifeMonths" :min="1" :max="600" style="width: 100%" />
          </el-form-item>
          <el-form-item label="生成购置凭证">
            <el-switch v-model="form.postVoucher" active-text="生成" inactive-text="不生成" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" />
          </el-form-item>
        </div>
        <div class="muted">
          折旧从购置次月起计提；期初导入的资产请关闭「生成购置凭证」，避免重复计入银行付款。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="depreciateDialog" title="计提折旧" width="420px">
      <el-form label-position="top">
        <el-form-item label="计提期间">
          <el-date-picker v-model="depreciatePeriod" type="month" value-format="YYYY-MM"
                          style="width: 100%" />
        </el-form-item>
      </el-form>
      <div class="muted">同一资产同一期间只计提一次；重复执行不会重复生成凭证。</div>
      <template #footer>
        <el-button @click="depreciateDialog = false">取消</el-button>
        <el-button type="primary" :loading="depreciating" @click="runDepreciation">计提</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="disposeDialog" title="资产处置" width="480px">
      <div v-if="disposeTarget" class="muted" style="margin-bottom: 10px">
        {{ disposeTarget.assetNo }} · {{ disposeTarget.name }} · 净值 ¥{{ fmtMoney(disposeTarget.netValue) }}
      </div>
      <el-form label-position="top">
        <el-form-item label="处置日期">
          <el-date-picker v-model="disposeForm.disposalDate" type="date" value-format="YYYY-MM-DD"
                          style="width: 100%" />
        </el-form-item>
        <el-form-item label="处置金额">
          <el-input-number v-model="disposeForm.amount" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="disposeForm.remark" placeholder="出售、报废等说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="disposeDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitDispose">确认处置</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" size="700px" title="折旧明细">
      <div v-if="detailAsset">
        <div class="detail-head">
          <div>
            <div class="detail-no mono">{{ detailAsset.assetNo }}</div>
            <div class="muted" style="margin-top: 4px">{{ detailAsset.name }}</div>
          </div>
          <el-tag :type="detailAsset.status === 'DISPOSED' ? 'info' : 'success'">
            {{ detailAsset.status === 'DISPOSED' ? '已处置' : '在用' }}
          </el-tag>
        </div>
        <div class="detail-grid">
          <div class="detail-item"><div class="label">资产原值</div><div class="value money">¥{{ fmtMoney(detailAsset.originalValue) }}</div></div>
          <div class="detail-item"><div class="label">累计折旧</div><div class="value money">¥{{ fmtMoney(detailAsset.accumulatedDepreciation) }}</div></div>
          <div class="detail-item"><div class="label">资产净值</div><div class="value money">¥{{ fmtMoney(detailAsset.netValue) }}</div></div>
          <div class="detail-item"><div class="label">残值率</div><div class="value">{{ (Number(detailAsset.residualRate) * 100).toFixed(2) }}%</div></div>
          <div class="detail-item"><div class="label">使用月数</div><div class="value">{{ detailAsset.usefulLifeMonths }}</div></div>
          <div class="detail-item"><div class="label">开始折旧</div><div class="value">{{ detailAsset.depreciationStart }}</div></div>
        </div>
        <div class="drawer-section">
          <h4>折旧记录</h4>
          <el-table v-loading="loading" :data="depreciations" size="small">
            <el-table-column prop="period" label="期间" width="100" />
            <el-table-column label="折旧金额" width="130" align="right">
              <template #default="{ row }">¥{{ fmtMoney(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="累计折旧" width="130" align="right">
              <template #default="{ row }">¥{{ fmtMoney(row.accumulatedAfter) }}</template>
            </el-table-column>
            <el-table-column prop="voucherNo" label="凭证号" width="150" />
            <el-table-column prop="createdAt" label="计提时间" min-width="140" />
          </el-table>
          <el-pagination v-model:current-page="depreciationPage" :page-size="12" :total="depreciationTotal"
                         layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 10px"
                         @current-change="loadDepreciations" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}
.summary-card {
  background: #fff;
  border: 1px solid #e4e7ec;
  border-radius: 10px;
  padding: 14px 16px;
}
.summary-card .label {
  color: #667085;
  font-size: 13px;
}
.summary-card .value {
  font-size: 20px;
  font-weight: 600;
  margin-top: 6px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}
.form-grid .full {
  grid-column: span 3;
}
</style>
