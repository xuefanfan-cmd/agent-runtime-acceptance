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
 * FEAT-054：文件输入白名单的拦截半边。
 *
 * <p>依据：设计 §3.2 可选增益取舍行「纳入文件输入白名单校验」；L2 §2.9 说明该能力为宿主 Handler 层新增。
 *
 * <p>断言边界刻意收紧：只验证「非白名单输入被拒且不进入执行」，<b>不断言</b>具体错误码——L2 §2.9 同时
 * 写了「等价于 EDP-FILE-003」与「实现错误码 DA-FILE-003」，口径待开发确认（L2 问题清单 L-07）。
 * 因此本类只断言 4xx + 业务错误体，并把实际响应体作为证据记录，等口径确定后收紧断言。
 *
 * <p>本用例不需要模型与工具：拒绝发生在进入执行之前。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaFileWhitelistE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";

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

    @Test
    @Story("da.file.whitelist: 非白名单文件输入被拦截且不进入执行")
    @DisplayName("da.file.whitelist/reject: 非白名单类型 → 4xx 业务错误，不进入执行")
    void nonWhitelistedInputIsRejected() throws Exception {
        initClient();

        // yml 默认白名单为 pdf/docx/doc/txt/md/xlsx/csv/png/jpg；这里用 .exe 作反例。
        String payload = "{\"sessionId\":\"sit-feat054-whitelist-01\","
                + "\"input\":\"请分析这个文件\","
                + "\"mediaIds\":[\"sit-feat054-not-whitelisted.exe\"]}";
        HttpResponse<String> r = post("/agent/run-stream", payload);
        Allure.addAttachment("POST /agent/run-stream（非白名单 mediaId）", "text/plain", r.body());

        assertThat(r.statusCode())
                .as("非白名单输入应在进入执行前被拒（不得进入任务执行）")
                .isBetween(400, 499);
        assertThat(r.body())
                .as("错误体应为业务错误表面")
                .contains("error");

        Allure.addAttachment("待确认口径（L-07）", "text/plain",
                "L2 §2.9 同时写了「等价于 EDP-FILE-003」与「实现错误码 DA-FILE-003」；"
                        + "本用例只断言 4xx + 业务错误体，错误码口径确认后收紧断言。");
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
