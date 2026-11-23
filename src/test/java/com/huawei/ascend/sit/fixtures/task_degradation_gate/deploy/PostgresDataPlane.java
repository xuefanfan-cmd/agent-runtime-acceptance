package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedContainer;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A test-owned PostgreSQL wired as the DA shared data plane ({@code deepanalyze.data.pg.*}). Before the DA starts,
 * the shared data-plane baseline (the tool-platform tables the DA reads, e.g. {@code agent_teams}) is replayed from
 * the repository's {@code /deepanalyze/da-dataplane-baseline.sql}, the same baseline the FEAT-069 suites use.
 */
public final class PostgresDataPlane implements AutoCloseable {
    static final String DB = "feat112";
    static final String USER = "feat112";
    static final String PASSWORD = "feat112-test-only";
    static final String BASELINE = "/deepanalyze/da-dataplane-baseline.sql";

    private final ManagedContainer container;
    private final String image;

    private PostgresDataPlane(ManagedContainer container, String image) {
        this.container = container;
        this.image = image;
    }

    static PostgresDataPlane start(TestConfig config, String formName) throws Exception {
        String image = config.getString("sut.services.postgres.image", "postgres:16");
        ManagedContainer container;
        try {
            container = new TestContainerFactory(Path.of("target", "sit-logs")).start("feat112-" + formName + "-postgres", image,
                    5432, Map.of("POSTGRES_DB", DB, "POSTGRES_USER", USER, "POSTGRES_PASSWORD", PASSWORD));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("deployment form " + formName + " needs a PostgreSQL container (" + image
                    + "); Testcontainers could not start it: " + failure.getMessage(), failure);
        }
        var plane = new PostgresDataPlane(container, image);
        try {
            try (Connection connection = plane.awaitConnection(Duration.ofSeconds(60))) {
                execute(connection, baseline());
            }
            return plane;
        } catch (Exception | Error failure) {
            try { plane.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + container.host() + ":" + container.mappedPort() + "/" + DB;
    }

    /** Spring properties that enable the DA data plane on this database. */
    public Map<String, String> properties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("deepanalyze.data.pg.enabled", "true");
        p.put("deepanalyze.data.pg.jdbc-url", jdbcUrl());
        p.put("deepanalyze.data.pg.username", USER);
        p.put("deepanalyze.data.pg.password", PASSWORD);
        p.put("spring.datasource.url", jdbcUrl());
        p.put("spring.datasource.username", USER);
        p.put("spring.datasource.password", PASSWORD);
        return p;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), USER, PASSWORD);
    }

    public String describe() {
        return image + " at " + container.host() + ":" + container.mappedPort() + "/" + DB;
    }

    private Connection awaitConnection(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try {
                Connection connection = connect();
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1");
                }
                return connection;
            } catch (SQLException notReady) {
                last = notReady; // the server restarts once after initdb; retry within the bound
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("PostgreSQL not accepting connections within " + timeout.toSeconds() + "s", last);
    }

    private static String baseline() throws IOException {
        try (InputStream stream = PostgresDataPlane.class.getResourceAsStream(BASELINE)) {
            if (stream == null) throw new IllegalStateException("data-plane baseline missing on the test classpath: " + BASELINE);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Line comments stripped, statements split on a trailing semicolon (the baseline has no function bodies). */
    static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.replace("\r\n", "\n").split("\n")) {
            String stripped = line.replaceFirst("--.*$", "");
            if (stripped.isBlank()) continue;
            current.append(stripped).append('\n');
            if (stripped.trim().endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) throw new IllegalStateException("unterminated statement: " + current.toString().strip());
        return statements;
    }

    private static void execute(Connection connection, String script) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements(script)) statement.execute(sql);
        }
    }

    @Override
    public void close() {
        container.close();
    }
}
