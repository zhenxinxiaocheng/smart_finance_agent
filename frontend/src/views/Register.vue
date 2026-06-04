<template>
  <div class="register-page">
    <div class="register-bg">
      <div class="bg-shape shape-1"></div>
      <div class="bg-shape shape-2"></div>
      <div class="bg-shape shape-3"></div>
    </div>
    <div class="register-container">
      <div class="register-card">
        <div class="card-brand">
          <svg width="48" height="48" viewBox="0 0 32 32" fill="none">
            <rect width="32" height="32" rx="8" fill="#1e3a8a"/>
            <path d="M16 8c-4.42 0-8 3.13-8 7 0 2.97 2.16 5.45 5.2 6.37l-2.11 3.54a.5.5 0 00.43.76l2.37-.01 1.96-2.99c.37.05.75.08 1.15.08 4.42 0 8-3.13 8-7s-3.58-7-8-7zm-2.5 9.5a1.5 1.5 0 110-3 1.5 1.5 0 010 3zm5 0a1.5 1.5 0 110-3 1.5 1.5 0 010 3z" fill="#22d3ee" opacity="0.9"/>
          </svg>
          <h2 class="brand-name">创建账号</h2>
          <p class="brand-desc">加入智财Agent，开启智能财务管理</p>
        </div>
        <el-form ref="formRef" :model="form" :rules="rules" size="large" class="register-form">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" />
          </el-form-item>
          <el-form-item prop="nickname">
            <el-input v-model="form.nickname" placeholder="昵称（选填）" :prefix-icon="EditPen" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="密码（至少6位）" :prefix-icon="Lock" show-password />
          </el-form-item>
          <el-form-item prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" placeholder="确认密码" :prefix-icon="Lock" show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="large" :loading="loading" class="register-btn" @click="handleRegister">
              注 册
            </el-button>
          </el-form-item>
        </el-form>
        <div class="card-footer">
          已有账号？
          <router-link to="/login" class="login-link">立即登录</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { User, Lock, EditPen } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const router = useRouter()
const authStore = useAuthStore()

const formRef = ref(null)
const loading = ref(false)

const form = reactive({ username: '', nickname: '', password: '', confirmPassword: '' })

const validatePass = (rule, value, callback) => {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少6位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validatePass, trigger: 'blur' }
  ]
}

async function handleRegister() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await authStore.register({
      username: form.username,
      password: form.password,
      nickname: form.nickname || undefined
    })
    ElMessage.success('注册成功')
    router.push('/statistics')
  } catch {
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
  position: relative;
  overflow: hidden;
}

.register-bg {
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
  background: #22d3ee;
  top: -200px;
  left: -100px;
}

.shape-2 {
  width: 400px;
  height: 400px;
  background: #1e3a8a;
  bottom: -150px;
  right: -150px;
}

.shape-3 {
  width: 300px;
  height: 300px;
  background: #3b82f6;
  top: 30%;
  left: 40%;
}

.register-container {
  position: relative;
  z-index: 1;
}

.register-card {
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

.register-form {
  margin-bottom: 20px;
}

.register-btn {
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

.login-link {
  color: var(--primary-light);
  font-weight: 600;
  margin-left: 4px;
}

.login-link:hover {
  color: var(--primary);
}
</style>
