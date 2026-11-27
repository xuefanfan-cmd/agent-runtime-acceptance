/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.BlueprintScheduler;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.BlueprintType;
import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.orchestration.StepOutcome;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 065-S01：图纸四形态的执行序（流水线 / 依赖图 / 全并行 / 单兵）。
 *
 * <p>Oracle 来源：FEAT-065 §2「图纸四种形态」（MUST，"四种形态语义互不相同且装载期可区分"）
 * 与 §5.1.2 调度语义（流水线每步一层；依赖图入度归零即入层、层内并行、层间汇合；全并行与单兵单层）；
 * L2-065 §3.2/§3.3。
 *
 * <p>主断言：① 装载后 {@code spec.type()} 与图纸声明一致（证明四形态装载期可区分）；
 * ② {@code stepOutcomes()} 的登记序 == 图纸声明序（依赖图为"拓扑层序 × 层内声明序"）；
 * ③ 收口产物为末个实际执行步的产物且非空白。
 *
 * <p>故障与主断言：若引擎把依赖图悄悄压扁成串行、或按 worker 完成序而非声明序登记，
 * 则断言 ② 失败；若四形态在装载期不可区分，则断言 ① 失败。
 *
 * <p>执行体形态：测试作为装配方按 FEAT-065 §3「成员绑定契约」提供**确定性执行体**
 * （回显带用例 ID 与随机后缀的唯一标记串）。这不是 Mock LLM——设计明文把"执行体由谁提供"
 * 交给装配方，且 §1 面向角色明写"测试与验收团队：以确定性图纸做可重放的行为判据"。
 * 真实模型成员形态由 065-S22（端到端）承接，两层互为对照。
 *
 * <p>不应断言：层内并行的时间重叠（归 065-S02 的闩锁构造）；投影段的字面格式
 * （FEAT-065 §5.1.3 明写"拼入形态由成员绑定实现决定"）。
 */
