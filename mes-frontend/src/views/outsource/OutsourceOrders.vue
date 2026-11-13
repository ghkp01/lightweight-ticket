<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, fmtMoney, fmtQty } from '../../api'

/**
 * 外协管理：所有发出去做的工序都在这里，收回也在这里登记。
 *
 * 为什么不挂在工单页：外协是「我这单的某道工序在外面」，仓管和质检要按外协厂、按超期看，
 * 而不是一单一单翻。工单页只留入口，台账放这里。
 */

const route = useRoute()

const rows = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const status = ref('')
const overdueOnly = ref(false)
const page = ref(1)
const total = ref(0)
const size = 20
const summary = ref<any>({ open: 0, overdue: 0, openQty: 0, monthAmount: 0, totalAmount: 0 })

const detailVisible = ref(false)
const detail = ref<any>(null)
const detailLoading = ref(false)

const receiveVisible = ref(false)
const receiveSaving = ref(false)
const receiveTarget = ref<any>(null)
const receiveForm = reactive({
  qty: 0,
  defectQty: 0,
  receiver: '',
  remark: '',
  inspectionResult: '',
  inspectionRemark: ''
})

const statusOptions = [
  { value: 'SENT', label: '已发出' },
  { value: 'PARTIAL', label: '部分收回' },
  { value: 'RECEIVED', label: '已收回' }
]

const remaining = computed(() => {
  const target = receiveTarget.value
  if (!target) return 0
  return Number(target.qty || 0) - Number(target.receivedQty || 0)
})

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/outsource/orders', {
      keyword: keyword.value || undefined,
      status: status.value || undefined,
      overdueOnly: overdueOnly.value ? true : undefined,
      page: page.value,
      size
    })
    rows.value = data?.records || []
    total.value = Number(data?.total || 0)
    summary.value = (await api.get('/outsource/summary')) || summary.value
  } finally {
    loading.value = false
  }
}

const reload = () => {
  page.value = 1
  load()
}

const resetFilter = () => {
  keyword.value = ''
  status.value = ''
  overdueOnly.value = false
  page.value = 1
  load()
}

const openDetail = async (row: any) => {
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await api.get(`/outsource/orders/${row.id}`)
  } finally {
    detailLoading.value = false
  }
}

const openReceive = async (row: any) => {
  const data = await api.get(`/outsource/orders/${row.id}`)
  receiveTarget.value = data
  receiveForm.qty = Number(data?.qty || 0) - Number(data?.receivedQty || 0)
  receiveForm.defectQty = 0
  receiveForm.receiver = ''
  receiveForm.remark = ''
  receiveForm.inspectionResult = data?.inspectionRequired ? '' : 'PASS'
  receiveForm.inspectionRemark = ''
  receiveVisible.value = true
}

const submitReceive = async () => {
  if (!receiveTarget.value) return
  if (Number(receiveForm.qty || 0) <= 0) {
    ElMessage.warning('请填写收回数量')
    return
  }
  if (receiveTarget.value?.inspectionRequired && !receiveForm.inspectionResult) {
    ElMessage.warning('这道工序是检验点，请选择检验结论')
    return
  }
  receiveSaving.value = true
  try {
    const result = await api.post(`/outsource/orders/${receiveTarget.value.id}/receive`, {
      qty: Number(receiveForm.qty || 0),
      defectQty: Number(receiveForm.defectQty || 0),
      receiver: receiveForm.receiver || null,
      remark: receiveForm.remark || null,
      inspectionResult: receiveForm.inspectionResult || null,
      inspectionRemark: receiveForm.inspectionRemark || null
    })
    ElMessage.success(result?.finished ? '已收回，工单已完工' : '已登记收回')
    receiveVisible.value = false
    load()
  } catch {
    // 业务提示由接口层统一弹出
  } finally {
    receiveSaving.value = false
  }
}

const statusLabel = (value: string) =>
  ({ SENT: '已发出', PARTIAL: '部分收回', RECEIVED: '已收回' }[value] || value)
const statusCls = (value: string) =>
  ({ SENT: 'warning', PARTIAL: 'warning', RECEIVED: 'success' }[value] || 'info')

const changePage = (value: number) => {
  page.value = value
  load()
}

