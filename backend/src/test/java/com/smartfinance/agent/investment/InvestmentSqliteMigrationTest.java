package com.smartfinance.agent.investment;

import com.smartfinance.agent.mapper.TransactionMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentSqliteMigrationTest {

    @Test
    void migrationsCreateCoreAndInvestmentTables() throws Exception {
        Path database = Files.createTempFile("smart-finance-", ".db");
        try {
            String url = "jdbc:sqlite:" + database.toAbsolutePath();
            Flyway flyway = Flyway.configure()
                    .dataSource(url, null, null)
                    .locations("classpath:db/migration/sqlite")
                    .load();
            flyway.migrate();

            try (var connection = DriverManager.getConnection(url);
                 var statement = connection.prepareStatement(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name IN (" +
                                 "'user','investment_transaction','investment_position','investment_asset'," +
                                 "'wealth_baseline','investment_analysis_preference','investment_analysis_snapshot'," +
                                 "'investment_horizon_profile','investment_horizon_setting'," +
                                 "'quant_feature_set','quant_model_version','quant_strategy_version'," +
                                 "'quant_prediction','quant_paper_order','investment_data_job'," +
                                 "'benchmark_profile','quant_research_universe'," +
                                 "'quant_universe_membership','quant_experiment'," +
                                 "'quant_validation_report','quant_benchmark_snapshot')")) {
                try (var result = statement.executeQuery()) {
                    int count = 0;
                    while (result.next()) count++;
                    assertThat(count).isEqualTo(21);
                }
            }
            try (var connection = DriverManager.getConnection(url);
                 var statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM pragma_table_info('product_daily_quote') " +
                                 "WHERE name IN ('change_amount','change_percent','turnover_rate','volume_ratio','amplitude')");
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(5);
            }
            try (var connection = DriverManager.getConnection(url)) {
                assertThat(columnExists(connection, "investment_analysis_snapshot", "horizon_profile_version"))
                        .isTrue();
                assertThat(columnExists(connection, "investment_analysis_snapshot", "horizon_config_json"))
                        .isTrue();
                assertThat(columnExists(connection, "investment_horizon_setting", "target_holding_days"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_prediction", "benchmark_code"))
                        .isTrue();
                assertThat(columnExists(connection, "investment_data_job", "lease_token"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_job", "experiment_fingerprint"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_job", "request_fingerprint"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_job", "error_code"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_job", "error_summary"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_job", "quant_config_version"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_job", "product_type"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_job", "metrics_json"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_experiment", "training_mode"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_experiment", "trigger_reason"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_experiment", "parent_model_version"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_experiment", "best_model_version"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_experiment", "search_summary_json"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_strategy_version", "user_id"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_strategy_version", "asset_id"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_strategy_version", "model_family"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_strategy_version", "horizon_code"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_strategy_version", "deployment_role"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_prediction", "target_weight"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_prediction", "profit_probability"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_prediction", "loss_probability"))
                        .isTrue();
                assertThat(columnExists(connection, "quant_prediction", "expected_net_return"))
                        .isTrue();
                assertThat(tableExists(connection, "quant_training_run")).isFalse();
            }
            try (var connection = DriverManager.getConnection(url);
                 var statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM pragma_index_list('investment_data_job') " +
                                 "WHERE name = 'idx_investment_data_job_pending'" );
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            try (var connection = DriverManager.getConnection(url)) {
                assertThat(indexExists(connection, "investment_account", "uk_investment_active_paper_user"))
                        .isTrue();
                assertThat(indexExists(connection, "quant_paper_order", "uk_quant_paper_order_prediction"))
                        .isTrue();
                assertThat(indexExists(connection, "quant_paper_fill", "uk_quant_paper_fill_order"))
                        .isTrue();
            }
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("23");
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void versionEightMigratesLegacyPerAssetHorizonValues() throws Exception {
        Path database = Files.createTempFile("smart-finance-horizon-", ".db");
        try {
            String url = "jdbc:sqlite:" + database.toAbsolutePath();
            Flyway.configure()
                    .dataSource(url, null, null)
                    .locations("classpath:db/migration/sqlite")
                    .target("7")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url);
                 var statement = connection.prepareStatement("""
                         INSERT INTO investment_analysis_preference (
                           user_id, asset_id, short_min_days, short_max_days,
                           medium_min_days, medium_max_days, long_min_days, long_max_days
                         ) VALUES (7, 11, 3, 17, 40, 160, 260, 900)
                         """)) {
                statement.executeUpdate();
            }

            Flyway flyway = Flyway.configure()
                    .dataSource(url, null, null)
                    .locations("classpath:db/migration/sqlite")
                    .load();
            flyway.migrate();

            try (var connection = DriverManager.getConnection(url);
                 var statement = connection.prepareStatement("""
                         SELECT p.scope_type, p.version, p.source, p.active,
                                s.horizon_code, s.min_holding_days, s.max_holding_days,
                                s.target_holding_days
                         FROM investment_horizon_profile p
                         JOIN investment_horizon_setting s ON s.profile_id = p.id
                         WHERE p.user_id = 7 AND p.asset_id = 11
                         ORDER BY s.sort_order
                         """);
                 var result = statement.executeQuery()) {
                assertLegacySetting(result, "SHORT", 3, 17);
                assertLegacySetting(result, "MEDIUM", 40, 160);
                assertLegacySetting(result, "LONG", 260, 900);
                assertThat(result.next()).isFalse();
            }
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("23");
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void versionFifteenMigratesTrainingRunIntoQuantJobBeforeDroppingDuplicateTable() throws Exception {
        Path database = Files.createTempFile("smart-finance-quant-job-", ".db");
        try {
            String url = "jdbc:sqlite:" + database.toAbsolutePath();
            Flyway.configure()
                    .dataSource(url, null, null)
                    .locations("classpath:db/migration/sqlite")
                    .target("14")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url)) {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO quant_job
                            (user_id, asset_id, external_job_id, job_type, status,
                             dataset_version, horizon_days, started_at)
                        VALUES (7, 12, 'job-v15', 'TRAIN_PREDICT', 'SUCCEEDED',
                                'dataset-v15', 20, CURRENT_TIMESTAMP)
                        """)) {
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO quant_training_run
                            (external_job_id, dataset_version, feature_set_version,
                             quant_config_version, product_type, horizon_days, status,
                             metrics_json, started_at, finished_at)
                        VALUES ('job-v15', 'dataset-v15', 'feature-v15',
                                'config-v15', 'STOCK', 20, 'SUCCEEDED',
                                '{"sharpe":0.8}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)) {
                    statement.executeUpdate();
                }
            }

            Flyway.configure()
                    .dataSource(url, null, null)
                    .locations("classpath:db/migration/sqlite")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url);
                 var statement = connection.prepareStatement("""
                         SELECT feature_set_version, quant_config_version, product_type,
                                metrics_json, finished_at
                         FROM quant_job
                         WHERE external_job_id = 'job-v15'
                         """);
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("feature_set_version")).isEqualTo("feature-v15");
                assertThat(result.getString("quant_config_version")).isEqualTo("config-v15");
                assertThat(result.getString("product_type")).isEqualTo("STOCK");
                assertThat(result.getString("metrics_json")).isEqualTo("{\"sharpe\":0.8}");
                assertThat(result.getString("finished_at")).isNotBlank();
                assertThat(tableExists(connection, "quant_training_run")).isFalse();
            }
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

    private static boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private static boolean indexExists(Connection connection, String table, String index) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM pragma_index_list(?) WHERE name = ?")) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private static void assertLegacySetting(java.sql.ResultSet result, String code,
                                            int minimum, int maximum) throws Exception {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("scope_type")).isEqualTo("ASSET");
        assertThat(result.getInt("version")).isEqualTo(1);
        assertThat(result.getString("source")).isEqualTo("LEGACY");
        assertThat(result.getBoolean("active")).isTrue();
        assertThat(result.getString("horizon_code")).isEqualTo(code);
        assertThat(result.getInt("min_holding_days")).isEqualTo(minimum);
        assertThat(result.getInt("max_holding_days")).isEqualTo(maximum);
        assertThat(result.getInt("target_holding_days"))
                .isEqualTo(minimum + (maximum - minimum) / 2);
    }
}
