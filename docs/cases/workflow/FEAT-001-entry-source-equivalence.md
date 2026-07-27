---
用例编号: FEAT-001-entry-source-equivalence
测试标题: 入口来源等价机制——agent-runtime-java 对普通 client / runtime-to-runtime / 网关转发定义同一执行语义
story: 横向
优先级: P1
自动化状态: READY（四拓扑证据分散在多测试类，按 FEAT-001 §4 落点表汇总）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, feat-001]
---

# FEAT-001-entry-source-equivalence — 入口来源等价机制

> **机制一句话**：入口来源等价是 **agent-runtime-java 的机制契约**（§5.1.0）——无论调用来自普通
> client、runtime-to-runtime（一个 agent 把另一个 agent 当远程 A2A 工具调用）、还是经网关转发，
> 机制定义**同一执行语义，不得漂移，无私有执行入口**。本用例以四拓扑事实证据汇总验证：近端 workflow
> 直连、main→workflow runtime-to-runtime、直连 plan-agent、网关下行（远端 versatile）四条来源同场景、
> 同 kickoff、同断言成立。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 来源无关的统一执行语义（§5.1.0）；所有来源走标准化入口，无私有执行入口 |
| **载体层 · agent-solution** | 机制触发载体（四拓扑） | 近端 workflow 直连 / main→workflow runtime-to-runtime / 直连 plan-agent / 网关下行远端 versatile |
| **测试数据层** | 载体 agent 的实现逻辑 | 各拓扑载体的业务场景（报销审核 / 差旅规划 / 转账后余额等），同 kickoff 同断言 |

## 关联特性

- **FEAT-001**：§5.1.0「不得为不同来源/入口定义互相漂移的执行语义」+ §4 用户旅程。

## 关联架构约束 / FEAT-001 事实要求

- FEAT-001 §5.1.0 / §4：入口来源等价（机制能力）。
- FEAT-001 §1 覆盖矩阵：`wf.entry-source-equivalence`（横向）。

## 前置条件

1. 被测 jar 就绪：`expense-review-workflow`、`expense-review-main`、`edpa-plan-agent`、`edpa-adapter`、`edpa-gateway`。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。
3. envexplorer 容器由 service-bindings 自动拉起（plan-agent / adapter 拓扑）。
4. 四拓扑分别由各自的测试类构栈，无跨用例运行依赖（本用例为证据汇总视角）。

## 测试数据

- 各拓扑载体的业务输入（同 kickoff 语义）：报销审核场景（近端 workflow 直连 + main-routed）、差旅规划/转账后余额（plan-agent / gateway 拓扑）。
- 关键不是业务文本一致，而是「同一标准化入口机制」被四条来源共同使用。

## 来源拓扑表（同一执行语义，四类来源）

> 按「执行步骤+终态语义一致、仅调用来源拓扑不同」原则汇总；机制断言对四行同构成立。

| # | 来源拓扑 | 载体 / 入口 | 落点测试类 | 近/远端 |
|---|---|---|---|---|
| ① | 普通 client 直连 workflow | `expense-review-workflow` 直连（不经 main） | [ExpenseReviewWorkflowDirectAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewWorkflowDirectAcceptanceTest.java) | 近端 workflow |
| ② | runtime-to-runtime | `expense-review-main` 经远程 A2A 工具 `review_expense` 调 workflow | [AbstractExpenseReviewAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/AbstractExpenseReviewAcceptanceTest.java) 三叶子 | 近端 workflow（被 main 远程调） |
| ③ | 普通 client 直连 plan-agent | `ConversationInteractionAdapter` 直连 plan-agent 栈 | [PlanAgentDirectStreamingTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingTest.java) | 近端 plan-agent |
| ④ | 网关转发 | `edpa-gateway` 下行 a2a/rest 双模式转 plan-agent | [TransferAfterBalanceAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/TransferAfterBalanceAcceptanceTest.java) | 远端（经 gateway） |

## 测试步骤

