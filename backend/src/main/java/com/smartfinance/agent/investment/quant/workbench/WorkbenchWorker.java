package com.smartfinance.agent.investment.quant.workbench;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import com.smartfinance.agent.investment.quant.workbench.experiment.*;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;
import static com.smartfinance.agent.investment.quant.workbench.WorkbenchService.*;

@Component
public class WorkbenchWorker {
    private static final Logger log=LoggerFactory.getLogger(WorkbenchWorker.class);
    private final WorkbenchService s;
    private final WorkbenchAnalysisClient client;
    private final long leaseMs;
    private ExperimentExecutionResolver resolver;
    private ExperimentResultValidator validator;
    private ExperimentSummaryService summaries;
    @Autowired public void configureExperiments(ExperimentExecutionResolver resolver,ExperimentResultValidator validator,ExperimentSummaryService summaries){this.resolver=resolver;this.validator=validator;this.summaries=summaries;}
    public WorkbenchWorker(WorkbenchService service,WorkbenchAnalysisClient client) {
        s=service;this.client=client;leaseMs=client.leaseMillis();
    }
    Map<String,Object> execute(Map<String,Object> request){var response=client.execute(request);if(!"SUCCEEDED".equals(response.get("status")))throw new EngineFailure(str(response.getOrDefault("errorCode","ENGINE_FAILED")),str(response.getOrDefault("errorMessage","分析任务执行失败")));return response;}
    @Scheduled(scheduler="quantWorkbenchScheduler", fixedDelayString="${quant.workbench.poll-ms:3000}") public void tasks(){
        try {var rows=s.db.queryForList("SELECT * FROM quant_v2_task WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<?) ORDER BY created_at LIMIT 1",System.currentTimeMillis());if(rows.isEmpty())return;var task=rows.get(0);String identity=str(task.get("id")),claim=id();int claimed=s.db.update("UPDATE quant_v2_task SET status='RUNNING',stage='EXECUTING',claim_token=?,lease_until=?,updated_at=? WHERE id=? AND (status='QUEUED' OR (status='RUNNING' AND lease_until<?))",claim,System.currentTimeMillis()+leaseMs,now(),identity,System.currentTimeMillis());if(claimed!=1)return;
            if(summaries!=null)try{summaries.taskChanged(((Number)task.get("user_id")).longValue(),identity);}catch(Exception e){log.error("参数敏感性状态投影失败",e);}
            try {Long user=((Number)task.get("user_id")).longValue();
                var input=resolver==null?new ExperimentExecutionResolver.Resolved(s.decode(task.get("request_json")),null,null,null):resolver.resolve(user,identity,s.decode(task.get("request_json")));
                var result=execute(input.payload());
                if(input.experiment()!=null){result=new LinkedHashMap<>(result);result.put("experimentValidation",validator.validate(input,result));}s.db.update("UPDATE quant_v2_task SET status='SUCCEEDED',stage='COMPLETED',result_json=?,error_code=NULL,error_message=NULL,lease_until=NULL,claim_token=NULL,updated_at=? WHERE id=? AND claim_token=? AND status='RUNNING'",s.encode(result),now(),identity,claim);}catch(Exception e){var error=error(e);s.db.update("UPDATE quant_v2_task SET status='FAILED',stage='FAILED',error_code=?,error_message=?,lease_until=NULL,claim_token=NULL,updated_at=? WHERE id=? AND claim_token=? AND status='RUNNING'",error.get("code"),error.get("message"),now(),identity,claim);}
            if(summaries!=null)try{summaries.taskChanged(((Number)task.get("user_id")).longValue(),identity);}catch(Exception e){log.error("参数敏感性汇总失败，任务原始结果已保留",e);}
        }catch(Exception e){log.error("量化工作台任务调度失败",e);}
    }
    @Scheduled(scheduler="quantWorkbenchScheduler", fixedDelayString="${quant.workbench.paper-poll-ms:30000}") public void paper(){
        try {var rows=s.db.queryForList("SELECT * FROM quant_v2_deployment WHERE status IN ('RUNNING','PAUSED','STOPPING') AND next_run_at<=? AND (claim_token IS NULL OR lease_until<?) ORDER BY next_run_at LIMIT 1",System.currentTimeMillis(),System.currentTimeMillis());if(rows.isEmpty())return;var deployment=rows.get(0);String identity=str(deployment.get("id")),claim=id();int revision=((Number)deployment.get("revision")).intValue();if(s.db.update("UPDATE quant_v2_deployment SET claim_token=?,lease_until=? WHERE id=? AND revision=? AND (claim_token IS NULL OR lease_until<?)",claim,System.currentTimeMillis()+leaseMs,identity,revision,System.currentTimeMillis())!=1)return;
            try {Long user=((Number)deployment.get("user_id")).longValue();var request=s.decode(deployment.get("request_json"));var previous=s.decode(deployment.get("result_json"));request.put("state",previous.getOrDefault("state",request.get("state")));String end=LocalDate.now().toString();request.put("endDate",end);request.put("assets",s.snapshot(user,map(request.get("universe")),end));String status=str(deployment.get("status"));request.put("action","PAUSED".equals(status)?"PAUSE":"STOPPING".equals(status)?"LIQUIDATE":"RUN");var response=execute(request);
                s.tx.execute(tx->{String next=status;if("STOPPING".equals(status)&&Boolean.TRUE.equals(map(response.get("state")).get("liquidated")))next="STOPPED";int saved=s.db.update("UPDATE quant_v2_deployment SET revision=revision+1,result_json=?,status=?,error_code=NULL,error_message=NULL,claim_token=NULL,lease_until=NULL,next_run_at=?,updated_at=? WHERE id=? AND revision=? AND claim_token=?",s.encode(response),next,System.currentTimeMillis()+60000,now(),identity,revision,claim);if(saved==1){var result=map(response.get("result"));for(String key:List.of("orders","fills","cashLedger")){Object list=result.get(key);if(list instanceof List<?> events)for(Object event:events)append(user,identity,key,map(event));}}return null;});
            }catch(Exception e){var error=error(e);s.db.update("UPDATE quant_v2_deployment SET error_code=?,error_message=?,claim_token=NULL,lease_until=NULL,next_run_at=?,updated_at=? WHERE id=? AND revision=? AND claim_token=?",error.get("code"),error.get("message"),System.currentTimeMillis()+60000,now(),identity,revision,claim);}
        }catch(Exception e){log.error("量化模拟组合调度失败",e);}
    }
    boolean emptyPositions(Map<String,Object> state){if(state.isEmpty())return false;for(Object value:map(state.get("positions")).values()){double quantity=value instanceof Number n?n.doubleValue():((Number)map(value).getOrDefault("quantity",0)).doubleValue();if(quantity>1e-8)return false;}return !(state.get("pendingOrders") instanceof List<?> pending)||pending.isEmpty();}
    void append(Long user,String deployment,String kind,Map<String,Object> payload){String value=s.encode(payload);String key=kind+":"+UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));if(s.db.queryForObject("SELECT COUNT(*) FROM quant_v2_paper_event WHERE deployment_id=? AND event_key=?",Integer.class,deployment,key)==0)s.db.update("INSERT INTO quant_v2_paper_event(id,user_id,deployment_id,event_key,kind,payload,created_at) VALUES(?,?,?,?,?,?,?)",id(),user,deployment,key,kind,value,now());}
    Map<String,String> error(Exception e){if(e instanceof ExperimentInvariant.ExperimentException x)return Map.of("code",x.code(),"message",x.getMessage());if(e instanceof ResearchSnapshotStore.SnapshotException x)return Map.of("code",x.code(),"message",x.getMessage());if(e instanceof EngineFailure f)return Map.of("code",f.code,"message",f.getMessage());if(e instanceof RestClientResponseException http){try{var body=s.decode(http.getResponseBodyAsString());var detail=map(body.get("detail"));return Map.of("code",str(detail.getOrDefault("code","ANALYSIS_HTTP_"+http.getStatusCode().value())),"message",str(detail.getOrDefault("message",body.getOrDefault("detail","分析服务请求失败"))));}catch(Exception ignored){return Map.of("code","ANALYSIS_HTTP_"+http.getStatusCode().value(),"message","分析服务返回错误，请检查服务日志");}}return Map.of("code","ANALYSIS_EXECUTION_FAILED","message",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
    static class EngineFailure extends RuntimeException{final String code;EngineFailure(String code,String message){super(message);this.code=code;}}
}
