/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl;

import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioExportCorpusFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.WorkflowAssemblyProbe;
import com.openjiuwen.studio.dsl.contract.PythonCodeExecutor;
import com.openjiuwen.studio.dsl.nodes.FlowCodeNode;
import com.openjiuwen.studio.dsl.python.InprocessPythonCodeExecutor;
import com.openjiuwen.studio.dsl.python.PythonCodeRunners;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-031 §4.2「代码节点沙箱装配」契约（2026-09-21 设计侧修订后的该行原文）：
 *
 * <blockquote>SDK 装配 {@code jiuwen.code} 节点时必须按本文档 §6 解析 Python 执行器：先使用已注册的
 * 自定义沙箱执行器，未注册时使用 §3 默认 Python 执行器。装配产物必须可追溯代码节点最终使用的执行器来源
 * （默认 / 自定义）；执行器机制的通用化程度由 L2 细化。</blockquote>
 *
 * <p>本类只断言**当前制品可观测**的部分：
 *
 * <ul>
 *   <li>装配目标：真实导出 {@code system} 语料中的 {@code jiuwen.code} 组件装配为 {@code FlowCodeNode}
 *       （绑定"SDK 装配"这一半）；</li>
 *   <li>执行器解析：已注册的自定义沙箱执行器优先、未注册时不得静默（严格模式 fail-closed）、关闭严格
 *       模式后回退默认本地执行器、{@code exec_env} 非 {@code sandbox} 走本地默认执行器。</li>
 * </ul>
 *
 * <p><b>仍缺的承接面（已登记为覆盖缺口，不在本类中伪造）</b>：① L2 未定义"沙箱执行器注册器"与
 * "执行器来源元数据"的契约（`agent-solution` 侧只有静态注册入口，装配产物未暴露来源字段）；② 严格模式下
 * {@code exec_env=sandbox} 且未注册时不回退默认执行器，与 §6「未命中时必须使用 §3 默认 Python 执行器」
 * 的措辞存在口径差异（是否以部署开关为准待设计确认）；③ 本类的执行记录尚未按 runner 格式登记到覆盖台账。
 */
