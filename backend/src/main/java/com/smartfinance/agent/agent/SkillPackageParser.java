package com.smartfinance.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.dto.ParsedSkillPackage;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class SkillPackageParser {

    private final ObjectMapper objectMapper;

    public SkillPackageParser() {
        this(new ObjectMapper());
    }

    public SkillPackageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedSkillPackage parse(Path packageRoot) {
        try {
            Path skillMd = packageRoot.resolve("SKILL.md");
            if (!Files.exists(skillMd)) {
                throw new IllegalArgumentException("Skill package must contain SKILL.md");
            }
            String instructionText = Files.readString(skillMd);
            ParsedSkillPackage parsed = new ParsedSkillPackage();
            parsed.setInstructionText(instructionText);
            applyMarkdownDefaults(parsed, instructionText);

            Path manifest = packageRoot.resolve("skill.json");
            if (Files.exists(manifest)) {
                applyManifest(parsed, Files.readString(manifest));
            }
            normalize(parsed);
            return parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid skill package: " + e.getMessage(), e);
        }
    }

    private void applyMarkdownDefaults(ParsedSkillPackage parsed, String markdown) {
        String title = "Installed Skill";
        String description = "";
        for (String line : markdown.split("\\R")) {
            String clean = line.trim();
            if (clean.startsWith("# ")) {
                title = clean.substring(2).trim();
            } else if (description.isBlank() && !clean.isBlank() && !clean.startsWith("#")) {
                description = clean;
            }
        }
        parsed.setName(title);
        parsed.setSkillKey(slug(title));
        parsed.setDescription(description.isBlank() ? title : description);
    }

    private void applyManifest(ParsedSkillPackage parsed, String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        setIfPresent(root, "key", parsed::setSkillKey);
        setIfPresent(root, "name", parsed::setName);
        setIfPresent(root, "description", parsed::setDescription);
        setIfPresent(root, "version", parsed::setVersion);
        setIfPresent(root, "author", parsed::setAuthor);
        setIfPresent(root, "category", parsed::setCategory);
        setIfPresent(root, "riskLevel", parsed::setRiskLevel);
        setIfPresent(root, "triggerText", parsed::setTriggerText);
        setIfPresent(root, "inputSchema", parsed::setInputSchema);
        JsonNode boundTools = root.get("boundTools");
        if (boundTools != null && boundTools.isArray()) {
            List<String> tools = new ArrayList<>();
            boundTools.forEach(node -> {
                if (node != null && !node.asText("").isBlank()) {
                    tools.add(node.asText().trim());
                }
            });
            parsed.setBoundTools(tools);
        }
    }

    private void setIfPresent(JsonNode root, String field, java.util.function.Consumer<String> setter) {
        JsonNode value = root.get(field);
        if (value != null && !value.asText("").isBlank()) {
            setter.accept(value.asText().trim());
        }
    }

    private void normalize(ParsedSkillPackage parsed) {
        if (parsed.getSkillKey() == null || parsed.getSkillKey().isBlank()) {
            parsed.setSkillKey(slug(parsed.getName()));
        } else {
            parsed.setSkillKey(slug(parsed.getSkillKey()));
        }
        if (parsed.getRiskLevel() == null || parsed.getRiskLevel().isBlank()) {
            parsed.setRiskLevel("READ_ONLY");
        }
        if (parsed.getInputSchema() == null || parsed.getInputSchema().isBlank()) {
            parsed.setInputSchema("{}");
        }
    }

    private String slug(String value) {
        String base = value == null ? "installed-skill" : value.trim().toLowerCase(Locale.ROOT);
        String slug = base.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "installed-skill" : slug;
    }
}
