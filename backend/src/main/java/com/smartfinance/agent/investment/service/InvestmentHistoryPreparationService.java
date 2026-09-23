package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class InvestmentHistoryPreparationService {

    private static final ZoneId RUNTIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int INCEPTION_TOLERANCE_DAYS = 14;

    static final class QualityBlockedException extends IllegalStateException {
        QualityBlockedException() {
            super("全量历史数据完整性校验未通过");
        }
    }

    public record PreparationResult(
            int recordCount,
            LocalDate requestedStartDate,
            LocalDate sampleStartDate,
            LocalDate sampleEndDate,
            boolean coverageComplete,
            String datasetVersion) {
    }

    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final InvestmentDataQualityService dataQualityService;
    private final InvestmentSyncWorker syncWorker;
    private final FundClassificationService classificationService;
    private final AnalysisServiceClient analysisClient;
    private final Clock clock;

    @Autowired
    public InvestmentHistoryPreparationService(
            InvestmentProductMapper productMapper,
            ProductDailyQuoteMapper quoteMapper,
            InvestmentDataQualityService dataQualityService,
            InvestmentSyncWorker syncWorker,
            FundClassificationService classificationService,
            AnalysisServiceClient analysisClient) {
        this(productMapper, quoteMapper, dataQualityService, syncWorker, classificationService, analysisClient,
                Clock.system(RUNTIME_ZONE));
    }

    InvestmentHistoryPreparationService(
            InvestmentProductMapper productMapper,
            ProductDailyQuoteMapper quoteMapper,
            InvestmentDataQualityService dataQualityService,
            InvestmentSyncWorker syncWorker,
            FundClassificationService classificationService,
            AnalysisServiceClient analysisClient,
            Clock clock) {
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.dataQualityService = dataQualityService;
        this.syncWorker = syncWorker;
        this.classificationService = classificationService;
        this.analysisClient = analysisClient;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = QualityBlockedException.class)
    public PreparationResult prepare(InvestmentDataJob job) {
        if (!isAssetHistoryJob(job)) {
            throw new IllegalArgumentException("仅支持股票或基金历史任务");
        }
        InvestmentProduct product = productMapper.selectById(job.getProductId());
        if (product == null) {
            throw new IllegalStateException("历史任务对应的产品不存在");
        }
        product = classificationService.enrichIfMissing(product);

        boolean initialLoad = Boolean.TRUE.equals(job.getForceRefresh())
                || !Boolean.TRUE.equals(product.getHistoryCoverageComplete())
                || product.getHistoryEndDate() == null
                || ("FUND_NAV_HISTORY".equals(job.getJobType())
                    && quoteMapper.hasMissingFundReturns(product.getId()));
        if (initialLoad && knownStartDate(product) == null) {
            AnalysisServiceClient.ResolvedProduct resolved = analysisClient.resolveProduct(
                    product.getProductType(), product.getCode());
            if (resolved != null && resolved.inceptionDate() != null) {
                product.setInceptionDate(resolved.inceptionDate());
            }
        }
        LocalDate requestedStart = initialLoad
                ? initialStart(product)
                : product.getHistoryEndDate();
        LocalDate requestedEnd = LocalDate.now(clock);
        if (requestedStart.isAfter(requestedEnd)) {
            requestedStart = requestedEnd;
        }

        InvestmentDataQualityService.Evaluation evaluation = dataQualityService.resolve(
                product, requestedStart, requestedEnd, true);
        if (evaluation.blocked()) {
            product.setHistoryCoverageComplete(false);
            productMapper.updateById(product);
            throw new QualityBlockedException();
        }

        dataQualityService.claim(evaluation);
        syncWorker.persistDailyQuotes(product, evaluation.response());

        LocalDate sampleStart = evaluation.snapshot().getSampleStartDate();
        LocalDate sampleEnd = evaluation.snapshot().getSampleEndDate();
        boolean coverageComplete = coverageComplete(
                product, initialLoad, sampleStart, sampleEnd);
        if (evaluation.failedRule("STOCK_UNEXPLAINED_TRADING_GAPS")
                || evaluation.failedRule("FUND_UNEXPLAINED_NAV_GAPS")) {
            coverageComplete = false;
        }
        LocalDate latestKnownDate = quoteMapper.latestTradeDate(product.getId());
        if (latestKnownDate != null && sampleEnd != null && latestKnownDate.isAfter(sampleEnd)
                && !latestKnownDate.equals(LocalDate.now(clock))) {
            coverageComplete = false;
        }
        if ("FUND_NAV_HISTORY".equals(job.getJobType())
                && quoteMapper.hasMissingFundReturns(product.getId())) {
            coverageComplete = false;
        }
        product.setHistoryStartDate(earlier(product.getHistoryStartDate(), sampleStart));
        product.setHistoryEndDate(later(product.getHistoryEndDate(), sampleEnd));
        product.setHistoryCoverageComplete(coverageComplete);
        productMapper.updateById(product);

        long persistedCount = quoteMapper.selectCount(
                new LambdaQueryWrapper<ProductDailyQuote>()
                        .eq(ProductDailyQuote::getProductId, product.getId()));
        int recordCount = persistedCount > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) persistedCount;
        return new PreparationResult(
                recordCount,
                requestedStart,
                sampleStart,
                sampleEnd,
                coverageComplete,
                evaluation.datasetVersion());
    }

    private static boolean isAssetHistoryJob(InvestmentDataJob job) {
        return job != null && ("STOCK_HISTORY".equals(job.getJobType())
                || "FUND_NAV_HISTORY".equals(job.getJobType()));
    }

    private static LocalDate initialStart(InvestmentProduct product) {
        LocalDate start = knownStartDate(product);
        if (start == null) {
            throw new IllegalStateException("产品历史起始日期缺失，无法校验完整历史数据");
        }
        return start;
    }

    private static LocalDate knownStartDate(InvestmentProduct product) {
        if ("STOCK".equals(product.getProductType()) && product.getListingDate() != null) {
            return product.getListingDate();
        }
        return product.getInceptionDate();
    }

    private static boolean coverageComplete(
            InvestmentProduct product,
            boolean initialLoad,
            LocalDate sampleStart,
            LocalDate sampleEnd) {
        if (sampleStart == null || sampleEnd == null || sampleEnd.isBefore(sampleStart)) {
            return false;
        }
        if (!initialLoad) {
            return true;
        }
        LocalDate start = knownStartDate(product);
        return start != null && !sampleStart.isAfter(start.plusDays(INCEPTION_TOLERANCE_DAYS));
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
