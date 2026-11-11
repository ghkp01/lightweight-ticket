<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const router = useRouter()
const loading = ref(false)
const flows = ref<any[]>([])

const bizLabel: Record<string, string> = {
  TRANSFER: '跨厂调拨',
  REQUISITION: '采购申请',
  PERFORMANCE: '绩效核算'
}

/* ---------------- 业务审批开关：配了就审批，不配就直接生效 ---------------- */
const bizRows = ref<any[]>([])
const BIZ_MODES = [
  { value: 'NONE', label: '免审批' },
  { value: 'THRESHOLD', label: '只按金额阈值' },
  { value: 'FLOW', label: '走审批流' }
]

const loadBiz = async () => {
  bizRows.value = (await api.get('/approvals/biz-types')) || []
}

const saveBiz = async (row: any) => {
  await api.put(`/approvals/biz-types/${row.bizType}`, {
    name: row.name,
    menuPath: row.menuPath,
    mode: row.mode,
    remark: row.remark
  })
  ElMessage.success('已更新，之后提交的单按新规则走')
  await loadBiz()
}

/* ---------------- 适用条件：按类别 / 金额分流到不同审批链 ---------------- */
const condFields = ref<string[]>([])
const condOps = ref<string[]>([])

const loadCondMeta = async () => {
  try {
    const data = await api.get('/approvals/condition-fields')
    condFields.value = data?.fields || []
    condOps.value = data?.ops || []
  } catch {
    condFields.value = []
    condOps.value = []
  }
}

const fieldLabel: Record<string, string> = {
  AMOUNT: '金额',
  QTY: '数量',
  ITEM_COUNT: '明细行数',
  CATEGORY: '采购类别',
  DEPARTMENT: '申请部门',
  SOURCE_TYPE: '来源',
  URGENT: '是否紧急',
  APPLICANT_ROLE: '申请人角色',
  BIZ_TYPE: '业务类型'
}

const ruleDialog = ref(false)
const ruleSaving = ref(false)
const ruleTarget = ref<any>({ id: 0, name: '', bizType: '', priority: 100, matchNote: '' })
const ruleRows = ref<Array<{ field: string; op: string; value: string }>>([])

const openRules = (row: any) => {
  ruleTarget.value = {
    id: row.id,
    name: row.name,
    bizType: row.bizType,
    priority: row.priority ?? 100,
    matchNote: row.matchNote || ''
  }
  ruleRows.value = (row.matchRules || []).map((rule: any) => ({
    field: rule.field,
    op: rule.op,
    value: rule.value
  }))
  ruleDialog.value = true
}

const addRule = () => ruleRows.value.push({ field: 'AMOUNT', op: '>=', value: '' })
const removeRule = (index: number) => ruleRows.value.splice(index, 1)

const saveRuleRows = async () => {
  ruleSaving.value = true
  try {
    await api.put(`/approvals/flows/${ruleTarget.value.id}/match`, {
      priority: Number(ruleTarget.value.priority) || 100,
      note: ruleTarget.value.matchNote,
      rules: ruleRows.value
    })
    ElMessage.success('适用条件已保存，下一张单按新条件分流')
    ruleDialog.value = false
    await load()
  } finally {
    ruleSaving.value = false
  }
}

/* 试算：填一组假想变量，看会命中哪条链 */
const simDialog = ref(false)
const simLoading = ref(false)
const simForm = ref({
  bizType: 'REQUISITION',
  amount: 0,
  qty: 0,
  itemCount: 1,
  category: 'MATERIAL',
  department: '',
  urgent: false
})
const simResult = ref<any>(null)

const openSimulate = (row?: any) => {
  if (row?.bizType) simForm.value.bizType = row.bizType
  simResult.value = null
  simDialog.value = true
}

