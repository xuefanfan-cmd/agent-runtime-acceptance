---
用例编号: FEAT-002-framework-ingress-compat
测试标题: 异构框架入口适配机制——agent-runtime-java 经标准化入口兼容驱动 openJiuwen workflow/deepagent/ReAct 智能体
story: S1 + S2
优先级: P1
自动化状态: READY（证据汇总视角——复用 workflow_call 跨特性用例，不新建类）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, versatile, feat-002]
---

# FEAT-002-framework-ingress-compat — 异构框架入口适配机制

> **机制一句话**：异构框架入口适配是 **agent-runtime-java 的兼容机制**（FEAT-002 story 1/2）——同一套
> 标准化入口（A2A 流式/阻塞、REST 流式/同步）能正确驱动 **不同框架实现**的 openJiuwen 智能体
> （workflow 8 节点 DAG / deepagent / ReAct），各框架智能体经入口到正确终态、产出框架特有的业务结果。
> 本机制与 [FEAT-001 message-ingress](FEAT-001-message-ingress.md) 正交：后者断「同一 agent 四协议
> 入口等价」（协议维度），本机制断「同一入口兼容异构框架」（框架维度）。入口串通复用 FEAT-001 入口承载面，
> 本用例为证据汇总，不新建类。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 标准化入口对异构框架智能体的兼容驱动——不随被驱动智能体的框架实现变化 |
| **载体层 · agent-solution（异构框架）** | 机制触发载体 | workflow（`expense-review`，近端 8 节点 DAG）/ deepagent（`plan-agent`）/ ReAct（经 `edpa-gateway`） |
| **测试数据层** | 各载体 agent 的实现逻辑 | 报销审核 / deep-research / 查余额+转账——各框架载体自身的业务夹具 |

## 关联特性

- **FEAT-002（异构智能体框架兼容适配）**：story 1「兼容 openJiuwen workflow/deepagent 智能体」+ story 2「兼容 openJiuwen ReAct 智能体」。

## 关联架构约束 / FEAT-002 事实要求

- FEAT-002 §2.1 / §2.2：经标准化入口调用 openJiuwen workflow/deepagent/ReAct 智能体并返回正确结果（机制能力）。
- FEAT-002 §6.1 分工：入口协议等价性归 FEAT-001；本用例只断「框架兼容性」维度。

## 前置条件

1. 被测 jar 就绪：`expense-review-workflow`、`edpa-plan-agent`、`edpa-adapter`、`edpa-gateway`；envexplorer 容器由 service-bindings 自动拉起。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。
3. 三类框架载体的栈分别由各自测试类构栈，无跨用例运行依赖（本用例为证据汇总视角）。

## 测试数据

- 各框架载体的业务输入（各自实现逻辑）：超标/合规报销（workflow 载体）、deep-research 提问（deepagent 载体）、`"先查下余额，再给李四和王五各转50元"`（ReAct 载体）。
- 关键不是业务文本一致，而是「同一标准化入口机制」被三类异构框架智能体共同使用并产出各自正确结果。

## 框架载体表（同一入口兼容机制，三类异构框架）

> 按「入口驱动步骤+终态语义一致、仅被驱动框架不同」原则汇总；机制断言对三行同构成立。

| # | 框架载体 | 智能体类型 | 入口协议 | 落点测试类 | Allure 注册 | 近/远端 | 状态 |
|---|---|---|---|---|---|---|---|
| ① | `expense-review-workflow` | openJiuwen workflow（8 节点 DAG） | 四协议 | [ExpenseReviewAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewAcceptanceTest.java) | FEAT-004 | 近端 | ✅ |
| ② | `edpa-plan-agent` | deepagent | A2A_STREAM / REST_QUERY | [PlanAgentDirectStreamingTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingTest.java) | FEAT-004 | 近端 | ✅ |
| ③ | `edpa-plan-agent`（ReAct）经 `edpa-gateway` | openJiuwen ReAct | gateway a2a / rest | [TransferAfterBalanceAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/TransferAfterBalanceAcceptanceTest.java) | FEAT-002 | 远端（经 gateway） | ✅ |

## 测试步骤

> 本用例为证据汇总视角，不新增可执行流程；三类框架载体各自步骤见上表落点类。

