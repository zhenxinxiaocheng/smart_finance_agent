package com.smartfinance.agent.context;

abstract class AbstractRuleBasedCompressor implements ContextCompressor {

    private final ContextTokenEstimator estimator;

    protected AbstractRuleBasedCompressor(ContextTokenEstimator estimator) {
        this.estimator = estimator;
    }

    protected ContextBlock compressedBlock(ContextBlock block, String content) {
        return ContextBlock.builder()
                .key(block.key())
                .displayName(block.displayName())
                .sourceType(block.sourceType())
                .content(content)
                .priority(block.priority())
                .required(block.required())
                .relevanceScore(block.relevanceScore())
                .displayPolicy(block.displayPolicy())
                .overflowStrategy(block.overflowStrategy())
                .storageRef(block.storageRef())
                .messageRole(block.messageRole())
                .compressed(true)
                .updatedAt(block.updatedAt())
                .build();
    }

    protected String firstLines(String text, int maxLines, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\\R+");
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append("\n");
            }
            out.append("- ").append(truncate(line, Math.min(180, maxChars)));
            if (out.toString().split("\\R").length >= maxLines || out.length() >= maxChars) {
                break;
            }
        }
        return truncate(out.toString(), maxChars);
    }

    protected String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
    }

    
    protected String lastLines(String text, int maxLines, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\\R+");
        // 从尾部开始收集非空行，最多 maxLines 条
        java.util.ArrayList<String> collected = new java.util.ArrayList<>();
        for (int i = lines.length - 1; i >= 0 && collected.size() < maxLines; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isBlank()) {
                continue;
            }
            collected.add(truncate(line, Math.min(180, maxChars)));
        }
        // 反转回正序（最旧的在上面，最新的在下面）
        java.util.Collections.reverse(collected);
        StringBuilder out = new StringBuilder();
        for (String line : collected) {
            if (out.length() > 0) {
                out.append("\n");
            }
            out.append("- ").append(line);
            if (out.length() >= maxChars) {
                break;
            }
        }
        return truncate(out.toString(), maxChars);
    }protected int targetChars(int targetTokens) {
        return Math.max(240, targetTokens * 2);
    }

    protected int estimate(String text) {
        return estimator.estimate(text);
    }
}
