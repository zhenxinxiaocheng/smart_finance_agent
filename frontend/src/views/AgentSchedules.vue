<template>
  <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_380px]">
    <Card class="min-w-0">
      <CardHeader class="gap-4">
        <div class="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
          <div>
            <CardTitle class="text-2xl">周期任务</CardTitle>
            <CardDescription>管理 Agent 自动复盘、预算检查和监控任务。</CardDescription>
          </div>
          <div class="flex flex-wrap gap-2">
            <Button @click="openCreateDialog">
              新增任务
            </Button>
            <Button variant="outline" :disabled="loading" @click="loadSchedules">
              <RefreshCw data-icon="inline-start" :class="{ 'animate-spin': loading }" />
              刷新
            </Button>
          </div>
        </div>
        <Input v-model="keyword" placeholder="搜索任务名、描述或执行内容" />
      </CardHeader>
      <CardContent>
        <div v-if="loading" class="rounded-lg border p-6 text-center text-sm text-muted-foreground">加载中...</div>
        <div v-else-if="filteredSchedules.length === 0" class="grid gap-3 rounded-lg border p-6 text-center text-sm text-muted-foreground">
          <p>暂无周期任务</p>
          <div>
            <Button @click="openCreateDialog">新增任务</Button>
          </div>
        </div>
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
            <div class="mt-3 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div class="flex flex-wrap gap-2">
                <Badge variant="outline">{{ scheduleTimeLabel(schedule) }}</Badge>
                <Badge :variant="schedule.lastStatus === 'FAILED' ? 'destructive' : 'secondary'">{{ lastStatusLabel(schedule) }}</Badge>
                <Badge variant="outline">运行 {{ Number(schedule.runCount || 0) }} 次</Badge>
              </div>
              <div class="flex gap-2 self-start sm:self-auto">
                <Button size="sm" variant="outline" @click.stop="openEditDialog(schedule)">
                  <Pencil data-icon="inline-start" />
                  编辑
                </Button>
                <Button size="sm" variant="outline" class="text-destructive hover:text-destructive" @click.stop="openDeleteDialog(schedule)">
                  <Trash2 data-icon="inline-start" />
                  删除
                </Button>
              </div>
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
          <p><span class="text-muted-foreground">触发：</span>{{ scheduleTimeLabel(selectedSchedule) }}</p>
          <p><span class="text-muted-foreground">下次：</span>{{ formatTime(selectedSchedule.nextRunAt) || '未计算' }}</p>
          <p><span class="text-muted-foreground">连续失败：</span>{{ Number(selectedSchedule.consecutiveFailures || 0) }} 次</p>
        </div>
        <pre class="max-h-40 overflow-auto rounded-lg border bg-muted/40 p-3 text-xs">{{ selectedSchedule.taskQuery || '暂无执行内容' }}</pre>
        <div class="flex flex-wrap gap-2">
          <Button variant="outline" @click="openEditDialog(selectedSchedule)">
            <Pencil data-icon="inline-start" />
            编辑
          </Button>
          <Button variant="outline" class="text-destructive hover:text-destructive" @click="openDeleteDialog(selectedSchedule)">
            <Trash2 data-icon="inline-start" />
            删除
          </Button>
          <Button :disabled="selectedSchedule.executingNow" @click="executeScheduleNow(selectedSchedule)">
            <Play data-icon="inline-start" />
            {{ selectedSchedule.executingNow ? '执行中' : executeNowLabel(selectedSchedule) }}
          </Button>
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
    </Card>

    <Dialog v-model:open="editDialogVisible">
      <DialogContent class="sm:max-w-[620px]">
        <DialogHeader>
          <DialogTitle>{{ editingScheduleId ? '编辑周期任务' : '新增周期任务' }}</DialogTitle>
          <DialogDescription>{{ editingScheduleId ? '修改任务名称、触发时间和执行内容，保存后会重新计算下次执行时间。' : '设置任务名称、触发时间和执行内容，保存后会自动开始执行。' }}</DialogDescription>
        </DialogHeader>
        <div class="grid gap-4">
          <div class="grid gap-2">
            <Label>任务名称</Label>
            <Input v-model="editForm.name" placeholder="如：每日财务分析提醒" />
          </div>
          <div class="grid gap-2">
            <Label>描述</Label>
            <Input v-model="editForm.description" placeholder="简要说明这个任务做什么" />
          </div>
          <div class="grid gap-4 md:grid-cols-2">
            <div class="grid gap-2">
              <Label>重复频率</Label>
              <Select v-model="editForm.frequency">
                <SelectTrigger>
                  <SelectValue placeholder="选择频率" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value="daily">每天</SelectItem>
                    <SelectItem value="weekly">每周</SelectItem>
                    <SelectItem value="monthly">每月</SelectItem>
                    <SelectItem value="custom">高级自定义</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>
            <div class="grid gap-2">
              <Label>触发时间</Label>
              <div
                class="grid gap-3 rounded-lg border bg-muted/20 p-3 transition-colors sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]"
                :class="editForm.frequency === 'custom' ? 'opacity-60' : 'border-border/80'"
              >
                <div class="grid gap-2">
                  <span class="text-xs text-muted-foreground">小时</span>
                  <Select v-model="editForm.timeHour" :disabled="editForm.frequency === 'custom'">
                    <SelectTrigger class="w-full">
                      <SelectValue placeholder="小时" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        <SelectItem v-for="hour in hourOptions" :key="hour" :value="hour">{{ hour }}</SelectItem>
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </div>
                <div class="grid gap-2">
                  <span class="text-xs text-muted-foreground">分钟</span>
                  <Select v-model="editForm.timeMinute" :disabled="editForm.frequency === 'custom'">
                    <SelectTrigger class="w-full">
                      <SelectValue placeholder="分钟" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        <SelectItem v-for="minute in minuteOptions" :key="minute" :value="minute">{{ minute }}</SelectItem>
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </div>
                <p class="sm:col-span-2 text-xs text-muted-foreground">
                  {{ editForm.frequency === 'custom' ? '高级自定义模式下请直接填写 cron 表达式。' : `将在 ${previewTimeLabel} 触发` }}
                </p>
              </div>
            </div>
          </div>
          <div v-if="editForm.frequency === 'weekly'" class="grid gap-2">
            <Label>星期</Label>
            <Select v-model="editForm.weekday">
              <SelectTrigger>
                <SelectValue placeholder="选择星期" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  <SelectItem v-for="day in weekdayOptions" :key="day.value" :value="day.value">{{ day.label }}</SelectItem>
                </SelectGroup>
              </SelectContent>
            </Select>
          </div>
          <div v-if="editForm.frequency === 'monthly'" class="grid gap-2">
            <Label>每月几号</Label>
            <Input v-model.number="editForm.monthDay" max="31" min="1" type="number" />
          </div>
          <details class="rounded-lg border p-3 text-sm" :open="editForm.frequency === 'custom'">
            <summary class="cursor-pointer select-none font-medium">高级设置</summary>
            <div class="mt-3 grid gap-4 md:grid-cols-2">
              <div class="grid gap-2">
                <Label>cron 表达式</Label>
                <Input v-model="editForm.cronExpression" placeholder="0 16 20 * * *" />
              </div>
              <div class="grid gap-2">
                <Label>时区</Label>
                <Input v-model="editForm.timezone" placeholder="Asia/Shanghai" />
              </div>
            </div>
          </details>
          <div class="grid gap-2">
            <Label>执行内容</Label>
            <Textarea v-model="editForm.taskQuery" rows="4" placeholder="Agent 到时间后要执行的具体任务" />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="editDialogVisible = false">取消</Button>
          <Button :disabled="editSaving" @click="saveScheduleEdit">
            <Loader2 v-if="editSaving" data-icon="inline-start" class="animate-spin" />
            {{ editingScheduleId ? '保存更改' : '创建任务' }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="deleteDialogVisible">
      <DialogContent class="sm:max-w-[420px]">
        <DialogHeader>
          <DialogTitle>删除周期任务</DialogTitle>
          <DialogDescription>删除后这个任务不会再自动执行，历史运行记录仍可保留在系统数据中。</DialogDescription>
        </DialogHeader>
        <p class="rounded-lg border bg-muted/40 p-3 text-sm font-medium">{{ deletingSchedule?.name || '未命名任务' }}</p>
        <DialogFooter>
          <Button variant="outline" @click="deleteDialogVisible = false">取消</Button>
          <Button variant="destructive" :disabled="deleteSaving" @click="deleteSchedule">
            <Loader2 v-if="deleteSaving" data-icon="inline-start" class="animate-spin" />
            确认删除
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Loader2, Pencil, Play, RefreshCw, Trash2 } from '@lucide/vue'
import { feedback } from '@/lib/feedback'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue
} from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import { createAgentScheduleAPI, deleteAgentScheduleAPI, listAgentScheduleRunsAPI, listAgentSchedulesAPI, retryAgentScheduleAPI, setAgentScheduleEnabledAPI, updateAgentScheduleAPI } from '@/api/agentSchedules'

