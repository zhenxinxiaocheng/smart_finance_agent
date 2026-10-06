<template>
  <div class="space-y-3">
    <div class="flex gap-2">
      <div class="relative min-w-0 flex-1">
        <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          v-model="keyword"
          class="pl-9"
          :placeholder="placeholder"
          :aria-label="`搜索${typeLabel}名称或代码`"
          @keyup.enter="search()"
        />
      </div>
      <Button :disabled="searching || submitting || !keyword.trim()" @click="search()">
        <LoaderCircle v-if="searching" class="animate-spin" data-icon="inline-start" />
        搜索
      </Button>
    </div>
    <p v-if="searchError || errorMessage" role="alert" class="text-sm text-destructive">{{ searchError || errorMessage }}</p>
    <div v-if="results.length" class="max-h-72 divide-y overflow-y-auto rounded-xl border">
      <div v-for="item in results" :key="item.id ?? `${item.market}:${item.code}`" class="flex items-center gap-3 p-3">
        <div class="min-w-0 flex-1">
          <div class="truncate font-medium" :title="item.name">{{ item.name }}</div>
          <div class="mt-1 text-xs text-muted-foreground">{{ item.code }} · {{ item.market }}</div>
        </div>
        <Button size="sm" variant="outline" :disabled="submitting || isExisting(item.code)" @click="submit(item)">
          <LoaderCircle v-if="submitting && addingCode === item.code" class="animate-spin" />
          {{ isExisting(item.code) ? '已添加' : '添加' }}
        </Button>
      </div>
      <div v-if="results.length < total" class="p-2 text-center">
        <Button size="sm" variant="ghost" :disabled="searching || submitting" @click="search(page + 1)">
          加载更多
        </Button>
      </div>
    </div>
    <div v-else-if="searched && !searching && !searchError" class="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
      没有找到匹配{{ typeLabel }}，请尝试简称或代码。
    </div>
  </div>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { LoaderCircle, Search } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { resolveInvestmentAssetAPI, searchInvestmentAssetProductsAPI } from '@/api/investment'

const props = defineProps({
  productType: { type: String, required: true },
  submitting: Boolean,
  existingCodes: { type: Array, default: () => [] },
  errorMessage: { type: String, default: '' }
})
const emit = defineEmits(['submit'])
const keyword = ref('')
const searching = ref(false)
const searched = ref(false)
const results = ref([])
const searchError = ref('')
const page = ref(1)
const total = ref(0)
const addingCode = ref('')
let requestId = 0
const typeLabel = computed(() => props.productType === 'STOCK' ? '股票' : '基金')
const placeholder = computed(() => props.productType === 'STOCK'
  ? '搜索股票名称或代码，如贵州茅台、600519'
  : '搜索基金名称或代码，如易方达、010736')

watch(keyword, resetSearch, { flush: 'sync' })
watch(() => props.productType, () => { keyword.value = ''; resetSearch() }, { flush: 'sync' })
onUnmounted(() => { requestId++ })

function resetSearch() {
  requestId++
  searching.value = false
  searched.value = false
  results.value = []
  searchError.value = ''
  page.value = 1
  total.value = 0
}

async function search(nextPage = 1) {
  const value = keyword.value.trim()
  if (!value || searching.value || props.submitting) return
  const current = ++requestId
  const productType = props.productType
  searching.value = true
  searched.value = true
  searchError.value = ''
  if (nextPage === 1) results.value = []
  try {
    const response = await searchInvestmentAssetProductsAPI({ keyword: value, productType, page: nextPage })
    if (current !== requestId) return
    let items = response.data?.items || []
    let count = response.data?.total || 0
    // 目录首次准备期间仍保留原有的精确代码识别能力。
    if (nextPage === 1 && !items.length && /^\d{6}$/.test(value)) {
      const resolved = await resolveInvestmentAssetAPI({ productType, code: value })
      if (current !== requestId) return
      items = resolved.data ? [resolved.data] : []
      count = items.length
    }
    results.value = nextPage === 1 ? items : [...results.value, ...items]
    total.value = count
    page.value = nextPage
  } catch {
    if (current === requestId) searchError.value = `${typeLabel.value}搜索暂不可用，请稍后重试。`
  } finally {
    if (current === requestId) searching.value = false
  }
}

function isExisting(code) {
  return props.existingCodes.includes(code)
}

function submit(item) {
  if (props.submitting || isExisting(item.code)) return
  addingCode.value = item.code
  emit('submit', { productType: props.productType, code: item.code })
}
</script>
