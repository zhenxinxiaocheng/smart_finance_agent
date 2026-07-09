package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.dto.AgentMemoryPreferencesRequest;
import com.smartfinance.agent.dto.AgentMemoryPreferencesResponse;
import com.smartfinance.agent.dto.AgentMemoryRequest;
import com.smartfinance.agent.entity.AgentMemory;
import com.smartfinance.agent.mapper.AgentMemoryMapper;
import com.smartfinance.agent.service.AgentMemoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;

@Service
public class AgentMemoryServiceImpl implements AgentMemoryService {

    private static final double MIN_CONFIDENCE = 0.70;
    private static final int AGENT_CONTEXT_LIMIT = 10;
    private static final int CUSTOM_INSTRUCTIONS_LIMIT = 3000;
    private static final int USER_PROFILE_CONTEXT_LIMIT = 1375;
    private static final int MEMORY_CONTEXT_LIMIT = 2200;
    private static final String AGENT_PREFERENCE = "AGENT_PREFERENCE";
    private static final String CUSTOM_INSTRUCTIONS_KEY = "_CUSTOM_INSTRUCTIONS";
    private static final String AUTO_MEMORY_ENABLED_KEY = "_AUTO_MEMORY_ENABLED";
    private static final String SKIP_TOOL_ASSISTED_MEMORY_KEY = "_SKIP_TOOL_ASSISTED_MEMORY";

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "CATEGORY_PREFERENCE",
            "RESPONSE_STYLE",
            "ANALYSIS_PREFERENCE",
            AGENT_PREFERENCE
    );
    private static final Set<String> RESERVED_KEYS = Set.of(
            CUSTOM_INSTRUCTIONS_KEY,
            AUTO_MEMORY_ENABLED_KEY,
            SKIP_TOOL_ASSISTED_MEMORY_KEY
    );
    private static final List<String> SENSITIVE_WORDS = List.of(
            "银行卡", "身份证", "密码", "API key", "api key", "密钥", "手机号",
            "资产", "负债", "月收入", "收入", "存款", "余额", "贷款"
    );

    private final AgentMemoryMapper agentMemoryMapper;

    public AgentMemoryServiceImpl(AgentMemoryMapper agentMemoryMapper) {
        this.agentMemoryMapper = agentMemoryMapper;
    }

    @Override
    public List<AgentMemory> list(Long userId) {
        return agentMemoryMapper.selectByUser(userId).stream()
                .filter(memory -> !RESERVED_KEYS.contains(memory.getMemoryKey()))
                .toList();
    }

    @Override
    @Transactional
    public AgentMemory createManual(Long userId, AgentMemoryRequest request) {
        String type = clean(request.getMemoryType());
        String key = clean(request.getMemoryKey());
        String value = clean(request.getMemoryValue());
        if (!isAllowedType(type) || RESERVED_KEYS.contains(key)) {
            throw new IllegalArgumentException("Unsupported memory type or key");
        }
        if (isSensitive(key) || isSensitive(value)) {
            throw new IllegalArgumentException("This looks like sensitive information. Please keep it in the financial profile.");
        }
        return upsertMemory(userId, type, key, value, 1.0, "MANUAL", false);
    }

    @Override
    public AgentMemoryPreferencesResponse getPreferences(Long userId) {
        return AgentMemoryPreferencesResponse.builder()
                .customInstructions(settingValue(userId, CUSTOM_INSTRUCTIONS_KEY, ""))
                .autoMemoryEnabled(isAutoMemoryEnabled(userId))
                .skipToolAssistedMemory(shouldSkipToolAssistedMemory(userId))
                .build();
    }

    @Override
    @Transactional
    public AgentMemoryPreferencesResponse updatePreferences(Long userId, AgentMemoryPreferencesRequest request) {
        String currentInstructions = settingValue(userId, CUSTOM_INSTRUCTIONS_KEY, "");
        boolean autoMemoryEnabled = isAutoMemoryEnabled(userId);
        boolean skipToolAssistedMemory = shouldSkipToolAssistedMemory(userId);

        String instructions = request.getCustomInstructions() == null
                ? currentInstructions
                : truncate(clean(request.getCustomInstructions()), CUSTOM_INSTRUCTIONS_LIMIT);
        if (isSensitive(instructions)) {
            throw new IllegalArgumentException("This looks like sensitive financial information. Please keep it in the financial profile.");
        }
        upsertMemory(userId, AGENT_PREFERENCE, CUSTOM_INSTRUCTIONS_KEY,
                instructions == null ? "" : instructions, 1.0, "SETTINGS", instructions == null || instructions.isBlank());

        if (request.getAutoMemoryEnabled() != null) {
            autoMemoryEnabled = request.getAutoMemoryEnabled();
            upsertMemory(userId, AGENT_PREFERENCE, AUTO_MEMORY_ENABLED_KEY,
                    Boolean.toString(autoMemoryEnabled), 1.0, "SETTINGS", false);
        }
        if (request.getSkipToolAssistedMemory() != null) {
            skipToolAssistedMemory = request.getSkipToolAssistedMemory();
            upsertMemory(userId, AGENT_PREFERENCE, SKIP_TOOL_ASSISTED_MEMORY_KEY,
                    Boolean.toString(skipToolAssistedMemory), 1.0, "SETTINGS", false);
        }
        return AgentMemoryPreferencesResponse.builder()
                .customInstructions(instructions == null ? "" : instructions)
                .autoMemoryEnabled(autoMemoryEnabled)
                .skipToolAssistedMemory(skipToolAssistedMemory)
                .build();
    }

    @Override
    @Transactional
    public boolean upsertAutoMemory(Long userId,
                                    String memoryType,
                                    String memoryKey,
                                    String memoryValue,
                                    double confidence,
                                    String sourceQuery) {
        String type = clean(memoryType);
        String key = clean(memoryKey);
        String value = clean(memoryValue);
        if (!isAutoMemoryEnabled(userId)
                || userId == null || confidence < MIN_CONFIDENCE || !isAllowedType(type)
                || RESERVED_KEYS.contains(key)
                || key == null || key.isBlank() || value == null || value.isBlank()
                || isSensitive(key) || isSensitive(value) || isSensitive(sourceQuery)) {
            return false;
        }

        upsertMemory(userId, type, key, value, confidence, truncate(sourceQuery, 500), false);
        return true;
    }

    @Override
    @Transactional
    public AgentMemory setDisabled(Long userId, Long memoryId, boolean disabled) {
        AgentMemory memory = loadOwned(userId, memoryId);
        memory.setDisabled(disabled ? 1 : 0);
        agentMemoryMapper.updateById(memory);
        return memory;
    }

    @Override
    @Transactional
    public void delete(Long userId, Long memoryId) {
        AgentMemory memory = loadOwned(userId, memoryId);
        agentMemoryMapper.deleteById(memory.getId());
    }

    @Override
    @Transactional
    public void reset(Long userId) {
        agentMemoryMapper.softDeleteByUser(userId);
    }

    @Override
    public boolean isAutoMemoryEnabled(Long userId) {
        return Boolean.parseBoolean(settingValue(userId, AUTO_MEMORY_ENABLED_KEY, "true"));
    }

    @Override
    public boolean shouldSkipToolAssistedMemory(Long userId) {
        return Boolean.parseBoolean(settingValue(userId, SKIP_TOOL_ASSISTED_MEMORY_KEY, "false"));
    }

    @Override
    public List<AgentMemory> retrieveRelevantMemories(Long userId, String query, int limit) {
        int fetchLimit = Math.max(50, limit);
        List<AgentMemory> candidates = agentMemoryMapper.selectActiveForAgent(userId, fetchLimit);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> queryTerms = terms(query);
        return candidates.stream()
                .filter(memory -> memory != null && !RESERVED_KEYS.contains(memory.getMemoryKey()))
                .sorted(Comparator.comparingDouble((AgentMemory memory) -> relevance(memory, queryTerms)).reversed()
                        .thenComparing(memory -> memory.getConfidence() == null ? 0.0 : memory.getConfidence(), Comparator.reverseOrder())
                        .thenComparing(AgentMemory::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public String buildAgentContext(Long userId) {
        StringBuilder userProfile = new StringBuilder();
        String customInstructions = settingValue(userId, CUSTOM_INSTRUCTIONS_KEY, "");
        if (!customInstructions.isBlank()) {
            appendWithinBudget(userProfile, customInstructions, Integer.MAX_VALUE);
        }

        StringBuilder memoryBlock = new StringBuilder();
        List<AgentMemory> memories = retrieveRelevantMemories(userId, "", AGENT_CONTEXT_LIMIT);
        if (memories != null && !memories.isEmpty()) {
            for (AgentMemory memory : memories) {
                String line = "- %s/%s: %s".formatted(
                        clean(memory.getMemoryType()),
                        clean(memory.getMemoryKey()),
                        clean(memory.getMemoryValue()));
                appendWithinBudget(memoryBlock, line, Integer.MAX_VALUE);
            }
        }
        if (userProfile.isEmpty() && memoryBlock.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        if (!userProfile.isEmpty()) {
            context.append("§ USER PROFILE (用户可编辑的 Agent 长期指令, ")
                    .append(userProfile.length())
                    .append("/")
                    .append(USER_PROFILE_CONTEXT_LIMIT)
                    .append(" chars)\n")
                    .append(userProfile);
        }
        if (!memoryBlock.isEmpty()) {
            if (!context.isEmpty()) {
                context.append("\n\n");
            }
            context.append("§ MEMORY (自动沉淀的 Agent 长期记忆, ")
                    .append(memoryBlock.length())
                    .append("/")
                    .append(MEMORY_CONTEXT_LIMIT)
                    .append(" chars)\n")
                    .append(memoryBlock);
        }
        return context.toString().trim();
    }

    private double relevance(AgentMemory memory, Set<String> queryTerms) {
        String text = "%s %s %s".formatted(
                clean(memory.getMemoryType()),
                clean(memory.getMemoryKey()),
                clean(memory.getMemoryValue())).toLowerCase(Locale.ROOT);
        double score = 0.0;
        for (String term : queryTerms) {
            if (!term.isBlank() && text.contains(term)) {
                score += term.length() >= 2 ? 3.0 : 1.0;
            }
        }
        String type = clean(memory.getMemoryType());
        if ("CATEGORY_PREFERENCE".equals(type)) {
            score += 0.4;
        } else if ("RESPONSE_STYLE".equals(type)) {
            score += 0.2;
        } else if (AGENT_PREFERENCE.equals(type)) {
            score += 0.1;
        }
        return score;
    }

    private Set<String> terms(String query) {
        Set<String> terms = new HashSet<>();
        if (query == null || query.isBlank()) {
            return terms;
        }
        String clean = query.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ");
        for (String part : clean.split("\\s+")) {
            if (!part.isBlank()) {
                terms.add(part);
                addKnownChineseTerms(part, terms);
            }
        }
        addKnownChineseTerms(clean, terms);
        return terms;
    }

    private void addKnownChineseTerms(String text, Set<String> terms) {
        List<String> known = List.of("咖啡", "餐饮", "预算", "英语", "英文", "中文", "简短", "详细", "分类", "支出", "收入");
        for (String term : known) {
            if (text.contains(term)) {
                terms.add(term);
            }
        }
    }

    private AgentMemory upsertMemory(Long userId,
                                     String type,
                                     String key,
                                     String value,
                                     double confidence,
                                     String sourceQuery,
                                     boolean disabled) {
        AgentMemory existing = agentMemoryMapper.selectByKeyIncludingDeleted(userId, type, key);
        if (existing == null) {
            AgentMemory memory = new AgentMemory();
            memory.setUserId(userId);
            memory.setMemoryType(type);
            memory.setMemoryKey(key);
            memory.setMemoryValue(value);
            memory.setConfidence(confidence);
            memory.setSourceQuery(sourceQuery);
            memory.setDisabled(disabled ? 1 : 0);
            agentMemoryMapper.insert(memory);
            return memory;
        }
        existing.setMemoryValue(value);
        existing.setConfidence(confidence);
        existing.setSourceQuery(sourceQuery);
        existing.setDisabled(disabled ? 1 : 0);
        existing.setDeleted(0);
        agentMemoryMapper.restoreOrUpdateById(existing);
        return existing;
    }

    private String settingValue(Long userId, String key, String defaultValue) {
        AgentMemory setting = agentMemoryMapper.selectActiveByKey(userId, AGENT_PREFERENCE, key);
        if (setting == null || setting.getDisabled() != null && setting.getDisabled() == 1) {
            return defaultValue;
        }
        String value = setting.getMemoryValue();
        return value == null ? defaultValue : value;
    }

    private AgentMemory loadOwned(Long userId, Long memoryId) {
        AgentMemory memory = agentMemoryMapper.selectById(memoryId);
        if (memory == null || !userId.equals(memory.getUserId())) {
            throw new IllegalArgumentException("Memory does not exist");
        }
        return memory;
    }

    private static boolean isAllowedType(String type) {
        return type != null && ALLOWED_TYPES.contains(type);
    }

    private static boolean isSensitive(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase();
        if (lower.matches(".*\\b\\d{15,19}\\b.*")) {
            return true;
        }
        return SENSITIVE_WORDS.stream().anyMatch(lower::contains);
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private static void appendWithinBudget(StringBuilder target, String line, int maxChars) {
        if (line == null || line.isBlank()) {
            return;
        }
        String cleanLine = line.trim();
        int required = cleanLine.length() + (target.isEmpty() ? 0 : 1);
        if (target.length() + required > maxChars) {
            return;
        }
        if (!target.isEmpty()) {
            target.append("\n");
        }
        target.append(cleanLine);
    }
}
