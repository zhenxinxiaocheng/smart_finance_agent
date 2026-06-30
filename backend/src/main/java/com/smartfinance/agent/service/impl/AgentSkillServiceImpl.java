package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smartfinance.agent.agent.SkillPackageParser;
import com.smartfinance.agent.dto.AgentSkillDefinition;
import com.smartfinance.agent.dto.AgentSkillInstallRequest;
import com.smartfinance.agent.dto.CustomSkillDraftRequest;
import com.smartfinance.agent.dto.ParsedSkillPackage;
import com.smartfinance.agent.entity.AgentSkill;
import com.smartfinance.agent.mapper.AgentSkillMapper;
import com.smartfinance.agent.service.AgentSkillService;
import com.smartfinance.agent.service.SkillPackageSourceProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

@Service
public class AgentSkillServiceImpl implements AgentSkillService {

    private static final String BUILT_IN_SOURCE = "BUILT_IN";
    private static final String BUILT_IN_URI = "builtin";
    private static final String CUSTOM_SOURCE = "CUSTOM";
    private static final List<String> SAFE_BOUND_TOOLS = List.of(
            "get_total_expense",
            "get_total_income",
            "get_expense_by_category",
            "get_recent_transactions",
            "get_monthly_summary",
            "get_finance_overview",
            "analyze_emergency_fund",
            "evaluate_savings_rate",
            "detect_anomalies",
            "compare_with_benchmark",
            "budget_planning_wizard",
            "tax_estimation",
            "suggest_category",
            "record_transaction",
            "set_budget",
            "get_budget_status",
            "check_alerts",
            "get_alert_history",
            "search_web"
    );

    private final AgentSkillMapper mapper;
    private final SkillPackageParser parser;
    private final List<SkillPackageSourceProvider> providers;

    public AgentSkillServiceImpl(AgentSkillMapper mapper,
                                 SkillPackageParser parser,
                                 List<SkillPackageSourceProvider> providers) {
        this.mapper = mapper;
        this.parser = parser;
        this.providers = providers;
    }

    @Override
    public void syncBuiltInSkills(Long userId, Collection<AgentSkillDefinition> definitions) {
        if (userId == null || definitions == null) {
            return;
        }
        for (AgentSkillDefinition definition : definitions) {
            AgentSkill existing = mapper.selectByUserAndSource(userId, BUILT_IN_SOURCE, BUILT_IN_URI, definition.skillKey());
            if (existing == null) {
                AgentSkill skill = fromDefinition(userId, definition);
                mapper.insert(skill);
            } else {
                existing.setName(definition.name());
                existing.setCategory(definition.category());
                existing.setDescription(definition.description());
                existing.setVersion(definition.version());
                existing.setAuthor(definition.author());
                existing.setRiskLevel(definition.riskLevel());
                existing.setInputSchema(definition.inputSchema());
                existing.setInstructionText(definition.instructionText());
                existing.setBoundTools(joinTools(definition.boundTools()));
                existing.setDeleted(0);
                existing.setBuiltIn(1);
                if (existing.getEnabled() == null) {
                    existing.setEnabled(1);
                }
                mapper.updateById(existing);
            }
        }
    }

    @Override
    public String buildEnabledSkillManifest(Long userId) {
        List<AgentSkill> skills = mapper.selectByUser(userId).stream()
                .filter(skill -> value(skill.getEnabled()) == 1)
                .filter(skill -> value(skill.getDeleted()) == 0)
                .toList();
        if (skills.isEmpty()) {
            return "No enabled skills.";
        }
        StringJoiner joiner = new StringJoiner("\n");
        String currentCategory = null;
        for (AgentSkill skill : skills) {
            String category = clean(skill.getCategory(), "Skills");
            if (!category.equals(currentCategory)) {
                currentCategory = category;
                joiner.add("[" + currentCategory + "]");
            }
            joiner.add("- " + skill.getSkillKey() + " (" + clean(skill.getName(), skill.getSkillKey()) + "): "
                    + clean(skill.getDescription(), "")
                    + " Risk: " + clean(skill.getRiskLevel(), "READ_ONLY")
                    + " input: " + clean(skill.getInputSchema(), "{}")
                    + " boundTools: " + clean(skill.getBoundTools(), skill.getSkillKey())
                    + " source: " + clean(skill.getSourceType(), "UNKNOWN"));
            if (skill.getInstructionText() != null && !skill.getInstructionText().isBlank()) {
                joiner.add("  Skill instructions: " + compact(skill.getInstructionText(), 500));
            }
        }
        return joiner.toString();
    }

    @Override
    public String buildEnabledSkillManifest(Long userId, Collection<AgentSkillDefinition> definitions) {
        syncBuiltInSkills(userId, definitions);
        return buildEnabledSkillManifest(userId);
    }

