package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChinaTradingCalendarServiceTest {

    @Test
    void shouldUseProviderCalendarAndMoveHolidayToNextTradingDay() {
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        when(client.aShareTradingDates(2026)).thenReturn(List.of(
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 8),
                LocalDate.of(2026, 10, 9)
        ));
        ChinaTradingCalendarService calendar = new ChinaTradingCalendarService(client, runtimeProperties());

        assertThat(calendar.isTradingDay(LocalDate.of(2026, 10, 1))).isFalse();
        assertThat(calendar.nextOrSameTradingDay(LocalDate.of(2026, 10, 1)))
                .isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void shouldUseOfficial2026FallbackWhenCalendarProviderFails() {
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        when(client.aShareTradingDates(2026)).thenThrow(new IllegalStateException("analysis unavailable"));
        ChinaTradingCalendarService calendar = new ChinaTradingCalendarService(client, runtimeProperties());

        assertThat(calendar.nextOrSameTradingDay(LocalDate.of(2026, 10, 1)))
                .isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void configuredFallbackClosedDatesShouldReplaceSourceCodeHolidayLists() {
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        when(client.aShareTradingDates(2026)).thenThrow(new IllegalStateException("analysis unavailable"));
        InvestmentRuntimeProperties properties = runtimeProperties();
        properties.getMarket().setFallbackClosedDates(List.of(LocalDate.of(2026, 3, 2)));
        ChinaTradingCalendarService calendar = new ChinaTradingCalendarService(client, properties);

        assertThat(calendar.nextOrSameTradingDay(LocalDate.of(2026, 3, 2)))
                .isEqualTo(LocalDate.of(2026, 3, 3));
    }

    private static InvestmentRuntimeProperties runtimeProperties() {
        InvestmentRuntimeProperties properties = new InvestmentRuntimeProperties();
        properties.getMarket().setCalendarCacheHours(12);
        properties.getMarket().setCalendarSearchLimitDays(370);
        properties.getMarket().setFallbackClosedDates(List.of(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2),
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6),
                LocalDate.of(2026, 10, 7)));
        return properties;
    }
}
