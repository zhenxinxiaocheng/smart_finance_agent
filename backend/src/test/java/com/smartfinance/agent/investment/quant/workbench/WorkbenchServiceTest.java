package com.smartfinance.agent.investment.quant.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class WorkbenchServiceTest {
    private JdbcTemplate db;
    private WorkbenchService service;
    private DriverManagerDataSource source;

    @BeforeEach
    void createDatabase() {
        source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/sqlite/V30__quant_strategy_workbench.sql")).execute(source);
        db.execute("CREATE TABLE investment_product(id BIGINT PRIMARY KEY,name VARCHAR(80),code VARCHAR(20),"
                + "product_type VARCHAR(30),market VARCHAR(20),history_coverage_complete BOOLEAN)");
        db.execute("CREATE TABLE investment_asset(id BIGINT PRIMARY KEY,user_id BIGINT,product_id BIGINT,deleted INT)");
        db.execute("CREATE TABLE product_daily_quote(product_id BIGINT,trade_date VARCHAR(10),open_price DECIMAL(20,6),"
                + "high_price DECIMAL(20,6),low_price DECIMAL(20,6),close_price DECIMAL(20,6),previous_close DECIMAL(20,6),"
                + "total_return_index DECIMAL(20,6),volume DECIMAL(20,6),amount DECIMAL(20,6),adjust_type VARCHAR(10),source VARCHAR(20))");
        db.update("INSERT INTO investment_product VALUES(1,'ETF联接基金测试','fixture','MUTUAL_FUND','FUND_CN',FALSE)");
        db.update("INSERT INTO investment_asset VALUES(1,1,1,0),(2,2,1,0)");
        for (int index = 0; index < 60; index++) {
            db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,total_return_index,adjust_type,source) "
                    + "VALUES(?,?,?,?,?,?)", 1L, LocalDate.of(2023, 1, 2).plusDays(index).toString(),
                    BigDecimal.valueOf(1.25), BigDecimal.valueOf(1.25), "NONE", "TEST");
        }
        service = new WorkbenchService(db, new ObjectMapper(), new DataSourceTransactionManager(source));
    }

    private Map<String,Object> universe() {
        return service.save(1L, "universes", null,
                Map.of("name", "基金池", "assetClass", "FUND", "assetIds", List.of(1)));
    }

    private Map<String,Object> strategy(String pool) {
        return service.save(1L, "strategies", null,
                Map.of("name", "趋势测试", "universeId", pool, "config", Map.of("strategyType", "TREND")));
    }

    @Test
    void ownFundAssetsRemainFundsAndForeignUserAccessIsRejected() {
        assertThat(service.assets(1L)).hasSize(1);
        assertThat(service.assets(1L).get(0)).containsEntry("assetClass", "FUND");
        String id = (String) universe().get("id");
        assertThatThrownBy(() -> service.get(2L, "universes", id)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.save(1L, "universes", null,
                Map.of("name", "非法池", "assetClass", "FUND", "assetIds", List.of(2))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void frozenRequestSurvivesEditsAndServiceRecreation() {
        String pool = (String) universe().get("id");
        String strategy = (String) strategy(pool).get("id");
        var task = service.createTask(1L, "backtests",
                Map.of("strategyId", strategy, "startDate", "2023-01-01", "endDate", "2023-03-02"));
        String snapshot = db.queryForObject("SELECT request_json FROM quant_v2_task WHERE id=?", String.class, task.get("id"));
        service.save(1L, "strategies", strategy, Map.of("name", "修改后的策略", "universeId", pool,
                "config", Map.of("strategyType", "TREND", "lookback", 40)));
        var restarted = new WorkbenchService(db, new ObjectMapper(), new DataSourceTransactionManager(source));
        assertThat(restarted.get(1L, "strategies", strategy)).containsEntry("name", "修改后的策略");
        assertThat(db.queryForObject("SELECT request_json FROM quant_v2_task WHERE id=?", String.class, task.get("id")))
                .isEqualTo(snapshot);
        assertThat(restarted.versions(1L, "strategies", strategy)).hasSize(1);
    }

    @Test
    void deletionEnforcesOwnershipReferencesAndPreservesSnapshots() {
        String pool = (String) universe().get("id");
        String strategy = (String) strategy(pool).get("id");
        assertThatThrownBy(() -> service.delete(2L, "universes", pool)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.delete(1L, "universes", pool)).hasMessageContaining("仍在使用");
        var task = service.createTask(1L, "backtests", Map.of("strategyId", strategy,
                "startDate", "2023-01-01", "endDate", "2023-03-02"));
        String taskId = (String) task.get("id");
        assertThatThrownBy(() -> service.delete(1L, "backtests", taskId)).hasMessageContaining("请先取消任务");
        service.cancel(1L, "backtests", taskId);
        String snapshot = db.queryForObject("SELECT request_json FROM quant_v2_task WHERE id=?", String.class, taskId);
        service.delete(1L, "backtests", taskId);
        assertThat(service.list(1L, "backtests")).isEmpty();
        assertThatThrownBy(() -> service.get(1L, "backtests", taskId)).isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT request_json FROM quant_v2_task WHERE id=?", String.class, taskId)).isEqualTo(snapshot);
        service.deleteStrategy(1L, strategy);
        service.delete(1L, "universes", pool);
        assertThat(service.list(1L, "universes")).isEmpty();
        assertThat(service.assets(1L)).hasSize(1);
        String factor = (String) service.save(1L, "factors", null, Map.of("name", "测试因子", "assetClass", "FUND",
                "factors", List.of(Map.of("key", "momentum", "weight", 1)))).get("id");
        service.delete(1L, "factors", factor);
        assertThat(service.list(1L, "factors")).isEmpty();
    }

    @Test
    void runningTaskPreventsArchivingAndCancelledTaskCannotDeploy() {
        String pool = (String) universe().get("id");
        String strategy = (String) strategy(pool).get("id");
        var task = service.createTask(1L, "backtests",
                Map.of("strategyId", strategy, "startDate", "2023-01-01", "endDate", "2023-03-02"));
        assertThatThrownBy(() -> service.archive(1L, "strategies", strategy)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.archive(1L, "universes", pool)).isInstanceOf(ResponseStatusException.class);
        service.cancel(1L, "backtests", (String) task.get("id"));
        assertThatThrownBy(() -> service.deploy(1L, Map.of("name", "禁止部署", "backtestId", task.get("id"))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(service.archive(1L, "strategies", strategy)).containsEntry("status", "ARCHIVED");
        assertThat(service.get(1L, "backtests", (String) task.get("id"))).containsEntry("status", "CANCELLED");
    }

    @Test
    void completedButUnqualifiedBacktestCannotDeploy() {
        String pool = (String) universe().get("id");
        String strategy = (String) strategy(pool).get("id");
        var task = service.createTask(1L, "backtests",
                Map.of("strategyId", strategy, "startDate", "2023-01-01", "endDate", "2023-03-02"));
        db.update("UPDATE quant_v2_task SET status='SUCCEEDED',result_json=? WHERE id=?",
                "{\"status\":\"SUCCEEDED\",\"qualification\":{\"status\":\"UNQUALIFIED\"}}", task.get("id"));
        assertThatThrownBy(() -> service.deploy(1L, Map.of("name", "禁止部署", "backtestId", task.get("id"))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(service.list(1L, "deployments")).isEmpty();
    }

    @Test
    void deploymentUsesEvaluatedDefaultsAndResumeRecordsTimeBoundary() throws Exception {
        String pool = (String) universe().get("id");
        String strategy = (String) strategy(pool).get("id");
        var task = service.createTask(1L, "backtests",
                Map.of("strategyId", strategy, "startDate", "2023-01-01", "endDate", "2023-03-02"));
        var evaluated = Map.of("qualification", Map.of("status", "QUALIFIED"), "result",
                Map.of("provenance", Map.of("config", Map.of("strategyType", "TREND", "assetClass", "FUND",
                        "initialCash", 250000, "lookback", 17))));
        db.update("UPDATE quant_v2_task SET status='SUCCEEDED',result_json=? WHERE id=?",
                new ObjectMapper().writeValueAsString(evaluated), task.get("id"));
        var deployment = service.deploy(1L, Map.of("name", "模拟测试", "backtestId", task.get("id")));
        String deploymentId = (String) deployment.get("id");
        service.lifecycle(1L, deploymentId, "pause", Map.of());
        service.lifecycle(1L, deploymentId, "resume", Map.of());
        String request = db.queryForObject("SELECT request_json FROM quant_v2_deployment WHERE id=?",
                String.class, deploymentId);
        var json = new ObjectMapper().readTree(request);
        assertThat(json.path("config").path("lookback").asInt()).isEqualTo(17);
        assertThat(json.path("config").path("initialCash").asInt()).isEqualTo(250000);
        assertThat(json.path("signalStartDate").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(service.get(1L, "deployments", deploymentId)).containsEntry("status", "RUNNING");
        assertThatThrownBy(() -> service.delete(1L, "deployments", deploymentId)).hasMessageContaining("请先停止模拟组合");
        service.lifecycle(1L, deploymentId, "stop", Map.of("mode", "KEEP"));
        int eventCount = db.queryForObject("SELECT COUNT(*) FROM quant_v2_paper_event WHERE deployment_id=?", Integer.class, deploymentId);
        service.delete(1L, "deployments", deploymentId);
        assertThat(service.list(1L, "deployments")).isEmpty();
        assertThatThrownBy(() -> service.lifecycle(1L, deploymentId, "resume", Map.of())).isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM quant_v2_paper_event WHERE deployment_id=?", Integer.class, deploymentId)).isEqualTo(eventCount);
    }

    @Test
    void evaluationWindowsUseSharedObservedDatesRatherThanCalendarDays() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        for (int index = 0; index < 130; index++) {
            db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,total_return_index,adjust_type,source) VALUES(?,?,?,?,?,?)",
                    1L, day.plusDays(index).toString(), BigDecimal.ONE, BigDecimal.ONE, "NONE", "TEST");
        }
        String pool = (String) universe().get("id");

        var window = service.evaluationWindow(1L, pool, null, null);

        assertThat(window).containsEntry("minimumEvaluationDays", 60);
        assertThat(window).containsEntry("availableEvaluationDays", 190);
        Map<String, Object> sixtyDaySuggestion = ((List<Map<String, Object>>) window.get("suggestions"))
                .stream()
                .filter(item -> item.get("days").equals(60))
                .findFirst()
                .orElseThrow();
        assertThat(sixtyDaySuggestion)
                .containsEntry("ready", true)
                .containsEntry("startDate", LocalDate.of(2025, 3, 13).toString())
                .containsEntry("endDate", LocalDate.of(2025, 5, 11).toString());
    }

    @Test
    void backtestRejectsASelectedRangeWithFewerThanSixtySharedDates() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        for (int index = 0; index < 59; index++) {
            db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,total_return_index,adjust_type,source) VALUES(?,?,?,?,?,?)",
                    1L, day.plusDays(index).toString(), BigDecimal.ONE, BigDecimal.ONE, "NONE", "TEST");
        }
        String pool = (String) universe().get("id");
        String strategy = (String) strategy(pool).get("id");

        assertThatThrownBy(() -> service.createTask(1L, "backtests", Map.of(
                "strategyId", strategy, "startDate", "2025-01-02", "endDate", "2025-03-01")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("有效评估日期不足");
    }
}
