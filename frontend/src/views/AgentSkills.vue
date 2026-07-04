<template>
  <div class="grid h-full gap-5 overflow-auto lg:grid-cols-[minmax(0,1fr)_380px]">
    <Card class="min-w-0">
      <CardHeader class="gap-4">
        <div class="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
          <div class="min-w-0">
            <CardTitle class="text-2xl">Agent 技能</CardTitle>
            <CardDescription>启用、禁用和审计 Agent 可使用的 Skills。</CardDescription>
          </div>
          <Button variant="outline" :disabled="loading" @click="loadSkills">
            <RefreshCw data-icon="inline-start" :class="{ 'animate-spin': loading }" />
            刷新
          </Button>
        </div>

        <div class="grid gap-3 md:grid-cols-[minmax(0,1fr)_220px]">
          <div class="relative">
            <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input v-model="keyword" class="pl-9" placeholder="搜索技能" />
          </div>
          <Select v-model="category">
            <SelectTrigger>
              <SelectValue placeholder="全部分类" />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                <SelectItem value="ALL">全部分类</SelectItem>
                <SelectItem v-for="item in categories" :key="item" :value="item">{{ item }}</SelectItem>
              </SelectGroup>
            </SelectContent>
          </Select>
        </div>
      </CardHeader>

      <CardContent>
        <div v-if="loading" class="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <Skeleton v-for="index in 6" :key="index" class="h-36 rounded-lg" />
        </div>
        <Alert v-else-if="filteredSkills.length === 0">
          <AlertTitle>暂无技能</AlertTitle>
          <AlertDescription>没有找到匹配当前筛选条件的 Skill。</AlertDescription>
        </Alert>
        <div v-else class="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <Card
            v-for="skill in filteredSkills"
            :key="skill.id"
            class="cursor-pointer transition-colors"
            :class="[
              selectedSkill?.id === skill.id ? 'border-primary bg-muted/40' : 'hover:border-primary/40',
              !isEnabled(skill) ? 'opacity-60' : ''
            ]"
            @click="selectSkill(skill)"
          >
            <CardHeader class="gap-3 p-4">
              <div class="flex items-start justify-between gap-3">
                <div class="min-w-0">
                  <CardTitle class="truncate text-base">{{ skill.name || skill.skillKey }}</CardTitle>
                  <CardDescription class="truncate">{{ skill.skillKey }}</CardDescription>
                </div>
                <Switch
                  :model-value="isEnabled(skill)"
                  :disabled="skill.updating"
                  @click.stop
                  @update:model-value="value => toggleSkill(skill, value)"
                />
              </div>
            </CardHeader>
            <CardContent class="flex flex-col gap-4 p-4 pt-0">
              <p class="line-clamp-3 min-h-[60px] text-sm leading-5 text-muted-foreground">
                {{ skill.description || '暂无描述' }}
              </p>
              <div class="flex flex-wrap gap-2">
                <Badge variant="secondary">{{ skill.category || '未分类' }}</Badge>
                <Badge :variant="riskVariant(skill.riskLevel)">{{ riskLabel(skill.riskLevel) }}</Badge>
                <Badge variant="outline">{{ sourceLabel(skill) }}</Badge>
              </div>
            </CardContent>
          </Card>
        </div>
      </CardContent>
    </Card>

    <Card class="min-w-0 lg:sticky lg:top-0 lg:h-fit">
      <template v-if="selectedSkill">
        <CardHeader class="gap-3">
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <CardTitle class="truncate">{{ selectedSkill.name || selectedSkill.skillKey }}</CardTitle>
              <CardDescription>{{ selectedSkill.sourceType }} · {{ selectedSkill.version || '1.0.0' }}</CardDescription>
            </div>
            <Button v-if="!Number(selectedSkill.builtIn)" variant="destructive" size="sm" @click="deleteSkill(selectedSkill)">
              <Trash2 data-icon="inline-start" />
              卸载
            </Button>
          </div>
        </CardHeader>

        <CardContent class="flex flex-col gap-5">
          <div class="grid gap-3">
            <div class="rounded-lg border border-border p-3">
              <p class="text-xs text-muted-foreground">风险等级</p>
              <p class="mt-1 text-sm font-medium">{{ riskLabel(selectedSkill.riskLevel) }}</p>
            </div>
            <div class="rounded-lg border border-border p-3">
              <p class="text-xs text-muted-foreground">绑定工具</p>
              <p class="mt-1 break-words text-sm font-medium">{{ selectedSkill.boundTools || '无' }}</p>
            </div>
            <div class="rounded-lg border border-border p-3">
              <p class="text-xs text-muted-foreground">来源</p>
              <p class="mt-1 break-words text-sm font-medium">{{ selectedSkill.sourceUri || '-' }}</p>
            </div>
          </div>

          <Separator />

          <section class="flex flex-col gap-3">
            <h3 class="text-sm font-semibold">Skill 说明</h3>
            <ScrollArea class="h-64 rounded-lg border bg-muted/30 p-3">
              <pre class="whitespace-pre-wrap break-words font-mono text-xs leading-6 text-foreground">{{ selectedSkill.instructionText || selectedSkill.description || '暂无说明' }}</pre>
            </ScrollArea>
          </section>

          <section class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-3">
              <h3 class="text-sm font-semibold">调用历史</h3>
              <Button size="sm" variant="ghost" :disabled="invocationLoading" @click="loadInvocations(selectedSkill)">
                <RefreshCw data-icon="inline-start" :class="{ 'animate-spin': invocationLoading }" />
                刷新
              </Button>
            </div>
            <Skeleton v-if="invocationLoading" class="h-24 rounded-lg" />
            <Alert v-else-if="invocations.length === 0">
              <AlertTitle>暂无调用记录</AlertTitle>
              <AlertDescription>这个 Skill 暂时还没有被 Agent 调用。</AlertDescription>
            </Alert>
            <div v-else class="grid gap-2">
              <div v-for="item in invocations" :key="item.id" class="rounded-lg border p-3">
                <div class="flex items-center justify-between gap-3">
                  <span class="truncate text-sm font-medium">{{ item.skillName }}</span>
                  <Badge :variant="item.success ? 'secondary' : 'destructive'">
                    {{ item.success ? '成功' : item.blocked ? '已拦截' : '失败' }}
                  </Badge>
                </div>
                <p class="mt-2 text-sm leading-5 text-muted-foreground">{{ item.summary || '无摘要' }}</p>
                <time class="mt-2 block text-xs text-muted-foreground">{{ formatTime(item.createdAt) }}</time>
              </div>
            </div>
          </section>
        </CardContent>
      </template>

      <CardContent v-else class="p-6">
        <Alert>
          <AlertTitle>选择一个技能</AlertTitle>
          <AlertDescription>点击左侧 Skill 后查看说明、风险和调用历史。</AlertDescription>
        </Alert>
      </CardContent>
    </Card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { RefreshCw, Search, Trash2 } from '@lucide/vue'
