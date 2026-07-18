<template>
  <div class="flex h-screen overflow-hidden bg-muted/40 text-foreground dark:bg-background">
    <aside
      class="relative z-20 flex shrink-0 flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground transition-[width] duration-200"
      :class="isCollapse ? 'w-[72px]' : 'w-[72px] md:w-[260px]'"
    >
      <div class="flex h-16 items-center gap-3 border-b border-sidebar-border px-4">
        <div class="flex size-9 shrink-0 items-center justify-center rounded-xl bg-sidebar-primary text-sidebar-primary-foreground shadow-sm shadow-primary/20">
          <Zap />
        </div>
        <Transition name="fade">
          <div v-if="!isCollapse" class="hidden min-w-0 md:block">
            <p class="truncate text-sm font-semibold text-white">智财 Agent</p>
            <p class="truncate text-[11px] font-medium tracking-wide text-sidebar-foreground/55">DASHBOARD</p>
          </div>
        </Transition>
      </div>

      <ScrollArea class="min-h-0 flex-1">
        <nav class="flex flex-col gap-5 p-3">
          <div v-if="showChatConversationPanel" class="hidden md:block">
            <ChatConversationPanel />
          </div>
          <section v-for="group in navGroups" :key="group.label" class="flex flex-col gap-1">
            <div v-if="!isCollapse" class="hidden px-2 pb-1 text-[11px] font-semibold uppercase tracking-wider text-sidebar-foreground/45 md:block">
              {{ group.label }}
            </div>
            <button
              v-for="item in group.items"
              :key="item.path || item.label"
              type="button"
              :aria-label="item.label"
              :title="isCollapse ? item.label : undefined"
              :data-developing="item.disabled ? 'true' : undefined"
              class="flex h-10 items-center justify-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors md:justify-start"
              :class="navItemClass(item)"
              @click="navigate(item)"
            >
              <component :is="item.icon" class="shrink-0" />
              <Transition name="fade">
                <span v-if="!isCollapse" class="hidden truncate md:inline">{{ item.label }}</span>
              </Transition>
              <Badge v-if="!isCollapse && item.badge" class="ml-auto hidden ring-0 md:inline-flex" :class="navBadgeClass(item)" variant="secondary">
                {{ item.badge }}
              </Badge>
            </button>
          </section>
        </nav>
      </ScrollArea>

      <div class="border-t border-sidebar-border p-3">
        <button
          type="button"
          class="flex h-12 w-full items-center gap-3 rounded-lg px-2 text-left transition-colors hover:bg-sidebar-accent"
          @click="logoutOpen = true"
        >
          <Avatar class="size-8">
            <AvatarFallback class="bg-sidebar-primary text-sidebar-primary-foreground">{{ userInitial }}</AvatarFallback>
          </Avatar>
          <Transition name="fade">
            <div v-if="!isCollapse" class="hidden min-w-0 flex-1 md:block">
              <p class="truncate text-sm font-semibold text-white">{{ authStore.username }}</p>
              <p class="truncate text-xs text-sidebar-foreground/55">Finance Admin</p>
            </div>
          </Transition>
          <LogOut v-if="!isCollapse" class="hidden text-sidebar-foreground/45 md:block" />
        </button>
      </div>

      <Button
        variant="outline"
        size="icon-xs"
        class="absolute -right-3 top-[112px] z-30 rounded-full border-sidebar-border bg-background text-foreground shadow-sm"
        @click="isCollapse = !isCollapse"
      >
        <PanelLeftOpen v-if="isCollapse" />
        <PanelLeftClose v-else />
      </Button>
    </aside>

    <div class="flex min-w-0 flex-1 flex-col">
      <header class="sticky top-0 z-10 flex h-16 shrink-0 items-center justify-end gap-4 border-b border-border bg-background/90 px-4 backdrop-blur-xl">
        <div class="ml-auto flex items-center gap-2">
          <Button class="hidden bg-primary text-primary-foreground md:inline-flex" @click="navigate('/transactions')">
            <Plus data-icon="inline-start" />
            新增记录
          </Button>
          <ThemeCustomizer />
          <DropdownMenu v-model:open="notificationOpen" @update:open="handleNotificationOpen">
            <DropdownMenuTrigger as-child>
              <Button variant="ghost" size="icon" aria-label="通知" class="relative">
                <Bell />
                <span
                  v-if="notificationCount"
                  class="absolute -right-0.5 -top-0.5 flex size-4 items-center justify-center rounded-full bg-destructive text-[10px] font-semibold text-destructive-foreground"
                >
                  {{ notificationCount > 9 ? '9+' : notificationCount }}
                </span>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" class="w-80 p-0">
              <div class="flex items-start justify-between gap-3 p-3">
                <div>
                  <p class="text-sm font-semibold">通知</p>
                  <p class="mt-0.5 text-xs text-muted-foreground">预算预警和系统提醒</p>
                </div>
                <Badge v-if="notificationCount" variant="outline">{{ notificationCount }} 条未读</Badge>
              </div>
              <DropdownMenuSeparator />
              <div class="max-h-80 overflow-y-auto p-2">
                <div v-if="notificationLoading" class="rounded-lg px-3 py-6 text-center text-sm text-muted-foreground">
                  正在加载通知...
                </div>
                <div v-else-if="!notificationCount" class="rounded-lg px-3 py-6 text-center text-sm text-muted-foreground">
                  暂无未读通知
                </div>
                <button
                  v-for="alert in safeUnreadAlerts"
                  v-else
                  :key="alert.id"
                  type="button"
                  class="w-full rounded-lg px-3 py-2 text-left transition-colors hover:bg-muted"
                  @click="markNotificationRead(alert)"
                >
                  <div class="flex items-start justify-between gap-2">
                    <p class="line-clamp-2 text-sm font-medium">{{ alert.message }}</p>
                    <Badge :variant="alert.severity === 'CRITICAL' ? 'destructive' : 'secondary'">
                      {{ notificationSeverityLabel(alert.severity) }}
                    </Badge>
                  </div>
                  <div class="mt-2 flex items-center justify-between text-xs text-muted-foreground">
                    <span>{{ alert.category === 'ALL' ? '总预算' : alert.category }} · {{ notificationTypeLabel(alert.alertType) }}</span>
                    <time>{{ formatNotificationTime(alert.createdAt) }}</time>
                  </div>
                </button>
              </div>
              <DropdownMenuSeparator />
              <div class="flex items-center justify-between gap-2 p-2">
                <Button variant="ghost" size="sm" :disabled="!notificationCount" @click="markAllNotificationsRead">全部已读</Button>
                <Button variant="ghost" size="sm" @click="navigate('/profile')">查看预算</Button>
              </div>
            </DropdownMenuContent>
          </DropdownMenu>

          <DropdownMenu>
            <DropdownMenuTrigger as-child>
              <Button variant="ghost" class="h-10 gap-2 px-2">
                <Avatar class="size-8">
                  <AvatarFallback class="bg-primary/10 text-primary">{{ userInitial }}</AvatarFallback>
                </Avatar>
                <span class="hidden max-w-28 truncate text-sm md:inline">{{ authStore.username }}</span>
                <ChevronDown />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" class="w-48">
              <DropdownMenuLabel>{{ authStore.username }}</DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuItem @click="logoutOpen = true">
                <LogOut />
                退出登录
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>

      <main class="flex-1 overflow-y-auto overflow-x-hidden">
        <div class="mx-auto w-full" :style="{ maxWidth: 'var(--app-container-max)' }">
          <div class="p-[var(--app-page-padding)]">
            <router-view v-slot="{ Component }">
              <transition name="fade" mode="out-in">
                <component :is="Component" />
              </transition>
            </router-view>
          </div>
        </div>
      </main>
    </div>

    <Dialog v-model:open="logoutOpen">
      <DialogContent>
        <DialogHeader>
          <DialogTitle>退出登录？</DialogTitle>
          <DialogDescription>退出后需要重新登录才能继续使用智财 Agent。</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" @click="logoutOpen = false">取消</Button>
          <Button variant="destructive" @click="confirmLogout">确认退出</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import {
  BarChart3,
  Bell,
  Blocks,
  CalendarClock,
  ChartCandlestick,
  ChevronDown,
  ListChecks,
  LogOut,
  MessageSquareText,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  ShieldCheck,
  UploadCloud,
  UserRound,
  Zap
} from '@lucide/vue'
import ThemeCustomizer from '@/components/theme/ThemeCustomizer.vue'
import ChatConversationPanel from '@/components/chat/ChatConversationPanel.vue'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from '@/components/ui/dropdown-menu'
import { ScrollArea } from '@/components/ui/scroll-area'
import { cn } from '@/lib/utils'
import { getUnreadAlertsAPI, markAlertReadAPI } from '@/api/alert'
import { useChatConversationsStore } from '@/stores/chatConversations'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const conversationStore = useChatConversationsStore()
const { conversations, conversationLoading } = storeToRefs(conversationStore)

