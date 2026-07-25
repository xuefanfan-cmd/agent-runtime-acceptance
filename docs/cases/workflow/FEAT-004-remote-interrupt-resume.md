---
用例编号: FEAT-004-remote-interrupt-resume
测试标题: 远程中断-续接跨 agent 传播机制——agent-runtime-java 把远程 INPUT_REQUIRED 透传至父 Task 挂起并续写恢复
story: S1
优先级: P0
自动化状态: READY（证据汇总视角——本地场景 1 + 远端 stepUi 多轮续接已实现；业务场景需 LLM）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, feat-004, remote-orchestration, hitl]
---

# FEAT-004-remote-interrupt-resume — 远程中断-续接跨 agent 传播机制

> **机制一句话**：远程中断-续接跨 agent 传播是 **agent-runtime-java 的编排生命周期机制**（FEAT-004
> §2.1 story 1）——远程 agent 触发 `INPUT_REQUIRED`（workflow Questioner / versatile stepUi）时，信号
> **跨 agent 透传至父 Task**（父挂起），客户端可见审批/选择提示；用户输入后**续写恢复**（携 taskId 续轮），
> 远程续行至 `COMPLETED`，结果回灌父 Task。本机制是 [remote-orchestration-link](FEAT-004-remote-orchestration-link.md)
> 正向链路的中断-续接生命周期形态。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 远程 `INPUT_REQUIRED` 跨 agent 透传至父挂起 + 续写恢复 + 远程 COMPLETED 回灌父 Task |
| **载体层 · agent-solution** | 机制触发载体 | 本地编排（`expense-review-main` → `expense-review-workflow` Questioner 节点）· 远端编排（`edpa-plan-agent` → `edpa-adapter` → versatile stepUi） |
| **测试数据层** | 载体 agent 的实现逻辑 | 超标报销（→INPUT_REQUIRED→`approved`）/ 查余额+转账（5 manual select）——驱动中断-续接的业务夹具 |

## 关联特性

- **FEAT-004（任务驱动远程智能体调用）**：story 1「任务全生命周期中断与接续」。

## 关联架构约束 / FEAT-004 事实要求

- FEAT-004 §2.1：中断-续接（远程 `INPUT_REQUIRED` → 父挂起 → 用户输入 → 续写）；父 Task 进度投射（中断形态）；结果回灌。
- FEAT-004 §6：父 Task 中断形态的进度投射。

## 前置条件

1. 被测 jar 就绪（同 [remote-orchestration-link](FEAT-004-remote-orchestration-link.md)）。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。
3. 多轮 `INPUT_REQUIRED` 唯一可靠呈现的协议为 **A2A_STREAM**（继承既有结论；本地链路四协议参数化，远端 A2A_STREAM/REST_QUERY）。

## 测试数据

- 本地编排：超标报销（机票5000+酒店2400+晚餐800 → workflow Questioner `INPUT_REQUIRED` 透传 → 续 `approved` → COMPLETED）。
- 远端编排：`"先查下余额，再给李四和王五各转50元"`（stepUi 自推进 5 manual select：李四 3 + 王五 2）。

## 中断-续接载体表（同一传播机制，两条远程链路）

> 按「远程 INPUT_REQUIRED→父挂起→续写→回灌」步骤一致、仅远程链路载体不同」原则汇总。

| # | 载体 | 中断源 | 落点方法 | 协议 | Allure story | 状态 |
|---|---|---|---|---|---|---|
| ① | 本地 workflow | workflow Questioner `INPUT_REQUIRED` | [ExpenseReviewAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewAcceptanceTest.java) 场景 1（超标→续 approved） | 四协议 | `wf.agent-twoturn` | ✅ |
| ② | 远端 Versatile | versatile stepUi manual select | [PlanAgentDirectStreamingTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingTest.java) | A2A_STREAM/REST_QUERY | `wf.verstaile-input-required` | ✅ |

## 测试步骤

> 两条编排链路共享同一中断-续接传播断言流程；本用例为证据汇总视角，不新增类。

