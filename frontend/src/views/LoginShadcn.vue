<template>
  <div class="auth-page">
    <div class="auth-shell auth-shell-login">
      <section class="auth-hero">
        <div class="flex items-center gap-3">
          <div class="flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <MessageCircle />
          </div>
          <div>
            <p class="text-sm font-medium text-muted-foreground">智能财务</p>
            <h1 class="text-xl font-semibold tracking-normal">智财 Agent</h1>
          </div>
        </div>

        <div class="max-w-xl">
          <p class="mb-4 text-sm font-medium text-muted-foreground">个人智能财务代理系统</p>
          <h2 class="text-4xl font-semibold leading-tight tracking-normal">
            把账单、统计和财务助手收进一个安静的工作台。
          </h2>
          <p class="mt-5 text-base leading-7 text-muted-foreground">
            从导入账单到查看趋势，再到与 Agent 对话，关键财务动作都可以在这里完成。
          </p>
        </div>

        <div class="auth-feature-grid text-sm">
          <div class="rounded-lg border border-border bg-background p-4">
            <p class="text-muted-foreground">账单导入</p>
            <p class="mt-2 font-semibold">AI 识别</p>
          </div>
          <div class="rounded-lg border border-border bg-background p-4">
            <p class="text-muted-foreground">支出统计</p>
            <p class="mt-2 font-semibold">多维分析</p>
          </div>
          <div class="rounded-lg border border-border bg-background p-4">
            <p class="text-muted-foreground">智能助手</p>
            <p class="mt-2 font-semibold">对话决策</p>
          </div>
        </div>
      </section>

      <main class="auth-main">
        <Card class="w-full max-w-[420px]">
          <CardHeader>
            <div class="mb-2 flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground lg:hidden">
              <MessageCircle />
            </div>
            <CardTitle>登录智财 Agent</CardTitle>
            <CardDescription>继续管理你的账单、消费记录和财务画像。</CardDescription>
          </CardHeader>

          <CardContent>
            <form class="flex flex-col gap-4" @submit.prevent="handleLogin">
              <div class="flex flex-col gap-2">
                <Label for="username">用户名</Label>
                <div class="relative">
                  <UserRound class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="username"
                    v-model="form.username"
                    class="h-10 pl-9"
                    placeholder="请输入用户名"
                    autocomplete="username"
                    :aria-invalid="Boolean(errors.username)"
                  />
                </div>
                <p v-if="errors.username" class="text-sm text-destructive">{{ errors.username }}</p>
              </div>

              <div class="flex flex-col gap-2">
                <Label for="password">密码</Label>
                <div class="relative">
                  <LockKeyhole class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="password"
                    v-model="form.password"
                    class="h-10 pl-9"
                    type="password"
                    placeholder="请输入密码"
                    autocomplete="current-password"
                    :aria-invalid="Boolean(errors.password)"
                  />
                </div>
                <p v-if="errors.password" class="text-sm text-destructive">{{ errors.password }}</p>
              </div>

              <Button class="mt-2 h-10 w-full" size="lg" type="submit" :disabled="loading">
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
import { ArrowRight, Loader2, LockKeyhole, MessageCircle, UserRound } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { feedback } from '@/lib/feedback'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const form = reactive({ username: '', password: '' })
const errors = reactive({ username: '', password: '' })

function validate() {
  errors.username = form.username.trim() ? '' : '请输入用户名'
  errors.password = form.password ? '' : '请输入密码'
  return !errors.username && !errors.password
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

<style scoped>
.auth-page {
  min-height: 100vh;
  background: var(--background);
  color: var(--foreground);
}

.auth-shell {
  display: grid;
  min-height: 100vh;
  width: 100%;
  max-width: 1152px;
  margin: 0 auto;
}

.auth-shell-login {
  grid-template-columns: 1fr 420px;
}

.auth-hero {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  border-right: 1px solid var(--border);
  background: color-mix(in oklab, var(--muted) 30%, transparent);
  padding: 40px;
}

.auth-main {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
}

.auth-feature-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 1023px) {
  .auth-shell {
    display: block;
  }

  .auth-hero {
    display: none;
  }

  .auth-main {
    min-height: 100vh;
  }
}
</style>
