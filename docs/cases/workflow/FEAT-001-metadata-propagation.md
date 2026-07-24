---
用例编号: FEAT-001-metadata-propagation
测试标题: 元数据传播机制——agent-runtime-java 按入口注入并隔离 userId/agentId/sessionId
story: 横向
优先级: P1
自动化状态: READY（隐式覆盖——所有场景 TC 经 `InteractionFlow.withMetadata` 注入）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, feat-001]
---

# FEAT-001-metadata-propagation — 元数据传播机制

> **机制一句话**：元数据传播是 **agent-runtime-java 的横切机制**（§5.1.7）——入口侧注入的
> `userId` / `agentId` / `sessionId` 等元数据随消息进入执行上下文，并按调用维度隔离，不得跨调用串扰。
> 本机制由所有场景 TC 经 `InteractionFlow.withMetadata(...)` 隐式承载（全协议注入 + 按
> `<scenario>-<protocol>` 隔离 session），本用例汇总该横切断言；显式派生字段（如 tenant 路由）不列入。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 入口元数据注入 + 调用维度隔离（§5.1.7） |
| **载体层 · agent-solution** | 机制触发载体 | 所有入口（近端 workflow / 远端 versatile，四协议同型） |
| **测试数据层** | 载体 agent 的实现逻辑 | 各场景 TC 的 `userId/agentId/sessionId` 取值（按 `<scenario>-<protocol>` 命名） |

## 关联特性

- **FEAT-001**：§5.1.7「输入与元数据语义」。

## 关联架构约束 / FEAT-001 事实要求

- FEAT-001 §5.1.7：元数据传播与隔离（机制能力）。
- FEAT-001 §1 覆盖矩阵：`wf.metadata-propagation`（横向，隐式覆盖）。

## 前置条件

1. 任一场景 TC 的栈就绪（本机制横切，无独立栈需求）。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。
3. 依赖 [message-ingress](FEAT-001-message-ingress.md) / [input-required](FEAT-001-input-required.md) 等场景 TC 的入口可达性。

## 测试数据

- 元数据取值约定（载体侧命名）：
  - `userId`：如 `direct-user` / `manual-user`（区分直连 vs main-routed 来源）。
  - `agentId`：入口 agent 名（`expense-review-workflow` / `expense-review-main`）。
  - `sessionId`：`<scenario>-<protocol>`（如 `expense-direct-scenario2-A2A_STREAM`），保证四协议 × 两场景八次调用互不撞 session。

## 协议维度表（全入口注入）

| 协议 | 元数据注入路径 | 隔离维度 |
|---|---|---|
| `A2A_STREAM` / `A2A_SYNC` | `InteractionFlow.withMetadata` → A2A metadata | `sessionId=<scenario>-<protocol>` |
| `REST_QUERY` / `REST_QUERY_SYNC` | 同上 → REST metadata | 同上 |

## 测试步骤

> 本机制横切，无独立可执行流程；由各场景 TC 的 `.withMetadata(...)` 隐式承载。

| # | 动作 | 预期 |
|---|------|------|
| 1 | 任一场景 TC：`.withMetadata(Map.of("userId", ..., "agentId", ..., "sessionId", <scenario>-<protocol>))` | 元数据随入口注入 |
| 2 | 同一 TC 四协议参数化各跑一次 | 四次调用 sessionId 互异（按协议隔离） |
| 3 | 不同场景 TC（scenario1/2）各跑 | 不同场景 sessionId 互异（按场景隔离） |
| 4 | 核查执行上下文不串扰 | 各调用独立，无跨调用记忆污染 |

## 预期结果（机制断言）

### A — 元数据全协议注入
- **Given**：场景 TC 栈就绪。
- **When**：`.withMetadata(userId/agentId/sessionId)` 后发送消息（四协议）。
- **Then**：四协议均接受注入并随消息进入执行上下文（调用不因元数据缺失/格式被拒）。
- **PASS**：四协议注入均生效（各场景 TC 正常达终态即隐式证明）。**FAIL**：某协议丢弃/拒绝元数据。

### B — 按 `<scenario>-<protocol>` 隔离不串扰
- **Given**：多场景 × 多协议并发/顺序执行。
- **When**：各调用用不同 `sessionId`。
- **Then**：各调用上下文独立，无跨调用记忆污染（A2A 续轮的 taskId+contextId 续传仅限同 sessionId 内）。
- **PASS**：无串扰。**FAIL**：跨调用泄漏上下文（机制隔离缺陷）。

## 框架落点

| 项 | 值 |
|----|----|
| 承载方式 | 横切——所有场景 TC 的 `InteractionFlow.withMetadata(...)`（[message-ingress](FEAT-001-message-ingress.md) / [input-required](FEAT-001-input-required.md) 等） |
| 标签 | 随各场景 TC；Allure story 归属各场景 |
| 备注 | 显式派生字段断言（如 tenant 路由、配额）不列入——属下游路由机制，非本入口元数据传播机制 |

## 运行方式

```bash
# 本机制无独立类；随任一场景 TC 运行即隐式覆盖
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewWorkflowDirectAcceptanceTest test
```

## 覆盖追溯

| FEAT-001 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| `wf.metadata-propagation`（元数据注入与隔离） | A/B（隐式） | ✅ |

## 清理策略

- 随各场景 TC 的栈生命周期；无额外状态。

## 风险与备注

- **隐式覆盖**：本机制不建独立断言类，由所有场景 TC 的 `.withMetadata(...)` 隐式承载——这是有意设计（元数据传播是横切关注点，孤立断言价值低于在真实业务流中验证）。
- **续轮续传**：`InteractionFlow` 续轮自动携原 taskId+contextId 续传，属同 sessionId 内的合法恢复，不计为串扰。
