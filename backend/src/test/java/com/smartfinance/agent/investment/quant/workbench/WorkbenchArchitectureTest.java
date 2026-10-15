package com.smartfinance.agent.investment.quant.workbench;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the ordinary workbench from growing another all-purpose service. */
class WorkbenchArchitectureTest {
    private static final Path ROOT = Path.of("src/main/java/com/smartfinance/agent/investment/quant/workbench");

    @Test
    void jdbcBelongsToRepositoriesAndSchedulingDoesNotOwnBusinessTransactions() throws Exception {
        try (var files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ROOT.relativize(p).startsWith("experiment")).toList()) {
                String source = Files.readString(file);
                if (!source.contains("@Repository")) {
                    assertThat(source).as("SQL boundary: %s", file)
                            .doesNotContain("JdbcTemplate", "db.query", "db.update", "db.batchUpdate");
                }
                if (source.contains("@Scheduled")) {
                    assertThat(source).as("Scheduler boundary: %s", file)
                            .doesNotContain("TransactionTemplate", "request_json", "result_json");
                }
            }
        }
    }

    @Test
    void businessResponsibilitiesHaveSeparatePackages() throws Exception {
        for (String name : List.of("api", "configuration", "market", "task", "deployment", "client", "scheduling")) {
            assertThat(Files.isDirectory(ROOT.resolve(name))).as("Business package: %s", name).isTrue();
        }
        assertThat(Files.exists(ROOT.resolve("WorkbenchService.java")))
                .as("The former all-purpose implementation must not remain as a second entry point").isFalse();
    }
}
