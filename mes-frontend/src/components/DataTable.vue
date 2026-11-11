<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'

/**
 * 通用列表表格。
 * 列由 useTableView 的列定义驱动渲染，显隐 / 宽度 / 冻结统一来自当前视图，
 * 页面只声明一次 columns，不再逐列手写 v-if + width + fixed 三绑定。
 *
 * 自定义单元格用同名插槽：
 *   <DataTable :table="table" :data="rows">
 *     <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
 *   </DataTable>
 * 未提供插槽的列自动渲染 row[prop]，空值显示 -。
 */
defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    /** useTableView 的返回值 */
    table: any
    data: any[]
    loading?: boolean
    height?: string | number
    rowKey?: string
    emptyText?: string
    stripe?: boolean
    /** 传入总数才渲染分页条，不传表示该页不分页（明细表格等） */
    total?: number
    page?: number
    size?: number
  }>(),
  {
    loading: false,
    height: '100%',
    rowKey: 'id',
    emptyText: '暂无数据',
    stripe: true
  }
)

const emit = defineEmits<{ (e: 'page-change', value: number): void }>()

const cols = computed(() => {
  const table = props.table
  // orderedDefs 是按视图里保存的列顺序排好的定义；老页面若没提供就退回声明顺序
  const defs = table?.orderedDefs?.value ?? table?.defs ?? []
  return defs
    .filter((def: any) => table.show(def.key))
    .map((def: any) => ({
      key: def.key,
      prop: def.prop === undefined ? def.key : def.prop,
      label: def.label,
      width: table.widthOf(def.key, def.width),
      fixed: table.fixedOf(def.key),
      align: def.align || table.alignOf(def.key),
      flex: Boolean(def.flex),
      ellipsis: Boolean(def.ellipsis)
    }))
})

const slotName = (key: string) => `cell-${key}`

const textOf = (row: any, prop: string) => {
  if (!prop) return ''
  const value = row?.[prop]
  if (value === null || value === undefined || value === '') return '-'
  return value
}

/**
 * 表头拖拽调宽：Element Plus 的拖拽手柄只在 border 模式下显示，
 * 所以 el-table 开了 border，再用样式把单元格竖线收回到表头。
 */
let lastWidthHint = 0
const onHeaderDragEnd = (newWidth: number, _oldWidth: number, column: any) => {
  const key = column?.columnKey
  if (!key) return
  props.table.setWidth(key, newWidth)
  const now = Date.now()
  if (now - lastWidthHint > 4000) {
    lastWidthHint = now
    ElMessage.info('列宽已调整，点「更新当前视图」可长期保留')
  }
}
</script>

<template>
  <el-table
    v-bind="$attrs"
    v-loading="loading"
    :data="data"
    :height="height"
    :stripe="stripe"
    :row-key="rowKey"
    :empty-text="emptyText"
    border
    class="data-table"
    @header-dragend="onHeaderDragEnd"
  >
    <el-table-column
      v-for="col in cols"
      :key="col.key"
      :column-key="col.key"
      :prop="col.prop || undefined"
      :label="col.label"
      :width="col.flex ? undefined : col.width"
      :min-width="col.flex ? col.width : undefined"
      :fixed="col.fixed"
      :align="col.align"
      :show-overflow-tooltip="col.ellipsis"
      :resizable="!col.fixed"
    >
      <template #default="scope">
        <slot :name="slotName(col.key)" v-bind="scope">{{ textOf(scope.row, col.prop) }}</slot>
      </template>
    </el-table-column>

    <!-- 空状态交给页面自己写：列表为空时告诉用户下一步该干什么 -->
    <template #empty>
      <slot name="empty">
        <div class="data-table-empty">{{ emptyText }}</div>
      </slot>
    </template>
  </el-table>

  <div v-if="total !== undefined" class="pager">
    <el-pagination
      layout="total, prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="(value: number) => emit('page-change', value)"
    />
  </div>
</template>

<style scoped>
.data-table-empty {
  padding: 22px 0;
  color: var(--muted);
  font-size: 13px;
}

/* border 模式只用于启用表头拖拽手柄，视觉上保留表头竖线、去掉单元格网格线 */
.data-table :deep(.el-table--border),
.data-table.el-table--border {
  border: none;
}

.data-table :deep(.el-table--border::after),
.data-table :deep(.el-table--border::before) {
  display: none;
}

.data-table :deep(.el-table__cell) {
  border-right: none;
}

.data-table :deep(th.el-table__cell) {
  border-right: 1px solid var(--line);
}

.data-table :deep(th.el-table__cell:last-child) {
  border-right: none;
}

/* 拖拽热区略微加宽，鼠标更容易抓到手柄 */
.data-table :deep(th.el-table__cell > .cell) {
  position: relative;
  padding-right: 14px;
}
</style>
