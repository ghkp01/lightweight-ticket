<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const props = defineProps<{
  modelValue: boolean
  productId?: number | null
  processId?: number | null
  title?: string
}>()
const emit = defineEmits(['update:modelValue'])

const rows = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)

const valueTypeOptions = [
  { value: 'VISUAL', label: '目视' },
  { value: 'MEASURE', label: '计量' },
  { value: 'COUNT', label: '计数' }
]

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/inspections/items', {
      productId: props.productId || undefined,
      processId: props.processId || undefined
    })
    rows.value = (data || []).map((item: any) => ({
      itemCode: item.itemCode,
      itemName: item.itemName,
      standard: item.standard,
      method: item.method,
      valueType: item.valueType || 'VISUAL',
      lowerLimit: item.lowerLimit,
      upperLimit: item.upperLimit,
      unit: item.unit,
      required: item.required !== false,
      sortNo: item.sortNo,
      enabled: item.enabled !== false
    }))
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) load()
  }
)

const addRow = () => {
  rows.value.push({
    itemCode: '',
    itemName: '',
    standard: '',
    method: '',
    valueType: 'VISUAL',
    lowerLimit: null,
    upperLimit: null,
    unit: '',
    required: true,
    sortNo: rows.value.length + 1,
    enabled: true
  })
}

const removeRow = (index: number) => {
  rows.value.splice(index, 1)
}

const save = async () => {
  if (!props.productId && !props.processId) {
    ElMessage.warning('没有指定挂载对象')
    return
  }
  const invalid = rows.value.find((row) => !String(row.itemName || '').trim())
  if (invalid) {
    ElMessage.warning('检验项名称不能为空')
    return
  }
  saving.value = true
  try {
    await api.put('/inspections/items', {
      productId: props.productId || null,
      processId: props.processId || null,
      items: rows.value
    })
    ElMessage.success('检验项已保存')
    emit('update:modelValue', false)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    append-to-body
    :model-value="modelValue"
    size="820px"
    :title="title || '检验项（SIP）'"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="item-toolbar">
      <div class="muted">
        检验项就是这个对象的检查清单：标准、检具、上下限、是否必检。检验单会按「工序项 + 产品通用项」自动带出。
      </div>
      <el-button size="small" type="primary" plain @click="addRow">新增检验项</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" size="small" max-height="560">
      <el-table-column label="检验项名称" min-width="140">
        <template #default="{ row }"><el-input v-model="row.itemName" size="small" placeholder="如 长度" /></template>
      </el-table-column>
      <el-table-column label="编码" width="110">
        <template #default="{ row }"><el-input v-model="row.itemCode" size="small" placeholder="可选" /></template>
      </el-table-column>
      <el-table-column label="标准" min-width="150">
        <template #default="{ row }"><el-input v-model="row.standard" size="small" placeholder="如 180±0.5mm" /></template>
      </el-table-column>
      <el-table-column label="检具 / 方法" width="130">
        <template #default="{ row }"><el-input v-model="row.method" size="small" placeholder="卡尺 / 目视" /></template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-select v-model="row.valueType" size="small">
            <el-option v-for="item in valueTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="下限" width="100">
        <template #default="{ row }">
          <el-input-number v-model="row.lowerLimit" size="small" :controls="false" style="width: 100%" />
        </template>
      </el-table-column>
      <el-table-column label="上限" width="100">
        <template #default="{ row }">
          <el-input-number v-model="row.upperLimit" size="small" :controls="false" style="width: 100%" />
        </template>
      </el-table-column>
      <el-table-column label="单位" width="80">
        <template #default="{ row }"><el-input v-model="row.unit" size="small" placeholder="mm" /></template>
      </el-table-column>
      <el-table-column label="必检" width="70" align="center">
        <template #default="{ row }"><el-switch v-model="row.required" size="small" /></template>
      </el-table-column>
      <el-table-column label="排序" width="80">
        <template #default="{ row }">
          <el-input-number v-model="row.sortNo" size="small" :min="0" :controls="false" style="width: 100%" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="70" fixed="right">
        <template #default="{ $index }">
          <el-button link type="danger" @click="removeRow($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!rows.length && !loading" class="muted empty-tip">还没有检验项，点右上角「新增检验项」开始维护。</div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.item-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 12px;
  line-height: 1.6;
}

.empty-tip {
  padding: 22px 0;
  text-align: center;
  font-size: 12px;
}
</style>
