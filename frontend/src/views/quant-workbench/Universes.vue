<script setup>
import QuantStatusBadge from './QuantStatusBadge.vue'
import QuantPageHeader from './QuantPageHeader.vue'
import QuantEmptyState from './QuantEmptyState.vue'
import DeleteRecordButton from './DeleteRecordButton.vue'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table'
import { Input } from '@/components/ui/input'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { quant } from '@/api/quantWorkbench'
import { label, clone, useOperation } from './shared'
const route=useRoute(), router=useRouter(), rows=ref([]), assets=ref([]), form=ref(null)
const {busy,error,run}=useOperation()
const q=computed({get:()=>route.query.q||'',set:q=>router.replace({query:{...route.query,q:q||undefined}})})
const filtered=computed(()=>rows.value.filter(r=>!q.value||r.name?.includes(q.value)))
const eligible=computed(()=>assets.value.filter(a=>a.assetClass===form.value?.assetClass))
function restoreSelection(){const selected=rows.value.find(r=>String(r.id)===String(route.query.id));if(selected)form.value=clone(selected)}
async function load(){[rows.value,assets.value]=await Promise.all([quant.list('universes'),quant.list('assets')]);restoreSelection()}
watch(()=>route.query.id,restoreSelection)
function edit(row){router.replace({query:{...route.query,id:row?.id}});form.value=row?clone(row):{name:'',assetClass:'STOCK',assetIds:[]}}
async function close(){form.value=null;await router.replace({query:{...route.query,id:undefined}})}
function save(){run(async()=>{if(form.value.id)await quant.update('universes',form.value.id,form.value);else await quant.create('universes',form.value);await close();await load()})}
function act(row,action){run(async()=>{await quant.action('universes',row.id,action);await load()})}
onMounted(()=>run(load))
</script>
<template>
 <p v-if="error&&!form" class="error" role="alert">{{error}}</p><QuantPageHeader title="资产池"><template #actions><Button @click="edit()">创建资产池</Button></template></QuantPageHeader>
 <Dialog :open="!!form" @update:open="value=>{if(!value)close()}"><DialogContent class="qw max-h-[88vh] overflow-y-auto sm:max-w-[960px]" :aria-describedby="undefined"><DialogHeader><DialogTitle>{{form?.id?'编辑资产池':'创建资产池'}}</DialogTitle></DialogHeader><p v-if="error" class="error" role="alert">{{error}}</p><form v-if="form" class="space-y-5 px-1 pb-1" @submit.prevent="save"><div class="fields"><label class="field">名称<Input v-model.trim="form.name" required maxlength="120" /></label><label class="field">资产类别<select v-model="form.assetClass" @change="form.assetIds=[]"><option value="STOCK">股票</option><option value="ETF">ETF</option><option value="FUND">场外基金</option></select></label></div>
 <h3 class="mt-5">从已有资产导入</h3><p class="muted mb-3">历史覆盖只表示已有记录，不代表每个交易日都完整；任务还会校验有效数据。</p><div class="table-wrap" v-if="eligible.length"><Table><TableHeader><TableRow><TableHead>选入</TableHead><TableHead>资产</TableHead><TableHead>历史起止</TableHead><TableHead>观察数</TableHead><TableHead>数据准备</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="a in eligible" :key="a.id"><TableCell><input v-model="form.assetIds" type="checkbox" :value="a.id" :aria-label="`选择${a.name}`"></TableCell><TableCell>{{a.name}} · {{a.code}}</TableCell><TableCell>{{a.historyStartDate||'缺少历史'}} ～ {{a.historyEndDate||'—'}}</TableCell><TableCell>{{a.observations??'未知'}}</TableCell><TableCell><Button size="sm" variant="outline" as-child><RouterLink :to="`/stocks/${a.id}`">查看 / 补充历史</RouterLink></Button></TableCell></TableRow></TableBody></Table></div><p v-else class="empty">暂无可导入资产。<Button size="sm" variant="outline" as-child><RouterLink to="/stocks">前往投资分析添加资产</RouterLink></Button></p>
 <div class="toolbar mt-4"><Button type="submit" :disabled="busy||!form.assetIds.length||form.status==='ARCHIVED'">保存资产池</Button><Button type="button" variant="outline" @click="close">关闭</Button><span class="muted">已选 {{form.assetIds.length}} 个</span></div></form></DialogContent></Dialog>
 <div class="panel"><div class="toolbar"><Input v-model="q" placeholder="搜索资产池" aria-label="搜索资产池" style="max-width:300px" /><Button variant="outline" :disabled="busy" @click="run(load)">刷新</Button></div><div v-if="filtered.length" class="table-wrap"><Table><TableHeader><TableRow><TableHead>名称</TableHead><TableHead>类别</TableHead><TableHead>成员</TableHead><TableHead>版本 / 状态</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="r in filtered" :key="r.id"><TableCell>{{r.name}}</TableCell><TableCell>{{label(r.assetClass)}}</TableCell><TableCell>{{r.assetIds?.length||0}}</TableCell><TableCell><div class="flex gap-2"><QuantStatusBadge :status="r.status" /><span class="muted">v{{r.revision}}</span></div></TableCell><TableCell><div class="flex gap-2"><Button size="sm" variant="outline" @click="edit(r)">{{r.status==='ARCHIVED'?'查看':'编辑'}}</Button><Button size="sm" variant="outline" :disabled="busy" @click="act(r,'copy')">复制</Button><Button v-if="r.status!=='ARCHIVED'" size="sm" variant="outline" :disabled="busy" @click="act(r,'archive')">归档</Button><DeleteRecordButton resource="universes" :record="r" :disabled="busy" @deleted="id=>{if(form?.id===id)form=null;run(load)}" /></div></TableCell></TableRow></TableBody></Table></div><QuantEmptyState v-else :title="busy?'加载中…':q?'没有匹配的资产池':'暂无资产池'" description="将已有资产加入资产池，供策略和因子研究使用。"><Button :disabled="busy" @click="edit()">创建资产池</Button></QuantEmptyState><div class="quant-list-footer">共 {{filtered.length}} 个资产池</div></div>
</template>
