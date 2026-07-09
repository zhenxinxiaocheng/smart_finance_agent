package com.smartfinance.agent.context;

import com.smartfinance.agent.service.RagKnowledgeService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagContextProvider implements AgentContextProvider {

    private final RagKnowledgeService ragKnowledgeService;

    public RagContextProvider(RagKnowledgeService ragKnowledgeService) {
        this.ragKnowledgeService = ragKnowledgeService;
    }

    @Override
    public List<ContextBlock> provide(ContextRequest request) {
        String rag = ragKnowledgeService.retrieveRelevantContext(request.userMessage());
        if (rag == null || rag.isBlank()) {
            return List.of();
        }
        return List.of(ContextBlock.builder()
                .key("rag-knowledge")
                .displayName("RAG 知识")
                .sourceType(ContextSourceType.RAG)
                .content("""
                        系统知识库：下面是 RAG 检索到的理财知识片段，仅用于回答通用理财、预算、保险、税务、投资原则类问题。
                        这些知识不是用户账单、预算或实时行情；涉及用户个人数据或实时信息时，必须优先调用工具。
                        %s
                        """.formatted(rag).trim())
                .priority(60)
                .required(false)
                .relevanceScore(0.7)
                .displayPolicy(ContextDisplayPolicy.NAME_ONLY)
                .overflowStrategy(ContextOverflowStrategy.SUMMARIZE)
                .build());
    }
}
