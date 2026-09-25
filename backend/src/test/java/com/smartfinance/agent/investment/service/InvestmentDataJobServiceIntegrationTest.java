package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.config.MyBatisPlusConfig;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.mapper.InvestmentDataJobMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = InvestmentDataJobServiceIntegrationTest.Configuration.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:investment_data_jobs;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class InvestmentDataJobServiceIntegrationTest {

    @Autowired
    private InvestmentDataJobService service;
    @Autowired
    private InvestmentDataJobMapper mapper;

    @Test
    void uniqueAssetAndTypeConstraintPreventsDuplicateJobs() {
        InvestmentDataJob first = service.ensureQueued(7L, 11L, 21L, "STOCK", false);

        InvestmentDataJob duplicate = new InvestmentDataJob();
        duplicate.setUserId(7L);
        duplicate.setAssetId(11L);
        duplicate.setProductId(21L);
        duplicate.setJobType("STOCK_HISTORY");
        duplicate.setStatus("QUEUED");
        duplicate.setForceRefresh(false);
        duplicate.setRecordCount(0);
        duplicate.setAttemptCount(0);

        assertThatThrownBy(() -> mapper.insert(duplicate)).isInstanceOf(DuplicateKeyException.class);
        assertThat(service.ensureQueued(7L, 11L, 21L, "STOCK", false).getId()).isEqualTo(first.getId());
    }

    @Test
    void expiredRunningLeaseIsPendingAndCanBeReclaimed() {
        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", false);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        assertThat(service.claim(job.getId(), now.minusMinutes(10), now.minusMinutes(1), "expired-worker")).isTrue();

        assertThat(service.pendingJobs(10)).extracting(InvestmentDataJob::getId).contains(job.getId());
        assertThat(service.claim(job.getId(), now, now.plusMinutes(5), "new-worker")).isTrue();
        assertThat(mapper.selectById(job.getId()).getLeaseToken()).isEqualTo("new-worker");
    }

    @Test
    void expiredWorkerTokenCannotFinishJobAfterReclaim() {
        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", false);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        assertThat(service.claim(job.getId(), now, now.plusSeconds(30), "stale-worker")).isTrue();
        assertThat(service.claim(job.getId(), now.plusMinutes(1), now.plusMinutes(6), "fresh-worker")).isTrue();

        assertThat(service.markSucceeded(job.getId(), "stale-worker", 20, now)).isFalse();
        assertThat(mapper.selectById(job.getId()).getStatus()).isEqualTo("RUNNING");
        assertThat(service.markSucceeded(job.getId(), "fresh-worker", 20, now)).isTrue();
        assertThat(mapper.selectById(job.getId()).getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void statusViewDoesNotExposeAnotherUsersJob() {
        service.ensureQueued(7L, 11L, 21L, "STOCK", false);

        assertThat(service.statusForAsset(8L, 11L)).isEmpty();
    }

    @Test
    void concurrentRequeueCannotResetAQueuedOrClaimedJob() {
        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", true);
        LocalDateTime now = LocalDateTime.now();
        assertThat(service.claim(job.getId(), now, now.plusMinutes(5), "worker-1")).isTrue();
        assertThat(service.markSucceeded(job.getId(), "worker-1", 30, now)).isTrue();
        assertThat(mapper.requeueTerminal(job.getId())).isEqualTo(1);
        assertThat(mapper.requeueTerminal(job.getId())).isZero();
        assertThat(service.claim(job.getId(), now, now.plusMinutes(5), "worker-2")).isTrue();
        assertThat(mapper.requeueTerminal(job.getId())).isZero();
        assertThat(mapper.selectById(job.getId()).getLeaseToken()).isEqualTo("worker-2");
    }

    @Test
    void completedJobCanBeQueuedForIncrementalRefreshOnlyOnce() {
        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", false);
        LocalDateTime now = LocalDateTime.now();
        assertThat(service.claim(job.getId(), now, now.plusMinutes(5), "worker-1")).isTrue();
        assertThat(service.markSucceeded(job.getId(), "worker-1", 30, now)).isTrue();

        assertThat(mapper.requeueTerminalIncremental(job.getId())).isEqualTo(1);
        assertThat(mapper.requeueTerminalIncremental(job.getId())).isZero();
        InvestmentDataJob queued = mapper.selectById(job.getId());
        assertThat(queued.getStatus()).isEqualTo("QUEUED");
        assertThat(queued.getForceRefresh()).isFalse();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.smartfinance.agent.investment.mapper")
    @Import({MyBatisPlusConfig.class, InvestmentDataJobService.class,
            QuoteSeriesCoverageService.class, QuoteSeriesPolicy.class,
            com.smartfinance.agent.investment.config.InvestmentRuntimeProperties.class})
    static class Configuration {
    }
}
