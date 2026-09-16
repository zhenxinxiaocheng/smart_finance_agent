package com.smartfinance.agent.config;

import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RagConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsKnowledgeDocumentsFromJar() throws Exception {
        Path jar = tempDir.resolve("knowledge.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("knowledge/"));
            output.closeEntry();
            addEntry(output, "knowledge/budget.txt", "预算内容");
            addEntry(output, "knowledge/investment.txt", "投资内容");
            addEntry(output, "knowledge/ignore.md", "不应加载");
        }

        boolean previousCacheSetting = URLConnection.getDefaultUseCaches("jar");
        URLConnection.setDefaultUseCaches("jar", false);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            List<Document> documents = new RagConfig().knowledgeDocuments(
                    new PathMatchingResourcePatternResolver(loader));

            assertThat(documents).hasSize(2);
            assertThat(documents).extracting(Document::text)
                    .containsExactlyInAnyOrder("预算内容", "投资内容");
            assertThat(documents).extracting(document -> document.metadata(Document.FILE_NAME))
                    .containsExactlyInAnyOrder("budget.txt", "investment.txt");
        } finally {
            URLConnection.setDefaultUseCaches("jar", previousCacheSetting);
        }
    }

    @Test
    void loadsBundledKnowledgeDocuments() {
        List<Document> documents = new RagConfig().knowledgeDocuments();

        assertThat(documents).hasSize(11);
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.text()).isNotBlank();
            assertThat(document.metadata(Document.FILE_NAME)).endsWith(".txt");
        });
    }

    private static void addEntry(JarOutputStream output, String name, String text) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(text.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
