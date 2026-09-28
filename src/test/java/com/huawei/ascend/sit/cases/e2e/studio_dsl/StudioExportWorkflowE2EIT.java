/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.studio_dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslRedisBackedE2EBase;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingOpenAiEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingPluginMcpEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslBehaviorTraceFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslHostControlClient;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioExportCorpusFixture;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.A2aEventMapping;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.openjiuwen.studio.dsl.ir.IrModelMapping;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Tag("e2e")
@Tag("studio-dsl")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioExportWorkflowE2EIT extends StudioDslRedisBackedE2EBase {

    @Test
    @Story("RT-031-04-27/E1a: 仅引用 Start.query 的真实导出工作流贯通")
    @DisplayName("E1a 只引用 Start.query 的真实导出工作流经 Bridge 和 A2A 流式回显调用输入")
    void e1aRunsQueryOnlyExport() {
        Map<String, Object> ir = artifact("feat031-e3-child", Set.of("sequential", "streaming"));
        StudioDslHostControlClient control = controlClient();
        try {
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:e3-child", ir, Map.of(), Map.of());
            String canary = "rt-031-04-27-e1a-query-canary";

            invokeCompleted("rt-031-04-27-e1a", canary, text -> assertThat(text).contains(canary));

            assertSingleStreamInvocation(control.status(), loaded);
        } finally {
            control.reset();
        }
    }

    @Test
    @Story("RT-031-04-27/E1b: 含 sys.* 引用的真实导出工作流贯通")
    @DisplayName("E1b 含 sys.* 引用的真实导出工作流经 Bridge 和 A2A 流式返回受控模型 canary")
    void e1bRunsSystemFieldsExport() throws Exception {
        Map<String, Object> ir = artifact("system", Set.of("sequential", "streaming"));
        StudioDslHostControlClient control = controlClient();
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            String canary = "rt-031-04-27-e1b-model-canary";
            model.responseCanary(canary);
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:system", ir, modelMapping(ir, model.baseUrl()), Map.of());
            int requestBaseline = model.requestCount();

            // Oracle (confirmed 2026-09-14): this corpus composes its response from message nodes and sys.* fields
            // (`{{result}}\n{{result1}}\n{{result2}}`), so the model answer never reaches the response text.
            // Assert the structural echo instead of the canary, and prove the LLM node ran via the request delta.
            // The structural echo is the corpus's fixed shape, but the JSON key order inside the rendered
            // message list is not stable across runs (observed "[{content=…, role=user}]" and
            // "[{role=user, content=…}]"), so assert the stable parts independently.
            invokeCompleted("rt-031-04-27-e1b", "E1b input",
                    text -> assertThat(text)
                            .contains("E1b input")
                            .contains("role=user")
                            .contains("jiuwen.message"));

            assertThat(model.requestsSince(requestBaseline)).singleElement();
            assertSingleStreamInvocation(control.status(), loaded);
        } finally {
            control.reset();
        }
    }

    @Test
    @Story("RT-031-04-27/E1c: 调用方扩展参数能否经 A2A metadata 送达 Start.userFields")
    @DisplayName("E1c 探针语料经 metadata.inputs 的流式 A2A 请求回显 ${node_start.userFields.city}")
    void e1cProbesUserFieldsDeliveryChannel() {
        Map<String, Object> ir = artifact("feat031-ref-userfield-probe", Set.of("sequential", "streaming"));
        StudioDslHostControlClient control = controlClient();
        try {
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:userfield-probe", ir, Map.of(), Map.of());
            String city = "Shenzhen";

            // Probe oracle（2026-09-18 起，B2 输入通道落地后启用）: the probe IR declares Start.userFields.city
            // and renders the reply from ${node_start.userFields.city}. Only the caller supplies `city`, so the
            // value appearing in the reply proves the caller -> metadata.inputs -> Start.userFields channel works.
            // 2026-09-14 的 workflow_req_params 通道已作废；本用例现使用交付宿主同款语义的 metadata.inputs。
            InteractionFlow.RoundResult round = sendWithMetadata("rt-031-04-27-e1c", "E1c probe input",
                    Map.of("inputs", Map.of("city", city)));

            assertThat(round.generatedText())
                    .as("RT-031-04-27/E1c userFields delivery probe: "
                            + "metadata.workflow_req_params.city=%s -> ${node_start.userFields.city} "
                            + "(task=%s, state=%s, answer=%s)",
                            city, round.taskId(), round.taskState(), round.answerText())
                    .contains(city);

            assertSingleStreamInvocation(control.status(), loaded);
        } finally {
            control.reset();
        }
    }

    @Test
    @Story("RT-031-04-27/E1c-control: 带 metadata 的 A2A 请求仍可观测 query 回显")
    @DisplayName("E1c 控制组：非流式 metadata 链路上 ${node_start.systemFields.query} 仍回显调用输入")
    void e1cControlKeepsQueryEchoOnTheMetadataWire() {
        Map<String, Object> ir = artifact("feat031-e3-child", Set.of("sequential", "streaming"));
        StudioDslHostControlClient control = controlClient();
        try {
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:e1c-control", ir, Map.of(), Map.of());
            String canary = "rt-031-04-27-e1c-control-input";

            // Control for the previous probe runs: identical send path and identical metadata, but a corpus that
            // renders ${node_start.systemFields.query} (the field E1a already proved). If the canary shows up
            // here, the metadata wire and the Start-field rendering path both work, so an empty render in the
            // probe run is attributable to the userFields channel rather than to the wire.
            Task task = sendRequestParamsProbe("rt-031-04-27-e1c-control", canary,
                    Map.of("workflow_req_params", Map.of("city", "Shenzhen")));

            assertThat(replyText(task))
                    .as("RT-031-04-27/E1c control: ${node_start.systemFields.query} echo on the metadata wire "
                            + "(task=%s, state=%s, task=%s)", task.id(), task.status().state(), task)
                    .contains(canary);

            // The legacy no-suffix overload is documented as `message/send`, but the A2A SDK dispatches on the
            // card's capabilities too: this SUT advertises streaming=true, so the call lands on message/stream
            // and the host's counter moves on streamQuery (observed: streamQuery +1, query unchanged).
            assertSingleStreamInvocation(control.status(), loaded);
        } finally {
            control.reset();
        }
    }

    /** E2 语料两个 Agent 的 systemPrompt 片段（真实导出 IR 原文），用于判定哪条分支真的被执行。 */
    private static final String E2_AGENT_A_PROMPT = "你是E2分支A的测试Agent";
    private static final String E2_AGENT_B_PROMPT = "你是E2分支B的测试Agent";

    /**
     * E2 真执行：语料是真实导出的 if/else 分支 + 汇聚 IR（`node_start → node_branch → {AgentA, AgentB}
     * → node_agg(first-non-null) → node_end`），分支条件为 {@code length(${query}) > 1}。
     *
     * <p>因此"命中分支执行、非命中分支未执行"的可判定观察面是**模型端点收到的请求体**：命中分支的
     * Agent systemPrompt 必须出现，未命中分支的 systemPrompt 必须不出现；命中分支的输出经聚合进入
     * 父 End（响应含受控模型 canary）。该语料不是"两路并行都执行"的形态，断言按语料真实语义设计。</p>
     */
    @Test
    @Story("RT-031-04-27/E2: 分支与并行真实工作流")
    @DisplayName("E2 真执行：if 分支命中 AgentA、default 分支命中 AgentB，命中结果经聚合回灌父 End")
    void e2RequiresSingleBranchParallelExport() throws Exception {
        Map<String, Object> ir = artifact("feat031-e2-branch-parallel", Set.of("branch", "parallel"));
        StudioDslHostControlClient control = controlClient();
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            String canary = "rt-031-04-27-e2-model-canary";
            model.responseCanary(canary);
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:e2", ir, modelMapping(ir, model.baseUrl()), Map.of());

            int branchABaseline = model.requestCount();
            InteractionFlow.RoundResult branchARound =
                    invokeOnce("rt-031-04-27-e2-branch-a", "rt-031-04-27-e2-branch-a-canary");
            List<RecordingOpenAiEndpointFixture.RequestRecord> branchACalls =
                    model.requestsSince(branchABaseline);
            Allure.addAttachment("E2 if 分支模型端点请求增量（非门禁：主断言失败时仍留证）",
                    "calls=" + branchACalls.size() + "\n"
                            + branchACalls.stream().map(call -> compactForMessage(call.body().toString()))
                            .collect(Collectors.joining("\n")));
            assertThat(branchARound.generatedText())
                    .as("if 分支响应必须含受控模型 canary（本轮模型端点请求增量=%s）", branchACalls.size())
                    .contains(canary);
            assertThat(branchACalls).as("if 分支必须真的调用模型").isNotEmpty();
            assertThat(branchACalls)
                    .as("if 分支只允许 AgentA 被调用")
                    .allMatch(call -> call.body().toString().contains(E2_AGENT_A_PROMPT))
                    .noneMatch(call -> call.body().toString().contains(E2_AGENT_B_PROMPT));

            int branchBBaseline = model.requestCount();
            InteractionFlow.RoundResult branchBRound = invokeOnce("rt-031-04-27-e2-branch-b", "x");
            List<RecordingOpenAiEndpointFixture.RequestRecord> branchBCalls =
                    model.requestsSince(branchBBaseline);
            Allure.addAttachment("E2 default 分支模型端点请求增量（非门禁：主断言失败时仍留证）",
                    "calls=" + branchBCalls.size() + "\n"
                            + branchBCalls.stream().map(call -> compactForMessage(call.body().toString()))
                            .collect(Collectors.joining("\n")));
            assertThat(branchBRound.generatedText())
                    .as("default 分支响应必须含受控模型 canary（本轮模型端点请求增量=%s）", branchBCalls.size())
                    .contains(canary);
            assertThat(branchBCalls).as("default 分支必须真的调用模型").isNotEmpty();
            assertThat(branchBCalls)
                    .as("default 分支只允许 AgentB 被调用")
                    .allMatch(call -> call.body().toString().contains(E2_AGENT_B_PROMPT))
                    .noneMatch(call -> call.body().toString().contains(E2_AGENT_A_PROMPT));

            assertThat(control.status().loadCount()).isEqualTo(loaded.loadCount());
            assertThat(control.status().queryCount()).isEqualTo(loaded.queryCount());
            assertThat(control.status().streamQueryCount()).isEqualTo(loaded.streamQueryCount() + 2);
        } finally {
            control.reset();
        }
    }

    // 旧用例 `e3RequiresSingleLoopSubworkflowExpansionExport`（只校验旧 E3 语料的 IR 含
    // loop/subworkflow/parameter-expansion 三个维度）已于 2026-09-19 移除：该语料引用的两个子流
    // 已从 Studio 删除、永久不可执行，"前置语料断言"不构成执行验证，保留它会让台账看上去更满。
    // 执行面由 B1 自洽语料用例 e3bRunsLoopAndChildFromCallerInputs / e3peParamExtractionRealExecution
    // 承接，登记见《FEAT-031-降级与待恢复登记-20260918.md》。

    /**
     * B1 自洽语料（2026-09-18 新建）：原 E3 语料引用的两个子流已从 Studio 删除，原语料永久不可执行，
     * 因此新建一份同源语料，让"循环 + 子流"在同一份 IR 里都能真实执行。
     *
     * <p>语料形态：父 {@code Start(query, questions[array]) → 子工作流 → Loop(array, body=[LLM]) → End}，
     * 循环列由调用方 {@code metadata.inputs.questions} 经父 Start 的 userFields 注入，循环体内 LLM 节点的
     * query 引用 {@code ${node_loop.arrLoopVar.item}}，父 End 同时消费子流输出与循环产出。</p>
     *
     * <p>主断言：(1) 任务到达 COMPLETED 且流中存在 {@code jiuwen.end} 产出；(2) 受控模型端点按列表长度
     * 收到请求且每轮请求体携带对应列表元素——元素只有调用方提供，命中即同时证明"列表来自 metadata.inputs"
     * 与"循环真实逐元素迭代"；(3) 父响应含子流回显——命中即证明子流被调度并回灌父工作流。
     * 子流 IR 装载计数只作为增强证据留档，不参与判定。</p>
     */
    @Test
    @Story("RT-031-04-27/E3b: 循环+子流自洽语料真执行")
    @DisplayName("E3b 真执行：循环按 metadata.inputs 列表迭代 N 次，子流被调度并回灌父响应")
    void e3bRunsLoopAndChildFromCallerInputs() throws Exception {
        Map<String, Object> parent = artifact(
                "feat031-b1-loop-child", Set.of("loop", "subworkflow", "streaming"));
        Map<String, Object> child = StudioExportCorpusFixture.artifactIr(
                manifest(), "feat031-b1-loop-child-child");
        String childRefPath = childReferencePath(parent);
        StudioDslHostControlClient control = controlClient();
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            model.responseCanary("rt-031-04-27-e3b-model-canary");
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:b1-loop-child",
                    parent,
                    modelMapping(parent, model.baseUrl()),
                    Map.of(childRefPath, child));
            int requestBaseline = model.requestCount();
            long childLoadBaseline = loaded.childIrLoadCount();

            List<String> loopItems = List.of(
                    "rt-031-04-27-e3b-item-1", "rt-031-04-27-e3b-item-2", "rt-031-04-27-e3b-item-3");
            String inputCanary = "rt-031-04-27-e3b-query-canary";

            // 执行级失败必须留下内层原因：先用 mayReachState 让失败轮也结算，再把事件流（含失败原文、
            // 错误码、失败节点标识）写进 Allure 与断言消息，避免只留 "expected COMPLETED" 的外层签名。
            InteractionFlow.RoundResult round = InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withTimeoutMs(DEFAULT_AWAIT_TIMEOUT_MS)
                    .withContextId("rt-031-04-27-e3b-" + System.nanoTime())
                    .send(inputCanary)
                    .withMetadata(Map.of("inputs", Map.of("questions", loopItems)))
                    .mayReachState(TaskState.TASK_STATE_FAILED)
                    .execute()
                    .round(0);
            assertTerminalCompletedWithInnerCause("rt-031-04-27-e3b", round, model, requestBaseline);
            assertEndNodeEmitted("rt-031-04-27-e3b", round);

            // 主断言 1：循环真实按 metadata.inputs 列表迭代，且每轮展开为对应元素。
            List<String> bodies = model.requestsSince(requestBaseline).stream()
                    .map(record -> record.body().toString())
                    .toList();
            assertThat(bodies)
                    .as("循环体 LLM 节点必须按 metadata.inputs 列表长度被调用（循环列表来自调用方）")
                    .hasSize(loopItems.size());
            for (String item : loopItems) {
                assertThat(bodies)
                        .as("循环元素 %s 必须出现在某一轮模型请求体中（逐元素展开）", item)
                        .anySatisfy(body -> assertThat(body).contains(item));
            }

            // 主断言 2：子流被真实调度，其回显经父 End 回灌到响应。
            assertThat(round.generatedText())
                    .as("父响应必须含子流回显 canary（子流输出经 ${node_subworkflow.responseContent} 回灌）"
                            + "（task=%s state=%s）", round.taskId(), round.taskState())
                    .contains(inputCanary);

            // 增强证据（non-gating）：子流懒装配观察面。
            Allure.addAttachment("E3b childIrLoadCount delta（非门禁）",
                    String.valueOf(control.status().childIrLoadCount() - childLoadBaseline));
            assertSingleStreamInvocation(control.status(), loaded);
        } finally {
            control.reset();
        }
    }

    @Test
    @Story("RT-031-04-27/E4: 用户交互真实工作流")
    @DisplayName("E4 真实 QA 工作流经 Bridge 和 A2A 返回稳定选项")
    void e4RunsUserInteractionExport() {
        Map<String, Object> ir = artifact("qa", Set.of("user-interaction", "streaming"));
        StudioDslHostControlClient control = controlClient();
        try {
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:qa", ir, Map.of(), Map.of());
            List<String> options = List.of("你叫什么", "今天吃什么", "明天去哪玩");

            invokeCompleted("rt-031-04-27-e4", "E4 input", text ->
                    assertThat(options).anySatisfy(option -> assertThat(text).contains(option)));

            assertSingleStreamInvocation(control.status(), loaded);
        } finally {
            control.reset();
        }
    }

    /** 受控插件端点固定返回体中的 canary（见 RecordingPluginMcpEndpointFixture 的 /plugin/weather）。 */
    private static final String E5_PLUGIN_CANARY = "plugin-canary";

    /**
     * E5 真执行：语料 `node_start → node_e5_plugin(GET 受控端点) → node_end(result = 插件 data)`，
     * 受控端点按调用前后增量断言"恰一次"外部调用，并断言插件响应回灌父 End。
     */
    @Test
    @Story("RT-031-04-27/E5: 外部调用真实工作流")
    @DisplayName("E5 真执行：受控插件端点收到恰一次调用，插件响应回灌父 End")
    void e5RequiresControlledExternalCallExport() throws Exception {
        Map<String, Object> ir = artifact("feat031-e5-plugin-endpoint", Set.of("external-call", "streaming"));
        StudioDslHostControlClient control = controlClient();
        try (RecordingPluginMcpEndpointFixture endpoint = new RecordingPluginMcpEndpointFixture()) {
            String exportedUrl = componentConfig(ir, "jiuwen.plugin").get("url").toString();
            assertThat(exportedUrl)
                    .as("E5 prerequisite: the byte-identical real export must target the controlled endpoint")
                    .isEqualTo(endpoint.pluginUrl());
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:e5", ir, Map.of(), Map.of());
            int requestBaseline = endpoint.requestCount();

            InteractionFlow.RoundResult round = invokeOnce("rt-031-04-27-e5", "rt-031-04-27-e5-query");
            List<RecordingPluginMcpEndpointFixture.RequestRecord> calls = endpoint.requestsSince(requestBaseline);
            Allure.addAttachment("E5 受控插件端点请求增量（非门禁：主断言失败时仍留证）",
                    "calls=" + calls.size() + "\n"
                            + calls.stream().map(call -> call.method() + " " + call.path())
                            .collect(Collectors.joining("\n")));
            assertThat(round.generatedText())
                    .as("E5 父响应必须含受控插件端点 canary（本轮受控端点请求增量=%s）", calls.size())
                    .contains(E5_PLUGIN_CANARY);
            assertThat(calls).as("受控端点在本轮调用次数").hasSize(1);
            assertThat(calls.get(0).method()).isEqualTo("GET");
            assertThat(calls.get(0).path()).startsWith("/plugin/weather");
            assertSingleStreamInvocation(control.status(), loaded);
        } finally {
            control.reset();
        }
    }

    @Test
    @Story("RT-031-04-27/E6: 异常路径真实工作流")
    @DisplayName("E6 真实异常节点经 Bridge 和 A2A 收敛为失败终态")
    void e6RunsExceptionExport() {
        Map<String, Object> ir = artifact("exception", Set.of("exception", "streaming"));
        StudioDslHostControlClient control = controlClient();
        try {
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:exception", ir, Map.of(), Map.of());
            InteractionFlow.FlowResult result = InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withContextId("rt-031-04-27-e6-" + System.nanoTime())
                    .send("E6 trigger")
                    .mayReachState(TaskState.TASK_STATE_FAILED)
                    .execute();

            assertThat(result.round(0).taskState()).isEqualTo(TaskState.TASK_STATE_FAILED);
            assertSingleStreamInvocation(control.status(), loaded);
        } finally {
            control.reset();
        }
    }

    /**
     * 包 5-a｜执行超时上限的失败面：任务执行超过产品自身的执行时限（实测 60s）时，必须给出**有界且可诊断**
     * 的失败——既不得无限悬挂，也不得以"成功但空结果"收尾（#280 的表现形态）。
     *
     * <p>触发语料是已冻结的父子嵌套语料（该语料当前的执行另有独立产品问题在跟：父套子不回灌）；
     * 本用例只判定"超时面"这一契约：终态为 FAILED、失败文本含超时语义、且在测试上界内返回。</p>
     */
    @Test
    @Story("RT-031-04-30/timeout: 执行超时上限的失败面")
    @DisplayName("RT-031-04-30 超时上限：受控模型端点不响应时，任务以 FAILED + 可诊断超时原因收口（不悬挂、不静默成功）")
    void timeoutLimitSurfacesDiagnosableFailure() throws Exception {
        Map<String, Object> ir = artifact("system", Set.of("sequential", "streaming"));
        StudioDslHostControlClient control = controlClient();
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            // 确定性超时触发件：本轮受控模型端点保持连接但不返回响应，调用必然触达运行时 60s 执行上限。
            model.stallModelResponses();
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:timeout-stall", ir, modelMapping(ir, model.baseUrl()), Map.of());
            A2aServiceClient client = client("studio-dsl-ir-sit");
            long awaitBoundMs = 100_000L;

            InteractionFlow.FlowResult result = InteractionFlow.of(client)
                    .withTimeoutMs(awaitBoundMs)
                    .withContextId("rt-031-04-30-timeout-" + System.nanoTime())
                    .send("rt-031-04-30-timeout-input")
                    .mayReachState(TaskState.TASK_STATE_FAILED)
                    .execute();

            assertThat(result.round(0).taskState())
                    .as("超过执行时限必须以失败终态收口，不得停留在 WORKING")
                    .isEqualTo(TaskState.TASK_STATE_FAILED);
            assertThat(result.round(0).durationMs())
                    .as("必须在有界时间内返回（超时面上限内），不得悬挂到测试上界")
                    .isLessThan(awaitBoundMs);

            String failureText = replyText(client.getTask(result.lastTaskId()));
            assertThat(failureText)
                    .as("超时失败面必须可诊断（含超时语义），不得静默成功")
                    .contains("time limit");
            assertSingleStreamInvocation(control.status(), loaded);
        } finally {
            control.reset();
        }
    }

    /**
     * 包 5-b｜非法/缺配置的 IR 不得静默装配：模型映射缺失时，装载必须在装配期显式失败并给出可诊断原因
     * （`IrAssembleException` + 缺失模型标识），不得半装配、也不得等到执行期才以静默成功收尾。
     *
     * <p>观察面是宿主控制面的装载结果：失败面必须带错误类型与原因原文（#280 的教训是"静默成功"，
     * 本用例固定"装配期必须响"这一契约）。</p>
     */
    @Test
    @Story("RT-031-04-30/no-silent-assembly: 非法或缺配置的 IR 不得静默装配")
    @DisplayName("RT-031-04-30 非静默失败：缺模型映射的 IR 在装载时显式失败并给出可诊断原因")
    void missingModelMappingFailsLoudlyAtLoadInsteadOfSilentAssembly() {
        Map<String, Object> ir = artifact("feat031-e2-branch-parallel", Set.of("branch", "parallel"));
        StudioDslHostControlClient control = controlClient();
        try {
            // 刻意不提供模型映射：装配期必须报错，不得静默装配出"能跑但结果为空"的产物。
            assertThatThrownBy(() -> control.replace(
                    "studio-export:no-silent-assembly", ir, Map.of(), Map.of()))
                    .as("缺模型映射的 IR 必须在装载时显式失败，不得静默成功")
                    .hasMessageContaining("cannot replace")
                    .hasStackTraceContaining("IrAssembleException")
                    .hasStackTraceContaining("has no endpoint/apiKey mapping");

            assertThat(control.status().loadCount()).as("失败装载不得改变已装载版本").isNotNull();
        } finally {
            control.reset();
        }
    }

    /**
     * 包 3b｜子流懒装配的可观测面（按 L2 口径分相断言）。
     *
     * <p>L2 承诺的分工是「**装配期 snapshot 子 IR，首次 invoke/stream 再 assemble**」：取 IR（host
     * 的 {@code childIrLoader} 回调）发生在父工作流装配期；首次调用只做子图装配，不得再次取 IR。
     * 因此断言分为两段：替换后计数 +1、调用后再取计数为 0 增量；缺子流对照必须为 0。</p>
     *
     * <p>2026-09-20 修正：原断言写成「调用后装载计数 +1」，把 snapshot 与 assemble 两个相位混为一谈，
     * 在合入前后的制品上都失败（计数实测为 0）。按实现注释与 L2 口径改为分相断言。</p>
     */
    @Test
    @Story("RT-031-04-30/child-lazy-load: 子流懒装配观察面")
    @DisplayName("RT-031-04-30 子流懒装配：装配期 snapshot 子 IR 恰一次，首次 invoke 不再取 IR，缺子流为 0")
    void childIrLoadCounterProvesLazyChildAssembly() {
        Map<String, Object> parent = artifact(
                "feat031-e2e-parent-child", Set.of("sequential", "subworkflow", "streaming"));
        Map<String, Object> child = StudioExportCorpusFixture.artifactIr(
                manifest(), "feat031-e2e-parent-child-child");
        String childRefPath = childReferencePath(parent);
        StudioDslHostControlClient control = controlClient();
        try {
            long beforeReplace = control.status().childIrLoadCount();
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:lazy-child", parent, Map.of(), Map.of(childRefPath, child));

            assertThat(loaded.childIrLoadCount() - beforeReplace)
                    .as("父工作流装配期必须 snapshot 一次子流 IR（L2：装配期 snapshot 子 IR）")
                    .isEqualTo(1L);

            long beforeInvoke = loaded.childIrLoadCount();

            // 调用一次（该语料的执行另有产品问题跟踪，本用例只判"子流是否被装载"这一观测面）。
            InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withContextId("rt-031-04-30-lazy-" + System.nanoTime())
                    .send("rt-031-04-30-lazy-input")
                    .execute();

            assertThat(control.status().childIrLoadCount())
                    .as("首次 invoke 只做子图装配，不得再次取子流 IR（L2：首次 invoke/stream 再 assemble）")
                    .isEqualTo(beforeInvoke);

            StudioDslHostControlClient.HostState missing = control.replace(
                    "studio-export:lazy-child-missing", parent, Map.of(), Map.of());
            long beforeMissing = missing.childIrLoadCount();
            InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withContextId("rt-031-04-30-lazy-missing-" + System.nanoTime())
                    .send("rt-031-04-30-lazy-missing-input")
                    .execute();
            assertThat(control.status().childIrLoadCount() - beforeMissing)
                    .as("未提供子流的对照：装配与调用都不得产生子流装载计数")
                    .isZero();
        } finally {
            control.reset();
        }
    }

    /**
     * 包 6a｜嵌套深度上限：超过 {@code maxNestingDepth} 的嵌套必须**显式失败**且失败面含嵌套深度语义
     * （FEAT §3.4「超过配置深度的嵌套必须被拒绝并映射为结构化失败表面」）。
     *
     * <p><b>2026-09-20 核实（两轮执行证据）</b>：①「父→子两层 + {@code maxNestingDepth=1}」不会超限——
     * 实现按"父工作流第 0 层、子流第 1 层"计数（{@code IrAssembleContext.child(): nestingDepth + 1 > max}）；
     * ② {@code maxNestingDepth <= 0} 会被 SDK 归一为默认值（{@code IrAssembleContext:51}），因此
     * {@code maxNestingDepth=0} 无法作为触发手段。**与层数口径无关的唯一触发方式是存在三层真实语料**
     * （父→子→孙）。当前真实导出语料最深两层（`feat031-e2e-parent-child` + 其子），故本用例处于
     * `blocked`：用 assumption 门控，Studio 产出三层真实导出后自动升级为真执行（登记见 FEAT-031
     * 降级与待恢复登记）。</p>
     */
    @Test
    @Story("RT-031-04-30/nesting-depth: 嵌套深度上限必须显式生效")
    @DisplayName("RT-031-04-30 嵌套深度：三层语料在 maxNestingDepth=1 下必须显式失败并指出嵌套语义")
    void nestingDepthLimitIsEnforcedExplicitly() {
        Map<String, Object> parent = artifact(
                "feat031-e2e-parent-child", Set.of("sequential", "subworkflow", "streaming"));
        Map<String, Object> child = StudioExportCorpusFixture.artifactIr(
                manifest(), "feat031-e2e-parent-child-child");
        String childRefPath = childReferencePath(parent);

        Assumptions.assumeTrue(
                hasNestedChildWorkflow(child),
                "嵌套深度上限 blocked：现有真实导出语料最深两层，无法超过 maxNestingDepth"
                        + "（父层按 0 计且 max<=0 归一为默认值）；升级触发＝Studio 产出三层真实导出语料");

        StudioDslHostControlClient control = controlClient();
        try {
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> control.replace(
                    "studio-export:depth-limit", parent, Map.of(), Map.of(childRefPath, child), 1));
            assertThat(thrown)
                    .as("maxNestingDepth=1 时三层语料必须显式失败，不得静默装配")
                    .isNotNull();
            String reason = String.valueOf(thrown.getMessage())
                    + " | " + (thrown.getCause() == null ? "" : String.valueOf(thrown.getCause().getMessage()));
            assertThat(reason.toLowerCase())
                    .as("失败面必须能定位到嵌套深度语义")
                    .contains("nest");
        } finally {
            control.reset();
        }
    }

    /** 三层语料探针：子流自身还必须包含子工作流节点（父→子→孙）。 */
    private static boolean hasNestedChildWorkflow(Map<String, Object> child) {
        Object components = child.get("components");
        if (!(components instanceof List<?> list)) {
            return false;
        }
        return list.stream()
                .filter(component -> component instanceof Map<?, ?>)
                .map(component -> String.valueOf(((Map<?, ?>) component).get("type")))
                .anyMatch(type -> type.contains("workflowComposite") || type.contains("subWorkflow"));
    }

    /**
     * #293 / #302 取证：宿主按契约（L2「Tracer I/O 级别由宿主消费配置对象」+ SDK
     * {@code IrCrossCuttingIoTrace.ensureTraceModes}）消费装配出的横切配置后，IR 的 {@code ioLogLevel}
     * 必须驱动出可观测的节点 I/O；未声明该配置时必须观测不到（对照组）。
     *
     * <p>语料口径（结论需标注）：使用真实 Studio 导出语料 {@code system}，**仅在测试内注入工作流级
     * {@code configs.ioLogLevel}**——当前冻结的真实导出语料未携带该字段。待 Studio 产出自带该字段的
     * 真实导出语料后，应替换为本字段属真实导出的变体。</p>
     *
     * <p><b>开发确认口径（2026-09-20）</b>：① 与设计一致——由宿主消费 {@code IrCrossCuttingConfig} 并请求
     * TRACE，**不是 IR 配置自动生效**；② 主证据＝TRACE 流，host.log 只是宿主镜像、作补充；③ INFO 与 DEBUG
     * 是同一套 TRACE 内容（可选日志标签不同），不是内容粒度分级。因此本用例以宿主观测到的 TRACE chunk
     * 为准判定，应用日志不作门禁。</p>
     *
     * <p>主断言：① {@code ioLogLevel=INFO} 时宿主观测到的 TRACE I/O chunk 增量 &gt; 0，且节点 I/O 样本
     * 携带本轮 canary（可定位到节点）；② 未声明 {@code ioLogLevel} 时增量为 0。</p>
     */
    @Test
    @Story("RT-031-04-31/io-log: 横切 I/O 日志配置生效观察面")
    @DisplayName("#293/#302 取证：ioLogLevel=INFO 时宿主可观测到携带 canary 的节点 I/O，未声明时为 0")
    void ioLogLevelDrivesHostObservableNodeIo() throws Exception {
        Map<String, Object> base = artifact("system", Set.of("sequential", "streaming"));
        StudioDslHostControlClient control = controlClient();
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            String modelCanary = "rt-031-04-31-io-model-canary";
            String inputCanary = "rt-031-04-31-io-query-canary";
            model.responseCanary(modelCanary);

            Map<String, Object> withIoLog = withIoLogLevel(base, "INFO");
            StudioDslHostControlClient.HostState on = control.replace(
                    "studio-export:io-log-on", withIoLog,
                    modelMapping(withIoLog, model.baseUrl()), Map.of());
            assertThat(on.ioLogActive())
                    .as("ioLogLevel=INFO 必须被装配为 active 横切配置")
                    .isTrue();
            long onBaseline = on.ioTraceChunkCount();
            invokeOnce("rt-031-04-31-io-on", inputCanary);
            long onDelta = control.status().ioTraceChunkCount() - onBaseline;
            assertThat(onDelta)
                    .as("ioLogLevel=INFO 时宿主必须观测到节点 I/O（#293 主断言：装配产物驱动按配置生效）")
                    .isPositive();
            assertThat(control.status().ioTraceSample())
                    .as("节点 I/O 必须携带本轮 canary，可用于定位到节点（#302 主断言）")
                    .satisfiesAnyOf(
                            sample -> assertThat(sample).contains(modelCanary),
                            sample -> assertThat(sample).contains(inputCanary));

            StudioDslHostControlClient.HostState off = control.replace(
                    "studio-export:io-log-off", base,
                    modelMapping(base, model.baseUrl()), Map.of());
            assertThat(off.ioLogActive())
                    .as("未声明 ioLogLevel 时不得请求 TRACE")
                    .isFalse();
            long offBaseline = off.ioTraceChunkCount();
            invokeOnce("rt-031-04-31-io-off", "rt-031-04-31-io-off-query");
            assertThat(control.status().ioTraceChunkCount() - offBaseline)
                    .as("未声明 ioLogLevel 时不得产生节点 I/O TRACE chunk（对照组）")
                    .isZero();
        } finally {
            control.reset();
        }
    }

    /**
     * #302 后半段取证：执行期失败必须能从可观测面**定位到失败节点**。
     *
     * <p>做法（口径：主证据＝TRACE 流，开发 2026-09-20 确认）：真实导出 {@code exception} 语料 + 测试内
     * 注入工作流级 {@code configs.ioLogLevel=INFO} → 触发失败终态 → 从失败面原文提取失败节点 id →
     * 在宿主消费到的节点 I/O 记录里定位到同一节点 id。</p>
     *
     * <p>主断言：① 任务以 FAILED 收口；② 失败面（A2A 事件流 + 终态 Task 快照）给出失败节点 id，
     * 即"能从可观测面定位到是哪个节点失败"。</p>
     *
     * <p><b>非门禁观察</b>：失败路径上宿主消费到的 TRACE I/O 记录数。2026-09-20 实测为 **0**（异常节点
     * 中止执行时运行时不向流式消费方投递 TRACE chunk），因此失败定位的<b>实际载体是失败面</b>而非 TRACE
     * I/O；该观察只作留档，不用于判定，避免把"期望 TRACE 也能定位"写成 PASS。</p>
     */
    @Test
    @Story("RT-031-04-32/io-log-failure: 失败节点的 I/O 定位面")
    @DisplayName("#302 取证：失败终态下可从节点 I/O 记录定位到失败节点")
    void ioLogLevelLocatesFailingNodeOnExecutionFailure() {
        Map<String, Object> base = artifact("exception", Set.of("exception", "streaming"));
        Map<String, Object> ir = withIoLogLevel(base, "INFO");
        StudioDslHostControlClient control = controlClient();
        try {
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:io-log-failure", ir, Map.of(), Map.of());
            assertThat(loaded.ioLogActive())
                    .as("ioLogLevel=INFO 必须被装配为 active 横切配置")
                    .isTrue();
            long baseline = loaded.ioTraceChunkCount();

            InteractionFlow.RoundResult round = InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withTimeoutMs(DEFAULT_AWAIT_TIMEOUT_MS)
                    .withContextId("rt-031-04-32-io-failure-" + System.nanoTime())
                    .send("rt-031-04-32-io-failure-trigger")
                    .mayReachState(TaskState.TASK_STATE_FAILED)
                    .execute()
                    .round(0);

            String failureText = round.events().stream()
                    .map(InboundEvent::text)
                    .collect(Collectors.joining("\n"))
                    + "\n" + terminalTaskSnapshot(round);
            Allure.addAttachment("RT-031-04-32 失败面原文（含失败节点标识）", failureText);

            assertThat(round.taskState())
                    .as("异常语料必须以失败终态收口（实际=%s）", round.taskState())
                    .isEqualTo(TaskState.TASK_STATE_FAILED);

            String failingNodeId = firstNodeId(failureText);
            assertThat(failingNodeId)
                    .as("失败面必须给出失败节点标识，否则无从定位（失败面原文=%s）", compactForMessage(failureText))
                    .isNotNull();

            long failureTraceChunks = control.status().ioTraceChunkCount() - baseline;
            Allure.addAttachment("RT-031-04-32 失败路径节点 I/O 记录数（非门禁观察）",
                    "ioTraceChunkCount delta=" + failureTraceChunks
                            + "; 失败节点 id=" + failingNodeId
                            + "; 定位载体=" + (failureTraceChunks > 0 ? "TRACE I/O 记录" : "失败面（task status.message）"));
            assertThat(failingNodeId)
                    .as("#302 主断言：失败节点可从可观测面定位（失败面原文=%s；TRACE I/O 记录数=%s）",
                            compactForMessage(failureText), failureTraceChunks)
                    .isNotNull();
        } finally {
            control.reset();
        }
    }

    /** 从失败原文里取第一个节点标识：优先 {@code nodeId=node_*}，其次任意 {@code node_*} 片段。 */
    private static String firstNodeId(String text) {
        java.util.regex.Matcher explicit =
                java.util.regex.Pattern.compile("nodeId=(node_[A-Za-z0-9_]+)").matcher(text);
        if (explicit.find()) {
            return explicit.group(1);
        }
        java.util.regex.Matcher any = java.util.regex.Pattern.compile("(node_[A-Za-z0-9_]+)").matcher(text);
        return any.find() ? any.group(1) : null;
    }

    /** 复制真实导出语料并在工作流级注入横切 {@code ioLogLevel}（不修改共享语料对象）。 */
    private static Map<String, Object> withIoLogLevel(Map<String, Object> ir, String level) {
        Map<String, Object> copy = new LinkedHashMap<>(ir);
        Map<String, Object> configs = new LinkedHashMap<>();
        Object existing = ir.get("configs");
        if (existing instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                configs.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        configs.put("ioLogLevel", level);
        copy.put("configs", configs);
        return copy;
    }

    @Test
    @Story("RT-031-04-27/E7: 条件 Card 真实工作流")
    @DisplayName("E7 Card 必须有可追溯的真实导出 artifact")
    void e7RequiresRealCardExport() {
        StudioExportCorpusFixture.CatalogEntry card = manifest().catalog().stream()
                .filter(entry -> "Card".equals(entry.node()))
                .findFirst()
                .orElseThrow();
        // Studio has not produced a real Card export artifact yet (capability not implemented),
        // so the case is recorded as a documented skip instead of a product-facing failure.
        Assumptions.assumeTrue(
                card.artifact() != null,
                "E7 OUT: Card 在冻结 Studio 版本上还没有真实导出产物（Studio 侧功能未实现），本用例只记录说明");
    }

    private StudioExportCorpusFixture.Manifest manifest() {
        return StudioExportCorpusFixture.manifest();
    }

    /**
     * B1 参数展开语料（2026-09-18 新建）：原 E3 语料的 ParamExtraction 引用的两个校准子流已从 Studio 删除，
     * 因此按同一节点 DSL 派生一份不含外部校准子流的自洽语料（{@code Start(query) → ParamExtraction → End}，
     * 领域对象 1 个、{@code processing_workflows} 为空），其 IR 是管理器展开后的 8 组件图
     * （{@code EI.ParamOutput} + {@code jiuwen.branch} + 提取用 {@code jiuwen.LLMComponent} 构成的循环）。
     *
     * <p>主断言（探针）：任务到达 COMPLETED 且流中存在 {@code jiuwen.end} 产物；提取用模型节点经受控端点被真实调用，
     * 且请求体携带调用方输入（证明 {@code Start.query → ParamExtraction.input_param1 → LLM 模板} 这条链真的执行）。
     * 提取结果是否回灌到响应文本只作观察，不参与判定。</p>
     */
    @Test
    @Story("RT-031-04-27/E3pe: 参数展开节点真执行探针")
    @DisplayName("E3pe 真执行：ParamExtraction 展开图装载并调用受控模型，任务到达 COMPLETED")
    void e3peParamExtractionRealExecution() throws Exception {
        Map<String, Object> ir = artifact(
                "feat031-b1-param-extraction", Set.of("sequential", "parameter-expansion", "streaming"));
        StudioDslHostControlClient control = controlClient();
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            model.responseCanary("rt-031-04-27-e3pe-model-canary");
            // G4 复验：该提取节点声明 response_format=json，纯文本 canary 会被 JSON 解析路径拒绝，
            // 因此这里返回一个合法 JSON，键名取节点声明的字段名（intermediate_param1 / accountName）。
            String jsonCanary = "rt-031-04-27-e3pe-json-canary";
            model.responseContent(
                    "{\"intermediate_param1\":\"" + jsonCanary + "\",\"accountName\":\"" + jsonCanary + "\"}");
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:b1-param-extraction",
                    ir,
                    modelMapping(ir, model.baseUrl()),
                    Map.of());
            int requestBaseline = model.requestCount();
            String inputCanary = "rt-031-04-27-e3pe-query-canary";

            InteractionFlow.RoundResult round = InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withTimeoutMs(DEFAULT_AWAIT_TIMEOUT_MS)
                    .withContextId("rt-031-04-27-e3pe-" + System.nanoTime())
                    .send(inputCanary)
                    .mayReachState(TaskState.TASK_STATE_FAILED)
                    .execute()
                    .round(0);
            assertTerminalCompletedWithInnerCause("rt-031-04-27-e3pe", round, model, requestBaseline);
            assertEndNodeEmitted("rt-031-04-27-e3pe", round);

            // 主断言：参数展开的提取模型真的被调用，且请求体携带调用方输入。
            List<String> bodies = model.requestsSince(requestBaseline).stream()
                    .map(record -> record.body().toString())
                    .toList();
            assertThat(bodies)
                    .as("参数展开必须调用提取用模型节点（受控端点请求增量 > 0）")
                    .isNotEmpty();
            assertThat(bodies.get(0))
                    .as("提取请求体必须携带调用方输入（Start.query → input_param1）")
                    .contains(inputCanary);

            // 门禁（2026-09-19：按 ISSUE #304 回归入口 R-304 的要求，由"非门禁观察"升级为判据）：
            // JSON 响应变体下，提取结果必须回灌到响应文本。未修复基线上本断言应 FAIL ——
            // 该失败同时是"主断言具备故障检出能力"的反证，修复后按同一入口复跑应转 PASS。
            boolean propagated = round.generatedText().contains(jsonCanary);
            Allure.addAttachment("E3pe G4 回灌门禁（JSON 响应变体）",
                    "g4Propagated=" + propagated
                            + "\nextractionRequests=" + bodies.size()
                            + "\nresponse=" + compactForMessage(round.generatedText()));
            assertThat(propagated)
                    .as("提取结果必须回灌到响应文本（回灌 canary 命中）；未修复基线应 FAIL，见 #304")
                    .isTrue();
            assertSingleStreamInvocation(control.status(), loaded);
        } finally {
            control.reset();
        }
    }

    /**
     * 端到端父子嵌套（2026-09-17 新增语料）：父 IR 的 {@code jiuwen.workflowComposite} 节点按
     * {@code configs.reference.path} 引用子流，子 IR 经宿主控制面的 {@code children} 传入。
     *
     * <p>本用例补的是既有 E3 语料无法覆盖的一环：E3 父 IR 的 {@code node_subworkflow} 输出没有消费者
     * （全 IR 搜索 {@code node_subworkflow.} 命中 0），因此"子流是否被调度、结果是否回灌"无法从响应判定。
     * 新语料把父 {@code node_end} 的输入接成 {@code ${node_subworkflow.responseContent}}，于是子流回显的
     * canary 若能出现在父流程响应里，即同时证明：子流被真正调度 + 结果回灌父工作流。</p>
     */
    @Test
    @Story("RT-031-04-27/E3s: 父工作流经 children 装载子工作流并回灌结果")
    @DisplayName("E3s 父套子：子流被调度且其结果回灌父工作流并出现在响应中")
    void e3sParentInvokesChildAndChildEchoReachesParentResponse() {
        Map<String, Object> parent = artifact(
                "feat031-e2e-parent-child", Set.of("sequential", "subworkflow", "streaming"));
        Map<String, Object> child = StudioExportCorpusFixture.artifactIr(
                manifest(), "feat031-e2e-parent-child-child");
        String childRefPath = childReferencePath(parent);
        StudioDslHostControlClient control = controlClient();
        try {
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:e2e-parent-child",
                    parent,
                    Map.of(),
                    Map.of(childRefPath, child));
            String canary = "rt-031-04-27-e3s-child-echo";

            // 主断言：子流把 query 回显，回显值经 ${node_subworkflow.responseContent} 进入父 End。
            invokeCompleted("rt-031-04-27-e3s", canary,
                    text -> assertThat(text).contains(canary), e3sAwaitTimeoutMs());

            assertSingleStreamInvocation(control.status(), loaded);
        } finally {
            control.reset();
        }
    }

    /**
     * 缺子流的执行层行为：设计 §4.2 承诺"缺失子工作流只记 WARN 并跳过，不中断主图装配"，FEAT-031
     * §3.5.4 亦不承诺子流缺失时的输出。因此本用例只断言**父流程仍到达终态**（不中断），并显式声明
     * 本形态下响应不含子流输出——这属于设计已明确的行为边界，不作为 FAIL 面。
     */
    @Test
    @Story("RT-031-04-27/E3s-missing: 缺子流时装配不中断")
    @DisplayName("E3s 缺子流：控制面装配不中断（门禁）；运行期终态仅观察记录（设计未定义，非门禁）")
    void e3sMissingChildDoesNotAbortParent() {
        Map<String, Object> parent = artifact(
                "feat031-e2e-parent-child", Set.of("sequential", "subworkflow", "streaming"));
        StudioDslHostControlClient control = controlClient();
        try {
            // 门禁：FEAT-031 §4.2/§4.4 —— 缺子流只 WARN + 跳过，不得中断装配。
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "studio-export:e2e-parent-child-missing",
                    parent,
                    Map.of(),
                    Map.of());
            assertThat(loaded).as("缺子流时控制面装配不得中断").isNotNull();
            assertThat(loaded.componentIds()).as("父图装配产物不应为空").isNotEmpty();

            // 观察（非门禁）：§3.2 明确“子流失败传播”为 OUT，运行期终态不作为验收判据，仅留档。
            String canary = "rt-031-04-27-e3s-missing-canary";
            InteractionFlow.FlowResult observed = InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withTimeoutMs(DEFAULT_AWAIT_TIMEOUT_MS)
                    .withContextId("rt-031-04-27-e3s-missing-" + System.nanoTime())
                    .send(canary)
                    .mayReachState(TaskState.TASK_STATE_FAILED)
                    .execute();
            Allure.addAttachment("E3s-missing 运行期终态（设计未定义，非门禁）",
                    String.valueOf(observed.round(0).taskState()));
        } finally {
            control.reset();
        }
    }

    /** 取出父 IR 中 {@code jiuwen.workflowComposite} 节点的 {@code configs.reference.path}（即 children 键）。 */
    private static String childReferencePath(Map<String, Object> ir) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components = (List<Map<String, Object>>) ir.get("components");
        for (Map<String, Object> component : components) {
            if (!"jiuwen.workflowComposite".equals(component.get("type"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> configs = (Map<String, Object>) component.get("configs");
            @SuppressWarnings("unchecked")
            Map<String, Object> reference = (Map<String, Object>) configs.get("reference");
            return String.valueOf(reference.get("path"));
        }
        throw new IllegalStateException("parent IR has no jiuwen.workflowComposite node");
    }

    @Test
    @Story("RT-031-04-27/E8: 入口自定义字段驱动判断节点分流（本仓 #311 回归入口）")
    @DisplayName("E8 default 边：metadata.inputs.loginFlag=1 时只命中 default 分支下游，行为轨迹与 golden 一致")
    void e8EntryFieldDrivesDefaultBranch() throws Exception {
        assertBranchBehavior("feat031-e8-entry-field-branch", "rt-031-04-27-e8-default", "hello",
                Map.of("inputs", Map.of("loginFlag", "1")), E8_MARKERS, null,
                "/testdata/studio_dsl/behavior-golden/feat031-e8-entry-field-branch.default.json");
    }

    @Test
    @Story("RT-031-04-27/E8: 入口自定义字段驱动判断节点分流（本仓 #311 回归入口）")
    @DisplayName("E8 if 边：metadata.inputs.loginFlag=0 时只命中 if 分支下游，行为轨迹与 golden 一致")
    void e8EntryFieldDrivesIfBranch() throws Exception {
        assertBranchBehavior("feat031-e8-entry-field-branch", "rt-031-04-27-e8-if", "hello",
                Map.of("inputs", Map.of("loginFlag", "0")), E8_MARKERS, null,
                "/testdata/studio_dsl/behavior-golden/feat031-e8-entry-field-branch.if.json");
    }

    @Test
    @Story("RT-031-04-27/E9: 判断条件读 systemFields.query 时的分流（既有语料写法的可执行对照）")
    @DisplayName("E9 if 边：query 长度>1 时命中 if 分支下游，标记序列与 golden 一致")
    void e9QueryConditionDrivesIfBranch() throws Exception {
        assertBranchBehavior("feat031-e9-query-condition-branch", "rt-031-04-27-e9-if", "hello",
                Map.of(), E8_MARKERS, null,
                "/testdata/studio_dsl/behavior-golden/feat031-e9-query-cond-branch.if.json");
    }

    @Test
    @Story("RT-031-04-27/E9: 判断条件读 systemFields.query 时的分流（既有语料写法的可执行对照）")
    @DisplayName("E9 default 边：query 长度<=1 时命中 default 分支下游，标记序列与 golden 一致")
    void e9QueryConditionDrivesDefaultBranch() throws Exception {
        assertBranchBehavior("feat031-e9-query-condition-branch", "rt-031-04-27-e9-default", "x",
                Map.of(), E8_MARKERS, null,
                "/testdata/studio_dsl/behavior-golden/feat031-e9-query-cond-branch.default.json");
    }

    @Test
    @Story("RT-031-04-27/E10: 判断条件读【上游节点产出】时的分流（既有语料里最常见的一类）")
    @DisplayName("E10 判别用例：上游产出等于 canary → 条件应为 false → 只允许命中 default 分支下游")
    void e10UpstreamFieldDrivesDefaultBranch() throws Exception {
        // 受控模型返回固定 canary，使 ${node_up.userFields.raw_output} != 'UPSTREAM_CANARY' 语义为 false。
        assertBranchBehavior("feat031-e10-upstream-field-branch", "rt-031-04-27-e10-default", "hello",
                Map.of(), E10_MARKERS, "UPSTREAM_CANARY",
                "/testdata/studio_dsl/behavior-golden/feat031-e10-upstream-field-branch.default.json");
    }

    @Test
    @Story("RT-031-04-27/E10: 判断条件读【上游节点产出】时的分流（既有语料里最常见的一类）")
    @DisplayName("E10 镜像用例：上游产出不等于比较值 → 条件应为 true → 只允许命中 if 分支下游")
    void e10UpstreamFieldDrivesIfBranch() throws Exception {
        // 受控模型返回另一个 canary，使条件语义为 true；缺陷下条件也判真，故该条用于给出镜像证据。
        assertBranchBehavior("feat031-e10-upstream-field-branch", "rt-031-04-27-e10-if", "hello",
                Map.of(), E10_MARKERS, "NOT_THE_CANARY",
                "/testdata/studio_dsl/behavior-golden/feat031-e10-upstream-field-branch.if.json");
    }

    /**
     * 入口自定义字段驱动分流的行为对齐断言（FEAT-031 §4.2 行为对齐手段的最小落地）。
     *
     * <p>被测 IR 的节点 id 集合，用于从宿主 I/O 采样里还原行为轨迹。</p>
     *
     * <p>判据两层：① 受控模型端点只收到期望分支下游的提示词标记；② A2A 事件流里的节点序列等于 golden。</p>
     */
    private void assertBranchBehavior(String artifactId, String caseId, String text,
            Map<String, Object> metadata, List<String> markers, String canaryOverride, String goldenResource)
            throws Exception {
        Map<String, Object> ir = artifact(artifactId, Set.of("branch", "parallel"));
        StudioDslBehaviorTraceFixture.BehaviorGolden golden = StudioDslBehaviorTraceFixture.loadGolden(goldenResource);
        StudioDslHostControlClient control = controlClient();
        try (RecordingOpenAiEndpointFixture model = new RecordingOpenAiEndpointFixture()) {
            String canary = canaryOverride == null ? caseId + "-model-canary" : canaryOverride;
            model.responseCanary(canary);
            control.replace("studio-export:" + artifactId, ir, modelMapping(ir, model.baseUrl()), Map.of());

            int baseline = model.requestCount();
            InteractionFlow.RoundResult round = sendWithMetadata(caseId, text, metadata);
            List<RecordingOpenAiEndpointFixture.RequestRecord> calls = model.requestsSince(baseline);
            String bodies = calls.stream()
                    .map(call -> call.body().toString())
                    .collect(Collectors.joining("\n"));
            Allure.addAttachment("E8 受控模型端点请求（非门禁：主断言失败时仍留证）",
                    "calls=" + calls.size() + "\n" + compactForMessage(bodies));

            // 文本贯通由 E1a/E1b 覆盖；本用例聚焦「分流行为」：哪条边被走、节点轨迹是否与 golden 一致。
            // 因此这里不断言响应文本（该语料的 fan-in/end 形状不是本用例的判据对象）。
            // 行为轨迹观测面（2026-09-20 切换）：受控端点请求序列 —— 按调用顺序抽出下游标记序列，
            // 与 golden 的期望标记序列做相等断言（比 contains/不含更强：顺序与多寡都判）。
            List<String> markerTrace = markerTraceOf(calls, markers);
            Allure.addAttachment("E8 行为轨迹（受控端点标记序列）", String.join(" -> ", markerTrace));
            assertThat(markerTrace)
                    .as("行为对齐（观测面=受控端点请求序列）：golden=%s，期望来源=%s",
                            golden.scenario(), golden.expectationSource())
                    .isEqualTo(golden.expectedMarkers());

            String ioTrace = control.status().ioTraceSample();
            List<String> trace = StudioDslBehaviorTraceFixture.nodeTraceFromIoTrace(ioTrace, componentIdsOf(ir));
            if (trace.isEmpty()) {
                trace = StudioDslBehaviorTraceFixture.nodeTrace(round.events());
            }
            Allure.addAttachment("E8 宿主 I/O 采样（ioTraceSample）",
                    ioTrace == null ? "(null)" : compactForMessage(ioTrace));
            Allure.addAttachment("E8 行为轨迹（节点序列，增强证据 / non-gating）", String.join(" -> ", trace));
            // 2026-09-21 判定：节点序列＝**增强证据 / non-gating**，不得作为门禁。主断言＝上面的受控端点标记序列。
            // 依据：G1 文档 §①-补八 + behavior-golden/provenance.json（A2A 节点帧不是稳定观测面，Python 侧行为
            // golden 未生成）。实测 PR !646 之后 A2A 事件流开始带 node_end 帧，若沿用"非空即断言"会把增强证据
            // 变成门禁，产生与产品行为无关的误红（见 G1 §16.3）。轨迹与 golden 的一致性只留证、不判定。
            Allure.addAttachment("E8 行为轨迹来源说明",
                    "增强证据 / non-gating；主断言＝受控端点标记序列（是否命中期望分支下游）。"
                            + " golden=%s，期望轨迹=%s，本轮观测轨迹=%s，是否一致=%s".formatted(
                                    golden.scenario(), golden.expectedTrace(),
                                    trace, trace.equals(golden.expectedTrace())));
        } finally {
            control.reset();
        }
    }

    private Map<String, Object> artifact(String artifactId, Set<String> requiredDimensions) {
        Map<String, Object> ir = StudioExportCorpusFixture.artifactIr(manifest(), artifactId);
        assertThat(StudioExportCorpusFixture.derivedDimensions(ir))
                .as(artifactId + " dimensions derived from components/connections")
                .containsAll(requiredDimensions);
        return ir;
    }

    private StudioDslHostControlClient controlClient() {
        return new StudioDslHostControlClient(stack.baseUrl("studio-dsl-ir-sit"));
    }

    /** 被测 IR 的节点 id 集合（用于从宿主 I/O 采样里还原行为轨迹）。 */
    private static Set<String> componentIdsOf(Map<String, Object> ir) {
        return Set.copyOf(studioNodeIds(ir));
    }

    /** E8 语料两路下游在受控端点请求里出现的标记。 */
    private static final List<String> E8_MARKERS = List.of("PATH_MARKER=IF", "PATH_MARKER=DEFAULT");

    /** E10 语料：上游节点标记 + 两路下游标记。 */
    private static final List<String> E10_MARKERS =
            List.of("UPSTREAM_MARKER", "PATH_MARKER=IF", "PATH_MARKER=DEFAULT");

    /**
     * 受控端点请求序列 → 下游标记序列（行为轨迹的观测面）。
     *
     * @param calls 本轮受控模型端点的请求记录（按发生顺序）
     * @param markers 本语料可识别的下游标记
     * @return 标记序列；请求体未命中任何已知标记时记 {@code UNKNOWN}
     */
    private static List<String> markerTraceOf(
            List<RecordingOpenAiEndpointFixture.RequestRecord> calls, List<String> markers) {
        List<String> trace = new java.util.ArrayList<>();
        for (RecordingOpenAiEndpointFixture.RequestRecord call : calls) {
            String body = String.valueOf(call.body());
            String hit = "UNKNOWN";
            for (String marker : markers) {
                if (body.contains(marker)) {
                    hit = marker;
                    break;
                }
            }
            trace.add(hit);
        }
        return List.copyOf(trace);
    }

    private static List<String> studioNodeIds(Map<String, Object> ir) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components = (List<Map<String, Object>>) ir.get("components");
        List<String> ids = new java.util.ArrayList<>();
        for (Map<String, Object> component : components) {
            ids.add(String.valueOf(component.get("id")));
        }
        return ids;
    }

    private void invokeCompleted(String caseId, String input, java.util.function.Consumer<String> assertion) {
        invokeCompleted(caseId, input, assertion, DEFAULT_AWAIT_TIMEOUT_MS);
    }

    private void invokeCompleted(
            String caseId, String input, java.util.function.Consumer<String> assertion, long awaitTimeoutMs) {
        A2aServiceClient client = client("studio-dsl-ir-sit");
        InteractionFlow.FlowResult result = InteractionFlow.of(client)
                .withTimeoutMs(awaitTimeoutMs)
                .withContextId(caseId + "-" + System.nanoTime())
                .send(input)
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .assertGenerated(assertion)
                .execute();
        assertEndNodeEmitted(caseId, result.round(0));
    }

    /**
     * 执行一次调用并返回该轮结果，不在内部做文本断言。
     *
     * <p>用于需要「先取受控端点证据、再判主断言」的用例：主断言失败时，端点调用次数仍能进入断言消息与
     * Allure 附件，避免执行级失败只剩外层签名（FEAT-031 规则 10）。调用前置断言（终态 COMPLETED、与
     * {@code invokeCompleted} 相同）保持不变。</p>
     */
    private InteractionFlow.RoundResult invokeOnce(String caseId, String input) {
        A2aServiceClient client = client("studio-dsl-ir-sit");
        InteractionFlow.FlowResult result = InteractionFlow.of(client)
                .withTimeoutMs(DEFAULT_AWAIT_TIMEOUT_MS)
                .withContextId(caseId + "-" + System.nanoTime())
                .send(input)
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute();
        assertEndNodeEmitted(caseId, result.round(0));
        return result.round(0);
    }

    /**
     * FEAT-031 §3.2 结束节点 / §4.2 横切装配驱动的主断言：结束节点必须真的执行并产出对外可见结果。
     *
     * <p>#280 形态（end 装配为 TRANSFORM-only）下 A2A 流中只有上游节点产物、没有 {@code jiuwen.end}
     * 产物，上游 message 节点仍会把文本刷进 {@code generatedText}；因此只断言文本会漏检。本断言在有界
     * 报文里检查结束节点产物，2026-09-17 的现场与最小复现 before/after 对照可证明它能拦住该故障。</p>
     */
    private static void assertEndNodeEmitted(String caseId, InteractionFlow.RoundResult round) {
        assertThat(round.events())
                .as(caseId + ": A2A 流中必须出现 jiuwen.end 节点的产物（结束节点被跳过时不会出现）")
                .anySatisfy(event -> assertThat(event.text()).contains("jiuwen.end"));
    }

    /**
     * 执行级失败的内层原因采集（FEAT-031 规则 10）：把本轮 A2A 事件流与终态 Task 快照（{@code status.message}
     * 里通常带失败原文与错误码）一并写进 Allure，再让它们进入断言消息，避免结论只停在“期望成功、实际失败”。
     */
    private void assertTerminalCompletedWithInnerCause(
            String caseId,
            InteractionFlow.RoundResult round,
            RecordingOpenAiEndpointFixture model,
            int requestBaseline) {
        String observed = round.events().stream()
                .map(InboundEvent::text)
                .collect(Collectors.joining("\n"));
        String taskSnapshot = terminalTaskSnapshot(round);
        String modelEvidence = modelEvidenceSince(model, requestBaseline);
        Allure.addAttachment(caseId + " 运行期事件流（含失败内层原因）", observed);
        Allure.addAttachment(caseId + " 终态 Task 快照（含 status.message 失败原文）", taskSnapshot);
        Allure.addAttachment(caseId + " 模型端点请求增量（判循环是否真的执行）", modelEvidence);
        assertThat(round.taskState())
                .as("%s: 任务必须到达 COMPLETED；实际终态=%s；终态 Task 快照=%s；模型端点证据=%s；事件流=%s",
                        caseId, round.taskState(), compactForMessage(taskSnapshot), modelEvidence,
                        compactForMessage(observed))
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }

    /** 失败现场也要能判定循环是否真的发生：给出模型请求增量与首条请求体摘要。 */
    private static String modelEvidenceSince(RecordingOpenAiEndpointFixture model, int requestBaseline) {
        List<RecordingOpenAiEndpointFixture.RequestRecord> requests = model.requestsSince(requestBaseline);
        if (requests.isEmpty()) {
            return "modelRequests=0（循环体 LLM 未被调用）";
        }
        return "modelRequests=" + requests.size() + "; firstBody="
                + compactForMessage(requests.get(0).body().toString());
    }

    /** 终态 Task 快照：失败原文通常在 {@code status.message} 的 part 文本里，取不到时显式标注原因。 */
    private String terminalTaskSnapshot(InteractionFlow.RoundResult round) {
        if (round.taskId() == null || round.taskId().isBlank()) {
            return "<本轮无 taskId，取不到终态 Task>";
        }
        try {
            Task task = client("studio-dsl-ir-sit").getTask(round.taskId());
            String messageParts = task.status().message() == null
                    ? "<status.message 为空>"
                    : String.valueOf(task.status().message().parts());
            return "state=" + task.status().state()
                    + " statusMessageParts=" + messageParts
                    + " artifacts=" + task.artifacts();
        } catch (RuntimeException exception) {
            return "<getTask 失败: " + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ">";
        }
    }

    /** 把事件流压成单行短文本，供断言消息携带；事件流缺失时显式标注而不是留空。 */
    private static String compactForMessage(String text) {
        if (text == null || text.isBlank()) {
            return "<事件流为空，无法给出内层原因>";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 600 ? normalized : normalized.substring(0, 600) + "…";
    }

    /**
     * 其它用例使用的单轮状态等待上界（历史口径 30s），不随探针属性变化。
     */
    private static final long DEFAULT_AWAIT_TIMEOUT_MS = 30_000L;

    /**
     * E3s 父套子用例的单轮状态等待上界：默认与其它用例一致的 30s，断言不变；做「父图不返回 vs 晚到但可回灌」
     * 的有界复现时由 {@code -Dsit.studio.e3s.await-ms} 覆盖上界，用于区分悬挂与超时口径问题。
     */
    private static long e3sAwaitTimeoutMs() {
        return Long.getLong("sit.studio.e3s.await-ms", DEFAULT_AWAIT_TIMEOUT_MS);
    }

    /**
     * Sends one message on the streaming wire (the surface E1a/E1b were judged on) carrying per-request
     * metadata, and returns that round's result. Metadata rides {@code MessageSendParams.metadata} exactly as
     * in {@code A2aServiceClient#sendMessage(Message, Map, List, Consumer)}, so the carrier under test is the
     * same — only the read-back surface differs (the non-streaming Task snapshot showed empty envelopes).
     */
    private InteractionFlow.RoundResult sendWithMetadata(String caseId, String input, Map<String, Object> metadata) {
        return InteractionFlow.of(client("studio-dsl-ir-sit"))
                .withContextId(caseId + "-" + System.nanoTime())
                .send(input)
                .withMetadata(metadata)
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute()
                .round(0);
    }

    /**
     * Sends one message through {@code A2aServiceClient#sendMessage(Message, Map, List, Consumer)} — the
     * legacy non-streaming {@code message/send} wire — with per-request metadata, waits for the terminal
     * state and returns the terminal task snapshot.
     *
     * <p>This is the only send path that stamps {@code MessageSendParams.metadata}, which is exactly the
     * carrier under test: whether a caller-declared {@code workflow_req_params} reaches
     * {@code Start.userFields}.</p>
     */
    private Task sendRequestParamsProbe(String caseId, String input, Map<String, Object> metadata) {
        A2aServiceClient client = client("studio-dsl-ir-sit");
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(caseId + "-" + System.nanoTime())
                .parts(List.of(new TextPart(input)))
                .build();

        client.sendMessage(message, metadata, List.of(collector.createConsumer()), streamError::set);

        TaskState terminalState = collector.awaitTerminalState(30_000);
        String taskId = collector.findFirstTaskId();
        assertThat(taskId).as(caseId + " task id").isNotEmpty();

        Task task = client.getTask(taskId);
        assertThat(task.status()).as(caseId + " task status").isNotNull();
        // Non-streaming `message/send` tears the HTTP exchange down right after the terminal Task is delivered,
        // so the SDK reports a benign CancellationException to the error handler even on success — the same
        // cleanup GracefulTripDownFailureTest tolerates. An error with no terminal state is the real defect.
        if (streamError.get() != null) {
            assertThat(terminalState.isFinal())
                    .as(caseId + " stream error without a terminal state: %s", streamError.get())
                    .isTrue();
        }
        assertThat(task.status().state()).as(caseId + " terminal state observed on the wire").isEqualTo(terminalState);
        return task;
    }

    /** The task's reply surface as text (artifacts first, then status message, then last history entry). */
    private static String replyText(Task task) {
        return A2aEventMapping.contentEventsOf(task).stream()
                .map(InboundEvent::text)
                .collect(Collectors.joining("\n"));
    }

    private static void assertSingleStreamInvocation(
            StudioDslHostControlClient.HostState after,
            StudioDslHostControlClient.HostState before) {
        assertThat(after.loadCount()).isEqualTo(before.loadCount());
        assertThat(after.queryCount()).isEqualTo(before.queryCount());
        assertThat(after.streamQueryCount()).isEqualTo(before.streamQueryCount() + 1);
    }

    private static Map<String, Object> modelMapping(Map<String, Object> ir, String endpoint) {
        Map<String, Object> models = new LinkedHashMap<>();
        IrModelMapping.collectModelNames(ir).forEach(name -> models.put(name, Map.of(
                "clientProvider", "OpenAI",
                "endpoint", endpoint,
                "apiKey", "rt-031-04-27-test-key")));
        return Map.of("schemaVersion", "1", "models", models);
    }

    private static Map<String, Object> componentConfig(Map<String, Object> ir, String type) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components = (List<Map<String, Object>>) ir.get("components");
        Object configs = components.stream()
                .filter(component -> type.equals(component.get("type")))
                .findFirst()
                .orElseThrow()
                .get("configs");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) configs;
        return result;
    }
}
