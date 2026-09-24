package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fixtures.moduledecoupling.MavenConsumerFixture;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Feature("FEAT-XXX3: Todo 复用 Checkpointer Redis")
@Tag("FEAT-XXX3")
@Tag("integration")
@Tag("spi-integration")
class RedisMiddlewareReuseAcceptanceTest {
    private static final String MAIN = "acceptance.redis.RedisReuseConsumer";

    @Test
    @Story("FEAT-XXX3.redis.runtime-default-reuse: Runtime 一次配置驱动 Todo 复用")
    @DisplayName("Feat-XXX3 Runtime 的 Redis Checkpointer 配置同时承载默认 Todo")
    void runtimeRedisConfigurationMakesDefaultTodoReuseCheckpointerStore() throws Exception {
        String session = "feat-xxx3-" + UUID.randomUUID();
        String canary = "todo-" + UUID.randomUUID();
        try (BackingServices backing = new BackingServices(
                TestConfig.load(), Set.of("redis"), new TestContainerFactory(null))) {
            Endpoint endpoint = Endpoint.parse(backing.url("redis"));
            Set<String> before;
            try (RespClient redis = new RespClient(endpoint)) {
                before = redis.scan("*");
            }

            var run = MavenConsumerFixture.build("redis-runtime-reuse")
                    .run(MAIN, endpoint.host(), Integer.toString(endpoint.port()), session, canary);

            assertThat(run.value("STATUS")).isEqualTo("REDIS_REUSE_OK");
            assertThat(run.value("TODO_STORAGE_TYPE")).isEqualTo("checkpointer_redis");
            assertThat(run.value("TODO_SUCCESS")).isEqualTo("true");
            assertThat(run.value("SESSION")).isEqualTo(session);

            try (RespClient redis = new RespClient(endpoint)) {
                Set<String> added = redis.scan("*");
                added.removeAll(before);
                assertThat(added).as("Todo and checkpoint keys created by the isolated consumer").hasSizeGreaterThan(1);
                List<String> values = new ArrayList<>();
                for (String key : added) {
                    if ("string".equals(redis.text("TYPE", key))) {
                        Object value = redis.command("GET", key);
                        if (value != null) {
                            values.add(String.valueOf(value));
                        }
                    }
                }
                assertThat(values).anyMatch(value -> value.contains(canary));
                assertThat(added).anyMatch(key -> key.contains(session));
                redis.command(join("DEL", added));
            }
        }
    }

    private static String[] join(String command, Set<String> values) {
        String[] args = new String[values.size() + 1];
        args[0] = command;
        int index = 1;
        for (String value : values) {
            args[index++] = value;
        }
        return args;
    }

    private record Endpoint(String host, int port) {
        static Endpoint parse(String value) {
            int colon = value.lastIndexOf(':');
            return new Endpoint(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
        }
    }

    private static final class RespClient implements AutoCloseable {
        private final Socket socket = new Socket();
        private final BufferedInputStream input;
        private final BufferedOutputStream output;

        private RespClient(Endpoint endpoint) throws IOException {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 5_000);
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
        }

        Set<String> scan(String pattern) throws IOException {
            Set<String> keys = new LinkedHashSet<>();
            String cursor = "0";
            do {
                @SuppressWarnings("unchecked")
                List<Object> reply = (List<Object>) command("SCAN", cursor, "MATCH", pattern, "COUNT", "200");
                cursor = String.valueOf(reply.get(0));
                @SuppressWarnings("unchecked")
                List<Object> page = (List<Object>) reply.get(1);
                page.forEach(key -> keys.add(String.valueOf(key)));
            } while (!"0".equals(cursor));
            return keys;
        }

        String text(String... args) throws IOException {
            return String.valueOf(command(args));
        }

        Object command(String... args) throws IOException {
            output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (String arg : args) {
                byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
                output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(bytes);
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            output.flush();
            return readReply();
        }

        private Object readReply() throws IOException {
            int marker = input.read();
            if (marker < 0) {
                throw new EOFException("Redis closed connection");
            }
            String line = readLine();
            return switch (marker) {
                case '+' -> line;
                case '-' -> throw new IOException("Redis command failed: " + line);
                case ':' -> Long.parseLong(line);
                case '$' -> readBulk(Integer.parseInt(line));
                case '*' -> readArray(Integer.parseInt(line));
                default -> throw new IOException("Unknown Redis response marker " + (char) marker);
            };
        }

        private Object readBulk(int length) throws IOException {
            if (length < 0) {
                return null;
            }
            byte[] value = input.readNBytes(length);
            if (value.length != length) {
                throw new EOFException("Truncated Redis bulk reply");
            }
            readLine();
            return new String(value, StandardCharsets.UTF_8);
        }

        private List<Object> readArray(int length) throws IOException {
            List<Object> values = new ArrayList<>(Math.max(length, 0));
            for (int index = 0; index < length; index++) {
                values.add(readReply());
            }
            return values;
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int previous = -1;
            for (int current; (current = input.read()) >= 0; previous = current) {
                if (previous == '\r' && current == '\n') {
                    byte[] value = bytes.toByteArray();
                    return new String(value, 0, value.length - 1, StandardCharsets.UTF_8);
                }
                bytes.write(current);
            }
            throw new EOFException("Truncated Redis line");
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}

