<template>
  <Sheet :open="open" @update:open="$emit('update:open', $event)">
    <SheetContent class="w-full overflow-y-auto sm:max-w-[520px]">
      <SheetHeader v-if="asset">
        <SheetTitle>{{ asset.name }}</SheetTitle>
        <SheetDescription>{{ asset.market }} · {{ asset.code }} · {{ asset.productType === 'MUTUAL_FUND' ? '基金' : '股票' }}</SheetDescription>
      </SheetHeader>
      <template v-if="asset">
        <div class="grid grid-cols-2 gap-3">
          <div class="metric"><span>最新价</span><strong>{{ originalMoney(asset.latestPrice, asset.currency) }}</strong></div>
          <div class="metric"><span>{{ asset.productType === 'MUTUAL_FUND' ? '日涨幅' : '今日涨跌' }}</span><strong :class="tone(asset.changePercent)">{{ signedPercent(asset.changePercent) }}</strong></div>
          <div class="metric"><span>人民币市值</span><strong>{{ money(asset.marketValueCny) }}</strong></div>
          <div class="metric"><span>持仓盈亏</span><strong :class="tone(asset.unrealizedPnlCny)">{{ signedMoney(asset.unrealizedPnlCny) }} <small>{{ signedPercent(asset.holdingReturnPercent) }}</small></strong></div>
        </div>
        <div class="space-y-3 rounded-xl border p-4">
          <h3 class="font-medium">{{ asset.productType === 'MUTUAL_FUND' ? '净值信息' : '今日行情' }}</h3>
          <dl v-if="asset.productType === 'MUTUAL_FUND'" class="quote-grid">
            <div><dt>前一净值</dt><dd>{{ originalMoney(asset.previousClose, asset.currency) }}</dd></div>
            <div><dt>净值变动</dt><dd :class="tone(asset.changeAmount)">{{ signedOriginalMoney(asset.changeAmount, asset.currency) }}</dd></div>
            <div><dt>日涨幅</dt><dd :class="tone(asset.changePercent)">{{ signedPercent(asset.changePercent) }}</dd></div>
            <div><dt>净值日期</dt><dd>{{ asset.dataDate || '-' }}</dd></div>
          </dl>
          <dl v-else class="quote-grid">
            <div><dt>今开</dt><dd>{{ originalMoney(asset.openPrice, asset.currency) }}</dd></div>
            <div><dt>昨收</dt><dd>{{ originalMoney(asset.previousClose, asset.currency) }}</dd></div>
            <div><dt>最高</dt><dd>{{ originalMoney(asset.highPrice, asset.currency) }}</dd></div>
            <div><dt>最低</dt><dd>{{ originalMoney(asset.lowPrice, asset.currency) }}</dd></div>
            <div><dt>换手率</dt><dd>{{ unsignedPercent(asset.turnoverRate) }}</dd></div>
            <div><dt>量比</dt><dd>{{ decimal(asset.volumeRatio) }}</dd></div>
            <div><dt>振幅</dt><dd>{{ unsignedPercent(asset.amplitude) }}</dd></div>
            <div><dt>成交量</dt><dd>{{ compactNumber(asset.volume) }}</dd></div>
            <div><dt>成交额</dt><dd>{{ compactNumber(asset.amount) }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ formatTime(asset.fetchedAt) }}</dd></div>
          </dl>
        </div>
        <div class="space-y-4 rounded-xl border p-4">
          <div><h3 class="font-medium">编辑持仓</h3></div>
          <label>份额<Input v-model="form.quantity" type="number" min="0" step="0.0001" /></label>
          <label>持仓成本价<Input v-model="form.averageCost" type="number" min="0" step="0.0001" /></label>
          <label>备注<textarea v-model="form.note" class="min-h-24 w-full rounded-md border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring" /></label>
          <Button class="w-full" :disabled="saving" @click="save">{{ saving ? '保存中' : '保存持仓' }}</Button>
        </div>
        <InvestmentPlanCard v-if="open && asset.productType === 'MUTUAL_FUND'" :asset="asset" @holding-updated="$emit('saved')" />
        <div class="rounded-xl border p-4 text-sm">
          <h3 class="font-medium">数据说明</h3>
          <dl class="mt-3 grid grid-cols-[88px_1fr] gap-y-2 text-muted-foreground"><dt>币种</dt><dd>{{ asset.currency }}</dd><dt>状态</dt><dd>{{ asset.syncStatus }}</dd><dt>最后更新</dt><dd>{{ formatTime(asset.updatedAt) }}</dd><template v-if="asset.syncError"><dt>获取提示</dt><dd class="text-amber-500">{{ asset.syncError }}</dd></template></dl>
        </div>
      </template>
      <SheetFooter><Button variant="outline" @click="$emit('update:open', false)">关闭</Button></SheetFooter>
    </SheetContent>
  </Sheet>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import InvestmentPlanCard from '@/components/investment/InvestmentPlanCard.vue'
