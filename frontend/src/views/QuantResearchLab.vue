<template>
  <div class="mx-auto max-w-[1480px] space-y-5">
    <header class="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
      <div>
        <div class="flex items-center gap-2">
          <FlaskConical class="size-6 text-primary" />
          <h1 class="text-2xl font-semibold tracking-tight">量化研究实验室</h1>
          <Badge variant="outline">严格验证</Badge>
        </div>
        <p class="mt-1 text-sm text-muted-foreground">
          配置、训练、比较并验证模型。只有 VALIDATED 模型才能生成金额、数量和模拟订单。
        </p>
      </div>
      <Button variant="outline" :disabled="loading" @click="loadAll">
        <RefreshCw :class="['size-4', loading && 'animate-spin']" />
        刷新研究状态
      </Button>
    </header>

    <section class="grid gap-3 sm:grid-cols-3">
      <Card>
        <CardHeader class="pb-2"><CardDescription>验证模式</CardDescription></CardHeader>
        <CardContent class="text-xl font-semibold">{{ quality.validationMode || 'STRICT' }}</CardContent>
      </Card>
      <Card>
        <CardHeader class="pb-2"><CardDescription>研究实验</CardDescription></CardHeader>
        <CardContent class="text-xl font-semibold">{{ quality.experimentCount ?? experiments.length }}</CardContent>
      </Card>
      <Card>
        <CardHeader class="pb-2"><CardDescription>运行中</CardDescription></CardHeader>
        <CardContent class="text-xl font-semibold">{{ quality.runningCount ?? runningCount }}</CardContent>
      </Card>
    </section>

    <section class="grid gap-5 xl:grid-cols-[minmax(0,0.88fr)_minmax(0,1.12fr)]">
      <Card>
        <CardHeader>
          <CardTitle>新建实验</CardTitle>
          <CardDescription>参数表单由后端 Schema 生成，验证门槛不可在这里降低。</CardDescription>
        </CardHeader>
        <CardContent class="space-y-5">
          <div class="grid gap-4 sm:grid-cols-2">
            <label class="space-y-1.5 text-sm">
              <span class="font-medium">历史研究资产池</span>
              <select v-model="form.universeId" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none transition-colors focus:ring-2 focus:ring-ring">
                <option value="">仅目标资产</option>
                <option v-for="universe in filteredUniverses" :key="universe.id" :value="String(universe.id)">
                  {{ universe.name }}（{{ universe.memberCount }} 个成员）
                </option>
              </select>
            </label>
            <label class="space-y-1.5 text-sm">
              <span class="font-medium">研究资产</span>
              <select v-model="form.assetId" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none transition-colors focus:ring-2 focus:ring-ring" @change="selectAssetFamily">
                <option disabled value="">请选择资产</option>
                <option v-for="asset in assets" :key="asset.id" :value="String(asset.id)">
                  {{ asset.name }} · {{ asset.code }}
                </option>
              </select>
            </label>
            <label class="space-y-1.5 text-sm">
              <span class="font-medium">模型家族</span>
              <select v-model="form.modelFamily" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none transition-colors focus:ring-2 focus:ring-ring" @change="selectDefaultUniverse">
                <option v-for="family in families" :key="family.code" :value="family.code">
                  {{ family.name }}（{{ family.code }}）
                </option>
              </select>
            </label>
            <label class="space-y-1.5 text-sm">
              <span class="font-medium">预测周期</span>
              <select v-model="form.horizonCode" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none transition-colors focus:ring-2 focus:ring-ring">
                <option v-for="horizon in schema.horizons || []" :key="horizon" :value="horizon">
                  {{ horizonLabel(horizon) }}
                </option>
              </select>
            </label>
            <label class="space-y-1.5 text-sm">
              <span class="font-medium">算法</span>
              <select v-model="form.algorithm" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none transition-colors focus:ring-2 focus:ring-ring">
                <option v-for="algorithm in schema.algorithms || []" :key="algorithm" :value="algorithm">
                  {{ algorithmLabel(algorithm) }}
                </option>
              </select>
            </label>
          </div>

          <div>
            <div class="mb-3 flex items-center justify-between">
              <div>
                <h3 class="text-sm font-semibold">可调参数</h3>
                <p class="text-xs text-muted-foreground">修改后会生成新的实验指纹；相同指纹自动复用。</p>
              </div>
              <Button size="sm" variant="ghost" @click="resetParameters">恢复默认</Button>
            </div>
            <div class="grid gap-3 sm:grid-cols-2">
              <label v-for="field in schema.fields || []" :key="field.key" class="space-y-1.5 text-sm">
                <span class="flex items-center justify-between gap-2">
                  <span class="font-medium">{{ field.label }}</span>
                  <span class="text-xs text-muted-foreground">{{ field.minimum }}～{{ field.maximum }}</span>
                </span>
                <Input
                  v-model.number="form.parameters[field.key]"
                  type="number"
                  :min="field.minimum"
                  :max="field.maximum"
                  :step="field.step"
                />
              </label>
            </div>
          </div>

          <div class="rounded-lg border border-amber-500/30 bg-amber-500/5 p-3 text-xs text-muted-foreground">
            固定门槛：至少 {{ schema.immutableValidation?.minimumWalkForwardFolds || 5 }} 个走步窗口，
            DM p 值不高于 {{ schema.immutableValidation?.maximumDmPValue || 0.05 }}，
            DSR 概率不低于 {{ percent(schema.immutableValidation?.minimumDeflatedSharpeProbability || 0.95) }}，
            PBO 不高于 {{ percent(schema.immutableValidation?.maximumPbo || 0.2) }}。
          </div>

          <Button class="w-full" :disabled="creating || !form.assetId" @click="createExperiment">
            <Loader2 v-if="creating" class="size-4 animate-spin" />
            <Play v-else class="size-4" />
            开始严格验证实验
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div class="flex items-start justify-between gap-3">
            <div>
              <CardTitle>实验详情</CardTitle>
              <CardDescription>逐项展示实际值、要求值和失败原因。</CardDescription>
            </div>
            <Badge v-if="selected" :variant="statusVariant(selected.status)">
              {{ selected.status }}
            </Badge>
          </div>
        </CardHeader>
        <CardContent v-if="selected" class="space-y-5">
          <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <div v-for="item in detailMetrics" :key="item.label" class="rounded-lg border bg-muted/20 p-3">
              <p class="text-xs text-muted-foreground">{{ item.label }}</p>
              <p class="mt-1 text-sm font-semibold">{{ item.value }}</p>
            </div>
          </div>

          <div v-if="selected.errorCode" class="rounded-lg border border-destructive/30 bg-destructive/5 p-3">
            <p class="text-sm font-semibold text-destructive">{{ failureLabel(selected.errorCode) }}</p>
            <p class="mt-1 text-sm text-muted-foreground">{{ selected.errorSummary }}</p>
          </div>

          <div class="grid gap-2 text-xs text-muted-foreground sm:grid-cols-2">
            <p>数据版本：<span class="break-all text-foreground">{{ selected.datasetVersion || '-' }}</span></p>
            <p>特征版本：<span class="break-all text-foreground">{{ selected.featureSetVersion || '-' }}</span></p>
            <p>配置版本：<span class="break-all text-foreground">{{ selected.quantConfigVersion || '-' }}</span></p>
            <p>模型版本：<span class="break-all text-foreground">{{ selected.candidateModelVersion || '-' }}</span></p>
          </div>

          <div>
            <div class="mb-2 flex items-center justify-between">
              <h3 class="text-sm font-semibold">训练质量检查</h3>
              <Badge
                v-if="selected.validationReport"
                :variant="selected.validationReport.passed ? 'default' : 'destructive'"
              >
                {{ selected.validationReport.passed ? '全部通过' : '未通过验证' }}
              </Badge>
            </div>
            <div class="max-h-[420px] overflow-auto rounded-lg border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>指标</TableHead>
                    <TableHead>实际值</TableHead>
                    <TableHead>要求</TableHead>
                    <TableHead>状态</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow v-for="check in checkRows" :key="check.key">
                    <TableCell>
                      <p class="font-medium">{{ metricLabel(check.key) }}</p>
                      <p v-if="check.explanation" class="mt-0.5 text-xs text-muted-foreground">{{ check.explanation }}</p>
                    </TableCell>
                    <TableCell>{{ metricValue(check.actual) }}</TableCell>
                    <TableCell>{{ check.threshold }}</TableCell>
                    <TableCell>
                      <Badge :variant="check.passed ? 'outline' : 'destructive'">{{ check.statusLabel }}</Badge>
                    </TableCell>
                  </TableRow>
                  <TableRow v-if="!checkRows.length">
                    <TableCell colspan="4" class="py-10 text-center text-muted-foreground">
                      {{ isTerminal(selected.status) ? '本次任务没有生成验证报告，请查看错误摘要。' : '训练和走步验证进行中…' }}
                    </TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </div>
          </div>

          <div class="flex flex-wrap gap-2">
            <Button
              v-if="!isTerminal(selected.status)"
              variant="destructive"
              :disabled="acting"
              @click="cancelSelected"
            >
              取消实验
            </Button>
            <Button
              :disabled="acting || !canPromote(selected)"
              @click="promoteSelected"
            >
              晋级模拟盘
            </Button>
            <Button variant="outline" @click="copySelectedParameters">复制参数</Button>
            <Button variant="outline" @click="rerunSelected">用新指纹重跑</Button>
          </div>
        </CardContent>
        <CardContent v-else class="py-20 text-center text-sm text-muted-foreground">
          选择一个历史实验，或从左侧创建新实验。
        </CardContent>
      </Card>
    </section>

    <Card>
      <CardHeader>
        <CardTitle>实验对比</CardTitle>
        <CardDescription>同一配置指纹只会创建一次任务。</CardDescription>
      </CardHeader>
      <CardContent>
        <div class="overflow-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>资产</TableHead>
                <TableHead>家族 / 算法</TableHead>
                <TableHead>周期</TableHead>
                <TableHead>任务</TableHead>
                <TableHead>模型</TableHead>
                <TableHead>失败原因</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableRow
                v-for="experiment in experiments"
                :key="experiment.id"
                class="cursor-pointer"
                :class="selected?.id === experiment.id && 'bg-muted/60'"
                @click="selectExperiment(experiment)"
              >
                <TableCell>#{{ experiment.id }}</TableCell>
                <TableCell>{{ assetName(experiment.assetId) }}</TableCell>
                <TableCell>
                  <p class="font-medium">{{ experiment.modelFamily }}</p>
                  <p class="text-xs text-muted-foreground">{{ algorithmLabel(experiment.algorithm) }}</p>
                </TableCell>
                <TableCell>{{ horizonLabel(experiment.horizonCode) }}</TableCell>
                <TableCell><Badge :variant="statusVariant(experiment.status)">{{ experiment.status }}</Badge></TableCell>
                <TableCell>{{ experiment.validationReport?.lifecycle || '-' }}</TableCell>
                <TableCell>{{ experiment.errorCode ? failureLabel(experiment.errorCode) : '-' }}</TableCell>
              </TableRow>
              <TableRow v-if="!experiments.length">
                <TableCell colspan="7" class="py-10 text-center text-muted-foreground">还没有量化实验。</TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { FlaskConical, Loader2, Play, RefreshCw } from '@lucide/vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow
} from '@/components/ui/table'
import { feedback } from '@/lib/feedback'
import {
  buildDefaultParameters,
  canPromoteExperiment,
  isExperimentTerminal,
  validationCheckRows
} from '@/lib/quantResearch'
import {
  cancelQuantExperimentAPI,
  createQuantExperimentAPI,
  getQuantDataQualityAPI,
  getQuantExperimentAPI,
  getQuantModelFamiliesAPI,
  getQuantParameterSchemaAPI,
  listInvestmentAssetsAPI,
  listQuantBenchmarksAPI,
  listQuantResearchUniversesAPI,
  listQuantExperimentsAPI,
  promoteQuantExperimentAPI
} from '@/api/investment'

