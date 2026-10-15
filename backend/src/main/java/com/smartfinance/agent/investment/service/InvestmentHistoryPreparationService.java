package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.investment.marketdata.contract.MarketDataContract;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 历史行情准备：全市场与个人持仓共用的 product-level 能力。
 *
 * <p>调用链保持与原始实现一致：
 * <pre>
 *   MarketCatalog / UserPosition
 *     → 本服务（区间决策 + 质量校验 + 落库 + 采集事实）
 *       → InvestmentDataQualityService.resolve  （Provider + Normalize + Quality）
 *         → ProductDailyQuoteService.persistBatch （Persistence）
 *           → product_daily_quote + market_data_collection_batch
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
            boolean sourceWindowChecked,
            String datasetVersion,
            boolean skipped,
            LocalDate checkedThrough) {
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
     * 数据源调用与写事务分离；每个有效序列和它的准备记录独立提交。
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

    public PreparationResult prepareMarketProduct(InvestmentProduct product,String jobType,boolean forceRefresh,
                                                   LocalDate start,LocalDate target,boolean demandWindow,Runnable beforeWrite) {
        return prepareProduct(product,jobType,forceRefresh,start,target,demandWindow,beforeWrite);
    }

    public boolean requiresPreparation(InvestmentProduct product, LocalDate start, LocalDate end) {
        if (!supportsQuality(product)) return false;
        var inputs = quoteMapper.selectList(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .eq(ProductDailyQuote::getAdjustType, dataQualityService.adjustType(product))
                .ge(ProductDailyQuote::getTradeDate, start).le(ProductDailyQuote::getTradeDate, end));
        return dataQualityService.requiresPreparation(product, start, end, inputs);
    }

    private PreparationResult prepareProduct(InvestmentProduct incoming,String jobType,boolean forceRefresh,
                                             LocalDate start,LocalDate target,boolean demandWindow) {
        return prepareProduct(incoming,jobType,forceRefresh,start,target,demandWindow,()->{});
    }
    private PreparationResult prepareProduct(InvestmentProduct incoming,String jobType,boolean forceRefresh,
                                             LocalDate start,LocalDate target,boolean demandWindow,Runnable beforeWrite) {
        InvestmentProduct product = fund(incoming) && (start == null || demandWindow) ? classifyFund(incoming)
                : start == null ? classificationService.enrichIfMissing(incoming) : incoming;
        String adjustType = dataQualityService.adjustType(product);
        String dataContract = MarketDataContract.forProduct("PRICE",product.getProductType()).version();
        boolean strict = supportsQuality(product);
        boolean needsRaw = !"NONE".equals(adjustType);
        LocalDate nextDate = incrementalStart(product);
        LocalDate localLastValid = nextDate == null ? null : nextDate.minusDays(1);
        boolean fullBackfill = localLastValid == null || forceRefresh;
        if (fullBackfill && start == null && knownStartDate(product) == null && strict) {
            try {
                AnalysisServiceClient.ResolvedProduct resolved = analysisClient.resolveProduct(product.getProductType(), product.getCode());
                if (resolved != null && resolved.inceptionDate() != null) product.setInceptionDate(resolved.inceptionDate());
            } catch (RuntimeException unavailable) {
                log.debug("历史起点资料暂不可用，仍按配置窗口采集 {}",product.getId(),unavailable);
            }
        }
        LocalDate availableEnd = availability == null ? LocalDate.now(clock)
                : availability.target(product, LocalDate.now(clock), clock.instant());
        // Explicit acquisition windows stay intact; automatic callers use the calendar target.
        LocalDate requestedEnd = target == null ? availableEnd : target;
        LocalDate requestedStart = start != null ? start
                : fullBackfill ? initialStart(product) : localLastValid.plusDays(1);
        if (demandWindow && !forceRefresh && localLastValid != null && requestedStart.isAfter(localLastValid))
            requestedStart = earlier(requestedStart, localLastValid.plusDays(1));
        if (requestedStart.isAfter(requestedEnd))
            return skipped(product, adjustType, localLastValid, requestedStart);
        if (!forceRefresh && confirmedClosedWindow(product, requestedStart, requestedEnd,beforeWrite))
            return checkedEmpty(product, adjustType, localLastValid, requestedStart, requestedEnd);
        boolean useQuality = strict;

        PreparedSeries primary = null;
        RuntimeException failure = null;
        boolean rebuilding = false;
        boolean anchoredBasis = "QFQ".equals(adjustType) || fund(product);
        try {
            ProductDailyQuote anchor = anchoredBasis && (!fullBackfill || start!=null)
                    ? quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                    .eq(ProductDailyQuote::getProductId, product.getId())
                    .eq(ProductDailyQuote::getAdjustType, adjustType)
                    .lt(ProductDailyQuote::getTradeDate, requestedStart)
                    .orderByDesc(ProductDailyQuote::getTradeDate).last("LIMIT 1")) : null;
            if(anchor==null && start!=null && anchoredBasis)
                anchor=quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                        .eq(ProductDailyQuote::getProductId,product.getId()).eq(ProductDailyQuote::getAdjustType,adjustType)
                        .orderByDesc(ProductDailyQuote::getTradeDate).last("LIMIT 1"));
            if(anchor!=null&&!dataContract.equals(anchor.getDataContractVersion()))anchor=null;
            primary = fetch(product, anchor == null ? requestedStart : earlier(requestedStart,anchor.getTradeDate()),
                    anchor==null ? requestedEnd : later(requestedEnd,anchor.getTradeDate()), adjustType, useQuality);
            if (anchor != null) {
                BigDecimal remoteAnchor = valueOn(primary.response(), anchor.getTradeDate(), "close");
                if (remoteAnchor == null || anchor.getClosePrice() == null) {
                    throw new IllegalStateException("复权增量缺少重叠价格，无法确认历史价格基准");
                }
                BigDecimal remoteReturn = fund(product) ? valueOn(primary.response(), anchor.getTradeDate(), "total_return_index") : null;
                boolean sameReturn = !fund(product) || ProductDailyQuoteService.same(remoteReturn, anchor.getTotalReturnIndex(), 10);
                if (!ProductDailyQuoteService.same(remoteAnchor, anchor.getClosePrice(), 10) || !sameReturn) {
                    // Adjusted prices and fund return indices must share one basis across history.
                    rebuilding = true;
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
                            .eq(ProductDailyQuote::getProductId,product.getId()).eq(ProductDailyQuote::getAdjustType,adjustType))) {
                        if(!dataContract.equals(existing.getDataContractVersion()))continue;
                        if(!refreshed.contains(existing.getTradeDate()))
                            throw new IllegalStateException("复权基准重建未覆盖已有日期，保留原行情等待补齐");
                        if (fund(product) && existing.getTotalReturnIndex()!=null && existing.getTotalReturnIndex().signum()>0) {
                            BigDecimal rebuiltReturn = valueOn(primary.response(),existing.getTradeDate(),"total_return_index");
                            if (rebuiltReturn==null || rebuiltReturn.signum()<=0)
                                throw new IllegalStateException("收益基准重建未覆盖已有收益数据，保留原行情等待补齐");
                        }
                    }
                }
            }
        } catch (RuntimeException exception) {
            primary = null;
            if (exception instanceof AnalysisServiceClient.SourceEmptyException empty && !rebuilding) {
                LocalDate emptyStart=requestedStart,emptyEnd=requestedEnd;
                write(writeTransaction,()->{beforeWrite.run();quoteService.recordEmpty(product,adjustType,emptyStart,emptyEnd,empty.response());});
                log.debug("研究价格查询正常返回空记录 {} 至 {}", requestedStart, requestedEnd);
            } else failure = isSourceEmpty(exception)
                    ? new IllegalStateException("价格基准重建未返回记录，保留原行情等待重试", exception) : exception;
        }
        if (primary != null) {
            try {
                if (primary.evaluation() != null && primary.evaluation().blocked()) throw new QualityBlockedException();
                persistSeries(product, primary, true,beforeWrite);
            } catch (RuntimeException exception) {
                failure = exception;
            }
        }
        if (needsRaw) {
            try {
                PreparedSeries raw = fetch(product, requestedStart, requestedEnd, "NONE", useQuality);
                if (raw.evaluation() != null && raw.evaluation().blocked()) throw new QualityBlockedException();
                persistSeries(product, raw, false,beforeWrite);
            } catch (AnalysisServiceClient.SourceEmptyException empty) {
                LocalDate emptyStart=requestedStart,emptyEnd=requestedEnd;
                write(writeTransaction,()->{beforeWrite.run();quoteService.recordEmpty(product,"NONE",emptyStart,emptyEnd,empty.response());});
                log.debug("原始价格查询正常返回空记录 {} 至 {}", requestedStart, requestedEnd);
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else if (failure != exception) failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
        if (primary == null) return checkedEmpty(product, adjustType, localLastValid, requestedStart, requestedEnd);

        return new PreparationResult(
                persistedCount(product.getId(), adjustType),
                requestedStart,
                primary.start(),
                primary.end(),
                true,
                primary.evaluation() == null ? null : primary.evaluation().datasetVersion(),
                false,
                requestedEnd);
    }

    /** Another series failing must not discard valid observations already fetched and stored. */
    private void persistSeries(InvestmentProduct product, PreparedSeries item, boolean primary,Runnable beforeWrite) {
        if (item.evaluation() != null) dataQualityService.claim(item.evaluation());
        write(writeTransaction, () -> {
            beforeWrite.run();
            quoteService.persistBatch(product, item.response(), item.adjustType(),item.requestedStart(),item.requestedEnd());
            if (item.evaluation() != null) dataQualityService.persistPrepared(item.evaluation());
            if (primary) {
                product.setHistoryStartDate(earlier(product.getHistoryStartDate(), item.start()));
                product.setHistoryEndDate(later(product.getHistoryEndDate(), item.end()));
                productMapper.updateById(product);
            }
        });
    }

    /** null means one of the required price series still needs its initial history. */
    public LocalDate incrementalStart(InvestmentProduct product) {
        String adjustment = dataQualityService.adjustType(product);
        String contract=com.smartfinance.agent.investment.marketdata.contract.MarketDataContract.forProduct("PRICE",product.getProductType()).version();
        if(quoteMapper.countIncompatible(product.getId(),adjustment,contract)>0
                ||!"NONE".equals(adjustment)&&quoteMapper.countIncompatible(product.getId(),"NONE",contract)>0)return null;
        LocalDate latest = quoteMapper.latestTradeDate(product.getId(), adjustment);
        if (!"NONE".equals(adjustment)) {
            LocalDate raw = quoteMapper.latestTradeDate(product.getId(), "NONE");
            if (raw == null || latest == null) return null;
            latest = earlier(latest, raw);
        }
        LocalDate next = latest == null ? null : latest.plusDays(1);
        return next;
    }

    private record PreparedSeries(String adjustType, Map<String, Object> response,
                                  InvestmentDataQualityService.Evaluation evaluation,
                                  LocalDate start, LocalDate end,LocalDate requestedStart,LocalDate requestedEnd) { }

    private static BigDecimal valueOn(Map<String, Object> response, LocalDate day, String field) {
        for (Object value : (List<?>) response.get("records")) {
            Map<?, ?> row = (Map<?, ?>) value;
            if (day.equals(LocalDate.parse(String.valueOf(row.get("data_date")).substring(0, 10)))) {
                if ("total_return_index".equals(field)) return ProductDailyQuoteService.totalReturnIndex(row);
                Object valueAtDate = row.get(field);
                if (valueAtDate == null && "close".equals(field)) valueAtDate = row.get("nav");
                return valueAtDate == null ? null : new BigDecimal(String.valueOf(valueAtDate));
            }
        }
        return null;
    }

    private boolean domesticCalendarProduct(InvestmentProduct product) {
        return mainlandExchangeProduct(product)||"CN_INDEX".equals(product.getMarket())
                ||(fund(product)&&"FUND_CN".equals(product.getMarket())
                    &&aShareCalendarFundCategories.contains(product.getFundCategory()==null?"":product.getFundCategory()));
    }
    /** Pending classification may enter the existing job; calendar receipts still require a known supported category. */
    public boolean supportsDemandPreparation(InvestmentProduct product) {
        return domesticCalendarProduct(product) || (fund(product) && "FUND_CN".equals(product.getMarket())
                && !com.smartfinance.agent.investment.domain.FundClassificationPolicy.known(product.getFundCategory()));
    }
    private InvestmentProduct classifyFund(InvestmentProduct product) {
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
        if (dates.isEmpty()) throw new AnalysisServiceClient.SourceEmptyException("请求区间未返回记录",null,response);
        if (dates.stream().anyMatch(day -> day.isBefore(start) || day.isAfter(end))) {
            // A stale/out-of-window response must never overwrite local data.
            throw new IllegalStateException("数据源返回请求区间之外的记录");
        }
        return new PreparedSeries(adjustType, response, evaluation,
                dates.stream().min(LocalDate::compareTo).orElseThrow(),
                dates.stream().max(LocalDate::compareTo).orElseThrow(),start,end);
    }

    @SuppressWarnings("unchecked")
    static Set<LocalDate> recordDates(Map<String, Object> response) {
        return recordDates(response,"NONE");
    }

    @SuppressWarnings("unchecked")
    static Set<LocalDate> recordDates(Map<String, Object> response,String adjustType) {
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
        return fund(product) || ("STOCK".equals(product.getProductType()) && (mainlandExchangeProduct(product)
                || product.getMarket() != null && Set.of("NASDAQ", "NYSE", "AMEX").contains(product.getMarket())));
    }

    private static boolean mainlandExchangeProduct(InvestmentProduct product) {
        return ("STOCK".equals(product.getProductType()) || "ETF".equals(product.getProductType()))
                && product.getMarket() != null
                && Set.of("SSE", "SZSE", "BSE", "SH", "SZ", "BJ", "CN").contains(product.getMarket());
    }

    private boolean confirmedClosedWindow(InvestmentProduct product,LocalDate start, LocalDate end,Runnable beforeWrite) {
        try {
            var response=analysisClient.quoteAvailability(product,start,end,clock.instant());
            List<LocalDate> dates=AnalysisServiceClient.expectedQuoteDates(response,start,end);
            if(dates==null||!dates.isEmpty())return false;
            String adjustment=dataQualityService.adjustType(product);
            var closed=Map.<String,Object>of("result","CLOSED","records",List.of(),"calendarVerified",true,
                    "dataContractVersion",com.smartfinance.agent.investment.marketdata.contract.MarketDataContract.forProduct("PRICE",product.getProductType()).version());
            write(writeTransaction,()->{
                beforeWrite.run();
                quoteService.recordEmpty(product,adjustment,start,end,closed);
                if(!"NONE".equals(adjustment))quoteService.recordEmpty(product,"NONE",start,end,closed);
            });
            return true;
        } catch (RuntimeException unavailable) {
            log.debug("交易日历暂不可用，继续从价格源获取 {} 至 {}", start, end, unavailable);
            return false;
        }
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

    private PreparationResult checkedEmpty(InvestmentProduct product, String adjustment,
                                           LocalDate last, LocalDate start, LocalDate end) {
        var previous = skipped(product, adjustment, last, start);
        return new PreparationResult(previous.recordCount(), start, previous.sampleStartDate(),
                previous.sampleEndDate(), true, null, true, end);
    }

    private PreparationResult skipped(InvestmentProduct product, String adjustType,
                                      LocalDate localLastValid, LocalDate requestedStart) {
        return new PreparationResult(
                persistedCount(product.getId(), adjustType),
                requestedStart,
                localLastValid,
                localLastValid,
                false,
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

    private LocalDate initialStart(InvestmentProduct product) {
        LocalDate start = knownStartDate(product);
        if (start == null) {
            if (historyYears <= 0) {
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

    /** Completion describes a successful origin-to-target query, not guessed daily observations. */
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
