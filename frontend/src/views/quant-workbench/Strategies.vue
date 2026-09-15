<script setup>
import QuantStatusBadge from './QuantStatusBadge.vue'
import QuantPageHeader from './QuantPageHeader.vue'
import QuantEmptyState from './QuantEmptyState.vue'
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table'
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog'
import { feedback } from '@/lib/feedback'
const deleting = ref(null)
function removeStrategy() { run(async () => { await quant.deleteStrategy(deleting.value.id); deleting.value = null; feedback.success('策略已删除'); await load() }) }
import { quant } from '@/api/quantWorkbench'
import { label, useOperation } from './shared'
const route = useRoute(), router = useRouter()
const rows = ref([]), universes = ref([])
const { busy, error, run } = useOperation()
const q = computed({ get: () => route.query.q || '', set: q => router.replace({query:{...route.query,q:q||undefined}}) })
const status = computed({ get: () => route.query.status || '', set: status => router.replace({query:{...route.query,status:status||undefined}}) })
const filtered = computed(() => rows.value.filter(r => (!q.value || r.name?.includes(q.value)) && (!status.value || r.status === status.value)))
const universeName = id => universes.value.find(u => String(u.id) === String(id))?.name || '未配置'
async function load() { [rows.value, universes.value] = await Promise.all([quant.list('strategies'),quant.list('universes')]) }
function action(row, action) { run(async () => { const result = await quant.action('strategies',row.id,action); if(action==='copy') await router.push(`/quant/strategies/${result.id}`); else await load() }) }
onMounted(() => run(load))
</script>
<template>
  <p v-if="error" class="error" role="alert">{{error}}</p><QuantPageHeader title="策略管理"><template #actions><Button @click="router.push('/quant/strategies/new')">创建策略</Button></template></QuantPageHeader>
  <div class="panel"><div class="toolbar"><Input v-model="q" aria-label="搜索策略" placeholder="搜索策略名称" style="max-width:300px" /><select v-model="status" aria-label="筛选状态" style="max-width:180px"><option value="">全部状态</option><option value="DRAFT">草稿</option><option value="ACTIVE">启用</option><option value="ARCHIVED">已归档</option></select><Button variant="outline" :disabled="busy" @click="run(load)">刷新</Button></div>
  <div v-if="filtered.length" class="table-wrap"><Table><TableHeader><TableRow><TableHead>策略</TableHead><TableHead>类型</TableHead><TableHead>资产池</TableHead><TableHead>状态 / 版本</TableHead><TableHead>最新回测</TableHead><TableHead>管理</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="row in filtered" :key="row.id"><TableCell><Button size="sm" variant="outline" as-child><RouterLink :to="`/quant/strategies/${row.id}`">{{ row.name }}</RouterLink></Button></TableCell><TableCell>{{ label(row.config?.strategyType) }}</TableCell><TableCell>{{ universeName(row.universeId) }}</TableCell><TableCell><div class="flex gap-2"><QuantStatusBadge :status="row.status" /><span class="muted">v{{row.revision}}</span></div></TableCell><TableCell>{{ label(row.latestBacktest?.status) }}<br><span class="muted">{{ label(row.latestBacktest?.qualification?.status) }}</span></TableCell><TableCell><div class="flex flex-wrap gap-2"><Button size="sm" variant="outline" @click="router.push(`/quant/strategies/${row.id}`)">管理 / 回测</Button><DropdownMenu><DropdownMenuTrigger as-child><Button size="sm" variant="ghost" :disabled="busy" :aria-label="'更多操作：'+row.name">更多</Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuItem :disabled="busy" @select="action(row,'copy')">复制</DropdownMenuItem><DropdownMenuItem v-if="row.status!=='ARCHIVED'" :disabled="busy" @select="action(row,'archive')">归档</DropdownMenuItem><DropdownMenuSeparator /><DropdownMenuItem variant="destructive" :disabled="busy" @select="deleting=row">删除</DropdownMenuItem></DropdownMenuContent></DropdownMenu></div></TableCell></TableRow></TableBody></Table></div>
  <QuantEmptyState v-else :title="busy?'正在加载策略…':q||status?'没有符合条件的策略':'暂无策略'" description="选择资产池和策略模板，开始第一次回测。"><Button :disabled="busy" @click="router.push('/quant/strategies/new')">创建策略</Button></QuantEmptyState><div class="quant-list-footer">共 {{filtered.length}} 个策略</div></div>
  <Dialog :open="!!deleting" @update:open="value=>{if(!value&&!busy)deleting=null}">
    <DialogContent>
      <DialogHeader><DialogTitle>删除策略“{{deleting?.name}}”？</DialogTitle><DialogDescription>删除后将从策略列表移除，不能再编辑或运行。历史训练、回测和模拟账本仍保留供查询。运行中的关联任务和模拟组合需要先停止。</DialogDescription></DialogHeader>
      <p v-if="error" class="text-sm text-destructive" role="alert">{{error}}</p><DialogFooter><Button variant="outline" :disabled="busy" @click="deleting=null">取消</Button><Button variant="destructive" :disabled="busy" @click="removeStrategy">{{busy?'正在删除…':'确认删除'}}</Button></DialogFooter>
    </DialogContent>
  </Dialog>
</template>
