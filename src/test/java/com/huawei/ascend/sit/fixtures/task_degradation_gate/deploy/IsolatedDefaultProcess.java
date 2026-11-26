package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Owned default-configuration launch; never inherits Spring/JVM/config environment overrides. */
public final class IsolatedDefaultProcess implements AutoCloseable {
    private final Process process;
    private final Path directory;
    private final ExecutorService drain = Executors.newSingleThreadExecutor();
    private final Future<?> output;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final List<String> secrets;
    private volatile boolean truncated;
    /** Set before {@link Process#destroy()}: on Linux destroy closes the pipe first and the process lingers a few
     * seconds on SIGTERM, so a read failure during intentional termination is expected, not an error. */
    private volatile boolean closing;
    /** Markers watched over the whole output stream, independent of the bounded capture. */
    private final List<String> watched = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<String> observed = ConcurrentHashMap.newKeySet();
    private String window = "";
    private final StringBuilder tail = new StringBuilder();

    public static void validate(Properties properties) {
        for (String key : properties.stringPropertyNames()) {
            String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (normalized.contains("degradationgateenabled") || normalized.startsWith("springconfig")
                    || normalized.startsWith("springprofiles") || normalized.startsWith("springapplicationjson"))
                throw new IllegalArgumentException("Default launch forbids configuration indirection or enabled override");
        }
    }
    /**
     * The carrier's delivered configuration with every degradation-gate key absent: launched with
     * {@code --spring.config.location} pointing only at these properties, the packaged engine configuration is not
     * read, so the gate takes the DA host's own default. Service authentication stays on (the delivered default), with
     * a fresh secret per launch that the caller reads back with {@link #secret(Properties)}.
     */
    public static Properties deliveredWithoutGateKeys(String modelBaseUrl) {
        Properties properties = new Properties();
        properties.setProperty("spring.application.name", "deepanalyze-agent");
        properties.setProperty("deepanalyze.model.model.model", "deepanalyze-model");
        properties.setProperty("deepanalyze.model.backend.client_provider", "openai");
        properties.setProperty("deepanalyze.model.backend.base_url", modelBaseUrl);
        properties.setProperty("deepanalyze.model.backend.api_key", "feat112-test-stub-only");
        properties.setProperty("deepanalyze.auth.enabled", "true");
        properties.setProperty(ENGINE_SECRET, "feat112-" + UUID.randomUUID());
        validate(properties);
        return properties;
    }

    /** The service-authentication secret a launch configuration carries. */
    public static String secret(Properties properties) {
        return properties.getProperty(ENGINE_SECRET);
    }

    /**
     * Deployment overlay on top of the packaged (delivered) engine configuration, for {@link #launchPackaged}: only
     * what a deployment must supply (model connection, service secret) and an in-memory checkpointer in place of the
     * delivered Redis. The degradation-gate keys are left to the packaged configuration.
     */
    public static Properties packagedOverlay(String modelBaseUrl) {
        Properties properties = new Properties();
        properties.setProperty("deepanalyze.model.backend.base_url", modelBaseUrl);
        properties.setProperty("deepanalyze.model.backend.api_key", "feat112-test-stub-only");
        properties.setProperty(ENGINE_SECRET, "feat112-" + UUID.randomUUID());
        properties.setProperty("openjiuwen.service.middleware.checkpointer.type", "in_memory");
        validate(properties);
        return properties;
    }

    private static final String ENGINE_SECRET = "deepanalyze.auth.engine-secret";
    public static Map<String,String> cleanEnvironment(Map<String,String> inherited) {
        Map<String,String> clean = new HashMap<>();
        inherited.forEach((key,value) -> {
            // OS basics only, never Spring/JVM/config overrides. Locale stays: without LANG/LC_* a Linux JVM
            // resolves file paths as ASCII and the carrier's built-in skill directories (Chinese names) break startup.
            if (Set.of("systemroot", "windir", "path", "pathext", "temp", "tmp", "home", "userprofile",
                    "lang", "lc_all", "lc_ctype")
                    .contains(key.toLowerCase(Locale.ROOT))) clean.put(key,value);
        });
        return clean;
    }
    public static IsolatedDefaultProcess launch(Path jar, Properties properties, int port) throws Exception {
        return launch(jar, properties, port, "--spring.config.location=");
    }

    /** Launch reading the packaged configuration, with {@code overlay} layered over it (higher precedence). */
    public static IsolatedDefaultProcess launchPackaged(Path jar, Properties overlay, int port) throws Exception {
        return launch(jar, overlay, port, "--spring.config.additional-location=");
    }

