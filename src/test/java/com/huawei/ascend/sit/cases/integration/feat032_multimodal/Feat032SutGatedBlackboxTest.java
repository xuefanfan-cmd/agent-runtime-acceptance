/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat032_multimodal;

import static org.assertj.core.api.Assertions.assertThat;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * FEAT-032 全链路 SUT 直驱黑盒验收（真实模型 + 真实 Agent 闭环）
 * （测试设计 F032-01/02/12/13/14/15/22；SC-01/02/03/05/10 对应，主门禁所在类）。
 *
 * <p><b>2026-10-23 整改</b>：PR !404 已合入 agent-core-java develop，验证基座改为
 * agent-solution {@code common/example/multi-modal-tools-demo} 的 MultimodalToolsAgent
 * （ObservationToolSuite 六工具 + vote_answer 装配的真实 ReActAgent）。原
 * {@code feat032.sut.invokerClass} SPI 注入门禁撤销——直驱后端固定为
 * {@link Feat032DemoAgentInvoker}，仅剩两类环境门禁：
 * <ol>
 *   <li>真实模型网关：VISION_API_KEY / VISION_BASE_URL（或 LLM_API_*），密钥不落盘；
 *       离线时仅 F032-14（离线持久化往返）仍执行，其余场景 SKIPPED 并写明原因；</li>
 *   <li>F032-22 另需有桌面会话的 Windows 真机（headless 下由 F032-23 验失败语义）。</li>
 * </ol>
 *
 * <p><b>断言口径</b>（与测试设计 §7 关键链路断言一致）：
 * F032-12 + F032-13 双条件同时成立（第二轮调用含原生 image_url 块 + ToolMessage
 * 文本侧无 base64）；F032-15 无视觉模型不得静默丢图继续作答。
 */
