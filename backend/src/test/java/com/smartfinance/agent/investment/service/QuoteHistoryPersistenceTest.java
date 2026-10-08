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
                    var writer = new ProductDailyQuoteService(mapper, new InvestmentRuntimeProperties());
                    fixture.transaction().execute(status -> writer.persistDailyQuotes(fixture.product(),
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

    @ParameterizedTest
    @ValueSource(strings={"h2","sqlite"})
    void signedQfqRebuildClearsInvalidReturnRatiosAndKeepsPositiveRatios(String dialect) throws Exception {
        var fixture=fixture(dialect);
        var writer=new ProductDailyQuoteService(fixture.mapper(), new InvestmentRuntimeProperties());
        for(String first:List.of("10","-10")) fixture.transaction().execute(status->writer.persistDailyQuotes(fixture.product(),
                Map.of("provider","TEST","adapterVersion","fixture","records",List.of(
                        Map.of("data_date","2026-07-01","close",first),Map.of("data_date","2026-07-02","close","12"))),"QFQ"));
        assertThat(fixture.jdbc().queryForObject("SELECT change_percent FROM product_daily_quote WHERE trade_date='2026-07-02' AND adjust_type='QFQ'",BigDecimal.class)).isNull();
        fixture.transaction().execute(status->writer.persistDailyQuotes(fixture.product(),Map.of("provider","TEST","adapterVersion","fixture","records",
                List.of(Map.of("data_date","2026-07-03","close","15"))),"QFQ"));
        assertThat(fixture.jdbc().queryForObject("SELECT change_percent FROM product_daily_quote WHERE trade_date='2026-07-03' AND adjust_type='QFQ'",BigDecimal.class)).isEqualByComparingTo("25");
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

    @ParameterizedTest
    @ValueSource(strings = {"h2", "sqlite"})
    void historyPreservesObservedAmountAndTurnoverAcrossMissingAndZeroUpdates(String dialect) throws Exception {
        var fixture = fixture(dialect);
        fixture.persist(Map.of("provider", "EASTMONEY", "adapterVersion", "2", "records", List.of(
                Map.of("data_date", "2026-09-30", "close", "10", "amount", "100000", "turnover_rate", "1.25"))), "NONE");
        var row = fixture.jdbc().queryForMap("SELECT amount,turnover_rate FROM product_daily_quote");
        assertThat(row.get("amount")).as("observed amount must survive history persistence").isNotNull();
        assertThat(row.get("turnover_rate")).as("observed turnover must survive history persistence").isNotNull();
        assertThat(new BigDecimal(row.get("amount").toString())).isEqualByComparingTo("100000");
        assertThat(new BigDecimal(row.get("turnover_rate").toString())).isEqualByComparingTo("1.25");
        fixture.persist(Map.of("provider", "TENCENT", "adapterVersion", "2", "records", List.of(
                Map.of("data_date", "2026-09-30", "close", "10"))), "NONE");
        assertThat(fixture.jdbc().queryForObject("SELECT amount FROM product_daily_quote", BigDecimal.class))
                .isEqualByComparingTo("100000");
        fixture.persist(Map.of("provider", "EASTMONEY", "adapterVersion", "2", "records", List.of(
                Map.of("data_date", "2026-09-30", "close", "10", "amount", "0", "turnover_rate", "0"))), "NONE");
        assertThat(fixture.jdbc().queryForObject("SELECT amount FROM product_daily_quote", BigDecimal.class)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT turnover_rate FROM product_daily_quote", BigDecimal.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"h2","sqlite"})
    void batchSeriesReadsOptionalStartAndAllStoredLiquidityFields(String dialect) throws Exception {
        var fixture=fixture(dialect);
        long first=fixture.product().getId();
        fixture.jdbc().update("INSERT INTO investment_product(product_type,market,code,name,currency) VALUES('ETF','SSE','SECOND','second','CNY')");
        long second=fixture.jdbc().queryForObject("SELECT id FROM investment_product WHERE code='SECOND'",Long.class);
        for(long id:List.of(first,second)) {
            var raw=quote(id,LocalDate.of(2026,1,5),"10");
            raw.setAmount(new BigDecimal("1234.50"));raw.setTurnoverRate(new BigDecimal("2.5"));
            fixture.mapper().insert(raw);
            var adjusted=quote(id,LocalDate.of(2026,1,5),"5");adjusted.setAdjustType("QFQ");fixture.mapper().insert(adjusted);
            fixture.mapper().insert(quote(id,LocalDate.of(2026,1,6),"11"));
        }
        var reader=new MarketDataService(fixture.jdbc(),org.mockito.Mockito.mock(AnalysisServiceClient.class),fixture.mapper());
        fixture.counter().statements=0;
        var series=reader.readDailyQuotes(List.of(first,second,first),null,LocalDate.of(2026,1,6),Set.of("NONE","QFQ"));
        assertThat(fixture.counter().statements).isEqualTo(1);
        assertThat(series).containsOnlyKeys(first,second);
        assertThat(series.get(first)).extracting(ProductDailyQuote::getAdjustType).containsExactly("NONE","QFQ","NONE");
        assertThat(series.get(first)).extracting(ProductDailyQuote::getTradeDate)
                .containsExactly(LocalDate.of(2026,1,5),LocalDate.of(2026,1,5),LocalDate.of(2026,1,6));
        assertThat(series.get(first).get(0).getAmount()).isEqualByComparingTo("1234.50");
        assertThat(series.get(first).get(0).getTurnoverRate()).isEqualByComparingTo("2.5");
        assertThat(series.get(first).get(0).getSource()).isEqualTo("FIXTURE");
        assertThat(series.get(first).get(0).getAdapterVersion()).isEqualTo("fixture");
        assertThat(reader.readDailyQuotes(List.of(first),LocalDate.of(2026,1,6),LocalDate.of(2026,1,6),Set.of("NONE")).get(first))
                .hasSize(1).first().extracting(ProductDailyQuote::getTradeDate).isEqualTo(LocalDate.of(2026,1,6));
    }

    @ParameterizedTest
    @ValueSource(strings={"h2","sqlite"})
    void strictDailySeriesProjectionKeepsItsExistingContract(String dialect) throws Exception {
        var fixture=fixture(dialect);long id=fixture.product().getId();
        fixture.mapper().insert(quote(id,LocalDate.of(2026,1,5),"10"));
        var reader=new MarketDataService(fixture.jdbc(),org.mockito.Mockito.mock(AnalysisServiceClient.class),fixture.mapper());
        LocalDate day=LocalDate.of(2026,1,5);
        var projected=reader.getDailySeries(List.of(id),day,day,"NONE").get(id).get(0);
        assertThat(projected.keySet()).containsExactlyInAnyOrder("productId","tradeDate","openPrice","highPrice","lowPrice",
                "closePrice","previousClose","volume","amount","totalReturnIndex","source","adjustType");
        assertThat(projected).containsEntry("tradeDate","2026-01-05").containsEntry("productId",id);
        assertThat(reader.readDailyQuotes(List.of(id,id,999999L),null,day,Set.of("NONE")).get(999999L)).isEmpty();
        assertThat(reader.readDailyQuotes(List.of(),null,null,null)).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->reader.getDailySeries(List.of(id),null,day,"NONE"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->reader.getDailySeries(List.of(id),day.plusDays(1),day,"NONE"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->reader.getDailySeries(Collections.nCopies(101,id),day,day,"NONE"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        for(List<Long> ids:List.of(List.of(0L),List.of(-1L))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(()->reader.readDailyQuotes(ids,null,day,Set.of("NONE")))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(()->reader.readDailyQuotes(List.of(id),null,day,Set.of("BAD")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
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
        var writer = new ProductDailyQuoteService(mapper, new InvestmentRuntimeProperties());
        return new Fixture(dialect, jdbc, mapper, writer, product, counter,
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
                   ProductDailyQuoteService writer, InvestmentProduct product, StatementCounter counter,
                   TransactionTemplate transaction) {
        ProductDailyQuote persist(Map<String, Object> payload, String adjustment) {
            return transaction.execute(status -> writer.persistDailyQuotes(product, payload, adjustment));
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
