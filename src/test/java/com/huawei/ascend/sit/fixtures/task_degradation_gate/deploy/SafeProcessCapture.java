package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded in-memory process capture. Raw startup output is never written to disk. */
public final class SafeProcessCapture {
    private SafeProcessCapture() {}
    public record Result(long pid, int exit, String redacted, boolean truncated) {}
    public static String redact(String text, Collection<String> secrets) {
        String result = text;
        for (String secret : secrets) if (secret != null && !secret.isBlank()) result = result.replace(secret, "[REDACTED]");
        // Drop the whole credential-bearing line, including JSON quotes and Bearer payloads.
        return result.replaceAll("(?im)^.*(?:password|passwd|secret|api[_-]?key|authorization|token)[\\\"']?\\s*[=:].*$", "[REDACTED CREDENTIAL LINE]");
    }
    public static Result run(List<String> command, Path directory, Duration timeout, Collection<String> secrets) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<byte[]> output = reader.submit(() -> {
            var kept = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096];
            try (InputStream stream = process.getInputStream()) {
                for (int n; (n = stream.read(buffer)) != -1;) {
                    int retain = Math.min(n, Math.max(0, 1048577 - kept.size()));
                    kept.write(buffer, 0, retain); // Continue draining after cap to avoid a blocked child.
                }
            }
            return kept.toByteArray();
        });
        Throwable primary = null;
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new TimeoutException("process did not terminate");
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            boolean truncated = bytes.length > 1048576;
            String text = truncated ? "" : redact(new String(bytes, StandardCharsets.UTF_8), secrets);
            Files.createDirectories(directory);
            if (!truncated) Files.writeString(directory.resolve("startup-redacted.log"), text);
            Files.writeString(directory.resolve("process.properties"), "pid=" + process.pid() + "\nexit=" + process.exitValue() + "\ntruncated=" + truncated + "\n");
            return new Result(process.pid(), process.exitValue(), text, truncated);
        } catch (Exception | Error failure) { primary = failure; throw failure; }
        finally {
            try {
                if (process.isAlive()) { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
                process.getInputStream().close();
            } catch (Exception failure) { if (primary != null) primary.addSuppressed(failure); else throw failure; }
            finally { output.cancel(true); reader.shutdownNow(); }
        }
    }
}
