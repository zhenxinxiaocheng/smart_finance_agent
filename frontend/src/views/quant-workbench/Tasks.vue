<script setup>
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
import { feedback } from '@/lib/feedback'
const route=useRoute(),router=useRouter(), rows=ref([]),detail=ref(null),compare=ref([]),reports=ref([])
const experimentOpen=ref(false),experimentEligibility=ref(null),parameterCatalog=ref(null),experimentEligibilityLoading=ref(false)
const {busy,error,run,load:loadLatest}=useOperation()
let lastDetailError=''
let eligibilityBacktestId=''
const types=[['backtests','回测'],['training-runs','训练'],['factor-runs','因子分析']]
const type=computed({get:()=>types.some(([t])=>t===route.query.type)?route.query.type:'backtests',set:type=>router.push({query:{type}})})
const status=computed({get:()=>route.query.status||'',set:status=>router.replace({query:{...route.query,status:status||undefined}})})
const filtered=computed(()=>rows.value.filter(r=>!status.value||r.status===status.value))
async function load(){rows.value=await quant.list(type.value);detail.value=route.query.id?await quant.get(type.value,route.query.id):null;if(detail.value?.errorMessage){const message=`${detail.value.errorCode||'任务失败'} · ${detail.value.errorMessage}`;if(message!==lastDetailError){lastDetailError=message;feedback.error(message,{duration:6000})}}else lastDetailError='';await loadExperimentEligibility()}
async function loadExperimentEligibility(){const id=type.value==='backtests'&&detail.value?.status==='SUCCEEDED'?String(detail.value.id):'';if(!id){eligibilityBacktestId='';experimentEligibility.value=null;return}if(id===eligibilityBacktestId&&experimentEligibility.value)return;eligibilityBacktestId=id;experimentEligibilityLoading.value=true;try{experimentEligibility.value=await quant.experiments.eligibility(id)}catch{experimentEligibility.value={eligible:false,message:'暂时无法检查参数敏感性'}}finally{experimentEligibilityLoading.value=false}}
async function openExperiment(){if(!parameterCatalog.value)parameterCatalog.value=await quant.list('parameter-catalog');experimentOpen.value=true}
function experimentCreated(created){router.push(`/quant/experiments/${created.id}`)}
function open(id){router.push({query:{...route.query,id}})}
function act(action){run(async()=>{const t=await quant.action(type.value,detail.value.id,action);if(action==='retry')open(t.id);await load()})}
function toggle(id){const i=compare.value.indexOf(id);if(i>=0)compare.value.splice(i,1);else if(compare.value.length<4)compare.value.push(id)}
function compareNow(){run(async()=>{reports.value=await Promise.all(compare.value.map(id=>quant.get('backtests',id)))})}
const researchReport=computed(()=>explainBacktest(detail.value||{}))
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
 <QuantPageHeader v-if="!detail" title="任务中心" /><details :open="!route.query.id" class="quant-section"><summary v-show="route.query.id">任务列表与比较</summary><div class="toolbar"><div class="quant-type-tabs" role="group" aria-label="任务类型"><Button v-for="[key,title] in types" :key="key" variant="ghost" :aria-pressed="type===key" @click="type=key">{{title}}</Button></div><select v-model="status" aria-label="任务状态" style="max-width:180px"><option value="">全部状态</option><option v-for="s in ['QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED']" :key="s" :value="s">{{label(s)}}</option></select><Button variant="outline" :disabled="busy" @click="run(load)">刷新</Button></div>
 <div class="panel"><div class="toolbar" v-if="type==='backtests'"><span class="muted">选择最多四次回测进行比较</span><Button size="sm" variant="outline" :disabled="busy||compare.length<2" @click="compareNow">比较 {{compare.length}} 次结果</Button></div><div v-if="filtered.length" class="table-wrap"><Table><TableHeader><TableRow><TableHead v-if="type==='backtests'">比较</TableHead><TableHead>任务</TableHead><TableHead>状态</TableHead><TableHead>阶段</TableHead><TableHead>创建时间</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="r in filtered" :key="r.id"><TableCell v-if="type==='backtests'"><input type="checkbox" :checked="compare.includes(r.id)" :disabled="r.status!=='SUCCEEDED'||(!compare.includes(r.id)&&compare.length>=4)" :aria-label="`比较${r.name||r.id}`" @change="toggle(r.id)"></TableCell><TableCell>{{r.name||r.id}}</TableCell><TableCell><QuantStatusBadge :status="r.status" /></TableCell><TableCell>{{label(r.stage)}}</TableCell><TableCell>{{formatTime(r.createdAt)}}</TableCell><TableCell><div class="flex gap-2"><Button size="sm" variant="outline" @click="open(r.id)">查看</Button><DeleteRecordButton :resource="type" :record="r" :disabled="busy" @deleted="afterDelete" /></div></TableCell></TableRow></TableBody></Table></div><QuantEmptyState v-else :title="busy?'加载中…':status?'没有匹配的任务':'暂无任务'" description="从策略启动回测或训练，结果会保存在这里。"><Button variant="outline" as-child><RouterLink to="/quant">选择策略</RouterLink></Button></QuantEmptyState><div class="quant-list-footer">共 {{filtered.length}} 个任务</div></div>
 <div v-if="reports.length" class="panel"><h3>回测比较</h3><div class="table-wrap"><Table><TableHeader><TableRow><TableHead>指标</TableHead><TableHead v-for="r in reports" :key="r.id">{{r.name||r.id}}</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="key in [...new Set(reports.flatMap(r=>Object.keys(r.result?.metrics||{})))]" :key="key"><TableCell>{{metricNames[key]||key}}</TableCell><TableCell v-for="r in reports" :key="r.id">{{metricValue(key,r.result?.metrics?.[key])}}</TableCell></TableRow></TableBody></Table></div><div class="mt-4 grid gap-4 lg:grid-cols-2"><div v-for="r in reports" :key="r.id"><h3>{{r.name||r.id}}</h3><ResultReport :result="{...r.result,qualification:r.qualification}" /></div></div></div>
 </details>
 <template v-if="detail"><QuantPageHeader :title="detail.name||'任务详情'"><template #back><Button variant="ghost" size="sm" @click="router.push({query:{...route.query,id:undefined}})">返回任务列表</Button></template><template #badges><QuantStatusBadge :status="detail.status" /><QuantStatusBadge :status="qualification" /></template><template #meta><span>{{formatTime(detail.createdAt)}}</span><span>{{label(detail.stage)}}</span><span v-if="detail.strategyVersionId" :title="detail.strategyVersionId">策略版本 · {{detail.strategyVersionId.slice(0,8)}}</span></template><template #actions><Button v-if="activeTask(detail.status)" variant="outline" :disabled="busy" @click="act('cancel')">取消任务</Button><Button v-if="['FAILED','CANCELLED'].includes(detail.status)" variant="outline" :disabled="busy" @click="act('retry')">重新运行</Button><Button v-if="type==='backtests'&&detail.status==='SUCCEEDED'" variant="outline" :disabled="busy||experimentEligibilityLoading||!experimentEligibility?.eligible" :title="experimentEligibility?.eligible?'创建五点参数检查':experimentEligibility?.message" @click="run(openExperiment)">检查参数敏感性</Button><span v-if="type==='backtests'&&detail.status==='SUCCEEDED'&&!experimentEligibilityLoading&&experimentEligibility&&!experimentEligibility.eligible" class="muted">{{experimentEligibility.message}}</span><Button v-if="type==='backtests'&&detail.status==='SUCCEEDED'&&qualification==='QUALIFIED'" as-child><RouterLink :to="{path:'/quant/deployments',query:{backtestId:detail.id}}">使用此版本创建模拟组合</RouterLink></Button><Button v-if="canOpenStrategy" variant="outline" as-child><RouterLink :to="`/quant/strategies/${detail.strategyId}`">查看策略</RouterLink></Button><Button v-if="canOpenStrategy" variant="outline" as-child><RouterLink :to="{path:`/quant/strategies/${detail.strategyId}`,query:{tab:type==='training-runs'?'training':'backtests'}}">配置并重新运行</RouterLink></Button><DeleteRecordButton :resource="type" :record="detail" :disabled="busy" @deleted="afterDelete" /></template></QuantPageHeader><ParameterExperimentDialog v-model:open="experimentOpen" :backtest="detail" :eligibility="experimentEligibility" :catalog="parameterCatalog" @created="experimentCreated" /><BacktestResearch v-if="isBacktest" :report="researchReport" summary /><div v-if="activeTask(detail.status)||(!isBacktest&&detail.errorMessage)" class="panel"><h3>任务状态</h3><p class="muted">当前阶段：{{label(detail.stage)}}</p><div v-if="activeTask(detail.status)&&typeof detail.progress==='number'" class="mt-3"><progress :value="detail.progress" max="100" aria-label="实际任务进度"></progress><p class="muted">{{detail.progress}}%</p></div><p v-else-if="activeTask(detail.status)" class="muted mt-3">当前阶段未报告百分比进度。取消将撤销结果发布，已开始的计算可能仍在后台结束。</p><p v-if="detail.errorMessage" class="error mt-3">{{detail.errorCode}} · {{detail.errorMessage}}</p><p v-if="qualification" class="mt-3">策略验证：{{label(qualification)}}</p></div><ResultReport hide-qualification-status :research="isBacktest" :result="{...detail.result,qualification:detail.qualification}"><template v-if="isBacktest" #research><BacktestResearch :report="researchReport" /></template></ResultReport></template>
</template>
