<template>
  <div class="mx-auto max-w-[1440px] space-y-5 pb-8">
    <header class="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
      <div>
        <div class="flex items-center gap-2">
          <BrainCircuit class="size-6 text-primary" />
          <h1 class="text-2xl font-semibold tracking-tight">量化模型管理</h1>
          <Badge variant="secondary">自动训练</Badge>
        </div>
        <p class="mt-1 text-sm text-muted-foreground">
          系统自动准备数据、训练和验证模型。普通使用无需设置算法参数。
        </p>
      </div>
      <div class="flex flex-wrap gap-2">
        <Button variant="outline" :disabled="loading" @click="loadManagement">
          <RefreshCw :class="['size-4', loading && 'animate-spin']" />
          刷新状态
        </Button>
        <Button variant="outline" @click="openExpertMode">
          <Settings2 class="size-4" />
          专家模式
        </Button>
      </div>
    </header>

    <Card>
      <CardContent class="grid gap-4 pt-6 sm:grid-cols-[minmax(0,1fr)_220px]">
        <label class="space-y-1.5 text-sm">
          <span class="font-medium">管理资产</span>
          <select
            v-model="assetId"
            class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
          >
            <option v-for="asset in assets" :key="asset.id" :value="String(asset.id)">
              {{ asset.name }} · {{ asset.code }}
            </option>
          </select>
        </label>
        <label class="space-y-1.5 text-sm">
          <span class="font-medium">预测周期</span>
          <select
            v-model="horizonCode"
            class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="SHORT">短期</option>
            <option value="MEDIUM">中期</option>
            <option value="LONG">长期</option>
          </select>
        </label>
      </CardContent>
    </Card>

    <template v-if="assetId">
      <section class="grid gap-3 md:grid-cols-3">
        <Card>
          <CardHeader class="pb-2">
            <CardDescription>自动训练状态</CardDescription>
            <CardTitle class="text-base">{{ trainingStatus }}</CardTitle>
          </CardHeader>
          <CardContent>
            <p class="text-sm text-muted-foreground">{{ trainingTime }}</p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader class="pb-2">
            <CardDescription>当前使用模型</CardDescription>
            <CardTitle class="text-base">{{ formatModelStatus(management.champion) }}</CardTitle>
          </CardHeader>
          <CardContent>
            <p class="text-sm text-muted-foreground">{{ modelDescription(management.champion, '当前没有通过模拟验证的模型') }}</p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader class="pb-2">
            <CardDescription>观察中的新模型</CardDescription>
            <CardTitle class="text-base">{{ formatModelStatus(management.challenger) }}</CardTitle>
          </CardHeader>
          <CardContent>
            <p class="text-sm text-muted-foreground">{{ modelDescription(management.challenger, '当前没有等待模拟验证的新模型') }}</p>
          </CardContent>
        </Card>
      </section>

      <Card>
        <CardHeader>
          <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <CardDescription>当前操作</CardDescription>
              <CardTitle class="mt-1 flex items-center gap-2">
                {{ actionLabel(actionPlan.action) }}
                <Badge :variant="actionPlan.status === 'READY' ? 'default' : 'outline'">
                  {{ actionPlan.status === 'READY' ? '计划可执行' : '当前不下单' }}
                </Badge>
              </CardTitle>
            </div>
            <Button :disabled="training || !assetId" @click="retrain">
              <Loader2 v-if="training" class="size-4 animate-spin" />
              <RefreshCw v-else class="size-4" />
              立即重新训练
            </Button>
          </div>
        </CardHeader>
        <CardContent class="space-y-5">
          <Alert v-if="actionPlan.status !== 'READY'">
            <PauseCircle class="size-4" />
            <AlertTitle>暂无可执行的量化操作</AlertTitle>
            <AlertDescription>{{ actionPlan.userMessage || '系统会在数据或模型更新后自动重新检查。' }}</AlertDescription>
          </Alert>

          <template v-else>
            <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              <div v-for="metric in predictionMetrics" :key="metric.key" class="rounded-lg border bg-muted/20 p-3">
                <p class="text-xs text-muted-foreground">{{ metric.label }}</p>
                <p class="mt-1 text-lg font-semibold">{{ metricText(metric) }}</p>
              </div>
            </div>

            <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              <PlanMetric label="执行时间" :value="formatDateTime(actionPlan.executeFrom)" />
              <PlanMetric label="计划金额" :value="money(actionPlan.orderAmountCny)" />
              <PlanMetric label="目标仓位" :value="percent(actionPlan.targetWeight)" />
              <PlanMetric label="预估数量/份额" :value="quantity(actionPlan.estimatedQuantity)" />
            </div>

            <div class="rounded-lg border p-4">
              <p class="text-sm font-medium">执行说明</p>
              <p class="mt-1 text-sm text-muted-foreground">{{ actionPlan.executionNote || '-' }}</p>
            </div>
          </template>

          <div>
            <p class="text-sm font-medium">停止条件</p>
            <ul class="mt-2 space-y-1 text-sm text-muted-foreground">
              <li v-for="condition in actionPlan.stopConditions || []" :key="condition">• {{ condition }}</li>
            </ul>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div class="flex items-center justify-between gap-3">
            <div>
              <CardTitle>历史有效模型</CardTitle>
              <CardDescription>只有通过严格验证的模型会出现在这里；恢复后先进入模拟观察。</CardDescription>
            </div>
            <Button variant="ghost" @click="historyOpen = !historyOpen">
              {{ historyOpen ? '收起' : '查看历史' }}
              <ChevronDown :class="['size-4 transition-transform', historyOpen && 'rotate-180']" />
            </Button>
          </div>
        </CardHeader>
        <CardContent v-if="historyOpen">
          <div v-if="visibleModels.length" class="space-y-2">
            <div
              v-for="model in visibleModels"
              :key="`${model.strategyVersion}-${model.modelVersion}`"
              class="flex flex-col gap-3 rounded-lg border p-3 sm:flex-row sm:items-center sm:justify-between"
            >
              <div>
                <p class="font-medium">{{ horizonLabel(model.horizonCode) }}模型 · {{ formatModelStatus(model) }}</p>
                <p class="mt-1 text-xs text-muted-foreground">
                  {{ roleLabel(model.deploymentRole) }} · {{ formatDateTime(model.activatedAt) }}
                </p>
              </div>
              <Button
                v-if="modelCanBeRestored(model) && model.deploymentRole !== 'CHAMPION'"
                size="sm"
                variant="outline"
                :disabled="restoring === model.modelVersion"
                @click="restoreModel(model)"
              >
                <Loader2 v-if="restoring === model.modelVersion" class="size-4 animate-spin" />
                恢复此版本
              </Button>
            </div>
          </div>
          <p v-else class="text-sm text-muted-foreground">暂无可恢复的历史有效模型。</p>
        </CardContent>
      </Card>
    </template>

    <div v-else-if="!loading" class="rounded-lg border border-dashed p-10 text-center text-sm text-muted-foreground">
      请先在投资分析中添加基金或股票。
    </div>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  BrainCircuit,
  ChevronDown,
  Loader2,
  PauseCircle,
  RefreshCw,
  Settings2,
} from '@lucide/vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { feedback } from '@/lib/feedback'
import {
  actionLabel,
  formatModelStatus,
  formatTrainingStatus,
  modelCanBeRestored,
  visiblePredictionMetrics,
} from '@/lib/quantModelManagement'
import {
  activateQuantAssetModelAPI,
  getQuantActionPlanAPI,
  getQuantModelManagementAPI,
  listInvestmentAssetsAPI,
  listQuantAssetModelsAPI,
  startQuantTrainingSessionAPI,
} from '@/api/investment'

