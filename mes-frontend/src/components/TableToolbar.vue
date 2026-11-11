<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Sortable from 'sortablejs'
import { api } from '../api'
import { datedFileName, downloadCsv } from '../utils/exportCsv'

const props = defineProps<{
  table: any
  /** 传了列表接口路径就显示「导出 CSV」，按当前视图的筛选条件拉全量再导出 */
  exportApi?: string
  /** 导出文件名（不含日期与后缀），默认「导出数据」 */
  exportName?: string
  /** 导出接口不返回分页结构时可用它自定义取数 */
  exportFetcher?: () => Promise<any[]>
  /** 导出前把行数据转成表格列能识别的形状（如展开 JSONB 自定义字段） */
  exportTransform?: (rows: any[]) => any[]
}>()
const emit = defineEmits<{ (e: 'applied'): void }>()

const exporting = ref(false)

/** 导出时最多拉这么多行，避免一个误点把服务拉爆 */
const EXPORT_MAX = 5000
/** 单页取多少：部分接口会对 size 做上限裁剪，所以按页循环拉，不指望一次拿全 */
const EXPORT_PAGE_SIZE = 200

const fetchAll = async (): Promise<any[]> => {
  if (props.exportFetcher) {
    return await props.exportFetcher()
  }
  if (!props.exportApi) return []
  const filters = props.table?.snapshot?.().filters ?? {}
  const base: Record<string, any> = {}
  Object.entries(filters).forEach(([key, value]) => {
    if (value === '' || value === null || value === undefined) return
    if (Array.isArray(value)) {
      if (value.length === 2) {
        // 日期区间在列表参数里统一拆成 from / to
        base.from = value[0] || undefined
        base.to = value[1] || undefined
      }
      return
    }
    base[key] = value
  })

  const first = await api.get(props.exportApi, { ...base, page: 1, size: EXPORT_PAGE_SIZE })
  if (Array.isArray(first)) {
    return first.slice(0, EXPORT_MAX)
  }
  const rows: any[] = [...(first?.records || [])]
  const total = Number(first?.total || rows.length)
  let page = 1
  while (rows.length < total && rows.length < EXPORT_MAX) {
    page += 1
    const next = await api.get(props.exportApi, { ...base, page, size: EXPORT_PAGE_SIZE })
    const records = next?.records || []
    if (!records.length) break
    rows.push(...records)
  }
  return rows.slice(0, EXPORT_MAX)
}

const doExport = async () => {
  exporting.value = true
  try {
    const raw = await fetchAll()
    const rows = props.exportTransform ? props.exportTransform(raw) : raw
    if (!rows.length) {
      ElMessage.warning('当前条件下没有可导出的数据')
      return
    }
    const count = downloadCsv(props.table, rows, datedFileName(props.exportName || '导出数据'))
    ElMessage.success(`已导出 ${count} 行${rows.length >= EXPORT_MAX ? '（已达上限，请缩小筛选范围）' : ''}`)
  } finally {
    exporting.value = false
  }
}

const panel = ref(false)
const saving = ref(false)
const viewName = ref('')

// composable 返回的是 ref / computed，props 里的嵌套 ref 在模板中不会自动解包，
// 这里统一取出来，模板与脚本都读解包后的值
const views = computed(() => props.table.views?.value ?? [])
const activeView = computed(() => props.table.activeView?.value ?? null)
const activeId = computed(() => props.table.activeId?.value ?? null)
const visibleCount = computed(() => props.table.visibleCount?.value ?? 0)
const orderedDefs = computed(() => props.table.orderedDefs?.value ?? props.table.defs ?? [])
/** 内置视角：页面声明了才显示，点了只切列显隐，不写库 */
const presets = computed<any[]>(() => props.table.presets ?? [])
const activePreset = computed(() => props.table.activePreset?.value ?? null)

// 常见筛选字段的中文名；未登记的字段直接显示 key，避免猜错
const FILTER_LABELS: Record<string, string> = {
  keyword: '关键词',
  status: '状态',
  type: '类型',
  result: '检验结果',
  supplierId: '供应商',
  customerId: '客户',
  warehouseId: '仓库',
  productId: '产品',
  category: '分类',
  module: '模块',
  operator: '操作人',
  enabled: '启用状态',
  sourceType: '来源',
  bizType: '业务类型',
  storageType: '存储方式',
  range: '日期范围',
  success: '执行结果',
  validOnly: '只看有效价格',
  onlyWarning: '只看预警',
  dataScope: '数据范围'
}

