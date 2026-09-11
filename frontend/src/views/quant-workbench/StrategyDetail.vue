<script setup>
import QuantStatusBadge from './QuantStatusBadge.vue'
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table'
import { Input } from '@/components/ui/input'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { ArrowLeft, ExternalLink } from '@lucide/vue'
import { quant } from '@/api/quantWorkbench'
import { label, formatTime, clone, useOperation } from './shared'
import DataTable from './DataTable.vue'
import QuantPageHeader from './QuantPageHeader.vue'
import { Badge } from '@/components/ui/badge'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { feedback } from '@/lib/feedback'
import { filterVersionBacktests } from './researchExplanation.js'
const historyOpen=ref(false)
const parameterCatalog=ref(null)
const defaults=computed(()=>Object.fromEntries((parameterCatalog.value?.parameters||[]).map(p=>[p.key,p.default])))
const effectiveConfig=config=>({...defaults.value,...Object.fromEntries(Object.entries(config||{}).filter(([,v])=>v!==''&&v!=null))})
const percentageKeys=['maxWeight','maxDrawdown','targetVol','feeRate','sellFeeRate']
const parameterValue=key=>{const value=form.value.config[key] ?? defaults.value[key];return value==null||value===''?'':percentageKeys.includes(key)?Number((value*100).toFixed(8)):value}
function setParameter(key,value){form.value.config[key]=value===''?'':percentageKeys.includes(key)?Number(value)/100:Number(value)}
const parameterTitle=(key,title)=>percentageKeys.includes(key)?title.replace('（0～1）','')+'（%）':title
function parameterConstraints(key) {
 const parameter=parameterCatalog.value?.parameters.find(p=>p.key===key)
 if(!parameter)return {}
 const scale=percentageKeys.includes(key)?100:1
 return {min:parameter.min*scale,max:parameter.max*scale,step:parameter.type==='integer'?1:'any'}
}
const parameterGroups=[{title:'信号与调仓',keys:['lookback','slowWindow','topN','rebalanceDays','predictionHorizon']},{title:'风险与仓位',keys:['initialCash','maxWeight','maxDrawdown','targetVol']}]
const templateSummary=computed(()=>form.value?.config?.strategyType==='TREND'?'通过趋势与均线筛选资产，并根据波动率调整仓位。':ml.value?'使用历史数据训练收益预测模型，再根据预测结果选择资产。':'按因子组合加权评分，对资产排名后构建组合。')
const advancedKeys=['feeRate','sellFeeRate','slippageBps','publicationLagDays','settlementDays','seed']
const visibleParameters=computed(()=>numeric.filter(([key])=>{
 if(['publicationLagDays','settlementDays'].includes(key))return selectedUniverse.value?.assetClass==='FUND'
 if(key==='targetVol')return form.value?.config?.strategyType==='TREND'
 if(key==='slippageBps')return selectedUniverse.value?.assetClass!=='FUND'
 if(['predictionHorizon','seed'].includes(key))return ml.value
 return true
}))
const tabs=computed(()=>[['config','配置'],...(ml.value?[['training','训练']]:[]),['backtests','回测']])
const route=useRoute(),router=useRouter(), form=ref(null),universes=ref([]),factorSets=ref([]),versions=ref([]),tasks=ref([])
const {busy,error,run,load:loadLatest}=useOperation()
const isNew=computed(()=>route.params.id==='new'),tab=computed({get:()=>['config','training','backtests'].includes(route.query.tab)?route.query.tab:'config',set:tab=>router.replace({query:{...route.query,tab}})})
const range=ref({startDate:'',endDate:'',modelTaskId:''}), evaluation=ref(null), evaluationError=ref(''), evaluationLoading=ref(false)
const ml=computed(()=>form.value?.config?.strategyType?.startsWith('ML_'))
const numeric=[['lookback','观察窗口（日）',2,1],['slowWindow','趋势慢均线（日）',2,1],['topN','最多选择标的数',1,1],['rebalanceDays','调仓间隔（日）',1,1],['predictionHorizon','预测周期（日）',1,1],['initialCash','回测初始资金',1,1000],['maxWeight','单标的权重上限（0～1）',0.001,0.01],['maxDrawdown','最大允许回撤（0～1）',0.001,0.01],['targetVol','单标的降仓波动阈值',0.001,0.01],['feeRate','买入费率',0,0.0001],['sellFeeRate','卖出费率',0,0.0001],['slippageBps','滑点（基点）',0,1],['publicationLagDays','净值公布延迟（日）',0,1],['settlementDays','结算延迟（日）',0,1],['seed','随机种子',0,1]]
const selectedUniverse=computed(()=>universes.value.find(u=>String(u.id)===String(form.value?.universeId)))
const compatibleSets=computed(()=>factorSets.value.filter(f=>f.status!=='ARCHIVED'&&f.assetClass===selectedUniverse.value?.assetClass))
const relevantTasks=computed(()=>tasks.value.filter(t=>String(t.strategyId)===String(route.params.id)))
const versionSelection=computed({get:()=>typeof route.query.version==='string'?route.query.version:'current',set:version=>router.replace({query:{...route.query,version:version==='current'?undefined:version}})})
const backtestTasks=computed(()=>filterVersionBacktests(relevantTasks.value.filter(t=>t.resource==='backtests'),versions.value,form.value?.revision,versionSelection.value))
const displayedTasks=computed(()=>tab.value==='backtests'?backtestTasks.value:relevantTasks.value.filter(t=>t.resource==='training-runs'))
const versionLabel=id=>{const version=versions.value.find(v=>String(v.id)===String(id));return version?`v${version.version}`:'版本未记录'}
const selectedVersion=computed(()=>versions.value.find(v=>String(v.id)===String(versionSelection.value)))
const trained=computed(()=>relevantTasks.value.filter(t=>t.resource==='training-runs'&&t.status==='SUCCEEDED'))
const hasSelectedRange=computed(()=>Boolean(range.value.startDate&&range.value.endDate))
const insufficientEvaluation=computed(()=>tab.value==='backtests'&&hasSelectedRange.value&&evaluation.value?.selectedRangeReady===false)
let evaluationSequence=0
async function loadEvaluationWindow(){
 if(tab.value!=='backtests'||!form.value?.universeId){evaluation.value=null;return}
 const sequence=++evaluationSequence
 evaluationLoading.value=true;evaluationError.value=''
 try{
  const params=hasSelectedRange.value?{startDate:range.value.startDate,endDate:range.value.endDate}:undefined
  const next=await quant.list(`universes/${encodeURIComponent(form.value.universeId)}/evaluation-window`,params)
  if(sequence===evaluationSequence)evaluation.value=next
 }catch(error){if(sequence===evaluationSequence){evaluation.value=null;evaluationError.value=error.message||'无法读取可用评估日期';feedback.error(evaluationError.value,{duration:5000})}}
 finally{if(sequence===evaluationSequence)evaluationLoading.value=false}
}
function applyEvaluationWindow(suggestion){if(!suggestion.ready)return;range.value.startDate=suggestion.startDate;range.value.endDate=suggestion.endDate}
async function refreshChoices(){[universes.value,factorSets.value]=await Promise.all([quant.list('universes'),quant.list('factors')])}
async function load(){
 parameterCatalog.value=await quant.list('parameter-catalog');
 [universes.value,factorSets.value]=await Promise.all([quant.list('universes'),quant.list('factors')])
 if(isNew.value){form.value={name:'',universeId:'',factorSetId:'',config:effectiveConfig({strategyType:'TREND'})};versions.value=[];tasks.value=[];return}
 const [strategy,vs,training,backtests]=await Promise.all([quant.get('strategies',route.params.id),quant.versions('strategies',route.params.id),quant.list('training-runs'),quant.list('backtests')])
 form.value=clone(strategy);form.value.config=effectiveConfig(form.value.config||{strategyType:'TREND'});versions.value=vs;tasks.value=[...training.map(t=>({...t,resource:'training-runs'})),...backtests.map(t=>({...t,resource:'backtests'}))];await loadEvaluationWindow()
}
function save(){run(async()=>{const payload=clone(form.value);payload.config=effectiveConfig(payload.config);if(!payload.factorSetId)delete payload.factorSetId;const saved=isNew.value?await quant.create('strategies',payload):await quant.update('strategies',form.value.id,payload);await router.replace({path:`/quant/strategies/${saved.id}`,query:{tab:payload.config.strategyType.startsWith('ML_')?'training':'backtests'}});await load()})}
function start(resource){run(async()=>{const task=await quant.create(resource,{strategyId:form.value.id,...range.value,modelTaskId:range.value.modelTaskId||undefined});await router.push({path:'/quant/tasks',query:{type:resource,id:task.id}})})}
onMounted(()=>{if(tab.value==='versions'){historyOpen.value=true;tab.value='config'}loadLatest(load)});watch(()=>route.params.id,()=>loadLatest(load));watch(ml,value=>{if(!value&&tab.value==='training')tab.value='backtests'});watch(()=>[tab.value,form.value?.universeId,range.value.startDate,range.value.endDate],()=>loadEvaluationWindow())
</script>
<template>
 <template v-if="form">
 <QuantPageHeader :title="isNew?'创建策略':form.name">
 <template #back><Button variant="ghost" size="sm" as-child><RouterLink to="/quant"><ArrowLeft />返回策略列表</RouterLink></Button></template>
 <template v-if="!isNew" #badges><QuantStatusBadge :status="form.status" /><Badge variant="outline">v{{form.revision}}</Badge></template>
 <template #actions><Button v-if="!isNew" variant="outline" @click="historyOpen=true">历史版本</Button><Button v-if="!isNew&&tab==='config'" variant="outline" @click="tab='backtests'">回测</Button><Button v-if="tab==='config'||isNew" type="submit" form="strategy-config" :disabled="busy||form.status==='ARCHIVED'">{{isNew?'创建策略':'保存配置'}}</Button></template>
 </QuantPageHeader>
 <div v-if="!isNew" class="quant-tabs"><Button v-for="[key,title] in tabs" :key="key" :variant="tab===key?'secondary':'ghost'" @click="tab=key">{{title}}</Button></div>
 <form v-if="tab==='config'||isNew" id="strategy-config" @submit.prevent="save"><fieldset :disabled="busy||form.status==='ARCHIVED'" class="quant-config-layout"><div><section class="panel"><h3>基础配置</h3><div class="fields"><label class="field">策略名称<Input v-model.trim="form.name" required maxlength="120" /></label><div class="quant-field-stack"><label class="field">资产池<select v-model="form.universeId" required @change="form.factorSetId='' "><option value="" disabled>请选择</option><option v-for="u in universes.filter(u=>u.status!=='ARCHIVED'||u.id===form.universeId)" :key="u.id" :value="u.id">{{u.name}} · {{label(u.assetClass)}}</option></select></label><div class="quant-field-actions"><Button type="button" variant="outline" size="sm" as-child><RouterLink to="/quant/universes" target="_blank" rel="noopener">管理资产池<ExternalLink /></RouterLink></Button><Button type="button" size="sm" variant="ghost" @click="run(refreshChoices)">刷新选项</Button></div></div></div>
 <h3 class="mt-6">策略规则</h3><div class="fields"><label class="field">策略模板<select v-model="form.config.strategyType"><option value="TREND">趋势：均线过滤 + 波动率仓位</option><option value="MULTI_FACTOR">多因子：加权评分 + 排名</option><option value="ML_ELASTIC_NET">机器学习：Elastic Net</option><option value="ML_XGBOOST">机器学习：XGBoost</option></select></label><label v-if="form.config.strategyType!=='TREND'" class="field">因子组合<select v-model="form.factorSetId" required><option value="" disabled>选择兼容的因子组合</option><option v-for="f in compatibleSets" :key="f.id" :value="f.id">{{f.name}} · v{{f.revision}}</option></select></label></div><p v-if="form.config.strategyType!=='TREND'" class="muted mt-3"><Button type="button" variant="outline" size="sm" as-child><RouterLink to="/quant/factors" target="_blank" rel="noopener">管理因子组合<ExternalLink /></RouterLink></Button></p>
 </section>
 <section v-for="group in parameterGroups" :key="group.title" class="panel"><h3>{{group.title}}</h3><div class="fields"><label v-for="[key,title] in visibleParameters.filter(([key])=>group.keys.includes(key))" :key="key" class="field">{{parameterTitle(key,title)}}<Input :model-value="parameterValue(key)" @update:model-value="value=>setParameter(key,value)" type="number" v-bind="parameterConstraints(key)" /></label></div></section>
 <details class="panel"><summary>高级设置</summary><p v-if="selectedUniverse?.assetClass==='FUND'" class="muted mb-4">未知申赎费率、公布和到账期限按模拟假设处理。</p><div class="fields py-3"><label v-for="[key,title] in visibleParameters.filter(([key])=>advancedKeys.includes(key))" :key="key" class="field">{{parameterTitle(key,title)}}<Input :model-value="parameterValue(key)" @update:model-value="value=>setParameter(key,value)" type="number" v-bind="parameterConstraints(key)" :placeholder="String(defaults[key] ?? '')" /></label></div></details></div>
 <aside class="quant-summary panel"><h3>策略摘要</h3><dl class="text-sm">
 <div><dt class="muted">资产池</dt><dd>{{selectedUniverse?.name||'尚未选择'}}</dd></div>
 <div><dt class="muted">策略模板</dt><dd>{{label(form.config.strategyType)}}</dd></div>
 <div><dt class="muted">最大持仓</dt><dd>{{parameterValue('topN')}} 个标的</dd></div>
 <div><dt class="muted">调仓周期</dt><dd>{{parameterValue('rebalanceDays')}} 个交易日</dd></div>
 <div><dt class="muted">回撤控制阈值</dt><dd>{{parameterValue('maxDrawdown')}}%</dd></div>
 <div><dt class="muted">初始资金</dt><dd>{{Number(parameterValue('initialCash')).toLocaleString('zh-CN')}}</dd></div>
 </dl><p class="muted mt-5 leading-relaxed">{{templateSummary}}</p><details class="mt-3"><summary class="text-sm">查看详细配置</summary><pre>{{JSON.stringify(form.config,null,2)}}</pre></details></aside>
 </fieldset></form>

 <template v-else-if="tab==='training'||tab==='backtests'"><form class="panel" @submit.prevent="start(tab==='training'?'training-runs':'backtests')"><h3>{{tab==='training'?'启动训练':'启动回测'}}</h3><p v-if="ml&&tab==='backtests'" class="muted mb-3">回测开始日期须晚于模型最终留出截止日。</p><div v-if="tab==='backtests'" class="quant-backtest-period"><p v-if="evaluationLoading" class="muted mt-2">正在计算共同有效日期…</p><p v-else-if="evaluationError" class="error mt-2">{{evaluationError}}</p><template v-else-if="evaluation"><div class="toolbar mt-3"><Button v-for="suggestion in evaluation.suggestions" :key="suggestion.days" type="button" size="sm" variant="outline" :disabled="!suggestion.ready||busy" @click="applyEvaluationWindow(suggestion)">近 {{suggestion.days}} 个交易日{{suggestion.ready?'':`（仅有 ${suggestion.availableEvaluationDays} 日`+'）'}}</Button></div><p class="muted">最新共同可用日期：{{evaluation.latestAvailableDate||'暂无'}}；当前最多 {{evaluation.availableEvaluationDays}} 个有效交易日。</p><p v-if="hasSelectedRange" :class="insufficientEvaluation?'error mt-2':'muted mt-2'">当前所选区间：{{evaluation.selectedEvaluationDays||0}} 个共同有效交易日{{insufficientEvaluation?`，不足 ${evaluation.minimumEvaluationDays} 日，不能启动回测。`:'，可以启动回测。'}}</p></template></div><div class="fields"><label class="field">开始日期<Input v-model="range.startDate" type="date" required :max="range.endDate||undefined" /></label><label class="field">结束日期<Input v-model="range.endDate" type="date" required :min="range.startDate||undefined" /></label><label v-if="ml&&tab==='backtests'" class="field">训练产物<select v-model="range.modelTaskId" required><option value="" disabled>选择已完成训练</option><option v-for="t in trained" :key="t.id" :value="t.id">{{t.name||t.id}} · 最终留出截止 {{t.result?.metrics?.evaluatedThrough||t.result?.metrics?.holdoutEnd||"见训练报告"}}</option></select></label></div><Button class="mt-4" type="submit" :disabled="busy||evaluationLoading||!!evaluationError||insufficientEvaluation||form.status==='ARCHIVED'||(tab==='training'&&!ml)">{{tab==='training'?'启动训练':'启动回测'}}</Button><p v-if="tab==='training'&&!ml" class="muted mt-3">规则策略无需训练，直接运行回测。</p></form><div class="panel"><h3>任务记录</h3><div v-if="tab==='backtests'" class="toolbar"><label class="field">回测来源版本<select v-model="versionSelection" aria-label="筛选策略版本"><option value="current">当前版本（v{{form.revision}}）</option><option value="all">全部版本</option><option v-for="v in versions" :key="v.id" :value="v.id">v{{v.version}} · {{formatTime(v.createdAt)}}</option></select></label></div><details v-if="tab==='backtests'&&selectedVersion" class="quant-secondary mb-4"><summary>查看策略冻结版本 v{{selectedVersion.version}}</summary><p class="muted">以下为历史快照；上方启动回测使用当前已保存配置。</p><pre>{{JSON.stringify(selectedVersion,null,2)}}</pre></details><p v-if="!displayedTasks.length" class="muted mb-3">{{tab==='backtests'&&versionSelection==='current'?'当前版本暂无回测':'该范围暂无任务记录'}}</p><div class="table-wrap"><Table><TableHeader><TableRow><TableHead>任务</TableHead><TableHead v-if="tab==='backtests'">策略版本</TableHead><TableHead>状态</TableHead><TableHead>创建时间</TableHead><TableHead>结果</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="t in displayedTasks" :key="t.id"><TableCell>{{t.name||t.id}}</TableCell><TableCell v-if="tab==='backtests'"><Button variant="ghost" size="sm" :disabled="!versions.some(v=>v.id===t.strategyVersionId)" @click="versionSelection=t.strategyVersionId">{{versionLabel(t.strategyVersionId)}}</Button></TableCell><TableCell><QuantStatusBadge :status="t.status" /></TableCell><TableCell>{{formatTime(t.createdAt)}}</TableCell><TableCell><Button size="sm" variant="outline" as-child><RouterLink :to="{path:'/quant/tasks',query:{type:t.resource,id:t.id}}">查看进度 / 报告</RouterLink></Button></TableCell></TableRow></TableBody></Table></div></div></template>
 <Dialog v-model:open="historyOpen"><DialogContent class="qw max-h-[88vh] overflow-y-auto sm:max-w-[960px]" :aria-describedby="undefined"><DialogHeader><DialogTitle>历史版本</DialogTitle></DialogHeader><div class="px-1 pb-1"><DataTable :rows="versions" /></div></DialogContent></Dialog>
 </template><p v-else class="empty">{{busy?'加载中…':'策略不可用'}}</p>
</template>
