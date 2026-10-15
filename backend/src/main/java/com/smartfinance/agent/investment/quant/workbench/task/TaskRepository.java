package com.smartfinance.agent.investment.quant.workbench.task;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class TaskRepository {
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public TaskRepository(org.springframework.jdbc.core.JdbcTemplate db) {
        this.db = db;
    }

    public Integer experimentLinks(Object user, Object taskId) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM quant_v2_experiment_run_attempt WHERE user_id=? AND"
                        + " task_id=?",
                Integer.class,
                user,
                taskId);
    }

    public int insert(
            Object id,
            Object user,
            String kind,
            Object name,
            String status,
            String stage,
            Object strategy,
            Object version,
            Object universe,
            Object request,
            String created,
            String updated) {
        return db.update(
                "INSERT INTO"
                    + " quant_v2_task(id,user_id,kind,name,status,stage,strategy_id,strategy_version_id,universe_id,request_json,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                user,
                kind,
                name,
                status,
                stage,
                strategy,
                version,
                universe,
                request,
                created,
                updated);
    }

    public int cancel(String updated, Object identity, Object user) {
        return db.update(
                "UPDATE quant_v2_task SET"
                    + " status='CANCELLED',stage='CANCELLED',claim_token=NULL,lease_until=NULL,updated_at=?"
                    + " WHERE id=? AND user_id=? AND status IN ('QUEUED','RUNNING')",
                updated,
                identity,
                user);
    }

    public int markPreparing(Object id) {
        return db.update("UPDATE quant_v2_task SET stage='DATA_PREPARING' WHERE id=?", id);
    }

    public List<Map<String, Object>> next(long readyAt, long expiredAt) {
        return db.queryForList(
                "SELECT * FROM quant_v2_task WHERE (status='QUEUED' AND (preparation_next_check_at"
                        + " IS NULL OR preparation_next_check_at<=?)) OR (status='RUNNING' AND"
                        + " lease_until<?) ORDER BY created_at LIMIT 1",
                readyAt,
                expiredAt);
    }

    public int claim(String claim, long leaseUntil, String updated, Object id, long expiredAt) {
        return db.update(
                "UPDATE quant_v2_task SET"
                    + " status='RUNNING',stage='EXECUTING',claim_token=?,lease_until=?,updated_at=?"
                    + " WHERE id=? AND (status='QUEUED' OR (status='RUNNING' AND lease_until<?))",
                claim,
                leaseUntil,
                updated,
                id,
                expiredAt);
    }

    public int complete(String result, String updated, Object id, String claim) {
        return db.update(
                "UPDATE quant_v2_task SET"
                    + " status='SUCCEEDED',stage='COMPLETED',result_json=?,error_code=NULL,error_message=NULL,lease_until=NULL,claim_token=NULL,updated_at=?"
                    + " WHERE id=? AND claim_token=? AND status='RUNNING'",
                result,
                updated,
                id,
                claim);
    }

    public int fail(Object code, Object message, String updated, Object id, String claim) {
        return db.update(
                "UPDATE quant_v2_task SET"
                    + " status='FAILED',stage='FAILED',error_code=?,error_message=?,lease_until=NULL,claim_token=NULL,updated_at=?"
                    + " WHERE id=? AND claim_token=? AND status='RUNNING'",
                code,
                message,
                updated,
                id,
                claim);
    }

    public int waitForData(long nextCheck, String updated, Object id, String claim) {
        return db.update(
                "UPDATE quant_v2_task SET"
                    + " status='QUEUED',stage='DATA_PREPARING',claim_token=NULL,lease_until=NULL,preparation_next_check_at=?,updated_at=?"
                    + " WHERE id=? AND claim_token=? AND status='RUNNING'",
                nextCheck,
                updated,
                id,
                claim);
    }

    public int savePreparedRequest(String request, Object id, String claim, long now) {
        return db.update(
                "UPDATE quant_v2_task SET"
                    + " request_json=?,stage='EXECUTING',preparation_next_check_at=NULL WHERE id=?"
                    + " AND claim_token=? AND status='RUNNING' AND lease_until>?",
                request,
                id,
                claim,
                now);
    }

    public void lockForDeletion(Long user, String identity) {
        db.update(
                "UPDATE quant_v2_task SET updated_at=updated_at WHERE id=? AND user_id=?",
                identity,
                user);
    }

    public void delete(Long user, String identity, String updated) {
        db.update(
                "UPDATE quant_v2_task SET status='DELETED',updated_at=? WHERE id=? AND user_id=?",
                updated,
                identity,
                user);
    }
}
