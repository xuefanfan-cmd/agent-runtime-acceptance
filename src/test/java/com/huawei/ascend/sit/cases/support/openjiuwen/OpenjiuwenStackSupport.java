package com.huawei.ascend.sit.cases.support.openjiuwen;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

/**
 * Shared SUT stack builders for openjiuwen travel-chain tests.
 */
public final class OpenjiuwenStackSupport {

    public static final String MAINPLAN = "mainplan";
    public static final String TRIP = "trip";
    public static final String HOTEL = "hotel";

    public static final String REDIS_PROFILE = "redis";
    public static final String MCP_PROFILE = "mcp";
    public static final String SANDBOX_PROFILE = "sandbox";
    public static final String SKILL_PROFILE = "skill";

    /**
     * Placeholder trip URL for standalone mainplan stacks (no managed trip downstream).
     * {@code application-openjiuwen.yml} sets {@code remote-agents[0].name} only; without a URL
     * {@code A2AAgentCardDiscovery} NPEs at startup. Trip is not required for OJ-01/OJ-03/OJ-07 dialogue.
     */
    private static final String STANDALONE_TRIP_URL = "http://127.0.0.1:8092/a2a/";

    private static final String REMOTE_AGENTS_URL_KEY =
            "openjiuwen.service.a2a.remote-agents[0].url";

    private OpenjiuwenStackSupport() {
    }

    /** Single mainplan agent, synchronous {@code message/send}. */
    public static SutStack.Builder mainplanSync(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(MAINPLAN, OpenjiuwenStackSupport::withStandaloneRemoteUrl);
    }

    /** Mainplan + trip (no hotel); trip URL injected into mainplan remote-agents. */
    public static SutStack.Builder mainplanTripSync(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(TRIP)
                .agent(MAINPLAN, a -> a.downstream(TRIP));
    }

    /** Full travel chain (hotel → trip → mainplan), synchronous {@code message/send}. */
    public static SutStack.Builder fullChainSync(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(HOTEL)
                .agent(TRIP, a -> a.downstream(HOTEL))
                .agent(MAINPLAN, a -> a.downstream(TRIP));
    }

    /** Full travel chain, streaming {@code message/stream} (OJ-06+ default). */
    public static SutStack.Builder fullChainStreaming(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(HOTEL)
                .agent(TRIP, a -> a.downstream(HOTEL))
                .agent(MAINPLAN, a -> a.downstream(TRIP));
    }

    /**
     * Full chain with {@code redis} profile and {@code REDIS_HOST}/{@code REDIS_PORT} on every agent.
     * Pair with a test-managed {@link com.huawei.ascend.sit.lifecycle.BackingServices} for Redis.
     */
    public static SutStack.Builder fullChainRedisStreaming(TestConfig config, String redisHost, String redisPort) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(HOTEL, a -> withRedisEnv(a.profile(REDIS_PROFILE), redisHost, redisPort))
                .agent(TRIP, a -> withRedisEnv(a.downstream(HOTEL).profile(REDIS_PROFILE), redisHost, redisPort))
                .agent(MAINPLAN, a -> withRedisEnv(a.downstream(TRIP).profile(REDIS_PROFILE), redisHost, redisPort));
    }

    /** Single mainplan, streaming {@code message/stream}. */
    public static SutStack.Builder mainplanStreaming(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(MAINPLAN, OpenjiuwenStackSupport::withStandaloneRemoteUrl);
    }

    /** Single mainplan streaming with {@code redis} profile + Redis env (OJ-07). */
    public static SutStack.Builder mainplanRedisStreaming(TestConfig config, String redisHost, String redisPort) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(MAINPLAN, a -> withRedisEnv(withStandaloneRemoteUrl(a).profile(REDIS_PROFILE), redisHost, redisPort));
    }

    /** Single hotel streaming with {@code mcp} profile + Mock MCP env (OJ-08). */
    public static SutStack.Builder hotelMcpStreaming(TestConfig config, String mcpHost, String mcpPort) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(HOTEL, a -> withMcpEnv(a.profile(MCP_PROFILE), mcpHost, mcpPort));
    }

    /** Single hotel streaming with {@code sandbox} profile + jiuwenbox env (OJ-09 / OJ-10). */
    public static SutStack.Builder hotelSandboxStreaming(TestConfig config, String sandboxHost, String sandboxPort) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(HOTEL, a -> withSandboxEnv(a.profile(SANDBOX_PROFILE), sandboxHost, sandboxPort));
    }

    /** Single hotel streaming with {@code skill} profile only (OJ-11). */
    public static SutStack.Builder hotelSkillStreaming(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(HOTEL, a -> a.profile(SKILL_PROFILE));
    }

    public static long timeoutMs(TestConfig config) {
        return config.getPollTimeoutSeconds() * 1000L;
    }

    /**
     * Standalone mainplan (no managed trip): supply remote-agents[0].url so startup discovery does not NPE.
     * Discovery may warn/retry if trip is not listening — acceptable for single-agent memory tests.
     */
    private static SutStack.AgentBuilder withStandaloneRemoteUrl(SutStack.AgentBuilder builder) {
        return builder.property(REMOTE_AGENTS_URL_KEY, STANDALONE_TRIP_URL);
    }

    /**
     * Wires Redis for openjiuwen middleware checkpointer on a managed agent.
     *
     * <p>Uses Spring Boot command-line args (highest precedence) so checkpointer type is {@code redis}
     * even when the fat jar's {@code application-redis.yml} profile overlay is missing or stale.
     * {@code REDIS_HOST}/{@code REDIS_PORT} env vars remain for {@code ${REDIS_*}} placeholders in YAML.</p>
     */
    private static SutStack.AgentBuilder withRedisEnv(SutStack.AgentBuilder builder,
                                                      String redisHost, String redisPort) {
        return builder
                .env("REDIS_HOST", redisHost)
                .env("REDIS_PORT", redisPort)
                .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                .property("openjiuwen.service.middleware.redis.default.host", redisHost)
                .property("openjiuwen.service.middleware.redis.default.port", redisPort)
                .property("openjiuwen.service.middleware.redis.default.database", "0")
                .property("openjiuwen.service.middleware.redis.default.encrypted-password", "");
    }

    private static SutStack.AgentBuilder withMcpEnv(SutStack.AgentBuilder builder,
                                                   String mcpHost, String mcpPort) {
        return builder
                .env("MCP_HOST", mcpHost)
                .env("MCP_PORT", mcpPort);
    }

    private static SutStack.AgentBuilder withSandboxEnv(SutStack.AgentBuilder builder,
                                                        String sandboxHost, String sandboxPort) {
        return builder
                .env("SANDBOX_HOST", sandboxHost)
                .env("SANDBOX_PORT", sandboxPort);
    }

    /** Parses {@code host:port} from {@link com.huawei.ascend.sit.lifecycle.BackingServices#url(String)}. */
    public static RedisEndpoint parseRedisEndpoint(String hostColonPort) {
        int colon = hostColonPort.lastIndexOf(':');
        if (colon <= 0 || colon >= hostColonPort.length() - 1) {
            throw new IllegalArgumentException("Expected host:port, got: " + hostColonPort);
        }
        return new RedisEndpoint(hostColonPort.substring(0, colon), hostColonPort.substring(colon + 1));
    }

    public record RedisEndpoint(String host, String port) {
    }
}
