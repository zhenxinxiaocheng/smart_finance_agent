<template>
  <RegisterShadcn />
</template>

<script setup>
import RegisterShadcn from './RegisterShadcn.vue'
</script>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  position: relative;
  overflow: hidden;
}

.register-bg {
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
  background: var(--primary-surface);
  top: -200px;
  left: -100px;
}

.shape-2 {
  width: 400px;
  height: 400px;
  background: var(--primary);
  bottom: -150px;
  right: -150px;
}

.shape-3 {
  width: 300px;
  height: 300px;
  background: var(--border);
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
  color: var(--primary);
  font-weight: 600;
  margin-left: 4px;
}

.login-link:hover {
  color: var(--primary);
}
</style>
