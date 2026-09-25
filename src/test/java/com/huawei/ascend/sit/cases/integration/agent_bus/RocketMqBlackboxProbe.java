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
        awaitAssignmentAndSeekToEnd();
    }

    /**
     * A fresh lite-pull consumer group with CONSUME_FROM_LAST_OFFSET does not reliably start at the
     * queue end (observed replaying thousands of retained history messages before reaching live
     * traffic). Poll until rebalance assigns every subscribed topic's queues, then seek every
     * assigned queue to its end so the probe observes only messages published from now on.
     */
    private void awaitAssignmentAndSeekToEnd() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean allAssigned;
        do {
            allAssigned = true;
            for (DefaultLitePullConsumer consumer : consumers) {
                if (consumer.assignment().isEmpty()) {
                    allAssigned = false;
                    consumer.poll(250L);
                }
            }
        } while (!allAssigned && System.nanoTime() < deadline);
        for (DefaultLitePullConsumer consumer : consumers) {
            for (org.apache.rocketmq.common.message.MessageQueue queue : consumer.assignment()) {
                consumer.seekToEnd(queue);
            }
        }
    }

    public List<ObservedMessage> awaitAtLeast(int count, Predicate<ObservedMessage> predicate,
                                              Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<ObservedMessage> matched = matching(predicate);
        while (System.nanoTime() < deadline && matched.size() < count) {
            pollAll();
            matched = matching(predicate);
        }
        assertThat(matched).as("matching public Agent Bus messages").hasSizeGreaterThanOrEqualTo(count);
        return List.copyOf(matched);
    }

    /** Keep polling every subscribed topic for the given window so late duplicates become visible. */
    public void drain(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            pollAll();
        }
    }

    /**
     * Poll until no new message arrives for the quiet window (late duplicates / broker redelivery
     * become visible), bounded by the total cap. Lite-pull delivery is throttled per poll cycle,
     * so a fixed window can assert before the consumer has caught up; quiet-based draining only
     * makes the observation window longer, strengthening duplicate/loss detection.
     */
    public void drainUntilQuiet(Duration quiet, Duration maxTotal) {
        long totalDeadline = System.nanoTime() + maxTotal.toNanos();
        long quietDeadline = System.nanoTime() + quiet.toNanos();
        while (System.nanoTime() < totalDeadline) {
            int before = observed.size();
            pollAll();
            if (observed.size() > before) {
                quietDeadline = System.nanoTime() + quiet.toNanos();
            }
            if (System.nanoTime() >= quietDeadline) {
                return;
            }
        }
    }

    private void pollAll() {
        for (DefaultLitePullConsumer consumer : consumers) {
            for (MessageExt message : consumer.poll(250)) {
                observed.add(new ObservedMessage(message.getTopic(),
                        message.getProperty("eventType"), message.getProperty("tenantId"),
                        message.getProperty("messageId"), message.getProperty("correlationId"),
                        message.getProperty("sourceServiceId"), message.getProperty("targetServiceId"),
                        message.getProperty("payloadRef"), message.getProperty("inlinePayload"),
                        new String(message.getBody(), StandardCharsets.UTF_8)));
            }
        }
    }

    public List<ObservedMessage> matching(Predicate<ObservedMessage> predicate) {
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