// 常见业务状态码的中文名；未登记的按原值展示，避免猜错业务含义
const VALUE_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  RELEASED: '已下达',
  SCHEDULED: '已排产',
  IN_PROGRESS: '生产中',
  COMPLETED: '已完成',
  FINISHED: '已完工',
  CLOSED: '已关闭',
  CANCELLED: '已取消',
  PENDING: '待处理',
  PENDING_QC: '待检',
  OPEN: '待处理',
  QUOTED: '已报价',
  SELECTED: '已选定',
  MATCHED: '已匹配',
  PARTIAL: '部分完成',
  PAID: '已付清',
  SHIPPED: '已发货',
  SIGNED: '已签收',
  READY_TO_SHIP: '待发货',
  PASS: '合格',
  FAIL: '不合格',
  NORMAL: '普通',
  URGENT: '加急',
  CRITICAL: '特急',
  MANUAL: '手工',
  SCRAP: '报废',
  OTHER: '其他',
  ISSUE_OTHER: '车间领用',
  RAW: '原料仓',
  FINISHED_GOODS: '成品仓',
  REWORK: '返修'
}

const formatFilterValue = (value: any) => {
  if (Array.isArray(value)) {
    return value.filter((item) => item !== '' && item !== null).join(' ~ ') || '-'
  }
  if (typeof value === 'boolean') return value ? '是' : '否'
  return VALUE_LABELS[String(value)] || String(value)
}

/** 当前会被一起保存的查询条件，让用户看得见「默认查询条件」到底存了什么 */
const filterEntries = computed(() => {
  const filters = props.table.snapshot?.().filters ?? {}
  return Object.entries(filters)
    .filter(([, value]) => {
      if (value === '' || value === null || value === undefined) return false
      if (Array.isArray(value)) return value.some((item) => item !== '' && item !== null)
      return true
    })
    .map(([key, value]) => ({
      key,
      label: FILTER_LABELS[key] || key,
      value: formatFilterValue(value)
    }))
})

// 列顺序拖拽：Sortable 提供跟手拖影、让位缓动与占位样式
const colListRef = ref<HTMLElement | null>(null)
let sortable: Sortable | null = null

const initSortable = () => {
  const el = colListRef.value
  if (!el || sortable) return
  sortable = Sortable.create(el, {
    animation: 200,
    easing: 'cubic-bezier(0.2, 0, 0, 1)',
    draggable: '.col-row',
    // 只有左侧把手是拖拽入口，行内其它位置保留文本选中与控件操作
    handle: '.col-drag',
    ghostClass: 'col-ghost',
    chosenClass: 'col-chosen',
    dragClass: 'col-dragging',
    scroll: true,
    bubbleScroll: true,
    onEnd: (evt: any) => {
      if (evt.oldIndex === undefined || evt.newIndex === undefined) return
      if (evt.oldIndex === evt.newIndex) return
      const movedKey = props.table.order.value[evt.oldIndex]
      props.table.moveColumn(evt.oldIndex, evt.newIndex)
      // 冻结列会被表格钉在两端，拖到中间时自动取消冻结，否则看起来像没拖动
      const item = props.table.layout[movedKey]
      const lastIndex = props.table.order.value.length - 1
      let hint = ''
      if (item?.fixed === 'left' && evt.newIndex > 0) {
        item.fixed = ''
        hint = '已取消该列左侧冻结'
      } else if (item?.fixed === 'right' && evt.newIndex < lastIndex) {
        item.fixed = ''
        hint = '已取消该列右侧冻结'
      }
      props.table.reflowOrder()
      if (hint) ElMessage.info(`${hint}，冻结列固定显示在两端`)
    }
  })
}

const destroySortable = () => {
  sortable?.destroy()
  sortable = null
}

const initWithRetry = (retry = 0) => {
  if (colListRef.value) {
    initSortable()
    return
  }
  if (retry < 10) setTimeout(() => initWithRetry(retry + 1), 60)
}

watch(panel, async (open) => {
  if (open) {
    await nextTick()
    // 抽屉内容首次打开才挂载，轮询几帧等容器就绪再绑定
    initWithRetry()
  } else {
    destroySortable()
  }
})

onBeforeUnmount(destroySortable)

const resetOrder = () => {
  props.table.resetOrder()
  ElMessage.success('列顺序已恢复默认')
}

const currentName = computed(() => activeView.value?.name || '默认视图')

const onCommand = (command: any) => {
  if (command === 'save') {
    openPanel()
    return
  }
  if (command === 'reset') {
    resetAll()
    return
  }
  applyView(Number(command))
}

const openPanel = () => {
  viewName.value = activeView.value ? `${activeView.value.name} 副本` : '我的视图'
  panel.value = true
}

const applyView = (id: number) => {
  props.table.applyView(id)
  ElMessage.success('已切换视图')
  emit('applied')
}

const applyPreset = (key: string) => {
  props.table.applyPreset(key)
}