const route = useRoute()
const router = useRouter()
const schedules = ref([])
const selectedSchedule = ref(null)
const scheduleRuns = ref([])
const loading = ref(false)
const runsLoading = ref(false)
const keyword = ref('')
const editDialogVisible = ref(false)
const editSaving = ref(false)
const editingScheduleId = ref(null)
const deleteDialogVisible = ref(false)
const deleteSaving = ref(false)
const deletingSchedule = ref(null)
const editForm = reactive({
  name: '',
  description: '',
  frequency: 'daily',
  time: '09:00',
  timeHour: '09',
  timeMinute: '00',
  weekday: 'MON',
  monthDay: 1,
  cronExpression: '',
  timezone: 'Asia/Shanghai',
  taskQuery: ''
})
const hourOptions = Array.from({ length: 24 }, (_, index) => String(index).padStart(2, '0'))
const minuteOptions = Array.from({ length: 60 }, (_, index) => String(index).padStart(2, '0'))
const weekdayOptions = [
  { value: 'MON', label: '周一' },
  { value: 'TUE', label: '周二' },
  { value: 'WED', label: '周三' },
  { value: 'THU', label: '周四' },
  { value: 'FRI', label: '周五' },
  { value: 'SAT', label: '周六' },
  { value: 'SUN', label: '周日' }
]

