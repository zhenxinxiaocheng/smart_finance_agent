<template>
  <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_380px]">
    <Card class="min-w-0">
      <CardHeader class="gap-4">
        <div class="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
          <div>
            <CardTitle class="text-2xl">周期任务</CardTitle>
            <CardDescription>管理 Agent 自动复盘、预算检查和监控任务。</CardDescription>
          </div>
          <Button variant="outline" :disabled="loading" @click="loadSchedules">
            <RefreshCw data-icon="inline-start" :class="{ 'animate-spin': loading }" />
            刷新
          </Button>
        </div>
        <Input v-model="keyword" placeholder="搜索任务名、描述或执行内容" />
      </CardHeader>
      <CardContent>
        <div v-if="loading" class="rounded-lg border p-6 text-center text-sm text-muted-foreground">加载中...</div>
        <div v-else-if="filteredSchedules.length === 0" class="rounded-lg border p-6 text-center text-sm text-muted-foreground">暂无周期任务</div>
        <div v-else class="grid gap-3">
          <article
            v-for="schedule in filteredSchedules"
            :key="schedule.id"
            class="cursor-pointer rounded-lg border p-4 transition-colors hover:border-primary/50"
            :class="selectedSchedule?.id === schedule.id ? 'border-primary bg-muted/40' : ''"
            @click="selectSchedule(schedule)"
          >
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <h2 class="truncate text-base font-semibold">{{ schedule.name || '未命名任务' }}</h2>
                <p class="mt-1 line-clamp-2 text-sm text-muted-foreground">{{ schedule.description || schedule.taskQuery || '暂无描述' }}</p>
              </div>
              <Switch :model-value="isEnabled(schedule)" :disabled="schedule.updating" @click.stop @update:model-value="value => toggleSchedule(schedule, value)" />
            </div>
            <div class="mt-3 flex flex-wrap gap-2">
              <Badge variant="outline">{{ schedule.cronExpression }}</Badge>
              <Badge :variant="schedule.lastStatus === 'FAILED' ? 'destructive' : 'secondary'">{{ lastStatusLabel(schedule) }}</Badge>
              <Badge variant="outline">运行 {{ Number(schedule.runCount || 0) }} 次</Badge>
            </div>
          </article>
        </div>
      </CardContent>
    </Card>

    <Card class="h-fit min-w-0">
      <CardHeader>
        <CardTitle>{{ selectedSchedule?.name || '任务详情' }}</CardTitle>
        <CardDescription>{{ selectedSchedule ? lastStatusLabel(selectedSchedule) : '选择一个任务查看运行历史' }}</CardDescription>
      </CardHeader>
      <CardContent v-if="selectedSchedule" class="grid gap-4">
        <div class="grid gap-2 text-sm">
          <p><span class="text-muted-foreground">cron：</span>{{ selectedSchedule.cronExpression }}</p>
          <p><span class="text-muted-foreground">时区：</span>{{ selectedSchedule.timezone || 'Asia/Shanghai' }}</p>
          <p><span class="text-muted-foreground">下次：</span>{{ formatTime(selectedSchedule.nextRunAt) || '未计算' }}</p>
          <p><span class="text-muted-foreground">连续失败：</span>{{ Number(selectedSchedule.consecutiveFailures || 0) }} 次</p>
        </div>
        <pre class="max-h-40 overflow-auto rounded-lg border bg-muted/40 p-3 text-xs">{{ selectedSchedule.taskQuery || '暂无执行内容' }}</pre>
        <div class="flex flex-wrap gap-2">
          <Button v-if="canRetrySchedule(selectedSchedule)" :disabled="selectedSchedule.retrying" @click="retrySchedule(selectedSchedule)">恢复并重试</Button>
          <Button v-if="selectedSchedule.traceId" variant="outline" @click="openTrace(selectedSchedule.traceId)">查看 trace</Button>
        </div>
        <section class="grid gap-2">
          <div class="flex items-center justify-between gap-3">
            <h3 class="text-sm font-semibold">运行历史</h3>
            <Button size="sm" variant="ghost" :disabled="runsLoading" @click="loadRuns(selectedSchedule)">刷新</Button>
          </div>
          <div v-if="runsLoading" class="rounded-lg border p-4 text-center text-sm text-muted-foreground">加载中...</div>
          <div v-else-if="scheduleRuns.length === 0" class="rounded-lg border p-4 text-center text-sm text-muted-foreground">暂无历史</div>
          <article
            v-for="run in scheduleRuns"
            v-else
            :key="run.id"
            class="rounded-lg border p-3"
            :class="Number(route.query.runId || 0) === Number(run.id) ? 'border-primary bg-muted/40' : ''"
          >
            <div class="flex items-center justify-between gap-3">
              <Badge :variant="run.status === 'FAILED' ? 'destructive' : 'secondary'">{{ run.status === 'FAILED' ? '失败' : '成功' }}</Badge>
              <time class="text-xs text-muted-foreground">{{ formatTime(run.startedAt) }}</time>
            </div>
            <p class="mt-2 line-clamp-2 text-sm text-muted-foreground">{{ run.answer || run.errorMessage || '无结果摘要' }}</p>
          </article>
        </section>
      </CardContent>
      <CardContent v-else>
        <p class="text-sm text-muted-foreground">左侧选择一个周期任务。</p>
      </CardContent>
    </Card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RefreshCw } from '@lucide/vue'
