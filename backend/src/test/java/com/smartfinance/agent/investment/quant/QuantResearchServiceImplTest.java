package com.smartfinance.agent.investment.quant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantResearchServiceImplTest {

    @Test
    void createsExperimentAndStartsExistingQuantJobFlow() {
        BenchmarkProfileMapper benchmarkMapper = mock(BenchmarkProfileMapper.class);
        QuantExperimentMapper experimentMapper = mock(QuantExperimentMapper.class);
        QuantValidationReportMapper reportMapper = mock(QuantValidationReportMapper.class);
        QuantModelVersionMapper modelMapper = mock(QuantModelVersionMapper.class);
        QuantStrategyVersionMapper strategyMapper = mock(QuantStrategyVersionMapper.class);
        QuantResearchUniverseMapper universeMapper = mock(QuantResearchUniverseMapper.class);
        QuantUniverseMembershipMapper membershipMapper = mock(QuantUniverseMembershipMapper.class);
        QuantService quantService = mock(QuantService.class);
        QuantResearchServiceImpl service = new QuantResearchServiceImpl(
                benchmarkMapper,
                experimentMapper,
                reportMapper,
                modelMapper,
                strategyMapper,
                universeMapper,
                membershipMapper,
                quantService,
                new ObjectMapper()
        );
        when(quantService.refreshExperiment(
                eq(7L),
                eq(12L),
                isNull(),
                eq("SHORT"),
                anyString(),
                eq("A_SHARE_STOCK"),
                eq("VALIDATED_ENSEMBLE"),
                eq(Map.of("learningRate", 0.05))
        ))
                .thenReturn(Map.of("jobId", "a".repeat(32), "status", "QUEUED"));

        Map<String, Object> result = service.createExperiment(
                7L,
                new QuantResearchController.ExperimentRequest(
                        12L,
                        "A_SHARE_STOCK",
                        "SHORT",
                        Map.of("learningRate", 0.05)
                )
        );

        assertThat(result)
                .containsEntry("status", "QUEUED")
                .containsEntry("modelFamily", "A_SHARE_STOCK")
                .containsEntry("horizonDays", 20);
        assertThat(result.get("experimentFingerprint").toString()).hasSize(64);
        verify(experimentMapper).insert(any(QuantExperiment.class));
        verify(quantService).refreshExperiment(
                7L,
                12L,
                null,
                "SHORT",
                result.get("experimentFingerprint").toString(),
                "A_SHARE_STOCK",
                "VALIDATED_ENSEMBLE",
                Map.of("learningRate", 0.05)
        );
    }

    @Test
    void duplicateFingerprintReturnsExistingExperimentWithoutRetraining() {
        BenchmarkProfileMapper benchmarkMapper = mock(BenchmarkProfileMapper.class);
        QuantExperimentMapper experimentMapper = mock(QuantExperimentMapper.class);
        QuantValidationReportMapper reportMapper = mock(QuantValidationReportMapper.class);
        QuantModelVersionMapper modelMapper = mock(QuantModelVersionMapper.class);
        QuantStrategyVersionMapper strategyMapper = mock(QuantStrategyVersionMapper.class);
        QuantResearchUniverseMapper universeMapper = mock(QuantResearchUniverseMapper.class);
        QuantUniverseMembershipMapper membershipMapper = mock(QuantUniverseMembershipMapper.class);
        QuantService quantService = mock(QuantService.class);
        QuantResearchServiceImpl service = new QuantResearchServiceImpl(
                benchmarkMapper,
                experimentMapper,
                reportMapper,
                modelMapper,
                strategyMapper,
                universeMapper,
                membershipMapper,
                quantService,
                new ObjectMapper()
        );
        QuantExperiment existing = new QuantExperiment();
        existing.setId(99L);
        existing.setUserId(7L);
        existing.setAssetId(12L);
        existing.setModelFamily("A_SHARE_STOCK");
        existing.setHorizonCode("SHORT");
        existing.setHorizonDays(20);
        existing.setStatus("RUNNING");
        existing.setExperimentFingerprint("f".repeat(64));
        existing.setConfigJson("{}");
        existing.setQuantConfigVersion("quant-research-v2");
        existing.setCodeVersion("quant-research-lab-v1");
        when(experimentMapper.selectOne(any())).thenReturn(existing);

        Map<String, Object> result = service.createExperiment(
                7L,
                new QuantResearchController.ExperimentRequest(
                        12L,
                        "A_SHARE_STOCK",
                        "SHORT",
                        Map.of("learningRate", 0.05)
                )
        );

        assertThat(result).containsEntry("id", 99L).containsEntry("reused", true);
        verify(experimentMapper, never()).insert(any());
        verify(quantService, never()).refreshExperiment(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void promotingValidatedExperimentActivatesPaperModelExplicitly() {
        BenchmarkProfileMapper benchmarkMapper = mock(BenchmarkProfileMapper.class);
        QuantExperimentMapper experimentMapper = mock(QuantExperimentMapper.class);
        QuantValidationReportMapper reportMapper = mock(QuantValidationReportMapper.class);
        QuantModelVersionMapper modelMapper = mock(QuantModelVersionMapper.class);
        QuantStrategyVersionMapper strategyMapper = mock(QuantStrategyVersionMapper.class);
        QuantResearchUniverseMapper universeMapper = mock(QuantResearchUniverseMapper.class);
        QuantUniverseMembershipMapper membershipMapper = mock(QuantUniverseMembershipMapper.class);
        QuantService quantService = mock(QuantService.class);
        QuantResearchServiceImpl service = new QuantResearchServiceImpl(
                benchmarkMapper,
                experimentMapper,
                reportMapper,
                modelMapper,
                strategyMapper,
                universeMapper,
                membershipMapper,
                quantService,
                new ObjectMapper()
        );
        QuantExperiment experiment = new QuantExperiment();
        experiment.setId(91L);
        experiment.setUserId(7L);
        experiment.setAssetId(12L);
        experiment.setModelFamily("A_SHARE_STOCK");
        experiment.setHorizonCode("SHORT");
        experiment.setHorizonDays(20);
        experiment.setStatus("SUCCEEDED");
        experiment.setExperimentFingerprint("f".repeat(64));
        experiment.setConfigJson("{\"algorithm\":\"VALIDATED_ENSEMBLE\",\"parameters\":{}}");
        experiment.setQuantConfigVersion("quant-research-v2");
        experiment.setCodeVersion("quant-research-lab-v1");
        QuantValidationReport report = new QuantValidationReport();
        report.setExperimentId(91L);
        report.setModelVersion("model-v1");
        report.setLifecycle("VALIDATED");
        report.setPassed(true);
        report.setFailureCodesJson("[]");
        report.setChecksJson("[]");
        report.setMetricsJson("{}");
        QuantModelVersion model = new QuantModelVersion();
        model.setModelVersion("model-v1");
        model.setStatus("VALIDATED");
        QuantStrategyVersion strategy = new QuantStrategyVersion();
        strategy.setModelVersion("model-v1");
        strategy.setStatus("DRAFT");
        when(experimentMapper.selectOne(any())).thenReturn(experiment);
        when(reportMapper.selectOne(any())).thenReturn(report);
        when(modelMapper.selectOne(any())).thenReturn(model);
        when(strategyMapper.selectOne(any())).thenReturn(strategy);

        service.promoteExperiment(7L, 91L);

        verify(quantService).activatePaperModel(7L, "model-v1");
        assertThat(strategy.getStatus()).isEqualTo("PAPER");
        assertThat(strategy.getActivatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }
}
