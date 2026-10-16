/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.concurrency.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 拉起/停止单个 SUT（concurrency-agent-runtime）的生命周期管理。
 *
 * <p>SUT jar 路径通过以下优先级解析：</p>
 * <ol>
 *   <li>系统属性 {@code concurrency.sut.jar}（绝对路径）</li>
 *   <li>环境变量 {@code SUT_JAR_PATH}（绝对路径）</li>
 *   <li>{@code concurrency.sut.root} 系统属性下的
 *       {@code concurrency-agent-runtime/target/concurrency-agent-runtime-0.1.2.jar}</li>
 * </ol>
 *
 * <p>SUT 工作目录通过以下优先级解析：</p>
 * <ol>
 *   <li>系统属性 {@code concurrency.sut.workdir}</li>
 *   <li>环境变量 {@code SUT_WORK_DIR}</code></li>
 *   <li>SUT jar 所在目录的上一级（demo 根目录）</li>
 * </ol>
 *
 * @since 0.1.0
 */
public final class SutStack implements AutoCloseable {
    /**
     * 被测 SUT 端口。
     */
    public static final String SUT_PORT = "18210";

    private static final Duration HEALTH_TIMEOUT = Duration.ofMinutes(3);

    private final Path jarPath;
    private final Path workDir;
    private final Path logDir;
    private final Integer maxConcurrentTasks;
    private final Map<String, String> extraEnv;
    private Process process;
    private Path logFile;

    /**
     * 以默认配置启动 SUT（{@code max-concurrent-tasks} 由 {@code application.yml} 的默认值决定）。
     */
    public SutStack() {
        this(null);
    }

    /**
     * 以指定 {@code max-concurrent-tasks} 启动 SUT。
     *
     * @param maxConcurrentTasks
     *            {@code CONCURRENCY_MAX_TASKS} 覆盖值；传 {@code null} 表示使用默认值
     */
    public SutStack(Integer maxConcurrentTasks) {
        this(maxConcurrentTasks, Map.of());
    }

    /**
     * 以指定 {@code max-concurrent-tasks} 和额外环境变量启动 SUT。
     *
     * @param maxConcurrentTasks
     *            {@code CONCURRENCY_MAX_TASKS} 覆盖值；传 {@code null} 表示使用默认值
     * @param extraEnv
     *            额外环境变量
     */
    public SutStack(Integer maxConcurrentTasks, Map<String, String> extraEnv) {
        this.jarPath = resolveJarPath();
        this.workDir = resolveWorkDir();
        this.logDir = workDir.resolve("target").resolve("sut-logs");
        this.maxConcurrentTasks = maxConcurrentTasks;
        this.extraEnv = extraEnv;
    }

    /**
     * SUT 日志文件路径（供 LogTailProbe 使用）。
     *
     * @return SUT 日志文件路径
     */
    public Path logFile() {
        return logFile;
    }

    /**
     * 启动 SUT 并等待 {@code /health} 就绪。
     */
    public void start() {
        if (!Files.exists(jarPath)) {
            throw new IllegalStateException("missing SUT jar: " + jarPath
                    + " (set -Dconcurrency.sut.jar=<path> or SUT_JAR_PATH env)");
        }
        String suffix = (maxConcurrentTasks != null ? "max" + maxConcurrentTasks : "default")
                + "-" + System.currentTimeMillis();
        logFile = logDir.resolve("concurrency-agent-" + suffix + ".log");
        try {
            Files.createDirectories(logDir);
            ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-jar", jarPath.toString());
            if (maxConcurrentTasks != null) {
                builder.environment().put("CONCURRENCY_MAX_TASKS", String.valueOf(maxConcurrentTasks));
            }
            builder.environment().putAll(extraEnv);
            builder.directory(workDir.toFile());
            builder.redirectOutput(logFile.toFile());
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start SUT", e);
        }
        waitForHealth();
    }

    private void waitForHealth() {
        String url = "http://127.0.0.1:" + SUT_PORT + "/health";
        long deadline = System.currentTimeMillis() + HEALTH_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (process != null && !process.isAlive()) {
                throw new IllegalStateException("SUT exited before health check; see " + logFile);
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET()
                        .build();
                HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"status\":\"healthy\"")) {
                    return;
                }
            } catch (IOException | InterruptedException ignored) {
                // 尚未就绪，继续轮询
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        throw new IllegalStateException("SUT did not become healthy at " + url);
    }

    /**
     * 停止 SUT（故障注入 / 清理用）。
     */
    public void stop() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ignored) {
            process.destroyForcibly();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static Path resolveJarPath() {
        String prop = System.getProperty("concurrency.sut.jar");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        String env = System.getenv("SUT_JAR_PATH");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        String root = System.getProperty("concurrency.sut.root");
        if (root != null && !root.isBlank()) {
            return Path.of(root).toAbsolutePath().normalize()
                    .resolve("concurrency-agent-runtime").resolve("target")
                    .resolve("concurrency-agent-runtime-0.1.2.jar");
        }
        throw new IllegalStateException(
                "SUT jar not configured. Set -Dconcurrency.sut.jar=<path>, SUT_JAR_PATH env, or -Dconcurrency.sut.root=<demo-root>");
    }

    private static Path resolveWorkDir() {
        String prop = System.getProperty("concurrency.sut.workdir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        String env = System.getenv("SUT_WORK_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        // 默认：SUT jar 所在目录的上两级（demo 根目录）
        return resolveJarPath().getParent().getParent().getParent();
    }

    private static String javaExecutable() {
        String binary = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", binary).toString();
    }
}
