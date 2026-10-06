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
    void latestFundNavQueuesReturnHistoryWithoutInventingATotalReturnIndex() {
        ProductDailyQuoteMapper quotes = mock(ProductDailyQuoteMapper.class);
        InvestmentDataJobService jobs = mock(InvestmentDataJobService.class);
        InvestmentSyncWorker writer = new InvestmentSyncWorker(
                mock(InvestmentSyncBatchMapper.class), mock(InvestmentPositionMapper.class),
                mock(InvestmentProductMapper.class), quotes,
                mock(DailyExchangeRateMapper.class), mock(AnalysisServiceClient.class),
                mock(InvestmentDataQualityService.class), jobs,
                mock(InvestmentHorizonService.class), null, new InvestmentRuntimeProperties());
        InvestmentProduct product = new InvestmentProduct();
        product.setId(89L);
        product.setProductType("MUTUAL_FUND");
        ProductDailyQuote incoming = new ProductDailyQuote();
        incoming.setProductId(89L);
        incoming.setTradeDate(java.time.LocalDate.of(2026, 9, 30));
        incoming.setClosePrice(new java.math.BigDecimal("1.2065"));

        assertThat(writer.persistDisplayQuote(product, incoming)).isTrue();

        verify(quotes).insert(incoming);
        assertThat(incoming.getTotalReturnIndex()).isNull();
        verify(jobs).queueAnalysisForProduct(89L);
    }

    @Test
    void completeFundHistoryIsNotOverwrittenOrRequeuedByANavSnapshot() {
        ProductDailyQuoteMapper quotes = mock(ProductDailyQuoteMapper.class);
        InvestmentDataJobService jobs = mock(InvestmentDataJobService.class);
        InvestmentSyncWorker writer = new InvestmentSyncWorker(
                mock(InvestmentSyncBatchMapper.class), mock(InvestmentPositionMapper.class),
                mock(InvestmentProductMapper.class), quotes,
                mock(DailyExchangeRateMapper.class), mock(AnalysisServiceClient.class),
                mock(InvestmentDataQualityService.class), jobs,
                mock(InvestmentHorizonService.class), null, null);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(89L);
        product.setProductType("MUTUAL_FUND");
        ProductDailyQuote existing = new ProductDailyQuote();
        existing.setTotalReturnIndex(java.math.BigDecimal.ONE);
        when(quotes.selectOne(any())).thenReturn(existing);
        ProductDailyQuote incoming = new ProductDailyQuote();
        incoming.setProductId(89L);
        incoming.setTradeDate(java.time.LocalDate.of(2026, 9, 30));
        incoming.setClosePrice(java.math.BigDecimal.ONE);

        assertThat(writer.persistDisplayQuote(product, incoming)).isFalse();

        verify(quotes, never()).updateById(any(ProductDailyQuote.class));
        verify(quotes, never()).insert(any(ProductDailyQuote.class));
        verifyNoInteractions(jobs);
    }

    private static InvestmentSyncWorker syncWorker(ProductDailyQuoteMapper quoteMapper) {
        return new InvestmentSyncWorker(
                mock(InvestmentSyncBatchMapper.class), mock(InvestmentPositionMapper.class),
                mock(InvestmentProductMapper.class), quoteMapper,
                mock(DailyExchangeRateMapper.class), mock(AnalysisServiceClient.class),
                mock(InvestmentDataQualityService.class), mock(InvestmentDataJobService.class),
                mock(InvestmentHorizonService.class), null, new InvestmentRuntimeProperties());
    }


    @Test
    void persistDailyQuotes_shouldSaveEveryReturnedTradingDay() {
        ProductDailyQuoteMapper quoteMapper = mock(ProductDailyQuoteMapper.class);
        when(quoteMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        InvestmentSyncWorker writer = syncWorker(quoteMapper);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(88L);

        ProductDailyQuote latest = writer.persistDailyQuotes(product, Map.of(
                "provider", "AKSHARE",
                "adapterVersion", "1",
                "records", List.of(
                        quote("2026-07-10", "10.20"),
                        quote("2026-07-11", "10.50"),
                        quote("2026-07-14", "10.80")
                )
        ), "QFQ");

        assertThat(latest.getTradeDate().toString()).isEqualTo("2026-07-14");
        assertThat(latest.getClosePrice()).isEqualByComparingTo("10.80");

        ArgumentCaptor<List<ProductDailyQuote>> captor = ArgumentCaptor.forClass(List.class);
        verify(quoteMapper).upsertHistory(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        ProductDailyQuote second = captor.getValue().get(1);
        ProductDailyQuote third = captor.getValue().get(2);
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
        InvestmentSyncWorker writer = syncWorker(quoteMapper);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(89L);
        product.setProductType("MUTUAL_FUND");

        ProductDailyQuote latest = writer.persistDailyQuotes(product, Map.of(
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
        existing.setTradeDate(java.time.LocalDate.of(2026, 7, 17));
        when(quoteMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(quoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        InvestmentSyncWorker writer = syncWorker(quoteMapper);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(89L);
        product.setProductType("MUTUAL_FUND");

        writer.persistDailyQuotes(product, Map.of("provider", "AKSHARE", "adapterVersion", "1",
                "records", List.of(Map.of("data_date", "2026-07-17", "nav", "1.2511"))), "NONE");

        assertThat(existing.getTotalReturnIndex()).isEqualByComparingTo("1.4321");
        assertThat(existing.getClosePrice()).isEqualByComparingTo("1.2511");
    }

    @Test
    void validatedReturnIndexCorrectionReplacesAnExistingFundIndex() {
        ProductDailyQuoteMapper quoteMapper = mock(ProductDailyQuoteMapper.class);
        ProductDailyQuote existing = new ProductDailyQuote();
        existing.setId(17L);
        existing.setTotalReturnIndex(new java.math.BigDecimal("1.4321"));
        existing.setTradeDate(java.time.LocalDate.of(2026, 7, 17));
        when(quoteMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(quoteMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        InvestmentProduct product = new InvestmentProduct();
        product.setId(89L);
        product.setProductType("MUTUAL_FUND");

        syncWorker(quoteMapper).persistDailyQuotes(product,
                Map.of("provider", "AKSHARE", "adapterVersion", "1", "records",
                        List.of(Map.of("data_date", "2026-07-17", "nav", "1.2511",
                                "total_return_index", "1.9999"))), "NONE");

        assertThat(existing.getTotalReturnIndex()).isEqualByComparingTo("1.9999");
    }

    @Test
    void singleDayIncrementUsesThePreviousPersistedTradingDay() {
        ProductDailyQuoteMapper quotes = mock(ProductDailyQuoteMapper.class);
        ProductDailyQuote previous = new ProductDailyQuote();
        previous.setClosePrice(new java.math.BigDecimal("10.00"));
        when(quotes.selectOne(any(Wrapper.class))).thenReturn(previous, null);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(88L);
        ProductDailyQuote latest = syncWorker(quotes).persistDailyQuotes(product,
                Map.of("provider", "AKSHARE", "adapterVersion", "1", "records",
                        List.of(quote("2026-07-14", "10.80"))), "NONE");
        assertThat(latest.getPreviousClose()).isEqualByComparingTo("10.00");
        assertThat(latest.getChangePercent()).isEqualByComparingTo("8.00");
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
                mock(DailyExchangeRateMapper.class),
                mock(AnalysisServiceClient.class), mock(InvestmentDataQualityService.class),
                mock(InvestmentDataJobService.class), horizonService,
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
                mock(DailyExchangeRateMapper.class),
                mock(AnalysisServiceClient.class), mock(InvestmentDataQualityService.class),
                mock(InvestmentDataJobService.class), mock(InvestmentHorizonService.class),
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
