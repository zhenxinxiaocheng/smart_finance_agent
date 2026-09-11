<script setup>
defineProps({ title: String, description: String })
</script>
<template>
  <header class="quant-page-header">
    <div v-if="$slots.back" class="quant-back"><slot name="back" /></div>
    <div class="quant-heading-row">
      <div class="min-w-0">
        <div class="flex flex-wrap items-center gap-3"><h2 class="!mb-0 break-words">{{ title }}</h2><slot name="badges" /></div>
        <p v-if="description" class="muted mt-2">{{ description }}</p>
        <div v-if="$slots.meta" class="muted mt-2 flex flex-wrap gap-x-4 gap-y-1"><slot name="meta" /></div>
      </div>
      <div v-if="$slots.actions" class="quant-actions"><slot name="actions" /></div>
    </div>
  </header>
</template>
