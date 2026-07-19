package com.huawei.ascend.sit.conversation;

import org.awaitility.Awaitility;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个 Step 的 SSE 事件采集器（ConcurrentLinkedQueue + Awaitility）。镜像 {@code A2aEventCollector} 模式。
 * {@link #add(SseEvent)} 由 {@code ConversationTransport} 在 SSE 流消费时调用；
 * {@link #markStreamEnd()} 在该次 POST 的流结束后调用；{@link #awaitStreamEnd(long)} 阻塞等待流结束。
 */
public final class ConversationEventCollector {

    private final ConcurrentLinkedQueue<SseEvent> events = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean streamEnded = new AtomicBoolean(false);

    public void add(SseEvent event) { events.add(event); }

    public void markStreamEnd() { streamEnded.set(true); }

    /** 阻塞直到该次 POST 的 SSE 流结束（或超时）。 */
    public void awaitStreamEnd(long timeoutMs) {
        Awaitility.await("stream end")
                .atMost(timeoutMs, TimeUnit.MILLISECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .untilTrue(streamEnded);
    }

    /** 非破坏性快照。 */
    public List<SseEvent> snapshot() { return new ArrayList<>(events); }
}
