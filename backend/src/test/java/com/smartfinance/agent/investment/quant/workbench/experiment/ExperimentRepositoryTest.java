package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentRepositoryTest {
    private ExperimentRepository repository;
    private ExperimentTaskStore tasks;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var db = new JdbcTemplate(source);
        var schema = new ResourceDatabasePopulator(
                new ClassPathResource("schema-h2.sql"));
        schema.execute(source);
        var json = new ObjectMapper();
        repository = new ExperimentRepository(db, json);
        tasks = new ExperimentTaskStore(db, json);
    }

    @Test
    void runKeepsEveryTaskAttemptAndSelectsOneActiveAttempt() {
        repository.insertExperiment(experiment("experiment-1", 7L, "snapshot-1"));
        repository.insertRun(new ExperimentModels.RunDraft(
                "run-1", 7L, "experiment-1", 0, Map.of("slowWindow", 60), "value-hash", true));
        String firstTask = tasks.enqueueBacktest(7L, "first", "strategy-1", "strategy-version-1",
                "universe-1", Map.of("kind", "BACKTEST"));
        String secondTask = tasks.enqueueBacktest(7L, "retry", "strategy-1", "strategy-version-1",
                "universe-1", Map.of("kind", "BACKTEST"));
        repository.insertAttempt(new ExperimentModels.AttemptDraft(
                "attempt-1", 7L, "run-1", 1, "INITIAL", firstTask));
        repository.insertAttempt(new ExperimentModels.AttemptDraft(
                "attempt-2", 7L, "run-1", 2, "RETRY", secondTask));
        repository.activateAttempt(7L, "run-1", "attempt-2");

        assertThat(repository.attempts(7L, "run-1"))
                .extracting(ExperimentModels.AttemptView::taskId)
                .containsExactly(firstTask, secondTask);
        assertThat(repository.run(7L, "run-1").activeAttemptId()).isEqualTo("attempt-2");
        assertThat(repository.isExperimentTask(7L, firstTask)).isTrue();
        assertThat(repository.isExperimentTask(8L, firstTask)).isFalse();
    }

    @Test
    void runUniquenessBelongsToRunAndAttemptUniquenessBelongsToAttempt() {
        repository.insertExperiment(experiment("experiment-1", 7L, "snapshot-1"));
        var run = new ExperimentModels.RunDraft(
                "run-1", 7L, "experiment-1", 0, Map.of("slowWindow", 60), "value-hash", true);
        repository.insertRun(run);

        assertThatThrownBy(() -> repository.insertRun(new ExperimentModels.RunDraft(
                "run-2", 7L, "experiment-1", 1, Map.of("slowWindow", 60), "value-hash", false)))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> repository.insertRun(new ExperimentModels.RunDraft(
                "run-3", 7L, "experiment-1", 0, Map.of("slowWindow", 54), "other-hash", false)))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void experimentReadsAreUserScoped() {
        repository.insertExperiment(experiment("experiment-1", 7L, "snapshot-1"));

        assertThat(repository.experiment(7L, "experiment-1").name()).isEqualTo("参数敏感性检查");
        assertThatThrownBy(() -> repository.experiment(8L, "experiment-1"))
                .isInstanceOf(ExperimentRepository.ExperimentNotFoundException.class);
    }

    private static ExperimentModels.ExperimentDraft experiment(String id, Long userId, String snapshotId) {
        return new ExperimentModels.ExperimentDraft(
                id, userId, "参数敏感性检查", "QUEUED", "source-backtest",
                "strategy-1", "strategy-version-1", "universe-1", "universe-version-1",
                null, null, snapshotId, null, null,
                List.of(Map.of("key", "slowWindow")), Map.of("slowWindow", 60),
                List.of(48, 54, 60, 66, 72), Map.of("kind", "BACKTEST"),
                Map.of("engineVersion", "old"), Map.of("engineVersion", "current"),
                "parameter-sensitivity-v1", "parameter-stability-v1", "invariant-hash",
                "request-key", "request-hash");
    }
}
