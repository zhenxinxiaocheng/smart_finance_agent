package com.smartfinance.agent.util;

public final class AgentCronExpressions {

    private AgentCronExpressions() {
    }

    public static String normalize(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return cronExpression;
        }
        String clean = cronExpression.trim().replaceAll("\\s+", " ");
        String[] parts = clean.split(" ");
        if (parts.length == 5) {
            return "0 " + clean;
        }
        return clean;
    }
}
