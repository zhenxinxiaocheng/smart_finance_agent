<template>
  <LoginShadcn />
</template>

<script setup>
import LoginShadcn from './LoginShadcn.vue'
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  position: relative;
  overflow: hidden;
}

.login-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.bg-shape {
  display: none;
}

.shape-1 {
  width: 500px;
  height: 500px;
  background: var(--primary);
  top: -200px;
  right: -100px;
}

.shape-2 {
  width: 400px;
  height: 400px;
  background: var(--primary-surface);
  bottom: -150px;
  left: -150px;
}

.shape-3 {
  width: 300px;
  height: 300px;
  background: var(--border);
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
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
}

.card-brand {
  text-align: center;
  margin-bottom: 36px;
}

.brand-name {
  font-size: 22px;
  font-weight: 800;
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
  color: var(--primary);
  font-weight: 600;
  margin-left: 4px;
}

.register-link:hover {
  color: var(--primary);
}
</style>