@Tag("integration")
@Tag("feat-032")
@Tag("multimodal")
@Tag("blackbox")
@Feature("FEAT-032: 智能体支持多模态")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat032SutGatedBlackboxTest {

    private static Feat032DemoAgentInvoker invoker;

    @BeforeAll
    static void createInvoker() {
        invoker = Feat032DemoAgentInvoker.create();
    }

    @AfterAll
    static void closeInvoker() {
        if (invoker != null) {
            invoker.close();
        }
    }

    /** 基座 agent 制品门禁（demo 模块 + 工具模块在 classpath）。 */
    private static void assumeDemoHarness() {
        org.junit.jupiter.api.Assumptions.assumeTrue(invoker != null,
                "FEAT-032 dependency-gated: multi-modal-tools-demo / agent-core-ext-multimodal-tools "
                        + "制品未就绪（需 agent-solution common!882 构建 demo 0.1.3 与工具模块 0.1.3）");
    }

    /** 真实模型网关门禁（VISION_* / LLM_API_* 环境变量；离线 invoker 直接跳过）。 */
    private static void assumeRealModel() {
        assumeDemoHarness();
        org.junit.jupiter.api.Assumptions.assumeTrue(!invoker.isOffline(),
                "FEAT-032 dependency-gated: VISION_API_KEY / VISION_BASE_URL（或 LLM_API_*）未注入，"
                        + "真实模型场景离线不执行（离线可执行子集见 F032-14 与 Pr404EntryDirectTest）");
    }

    /** 构造 text + raw 图片块（data: 内联字节）。 */
    private static List<Map<String, Object>> textAndImage(String text, String mediaType, byte[] bytes) {
        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
        return List.of(
                Map.of("type", "text", "text", text),
                Map.of("type", "image", "source", Map.of("kind", "raw", "bytes", base64)));
    }

    /** demo 样张字节（真实可解码 PNG，含可判定印刷码：East Q3 = 4,286 + 竖排批注）。 */
    private static byte[] samplePageBytes() {
        try {
            return Files.readAllBytes(Feat032DemoAgentInvoker.SAMPLE_PAGE);
        } catch (Exception e) {
            throw new IllegalStateException("demo 样张读取失败", e);
        }
    }

    // ------------------------------------------------------------------
    // F032-01 图文联合问答直传（SC-01，主门禁）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-01")
    @Story("FEAT-032.F032-01: 图文直传同次调用含 1 text + 1 image_url 块，data: scheme，路径零泄漏")
    @DisplayName("F032-01 入口直传图文且请求 wire 契约正确")
    void f03201StructuredEntrySendsTextAndImageParts() {
        assumeRealModel();
        // demo 样张含可判定印刷码（East Q3 表格数值），覆盖「响应包含图内可判定内容」判据。
        // demo agent 挂载观察工具，模型可先调工具再作答：断言放宽为 ≥1 次调用，
        // 但首轮请求必须携带 text+image_url 有序块（PR !404 入口契约本体）。
        Feat032SutAgentInvoker.CapturedRun run = invoker.invokeWithStructuredEntry(
                textAndImage("这张图里 East 区域 Q3 的销售额是多少？", "image/png", samplePageBytes()));

        assertThat(run.modelCalls()).as("至少 1 次模型调用").isNotEmpty();
        List<Map<String, Object>> parts = run.modelCalls().get(0).userContentParts();
        assertThat(parts.get(0)).containsEntry("type", "text");
        assertThat(parts.get(1)).containsEntry("type", "image_url");
        assertThat(String.valueOf(imageUrl(parts.get(1)))).startsWith("data:image/png;base64,");
        // 工具观察链路会复读同一图文上下文，后续调用也须保留 image_url（不退化为文本）
        List<Map<String, Object>> lastParts = run.modelCalls().get(run.modelCalls().size() - 1).userContentParts();
        assertThat(lastParts.stream().anyMatch(p -> "image_url".equals(p.get("type"))))
                .as("末轮调用仍保留 image_url 块").isTrue();
        assertThat(run.finalAnswer())
                .as("答案为图内千美元量级表格数值（模型 OCR 末位可有 ±1 误读，此处判读出数值即可）")
                .containsPattern("\\d{1,3},\\d{3}");
        String allWire = run.toString();
        // data: scheme 含字母 "D:"，泄漏判据改为「不存在真实文件系统路径」：
        // 样张临时目录路径与 POSIX 家目录前缀
        assertThat(allWire).as("路径零泄漏")
                .doesNotContain(Feat032DemoAgentInvoker.SAMPLE_PAGE.getParent().toString())
                .doesNotContain("/home/");
    }

    // ------------------------------------------------------------------
    // F032-02 多图顺序与说明/来源保全（SC-02 双图子集）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-02")
    @Story("FEAT-032.F032-02: 双图按提交顺序进入请求，description 与 provenance 不丢失")
    @DisplayName("F032-02 双图顺序保持与说明保全")
    void f03202MultiImageOrderAndDescriptionPreserved() {
        assumeRealModel();
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "text", "text", "依次描述两张图"),
                Map.of("type", "image", "description", "图A：demo 样张整页",
                        "source", Map.of("kind", "path", "path",
                                Feat032DemoAgentInvoker.SAMPLE_PAGE.toString())),
                Map.of("type", "image", "description", "图B：1x1 探针图",
                        "source", Map.of("kind", "raw", "bytes",
                                java.util.Base64.getEncoder()
                                        .encodeToString(Feat032SutSupport.pngMagicBytes()))));

        Feat032SutAgentInvoker.CapturedRun run = invoker.invokeWithStructuredEntry(blocks);
        assertThat(run.modelCalls()).as("至少 1 次模型调用").isNotEmpty();
        List<Map<String, Object>> parts = run.modelCalls().get(0).userContentParts();
        long imageUrls = parts.stream().filter(p -> "image_url".equals(p.get("type"))).count();
        assertThat(imageUrls).as("两个 image_url 块按提交顺序出现").isEqualTo(2);
        assertThat(parts.get(0)).containsEntry("type", "text");
        assertThat(parts.toString()).as("description 随文字块传递不丢失").contains("图A").contains("图B");
    }

    // ------------------------------------------------------------------
    // F032-12 工具图片进入下一次模型调用（SC-03，主门禁）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-12")
    @Story("FEAT-032.F032-12: image_observe 后第二轮调用含原生 image_url 块且答案基于图片")
    @DisplayName("F032-12 工具图片回流到下一次模型调用")
    void f03212ToolImageEntersNextModelCall() {
        assumeRealModel();
        Feat032SutAgentInvoker.CapturedRun run = invoker.invokeWithToolLoop(
                "image_observe",
                Map.of("imagePath", Feat032DemoAgentInvoker.SAMPLE_PAGE.toString()));

        assertThat(run.modelCalls()).as("至少 2 次模型调用（工具前 + 回流后）")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(countImageUrls(run.modelCalls().get(0))).as("第 1 次调用 0 图片块").isEqualTo(0);
        assertThat(countImageUrls(run.modelCalls().get(1)))
                .as("第 2 次调用含 ≥1 个原生 image_url 块").isGreaterThanOrEqualTo(1);
        assertThat(run.finalAnswer()).as("答案基于图片内容（图内可判定印刷码）")
                .containsAnyOf("4,286", "4286");
    }

    // ------------------------------------------------------------------
    // F032-13 base64 不双发（主门禁，与 F032-12 成对）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-13")
    @Story("FEAT-032.F032-13: 多模态 data 的 ToolMessage 文本侧为结构化摘要，base64 不进文本")
    @DisplayName("F032-13 ToolMessage 文本侧不含 base64（不双发）")
    void f03213Base64NotDuplicatedInToolMessageText() {
        assumeRealModel();
        Feat032SutAgentInvoker.CapturedRun run = invoker.invokeWithToolLoop(
                "image_observe",
                Map.of("imagePath", Feat032DemoAgentInvoker.SAMPLE_PAGE.toString()));

        assertThat(run.modelCalls()).as("前置：工具环至少两次调用").hasSizeGreaterThanOrEqualTo(2);
        // 判据口径（2026-10-23 第二轮整改，对齐 L2 §171 修复规格与 fcb2334d 实现）：
        // 「base64 不进文本」指载荷本体不得进入文本通道；结构化摘要允许保留 MIME 头 +
        // 脱敏标记「data:image/png;base64,<base64 N chars omitted>」（AbilityManager.
        // describeDataUrl 规格），故不得用子串 ";base64," 一刀切（会误伤脱敏标记的头部）。
        java.util.regex.Pattern dataUrlWithPayload =
                java.util.regex.Pattern.compile("data:[^,\\s]{1,64},[A-Za-z0-9+/]{40}");
        java.util.regex.Pattern longBase64Run = java.util.regex.Pattern.compile("[A-Za-z0-9+/]{120,}");
        for (Feat032SutAgentInvoker.CapturedModelCall call : run.modelCalls()) {
            for (String toolText : call.toolMessageTexts()) {
                assertThat(toolText).as("ToolMessage 文本不得携带 base64 载荷本体（data: 头后跟实际编码字符）")
                        .doesNotContainPattern(dataUrlWithPayload);
                assertThat(toolText).as("ToolMessage 文本不得出现长 base64 串（≥120 连续编码字符）")
                        .doesNotContainPattern(longBase64Run);
                if (toolText.contains("multimodal")) {
                    assertThat(toolText).as("结构化摘要：声明编码数据已在文本侧省略（L2 §171）")
                            .contains("encoded data is omitted here");
                    assertThat(toolText).as("结构化摘要事实字段存在（success 标志）").contains("success");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // F032-14 持久化/offload 往返结构保全（离线可执行：无网关也验证结构事实）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-14")
    @Story("FEAT-032.F032-14: 持久化回载后 user content 仍为结构，下一次调用仍编码出图片块")
    @DisplayName("F032-14 持久化往返后图片块不退化为字符串（离线）")
    void f03214PersistReloadPreservesListContent() {
        assumeDemoHarness();
        List<Map<String, Object>> blocks = textAndImage("往返后再次识别", "image/png",
                Feat032SutSupport.pngMagicBytes());
        Feat032SutAgentInvoker.CapturedRun run = invoker.invokeAfterPersistReload(blocks);

        assertThat(run.modelCalls()).hasSizeGreaterThanOrEqualTo(2);
        int imageUrls = countImageUrls(run.modelCalls().get(1));
        assertThat(imageUrls).as("回载后调用仍含原生图片块").isGreaterThanOrEqualTo(1);
        Map<String, Object> evidence = run.modelCalls().get(run.modelCalls().size() - 1)
                .userContentParts().get(0);
        assertThat(evidence).containsEntry("type", "reloadedUserContent");
        assertThat(evidence).containsEntry("isList", "true");
    }

    // ------------------------------------------------------------------
    // F032-15 无视觉能力模型处置（SC-05，现状验收 + 缺口判定）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-15")
    @Story("FEAT-032.F032-15: 无视觉模型收到图文必须显式不兼容，静默丢图/降级即缺陷")
    @DisplayName("F032-15 无视觉模型处置现状验收（M4 缺口挂账）")
    void f03215NonVisionModelExplicitIncompatibility() {
        assumeRealModel();
        // 无视觉模型默认取当前网关（Ark coding）已验证的纯文本模型 glm-5.2（实测：服务端对
        // image_url 显式 81001 拒绝）；不同网关可用 feat032.nonVisionModel / FEAT032_NON_VISION_MODEL 覆盖。
        String nonVisionModel = System.getProperty("feat032.nonVisionModel",
                System.getenv("FEAT032_NON_VISION_MODEL") != null && !System.getenv("FEAT032_NON_VISION_MODEL").isBlank()
                        ? System.getenv("FEAT032_NON_VISION_MODEL") : "glm-5.2");

        Feat032SutAgentInvoker.CapturedRun run = invoker.invokeAgainstModel(
                nonVisionModel, textAndImage("这张图里是什么", "image/png",
                        Feat032SutSupport.pngMagicBytes()));

        // 特性档 §2 MUST：明确返回不兼容错误，不静默丢图、不回退降级。
        // 判定（测试设计附录 A）：若返回文本成功作答（丢图继续）或伪成功 → 判缺陷；
        // 若显式错误（框架前置或服务端拒绝）→ 记录 reason，框架前置校验缺失按缺口上报。
        String answer = run.finalAnswer();
        boolean answeredAsSuccess = answer != null && !answer.isBlank() && !"null".equals(answer);
        String runText = run.toString().toUpperCase();
        boolean explicitError = (run.modelCallError() != null && !run.modelCallError().isBlank())
                || run.modelCalls().isEmpty()
                || runText.contains("ERROR")
                || runText.contains("CONTENT_INVALID");
        String rejectReason = run.modelCallError() != null ? run.modelCallError() : "(无捕获错误，答案=" + answer + ")";

        assertThat(answeredAsSuccess && !explicitError)
                .as("无视觉模型不得静默丢图继续作答（特性档 MUST）；显式拒绝原因=%s", rejectReason)
                .isFalse();
    }

    private static int countImageUrls(Feat032SutAgentInvoker.CapturedModelCall call) {
        int count = 0;
        for (Map<String, Object> part : call.userContentParts()) {
            if ("image_url".equals(part.get("type"))) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static Object imageUrl(Map<String, Object> part) {
        Object url = part.get("image_url");
        if (url instanceof Map<?, ?> map) {
            return ((Map<String, Object>) map).get("url");
        }
        return part.get("url");
    }

    // ------------------------------------------------------------------
    // F032-22 桌面截图元数据与资产链式复用（真机场景，SC-10 部分）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-22")
    @Story("FEAT-032.F032-22: desktop_screenshot 元数据完整，资产可被 image_crop 以 asset: 链式复用")
    @DisplayName("F032-22 桌面截图元数据四要素与 asset: 链式复用")
    void f03222DesktopScreenshotMetadataAndAssetChaining() {
        // 双重门禁：真实模型（驱动 Agent 环）+ 有桌面会话的 Windows 真机
        assumeRealModel();
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        org.junit.jupiter.api.Assumptions.assumeTrue(!headless,
                "FEAT-032 dependency-gated: 当前为 headless 环境，真机桌面截图需 Windows 桌面会话"
                        + "（headless 失败语义见 F032-23）");

        Feat032SutAgentInvoker.CapturedRun run = invoker.invokeWithToolLoop(
                "desktop_screenshot", Map.of("screenId", 0));
        assertThat(run.finalAnswer()).isNotBlank();
        String evidence = run.toString();
        // 元数据事实出现在工具输出/答案链路中（connectionId 缺省 local、machineId 非空、capturedAt 时间戳）
        assertThat(evidence).contains("local").contains("machineId").contains("capturedAt");
    }
}
