<template>
  <section class="space-y-3">
    <div class="flex items-center justify-between gap-3">
      <div>
        <h2 class="font-semibold">市场指数</h2>
      </div>
      <Button size="sm" variant="outline" @click="$emit('add')">
        <Plus data-icon="inline-start" />
        添加指数
      </Button>
    </div>

    <div
      ref="strip"
      class="index-strip flex cursor-grab gap-3 overflow-x-auto pb-1"
      :class="{ 'cursor-grabbing select-none': dragging || reorderingCode }"
      role="region"
      aria-label="市场指数横向列表"
      tabindex="0"
      @pointerdown="startDrag"
      @pointermove="drag"
      @pointerup="stopDrag"
      @pointercancel="stopDrag"
      @lostpointercapture="stopDrag"
      @focusin="pauseOnFocus"
      @focusout="resumeAfterFocus"
      @scroll.passive="wrapScroll"
    >
      <template v-if="!displayedIndexes.length">
        <article
          v-for="position in 2"
          :key="position"
          aria-hidden="true"
          class="min-h-32 w-[280px] shrink-0 rounded-xl border bg-card p-4 shadow-sm"
        >
          <div class="h-4 w-24 rounded bg-muted/70"></div>
          <div class="mt-2 h-3 w-16 rounded bg-muted/50"></div>
          <div class="mt-6 h-7 w-32 rounded bg-muted/60"></div>
          <div class="mt-3 h-4 w-24 rounded bg-muted/50"></div>
        </article>
      </template>
      <TransitionGroup v-else name="index-order">
        <div
          v-for="entry in loopedIndexes"
          :key="entry.key"
          :data-index-code="entry.item.indexCode"
          :data-cycle="entry.cycle"
          :aria-hidden="entry.cycle === 0 ? undefined : 'true'"
          class="w-[280px] shrink-0"
          :class="{ 'z-10': reorderingCode === entry.item.indexCode && reorderingCycle === entry.cycle }"
        >
          <article
            :aria-label="`${entry.item.name}，按住拖动可调整位置`"
            class="group relative h-full cursor-grab rounded-xl border bg-card p-4 shadow-sm transition-[background-color,border-color,box-shadow] duration-200 ease-out"
            :class="{ 'cursor-grabbing border-primary/60 bg-accent/40 shadow-lg ring-2 ring-inset ring-primary/40': reorderingCode === entry.item.indexCode && reorderingCycle === entry.cycle }"
          >
            <button
              v-if="!entry.item.defaultItem"
              type="button"
              class="absolute right-2 top-2 rounded-md p-1 text-muted-foreground opacity-100 transition hover:bg-muted hover:text-destructive sm:opacity-0 sm:group-hover:opacity-100 focus:opacity-100"
              :aria-label="`移除${entry.item.name}`"
              :tabindex="entry.cycle === 0 ? 0 : -1"
              @click="removeEntry(entry, $event)"
            >
              <X class="size-3.5" />
            </button>
            <div class="pr-5">
              <div class="truncate font-medium">{{ entry.item.name }}</div>
              <div class="mt-0.5 text-xs text-muted-foreground">{{ shortCode(entry.item.indexCode) }} · {{ marketLabel(entry.item.market) }}</div>
            </div>
            <template v-if="entry.item.latestPrice != null">
              <div class="mt-4 text-2xl font-semibold tabular-nums">{{ number(entry.item.latestPrice) }}</div>
              <div class="mt-2 flex items-center gap-3 text-sm tabular-nums" :class="tone(entry.item.changePercent)">
                <span>{{ signedPercent(entry.item.changePercent) }}</span>
                <span>{{ signedNumber(entry.item.changeAmount) }}</span>
              </div>
            </template>
            <div v-else class="mt-6 rounded-lg bg-muted/50 px-3 py-2 text-sm text-muted-foreground">
              {{ entry.item.syncError || '行情暂不可用' }}
            </div>
          </article>
        </div>
      </TransitionGroup>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { Plus, X } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { moveInvestmentIndexToPosition } from '@/lib/investmentIndexOrder'

const props = defineProps({
  indexes: { type: Array, default: () => [] }
})
const emit = defineEmits(['add', 'remove', 'reorder'])

