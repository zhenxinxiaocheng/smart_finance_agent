package com.smartfinance.agent.investment.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.investment.service.MarketDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/quant/v2/market-data")
public class MarketDataController {
    private final MarketDataService service;

    public MarketDataController(MarketDataService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Result<?> overview(@RequestAttribute Long userId) {
        return Result.success(service.overview());
    }

    @GetMapping("/products")
    public Result<?> products(@RequestAttribute Long userId,
                              @RequestParam(required = false) String search,
                              @RequestParam(required = false) String market,
                              @RequestParam(required = false) String marketGroup,
                              @RequestParam(required = false) String assetType,
                              @RequestParam(required = false) String status,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "25") int size) {
        return Result.success(service.products(search, market, marketGroup, assetType, status, page, size));
    }

    @GetMapping("/products/{productId}")
    public Result<?> detail(@RequestAttribute Long userId, @PathVariable long productId) {
        return Result.success(service.detail(productId));
    }

    @GetMapping("/products/{productId}/daily")
    public Result<?> daily(@RequestAttribute Long userId, @PathVariable long productId,
                           @RequestParam(defaultValue = "180") int limit) {
        return Result.success(service.preview(productId, limit));
    }

    @GetMapping("/jobs")
    public Result<?> jobs(@RequestAttribute Long userId) {
        return Result.success(service.jobs());
    }

    @GetMapping("/universes/{id}/members")
    public Result<?> members(@RequestAttribute Long userId, @PathVariable String id,
                             @RequestParam LocalDate asOfDate) {
        return Result.success(service.getUniverseMembers(userId, id, asOfDate));
    }
}
