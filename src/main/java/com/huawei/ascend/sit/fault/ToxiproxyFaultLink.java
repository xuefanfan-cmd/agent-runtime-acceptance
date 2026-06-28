package com.huawei.ascend.sit.fault;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

import java.io.IOException;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link FaultLink} 的 toxiproxy 实现。
 *
 * <p>容器生命周期由 {@link ToxiproxyContainer}（testcontainers 2.0.5，
 * {@code org.testcontainers.toxiproxy} 包）管理；
 * 代理控制面经 {@link ToxiproxyClient} 直连容器控制端口 8474。每个 {@code ToxiproxyFaultLink}
 * 在容器内独占一个 8666-8697 的监听端口（容器构造时已 expose 并映射），转发到
 * {@code upstreamHost:upstreamPort}。
 *
 * <p><b>寻址二义性（重要）</b>：
 * <ul>
 *   <li>{@code upstreamHost} —— toxiproxy <b>容器内</b>视角可达 upstream（test JVM / SUT 进程）
 *       的地址。Linux Docker 桥接网关 {@code 172.17.0.1}（本环境 application-local.yml 已验证）；
 *       {@code host.docker.internal} 为可移植替代（testcontainers 在部分平台映射）。</li>
 *   <li>{@link #listenUrl()} —— test JVM 视角可达容器的地址（{@code container.getHost():mappedPort}）。</li>
 * </ul>
 */
final class ToxiproxyFaultLink implements FaultLink {

    /** 容器内视角的 upstream 主机（test JVM / SUT 所在宿主机）。 */
    static final String DEFAULT_UPSTREAM_HOST = "172.17.0.1";

    private static final int FIRST_PROXIED_PORT = 8666;
    private static final int LAST_PROXIED_PORT = 8697;

    private static final String RST_UP = "fault-reset-upstream";
    private static final String RST_DOWN = "fault-reset-downstream";

    /**
     * 同一容器上多 link 共享的监听端口分配器：toxiproxy 每容器暴露 8666-8697（32 端口），
     * 每新建一个 {@link ToxiproxyFaultLink} 领用下一个。分配按容器单调递增（{@link #close()}
     * 不回收端口——32 对现有所有测试足够）。WeakHashMap 本身非线程安全，且
     * {@link java.util.Collections#synchronizedMap} 并不覆盖 {@code computeIfAbsent} 等默认方法，
     * 故所有访问必须在 {@code PORT_ALLOC} 监视器内（见 {@link #allocatePort}）。
     */
    private static final Map<ToxiproxyContainer, AtomicInteger> PORT_ALLOC = new WeakHashMap<>();

    private final ToxiproxyContainer container;
    private final Proxy proxy;
    private final int listenPortInside;
    private volatile boolean active;

    ToxiproxyFaultLink(ToxiproxyContainer container, String name,
                       String upstreamHost, int upstreamPort) {
        this.container = container;
        this.listenPortInside = allocatePort(container);
        try {
            ToxiproxyClient client =
                    new ToxiproxyClient(container.getHost(), container.getControlPort());
            this.proxy = client.createProxy(name,
                    "0.0.0.0:" + listenPortInside,
                    upstreamHost + ":" + upstreamPort);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create toxiproxy '" + name
                    + "' -> " + upstreamHost + ":" + upstreamPort, e);
        }
    }

    private static int allocatePort(ToxiproxyContainer c) {
        synchronized (PORT_ALLOC) {
            AtomicInteger next = PORT_ALLOC.computeIfAbsent(c, k -> new AtomicInteger(FIRST_PROXIED_PORT));
            int p = next.getAndIncrement();
            if (p > LAST_PROXIED_PORT) {
                throw new IllegalStateException(
                        "Toxiproxy listen-port range (8666-8697) exhausted for one container");
            }
            return p;
        }
    }

    @Override
    public String listenHost() {
        return container.getHost();
    }

    @Override
    public int listenPort() {
        return container.getMappedPort(listenPortInside);
    }

    @Override
    public String listenUrl() {
        return "http://" + listenHost() + ":" + listenPort();
    }

    @Override
    public void resetPeer() {
        // 先幂等移除同名 toxic 再重新挂载：toxiproxy 对重名 toxic 返回 409 Conflict，
        // 若不先移除，重复调用 resetPeer 会抛 IllegalStateException；同时也让 resetPeer
        // 在前一次部分失败后可安全重试。
        removeToxicQuietly(RST_UP);
        removeToxicQuietly(RST_DOWN);
        // 双向 reset_peer（timeout=0 立即 RST）：
        //   UPSTREAM   → 服务端 socket 收到 RST（触发其清理在途任务）
        //   DOWNSTREAM → test 客户端 socket 收到 RST（其 SSE 读快速失败）
        // 双向确保两端都可靠观测到突然断开。
        try {
            proxy.toxics().resetPeer(RST_UP, ToxicDirection.UPSTREAM, 0);
            proxy.toxics().resetPeer(RST_DOWN, ToxicDirection.DOWNSTREAM, 0);
            active = true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to apply reset_peer toxic", e);
        }
    }

    @Override
    public void restore() {
        removeToxicQuietly(RST_UP);
        removeToxicQuietly(RST_DOWN);
        active = false;
    }

    @Override
    public void retarget(String upstreamHost, int upstreamPort) {
        // setUpstream 仅改转发目标，不动 listen 绑定——listen URL 已写进被代理 agent 的
        // card-endpoint 属性，必须稳定。
        try {
            proxy.setUpstream(upstreamHost + ":" + upstreamPort);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to retarget fault-link upstream to "
                    + upstreamHost + ":" + upstreamPort, e);
        }
    }

    private void removeToxicQuietly(String name) {
        try {
            proxy.toxics().get(name).remove();
        } catch (IOException ignored) {
            // toxic 不存在（如未 resetPeer 即 restore）—— 良性，忽略
        }
    }

    @Override
    public boolean isFaultActive() {
        return active;
    }

    @Override
    public void close() {
        restore();
        try {
            proxy.delete();
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
