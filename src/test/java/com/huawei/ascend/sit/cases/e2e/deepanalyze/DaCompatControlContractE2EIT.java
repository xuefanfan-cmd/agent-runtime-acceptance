package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054：DA 兼容适配层的**外部契约**（控制面与错误面），只覆盖不依赖"任务能跑通"的部分。
 *
 * <p>覆盖场景：{@code da.compat.cancel}、{@code da.compat.answer}、{@code da.compat.inject}、
 * {@code da.compat.run-sync}、{@code da.compat.status-tasks}、{@code da.compat.errors}、
 * {@code da.compat.skills-read}、{@code da.compat.providers-test}。
 *
 * <p>本类只验证"输入非法 / 任务不存在 / 数据面未配置"这些**不需要模型与工具**的路径。
 * 成功路径（真实任务闭环、取消终态组、同步执行聚合、属主绑定）依赖
 * {@code GAP-054-01}（工具团队最小工具集）与可用的模型配置，另行登记，不在本类臆造。
 *
 * <p>Oracle 来源：特性 §4 端点明细表；L2 §2.11 端点契约与错误表面（Oracle 强度受限，
 * 见 {@code GAP-054-06}：DA 源仓不可访问，事件与文案以 L2 转写为口径）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaCompatControlContractE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";
    private static final String UNKNOWN_TASK = "no-such-task-054";
    private static final String UNKNOWN_SESSION = "no-such-session-054";

    private HttpClient http;
    private String base;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(AGENT);
    }

    private void initClient() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            base = client(AGENT).getBaseUrl();
        }
    }

    // ---- da.compat.cancel：不存在或非运行中的任务 → 404 ----

    @Test
    @Story("da.compat.cancel: 取消非运行中任务返回 404")
    @DisplayName("da.compat.cancel: POST /agent/cancel/{unknownTask} → 404 + {error}")
    void cancelUnknownTaskReturnsNotFound() throws Exception {
        initClient();
        HttpResponse<String> r = post("/agent/cancel/" + UNKNOWN_TASK, null);
        Allure.addAttachment("POST /agent/cancel/{unknownTask}", "text/plain", r.body());
        assertThat(r.statusCode()).as("不存在的任务不可受理为已取消").isEqualTo(404);
        assertThat(r.body()).as("错误体应为 DA 同源的 {error} 形态").contains("error");
    }

    // ---- da.compat.answer：缺参 400 / 无待答 404 ----

    @Test
    @Story("da.compat.answer: 缺 answer 返回 400")
    @DisplayName("da.compat.answer: POST /agent/answer/{task} 空体 → 400 + {error}")
    void answerWithoutAnswerReturnsBadRequest() throws Exception {
        initClient();
        HttpResponse<String> r = post("/agent/answer/" + UNKNOWN_TASK, "{}");
        Allure.addAttachment("POST /agent/answer/{task} body={}", "text/plain", r.body());
        assertThat(r.statusCode()).as("缺 answer 属参数错误").isEqualTo(400);
        assertThat(r.body()).contains("error");
    }

    @Test
    @Story("da.compat.answer: 无待答问题返回 404")
    @DisplayName("da.compat.answer: POST /agent/answer/{unknownTask} → 404 + {error}")
    void answerUnknownTaskReturnsNotFound() throws Exception {
        initClient();
        HttpResponse<String> r = post("/agent/answer/" + UNKNOWN_TASK, "{\"answer\":\"x\"}");
        Allure.addAttachment("POST /agent/answer/{unknownTask}", "text/plain", r.body());
        assertThat(r.statusCode()).as("无待答问题应返回 404").isEqualTo(404);
        assertThat(r.body()).contains("error");
    }

    // ---- da.compat.inject：缺参 400 / 任务不存在 404 ----

    @Test
    @Story("da.compat.inject: 缺 message 返回 400")
    @DisplayName("da.compat.inject: POST /agent/inject/{task} 空体 → 400 + {error}")
    void injectWithoutMessageReturnsBadRequest() throws Exception {
        initClient();
        HttpResponse<String> r = post("/agent/inject/" + UNKNOWN_TASK, "{}");
        Allure.addAttachment("POST /agent/inject/{task} body={}", "text/plain", r.body());
        assertThat(r.statusCode()).as("缺 message 属参数错误").isEqualTo(400);
        assertThat(r.body()).contains("error");
    }

    @Test
    @Story("da.compat.inject: 任务不存在返回 404")
    @DisplayName("da.compat.inject: POST /agent/inject/{unknownTask} → 404 + {error}")
    void injectUnknownTaskReturnsNotFound() throws Exception {
        initClient();
        HttpResponse<String> r = post("/agent/inject/" + UNKNOWN_TASK, "{\"message\":\"x\"}");
        Allure.addAttachment("POST /agent/inject/{unknownTask}", "text/plain", r.body());
        assertThat(r.statusCode()).as("无存活任务实例应返回 404").isEqualTo(404);
        assertThat(r.body()).contains("error");
    }

    // ---- da.compat.run-sync：缺参 400 ----

    @Test
    @Story("da.compat.run-sync: 缺 sessionId/input 返回 400")
    @DisplayName("da.compat.run-sync: POST /agent/run 空体 → 400 + {error}")
    void runSyncWithoutParamsReturnsBadRequest() throws Exception {
        initClient();
        HttpResponse<String> r = post("/agent/run", "{}");
        Allure.addAttachment("POST /agent/run body={}", "text/plain", r.body());
        assertThat(r.statusCode()).as("同步执行缺必填参数应返回 400").isEqualTo(400);
        assertThat(r.body()).contains("error");
    }

    // ---- da.compat.status-tasks：未命中 404；未知会话任务列表为空 ----

    @Test
    @Story("da.compat.status-tasks: 状态查询未命中返回 404")
    @DisplayName("da.compat.status-tasks: GET /agent/status/{unknownTask} → 404 + {error}")
    void statusUnknownTaskReturnsNotFound() throws Exception {
        initClient();
        HttpResponse<String> r = get("/agent/status/" + UNKNOWN_TASK);
        Allure.addAttachment("GET /agent/status/{unknownTask}", "text/plain", r.body());
        assertThat(r.statusCode()).as("未命中任务应返回 404").isEqualTo(404);
        assertThat(r.body()).contains("error");
    }

    @Test
    @Story("da.compat.status-tasks: 未知会话任务列表为空数组且不泄露存在性")
    @DisplayName("da.compat.status-tasks: GET /agent/tasks/{unknownSession} → 200 + []")
    void taskListForUnknownSessionIsEmpty() throws Exception {
        initClient();
        HttpResponse<String> r = get("/agent/tasks/" + UNKNOWN_SESSION);
        Allure.addAttachment("GET /agent/tasks/{unknownSession}", "text/plain", r.body());
        assertThat(r.statusCode()).as("任务列表走读侧谓词语义，不返回 404").isEqualTo(200);
        assertThat(r.body().trim())
                .as("未知会话返回空数组（不泄露该会话是否存在）")
                .isEqualTo("[]");
    }

    // ---- da.compat.errors：run-stream 入口参数校验（DA 同源 400）----

    @Test
    @Story("da.compat.errors: 流式执行缺 sessionId 返回 400")
    @DisplayName("da.compat.errors: POST /agent/run-stream 缺 sessionId → 400 + {error}")
    void runStreamWithoutSessionIdReturnsBadRequest() throws Exception {
        initClient();
        HttpResponse<String> r = post("/agent/run-stream", "{\"input\":\"hello\"}");
        Allure.addAttachment("POST /agent/run-stream 缺 sessionId", "text/plain", r.body());
        assertThat(r.statusCode()).as("缺 sessionId 应在受理前拒绝").isEqualTo(400);
        assertThat(r.body()).contains("error");
    }

    @Test
    @Story("da.compat.errors: 流式执行 input 与 mediaIds 均为空返回 400")
    @DisplayName("da.compat.errors: POST /agent/run-stream 空输入 → 400 + {error}")
    void runStreamWithEmptyInputReturnsBadRequest() throws Exception {
        initClient();
        HttpResponse<String> r = post("/agent/run-stream", "{\"sessionId\":\"s-054\",\"input\":\"\"}");
        Allure.addAttachment("POST /agent/run-stream 空输入", "text/plain", r.body());
        assertThat(r.statusCode()).as("input 与 mediaIds 均空应被拒绝").isEqualTo(400);
        assertThat(r.body()).contains("error");
    }

    // ---- da.compat.skills-read / providers-test：数据面未配置 → 503 ----

    @Test
    @Story("da.compat.skills-read: 数据面未配置返回 503")
    @DisplayName("da.compat.skills-read: GET /agent/skills → 503（数据面未配置）")
    void skillsListWithoutDataPlaneReturnsServiceUnavailable() throws Exception {
        initClient();
        HttpResponse<String> r = get("/agent/skills");
        Allure.addAttachment("GET /agent/skills（数据面未配置）", "text/plain", r.body());
        assertThat(r.statusCode())
                .as("数据面未配置时技能读面应返回 503（宿主侧新增语义）")
                .isEqualTo(503);
        assertThat(r.body()).contains("error");
    }

    @Test
    @Story("da.compat.providers-test: 数据面未配置返回 503")
    @DisplayName("da.compat.providers-test: POST /agent/providers/{id}/test → 503（数据面未配置）")
    void providerTestWithoutDataPlaneReturnsServiceUnavailable() throws Exception {
        initClient();
        HttpResponse<String> r = post("/agent/providers/any-provider/test", "{}");
        Allure.addAttachment("POST /agent/providers/{id}/test（数据面未配置）", "text/plain", r.body());
        assertThat(r.statusCode())
                .as("数据面未配置时 provider 探测应返回 503")
                .isEqualTo(503);
        assertThat(r.body()).contains("error");
    }

    // ---- HTTP helpers ----

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        b = (jsonBody == null)
                ? b.POST(HttpRequest.BodyPublishers.noBody())
                : b.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