import { feedback } from '@/lib/feedback'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { listAgentScheduleRunsAPI, listAgentSchedulesAPI, retryAgentScheduleAPI, setAgentScheduleEnabledAPI } from '@/api/agentSchedules'

const route = useRoute()
const router = useRouter()
const schedules = ref([])
const selectedSchedule = ref(null)
const scheduleRuns = ref([])
const loading = ref(false)
const runsLoading = ref(false)
const keyword = ref('')

const filteredSchedules = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return schedules.value.filter(item => !key || `${item.name || ''} ${item.description || ''} ${item.taskQuery || ''}`.toLowerCase().includes(key))
})

async function loadSchedules() {
  loading.value = true
  try {
    const res = await listAgentSchedulesAPI()
    schedules.value = Array.isArray(res.data) ? res.data : []
    selectedSchedule.value = resolveSelectedSchedule() || schedules.value[0] || null
    if (selectedSchedule.value) await loadRuns(selectedSchedule.value)
  } finally {
    loading.value = false
  }
}

function resolveSelectedSchedule() {
  const queryId = route.query.scheduleId ? Number(route.query.scheduleId) : null
  return queryId ? schedules.value.find(item => Number(item.id) === queryId) : null
}

async function selectSchedule(schedule) {
  selectedSchedule.value = schedule
  await loadRuns(schedule)
}

async function loadRuns(schedule) {
  if (!schedule?.id) return
  runsLoading.value = true
  try {
    const res = await listAgentScheduleRunsAPI(schedule.id)
    scheduleRuns.value = Array.isArray(res.data) ? res.data : []
  } finally {
    runsLoading.value = false
  }
}

async function toggleSchedule(schedule, enabled) {
  schedule.updating = true
  try {
    const res = await setAgentScheduleEnabledAPI(schedule.id, enabled)
    Object.assign(schedule, res.data || {}, { updating: false })
    feedback.success(enabled ? '任务已启用' : '任务已停用')
  } catch {
    schedule.updating = false
  }
}

async function retrySchedule(schedule) {
  schedule.retrying = true
  try {
    const res = await retryAgentScheduleAPI(schedule.id)
    Object.assign(schedule, res.data || {}, { retrying: false })
    feedback.success('已触发一次重试')
    await loadRuns(schedule)
  } catch {
    schedule.retrying = false
  }
}

function isEnabled(schedule) {
  return Number(schedule?.enabled ?? 0) === 1
}

function canRetrySchedule(schedule) {
  return schedule?.lastStatus === 'FAILED' || (!isEnabled(schedule) && Number(schedule?.consecutiveFailures || 0) >= 3)
}

function lastStatusLabel(schedule) {
  if (schedule?.lastStatus === 'FAILED') return '最近失败'
  if (schedule?.lastStatus === 'SUCCESS') return '最近成功'
  return isEnabled(schedule) ? '运行中' : '已停用'
}

function openTrace(traceId) {
  router.push({ path: '/chat', query: { traceId } })
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : ''
}

watch(() => route.query.scheduleId, loadSchedules)
onMounted(loadSchedules)
</script>
