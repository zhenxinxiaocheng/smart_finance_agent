<script setup>
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { ArrowLeft } from '@lucide/vue'
import { returnToSource } from './navigation.js'
const props = defineProps({ fallback: { type: String, default: '/quant' } })
const route = useRoute(), router = useRouter()
</script>
<template><Button variant="ghost" size="sm" @click="returnToSource(router, route, props.fallback)"><ArrowLeft />返回</Button></template>
