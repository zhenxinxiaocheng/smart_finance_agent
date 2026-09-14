<script setup>
import { computed, ref, watch } from 'vue'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { quant } from '@/api/quantWorkbench.js'
import { applicableSensitivityParameters, explainExperimentError, frozenStrategyType } from './experimentExplanation.js'
import { parameterTitle } from './parameterPresentation.js'

const props = defineProps({
  open: Boolean,
  backtest: { type: Object, default: null },
  eligibility: { type: Object, default: null },
  catalog: { type: Object, default: null },
})
const emit = defineEmits(['update:open', 'created'])
const parameterKey = ref('')
const name = ref('')
const busy = ref(false)
const error = ref(null)
let pendingSignature = ''
let pendingKey = ''
let nameWasEdited = false

const parameters = computed(() => applicableSensitivityParameters(props.catalog, frozenStrategyType(props.backtest)))
const selectedTitle = computed(() => parameterTitle(parameterKey.value))

function reset() {
  parameterKey.value = parameters.value[0]?.key || ''
  name.value = parameterKey.value ? `${selectedTitle.value}参数敏感性` : ''
  error.value = null
  pendingSignature = ''
  pendingKey = ''
  nameWasEdited = false
}

watch(() => props.open, value => { if (value) reset() })
watch(parameters, values => { if (props.open && !values.some(item => item.key === parameterKey.value)) reset() })
watch(parameterKey, () => {
  if (!nameWasEdited && parameterKey.value) name.value = `${selectedTitle.value}参数敏感性`
})

async function submit() {
  if (busy.value || !props.eligibility?.eligible || !parameterKey.value || !name.value.trim()) return
  const payload = { sourceBacktestId: props.backtest.id, parameterKey: parameterKey.value, name: name.value.trim() }
  const signature = JSON.stringify(payload)
  if (signature !== pendingSignature) {
    pendingSignature = signature
    pendingKey = crypto.randomUUID()
  }
  busy.value = true
  error.value = null
  try {
    const created = await quant.experiments.create(payload, pendingKey)
    pendingSignature = ''
    pendingKey = ''
    emit('update:open', false)
    emit('created', created)
  } catch (cause) {
    error.value = explainExperimentError(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Dialog :open="open" @update:open="value=>emit('update:open',value)">
    <DialogContent class="qw max-h-[88vh] overflow-y-auto sm:max-w-[680px]">
      <DialogHeader><DialogTitle>参数敏感性检查</DialogTitle><DialogDescription>系统会保持研究数据和其他配置不变，仅调整一个参数，在当前值附近运行五个候选点，用于观察结果是否依赖某个孤立参数值。</DialogDescription></DialogHeader>
      <form class="px-1 pb-1" @submit.prevent="submit">
        <div class="fields">
          <label class="field">检查参数<select v-model="parameterKey" required><option value="" disabled>选择参数</option><option v-for="item in parameters" :key="item.key" :value="item.key">{{ parameterTitle(item.key) }}</option></select></label>
          <label class="field">实验名称<Input :model-value="name" required maxlength="120" @update:model-value="value=>{name=value;nameWasEdited=true}" /></label>
        </div>
        <p v-if="!frozenStrategyType(backtest)" class="mt-3 text-sm text-destructive" role="alert">来源回测未记录冻结策略类型，无法选择适用参数。</p>
        <p v-else-if="!parameters.length" class="mt-3 text-sm text-destructive" role="alert">当前冻结策略没有可检查的参数。</p>
        <p v-if="error" class="mt-3 text-sm text-destructive" role="alert">{{ error.message }}<code v-if="error.reasonCode" class="block muted mt-1">{{ error.reasonCode }}</code></p>
        <div class="quant-actions mt-5 justify-end"><Button type="button" variant="outline" :disabled="busy" @click="emit('update:open',false)">取消</Button><Button type="submit" :disabled="busy||!eligibility?.eligible||!parameterKey||!name.trim()">{{ busy?'正在创建…':'开始检查' }}</Button></div>
      </form>
    </DialogContent>
  </Dialog>
</template>
