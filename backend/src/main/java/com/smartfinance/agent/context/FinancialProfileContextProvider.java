package com.smartfinance.agent.context;

import com.smartfinance.agent.service.FinancialProfileService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FinancialProfileContextProvider implements AgentContextProvider {

    private final FinancialProfileService financialProfileService;

    public FinancialProfileContextProvider(FinancialProfileService financialProfileService) {
        this.financialProfileService = financialProfileService;
    }

    @Override
    public List<ContextBlock> provide(ContextRequest request) {
        String profile = financialProfileService.buildAgentContext(request.userId());
        if (profile == null || profile.isBlank()) {
            return List.of();
        }
        return List.of(ContextBlock.builder()
                .key("financial-profile")
                .displayName("财务画像")
                .sourceType(ContextSourceType.PROFILE)
                .content("""
                        系统资料：下面是用户主动维护的长期财务画像。回答预算、省钱、储蓄、风险相关问题时必须优先参考；不要声称这是实时流水。
                        %s
                        """.formatted(profile).trim())
                .priority(85)
                .required(false)
                .relevanceScore(0.8)
                .displayPolicy(ContextDisplayPolicy.HIDDEN)
                .overflowStrategy(ContextOverflowStrategy.SUMMARIZE)
                .build());
    }
}
