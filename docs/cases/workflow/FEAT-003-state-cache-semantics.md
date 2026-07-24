---
用例编号: FEAT-003-state-cache-semantics
测试标题: 状态缓存独立语义机制——agent-runtime-java 的 TTL 过期 / Redis 故障可诊断 / 任务级隔离
story: S1
优先级: P1
自动化状态: TOBUILD（`ExpenseReviewRedisStateCacheTest` 3 方法待新建；建议继承 `AbstractExpenseReviewAcceptanceTest` 复用业务模板）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, redis, feat-003]
---

# FEAT-003-state-cache-semantics — 状态缓存独立语义机制

> **机制一句话**：状态缓存独立语义是 **agent-runtime-java 的存储生命周期机制**（FEAT-003 §2 SHOULD /
> §4.1 / §5.2）——在 [middleware-activation](FEAT-003-middleware-activation.md)（起来）与
> [state-cache-persistence](FEAT-003-state-cache-persistence.md)（落盘/恢复）之上，验证三条独立语义：
> **TTL 过期**（快照按配置 TTL 收敛到 not-found）、**Redis 故障不静默降级**（恢复期 Redis 不可达 → 明确
> 失败/可诊断，不假 COMPLETED）、**任务级隔离**（并发任务状态按 taskId 隔离不串线）。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 状态缓存 TTL 过期（§2 SHOULD/§5.1.3）+ Redis 故障不静默降级（§5.2 反向）+ 任务级隔离（§4.1） |
| **载体层 · agent-solution** | 机制触发载体 | 近端 workflow（`expense-review` standalone，A2A_STREAM 为主可扩四协议） |
| **测试数据层** | 载体 agent 的实现逻辑 | 合规报销（TTL 用例）/ 超标报销→INPUT_REQUIRED（故障用例）/ 并行合规+超标（隔离用例）——驱动各语义的业务夹具 |

## 关联特性

- **FEAT-003（智能体任务状态缓存）**：§2 SHOULD TTL；§4.1 任务级隔离；§5.1.3 TTL 配置；§5.2 不降级不承诺（反向验收）。

## 关联架构约束 / FEAT-003 事实要求

- FEAT-003 §2 SHOULD / §5.1.3：状态缓存 TTL——短 TTL 配置下快照过期。
- FEAT-003 §5.2：不承诺运行时故障自动降级——以「明确失败/可诊断」反向验收，**不验证降级本身**。
- FEAT-003 §4.1：任务级隔离——共用 Redis 的等价单 agent 隔离语义。

## 前置条件

1. 被测 jar 就绪 + Redis 激活（依赖 [middleware-activation](FEAT-003-middleware-activation.md)）。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。
3. Docker（Testcontainers Redis，故障用例需 `SutStack.stop()` 杀容器）。

## 测试数据

- TTL 用例：合规报销 + 注入 `openjiuwen.service.middleware.checkpointer.ttl-seconds=<短值>`（如 12s）。
- 故障用例：超标报销 → 轮 1 INPUT_REQUIRED → 停 Testcontainers redis → 续 approved。
- 隔离用例：并行 A 合规 + B 超标（B 后续 approved）。

## 语义参数表（同一载体，三类独立语义）

| 方法 | 语义 | 注入/动作 | 预期 |
|---|---|---|---|
| `taskSnapshotExpiresWithConfiguredTtl` | TTL 过期 | 短 TTL 配置 + 轮询 | 过期后 getTask not-found |
| `redisUnavailableAtResumeFailsDiagnosably` | 故障不降级 | 恢复期 `stop()` 杀 redis | FAILED/可诊断，不假 COMPLETED |
| `concurrentExpenseTasksStateIsolated` | 任务隔离 | 并行两笔报销 | 各自终态正确，键按 taskId 隔离 |

## 测试步骤

> 三类语义合并为一个测试类（`ExpenseReviewRedisStateCacheTest`）的三个 `@Test` 方法；建议继承 `AbstractExpenseReviewAcceptanceTest` 复用业务模板，方法级 override `buildStack` 或额外配置实现特殊注入。

| # | 动作 | 预期 |
|---|------|------|
| 1 | **TTL**：短 TTL 配置栈跑合规报销 → 立即 getTask 可读 → 轮询超过 TTL | 立即读取内容/状态一致；过期后 getTask not-found/空语义；TTL 在配置窗口内 |
| 2 | **故障**：超标报销轮 1 到 INPUT_REQUIRED → 停 Testcontainers redis → 续 approved | 任务收敛 FAILED/可诊断（有界不挂起）；**不**静默 COMPLETED；错误日志可定位 Redis 连接且脱敏 |
| 3 | **隔离**：并行提交 A 合规、B 超标（B 后续 approved） | 各自终态/结果正确；Redis 键按各自 taskId 区分互不覆盖；B 恢复不串入 A 内容 |

