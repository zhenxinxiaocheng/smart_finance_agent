<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from "@/lib/utils"

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()
</script>

<template>
  <div
    data-slot="card-footer"
    :class="cn('bg-muted/50 rounded-b-xl border-t p-[var(--app-card-padding)] group-data-[size=sm]/card:p-[calc(var(--app-card-padding)*0.75)] flex items-center', props.class)"
  >
    <slot />
  </div>
</template>