    @Override
    public boolean isEnabled(Long userId, String skillKey) {
        if (userId == null || skillKey == null || skillKey.isBlank()) {
            return true;
        }
        AgentSkill skill = mapper.selectByUserAndKey(userId, skillKey);
        return skill == null || value(skill.getEnabled()) == 1;
    }

    @Override
    public AgentSkill resolveInvocationSkill(Long userId, String toolName, String requestedSkillKey) {
        if (userId == null || toolName == null || toolName.isBlank()) {
            return null;
        }
        String skillKey = requestedSkillKey == null || requestedSkillKey.isBlank()
                ? toolName.trim()
                : requestedSkillKey.trim();
        AgentSkill skill = mapper.selectByUserAndKey(userId, skillKey);
        if (skill == null) {
            if (requestedSkillKey == null || requestedSkillKey.isBlank()) {
                return null;
            }
            skill = mapper.selectByUserAndKey(userId, toolName.trim());
            if (skill == null) {
                return null;
            }
        }
        if (value(skill.getDeleted()) == 1) {
            throw new IllegalArgumentException("Skill deleted: " + skillKey);
        }
        if (requestedSkillKey != null && !requestedSkillKey.isBlank()
                && !isBoundToTool(skill, toolName.trim())) {
            throw new IllegalArgumentException("Skill " + skillKey + " is not bound to tool " + toolName);
        }
        return skill;
    }

    @Override
    public List<AgentSkill> list(Long userId) {
        return mapper.selectByUser(userId);
    }

    @Override
    public AgentSkill detail(Long userId, Long id) {
        AgentSkill skill = mapper.selectById(id);
        if (skill == null || !userId.equals(skill.getUserId()) || value(skill.getDeleted()) == 1) {
            throw new IllegalArgumentException("Skill not found");
        }
        return skill;
    }

