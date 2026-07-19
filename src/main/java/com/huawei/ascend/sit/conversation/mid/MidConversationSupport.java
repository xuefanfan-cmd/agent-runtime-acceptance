package com.huawei.ascend.sit.conversation.mid;

import com.huawei.ascend.sit.conversation.mid.dto.NextRequest;
import com.huawei.ascend.sit.conversation.mid.dto.StepUI;
import com.huawei.ascend.sit.transport.HttpClients;
import com.huawei.ascend.sit.utils.JsonUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * 中台 client（→mock /admin）：取下一步 step-ui 与渲染好的 next-request。
 * next-request 的选择 KV 作为查询串 ?k=v 拼接（注入模板 userInput_<key>）。
 *
 * <p><b>cid-gap 容错</b>：envexplorer 在每个场景 END 删除 cid 行，到 agent 下一次调用才重新绑定；
 * 所有腿跑完后该行永久消失。若 {@code step-ui}/{@code next-request} 在此窗口查到 HTTP 400/404
 * （cid 暂缺或已结束），{@link #getOpt} 短重试，持续缺失则视作<b>工作流完成</b>（{@link #stepUi}
 * 返回 {@link StepUI#deserializeAuto()}、{@link #nextRequest} 返回 {@code query=null}），而非崩掉驱动器。
 * 其它非 200 仍抛。
 */
public final class MidConversationSupport {

    private static final int DEFAULT_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;

    private final String midBase;
    private final int retries;
    private final long retryDelayMs;
    // HTTP/1.1 (not the JDK HTTP_2 default): GETs hit the cleartext envmock mid-platform, whose strict
    // server could reject the h2c upgrade headers a default client emits. See transport.HttpClients.
    private final HttpClient http = HttpClients.newHttp1Client();

    public MidConversationSupport(String midBaseUrl) {
        this(midBaseUrl, DEFAULT_RETRIES, DEFAULT_RETRY_DELAY_MS);
    }

    /** Package-private: tests inject a tight retry so cid-gap assertions stay sub-second. */
    MidConversationSupport(String midBaseUrl, int retries, long retryDelayMs) {
        this.midBase = midBaseUrl;
        this.retries = retries;
        this.retryDelayMs = retryDelayMs;
    }

    /** Base URL (no trailing slash) this client targets — the envmock mid-platform address. */
    public String midBase() { return midBase; }

    public StepUI stepUi(String cid) {
        return getOpt(midBase + "/admin/conversations/" + cid + "/step-ui")
                .map(b -> JsonUtils.fromJson(b, StepUI.class))
                .orElseGet(StepUI::deserializeAuto);   // cid 持续缺失 → 工作流完成
    }

    public NextRequest nextRequest(String cid, Map<String, String> selectionKv) {
        StringBuilder url = new StringBuilder(midBase)
                .append("/admin/conversations/").append(cid).append("/next-request");
        if (selectionKv != null && !selectionKv.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (var e : selectionKv.entrySet()) {
                if (!first) url.append('&');
                url.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
                first = false;
            }
        }
        return getOpt(url.toString())
                .map(b -> JsonUtils.fromJson(b, NextRequest.class))
                .orElseGet(() -> new NextRequest(null, "auto", null, ""));   // cid 持续缺失 → 结束
    }

    /**
     * GET，对 HTTP 400/404 短重试（cid 暂缺/已结束），持续缺失返回 {@link Optional#empty()}
     * （调用方据此判定工作流完成）。其它非 200 立即抛；连接异常也重试。
     */
    private Optional<String> getOpt(String url) {
        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = resp.statusCode();
                if (code == 200) return Optional.of(resp.body());
                if (code == 400 || code == 404) { sleep(retryDelayMs); continue; }   // cid 暂缺/已结束
                throw new RuntimeException("mid GET failed: HTTP " + code + " @ " + url);
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception ex) {
                sleep(retryDelayMs);                                                  // 连接抖动
            }
        }
        return Optional.empty();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static String encode(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