import { feedback } from '@/lib/feedback'
import { createInvestmentAssetDraftController } from '@/lib/investmentAssetDraft'
import { compactNumber, signedPercent } from '@/lib/investmentQuoteMetrics'
import { updateInvestmentAssetAPI } from '@/api/investment'

const props = defineProps({ open: Boolean, asset: Object })
const emit = defineEmits(['update:open', 'saved'])
const saving = ref(false)
const form = reactive({ quantity: '', averageCost: '', note: '' })
const draftController = createInvestmentAssetDraftController(form)

watch(() => [props.open, props.asset], ([open, asset]) => {
  draftController.sync(open, asset)
}, { immediate: true })

async function save() {
  const hasQuantity = form.quantity !== ''
  const hasCost = form.averageCost !== ''
  if (hasQuantity !== hasCost) return feedback.error('份额和持仓成本价需要同时填写或同时清空')
  saving.value = true
  try {
    await updateInvestmentAssetAPI(props.asset.id, {
      quantity: hasQuantity ? Number(form.quantity) : null,
      averageCost: hasCost ? Number(form.averageCost) : null,
      note: form.note.trim() || null
    })
    feedback.success('持仓已保存')
    emit('saved')
    emit('update:open', false)
  } finally { saving.value = false }
}

function money(value) { return value == null ? '-' : new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 2 }).format(Number(value)) }
function originalMoney(value, currency) { if (value == null) return '-'; try { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: currency || 'CNY', maximumFractionDigits: 4 }).format(Number(value)) } catch { return `${value} ${currency}` } }
function signedOriginalMoney(value, currency) { if (value == null) return '-'; const number = Number(value); return `${number > 0 ? '+' : ''}${originalMoney(number, currency)}` }
function unsignedPercent(value) { if (value == null) return '-'; return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(Number(value))}%` }
function decimal(value) { return value == null ? '-' : new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(Number(value)) }
function signedMoney(value) { if (value == null) return '-'; const number = Number(value); return `${number > 0 ? '+' : ''}${money(number)}` }
function tone(value) { const number = Number(value || 0); return number > 0 ? 'text-emerald-500' : number < 0 ? 'text-destructive' : '' }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN') : '-' }
</script>

<style scoped>
.metric { display: grid; gap: .35rem; border: 1px solid var(--border); border-radius: .75rem; padding: 1rem; }
.metric span { font-size: .75rem; color: var(--muted-foreground); }
.metric strong { font-size: 1.05rem; }
.metric small { font-size: .75rem; font-weight: 500; }
.quote-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .85rem 1rem; }
.quote-grid div { min-width: 0; }
.quote-grid dt { font-size: .75rem; color: var(--muted-foreground); }
.quote-grid dd { margin-top: .2rem; font-size: .875rem; font-weight: 500; overflow-wrap: anywhere; }
label { display: grid; gap: .45rem; font-size: .875rem; font-weight: 500; }
</style>
