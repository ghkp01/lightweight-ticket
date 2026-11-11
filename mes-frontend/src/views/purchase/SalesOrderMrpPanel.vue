<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtQty } from '../../api'

/**
 * 销售订单 MRP：按交期先到先得占用成品库存（ATP 预留），
 * 再逐层展开，自动生成成品/半成品工单与采购申请。
 * 全局运行会把所有已确认、未生成工单的订单合并计算，半成品与采购需求只下一批单。
 */

const loading = ref(false)
const rows = ref<any[]>([])
const runningId = ref<number | null>(null)
const globalRunning = ref(false)
const dialog = ref(false)
const result = ref<any>(null)

const load = async () => {
  loading.value = true
  try {
    rows.value = await api.get('/mrp/sales-orders/open')
  } finally {
    loading.value = false
  }
}

const showResult = (data: any) => {
  result.value = data
  dialog.value = true
}

const runOrder = async (row: any) => {
  await ElMessageBox.confirm(
    `对订单 ${row.orderNo} 运行 MRP？成品库存先按交期预留，再逐层展开；自制件生成工单，外购件生成采购申请。`,
    '运行 MRP'
  )
  runningId.value = row.id
  try {
    showResult(await api.post(`/mrp/sales-orders/${row.id}/run`))
    await load()
  } finally {
    runningId.value = null
  }
}

