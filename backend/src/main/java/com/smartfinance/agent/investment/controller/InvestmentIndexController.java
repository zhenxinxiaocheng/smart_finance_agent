package com.smartfinance.agent.investment.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.investment.dto.InvestmentIndexCreateRequest;
import com.smartfinance.agent.investment.dto.InvestmentIndexReorderRequest;
import com.smartfinance.agent.investment.dto.InvestmentIndexView;
import com.smartfinance.agent.investment.service.InvestmentIndexService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/investment/indexes")
public class InvestmentIndexController {

    private final InvestmentIndexService service;

    public InvestmentIndexController(InvestmentIndexService service) {
        this.service = service;
    }

    @GetMapping("/watchlist")
    public Result<List<InvestmentIndexView>> list(@RequestAttribute Long userId) {
        return Result.success(service.list(userId));
    }

    @GetMapping("/search")
    public Result<List<InvestmentIndexView>> search(@RequestParam String keyword) {
        return Result.success(service.search(keyword));
    }

    @PostMapping("/watchlist")
    public Result<InvestmentIndexView> add(@RequestAttribute Long userId,
                                           @Valid @RequestBody InvestmentIndexCreateRequest request) {
        return Result.success(service.add(userId, request));
    }

    @PutMapping("/watchlist/order")
    public Result<Void> reorder(@RequestAttribute Long userId,
                                @Valid @RequestBody InvestmentIndexReorderRequest request) {
        service.reorder(userId, request);
        return Result.success();
    }

    @DeleteMapping("/watchlist/{id}")
    public Result<Void> remove(@RequestAttribute Long userId, @PathVariable Long id) {
        service.remove(userId, id);
        return Result.success();
    }
}
