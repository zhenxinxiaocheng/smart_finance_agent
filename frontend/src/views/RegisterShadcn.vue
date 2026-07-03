<template>
  <div class="auth-page">
    <div class="auth-shell auth-shell-register">
      <main class="auth-main">
        <Card class="w-full max-w-[420px]">
          <CardHeader>
            <div class="mb-2 flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground">
              <WalletCards />
            </div>
            <CardTitle>创建账号</CardTitle>
            <CardDescription>加入智财 Agent，开始构建你的个人财务工作台。</CardDescription>
          </CardHeader>

          <CardContent>
            <form class="flex flex-col gap-4" @submit.prevent="handleRegister">
              <div class="flex flex-col gap-2">
                <Label for="username">用户名</Label>
                <Input
                  id="username"
                  v-model="form.username"
                  class="h-10"
                  placeholder="请输入用户名"
                  autocomplete="username"
                  :aria-invalid="Boolean(errors.username)"
                />
                <p v-if="errors.username" class="text-sm text-destructive">{{ errors.username }}</p>
              </div>

              <div class="flex flex-col gap-2">
                <Label for="nickname">昵称</Label>
                <Input id="nickname" v-model="form.nickname" class="h-10" placeholder="可选" autocomplete="nickname" />
              </div>

              <div class="flex flex-col gap-2">
                <Label for="password">密码</Label>
                <Input
                  id="password"
                  v-model="form.password"
                  class="h-10"
                  type="password"
                  placeholder="至少 6 位"
                  autocomplete="new-password"
                  :aria-invalid="Boolean(errors.password)"
                />
                <p v-if="errors.password" class="text-sm text-destructive">{{ errors.password }}</p>
              </div>

              <div class="flex flex-col gap-2">
                <Label for="confirmPassword">确认密码</Label>
                <Input
                  id="confirmPassword"
                  v-model="form.confirmPassword"
                  class="h-10"
                  type="password"
                  placeholder="再次输入密码"
                  autocomplete="new-password"
                  :aria-invalid="Boolean(errors.confirmPassword)"
                />
                <p v-if="errors.confirmPassword" class="text-sm text-destructive">{{ errors.confirmPassword }}</p>
              </div>

              <Button class="mt-2 h-10 w-full" size="lg" type="submit" :disabled="loading">
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

      <section class="auth-hero">
        <div class="flex items-center gap-3">
          <div class="flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <WalletCards />
          </div>
          <div>
            <p class="text-sm font-medium text-muted-foreground">智能财务</p>
            <h1 class="text-xl font-semibold tracking-normal">智财 Agent</h1>
          </div>
        </div>

        <div class="max-w-xl">
          <p class="mb-4 text-sm font-medium text-muted-foreground">开始前只需要一个账号</p>
          <h2 class="text-4xl font-semibold leading-tight tracking-normal">
            让每一笔消费都能被记录、理解和追踪。
          </h2>
          <p class="mt-5 text-base leading-7 text-muted-foreground">
            注册后可以导入账单、维护消费记录、查看统计报表，并通过智能助手查询财务状态。
          </p>
        </div>

        <div class="rounded-lg border border-border bg-background p-5 text-sm text-muted-foreground">
          shadcn-vue 组件已经接入：Card、Input、Label、Button 现在由源码组件驱动，不再只是改 Element Plus 主题色。
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { Loader2, UserPlus, WalletCards } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { feedback } from '@/lib/feedback'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const form = reactive({ username: '', nickname: '', password: '', confirmPassword: '' })
const errors = reactive({ username: '', password: '', confirmPassword: '' })

function validate() {
  errors.username = form.username.trim() ? '' : '请输入用户名'
  errors.password = form.password.length >= 6 ? '' : '密码至少 6 位'
  errors.confirmPassword = form.confirmPassword === form.password ? '' : '两次输入的密码不一致'
  return !errors.username && !errors.password && !errors.confirmPassword
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
    router.push('/statistics')
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

.auth-shell-register {
  grid-template-columns: 420px 1fr;
}

.auth-hero {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  border-left: 1px solid var(--border);
  background: color-mix(in oklab, var(--muted) 30%, transparent);
  padding: 40px;
}

.auth-main {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
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
