package com.huawei.ascend.sit.lifecycle;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable descriptor of a Python-hosted SUT agent — the counterpart to {@link MavenArtifact}
 * for a Java jar. Where a managed Java agent is defined by {@code group/artifact/version},
 * a managed Python agent is defined by the executable {@code module} name, the {@code cwd}
 * the process should run in, the {@code PYTHONPATH} entries it needs to import, and the
 * name of the environment variable through which the process learns which port to listen on.
 *
 * <p>Rationale for env-var port (not {@code --server.port}): the Python demo servers we host
 * read their bind port from a per-agent env var (e.g. {@code DEEP_RESEARCH_PORT},
 * {@code SEARCH_AGENT_PORT}, {@code VERIFY_AGENT_PORT}) — {@code smoke_real_llm.py} injects
 * these before spawn. The SIT framework mirrors that contract: it picks a free port itself
 * (so multiple test stacks coexist), then injects it as {@code <portEnvKey>=<port>} into the
 * child's environment. Random-port-via-PID doesn't apply here — the port is known before spawn.
 *
 * <p>Readiness is checked against {@code /.well-known/agent-card.json} (the A2A Python-server
 * convention), not the Java runtime's {@code /.well-known/agent.json}.
 */
public final class PythonRuntime {

    private final String module;
    private final Path workingDirectory;
    private final List<String> pythonpath;
    private final String portEnvKey;
    private final String pythonBin;

    /**
     * @param module           the {@code -m} target (e.g. {@code "deep_research.service.application"})
     * @param workingDirectory the cwd for the child process — absolute, must exist at spawn time
     * @param pythonpath       extra entries to <em>prepend</em> to {@code PYTHONPATH}
     *                         (the demo's src dir + shared runtime root); may be empty, never null
     * @param portEnvKey       env-var name the module reads to know its bind port
     *                         (e.g. {@code "DEEP_RESEARCH_PORT"}); required
     * @param pythonBin        Python executable to invoke — {@code null} / blank ⇒ {@code "python"}
     *                         (letting {@code PATH} resolve)
     */
    public PythonRuntime(String module, Path workingDirectory, List<String> pythonpath,
                         String portEnvKey, String pythonBin) {
        this.module = requireNonBlank(module, "module");
        if (workingDirectory == null) {
            throw new IllegalArgumentException("workingDirectory must not be null");
        }
        this.workingDirectory = workingDirectory;
        this.pythonpath = pythonpath == null ? List.of() : List.copyOf(pythonpath);
        this.portEnvKey = requireNonBlank(portEnvKey, "portEnvKey");
        this.pythonBin = pythonBin == null || pythonBin.isBlank() ? "python" : pythonBin.trim();
    }

    public String module() {
        return module;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public List<String> pythonpath() {
        return pythonpath;
    }

    public String portEnvKey() {
        return portEnvKey;
    }

    public String pythonBin() {
        return pythonBin;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
