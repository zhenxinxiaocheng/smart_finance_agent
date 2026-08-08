<template>
  <div class="mx-auto max-w-[1440px] space-y-5">
    <header class="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
      <div>
        <h1 class="text-2xl font-semibold tracking-tight">我的投资</h1>
        <p class="mt-1 text-sm text-muted-foreground">输入代码添加股票或基金，持仓信息可以随后编辑。</p>
      </div>
      <div class="flex flex-wrap items-center gap-4 text-sm">
        <Button variant="outline" @click="horizonDialogOpen = true"><SlidersHorizontal />周期偏好</Button>
        <div><span class="text-muted-foreground">总市值</span><strong class="ml-2 text-base">{{ money(totalMarketValue) }}</strong></div>
        <div><span class="text-muted-foreground">持仓盈亏</span><strong class="ml-2 text-base" :class="tone(totalPnl)">{{ signedMoney(totalPnl) }}</strong></div>
      </div>
    </header>

    <InvestmentAddBar @added="refreshAfterAssetChange" />

    <section class="space-y-3">
      <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div><h2 class="font-semibold">资产列表</h2><p class="text-sm text-muted-foreground">{{ assets.length }} 个标的</p></div>
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
      />
    </section>

    <InvestmentAssetDrawer v-model:open="drawerOpen" :asset="selectedAsset" @saved="refreshAfterAssetChange" />

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
import InvestmentAddBar from '@/components/investment/InvestmentAddBar.vue'
import InvestmentAssetDrawer from '@/components/investment/InvestmentAssetDrawer.vue'
import InvestmentAssetTable from '@/components/investment/InvestmentAssetTable.vue'
import HorizonProfileDialog from '@/components/investment/HorizonProfileDialog.vue'
import { feedback } from '@/lib/feedback'
import { createPrioritizedRefreshRunner, startInvestmentRealtimePolling } from '@/lib/investmentRealtime'
import {
  deleteInvestmentAssetAPI,
  getInvestmentHorizonProfileAPI,
  listInvestmentAssetsAPI,
  refreshInvestmentAssetsAPI,
  updateInvestmentHorizonProfileAPI,
} from '@/api/investment'

const assets = ref([])
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

function refreshOnFocus() { refreshAssets(true, true).catch(() => loadAssets().catch(() => {})) }

function refreshAfterAssetChange() { refreshAssets(true, true, true).catch(() => {}) }

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
