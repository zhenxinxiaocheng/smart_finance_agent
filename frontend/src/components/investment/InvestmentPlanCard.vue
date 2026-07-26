<template>
  <section class="space-y-4 rounded-xl border p-4">
    <div class="flex items-start justify-between gap-3">
      <div>
        <div class="flex items-center gap-2">
          <h3 class="font-medium">模拟定投</h3>
          <span v-if="plan" class="rounded-full border px-2 py-0.5 text-[11px]" :class="plan.enabled === 1 ? 'text-emerald-500' : 'text-muted-foreground'">
            {{ plan.enabled === 1 ? '执行中' : '已暂停' }}
          </span>
        </div>
        <p class="mt-1 text-xs text-muted-foreground">按计划日净值自动更新模拟持仓，不会向真实券商下单</p>
      </div>
      <Switch
        v-if="plan"
        :model-value="plan.enabled === 1"
        :disabled="updating"
        :aria-label="plan.enabled === 1 ? '暂停模拟定投' : '启用模拟定投'"
        @update:model-value="toggleEnabled"
      />
    </div>

    <div class="rounded-lg bg-muted/40 p-3">
      <div class="text-xs text-muted-foreground">当前持仓成本</div>
      <div class="mt-1 text-xl font-semibold">{{ money(investedAmount) }}</div>
    </div>

    <div v-if="loading" class="py-6 text-center text-sm text-muted-foreground">正在加载定投计划</div>

    <template v-else-if="plan && !editing">
    <dl class="grid grid-cols-2 gap-3 text-sm">
      <div class="rounded-lg border p-3"><dt class="text-xs text-muted-foreground">每期定投金额</dt><dd class="mt-1 font-medium">{{ money(plan.amount) }}</dd></div>
      <div class="rounded-lg border p-3"><dt class="text-xs text-muted-foreground">定投频率</dt><dd class="mt-1 font-medium">{{ frequencyLabel(plan.frequency) }}</dd></div>
      <div v-if="plan.frequency !== 'DAILY'" class="rounded-lg border p-3"><dt class="text-xs text-muted-foreground">执行日</dt><dd class="mt-1 font-medium">{{ executionLabel(plan) }}</dd></div>
      <div class="rounded-lg border p-3"><dt class="text-xs text-muted-foreground">下次计划日</dt><dd class="mt-1 font-medium">{{ plan.nextExecutionDate || '-' }}</dd></div>
      <div class="rounded-lg border p-3"><dt class="text-xs text-muted-foreground">已模拟次数</dt><dd class="mt-1 font-medium">{{ plan.executionCount || 0 }} 次</dd></div>
      <div class="rounded-lg border p-3"><dt class="text-xs text-muted-foreground">最近执行</dt><dd class="mt-1 font-medium">{{ plan.lastExecutionDate || statusLabel(plan.lastExecutionStatus) }}</dd></div>
    </dl>
    <p v-if="plan.lastExecutionMessage" class="text-xs text-muted-foreground">{{ plan.lastExecutionMessage }}</p>
    <div class="grid grid-cols-2 gap-3">
      <Button variant="outline" :disabled="updating || deleting" @click="beginEdit">修改计划</Button>
      <Button variant="outline" class="text-destructive" :disabled="updating || deleting" @click="removePlan">{{ deleting ? '删除中' : '删除计划' }}</Button>
    </div>
    </template>

    <div v-else-if="!loading" class="space-y-4">
      <label>每期定投金额<Input v-model="form.amount" type="number" min="0.01" step="0.01" /></label>
      <div class="grid gap-3" :class="form.frequency === 'DAILY' ? 'grid-cols-1' : 'grid-cols-2'">
        <label>
          定投频率
          <Select v-model="form.frequency">
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectGroup>
                <SelectItem value="DAILY">每日</SelectItem>
                <SelectItem value="WEEKLY">每周</SelectItem>
                <SelectItem value="MONTHLY">每月</SelectItem>
              </SelectGroup>
            </SelectContent>
          </Select>
        </label>
        <label v-if="form.frequency !== 'DAILY'">
          执行日
          <Select v-model="form.executionDay">
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectGroup>
                <SelectItem v-for="option in dayOptions" :key="option.value" :value="option.value">{{ option.label }}</SelectItem>
              </SelectGroup>
            </SelectContent>
          </Select>
        </label>
      </div>
      <label v-if="editing">下次计划日<Input v-model="form.nextExecutionDate" type="date" /></label>
      <div class="grid" :class="editing ? 'grid-cols-2 gap-3' : 'grid-cols-1'">
        <Button v-if="editing" variant="outline" :disabled="saving" @click="cancelEdit">取消</Button>
        <Button :disabled="saving" @click="savePlan">{{ saving ? '保存中' : editing ? '保存修改' : '创建定投计划' }}</Button>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue
} from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { feedback } from '@/lib/feedback'
import { actualInvestedAmount, buildInvestmentPlanPayload } from '@/lib/investmentPlanCard'
import {
  createInvestmentPlanAPI,
  deleteInvestmentPlanAPI,
  listInvestmentPlansAPI,
  setInvestmentPlanEnabledAPI,
  updateInvestmentPlanAPI
} from '@/api/investment'

