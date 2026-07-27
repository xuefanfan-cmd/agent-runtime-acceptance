---
用例编号: FEAT-003-state-cache-persistence
测试标题: 状态缓存持久化机制——agent-runtime-java 的 Task 快照/workflow 状态复用同一数据源并在挂起/恢复边界持久化
story: S1
优先级: P1
自动化状态: READY（证据汇总视角——近端 ExpenseReview 8 场景 + 远端 balanceThenTransfers 已实现；业务场景需 LLM）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, redis, feat-003]
---

# FEAT-003-state-cache-persistence — 状态缓存持久化机制

> **机制一句话**：状态缓存持久化是 **agent-runtime-java 的存储机制**（FEAT-003 §2 SHOULD / §4.1）——
> A2A **Task 快照**与 **agent/workflow 状态**复用**同一 Redis 数据源**（双角色同库落盘，§2 SHOULD），
> 且在**挂起/恢复边界**（INPUT_REQUIRED → 续轮 → COMPLETED）状态经 Redis 持久化可恢复（§4.1）。
> 本机制由近端 ExpenseReview 8 协议参数化场景（双角色 + 场景 1 挂起恢复）与远端 balanceThenTransfers
> （显式键空间门禁）共同承载，属业务链路隐含证据汇总。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | Task 快照 + workflow 状态双角色同库复用（§2 SHOULD）+ 挂起/恢复边界状态持久化（§4.1） |
| **载体层 · agent-solution** | 机制触发载体 | 近端 workflow（`expense-review` 8 节点 DAG，四协议）· 远端 versatile（`edpa-plan-agent` checkpointer + `edpa-adapter` TaskStore，A2A_STREAM/REST_QUERY） |
| **测试数据层** | 载体 agent 的实现逻辑 | 超标报销（→INPUT_REQUIRED→approved）/ 合规报销 / 查余额+转账——驱动状态落盘与边界恢复的业务夹具 |

## 关联特性

- **FEAT-003（智能体任务状态缓存）**：§2 SHOULD Task/状态复用；§4.1 恢复边界。

## 关联架构约束 / FEAT-003 事实要求

- FEAT-003 §2 SHOULD：A2A Task 与 agent 状态复用同一 Redis 数据源。
- FEAT-003 §4.1：挂起/恢复边界状态缓存（Task 级恢复上下文边界在 workflow 的特有形态）。
- FEAT-003 §3：key schema 非外部稳定契约 → 键空间断言取弱形态（存在性 + taskId 特征）。

## 前置条件

