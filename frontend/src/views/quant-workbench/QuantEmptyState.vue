<script setup>
defineProps({ title: String, description: String })
</script>
<template>
  <div class="quant-empty"><h3>{{ title }}</h3><p v-if="description" class="muted">{{ description }}</p><div v-if="$slots.default" class="mt-4"><slot /></div></div>
</template>
