package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-004.remote-task-id-not-exposed — 远端 Task 标识不进入客户端可见投影(testplan B3).
 *
 * <p><b>特性依据</b>:FEAT-004 §3.1「远端 taskId/contextId 标识下游 runtime 受理并执行的实际远端 Task;
 * 当前 runtime 只能关联和代理」+ §5.6「远端 Task 标识属于 runtime 内部路由状态,不进入客户端可见投影」。
 *
 * <p><b>L2 自承差距看守</b>(Feat-Func-004 §1.3):「远端 Task ID 对外暴露与需求边界不完全一致:当前
 * agentEvent.source/target.taskId 会在流式父输出中暴露」——本用例作为该差距的黑盒看守:契约对齐前若
 * 实现继续暴露则 FAIL(期望红);对齐后(隐藏或契约改口)自动转绿或调整断言。
 *
 * <p><b>观察面</b>:wire 层直发 SendStreamingMessage 收集完整 SSE 原文 + GetTask 快照原文(不经 SDK,
 * 保证看到的就是客户端可见的字节);远端 Task 标识集合从 search 侧 stdout 日志提取(测试框架自有日志,
 * 非 SUT 内部状态)。断言:remoteIds 中任何一个都不得出现在父面 SSE 或 GetTask 文本中。
 */
@Disabled("契约冲突待对齐(2026-09-01):FEAT-004 §5.6 要求远端 taskId 不进客户端投影,但 FEAT-028 §2.1/§5.0.1 要求客户端按 agentEvent.source.taskId 分流渲染多子任务流式输出(FEAT-027 数据面)。实测当前实现按 FEAT-028 口径暴露(artifactId delegation 拼接 + agentEvent.target.taskId)。两档对齐后按结论启用或反转断言。")
@Tag("integration")
@Tag("deepagent")
@Tag("feat-004")
@Feature("FEAT-004: 任务驱动远程智能体通信")
@Story("da.remote-task-id-not-exposed: 远端 Task 标识不进入客户端可见投影(§3.1/§5.6,L2 §1.3 差距看守)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteTaskIdNotExposedTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";
    private static final long STREAM_TIMEOUT_S = 120;
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private TestConfig config;
    private SutStack searchStack;
    private SutStack deepStack;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    void startStack() {
        config = TestConfig.load();
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        deepStack = SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", searchBaseUrl)
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1"))
                .start();
    }

    @AfterAll
    void tearDown() {
        if (deepStack != null) {
            deepStack.close();
        }
        if (searchStack != null) {
            searchStack.close();
        }
    }

    @Test
    @DisplayName("FEAT-004.remote-task-id-not-exposed: 父面 SSE 与 GetTask 快照不得含 search 侧任务标识")
    void remoteTaskIdsMustNotAppearInParentProjection() throws Exception {
        String contextId = "ctx-feat004-noexpose-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"jsonrpc":"2.0","id":"b3-noexpose","method":"SendStreamingMessage","params":{"message":{
                "role":"ROLE_USER","messageId":"%s","contextId":"%s",
                "parts":[{"text":"帮我搜索 2026 年 7 月 15 日全球黄金价格盘中最高价的准确数字,直接给出数字和单位"}]}}}
                """.formatted(UUID.randomUUID(), contextId);

        String drBase = deepStack.baseUrl(DEEP_RESEARCH);
        HttpRequest streamReq = HttpRequest.newBuilder(URI.create(drBase + "/a2a"))
                .timeout(Duration.ofSeconds(STREAM_TIMEOUT_S))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        // wire 层收集完整 SSE 原文(客户端可见的每一个字节)
        HttpResponse<String> streamResp = http.send(streamReq, HttpResponse.BodyHandlers.ofString());
        String sseText = streamResp.body();
        assertThat(streamResp.statusCode()).as("SendStreamingMessage HTTP status").isEqualTo(200);
        assertThat(sseText).as("SSE 流应达到终态帧").containsAnyOf(
                "TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_INPUT_REQUIRED");

        // 父 taskId + 请求侧标识(排除集)
        Set<String> parentIds = extractUuids(sseText + " " + contextId + " " + body);

        // GetTask 快照原文
        String parentTaskId = firstTaskId(sseText);
        HttpRequest getTaskReq = HttpRequest.newBuilder(URI.create(drBase + "/a2a"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"b3-gt\",\"method\":\"GetTask\",\"params\":{\"id\":\""
                                + parentTaskId + "\"}}", StandardCharsets.UTF_8))
                .build();
        String getTaskJson = http.send(getTaskReq, HttpResponse.BodyHandlers.ofString()).body();

        // search 侧任务标识集合(来自测试框架为 search 重定向的 stdout 日志)
        Path searchLog = Path.of("target/sit-logs/search/stdout.log");
        Assumptions.assumeTrue(Files.exists(searchLog),
                "INCONCLUSIVE: search stdout 日志不存在,无法建立远端标识集合 — " + searchLog);
        String searchLogText = Files.readString(searchLog, StandardCharsets.UTF_8);
        Set<String> remoteIds = extractUuids(searchLogText);
        // 父面出现过的标识(父 taskId/contextId/messageId/artifactId 等)不属于"远端内部标识"
        remoteIds.removeAll(parentIds);
        Assumptions.assumeTrue(!remoteIds.isEmpty(),
                "INCONCLUSIVE: search 日志未产生独立于父面的任务标识(可能下游未被调用)——无法断言");

        // 层 1:远端标识不得出现在父面 SSE
        Set<String> leakedInSse = new LinkedHashSet<>();
        Set<String> leakedInSnapshot = new LinkedHashSet<>();
        for (String id : remoteIds) {
            if (sseText.contains(id)) {
                leakedInSse.add(id);
            }
            if (getTaskJson.contains(id)) {
                leakedInSnapshot.add(id);
            }
        }
        assertThat(leakedInSse)
                .as("FEAT-004 §5.6: 远端 Task 标识不得出现在流式父输出(L2 §1.3 自承 agentEvent 暴露差距的看守)\n"
                        + "  remoteIds 总数=%d  泄漏=%s", remoteIds.size(), leakedInSse)
                .isEmpty();
        assertThat(leakedInSnapshot)
                .as("FEAT-004 §5.6: 远端 Task 标识不得出现在 GetTask 快照\n"
                        + "  remoteIds 总数=%d  泄漏=%s", remoteIds.size(), leakedInSnapshot)
                .isEmpty();
    }

    private static Set<String> extractUuids(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = UUID_PATTERN.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static String firstTaskId(String sseText) {
        Matcher m = Pattern.compile("\"taskId\":\"([0-9a-f-]{36})\"").matcher(sseText);
        assertThat(m.find()).as("SSE 中应含 taskId").isTrue();
        return m.group(1);
    }
}
