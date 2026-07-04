<template>
  <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_360px]">
    <Card class="min-w-0">
      <CardHeader class="gap-4">
        <div class="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
          <div>
            <CardTitle class="text-2xl">待确认动作</CardTitle>
            <CardDescription>审阅并执行 Agent 起草的交易、预算、记忆、Skill 和周期任务。</CardDescription>
          </div>
          <Button variant="outline" :disabled="loading" @click="loadActions">
            <RefreshCw data-icon="inline-start" :class="{ 'animate-spin': loading }" />
            刷新
          </Button>
        </div>
        <div class="grid gap-3 md:grid-cols-[minmax(0,1fr)_160px]">
          <Input v-model="keyword" placeholder="搜索动作" />
          <select v-model="statusFilter" class="h-10 rounded-md border bg-background px-3 text-sm">
            <option value="">全部状态</option>
            <option value="PENDING">待确认</option>
            <option value="CONFIRMED">已确认</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </div>
      </CardHeader>
      <CardContent>
        <div v-if="loading" class="rounded-lg border p-6 text-center text-sm text-muted-foreground">加载中...</div>
        <div v-else-if="filteredActions.length === 0" class="rounded-lg border p-6 text-center text-sm text-muted-foreground">暂无动作</div>
        <div v-else class="grid gap-3">
          <article
            v-for="action in filteredActions"
            :key="action.id"
            class="cursor-pointer rounded-lg border p-4 transition-colors hover:border-primary/50"
            :class="selectedAction?.id === action.id ? 'border-primary bg-muted/40' : ''"
            @click="selectedAction = action"
          >
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <h2 class="truncate text-base font-semibold">{{ action.title || actionTypeLabel(action.actionType) }}</h2>
                <p class="mt-1 line-clamp-2 text-sm text-muted-foreground">{{ action.summary || '暂无摘要' }}</p>
              </div>
              <Badge :variant="action.status === 'PENDING' ? 'outline' : 'secondary'">{{ actionStatusLabel(action.status) }}</Badge>
            </div>
            <div class="mt-3 flex flex-wrap items-center gap-2">
              <Badge variant="secondary">{{ actionTypeLabel(action.actionType) }}</Badge>
              <time class="text-xs text-muted-foreground">{{ formatTime(action.createdAt) }}</time>
            </div>
          </article>
        </div>
      </CardContent>
    </Card>

    <Card class="h-fit min-w-0">
      <CardHeader>
        <CardTitle>{{ selectedAction?.title || '动作详情' }}</CardTitle>
        <CardDescription>{{ selectedAction ? actionTypeLabel(selectedAction.actionType) : '选择一个动作查看载荷和链路' }}</CardDescription>
      </CardHeader>
      <CardContent v-if="selectedAction" class="grid gap-4">
        <p class="text-sm text-muted-foreground">{{ selectedAction.summary || '暂无摘要' }}</p>
        <div class="grid gap-2">
          <div v-for="step in actionTimeline(selectedAction)" :key="step.key" class="rounded-lg border p-3">
            <div class="flex items-center justify-between gap-3">
              <span class="text-sm font-medium">{{ step.title }}</span>
              <Badge variant="outline">{{ step.status }}</Badge>
            </div>
            <p class="mt-1 text-sm text-muted-foreground">{{ step.description }}</p>
            <p v-if="step.meta" class="mt-1 text-xs text-muted-foreground">{{ step.meta }}</p>
          </div>
        </div>
        <pre class="max-h-80 overflow-auto rounded-lg border bg-muted/40 p-3 text-xs">{{ prettyPayload(selectedAction) }}</pre>
        <div v-if="selectedAction.status === 'PENDING'" class="flex gap-2">
          <Button :disabled="selectedAction.handling" @click="confirmAction(selectedAction)">确认执行</Button>
          <Button variant="outline" :disabled="selectedAction.handling" @click="cancelAction(selectedAction)">取消</Button>
        </div>
      </CardContent>
      <CardContent v-else>
        <p class="text-sm text-muted-foreground">左侧选择一项待确认动作。</p>
      </CardContent>
    </Card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { RefreshCw } from '@lucide/vue'
import { feedback } from '@/lib/feedback'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { cancelPendingActionAPI, confirmPendingActionAPI, listPendingActionsAPI } from '@/api/pendingAction'
import { actionStatusLabel, actionTypeLabel, buildPendingActionTimeline, filterPendingActions, parseActionPayload } from '@/utils/agentPendingActions'

const route = useRoute()
const actions = ref([])
const selectedAction = ref(null)
const loading = ref(false)
const keyword = ref('')
const statusFilter = ref(route.query.actionId ? '' : 'PENDING')

const filteredActions = computed(() => filterPendingActions(actions.value, { status: statusFilter.value, keyword: keyword.value }))

async function loadActions() {
  loading.value = true
  try {
    const res = await listPendingActionsAPI()
    actions.value = Array.isArray(res.data) ? res.data : []
    selectedAction.value = resolveSelectedAction() || filteredActions.value[0] || null
  } finally {
    loading.value = false
  }
}

function resolveSelectedAction() {
  const queryId = route.query.actionId ? Number(route.query.actionId) : null
  return queryId ? actions.value.find(item => Number(item.id) === queryId) : null
}

async function confirmAction(action) {
  await handleAction(action, () => confirmPendingActionAPI(action.id), '动作已确认')
}

async function cancelAction(action) {
  await handleAction(action, () => cancelPendingActionAPI(action.id), '动作已取消')
}

async function handleAction(action, fn, message) {
  if (!action || action.status !== 'PENDING' || action.handling) return
  action.handling = true
  try {
    const res = await fn()
    Object.assign(action, res.data || {}, { handling: false })
    selectedAction.value = action
    feedback.success(message)
  } catch {
    action.handling = false
  }
}

function actionTimeline(action) {
  return buildPendingActionTimeline(action)
}

function prettyPayload(action) {
  const payload = parseActionPayload(action)
  return Object.keys(payload).length ? JSON.stringify(payload, null, 2) : '暂无'
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : ''
}

watch(() => route.query.actionId, loadActions)
onMounted(loadActions)
</script>
