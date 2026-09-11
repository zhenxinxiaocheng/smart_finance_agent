package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class QuantBenchmarkPreparationService {
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final QuantBenchmarkProfileService benchmarkProfileService;

    public QuantBenchmarkPreparationService(InvestmentProductMapper productMapper,
                                             ProductDailyQuoteMapper quoteMapper,
                                             QuantBenchmarkProfileService benchmarkProfileService) {
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.benchmarkProfileService = benchmarkProfileService;
    }

    public int prepare(InvestmentDataJob job) {
        if (!"BENCHMARK_HISTORY".equals(job.getJobType())) {
            throw new IllegalArgumentException("任务不是官方基准数据准备任务");
        }
        InvestmentProduct product = productMapper.selectById(job.getProductId());
        if (product == null) {
            throw new IllegalStateException("未找到需要准备官方基准的投资产品");
        }
        List<ProductDailyQuote> quotes = quoteMapper.selectList(
                new LambdaQueryWrapper<ProductDailyQuote>()
                        .eq(ProductDailyQuote::getProductId, product.getId())
                        .orderByAsc(ProductDailyQuote::getTradeDate)
        );
        if (quotes.size() < 2) {
            throw new IllegalStateException("资产历史行情不足，无法确定官方基准获取区间");
        }
        LocalDate targetStartDate = quotes.get(0).getTradeDate();
        LocalDate endDate = quotes.get(quotes.size() - 1).getTradeDate();
        QuantBenchmarkProfileService.ResolvedBenchmark benchmark =
                benchmarkProfileService.resolve(
                        product.getProductType(),
                        product.getCode(),
                        endDate,
                        targetStartDate,
                        endDate
                );
        if (!benchmark.available()) {
            throw new IllegalStateException(benchmark.failureSummary());
        }
        return benchmark.records().size();
    }
}
