package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.TransactionStatisticsRow;
import com.smartfinance.agent.service.FinanceStatisticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/transactions/statistics")
public class FinanceStatisticsController {
    private final FinanceStatisticsService statistics;

    public FinanceStatisticsController(FinanceStatisticsService statistics) { this.statistics = statistics; }

    @GetMapping
    public Result<List<TransactionStatisticsRow>> statistics(@RequestAttribute Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(statistics.statisticsByDateRange(userId, startDate, endDate));
    }
}
