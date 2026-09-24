package acceptance.redis;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.TaskPlanningRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.tools.ToolOutput;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.middleware.DefaultMiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.common.credential.PassthroughCredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.JedisPooledRuntimeRedisClient;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Map;

public final class RedisReuseConsumer {
    private RedisReuseConsumer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected host, port, session and canary");
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String session = args[2];
        String canary = args[3];

        MiddlewareProperties properties = redisProperties(host, port);
        RuntimeRedisClient redisClient = new JedisPooledRuntimeRedisClient(new JedisPooled(host, port));
        MiddlewareAdapterRegistrar registrar = new DefaultMiddlewareAdapterRegistrar(
                properties, new PassthroughCredentialDecryptor(), redisClient);
        DeepAgentConfig config = DeepAgentConfig.builder()
                .rails(List.of(new TaskPlanningRail()))
                .isTaskLoopEnabled(false)
                .isTaskPlanningEnabled(false)
                .workspacePath("target/redis-consumer-workspace")
                .build();

        try (DeepAgent agent = HarnessFactory.createDeepAgent(config)) {
            ProbeHandler handler = new ProbeHandler(agent, registrar);
            boolean started = false;
            try {
                handler.start();
                started = true;
                Object result = handler.execute(Map.of(
                        "query", "checkpoint-" + canary,
                        "conversation_id", session), session);
                if (!(result instanceof Map<?, ?>)) {
                    throw new IllegalStateException("Deterministic DeepAgent execution returned no map");
                }
                Tool create = agent.getRegisteredTools().stream()
                        .filter(Tool.class::isInstance)
                        .map(Tool.class::cast)
                        .filter(tool -> "todo_create".equals(tool.getCard().getName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("todo_create was not registered"));
                Object raw = create.invoke(Map.of("session_id", session, "tasks", List.of(canary)));
                if (!(raw instanceof ToolOutput output) || !output.isSuccess()) {
                    throw new IllegalStateException("todo_create failed: " + raw);
                }
                System.out.println("SESSION=" + session);
                System.out.println("TODO_STORAGE_TYPE=" + agent.getConfig().getTodoStorageType());
                System.out.println("TODO_SUCCESS=" + output.isSuccess());
                System.out.println("STATUS=REDIS_REUSE_OK");
            } finally {
                if (started) {
                    handler.stop();
                } else {
                    redisClient.close();
                }
            }
        }
    }

    private static MiddlewareProperties redisProperties(String host, int port) {
        MiddlewareProperties properties = new MiddlewareProperties();
        MiddlewareProperties.Checkpointer checkpointer = new MiddlewareProperties.Checkpointer();
        checkpointer.setType("redis");
        checkpointer.setRedisRef("default");
        checkpointer.setTtlSeconds(180);
        properties.setCheckpointer(checkpointer);

        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setType("standalone");
        endpoint.setHost(host);
        endpoint.setPort(port);
        endpoint.setDatabase(0);
        endpoint.setEncryptedPassword("");
        properties.setRedis(Map.of("default", endpoint));
        return properties;
    }

    private static final class ProbeHandler extends JiuwenCoreAgentHandler {
        private ProbeHandler(Object agent, MiddlewareAdapterRegistrar registrar) {
            super(agent, registrar);
        }

        private Object execute(Map<String, Object> inputs, String session) {
            return super.executeAgent(inputs, session);
        }
    }
}

