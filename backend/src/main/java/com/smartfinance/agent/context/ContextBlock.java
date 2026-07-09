package com.smartfinance.agent.context;

import java.time.LocalDateTime;

public record ContextBlock(String key,
                           String displayName,
                           ContextSourceType sourceType,
                           String content,
                           int priority,
                           boolean required,
                           double relevanceScore,
                           ContextDisplayPolicy displayPolicy,
                           ContextOverflowStrategy overflowStrategy,
                           String storageRef,
                           ContextMessageRole messageRole,
                           boolean compressed,
                           LocalDateTime updatedAt) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String key;
        private String displayName;
        private ContextSourceType sourceType = ContextSourceType.SYSTEM;
        private String content = "";
        private int priority = 50;
        private boolean required;
        private double relevanceScore;
        private ContextDisplayPolicy displayPolicy = ContextDisplayPolicy.VISIBLE;
        private ContextOverflowStrategy overflowStrategy = ContextOverflowStrategy.SUMMARIZE;
        private String storageRef;
        private ContextMessageRole messageRole = ContextMessageRole.USER;
        private boolean compressed;
        private LocalDateTime updatedAt;

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder sourceType(ContextSourceType sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder content(String content) {
            this.content = content == null ? "" : content;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder relevanceScore(double relevanceScore) {
            this.relevanceScore = relevanceScore;
            return this;
        }

        public Builder displayPolicy(ContextDisplayPolicy displayPolicy) {
            this.displayPolicy = displayPolicy;
            return this;
        }

        public Builder overflowStrategy(ContextOverflowStrategy overflowStrategy) {
            this.overflowStrategy = overflowStrategy;
            return this;
        }

        public Builder storageRef(String storageRef) {
            this.storageRef = storageRef;
            return this;
        }

        public Builder messageRole(ContextMessageRole messageRole) {
            this.messageRole = messageRole;
            return this;
        }

        public Builder compressed(boolean compressed) {
            this.compressed = compressed;
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public ContextBlock build() {
            String resolvedKey = key == null || key.isBlank() ? sourceType.name().toLowerCase() : key;
            String resolvedName = displayName == null || displayName.isBlank() ? resolvedKey : displayName;
            return new ContextBlock(resolvedKey, resolvedName, sourceType, content, priority, required,
                    relevanceScore, displayPolicy, overflowStrategy, storageRef, messageRole, compressed, updatedAt);
        }
    }
}
