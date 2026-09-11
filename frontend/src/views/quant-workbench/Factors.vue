<script setup>
import QuantStatusBadge from './QuantStatusBadge.vue'
import QuantPageHeader from './QuantPageHeader.vue'
import QuantEmptyState from './QuantEmptyState.vue'
import DeleteRecordButton from './DeleteRecordButton.vue'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table'
import { Input } from '@/components/ui/input'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { quant } from '@/api/quantWorkbench'
import { clone, label, useOperation } from './shared'
const search=ref('')
const filteredSets=computed(()=>sets.value.filter(s=>!search.value||s.name?.includes(search.value)))
const editorOpen=ref(false)
const router=useRouter(), catalog=ref([]),sets=ref([]),universes=ref([]),form=ref({name:'',assetClass:'STOCK',factors:[]}),range=ref({universeId:'',startDate:'',endDate:'',predictionHorizon:5})
const {busy,error,run}=useOperation()
const compatible=computed(()=>universes.value.filter(u=>u.assetClass===form.value.assetClass&&u.status!=='ARCHIVED'))
const factors=computed(()=>catalog.value.filter(f=>!f.assetClasses||f.assetClasses.includes(form.value.assetClass)))
const factorKey=f=>f.key||f.id
const selected=key=>form.value.factors.find(f=>f.key===key)
function toggle(key){const i=form.value.factors.findIndex(f=>f.key===key);if(i<0)form.value.factors.push({key,weight:1});else form.value.factors.splice(i,1)}
async function load(){[catalog.value,sets.value,universes.value]=await Promise.all([quant.list('factor-catalog'),quant.list('factors'),quant.list('universes')])}
function save(){run(async()=>{const saved=form.value.id?await quant.update('factors',form.value.id,form.value):await quant.create('factors',form.value);form.value=clone(saved);sets.value=await quant.list('factors')})}
function research(){run(async()=>{const t=await quant.create('factor-runs',{universeId:range.value.universeId,startDate:range.value.startDate,endDate:range.value.endDate,config:{factors:clone(form.value.factors),predictionHorizon:range.value.predictionHorizon}});await router.push({path:'/quant/tasks',query:{type:'factor-runs',id:t.id}})})}
function reset(){editorOpen.value=true;form.value={name:'',assetClass:'STOCK',factors:[]}}
onMounted(()=>run(load))
</script>
<template>
 <QuantPageHeader title="因子研究"><template #actions><Button @click="reset">创建因子组合</Button></template></QuantPageHeader>
 <Dialog v-model:open="editorOpen"><DialogContent class="qw max-h-[88vh] overflow-y-auto sm:max-w-[960px]" :aria-describedby="undefined"><DialogHeader><DialogTitle>{{form.id?form.name:'新建因子组合'}}</DialogTitle></DialogHeader><p v-if="error" class="error" role="alert">{{error}}</p><div class="px-1 pb-1"><form @submit.prevent="save"><div class="fields"><label class="field">组合名称<Input v-model.trim="form.name" required maxlength="120" /></label><label class="field">资产类别<select v-model="form.assetClass" @change="form.factors=[];range.universeId='' "><option value="STOCK">股票</option><option value="ETF">ETF</option><option value="FUND">场外基金</option></select></label></div><h3 class="mt-5">因子与权重</h3><div class="table-wrap"><Table><TableHeader><TableRow><TableHead>选择</TableHead><TableHead>因子</TableHead><TableHead>说明</TableHead><TableHead>权重</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="f in factors" :key="factorKey(f)"><TableCell><input type="checkbox" :checked="!!selected(factorKey(f))" :disabled="f.available===false" :aria-label="f.name||factorKey(f)" @change="toggle(factorKey(f))"></TableCell><TableCell>{{f.name||factorKey(f)}}</TableCell><TableCell>{{f.description||f.reason||'—'}}</TableCell><TableCell><Input v-if="selected(factorKey(f))" v-model.number="selected(factorKey(f)).weight" type="number" step="0.1" required :aria-label="`${f.name||factorKey(f)}权重`" /></TableCell></TableRow></TableBody></Table></div><Button class="mt-4" type="submit" :disabled="busy||!form.factors.length||form.status==='ARCHIVED'">保存因子组合</Button></form>
 <form class="mt-6 border-t pt-5" @submit.prevent="research"><h3>运行因子分析</h3><div class="fields"><label class="field">资产池<select v-model="range.universeId" required><option value="" disabled>选择同类别资产池</option><option v-for="u in compatible" :key="u.id" :value="u.id">{{u.name}}</option></select></label><label class="field">开始日期<Input v-model="range.startDate" type="date" required :max="range.endDate||undefined" /></label><label class="field">结束日期<Input v-model="range.endDate" type="date" required :min="range.startDate||undefined" /></label><label class="field">未来收益周期（日）<Input v-model.number="range.predictionHorizon" type="number" min="1" step="1" required /></label></div><Button class="mt-4" :disabled="busy||!form.factors.length" type="submit">计算因子结果</Button></form></div></DialogContent></Dialog>
 <div class="panel"><div class="toolbar"><Input v-model="search" placeholder="搜索因子组合" aria-label="搜索因子组合" style="max-width:300px" /><Button variant="outline" :disabled="busy" @click="run(load)">刷新</Button></div><div v-if="filteredSets.length" class="table-wrap"><Table><TableHeader><TableRow><TableHead>组合</TableHead><TableHead>类别</TableHead><TableHead>因子数</TableHead><TableHead>版本 / 状态</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="s in filteredSets" :key="s.id"><TableCell>{{s.name}}</TableCell><TableCell>{{label(s.assetClass)}}</TableCell><TableCell>{{s.factors?.length||0}}</TableCell><TableCell><div class="flex gap-2"><QuantStatusBadge :status="s.status" /><span class="muted">v{{s.revision}}</span></div></TableCell><TableCell><div class="flex gap-2"><Button size="sm" variant="outline" @click="form=clone(s);editorOpen=true">研究 / 编辑</Button><Button size="sm" variant="outline" :disabled="busy" @click="run(async()=>{await quant.action('factors',s.id,'copy');await load()})">复制</Button><Button v-if="s.status!=='ARCHIVED'" size="sm" variant="outline" :disabled="busy" @click="run(async()=>{await quant.action('factors',s.id,'archive');await load()})">归档</Button><DeleteRecordButton resource="factors" :record="s" :disabled="busy" @deleted="id=>{if(form.id===id){editorOpen=false;form={name:'',assetClass:'STOCK',factors:[]}}run(load)}" /></div></TableCell></TableRow></TableBody></Table></div><QuantEmptyState v-else :title="busy?'加载中…':search?'没有匹配的因子组合':'暂无因子组合'" description="组合多个因子后，可以在策略中直接复用。"><Button :disabled="busy" @click="reset">创建因子组合</Button></QuantEmptyState><div class="quant-list-footer">共 {{filteredSets.length}} 个因子组合</div></div>
</template>
