package com.huawei.ascend.sit.cases.e2e.deepanalyze_resume;

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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-110 启动期非法配置负向（GAP-110-cfg，覆盖扫描发现的缺口；用户 2026-11-12 指令）。
 * 参考面：FEAT-037 T02 `assertStartupFails`（ReactAgentMultiInstanceHostingBlackboxTest 同款）。
 *
 * <p>判据（被测制品 0.1.3-feat109111-r2 = agent-solution common@76bcad137）：
 * <ul>
 *   <li>七容量键中六个 int 键的 setter 全部 {@code requirePositive("<键名>", v)}——≤0 抛
 *       {@code IllegalStateException("deepanalyze.compat.<键名>=<值> must be positive")}
 *       （DaCompatProperties:273-372，错误消息**含键名**）；Spring 绑定失败 ⇒ SUT 进程
 *       启动失败退出。</li>
 *   <li>SutStack 启动失败形态：{@code IllegalStateException("process exited before becoming
 *       ready")}（与 FEAT-037 先例同款断言）。</li>
 *   <li>键名断言面：SUT stdout 日志（target/sit-logs/&lt;agent&gt;/stdout.log，金丝雀类同构定位）
 *       必须含 {@code deepanalyze.compat.<键名>}（绑定失败异常栈打印）。</li>
 *   <li>第 7 键 idempotency-wait-timeout 为 Duration 型（setter 251，无 requirePositive 校验面）——
 *       启动期校验面仅覆盖六 int 键；本用例全覆盖六键，第 7 键若需运行期校验另属运行期行为
 *       （不在 GAP-110-cfg 启动期范围内）。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110CfgStartupGateE2EIT {

    private static final TestConfig CONFIG = TestConfig.load();
    private static final Path SUT_LOG = Path.of(
            System.getProperty("basedir", System.getProperty("user.dir")),
            "target", "sit-logs", "deepanalyze-guardrail", "stdout.log");

    @Test
    @Story("F110-23 (GAP-110-cfg): 六容量键配 0/负数 → SUT 启动失败且错误消息含键名")
    @DisplayName("F110-23: 启动期非法配置负向矩阵")
    void invalidCapacityConfigFailsStartupWithKeyName() throws Exception {
        Map<String, String> invalidCases = new LinkedHashMap<>();
        invalidCases.put("max-request-body-bytes", "0");
        invalidCases.put("max-answer-chars", "-1");
        invalidCases.put("max-idempotency-key-chars", "0");
        invalidCases.put("max-tool-call-id-chars", "-5");
        invalidCases.put("steering-queue-capacity", "-1");
        invalidCases.put("early-answer-queue-capacity", "0");

        for (Map.Entry<String, String> bad : invalidCases.entrySet()) {
            String key = bad.getKey();
            String value = bad.getValue();
            // 启动失败断言（FEAT-037 先例同款形态）
            assertThatThrownBy(() -> startSutWith(key, value))
                    .as("键 %s=%s 必须使 SUT 启动失败", key, value)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("process exited before becoming ready");
        }

        // 键名断言：SUT 日志必须逐键出现 requirePositive 的错误消息（含键名与非法值）
        assertThat(Files.exists(SUT_LOG)).as("SUT 日志文件存在: " + SUT_LOG).isTrue();
        String log = Files.readString(SUT_LOG);
        for (Map.Entry<String, String> bad : invalidCases.entrySet()) {
            assertThat(log).as("启动失败错误消息含键名与非法值（%s=%s）", bad.getKey(), bad.getValue())
                    .contains("deepanalyze.compat." + bad.getKey() + "=" + bad.getValue());
        }
        Allure.addAttachment("F110-23 证据", "text/plain",
                "六键逐一非法值 → 启动失败（process exited before becoming ready）×6；"
                        + "SUT 日志逐键含 \"deepanalyze.compat.<键名>=<值> must be positive\"；"
                        + "日志=" + SUT_LOG);
    }

    /** 以非法配置起 SUT（方法内独立栈——不与共享实例互相污染）。 */
    private static SutStack startSutWith(String key, String value) throws Exception {
        return SutStack.builder(CONFIG).agent("deepanalyze-guardrail", agent -> agent
                        .property("deepanalyze.compat." + key, value))
                .start();
    }
}
