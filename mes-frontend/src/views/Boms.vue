<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, fmtQty } from '../api'
import DataTable from '../components/DataTable.vue'
import TableToolbar from '../components/TableToolbar.vue'
import ReferenceSelect from '../components/ReferenceSelect.vue'
import { useTableView } from '../composables/useTableView'

/**
 * BOM 管理：物料主数据之外的独立对象。
 * 一个物料可以维护多套版本，同一时刻只有一套生效；工单与 MRP 按生效版本取料。
 */

const loading = ref(false)
const rows = ref<any[]>([])
const keyword = ref('')
const status = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const route = useRoute()

const table = useTableView({
  pageKey: 'boms',
  columns: [
    { key: 'bomNo', label: 'BOM 编号', width: 150 },
    { key: 'product', label: '父件物料', width: 220, flex: true, prop: '' },
    { key: 'version', label: '版本', width: 90 },
    { key: 'status', label: '状态', width: 100 },
    { key: 'baseQty', label: '基础数量', width: 100, align: 'right' },
    { key: 'effective', label: '生效区间', width: 200, prop: '' },
    { key: 'lineCount', label: '行数', width: 80, align: 'right' },
    { key: 'updatedAt', label: '更新时间', width: 150 },
    { key: 'actions', label: '操作', width: 360, fixed: 'right', prop: '' }
  ],
  getFilters: () => ({ keyword: keyword.value, status: status.value }),
  setFilters: (filters) => {
    keyword.value = filters.keyword || ''
    status.value = filters.status || ''
  },
  getPageSize: () => size.value,
  setPageSize: (value) => { size.value = value }
})

const statusMap: Record<string, { label: string; cls: string }> = {
  DRAFT: { label: '草稿', cls: 'draft' },
  PENDING: { label: '审批中', cls: 'warning' },
  ACTIVE: { label: '生效', cls: 'success' },
  INACTIVE: { label: '停用', cls: 'info' }
}

