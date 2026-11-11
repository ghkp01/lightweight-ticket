<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, downloadFile, uploadFile } from '../api'

const props = withDefaults(
  defineProps<{
    bizType: string
    bizId?: number | string | null
    bizNo?: string | null
    title?: string
    hint?: string
    readonly?: boolean
  }>(),
  { bizId: null, bizNo: null, title: '附件', hint: '', readonly: false }
)

const rows = ref<any[]>([])
const loading = ref(false)
const uploading = ref(false)

const load = async () => {
  if (!props.bizId) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const data = await api.get('/files', { bizType: props.bizType, bizId: props.bizId, limit: 100 })
    rows.value = Array.isArray(data) ? data : []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

const doUpload = async (options: any) => {
  const file: File = options.file
  if (!file) return
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.error('单个附件不能超过 20MB')
    options.onError?.(new Error('too large'))
    return
  }
  uploading.value = true
  try {
    await uploadFile(file, {
      bizType: props.bizType,
      bizId: props.bizId || undefined,
      bizNo: props.bizNo || undefined
    })
    ElMessage.success('上传成功')
    await load()
    options.onSuccess?.({})
  } catch (error) {
    options.onError?.(error)
  } finally {
    uploading.value = false
  }
}

const remove = async (row: any) => {
  await ElMessageBox.confirm(`确定删除「${row.originalName}」？删除后不可恢复。`, '删除附件', {
    type: 'warning'
  })
  await api.delete(`/files/${row.id}`)
  ElMessage.success('已删除')
  load()
}

const download = (row: any) => downloadFile(row.id, row.originalName)

onMounted(load)
watch(() => props.bizId, load)
</script>

<template>
  <div class="attachments">
    <div class="attach-head">
      <b>{{ title }}</b>
      <span class="attach-count">{{ rows.length }} 个</span>
      <div class="spacer"></div>
      <el-upload
        v-if="!readonly && bizId"
        :http-request="doUpload"
        :show-file-list="false"
        :disabled="uploading"
      >
        <el-button v-perm="'file:upload'" size="small" type="primary" plain :loading="uploading">
          上传附件
        </el-button>
      </el-upload>
    </div>

    <p v-if="hint" class="attach-hint">{{ hint }}</p>

    <el-table v-loading="loading" :data="rows" size="small" empty-text="还没有附件">
      <el-table-column label="文件名" min-width="210" show-overflow-tooltip>
        <template #default="{ row }">
          <el-button link type="primary" @click="download(row)">{{ row.originalName }}</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="sizeText" label="大小" width="90" />
      <el-table-column label="存储" width="80">
        <template #default="{ row }">
          <span class="attach-tag">{{ row.storageType === 'S3' ? 'S3' : '本地' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="上传" min-width="140">
        <template #default="{ row }">
          <div>{{ row.uploadedBy || '-' }}</div>
          <div class="muted" style="font-size: 11px">{{ row.uploadedAt }}</div>
        </template>
      </el-table-column>
      <el-table-column v-if="!readonly" label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="download(row)">下载</el-button>
          <el-button v-perm="'file:delete'" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.attachments {
  margin-top: 6px;
}

.attach-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.attach-head b {
  font-size: 13px;
  color: var(--ink);
}

.attach-count {
  font-size: 11px;
  color: var(--muted);
}

.attach-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
}

.attach-tag {
  display: inline-block;
  padding: 1px 6px;
  border: 1px solid var(--line-strong);
  border-radius: 4px;
  font-size: 11px;
  color: var(--ink-3);
}
</style>
