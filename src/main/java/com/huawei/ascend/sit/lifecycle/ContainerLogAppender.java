package com.huawei.ascend.sit.lifecycle;

import org.testcontainers.containers.output.OutputFrame;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * Appends a backing-service container's stdout+stderr to {@code <logDir>/<name>/stdout.log},
 * mirroring {@link ProcessLauncher}'s per-process redirect so every SUT service (process or
 * container) lives under one uniform {@code target/sit-logs/<name>/} tree.
 *
 * <p>Registered with Testcontainers via {@code GenericContainer.withLogConsumer(this)} before
 * {@code start()}, so it captures from container boot. Each {@link OutputFrame#getUtf8String()}
 * already carries its line ending, so frames are written verbatim; the {@link OutputFrame.OutputType#END}
 * EOF sentinel is skipped.
 *
 * <p>{@link #accept(OutputFrame)} runs on Testcontainers' log thread while {@link #closeQuietly()}
 * runs on the test thread at container stop — both are guarded by one lock so a final frame
 * delivered during stop can't write to a closed stream.
 */
final class ContainerLogAppender implements Consumer<OutputFrame> {

    private final Object lock = new Object();
    private final PrintWriter writer;

    private ContainerLogAppender(PrintWriter writer) {
        this.writer = writer;
    }

    /** Create the {@code <logDir>/<name>/} directory and open {@code stdout.log} for appending (UTF-8). */
    static ContainerLogAppender open(Path logDir, String name) {
        Path file = logDir.resolve(name).resolve("stdout.log");
        try {
            Files.createDirectories(file.getParent());
            PrintWriter pw = new PrintWriter(
                    Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                    true); // auto-flush on println/printf — we also flush explicitly per frame in accept
            return new ContainerLogAppender(pw);
        } catch (IOException e) {
            throw new IllegalStateException("cannot open container log " + file, e);
        }
    }

    @Override
    public void accept(OutputFrame frame) {
        if (frame == null || frame.getType() == OutputFrame.OutputType.END) {
            return;
        }
        String chunk = frame.getUtf8String(); // includes the line ending
        synchronized (lock) {
            writer.print(chunk);
            writer.flush();
        }
    }

    /** Flush and close the writer. Idempotent (PrintWriter.close is too). Swallows IO errors. */
    void closeQuietly() {
        synchronized (lock) {
            writer.close();
        }
    }
}
