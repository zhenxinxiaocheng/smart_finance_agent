<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from "@/lib/utils"

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()
</script>

<template>
  <div
    data-slot="card-header"
    :class="cn('gap-1 rounded-t-xl px-[var(--app-card-padding)] group-data-[size=sm]/card:px-[calc(var(--app-card-padding)*0.75)] [.border-b]:pb-[var(--app-card-padding)] group-data-[size=sm]/card:[.border-b]:pb-[calc(var(--app-card-padding)*0.75)] group/card-header @container/card-header grid auto-rows-min items-start has-data-[slot=card-action]:grid-cols-[1fr_auto] has-data-[slot=card-description]:grid-rows-[auto_auto]', props.class)"
  >
    <slot />
  </div>
</template>
