package com.huawei.ascend.sit.cases.e2e.reconnect;

import com.huawei.ascend.sit.fault.FaultLink;
import com.huawei.ascend.sit.fixtures.reconnect.ReActReconnectFixture;
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.TaskState;
import org.junit.jupiter.api.Assumptions;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

final class ReconnectJourney {
    private static final String COMPLETE_TRAVEL_REQUEST = "请制定一份从上海到北京的三天商务出行方案，"
            + "明天出发，酒店每晚不超过800元、至少四星并靠近国贸，同时给出往返行程和酒店建议。"
            + "请在完成所有必要查询后给出最终方案。";

    private ReconnectJourney() {
    }

    static Result execute(ReActReconnectFixture environment) throws Exception {
        String conversationId = "reconnect-" + UUID.randomUUID();
        String invocationId = "inv-" + UUID.randomUUID();
        try (AgentClient client = environment.client()) {
            InvocationCall call = client.invoke(InvocationRequest.builder()
                    .agentId(ReActReconnectFixture.agentId())
                    .conversationId(conversationId)
                    .invocationId(invocationId)
                    .mode(InvocationMode.STREAMING)
                    .input(COMPLETE_TRAVEL_REQUEST)
                    .build());
            EventProbe probe = new EventProbe();
            call.events().subscribe(probe);

            String taskId = call.accepted().toCompletableFuture()
                    .get(45, TimeUnit.SECONDS).diagnosticTaskRef();
            assertThat(taskId).as("accepted diagnostic task id").isNotBlank();
            Assumptions.assumeTrue(probe.awaitWorking(45, TimeUnit.SECONDS),
                    "INCONCLUSIVE: ReAct task reached no observable WORKING window before terminal state");

            FaultLink link = environment.faultLink();
            link.resetPeer();
            try {
                Thread.sleep(150);
            } finally {
                link.restore();
            }

            InvocationSnapshot completed = call.completion().toCompletableFuture()
                    .get(180, TimeUnit.SECONDS);
            InvocationSnapshot queried = client.getInvocation(call.invocationRef())
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);

            assertThat(completed.invocationRef()).isEqualTo(call.invocationRef());
            assertThat(completed.diagnosticTaskRef()).isEqualTo(taskId);
            assertThat(completed.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(completed.terminal()).isTrue();
            assertThat(completed.maybeRecovery()).isEmpty();
            assertThat(completed.outputText()).isNotBlank();
            assertThat(queried.diagnosticTaskRef()).isEqualTo(taskId);
            assertThat(queried.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(probe.events()).noneMatch(InvocationEvent.Failed.class::isInstance);

            return new Result(environment.endpointType().name(), call.invocationRef(), taskId,
                    completed.outputText());
        }
    }

    record Result(String endpointType, String invocationRef, String taskId, String outputText) {
    }

    private static final class EventProbe implements Flow.Subscriber<InvocationEvent> {
        private final List<InvocationEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch working = new CountDownLatch(1);
        private volatile Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(InvocationEvent item) {
            events.add(item);
            if (item instanceof InvocationEvent.StatusChanged changed
                    && changed.state() == TaskState.WORKING) {
                working.countDown();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // The SDK owns transport recovery; completion() is the authoritative outcome.
        }

        @Override
        public void onComplete() {
            // completion() supplies the final snapshot asserted by the journey.
        }

        boolean awaitWorking(long timeout, TimeUnit unit) throws InterruptedException {
            return working.await(timeout, unit);
        }

        List<InvocationEvent> events() {
            return List.copyOf(events);
        }
    }
}
