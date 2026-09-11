<script setup>
import { computed, ref } from 'vue'
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import DataTable from './DataTable.vue'
import { label, format } from './shared'

const props = defineProps({ rows: { type: Array, default: () => [] } })
const selectedKey = ref(null)
// The engine appends order events in execution order, including undated cancellations.
const orders = computed(() => {
  const grouped = new Map()
  props.rows.forEach((event, index) => {
    if (!event || typeof event !== 'object') return
    const key = event.id != null ? `id:${event.id}` : event.orderId != null ? `id:${event.orderId}` : `row:${index}`
    const previous = grouped.get(key)
    grouped.set(key, { key, latest: { ...previous?.latest, ...event }, history: [...(previous?.history || []), event] })
  })
  return [...grouped.values()]
})
const selected = computed(() => orders.value.find(order => order.key === selectedKey.value))
const columns = { id:'委托编号', assetId:'资产 ID', signalDate:'信号日期', side:'方向', quantity:'委托数量', status:'最新状态', filledQuantity:'已成交数量', date:'状态日期' }
const display = (key, value) => ['status','side'].includes(key) ? label(value) : typeof value === 'number' ? value.toLocaleString(undefined,{maximumFractionDigits:6}) : format(value)
</script>

<template>
  <div v-if="orders.length" class="table-wrap">
    <Table>
      <TableHeader><TableRow><TableHead v-for="(title,key) in columns" :key="key">{{ title }}</TableHead><TableHead>操作</TableHead></TableRow></TableHeader>
      <TableBody><TableRow v-for="order in orders" :key="order.key">
        <TableCell v-for="(title,key) in columns" :key="key">{{ display(key, key==='id' ? order.latest.id ?? order.latest.orderId : order.latest[key]) }}</TableCell>
        <TableCell><Button size="sm" variant="outline" @click="selectedKey=order.key">查看详情</Button></TableCell>
      </TableRow></TableBody>
    </Table>
  </div>
  <p v-else class="empty">暂无委托</p>
  <Dialog :open="!!selected" @update:open="open=>{if(!open)selectedKey=null}">
    <DialogContent class="qw max-h-[88vh] overflow-y-auto sm:max-w-[960px]" :aria-describedby="undefined">
      <DialogHeader><DialogTitle>委托详情</DialogTitle></DialogHeader>
      <template v-if="selected"><p>委托编号：{{ selected.latest.id ?? selected.latest.orderId ?? '—' }}</p><h3>状态变化</h3><DataTable :rows="selected.history" /></template>
    </DialogContent>
  </Dialog>
</template>
