<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtMoney } from '../../api'

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const yearFilter = ref<number | null>(new Date().getFullYear())
const keyword = ref('')

const summary = ref<any>({})

const projectDialog = ref(false)
const projectSaving = ref(false)
const projectForm = reactive<any>({
  name: '',
  taxYear: new Date().getFullYear(),
  responsible: '',
  remark: ''
})

const expenseDrawer = ref(false)
const currentProject = ref<any>(null)
const expenses = ref<any[]>([])
const expenseTotal = ref(0)
const expensePage = ref(1)
const expenseDialog = ref(false)
const expenseSaving = ref(false)
const expenseForm = reactive<any>({
  expenseDate: new Date().toISOString().slice(0, 10),
  category: 'PERSONNEL',
  amount: 0,
  remark: ''
})

const categoryLabel: Record<string, string> = {
  PERSONNEL: '人员人工',
  MATERIAL: '直接投入',
  DEPRECIATION: '折旧费用',
  AMORTIZATION: '无形资产摊销',
  OTHER: '其他'
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/finance/rd-projects', {
      year: yearFilter.value || undefined,
      keyword: keyword.value || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}
const loadSummary = async () => {
  if (!yearFilter.value) {
    summary.value = {}
    return
  }
  summary.value = await api.get('/finance/rd-projects/summary', { year: yearFilter.value })
}

const openProjectDialog = () => {
  Object.assign(projectForm, {
    name: '',
    taxYear: yearFilter.value || new Date().getFullYear(),
    responsible: '',
    remark: ''
  })
  projectDialog.value = true
}
const submitProject = async () => {
  if (!projectForm.name) {
    ElMessage.warning('请填写项目名称')
    return
  }
  projectSaving.value = true
  try {
    await api.post('/finance/rd-projects', {
      name: projectForm.name,
      taxYear: projectForm.taxYear,
      responsible: projectForm.responsible,
      remark: projectForm.remark
    })
    ElMessage.success('研发项目已创建')
    projectDialog.value = false
    await Promise.all([load(), loadSummary()])
  } finally {
    projectSaving.value = false
  }
}

const openProject = async (row: any) => {
  currentProject.value = row
  expensePage.value = 1
  await loadExpenses()
  expenseDrawer.value = true
}
const loadExpenses = async () => {
  const data = await api.get(`/finance/rd-projects/${currentProject.value.id}/expenses`, {
    page: expensePage.value,
    size: 20
  })
  expenses.value = data.records || []
  expenseTotal.value = data.total || 0
}
const openExpenseDialog = () => {
  Object.assign(expenseForm, {
    expenseDate: `${currentProject.value.taxYear}-${new Date().toISOString().slice(5, 10)}`,
    category: 'PERSONNEL',
    amount: 0,
    remark: ''
  })
  expenseDialog.value = true
}
const submitExpense = async () => {
  if (!expenseForm.amount || Number(expenseForm.amount) <= 0) {
    ElMessage.warning('请填写归集金额')
    return
  }
  expenseSaving.value = true
  try {
    await api.post(`/finance/rd-projects/${currentProject.value.id}/expenses`, {
      expenseDate: expenseForm.expenseDate,
      category: expenseForm.category,
      amount: Number(expenseForm.amount),
      remark: expenseForm.remark
    })
    ElMessage.success('费用已归集')
    expenseDialog.value = false
    await Promise.all([loadExpenses(), load(), loadSummary()])
  } finally {
    expenseSaving.value = false
  }
}
const deleteExpense = async (row: any) => {
  await ElMessageBox.confirm('删除这条研发费用？', '删除确认')
  await api.delete(`/finance/rd-projects/expenses/${row.id}`)
  ElMessage.success('已删除')
  await Promise.all([loadExpenses(), load(), loadSummary()])
}

const closeProject = async (row: any) => {
  await ElMessageBox.confirm(`关闭研发项目 ${row.name}？关闭后不能再归集费用。`, '关闭项目')
  await api.post(`/finance/rd-projects/${row.id}/close`)
  ElMessage.success('项目已关闭')
  await load()
}

