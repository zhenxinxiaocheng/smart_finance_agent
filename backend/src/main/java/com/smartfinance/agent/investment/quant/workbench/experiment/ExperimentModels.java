package com.smartfinance.agent.investment.quant.workbench.experiment;

import java.util.List;
import java.util.Map;

public final class ExperimentModels {
    private ExperimentModels() {}

    public record ExperimentDraft(
            String id, Long userId, String name, String status, String sourceBacktestId,
            String strategyId, String strategyVersionId, String universeId, String universeVersionId,
            String factorId, String factorVersionId, String snapshotId, String modelRef, String modelTaskId,
            Object variableDefinition, Object baselineValues, Object candidateValues,
            Map<String, Object> baseRequest, Map<String, Object> sourceContext,
            Map<String, Object> environment, String candidateRuleVersion,
            String stabilityAlgorithmVersion, String invariantHash, String requestKey, String requestHash) {}

    public record ExperimentView(
            String id, Long userId, String name, String status, String sourceBacktestId,
            String strategyId, String strategyVersionId, String universeId, String universeVersionId,
            String factorId, String factorVersionId, String snapshotId, String modelRef, String modelTaskId,
            Object variableDefinition, Object baselineValues, Object candidateValues,
            Map<String, Object> baseRequest, Map<String, Object> sourceContext,
            Map<String, Object> environment, String candidateRuleVersion,
            String stabilityAlgorithmVersion, String invariantHash, Object summary,
            String requestKey, String requestHash, int revision, String createdAt, String updatedAt,
            String completedAt, String cancelledAt) {}

    public record RunDraft(
            String id, Long userId, String experimentId, int ordinal,
            Map<String, Object> variableValues, String valueHash, boolean baseline) {}

    public record RunView(
            String id, Long userId, String experimentId, int ordinal,
            Map<String, Object> variableValues, String valueHash, boolean baseline,
            String activeAttemptId, String createdAt) {}

    public record AttemptDraft(
            String id, Long userId, String runId, int attemptNo, String reason, String taskId) {}

    public record AttemptView(
            String id, Long userId, String runId, int attemptNo, String reason,
            String taskId, String createdAt) {}

    public record SnapshotView(
            String id, Long userId, String formatVersion, String contentHash,
            String compression, Map<String, Object> metadata,
            long uncompressedBytes, long compressedBytes, String createdAt) {}
}