const route = useRoute()
const assets = ref([])
const families = ref([])
const benchmarks = ref([])
const universes = ref([])
const experiments = ref([])
const selected = ref(null)
const schema = ref({ fields: [], horizons: [], algorithms: [], immutableValidation: {} })
const quality = ref({})
const loading = ref(false)
const creating = ref(false)
const acting = ref(false)
const form = reactive({
  assetId: route.query.assetId ? String(route.query.assetId) : '',
  universeId: '',
  modelFamily: '',
  horizonCode: '',
  algorithm: '',
  parameters: {}
})
let pollTimer

const runningCount = computed(() => experiments.value.filter(item => !isExperimentTerminal(item.status)).length)
const filteredUniverses = computed(() => universes.value.filter(
  item => item.modelFamily === form.modelFamily
))
const checkRows = computed(() => validationCheckRows(selected.value?.validationReport))
const detailMetrics = computed(() => [
  { label: '模型生命周期', value: selected.value?.validationReport?.lifecycle || 'DRAFT' },
  { label: '模型家族', value: selected.value?.modelFamily || '-' },
  { label: '算法', value: algorithmLabel(selected.value?.algorithm) },
  { label: '有效周期', value: `${selected.value?.horizonDays || '-'} 日` }
])

onMounted(loadAll)
onUnmounted(() => clearTimeout(pollTimer))