import { useRoute } from 'vue-router'
import { confirmAction, feedback } from '@/lib/feedback'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue
} from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import {
  deleteAgentSkillAPI,
  listAgentSkillsAPI,
  listSkillInvocationsAPI,
  setAgentSkillEnabledAPI
} from '../api/agentSkills'

const route = useRoute()
const skills = ref([])
const selectedSkill = ref(null)
const invocations = ref([])
const loading = ref(false)
const invocationLoading = ref(false)
const keyword = ref('')
const category = ref('ALL')

const categories = computed(() => [...new Set(skills.value.map(skill => skill.category).filter(Boolean))])

const filteredSkills = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return skills.value.filter(skill => {
    const matchKeyword = !key
      || `${skill.name || ''} ${skill.skillKey || ''} ${skill.description || ''}`.toLowerCase().includes(key)
    const matchCategory = category.value === 'ALL' || skill.category === category.value
    return matchKeyword && matchCategory
  })
})

function isEnabled(skill) {
  return Number(skill?.enabled ?? 0) === 1
}

function sourceLabel(skill) {
  if (Number(skill?.builtIn ?? 0) === 1) return '内置'
  return skill?.sourceType || '外部'
}

function riskLabel(risk) {
  if (risk === 'REQUIRES_CONFIRMATION') return '需确认'
  if (risk === 'EXTERNAL_INFORMATION') return '外部信息'
  return '只读'
}

function riskVariant(risk) {
  if (risk === 'REQUIRES_CONFIRMATION') return 'destructive'
  if (risk === 'EXTERNAL_INFORMATION') return 'outline'
  return 'secondary'
}

async function loadSkills() {
  loading.value = true
  try {
    const res = await listAgentSkillsAPI()
    skills.value = Array.isArray(res.data) ? res.data : []
    selectedSkill.value = resolveSelectedSkill() || skills.value[0] || null
    if (selectedSkill.value) {
      await loadInvocations(selectedSkill.value)
    }
  } finally {
    loading.value = false
  }
}

function resolveSelectedSkill() {
  const queryId = route.query.skillId ? Number(route.query.skillId) : null
  if (queryId) {
    const matched = skills.value.find(item => Number(item.id) === queryId)
    if (matched) return matched
  }
  if (selectedSkill.value) {
    return skills.value.find(item => item.id === selectedSkill.value.id) || null
  }
  return null
}

async function toggleSkill(skill, enabled) {
  skill.updating = true
  try {
    const res = await setAgentSkillEnabledAPI(skill.id, enabled)
    Object.assign(skill, res.data || {}, { updating: false })
    if (selectedSkill.value?.id === skill.id) {
      selectedSkill.value = skill
    }
  } catch {
    skill.updating = false
  }
}

async function deleteSkill(skill) {
  const confirmed = await confirmAction(`确定卸载 ${skill.name || skill.skillKey} 吗？`)
  if (!confirmed) return
  await deleteAgentSkillAPI(skill.id)
  feedback.success('技能已卸载')
  selectedSkill.value = null
  await loadSkills()
}

function selectSkill(skill) {
  selectedSkill.value = skill
  loadInvocations(skill)
}

async function loadInvocations(skill) {
  if (!skill) return
  invocationLoading.value = true
  try {
    const res = await listSkillInvocationsAPI({ skillName: skill.skillKey, limit: 30 })
    invocations.value = Array.isArray(res.data) ? res.data : []
  } finally {
    invocationLoading.value = false
  }
}

function formatTime(value) {
  return value ? String(value).slice(0, 19) : ''
}

onMounted(async () => {
  await loadSkills()
})

watch(() => route.query.skillId, async () => {
  const matched = resolveSelectedSkill()
  if (matched) {
    selectedSkill.value = matched
    await loadInvocations(matched)
  }
})
</script>
