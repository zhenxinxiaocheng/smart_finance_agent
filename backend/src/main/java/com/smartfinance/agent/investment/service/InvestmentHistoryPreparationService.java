package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.domain.FundClassificationPolicy;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 历史行情准备：全市场与个人持仓共用的 product-level 能力。
 *
 * <p>调用链保持与原始实现一致：
 * <pre>
 *   MarketCatalog / UserPosition
 *     → 本服务（区间决策 + 质量校验 + 落库 + 覆盖标记）
 *       → InvestmentDataQualityService.resolve  （Provider + Normalize + Quality）
 *         → ProductDailyQuoteService.persistDailyQuotes （Persistence）
 *           → product_daily_quote
 * </pre>
 *
 * <p>正常运行为增量同步：只请求「本地最后有效日期 + 1」到当前可披露日期的区间。
 * 首次或缺少必需价格序列时回补；前复权基准变化时重建已有历史，用户也可明确执行全量修复。
 */
@Service
@Slf4j
public class InvestmentHistoryPreparationService {

    private static final ZoneId RUNTIME_ZONE = ZoneId.of("Asia/Shanghai");
    @Value("${market-data.history-years}")
    private int historyYears;
    @Value("${market-data.demand.a-share-calendar-fund-categories:INDEX_FUND,ACTIVE_EQUITY_FUND,HYBRID_FUND,BOND_FUND}")
    private Set<String> aShareCalendarFundCategories=Set.of("INDEX_FUND","ACTIVE_EQUITY_FUND","HYBRID_FUND","BOND_FUND");

    static final class QualityBlockedException extends IllegalStateException {
        QualityBlockedException() {
            super("历史数据质量校验未通过");
        }
    }

    public record PreparationResult(
            int recordCount,
            LocalDate requestedStartDate,
            LocalDate sampleStartDate,
            LocalDate sampleEndDate,
            boolean coverageComplete,
            String datasetVersion,
            boolean skipped,
            LocalDate verifiedThrough) {
        public PreparationResult(int count,LocalDate requestedStart,LocalDate sampleStart,LocalDate sampleEnd,
                                 boolean complete,String version,boolean skipped) {
            this(count,requestedStart,sampleStart,sampleEnd,complete,version,skipped,null);
        }
    }

    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final InvestmentDataQualityService dataQualityService;
    private final ProductDailyQuoteService quoteService;
    private final FundClassificationService classificationService;
    private final AnalysisServiceClient analysisClient;
    private final Clock clock;
    private InvestmentQuoteAvailabilityService availability;

    @Autowired
    public void setAvailability(InvestmentQuoteAvailabilityService availability) {
        this.availability = availability;
    }
    /**
     * 数据源调用与行情写事务分离；全部必需序列准备成功后，行情和覆盖标记一起提交。
     */
    private final TransactionTemplate writeTransaction;

    @Autowired
    public InvestmentHistoryPreparationService(
            InvestmentProductMapper productMapper,
            ProductDailyQuoteMapper quoteMapper,
            InvestmentDataQualityService dataQualityService,
            ProductDailyQuoteService quoteService,
            FundClassificationService classificationService,
            AnalysisServiceClient analysisClient,
            PlatformTransactionManager transactionManager) {
        this(productMapper, quoteMapper, dataQualityService, quoteService, classificationService, analysisClient,
                Clock.system(RUNTIME_ZONE), new TransactionTemplate(transactionManager));
    }

    InvestmentHistoryPreparationService(
            InvestmentProductMapper productMapper,
            ProductDailyQuoteMapper quoteMapper,
            InvestmentDataQualityService dataQualityService,
            ProductDailyQuoteService quoteService,
            FundClassificationService classificationService,
            AnalysisServiceClient analysisClient,
            Clock clock) {
        this(productMapper, quoteMapper, dataQualityService, quoteService, classificationService, analysisClient,
                clock, transactionTemplate(null));
    }