@Tag("component")
@Tag("contract")
@Tag("feat-031")
@Tag("code-sandbox")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioCodeSandboxExecutorContractTest extends StudioDslContractTestBase {

    private static final String ARTIFACT = "system";
    private static final String CODE_IR_TYPE = "jiuwen.code";
    private static final String FLOW_CODE_NODE = "com.openjiuwen.studio.dsl.nodes.FlowCodeNode";
    private static final String STRICT_PROPERTY = "studio.dsl.sandbox.strict";

    @AfterEach
    void clearExecutorRegistrationAndStrictOverride() {
        PythonCodeRunners.setSandboxExecutor(null);
        System.clearProperty(STRICT_PROPERTY);
    }

    @Test
    @Story("RT-031-04-09/sandbox-executor: 代码节点沙箱执行器装配与解析")
    @DisplayName("真实导出 jiuwen.code 组件装配为 FlowCodeNode（代码节点沙箱装配目标）")
    void codeNodeAssemblesToFlowCodeNode() {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();
        String codeNodeId = firstCodeNodeId(manifest);

        WorkflowAssemblyProbe.Snapshot snapshot = WorkflowAssemblyProbe.inspect(
                StudioExportCorpusFixture.assembleArtifact(manifest, ARTIFACT).workflow());

        assertThat(snapshot.javaTypes().get(codeNodeId))
                .as("jiuwen.code 组件的装配目标类型")
                .isEqualTo(FLOW_CODE_NODE);
        assertThat(snapshot.studioTypes().get(codeNodeId))
                .as("装配产物的 Studio 节点类型")
                .isEqualTo(CODE_IR_TYPE);
        assertThat(snapshot.nodes().get(codeNodeId))
                .as("装配产物中的代码节点实例")
                .isInstanceOf(FlowCodeNode.class);
    }

    @Test
    @Stories({
        @Story("RT-031-04-09/sandbox-executor: 代码节点沙箱执行器装配与解析"),
        @Story("RT-031-04-09: 代码节点沙箱装配（§6 沙箱执行器注册器）")
    })
    @DisplayName("已注册的自定义沙箱执行器优先于任何默认实现")
    void registeredSandboxExecutorIsPreferred() {
        PythonCodeExecutor marker = request -> {
            throw new AssertionError("marker executor must not be executed by this contract test");
        };

        PythonCodeRunners.setSandboxExecutor(marker);

        assertThat(PythonCodeRunners.resolve("sandbox", "subprocess", null))
                .as("exec_env=sandbox 时必须选中已注册的自定义沙箱执行器（同一实例）")
                .isSameAs(marker);
    }

    @Test
    @Story("RT-031-04-09/sandbox-executor: 代码节点沙箱执行器装配与解析")
    @DisplayName("未注册执行器且处于严格模式时，exec_env=sandbox 必须可诊断失败，不得静默回退")
    void unregisteredSandboxFailsClosedInStrictMode() {
        System.setProperty(STRICT_PROPERTY, "true");
        assertThat(PythonCodeRunners.isSandboxStrict())
                .as("严格模式前置条件（环境变量 STUDIO_DSL_SANDBOX_STRICT 未覆盖）")
                .isTrue();

        PythonCodeRunners.setSandboxExecutor(null);

        assertThatThrownBy(() -> PythonCodeRunners.resolve("sandbox", "subprocess", null))
                .as("未注册自定义沙箱执行器时不得静默降级")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no sandbox executor configured");
    }

    @Test
    @Story("RT-031-04-09/sandbox-executor: 代码节点沙箱执行器装配与解析")
    @DisplayName("关闭严格模式后，未注册执行器的 sandbox 请求回退到默认本地执行器")
    void unregisteredSandboxFallsBackToLocalExecutorWhenStrictDisabled() {
        System.setProperty(STRICT_PROPERTY, "false");
        assertThat(PythonCodeRunners.isSandboxStrict())
                .as("非严格模式前置条件（环境变量 STUDIO_DSL_SANDBOX_STRICT 未覆盖）")
                .isFalse();

        PythonCodeRunners.setSandboxExecutor(null);

        PythonCodeExecutor resolved = PythonCodeRunners.resolve("sandbox", "inprocess", null);
        assertThat(resolved)
                .as("非严格模式下未注册自定义沙箱执行器时，必须回退 §3 默认 Python 执行器")
                .isInstanceOf(InprocessPythonCodeExecutor.class);
    }

    @Test
    @Story("RT-031-04-09/sandbox-executor: 代码节点沙箱执行器装配与解析")
    @DisplayName("exec_env 非 sandbox（含非法值）走本地默认执行器，不触发沙箱解析")
    void nonSandboxExecEnvResolvesToLocalExecutor() {
        PythonCodeExecutor fallback = request -> {
            throw new AssertionError("fallback subprocess executor must not be executed by this contract test");
        };

        assertThat(PythonCodeRunners.resolve("local", "inprocess", fallback))
                .as("exec_env=local + local_exec_mode=inprocess")
                .isInstanceOf(InprocessPythonCodeExecutor.class);
        assertThat(PythonCodeRunners.resolve("local", "subprocess", fallback))
                .as("exec_env=local + local_exec_mode=subprocess 时使用上下文提供的默认执行器")
                .isSameAs(fallback);
        assertThat(PythonCodeRunners.normalizeExecEnv("weird-mode"))
                .as("未知 exec_env 的归一结果")
                .isEqualTo("local");
    }

    private static String firstCodeNodeId(StudioExportCorpusFixture.Manifest manifest) {
        Map<String, Object> ir = StudioExportCorpusFixture.artifactIr(manifest, ARTIFACT);
        for (Object component : (List<?>) ir.get("components")) {
            Map<?, ?> node = (Map<?, ?>) component;
            if (CODE_IR_TYPE.equals(String.valueOf(node.get("type")))) {
                return String.valueOf(node.get("id"));
            }
        }
        throw new AssertionError("artifact " + ARTIFACT + " must contain a " + CODE_IR_TYPE + " component");
    }
}
