<template>
  <div class="mx-auto flex max-w-[1180px] flex-col gap-5">
    <div class="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
      <div class="min-w-0">
        <h1 class="text-2xl font-semibold tracking-normal text-foreground">财务画像</h1>
        <p class="mt-1 max-w-3xl text-sm text-muted-foreground">
          维护长期财务背景，Agent 会据此调整预算、省钱和风险建议。
        </p>
      </div>
      <Button :disabled="saving" @click="handleSave">
        <Loader2 v-if="saving" data-icon="inline-start" class="animate-spin" />
        <Save v-else data-icon="inline-start" />
        保存画像
      </Button>
    </div>

    <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_380px]">
      <Card>
        <CardHeader>
          <CardTitle class="text-base">基础信息</CardTitle>
          <CardDescription>用于判断建议尺度、预算约束和风险表达。</CardDescription>
        </CardHeader>
        <CardContent>
          <div class="flex flex-col gap-5">
            <div class="grid gap-4 md:grid-cols-2">
              <div class="flex flex-col gap-2">
                <Label>身份阶段</Label>
                <Select v-model="form.lifeStage">
                  <SelectTrigger>
                    <SelectValue placeholder="请选择" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      <SelectItem value="学生">学生</SelectItem>
                      <SelectItem value="上班族">上班族</SelectItem>
                      <SelectItem value="自由职业">自由职业</SelectItem>
                      <SelectItem value="家庭管理者">家庭管理者</SelectItem>
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </div>
              <div class="flex flex-col gap-2">
                <Label>风险偏好</Label>
                <div class="grid w-full grid-cols-3 gap-2">
                  <Button
                    v-for="option in riskOptions"
                    :key="option.value"
                    type="button"
                    :variant="form.riskPreference === option.value ? 'default' : 'outline'"
                    @click="form.riskPreference = option.value"
                  >
                    {{ option.label }}
                  </Button>
                </div>
              </div>
              <div class="flex flex-col gap-2">
                <Label>月收入</Label>
                <Input v-model.number="form.monthlyIncome" min="0" step="500" type="number" />
              </div>
              <div class="flex flex-col gap-2">
                <Label>固定支出</Label>
                <Input v-model.number="form.fixedExpense" min="0" step="100" type="number" />
              </div>
            </div>

            <Separator class="my-5" />

            <div class="mb-4 flex items-center justify-between gap-3">
              <div>
                <h2 class="text-base font-semibold">长期目标</h2>
                <p class="text-sm text-muted-foreground">用于判断当前消费是否影响目标。</p>
              </div>
            </div>
            <div class="grid gap-4 md:grid-cols-2">
              <div class="flex flex-col gap-2">
                <Label>储蓄目标金额</Label>
                <Input v-model.number="form.savingsGoalAmount" min="0" step="500" type="number" />
              </div>
              <div class="flex flex-col gap-2">
                <Label>目标期限</Label>
                <Input v-model="form.savingsGoalDeadline" type="month" />
              </div>
            </div>

            <Separator class="my-5" />

            <div class="mb-4 flex items-center justify-between gap-3">
              <div>
                <h2 class="text-base font-semibold">本月预算设置</h2>
                <p class="text-sm text-muted-foreground">{{ currentBudgetMonth }}</p>
              </div>
              <Button type="button" variant="outline" size="sm" @click="addCategoryBudget">
                <Plus data-icon="inline-start" />
                新增分类预算
              </Button>
            </div>
            <div class="grid gap-4 md:grid-cols-2">
              <div class="flex flex-col gap-2">
                <Label>本月总预算</Label>
                <Input v-model.number="form.monthlyBudgetGoal" min="0" step="200" type="number" />
              </div>
              <div class="flex flex-col gap-2">
                <Label>总预算预警阈值</Label>
                <div class="relative">
                  <Input v-model.number="totalBudgetThreshold" class="pr-9" max="100" min="1" step="5" type="number" />
                  <span class="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-sm font-medium text-muted-foreground">%</span>
                </div>
              </div>
            </div>

            <div class="mb-5 rounded-lg border p-3">
              <Alert v-if="!categoryBudgets.length">
                <AlertTitle>暂未设置分类预算</AlertTitle>
                <AlertDescription>可按餐饮、购物等分别控制预算。</AlertDescription>
              </Alert>
              <div v-else class="flex flex-col gap-3">
                <div v-for="(item, index) in categoryBudgets" :key="item.key" class="grid gap-3 md:grid-cols-[minmax(0,1fr)_160px_130px_auto] md:items-center">
                  <Select v-model="item.category">
                    <SelectTrigger>
                      <SelectValue placeholder="选择分类" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        <SelectItem v-for="category in categories" :key="category.id" :value="category.name">
                          {{ category.name }}
                        </SelectItem>
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                  <Input v-model.number="item.amount" min="0" step="100" type="number" />
                  <div class="relative">
                    <Input v-model.number="item.alertThreshold" class="pr-9" max="100" min="1" step="5" type="number" />
                    <span class="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-sm font-medium text-muted-foreground">%</span>
                  </div>
                  <Button type="button" variant="destructive" size="icon-sm" @click="removeCategoryBudget(index)">
                    <Trash2 />
                  </Button>
                </div>
              </div>
            </div>

            <div class="flex flex-col gap-2">
              <Label>补充偏好</Label>
              <Textarea
                v-model="form.notes"
                rows="4"
                maxlength="500"
                placeholder="例如：优先攒应急金；少买数码产品；不接受高风险投资。"
              />
            </div>
          </div>
        </CardContent>
      </Card>

      <aside class="flex flex-col gap-4">
        <Card>
          <CardHeader class="flex flex-row items-start justify-between gap-3">
            <div>
              <CardTitle class="text-base">目标概览</CardTitle>
              <CardDescription>{{ hasProfile ? '已配置' : '待完善' }}</CardDescription>
            </div>
            <Badge variant="secondary">{{ riskLabel(form.riskPreference) }}</Badge>
          </CardHeader>
          <CardContent class="flex flex-col gap-3">
            <div class="grid gap-2">
              <div class="flex items-center justify-between rounded-lg border p-3">
                <span class="text-sm text-muted-foreground">可支配收入</span>
                <strong class="text-sm">{{ money(disposableIncome) }}</strong>
              </div>
              <div class="flex items-center justify-between rounded-lg border p-3">
                <span class="text-sm text-muted-foreground">预算占收入</span>
                <strong class="text-sm">{{ budgetRatio }}</strong>
              </div>
              <div class="flex items-center justify-between rounded-lg border p-3">
                <span class="text-sm text-muted-foreground">储蓄目标</span>
                <strong class="text-sm">{{ money(form.savingsGoalAmount) }}</strong>
              </div>
            </div>
            <Alert>
              <WalletCards data-icon="inline-start" />
              <AlertTitle>风险偏好</AlertTitle>
              <AlertDescription>{{ riskText }}</AlertDescription>
            </Alert>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle class="text-base">Agent 长期记忆</CardTitle>
            <CardDescription>写给 Agent 的长期偏好和工作习惯。</CardDescription>
          </CardHeader>
          <CardContent class="flex flex-col gap-4">
            <div class="flex flex-col gap-2">
              <Label>自定义指令</Label>
              <Textarea
                v-model="memoryPreferences.customInstructions"
                rows="9"
                maxlength="3000"
                placeholder="例如：用中文对话，回答尽量简短；咖啡归为餐饮；股票问题可以给偏看好/偏谨慎/可观察，但要说明风险。"
              />
            </div>
            <div class="rounded-lg border">
              <div class="flex items-center justify-between gap-3 border-b p-3">
                <div>
                  <p class="text-sm font-medium">启用自动记忆</p>
                  <p class="text-xs text-muted-foreground">从普通聊天中沉淀低风险偏好。</p>
                </div>
                <Switch v-model="memoryPreferences.autoMemoryEnabled" />
              </div>
              <div class="flex items-center justify-between gap-3 border-b p-3">
                <div>
                  <p class="text-sm font-medium">跳过工具辅助对话</p>
                  <p class="text-xs text-muted-foreground">用了查询、搜索、记账等工具的对话不生成记忆。</p>
                </div>
                <Switch v-model="memoryPreferences.skipToolAssistedMemory" />
              </div>
              <div class="flex items-center justify-between gap-3 p-3">
                <div>
                  <p class="text-sm font-medium">重置记忆</p>
                  <p class="text-xs text-muted-foreground">删除所有 Agent 自定义指令和自动沉淀记忆。</p>
                </div>
                <Button variant="destructive" size="sm" :disabled="memoryResetting" @click="resetMemory">
                  <Loader2 v-if="memoryResetting" data-icon="inline-start" class="animate-spin" />
                  <RotateCcw v-else data-icon="inline-start" />
                  重置
                </Button>
              </div>
            </div>
          </CardContent>
          <CardFooter class="justify-end border-t">
            <Button :disabled="memorySaving" @click="saveMemoryPreferences">
              <Loader2 v-if="memorySaving" data-icon="inline-start" class="animate-spin" />
              保存记忆
            </Button>
          </CardFooter>
        </Card>

        <Card>
          <CardHeader class="flex flex-row items-start justify-between gap-3">
            <div>
              <CardTitle class="text-base">最近预警</CardTitle>
              <CardDescription>达到阈值或超支时会显示在这里。</CardDescription>
            </div>
            <Badge variant="outline">{{ recentAlerts.length }} 条</Badge>
          </CardHeader>
          <CardContent class="flex flex-col gap-2">
            <Alert v-if="!recentAlerts.length">
              <AlertTitle>暂无预警</AlertTitle>
              <AlertDescription>保存预算后，系统会根据阈值生成提醒。</AlertDescription>
            </Alert>
            <Alert v-for="alert in recentAlerts" v-else :key="alert.id" :variant="alert.severity === 'CRITICAL' ? 'destructive' : 'default'">
              <AlertTitle>{{ alert.category === 'ALL' ? '总预算' : alert.category }} · {{ alertLabel(alert) }}</AlertTitle>
              <AlertDescription>{{ alert.message }}</AlertDescription>
            </Alert>
          </CardContent>
        </Card>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Loader2, Plus, RotateCcw, Save, Trash2, WalletCards } from '@lucide/vue'
