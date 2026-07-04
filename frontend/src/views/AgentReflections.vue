<template>
  <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_360px]">
    <Card class="min-w-0">
      <CardHeader class="gap-4">
        <div class="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
          <div>
            <CardTitle class="text-2xl">Agent 反思</CardTitle>
            <CardDescription>运行后沉淀的记忆、Skill、周期任务候选和风险提示。</CardDescription>
          </div>
          <Button variant="outline" :disabled="loading" @click="loadReflections">
            <RefreshCw data-icon="inline-start" :class="{ 'animate-spin': loading }" />
            刷新
          </Button>
        </div>
        <div class="grid gap-3 md:grid-cols-[minmax(0,1fr)_160px]">
          <Input v-model="keyword" placeholder="搜索反思" />
          <select v-model="statusFilter" class="h-10 rounded-md border bg-background px-3 text-sm" @change="loadReflections">
            <option value="">全部状态</option>
            <option value="OPEN">待处理</option>
            <option value="ACCEPTED">已采纳</option>
            <option value="DISMISSED">已忽略</option>
          </select>
        </div>
      </CardHeader>
      <CardContent>
        <div v-if="loading" class="rounded-lg border p-6 text-center text-sm text-muted-foreground">加载中...</div>
        <div v-else-if="filteredReflections.length === 0" class="rounded-lg border p-6 text-center text-sm text-muted-foreground">暂无反思</div>
        <div v-else class="grid gap-3">
          <article
            v-for="reflection in filteredReflections"
            :key="reflection.id"
            class="cursor-pointer rounded-lg border p-4 transition-colors hover:border-primary/50"
            :class="selectedReflection?.id === reflection.id ? 'border-primary bg-muted/40' : ''"
            @click="selectedReflection = reflection"
          >
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <h2 class="truncate text-base font-semibold">{{ reflection.title || reflectionTypeLabel(reflection.suggestionType) }}</h2>
                <p class="mt-1 line-clamp-2 text-sm text-muted-foreground">{{ reflection.summary || '暂无摘要' }}</p>
              </div>
              <Badge :variant="reflection.suggestionType === 'RISK_WARNING' ? 'destructive' : 'outline'">{{ reflectionStatusLabel(reflection.status) }}</Badge>
            </div>
            <div class="mt-3 flex flex-wrap items-center gap-2">
              <Badge variant="secondary">{{ reflectionTypeLabel(reflection.suggestionType) }}</Badge>
              <Button v-if="reflection.traceId" class="h-auto px-0" variant="link" size="sm" @click.stop="openTrace(reflection.traceId)">trace</Button>
            </div>
          </article>
        </div>
      </CardContent>
    </Card>

    <Card class="h-fit min-w-0">
      <CardHeader>
        <CardTitle>{{ selectedReflection?.title || '反思详情' }}</CardTitle>
        <CardDescription>{{ selectedReflection ? reflectionTypeLabel(selectedReflection.suggestionType) : '选择一条反思查看证据' }}</CardDescription>
      </CardHeader>
      <CardContent v-if="selectedReflection" class="grid gap-4">
        <p class="text-sm text-muted-foreground">{{ selectedReflection.summary || '暂无摘要' }}</p>
        <div v-if="selectedReflection.status === 'OPEN'" class="flex flex-wrap gap-2">
          <Button v-if="canAcceptReflection(selectedReflection)" :disabled="selectedReflection.handling" @click="acceptReflection(selectedReflection)">生成待确认动作</Button>
          <Button variant="outline" :disabled="selectedReflection.handling" @click="dismissReflection(selectedReflection)">忽略</Button>
        </div>
        <pre class="max-h-96 overflow-auto rounded-lg border bg-muted/40 p-3 text-xs">{{ prettyPayload(selectedReflection.payload) }}</pre>
      </CardContent>
      <CardContent v-else>
        <p class="text-sm text-muted-foreground">左侧选择一条反思建议。</p>
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
import { acceptAgentReflectionAPI, dismissAgentReflectionAPI, listAgentReflectionsAPI } from '@/api/agentReflections'
import { buildScheduleDraftFromReflection, canAcceptReflection, filterAgentReflections, reflectionStatusLabel, reflectionTypeLabel } from '@/utils/agentReflectionFilters'

const route = useRoute()
const router = useRouter()
const reflections = ref([])
const selectedReflection = ref(null)
const loading = ref(false)
const keyword = ref('')
const statusFilter = ref(Object.prototype.hasOwnProperty.call(route.query, 'status') ? String(route.query.status || '') : 'OPEN')

const filteredReflections = computed(() => filterAgentReflections(reflections.value, { status: statusFilter.value, keyword: keyword.value }))

async function loadReflections() {
  loading.value = true
  try {
    const params = {}
    if (statusFilter.value) params.status = statusFilter.value
    const res = await listAgentReflectionsAPI(params)
    reflections.value = Array.isArray(res.data) ? res.data : []
    selectedReflection.value = resolveSelectedReflection() || filteredReflections.value[0] || null
  } finally {
    loading.value = false
  }
}

function resolveSelectedReflection() {
  const queryId = route.query.reflectionId ? Number(route.query.reflectionId) : null
  return queryId ? reflections.value.find(item => Number(item.id) === queryId) : null
}

async function acceptReflection(reflection) {
  if (!canAcceptReflection(reflection)) return
  reflection.handling = true
  try {
    const payload = reflection.suggestionType === 'SCHEDULE_CANDIDATE'
      ? buildScheduleDraftFromReflection(reflection)
      : null
    const res = await acceptAgentReflectionAPI(reflection.id, payload)
    Object.assign(reflection, res.data || {}, { handling: false })
    selectedReflection.value = reflection
    feedback.success('已生成待确认动作')
  } catch {
    reflection.handling = false
  }
}

async function dismissReflection(reflection) {
  if (!reflection || reflection.status !== 'OPEN') return
  reflection.handling = true
  try {
    const res = await dismissAgentReflectionAPI(reflection.id)
    Object.assign(reflection, res.data || {}, { handling: false })
    selectedReflection.value = reflection
    feedback.success('已忽略建议')
  } catch {
    reflection.handling = false
  }
}

function openTrace(traceId) {
  router.push({ path: '/chat', query: { traceId } })
}

function prettyPayload(payload) {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload || '暂无'
  }
}

watch(() => route.query.reflectionId, loadReflections)
watch(() => route.query.status, status => {
  statusFilter.value = Object.prototype.hasOwnProperty.call(route.query, 'status') ? String(status || '') : statusFilter.value
  loadReflections()
})
onMounted(loadReflections)
</script>