const filteredSchedules = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return schedules.value.filter(item => !key || `${item.name || ''} ${item.description || ''} ${item.taskQuery || ''}`.toLowerCase().includes(key))
})
const previewTimeLabel = computed(() => `${editForm.timeHour}:${editForm.timeMinute}`)

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

async function executeScheduleNow(schedule) {
  schedule.executingNow = true
  try {
    const res = await retryAgentScheduleAPI(schedule.id)
    Object.assign(schedule, res.data || {}, { executingNow: false })
    feedback.success('已立即执行一次任务')
    await loadRuns(schedule)
    router.push({
      path: '/chat',
      query: {
        scheduleTraceId: schedule.traceId || res.data?.traceId || '',
        refresh: Date.now()
      }
    })
  } catch {
    schedule.executingNow = false
  }
}

function openEditDialog(schedule) {
  if (!schedule?.id) return
  editingScheduleId.value = schedule.id
  editForm.name = schedule.name || ''
  editForm.description = schedule.description || ''
  editForm.cronExpression = schedule.cronExpression || ''
  editForm.timezone = schedule.timezone || 'Asia/Shanghai'
  editForm.taskQuery = schedule.taskQuery || ''
  applyCronToEditForm(schedule.cronExpression)
  editDialogVisible.value = true
}

function openCreateDialog() {
  editingScheduleId.value = null
  editForm.name = ''
  editForm.description = ''
  editForm.frequency = 'daily'
  applyTimeValue('09:00')
  editForm.weekday = 'MON'
  editForm.monthDay = 1
  editForm.cronExpression = ''
  editForm.timezone = 'Asia/Shanghai'
  editForm.taskQuery = ''
  editDialogVisible.value = true
}

function applyTimeValue(value) {
  const [rawHour = '09', rawMinute = '00'] = String(value || '').split(':')
  const hour = hourOptions.includes(rawHour.padStart(2, '0')) ? rawHour.padStart(2, '0') : '09'
  const minute = minuteOptions.includes(rawMinute.padStart(2, '0')) ? rawMinute.padStart(2, '0') : '00'
  editForm.timeHour = hour
  editForm.timeMinute = minute
  editForm.time = `${hour}:${minute}`
}

async function saveScheduleEdit() {
  if (editSaving.value) return
  if (!editForm.name.trim()) {
    feedback.error('请输入任务名称')
    return
  }
  const cronExpression = buildCronExpression()
  if (!cronExpression) {
    feedback.error('请选择有效的触发时间')
    return
  }
  if (!editForm.taskQuery.trim()) {
    feedback.error('请输入执行内容')
    return
  }
  editSaving.value = true
  try {
    const payload = {
      name: editForm.name.trim(),
      description: editForm.description.trim(),
      cronExpression,
      timezone: editForm.timezone.trim() || 'Asia/Shanghai',
      taskQuery: editForm.taskQuery.trim()
    }
    if (editingScheduleId.value) {
      const res = await updateAgentScheduleAPI(editingScheduleId.value, payload)
      const updated = res.data || {}
      const index = schedules.value.findIndex(item => Number(item.id) === Number(editingScheduleId.value))
      if (index >= 0) {
        schedules.value[index] = { ...schedules.value[index], ...updated }
        selectedSchedule.value = schedules.value[index]
      }
      feedback.success('周期任务已更新')
      if (selectedSchedule.value) await loadRuns(selectedSchedule.value)
    } else {
      const res = await createAgentScheduleAPI(payload)
      const created = res.data || {}
      schedules.value = [created, ...schedules.value]
      selectedSchedule.value = created
      scheduleRuns.value = []
      feedback.success('周期任务已创建')
    }
    editDialogVisible.value = false
  } finally {
    editSaving.value = false
  }
}