const strip = ref(null)
const dragging = ref(false)
const reorderingCode = ref('')
const reorderingCycle = ref(0)
const displayedIndexes = ref([])
const sideCopies = ref(1)
const focused = ref(false)
const pointerActive = ref(false)
const visible = ref(true)
const reducedMotion = ref(false)
const loopedIndexes = computed(() => {
  const entries = []
  for (let cycle = -sideCopies.value; cycle <= sideCopies.value; cycle++) {
    for (const item of displayedIndexes.value) {
      entries.push({ item, cycle, key: `${cycle}:${item.indexCode}` })
    }
  }
  return entries
})
let dragPointerId = null
let activePointerId = null
let pendingReorderCycle = 0
let pointerStartX = 0
let lastDragX = 0
let lastDragAt = 0
let scrollVelocity = 0
let momentumFrame = null
let holdTimer = null
let pendingReorderCode = ''
let reorderChanged = false
let reorderGeometry = null
let loopSpan = 0
let loopAnchor = 0
let autoFrame = null
let lastAutoAt = null
let autoRemainder = 0
let resizeObserver = null
let intersectionObserver = null
let motionPreference = null
let resetVersion = 0

const FRAME_DURATION = 1000 / 60
const MOMENTUM_FRICTION = 0.94
const MIN_MOMENTUM_VELOCITY = 0.02
const MAX_MOMENTUM_VELOCITY = 2.4
const REORDER_HOLD_DELAY = 200
const SCROLL_DRAG_THRESHOLD = 4
const AUTO_SCROLL_SPEED = 64 / 1000

watch(() => props.indexes, items => {
  if (reorderingCode.value) return
  const previousCodes = displayedIndexes.value.map(item => item.indexCode).join('|')
  displayedIndexes.value = [...items]
  if (previousCodes !== items.map(item => item.indexCode).join('|')) resetLoop()
}, { immediate: true })

onMounted(() => {
  motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)')
  reducedMotion.value = motionPreference.matches
  motionPreference.addEventListener('change', onMotionPreferenceChange)
  resizeObserver = new ResizeObserver(() => resetLoop())
  resizeObserver.observe(strip.value)
  intersectionObserver = new IntersectionObserver(entries => {
    visible.value = entries[0]?.isIntersecting ?? false
    updateAutoScroll()
  })
  intersectionObserver.observe(strip.value)
  document.addEventListener('visibilitychange', updateAutoScroll)
  resetLoop()
})

async function resetLoop() {
  const version = ++resetVersion
  stopAutoScroll()
  loopSpan = 0
  await nextTick()
  if (version !== resetVersion || !strip.value || !displayedIndexes.value.length) return
  const span = measureLoopSpan()
  if (!span) return
  const requiredCopies = Math.max(1, Math.ceil(strip.value.clientWidth / span))
  if (sideCopies.value !== requiredCopies) {
    sideCopies.value = requiredCopies
    await nextTick()
  }
  if (version !== resetVersion || !strip.value) return
  loopSpan = measureLoopSpan()
  const first = strip.value.querySelector('[data-cycle="0"]')
  if (!first || !loopSpan) return
  loopAnchor = first.getBoundingClientRect().left - strip.value.getBoundingClientRect().left + strip.value.scrollLeft
  strip.value.scrollLeft = loopAnchor
  updateAutoScroll()
}

function measureLoopSpan() {
  const first = strip.value?.querySelector('[data-cycle="0"]')
  const next = strip.value?.querySelector('[data-cycle="1"]')
  return first && next ? next.getBoundingClientRect().left - first.getBoundingClientRect().left : 0
}

function wrapScroll() {
  if (!strip.value || !loopSpan || reorderingCode.value) return
  const offset = strip.value.scrollLeft - loopAnchor
  if (Math.abs(offset) < loopSpan / 2) return
  strip.value.scrollLeft = loopAnchor + (((offset + loopSpan / 2) % loopSpan + loopSpan) % loopSpan) - loopSpan / 2
}

function canAutoScroll() {
  return strip.value && loopSpan && displayedIndexes.value.length > 1
    && visible.value && !document.hidden && !reducedMotion.value
    && !focused.value && !pointerActive.value
    && !dragging.value && !reorderingCode.value && momentumFrame == null
}

function updateAutoScroll() {
  if (!canAutoScroll()) {
    stopAutoScroll()
    return
  }
  if (autoFrame == null) autoFrame = window.requestAnimationFrame(autoScroll)
}

function autoScroll(timestamp) {
  autoFrame = null
  if (!canAutoScroll()) return
  if (lastAutoAt != null) {
    autoRemainder += Math.min(timestamp - lastAutoAt, 64) * AUTO_SCROLL_SPEED
    const wholePixels = Math.floor(autoRemainder)
    if (wholePixels > 0) {
      autoRemainder -= wholePixels
      strip.value.scrollLeft += wholePixels
      wrapScroll()
    }
  }
  lastAutoAt = timestamp
  autoFrame = window.requestAnimationFrame(autoScroll)
}

