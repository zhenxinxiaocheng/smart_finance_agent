package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentRepositorySqliteTest {
    @Test
    void sqliteBooleanValuesRoundTripForBaselineRuns() throws Exception {
        var database = Files.createTempFile("quant-experiment-repository-", ".db");
        try {
            var source = new DriverManagerDataSource("jdbc:sqlite:" + database.toAbsolutePath(), "", "");
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/sqlite/V1__baseline.sql"))
                    .execute(source);
            var repository = new ExperimentRepository(new JdbcTemplate(source), new ObjectMapper());
            repository.insertExperiment(experiment());
            repository.insertRun(new ExperimentModels.RunDraft(
                    "run-1", 7L, "experiment-1", 2, Map.of("slowWindow", 60), "value-hash", true));

            assertThat(repository.run(7L, "run-1").baseline()).isTrue();
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private static ExperimentModels.ExperimentDraft experiment() {
        return new ExperimentModels.ExperimentDraft(
                "experiment-1", 7L, "参数敏感性检查", "QUEUED", "source-backtest",
                "strategy-1", "strategy-version-1", "universe-1", "universe-version-1",
                null, null, "snapshot-1", null, null,
                List.of(Map.of("key", "slowWindow")), Map.of("slowWindow", 60),
                List.of(48, 54, 60, 66, 72), Map.of("kind", "BACKTEST"),
                Map.of("engineVersion", "old"), Map.of("engineVersion", "current"),
                "parameter-sensitivity-v1", "parameter-stability-v1", "invariant-hash",
                "request-key", "request-hash");
    }
}
