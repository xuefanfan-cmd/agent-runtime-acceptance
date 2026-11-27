/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintResult;
import com.openjiuwen.agents.orchestration.BlueprintScheduler;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.agents.reactrails.loopforest.observability.TraceForest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * FEAT-065/066 编排层组件用例的共用装配设施（**非测试类，不含任何 {@code @Test}**）。
 *
 * <p>存在的理由：三份测试设计细档 §6 要求"标记串带用例 ID + 随机后缀、不依赖方法顺序或其他用例
 * 残留状态"（RULES「测试证据门禁」第 6 条），且"确定性执行体"是 FEAT-065 §3「成员绑定契约」
 * 明文交给装配方的位置。把执行体与调度器装配收敛到一处，避免 12 个测试类各写一份而口径漂移。
 *
 * <p><b>这不是 Mock LLM</b>：执行体由测试提供是设计给出的装配方式（FEAT-065 §3、NC-02），
 * 真实模型成员形态由 065-S22 / 066-S16 / 067-S16 的端到端用例承接，两层互为对照。
 */
final class OrchestrationTestSupport {

    /** 闩锁类用例的等待上限（秒）——测试装置参数，非产品判据。 */
    static final int LATCH_TIMEOUT_SECONDS = 10;

    private OrchestrationTestSupport() {
    }

    /** 8 位随机后缀，保证同一 JVM 内多次运行的数据唯一。 */
    static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 唯一标记串：{@code M-<用例ID>-<成员ID>-<随机后缀>}。 */
    static String marker(final String caseId, final String memberId, final String run) {
        return "M-" + caseId + "-" + memberId + "-" + run;
    }

    /**
     * 记录型确定性执行体：记录实收的两个输入位原文与调用次数，
     * 可配置为返回指定文本 / 抛异常 / 阻塞在闩锁上。
     *
     * <p><b>不在执行体内部做断言</b>——它跑在调度器的线程池线程上，
     * 断言失败只会变成 {@code ExecutionException}，反而掩盖真实原因；
     * 一切断言都在测试线程上对记录下来的原文做。
     */
    static final class Recording implements SubAgentExecutor {

        private final String memberId;
        private final String defaultOutput;
        private final List<String[]> received = Collections.synchronizedList(new ArrayList<>());
        private volatile int calls;
        private Supplier<String> outputSupplier;
        private RuntimeException failure;
        private CountDownLatch latch;
        private volatile String latchFailureMessage;
        private CountDownLatch gate;
        private volatile String gateMessage;

        Recording(final String memberId, final String defaultOutput) {
            this.memberId = memberId;
            this.defaultOutput = defaultOutput;
        }

