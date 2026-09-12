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
                    yield Map.of("compatible",!"rejected".equals(mode),"runtime",f.environment,"effectiveConfig",effective);
                }
                case "candidates" -> Map.of("parameterKey","slowWindow","baseline",60,"delta",6,"values",List.of(48,54,60,66,72),"ruleVersion","rule");
                default -> RETURNS_DEFAULTS.answer(invocation);
            };
        });
        f.experiments=new ExperimentService(f.db,f.repository,f.snapshots,new ExperimentCandidateService(client),client,f.attempts,new DataSourceTransactionManager(f.db.getDataSource()));
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
