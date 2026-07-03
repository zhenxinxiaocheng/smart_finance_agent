<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { CheckIcon } from "@lucide/vue"
import { cn } from "@/lib/utils"

const props = defineProps<{
  modelValue?: boolean
  disabled?: boolean
  class?: HTMLAttributes["class"]
}>()

const emit = defineEmits<{
  (e: "update:modelValue", value: boolean): void
}>()
</script>

<template>
  <button
    type="button"
    data-slot="checkbox"
    role="checkbox"
    :aria-checked="Boolean(modelValue)"
    :disabled="disabled"
    :data-state="modelValue ? 'checked' : 'unchecked'"
    :class="cn(
      'border-input bg-background focus-visible:border-ring focus-visible:ring-ring/50 data-[state=checked]:bg-primary data-[state=checked]:text-primary-foreground inline-flex size-4 shrink-0 items-center justify-center rounded-sm border shadow-xs transition-shadow outline-none focus-visible:ring-3 disabled:cursor-not-allowed disabled:opacity-50',
      props.class,
    )"
    @click="emit('update:modelValue', !modelValue)"
  >
    <CheckIcon v-if="modelValue" />
  </button>
</template>