const props = defineProps({ asset: { type: Object, required: true } })
const emit = defineEmits(['holding-updated'])
const plan = ref(null)
const loading = ref(false)
const saving = ref(false)
const updating = ref(false)
const deleting = ref(false)
const editing = ref(false)
const form = reactive({ amount: '', frequency: 'MONTHLY', executionDay: '1', nextExecutionDate: '' })
let refreshTimer = null

const investedAmount = computed(() => actualInvestedAmount(props.asset))
const dayOptions = computed(() => form.frequency === 'WEEKLY'
  ? ['周一', '周二', '周三', '周四', '周五', '周六', '周日'].map((label, index) => ({ value: String(index + 1), label }))
  : Array.from({ length: 28 }, (_, index) => ({ value: String(index + 1), label: `${index + 1} 日` })))

watch(() => props.asset.id, loadPlan, { immediate: true })
watch(() => form.frequency, frequency => {
  if (frequency === 'DAILY' || (frequency === 'WEEKLY' && Number(form.executionDay) > 7)) form.executionDay = '1'
})

onMounted(() => {
  refreshTimer = window.setInterval(() => {
    loadPlan(props.asset.id, true).catch(() => {})
  }, 10000)
})
onUnmounted(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
})

async function loadPlan(assetId, silent = false) {
  if (!assetId) return
  if (!silent) loading.value = true
  try {
    const response = await listInvestmentPlansAPI()
    if (props.asset.id !== assetId) return
    const nextPlan = (response.data || []).find(item =>
      String(item.accountId) === String(props.asset.accountId)
      && String(item.productId) === String(props.asset.productId)) || null
    const executionAdvanced = plan.value && nextPlan
      && Number(nextPlan.executionCount || 0) > Number(plan.value.executionCount || 0)
    plan.value = nextPlan
    if (executionAdvanced) emit('holding-updated')
  } finally {
    if (!silent && props.asset.id === assetId) loading.value = false
  }
}

async function savePlan() {
  if (!form.amount || Number(form.amount) <= 0) return feedback.error('请输入正确的定投金额')
  saving.value = true
  try {
    const wasEditing = editing.value
    const payload = buildInvestmentPlanPayload(props.asset, form)
    const response = wasEditing
      ? await updateInvestmentPlanAPI(plan.value.id, payload)
      : await createInvestmentPlanAPI(payload)
    plan.value = response.data
    editing.value = false
    resetForm()
    feedback.success(wasEditing ? '定投计划已更新' : '定投计划已创建')
  } finally { saving.value = false }
}

function beginEdit() {
  form.amount = String(plan.value.amount)
  form.frequency = plan.value.frequency
  form.executionDay = String(plan.value.executionDay || 1)
  form.nextExecutionDate = plan.value.nextExecutionDate || ''
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  resetForm()
}

async function removePlan() {
  if (!plan.value || !window.confirm('确定删除这条模拟定投计划吗？')) return
  deleting.value = true
  try {
    await deleteInvestmentPlanAPI(plan.value.id)
    plan.value = null
    editing.value = false
    resetForm()
    feedback.success('定投计划已删除')
  } finally { deleting.value = false }
}

function resetForm() {
  form.amount = ''
  form.frequency = 'MONTHLY'
  form.executionDay = '1'
  form.nextExecutionDate = ''
}

async function toggleEnabled(enabled) {
  if (!plan.value) return
  updating.value = true
  try {
    const response = await setInvestmentPlanEnabledAPI(plan.value.id, enabled)
    plan.value = response.data
    feedback.success(enabled ? '定投计划已启用' : '定投计划已暂停')
  } finally { updating.value = false }
}

function money(value) {
  if (value == null) return '未填写持仓'
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: props.asset.currency || 'CNY',
    maximumFractionDigits: 2
  }).format(Number(value))
}

function frequencyLabel(value) {
  if (value === 'DAILY') return '每日'
  return value === 'WEEKLY' ? '每周' : '每月'
}
function executionLabel(value) {
  if (value.frequency === 'DAILY') return '每日'
  if (value.frequency === 'WEEKLY') return ['周一', '周二', '周三', '周四', '周五', '周六', '周日'][value.executionDay - 1] || '-'
  return `每月 ${value.executionDay} 日`
}
function statusLabel(value) {
  return ({ WAITING_DATA: '等待净值', FAILED: '执行失败', PAUSED: '已暂停', WAITING: '等待执行' }[value] || '尚未执行')
}
</script>

<style scoped>
label { display: grid; gap: .45rem; font-size: .875rem; font-weight: 500; }
</style>
