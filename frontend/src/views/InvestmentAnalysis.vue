<template>
  <div class="mx-auto max-w-[1440px] space-y-5">
    <header class="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
      <div>
        <h1 class="text-2xl font-semibold tracking-tight">我的投资</h1>
        <p class="mt-1 text-sm text-muted-foreground">管理股票、基金持仓，并关注主要市场指数。</p>
      </div>
      <div class="flex flex-wrap items-center gap-4 text-sm">
        <Button variant="outline" @click="horizonDialogOpen = true"><SlidersHorizontal />周期偏好</Button>
        <div><span class="text-muted-foreground">总市值</span><strong class="ml-2 text-base">{{ money(totalMarketValue) }}</strong></div>
        <div><span class="text-muted-foreground">持仓盈亏</span><strong class="ml-2 text-base" :class="tone(totalPnl)">{{ signedMoney(totalPnl) }}</strong></div>
      </div>
    </header>

    <InvestmentIndexStrip
      :indexes="indexes"
      @add="openIndexWatchlist"
      @remove="removeIndex"
      @reorder="reorderIndexes"
    />

    <section class="space-y-3">
      <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <h2 class="font-semibold">资产列表</h2>
        <div class="relative w-full sm:w-72">
          <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input v-model="keyword" class="pl-9" placeholder="搜索代码或名称" />
        </div>
      </div>
      <InvestmentAssetTable
        :assets="filteredAssets"
        :loading="loading"
        @select="openAsset"
        @edit="openEditor"
        @remove="removeAsset"
        @add="openAssetWatchlist"
      />
    </section>

    <InvestmentAssetDrawer v-model:open="drawerOpen" :asset="selectedAsset" @saved="refreshAfterAssetChange" />

    <InvestmentWatchlistDialog
      v-model:open="watchlistDialogOpen"
      :initial-type="watchlistInitialType"
      :existing-index-codes="indexes.map(item => item.indexCode)"
      @asset-added="refreshAfterAssetChange"
      @index-added="refreshAfterIndexChange"
    />

    <HorizonProfileDialog
      v-model:open="horizonDialogOpen"
      :profile="horizonProfile"
      :saving="horizonSaving"
      scope="global"
      @save="saveHorizonProfile"
    />

    <Dialog v-model:open="deleteDialogOpen">
      <DialogContent class="sm:max-w-[420px]">
        <DialogHeader>
          <DialogTitle>删除资产</DialogTitle>
          <DialogDescription>删除后会从资产列表移除，历史流水仍会保留。</DialogDescription>
        </DialogHeader>
        <p class="rounded-lg border bg-muted/40 p-3 text-sm font-medium">
          {{ deletingAsset?.name }}
        </p>
        <DialogFooter>
          <Button variant="outline" :disabled="deleteSaving" @click="deleteDialogOpen = false">取消</Button>
          <Button variant="destructive" :disabled="deleteSaving" @click="confirmRemoveAsset">
            <Loader2 v-if="deleteSaving" data-icon="inline-start" class="animate-spin" />
            确认删除
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Loader2, Search, SlidersHorizontal } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import InvestmentAssetDrawer from '@/components/investment/InvestmentAssetDrawer.vue'
import InvestmentAssetTable from '@/components/investment/InvestmentAssetTable.vue'
import InvestmentIndexStrip from '@/components/investment/InvestmentIndexStrip.vue'
import InvestmentWatchlistDialog from '@/components/investment/InvestmentWatchlistDialog.vue'
import HorizonProfileDialog from '@/components/investment/HorizonProfileDialog.vue'
import { feedback } from '@/lib/feedback'
import { readInvestmentIndexCache, writeInvestmentIndexCache } from '@/lib/investmentIndexCache'
import { createPrioritizedRefreshRunner, startInvestmentRealtimePolling } from '@/lib/investmentRealtime'
import { useAuthStore } from '@/stores/auth'
import {
  deleteInvestmentAssetAPI,
  deleteInvestmentIndexAPI,
  getInvestmentHorizonProfileAPI,
  listInvestmentAssetsAPI,
  listInvestmentIndexesAPI,
  reorderInvestmentIndexesAPI,
  refreshInvestmentAssetsAPI,
  updateInvestmentHorizonProfileAPI,
} from '@/api/investment'

const assets = ref([])
const authStore = useAuthStore()
const indexCacheAccount = computed(() => authStore.user?.id || authStore.user?.username || '')
const indexCacheStorage = typeof window === 'undefined' ? null : window.sessionStorage
const indexes = ref(readInvestmentIndexCache(indexCacheStorage, indexCacheAccount.value))
const loading = ref(false)
const keyword = ref('')
const selectedAsset = ref(null)
const drawerOpen = ref(false)
const deleteDialogOpen = ref(false)
const deleteSaving = ref(false)
const deletingAsset = ref(null)
const horizonDialogOpen = ref(false)
const horizonSaving = ref(false)
const horizonProfile = ref({ settings: [] })
const watchlistDialogOpen = ref(false)
const watchlistInitialType = ref('STOCK')
let stopRealtimePolling = null
let disposed = false
const router = useRouter()

