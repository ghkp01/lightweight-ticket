<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SalesOrderMrpPanel from './SalesOrderMrpPanel.vue'
import MrpSinglePanel from './MrpSinglePanel.vue'
import MrpMultiPanel from './MrpMultiPanel.vue'

/**
 * 物料需求统一入口：
 * - 销售订单 MRP：从订单出发，做库存预留、逐层展开并生成工单/采购申请；
 * - 工单缺料：已有工单还缺什么、建议买多少；
 * - 多层测算：把半成品继续拆到原料，评估该开多少半成品工单。
 */

const route = useRoute()
const router = useRouter()
const tabs = ['sales', 'workorder', 'multi']
const initial = String(route.query.tab || 'sales')
const tab = ref(tabs.includes(initial) ? initial : 'sales')

watch(() => route.query.tab, (value) => {
  if (value && tabs.includes(String(value))) {
    tab.value = String(value)
  }
})

const onTabChange = (name: any) => {
  router.replace({ path: '/purchase/mrp', query: { ...route.query, tab: String(name) } })
}
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">物料需求</h2>
        <p class="page-sub">
          销售订单 MRP 生成执行单据，工单缺料看现有工单还差什么，多层测算把半成品继续拆到原料
        </p>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="销售订单 MRP" name="sales" lazy>
        <SalesOrderMrpPanel />
      </el-tab-pane>
      <el-tab-pane label="工单缺料" name="workorder" lazy>
        <MrpSinglePanel />
      </el-tab-pane>
      <el-tab-pane label="多层测算" name="multi" lazy>
        <MrpMultiPanel />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
