package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-109 权限链装配 fail-fast（F109-20，DV-109-01 撤销后转 runnable 执行验证）。
 *
 * <p><b>判据（被测制品 0.1.3-feat109111-r3 = common@babb882b9，源码读出）：</b></p>
 * <ul>
 *   <li>actrule 资产腐坏 → 装配失败 fail-fast：{@code DaEngineConfiguration.buildPermissions}
 *       对框架级资产缺失抛 IllegalStateException（"Framework actrule asset required"），
 *       场景级 assets（{@code scenarios/<dir>/governance/actrule.yaml}，相对 SUT 进程 CWD）
 *       经 {@code DaGuardrailAssembler.translate} 校验——非法级别/空/解析失败抛
 *       {@code DaAssetException}（消息含 source 与 reason）→ Spring 装配失败 → 进程退出。</li>
 *   <li>启动失败形态：SutStack 抛 IllegalStateException("process exited before becoming ready")
 *       （F110-29 先例同款）；SUT 日志含 actrule 错误消息（键名/来源断言面）。</li>
 *   <li><b>「重复 Rail」面不属黑盒可构造</b>：Rail 注册发生在宿主装配线内部（无外部配置能触发
 *       重复注册），该半格登记为黑盒不可构造，按 !1181 修复的引擎内测试承接（装配断言在
 *       agent-solution 仓内），本类只承接资产腐坏面。</li>
 * </ul>
 *
 * <p><b>构造纪律</b>：corrupt scenarios/ 目录只在测试方法内存活（try/finally 即建即删）——
 * 该目录对同 CWD 的所有 SUT 启动全局生效，绝不可跨用例残留。failsafe 类间串行，
 * 窗口内无其他类并发起 SUT。</p>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109AssetFailFastE2EIT {

    private static final TestConfig CONFIG = TestConfig.load();
    private static final Path BASEDIR = Path.of(
            System.getProperty("basedir", System.getProperty("user.dir")));
    private static final Path SUT_LOG = BASEDIR.resolve(
            "target/sit-logs/deepanalyze-guardrail/stdout.log");

    @Test
    @Story("F109-20: actrule 资产腐坏 → 装配失败 fail-fast，错误含来源与原因")
    @DisplayName("F109-20: 场景级 actrule 非法级别 → 启动失败")
    void corruptScenarioActruleFailsStartup() throws Exception {
        Path scenarioDir = BASEDIR.resolve("scenarios/f10920-corrupt/governance");
        Files.createDirectories(scenarioDir);
        long logOffset = logLength();
        try {
            // 非法级别 "block"（合法集 = allow/ask/deny）→ DaAssetException（消息含
            // source=scenario:f10920-corrupt 与 reason=must-be-allow-ask-or-deny）。
            // 模型环境必须先合法（backend 完整性校验先于 actrule 装配——run-01 实测：
            // 裸栈死于 missing=[api_key]，到不了 actrule 面）
            Files.writeString(scenarioDir.resolve("actrule.yaml"),
                    "enabled: true\ndefaults:\n  \"*\": ask\n"
                            + "tools:\n  write_file: block\n");
            assertThatThrownBy(() -> startSut())
                    .as("场景级 actrule 非法级别必须使 SUT 装配失败（fail-fast）")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("process exited before becoming ready");
        } finally {
            // 即建即删（构造纪律：全局生效面不跨用例残留）
            deleteRecursively(BASEDIR.resolve("scenarios/f10920-corrupt"));
        }

        // 错误消息断言面：只读本轮启动新增的日志段（SUT 日志跨轮追加共享——run-01 教训）
        assertThat(logTail(logOffset)).as("本轮日志含 actrule 校验错误（来源与原因）")
                .contains("actrule invalid permission level")
                .contains("scenario:f10920-corrupt")
                .contains("must-be-allow-ask-or-deny");
        Allure.addAttachment("F109-20 证据", "text/plain",
                "scenarios/f10920-corrupt/governance/actrule.yaml 非法级别 → 启动失败"
                        + "（process exited before becoming ready）+ 新增日志段含 source/reason；"
                        + "corrupt 目录已在 finally 清除");
    }

    /** 场景级 actrule 为空文件 → DaAssetException("actrule yaml empty")——腐坏第二形态。 */
    @Test
    @Story("F109-20: actrule 资产空文件 → 装配失败 fail-fast")
    @DisplayName("F109-20: 场景级 actrule 空文件 → 启动失败")
    void emptyScenarioActruleFailsStartup() throws Exception {
        Path scenarioDir = BASEDIR.resolve("scenarios/f10920-empty/governance");
        Files.createDirectories(scenarioDir);
        long logOffset = logLength();
        try {
            Files.writeString(scenarioDir.resolve("actrule.yaml"), "");
            assertThatThrownBy(() -> startSut())
                    .as("空 actrule 必须使 SUT 装配失败")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("process exited before becoming ready");
        } finally {
            deleteRecursively(BASEDIR.resolve("scenarios/f10920-empty"));
        }
        assertThat(logTail(logOffset)).as("本轮日志含 empty/解析失败资产错误（含 source）")
                .contains("actrule yaml parse failed")
                .contains("scenario:f10920-empty");
    }

    private static SutStack startSut() {
        // 模型环境必须合法（backend fail-fast 先于 actrule 装配，run-01 实测）——
        // 本用例 SUT 不发请求，真实透传环境仅用于通过装配校验
        return DaCompatGuardrailSupport.guardrailStack(CONFIG, agent -> agent).start();
    }

    private static long logLength() {
        try {
            return Files.size(SUT_LOG);
        } catch (java.io.IOException absent) {
            return 0L;
        }
    }

    /** 只读 offset 之后的新增日志段（run-01 教训：SUT 日志跨 run 追加共享，全量断言会被历史污染）。 */
    private static String logTail(long offset) {
        try {
            if (offset >= Files.size(SUT_LOG)) {
                return "";
            }
            return Files.readString(SUT_LOG).substring((int) offset);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
