package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentDataQualitySnapshot;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

/**
 * 历史行情准备：增量同步与全量回补边界的回归测试。
 */
class InvestmentHistoryPreparationServiceTest {

    @TempDir
    Path temporaryDirectory;

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
        when(dataQualityService.adjustType(any())).thenReturn("QFQ");
        when(quoteMapper.latestTradeDate(any(), eq("NONE"))).thenAnswer(invocation ->
                quoteMapper.latestTradeDate(invocation.getArgument(0), "QFQ"));
        when(dataQualityService.resolve(any(), any(), any(), anyString(), eq(true)))
                .thenAnswer(invocation -> evaluation(invocation.getArgument(1), invocation.getArgument(2)));
        when(analysisClient.marketDailyQuotes(any(), any(), any(), anyString()))
                .thenAnswer(invocation -> evaluation(invocation.getArgument(1), invocation.getArgument(2)).response());
        when(quoteMapper.selectCount(any())).thenReturn(10L);
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T02:00:00Z"), SHANGHAI);
        service = new InvestmentHistoryPreparationService(
                productMapper, quoteMapper, dataQualityService, syncWorker,
                classificationService, analysisClient, clock);
    }

    @Test
    void confirmedClosedStockWindowDoesNotCallFailingPriceSources() {
        InvestmentProduct product = product();
        LocalDate lastQuote = LocalDate.of(2026, 9, 30);
        LocalDate target = LocalDate.of(2026, 10, 4);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(lastQuote);
        when(analysisClient.aShareTradingDates(2026)).thenReturn(List.of(lastQuote, target.plusDays(4)));
        org.mockito.Mockito.doThrow(new IllegalStateException("price source unavailable"))
                .when(dataQualityService).resolve(any(), any(), any(), anyString(), anyBoolean());

        var result = service.prepareProduct(product, "STOCK_HISTORY", false, lastQuote.plusDays(1), target);

        assertThat(result.skipped()).isTrue();
        assertThat(result.sampleEndDate()).isEqualTo(lastQuote);
        assertThat(product.getHistoryCoverageComplete()).isFalse();
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
        verify(analysisClient, never()).marketDailyQuotes(any(), any(), any(), anyString());
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
        verify(productMapper, never()).updateById(any(InvestmentProduct.class));
    }

    @Test
    void assetFundJobAlreadyAtPublicationBoundDoesNotRefetchAnUnpublishedTail() {
        var product = product();
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setFundCategory("QDII_INDEX_FUND");
        var published = TODAY.minusDays(1);
        var availability = mock(InvestmentQuoteAvailabilityService.class);
        when(availability.target(any(), any(), any())).thenReturn(published);
        service.setAvailability(availability);
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(quoteMapper.latestCompleteFundTradeDate(21L)).thenReturn(published);
        when(productMapper.selectById(21L)).thenReturn(product);
        var job = new InvestmentDataJob();
        job.setProductId(21L);
        job.setJobType("FUND_NAV_HISTORY");

        assertThat(service.prepare(job).skipped()).isTrue();
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
        verify(analysisClient, never()).marketDailyQuotes(any(), any(), any(), anyString());
    }

    @Test
    void fullHistoryCanBeVerifiedThroughThePublishedBoundOnAClosedDay() {
        var product = product();
        var origin = TODAY.minusYears(5);
        var published = TODAY.minusDays(1);
        product.setListingDate(origin);
        var availability = mock(InvestmentQuoteAvailabilityService.class);
        when(availability.target(any(), any(), any())).thenReturn(published);
        service.setAvailability(availability);

        var result = service.prepareProduct(product, "STOCK_HISTORY", true);

        verify(dataQualityService).resolve(product, origin, published, "QFQ", true);
        assertThat(result.coverageComplete()).isTrue();
    }

    @Test
    void explicitCurrentTailRetainsQualityChecksWhenPublicationEndsBeforeToday() {
        var product = product();
        var origin = TODAY.minusYears(5);
        var published = TODAY.minusDays(1);
        product.setListingDate(origin);
        var availability = mock(InvestmentQuoteAvailabilityService.class);
        when(availability.target(any(), any(), any())).thenReturn(published);
        service.setAvailability(availability);

        service.prepareProduct(product, "STOCK_HISTORY", true, origin, published);

        verify(dataQualityService).resolve(product, origin, published, "QFQ", true);
        verify(analysisClient, never()).marketDailyQuotes(any(), any(), any(), anyString());
    }
    @Test
    void demandWindowCannotBeConfirmedFromOnlyItsLastObservation() {
        var product=product();
        LocalDate start=LocalDate.of(2026,7,20),end=LocalDate.of(2026,7,24);
        when(dataQualityService.resolve(any(),any(),any(),anyString(),eq(true)))
                .thenReturn(evaluation(end,end));
        when(analysisClient.aShareTradingDates(2026)).thenReturn(List.of(start,start.plusDays(1),end,end.plusDays(10)));
        var result=service.prepareDemandProduct(product,"STOCK_HISTORY",true,start,end);
        assertThat(result.verifiedThrough()).isNull();
    }
    @Test
    void publishedFundWindowCanFinishOnAConfirmedClosedDay() {
        var product=product();product.setProductType("MUTUAL_FUND");product.setMarket("FUND_CN");product.setFundCategory("INDEX_FUND");
        when(dataQualityService.adjustType(any())).thenReturn("NONE");
        LocalDate friday=LocalDate.of(2026,7,24),sunday=friday.plusDays(2);
        var response=new java.util.LinkedHashMap<>(evaluation(friday,friday).response());
        response.put("records",List.of(Map.of("data_date",friday.toString(),"nav",1.2,"total_return_index",1.2)));
        var evaluated=new InvestmentDataQualityService.Evaluation(evaluation(friday,friday).snapshot(),response,List.of(),List.of());
        when(dataQualityService.resolve(any(),any(),any(),anyString(),eq(true))).thenReturn(evaluated);
        when(analysisClient.aShareTradingDates(2026)).thenReturn(List.of(friday,friday.plusDays(3)));
        assertThat(service.prepareDemandProduct(product,"FUND_NAV_HISTORY",true,friday,sunday).verifiedThrough()).isEqualTo(sunday);
    }
    @Test
    void emptyFundWeekendExtendsOnlyAConfirmedDomesticReceipt() {
        var product=product();product.setProductType("MUTUAL_FUND");product.setMarket("FUND_CN");product.setFundCategory("INDEX_FUND");
        when(dataQualityService.adjustType(any())).thenReturn("NONE");
        LocalDate friday=LocalDate.of(2026,7,24),sunday=friday.plusDays(2);
        when(quoteMapper.latestCompleteFundTradeDate(21L)).thenReturn(friday);
        when(analysisClient.aShareTradingDates(2026)).thenReturn(List.of(friday,friday.plusDays(3)));
        org.mockito.Mockito.doThrow(new AnalysisServiceClient.SourceEmptyException("not published"))
                .when(dataQualityService).resolve(any(),any(),any(),anyString(),eq(true));
        assertThat(service.prepareDemandProduct(product,"FUND_NAV_HISTORY",false,friday.plusDays(1),sunday).verifiedThrough()).isEqualTo(sunday);
        product.setFundCategory("QDII_FUND");
        assertThat(service.prepareDemandProduct(product,"FUND_NAV_HISTORY",false,friday.plusDays(1),sunday).verifiedThrough()).isNull();
    }

    @Test
    void confirmedClosedEtfWindowDoesNotFetchOrInventPrices() {
        InvestmentProduct product = product();
        product.setProductType("ETF");
        product.setMarket("SSE");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        LocalDate lastQuote = LocalDate.of(2026, 9, 30);
        LocalDate target = LocalDate.of(2026, 10, 4);
        when(quoteMapper.latestTradeDate(21L, "NONE")).thenReturn(lastQuote);
        when(analysisClient.aShareTradingDates(2026)).thenReturn(List.of(lastQuote, target.plusDays(4)));
        org.mockito.Mockito.doThrow(new IllegalStateException("price source unavailable"))
                .when(analysisClient).marketDailyQuotes(any(), any(), any(), anyString());

        var result = service.prepareProduct(product, "STOCK_HISTORY", false, lastQuote.plusDays(1), target);

        assertThat(result.skipped()).isTrue();
        assertThat(result.sampleEndDate()).isEqualTo(lastQuote);
        verify(analysisClient, never()).marketDailyQuotes(any(), any(), any(), anyString());
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
    }

    @Test
    void truncatedCalendarCannotDeclareTheUncoveredWindowClosed() {
        InvestmentProduct product = product();
        LocalDate lastQuote = LocalDate.of(2026, 9, 30);
        LocalDate target = LocalDate.of(2026, 10, 4);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(lastQuote);
        when(analysisClient.aShareTradingDates(2026)).thenReturn(List.of(lastQuote.minusDays(1), lastQuote));
        org.mockito.Mockito.doThrow(new IllegalStateException("price source unavailable"))
                .when(dataQualityService).resolve(any(), any(), any(), anyString(), anyBoolean());

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false, lastQuote.plusDays(1), target))
                .hasMessage("price source unavailable");
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
    }

    @Test
    void calendarOutageDoesNotBlockRealPriceAcquisition() {
        InvestmentProduct product = product();
        LocalDate lastQuote = TODAY.minusDays(2);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(lastQuote);
        when(analysisClient.aShareTradingDates(2026)).thenThrow(new IllegalStateException("calendar unavailable"));

        var result = service.prepareProduct(product, "STOCK_HISTORY", false, lastQuote.plusDays(1), TODAY);

        assertThat(result.skipped()).isFalse();
        verify(syncWorker, org.mockito.Mockito.times(2)).persistDailyQuotes(eq(product), any(), anyString());
    }

    @Test
    void closedChinaExchangeDoesNotSuppressOverseasFundNav() {
        InvestmentProduct product = product();
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(quoteMapper.latestCompleteFundTradeDate(21L)).thenReturn(TODAY.minusDays(2));

        var result = service.prepareProduct(product, "FUND_NAV_HISTORY", false, TODAY.minusDays(1), TODAY);

        assertThat(result.skipped()).isFalse();
        verify(analysisClient, never()).aShareTradingDates(org.mockito.ArgumentMatchers.anyInt());
        verify(syncWorker).persistDailyQuotes(eq(product), any(), eq("NONE"));
    }

    @Test
    void chinaCalendarDoesNotSuppressUsStockAcquisition() {
        InvestmentProduct product = product();
        product.setMarket("NASDAQ");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(quoteMapper.latestTradeDate(21L, "NONE")).thenReturn(TODAY.minusDays(2));

        var result = service.prepareProduct(product, "STOCK_HISTORY", false, TODAY.minusDays(1), TODAY);

        assertThat(result.skipped()).isFalse();
        verify(analysisClient, never()).aShareTradingDates(org.mockito.ArgumentMatchers.anyInt());
        verify(syncWorker).persistDailyQuotes(eq(product), any(), eq("NONE"));
    }

    @Test
    void initialHistoryCannotBeCompletedByAClosedWindowShortcut() {
        InvestmentProduct product = product();
        product.setProductType("ETF");
        product.setMarket("SSE");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "historyYears", 5);
        org.mockito.Mockito.doThrow(new AnalysisServiceClient.SourceEmptyException("no initial history"))
                .when(analysisClient).marketDailyQuotes(any(), any(), any(), anyString());

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(AnalysisServiceClient.SourceEmptyException.class);
        verify(analysisClient, never()).aShareTradingDates(org.mockito.ArgumentMatchers.anyInt());
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
        assertThat(product.getHistoryCoverageComplete()).isFalse();
    }

    @Test
    void unchangedQfqAnchorIsCheckedButNotWrittenAsANewIncrement() {
        InvestmentProduct product = product();
        LocalDate anchorDay = LocalDate.of(2026, 7, 28);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(anchorDay);
        ProductDailyQuote anchor = new ProductDailyQuote();
        anchor.setTradeDate(anchorDay);
        anchor.setClosePrice(java.math.BigDecimal.ONE);
        when(quoteMapper.selectOne(any())).thenReturn(anchor);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.requestedStartDate()).isEqualTo(anchorDay.plusDays(1));
        verify(dataQualityService).resolve(product, anchorDay, TODAY, "QFQ", true);
        verify(dataQualityService).resolve(product, anchorDay.plusDays(1), TODAY, "NONE", true);
        var written = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(syncWorker).persistDailyQuotes(eq(product), written.capture(), eq("QFQ"));
        assertThat(InvestmentHistoryPreparationService.recordDates(written.getValue()))
                .doesNotContain(anchorDay).contains(TODAY);
    }

    @Test
    void changedQfqAnchorRebuildsBothSeriesInsteadOfSplicingAdjustmentBases() {
        InvestmentProduct product = product();
        LocalDate origin = LocalDate.of(2001, 8, 27);
        LocalDate anchorDay = LocalDate.of(2026, 7, 28);
        product.setListingDate(origin);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(anchorDay);
        ProductDailyQuote anchor = new ProductDailyQuote();
        anchor.setTradeDate(anchorDay);
        anchor.setClosePrice(java.math.BigDecimal.TEN);
        when(quoteMapper.selectOne(any())).thenReturn(anchor);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.requestedStartDate()).isEqualTo(origin);
        verify(dataQualityService).resolve(product, anchorDay, TODAY, "QFQ", true);
        verify(dataQualityService).resolve(product, origin, TODAY, "QFQ", true);
        verify(dataQualityService).resolve(product, origin, TODAY, "NONE", true);
        verify(syncWorker, org.mockito.Mockito.times(2)).persistDailyQuotes(eq(product), any(), anyString());
        assertThat(product.getHistoryStartDate()).isEqualTo(origin);
    }

    @Test
    void forcedHistoricalWindowStillChecksTheExistingAdjustmentBasis() {
        var product=product();LocalDate origin=LocalDate.of(2026,7,1),start=origin.plusDays(9),end=start.plusDays(2),last=TODAY.minusDays(1);
        product.setListingDate(origin);
        when(quoteMapper.latestTradeDate(21L,"QFQ")).thenReturn(last);
        var anchor=new ProductDailyQuote();anchor.setTradeDate(last);anchor.setClosePrice(java.math.BigDecimal.TEN);
        when(quoteMapper.selectOne(any())).thenReturn(null,anchor);
        when(quoteMapper.selectList(any())).thenReturn(List.of(anchor));
        var response=new java.util.LinkedHashMap<String,Object>(evaluation(origin,last).response());
        response.put("records",List.of(Map.of("data_date",origin.toString(),"close",1),Map.of("data_date",last.toString(),"close",1)));
        org.mockito.Mockito.doReturn(response).when(analysisClient).marketDailyQuotes(eq(product),eq(origin),eq(last),anyString());
        var result=service.prepareProduct(product,"STOCK_HISTORY",true,start,end);
        assertThat(result.requestedStartDate()).isEqualTo(origin);
        verify(analysisClient).marketDailyQuotes(product,start,last,"QFQ");
        verify(analysisClient).marketDailyQuotes(product,origin,last,"QFQ");
        verify(analysisClient).marketDailyQuotes(product,origin,last,"NONE");
    }

    @Test
    void basisRebuildMissingAnExistingObservationPreservesBothStoredSeries() {
        var product=product();LocalDate origin=LocalDate.of(2026,7,1),start=origin.plusDays(9),end=start.plusDays(2),last=TODAY.minusDays(1);
        product.setListingDate(origin);
        when(quoteMapper.latestTradeDate(21L,"QFQ")).thenReturn(last);
        var anchor=new ProductDailyQuote();anchor.setTradeDate(last);anchor.setClosePrice(java.math.BigDecimal.TEN);
        var existing=new ProductDailyQuote();existing.setTradeDate(origin.plusDays(1));
        when(quoteMapper.selectOne(any())).thenReturn(null,anchor);
        when(quoteMapper.selectList(any())).thenReturn(List.of(anchor,existing));
        assertThatThrownBy(()->service.prepareProduct(product,"STOCK_HISTORY",true,start,end)).hasMessageContaining("未覆盖已有日期");
        verify(syncWorker,never()).persistDailyQuotes(any(),any(),anyString());
    }

    @Test
    void incrementalRunOnlyRequestsTheNewDateRange() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenReturn(evaluation(LocalDate.of(2026, 7, 29), LocalDate.of(2026, 7, 29)));

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        // 只请求「本地最后有效日期 + 1」到今天的区间。
        assertThat(result.requestedStartDate()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(result.skipped()).isFalse();
        verify(dataQualityService).resolve(product, LocalDate.of(2026, 7, 29), TODAY, "QFQ", true);
        verify(syncWorker).persistDailyQuotes(eq(product), any(), eq("QFQ"));
        assertThat(product.getHistoryEndDate()).isEqualTo(LocalDate.of(2026, 7, 29));
    }

    @Test
    void noRemoteIncrementSkipsWithoutTouchingCoverage() {
        InvestmentProduct product = product();
        product.setHistoryCoverageComplete(true);
        product.setHistoryEndDate(LocalDate.of(2026, 7, 28));
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        // 只有结构化SOURCE_EMPTY代表该增量窗口尚无记录。
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenThrow(new AnalysisServiceClient.SourceEmptyException("no records"));

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.skipped()).isTrue();
        // 已有效的覆盖标记不能被增量同步打回。
        assertThat(product.getHistoryCoverageComplete()).isTrue();
        assertThat(product.getHistoryEndDate()).isEqualTo(LocalDate.of(2026, 7, 28));
    }

    @Test
    void localSeriesAlreadyCurrentDoesNotCallTheProvider() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(TODAY);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.skipped()).isTrue();
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
    }

    @Test
    void providerHasNothingInIncrementalWindowIsSkippedNotFailed() {
        InvestmentProduct product = product();
        product.setHistoryCoverageComplete(true);
        product.setHistoryEndDate(LocalDate.of(2026, 7, 28));
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        // 数据源在该增量窗口内没有任何记录，客户端已经按结构化code分类。
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenThrow(new AnalysisServiceClient.SourceEmptyException("AKSHARE: no records"));

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        // 视为「尚无新发布」，而不是失败；既有状态原样保留。
        assertThat(result.skipped()).isTrue();
        assertThat(result.recordCount()).isEqualTo(10);
        assertThat(product.getHistoryCoverageComplete()).isTrue();
        assertThat(product.getHistoryEndDate()).isEqualTo(LocalDate.of(2026, 7, 28));
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
    }

    @Test
    void providerEmptyDuringExplicitFullRepairStillFails() {
        InvestmentProduct product = product();
        product.setInceptionDate(LocalDate.of(2020, 1, 2));
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenThrow(new AnalysisServiceClient.SourceEmptyException("AKSHARE: no records"));

        // 用户显式要求全量修复时，数据源为空必须暴露为失败，不能静默跳过。
        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no records");
    }

    @Test
    void unrelatedProviderFailureIsNotSwallowed() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenThrow(new IllegalStateException("503 Service Unavailable: provider timeout"));

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider timeout");
    }

    @Test
    void missingLocalHistoryTriggersFullBackfillFromInception() {
        InvestmentProduct product = product();
        product.setInceptionDate(LocalDate.of(2001, 8, 27));
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(null);
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenReturn(evaluation(LocalDate.of(2001, 8, 27), TODAY));

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.requestedStartDate()).isEqualTo(LocalDate.of(2001, 8, 27));
        assertThat(result.coverageComplete()).isTrue();
        assertThat(product.getHistoryCoverageComplete()).isTrue();
    }

    @Test
    void explicitFullRepairIsTheOnlyWayToRebuildAnExistingSeries() {
        InvestmentProduct product = product();
        product.setInceptionDate(LocalDate.of(2001, 8, 27));
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenReturn(evaluation(LocalDate.of(2001, 8, 27), TODAY));

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", true);

        assertThat(result.requestedStartDate()).isEqualTo(LocalDate.of(2001, 8, 27));
        verify(dataQualityService).resolve(product, LocalDate.of(2001, 8, 27), TODAY, "QFQ", true);
    }

    @Test
    void assetJobEntryDelegatesToTheSameProductLevelCapability() {
        InvestmentProduct product = product();
        when(productMapper.selectById(21L)).thenReturn(product);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenReturn(evaluation(LocalDate.of(2026, 7, 29), LocalDate.of(2026, 7, 29)));

        InvestmentDataJob job = new InvestmentDataJob();
        job.setId(91L);
        job.setProductId(21L);
        job.setJobType("STOCK_HISTORY");
        job.setForceRefresh(false);

        InvestmentHistoryPreparationService.PreparationResult viaJob = service.prepare(job);
        InvestmentHistoryPreparationService.PreparationResult viaProduct =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        // 个人持仓入口与 Market Data 入口走同一套逻辑，结果一致。
        assertThat(viaJob.requestedStartDate()).isEqualTo(viaProduct.requestedStartDate());
        assertThat(viaJob.skipped()).isEqualTo(viaProduct.skipped());
    }

    @Test
    void blockedQualityStopsBeforePersistence() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        InvestmentDataQualityService.Evaluation blocked = evaluation(
                LocalDate.of(2026, 7, 29), LocalDate.of(2026, 7, 29));
        blocked.snapshot().setDecision("BLOCK");
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true))).thenReturn(blocked);

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(InvestmentHistoryPreparationService.QualityBlockedException.class);
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
    }

    @Test
    void outOfWindowRowsAreFailuresAndNeverOverwriteExistingHistory() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenReturn(evaluation(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 7, 28)));

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("区间之外");
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
        verify(productMapper, never()).updateById(any());
    }

    @Test
    void errorTextContainingNoRecordsIsNeverTreatedAsAnEmptyWindow() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenThrow(new IllegalStateException("transport timeout while parsing no records"));

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transport timeout");
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
    }

    @Test
    void rawSeriesLagControlsTheSharedIncrementalWindow() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 29));
        when(quoteMapper.latestTradeDate(21L, "NONE")).thenReturn(LocalDate.of(2026, 7, 27));

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.requestedStartDate()).isEqualTo(LocalDate.of(2026, 7, 28));
        verify(dataQualityService).resolve(product, LocalDate.of(2026, 7, 28), TODAY, "QFQ", true);
        verify(dataQualityService).resolve(product, LocalDate.of(2026, 7, 28), TODAY, "NONE", true);
        verify(syncWorker).persistDailyQuotes(eq(product), any(), eq("QFQ"));
        verify(syncWorker).persistDailyQuotes(eq(product), any(), eq("NONE"));
    }

    @Test
    void missingRawSeriesRequiresFirstBackfillEvenWhenResearchQuotesExist() {
        InvestmentProduct product = product();
        product.setListingDate(LocalDate.of(2001, 8, 27));
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 29));
        when(quoteMapper.latestTradeDate(21L, "NONE")).thenReturn(null);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.requestedStartDate()).isEqualTo(product.getListingDate());
        verify(dataQualityService).resolve(product, product.getListingDate(), TODAY, "QFQ", true);
        verify(dataQualityService).resolve(product, product.getListingDate(), TODAY, "NONE", true);
    }

    @Test
    void rawSeriesMustCoverEveryResearchDateBeforeEitherSeriesIsWritten() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        when(dataQualityService.resolve(any(), any(), any(), eq("NONE"), eq(true)))
                .thenReturn(evaluation(LocalDate.of(2026, 7, 29), LocalDate.of(2026, 7, 29)));

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("原始价格未覆盖");
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
        verify(dataQualityService, never()).claim(any());
    }

    @Test
    void rawQualityBlockStopsBothSeriesBeforePersistence() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        InvestmentDataQualityService.Evaluation blocked = evaluation(LocalDate.of(2026, 7, 29), TODAY);
        blocked.snapshot().setDecision("BLOCK");
        when(dataQualityService.resolve(any(), any(), any(), eq("NONE"), eq(true))).thenReturn(blocked);

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(InvestmentHistoryPreparationService.QualityBlockedException.class);
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
    }

    @Test
    void secondSeriesWriteFailureRollsBackTheFirstSeriesAndTheCoverageMarker() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("history-atomic.db").toAbsolutePath(), "", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE prepared_quotes (adjust_type TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE prepared_product (id INTEGER PRIMARY KEY, coverage_complete INTEGER NOT NULL)");
        jdbc.update("INSERT INTO prepared_product(id, coverage_complete) VALUES (21, 0)");
        doAnswer(invocation -> {
            String adjustment = invocation.getArgument(2);
            jdbc.update("INSERT INTO prepared_quotes(adjust_type) VALUES (?)", adjustment);
            if ("NONE".equals(adjustment)) throw new IllegalStateException("raw persistence failed");
            return null;
        }).when(syncWorker).persistDailyQuotes(any(), any(), anyString());
        when(productMapper.updateById(any())).thenAnswer(invocation -> {
            InvestmentProduct product = invocation.getArgument(0);
            return jdbc.update("UPDATE prepared_product SET coverage_complete=? WHERE id=?",
                    Boolean.TRUE.equals(product.getHistoryCoverageComplete()) ? 1 : 0, product.getId());
        });
        InvestmentProduct product = product();
        product.setListingDate(LocalDate.of(2001, 8, 27));
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T02:00:00Z"), SHANGHAI);
        InvestmentHistoryPreparationService transactionalService = new InvestmentHistoryPreparationService(
                productMapper, quoteMapper, dataQualityService, syncWorker, classificationService, analysisClient,
                clock, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));

        assertThatThrownBy(() -> transactionalService.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("raw persistence failed");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_quotes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT coverage_complete FROM prepared_product WHERE id=21", Integer.class))
                .isZero();
        verify(syncWorker).persistDailyQuotes(eq(product), any(), eq("QFQ"));
        verify(syncWorker).persistDailyQuotes(eq(product), any(), eq("NONE"));
        verify(productMapper, never()).updateById(any());
    }

    @Test
    void historicalMarketWindowUsesTheDailyContractWithoutApplyingTodaysFreshnessGate() {
        InvestmentProduct product = product();
        LocalDate start = LocalDate.of(2026, 7, 10);
        LocalDate target = LocalDate.of(2026, 7, 20);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false, start, target);

        assertThat(result.requestedStartDate()).isEqualTo(start);
        assertThat(result.sampleEndDate()).isEqualTo(target);
        assertThat(result.coverageComplete()).isFalse();
        assertThat(result.datasetVersion()).isNull();
        verify(analysisClient).marketDailyQuotes(product, start, target, "QFQ");
        verify(analysisClient).marketDailyQuotes(product, start, target, "NONE");
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
        verify(dataQualityService, never()).claim(any());
        verify(analysisClient, never()).resolveProduct(anyString(), anyString());
        verify(classificationService, never()).enrichIfMissing(any());
    }

    @Test
    void unknownFundInceptionUsesAcquiredTotalReturnsWithoutAPlaceholderCalendarDenominator() {
        InvestmentProduct product = product();
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setCode("010736");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        LocalDate placeholderStart = TODAY.minusYears(50);
        LocalDate actualStart = LocalDate.of(2018, 6, 1);
        Map<String, Object> response = Map.of("records", List.of(
                Map.of("data_date", actualStart.toString(), "close", "1.20", "total_return_index", "100"),
                Map.of("data_date", TODAY.toString(), "close", "1.24", "total_return_index", "108")));
        when(analysisClient.marketDailyQuotes(product, placeholderStart, TODAY, "NONE")).thenReturn(response);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "FUND_NAV_HISTORY", false, placeholderStart, TODAY);

        assertThat(result.requestedStartDate()).isEqualTo(placeholderStart);
        assertThat(result.sampleStartDate()).isEqualTo(actualStart);
        assertThat(product.getHistoryStartDate()).isEqualTo(actualStart);
        assertThat(product.getInceptionDate()).isNull();
        assertThat(result.coverageComplete()).isFalse();
        assertThat(result.datasetVersion()).isNull();
        verify(analysisClient).marketDailyQuotes(product, placeholderStart, TODAY, "NONE");
        verify(syncWorker).persistDailyQuotes(product, response, "NONE");
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
        verify(dataQualityService, never()).claim(any());
        verify(analysisClient, never()).resolveProduct(anyString(), anyString());
        verify(classificationService, never()).enrichIfMissing(any());
    }

    @Test
    void unknownFundFullWindowWithOneMissingTotalReturnRejectsTheWholeBatchBeforeAnyWrite() {
        InvestmentProduct product = product();
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setCode("010736");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        LocalDate placeholderStart = TODAY.minusYears(50);
        Map<String, Object> response = Map.of("records", List.of(
                Map.of("data_date", "2018-06-01", "close", "1.20", "total_return_index", "100"),
                Map.of("data_date", TODAY.toString(), "close", "1.24")));
        when(analysisClient.marketDailyQuotes(product, placeholderStart, TODAY, "NONE")).thenReturn(response);

        assertThatThrownBy(() -> service.prepareProduct(
                product, "FUND_NAV_HISTORY", false, placeholderStart, TODAY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("累计收益");

        assertThat(product.getHistoryStartDate()).isNull();
        assertThat(product.getHistoryEndDate()).isNull();
        assertThat(product.getHistoryCoverageComplete()).isFalse();
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
        verify(productMapper, never()).updateById(any());
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
        verify(dataQualityService, never()).claim(any());
        verify(analysisClient, never()).resolveProduct(anyString(), anyString());
    }

    @Test
    void historicalFundChunkPreservesTheProviderTotalReturnIndexWithinItsWindow() {
        InvestmentProduct product = product();
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setCode("010736");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        LocalDate start = LocalDate.of(2020, 6, 1);
        LocalDate target = LocalDate.of(2020, 6, 30);
        Map<String, Object> response = Map.of("records", List.of(
                Map.of("data_date", start.toString(), "close", "1.21", "total_return_index", "121.5"),
                Map.of("data_date", target.toString(), "close", "1.22", "total_return_index", "124.0")));
        when(analysisClient.marketDailyQuotes(product, start, target, "NONE")).thenReturn(response);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "FUND_NAV_HISTORY", false, start, target);

        assertThat(result.sampleStartDate()).isEqualTo(start);
        assertThat(result.sampleEndDate()).isEqualTo(target);
        assertThat(result.datasetVersion()).isNull();
        assertThat(result.coverageComplete()).isFalse();
        verify(syncWorker).persistDailyQuotes(product, response, "NONE");
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
    }

    @Test
    void historicalAcquisitionStillRejectsOutOfWindowRowsBeforePersistence() {
        InvestmentProduct product = product();
        LocalDate start = LocalDate.of(2026, 7, 10);
        LocalDate target = LocalDate.of(2026, 7, 20);
        when(analysisClient.marketDailyQuotes(product, start, target, "QFQ"))
                .thenReturn(evaluation(start.minusDays(1), target).response());

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false, start, target))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("区间之外");
        verify(syncWorker, never()).persistDailyQuotes(any(), any(), anyString());
    }

    @Test
    void knownProductCurrentIncrementRemainsQualityGatedEvenWithAnExplicitWindow() {
        InvestmentProduct product = product();
        product.setListingDate(LocalDate.of(2001, 8, 27));
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        LocalDate start = LocalDate.of(2026, 7, 29);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false, start, TODAY);

        assertThat(result.datasetVersion()).isEqualTo("dataset-v1");
        verify(dataQualityService).resolve(product, start, TODAY, "QFQ", true);
        verify(dataQualityService).resolve(product, start, TODAY, "NONE", true);
        verify(analysisClient, never()).marketDailyQuotes(any(), any(), any(), anyString());
    }

    @Test
    void etfUsesTheGeneralDailyContractAndDoesNotRequireProductMetadata() {
        InvestmentProduct product = product();
        product.setProductType("ETF");
        product.setCode("510300");
        product.setMarket("SH");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        org.mockito.Mockito.doAnswer(invocation ->
                        evaluation(invocation.getArgument(1), invocation.getArgument(2)).response())
                .when(analysisClient).marketDailyQuotes(eq(product), any(), any(), anyString());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "historyYears", 5);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.skipped()).isFalse();
        assertThat(result.coverageComplete()).isFalse();
        verify(analysisClient).marketDailyQuotes(product, TODAY.minusYears(5), TODAY, "NONE");
        verify(analysisClient, never()).marketDailyQuotes(any(), any(), any(), eq("QFQ"));
        verify(analysisClient, never()).resolveProduct(anyString(), anyString());
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
    }

    @Test
    void fundIncrementalStartRepairsTheEarliestMissingReturnInsteadOfSkippingAtLatestNav() {
        InvestmentProduct product = product();
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setCode("010736");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(quoteMapper.latestCompleteFundTradeDate(21L)).thenReturn(TODAY);
        LocalDate gap = LocalDate.of(2026, 7, 15);
        when(quoteMapper.earliestMissingFundReturnDate(21L)).thenReturn(gap);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "FUND_NAV_HISTORY", false);

        assertThat(result.requestedStartDate()).isEqualTo(gap);
        assertThat(result.skipped()).isFalse();
        verify(dataQualityService).resolve(product, gap, TODAY, "NONE", true);
        verify(syncWorker).persistDailyQuotes(eq(product), any(), eq("NONE"));
    }

    @Test
    void completeHistoryRequiresAnExplicitCalendarProofForBothSeries() {
        InvestmentProduct product = product();
        product.setListingDate(LocalDate.of(2001, 8, 27));
        when(dataQualityService.resolve(any(), any(), any(), eq("NONE"), eq(true)))
                .thenReturn(evaluation(product.getListingDate(), TODAY, false));

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.coverageComplete()).isFalse();
        assertThat(product.getHistoryCoverageComplete()).isFalse();
    }

    private static InvestmentProduct product() {
        InvestmentProduct product = new InvestmentProduct();
        product.setId(21L);
        product.setProductType("STOCK");
        product.setMarket("CN");
        product.setCode("600000");
        product.setHistoryCoverageComplete(false);
        return product;
    }

    private static InvestmentDataQualityService.Evaluation evaluation(
            LocalDate sampleStart, LocalDate sampleEnd) {
        return evaluation(sampleStart, sampleEnd, true);
    }

    private static InvestmentDataQualityService.Evaluation evaluation(
            LocalDate sampleStart, LocalDate sampleEnd, boolean calendarVerified) {
        InvestmentDataQualitySnapshot snapshot = new InvestmentDataQualitySnapshot();
        snapshot.setDatasetVersion("dataset-v1");
        snapshot.setQualityStatus("PASS");
        snapshot.setQualityRuleSetVersion("quality-v1");
        snapshot.setDecision("ALLOW");
        snapshot.setSampleStartDate(sampleStart);
        snapshot.setSampleEndDate(sampleEnd);
        List<Map<String, Object>> records = sampleStart.equals(sampleEnd)
                ? List.of(Map.of("data_date", sampleStart.toString(), "close", 1, "total_return_index", 100))
                : List.of(Map.of("data_date", sampleStart.toString(), "close", 1, "total_return_index", 100),
                        Map.of("data_date", sampleEnd.toString(), "close", 2, "total_return_index", 200));
        Map<String, Object> response = Map.of(
                "records", records,
                "manifest", Map.of("provider", "akshare", "adapterVersion", "v1"),
                "qualityReport", Map.of("issues", calendarVerified
                        ? List.of(Map.of("ruleCode", "STOCK_UNEXPLAINED_TRADING_GAPS", "outcome", "PASS",
                                "observed", Map.of("missingDateCount", 0)))
                        : List.of()));
        return new InvestmentDataQualityService.Evaluation(
                snapshot, response, records, List.of());
    }
}