@Tag("component")
@Tag("feat-065")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class BlueprintFourShapesExecutionOrderTest {

    /** 数据唯一性：标记串带用例 ID + 随机后缀，不依赖方法顺序或其他用例残留状态。 */
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private static final String PIPELINE = """
            blueprint:
              id: T-065-S01-pipeline
              type: pipeline
              members:
                - {id: m1, instruction: "汇总技术要点"}
                - {id: m2, instruction: "汇总合规要点"}
              steps:
                - {id: s1, member: m1}
                - {id: s2, member: m2}
            """;

    private static final String DAG = """
            blueprint:
              id: T-065-S01-dag
              type: dag
              members:
                - {id: m1, instruction: "汇总技术要点"}
                - {id: m2, instruction: "汇总合规要点"}
                - {id: m3, instruction: "合并两路要点"}
              steps:
                - {id: s1, member: m1}
                - {id: s2, member: m2}
                - {id: s3, member: m3, depends_on: [s1, s2]}
            """;

    private static final String PARALLEL = """
            blueprint:
              id: T-065-S01-parallel
              type: parallel
              members:
                - {id: m1, instruction: "技术视角"}
                - {id: m2, instruction: "市场视角"}
                - {id: m3, instruction: "合规视角"}
            """;

    private static final String SINGLE = """
            blueprint:
              id: T-065-S01-single
              type: single
              members:
                - {id: m1, instruction: "独立作答"}
            """;

    static Stream<Arguments> shapes() {
        return Stream.of(
                Arguments.of("流水线", PIPELINE, BlueprintType.PIPELINE,
                        List.of("s1", "s2"), List.of("m1", "m2")),
                Arguments.of("依赖图", DAG, BlueprintType.DAG,
                        List.of("s1", "s2", "s3"), List.of("m1", "m2", "m3")),
                Arguments.of("全并行", PARALLEL, BlueprintType.PARALLEL,
                        null, List.of("m1", "m2", "m3")),
                Arguments.of("单兵", SINGLE, BlueprintType.SINGLE,
                        null, List.of("m1")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    @Story("065-S01: 四形态执行序")
    @DisplayName("065-S01 图纸四形态：装载期可区分 + 登记序 == 声明序 + 收口为末步产物")
    void fourShapesExecuteInDeclaredOrder(final String label, final String yaml,
            final BlueprintType expectedType, final List<String> expectedStepIds,
            final List<String> expectedMemberIds) {
        // Given：装载图纸
        final BlueprintSpec spec = BlueprintLoader.load(yaml);

        // Then①：四形态装载期可区分
        assertThat(spec.type()).as("%s：装载后 type 应与图纸声明一致", label).isEqualTo(expectedType);
        assertThat(spec.id()).as("%s：图纸 id 应保留", label).isNotBlank();

        // Given：按 FEAT-065 §3 成员绑定契约提供确定性执行体，并记录调用序
        final List<String> invoked = new ArrayList<>();
        final Map<String, SubAgentExecutor> bindings = bindings(spec, invoked);
        assertThat(bindings).as("%s：每个声明的成员都应有绑定", label).isNotEmpty();

        // When：执行（留痕载体由测试持有——FEAT-065 §3「留痕写入宿主持有的轨迹结构」）
        final BlueprintResult result;
        try (BlueprintScheduler scheduler = new BlueprintScheduler(new TraceForest(), 4)) {
            result = scheduler.execute(spec, bindings, "U-065-S01-" + RUN);
        }

        // Then②：登记序 == 声明序（依赖图为拓扑层序 × 层内声明序）
        final List<StepOutcome> outcomes = result.stepOutcomes();
        assertThat(outcomes).as("%s：每步都应有留痕", label).hasSize(expectedMemberIds.size());
        assertThat(outcomes).as("%s：成员登记序应等于声明序", label)
                .extracting(StepOutcome::memberId).containsExactlyElementsOf(expectedMemberIds);
        if (expectedStepIds != null) {
            assertThat(outcomes).as("%s：步骤登记序应等于声明序", label)
                    .extracting(StepOutcome::stepId).containsExactlyElementsOf(expectedStepIds);
        }
        assertThat(outcomes).as("%s：无步骤应被跳过", label)
                .allSatisfy(o -> assertThat(o.skipped()).isFalse());
        assertThat(outcomes).as("%s：每步都应有留痕分支标识", label)
                .allSatisfy(o -> assertThat(o.branchId()).isNotBlank());
        // 全部成员都真的被执行过（不是只登记不执行）
        assertThat(invoked).as("%s：每个成员执行体都应被调用一次", label)
                .containsExactlyInAnyOrderElementsOf(new LinkedHashSet<>(expectedMemberIds));

        // Then③：收口产物含末步产物、且非空白。
        // 【2026-10-26 r6 判据改按新语义】原判据 isEqualTo(last.output()) 对**全并行**形态属过度指定：
        // r6 起 BlueprintScheduler.collectResults:205-222 规定「层内多步有产出 ⇒ 按 ## <步骤id> 分节聚合」，
        // 【2026-10-28 出处更新】该规则现已有**文档出处**：docs `!383`（`main@bd6ac83f`）把它写进
        // FEAT-065:45（MUST 行三分支）与 L2-065:87/:132/:221（我方 `docs#41` 上报、已验证关单），
        // 代码注释明写「council / parallel **不能只取最后一个成员的输出**」⇒ **旧行为才是缺陷**。
        // 分流后仍保留最强判据：层内**单步**产出时 final_output 严格等于该步产物（走 if 之外的相等分支）；
        // 多步层则要求「含末步产物原文 + 含末步的分节头」——既能抓"错位到别的步"，也能抓"只取某个成员"。
        final StepOutcome last = outcomes.get(outcomes.size() - 1);
        assertThat(result.finalOutput()).as("%s：收口产物应含末步产物原文（不得错位、不得丢失）", label)
                .contains(last.output());
        if (!result.finalOutput().equals(last.output())) {
            assertThat(result.finalOutput())
                    .as("%s：层内多步产出 ⇒ 收口产物应是 \"## <步骤id>\" 分节聚合，且含末步 %s 的分节头；实收=%s",
                            label, last.stepId(), result.finalOutput())
                    .contains("## " + last.stepId());
        }
        assertThat(result.finalOutputText()).as("%s：收口产物不应为空白", label).isPresent();
        assertThat(result.blueprintId()).as("%s：结果应回带图纸 id", label).isEqualTo(spec.id());
        // 产物内容确实是执行体回显的唯一标记（证明不是空跑）
        assertThat(last.output()).as("%s：末步产物应含本用例唯一标记", label)
                .contains("M-065-S01-" + last.memberId() + "-" + RUN);
    }

    /** 按图纸声明的成员构造确定性执行体：回显唯一标记串，并记录被调用的成员。 */
    private static Map<String, SubAgentExecutor> bindings(final BlueprintSpec spec, final List<String> invoked) {
        final Map<String, SubAgentExecutor> map = new LinkedHashMap<>();
        for (MemberSpec member : spec.members()) {
            final String mid = member.id();
            map.put(mid, (userInput, subGoal) -> {
                synchronized (invoked) {
                    invoked.add(mid);
                }
                // 同时断言两个输入位都送达（FEAT-065 §3 成员绑定契约），但不校验拼接格式
                assertThat(userInput).as("成员 %s 应收到原始用户输入位", mid).isNotBlank();
                assertThat(subGoal).as("成员 %s 应收到步骤目标位", mid).isNotBlank();
                return "M-065-S01-" + mid + "-" + RUN;
            });
        }
        return map;
    }

    /** 供 065-S02 等复用：构造带闩锁的执行体以证明层内真并发。 */
    static Set<String> memberIds(final BlueprintSpec spec) {
        final Set<String> ids = new LinkedHashSet<>();
        for (MemberSpec m : spec.members()) {
            ids.add(m.id());
        }
        return ids;
    }
}
