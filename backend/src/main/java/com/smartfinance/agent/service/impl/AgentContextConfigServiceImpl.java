package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.dto.AgentContextConfig;
import com.smartfinance.agent.context.ModelContextWindowRegistry;
import com.smartfinance.agent.service.AgentContextConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentContextConfigServiceImpl implements AgentContextConfigService {

    private final ModelContextWindowRegistry modelContextWindowRegistry;
    private final String modelName;
    private final int configuredMaxTokens;
    private final int reservedOutputTokens;

    public AgentContextConfigServiceImpl(ModelContextWindowRegistry modelContextWindowRegistry,
                                         @Value("${langchain4j.dashscope.chat-model.model-name:}") String modelName,
                                         @Value("${agent.context.max-tokens:12000}") int configuredMaxTokens,
                                         @Value("${agent.context.reserved-output-tokens:1500}") int reservedOutputTokens) {
        this.modelContextWindowRegistry = modelContextWindowRegistry;
        this.modelName = modelName;
        this.configuredMaxTokens = configuredMaxTokens;
        this.reservedOutputTokens = reservedOutputTokens;
    }

    @Override
    public AgentContextConfig getCurrentConfig(Long userId) {
        int maxTokens = modelContextWindowRegistry.resolve(modelName, configuredMaxTokens);
        int effectiveBudget = Math.max(1, maxTokens - reservedOutputTokens);
        return new AgentContextConfig(maxTokens, reservedOutputTokens, effectiveBudget);
    }
}