        @Override
        public String execute(final String userInput, final String subGoal) {
            calls++;
            received.add(new String[] {userInput, subGoal});
            if (gate != null) {
                try {
                    if (!gate.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(gateMessage == null
                                ? "gate timeout: member=" + memberId : gateMessage);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while awaiting gate: " + memberId, ex);
                }
            }
            if (latch != null) {
                latch.countDown();
                try {
                    if (!latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(latchFailureMessage == null
                                ? "latch timeout: member=" + memberId + " 层内未真并发"
                                : latchFailureMessage);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while awaiting latch: " + memberId, ex);
                }
            }
            if (failure != null) {
                throw failure;
            }
            return outputSupplier != null ? outputSupplier.get() : defaultOutput;
        }

        String memberId() {
            return memberId;
        }

        int calls() {
            return calls;
        }

        /** 令执行体返回指定文本（覆盖默认标记串）。 */
        Recording returns(final String output) {
            this.outputSupplier = () -> output;
            return this;
        }

        /** 令执行体每次返回不同的动态文本。 */
        Recording returnsDynamic(final Supplier<String> supplier) {
            this.outputSupplier = supplier;
            return this;
        }

        /** 令执行体抛出指定运行时异常（失败注入）。 */
        Recording throwsOnExecute(final RuntimeException ex) {
            this.failure = ex;
            return this;
        }

        /**
         * 令执行体阻塞到外部门闸放行（不 countDown）——用于把"同层另一步仍在跑"这一时序固定下来，
         * 使断言不与该步的留痕写入发生竞态。
         */
        Recording blocksUntil(final CountDownLatch gate, final String timeoutMessage) {
            this.gate = gate;
            this.gateMessage = timeoutMessage;
            return this;
        }

        /** 令执行体到达即等待闩锁（证明层内真并发；串行则必然超时）。 */
        Recording awaitsLatch(final CountDownLatch latch, final String timeoutMessage) {
            this.latch = latch;
            this.latchFailureMessage = timeoutMessage;
            return this;
        }

        /** 第 index 次调用实收的原始用户输入位。 */
        String userInput(final int index) {
            return received.get(index)[0];
        }

        /** 第 index 次调用实收的步骤目标位（含投影）。 */
        String subGoal(final int index) {
            return received.get(index)[1];
        }

        String lastUserInput() {
            return received.get(received.size() - 1)[0];
        }

        String lastSubGoal() {
            return received.get(received.size() - 1)[1];
        }

        List<String> allSubGoals() {
            synchronized (received) {
                final List<String> out = new ArrayList<>();
                for (String[] pair : received) {
                    out.add(pair[1]);
                }
                return out;
            }
        }
    }

    /** 按图纸声明的成员逐个建记录型执行体（默认回显唯一标记串）。 */
    static Map<String, Recording> recorders(final BlueprintSpec spec, final String caseId, final String run) {
        final Map<String, Recording> map = new LinkedHashMap<>();
        for (MemberSpec member : spec.members()) {
            map.put(member.id(), new Recording(member.id(), marker(caseId, member.id(), run)));
        }
        return map;
    }

    /** 把记录型执行体适配成调度器要的绑定表。 */
    static Map<String, SubAgentExecutor> bindings(final Map<String, Recording> recorders) {
        final Map<String, SubAgentExecutor> map = new LinkedHashMap<>();
        recorders.forEach(map::put);
        return map;
    }

    /** 用测试自持的 {@link TraceForest} 跑一次并返回结果（调度器按 try-with-resources 关闭）。 */
    static BlueprintResult run(final BlueprintSpec spec, final Map<String, SubAgentExecutor> bindings,
            final String userInput, final int parallelism, final TraceForest forest) {
        return run(spec, bindings, userInput, parallelism, forest, null);
    }

    /** 同上，但可注入成员消息消费面（{@code memberId → 待读留言}）。 */
    static BlueprintResult run(final BlueprintSpec spec, final Map<String, SubAgentExecutor> bindings,
            final String userInput, final int parallelism, final TraceForest forest,
            final Function<String, List<String>> messageDrain) {
        try (BlueprintScheduler scheduler = messageDrain == null
                ? new BlueprintScheduler(forest, parallelism)
                : new BlueprintScheduler(forest, parallelism, messageDrain)) {
            return scheduler.execute(spec, bindings, userInput);
        }
    }

    /** 便捷入口：装载 + 建执行体 + 执行，返回结果与执行体（供只关心产物的用例）。 */
    static Loaded load(final String yaml, final String caseId, final String run) {
        final BlueprintSpec spec = com.openjiuwen.agents.orchestration.BlueprintLoader.load(yaml);
        return new Loaded(spec, recorders(spec, caseId, run));
    }

    /** 装载产物 + 对应执行体的二元组。 */
    static final class Loaded {
        final BlueprintSpec spec;
        final Map<String, Recording> recorders;

        Loaded(final BlueprintSpec spec, final Map<String, Recording> recorders) {
            this.spec = spec;
            this.recorders = recorders;
        }

        BlueprintResult execute(final String userInput, final int parallelism, final TraceForest forest) {
            return run(spec, bindings(recorders), userInput, parallelism, forest);
        }
    }
}
