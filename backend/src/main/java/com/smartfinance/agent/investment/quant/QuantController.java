package com.smartfinance.agent.investment.quant;

import com.smartfinance.agent.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/investment")
public class QuantController {
    private final QuantService service;

    public QuantController(QuantService service) {
        this.service = service;
    }

    @GetMapping("/assets/{id}/quant-analysis")
    public Result<Map<String, Object>> analysis(@RequestAttribute Long userId,
                                                @PathVariable Long id,
                                                @RequestParam String horizonCode) {
        return Result.success(service.latestAnalysis(userId, id, horizonCode));
    }

}
