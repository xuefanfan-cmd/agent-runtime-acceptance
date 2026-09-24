package com.huawei.ascend.sit.fixtures.moduledecoupling;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Builds and executes isolated Maven consumer projects against the tested artifacts. */
public final class MavenConsumerFixture {
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(45);
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private static final Path SOURCES = ROOT.resolve("src/test/resources/testdata/module_decoupling_consumers");
    private static final Path WORK = ROOT.resolve("target/acceptance-fixtures/module-decoupling");
    private static final Map<String, ConsumerBuild> BUILDS = new ConcurrentHashMap<>();

    private MavenConsumerFixture() {
    }

    public static ConsumerBuild build(String fixtureName) {
        return BUILDS.computeIfAbsent(fixtureName, MavenConsumerFixture::buildUncached);
    }

    private static ConsumerBuild buildUncached(String fixtureName) {
        Path source = SOURCES.resolve(fixtureName).normalize();
        if (!source.startsWith(SOURCES) || !Files.isRegularFile(source.resolve("pom.xml"))) {
            throw new AssertionError("Unknown Maven consumer fixture: " + fixtureName);
        }
        Path work = WORK.resolve(fixtureName).normalize();
        try {
            copyTree(source, work);
            Path log = work.resolve("fixture-build.log");
            List<String> command = new ArrayList<>();
            command.add(mavenExecutable());
            command.add("-q");
            String settings = System.getProperty("acceptance.fixture.maven.settings", "").trim();
            if (!settings.isEmpty()) {
                command.add("-s");
                command.add(settings);
            }
            command.add("-f");
            command.add(work.resolve("pom.xml").toString());
            command.add("clean");
            command.add("package");
            command.add("dependency:build-classpath");
            command.add("-DskipTests");
            command.add("-DincludeScope=runtime");
            command.add("-Dmdep.outputFile=target/classpath.txt");
            command.add("-Dmaven.repo.local=" + mavenRepository());
            runProcess(command, ROOT, log, BUILD_TIMEOUT, "Maven consumer build " + fixtureName);

            Path classpathFile = work.resolve("target/classpath.txt");
            if (!Files.isRegularFile(classpathFile)) {
                throw new AssertionError("Maven did not generate " + classpathFile);
            }
            String dependencyClasspath = Files.readString(classpathFile, StandardCharsets.UTF_8).trim();
            List<Path> entries = dependencyClasspath.isBlank() ? List.of()
                    : Arrays.stream(dependencyClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                            .map(Path::of).map(Path::toAbsolutePath).map(Path::normalize).toList();
            return new ConsumerBuild(work, dependencyClasspath, entries);
        } catch (IOException e) {
            throw new AssertionError("Cannot prepare Maven consumer fixture " + fixtureName, e);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String mavenExecutable() {
        return isWindows() ? ROOT.resolve("mvnw.cmd").toString() : ROOT.resolve("mvnw").toString();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static String mavenRepository() {
        String configured = System.getProperty("maven.repo.local", "").trim();
        if (configured.isEmpty()) {
            configured = System.getenv().getOrDefault("SUT_M2_REPO", "").trim();
        }
        if (configured.isEmpty()) {
            configured = Path.of(System.getProperty("user.home"), ".m2", "repository").toString();
        }
        return Path.of(configured).toAbsolutePath().normalize().toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void runProcess(List<String> command, Path directory, Path log, Duration timeout, String label) {
        try {
            Files.createDirectories(log.getParent());
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            builder.environment().keySet().removeIf(key -> key.startsWith("LLM_"));
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AssertionError(label + " timed out after " + timeout);
            }
            if (process.exitValue() != 0) {
                throw new AssertionError(label + " failed with exit=" + process.exitValue() + "\n" + readLog(log));
            }
        } catch (IOException e) {
            throw new AssertionError(label + " could not start", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(label + " was interrupted", e);
        }
    }

    private static String readLog(Path log) {
        try {
            String text = Files.readString(log, StandardCharsets.UTF_8);
            int max = 20_000;
            return text.length() <= max ? text : text.substring(text.length() - max);
        } catch (IOException e) {
            return "<unreadable log: " + e.getMessage() + ">";
        }
    }

    public record ConsumerBuild(Path projectDirectory, String dependencyClasspath, List<Path> classpathEntries) {
        public ConsumerRun run(String mainClass, String... args) {
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.add("-cp");
            command.add(projectDirectory.resolve("target/classes") + File.pathSeparator + dependencyClasspath);
            command.add(mainClass);
            command.addAll(List.of(args));
            String safeName = mainClass.substring(mainClass.lastIndexOf('.') + 1);
            Path log = projectDirectory.resolve("target/run-" + safeName + "-" + System.nanoTime() + ".log");
            runProcess(command, projectDirectory, log, RUN_TIMEOUT, "Consumer " + mainClass);
            String output = readLog(log);
            Map<String, String> values = new LinkedHashMap<>();
            output.lines().forEach(line -> {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
                }
            });
            return new ConsumerRun(output, Map.copyOf(values));
        }

        public boolean classpathContains(String artifactFragment) {
            return classpathEntries.stream().map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.contains(artifactFragment));
        }

        public long classpathCount(String artifactFragment) {
            return classpathEntries.stream().map(path -> path.getFileName().toString())
                    .filter(name -> name.contains(artifactFragment)).count();
        }
    }

    public record ConsumerRun(String output, Map<String, String> values) {
        public String value(String key) {
            String value = values.get(key);
            if (value == null) {
                throw new AssertionError("Consumer output does not contain " + key + "\n" + output);
            }
            return value;
        }
    }
}
