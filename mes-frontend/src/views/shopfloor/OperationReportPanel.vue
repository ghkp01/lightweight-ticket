<script setup lang="ts">
import { fmtQty } from '../../api'

/**
 * 工序报工面板（扫码工位用）。
 *
 * 抽出来之前，这段表单在「扫工单码」和「扫工序码」两个页签里各复制了一份，
 * 改一处忘一处很容易出现两个页签行为不一致。现在只有这一份：
 * 父组件只负责「现在报哪张工单、哪些指导书」，表单状态由父组件持有
 * （reportForm / reportQualified / needInspect / canReport），
 * 组件只渲染并往上抛两个事件：operation-change、submit。
 */
const props = defineProps<{
  /** 工序列表，用于下拉选择 */
  operations: any[]
  /** 报工表单对象（父组件的 reactive，组件内直接改它的字段） */
  form: any
  /** 当前选中的工序 */
  operation: any | null
  /** 当前工序的 SOP / SIP 指导书 */
  docs: any[]
  /** 合格数量 = 报工数量 - 不良数量，由父组件算 */
  qualified: number
  /** 该工序是工序内检验点，必须给出检验结论 */
  needInspect: boolean
  /** 当前工单状态是否可报工 */
  canReport: boolean
  /** 提交中，防重复点 */
  submitting: boolean
  /** 工单单位，用于「剩余 x 件」 */
  unit?: string
}>()

const emit = defineEmits<{
  (e: 'operation-change', operationId: number): void
  (e: 'submit'): void
}>()

const remainingOf = (operation: any) =>
  fmtQty(Math.max(0, Number(operation?.planQty || 0) - Number(operation?.doneQty || 0)))
</script>

<template>
  <div class="panel">
    <div class="card-head">
      <div class="card-title">工序报工</div>
      <span class="toolbar-tip">{{ props.canReport ? '提交后实时回写工单进度' : '当前状态不可报工' }}</span>
    </div>
    <div class="card-body">
      <el-form label-position="top">
        <el-form-item label="报工工序" required>
          <el-select
            :model-value="props.form.operationId"
            placeholder="选择工序"
            style="width: 100%"
            @change="(value: any) => emit('operation-change', value)"
          >
            <el-option
              v-for="op in props.operations"
              :key="op.id"
              :label="`${op.seq}. ${op.name}（已报 ${fmtQty(op.doneQty)} / 计划 ${fmtQty(op.planQty)}）`"
              :value="op.id"
            />
          </el-select>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="报工数量" required>
            <el-input-number v-model="props.form.qty" :min="0" :precision="2" style="width: 100%" />
          </el-form-item>
          <el-form-item label="不良数量">
            <el-input-number
              v-model="props.form.defectQty"
              :min="0"
              :max="Number(props.form.qty || 0)"
              :precision="2"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="合格数量（自动）">
            <el-input-number :model-value="props.qualified" :min="0" :precision="2" disabled style="width: 100%" />
          </el-form-item>
          <el-form-item label="报工人" required>
            <el-input v-model="props.form.worker" placeholder="默认当前登录用户" />
          </el-form-item>
          <el-form-item v-if="props.needInspect" label="检验结论" required class="full">
            <el-radio-group v-model="props.form.inspectionResult">
              <el-radio value="PASS">合格</el-radio>
              <el-radio value="FAIL">不合格</el-radio>
            </el-radio-group>
            <div class="hint-line">
              该工序是工序内检验点，报工必须判定；判不合格时不良数量必须大于 0，
              系统会自动生成过程检验记录与待处置不良。
            </div>
          </el-form-item>
          <el-form-item v-if="props.needInspect" label="检验说明" class="full">
            <el-input v-model="props.form.inspectionRemark" placeholder="不良现象、隔离或返修建议（选填）" />
          </el-form-item>
          <el-form-item label="备注" class="full">
            <el-input v-model="props.form.remark" placeholder="选填" />
          </el-form-item>
        </div>

        <div v-if="props.docs.length" class="doc-block">
          <div class="doc-block-head">
            <span>当前工序指导书</span>
            <span class="muted">{{ props.docs.length }} 份</span>
          </div>
          <div v-for="doc in props.docs" :key="doc.id" class="doc-item">
            <div class="doc-item-head">
              <span class="status-tag" :class="doc.docType === 'SIP' ? 'success' : 'primary'">
                <i></i>{{ doc.docType }}
              </span>
              <b>{{ doc.title }}</b>
              <span class="muted">{{ doc.version }}</span>
            </div>
            <pre class="doc-text">{{ doc.content || '（无正文，见附件）' }}</pre>
            <a v-if="doc.fileUrl" :href="doc.fileUrl" target="_blank" class="doc-attach">查看附件</a>
          </div>
        </div>

        <div v-if="props.operation" class="op-current">
          <span>当前工序</span>
          <b>{{ props.operation.seq }}. {{ props.operation.name }}</b>
          <span class="muted">
            剩余 {{ remainingOf(props.operation) }} {{ props.unit || '' }}
            <template v-if="props.operation.assignedWorker"> · 派工 {{ props.operation.assignedWorker }}</template>
          </span>
        </div>

        <div class="submit-row">
          <span class="muted">合格数 = 报工数量 - 不良数量</span>
          <el-button type="primary" :loading="props.submitting" :disabled="!props.canReport" @click="emit('submit')">
            提交报工
          </el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.op-current {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 10px;
  padding: 8px 10px;
  margin-bottom: 12px;
  border-radius: 6px;
  background: #f8fafc;
  font-size: 12px;
}

.op-current > span:first-child {
  color: var(--muted);
}

.hint-line {
  margin-top: 6px;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
}

.doc-block {
  margin: 4px 0 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
}

.doc-block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #f7f9fc;
  font-size: 12px;
  font-weight: 600;
  border-bottom: 1px solid var(--line);
}

.doc-item {
  padding: 10px 12px;
  border-bottom: 1px dashed var(--line);
}

.doc-item:last-child {
  border-bottom: none;
}

.doc-item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.doc-item-head b {
  flex: 1;
}

.doc-text {
  margin: 8px 0 0;
  padding: 8px 10px;
  background: #fbfcfe;
  border-radius: 8px;
  font-family: inherit;
  font-size: 12px;
  line-height: 1.8;
  color: var(--ink-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.doc-attach {
  display: inline-block;
  margin-top: 6px;
  font-size: 12px;
  color: var(--primary);
}

/* 窄屏：三列表单压成一列，提交按钮铺满，工人戴手套也好点 */
@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .submit-row {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
  }

  .submit-row .el-button {
    width: 100%;
    min-height: 44px;
  }

  .doc-text {
    font-size: 13px;
  }
}
</style>
