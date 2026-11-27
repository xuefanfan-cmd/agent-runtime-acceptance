/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S12：护栏 {@code actrule.yaml} 六件显式放行（静态）。
 *
 * <p>Oracle 来源：FEAT-067 §2「六件在护栏规则中**显式放行**；新增编排工具必须同步护栏登记」；
 * L2-067 §6（自陈"登记缺口曾是评审发现项"⇒ 有真实缺陷史，设计建议作 **gating**）。
 *
 * <p>判据：① {@code tools:} 节下六件**全部为 {@code allow}**；② 该文件 {@code defaults: "*": ask}
 * 为 **fail-closed** ⇒ 未登记的工具默认需确认，故"显式放行"是必需而非冗余；③ {@code enabled: true}
 * （护栏资产本身处于生效状态）。
 *
 * <p>这是设计**本来只要求静态验证**的条目（形态 {@code static}），静态断言即充分、不属降级。
 *
 * <p><b>取证方式</b>：{@code governance/actrule.yaml} 是随制品发布的 classpath 资产
 * （实测在 {@code deepanalyze-engine} jar 内），因此**直接读 classpath**，
 * 不需要本机源码路径、也不硬编码任何绝对路径。
 *
 * <p>不应断言：其余工具的级别（属 FEAT-040 护栏谱系，不在本变更包范围内）；
 * 护栏的**运行期执行行为**（Feature §2 OUT「core 仓改动」，且 PermissionEngine 属 core）。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowGuardrailRegistrationTest {

    private static final String ACTRULE = "governance/actrule.yaml";

    /** FEAT-067 §2 的编排工具六件（与 067-S10 的工具集合同源）。 */
    private static final List<String> SIX_TOOLS = List.of(
            "workflow_run", "delegate_task", "task_output",
            "send_message", "subagent_transcript", "workflow_status");

    @Test
    @Story("067-S12: 护栏 actrule.yaml 六件显式放行")
    @DisplayName("067-S12 六件在 tools 节全部为 allow，且 defaults.* 为 ask（fail-closed）、enabled 为 true")
    void sixOrchestrationToolsAreExplicitlyAllowed() throws Exception {
        final String text = readClasspath(ACTRULE);
        assertThat(text).as("护栏资产不应为空").isNotBlank();

        @SuppressWarnings("unchecked")
        final Map<String, Object> root = new ObjectMapper(new YAMLFactory())
                .readValue(text, Map.class);

        // ③ 护栏资产处于生效状态
        assertThat(root.get("enabled")).as("actrule.yaml 应声明 enabled=true（护栏资产生效）")
                .isEqualTo(Boolean.TRUE);

        // ② fail-closed：未登记的工具默认需确认
        assertThat(root.get("defaults")).as("应有 defaults 节").isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        final Map<String, Object> defaults = (Map<String, Object>) root.get("defaults");
        assertThat(defaults).as("defaults 应对通配 * 声明 ask（fail-closed），"
                        + "否则\"显式放行\"就只是冗余而非必需")
                .containsEntry("*", "ask");

        // ① 六件全部显式 allow
        assertThat(root.get("tools")).as("应有 tools 节").isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        final Map<String, Object> tools = (Map<String, Object>) root.get("tools");
        for (String tool : SIX_TOOLS) {
            assertThat(tools).as("tools 节应显式登记 %s（缺失即护栏登记缺口，L2-067 §6 有真实缺陷史）",
                    tool).containsKey(tool);
            assertThat(tools.get(tool)).as("工具 %s 的级别应为 allow（显式放行）；实收=%s",
                    tool, tools.get(tool)).isEqualTo("allow");
        }
        // 反向自检：解析方式有效（能读到已知的非六件条目）
        assertThat(tools).as("解析自检：应能读到护栏里已知的其它条目").containsKey("read_file");
    }

    private static String readClasspath(final String path) throws Exception {
        try (InputStream in = DaWorkflowGuardrailRegistrationTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(in).as("classpath 资产应存在：%s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
