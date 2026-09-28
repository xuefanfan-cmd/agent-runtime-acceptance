/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl;

import com.openjiuwen.core.session.internal.WorkflowSession;
import com.openjiuwen.core.session.state.InMemoryState;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowOutput;
import com.openjiuwen.studio.dsl.ir.IrAssembleContext;
import com.openjiuwen.studio.dsl.ir.IrModelMapping;
import com.openjiuwen.studio.dsl.ir.StudioIrSdk;
import com.openjiuwen.studio.dsl.store.ConversationValsStores;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingOpenAiEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslBehaviorTraceFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioExportCorpusFixture;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组件级"装配后按值选边"最小执行断言（FEAT-031 §4.2 行为对齐的快速反馈面）。
 *
 * <p>不走 A2A、不起 SUT 栈：直接用 SDK 装配 E8 语料并 {@code Workflow.invoke}，模型端点用受控夹具
 * （{@link RecordingOpenAiEndpointFixture}），按**受控端点请求序列**抽出下游标记序列，与行为 golden 做
 * 相等断言。判据与端到端层 {@code StudioExportWorkflowE2EIT#e8*} 一致，只是把层与入口换成组件层直调。</p>
 *
 * <p>观测面演进记录：原先尝试读判断节点产出（{@code WorkflowCommitState#getOutputs(nodeId)}），实测返回
 * {@code {userFields={}}}（未声明输出字段不落库），故改用本观测面（2026-09-20）。</p>
 *
 * @since 2026-09-20
 */
@Tag("component")
@Tag("studio-dsl")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioBranchEdgeSelectionTest {
    private static final String ARTIFACT = "feat031-e8-entry-field-branch";
    private static final List<String> MARKERS = List.of("PATH_MARKER=IF", "PATH_MARKER=DEFAULT");

    /**
     * 本类直接调 SDK 装配 / 执行 IR；PR707 起会话 KV 取用即 fail-fast，故注入产品自带的进程内实现。
     * 本类不验证 Redis 交互，Redis 相关的正/负例在 e2e 层（StudioDslRuntimeE2EIT / StudioDslRedisUnconfiguredIT）。
     */
    @BeforeAll
    static void installInMemoryConversationVals() {
        ConversationValsStores.setDefault(ConversationValsStores.memoryStore());
    }

    @AfterAll
    static void clearConversationValsOverride() {
        ConversationValsStores.setDefault(null);
    }

    @Test
    @Story("RT-031-04-10/a: 装配后按入口字段取值选边（组件级执行）")
    @DisplayName("loginFlag=1 → 只允许命中 default 边下游（当前实现命中 if 即缺陷 #311）")
    void selectDefaultEdgeWhenEntryFieldIsOne() throws Exception {
        assertMarkerTrace("1",
                "/testdata/studio_dsl/behavior-golden/feat031-e8-entry-field-branch.default.json");
    }

    @Test
    @Story("RT-031-04-10/a: 装配后按入口字段取值选边（组件级执行）")
    @DisplayName("loginFlag=0 → 只允许命中 if 边下游（镜像方向）")
    void selectIfEdgeWhenEntryFieldIsZero() throws Exception {
        assertMarkerTrace("0",
                "/testdata/studio_dsl/behavior-golden/feat031-e8-entry-field-branch.if.json");
    }

    private void assertMarkerTrace(String loginFlag, String goldenResource) throws Exception {
        Map<String, Object> ir = StudioExportCorpusFixture.artifactIr(StudioExportCorpusFixture.manifest(), ARTIFACT);
        StudioDslBehaviorTraceFixture.BehaviorGolden golden =
                StudioDslBehaviorTraceFixture.loadGolden(goldenResource);

        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            String canary = "rt-031-04-10a-canary-" + loginFlag;
            model.responseCanary(canary);
            Map<String, IrModelMapping.Entry> entries = new LinkedHashMap<>();
            Map<String, Object> modelMap = new LinkedHashMap<>();
            for (String name : IrModelMapping.collectModelNames(ir)) {
                entries.put(name, new IrModelMapping.Entry(name, model.baseUrl(), "rt-031-04-10a-key", "OpenAI"));
                modelMap.put(name, Map.of(
                        "clientProvider", "OpenAI",
                        "endpoint", model.baseUrl(),
                        "apiKey", "rt-031-04-10a-key"));
            }
            Workflow workflow = StudioIrSdk.loadWorkflow(
                    ir,
                    IrAssembleContext.builder()
                            .workflowId("sit-rt-031-04-10a")
                            .modelMapping(new IrModelMapping(entries))
                            .build());
            // 与宿主直连口同款：把模型映射放进会话 global 的 llm_extra_configs.model_map，
            // 否则 LLMComponent 初始化时取不到 endpoint/key（实测 causeCode=NODE_CONFIG_INVALID / Failed to initialize LLM）。
            Map<String, Object> global = new LinkedHashMap<>();
            global.put(IrModelMapping.SESSION_LLM_EXTRA_CONFIGS_KEY,
                    Map.of("model_map", modelMap));
            WorkflowSession session = new WorkflowSession(
                    "sit-rt-031-04-10a", null, null, InMemoryState.create(null, global, null, null, null), null);

            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("query", "hello");
            inputs.put("loginFlag", loginFlag);
            WorkflowOutput out = workflow.invoke(inputs, session, null);

            List<String> trace = markerTrace(model.requestsSince(0));
            Allure.addAttachment("组件级行为轨迹（受控端点标记序列）", String.join(" -> ", trace));
            Allure.addAttachment("工作流终态", String.valueOf(out == null ? null : out.getState()));

            assertThat(trace)
                    .as("行为对齐（观测面=受控端点请求序列）：golden=%s，期望来源=%s",
                            golden.scenario(), golden.expectationSource())
                    .isEqualTo(golden.expectedMarkers());
        }
    }

    private static List<String> markerTrace(List<RecordingOpenAiEndpointFixture.RequestRecord> calls) {
        List<String> trace = new ArrayList<>();
        for (RecordingOpenAiEndpointFixture.RequestRecord call : calls) {
            String body = String.valueOf(call.body());
            String hit = "UNKNOWN";
            for (String marker : MARKERS) {
                if (body.contains(marker)) {
                    hit = marker;
                    break;
                }
            }
            trace.add(hit);
        }
        return List.copyOf(trace);
    }
}
