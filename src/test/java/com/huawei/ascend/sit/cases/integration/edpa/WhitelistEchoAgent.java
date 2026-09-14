/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa;

import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.exception.AgentExecutionException;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * FEAT-036 矩阵 #22（val.business-type-reject）专用<b>测试自有业务白名单 Agent</b>。
 *
 * <p>背景（特性档 §2/§3、L2 §7.3）：文件类型白名单校验（{@code EDP-FILE-003}）按契约归
 * <b>业务 Agent</b>，runtime 只做协议级校验、不产生、不翻译、不吞掉该错误。存量 SUT
 * （echo-agent 为固定回显 stub、mp demo 无白名单配置）均无业务白名单，该 red-first 用例
 * 无法闭环。本类在<b>测试 JVM 内</b>以内嵌 {@code agent-service-app:0.1.2}（pom test-scope
 * 依赖，与 echo-agent 进程内捆绑的 runtime 完全同款）启动一个携带业务白名单的业务 Agent：
 * 白名单仅 {@code application/pdf}，其余文件类型以
 * {@link AgentExecutionException} + {@link AgentFailureDescriptor}("EDP-FILE-003") 拒绝。
 *
 * <p><b>错误透出管道（被测面）</b>：业务 handler 抛 {@code AgentExecutionException} →
 * {@code A2AAgentExecutor.executeAdmitted} catch → {@code failAndDrain} →
 * {@code AgentEmitter.fail}（Task 达 FAILED）→ status.message.parts[0].text 携带业务
 * message、status.message.metadata["openjiuwen.error"] = {schemaVersion, code, numericCode,
 * retryable}（{@code A2aErrorMetadata.encode}）。即：业务码经 runtime 真实管道<b>原样透出</b>
 * （非 -32602、非 5xx、非静默降级）——正是矩阵 #22 的判据面。
 *
 * <p>配置镜像 echo-agent demo 的 application.yml（DataSource/Flyway 排除、bus 消费关闭、
 * query.webflux 关闭），仅端口改为随机。属测试资产（P-M7 豁免同 GatewayStub/FileServerStub），
 * 不改动 agent-solution / agent-runtime-java 等产品源码。
 */
final class WhitelistEchoAgent {

    static final String CODE_EDP_FILE_003 = "EDP-FILE-003";

    /** 业务白名单：仅 PDF（测试设计 §6.3 #22 行 1）。 */
    private static final Set<String> ALLOWED_MEDIA_TYPES = Set.of("application/pdf");

    /** 与 A2aErrorMetadata.encode 对齐的业务码数值面（可选，透出为 numericCode）。 */
    private static final int NUMERIC_EDP_FILE_003 = 40003;

    private static volatile ConfigurableApplicationContext context;
    private static volatile String baseUrl;

    private WhitelistEchoAgent() {
    }

    /** 惰性启动（随机端口），返回基础 URL（fixtures post() 内部追加 "/a2a"）。幂等。 */
    static synchronized String start() {
        if (baseUrl != null) {
            return baseUrl;
        }
        context = new SpringApplicationBuilder(App.class)
                .properties(
                        "server.port=0",
                        "spring.application.name=whitelist-echo-agent",
                        // 镜像 echo-agent demo application.yml：无库表依赖。
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                        "spring.flyway.enabled=false",
                        "openjiuwen.service.service-id=whitelist-echo-agent",
                        "openjiuwen.service.query.legacy-path-enabled=true",
                        "openjiuwen.service.query.webflux.enabled=false",
                        // 测试环境无 RocketMQ，与 echo-agent 同款禁用总线消费。
                        "openjiuwen.service.bus.consumer.enabled=false",
                        "agent-bus.role.runtime.enabled=false",
                        "agent-bus.role.caller.enabled=false",
                        "agent-bus.reliability.enabled=false")
                .run();
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        return baseUrl;
    }

    /** 测试类结束后关闭（与 @AfterAll 桩清理同款纪律）。 */
    static synchronized void stop() {
        if (context != null) {
            context.close();
            context = null;
            baseUrl = null;
        }
    }

    /** 最小装配：无组件扫描（避免误扫测试树），仅注册白名单 handler bean。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        AgentHandler whitelistAgentHandler() {
            return new Handler();
        }
    }

    /**
     * 白名单业务 handler。入站 Part 的业务侧形态（A2AProtocolAdapter 映射）：
     * {kind:"raw", bytesBase64, byteSize, filename?, mediaType?} /
     * {kind:"url", url, filename?, mediaType?}——按 mediaType 判白名单。
     */
    static final class Handler implements AgentHandler {

        @Override
        public QueryResponse query(ServeRequest request) {
            checkWhitelist(request);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("role", "assistant");
            result.put("content", "whitelist-ok: " + request.lastUserQuery());
            result.put("tenantId", String.valueOf(request.getTenantId()));
            return new QueryResponse(result, request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            try {
                QueryResponse response = query(request);
                observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, response.getResult()));
                observer.onComplete();
            } catch (RuntimeException e) {
                observer.onError(e);
            }
        }

        private void checkWhitelist(ServeRequest request) {
            for (Map<String, Object> part : request.lastUserParts()) {
                String kind = String.valueOf(part.get("kind"));
                if (!"raw".equals(kind) && !"url".equals(kind)) {
                    continue;
                }
                String mediaType = String.valueOf(part.getOrDefault("mediaType", ""));
                if (ALLOWED_MEDIA_TYPES.contains(mediaType)) {
                    continue;
                }
                String filename = String.valueOf(part.getOrDefault("filename", ""));
                // 业务层拒绝（特性档 §2 MUST）：EDP-FILE-003 经 AgentExecutionException
                // descriptor 透出（runtime 不翻译、不吞掉，A2AAgentExecutor.failAndDrain
                // → Task FAILED + openjiuwen.error 元数据）。
                throw new AgentExecutionException(
                        "EDP-FILE-003: 文件类型不在业务白名单 " + ALLOWED_MEDIA_TYPES
                                + " 内: mediaType=" + mediaType + ", filename=" + filename,
                        new AgentFailureDescriptor(CODE_EDP_FILE_003, NUMERIC_EDP_FILE_003, false),
                        null);
            }
        }
    }
}
