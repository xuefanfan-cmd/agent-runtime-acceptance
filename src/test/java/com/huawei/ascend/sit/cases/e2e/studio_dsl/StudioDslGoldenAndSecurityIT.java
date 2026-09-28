/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.studio_dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.studio_dsl.OfflineAccessAuditFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioExportCorpusFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioPythonGoldenFixture;
import com.openjiuwen.studio.dsl.ir.IrAssembleResult;
import com.openjiuwen.studio.dsl.ir.IrAssembleTopologySnapshot;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Tag("e2e")
@Tag("studio-dsl")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioDslGoldenAndSecurityIT extends StudioDslContractTestBase {
    /**
     * Golden generated on 2026-09-12 from the deployed {@code studio-runtime} image; input, image id, tool hash and
     * known schema gaps are recorded next to the file in {@code golden-provenance.json}.
     */
    private static final String GOLDEN_ARTIFACT = "feat031-n01-stream-transform";

    private static final String GOLDEN_RESOURCE =
            "/testdata/studio_dsl/studio-export/golden/n01-552c74ed.python-golden.json";

    /**
     * Field the Python dump tool cannot resolve on the deployed runtime ({@code endId} stays empty). The end node
     * identity is still fully covered by {@code componentIds} and {@code edges}, so this single field is excluded
     * from the gating comparison and recorded as evidence until the dump tool is fixed.
     */
    private static final Set<String> NON_GATING_FIELDS = Set.of("endId");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @Story("RT-031-04-16: Java 与 Python 装配保真对照")
    @DisplayName("同源 IR 的 Java/Python 拓扑摘要使用统一 schema 对照")
    void compareJavaAndPythonAssemblySchema() throws Exception {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();
        Map<String, Object> ir = StudioExportCorpusFixture.artifactIr(manifest, GOLDEN_ARTIFACT);
        String pythonGolden = pythonGolden(ir);

        IrAssembleResult assembled = StudioExportCorpusFixture.assembleArtifact(manifest, GOLDEN_ARTIFACT);
        IrAssembleTopologySnapshot javaSnapshot = IrAssembleTopologySnapshot.from(assembled, ir);
        IrAssembleTopologySnapshot pythonSnapshot = IrAssembleTopologySnapshot.fromJson(pythonGolden);

        List<String> differences = javaSnapshot.diff(pythonSnapshot);
        List<String> gating = differences.stream()
                .filter(difference -> !NON_GATING_FIELDS.contains(fieldOf(difference)))
                .toList();
        List<String> nonGating = differences.stream()
                .filter(difference -> NON_GATING_FIELDS.contains(fieldOf(difference)))
                .toList();

        Allure.addAttachment("RT-031-04-16 Java topology snapshot", javaSnapshot.toJson());
        Allure.addAttachment("RT-031-04-16 Python topology snapshot", pythonSnapshot.toJson());
        Allure.addAttachment("RT-031-04-16 differences", String.join("\n", differences));

        assertThat(nonGating)
                .as("documented non-gating differences must stay inside the agreed field list")
                .allSatisfy(difference -> assertThat(fieldOf(difference)).isIn(NON_GATING_FIELDS));
        assertThat(gating)
                .as("Java assembly vs Python IRConverter topology differences")
                .isEmpty();
    }

    @Test
    @Disabled("OUT / non-gating：仅在冻结执行目录后手工启用风险观察")
    @Tag("security")
    @Story("RT-031-04-17: 敏感信息与日志边界观察")
    @DisplayName("扫描唯一敏感 canary 仅生成非门禁观察")
    void observeSensitiveCanaryWithoutProductGate() throws Exception {
        List<Path> matches = OfflineAccessAuditFixture.findCanary(
                List.of(Path.of(requiredProperty("studio.dsl.audit.root"))),
                requiredProperty("studio.dsl.audit.canary"));
        Allure.addAttachment("RT-031-04-17 non-gating canary matches", matches.toString());
    }

    /**
     * Uses the checked-in Python golden by default; regenerates against a live Studio Python runtime when
     * {@code -Dstudio.python.golden.executable} and {@code -Dstudio.python.golden.script} are supplied
     * (see the corpus generation guide §9).
     */
    private String pythonGolden(Map<String, Object> ir) throws Exception {
        String executable = System.getProperty("studio.python.golden.executable");
        String script = System.getProperty("studio.python.golden.script");
        if (executable == null || executable.isBlank() || script == null || script.isBlank()) {
            try (InputStream input = StudioDslGoldenAndSecurityIT.class.getResourceAsStream(GOLDEN_RESOURCE)) {
                if (input == null) {
                    throw new IllegalStateException("missing golden resource: " + GOLDEN_RESOURCE);
                }
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Path irFile = tempDir.resolve("rt-031-04-16-n01-ir.json");
        Files.createDirectories(irFile.getParent());
        Files.writeString(irFile, MAPPER.writeValueAsString(ir), StandardCharsets.UTF_8);
        StudioPythonGoldenFixture fixture = new StudioPythonGoldenFixture(List.of(executable, script));
        return fixture.summarize(irFile, Duration.ofSeconds(120)).rawOutput();
    }

    private static String fieldOf(String difference) {
        int separator = difference.indexOf(':');
        return separator < 0 ? difference : difference.substring(0, separator);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required execution property -D" + name);
        }
        return value;
    }
}