    private static IsolatedDefaultProcess launch(Path jar, Properties properties, int port, String locationArgument) throws Exception {
        validate(properties);
        if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("Executable jar missing");
        Path directory = Files.createTempDirectory("feat112-default-");
        Path config = directory.resolve("isolated.properties");
        try {
            try (Writer writer = Files.newBufferedWriter(config)) { properties.store(writer, "isolated test input"); }
            List<String> command = List.of(javaExecutable(), "-jar", jar.toAbsolutePath().toString(),
                    locationArgument + config.toUri(), "--server.address=127.0.0.1", "--server.port=" + port);
            return new IsolatedDefaultProcess(command, directory, new ArrayList<>(properties.values().stream().map(Object::toString).toList()));
        } catch (Exception | Error failure) {
            try { deleteDirectory(directory); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    static String javaExecutable() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    // Package-private entry point exists only for local child-JVM self-tests.
    IsolatedDefaultProcess(List<String> command, Path directory, List<String> secrets) throws IOException {
        this.directory = directory; this.secrets = List.copyOf(secrets);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        Map<String,String> env = cleanEnvironment(builder.environment()); builder.environment().clear(); builder.environment().putAll(env);
        try { process = builder.start(); }
        catch (IOException | RuntimeException | Error failure) { drain.shutdownNow(); throw failure; }
        try {
        output = drain.submit(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) >= 0;) synchronized (captured) {
                    int keep = Math.min(n, 1048576 - captured.size()); captured.write(buffer, 0, keep);
                    if (keep < n) truncated = true;
                    String text = new String(buffer, 0, n, StandardCharsets.ISO_8859_1);
                    scan(text);
                    tail.append(text);
                    if (tail.length() > 65536) tail.delete(0, tail.length() - 65536);
                }
            } catch (IOException failure) { if (!closing && process.isAlive()) throw new UncheckedIOException(failure); }
        });
        } catch (RuntimeException | Error failure) {
            try { terminateOwned(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            finally { drain.shutdownNow(); }
            throw failure;
        }
    }
    /** Caller holds {@code captured}. ASCII markers are matched across chunk boundaries. */
    private void scan(String chunk) {
        String text = window + chunk;
        for (String marker : watched) if (text.contains(marker)) observed.add(marker);
        int longest = watched.stream().mapToInt(String::length).max().orElse(0);
        window = text.substring(Math.max(0, text.length() - longest));
    }
    /** Starts watching an ASCII marker; output already captured is checked as well. */
    public void watch(String marker) {
        if (marker == null || marker.isBlank() || !StandardCharsets.US_ASCII.newEncoder().canEncode(marker))
            throw new IllegalArgumentException("non-blank ASCII marker required");
        synchronized (captured) {
            watched.add(marker);
            if (captured.toString(StandardCharsets.ISO_8859_1).contains(marker)) observed.add(marker);
        }
    }
    /** Whether a watched marker has appeared anywhere in the output so far, even beyond the capture limit. */
    public boolean observed(String marker) {
        if (!watched.contains(marker)) throw new IllegalStateException("marker not watched: " + marker);
        return observed.contains(marker);
    }
    /** Last 64KB of output (redacted), kept even when the bounded head capture is truncated. */
    public String tail() {
        synchronized (captured) {
            return SafeProcessCapture.redact(new String(tail.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8), secrets);
        }
    }
    public long pid() { return process.pid(); }
    public boolean isAlive() { return process.isAlive(); }
    public String diagnostics() {
        synchronized (captured) { return truncated ? "Startup evidence truncated" : SafeProcessCapture.redact(captured.toString(StandardCharsets.UTF_8), secrets); }
    }
    private void terminateOwned() throws IOException {
        boolean interrupted = Thread.interrupted();
        closing = true;
        try {
            process.destroy();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try { process.waitFor(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { interrupted = true; break; }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (process.isAlive() && System.nanoTime() < deadline) {
                    try { process.waitFor(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            }
            if (process.isAlive()) throw new IOException("Owned process failed to stop");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    @Override public void close() throws Exception {
        Throwable primary = null;
        boolean interrupted = Thread.interrupted();
        try {
            try { terminateOwned(); }
            catch (Exception | Error failure) { primary = failure; }
            interrupted |= Thread.interrupted();
            try { output.get(5, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { interrupted = true; primary = append(primary, failure); }
            catch (Exception | Error failure) { primary = append(primary, failure); }
        } finally {
            output.cancel(true);
            drain.shutdownNow();
            // Never remove a live child's working directory.
            if (!process.isAlive()) {
                try { deleteDirectory(directory); }
                catch (Exception | Error failure) { primary = append(primary, failure); }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (primary instanceof Error error) throw error;
        if (primary instanceof Exception exception) throw exception;
    }
    private static Throwable append(Throwable primary, Throwable next) {
        if (primary == null) return next;
        primary.addSuppressed(next); return primary;
    }
}
