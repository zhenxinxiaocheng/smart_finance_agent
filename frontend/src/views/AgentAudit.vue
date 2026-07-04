<template>
  <div class="grid gap-5">
    <div class="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
      <div>
        <h1 class="text-2xl font-semibold tracking-normal">Agent 审计</h1>
        <p class="mt-1 text-sm text-muted-foreground">统一查看反思、待确认动作、周期任务和运行记录。</p>
      </div>
      <Button variant="outline" :disabled="loading" @click="loadAudit">
        <RefreshCw data-icon="inline-start" :class="{ 'animate-spin': loading }" />
        刷新
      </Button>
    </div>

    <section class="grid gap-3 md:grid-cols-4">
      <Card v-for="item in summaryCards" :key="item.label">
        <CardHeader class="p-4">
          <CardDescription>{{ item.label }}</CardDescription>
          <CardTitle class="text-2xl">{{ item.value }}</CardTitle>
        </CardHeader>
      </Card>
    </section>

    <div class="grid gap-5 xl:grid-cols-[minmax(0,1fr)_320px]">
      <Card class="min-w-0">
        <CardHeader>
          <CardTitle>近期事件</CardTitle>
          <CardDescription>{{ events.length }} 条可追溯 Agent 事件</CardDescription>
        </CardHeader>
        <CardContent>
          <div v-if="loading" class="rounded-lg border p-6 text-center text-sm text-muted-foreground">加载中...</div>
          <div v-else-if="events.length === 0" class="rounded-lg border p-6 text-center text-sm text-muted-foreground">暂无审计事件</div>
          <div v-else class="grid gap-3">
            <article
              v-for="event in events"
              :key="event.id"
              class="grid gap-3 rounded-lg border p-4 md:grid-cols-[minmax(0,1fr)_auto]"
              :class="eventBorderClass(event)"
            >
              <div class="min-w-0">
                <div class="flex flex-wrap items-center gap-2">
                  <Badge variant="secondary">{{ sourceLabel(event.source) }}</Badge>
                  <Badge :variant="statusVariant(event)">{{ event.status || event.rawStatus || '未知' }}</Badge>
                  <time class="text-xs text-muted-foreground">{{ formatTime(event.time) || '未知时间' }}</time>
                </div>
                <h2 class="mt-3 truncate text-base font-semibold">{{ event.title }}</h2>
                <p class="mt-1 line-clamp-2 text-sm leading-6 text-muted-foreground">{{ event.summary }}</p>
                <Button v-if="event.traceId" class="mt-2 px-0" variant="link" size="sm" @click="openTrace(event.traceId)">
                  trace: {{ event.traceId }}
                </Button>
              </div>
              <div class="flex flex-wrap items-center justify-end gap-2">
                <Button v-if="canConfirmAction(event)" size="sm" :disabled="event.handling" @click="confirmActionEvent(event)">确认</Button>
                <Button v-if="canCancelAction(event)" size="sm" variant="outline" :disabled="event.handling" @click="cancelActionEvent(event)">取消</Button>
                <Button v-if="canDismissReflection(event)" size="sm" variant="outline" :disabled="event.handling" @click="dismissReflectionEvent(event)">忽略</Button>
                <Button size="sm" variant="ghost" @click="openTarget(event)">查看</Button>
              </div>
            </article>
          </div>
        </CardContent>
      </Card>

      <Card class="h-fit">
        <CardHeader>
          <CardTitle>快速入口</CardTitle>
          <CardDescription>处理 Agent 自我演化队列</CardDescription>
        </CardHeader>
        <CardContent class="grid gap-2">
          <Button variant="outline" class="justify-start" @click="router.push('/reflections?status=OPEN')">处理反思建议</Button>
          <Button variant="outline" class="justify-start" @click="router.push('/pending-actions')">确认待办动作</Button>
          <Button variant="outline" class="justify-start" @click="router.push('/schedules')">治理周期任务</Button>
          <Button variant="outline" class="justify-start" @click="router.push('/chat')">查看运行轨迹</Button>
        </CardContent>
      </Card>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { RefreshCw } from '@lucide/vue'
import { feedback } from '@/lib/feedback'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { getAgentAuditOverviewAPI } from '@/api/agentAudit'
import { dismissAgentReflectionAPI } from '@/api/agentReflections'
import { cancelPendingActionAPI, confirmPendingActionAPI } from '@/api/pendingAction'

const router = useRouter()
const loading = ref(false)
const summary = ref(defaultSummary())
const events = ref([])

const summaryCards = computed(() => [
  { label: '待处理反思', value: summary.value.openReflections },
  { label: '待确认动作', value: summary.value.pendingActions },
  { label: '异常任务', value: summary.value.failedSchedules },
  { label: '运行中任务', value: summary.value.activeSchedules }
])

async function loadAudit() {
  loading.value = true
  try {
    const res = await getAgentAuditOverviewAPI()
    summary.value = res.data?.summary || defaultSummary()
    events.value = Array.isArray(res.data?.events) ? res.data.events : []
  } finally {
    loading.value = false
  }
}

function defaultSummary() {
  return { openReflections: 0, pendingActions: 0, failedSchedules: 0, activeSchedules: 0 }
}

function openTarget(event) {
  if (event?.target) router.push(event.target)
}

function openTrace(traceId) {
  router.push({ path: '/chat', query: { traceId } })
}

function canConfirmAction(event) {
  return event?.source === 'ACTION' && event.rawStatus === 'PENDING' && Boolean(entityId(event, 'actionId'))
}

function canCancelAction(event) {
  return canConfirmAction(event)
}

function canDismissReflection(event) {
  return event?.source === 'REFLECTION' && event.rawStatus === 'OPEN' && Boolean(entityId(event, 'reflectionId'))
}

async function confirmActionEvent(event) {
  await handleEvent(event, () => confirmPendingActionAPI(entityId(event, 'actionId')), '动作已确认')
}

async function cancelActionEvent(event) {
  await handleEvent(event, () => cancelPendingActionAPI(entityId(event, 'actionId')), '动作已取消')
}

async function dismissReflectionEvent(event) {
  await handleEvent(event, () => dismissAgentReflectionAPI(entityId(event, 'reflectionId')), '已忽略建议')
}

async function handleEvent(event, action, message) {
  if (!event || event.handling) return
  event.handling = true
  try {
    await action()
    feedback.success(message)
    await loadAudit()
  } catch {
    event.handling = false
  }
}

function entityId(event, key) {
  return event?.target?.query?.[key] || null
}

function sourceLabel(source) {
  const labels = { REFLECTION: '反思', ACTION: '动作', SCHEDULE: '周期任务', SCHEDULE_RUN: '任务运行' }
  return labels[source] || source || '事件'
}

function statusVariant(event) {
  if (event?.severity === 'warning') return 'destructive'
  if (event?.severity === 'success') return 'secondary'
  return 'outline'
}

function eventBorderClass(event) {
  if (event?.severity === 'warning') return 'border-destructive/40'
  if (event?.severity === 'success') return 'border-emerald-500/40'
  if (event?.severity === 'pending') return 'border-primary/40'
  return ''
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : ''
}

onMounted(loadAudit)
</script>
