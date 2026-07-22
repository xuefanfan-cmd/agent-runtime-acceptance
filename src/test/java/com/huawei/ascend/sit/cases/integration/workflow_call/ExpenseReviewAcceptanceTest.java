package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;

/**
 * 费用报销审核的 <b>in-memory（默认）变体</b>。流程、断言、协议参数化全部继承自
 * {@link AbstractExpenseReviewAcceptanceTest}；本类只 override {@link #buildStack} 描述默认栈
 * （{@code expense-review-workflow} + {@code expense-review-main}→workflow，无中间件）。
 *
 * <p>运行：{@code ./mvnw -Dtest=ExpenseReviewAcceptanceTest test}（单场景加 {@code #方法名}）。
 *
 * @see ExpenseReviewRedisAcceptanceTest redis 中间件变体
 */
@Feature("FEAT-004: 任务驱动远程智能体调用")
@Stories({
        @Story("wf.agent-once: workflow agent远端链路"),
        @Story("wf.agent-twoturn: workflow agent远端链路多轮中断续接")
})
class ExpenseReviewAcceptanceTest extends AbstractExpenseReviewAcceptanceTest {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 同一 jar、双 profile，叶子优先。profile 由 YAML 单点声明（sut.agents.expense-review-main.profile: main），
        // buildStack 只管拓扑：SutStack 把 workflow 的随机端口 URL 作为
        // --agent-runtime.remote-agents[0].url 注入 main（命令行覆盖 yaml 的 localhost:8080 默认值）。
        return SutStack.builder(config)
                .agent(WORKFLOW_AGENT)
                .agent(ENTRY_AGENT, a -> a.downstream(WORKFLOW_AGENT));
    }
}
