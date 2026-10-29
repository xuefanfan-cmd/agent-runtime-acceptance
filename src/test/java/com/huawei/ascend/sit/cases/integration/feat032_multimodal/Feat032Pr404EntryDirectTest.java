/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat032_multimodal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.common.exception.BaseError;
import com.openjiuwen.core.context.ContextEngine;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.ModelInvokeOptions;
import com.openjiuwen.core.foundation.llm.multimodal.MaterialAccessConfig;
import com.openjiuwen.core.foundation.llm.multimodal.MultimodalErrorReasons;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.harness.tools.multimodal.FileSystemImageAssetStore;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * FEAT-032 × PR !404 直驱验收：Agent 入口支持图文内容块输入与图片来源解析
 * （agent-core-java PR !404，feat/multimodal-entry-m1m2@1dab2530，2026-10-20 合入 develop）。
 *
 * <p><b>覆盖映射</b>（测试设计场景矩阵 F032-01..11 到 PR !404 交付面）：
 * M1 图文内容块契约与入口分流 对应 F032-01/02/03/04/05（invoke/stream 同一分流点、
 * 四类输入判别、旧 image_url 转换、非法输入显式失败）；M2 图片来源解析 对应
 * F032-06/07/08/09/10/11（raw/path/asset 三来源、url 显式失败、allowedRoots 守卫、
 * 版本钉住、MIME 魔数、字节预算、非图片附件）。
 *
 * <p><b>方法学</b>：与 PR !404 自带 MultimodalAgentInputTest 相同的请求捕获法——真实
 * ReActAgent.invoke/stream 加捕获型 ModelClient（离线、无真实模型），断言全部落在
 * wire 契约（UserMessage content parts、BaseError.details.reason）上。
 * 场景级真实模型闭环（F032-01/02/12/13/15 全链路）另见 Feat032SutGatedBlackboxTest。
 *
 * <p><b>与 L2 基线的口径差异（2026-10-23 实测记录，不算缺陷）</b>：PR !404 的字节预算
 * 超限在 core 侧以 details.reason=IMAGE_LOAD_FAILED（message 含 material budget exceeded，
 * details 携带 limit/actual）呈现；L2 §2.8 记录的 CONTENT_LIMIT_EXCEEDED 由 solution
 * 工具侧（ImageMaterialException）承载。F032-10 直驱断言按 PR !404 实测口径执行。
 *
 * @since 0.1.2
 */
