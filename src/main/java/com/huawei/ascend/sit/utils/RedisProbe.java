package com.huawei.ascend.sit.utils;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal read-only Redis (RESP) probe used by middleware/checkpointer acceptance tests to assert
 * on Redis key-space (DBSIZE / SCAN) without a jedis dependency.
 *
 * <p>Speaks raw RESP over a short-lived TCP socket — mirroring the DBSIZE probe in
 * {@code OpenjiuwenCheckpointerConfigSwitchTest}. The public surface ({@link #dbsize()},
 * {@link #keys(String)}, {@link #keysAny(String...)}) is strictly read-only (DBSIZE + SCAN).
 * Each call opens a fresh socket, so the probe is stateless and thread-safe. The package-private
 * {@link #exec(String...)} seam reuses the wire framing for the unit test's {@code SET} plants.
 */
public final class RedisProbe implements AutoCloseable {

    private static final byte[] CRLF = new byte[] {0x0D, 0x0A};

    private static final int SCAN_BATCH = 100;

    private final String host;
    private final int port;

    public RedisProbe(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Current database key count (DBSIZE). */
    public long dbsize() {
        return (Long) exec("DBSIZE");
    }

    /** All keys matching {@code glob}, aggregated across SCAN cursors. */
    public List<String> keys(String glob) {
        List<String> all = new ArrayList<>();
        long cursor = 0;
        do {
            Object[] reply = (Object[]) exec("SCAN", String.valueOf(cursor), "MATCH", glob, "COUNT", String.valueOf(SCAN_BATCH));
            cursor = Long.parseLong((String) reply[0]);
            for (Object k : (Object[]) reply[1]) {
                if (k != null) {
                    all.add((String) k);
                }
            }
        } while (cursor != 0);
        return all;
    }

    /** Keys from the first non-empty glob in {@code globs}; empty list if all miss. */
    public List<String> keysAny(String... globs) {
        for (String g : globs) {
            List<String> hit = keys(g);
            if (!hit.isEmpty()) {
                return hit;
            }
        }
        return List.of();
    }

    /** No persistent resource; present so callers may use try-with-resources. */
    @Override
    public void close() {
        // no-op
    }

    /** Package-private wire seam: send a command, return the parsed RESP reply (test reuse). */
    Object exec(String... args) {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(3000);
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(req);
            out.write('*');
            out.write(Integer.toString(args.length).getBytes(StandardCharsets.US_ASCII));
            out.write(CRLF);
            for (String a : args) {
                byte[] b = a.getBytes(StandardCharsets.UTF_8);
                out.write('$');
                out.write(Integer.toString(b.length).getBytes(StandardCharsets.US_ASCII));
                out.write(CRLF);
                out.write(b);
                out.write(CRLF);
            }
            socket.getOutputStream().write(req.toByteArray());
            socket.getOutputStream().flush();
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            return readReply(in);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Redis probe failed (" + host + ":" + port + "): " + e.getMessage(), e);
        }
    }

    private static Object readReply(DataInputStream in) throws IOException {
        String line = readLine(in);
        if (line.isEmpty()) {
            throw new IOException("empty Redis reply");
        }
        char type = line.charAt(0);
        switch (type) {
            case '+':                               // simple string
                return line.substring(1);
            case '-':                               // error
                throw new IOException("Redis error: " + line.substring(1));
            case ':':                               // integer
                return Long.parseLong(line.substring(1));
            case '$': {                             // bulk string (byte-accurate)
                int len = Integer.parseInt(line.substring(1));
                if (len < 0) {
                    return null;                    // nil
                }
                byte[] buf = new byte[len];
                in.readFully(buf);
                readCrLf(in);
                return new String(buf, StandardCharsets.UTF_8);
            }
            case '*': {                             // array
                int count = Integer.parseInt(line.substring(1));
                if (count < 0) {
                    return null;                    // nil array
                }
                Object[] arr = new Object[count];
                for (int i = 0; i < count; i++) {
                    arr[i] = readReply(in);
                }
                return arr;
            }
            default:
                throw new IOException("unknown RESP type: " + line);
        }
    }

    /** Read one RESP inline line (up to and excluding the trailing CRLF), byte-accurate, UTF-8 decoded. */
    private static String readLine(DataInputStream in) throws IOException {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') {
                byte[] bytes = acc.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8); // drop trailing \r
            }
            acc.write(c);
            prev = c;
        }
        throw new IOException("truncated RESP line");
    }

    /** Consume the CRLF that follows a fixed-length bulk payload. */
    private static void readCrLf(DataInputStream in) throws IOException {
        int cr = in.read();
        int lf = in.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("expected CRLF after bulk string, got: " + cr + ", " + lf);
        }
    }
}
