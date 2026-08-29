package com.huawei.ascend.sit.cases.integration.edpa;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("feat-000")
@Tag("blackbox")
@Feature("FEAT-000: Solution 层 Fat Jar 瘦身")
@Story("FEAT-000.artifact-slimming: 体积预算、classifier 和禁包")
class SolutionFatJarSlimmingAcceptanceTest {

    private static final long SIXTY_MB = 60L * 1024 * 1024;
    private static final long ONE_MB = 1024L * 1024;
    private static final List<String> FORBIDDEN = List.of(
            "pulsar", "milvus", "openai", "grpc-netty-shaded", "sqlite",
            "bcprov", "bcpkix", "bcutil", "poi-ooxml", "pdfbox", "dashscope",
            "postgresql", "pgvector", "opentelemetry-exporter-sender-jdk",
            "opentelemetry-exporter-sender-grpc-managed-channel");

    @Test
    @DisplayName("FEAT-000.artifact-slimming: fat/thin/exec 产物满足体积预算且不含重型依赖")
    void artifactsMeetSlimmingBudgetAndForbiddenDependencyRules() throws IOException {
        Path engine = requiredPath("edpa.engine.jar");
        Path versatileThin = requiredPath("versatile.thin.jar");
        Path versatileExec = requiredPath("versatile.exec.jar");
        Path integration = requiredPath("integration.fat.jar");

        assertThat(Files.size(engine)).as("edp-agent-engine fat jar").isLessThan(SIXTY_MB);
        assertThat(Files.size(integration)).as("customer-agent-app fat jar").isLessThan(SIXTY_MB);
        assertThat(Files.size(versatileThin)).as("versatile main artifact must be thin").isLessThan(ONE_MB);
        assertThat(versatileExec.getFileName().toString()).contains("-exec");
        assertThat(Files.size(versatileExec)).isGreaterThan(Files.size(versatileThin));

        assertJarDoesNotContain(engine);
        assertJarDoesNotContain(integration);
        assertJarDoesNotContain(versatileThin);
    }

    @Test
    @DisplayName("FEAT-000.edpa-startup: 瘦身后的 EDP Agent Agent Card 可达")
    void edpAgentStartupCardIsReachable() throws IOException, InterruptedException {
        String baseUrl = System.getProperty("edp.agent.base-url", "http://172.23.32.1:8190");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/.well-known/agent-card.json"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("customer-agent-app", "streaming");
    }

    private static Path requiredPath(String property) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            raw = switch (property) {
                case "edpa.engine.jar" -> "/mnt/d/code-agent/agent-solution/common/agents/edp-agent-java/engine/target/edp-agent-engine-0.1.0-exec.jar";
                case "versatile.thin.jar" -> "/mnt/d/code-agent/agent-solution/common/agents/versatile-agent-java/target/adapter-versatile-agent-java-0.1.0.jar";
                case "versatile.exec.jar" -> "/mnt/d/code-agent/agent-solution/common/agents/versatile-agent-java/target/adapter-versatile-agent-java-0.1.0-exec.jar";
                case "integration.fat.jar" -> "/mnt/d/code-agent/agent-solution/common/example/edp-agent-integration-demo/target/customer-agent-app-1.0.0.jar";
                default -> null;
            };
        }
        assertThat(raw).as("-D%s must point to a built artifact", property).isNotBlank();
        Path path = Path.of(raw).toAbsolutePath().normalize();
        assertThat(Files.isRegularFile(path)).as("artifact exists: %s", path).isTrue();
        return path;
    }

    private static void assertJarDoesNotContain(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            List<String> entries = jar.stream().map(e -> e.getName().toLowerCase(Locale.ROOT)).toList();
            for (String forbidden : FORBIDDEN) {
                assertThat(entries.stream().noneMatch(entry -> entry.contains(forbidden)))
                        .as("%s must not be present in %s", forbidden, jarPath)
                        .isTrue();
            }
        }
    }
}
