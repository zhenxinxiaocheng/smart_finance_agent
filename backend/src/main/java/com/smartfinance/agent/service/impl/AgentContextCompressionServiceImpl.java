package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.context.ContextSummaryGenerator;
import com.smartfinance.agent.context.ContextTokenEstimator;
import com.smartfinance.agent.entity.AgentContextSummary;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.AgentContextSummaryMapper;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.AgentContextCompressionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AgentContextCompressionServiceImpl implements AgentContextCompressionService {

    private static final int HISTORY_LIMIT = 30;

    private final ChatMessageMapper chatMessageMapper;
    private final AgentContextSummaryMapper summaryMapper;
    private final ContextTokenEstimator tokenEstimator;
    private final ContextSummaryGenerator summaryGenerator;

    public AgentContextCompressionServiceImpl(ChatMessageMapper chatMessageMapper,
                                              AgentContextSummaryMapper summaryMapper,
                                              ContextTokenEstimator tokenEstimator,
                                              ContextSummaryGenerator summaryGenerator) {
        this.chatMessageMapper = chatMessageMapper;
        this.summaryMapper = summaryMapper;
        this.tokenEstimator = tokenEstimator;
        this.summaryGenerator = summaryGenerator;
    }

    @Override
    @Transactional
    public AgentContextSummary compressConversation(Long userId, String traceId, String scope) {
        throw new IllegalArgumentException("conversationId is required");
    }

    @Override
    @Transactional
    public AgentContextSummary compressConversation(Long userId, Long conversationId, String traceId, String scope) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId is required");
        }
        List<ChatMessage> latestDesc = chatMessageMapper.selectByTraceOrRecentAndConversation(
                userId, conversationId, traceId, HISTORY_LIMIT);
        List<ChatMessage> ordered = new java.util.ArrayList<>();
        for (int i = latestDesc.size() - 1; i >= 0; i--) {
            ordered.add(latestDesc.get(i));
        }
        String source = ordered.stream()
                .map(message -> safe(message.getRole()) + ": " + safe(message.getContent()))
                .collect(Collectors.joining("\n"));
        if (source.isBlank()) {
            throw new IllegalArgumentException("当前对话暂无可压缩内容");
        }
        String summary = summaryGenerator.summarize("对话历史", "手动压缩当前对话", source, 800);

        AgentContextSummary entity = new AgentContextSummary();
        entity.setUserId(userId);
        entity.setConversationId(conversationId);
        entity.setTraceId(traceId);
        entity.setScope(scope == null || scope.isBlank() ? "MANUAL_COMPRESS" : scope);
        entity.setSummary(summary);
        entity.setSourceRefs(traceId == null || traceId.isBlank() ? "recent-chat" : traceId);
        entity.setSourceHash(summaryGenerator.sourceHash(source));
        entity.setCoveredFromMessageId(coveredFrom(ordered));
        entity.setCoveredUntilMessageId(coveredUntil(ordered));
        entity.setOriginalTokens(tokenEstimator.estimate(source));
        entity.setCompressedTokens(tokenEstimator.estimate(summary));
        entity.setDeleted(0);
        summaryMapper.insert(entity);
        return entity;
    }

    @Override
    public AgentContextSummary latestSummary(Long userId) {
        if (userId == null) return null;
        return summaryMapper.selectLatestByUser(userId);
    }

    @Override
    public AgentContextSummary latestByScope(Long userId, String scope) {
        if (userId == null || scope == null) return null;
        return summaryMapper.selectLatestByScope(userId, scope);
    }

    @Override
    public AgentContextSummary latestConversationSummary(Long userId) {
        if (userId == null) return null;
        return summaryMapper.selectLatestConversationSummary(userId);
    }

    @Override
    public AgentContextSummary latestConversationSummary(Long userId, Long conversationId) {
        if (userId == null) return null;
        if (conversationId == null) {
            return latestConversationSummary(userId);
        }
        return summaryMapper.selectLatestConversationSummaryByConversation(userId, conversationId);
    }

    @Override
    public AgentContextSummary latestByScopeAndSourceHash(Long userId, String scope, String sourceHash) {
        if (userId == null || scope == null || sourceHash == null || sourceHash.isBlank()) return null;
        return summaryMapper.selectLatestByScopeAndSourceHash(userId, scope, sourceHash);
    }

    @Override
    public AgentContextSummary latestByScopeAndSourceHash(Long userId, Long conversationId, String scope, String sourceHash) {
        if (userId == null || scope == null || sourceHash == null || sourceHash.isBlank()) return null;
        if (conversationId == null) {
            return latestByScopeAndSourceHash(userId, scope, sourceHash);
        }
        return summaryMapper.selectLatestByScopeAndSourceHashByConversation(userId, conversationId, scope, sourceHash);
    }

    @Override
    @Transactional
    public AgentContextSummary saveAutoSummary(Long userId, String sourceType, String summary,
                                                String sourceRefs, String sourceHash, int originalTokens, int compressedTokens) {
        return saveAutoSummary(userId, null, sourceType, summary, sourceRefs, sourceHash, originalTokens, compressedTokens);
    }

    @Override
    @Transactional
    public AgentContextSummary saveAutoSummary(Long userId, Long conversationId, String sourceType, String summary,
                                                String sourceRefs, String sourceHash, int originalTokens, int compressedTokens) {
        AgentContextSummary entity = new AgentContextSummary();
        entity.setUserId(userId);
        entity.setConversationId(conversationId);
        entity.setTraceId(null);
        entity.setScope("AUTO_" + sourceType);
        entity.setSummary(summary);
        entity.setSourceRefs(sourceRefs == null ? "auto-compress" : sourceRefs);
        entity.setSourceHash(sourceHash);
        entity.setOriginalTokens(originalTokens);
        entity.setCompressedTokens(compressedTokens);
        entity.setDeleted(0);
        summaryMapper.insert(entity);
        return entity;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static Long coveredFrom(List<ChatMessage> messages) {
        return messages.stream()
                .map(ChatMessage::getId)
                .filter(id -> id != null)
                .min(Long::compareTo)
                .orElse(null);
    }

    private static Long coveredUntil(List<ChatMessage> messages) {
        return messages.stream()
                .map(ChatMessage::getId)
                .filter(id -> id != null)
                .max(Long::compareTo)
                .orElse(null);
    }

}
