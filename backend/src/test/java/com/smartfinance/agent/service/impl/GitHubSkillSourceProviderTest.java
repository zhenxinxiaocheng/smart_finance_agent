package com.smartfinance.agent.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubSkillSourceProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveSkillRoot_shouldReturnSubdirectoryForGithubTreeUrl() throws Exception {
        Path repoRoot = tempDir.resolve("skills-main");
        Path skillDir = repoRoot.resolve("skills/.curated/openai-docs");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# OpenAI Docs");

        GitHubSkillSourceProvider provider = new GitHubSkillSourceProvider();
        Path resolved = provider.resolveSkillRoot(repoRoot,
                new GitHubSkillSourceProvider.GitHubRepo("openai", "skills", "main", "skills/.curated/openai-docs"));

        assertThat(resolved).isEqualTo(skillDir);
    }
}
