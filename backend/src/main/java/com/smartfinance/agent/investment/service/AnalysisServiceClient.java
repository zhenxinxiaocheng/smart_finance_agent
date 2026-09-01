package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentProduct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AnalysisServiceClient {

    public record ResolvedProduct(String productType, String code, String name, String market,
                                  String currency, String provider, LocalDate dataDate,
                                  BigDecimal latestPrice, BigDecimal previousClose,
                                  BigDecimal changeAmount, BigDecimal changePercent,
                                  BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
                                  BigDecimal volume, BigDecimal amount, BigDecimal turnoverRate,
                                  BigDecimal volumeRatio, BigDecimal amplitude,
                                  List<String> warnings, LocalDate inceptionDate,
                                  String fundTypeRaw, String fundCategory,
                                  String classificationSource, String classificationVersion,
                                  String benchmarkName, String trackingTarget,
                                  String benchmarkCode, String benchmarkSourceUri,
                                  String benchmarkSourceVersion) {
        public ResolvedProduct(String productType, String code, String name, String market,
                               String currency, String provider, LocalDate dataDate,
                               BigDecimal latestPrice, BigDecimal previousClose,
                               BigDecimal changeAmount, BigDecimal changePercent,
                               BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
                               BigDecimal volume, BigDecimal amount, BigDecimal turnoverRate,
                               BigDecimal volumeRatio, BigDecimal amplitude,
                               List<String> warnings, LocalDate inceptionDate,
                               String fundTypeRaw, String fundCategory,
                               String classificationSource, String classificationVersion) {
            this(productType, code, name, market, currency, provider, dataDate, latestPrice,
                    previousClose, changeAmount, changePercent, openPrice, highPrice, lowPrice,
                    volume, amount, turnoverRate, volumeRatio, amplitude, warnings, inceptionDate,
                    fundTypeRaw, fundCategory, classificationSource, classificationVersion,
                    null, null, null, null, null);
        }

        public ResolvedProduct(String productType, String code, String name, String market,
                               String currency, String provider, LocalDate dataDate,
                               BigDecimal latestPrice, BigDecimal previousClose,
                               BigDecimal changeAmount, BigDecimal changePercent,
                               BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
                               BigDecimal volume, BigDecimal amount, BigDecimal turnoverRate,
                               BigDecimal volumeRatio, BigDecimal amplitude,
                               List<String> warnings, LocalDate inceptionDate) {
            this(productType, code, name, market, currency, provider, dataDate, latestPrice,
                    previousClose, changeAmount, changePercent, openPrice, highPrice, lowPrice,
                    volume, amount, turnoverRate, volumeRatio, amplitude, warnings, inceptionDate,
                    null, null, null, null, null, null, null, null, null);
        }

        public ResolvedProduct(String productType, String code, String name, String market,
                               String currency, String provider, LocalDate dataDate,
                               BigDecimal latestPrice, BigDecimal previousClose,
                               BigDecimal changeAmount, BigDecimal changePercent,
                               BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
                               BigDecimal volume, BigDecimal amount, BigDecimal turnoverRate,
                               BigDecimal volumeRatio, BigDecimal amplitude,
                               List<String> warnings) {
            this(productType, code, name, market, currency, provider, dataDate, latestPrice,
                    previousClose, changeAmount, changePercent, openPrice, highPrice, lowPrice,
                    volume, amount, turnoverRate, volumeRatio, amplitude, warnings, null,
                    null, null, null, null, null, null, null, null, null);
        }

        public ResolvedProduct(String productType, String code, String name, String market,
                               String currency, String provider, LocalDate dataDate,
                               BigDecimal latestPrice, List<String> warnings) {
            this(productType, code, name, market, currency, provider, dataDate, latestPrice,
                    null, null, null, null, null, null, null, null, null, null, null, warnings, null,
                    null, null, null, null, null, null, null, null, null);
        }
    }

    public record RealtimeQuote(String code, String market, BigDecimal latestPrice,
                                LocalDate dataDate, LocalDateTime fetchedAt,
                                BigDecimal previousClose, BigDecimal changeAmount,
                                BigDecimal changePercent, BigDecimal openPrice,
                                BigDecimal highPrice, BigDecimal lowPrice,
                                BigDecimal volume, BigDecimal amount,
                                BigDecimal turnoverRate, BigDecimal volumeRatio,
                                BigDecimal amplitude,
                                String provider, List<String> warnings) {
    }

    private final RestClient restClient;
    private final RestClient benchmarkRestClient;
    private final String internalToken;

    @Autowired
    public AnalysisServiceClient(RestClient.Builder builder,
                                 @Value("${analysis-service.base-url:http://127.0.0.1:8090}") String baseUrl,
                                 @Value("${analysis-service.internal-token:dev-analysis-token}") String internalToken,
                                 @Value("${analysis-service.benchmark-connect-timeout:10s}") Duration benchmarkConnectTimeout,
                                 @Value("${analysis-service.benchmark-read-timeout:90s}") Duration benchmarkReadTimeout) {
        RestClient.Builder baseBuilder = builder.clone().baseUrl(baseUrl);
        this.restClient = baseBuilder.clone().build();
        SimpleClientHttpRequestFactory benchmarkRequestFactory =
                new SimpleClientHttpRequestFactory();
        benchmarkRequestFactory.setConnectTimeout(benchmarkConnectTimeout);
        benchmarkRequestFactory.setReadTimeout(benchmarkReadTimeout);
        this.benchmarkRestClient = baseBuilder.clone()
                .requestFactory(benchmarkRequestFactory)
                .build();
        this.internalToken = internalToken;
    }

    AnalysisServiceClient(RestClient.Builder builder, String baseUrl, String internalToken) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.benchmarkRestClient = this.restClient;
        this.internalToken = internalToken;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> dailyQuotes(InvestmentProduct product, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", product.getCode());
        body.put("market", product.getMarket());
        body.put("product_type", product.getProductType());
        body.put("start_date", startDate.toString());
        body.put("end_date", endDate.toString());
        Map<String, Object> response = restClient.post()
                .uri("/internal/v1/market-data/quotes/daily")
                .header("X-Internal-Token", internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("分析服务返回空响应");
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> dailyFx(String baseCurrency, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("base_currency", baseCurrency);
        body.put("quote_currency", "CNY");
        body.put("start_date", startDate.toString());
        body.put("end_date", endDate.toString());
        Map<String, Object> response = restClient.post()
                .uri("/internal/v1/market-data/fx/daily")
                .header("X-Internal-Token", internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null) throw new IllegalStateException("分析服务返回空汇率响应");
        return response;
    }

    @SuppressWarnings("unchecked")
    public List<LocalDate> aShareTradingDates(int year) {
        Map<String, Object> response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/v1/market-data/calendar")
                        .queryParam("market", "A_SHARE")
                        .queryParam("year", year)
                        .build())
                .header("X-Internal-Token", internalToken)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("tradingDates") instanceof List<?> dates)) {
            throw new IllegalStateException("分析服务返回空交易日历");
        }
        return dates.stream().map(String::valueOf).map(LocalDate::parse).toList();
    }

    public Map<String, Object> technicalAnalysis(List<? extends Map<String, ?>> records,
                                                 Map<String, ? extends List<Integer>> horizons,
                                                 String primaryHorizon,
                                                 Map<String, ?> marketSnapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", records);
        body.put("horizons", horizons);
        body.put("primaryHorizon", primaryHorizon);
        body.put("marketSnapshot", marketSnapshot == null ? Map.of() : marketSnapshot);
        return postAnalysis("/internal/v1/analysis/technical", body);
    }

    public Map<String, Object> fundamentalAnalysis(List<? extends Map<String, ?>> periods) {
        return postAnalysis("/internal/v1/analysis/fundamental", Map.of("periods", periods));
    }

    public Map<String, Object> fundamentalAnalysis(String code, String market) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("market", market);
        body.put("periods", List.of());
        return postAnalysis("/internal/v1/analysis/fundamental", body);
    }

    public Map<String, Object> fundAnalysis(List<? extends Map<String, ?>> records) {
        return fundAnalysis(records, null, Map.of(), null, Map.of());
    }

    public Map<String, Object> fundAnalysis(List<? extends Map<String, ?>> records,
                                            Map<String, Map<String, Integer>> horizons,
                                            String primaryHorizon) {
        return fundAnalysis(records, null, horizons, primaryHorizon, Map.of());
    }

    public Map<String, Object> fundAnalysis(List<? extends Map<String, ?>> records,
                                            String fundCategory,
                                            Map<String, Map<String, Integer>> horizons,
                                            String primaryHorizon,
                                            Map<String, ?> benchmark) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", records);
        if (fundCategory != null && !fundCategory.isBlank()) {
            body.put("fundCategory", fundCategory);
        }
        body.put("horizons", horizons);
        body.put("primaryHorizon", primaryHorizon);
        if (benchmark != null && !benchmark.isEmpty()) {
            body.put("benchmark", benchmark);
        }
        return postAnalysis("/internal/v1/analysis/fund", body);
    }

    public Map<String, Object> backtest(List<? extends Map<String, ?>> records,
                                        Map<String, ? extends List<Integer>> horizons) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", records);
        body.put("horizons", horizons);
        return postAnalysis("/internal/v1/analysis/backtest", body);
    }

    public Map<String, Object> createQuantJob(Map<String, ?> body) {
        return postInternal("/internal/v1/quant/jobs", body, "量化任务");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> quantRuntimeManifest() {
        Map<String, Object> response = restClient.get()
                .uri("/internal/v1/quant/runtime-manifest")
                .header("X-Internal-Token", internalToken)
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("量化运行时版本信息为空");
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> quantJob(String jobId) {
        Map<String, Object> response = restClient.get()
                .uri("/internal/v1/quant/jobs/{jobId}", jobId)
                .header("X-Internal-Token", internalToken)
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("分析服务返回空量化任务结果");
        }
        return response;
    }

    public Map<String, Object> benchmarkHistory(String benchmarkCode,
                                                LocalDate startDate,
                                                LocalDate endDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("benchmarkCode", benchmarkCode);
        body.put("startDate", startDate.toString());
        body.put("endDate", endDate.toString());
        return postInternal(
                benchmarkRestClient,
                "/internal/v1/market-data/benchmarks/daily",
                body,
                "量化基准行情"
        );
    }

    public Map<String, Object> researchFundUniverse(String modelFamily,
                                                    String benchmarkCode,
                                                    String targetCode,
                                                    LocalDate startDate,
                                                    LocalDate endDate,
                                                    int limit,
                                                    int minimumRecords,
                                                    Map<String, Object> selectionRule) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelFamily", modelFamily);
        body.put("benchmarkCode", benchmarkCode);
        body.put("targetCode", targetCode);
        body.put("startDate", startDate.toString());
        body.put("endDate", endDate.toString());
        body.put("limit", limit);
        body.put("minimumRecords", minimumRecords);
        body.put("selectionRule", selectionRule);
        return postInternal(
                benchmarkRestClient,
                "/internal/v1/market-data/research-universes/funds",
                body,
                "量化研究资产池"
        );
    }

    public Map<String, Object> cancelQuantJob(String jobId) {
        return postInternal(
                "/internal/v1/quant/jobs/" + jobId + "/cancel",
                Map.of(),
                "取消量化任务"
        );
    }

    public Map<String, Object> validateDataQuality(InvestmentProduct product,
                                                    LocalDate startDate,
                                                    LocalDate endDate,
                                                    String frequency,
                                                    String adjustType,
                                                    String qualityConfigVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productType", product.getProductType());
        body.put("code", product.getCode());
        body.put("market", product.getMarket());
        body.put("frequency", frequency);
        body.put("adjustType", adjustType);
        body.put("startDate", startDate.toString());
        body.put("endDate", endDate.toString());
        body.put("qualityConfigVersion", qualityConfigVersion);
        return postInternal("/internal/v1/data-quality/validate", body, "数据质量校验");
    }

    public Map<String, Object> replayDataQuality(String datasetVersion,
                                                  String secondaryDatasetVersion,
                                                  String qualityConfigVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasetVersion", datasetVersion);
        if (secondaryDatasetVersion != null && !secondaryDatasetVersion.isBlank()) {
            body.put("secondaryDatasetVersion", secondaryDatasetVersion);
        }
        body.put("qualityConfigVersion", qualityConfigVersion);
        return postInternal("/internal/v1/data-quality/replay", body, "数据质量重放");
    }

    public void claimDataQuality(String datasetVersion, String qualityConfigVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasetVersion", datasetVersion);
        body.put("qualityConfigVersion", qualityConfigVersion);
        postInternal("/internal/v1/data-quality/claim", body, "数据快照认领");
    }

    @SuppressWarnings("unchecked")
    public ResolvedProduct resolveProduct(String productType, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product_type", productType);
        body.put("code", code);
        Map<String, Object> response = benchmarkRestClient.post()
                .uri("/internal/v1/products/resolve")
                .header("X-Internal-Token", internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null) throw new IllegalStateException("分析服务返回空产品响应");
        Object dataDate = response.get("dataDate");
        return new ResolvedProduct(
                String.valueOf(response.get("productType")),
                String.valueOf(response.get("code")),
                String.valueOf(response.get("name")),
                String.valueOf(response.get("market")),
                String.valueOf(response.get("currency")),
                String.valueOf(response.get("provider")),
                dataDate == null ? null : LocalDate.parse(String.valueOf(dataDate)),
                decimal(response, "latestPrice"), decimal(response, "previousClose"),
                decimal(response, "changeAmount"), decimal(response, "changePercent"),
                decimal(response, "openPrice"), decimal(response, "highPrice"),
                decimal(response, "lowPrice"), decimal(response, "volume"),
                decimal(response, "amount"), decimal(response, "turnoverRate"),
                decimal(response, "volumeRatio"), decimal(response, "amplitude"),
                response.get("warnings") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(),
                response.get("inceptionDate") == null
                        ? null
                        : LocalDate.parse(String.valueOf(response.get("inceptionDate"))),
                text(response, "fundTypeRaw"),
                text(response, "fundCategory"),
                text(response, "classificationSource"),
                text(response, "classificationVersion"),
                text(response, "benchmarkName"),
                text(response, "trackingTarget"),
                text(response, "benchmarkCode"),
                text(response, "benchmarkSourceUri"),
                text(response, "benchmarkSourceVersion")
        );
    }

    private static String text(Map<String, Object> response, String key) {
        Object value = response.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    @SuppressWarnings("unchecked")
    public RealtimeQuote realtimeQuote(String code, String market) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("market", market);
        Map<String, Object> response = restClient.post()
                .uri("/internal/v1/quotes/realtime")
                .header("X-Internal-Token", internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null) throw new IllegalStateException("分析服务返回空实时报价响应");
        return new RealtimeQuote(
                String.valueOf(response.get("code")),
                String.valueOf(response.get("market")),
                decimal(response, "latestPrice"),
                LocalDate.parse(String.valueOf(response.get("dataDate"))),
                OffsetDateTime.parse(String.valueOf(response.get("fetchedAt"))).toLocalDateTime(),
                decimal(response, "previousClose"), decimal(response, "changeAmount"),
                decimal(response, "changePercent"), decimal(response, "openPrice"),
                decimal(response, "highPrice"), decimal(response, "lowPrice"),
                decimal(response, "volume"), decimal(response, "amount"),
                decimal(response, "turnoverRate"), decimal(response, "volumeRatio"),
                decimal(response, "amplitude"),
                String.valueOf(response.get("provider")),
                response.get("warnings") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of()
        );
    }

    private static BigDecimal decimal(Map<String, Object> response, String key) {
        Object value = response.get(key);
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postAnalysis(String path, Map<String, ?> body) {
        return postInternal(path, body, "分析");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postInternal(String path, Map<String, ?> body, String operation) {
        return postInternal(restClient, path, body, operation);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postInternal(RestClient client,
                                             String path,
                                             Map<String, ?> body,
                                             String operation) {
        Map<String, Object> response = client.post()
                .uri(path)
                .header("X-Internal-Token", internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("分析服务返回空" + operation + "结果");
        }
        return response;
    }
}