import { confirmAction, feedback } from '@/lib/feedback'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle
} from '@/components/ui/card'
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
import { Separator } from '@/components/ui/separator'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import { getFinancialProfileAPI, saveFinancialProfileAPI } from '../api/financialProfile'
import { getBudgetsAPI, saveBudgetAPI, deleteBudgetAPI } from '../api/budget'
import { getRecentAlertsAPI } from '../api/alert'
import { listCategoriesAPI } from '../api/category'
import {
  getAgentMemoryPreferencesAPI,
  updateAgentMemoryPreferencesAPI,
  resetAgentMemoriesAPI
} from '../api/agentMemory'

const saving = ref(false)
const loading = ref(false)
const hasProfile = ref(false)
const categories = ref([])
const categoryBudgets = ref([])
const removedBudgetIds = ref([])
const recentAlerts = ref([])
const memorySaving = ref(false)
const memoryResetting = ref(false)
const totalBudgetId = ref(null)
const totalBudgetThreshold = ref(80)
const currentBudgetMonth = ref(currentMonth())

const form = reactive({
  lifeStage: '',
  monthlyIncome: 0,
  fixedExpense: 0,
  riskPreference: 'STEADY',
  savingsGoalAmount: 0,
  savingsGoalDeadline: '',
  monthlyBudgetGoal: 0,
  notes: ''
})

