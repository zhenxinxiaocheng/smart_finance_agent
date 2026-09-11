<template>
  <div class="space-y-3">
    <div class="relative">
      <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
      <Input
        v-model="code"
        class="h-11 pl-9 font-mono"
        maxlength="6"
        inputmode="numeric"
        :placeholder="`输入 6 位${typeLabel}代码`"
        @keyup.enter="submit"
      />
    </div>
    <Button class="h-11 w-full" :disabled="submitting || code.length !== 6" @click="submit">
      <LoaderCircle v-if="submitting" class="animate-spin" data-icon="inline-start" />
      {{ submitting ? '正在识别' : '添加' }}
    </Button>
    <p v-if="localError || errorMessage" class="text-sm text-destructive">
      {{ localError || errorMessage }}
    </p>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { LoaderCircle, Search } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

const props = defineProps({
  productType: { type: String, required: true },
  submitting: Boolean,
  errorMessage: { type: String, default: '' }
})
const emit = defineEmits(['submit'])
const code = ref('')
const localError = ref('')
const typeLabel = computed(() => props.productType === 'STOCK' ? '股票' : '基金')

function submit() {
  const normalized = code.value.replace(/\D/g, '').slice(0, 6)
  code.value = normalized
  if (normalized.length !== 6) {
    localError.value = `请输入 6 位${typeLabel.value}代码`
    return
  }
  localError.value = ''
  emit('submit', { productType: props.productType, code: normalized })
}
</script>
