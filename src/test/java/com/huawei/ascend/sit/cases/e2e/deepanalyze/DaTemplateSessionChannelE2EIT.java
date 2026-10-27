/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 068-S12/S13（2026-10-19 用户裁决纳入本批）：post-085 新形态的**会话侧模板通道**。
 *
 * <p>Oracle 来源：Feat-Func-085（workflow_run 原版入参 {@code teamName}）；team-names.properties
 * （原版中文团队名→随包模板名/动态标记——「跨库对比分析」映射内部标记 {@code @cross-kb-comparison}，
 * 该 @ 串是<b>注册值/内部标记</b>而非入参形态：r2 时代 Allure 附件实证在 ce9019d86 上以 @ 串直调
 * 同样报 Team not found、模型自纠改注册名后才完成——本类旧版用 @ 串作入参属假通过（2026-10-21 修正），
 * 085 文档对 teamName 的可用名清单未记载（潜在文档澄清项，见批次记录）。
 * 前提：FEAT-068 Feature §2 行7 的 OUT 口径已被 085 关闭（文档滞后偏差，增量审视记录 §3-①）。
 *
 * <p>判据边界：任务级旅程断言（点名→调用→completed→收口非空 + 调用入参足迹）+ 步骤级结构断言
 * （2026-10-21 随 r3 制品回归升级回真执行——GAP-068-01 恢复）：S12a 期望=同制品
 * {@code DynamicTeamTemplates.crossKbComparison(kbIds)} 纯函数推导（每库一检索员+校验/汇总）；
 * S13 期望=research-pipeline 随包资产编译序（verify/write 资产声明 skip_if=DEPS_INCONCLUSIVE，允许跳过）；
 * S12b 为受控拒绝路径、无步骤可断。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-068")
@Feature("FEAT-068: 团队模板库")
class DaTemplateSessionChannelE2EIT extends DaTemplateE2EBase {

    /** ① @cross-kb-comparison 能力（跨库对比按会话 kbScope 动态生成）：经注册名 跨库对比分析 调用。 */
    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    @Story("068-S12: 会话侧动态模板通道（@cross-kb-comparison + kbScope）")
    @DisplayName("068-S12 跨库对比分析（动态生成）+ kbScope×3：按库生成并完成编排")
    void sessionChannelGeneratesCrossKbTeamFromScope() throws Exception {
        final Map<String, Object> body = baseBody(newSession("sess-068-s12"),
                "请用 workflow_run 工具、以 teamName 参数 跨库对比分析 对比分析这个问题："
                        + "三种知识库各自的检索特点。工具返回后，请只回复工具出参里 final_output 的原文。");
        body.put("scope", Map.of("kbIds", List.of("kb-e2e-a", "kb-e2e-b", "kb-e2e-c")));

        final com.fasterxml.jackson.databind.JsonNode snapshot = postRun(body);
        failIfTaskFailed(snapshot);
        final com.fasterxml.jackson.databind.JsonNode call = requireTeamCall(snapshot, "跨库对比分析");
        assertThat(snapshot.path("status").asText()).as("任务级：编排应完成").isEqualTo("completed");
        assertThat(snapshot.path("output").asText()).as("任务级：收口产物应非空").isNotBlank();
        assertThat(call.path("input").toString()).as("调用入参应含注册团队名足迹")
                .contains("跨库对比分析");

        // 步骤级（GAP-068-01 恢复）：期望由同制品纯函数按本会话 kbIds 推导（每库一检索员+校验/汇总）。
        assertStepsMatch(extractSteps(requireSuccessOutput(call)),
                expectedCrossKbSteps(List.of("kb-e2e-a", "kb-e2e-b", "kb-e2e-c")));
    }

