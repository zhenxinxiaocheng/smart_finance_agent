<script setup lang="ts">
import type { DialogContentEmits, DialogContentProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { X } from '@lucide/vue'
import { reactiveOmit } from '@vueuse/core'
import {
  DialogClose,
  DialogContent,
  DialogPortal,
  useForwardPropsEmits
} from 'reka-ui'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import SheetOverlay from './SheetOverlay.vue'

defineOptions({ inheritAttrs: false })

const props = withDefaults(defineProps<DialogContentProps & {
  class?: HTMLAttributes['class']
  side?: 'top' | 'right' | 'bottom' | 'left'
  showCloseButton?: boolean
}>(), {
  side: 'right',
  showCloseButton: true
})

const emits = defineEmits<DialogContentEmits>()
const delegatedProps = reactiveOmit(props, 'class', 'side', 'showCloseButton')
const forwarded = useForwardPropsEmits(delegatedProps, emits)

const sideClasses = {
  top: 'inset-x-0 top-0 border-b data-closed:slide-out-to-top data-open:slide-in-from-top',
  right: 'inset-y-0 right-0 h-full w-96 max-w-[calc(100vw-2rem)] border-l data-closed:slide-out-to-right data-open:slide-in-from-right',
  bottom: 'inset-x-0 bottom-0 border-t data-closed:slide-out-to-bottom data-open:slide-in-from-bottom',
  left: 'inset-y-0 left-0 h-full w-96 max-w-[calc(100vw-2rem)] border-r data-closed:slide-out-to-left data-open:slide-in-from-left'
}
</script>

<template>
  <DialogPortal>
    <SheetOverlay />
    <DialogContent
      data-slot="sheet-content"
      v-bind="{ ...$attrs, ...forwarded }"
      :class="cn(
        'fixed z-50 flex flex-col gap-5 border-border bg-popover p-6 text-popover-foreground shadow-2xl outline-none transition ease-in-out data-open:animate-in data-closed:animate-out data-closed:duration-200 data-open:duration-300',
        sideClasses[side],
        props.class
      )"
    >
      <slot />
      <DialogClose v-if="showCloseButton" as-child>
        <Button variant="ghost" size="icon-sm" class="absolute right-3 top-3">
          <X />
          <span class="sr-only">Close</span>
        </Button>
      </DialogClose>
    </DialogContent>
  </DialogPortal>
</template>
