<script setup>
import QuantBackButton from './QuantBackButton.vue'
import { userMessage } from './presentation.js'
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
import { label, formatTime, metricValue, useOperation, usePoll } from './shared'
import ResultReport from './ResultReport.vue'
import DataTable from './DataTable.vue'
import { feedback } from '@/lib/feedback'
const route=useRoute(),router=useRouter(),rows=ref([]),backtests=ref([]),detail=ref(null),events=ref([]),stopMode=ref('KEEP')
const assets=ref([])
const sourceBacktest=computed(()=>backtests.value.find(b=>String(b.id)===String(detail.value?.backtestId)))
const runtime=computed(()=>{
 const result=detail.value?.result||{}
 if(result.runtime)return result.runtime
 const reasons=(detail.value?.qualification?.reasons||[]).filter(r=>!['INSUFFICIENT_EVALUATION_DATES','NO_EXECUTED_TRADES'].includes(r))
 return {status:reasons.length?'ATTENTION_REQUIRED':detail.value?.state?.pendingOrders?.length?'WAITING_EXECUTION':result.metrics?.tradeCount?'MONITORING':'WAITING_SIGNAL',reasons,corporateActionWarnings:result.corporateActionWarnings||{}}
})
const stopOpen=ref(false)
const createOpen=ref(Boolean(route.query.backtestId))
let lastDetailError=''
const form=ref({name:'',backtestId:route.query.backtestId||'',initialCash:100000}),{busy,error,run,load:loadLatest}=useOperation()
const q=computed({get:()=>route.query.q||'',set:q=>router.replace({query:{...route.query,q:q||undefined}})})
const filtered=computed(()=>rows.value.filter(r=>!q.value||r.name?.includes(q.value)))
const qualified=computed(()=>backtests.value.filter(t=>t.status==='SUCCEEDED'&&(t.qualification?.status||t.qualification||t.result?.qualification?.status)==='QUALIFIED'))
async function load(){[rows.value,backtests.value,assets.value]=await Promise.all([quant.list('deployments'),quant.list('backtests'),quant.list('assets')]);if(route.query.id){[detail.value,events.value]=await Promise.all([quant.get('deployments',route.query.id),quant.list(`deployments/${encodeURIComponent(route.query.id)}/events`)]);if(detail.value?.errorMessage){const message=userMessage(detail.value.errorMessage,detail.value.errorCode,'模拟运行未完成，请稍后重试。');if(message!==lastDetailError){lastDetailError=message;feedback.error(message,{duration:6000})}}else lastDetailError=''}else{detail.value=null;events.value=[];lastDetailError=''}}
function create(){run(async()=>{const d=await quant.create('deployments',form.value);createOpen.value=false;await router.push({query:{id:d.id}});await load()})}
function act(action){run(async()=>{await quant.action('deployments',detail.value.id,action,action==='stop'?{mode:stopMode.value}:{});if(action==='stop')stopOpen.value=false;await load()})}
onMounted(()=>loadLatest(load));watch(()=>route.query.id,()=>loadLatest(load));watch(()=>route.query.backtestId,id=>{if(id){form.value.backtestId=id;createOpen.value=true}})
usePoll(()=>run(load),()=>rows.value.some(d=>['RUNNING','PAUSED','STOPPING'].includes(d.status)))
async function afterDelete(id) {
  if(String(route.query.id)===String(id)) { detail.value=null; events.value=[]; await router.replace({query:{...route.query,id:undefined}}) }
  loadLatest(load)
}
</script>
<template>
 <QuantPageHeader v-if="!detail" title="模拟交易"><template #actions><Button @click="createOpen=true">创建组合</Button></template></QuantPageHeader><p v-if="error" class="error" role="alert">{{error}}</p><Dialog v-model:open="createOpen"><DialogContent class="qw max-h-[88vh] overflow-y-auto sm:max-w-[760px]" :aria-describedby="undefined"><DialogHeader><DialogTitle>创建模拟组合</DialogTitle></DialogHeader><p v-if="error" class="error" role="alert">{{error}}</p><form class="px-1 pb-1" @submit.prevent="create"><div class="fields"><label class="field">组合名称<Input v-model.trim="form.name" required maxlength="120" /></label><label class="field">来源回测<select v-model="form.backtestId" required><option value="" disabled>选择回测记录</option><option v-for="b in qualified" :key="b.id" :value="b.id">{{b.name||'回测'}} · {{formatTime(b.createdAt)}}</option></select></label><label class="field">初始虚拟资金<Input v-model.number="form.initialCash" required type="number" min="1" step="0.01" /></label></div><Button class="mt-4" type="submit" :disabled="busy||!qualified.length">创建并启动模拟</Button><p v-if="!qualified.length" class="muted mt-3">暂无可继续模拟的回测。<Button size="sm" variant="outline" as-child><RouterLink to="/quant/tasks">查看回测任务及原因</RouterLink></Button></p></form></DialogContent></Dialog>
 <template v-if="!detail"><div class="panel"><div class="toolbar"><Input v-model="q" placeholder="搜索组合" aria-label="搜索模拟组合" style="max-width:300px" /><Button variant="outline" :disabled="busy" @click="run(load)">刷新</Button></div><div v-if="filtered.length" class="table-wrap"><Table><TableHeader><TableRow><TableHead>组合</TableHead><TableHead>状态</TableHead><TableHead>来源回测</TableHead><TableHead>当前权益</TableHead><TableHead>累计收益</TableHead><TableHead>最新处理</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="d in filtered" :key="d.id"><TableCell>{{d.name}}</TableCell><TableCell><QuantStatusBadge :status="d.status" /></TableCell><TableCell>{{backtests.find(b=>b.id===d.backtestId)?.name||'—'}}</TableCell><TableCell class="font-semibold tabular-nums">{{metricValue('cash',d.result?.valuation?.equity??d.state?.valuation?.equity??d.result?.equityCurve?.at(-1)?.equity)}}</TableCell><TableCell>{{metricValue('netReturn',d.result?.metrics?.netReturn??d.result?.metrics?.totalReturn)}}</TableCell><TableCell>{{d.state?.lastDate||formatTime(d.updatedAt)}}</TableCell><TableCell><div class="flex gap-2"><Button size="sm" variant="outline" @click="router.push({query:{...route.query,id:d.id}})">查看详情</Button><DeleteRecordButton resource="deployments" :record="d" :disabled="busy" @deleted="afterDelete" /></div></TableCell></TableRow></TableBody></Table></div><QuantEmptyState v-else :title="busy?'加载中…':q?'没有匹配的组合':'暂无模拟组合'" description="选择可继续模拟的回测，开始跟踪策略的模拟表现。"><Button :disabled="busy" @click="createOpen=true">创建组合</Button></QuantEmptyState><div class="quant-list-footer">共 {{filtered.length}} 个组合</div></div></template>
 <template v-if="detail">
 <QuantPageHeader :title="detail.name">
 <template #back><QuantBackButton fallback="/quant/deployments" /></template>
 <template #badges><QuantStatusBadge :status="detail.status" /></template>
 <template #actions><Button v-if="detail.status==='RUNNING'" variant="outline" class="text-destructive" :disabled="busy" @click="act('pause')">暂停组合</Button><Button v-if="detail.status==='PAUSED'" variant="outline" :disabled="busy" @click="act('resume')">恢复运行</Button><Button v-if="['RUNNING','PAUSED'].includes(detail.status)" variant="ghost" class="text-destructive" @click="stopOpen=true">停止组合</Button></template>
 </QuantPageHeader>
 <Dialog v-model:open="stopOpen"><DialogContent class="qw" :aria-describedby="undefined"><DialogHeader><DialogTitle>停止模拟组合</DialogTitle></DialogHeader><p v-if="error" class="error" role="alert">{{error}}</p><label class="field">持仓处理<select v-model="stopMode"><option value="KEEP">保留持仓及停止时估值</option><option value="LIQUIDATE">按正常成交规则模拟清仓</option></select></label><p class="muted">停止后不再产生新信号。清仓须等待可成交行情和结算。</p><div class="quant-actions justify-end"><Button variant="outline" @click="stopOpen=false">取消</Button><Button variant="destructive" :disabled="busy" @click="act('stop')">确认停止</Button></div></DialogContent></Dialog>
 <section class="panel"><h3>模拟运行状态</h3><dl class="quant-detail-meta quant-runtime-grid text-sm">
 <div><dt class="muted">策略来源</dt><dd>{{sourceBacktest?.name||'—'}}</dd></div>
 <div><dt class="muted">运行状态</dt><dd><QuantStatusBadge :status="detail.status==='RUNNING'?runtime.status:detail.status" /></dd></div>
 <div><dt class="muted">来源回测</dt><dd class="flex flex-wrap items-center gap-2"><span v-if="!sourceBacktest" class="muted">记录不可用</span><Button v-if="sourceBacktest" size="sm" variant="ghost" as-child><RouterLink :to="{path:'/quant/tasks',query:{type:'backtests',id:sourceBacktest.id}}">查看回测</RouterLink></Button></dd></div>
 <div><dt class="muted">最近处理</dt><dd>{{detail.state?.lastDate||'等待行情'}}</dd></div>
 <div><dt class="muted">最近更新</dt><dd>{{formatTime(detail.updatedAt)}}</dd></div>
 <div><dt class="muted">下次执行</dt><dd>{{detail.status==='STOPPED'?'已停止':formatTime(detail.nextRunAt)}}</dd></div>
 </dl><div v-if="runtime.reasons?.length||Object.keys(runtime.corporateActionWarnings||{}).length" class="mt-3 border-t pt-3"><p v-for="reason in runtime.reasons" :key="reason" class="muted">{{userMessage(reason,reason,'模拟运行存在待处理的问题。')}}</p><p v-for="(dates,assetId) in runtime.corporateActionWarnings" :key="assetId" class="muted">{{assets.find(a=>String(a.id)===String(assetId))?.name||'资产'}}：{{dates.join('、')}}</p></div></section><p v-if="detail.errorMessage" class="error" role="alert">{{userMessage(detail.errorMessage,detail.errorCode,'模拟运行未完成，请稍后重试。')}}</p><ResultReport :asset-names="Object.fromEntries(assets.map(a=>[a.id,a.name||a.code]))" :result="{...detail.state,...detail.result,qualification:null}" /><details class="quant-secondary"><summary>运行记录</summary><DataTable :rows="events" /></details></template>
</template>
