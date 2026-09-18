<template>
  <div ref="surfaceRef" class="auth-page">
    <div class="auth-shell auth-shell-login">
      <section
        ref="heroRef"
        class="auth-hero"
        @mousemove="handleHeroMove"
        @mouseleave="handleHeroLeave"
      >
        <div class="auth-brand auth-rise" style="--d: 0ms">
          <div class="flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <MessageCircle />
          </div>
          <div>
            <p class="text-sm font-medium text-muted-foreground">智能财务</p>
            <h1 class="text-xl font-semibold tracking-normal">智财 Agent</h1>
          </div>
        </div>

        <div class="auth-hero-body">
          <div class="auth-rise" style="--d: 90ms">
            <p class="mb-3 text-sm font-medium text-muted-foreground">个人财务 · 量化研究 · Agent 编排</p>
            <h2 class="text-balance text-4xl font-semibold leading-tight tracking-normal">
              把记账、分析和投资研究，交给一个会动手的 Agent。
            </h2>
            <p class="mt-6 text-balance text-base leading-7 text-muted-foreground">
              截图识别账单，对话完成操作，重要改动都由你确认。
            </p>
          </div>

          <div class="auth-feature-list text-sm">
            <div
              v-for="(feature, index) in features"
              :key="feature.title"
              class="auth-feature auth-rise"
              :style="`--d: ${200 + index * 90}ms`"
            >
              <span class="auth-feature-icon">
                <component :is="feature.icon" class="block size-[18px]" />
              </span>
              <span class="font-medium">{{ feature.title }}</span>
              <span class="auth-feature-value">{{ feature.value }}</span>
            </div>
          </div>
        </div>
      </section>

      <main class="auth-main">
        <Card
          ref="cardRef"
          class="auth-card auth-rise"
          style="--d: 40ms"
          @mousemove="handleCardMove"
          @mouseleave="handleCardLeave"
        >
          <CardHeader>
            <div class="mb-2 flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground lg:hidden">
              <MessageCircle />
            </div>
            <CardTitle>登录智财 Agent</CardTitle>
            <CardDescription>继续管理你的账单、消费记录和财务画像。</CardDescription>
          </CardHeader>

          <CardContent>
            <form class="flex flex-col gap-4" :class="{ 'auth-shake': shaking }" @submit.prevent="handleLogin">
              <div class="auth-field">
                <Label for="username" class="auth-field-label">用户名</Label>
                <div class="relative">
                  <UserRound class="auth-field-icon pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="username"
                    v-model="form.username"
                    class="h-10 pl-9 transition-[color,box-shadow] duration-200"
                    placeholder="请输入用户名"
                    autocomplete="username"
                    :aria-invalid="Boolean(errors.username)"
                  />
                </div>
                <p v-if="errors.username" class="auth-rise text-sm text-destructive">{{ errors.username }}</p>
              </div>

              <div class="auth-field">
                <Label for="password" class="auth-field-label">密码</Label>
                <div class="relative">
                  <LockKeyhole class="auth-field-icon pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="password"
                    v-model="form.password"
                    class="h-10 pr-11 pl-9 transition-[color,box-shadow] duration-200"
                    :type="showPassword ? 'text' : 'password'"
                    placeholder="请输入密码"
                    autocomplete="current-password"
                    :aria-invalid="Boolean(errors.password)"
                  />
                  <button
                    type="button"
                    class="auth-reveal"
                    :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                    @click="showPassword = !showPassword"
                  >
                    <EyeOff v-if="showPassword" class="size-4" />
                    <Eye v-else class="size-4" />
                  </button>
                </div>
                <p v-if="errors.password" class="auth-rise text-sm text-destructive">{{ errors.password }}</p>
              </div>

              <Button class="auth-submit mt-2 h-10 w-full" size="lg" type="submit" :disabled="loading">
                <Loader2 v-if="loading" class="animate-spin" data-icon="inline-start" />
                <ArrowRight v-else data-icon="inline-start" />
                登录
              </Button>
            </form>
          </CardContent>

          <CardFooter class="justify-center border-t border-border py-4 text-sm text-muted-foreground">
            还没有账号？
            <RouterLink to="/register" class="ml-1 font-medium text-foreground underline-offset-4 hover:underline">
              立即注册
            </RouterLink>
          </CardFooter>
        </Card>
      </main>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import {
  ArrowRight,
  Eye,
  EyeOff,
  FileSpreadsheet,
  FlaskConical,
  Loader2,
  LockKeyhole,
  MessageCircle,
  MessageSquareText,
  UserRound
} from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { feedback } from '@/lib/feedback'
import { usePointerMotion } from '@/composables/usePointerMotion'
import { useAuthStore } from '../stores/auth'
import '@/styles/auth.css'

const router = useRouter()
const authStore = useAuthStore()

const { heroRef, surfaceRef, cardRef, handleHeroMove, handleHeroLeave, handleCardMove, handleCardLeave } =
  usePointerMotion()

const features = [
  { title: '账单截图导入', value: '截图识别 · 确认入库', icon: FileSpreadsheet },
  { title: '对话式财务助手', value: '工具调用 · 先审后执', icon: MessageSquareText },
  { title: '量化研究与模拟盘', value: '因子回测 · 模拟运行', icon: FlaskConical }
]

const loading = ref(false)
const showPassword = ref(false)
const shaking = ref(false)
const form = reactive({ username: '', password: '' })
const errors = reactive({ username: '', password: '' })

let shakeTimer

function triggerShake() {
  shaking.value = false
  clearTimeout(shakeTimer)
  requestAnimationFrame(() => {
    shaking.value = true
    shakeTimer = setTimeout(() => {
      shaking.value = false
    }, 520)
  })
}

function validate() {
  errors.username = form.username.trim() ? '' : '请输入用户名'
  errors.password = form.password ? '' : '请输入密码'
  const ok = !errors.username && !errors.password
  if (!ok) triggerShake()
  return ok
}

async function handleLogin() {
  if (!validate()) return
  loading.value = true
  try {
    await authStore.login(form)
    feedback.success('登录成功')
    router.push('/chat')
  } catch {
  } finally {
    loading.value = false
  }
}
</script>