const filteredAssets = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return assets.value
  return assets.value.filter(item => item.code?.toLowerCase().includes(value) || item.name?.toLowerCase().includes(value))
})
const totalMarketValue = computed(() => assets.value.reduce((sum, item) => sum + Number(item.marketValueCny || 0), 0))
const totalPnl = computed(() => assets.value.reduce((sum, item) => sum + Number(item.unrealizedPnlCny || 0), 0))

const refreshAssets = createPrioritizedRefreshRunner(async (force = false, silent = false) => {
  if (!silent) loading.value = true
  try {
    const response = await refreshInvestmentAssetsAPI(force)
    if (!disposed) applyAssets(response.data)
    return response.data
  } finally {
    if (!silent && !disposed) loading.value = false
  }
})

onMounted(() => {
  loadAssets().catch(() => {})
  loadIndexes().catch(() => {})
  loadHorizonProfile()
  stopRealtimePolling = startInvestmentRealtimePolling(force => refreshAssets(force, true))
  window.addEventListener('focus', refreshOnFocus)
})

onUnmounted(() => {
  disposed = true
  refreshAssets.dispose()
  stopRealtimePolling?.()
  window.removeEventListener('focus', refreshOnFocus)
})

async function loadAssets(silent = false) {
  if (disposed) return
  if (!silent) loading.value = true
  try {
    const response = await listInvestmentAssetsAPI()
    if (!disposed) applyAssets(response.data)
  } finally {
    if (!silent && !disposed) loading.value = false
  }
}

async function loadIndexes() {
  if (disposed) return
  const response = await listInvestmentIndexesAPI()
  if (!disposed) applyIndexes(response.data)
}

function applyIndexes(items) {
  indexes.value = items || []
  writeInvestmentIndexCache(indexCacheStorage, indexCacheAccount.value, indexes.value)
}

function applyAssets(items) {
  assets.value = items || []
  if (selectedAsset.value) selectedAsset.value = assets.value.find(item => item.id === selectedAsset.value.id) || null
}

async function loadHorizonProfile() {
  const response = await getInvestmentHorizonProfileAPI()
  horizonProfile.value = response.data || { settings: [] }
}

async function saveHorizonProfile(payload) {
  if (horizonSaving.value) return
  horizonSaving.value = true
  try {
    const response = await updateInvestmentHorizonProfileAPI(payload)
    horizonProfile.value = response.data
    horizonDialogOpen.value = false
    feedback.success('全局周期偏好已保存')
  } finally {
    horizonSaving.value = false
  }
}

function refreshOnFocus() {
  refreshAssets(true, true).catch(() => loadAssets().catch(() => {}))
  loadIndexes().catch(() => {})
}

function refreshAfterAssetChange() { refreshAssets(true, true, true).catch(() => {}) }

function refreshAfterIndexChange() { loadIndexes().catch(() => {}) }

function openAssetWatchlist() {
  watchlistInitialType.value = 'STOCK'
  watchlistDialogOpen.value = true
}

function openIndexWatchlist() {
  watchlistInitialType.value = 'INDEX'
  watchlistDialogOpen.value = true
}

async function reorderIndexes(indexCodes) {
  const previousIndexes = [...indexes.value]
  const indexesByCode = new Map(previousIndexes.map(item => [item.indexCode, item]))
  const reordered = indexCodes.map(code => indexesByCode.get(code)).filter(Boolean)
  if (reordered.length !== previousIndexes.length) return
  applyIndexes(reordered)
  try {
    await reorderInvestmentIndexesAPI(indexCodes)
  } catch (error) {
    applyIndexes(previousIndexes)
    feedback.error(error.response?.data?.message || '指数排序保存失败')
  }
}

async function removeIndex(item) {
  if (!item?.id) return
  await deleteInvestmentIndexAPI(item.id)
  applyIndexes(indexes.value.filter(index => index.id !== item.id))
  feedback.success(`已移除 ${item.name}`)
}

function openAsset(asset) { router.push(`/stocks/${asset.id}`) }

function openEditor(asset) { selectedAsset.value = asset; drawerOpen.value = true }

function removeAsset(asset) {
  deletingAsset.value = asset
  deleteDialogOpen.value = true
}

async function confirmRemoveAsset() {
  if (!deletingAsset.value || deleteSaving.value) return
  deleteSaving.value = true
  try {
    await deleteInvestmentAssetAPI(deletingAsset.value.id)
    deleteDialogOpen.value = false
    deletingAsset.value = null
    feedback.success('资产已移出列表')
    await refreshAssets(false, false, true)
  } finally {
    deleteSaving.value = false
  }
}

function money(value) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 2 }).format(Number(value || 0)) }
function signedMoney(value) { const number = Number(value || 0); return `${number > 0 ? '+' : ''}${money(number)}` }
function tone(value) { const number = Number(value || 0); return number > 0 ? 'text-emerald-500' : number < 0 ? 'text-destructive' : '' }
</script>
