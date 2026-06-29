package com.huawei.ascend.sit.fault;

import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * 一条放在某 upstream 前面的 TCP 故障注入跳板（chaos test 基础设施）。
 *
 * <p>测试客户端 / 被测 agent 连接 {@link #listenUrl()}（取代真实 upstream）；中途调用
 * {@link #resetPeer()} 触发 TCP RST，模拟客户端/网络突然断开；{@link #restore()} 恢复转发。
 * 当前唯一实现是 {@link ToxiproxyFaultLink}（toxiproxy 后端）；接口为后端无关的扩展缝。
 *
 * <p>作为可复用的框架机制落在 {@code src/main}（chaos 测试基础设施）；
 * 其 testcontainers / toxiproxy-client 依赖在 pom 中为 compile scope，
 * 供 {@code src/test} 下的用例（如 {@code ToxiproxyFaultLinkTest}、C-10 用例）直接使用。
 */
public interface FaultLink extends AutoCloseable {

    /**
     * 容器内视角的 upstream 主机（test JVM / SUT 所在宿主机，Linux Docker 桥接网关 {@code 172.17.0.1}）。
     * 公开常量，供跨包调用方（如 {@code SutStack} 侦听关联时把 fault-link upstream 指向 agent 真实端口）使用。
     */
    String DEFAULT_UPSTREAM_HOST = "127.0.0.1";

    /** 客户端/下游应连接的地址（取代真实 upstream）。 */
    String listenUrl();

    /** 客户端/下游应连接的主机（test JVM 视角，容器映射后的主机地址）。 */
    String listenHost();

    /** 客户端/下游应连接的端口（test JVM 视角，容器映射后的主机端口）。 */
    int listenPort();

    /**
     * 强制 reset 对端连接（TCP RST）——模拟客户端/网络突然断开。
     * 挂上后对在途连接与新建连接均生效；恢复正常业务前必须调用 {@link #restore()}。
     */
    void resetPeer();

    /** 撤销 {@link #resetPeer()}，恢复正常转发。 */
    void restore();

    /**
     * 重指 upstream（转发目标）为 {@code host:port}，<b>listen 端口保持不变</b>。
     *
     * <p>用于延迟 upstream：建链时用占位 upstream（此时 {@link #listenUrl()} 已知、可写进
     * 被代理 agent 的启动参数），待 agent 拉起、真实端口解析出来后，再用本方法把 upstream
     * 关联到真实 {@code host:port}。listen 端口必须稳定——它已被写进 card-endpoint 属性。
     */
    void retarget(String upstreamHost, int upstreamPort);

    /** 当前是否处于故障激活态（reset_peer 已挂载）。 */
    boolean isFaultActive();

    /** 删除该 proxy 跳板（容器可继续承载其它 link）。 */
    @Override
    void close();

    /** 选定实现：集中"选后端"逻辑，换自研代理只改这里。 */
    static FaultLink toxiproxy(ToxiproxyContainer container, String name,
                               String upstreamHost, int upstreamPort) {
        return new ToxiproxyFaultLink(container, name, upstreamHost, upstreamPort);
    }

    /**
     * 延迟 upstream 版：占位 upstream 建链（{@link #listenUrl()} 立即可用），真实转发目标待
     * {@link #retarget(String, int)} 关联。用于 card-endpoint 重定向——agent 拉起前就需要
     * listenUrl 写进其启动参数，而其真实端口仅拉起后才知。
     */
    static FaultLink toxiproxy(ToxiproxyContainer container, String name) {
        return new ToxiproxyFaultLink(container, name, DEFAULT_UPSTREAM_HOST, 1);
    }
}
