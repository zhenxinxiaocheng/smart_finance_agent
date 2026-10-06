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
    private static com.smartfinance.agent.investment.service.InvestmentDataQualityService qualityService() {
        var properties = new com.smartfinance.agent.investment.config.InvestmentRuntimeProperties();
        properties.getDataQuality().setStockAdjustType("QFQ");
        properties.getDataQuality().setFundAdjustType("NONE");
        return new com.smartfinance.agent.investment.service.InvestmentDataQualityService(
                null, null, null, properties, new ObjectMapper());
    }
    private JdbcTemplate db;
    private WorkbenchService service;
    private DriverManagerDataSource source;
    private WorkbenchTrackingIndex tracking;

    @BeforeEach
    void createDatabase() {
        source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(source);
        db.update("INSERT INTO investment_product(id,name,code,product_type,market,history_coverage_complete) "
                + "VALUES(1,'ETF联接基金测试','fixture','MUTUAL_FUND','FUND_CN',FALSE)");
        db.update("INSERT INTO investment_asset(id,user_id,account_id,product_id,deleted) VALUES(1,1,1,1,0),(2,2,2,1,0)");
        for (int index = 0; index < 60; index++) {
            db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,total_return_index,adjust_type,source) "
                    + "VALUES(?,?,?,?,?,?)", 1L, LocalDate.of(2023, 1, 2).plusDays(index).toString(),
                    BigDecimal.valueOf(1.25), BigDecimal.valueOf(1.25), "NONE", "TEST");
        }
        tracking = org.mockito.Mockito.mock(WorkbenchTrackingIndex.class);
        service = new WorkbenchService(db, new ObjectMapper(), new DataSourceTransactionManager(source),
                tracking, null, qualityService());
    }

    private Map<String,Object> universe() {
        return service.save(1L, "universes", null,
                Map.of("name", "基金池", "assetClass", "FUND", "assetIds", List.of(1)));
    }
    private void assertPublishedDeploymentScope(String status,boolean onlyHeld) {
        LocalDate today=LocalDate.now(),published=today.minusDays(1),start=today.minusDays(10);
        db.update("INSERT INTO investment_product(id,name,code,product_type,market) VALUES(3,'未持有基金','unused','MUTUAL_FUND','FUND_CN')");
        for(long product:List.of(1L,3L))db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,total_return_index,adjust_type,source) VALUES(?,?,1.2,1.2,'NONE','TEST')",product,published.toString());
        var selected=List.of(Map.<String,Object>of("id",1,"product_id",1,"code","fixture","name","held","product_type","MUTUAL_FUND","market","FUND_CN","assetClass","FUND"),
                Map.<String,Object>of("id",3,"product_id",3,"code","unused","name","unused","product_type","MUTUAL_FUND","market","FUND_CN","assetClass","FUND"));
        var request=new java.util.LinkedHashMap<String,Object>(Map.of("config",Map.of("strategyType","TREND","assetClass","FUND"),"startDate",start.toString(),"endDate",today.toString(),
                "dataStartDate",start.toString(),"warmupCalendarDays",10,"preparedMembers",selected,"assets",service.snapshot(selected,start.toString(),published.toString())));
        var state=Map.of("lastDate",published.toString(),"positions",Map.of("1",Map.of("quantity",100),"3",Map.of("quantity",0)),"pendingOrders",List.of());
        db.update("INSERT INTO quant_v2_deployment(id,user_id,name,backtest_id,strategy_id,universe_id,status,revision,request_json,result_json,claim_token,lease_until,next_run_at,created_at,updated_at) VALUES('deployment',1,'test','test','test','test',?,1,?,?, 'claim',?,0,?,?)",
                status,service.encode(request),service.encode(Map.of("state",state)),System.currentTimeMillis()+60000,WorkbenchService.now(),WorkbenchService.now());
        var requirements=new com.smartfinance.agent.investment.service.MarketDataRequirementService(db);
        var properties=new com.smartfinance.agent.investment.config.InvestmentHorizonProperties();
        var demand=new com.smartfinance.agent.investment.service.MarketDataDemandService(db,requirements,null,properties);
        var preparation=new WorkbenchDataPreparation(requirements,demand,null,properties);
        requirements.save(1,"DEPLOYMENT","deployment",3,"PRICE","REQUIRED",start,today,true,null);
        var deployment=db.queryForMap("SELECT * FROM quant_v2_deployment WHERE id='deployment'");
        assertThat(preparation.prepareDeployment(service,deployment,"claim")).isFalse();
        assertThat(requirements.active()).hasSize(onlyHeld?1:2);
        requirements.recordPrepared(1,"PRICE",start,published);
        if(!onlyHeld)requirements.recordPrepared(3,"PRICE",start,published);
        // This later row lacks a return index and must stay outside the certified snapshot.
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,adjust_type,source) VALUES(1,?,1.3,'NONE','TEST')",today.toString());
        db.update("UPDATE quant_v2_deployment SET claim_token='claim',lease_until=? WHERE id='deployment'",System.currentTimeMillis()+60000);
        assertThat(preparation.prepareDeployment(service,deployment,"claim")).isTrue();
        var ready=service.decode(deployment.get("request_json"));
        assertThat(ready.get("endDate")).isEqualTo(today.toString());
        assertThat((List<?>)ready.get("assets")).hasSize(2);
        for(var asset:(List<?>)ready.get("assets"))for(var bar:(List<?>)WorkbenchService.map(asset).get("bars"))
            assertThat(WorkbenchService.str(WorkbenchService.map(bar).get("date"))).isLessThanOrEqualTo(published.toString());
    }
    @Test void publishedFundReceiptAllowsPaperExecutionBeforeTodaysNavExists() {
        assertPublishedDeploymentScope("RUNNING",false);
    }
    @Test void pausedDeploymentReleasesUnusedMembersAndPreservesItsFrozenUniverse() {
        assertPublishedDeploymentScope("PAUSED",true);
    }
    @Test void multiDigitAssetOwnershipRemainsActiveAndDeletionReleasesIt() {
        db.update("INSERT INTO investment_asset(id,user_id,account_id,product_id,deleted) VALUES(42,1,1,1,0)");
        var requirements=new com.smartfinance.agent.investment.service.MarketDataRequirementService(db);
        requirements.save(1,"ASSET","42",1,"PRICE","REQUIRED",LocalDate.now().minusDays(10),LocalDate.now(),true,null);
        assertThat(requirements.active()).hasSize(1);
        db.update("UPDATE investment_asset SET deleted=1 WHERE id=42");
        assertThat(requirements.active()).isEmpty();
    }
    @Test void missingHistoryQueuesPreparationWithFrozenMembersAndCancellationReleasesDemand() {
        var requirements=new com.smartfinance.agent.investment.service.MarketDataRequirementService(db);
        var demand=org.mockito.Mockito.mock(com.smartfinance.agent.investment.service.MarketDataDemandService.class);
        org.mockito.Mockito.when(demand.boundedStart(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call->call.getArgument(1));
        var client=org.mockito.Mockito.mock(WorkbenchAnalysisClient.class);
        org.mockito.Mockito.when(client.dataRequirements(org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(call->Map.of("config",call.getArgument(0),"warmupTradingDays",60,"datasets",List.of("PRICE")));
        var properties=new com.smartfinance.agent.investment.config.InvestmentHorizonProperties();
        properties.setCalendarDaysPerYear(365);properties.setTradingDaysPerYear(240);properties.setCalendarBufferDays(30);
        service.configurePreparation(new WorkbenchDataPreparation(requirements,demand,client,properties));
        db.update("DELETE FROM product_daily_quote");
        String pool=(String)universe().get("id"), strategy=(String)strategy(pool).get("id");
        var task=service.createTask(1L,"backtests",Map.of("strategyId",strategy,"startDate","2023-01-01","endDate","2023-03-02"));
        assertThat(task).containsEntry("stage","DATA_PREPARING");
        assertThat(requirements.active()).hasSize(1);
        var request=service.decode(db.queryForObject("SELECT request_json FROM quant_v2_task WHERE id=?",String.class,task.get("id")));
        assertThat((List<?>)request.get("preparedMembers")).hasSize(1);
        var worker=new WorkbenchWorker(service,client);
        worker.tasks();
        assertThat(service.get(1L,"backtests",task.get("id").toString())).containsEntry("stage","DATA_PREPARING").containsEntry("status","QUEUED");
        org.mockito.Mockito.verify(client,org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.anyMap());
        db.update("INSERT INTO quant_v2_task(id,user_id,kind,name,status,stage,request_json,created_at,updated_at) VALUES('ready',1,'factor-runs','ready','QUEUED','QUEUED','{}',?,?)",WorkbenchService.now(),WorkbenchService.now());
        org.mockito.Mockito.when(client.execute(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("status","SUCCEEDED"));
        worker.tasks();
        assertThat(db.queryForObject("SELECT status FROM quant_v2_task WHERE id='ready'",String.class)).isEqualTo("SUCCEEDED");
        service.cancel(1L,"backtests",task.get("id").toString());
        assertThat(requirements.active()).isEmpty();
    }

    @Test
    void stockResearchNeverFallsBackToRawClose() {
        db.update("INSERT INTO investment_product(id,name,code,product_type,market,history_coverage_complete) "
                + "VALUES(2,'股票测试','600000','STOCK','SSE',FALSE)");
        db.update("INSERT INTO investment_asset(id,user_id,account_id,product_id,deleted) "
                + "VALUES(3,1,1,2,0)");
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,adjust_type,source) "
                + "VALUES(2,'2024-01-02',10,'NONE','TEST')");

        assertThatThrownBy(() -> service.snapshot(1L, Map.of("assetIds", List.of(3)), "2024-01-02"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("研究行情缺少 QFQ");
    }

    @Test
    void marketProductUniverseDoesNotRequirePersonalAssetAndKeepsDatedMembership() {
        db.update("INSERT INTO investment_product(id,name,code,product_type,market,history_coverage_complete) "
                + "VALUES(3,'未持有股票','600001','STOCK','SSE',FALSE)");
        var pool = service.save(1L, "universes", null,
                Map.of("name", "研究池", "assetClass", "STOCK", "productIds", List.of(3)));
        var market = new com.smartfinance.agent.investment.service.MarketDataService(
                db, org.mockito.Mockito.mock(com.smartfinance.agent.investment.service.AnalysisServiceClient.class));
        String id = String.valueOf(pool.get("id"));
        assertThat(market.getUniverseMembers(1L, id, LocalDate.now()).get("productIds"))
                .isEqualTo(List.of(3L));
        assertThat(market.getUniverseMembers(1L, id, LocalDate.now().minusDays(1)))
                .containsEntry("capability", "UNAVAILABLE");
        assertThat(market.getUniverseMembers(2L, id, LocalDate.now()))
                .containsEntry("capability", "UNAVAILABLE");
    }

    @Test
    void backtestFreezesResolvedTrackingHistoryInWorkerPayload() {
        var index = Map.<String,Object>of("status","READY","code","NASDAQ100","name","纳斯达克100",
                "records",List.of(Map.of("data_date","2023-01-01","close",100)),"sourceVersion","snapshot-v1");
        org.mockito.Mockito.when(tracking.resolve(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(index);
        String pool = (String) universe().get("id");
        String strategy = (String) strategy(pool).get("id");
        var task = service.createTask(1L,"backtests",Map.of("strategyId",strategy,"startDate","2023-01-01","endDate","2023-03-02"));
        var request = service.decode(db.queryForObject("SELECT request_json FROM quant_v2_task WHERE id=?",String.class,task.get("id")));
        assertThat(request.get("trackingIndex")).isEqualTo(index);
        org.mockito.Mockito.verify(tracking).resolve(org.mockito.ArgumentMatchers.argThat(members -> members.size()==1 && "fixture".equals(members.get(0).get("code"))),
                org.mockito.ArgumentMatchers.eq(LocalDate.parse("2023-01-01")),org.mockito.ArgumentMatchers.eq(LocalDate.parse("2023-03-02")));
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
    void researchContextUsesFrozenSourcesAndDoesNotInflateLists() {
        String pool = (String) universe().get("id");
        String strategy = (String) strategy(pool).get("id");
        var task = service.createTask(1L, "backtests", Map.of("strategyId", strategy,
                "startDate", "2023-01-01", "endDate", "2023-03-02"));
        String taskId = (String) task.get("id");
        var request = service.decode(db.queryForObject("SELECT request_json FROM quant_v2_task WHERE id=?", String.class, taskId));
        request.put("modelRef", "frozen-model");
        request.put("modelTaskId", "frozen-training");
        db.update("UPDATE quant_v2_task SET request_json=? WHERE id=?", service.encode(request), taskId);
        var result = Map.of("provenance", Map.of("modelRef", "different-model", "modelTaskId", "different-training"));
        db.update("UPDATE quant_v2_task SET result_json=? WHERE id=?", service.encode(Map.of("result", result)), taskId);
        service.save(1L, "strategies", strategy, Map.of("name", "新策略名称", "universeId", pool,
                "config", Map.of("strategyType", "TREND", "lookback", 40)));
        service.cancel(1L, "backtests", taskId);
        service.deleteStrategy(1L, strategy);
        service.delete(1L, "universes", pool);
        var detail = service.get(1L, "backtests", taskId);
        assertThat(detail).containsKey("researchContext");
        var context = WorkbenchService.map(detail.get("researchContext"));
        var lineage = WorkbenchService.map(context.get("lineage"));
        assertThat(WorkbenchService.map(lineage.get("strategy")))
                .containsEntry("name", "趋势测试").containsEntry("version", 1)
                .containsEntry("currentStatus", "DELETED");
        assertThat(context).containsEntry("modelRef", "frozen-model").containsEntry("modelTaskId", "frozen-training");
        assertThat(detail.get("result")).isEqualTo(result);
        assertThat((List<Map<String,Object>>) context.get("warnings"))
                .anySatisfy(w -> assertThat(w).containsEntry("code", "SOURCE_REFERENCE_CONFLICT").containsEntry("source", "provenance.modelRef"))
                .anySatisfy(w -> assertThat(w).containsEntry("code", "SOURCE_REFERENCE_CONFLICT").containsEntry("source", "provenance.modelTaskId"));
        var data = (List<Map<String,Object>>) context.get("dataSnapshot");
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("startDate", "2023-01-02")
                .containsEntry("endDate", "2023-03-02").containsEntry("observations", 60)
                .containsEntry("sources", List.of("TEST")).doesNotContainKey("bars");
        assertThat(service.list(1L, "backtests").get(0)).doesNotContainKey("researchContext");
        assertThatThrownBy(() -> service.get(2L, "backtests", taskId)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void eachTaskKeepsItsOwnUniverseAndFactorVersions() {
        String pool = (String) universe().get("id");
        var factor = service.save(1L, "factors", null, Map.of("name", "因子一", "assetClass", "FUND",
                "factors", List.of(Map.of("key", "momentum", "weight", 1))));
        var strategy = service.save(1L, "strategies", null, Map.of("name", "多因子", "universeId", pool,
                "factorSetId", factor.get("id"), "config", Map.of("strategyType", "MULTI_FACTOR")));
        var body = Map.<String,Object>of("strategyId", strategy.get("id"), "startDate", "2023-01-01", "endDate", "2023-03-02");
        var first = service.createTask(1L, "backtests", body);
        service.save(1L, "universes", pool, Map.of("name", "池二", "assetClass", "FUND", "assetIds", List.of(1)));
        service.save(1L, "factors", (String) factor.get("id"), Map.of("name", "因子二", "assetClass", "FUND",
                "factors", List.of(Map.of("key", "momentum", "weight", 2))));
        var second = service.createTask(1L, "backtests", body);
        assertThat(first.get("strategyVersionId")).isEqualTo(second.get("strategyVersionId"));
        var a = WorkbenchService.map(WorkbenchService.map(first.get("researchContext")).get("lineage"));
        var b = WorkbenchService.map(WorkbenchService.map(second.get("researchContext")).get("lineage"));
        assertThat(WorkbenchService.map(a.get("universe"))).containsEntry("version", 1);
        assertThat(WorkbenchService.map(b.get("universe"))).containsEntry("version", 2);
        assertThat(WorkbenchService.map(a.get("factorSet"))).containsEntry("name", "因子一");
        assertThat(WorkbenchService.map(b.get("factorSet"))).containsEntry("name", "因子二");
    }

    @Test
    void invalidOrForeignVersionReferencesNeverExposeSnapshots() {
        String pool = (String) universe().get("id");
        String strategy = (String) strategy(pool).get("id");
        var task = service.createTask(1L, "backtests", Map.of("strategyId", strategy,
                "startDate", "2023-01-01", "endDate", "2023-03-02"));
        String taskId = (String) task.get("id");
        var request = service.decode(db.queryForObject("SELECT request_json FROM quant_v2_task WHERE id=?", String.class, taskId));
        request.put("universeVersionId", task.get("strategyVersionId"));
        request.put("factorVersionId", "foreign-version");
        db.update("INSERT INTO quant_v2_version VALUES(?,?,?,?,?,?)", "foreign-version", 2L, "foreign-factor", 1,
                "{\"name\":\"SECRET\"}", "2023-01-01");
        db.update("UPDATE quant_v2_task SET request_json=? WHERE id=?", service.encode(request), taskId);
        var context = WorkbenchService.map(service.get(1L, "backtests", taskId).get("researchContext"));
        assertThat(context).containsKey("warnings");
        var lineage = WorkbenchService.map(context.get("lineage"));
        assertThat(WorkbenchService.map(lineage.get("universe"))).doesNotContainKey("snapshot");
        assertThat(WorkbenchService.map(lineage.get("factorSet"))).doesNotContainKey("snapshot");
        assertThat(service.encode(context)).doesNotContain("SECRET");
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
        var restarted = new WorkbenchService(db, new ObjectMapper(), new DataSourceTransactionManager(source),
                org.mockito.Mockito.mock(WorkbenchTrackingIndex.class), null, qualityService());
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