const route = useRoute()
const router = useRouter()
const assets = ref([])
const assetId = ref(route.query.assetId ? String(route.query.assetId) : '')
const horizonCode = ref(String(route.query.horizonCode || 'MEDIUM').toUpperCase())
const management = ref({})
const actionPlan = ref({ status: 'PAUSED', action: 'PAUSE', stopConditions: [] })
const models = ref([])
const loading = ref(false)
const training = ref(false)
const restoring = ref('')
const historyOpen = ref(false)

const trainingStatus = computed(() => formatTrainingStatus(management.value.training))
const trainingTime = computed(() => {
  const trainingItem = management.value.training
  if (!trainingItem?.createdAt) return '数据更新后会自动检查是否需要训练'
  return `最近检查：${formatDateTime(trainingItem.finishedAt || trainingItem.createdAt)}`
})
const predictionMetrics = computed(() => visiblePredictionMetrics(actionPlan.value))
const visibleModels = computed(() => models.value.filter(
  model => model.modelLifecycle !== 'DRAFT'
))

const PlanMetric = defineComponent({
  props: { label: String, value: String },
  setup(props) {
    return () => h('div', { class: 'rounded-lg border bg-muted/20 p-3' }, [
      h('p', { class: 'text-xs text-muted-foreground' }, props.label),
      h('p', { class: 'mt-1 font-semibold' }, props.value || '-'),
    ])
  },
})

