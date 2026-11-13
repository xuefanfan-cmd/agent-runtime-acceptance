package com.huawei.ascend.sit.cases.e2e.deepanalyze_resume;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;
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

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 同步 /run 任务的恢复（F110-14）。
 *
 * <p>判据（被测制品 0.1.3-feat109111 = agent-solution common@6fd13012b，DaCompatController
 * collectSyncResult:1757）：
 * <ul>
 *   <li>同步 /run 的挂起形态（core run 边界语义的同步面，L2 结构偏差已述）：任务转待答时
 *       collectSyncResult **立即返回** 200 {@code {taskId, status:"awaiting_answer", output,
 *       turnsUsed, usage}}——不阻塞等待；回答/恢复经 /answer、/resume 续跑。</li>
 *   <li>F110-14 主断言：同步发起的任务挂起后经**同一恢复端点** /resume 受理 202，
 *       事件归原 taskId（status 投影 taskId 一致 + 续跑后 status=completed + 文件落地）。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110SyncRunE2EIT {

    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(150);
    private static final Path DUMP_DIR = Path.of("target", "f110-dump");

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat110-syncrun-workspace");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString()))
                .start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaResumeSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @Story("F110-14: 同步 /run 任务待答后经同一 /resume 端点恢复，事件归原 taskId")
    @DisplayName("F110-14: 同步任务挂起与恢复")
    void syncRunTaskResumableViaSameEndpoint() throws Exception {
        // ---- 同步发起 write_file 任务（挂起即返回 awaiting_answer）----
        String session = "sess-f110-sync-" + System.nanoTime();
        String marker = "SYNC-MARKER-" + System.nanoTime();
        String fileName = "f110-sync-" + System.nanoTime() + ".md";
        String input = "请使用 write_file 工具创建文件 " + fileName + "，内容为：" + marker
                + "。只创建这一个文件，不要执行任何其他工具，完成后直接结束任务。";
        HttpResponse<String> runResp = DaCompatGuardrailSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"" + DaResumeSupport.escape(input) + "\"}",
                Duration.ofSeconds(240));
        assertThat(runResp.statusCode()).as("同步 /run 受理 200").isEqualTo(200);
        assertThat(DaResumeSupport.jsonString(runResp.body(), "status"))
                .as("挂起形态：同步端点立即返回 awaiting_answer（collectSyncResult 判据）")
                .isEqualTo("awaiting_answer");
        String taskId = DaResumeSupport.jsonString(runResp.body(), "taskId");
        assertThat(taskId).as("同步响应回显 taskId").isNotBlank();

        // ---- status 取证：待答可恢复 ----
        HttpResponse<String> st = DaResumeSupport.status(http, base, taskId, session, Duration.ofSeconds(15));
        assertThat(DaResumeSupport.jsonString(st.body(), "resumeState"))
                .isEqualTo("awaiting_answer");
        assertThat(DaResumeSupport.jsonBool(st.body(), "resumable")).isTrue();

        // ---- 同一恢复端点 /resume ----
        HttpResponse<String> resp = DaResumeSupport.resume(http, base, taskId, session,
                "idem-sync-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(resp.statusCode()).as("/resume 202").isEqualTo(202);
        assertThat(DaResumeSupport.jsonString(resp.body(), "taskId"))
                .as("202 体回显同步任务的 taskId").isEqualTo(taskId);

        // ---- 续跑完成：事件归原 taskId ----
        HttpResponse<String> st2 = awaitTerminalStatus(taskId, session, 150_000);
        assertThat(DaResumeSupport.jsonString(st2.body(), "status"))
                .as("续跑后终态 completed").isEqualTo("completed");
        assertThat(DaResumeSupport.jsonString(st2.body(), "id"))
                .as("status 投影 id=原 taskId（事件归原任务）").isEqualTo(taskId);

        // ---- 文件落地（原输入只执行一次）----
        Path expected = workspaceRoot.resolve(session).resolve(fileName);
        assertThat(Files.exists(expected)).as("文件已写: " + expected).isTrue();
        assertThat(Files.readString(expected).split(marker, -1).length - 1)
                .as("marker 恰一次").isEqualTo(1);

        // ---- SSE 面取证：终态事件在原任务流上（重连重放含 done → reconnect_done 哨兵收尾）----
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openStream(http, base, taskId,
                session, null, Duration.ofSeconds(60));
        List<SseFrame> tail = new ArrayList<>();
        try {
            DaCompatGuardrailSupport.readFramesInto(stream, tail::add, f -> false, SSE_READ_TIMEOUT);
        } finally {
            stream.body().close();
        }
        DaResumeSupport.dumpFrames(DUMP_DIR, session + "-sync.txt",
                "case=F110-14 runBody=" + runResp.body() + " resume=" + resp.body()
                        + " finalStatus=" + st2.body(), tail);
        assertThat(DaResumeSupport.countEvent(tail, DaResumeSupport.EVENT_DONE))
                .as("原任务流上业务 done 恰一次（终态事件归原 taskId；reconnect_done 哨兵不计）")
                .isEqualTo(1);
        Allure.addAttachment("F110-14 证据", "text/plain",
                "taskId=" + taskId + " runStatus=awaiting_answer→resume 202→completed; tailFrames="
                        + tail.size());
    }

    private static HttpResponse<String> awaitTerminalStatus(String taskId, String session, long timeoutMillis)
            throws Exception {
        HttpResponse<String> last = null;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            last = DaResumeSupport.status(http, base, taskId, session, Duration.ofSeconds(15));
            String s = DaResumeSupport.jsonString(last.body(), "status");
            if ("completed".equals(s) || "failed".equals(s) || "cancelled".equals(s)) {
                return last;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("150s 内未到终态；末次=" + (last == null ? "<null>" : last.body()));
    }
}
