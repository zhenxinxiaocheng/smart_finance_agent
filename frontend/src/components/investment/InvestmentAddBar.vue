<template>
  <div class="rounded-xl border bg-card p-4 shadow-sm">
    <div class="flex flex-col gap-3 lg:flex-row lg:items-center">
      <div class="flex rounded-lg bg-muted p-1">
        <button
          v-for="option in typeOptions"
          :key="option.value"
          type="button"
          class="rounded-md px-4 py-2 text-sm font-medium transition"
          :class="form.productType === option.value ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'"
          @click="form.productType = option.value"
        >
          {{ option.label }}
        </button>
      </div>
      <div class="relative min-w-0 flex-1">
        <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          v-model="form.code"
          class="h-11 pl-9 font-mono"
          maxlength="6"
          inputmode="numeric"
          :placeholder="form.productType === 'STOCK' ? '输入 6 位股票代码' : '输入 6 位基金代码'"
          @keyup.enter="addAsset"
        />
      </div>
      <Button class="h-11 px-6" :disabled="submitting || form.code.length !== 6" @click="addAsset">
        <LoaderCircle v-if="submitting" class="animate-spin" data-icon="inline-start" />
        <Plus v-else data-icon="inline-start" />
        {{ submitting ? '正在识别' : '添加' }}
      </Button>
    </div>
    <p v-if="errorMessage" class="mt-3 text-sm text-destructive">{{ errorMessage }}</p>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { LoaderCircle, Plus, Search } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { feedback } from '@/lib/feedback'
import { createInvestmentAssetAPI } from '@/api/investment'

const emit = defineEmits(['added'])
const typeOptions = [
  { value: 'STOCK', label: '股票' },
  { value: 'MUTUAL_FUND', label: '基金' }
]
const form = reactive({ productType: 'STOCK', code: '' })
const submitting = ref(false)
const errorMessage = ref('')

async function addAsset() {
  const code = form.code.replace(/\D/g, '').slice(0, 6)
  form.code = code
  if (code.length !== 6) {
    errorMessage.value = '请输入 6 位股票或基金代码'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const response = await createInvestmentAssetAPI({ productType: form.productType, code })
    feedback.success(`已添加 ${response.data?.name || code}`)
    form.code = ''
    emit('added')
  } catch (error) {
    errorMessage.value = error.response?.data?.message || error.response?.data?.detail || error.message || '代码识别失败'
  } finally {
    submitting.value = false
  }
}
</script>
