package com.smartfinance.agent.investment.quant.workbench.experiment;

import java.util.List;
import java.util.Map;

public final class ExperimentProductDtos {
    private ExperimentProductDtos() {}

    public record CreateRequest(String sourceBacktestId, String parameterKey, String name) {}

    public record ParameterResponse(String key, Object baseline, List<?> candidates) {}

    public record ExperimentListItemResponse(
            String id, String name, String status, String sourceBacktestId,
            String strategyId, String strategyVersionId, ParameterResponse parameter,
            String classification, String performanceProfile, String evidenceQuality,
            Integer progress, int revision, String createdAt, String updatedAt, String completedAt) {}

    public record StrategyResponse(String id, String versionId, Integer version, String name) {}
    public record SourceBacktestResponse(String id, String name, String status) {}
    public record ResearchWindowResponse(String startDate, String endDate) {}

    public record QualificationSummaryResponse(
            Integer qualified, Integer unqualified, Integer missing, Map<String, Object> reasons) {}

    public record ExperimentSummaryResponse(
            String classification, String performanceProfile, List<?> stableRange,
            String direction, Object directionConsistency, Number returnTolerance,
            Number drawdownTolerance, Map<String, Object> localSensitivity,
            Boolean isolatedPeak, List<String> reasons,
            QualificationSummaryResponse qualificationSummary) {}

    public record EvidenceResponse(
            String quality, String controlIntegrity, String dataConsistency,
            String sourceCompleteness, String runCoverage, Integer validRuns,
            Integer totalRuns, Integer excludedRunCount,
            Map<String, Object> reasonClassifications) {}

    public record QualificationResponse(String status, List<String> reasons, String scope) {}
    public record MetricsResponse(
            Number netReturn, Number maxDrawdown, Number volatility,
            Number turnover, Number tradeCount) {}
    public record ValidationResponse(
            Boolean valid, String code, List<?> mismatches, String validatorVersion) {}
    public record RunErrorResponse(String code, String message) {}

    public record RunResponse(
            String id, int ordinal, Object value, boolean baseline,
            String status, String stage, Integer progress, String attemptId,
            QualificationResponse qualification, MetricsResponse metrics,
            ValidationResponse validation, RunErrorResponse error, String updatedAt) {}

    public record SnapshotResponse(
            String id, String contentHash, String formatVersion, Map<String, Object> metadata) {}

    public record ProvenanceResponse(
            String candidateRuleVersion, String stabilityAlgorithmVersion,
            String evidenceSchemaVersion, Map<String, Object> sourceRuntime,
            Map<String, Object> experimentRuntime, SnapshotResponse snapshot,
            List<?> assumptions, Map<String, Object> benchmarkContract) {}

    public record ExperimentDetailResponse(
            String id, String name, String status, int revision, Integer progress,
            ParameterResponse parameter, StrategyResponse strategy,
            SourceBacktestResponse sourceBacktest, ResearchWindowResponse researchWindow,
            ExperimentSummaryResponse summary, List<RunResponse> runs,
            EvidenceResponse evidence, ProvenanceResponse provenance,
            String createdAt, String updatedAt, String completedAt) {}

    public record EligibilityResponse(boolean eligible, String reasonCode, String message) {}
}