    @Override
    public AgentSkill install(Long userId, AgentSkillInstallRequest request) {
        String sourceType = request.getSourceType().trim().toUpperCase(Locale.ROOT);
        String sourceUri = request.getSourceUri().trim();
        SkillPackageSourceProvider provider = providers.stream()
                .filter(candidate -> candidate.supports(sourceType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported skill source type: " + sourceType));

        Path packageRoot = provider.fetch(sourceUri);
        ParsedSkillPackage parsed = parser.parse(packageRoot);
        AgentSkill existing = mapper.selectByUserAndSource(userId, sourceType, sourceUri, parsed.getSkillKey());
        AgentSkill skill = existing == null ? new AgentSkill() : existing;
        skill.setUserId(userId);
        skill.setSkillKey(parsed.getSkillKey());
        skill.setName(parsed.getName());
        skill.setDescription(parsed.getDescription());
        skill.setVersion(parsed.getVersion());
        skill.setAuthor(parsed.getAuthor());
        skill.setCategory(parsed.getCategory());
        skill.setRiskLevel(parsed.getRiskLevel());
        skill.setInputSchema(parsed.getInputSchema());
        skill.setTriggerText(parsed.getTriggerText());
        skill.setInstructionText(parsed.getInstructionText());
        skill.setBoundTools(joinTools(parsed.getBoundTools()));
        skill.setSourceType(sourceType);
        skill.setSourceUri(sourceUri);
        skill.setSourceVersion(provider.resolveVersion(sourceUri));
        skill.setBuiltIn(0);
        skill.setDeleted(0);
        if (skill.getEnabled() == null) {
            skill.setEnabled(1);
        }
        if (existing == null) {
            mapper.insert(skill);
        } else {
            mapper.updateById(skill);
        }
        return skill;
    }

    @Override
    public AgentSkill installCustomSkill(Long userId, CustomSkillDraftRequest request) {
        String skillKey = normalizeSkillKey(request.getSkillKey(), request.getName());
        String sourceUri = "custom:" + skillKey;
        AgentSkill existing = mapper.selectByUserAndSource(userId, CUSTOM_SOURCE, sourceUri, skillKey);
        AgentSkill skill = existing == null ? new AgentSkill() : existing;
        skill.setUserId(userId);
        skill.setSkillKey(skillKey);
        skill.setName(clean(request.getName(), skillKey));
        skill.setDescription(clean(request.getDescription(), ""));
        skill.setVersion("1.0.0");
        skill.setAuthor("user");
        skill.setCategory(clean(request.getCategory(), "Custom"));
        skill.setRiskLevel(normalizeRisk(request.getRiskLevel(), request.getBoundTools()));
        skill.setInputSchema("{}");
        skill.setTriggerText(clean(request.getTriggerText(), ""));
        skill.setInstructionText(buildInstructionText(request));
        skill.setBoundTools(joinTools(normalizeBoundTools(request.getBoundTools())));
        skill.setSourceType(CUSTOM_SOURCE);
        skill.setSourceUri(sourceUri);
        skill.setSourceVersion("user-defined");
        skill.setBuiltIn(0);
        skill.setDeleted(0);
        skill.setEnabled(1);
        if (existing == null) {
            mapper.insert(skill);
        } else {
            mapper.updateById(skill);
        }
        return skill;
    }

    @Override
    public AgentSkill setEnabled(Long userId, Long id, boolean enabled) {
        AgentSkill skill = detail(userId, id);
        skill.setEnabled(enabled ? 1 : 0);
        mapper.updateById(skill);
        return skill;
    }

    @Override
    public void delete(Long userId, Long id) {
        AgentSkill skill = detail(userId, id);
        if (value(skill.getBuiltIn()) == 1) {
            throw new IllegalArgumentException("Built-in skills cannot be uninstalled; disable them instead.");
        }
        mapper.update(null, new LambdaUpdateWrapper<AgentSkill>()
                .eq(AgentSkill::getId, id)
                .eq(AgentSkill::getUserId, userId)
                .set(AgentSkill::getDeleted, 1));
    }

    private AgentSkill fromDefinition(Long userId, AgentSkillDefinition definition) {
        AgentSkill skill = new AgentSkill();
        skill.setUserId(userId);
        skill.setSkillKey(definition.skillKey());
        skill.setName(definition.name());
        skill.setCategory(definition.category());
        skill.setDescription(definition.description());
        skill.setVersion(definition.version());
        skill.setAuthor(definition.author());
        skill.setRiskLevel(definition.riskLevel());
        skill.setInputSchema(definition.inputSchema());
        skill.setInstructionText(definition.instructionText());
        skill.setBoundTools(joinTools(definition.boundTools()));
        skill.setSourceType(BUILT_IN_SOURCE);
        skill.setSourceUri(BUILT_IN_URI);
        skill.setSourceVersion(definition.version());
        skill.setEnabled(1);
        skill.setBuiltIn(1);
        skill.setDeleted(0);
        return skill;
    }

    private String joinTools(List<String> tools) {
        return tools == null ? "" : String.join(",", tools);
    }

    private List<String> normalizeBoundTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
                .map(tool -> tool == null ? "" : tool.trim())
                .filter(tool -> !tool.isBlank())
                .filter(SAFE_BOUND_TOOLS::contains)
                .distinct()
                .toList();
    }

    private String normalizeSkillKey(String requestedKey, String name) {
        String base = requestedKey == null || requestedKey.isBlank() ? name : requestedKey;
        if (base == null || base.isBlank()) {
            base = "custom-skill";
        }
        String slug = base.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (!slug.isBlank()) {
            return slug.length() <= 80 ? slug : slug.substring(0, 80);
        }
        return "custom-skill-" + Integer.toHexString(Math.abs(base.hashCode()));
    }

    private String normalizeRisk(String requestedRisk, List<String> boundTools) {
        List<String> tools = normalizeBoundTools(boundTools);
        if (tools.contains("record_transaction") || tools.contains("set_budget")) {
            return "REQUIRES_CONFIRMATION";
        }
        if (tools.contains("search_web")) {
            return "EXTERNAL_INFORMATION";
        }
        String risk = requestedRisk == null ? "" : requestedRisk.trim().toUpperCase(Locale.ROOT);
        return switch (risk) {
            case "EXTERNAL_INFORMATION", "REQUIRES_CONFIRMATION" -> risk;
            default -> "READ_ONLY";
        };
    }

    private String buildInstructionText(CustomSkillDraftRequest request) {
        String trigger = clean(request.getTriggerText(), "When the user request matches this skill.");
        String instruction = clean(request.getInstructionText(), clean(request.getDescription(), ""));
        String tools = joinTools(normalizeBoundTools(request.getBoundTools()));
        return """
                # %s

                ## Trigger
                %s

                ## Behavior
                %s

                ## Bound Tools
                %s

                ## Safety
                Only use the bound tools listed above. Do not execute third-party code.
                """.formatted(clean(request.getName(), "Custom Skill"), trigger, instruction, tools);
    }

    private boolean isBoundToTool(AgentSkill skill, String toolName) {
        if (skill == null || toolName == null || toolName.isBlank()) {
            return false;
        }
        if (toolName.equals(skill.getSkillKey())) {
            return true;
        }
        String boundTools = skill.getBoundTools();
        if (boundTools == null || boundTools.isBlank()) {
            return false;
        }
        for (String boundTool : boundTools.split(",")) {
            if (toolName.equals(boundTool.trim())) {
                return true;
            }
        }
        return false;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String compact(String value, int max) {
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }
}
