package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.InvestmentDataJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class FundBenchmarkBackfillService {
    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final AnalysisServiceClient analysisClient;
    private final QuantBenchmarkProfileService benchmarkProfileService;
    private final InvestmentDataJobService dataJobService;

    public FundBenchmarkBackfillService(InvestmentAssetMapper assetMapper,
                                        InvestmentProductMapper productMapper,
                                        AnalysisServiceClient analysisClient,
                                        QuantBenchmarkProfileService benchmarkProfileService,
                                        InvestmentDataJobService dataJobService) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.analysisClient = analysisClient;
        this.benchmarkProfileService = benchmarkProfileService;
        this.dataJobService = dataJobService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void configureMissingFundBenchmarks() {
        List<InvestmentAsset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<InvestmentAsset>()
                        .eq(InvestmentAsset::getDeleted, 0)
        );
        for (InvestmentAsset asset : assets) {
            InvestmentProduct product = productMapper.selectById(asset.getProductId());
            if (product == null || !"MUTUAL_FUND".equals(product.getProductType())) {
                continue;
            }
            BenchmarkProfile existing = benchmarkProfileService.configuration(
                    product.getProductType(), product.getCode(), LocalDate.now()
            );
            if (existing != null && Objects.equals(product.getCode(), existing.getProductCode())) {
                continue;
            }
            try {
                AnalysisServiceClient.ResolvedProduct resolved = analysisClient.resolveProduct(
                        product.getProductType(), product.getCode()
                );
                if (benchmarkProfileService.configureImportedFundBenchmark(product, resolved)) {
                    dataJobService.ensureBenchmarkQueued(
                            asset.getUserId(), asset.getId(), product.getId()
                    );
                } else if (resolved.benchmarkResolutionReason() != null) {
                    log.info(
                            "Fund benchmark remains unavailable: code={}, reason={}",
                            product.getCode(), resolved.benchmarkResolutionReason()
                    );
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Fund benchmark backfill failed: code={}, reason={}",
                        product.getCode(), exception.getMessage()
                );
            }
        }
    }
}
