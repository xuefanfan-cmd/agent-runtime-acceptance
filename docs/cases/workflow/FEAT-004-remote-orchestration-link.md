---
用例编号: FEAT-004-remote-orchestration-link
测试标题: 远程编排链路串通机制——agent-runtime-java 把远程端点装配为工具并经 A2A 调用投射进度、回灌结果
story: S1
优先级: P0
自动化状态: READY（证据汇总视角——本地 main→workflow + 远端 plan-agent→versatile 两条编排链路已实现；业务场景需 LLM）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, feat-004, remote-orchestration]
---

# FEAT-004-remote-orchestration-link — 远程编排链路串通机制

> **机制一句话**：远程编排链路串通是 **agent-runtime-java 的编排机制**（FEAT-004 §2.1 story 1）——父
> agent 经 YAML `remote-agents` 把**远程端点装配为可调用工具**（Agent Card 拉取 → `RemoteAgentToolSpec`
> 生成 → Tool 安装），运行时**远程 A2A `SendStreamingMessage` 独立 streaming 调用**，调用**进度投射父事件流**
> （`SUBMITTED → WORKING → …`），远程 `COMPLETED` 后**结果回灌**本地 resume。本机制由两条编排链路承载：
> 本地 workflow 编排（main→workflow）与远端 Versatile 编排（plan-agent→adapter→versatile）。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 远程端点装配为工具（Card 拉取/ToolSpec 生成/安装）+ 远程 A2A streaming 调用 + 进度投射父事件流 + 结果回灌 |
| **载体层 · agent-solution** | 机制触发载体 | 本地编排（`expense-review-main` ReAct 主控 → `expense-review-workflow` 8 节点 DAG）· 远端编排（`edpa-plan-agent` → `edpa-adapter` → envexplorer versatile） |
| **测试数据层** | 载体 agent 的实现逻辑 | 合规报销（直达 COMPLETED）/ 查余额+转账（stepUi）——驱动远程调用与结果回灌的业务夹具 |

## 关联特性

- **FEAT-004（任务驱动远程智能体调用）**：story 1「A2A 远端智能体调用」。

## 关联架构约束 / FEAT-004 事实要求

- FEAT-004 §2.1：YAML 配置远程端点 / Agent Card 启动拉取 / `RemoteAgentToolSpec` 生成与 Tool 安装；远程 A2A 调用（`SendStreamingMessage` 独立 streaming）；结果回灌（远程 `COMPLETED` → 本地 resume）。
- FEAT-004 §6：父 Task 进度投射。

## 前置条件

1. 被测 jar 就绪：`expense-review-workflow`/`expense-review-main`、`edpa-plan-agent`/`edpa-adapter`；envexplorer 由 service-bindings 自动拉起。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。

## 测试数据

- 本地编排：合规报销（机票3000+酒店1000+餐200 → workflow 直达 COMPLETED，`workflow_final` 审核报告）。
- 远端编排：`"先查下余额，再给李四和王五各转50元"`（stepUi 自推进，转账完成态）。
- 装配证据：LLM 成功调用远程工具 `review_expense`（本地）/ 经 adapter 调用 versatile workflow（远端）。

## 编排载体表（同一编排机制，两条远程链路）

> 按「装配→调用→投射→回灌」步骤一致、仅远程链路载体不同」原则汇总；机制断言对两行同构成立。

| # | 载体 | 远程链路 | 落点方法 | 协议 | Allure story | 状态 |
|---|---|---|---|---|---|---|
| ① | 本地 workflow | main → workflow（A2A 工具调用） | [ExpenseReviewAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewAcceptanceTest.java) 场景 2（合规直达） | 四协议 | `wf.agent-once` | ✅ |
| ② | 远端 Versatile | plan-agent → adapter → versatile | [PlanAgentDirectStreamingTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingTest.java) | A2A_STREAM/REST_QUERY | `wf.verstaile-once` | ✅ |

## 测试步骤

> 两条编排链路共享同一「装配→调用→投射→回灌」断言流程；本用例为证据汇总视角，不新增类。

