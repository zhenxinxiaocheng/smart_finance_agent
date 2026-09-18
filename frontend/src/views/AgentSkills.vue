<template>
  <div class="h-full overflow-auto p-1">
    <div class="mb-6 flex items-center justify-between gap-4">
      <h1 class="text-2xl font-semibold">技能</h1>
      <Button variant="outline" :disabled="loading" @click="loadSkills"><RefreshCw class="mr-2 size-4" />刷新</Button>
    </div>
    <div class="mb-5 flex flex-wrap gap-3">
      <Input v-model="keyword" class="min-w-48 flex-1" placeholder="搜索技能" aria-label="搜索技能" />
      <Select v-model="category"><SelectTrigger class="w-40"><SelectValue /></SelectTrigger><SelectContent>
        <SelectItem value="ALL">全部分类</SelectItem>
        <SelectItem v-for="item in categories" :key="item" :value="item">{{ item }}</SelectItem>
      </SelectContent></Select>
    </div>
    <p v-if="loadError" role="alert" class="py-6 text-destructive">技能加载失败，请刷新重试。</p>
    <div v-else-if="loading" class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3"><Skeleton v-for="n in 6" :key="n" class="h-40 rounded-xl" /></div>
    <p v-else-if="!filteredSkills.length" class="py-10 text-center text-muted-foreground">没有找到匹配的技能</p>
    <div v-else class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
      <article v-for="skill in filteredSkills" :key="skill.id" class="flex flex-col rounded-xl border bg-card p-5" :class="!isEnabled(skill) ? 'opacity-60' : ''">
        <div class="flex items-start justify-between gap-3">
          <button class="text-left text-base font-semibold hover:text-primary focus-visible:outline-primary" @click="selectSkill(skill)">{{ title(skill) }}</button>
          <Switch :aria-label="`启用${title(skill)}`" :model-value="isEnabled(skill)" :disabled="skill.updating" @update:model-value="value => toggleSkill(skill, value)" />
        </div>
        <p class="mt-3 text-sm leading-6 text-muted-foreground">{{ skill.userDescription || '查看详情了解使用方式' }}</p>
        <div class="mt-auto flex items-center gap-2 pt-4">
          <Badge variant="secondary">{{ skill.category || '其他' }}</Badge>
          <Badge v-if="skill.riskLevel === 'REQUIRES_CONFIRMATION'" variant="outline">需要确认</Badge>
          <span v-if="!isEnabled(skill)" class="text-xs text-muted-foreground">已停用</span>
          <Button class="ml-auto" variant="ghost" size="sm" @click="selectSkill(skill)">详情</Button>
        </div>
      </article>
    </div>
    <Sheet v-model:open="detailOpen">
      <SheetContent class="w-full overflow-y-auto sm:max-w-lg">
        <SheetHeader><SheetTitle>{{ selectedSkill ? title(selectedSkill) : '技能详情' }}</SheetTitle>
          <SheetDescription>{{ selectedSkill?.userDescription || '查看使用方式与执行记录' }}</SheetDescription>
        </SheetHeader>
        <div v-if="selectedSkill" class="space-y-6 p-4">
          <div class="flex items-center justify-between rounded-lg border p-3"><span>{{ isEnabled(selectedSkill) ? '已启用' : '已停用' }}</span>
            <Switch :aria-label="`启用${title(selectedSkill)}`" :model-value="isEnabled(selectedSkill)" :disabled="selectedSkill.updating" @update:model-value="value => toggleSkill(selectedSkill, value)" />
          </div>
          <section v-if="selectedSkill.example"><h2 class="mb-2 font-medium">你可以这样说</h2><p class="rounded-lg bg-muted p-3 text-sm leading-6">{{ selectedSkill.example }}</p></section>
          <section>
            <div class="mb-3 flex items-center justify-between"><h2 class="font-medium">最近使用</h2><Button variant="ghost" size="sm" :disabled="invocationLoading" @click="loadInvocations(selectedSkill)">刷新</Button></div>
            <Skeleton v-if="invocationLoading" class="h-24" />
            <p v-else-if="invocationError" role="alert" class="text-sm text-destructive">记录加载失败，请重试。</p>
            <p v-else-if="!invocations.length" class="text-sm text-muted-foreground">暂无使用记录</p>
            <div v-else class="space-y-3">
              <div v-for="item in invocations" :key="item.id" class="rounded-lg border p-3">
                <div class="flex items-center justify-between gap-2"><time class="text-xs text-muted-foreground">{{ formatTime(item.createdAt) }}</time><Badge :variant="item.outcome === 'FAILED' ? 'destructive' : 'secondary'">{{ outcomeLabel(item) }}</Badge></div>
                <p class="mt-2 text-sm leading-6">{{ outcomeSummary(item) }}</p>
                <Button v-if="item.traceId" variant="link" class="h-auto px-0 pt-2" @click="openTrace(item.traceId)">查看对话过程</Button>
              </div>
            </div>
          </section>
          <details class="rounded-lg border p-3"><summary class="cursor-pointer text-sm">技术信息</summary>
            <dl class="mt-3 space-y-2 break-words text-xs text-muted-foreground"><dt>标识</dt><dd>{{ selectedSkill.skillKey }}</dd><dt>来源与版本</dt><dd>{{ selectedSkill.sourceType }} · {{ selectedSkill.version }}</dd><dt>绑定工具</dt><dd>{{ selectedSkill.boundTools }}</dd></dl>
            <pre class="mt-3 whitespace-pre-wrap break-words text-xs leading-6">{{ selectedSkill.instructionText }}</pre>
          </details>
          <Button v-if="!Number(selectedSkill.builtIn)" variant="destructive" @click="deleteSkill(selectedSkill)">卸载技能</Button>
        </div>
      </SheetContent>
    </Sheet>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RefreshCw } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '@/components/ui/select'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import { confirmAction, feedback } from '@/lib/feedback'
