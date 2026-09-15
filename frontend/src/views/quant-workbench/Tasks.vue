<script setup>
import QuantBackButton from './QuantBackButton.vue'
import { userMessage } from './presentation.js'
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from '@/components/ui/dropdown-menu'
import QuantStatusBadge from './QuantStatusBadge.vue'
import QuantEmptyState from './QuantEmptyState.vue'
import QuantPageHeader from './QuantPageHeader.vue'
import DeleteRecordButton from './DeleteRecordButton.vue'
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { quant } from '@/api/quantWorkbench'
import { activeTask, formatTime, label, metricNames, metricValue, useOperation, usePoll } from './shared'
import ResultReport from './ResultReport.vue'
import BacktestResearch from './BacktestResearch.vue'
import ParameterExperimentDialog from './ParameterExperimentDialog.vue'
import { explainBacktest } from './researchExplanation.js'
import { createEligibilityLoader } from './experimentExplanation.js'
import { feedback } from '@/lib/feedback'
const route=useRoute(),router=useRouter(), rows=ref([]),detail=ref(null),compare=ref([]),reports=ref([])
const deleteOpen=ref(false)
const experimentOpen=ref(false),experimentEligibility=ref(null),parameterCatalog=ref(null),experimentEligibilityLoading=ref(false)
const {busy,error,run,load:loadLatest}=useOperation()
let lastDetailError=''
const getExperimentEligibility=createEligibilityLoader(id=>quant.experiments.eligibility(id))
const types=[['backtests','回测'],['training-runs','训练'],['factor-runs','因子分析']]
const type=computed({get:()=>types.some(([t])=>t===route.query.type)?route.query.type:'backtests',set:type=>router.replace({query:{...route.query,type,id:undefined}})})
const status=computed({get:()=>route.query.status||'',set:status=>router.replace({query:{...route.query,status:status||undefined}})})
const filtered=computed(()=>rows.value.filter(r=>!status.value||r.status===status.value))
async function load(){rows.value=await quant.list(type.value);detail.value=route.query.id?await quant.get(type.value,route.query.id):null;if(detail.value?.errorMessage){const message=userMessage(detail.value.errorMessage,detail.value.errorCode,'任务未完成，请检查设置后重试。');if(message!==lastDetailError){lastDetailError=message;feedback.error(message,{duration:6000})}}else lastDetailError='';await loadExperimentEligibility()}
async function loadExperimentEligibility(){const id=type.value==='backtests'&&detail.value?.status==='SUCCEEDED'?String(detail.value.id):'';if(!id){experimentEligibility.value=null;return}experimentEligibilityLoading.value=true;try{experimentEligibility.value=await getExperimentEligibility(id)}catch{experimentEligibility.value={eligible:false,message:'暂时无法检查参数敏感性'}}finally{experimentEligibilityLoading.value=false}}
async function openExperiment(){if(!parameterCatalog.value)parameterCatalog.value=await quant.list('parameter-catalog');experimentOpen.value=true}
function experimentCreated(created){router.push(`/quant/experiments/${created.id}`)}
function open(id){router.push({query:{...route.query,id}})}
function act(action){run(async()=>{const t=await quant.action(type.value,detail.value.id,action);if(action==='retry')open(t.id);await load()})}
function toggle(id){const i=compare.value.indexOf(id);if(i>=0)compare.value.splice(i,1);else if(compare.value.length<4)compare.value.push(id)}
function compareNow(){run(async()=>{reports.value=await Promise.all(compare.value.map(id=>quant.get('backtests',id)))})}
function comparisonValue(report,key){const metrics=report.result?.metrics||{};const aliases={totalReturn:['totalReturn','netReturn'],annualizedReturn:['annualizedReturn','annualReturn'],fees:['fees','totalFees']};const actual=(aliases[key]||[key]).find(name=>metrics[name]!=null)||key;return metricValue(actual,metrics[actual])}
const researchReport=computed(()=>explainBacktest(detail.value||{}))
const taskError=computed(()=>detail.value?.errorMessage?userMessage(detail.value.errorMessage,detail.value.errorCode,'任务未完成，请检查设置后重试。'):'')
const isBacktest=computed(()=>type.value==='backtests')
const canOpenStrategy=computed(()=>detail.value?.strategyId&&(!isBacktest.value||researchReport.value.lineage.strategy?.state==='AVAILABLE'&&researchReport.value.lineage.strategy?.currentStatus!=='DELETED'))
const qualification=computed(()=>isBacktest.value?researchReport.value.status:detail.value?.qualification?.status||(typeof detail.value?.qualification==='string'?detail.value.qualification:null)||detail.value?.result?.qualification?.status)
onMounted(()=>loadLatest(load));watch(()=>[route.query.type,route.query.id],()=>{reports.value=[];compare.value=[];loadLatest(load)})
usePoll(()=>run(load),()=>rows.value.some(r=>activeTask(r.status)))
async function afterDelete(id) {
  compare.value=compare.value.filter(value=>value!==id)
  reports.value=reports.value.filter(value=>value.id!==id)
  if(String(route.query.id)===String(id)) { detail.value=null; await router.replace({query:{...route.query,id:undefined}}) }
  loadLatest(load)
}
</script>
<template>
 <p v-if="error" class="error" role="alert">{{error}}</p>
 <template v-if="!detail">
 <QuantPageHeader title="任务中心" />
 <div class="toolbar"><div class="quant-type-tabs" role="group" aria-label="任务类型"><Button v-for="[key,title] in types" :key="key" variant="ghost" :aria-pressed="type===key" @click="type=key">{{title}}</Button></div><select v-model="status" aria-label="任务状态" style="max-width:180px"><option value="">全部状态</option><option v-for="s in ['QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED']" :key="s" :value="s">{{label(s)}}</option></select><Button variant="outline" :disabled="busy" @click="run(load)">刷新</Button></div>
 <div class="panel"><div class="toolbar" v-if="type==='backtests'"><span class="muted">选择最多四次回测进行比较</span><Button size="sm" variant="outline" :disabled="busy||compare.length<2" @click="compareNow">比较 {{compare.length}} 次结果</Button></div>
 <div v-if="filtered.length" class="table-wrap"><Table><TableHeader><TableRow><TableHead v-if="isBacktest">比较</TableHead><TableHead>研究</TableHead><TableHead>状态</TableHead><TableHead>创建时间</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="r in filtered" :key="r.id"><TableCell v-if="isBacktest"><input type="checkbox" :checked="compare.includes(r.id)" :disabled="r.status!=='SUCCEEDED'||(!compare.includes(r.id)&&compare.length>=4)" :aria-label="'比较'+(r.name||'回测')" @change="toggle(r.id)"></TableCell><TableCell>{{r.name||'研究任务'}}</TableCell><TableCell><QuantStatusBadge :status="r.status" /></TableCell><TableCell>{{formatTime(r.createdAt)}}</TableCell><TableCell><Button size="sm" variant="outline" @click="open(r.id)">查看结果</Button></TableCell></TableRow></TableBody></Table></div>
 <QuantEmptyState v-else :title="busy?'加载中…':status?'没有匹配的任务':'暂无任务'" /><div class="quant-list-footer">共 {{filtered.length}} 个任务</div></div>
 <div v-if="reports.length" class="panel"><h3>回测比较</h3><div class="table-wrap"><Table><TableHeader><TableRow><TableHead>指标</TableHead><TableHead v-for="r in reports" :key="r.id">{{r.name||'回测'}}</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="key in ['totalReturn','annualizedReturn','maxDrawdown','fees','tradeCount']" :key="key"><TableCell>{{metricNames[key]}}</TableCell><TableCell v-for="r in reports" :key="r.id">{{comparisonValue(r,key)}}</TableCell></TableRow></TableBody></Table></div></div>
 </template>
 <template v-else>
 <QuantPageHeader :title="detail.name||'任务详情'">
 <template #back><QuantBackButton :fallback="'/quant/tasks?type='+type" /></template>
 <template #badges><QuantStatusBadge :status="detail.status" /></template>
 <template #meta><span>{{formatTime(detail.createdAt)}}</span><span v-if="detail.result?.provenance?.startDate">{{detail.result.provenance.startDate}} ～ {{detail.result.provenance.endDate}}</span></template>
 <template #actions>
 <Button v-if="activeTask(detail.status)" variant="outline" :disabled="busy" @click="act('cancel')">取消任务</Button>
 <Button v-if="['FAILED','CANCELLED'].includes(detail.status)" :disabled="busy" @click="act('retry')">重新运行</Button>
 <Button v-if="isBacktest&&detail.status==='SUCCEEDED'&&qualification==='QUALIFIED'" as-child><RouterLink :to="{path:'/quant/deployments',query:{backtestId:detail.id}}">创建模拟组合</RouterLink></Button>
 <Button v-if="isBacktest&&detail.status==='SUCCEEDED'" :variant="qualification==='QUALIFIED'?'outline':'default'" :disabled="busy||experimentEligibilityLoading||!experimentEligibility?.eligible" @click="run(openExperiment)">检查参数敏感性</Button>
 <DropdownMenu><DropdownMenuTrigger as-child><Button variant="ghost">更多</Button></DropdownMenuTrigger><DropdownMenuContent align="end">
 <DropdownMenuItem v-if="canOpenStrategy" @select="router.push('/quant/strategies/'+detail.strategyId)">查看策略</DropdownMenuItem>
 <DropdownMenuItem v-if="canOpenStrategy" @select="router.push({path:'/quant/strategies/'+detail.strategyId,query:{tab:'config'}})">配置并重新运行</DropdownMenuItem>
 <DropdownMenuSeparator v-if="canOpenStrategy" /><DropdownMenuItem variant="destructive" :disabled="busy" @select="deleteOpen=true">删除</DropdownMenuItem>
 </DropdownMenuContent></DropdownMenu>
 </template></QuantPageHeader>
 <DeleteRecordButton v-model:open="deleteOpen" hide-trigger :resource="type" :record="detail" :disabled="busy" @deleted="afterDelete" />
 <p v-if="isBacktest&&detail.status==='SUCCEEDED'&&!experimentEligibilityLoading&&experimentEligibility&&!experimentEligibility.eligible" class="muted mb-4">{{userMessage(experimentEligibility.message,null,'当前结果暂不支持参数敏感性检查。')}}</p>
 <p v-if="taskError" class="error" role="alert">{{taskError}}</p>
 <ParameterExperimentDialog v-model:open="experimentOpen" :backtest="detail" :eligibility="experimentEligibility" :catalog="parameterCatalog" @created="experimentCreated" />
 <section v-if="activeTask(detail.status)" class="panel"><h3>任务进度</h3><progress v-if="typeof detail.progress==='number'" :value="detail.progress" max="100" aria-label="实际任务进度"></progress><p class="muted">{{typeof detail.progress==='number'?detail.progress+'%':'任务正在处理，请稍候。'}}</p></section>
 <p v-if="type==='training-runs'&&detail.status==='SUCCEEDED'" class="muted mb-4">{{qualification==='QUALIFIED'?'模型评估通过，可回到策略选择此模型进行回测。':qualification==='UNQUALIFIED'?'模型已生成，但本次评估未通过。':'模型已生成，未记录评估结论。'}}</p>
 <ResultReport hide-qualification-status :research="isBacktest" :asset-names="Object.fromEntries(researchReport.dataSnapshot.map(a=>[a.id,a.name||a.code]))" :result="detail.result||{}"><template v-if="isBacktest&&!taskError" #summary><BacktestResearch :report="researchReport" summary /></template><template v-if="isBacktest" #research><BacktestResearch :report="researchReport" /></template></ResultReport>
 </template>
</template>
