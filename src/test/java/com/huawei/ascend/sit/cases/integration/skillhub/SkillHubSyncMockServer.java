package com.huawei.ascend.sit.cases.integration.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * FEAT-072 SIT 用例的进程内 Skill Hub 替身。
 *
 * <p>复刻 {@code OpenJiuwenSkillHubProvider}/{@code OpenJiuwenSkillHubCatalogAdapter} 消费的
 * Hub HTTP 契约（契约取自 2026-10 真实 Hub 五件套的实测抓包）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/plugins?plugin_type=skill&page=N&page_size=200} — 目录分页列表，
 *       响应 {@code {"data":{"items":[{asset_id,name,display_name,description,latest_version}],"total":N,"page":N,"page_size":M}}}；
 *       私有资产不出现在列表（Hub 侧对任何身份都过滤私有 —— 见 FEAT-072 测试方案 §4.4）。</li>
 *   <li>{@code GET /api/v1/artifacts/{id}?version=V} — 单资产元数据，
 *       响应 {@code {"data":{"download_url","checksum_sha256","file_size","name","version"}}}。</li>
 *   <li>{@code GET /downloads/{id}-{version}.zip} — 技能包 zip（SKILL.md + plugin.yaml）。</li>
 *   <li>{@code GET /identity/user} — 可选身份校验端点（对应 demo 的
 *       {@code openjiuwen.service.middleware.skillhub.identity-check-url}），
 *       凭据不匹配返回 401，匹配返回 200。</li>
 * </ul>
 *
 * <p>目录页固定单页返回（条目数 &lt; 200），满足适配器的分页校验
 * （items 数小于 PAGE_SIZE 即终止翻页）。
 */
final class SkillHubSyncMockServer implements AutoCloseable {

    /** 技能可见性：私有资产不出现在目录列表（Hub 侧语义）。 */
    enum Visibility {
        PUBLIC, PRIVATE
    }

    /** 一个技能包版本的内容：SKILL.md 的 marker 与 plugin.yaml 的 description。 */
    record SkillPackage(String marker, String pluginDescription) {
    }

    /** 一个技能资产：多版本 + 可见性。 */
    static final class Asset {
        private final String id;
        private final String displayName;
        private final String description;
        private final LinkedHashMap<String, SkillPackage> versions = new LinkedHashMap<>();
        private volatile Visibility visibility = Visibility.PUBLIC;

        Asset(String id, String displayName, String description, String version, SkillPackage pkg) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.versions.put(version, pkg);
        }

        String id() {
            return id;
        }

