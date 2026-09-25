package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvestmentSyncWorkerTest {

    @Test
    void persistDailyQuotes_shouldSaveEveryReturnedTradingDay() {
        ProductDailyQuoteMapper quoteMapper = mock(ProductDailyQuoteMapper.class);
        when(quoteMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        ProductDailyQuotePersistenceService writer = new ProductDailyQuotePersistenceService(quoteMapper);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(88L);

        ProductDailyQuote latest = writer.persistHistory(product, Map.of(
                "provider", "AKSHARE",
                "adapterVersion", "1",
                "records", List.of(
                        quote("2026-07-10", "10.20"),
                        quote("2026-07-11", "10.50"),
                        quote("2026-07-14", "10.80")
                )
        ), "QFQ");

        verify(quoteMapper, times(3)).insert(any(ProductDailyQuote.class));
        assertThat(latest.getTradeDate().toString()).isEqualTo("2026-07-14");
        assertThat(latest.getClosePrice()).isEqualByComparingTo("10.80");

        ArgumentCaptor<ProductDailyQuote> captor = ArgumentCaptor.forClass(ProductDailyQuote.class);
        verify(quoteMapper, times(3)).insert(captor.capture());
        ProductDailyQuote second = captor.getAllValues().get(1);
        ProductDailyQuote third = captor.getAllValues().get(2);
        assertThat(second.getPreviousClose()).isEqualByComparingTo("10.20");
        assertThat(second.getChangeAmount()).isEqualByComparingTo("0.30");
        assertThat(second.getChangePercent()).isEqualByComparingTo("2.941176");
        assertThat(third.getPreviousClose()).isEqualByComparingTo("10.50");
        assertThat(third.getChangeAmount()).isEqualByComparingTo("0.30");
        assertThat(third.getChangePercent()).isEqualByComparingTo("2.857143");
    }

    @Test
    void persistDailyQuotes_shouldUseCanonicalNavForMutualFunds() {
        ProductDailyQuoteMapper quoteMapper = mock(ProductDailyQuoteMapper.class);
        when(quoteMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        ProductDailyQuotePersistenceService writer = new ProductDailyQuotePersistenceService(quoteMapper);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(89L);
        product.setProductType("MUTUAL_FUND");

        ProductDailyQuote latest = writer.persistHistory(product, Map.of(
                "provider", "AKSHARE",
                "adapterVersion", "1",
                "records", List.of(Map.of("data_date", "2026-07-17", "nav", "1.2511"))
        ), "NONE");

        assertThat(latest.getClosePrice()).isEqualByComparingTo("1.2511");
        assertThat(latest.getAdjustType()).isEqualTo("NONE");
    }

    @Test
    void navOnlyRefreshPreservesExistingFundTotalReturnIndex() {
        ProductDailyQuoteMapper quoteMapper = mock(ProductDailyQuoteMapper.class);
        ProductDailyQuote existing = new ProductDailyQuote();
        existing.setId(17L);
        existing.setTotalReturnIndex(new java.math.BigDecimal("1.4321"));
        when(quoteMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        ProductDailyQuotePersistenceService writer = new ProductDailyQuotePersistenceService(quoteMapper);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(89L);
        product.setProductType("MUTUAL_FUND");

        writer.persistHistory(product, Map.of("provider", "AKSHARE", "adapterVersion", "1",
                "records", List.of(Map.of("data_date", "2026-07-17", "nav", "1.2511"))), "NONE");

        assertThat(existing.getTotalReturnIndex()).isEqualByComparingTo("1.4321");
        verify(quoteMapper).updateById(existing);
    }

    @Test
    void incomingReturnIndexDoesNotReplaceAnExistingValidFundIndex() {
        ProductDailyQuoteMapper quoteMapper = mock(ProductDailyQuoteMapper.class);
        ProductDailyQuote existing = new ProductDailyQuote();
        existing.setId(17L);
        existing.setTotalReturnIndex(new java.math.BigDecimal("1.4321"));
        when(quoteMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(89L);
        product.setProductType("MUTUAL_FUND");

        new ProductDailyQuotePersistenceService(quoteMapper).persistHistory(product,
                Map.of("provider", "AKSHARE", "adapterVersion", "1", "records",
                        List.of(Map.of("data_date", "2026-07-17", "nav", "1.2511",
                                "total_return_index", "1.9999"))), "NONE");

        assertThat(existing.getTotalReturnIndex()).isEqualByComparingTo("1.4321");
    }

    @Test
    void syncHistoryRequirement_shouldComeFromTheResolvedGlobalProfile() {
        InvestmentHorizonService horizonService = mock(InvestmentHorizonService.class);
        when(horizonService.resolve(7L, null)).thenReturn(new ResolvedHorizonProfile(
                "template:v1|global:3|asset:0", "v1",
                List.of(new HorizonSetting("POSITION", "配置", 10, 80, 900, true, "GLOBAL")),
                List.of()));
        InvestmentSyncWorker worker = new InvestmentSyncWorker(
                mock(InvestmentSyncBatchMapper.class), mock(InvestmentPositionMapper.class),
                mock(InvestmentProductMapper.class), mock(ProductDailyQuoteMapper.class),
                mock(UnifiedMarketDataIngestionService.class), mock(DailyExchangeRateMapper.class),
                mock(AnalysisServiceClient.class), horizonService,
                horizonProperties(2500), runtimeProperties(14));

        int requiredHistoryDays = worker.requiredHistoryDays(7L);

        assertThat(requiredHistoryDays).isEqualTo(2500);
        verify(horizonService).resolve(7L, null);
    }

    @Test
    void fxLookbackShouldComeFromRuntimeConfiguration() {
        InvestmentSyncWorker worker = new InvestmentSyncWorker(
                mock(InvestmentSyncBatchMapper.class), mock(InvestmentPositionMapper.class),
                mock(InvestmentProductMapper.class), mock(ProductDailyQuoteMapper.class),
                mock(UnifiedMarketDataIngestionService.class), mock(DailyExchangeRateMapper.class),
                mock(AnalysisServiceClient.class), mock(InvestmentHorizonService.class),
                horizonProperties(2500), runtimeProperties(21));

        assertThat(worker.fxStartDate(java.time.LocalDate.of(2026, 7, 16)))
                .isEqualTo(java.time.LocalDate.of(2026, 6, 25));
    }

    static InvestmentHorizonProperties horizonProperties(int maximumHistoryDays) {
        InvestmentHorizonProperties properties = new InvestmentHorizonProperties();
        properties.setMaxHistoryTradingDays(maximumHistoryDays);
        properties.setHistoryMultiplier(3);
        properties.setMinimumHistoryTradingDays(20);
        properties.setCalendarDaysPerYear(365);
        properties.setTradingDaysPerYear(240);
        properties.setCalendarBufferDays(30);
        return properties;
    }

    static InvestmentRuntimeProperties runtimeProperties(int fxLookbackDays) {
        InvestmentRuntimeProperties properties = new InvestmentRuntimeProperties();
        properties.getSync().setBatchLimit(5);
        properties.getSync().setErrorMessageMaxLength(500);
        properties.getSync().setFxLookbackCalendarDays(fxLookbackDays);
        properties.getDataQuality().setStockAdjustType("QFQ");
        properties.getDataQuality().setFundAdjustType("NONE");
        return properties;
    }

    private static Map<String, Object> quote(String date, String close) {
        return Map.of(
                "data_date", date, "open", close, "high", close,
                "low", close, "close", close, "volume", "1000"
        );
    }
}
