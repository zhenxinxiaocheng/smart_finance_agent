package com.smartfinance.agent.investment.quant.workbench.experiment;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.*;

@Component
public class ExperimentExecutionResolver {
    private final ExperimentRepository repository;private final ResearchSnapshotStore snapshots;private final JdbcTemplate db;
    public ExperimentExecutionResolver(ExperimentRepository repository,ResearchSnapshotStore snapshots,JdbcTemplate db) {this.repository=repository;this.snapshots=snapshots;this.db=db;}
    public record Resolved(Map<String,Object> payload,ExperimentModels.ExperimentView experiment,ExperimentModels.RunView run,ExperimentModels.AttemptView attempt) {}
    public Resolved resolve(Long user,String taskId,Map<String,Object> request) {
        var attempt=repository.taskAttempt(user,taskId);var input=map(request.get("executionInput"));
        if(attempt==null) {require(!"EXPERIMENT_RUN".equals(input.get("type")),"EXPERIMENT_TASK_LINK_INVALID");return new Resolved(request,null,null,null);}
        var run=repository.run(user,attempt.runId());var experiment=repository.experiment(user,run.experimentId());
        require("EXPERIMENT_RUN".equals(input.get("type")) && attempt.id().equals(input.get("attemptId"))
                && run.id().equals(input.get("experimentRunId")) && experiment.id().equals(input.get("experimentId"))
                && experiment.snapshotId().equals(input.get("snapshotId")) && experiment.invariantHash().equals(input.get("invariantHash"))
                && attempt.id().equals(run.activeAttemptId()),"EXPERIMENT_TASK_LINK_INVALID");
        var task=db.queryForMap("SELECT strategy_id,strategy_version_id,universe_id,kind FROM quant_v2_task WHERE user_id=? AND id=?",user,taskId);
        require("backtests".equals(task.get("kind")) && Objects.equals(experiment.strategyId(),task.get("strategy_id"))
                && Objects.equals(experiment.strategyVersionId(),task.get("strategy_version_id")) && Objects.equals(experiment.universeId(),task.get("universe_id")),"EXPERIMENT_TASK_LINK_INVALID");
        String key=map(experiment.variableDefinition()).get("key").toString();
        require(run.variableValues().keySet().equals(Set.of(key)) && hash(run.variableValues()).equals(run.valueHash()),"CONTROL_VARIABLE_VIOLATION");
        require(experiment.candidateValues() instanceof List<?> values && run.ordinal()>=0 && run.ordinal()<values.size()
                && same(values.get(run.ordinal()),run.variableValues().get(key)) && run.baseline()==(run.ordinal()==2),"CONTROL_VARIABLE_VIOLATION");
        var context=experiment.sourceContext();
        require(experiment.invariantHash().equals(calculate(experiment.baseRequest(),experiment.environment(),context.get("snapshotContentHash").toString(),key,
                context.get("benchmarkContract"),context.get("assumptions"))),"CONTROL_VARIABLE_VIOLATION");
        var metadata=db.queryForList("SELECT content_hash,format_version FROM quant_v2_research_snapshot WHERE user_id=? AND id=?",user,experiment.snapshotId());
        require(metadata.size()==1 && Objects.equals(metadata.get(0).get("content_hash"),context.get("snapshotContentHash")),"SNAPSHOT_CORRUPTED");
        var payload=decode(encode(experiment.baseRequest()));var config=map(payload.get("config"));config.put(key,run.variableValues().get(key));payload.put("config",config);
        payload.put("assets",snapshots.loadAssets(user,experiment.snapshotId()));payload.put("expectedRuntime",experiment.environment());
        return new Resolved(payload,experiment,run,attempt);
    }
}