| # | 动作 | 预期 |
|---|------|------|
| 1 | 客户端提交触发中断的业务消息（超标报销 / 查余额+转账） | 远程 agent 触发 `INPUT_REQUIRED`（Questioner / stepUi） |
| 2 | 远程 `INPUT_REQUIRED` 跨 agent 透传至父 Task | 父 Task 挂起；客户端可见审批/选择提示（中断形态进度投射） |
| 3 | 用户输入（`approved` / manual select）续写恢复（携 taskId 续轮） | 父 Task resume；远程续行 |
| 4 | 远程达 `COMPLETED`，结果回灌父 Task | 父 Task `COMPLETED`；`workflow_final` / 转账结果经父汇总返回 |

## 预期结果（机制断言）

### A — 远程 INPUT_REQUIRED 跨 agent 透传至父 Task 挂起
- **Given**：远程 agent 触发 `INPUT_REQUIRED`（Questioner / stepUi）。
- **When**：信号跨 agent 传播。
- **Then**：父 Task 挂起；客户端可见审批/选择提示（中断形态的进度投射）。
- **PASS**：中断透传至父。**FAIL**：中断被吞 / 父不挂起（跨 agent 传播机制失效）。

### B — 续写恢复（用户输入 → 续轮 → 父 resume）
- **Given**：A 通过，父 Task 挂起。
- **When**：用户输入（`approved` / manual select）续写（携 taskId 续轮）。
- **Then**：父 Task resume，远程续行。
- **PASS**：续写恢复正确。**FAIL**：续轮丢失上下文 / 父不 resume（恢复机制失效）。

### C — 远程 COMPLETED 回灌父 Task
- **Given**：B 通过。
- **When**：远程续行至 `COMPLETED`。
- **Then**：父 Task `COMPLETED`；`workflow_final` / 业务结果经父汇总返回客户端。
- **PASS**：回灌父 Task 正确。**FAIL**：父未达 COMPLETED / 结果丢失（回灌机制失效）。

## 框架落点

| 项 | 值 |
|----|----|
| 证据来源 | 本地 `ExpenseReviewAcceptanceTest` 场景 1 + 远端 `PlanAgentDirectStreamingTest` |
| 标签 | `@Tag("integration")`；Allure stories `wf.agent-twoturn` / `wf.verstaile-input-required` |
| 基类 | `BaseManagedStackTest` + `SutStack`；远端 `ConversationInteractionAdapter` |
| 客户端 | `InteractionFlow`（续轮携 taskId+contextId；A2A_STREAM 为多轮 INPUT_REQUIRED 可靠呈现协议） |
| 视角 | 证据汇总——中断-续接跨 agent 传播横切两条远程链路，本用例不新增类 |

## 运行方式

```bash
# 业务场景需 LLM
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewAcceptanceTest test                  # 本地场景 1
./mvnw -Dtest.env=openjiuwen -Dtest=PlanAgentDirectStreamingTest test                 # 远端 stepUi
```

## 覆盖追溯

| FEAT-004 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| §2.1 中断-续接（远程 INPUT_REQUIRED → 父挂起） | A（载体①②） | ✅ |
| §2.1 续写恢复（用户输入 → 续写） | B（载体①②） | ✅ |
| §2.1 父 Task 进度投射（中断形态）+ 结果回灌 | C（载体①②） | ✅ |

## 清理策略

- 各编排链路栈由各自测试类类级生命周期管理。

## 风险与备注

- **与 FEAT-001 input-required 边界**：[FEAT-001 input-required](FEAT-001-input-required.md) 断**单 agent 直连**的 INPUT_REQUIRED 中断恢复（入口层）；本用例断**远程 agent 的 INPUT_REQUIRED 跨 agent 透传至父**（编排层）——同一中断语义在直连 vs 远程编排两个维度。
- **与正向链路边界**：本用例断中断-续接生命周期；直达 COMPLETED 的装配/调用/投射/回灌归 [remote-orchestration-link](FEAT-004-remote-orchestration-link.md)。
- **A2A_STREAM 协议选择**：多轮 `INPUT_REQUIRED` 在 A2A_STREAM 上唯一可靠呈现（继承既有结论）；本地链路四协议参数化覆盖，远端限 A2A_STREAM/REST_QUERY。
- **Redis 下的中断恢复**：中断-续接的状态持久化（Redis 快照）属 [FEAT-003 state-cache-persistence](FEAT-003-state-cache-persistence.md)（存储介质维度，与编排传播正交）；`PlanAgentDirectStreamingRedisTest` 同时归 FEAT-003。
