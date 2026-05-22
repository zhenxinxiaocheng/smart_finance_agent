<template>
  <div class="login-page">
    <div class="login-bg">
      <div class="bg-shape shape-1"></div>
      <div class="bg-shape shape-2"></div>
      <div class="bg-shape shape-3"></div>
    </div>
    <div class="login-container">
      <div class="login-card">
        <div class="card-brand">
          <svg width="48" height="48" viewBox="0 0 32 32" fill="none">
            <rect width="32" height="32" rx="8" fill="#1e3a8a"/>
            <path d="M16 8c-4.42 0-8 3.13-8 7 0 2.97 2.16 5.45 5.2 6.37l-2.11 3.54a.5.5 0 00.43.76l2.37-.01 1.96-2.99c.37.05.75.08 1.15.08 4.42 0 8-3.13 8-7s-3.58-7-8-7zm-2.5 9.5a1.5 1.5 0 110-3 1.5 1.5 0 010 3zm5 0a1.5 1.5 0 110-3 1.5 1.5 0 010 3z" fill="#22d3ee" opacity="0.9"/>
          </svg>
          <h2 class="brand-name">智财Agent</h2>
          <p class="brand-desc">个人智能财务代理系统</p>
        </div>
        <el-form ref="formRef" :model="form" :rules="rules" size="large" class="login-form">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              :prefix-icon="Lock"
              show-password
              @keyup.enter="handleLogin"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="large" :loading="loading" class="login-btn" @click="handleLogin">
              登 录
            </el-button>
          </el-form-item>
        </el-form>
        <div class="card-footer">
          还没有账号？
          <router-link to="/register" class="register-link">立即注册</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const router = useRouter()
const authStore = useAuthStore()

const formRef = ref(null)
const loading = ref(false)

const form = reactive({ username: '', password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await authStore.login(form)
    ElMessage({ type: 'success', message: '登录成功', duration: 2000 })
    router.push('/dashboard')
  } catch {
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
  position: relative;
  overflow: hidden;
}

.login-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.bg-shape {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.3;
}

.shape-1 {
  width: 500px;
  height: 500px;
  background: #1e3a8a;
  top: -200px;
  right: -100px;
}

.shape-2 {
  width: 400px;
  height: 400px;
  background: #22d3ee;
  bottom: -150px;
  left: -150px;
}

.shape-3 {
  width: 300px;
  height: 300px;
  background: #3b82f6;
  bottom: 10%;
  right: 20%;
}

.login-container {
  position: relative;
  z-index: 1;
}

.login-card {
  width: 420px;
  padding: 44px 40px 36px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-xl);
}

.card-brand {
  text-align: center;
  margin-bottom: 36px;
}

.brand-name {
  font-size: 24px;
  font-weight: 700;
  color: var(--text);
  margin: 12px 0 4px;
}

.brand-desc {
  font-size: 14px;
  color: var(--text-muted);
  margin: 0;
}

.login-form {
  margin-bottom: 20px;
}

.login-btn {
  width: 100%;
  height: 48px;
  border-radius: var(--radius);
  font-size: 16px;
  font-weight: 600;
}

.card-footer {
  text-align: center;
  font-size: 14px;
  color: var(--text-muted);
}

.register-link {
  color: var(--primary-light);
  font-weight: 600;
  margin-left: 4px;
}

.register-link:hover {
  color: var(--primary);
}
</style>
