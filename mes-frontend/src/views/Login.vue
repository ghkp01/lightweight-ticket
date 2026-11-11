<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '123456' })

const demoUsers = [
  { label: '管理员', username: 'admin' },
  { label: '销售', username: 'sales' },
  { label: '计划', username: 'planner' },
  { label: '仓库', username: 'warehouse' },
  { label: '质检', username: 'qc' }
]

const pick = (username: string) => {
  form.username = username
  form.password = '123456'
}

const submit = async () => {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (e) {
    // 错误提示已统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="login-visual">
      <div class="login-brand">
        <div class="mark">M</div>
        <span>智造工单 · LIGHT MES</span>
      </div>

      <div class="login-copy">
        <div class="eyebrow">Workshop Operating System</div>
        <h1>从一张订单，<br />到一次准时交付。</h1>
        <p>
          销售、计划、车间、仓库、质检、退货在同一条链路里流转。
          工单进度、物料消耗、成品库存、发货状态，打开就能看到真实数字。
        </p>
      </div>

      <div class="login-stats">
        <div class="stat">
          <b>6</b>
          <span>业务模块</span>
        </div>
        <div class="stat">
          <b>1</b>
          <span>条交付主线</span>
        </div>
        <div class="stat">
          <b>100%</b>
          <span>库存流水可追溯</span>
        </div>
      </div>
    </section>

    <section class="login-panel">
      <div class="login-card">
        <h2>登录系统</h2>
        <p class="sub">使用企业账号进入生产管理平台</p>

        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="账号">
            <el-input v-model="form.username" size="large" placeholder="请输入账号" @keyup.enter="submit">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              size="large"
              placeholder="请输入密码"
              @keyup.enter="submit"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-button type="primary" size="large" style="width: 100%; margin-top: 6px" :loading="loading" @click="submit">
            进入工作台
          </el-button>
        </el-form>

        <div class="demo-users">
          <div class="label">演示账号，密码均为 123456</div>
          <div class="demo-chips">
            <button v-for="user in demoUsers" :key="user.username" class="demo-chip" @click="pick(user.username)">
              {{ user.label }} · {{ user.username }}
            </button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>