| # | 动作 | 预期 |
|---|------|------|
| 1 | 父 agent 启动：经 YAML `remote-agents[0]` 指向远程端点，拉取 Agent Card，生成 `RemoteAgentToolSpec` 并安装为 Tool | 远程端点装配为可调用工具（业务证据 = LLM 调用远程工具 `review_expense` / versatile workflow） |
| 2 | 客户端提交业务消息，父 agent 经 A2A `SendStreamingMessage` 远程调用 | 远程调用经独立 streaming；进度投射父事件流（`SUBMITTED → WORKING → …`） |
| 3 | 远程达 `COMPLETED`，结果回灌本地 resume | `workflow_final` 审核报告 / 转账结果经父汇总返回客户端 |

## 预期结果（机制断言）

### A — 远程端点装配为可调用工具（YAML → Card → ToolSpec → Tool 安装）
- **Given**：父 agent 经 YAML `remote-agents[0]` 指向远程端点。
- **When**：启动拉取 Agent Card，生成 `RemoteAgentToolSpec` 并安装。
- **Then**：远程端点装配为可调用工具；业务证据 = LLM 成功调用远程工具完成业务。
- **PASS**：装配成立（工具可被调用）。**FAIL**：装配失败 / 工具不可达（装配机制失效）。

### B — 远程 A2A 调用 + 进度投射父事件流
- **Given**：A 通过。
- **When**：父 agent 经 `SendStreamingMessage` 远程调用（独立 streaming）。
- **Then**：客户端可见流式状态轨迹 `SUBMITTED → WORKING → …`（进度投射父事件流）。
- **PASS**：进度投射可见。**FAIL**：进度不投射 / 流式轨迹缺失（投射机制失效）。

### C — 结果回灌（远程 COMPLETED → 本地 resume）
- **Given**：B 通过。
- **When**：远程达 `COMPLETED`。
- **Then**：结果回灌本地 resume；`workflow_final` / 业务结果经父汇总返回客户端。
- **PASS**：结果回灌正确。**FAIL**：结果丢失 / 父未 resume（回灌机制失效）。

## 框架落点

| 项 | 值 |
|----|----|
| 证据来源 | 本地 `ExpenseReviewAcceptanceTest`（继承 `AbstractExpenseReviewAcceptanceTest`）+ 远端 `PlanAgentDirectStreamingTest`（继承 `AbstractBalanceThenTransfersTest`），落 `cases/integration/workflow_call` |
| 标签 | `@Tag("integration")`；Allure `@Feature("FEAT-004: 任务驱动远程智能体调用")` + stories `wf.agent-once` / `wf.verstaile-once` |
| 基类 | `BaseManagedStackTest` + `SutStack`；远端 `ConversationInteractionAdapter` |
| 客户端 | `InteractionFlow`（四协议 / A2A_STREAM/REST_QUERY 参数化） |
| 视角 | 证据汇总——编排正向链路（装配/调用/投射/回灌）横切两条远程链路，本用例不新增类 |

## 运行方式

```bash
# 业务场景需 LLM
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewAcceptanceTest test                  # 本地编排
./mvnw -Dtest.env=openjiuwen -Dtest=PlanAgentDirectStreamingTest test                 # 远端编排
```

## 覆盖追溯

| FEAT-004 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| §2.1 YAML/Card/ToolSpec/Tool 装配 | A（载体①②） | ✅ |
| §2.1 远程 A2A SendStreamingMessage + 进度投射 | B（载体①②） | ✅ |
| §2.1 结果回灌（远程 COMPLETED → 本地 resume） | C（载体①②） | ✅ |

## 清理策略

- 各编排链路栈由各自测试类类级生命周期管理；envexplorer 容器随栈销毁。

## 风险与备注

- **与 FEAT-002 边界（同载体不同机制）**：载体①②也出现在 [FEAT-002 framework-ingress-compat](FEAT-002-framework-ingress-compat.md)，但那里断「异构框架入口兼容」（框架维度），本用例断「远程编排链路串通」（编排维度）——同载体、不同机制断言焦点。
- **与中断-续接边界**：本用例断正向链路（直达 COMPLETED 的装配/调用/投射/回灌）；远程 `INPUT_REQUIRED` 跨 agent 传播归 [remote-interrupt-resume](FEAT-004-remote-interrupt-resume.md)。
- **装配证据的隐含性**：装配无独立断言方法，业务证据 = LLM 成功调用远程工具（工具可达即装配成立）；与 FEAT-001 [agent-card](FEAT-001-agent-card.md)（Card 发现的入口层断言）正交。
- **story 与入口耦合**：story 1 复用 FEAT-001 入口，若入口行为变更，本档 workflow_call 用例需同步回归（设计档风险 5）。
