package com.smartfinance.agent.investment.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AnalysisServiceClientTest {

    @Test
    void quantJobs_shouldUseAsynchronousInternalContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/quant/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andExpect(content().json("""
                        {"type":"FACTOR_ANALYSIS","datasetVersion":"%s","productType":"STOCK",
                         "horizonCode":"WAVE","horizonDays":37,"records":[{"close":"10"}]}
                        """.formatted("a".repeat(64))))
                .andRespond(withSuccess("{\"jobId\":\"%s\",\"status\":\"QUEUED\"}"
                        .formatted("b".repeat(32)), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://analysis.test/internal/v1/quant/jobs/" + "b".repeat(32)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"jobId\":\"%s\",\"status\":\"SUCCEEDED\"}"
                        .formatted("b".repeat(32)), MediaType.APPLICATION_JSON));

        Map<String, Object> created = client.createQuantJob(Map.of(
                "type", "FACTOR_ANALYSIS", "datasetVersion", "a".repeat(64),
                "productType", "STOCK", "horizonCode", "WAVE", "horizonDays", 37,
                "records", List.of(Map.of("close", "10"))));
        Map<String, Object> completed = client.quantJob(String.valueOf(created.get("jobId")));

        assertThat(completed.get("status")).isEqualTo("SUCCEEDED");
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
                         "primaryHorizon":"WAVE"}
                        """))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("riskPreference"))))
                .andRespond(withSuccess("{" + "\"status\":\"READY\",\"score\":72" + "}",
                        MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.technicalAnalysis(
                List.of(Map.of("data_date", "2026-07-14", "close", "10.50")),
                Map.of("WAVE", List.of(7, 33), "POSITION", List.of(55, 233)),
                "WAVE");

        assertThat(result.get("score")).isEqualTo(72);
        server.verify();
    }

    @Test
    void backtest_shouldSendEveryConfiguredHorizonWithoutLegacyFixedDays() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalysisServiceClient client = new AnalysisServiceClient(builder, "http://analysis.test", "secret-token");
        server.expect(requestTo("http://analysis.test/internal/v1/analysis/backtest"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"records":[{"data_date":"2026-07-14","close":"10.50"}],
                         "horizons":{"WAVE":[7,33],"POSITION":[55,233]}}
                        """))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("horizon_days"))))
                .andRespond(withSuccess("{\"status\":\"READY\",\"horizons\":{}}",
                        MediaType.APPLICATION_JSON));

        client.backtest(
                List.of(Map.of("data_date", "2026-07-14", "close", "10.50")),
                Map.of("WAVE", List.of(7, 33), "POSITION", List.of(55, 233)));

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
                         "latestPrice":"1204.98","previousClose":"1190.00",
                         "changeAmount":"14.98","changePercent":"1.2588","warnings":[]}
                        """, MediaType.APPLICATION_JSON));

        AnalysisServiceClient.ResolvedProduct result = client.resolveProduct("STOCK", "600519");

        assertThat(result.name()).isEqualTo("贵州茅台");
        assertThat(result.latestPrice()).isEqualByComparingTo("1204.98");
        assertThat(result.changeAmount()).isEqualByComparingTo("14.98");
        assertThat(result.changePercent()).isEqualByComparingTo("1.2588");
        server.verify();
    }
}
