<template>
  <div ref="surfaceRef" class="auth-page">
    <div class="auth-shell auth-shell-register">
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
              <WalletCards />
            </div>
            <CardTitle>创建账号</CardTitle>
            <CardDescription>加入智财 Agent，开始构建你的个人财务工作台。</CardDescription>
          </CardHeader>

          <CardContent>
            <form class="flex flex-col gap-4" :class="{ 'auth-shake': shaking }" @submit.prevent="handleRegister">
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
                <Label for="nickname" class="auth-field-label">昵称</Label>
                <div class="relative">
                  <Sparkles class="auth-field-icon pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="nickname"
                    v-model="form.nickname"
                    class="h-10 pl-9 transition-[color,box-shadow] duration-200"
                    placeholder="可选"
                    autocomplete="nickname"
                  />
                </div>
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
                    placeholder="至少 6 位"
                    autocomplete="new-password"
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

              <div class="auth-field">
                <Label for="confirmPassword" class="auth-field-label">确认密码</Label>
                <div class="relative">
                  <ShieldCheck class="auth-field-icon pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="confirmPassword"
                    v-model="form.confirmPassword"
                    class="h-10 pr-11 pl-9 transition-[color,box-shadow] duration-200"
                    :type="showConfirm ? 'text' : 'password'"
                    placeholder="再次输入密码"
                    autocomplete="new-password"
                    :aria-invalid="Boolean(errors.confirmPassword)"
                  />
                  <button
                    type="button"
                    class="auth-reveal"
                    :aria-label="showConfirm ? '隐藏密码' : '显示密码'"
                    @click="showConfirm = !showConfirm"
                  >
                    <EyeOff v-if="showConfirm" class="size-4" />
                    <Eye v-else class="size-4" />
                  </button>
                </div>
                <p v-if="errors.confirmPassword" class="auth-rise text-sm text-destructive">{{ errors.confirmPassword }}</p>
              </div>

              <Button class="auth-submit mt-2 h-10 w-full" size="lg" type="submit" :disabled="loading">
                <Loader2 v-if="loading" class="animate-spin" data-icon="inline-start" />
                <UserPlus v-else data-icon="inline-start" />
                注册
              </Button>
            </form>
          </CardContent>

          <CardFooter class="justify-center border-t border-border py-4 text-sm text-muted-foreground">
            已有账号？
            <RouterLink to="/login" class="ml-1 font-medium text-foreground underline-offset-4 hover:underline">
              立即登录
            </RouterLink>
          </CardFooter>
        </Card>
      </main>

      <section
        ref="heroRef"
        class="auth-hero"
        @mousemove="handleHeroMove"
        @mouseleave="handleHeroLeave"
      >

        <div class="auth-brand auth-rise" style="--d: 0ms">
          <div class="flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <WalletCards />
          </div>
          <div>
            <p class="text-sm font-medium text-muted-foreground">智能财务</p>
            <h1 class="text-xl font-semibold tracking-normal">智财 Agent</h1>
          </div>
        </div>

        <div class="auth-hero-body">
          <div class="auth-rise" style="--d: 90ms">
            <p class="mb-3 text-sm font-medium text-muted-foreground">开始前只需要一个账号</p>
            <h2 class="text-balance text-4xl font-semibold leading-tight tracking-normal">
              让每一笔消费都能被记录、理解和追踪。
            </h2>
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
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import {
  Eye,
  EyeOff,
  FileSpreadsheet,
  FlaskConical,
  Loader2,
  LockKeyhole,
  MessageSquareText,
  ShieldCheck,
  Sparkles,
  UserPlus,
  UserRound,
  WalletCards
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
const showConfirm = ref(false)
const shaking = ref(false)
const form = reactive({ username: '', nickname: '', password: '', confirmPassword: '' })
const errors = reactive({ username: '', password: '', confirmPassword: '' })

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
  errors.password = form.password.length >= 6 ? '' : '密码至少 6 位'
  errors.confirmPassword = form.confirmPassword === form.password ? '' : '两次输入的密码不一致'
  const ok = !errors.username && !errors.password && !errors.confirmPassword
  if (!ok) triggerShake()
  return ok
}

async function handleRegister() {
  if (!validate()) return
  loading.value = true
  try {
    await authStore.register({
      username: form.username,
      password: form.password,
      nickname: form.nickname || undefined
    })
    feedback.success('注册成功')
    router.push('/chat')
  } catch {
  } finally {
    loading.value = false
  }
}
</script>
