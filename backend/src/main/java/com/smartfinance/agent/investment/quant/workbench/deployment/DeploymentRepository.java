package com.smartfinance.agent.investment.quant.workbench.deployment;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class DeploymentRepository {
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public DeploymentRepository(org.springframework.jdbc.core.JdbcTemplate db) {
        this.db = db;
    }

    public int insert(
            String id,
            Object user,
            String name,
            Object backtest,
            Object strategy,
            Object universe,
            String status,
            int revision,
            String request,
            String result,
            long nextRun,
            String created,
            String updated) {
        return db.update(
                "INSERT INTO"
                    + " quant_v2_deployment(id,user_id,name,backtest_id,strategy_id,universe_id,status,revision,request_json,result_json,next_run_at,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                user,
                name,
                backtest,
                strategy,
                universe,
                status,
                revision,
                request,
                result,
                nextRun,
                created,
                updated);
    }

    public int lock(Object identity, Object user) {
        return db.update(
                "UPDATE quant_v2_deployment SET revision=revision WHERE id=? AND user_id=?",
                identity,
                user);
    }

    public int insertEvent(
            String id,
            Object user,
            Object deployment,
            String eventKey,
            String kind,
            String payload,
            String created) {
        return db.update(
                "INSERT INTO"
                    + " quant_v2_paper_event(id,user_id,deployment_id,event_key,kind,payload,created_at)"
                    + " VALUES(?,?,?,?,?,?,?)",
                id,
                user,
                deployment,
                eventKey,
                kind,
                payload,
                created);
    }

    public int transition(
            String status,
            String result,
            String request,
            long nextRun,
            String updated,
            Object id,
            Object user,
            Object revision) {
        return db.update(
                "UPDATE quant_v2_deployment SET"
                    + " status=?,result_json=?,request_json=?,revision=revision+1,claim_token=NULL,lease_until=NULL,next_run_at=?,updated_at=?"
                    + " WHERE id=? AND user_id=? AND revision=?",
                status,
                result,
                request,
                nextRun,
                updated,
                id,
                user,
                revision);
    }

    public List<Map<String, Object>> next(long readyAt, long expiredAt) {
        return db.queryForList(
                "SELECT * FROM quant_v2_deployment WHERE status IN ('RUNNING','PAUSED','STOPPING')"
                        + " AND next_run_at<=? AND (claim_token IS NULL OR lease_until<?) ORDER BY"
                        + " next_run_at LIMIT 1",
                readyAt,
                expiredAt);
    }

    public int claim(String claim, long leaseUntil, Object id, int revision, long expiredAt) {
        return db.update(
                "UPDATE quant_v2_deployment SET claim_token=?,lease_until=? WHERE id=? AND"
                        + " revision=? AND (claim_token IS NULL OR lease_until<?)",
                claim,
                leaseUntil,
                id,
                revision,
                expiredAt);
    }

    public int complete(
            String result,
            String status,
            long nextRun,
            String updated,
            Object id,
            int revision,
            String claim) {
        return db.update(
                "UPDATE quant_v2_deployment SET"
                    + " revision=revision+1,result_json=?,status=?,error_code=NULL,error_message=NULL,claim_token=NULL,lease_until=NULL,next_run_at=?,updated_at=?"
                    + " WHERE id=? AND revision=? AND claim_token=?",
                result,
                status,
                nextRun,
                updated,
                id,
                revision,
                claim);
    }

    public int fail(
            Object code,
            Object message,
            long nextRun,
            String updated,
            Object id,
            int revision,
            String claim) {
        return db.update(
                "UPDATE quant_v2_deployment SET"
                    + " error_code=?,error_message=?,claim_token=NULL,lease_until=NULL,next_run_at=?,updated_at=?"
                    + " WHERE id=? AND revision=? AND claim_token=?",
                code,
                message,
                nextRun,
                updated,
                id,
                revision,
                claim);
    }

    public Integer eventCount(Object deployment, String key) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM quant_v2_paper_event WHERE deployment_id=? AND event_key=?",
                Integer.class,
                deployment,
                key);
    }

    public int lockClaim(Object id, String claim, Object revision, long now) {
        return db.update(
                "UPDATE quant_v2_deployment SET claim_token=claim_token WHERE id=? AND"
                        + " claim_token=? AND revision=? AND lease_until>?",
                id,
                claim,
                revision,
                now);
    }

    public int waitForData(long nextRun, String updated, Object id, String claim, Object revision) {
        return db.update(
                "UPDATE quant_v2_deployment SET"
                    + " claim_token=NULL,lease_until=NULL,next_run_at=?,updated_at=? WHERE id=? AND"
                    + " claim_token=? AND revision=?",
                nextRun,
                updated,
                id,
                claim,
                revision);
    }

    public int savePreparedRequest(
            String request, Object id, String claim, Object revision, long now) {
        return db.update(
                "UPDATE quant_v2_deployment SET request_json=? WHERE id=? AND claim_token=? AND"
                        + " revision=? AND lease_until>?",
                request,
                id,
                claim,
                revision,
                now);
    }

    public void lockForDeletion(Long user, String identity) {
        db.update(
                "UPDATE quant_v2_deployment SET updated_at=updated_at WHERE id=? AND user_id=?",
                identity,
                user);
    }

    public void delete(Long user, String identity, String updated) {
        db.update(
                "UPDATE quant_v2_deployment SET status='DELETED',updated_at=? WHERE id=? AND"
                    + " user_id=?",
                updated,
                identity,
                user);
    }
}
