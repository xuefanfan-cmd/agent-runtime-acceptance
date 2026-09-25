package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.lifecycle.SutStack;

/**
 * FEAT-054 用例启动 DA 宿主（deepanalyze-java）时的模型环境绑定。
 *
 * <p>宿主内 {@code application.yml} 读的是 {@code deepanalyze.model.backend.api_key: ${LLM_API_KEY:}}，
 * 缺 key 即启动 fail-fast（{@code deepanalyze.model.backend incomplete missing=[api_key]}）。
 *
 * <p>为什么 key 不能用「环境变量」这一条路：{@code application-openjiuwen.yml} 的全局
 * {@code sut.java.system-properties.LLM_API_KEY} 是空串，框架会把它以 {@code -DLLM_API_KEY=}
 * 注入每个被拉起的 SUT；系统属性优先级高于环境变量，会把继承来的 {@code LLM_API_KEY} 盖成空。
 * 因此 key 必须以优先级最高的 Spring {@code --} 参数注入（值只在运行时从环境读取，不落文件、不落日志）。
 */
final class DaModelEnvironment {

    private DaModelEnvironment() {
    }

    /** 透传 DA 宿主需要的模型环境变量，并注入 API key；返回入参便于流式书写。 */
    static SutStack.AgentBuilder bind(SutStack.AgentBuilder agent) {
        passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
        passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
        passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
        return bindModelKey(agent);
    }

    /** 仅注入 API key 的空值兜底之外的场景：值取自环境变量。 */
    static SutStack.AgentBuilder bindModelKey(SutStack.AgentBuilder agent) {
        return bindModelKey(agent, System.getenv("LLM_API_KEY"));
    }

    /** 仅注入 API key（用例自行透传 {@code DA_MODEL_*} 时使用，例如黑洞模型用例传占位值）。 */
    static SutStack.AgentBuilder bindModelKey(SutStack.AgentBuilder agent, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            agent.property("LLM_API_KEY", apiKey);
        }
        return agent;
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }
}
