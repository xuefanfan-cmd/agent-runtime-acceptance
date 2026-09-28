/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.studio_dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslRedisBackedE2EBase;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingOpenAiEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslHostControlClient;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.openjiuwen.studio.dsl.ir.IrModelMapping;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * FEAT-031 · **P1 生成式长链**（`RT-031-04-43/property-long-chain`）。
 *
 * <p>语料由 `01-Design_File/20260922/feat031-property-chain/tools/gen-property-chain-corpus.py`
 * 按**固定种子**生成（随机链长、随机形状：线性链 / 双泳道扇入），一律带 `configs.ioLogLevel=INFO`。
 * 目的：把"**上游产出对其直接下游可见**"这条不变量在随机形状上再钉一遍——手写语料只覆盖了少数形状，
 * 而 #355 那一类"产出被空占位符静默冲掉"的故障与形状无关。</p>
 *
 * <p>主断言（四条不变量，逐条由窗口证据支撑）：① 每个被调用前缀节点的产出值都出现在本用例窗口里；
 * ② 链尾产出被判断节点看到并按 `!= ''` 判成 IF；③ 终态 COMPLETED 且响应文本非空；④ 窗口无 ERROR、
 * 且不得走到 default 哨兵。语料登记：`corpus/feat031_pc_index.json`。</p>
 */