const runSimulate = async () => {
  simLoading.value = true
  try {
    simResult.value = await api.post('/approvals/flows/simulate', {
      bizType: simForm.value.bizType,
      variables: {
        amount: Number(simForm.value.amount) || 0,
        qty: Number(simForm.value.qty) || 0,
        itemCount: Number(simForm.value.itemCount) || 0,
        category: simForm.value.category,
        department: simForm.value.department,
        urgent: !!simForm.value.urgent
      }
    })
  } finally {
    simLoading.value = false
  }
}

const load = async () => {
  loading.value = true
  try {
    flows.value = (await api.get('/approvals/flows')) || []
  } finally {
    loading.value = false
  }
}

const design = (row: any) => router.push(`/approvals/design/${row ? row.id : 'new'}`)

const toggle = async (row: any) => {
  await api.post(`/approvals/flows/${row.id}/toggle`, { enabled: !row.enabled })
  ElMessage.success(row.enabled ? '已停用' : '已启用')
  await load()
}

const remove = async (row: any) => {
  try {
    await ElMessageBox.confirm(
      `删除流程「${row.name}」？已有审批记录的流程只能停用不能删除。`,
      '删除审批流'
    )
    await api.delete(`/approvals/flows/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    // 取消
  }
}

/** 自定义逻辑：流程结束 / 驳回时回调这个地址 */
const editWebhook = async (row: any) => {
  try {
    const { value } = await ElMessageBox.prompt(
      '流程结束或驳回时，会 POST 一份 JSON 到这个地址（留空表示不回调）',
      '自定义回调',
      { inputValue: row.webhookUrl || '', inputPlaceholder: 'https://example.com/hooks/approval' }
    )
    await api.put(`/approvals/flows/${row.id}`, {
      code: row.code,
      name: row.name,
      bizType: row.bizType,
      enabled: row.enabled,
      remark: row.remark || '',
      webhookUrl: String(value || '').trim()
    })
    ElMessage.success('已保存回调地址')
    await load()
  } catch (error) {
    // 取消
  }
}

onMounted(() => {
  load()
  loadBiz()
  loadCondMeta()
})

/* ---------------- 灰度 ---------------- */
const canaryDialog = ref(false)
const canaryFlow = ref<any>(null)
const canaryLoading = ref(false)
const canarySaving = ref(false)
const canaryPercent = ref(0)
const canaryVersion = ref<number | null>(null)
const canaryFactories = ref<number[]>([])
const canaryUsers = ref<string[]>([])
const canaryNote = ref('')
const canaryStatus = ref<any>({})
const factoryOptions = ref<any[]>([])
const userOptions = ref<any[]>([])

const loadCanaryOptions = async () => {
  if (factoryOptions.value.length && userOptions.value.length) return
  try {
    // 用户下拉在系统管理下的 /system/users/options；之前少了 system 段，请求落到不存在的路径上
    const [orgs, users] = await Promise.all([
      api.get('/orgs/options'),
      api.get('/system/users/options')
    ])
    factoryOptions.value = (orgs || []).filter((item: any) => item.type === 'PLANT')
    userOptions.value = users || []
  } catch {
    // 下拉数据拿不到不影响读当前灰度状态，弹窗照常打开（具体错误拦截器已经提示过）
  }
}

const openCanary = async (row: any) => {
  canaryFlow.value = row
  canaryDialog.value = true
  canaryLoading.value = true
  try {
    await loadCanaryOptions()
    const status = (await api.get(`/approvals/flows/${row.id}/canary`)) || {}
    canaryStatus.value = status
    canaryVersion.value = status.canaryVersion || status.processVersion || 1
    canaryPercent.value = Number(status.canaryPercent || 0)
    canaryFactories.value = (status.canaryFactoryIds || []).map((id: any) => Number(id))
    canaryUsers.value = status.canaryUsers || []
    canaryNote.value = status.canaryNote || ''
  } finally {
    canaryLoading.value = false
  }
}

const saveCanary = async () => {
  if (!canaryVersion.value) {
    ElMessage.warning('请填灰度版本号')
    return
  }
  if (!canaryPercent.value && !canaryFactories.value.length && !canaryUsers.value.length) {
    ElMessage.warning('比例是 0 的时候，至少要选一个定向工厂或定向用户')
    return
  }
  canarySaving.value = true
  try {
    const res = await api.post(`/approvals/flows/${canaryFlow.value.id}/canary`, {
      version: canaryVersion.value,
      percent: canaryPercent.value || 0,
      factoryOrgIds: canaryFactories.value,
      usernames: canaryUsers.value,
      note: canaryNote.value
    })
    ElMessage.success(res?.message || '灰度已保存')
    canaryDialog.value = false
    await load()
  } finally {
    canarySaving.value = false
  }
}

const disableCanary = async () => {
  try {
    await ElMessageBox.confirm('关闭灰度后，所有新发起的单据都会走稳定版本。', '关闭灰度')
  } catch {
    return
  }
  await api.post(`/approvals/flows/${canaryFlow.value.id}/canary`, { version: null, percent: 0 })
  ElMessage.success('已关闭灰度')
  canaryDialog.value = false
  await load()
}

/** 列表里的灰度摘要：定向优先，其次比例 */
const canarySummary = (row: any) => {
  if (!row.canaryVersion) return ''
  const parts: string[] = []
  if (row.canaryTargetCount) parts.push(`定向 ${row.canaryTargetCount}`)
  if (row.canaryPercent) parts.push(`其余 ${row.canaryPercent}%`)
  return `灰度 v${row.canaryVersion}${parts.length ? ' · ' + parts.join(' / ') : ''}`
}

/* ---------------- 节点策略（超时 / 转交 / 加签 / 抄送） ---------------- */
const policyDialog = ref(false)
const policyFlow = ref<any>(null)
const policyLoading = ref(false)
const policySaving = ref(false)
const policyRows = ref<any[]>([])

const openPolicies = async (row: any) => {
  policyFlow.value = row
  policyDialog.value = true
  policyLoading.value = true
  try {
    await loadCanaryOptions()
    const rows = (await api.get(`/approvals/flows/${row.id}/node-policies`)) || []
    policyRows.value = rows.map((item: any) => ({
      nodeKey: item.nodeKey,
      nodeName: item.nodeName,
      nodeKind: item.nodeKind,
      multiMode: item.multiMode,
      timeoutHours: item.timeoutHours || 0,
      timeoutAction: item.timeoutAction || 'REMIND',
      allowTransfer: item.allowTransfer !== false,
      allowAddSign: item.allowAddSign !== false,
      ccList: item.ccUsers ? String(item.ccUsers).split(',').filter(Boolean) : []
    }))
  } finally {
    policyLoading.value = false
  }
}

const savePolicies = async () => {
  policySaving.value = true
  try {
    await api.post(`/approvals/flows/${policyFlow.value.id}/node-policies`, policyRows.value.map((item) => ({
      nodeKey: item.nodeKey,
      timeoutHours: item.timeoutHours || null,
      timeoutAction: item.timeoutHours ? item.timeoutAction : null,
      allowTransfer: item.allowTransfer,
      allowAddSign: item.allowAddSign,
      ccUsers: (item.ccList || []).join(',')
    })))
    ElMessage.success('节点策略已保存')
    policyDialog.value = false
  } finally {
    policySaving.value = false
  }
}

/* ---------------- 手写签名开关 ---------------- */
const toggleSignature = async (row: any) => {
  await api.post(`/approvals/flows/${row.id}/signature-required`, { required: !row.signatureRequired })
  ElMessage.success(row.signatureRequired ? '已取消必签' : '已开启必签')
  await load()
}

/* ---------------- 字段级权限 ---------------- */
const FIELD_DEFS = [
  { key: 'fromWarehouse', label: '源仓库', readOnly: true },
  { key: 'toWarehouse', label: '目标仓库', readOnly: true },
  { key: 'totalQty', label: '调拨数量', readOnly: true },
  { key: 'plannedDate', label: '计划日期', readOnly: false },
  { key: 'remark', label: '备注', readOnly: false }
]

const fieldDialog = ref(false)
const fieldFlow = ref<any>(null)
const nodeOptions = ref<any[]>([])
const fieldNode = ref('')
const accessMap = ref<Record<string, string>>({})
const savingRules = ref(false)

const loadRules = async () => {
  const rules: any[] = (await api.get(`/approvals/flows/${fieldFlow.value.id}/field-rules`)) || []
  const map: Record<string, string> = {}
  FIELD_DEFS.forEach((field) => { map[field.key] = 'VIEW' })
  rules.filter((rule) => rule.nodeKey === fieldNode.value)
    .forEach((rule) => { map[rule.fieldKey] = rule.access })
  accessMap.value = map
}

const openFields = async (row: any) => {
  fieldFlow.value = row
  nodeOptions.value = (await api.get(`/approvals/flows/${row.id}/user-tasks`)) || []
  if (!nodeOptions.value.length) {
    ElMessage.warning('这条流程还没有用户任务节点，先去画布上画节点')
    return
  }
  fieldNode.value = nodeOptions.value[0].nodeKey
  await loadRules()
  fieldDialog.value = true
}

const onNodeChange = async () => {
  await loadRules()
}

const saveRules = async () => {
  savingRules.value = true
  try {
    const all: any[] = (await api.get(`/approvals/flows/${fieldFlow.value.id}/field-rules`)) || []
    const kept = all.filter((rule) => rule.nodeKey !== fieldNode.value)
    const current = Object.entries(accessMap.value).map(([fieldKey, access]) => ({
      nodeKey: fieldNode.value,
      fieldKey,
      access
    }))
    await api.put(`/approvals/flows/${fieldFlow.value.id}/field-rules`,
      [...kept, ...current].map((rule) => ({
        nodeKey: rule.nodeKey,
        fieldKey: rule.fieldKey,
        access: rule.access
      })))
    ElMessage.success('字段权限已保存')
    fieldDialog.value = false
  } finally {
    savingRules.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="head-left">
        <h2 class="page-title">流程配置</h2>
        <span class="muted">一个业务类型可以挂多条链，按适用条件分流；画布改完保存会部署到 Flowable</span>
      </div>
      <div class="head-actions">
        <el-button @click="openSimulate()">试算命中</el-button>
        <el-button type="primary" @click="design(null)">新建流程</el-button>
      </div>
    </div>

    <div class="panel biz-panel">
      <div class="biz-head">
        <div>
          <h3 class="biz-title">业务审批开关</h3>
          <p class="muted">配了就走审批流，不配就直接生效；采购申请默认只按金额阈值分级</p>
        </div>
        <span class="muted">改完立即生效，已经在走审批的单据不受影响</span>
      </div>
      <el-table :data="bizRows" size="small" empty-text="还没有登记业务类型">
        <el-table-column label="业务 / 菜单" min-width="220">
          <template #default="{ row }">
            <div class="biz-name">{{ row.name }}</div>
            <div class="mono muted">{{ row.menuPath || '未绑定菜单' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="审批强度" width="190">
          <template #default="{ row }">
            <el-select v-model="row.mode" size="small" @change="saveBiz(row)">
              <el-option v-for="mode in BIZ_MODES" :key="mode.value" :label="mode.label" :value="mode.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="审批链" width="110">
          <template #default="{ row }">
            <span v-if="row.flowCount">{{ row.flowCount }} 条</span>
            <span v-else class="muted">未配置</span>
          </template>
        </el-table-column>
        <el-table-column label="在办" width="70" prop="pendingCount" />
        <el-table-column label="说明" min-width="260">
          <template #default="{ row }">
            <span :class="row.mode === 'FLOW' && !row.catchAllCount ? 'warn-text' : 'muted'">{{ row.hint }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel" v-loading="loading">
      <el-table :data="flows" size="small" empty-text="还没有配置审批流">
        <el-table-column prop="name" label="流程" min-width="180" />
        <el-table-column prop="code" label="编码" width="230">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="业务类型" width="120">
          <template #default="{ row }">{{ bizLabel[row.bizType] || row.bizType }}</template>
        </el-table-column>
        <el-table-column label="适用条件" min-width="230">
          <template #default="{ row }">
            <div class="rule-summary" :class="{ fallback: row.catchAll }">{{ row.matchSummary }}</div>
            <div class="mono muted">优先级 {{ row.priority }}</div>
          </template>
        </el-table-column>
        <el-table-column label="引擎版本" width="100">
          <template #default="{ row }">
            <span class="mono">v{{ row.processVersion || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="在办" width="80">
          <template #default="{ row }">{{ row.pendingCount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small" effect="plain">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="自定义回调" min-width="200">
          <template #default="{ row }">
            <span v-if="row.webhookUrl" class="mono">{{ row.webhookUrl }}</span>
            <span v-else class="muted">未配置</span>
          </template>
        </el-table-column>
        <el-table-column label="灰度 / 必签" width="190">
          <template #default="{ row }">
            <span v-if="row.canaryVersion" class="canary-tag">{{ canarySummary(row) }}</span>
            <span v-else class="muted">未灰度</span>
            <el-tag v-if="row.signatureRequired" size="small" type="warning" effect="plain" style="margin-left: 6px">必签</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="470" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="design(row)">设计</el-button>
            <el-button link type="primary" @click="openRules(row)">条件</el-button>
            <el-button link type="primary" @click="openSimulate(row)">试算</el-button>
            <el-button link :type="row.enabled ? 'warning' : 'success'" @click="toggle(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button link type="primary" @click="openCanary(row)">灰度</el-button>
            <el-button link type="primary" @click="openPolicies(row)">节点策略</el-button>
            <el-button link type="primary" @click="openFields(row)">字段权限</el-button>
            <el-button link type="primary" @click="toggleSignature(row)">
              {{ row.signatureRequired ? '取消必签' : '必签' }}
            </el-button>
            <el-button link type="primary" @click="editWebhook(row)">回调</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog append-to-body v-model="canaryDialog" title="灰度发布" width="560px">
      <div v-loading="canaryLoading">
        <p class="dialog-sub">
          {{ canaryFlow?.name }} · 当前版本 v{{ canaryFlow?.processVersion }}
          <span v-if="canaryStatus.stableVersion" class="muted">· 稳定版 v{{ canaryStatus.stableVersion }}</span>
        </p>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="定向的人一定走新版本，其余人按比例抽"
          description="先选工厂或用户做定向灰度，验证没问题再把比例往上放，最后关掉灰度让新版本变成稳定版。"
        />

        <el-form label-position="top" style="margin-top: 14px">
          <el-form-item label="灰度版本号">
            <el-input-number v-model="canaryVersion" :min="1" />
            <div class="dialog-tip">填已部署过的版本号，保存一次流程就会产生新版本。</div>
          </el-form-item>
          <el-form-item label="定向工厂">
            <el-select
              v-model="canaryFactories"
              multiple
              clearable
              filterable
              placeholder="选中工厂：该厂发起的单据全部走灰度版"
              style="width: 100%"
            >
              <el-option
                v-for="item in factoryOptions"
                :key="item.id"
                :label="item.code ? `${item.code} · ${item.name}` : item.name"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="定向用户">
            <el-select
              v-model="canaryUsers"
              multiple
              clearable
              filterable
              placeholder="选中用户：这些人发起的单据全部走灰度版"
              style="width: 100%"
            >
              <el-option
                v-for="item in userOptions"
                :key="item.username"
                :label="`${item.realName || item.username}（${item.username}）`"
                :value="item.username"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="其余人灰度比例（%）">
            <el-input-number v-model="canaryPercent" :min="0" :max="100" />
            <div class="dialog-tip">没被定向命中的人按这个比例随机走灰度版；填 0 表示只放给定向名单。</div>
          </el-form-item>
          <el-form-item label="灰度备注">
            <el-input v-model="canaryNote" maxlength="120" placeholder="这次要验证什么，例如：验证并行会签节点" />
          </el-form-item>
        </el-form>

        <div v-if="canaryStatus.versions?.length" class="version-box">
          <div class="version-title">各版本实际单据量</div>
          <el-table :data="canaryStatus.versions" size="small">
            <el-table-column label="流程版本" width="100">
              <template #default="{ row }">
                <span class="mono">v{{ row.version }}</span>
                <el-tag
                  v-if="Number(row.version) === Number(canaryStatus.canaryVersion)"
                  size="small"
                  type="warning"
                  effect="plain"
                  style="margin-left: 6px"
                >灰度</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="total" label="单据总数" width="100" align="right" />
            <el-table-column prop="pending" label="在办" width="80" align="right" />
            <el-table-column prop="approved" label="已通过" width="90" align="right" />
            <el-table-column prop="abnormal" label="驳回/撤回" width="110" align="right" />
          </el-table>
          <div class="dialog-tip">灰度版跑稳了再放大比例；有问题点下方「关闭灰度」，新单据立刻回到稳定版。</div>
        </div>
      </div>

      <template #footer>
        <el-button
          v-if="canaryStatus.active"
          type="danger"
          plain
          style="margin-right: auto"
          @click="disableCanary"
        >关闭灰度</el-button>
        <el-button @click="canaryDialog = false">取消</el-button>
        <el-button type="primary" :loading="canarySaving" @click="saveCanary">保存灰度</el-button>
      </template>
    </el-dialog>

    <el-dialog append-to-body v-model="policyDialog" title="节点策略" width="820px">
      <p class="dialog-sub">
        {{ policyFlow?.name }} · 流程结构在画布里改，这里只配 BPMN 表达不了的业务规则
      </p>
      <div v-loading="policyLoading">
        <el-table :data="policyRows" size="small" empty-text="画布保存后这里会列出所有审批节点">
          <el-table-column label="节点" min-width="180">
            <template #default="{ row }">
              <span>{{ row.nodeName }}</span>
              <el-tag v-if="row.nodeKind === 'CC'" size="small" type="info" effect="plain" style="margin-left: 6px">抄送</el-tag>
              <el-tag v-else-if="row.multiMode === 'ALL'" size="small" type="warning" effect="plain" style="margin-left: 6px">会签</el-tag>
              <div class="mono" style="color: var(--muted)">{{ row.nodeKey }}</div>
            </template>
          </el-table-column>
          <el-table-column label="超时（小时）" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.timeoutHours" :min="0" :max="720" size="small" controls-position="right" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="超时动作" width="120">
            <template #default="{ row }">
              <el-select v-model="row.timeoutAction" size="small" :disabled="!row.timeoutHours">
                <el-option label="只提醒" value="REMIND" />
                <el-option label="自动通过" value="AUTO_PASS" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="允许转交" width="90" align="center">
            <template #default="{ row }">
              <el-switch v-model="row.allowTransfer" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="允许加签" width="90" align="center">
            <template #default="{ row }">
              <el-switch v-model="row.allowAddSign" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="抄送人" min-width="200">
            <template #default="{ row }">
              <el-select v-model="row.ccList" multiple filterable clearable collapse-tags size="small" placeholder="不抄送" style="width: 100%">
                <el-option
                  v-for="item in userOptions"
                  :key="item.username"
                  :label="`${item.realName || item.username}（${item.username}）`"
                  :value="item.username"
                />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
        <div class="dialog-tip">
          超时填 0 表示不提醒；到点后按动作提醒当前审批人或直接代为通过。转交 / 加签关掉后，该节点的待办只能通过或驳回。
        </div>
      </div>
      <template #footer>
        <el-button @click="policyDialog = false">取消</el-button>
        <el-button type="primary" :loading="policySaving" @click="savePolicies">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog append-to-body v-model="fieldDialog" title="字段级权限" width="560px">
      <p class="dialog-sub">{{ fieldFlow?.name }}</p>
      <el-select v-model="fieldNode" style="width: 100%; margin-bottom: 12px" @change="onNodeChange">
        <el-option v-for="item in nodeOptions" :key="item.nodeKey" :label="`${item.nodeName}（${item.nodeKey}）`" :value="item.nodeKey" />
      </el-select>
      <el-table :data="FIELD_DEFS" size="small">
        <el-table-column prop="label" label="字段" width="140" />
        <el-table-column label="权限">
          <template #default="{ row }">
            <el-radio-group v-model="accessMap[row.key]" size="small">
              <el-radio-button value="VIEW">只读</el-radio-button>
              <el-radio-button value="EDIT" :disabled="row.readOnly">可编辑</el-radio-button>
              <el-radio-button value="HIDDEN">隐藏</el-radio-button>
            </el-radio-group>
          </template>
        </el-table-column>
      </el-table>
      <div class="dialog-tip">仓库和数量属于单据主键信息，只允许只读或隐藏。</div>
      <template #footer>
        <el-button @click="fieldDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingRules" @click="saveRules">保存</el-button>
      </template>
    </el-dialog>

    <!-- 适用条件：按类别 / 金额 / 紧急程度分流到不同审批链 -->
    <el-dialog v-model="ruleDialog" title="适用条件与优先级" width="640px">
      <div class="rule-tip">
        多条条件之间是「且」；一条条件都不填的链是不限条件的兜底，同一业务类型只能有一条。
        提交时按优先级从高到低逐条试，第一条命中的链生效。
      </div>
      <div class="rule-meta">
        <span class="muted">{{ ruleTarget.name }}（{{ bizLabel[ruleTarget.bizType] || ruleTarget.bizType }}）</span>
        <div class="rule-priority">
          <span class="muted">优先级</span>
          <el-input-number v-model="ruleTarget.priority" :min="1" :max="999" size="small" controls-position="right" />
        </div>
      </div>
      <div v-for="(rule, index) in ruleRows" :key="index" class="rule-row">
        <el-select v-model="rule.field" size="small" style="width: 150px">
          <el-option v-for="field in condFields" :key="field" :label="fieldLabel[field] || field" :value="field" />
        </el-select>
        <el-select v-model="rule.op" size="small" style="width: 110px">
          <el-option v-for="op in condOps" :key="op" :label="op" :value="op" />
        </el-select>
        <el-input v-model="rule.value" size="small" placeholder="比较值，多个用逗号分隔" style="width: 200px" />
        <el-button link type="danger" @click="removeRule(index)">删除</el-button>
      </div>
      <el-empty v-if="!ruleRows.length" description="没有条件即兜底：所有单都走这条链" :image-size="56" />
      <el-button size="small" @click="addRule">加一条条件</el-button>
      <el-input
        v-model="ruleTarget.matchNote"
        size="small"
        placeholder="备注：这条链给谁看、为什么这么分"
        style="margin-top: 10px"
      />
      <template #footer>
        <el-button @click="ruleDialog = false">取消</el-button>
        <el-button type="primary" :loading="ruleSaving" @click="saveRuleRows">保存</el-button>
      </template>
    </el-dialog>

    <!-- 试算：拿一组假想变量看会走哪条链 -->
    <el-dialog v-model="simDialog" title="试算命中哪条审批链" width="660px">
      <div class="sim-form">
        <el-input v-model="simForm.bizType" size="small" placeholder="业务类型" style="width: 170px" />
        <el-input-number v-model="simForm.amount" :min="0" size="small" :controls="false" placeholder="金额" style="width: 110px" />
        <el-input-number v-model="simForm.qty" :min="0" size="small" :controls="false" placeholder="数量" style="width: 100px" />
        <el-select v-model="simForm.category" size="small" style="width: 130px">
          <el-option label="原辅料" value="MATERIAL" />
          <el-option label="外协" value="OUTSOURCE" />
          <el-option label="设备备件" value="SPARE" />
          <el-option label="设备" value="EQUIPMENT" />
          <el-option label="服务" value="SERVICE" />
        </el-select>
        <el-checkbox v-model="simForm.urgent" label="紧急" border size="small" />
        <el-button size="small" type="primary" :loading="simLoading" @click="runSimulate">试算</el-button>
      </div>
      <div v-if="simResult" class="sim-result">
        <div class="sim-hit" :class="{ miss: !simResult.matched }">
          {{ simResult.matched
            ? '命中：' + simResult.flowName + '（' + simResult.flowCode + '）'
            : '没有命中任何链，这张单会直接生效' }}
        </div>
        <el-table :data="simResult.traces" size="small">
          <el-table-column label="审批链" min-width="170">
            <template #default="{ row }">
              <div>{{ row.flowName }}</div>
              <div class="mono muted">优先级 {{ row.priority }}</div>
            </template>
          </el-table-column>
          <el-table-column label="条件" min-width="240">
            <template #default="{ row }">
              <span v-if="row.catchAll" class="muted">不限条件（兜底）</span>
              <div v-for="(cond, index) in row.conditions" :key="index" class="cond-line">
                <span :class="cond.pass ? 'pass' : 'fail'">{{ cond.pass ? '✓' : '✗' }}</span>
                {{ fieldLabel[cond.field] || cond.field }} {{ cond.op }} {{ cond.value }}
                <span class="muted">（实际 {{ cond.actual === null || cond.actual === undefined ? '空' : cond.actual }}）</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="row.pass ? 'success' : 'info'" size="small" effect="plain">
                {{ row.pass ? '命中' : '不命中' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.biz-panel {
  margin-bottom: 14px;
}

.biz-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px 6px;
}

.biz-title {
  margin: 0 0 4px;
  font-size: 14px;
  color: var(--text);
}

.biz-name {
  font-weight: 600;
  color: var(--text);
}

.warn-text {
  color: #b54708;
}

.rule-summary {
  font-size: 12px;
  color: var(--text);
  line-height: 1.6;
}

.rule-summary.fallback {
  color: var(--muted);
}

.rule-tip {
  margin-bottom: 12px;
  padding: 8px 12px;
  border: 1px dashed #d5e3fb;
  border-radius: 8px;
  background: #f8fbff;
  color: #475467;
  font-size: 12px;
  line-height: 1.6;
}

.rule-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 12px;
}

.rule-priority {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rule-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.sim-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.sim-hit {
  margin-bottom: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  background: #ecfdf3;
  color: #067647;
  font-size: 13px;
}

.sim-hit.miss {
  background: #fffaeb;
  color: #b54708;
}

.cond-line {
  font-size: 12px;
  line-height: 1.7;
}

.cond-line .pass {
  color: #067647;
  font-weight: 600;
}

.cond-line .fail {
  color: #b42318;
  font-weight: 600;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.head-left {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.panel {
  padding: 12px;
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 10px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.canary-tag {
  display: inline-block;
  padding: 1px 6px;
  font-size: 12px;
  color: #b45309;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  border-radius: 4px;
}

.dialog-sub {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--muted);
}

.dialog-tip {
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

.version-box {
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px dashed var(--line);
}

.version-title {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
}
</style>
