---
用例编号: FEAT-003-middleware-activation
测试标题: Redis 状态缓存中间件激活机制——agent-runtime-java 单一开关激活 Checkpointer+A2A TaskStore 并发启动诊断
story: S1
优先级: P0
自动化状态: READY（证据汇总视角——3 个载体类已实现，启动门禁无需 LLM）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, redis, feat-003]
---

# FEAT-003-middleware-activation — Redis 状态缓存中间件激活机制

> **机制一句话**：Redis 状态缓存激活是 **agent-runtime-java 的中间件机制**（FEAT-003 §2 MUST）——运行时
> **单一开关** `openjiuwen.service.middleware.checkpointer.type=redis` 同时激活 Redis Checkpointer 与
> A2A TaskStore（复用同一 Redis 数据源抽象）；启动时发**策略日志**（profile 激活行 + checkpointer type 行 +
> Runtime Redis datasource 诊断行）并通过**可达探测**确认连接；认证场景下**密码不落日志**。本机制由 3 个
> 载体类（近端 standalone / 近端 cluster / 远端 standalone）共同承载，启动门禁断言 hermetic（无需 LLM）。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 单一开关激活 Checkpointer+TaskStore 双角色 + 启动策略日志 + 可达探测 + 密码脱敏（§2 MUST / §5.1.5 MUST） |
| **载体层 · agent-solution** | 机制触发载体 | 近端 workflow（`expense-review`，standalone+认证 / cluster 拓扑）· 远端 versatile（`edpa-adapter` 桥 + `edpa-plan-agent`） |
| **测试数据层** | 载体 agent 的实现逻辑 | Testcontainers Redis（standalone `--requirepass` / grokzen cluster 6 节点）——触发激活机制的载体夹具 |

## 关联特性

- **FEAT-003（智能体任务状态缓存）**：§2 单机接入 MUST / 集群接入 MUST / 策略日志 MUST / 密码脱敏 MUST；§3 数据源诊断；§4.1 同一接口。

## 关联架构约束 / FEAT-003 事实要求

- FEAT-003 §2：原生单机/集群接入 + 启动策略日志（MUST）；密码日志脱敏（MUST）。
- FEAT-003 §3：endpoint type / ref / client / TTL 摘要诊断行。
- FEAT-003 §5.1.5：密码脱敏 MUST。

## 前置条件

1. 被测 jar 就绪：`expense-review-workflow`/`expense-review-main`、`edpa-adapter`/`edpa-plan-agent`；Redis 由 Testcontainers（standalone / grokzen cluster）拉起。
2. `-Dtest.env=openjiuwen`（启动门禁 hermetic，无需 LLM）。
3. Docker 可用（Testcontainers）。

## 测试数据

- 激活夹具：Testcontainers Redis——standalone 经 `AuthenticatedRedisFactory`（`--requirepass <canary>`）拉起；cluster 经 `grokzen/redis-cluster:6.2.14`（6 节点 7000–7005）拉起。
- 注入：`profile("redis")` + `serviceBinding("redis", "REDIS_HOST/PORT", "{{host}}/{{port}}")` + `env("REDIS_PASSWORD", canary)`；cluster 经 `property(openjiuwen.service.middleware.redis.default.type, "cluster")` + `nodes[i]=<bridgeIp>:<port>`。

## 激活载体表（同一激活机制，三种拓扑 × 近/远端）

> 按「启动诊断步骤+可达语义一致、仅 Redis 拓扑 / 近远端载体不同」原则汇总；机制断言对三行同构成立。

| # | 载体 | Redis 拓扑 | 近/远端 | 落点方法 | 诊断证据 | 状态 |
|---|---|---|---|---|---|---|
| ① | `expense-review` | standalone（+认证） | 近端 | [ExpenseReviewRedisAcceptanceTest::redisMiddlewareActivatesOnBoot](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRedisAcceptanceTest.java) | profile 行 + checkpointer type 行 + Runtime Redis datasource 行 | ✅ |
| ② | `expense-review` | cluster | 近端 | [ExpenseReviewRedisClusterAcceptanceTest::redisClusterMiddlewareActivatesOnBoot](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRedisClusterAcceptanceTest.java) | profile 行 + `endpoint-type=cluster` + `JedisClusterRuntimeRedisClient` | ✅ |
| ③ | `edpa-adapter` + `edpa-plan-agent` | standalone | 远端 | [PlanAgentDirectStreamingRedisTest::redisMiddlewareActivatesOnBoot](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingRedisTest.java) | plan-agent checkpointer 行 + adapter Runtime Redis datasource 行 + profile 行 | ✅ |

## 测试步骤

> 三载体共享同一激活断言流程（hermetic 启动门禁）；密码脱敏为独立方法（同一载体①）。