function stopAutoScroll() {
  if (autoFrame != null) window.cancelAnimationFrame(autoFrame)
  autoFrame = null
  lastAutoAt = null
  autoRemainder = 0
}

function pauseOnFocus() {
  focused.value = true
  updateAutoScroll()
}

function resumeAfterFocus(event) {
  if (strip.value?.contains(event.relatedTarget)) return
  focused.value = false
  updateAutoScroll()
}

function onMotionPreferenceChange(event) {
  reducedMotion.value = event.matches
  updateAutoScroll()
}

function removeEntry(entry, event) {
  if (entry.cycle !== 0) event.currentTarget.blur()
  emit('remove', entry.item)
}

function startDrag(event) {
  activePointerId = event.pointerId
  pointerActive.value = true
  updateAutoScroll()
  if (event.pointerType !== 'mouse' || event.button !== 0 || event.target.closest?.('button')) return
  cancelMomentum()
  event.preventDefault()
  dragPointerId = event.pointerId
  pointerStartX = event.clientX
  lastDragX = event.clientX
  lastDragAt = event.timeStamp
  scrollVelocity = 0
  strip.value.setPointerCapture(event.pointerId)
  const target = event.target.closest?.('[data-index-code]')
  pendingReorderCode = target?.dataset.indexCode || ''
  pendingReorderCycle = Number(target?.dataset.cycle || 0)
  if (pendingReorderCode) {
    const pointerId = event.pointerId
    holdTimer = window.setTimeout(() => {
      if (dragPointerId !== pointerId || dragging.value || !pendingReorderCode) return
      reorderGeometry = measureReorderGeometry(pendingReorderCycle)
      if (!reorderGeometry) return
      reorderingCode.value = pendingReorderCode
      reorderingCycle.value = pendingReorderCycle
      pendingReorderCode = ''
      reorderChanged = false
    }, REORDER_HOLD_DELAY)
  }
}

function drag(event) {
  if (event.pointerId !== dragPointerId) return
  event.preventDefault()
  if (reorderingCode.value) {
    reorderAtPointer(event)
    return
  }
  if (!dragging.value && Math.abs(event.clientX - pointerStartX) >= SCROLL_DRAG_THRESHOLD) {
    cancelHold()
    pendingReorderCode = ''
    dragging.value = true
  }
  if (!dragging.value) return
  const elapsed = Math.max(event.timeStamp - lastDragAt, 1)
  const delta = event.clientX - lastDragX
  strip.value.scrollLeft -= delta
  wrapScroll()
  const currentVelocity = -delta / elapsed
  scrollVelocity = Math.max(
    -MAX_MOMENTUM_VELOCITY,
    Math.min(MAX_MOMENTUM_VELOCITY, scrollVelocity * 0.55 + currentVelocity * 0.45)
  )
  lastDragX = event.clientX
  lastDragAt = event.timeStamp
}

function stopDrag(event) {
  if (event.pointerId === activePointerId) {
    activePointerId = null
    pointerActive.value = false
  }
  if (event.pointerId !== dragPointerId) {
    updateAutoScroll()
    return
  }
  cancelHold()
  const wasReordering = Boolean(reorderingCode.value)
  const shouldGlide = dragging.value && event.type === 'pointerup' && event.timeStamp - lastDragAt < 80
  if (strip.value?.hasPointerCapture(event.pointerId)) {
    strip.value.releasePointerCapture(event.pointerId)
  }
  dragging.value = false
  dragPointerId = null
  pendingReorderCode = ''
  if (wasReordering) {
    reorderingCode.value = ''
    reorderingCycle.value = 0
    reorderGeometry = null
    wrapScroll()
    scrollVelocity = 0
    if (event.type === 'pointerup' && reorderChanged) {
      emit('reorder', displayedIndexes.value.map(item => item.indexCode))
    } else {
      displayedIndexes.value = [...props.indexes]
    }
    reorderChanged = false
  } else if (shouldGlide) {
    startMomentum()
  } else {
    scrollVelocity = 0
  }
  updateAutoScroll()
}

