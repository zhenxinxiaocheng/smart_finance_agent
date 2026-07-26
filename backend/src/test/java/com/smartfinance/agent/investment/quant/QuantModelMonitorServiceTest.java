package com.smartfinance.agent.investment.quant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuantModelMonitorServiceTest {
    @Mock private QuantStrategyVersionMapper strategyMapper;
    @Mock private QuantModelVersionMapper modelMapper;
    @Mock private QuantPredictionMapper predictionMapper;
    @Mock private QuantModelMonitorMapper monitorMapper;
    @Mock private QuantPaperOrderMapper orderMapper;
    @Mock private QuantPaperFillMapper fillMapper;
    @Mock private InvestmentAssetMapper assetMapper;
    @Mock private ProductDailyQuoteMapper quoteMapper;
    @Mock private AnalysisServiceClient analysisServiceClient;

    private QuantModelMonitorService service;

    @BeforeEach
    void setUp() {
        InvestmentRuntimeProperties runtime = new InvestmentRuntimeProperties();
        runtime.getMarket().setZone(ZoneId.of("Asia/Shanghai"));
        service = new QuantModelMonitorService(
                strategyMapper,
                modelMapper,
                predictionMapper,
                monitorMapper,
                orderMapper,
                fillMapper,
                assetMapper,
                quoteMapper,
                analysisServiceClient,
                runtime,
                new ObjectMapper().findAndRegisterModules(),
                20
        );
    }

    @Test
    void passingPaperStrategyBecomesChampionAfterRequiredTradingDays() {
        QuantStrategyVersion strategy = new QuantStrategyVersion();
        strategy.setId(1L);
        strategy.setStrategyVersion("strategy-v1");
        strategy.setModelVersion("model-v1");
        strategy.setUserId(7L);
        strategy.setAssetId(2L);
        strategy.setProductType("STOCK");
        strategy.setModelFamily("A_SHARE_STOCK");
        strategy.setHorizonCode("SHORT");
        strategy.setDeploymentRole("CHALLENGER");
        strategy.setStatus("PAPER");
        QuantStrategyVersion currentChampion = new QuantStrategyVersion();
        currentChampion.setId(2L);
        currentChampion.setStrategyVersion("strategy-old");
        currentChampion.setModelVersion("model-old");
        currentChampion.setUserId(7L);
        currentChampion.setAssetId(2L);
        currentChampion.setProductType("STOCK");
        currentChampion.setModelFamily("A_SHARE_STOCK");
        currentChampion.setHorizonCode("SHORT");
        currentChampion.setDeploymentRole("CHAMPION");
        currentChampion.setStatus("CHAMPION");
        QuantStrategyVersion unrelatedChampion = new QuantStrategyVersion();
        unrelatedChampion.setId(3L);
        unrelatedChampion.setStrategyVersion("strategy-other");
        unrelatedChampion.setModelVersion("model-other");
        unrelatedChampion.setUserId(8L);
        unrelatedChampion.setAssetId(9L);
        unrelatedChampion.setProductType("STOCK");
        unrelatedChampion.setModelFamily("A_SHARE_STOCK");
        unrelatedChampion.setHorizonCode("SHORT");
        unrelatedChampion.setDeploymentRole("CHAMPION");
        unrelatedChampion.setStatus("CHAMPION");
        QuantModelVersion model = new QuantModelVersion();
        model.setModelVersion("model-v1");
        model.setHorizonDays(2);
        model.setStatus("VALIDATED");
        model.setMetricsJson("""
                {
                  "policyMaximumBrierScore": 0.25,
                  "policyMinimumRealizedExcessReturn": 0.0,
                  "policyConsecutiveFailuresBeforeRetirement": 3,
                  "policyMinimumPaperTradingDays": 2,
                  "policyMaximumFeatureZScore": 3.0,
                  "policyMaximumLabelZScore": 3.0,
                  "featureDistribution": {"trend": {"mean": 0.0, "std": 1.0}},
                  "labelDistribution": {"mean": 0.05, "std": 0.05}
                }
                """);
        QuantPrediction prediction = prediction("2026-07-01");
        QuantPrediction prediction2 = prediction("2026-07-04");
        QuantPrediction prediction3 = prediction("2026-07-07");
        InvestmentAsset asset = new InvestmentAsset();
        asset.setProductId(3L);
        when(modelMapper.selectOne(any())).thenReturn(model);
        when(monitorMapper.selectCount(any())).thenReturn(0L);
        when(monitorMapper.selectList(any())).thenReturn(List.of());
        when(predictionMapper.selectList(any())).thenReturn(List.of(
                prediction, prediction2, prediction3));
        when(assetMapper.selectById(2L)).thenReturn(asset);
        when(quoteMapper.selectList(any())).thenReturn(
                quotes("2026-07-01"),
                quotes("2026-07-04"),
                quotes("2026-07-07")
        );
        when(analysisServiceClient.benchmarkHistory(any(), any(), any())).thenReturn(Map.of(
                "records", List.of(
                        Map.of("data_date", "2026-07-01", "close", 100),
                        Map.of("data_date", "2026-07-03", "close", 101)
                )
        ));
        when(fillMapper.countTradingDays("strategy-v1")).thenReturn(60L);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(strategyMapper.selectList(any())).thenReturn(List.of(
                currentChampion,
                unrelatedChampion
        ));

        service.monitor(strategy);

        assertThat(strategy.getStatus()).isEqualTo("CHAMPION");
        assertThat(strategy.getDeploymentRole()).isEqualTo("CHAMPION");
        assertThat(currentChampion.getStatus()).isEqualTo("ARCHIVED");
        assertThat(currentChampion.getDeploymentRole()).isEqualTo("ARCHIVED");
        assertThat(unrelatedChampion.getStatus()).isEqualTo("CHAMPION");
        assertThat(unrelatedChampion.getDeploymentRole()).isEqualTo("CHAMPION");
        assertThat(model.getStatus()).isEqualTo("PAPER_VERIFIED");
        ArgumentCaptor<QuantModelMonitor> monitorCaptor =
                ArgumentCaptor.forClass(QuantModelMonitor.class);
        verify(monitorMapper).insert(monitorCaptor.capture());
        assertThat(monitorCaptor.getValue().getEvidenceJson())
                .contains("\"featureDriftStatus\":\"PASS\"")
                .contains("\"dataDriftStatus\":\"PASS\"");
        verify(strategyMapper).updateById(strategy);
        verify(modelMapper).updateById(model);
    }

    private static QuantPrediction prediction(String date) {
        QuantPrediction prediction = new QuantPrediction();
        prediction.setAssetId(2L);
        prediction.setAsOfDate(LocalDate.parse(date));
        prediction.setHorizonDays(2);
        prediction.setProbabilityPositiveExcess(new BigDecimal("0.70"));
        prediction.setRoundTripCostBps(new BigDecimal("18"));
        prediction.setBenchmarkCode("CSI300");
        prediction.setFeatureVectorJson("{\"trend\":1.0}");
        return prediction;
    }

    private static ProductDailyQuote quote(String date, String close) {
        ProductDailyQuote quote = new ProductDailyQuote();
        quote.setTradeDate(LocalDate.parse(date));
        quote.setClosePrice(new BigDecimal(close));
        return quote;
    }

    private static List<ProductDailyQuote> quotes(String start) {
        LocalDate date = LocalDate.parse(start);
        return List.of(
                quote(date.toString(), "100"),
                quote(date.plusDays(1).toString(), "104"),
                quote(date.plusDays(2).toString(), "110")
        );
    }
}
