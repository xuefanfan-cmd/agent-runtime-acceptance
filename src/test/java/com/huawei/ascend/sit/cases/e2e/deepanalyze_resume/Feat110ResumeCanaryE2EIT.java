package com.huawei.ascend.sit.cases.e2e.deepanalyze_resume;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 恢复面金丝雀（F110-17，独立 SUT 实例——503 路径需要 max-concurrent-tasks=1）。
 *
 * <p>判据（被测制品 0.1.3-feat109111 = agent-solution common@6fd13012b）：
 * <ul>
 *   <li>L1 出口 16 / §5.1.8：恢复各路径放合成金丝雀 → 恢复日志与新增幂等记录无明文；
 *       L2 §4.3：幂等记录只存 64hex SHA-256 摘要（digestOf）。</li>
 *   <li>日志取证面：SUT stdout = {@code {basedir}/target/sit-logs/<agent>/stdout.log}
 *       （ProcessLauncher:71/336/415 同构判据——LOG_HOME 注入 per-agent 目录）。
 *       金丝雀串含 nanoTime 唯一 ⇒ 与历史轮日志天然隔离，grep 0 命中即本轮断言。</li>
 *   <li>路径覆盖与 Oracle 注记：①首次受理（resume accepted）②幂等命中（同键重放
 *       resume replayed——与①共用同一金丝雀 answer，两路径日志行不同、串一致）③503
 *       受理前失败回滚（resume rolled back outcome=busy）④受理后失败路径**以拒绝深渗
 *       替代**（answer=非批准词 → feedback=金丝雀原文进模型请求 payload——渗透深度不低于
 *       失败路径；受理后失败与 F110-12 绑定、必败语料不可控，登记替代并标 Oracle 注记）。</li>
 *   <li>事件流明文豁免注记：ask_user_answered 事件携带 answer 原文是产品有意行为
 *       （页面回显，#515），不属「日志与幂等记录」面——金丝雀断言只对 SUT 日志文件。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110ResumeCanaryE2EIT {

    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(150);
    private static final long SUSPEND_TIMEOUT_MILLIS = 120_000;

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;
    private static Path sutLog;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat110-canary-workspace");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                        .property("deepanalyze.compat.max-concurrent-tasks", "1"))
                .start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaResumeSupport.AGENT).getBaseUrl();
        // SUT 日志定位（ProcessLauncher.resolveLogDir 同构：sut.logging.dir → basedir/target/sit-logs）
        Path logRoot = Path.of(System.getProperty("basedir", System.getProperty("user.dir")),
                "target", "sit-logs");
        sutLog = logRoot.resolve(DaResumeSupport.AGENT).resolve("stdout.log");
        assertThat(Files.exists(sutLog)).as("SUT 日志文件必须存在: " + sutLog).isTrue();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @Story("F110-17: 首次受理/幂等命中/503 回滚/拒绝深渗四路径金丝雀——SUT 日志无明文")
    @DisplayName("F110-17: 恢复面金丝雀取证")
    void canaryNeverInSutLogs() throws Exception {
        String c1 = "CANARY-C1-" + System.nanoTime(); // 首次受理+幂等命中（同键同载荷共用）
        String c3 = "CANARY-C3-" + System.nanoTime(); // 503 回滚+同键重试成功
        String c4 = "CANARY-C4-" + System.nanoTime(); // 拒绝深渗（feedback 进模型 payload）

        // ---- 路径①②：首次受理 + 同键重放（answer=C1）----
        DaResumeSupport.SuspendedTask a = startSuspendedTaskRetry("canary-a",
                "CANARY-FILE-A-" + System.nanoTime());
        String keyA = "idem-canary-a-" + System.nanoTime();
        HttpResponse<String> first = DaResumeSupport.resume(http, base, a.taskId(), a.sessionId(),
                keyA, c1, null, Duration.ofSeconds(30));
        assertThat(first.statusCode()).as("路径①首次受理 202").isEqualTo(202);
        HttpResponse<String> replay = DaResumeSupport.resume(http, base, a.taskId(), a.sessionId(),
                keyA, c1, null, Duration.ofSeconds(30));
        assertThat(replay.statusCode()).as("路径②幂等命中 200").isEqualTo(200);
        assertThat(DaResumeSupport.jsonBool(replay.body(), "duplicate")).isTrue();
        a.awaitReader(150_000);

        // ---- 路径④：拒绝深渗（answer=C4，非批准词 → denied + feedback=C4 进模型请求）----
        DaResumeSupport.SuspendedTask b = startSuspendedTaskRetry("canary-b",
                "CANARY-FILE-B-" + System.nanoTime());
        HttpResponse<String> deny = DaResumeSupport.resume(http, base, b.taskId(), b.sessionId(),
                "idem-canary-b-" + System.nanoTime(), c4, null, Duration.ofSeconds(30));
        assertThat(deny.statusCode()).as("路径④拒绝受理 202").isEqualTo(202);
        b.awaitReader(150_000);
        assertThat(Files.exists(workspaceRoot.resolve(b.sessionId()).resolve(b.fileName())))
                .as("拒绝路径文件不落地").isFalse();

        // ---- 路径③：503 回滚（池容量=1，C 占池后 D 的新键 resume 503）----
        DaResumeSupport.SuspendedTask c = startSuspendedTaskRetry("canary-c",
                "CANARY-FILE-C-" + System.nanoTime());
        DaResumeSupport.SuspendedTask d = startSuspendedTaskRetry("canary-d",
                "CANARY-FILE-D-" + System.nanoTime());
        HttpResponse<String> occupy = DaResumeSupport.resume(http, base, c.taskId(), c.sessionId(),
                "idem-canary-c-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(occupy.statusCode()).as("占池任务受理 202").isEqualTo(202);
        String keyD = "idem-canary-d-" + System.nanoTime();
        HttpResponse<String> busy = DaResumeSupport.resume(http, base, d.taskId(), d.sessionId(),
                keyD, c3, null, Duration.ofSeconds(30));
        assertThat(busy.statusCode()).as("路径③池满 503").isEqualTo(503);
        // 同键重试成功（c3 金丝雀 answer 经历 503 请求、回滚日志、重试成功续跑三个日志面）。
        // run-20 教训：C 完成（done）后池槽释放仍可能滞后——同键重试本身也是合法重试
        // （503=受理前失败键已回滚，F110-11 语义），退避循环到 202。
        c.awaitReader(150_000);
        HttpResponse<String> retry = null;
        long retryDeadline = System.currentTimeMillis() + 60_000;
        while (true) {
            retry = DaResumeSupport.resume(http, base, d.taskId(), d.sessionId(),
                    keyD, c3, null, Duration.ofSeconds(30));
            if (retry.statusCode() == 202 || System.currentTimeMillis() > retryDeadline) {
                break;
            }
            Thread.sleep(2_000);
        }
        assertThat(retry.statusCode()).as("503 后同键重试 202（键未烧，池槽释放滞后时退避重试）")
                .isEqualTo(202);
        d.awaitReader(150_000);

        // ---- 主断言：SUT 日志全文无金丝雀明文 ----
        String log = Files.readString(sutLog);
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String canary : new String[] {c1, c3, c4}) {
            hits.put(canary, countOccurrences(log, canary));
        }
        assertThat(hits).as("SUT 日志不得出现任何金丝雀明文（日志脱敏面，L1 出口16/§5.1.8）")
                .allSatisfy((canary, count) -> assertThat(count)
                        .as("金丝雀 %s 在 SUT 日志中的出现次数", canary).isZero());
        Allure.addAttachment("F110-17 证据", "text/plain",
                "logFile=" + sutLog + " logBytes=" + log.length()
                        + "; canary 命中计数=" + hits
                        + "; 路径覆盖=accepted/replayed/rolled-back-busy/denied-feedback"
                        + "（受理后失败路径以拒绝深渗替代，见类注记）");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * 池容量=1 实例上的任务发起重试（run-19 教训：done 事件先于执行池释放——
     * done 后立刻发起下一任务会撞 503，属环境形态非产品缺陷；退避重试至受理）。
     */
    private static DaResumeSupport.SuspendedTask startSuspendedTaskRetry(String suffix, String marker)
            throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            try {
                return DaResumeSupport.startSuspendedTask(http, base, workspaceRoot, suffix, marker,
                        SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);
            } catch (IllegalStateException busy) {
                if (!busy.getMessage().contains("HTTP 503")
                        || System.currentTimeMillis() > deadline) {
                    throw busy;
                }
                Thread.sleep(2_000);
            }
        }
    }
}
