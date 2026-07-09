package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.context.ModelContextWindowRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextConfigServiceImplTest {

    @Test
    void getCurrentConfig_shouldFollowCurrentModelWindow() {
        AgentContextConfigServiceImpl service = new AgentContextConfigServiceImpl(
                new ModelContextWindowRegistry(), "qwen3.6-flash", 12000, 1500);

        var config = service.getCurrentConfig(1L);

        assertThat(config.getMaxTokens()).isEqualTo(131072);
        assertThat(config.getEffectiveBudget()).isEqualTo(129572);
    }
}