1. 被测 jar 就绪 + Redis 激活（依赖 [middleware-activation](FEAT-003-middleware-activation.md) 机制）。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`（业务场景需 LLM）。
3. Docker（Testcontainers Redis）。

## 测试数据

- 近端场景：超标报销（机票5000+酒店2400+晚餐800 → INPUT_REQUIRED → `approved` → COMPLETED）/ 合规报销（机票3000+酒店1000+餐200 → COMPLETED）。
- 远端场景：`"先查下余额，再给李四和王五各转50元"`（stepUi 自推进 5 manual select）。
- 键空间弱断言常量：`*agent_state_blobs` / `*workflow_state_blobs` / `a2a:task:*`（远端显式门禁）。

## 持久化载体表（同一持久化机制，两类子断言 × 近/远端）

> 按「跑业务链路 + 断状态落盘/恢复」步骤一致、仅载体与子断言不同」原则汇总。

| # | 载体 | 子断言 | 落点方法 | 协议 | 状态 |
|---|---|---|---|---|---|
| ① | 近端 `expense-review` | 双角色复用（Task+workflow 状态同库） | [ExpenseReviewRedisAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRedisAcceptanceTest.java) 继承 8 参数化场景 | 四协议 | ✅ |
| ② | 近端 `expense-review` | 挂起/恢复边界持久化 | 同上 场景 1 参数化（超标→INPUT_REQUIRED→approved→COMPLETED） | 四协议 | ✅ |
| ③ | 远端 `edpa-plan-agent`+`edpa-adapter` | 双角色复用（显式键空间门禁） | [PlanAgentDirectStreamingRedisTest::balanceThenTransfersDirectRedis](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingRedisTest.java) | A2A_STREAM/REST_QUERY | ✅ |

## 测试步骤

> 双角色复用与挂起/恢复共享同一业务链路载体，断言维度互补；本用例为证据汇总视角。

| # | 动作 | 预期 |
|---|------|------|
| 1 | 近端：四协议跑超标报销 → INPUT_REQUIRED → 续 `approved` → COMPLETED | 终态/流式轨迹正确；Task 快照与 workflow 状态键同库出现（双角色） |
| 2 | 近端：四协议跑合规报销 → COMPLETED | 终态/结果正确；状态经 Redis 持久化 |
| 3 | 远端：A2A_STREAM/REST_QUERY 跑查余额+转账 stepUi 自推进 | 语义/不泄露断言（8200/李四/王五）；键空间门禁 `DBSIZE>0` + `*agent_state_blobs`/`*workflow_state_blobs` 非空 + `a2a:task:*` 非空 |

## 预期结果（机制断言）

### A — Task 快照与 workflow 状态复用同一 Redis 数据源（§2 SHOULD 双角色）
- **Given**：Redis 激活，业务链路跑通。
- **When**：核查 Redis 键空间。
- **Then**：Task 快照键（`a2a:task:*`）与 agent/workflow 状态键（`*agent_state_blobs` / `*workflow_state_blobs`）在同一 Redis 数据源出现（近端隐含 / 远端显式门禁）；按 taskId 区分。
- **PASS**：双角色同库落盘。**FAIL**：两角色分库 / 状态未落盘（双角色复用机制失效）。
- **弱形态**：key schema 非稳定契约，断言取「存在且与 taskId 相关」，不硬编码前缀。

### B — 挂起/恢复边界状态持久化（§4.1）
- **Given**：超标报销到 INPUT_REQUIRED（Task 快照 + workflow 状态脱水落盘）。
- **When**：续轮 `approved`（携 taskId+contextId）。
- **Then**：经 Redis 持久化恢复，续轮达 COMPLETED；终态/结果正确；不要求重启跨进程（本子断言为同进程续轮，跨 JVM 恢复见 [state-cache-semantics](FEAT-003-state-cache-semantics.md) 的隔离/恢复语义）。
- **PASS**：边界恢复正确。**FAIL**：续轮丢失上下文 / 终态错误（边界持久化机制失效）。

## 框架落点

| 项 | 值 |
|----|----|
| 证据来源 | 近端 `ExpenseReviewRedisAcceptanceTest`（继承 `AbstractExpenseReviewAcceptanceTest` final 模板）+ 远端 `PlanAgentDirectStreamingRedisTest` |
| 标签 | `@Tag("integration")`；Allure stories `wf.task-state-reuse` / `wf.input-required` |
| 基类 | `BaseManagedStackTest` + `SutStack`（redis profile + serviceBinding 注入）；近端 `AuthenticatedRedisFactory` |
| 客户端 | `InteractionFlow`（续轮携 taskId+contextId）；远端 `ConversationInteractionAdapter` |
| 键空间 | `RedisProbe` 弱形态（存在性 + taskId 特征）；cluster 下禁 `KEYS *`，用 scan 容忍 MOVED |
| 视角 | 证据汇总——双角色复用 + 挂起/恢复均业务链路隐含，本用例不新增类 |

## 运行方式

```bash
# 业务场景需 LLM
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewRedisAcceptanceTest test                          # 近端 8 场景
./mvnw -Dtest.env=openjiuwen -Dtest=PlanAgentDirectStreamingRedisTest#balanceThenTransfersDirectRedis test  # 远端
```

## 覆盖追溯

| FEAT-003 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| §2 SHOULD Task/状态复用同一数据源 | A（载体①③） | ✅ |
| §4.1 挂起/恢复边界状态缓存 | B（载体②） | ✅ |

## 清理策略

- 栈由类级生命周期管理；Testcontainers Redis 随栈销毁； taskId 键随库销毁。

## 风险与备注

- **键空间断言稳定性**：沿用弱形态（存在性 + taskId 特征）；实现若调整 key 命名，放宽为「存在与该 taskId 相关的新增键」，不硬编码前缀（设计档风险 2）。
- **远端双角色分布**：adapter 侧 A2A TaskStore（无状态桥，无 checkpointer）、plan-agent 侧 checkpointer——复用同一 Redis 数据源；远端显式键空间门禁证明两角色同库。
- **与激活机制边界**：本用例断「状态正确落盘/恢复」；中间件是否起来归 [middleware-activation](FEAT-003-middleware-activation.md)。TTL 过期 / Redis 故障 / 任务隔离等独立状态语义归 [state-cache-semantics](FEAT-003-state-cache-semantics.md)。
- **跨 JVM 恢复**：本用例子断言 B 为同进程续轮；进程重启后快照可查的持久化语义属 [state-cache-semantics](FEAT-003-state-cache-semantics.md) 的扩展空间。
