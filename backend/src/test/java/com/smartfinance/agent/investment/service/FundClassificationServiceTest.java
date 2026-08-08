package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.quant.BenchmarkProfile;
import com.smartfinance.agent.investment.quant.BenchmarkProfileMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FundClassificationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-08T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void missingFundClassificationIsResolvedAndPersisted() {
        InvestmentProductMapper mapper = mock(InvestmentProductMapper.class);
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        BenchmarkProfileMapper benchmarkMapper = mock(BenchmarkProfileMapper.class);
        FundClassificationService service = new FundClassificationService(
                mapper, benchmarkMapper, client, CLOCK);
        InvestmentProduct product = product(null, null, null);
        when(client.resolveProduct("MUTUAL_FUND", "000834"))
                .thenReturn(resolved("QDII_INDEX_FUND", "指数型-海外股票"));

        InvestmentProduct result = service.enrichIfMissing(product);

        assertThat(result.getFundCategory()).isEqualTo("QDII_INDEX_FUND");
        assertThat(result.getFundTypeRaw()).isEqualTo("指数型-海外股票");
        assertThat(result.getClassificationSource()).isEqualTo("AKSHARE_FUND_NAME_EM");
        assertThat(result.getClassificationVersion()).isEqualTo("fund-classification-v1");
        assertThat(result.getClassifiedAt()).isEqualTo(LocalDateTime.of(2026, 8, 8, 10, 0));
        verify(mapper).updateById(product);
    }

    @Test
    void existingKnownClassificationSkipsProviderCall() {
        InvestmentProductMapper mapper = mock(InvestmentProductMapper.class);
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        BenchmarkProfileMapper benchmarkMapper = mock(BenchmarkProfileMapper.class);
        FundClassificationService service = new FundClassificationService(
                mapper, benchmarkMapper, client, CLOCK);
        InvestmentProduct product = product(
                "COMMODITY_FUND", "CURATED_PROFILE", "official-2026");

        InvestmentProduct result = service.enrichIfMissing(product);

        assertThat(result).isSameAs(product);
        verifyNoInteractions(client, mapper, benchmarkMapper);
    }

    @Test
    void exactCuratedProfileTakesPriorityForNewFund() {
        InvestmentProductMapper mapper = mock(InvestmentProductMapper.class);
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        BenchmarkProfileMapper benchmarkMapper = mock(BenchmarkProfileMapper.class);
        FundClassificationService service = new FundClassificationService(
                mapper, benchmarkMapper, client, CLOCK);
        InvestmentProduct product = product(null, null, null);
        product.setCode("000218");
        BenchmarkProfile profile = new BenchmarkProfile();
        profile.setModelFamily("COMMODITY_FUND");
        profile.setSourceVersion("OFFICIAL-PRODUCT");
        when(benchmarkMapper.selectOne(any())).thenReturn(profile);

        InvestmentProduct result = service.enrichIfMissing(product);

        assertThat(result.getFundCategory()).isEqualTo("COMMODITY_FUND");
        assertThat(result.getClassificationSource()).isEqualTo("CURATED_BENCHMARK_PROFILE");
        assertThat(result.getClassificationVersion()).isEqualTo("OFFICIAL-PRODUCT");
        assertThat(result.getClassifiedAt()).isEqualTo(LocalDateTime.of(2026, 8, 8, 10, 0));
        verify(mapper).updateById(product);
        verifyNoInteractions(client);
    }

    private static InvestmentProduct product(
            String category, String source, String version) {
        InvestmentProduct product = new InvestmentProduct();
        product.setId(21L);
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setCode("000834");
        product.setFundCategory(category);
        product.setClassificationSource(source);
        product.setClassificationVersion(version);
        return product;
    }

    private static AnalysisServiceClient.ResolvedProduct resolved(
            String category, String rawType) {
        return new AnalysisServiceClient.ResolvedProduct(
                "MUTUAL_FUND", "000834", "纳指联接", "FUND_CN", "CNY", "AKSHARE",
                LocalDate.of(2026, 8, 6), new BigDecimal("1.2345"),
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(), null, rawType, category,
                "AKSHARE_FUND_NAME_EM", "fund-classification-v1");
    }
}
