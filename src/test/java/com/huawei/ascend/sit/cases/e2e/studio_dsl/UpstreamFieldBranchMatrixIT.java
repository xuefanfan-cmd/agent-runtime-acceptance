/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.studio_dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslRedisBackedE2EBase;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslBehaviorTraceFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslHostControlClient;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingOpenAiEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingPluginMcpEndpointFixture;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import com.openjiuwen.studio.dsl.ir.IrModelMapping;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * FEAT-031 覆盖矩阵（P0）：**判断节点条件读取“上游节点产出”时，是否走对边**。
 *
 * <p>背景（2026-09-21）：ISSUE #319 的修复只覆盖了 `jiuwen.llm*` 类型；客户现场暴露的是
 * `jiuwen.code`（数据处理节点）。本类把“上游类型”当作覆盖维度，逐类型验证同一语义：
 * 上游产出为非空 canary ⇒ 条件应为假 ⇒ 必须走 default 边。</p>
 *
 * <p>主断言＝**实际走到了哪条边的哨兵节点**（来自 A2A 事件流的 node_id 轨迹），
 * 不是“任务是否完成”这类低层 Oracle。</p>
 *
 * <p>语料由 `01-Design_File/20260921/feat031-coverage-matrix/tools/gen-upstream-branch-corpus.py` 生成；
 * 形状与 Studio 导出一致（上游节点声明空字符串 outputs 占位符）。</p>
 */
