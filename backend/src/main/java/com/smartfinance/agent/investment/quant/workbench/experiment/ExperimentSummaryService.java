package com.smartfinance.agent.investment.quant.workbench.experiment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.*;

@Service
public class ExperimentSummaryService {
    private final JdbcTemplate db;private final ExperimentRepository repository;private final ExperimentResultValidator validator;private final TransactionTemplate tx;
    public ExperimentSummaryService(JdbcTemplate db,ExperimentRepository repository,ExperimentResultValidator validator,PlatformTransactionManager manager){this.db=db;this.repository=repository;this.validator=validator;tx=new TransactionTemplate(manager);}
    public void taskChanged(Long user,String taskId) {
        var attempt=repository.taskAttempt(user,taskId);if(attempt!=null)refresh(user,repository.run(user,attempt.runId()).experimentId());
    }
    public Map<String,Object> refresh(Long user,String experimentId) {
        return tx.execute(status->{
            repository.experiment(user,experimentId);
            db.update("UPDATE quant_v2_experiment SET revision=revision WHERE user_id=? AND id=?",user,experimentId);
            var experiment=repository.experiment(user,experimentId);var runs=repository.runs(user,experimentId);
            List<String> statuses=new ArrayList<>(),ids=new ArrayList<>();Map<Integer,Map<String,Object>> responses=new TreeMap<>();
            Map<Integer,String> taskErrors=new TreeMap<>();
            for(var run:runs) {
                var attempt=repository.attempt(user,run.activeAttemptId());ids.add(attempt.id());
                var task=db.queryForMap("SELECT status,result_json,error_code FROM quant_v2_task WHERE id=? AND user_id=?",attempt.taskId(),user);
                statuses.add(task.get("status").toString());responses.put(run.ordinal(),decode(task.get("result_json")));
                if(task.get("error_code")!=null)taskErrors.put(run.ordinal(),task.get("error_code").toString());
            }
            String state=new ExperimentStateProjector().project(statuses);
            var previous=map(experiment.summary());
            if(previous.containsKey("classification") && same(previous.get("inputAttemptIds"),ids))return previous;
            db.update("UPDATE quant_v2_experiment SET status=?,updated_at=? WHERE user_id=? AND id=?",state,Instant.now().toString(),user,experimentId);
            if(Set.of("QUEUED","RUNNING").contains(state))return Map.of("status",state);
            require(StabilityAnalyzer.VERSION.equals(experiment.stabilityAlgorithmVersion()),"STABILITY_ALGORITHM_UNAVAILABLE");
            List<StabilityAnalyzer.Point> points=new ArrayList<>();TreeSet<String> reasons=new TreeSet<>(taskErrors.values());Map<String,Integer> qualificationReasons=new TreeMap<>();
            int qualified=0,unqualified=0,missing=0;boolean controls=!taskErrors.containsValue("CONTROL_VARIABLE_VIOLATION") && !taskErrors.containsValue("EXPERIMENT_ENVIRONMENT_CHANGED") && !taskErrors.containsValue("EXPERIMENT_TASK_LINK_INVALID"),data=!taskErrors.containsValue("SNAPSHOT_CORRUPTED");var baseline=responses.getOrDefault(2,Map.of());
            List<Map<String,Object>> exclusions=new ArrayList<>();
            for(int i=0;i<runs.size();i++) {
                var run=runs.get(i);var response=responses.get(run.ordinal());var q=map(response.get("qualification"));
                if("QUALIFIED".equals(q.get("status")))qualified++;else if("UNQUALIFIED".equals(q.get("status")))unqualified++;else missing++;
                if(q.get("reasons") instanceof List<?> list)for(Object code:list){reasons.add(code.toString());qualificationReasons.merge(code.toString(),1,Integer::sum);}
                var validation=map(response.get("experimentValidation"));
                boolean executed="SUCCEEDED".equals(statuses.get(i));boolean valid=executed && Boolean.TRUE.equals(validation.get("valid")) && ids.get(i).equals(validation.get("attemptId"));
                boolean baselinePresent=Boolean.TRUE.equals(map(baseline.get("experimentValidation")).get("valid"));
                boolean consistent=!executed || !baselinePresent || validator.sameData(baseline,response);
                if(executed && !valid)controls=false;
                if(executed && !consistent)data=false;
                var metrics=map(map(response.get("result")).get("metrics"));
                boolean metricValid=finite(metrics.get("netReturn")) && finite(metrics.get("maxDrawdown")) && ((Number)metrics.get("maxDrawdown")).doubleValue()>=0;
                if(valid && consistent && metricValid)points.add(new StabilityAnalyzer.Point(run.ordinal(),run.variableValues().values().iterator().next(),((Number)metrics.get("netReturn")).doubleValue(),((Number)metrics.get("maxDrawdown")).doubleValue(),ids.get(i)));
                else exclusions.add(Map.of("runId",run.id(),"attemptId",ids.get(i),"reason",!executed?statuses.get(i):!valid||!consistent?"CONTROL_VARIABLE_VIOLATION":"RESULT_METRICS_MISSING"));
            }
            var summary=new LinkedHashMap<>(new StabilityAnalyzer().analyze(points,runs.size()));
            var quality=new EvidenceQualityEvaluator().evaluate(controls,data,sourceComplete(experiment.sourceContext()),points.size(),runs.size(),reasons);
            summary.put("evidenceQuality",quality.get("level"));summary.put("evidence",quality);summary.put("qualificationSummary",Map.of("qualified",qualified,"unqualified",unqualified,"missing",missing,"reasons",qualificationReasons));
            summary.put("stabilityAlgorithmVersion",experiment.stabilityAlgorithmVersion());summary.put("candidateRuleVersion",experiment.candidateRuleVersion());
            summary.put("includedAttemptIds",summary.get("inputAttemptIds"));summary.put("inputAttemptIds",ids);summary.put("excludedRuns",exclusions);
            reasons.addAll((List<String>)summary.get("reasonCodes"));if(!controls||!data)reasons.add("CONTROL_VARIABLE_VIOLATION");summary.put("reasonCodes",List.copyOf(reasons));
            if(previous.get("history") instanceof List<?> history)summary.put("history",history);
            db.update("UPDATE quant_v2_experiment SET summary_json=?,status=?,completed_at=?,updated_at=?,revision=revision+1 WHERE user_id=? AND id=?",encode(summary),state,Instant.now().toString(),Instant.now().toString(),user,experimentId);
            return summary;
        });
    }
    private static boolean finite(Object value){return value instanceof Number n&&Double.isFinite(n.doubleValue());}
    private static boolean sourceComplete(Map<String,Object> context) {
        var provenance=map(context.get("provenance"));var runtime=map(context.get("runtime"));
        return List.of("engineVersion","codeHash").stream().allMatch(k->runtime.get(k) instanceof String s&&!s.isBlank())
                && List.of("dataHash","startDate","endDate").stream().allMatch(k->provenance.get(k) instanceof String s&&!s.isBlank())
                && !map(provenance.get("config")).isEmpty() && !map(context.get("benchmarkContract")).isEmpty()
                && context.get("assumptions") instanceof List<?> && context.get("snapshotContentHash") instanceof String s&&!s.isBlank();
    }
}
