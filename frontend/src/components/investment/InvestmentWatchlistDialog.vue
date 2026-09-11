<template>
  <Dialog :open="open" @update:open="emit('update:open', $event)">
    <DialogContent class="sm:max-w-[620px]">
      <DialogHeader>
        <DialogTitle>添加自选</DialogTitle>
        <DialogDescription class="sr-only">添加股票、基金或指数</DialogDescription>
      </DialogHeader>

      <Tabs v-model="activeType">
        <TabsList class="grid h-10 w-full grid-cols-3">
          <TabsTrigger value="STOCK">股票</TabsTrigger>
          <TabsTrigger value="MUTUAL_FUND">基金</TabsTrigger>
          <TabsTrigger value="INDEX">指数</TabsTrigger>
        </TabsList>

        <TabsContent value="STOCK" class="pt-3">
          <InvestmentAssetAddForm
            product-type="STOCK"
            :submitting="assetSubmitting"
            :error-message="assetError"
            @submit="addAsset"
          />
        </TabsContent>
        <TabsContent value="MUTUAL_FUND" class="pt-3">
          <InvestmentAssetAddForm
            product-type="MUTUAL_FUND"
            :submitting="assetSubmitting"
            :error-message="assetError"
            @submit="addAsset"
          />
        </TabsContent>
        <TabsContent value="INDEX" class="space-y-3 pt-3">
          <div class="flex gap-2">
            <div class="relative min-w-0 flex-1">
              <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                v-model="indexKeyword"
                class="pl-9"
                placeholder="搜索指数名称或代码，如沪深300、NDX"
                @keyup.enter="searchIndexes"
              />
            </div>
            <Button :disabled="indexSearching || !indexKeyword.trim()" @click="searchIndexes">
              <LoaderCircle v-if="indexSearching" class="animate-spin" data-icon="inline-start" />
              搜索
            </Button>
          </div>
          <p v-if="indexError" class="text-sm text-destructive">{{ indexError }}</p>
          <div v-if="indexResults.length" class="max-h-72 divide-y overflow-y-auto rounded-xl border">
            <div v-for="item in indexResults" :key="item.indexCode" class="flex items-center gap-3 p-3">
              <div class="min-w-0 flex-1">
                <div class="truncate font-medium">{{ item.name }}</div>
                <div class="mt-1 text-xs text-muted-foreground">{{ item.indexCode }} · {{ item.market }}</div>
              </div>
              <div class="text-right text-sm tabular-nums">
                <div>{{ number(item.latestPrice) }}</div>
                <div :class="tone(item.changePercent)">{{ signedPercent(item.changePercent) }}</div>
              </div>
              <Button
                size="sm"
                variant="outline"
                :disabled="isExisting(item.indexCode) || addingIndexCode === item.indexCode"
                @click="addIndex(item)"
              >
                <LoaderCircle v-if="addingIndexCode === item.indexCode" class="animate-spin" />
                {{ isExisting(item.indexCode) ? '已展示' : '添加' }}
              </Button>
            </div>
          </div>
          <div v-else-if="indexSearched && !indexSearching && !indexError" class="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
            没有找到匹配指数，请尝试简称或代码。
          </div>
        </TabsContent>
      </Tabs>
    </DialogContent>
  </Dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { LoaderCircle, Search } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import InvestmentAssetAddForm from '@/components/investment/InvestmentAssetAddForm.vue'
import { feedback } from '@/lib/feedback'
import {
  addInvestmentIndexAPI,
  createInvestmentAssetAPI,
  searchInvestmentIndexesAPI
} from '@/api/investment'

const props = defineProps({
  open: Boolean,
  initialType: { type: String, default: 'STOCK' },
  existingIndexCodes: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:open', 'asset-added', 'index-added'])

const activeType = ref('STOCK')
const assetSubmitting = ref(false)
const assetError = ref('')
const indexKeyword = ref('')
const indexSearching = ref(false)
const indexSearched = ref(false)
const indexResults = ref([])
const indexError = ref('')
const addingIndexCode = ref('')
const localAddedCodes = ref([])

watch(() => props.open, open => {
  if (!open) return
  activeType.value = props.initialType === 'INDEX' ? 'INDEX' : 'STOCK'
  assetError.value = ''
  indexError.value = ''
  localAddedCodes.value = localAddedCodes.value.filter(code => props.existingIndexCodes.includes(code))
})

async function addAsset({ productType, code }) {
  assetSubmitting.value = true
  assetError.value = ''
  try {
    const response = await createInvestmentAssetAPI({ productType, code })
    feedback.success(`已添加 ${response.data?.name || code}`)
    emit('asset-added')
    emit('update:open', false)
  } catch (error) {
    assetError.value = errorMessage(error, '代码识别失败')
  } finally {
    assetSubmitting.value = false
  }
}

async function searchIndexes() {
  const keyword = indexKeyword.value.trim()
  if (!keyword || indexSearching.value) return
  indexSearching.value = true
  indexSearched.value = true
  indexError.value = ''
  try {
    const response = await searchInvestmentIndexesAPI(keyword)
    indexResults.value = response.data || []
  } catch (error) {
    indexResults.value = []
    indexError.value = errorMessage(error, '指数搜索失败')
  } finally {
    indexSearching.value = false
  }
}

async function addIndex(item) {
  if (isExisting(item.indexCode) || addingIndexCode.value) return
  addingIndexCode.value = item.indexCode
  indexError.value = ''
  try {
    await addInvestmentIndexAPI({ indexCode: item.indexCode })
    localAddedCodes.value.push(item.indexCode)
    feedback.success(`已添加 ${item.name}`)
    emit('index-added')
  } catch (error) {
    indexError.value = errorMessage(error, '指数添加失败')
  } finally {
    addingIndexCode.value = ''
  }
}

function isExisting(code) {
  return props.existingIndexCodes.includes(code) || localAddedCodes.value.includes(code)
}

function number(value) {
  return value == null ? '-' : new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(Number(value))
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

function errorMessage(error, fallback) {
  return error.response?.data?.message || error.response?.data?.detail || error.message || fallback
}
</script>
