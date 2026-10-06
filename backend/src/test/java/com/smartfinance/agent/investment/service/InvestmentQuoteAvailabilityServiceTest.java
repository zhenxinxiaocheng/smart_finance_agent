package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentProduct;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InvestmentQuoteAvailabilityServiceTest {
    @Test void sameMarketSharesPublicationBoundWhileEachDelistingDateStillClipsIt() {
        var client=mock(AnalysisServiceClient.class);
        when(client.quoteAvailability(any(),any(),any(),any())).thenReturn(Map.of("targetDate","2026-09-30"));
        var service=new InvestmentQuoteAvailabilityService(client);
        var a=product("SSE","STOCK",null);
        var b=product("SSE","STOCK",null); b.setDelistingDate(LocalDate.of(2026,9,29));
        var date=LocalDate.of(2026,10,6);var now=Instant.parse("2026-10-06T08:00:00Z");
        assertThat(service.target(a,date,now)).isEqualTo(LocalDate.of(2026,9,30));
        assertThat(service.target(b,date,now)).isEqualTo(LocalDate.of(2026,9,29));
    }

    @Test void cachedBoundExpiresSoResumptionAndNewPublicationsCanAdvance() {
        var client=mock(AnalysisServiceClient.class);
        when(client.quoteAvailability(any(),any(),any(),any())).thenReturn(
                Map.of("targetDate","2026-09-29"),Map.of("targetDate","2026-09-30"));
        var service=new InvestmentQuoteAvailabilityService(client);
        var fund=product("FUND_CN","MUTUAL_FUND","QDII_INDEX_FUND");
        var date=LocalDate.of(2026,10,8);var now=Instant.parse("2026-10-08T14:59:40Z");
        assertThat(service.target(fund,date,now)).isEqualTo(LocalDate.of(2026,9,29));
        assertThat(service.target(fund,date,now.plusSeconds(10))).isEqualTo(LocalDate.of(2026,9,29));
        assertThat(service.target(fund,date,now.plusSeconds(70))).isEqualTo(LocalDate.of(2026,9,30));
    }
    @Test void unavailableCalendarCannotAbortApplicationStartup() {
        var client=mock(AnalysisServiceClient.class);
        when(client.quoteAvailability(any(),any(),any(),any())).thenThrow(new IllegalStateException("analysis unavailable"));
        var service=new InvestmentQuoteAvailabilityService(client);
        assertThat(service.target(product("SSE","STOCK",null),LocalDate.of(2026,10,6),Instant.parse("2026-10-06T08:00:00Z")))
                .isEqualTo(LocalDate.of(2026,10,5));
    }
    private static InvestmentProduct product(String market,String type,String category) {
        var p=new InvestmentProduct();p.setMarket(market);p.setProductType(type);p.setFundCategory(category);return p;
    }
}