const memoryPreferences = reactive({
  customInstructions: '',
  autoMemoryEnabled: true,
  skipToolAssistedMemory: false
})

const riskOptions = [
  { label: '保守', value: 'CONSERVATIVE' },
  { label: '稳健', value: 'STEADY' },
  { label: '进取', value: 'AGGRESSIVE' }
]

const disposableIncome = computed(() => Math.max(Number(form.monthlyIncome || 0) - Number(form.fixedExpense || 0), 0))

const budgetRatio = computed(() => {
  const income = Number(form.monthlyIncome || 0)
  if (!income || !form.monthlyBudgetGoal) return '-'
  return `${Math.round((Number(form.monthlyBudgetGoal) / income) * 100)}%`
})

const riskText = computed(() => {
  if (form.riskPreference === 'CONSERVATIVE') return '保守型：优先保障现金流和低波动目标'
  if (form.riskPreference === 'AGGRESSIVE') return '进取型：可接受更高波动，但仍需保留应急金'
  return '稳健型：兼顾储蓄进度和日常生活质量'
})

function money(value) {
  return `¥${Number(value || 0).toFixed(2)}`
}

function riskLabel(value) {
  return riskOptions.find(option => option.value === value)?.label || '稳健'
}

function applyProfile(profile) {
  form.lifeStage = profile.lifeStage || ''
  form.monthlyIncome = Number(profile.monthlyIncome || 0)
  form.fixedExpense = Number(profile.fixedExpense || 0)
  form.riskPreference = profile.riskPreference || 'STEADY'
  form.savingsGoalAmount = Number(profile.savingsGoalAmount || 0)
  form.savingsGoalDeadline = profile.savingsGoalDeadline || ''
  form.monthlyBudgetGoal = Number(profile.monthlyBudgetGoal || 0)
  form.notes = profile.notes || ''
  hasProfile.value = Boolean(profile.id)
}

function applyBudgetData(data) {
  const items = data?.items || []
  currentBudgetMonth.value = data?.month || currentMonth()
  removedBudgetIds.value = []

  const totalBudget = items.find(item => item.category === 'ALL')
  totalBudgetId.value = totalBudget?.id || null
  totalBudgetThreshold.value = totalBudget?.alertThreshold || 80
  if (totalBudget) {
    form.monthlyBudgetGoal = Number(totalBudget.budgetAmount || 0)
  }

  categoryBudgets.value = items
    .filter(item => item.category !== 'ALL')
    .map(item => ({
      key: item.id || `${item.category}-${item.month}`,
      id: item.id || null,
      category: item.category,
      amount: Number(item.budgetAmount || 0),
      alertThreshold: item.alertThreshold || 80
    }))
}