onMounted(async () => {
  await Promise.all([load(), loadSummary()])
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2>研发费用辅助账</h2>
      <div class="toolbar-actions">
        <el-button v-perm="'finance:tax'" type="primary" @click="openProjectDialog">新建研发项目</el-button>
      </div>
    </div>

    <div class="summary">
      <div class="summary-card">
        <div class="label">研发项目</div>
        <div class="value">{{ summary.projectCount || 0 }} 个</div>
      </div>
      <div class="summary-card">
        <div class="label">研发费用合计（{{ yearFilter }}）</div>
        <div class="value money">¥{{ fmtMoney(summary.total) }}</div>
      </div>
      <div class="summary-card">
        <div class="label">加计扣除（按 100%）</div>
        <div class="value money">¥{{ fmtMoney(Number(summary.total || 0)) }}</div>
      </div>
    </div>

    <div class="toolbar">
      <el-input-number v-model="yearFilter" :min="2000" :max="2100" placeholder="年度"
                       controls-position="right" style="width: 140px"
                       @change="page = 1; load(); loadSummary()" />
      <el-input v-model="keyword" placeholder="项目编号 / 名称 / 负责人" clearable style="width: 260px"
                @keyup.enter="page = 1; load()" />
      <el-button @click="page = 1; load(); loadSummary()">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" size="small">
      <el-table-column prop="projectNo" label="项目编号" width="150" />
      <el-table-column prop="name" label="项目名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="taxYear" label="年度" width="90" />
      <el-table-column prop="responsible" label="负责人" width="110" />
      <el-table-column label="归集金额" width="140" align="right">
        <template #default="{ row }"><b class="money">¥{{ fmtMoney(row.totalExpense) }}</b></template>
      </el-table-column>
      <el-table-column label="笔数" width="80" align="right">
        <template #default="{ row }">{{ row.expenseCount }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'CLOSED' ? 'info' : 'success'">
            {{ row.status === 'CLOSED' ? '已关闭' : '进行中' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openProject(row)">费用明细</el-button>
          <el-button v-if="row.status !== 'CLOSED'" v-perm="'finance:tax'"
                     link type="warning" @click="closeProject(row)">关闭</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
                   layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 12px"
                   @current-change="load" />

    <el-dialog v-model="projectDialog" title="新建研发项目" width="520px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="项目名称" required class="full">
            <el-input v-model="projectForm.name" />
          </el-form-item>
          <el-form-item label="研发年度" required>
            <el-input-number v-model="projectForm.taxYear" :min="2000" :max="2100"
                             style="width: 100%" />
          </el-form-item>
          <el-form-item label="负责人">
            <el-input v-model="projectForm.responsible" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="projectForm.remark" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="projectDialog = false">取消</el-button>
        <el-button type="primary" :loading="projectSaving" @click="submitProject">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="expenseDrawer" size="720px" title="研发费用明细">
      <div v-if="currentProject">
        <div class="detail-head">
          <div>
            <div class="detail-no mono">{{ currentProject.projectNo }}</div>
            <div class="muted" style="margin-top: 4px">
              {{ currentProject.name }} · {{ currentProject.taxYear }} 年
            </div>
          </div>
          <div style="display: flex; align-items: center; gap: 8px">
            <el-button v-if="currentProject.status !== 'CLOSED'" v-perm="'finance:tax'"
                       size="small" type="primary" @click="openExpenseDialog">归集费用</el-button>
            <el-tag :type="currentProject.status === 'CLOSED' ? 'info' : 'success'">
              {{ currentProject.status === 'CLOSED' ? '已关闭' : '进行中' }}
            </el-tag>
          </div>
        </div>
        <div class="detail-grid">
          <div class="detail-item"><div class="label">负责人</div><div class="value">{{ currentProject.responsible || '-' }}</div></div>
          <div class="detail-item"><div class="label">归集金额</div><div class="value money">¥{{ fmtMoney(currentProject.totalExpense) }}</div></div>
          <div class="detail-item"><div class="label">费用笔数</div><div class="value">{{ currentProject.expenseCount }}</div></div>
        </div>
        <div class="drawer-section">
          <h4>费用明细</h4>
          <div v-if="!expenses.length" class="muted">还没有归集费用</div>
          <el-table v-else :data="expenses" size="small">
            <el-table-column prop="expenseDate" label="日期" width="110" />
            <el-table-column label="类别" width="130">
              <template #default="{ row }">{{ categoryLabel[row.category] || row.category }}</template>
            </el-table-column>
            <el-table-column label="金额" width="130" align="right">
              <template #default="{ row }">¥{{ fmtMoney(row.amount) }}</template>
            </el-table-column>
            <el-table-column prop="remark" label="说明" min-width="150" />
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button v-perm="'finance:tax'" link type="danger" @click="deleteExpense(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination v-model:current-page="expensePage" :page-size="20" :total="expenseTotal"
                         layout="total, prev, pager, next" style="justify-content: flex-end; margin-top: 10px"
                         @current-change="loadExpenses" />
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="expenseDialog" title="归集研发费用" width="460px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="费用日期" required>
            <el-date-picker v-model="expenseForm.expenseDate" type="date" value-format="YYYY-MM-DD"
                            style="width: 100%" />
          </el-form-item>
          <el-form-item label="费用类别">
            <el-select v-model="expenseForm.category" style="width: 100%">
              <el-option v-for="(label, key) in categoryLabel" :key="key" :label="label" :value="key" />
            </el-select>
          </el-form-item>
          <el-form-item label="金额" required>
            <el-input-number v-model="expenseForm.amount" :min="0" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="说明" class="full">
            <el-input v-model="expenseForm.remark" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="expenseDialog = false">取消</el-button>
        <el-button type="primary" :loading="expenseSaving" @click="submitExpense">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
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
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.form-grid .full {
  grid-column: span 2;
}
</style>