async function loadAll() {
  loading.value = true
  try {
    const [assetRes, familyRes, schemaRes, benchmarkRes, universeRes, experimentRes, qualityRes] = await Promise.all([
      listInvestmentAssetsAPI(),
      getQuantModelFamiliesAPI(),
      getQuantParameterSchemaAPI(),
      listQuantBenchmarksAPI(),
      listQuantResearchUniversesAPI(),
      listQuantExperimentsAPI(),
      getQuantDataQualityAPI()
    ])
    assets.value = assetRes.data || []
    families.value = familyRes.data || []
    schema.value = schemaRes.data || schema.value
    benchmarks.value = benchmarkRes.data || []
    universes.value = universeRes.data || []
    experiments.value = experimentRes.data || []
    quality.value = qualityRes.data || {}
    initializeForm()
    if (selected.value) {
      selected.value = experiments.value.find(item => item.id === selected.value.id) || selected.value
    } else if (experiments.value.length) {
      await selectExperiment(experiments.value[0])
    }
  } finally {
    loading.value = false
  }
}

function initializeForm() {
  if (!form.assetId && assets.value.length) form.assetId = String(assets.value[0].id)
  selectAssetFamily()
  if (!form.horizonCode) form.horizonCode = schema.value.horizons?.[0] || 'SHORT'
  if (!form.algorithm) form.algorithm = schema.value.algorithms?.[2] || schema.value.algorithms?.[0] || 'VALIDATED_ENSEMBLE'
  if (!Object.keys(form.parameters).length) resetParameters()
}

