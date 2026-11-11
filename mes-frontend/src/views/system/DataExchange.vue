<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'

type ExchangeType = 'product' | 'customer' | 'supplier' | 'bom' | 'routing' | 'inventory'

const TYPES: { type: ExchangeType; label: string; desc: string; note: string }[] = [
  { type: 'product', label: '产品', desc: '成品 / 半成品 / 原料档案', note: '按编码新增或更新' },
  { type: 'customer', label: '客户', desc: '客户档案与等级', note: '按编码新增或更新' },
  { type: 'supplier', label: '供应商', desc: '供应商档案、账期与交期', note: '按编码新增或更新' },
  { type: 'bom', label: 'BOM', desc: '产品用料清单', note: '按父件+子件新增或更新' },
  { type: 'routing', label: '工艺路线', desc: '产品的工序顺序与工时', note: '按产品+工序号新增或更新' },
  { type: 'inventory', label: '期初库存', desc: '仓库结存初始化', note: '增量入库，勿重复导入' }
]

const downloading = ref<string>('')
const importing = ref(false)
const activeType = ref<ExchangeType>('product')
const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const preview = ref<any>(null)
const result = ref<any>(null)

const current = computed(() => TYPES.find((item) => item.type === activeType.value)!)

const token = () => localStorage.getItem('mes_token') || ''