@Tag("integration")
@Tag("feat-032")
@Tag("multimodal")
@Tag("pr-404")
@Feature("FEAT-032: 智能体支持多模态")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat032Pr404EntryDirectTest {

    private static final byte[] PNG_BYTES = Feat032SutSupport.pngMagicBytes();
    private static final byte[] JPEG_BYTES = Feat032SutSupport.jpegMagicBytes();
    private static final String PNG_DATA_URL =
            "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES);
    private static final String JPEG_DATA_URL =
            "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(JPEG_BYTES);

    @TempDir
    Path workspace;

    @TempDir
    Path outsideDir;

    // ------------------------------------------------------------------
    // F032-01 图文联合输入直传（PR !404 M1：invoke 分流 + wire parts 保序）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-01")
    @Story("FEAT-032.F032-01 x PR!404: 结构化图文输入按序进入同次模型调用，data: 原生块编码")
    @DisplayName("F032-01[直驱] invoke 图文 content blocks -> 1 text + 1 image_url 保序")
    void f03201InvokeStructuredTextAndImageEntersSingleCallOrdered() {
        CapturingModelClient client = new CapturingModelClient();
        ReActAgent agent = newAgent(client);
        List<Object> blocks = List.of(
                Map.of("type", "text", "text", "识别图片中的印刷码"),
                imageBlock("img-1", rawSource(PNG_BYTES),
                        Map.of("description", "first page", "mediaType", "image/png")));

        agent.invoke(blocks, new TestSession());

        assertThat(client.calls).as("恰好 1 次模型调用").hasSize(1);
        List<Map<String, Object>> parts = lastUserParts(client);
        assertThat(parts.get(0)).containsEntry("type", "text").containsEntry("text", "识别图片中的印刷码");
        assertThat(parts.get(1)).containsEntry("type", "text").containsEntry("text", "first page");
        assertThat(parts.get(2)).containsEntry("type", "image_url");
        assertThat(imageUrlOf(parts.get(2))).containsEntry("url", PNG_DATA_URL);
        assertThat(String.valueOf(lastUserMessage(client).getContent()))
                .as("raw 来源不出现本地路径字符串").doesNotContain(workspace.toString());
    }

    @Test
    @Tag("story-feat-032-f032-01")
    @Story("FEAT-032.F032-01 x PR!404: stream 与 invoke 共用同一分流点")
    @DisplayName("F032-01[直驱] stream 同请求捕获 -> 相同 wire parts")
    void f03201StreamSharesDispatchWithInvoke() {
        CapturingModelClient client = new CapturingModelClient();
        ReActAgent agent = newAgent(client);
        List<Object> blocks = List.of(
                Map.of("type", "text", "text", "describe"),
                imageBlock("img-1", rawSource(PNG_BYTES), Map.of()));

        Iterator<Object> iterator = agent.stream(
                Map.of("conversation_id", "stream-session", "query", blocks), new TestSession(), List.of());
        while (iterator.hasNext()) {
            iterator.next();
        }

        List<Map<String, Object>> parts = lastUserParts(client);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).containsEntry("type", "text").containsEntry("text", "describe");
        assertThat(imageUrlOf(parts.get(1))).containsEntry("url", PNG_DATA_URL);
    }

    // ------------------------------------------------------------------
    // F032-02 多图顺序与说明保全（PR !404 M1：保序保量、不去重）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-02")
    @Story("FEAT-032.F032-02 x PR!404: 双图按提交顺序进入请求且 description 不丢失")
    @DisplayName("F032-02[直驱] 双图顺序与来源保全")
    void f03202TwoImagesEnterRequestInOrderWithDescriptions() {
        CapturingModelClient client = new CapturingModelClient();
        ReActAgent agent = newAgent(client);
        List<Object> blocks = List.of(
                Map.of("type", "text", "text", "对比两张图"),
                imageBlock("img-a", rawSource(PNG_BYTES), Map.of("description", "图A印刷码AAA-111")),
                imageBlock("img-b", rawSource(JPEG_BYTES), Map.of("description", "图B印刷码BBB-222")));

        agent.invoke(blocks, new TestSession());

        List<Map<String, Object>> parts = lastUserParts(client);
        assertThat(parts).hasSize(5);
        assertThat(imageUrlOf(parts.get(2))).containsEntry("url", PNG_DATA_URL);
        assertThat(imageUrlOf(parts.get(4))).containsEntry("url", JPEG_DATA_URL);
        assertThat(String.valueOf(parts.get(1).get("text"))).contains("图A印刷码AAA-111");
        assertThat(String.valueOf(parts.get(3).get("text"))).contains("图B印刷码BBB-222");
    }

    // ------------------------------------------------------------------
    // F032-03 纯文本与旧业务 Map 兼容（PR !404 M1：原路径逐字节不变）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-03")
    @Story("FEAT-032.F032-03 x PR!404: 纯字符串与旧业务 Map 走原路径，content 仍为字符串")
    @DisplayName("F032-03[直驱] 纯字符串/旧 Map 输入不被结构化分支改写")
    void f03203PlainTextAndLegacyBusinessMapKeepOriginalShape() {
        CapturingModelClient client = new CapturingModelClient();
        ReActAgent agent = newAgent(client);

        agent.invoke("你好，帮我看看这张图", new TestSession());
        assertThat(lastUserMessage(client).getContent()).isInstanceOf(String.class);
        assertThat(lastUserMessage(client).getContent()).isEqualTo("你好，帮我看看这张图");

        agent.invoke(Map.of("conversation_id", "biz-session", "query", "查天气", "city", "深圳"),
                new TestSession());
        assertThat(lastUserMessage(client).getContent()).isEqualTo("查天气");

        assertThat(agent.extractUserText("plain")).isEqualTo("plain");
        assertThat(agent.extractUserText(List.of("a", "b"))).isEqualTo("[a, b]");
        List<?> normalized = agent.extractUserContentBlocks(List.of(
                Map.of("type", "text", "text", "hello")));
        assertThat(normalized).hasSize(1);
    }

    // ------------------------------------------------------------------
    // F032-04 旧 image_url 块转换与裸路径拒绝（PR !404 M1）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-04")
    @Story("FEAT-032.F032-04 x PR!404: legacy image_url data: 解一次 base64 转 raw；裸路径 CONTENT_INVALID")
    @DisplayName("F032-04[直驱] legacy image_url 转换与裸路径拒绝")
    void f03204LegacyImageUrlConvertedAndBarePathRejected() {
        CapturingModelClient client = new CapturingModelClient();
        ReActAgent agent = newAgent(client);
        String legacyUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES);
        List<Object> dataUrlBlocks = List.of(
                Map.of("type", "text", "text", "legacy shape"),
                Map.of("type", "image_url", "image_url", Map.of("url", legacyUrl)));

        agent.invoke(dataUrlBlocks, new TestSession());
        List<Map<String, Object>> parts = lastUserParts(client);
        assertThat(parts).hasSize(2);
        assertThat(imageUrlOf(parts.get(1))).containsEntry("url", PNG_DATA_URL);

        assertThatThrownBy(() -> agent.invoke(List.of(
                        Map.of("type", "image_url", "image_url", Map.of("url", "C:\\ws\\a.png"))),
                new TestSession()))
                .isInstanceOfSatisfying(BaseError.class, error -> assertThat(details(error))
                        .containsEntry("reason", MultimodalErrorReasons.CONTENT_INVALID));
        assertThat(client.calls).as("拒绝发生在解析阶段，零新增模型调用").hasSize(1);
    }

    // ------------------------------------------------------------------
    // F032-05 结构化输入非法显式失败（PR !404 M1：不回退 String.valueOf）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-05")
    @Story("FEAT-032.F032-05 x PR!404: 不识别 type / 多 kind 冲突 / 缺 source 显式失败并定位到块")
    @DisplayName("F032-05[直驱] 非法输入 CONTENT_INVALID 携带 blockId/field 定位")
    void f03205IllegalStructuredInputFailsExplicitlyWithLocation() {
        CapturingModelClient client = new CapturingModelClient();
        ReActAgent agent = newAgent(client);

        assertThatThrownBy(() -> agent.invoke(
                        List.of(Map.of("type", "audio", "id", "snd-1")), new TestSession()))
                .isInstanceOfSatisfying(BaseError.class, error -> assertThat(details(error))
                        .containsEntry("reason", MultimodalErrorReasons.CONTENT_INVALID)
                        .containsEntry("blockId", "snd-1")
                        .containsEntry("field", "type"));

        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("kind", "raw");
        conflict.put("bytes", PNG_BYTES);
        conflict.put("path", workspace.resolve("a.png").toString());
        assertThatThrownBy(() -> agent.invoke(
                        List.of(imageBlock("img-c", conflict, Map.of())), new TestSession()))
                .isInstanceOfSatisfying(BaseError.class, error -> {
                    assertThat(details(error)).containsEntry("reason", MultimodalErrorReasons.CONTENT_INVALID);
                    assertThat(error.getMessage()).contains("only one source kind");
                });

        assertThatThrownBy(() -> agent.invoke(
                        List.of(Map.of("type", "image", "id", "img-empty")), new TestSession()))
                .isInstanceOfSatisfying(BaseError.class, error -> assertThat(details(error))
                        .containsEntry("reason", MultimodalErrorReasons.CONTENT_INVALID));

        assertThatThrownBy(() -> agent.invoke(List.of(), new TestSession()))
                .isInstanceOfSatisfying(BaseError.class, error -> assertThat(details(error))
                        .containsEntry("reason", MultimodalErrorReasons.CONTENT_INVALID));

        assertThat(client.calls).as("全部非法输入在解析阶段显式失败，零模型调用").isEmpty();
    }

    // ------------------------------------------------------------------
    // F032-06 kind=url 来源不下载不透传（PR !404 M2）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-06")
    @Story("FEAT-032.F032-06 x PR!404: url 来源解析阶段 SOURCE_UNRESOLVED，不透传不降级")
    @DisplayName("F032-06[直驱] url 来源显式 SOURCE_UNRESOLVED 零模型调用")
    void f03206UrlSourceFailsSourceUnresolvedWithoutTransmission() {
        CapturingModelClient client = new CapturingModelClient();
        ReActAgent agent = newAgent(client);

        assertThatThrownBy(() -> agent.invoke(List.of(imageBlock("img-url",
                        Map.of("kind", "url", "url", "https://files.example/b.png"), Map.of())),
                new TestSession()))
                .isInstanceOfSatisfying(BaseError.class, error -> {
                    assertThat(details(error)).containsEntry("reason", MultimodalErrorReasons.SOURCE_UNRESOLVED);
                    assertThat(error.getMessage()).contains("material blocks failed");
                });
        assertThat(client.calls).as("不发起下载、URL 不透传给模型").isEmpty();
    }

    // ------------------------------------------------------------------
    // F032-07 path 来源受控读取与 allowedRoots 越界拒绝（PR !404 M2）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-07")
    @Story("FEAT-032.F032-07 x PR!404: 工作区内 path 编码为 data: 且路径零泄漏；区外/穿越被拒")
    @DisplayName("F032-07[直驱] allowedRoots canonical 守卫与受控读取")
    void f03207PathSourceGuardedReadAndOutsideDenied() throws Exception {
        CapturingModelClient client = new CapturingModelClient();
        ReActAgent agent = newAgent(client);
        Path inside = workspace.resolve("inside.png");
        Files.write(inside, PNG_BYTES);

        agent.invoke(List.of(imageBlock("img-in",
                Map.of("kind", "path", "path", inside.toString()), Map.of())), new TestSession());
        assertThat(imageUrlOf(lastUserParts(client).get(0))).containsEntry("url", PNG_DATA_URL);
        assertThat(String.valueOf(lastUserMessage(client).getContent()))
                .as("path 字符串零泄漏").doesNotContain(inside.toString());

        Path outside = outsideDir.resolve("secret.png");
        Files.write(outside, PNG_BYTES);
        assertThatThrownBy(() -> agent.invoke(List.of(imageBlock("img-out",
                        Map.of("kind", "path", "path", outside.toString()), Map.of())), new TestSession()))
                .isInstanceOfSatisfying(BaseError.class, error -> assertThat(details(error))
                        .containsEntry("reason", MultimodalErrorReasons.SOURCE_UNRESOLVED));

        assertThatThrownBy(() -> agent.invoke(List.of(imageBlock("img-traverse",
                        Map.of("kind", "path", "path",
                                workspace.resolve("..").resolve("secret.png").toString()), Map.of())),
                new TestSession()))
                .isInstanceOfSatisfying(BaseError.class, error -> assertThat(details(error))
                        .containsEntry("reason", MultimodalErrorReasons.SOURCE_UNRESOLVED));

        assertThat(client.calls).as("越界与穿越均零模型调用").hasSize(1);
    }

    // ------------------------------------------------------------------
    // F032-08 asset 版本固定与 VERSION_CONFLICT（PR !404 M2）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-08")
    @Story("FEAT-032.F032-08 x PR!404: 版本钉住（同 ID 换版本不读到新内容）；缺失版本 VERSION_CONFLICT")
    @DisplayName("F032-08[直驱] asset 版本固定与冲突语义")
    void f03208AssetVersionPinnedAndConflictReported() {
        CapturingModelClient client = new CapturingModelClient();
        ReActAgent agent = newAgent(client);
        FileSystemImageAssetStore store = new FileSystemImageAssetStore(Path.of(assetRoot()));
        store.put("doc-2", "v1", PNG_BYTES, "image/png");
        List<Object> blocks = List.of(imageBlock("img-a2",
                Map.of("kind", "asset", "assetId", "doc-2", "version", "v1"), Map.of()));

        agent.invoke(blocks, new TestSession());
        store.put("doc-2", "v2", JPEG_BYTES, "image/jpeg");
        agent.invoke(blocks, new TestSession());
        assertThat(client.calls).hasSize(2);
        assertThat(imageUrlOf(lastUserParts(client).get(0))).containsEntry("url", PNG_DATA_URL);

        assertThatThrownBy(() -> agent.invoke(List.of(imageBlock("img-miss",
                        Map.of("kind", "asset", "assetId", "doc-2", "version", "vX"), Map.of())),
                new TestSession()))
                .isInstanceOfSatisfying(BaseError.class, error -> assertThat(details(error))
                        .containsEntry("reason", MultimodalErrorReasons.VERSION_CONFLICT));
        assertThat(client.calls).as("版本冲突不进入模型调用").hasSize(2);
    }

    // ------------------------------------------------------------------
    // F032-09 MIME 以实际字节判定（PR !404 M2：魔数优先，声明值不被盲信）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-09")
    @Story("FEAT-032.F032-09 x PR!404: 声明 image/jpeg 的 PNG 字节按实际魔数判定")
    @DisplayName("F032-09[直驱] MIME 魔数判定优先于声明值")
    void f03209MimeResolvedByMagicBytesNotDeclaration() {
        CapturingModelClient client = new CapturingModelClient();
        ReActAgent agent = newAgent(client);

        agent.invoke(List.of(imageBlock("img-mime", rawSource(PNG_BYTES),
                Map.of("mediaType", "image/jpeg"))), new TestSession());
        assertThat(imageUrlOf(lastUserParts(client).get(0)))
                .as("PNG 字节声明为 image/jpeg 仍按实际魔数判定")
                .containsEntry("url", PNG_DATA_URL);
        assertThat(client.calls).hasSize(1);
    }

    // ------------------------------------------------------------------
    // F032-10 字节预算超限（PR !404 M2：预检 + 超限断流）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-10")
    @Story("FEAT-032.F032-10 x PR!404: 注入小限额后超限图片整请求显式失败，details 含 limit/actual")
    @DisplayName("F032-10[直驱] 注入限额触发预算失败（PR !404 口径：IMAGE_LOAD_FAILED + limit/actual）")
    void f03210BudgetExceededFailsExplicitlyWithLimits() {
        CapturingModelClient client = new CapturingModelClient();
        MaterialAccessConfig materialAccess = baseMaterialAccess();
        materialAccess.setMaxImageBytes(8L);
        materialAccess.setMaxTotalImageBytes(8L);
        ReActAgent agent = newAgent(client, materialAccess);
        byte[] oversized = new byte[64];
        oversized[0] = (byte) 0x89;
        oversized[1] = 'P';
        oversized[2] = 'N';
        oversized[3] = 'G';
        oversized[4] = 0x0D;
        oversized[5] = 0x0A;
        oversized[6] = 0x1A;
        oversized[7] = 0x0A;

        assertThatThrownBy(() -> agent.invoke(List.of(imageBlock("img-big",
                        Map.of("kind", "raw", "bytes", oversized), Map.of("mediaType", "image/png"))),
                new TestSession()))
                .isInstanceOfSatisfying(BaseError.class, error -> {
                    Map<String, Object> detail = details(error);
                    assertThat(detail)
                            .as("PR !404 实测口径：core 预算超限以 IMAGE_LOAD_FAILED 呈现"
                                    + "（CONTENT_LIMIT_EXCEEDED 由 solution 工具侧承载，见类注释）")
                            .containsEntry("reason", MultimodalErrorReasons.IMAGE_LOAD_FAILED);
                    // limit/actual 位于 details.items[0]（逐项失败明细）
                    assertThat((java.util.List<?>) detail.get("items")).hasSize(1);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = (Map<String, Object>) ((java.util.List<?>) detail.get("items")).get(0);
                    assertThat(item)
                            .containsEntry("reason", MultimodalErrorReasons.IMAGE_LOAD_FAILED)
                            .containsEntry("limit", 8L)
                            .containsEntry("actual", 64L)
                            .containsEntry("stage", "budget.image");
                    assertThat(String.valueOf(item.get("message"))).contains("material budget exceeded");
                    assertThat(error.getMessage()).as("顶层为逐项汇总文案").contains("all 1 material blocks failed");
                });
        assertThat(client.calls).as("超限断流，零模型调用").isEmpty();
    }

    // ------------------------------------------------------------------
    // F032-11 非图片 file 块无处理链（PR !404 M2）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-11")
    @Story("FEAT-032.F032-11 x PR!404: 非图片附件无处理链报 FILE_PROCESSING_REQUIRED，不静默只发文件名")
    @DisplayName("F032-11[直驱] 非图片 file 块显式 FILE_PROCESSING_REQUIRED")
    void f03211NonImageFileBlockFailsFileProcessingRequired() {
        CapturingModelClient client = new CapturingModelClient();
        ReActAgent agent = newAgent(client);

        assertThatThrownBy(() -> agent.invoke(List.of(fileBlock("file-1",
                        rawSource("%PDF-1.4".getBytes(StandardCharsets.UTF_8)),
                        Map.of("mediaType", "application/pdf", "filename", "report.pdf"))),
                new TestSession()))
                .isInstanceOfSatisfying(BaseError.class, error -> assertThat(details(error))
                        .containsEntry("reason", MultimodalErrorReasons.FILE_PROCESSING_REQUIRED)
                        .containsEntry("blockId", "file-1")
                        .containsEntry("filename", "report.pdf"));
        assertThat(client.calls).as("不静默只发文件名，零模型调用").isEmpty();
    }

    // ==================================================================
    // Fixture：真实 ReActAgent + 捕获型 ModelClient（对齐 PR !404 请求捕获方法学）
    // ==================================================================

    private MaterialAccessConfig baseMaterialAccess() {
        MaterialAccessConfig materialAccess = new MaterialAccessConfig();
        materialAccess.setAllowedRoots(List.of(workspace.toString()));
        materialAccess.setAssetRoot(assetRoot());
        return materialAccess;
    }

    private String assetRoot() {
        return workspace.resolve(".material-assets").toString();
    }

    private ReActAgent newAgent(CapturingModelClient client) {
        return newAgent(client, baseMaterialAccess());
    }

    private ReActAgent newAgent(CapturingModelClient client, MaterialAccessConfig materialAccess) {
        ReActAgent agent = new ReActAgent(new AgentCard("feat032-pr404-agent", "feat032-pr404-agent",
                "FEAT-032 PR!404 entry acceptance agent"));
        agent.configure(new ReActAgentConfig()
                .configureMaxIterations(3)
                .configureMaterialAccess(materialAccess));
        agent.setLlm(new Model(client));
        return agent;
    }

    private static Map<String, Object> rawSource(byte[] bytes) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", "raw");
        source.put("bytes", bytes);
        return source;
    }

    private static Map<String, Object> imageBlock(String id, Map<String, Object> source,
                                                  Map<String, Object> extraFields) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("id", id);
        block.put("source", source);
        block.putAll(extraFields);
        return block;
    }

    private static Map<String, Object> fileBlock(String id, Map<String, Object> source,
                                                 Map<String, Object> extraFields) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "file");
        block.put("id", id);
        block.put("source", source);
        block.putAll(extraFields);
        return block;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(BaseError error) {
        return (Map<String, Object>) error.getDetails();
    }

    private static UserMessage lastUserMessage(CapturingModelClient client) {
        assertThat(client.calls).isNotEmpty();
        List<BaseMessage> messages = client.calls.get(client.calls.size() - 1);
        BaseMessage lastUser = null;
        for (BaseMessage message : messages) {
            if (message instanceof UserMessage user) {
                lastUser = user;
            }
        }
        assertThat(lastUser).as("每次捕获的请求必须含 UserMessage").isNotNull();
        return (UserMessage) lastUser;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> partsOf(UserMessage user) {
        assertThat(user.getContent()).as("结构化输入的 UserMessage content 必须保持 parts 结构")
                .isInstanceOf(List.class);
        return (List<Map<String, Object>>) user.getContent();
    }

    private static List<Map<String, Object>> lastUserParts(CapturingModelClient client) {
        return partsOf(lastUserMessage(client));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> imageUrlOf(Map<String, Object> part) {
        assertThat(part).containsEntry("type", "image_url");
        Object url = part.get("image_url");
        if (url instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return part;
    }

    /**
     * 轻量会话（对齐 PR !404 测试的 MemorySession：AgentSessionApi + SessionPort 最小实现）。
     */
    static final class TestSession implements AgentSessionApi, ContextEngine.SessionPort {
        private final String sessionId = "feat032-" + UUID.randomUUID();
        private final Map<String, Object> state = new LinkedHashMap<>();
        private final List<Object> stream = new ArrayList<>();

        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public Object getState(String key) {
            return state.get(key);
        }

        @Override
        public void updateState(Map<String, Object> data) {
            state.putAll(data);
        }

        @Override
        public void writeStream(Object data) {
            stream.add(data);
        }

        @Override
        public Iterator<Object> streamIterator() {
            return stream.iterator();
        }
    }

    /** 捕获型模型客户端：记录每次进入模型调用的完整消息列表（离线，返回固定应答）。 */
    static final class CapturingModelClient implements Model.ModelClient {
        final List<List<BaseMessage>> calls = new ArrayList<>();

        @Override
        public CompletionStage<AssistantMessage> invoke(List<BaseMessage> messages,
                                                        ModelInvokeOptions options) {
            calls.add(List.copyOf(messages));
            return CompletableFuture.completedFuture(new AssistantMessage("ok"));
        }

        @Override
        public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages,
                                                      ModelInvokeOptions options) {
            calls.add(List.copyOf(messages));
            return new Iterator<>() {
                private boolean done;

                @Override
                public boolean hasNext() {
                    return !done;
                }

                @Override
                public AssistantMessageChunk next() {
                    done = true;
                    return AssistantMessageChunk.builder().content("ok").finishReason("stop").build();
                }
            };
        }
    }
}
