package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        experiment(value.userId(), value.experimentId());
        db.update("INSERT INTO quant_v2_experiment_run(" +
                        "id,user_id,experiment_id,ordinal,variable_values_json,value_hash,baseline,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?)",
                value.id(), value.userId(), value.experimentId(), value.ordinal(),
                encode(value.variableValues()), value.valueHash(), value.baseline(), Instant.now().toString());
    }

    public void insertAttempt(ExperimentModels.AttemptDraft value) {
        run(value.userId(),value.runId());
        if (db.queryForObject("SELECT COUNT(*) FROM quant_v2_task WHERE id=? AND user_id=?",Integer.class,value.taskId(),value.userId()) != 1) throw new ExperimentNotFoundException();
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
        run(userId,runId);
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

    public ExperimentModels.ExperimentView byRequest(Long userId,String key) {
        var ids=db.queryForList("SELECT id FROM quant_v2_experiment WHERE user_id=? AND request_key=?",String.class,userId,key);
        return ids.isEmpty()?null:experiment(userId,ids.get(0));
    }
    public List<ExperimentModels.RunView> runs(Long userId,String experimentId) {
        experiment(userId,experimentId);
        return db.queryForList("SELECT id FROM quant_v2_experiment_run WHERE user_id=? AND experiment_id=? ORDER BY ordinal",String.class,userId,experimentId)
                .stream().map(id->run(userId,id)).toList();
    }
    public ExperimentModels.AttemptView attempt(Long userId,String attemptId) {
        var rows=db.queryForList("SELECT run_id FROM quant_v2_experiment_run_attempt WHERE user_id=? AND id=?",String.class,userId,attemptId);
        if(rows.isEmpty())throw new ExperimentNotFoundException();
        return attempts(userId,rows.get(0)).stream().filter(a->a.id().equals(attemptId)).findFirst().orElseThrow(ExperimentNotFoundException::new);
    }
    public ExperimentModels.AttemptView taskAttempt(Long userId,String taskId) {
        var rows=db.queryForList("SELECT id FROM quant_v2_experiment_run_attempt WHERE user_id=? AND task_id=?",String.class,userId,taskId);
        return rows.isEmpty()?null:attempt(userId,rows.get(0));
    }

    public List<Map<String, Object>> productExperiments(
            Long userId, String strategyId, String sourceBacktestId, String status) {
        var sql = new StringBuilder("SELECT id,name,status,source_backtest_id,strategy_id,strategy_version_id," +
                "variable_definition_json,baseline_values_json,candidate_values_json,summary_json,revision," +
                "created_at,updated_at,completed_at FROM quant_v2_experiment WHERE user_id=?");
        var arguments = new ArrayList<>();
        arguments.add(userId);
        if (strategyId != null) { sql.append(" AND strategy_id=?"); arguments.add(strategyId); }
        if (sourceBacktestId != null) { sql.append(" AND source_backtest_id=?"); arguments.add(sourceBacktestId); }
        if (status != null) { sql.append(" AND status=?"); arguments.add(status); }
        sql.append(" ORDER BY created_at DESC,id DESC");
        return db.queryForList(sql.toString(), arguments.toArray()).stream().map(this::decodeProductExperiment).toList();
    }

    public Map<String, Object> productExperiment(Long userId, String experimentId) {
        var rows = db.queryForList("SELECT e.id,e.name,e.status,e.source_backtest_id,e.strategy_id," +
                        "e.strategy_version_id,e.snapshot_id,e.variable_definition_json,e.baseline_values_json," +
                        "e.candidate_values_json,e.source_context_json,e.environment_json,e.candidate_rule_version," +
                        "e.stability_algorithm_version,e.summary_json,e.revision,e.created_at,e.updated_at,e.completed_at," +
                        "v.version strategy_version_number,o.name strategy_name,t.name source_name,t.status source_status," +
                        "s.content_hash snapshot_content_hash,s.format_version snapshot_format_version,s.metadata_json snapshot_metadata_json " +
                        "FROM quant_v2_experiment e " +
                        "LEFT JOIN quant_v2_version v ON v.id=e.strategy_version_id AND v.user_id=e.user_id " +
                        "LEFT JOIN quant_v2_object o ON o.id=e.strategy_id AND o.user_id=e.user_id " +
                        "LEFT JOIN quant_v2_task t ON t.id=e.source_backtest_id AND t.user_id=e.user_id " +
                        "JOIN quant_v2_research_snapshot s ON s.id=e.snapshot_id AND s.user_id=e.user_id " +
                        "WHERE e.id=? AND e.user_id=?", experimentId, userId);
        if (rows.isEmpty()) throw new ExperimentNotFoundException();
        var result = decodeProductExperiment(rows.get(0));
        result.put("sourceContext", decodeMap(rows.get(0).get("source_context_json")));
        result.put("environment", decodeMap(rows.get(0).get("environment_json")));
        result.put("candidateRuleVersion", nullable(rows.get(0), "candidate_rule_version"));
        result.put("stabilityAlgorithmVersion", nullable(rows.get(0), "stability_algorithm_version"));
        result.put("strategyVersion", rows.get(0).get("strategy_version_number"));
        result.put("strategyName", nullable(rows.get(0), "strategy_name"));
        result.put("sourceName", nullable(rows.get(0), "source_name"));
        result.put("sourceStatus", nullable(rows.get(0), "source_status"));
        result.put("snapshotId", nullable(rows.get(0), "snapshot_id"));
        result.put("snapshotContentHash", nullable(rows.get(0), "snapshot_content_hash"));
        result.put("snapshotFormatVersion", nullable(rows.get(0), "snapshot_format_version"));
        result.put("snapshotMetadata", decodeMap(rows.get(0).get("snapshot_metadata_json")));
        return result;
    }

    public List<Map<String, Object>> productRuns(Long userId, String experimentId) {
        return db.queryForList("SELECT r.id,r.ordinal,r.variable_values_json,r.baseline,r.active_attempt_id," +
                        "t.status,t.stage,t.result_json,t.error_code,t.updated_at " +
                        "FROM quant_v2_experiment_run r " +
                        "LEFT JOIN quant_v2_experiment_run_attempt a ON a.id=r.active_attempt_id AND a.user_id=r.user_id " +
                        "LEFT JOIN quant_v2_task t ON t.id=a.task_id AND t.user_id=r.user_id " +
                        "WHERE r.user_id=? AND r.experiment_id=? ORDER BY r.ordinal", userId, experimentId).stream()
                .map(row -> {
                    var result = new LinkedHashMap<String, Object>();
                    result.put("id", string(row, "id"));
                    result.put("ordinal", number(row, "ordinal").intValue());
                    result.put("variableValues", decodeMap(row.get("variable_values_json")));
                    result.put("baseline", booleanValue(row.get("baseline")));
                    result.put("attemptId", nullable(row, "active_attempt_id"));
                    result.put("status", nullable(row, "status"));
                    result.put("stage", nullable(row, "stage"));
                    result.put("result", decodeNullable(row.get("result_json")));
                    result.put("errorCode", nullable(row, "error_code"));
                    result.put("updatedAt", nullable(row, "updated_at"));
                    return (Map<String, Object>) result;
                }).toList();
    }

    public Map<String, Object> eligibilitySource(Long userId, String sourceBacktestId) {
        var rows = db.queryForList("SELECT t.id,t.kind,t.name,t.status,t.strategy_version_id,t.request_json,t.result_json," +
                        "CASE WHEN EXISTS(SELECT 1 FROM quant_v2_experiment_run_attempt a WHERE a.user_id=t.user_id AND a.task_id=t.id) " +
                        "THEN 1 ELSE 0 END experiment_task FROM quant_v2_task t WHERE t.id=? AND t.user_id=?",
                sourceBacktestId, userId);
        if (rows.isEmpty()) throw new ExperimentInvariant.ExperimentException(
                "SOURCE_BACKTEST_NOT_FOUND", null, "来源回测不存在或无权访问");
        var row = rows.get(0);
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("id", "kind", "name", "status", "strategy_version_id"))
            result.put(key, row.get(key));
        result.put("request", decodeMap(row.get("request_json")));
        result.put("response", decodeNullable(row.get("result_json")));
        result.put("experimentTask", booleanValue(row.get("experiment_task")));
        return result;
    }

    private Map<String, Object> decodeProductExperiment(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("id", "name", "status", "source_backtest_id", "strategy_id",
                "strategy_version_id", "revision", "created_at", "updated_at", "completed_at"))
            result.put(key, row.get(key));
        result.put("variableDefinition", decodeAny(row.get("variable_definition_json")));
        result.put("baselineValues", decodeAny(row.get("baseline_values_json")));
        result.put("candidateValues", decodeAny(row.get("candidate_values_json")));
        result.put("summary", decodeNullable(row.get("summary_json")));
        return result;
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