function openDeleteDialog(schedule) {
  if (!schedule?.id) return
  deletingSchedule.value = schedule
  deleteDialogVisible.value = true
}

async function deleteSchedule() {
  if (!deletingSchedule.value?.id || deleteSaving.value) return
  deleteSaving.value = true
  try {
    await deleteAgentScheduleAPI(deletingSchedule.value.id)
    schedules.value = schedules.value.filter(item => Number(item.id) !== Number(deletingSchedule.value.id))
    if (Number(selectedSchedule.value?.id) === Number(deletingSchedule.value.id)) {
      selectedSchedule.value = schedules.value[0] || null
      scheduleRuns.value = []
      if (selectedSchedule.value) await loadRuns(selectedSchedule.value)
    }
    deleteDialogVisible.value = false
    deletingSchedule.value = null
    feedback.success('周期任务已删除')
  } finally {
    deleteSaving.value = false
  }
}

function applyCronToEditForm(cronExpression) {
  const parts = String(cronExpression || '').trim().split(/\s+/)
  const normalized = parts.length === 5 ? ['0', ...parts] : parts
  if (normalized.length !== 6) {
    editForm.frequency = 'custom'
    applyTimeValue('09:00')
    return
  }
  const [, minute, hour, dayOfMonth, month, dayOfWeek] = normalized
  applyTimeValue(`${String(Number(hour || 9)).padStart(2, '0')}:${String(Number(minute || 0)).padStart(2, '0')}`)
  editForm.monthDay = Number(dayOfMonth) > 0 ? Number(dayOfMonth) : 1
  editForm.weekday = weekdayOptions.some(day => day.value === dayOfWeek) ? dayOfWeek : 'MON'
  if (dayOfMonth === '*' && month === '*' && dayOfWeek === '*') {
    editForm.frequency = 'daily'
  } else if (dayOfMonth === '*' && month === '*' && weekdayOptions.some(day => day.value === dayOfWeek)) {
    editForm.frequency = 'weekly'
  } else if (/^\d+$/.test(dayOfMonth) && month === '*' && dayOfWeek === '*') {
    editForm.frequency = 'monthly'
  } else {
    editForm.frequency = 'custom'
  }
}

function buildCronExpression() {
  if (editForm.frequency === 'custom') {
    return editForm.cronExpression.trim()
  }
  const hour = Number(editForm.timeHour)
  const minute = Number(editForm.timeMinute)
  if (!Number.isInteger(hour) || !Number.isInteger(minute) || hour < 0 || hour > 23 || minute < 0 || minute > 59) {
    return ''
  }
  if (editForm.frequency === 'weekly') {
    return `0 ${minute} ${hour} * * ${editForm.weekday || 'MON'}`
  }
  if (editForm.frequency === 'monthly') {
    const day = Math.min(Math.max(Number(editForm.monthDay || 1), 1), 31)
    return `0 ${minute} ${hour} ${day} * *`
  }
  return `0 ${minute} ${hour} * * *`
}

function scheduleTimeLabel(schedule) {
  const cron = String(schedule?.cronExpression || '').trim()
  const parts = cron.split(/\s+/)
  const normalized = parts.length === 5 ? ['0', ...parts] : parts
  if (normalized.length !== 6) return cron || '未设置'
  const [, minute, hour, dayOfMonth, month, dayOfWeek] = normalized
  const time = `${String(Number(hour || 0)).padStart(2, '0')}:${String(Number(minute || 0)).padStart(2, '0')}`
  if (dayOfMonth === '*' && month === '*' && dayOfWeek === '*') return `每天 ${time}`
  const weekday = weekdayOptions.find(day => day.value === dayOfWeek)?.label
  if (dayOfMonth === '*' && month === '*' && weekday) return `每周${weekday.replace('周', '')} ${time}`
  if (/^\d+$/.test(dayOfMonth) && month === '*' && dayOfWeek === '*') return `每月 ${Number(dayOfMonth)} 号 ${time}`
  return cron || '未设置'
}

function isEnabled(schedule) {
  return Number(schedule?.enabled ?? 0) === 1
}

function executeNowLabel(schedule) {
  if (schedule?.lastStatus === 'FAILED' || !isEnabled(schedule)) return '恢复并立即执行'
  return '立即执行'
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
watch(
  [() => editForm.timeHour, () => editForm.timeMinute],
  () => {
    editForm.time = `${editForm.timeHour}:${editForm.timeMinute}`
  }
)
onMounted(loadSchedules)
</script>
