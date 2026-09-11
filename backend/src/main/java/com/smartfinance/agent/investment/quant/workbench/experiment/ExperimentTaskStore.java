package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Repository
public class ExperimentTaskStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ExperimentTaskStore(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public String enqueueBacktest(Long userId, String name, String strategyId,
                                  String strategyVersionId, String universeId,
                                  Map<String, Object> request) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try {
            db.update("INSERT INTO quant_v2_task(id,user_id,kind,name,status,stage,strategy_id," +
                            "strategy_version_id,universe_id,request_json,created_at,updated_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, userId, "backtests", name, "QUEUED", "QUEUED", strategyId,
                    strategyVersionId, universeId, json.writeValueAsString(request), now, now);
            return id;
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建参数敏感性回测任务", exception);
        }
    }
}
