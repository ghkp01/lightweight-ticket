<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const scopes = ref<string[]>([])
const drawer = ref(false)
const saving = ref(false)
const created = ref<any>(null)
const form = reactive<any>({ name: '', scopes: ['WORK_ORDER', 'INVENTORY'], expiresOn: '', remark: '' })

const scopeLabels: Record<string, string> = {
  WORK_ORDER: '工单',
  INVENTORY: '库存',
  MATERIAL: '物料',
  COST: '成本'
}

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/system/api-tokens', {
      page: page.value,
      size: size.value
    })
    rows.value = data?.records || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

const changePage = (value: number) => {
  page.value = value
  load()
}

const changeSize = () => {
  page.value = 1
  load()
}

/** 删除后刷新：当前页空了就回上一页 */
const refresh = async () => {
  await load()
  if (!rows.value.length && page.value > 1) {
    page.value -= 1
    await load()
  }
}

const loadScopes = async () => {
  scopes.value = (await api.get('/system/api-tokens/scopes')) || []
}

const openCreate = () => {
  created.value = null
  Object.assign(form, { name: '', scopes: ['WORK_ORDER', 'INVENTORY'], expiresOn: '', remark: '' })
  drawer.value = true
}

const submit = async () => {
  if (!form.name.trim()) {
    ElMessage.warning('请填写令牌名称')
    return
  }
  if (!form.scopes.length) {
    ElMessage.warning('请至少选择一个权限范围')
    return
  }
  saving.value = true
  try {
    created.value = await api.post('/system/api-tokens', {
      name: form.name,
      scopes: form.scopes,
      expiresOn: form.expiresOn || null,
      remark: form.remark
    })
    ElMessage.success('令牌已生成，明文只显示这一次，请立即复制')
    await load()
  } finally {
    saving.value = false
  }
}

const copyToken = async () => {
  try {
    await navigator.clipboard.writeText(created.value?.token || '')
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

const toggle = async (row: any) => {
  await api.post(`/system/api-tokens/${row.id}/toggle?enabled=${!row.enabled}`)
  ElMessage.success(row.enabled ? '已停用' : '已启用')
  await load()
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(`删除令牌「${row.name}」？外部系统将立即无法调用。`, '删除令牌', {
    type: 'warning'
  })
  await api.delete(`/system/api-tokens/${row.id}`)
  ElMessage.success('已删除')
  await refresh()
}

const scopeText = (value: any) =>
  String(value || '')
    .split(',')
    .filter(Boolean)
    .map((item) => scopeLabels[item] || item)
    .join(' / ')

onMounted(async () => {
  await loadScopes()
  await load()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">对外接口令牌</h2>
        <p class="page-sub">
          给 ERP / 财务等外部系统读数据用：令牌只存摘要、可设有效期与范围，关闭「启用对外接口」后全部失效
        </p>
      </div>
      <div class="head-actions">
        <el-button type="primary" @click="openCreate">新建令牌</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="api-hint">
        调用方式：请求头带 <code>X-API-Token: &lt;令牌&gt;</code>，
        可用接口 <code>GET /api/open/v1/ping</code>、<code>/work-orders</code>、<code>/inventory</code>、
        <code>/materials</code>、<code>/cost/work-orders</code>；每令牌每分钟最多 120 次。
      </div>

      <div v-loading="loading" class="table-wrap">
        <el-table :data="rows" size="small" empty-text="还没有对外令牌">
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column label="令牌前缀" width="150">
            <template #default="{ row }"><span class="mono">{{ row.tokenPrefix }}…</span></template>
          </el-table-column>
          <el-table-column label="权限范围" min-width="180">
            <template #default="{ row }">{{ scopeText(row.scopes) }}</template>
          </el-table-column>
          <el-table-column label="有效期" width="120">
            <template #default="{ row }">
              <span v-if="!row.expiresOn" class="muted">长期</span>
              <span v-else class="mono" :class="row.expired ? 'danger-text' : ''">{{ row.expiresOn }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最近调用" width="150">
            <template #default="{ row }">
              <span class="mono muted">{{ row.lastUsedAt || '从未调用' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span class="status-tag" :class="row.enabled && !row.expired ? 'success' : 'info'">
                <i></i>{{ row.expired ? '已过期' : row.enabled ? '启用' : '停用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="createdBy" label="创建人" width="110" />
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="toggle(row)">
                {{ row.enabled ? '停用' : '启用' }}
              </el-button>
              <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="changePage"
          @size-change="changeSize"
        />
      </div>
    </div>

    <el-drawer append-to-body v-model="drawer" title="新建对外令牌" size="480px">
      <template v-if="!created">
        <el-form label-position="top">
          <el-form-item label="令牌名称" required>
            <el-input v-model="form.name" placeholder="例如 ERP 对接 / 财务取数" />
          </el-form-item>
          <el-form-item label="权限范围" required>
            <el-checkbox-group v-model="form.scopes">
              <el-checkbox v-for="scope in scopes" :key="scope" :value="scope">
                {{ scopeLabels[scope] || scope }}
              </el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item label="有效期（留空表示长期）">
            <el-date-picker v-model="form.expiresOn" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="给谁用、对接哪个系统" />
          </el-form-item>
          <div class="dialog-tip">令牌明文只在创建成功时返回一次，关闭后无法再查看，只能重新生成。</div>
        </el-form>
      </template>

      <template v-else>
        <el-alert type="warning" :closable="false" show-icon title="请立即复制并保存到对方的密钥管理里" />
        <div class="token-box">
          <span class="mono">{{ created.token }}</span>
        </div>
        <el-button type="primary" plain @click="copyToken">复制令牌</el-button>
        <div class="dialog-tip">
          名称 {{ created.name }} · 范围 {{ scopeText((created.scopes || []).join(',')) }} ·
          有效期 {{ created.expiresOn || '长期' }}
        </div>
      </template>

      <template #footer>
        <template v-if="!created">
          <el-button @click="drawer = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="submit">生成令牌</el-button>
        </template>
        <el-button v-else type="primary" @click="drawer = false">我已保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.api-hint {
  font-size: 12px;
  line-height: 1.9;
  color: var(--muted);
  background: #f8fafc;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 12px;
}

.api-hint code {
  background: #eef2f6;
  border-radius: 4px;
  padding: 1px 5px;
  color: #2456d8;
}

.token-box {
  margin: 14px 0;
  padding: 12px;
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  background: #fcfcfd;
  word-break: break-all;
  font-size: 13px;
}

.dialog-tip {
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
  margin-top: 10px;
}
</style>
