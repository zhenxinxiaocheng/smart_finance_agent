package com.smartfinance.agent.investment.quant.workbench.query;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class WorkbenchQueryRepository {
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public WorkbenchQueryRepository(org.springframework.jdbc.core.JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> findRecord(String table, Object user, Object id) {
        require(
                Set.of(
                                "quant_v2_object",
                                "quant_v2_task",
                                "quant_v2_deployment",
                                "quant_v2_version")
                        .contains(table),
                "未知记录类型");
        return db.queryForList("SELECT * FROM " + table + " WHERE user_id=? AND id=?", user, id);
    }

    public List<Map<String, Object>> latestBacktest(Object user, Object strategy) {
        return db.queryForList(
                "SELECT * FROM quant_v2_task WHERE user_id=? AND strategy_id=? AND kind='backtests'"
                    + " AND status<>'DELETED' AND NOT EXISTS (SELECT 1 FROM"
                    + " quant_v2_experiment_run_attempt a WHERE a.task_id=quant_v2_task.id) ORDER"
                    + " BY created_at DESC LIMIT 1",
                user,
                strategy);
    }

    public List<Map<String, Object>> latestVersion(Object user, Object objectId) {
        return db.queryForList(
                "SELECT id,version,created_at FROM quant_v2_version WHERE user_id=? AND object_id=?"
                        + " ORDER BY version DESC LIMIT 1",
                user,
                objectId);
    }

    public List<Map<String, Object>> strategyDeployments(Object user, Object strategy) {
        return db.queryForList(
                "SELECT id,name,status FROM quant_v2_deployment WHERE user_id=? AND strategy_id=?"
                        + " AND status<>'DELETED' ORDER BY created_at DESC",
                user,
                strategy);
    }

    public List<Map<String, Object>> listDeployments(String table, Object user) {
        require(
                Set.of(
                                "quant_v2_object",
                                "quant_v2_task",
                                "quant_v2_deployment",
                                "quant_v2_version")
                        .contains(table),
                "未知记录类型");
        return db.queryForList(
                "SELECT * FROM "
                        + table
                        + " WHERE user_id=? AND status<>'DELETED' ORDER BY created_at DESC",
                user);
    }

    public List<Map<String, Object>> listResources(String table, Object user, String kind) {
        require(
                Set.of(
                                "quant_v2_object",
                                "quant_v2_task",
                                "quant_v2_deployment",
                                "quant_v2_version")
                        .contains(table),
                "未知记录类型");
        return db.queryForList(
                "SELECT * FROM "
                        + table
                        + " WHERE user_id=? AND kind=? AND status<>'DELETED' "
                        + (TASKS.contains(kind)
                                ? "AND NOT EXISTS (SELECT 1 FROM quant_v2_experiment_run_attempt a"
                                        + " WHERE a.task_id=quant_v2_task.id) "
                                : "")
                        + "ORDER BY created_at DESC",
                user,
                kind);
    }

    public List<Map<String, Object>> eventPayloads(Object user, Object identity) {
        return db.queryForList(
                "SELECT kind,payload FROM quant_v2_paper_event WHERE user_id=? AND deployment_id=?"
                        + " ORDER BY created_at,id",
                user,
                identity);
    }

    public List<Map<String, Object>> versions(Object user, Object identity) {
        return db.queryForList(
                "SELECT * FROM quant_v2_version WHERE user_id=? AND object_id=? ORDER BY version"
                        + " DESC",
                user,
                identity);
    }

    public List<Map<String, Object>> events(Object user, Object id) {
        return db.queryForList(
                "SELECT * FROM quant_v2_paper_event WHERE user_id=? AND deployment_id=? ORDER BY"
                        + " created_at,id",
                user,
                id);
    }

    public List<Map<String, Object>> findVersion(Object user, Object id) {
        return db.queryForList("SELECT * FROM quant_v2_version WHERE user_id=? AND id=?", user, id);
    }

    public List<Map<String, Object>> versionOwner(Object user, Object owner) {
        return db.queryForList(
                "SELECT kind,status FROM quant_v2_object WHERE user_id=? AND id=?", user, owner);
    }
}
