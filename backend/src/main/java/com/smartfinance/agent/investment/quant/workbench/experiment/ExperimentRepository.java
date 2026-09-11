package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class ExperimentRepository {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ExperimentRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public void insertExperiment(ExperimentModels.ExperimentDraft value) {
        String now = Instant.now().toString();
        db.update("INSERT INTO quant_v2_experiment(" +
                        "id,user_id,name,status,source_backtest_id,strategy_id,strategy_version_id," +
                        "universe_id,universe_version_id,factor_id,factor_version_id,snapshot_id,model_ref,model_task_id," +
                        "variable_definition_json,baseline_values_json,candidate_values_json,base_request_json," +
                        "source_context_json,environment_json,candidate_rule_version,stability_algorithm_version," +
                        "invariant_hash,request_key,request_hash,revision,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.userId(), value.name(), value.status(), value.sourceBacktestId(),
                value.strategyId(), value.strategyVersionId(), value.universeId(), value.universeVersionId(),
                value.factorId(), value.factorVersionId(), value.snapshotId(), value.modelRef(), value.modelTaskId(),
                encode(value.variableDefinition()), encode(value.baselineValues()), encode(value.candidateValues()),
                encode(value.baseRequest()), encode(value.sourceContext()), encode(value.environment()),
                value.candidateRuleVersion(), value.stabilityAlgorithmVersion(), value.invariantHash(),
                value.requestKey(), value.requestHash(), 1, now, now);
    }

    public void insertRun(ExperimentModels.RunDraft value) {
        db.update("INSERT INTO quant_v2_experiment_run(" +
                        "id,user_id,experiment_id,ordinal,variable_values_json,value_hash,baseline,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?)",
                value.id(), value.userId(), value.experimentId(), value.ordinal(),
                encode(value.variableValues()), value.valueHash(), value.baseline(), Instant.now().toString());
    }

    public void insertAttempt(ExperimentModels.AttemptDraft value) {
        db.update("INSERT INTO quant_v2_experiment_run_attempt(" +
                        "id,user_id,run_id,attempt_no,reason,task_id,created_at) VALUES(?,?,?,?,?,?,?)",
                value.id(), value.userId(), value.runId(), value.attemptNo(), value.reason(),
                value.taskId(), Instant.now().toString());
    }

    public void activateAttempt(Long userId, String runId, String attemptId) {
        Integer linked = db.queryForObject("SELECT COUNT(*) FROM quant_v2_experiment_run_attempt " +
                "WHERE id=? AND run_id=? AND user_id=?", Integer.class, attemptId, runId, userId);
        if (linked == null || linked != 1 || db.update("UPDATE quant_v2_experiment_run " +
                "SET active_attempt_id=? WHERE id=? AND user_id=?", attemptId, runId, userId) != 1) {
            throw new ExperimentNotFoundException();
        }
    }

    public ExperimentModels.ExperimentView experiment(Long userId, String experimentId) {
        var rows = db.queryForList("SELECT * FROM quant_v2_experiment WHERE id=? AND user_id=?", experimentId, userId);
        if (rows.isEmpty()) throw new ExperimentNotFoundException();
        var row = rows.get(0);
        return new ExperimentModels.ExperimentView(
                string(row, "id"), number(row, "user_id").longValue(), string(row, "name"), string(row, "status"),
                string(row, "source_backtest_id"), string(row, "strategy_id"), string(row, "strategy_version_id"),
                string(row, "universe_id"), string(row, "universe_version_id"), nullable(row, "factor_id"),
                nullable(row, "factor_version_id"), string(row, "snapshot_id"), nullable(row, "model_ref"),
                nullable(row, "model_task_id"), decodeAny(row.get("variable_definition_json")),
                decodeAny(row.get("baseline_values_json")), decodeAny(row.get("candidate_values_json")),
                decodeMap(row.get("base_request_json")), decodeMap(row.get("source_context_json")),
                decodeMap(row.get("environment_json")), string(row, "candidate_rule_version"),
                string(row, "stability_algorithm_version"), string(row, "invariant_hash"),
                decodeNullable(row.get("summary_json")), nullable(row, "request_key"), nullable(row, "request_hash"),
                number(row, "revision").intValue(), string(row, "created_at"), string(row, "updated_at"),
                nullable(row, "completed_at"), nullable(row, "cancelled_at"));
    }

    public ExperimentModels.RunView run(Long userId, String runId) {
        var rows = db.queryForList("SELECT * FROM quant_v2_experiment_run WHERE id=? AND user_id=?", runId, userId);
        if (rows.isEmpty()) throw new ExperimentNotFoundException();
        var row = rows.get(0);
        return new ExperimentModels.RunView(string(row, "id"), number(row, "user_id").longValue(),
                string(row, "experiment_id"), number(row, "ordinal").intValue(),
                decodeMap(row.get("variable_values_json")), string(row, "value_hash"),
                booleanValue(row.get("baseline")), nullable(row, "active_attempt_id"), string(row, "created_at"));
    }

    public List<ExperimentModels.AttemptView> attempts(Long userId, String runId) {
        return db.queryForList("SELECT * FROM quant_v2_experiment_run_attempt " +
                        "WHERE user_id=? AND run_id=? ORDER BY attempt_no", userId, runId).stream()
                .map(row -> new ExperimentModels.AttemptView(string(row, "id"),
                        number(row, "user_id").longValue(), string(row, "run_id"),
                        number(row, "attempt_no").intValue(), string(row, "reason"),
                        string(row, "task_id"), string(row, "created_at")))
                .toList();
    }

    public boolean isExperimentTask(Long userId, String taskId) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM quant_v2_experiment_run_attempt " +
                "WHERE user_id=? AND task_id=?", Integer.class, userId, taskId);
        return count != null && count > 0;
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("实验记录无法序列化", exception);
        }
    }

    private Object decodeAny(Object value) {
        try {
            return json.readValue(String.valueOf(value), Object.class);
        } catch (Exception exception) {
            throw new IllegalStateException("实验记录损坏", exception);
        }
    }

    private Object decodeNullable(Object value) {
        return value == null ? null : decodeAny(value);
    }

    private Map<String, Object> decodeMap(Object value) {
        try {
            return json.readValue(String.valueOf(value), new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("实验记录损坏", exception);
        }
    }

    private static String string(Map<String, Object> row, String key) {
        return String.valueOf(row.get(key));
    }

    private static String nullable(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : String.valueOf(row.get(key));
    }

    private static Number number(Map<String, Object> row, String key) {
        return (Number) row.get(key);
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean flag ? flag : value instanceof Number number && number.intValue() != 0;
    }

    public static final class ExperimentNotFoundException extends RuntimeException {
        ExperimentNotFoundException() {
            super("参数敏感性实验不存在或无权访问");
        }
    }
}