const saveBlob = (blob: Blob, fileName: string) => {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

const download = async (type: ExchangeType, kind: 'templates' | 'export') => {
  downloading.value = `${type}-${kind}`
  try {
    const resp = await fetch(`/api/data-exchange/${kind}/${type}`, {
      headers: token() ? { Authorization: `Bearer ${token()}` } : {}
    })
    if (!resp.ok) {
      throw new Error(resp.status === 403 ? '没有导入导出权限' : `下载失败（${resp.status}）`)
    }
    const blob = await resp.blob()
    const label = TYPES.find((item) => item.type === type)?.label || type
    saveBlob(blob, `${label}${kind === 'templates' ? '导入模板' : '导出'}.csv`)
  } catch (e: any) {
    ElMessage.error(e?.message || '下载失败')
  } finally {
    downloading.value = ''
  }
}

const pickFile = (type: ExchangeType) => {
  activeType.value = type
  fileInput.value?.click()
}

const onFileChange = (event: Event) => {
  const target = event.target as HTMLInputElement
  const picked = target.files?.[0] || null
  if (picked && !picked.name.toLowerCase().endsWith('.csv')) {
    ElMessage.warning('请选择 CSV 文件（Excel 里另存为 CSV UTF-8）')
    target.value = ''
    return
  }
  file.value = picked
  preview.value = null
  result.value = null
  if (picked) {
    upload(true)
  }
}

const upload = async (isPreview: boolean) => {
  if (!file.value) {
    ElMessage.warning('请先选择 CSV 文件')
    return
  }
  importing.value = true
  try {
    const form = new FormData()
    form.append('file', file.value)
    const data = await api.post(
      `/data-exchange/import/${activeType.value}?preview=${isPreview}`,
      form
    )
    if (isPreview) {
      preview.value = data
    } else {
      result.value = data
      preview.value = data
      ElMessage.success(`导入完成：成功 ${data.success ?? 0} 行，失败 ${data.failed ?? 0} 行`)
    }
  } finally {
    importing.value = false
  }
}

const reset = () => {
  file.value = null
  preview.value = null
  result.value = null
  if (fileInput.value) fileInput.value.value = ''
}

/* ---------- 行业模板：一键初始化示例主数据 ---------- */

const TEMPLATES = [
  { key: 'electronics', label: '电子组装', desc: '电控主板组件 + 主板半成品 + 主控芯片/PCB，含 BOM 与工艺路线' },
  { key: 'auto', label: '汽车零部件', desc: '转向节总成 + 毛坯 + 合金铸件/紧固螺栓，含 BOM 与工艺路线' },
  { key: 'food', label: '食品包装', desc: '每日坚果礼盒 + 混合坚果 + 巴旦木/腰果/礼盒，含 BOM 与工艺路线' }
]
const templateLoading = ref('')

const applyTemplate = async (item: typeof TEMPLATES[number]) => {
  try {
    await ElMessageBox.confirm(
      `将初始化「${item.label}」示例产品、BOM、工艺路线与客户。已存在的编码按编码更新，不会重复建。继续？`,
      '行业模板',
      { type: 'info', confirmButtonText: '开始初始化', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  templateLoading.value = item.key
  try {
    const data = await api.post(`/data-exchange/apply-template/${item.key}`)
    if (Number(data?.failed || 0) > 0) {
      ElMessage.warning(`${item.label} 初始化完成，但有 ${data.failed} 行失败，请查看导入结果`)
    } else {
      ElMessage.success(`${item.label} 模板已初始化`)
    }
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '模板初始化失败')
  } finally {
    templateLoading.value = ''
  }
}
</script>

<template>
  <div class="page page-fill">
    <div class="page-head">
      <div>
        <h2 class="page-title">基础数据导入</h2>
        <p class="page-sub">
          上线先把 Excel 里的档案灌进来：下载模板 → 填好另存为 CSV（UTF-8）→ 上传预览校验 → 确认导入；
          编码已存在时按编码更新，不会重复建
        </p>
      </div>
    </div>

    <div class="panel template-panel">
      <div class="card-head">
        <div class="card-title">行业模板一键初始化</div>
        <span class="muted">示例产品 / BOM / 工艺路线 / 客户；编码已存在则按编码更新</span>
      </div>
      <div class="template-grid">
        <div v-for="item in TEMPLATES" :key="item.key" class="template-card">
          <div class="template-head">
            <b>{{ item.label }}</b>
            <span class="status-tag info"><i></i>模板</span>
          </div>
          <p class="exchange-desc">{{ item.desc }}</p>
          <el-button
            size="small"
            type="primary"
            plain
            :loading="templateLoading === item.key"
            @click="applyTemplate(item)"
          >
            一键初始化
          </el-button>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="exchange-grid">
        <div v-for="item in TYPES" :key="item.type" class="exchange-card">
          <div class="exchange-head">
            <b>{{ item.label }}</b>
            <span class="muted">{{ item.note }}</span>
          </div>
          <p class="exchange-desc">{{ item.desc }}</p>
          <div class="exchange-actions">
            <el-button size="small" :loading="downloading === item.type + '-templates'" @click="download(item.type, 'templates')">
              下载模板
            </el-button>
            <el-button size="small" plain :loading="downloading === item.type + '-export'" @click="download(item.type, 'export')">
              导出当前数据
            </el-button>
            <el-button size="small" type="primary" @click="pickFile(item.type)">导入 CSV</el-button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="preview" class="panel result-panel">
      <div class="card-head">
        <div class="card-title">
          导入结果 · {{ current.label }}
          <span class="result-tag" :class="preview.failed ? 'danger' : 'success'">
            {{ result ? '已导入' : '预览校验' }}
          </span>
        </div>
        <el-button link type="primary" @click="reset">重新选择文件</el-button>
      </div>
      <div class="result-summary">
        <div><span class="muted">文件行数</span><b>{{ preview.total ?? 0 }}</b></div>
        <div><span class="muted">可导入</span><b class="ok">{{ preview.success ?? 0 }}</b></div>
        <div><span class="muted">新增</span><b>{{ preview.created ?? 0 }}</b></div>
        <div><span class="muted">更新</span><b>{{ preview.updated ?? 0 }}</b></div>
        <div><span class="muted">失败</span><b :class="{ 'danger-text': preview.failed }">{{ preview.failed ?? 0 }}</b></div>
      </div>

      <el-table v-if="(preview.errors || []).length" :data="preview.errors" size="small" max-height="280">
        <el-table-column prop="line" label="行号" width="80" />
        <el-table-column prop="message" label="失败原因" />
      </el-table>

      <div v-if="!result" class="confirm-row">
        <span class="muted">
          {{ (preview.errors || []).length ? '失败行不会被导入，可以改好后重新上传' : '校验通过，确认后写入系统' }}
        </span>
        <el-button type="primary" :loading="importing" @click="upload(false)">确认导入</el-button>
      </div>
    </div>

    <input ref="fileInput" type="file" accept=".csv,text/csv" hidden @change="onFileChange" />
  </div>
</template>

<style scoped>
.template-panel {
  margin-bottom: 14px;
  padding: 16px;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
  padding-top: 12px;
}

.template-card {
  border: 1px dashed var(--line);
  border-radius: 10px;
  padding: 12px;
  background: #fafbff;
}

.template-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.template-card .exchange-desc {
  min-height: 36px;
}

.exchange-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
  padding: 16px;
}

.exchange-card {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 14px;
  background: #fff;
}

.exchange-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
}

.exchange-head b {
  color: var(--text);
}

.exchange-head .muted {
  font-size: 11px;
}

.exchange-desc {
  margin: 8px 0 12px;
  color: var(--muted);
  font-size: 12px;
}

.exchange-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.exchange-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.result-panel {
  margin-top: 16px;
  padding-bottom: 16px;
}

.result-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 26px;
  padding: 4px 16px 14px;
  font-size: 12px;
}

.result-summary > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.result-summary b {
  font-size: 18px;
  font-family: Bahnschrift, monospace;
}

.result-summary .ok {
  color: #12b76a;
}

.result-tag {
  margin-left: 8px;
  padding: 1px 8px;
  border-radius: 20px;
  font-size: 11px;
}

.result-tag.success {
  background: #ecfdf3;
  color: #027a48;
}

.result-tag.danger {
  background: #fef3f2;
  color: #b42318;
}

.confirm-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px 0;
  font-size: 12px;
}
</style>