const saveAs = async (asDefault: boolean) => {
  const name = viewName.value.trim()
  if (name.length < 2) {
    ElMessage.warning('视图名称至少 2 个字')
    return
  }
  saving.value = true
  try {
    await props.table.saveAs(name, asDefault)
    ElMessage.success(
      asDefault
        ? '已保存并设为默认视图，下次打开自动应用列设置与查询条件'
        : '视图已保存，可在视图列表里把它设为默认'
    )
    viewName.value = `${name} 副本`
    emit('applied')
  } finally {
    saving.value = false
  }
}

const updateActive = async () => {
  saving.value = true
  try {
    await props.table.updateActive()
    ElMessage.success('当前视图已更新')
  } finally {
    saving.value = false
  }
}

const setDefault = async (id: number) => {
  await props.table.setDefault(id)
  ElMessage.success('已设为默认视图，下次打开自动应用')
}

const removeView = async (row: any) => {
  await ElMessageBox.confirm(`删除视图「${row.name}」？`, '删除视图', { type: 'warning' })
  await props.table.remove(row.id)
  ElMessage.success('视图已删除')
  emit('applied')
}

const resetAll = () => {
  props.table.reset()
  ElMessage.success('已恢复内置默认列设置')
  emit('applied')
}

const toggleVisible = (key: string) => {
  const item = props.table.layout[key]
  if (!item) return
  if (item.visible && visibleCount.value <= 1) {
    ElMessage.warning('至少保留一列显示')
    return
  }
  item.visible = !item.visible
}

const setFixed = (key: string, value: string) => {
  const item = props.table.layout[key]
  if (item) {
    item.fixed = value as 'left' | 'right' | ''
    // 冻结后该列会被表格贴到对应边缘，列表顺序跟着调整，避免设置面板与表格对不上
    props.table.reflowOrder()
  }
}

const setWidth = (key: string, value: any) => {
  const item = props.table.layout[key]
  if (item && Number(value) > 0) {
    item.width = Number(value)
  }
}
</script>

<template>
  <div class="table-toolbar">
    <el-dropdown trigger="click" @command="onCommand">
      <el-button link type="primary">
        视图：{{ currentName }}
      </el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-for="item in views" :key="item.id" :command="item.id">
            {{ item.name }}
            <span v-if="item.isDefault" class="view-badge">默认</span>
          </el-dropdown-item>
          <el-dropdown-item v-if="!views.length" disabled>还没有保存的视图</el-dropdown-item>
          <el-dropdown-item divided command="save">另存为视图…</el-dropdown-item>
          <el-dropdown-item command="reset">恢复内置默认</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <el-button link @click="openPanel">列设置</el-button>
    <span v-if="presets.length" class="preset-group">
      <el-button
        v-for="item in presets"
        :key="item.key"
        size="small"
        plain
        :type="activePreset === item.key ? 'primary' : undefined"
        :title="item.hint || ''"
        @click="applyPreset(item.key)"
      >
        {{ item.label }}
      </el-button>
    </span>
    <el-button v-if="exportApi || exportFetcher" link :loading="exporting" @click="doExport">
      导出 CSV
    </el-button>
  </div>

  <el-drawer append-to-body v-model="panel" size="520px" title="列设置与视图">
    <div class="tool-section">
      <div class="tool-title">
        <span>当前视图</span>
        <span class="tool-tip">{{ activeView ? '修改后记得更新当前视图' : '当前为内置默认设置' }}</span>
      </div>
      <div class="tool-row">
        <el-input v-model="viewName" maxlength="40" placeholder="视图名称，例如「本月待到货」" />
      </div>
      <div class="tool-actions">
        <el-button type="primary" :loading="saving" @click="saveAs(false)">保存为新视图</el-button>
        <el-button :disabled="!activeView" :loading="saving" @click="updateActive">更新当前视图</el-button>
        <el-button :loading="saving" @click="saveAs(true)">保存并设为默认</el-button>
      </div>
    </div>

    <div class="tool-section">
      <div class="tool-title">
        <span>默认查询条件</span>
        <span class="tool-tip">随视图一起保存，打开页面自动回填并查询</span>
      </div>
      <div v-if="!filterEntries.length" class="tool-empty">
        当前没有筛选条件，只保存列设置；填好筛选再保存即可一并记住
      </div>
      <div v-for="item in filterEntries" :key="item.key" class="filter-item">
        <span class="filter-label">{{ item.label }}</span>
        <span class="filter-value">{{ item.value }}</span>
      </div>
    </div>

    <div class="tool-section">
      <div class="tool-title"><span>已保存视图</span></div>
      <div v-if="!views.length" class="tool-empty">还没有保存的视图，先在上面起个名字保存一份</div>
      <div v-for="item in views" :key="item.id" class="view-item">
        <div class="view-name">
          {{ item.name }}
          <span v-if="item.isDefault" class="view-badge">默认</span>
          <span v-if="activeId === item.id" class="view-active">使用中</span>
        </div>
        <div class="view-actions">
          <el-button link type="primary" @click="applyView(item.id)">应用</el-button>
          <el-button v-if="!item.isDefault" link @click="setDefault(item.id)">设默认</el-button>
          <el-button link type="danger" @click="removeView(item)">删除</el-button>
        </div>
      </div>
    </div>

    <div class="tool-section">
      <div class="tool-title">
        <span>列设置</span>
        <span class="tool-tip">拖动左侧把手调整顺序，可控制显示、冻结与列宽</span>
      </div>
      <div class="col-head">
        <span class="col-drag-ghost"></span>
        <span class="col-name">列</span>
        <span class="col-fixed">冻结</span>
        <span class="col-width">宽度</span>
      </div>
      <div ref="colListRef" class="col-list">
        <div v-for="def in orderedDefs" :key="def.key" class="col-row">
          <span class="col-drag" draggable="true" title="按住拖动调整顺序">⠿</span>
          <div class="col-name">
            <el-checkbox
              :model-value="table.layout[def.key]?.visible !== false"
              @change="toggleVisible(def.key)"
            />
            <span class="col-label">{{ def.label }}</span>
          </div>
          <el-select
            class="col-fixed"
            :model-value="table.layout[def.key]?.fixed || ''"
            placeholder="不冻结"
            size="small"
            @change="(value) => setFixed(def.key, value)"
          >
            <el-option label="不冻结" value="" />
            <el-option label="冻结左侧" value="left" />
            <el-option label="冻结右侧" value="right" />
          </el-select>
          <el-input-number
            class="col-width"
            :model-value="table.layout[def.key]?.width"
            :min="60"
            :max="800"
            :step="10"
            size="small"
            controls-position="right"
            @change="(value) => setWidth(def.key, value)"
          />
        </div>
      </div>
    </div>

    <template #footer>
      <div class="drawer-foot">
        <div>
          <el-button @click="resetOrder">恢复默认顺序</el-button>
          <el-button @click="resetAll">恢复内置默认</el-button>
        </div>
        <el-button type="primary" @click="panel = false">完成</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.table-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
}

