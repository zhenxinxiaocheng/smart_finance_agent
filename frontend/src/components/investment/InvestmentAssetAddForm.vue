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
          <div class="mt-1 text-xs text-muted-foreground">{{ item.code }} · {{ item.market }} · {{ item.productType === 'STOCK' ? '股票' : '基金' }}</div>
        </div>
        <Button size="sm" variant="outline" :disabled="submitting || isExisting(item)" @click="submit(item)">
          <LoaderCircle v-if="submitting && addingCode === item.code" class="animate-spin" />
          {{ isExisting(item) ? '已添加' : '添加' }}
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
  productType: { type: String, default: '' },
  submitting: Boolean,
  existingCodes: { type: Array, default: () => [] },
  existingAssets: { type: Array, default: () => [] },
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
const typeLabel = computed(() => props.productType ? (props.productType === 'STOCK' ? '股票' : '基金') : '股票或基金')
const placeholder = computed(() => `搜索${typeLabel.value}名称或代码`)

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
    if (nextPage === 1 && /^\d{6}$/.test(value)) {
      const types = (productType ? [productType] : ['STOCK', 'MUTUAL_FUND']).filter(type =>
        !items.some(item => item.code === value && normalizeType(item.productType) === type))
      const resolutions = await Promise.allSettled(types.map(type =>
        resolveInvestmentAssetAPI({ productType: type, code: value })))
      if (current !== requestId) return
      const identified = resolutions.filter(result => result.status === 'fulfilled' && result.value.data)
        .map(result => result.value.data)
      if (!items.length && resolutions.length && resolutions.every(result => result.status === 'rejected')) throw new Error('resolution unavailable')
      items = [...items, ...identified]
      count += identified.length
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

function normalizeType(type) {
  return type === 'FUND' ? 'MUTUAL_FUND' : type
}

function isExisting(item) {
  return props.existingAssets.some(asset => asset.productId != null && item.id != null
    ? String(asset.productId) === String(item.id)
    : asset.code === item.code && asset.market === item.market &&
      normalizeType(asset.productType) === normalizeType(item.productType)) ||
    (Boolean(props.productType) && props.existingCodes.includes(item.code))
}

function submit(item) {
  if (props.submitting || isExisting(item)) return
  addingCode.value = item.code
  emit('submit', { productId: item.id, productType: normalizeType(item.productType),
    market: item.market, code: item.code })
}
</script>
