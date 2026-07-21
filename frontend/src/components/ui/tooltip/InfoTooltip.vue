<script setup lang="ts">
import { CircleHelp } from '@lucide/vue'
import {
  TooltipArrow,
  TooltipContent,
  TooltipPortal,
  TooltipProvider,
  TooltipRoot,
  TooltipTrigger,
} from 'reka-ui'

withDefaults(defineProps<{
  content: string
  label?: string
}>(), {
  label: '查看说明',
})
</script>

<template>
  <TooltipProvider :delay-duration="180" :skip-delay-duration="100">
    <TooltipRoot>
      <TooltipTrigger as-child>
        <button
          type="button"
          :aria-label="label"
          class="inline-grid size-5 shrink-0 place-items-center rounded-full text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
        >
          <CircleHelp class="size-3.5" />
        </button>
      </TooltipTrigger>
      <TooltipPortal>
        <TooltipContent
          side="top"
          :side-offset="7"
          class="z-50 max-w-[340px] whitespace-pre-line rounded-lg border bg-popover px-3.5 py-3 text-[13px] leading-5 text-popover-foreground shadow-md"
        >
          {{ content }}
          <TooltipArrow class="fill-popover stroke-border" :width="10" :height="5" />
        </TooltipContent>
      </TooltipPortal>
    </TooltipRoot>
  </TooltipProvider>
</template>
