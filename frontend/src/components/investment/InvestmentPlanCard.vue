<template>
  <section class="space-y-4 rounded-xl border p-4">
    <div class="flex items-start justify-between gap-3">
      <div>
        <h3 class="font-medium">定投计划</h3>
        <p class="mt-1 text-xs text-muted-foreground">休市日自动顺延，不会自动下单</p>
      </div>
      <Switch
        v-if="plan"
        :model-value="plan.enabled === 1"
        :disabled="updating"
        aria-label="启用定投计划"
        @update:model-value="toggleEnabled"
      />
    </div>

    <div class="rounded-lg bg-muted/40 p-3">
      <div class="text-xs text-muted-foreground">当前实际投入</div>
      <div class="mt-1 text-xl font-semibold">{{ money(investedAmount) }}</div>
    </div>

    <div v-if="loading" class="py-6 text-center text-sm text-muted-foreground">正在加载定投计划</div>

    <dl v-else-if="plan" class="grid grid-cols-2 gap-3 text-sm">
      <div class="rounded-lg border p-3"><dt class="text-xs text-muted-foreground">每期定投金额</dt><dd class="mt-1 font-medium">{{ money(plan.amount) }}</dd></div>
      <div class="rounded-lg border p-3"><dt class="text-xs text-muted-foreground">定投频率</dt><dd class="mt-1 font-medium">{{ frequencyLabel(plan.frequency) }}</dd></div>
      <div class="rounded-lg border p-3"><dt class="text-xs text-muted-foreground">执行日</dt><dd class="mt-1 font-medium">{{ executionLabel(plan) }}</dd></div>
      <div class="rounded-lg border p-3"><dt class="text-xs text-muted-foreground">下次计划日</dt><dd class="mt-1 font-medium">{{ plan.nextExecutionDate || '-' }}</dd></div>
    </dl>

    <div v-else class="space-y-4">
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
      <Button class="w-full" :disabled="saving" @click="createPlan">{{ saving ? '保存中' : '创建定投计划' }}</Button>
    </div>
  </section>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
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
import { createInvestmentPlanAPI, listInvestmentPlansAPI, setInvestmentPlanEnabledAPI } from '@/api/investment'

const props = defineProps({ asset: { type: Object, required: true } })
const plan = ref(null)
const loading = ref(false)
const saving = ref(false)
const updating = ref(false)
const form = reactive({ amount: '', frequency: 'MONTHLY', executionDay: '1' })

const investedAmount = computed(() => actualInvestedAmount(props.asset))
const dayOptions = computed(() => form.frequency === 'WEEKLY'
  ? ['周一', '周二', '周三', '周四', '周五', '周六', '周日'].map((label, index) => ({ value: String(index + 1), label }))
  : Array.from({ length: 28 }, (_, index) => ({ value: String(index + 1), label: `${index + 1} 日` })))

watch(() => props.asset.id, loadPlan, { immediate: true })
watch(() => form.frequency, () => { form.executionDay = '1' })

async function loadPlan(assetId) {
  if (!assetId) return
  loading.value = true
  try {
    const response = await listInvestmentPlansAPI()
    if (props.asset.id !== assetId) return
    plan.value = (response.data || []).find(item =>
      String(item.accountId) === String(props.asset.accountId)
      && String(item.productId) === String(props.asset.productId)) || null
  } finally {
    if (props.asset.id === assetId) loading.value = false
  }
}

async function createPlan() {
  if (!form.amount || Number(form.amount) <= 0) return feedback.error('请输入正确的定投金额')
  saving.value = true
  try {
    const response = await createInvestmentPlanAPI(buildInvestmentPlanPayload(props.asset, form))
    plan.value = response.data
    feedback.success('定投计划已创建')
  } finally { saving.value = false }
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
</script>

<style scoped>
label { display: grid; gap: .45rem; font-size: .875rem; font-weight: 500; }
</style>