function selectAssetFamily() {
  const asset = assets.value.find(item => String(item.id) === form.assetId)
  const benchmark = asset
    ? benchmarks.value.find(item => item.productCode === asset.code)
    : null
  if (benchmark?.modelFamily) {
    form.modelFamily = benchmark.modelFamily
  } else if (asset?.productType === 'STOCK') {
    form.modelFamily = 'A_SHARE_STOCK'
  } else if (!form.modelFamily && families.value.length) {
    form.modelFamily = families.value[0].code
  }
  selectDefaultUniverse()
}

function selectDefaultUniverse() {
  const selectedUniverse = universes.value.find(
    item => String(item.id) === form.universeId && item.modelFamily === form.modelFamily
  )
  if (!selectedUniverse) {
    form.universeId = filteredUniverses.value.length
      ? String(filteredUniverses.value[0].id)
      : ''
  }
}

function resetParameters() {
  form.parameters = buildDefaultParameters(schema.value)
}

async function createExperiment() {
  creating.value = true
  try {
    const response = await createQuantExperimentAPI({
      assetId: Number(form.assetId),
      universeId: form.universeId ? Number(form.universeId) : null,
      modelFamily: form.modelFamily,
      horizonCode: form.horizonCode,
      algorithm: form.algorithm,
      parameters: form.parameters
    })
    selected.value = response.data
    feedback.success(response.data?.reused ? '已复用相同配置的实验' : '量化实验已创建')
    await refreshExperiments()
    schedulePoll()
  } finally {
    creating.value = false
  }
}

async function refreshExperiments() {
  const response = await listQuantExperimentsAPI()
  experiments.value = response.data || []
}

