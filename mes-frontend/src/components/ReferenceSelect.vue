<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'

/**
 * 引用类型远程选择器：服务端关键字搜索 + 每页 20 条，避免把整张主数据表塞进下拉框。
 *
 * <p>用法：</p>
 * <pre>
 *   <ReferenceSelect v-model="form.customerId" url="/options/customers"
 *                    :initial-label="detail.customerName" @select="onCustomer" />
 * </pre>
 *
 * <p>接口需返回数组（或 { records: [] }），字段默认 id/code/name；选中时抛出整行，
 * 页面可以继续带出单价、单位、交期等。</p>
 */
const props = withDefaults(defineProps<{
  modelValue?: number | string | null
  url: string
  params?: Record<string, any>
  placeholder?: string
  disabled?: boolean
  clearable?: boolean
  initialLabel?: string
  labelField?: string
  codeField?: string
  valueField?: string
  size?: 'large' | 'default' | 'small'
  limit?: number
}>(), {
  modelValue: null,
  params: () => ({}),
  placeholder: '输入编码或名称搜索',
  disabled: false,
  clearable: true,
  initialLabel: '',
  labelField: 'name',
  codeField: 'code',
  valueField: 'id',
  size: 'default',
  limit: 20
})

const emit = defineEmits<{
  'update:modelValue': [value: number | string | null]
  select: [row: any]
}>()

const options = ref<any[]>([])
const loading = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null
let requestSeq = 0

const labelOf = (row: any) => {
  const code = row?.[props.codeField]
  const name = row?.[props.labelField]
  if (code && name) return `${code} · ${name}`
  return String(name ?? code ?? '')
}

const optionValue = (row: any) => row?.[props.valueField]

/** 编辑回显：当前值不在搜索结果里时，用页面传入的名称补一个虚拟选项 */
const ensureInitial = () => {
  if (props.modelValue == null || !props.initialLabel) return
  if (options.value.some((row) => String(optionValue(row)) === String(props.modelValue))) return
  options.value = [
    { [props.valueField]: props.modelValue, _virtual: true, [props.labelField]: props.initialLabel },
    ...options.value
  ]
}

const load = async (keyword = '') => {
  const seq = ++requestSeq
  loading.value = true
  try {
    const data = await api.get(props.url, {
      ...props.params,
      keyword: keyword || undefined,
      limit: props.limit
    })
    if (seq !== requestSeq) return
    options.value = Array.isArray(data) ? data : (data?.records || [])
    ensureInitial()
  } catch {
    if (seq === requestSeq) options.value = []
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

const remote = (query: string) => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => load(query), 300)
}

const onChange = (value: any) => {
  emit('update:modelValue', value)
  emit('select', options.value.find((row) => String(optionValue(row)) === String(value)) || null)
}

watch(() => props.modelValue, ensureInitial)
watch(() => props.initialLabel, ensureInitial)

onMounted(() => load(''))
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <el-select
    :model-value="modelValue"
    filterable
    remote
    reserve-keyword
    :clearable="clearable"
    :remote-method="remote"
    :loading="loading"
    :disabled="disabled"
    :size="size"
    :placeholder="placeholder"
    style="width: 100%"
    @change="onChange"
    @visible-change="(visible: boolean) => visible && !options.length && load('')"
  >
    <el-option v-for="item in options" :key="optionValue(item)" :label="labelOf(item)" :value="optionValue(item)" />
  </el-select>
</template>
