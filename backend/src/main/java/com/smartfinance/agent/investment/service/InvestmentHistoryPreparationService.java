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

    static final LocalDate EARLIEST_PROVIDER_DATE = LocalDate.of(1990, 1, 1);
    private static final ZoneId RUNTIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int INCEPTION_TOLERANCE_DAYS = 14;

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
    private final Clock clock;

    @Autowired
    public InvestmentHistoryPreparationService(
            InvestmentProductMapper productMapper,
            ProductDailyQuoteMapper quoteMapper,
            InvestmentDataQualityService dataQualityService,
            InvestmentSyncWorker syncWorker,
            FundClassificationService classificationService) {
        this(productMapper, quoteMapper, dataQualityService, syncWorker, classificationService,
                Clock.system(RUNTIME_ZONE));
    }

    InvestmentHistoryPreparationService(
            InvestmentProductMapper productMapper,
            ProductDailyQuoteMapper quoteMapper,
            InvestmentDataQualityService dataQualityService,
            InvestmentSyncWorker syncWorker,
            FundClassificationService classificationService,
            Clock clock) {
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.dataQualityService = dataQualityService;
        this.syncWorker = syncWorker;
        this.classificationService = classificationService;
        this.clock = clock;
    }

    @Transactional
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
            throw new IllegalStateException("全量历史数据完整性校验未通过");
        }

        dataQualityService.claim(evaluation);
        syncWorker.persistDailyQuotes(product, evaluation.response());

        LocalDate sampleStart = evaluation.snapshot().getSampleStartDate();
        LocalDate sampleEnd = evaluation.snapshot().getSampleEndDate();
        boolean coverageComplete = coverageComplete(
                product, initialLoad, sampleStart, sampleEnd);
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
        return product.getInceptionDate() == null
                ? EARLIEST_PROVIDER_DATE
                : product.getInceptionDate();
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
        LocalDate inceptionDate = product.getInceptionDate();
        return inceptionDate == null
                || !sampleStart.isAfter(inceptionDate.plusDays(INCEPTION_TOLERANCE_DAYS));
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
