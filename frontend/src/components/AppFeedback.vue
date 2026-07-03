<template>
  <div class="pointer-events-none fixed right-4 top-4 z-50 flex w-[320px] max-w-[calc(100vw-2rem)] flex-col gap-2">
    <div
      v-for="item in items"
      :key="item.id"
      class="pointer-events-auto rounded-xl border bg-background p-3 text-sm shadow-lg"
      :class="itemClass(item.type)"
    >
      <div class="font-medium">{{ item.title }}</div>
      <div v-if="item.message" class="mt-1 text-muted-foreground">{{ item.message }}</div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'

const items = ref([])
let nextId = 1

function itemClass(type) {
  if (type === 'error') return 'border-destructive/30'
  if (type === 'warning') return 'border-amber-300'
  return 'border-border'
}

function onFeedback(event) {
  const payload = event.detail || {}
  const item = {
    id: nextId++,
    type: payload.type || 'info',
    title: payload.title || payload.message || '提示',
    message: payload.title ? payload.message : ''
  }
  items.value.push(item)
  window.setTimeout(() => {
    items.value = items.value.filter(current => current.id !== item.id)
  }, payload.duration || 2600)
}

onMounted(() => window.addEventListener('app-feedback', onFeedback))
onUnmounted(() => window.removeEventListener('app-feedback', onFeedback))
</script>
