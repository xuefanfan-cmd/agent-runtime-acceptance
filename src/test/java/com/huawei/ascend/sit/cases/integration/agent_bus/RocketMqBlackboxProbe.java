package com.huawei.ascend.sit.cases.integration.agent_bus;

import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent RocketMQ consumer observing only public topic messages and user properties. */
public final class RocketMqBlackboxProbe implements AutoCloseable {
    static final List<String> TOPICS = List.of(
            "ascend_bus_invocation_req", "ascend_bus_invocation_deliver",
            "ascend_bus_invocation_resp_in", "ascend_bus_invocation_resp_out",
            "ascend_bus_a2a_req", "ascend_bus_a2a_deliver",
            "ascend_bus_a2a_resp_in", "ascend_bus_a2a_resp_out");

    private final List<DefaultLitePullConsumer> consumers = new ArrayList<>();
    private final List<ObservedMessage> observed = new ArrayList<>();

    public RocketMqBlackboxProbe() throws Exception {
        String nameserver = System.getProperty("agent.bus.nameserver");
        if (nameserver == null || nameserver.isBlank()) {
            nameserver = System.getenv("AGENT_BUS_NAMESERVER");
        }
        Assumptions.assumeTrue(nameserver != null && !nameserver.isBlank(),
                "AGENT_BUS_NAMESERVER is required for broker-observation stories");
        for (String topic : TOPICS) {
            DefaultLitePullConsumer consumer = new DefaultLitePullConsumer(
                    "acceptance-probe-" + UUID.randomUUID());
            consumer.setNamesrvAddr(nameserver);
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            consumer.subscribe(topic, "*");
            consumer.start();
            consumers.add(consumer);
        }
        Thread.sleep(2_000L);
    }

    public List<ObservedMessage> awaitAtLeast(int count, Predicate<ObservedMessage> predicate,
                                              Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<ObservedMessage> matched = matching(predicate);
        while (System.nanoTime() < deadline && matched.size() < count) {
            for (DefaultLitePullConsumer consumer : consumers) {
                for (MessageExt message : consumer.poll(250)) {
                    ObservedMessage observed = new ObservedMessage(message.getTopic(),
                            message.getProperty("eventType"), message.getProperty("tenantId"),
                            message.getProperty("messageId"), message.getProperty("correlationId"),
                            message.getProperty("sourceServiceId"), message.getProperty("targetServiceId"),
                            message.getProperty("payloadRef"), message.getProperty("inlinePayload"),
                            new String(message.getBody(), StandardCharsets.UTF_8));
                    this.observed.add(observed);
                }
            }
            matched = matching(predicate);
        }
        assertThat(matched).as("matching public Agent Bus messages").hasSizeGreaterThanOrEqualTo(count);
        return List.copyOf(matched);
    }

    private List<ObservedMessage> matching(Predicate<ObservedMessage> predicate) {
        return observed.stream().filter(predicate).toList();
    }

    @Override
    public void close() {
        consumers.forEach(DefaultLitePullConsumer::shutdown);
    }

    public record ObservedMessage(String topic, String eventType, String tenantId, String messageId,
                                  String correlationId, String sourceServiceId, String targetServiceId,
                                  String payloadRef, String inlinePayload, String body) {
    }
}