const isCollapse = ref(false)
const logoutOpen = ref(false)
const notificationOpen = ref(false)
const notificationLoading = ref(false)
const unreadAlerts = ref([])
let notificationTimer = null
const activeMenu = computed(() => route.path)
const showChatConversationPanel = computed(() => !isCollapse.value && conversations.value.length > 0)
const userInitial = computed(() => authStore.username?.charAt(0)?.toUpperCase() || 'U')
const safeUnreadAlerts = computed(() => Array.isArray(unreadAlerts.value) ? unreadAlerts.value : [])
const notificationCount = computed(() => safeUnreadAlerts.value.length)

const navGroups = [
  {
    label: 'AGENT',
    items: [
      { path: '/chat', label: '新对话', icon: MessageSquareText },
      { path: '/profile', label: '财务画像', icon: UserRound },
      { path: '/stocks', label: '投资分析', icon: ChartCandlestick },
      { path: '/agent-audit', label: 'Agent 审计', icon: ShieldCheck },
      { path: '/schedules', label: '周期任务', icon: CalendarClock },
      { path: '/skills', label: 'Agent 技能', icon: Blocks }
    ]
  },
  {
    label: 'WORKSPACE',
    items: [
      { path: '/statistics', label: '统计', icon: BarChart3 },
      { path: '/bill-import', label: '账单导入', icon: UploadCloud },
      { path: '/transactions', label: '消费记录', icon: ListChecks }
    ]
  }
]