        String latestVersion() {
            List<String> keys = new ArrayList<>(versions.keySet());
            return keys.get(keys.size() - 1);
        }
    }

    /** 一次 API 调用的审计记录。 */
    record Audit(String method, String path, String query, String tokenKind) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Asset> assets = new LinkedHashMap<>();
    private final List<Audit> audits = new CopyOnWriteArrayList<>();
    private final AtomicInteger catalogRequests = new AtomicInteger();
    private final AtomicInteger artifactRequests = new AtomicInteger();
    private final AtomicInteger downloadRequests = new AtomicInteger();
    private final AtomicReference<Integer> catalogStatus = new AtomicReference<>(200);
    private final AtomicReference<Integer> artifactStatus = new AtomicReference<>(200);
    private final AtomicReference<Integer> downloadStatus = new AtomicReference<>(200);
    private final AtomicReference<String> identityValidToken = new AtomicReference<>("");
    /** 挂起下一次目录请求（SC-CRASH：崩溃注入窗口）。close() 时强制放行，避免线程泄漏。 */
    private final AtomicReference<CountDownLatch> catalogStall = new AtomicReference<>();
    private volatile boolean closed;

    private SkillHubSyncMockServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    static SkillHubSyncMockServer start() throws IOException {
        return new SkillHubSyncMockServer();
    }

    // -------------------------------------------------------------- 资产操作

    /** 新增一个公开技能（单版本）。 */
    void addSkill(String id, String displayName, String version, String marker) {
        synchronized (assets) {
            assets.put(id, new Asset(id, displayName,
                    "FEAT-072 SIT skill " + id + ": reply with the marker in SKILL.md.",
                    version, new SkillPackage(marker, "SIT skill " + id + " " + version)));
        }
    }

    /** 为既有资产发布新版本（SKILL.md marker 与 plugin.yaml 描述可分别变化）。 */
    void publishVersion(String id, String version, String marker, String pluginDescription) {
        synchronized (assets) {
            Asset asset = assets.get(id);
            if (asset == null) {
                throw new IllegalStateException("unknown asset id " + id);
            }
            asset.versions.put(version, new SkillPackage(marker, pluginDescription));
        }
    }

    /** 同版本重发：仅变化 plugin.yaml 描述（SKILL.md 不变）→ 客户端应判 METADATA_CHANGED。 */
    void republishSameVersionMetadataOnly(String id, String version, String pluginDescription) {
        synchronized (assets) {
            Asset asset = assets.get(id);
            SkillPackage old = asset.versions.get(version);
            asset.versions.put(version, new SkillPackage(old.marker(), pluginDescription));
        }
    }

    /** 同版本重发：SKILL.md 内容变化（marker 变）→ 内容摘要变化触发重下载。 */
    void republishSameVersionContent(String id, String version, String marker) {
        synchronized (assets) {
            Asset asset = assets.get(id);
            SkillPackage old = asset.versions.get(version);
            asset.versions.put(version, new SkillPackage(marker, old.pluginDescription()));
        }
    }

    /** 资产从目录列表消失（等价 Hub 删行/转私有）→ 客户端应走删除计划。 */
    void removeSkill(String id) {
        synchronized (assets) {
            assets.remove(id);
        }
    }

    /** 调整可见性：PRIVATE 不进列表、但直达 artifacts 端点仍可查（Hub 直达端点凭据感知语义）。 */
    void setVisibility(String id, Visibility visibility) {
        synchronized (assets) {
            assets.get(id).visibility = visibility;
        }
    }

    // -------------------------------------------------------------- 故障注入

    void setCatalogStatus(int status) {
        catalogStatus.set(status);
    }

    void setArtifactStatus(int status) {
        artifactStatus.set(status);
    }

    void setDownloadStatus(int status) {
        downloadStatus.set(status);
    }

    /** 配置身份端点认可的 Bearer token；不匹配的 token 返回 401。 */
    void setIdentityValidToken(String token) {
        identityValidToken.set(token);
    }

    /** 挂起下一次（以及后续所有）目录请求直到 latch 归零或服务器关闭。 */
    void stallCatalog(CountDownLatch latch) {
        catalogStall.set(latch);
    }

    void clearCatalogStall() {
        catalogStall.set(null);
    }

    // -------------------------------------------------------------- 观测

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String identityEndpoint() {
        return endpoint() + "/identity/user";
    }

    int catalogRequests() {
        return catalogRequests.get();
    }

    int downloadRequests() {
        return downloadRequests.get();
    }

    List<Audit> audits() {
        return List.copyOf(audits);
    }

    // -------------------------------------------------------------- HTTP

    private void handle(HttpExchange exchange) throws IOException {
        try {
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            audits.add(new Audit(exchange.getRequestMethod(), path, uri.getRawQuery() == null ? "" : uri.getRawQuery(),
                    tokenKind(exchange)));
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{}");
                return;
            }
            if (path.equals("/api/v1/plugins")) {
                handleCatalog(exchange);
                return;
            }
            if (path.startsWith("/api/v1/artifacts/")) {
                handleArtifact(exchange, path, uri.getRawQuery());
                return;
            }
            if (path.startsWith("/downloads/")) {
                handleDownload(exchange, path);
                return;
            }
            if (path.equals("/identity/user")) {
                handleIdentity(exchange);
                return;
            }
            send(exchange, 404, "{\"error\":\"unknown\"}");
        } finally {
            exchange.close();
        }
    }

    private void handleCatalog(HttpExchange exchange) throws IOException {
        catalogRequests.incrementAndGet();
        CountDownLatch stall = catalogStall.get();
        if (stall != null) {
            try {
                if (!stall.await(60, TimeUnit.SECONDS)) {
                    send(exchange, 500, "{\"error\":\"stalled\"}");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(exchange, 500, "{\"error\":\"interrupted\"}");
                return;
            }
        }
        int status = catalogStatus.get();
        if (status != 200) {
            send(exchange, status, "{\"error\":\"catalog failure injected\"}");
            return;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        synchronized (assets) {
            for (Asset asset : assets.values()) {
                if (asset.visibility == Visibility.PRIVATE) {
                    continue;
                }
                items.add(Map.of(
                        "asset_id", asset.id,
                        "name", asset.id,
                        "display_name", asset.displayName,
                        "description", asset.description,
                        "latest_version", asset.latestVersion()));
            }
        }
        Map<String, Object> data = Map.of(
                "items", items,
                "total", items.size(),
                "page", 1,
                "page_size", 200);
        send(exchange, 200, JSON.writeValueAsString(Map.of("data", data)));
    }

    private void handleArtifact(HttpExchange exchange, String path, String rawQuery) throws IOException {
        artifactRequests.incrementAndGet();
        int status = artifactStatus.get();
        if (status != 200) {
            send(exchange, status, "{\"error\":\"artifact failure injected\"}");
            return;
        }
        String id = path.substring("/api/v1/artifacts/".length());
        String version = queryParam(rawQuery, "version");
        Asset asset;
        synchronized (assets) {
            asset = assets.get(id);
        }
        if (asset == null) {
            send(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }
        String resolved = version == null || version.isBlank() || !asset.versions.containsKey(version)
                ? asset.latestVersion()
                : version;
        SkillPackage pkg = asset.versions.get(resolved);
        byte[] zip = skillZip(asset, resolved, pkg);
        Map<String, Object> data = Map.of(
                "download_url", endpoint() + "/downloads/" + asset.id + "-" + resolved + ".zip",
                "checksum_sha256", sha256(zip),
                "file_size", zip.length,
                "name", asset.id,
                "version", resolved);
        send(exchange, 200, JSON.writeValueAsString(Map.of("data", data)));
    }

    private void handleDownload(HttpExchange exchange, String path) throws IOException {
        downloadRequests.incrementAndGet();
        int status = downloadStatus.get();
        if (status != 200) {
            send(exchange, status, "download failure injected".getBytes(StandardCharsets.UTF_8), "text/plain");
            return;
        }
        String file = path.substring("/downloads/".length());
        String id = file.contains("-") ? file.substring(0, file.lastIndexOf('-')) : file;
        String version = file.endsWith(".zip") && file.contains("-")
                ? file.substring(file.lastIndexOf('-') + 1, file.length() - 4)
                : "";
        Asset asset;
        synchronized (assets) {
            asset = assets.get(id);
        }
        if (asset == null) {
            send(exchange, 404, "unknown asset".getBytes(StandardCharsets.UTF_8), "text/plain");
            return;
        }
        SkillPackage pkg = asset.versions.get(version);
        if (pkg == null) {
            send(exchange, 404, "unknown version".getBytes(StandardCharsets.UTF_8), "text/plain");
            return;
        }
        send(exchange, 200, skillZip(asset, version, pkg), "application/zip");
    }

    private void handleIdentity(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + identityValidToken.get();
        if (expected.isBlank() || !expected.equals(authorization)) {
            send(exchange, 401, "{\"error\":\"Bad credentials\"}");
            return;
        }
        send(exchange, 200, "{\"login\":\"sit-tester\"}");
    }

    // -------------------------------------------------------------- 技能包

    private static byte[] skillZip(Asset asset, String version, SkillPackage pkg) {
        // description 以双引号包裹：SUT 的 SkillPathSafety.inspectSkill 用 SnakeYAML 解析
        // front matter，裸标量中的冒号（"skill xxx: reply ..."）会导致解析失败 → PACKAGE_INVALID。
        String skillMd = "---\nname: " + asset.id + "\ndescription: \"" + asset.description + "\"\n---\n"
                + "# " + asset.id + " " + version + "\n"
                + "Read this file before answering. Reply exactly with the marker `" + pkg.marker() + "`.\n";
        String pluginYaml = "name: " + asset.id + "\nversion: \"" + version + "\"\n"
                + "description: \"" + pkg.pluginDescription() + "\"\n"
                + "metadata:\n  plugin_type: skill\n";
        return zip(Map.of("SKILL.md", skillMd, "plugin.yaml", pluginYaml));
    }

    private static byte[] zip(Map<String, String> entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("could not build mock skill zip", ex);
        }
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("could not hash mock skill", ex);
        }
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static String tokenKind(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null) {
            return authorization.startsWith("Bearer ") ? "bearer" : "authorization";
        }
        return exchange.getRequestHeaders().getFirst("X-System-Token") != null ? "system-token" : "anonymous";
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void send(HttpExchange exchange, int status, byte[] bytes, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            CountDownLatch stall = catalogStall.get();
            if (stall != null) {
                stall.countDown();
            }
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
