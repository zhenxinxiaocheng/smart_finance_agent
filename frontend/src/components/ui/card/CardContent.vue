<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from "@/lib/utils"

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()
</script>

<template>
  <div
    data-slot="card-content"
    :class="cn('px-[var(--app-card-padding)] group-data-[size=sm]/card:px-[calc(var(--app-card-padding)*0.75)]', props.class)"
  >
    <slot />
  </div>
</template>
