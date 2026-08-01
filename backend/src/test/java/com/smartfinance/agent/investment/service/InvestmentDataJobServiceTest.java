package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.mapper.InvestmentDataJobMapper;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvestmentDataJobServiceTest {

    private InvestmentDataJobMapper mapper;
    private InvestmentDataJobService service;

    @BeforeEach
    void setUp() {
        mapper = mock(InvestmentDataJobMapper.class);
        service = new InvestmentDataJobService(mapper);
    }

    @Test
    void claimAlwaysRequiresCallerGeneratedLeaseToken() {
        assertThat(Arrays.stream(InvestmentDataJobService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("claim")))
                .allMatch(method -> method.getParameterCount() == 4);
    }

    @Test
    void claimedSnapshotReturnsRunningJobOwnedByLeaseToken() {
        InvestmentDataJob claimed = job("RUNNING", "STOCK_HISTORY");
        claimed.setId(91L);
        claimed.setLeaseToken("worker-token");
        when(mapper.selectOne(any())).thenReturn(claimed);

        InvestmentDataJob result = service.claimedSnapshot(91L, "worker-token");

        assertThat(result).isSameAs(claimed);
        verify(mapper).selectOne(any());
    }

    @Test
    void firstQueueCreatesStockHistoryJob() {
        when(mapper.insert(any())).thenAnswer(invocation -> {
            InvestmentDataJob job = invocation.getArgument(0);
            job.setId(91L);
            return 1;
        });

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", false);

        assertThat(job.getId()).isEqualTo(91L);
        assertThat(job.getUserId()).isEqualTo(7L);
        assertThat(job.getAssetId()).isEqualTo(11L);
        assertThat(job.getProductId()).isEqualTo(21L);
        assertThat(job.getJobType()).isEqualTo("STOCK_HISTORY");
        assertThat(job.getStatus()).isEqualTo("QUEUED");
        assertThat(job.getForceRefresh()).isFalse();
        verify(mapper).insert(job);
    }

    @Test
    void sameAssetAndTypeReusesExistingActiveJob() {
        InvestmentDataJob existing = job("RUNNING", "STOCK_HISTORY");
        existing.setId(91L);
        when(mapper.selectOne(any())).thenReturn(existing);

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", false);

        assertThat(job).isSameAs(existing);
        verify(mapper, never()).insert(any());
        verify(mapper, never()).updateById(any());
    }

    @Test
    void fundUsesFundNavHistoryJobType() {
        when(mapper.insert(any())).thenReturn(1);

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "FUND", false);

        assertThat(job.getJobType()).isEqualTo("FUND_NAV_HISTORY");
    }

    @Test
    void mutualFundUsesFundNavHistoryJobType() {
        when(mapper.insert(any())).thenReturn(1);

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "MUTUAL_FUND", false);

        assertThat(job.getJobType()).isEqualTo("FUND_NAV_HISTORY");
    }

    @Test
    void benchmarkPreparationUsesDedicatedReusableJobType() {
        when(mapper.insert(any())).thenReturn(1);

        InvestmentDataJob job = service.ensureBenchmarkQueued(7L, 11L, 21L);

        assertThat(job.getUserId()).isEqualTo(7L);
        assertThat(job.getAssetId()).isEqualTo(11L);
        assertThat(job.getProductId()).isEqualTo(21L);
        assertThat(job.getJobType()).isEqualTo("BENCHMARK_HISTORY");
        assertThat(job.getStatus()).isEqualTo("QUEUED");
        verify(mapper).insert(job);
    }

    @Test
    void unknownProductTypeIsRejected() {
        assertThatThrownBy(() -> service.ensureQueued(7L, 11L, 21L, "BOND", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("产品类型");
    }

    @Test
    void duplicateInsertRequeriesAndReturnsTheWinningJob() {
        InvestmentDataJob winner = job("QUEUED", "STOCK_HISTORY");
        winner.setId(92L);
        when(mapper.selectOne(any())).thenReturn(null, winner);
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("unique key"));

        InvestmentDataJob result = service.ensureQueued(7L, 11L, 21L, "STOCK", false);

        assertThat(result).isSameAs(winner);
        verify(mapper, times(2)).selectOne(any());
    }

    @Test
    void manualRefreshResetsTerminalJobForForcedReload() {
        InvestmentDataJob existing = job("FAILED", "STOCK_HISTORY");
        existing.setAttemptCount(3);
        existing.setRecordCount(18);
        existing.setErrorMessage("upstream unavailable");
        existing.setNextRetryAt(LocalDateTime.of(2026, 7, 22, 10, 30));
        existing.setLeaseUntil(LocalDateTime.of(2026, 7, 22, 10, 5));
        existing.setLeaseToken("old-worker");
        existing.setStartedAt(LocalDateTime.of(2026, 7, 22, 9, 55));
        existing.setFinishedAt(LocalDateTime.of(2026, 7, 22, 10, 0));
        when(mapper.selectOne(any())).thenReturn(existing);

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", true);

        assertThat(job.getStatus()).isEqualTo("QUEUED");
        assertThat(job.getForceRefresh()).isTrue();
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getErrorMessage()).isNull();
        assertThat(job.getNextRetryAt()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(job.getLeaseToken()).isNull();
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getFinishedAt()).isNull();
        verify(mapper).updateById(existing);
    }

    @Test
    void anotherUserCannotReadAssetJobStatus() {
        when(mapper.selectOne(any())).thenReturn(null);

        var status = service.statusForAsset(8L, 11L);

        assertThat(status).isEmpty();
    }

    @Test
    void statusIncludesAttemptCount() {
        InvestmentDataJob existing = job("RETRY_WAIT", "STOCK_HISTORY");
        existing.setAttemptCount(2);
        when(mapper.selectOne(any())).thenReturn(existing);

        var status = service.statusForAsset(7L, 11L);

        assertThat(status).containsEntry("attemptCount", 2);
    }

    @Test
    void statusExposesOnlySafeHistoryJobFields() {
        InvestmentDataJob existing = job("RUNNING", "STOCK_HISTORY");
        existing.setRecordCount(12);
        existing.setAttemptCount(1);
        existing.setErrorMessage("temporary");
        existing.setStartedAt(LocalDateTime.of(2026, 7, 22, 9, 55));
        existing.setFinishedAt(LocalDateTime.of(2026, 7, 22, 9, 56));
        existing.setUpdatedAt(LocalDateTime.of(2026, 7, 22, 10, 0));
        existing.setRequestedStartDate(LocalDate.of(2001, 8, 27));
        existing.setSampleStartDate(LocalDate.of(2001, 8, 27));
        existing.setSampleEndDate(LocalDate.of(2026, 7, 21));
        existing.setCoverageComplete(true);
        existing.setDatasetVersion("dataset-v1");
        when(mapper.selectOne(any())).thenReturn(existing);

        var status = service.statusForAsset(7L, 11L);

        assertThat(status).containsOnlyKeys(
                "status", "recordCount", "attemptCount", "errorMessage", "updatedAt",
                "requestedStartDate", "sampleStartDate", "sampleEndDate",
                "coverageComplete", "datasetVersion");
        assertThat(status).containsEntry("coverageComplete", true);
    }

    @Test
    void recordsActualCoverageOnlyForCurrentLeaseOwner() {
        when(mapper.updateCoverage(
                91L,
                "worker-token",
                LocalDate.of(2001, 8, 27),
                LocalDate.of(2001, 8, 27),
                LocalDate.of(2026, 7, 21),
                true,
                "dataset-v1")).thenReturn(1);

        boolean updated = service.recordCoverage(
                91L,
                "worker-token",
                new InvestmentHistoryPreparationService.PreparationResult(
                        5_987,
                        LocalDate.of(2001, 8, 27),
                        LocalDate.of(2001, 8, 27),
                        LocalDate.of(2026, 7, 21),
                        true,
                        "dataset-v1"));

        assertThat(updated).isTrue();
    }

    private static InvestmentDataJob job(String status, String jobType) {
        InvestmentDataJob job = new InvestmentDataJob();
        job.setUserId(7L);
        job.setAssetId(11L);
        job.setProductId(21L);
        job.setJobType(jobType);
        job.setStatus(status);
        job.setForceRefresh(false);
        job.setAttemptCount(0);
        job.setRecordCount(0);
        return job;
    }
}