> 本用例为证据汇总视角，不新增可执行流程；四拓扑各自的步骤见上表落点类。

| # | 动作 | 预期 |
|---|------|------|
| 1 | 拓扑①：普通 client 直连近端 workflow，跑业务场景至终态 | 同一标准化入口服务 |
| 2 | 拓扑②：main 把 workflow 当远程 A2A 工具调，跑同语义场景 | 同一标准化入口服务（runtime-to-runtime 复用同一入口） |
| 3 | 拓扑③：普通 client 直连 plan-agent | 同一标准化入口服务 |
| 4 | 拓扑④：网关下行转发（`-DGATEWAY_PROTOCOL=a2a\|rest`） | 转发复用同一入口，非私有执行入口 |
| 5 | 汇总四拓扑断言 | 四来源同语义、无漂移、无私有入口 |

## 预期结果（机制断言）

### A — 四来源同执行语义（无漂移）
- **Given**：四拓扑各自就绪。
- **When**：各拓扑跑载体业务场景至终态。
- **Then**：四拓扑均经标准化入口（`/a2a` 或 `/v1/query`）达成终态，终态语义一致（COMPLETED / 中断恢复同型）。
- **PASS**：四来源同语义。**FAIL**：某来源走私有入口 / 终态语义与他来源漂移。

### B — 无私有执行入口
- **Given**：四拓扑调用链。
- **When**：核查各来源的入口端点。
- **Then**：所有来源均走 `agent-runtime-java` 标准化入口（`/a2a` / `/v1/query`），无 agent-bus 等私有入口（§5.2 明示 OUT）。
- **PASS**：无私有入口。**FAIL**：某来源依赖私有执行入口。

## 框架落点

| 项 | 值 |
|----|----|
| 证据来源 | 四拓扑测试类（见来源拓扑表），分散在 `cases/integration/workflow_call` |
| 标签 | `@Tag("integration")`；Allure `@Story("wf.workflow-agent/wf.entry-source-equivalence")` |
| 视角 | 本用例不新增类——四协议参数化矩阵（[message-ingress](FEAT-001-message-ingress.md)）+ 四拓扑证据汇总共同构成来源等价验证 |
| 备注 | edpa-gateway 转发本身属 FEAT-011/012 范畴，本用例仅借作「来源等价」证据 |

## 运行方式

```bash
# 四拓扑各自运行（需 LLM）
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewWorkflowDirectAcceptanceTest test          # ① 近端直连
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewAcceptanceTest test                         # ② runtime-to-runtime
./mvnw -Dtest.env=openjiuwen -Dtest=PlanAgentDirectStreamingTest test                        # ③ 直连 plan-agent
./mvnw -Dtest.env=openjiuwen -Dtest=TransferAfterBalanceAcceptanceTest test                  # ④ 网关 a2a
./mvnw -Dtest.env=openjiuwen -DGATEWAY_PROTOCOL=rest -Dtest=TransferAfterBalanceAcceptanceTest test  # ④ 网关 rest
```

## 覆盖追溯

| FEAT-001 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| `wf.entry-source-equivalence`（来源等价 / 无私有入口） | A/B | ✅（四拓扑证据） |

## 清理策略

- 各拓扑栈由各自测试类类级生命周期管理。

## 风险与备注

- **证据分散**：四拓扑落点分散在多个 feature 注册（FEAT-001/002/003/004），报告树中来源等价证据跨 feature；覆盖对照以本用例 + [FEAT-001 设计文档 §1.2 看板](../FEAT-001-standardized-agent-service-entrypoint-workflow.md)为准。
- **近端 vs 远端载体**：拓扑①②③为近端 agent（workflow / plan-agent 直连或被近端 main 远程调），拓扑④经 gateway 属远端转发——四者共用 `agent-runtime-java` 同一标准化入口机制，是本用例的核心断言。
- **网关转发边界**：转发语义本身的验收归 FEAT-011/012 档；本用例只断「转发复用标准化入口」这一来源等价事实。