onMounted(() => {
  if (!conversationLoading.value && !conversations.value.length) {
    conversationStore.loadConversations()
  }
})

function navItemClass(item) {
  const path = typeof item === 'string' ? item : item.path
  const disabled = typeof item === 'object' && item.disabled
  const active = activeMenu.value === path
  return cn(
    active
      ? 'bg-sidebar-accent text-sidebar-primary shadow-sm'
      : 'text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
    disabled && 'cursor-default opacity-80',
    isCollapse.value ? 'justify-center px-0' : ''
  )
}

function navBadgeClass(item) {
  return item.disabled
    ? 'bg-amber-500/10 text-amber-500 dark:text-amber-400'
    : 'bg-sidebar-primary/20 text-sidebar-primary'
}

function navigate(item) {
  if (typeof item === 'object' && item.disabled) {
    window.dispatchEvent(new CustomEvent('app-feedback', {
      detail: {
        type: 'info',
        title: '股票分析开发中',
        message: '这个分支已预留，后续会接入行情、持仓和投资分析能力。'
      }
    }))
    return
  }

  const path = typeof item === 'string' ? item : item.path
  router.push(path)
}

async function fetchNotifications() {
  notificationLoading.value = true
  try {
    const res = await getUnreadAlertsAPI()
    unreadAlerts.value = Array.isArray(res.data) ? res.data : []
  } catch (err) {
    console.error('获取通知失败:', err)
  } finally {
    notificationLoading.value = false
  }
}

function handleNotificationOpen(open) {
  if (open) {
    fetchNotifications()
  }
}

async function markNotificationRead(alert) {
  if (!alert?.id) return
  try {
    await markAlertReadAPI(alert.id)
    unreadAlerts.value = safeUnreadAlerts.value.filter(item => item.id !== alert.id)
  } catch (err) {
    console.error('标记通知已读失败:', err)
  }
}

async function markAllNotificationsRead() {
  const alerts = [...safeUnreadAlerts.value]
  if (!alerts.length) return
  try {
    await Promise.all(alerts.map(alert => markAlertReadAPI(alert.id)))
    unreadAlerts.value = []
  } catch (err) {
    console.error('批量标记通知已读失败:', err)
    fetchNotifications()
  }
}

function notificationSeverityLabel(severity) {
  return severity === 'CRITICAL' ? '严重' : '提醒'
}

function notificationTypeLabel(type) {
  if (type === 'OVERRUN') return '已超支'
  if (type === 'THRESHOLD') return '接近预算'
  if (type === 'TREND') return '趋势提醒'
  return '预算预警'
}

function formatNotificationTime(value) {
  if (!value) return ''
  return String(value).slice(0, 16).replace('T', ' ')
}

function confirmLogout() {
  logoutOpen.value = false
  authStore.logout()
  router.push('/login')
}

onMounted(() => {
  fetchNotifications()
  notificationTimer = window.setInterval(fetchNotifications, 30000)
})

onUnmounted(() => {
  if (notificationTimer) {
    window.clearInterval(notificationTimer)
    notificationTimer = null
  }
})
</script>