function reorderAtPointer(event) {
  if (!strip.value || !reorderGeometry) return
  const bounds = strip.value.getBoundingClientRect()
  if (event.clientX < bounds.left + 48) strip.value.scrollLeft -= 12
  else if (event.clientX > bounds.right - 48) strip.value.scrollLeft += 12
  const contentX = event.clientX - bounds.left + strip.value.scrollLeft
  const targetIndex = Math.max(0, Math.min(
    reorderGeometry.count - 1,
    Math.round((contentX - reorderGeometry.firstCenter) / reorderGeometry.step)
  ))
  const currentIndex = displayedIndexes.value.findIndex(
    item => item.indexCode === reorderingCode.value
  )
  if (currentIndex === targetIndex) return
  displayedIndexes.value = moveInvestmentIndexToPosition(
    displayedIndexes.value,
    reorderingCode.value,
    targetIndex
  )
  reorderChanged = true
}

function measureReorderGeometry(cycle) {
  if (!strip.value) return null
  const cards = [...strip.value.querySelectorAll(`[data-cycle="${cycle}"]`)]
  if (!cards.length) return null
  const stripBounds = strip.value.getBoundingClientRect()
  const firstBounds = cards[0].getBoundingClientRect()
  const firstCenter = firstBounds.left + firstBounds.width / 2
    - stripBounds.left + strip.value.scrollLeft
  let step = firstBounds.width
  if (cards.length > 1) {
    const secondBounds = cards[1].getBoundingClientRect()
    step = secondBounds.left - firstBounds.left
  }
  if (step <= 0) return null
  return { count: cards.length, firstCenter, step }
}

function startMomentum() {
  if (Math.abs(scrollVelocity) < MIN_MOMENTUM_VELOCITY) return
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
    scrollVelocity = 0
    return
  }
  let previousTimestamp = null
  const step = timestamp => {
    if (!strip.value) return
    if (previousTimestamp == null) {
      previousTimestamp = timestamp
      momentumFrame = window.requestAnimationFrame(step)
      return
    }
    const elapsed = Math.min(timestamp - previousTimestamp, FRAME_DURATION * 2)
    const previousScrollLeft = strip.value.scrollLeft
    strip.value.scrollLeft += scrollVelocity * elapsed
    scrollVelocity *= Math.pow(MOMENTUM_FRICTION, elapsed / FRAME_DURATION)
    previousTimestamp = timestamp
    const reachedBoundary = strip.value.scrollLeft === previousScrollLeft
    if (!reachedBoundary && Math.abs(scrollVelocity) >= MIN_MOMENTUM_VELOCITY) {
      momentumFrame = window.requestAnimationFrame(step)
    } else {
      momentumFrame = null
      scrollVelocity = 0
      updateAutoScroll()
    }
  }
  momentumFrame = window.requestAnimationFrame(step)
}

function cancelMomentum() {
  if (momentumFrame != null) {
    window.cancelAnimationFrame(momentumFrame)
    momentumFrame = null
  }
  scrollVelocity = 0
}

function cancelHold() {
  if (holdTimer != null) {
    window.clearTimeout(holdTimer)
    holdTimer = null
  }
}

onUnmounted(() => {
  resetVersion++
  cancelHold()
  cancelMomentum()
  stopAutoScroll()
  resizeObserver?.disconnect()
  intersectionObserver?.disconnect()
  motionPreference?.removeEventListener('change', onMotionPreferenceChange)
  document.removeEventListener('visibilitychange', updateAutoScroll)
})

function shortCode(value) {
  return String(value || '').split(':').at(-1) || '-'
}

function marketLabel(value) {
  return ({ CN: '中国', US: '美国', GLOBAL: '全球' })[value] || value || '全球'
}

function number(value) {
  return value == null ? '-' : new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(Number(value))
}

function signedNumber(value) {
  if (value == null) return '-'
  const numeric = Number(value)
  return `${numeric > 0 ? '+' : ''}${number(numeric)}`
}

function signedPercent(value) {
  if (value == null) return '-'
  const numeric = Number(value)
  return `${numeric > 0 ? '+' : ''}${numeric.toFixed(2)}%`
}

function tone(value) {
  const numeric = Number(value || 0)
  return numeric > 0 ? 'text-emerald-500' : numeric < 0 ? 'text-destructive' : 'text-muted-foreground'
}
</script>

<style scoped>
.index-strip {
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.index-strip::-webkit-scrollbar {
  display: none;
}

.index-order-move {
  transition: transform 220ms cubic-bezier(0.22, 1, 0.36, 1);
  will-change: transform;
}

@media (prefers-reduced-motion: reduce) {
  .index-order-move {
    transition-duration: 0.01ms;
  }
}

</style>