function applyMemoryPreferences(data) {
  memoryPreferences.customInstructions = data?.customInstructions || ''
  memoryPreferences.autoMemoryEnabled = data?.autoMemoryEnabled !== false
  memoryPreferences.skipToolAssistedMemory = Boolean(data?.skipToolAssistedMemory)
}

async function loadProfile() {
  loading.value = true
  try {
    const [profileRes, budgetRes, alertRes, categoryRes, memoryRes] = await Promise.all([
      getFinancialProfileAPI(),
      getBudgetsAPI(currentBudgetMonth.value),
      getRecentAlertsAPI(5),
      listCategoriesAPI(),
      getAgentMemoryPreferencesAPI()
    ])
    applyProfile(profileRes.data || {})
    applyBudgetData(budgetRes.data || {})
    recentAlerts.value = alertRes.data || []
    categories.value = categoryRes.data || []
    applyMemoryPreferences(memoryRes.data || {})
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (!validateProfile()) return
  saving.value = true
  try {
    const profileRes = await saveFinancialProfileAPI({ ...form })
    applyProfile(profileRes.data || {})
    await syncBudgets()
    const [budgetRes, alertRes] = await Promise.all([
      getBudgetsAPI(currentBudgetMonth.value),
      getRecentAlertsAPI(5)
    ])
    applyBudgetData(budgetRes.data || {})
    recentAlerts.value = alertRes.data || []
    feedback.success('财务画像和预算已保存')
  } finally {
    saving.value = false
  }
}

async function saveMemoryPreferences() {
  memorySaving.value = true
  const submitted = { ...memoryPreferences }
  try {
    const res = await updateAgentMemoryPreferencesAPI(submitted)
    const saved = res.data || {}
    applyMemoryPreferences({
      customInstructions: saved.customInstructions ?? submitted.customInstructions,
      autoMemoryEnabled: saved.autoMemoryEnabled ?? submitted.autoMemoryEnabled,
      skipToolAssistedMemory: saved.skipToolAssistedMemory ?? submitted.skipToolAssistedMemory
    })
    feedback.success('Agent 长期记忆已保存')
  } finally {
    memorySaving.value = false
  }
}

async function resetMemory() {
  const confirmed = await confirmAction('确定删除所有 Agent 长期记忆吗？财务画像不会被删除。')
  if (!confirmed) return
  memoryResetting.value = true
  try {
    await resetAgentMemoriesAPI()
    applyMemoryPreferences({})
    feedback.success('Agent 长期记忆已重置')
  } finally {
    memoryResetting.value = false
  }
}

function validateProfile() {
  const checks = [
    [form.monthlyIncome, '月收入不能为负数'],
    [form.fixedExpense, '固定支出不能为负数'],
    [form.savingsGoalAmount, '储蓄目标不能为负数'],
    [form.monthlyBudgetGoal, '预算目标不能为负数']
  ]
  const failed = checks.find(([value]) => Number(value || 0) < 0)
  if (failed) {
    feedback.warning(failed[1])
    return false
  }
  if (Number(totalBudgetThreshold.value || 0) < 1 || Number(totalBudgetThreshold.value || 0) > 100) {
    feedback.warning('总预算预警阈值需要在 1 到 100 之间')
    return false
  }
  return true
}

async function syncBudgets() {
  if (Number(form.monthlyBudgetGoal || 0) > 0) {
    await saveBudgetAPI({
      category: 'ALL',
      month: currentBudgetMonth.value,
      amount: Number(form.monthlyBudgetGoal),
      alertThreshold: totalBudgetThreshold.value || 80
    })
  } else if (totalBudgetId.value) {
    await deleteBudgetAPI(totalBudgetId.value)
  }

  for (const id of removedBudgetIds.value) {
    await deleteBudgetAPI(id)
  }

  for (const item of categoryBudgets.value) {
    if (!item.category || Number(item.amount || 0) <= 0) {
      continue
    }
    await saveBudgetAPI({
      category: item.category,
      month: currentBudgetMonth.value,
      amount: Number(item.amount),
      alertThreshold: item.alertThreshold || 80
    })
  }
}

function addCategoryBudget() {
  categoryBudgets.value.push({
    key: `new-${Date.now()}-${categoryBudgets.value.length}`,
    id: null,
    category: '',
    amount: 0,
    alertThreshold: 80
  })
}

function removeCategoryBudget(index) {
  const [removed] = categoryBudgets.value.splice(index, 1)
  if (removed?.id) {
    removedBudgetIds.value.push(removed.id)
  }
}

function alertLabel(alert) {
  return alert.alertType === 'OVERRUN' ? '已超支' : `${alert.usagePercent}%`
}

function currentMonth() {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}`
}

onMounted(loadProfile)
</script>
