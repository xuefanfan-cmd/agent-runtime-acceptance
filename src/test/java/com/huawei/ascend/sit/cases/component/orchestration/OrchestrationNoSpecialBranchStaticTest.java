/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 065-S21 与 066-S13（**同一份证据服务两个特性**，覆盖台账登记共担）：
 * 执行层无 {@code cross_validation} 专用分支（静态合同核对）。
 *
 * <p>Oracle 来源：FEAT-065 §5.1.1「交叉验证形态在装载期编译为依赖图，**执行层无专用分支**」；
 * FEAT-066 §6「不得在执行层为交叉验证增加专用分支」；L2-066 §4「{@code BlueprintScheduler}
 * 全类无 {@code cross_validation} 分支（grep 可证）」、§7.1「结构性审查(grep 证据)」。
 *
 * <p>这是设计**本来只要求静态验证**的条目（形态 {@code static}），因此静态断言即充分、不属降级
 * （RULES「测试证据门禁」：静态检查只有在设计本来要求静态验证时才能证明能力）。
 * 与之互补的**动态**证据是 066-S12（交叉验证图纸与手写等价 DAG 执行结果逐项一致）。
 *
 * <p><b>源码定位方式</b>（设计 §6 要求"把源码路径作为测试资源解析，避免硬编码本机路径"）：
 * 按 ① 系统属性 {@code orchestration.source.root} ② 环境变量 {@code ORCHESTRATION_SOURCE_ROOT}
 * 的顺序解析被测模块的 {@code src/main/java} 根，两者都缺时以 {@link Assumptions} 中止
 * （记为 SKIPPED ⇒ 结论 INCONCLUSIVE，**不判产品 FAIL**，也不假装通过）。
 * 执行命令须显式带 {@code -Dorchestration.source.root=<被测模块 src/main/java 绝对路径>}。
 *
 * <p>断言范围：{@code BlueprintScheduler.java} **全文含注释**（设计明写"含注释"），
 * 字面量 {@code cross_validation} 与 {@code CROSS_VALIDATION} 命中数必须为 0。
 */
@Tag("component")
@Tag("feat-065")
@Tag("feat-066")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class OrchestrationNoSpecialBranchStaticTest {

    private static final String SCHEDULER_SOURCE =
            "com/openjiuwen/agents/orchestration/BlueprintScheduler.java";

    private static final List<String> FORBIDDEN_LITERALS = List.of("cross_validation", "CROSS_VALIDATION");

    @Test
    @Story("065-S21 / 066-S13: 执行层无 cross_validation 专用分支（静态）")
    @DisplayName("065-S21 / 066-S13 BlueprintScheduler.java 全文（含注释）不含 cross_validation 字面量")
    void schedulerSourceHasNoCrossValidationBranch() throws IOException {
        final Path sourceRoot = resolveSourceRoot();
        final Path scheduler = sourceRoot.resolve(SCHEDULER_SOURCE);
        assertThat(Files.isRegularFile(scheduler))
                .as("被测源码应存在：%s", scheduler).isTrue();

        final String text = Files.readString(scheduler, StandardCharsets.UTF_8);
        assertThat(text).as("被测源码不应为空：%s", scheduler).isNotBlank();

        final List<String> hits = new ArrayList<>();
        for (String literal : FORBIDDEN_LITERALS) {
            int from = 0;
            while (true) {
                final int at = text.indexOf(literal, from);
                if (at < 0) {
                    break;
                }
                hits.add(literal + "@" + lineOf(text, at));
                from = at + literal.length();
            }
        }
        assertThat(hits).as("执行层 %s 全文（含注释）不应出现任何交叉验证专用字面量；命中=%s",
                scheduler.getFileName(), hits).isEmpty();

        // 反向自检：扫描机制本身有效（若连必然存在的标识符都扫不到，说明读取方式失效）
        assertThat(text).as("扫描自检：应能扫到必然存在的类名 BlueprintScheduler")
                .contains("BlueprintScheduler");
        assertThat(text).as("扫描自检：应能扫到必然存在的方法名 execute").contains("execute");
    }

    /** 解析被测模块源码根：系统属性优先，其次环境变量；都缺则中止（不猜、不硬编码本机路径）。 */
    private static Path resolveSourceRoot() {
        final String fromProperty = System.getProperty("orchestration.source.root");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Path.of(fromProperty.trim());
        }
        final String fromEnv = System.getenv("ORCHESTRATION_SOURCE_ROOT");
        Assumptions.assumeTrue(fromEnv != null && !fromEnv.isBlank(),
                "未提供被测源码根：请加 -Dorchestration.source.root=<agent-core-ext-orchestration 的 "
                        + "src/main/java 绝对路径>，或设环境变量 ORCHESTRATION_SOURCE_ROOT。"
                        + "缺它时本用例记 SKIPPED（结论 INCONCLUSIVE），不判产品 FAIL。");
        return Path.of(fromEnv.trim());
    }

    private static int lineOf(final String text, final int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
