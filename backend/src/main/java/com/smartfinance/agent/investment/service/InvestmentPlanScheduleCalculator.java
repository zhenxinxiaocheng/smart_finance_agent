package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentPlan;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Component
public class InvestmentPlanScheduleCalculator {

    private final ChinaTradingCalendarService tradingCalendar;

    public InvestmentPlanScheduleCalculator(ChinaTradingCalendarService tradingCalendar) {
        this.tradingCalendar = tradingCalendar;
    }

    public LocalDate normalize(LocalDate date) {
        return tradingCalendar.nextOrSameTradingDay(date);
    }

    public LocalDate restartDate(InvestmentPlan plan, LocalDate today) {
        LocalDate nominal = switch (plan.getFrequency()) {
            case "DAILY" -> today;
            case "WEEKLY" -> {
                int current = weekday(today);
                int offset = (plan.getExecutionDay() - current + 7) % 7;
                yield today.plusDays(offset);
            }
            case "MONTHLY" -> monthlyOnOrAfter(today, plan.getExecutionDay());
            default -> throw new IllegalArgumentException("不支持的定投频率：" + plan.getFrequency());
        };
        return normalize(nominal);
    }

    public LocalDate nextAfter(InvestmentPlan plan, LocalDate today) {
        LocalDate nominal = nextNominal(plan, plan.getNextExecutionDate());
        LocalDate next = normalize(nominal);
        while (!next.isAfter(today)) {
            nominal = nextNominal(plan, nominal);
            next = normalize(nominal);
        }
        return next;
    }

    private static LocalDate nextNominal(InvestmentPlan plan, LocalDate current) {
        return switch (plan.getFrequency()) {
            case "DAILY" -> current.plusDays(1);
            case "WEEKLY" -> current.plusWeeks(1);
            case "MONTHLY" -> {
                LocalDate month = current.plusMonths(1).withDayOfMonth(1);
                yield month.withDayOfMonth(Math.min(plan.getExecutionDay(), month.lengthOfMonth()));
            }
            default -> throw new IllegalArgumentException("不支持的定投频率：" + plan.getFrequency());
        };
    }

    private static LocalDate monthlyOnOrAfter(LocalDate today, int executionDay) {
        LocalDate month = today.withDayOfMonth(1);
        LocalDate candidate = month.withDayOfMonth(Math.min(executionDay, month.lengthOfMonth()));
        if (!candidate.isBefore(today)) return candidate;
        LocalDate nextMonth = month.plusMonths(1);
        return nextMonth.withDayOfMonth(Math.min(executionDay, nextMonth.lengthOfMonth()));
    }

    private static int weekday(LocalDate date) {
        DayOfWeek value = date.getDayOfWeek();
        return value.getValue();
    }
}
