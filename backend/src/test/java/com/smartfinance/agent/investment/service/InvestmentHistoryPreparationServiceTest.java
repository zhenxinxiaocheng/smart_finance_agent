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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestmentHistoryPreparationServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 30);

    private InvestmentProductMapper productMapper;
    private ProductDailyQuoteMapper quoteMapper;
    private InvestmentDataQualityService dataQualityService;
    private InvestmentSyncWorker syncWorker;
    private FundClassificationService classificationService;
    private InvestmentHistoryPreparationService service;

    @BeforeEach
    void setUp() {
        productMapper = mock(InvestmentProductMapper.class);
        quoteMapper = mock(ProductDailyQuoteMapper.class);
        dataQualityService = mock(InvestmentDataQualityService.class);
        syncWorker = mock(InvestmentSyncWorker.class);
        classificationService = mock(FundClassificationService.class);
        when(classificationService.enrichIfMissing(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T02:00:00Z"), SHANGHAI);
        service = new InvestmentHistoryPreparationService(
                productMapper, quoteMapper, dataQualityService, syncWorker,
                classificationService, clock);
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
    void missingInceptionFallsBackToEarliestProviderRange() {
        InvestmentProduct product = product(false);
        when(productMapper.selectById(21L)).thenReturn(product);
        InvestmentDataQualityService.Evaluation evaluation = evaluation(
                LocalDate.of(1998, 3, 23), LocalDate.of(2026, 7, 29));
        when(dataQualityService.resolve(
                product, InvestmentHistoryPreparationService.EARLIEST_PROVIDER_DATE, TODAY, true))
                .thenReturn(evaluation);
        when(quoteMapper.selectCount(any())).thenReturn(6_400L);

        InvestmentHistoryPreparationService.PreparationResult result = service.prepare(job());

        assertThat(result.requestedStartDate())
                .isEqualTo(InvestmentHistoryPreparationService.EARLIEST_PROVIDER_DATE);
        assertThat(result.coverageComplete()).isTrue();
        assertThat(product.getHistoryStartDate()).isEqualTo(LocalDate.of(1998, 3, 23));
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
