package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.quant.workbench.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.*;

class ExperimentFlowTest {
    JdbcTemplate db;ExperimentRepository repository;ResearchSnapshotStore snapshots;ExperimentService experiments;
    ExperimentAttemptService attempts;ExperimentExecutionResolver resolver;ExperimentResultValidator validator;
    ExperimentSummaryService summaries;WorkbenchAnalysisClient client;WorkbenchService ordinary;WorkbenchWorker worker;
    Map<String,Object> environment=Map.of("engineVersion","current","codeHash","current-code","parameterCatalogVersion","catalog","candidateRuleVersion","rule");
    String source;Map<String,Object> original;List<Map<String,Object>> calls;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");db=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(ds);
        var json=new ObjectMapper();var manager=new DataSourceTransactionManager(ds);
        repository=new ExperimentRepository(db,json);snapshots=new ResearchSnapshotStore(db,json,1000000,1000000);var tasks=new ExperimentTaskStore(db,json);
        attempts=new ExperimentAttemptService(db,repository,tasks,manager);client=mock(WorkbenchAnalysisClient.class);
        when(client.runtimeInfo()).thenAnswer(a->environment);when(client.leaseMillis()).thenReturn(60000L);
        when(client.validateConfig(anyMap())).thenAnswer(a->Map.of("compatible",true,"runtime",environment,"effectiveConfig",map(a.getArgument(0)).get("config"),"assumptions",List.of("fixed assumptions"),"benchmarkContract",Map.of("status","READY","type","UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE")));
        when(client.candidates(anyMap())).thenReturn(Map.of("parameterKey","slowWindow","baseline",60,"delta",6,"values",List.of(48,54,60,66,72),"ruleVersion","rule"));
        experiments=new ExperimentService(db,repository,snapshots,new ExperimentCandidateService(client),client,attempts,manager);
        resolver=new ExperimentExecutionResolver(repository,snapshots,db);validator=new ExperimentResultValidator();summaries=new ExperimentSummaryService(db,repository,validator,manager);
        var quoteProperties=new com.smartfinance.agent.investment.config.InvestmentRuntimeProperties();
        quoteProperties.getDataQuality().setStockAdjustType("QFQ");
        ordinary=new WorkbenchService(db,json,manager,
                org.mockito.Mockito.mock(com.smartfinance.agent.investment.quant.workbench.WorkbenchTrackingIndex.class),
                null,new com.smartfinance.agent.investment.service.QuoteSeriesPolicy(quoteProperties));
        worker=new WorkbenchWorker(ordinary,client);worker.configureExperiments(resolver,validator,summaries);
        for(String[] object:List.of(new String[]{"strategy","strategies"},new String[]{"universe","universes"})) {
            db.update("INSERT INTO quant_v2_object VALUES(?,7,?,?,'ACTIVE',1,'{}','now','now')",object[0],object[1],object[0]);
            db.update("INSERT INTO quant_v2_version VALUES(?,7,?,1,'{}','now')",object[0]+"-v1",object[0]);
        }
        original=new LinkedHashMap<>();original.put("kind","BACKTEST");original.put("strategyVersionId","strategy-v1");original.put("universeId","universe");original.put("universeVersionId","universe-v1");
        original.put("startDate","2024-01-01");original.put("endDate","2024-12-31");original.put("config",Map.of("slowWindow",60,"feeRate",.001,"seed",42));
        original.put("assets",List.of(Map.of("id","asset","assetClass","STOCK","bars",List.of(Map.of("date","2024-01-01","close",10)))));
        source=tasks.enqueueBacktest(7L,"source","strategy","strategy-v1","universe",original);
        var response=response(original,-.1);var provenance=map(map(response.get("result")).get("provenance"));provenance.put("engineVersion","old");provenance.put("codeHash","old-code");mapResult(response).put("provenance",provenance);
        db.update("UPDATE quant_v2_task SET status='SUCCEEDED',result_json=? WHERE id=?",encode(response),source);
        calls=new ArrayList<>();when(client.execute(anyMap())).thenAnswer(a->{Map<String,Object> payload=a.getArgument(0);calls.add(payload);return response(payload,-.1);});
    }
    ExperimentModels.ExperimentView create(){return experiments.create(7L,source,"slowWindow","参数敏感性检查","request");}
    String task(ExperimentModels.RunView run){return repository.attempt(7L,run.activeAttemptId()).taskId();}
    void finish(){for(int i=0;i<5;i++)worker.tasks();}
    Map<String,Object> saved(String task){return decode(db.queryForObject("SELECT result_json FROM quant_v2_task WHERE id=?",String.class,task));}
    @SuppressWarnings("unchecked") static Map<String,Object> mapResult(Map<String,Object> response){return (Map<String,Object>)response.get("result");}
    Map<String,Object> response(Map<String,Object> payload,double netReturn) {
        var p=new LinkedHashMap<String,Object>();p.putAll(environment);p.put("config",payload.get("config"));p.put("dataHash","engine-normalized-hash");
        for(String key:List.of("strategyVersionId","universeId","startDate","endDate","modelRef"))p.put(key,payload.get(key));p.put("universeVersion",payload.get("universeVersionId"));p.put("factorSetVersionId",payload.get("factorVersionId"));
        var result=new LinkedHashMap<String,Object>();result.put("provenance",p);result.put("assumptions",List.of("fixed assumptions"));
        result.put("benchmark",Map.of("status","READY","type","UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE","metrics",Map.of("netReturn",netReturn+.05)));
        result.put("metrics",Map.of("netReturn",netReturn,"maxDrawdown",.12));
        return new LinkedHashMap<>(Map.of("status","SUCCEEDED","qualification",Map.of("status","UNQUALIFIED","reasons",List.of("DRAWDOWN_LIMIT_EXCEEDED")),"result",result));
    }
    @Test void createsFiveFreshRunsWithSharedSnapshotAndCurrentRuntimeThenProjectsHighEvidenceDespiteUnqualified() {
        var e=create();var runs=repository.runs(7L,e.id());assertThat(runs).hasSize(5);assertThat(runs.get(2).baseline()).isTrue();
        assertThat(e.environment()).isEqualTo(environment);assertThat(map(e.sourceContext().get("runtime"))).containsEntry("engineVersion","old");
        for(var run:runs){var request=decode(db.queryForObject("SELECT request_json FROM quant_v2_task WHERE id=?",String.class,task(run)));assertThat(request).doesNotContainKey("assets");assertThat(map(request.get("executionInput"))).containsEntry("snapshotId",e.snapshotId());}
        finish();assertThat(calls).hasSize(5);for(var payload:calls){assertThat(payload.get("assets")).isEqualTo(original.get("assets"));assertThat(payload.get("expectedRuntime")).isEqualTo(environment);var config=map(payload.get("config"));config.remove("slowWindow");assertThat(config).isEqualTo(Map.of("feeRate",.001,"seed",42));}
        var summary=map(repository.experiment(7L,e.id()).summary());assertThat(summary).containsEntry("classification","STABLE").containsEntry("performanceProfile","NEGATIVE").containsEntry("evidenceQuality","HIGH");
        assertThat(summary.get("message")).isEqualTo("参数行为稳定，但该区间整体收益为负。");assertThat(map(summary.get("qualificationSummary"))).containsEntry("unqualified",5);
        assertThat(summaries.refresh(7L,e.id())).isEqualTo(summary);assertThat((List<?>)summary.get("inputAttemptIds")).hasSize(5);
    }
    @Test void ordinaryRequestStillPassesDirectlyThroughWorker() {
        var tasks=new ExperimentTaskStore(db,new ObjectMapper());var id=tasks.enqueueBacktest(7L,"ordinary","strategy","strategy-v1","universe",original);
        worker.tasks();assertThat(calls).containsExactly(original);assertThat(saved(id)).doesNotContainKey("experimentValidation");
    }
    @Test void sourceCurrentObjectEditsAndDeletionDoNotReplaceFrozenConfig() {
        db.update("UPDATE quant_v2_object SET status='DELETED',payload='{}',revision=22");var e=create();assertThat(map(e.baseRequest().get("config"))).containsEntry("slowWindow",60);
    }
    @Test void idempotencySurvivesSourceDeletionAndConflictsAreExplicit() {
        var e=create();db.update("DELETE FROM quant_v2_task WHERE id=?",source);assertThat(create().id()).isEqualTo(e.id());
        assertThatThrownBy(()->experiments.create(7L,source,"slowWindow","changed","request")).hasMessage("EXPERIMENT_REQUEST_CONFLICT");
    }
    @Test void failedInsertRollsBackSnapshotExperimentRunsAttemptsAndTasks() {
        db.execute("ALTER TABLE quant_v2_task ADD CONSTRAINT reject_experiment CHECK (name <> '参数敏感性检查')");
        assertThatThrownBy(this::create).isInstanceOf(RuntimeException.class);
        for(String table:List.of("quant_v2_research_snapshot","quant_v2_experiment","quant_v2_experiment_run","quant_v2_experiment_run_attempt"))assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM quant_v2_task",Integer.class)).isEqualTo(1);
    }
    @Test void cannotCreateFromExperimentOrForeignSource() {
        var e=create();finish();assertThatThrownBy(()->experiments.create(7L,task(repository.runs(7L,e.id()).get(2)),"slowWindow","new","other")).hasMessage("INVALID_EXPERIMENT_SOURCE");
        assertThatThrownBy(()->experiments.create(8L,source,"slowWindow","new","other")).hasMessage("SOURCE_BACKTEST_NOT_FOUND");
    }
    @Test void crossUserEveryRelationIsDenied() {
        var e=create();var run=repository.runs(7L,e.id()).get(0);assertThatThrownBy(()->repository.experiment(8L,e.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->repository.run(8L,run.id())).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->repository.attempt(8L,run.activeAttemptId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->snapshots.loadAssets(8L,e.snapshotId())).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->attempts.appendAttempt(8L,run.id(),"RETRY")).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings={"retry","delete","cancel","deploy"}) void relationshipGuardsWorkWithoutParsingRequest(String action) {
        var e=create();var id=task(repository.runs(7L,e.id()).get(0));db.update("UPDATE quant_v2_task SET request_json='{}' WHERE id=?",id);
        assertThatThrownBy(()->{switch(action){case "retry"->ordinary.retry(7L,"backtests",id);case "delete"->ordinary.delete(7L,"backtests",id);case "cancel"->ordinary.cancel(7L,"backtests",id);default->ordinary.deploy(7L,Map.of("backtestId",id));}}).isInstanceOf(RuntimeException.class);
        assertThat(ordinary.list(7L,"backtests")).hasSize(1);
    }
    @Test void corruptedSnapshotFailsAttemptAndInvalidatesEvidence() {
        var e=create();db.update("UPDATE quant_v2_research_snapshot SET payload_blob=? WHERE id=?",new byte[]{1},e.snapshotId());finish();
        assertThat(calls).isEmpty();assertThat(map(repository.experiment(7L,e.id()).summary())).containsEntry("evidenceQuality","INVALID").containsEntry("classification","INSUFFICIENT");
    }
    @Test void runtimeChangeRefusesAllAttempts() {
        var e=create();when(client.execute(anyMap())).thenThrow(new ExperimentException("EXPERIMENT_ENVIRONMENT_CHANGED"));finish();
        assertThat(repository.experiment(7L,e.id()).status()).isEqualTo("FAILED");assertThat(map(repository.experiment(7L,e.id()).summary())).containsEntry("evidenceQuality","INVALID");
    }
    @Test void preExecutionTamperingFailsBeforeEngine() {
        var e=create();var run=repository.runs(7L,e.id()).get(0);db.update("UPDATE quant_v2_experiment_run SET variable_values_json=? WHERE id=?",encode(Map.of("slowWindow",48,"feeRate",0)),run.id());finish();
        assertThat(calls).hasSize(4);assertThat(map(repository.experiment(7L,e.id()).summary())).containsEntry("evidenceQuality","INVALID");
    }
    @ParameterizedTest @ValueSource(strings={"config","codeHash","dataHash","modelRef","startDate","benchmark","assumptions"}) void postExecutionViolationsRetainRawResult(String field) {
        var e=create();when(client.execute(anyMap())).thenAnswer(a->{Map<String,Object> payload=a.getArgument(0);var response=response(payload,-.1);if(((Number)map(payload.get("config")).get("slowWindow")).intValue()==48){var result=mapResult(response);var p=map(result.get("provenance"));if(field.equals("benchmark"))result.put(field,Map.of("status","READY","type","OTHER"));else if(field.equals("assumptions"))result.put(field,List.of("changed"));else {p.put(field,field.equals("config")?Map.of("feeRate",0):"changed");result.put("provenance",p);}}return response;});finish();
        var saved=saved(task(repository.runs(7L,e.id()).get(0)));assertThat(saved).containsKeys("result","qualification","experimentValidation");
        var summary=map(repository.experiment(7L,e.id()).summary());assertThat(summary).containsEntry("evidenceQuality","INVALID").containsEntry("excludedRunCount",1);
    }
    @Test void benchmarkReturnMayDifferAndCorporateReasonsReduceEvidence() {
        var e=create();when(client.execute(anyMap())).thenAnswer(a->{Map<String,Object> payload=a.getArgument(0);var response=response(payload,-.1);mapResult(response).put("benchmark",Map.of("status","READY","type","UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE","metrics",Map.of("netReturn",((Number)map(payload.get("config")).get("slowWindow")).doubleValue()/1000)));response.put("qualification",Map.of("status","UNQUALIFIED","reasons",List.of("CORPORATE_ACTIONS_NOT_VERIFIED")));return response;});finish();
        assertThat(map(repository.experiment(7L,e.id()).summary())).containsEntry("evidenceQuality","LOW").containsEntry("classification","STABLE");
    }
    @ParameterizedTest @ValueSource(ints={1,2,3}) void missingBaselineOrImmediateNeighborIsInsufficientWithoutInventedDataMismatch(int ordinal) {
        var e=create();var id=task(repository.runs(7L,e.id()).get(ordinal));db.update("UPDATE quant_v2_task SET status='FAILED',error_code='NO_EXECUTED_TRADES' WHERE id=?",id);finish();
        var summary=map(repository.experiment(7L,e.id()).summary());assertThat(summary).containsEntry("classification","INSUFFICIENT");assertThat(summary.get("evidenceQuality")).isNotEqualTo("INVALID");
    }
    @Test void appendAttemptKeepsAuditAndArchivesPreviousSummary() {
        var e=create();var run=repository.runs(7L,e.id()).get(0);db.update("UPDATE quant_v2_task SET status='FAILED',error_code='NO_EXECUTED_TRADES' WHERE id=?",task(run));finish();
        var old=map(repository.experiment(7L,e.id()).summary());var next=attempts.appendAttempt(7L,run.id(),"RETRY");assertThat(next.attemptNo()).isEqualTo(2);assertThat(repository.attempts(7L,run.id())).hasSize(2);
        assertThat(map(repository.experiment(7L,e.id()).summary())).doesNotContainKey("classification");worker.tasks();var current=map(repository.experiment(7L,e.id()).summary());
        old.remove("history");assertThat(((List<?>)current.get("history")).contains(old)).isTrue();assertThat(((List<?>)current.get("inputAttemptIds")).contains(next.id())).isTrue();
    }

    @Test void modelAndFactorReferencesStayFrozenAndForeignModelTasksAreRejected() {
        db.update("INSERT INTO quant_v2_object VALUES('factor',7,'factors','factor','DELETED',2,'{}','now','now')");
        db.update("INSERT INTO quant_v2_version VALUES('factor-v1',7,'factor',1,'{}','now')");
        db.update("UPDATE quant_v2_version SET payload=? WHERE id='strategy-v1'",encode(Map.of("factorSetId","factor")));
        original.put("factorVersionId","factor-v1");original.put("modelRef","model-frozen");original.put("modelTaskId","training");
        db.update("INSERT INTO quant_v2_task(id,user_id,kind,name,status,stage,request_json,result_json,created_at,updated_at) VALUES('training',7,'training-runs','training','SUCCEEDED','COMPLETED','{}',?,'now','now')",encode(Map.of("modelRef","model-frozen")));
        db.update("UPDATE quant_v2_task SET request_json=?,result_json=? WHERE id=?",encode(original),encode(response(original,-.1)),source);
        var e=create();assertThat(e.factorId()).isEqualTo("factor");assertThat(e.factorVersionId()).isEqualTo("factor-v1");assertThat(e.modelRef()).isEqualTo("model-frozen");finish();
        assertThat(map(repository.experiment(7L,e.id()).summary())).containsEntry("evidenceQuality","HIGH");
        db.update("UPDATE quant_v2_task SET user_id=8 WHERE id='training'");
        assertThatThrownBy(()->experiments.create(7L,source,"slowWindow","second","second")).hasMessage("SOURCE_BACKTEST_NOT_FOUND");
    }

    @Test void partialThirdAttemptInsertionRollsBackEverything() {
        var failingTasks=new ExperimentTaskStore(db,new ObjectMapper()) {
            int count;
            @Override public String enqueueBacktest(Long user,String name,String strategy,String version,String universe,Map<String,Object> request) {
                if(++count==3)throw new IllegalStateException("third task failed");return super.enqueueBacktest(user,name,strategy,version,universe,request);
            }
        };
        var manager=new DataSourceTransactionManager(db.getDataSource());
        var service=new ExperimentService(db,repository,snapshots,new ExperimentCandidateService(client),client,new ExperimentAttemptService(db,repository,failingTasks,manager),manager);
        assertThatThrownBy(()->service.create(7L,source,"slowWindow","test","key")).hasMessage("third task failed");
        for(String table:List.of("quant_v2_research_snapshot","quant_v2_experiment","quant_v2_experiment_run","quant_v2_experiment_run_attempt"))assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM quant_v2_task",Integer.class)).isEqualTo(1);
    }

    @Test void missingSourceRuntimeCannotBecomeHighQualityEvidence() {
        var response=decode(db.queryForObject("SELECT result_json FROM quant_v2_task WHERE id=?",String.class,source));
        var p=map(mapResult(response).get("provenance"));p.remove("codeHash");mapResult(response).put("provenance",p);
        db.update("UPDATE quant_v2_task SET result_json=? WHERE id=?",encode(response),source);
        assertThatThrownBy(this::create).hasMessage("SOURCE_CONTEXT_INCOMPLETE");
    }

    @Test void evidenceUsesExplicitV2AxesAndCounts() {
        var e=create();finish();var s=map(repository.experiment(7L,e.id()).summary());
        assertThat(s).containsEntry("evidenceSchemaVersion","experiment-evidence-v2");
        assertThat(map(s.get("evidence"))).containsEntry("controlIntegrity","PASS").containsEntry("dataConsistency","PASS")
            .containsEntry("sourceCompleteness","COMPLETE").containsEntry("runCoverage","COMPLETE")
            .containsEntry("validRuns",5).containsEntry("totalRuns",5).containsEntry("excludedRunCount",0).containsEntry("excludedRuns",List.of());
    }
    @ParameterizedTest @ValueSource(strings={"different","missing"}) void dataFailureDoesNotInventControlFailure(String mode) {
        var e=create();when(client.execute(anyMap())).thenAnswer(a->{var response=response(a.getArgument(0),-.1);
            var p=map(mapResult(response).get("provenance"));if(mode.equals("missing"))p.remove("dataHash");
            else if(((Number)map(p.get("config")).get("slowWindow")).intValue()==48)p.put("dataHash","different");
            mapResult(response).put("provenance",p);return response;});finish();
        var s=map(repository.experiment(7L,e.id()).summary());
        assertThat(s).containsEntry("evidenceQuality","INVALID");
        assertThat(map(s.get("evidence"))).containsEntry("controlIntegrity","PASS").containsEntry("dataConsistency","FAIL");
        assertThat((List<String>)s.get("reasonCodes")).contains("EXPERIMENT_DATA_INCONSISTENCY").doesNotContain("CONTROL_VARIABLE_VIOLATION");
        assertThat(map(saved(task(repository.runs(7L,e.id()).get(0))).get("experimentValidation"))).containsEntry("valid",true);
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4}) void coverageUsesActualCriticalOrdinals(int ordinal) {
        var e=create();db.update("UPDATE quant_v2_task SET status='FAILED',error_code='NO_EXECUTED_TRADES' WHERE id=?",task(repository.runs(7L,e.id()).get(ordinal)));finish();
        var s=map(repository.experiment(7L,e.id()).summary());boolean critical=ordinal>0&&ordinal<4;
        assertThat(s).containsEntry("evidenceQuality",critical?"INSUFFICIENT":"MEDIUM");
        assertThat(map(s.get("evidence"))).containsEntry("runCoverage",critical?"INSUFFICIENT":"PARTIAL");
    }
    @Test void legacyMissingValidationIsPartialControlRatherThanExplicitFailure() {
        var e=create();var runs=repository.runs(7L,e.id());
        for(var run:runs)db.update("UPDATE quant_v2_task SET status='SUCCEEDED',result_json=? WHERE id=?",encode(response(original,-.1)),task(run));
        var s=summaries.refresh(7L,e.id());
        assertThat(map(s.get("evidence"))).containsEntry("controlIntegrity","PARTIAL");
        assertThat((List<String>)s.get("reasonCodes")).doesNotContain("CONTROL_VARIABLE_VIOLATION");
    }
    @Test void newExperimentsFreezeV2AndV1RecomputationUsesV1() {
        var e=create();assertThat(e.stabilityAlgorithmVersion()).isEqualTo("parameter-stability-v2");
        when(client.execute(anyMap())).thenAnswer(a->{Map<String,Object> p=a.getArgument(0);int value=((Number)map(p.get("config")).get("slowWindow")).intValue();return response(p,value==48||value==72?.5:.1);});
        finish();assertThat(map(repository.experiment(7L,e.id()).summary())).containsEntry("classification","STABLE");
        db.update("UPDATE quant_v2_experiment SET stability_algorithm_version='parameter-stability-v1',summary_json=NULL WHERE id=?",e.id());
        assertThat(summaries.refresh(7L,e.id())).containsEntry("classification","MIXED").containsEntry("algorithmVersion","parameter-stability-v1");
        var cached=summaries.refresh(7L,e.id());
        db.update("UPDATE quant_v2_experiment SET stability_algorithm_version='future' WHERE id=?",e.id());
        assertThat(summaries.refresh(7L,e.id())).isEqualTo(cached);
        db.update("UPDATE quant_v2_experiment SET summary_json=NULL WHERE id=?",e.id());
        assertThatThrownBy(()->summaries.refresh(7L,e.id())).hasMessage("STABILITY_ALGORITHM_UNAVAILABLE");
    }
    @Test void oneLegacyOuterResultWithoutValidationProducesLowEvidence() {
        var e=create();finish();var run=repository.runs(7L,e.id()).get(0);var response=saved(task(run));response.remove("experimentValidation");
        db.update("UPDATE quant_v2_task SET result_json=? WHERE id=?",encode(response),task(run));
        db.update("UPDATE quant_v2_experiment SET summary_json=NULL WHERE id=?",e.id());
        var s=summaries.refresh(7L,e.id());assertThat(s).containsEntry("evidenceQuality","LOW").containsEntry("classification","STABLE");
        assertThat(map(s.get("evidence"))).containsEntry("controlIntegrity","PARTIAL").containsEntry("runCoverage","PARTIAL");
    }
    @ParameterizedTest @ValueSource(strings={"runtime","config"}) void sourceAxisComesFromRealFrozenContext(String field) {
        var e=create();finish();var context=map(e.sourceContext());
        if(field.equals("runtime"))context.remove("runtime");else {var p=map(context.get("provenance"));p.remove("config");context.put("provenance",p);}
        db.update("UPDATE quant_v2_experiment SET summary_json=NULL,source_context_json=? WHERE id=?",encode(context),e.id());
        var s=summaries.refresh(7L,e.id());
        assertThat(s).containsEntry("evidenceQuality",field.equals("runtime")?"LOW":"INSUFFICIENT");
        assertThat(map(s.get("evidence"))).containsEntry("sourceCompleteness",field.equals("runtime")?"PARTIAL":"INSUFFICIENT");
    }
    @Test void unsupportedSnapshotIsADataFailureOnly() {
        var e=create();db.update("UPDATE quant_v2_research_snapshot SET format_version='future' WHERE id=?",e.snapshotId());finish();
        var s=map(repository.experiment(7L,e.id()).summary());assertThat(s).containsEntry("evidenceQuality","INVALID");
        assertThat(map(s.get("evidence"))).containsEntry("controlIntegrity","PASS").containsEntry("dataConsistency","FAIL");
        assertThat((List<String>)s.get("reasonCodes")).contains("SNAPSHOT_CORRUPTED").doesNotContain("CONTROL_VARIABLE_VIOLATION");
    }
    @Test void unknownQualificationReasonSurvivesSummary() {
        var e=create();when(client.execute(anyMap())).thenAnswer(a->{var response=response(a.getArgument(0),-.1);
            response.put("qualification",Map.of("status","UNQUALIFIED","reasons",List.of("NEW_ENGINE_REASON")));return response;});finish();
        var s=map(repository.experiment(7L,e.id()).summary());assertThat(s).containsEntry("evidenceQuality","MEDIUM");
        assertThat(map(map(s.get("evidence")).get("reasonClassifications"))).containsEntry("NEW_ENGINE_REASON","OTHER");
        assertThat((List<String>)s.get("reasonCodes")).contains("NEW_ENGINE_REASON");
    }
}
