/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat032_multimodal;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.multimodal.documents.tools.DocumentEditTextTool;
import com.openjiuwen.agents.multimodal.documents.tools.DocumentInspectTool;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.harness.tools.ToolOutput;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * FEAT-032 CORE-003 文档工具直驱验收（离线，真实 DocumentInspectTool / DocumentEditTextTool，
 * workspace 路径守卫与 registerDocumentsOnto 装配同源）。
 *
 * <p>覆盖场景：F032-24（inspect 探查事实）、F032-27（docx 跨 run 保真替换闭环，主门禁）、
 * F032-30（乐观版本校验与输出冲突）、F032-31（读取失败三分类显式错误）。断言落在
 * ToolOutput data 事实字段（version/format/supportedOps/matched/parentVersion/reason）。
 *
 * @since 0.1.2
 */
@Tag("integration")
@Tag("feat-032")
@Tag("multimodal")
@Tag("sut-direct")
@Feature("FEAT-032: 智能体支持多模态")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat032DocumentToolDirectTest {

    @TempDir
    static Path workspace;

    private DocumentInspectTool inspect;
    private DocumentEditTextTool edit;

    @BeforeAll
    void setUp() {
        Assumptions.assumeTrue(Feat032SutSupport.solutionToolsPresent() && Feat032SutSupport.demoAgentPresent(),
                "FEAT-032 dependency-gated: agent-core-ext-multimodal-tools / multi-modal-tools-demo "
                        + "制品未就绪（需 agent-solution common!882 构建）");
        inspect = new DocumentInspectTool(workspace.toString());
        edit = new DocumentEditTextTool(workspace.toString());
    }

    private static ToolOutput invoke(Tool tool, Map<String, Object> inputs) {
        try {
            Object raw = tool.invoke(inputs, Map.of());
            assertThat(raw).isInstanceOf(ToolOutput.class);
            return (ToolOutput) raw;
        } catch (java.lang.Exception ex) {
            throw new IllegalStateException("tool invoke failed: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolOutput output) {
        assertThat(output.getData()).isInstanceOf(Map.class);
        return (Map<String, Object>) output.getData();
    }

    private Path writeDocx(String name, String paragraphXml) throws Exception {
        Path file = workspace.resolve(name);
        Files.write(file, Feat032SutSupport.minimalDocx(paragraphXml));
        return file;
    }

    // ------------------------------------------------------------------
    // F032-24 document_inspect 探查事实
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-24")
    @Story("FEAT-032.F032-24: inspect 返回魔数格式、SHA-256 版本、加密标记与一期支持/不支持清单")
    @DisplayName("F032-24[直驱] inspect 探查事实与格式判定")
    void f03224InspectReportsMagicFormatVersionAndOps() throws Exception {
        Path docx = writeDocx("sample.docx", "<w:p><w:r><w:t>hello</w:t></w:r></w:p>");
        ToolOutput output = invoke(inspect, Map.of("path", docx.toString()));

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = dataOf(output);
        assertThat(data.get("format")).isEqualTo("DOCX");
        assertThat(data.get("version")).isEqualTo(Feat032SutSupport.sha256Hex(
                Feat032SutSupport.readAll(docx)));
        assertThat(data.get("encrypted")).isEqualTo(false);
        assertThat(String.valueOf(data.get("supportedOperations")))
                .contains("inspect").contains("read").contains("search").contains("edit_text");
        assertThat(String.valueOf(data.get("unsupportedOperations")))
                .contains("render").contains("query").contains("create");

        // 一期不支持的格式显式 FORMAT_UNSUPPORTED（.wps 样式扩展名 + 未知魔数）
        Path wps = workspace.resolve("legacy.wps");
        Files.write(wps, Feat032SutSupport.unknownMagicBytes());
        ToolOutput wpsOut = invoke(inspect, Map.of("path", wps.toString()));
        assertThat(wpsOut.isSuccess()).isFalse();
        assertThat(dataOf(wpsOut).get("reason")).isEqualTo(Feat032SutSupport.REASON_FORMAT_UNSUPPORTED);
    }

    // ------------------------------------------------------------------
    // F032-27 document_edit_text 保真替换闭环（SC-16 部分，主门禁）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-27")
    @Story("FEAT-032.F032-27: dry_run 不落盘；跨 run 替换仅目标 part 变化；原文件 sha 不变；matched=0 不产出")
    @DisplayName("F032-27[直驱] docx 跨 run 保真替换闭环")
    void f03227FaithfulReplacementClosedLoop() throws Exception {
        Path source = writeDocx("contract.docx", """
                <w:p><w:r><w:t>头部-ALPHA</w:t></w:r><w:r><w:t>-TOKEN 尾部 </w:t></w:r>
                <w:r><w:t>包含 </w:t></w:r><w:r><w:t>BETA-TOKEN</w:t></w:r>
                <w:r><w:t> 唯一目标</w:t></w:r></w:p>""");
        String originalSha = Feat032SutSupport.sha256Hex(Feat032SutSupport.readAll(source));
        Path output = workspace.resolve("contract-v2.docx");

        Map<String, Object> inputs = Map.of(
                "path", source.toString(),
                "output_path", output.toString(),
                "replacements", List.of(Map.of("search", "ALPHA-TOKEN", "replace", "GAMMA-TOKEN")));

        // ① dry_run=true 预览：matched=1，不落盘
        ToolOutput dryRun = invoke(edit, merge(inputs, "dry_run", true));
        assertThat(dryRun.isSuccess()).isTrue();
        assertThat(dataOf(dryRun).get("matched")).isEqualTo(1);
        assertThat(Files.exists(output)).as("dry_run 产物不落盘").isFalse();

        // ② 实际替换：跨 run 命中，产物含 GAMMA 无 ALPHA
        ToolOutput applied = invoke(edit, inputs);
        assertThat(applied.isSuccess()).isTrue();
        assertThat(Files.exists(output)).isTrue();
        Map<String, byte[]> beforeParts = Feat032SutSupport.zipParts(Feat032SutSupport.readAll(source));
        Map<String, byte[]> afterParts = Feat032SutSupport.zipParts(Feat032SutSupport.readAll(output));
        assertThat(afterParts.keySet()).isEqualTo(beforeParts.keySet());
        for (String part : beforeParts.keySet()) {
            if (!part.equals("word/document.xml")) {
                assertThat(afterParts.get(part)).as("part %s 字节零改动", part)
                        .isEqualTo(beforeParts.get(part));
            }
        }
        String resultText = new String(afterParts.get("word/document.xml"), StandardCharsets.UTF_8);
        assertThat(resultText).contains("GAMMA").doesNotContain("ALPHA");

        // ③ 原文件 sha 不变 + 产物版本事实
        assertThat(Feat032SutSupport.sha256Hex(Feat032SutSupport.readAll(source))).isEqualTo(originalSha);
        assertThat(dataOf(applied).get("parentVersion")).isEqualTo(originalSha);
        assertThat(dataOf(applied).get("version"))
                .isEqualTo(Feat032SutSupport.sha256Hex(Feat032SutSupport.readAll(output)));

        // ④ matched=0 不产出新版本文件
        Path zeroOutput = workspace.resolve("contract-v3.docx");
        ToolOutput zero = invoke(edit, Map.of(
                "path", source.toString(),
                "output_path", zeroOutput.toString(),
                "replacements", List.of(Map.of("search", "NOT-EXIST", "replace", "X"))));
        assertThat(dataOf(zero).get("matched")).isEqualTo(0);
        assertThat(Files.exists(zeroOutput)).as("matched=0 不产出新版本文件").isFalse();
    }

    // ------------------------------------------------------------------
    // F032-30 乐观版本校验与输出路径冲突
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-30")
    @Story("FEAT-032.F032-30: 版本失配 VERSION_CONFLICT；旧 expected_version 触发显式冲突而非静默覆盖")
    @DisplayName("F032-30[直驱] 乐观版本校验")
    void f03230VersionConflict() throws Exception {
        Path source = writeDocx("versioned.docx", "<w:p><w:r><w:t>V1-BODY-TOKEN</w:t></w:r></w:p>");
        String v1 = Feat032SutSupport.sha256Hex(Feat032SutSupport.readAll(source));
        Path output = workspace.resolve("versioned-out.docx");

        // ① 正确版本号可写
        ToolOutput ok = invoke(edit, Map.of(
                "path", source.toString(),
                "output_path", output.toString(),
                "replacements", List.of(Map.of("search", "V1-BODY", "replace", "V2-BODY")),
                "expected_version", v1));
        assertThat(ok.isSuccess()).isTrue();
        assertThat(dataOf(ok).get("parentVersion")).isEqualTo(v1);
        assertThat(dataOf(ok).get("version")).isEqualTo(Feat032SutSupport.sha256Hex(Feat032SutSupport.readAll(output)));

        // ② 源文件在读取后被变更，旧 expected_version 触发 VERSION_CONFLICT
        // （版本校验基于源文件当前摘要：编辑写 output_path 不回写源文件，需先变更源才能制造失配）
        Files.write(source, Feat032SutSupport.minimalDocx("<w:p><w:r><w:t>V1-CHANGED</w:t></w:r></w:p>"));
        ToolOutput stale = invoke(edit, Map.of(
                "path", source.toString(),
                "output_path", workspace.resolve("versioned-stale.docx").toString(),
                "replacements", List.of(Map.of("search", "V1-CHANGED", "replace", "V9-BODY")),
                "expected_version", v1));
        assertThat(stale.isSuccess()).isFalse();
        assertThat(dataOf(stale).get("reason")).isEqualTo(Feat032SutSupport.REASON_VERSION_CONFLICT);
        assertThat(dataOf(stale).get("stage")).isEqualTo("edit");
        assertThat(String.valueOf(dataOf(stale).get("message"))).contains(v1);

        // ③ 未知版本号 → VERSION_CONFLICT
        ToolOutput unknown = invoke(edit, Map.of(
                "path", source.toString(),
                "output_path", workspace.resolve("versioned-x.docx").toString(),
                "replacements", List.of(Map.of("search", "V1-CHANGED", "replace", "V9-BODY")),
                "expected_version", "deadbeef"));
        assertThat(unknown.isSuccess()).isFalse();
        assertThat(dataOf(unknown).get("reason")).isEqualTo(Feat032SutSupport.REASON_VERSION_CONFLICT);

        // ④ 输出路径已被占用且未显式 overwrite → CONTENT_INVALID 显式拒绝
        ToolOutput occupied = invoke(edit, Map.of(
                "path", source.toString(),
                "output_path", output.toString(),
                "replacements", List.of(Map.of("search", "V1-CHANGED", "replace", "V3-BODY"))));
        assertThat(occupied.isSuccess()).isFalse();
        assertThat(dataOf(occupied).get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
        assertThat(dataOf(occupied).get("stage")).isEqualTo("resolve");
        ToolOutput forced = invoke(edit, Map.of(
                "path", source.toString(),
                "output_path", output.toString(),
                "replacements", List.of(Map.of("search", "V1-CHANGED", "replace", "V3-BODY")),
                "overwrite", true));
        assertThat(forced.isSuccess()).as("显式 overwrite=true 允许覆盖旧产物").isTrue();
    }

    // ------------------------------------------------------------------
    // F032-31 读取失败三分类显式错误
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-31")
    @Story("FEAT-032.F032-31: 文件缺失/损毁包 FILE_INVALID；未支持格式 FORMAT_UNSUPPORTED")
    @DisplayName("F032-31[直驱] 读取失败三分类")
    void f03231ReadFailureTripartite() throws Exception {
        // ① 文件不存在（resolve 阶段统一 FILE_INVALID，不引入独立 FILE_NOT_FOUND）
        ToolOutput missing = invoke(inspect, Map.of("path", workspace.resolve("nope.docx").toString()));
        assertThat(missing.isSuccess()).isFalse();
        assertThat(dataOf(missing).get("reason")).isEqualTo(Feat032SutSupport.REASON_FILE_INVALID);
        assertThat(dataOf(missing).get("stage")).isEqualTo("resolve");

        // ② 损毁包：.docx 扩展名但不是 zip
        Path corrupted = workspace.resolve("corrupted.docx");
        Files.write(corrupted, "this is plain text, not a zip archive".getBytes(StandardCharsets.UTF_8));
        ToolOutput bad = invoke(inspect, Map.of("path", corrupted.toString()));
        assertThat(bad.isSuccess()).isFalse();
        assertThat(dataOf(bad).get("reason")).isEqualTo(Feat032SutSupport.REASON_FILE_INVALID);

        // ③ 未支持格式：.wps
        Path wps = workspace.resolve("another.wps");
        Files.write(wps, Feat032SutSupport.unknownMagicBytes());
        ToolOutput unsupported = invoke(inspect, Map.of("path", wps.toString()));
        assertThat(unsupported.isSuccess()).isFalse();
        assertThat(dataOf(unsupported).get("reason")).isEqualTo(Feat032SutSupport.REASON_FORMAT_UNSUPPORTED);
    }

    private static Map<String, Object> merge(Map<String, Object> base, String key, Object value) {
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(base);
        merged.put(key, value);
        return merged;
    }
}