| # | 动作 | 预期 |
|---|------|------|
| 1 | Testcontainers Redis 就绪；agent 以 redis profile + 注入启动 | 启动成功 |
| 2 | 就绪后读启动日志 + `RedisProbe`（standalone 带 AUTH / cluster 对 bridge IP）DBSIZE 探测 | 见「预期结果」A/B/C |
| 3 | 载体①附加：canary 为本次运行唯一 UUID，扫两 agent 全量日志 | 见「预期结果」D（密码脱敏） |

## 预期结果（机制断言）

### A — 单一开关激活两端双角色（Checkpointer + A2A TaskStore）
- **Given**：agent 以 `checkpointer.type=redis` 启动。
- **When**：读启动日志。
- **Then**：同一开关激活 Checkpointer 与 A2A TaskStore（复用同一 Redis 数据源抽象）；远端 adapter（无 Runner）仅激活 TaskStore、plan-agent 激活 checkpointer；近端 main 激活 checkpointer。
- **PASS**：两端双角色按拓扑各激活其角色。**FAIL**：开关未生效 / 角色激活错位（激活机制失效）。

### B — 启动策略日志（profile 激活 + checkpointer type + Runtime datasource 诊断）
- **Given**：A 通过。
- **When**：核查启动日志诊断行。
- **Then**：两 agent 均含 `The following 1 profile is active: "redis"`；装配 Runner 的 agent 含 `Begin to initializing checkpointer with type: redis`；含 `Runtime Redis datasource selected:` + `RuntimeRedisClient=`；cluster 含 `endpoint-type=cluster` + `JedisClusterRuntimeRedisClient`。
- **PASS**：诊断行齐备（checkpointer 行按 agent 装配情况可选）。**FAIL**：稳定诊断行（Runtime Redis datasource）缺失（激活机制无证据）。

### C — Redis 可达探测
- **Given**：A/B 通过。
- **When**：`RedisProbe`（standalone 3 参 AUTH / cluster 对 bridge IP:7000）发 DBSIZE。
- **Then**：可达（DBSIZE 返回）。
- **PASS**：Redis 可达。**FAIL**：探测失败（连接未建立）。

### D — 密码日志脱敏（§5.1.5 MUST，载体①）
- **Given**：认证 Redis（canary 唯一 UUID）。
- **When**：扫两 agent 全量日志。
- **Then**：日志含 `passwordConfigured=true`；canary **不**出现在任何 agent 日志。
- **PASS**：密码不泄漏。**FAIL**：canary 命中（脱敏机制缺陷）。

## 框架落点

| 项 | 值 |
|----|----|
| 证据来源 | 3 个载体类（见激活载体表），落 `cases/integration/workflow_call` |
| 标签 | `@Tag("integration")`；Allure `@Feature("FEAT-003: 智能体任务状态缓存")` + stories `wf.redis-standalone-activate` / `wf.cluster-access` / `wf.password-desensitize` / `wf.redis-two-role` |
| 基类 | `BaseManagedStackTest` + `SutStack.Builder.profile/.serviceBinding/.env/.property/.containerFactory` |
| 探针 | `RedisProbe`（standalone 3 参 AUTH 构造 / cluster bridge-IP）；`ManagedSutInstance.logFile()` |
| 视角 | 证据汇总——激活机制横切 3 载体，本用例不新增类 |

## 运行方式

```bash
# 启动门禁 hermetic（无需 LLM）；三载体各自运行
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewRedisAcceptanceTest#redisMiddlewareActivatesOnBoot+redisPasswordDoesNotLeakInLogs test
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewRedisClusterAcceptanceTest#redisClusterMiddlewareActivatesOnBoot test
./mvnw -Dtest.env=openjiuwen -Dtest=PlanAgentDirectStreamingRedisTest#redisMiddlewareActivatesOnBoot test
```

## 覆盖追溯

| FEAT-003 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| §2 原生单机接入 + 策略日志（MUST） | A/B/C（载体①③） | ✅ |
| §2 原生集群接入 + 同一接口（MUST） | A/B/C（载体②） | ✅ |
| §2/§5.1.5 密码日志脱敏（MUST） | D（载体①） | ✅ |

## 清理策略

- 栈由类级生命周期管理；Testcontainers Redis 随栈销毁。

## 风险与备注

- **激活证据的 agent 差异**：`Runtime Redis datasource selected:` 是稳定证据；`Begin to initializing checkpointer with type: redis` 是否打印取决于 agent 是否装配 Runner（远端 plan-agent 有、adapter 无；本地 main 有）。门禁以稳定诊断行为主，checkpointer 行仅作可选增强。
- **默认 `mvn test` 会真跑**：`TestEnvironment.current()` 回退 OPENJIUWEN，`@Tag("integration")` 不在 surefire 排除组内；启动门禁类无 `@Disabled`，首跑前须确认环境门控。
- **与持久化/恢复边界**：激活只断「中间件起来 + 可达 + 不泄密」；状态双角色复用与挂起/恢复归 [state-cache-persistence](FEAT-003-state-cache-persistence.md)。
