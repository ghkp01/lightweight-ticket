<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, fmtQty } from '../../api'

/**
 * 多层测算：在工单缺料的基础上把半成品继续拆到原料。
 * 半成品给「该开工单多少」，原料给「扣掉在途与已申请后还要买多少」。
 */

const loading = ref(false)
const summary = ref<any>({})
const make = ref<any[]>([])
const buy = ref<any[]>([])
const activeTab = ref('make')

const form = reactive({
  includeScheduled: false,
  horizonDays: 0,
  maxLevel: 3
})

const makeShortage = computed(() =>
  make.value.filter((row) => Number(row.shortage || 0) > 0)
)
const buyShortage = computed(() => buy.value.filter((row) => Number(row.shortage || 0) > 0))

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/mrp/explode', {
      includeScheduled: form.includeScheduled,
      horizonDays: form.horizonDays || undefined,
      maxLevel: form.maxLevel
    })
    make.value = data?.make || []
    buy.value = data?.buy || []
    summary.value = data?.summary || {}
  } finally {
    loading.value = false
  }
}

// ---------------- 半成品转工单
const workDialog = ref(false)
const workRow = ref<any>(null)
const working = ref(false)
const workForm = reactive<any>({ qty: 0, planStart: '', planEnd: '' })

const today = () => {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

const openWork = (row: any) => {
  workRow.value = row
  workForm.qty = Number(row.shortage || 0) > 0 ? Number(row.shortage) : Number(row.grossNeed || 0)
  workForm.planStart = row.needDate || today()
  workForm.planEnd = row.needDate || today()
  workDialog.value = true
}

const createWorkOrder = async () => {
  if (!workForm.qty || Number(workForm.qty) <= 0) {
    ElMessage.warning('请填写开工数量')
    return
  }
  working.value = true
  try {
    const id = await api.post('/work-orders', {
      productId: workRow.value.productId,
      planQty: Number(workForm.qty),
      planStart: workForm.planStart || null,
      planEnd: workForm.planEnd || null
    })
    ElMessage.success(`已为 ${workRow.value.productCode} 生成工单${id ? `（ID ${id}）` : ''}，请到生产工单里排产下达`)
    workDialog.value = false
  } finally {
    working.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="panel multi-panel">
    <div class="filter-bar">
      <el-switch v-model="form.includeScheduled" active-text="含已排产" @change="load" />
      <el-select v-model="form.horizonDays" style="width: 150px" @change="load">
        <el-option label="全部需求日" :value="0" />
        <el-option label="7 天内" :value="7" />
        <el-option label="14 天内" :value="14" />
        <el-option label="30 天内" :value="30" />
      </el-select>
      <el-select v-model="form.maxLevel" style="width: 150px" @change="load">
        <el-option v-for="level in 5" :key="level" :label="`展开 ${level} 层`" :value="level" />
      </el-select>
      <div class="spacer"></div>
      <span class="toolbar-tip">半成品只有在「BOM 管理」里配了下层 BOM 才会继续展开</span>
      <el-button plain :loading="loading" @click="load">重新计算</el-button>
    </div>

    <div v-loading="loading" class="multi-body">
      <div class="kpi-grid kpi-4">
        <div class="kpi-card">
          <div class="kpi-label">参与物料</div>
          <div class="kpi-value">{{ summary.demandRows ?? 0 }}<small>个</small></div>
          <div class="kpi-foot">展开 {{ summary.maxLevel ?? form.maxLevel }} 层后的物料数</div>
        </div>
        <div class="kpi-card" :class="{ 'kpi-alert': Number(summary.makeShortageRows || 0) > 0 }">
          <div class="kpi-label">半成品缺口</div>
          <div class="kpi-value">{{ summary.makeShortageRows ?? 0 }}<small>项</small></div>
          <div class="kpi-foot">需要开工单补的自制件</div>
        </div>
        <div class="kpi-card" :class="{ 'kpi-alert': Number(summary.buyShortageRows || 0) > 0 }">
          <div class="kpi-label">原料缺口</div>
          <div class="kpi-value">{{ summary.buyShortageRows ?? 0 }}<small>项</small></div>
          <div class="kpi-foot">需要采购的物料</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">自制 / 采购</div>
          <div class="kpi-value">{{ summary.makeRows ?? 0 }}<small>/ {{ summary.buyRows ?? 0 }}</small></div>
          <div class="kpi-foot">半成品行数 / 原料行数</div>
        </div>
      </div>

      <el-tabs v-model="activeTab">
        <el-tab-pane :label="`自制需求（${makeShortage.length} 项缺口）`" name="make">
          <div class="table-wrap">
            <el-table :data="make" size="small" empty-text="没有半成品需求：成品 BOM 里没有半成品，或半成品没配下层 BOM">
              <el-table-column label="层级" width="70" align="center">
                <template #default="{ row }"><span class="num">{{ row.level }}</span></template>
              </el-table-column>
              <el-table-column label="半成品" min-width="200">
                <template #default="{ row }">
                  <div>{{ row.productName }}</div>
                  <div class="sub mono">{{ row.productCode }}</div>
                </template>
              </el-table-column>
              <el-table-column label="毛需求" width="110" align="right">
                <template #default="{ row }">
                  <span class="num">{{ fmtQty(row.grossNeed) }} {{ row.unit }}</span>
                </template>
              </el-table-column>
              <el-table-column label="现有库存" width="110" align="right">
                <template #default="{ row }"><span class="num muted">{{ fmtQty(row.stock) }}</span></template>
              </el-table-column>
              <el-table-column label="建议开工" width="110" align="right">
                <template #default="{ row }">
                  <b v-if="Number(row.shortage) > 0" class="num danger-text">{{ fmtQty(row.shortage) }}</b>
                  <span v-else class="muted">已覆盖</span>
                </template>
              </el-table-column>
              <el-table-column label="需求日" width="110">
                <template #default="{ row }"><span class="mono">{{ row.needDate }}</span></template>
              </el-table-column>
              <el-table-column label="建议开工日" width="110">
                <template #default="{ row }"><span class="mono">{{ row.suggestStart || '—' }}</span></template>
              </el-table-column>
              <el-table-column label="提前期" width="80" align="right">
                <template #default="{ row }">{{ row.leadDays || 0 }} 天</template>
              </el-table-column>
              <el-table-column label="上层来源" min-width="200" show-overflow-tooltip>
                <template #default="{ row }"><span class="muted">{{ row.parents }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openWork(row)">生成工单</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="`采购需求（${buyShortage.length} 项缺口）`" name="buy">
          <div class="table-wrap">
            <el-table :data="buy" size="small" empty-text="没有原料需求">
              <el-table-column label="层级" width="70" align="center">
                <template #default="{ row }"><span class="num">{{ row.level }}</span></template>
              </el-table-column>
              <el-table-column label="物料" min-width="200">
                <template #default="{ row }">
                  <div>{{ row.productName }}</div>
                  <div class="sub mono">{{ row.productCode }}</div>
                </template>
              </el-table-column>
              <el-table-column label="毛需求" width="110" align="right">
                <template #default="{ row }">
                  <span class="num">{{ fmtQty(row.grossNeed) }} {{ row.unit }}</span>
                </template>
              </el-table-column>
              <el-table-column label="库存可用" width="105" align="right">
                <template #default="{ row }"><span class="num">{{ fmtQty(row.stock) }}</span></template>
              </el-table-column>
              <el-table-column label="采购在途" width="105" align="right">
                <template #default="{ row }"><span class="num muted">{{ fmtQty(row.onWay) }}</span></template>
              </el-table-column>
              <el-table-column label="已申请未转" width="110" align="right">
                <template #default="{ row }"><span class="num muted">{{ fmtQty(row.pending) }}</span></template>
              </el-table-column>
              <el-table-column label="缺口" width="110" align="right">
                <template #default="{ row }">
                  <b v-if="Number(row.shortage) > 0" class="num danger-text">{{ fmtQty(row.shortage) }}</b>
                  <span v-else class="muted">已覆盖</span>
                </template>
              </el-table-column>
              <el-table-column label="需求日" width="110">
                <template #default="{ row }"><span class="mono">{{ row.needDate }}</span></template>
              </el-table-column>
              <el-table-column label="建议下单日" width="110">
                <template #default="{ row }"><span class="mono">{{ row.suggestOrderDate || '—' }}</span></template>
              </el-table-column>
              <el-table-column label="提前期" width="80" align="right">
                <template #default="{ row }">{{ row.leadDays || 0 }} 天</template>
              </el-table-column>
              <el-table-column label="上层来源" min-width="200" show-overflow-tooltip>
                <template #default="{ row }"><span class="muted">{{ row.parents }}</span></template>
              </el-table-column>
            </el-table>
          </div>
          <div class="muted note">
            原料缺口还没有算安全库存，需要带安全库存下单时请用「工单缺料」页的净需求口径。
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog append-to-body draggable v-model="workDialog" title="生成半成品工单" width="460px">
      <el-form label-position="top">
        <el-form-item label="半成品">
          <el-input :model-value="`${workRow?.productCode || ''} · ${workRow?.productName || ''}`" disabled />
        </el-form-item>
        <el-form-item label="开工数量" required>
          <el-input-number v-model="workForm.qty" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="计划开始">
          <el-date-picker v-model="workForm.planStart" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="计划完成">
          <el-date-picker v-model="workForm.planEnd" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <div class="dialog-tip">生成的是草稿工单，生成后到「生产工单」里排产并下达。</div>
      </el-form>
      <template #footer>
        <el-button @click="workDialog = false">取消</el-button>
        <el-button type="primary" :loading="working" @click="createWorkOrder">生成工单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.multi-panel {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.multi-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.kpi-grid.kpi-4 {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 12px;
}

@media (max-width: 1100px) {
  .kpi-grid.kpi-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.sub {
  font-size: 11px;
  color: var(--muted);
}

.note {
  font-size: 12px;
  line-height: 1.8;
  padding: 8px 2px 0;
}

.dialog-tip {
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}
</style>
