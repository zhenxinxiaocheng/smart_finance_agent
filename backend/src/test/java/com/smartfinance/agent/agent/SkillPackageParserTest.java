package com.smartfinance.agent.agent;

import com.smartfinance.agent.dto.ParsedSkillPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SkillPackageParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parse_shouldReadSkillMdAndOptionalManifestWithoutExecutingScripts() throws Exception {
        Files.writeString(tempDir.resolve("SKILL.md"), """
                # Monthly Review

                Use this skill when the user asks for a monthly finance review.
                """);
        Files.writeString(tempDir.resolve("skill.json"), """
                {
                  "key": "monthly-review",
                  "name": "Monthly Review",
                  "description": "Review monthly spending",
                  "version": "1.2.0",
                  "author": "open-source",
                  "category": "finance",
                  "riskLevel": "READ_ONLY",
                  "triggerText": "monthly review",
                  "boundTools": ["get_monthly_summary", "get_expense_by_category"],
                  "scripts": ["do-not-run.sh"]
                }
                """);
        Files.writeString(tempDir.resolve("do-not-run.sh"), "exit 99");

        ParsedSkillPackage parsed = new SkillPackageParser().parse(tempDir);

        assertThat(parsed.getSkillKey()).isEqualTo("monthly-review");
        assertThat(parsed.getName()).isEqualTo("Monthly Review");
        assertThat(parsed.getVersion()).isEqualTo("1.2.0");
        assertThat(parsed.getAuthor()).isEqualTo("open-source");
        assertThat(parsed.getInstructionText()).contains("monthly finance review");
        assertThat(parsed.getBoundTools()).containsExactly("get_monthly_summary", "get_expense_by_category");
    }

    @Test
    void parse_shouldDeriveMetadataFromSkillMdWhenManifestIsMissing() throws Exception {
        Files.writeString(tempDir.resolve("SKILL.md"), """
                # Coffee Categorizer

                Use this skill when the user asks how coffee expenses should be categorized.
                """);

        ParsedSkillPackage parsed = new SkillPackageParser().parse(tempDir);

        assertThat(parsed.getSkillKey()).isEqualTo("coffee-categorizer");
        assertThat(parsed.getName()).isEqualTo("Coffee Categorizer");
        assertThat(parsed.getDescription()).contains("coffee expenses");
        assertThat(parsed.getRiskLevel()).isEqualTo("READ_ONLY");
    }
}
