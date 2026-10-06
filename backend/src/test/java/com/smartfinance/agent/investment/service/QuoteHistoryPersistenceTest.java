package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.config.MyBatisPlusConfig;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.plugin.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class QuoteHistoryPersistenceTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"h2", "sqlite"})
    void thousandHistoryDaysAvoidPerRowDatabaseRoundTrips(String dialect) throws Exception {
        verifyHistory(fixture(dialect));
    }

    @Test
    void sqliteRuntimeUsesNativeUpsertWithProductionConfiguration() throws Exception {
        var fixture = fixture("sqlite");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
                .withUserConfiguration(MyBatisPlusConfig.class)
                .withBean(DataSource.class, () -> fixture.jdbc().getDataSource())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var factory = context.getBean(SqlSessionFactory.class);
                    factory.getConfiguration().addMapper(ProductDailyQuoteMapper.class);
                    var mapper = new SqlSessionTemplate(factory).getMapper(ProductDailyQuoteMapper.class);
                    var worker = new InvestmentSyncWorker(null, null, null, mapper, null, null, null,
                            null, null, null, new InvestmentRuntimeProperties());
                    fixture.transaction().execute(status -> worker.persistDailyQuotes(fixture.product(),
                            Map.of("provider", "SINA", "adapterVersion", "fixture", "records",
                                    List.of(Map.of("data_date", "2026-09-30", "close", "12.34"))), "NONE"));
                    assertThat(fixture.jdbc().queryForObject("SELECT close_price FROM product_daily_quote",
                            BigDecimal.class)).isEqualByComparingTo("12.34");
                });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
    void mysqlHistoryImportKeepsPricesAndUsesBoundedRoundTrips() throws Exception {
        verifyHistory(fixture("mysql"));
    }

    private void verifyHistory(Fixture fixture) {
        var previous = quote(fixture.product().getId(), LocalDate.of(2009, 12, 31), "9.99");
        fixture.mapper().insert(previous);
        var records = new ArrayList<Map<String, Object>>();
        for (int day = 0; day < 1000; day++) {
            records.add(Map.of("data_date", LocalDate.of(2010, 1, 1).plusDays(day).toString(),
                    "close", new BigDecimal("10.00").add(BigDecimal.valueOf(day, 2)).toPlainString()));
        }
        Collections.reverse(records);
        var payload = Map.<String, Object>of("provider", "SINA", "adapterVersion", "fixture", "records", records);
        fixture.counter().statements = 0;
        long started = System.nanoTime();
        var latest = fixture.persist(payload, "NONE");
        int statements = fixture.counter().statements;
        System.out.printf("History import %s: 1000 rows, %d statements, %.1f ms%n",
                fixture.dialect(), statements, (System.nanoTime() - started) / 1_000_000.0);
        assertThat(latest.getClosePrice()).isEqualByComparingTo("19.99");
        assertThat(latest.getPreviousClose()).isEqualByComparingTo("19.98");
        assertThat(latest.getChangeAmount()).isEqualByComparingTo("0.01");
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM product_daily_quote", Integer.class)).isEqualTo(1001);
        assertThat(statements).as("database calls must not grow once per history row").isLessThan(25);
        fixture.persist(payload, "NONE");
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM product_daily_quote", Integer.class)).isEqualTo(1001);
        fixture.persist(payload, "QFQ");
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM product_daily_quote", Integer.class)).isEqualTo(2001);
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM product_daily_quote WHERE adjust_type='QFQ'", Integer.class)).isEqualTo(1000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "sqlite"})
    void navRefreshPreservesReturnsAndExistingOptionalFields(String dialect) throws Exception {
        var fixture = fixture(dialect);
        fixture.product().setProductType("MUTUAL_FUND");
        var previous = quote(fixture.product().getId(), LocalDate.of(2026, 9, 30), "1.20");
        previous.setTotalReturnIndex(new BigDecimal("1.50"));
        previous.setHighPrice(new BigDecimal("1.30"));
        fixture.mapper().insert(previous);
        var payload = Map.<String, Object>of("provider", "EASTMONEY", "adapterVersion", "fixture", "records",
                List.of(Map.of("data_date", "2026-09-30", "nav", "1.25")));
        fixture.persist(payload, "NONE");
        var stored = fixture.mapper().selectById(previous.getId());
        assertThat(stored.getClosePrice()).isEqualByComparingTo("1.25");
        assertThat(stored.getHighPrice()).isEqualByComparingTo("1.30");
        assertThat(stored.getTotalReturnIndex()).isEqualByComparingTo("1.50");
        fixture.persist(Map.of("provider", "EASTMONEY", "adapterVersion", "fixture",
                "records", List.of(Map.of("data_date", "2026-09-30", "nav", "1.25", "total_return_index", "1.60"))), "NONE");
        assertThat(fixture.mapper().selectById(previous.getId()).getTotalReturnIndex()).isEqualByComparingTo("1.60");
    }

    private Fixture fixture(String dialect) throws Exception {
        DriverManagerDataSource source;
        if (dialect.equals("mysql")) {
            String url = System.getenv("MYSQL_TEST_URL");
            if (!url.contains("/codex_migration_test_")) throw new IllegalArgumentException("dedicated test database required");
            source = new DriverManagerDataSource(url, System.getenv("MYSQL_TEST_USER"), System.getenv("MYSQL_TEST_PASSWORD"));
        } else if (dialect.equals("sqlite")) {
            source = new DriverManagerDataSource("jdbc:sqlite:" + temporary.resolve(UUID.randomUUID() + ".db"));
        } else {
            source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        }
        if (dialect.equals("h2")) new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(source);
        else Flyway.configure().dataSource(source).locations("classpath:db/migration/" + dialect).load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO investment_product(product_type,market,code,name,currency) VALUES('STOCK','SSE','QUOTE_FIXTURE','quote fixture','CNY')");
        var product = new InvestmentProduct();
        product.setId(jdbc.queryForObject("SELECT id FROM investment_product WHERE code='QUOTE_FIXTURE'", Long.class));
        product.setProductType("STOCK");
        var counter = new StatementCounter();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(NoLoggingImpl.class);
        configuration.setDatabaseId(dialect.equals("sqlite") ? "sqlite" : null);
        configuration.addInterceptor(counter);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(new GlobalConfig().setBanner(false));
        var sessions = Objects.requireNonNull(factory.getObject());
        sessions.getConfiguration().addMapper(ProductDailyQuoteMapper.class);
        var mapper = new SqlSessionTemplate(sessions).getMapper(ProductDailyQuoteMapper.class);
        var worker = new InvestmentSyncWorker(null, null, null, mapper, null, null, null, null, null, null, new InvestmentRuntimeProperties());
        return new Fixture(dialect, jdbc, mapper, worker, product, counter,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    private static ProductDailyQuote quote(long product, LocalDate date, String close) {
        var quote = new ProductDailyQuote();
        quote.setProductId(product);
        quote.setTradeDate(date);
        quote.setAdjustType("NONE");
        quote.setClosePrice(new BigDecimal(close));
        quote.setSource("FIXTURE");
        quote.setAdapterVersion("fixture");
        quote.setSyncedAt(java.time.LocalDateTime.now());
        return quote;
    }

    record Fixture(String dialect, JdbcTemplate jdbc, ProductDailyQuoteMapper mapper,
                   InvestmentSyncWorker worker, InvestmentProduct product, StatementCounter counter,
                   TransactionTemplate transaction) {
        ProductDailyQuote persist(Map<String, Object> payload, String adjustment) {
            return transaction.execute(status -> worker.persistDailyQuotes(product, payload, adjustment));
        }
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    static class StatementCounter implements Interceptor {
        int statements;
        public Object intercept(Invocation invocation) throws Throwable {
            statements++;
            return invocation.proceed();
        }
    }
}
