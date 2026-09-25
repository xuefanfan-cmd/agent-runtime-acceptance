package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * FEAT-054 DA 兼容面 E2E 的公共栈基座：模型连接按进程环境透传、Redis 由栈自管拉起。
 *
 * <p>心跳间隔在本族用例里压到 {@code 500ms}（{@code deepanalyze.compat.heartbeat-ms}），
 * 目的是让"心跳帧在场 + 该配置项确实被消费"在几秒级的短任务里可观测；
 * L2 §2.11 的默认值 15000ms 属配置事实，本族不冒充"默认值已在真实 15 秒窗口验证"。
 */
abstract class DaCompatStreamTestBase extends BaseManagedStackTest {

    protected static final String AGENT = "deepanalyze";
    protected static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    protected static final String SESSION_HEADER = "X-Da-Session-Id";

    protected HttpClient http;
    protected String base;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    bindModelEnvironment(agent);
                    DaModelEnvironment.bindModelKey(agent);
                    agent.property("deepanalyze.compat.heartbeat-ms", "500")
                            // 运行时 A2A TaskStore 与 core checkpointer 走 Redis；默认主机名 "redis" 在本地解析不到。
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    protected static void bindModelEnvironment(SutStack.AgentBuilder agent) {
        passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
        passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
        passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
        passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    protected void initClient() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            base = client(AGENT).getBaseUrl();
        }
    }

    protected static String runStreamBody(String sessionId, String input) {
        return "{\"sessionId\":\"" + sessionId + "\",\"input\":\"" + input + "\"}";
    }
}
