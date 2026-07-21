<template>
  <div class="overflow-hidden rounded-xl border bg-card">
    <div v-if="loading" class="grid place-items-center py-20 text-sm text-muted-foreground">
      <LoaderCircle class="mb-3 size-6 animate-spin" />
      正在加载资产
    </div>
    <div v-else-if="assets.length === 0" class="grid place-items-center px-6 py-20 text-center">
      <div class="mb-4 grid size-12 place-items-center rounded-full bg-muted"><ChartNoAxesCombined class="size-6 text-muted-foreground" /></div>
      <p class="font-medium">输入股票或基金代码开始添加</p>
      <p class="mt-1 text-sm text-muted-foreground">无需先创建账户，也不需要填写复杂流水。</p>
    </div>
    <div v-else class="investment-table-scroll overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead class="min-w-[200px]">资产</TableHead>
            <TableHead>最新价</TableHead>
            <TableHead>涨跌</TableHead>
            <TableHead>份额</TableHead>
            <TableHead>持仓成本价</TableHead>
            <TableHead>市值</TableHead>
            <TableHead>盈亏</TableHead>
            <TableHead>数据状态</TableHead>
            <TableHead class="w-24 text-right">操作</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="asset in assets" :key="asset.id" class="cursor-pointer" @click="$emit('select', asset)">
            <TableCell>
              <div class="font-medium">{{ asset.name }}</div>
              <div class="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
                <span class="font-mono">{{ asset.code }}</span><span>{{ asset.market }}</span><span>{{ typeLabel(asset.productType) }}</span>
              </div>
            </TableCell>
            <TableCell><div class="font-medium">{{ originalMoney(asset.latestPrice, asset.currency) }}</div><div class="text-xs text-muted-foreground">{{ formatQuoteTime(asset) }}</div></TableCell>
            <TableCell>
              <div class="font-medium" :class="tone(asset.changePercent)">{{ signedPercent(asset.changePercent) }}</div>
              <div class="text-xs" :class="tone(asset.changeAmount)">{{ signedOriginalMoney(asset.changeAmount, asset.currency) }}</div>
            </TableCell>
            <TableCell>{{ decimal(asset.quantity) }}</TableCell>
            <TableCell>{{ originalMoney(asset.averageCost, asset.currency) }}</TableCell>
            <TableCell>{{ money(asset.marketValueCny) }}</TableCell>
            <TableCell>
              <div :class="tone(asset.unrealizedPnlCny)">{{ signedMoney(asset.unrealizedPnlCny) }}</div>
              <div class="text-xs" :class="tone(asset.holdingReturnPercent)">{{ signedPercent(asset.holdingReturnPercent) }}</div>
            </TableCell>
            <TableCell><Badge :variant="statusVariant(asset.syncStatus)">{{ statusLabel(asset.syncStatus) }}</Badge></TableCell>
            <TableCell>
              <div class="flex justify-end gap-1" @click.stop>
                <Button size="icon-sm" variant="ghost" title="编辑" @click="$emit('edit', asset)"><Pencil /></Button>
                <Button size="icon-sm" variant="ghost" class="text-destructive hover:text-destructive" title="删除" @click="$emit('remove', asset)"><Trash2 /></Button>
              </div>
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>
  </div>
</template>

<script setup>
import { ChartNoAxesCombined, LoaderCircle, Pencil, Trash2 } from '@lucide/vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatQuoteTime } from '@/lib/investmentRealtime'
import { signedPercent } from '@/lib/investmentQuoteMetrics'

defineProps({ assets: { type: Array, default: () => [] }, loading: Boolean })
defineEmits(['select', 'edit', 'remove'])

function typeLabel(value) { return value === 'MUTUAL_FUND' ? '基金' : '股票' }
function statusLabel(value) { return ({ SUCCESS: '数据正常', PARTIAL: '部分数据', PENDING: '等待刷新', RUNNING: '刷新中', FAILED: '数据延迟', NOT_SYNCED: '未同步' }[value] || '未同步') }
function statusVariant(value) { return value === 'FAILED' ? 'destructive' : value === 'SUCCESS' ? 'secondary' : 'outline' }
function decimal(value) { return value == null ? '未填写' : new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 6 }).format(Number(value)) }
function money(value) { return value == null ? '-' : new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 2 }).format(Number(value)) }
function originalMoney(value, currency) { if (value == null) return '-'; try { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: currency || 'CNY', maximumFractionDigits: 4 }).format(Number(value)) } catch { return `${value} ${currency}` } }
function signedOriginalMoney(value, currency) { if (value == null) return '-'; const number = Number(value); return `${number > 0 ? '+' : ''}${originalMoney(number, currency)}` }
function signedMoney(value) { if (value == null) return '-'; const number = Number(value); return `${number > 0 ? '+' : ''}${money(number)}` }
function tone(value) { const number = Number(value || 0); return number > 0 ? 'text-emerald-500' : number < 0 ? 'text-destructive' : '' }
</script>

<style scoped>
.investment-table-scroll::-webkit-scrollbar {
  height: 12px;
}

.investment-table-scroll {
  scrollbar-width: auto;
}
</style>
