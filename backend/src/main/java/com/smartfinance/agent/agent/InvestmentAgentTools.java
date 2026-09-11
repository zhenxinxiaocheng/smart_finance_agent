package com.smartfinance.agent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.investment.service.InvestmentService;
import org.springframework.stereotype.Component;

@Component
public class InvestmentAgentTools {

    private final InvestmentService investmentService;
    private final ObjectMapper objectMapper;

    public InvestmentAgentTools(InvestmentService investmentService,
                                ObjectMapper objectMapper) {
        this.investmentService = investmentService;
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
