/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.studio_dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslRedisBackedE2EBase;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingPluginMcpEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslHostControlClient;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RT-031-04-27/E5real：插件节点能否按 IR 在默认配置下直连外部端点。
 *
 * <p>与 {@code StudioExportWorkflowE2EIT#e5RequiresControlledExternalCallExport} 的分工：那条用**合成语料**（插件未声明
 * {@code arguments}）证明"直调路径已通"；本用例用**画布正常产物形状**的语料证明"声明了 {@code arguments} 且接线字段与
 * 参数同名的插件节点"可直调。</p>
 *
 * <p><b>语料口径（2026-09-22 变更）</b>：门禁语料改为 {@code corpus/test_plugin_ir_dsl_legal.json}——由 studio 单测样例
 * {@code corpus/test_plugin_ir_dsl.json} 机械派生，只把插件节点 {@code configs.userFields.inputs} 与
 * {@code inputs.userFields} 改为与其 {@code configs.arguments} 同名（画布加插件节点时输入字段由插件
 * {@code input_schema} 生成，故"同名"才是正常形状）。原快照仍在本用例内作为**非门禁负向对照**留证。</p>
 *
 * <p>原快照的观测事实（节点接线 {@code {query,location}} 与 {@code arguments=[meetingRoom,start,end]} 完全不相交）
 * **不构成本用例判定**：这种"接线与插件参数不一致"的 IR 由 Studio 侧产出（插件参数变更后画布不同步/不校验，
 * 见 {@code 01-Design_File/20260922/studio-side/}）。行为随制品变化：
 * 合入 PR !665 之前，运行期按 {@code configs.arguments} 严格校验（FEAT-031 L2 明文的"完整复刻 Python
 * {@code flowapi.FlowApiEngine}"）⇒ 该形状下入参校验拦下、HTTP 未发出；
 * 合入 !665 之后，运行期在"两边完全不相交"时改按接线 {@code userFields.inputs} 组参 ⇒ 该形状下请求会实际发出。
 * 两种制品上的对照结果均只作留证；该口径变更已提请设计确认（docs 仓 ISSUE）。</p>
 *
 * <p>语料处理：文件**零改动**，只在内存里把插件 {@code configs.url}（占位 {@code https://fake.com}）指向受控端点，
 * 并注入 {@code configs.ioLogLevel=INFO}（只加日志，便于采集内层原因）。</p>
 */
@Tag("e2e")
@Tag("studio-dsl")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioRealExportPluginInvokeIT extends StudioDslRedisBackedE2EBase {

    /** 门禁语料：接线字段与插件声明的 {@code arguments} 同名（画布正常产物形状）。 */
    private static final String LEGAL_RESOURCE =
            "/testdata/studio_dsl/studio-export/corpus/test_plugin_ir_dsl_legal.json";
    /** 非门禁负向对照：studio 单测样例原件（接线与 {@code arguments} 完全不相交）。 */
    private static final String ORIGINAL_RESOURCE =
            "/testdata/studio_dsl/studio-export/corpus/test_plugin_ir_dsl.json";
    private static final String PLUGIN_NODE_ID = "node_1727403791211";
    private static final String PLUGIN_TYPE = "jiuwen.plugin";
    /** 快照里的占位端点；用例会把它换成受控端点，因此先断言它仍是占位值（快照漂移锚点）。 */
    private static final String PLACEHOLDER_URL = "https://fake.com";
    private static final long AWAIT_TIMEOUT_MS = 30_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @Story("RT-031-04-27/E5real: 插件节点按 IR 直调（接线字段与插件参数同名）")
    @DisplayName("E5real 插件节点按 IR 直接发起一次外部调用")
    void e5realRequiresPluginInvokeWithDeclaredApiParams() throws Exception {
        StudioDslHostControlClient control = new StudioDslHostControlClient(stack.baseUrl("studio-dsl-ir-sit"));
        try (RecordingPluginMcpEndpointFixture endpoint = new RecordingPluginMcpEndpointFixture()) {
            // 门禁：画布正常产物形状（接线字段 == configs.arguments）
            Map<String, Object> legal = irWithControlledEndpoint(LEGAL_RESOURCE, endpoint.pluginUrl(), true);
            StudioDslHostControlClient.HostState loaded = control.replace(
                    "plugin:legal-inputs", legal, Map.of(), Map.of());
            int legalBaseline = endpoint.requestCount();
            InteractionFlow.RoundResult round = invokeOnce("rt-031-04-27-e5real");
            List<RecordingPluginMcpEndpointFixture.RequestRecord> calls =
                    endpoint.requestsSince(legalBaseline);
            Allure.addAttachment("E5real 受控插件端点请求增量（主断言失败时也留证）",
                    "calls=" + calls.size() + "\n"
                            + calls.stream().map(call -> call.method() + " " + call.path()
                                    + " body=" + call.body())
                            .collect(Collectors.joining("\n")));
            Allure.addAttachment("E5real 非门禁增强证据：结束节点对外文本与插件绑定口径",
                    "generatedText=" + round.generatedText() + "\n"
                            + "note=结束节点绑定 ${node_1727403791211.userFields.data}（画布端口占位键），"
                            + "其取值属导出侧/绑定口径跟踪项（#284 收口转出），本用例不作判定");

            assertThat(calls)
                    .as("接线字段与插件参数同名的插件节点必须按 IR 直接发起调用；本轮受控端点请求增量=%s，"
                            + "状态=%s，文本=%s", calls.size(), round.taskState(), compact(round.generatedText()))
                    .hasSize(1);
            assertThat(calls.get(0).method()).as("IR 声明的 method").isEqualTo("POST");
            assertThat(calls.get(0).path()).startsWith("/plugin/weather");
            assertThat(calls.get(0).body().toString())
                    .as("请求体应携带插件声明的参数名")
                    .contains("meetingRoom").contains("start").contains("end");
            assertThat(round.taskState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertSingleStreamInvocation(control.status(), loaded);

            // 非门禁负向对照：原快照（接线与插件参数不相交）——只留证，不参与判定
            Map<String, Object> original = irWithControlledEndpoint(ORIGINAL_RESOURCE, endpoint.pluginUrl(), false);
            control.replace("plugin:original-snapshot", original, Map.of(), Map.of());
            int originalBaseline = endpoint.requestCount();
            InteractionFlow.RoundResult originalRound = invokeOnce("rt-031-04-27-e5real-orig");
            int originalCalls = endpoint.requestCount() - originalBaseline;
            Allure.addAttachment("E5real 非门禁负向对照：原快照（接线与 configs.arguments 不相交）",
                    "受控端点请求增量=" + originalCalls
                            + "（合入 PR !665 前为 0：入参校验在发出 HTTP 前拦下；"
                            + "!665 后运行期按接线组参，该对照会实际发出请求，故此处不作断言）\n"
                            + "状态=" + originalRound.taskState() + "\n"
                            + "note=该形状由 Studio 侧产出（插件参数变更后画布不同步/不校验），"
                            + "转 Studio 侧确认，见 01-Design_File/20260922/studio-side/；本用例不作判定");
        } finally {
            control.reset();
        }
    }

    /**
     * 装载语料并做**内存**补丁：插件 url → 受控端点、插件 ioLogLevel=INFO。
     *
     * <p>同时校验快照漂移锚点：插件节点声明了非空 {@code arguments}、原始 url 仍是占位值；
     * {@code assertSelfConsistent} 为真时再钉"接线字段集合是 {@code arguments} 的子集"这一语料语义锚点
     * （只对门禁语料生效；原快照作为负向对照不做该断言，属非门禁留证）。</p>
     *
     * @param resource 语料资源路径
     * @param pluginUrl 受控插件端点地址
     * @param assertSelfConsistent 是否断言该语料接线字段与 {@code arguments} 自洽
     * @return 可直接装载的 IR
     */
    private static Map<String, Object> irWithControlledEndpoint(
            String resource, String pluginUrl, boolean assertSelfConsistent) throws IOException {
        try (InputStream input = StudioRealExportPluginInvokeIT.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing resource: " + resource);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> ir = MAPPER.readValue(input, LinkedHashMap.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> components = (List<Map<String, Object>>) ir.get("components");
            Map<String, Object> plugin = components.stream()
                    .filter(component -> PLUGIN_TYPE.equals(component.get("type")))
                    .filter(component -> PLUGIN_NODE_ID.equals(component.get("id")))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "corpus must contain plugin node " + PLUGIN_NODE_ID));
            @SuppressWarnings("unchecked")
            Map<String, Object> configs = (Map<String, Object>) plugin.get("configs");
            assertThat(configs.get("arguments"))
                    .as("快照漂移锚点：插件节点必须仍声明非空 arguments")
                    .isInstanceOf(List.class);
            List<?> arguments = (List<?>) configs.get("arguments");
            assertThat(arguments).as("插件 API 参数声明").isNotEmpty();
            assertThat(configs.get("url"))
                    .as("快照漂移锚点：语料端点为占位值，用例把它换成受控端点")
                    .isEqualTo(PLACEHOLDER_URL);

            if (assertSelfConsistent) {
                Set<String> declared = arguments.stream()
                        .map(argument -> String.valueOf(((Map<?, ?>) argument).get("name")))
                        .collect(Collectors.toSet());
                @SuppressWarnings("unchecked")
                Map<String, Object> nodeInputs = (Map<String, Object>) plugin.get("inputs");
                @SuppressWarnings("unchecked")
                Map<String, Object> wired = (Map<String, Object>) nodeInputs.get("userFields");
                assertThat(declared)
                        .as("语料语义锚点：%s 的接线字段应是 arguments 的子集（同名才算自洽）", resource)
                        .containsAll(wired.keySet());
            }

            configs.put("url", pluginUrl);
            configs.put("ioLogLevel", "INFO");
            return ir;
        }
    }

    /** 经 A2A 流式面发一次调用（入口字段 location 走 metadata.inputs），等终态并返回该轮结果。 */
    private InteractionFlow.RoundResult invokeOnce(String caseId) {
        String location = "rt-031-04-27-e5real-location";
        return InteractionFlow.of(client("studio-dsl-ir-sit"))
                .withTimeoutMs(AWAIT_TIMEOUT_MS)
                .withContextId(caseId + "-" + System.nanoTime())
                .send(location)
                .withMetadata(Map.of("inputs", Map.of("location", location)))
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .execute()
                .round(0);
    }

    /** 单轮只允许一次流式调用（宿主计数增量断言）。 */
    private static void assertSingleStreamInvocation(
            StudioDslHostControlClient.HostState after, StudioDslHostControlClient.HostState before) {
        assertThat(after.loadCount()).isEqualTo(before.loadCount());
        assertThat(after.queryCount()).isEqualTo(before.queryCount());
        assertThat(after.streamQueryCount()).isEqualTo(before.streamQueryCount() + 1);
    }

    /** 断言消息里的短文本，不影响判定。 */
    private static String compact(String text) {
        if (text == null || text.isBlank()) {
            return "<空>";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "…";
    }
}