| # | 动作 | 预期 |
|---|------|------|
| 1 | 框架①：经四协议入口驱动 workflow DAG 至终态 | workflow 经标准化入口可达终态，产出 `workflow_final` 结果 |
| 2 | 框架②：经 A2A/REST 入口驱动 deepagent 至终态 | deepagent 经标准化入口可达终态，产出流式 artifact |
| 3 | 框架③：经 gateway 入口（a2a/rest 双模式）驱动 ReAct 至终态 | ReAct 经标准化入口可达终态，产出余额/转账结果 |
| 4 | 汇总三类框架断言 | 异构框架经同一入口机制兼容驱动，无框架被入口拒绝 |

## 预期结果（机制断言）

### A — 异构框架经标准化入口达正确终态（框架兼容性核心）
- **Given**：三类框架载体各自就绪。
- **When**：各自经标准化入口（A2A/REST/gateway）驱动至终态。
- **Then**：三类框架智能体均经同一入口机制达成正确终态（COMPLETED / 中断恢复），不被入口因框架差异拒绝。
- **PASS**：三类框架兼容。**FAIL**：某框架被入口拒绝 / 终态错误（该框架与入口机制不兼容）。

### B — 框架特有业务产出正确
- **Given**：A 通过。
- **When**：核查各框架载体的业务产出。
- **Then**：workflow 产出 `workflow_final` 审核/审批结果；deepagent 产出流式 artifact；ReAct 产出余额 8200 / 收款人李四·王五。
- **PASS**：各框架产出符合自身业务语义。**FAIL**：框架产出缺失/错误。

### C — 无堆栈泄露（入口输出卫生）
- **Given**：三类框架任一响应/wire 日志。
- **When**：扫描泄露标志串（`java.io.IOException` / `Caused by:` / `Exception in thread` / `at java.base/` / `at org.springframework.` / `at reactor.`）。
- **Then**：均不命中。
- **PASS**：无泄露。**FAIL**：任一命中（框架异常泄漏到外部可观测面）。

## 框架落点

| 项 | 值 |
|----|----|
| 证据来源 | 三类框架载体测试类（见框架载体表），落 `cases/integration/workflow_call` |
| 标签 | `@Tag("integration")`；Allure story 分散（`wf.verpkt-gateway-rest/a2a` 等） |
| 视角 | 本用例不新增类——与 [FEAT-001 message-ingress](FEAT-001-message-ingress.md)（协议维度）正交，共同构成入口 × 框架兼容矩阵 |

## 运行方式

```bash
# 三类框架载体各自运行（需 LLM）
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewAcceptanceTest test            # ① workflow
./mvnw -Dtest.env=openjiuwen -Dtest=PlanAgentDirectStreamingTest test            # ② deepagent
./mvnw -Dtest.env=openjiuwen -Dtest=TransferAfterBalanceAcceptanceTest test      # ③ ReAct (gateway a2a)
./mvnw -Dtest.env=openjiuwen -DGATEWAY_PROTOCOL=rest -Dtest=TransferAfterBalanceAcceptanceTest test  # ③ gateway rest
```

## 覆盖追溯

| FEAT-002 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| story 1：兼容 workflow 经 A2A/REST 调用 | A/B/C（载体①） | ✅（workflow_call） |
| story 1：兼容 deepagent 经 A2A/REST 调用 | A/B/C（载体②） | ✅（workflow_call） |
| story 2：兼容 ReAct 经 REST/gateway 调用 | A/B/C（载体③） | ✅（workflow_call） |

## 清理策略

- 各框架载体栈由各自测试类类级生命周期管理。

## 风险与备注

- **证据分散 / Allure 跨 feature**：载体①②注册在 FEAT-004，载体③注册在 FEAT-002；报告树中框架兼容证据跨 feature，覆盖对照以本用例 + [FEAT-002 设计文档 §4 看板](../FEAT-002-heterogeneous-agent-framework-compatibility-workflow.md)为准。
- **与 FEAT-001 边界**：入口协议等价性（四协议）归 [FEAT-001 message-ingress](FEAT-001-message-ingress.md)；本用例只断「框架兼容」维度，不重复协议断言。
- **远端 versatile 载体**：载体③经 gateway 属远端编排；远程 Versatile 控制器的故障语义是独立机制，见 [versatile-remote-reliability](FEAT-002-versatile-remote-reliability.md)。
