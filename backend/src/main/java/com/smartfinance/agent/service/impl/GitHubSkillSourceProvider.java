package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.service.SkillPackageSourceProvider;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

@Component
public class GitHubSkillSourceProvider implements SkillPackageSourceProvider {

    @Override
    public boolean supports(String sourceType) {
        return "GITHUB".equalsIgnoreCase(sourceType);
    }

    @Override
    public Path fetch(String sourceUri) {
        try {
            GitHubRepo repo = parse(sourceUri);
            Path tempDir = Files.createTempDirectory("agent-skill-github-");
            URI zipUri = URI.create("https://codeload.github.com/%s/%s/zip/refs/heads/%s"
                    .formatted(repo.owner(), repo.repo(), repo.ref()));
            try (InputStream stream = openZipStream(zipUri);
                 ZipInputStream zip = new ZipInputStream(stream)) {
                unzip(zip, tempDir);
            }
            try (var children = Files.list(tempDir)) {
                Path repoRoot = children.filter(Files::isDirectory).findFirst().orElse(tempDir);
                return resolveSkillRoot(repoRoot, repo);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Download GitHub skill failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String resolveVersion(String sourceUri) {
        return parse(sourceUri).ref();
    }

    private GitHubRepo parse(String sourceUri) {
        try {
            URI uri = URI.create(sourceUri);
            if (!"github.com".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("Only github.com URLs are supported for GITHUB source");
            }
            String[] parts = uri.getPath().replaceFirst("^/", "").split("/");
            if (parts.length < 2) {
                throw new IllegalArgumentException("GitHub URL must contain owner and repo");
            }
            String ref = "main";
            String subPath = "";
            if (parts.length >= 5 && "tree".equals(parts[2].toLowerCase(Locale.ROOT))) {
                ref = parts[3];
                subPath = String.join("/", Arrays.copyOfRange(parts, 4, parts.length));
            }
            return new GitHubRepo(parts[0], parts[1].replaceAll("\\.git$", ""), ref, subPath);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid GitHub URL: " + sourceUri, e);
        }
    }

    Path resolveSkillRoot(Path repoRoot, GitHubRepo repo) {
        if (repo.subPath() == null || repo.subPath().isBlank()) {
            return repoRoot;
        }
        Path skillRoot = repoRoot.resolve(repo.subPath()).normalize();
        if (!skillRoot.startsWith(repoRoot)) {
            throw new IllegalArgumentException("Unsafe GitHub skill path: " + repo.subPath());
        }
        if (!Files.exists(skillRoot)) {
            throw new IllegalArgumentException("GitHub skill path does not exist: " + repo.subPath());
        }
        return skillRoot;
    }

    private InputStream openZipStream(URI zipUri) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .sslContext(resolveSslContext())
                .build();
        HttpRequest request = HttpRequest.newBuilder(zipUri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "smart-finance-agent-skill-installer")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw new IllegalArgumentException("GitHub returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private SSLContext resolveSslContext() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return SSLContext.getDefault();
        }
        try {
            KeyStore windowsRoot = KeyStore.getInstance("Windows-ROOT");
            windowsRoot.load(null, null);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(windowsRoot);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            return sslContext;
        } catch (Exception ignored) {
            return SSLContext.getDefault();
        }
    }

    private void unzip(ZipInputStream zip, Path targetDir) throws Exception {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            Path target = targetDir.resolve(entry.getName()).normalize();
            if (!target.startsWith(targetDir)) {
                throw new IllegalArgumentException("Unsafe zip entry: " + entry.getName());
            }
            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(zip, target);
            }
        }
    }

    record GitHubRepo(String owner, String repo, String ref, String subPath) {
    }
}
