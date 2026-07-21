package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ChinaTradingCalendarService {

    private final AnalysisServiceClient analysisClient;
    private final InvestmentRuntimeProperties runtimeProperties;
    private final Map<Integer, CacheEntry> cache = new HashMap<>();

    public ChinaTradingCalendarService(AnalysisServiceClient analysisClient,
                                       InvestmentRuntimeProperties runtimeProperties) {
        this.analysisClient = analysisClient;
        this.runtimeProperties = runtimeProperties;
    }

    public boolean isTradingDay(LocalDate date) {
        return tradingDates(date.getYear()).contains(date);
    }

    public LocalDate nextOrSameTradingDay(LocalDate date) {
        LocalDate candidate = date;
        for (int offset = 0; offset < runtimeProperties.getMarket().getCalendarSearchLimitDays(); offset++) {
            if (isTradingDay(candidate)) return candidate;
            candidate = candidate.plusDays(1);
        }
        throw new IllegalStateException("无法找到下一个 A 股交易日");
    }

    private Set<LocalDate> tradingDates(int year) {
        synchronized (cache) {
            CacheEntry current = cache.get(year);
            if (current != null && current.expiresAt().isAfter(Instant.now())) {
                return current.dates();
            }
            Set<LocalDate> dates = loadTradingDates(year);
            Duration cacheTtl = Duration.ofHours(runtimeProperties.getMarket().getCalendarCacheHours());
            cache.put(year, new CacheEntry(dates, Instant.now().plus(cacheTtl)));
            return dates;
        }
    }

    private Set<LocalDate> loadTradingDates(int year) {
        try {
            var providerDates = analysisClient.aShareTradingDates(year);
            if (providerDates != null && !providerDates.isEmpty()) {
                return Set.copyOf(providerDates);
            }
        } catch (RuntimeException exception) {
            log.warn("A-share trading calendar unavailable for {}, using fallback: {}",
                    year, exception.getMessage());
        }
        return fallbackTradingDates(year);
    }

    private Set<LocalDate> fallbackTradingDates(int year) {
        Set<LocalDate> dates = new HashSet<>();
        LocalDate cursor = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        while (!cursor.isAfter(end)) {
            DayOfWeek day = cursor.getDayOfWeek();
            boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
            boolean officiallyClosed = runtimeProperties.getMarket().getFallbackClosedDates().contains(cursor);
            if (!weekend && !officiallyClosed) dates.add(cursor);
            cursor = cursor.plusDays(1);
        }
        return Set.copyOf(dates);
    }

    private record CacheEntry(Set<LocalDate> dates, Instant expiresAt) {
    }
}
