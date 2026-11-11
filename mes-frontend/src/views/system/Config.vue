<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import { useConfigStore } from '../../stores/config'
import { useAuthStore } from '../../stores/auth'

const MASK = '******'

const auth = useAuthStore()
const config = useConfigStore()
const loading = ref(false)
const savingKey = ref<string | null>(null)
const testingStorage = ref(false)
const items = ref<any[]>([])
const drafts = reactive<Record<string, string>>({})

const groups = computed(() => {
  const map = new Map<string, any[]>()
  for (const item of items.value) {
    const name = item.group || '通用'
    if (!map.has(name)) map.set(name, [])
    map.get(name)!.push(item)
  }
  return Array.from(map.entries()).map(([name, list]) => ({ name, list }))
})

const load = async () => {
  loading.value = true
  try {
    const data = await api.get('/system/configs')
    items.value = Array.isArray(data) ? data : []
    for (const item of items.value) {
      // 密文不回传真实值，输入框留空；保存时留空表示不改
      drafts[item.key] = item.sensitive ? '' : String(item.value ?? '')
    }
  } catch {
    items.value = []
  } finally {
    loading.value = false
  }
}

const boolOf = (item: any) => String(item.value) === 'true'

const optionLabel = (item: any, option: string) => {
  if (item.key === 'storage.provider') {
    return option === 'S3' ? 'S3 对象存储' : '本地目录'
  }
  return option
}

const stamp = () => new Date().toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')

const toggle = async (item: any, value: any) => {
  const target = value === true || value === 'true'
  savingKey.value = item.key
  try {
    await api.put(`/system/configs/${encodeURIComponent(item.key)}`, {
      value: target ? 'true' : 'false'
    })
    item.value = target ? 'true' : 'false'
    item.updatedAt = stamp()
    // 同步到全局 store，水印层立即响应，不用刷新页面
    config.values = { ...config.values, [item.key]: item.value }
    ElMessage.success(target ? '已开启' : '已关闭')
  } finally {
    savingKey.value = null
  }
}

const save = async (item: any, value?: string) => {
  const raw = value !== undefined ? value : drafts[item.key] ?? ''
  if (item.sensitive && raw === '') {
    ElMessage.warning('留空表示不修改，如需清空请填一个空格')
    return
  }
  savingKey.value = item.key
  try {
    await api.put(`/system/configs/${encodeURIComponent(item.key)}`, { value: raw })
    if (item.sensitive) {
      item.value = MASK
      item.hasValue = true
      drafts[item.key] = ''
    } else {
      item.value = raw
      config.values = { ...config.values, [item.key]: raw }
    }
    item.updatedAt = stamp()
    item.updatedBy = auth.user?.realName || auth.user?.username || ''
    ElMessage.success('已保存')
  } finally {
    savingKey.value = null
  }
}

const testStorage = async () => {
  testingStorage.value = true
  try {
    const result = await api.post('/files/storage/test')
    ElMessage.success(result?.message || '存储可用')
  } finally {
    testingStorage.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">系统配置</h2>
        <p class="page-sub">全局参数，改动后立即生效；配置项按分组维护，后续新增的配置会自动出现在对应分组里</p>
      </div>
      <div class="head-actions">
        <el-button v-perm="'config:save'" plain :loading="testingStorage" @click="testStorage">
          测试附件存储
        </el-button>
        <el-button plain @click="load">刷新</el-button>
      </div>
    </div>

    <div v-loading="loading">
      <div v-for="group in groups" :key="group.name" class="panel config-panel">
        <div class="config-group">{{ group.name }}</div>

        <div v-for="item in group.list" :key="item.key" class="config-row">
          <div class="config-info">
            <b>{{ item.title }}</b>
            <span v-if="item.remark">{{ item.remark }}</span>
            <em>
              <span class="mono">{{ item.key }}</span>
              <template v-if="item.updatedAt">
                · 最近修改 {{ item.updatedAt }}
                <template v-if="item.updatedBy">（{{ item.updatedBy }}）</template>
              </template>
            </em>
          </div>

          <div class="config-control">
            <el-switch
              v-if="item.valueType === 'BOOLEAN'"
              :model-value="boolOf(item)"
              :loading="savingKey === item.key"
              :disabled="!auth.can('config:save')"
              @change="(value: any) => toggle(item, value)"
            />

            <el-select
              v-else-if="item.valueType === 'SELECT'"
              :model-value="String(item.value) || String(item.options || '').split(',')[0]"
              style="width: 200px"
              :disabled="!auth.can('config:save')"
              @change="(value: string) => save(item, value)"
            >
              <el-option
                v-for="option in String(item.options || '').split(',').filter(Boolean)"
                :key="option"
                :label="optionLabel(item, option)"
                :value="option"
              />
            </el-select>

            <div v-else class="config-input">
              <el-input
                v-model="drafts[item.key]"
                :type="item.sensitive ? 'password' : 'text'"
                :show-password="item.sensitive"
                size="small"
                style="width: 260px"
                :placeholder="
                  item.sensitive
                    ? item.hasValue
                      ? '已配置，留空表示不修改'
                      : '未配置'
                    : '未配置'
                "
              />
              <el-button
                size="small"
                :loading="savingKey === item.key"
                :disabled="!auth.can('config:save')"
                @click="save(item)"
              >
                保存
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!loading && !items.length" class="panel config-empty">还没有配置项</div>
    </div>

    <p class="config-note">
      水印关闭后所有页面立即不再铺水印，重新打开同样即时生效；附件存储方式切到 S3 后只影响新上传，
      历史文件仍按台账记录的位置读取。密文配置保存后只显示掩码，留空提交表示保持原值。
    </p>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  gap: 8px;
}

.config-panel {
  margin-bottom: 14px;
  padding: 0 0 4px;
}

.config-group {
  padding: 12px 18px;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}

.config-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding: 14px 18px;
  border-bottom: 1px dashed var(--line);
}

.config-row:last-child {
  border-bottom: none;
}

.config-info b {
  display: block;
  font-size: 13px;
  color: var(--ink);
}

.config-info span {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.6;
}

.config-info em {
  display: block;
  margin-top: 4px;
  font-style: normal;
  font-size: 11px;
  color: var(--muted);
}

.config-control {
  flex: 0 0 auto;
  padding-top: 2px;
}

.config-input {
  display: flex;
  align-items: center;
  gap: 8px;
}

.config-empty {
  padding: 40px;
  text-align: center;
  color: var(--muted);
  font-size: 12px;
}

.config-note {
  margin: 0;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}
</style>
