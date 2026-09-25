package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentDataQualitySnapshot;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UnifiedMarketDataIngestionServiceTest {
    private final InvestmentDataQualityService quality = mock(InvestmentDataQualityService.class);
    private final ProductDailyQuotePersistenceService writer = mock(ProductDailyQuotePersistenceService.class);
    private final AnalysisServiceClient analysis = mock(AnalysisServiceClient.class);
    private final QuoteSeriesCoverageService coverage = mock(QuoteSeriesCoverageService.class);
    private final UnifiedMarketDataIngestionService service =
            new UnifiedMarketDataIngestionService(quality, writer, analysis, coverage);

    @Test
    void stockResearchUsesExplicitQfqQualityAndSingleWriter() {
        InvestmentProduct product = new InvestmentProduct();
        product.setId(9L); product.setProductType("STOCK"); product.setMarket("SSE");
        LocalDate day = LocalDate.of(2024, 1, 2);
        InvestmentDataQualitySnapshot snapshot = new InvestmentDataQualitySnapshot();
        snapshot.setDecision("ALLOW"); snapshot.setDatasetVersion("verified");
        Map<String, Object> response = Map.of("provider", "TEST", "adapterVersion", "1",
                "records", List.of(Map.of("data_date", day.toString(), "close", "10")));
        when(quality.resolve(product, day, day, "QFQ", true)).thenReturn(
                new InvestmentDataQualityService.Evaluation(snapshot, response,
                        (List<Map<String, Object>>) response.get("records"), List.of()));

        service.ingest(product, day, day, "QFQ", day);

        verify(quality).claim(any());
        verify(writer).persistHistory(product, response, "QFQ");
        verify(coverage).record(eq(9L), eq("QFQ"), eq("PRICE"), eq(day), eq(day),
                eq(true), eq("TEST"), eq("verified"), any());
        verifyNoInteractions(analysis);
    }

    @Test
    void blockedQualityNeverWritesQuotes() {
        InvestmentProduct product = new InvestmentProduct();
        product.setId(9L); product.setProductType("STOCK"); product.setMarket("SSE");
        LocalDate day = LocalDate.of(2024, 1, 2);
        InvestmentDataQualitySnapshot snapshot = new InvestmentDataQualitySnapshot();
        snapshot.setDecision("BLOCK"); snapshot.setDatasetVersion("blocked");
        when(quality.resolve(product, day, day, "QFQ", true)).thenReturn(
                new InvestmentDataQualityService.Evaluation(snapshot, Map.of("records", List.of()),
                        List.of(), List.of()));

        assertThatThrownBy(() -> service.ingest(product, day, day, "QFQ", day))
                .isInstanceOf(UnifiedMarketDataIngestionService.QualityBlockedException.class);
        verifyNoInteractions(writer, coverage);
    }
}
