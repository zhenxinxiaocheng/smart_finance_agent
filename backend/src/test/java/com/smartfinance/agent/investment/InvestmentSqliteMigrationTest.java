package com.smartfinance.agent.investment;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.smartfinance.agent.mapper.TransactionMapper;
import org.apache.ibatis.annotations.Select;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.lang.reflect.Modifier;
import static org.assertj.core.api.Assertions.*;

class InvestmentSqliteMigrationTest {
    @Test
    void emptyDatabaseCreatesCurrentSchemaAndSecondMigrationDoesNothing() throws Exception {
        Path database = Files.createTempFile("smart-finance-", ".db");
        try {
            verifyEmptyDatabase("jdbc:sqlite:" + database.toAbsolutePath(), null, null, "sqlite");
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
    void emptyMysqlDatabaseCreatesCurrentSchemaAndSecondMigrationDoesNothing() throws Exception {
        verifyEmptyDatabase(System.getenv("MYSQL_TEST_URL"), System.getenv("MYSQL_TEST_USER"),
                System.getenv("MYSQL_TEST_PASSWORD"), "mysql");
    }

    private void verifyEmptyDatabase(String url, String user, String password, String dialect) throws Exception {
        // Only accept an empty database. Never clean or alter an existing database for this test.
        try (var connection = DriverManager.getConnection(url, user, password);
             var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            assertThat(tables.next()).as("Use a dedicated empty test database").isFalse();
        }
        Flyway flyway = Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration/" + dialect).baselineOnMigrate(false).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        try (var connection = DriverManager.getConnection(url, user, password)) {
            String tableQuery = dialect.equals("mysql")
                    ? "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history'"
                    : "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'flyway_schema_history'";
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(tableQuery)) {
                var names = new java.util.HashSet<String>();
                while (rows.next()) names.add(rows.getString(1));
                assertThat(names).hasSize(59)
                        .contains("product_quote_coverage", "market_data_scope_product")
                        .doesNotContain("investment_analysis_preference", "quant_job", "quant_prediction", "quant_paper_order");
            }
            assertUniqueColumns(connection, "quant_v2_experiment_run", List.of("experiment_id", "value_hash"));
            assertUniqueColumns(connection, "quant_v2_experiment_run", List.of("experiment_id", "ordinal"));
            assertUniqueColumns(connection, "quant_v2_experiment_run_attempt", List.of("run_id", "attempt_no"));
            assertUniqueColumns(connection, "quant_v2_experiment_run_attempt", List.of("task_id"));
            var scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(TableName.class));
            int count = 0;
            for (var bean : scanner.findCandidateComponents("com.smartfinance.agent")) {
                Class<?> entity = Class.forName(bean.getBeanClassName());
                String table = entity.getAnnotation(TableName.class).value();
                var columns = Arrays.stream(entity.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .filter(field -> field.getAnnotation(TableField.class) == null || field.getAnnotation(TableField.class).exist())
                        .map(field -> {
                            TableField mapping = field.getAnnotation(TableField.class);
                            return mapping != null && !mapping.value().isBlank() ? mapping.value()
                                    : field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
                        }).map(column -> "`" + column + "`").toList();
                try (var statement = connection.createStatement()) {
                    statement.executeQuery("SELECT " + String.join(",", columns) + " FROM `" + table + "` WHERE 1=0").close();
                }
                count++;
            }
            assertThat(count).isEqualTo(44);
            for (String table : List.of("quant_v2_object", "quant_v2_version", "quant_v2_task",
                    "quant_v2_deployment", "quant_v2_paper_event", "quant_v2_research_snapshot",
                    "quant_v2_experiment", "quant_v2_experiment_run", "quant_v2_experiment_run_attempt")) {
                try (var statement = connection.createStatement()) {
                    statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0").close();
                }
            }
            for (String table : List.of("user", "expense_category", "agent_skill", "investment_account")) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).as(table + " has no seed data").isZero();
                }
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT type, success FROM flyway_schema_history WHERE version='1'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("SQL");
                assertThat(rows.getBoolean(2)).isTrue();
            }
        }
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO `user` (username,password) VALUES ('migration_restart_check','test-only')");
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM `user` WHERE username='migration_restart_check'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
        assertThat(flyway.info().pending()).isEmpty();
        flyway.validate();
    }

    private static void assertUniqueColumns(Connection connection, String table, List<String> expected) throws Exception {
        var indexes = new java.util.HashMap<String, java.util.SortedMap<Integer, String>>();
        try (var rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, true, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name != null && column != null) {
                    indexes.computeIfAbsent(name, ignored -> new java.util.TreeMap<>())
                            .put(rows.getInt("ORDINAL_POSITION"), column);
                }
            }
        }
        assertThat(indexes.values().stream().map(index -> List.copyOf(index.values())).toList()).contains(expected);
    }

    @Test
    void refusesNonEmptyDatabaseWithoutHistory() throws Exception {
        Path database = Files.createTempFile("smart-finance-unknown-", ".db");
        String url = "jdbc:sqlite:" + database.toAbsolutePath();
        try {
            try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE existing_data (id INTEGER PRIMARY KEY)");
            }
            Flyway flyway = Flyway.configure().dataSource(url, null, null)
                    .locations("classpath:db/migration/sqlite").baselineOnMigrate(false).load();
            assertThatThrownBy(flyway::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void transactionAggregateQueryIsCompatibleWithSqlite() throws Exception {
        Path database = Files.createTempFile("smart-finance-transaction-", ".db");
        try {
            String url = "jdbc:sqlite:" + database.toAbsolutePath();
            Flyway.configure()
                    .dataSource(url, null, null)
                    .locations("classpath:db/migration/sqlite")
                    .load()
                    .migrate();

            Select select = TransactionMapper.class
                    .getMethod("sumByUserAndTypeCreatedAfter", Long.class, String.class,
                            java.time.LocalDateTime.class)
                    .getAnnotation(Select.class);
            String sql = String.join(" ", select.value())
                    .replace("#{userId}", "1")
                    .replace("#{type}", "'INCOME'")
                    .replace("#{after}", "'2020-01-01 00:00:00'");

            try (var connection = DriverManager.getConnection(url);
                 var statement = connection.prepareStatement(sql);
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBigDecimal(1)).isEqualByComparingTo("0");
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

}