async function selectExperiment(experiment) {
  clearTimeout(pollTimer)
  const response = await getQuantExperimentAPI(experiment.id)
  selected.value = response.data
  schedulePoll()
}

function schedulePoll() {
  clearTimeout(pollTimer)
  if (!selected.value || isExperimentTerminal(selected.value.status)) return
  pollTimer = setTimeout(async () => {
    try {
      const response = await getQuantExperimentAPI(selected.value.id)
      selected.value = response.data
      if (isExperimentTerminal(selected.value.status)) {
        await refreshExperiments()
      }
    } finally {
      schedulePoll()
    }
  }, 2500)
}

async function cancelSelected() {
  acting.value = true
  try {
    selected.value = (await cancelQuantExperimentAPI(selected.value.id)).data
    feedback.info('实验已取消')
    await refreshExperiments()
  } finally {
    acting.value = false
  }
}

async function promoteSelected() {
  acting.value = true
  try {
    selected.value = (await promoteQuantExperimentAPI(selected.value.id)).data
    feedback.success('模型已晋级模拟盘')
    await refreshExperiments()
  } finally {
    acting.value = false
  }
}

function copySelectedParameters() {
  form.assetId = String(selected.value.assetId)
  form.universeId = selected.value.universeId ? String(selected.value.universeId) : ''
  form.modelFamily = selected.value.modelFamily
  form.horizonCode = selected.value.horizonCode
  form.algorithm = selected.value.algorithm || 'VALIDATED_ENSEMBLE'
  form.parameters = { ...(selected.value.parameters || {}) }
  feedback.success('参数已复制到新建实验表单')
}

function rerunSelected() {
  copySelectedParameters()
  const learningRate = Number(form.parameters.learningRate || 0)
  if (learningRate) {
    form.parameters.learningRate = Number((learningRate + 0.005).toFixed(3))
  }
  feedback.info('已轻微调整参数以生成新实验指纹，请确认后开始实验')
}

const canPromote = canPromoteExperiment
const isTerminal = isExperimentTerminal
const assetName = id => {
  const asset = assets.value.find(item => item.id === id)
  return asset ? `${asset.name} · ${asset.code}` : `资产 #${id}`
}
const horizonLabel = value => ({ SHORT: '短期', MEDIUM: '中期', LONG: '长期' }[value] || value)
const algorithmLabel = value => ({
  ELASTIC_NET: 'ElasticNet / Logistic',
  GRADIENT_BOOSTING: '梯度提升树',
  VALIDATED_ENSEMBLE: '验证加权集成'
}[value] || value || '-')
const failureLabel = value => ({
  JOB_FAILED: '训练任务执行失败',
  MODEL_REJECTED: '模型未通过严格验证',
  INSUFFICIENT_DATA: '有效样本不足',
  DATA_STALE: '研究数据已过期',
  BENCHMARK_UNAVAILABLE: '官方基准不可用'
}[value] || value)
const metricLabel = value => ({
  minimumSamples: '独立样本事件',
  minimumFolds: '外层时间窗口',
  oosR2: '样本外 R²',
  dmPValue: 'Diebold–Mariano p 值',
  medianRankIc: 'Rank IC 中位数',
  positiveIcFoldRatio: 'IC 为正窗口占比',
  brierSkill: 'Brier Skill',
  logLossSkill: 'LogLoss Skill',
  calibrationSlope: '校准斜率',
  calibrationIntercept: '校准截距',
  intervalCoverage: '80% 区间覆盖率',
  pinballSkill: 'Pinball Skill',
  annualizedExcessReturn: '成本后年化超额',
  sharpe: '原始 Sharpe',
  deflatedSharpeProbability: 'DSR 置信概率',
  pbo: '过拟合概率 PBO',
  profitableFoldRatio: '盈利窗口占比',
  maximumDrawdown: '最大回撤',
  costStressAnnualizedExcessReturn: '1.5倍成本压力收益'
}[value] || value)
const metricValue = value => value == null ? '-' : (typeof value === 'number' ? Number(value.toFixed(6)) : value)
const percent = value => `${(Number(value || 0) * 100).toFixed(0)}%`
const statusVariant = status => status === 'FAILED'
  ? 'destructive'
  : status === 'SUCCEEDED'
    ? 'default'
    : 'outline'
</script>
