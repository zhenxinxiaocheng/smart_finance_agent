package com.smartfinance.agent.investment.quant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
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
    void comparesOnlyCompatibleExperimentsAndExplainsTheNextDirection()
            throws Exception {
        BenchmarkProfileMapper benchmarkMapper = mock(BenchmarkProfileMapper.class);
        QuantExperimentMapper experimentMapper = mock(QuantExperimentMapper.class);
        QuantValidationReportMapper reportMapper = mock(QuantValidationReportMapper.class);
        QuantModelVersionMapper modelMapper = mock(QuantModelVersionMapper.class);
        QuantStrategyVersionMapper strategyMapper = mock(QuantStrategyVersionMapper.class);
        QuantResearchUniverseMapper universeMapper = mock(QuantResearchUniverseMapper.class);
        QuantUniverseMembershipMapper membershipMapper = mock(QuantUniverseMembershipMapper.class);
        QuantService quantService = mock(QuantService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        QuantResearchServiceImpl service = new QuantResearchServiceImpl(
                benchmarkMapper, experimentMapper, reportMapper, modelMapper,
                strategyMapper, universeMapper, membershipMapper, quantService,
                objectMapper
        );
        QuantExperiment current = experiment(
                2L,
                "XGBOOST",
                Map.of("maximumDepth", 2, "learningRate", 0.03)
        );
        current.setBaselineComparisonJson(objectMapper.writeValueAsString(Map.of(
                "netExcessVsStrongestBaseline", 0.018
        )));
        current.setSearchSummaryJson(objectMapper.writeValueAsString(Map.of(
                "failureDiagnosis", Map.of(
                        "code", "COST_TOO_HIGH",
                        "message", "换手或交易成本吞噬了策略优势"
                ),
                "nextAdjustment", "延长信号窗口并使用波动率目标抑制换手"
        )));
        QuantExperiment previous = experiment(
                1L,
                "XGBOOST",
                Map.of("maximumDepth", 5, "learningRate", 0.1)
        );
        previous.setBaselineComparisonJson(objectMapper.writeValueAsString(Map.of(
                "netExcessVsStrongestBaseline", 0.006
        )));
        QuantValidationReport currentReport = report(
                2L, "{\"maximumDrawdown\":-0.12}"
        );
        QuantValidationReport previousReport = report(
                1L, "{\"maximumDrawdown\":-0.19}"
        );
        when(experimentMapper.selectList(any())).thenReturn(List.of(current));
        when(experimentMapper.selectOne(any())).thenReturn(previous);
        when(reportMapper.selectOne(any()))
                .thenReturn(currentReport, previousReport);

        Map<String, Object> view = service.experiments(7L).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> comparison =
                (Map<String, Object>) view.get("comparison");

        assertThat(comparison)
                .containsEntry("comparable", true)
                .containsEntry(
                        "nextSuggestion",
                        "延长信号窗口并使用波动率目标抑制换手"
                );
        assertThat((Double) comparison.get("netExcessChange"))
                .isCloseTo(0.012, org.assertj.core.data.Offset.offset(1e-12));
        assertThat((Double) comparison.get("drawdownImprovement"))
                .isCloseTo(0.07, org.assertj.core.data.Offset.offset(1e-12));
        assertThat((List<?>) comparison.get("parameterChanges")).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> bottleneck =
                (Map<String, Object>) comparison.get("bottleneck");
        assertThat(bottleneck)
                .containsEntry("code", "COST_TOO_HIGH");
    }

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
                eq("REGIME_ENSEMBLE"),
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
                "REGIME_ENSEMBLE",
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

    private static QuantExperiment experiment(
            Long id,
            String algorithm,
            Map<String, Object> parameters
    ) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        QuantExperiment experiment = new QuantExperiment();
        experiment.setId(id);
        experiment.setUserId(7L);
        experiment.setAssetId(12L);
        experiment.setModelFamily("A_SHARE_STOCK");
        experiment.setHorizonCode("SHORT");
        experiment.setHorizonDays(20);
        experiment.setStatus("SUCCEEDED");
        experiment.setDatasetVersion("dataset-v1");
        experiment.setFeatureSetVersion("features-v1");
        experiment.setQuantConfigVersion("quant-research-v2");
        experiment.setCodeVersion("algorithm-v2");
        experiment.setConfigJson(mapper.writeValueAsString(Map.of(
                "algorithm", algorithm,
                "parameters", parameters
        )));
        experiment.setSearchSummaryJson("{}");
        experiment.setBaselineComparisonJson("{}");
        return experiment;
    }

    private static QuantValidationReport report(
            Long experimentId,
            String metrics
    ) {
        QuantValidationReport report = new QuantValidationReport();
        report.setExperimentId(experimentId);
        report.setModelVersion("model-" + experimentId);
        report.setLifecycle("DRAFT");
        report.setPassed(false);
        report.setFailureCodesJson("[\"MODEL_REJECTED\"]");
        report.setChecksJson("[]");
        report.setMetricsJson(metrics);
        report.setDatasetVersion("dataset-v1");
        report.setFeatureSetVersion("features-v1");
        report.setQuantConfigVersion("quant-research-v2");
        return report;
    }
}