const today = () => {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/boms', {
      keyword: keyword.value,
      status: status.value || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = data?.records || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

const reload = () => {
  page.value = 1
  load()
}

// ---------------- 新建 / 编辑 BOM 头
const dialog = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive<any>({
  productId: null,
  productLabel: '',
  version: 'V1.0',
  baseQty: 1,
  effectiveFrom: today(),
  effectiveTo: '',
  remark: ''
})

const openCreate = () => {
  editingId.value = null
  Object.assign(form, {
    productId: null,
    productLabel: '',
    version: 'V1.0',
    baseQty: 1,
    effectiveFrom: today(),
    effectiveTo: '',
    remark: ''
  })
  dialog.value = true
}

const openEdit = (row: any) => {
  editingId.value = row.id
  Object.assign(form, {
    productId: row.productId,
    productLabel: row.productCode ? `${row.productCode} · ${row.productName}` : (row.productName || ''),
    version: row.version,
    baseQty: Number(row.baseQty || 1),
    effectiveFrom: row.effectiveFrom || today(),
    effectiveTo: row.effectiveTo || '',
    remark: row.remark || ''
  })
  dialog.value = true
}

const payload = () => ({
  version: form.version,
  baseQty: Number(form.baseQty || 1),
  effectiveFrom: form.effectiveFrom || null,
  effectiveTo: form.effectiveTo || null,
  remark: form.remark
})

const submit = async () => {
  if (!editingId.value && !form.productId) {
    ElMessage.warning('请选择父件物料')
    return
  }
  if (!form.version || !String(form.version).trim()) {
    ElMessage.warning('请填写 BOM 版本')
    return
  }
  if (!form.baseQty || Number(form.baseQty) <= 0) {
    ElMessage.warning('基础数量必须大于 0')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await api.put(`/boms/${editingId.value}`, payload())
    } else {
      await api.post(`/boms?productId=${form.productId}`, payload())
    }
    ElMessage.success('BOM 已保存')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

const submitApproval = async (row: any) => {
  await ElMessageBox.confirm(
    `提交版本「${row.version}」审批？通过后会自动替换该物料当前生效版本；若审批开关为免审批则直接生效。`,
    '提交 BOM 审批',
    { type: 'warning' }
  )
  const result = await api.post(`/boms/${row.id}/submit`)
  if (result?.approved) {
    ElMessage.success(`审批开关为免审批，${row.bomNo}（${row.version}）已直接生效`)
  } else {
    ElMessage.success('已提交审批，请到审批中心处理')
  }
  load()
}

const withdraw = async (row: any) => {
  await ElMessageBox.confirm(
    `撤回版本「${row.version}」的审批？撤回后回到草稿，可以继续修改。`,
    '撤回审批',
    { type: 'warning' }
  )
  await api.post(`/boms/${row.id}/withdraw`)
  ElMessage.success('已撤回，版本回到草稿')
  load()
}

const copyVersion = async (row: any) => {
  const { value } = await ElMessageBox.prompt(
    `复制 ${row.bomNo}（${row.version}）为新版本，留空自动生成版本号。`,
    '复制为新版本',
    {
      inputPlaceholder: '如 V2.0，留空自动生成',
      confirmButtonText: '复制',
      cancelButtonText: '取消'
    }
  )
  await api.post(`/boms/${row.id}/copy`, { version: value || null })
  ElMessage.success('已复制为草稿版本，请维护明细后提交审批')
  load()
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(
    `确认删除草稿 ${row.bomNo}（${row.version}）？只有草稿版本可以删除。`,
    '删除 BOM',
    { type: 'warning' }
  )
  await api.delete(`/boms/${row.id}`)
  ElMessage.success('已删除')
  load()
}

// ---------------- 行明细
const lineDrawer = ref(false)
const lineLoading = ref(false)
const lineSaving = ref(false)
const lineHeader = ref<any>(null)
const lineRows = ref<any[]>([])
const lineEditable = computed(() => lineHeader.value?.status === 'DRAFT')

/** 远程选择后兜底：不允许把父件自己当用料，也不允许替代料等于主料 */
const onMaterialSelect = (line: any, product: any) => {
  if (!product) return
  if (product.id === lineHeader.value?.productId) {
    line.materialId = null
    line.materialCode = ''
    line.materialName = ''
    ElMessage.warning('不能用父件物料本身作为 BOM 用料')
    return
  }
  line.materialCode = product.code
  line.materialName = product.name
  if (line.substituteMaterialId && line.substituteMaterialId === product.id) {
    line.substituteMaterialId = null
  }
}

const onSubstituteSelect = (line: any, product: any) => {
  if (!product) return
  if (product.id === lineHeader.value?.productId || product.id === line.materialId) {
    line.substituteMaterialId = null
    ElMessage.warning('替代料不能是父件或当前用料本身')
    return
  }
  line.substituteMaterialCode = product.code
  line.substituteMaterialName = product.name
}

const openLines = async (row: any) => {
  lineDrawer.value = true
  lineLoading.value = true
  try {
    const data = await api.get(`/boms/${row.id}`)
    lineHeader.value = data
    lineRows.value = (data.lines || []).map((line: any) => ({
      materialId: line.materialId,
      materialCode: line.materialCode || '',
      materialName: line.materialName || '',
      qty: Number(line.qty || 0),
      operationSeq: line.operationSeq == null ? 1 : Number(line.operationSeq),
      substituteGroup: line.substituteGroup || '',
      isKey: !!line.isKey,
      remark: line.remark || '',
      substituteMaterialId: line.substituteMaterialId || null,
      substituteMaterialCode: line.substituteMaterialCode || '',
      substituteMaterialName: line.substituteMaterialName || ''
    }))
  } finally {
    lineLoading.value = false
  }
}

const addLine = () => {
  if (!lineEditable.value) {
    ElMessage.warning('只有草稿版本可以修改明细，请先复制为新版本')
    return
  }
  lineRows.value.push({
    materialId: null,
    materialCode: '',
    materialName: '',
    qty: 1,
    operationSeq: 1,
    substituteGroup: '',
    isKey: false,
    remark: '',
    substituteMaterialId: null
  })
}

const removeLine = (index: number) => {
  lineRows.value.splice(index, 1)
}

const saveLines = async () => {
  if (!lineEditable.value) {
    ElMessage.warning('只有草稿版本可以修改明细')
    return
  }
  const ids = lineRows.value.map((line) => line.materialId).filter(Boolean)
  if (ids.length !== new Set(ids).size) {
    ElMessage.warning('同一物料不能重复出现')
    return
  }
  for (const line of lineRows.value) {
    if (!line.materialId) {
      ElMessage.warning('请为每一行选择物料')
      return
    }
    if (!line.qty || Number(line.qty) <= 0) {
      ElMessage.warning('BOM 用量必须大于 0')
      return
    }
  }
  lineSaving.value = true
  try {
    await api.put(`/boms/${lineHeader.value.id}/lines`, lineRows.value.map((line) => ({
      materialId: line.materialId,
      qty: Number(line.qty),
      operationSeq: Number(line.operationSeq || 1),
      substituteGroup: line.substituteGroup,
      isKey: !!line.isKey,
      remark: line.remark,
      substituteMaterialId: line.substituteMaterialId || null
    })))
    ElMessage.success('BOM 明细已保存')
    lineDrawer.value = false
    load()
  } finally {
    lineSaving.value = false
  }
}

onMounted(async () => {
  const queryKeyword = route.query.keyword
  if (typeof queryKeyword === 'string') {
    keyword.value = queryKeyword
  }
  await table.load()
  await load()
})
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">BOM 管理</h2>
        <p class="page-sub">
          物料可以维护多套 BOM 版本，同一时刻只有一套生效；工单与 MRP 按「生效 + 当天在区间内」的版本取料
        </p>
      </div>
      <el-button v-perm="'products'" type="primary" @click="openCreate">新建 BOM</el-button>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="物料编码 / 名称 / BOM 编号"
          clearable
          style="width: 260px"
          @keyup.enter="reload"
        />
        <el-select v-model="status" clearable placeholder="全部状态" style="width: 140px" @change="reload">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="审批中" value="PENDING" />
          <el-option label="生效" value="ACTIVE" />
          <el-option label="停用" value="INACTIVE" />
        </el-select>
        <el-button type="primary" plain @click="reload">查询</el-button>
        <div class="spacer"></div>
        <TableToolbar :table="table" export-api="/boms" export-name="BOM" @applied="reload" />
        <span class="toolbar-tip">共 {{ total }} 套 BOM</span>
      </div>

      <div class="table-wrap">
        <DataTable :table="table" :data="rows" :loading="loading">
          <template #cell-bomNo="{ row }"><span class="mono">{{ row.bomNo }}</span></template>
          <template #cell-product="{ row }">
            <div>{{ row.productName }}</div>
            <div class="muted mono" style="font-size: 11px">{{ row.productCode }} · {{ row.unit }}</div>
          </template>
          <template #cell-version="{ row }"><span class="mono">{{ row.version }}</span></template>
          <template #cell-status="{ row }">
            <span class="status-tag" :class="statusMap[row.status]?.cls || 'info'">
              <i></i>{{ statusMap[row.status]?.label || row.status }}
            </span>
          </template>
          <template #cell-baseQty="{ row }"><span class="num">{{ fmtQty(row.baseQty) }}</span></template>
          <template #cell-effective="{ row }">
            <span class="mono muted">{{ row.effectiveFrom || '—' }} ~ {{ row.effectiveTo || '长期' }}</span>
          </template>
          <template #cell-lineCount="{ row }"><span class="num">{{ row.lineCount }}</span></template>
          <template #cell-updatedAt="{ row }"><span class="muted">{{ row.updatedAt || '—' }}</span></template>
          <template #cell-actions="{ row }">
            <el-button link type="primary" @click="openLines(row)">
              {{ row.status === 'DRAFT' ? '明细' : '查看' }}
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              v-perm="'products'"
              link
              type="primary"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              v-perm="'products'"
              link
              type="success"
              @click="submitApproval(row)"
            >
              提交审批
            </el-button>
            <el-button
              v-if="row.status === 'PENDING'"
              v-perm="'products'"
              link
              type="warning"
              @click="withdraw(row)"
            >
              撤回
            </el-button>
            <el-button v-perm="'products'" link type="primary" @click="copyVersion(row)">复制新版本</el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              v-perm="'products'"
              link
              type="danger"
              @click="remove(row)"
            >
              删除
            </el-button>
          </template>
        </DataTable>
      </div>
      <div class="bom-tip">
        提示：新版本一律从草稿开始，维护明细后提交审批；审批通过自动替换同物料旧版本。生效/审批中的版本只读，如需变更请点「复制新版本」。
      </div>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load"
        />
      </div>
    </div>

    <el-dialog draggable append-to-body v-model="dialog" :title="editingId ? '编辑 BOM 版本' : '新建 BOM'" width="560px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="父件物料" required>
            <ReferenceSelect v-model="form.productId" url="/options/products"
                             :initial-label="form.productLabel"
                             :disabled="!!editingId"
                             placeholder="输入物料编码或名称搜索"
                             @select="(product) => (form.productLabel = product ? `${product.code} · ${product.name}` : '')" />
          </el-form-item>
          <el-form-item label="版本" required>
            <el-input v-model="form.version" maxlength="32" placeholder="如 V1.0" />
          </el-form-item>
          <el-form-item label="基础数量">
            <el-input-number v-model="form.baseQty" :min="0.0001" :precision="4" style="width: 100%" />
            <span class="form-tip">明细用量按「基础数量」个父件填写，默认 1</span>
          </el-form-item>
          <el-form-item label="生效日期">
            <el-date-picker v-model="form.effectiveFrom" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="失效日期">
            <el-date-picker
              v-model="form.effectiveTo"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="留空表示长期有效"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="版本说明、变更原因等" />
          </el-form-item>
        </div>
        <div class="form-tip">保存后版本为「草稿」，维护明细后在列表里提交审批，审批通过才生效。</div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer
      append-to-body
      v-model="lineDrawer"
      size="1080px"
      :title="`BOM 明细 · ${lineHeader?.productName || ''}（${lineHeader?.version || ''}）`"
    >
      <div v-loading="lineLoading">
        <div class="bom-head">
          <div>
            <span class="mono">{{ lineHeader?.bomNo }}</span>
            <span class="muted">
              · 基础数量 {{ fmtQty(lineHeader?.baseQty) }} {{ lineHeader?.unit }} · 状态
              {{ statusMap[lineHeader?.status]?.label || lineHeader?.status }}
            </span>
          </div>
          <el-button v-if="lineEditable" size="small" @click="addLine">添加物料</el-button>
        </div>

        <div v-if="!lineEditable" class="drawer-tip">
          该版本当前为「{{ statusMap[lineHeader?.status]?.label || lineHeader?.status }}」，明细只读；如需变更请回到列表点「复制新版本」。
        </div>

        <el-table :data="lineRows" size="small" empty-text="尚未维护 BOM 明细">
          <el-table-column label="物料" min-width="200">
            <template #default="{ row }">
              <ReferenceSelect v-model="row.materialId" url="/options/products"
                               :initial-label="row.materialCode ? `${row.materialCode} · ${row.materialName}` : row.materialName"
                               :disabled="!lineEditable"
                               placeholder="输入物料编码或名称搜索"
                               size="small"
                               @select="(product) => onMaterialSelect(row, product)" />
            </template>
          </el-table-column>
          <el-table-column label="用量" width="130">
            <template #default="{ row }">
              <el-input-number
                v-model="row.qty"
                :min="0.0001"
                :precision="4"
                :step="0.1"
                :disabled="!lineEditable"
                style="width: 100%"
              />
            </template>
          </el-table-column>
          <el-table-column label="投料工序" width="110">
            <template #default="{ row }">
              <el-input-number
                v-model="row.operationSeq"
                :min="1"
                :precision="0"
                :disabled="!lineEditable"
                style="width: 100%"
              />
            </template>
          </el-table-column>
          <el-table-column label="替代组" width="110">
            <template #default="{ row }">
              <el-input v-model="row.substituteGroup" :disabled="!lineEditable" placeholder="可空" />
            </template>
          </el-table-column>
          <el-table-column label="关键件" width="80" align="center">
            <template #default="{ row }">
              <el-checkbox v-model="row.isKey" :disabled="!lineEditable" />
            </template>
          </el-table-column>
          <el-table-column label="替代料" width="180">
            <template #default="{ row }">
              <ReferenceSelect v-model="row.substituteMaterialId" url="/options/products"
                               :initial-label="row.substituteMaterialCode
                                 ? `${row.substituteMaterialCode} · ${row.substituteMaterialName}`
                                 : row.substituteMaterialName"
                               :disabled="!lineEditable"
                               placeholder="不设替代"
                               size="small"
                               @select="(product) => onSubstituteSelect(row, product)" />
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="150">
            <template #default="{ row }">
              <el-input v-model="row.remark" :disabled="!lineEditable" placeholder="可空" />
            </template>
          </el-table-column>
          <el-table-column v-if="lineEditable" label="操作" width="70" align="center">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeLine($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="drawer-tip">
          用量按「基础数量 {{ fmtQty(lineHeader?.baseQty) }} 个父件」填写。领料时系统按
          <b>用量 ÷ 基础数量 × 工单计划数量 × (1 + 产品损耗率)</b> 计算需求。
        </div>
      </div>
      <template #footer>
        <el-button @click="lineDrawer = false">{{ lineEditable ? '取消' : '关闭' }}</el-button>
        <el-button
          v-if="lineEditable"
          type="primary"
          :loading="lineSaving"
          @click="saveLines"
        >
          保存明细
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.bom-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.bom-tip,
.drawer-tip {
  margin: 12px 16px 16px;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  color: var(--muted);
  font-size: 12px;
}

.drawer-tip {
  margin: 14px 0 0;
}

.form-tip {
  margin-top: 4px;
  color: var(--muted);
  font-size: 11px;
}
</style>
