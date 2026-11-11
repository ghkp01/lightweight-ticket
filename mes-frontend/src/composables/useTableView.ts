import { computed, reactive, ref } from 'vue'
import { api } from '../api'

/** 列定义：页面按渲染顺序声明，视图只保存显示/宽度/冻结，本期不支持拖拽换序 */
export interface ColumnDef {
  key: string
  label: string
  width: number
  fixed?: 'left' | 'right' | ''
  align?: 'left' | 'right' | 'center'
  /** 弹性列：按 min-width 渲染，剩余宽度由该列吸收 */
  flex?: boolean
  /** 内容超长时省略并在悬浮时显示完整内容 */
  ellipsis?: boolean
  /** 取值字段，默认与 key 相同；纯模板列可用空字符串关闭默认取值 */
  prop?: string
  /** 内置默认是否显示。次要列默认收起，用户想要时到列设置里打开 */
  visible?: boolean
}

/**
 * 内置视角：给同一张表预置几种「谁来看」的列组合。
 * 车间关心当前工序和待报数量，计划关心交期和进度，不用每次都去列设置里点。
 */
export interface ViewPreset {
  key: string
  label: string
  /** 只显示这些列，未列出的隐藏；不传表示保留列定义里的默认显隐 */
  visible?: string[]
  /** 在 visible 之外再补充隐藏的列 */
  hidden?: string[]
  /** 悬浮提示，说明这个视角给谁用 */
  hint?: string
}

export interface ColumnLayout {
  visible: boolean
  width: number
  fixed: 'left' | 'right' | ''
}

export interface ViewPayload {
  /** 列顺序，未保存过顺序的旧视图没有该字段 */
  order?: string[]
  columns: Array<{ key: string; visible: boolean; width: number; fixed: string }>
  filters: Record<string, any>
  pageSize: number
}

export interface SavedView {
  id: number
  pageKey: string
  name: string
  isDefault: boolean
  view: ViewPayload
  updatedAt?: string
}

export interface UseTableViewOptions {
  /** 页面唯一标识，例如 production.work-orders */
  pageKey: string
  columns: ColumnDef[]
  /** 内置视角，页面不声明就不显示切换入口 */
  presets?: ViewPreset[]
  /** 页面当前的筛选条件，保存视图时取这份 */
  getFilters?: () => Record<string, any>
  /** 应用视图时把筛选值回填到页面 */
  setFilters?: (filters: Record<string, any>) => void
  getPageSize?: () => number
  setPageSize?: (size: number) => void
}

const MIN_WIDTH = 60
const MAX_WIDTH = 800
const PAGE_SIZES = [10, 20, 50, 100]

