package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.domain.FundClassificationPolicy;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.quant.BenchmarkProfile;
import com.smartfinance.agent.investment.quant.BenchmarkProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Service
public class FundClassificationService {

    private static final ZoneId RUNTIME_ZONE = ZoneId.of("Asia/Shanghai");

    private final InvestmentProductMapper productMapper;
    private final BenchmarkProfileMapper benchmarkMapper;
    private final AnalysisServiceClient analysisClient;
    private final Clock clock;

    @Autowired
    public FundClassificationService(
            InvestmentProductMapper productMapper,
            BenchmarkProfileMapper benchmarkMapper,
            AnalysisServiceClient analysisClient) {
        this(productMapper, benchmarkMapper, analysisClient, Clock.system(RUNTIME_ZONE));
    }

    FundClassificationService(
            InvestmentProductMapper productMapper,
            BenchmarkProfileMapper benchmarkMapper,
            AnalysisServiceClient analysisClient,
            Clock clock) {
        this.productMapper = productMapper;
        this.benchmarkMapper = benchmarkMapper;
        this.analysisClient = analysisClient;
        this.clock = clock;
    }

    public InvestmentProduct enrichIfMissing(InvestmentProduct product) {
        if (!isFund(product) || FundClassificationPolicy.known(product.getFundCategory())) {
            return product;
        }
        if (applyCuratedProfile(product)) {
            productMapper.updateById(product);
            return product;
        }
        AnalysisServiceClient.ResolvedProduct resolved = analysisClient.resolveProduct(
                product.getProductType(), product.getCode());
        if (applyProviderClassification(product, resolved)) {
            productMapper.updateById(product);
        }
        return product;
    }

    public boolean applyResolved(
            InvestmentProduct product,
            AnalysisServiceClient.ResolvedProduct resolved) {
        if (!isFund(product) || resolved == null) {
            return false;
        }
        if (!Objects.equals(product.getProductType(), resolved.productType())
                || !Objects.equals(product.getCode(), resolved.code())) {
            throw new IllegalArgumentException("基金分类响应与目标产品不一致");
        }
        if (applyCuratedProfile(product)) {
            return true;
        }
        return applyProviderClassification(product, resolved);
    }

    private boolean applyProviderClassification(
            InvestmentProduct product,
            AnalysisServiceClient.ResolvedProduct resolved) {
        boolean replace = FundClassificationPolicy.shouldReplace(
                product.getFundCategory(),
                product.getClassificationSource(),
                product.getClassificationVersion(),
                resolved.fundCategory(),
                resolved.classificationSource(),
                resolved.classificationVersion());
        if (!replace) {
            return false;
        }
        product.setFundTypeRaw(resolved.fundTypeRaw());
        product.setFundCategory(resolved.fundCategory());
        product.setClassificationSource(resolved.classificationSource());
        product.setClassificationVersion(resolved.classificationVersion());
        product.setClassifiedAt(LocalDateTime.now(clock));
        return true;
    }

    private boolean applyCuratedProfile(InvestmentProduct product) {
        if (FundClassificationPolicy.known(product.getFundCategory())) {
            return false;
        }
        LocalDate today = LocalDate.now(clock);
        BenchmarkProfile profile = benchmarkMapper.selectOne(
                new LambdaQueryWrapper<BenchmarkProfile>()
                        .eq(BenchmarkProfile::getProductType, product.getProductType())
                        .eq(BenchmarkProfile::getProductCode, product.getCode())
                        .eq(BenchmarkProfile::getActive, true)
                        .le(BenchmarkProfile::getEffectiveFrom, today)
                        .and(query -> query.isNull(BenchmarkProfile::getEffectiveTo)
                                .or()
                                .ge(BenchmarkProfile::getEffectiveTo, today))
                        .orderByDesc(BenchmarkProfile::getEffectiveFrom)
                        .orderByDesc(BenchmarkProfile::getId)
                        .last("LIMIT 1"));
        if (profile == null || !FundClassificationPolicy.known(profile.getModelFamily())
                || profile.getSourceVersion() == null || profile.getSourceVersion().isBlank()) {
            return false;
        }
        product.setFundCategory(profile.getModelFamily());
        product.setClassificationSource("CURATED_BENCHMARK_PROFILE");
        product.setClassificationVersion(profile.getSourceVersion());
        product.setClassifiedAt(LocalDateTime.now(clock));
        return true;
    }

    private static boolean isFund(InvestmentProduct product) {
        return product != null && "MUTUAL_FUND".equals(product.getProductType());
    }
}
