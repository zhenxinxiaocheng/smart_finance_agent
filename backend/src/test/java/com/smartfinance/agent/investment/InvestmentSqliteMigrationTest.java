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
                                 "'investment_data_job','benchmark_profile','quant_benchmark_snapshot'," +
                                 "'investment_index_watchlist','investment_index_display_order'," +
                                 "'quant_v2_object','quant_v2_version','quant_v2_task'," +
                                 "'quant_v2_deployment','quant_v2_paper_event'," +
                                 "'quant_v2_research_snapshot','quant_v2_experiment'," +
                                 "'quant_v2_experiment_run','quant_v2_experiment_run_attempt')")) {
                try (var result = statement.executeQuery()) {
                    int count = 0;
                    while (result.next()) count++;
                    assertThat(count).isEqualTo(23);
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
                assertThat(columnExists(connection, "product_daily_quote", "total_return_index"))
                        .isTrue();
                assertThat(columnExists(connection, "investment_analysis_snapshot", "horizon_profile_version"))
                        .isTrue();
                assertThat(columnExists(connection, "investment_analysis_snapshot", "horizon_config_json"))
                        .isTrue();
                assertThat(columnExists(connection, "investment_horizon_setting", "target_holding_days"))
                        .isTrue();
                assertThat(columnExists(connection, "investment_data_job", "lease_token"))
                        .isTrue();
                assertThat(columnExists(connection, "investment_product", "fund_category"))
                        .isTrue();
                assertThat(columnExists(connection, "investment_product", "classification_source"))
                        .isTrue();
                assertThat(columnExists(connection, "investment_product", "classification_version"))
                        .isTrue();
                assertThat(tableExists(connection, "quant_feature_set")).isFalse();
                assertThat(tableExists(connection, "quant_job")).isFalse();
                assertThat(tableExists(connection, "quant_model_version")).isFalse();
                assertThat(tableExists(connection, "quant_strategy_version")).isFalse();
                assertThat(tableExists(connection, "quant_prediction")).isFalse();
                assertThat(tableExists(connection, "quant_experiment")).isFalse();
                assertThat(tableExists(connection, "quant_paper_order")).isFalse();
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
                        .isFalse();
                assertThat(indexColumns(connection, "quant_v2_experiment_run", "uk_qv2_experiment_run_value"))
                        .containsExactly("experiment_id", "value_hash");
                assertThat(indexColumns(connection, "quant_v2_experiment_run", "uk_qv2_experiment_run_ordinal"))
                        .containsExactly("experiment_id", "ordinal");
                assertThat(indexColumns(connection, "quant_v2_experiment_run_attempt", "uk_qv2_experiment_attempt_number"))
                        .containsExactly("run_id", "attempt_no");
                assertThat(indexColumns(connection, "quant_v2_experiment_run_attempt", "uk_qv2_experiment_attempt_task"))
                        .containsExactly("task_id");
            }
            assertThat(flyway.info().pending()).isEmpty();
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
            assertThat(flyway.info().pending()).isEmpty();
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void versionTwentyFiveBackfillsKnownFundClassificationFromBenchmarkProfile() throws Exception {
        Path database = Files.createTempFile("smart-finance-fund-classification-", ".db");
        try {
            String url = "jdbc:sqlite:" + database.toAbsolutePath();
            Flyway.configure()
                    .dataSource(url, null, null)
                    .locations("classpath:db/migration/sqlite")
                    .target("24")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url);
                 var statement = connection.prepareStatement("""
                         INSERT INTO investment_product
                             (product_type, market, code, name, currency, status)
                         VALUES ('MUTUAL_FUND', 'CN', '000218', 'existing fund', 'CNY', 'ACTIVE')
                         """)) {
                statement.executeUpdate();
            }

            Flyway.configure()
                    .dataSource(url, null, null)
                    .locations("classpath:db/migration/sqlite")
                    .target("25")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url);
                 var statement = connection.prepareStatement("""
                         SELECT fund_category, classification_source, classification_version, classified_at
                         FROM investment_product
                         WHERE product_type = 'MUTUAL_FUND' AND code = '000218'
                         """);
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("fund_category")).isEqualTo("COMMODITY_FUND");
                assertThat(result.getString("classification_source"))
                        .isEqualTo("CURATED_BENCHMARK_PROFILE");
                assertThat(result.getString("classification_version"))
                        .isEqualTo("OFFICIAL-PRODUCT");
                assertThat(result.getString("classified_at")).isNotBlank();
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

    private static java.util.List<String> indexColumns(Connection connection, String table, String index) throws Exception {
        assertThat(indexExists(connection, table, index)).isTrue();
        try (var statement = connection.prepareStatement(
                "SELECT name FROM pragma_index_info(?) ORDER BY seqno")) {
            statement.setString(1, index);
            try (var result = statement.executeQuery()) {
                var columns = new java.util.ArrayList<String>();
                while (result.next()) columns.add(result.getString(1));
                return columns;
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
