<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, fmtQty } from '../../api'

/**
 * 工单缺料：把未完工工单的 BOM 待领量汇总起来，
 * 扣掉库存可用、采购在途、已申请未转单与安全库存，给出「缺多少、什么时候要、建议哪天下单」。
 * 系统只给建议，采购员确认后再一键生成采购申请。
 */

const loading = ref(false)
const saving = ref(false)
const rows = ref<any[]>([])
const summary = ref<any>({ materialCount: 0, shortageCount: 0, overdueCount: 0, totalGapQty: 0, rowCount: 0 })
const selected = ref<any[]>([])
const calculatedAt = ref('')

const form = reactive({
  includeScheduled: false,
  onlyShortage: true,
  horizonDays: 0,
  needDate: '',
  department: '',
  remark: ''
})

const selectedQty = computed(() =>
  selected.value.reduce((sum, row) => sum + Number(row.suggestQty || 0), 0)
)

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/mrp/run', {
      includeScheduled: form.includeScheduled,
      onlyShortage: form.onlyShortage,
      horizonDays: form.horizonDays || undefined
    })
    rows.value = data?.rows || []
    summary.value = data?.summary || summary.value
    calculatedAt.value = data?.calculatedAt || ''
    selected.value = []
  } finally {
    loading.value = false
  }
}

const onSelectionChange = (selection: any[]) => {
  selected.value = selection
}

const fillAll = () => {
  rows.value.forEach((row) => {
    if (Number(row.gapQty) > 0 && !row.suggestQty) row.suggestQty = Number(row.gapQty)
  })
}

const createRequisition = async () => {
  if (!selected.value.length) {
    ElMessage.warning('请先勾选要采购的物料')
    return
  }
  const items = selected.value
    .filter((row) => Number(row.suggestQty) > 0)
    .map((row) => ({
      productId: row.productId,
      qty: Number(row.suggestQty),
      suggestSupplierId: row.suggestSupplierId || undefined,
      targetPrice: row.suggestPrice || 0,
      remark: `需求日 ${row.needDate}，建议下单 ${row.suggestOrderDate}`
    }))
  if (!items.length) {
    ElMessage.warning('勾选行的建议数量都是 0，请先填写采购数量')
    return
  }
  const needDates = selected.value.map((row) => String(row.needDate)).sort()
  const needDate = form.needDate || needDates[0]
  saving.value = true
  try {
    const result = await api.post('/mrp/requisitions', {
      needDate,
      department: form.department || undefined,
      remark: form.remark || undefined,
      items
    })
    ElMessage.success(`已生成采购申请 ${result?.reqNo || ''}（${result?.itemCount || items.length} 项），已派「转采购单」任务，提交审批后按已批准的行转单`)
    selected.value = []
    await load()
  } finally {
    saving.value = false
  }
}

const rowClass = ({ row }: any) => (row.overdue ? 'mrp-overdue' : '')

onMounted(load)
</script>