@Tag("e2e")
@Tag("studio-dsl")
@Tag("feat-031")
@Tag("coverage-matrix")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioDslPropertyChainIT extends StudioDslRedisBackedE2EBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CORPUS_DIR =
            "src/test/resources/testdata/studio_dsl/studio-export/corpus/";
    private static final String INDEX_FILE = "feat031_pc_index.json";
    private static final Path HOST_LOG = Path.of("target/sit-logs/studio-dsl-ir-sit/run/run.log");
    private static final long AWAIT_MS = 60_000L;

    private static RecordingOpenAiEndpointFixture model;

    @BeforeAll
    static void startModel() throws IOException {
        model = new RecordingOpenAiEndpointFixture();
    }

    @AfterAll
    static void stopModel() throws IOException {
        if (model != null) {
            model.close();
            model = null;
        }
    }

    static Stream<Map<String, Object>> chains() throws Exception {
        Map<String, Object> index = load(INDEX_FILE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chains = (List<Map<String, Object>>) index.get("chains");
        assertThat(chains).as("生成式长链索引必须有 chains").isNotNull().isNotEmpty();
        return chains.stream();
    }

    @ParameterizedTest(name = "property-chain[{0}]")
    @MethodSource("chains")
    @Story("RT-031-04-43/property-long-chain: 生成式长链的不变量（随机形状、固定种子）")
    @DisplayName("属性测试：随机前缀（链长 2–5 / 扇入）后，链尾产出必须对判断可见且响应非空")
    void propertyChainKeepsProducedValuesVisible(Map<String, Object> chain) throws Exception {
        String corpusFile = String.valueOf(chain.get("file")).replace("corpus/", "");
        String caseId = "rt-031-04-43-" + String.valueOf(chain.get("file")).replaceAll("[^0-9a-zA-Z]+", "-");
        String upstream = String.valueOf(chain.get("upstream_node_id"));
        String valueField = String.valueOf(chain.get("value_field"));
        @SuppressWarnings("unchecked")
        List<String> nodes = (List<String>) chain.get("prefix_node_ids");
        @SuppressWarnings("unchecked")
        List<String> canaries = (List<String>) chain.get("prefix_canaries");

        Map<String, Object> ir = load(corpusFile);
        StudioDslHostControlClient control = new StudioDslHostControlClient(
                stack.baseUrl("studio-dsl-ir-sit"));
        try {
            control.replace("studio-export:" + caseId, ir, modelMapping(ir), Map.of());
            long before = Files.exists(HOST_LOG) ? Files.size(HOST_LOG) : 0L;
            InteractionFlow.RoundResult round = InteractionFlow.of(client("studio-dsl-ir-sit"))
                    .withTimeoutMs(AWAIT_MS)
                    .withContextId(caseId + "-" + System.nanoTime())
                    .send("coverage-matrix")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .execute()
                    .round(0);
            String window = readWindow(before);

            assertThat(round.taskState()).as("%s（seed=%s shape=%s）终态", caseId, chain.get("seed"),
                    chain.get("shape")).isEqualTo(TaskState.TASK_STATE_COMPLETED);

            // 不变量①：每个被调用前缀节点的产出值都在窗口里出现（值级，按节点取值）
            for (int i = 0; i < nodes.size(); i++) {
                String nodeId = nodes.get(i);
                if (i >= canaries.size()) {
                    continue;
                }
                String canary = canaries.get(i);
                assertThat(window)
                        .as("%s 不变量①：节点 %s 的产出值 [%s] 必须在本用例窗口可见；被调用节点=%s",
                                caseId, nodeId, canary, calledNodes(window))
                        .contains(canary);
            }

            // 不变量②：链尾产出被判断节点看到并判成 IF
            String tail = outputValue(window, upstream, valueField);
            assertThat(tail).as("%s 不变量②：链尾 %s.%s 必须对判断可见；被调用节点=%s",
                    caseId, upstream, valueField, calledNodes(window)).isNotNull();
            assertThat(window)
                    .as("%s 不变量②：判断必须判成 IF 边；被调用节点=%s", caseId, calledNodes(window))
                    .contains("__branchId__=node_pc_branch-if")
                    .doesNotContain("Begin to call node [node_pc_default_sentinel]");

            // 不变量③：响应文本非空（结束节点回灌哨兵产出）
            assertThat(round.generatedText()).as("%s 不变量③：响应文本必须非空", caseId).isNotBlank();

            // 不变量④：窗口无 ERROR
            assertThat(window).as("%s 不变量④：窗口内不得出现 ERROR", caseId).doesNotContain(" ERROR ");
        } finally {
            control.reset();
        }
    }

    private static Map<String, Object> modelMapping(Map<String, Object> ir) {
        Map<String, Object> models = new LinkedHashMap<>();
        IrModelMapping.collectModelNames(ir).forEach(name -> models.put(name, Map.of(
                "clientProvider", "OpenAI",
                "endpoint", model.baseUrl(),
                "apiKey", "rt-031-04-43-property-test-key")));
        return Map.of("schemaVersion", "1", "models", models);
    }

    private static Map<String, Object> load(String fileName) throws Exception {
        return MAPPER.readValue(Files.readString(Path.of(CORPUS_DIR + fileName)), new TypeReference<>() { });
    }

    private static String readWindow(long offset) throws Exception {
        byte[] all = Files.readAllBytes(HOST_LOG);
        int from = offset > all.length ? 0 : (int) offset;
        return new String(all, from, all.length - from, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String outputValue(String window, String nodeId, String field) {
        String value = null;
        Matcher lines = Pattern.compile("componentId=" + Pattern.quote(nodeId) + " [^\\n]*").matcher(window);
        while (lines.find()) {
            String record = lines.group();
            int at = record.indexOf("outputs=");
            if (at < 0) {
                continue;
            }
            String after = record.substring(at + "outputs=".length());
            if (after.startsWith("null")) {
                continue;
            }
            Matcher fieldMatcher = Pattern.compile("(?:^|[{,\\s])" + Pattern.quote(field) + "=([^,}]*)")
                    .matcher(after);
            if (fieldMatcher.find()) {
                value = fieldMatcher.group(1).trim();
            }
        }
        return value;
    }

    private static List<String> calledNodes(String window) {
        List<String> nodes = new ArrayList<>();
        Matcher matcher = Pattern.compile("Begin to call node \\[([^\\]]+)\\]").matcher(window);
        while (matcher.find()) {
            String node = matcher.group(1);
            if (!nodes.contains(node)) {
                nodes.add(node);
            }
        }
        return nodes;
    }
}
