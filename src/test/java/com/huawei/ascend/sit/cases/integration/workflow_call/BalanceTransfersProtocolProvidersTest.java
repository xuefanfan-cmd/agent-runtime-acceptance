package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.transport.MessageProtocol;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 {@link AbstractBalanceThenTransfersTest#protocols()} 各叶子的协议集——非门禁单测：不 extend
 * {@code BaseManagedStackTest}、不拉栈，{@code @BeforeAll} 不触发，plain {@code ./mvnw -Dtest=... test} 即跑。
 *
 * <p>为何需要它：{@code balanceThenTransfers} 参数化模板仅在 openjiuwen profile 下执行（叶子 {@code buildStack}
 * 第一行 {@code assumeTrue} 在 {@code @BeforeAll} 里 abort → 整类 skip），故一次普通 {@code mvnw test}
 * <b>不一定</b>触发 {@code @MethodSource("protocols")} 解析——坏工厂方法会被 profile 门掩盖。本类直连调
 * {@code protocols()} 兜底验证 provider 返回正确的协议集。
 */
class BalanceTransfersProtocolProvidersTest {

    @Test
    void default_protocols_are_a2a_stream_and_rest_query() {
        Stream<MessageProtocol> protocols = new PlanAgentDirectStreamingTest().protocols();
        assertThat(protocols).containsExactly(MessageProtocol.A2A_STREAM, MessageProtocol.REST_QUERY);
    }

    @Test
    void gateway_direct_protocols_is_rest_gateway_only() {
        Stream<MessageProtocol> protocols = new PlanAgentGatewayDirectStreamingTest().protocols();
        assertThat(protocols).containsExactly(MessageProtocol.REST_GATEWAY);
    }
}