@Tag("e2e")
@Tag("studio-dsl")
@Tag("feat-031")
@Tag("coverage-matrix")
@Feature("FEAT-031: Studio DSL Java 承载")
class UpstreamFieldBranchMatrixIT extends StudioDslRedisBackedE2EBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CORPUS_DIR =
            "src/test/resources/testdata/studio_dsl/studio-export/corpus/";
    private static final String IF_SENTINEL = "node_if_sentinel";
    private static final String DEFAULT_SENTINEL = "node_default_sentinel";
    private static final long AWAIT_MS = 60_000L;
    /** 矩阵用例统一发送的调用文本；被上游节点引用（如 EI.ParamOutput 透出 query）时作为可定位 canary。 */
    private static final String SENT_TEXT = "coverage-matrix";
    /** 受控插件/MCP 端点固定返回体中的 canary（见 {@link RecordingPluginMcpEndpointFixture}）。 */
    private static final String UPSTREAM_CANARY = "UPSTREAM_CANARY";
    private static final String PLUGIN_CANARY = "plugin-canary";
    /** 值形态（D4）·含双引号串：语料里 code 节点返回的原文是 `A "B" C`。 */
    private static final String QUOTED_CANARY = "A \"B\" C";
    /** 宿主进程日志：SutStack 拉起 studio-dsl-ir-sit 后写在仓库 target 下，本仓库内可读。 */
    private static final Path HOST_LOG =
            Path.of("target/sit-logs/studio-dsl-ir-sit/run/run.log");
    /** 生成器产出的执行器登记索引（三态 + 值形态 + 连接形状）。 */
    private static final String INDEX_FILE = "feat031_mx_index.json";

    /** 受控外部端点（插件 /plugin/weather 与 MCP /mcp）；语料里硬编码该固定端口。 */
    private static RecordingPluginMcpEndpointFixture endpoint;
    /**
     * 受控模型端点。**带模型节点的语料（e10 / agent / 真实导出）必须带模型映射**，否则宿主
     * {@code /replace} 会以 {@code IrAssembleException: modelName '…' has no endpoint/apiKey mapping} 拒绝装配
     * （该行为本身由 {@code StudioExportWorkflowE2EIT#missingModelMappingFailsLoudlyAtLoadInsteadOfSilentAssembly} 看守）。
     */
    private static RecordingOpenAiEndpointFixture model;

    @BeforeAll
    static void startExternalEndpoints() throws IOException {
        endpoint = new RecordingPluginMcpEndpointFixture();
        model = new RecordingOpenAiEndpointFixture();
        // e10（createLlm）语料的条件是 `(${node_up.userFields.raw_output} != 'UPSTREAM_CANARY')` ⇒ 必须让模型
        // 正好回答 UPSTREAM_CANARY，条件才为假、走 default 边（与该语料的期望边一致）；
        // 其余带模型节点的执行器（LLMReAct / ParamOutput / 真实导出的 LLMComponent）只要求「非空」。
        model.responseCanary(UPSTREAM_CANARY);
    }

    @AfterAll
    static void stopExternalEndpoints() throws IOException {
        if (endpoint != null) {
            endpoint.close();
            endpoint = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
    }

    /** 按 IR 声明的模型名生成模型映射，全部指向受控模型端点（与 {@code StudioExportWorkflowE2EIT} 同做法）。 */
    private static Map<String, Object> modelMapping(Map<String, Object> ir) {
        Map<String, Object> models = new LinkedHashMap<>();
        IrModelMapping.collectModelNames(ir).forEach(name -> models.put(name, Map.of(
                "clientProvider", "OpenAI",
                "endpoint", model.baseUrl(),
                "apiKey", "rt-031-04-42-matrix-test-key")));
        return Map.of("schemaVersion", "1", "models", models);
    }

    @Test
    @Story("RT-031-04-32/mx-code-upstream: 判断读【jiuwen.code 数据处理节点】产出")
    @DisplayName("覆盖矩阵：上游为 jiuwen.code 时，判断必须按真实产出走 default 边")
    void codeUpstreamFieldDrivesDefaultBranch() throws Exception {
        assertEdge("feat031_mx_code_upstream_branch_ir.json", "rt-031-04-32-mx-code",
                DEFAULT_SENTINEL, IF_SENTINEL, "node_up", "raw_output", UPSTREAM_CANARY, -1);
    }

    @Test
    @Story("RT-031-04-36/mx-aggregation-upstream: 判断读【jiuwen.aggregation】产出")
    @DisplayName("覆盖矩阵：上游为 jiuwen.aggregation 时，判断必须按真实产出走 default 边")
    void aggregationUpstreamFieldDrivesDefaultBranch() throws Exception {
        assertEdge("feat031_mx_agg_upstream_branch_ir.json", "rt-031-04-36-mx-agg",
                DEFAULT_SENTINEL, IF_SENTINEL, "node_up", "group1", "~" + UPSTREAM_CANARY, -1);
    }

    @Test
    @Story("RT-031-04-34/mx-ei-qa-upstream: 判断读【EI.qa】产出")
    @DisplayName("覆盖矩阵：上游为 EI.qa 时（response 非空），判断必须走 IF 边")
    void qaUpstreamFieldDrivesIfBranch() throws Exception {
        assertEdge("feat031_mx_qa_upstream_branch_ir.json", "rt-031-04-34-mx-qa",
                IF_SENTINEL, DEFAULT_SENTINEL, "node_up", "response", "~OPTION_", -1);
    }

    @Test
    @Story("RT-031-04-37/mx-plugin-upstream: 判断读【jiuwen.plugin 插件节点】产出")
    @DisplayName("覆盖矩阵：上游为 jiuwen.plugin 时（受控端点返回非空），判断必须走 IF 边")
    void pluginUpstreamFieldDrivesIfBranch() throws Exception {
        assertThat(endpoint).as("受控外部端点必须已启动（固定端口 55207）").isNotNull();
        int baseline = endpoint.requestCount();
        // 引用字段＝插件回填契约键 `canary`（`configs.response` 声明字段兼受控返回体键）。
        // 2026-09-21 #284 开发裁定：`outputs.userFields` / `configs.userFields.outputs` 只是画布端口占位，
        // 运行期不会回填；此前读 `data`（画布占位键）恒为空 ⇒ 值级断言结构上不可能通过。
        assertEdge("feat031_mx_plugin_upstream_branch_ir.json", "rt-031-04-37-mx-plugin",
                IF_SENTINEL, DEFAULT_SENTINEL, "node_up", "canary", "~" + PLUGIN_CANARY, baseline);
    }

    @Test
    @Story("RT-031-04-38/mx-mcp-upstream: 判断读【jiuwen.mcp】产出")
    @DisplayName("覆盖矩阵：上游为 jiuwen.mcp 时（受控 MCP 工具返回非空），判断必须走 IF 边")
    void mcpUpstreamFieldDrivesIfBranch() throws Exception {
        assertThat(endpoint).as("受控外部端点必须已启动（固定端口 55207）").isNotNull();
        int baseline = endpoint.requestCount();
        // 引用字段＝MCP 回填契约键 `content`（`FlowMcpEngine` 把 tools/call 结果写成 content 列表 + isError，
        // 不写 raw_output/data）。`content` 是列表，宿主 I/O 采样按 `content=[…]` 渲染，逗号会截断取值，
        // 故值级只断言「非空」；"调用确实发生"由受控端点请求增量断言，"产出可见"由该非空值 + IF 边共同证明。
        assertEdge("feat031_mx_mcp_upstream_branch_ir.json", "rt-031-04-38-mx-mcp",
                IF_SENTINEL, DEFAULT_SENTINEL, "node_up", "content", "@nonempty", baseline);
    }

    @Test
    @Story("RT-031-04-39/mx-agent-upstream: 判断读【jiuwen.LLMReAct Agent 节点】产出")
    @DisplayName("覆盖矩阵：上游为 jiuwen.LLMReAct 时（模型返回非空），判断必须走 IF 边")
    void agentUpstreamFieldDrivesIfBranch() throws Exception {
        // 模型回答内容不确定，值级只断言「非空」；期望边由条件 `!= ''` 推出。
        assertEdge("feat031_mx_agent_upstream_branch_ir.json", "rt-031-04-39-mx-agent",
                IF_SENTINEL, DEFAULT_SENTINEL, "node_up", "output", "@nonempty", -1);
    }

    @Test
    @Story("RT-031-04-40/mx-paramoutput-upstream: 判断读【EI.ParamOutput 参数提取节点】产出")
    @DisplayName("覆盖矩阵：上游为 EI.ParamOutput 时（透出引用值），判断必须走 IF 边")
    void paramOutputUpstreamFieldDrivesIfBranch() throws Exception {
        // 该节点把引用值原样透出：引用的是 node_start.systemFields.query，即本轮发送文本。
        assertEdge("feat031_mx_paramoutput_upstream_branch_ir.json", "rt-031-04-40-mx-paramoutput",
                IF_SENTINEL, DEFAULT_SENTINEL, "node_up", "input_param1", SENT_TEXT, -1);
    }

    @Test
    @Story("RT-031-04-32/mx-code-empty-upstream: 值形态-空串（default 边可达性对照）")
    @DisplayName("覆盖矩阵（值形态）：上游产出为空串时，条件为假 ⇒ 必须走 default 边")
    void codeEmptyUpstreamFieldDrivesDefaultBranch() throws Exception {
        // 对照侧：证明 default 边可达，避免把「任何输入都走 default」误判成通过。
        assertEdge("feat031_mx_code_empty_upstream_branch_ir.json", "rt-031-04-32-mx-code-empty",
                DEFAULT_SENTINEL, IF_SENTINEL, "node_up", "raw_output", "", -1);
    }

    @Test
    @Story("RT-031-04-46/mx-value-quoted: 值形态-产出含双引号")
    @DisplayName("覆盖矩阵（值形态）：上游产出含双引号时，判断按非空走 IF 边，且引号不被吞")
    void quotedValueDrivesIfBranch() throws Exception {
        // 值形态（D4）：`!= ''` 为真 ⇒ IF；值级要求窗口里含 `A "B"`（引号既没被吞、也没截断后续内容）。
        // 换行形态故意不做：宿主 io-log 是单行渲染，换行会把行切断、值级证据不可靠（见增量设计 §4）。
        assertEdge("feat031_mx_value_quoted_ir.json", "rt-031-04-46-mx-value-quoted",
                IF_SENTINEL, DEFAULT_SENTINEL, "node_up", "raw_output", "~" + QUOTED_CANARY, -1);
    }

    @Test
    @Story("RT-031-04-47/mx-value-number: 值形态-产出为数字（字段声明仍是 string）")
    @DisplayName("覆盖矩阵（值形态）：上游产出数字 42 时，判断按非空走 IF 边，且值对下游可见")
    void numberValueDrivesIfBranch() throws Exception {
        // 值形态（D4）·非字符串：真实导出里该字段声明 type=string，运行期却是数字。
        // 探针（2026-09-22）已确认可装配、下游可见 `42`；值级只断言非空（渲染形态由宿主决定，不是设计契约）。
        assertEdge("feat031_mx_value_number_ir.json", "rt-031-04-47-mx-value-number",
                IF_SENTINEL, DEFAULT_SENTINEL, "node_up", "raw_output", "@nonempty", -1);
    }

    @Test
    @Story("RT-031-04-48/mx-value-object: 值形态-产出为对象（字段声明仍是 string）")
    @DisplayName("覆盖矩阵（值形态）：上游产出对象 {k:v} 时，判断按非空走 IF 边，且值对下游可见")
    void objectValueDrivesIfBranch() throws Exception {
        assertEdge("feat031_mx_value_object_ir.json", "rt-031-04-48-mx-value-object",
                IF_SENTINEL, DEFAULT_SENTINEL, "node_up", "raw_output", "@nonempty", -1);
    }

    @Test
    @Story("RT-031-04-49/mx-value-array: 值形态-产出为数组（字段声明仍是 string）")
    @DisplayName("覆盖矩阵（值形态）：上游产出数组 [a,b] 时，判断按非空走 IF 边，且值对下游可见")
    void arrayValueDrivesIfBranch() throws Exception {
        assertEdge("feat031_mx_value_array_ir.json", "rt-031-04-49-mx-value-array",
                IF_SENTINEL, DEFAULT_SENTINEL, "node_up", "raw_output", "@nonempty", -1);
    }

    @Test
    @Story("RT-031-04-10/mx-shape-elseif-chain: 连接形状-三分支链（if / else-if / default）")
    @DisplayName("覆盖矩阵（连接形状）：上游产出等于 canary 时，必须命中 if/else-if 链的第一支")
    void codeElseIfChainPicksMatchingBranch() throws Exception {
        // 形状维度：多条件链的求值顺序与选择。命中第一支、且不得落到第二支或 default。
        assertEdge("feat031_mx_code_elseif_chain_ir.json", "rt-031-04-10-mx-elseif-chain",
                "node_b1_sentinel", "node_default_sentinel", "node_up", "raw_output", UPSTREAM_CANARY, -1,
                "node_b2_sentinel");
    }

    @Test
    @Story("RT-031-04-50/mx-output-mixed-placeholder: 产出占位形态-部分空串 + 部分非空（ISSUE #390）")
    @DisplayName("覆盖矩阵（产出占位形态）：声明含空串+非空字面量时，节点真实产出必须可见（不得被冲成空）")
    void mixedOutputPlaceholderKeepsRealValueVisible() throws Exception {
        // #390：declared outputs = {"a":"", "b":"CONST"} 时，若不按字段剔除空串叶子，
        // Core 会按声明回填把真实值 a=1 冲成空，判断节点随即走错边（IF）。
        assertEdge("feat031_mx_output_mixed_placeholder_ir.json", "rt-031-04-50-mx-out-mixed",
                DEFAULT_SENTINEL, IF_SENTINEL, "node_up", "a", "1", -1);
    }

    @Test
    @Story("RT-031-04-51/mx-output-allempty-placeholder: 产出占位形态-全空串（#355/!661 对照，防回退）")
    @DisplayName("覆盖矩阵（产出占位形态）：声明全为空串占位时，节点真实产出必须可见")
    void allEmptyOutputPlaceholderKeepsRealValueVisible() throws Exception {
        assertEdge("feat031_mx_output_allempty_placeholder_ir.json", "rt-031-04-51-mx-out-allempty",
                DEFAULT_SENTINEL, IF_SENTINEL, "node_up", "a", "1", -1);
    }

    @Test
    @Story("RT-031-04-44/mx-shape-loop: 连接形状-循环体（内联 loopBody）")
    @DisplayName("覆盖矩阵（连接形状）：循环体内上游 code 的产出必须对判断可见，且循环真按调用方列表迭代")
    void loopBodyUpstreamFieldDrivesDefaultBranch() throws Exception {
        // 形状维度：上游产出对**其直接下游**可见，与该节点处在什么连接形状里无关。
        // 迭代列表由调用方经 metadata.inputs.questions 投递（数组循环；投递通道由 E3b 用例看守）。
        // 本例不能走 assertEdge：那条路径不带 metadata，数组循环会空转、体内节点一次都不会被调用。
        //
        // **判据边界（2026-09-22 源码定位后修正）**：循环体内部**不建边**——`IrWorkflowAssembler.wireConnections`
        // 对「两端都在 loopBody」的连接直接 return（该判断排在 branchId 分支之前），循环体由
        // `StudioLoopGroupAssembler.addBodyComponents` 按 loopBody 列表顺序登记为体组件 ⇒ 体内是**线性链**，
        // 分支只"算"不"路由"，所以"只允许调用被选中哨兵"的边级断言在此形状下**不成立**（原判据为测试侧缺陷）。
        // 本用例因此改为断言：① 真迭代；② 体内上游产出对判断可见（值级 + 分支决定）。
        Map<String, Object> ir = loadCorpus("feat031_mx_loop_body_upstream_branch_ir.json");
        String caseId = "rt-031-04-44-mx-shape-loop";
        List<String> loopItems = List.of(caseId + "-item-1", caseId + "-item-2");
        StudioDslHostControlClient control = new StudioDslHostControlClient(
                stack.baseUrl("studio-dsl-ir-sit"));
        try {
            control.replace("studio-export:" + caseId, ir, modelMapping(ir), Map.of());
            long before = Files.exists(HOST_LOG) ? Files.size(HOST_LOG) : 0L;
            InteractionFlow.RoundResult round = InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withTimeoutMs(AWAIT_MS)
                    .withContextId(caseId + "-" + System.nanoTime())
                    .send(SENT_TEXT)
                    .withMetadata(Map.of("inputs", Map.of("questions", loopItems)))
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .execute()
                    .round(0);

            assertThat(round.taskState()).as("%s 终态", caseId)
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);

            // 值级证据取自本用例窗口（按字节偏移切分，与 assertEdge 同源）。
            String window = readHostLogWindow(before);
            String produced = upstreamOutputValue(window, "node_up", "raw_output");
            assertThat(produced)
                    .as("%s 值级：循环体内 node_up.raw_output 必须对下游可见且等于 canary；实测=[%s]；"
                            + "被调用节点=%s", caseId, produced, calledNodes(window))
                    .isEqualTo(UPSTREAM_CANARY);

            // 主断言：循环真按调用方列表迭代（两轮各带自己的元素），体内分支按读到的产出做决定。
            assertThat(window)
                    .as("%s 必须按 metadata.inputs 的列表迭代（两轮）；窗口被调用节点=%s",
                            caseId, calledNodes(window))
                    .contains("arrLoopVar.item=" + loopItems.get(0))
                    .contains("arrLoopVar.item=" + loopItems.get(1));
            assertThat(window)
                    .as("%s 体内判断必须按读到的 canary 判成 default（条件 `!= UPSTREAM_CANARY` 为假）；"
                            + "窗口被调用节点=%s", caseId, calledNodes(window))
                    .contains("__branchId__=default");
        } finally {
            control.reset();
        }
    }

    @Test
    @Story("RT-031-04-45/mx-shape-nested-subflow: 连接形状-嵌套子流程（父套子）")
    @DisplayName("覆盖矩阵（连接形状）：上游是子流程节点的回显时，判断必须按真实回显走 default 边")
    void nestedSubflowResponseDrivesDefaultBranch() throws Exception {
        // 形状维度：上游产出对**其直接下游**可见，与该节点处在什么连接形状里无关。
        // 子流经宿主控制面 children 传入（键＝父 IR 的 configs.reference.path），与
        // StudioExportWorkflowE2EIT#e3sParentInvokesChildAndChildEchoReachesParentResponse 同法。
        Map<String, Object> parent = loadCorpus("feat031_mx_nested_subflow_parent_ir.json");
        Map<String, Object> child = loadCorpus("feat031_mx_nested_subflow_child_ir.json");
        String caseId = "rt-031-04-45-mx-shape-nested-subflow";
        String childRefPath = childReferencePath(parent);
        StudioDslHostControlClient control = new StudioDslHostControlClient(
                stack.baseUrl("studio-dsl-ir-sit"));
        try {
            control.replace("studio-export:" + caseId, parent, modelMapping(parent),
                    Map.of(childRefPath, child));
            long before = Files.exists(HOST_LOG) ? Files.size(HOST_LOG) : 0L;
            InteractionFlow.RoundResult round = InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withTimeoutMs(AWAIT_MS)
                    .withContextId(caseId + "-" + System.nanoTime())
                    .send(SENT_TEXT)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .execute()
                    .round(0);

            assertThat(round.taskState()).as("%s 终态", caseId)
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);
            // 子流回显必须经父 End（result=${node_subworkflow.responseContent}）回到响应文本。
            assertThat(round.generatedText())
                    .as("%s 父响应必须含子流回显 canary（子流产出经 workflowComposite 的 responseContent 回灌）",
                            caseId)
                    .contains(UPSTREAM_CANARY);

            // 值级证据取自本用例窗口（按字节偏移切分，与 assertEdge 同源）。
            String window = readHostLogWindow(before);
            String produced = upstreamOutputValue(window, "node_subworkflow", "responseContent");
            if (produced != null) {
                assertThat(produced)
                        .as("%s 值级：子流回显必须对父级下游可见且等于 canary；实测=[%s]；被调用节点=%s",
                                caseId, produced, calledNodes(window))
                        .contains(UPSTREAM_CANARY);
            }

            assertThat(window)
                    .as("%s 必须走 %s 边；不得走 %s 边；被调用节点=%s",
                            caseId, DEFAULT_SENTINEL, IF_SENTINEL, calledNodes(window))
                    .contains("Begin to call node [" + DEFAULT_SENTINEL + "]")
                    .doesNotContain("Begin to call node [" + IF_SENTINEL + "]");
        } finally {
            control.reset();
        }
    }

    @Test
    @Story("RT-031-04-47/mx-shape-nested-subflow-blocking: 连接形状-嵌套子流程 · 阻塞线制（message/send）")
    @DisplayName("覆盖矩阵（线制维度）：同一嵌套子流程形状走阻塞 message/send 时，子流回显仍必须对父级下游可见")
    void nestedSubflowResponseDrivesDefaultBranchOnBlockingWire() throws Exception {
        // 为什么单独有线制维度：流式线制下子流帧会"上冒"成响应文本，即使父级字段绑定拿不到值，
        // 响应正文里仍有 canary ⇒ 故障被文本掩盖（见 #395）。阻塞线制没有这条上冒通道，
        // 父 End 的 ${node_subworkflow.responseContent} 取空就会直接反映为"响应正文没有回显"，
        // 所以本用例的主断言放在响应正文上（阻塞线制不产出 [studio-dsl][ioLog]，拿不到节点级产出袋）。
        Map<String, Object> parent = loadCorpus("feat031_mx_nested_subflow_parent_ir.json");
        Map<String, Object> child = loadCorpus("feat031_mx_nested_subflow_child_ir.json");
        String caseId = "rt-031-04-47-mx-nested-subflow-blocking";
        String childRefPath = childReferencePath(parent);
        StudioDslHostControlClient control = new StudioDslHostControlClient(
                stack.baseUrl("studio-dsl-ir-sit"));
        try {
            control.replace("studio-export:" + caseId, parent, modelMapping(parent),
                    Map.of(childRefPath, child));
            long before = Files.exists(HOST_LOG) ? Files.size(HOST_LOG) : 0L;
            InteractionFlow.RoundResult round = InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .protocol(MessageProtocol.A2A_SYNC)
                    .withTimeoutMs(AWAIT_MS)
                    .withContextId(caseId + "-" + System.nanoTime())
                    .send(SENT_TEXT)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .execute()
                    .round(0);

            assertThat(round.taskState()).as("%s 终态", caseId)
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);
            // 主断言：阻塞线制没有"帧上冒"通道 ⇒ 正文里出现 canary 只能来自字段绑定。
            assertThat(String.valueOf(round.generatedText()))
                    .as("%s 阻塞线制：父响应必须含子流回显 canary（该值只能经 ${node_subworkflow.responseContent} "
                            + "字段绑定到达父 End；为空表示子流结果没有回填到父级声明字段）", caseId)
                    .contains(UPSTREAM_CANARY);

            // 增强证据：边级轨迹（宿主进程日志里的哨兵调用），与本类其它用例同源。
            String window = readHostLogWindow(before);
            assertThat(window)
                    .as("%s 必须走 %s 边；不得走 %s 边；被调用节点=%s",
                            caseId, DEFAULT_SENTINEL, IF_SENTINEL, calledNodes(window))
                    .contains("Begin to call node [" + DEFAULT_SENTINEL + "]")
                    .doesNotContain("Begin to call node [" + IF_SENTINEL + "]");
        } finally {
            control.reset();
        }
    }

    /** 取父 IR 中 {@code jiuwen.workflowComposite} 节点的 {@code configs.reference.path}（即 children 键）。 */
    private static String childReferencePath(Map<String, Object> ir) {
        Object raw = ir.get("components");
        if (raw instanceof List<?> components) {
            for (Object item : components) {
                if (!(item instanceof Map<?, ?> component)
                        || !"jiuwen.workflowComposite".equals(component.get("type"))) {
                    continue;
                }
                if (component.get("configs") instanceof Map<?, ?> configs
                        && configs.get("reference") instanceof Map<?, ?> reference) {
                    return String.valueOf(reference.get("path"));
                }
            }
        }
        throw new IllegalStateException("parent IR has no jiuwen.workflowComposite node: " + ir.get("workflowId"));
    }

    @Test
    @Story("RT-031-04-26/mx-real-export-code-branch: 真实导出 test_system_flow_ir 的判断读 jiuwen.code 产出")
    @DisplayName("覆盖矩阵（真实导出）：code 节点产出 key1=hi ⇒ 必须走 if 边；产出值必须是 hi")
    void realExportCorpusBranchReadsCodeOutput() throws Exception {
        // 真实 Studio 导出语料直接进运行期（改造原则 3）；语料文件不改，只在运行时注入 ioLogLevel 拿值级证据。
        // 判别力说明：该语料条件 = `(is_empty(key0)) && (length(key1) < 5)`，**值丢失时仍可能判真**，
        // 所以本条的判别力主要在值级断言（key1 必须等于真实导出代码里的常量 "hi"），边级只证明 IF 支可达。
        Map<String, Object> ir = withIoLogLevel(loadCorpus("test_system_flow_ir.json"), "INFO");
        String caseId = "rt-031-04-26-mx-real-export";
        StudioDslHostControlClient control = new StudioDslHostControlClient(
                stack.baseUrl("studio-dsl-ir-sit"));
        try {
            control.replace("studio-export:" + caseId, ir, modelMapping(ir), Map.of());
            long before = Files.exists(HOST_LOG) ? Files.size(HOST_LOG) : 0L;
            // 终态不强判：IF 支下游是真实导出的 LLMComponent，模型映射缺失会在下游失败，与本条要判的两件事无关。
            InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withTimeoutMs(AWAIT_MS)
                    .withContextId(caseId + "-" + System.nanoTime())
                    .send(SENT_TEXT)
                    .mayReachState(TaskState.TASK_STATE_COMPLETED)
                    .execute()
                    .round(0);

            // 值级证据取自**本用例的宿主日志窗口**（按字节偏移切分，天然按用例隔离）；
            // 不再用 control.status().ioTraceSample() —— 那是宿主全局样本，一旦超过 SAMPLE_LIMIT 就冻结，
            // 整类同轮下会读到前面用例的记录（codeEmpty 曾因此读到 codeUpstream 的 UPSTREAM_CANARY）。
            String window = readHostLogWindow(before);
            String key1 = upstreamOutputValue(window, "node_1741657511603", "key1");
            if (key1 != null) {
                assertThat(key1)
                        .as("%s 值级：真实导出 code 节点产出 key1 必须等于代码常量 hi（产出被冲掉时会为空）；轨迹实测=[%s]",
                                caseId, key1)
                        .isEqualTo("hi");
            }
        String key0 = upstreamOutputValue(window, "node_1741657511603", "key0");
        if (key0 != null) {
            assertThat(key0)
                    .as("%s 值级：真实导出 code 节点产出 key0 的源码值为 None，轨迹里应为空；实测=[%s]",
                            caseId, key0)
                    // 宿主把 Python None 渲染成字面量 null（ioLog 行里是 `key0=null`），空串与 "null" 都算"无值"。
                    .isIn("", "null");
        }

            assertThat(window)
                    .as("%s 必须走 if 支（node_llm）；宿主窗口=%s", caseId, calledNodes(window))
                    .contains("Begin to call node [node_llm]")
                    .doesNotContain("Begin to call node [node_1741657826722]");
        } finally {
            control.reset();
        }
    }

    /**
     * 参数化守卫（`RT-031-04-42/placeholder-guard`）的数据源：读生成器产出的索引，
     * 取所有"已生成语料"的执行器——**新增执行器只要进了登记表就被自动扫到**，不需要改测试代码。
     */
    static Stream<Arguments> guardedMatrixCases() throws Exception {
        Map<String, Object> index = loadCorpus(INDEX_FILE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> guarded = (List<Map<String, Object>>) index.get("guarded");
        assertThat(guarded).as("索引里必须有 guarded 段").isNotNull();
        return guarded.stream()
                .filter(g -> g.get("corpus") != null && !String.valueOf(g.get("corpus")).isEmpty())
                .filter(g -> g.get("expected_edge") != null && !String.valueOf(g.get("expected_edge")).isEmpty())
                .map(g -> Arguments.of(
                        String.valueOf(g.get("corpus")),
                        String.valueOf(g.get("executor")),
                        String.valueOf(g.get("upstream_node_id")),
                        String.valueOf(g.get("referenced_field")),
                        String.valueOf(g.get("expected_edge"))));
    }

    @ParameterizedTest(name = "placeholder-guard[{1}]")
    @MethodSource("guardedMatrixCases")
    @Story("RT-031-04-42/placeholder-guard: 产出可见性与「空 outputs 占位符」无关（参数化全执行器扫描）")
    @DisplayName("守卫：同一执行器在「带空 outputs 占位符」与「不带占位符」两种语料形态下都必须走对边")
    void placeholderGuardKeepsUpstreamOutputVisible(String corpusFile, String executor, String upstreamNode,
                                                    String referencedField, String expectedEdge)
            throws Exception {
        String field = referencedField.substring(referencedField.lastIndexOf('.') + 1);
        Map<String, Object> base = loadCorpus(corpusFile);
        String caseId = "rt-031-04-42-guard-" + executor;

        RunOutcome withPlaceholder = runVariant(base, caseId + "-placeholder", upstreamNode, field);
        assertThat(withPlaceholder.window())
                .as("%s（%s，带空 outputs 占位符）必须走 %s 边；产出实测=[%s]；窗口=%s",
                        caseId, executor, expectedEdge, String.valueOf(withPlaceholder.produced()),
                        calledNodes(withPlaceholder.window()))
                .contains("Begin to call node [" + expectedEdge + "]");

        Map<String, Object> stripped = withoutOutputsPlaceholder(base, upstreamNode);
        RunOutcome noPlaceholder = runVariant(stripped, caseId + "-noplaceholder", upstreamNode, field);
        assertThat(noPlaceholder.window())
                .as("%s（%s，**不带**空 outputs 占位符）也必须走 %s 边——产出可见性不得依赖占位符；产出实测=[%s]；窗口=%s",
                        caseId, executor, expectedEdge, String.valueOf(noPlaceholder.produced()),
                        calledNodes(noPlaceholder.window()))
                .contains("Begin to call node [" + expectedEdge + "]");
    }

    /** 一次执行的观测结果：宿主日志窗口 + 上游产出实测值。 */
    private record RunOutcome(String window, String produced) {}

    /** 跑一次矩阵用例并回收观测面（供参数化守卫复用；不断言，只采集）。 */
    private RunOutcome runVariant(Map<String, Object> ir, String caseId, String upstreamNode,
                                  String outputField) throws Exception {
        StudioDslHostControlClient control = new StudioDslHostControlClient(
                stack.baseUrl("studio-dsl-ir-sit"));
        try {
            control.replace("studio-export:" + caseId, ir, modelMapping(ir), Map.of());
            long before = Files.exists(HOST_LOG) ? Files.size(HOST_LOG) : 0L;
            InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withTimeoutMs(AWAIT_MS)
                    .withContextId(caseId + "-" + System.nanoTime())
                    .send(SENT_TEXT)
                    .mayReachState(TaskState.TASK_STATE_COMPLETED)
                    .execute()
                    .round(0);
            // 值级证据取自本用例的宿主日志窗口（按字节偏移切分，按用例隔离），不用宿主全局 io 采样。
            String window = readHostLogWindow(before);
            return new RunOutcome(window, upstreamOutputValue(window, upstreamNode, outputField));
        } finally {
            control.reset();
        }
    }

    /**
     * 复制语料并**去掉上游节点的 {@code outputs} 占位符**（保留 {@code configs.userFields.outputs} 字段声明）。
     *
     * <p>这是 {@code RT-031-04-42/placeholder-guard} 的对照侧：同一执行器、同一条件表达式，
     * 只改"运行前是否带空 outputs 占位符"，走边结果不应因此改变。</p>
     */
    private static Map<String, Object> withoutOutputsPlaceholder(Map<String, Object> ir, String nodeId) {
        Map<String, Object> copy = MAPPER.convertValue(ir, new TypeReference<Map<String, Object>>() { });
        Object comps = copy.get("components");
        if (comps instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && nodeId.equals(String.valueOf(map.get("id")))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> component = (Map<String, Object>) map;
                    component.remove("outputs");
                }
            }
        }
        return copy;
    }

    /** 复制语料并在工作流级注入横切 {@code ioLogLevel}（不修改共享语料对象；与 io-log 用例同做法）。 */
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

    /**
     * 覆盖矩阵主断言（两次判据）：
     * <ol>
     *   <li><b>值级</b>：宿主 I/O 采样里上游节点 {@code upstreamNode} 的产出字段 {@code outputField}
     *       必须满足 {@code expectedValue}（{@code ~x}＝包含 x，其余＝精确相等）；
     *       采样里没有该节点记录时跳过（该增强证据缺失不判 FAIL，按 Skill 口径只记未验证）。</li>
     *   <li><b>边级</b>：实际被调用的哨兵节点必须是 {@code expected}，且不得是 {@code forbidden}。</li>
     * </ol>
     *
     * @param expectedValue 期望产出值；{@code ~前缀}表示"包含"，否则精确相等
     * @param requestBaseline 受控端点调用基线（&lt;0 表示本用例不依赖外部端点）
     * @param alsoForbidden 额外禁止出现的哨兵节点（多分支链用例用；不得走的支路都要列出来）
     */
    private void assertEdge(String corpusFile, String caseId, String expected, String forbidden,
                            String upstreamNode, String outputField, String expectedValue,
                            int requestBaseline, String... alsoForbidden) throws Exception {
        Map<String, Object> ir = loadCorpus(corpusFile);
        StudioDslHostControlClient control = new StudioDslHostControlClient(
                stack.baseUrl("studio-dsl-ir-sit"));
        try {
            control.replace("studio-export:" + caseId, ir, modelMapping(ir), Map.of());
            long before = Files.exists(HOST_LOG) ? Files.size(HOST_LOG) : 0L;
            A2aServiceClient client = client("studio-dsl-ir-sit");
            InteractionFlow.RoundResult round = InteractionFlow.of(client)
                    .withTimeoutMs(AWAIT_MS)
                    .withContextId(caseId + "-" + System.nanoTime())
                    .send(SENT_TEXT)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .execute()
                    .round(0);

            assertThat(round.taskState()).as("%s 终态", caseId)
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);

            int endpointCalls = -1;
            if (requestBaseline >= 0) {
                endpointCalls = endpoint.requestsSince(requestBaseline).size();
                assertThat(endpointCalls)
                        .as("%s 前置：受控外部端点必须被调用（观测面＝夹具请求增量）；为 0 表示前置未满足——"
                                + "先查语料/出向网络策略，本用例结论应为 INCONCLUSIVE，不得据此判产品", caseId)
                        .isGreaterThan(0);
            }

            // 值级证据取自本用例的宿主日志窗口（按字节偏移切分，按用例隔离），不用宿主全局 io 采样。
            String window = readHostLogWindow(before);
            String produced = upstreamOutputValue(window, upstreamNode, outputField);
            if (produced != null) {
                if ("@nonempty".equals(expectedValue)) {
                    assertThat(produced)
                            .as("%s 值级：%s.%s 的产出必须非空；宿主轨迹实测=[%s]；受控端点调用=%d 次",
                                    caseId, upstreamNode, outputField, produced, endpointCalls)
                            .isNotBlank();
                } else if (expectedValue.startsWith("~")) {
                    assertThat(produced)
                            .as("%s 值级：%s.%s 的产出应为「包含 %s」；宿主轨迹实测=[%s]；受控端点调用=%d 次",
                                    caseId, upstreamNode, outputField, expectedValue.substring(1), produced,
                                    endpointCalls)
                            .contains(expectedValue.substring(1));
                } else {
                    assertThat(produced)
                            .as("%s 值级：%s.%s 的产出应为 [%s]；宿主轨迹实测=[%s]；受控端点调用=%d 次",
                                    caseId, upstreamNode, outputField, expectedValue, produced, endpointCalls)
                            .isEqualTo(expectedValue);
                }
            }

            assertThat(window)
                    .as("%s 必须走 %s 边；上游产出实测=[%s]；受控端点调用=%d 次；宿主窗口=%s",
                            caseId, expected, String.valueOf(produced), endpointCalls, calledNodes(window))
                    .contains("Begin to call node [" + expected + "]")
                    .doesNotContain("Begin to call node [" + forbidden + "]");
            for (String extra : alsoForbidden) {
                assertThat(window)
                        .as("%s 不得走 %s 边；宿主窗口=%s", caseId, extra, calledNodes(window))
                        .doesNotContain("Begin to call node [" + extra + "]");
            }
        } finally {
            control.reset();
        }
    }

    /**
     * 从**本用例的宿主日志窗口**里取某节点某字段的产出值（取最后一条 {@code outputs=…} 记录）。
     *
     * <p>输入文本来自 {@link #readHostLogWindow(long)}（按字节偏移切分，按用例隔离）；
     * 宿主 `[studio-dsl][ioLog]` 行形如
     * {@code … [studio-dsl][ioLog] ioLogLevel=INFO type=tracer_workflow componentId=node_up invokeId=node_up inputs={…} outputs={userFields={…}}}；
     * 只有 {@code outputs=null} 的行被跳过，因此"节点被调用但产出为空"会被如实读成空串。</p>
     *
     * <p>**不要**改用 {@code StudioDslHostControlClient.status().ioTraceSample()}：那是宿主全局样本，
     * 超过 SAMPLE_LIMIT 后冻结不再增长，整类同轮时会读到前面用例的记录（假值）。</p>
     *
     * @return 字段值；窗口缺失或无该节点/字段时返回 {@code null}
     */
    static String upstreamOutputValue(String sample, String nodeId, String field) {
        if (sample == null || sample.isEmpty()) {
            return null;
        }
        // 采样里同一节点的记录可能有多条（outputs=null 的"开始"记录、带产出的"结束"记录），
        // 取最后一条真正带 outputs 的。产出渲染有两种形状：
        //   outputs={userFields={data=x}}   （多数节点）
        //   outputs={group1=x}              （如聚合节点直接返回平铺 map）
        // 因此这里不假设嵌套层级，直接在 outputs= 之后的片段里找 "<field>="。
        String value = null;
        java.util.regex.Matcher lines = java.util.regex.Pattern
                .compile("componentId=" + java.util.regex.Pattern.quote(nodeId) + " [^\\n]*")
                .matcher(sample);
        while (lines.find()) {
            String record = lines.group();
            int outputsAt = record.indexOf("outputs=");
            if (outputsAt < 0) {
                continue;
            }
            String after = record.substring(outputsAt + "outputs=".length());
            if (after.startsWith("null")) {
                continue;
            }
            java.util.regex.Matcher fieldMatcher = java.util.regex.Pattern
                    .compile("(?:^|[{,\\s])" + java.util.regex.Pattern.quote(field) + "=([^,}]*)")
                    .matcher(after);
            if (fieldMatcher.find()) {
                value = fieldMatcher.group(1).trim();
            }
        }
        return value;
    }

    /** 读取宿主日志自 {@code offset} 之后的内容（一次执行的窗口）。 */
    private static String readHostLogWindow(long offset) throws Exception {
        byte[] all = Files.readAllBytes(HOST_LOG);
        if (offset > all.length) {
            offset = 0;
        }
        return new String(all, (int) offset, all.length - (int) offset, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 从窗口里抽出实际被调用的节点清单（失败信息里直接可见，便于归因）。 */
    private static List<String> calledNodes(String window) {
        return java.util.regex.Pattern.compile("Begin to call node \\[([^\\]]+)\\]")
                .matcher(window)
                .results()
                .map(m -> m.group(1))
                .distinct()
                .toList();
    }

    private static Map<String, Object> loadCorpus(String fileName) throws Exception {
        Path path = Path.of(CORPUS_DIR + fileName);
        assertThat(Files.exists(path)).as("矩阵语料存在：%s", path).isTrue();
        return MAPPER.readValue(Files.readString(path), new TypeReference<>() { });
    }
}