.preset-group {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: 2px;
}

.view-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 8px;
  background: #eef4ff;
  color: #1d4ed8;
  font-size: 10px;
  line-height: 16px;
}

.view-active {
  margin-left: 6px;
  color: #16a34a;
  font-size: 11px;
}

.tool-section {
  padding-bottom: 16px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.tool-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 600;
}

.tool-tip {
  color: var(--muted);
  font-size: 11px;
  font-weight: 400;
}

.tool-row {
  margin-bottom: 10px;
}

.tool-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tool-empty {
  padding: 8px 0;
  color: var(--muted);
  font-size: 12px;
}

.filter-item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 5px 0;
  font-size: 12px;
}

.filter-label {
  color: var(--muted);
  flex: none;
}

.filter-value {
  color: var(--text);
  font-weight: 500;
  text-align: right;
  word-break: break-all;
}

.view-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  margin-bottom: 8px;
}

.view-name {
  font-size: 13px;
}

.view-actions {
  display: flex;
  gap: 2px;
}

.col-head,
.col-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.col-head {
  padding: 0 2px 6px;
  color: var(--muted);
  font-size: 11px;
}

.col-row {
  padding: 5px 0;
}

.col-name {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 4px;
}

.col-drag-ghost,
.col-drag {
  flex: 0 0 18px;
}

.col-drag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 26px;
  color: #c0c4cc;
  font-size: 13px;
  line-height: 1;
  cursor: grab;
  user-select: none;
  border-radius: 4px;
  transition: color 0.15s ease, background 0.15s ease;
}

.col-drag:hover {
  color: #1d4ed8;
  background: #eef4ff;
}

.col-drag:active {
  cursor: grabbing;
}

.col-list {
  display: flex;
  flex-direction: column;
}

.col-row {
  transition: background 0.15s ease, box-shadow 0.15s ease;
  border-radius: 6px;
}

.col-row:hover {
  background: #f8fafc;
}

/* 拖动过程中留在原位的占位块 */
.col-row.col-ghost {
  background: #eff6ff;
  border-radius: 6px;
  box-shadow: inset 0 0 0 1px #93c5fd;
}

.col-row.col-ghost > * {
  visibility: hidden;
}

.col-row.col-chosen {
  background: #f8fafc;
  border-radius: 6px;
}

/* 跟随鼠标的拖影与正在拖动的行 */
.col-row.col-dragging {
  opacity: 0.9;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.18);
}

.col-label {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.col-fixed {
  flex: 0 0 100px;
}

.col-width {
  flex: 0 0 110px;
}

.drawer-foot {
  display: flex;
  justify-content: space-between;
}
</style>
