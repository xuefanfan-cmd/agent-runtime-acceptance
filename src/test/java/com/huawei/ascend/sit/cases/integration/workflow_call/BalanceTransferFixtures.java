package com.huawei.ascend.sit.cases.integration.workflow_call;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared constants + core-semantic assertions for the balance-then-transfers scenario. Local copy
 * (the abstract base keeps its own private copy) so the parallel test does not inherit its locked
 * serial {@code runFlow}. Mirrors {@link AbstractBalanceThenTransfersTest}'s constants exactly.
 */
final class BalanceTransferFixtures {

    private BalanceTransferFixtures() {}

    static final String SENTENCE = "先查下余额，再给李四和王五各转50元";
    static final String PLAN_AGENT = "edpa-plan-agent";
    static final long ROUND_TIMEOUT_MS = 600_000L;

    static final List<String> STACK_LEAK_MARKERS = List.of(
            "java.io.IOException", "Caused by:", "Exception in thread",
            "at java.base/", "at org.springframework.", "at reactor.");
    static final List<String> TOPICAL = List.of(
            "余额", "账", "转", "李四", "王五", "成功", "失败", "无法", "元");
    /** 转账完成态标记候选（宽松：命中其一即可）。*/
    static final List<String> TRANSFER_DONE = List.of(
            "转账成功", "转账信息已处理成功", "transfer_07", "SSTANDARDANSWER", "处理成功");

    /** 参考用例的语义/不泄露硬断言（gateway/direct/redis/parallel 通用）。*/
    static void assertCoreSemantics(String blob) {
        for (String m : STACK_LEAK_MARKERS) {
            assertThat(blob).as("SSE 不得泄露 JVM 堆栈").doesNotContain(m);
        }
        assertThat(blob).as("plan-agent 汇总非空").isNotBlank();
        assertThat(TOPICAL.stream().anyMatch(blob::contains))
                .as("汇总须含 余额/转账/参与者 之一").isTrue();
        assertThat(blob).as("余额笔数据(8200)").contains("8200");
        assertThat(blob).as("收款人 李四").contains("李四");
        assertThat(blob).as("收款人 王五").contains("王五");
    }

    /** 把一个事件流的 text + data 值拼成一个 blob（镜像 AbstractBalanceThenTransfersTest.concat）。*/
    static String concat(Iterable<? extends com.huawei.ascend.sit.conversation.SseEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (com.huawei.ascend.sit.conversation.SseEvent e : events) {
            if (e.text() != null) sb.append(e.text());
            if (e.data() != null) {
                e.data().values().forEach(v -> { if (v != null) sb.append(v); });
            }
        }
        return sb.toString();
    }
}