onMounted(() => {
  const preset = String(route.query.keyword || '')
  if (preset) keyword.value = preset
  load()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">外协管理</h2>
        <p class="page-sub">
          发出去做的工序都在这张台账上：谁做的、发了多少料、约定哪天回、回来几件。收回后工序完成数自动推进。
        </p>
      </div>
      <div class="page-actions">
        <el-select v-model="status" clearable placeholder="全部状态" style="width: 130px" @change="reload">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-checkbox v-model="overdueOnly" @change="reload">只看超期</el-checkbox>
        <el-input
          v-model="keyword"
          clearable
          placeholder="外协单号 / 工单号 / 工序"
          style="width: 210px"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-button @click="load">刷新</el-button>
        <el-button v-if="keyword || status || overdueOnly" @click="resetFilter">清空条件</el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-label">在外协厂</div>
        <div class="kpi-value">{{ summary.open || 0 }}<small>张单</small></div>
        <div class="kpi-foot">已发出未收完</div>
      </div>
      <div class="kpi-card" :class="{ 'kpi-alert': Number(summary.overdue) > 0 }">
        <div class="kpi-label">超期未回</div>
        <div class="kpi-value">{{ summary.overdue || 0 }}<small>张单</small></div>
        <div class="kpi-foot">过了约定回厂日</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">在制件数</div>
        <div class="kpi-value">{{ fmtQty(summary.openQty) }}</div>
        <div class="kpi-foot">发出减去已收回</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">本月外协费</div>
        <div class="kpi-value">{{ fmtMoney(summary.monthAmount) }}<small>元</small></div>
        <div class="kpi-foot">收回合格数 × 单价，累计 {{ fmtMoney(summary.totalAmount) }} 元</div>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" size="small" empty-text="还没有外协单">
      <el-table-column label="外协单号" width="155">
        <template #default="{ row }">
          <el-button link type="primary" class="mono" @click="openDetail(row)">{{ row.orderNo }}</el-button>
        </template>
      </el-table-column>
      <el-table-column label="工单 / 产品" min-width="190">
        <template #default="{ row }">
          <div class="mono">{{ row.workNo }}</div>
          <div class="muted" style="font-size: 11px">{{ row.productName || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="工序" width="115">
        <template #default="{ row }">{{ row.operationSeq }} · {{ row.operationName }}</template>
      </el-table-column>
      <el-table-column prop="supplierName" label="外协厂" min-width="150" show-overflow-tooltip />
      <el-table-column label="发出 / 收回" width="120" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtQty(row.qty) }} / {{ fmtQty(row.receivedQty) }}</span>
          <div v-if="Number(row.defectQty) > 0" class="muted" style="font-size: 11px">
            不良 {{ fmtQty(row.defectQty) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="单价 / 金额" width="125" align="right">
        <template #default="{ row }">
          <div class="num">{{ fmtMoney(row.price) }}</div>
          <div class="muted" style="font-size: 11px">{{ fmtMoney(row.amount) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="约定回厂" width="110">
        <template #default="{ row }">
          <span v-if="!row.expectedAt" class="muted">未约定</span>
          <span v-else :class="row.overdue ? 'danger-text' : ''">
            {{ row.expectedAt }}
            <span v-if="row.overdue">（超 {{ row.overdueDays }} 天）</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="95">
        <template #default="{ row }">
          <span class="status-tag" :class="row.overdue ? 'danger' : statusCls(row.status)">
            <i></i>{{ row.overdue ? '超期未回' : statusLabel(row.status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status !== 'RECEIVED'"
            v-perm="'production:outsource-receive'"
            link
            type="primary"
            @click="openReceive(row)"
          >
            登记收回
          </el-button>
          <el-button link @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        layout="total, prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="changePage"
      />
    </div>

    <el-drawer v-model="detailVisible" title="外协单详情" size="620px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-grid">
            <div class="detail-item">
              <div class="label">外协单号</div>
              <div class="value mono">{{ detail.orderNo }}</div>
            </div>
            <div class="detail-item">
              <div class="label">状态</div>
              <div class="value">
                <span class="status-tag" :class="statusCls(detail.status)">
                  <i></i>{{ statusLabel(detail.status) }}
                </span>
              </div>
            </div>
            <div class="detail-item">
              <div class="label">工单</div>
              <div class="value mono">{{ detail.workNo }}</div>
            </div>
            <div class="detail-item">
              <div class="label">产品</div>
              <div class="value">{{ detail.productName }}</div>
            </div>
            <div class="detail-item">
              <div class="label">工序</div>
              <div class="value">{{ detail.operationSeq }} · {{ detail.operationName }}</div>
            </div>
            <div class="detail-item">
              <div class="label">外协厂</div>
              <div class="value">{{ detail.supplierName || '-' }}</div>
            </div>
            <div class="detail-item">
              <div class="label">发出 / 收回</div>
              <div class="value num">{{ fmtQty(detail.qty) }} / {{ fmtQty(detail.receivedQty) }}</div>
            </div>
            <div class="detail-item">
              <div class="label">不良</div>
              <div class="value num">{{ fmtQty(detail.defectQty) }}</div>
            </div>
            <div class="detail-item">
              <div class="label">外协费</div>
              <div class="value num">{{ fmtMoney(detail.amount) }} 元</div>
            </div>
            <div class="detail-item">
              <div class="label">约定回厂</div>
              <div class="value">{{ detail.expectedAt || '未约定' }}</div>
            </div>
            <div class="detail-item">
              <div class="label">发出时间 / 人</div>
              <div class="value">{{ detail.sentAt || '-' }} {{ detail.sender || '' }}</div>
            </div>
            <div class="detail-item">
              <div class="label">收回时间 / 人</div>
              <div class="value">{{ detail.receivedAt || '-' }} {{ detail.receiver || '' }}</div>
            </div>
          </div>
          <div class="muted" v-if="detail.remark" style="margin: 8px 0">备注：{{ detail.remark }}</div>

          <h4 style="margin: 16px 0 8px">发出的料</h4>
          <el-table :data="detail.items || []" size="small" empty-text="这张单没有发料">
            <el-table-column label="物料" min-width="180">
              <template #default="{ row }">
                <div>{{ row.materialName }}</div>
                <div class="muted" style="font-size: 11px">{{ row.materialCode }} · {{ row.spec || '无规格' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="95" align="right">
              <template #default="{ row }">{{ fmtQty(row.qty) }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column label="批次" width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <span :class="row.fromStock ? '' : 'muted'">{{ row.batchSummary || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="120">
              <template #default="{ row }">
                <span class="status-tag" :class="row.fromStock ? 'success' : 'draft'">
                  <i></i>{{ row.fromStock ? '仓库发出' : '随件转出' }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="receiveVisible" title="登记外协收回" width="560px" append-to-body>
      <div class="section-note" style="margin-bottom: 12px">
        外协单 {{ receiveTarget?.orderNo }}，还剩 {{ fmtQty(remaining) }} 件没回。
        收回数量会累加到工序完成数；不良会记不良台账并扣减后道计划量。
      </div>
      <el-form label-width="96px">
        <el-form-item label="收回数量" required>
          <el-input-number v-model="receiveForm.qty" :min="0" :max="remaining" :precision="0" style="width: 150px" />
        </el-form-item>
        <el-form-item label="其中不良">
          <el-input-number v-model="receiveForm.defectQty" :min="0" :max="Number(receiveForm.qty || 0)" :precision="0" style="width: 150px" />
        </el-form-item>
        <el-form-item v-if="receiveTarget?.inspectionRequired" label="检验结论" required>
          <el-radio-group v-model="receiveForm.inspectionResult">
            <el-radio value="PASS">合格</el-radio>
            <el-radio value="FAIL">不合格</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="receiveTarget?.inspectionRequired" label="检验说明">
          <el-input v-model="receiveForm.inspectionRemark" placeholder="例如：外观抽检 10 件全检" />
        </el-form-item>
        <el-form-item label="收料人">
          <el-input v-model="receiveForm.receiver" placeholder="默认当前登录人" style="width: 200px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="receiveForm.remark" type="textarea" :rows="2" placeholder="例如：分两车回厂，本车 20 件" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="receiveVisible = false">取消</el-button>
        <el-button type="primary" :loading="receiveSaving" @click="submitReceive">确认收回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  padding: 16px 20px 24px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}
.page-title {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 600;
}
.page-sub {
  margin: 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
  max-width: 720px;
}
.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.kpi-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 14px;
  background: #fff;
}
.kpi-card.kpi-alert {
  border-color: #f56c6c;
}
.kpi-label {
  font-size: 12px;
  color: #909399;
}
.kpi-value {
  font-size: 22px;
  font-weight: 600;
  margin: 4px 0;
}
.kpi-value small {
  font-size: 12px;
  font-weight: 400;
  margin-left: 3px;
  color: #909399;
}
.kpi-foot {
  font-size: 11px;
  color: #909399;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
}
.detail-item .label {
  font-size: 11px;
  color: #909399;
}
.detail-item .value {
  font-size: 13px;
  margin-top: 2px;
}
.danger-text {
  color: #f56c6c;
}
</style>
