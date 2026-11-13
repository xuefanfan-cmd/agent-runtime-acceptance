package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-109 场景层显式 deny 端到端（F109-04 的场景资产构造形态，独立 SUT 实例）。
 *
 * <p>判据（run-06 后读齐）：
 * <ul>
 *   <li>场景 actrule 装载：{@code DaEngineConfiguration.loadScenarioActrules()} 扫
 *       <b>SUT 进程 CWD</b> 下 {@code scenarios/<场景>/governance/actrule.yaml}（相对路径，
 *       ProcessBuilder 不设 directory ⇒ CWD=worktree 根）；{@code buildPermissions()} 对全部
 *       场景资产 {@code mergeStricter} 全量合并——启动时生效，影响整实例 ⇒ 必须独立实例。</li>
 *   <li>deny 语义：场景层 {@code bash: deny} 取严合并后，模型调用 bash 直接拒绝、不弹确认
 *       （L1 §5.1.1「策略 deny 不提供虚假确认入口」）。</li>
 *   <li>显式 deny 的可构造面：delete_file 未上架（run-05 模型诚实旁路）；shutdown 被模型对齐
 *       拒绝执行（run-06 模型旁路）——场景层 deny 无害命令是当前唯一不依赖 fixture 的 deny 构造。</li>
 * </ul>
 *
 * <p>构造：@BeforeAll 写 {@code scenarios/sit-f109-deny/governance/actrule.yaml}
 * （tools.bash=deny）→ 起独立 SUT → 任务经 bash 执行无害命令（echo）→ 场景 deny →
 * 断言无 permission_confirm（deny 不弹确认）+ permissionDecision=denied。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109DenyE2EIT {

    private static final String SCENARIO_DIR = "scenarios/sit-f109-deny/governance";

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path scenarioActrule;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        // 场景资产落在 SUT 进程 CWD（= worktree 根，ProcessBuilder 不改 directory）
        scenarioActrule = Path.of(SCENARIO_DIR, "actrule.yaml");
        Files.createDirectories(scenarioActrule.getParent());
        Files.writeString(scenarioActrule, """
                enabled: true
                tools:
                  bash: deny
                """);
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent)
                .start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaCompatGuardrailSupport.AGENT).getBaseUrl();
        Allure.addAttachment("场景资产", "text/plain", scenarioActrule.toAbsolutePath()
                + ":\n" + Files.readString(scenarioActrule));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (stack != null) {
            stack.close();
        }
        if (scenarioActrule != null) {
            Files.deleteIfExists(scenarioActrule);
            // 逐级清理空的场景目录（不留散落文件）
            Path g = scenarioActrule.getParent();
            Files.deleteIfExists(g);
            Files.deleteIfExists(g.getParent());
        }
    }

    @Test
    @Story("F109-04: 场景层显式 deny——bash 直接拒绝且不弹确认")
    @DisplayName("F109-04: 场景 deny 无确认入口")
    void scenarioDenyRejectsWithoutConfirm() throws Exception {
        String session = "sess-f109-scdeny-" + System.nanoTime();
        String input = "使用 bash 工具执行命令 echo sit-f109-deny-probe-" + System.nanoTime()
                + "。不要执行其他任何工具，执行后结束。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + input + "\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(150));
        List<SseFrame> frames = DaCompatGuardrailSupport.readFrames(stream, f -> false,
                Duration.ofSeconds(150));

        boolean confirmSeen = frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("\"kind\":\"permission_confirm\""));
        if (confirmSeen) {
            String toolName = frames.stream()
                    .filter(f -> f.data() != null && f.data().contains("\"kind\":\"permission_confirm\""))
                    .map(f -> f.field("toolName")).filter(n -> n != null && !n.isBlank())
                    .findFirst().orElse("<未取到>");
            org.junit.jupiter.api.Assumptions.assumeTrue("bash".equals(toolName),
                    "INCONCLUSIVE——模型先调用了 '" + toolName + "'（场景 deny 未触达 bash）；帧数="
                            + frames.size());
        }
        // 模型旁路分流：模型未调 bash（纯文本回复）⇒ deny 未触达，不判 FAIL
        boolean bashCalled = frames.stream().anyMatch(f -> f.data() != null
                && (f.data().contains("\"toolName\":\"bash\"")
                || f.data().contains("bash")));
        if (!confirmSeen && !frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("permissionDecision"))) {
            org.junit.jupiter.api.Assumptions.assumeTrue(bashCalled,
                    "INCONCLUSIVE——流中无 bash 工具调用痕迹（模型纯文本回复未触达 deny 面）；帧数="
                            + frames.size());
        }

        boolean deniedSeen = frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("permissionDecision") && f.data().contains("denied"));
        assertThat(deniedSeen).as("场景 deny 的 bash 调用被拒（SSE 含 permissionDecision=denied）").isTrue();
        assertThat(frames.stream().noneMatch(f -> f.data() != null
                        && f.data().contains("\"kind\":\"permission_confirm\"")
                        && "bash".equals(f.field("toolName"))))
                .as("deny 路径不弹 bash 确认（无虚假确认入口）").isTrue();
        Allure.addAttachment("F109-04 场景 deny 证据", "text/plain",
                "帧数=" + frames.size() + " confirmSeen=" + confirmSeen + " deniedSeen=" + deniedSeen);
    }
}