const runGlobal = async () => {
  if (!rows.value.length) {
    ElMessage.warning('没有待运行的订单：需要状态为已确认、且还没生成过工单')
    return
  }
  await ElMessageBox.confirm(
    `将汇总 ${rows.value.length} 张已确认订单：成品库存按交期先到先得做 ATP 预留，半成品与采购需求按物料合并后只生成一批单据。确认运行？`,
    '全局 MRP'
  )
  globalRunning.value = true
  try {
    showResult(await api.post('/mrp/sales-orders/global/run'))
    await load()
  } finally {
    globalRunning.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="panel">
    <div class="filter-bar">
      <span class="toolbar-tip">
        只列出已确认、还没生成工单的订单；成品库存按交期先到先得预留，重复运行不会重复占库存
      </span>
      <div class="spacer"></div>
      <el-button plain :loading="loading" @click="load">刷新订单</el-button>
      <el-button
        v-perm="'production'"
        type="primary"
        :loading="globalRunning"
        :disabled="!rows.length"
        @click="runGlobal"
      >
        全局 MRP（{{ rows.length }} 张）
      </el-button>
    </div>

    <div class="table-wrap">
      <el-table
        v-loading="loading"
        :data="rows"
        height="100%"
        size="small"
        empty-text="没有待运行的订单：已确认订单都已生成工单，或当前没有已确认订单"
      >
        <el-table-column label="订单号" width="190">
          <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
        </el-table-column>
        <el-table-column label="客户" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.customerName || '—' }}</template>
        </el-table-column>
        <el-table-column label="交期" width="120">
          <template #default="{ row }"><span class="mono">{{ row.deliveryDate || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="已预留成品库存" width="150" align="right">
          <template #default="{ row }">
            <b v-if="Number(row.reservedQty) > 0" class="num">{{ fmtQty(row.reservedQty) }}</b>
            <span v-else class="muted">0</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button
              v-perm="'production'"
              link
              type="primary"
              :loading="runningId === row.id"
              @click="runOrder(row)"
            >运行 MRP</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog append-to-body draggable v-model="dialog" title="MRP 运算结果" width="1080px">
      <div v-if="result">
        <div v-if="result.orderCount !== undefined" class="mrp-summary">
          全局 MRP · 订单 <b>{{ result.orderCount }}</b> 张 · 库存预留
          <b>{{ fmtQty(result.reservedQty) }}</b> · 生成工单
          <b>{{ result.createdWorkOrders }}</b> 张 · 采购申请
          <b>{{ result.createdRequisitions }}</b> 张
        </div>
        <div v-else class="mrp-summary">
          订单 <b class="mono">{{ result.orderNo }}</b> · 交期
          <b class="mono">{{ result.deliveryDate }}</b> · 状态
          <b>{{ result.status }}</b> · 库存预留
          <b>{{ fmtQty(result.reservedQty) }}</b> · 生成工单
          <b>{{ result.createdWorkOrders }}</b> 张 · 采购申请
          <b>{{ result.createdRequisitions }}</b> 张
        </div>

        <template v-if="result.bomPending?.length">
          <el-alert
            class="bom-pending"
            type="warning"
            :closable="false"
            show-icon
            :title="`有 ${result.bomPending.length} 张订单因 BOM 未就绪被跳过，本次没有为它们生成工单`"
          >
            <div v-for="row in result.bomPending" :key="row.salesOrderId" class="bom-pending-line">
              <span class="mono">{{ row.orderNo }}</span>：{{ row.productCodes }}
            </div>
          </el-alert>
        </template>

        <template v-if="result.orders">
          <h4 class="mrp-title">订单与库存预留（ATP）</h4>
          <el-table :data="result.orders" size="small" empty-text="没有待运行的订单">
            <el-table-column label="订单号" width="180">
              <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
            </el-table-column>
            <el-table-column label="交期" width="110" prop="deliveryDate" />
            <el-table-column label="需求" width="100" align="right">
              <template #default="{ row }">{{ fmtQty(row.demandQty) }}</template>
            </el-table-column>
            <el-table-column label="库存预留" width="110" align="right">
              <template #default="{ row }">{{ fmtQty(row.reservedQty) }}</template>
            </el-table-column>
            <el-table-column label="净生产" width="110" align="right">
              <template #default="{ row }">
                <b :class="Number(row.netQty) > 0 ? 'danger-text' : 'muted'">{{ fmtQty(row.netQty) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110" prop="status" />
          </el-table>
        </template>

        <h4 class="mrp-title">成品净需求</h4>
        <el-table :data="result.finishedRows" size="small" empty-text="没有成品需求">
          <el-table-column v-if="result.orders" label="订单号" width="170">
            <template #default="{ row }"><span class="mono">{{ row.orderNo }}</span></template>
          </el-table-column>
          <el-table-column label="物料" min-width="200">
            <template #default="{ row }">
              <div>{{ row.productName }}</div>
              <div class="mono muted" style="font-size: 11px">{{ row.productCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="订单需求" width="110" align="right">
            <template #default="{ row }">{{ fmtQty(row.demandQty) }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="可用库存" width="110" align="right">
            <template #default="{ row }">{{ fmtQty(row.stockQty) }}</template>
          </el-table-column>
          <el-table-column label="本单预留" width="110" align="right">
            <template #default="{ row }">{{ fmtQty(row.reservedQty) }}</template>
          </el-table-column>
          <el-table-column label="净生产" width="110" align="right">
            <template #default="{ row }">
              <b :class="Number(row.netQty) > 0 ? 'danger-text' : 'muted'">{{ fmtQty(row.netQty) }}</b>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="80">
            <template #default="{ row }">{{ row.source === 'MAKE' ? '自制' : '外购' }}</template>
          </el-table-column>
        </el-table>

        <el-tabs style="margin-top: 12px">
          <el-tab-pane :label="`净生产（${result.make.length} 项）`">
            <el-table :data="result.make" size="small" empty-text="没有自制需求">
              <el-table-column label="层" width="50" align="center" prop="level" />
              <el-table-column label="物料" min-width="190">
                <template #default="{ row }">
                  <div>{{ row.productName }}</div>
                  <div class="mono muted" style="font-size: 11px">{{ row.productCode }}</div>
                </template>
              </el-table-column>
              <el-table-column label="毛需求" width="100" align="right">
                <template #default="{ row }">{{ fmtQty(row.grossNeed) }}</template>
              </el-table-column>
              <el-table-column label="库存" width="95" align="right">
                <template #default="{ row }">{{ fmtQty(row.stock) }}</template>
              </el-table-column>
              <el-table-column label="工单占用" width="95" align="right">
                <template #default="{ row }"><span class="muted">{{ fmtQty(row.committed) }}</span></template>
              </el-table-column>
              <el-table-column label="安全库存" width="95" align="right">
                <template #default="{ row }"><span class="muted">{{ fmtQty(row.safetyStock) }}</span></template>
              </el-table-column>
              <el-table-column label="净生产" width="100" align="right">
                <template #default="{ row }">
                  <b :class="Number(row.netNeed) > 0 ? 'danger-text' : 'muted'">{{ fmtQty(row.netNeed) }}</b>
                </template>
              </el-table-column>
              <el-table-column label="需求日" width="105" prop="needDate" />
              <el-table-column label="建议开工" width="105" prop="suggestStart" />
              <el-table-column label="提前期" width="75" align="right">
                <template #default="{ row }">{{ row.leadDays }} 天</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`采购需求（${result.buy.length} 项）`">
            <el-table :data="result.buy" size="small" empty-text="没有采购需求">
              <el-table-column label="层" width="50" align="center" prop="level" />
              <el-table-column label="物料" min-width="190">
                <template #default="{ row }">
                  <div>{{ row.productName }}</div>
                  <div class="mono muted" style="font-size: 11px">{{ row.productCode }}</div>
                </template>
              </el-table-column>
              <el-table-column label="毛需求" width="100" align="right">
                <template #default="{ row }">{{ fmtQty(row.grossNeed) }}</template>
              </el-table-column>
              <el-table-column label="库存" width="95" align="right">
                <template #default="{ row }">{{ fmtQty(row.stock) }}</template>
              </el-table-column>
              <el-table-column label="工单占用" width="95" align="right">
                <template #default="{ row }"><span class="muted">{{ fmtQty(row.committed) }}</span></template>
              </el-table-column>
              <el-table-column label="安全库存" width="95" align="right">
                <template #default="{ row }"><span class="muted">{{ fmtQty(row.safetyStock) }}</span></template>
              </el-table-column>
              <el-table-column label="在途" width="90" align="right">
                <template #default="{ row }">{{ fmtQty(row.onWay) }}</template>
              </el-table-column>
              <el-table-column label="已申请" width="90" align="right">
                <template #default="{ row }">{{ fmtQty(row.pending) }}</template>
              </el-table-column>
              <el-table-column label="净采购" width="100" align="right">
                <template #default="{ row }">
                  <b :class="Number(row.netNeed) > 0 ? 'danger-text' : 'muted'">{{ fmtQty(row.netNeed) }}</b>
                </template>
              </el-table-column>
              <el-table-column label="需求日" width="105" prop="needDate" />
              <el-table-column label="建议下单" width="105" prop="suggestOrderDate" />
              <el-table-column label="提前期" width="75" align="right">
                <template #default="{ row }">{{ row.leadDays }} 天</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>

        <h4 class="mrp-title">已生成单据</h4>
        <div class="mrp-docs">
          <div>
            <span class="muted">生产工单：</span>
            <template v-if="result.workOrders.length">
              <el-tag v-for="work in result.workOrders" :key="work.id" size="small" style="margin: 0 6px 6px 0">
                {{ work.workNo }} · {{ work.productName }} × {{ fmtQty(work.qty) }}
              </el-tag>
            </template>
            <span v-else class="muted">无</span>
          </div>
          <div>
            <span class="muted">采购申请：</span>
            <template v-if="result.requisitions.length">
              <el-tag v-for="req in result.requisitions" :key="req.id" size="small" type="warning" style="margin: 0 6px 6px 0">
                {{ req.reqNo }} · {{ req.productName }} × {{ fmtQty(req.qty) }}
              </el-tag>
            </template>
            <span v-else class="muted">无</span>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="dialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.panel {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel > .filter-bar {
  flex: none;
}

.panel > .table-wrap {
  flex: 1;
  min-height: 0;
}

.mrp-summary {
  padding: 10px 12px;
  margin-bottom: 12px;
  background: #f8fafc;
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 13px;
}

.bom-pending {
  margin-bottom: 12px;
}

.bom-pending-line {
  margin-top: 4px;
  font-size: 12px;
}

.mrp-title {
  margin: 14px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.mrp-docs {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  background: #fbfcfe;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  font-size: 12px;
}
</style>