onMounted(loadAll)

watch(assetId, async value => {
  if (!value) return
  await router.replace({ query: { ...route.query, assetId: value, horizonCode: horizonCode.value } })
  await loadManagement()
})

watch(horizonCode, async value => {
  if (!assetId.value) return
  await router.replace({ query: { ...route.query, assetId: assetId.value, horizonCode: value } })
  await loadManagement()
})

async function loadAll() {
  loading.value = true
  try {
    const response = await listInvestmentAssetsAPI()
    assets.value = response.data || []
    if (!assets.value.some(item => String(item.id) === assetId.value)) {
      assetId.value = assets.value.length ? String(assets.value[0].id) : ''
    }
    if (assetId.value) await loadManagement()
  } finally {
    loading.value = false
  }
}

async function loadManagement() {
  if (!assetId.value) return
  loading.value = true
  try {
    const [managementResponse, planResponse, modelsResponse] = await Promise.all([
      getQuantModelManagementAPI(assetId.value),
      getQuantActionPlanAPI(assetId.value, horizonCode.value),
      listQuantAssetModelsAPI(assetId.value),
    ])
    management.value = managementResponse.data || {}
    actionPlan.value = planResponse.data || { status: 'PAUSED', action: 'PAUSE', stopConditions: [] }
    models.value = modelsResponse.data || []
  } finally {
    loading.value = false
  }
}

async function retrain() {
  if (!assetId.value || training.value) return
  training.value = true
  try {
    const response = await startQuantTrainingSessionAPI(assetId.value, horizonCode.value)
    management.value = {
      ...management.value,
      training: response.data || { status: 'QUEUED' },
    }
    feedback.success(response.data?.reused ? '已复用相同数据的自动训练任务' : '自动训练已开始')
  } finally {
    training.value = false
  }
}

async function restoreModel(model) {
  restoring.value = model.modelVersion
  try {
    await activateQuantAssetModelAPI(assetId.value, model.modelVersion)
    feedback.success('历史模型已进入模拟观察，当前有效模型不会立即被替换')
    await loadManagement()
  } finally {
    restoring.value = ''
  }
}

function openExpertMode() {
  router.push({ path: '/quant-lab/expert', query: { assetId: assetId.value } })
}

function modelDescription(model, fallback) {
  if (!model) return fallback
  return `${horizonLabel(model.horizonCode)} · ${roleLabel(model.deploymentRole)}`
}

function roleLabel(role) {
  return {
    CHAMPION: '当前使用',
    CHALLENGER: '模拟观察',
    ARCHIVED: '历史版本',
  }[role] || '历史版本'
}

function horizonLabel(code) {
  return { SHORT: '短期', MEDIUM: '中期', LONG: '长期' }[code] || '未知周期'
}

function metricText(metric) {
  if (metric.type === 'percent') return percent(metric.value)
  if (metric.type === 'interval') return interval(metric.value)
  return metric.value ?? '-'
}

function percent(value) {
  const number = Number(value)
  return Number.isFinite(number) ? `${(number * 100).toFixed(1)}%` : '-'
}

function interval(value) {
  if (!value) return '-'
  if (Array.isArray(value) && value.length >= 2) return `${percent(value[0])} ～ ${percent(value[1])}`
  if (typeof value === 'object') {
    const low = value.lower ?? value.low
    const high = value.upper ?? value.high
    return `${percent(low)} ～ ${percent(high)}`
  }
  return String(value)
}

function money(value) {
  const number = Number(value)
  return Number.isFinite(number)
    ? new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(number)
    : '-'
}

function quantity(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toLocaleString('zh-CN') : '-'
}

function formatDateTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false })
}
</script>