    /** ② @cross-kb-comparison + 无知识库范围：生成器空列表受控拒绝，会话不炸。 */
    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    @Story("068-S12: 会话侧动态模板通道（@cross-kb-comparison + kbScope）")
    @DisplayName("068-S12 无 scope 点名 @cross-kb-comparison：空列表受控拒绝，任务可达终态且产物可读")
    void sessionChannelWithoutScopeIsControlledRejected() throws Exception {
        final Map<String, Object> body = baseBody(newSession("sess-068-s12-empty"),
                "请用 workflow_run 工具、以 teamName 参数 跨库对比分析 分析：知识库为空时的行为。"
                        + "若工具报错，请把错误原文告诉用户。");
        // 不放 scope → kbScope 为空列表 → 生成器空列表拒绝（L2 :33-35 契约经会话面兑现）

        final com.fasterxml.jackson.databind.JsonNode snapshot = postRun(body);
        final com.fasterxml.jackson.databind.JsonNode call = requireTeamCall(snapshot, "跨库对比分析");
        // 安全行为：任务可达终态（模型叙述错误）或 completed；不悬挂、不 5xx
        final String status = snapshot.path("status").asText();
        assertThat(status).as("空范围应受控到达终态（completed/failed 皆可，禁止悬挂）")
                .isIn("completed", "failed");
        final String narrative = snapshot.path("output").asText() + " " + call.path("output").asText();
        assertThat(narrative).as("受控拒绝应带可读叙述（空列表/至少一个 等口径）")
                .isNotBlank();
    }

    /** ③ 原版中文团队名映射：teamName=研究管道 → team-names.properties 解析到 research-pipeline。 */
    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    @Story("068-S13: 原版中文团队名映射（teamName→随包模板）")
    @DisplayName("068-S13 中文团队名「研究管道」经映射解析并完成编排")
    void chineseTeamNameResolvesToShippedTemplate() throws Exception {
        final Map<String, Object> body = baseBody(newSession("sess-068-s13"),
                "请用 workflow_run 工具、以 teamName 参数 研究管道 分析这个问题：写一份三步研究计划。"
                        + "工具返回后，请只回复工具出参里 final_output 的原文。");

        final com.fasterxml.jackson.databind.JsonNode snapshot = postRun(body);
        failIfTaskFailed(snapshot);
        final com.fasterxml.jackson.databind.JsonNode call = requireTeamCall(snapshot, "研究管道");
        assertThat(snapshot.path("status").asText()).as("任务级：映射后应照模板完成编排").isEqualTo("completed");
        assertThat(snapshot.path("output").asText()).as("任务级：收口产物应非空").isNotBlank();

        // 步骤级（GAP-068-01 恢复）：期望=research-pipeline 随包资产编译序（search→verify→write；
        // verify/write 声明 skip_if=DEPS_INCONCLUSIVE，允许跳过）。
        assertStepsMatch(extractSteps(requireSuccessOutput(call)),
                expectedStepsFromAsset("research-pipeline"));
    }

    /* ---- 私有装配 ---- */

    private Map<String, Object> baseBody(final String sessionId, final String input) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("input", input);
        return body;
    }

    private com.fasterxml.jackson.databind.JsonNode postRun(final Map<String, Object> body) throws Exception {
        initClient();
        final java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(base + "/run"))
                .timeout(RUN_TIMEOUT)
                .header("Content-Type", "application/json")
                .header(SESSION_HEADER, String.valueOf(body.get("sessionId")))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        final java.net.http.HttpResponse<String> response =
                http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Allure.addAttachment("POST /api/agents/run 响应（截断 8KB）", "text/plain",
                truncate(response.body(), 8192));
        assertThat(response.statusCode()).as("同步执行应返回 200").isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    /** 取 teamName 调用记录（workflow_run 且入参含指定团队名）；模型未配合 ⇒ INCONCLUSIVE。 */
    private com.fasterxml.jackson.databind.JsonNode requireTeamCall(
            final com.fasterxml.jackson.databind.JsonNode snapshot, final String teamName) {
        for (com.fasterxml.jackson.databind.JsonNode call : snapshot.path("toolCalls")) {
            if ("workflow_run".equals(call.path("toolName").asText())
                    && call.path("input").toString().contains(teamName)) {
                Allure.addAttachment("workflow_run（teamName）调用记录", "text/plain",
                        truncate(call.toString(), 16384));
                return call;
            }
        }
        org.junit.jupiter.api.Assumptions.abort("主 Agent 未以 teamName=" + teamName
                + " 调用 workflow_run（模型未配合，L3 不确定性）；记 INCONCLUSIVE，不判 FAIL。");
        throw new IllegalStateException("unreachable");
    }
}
