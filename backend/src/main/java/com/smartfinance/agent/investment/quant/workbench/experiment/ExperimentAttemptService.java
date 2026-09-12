package com.smartfinance.agent.investment.quant.workbench.experiment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.*;

@Service
public class ExperimentAttemptService {
    private final JdbcTemplate db;private final ExperimentRepository repository;private final ExperimentTaskStore tasks;private final TransactionTemplate tx;
    public ExperimentAttemptService(JdbcTemplate db,ExperimentRepository repository,ExperimentTaskStore tasks,PlatformTransactionManager manager) {this.db=db;this.repository=repository;this.tasks=tasks;tx=new TransactionTemplate(manager);}
    public ExperimentModels.AttemptView appendAttempt(Long user,String runId,String reason) {
        return tx.execute(status->{
            var run=repository.run(user,runId);var experiment=repository.experiment(user,run.experimentId());
            db.update("UPDATE quant_v2_experiment SET revision=revision WHERE id=? AND user_id=?",experiment.id(),user);
            run=repository.run(user,runId);
            if(run.activeAttemptId()!=null) {
                var active=repository.attempt(user,run.activeAttemptId());
                var taskStatus=db.queryForObject("SELECT status FROM quant_v2_task WHERE id=? AND user_id=?",String.class,active.taskId(),user);
                require(Set.of("FAILED","CANCELLED").contains(taskStatus),"EXPERIMENT_ATTEMPT_NOT_RETRYABLE");
            }
            require(reason!=null && !reason.isBlank() && reason.length()<=32,"INVALID_ATTEMPT_REASON");
            int number=repository.attempts(user,runId).stream().mapToInt(ExperimentModels.AttemptView::attemptNo).max().orElse(0)+1;
            String id=UUID.randomUUID().toString();
            var request=Map.<String,Object>of("kind","BACKTEST","executionInput",Map.of("type","EXPERIMENT_RUN","experimentId",experiment.id(),"experimentRunId",run.id(),"attemptId",id,"snapshotId",experiment.snapshotId(),"invariantHash",experiment.invariantHash()));
            String task=tasks.enqueueBacktest(user,experiment.name(),experiment.strategyId(),experiment.strategyVersionId(),experiment.universeId(),request);
            repository.insertAttempt(new ExperimentModels.AttemptDraft(id,user,runId,number,reason,task));repository.activateAttempt(user,runId,id);
            var previous=map(repository.experiment(user,experiment.id()).summary());
            var history=new ArrayList<Object>();
            if(previous.get("history") instanceof List<?> values)history.addAll(values);
            if(previous.containsKey("classification")){previous.remove("history");history.add(previous);}
            db.update("UPDATE quant_v2_experiment SET status='QUEUED',summary_json=?,completed_at=NULL,cancelled_at=NULL,revision=revision+1,updated_at=? WHERE id=? AND user_id=?",encode(Map.of("history",history)),java.time.Instant.now().toString(),experiment.id(),user);
            return repository.attempt(user,id);
        });
    }
}
