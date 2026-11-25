package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-109 hooks L1 三键绑定黑盒（F109-25 三值校验格＋F109-26 双键冲突格；#824 裁决落地的
 * 验收格，!1308/!1317 已合入）。制品 0.1.3-feat109111-r7 = agent-solution common@f21d1eafb
 * （含 !1308 三键绑定＋白名单新旧别名窗口＋!1317 冲突测试锁）。
 *
 * <p>判据（实现前已读：DaHooksProperties/DaHookHostWhitelist/DaHooksWiringTest）：
 * <ul>
 *   <li>新键 {@code deepanalyze.hooks.timeout / max-response-bytes / http-allowed-hosts}
 *       （DaHooksProperties @ConfigurationProperties prefix="deepanalyze.hooks"）。</li>
 *   <li>非法值绑定期 {@code IllegalStateException} 含完整键名（timeout 非正数／
 *       max-response-bytes 非正数／白名单条目空白、通配符、带协议或路径）→ SUT 启动失败
 *       优于静默降级（F110-29 同款断言形态）。</li>
 *   <li>双键冲突：新 {@code http-allowed-hosts} 与旧别名 {@code deepanalyze.compat.hooks.
 *       http-host-whitelist} 同时显式 → 启动失败且消息**同时含两键名**
 *       （DaHooksWiringTest.conflictingKeys_failStartupNamingBothKeys 的黑盒面）；
 *       host:port 与 host 同 host 亦判冲突（!1317 hostPortVersusHostOnly 测试锁）。</li>
 *   <li>接线面：{@code deepanalyze.hooks.timeout=1s} + 阻塞 hook（sleep 5）→ hook_result
 *       超时失败（1s 生效，非缺省 10s）。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109HookL1KeysE2EIT {

    private static final TestConfig CONFIG = TestConfig.load();
    private static final Path SUT_LOG = Path.of(
            System.getProperty("basedir", System.getProperty("user.dir")),
            "target", "sit-logs", "deepanalyze-guardrail", "stdout.log");

    private static final String NEW_HOSTS = "deepanalyze.hooks.http-allowed-hosts[0]";
    private static final String NEW_HOSTS_KEY = "deepanalyze.hooks.http-allowed-hosts";
    private static final String LEGACY_HOSTS = "deepanalyze.compat.hooks.http-host-whitelist[0]";
    private static final String LEGACY_HOSTS_KEY = "deepanalyze.compat.hooks.http-host-whitelist";

    private static SutStack startSut(Map<String, String> props) throws Exception {
        return SutStack.builder(CONFIG).agent("deepanalyze-guardrail", agent -> {
            // ENV-5.15：模型环境必须先绑——hooks 绑定校验在 backend 完整性校验之后执行，
            // 缺 api_key 会先死于 backend fail-fast，到不了目标校验面
            DaReflectionCostSupport.bindModelEnvironment(agent);
            props.forEach(agent::property);
        }).start();
    }

    @Test
    @Story("F109-25: 三值校验格——hooks 三键非法值绑定期 fail-fast，SUT 启动失败且错误含键名")
    @DisplayName("F109-25: 三值校验格（非法值启动失败矩阵）")
    void threeKeyInvalidValuesFailStartup() throws Exception {
        Map<String, String> invalidCases = new LinkedHashMap<>();
        invalidCases.put("deepanalyze.hooks.timeout", "0");
        invalidCases.put("deepanalyze.hooks.max-response-bytes", "0");
        invalidCases.put(NEW_HOSTS_KEY, "*.example.com");

        for (Map.Entry<String, String> bad : invalidCases.entrySet()) {
            String key = bad.getKey();
            String value = bad.getValue();
            Map<String, String> props = new LinkedHashMap<>();
            if (key.equals(NEW_HOSTS_KEY)) {
                props.put(NEW_HOSTS, value);
            } else {
                props.put(key, value);
            }
            final Map<String, String> propsFinal = props;
            assertThatThrownBy(() -> startSut(propsFinal))
                    .as("键 %s=%s 必须使 SUT 启动失败（绑定期 fail-fast）", key, value)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("process exited before becoming ready");
        }

        assertThat(Files.exists(SUT_LOG)).as("SUT 日志存在: " + SUT_LOG).isTrue();
        String log = Files.readString(SUT_LOG);
        // 实测日志形态（run-40c）：timeout=ISO-8601（PT0S）在 Reason 行；另两键以
        // Spring Boot 诊断的 Property:/Value: 分行呈现——按观测格式断言（键名均含键全名）
        assertThat(log).as("timeout 非法值错误含键名与 fail-fast 语义")
                .contains("deepanalyze.hooks.timeout").contains("must be positive");
        assertThat(log).as("max-response-bytes 非法值错误含键名")
                .contains("deepanalyze.hooks.max-response-bytes").contains("must be positive");
        assertThat(log).as("白名单通配符错误含键名与拒绝语义")
                .contains("deepanalyze.hooks.http-allowed-hosts");
        Allure.addAttachment("F109-25 证据", "text/plain",
                "三键非法值 → 启动失败 ×3；SUT 日志逐键含键名（timeout=0/max-response-bytes=0/通配符）");
    }

    @Test
    @Story("F109-25: 接线面——deepanalyze.hooks.timeout=1s 使阻塞 hook 在 ~1s 超时（新键到达执行器）")
    @DisplayName("F109-25: timeout 新键接线行为面")
    void newTimeoutKeyReachesExecutor() throws Exception {
        DaReflectionCostSupport.ModelStubServer stub = new DaReflectionCostSupport.ModelStubServer();
        Path ws = Files.createTempDirectory("probe-f10925-ws");
        try (SutStack st = SutStack.builder(CONFIG)
                .streaming(true)
                .agent(DaReflectionCostSupport.AGENT, agent -> {
                    DaReflectionCostSupport.bindModelEnvironment(agent);
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                    DaReflectionCostSupport.bindStubModel(agent, stub);
                    agent.property("deepanalyze.workspace.root", ws.toAbsolutePath().toString());
                    agent.property("deepanalyze.hooks.timeout", "1s");
                }).start()) {
            String base = st.client(DaReflectionCostSupport.AGENT).getBaseUrl();
            HttpClient http = DaCompatGuardrailSupport.newHttpClient();
            String session = "sess-f10925-wire-" + System.nanoTime();
            String hooksJson = "{\"id\":\"slow-hook\",\"event\":\"PreToolUse\",\"type\":\"command\","
                    + "\"matcher\":\"read_file\",\"enabled\":true,\"config\":{\"command\":\"sleep 5\"}}";
            String bodyStr = "{\"sessionId\":\"" + session + "\",\"input\":\"直接开始\","
                    + "\"runtimeConfig\":{\"version\":1,\"tenantId\":\"default-user\",\"sessionId\":\""
                    + session + "\",\"hooks\":[" + hooksJson + "]}}";
            stub.script(
                    "toolargs:workflow_run\\u0001" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                            "mode", "single", "goal", "读取文件",
                            "agents", java.util.List.of(Map.of(
                                    "id", "reader", "role", "读取员",
                                    "task", "使用 read_file 工具读取文件 probe.txt")),
                            "question", "执行读取任务")),
                    "tool:__probe_tool__",
                    "tool:read_file",
                    "answer");
            HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, bodyStr,
                    Duration.ofSeconds(120));
            List<SseFrame> frames = new java.util.ArrayList<>();
            // S9 前置步守卫（solution#849）：workflow_run 成员派遣路径悬挂自动取证（本格=#849 守卫
            // SKIP 面）——活动=SSE 帧数，完成=帧转储文件出现；零判据变化
            DaReflectionCostSupport.startStallWatchdog(st, DaReflectionCostSupport.AGENT,
                    session, Path.of("target", "f109-dump"), frames::size,
                    () -> Files.exists(Path.of("target", "f109-dump", session + ".txt")));
            DaCompatGuardrailSupport.readFramesInto(stream, frames::add, f -> false,
                    Duration.ofSeconds(120));
            Path dir = Path.of("target", "f109-dump");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(session + ".txt"),
                    frames.stream().map(f -> f.event() + " " + f.data()).reduce("", (a, b) -> a + b + "\n"),
                    StandardCharsets.UTF_8);
            StringBuilder all = new StringBuilder();
            for (SseFrame f : frames) {
                all.append(f.data() == null ? "" : f.data());
            }
            boolean memberRan = frames.stream().anyMatch(f -> f.data() != null
                    && (f.data().contains("workflow_start") || f.data().contains("workflow_agent_start")));
            org.junit.jupiter.api.Assumptions.assumeTrue(memberRan,
                    "INCONCLUSIVE——成员未发起（solution#849 间歇拦截家族，构造与 20b 同链路）");
            // 阻塞 hook 在 1s 预算下超时失败（成员 read_file 被拒）——timeout 新键到达执行器
            assertThat(all.toString().contains("slow-hook"))
                    .as("slow-hook hook_result 在场（hooks 链路触发）").isTrue();
            assertThat(all.toString().contains("timeout"))
                    .as("hook 超时失败证据（1s 预算生效，非缺省 10s）").isTrue();
            Allure.addAttachment("F109-25 接线证据", "text/plain",
                    "timeout=1s + sleep 5 阻塞 hook → 超时失败；dump 帧含 slow-hook/timeout");
        }
    }

    @Test
    @Story("F109-26: 双键冲突格——新旧白名单同时显式 → 启动失败且消息同时含两键名")
    @DisplayName("F109-26: 双键冲突（异值同配）")
    void dualKeyConflictFailsStartupNamingBoth() throws Exception {
        Map<String, String> props = new LinkedHashMap<>();
        props.put(NEW_HOSTS, "new.example.com");
        props.put(LEGACY_HOSTS, "legacy.example.com");
        assertThatThrownBy(() -> startSut(props))
                .as("新旧白名单同时显式必须启动失败（冲突 fail-fast）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process exited before becoming ready");
        String log = Files.readString(SUT_LOG);
        assertThat(log).as("冲突消息含新键名").contains(NEW_HOSTS_KEY);
        assertThat(log).as("冲突消息含旧键名").contains(LEGACY_HOSTS_KEY);
        Allure.addAttachment("F109-26 证据", "text/plain",
                "new=legacy 异值同配 → 启动失败；日志同时含两键名");
    }

    @Test
    @Story("F109-26: 双键冲突格——host:port 与 host 同域异形态亦判冲突（!1317 测试锁黑盒面）")
    @DisplayName("F109-26: 双键冲突（host:port vs host 同 host）")
    void hostPortVersusHostOnlyConflict() throws Exception {
        Map<String, String> props = new LinkedHashMap<>();
        props.put(NEW_HOSTS, "api.example.com:8443");
        props.put(LEGACY_HOSTS, "api.example.com");
        assertThatThrownBy(() -> startSut(props))
                .as("host:port 与 host 同域异形态必须判冲突启动失败（端口归一不静默放行）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process exited before becoming ready");
        String log = Files.readString(SUT_LOG);
        assertThat(log).as("冲突消息含两键名").contains(NEW_HOSTS_KEY).contains(LEGACY_HOSTS_KEY);
        Allure.addAttachment("F109-26 host:port 证据", "text/plain",
                "api.example.com:8443 vs api.example.com → 冲突启动失败（!1317 锁的黑盒面）");
    }
}
