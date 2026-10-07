package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentProduct;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

class AnalysisServiceClientTest {

    @Test
    void optionalProductNamesHaveABoundedHttpReadTimeout() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/market-data/product-names", exchange -> {
            try { Thread.sleep(800); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try {
                byte[] body = "{\"items\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new AnalysisServiceClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-token", java.time.Duration.ofMillis(50), java.time.Duration.ofMillis(50));
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.marketProductNames("英伟达")).isInstanceOf(ResourceAccessException.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofMillis(600));
        } finally { server.stop(0); }
    }

    @Test
    void marketDailyQuotesSendsAnExplicitProductAndAdjustmentWindow() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(builder, "http://analysis.test", "secret-token");
        InvestmentProduct product = etfProduct();
        server.expect(requestTo("http://analysis.test/internal/v1/market-data/quotes/daily"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andExpect(content().json("""
                        {"code":"510300","market":"SH","product_type":"ETF",
                         "start_date":"2026-07-01","end_date":"2026-07-30","adjust_type":"NONE"}
                        """))
                .andRespond(withSuccess("""
                        {"records":[{"data_date":"2026-07-01","close":"4.50"}]}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.marketDailyQuotes(product,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 30), "NONE");

        assertThat(result).containsKey("records");
        server.verify();
    }

    @Test
    void structuredSourceEmpty503IsTheOnlyHttpEmptyWindowSignal() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/market-data/quotes/daily"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"detail":{"code":"SOURCE_EMPTY","message":"provider has no records in window"}}
                                """));

        assertThatThrownBy(() -> client.marketDailyQuotes(etfProduct(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 30), "NONE"))
                .isInstanceOf(AnalysisServiceClient.SourceEmptyException.class)
                .hasMessageContaining("no records");
        server.verify();
    }

    @Test
    void unstructuredNoRecordsTextPreservesTheHttpFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/market-data/quotes/daily"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"provider transport error: no records\"}"));

        assertThatThrownBy(() -> client.marketDailyQuotes(etfProduct(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 30), "NONE"))
                .isInstanceOf(RestClientResponseException.class)
                .isNotInstanceOf(AnalysisServiceClient.SourceEmptyException.class);
        server.verify();
    }

    @Test
    void transportFailureContainingNoRecordsIsNeverReclassifiedAsEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/market-data/quotes/daily"))
                .andRespond(withException(new SocketTimeoutException("no records after transport timeout")));

        assertThatThrownBy(() -> client.marketDailyQuotes(etfProduct(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 30), "NONE"))
                .isInstanceOf(ResourceAccessException.class)
                .isNotInstanceOf(AnalysisServiceClient.SourceEmptyException.class);
        server.verify();
    }

    private static InvestmentProduct etfProduct() {
        InvestmentProduct product = new InvestmentProduct();
        product.setProductType("ETF");
        product.setCode("510300");
        product.setMarket("SH");
        return product;
    }

    @Test
    void benchmarkHistory_shouldUseVersionedBenchmarkContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(
                builder,
                "http://analysis.test",
                "secret-token"
        );
        server.expect(requestTo("http://analysis.test/internal/v1/market-data/benchmarks/daily"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andExpect(content().json("""
                        {"benchmarkCode":"CSI300_95_CASH_5",
                         "startDate":"2026-01-01","endDate":"2026-07-01"}
                        """))
                .andRespond(withSuccess("""
                        {"benchmarkCode":"CSI300_95_CASH_5","provider":"AKSHARE",
                         "records":[{"data_date":"2026-01-01","close":"100"}]}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.benchmarkHistory(
                "CSI300_95_CASH_5",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 7, 1)
        );

        assertThat(result).containsEntry("benchmarkCode", "CSI300_95_CASH_5");
        server.verify();
    }

    @Test
    void indexQuotesShouldUseTheDedicatedMarketIndexContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(
                builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/market-data/indexes/quotes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andExpect(content().json("""
                        {"indexCodes":["CN_INDEX:000300","GLOBAL_INDEX:NDX"]}
                        """))
                .andRespond(withSuccess("""
                        {"items":[
                          {"indexCode":"CN_INDEX:000300","name":"沪深300","market":"CN",
                           "latestPrice":"4552.58","changePercent":"0.10","provider":"AKSHARE"},
                          {"indexCode":"GLOBAL_INDEX:NDX","name":"纳斯达克100","market":"US",
                           "latestPrice":"29143.33","changePercent":"0.23","provider":"AKSHARE"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        var result = client.indexQuotes(List.of("CN_INDEX:000300", "GLOBAL_INDEX:NDX"));

        assertThat(result).extracting(AnalysisServiceClient.IndexQuote::indexCode)
                .containsExactly("CN_INDEX:000300", "GLOBAL_INDEX:NDX");
        assertThat(result.get(1).latestPrice()).isEqualByComparingTo("29143.33");
        server.verify();
    }

    @Test
    void aShareTradingDates_shouldReadCalendarFromAnalysisService() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/market-data/calendar?market=A_SHARE&year=2026"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"market":"A_SHARE","year":2026,"provider":"AKSHARE",
                         "tradingDates":["2026-09-30","2026-10-08"]}
                        """, MediaType.APPLICATION_JSON));

        var result = client.aShareTradingDates(2026);

        assertThat(result).containsExactly(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 8));
        server.verify();
    }

    @Test
    void technicalAnalysis_shouldSendOnlyMarketDataAndHorizonPreferences() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/analysis/technical"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"records":[{"data_date":"2026-07-14","close":"10.50"}],
                         "horizons":{"WAVE":[7,33],"POSITION":[55,233]},
                         "primaryHorizon":"WAVE",
                         "marketSnapshot":{"turnoverRate":3.79,"volumeRatio":2.61,"amplitude":6.63}}
                        """))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("riskPreference"))))
                .andRespond(withSuccess("{" + "\"status\":\"READY\",\"score\":72" + "}",
                        MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.technicalAnalysis(
                List.of(Map.of("data_date", "2026-07-14", "close", "10.50")),
                Map.of("WAVE", List.of(7, 33), "POSITION", List.of(55, 233)),
                "WAVE",
                Map.of("turnoverRate", new BigDecimal("3.79"),
                        "volumeRatio", new BigDecimal("2.61"),
                        "amplitude", new BigDecimal("6.63")));

        assertThat(result.get("score")).isEqualTo(72);
        server.verify();
    }

    @Test
    void fundAnalysis_shouldSendClassificationAndValidatedRecords() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(
                builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/analysis/fund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"records":[{"data_date":"2026-08-06","nav":"1.2345"}],
                         "fundCategory":"QDII_INDEX_FUND",
                         "horizons":{"SHORT":{"minDays":5,"maxDays":20,"targetDays":10}},
                         "primaryHorizon":"SHORT",
                         "benchmark":{"status":"READY","code":"CSI300",
                                      "sourceVersion":"CSI-OFFICIAL-V1",
                                      "records":[{"data_date":"2026-08-06","close":"101.2"}]}}
                        """))
                .andRespond(withSuccess("""
                        {"status":"READY","analysisMode":"DESCRIPTIVE_ONLY",
                         "adviceStatus":"UNAVAILABLE","action":"WAIT"}
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.fundAnalysis(
                List.of(Map.of("data_date", "2026-08-06", "nav", "1.2345")),
                "QDII_INDEX_FUND",
                Map.of("SHORT", Map.of("minDays", 5, "maxDays", 20, "targetDays", 10)),
                "SHORT",
                Map.of(
                        "status", "READY",
                        "code", "CSI300",
                        "sourceVersion", "CSI-OFFICIAL-V1",
                        "records", List.of(Map.of(
                                "data_date", "2026-08-06",
                                "close", "101.2"
                        ))
                ));

        assertThat(result)
                .containsEntry("analysisMode", "DESCRIPTIVE_ONLY")
                .containsEntry("action", "WAIT");
        server.verify();
    }

    @Test
    void realtimeQuote_shouldMapExactQuoteTimeAndDecimalPrice() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/quotes/realtime"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andExpect(content().json("{\"code\":\"002632\",\"market\":\"SZSE\"}"))
                .andRespond(withSuccess("""
                        {"code":"002632","market":"SZSE","latestPrice":"8.63",
                         "dataDate":"2026-07-13","fetchedAt":"2026-07-13T11:23:30+08:00",
                         "previousClose":"8.90","changeAmount":"-0.27","changePercent":"-3.03",
                         "openPrice":"8.75","highPrice":"9.20","lowPrice":"8.61",
                         "volume":"21755100","amount":"194300000","turnoverRate":"3.79",
                         "volumeRatio":"2.61","amplitude":"6.63",
                         "provider":"TENCENT","warnings":[]}
                        """, MediaType.APPLICATION_JSON));

        AnalysisServiceClient.RealtimeQuote result = client.realtimeQuote("002632", "SZSE");

        assertThat(result.latestPrice()).isEqualByComparingTo("8.63");
        assertThat(result.changePercent()).isEqualByComparingTo("-3.03");
        assertThat(result.turnoverRate()).isEqualByComparingTo("3.79");
        assertThat(result.volumeRatio()).isEqualByComparingTo("2.61");
        assertThat(result.amount()).isEqualByComparingTo("194300000");
        assertThat(result.fetchedAt()).isEqualTo(LocalDateTime.of(2026, 7, 13, 11, 23, 30));
        server.verify();
    }

    @Test
    void resolveProduct_shouldSendCodeAndMapDecimalPrice() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/products/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andExpect(content().json("{\"product_type\":\"STOCK\",\"code\":\"600519\"}"))
                .andRespond(withSuccess("""
                        {"productType":"STOCK","code":"600519","name":"贵州茅台","market":"SSE",
                         "currency":"CNY","provider":"AKSHARE","dataDate":"2026-07-10",
                         "inceptionDate":"2001-08-27",
                         "latestPrice":"1204.98","previousClose":"1190.00",
                         "changeAmount":"14.98","changePercent":"1.2588","warnings":[]}
                        """, MediaType.APPLICATION_JSON));

        AnalysisServiceClient.ResolvedProduct result = client.resolveProduct("STOCK", "600519");

        assertThat(result.name()).isEqualTo("贵州茅台");
        assertThat(result.latestPrice()).isEqualByComparingTo("1204.98");
        assertThat(result.changeAmount()).isEqualByComparingTo("14.98");
        assertThat(result.changePercent()).isEqualByComparingTo("1.2588");
        assertThat(result.inceptionDate()).isEqualTo(LocalDate.of(2001, 8, 27));
        server.verify();
    }

    @Test
    void resolveFund_shouldMapProviderClassificationMetadata() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(
                builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/products/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        "{\"product_type\":\"MUTUAL_FUND\",\"code\":\"000834\"}"))
                .andRespond(withSuccess("""
                        {"productType":"MUTUAL_FUND","code":"000834","name":"纳指联接",
                         "market":"FUND_CN","currency":"CNY","provider":"AKSHARE",
                         "dataDate":"2026-08-06","latestPrice":"1.2345","warnings":[],
                         "fundTypeRaw":"指数型-海外股票","fundCategory":"QDII_INDEX_FUND",
                         "classificationSource":"AKSHARE_FUND_NAME_EM",
                         "classificationVersion":"fund-classification-v1"}
                        """, MediaType.APPLICATION_JSON));

        AnalysisServiceClient.ResolvedProduct result =
                client.resolveProduct("MUTUAL_FUND", "000834");

        assertThat(result.fundTypeRaw()).isEqualTo("指数型-海外股票");
        assertThat(result.fundCategory()).isEqualTo("QDII_INDEX_FUND");
        assertThat(result.classificationSource()).isEqualTo("AKSHARE_FUND_NAME_EM");
        assertThat(result.classificationVersion()).isEqualTo("fund-classification-v1");
        server.verify();
    }
}
