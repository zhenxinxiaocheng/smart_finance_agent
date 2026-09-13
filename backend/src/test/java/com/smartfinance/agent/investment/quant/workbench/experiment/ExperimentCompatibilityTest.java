package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.smartfinance.agent.investment.quant.workbench.WorkbenchAnalysisClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.*;

class ExperimentCompatibilityTest {
    ExperimentFlowTest f;Map<String,Object> validated;String mode;
    @BeforeEach void setup() {
        f=new ExperimentFlowTest();f.setup();
        var client=mock(WorkbenchAnalysisClient.class, invocation->{
            return switch(invocation.getMethod().getName()) {
                case "runtimeInfo" -> f.environment;
                case "validateConfig" -> {
                    validated=map(invocation.getArgument(0));var effective=map(validated.get("config"));
                    if("network".equals(mode))throw new org.springframework.web.client.ResourceAccessException("network unavailable");
                    if("server".equals(mode))throw new org.springframework.web.client.HttpServerErrorException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                    if("number".equals(mode))effective.put("seed",42.0);
                    if("change".equals(mode))effective.put("feeRate",.002);
                    if("default".equals(mode))effective.put("newDefault",1);
                    if(mode!=null && mode.startsWith("ENGINE_"))yield Map.of("compatible",false,"detail",Map.of("reasonCode",mode.substring(7),"message","internal /private/model path"));
                    var contract=new LinkedHashMap<String,Object>(Map.of("status","READY","type","UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE"));
                    if("benchmark".equals(mode))contract.put("type","OTHER");
                    if("name".equals(mode))contract.put("name","changed");
                    if("status".equals(mode))contract.put("status","OTHER");
                    yield Map.of("compatible",!"rejected".equals(mode),"runtime",f.environment,"effectiveConfig",effective,
                        "assumptions","assumptions".equals(mode)?List.of("changed"):"count".equals(mode)?List.of("fixed assumptions","extra"):List.of("fixed assumptions"),
                        "benchmarkContract",contract);
                }
                case "candidates" -> Map.of("parameterKey","slowWindow","baseline",60,"delta",6,"values",List.of(48,54,60,66,72),"ruleVersion","rule");
                default -> RETURNS_DEFAULTS.answer(invocation);
            };
        });
        f.experiments=new ExperimentService(f.db,f.repository,f.snapshots,new ExperimentCandidateService(client),client,f.attempts,new DataSourceTransactionManager(f.db.getDataSource()));
    }
    @ParameterizedTest @ValueSource(strings={"assumptions","count","benchmark","name","status","change","default"})
    void contractChangesFailBeforePersistence(String value) {
        mode=value;
        String reason=switch(value) {
            case "assumptions","count" -> "CURRENT_RUNTIME_ASSUMPTIONS_CHANGED";
            case "benchmark","name","status" -> "CURRENT_RUNTIME_BENCHMARK_CONTRACT_CHANGED";
            default -> "CURRENT_RUNTIME_CONFIG_CHANGED";
        };
        assertThatThrownBy(f::create).isInstanceOfSatisfying(ExperimentException.class,e->{
            assertThat(e.code()).isEqualTo("CURRENT_RUNTIME_CONFIG_INCOMPATIBLE");
            assertThat(e.reasonCode()).isEqualTo(reason);
        });
        assertNoArtifacts();
    }
    @ParameterizedTest @ValueSource(strings={"MODEL_CONFIG_MISMATCH","MODEL_NOT_FOUND","INVALID_CONFIG"})
    void engineReasonSurvivesService(String reason) {
        mode="ENGINE_"+reason;
        assertThatThrownBy(f::create).isInstanceOfSatisfying(ExperimentException.class,e->{
            assertThat(e.code()).isEqualTo("CURRENT_RUNTIME_CONFIG_INCOMPATIBLE");
            assertThat(e.reasonCode()).isEqualTo(reason);
            assertThat(e.safeMessage()).isNotBlank().doesNotContain("/private");
        });
        assertNoArtifacts();
    }
    @ParameterizedTest @ValueSource(strings={"MODEL_CONFIG_MISMATCH","MODEL_NOT_FOUND","INVALID_CONFIG"})
    void realHttpErrorRetainsReasonThroughService(String reason) throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            boolean runtime=exchange.getRequestURI().getPath().endsWith("runtime-info");
            byte[] body=encode(runtime?f.environment:Map.of("detail",Map.of("code","CURRENT_RUNTIME_CONFIG_INCOMPATIBLE",
                    "reasonCode",reason,"message","internal /private/model path"))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(runtime?200:422,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var client=new WorkbenchAnalysisClient(org.springframework.web.client.RestClient.builder(),
                    "http://127.0.0.1:"+server.getAddress().getPort(),"token",java.time.Duration.ofSeconds(2));
            f.experiments=new ExperimentService(f.db,f.repository,f.snapshots,new ExperimentCandidateService(client),client,f.attempts,
                    new DataSourceTransactionManager(f.db.getDataSource()));
            assertThatThrownBy(f::create).isInstanceOfSatisfying(ExperimentException.class,e->{
                assertThat(e.code()).isEqualTo("CURRENT_RUNTIME_CONFIG_INCOMPATIBLE");
                assertThat(e.reasonCode()).isEqualTo(reason);
                assertThat(e.safeMessage()).doesNotContain("/private");
            });
            assertNoArtifacts();
        } finally {server.stop(0);}
    }
    private void assertNoArtifacts() {
        for(String table:List.of("quant_v2_research_snapshot","quant_v2_experiment","quant_v2_experiment_run","quant_v2_experiment_run_attempt"))
            assertThat(f.db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM quant_v2_task",Integer.class)).isEqualTo(1);
    }
    @Test void sourceDynamicBenchmarkValuesDoNotChangeContract() {
        var response=f.saved(f.source);
        var benchmark=map(ExperimentFlowTest.mapResult(response).get("benchmark"));
        benchmark.put("benchmarkReturn",123.0);benchmark.put("excessReturn",-99.0);
        benchmark.put("equityCurve",List.of(Map.of("equity",123)));
        ExperimentFlowTest.mapResult(response).put("benchmark",benchmark);
        f.db.update("UPDATE quant_v2_task SET result_json=? WHERE id=?",encode(response),f.source);
        assertThat(f.create().sourceContext().get("benchmarkContract")).isEqualTo(Map.of("status","READY","type","UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE"));
    }
    @Test void oldSourceUsesCurrentRuntimeValidationWithFrozenInputs() {
        var e=f.create();assertThat(validated).isNotNull();assertThat(validated).containsEntry("assets",f.original.get("assets"))
            .containsEntry("config",f.original.get("config")).containsEntry("startDate",f.original.get("startDate"))
            .containsEntry("strategyVersionId","strategy-v1");
        assertThat(e.environment()).isEqualTo(f.environment);assertThat(map(e.sourceContext().get("runtime"))).containsEntry("engineVersion","old");
    }
    @Test void numericNormalizationDoesNotReplaceExactFrozenConfig() {
        mode="number";var e=f.create();assertThat(validated).isNotNull();
        assertThat(map(e.baseRequest().get("config"))).containsEntry("seed",42);
        assertThat(map(map(e.sourceContext().get("provenance")).get("config"))).containsEntry("seed",42);
    }
    @ParameterizedTest @ValueSource(strings={"change","default","rejected"}) void incompatibilityLeavesNoArtifacts(String value) {
        mode=value;assertThatThrownBy(f::create).hasMessage("CURRENT_RUNTIME_CONFIG_INCOMPATIBLE");
        for(String table:List.of("quant_v2_research_snapshot","quant_v2_experiment","quant_v2_experiment_run","quant_v2_experiment_run_attempt"))
            assertThat(f.db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM quant_v2_task",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"network","server"}) void transportFailuresAreNotConfigIncompatibilities(String value) {
        mode=value;assertThatThrownBy(f::create).isInstanceOf(org.springframework.web.client.RestClientException.class)
            .hasMessageNotContaining("CURRENT_RUNTIME_CONFIG_INCOMPATIBLE");
    }
}
