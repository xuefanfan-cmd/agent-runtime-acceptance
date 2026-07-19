package com.huawei.ascend.sit.client;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.transport.FileWireLogger;
import com.huawei.ascend.sit.transport.WireLogger;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves the active {@link WireLogger} from {@link TestConfig}, keeping the transport layer free
 * of any config dependency. Returns {@link WireLogger#NOOP} unless {@code sut.wire-log.enabled} is
 * true; otherwise a {@link FileWireLogger} writing under {@code <sut.wire-log.dir or
 * sut.logging.dir/wire or target/sit-logs/wire>/<runId>/}.
 *
 * <p>The result is cached per JVM (static) so the {@code runId} — and thus the per-run subdir — is
 * stable across every flow in one {@code mvnw test} invocation (surefire forks once). Any failure
 * reading config falls back to {@link WireLogger#NOOP}: wire logging never breaks tests.
 *
 * <p>Config keys:
 * <ul>
 *   <li>{@code sut.wire-log.enabled} (default {@code false})</li>
 *   <li>{@code sut.wire-log.dir} (default {@code <sut.logging.dir>/wire}, or
 *       {@code <basedir>/target/sit-logs/wire})</li>
 *   <li>{@code sut.wire-log.run-id} (default {@code run-<yyyyMMdd-HHmmss>} — groups one JVM run)</li>
 * </ul>
 */
public final class WireLoggerResolver {

    private static final Logger LOG = Logger.getLogger(WireLoggerResolver.class.getName());
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static volatile WireLogger cached;

    private WireLoggerResolver() {}

    /** The shared, cached wire logger for this JVM ({@link WireLogger#NOOP} when disabled). */
    public static WireLogger resolved() {
        WireLogger local = cached;
        if (local == null) {
            local = build();
            cached = local;
        }
        return local;
    }

    private static WireLogger build() {
        try {
            TestConfig config = TestConfig.load();
            if (!config.getBoolean("sut.wire-log.enabled", false)) {
                return WireLogger.NOOP;
            }
            return new FileWireLogger(resolveBaseDir(config), resolveRunId(config));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "wire-log disabled: could not read config", e);
            return WireLogger.NOOP;
        }
    }

    private static Path resolveBaseDir(TestConfig config) {
        String wireDir = config.getString("sut.wire-log.dir");
        if (wireDir != null && !wireDir.isBlank()) {
            return Path.of(wireDir);
        }
        String loggingDir = config.getString("sut.logging.dir");
        if (loggingDir != null && !loggingDir.isBlank()) {
            return Path.of(loggingDir, "wire");
        }
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        return Path.of(basedir, "target", "sit-logs", "wire");
    }

    private static String resolveRunId(TestConfig config) {
        String runId = config.getString("sut.wire-log.run-id");
        if (runId != null && !runId.isBlank()) {
            return runId;
        }
        return "run-" + LocalDateTime.now().format(RUN_ID_FORMAT);
    }
}