<template>
  <div class="mrp-tab">
    <div class="mrp-strip">
      <div class="strip-item">
        <span>参与计算物料</span>
        <b class="num">{{ summary.materialCount }}</b>
      </div>
      <div class="strip-item" :class="{ alert: Number(summary.shortageCount) > 0 }">
        <span>有缺口物料</span>
        <b class="num">{{ summary.shortageCount }}</b>
      </div>
      <div class="strip-item" :class="{ danger: Number(summary.overdueCount) > 0 }">
        <span>建议下单日已过</span>
        <b class="num">{{ summary.overdueCount }}</b>
      </div>
      <div class="strip-item">
        <span>缺口合计</span>
        <b class="num">{{ fmtQty(summary.totalGapQty) }}</b>
      </div>
      <div class="strip-tip">
        {{ calculatedAt ? `计算日期 ${calculatedAt} · ` : '' }}缺口 = 毛需求 + 安全库存 − 库存可用 − 采购在途 − 已申请未转单
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-switch v-model="form.includeScheduled" active-text="含已排产" @change="load" />
        <el-switch v-model="form.onlyShortage" active-text="只看缺料" @change="load" />
        <el-select v-model="form.horizonDays" style="width: 150px" @change="load">
          <el-option label="全部需求日" :value="0" />
          <el-option label="7 天内" :value="7" />
          <el-option label="14 天内" :value="14" />
          <el-option label="30 天内" :value="30" />
        </el-select>
        <el-divider direction="vertical" />
        <el-input v-model="form.department" placeholder="申请部门（选填）" clearable style="width: 160px" />
        <el-input v-model="form.remark" placeholder="说明（选填）" clearable style="width: 220px" />
        <div class="spacer"></div>
        <el-button link type="primary" @click="fillAll">按缺口填充数量</el-button>
        <span class="muted" style="font-size: 12px">
          已选 {{ selected.length }} 项 · 合计 {{ fmtQty(selectedQty) }}
        </span>
        <el-button plain :loading="loading" @click="load">重新计算</el-button>
        <el-button type="primary" :loading="saving" @click="createRequisition">
          生成采购申请{{ selected.length ? `（${selected.length} 项）` : '' }}
        </el-button>
      </div>

      <div class="table-wrap">
        <el-table
          v-loading="loading"
          :data="rows"
          height="100%"
          size="small"
          :row-class-name="rowClass"
          empty-text="没有需要补的料：库存与在途已覆盖所有未完工工单的需求"
          @selection-change="onSelectionChange"
        >
          <el-table-column type="selection" width="42" />
          <el-table-column label="物料" min-width="170">
            <template #default="{ row }">
              <div class="cell-main">
                <b>{{ row.productName }}</b>
                <small class="mono muted">{{ row.productCode }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="规格" width="110" prop="spec" show-overflow-tooltip />
          <el-table-column label="毛需求" width="95" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.grossNeed) }}</span></template>
          </el-table-column>
          <el-table-column label="库存可用" width="95" align="right">
            <template #default="{ row }"><span class="num">{{ fmtQty(row.available) }}</span></template>
          </el-table-column>
          <el-table-column label="采购在途" width="95" align="right">
            <template #default="{ row }"><span class="num muted">{{ fmtQty(row.onWay) }}</span></template>
          </el-table-column>
          <el-table-column label="已申请未转" width="100" align="right">
            <template #default="{ row }"><span class="num muted">{{ fmtQty(row.pendingRequisition) }}</span></template>
          </el-table-column>
          <el-table-column label="安全库存" width="90" align="right">
            <template #default="{ row }"><span class="num muted">{{ fmtQty(row.safetyStock) }}</span></template>
          </el-table-column>
          <el-table-column label="缺口" width="95" align="right">
            <template #default="{ row }">
              <b v-if="Number(row.gapQty) > 0" class="num danger-text">{{ fmtQty(row.gapQty) }}</b>
              <span v-else class="muted">已覆盖</span>
            </template>
          </el-table-column>
          <el-table-column label="最早需求日" width="110">
            <template #default="{ row }"><span class="mono">{{ row.needDate }}</span></template>
          </el-table-column>
          <el-table-column label="建议下单日" width="120">
            <template #default="{ row }">
              <span class="mono" :class="{ 'danger-text': row.overdue }">{{ row.suggestOrderDate }}</span>
              <span v-if="row.overdue" class="status-tag danger late-tag"><i></i>已逾期</span>
            </template>
          </el-table-column>
          <el-table-column label="建议供应商" width="150">
            <template #default="{ row }">
              <span v-if="row.suggestSupplierName">{{ row.suggestSupplierName }}</span>
              <span v-else class="muted">未定</span>
              <small v-if="row.suggestPrice" class="muted mono"> ¥{{ row.suggestPrice }}</small>
            </template>
          </el-table-column>
          <el-table-column label="采购数量" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.suggestQty" :min="0" :precision="2" size="small" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="涉及工单" width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tooltip :content="row.workOrders || ''" placement="top">
                <span class="muted">{{ row.workOrderCount }} 张</span>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mrp-tab {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.mrp-tab > .mrp-strip {
  flex: none;
}

.mrp-tab > .panel {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.mrp-tab > .panel > .filter-bar {
  flex: none;
}

.mrp-tab > .panel > .table-wrap {
  flex: 1;
  min-height: 0;
}

.mrp-strip {
  display: flex;
  align-items: stretch;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid var(--line);
}

.strip-item {
  flex: 0 0 auto;
  min-width: 110px;
  padding: 6px 12px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid var(--line);
}

.strip-item span {
  display: block;
  font-size: 11px;
  color: var(--muted);
  margin-bottom: 3px;
}

.strip-item b {
  font-size: 17px;
  line-height: 1.1;
}

.strip-item.alert b {
  color: #b54708;
}

.strip-item.danger b {
  color: var(--danger);
}

.strip-tip {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  padding-left: 6px;
  font-size: 11px;
  line-height: 1.7;
  color: var(--muted);
}

.cell-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.cell-main b {
  font-size: 13px;
  font-weight: 600;
}

.cell-main small {
  font-size: 11px;
}

.late-tag {
  margin-left: 4px;
  font-size: 10px;
}

:deep(.mrp-overdue) {
  background: #fffbf5;
}
</style>