import { listAgentSkillsAPI, setAgentSkillEnabledAPI, deleteAgentSkillAPI, listSkillInvocationsAPI } from '@/api/agentSkills'
const route = useRoute()
const router = useRouter()
const skills = ref([])
const selectedSkill = ref(null)
const detailOpen = ref(false)
const loading = ref(false)
const loadError = ref(false)
const invocationLoading = ref(false)
const invocationError = ref(false)
const invocations = ref([])
const keyword = ref('')
const category = ref('ALL')
let invocationRequest = 0
const title = skill => skill.displayName || skill.name || '未命名技能'
const isEnabled = skill => Number(skill?.enabled) === 1
const categories = computed(() => [...new Set(skills.value.map(s => s.category).filter(Boolean))])
const filteredSkills = computed(() => skills.value.filter(s => (category.value === 'ALL' || s.category === category.value)
  && `${title(s)} ${s.userDescription || ''} ${s.skillKey}`.toLowerCase().includes(keyword.value.trim().toLowerCase())))
async function loadSkills() {
  loading.value = true
  loadError.value = false
  try {
    const res = await listAgentSkillsAPI()
    skills.value = Array.isArray(res.data) ? res.data : []
    const id = route.query.skillId || selectedSkill.value?.id
    const selected = skills.value.find(s => String(s.id) === String(id))
    if (selected) {
      selectedSkill.value = selected
      if (route.query.skillId) detailOpen.value = true
      if (detailOpen.value) await loadInvocations(selected)
    } else { selectedSkill.value = null; detailOpen.value = false }
  } catch { loadError.value = true } finally { loading.value = false }
}
function selectSkill(skill) {
  selectedSkill.value = skill
  detailOpen.value = true
  router.replace({ query: { ...route.query, skillId: String(skill.id) } })
  loadInvocations(skill)
}
async function loadInvocations(skill) {
  const requestId = ++invocationRequest
  invocations.value = []
  invocationError.value = false
  invocationLoading.value = true
  try {
    const res = await listSkillInvocationsAPI({ skillName: skill.skillKey, limit: 30 })
    if (requestId === invocationRequest) invocations.value = Array.isArray(res.data) ? res.data : []
  } catch { if (requestId === invocationRequest) invocationError.value = true }
  finally { if (requestId === invocationRequest) invocationLoading.value = false }
}
async function toggleSkill(skill, enabled) {
  skill.updating = true
  try { const res = await setAgentSkillEnabledAPI(skill.id, enabled); Object.assign(skill, res.data) }
  catch { /* Shared request handling reports the error; preserve the previous switch state. */ }
  finally { skill.updating = false }
}
async function deleteSkill(skill) {
  if (!await confirmAction(`确定卸载“${title(skill)}”吗？`)) return
  await deleteAgentSkillAPI(skill.id)
  detailOpen.value = false
  selectedSkill.value = null
  feedback.success('技能已卸载')
  await loadSkills()
}
const outcomeLabel = item => ({ PENDING: '待确认', COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '失败', BLOCKED: '已拦截', RETURNED: '已返回结果', UNKNOWN: '结果待核实' }[item.outcome] || '结果待核实')
function outcomeSummary(item) {
  if (item.outcome === 'FAILED') return '这次操作未完成，可在对话中重试。'
  if (item.outcome === 'BLOCKED') return '这次操作未获准执行。'
  if (item.outcome === 'PENDING') return '已生成待确认操作，请在对话中确认后生效。'
  if (item.outcome === 'COMPLETED') return '操作已确认并完成。'
  if (item.outcome === 'CANCELLED') return '你已取消这次操作。'
  if (item.outcome === 'UNKNOWN') return '这条记录缺少完整的结果关联，不能确认是否已生效。'
  return '已返回查询结果，可在对话过程中查看。'
}
const formatTime = value => value ? String(value).slice(0, 19).replace('T', ' ') : ''
function openTrace(traceId) { router.push({ path: '/chat', query: { traceId } }) }
watch(detailOpen, open => {
  if (!open) {
    ++invocationRequest
    const { skillId, ...query } = route.query
    if (skillId) router.replace({ query })
  }
})
watch(() => route.query.skillId, id => {
  const skill = skills.value.find(s => String(s.id) === String(id))
  if (skill && (selectedSkill.value?.id !== skill.id || !detailOpen.value)) {
    selectedSkill.value = skill; detailOpen.value = true; loadInvocations(skill)
  } else if (!id) detailOpen.value = false
})
onMounted(loadSkills)
</script>