export const useTableView = (options: UseTableViewOptions) => {
  const { pageKey } = options
  /** 列定义可动态扩展：页面加载完自定义字段后再调用 setColumns 追加列 */
  const defs = ref<ColumnDef[]>([...options.columns])
  const layout = reactive<Record<string, ColumnLayout>>({})
  /** 列的渲染顺序，与 layout 分开存：layout 只管单列属性，顺序单独维护 */
  const order = ref<string[]>([])
  const views = ref<SavedView[]>([])
  const activeId = ref<number | null>(null)
  /** 内置视角：点是哪个就是哪个，另存/应用用户视图后回到「自定义」 */
  const presets = options.presets || []
  const activePreset = ref<string | null>(null)
  const loading = ref(false)
  const error = ref('')

  const builtin = (key: string): ColumnLayout => {
    const def = defs.value.find((item) => item.key === key)
    return {
      // visible 为 false 的列默认收起，用户可以在列设置里打开
      visible: def ? def.visible !== false : true,
      width: def ? def.width : 140,
      fixed: (def?.fixed as ColumnLayout['fixed']) || ''
    }
  }

  const resetLayout = () => {
    Object.keys(layout).forEach((key) => delete layout[key])
    defs.value.forEach((def) => {
      layout[def.key] = builtin(def.key)
    })
    order.value = defs.value.map((def) => def.key)
  }
  resetLayout()

  /**
   * 替换列定义（页面可能在异步加载完配置后追加列）。
   * 已有列的显隐/宽度/顺序保持不变，新列按定义顺序补到末尾。
   */
  const setColumns = (next: ColumnDef[]) => {
    defs.value = next
    const known = new Set(next.map((def) => def.key))
    Object.keys(layout).forEach((key) => {
      if (!known.has(key)) delete layout[key]
    })
    next.forEach((def) => {
      if (!layout[def.key]) layout[def.key] = builtin(def.key)
    })
    order.value = normalizeOrder(order.value)
  }

  /** 视图里保存的顺序可能缺列（页面新增了列）或多列（页面删了列），这里按列定义校正 */
  const normalizeOrder = (raw: any): string[] => {
    const known = defs.value.map((def) => def.key)
    const saved = Array.isArray(raw)
      ? raw.filter((key: any) => typeof key === 'string' && known.includes(key))
      : []
    // 去重后保留已保存的相对顺序，再把新增列按定义顺序补到末尾
    const seen = new Set<string>()
    const result: string[] = []
    saved.forEach((key: string) => {
      if (!seen.has(key)) {
        seen.add(key)
        result.push(key)
      }
    })
    known.forEach((key) => {
      if (!seen.has(key)) result.push(key)
    })
    return result
  }

  const clampWidth = (value: any, fallback: number) => {
    const num = Number(value)
    if (!Number.isFinite(num)) return fallback
    return Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, Math.round(num)))
  }

  /** 按当前顺序排好的列定义，表格渲染与列设置面板都读这份 */
  const orderedDefs = computed(() => {
    const map = new Map(defs.value.map((def) => [def.key, def]))
    const list: ColumnDef[] = []
    order.value.forEach((key) => {
      const def = map.get(key)
      if (def) list.push(def)
    })
    defs.value.forEach((def) => {
      if (!order.value.includes(def.key)) list.push(def)
    })
    return list
  })

  /** 拖拽换序：把 from 位置的列移动到 to 位置 */
  const moveColumn = (from: number, to: number) => {
    const keys = [...order.value]
    if (from === to || from < 0 || to < 0 || from >= keys.length || to >= keys.length) return
    const [moved] = keys.splice(from, 1)
    keys.splice(to, 0, moved)
    order.value = keys
  }

  const resetOrder = () => {
    order.value = defs.value.map((def) => def.key)
  }

  /** 表头拖拽调宽后写回布局，宽度与列设置面板保持同一口径（60–800） */
  const setWidth = (key: string, width: number) => {
    const item = layout[key]
    if (!item) return
    item.width = clampWidth(width, item.width)
  }

  /**
   * 表格组件会把冻结列钉在左右两端，数据和视觉可能对不上。
   * 这里按「左冻结 → 不冻结 → 右冻结」重排一次，保持顺序与所见一致。
   */
  const reflowOrder = () => {
    const fixedOfKey = (key: string) => layout[key]?.fixed || ''
    const left = order.value.filter((key) => fixedOfKey(key) === 'left')
    const middle = order.value.filter((key) => !fixedOfKey(key))
    const right = order.value.filter((key) => fixedOfKey(key) === 'right')
    order.value = [...left, ...middle, ...right]
  }

  const normalize = (payload: any): ViewPayload => {
    const rawColumns = Array.isArray(payload?.columns) ? payload.columns : []
    const next = defs.value.map((def) => {
      const found = rawColumns.find((item: any) => item && item.key === def.key)
      return {
        key: def.key,
        visible: found ? found.visible !== false : def.visible !== false,
        width: clampWidth(found?.width, def.width),
        fixed: ['left', 'right'].includes(String(found?.fixed)) ? String(found?.fixed) : ''
      }
    })
    const filters = payload?.filters && typeof payload.filters === 'object' ? payload.filters : {}
    const size = PAGE_SIZES.includes(Number(payload?.pageSize)) ? Number(payload?.pageSize) : 0
    return { order: normalizeOrder(payload?.order), columns: next, filters, pageSize: size }
  }

  const applyPayload = (payload: ViewPayload) => {
    resetLayout()
    // 应用到某份保存的视图后，不再算「内置视角」
    activePreset.value = null
    if (payload.order && payload.order.length) {
      order.value = normalizeOrder(payload.order)
    }
    payload.columns.forEach((col) => {
      if (layout[col.key]) {
        layout[col.key] = {
          visible: col.visible,
          width: col.width,
          fixed: (col.fixed as ColumnLayout['fixed']) || ''
        }
      }
    })
    if (options.setFilters) {
      options.setFilters({ ...payload.filters })
    }
    if (options.setPageSize && payload.pageSize) {
      options.setPageSize(payload.pageSize)
    }
  }

  const snapshot = (): ViewPayload => ({
    order: [...order.value],
    columns: orderedDefs.value.map((def) => ({
      key: def.key,
      visible: show(def.key),
      width: widthOf(def.key, def.width),
      fixed: fixedOf(def.key)
    })),
    filters: options.getFilters ? { ...options.getFilters() } : {},
    pageSize: options.getPageSize ? options.getPageSize() : 20
  })

  const load = async () => {
    loading.value = true
    error.value = ''
    try {
      const list = (await api.get('/user-views', { pageKey })) || []
      views.value = list
      const target = list.find((item: SavedView) => item.isDefault) || list[0]
      if (target) {
        activeId.value = target.id
        applyPayload(normalize(target.view))
      } else {
        activeId.value = null
        resetLayout()
      }
    } catch (e) {
      error.value = '视图加载失败，已使用默认列设置'
    } finally {
      loading.value = false
    }
  }

  const saveAs = async (name: string, isDefault = false) => {
    const id = await api.post('/user-views', {
      pageKey,
      name: name.trim(),
      view: snapshot(),
      isDefault
    })
    await load()
    return id
  }

  const updateActive = async () => {
    if (!activeId.value) {
      throw new Error('请先另存为视图')
    }
    const current = views.value.find((item) => item.id === activeId.value)
    await api.put(`/user-views/${activeId.value}`, {
      pageKey,
      name: current?.name || '我的视图',
      view: snapshot(),
      isDefault: Boolean(current?.isDefault)
    })
    await load()
  }

  const setDefault = async (id: number) => {
    await api.post(`/user-views/${id}/default`)
    await load()
  }

  const applyView = (id: number) => {
    const target = views.value.find((item) => item.id === id)
    if (!target) return
    activeId.value = id
    applyPayload(normalize(target.view))
  }

  const remove = async (id: number) => {
    await api.delete(`/user-views/${id}`)
    if (activeId.value === id) {
      activeId.value = null
    }
    await load()
  }

  const reset = () => {
    activeId.value = null
    activePreset.value = null
    resetLayout()
    if (options.setFilters) {
      options.setFilters({})
    }
    if (options.setPageSize) {
      options.setPageSize(20)
    }
  }

  /**
   * 套用内置视角：只改列显隐，不动列宽与顺序，也不写库。
   * 用户想长期用某套列，还是走「保存并设为默认视图」。
   */
  const applyPreset = (key: string) => {
    const preset = presets.find((item) => item.key === key)
    if (!preset) return
    resetLayout()
    const visible = preset.visible ? new Set(preset.visible) : null
    defs.value.forEach((def) => {
      if (!layout[def.key]) layout[def.key] = builtin(def.key)
      if (visible) layout[def.key].visible = visible.has(def.key)
    })
    ;(preset.hidden || []).forEach((hiddenKey) => {
      if (layout[hiddenKey]) layout[hiddenKey].visible = false
    })
    activeId.value = null
    activePreset.value = key
  }

  const show = (key: string) => (layout[key] ? layout[key].visible : true)
  const widthOf = (key: string, fallback?: number) => {
    const def = defs.value.find((item) => item.key === key)
    return layout[key]?.width || fallback || (def ? def.width : 140)
  }
  const fixedOf = (key: string) => (layout[key]?.fixed || undefined) as 'left' | 'right' | undefined
  const alignOf = (key: string) => defs.value.find((item) => item.key === key)?.align

  const activeView = computed(() => views.value.find((item) => item.id === activeId.value) || null)
  const visibleCount = computed(() => defs.value.filter((def) => show(def.key)).length)

  return {
    pageKey,
    defs,
    setColumns,
    orderedDefs,
    order,
    layout,
    views,
    activeId,
    activeView,
    presets,
    activePreset,
    applyPreset,
    loading,
    error,
    load,
    saveAs,
    updateActive,
    setDefault,
    applyView,
    remove,
    reset,
    resetOrder,
    reflowOrder,
    setWidth,
    moveColumn,
    snapshot,
    show,
    widthOf,
    fixedOf,
    alignOf,
    visibleCount
  }
}
