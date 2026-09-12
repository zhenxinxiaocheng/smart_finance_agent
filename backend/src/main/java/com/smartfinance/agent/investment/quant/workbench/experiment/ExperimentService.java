package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.smartfinance.agent.investment.quant.workbench.WorkbenchAnalysisClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import java.util.*;
import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.*;

@Service
public class ExperimentService {
    private final JdbcTemplate db;
    private final ExperimentRepository repository;
    private final ResearchSnapshotStore snapshots;
    private final ExperimentCandidateService candidates;
    private final WorkbenchAnalysisClient client;
    private final ExperimentAttemptService attempts;
    private final TransactionTemplate tx;
    public ExperimentService(JdbcTemplate db,ExperimentRepository repository,ResearchSnapshotStore snapshots,
            ExperimentCandidateService candidates,WorkbenchAnalysisClient client,ExperimentAttemptService attempts,PlatformTransactionManager manager) {
        this.db=db;this.repository=repository;this.snapshots=snapshots;this.candidates=candidates;this.client=client;this.attempts=attempts;tx=new TransactionTemplate(manager);
    }
    @SuppressWarnings("unchecked")
    public ExperimentModels.ExperimentView create(Long user,String sourceId,String key,String name,String requestKey) {
        require(user!=null && sourceId!=null && key!=null && requestKey!=null && !requestKey.isBlank() && requestKey.length()<=80,"INVALID_EXPERIMENT_REQUEST");
        require(name!=null && !name.isBlank() && name.length()<=160,"INVALID_EXPERIMENT_REQUEST");
        String requestHash=hash(Map.of("sourceBacktestId",sourceId,"parameterKey",key,"name",name));
        var existing=repository.byRequest(user,requestKey);
        if(existing!=null)return idempotent(existing,requestHash);
        var source=task(user,sourceId);
        require("backtests".equals(source.get("kind")) && "SUCCEEDED".equals(source.get("status")) && !repository.isExperimentTask(user,sourceId),"INVALID_EXPERIMENT_SOURCE");
        var original=decode(source.get("request_json")); var response=decode(source.get("result_json"));
        var result=map(response.get("result")); var provenance=map(result.get("provenance")); var config=map(provenance.get("config"));
        require(!config.isEmpty() && config.containsKey(key) && original.get("assets") instanceof List<?> && !((List<?>)original.get("assets")).isEmpty(),"SOURCE_CONTEXT_INCOMPLETE");
        for(String field:List.of("startDate","endDate","engineVersion","codeHash","dataHash")) require(provenance.get(field)!=null && !provenance.get(field).toString().isBlank(),"SOURCE_CONTEXT_INCOMPLETE");
        require(result.get("assumptions") instanceof List<?> && !benchmark(result.get("benchmark")).isEmpty(),"SOURCE_CONTEXT_INCOMPLETE");
        String strategy=string(source.get("strategy_id")), sv=string(source.get("strategy_version_id")), universe=string(source.get("universe_id"));
        String uv=string(original.get("universeVersionId")), fv=string(original.get("factorVersionId"));
        version(user,sv,strategy,"strategies"); version(user,uv,universe,"universes");
        String factor=fv==null?null:version(user,fv,null,"factors");
        var strategyPayload=decode(db.queryForObject("SELECT payload FROM quant_v2_version WHERE user_id=? AND id=?",String.class,user,sv));
        require(same(strategyPayload.get("factorSetId"),factor),"SOURCE_CONTEXT_CONFLICT");
        require(same(original.get("strategyVersionId"),sv) && same(original.get("universeId"),universe) && same(provenance.get("universeId"),universe),"SOURCE_CONTEXT_CONFLICT");
        if(config.getOrDefault("strategyType","").toString().startsWith("ML_"))require(original.get("modelRef")!=null && original.get("modelTaskId")!=null,"SOURCE_CONTEXT_INCOMPLETE");
        if(provenance.containsKey("modelRef"))require(same(provenance.get("modelRef"),original.get("modelRef")),"SOURCE_CONTEXT_CONFLICT");
        if(original.get("modelTaskId")!=null) {
            var model=task(user,original.get("modelTaskId").toString());
            require("training-runs".equals(model.get("kind")) && "SUCCEEDED".equals(model.get("status")) && same(decode(model.get("result_json")).get("modelRef"),original.get("modelRef")),"SOURCE_CONTEXT_CONFLICT");
        }
        require(same(provenance.get("strategyVersionId"),sv) && same(provenance.get("universeVersion"),uv)
                && same(provenance.get("factorSetVersionId"),fv),"SOURCE_CONTEXT_CONFLICT");
        var environment=client.runtimeInfo();
        for(String field:List.of("engineVersion","codeHash","parameterCatalogVersion","candidateRuleVersion"))
            require(environment.get(field)!=null && !environment.get(field).toString().isBlank(),"EXPERIMENT_ENVIRONMENT_CHANGED");
        var assets=(List<Map<String,Object>>)original.get("assets");
        var compatibilityRequest=map(original);compatibilityRequest.put("kind","BACKTEST");compatibilityRequest.put("config",config);
        compatibilityRequest.put("startDate",provenance.get("startDate"));compatibilityRequest.put("endDate",provenance.get("endDate"));
        compatibilityRequest.remove("state");compatibilityRequest.remove("action");compatibilityRequest.remove("expectedRuntime");
        var compatibility=client.validateConfig(compatibilityRequest);
        require(Boolean.TRUE.equals(compatibility.get("compatible")) && same(config,compatibility.get("effectiveConfig")),"CURRENT_RUNTIME_CONFIG_INCOMPATIBLE");
        require(same(environment,compatibility.get("runtime")),"EXPERIMENT_ENVIRONMENT_CHANGED");
        int effectiveCount=(int)assets.stream().filter(a->a.get("id")!=null && a.get("bars") instanceof List<?> bars && !bars.isEmpty()).map(a->a.get("id")).distinct().count();
        var generated=candidates.generate(config,key,effectiveCount,environment);
        require(same(environment,client.runtimeInfo()),"EXPERIMENT_ENVIRONMENT_CHANGED");
        var base=map(original); base.remove("assets");base.remove("state");base.remove("action");base.put("kind","BACKTEST");base.put("config",config);
        base.put("startDate",provenance.get("startDate"));base.put("endDate",provenance.get("endDate"));base.remove("expectedRuntime");
        Map<String,Object> context=new LinkedHashMap<>();context.put("provenance",provenance);context.put("runtime",runtime(provenance));
        context.put("benchmarkContract",benchmark(result.get("benchmark")));context.put("assumptions",result.get("assumptions"));
        try {
            return tx.execute(status->{
                var duplicate=repository.byRequest(user,requestKey);if(duplicate!=null)return idempotent(duplicate,requestHash);
                var snapshot=snapshots.put(user,assets); context.put("snapshotContentHash",snapshot.contentHash());
                String id=UUID.randomUUID().toString();
                var invariant=calculate(base,environment,snapshot.contentHash(),key,context.get("benchmarkContract"),context.get("assumptions"));
                repository.insertExperiment(new ExperimentModels.ExperimentDraft(id,user,name,"QUEUED",sourceId,strategy,sv,universe,uv,factor,fv,snapshot.id(),
                        string(base.get("modelRef")),string(base.get("modelTaskId")),Map.of("key",key),Map.of(key,generated.get("baseline")),generated.get("values"),base,context,environment,
                        generated.get("ruleVersion").toString(),StabilityAnalyzer.VERSION,invariant,requestKey,requestHash));
                var values=(List<?>)generated.get("values");
                for(int i=0;i<values.size();i++) {
                    String runId=UUID.randomUUID().toString();var variable=Map.<String,Object>of(key,values.get(i));
                    repository.insertRun(new ExperimentModels.RunDraft(runId,user,id,i,variable,hash(variable),i==2));
                    attempts.appendAttempt(user,runId,"INITIAL");
                }
                return repository.experiment(user,id);
            });
        } catch(DuplicateKeyException collision) {
            var winner=repository.byRequest(user,requestKey); if(winner!=null)return idempotent(winner,requestHash);throw collision;
        }
    }
    private ExperimentModels.ExperimentView idempotent(ExperimentModels.ExperimentView value,String hash) { require(hash.equals(value.requestHash()),"EXPERIMENT_REQUEST_CONFLICT");return value; }
    private Map<String,Object> task(Long user,String id) {
        var rows=db.queryForList("SELECT * FROM quant_v2_task WHERE user_id=? AND id=?",user,id);require(!rows.isEmpty(),"SOURCE_BACKTEST_NOT_FOUND");return rows.get(0);
    }
    private String version(Long user,String id,String object,String kind) {
        require(id!=null,"SOURCE_CONTEXT_INCOMPLETE");
        var rows=db.queryForList("SELECT v.object_id FROM quant_v2_version v JOIN quant_v2_object o ON o.id=v.object_id AND o.user_id=v.user_id WHERE v.user_id=? AND v.id=? AND o.kind=?",String.class,user,id,kind);
        require(rows.size()==1 && (object==null || object.equals(rows.get(0))),"SOURCE_CONTEXT_CONFLICT");return rows.get(0);
    }
    private static String string(Object value) {return value==null?null:value.toString();}
    private static Map<String,Object> runtime(Map<String,Object> p) {var result=new LinkedHashMap<String,Object>();for(String key:List.of("engineVersion","codeHash","parameterCatalogVersion","candidateRuleVersion"))if(p.get(key)!=null)result.put(key,p.get(key));return result;}
}
