<script setup>
import { Badge } from '@/components/ui/badge'
import { label } from './shared'
defineProps({ status: String })
</script>
<template>
  <Badge v-if="status" variant="outline" class="quant-status" :class="{ 'text-destructive border-destructive/30': ['FAILED','UNQUALIFIED','ATTENTION_REQUIRED','REJECTED'].includes(status) }">{{ label(status) }}</Badge>
</template>
