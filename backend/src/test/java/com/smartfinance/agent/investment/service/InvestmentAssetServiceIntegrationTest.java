package com.smartfinance.agent.service;

import com.smartfinance.agent.investment.dto.InvestmentAssetCreateRequest;
import com.smartfinance.agent.investment.dto.InvestmentAssetUpdateRequest;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.InvestmentAssetService;
import com.smartfinance.agent.investment.service.InvestmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = ServiceIntegrationTestConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:investment_asset_service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
@Import(com.smartfinance.agent.investment.service.InvestmentAssetServiceImpl.class)
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class InvestmentAssetServiceIntegrationTest {

    @Autowired
    private InvestmentAssetService assetService;
    @Autowired
    private InvestmentService investmentService;
    @Autowired
    private ProductDailyQuoteMapper quoteMapper;
    @MockBean
    private AnalysisServiceClient analysisServiceClient;

    @BeforeEach
    void setUpResolver() {
        when(analysisServiceClient.resolveProduct(anyString(), anyString()))
                .thenReturn(new AnalysisServiceClient.ResolvedProduct(
                        "STOCK", "600519", "贵州茅台", "SSE", "CNY", "AKSHARE",
                        LocalDate.of(2026, 7, 10), new BigDecimal("1204.98"),
                        new BigDecimal("1190.00"), new BigDecimal("14.98"), new BigDecimal("1.2588"),
                        null, null, null, null, null, null, null, null, List.of()));
        when(analysisServiceClient.realtimeQuote(anyString(), anyString()))
                .thenReturn(new AnalysisServiceClient.RealtimeQuote(
                        "600519", "SSE", new BigDecimal("1198.72"),
                        LocalDate.of(2026, 7, 13), LocalDateTime.of(2026, 7, 13, 11, 23, 30),
                        new BigDecimal("1200"), new BigDecimal("-1.28"), new BigDecimal("-0.1067"),
                        new BigDecimal("1201"), new BigDecimal("1210"), new BigDecimal("1190"),
                        new BigDecimal("1000000"), new BigDecimal("1200000000"),
                        new BigDecimal("0.88"), new BigDecimal("1.23"), new BigDecimal("1.67"),
                        "TENCENT", List.of()));
    }

    @Test
    void createWithCodeOnly_shouldResolveProductWithoutHoldingFields() {
        var asset = assetService.create(7L, createRequest("STOCK", "600519"));

        assertThat(asset.getName()).isEqualTo("贵州茅台");
        assertThat(asset.getQuantity()).isNull();
        assertThat(asset.getAverageCost()).isNull();
        assertThat(assetService.list(7L)).extracting("id").containsExactly(asset.getId());
        assertThatThrownBy(() -> assetService.get(8L, asset.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateAndDelete_shouldUseReversalAndReplacementTransactions() {
        var asset = assetService.create(7L, createRequest("STOCK", "600519"));

        assetService.update(7L, asset.getId(), updateRequest("10", "1500", "首次录入"));
        var updated = assetService.update(7L, asset.getId(), updateRequest("8", "1480", "调整持仓"));

        assertThat(updated.getQuantity()).isEqualByComparingTo("8");
        assertThat(updated.getAverageCost()).isEqualByComparingTo("1480");
        assertThat(updated.getMarketValueCny()).isEqualByComparingTo("9639.84");
        assertThat(updated.getUnrealizedPnlCny()).isEqualByComparingTo("-2200.16");
        assertThat(updated.getHoldingReturnPercent()).isEqualByComparingTo("-18.58243200");
        assertThat(investmentService.listTransactions(7L, updated.getAccountId(), 20))
                .extracting("eventType").containsExactly("TRANSFER_IN", "REVERSAL", "TRANSFER_IN");

        assetService.delete(7L, asset.getId());

        assertThat(assetService.list(7L)).isEmpty();
        assertThat(investmentService.listTransactions(7L, updated.getAccountId(), 20))
                .extracting("eventType").containsExactly("REVERSAL", "TRANSFER_IN", "REVERSAL", "TRANSFER_IN");
    }

    @Test
    void sync_shouldRefreshTheSelectedAssetImmediately() {
        var asset = assetService.create(7L, createRequest("STOCK", "600519"));

        var synced = assetService.sync(7L, asset.getId());

        assertThat(synced.getSyncStatus()).isEqualTo("SUCCESS");
        assertThat(synced.getLatestPrice()).isEqualByComparingTo("1198.72");
        assertThat(synced.getDataDate()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(synced.getFetchedAt()).isEqualTo(LocalDateTime.of(2026, 7, 13, 11, 23, 30));
        assertThat(synced.getChangeAmount()).isEqualByComparingTo("-1.28");
        assertThat(synced.getChangePercent()).isEqualByComparingTo("-0.1067");
        assertThat(synced.getTurnoverRate()).isEqualByComparingTo("0.88");
        assertThat(synced.getVolumeRatio()).isEqualByComparingTo("1.23");
    }

    @Test
    void list_shouldDeriveChangeMetricsForExistingHistoryRows() {
        var asset = assetService.create(7L, createRequest("STOCK", "600519"));
        insertHistoryQuote(asset.getProductId(), LocalDate.of(2026, 7, 15), "100");
        insertHistoryQuote(asset.getProductId(), LocalDate.of(2026, 7, 16), "110");

        var listed = assetService.list(7L).get(0);

        assertThat(listed.getLatestPrice()).isEqualByComparingTo("110");
        assertThat(listed.getPreviousClose()).isEqualByComparingTo("100");
        assertThat(listed.getChangeAmount()).isEqualByComparingTo("10");
        assertThat(listed.getChangePercent()).isEqualByComparingTo("10");
    }

    private void insertHistoryQuote(Long productId, LocalDate tradeDate, String close) {
        ProductDailyQuote quote = new ProductDailyQuote();
        quote.setProductId(productId);
        quote.setTradeDate(tradeDate);
        quote.setOpenPrice(new BigDecimal(close));
        quote.setHighPrice(new BigDecimal(close));
        quote.setLowPrice(new BigDecimal(close));
        quote.setClosePrice(new BigDecimal(close));
        quote.setAdjustType("QFQ");
        quote.setSource("TEST");
        quote.setAdapterVersion("1");
        quote.setSyncedAt(LocalDateTime.of(2026, 7, 16, 15, 0));
        quoteMapper.insert(quote);
    }

    private static InvestmentAssetCreateRequest createRequest(String type, String code) {
        InvestmentAssetCreateRequest request = new InvestmentAssetCreateRequest();
        request.setProductType(type);
        request.setCode(code);
        return request;
    }

    private static InvestmentAssetUpdateRequest updateRequest(String quantity, String cost, String note) {
        InvestmentAssetUpdateRequest request = new InvestmentAssetUpdateRequest();
        request.setQuantity(new BigDecimal(quantity));
        request.setAverageCost(new BigDecimal(cost));
        request.setNote(note);
        return request;
    }
}
