<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-logo">
        <el-icon :size="34" color="#409eff"><MagicStick /></el-icon>
        <h1>AI Agent 控制台</h1>
        <p class="sub">多智能体 · 工具治理 · 流式对话</p>
      </div>

      <el-alert v-if="expired" title="登录已过期，请重新登录" type="warning" :closable="false" show-icon class="login-alert" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="login-alert" />

      <el-form :model="form" size="large" @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" autocomplete="username" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" :prefix-icon="Lock"
                    autocomplete="current-password" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-btn" :loading="loading" @click="submit">
            {{ mode === 'login' ? '登 录' : '注 册' }}
          </el-button>
        </el-form-item>
      </el-form>

      <div class="login-switch">
        <template v-if="mode === 'login'">
          没有账号？<el-link type="primary" @click="mode = 'register'">注册一个</el-link>
        </template>
        <template v-else>
          已有账号？<el-link type="primary" @click="mode = 'login'">返回登录</el-link>
        </template>
      </div>

      <el-divider />
      <p class="login-hint">
        演示账号：admin / agent123（生产环境请修改默认密码并关闭开放注册）
      </p>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, MagicStick } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const mode = ref('login')
const loading = ref(false)
const error = ref('')
const expired = route.query.expired === '1'
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username.trim() || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    if (mode.value === 'register') {
      await auth.register(form.username.trim(), form.password, form.username.trim())
      ElMessage.success('注册成功，已自动登录')
    }
    await auth.login(form.username.trim(), form.password)
    router.replace(route.query.redirect || '/chat')
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #e8f1ff 0%, #f5f7fa 50%, #eaf7f0 100%);
}
.login-card {
  width: 400px; padding: 40px 36px 24px;
  background: #fff; border-radius: 16px;
  box-shadow: 0 8px 40px rgba(64, 158, 255, .12);
}
.login-logo { text-align: center; margin-bottom: 24px; }
.login-logo h1 { font-size: 20px; margin: 10px 0 4px; }
.login-logo .sub { font-size: 12px; color: #909399; margin: 0; }
.login-alert { margin-bottom: 16px; }
.login-btn { width: 100%; }
.login-switch { text-align: center; font-size: 13px; color: #606266; }
.login-hint { font-size: 12px; color: #c0c4cc; text-align: center; margin: 8px 0 0; }
</style>
