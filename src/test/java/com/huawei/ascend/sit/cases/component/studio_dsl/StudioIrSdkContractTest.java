/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.studio_dsl.JulLogCaptureFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.OfflineAccessAuditFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingOpenAiEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslTestResources;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioExportCorpusFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.WorkflowAssemblyProbe;
import com.openjiuwen.core.graph.Executable;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.internal.NodeSession;
import com.openjiuwen.core.session.internal.WorkflowSession;
import com.openjiuwen.core.session.state.InMemoryState;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.studio.dsl.ir.IrAssembleContext;
import com.openjiuwen.studio.dsl.ir.IrAssembleTopologySnapshot;
import com.openjiuwen.studio.dsl.ir.IrAssembleException;
import com.openjiuwen.studio.dsl.ir.IrAssembleResult;
import com.openjiuwen.studio.dsl.ir.IrDeclarationFidelity;
import com.openjiuwen.studio.dsl.ir.IrModelMapping;
import com.openjiuwen.studio.dsl.ir.IrStudioMemoryRefs;
import com.openjiuwen.studio.dsl.ir.LazySubWorkflowComponent;
import com.openjiuwen.studio.dsl.ir.SkippedSubWorkflowComponent;
import com.openjiuwen.studio.dsl.ir.StudioIrSdk;
import com.openjiuwen.studio.dsl.nodes.FlowBranchNode;
import com.openjiuwen.studio.dsl.nodes.FlowLlmNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Tag("component")
@Tag("contract")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioIrSdkContractTest extends StudioDslContractTestBase {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Node types whose IR {@code configs} are a Studio <em>declaration</em> schema, not configs the assembled
     * node holds. Confirmed by the agent-solution L2 owner on 2026-09-14 for {@code jiuwen.exception}: the
     * component keeps only its identity triple ({@code FlowExceptionConfig} = nodeId/nodeName/nodeType,
     * mirroring Python {@code ExceptionInfo}) and the declared {@code error_*} fields are resolved from the
     * runtime {@code inputs.userFields} ({@code FlowExceptionEngine#extractUserFields}). The fidelity
     * expectation for these types is therefore the empty held view, not a raw IR clone.
     */
    private static final Set<String> IDENTITY_ONLY_NODE_TYPES = Set.of("jiuwen.exception");
    private static final String REDACTED = "<redacted>";
    private static final Set<String> SENSITIVE_CONFIG_KEYS = Set.of(
            "apikey", "api_key", "api-key", "password", "access_token", "authorization",
            "secret", "client_secret", "token", "x-auth-token");
    private static final Set<String> NON_SENSITIVE_CONFIG_KEYS = Set.of("max_tokens");

    /**
     * Node-id prefix of the assembler-inserted parallel join barrier ({@code ParallelJoinPlan}, PR !632).
     * A <em>marker-less</em> fan-in ({@code A -> J} where J is fed by several lanes and the declaration
     * carries no {@code parallelBranchId}) is assembled as {@code A -> _parallel_done__* -> J} so that J
     * waits for every lane. The declaration-fidelity expectation therefore normalizes exactly that shape.
     */
    private static final String PARALLEL_DONE_PREFIX = "_parallel_done__";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Map、JSON 和文件入口返回完整装配对象")
    @Story("RT-031-04-06: JSON IR 内容入口")
    void loadMapJsonAndFileEntries() throws Exception {
        String json = StudioDslTestResources.text("minimal-start-end.json");
        Path file = StudioDslTestResources.copy("minimal-start-end.json", tempDir.resolve("workflow.json"));

        Workflow fromMap = StudioIrSdk.loadWorkflow(StudioDslTestResources.map("minimal-start-end.json"));
        Workflow fromJson = StudioIrSdk.loadWorkflowJson(json);
        Workflow fromFile = StudioIrSdk.loadWorkflowFile(file);
        IrAssembleResult result = StudioIrSdk.loadResultJson(json);

        Map<String, Workflow> entries = new LinkedHashMap<>();
        entries.put("map", fromMap);
        entries.put("json", fromJson);
        entries.put("file", fromFile);
        entries.put("result-json", result.workflow());

        assertSoftly(softly -> {
            entries.forEach((entry, workflow) -> {
                softly.assertThat(workflow).as(entry + " workflow").isNotNull();
                softly.assertThat(workflow.getCard().getId())
                        .as(entry + " workflowId")
                        .isEqualTo("wf-minimal");
                softly.assertThat(workflow.getCard().getName())
                        .as(entry + " workflowName")
                        .isEqualTo("minimal contract");
                softly.assertThat(workflow.getCard().getDescription())
                        .as(entry + " description")
                        .isEqualTo("FEAT-031 direct SDK contract input");
                softly.assertThat(workflow.getCard().getVersion())
                        .as(entry + " workflowVersion")
                        .isEqualTo("1");

                WorkflowAssemblyProbe.Snapshot snapshot = WorkflowAssemblyProbe.inspect(workflow);
                softly.assertThat(snapshot.nodes().keySet())
                        .as(entry + " components")
                        .containsExactlyInAnyOrder("node_start", "node_end");
                softly.assertThat(snapshot.startNodes())
                        .as(entry + " start nodes")
                        .containsExactly("node_start");
                softly.assertThat(snapshot.endNodes())
                        .as(entry + " end nodes")
                        .containsExactly("node_end");
                softly.assertThat(snapshot.edges())
                        .as(entry + " connections")
                        .containsEntry("node_start", List.of("node_end"));
            });

            softly.assertThat(result.crossCutting().workflowId()).isEqualTo("wf-minimal");
            softly.assertThat(result.crossCutting().workflowIoLogLevel()).isEqualTo("INFO");
            softly.assertThat(result.crossCutting().workflowStreamEnabled()).isFalse();
            softly.assertThat(result.modelMapping().toSessionModelMap()).isEmpty();
        });
    }

    @Test
    @DisplayName("非法 JSON、版本和未知类型返回可区分错误码")
    @Story("RT-031-04-07: schema、类型与版本非法")
    void rejectInvalidSchemaVersionAndType() {
        assertCode("IR_INVALID", () -> StudioIrSdk.loadWorkflowJson("{"));
        Map<String, Object> missingField = new LinkedHashMap<>(
                StudioDslTestResources.map("minimal-start-end.json"));
        missingField.remove("schemaVersion");
        assertCode("IR_REQUIRED_FIELD", () -> StudioIrSdk.loadWorkflow(missingField));

        Map<String, Object> typeMismatch = new LinkedHashMap<>(
                StudioDslTestResources.map("minimal-start-end.json"));
        typeMismatch.put("components", "not-a-list");
        assertCode("IR_TYPE_MISMATCH", () -> StudioIrSdk.loadWorkflow(typeMismatch));
        assertCode("IR_SCHEMA_VERSION_UNSUPPORTED", () ->
                StudioIrSdk.loadWorkflow(StudioDslTestResources.map("unsupported-version.json")));
        assertCode("IR_COMPONENT_UNKNOWN", () ->
                StudioIrSdk.loadWorkflow(StudioDslTestResources.map("unknown-component.json")));
    }

    @Test
    @DisplayName("断连与模型映射缺失在外部调用前失败")
    @Story("RT-031-04-08: 断连与模型映射缺失")
    void rejectBrokenConnectionAndMissingModelMapping() {
        assertThatThrownBy(() -> StudioIrSdk.loadWorkflow(
                StudioDslTestResources.map("broken-connection.json")))
                .isInstanceOfSatisfying(IrAssembleException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("IR_CONNECTION_BROKEN");
                    assertThat(exception.getMessage()).contains("node_missing");
                });
        assertThatThrownBy(() -> StudioIrSdk.loadWorkflow(
                StudioDslTestResources.map("llm-missing-mapping.json")))
                .isInstanceOfSatisfying(IrAssembleException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("IR_MODEL_MAPPING_MISSING");
                    assertThat(exception.getMessage()).contains("model-a");
                });
    }

    @Test
    @DisplayName("真实 Studio 导出节点的逐类型关键配置字段保持不变")
    @Story("RT-031-04-09: 当前节点目录逐类型关键配置保真")
    void preserveEveryCurrentNodeCriticalConfig() {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();
        // Corpus accounting口径 (user-confirmed 2026-09-14): a catalog row whose real export does not exist yet
        // is recorded as blocked and stays OUT of the verdict, instead of failing the whole testcase; the rows
        // that do have a real export are still judged, so RT-031-04-09 lands as `partial` with a precise scope.
        // Currently blocked: KnowledgeRepo (real export ships as jiuwen.plugin) and Card (no real export at all).
        List<String> blockedCorpusRows = manifest.catalog().stream()
                .filter(entry -> !entry.corpusStatus().startsWith("ready"))
                .map(entry -> entry.node() + " [" + entry.corpusStatus() + "]")
                .toList();

        List<String> blockedArtifacts = new ArrayList<>();
        List<String> assembledArtifacts = new ArrayList<>();
        List<String> missingProjections = new ArrayList<>();
        List<String> declarationDiffs = new ArrayList<>();
        List<String> barrierMediatedEdges = new ArrayList<>();
        List<String> redactionViolations = new ArrayList<>();
        List<String> overRedactions = new ArrayList<>();

        for (StudioExportCorpusFixture.CatalogEntry entry : manifest.catalog()) {
            if (entry.artifact() == null) {
                continue;
            }
            Map<String, Object> ir = StudioExportCorpusFixture.artifactIr(manifest, entry.artifact());
            IrAssembleResult assembled;
            try {
                assembled = StudioExportCorpusFixture.assembleArtifact(manifest, entry.artifact());
            } catch (RuntimeException exception) {
                // Blocked by existing product issues (e.g. #250/#251/#252/#237): recorded, never counted
                // as an RT-031-04-09 failure.
                blockedArtifacts.add(entry.artifact() + " [" + exception.getClass().getSimpleName() + ": "
                        + exception.getMessage() + "]");
                continue;
            }
            assembledArtifacts.add(entry.artifact());

            // Corpus precondition only: declared critical fields must exist in the raw IR. This is NOT
            // the fidelity oracle — the verdict below uses the assembly projection, never IR self-compare.
            for (String componentId : entry.componentIds()) {
                Map<String, Object> component = StudioExportCorpusFixture.component(manifest, entry, componentId);
                assertThat(component).as(componentId).containsKeys("id", "type", "configs");
                assertThat(irConfigs(component).keySet()).as(componentId)
                        .containsAll(entry.criticalFieldsFor(componentId));
            }

            IrAssembleTopologySnapshot snapshot = IrAssembleTopologySnapshot.from(assembled, ir);
            // Expectation side must go through the same parse/normalization the assembler applies
            // (L2 FEAT-Func-031 … load-assemble.md:132 / :918-919: "IR → 同型解析期望 → 与装配记录的节点投影
            // 比较"; "期望侧经同型解析/规范化后再比对"). Three normalizations are needed (see the helper).
            DeclarationExpectation expectation = declarationExpectation(ir, snapshot);
            for (String realization : expectation.parallelBarrierRealizations()) {
                barrierMediatedEdges.add(entry.artifact() + ": " + realization);
            }
            for (String diff : IrDeclarationFidelity.diffAgainstIr(snapshot, expectation.ir())) {
                declarationDiffs.add(entry.artifact() + ": " + diff);
            }

            Map<String, Map<String, Object>> nodeConfigs = snapshot.nodeConfigs();
            for (String componentId : entry.componentIds()) {
                Map<String, Object> projected = nodeConfigs.get(componentId);
                if (projected == null) {
                    missingProjections.add(entry.artifact() + "#" + componentId);
                    continue;
                }
                collectRedactionFindings(
                        entry.artifact() + "#" + componentId,
                        irConfigs(StudioExportCorpusFixture.component(manifest, entry, componentId)),
                        projected,
                        redactionViolations,
                        overRedactions);
            }
        }

        Assumptions.assumeTrue(!assembledArtifacts.isEmpty(),
                () -> "no real Studio export artifact could be assembled: " + blockedArtifacts);

        // Evidence of the partial scope: which catalog rows have no ready real export plus what was actually
        // judged. Rows are assembled whenever an artifact exists — a non-ready row no longer fails the whole
        // testcase. 2026-09-19 update: Card is the remaining row without a real export (validate rejects
        // type Card, see StudioNodeCatalogCompatibilityTest#cardExportChainRemainsOpenAtTheFrozenStudioCommit);
        // the KnowledgeRepo row now has both a real jiuwen.knowledgeRetrieval export
        // (feat031-l2s7-knowledge-repo) and a legacy exported-as-plugin artifact, judged separately.
        Allure.addAttachment("RT-031-04-09 non-ready corpus rows", blockedCorpusRows.toString());
        Allure.addAttachment("RT-031-04-09 judged artifacts", assembledArtifacts.toString());
        Allure.addAttachment("RT-031-04-09 identity-only node types (owner-confirmed)",
                IDENTITY_ONLY_NODE_TYPES.toString());
        Allure.addAttachment("RT-031-04-09 declared fan-in edges assembled through a parallel join barrier",
                barrierMediatedEdges.toString());

        assertSoftly(softly -> {
            softly.assertThat(missingProjections)
                    .as("assembled components missing from the nodeConfigs projection "
                                    + "(assembled=%s, assembly-blocked=%s, non-ready-corpus=%s)",
                            assembledArtifacts, blockedArtifacts, blockedCorpusRows)
                    .isEmpty();
            softly.assertThat(declarationDiffs)
                    .as("IR declaration vs assembled node config differences "
                                    + "(assembled=%s, assembly-blocked=%s, non-ready-corpus=%s)",
                            assembledArtifacts, blockedArtifacts, blockedCorpusRows)
                    .isEmpty();
            softly.assertThat(redactionViolations)
                    .as("sensitive keys that were not projected as " + REDACTED).isEmpty();
            softly.assertThat(overRedactions)
                    .as("non-sensitive keys redacted by mistake").isEmpty();
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> irConfigs(Map<String, Object> component) {
        Object configs = component.get("configs");
        return configs instanceof Map ? (Map<String, Object>) configs : Map.of();
    }

    /**
     * The declaration-fidelity expectation: a copy of the IR the diff compares against the assembled projection,
     * after the normalizations the assembler itself applies first. Every other declared field is passed through
     * untouched — the strict "IR declaration must survive assembly" rule still applies.
     *
     * <ol>
     *   <li>{@link #IDENTITY_ONLY_NODE_TYPES} components carry an empty {@code configs} map, so the diff expects
     *       the node's real (empty) held view.</li>
     *   <li>Studio legacy memory refs are rewritten exactly as the assembler does it
     *       ({@code IrWorkflowAssembler} → {@link IrStudioMemoryRefs#convertInPlace}, self-described as Python
     *       {@code IRConverter._convert_global_variable_refs} parity: {@code ${node_start.memory.X}} →
     *       {@code ${MEMORY_VARIABLE.X}}). Without this the diff compares a legacy declaration against the
     *       normalized held view and reports a difference for every node that carries such a ref — measured on
     *       {@code param-extraction}: 13 of 13 such nodes, with identical key sets and reference text as the only
     *       delta (probe: 01-Design_File/20260920/feat031-g1-closeout/RT-031-04-09-字段级探针-20260920.md).
     *       Reusing the product's public rewriter keeps the expectation from re-implementing Python semantics.</li>
     *   <li>Marker-less parallel fan-in ({@code ParallelJoinPlan}, PR !632): a declared {@code A -> J} edge whose
     *       target J is fed by at least two declared non-branch edges and which carries no
     *       {@code parallelBranchId} is assembled as {@code A -> _parallel_done__* -> J}. The expectation is
     *       therefore compared as {@code A -> barrier}, which still asserts "A reaches the convergence point
     *       through the join barrier"; the {@code barrier -> J} leg is required to build the rewrite at all
     *       (see {@link #parallelJoinBarrier}), so an edge that really disappeared stays a diff. Measured on
     *       {@code agent-parallel}: 2 declared fan-in edges, 1 per lane, both lanes converge into
     *       {@code jiuwen.aggregation} (evidence: 01-Design_File/20260920/feat031-g1-closeout/
     *       evidence/rt09-b-20260920/, plus issue306-verify/ for the same mechanism).</li>
     * </ol>
     */
    private static DeclarationExpectation declarationExpectation(
            Map<String, Object> ir, IrAssembleTopologySnapshot snapshot) {
        Object rawComponents = ir.get("components");
        if (!(rawComponents instanceof List<?> components)) {
            return new DeclarationExpectation(ir, List.of());
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : components) {
            if (!(item instanceof Map<?, ?> component)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            component.forEach((key, value) -> copy.put(String.valueOf(key), value));
            if (IDENTITY_ONLY_NODE_TYPES.contains(String.valueOf(copy.get("type")))) {
                copy.put("configs", Map.of());
            }
            normalized.add(copy);
        }
        Map<String, Object> result = new LinkedHashMap<>(ir);
        result.put("components", normalized);
        // COW rewrite: only touches "components", and only strings carrying the legacy memory ref.
        IrStudioMemoryRefs.convertInPlace(result);
        List<String> realizations = new ArrayList<>();
        result.put("connections", barrierMediatedConnections(result, snapshot, realizations));
        return new DeclarationExpectation(result, realizations);
    }

    /** IR passed to {@link IrDeclarationFidelity#diffAgainstIr} plus the edges the assembler realized via a barrier. */
    private record DeclarationExpectation(Map<String, Object> ir, List<String> parallelBarrierRealizations) {
    }

    /**
     * Rewrites the declared marker-less fan-in edges into the assembled shape
     * ({@code source -> _parallel_done__*}); every other connection is handed over unchanged, so a genuinely
     * missing edge still produces a diff. Only the two legs of the barrier route are normalized — the declared
     * target node and its private configs remain under the strict fidelity rule.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> barrierMediatedConnections(
            Map<String, Object> expectationIr,
            IrAssembleTopologySnapshot snapshot,
            List<String> realizations) {
        if (!(expectationIr.get("connections") instanceof List<?> rawConnections)) {
            return List.of();
        }
        Set<String> assembledEdges = edgeKeys(snapshot.edges());
        assembledEdges.addAll(edgeKeys(snapshot.streamEdges()));
        Map<String, Integer> declaredFanIn = new LinkedHashMap<>();
        List<Map<String, Object>> connections = new ArrayList<>();
        for (Object item : rawConnections) {
            if (!(item instanceof Map<?, ?> connection)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            connection.forEach((key, value) -> copy.put(String.valueOf(key), value));
            connections.add(copy);
            if (isParallelJoinCandidate(copy)) {
                declaredFanIn.merge(targetComponentId(copy), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> normalized = new ArrayList<>(connections.size());
        for (Map<String, Object> connection : connections) {
            if (!isParallelJoinCandidate(connection)) {
                normalized.add(connection);
                continue;
            }
            String source = sourceComponentId(connection);
            String target = targetComponentId(connection);
            String declared = source + "->" + target;
            // Fan-in of a single source is a plain edge: only an actual lane convergence is barrier-mediated.
            if (assembledEdges.contains(declared) || declaredFanIn.getOrDefault(target, 0) < 2) {
                normalized.add(connection);
                continue;
            }
            String barrier = parallelJoinBarrier(snapshot, assembledEdges, source, target);
            if (barrier == null) {
                normalized.add(connection);
                continue;
            }
            Map<String, Object> rewritten = new LinkedHashMap<>(connection);
            Map<String, Object> targetSpec = asMap(connection.get("target"));
            targetSpec.put("componentId", barrier);
            rewritten.put("target", targetSpec);
            normalized.add(rewritten);
            realizations.add(declared + " assembled as " + source + "->" + barrier + "->" + target);
        }
        return normalized;
    }

    /** Declared connections the fidelity diff itself judges: plain edges, no branch id, no parallel marker. */
    private static boolean isParallelJoinCandidate(Map<String, Object> connection) {
        Map<String, Object> source = asMap(connection.get("source"));
        Map<String, Object> target = asMap(connection.get("target"));
        return !text(source.get("componentId")).isEmpty()
                && !text(target.get("componentId")).isEmpty()
                && text(source.get("branchId")).isEmpty()
                && text(target.get("parallelBranchId")).isEmpty();
    }

    private static String sourceComponentId(Map<String, Object> connection) {
        return text(asMap(connection.get("source")).get("componentId"));
    }

    private static String targetComponentId(Map<String, Object> connection) {
        return text(asMap(connection.get("target")).get("componentId"));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * Returns the assembled join barrier that carries {@code source} into {@code target}, or {@code null} when
     * the declared edge was not realized that way (then the original expectation stays and the diff reports it).
     */
    private static String parallelJoinBarrier(
            IrAssembleTopologySnapshot snapshot, Set<String> assembledEdges, String source, String target) {
        for (String candidate : snapshot.componentIds()) {
            if (!candidate.startsWith(PARALLEL_DONE_PREFIX)) {
                continue;
            }
            if (assembledEdges.contains(source + "->" + candidate)
                    && assembledEdges.contains(candidate + "->" + target)) {
                return candidate;
            }
        }
        return null;
    }

    private static Set<String> edgeKeys(List<List<String>> edges) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (List<String> edge : edges) {
            if (edge != null && edge.size() >= 2) {
                keys.add(edge.get(0) + "->" + edge.get(1));
            }
        }
        return keys;
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, nested) -> copy.put(String.valueOf(key), nested));
        return copy;
    }

    private static void collectRedactionFindings(
            String componentPath,
            Map<String, Object> irConfigs,
            Map<String, Object> projected,
            List<String> redactionViolations,
            List<String> overRedactions) {
        collectRedactionFindings(
                componentPath, "", irConfigs, projected, redactionViolations, overRedactions);
    }

    private static void collectRedactionFindings(
            String componentPath,
            String path,
            Map<String, Object> irConfigs,
            Map<String, Object> projected,
            List<String> redactionViolations,
            List<String> overRedactions) {
        for (Map.Entry<String, Object> entry : irConfigs.entrySet()) {
            String key = entry.getKey();
            String keyPath = path.isEmpty() ? key : path + "." + key;
            Object raw = entry.getValue();
            Object actual = projected.get(key);
            String normalized = key.toLowerCase(java.util.Locale.ROOT);
            if (SENSITIVE_CONFIG_KEYS.contains(normalized)) {
                if (!REDACTED.equals(actual)) {
                    redactionViolations.add(componentPath + " " + keyPath + "=" + actual);
                }
                continue;
            }
            if (NON_SENSITIVE_CONFIG_KEYS.contains(normalized) && REDACTED.equals(actual)) {
                overRedactions.add(componentPath + " " + keyPath);
            }
            if (raw instanceof Map<?, ?> nestedRaw && actual instanceof Map<?, ?> nestedProjected) {
                Map<String, Object> nestedIr = new LinkedHashMap<>();
                nestedRaw.forEach((nestedKey, nestedValue) -> nestedIr.put(String.valueOf(nestedKey), nestedValue));
                Map<String, Object> nestedActual = new LinkedHashMap<>();
                nestedProjected.forEach((nestedKey, nestedValue) -> nestedActual.put(String.valueOf(nestedKey), nestedValue));
                collectRedactionFindings(
                        componentPath, keyPath, nestedIr, nestedActual, redactionViolations, overRedactions);
            }
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    @DisplayName("公共装配结果保留普通、条件、并行、流式连线和 end 节点")
    @Story("RT-031-04-10: 连线、条件、并行与 end 装配")
    void assembleDeclaredConnectionsThroughPublicSdk() {
        IrAssembleResult result;
        String previousDrawable = System.getProperty("WORKFLOW_DRAWABLE");
        try {
            System.setProperty("WORKFLOW_DRAWABLE", "true");
            result = StudioIrSdk.loadResult(
                    topologyMatrixIr(),
                    IrAssembleContext.builder()
                            .workflowId("wf-topology-matrix")
                            .modelMapping(IrModelMapping.filledPlaceholders(
                                    Set.of("topology-model"), "https://model.invalid/v1", "test-key"))
                            .build());
        } finally {
            if (previousDrawable == null) {
                System.clearProperty("WORKFLOW_DRAWABLE");
            } else {
                System.setProperty("WORKFLOW_DRAWABLE", previousDrawable);
            }
        }
        WorkflowAssemblyProbe.Snapshot snapshot = WorkflowAssemblyProbe.inspect(result.workflow());

        assertThat(snapshot.startNodes()).containsExactly("node_start");
        assertThat(snapshot.endNodes()).containsExactly("node_end");
        assertThat(snapshot.edges()).containsEntry("node_start", List.of("node_branch"));
        assertThat(snapshot.branchTargets().get("node_branch"))
                .containsExactlyInAnyOrder("node_if", "node_default");
        assertThat(snapshot.edges()).containsEntry("node_if", List.of("node_fork"));
        assertThat(snapshot.edges()).containsEntry("node_default", List.of("node_fork"));
        assertThat(snapshot.edges()).containsEntry("node_fork", List.of("node_llm", "node_code"));
        assertThat(snapshot.nodes().keySet())
                .anyMatch(nodeId -> nodeId.startsWith("_parallel_done__p1__node_end__"));
        assertThat(snapshot.streamEdges().getOrDefault("node_llm", List.of()))
                .singleElement()
                .satisfies(target -> assertThat(target).startsWith("_parallel_done__p1__node_end__"));
        assertThat(snapshot.javaTypes().get("node_branch")).isEqualTo(FlowBranchNode.class.getName());
    }

    @Test
    @DisplayName("子工作流按公开类型区分 Lazy 与缺失跳过并记录 WARN")
    @Story("RT-031-04-11: 子工作流独立单元")
    void keepParentAssemblyWhenChildIsMissing() {
        Map<String, Object> parent = StudioDslTestResources.map("parent-missing-child.json");
        IrAssembleContext missingContext = IrAssembleContext.builder()
                .workflowId("wf-parent")
                .childIrLoader(path -> Map.of())
                .build();
        try (JulLogCaptureFixture logs = new JulLogCaptureFixture(SkippedSubWorkflowComponent.class)) {
            WorkflowAssemblyProbe.Snapshot missing = WorkflowAssemblyProbe.inspect(
                    StudioIrSdk.loadWorkflow(parent, missingContext));
            Executable<?, ?> skipped = missing.nodes().get("node_child");
            assertThat(skipped).isInstanceOf(SkippedSubWorkflowComponent.class);
            assertThat(((SkippedSubWorkflowComponent) skipped).referencePath())
                    .isEqualTo("workflow/ir/wf-child.json");
            assertThat(missing.edges())
                    .containsEntry("node_start", List.of("node_child"))
                    .containsEntry("node_child", List.of("node_end"));
            assertThat(logs.messages()).singleElement().asString()
                    .contains("wf-parent", "node_child", "workflow/ir/wf-child.json", "skipped");
        }

        IrAssembleContext presentContext = IrAssembleContext.builder()
                .workflowId("wf-parent")
                .childIrLoader(path -> StudioDslTestResources.map("minimal-start-end.json"))
                .build();
        WorkflowAssemblyProbe.Snapshot present = WorkflowAssemblyProbe.inspect(
                StudioIrSdk.loadWorkflow(parent, presentContext));
        assertThat(present.nodes().get("node_child")).isInstanceOf(LazySubWorkflowComponent.class);
    }

    @Test
    @DisplayName("横切声明和模型映射通过 IrAssembleResult 暴露")
    @Story("RT-031-04-12: 横切配置与模型映射触达（第一批注入切片）")
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void exposeCrossCuttingAndModelMapping() throws Exception {
        String property = "studio.dsl.outbound.allowPrivate";
        String previous = System.getProperty(property);
        System.setProperty(property, "true");
        try (RecordingOpenAiEndpointFixture endpoint = new RecordingOpenAiEndpointFixture()) {
            Map<String, Object> ir = StudioDslTestResources.map("llm-missing-mapping.json");
            @SuppressWarnings("unchecked")
            Map<String, Object> llmConfigs = (Map<String, Object>) ((Map<String, Object>)
                    ((List<?>) ir.get("components")).get(1)).get("configs");
            llmConfigs.put("templateContent", List.of(Map.of("role", "user", "content", "{{query}}")));
            llmConfigs.put("responseFormat", Map.of("type", "text"));
            llmConfigs.put("enableHistory", false);
            llmConfigs.put("userFields", Map.of(
                    "inputs", List.of(Map.of("id", "query", "type", "string")),
                    "outputs", List.of(Map.of("id", "answer", "type", "string"))));

            IrModelMapping mapping = IrModelMapping.filledPlaceholders(
                    Set.of("model-a"), endpoint.baseUrl(), "test-key");
            IrAssembleResult result = StudioIrSdk.loadResult(
                    ir,
                    IrAssembleContext.builder().workflowId("wf-model").modelMapping(mapping).build());
            FlowLlmNode llm = (FlowLlmNode) WorkflowAssemblyProbe.inspect(result.workflow())
                    .nodes().get("node_llm");
            WorkflowSession workflowSession = new WorkflowSession(
                    "wf-model", null, null,
                    InMemoryState.create(null, Map.of(
                            "llm_extra_configs", Map.of(
                                    "model_map", result.modelMapping().toSessionModelMap())),
                            null, null, null),
                    null);
            NodeSessionApi nodeSession = new NodeSessionApi(new NodeSession(workflowSession, "node_llm"), true);
            int baseline = endpoint.requestCount();
            String inputCanary = "rt-031-04-12-input-canary";

            llm.stream(Map.of("userFields", Map.of("query", inputCanary)), nodeSession, null)
                    .forEachRemaining(ignored -> { });

            assertThat(result.crossCutting()).isNotNull();
            assertThat(result.modelMapping().get("model-a")).isPresent();
            assertThat(result.modelMapping().toSessionModelMap()).containsKey("model-a");
            assertThat(endpoint.requestsSince(baseline)).singleElement().satisfies(request -> {
                assertThat(request.path()).endsWith("/v1/chat/completions");
                assertThat(request.header("Authorization")).isEqualTo("Bearer test-key");
                assertThat(request.body().path("model").asText()).isEqualTo("model-a");
                assertThat(request.body().path("stream").asBoolean()).isTrue();
                assertThat(request.body().path("messages").toString()).contains(inputCanary);
            });
        } finally {
            restoreProperty(property, previous);
        }
    }

    @Test
    @DisplayName("四类装配失败返回精确错误且不产生半装配结果")
    @Story("RT-031-04-14: 装配失败原子性")
    void neverReturnPartialAssemblyOnFailure() throws Exception {
        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        cases.put("IR_COMPONENT_UNKNOWN", StudioDslTestResources.map("unknown-component.json"));
        cases.put("IR_CONNECTION_BROKEN", StudioDslTestResources.map("broken-connection.json"));
        Map<String, Object> requiredFieldMissing = deepCopy(
                StudioDslTestResources.map("minimal-start-end.json"));
        firstComponent(requiredFieldMissing).remove("type");
        cases.put("IR_REQUIRED_FIELD", requiredFieldMissing);
        cases.put("IR_MODEL_MAPPING_MISSING", StudioDslTestResources.map("llm-missing-mapping.json"));

        List<Path> filesBefore = regularFiles(tempDir);
        for (Map.Entry<String, Map<String, Object>> failureCase : cases.entrySet()) {
            String inputBefore = MAPPER.writeValueAsString(failureCase.getValue());
            AtomicReference<Workflow> returned = new AtomicReference<>();
            assertThatThrownBy(() -> returned.set(StudioIrSdk.loadWorkflow(failureCase.getValue())))
                    .as(failureCase.getKey())
                    .isInstanceOfSatisfying(IrAssembleException.class, exception ->
                            assertThat(exception.code()).isEqualTo(failureCase.getKey()));
            assertThat(returned).as(failureCase.getKey() + " partial result").hasValue(null);
            assertThat(MAPPER.writeValueAsString(failureCase.getValue()))
                    .as(failureCase.getKey() + " input mutation")
                    .isEqualTo(inputBefore);
        }
        assertThat(regularFiles(tempDir)).isEqualTo(filesBefore);
    }

    @Test
    @DisplayName("bundle 可从 manifest key 加载并拒绝路径逃逸")
    @Story("RT-031-04-05: 离线 bundle 结构与访问隔离（第一批文件边界切片）")
    void loadBundleAndRejectPathEscape() throws Exception {
        Path bundle = tempDir.resolve("bundle");
        prepareBundle(bundle, "ir/wf-minimal.json");
        Path outside = StudioDslTestResources.copy(
                "minimal-start-end-v2.json", tempDir.resolve("outside-canary.json"));
        Path nestedEscape = tempDir.resolve("nested-escape-bundle");
        prepareBundle(nestedEscape, "ir/../../outside-canary.json");
        Path absoluteEscape = tempDir.resolve("absolute-escape-bundle");
        prepareBundle(absoluteEscape, outside.toAbsolutePath().normalize().toString());

        try (RecordingOpenAiEndpointFixture endpoint = new RecordingOpenAiEndpointFixture()) {
            Map<String, java.util.function.IntSupplier> counters = Map.of(
                    "network", endpoint::requestCount);
            OfflineAccessAuditFixture.AuditSnapshot before =
                    OfflineAccessAuditFixture.snapshot(counters, List.of(tempDir));

            assertThat(StudioIrSdk.loadFromBundle(bundle, "workflow/ir/wf-minimal.json")).isNotNull();
            assertCode("IR_INVALID", () -> StudioIrSdk.loadFromBundle(bundle, "../outside.json"));
            assertCode("IR_INVALID", () -> StudioIrSdk.loadFromBundle(
                    nestedEscape, "workflow/ir/wf-minimal.json"));
            assertCode("IR_INVALID", () -> StudioIrSdk.loadFromBundle(
                    absoluteEscape, "workflow/ir/wf-minimal.json"));

            OfflineAccessAuditFixture.AuditSnapshot after =
                    OfflineAccessAuditFixture.snapshot(counters, List.of(tempDir));
            assertThat(after.endpointIncrementsFrom(before)).containsOnly(Map.entry("network", 0));
            assertThat(after.files()).isEqualTo(before.files());
        }
    }

    private static void prepareBundle(Path bundle, String localIrPath) throws Exception {
        StudioDslTestResources.copy("minimal-start-end.json", bundle.resolve("ir/wf-minimal.json"));
        Files.writeString(bundle.resolve("manifest.json"), MAPPER.writeValueAsString(Map.of(
                "release", false,
                "rowCount", 1,
                "irPathToLocal", Map.of("workflow/ir/wf-minimal.json", localIrPath),
                "modelMapping", "model-mapping.json")));
        Files.writeString(bundle.resolve("model-mapping.json"),
                "{\"schemaVersion\":\"1\",\"models\":{}}");
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }

    private static void assertCode(String code, Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(IrAssembleException.class, exception ->
                        assertThat(exception.code()).isEqualTo(code));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstComponent(Map<String, Object> ir) {
        return (Map<String, Object>) ((List<?>) ir.get("components")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> value) {
        return MAPPER.convertValue(value, Map.class);
    }

    private static List<Path> regularFiles(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static Map<String, Object> topologyMatrixIr() {
        Map<String, Object> ir = new LinkedHashMap<>();
        ir.put("workflowId", "wf-topology-matrix");
        ir.put("workflowName", "topology matrix");
        ir.put("schemaVersion", "0.6.0");
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(component("node_start", "jiuwen.start", Map.of()));
        components.add(component("node_branch", "jiuwen.branch", Map.of("branches", List.of(
                Map.of("branchId", "if", "condition", "true"),
                Map.of("branchId", "default", "isDefault", true)))));
        components.add(component("node_if", "jiuwen.message", Map.of("template", "if")));
        components.add(component("node_default", "jiuwen.message", Map.of("template", "default")));
        components.add(component("node_fork", "jiuwen.message", Map.of("template", "fork")));
        components.add(component("node_llm", "jiuwen.LLMComponent", Map.of(
                "stream", true,
                "model", Map.of("modelName", "topology-model", "modelType", "openai"))));
        components.add(component("node_code", "jiuwen.code", Map.of(
                "code", "def main(args): return {'lane': 'code'}")));
        components.add(component("node_end", "jiuwen.end", Map.of("isStreamOut", true)));
        ir.put("components", components);
        ir.put("connections", List.of(
                edge("node_start", "node_branch"),
                branchEdge("node_branch", "if", "node_if"),
                branchEdge("node_branch", "default", "node_default"),
                edge("node_if", "node_fork"),
                edge("node_default", "node_fork"),
                parallelStartEdge("node_fork", "node_llm", "p1"),
                parallelStartEdge("node_fork", "node_code", "p1"),
                parallelJoinEdge("node_llm", "node_end", "p1"),
                parallelJoinEdge("node_code", "node_end", "p1")));
        return ir;
    }

    private static Map<String, Object> component(
            String id, String type, Map<String, Object> configs) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("id", id);
        component.put("type", type);
        component.put("configs", configs);
        component.put("inputs", Map.of());
        component.put("outputs", Map.of());
        return component;
    }

    private static Map<String, Object> edge(String source, String target) {
        return Map.of("source", Map.of("componentId", source),
                "target", Map.of("componentId", target));
    }

    private static Map<String, Object> branchEdge(String source, String branchId, String target) {
        return Map.of("source", Map.of("componentId", source, "branchId", branchId),
                "target", Map.of("componentId", target));
    }

    private static Map<String, Object> parallelStartEdge(
            String source, String target, String parallelId) {
        return Map.of("source", Map.of("componentId", source, "parallelBranchId", parallelId),
                "target", Map.of("componentId", target));
    }

    private static Map<String, Object> parallelJoinEdge(
            String source, String target, String parallelId) {
        return Map.of("source", Map.of("componentId", source),
                "target", Map.of("componentId", target, "parallelBranchId", parallelId));
    }

    /**
     * L2 §7.1/§7.4 混泳道 sentinel：同一并行泳道组内同时存在**流式**终点与**非流式**终点时，
     * 装配必须为非流式终点插入 sentinel，接线为「非流式终点 —普通边→ sentinel —stream 边→ lane-done」，
     * 而流式终点直接 stream 到同一个 lane-done。
     *
     * <p>语料为 Studio 真实导出 {@code agent-parallel}：{@code Start 扇出} → 泳道A
     * {@code jiuwen.LLMComponent(stream=true)} ／ 泳道B {@code jiuwen.LLMReAct}（非流式）
     * → {@code jiuwen.aggregation} → {@code jiuwen.end(isStreamOut=true)}；不使用测试侧自造 IR。
     * 该语料**不带** {@code parallelBranchId}，因此装配必须推断出并行汇合屏障，否则汇聚节点缺少
     * "等全部泳道再收口"的语义。本用例只验证**装配期**接线，不执行工作流（泳道B 为 LLMReAct，执行面归 #285）。
     *
     * <p>方法名保留 #306 引用的历史回归入口名；判据已按 2026-09-20 的口径修正为"并行汇合屏障"
     * （原判据"必须插 sentinel"与本语料形状不符：该语料是两条各一个终点的泳道，不构成混泳道）。
     * 真正的混泳道 sentinel（同一泳道内流式 + 非流式终点）由
     * {@link #assembleSentinelForMarkedMixedLaneCorpus()} 覆盖。
     */
    @Test
    @Story("RT-031-04-30/parallel-barrier: 无标记并行扇入的汇合屏障装配")
    @DisplayName("真实导出并行扇出（无 parallelBranchId）：每条泳道经并行汇合屏障进入 Aggregation")
    void assembleMixedLaneSentinelForStreamAndBatchTerminals() {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();
        Map<String, Object> ir = StudioExportCorpusFixture.artifactIr(manifest, "agent-parallel");
        String streamLane = null;
        String nonStreamLane = null;
        String aggregation = null;
        for (Object component : (List<?>) ir.get("components")) {
            Map<?, ?> node = (Map<?, ?>) component;
            String type = String.valueOf(node.get("type"));
            if (type.endsWith("LLMComponent")) {
                streamLane = String.valueOf(node.get("id"));
            }
            if (type.endsWith("LLMReAct")) {
                nonStreamLane = String.valueOf(node.get("id"));
            }
            if (type.endsWith("aggregation")) {
                aggregation = String.valueOf(node.get("id"));
            }
        }
        assertThat(streamLane).as("agent-parallel 中的流式泳道终点").isNotNull();
        assertThat(nonStreamLane).as("agent-parallel 中的非流式泳道终点").isNotNull();
        assertThat(aggregation).as("agent-parallel 中的汇聚节点").isNotNull();

        Workflow workflow = StudioExportCorpusFixture
                .assembleArtifact(manifest, "agent-parallel")
                .workflow();
        WorkflowAssemblyProbe.Snapshot snapshot = WorkflowAssemblyProbe.inspect(workflow);

        String streamBarrier = snapshot.streamEdges().getOrDefault(streamLane, List.of()).stream()
                .filter(id -> id.startsWith("_parallel_done__"))
                .findFirst()
                .orElse(null);
        assertThat(streamBarrier)
                .as("流式泳道终点必须以 stream 边进入并行汇合屏障")
                .isNotNull();
        assertThat(snapshot.javaTypes().get(streamBarrier))
                .as("并行汇合屏障的装配目标类型")
                .isEqualTo("com.openjiuwen.studio.dsl.ir.ParallelLaneDoneComponent");
        assertThat(snapshot.edges().getOrDefault(streamBarrier, List.of()))
                .as("流式泳道的屏障 → 汇聚节点")
                .contains(aggregation);

        String batchBarrier = snapshot.edges().getOrDefault(nonStreamLane, List.of()).stream()
                .filter(id -> id.startsWith("_parallel_done__"))
                .findFirst()
                .orElse(null);
        assertThat(batchBarrier)
                .as("非流式泳道终点必须以普通边进入并行汇合屏障")
                .isNotNull();
        assertThat(batchBarrier).as("两条泳道各有独立屏障").isNotEqualTo(streamBarrier);
        assertThat(snapshot.edges().getOrDefault(batchBarrier, List.of()))
                .as("非流式泳道的屏障 → 汇聚节点")
                .contains(aggregation);

        assertThat(snapshot.edges().getOrDefault(streamLane, List.of()))
                .as("流式泳道不得绕过屏障直连汇聚节点")
                .doesNotContain(aggregation);
        assertThat(snapshot.edges().getOrDefault(nonStreamLane, List.of()))
                .as("非流式泳道不得绕过屏障直连汇聚节点")
                .doesNotContain(aggregation);
    }

    /**
     * 混泳道 sentinel 装配（L2 §3.2）：语料为"带 {@code parallelBranchId} 的平台链路形状"——
     * {@code start(p1) → {left, right}}，其中泳道 {@code left} 内同时挂流式终点 {@code llm} 与非流式终点
     * {@code code}，两者都汇入 {@code end(isStreamOut=true)}。
     *
     * <p>语料来源：产品仓 {@code agent-core-ext-studio-dsl/src/test/resources/ir_fixtures/test_mixed_lane_stream_ir.json}
     * （2026-09-20 复制为 SIT 语料，结构与节点 id 未改动，仅更新 workflowId/说明）。平台自存真实 IR 中
     * 暂无"同泳道混合终点"样本，本用例用于固定 L2 §3.2 的装配判据；真实链路的混泳道覆盖缺口单独登记。
     */
    @Test
    @Story("RT-031-04-30/mixed-lane: 混泳道 sentinel 装配")
    @DisplayName("同泳道流式与非流式终点：装配插入 sentinel，非流式经 batch 边接 lane-done、流式以 stream 边接同一 lane-done")
    void assembleSentinelForMarkedMixedLaneCorpus() {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();
        WorkflowAssemblyProbe.Snapshot snapshot = WorkflowAssemblyProbe.inspect(
                StudioExportCorpusFixture.assembleArtifact(manifest, "feat031-marked-mixed-lane").workflow());

        String sentinel = snapshot.nodes().keySet().stream()
                .filter(id -> id.startsWith("_lane_sentinel__"))
                .findFirst()
                .orElse(null);
        assertThat(sentinel)
                .as("同泳道内流式 + 非流式终点：必须插入 sentinel 组件")
                .isNotNull();
        assertThat(snapshot.javaTypes().get(sentinel))
                .as("sentinel 的装配目标类型")
                .isEqualTo("com.openjiuwen.studio.dsl.ir.MixedLaneSentinelComponent");

        List<String> nonStreamTargets = snapshot.edges().getOrDefault("code", List.of());
        assertThat(nonStreamTargets)
                .as("非流式终点 → sentinel（batch 边）")
                .contains(sentinel);

        List<String> sentinelStreamTargets = snapshot.streamEdges().getOrDefault(sentinel, List.of());
        assertThat(sentinelStreamTargets)
                .as("sentinel → lane-done（stream 边）")
                .isNotEmpty();
        String laneDone = sentinelStreamTargets.get(0);
        assertThat(nonStreamTargets)
                .as("非流式终点不得绕过 sentinel 直连 lane-done")
                .doesNotContain(laneDone);
        assertThat(snapshot.streamEdges().getOrDefault("llm", List.of()))
                .as("流式终点直接 stream 到同一 lane-done")
                .contains(laneDone);
    }

    /**
     * `#314` 修复回归：Studio <em>真实导出</em>的嵌套并行语料（汇合边 {@code parallelBranchId} 为
     * 逗号拼接的多值 {@code "ctnzkhsfbbkeamuj,eybrokotkavhimtg"}）必须被按集合拆分并参与 fork/join 匹配，
     * 从而建出 lane-done 屏障；泳道 A 内同时存在流式终点（{@code node_stream}）与非流式终点
     * （{@code node_batch}）时还必须按 L2 §3.2 插入混泳道 sentinel。
     *
     * <p>与 {@link #assembleSentinelForMarkedMixedLaneCorpus()} 的区别：后者用的是<em>单值</em>标记的
     * 契约形状语料，本用例才命中 `#314` 的多值形态（真实导出，非测试侧自造）。断言口径与 Feature §4.2
     * 「连线装配」MUST、L2 §3.2（混泳道 sentinel）一致；期望形状与被测实现自带用例
     * {@code MixedLaneSentinelAssembleTest#nestedParallel_multiValueJoinMark_wiresMixedLaneSentinel} 对齐。
     *
     * <p>范围：只覆盖 §4 装配期；不启动 SUT、不调用模型。
     */
    @Test
    @Story("RT-031-04-30/mixed-lane: 真实导出多值并行标记的 lane-done 与 sentinel 装配（#314 回归）")
    @DisplayName("真实导出嵌套并行（汇合边 parallelBranchId 多值）：建 lane-done，且混泳道插入 sentinel")
    void assembleSentinelForRealNestedMixedLaneCorpus() {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();
        WorkflowAssemblyProbe.Snapshot snapshot = WorkflowAssemblyProbe.inspect(
                StudioExportCorpusFixture.assembleArtifact(
                        manifest, "feat031-mixedlane-parallel-nested").workflow());

        assertThat(snapshot.nodes().keySet().stream().filter(id -> id.startsWith("_parallel_done__")))
                .as("多值 parallelBranchId 的汇合边必须被拆分并建立 lane-done 屏障")
                .isNotEmpty();

        String sentinel = snapshot.nodes().keySet().stream()
                .filter(id -> id.startsWith("_lane_sentinel__"))
                .findFirst()
                .orElse(null);
        assertThat(sentinel)
                .as("泳道 A 内同时有流式终点(node_stream)与非流式终点(node_batch)：必须插入 sentinel")
                .isNotNull();
        assertThat(snapshot.javaTypes().get(sentinel))
                .as("sentinel 的装配目标类型")
                .isEqualTo("com.openjiuwen.studio.dsl.ir.MixedLaneSentinelComponent");

        List<String> batchTargets = snapshot.edges().getOrDefault("node_batch", List.of());
        assertThat(batchTargets)
                .as("非流式终点 → sentinel（batch 边）")
                .contains(sentinel);

        List<String> sentinelStreamTargets = snapshot.streamEdges().getOrDefault(sentinel, List.of());
        assertThat(sentinelStreamTargets)
                .as("sentinel → lane-done（stream 边）")
                .isNotEmpty();
        String laneDone = sentinelStreamTargets.get(0);
        assertThat(laneDone)
                .as("sentinel 必须收口到 lane-done 屏障")
                .startsWith("_parallel_done__");
        assertThat(snapshot.streamEdges().getOrDefault("node_stream", List.of()))
                .as("流式终点以 stream 边接同一 lane-done")
                .contains(laneDone);
        assertThat(batchTargets)
                .as("非流式终点不得绕过 sentinel 直连 lane-done")
                .doesNotContain(laneDone);
    }

    /**
     * L2 §7.1 知识库 / 记忆后端（§4 装配侧字段契约）：以 Studio <em>真实导出</em>语料（画布
     * {@code KnowledgeRepo} 节点，workflow {@code 10b9efe1…}，2026-09-19 在部署机 validate → publish →
     * export 取得）证明装配侧保留知识库声明字段契约。
     *
     * <p>真实导出形态为 {@code jiuwen.knowledgeRetrieval}，而不是插件式 {@code jiuwen.plugin}。manifest 的
     * {@code knowledge-repo} 行仍是迁移前的历史产物（{@code corpusStatus=exported-as-plugin}）；本用例保留
     * 一条对比断言把两种形态分开，避免用历史语料反推当前画布节点契约。
     *
     * <p>范围：只覆盖 §4 装配侧。「知识库本地连接就绪、未接线不得假成功」的执行侧归 §3，不在本用例内。
     */
    @Test
    @Story("RT-031-04-31/knowledge-repo: 知识库装配侧字段契约")
    @DisplayName("真实导出 KnowledgeRepo 导出为 jiuwen.knowledgeRetrieval，装配保留知识库声明字段")
    void preserveKnowledgeRetrievalDeclarationFromStudioExport() {
        StudioExportCorpusFixture.Manifest manifest = StudioExportCorpusFixture.manifest();
        Map<String, Object> ir = StudioExportCorpusFixture.artifactIr(manifest, "feat031-l2s7-knowledge-repo");
        Map<String, Object> kbNode = componentById(ir, "node_kb");

        assertThat(kbNode.get("type"))
                .as("画布 KnowledgeRepo 节点的真实导出类型")
                .isEqualTo("jiuwen.knowledgeRetrieval");
        assertThat(kbNode.get("type"))
                .as("不得退回迁移前的插件式导出形态")
                .isNotEqualTo("jiuwen.plugin");

        Map<String, Object> configs = asMap(kbNode.get("configs"), "node_kb.configs");
        assertThat(configs.get("knowledgeBaseIds"))
                .as("知识库声明：知识库 ID 列表")
                .isEqualTo(List.of("feat031_l2s7_kb_0001"));
        Map<String, Object> retrievalConfig = asMap(configs.get("retrievalConfig"), "node_kb.retrievalConfig");
        assertThat(retrievalConfig.keySet())
                .as("知识库声明：检索参数契约字段")
                .contains("topK", "scoreThreshold", "recallThreshold", "searchMode", "faqThreshold");
        assertThat(retrievalConfig.get("topK")).as("topK").isEqualTo(2);
        assertThat(retrievalConfig.get("searchMode")).as("searchMode").isEqualTo("doc");

        WorkflowAssemblyProbe.Snapshot snapshot = WorkflowAssemblyProbe.inspect(
                StudioExportCorpusFixture.assembleArtifact(manifest, "feat031-l2s7-knowledge-repo").workflow());
        assertThat(snapshot.nodes().keySet())
                .as("装配后的知识库节点")
                .contains("node_kb");
        assertThat(snapshot.javaTypes().get("node_kb"))
                .as("知识库节点的装配目标类型")
                .isEqualTo("com.openjiuwen.studio.dsl.nodes.FlowKnowledgeRetrievalNode");
        assertThat(snapshot.edges().getOrDefault("node_start", List.of()))
                .as("声明拓扑：Start → KnowledgeRepo")
                .contains("node_kb");
        assertThat(snapshot.edges().getOrDefault("node_kb", List.of()))
                .as("声明拓扑：KnowledgeRepo → End")
                .contains("node_end");

        // 对比证据：历史语料（迁移前导出）仍是插件节点，说明旧记录里的「映射与真实导出不一致」
        // 来自该历史产物，而不是当前画布 KnowledgeRepo 的契约。
        Map<String, Object> legacyIr = StudioExportCorpusFixture.artifactIr(manifest, "knowledge-repo");
        assertThat(componentById(legacyIr, "node_1737183484514").get("type"))
                .as("历史语料的节点形态（迁移前导出）")
                .isEqualTo("jiuwen.plugin");
    }

    private static Map<String, Object> componentById(Map<String, Object> ir, String componentId) {
        for (Object component : (List<?>) ir.get("components")) {
            Map<?, ?> node = (Map<?, ?>) component;
            if (componentId.equals(String.valueOf(node.get("id")))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) node;
                return typed;
            }
        }
        throw new AssertionError("component " + componentId + " is missing from the exported IR");
    }

    private static Map<String, Object> asMap(Object value, String label) {
        assertThat(value).as(label).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) value;
        return typed;
    }
}
