<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { encryptPasswordPair } from '../utils/crypto'
import { passwordGateOpen, closePasswordGate } from '../utils/passwordGate'

/**
 * 强制改密弹窗：首次登录（或管理员重置密码后）必须先把初始口令换掉。
 * 后端会拦住其它接口，所以这个框不提供关闭按钮，也不响应 ESC / 点遮罩。
 */
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const saving = ref(false)

const reset = () => {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirm = ''
}

watch(passwordGateOpen, (open) => {
  if (open) reset()
})

const submit = async () => {
  if (!form.oldPassword || !form.newPassword) {
    ElMessage.warning('请填写原密码与新密码')
    return
  }
  if (form.newPassword.length < 8) {
    ElMessage.warning('新密码至少 8 位')
    return
  }
  if (form.newPassword !== form.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  if (form.newPassword === form.oldPassword) {
    ElMessage.warning('新密码不能与原密码相同')
    return
  }
  saving.value = true
  try {
    const secret = await encryptPasswordPair(form.oldPassword, form.newPassword)
    const data = await api.put('/auth/password', secret)
    // 后端在改密成功后直接换发了一张不带「待改密」标记的令牌，这里落盘即可继续用
    if (data?.token) {
      localStorage.setItem('mes_token', data.token)
    }
    const raw = localStorage.getItem('mes_user')
    if (raw) {
      try {
        const user = JSON.parse(raw)
        user.mustChangePassword = false
        localStorage.setItem('mes_user', JSON.stringify(user))
      } catch {
        // 本地缓存解析失败不影响改密本身
      }
    }
    closePasswordGate()
    ElMessage.success('密码已更新')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="passwordGateOpen"
    title="请先修改初始密码"
    width="440px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    append-to-body
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="当前账号还在使用初始口令"
      description="初始密码是公开的演示口令，任何知道它的人都能登录。请设置一个只有你自己知道的新密码，至少 8 位。"
    />
    <el-form label-position="top" style="margin-top: 16px">
      <el-form-item label="当前密码" required>
        <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入当前密码" />
      </el-form-item>
      <el-form-item label="新密码" required>
        <el-input v-model="form.newPassword" type="password" show-password placeholder="至少 8 位，含大小写与数字更佳" />
      </el-form-item>
      <el-form-item label="确认新密码" required>
        <el-input v-model="form.confirm" type="password" show-password placeholder="再输入一次新密码" @keyup.enter="submit" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button type="primary" :loading="saving" @click="submit">保存并继续</el-button>
    </template>
  </el-dialog>
</template>
