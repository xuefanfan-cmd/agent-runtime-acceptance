package com.huawei.ascend.sit.lifecycle;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the TCP listening ports owned by a process — cross-platform.
 *
 * <p>Strategy is selected by {@code os.name}:
 * <ul>
 *   <li><b>Linux</b> — pure {@code /proc}: the socket inodes open in {@code /proc/<pid>/fd/*}
 *       intersected with {@code LISTEN} (state {@code 0A}) rows of {@code /proc/net/tcp} and
 *       {@code /proc/net/tcp6}. No external command.</li>
 *   <li><b>Windows</b> — {@code cmd /c netstat -ano}, filtering {@code LISTENING} rows by the
 *       PID column (the {@code netstat -ano | findstr LISTENING} recipe, parsed in Java for
 *       precise PID matching rather than relying on a pipe + locale-dependent findstr).</li>
 *   <li><b>Other (macOS, *BSD, …)</b> — {@code lsof -nP -iTCP -sTCP:LISTEN -a -p <pid>}.</li>
 * </ul>
 *
 * <p>Used by {@link ProcessLauncher} to recover the actual port of an agent launched with
 * {@code --server.port=0} (the OS assigns the port atomically, avoiding the
 * {@code ServerSocket(0)} pick-then-bind race). The public surface is {@link #of(long)};
 * the per-format parsers are package-private pure functions and unit-tested directly with
 * fixture output, so each platform's parsing is covered without running on that OS.
 */
final class ListeningPorts {

    /** IPv4 and IPv6 TCP socket tables, in that order. */
    private static final String[] TCP_TABLES = {"/proc/net/tcp", "/proc/net/tcp6"};

    /** State field value for {@code TCP_LISTEN} in {@code /proc/net/tcp[,6]}. */
    private static final String PROC_STATE_LISTEN = "0A";

    /** Matches the numeric port in an lsof NAME token such as {@code *:8080} or {@code localhost:8080}. */
    private static final Pattern LSOF_PORT = Pattern.compile(".*[:\\]](\\d+)");

    private ListeningPorts() {
    }

    /**
     * TCP listening ports currently owned by {@code pid}. Empty if the process has none yet,
     * the platform is unsupported, or a read/command error occurs. Never throws.
     */
    static Set<Integer> of(long pid) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            return linuxProc(pid);
        }
        if (os.contains("win")) {
            return windowsNetstat(pid);
        }
        return lsof(pid);
    }

    // ---- Linux: /proc ----

    private static Set<Integer> linuxProc(long pid) {
        Set<Long> socketInodes = socketInodesOf(pid);
        if (socketInodes.isEmpty()) {
            return Set.of();
        }
        Set<Integer> ports = new HashSet<>();
        appendListenPorts(readLinesSafe("/proc/net/tcp"), socketInodes, ports);
        appendListenPorts(readLinesSafe("/proc/net/tcp6"), socketInodes, ports);
        return ports;
    }

    /** Socket inodes referenced by the pid's file descriptors ({@code /proc/<pid>/fd/*}). */
    private static Set<Long> socketInodesOf(long pid) {
        Path fdDir = Path.of("/proc", Long.toString(pid), "fd");
        Set<Long> inodes = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(fdDir)) {
            for (Path fd : ds) {
                String target = readLinkTarget(fd);
                // symlink target looks like "socket:[12345]"
                int start = target.indexOf("socket:[");
                int end = start < 0 ? -1 : target.indexOf(']', start);
                if (end > start) {
                    inodes.add(Long.parseLong(target.substring(start + 8, end)));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // process gone, /proc unavailable, or permission denied — treat as "no sockets yet"
        }
        return inodes;
    }

    /**
     * Pure parser: append LISTEN ports from {@code /proc/net/tcp[,6]} rows whose inode is in
     * {@code socketInodes}. Row layout (Linux 2.6+, fields split on whitespace):
     * {@code sl, local_addr, rem_addr, st, tx:rx, tr:tm, retrnsmt, uid, timeout, inode, ...}
     * — so the inode is at {@code f[9]} and the state at {@code f[3]}.
     */
    static void appendListenPorts(List<String> lines, Set<Long> socketInodes, Set<Integer> out) {
        for (String line : lines) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 10 || !PROC_STATE_LISTEN.equals(f[3])) {
                continue;
            }
            long inode;
            try {
                inode = Long.parseLong(f[9]);
            } catch (NumberFormatException badRow) {
                continue;
            }
            if (!socketInodes.contains(inode)) {
                continue;
            }
            int colon = f[1].indexOf(':');
            if (colon < 0) {
                continue;
            }
            // port = hex chars after the ':' in the local address
            out.add((int) Long.parseLong(f[1].substring(colon + 1), 16));
        }
    }

    private static List<String> readLinesSafe(String path) {
        try {
            return Files.readAllLines(Path.of(path));
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    private static String readLinkTarget(Path link) {
        try {
            return Files.readSymbolicLink(link).toString();
        } catch (IOException e) {
            return "";
        }
    }

    // ---- Windows: netstat -ano ----

    private static Set<Integer> windowsNetstat(long pid) {
        return parseNetstatAno(run("cmd", "/c", "netstat", "-ano"), pid);
    }

    /**
     * Pure parser for {@code netstat -ano} output. Rows look like
     * {@code TCP  <local>  <foreign>  LISTENING  <pid>}; we keep TCP rows whose state contains
     * {@code LISTEN} and whose PID column equals {@code pid}, taking the port after the last
     * {@code :} of the local address (handles IPv6 {@code [::]:port} too).
     */
    static Set<Integer> parseNetstatAno(List<String> lines, long pid) {
        Set<Integer> ports = new HashSet<>();
        for (String line : lines) {
            String[] f = line.trim().split("\\s+");
            // TCP local foreign state pid (length >= 5; header and UDP rows skipped)
            if (f.length < 5 || !f[0].equalsIgnoreCase("TCP")) {
                continue;
            }
            if (!f[3].regionMatches(true, 0, "LISTEN", 0, 6)) {
                continue;
            }
            if (!matchesPid(f[4], pid)) {
                continue;
            }
            int colon = f[1].lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            try {
                ports.add(Integer.parseInt(f[1].substring(colon + 1)));
            } catch (NumberFormatException badPort) {
                // skip unparseable port
            }
        }
        return ports;
    }

    private static boolean matchesPid(String field, long pid) {
        try {
            return Long.parseLong(field) == pid;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---- Other (macOS, *BSD, …): lsof ----

    private static Set<Integer> lsof(long pid) {
        return parseLsof(run("lsof", "-nP", "-iTCP", "-sTCP:LISTEN", "-a",
                "-p", Long.toString(pid)));
    }

    /**
     * Pure parser for {@code lsof -nP -iTCP -sTCP:LISTEN} output ({@code -P} forces numeric
     * ports). Each row's NAME token (e.g. {@code *:8080}, {@code localhost:8080},
     * {@code [::1]:8080}) yields its trailing numeric port.
     */
    static Set<Integer> parseLsof(List<String> lines) {
        Set<Integer> ports = new HashSet<>();
        for (String line : lines) {
            for (String tok : line.split("\\s+")) {
                Matcher m = LSOF_PORT.matcher(tok);
                if (m.matches()) {
                    try {
                        ports.add(Integer.parseInt(m.group(1)));
                    } catch (NumberFormatException ignored) {
                        // skip non-numeric tail
                    }
                    break;
                }
            }
        }
        return ports;
    }

    // ---- process runner (netstat / lsof) ----

    /** Run a command and return its stdout lines; empty on error or if it exceeds 5s. Never throws. */
    private static List<String> run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = process.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return List.of();
            }
            return lines;
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