## 预期结果（机制断言）

### A — 状态缓存 TTL 过期（§2 SHOULD / §5.1.3）
- **Given**：短 TTL 配置（如 12s）。
- **When**：完成一笔合规报销 → 立即 getTask → 轮询超过 TTL。
- **Then**：立即读取内容/状态一致；过期后 getTask 为 not-found/空语义；过期判定在配置窗口内（不等待默认 7 天）。
- **PASS**：快照按 TTL 过期。**FAIL**：快照不过期 / 过期判定偏离配置（TTL 机制失效）。
- **断言尺度**：过期判定用 getTask not-found 语义而非固定 key 消失（设计档风险 5）。

### B — Redis 故障不静默降级（§5.2 反向验收）
- **Given**：超标报销轮 1 已到 INPUT_REQUIRED。
- **When**：停掉栈内 Testcontainers redis → 续 approved。
- **Then**：任务收敛到 FAILED/可诊断错误（有界，不无限挂起）；**不得**静默 COMPLETED；错误日志可定位 Redis 连接问题且脱敏。
- **PASS**：明确失败/可诊断。**FAIL**：静默 COMPLETED（假成功）/ 无限挂起（不降级机制反向违反）。

### C — 任务级隔离（§4.1）
- **Given**：并行两笔报销（A 合规、B 超标）。
- **When**：A 直达 COMPLETED；B 后续 approved 恢复。
- **Then**：各自终态与结果语义正确；Redis 键按各自 taskId 区分，互不覆盖；B 恢复不串入 A 内容。
- **PASS**：任务隔离成立。**FAIL**：键覆盖 / 恢复串线（隔离机制失效）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | ⬜ `ExpenseReviewRedisStateCacheTest`（新建，落 `cases/integration/workflow_call`，继承 `AbstractExpenseReviewAcceptanceTest`） |
| 标签 | `@Tag("integration")`；Allure `@Feature("FEAT-003")` + stories `wf.state-cache-ttl` / `wf.redis-failure-diagnosable` / `wf.task-isolation`（待注册） |
| 基类 | `BaseManagedStackTest` + `SutStack`（serviceBinding 注入 / `stop()` 杀容器 / ttl-seconds property） |
| 客户端 | `InteractionFlow`（续轮携 taskId+contextId）；`RedisProbe`（弱形态键空间/分 taskId 断言）；`ManagedSutInstance.logFile()` |

## 运行方式

```bash
# ⬜ 待新建；故障/隔离用例需 LLM，TTL 用例 getTask 轮询无需 LLM（但报销业务需）
mvn test -Dtest=ExpenseReviewRedisStateCacheTest -Dtest.env=openjiuwen \
  -DLLM_API_KEY=... -DLLM_BASE_URL=... -DLLM_MODEL_NAME=...
```

## 覆盖追溯

| FEAT-003 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| §2 SHOULD / §5.1.3 状态缓存 TTL | A | ⬜ 待新建 |
| §5.2 不降级（反向验收） | B | ⬜ 待新建 |
| §4.1 任务级隔离 | C | ⬜ 待新建 |

## 清理策略

- 栈由类级生命周期管理；故障用例被杀的 redis 容器由 `SutStack.stop()` 收尾；taskId 键随库销毁。

## 风险与备注

- **TTL 等待尺度**：短 TTL（如 12s）+ 轮询，不等待默认 7 天；过期判定用 getTask not-found 语义而非固定 key 消失（设计档风险 5）。
- **故障注入手段**：「恢复期 redis 不可达」经 `SutStack.stop()` 杀 Testcontainers 容器实现（业务合理可达点）；属 FEAT-003 §5.2「不验证降级本身，以明确失败反向验收」。
- **隔离用例的并发尺度**：并行两笔报销须确保 sessionId/taskId 互异（[metadata-propagation](FEAT-001-metadata-propagation.md) 隔离约定）；断言取「键按 taskId 区分」弱形态。
- **与持久化边界**：本用例断 TTL/故障/隔离三条独立语义；双角色复用与同进程挂起恢复归 [state-cache-persistence](FEAT-003-state-cache-persistence.md)。
