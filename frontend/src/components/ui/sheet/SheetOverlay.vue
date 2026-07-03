<script setup lang="ts">
import type { DialogOverlayProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { DialogOverlay } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<DialogOverlayProps & { class?: HTMLAttributes['class'] }>()
const delegatedProps = reactiveOmit(props, 'class')
</script>

<template>
  <DialogOverlay
    data-slot="sheet-overlay"
    v-bind="delegatedProps"
    :class="cn('fixed inset-0 z-50 bg-black/35 backdrop-blur-[1px] data-open:animate-in data-closed:animate-out data-open:fade-in-0 data-closed:fade-out-0', props.class)"
  />
</template>