    /** 单测无事务管理器时退化为直接执行，语义与「无外层事务」一致。 */
    private static TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return manager == null ? null : new TransactionTemplate(manager);
    }

    InvestmentHistoryPreparationService(
            InvestmentProductMapper productMapper,
            ProductDailyQuoteMapper quoteMapper,
            InvestmentDataQualityService dataQualityService,
            ProductDailyQuoteService quoteService,
            FundClassificationService classificationService,
            AnalysisServiceClient analysisClient,
            Clock clock,
            TransactionTemplate writeTransaction) {
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.dataQualityService = dataQualityService;
        this.quoteService = quoteService;
        this.classificationService = classificationService;
        this.analysisClient = analysisClient;
        this.clock = clock;
        this.writeTransaction = writeTransaction;
    }

    /** 个人持仓入口：从 Job 解析出产品后复用 product-level 能力。 */
    public PreparationResult prepare(InvestmentDataJob job) {
        if (!isAssetHistoryJob(job)) {
            throw new IllegalArgumentException("仅支持股票或基金历史任务");
        }
        InvestmentProduct product = productMapper.selectById(job.getProductId());
        if (product == null) {
            throw new IllegalStateException("历史任务对应的产品不存在");
        }
        return prepareProduct(product, job.getJobType(), Boolean.TRUE.equals(job.getForceRefresh()));
    }

    /**
     * 全市场 / 个人持仓共用的 product-level 历史行情准备。
     *
     * <p><b>不开事务</b>：数据源调用（可能抛「无新记录」）在事务之外执行，
     * 只有真正需要写库的段落才用 {@link TransactionTemplate} 包起来，
     * 避免数据源的「无数据」把落库事务标记为 rollback-only。
     *
     * @param forceRefresh 仅当用户明确要求全量修复时为 true
     */
    public PreparationResult prepareProduct(InvestmentProduct incoming, String jobType, boolean forceRefresh) {
        return prepareProduct(incoming, jobType, forceRefresh, null, null);
    }

    /** Explicit windows are used by the existing resumable market job. */
    public PreparationResult prepareProduct(InvestmentProduct incoming, String jobType, boolean forceRefresh,
                                            LocalDate start, LocalDate target) {
        return prepareProduct(incoming,jobType,forceRefresh,start,target,false);
    }

    public PreparationResult prepareDemandProduct(InvestmentProduct product,String jobType,boolean forceRefresh,
                                                  LocalDate start,LocalDate target) {
        return prepareProduct(product,jobType,forceRefresh,start,target,true);
    }

    private PreparationResult prepareProduct(InvestmentProduct incoming,String jobType,boolean forceRefresh,
                                             LocalDate start,LocalDate target,boolean demandWindow) {
        InvestmentProduct product = start == null ? classificationService.enrichIfMissing(incoming)
                : demandWindow&&fund(incoming) ? classifyDemandFund(incoming) : incoming;
        String adjustType = dataQualityService.adjustType(product);
        boolean strict = supportsQuality(product);
        boolean needsRaw = !"NONE".equals(adjustType);
        LocalDate nextDate = incrementalStart(product);
        LocalDate localLastValid = nextDate == null ? null : nextDate.minusDays(1);
        boolean fullBackfill = localLastValid == null || forceRefresh;
        if (fullBackfill && start == null && knownStartDate(product) == null && strict) {
            AnalysisServiceClient.ResolvedProduct resolved = analysisClient.resolveProduct(
                    product.getProductType(), product.getCode());
            if (resolved != null && resolved.inceptionDate() != null) {
                product.setInceptionDate(resolved.inceptionDate());
            }
        }
        LocalDate availableEnd = availability == null ? LocalDate.now(clock)
                : availability.target(product, LocalDate.now(clock), clock.instant());
        LocalDate requestedEnd = target == null ? availableEnd
                : availability == null ? target : earlier(target, availableEnd);
        // Publication bounds limit acquisition; a demand receipt must prove the caller's whole window.
        LocalDate verificationEnd = demandWindow && target != null ? target : requestedEnd;
        LocalDate requestedStart = start != null ? start
                : fullBackfill ? initialStart(product, strict) : localLastValid.plusDays(1);
        if (fund(product)) {
            LocalDate missingReturn = quoteMapper.earliestMissingFundReturnDate(product.getId());
            requestedStart = earlier(requestedStart, missingReturn);
        }
        if (requestedStart.isAfter(requestedEnd)) {
            // 本地已是最新，无需请求数据源。
            PreparationResult result=skipped(product, adjustType, localLastValid, requestedStart);
            if(demandWindow&&confirmedClosedWindow(product,requestedStart,verificationEnd))
                return new PreparationResult(result.recordCount(),requestedStart,result.sampleStartDate(),result.sampleEndDate(),
                        result.coverageComplete(),null,true,verificationEnd);
            return result;
        }
        if (!fullBackfill && mainlandExchangeProduct(product)
                && confirmedClosedWindow(product, requestedStart, verificationEnd)) {
            PreparationResult result=skipped(product, adjustType, localLastValid, requestedStart);
            return demandWindow ? new PreparationResult(result.recordCount(),requestedStart,
                    result.sampleStartDate(),result.sampleEndDate(),result.coverageComplete(),null,true,verificationEnd) : result;
        }
        // Historical chunks are acquisition windows, not evidence of freshness today.
        // An unknown inception cannot supply a valid calendar denominator for a full-history check.
        boolean useQuality = strict && (demandWindow || start == null || (!requestedEnd.isBefore(availableEnd)
                && (knownStartDate(product) != null || !fullBackfill)));

        List<PreparedSeries> series = new ArrayList<>();
        try {
            ProductDailyQuote anchor = "QFQ".equals(adjustType) && (!fullBackfill || start!=null)
                    ? quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                    .eq(ProductDailyQuote::getProductId, product.getId())
                    .eq(ProductDailyQuote::getAdjustType, adjustType)
                    .lt(ProductDailyQuote::getTradeDate, requestedStart)
                    .orderByDesc(ProductDailyQuote::getTradeDate).last("LIMIT 1")) : null;
            if(anchor==null && start!=null && "QFQ".equals(adjustType))
                anchor=quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                        .eq(ProductDailyQuote::getProductId,product.getId()).eq(ProductDailyQuote::getAdjustType,adjustType)
                        .orderByDesc(ProductDailyQuote::getTradeDate).last("LIMIT 1"));
            PreparedSeries primary = fetch(product, anchor == null ? requestedStart : earlier(requestedStart,anchor.getTradeDate()),
                    anchor==null ? requestedEnd : later(requestedEnd,anchor.getTradeDate()), adjustType, useQuality);
            if (anchor != null) {
                BigDecimal remoteAnchor = closeOn(primary.response(), anchor.getTradeDate());
                if (remoteAnchor == null || anchor.getClosePrice() == null) {
                    throw new IllegalStateException("复权增量缺少重叠价格，无法确认历史价格基准");
                }
                if (remoteAnchor.compareTo(anchor.getClosePrice()) != 0) {
                    // QFQ prices are time-varying: never splice a new basis into the old series.
                    LocalDate origin = knownStartDate(product);
                    if (origin == null) {
                        ProductDailyQuote first = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                                .eq(ProductDailyQuote::getProductId, product.getId())
                                .eq(ProductDailyQuote::getAdjustType, adjustType)
                                .orderByAsc(ProductDailyQuote::getTradeDate).last("LIMIT 1"));
                        origin = first == null ? null : first.getTradeDate();
                    }
                    if (origin == null) throw new IllegalStateException("复权基准变化但本地历史起点缺失");
                    requestedStart = earlier(requestedStart,origin);
                    requestedEnd = later(requestedEnd,localLastValid);
                    requestedEnd = later(requestedEnd,anchor.getTradeDate());
                    fullBackfill = true;
                    primary = fetch(product, requestedStart, requestedEnd, adjustType, useQuality);
                    Set<LocalDate> refreshed=recordDates(primary.response(),primary.adjustType());
                    for(var existing:quoteMapper.selectList(new LambdaQueryWrapper<ProductDailyQuote>()
                            .eq(ProductDailyQuote::getProductId,product.getId()).eq(ProductDailyQuote::getAdjustType,adjustType)))
                        if(!refreshed.contains(existing.getTradeDate()))
                            throw new IllegalStateException("复权基准重建未覆盖已有日期，保留原行情等待补齐");
                } else {
                    primary = after(primary, requestedStart);
                    if(primary.end().isAfter(requestedEnd))primary=through(primary,requestedEnd);
                }
            }
            series.add(primary);
        } catch (RuntimeException exception) {
            if ((fullBackfill && start == null) || !isSourceEmpty(exception)) {
                throw exception;
            }
            log.debug("增量窗口 {} 在数据源尚无记录，跳过：{}", requestedStart, exception.getMessage());
            PreparationResult result=skipped(product, adjustType, localLastValid, requestedStart);
            // Funds are fetched first: a Chinese exchange holiday cannot suppress overseas NAV.
            if(demandWindow&&confirmedClosedWindow(product,requestedStart,verificationEnd))
                return new PreparationResult(result.recordCount(),requestedStart,result.sampleStartDate(),result.sampleEndDate(),
                        result.coverageComplete(),null,true,verificationEnd);
            return result;
        }
        PreparedSeries primary = series.get(0);
        if (primary.evaluation() != null && primary.evaluation().blocked()) {
            write(writeTransaction, () -> {
                product.setHistoryCoverageComplete(false);
                productMapper.updateById(product);
            });
            throw new QualityBlockedException();
        }
        if (needsRaw) {
            PreparedSeries raw = fetch(product, requestedStart, requestedEnd, "NONE", useQuality);
            if (raw.evaluation() != null && raw.evaluation().blocked()) {
                throw new QualityBlockedException();
            }
            // Every research date must have an executable raw price before either series is written.
            Set<LocalDate> rawDates = recordDates(raw.response(),raw.adjustType());
            if (!rawDates.containsAll(recordDates(primary.response(),primary.adjustType()))) {
                throw new IllegalStateException("原始价格未覆盖研究价格日期，等待补齐后重试");
            }
            series.add(raw);
        }
        if (!fullBackfill && primary.end() != null && !primary.end().isAfter(localLastValid)) {
            return skipped(product, adjustType, localLastValid, requestedStart);
        }
        for (PreparedSeries item : series) {
            if (item.evaluation() != null) dataQualityService.claim(item.evaluation());
        }
        LocalDate sampleStart = series.stream().map(PreparedSeries::start).max(LocalDate::compareTo).orElseThrow();
        LocalDate sampleEnd = series.stream().map(PreparedSeries::end).min(LocalDate::compareTo).orElseThrow();
        boolean coverageComplete = fullBackfill
                ? initialCoverageComplete(product, requestedStart, requestedEnd, series, availableEnd)
                : Boolean.TRUE.equals(product.getHistoryCoverageComplete());
        write(writeTransaction, () -> {
            for (PreparedSeries item : series) {
                quoteService.persistDailyQuotes(product, item.response(), item.adjustType());
            }
            product.setHistoryStartDate(
                    earlier(product.getHistoryStartDate(), sampleStart));
            product.setHistoryEndDate(
                    later(product.getHistoryEndDate(), sampleEnd));
            product.setHistoryCoverageComplete(coverageComplete);
            productMapper.updateById(product);
        });

        return new PreparationResult(
                persistedCount(product.getId(), adjustType),
                requestedStart,
                sampleStart,
                sampleEnd,
                coverageComplete,
                primary.evaluation() == null ? null : primary.evaluation().datasetVersion(),
                false,
                demandWindow ? verifiedThrough(product,requestedStart,verificationEnd,series) : null);
    }

    /** null means one of the required price series still needs its initial history. */
    public LocalDate incrementalStart(InvestmentProduct product) {
        String adjustment = dataQualityService.adjustType(product);
        LocalDate latest = fund(product) ? quoteMapper.latestCompleteFundTradeDate(product.getId())
                : quoteMapper.latestTradeDate(product.getId(), adjustment);
        if (!"NONE".equals(adjustment)) {
            LocalDate raw = quoteMapper.latestTradeDate(product.getId(), "NONE");
            if (raw == null || latest == null) return null;
            latest = earlier(latest, raw);
        }
        LocalDate next = latest == null ? null : latest.plusDays(1);
        if (fund(product) && next != null) {
            next = earlier(next, quoteMapper.earliestMissingFundReturnDate(product.getId()));
        }
        return next;
    }

    private record PreparedSeries(String adjustType, Map<String, Object> response,
                                  InvestmentDataQualityService.Evaluation evaluation,
                                  LocalDate start, LocalDate end) { }

    private static BigDecimal closeOn(Map<String, Object> response, LocalDate day) {
        for (Object value : (List<?>) response.get("records")) {
            Map<?, ?> row = (Map<?, ?>) value;
            if (day.equals(LocalDate.parse(String.valueOf(row.get("data_date")).substring(0, 10)))) {
                return new BigDecimal(String.valueOf(row.get("close")));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static PreparedSeries after(PreparedSeries series, LocalDate start) {
        List<Map<String, Object>> records = ((List<Map<String, Object>>) series.response().get("records")).stream()
                .filter(row -> !LocalDate.parse(String.valueOf(row.get("data_date")).substring(0, 10)).isBefore(start))
                .toList();
        if (records.isEmpty()) throw new AnalysisServiceClient.SourceEmptyException("请求区间未返回新记录");
        Map<String, Object> response = new java.util.LinkedHashMap<>(series.response());
        response.put("records", records);
        Set<LocalDate> dates = recordDates(response,series.adjustType());
        return new PreparedSeries(series.adjustType(), response, series.evaluation(),
                dates.stream().min(LocalDate::compareTo).orElseThrow(), dates.stream().max(LocalDate::compareTo).orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static PreparedSeries through(PreparedSeries series,LocalDate end) {
        List<Map<String,Object>> records=((List<Map<String,Object>>)series.response().get("records")).stream()
                .filter(row->!LocalDate.parse(String.valueOf(row.get("data_date")).substring(0,10)).isAfter(end)).toList();
        if(records.isEmpty())throw new AnalysisServiceClient.SourceEmptyException("请求区间未返回新记录");
        Map<String,Object> response=new java.util.LinkedHashMap<>(series.response());response.put("records",records);
        Set<LocalDate> dates=recordDates(response,series.adjustType());
        return new PreparedSeries(series.adjustType(),response,series.evaluation(),
                dates.stream().min(LocalDate::compareTo).orElseThrow(),dates.stream().max(LocalDate::compareTo).orElseThrow());
    }

    /** A receipt covers every expected observation, including the beginning and internal dates. */
    private LocalDate verifiedThrough(InvestmentProduct product,LocalDate start,LocalDate end,List<PreparedSeries> series) {
        LocalDate observed=series.stream().map(PreparedSeries::end).min(LocalDate::compareTo).orElseThrow();
        if(observed.isBefore(start))return null;
        try {
            List<LocalDate> calendar=AnalysisServiceClient.expectedQuoteDates(
                    analysisClient.quoteAvailability(product,start,end,clock.instant()),start,end);
            if(calendar==null||calendar.isEmpty())return null;
            Set<LocalDate> expected=new java.util.HashSet<>();
            for(LocalDate date:calendar)if(!date.isAfter(observed))expected.add(date);
            if(expected.isEmpty()||series.stream().anyMatch(item->!recordDates(item.response(),item.adjustType()).containsAll(expected)))return null;
            boolean closedTail=calendar.stream().noneMatch(date->date.isAfter(observed));
            return closedTail ? end : observed;
        } catch(RuntimeException unavailable) {
            log.debug("未确认区间覆盖，保留已获取行情 {} 至 {}",start,end,unavailable);
            return null;
        }
    }

    private boolean domesticCalendarProduct(InvestmentProduct product) {
        return mainlandExchangeProduct(product)||"CN_INDEX".equals(product.getMarket())
                ||(fund(product)&&"FUND_CN".equals(product.getMarket())
                    &&aShareCalendarFundCategories.contains(product.getFundCategory()==null?"":product.getFundCategory()));
    }
    /** Pending classification may enter the existing job; calendar receipts still require a known supported category. */
    public boolean supportsDemandPreparation(InvestmentProduct product) {
        return domesticCalendarProduct(product) || (fund(product) && "FUND_CN".equals(product.getMarket())
                && !FundClassificationPolicy.known(product.getFundCategory()));
    }
    private InvestmentProduct classifyDemandFund(InvestmentProduct product) {
        try{return classificationService.enrichIfMissing(product);}
        catch(RuntimeException unavailable){log.debug("基金分类暂未取得，仍保留可获取的净值 {}",product.getId(),unavailable);return product;}
    }

    private PreparedSeries fetch(InvestmentProduct product, LocalDate start, LocalDate end,
                                 String adjustType, boolean strict) {
        InvestmentDataQualityService.Evaluation evaluation = strict
                ? dataQualityService.resolve(product, start, end, adjustType, true) : null;
        Map<String, Object> response = evaluation == null
                ? analysisClient.marketDailyQuotes(product, start, end, adjustType) : evaluation.response();
        Set<LocalDate> dates = recordDates(response,adjustType);
        if (dates.isEmpty()) throw new AnalysisServiceClient.SourceEmptyException("请求区间未返回记录");
        if (fund(product) && hasInvalidFundReturns(response)) {
            throw new IllegalStateException("基金历史缺少有效累计收益，保留已有数据并等待重试");
        }
        if (dates.stream().anyMatch(day -> day.isBefore(start) || day.isAfter(end))) {
            // A stale/out-of-window response must never overwrite local data.
            throw new IllegalStateException("数据源返回请求区间之外的记录");
        }
        return new PreparedSeries(adjustType, response, evaluation,
                dates.stream().min(LocalDate::compareTo).orElseThrow(),
                dates.stream().max(LocalDate::compareTo).orElseThrow());
    }

    static boolean hasInvalidFundReturns(Map<String, Object> response) {
        for (Object value : (List<?>) response.get("records")) {
            Map<?, ?> row = (Map<?, ?>) value;
            Object index = row.get("total_return_index");
            if (index == null && row.get("adjustment_factor") != null) {
                Object nav = row.get("nav") == null ? row.get("close") : row.get("nav");
                if (nav != null) index = new BigDecimal(String.valueOf(nav))
                        .multiply(new BigDecimal(String.valueOf(row.get("adjustment_factor"))));
            }
            if (index == null || new BigDecimal(String.valueOf(index)).signum() <= 0) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static Set<LocalDate> recordDates(Map<String, Object> response) {
        return recordDates(response,"NONE");
    }

    @SuppressWarnings("unchecked")
    private static Set<LocalDate> recordDates(Map<String, Object> response,String adjustType) {
        if (response == null || !(response.get("records") instanceof List<?>)) {
            throw new IllegalStateException("日线响应缺少records");
        }
        Set<LocalDate> dates = new java.util.HashSet<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) response.get("records")) {
            LocalDate day = LocalDate.parse(String.valueOf(row.get("data_date")).substring(0, 10));
            Object close = row.get("close") == null ? row.get("nav") : row.get("close");
            if (close == null) throw new IllegalStateException("日线存在无效价格或重复日期");
            BigDecimal price=new BigDecimal(String.valueOf(close));
            if ((!"QFQ".equals(adjustType) && price.signum() <= 0) || !dates.add(day)) {
                throw new IllegalStateException("日线存在无效价格或重复日期");
            }
        }
        return dates;
    }

    static boolean supportsQuality(InvestmentProduct product) {
        return fund(product) || ("STOCK".equals(product.getProductType()) && mainlandExchangeProduct(product));
    }

    private static boolean mainlandExchangeProduct(InvestmentProduct product) {
        return ("STOCK".equals(product.getProductType()) || "ETF".equals(product.getProductType()))
                && product.getMarket() != null
                && Set.of("SSE", "SZSE", "BSE", "SH", "SZ", "BJ", "CN").contains(product.getMarket());
    }

    private boolean confirmedClosedWindow(InvestmentProduct product,LocalDate start, LocalDate end) {
        try {
            List<LocalDate> dates=AnalysisServiceClient.expectedQuoteDates(
                    analysisClient.quoteAvailability(product,start,end,clock.instant()),start,end);
            return dates!=null&&dates.isEmpty();
        } catch (RuntimeException unavailable) {
            log.debug("交易日历暂不可用，继续从价格源获取 {} 至 {}", start, end, unavailable);
            return false;
        }
    }

    public boolean closedAfter(InvestmentProduct product,LocalDate lastObserved,LocalDate end) {
        return lastObserved!=null&&lastObserved.isBefore(end)&&mainlandExchangeProduct(product)
                && confirmedClosedWindow(product,lastObserved.plusDays(1),end);
    }

    private static boolean fund(InvestmentProduct product) {
        return "MUTUAL_FUND".equals(product.getProductType()) || "FUND".equals(product.getProductType());
    }

    /** 写库段落包在独立事务里；无事务管理器（单测）时直接执行。 */
    private static void write(TransactionTemplate template, Runnable action) {
        if (template == null) {
            action.run();
            return;
        }
        template.executeWithoutResult(status -> action.run());
    }

    private PreparationResult skipped(InvestmentProduct product, String adjustType,
                                      LocalDate localLastValid, LocalDate requestedStart) {
        return new PreparationResult(
                persistedCount(product.getId(), adjustType),
                requestedStart,
                localLastValid,
                localLastValid,
                Boolean.TRUE.equals(product.getHistoryCoverageComplete()),
                null,
                true);
    }

    private int persistedCount(Long productId, String adjustType) {
        long count = quoteMapper.selectCount(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, productId)
                .eq(ProductDailyQuote::getAdjustType, adjustType));
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /**
     * 数据源「该区间没有记录」的判定。
     *
     * <p>仅识别分析服务结构化 SOURCE_EMPTY；不从错误文本推断是否尚未发布。
     */
    private static boolean isSourceEmpty(RuntimeException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof AnalysisServiceClient.SourceEmptyException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAssetHistoryJob(InvestmentDataJob job) {
        return job != null && ("STOCK_HISTORY".equals(job.getJobType())
                || "FUND_NAV_HISTORY".equals(job.getJobType()));
    }

    private LocalDate initialStart(InvestmentProduct product, boolean strict) {
        LocalDate start = knownStartDate(product);
        if (start == null) {
            if (strict || historyYears <= 0) {
                throw new IllegalStateException("产品历史起始日期缺失，无法校验完整历史数据");
            }
            return LocalDate.now(clock).minusYears(historyYears);
        }
        return start;
    }

    private static LocalDate knownStartDate(InvestmentProduct product) {
        if ("STOCK".equals(product.getProductType()) && product.getListingDate() != null) {
            return product.getListingDate();
        }
        return product.getInceptionDate();
    }

    /** 仅全量回补时校验：样本起点需覆盖产品成立/上市日期（含容差）。 */
    private boolean initialCoverageComplete(InvestmentProduct product, LocalDate start, LocalDate end,
                                            List<PreparedSeries> series, LocalDate availableEnd) {
        LocalDate inception = knownStartDate(product);
        if (inception == null || start.isAfter(inception) || end.isBefore(availableEnd)) return false;
        return series.stream().allMatch(item -> item.evaluation() != null
                && missingDatesVerified(item.response()));
    }

    private static boolean missingDatesVerified(Map<String, Object> response) {
        if (!(response.get("qualityReport") instanceof Map<?, ?> report)
                || !(report.get("issues") instanceof List<?> issues)) return false;
        return issues.stream().filter(Map.class::isInstance).map(Map.class::cast).anyMatch(issue ->
                Set.of("STOCK_UNEXPLAINED_TRADING_GAPS", "FUND_UNEXPLAINED_NAV_GAPS")
                        .contains(String.valueOf(issue.get("ruleCode")))
                        && "PASS".equals(issue.get("outcome"))
                        && issue.get("observed") instanceof Map<?, ?> observed
                        && "0".equals(String.valueOf(observed.get("missingDateCount"))));
    }

    private static LocalDate earlier(LocalDate first, LocalDate second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isBefore(second) ? first : second;
    }

    private static LocalDate later(LocalDate first, LocalDate second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }
}
