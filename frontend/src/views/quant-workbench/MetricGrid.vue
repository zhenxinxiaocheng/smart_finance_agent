<script setup>
defineProps({ items: { type: Array, default: () => [] }, primary: Boolean })
</script>
<template>
  <dl class="quant-metrics" :class="{ 'quant-metrics-primary': primary }">
    <div v-for="item in items" :key="item.key || item.label" class="min-w-0">
      <dt class="muted">{{ item.label }}</dt>
      <dd class="mt-1.5 break-words font-semibold tabular-nums">{{ item.value ?? '—' }}</dd>
    </div>
  </dl>
</template>
