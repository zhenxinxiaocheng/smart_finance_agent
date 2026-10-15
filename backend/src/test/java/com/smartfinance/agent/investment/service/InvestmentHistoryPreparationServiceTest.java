package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentDataQualitySnapshot;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.marketdata.contract.MarketDataContract;
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
    private ProductDailyQuoteService quoteService;
    private FundClassificationService classificationService;
    private AnalysisServiceClient analysisClient;
    private InvestmentHistoryPreparationService service;

    @BeforeEach
    void setUp() {
        productMapper = mock(InvestmentProductMapper.class);
        quoteMapper = mock(ProductDailyQuoteMapper.class);
        dataQualityService = mock(InvestmentDataQualityService.class);
        quoteService = mock(ProductDailyQuoteService.class);
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
                productMapper, quoteMapper, dataQualityService, quoteService,
                classificationService, analysisClient, clock);
    }

    @Test
    void oldRuleHistoryCannotAnchorNewRuleAcquisition() {
        var product=product();
        var start=TODAY.minusDays(5);
        var old=new ProductDailyQuote();old.setTradeDate(start.minusDays(10));old.setClosePrice(java.math.BigDecimal.TEN);
        old.setDataContractVersion("old-rule");
        when(quoteMapper.selectOne(any())).thenReturn(old);
        when(quoteMapper.countIncompatible(eq(21L),anyString(),anyString())).thenReturn(1);
        service.prepareDemandProduct(product,"STOCK_HISTORY",true,start,TODAY);
        verify(dataQualityService).resolve(eq(product),eq(start),eq(TODAY),eq("QFQ"),eq(true));
    }

    @Test
    void successfullyFetchedSparseWindowCompletesCollectionWithoutCalendarProof() {
        var product = product(); product.setMarket("NASDAQ");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        var start = TODAY.minusDays(20);
        when(dataQualityService.resolve(any(), any(), any(), anyString(), eq(true)))
                .thenReturn(evaluation(TODAY.minusDays(2), TODAY.minusDays(2)));
        var result = service.prepareDemandProduct(product, "STOCK_HISTORY", true, start, TODAY);
        assertThat(result.checkedThrough()).isEqualTo(TODAY);
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void normalEmptyInitialWindowCompletesQueryWithoutInventingQuotes() {
        var product = product();
        when(quoteMapper.selectCount(any())).thenReturn(0L);
        when(dataQualityService.resolve(any(), any(), any(), anyString(), eq(true)))
                .thenThrow(new AnalysisServiceClient.SourceEmptyException("no records"));
        var result = service.prepareDemandProduct(product, "STOCK_HISTORY", true, TODAY.minusDays(10), TODAY);
        assertThat(result.checkedThrough()).isEqualTo(TODAY);
        assertThat(result.recordCount()).isZero();
        assertThat(result.skipped()).isTrue();
        verify(quoteService, never()).persistBatch(any(), any(), anyString(), any(), any());
    }

    @Test
    void confirmedClosedStockWindowDoesNotCallFailingPriceSources() {
        InvestmentProduct product = product();
        LocalDate lastQuote = LocalDate.of(2026, 9, 30);
        LocalDate target = LocalDate.of(2026, 10, 4);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(lastQuote);
        policyCalendar(lastQuote.plusDays(1),target,List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("price source unavailable"))
                .when(dataQualityService).resolve(any(), any(), any(), anyString(), anyBoolean());

        var result = service.prepareProduct(product, "STOCK_HISTORY", false, lastQuote.plusDays(1), target);

        assertThat(result.skipped()).isTrue();
        assertThat(result.sampleEndDate()).isEqualTo(lastQuote);
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
        verify(analysisClient, never()).marketDailyQuotes(any(), any(), any(), anyString());
        verify(quoteService, never()).persistBatch(any(), any(), anyString(), any(), any());
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
        when(quoteMapper.latestTradeDate(21L, "NONE")).thenReturn(published);
        when(productMapper.selectById(21L)).thenReturn(product);
        var job = new InvestmentDataJob();
        job.setProductId(21L);
        job.setJobType("FUND_NAV_HISTORY");

        assertThat(service.prepare(job).skipped()).isTrue();
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
        verify(analysisClient, never()).marketDailyQuotes(any(), any(), any(), anyString());
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
    void usDemandCanFinishOnItsOwnCalendarDuringAChinaHoliday() {
        var product = product(); product.setMarket("NASDAQ");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        service = new InvestmentHistoryPreparationService(productMapper,quoteMapper,dataQualityService,quoteService,
                classificationService,analysisClient,Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"),SHANGHAI));
        var start = LocalDate.of(2026, 10, 1); var end = start.plusDays(1);
        when(analysisClient.quoteAvailability(eq(product), eq(start), eq(end), any())).thenReturn(Map.of(
                "calendarKnown", true, "expectedThroughDate", end.toString(), "targetDate", end.toString(),
                "expectedDates", List.of("2026-10-01", "2026-10-02"), "ruleProfile", "US_CLOSE", "ruleVersion", "quote-availability-v1"));
        assertThat(service.prepareDemandProduct(product, "STOCK_HISTORY", true, start, end).checkedThrough()).isEqualTo(end);
        assertThat(service.supportsDemandPreparation(product)).isFalse();
        verify(analysisClient, never()).aShareTradingDates(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void sourceSuccessfulSparseUsWindowReceivesCollectionProgress() {
        var product = product(); product.setMarket("NASDAQ");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        var start = LocalDate.of(2026, 7, 20); var end = start.plusDays(2);
        when(analysisClient.quoteAvailability(eq(product), eq(start), eq(end), any())).thenReturn(Map.of(
                "calendarKnown", true, "expectedThroughDate", end.toString(), "targetDate", end.toString(),
                "expectedDates", List.of("2026-07-20", "2026-07-21", "2026-07-22"), "ruleProfile", "US_CLOSE", "ruleVersion", "quote-availability-v1"));
        assertThat(service.prepareDemandProduct(product, "STOCK_HISTORY", true, start, end).checkedThrough()).isEqualTo(end);
    }

    @Test
    void foreignFundClassificationDoesNotEnableDomesticWorkbenchPreparation() {
        var product = product(); product.setProductType("MUTUAL_FUND"); product.setMarket("HKEX"); product.setFundCategory("INDEX_FUND");
        assertThat(service.supportsDemandPreparation(product)).isFalse();
    }

    @Test
    void collectionCompletionDoesNotClaimDailyObservationCompleteness() {
        var product=product();
        LocalDate start=LocalDate.of(2026,7,20),end=LocalDate.of(2026,7,24);
        when(dataQualityService.resolve(any(),any(),any(),anyString(),eq(true)))
                .thenReturn(evaluation(end,end));
        policyCalendar(start,end,List.of(start.toString(),start.plusDays(1).toString(),end.toString()));
        var result=service.prepareDemandProduct(product,"STOCK_HISTORY",true,start,end);
        assertThat(result.checkedThrough()).isEqualTo(end);
    }
    @Test
    void publishedFundWindowCanFinishOnAConfirmedClosedDay() {
        var product=product();product.setProductType("MUTUAL_FUND");product.setMarket("FUND_CN");product.setFundCategory("INDEX_FUND");
        when(dataQualityService.adjustType(any())).thenReturn("NONE");
        LocalDate friday=LocalDate.of(2026,7,24),sunday=friday.plusDays(2);
        var availability=mock(InvestmentQuoteAvailabilityService.class);
        when(availability.target(any(),any(),any())).thenReturn(friday);
        service.setAvailability(availability);
        var response=new java.util.LinkedHashMap<>(evaluation(friday,friday).response());
        response.put("records",List.of(Map.of("data_date",friday.toString(),"nav",1.2,"total_return_index",1.2)));
        var evaluated=new InvestmentDataQualityService.Evaluation(evaluation(friday,friday).snapshot(),response,List.of(),List.of());
        when(dataQualityService.resolve(any(),any(),any(),anyString(),eq(true))).thenReturn(evaluated);
        policyCalendar(friday,sunday,List.of(friday.toString()));
        assertThat(service.prepareDemandProduct(product,"FUND_NAV_HISTORY",true,friday,sunday).checkedThrough()).isEqualTo(sunday);
        verify(dataQualityService).resolve(product,friday,sunday,"NONE",true);
    }

    @Test
    void explicitWindowQueriesTheOriginalTargetDespitePublicationEstimate() {
        var product=product();product.setProductType("MUTUAL_FUND");product.setMarket("FUND_CN");product.setFundCategory("INDEX_FUND");
        when(dataQualityService.adjustType(any())).thenReturn("NONE");
        var monday=LocalDate.of(2026,7,27); var tuesday=monday.plusDays(1);
        var availability=mock(InvestmentQuoteAvailabilityService.class);
        when(availability.target(any(),any(),any())).thenReturn(monday);
        service.setAvailability(availability);
        when(analysisClient.quoteAvailability(eq(product),eq(monday),eq(tuesday),any()))
                .thenReturn(calendarResponse(monday,List.of(monday.toString())));

        assertThat(service.prepareDemandProduct(product,"FUND_NAV_HISTORY",true,monday,tuesday).checkedThrough()).isEqualTo(tuesday);
        verify(dataQualityService).resolve(product,monday,tuesday,"NONE",true);
    }

    @Test
    void knownClosedWindowSkipsButUnknownFundStillQueries() {
        var product=product();product.setProductType("MUTUAL_FUND");product.setMarket("FUND_CN");product.setFundCategory("INDEX_FUND");
        when(dataQualityService.adjustType(any())).thenReturn("NONE");
        var friday=LocalDate.of(2026,7,24); var saturday=friday.plusDays(1); var sunday=friday.plusDays(2);
        when(quoteMapper.latestTradeDate(21L, "NONE")).thenReturn(friday);
        var availability=mock(InvestmentQuoteAvailabilityService.class);
        when(availability.target(any(),any(),any())).thenReturn(friday);
        service.setAvailability(availability);
        when(analysisClient.quoteAvailability(eq(product),eq(saturday),eq(sunday),any())).thenAnswer(call ->
                "INDEX_FUND".equals(product.getFundCategory()) ? calendarResponse(sunday,List.of())
                        : Map.of("calendarKnown",false,"expectedDates",List.of()));

        assertThat(service.prepareDemandProduct(product,"FUND_NAV_HISTORY",false,saturday,sunday).checkedThrough()).isEqualTo(sunday);
        product.setFundCategory("QDII_FUND");
        assertThat(service.prepareDemandProduct(product,"FUND_NAV_HISTORY",false,saturday,sunday).checkedThrough()).isEqualTo(sunday);
        verify(dataQualityService).resolve(product,saturday,sunday,"NONE",true);
        verify(analysisClient,never()).marketDailyQuotes(any(),any(),any(),anyString());
    }

    @Test
    void unknownFundCollectionCompletesWithoutCalendarProof() {
        var product=product();product.setProductType("MUTUAL_FUND");product.setMarket("FUND_CN");product.setFundCategory(null);
        assertThat(service.supportsDemandPreparation(product)).isTrue();
        when(dataQualityService.adjustType(any())).thenReturn("NONE");
        LocalDate friday=LocalDate.of(2026,7,24),sunday=friday.plusDays(2);
        var response=new java.util.LinkedHashMap<>(evaluation(friday,friday).response());
        response.put("records",List.of(Map.of("data_date",friday.toString(),"nav",1.2,"total_return_index",1.2)));
        when(dataQualityService.resolve(any(),any(),any(),anyString(),eq(true))).thenReturn(
                new InvestmentDataQualityService.Evaluation(evaluation(friday,friday).snapshot(),response,List.of(),List.of()));
        when(analysisClient.quoteAvailability(eq(product),eq(friday),eq(sunday),any())).thenAnswer(call ->
                "INDEX_FUND".equals(product.getFundCategory()) ? calendarResponse(sunday,List.of(friday.toString()))
                        : Map.of("calendarKnown",false,"expectedDates",List.of(),"targetDate",friday.toString(),"ruleProfile","UNKNOWN","ruleVersion","quote-availability-v1"));
        assertThat(service.prepareDemandProduct(product,"FUND_NAV_HISTORY",true,friday,sunday).checkedThrough()).isEqualTo(sunday);
        when(classificationService.enrichIfMissing(product)).thenAnswer(call->{product.setFundCategory("INDEX_FUND");return product;});
        assertThat(service.prepareDemandProduct(product,"FUND_NAV_HISTORY",true,friday,sunday).checkedThrough()).isEqualTo(sunday);
        verify(classificationService,org.mockito.Mockito.times(2)).enrichIfMissing(product);
    }
    @Test
    void emptyFundQueryCompletesForKnownAndUnknownPublicationSchedules() {
        var product=product();product.setProductType("MUTUAL_FUND");product.setMarket("FUND_CN");product.setFundCategory("INDEX_FUND");
        when(dataQualityService.adjustType(any())).thenReturn("NONE");
        LocalDate friday=LocalDate.of(2026,7,24),sunday=friday.plusDays(2);
        when(quoteMapper.latestTradeDate(21L, "NONE")).thenReturn(friday);
        when(analysisClient.quoteAvailability(eq(product),eq(friday.plusDays(1)),eq(sunday),any())).thenAnswer(call ->
                "INDEX_FUND".equals(product.getFundCategory()) ? calendarResponse(sunday,List.of())
                        : Map.of("calendarKnown",false,"expectedDates",List.of(),"targetDate",friday.toString(),"ruleProfile","UNKNOWN","ruleVersion","quote-availability-v1"));
        org.mockito.Mockito.doThrow(new AnalysisServiceClient.SourceEmptyException("not published"))
                .when(dataQualityService).resolve(any(),any(),any(),anyString(),eq(true));
        assertThat(service.prepareDemandProduct(product,"FUND_NAV_HISTORY",false,friday.plusDays(1),sunday).checkedThrough()).isEqualTo(sunday);
        product.setFundCategory("QDII_FUND");
        assertThat(service.prepareDemandProduct(product,"FUND_NAV_HISTORY",false,friday.plusDays(1),sunday).checkedThrough()).isEqualTo(sunday);
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
        policyCalendar(lastQuote.plusDays(1),target,List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("price source unavailable"))
                .when(analysisClient).marketDailyQuotes(any(), any(), any(), anyString());

        var result = service.prepareProduct(product, "STOCK_HISTORY", false, lastQuote.plusDays(1), target);

        assertThat(result.skipped()).isTrue();
        assertThat(result.sampleEndDate()).isEqualTo(lastQuote);
        verify(analysisClient, never()).marketDailyQuotes(any(), any(), any(), anyString());
        verify(quoteService, never()).persistBatch(any(), any(), anyString(), any(), any());
    }

    @Test
    void truncatedCalendarCannotDeclareTheUncoveredWindowClosed() {
        InvestmentProduct product = product();
        LocalDate lastQuote = LocalDate.of(2026, 9, 30);
        LocalDate target = LocalDate.of(2026, 10, 4);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(lastQuote);
        when(analysisClient.quoteAvailability(any(),any(),any(),any())).thenReturn(calendarResponse(lastQuote,List.of()));
        org.mockito.Mockito.doThrow(new IllegalStateException("price source unavailable"))
                .when(dataQualityService).resolve(any(), any(), any(), anyString(), anyBoolean());

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false, lastQuote.plusDays(1), target))
                .hasMessage("price source unavailable");
        verify(quoteService, never()).persistBatch(any(), any(), anyString(), any(), any());
    }

    @Test
    void calendarOutageDoesNotBlockRealPriceAcquisition() {
        InvestmentProduct product = product();
        LocalDate lastQuote = TODAY.minusDays(2);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(lastQuote);
        when(analysisClient.quoteAvailability(any(),any(),any(),any())).thenThrow(new IllegalStateException("calendar unavailable"));

        var result = service.prepareProduct(product, "STOCK_HISTORY", false, lastQuote.plusDays(1), TODAY);

        assertThat(result.skipped()).isFalse();
        verify(quoteService, org.mockito.Mockito.times(2)).persistBatch(eq(product), any(), anyString(), any(), any());
    }

    @Test
    void closedChinaExchangeDoesNotSuppressOverseasFundNav() {
        InvestmentProduct product = product();
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(quoteMapper.latestTradeDate(21L, "NONE")).thenReturn(TODAY.minusDays(2));

        var result = service.prepareProduct(product, "FUND_NAV_HISTORY", false, TODAY.minusDays(1), TODAY);

        assertThat(result.skipped()).isFalse();
        verify(analysisClient, never()).aShareTradingDates(org.mockito.ArgumentMatchers.anyInt());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void chinaCalendarDoesNotSuppressUsStockAcquisition() {
        InvestmentProduct product = product();
        product.setMarket("NASDAQ");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(quoteMapper.latestTradeDate(21L, "NONE")).thenReturn(TODAY.minusDays(2));

        var result = service.prepareProduct(product, "STOCK_HISTORY", false, TODAY.minusDays(1), TODAY);

        assertThat(result.skipped()).isFalse();
        assertThat(result.datasetVersion()).isEqualTo("dataset-v1");
        verify(dataQualityService).resolve(product, TODAY.minusDays(1), TODAY, "NONE", true);
        verify(dataQualityService).claim(any());
        verify(analysisClient, never()).marketDailyQuotes(any(), any(), any(), anyString());
        verify(analysisClient, never()).aShareTradingDates(org.mockito.ArgumentMatchers.anyInt());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void explicitDemandRefreshPersistsSameDayCorrectionsWithoutResettingHistoricalCoverage() {
        InvestmentProduct product = product();
        product.setMarket("NASDAQ");
        product.setHistoryStartDate(TODAY.minusYears(3));
        product.setHistoryEndDate(TODAY);
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(quoteMapper.latestTradeDate(21L, "NONE")).thenReturn(TODAY);

        var result = service.prepareDemandProduct(product, "STOCK_HISTORY", false, TODAY.minusDays(3), TODAY);

        assertThat(result.skipped()).isFalse();
        assertThat(result.datasetVersion()).isEqualTo("dataset-v1");
        assertThat(product.getHistoryStartDate()).isEqualTo(TODAY.minusYears(3));
        assertThat(product.getHistoryEndDate()).isEqualTo(TODAY);
        verify(dataQualityService).claim(any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void unchangedQfqAnchorIsUpsertedWithTheFetchedIncrement() {
        InvestmentProduct product = product();
        LocalDate anchorDay = LocalDate.of(2026, 7, 28);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(anchorDay);
        ProductDailyQuote anchor = new ProductDailyQuote(); anchor.setDataContractVersion(MarketDataContract.forProduct("PRICE",product.getProductType()).version());
        anchor.setTradeDate(anchorDay);
        anchor.setClosePrice(java.math.BigDecimal.ONE);
        when(quoteMapper.selectOne(any())).thenReturn(anchor);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.requestedStartDate()).isEqualTo(anchorDay.plusDays(1));
        verify(dataQualityService).resolve(product, anchorDay, TODAY, "QFQ", true);
        verify(dataQualityService).resolve(product, anchorDay.plusDays(1), TODAY, "NONE", true);
        var written = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(quoteService).persistBatch(eq(product), written.capture(), eq("QFQ"), any(), any());
        assertThat(InvestmentHistoryPreparationService.recordDates(written.getValue()))
                .contains(anchorDay, TODAY);
    }

    @Test
    void realFundSnapshotShapeUsesNavTimesFactorForIncrementalBasis() {
        var product=product();product.setProductType("MUTUAL_FUND");product.setMarket("FUND_CN");
        LocalDate last=TODAY.minusDays(2);when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(quoteMapper.latestTradeDate(21L,"NONE")).thenReturn(last);
        var anchor=new ProductDailyQuote(); anchor.setDataContractVersion(MarketDataContract.forProduct("PRICE",product.getProductType()).version());anchor.setTradeDate(last);anchor.setClosePrice(new java.math.BigDecimal("1.2"));anchor.setTotalReturnIndex(new java.math.BigDecimal("120"));
        when(quoteMapper.selectOne(any())).thenReturn(anchor);
        var e=evaluation(last,TODAY);var response=new java.util.LinkedHashMap<>(e.response());
        response.put("records",List.of(Map.of("data_date",last.toString(),"nav","1.2","close","1.2","adjustment_factor","100"),
                Map.of("data_date",TODAY.toString(),"nav","1.3","close","1.3","adjustment_factor","100")));
        when(dataQualityService.resolve(product,last,TODAY,"NONE",true)).thenReturn(new InvestmentDataQualityService.Evaluation(e.snapshot(),response,List.of(),List.of()));
        var result=service.prepareProduct(product,"FUND_NAV_HISTORY",false);
        assertThat(result.requestedStartDate()).isEqualTo(last.plusDays(1));
        verify(dataQualityService,org.mockito.Mockito.times(1)).resolve(any(),any(),any(),anyString(),eq(true));
        var saved=org.mockito.ArgumentCaptor.forClass(Map.class);verify(quoteService).persistBatch(eq(product), saved.capture(), eq("NONE"), any(), any());
        assertThat(InvestmentHistoryPreparationService.recordDates(saved.getValue())).contains(last, TODAY);
    }

    @Test
    void changedFundReturnBasisReusesExistingOverlapAndHistoryRebuild() {
        var product=product(); product.setProductType("MUTUAL_FUND"); product.setMarket("FUND_CN");
        LocalDate origin=TODAY.minusDays(20),last=TODAY.minusDays(2); product.setInceptionDate(origin);
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(quoteMapper.latestTradeDate(21L,"NONE")).thenReturn(last);
        var anchor=new ProductDailyQuote(); anchor.setDataContractVersion(MarketDataContract.forProduct("PRICE",product.getProductType()).version());anchor.setTradeDate(last);anchor.setClosePrice(java.math.BigDecimal.ONE);
        anchor.setTotalReturnIndex(java.math.BigDecimal.TEN);
        when(quoteMapper.selectOne(any())).thenReturn(anchor); when(quoteMapper.selectList(any())).thenReturn(List.of(anchor));
        var original=evaluation(origin,TODAY);
        var response=new java.util.LinkedHashMap<>(original.response());
        response.put("records",List.of(Map.of("data_date",origin.toString(),"close",1,"total_return_index",100),
            Map.of("data_date",last.toString(),"close",1,"total_return_index",100),
            Map.of("data_date",TODAY.toString(),"close",2,"total_return_index",200)));
        when(dataQualityService.resolve(product,origin,TODAY,"NONE",true)).thenReturn(
            new InvestmentDataQualityService.Evaluation(original.snapshot(),response,List.of(),List.of()));
        var result=service.prepareProduct(product,"FUND_NAV_HISTORY",false);
        assertThat(result.requestedStartDate()).isEqualTo(origin);
        verify(dataQualityService).resolve(product,last,TODAY,"NONE",true);
        verify(dataQualityService).resolve(product,origin,TODAY,"NONE",true);
        verify(quoteService).persistBatch(eq(product), eq(response), eq("NONE"), any(), any());
    }

    @Test
    void emptyAdjustedRebuildIsFailureAndKeepsOldQuotes() {
        var product=product();LocalDate origin=TODAY.minusDays(20),last=TODAY.minusDays(2);product.setListingDate(origin);
        when(quoteMapper.latestTradeDate(21L,"QFQ")).thenReturn(last);
        var anchor=new ProductDailyQuote(); anchor.setDataContractVersion(MarketDataContract.forProduct("PRICE",product.getProductType()).version());anchor.setTradeDate(last);anchor.setClosePrice(java.math.BigDecimal.TEN);
        when(quoteMapper.selectOne(any())).thenReturn(anchor);
        when(dataQualityService.resolve(product,origin,TODAY,"QFQ",true)).thenThrow(new AnalysisServiceClient.SourceEmptyException("no rebuilt rows"));
        assertThatThrownBy(()->service.prepareProduct(product,"STOCK_HISTORY",false)).hasMessageContaining("重建");
        verify(quoteService,never()).persistBatch(any(), any(), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
        verify(productMapper,never()).updateById(any());
    }

    @Test
    void optionalFundMetadataOutageDoesNotBlockValidNavAcquisition() {
        var product=product();product.setProductType("MUTUAL_FUND");product.setMarket("FUND_CN");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(classificationService.enrichIfMissing(product)).thenThrow(new IllegalStateException("profile unavailable"));
        when(analysisClient.resolveProduct(anyString(),anyString())).thenThrow(new IllegalStateException("metadata unavailable"));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"historyYears",5);
        var result=service.prepareProduct(product,"FUND_NAV_HISTORY",false);
        assertThat(result.checkedThrough()).isEqualTo(TODAY);
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void fundBasisRebuildCannotPreserveOldReturnsAgainstANewNavBasis() {
        var product=product();product.setProductType("MUTUAL_FUND");product.setMarket("FUND_CN");
        LocalDate origin=TODAY.minusDays(20),last=TODAY.minusDays(2);product.setInceptionDate(origin);
        when(dataQualityService.adjustType(product)).thenReturn("NONE");when(quoteMapper.latestTradeDate(21L,"NONE")).thenReturn(last);
        var anchor=new ProductDailyQuote(); anchor.setDataContractVersion(MarketDataContract.forProduct("PRICE",product.getProductType()).version());anchor.setTradeDate(last);anchor.setClosePrice(java.math.BigDecimal.TEN);anchor.setTotalReturnIndex(java.math.BigDecimal.TEN);
        when(quoteMapper.selectOne(any())).thenReturn(anchor);when(quoteMapper.selectList(any())).thenReturn(List.of(anchor));
        var e=evaluation(origin,TODAY);var response=new java.util.LinkedHashMap<>(e.response());
        response.put("records",List.of(Map.of("data_date",origin.toString(),"close",1),Map.of("data_date",last.toString(),"close",1),Map.of("data_date",TODAY.toString(),"close",2)));
        when(dataQualityService.resolve(product,origin,TODAY,"NONE",true)).thenReturn(new InvestmentDataQualityService.Evaluation(e.snapshot(),response,List.of(),List.of()));
        assertThatThrownBy(()->service.prepareProduct(product,"FUND_NAV_HISTORY",false)).hasMessageContaining("收益");
        verify(quoteService,never()).persistBatch(any(), any(), anyString(), any(), any());
    }

    @Test
    void missingOriginMetadataDoesNotPreventSourceAcquisition() {
        var product=product();product.setMarket("NASDAQ");when(dataQualityService.adjustType(product)).thenReturn("NONE");
        org.springframework.test.util.ReflectionTestUtils.setField(service,"historyYears",5);
        var result=service.prepareProduct(product,"STOCK_HISTORY",false);
        assertThat(result.requestedStartDate()).isEqualTo(TODAY.minusYears(5));
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void changedQfqAnchorRebuildsBothSeriesInsteadOfSplicingAdjustmentBases() {
        InvestmentProduct product = product();
        LocalDate origin = LocalDate.of(2001, 8, 27);
        LocalDate anchorDay = LocalDate.of(2026, 7, 28);
        product.setListingDate(origin);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(anchorDay);
        ProductDailyQuote anchor = new ProductDailyQuote(); anchor.setDataContractVersion(MarketDataContract.forProduct("PRICE",product.getProductType()).version());
        anchor.setTradeDate(anchorDay);
        anchor.setClosePrice(java.math.BigDecimal.TEN);
        when(quoteMapper.selectOne(any())).thenReturn(anchor);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.requestedStartDate()).isEqualTo(origin);
        verify(dataQualityService).resolve(product, anchorDay, TODAY, "QFQ", true);
        verify(dataQualityService).resolve(product, origin, TODAY, "QFQ", true);
        verify(dataQualityService).resolve(product, origin, TODAY, "NONE", true);
        verify(quoteService, org.mockito.Mockito.times(2)).persistBatch(eq(product), any(), anyString(), any(), any());
        assertThat(product.getHistoryStartDate()).isEqualTo(origin);
    }

    @Test
    void forcedHistoricalWindowStillChecksTheExistingAdjustmentBasis() {
        var product=product();LocalDate origin=LocalDate.of(2026,7,1),start=origin.plusDays(9),end=start.plusDays(2),last=TODAY.minusDays(1);
        product.setListingDate(origin);
        when(quoteMapper.latestTradeDate(21L,"QFQ")).thenReturn(last);
        var anchor=new ProductDailyQuote(); anchor.setDataContractVersion(MarketDataContract.forProduct("PRICE",product.getProductType()).version());anchor.setTradeDate(last);anchor.setClosePrice(java.math.BigDecimal.TEN);
        when(quoteMapper.selectOne(any())).thenReturn(null,anchor);
        when(quoteMapper.selectList(any())).thenReturn(List.of(anchor));
        var response=new java.util.LinkedHashMap<String,Object>(evaluation(origin,last).response());
        response.put("records",List.of(Map.of("data_date",origin.toString(),"close",1),Map.of("data_date",last.toString(),"close",1)));
        when(dataQualityService.resolve(eq(product),eq(origin),eq(last),anyString(),eq(true))).thenReturn(new InvestmentDataQualityService.Evaluation(evaluation(origin,last).snapshot(),response,List.of(),List.of()));
        var result=service.prepareProduct(product,"STOCK_HISTORY",true,start,end);
        assertThat(result.requestedStartDate()).isEqualTo(origin);
        verify(dataQualityService).resolve(product,start,last,"QFQ", true);
        verify(dataQualityService).resolve(product,origin,last,"QFQ", true);
        verify(dataQualityService).resolve(product,origin,last,"NONE", true);
    }

    @Test
    void changedQfqBasisRetainsSignedHistoryWithoutRequiringMatchingRawDates() {
        var product=product();var origin=LocalDate.of(2002,8,26);var anchorDay=TODAY.minusDays(2);
        product.setListingDate(origin);
        when(quoteMapper.latestTradeDate(21L,"QFQ")).thenReturn(anchorDay);
        var anchor=new ProductDailyQuote(); anchor.setDataContractVersion(MarketDataContract.forProduct("PRICE",product.getProductType()).version());anchor.setTradeDate(anchorDay);anchor.setClosePrice(java.math.BigDecimal.TEN);
        when(quoteMapper.selectOne(any())).thenReturn(anchor);when(quoteMapper.selectList(any())).thenReturn(List.of(anchor));
        var base=evaluation(origin,TODAY);
        var signed=List.<Map<String,Object>>of(Map.of("data_date",origin.toString(),"close",-314),
                Map.of("data_date",anchorDay.toString(),"close",1),Map.of("data_date",TODAY.toString(),"close",2));
        var response=new java.util.LinkedHashMap<String,Object>(base.response());response.put("records",signed);
        when(dataQualityService.resolve(product,origin,TODAY,"QFQ",true))
                .thenReturn(new InvestmentDataQualityService.Evaluation(base.snapshot(),response,signed,List.of()));
        var raw=signed.stream().map(row->Map.<String,Object>of("data_date",row.get("data_date"),"close",10)).toList();
        var rawResponse=new java.util.LinkedHashMap<String,Object>(base.response());rawResponse.put("records",raw);
        when(dataQualityService.resolve(product,origin,TODAY,"NONE",true))
                .thenReturn(new InvestmentDataQualityService.Evaluation(base.snapshot(),rawResponse,raw,List.of()));
        var result=service.prepareProduct(product,"STOCK_HISTORY",false);
        assertThat(result.requestedStartDate()).isEqualTo(origin);
        verify(quoteService).persistBatch(eq(product), eq(response), eq("QFQ"), any(), any());verify(quoteService).persistBatch(eq(product), eq(rawResponse), eq("NONE"), any(), any());
        var missing=raw.subList(1,raw.size());rawResponse.put("records",missing);
        when(dataQualityService.resolve(product,origin,TODAY,"NONE",true))
                .thenReturn(new InvestmentDataQualityService.Evaluation(base.snapshot(),rawResponse,missing,List.of()));
        org.mockito.Mockito.clearInvocations(quoteService);
        service.prepareProduct(product,"STOCK_HISTORY",true,origin,TODAY);
        verify(quoteService).persistBatch(eq(product), eq(response), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), eq(rawResponse), eq("NONE"), any(), any());
    }

    @Test
    void basisRebuildMissingAnExistingObservationPreservesAdjustedPricesAndStoresValidRawPrices() {
        var product=product();LocalDate origin=LocalDate.of(2026,7,1),start=origin.plusDays(9),end=start.plusDays(2),last=TODAY.minusDays(1);
        product.setListingDate(origin);
        when(quoteMapper.latestTradeDate(21L,"QFQ")).thenReturn(last);
        var anchor=new ProductDailyQuote(); anchor.setDataContractVersion(MarketDataContract.forProduct("PRICE",product.getProductType()).version());anchor.setTradeDate(last);anchor.setClosePrice(java.math.BigDecimal.TEN);
        var existing=new ProductDailyQuote(); existing.setDataContractVersion(MarketDataContract.forProduct("PRICE",product.getProductType()).version());existing.setTradeDate(origin.plusDays(1));
        when(quoteMapper.selectOne(any())).thenReturn(null,anchor);
        when(quoteMapper.selectList(any())).thenReturn(List.of(anchor,existing));
        assertThatThrownBy(()->service.prepareProduct(product,"STOCK_HISTORY",true,start,end)).hasMessageContaining("未覆盖已有日期");
        verify(quoteService,never()).persistBatch(any(), any(), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
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
        verify(quoteService).persistBatch(eq(product), any(), eq("QFQ"), any(), any());
        assertThat(product.getHistoryEndDate()).isEqualTo(LocalDate.of(2026, 7, 29));
    }

    @Test
    void localSeriesAlreadyCurrentDoesNotCallTheProvider() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(TODAY);

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.skipped()).isTrue();
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
        verify(quoteService, never()).persistBatch(any(), any(), anyString(), any(), any());
    }

    @Test
    void providerHasNothingInIncrementalWindowIsSkippedNotFailed() {
        InvestmentProduct product = product();
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
        assertThat(product.getHistoryEndDate()).isEqualTo(LocalDate.of(2026, 7, 28));
        verify(quoteService, never()).persistBatch(any(), any(), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void explicitRepairReportsNormalEmptyAndPreservesExistingData() {
        InvestmentProduct product = product();
        product.setInceptionDate(LocalDate.of(2020, 1, 2));
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenThrow(new AnalysisServiceClient.SourceEmptyException("AKSHARE: no records"));

        var result = service.prepareProduct(product, "STOCK_HISTORY", true);
        assertThat(result.checkedThrough()).isEqualTo(TODAY);
        assertThat(result.skipped()).isTrue();
        verify(quoteService, never()).persistBatch(any(), any(), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
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
    void blockedQualityStopsOnlyTheInvalidSeriesBeforePersistence() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        InvestmentDataQualityService.Evaluation blocked = evaluation(
                LocalDate.of(2026, 7, 29), LocalDate.of(2026, 7, 29));
        blocked.snapshot().setDecision("BLOCK");
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true))).thenReturn(blocked);

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(InvestmentHistoryPreparationService.QualityBlockedException.class);
        verify(quoteService, never()).persistBatch(any(), any(), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void outOfWindowRowsAreFailuresAndNeverOverwriteExistingHistory() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenReturn(evaluation(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 7, 28)));

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("区间之外");
        verify(quoteService, never()).persistBatch(any(), any(), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
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
        verify(quoteService, never()).persistBatch(any(), any(), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
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
        verify(quoteService).persistBatch(eq(product), any(), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
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
    void successfulPriceSeriesAreStoredEvenWhenTheirDatesDiffer() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        when(dataQualityService.resolve(any(), any(), any(), eq("NONE"), eq(true)))
                .thenReturn(evaluation(LocalDate.of(2026, 7, 29), LocalDate.of(2026, 7, 29)));

        service.prepareProduct(product, "STOCK_HISTORY", false);
        verify(quoteService).persistBatch(eq(product), any(), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void rawQualityBlockPreservesTheSuccessfullyStoredResearchSeries() {
        InvestmentProduct product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 28));
        InvestmentDataQualityService.Evaluation blocked = evaluation(LocalDate.of(2026, 7, 29), TODAY);
        blocked.snapshot().setDecision("BLOCK");
        when(dataQualityService.resolve(any(), any(), any(), eq("NONE"), eq(true))).thenReturn(blocked);

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(InvestmentHistoryPreparationService.QualityBlockedException.class);
        verify(quoteService).persistBatch(eq(product), any(), eq("QFQ"), any(), any());
        verify(quoteService, never()).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void fetchedOverlapCorrectionsAreStoredWithTheNewObservation() {
        var product = product();
        var anchorDay = TODAY.minusDays(2);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(anchorDay);
        var anchor = new ProductDailyQuote(); anchor.setDataContractVersion(MarketDataContract.forProduct("PRICE",product.getProductType()).version());
        anchor.setTradeDate(anchorDay); anchor.setClosePrice(java.math.BigDecimal.ONE);
        when(quoteMapper.selectOne(any())).thenReturn(anchor);

        service.prepareProduct(product, "STOCK_HISTORY", false);

        var written = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(quoteService).persistBatch(eq(product), written.capture(), eq("QFQ"), any(), any());
        assertThat(InvestmentHistoryPreparationService.recordDates(written.getValue())).contains(anchorDay, TODAY);
    }

    @Test
    void aFailedPrimaryWriteCannotPublishAPreparedReceipt() {
        var product = product();
        product.setListingDate(TODAY.minusDays(10));
        org.mockito.Mockito.doThrow(new IllegalStateException("storage failed"))
                .when(quoteService).persistBatch(any(), any(), anyString(), any(), any());

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .hasMessageContaining("storage failed");
        verify(dataQualityService, never()).persistPrepared(any());
    }

    @Test
    void eachPreparedReceiptIsPublishedOnlyAfterItsOwnQuoteWrite() {
        var product = product();
        product.setListingDate(TODAY.minusDays(10));
        service.prepareProduct(product, "STOCK_HISTORY", false);

        var order = org.mockito.Mockito.inOrder(quoteService, dataQualityService);
        order.verify(quoteService).persistBatch(eq(product), any(), eq("QFQ"), any(), any());
        order.verify(dataQualityService).persistPrepared(any());
        order.verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
        order.verify(dataQualityService).persistPrepared(any());
    }

    @Test
    void aSuccessfullyRefetchedDateUpdatesExistingPricesWithoutNeedingANewerDate() {
        var product = product(); product.setMarket("NASDAQ");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(quoteMapper.latestTradeDate(21L, "NONE")).thenReturn(TODAY);

        var result = service.prepareProduct(product, "STOCK_HISTORY", false, TODAY, TODAY);

        assertThat(result.skipped()).isFalse();
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void anEmptyResearchResponseStillCollectsAvailableRawPrices() {
        var product = product(); product.setListingDate(TODAY.minusDays(10));
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenThrow(new AnalysisServiceClient.SourceEmptyException("research response empty"));

        var result = service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.skipped()).isTrue();
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
        verify(quoteService, never()).persistBatch(eq(product), any(), eq("QFQ"), any(), any());
    }

    @Test
    void aFailedResearchRequestStillStoresAvailableRawPricesAndReportsTheFailure() {
        var product = product(); product.setListingDate(TODAY.minusDays(10));
        when(dataQualityService.resolve(any(), any(), any(), eq("QFQ"), eq(true)))
                .thenThrow(new IllegalStateException("research transport timeout"));

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .hasMessageContaining("research transport timeout");
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
        verify(quoteService, never()).persistBatch(eq(product), any(), eq("QFQ"), any(), any());
    }

    @Test
    void aNormalEmptyRawResponseKeepsTheStoredResearchSeriesWithoutInventingRawPrices() {
        var product = product(); product.setListingDate(TODAY.minusDays(10));
        when(dataQualityService.resolve(any(), any(), any(), eq("NONE"), eq(true)))
                .thenThrow(new AnalysisServiceClient.SourceEmptyException("raw response contains no rows"));

        var result = service.prepareProduct(product, "STOCK_HISTORY", false);

        assertThat(result.skipped()).isFalse();
        verify(quoteService).persistBatch(eq(product), any(), eq("QFQ"), any(), any());
        verify(quoteService, never()).persistBatch(eq(product), any(), eq("NONE"), any(), any());
        verify(dataQualityService).persistPrepared(any());
    }

    @Test
    void rawRequestFailureDoesNotDiscardSuccessfullyFetchedResearchPrices() {
        var product = product();
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(TODAY.minusDays(2));
        when(dataQualityService.resolve(any(), any(), any(), eq("NONE"), eq(true)))
                .thenThrow(new IllegalStateException("raw transport timeout"));

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false))
                .hasMessageContaining("raw transport timeout");
        verify(quoteService).persistBatch(eq(product), any(), eq("QFQ"), any(), any());
        verify(quoteService, never()).persistBatch(eq(product), any(), eq("NONE"), any(), any());
    }

    @Test
    void secondSeriesWriteFailureRollsBackOnlyTheFailingSeries() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("history-atomic.db").toAbsolutePath(), "", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE prepared_quotes (adjust_type TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE prepared_product (id INTEGER PRIMARY KEY, history_end_date TEXT)");
        jdbc.update("INSERT INTO prepared_product(id) VALUES (21)");
        doAnswer(invocation -> {
            String adjustment = invocation.getArgument(2);
            jdbc.update("INSERT INTO prepared_quotes(adjust_type) VALUES (?)", adjustment);
            if ("NONE".equals(adjustment)) throw new IllegalStateException("raw persistence failed");
            return null;
        }).when(quoteService).persistBatch(any(), any(), anyString(), any(), any());
        when(productMapper.updateById(any())).thenAnswer(invocation -> {
            InvestmentProduct product = invocation.getArgument(0);
            return jdbc.update("UPDATE prepared_product SET history_end_date=? WHERE id=?",
                    product.getHistoryEndDate(), product.getId());
        });
        InvestmentProduct product = product();
        product.setListingDate(LocalDate.of(2001, 8, 27));
        Clock clock = Clock.fixed(Instant.parse("2026-07-30T02:00:00Z"), SHANGHAI);
        InvestmentHistoryPreparationService transactionalService = new InvestmentHistoryPreparationService(
                productMapper, quoteMapper, dataQualityService, quoteService, classificationService, analysisClient,
                clock, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));

        assertThatThrownBy(() -> transactionalService.prepareProduct(product, "STOCK_HISTORY", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("raw persistence failed");

        assertThat(jdbc.queryForList("SELECT adjust_type FROM prepared_quotes", String.class)).containsExactly("QFQ");
        assertThat(jdbc.queryForObject("SELECT history_end_date FROM prepared_product WHERE id=21", String.class))
                .isEqualTo(TODAY.toString());
        verify(quoteService).persistBatch(eq(product), any(), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
        verify(productMapper).updateById(any());
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
        assertThat(result.datasetVersion()).isEqualTo("dataset-v1");
        verify(dataQualityService).resolve(product, start, target, "QFQ", true);
        verify(dataQualityService).resolve(product, start, target, "NONE", true);
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
        when(dataQualityService.resolve(product, placeholderStart, TODAY, "NONE", true)).thenReturn(new InvestmentDataQualityService.Evaluation(evaluation(placeholderStart, TODAY).snapshot(), response, List.of(), List.of()));

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "FUND_NAV_HISTORY", false, placeholderStart, TODAY);

        assertThat(result.requestedStartDate()).isEqualTo(placeholderStart);
        assertThat(result.sampleStartDate()).isEqualTo(actualStart);
        assertThat(product.getHistoryStartDate()).isEqualTo(actualStart);
        assertThat(product.getInceptionDate()).isNull();
        assertThat(result.datasetVersion()).isEqualTo("dataset-v1");
        verify(dataQualityService).resolve(product, placeholderStart, TODAY, "NONE", true);
        verify(quoteService).persistBatch(eq(product), eq(response), eq("NONE"), any(), any());
                verify(analysisClient, never()).resolveProduct(anyString(), anyString());
        verify(classificationService, never()).enrichIfMissing(any());
    }

    @Test
    void validNavIsCollectedEvenWhenTotalReturnInputIsUnavailable() {
        InvestmentProduct product = product();
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setCode("010736");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        LocalDate placeholderStart = TODAY.minusYears(50);
        Map<String, Object> response = Map.of("records", List.of(
                Map.of("data_date", "2018-06-01", "close", "1.20", "total_return_index", "100"),
                Map.of("data_date", TODAY.toString(), "close", "1.24")));
        when(dataQualityService.resolve(product, placeholderStart, TODAY, "NONE", true)).thenReturn(new InvestmentDataQualityService.Evaluation(evaluation(placeholderStart, TODAY).snapshot(), response, List.of(), List.of()));

        var result = service.prepareProduct(product, "FUND_NAV_HISTORY", false, placeholderStart, TODAY);
        assertThat(result.checkedThrough()).isEqualTo(TODAY);
        assertThat(result.sampleEndDate()).isEqualTo(TODAY);
        verify(quoteService).persistBatch(eq(product), eq(response), eq("NONE"), any(), any());
        assertThat(((List<Map<String,Object>>)response.get("records")).get(1)).doesNotContainKey("total_return_index");
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
        when(dataQualityService.resolve(product, start, target, "NONE", true)).thenReturn(new InvestmentDataQualityService.Evaluation(evaluation(start, target).snapshot(), response, List.of(), List.of()));

        InvestmentHistoryPreparationService.PreparationResult result =
                service.prepareProduct(product, "FUND_NAV_HISTORY", false, start, target);

        assertThat(result.sampleStartDate()).isEqualTo(start);
        assertThat(result.sampleEndDate()).isEqualTo(target);
        assertThat(result.datasetVersion()).isEqualTo("dataset-v1");
        verify(quoteService).persistBatch(eq(product), eq(response), eq("NONE"), any(), any());
            }

    @Test
    void historicalAcquisitionStillRejectsOutOfWindowRowsBeforePersistence() {
        InvestmentProduct product = product();
        LocalDate start = LocalDate.of(2026, 7, 10);
        LocalDate target = LocalDate.of(2026, 7, 20);
        when(dataQualityService.resolve(product, start, target, "QFQ", true)).thenReturn(new InvestmentDataQualityService.Evaluation(evaluation(start, target).snapshot(), evaluation(start.minusDays(1), target).response(), List.of(), List.of()));

        assertThatThrownBy(() -> service.prepareProduct(product, "STOCK_HISTORY", false, start, target))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("区间之外");
        verify(quoteService, never()).persistBatch(any(), any(), eq("QFQ"), any(), any());
        verify(quoteService).persistBatch(eq(product), any(), eq("NONE"), any(), any());
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
        verify(analysisClient).marketDailyQuotes(product, TODAY.minusYears(5), TODAY, "NONE");
        verify(analysisClient, never()).marketDailyQuotes(any(), any(), any(), eq("QFQ"));
        verify(analysisClient, never()).resolveProduct(anyString(), anyString());
        verify(dataQualityService, never()).resolve(any(), any(), any(), anyString(), anyBoolean());
    }

    @Test
    void missingAnalysisReturnsDoNotForceRepeatedNavBackfills() {
        var product=product();product.setProductType("MUTUAL_FUND");product.setMarket("FUND_CN");
        when(dataQualityService.adjustType(product)).thenReturn("NONE");
        when(quoteMapper.latestTradeDate(21L,"NONE")).thenReturn(TODAY);
        assertThat(service.prepareProduct(product,"FUND_NAV_HISTORY",false).skipped()).isTrue();
        verify(dataQualityService,never()).resolve(any(),any(),any(),anyString(),anyBoolean());
    }


    private static InvestmentProduct product() {
        InvestmentProduct product = new InvestmentProduct();
        product.setId(21L);
        product.setProductType("STOCK");
        product.setMarket("CN");
        product.setCode("600000");
        return product;
    }

    private void policyCalendar(LocalDate start,LocalDate end,List<String> expected) {
        when(analysisClient.quoteAvailability(any(),eq(start),eq(end),any())).thenReturn(calendarResponse(end,expected));
    }

    private static Map<String,Object> calendarResponse(LocalDate through,List<String> expected) {
        return Map.of("calendarKnown",true,"expectedThroughDate",through.toString(),"targetDate",through.toString(),
                "expectedDates",expected,"ruleProfile","CN_CLOSE","ruleVersion","quote-availability-v1");
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
