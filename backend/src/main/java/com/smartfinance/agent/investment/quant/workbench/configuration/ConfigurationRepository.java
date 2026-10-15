package com.smartfinance.agent.investment.quant.workbench.configuration;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class ConfigurationRepository {
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public ConfigurationRepository(org.springframework.jdbc.core.JdbcTemplate db) {
        this.db = db;
    }

    public int lock(Object user, Object identity, String kind) {
        return db.update(
                "UPDATE quant_v2_object SET revision=revision WHERE user_id=? AND id=? AND kind=?",
                user,
                identity,
                kind);
    }

    public int insert(
            Object id,
            Object user,
            String kind,
            String name,
            String status,
            int revision,
            String payload,
            String created,
            String updated) {
        return db.update(
                "INSERT INTO"
                    + " quant_v2_object(id,user_id,kind,name,status,revision,payload,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                id,
                user,
                kind,
                name,
                status,
                revision,
                payload,
                created,
                updated);
    }

    public int update(
            String name, String payload, String updated, Object id, Object user, int revision) {
        return db.update(
                "UPDATE quant_v2_object SET name=?,payload=?,revision=revision+1,updated_at=? WHERE"
                        + " id=? AND user_id=? AND revision=?",
                name,
                payload,
                updated,
                id,
                user,
                revision);
    }

    public List<Map<String, Object>> activeRequests(String table, Object user) {
        require(
                Set.of(
                                "quant_v2_object",
                                "quant_v2_task",
                                "quant_v2_deployment",
                                "quant_v2_version")
                        .contains(table),
                "未知记录类型");
        return db.queryForList(
                "SELECT request_json FROM "
                        + table
                        + " WHERE user_id=? AND status IN ('QUEUED','RUNNING','PAUSED','STOPPING')",
                user);
    }

    public Integer activeDeployments(String column, Object user, Object identity) {
        require(Set.of("strategy_id", "universe_id").contains(column), "未知关联类型");
        return db.queryForObject(
                "SELECT COUNT(*) FROM quant_v2_deployment WHERE user_id=? AND "
                        + column
                        + "=? AND status NOT IN ('STOPPED','DELETED')",
                Integer.class,
                user,
                identity);
    }

    public Integer activeTasks(Object user, Object strategy, Object universe) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM quant_v2_task WHERE user_id=? AND (strategy_id=? OR"
                        + " universe_id=?) AND status IN ('QUEUED','RUNNING')",
                Integer.class,
                user,
                strategy,
                universe);
    }

    public int archive(String updated, Object id, Object user) {
        return db.update(
                "UPDATE quant_v2_object SET status='ARCHIVED',updated_at=? WHERE id=? AND"
                        + " user_id=?",
                updated,
                id,
                user);
    }

    public List<Map<String, Object>> activeStrategies(Object user) {
        return db.queryForList(
                "SELECT name,payload FROM quant_v2_object WHERE user_id=? AND kind='strategies' AND"
                        + " status NOT IN ('ARCHIVED','DELETED')",
                user);
    }

    public int delete(String updated, Object id, Object user) {
        return db.update(
                "UPDATE quant_v2_object SET status='DELETED',updated_at=? WHERE id=? AND user_id=?",
                updated,
                id,
                user);
    }

    public List<Map<String, Object>> findVersion(Object user, Object identity, int revision) {
        return db.queryForList(
                "SELECT id FROM quant_v2_version WHERE user_id=? AND object_id=? AND version=?",
                user,
                identity,
                revision);
    }

    public int insertVersion(
            Object id, Object user, Object identity, int revision, String payload, String created) {
        return db.update(
                "INSERT INTO quant_v2_version(id,user_id,object_id,version,payload,created_at)"
                        + " VALUES(?,?,?,?,?,?)",
                id,
                user,
                identity,
                revision,
                payload,
                created);
    }
}
