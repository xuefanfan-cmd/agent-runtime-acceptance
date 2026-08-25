package com.huawei.ascend.sit.cases.e2e.reconnect;

import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.TaskState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Records public SDK events and exposes deterministic reconnect injection points. */
final class ReconnectEventProbe implements Flow.Subscriber<InvocationEvent> {
    private final List<InvocationEvent> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch working = new CountDownLatch(1);
    private final CountDownLatch inputRequired = new CountDownLatch(1);

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(InvocationEvent item) {
        events.add(item);
        if (item instanceof InvocationEvent.StatusChanged changed) {
            if (changed.state() == TaskState.WORKING) {
                working.countDown();
            } else if (changed.state() == TaskState.INPUT_REQUIRED) {
                inputRequired.countDown();
            }
        } else if (item instanceof InvocationEvent.InputRequired) {
            inputRequired.countDown();
        }
    }

    @Override
    public void onError(Throwable throwable) {
        // completion() remains the authoritative outcome for recovery tests.
    }

    @Override
    public void onComplete() {
        // completion() remains the authoritative outcome for recovery tests.
    }

    boolean awaitWorking(long timeout, TimeUnit unit) throws InterruptedException {
        return working.await(timeout, unit);
    }

    boolean awaitInputRequired(long timeout, TimeUnit unit) throws InterruptedException {
        return inputRequired.await(timeout, unit);
    }

    List<InvocationEvent> events() {
        return List.copyOf(events);
    }
}
