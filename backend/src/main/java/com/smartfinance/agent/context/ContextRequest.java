package com.smartfinance.agent.context;

import com.smartfinance.agent.entity.ChatMessage;

import java.util.List;

public record ContextRequest(Long userId,
                             Long conversationId,
                             String userMessage,
                             List<ChatMessage> recentHistory,
                             String traceId,
                             String agentRole,
                             String runPhase) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long userId;
        private Long conversationId;
        private String userMessage;
        private List<ChatMessage> recentHistory = List.of();
        private String traceId;
        private String agentRole = "FINANCE_REACT";
        private String runPhase = "INITIAL";

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder conversationId(Long conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder userMessage(String userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        public Builder recentHistory(List<ChatMessage> recentHistory) {
            this.recentHistory = recentHistory == null ? List.of() : recentHistory;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder agentRole(String agentRole) {
            this.agentRole = agentRole;
            return this;
        }

        public Builder runPhase(String runPhase) {
            this.runPhase = runPhase;
            return this;
        }

        public ContextRequest build() {
            return new ContextRequest(userId, conversationId, userMessage, recentHistory, traceId, agentRole, runPhase);
        }
    }
}
