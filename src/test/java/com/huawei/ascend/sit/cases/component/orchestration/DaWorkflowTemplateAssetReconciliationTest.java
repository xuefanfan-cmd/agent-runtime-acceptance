/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.da.workflow.DaWorkflowRunner;
import com.openjiuwen.da.workflow.WorkflowRunTool;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S10 / 067-S21：模板资产与工具描述清单的对账（静态合同断言）。
 *
 * <p><b>2026-10-18 按批次记录 §7W.5 处置③ 拆为两条</b>，原因是本用例原口径与模板资产的
 * <b>归属方设计</b>方向相反：FEAT-068 L2 §1.3 明写「实装清单(SHIPPED_TEMPLATES)与资产目录靠
 * <b>人工对账</b>：测试断言『八套可装载』，<b>不断言『目录下只有八套』——多放一套资产不会红</b>，
 * 清单漂移只能评审拦截」，§2.5 亦记「已知弱化：<b>无自动化漂移检测</b>」；
 * 而 L2-067:30 明写「模板内容资产(<b>归模板库特性</b>；本特性只提供名字→资产的解析接缝)」
 * ⇒ FEAT-068 是归属方，其口径为权威口径。原实现却做「四方集合完全相等」的硬断言，
 * 会在 FEAT-068 合法落地第 9 套模板时产生<b>假红</b>，并被误读成产品缺陷。
 *
 * <p>拆分后按<b>方向分级</b>，两个方向都有真实 Oracle，不是单纯软化：
 * <ul>
 *   <li><b>067-S10（设计口径，硬断言）</b>：① FEAT-067 §3「实装八套」的八套资产都在制品里；
 *       ② 每套都能被装载器装载（非死文件）；③ 八套都对模型可见（{@code ships:} 清单段）；
 *       ④ 清单与制品资产<b>双向完全相等</b>（FEAT-067 §3「工具描述清单必须与资产对账一致」
 *       + FEAT-068 §2.5「三处漂移即缺陷」）。<b>不</b>断言「目录下只有八套」。</li>
 *   <li><b>067-S21（测试侧自愿加严，方向分级）</b>：把本期<b>冻结基准</b>（八套名单）与制品实际集合比对。
 *       <b>缺失方向</b>（基准有、制品无）⇒ 违反 FEAT-067 §3 MUST ⇒ <b>判 FAIL</b>；
 *       <b>新增方向</b>（制品有、基准无）⇒ FEAT-068 §1.3 明写不红 ⇒ <b>只记 WARN，不判 FAIL</b>，
 *       并明示正确动作是「更新测试侧冻结基准」而非「提产品 ISSUE」。</li>
 * </ul>
 *
 * <p>这样既<b>保留</b>了自动化漂移检测能力（正是 FEAT-068 §1.3 自陈的已知弱化、SIT 能补的真实价值），
 * 又<b>不让</b>设计明确容忍的方向污染验收判定。
 *
 * <p>这是设计<b>本来只要求静态对账</b>的条目，因此静态断言即充分、不属降级
 * （RULES「测试证据门禁」：静态检查只有在设计本来要求静态验证时才能证明能力）。
 *
 * <p>不应断言：模板<b>内容</b>的正确性（归 FEAT-068 模板库特性，见变更包范围卡 §4.1）。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowTemplateAssetReconciliationTest {

    /** 生产装配需要 baseRoot（DaWorkflowTools.attach:40）；缺它 workflow_run 走"不落盘"降级形态。 */
    /** 静态：本类的 {@code templateParamDescription()} 是静态助手，需从静态上下文取 baseRoot。 */
    @TempDir
    static Path baseRoot;

    /** FEAT-067 §3 与 L2-067 §2.4 一致列出的八套实装模板（对账基准）。 */
    private static final Set<String> EXPECTED = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "research-pipeline",
            "comprehensive-analysis",
            "comprehensive-deep-analysis",
            "generic-parallel-retrieval",
            "multi-perspective-council",
            "parallel-deep-retrieval",
            "parallel-research",
            "zero-hallucination")));

    private static final String DIR = "workflow-templates/";

    @Test
    @Story("067-S10: 模板资产与工具描述清单对账（设计口径）")
    @DisplayName("067-S10 八套模板全部实装且可装载，工具描述清单与制品资产双向一致")
    void shippedTemplatesReconcileAcrossSources() throws Exception {
        // 制品内 workflow-templates/ 的实际资产集合
        final Set<String> actual = listTemplateAssets();

        // ① FEAT-067 §3「实装八套(对账基准)」⇒ 八套资产必须都在（少一套即缺陷）
        assertThat(actual)
                .as("制品内 workflow-templates/ 应含 FEAT-067 §3『实装八套』的全部资产")
                .containsAll(EXPECTED);

        // ② 每套资产都能被装载器成功装载（证明不是死文件），且装载产物类型合法
        for (String name : EXPECTED) {
            final String yaml = readClasspath(DIR + name + ".yaml");
            assertThat(yaml).as("模板 %s 内容不应为空", name).isNotBlank();
            final BlueprintSpec spec = BlueprintLoader.load(yaml);
            assertThat(spec.id()).as("模板 %s 装载后应保留图纸 id", name).isNotBlank();
            assertThat(spec.type()).as("模板 %s 装载后类型不应为空", name).isNotNull();
            assertThat(spec.members()).as("模板 %s 应至少声明一个成员", name).isNotEmpty();
        }

        // ③ 八套都对模型可见（工具描述 template 参数里逐个点名）
        final String description = templateParamDescription();
        final List<String> missing = new ArrayList<>();
        for (String name : EXPECTED) {
            if (!description.contains(name)) {
                missing.add(name);
            }
        }
        assertThat(missing).as("工具描述的 template 参数里应列出全部八套模板名（对模型可见的清单）；缺失=%s", missing)
                .isEmpty();

        // ④ 清单与制品资产**双向完全相等**：FEAT-067 §3「工具描述清单必须与资产对账一致」
        //    + FEAT-068 §2.5「资产/清单常量/测试名单三处漂移即缺陷」。
        //    精确解析 "ships: <清单>)" 段，不用宽松正则扫全描述——描述里还含 "workflow-templates"
        //    这样的目录名，会被误当成模板名（本轮首跑即因此误报，属测试缺陷，已改为精确解析）。
        final Set<String> advertised = parseAdvertisedTemplates();
        assertThat(advertised)
                .as("工具描述里对模型可见的模板清单应与制品内资产**双向完全相等**（任一侧漂移即缺陷）")
                .containsExactlyInAnyOrderElementsOf(actual);
    }

    /**
     * 067-S21：冻结基准漂移检测（<b>测试侧自愿加严</b>，非设计要求的自动化检测）。
     *
     * <p>Oracle 按<b>方向分级</b>：
     * <ul>
     *   <li>缺失方向（冻结基准有、制品无）⇒ 违反 FEAT-067 §3「实装八套」MUST ⇒ <b>FAIL</b>；</li>
     *   <li>新增方向（制品有、冻结基准无）⇒ FEAT-068 L2 §1.3 明写「多放一套资产不会红」⇒ <b>WARN，不 FAIL</b>。</li>
     * </ul>
     *
     * <p>本条存在的意义：FEAT-068 §1.3/§2.5 自陈「已知弱化：无自动化漂移检测，清单漂移只能评审拦截」。
     * SIT 补上这个检测是有价值的，但<b>不能</b>让设计明确容忍的方向变成产品 FAIL——
     * 否则第 9 套模板合法落地时会产生假红并被误读成缺陷。故新增方向只报警并给出正确动作。
     */
    @Test
    @Story("067-S21: 冻结基准漂移检测（测试侧加严，方向分级）")
    @DisplayName("067-S21 相对冻结基准(八套)：缺失判 FAIL，新增只记 WARN 不判缺陷")
    void frozenBaselineDriftIsJudgedByDirection() throws Exception {
        final Set<String> actual = listTemplateAssets();
        final Set<String> advertised = parseAdvertisedTemplates();

        // —— 缺失方向：判 FAIL（设计 MUST）——
        final Set<String> missingAssets = difference(EXPECTED, actual);
        assertThat(missingAssets)
                .as("制品缺少冻结基准里的模板资产，违反 FEAT-067 §3『实装八套』MUST；缺失=%s", missingAssets)
                .isEmpty();
        final Set<String> missingAdvertised = difference(EXPECTED, advertised);
        assertThat(missingAdvertised)
                .as("工具描述清单缺少冻结基准里的模板名（对模型不可见）；缺失=%s", missingAdvertised)
                .isEmpty();

        // —— 新增方向：只记 WARN，不判 FAIL（设计明确容忍）——
        final Set<String> extraAssets = difference(actual, EXPECTED);
        final Set<String> extraAdvertised = difference(advertised, EXPECTED);
        if (extraAssets.isEmpty() && extraAdvertised.isEmpty()) {
            System.out.println("[INFO][067-S21] 制品模板集合与本期冻结基准(八套)完全一致，无漂移。");
            return;
        }
        System.out.println("[WARN][067-S21] 模板集合相对本期冻结基准(八套)已扩张："
                + " 资产新增=" + extraAssets + " 清单新增=" + extraAdvertised);
        System.out.println("[WARN][067-S21] 依据 FEAT-068 L2 §1.3「实装清单与资产目录靠人工对账：测试断言『八套可装载』，"
                + "不断言『目录下只有八套』——多放一套资产不会红」⇒ 本方向**不判产品缺陷、不提 ISSUE**。");
        System.out.println("[WARN][067-S21] 正确动作：更新测试侧冻结基准（本类 EXPECTED 常量 + 测试设计细档 067-S10/067-S21 名单），"
                + "并与 FEAT-068 L2 §2.1 的模板表对齐后重跑。");
    }

    /** 从工具卡 {@code template} 参数描述里精确解析 {@code ships: a, b, c)} 清单段。 */
    private static Set<String> parseAdvertisedTemplates() {
        final String description = templateParamDescription();
        final int from = description.indexOf("ships:");
        assertThat(from).as("template 参数描述应含 'ships:' 清单段，否则对模型不可见").isNotNegative();
        final int to = description.indexOf(')', from);
        assertThat(to).as("'ships:' 段应有右括号收尾").isGreaterThan(from);
        final Set<String> advertised = new LinkedHashSet<>();
        for (String token : description.substring(from + "ships:".length(), to).split(",")) {
            final String name = token.trim();
            if (!name.isEmpty()) {
                advertised.add(name);
            }
        }
        assertThat(advertised).as("'ships:' 段应解析出至少一个模板名").isNotEmpty();
        return advertised;
    }

    /** 集合差 {@code a - b}，保持 a 的迭代序。 */
    private static Set<String> difference(final Set<String> a, final Set<String> b) {
        final Set<String> out = new LinkedHashSet<>();
        for (String x : a) {
            if (!b.contains(x)) {
                out.add(x);
            }
        }
        return out;
    }

    /** 从制品（jar 或目录）枚举 {@code workflow-templates/*.yaml} 的实际文件名。 */
    private static Set<String> listTemplateAssets() throws Exception {
        final URI base = WorkflowRunTool.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        final Set<String> names = new LinkedHashSet<>();
        final Path p = Path.of(base);
        if (Files.isRegularFile(p)) {
            // jar 形态：用 FileSystems.newFileSystem 只读打开，避免依赖具体 jar 库
            try (FileSystem fs = FileSystems.newFileSystem(p, (ClassLoader) null)) {
                try (Stream<Path> walk = Files.walk(fs.getPath(DIR))) {
                    walk.filter(Files::isRegularFile)
                            .map(x -> x.getFileName().toString())
                            .filter(n -> n.endsWith(".yaml"))
                            .forEach(n -> names.add(n.substring(0, n.length() - ".yaml".length())));
                }
            }
        } else {
            try (Stream<Path> walk = Files.walk(p.resolve(DIR))) {
                walk.filter(Files::isRegularFile)
                        .map(x -> x.getFileName().toString())
                        .filter(n -> n.endsWith(".yaml"))
                        .forEach(n -> names.add(n.substring(0, n.length() - ".yaml".length())));
            }
        }
        assertThat(names).as("应能从制品枚举到 workflow-templates/ 下的资产（枚举到 0 个说明读取方式失效）")
                .isNotEmpty();
        return names;
    }

    /**
     * 取工具卡里 {@code template} 入参的 description——即"对模型可见的模板清单"。
     * 走 {@code getInputParams()} 而非 {@code getDescription()}，因为清单常量正是拼在 template 参数描述里。
     */
    @SuppressWarnings("unchecked")
    private static String templateParamDescription() {
        final DaWorkflowRunner runner = DaWorkflowTestSupport.newRunner((Function<MemberSpec, SubAgentExecutor>) member -> (userInput, subGoal) -> "unused", 1);
        final WorkflowRunTool tool = DaWorkflowTestSupport.newRunTool(runner, baseRoot);
        final Map<String, Object> params = tool.getCard().getInputParams();
        assertThat(params).as("工具卡应声明 inputParams").isNotNull();
        assertThat(params.get("properties")).as("inputParams 应含 properties").isInstanceOf(Map.class);
        final Map<String, Object> props = (Map<String, Object>) params.get("properties");
        assertThat(props).as("应声明 template 入参").containsKey("template");
        final Map<String, Object> template = (Map<String, Object>) props.get("template");
        assertThat(template.get("description")).as("template 入参应有 description").isInstanceOf(String.class);
        return (String) template.get("description");
    }

    private static String readClasspath(final String path) throws IOException {
        try (InputStream in = DaWorkflowTemplateAssetReconciliationTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(in).as("classpath 资源应存在：%s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
