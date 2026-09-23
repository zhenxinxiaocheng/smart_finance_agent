package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentDataQualitySnapshot;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InvestmentHistoryPreparationServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 30);

    private InvestmentProductMapper productMapper;
    private ProductDailyQuoteMapper quoteMapper;
    private InvestmentDataQualityService dataQualityService;
    private InvestmentSyncWorker syncWorker;
    private FundClassificationService classificationService;
    private AnalysisServiceClient analysisClient;
    private InvestmentHistoryPreparationService service;

    @BeforeEach
    void setUp() {
        productMapper = mock(InvestmentProductMapper.class);
        quoteMapper = mock(ProductDailyQuoteMapper.class);
        dataQualityService = mock(InvestmentDataQualityService.class);
        syncWorker = mock(InvestmentSyncWorker.class);
        classificationService = mock(FundClassificationService.class);
        analysisClient = mock(AnalysisServiceClient.class);
        when(classificationService.enrichIfMissing(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T02:00:00Z"), SHANGHAI);
        service = new InvestmentHistoryPreparationService(
                productMapper, quoteMapper, dataQualityService, syncWorker,
                classificationService, analysisClient, clock);
    }

    @Test
    void firstPreparationStartsAtInceptionAndPersistsActualCoverage() {
        InvestmentProduct product = product(false);
        product.setInceptionDate(LocalDate.of(2001, 8, 27));
        when(productMapper.selectById(21L)).thenReturn(product);
        InvestmentDataQualityService.Evaluation evaluation = evaluation(
                LocalDate.of(2001, 8, 27), LocalDate.of(2026, 7, 29));
        when(dataQualityService.resolve(
                product, LocalDate.of(2001, 8, 27), TODAY, true))
                .thenReturn(evaluation);
        when(quoteMapper.selectCount(any())).thenReturn(5_987L);

        InvestmentHistoryPreparationService.PreparationResult result = service.prepare(job());

        assertThat(result.recordCount()).isEqualTo(5_987);
        assertThat(result.requestedStartDate()).isEqualTo(LocalDate.of(2001, 8, 27));
        assertThat(result.sampleStartDate()).isEqualTo(LocalDate.of(2001, 8, 27));
        assertThat(result.sampleEndDate()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(result.coverageComplete()).isTrue();
        verify(classificationService).enrichIfMissing(product);
        verify(dataQualityService).claim(evaluation);
        verify(syncWorker).persistDailyQuotes(product, evaluation.response());
        verify(productMapper).updateById(product);
        assertThat(product.getHistoryStartDate()).isEqualTo(LocalDate.of(2001, 8, 27));
        assertThat(product.getHistoryEndDate()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(product.getHistoryCoverageComplete()).isTrue();
    }

    @Test
    void completeHistoryUpdatesIncrementallyFromLastTradingDay() {
        InvestmentProduct product = product(true);
        product.setInceptionDate(LocalDate.of(2001, 8, 27));
        product.setHistoryStartDate(LocalDate.of(2001, 8, 27));
        product.setHistoryEndDate(LocalDate.of(2026, 7, 28));
        when(productMapper.selectById(21L)).thenReturn(product);
        InvestmentDataQualityService.Evaluation evaluation = evaluation(
                LocalDate.of(2026, 7, 28), LocalDate.of(2026, 7, 29));
        when(dataQualityService.resolve(
                product, LocalDate.of(2026, 7, 28), TODAY, true))
                .thenReturn(evaluation);
        when(quoteMapper.selectCount(any())).thenReturn(5_989L);

        InvestmentHistoryPreparationService.PreparationResult result = service.prepare(job());

        assertThat(result.requestedStartDate()).isEqualTo(LocalDate.of(2026, 7, 28));
        assertThat(result.recordCount()).isEqualTo(5_989);
        assertThat(product.getHistoryStartDate()).isEqualTo(LocalDate.of(2001, 8, 27));
        assertThat(product.getHistoryEndDate()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(product.getHistoryCoverageComplete()).isTrue();
    }

    @Test
    void intradayQuoteDoesNotMakeYesterdayDailyHistoryIncomplete() {
        InvestmentProduct product = product(true);
        product.setHistoryEndDate(LocalDate.of(2026, 7, 28));
        when(productMapper.selectById(21L)).thenReturn(product);
        when(dataQualityService.resolve(product, LocalDate.of(2026, 7, 28), TODAY, true))
                .thenReturn(evaluation(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 7, 29)));
        when(quoteMapper.latestTradeDate(21L)).thenReturn(LocalDate.of(2026, 7, 30));

        InvestmentHistoryPreparationService.PreparationResult result = service.prepare(job());

        assertThat(result.coverageComplete()).isTrue();
        assertThat(product.getHistoryCoverageComplete()).isTrue();
    }

    @Test
    void previouslyPublishedQuoteStillExposesLaggingHistoryProvider() {
        InvestmentProduct product = product(true);
        product.setHistoryEndDate(LocalDate.of(2026, 7, 27));
        when(productMapper.selectById(21L)).thenReturn(product);
        when(dataQualityService.resolve(product, LocalDate.of(2026, 7, 27), TODAY, true))
                .thenReturn(evaluation(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 7, 28)));
        when(quoteMapper.latestTradeDate(21L)).thenReturn(LocalDate.of(2026, 7, 29));

        InvestmentHistoryPreparationService.PreparationResult result = service.prepare(job());

        assertThat(result.coverageComplete()).isFalse();
    }

    @Test
    void unresolvedHistoricalGapPublishesBarsButDoesNotClaimCompleteHistory() {
        InvestmentProduct product = product(false);
        product.setInceptionDate(LocalDate.of(2011, 11, 22));
        when(productMapper.selectById(21L)).thenReturn(product);
        InvestmentDataQualityService.Evaluation base = evaluation(
                LocalDate.of(2011, 11, 22), LocalDate.of(2026, 7, 29));
        base.snapshot().setQualityStatus("WARN");
        var response = new java.util.LinkedHashMap<>(base.response());
        response.put("qualityReport", Map.of("issues", List.of(Map.of(
                "ruleCode", "STOCK_UNEXPLAINED_TRADING_GAPS", "outcome", "FAIL"))));
        when(dataQualityService.resolve(product, LocalDate.of(2011, 11, 22), TODAY, true))
                .thenReturn(new InvestmentDataQualityService.Evaluation(
                        base.snapshot(), response, base.records(), base.secondaryDatasetVersions()));

        InvestmentHistoryPreparationService.PreparationResult result = service.prepare(job());

        assertThat(result.coverageComplete()).isFalse();
        verify(syncWorker).persistDailyQuotes(product, response);
    }

    @Test
    void missingListingDateDoesNotInventHistoryStart() {
        InvestmentProduct product = product(false);
        when(productMapper.selectById(21L)).thenReturn(product);

        assertThatThrownBy(() -> service.prepare(job()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("起始日期缺失");
        verify(analysisClient).resolveProduct("STOCK", "600000");
        verifyNoInteractions(dataQualityService, syncWorker);
    }

    @Test
    void resolvesMissingStockListingDateBeforeQualityValidation() {
        InvestmentProduct product = product(false);
        when(productMapper.selectById(21L)).thenReturn(product);
        AnalysisServiceClient.ResolvedProduct resolved = mock(AnalysisServiceClient.ResolvedProduct.class);
        when(resolved.inceptionDate()).thenReturn(LocalDate.of(2011, 11, 22));
        when(analysisClient.resolveProduct("STOCK", "600000")).thenReturn(resolved);
        when(dataQualityService.resolve(product, LocalDate.of(2011, 11, 22), TODAY, true))
                .thenReturn(evaluation(LocalDate.of(2011, 11, 22), LocalDate.of(2026, 7, 29)));

        InvestmentHistoryPreparationService.PreparationResult result = service.prepare(job());

        assertThat(result.requestedStartDate()).isEqualTo(LocalDate.of(2011, 11, 22));
        assertThat(product.getInceptionDate()).isEqualTo(LocalDate.of(2011, 11, 22));
        assertThat(result.coverageComplete()).isTrue();
    }

    @Test
    void resolvesMissingFundInceptionBeforeQualityValidation() {
        InvestmentProduct product = product(false);
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setCode("000001");
        InvestmentDataJob fundJob = job();
        fundJob.setJobType("FUND_NAV_HISTORY");
        when(productMapper.selectById(21L)).thenReturn(product);
        AnalysisServiceClient.ResolvedProduct resolved = mock(AnalysisServiceClient.ResolvedProduct.class);
        when(resolved.inceptionDate()).thenReturn(LocalDate.of(2012, 8, 15));
        when(analysisClient.resolveProduct("MUTUAL_FUND", "000001")).thenReturn(resolved);
        when(dataQualityService.resolve(product, LocalDate.of(2012, 8, 15), TODAY, true))
                .thenReturn(evaluation(LocalDate.of(2012, 8, 15), LocalDate.of(2026, 7, 29)));

        InvestmentHistoryPreparationService.PreparationResult result = service.prepare(fundJob);

        assertThat(result.requestedStartDate()).isEqualTo(LocalDate.of(2012, 8, 15));
        assertThat(product.getInceptionDate()).isEqualTo(LocalDate.of(2012, 8, 15));
    }

    @Test
    void fundWithoutIndependentInceptionDateDoesNotClaimCompleteHistory() {
        InvestmentProduct product = product(false);
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setCode("000001");
        InvestmentDataJob fundJob = job();
        fundJob.setJobType("FUND_NAV_HISTORY");
        when(productMapper.selectById(21L)).thenReturn(product);

        assertThatThrownBy(() -> service.prepare(fundJob))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("起始日期缺失");
        verifyNoInteractions(dataQualityService, syncWorker);
    }

    @Test
    void blockedQualityNeverPersistsQuotes() {
        InvestmentProduct product = product(false);
        product.setInceptionDate(LocalDate.of(2011, 11, 22));
        when(productMapper.selectById(21L)).thenReturn(product);
        InvestmentDataQualityService.Evaluation blocked = evaluation(
                LocalDate.of(2011, 11, 22), LocalDate.of(2026, 7, 29));
        blocked.snapshot().setDecision("BLOCK");
        when(dataQualityService.resolve(product, LocalDate.of(2011, 11, 22), TODAY, true))
                .thenReturn(blocked);

        assertThatThrownBy(() -> service.prepare(job()))
                .isInstanceOf(InvestmentHistoryPreparationService.QualityBlockedException.class);
        assertThat(product.getHistoryCoverageComplete()).isFalse();
        verify(productMapper).updateById(product);
        verifyNoInteractions(syncWorker);
    }

    @Test
    void stockListingDateDefinesHistoryStartAndRequiredCoverage() {
        InvestmentProduct product = product(false);
        product.setListingDate(LocalDate.of(2011, 11, 22));
        when(productMapper.selectById(21L)).thenReturn(product);
        when(dataQualityService.resolve(product, LocalDate.of(2011, 11, 22), TODAY, true))
                .thenReturn(evaluation(LocalDate.of(2011, 12, 20), LocalDate.of(2026, 7, 29)));

        InvestmentHistoryPreparationService.PreparationResult result = service.prepare(job());

        assertThat(result.requestedStartDate()).isEqualTo(LocalDate.of(2011, 11, 22));
        assertThat(result.coverageComplete()).isFalse();
    }

    private static InvestmentDataJob job() {
        InvestmentDataJob job = new InvestmentDataJob();
        job.setId(91L);
        job.setProductId(21L);
        job.setJobType("STOCK_HISTORY");
        return job;
    }

    private static InvestmentProduct product(boolean coverageComplete) {
        InvestmentProduct product = new InvestmentProduct();
        product.setId(21L);
        product.setProductType("STOCK");
        product.setMarket("CN");
        product.setCode("600000");
        product.setHistoryCoverageComplete(coverageComplete);
        return product;
    }

    private static InvestmentDataQualityService.Evaluation evaluation(
            LocalDate sampleStart, LocalDate sampleEnd) {
        InvestmentDataQualitySnapshot snapshot = new InvestmentDataQualitySnapshot();
        snapshot.setDatasetVersion("dataset-v1");
        snapshot.setQualityStatus("PASS");
        snapshot.setQualityRuleSetVersion("quality-v1");
        snapshot.setDecision("ALLOW");
        snapshot.setSampleStartDate(sampleStart);
        snapshot.setSampleEndDate(sampleEnd);
        Map<String, Object> response = Map.of(
                "records", List.of(
                        Map.of("data_date", sampleStart.toString(), "close", 1),
                        Map.of("data_date", sampleEnd.toString(), "close", 2)),
                "manifest", Map.of("provider", "akshare", "adapterVersion", "v1"));
        return new InvestmentDataQualityService.Evaluation(
                snapshot, response, List.of(), List.of());
    }
}
