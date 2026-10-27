/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.deepanalyze_template;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-012 静态合同（<b>post-085 形态重定向版</b>）：动态产物与手写图纸<b>同权</b>——进同一请求/调度通道，无专用执行分支。
 *
 * <p>Oracle 演进：原断言（Feature §5.1.1 / L2-068 §3.3，钉子 6cdf72a6b 形态）为"会话工具不引用动态生成器"；
 * 2026-10-19 用户裁决改测新形态后，被测面由 Feat-Func-085 接管——{@code workflow_run} 新增原版
 * {@code teamName}/@cross-kb-comparison 通道（085 L2 :182），动态产物与装载图纸走<b>同一个</b>
 * {@code Request} 通道（WorkflowRunTool.java :169 蓝图路径 / :183 动态路径 / :185 团队名→模板路径同型）。
 * 本类断言新形态下的"同权"实现事实：
 * <ol>
 *   <li>动态分支产出与蓝图路径<b>同一 Request 通道</b>（无专用调度分支）；</li>
 *   <li>名字→全文解析接缝仍不装载（{@code loadTemplate} 委托 {@code BlueprintTemplates.load} 返回
 *       YAML 全文，装载归 {@code BlueprintLoader} 单点 :169/:185）；</li>
 *   <li>文本装载路径与团队名→模板路径共用 {@code BlueprintLoader.load}。</li>
 * </ol>
 * 缺源码根时记 SKIPPED（INCONCLUSIVE），不判产品 FAIL。
 */
@Tag("component")
@Tag("feat-068")
@Feature("FEAT-068: 团队模板库")
class TemplateNoSpecialBranchStaticTest {

    @Test
    @Story("REQ-012: 动态产物与手写图纸同权、无专用分支")
    @DisplayName("REQ-012 静态（post-085）：动态产物与装载图纸同走 Request 通道；解析接缝只做名字→全文；装载归单点")
    void dynamicBlueprintSharesTheSameRequestChannelAsLoadedOnes() throws IOException {
        final Path root = DeepanalyzeTemplateTestSupport.resolveDeepanalyzeSourceRoot();
        final String tool = Files.readString(
                root.resolve("com/openjiuwen/da/workflow/WorkflowRunTool.java"), StandardCharsets.UTF_8);

        assertThat(tool).as("扫描自检：应能扫到会话工具类名").contains("class WorkflowRunTool");

        // ① 同一 Request 通道：蓝图路径与动态路径同型（new Request(spec, question)）
        assertThat(tool).as("蓝图路径应经 BlueprintLoader.load 进 Request（:169 同型）")
                .contains("new Request(BlueprintLoader.load(blueprint)");
        assertThat(tool).as("动态跨库对比产物应直接进同一 Request 通道（:183 同型，085 接管后的同权实现）")
                .contains("new Request(DynamicTeamTemplates.crossKbComparison(kbIds)");

        // ② 解析接缝只做名字→全文（不装载）
        assertThat(tool).as("解析接缝应委托 BlueprintTemplates.load（085 下沉后形态）")
                .contains("BlueprintTemplates.load");
        assertThat(tool).as("loadTemplate 仍返回 YAML 全文（Optional<String>）")
                .contains("static Optional<String> loadTemplate");

        // ③ 文本装载归 BlueprintLoader 单点：工具内两次装载都经它（蓝图直给 :169 / 团队名→模板 :185）
        assertThat(tool).as("模板全文与手写图纸共用 BlueprintLoader.load（同权装载，无模板专用装载分支）")
                .contains("BlueprintLoader.load(loadTemplate(id)");
    }
}
