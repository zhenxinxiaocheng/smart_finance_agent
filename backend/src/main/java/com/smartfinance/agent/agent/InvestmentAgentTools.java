package com.smartfinance.agent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.investment.service.InvestmentService;
import com.smartfinance.agent.investment.quant.QuantService;
import org.springframework.stereotype.Component;

@Component
public class InvestmentAgentTools {

    private final InvestmentService investmentService;
    private final QuantService quantService;
    private final ObjectMapper objectMapper;

    public InvestmentAgentTools(InvestmentService investmentService,
                                QuantService quantService,
                                ObjectMapper objectMapper) {
        this.investmentService = investmentService;
        this.quantService = quantService;
        this.objectMapper = objectMapper;
    }

    public String overview() {
        return json(investmentService.overview(requiredUserId()));
    }

    public String positions() {
        return json(investmentService.listPositions(requiredUserId(), null, null, null));
    }

    public String analysis() {
        return json(investmentService.analysis(requiredUserId()));
    }

    public String dataQuality() {
        return json(investmentService.dataQuality(requiredUserId()));
    }

    public String recommendations() {
        return json(investmentService.recommendations(requiredUserId()));
    }

    public String quantSignal(Long assetId, String horizonCode) {
        if (assetId == null || assetId < 1) throw new IllegalArgumentException("量化信号需要有效资产编号");
        return json(quantService.latestAnalysis(requiredUserId(), assetId, horizonCode));
    }

    public String quantStrategyStatus() {
        return json(quantService.strategyStatus(requiredUserId()));
    }

    public String paperAccount() {
        return json(quantService.paperAccount(requiredUserId()));
    }

    private Long requiredUserId() {
        Long userId = UserIdContext.get();
        if (userId == null) {
            throw new IllegalStateException("投资工具缺少用户上下文");
        }
        return userId;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("投资数据序列化失败", e);
        }
    }
}
