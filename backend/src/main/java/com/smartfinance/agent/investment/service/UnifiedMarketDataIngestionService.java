package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UnifiedMarketDataIngestionService {
    public static final class QualityBlockedException extends IllegalStateException {
        private final InvestmentDataQualityService.Evaluation evaluation;
        QualityBlockedException(InvestmentDataQualityService.Evaluation evaluation) {
            super("历史行情数据质量校验未通过");
            this.evaluation = evaluation;
        }
        public InvestmentDataQualityService.Evaluation evaluation() { return evaluation; }
    }

    public record Result(int recordCount, LocalDate sampleStartDate, LocalDate sampleEndDate,
                         boolean coverageComplete, String datasetVersion, String coverageStatus,
                         InvestmentDataQualityService.Evaluation evaluation) {
        public Result(int recordCount, LocalDate sampleStartDate, LocalDate sampleEndDate,
                      boolean coverageComplete, String datasetVersion, String coverageStatus) {
            this(recordCount, sampleStartDate, sampleEndDate, coverageComplete,
                    datasetVersion, coverageStatus, null);
        }
    }

    private final InvestmentDataQualityService quality;
    private final ProductDailyQuotePersistenceService writer;
    private final AnalysisServiceClient analysis;
    private final QuoteSeriesCoverageService coverage;

    public UnifiedMarketDataIngestionService(InvestmentDataQualityService quality,
                                              ProductDailyQuotePersistenceService writer,
                                              AnalysisServiceClient analysis,
                                              QuoteSeriesCoverageService coverage) {
        this.quality = quality;
        this.writer = writer;
        this.analysis = analysis;
        this.coverage = coverage;
    }

    @Transactional
    public Result ingest(InvestmentProduct product, LocalDate start, LocalDate end,
                         String adjustType, LocalDate expectedStart) {
        return ingest(product, start, end, adjustType, expectedStart, true);
    }

    @Transactional
    public Result ingest(InvestmentProduct product, LocalDate start, LocalDate end,
                         String adjustType, LocalDate expectedStart, boolean fetchFreshData) {
        if (start == null || end == null || end.isBefore(start))
            throw new IllegalArgumentException("历史行情请求区间无效");
        boolean fund = Set.of("MUTUAL_FUND", "FUND").contains(product.getProductType());
        boolean qualityPipeline = fund || "STOCK".equals(product.getProductType());
        if (fund && !"NONE".equals(adjustType))
            throw new IllegalArgumentException("基金历史数据只支持 NONE 口径");
        if (!Set.of("NONE", "QFQ", "HFQ").contains(adjustType))
            throw new IllegalArgumentException("不支持的行情复权口径");

        Map<String, Object> response;
        InvestmentDataQualityService.Evaluation evaluation = null;
        String datasetVersion = null;
        boolean failedGaps = false;
        if (qualityPipeline) {
            evaluation = quality.resolve(product, start, end, adjustType, fetchFreshData);
            if (evaluation.blocked()) throw new QualityBlockedException(evaluation);
            quality.claim(evaluation);
            response = evaluation.response();
            datasetVersion = evaluation.datasetVersion();
            failedGaps = evaluation.failedRule("STOCK_UNEXPLAINED_TRADING_GAPS")
                    || evaluation.failedRule("FUND_UNEXPLAINED_NAV_GAPS");
        } else if (Set.of("ETF", "INDEX").contains(product.getProductType())) {
            response = analysis.marketDailyQuotes(product, start, end, adjustType);
        } else {
            throw new IllegalArgumentException("不支持的历史行情产品类型");
        }
        List<Map<String, Object>> records = records(response);
        validate(records, start, end);
        if (!records.isEmpty()) writer.persistHistory(product, response, adjustType);

        LocalDate sampleStart = records.stream().map(row -> day(row.get("data_date")))
                .min(LocalDate::compareTo).orElse(null);
        LocalDate sampleEnd = records.stream().map(row -> day(row.get("data_date")))
                .max(LocalDate::compareTo).orElse(null);
        String datasetType = fund ? "NAV" : "PRICE";
        QuoteSeriesCoverageService.Coverage previous = coverage.find(product.getId(), adjustType, datasetType);
        boolean startCovered = expectedStart != null && sampleStart != null
                && !sampleStart.isAfter(expectedStart.plusDays(14));
        boolean complete = !failedGaps && (startCovered || previous != null && previous.complete());
        if (!qualityPipeline) complete = false; // ETF/index calendars need explicit verification.
        String provider = response.get("provider") == null ? null : String.valueOf(response.get("provider"));
        QuoteSeriesCoverageService.Coverage current = coverage.record(product.getId(), adjustType,
                datasetType, expectedStart, end, complete, provider, datasetVersion,
                failedGaps ? "UNEXPLAINED_GAPS" : "COVERAGE_UNVERIFIED");
        if (fund) {
            QuoteSeriesCoverageService.Coverage returns = coverage.record(product.getId(), adjustType,
                    "TOTAL_RETURN_INDEX", expectedStart, end, current != null && current.complete(),
                    provider, datasetVersion, "TOTAL_RETURN_MISSING");
            complete = current != null && current.complete() && returns != null && returns.complete();
        } else {
            complete = current != null && current.complete();
        }
        long count = current == null ? records.size() : current.observations();
        return new Result(count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count,
                sampleStart, sampleEnd, complete, datasetVersion,
                current == null ? "INCOMPLETE" : current.status(), evaluation);
    }

    @Transactional
    public void ingestDisplayQuote(InvestmentProduct product, ProductDailyQuote quote) {
        if (product.getId() == null || !product.getId().equals(quote.getProductId())
                || quote.getTradeDate() == null || quote.getClosePrice() == null
                || quote.getClosePrice().signum() <= 0)
            throw new IllegalArgumentException("展示行情缺少有效产品、日期或价格");
        quote.setAdjustType("NONE");
        if (!writer.persistDisplay(quote)) return;
        String datasetType = Set.of("MUTUAL_FUND", "FUND").contains(product.getProductType())
                ? "NAV" : "PRICE";
        QuoteSeriesCoverageService.Coverage previous = coverage.find(product.getId(), "NONE", datasetType);
        LocalDate expectedStart = previous == null ? null : previous.requestedStartDate();
        coverage.record(product.getId(), "NONE", datasetType, expectedStart, quote.getTradeDate(),
                false, quote.getSource(), null, "DISPLAY_ONLY_UNVERIFIED");
        if ("NAV".equals(datasetType))
            coverage.record(product.getId(), "NONE", "TOTAL_RETURN_INDEX", expectedStart,
                    quote.getTradeDate(), false, quote.getSource(), null, "TOTAL_RETURN_MISSING");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> response) {
        if (!(response.get("records") instanceof List<?> rows))
            throw new IllegalStateException("数据源未返回日线列表");
        for (Object row : rows) if (!(row instanceof Map<?, ?>))
            throw new IllegalStateException("行情记录格式无效");
        return (List<Map<String, Object>>) (List<?>) rows;
    }

    private static void validate(List<Map<String, Object>> records, LocalDate start, LocalDate end) {
        Set<LocalDate> seen = new HashSet<>();
        for (Map<String, Object> row : records) {
            LocalDate day = day(row.get("data_date"));
            if (day.isBefore(start) || day.isAfter(end) || !seen.add(day))
                throw new IllegalStateException("行情日期越界或重复");
            Object closeValue = row.get("close") == null ? row.get("nav") : row.get("close");
            BigDecimal close = decimal(closeValue);
            if (close == null || close.signum() <= 0)
                throw new IllegalStateException("行情收盘价无效");
            BigDecimal high = decimal(row.get("high"));
            BigDecimal low = decimal(row.get("low"));
            if (high != null && low != null && (high.compareTo(low) < 0
                    || close.compareTo(high) > 0 || close.compareTo(low) < 0))
                throw new IllegalStateException("行情 OHLC 关系无效");
            BigDecimal totalReturn = decimal(row.get("total_return_index"));
            if (totalReturn != null && totalReturn.signum() <= 0)
                throw new IllegalStateException("基金累计收益指数无效");
        }
    }

    private static LocalDate day(Object value) {
        if (value == null) throw new IllegalStateException("行情日期缺失");
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }
}
