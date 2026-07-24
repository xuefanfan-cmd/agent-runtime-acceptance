---
用例编号: FEAT-003-config-variant
测试标题: Redis 配置变体机制——agent-runtime-java 的缺省单机兼容与命名连接引用路由
story: S1
优先级: P1
自动化状态: TOBUILD（`ExpenseReviewRedisConfigTest` 2 方法待新建；方法级自管 `SutStack`，不继承固定类级栈）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, redis, feat-003]
---

# FEAT-003-config-variant — Redis 配置变体机制

> **机制一句话**：Redis 配置变体是 **agent-runtime-java 的配置解析机制**（FEAT-003 §5.1.1 / §3）——
> **缺省兼容**：不配 `type`/`ref` 时按单机 standalone 默认解析（§5.1.1）；**命名引用路由**：redis 连接按名
> 引用（`default`/`secondary`），`checkpointer.redis-ref=<name>` 把 workflow 状态路由到该命名数据源对应
> database（§3）。两条语义验证配置面正确性与多使用方复用。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 缺省配置按单机兼容解析（§5.1.1）+ 命名连接引用路由到指定 database（§3） |
| **载体层 · agent-solution** | 机制触发载体 | 近端 workflow（`expense-review`，方法级自管栈） |
| **测试数据层** | 载体 agent 的实现逻辑 | 全新 Redis 只注入 host/port（缺省用例）/ 同一 Redis 双 database 命名引用（路由用例）——驱动配置解析的载体夹具 |

## 关联特性

- **FEAT-003（智能体任务状态缓存）**：§5.1.1 缺省单机兼容；§3 命名连接引用、多使用方复用。

## 关联架构约束 / FEAT-003 事实要求

- FEAT-003 §5.1.1：缺省配置兼容——不配 type 按单机。
- FEAT-003 §3：redis 连接引用按名引用（default/secondary 等），多使用方复用同一连接抽象。

## 前置条件

1. 被测 jar 就绪（`expense-review`）。
2. `-Dtest.env=openjiuwen`（hermetic 启动门禁无需 LLM；路由用例跑一笔超标报销至 INPUT_REQUIRED 需 LLM）。
3. Docker（Testcontainers Redis）。

## 测试数据

- 缺省用例：全新 Redis，只注入 `host`/`port`（不配 `type`/`ref`）。
- 路由用例：同一 Redis——`redis.default.*`→DB0、`redis.secondary.*`→DB2；`checkpointer.redis-ref=secondary`；跑一笔超标报销至 INPUT_REQUIRED。

## 配置参数表（同一载体，两类配置语义）

| 方法 | 配置语义 | 注入 | 预期 |
|---|---|---|---|
| `legacyConfigWithoutTypeDefaultsToStandalone` | 缺省兼容 | 仅 host/port（不配 type/ref） | 诊断 `endpoint-type=standalone`/`redis-ref=default`，读写可达 |
| `namedReferenceRoutesWorkflowStateToConfiguredDatabase` | 命名引用路由 | default→DB0/secondary→DB2 + `redis-ref=secondary` | 状态只落 DB2，DB0 无新增；诊断 `redis-ref=secondary` |

## 测试步骤

> 两类配置语义合并为一个测试类（`ExpenseReviewRedisConfigTest`）的两个 `@Test` 方法；**方法级自管 `SutStack`**（try-with-resources，同 react_travel Feat003 类风格），不继承 `BaseManagedStackTest` 固定类级栈。

| # | 动作 | 预期 |
|---|------|------|
| 1 | **缺省**：全新 Redis，只注入 host/port 启动 agent 并就绪 | 启动成功；诊断行 `endpoint-type=standalone`、`redis-ref=default`；Redis 读写可达 |
| 2 | **路由**：同一 Redis 双 database 命名引用（`redis-ref=secondary`→DB2）跑一笔超标报销至 INPUT_REQUIRED | DB2 出现该 taskId 相关状态键，DB0 无新增；诊断行 `redis-ref=secondary` |

## 预期结果（机制断言）

### A — 缺省配置兼容（不配 type 按单机，§5.1.1）
- **Given**：全新 Redis，只注入 host/port（不配 type/ref）。
- **When**：启动 agent 并就绪。
- **Then**：启动成功；诊断行显示 `endpoint-type=standalone`、`redis-ref=default`；Redis 读写可达。
- **PASS**：缺省按单机兼容。**FAIL**：缺省配置启动失败 / 诊断错位（缺省兼容机制失效）。

### B — 命名连接引用路由到指定 database（§3）
- **Given**：同一 Redis，`redis.default.*`→DB0、`redis.secondary.*`→DB2，`checkpointer.redis-ref=secondary`。
- **When**：跑一笔超标报销至 INPUT_REQUIRED。
- **Then**：DB2 出现该 taskId 相关状态键，DB0 无新增；诊断行 `redis-ref=secondary`。
- **PASS**：状态路由到命名 database。**FAIL**：状态落错 database / 命名引用未生效（路由机制失效）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | ⬜ `ExpenseReviewRedisConfigTest`（新建，落 `cases/integration/workflow_call`，方法级自管栈） |
| 标签 | `@Tag("integration")`；Allure `@Feature("FEAT-003")` + stories `wf.config-default-standalone` / `wf.named-ref-routing`（待注册） |
| 基类 | 方法级 `SutStack`（try-with-resources）+ `BackingServices`/`TestContainerFactory` |
| 探针 | `RedisProbe` 分库探测（DB0/DB2 分别断言） |
| 同型先例 | `react_travel/Feat003RedisConfigurationAndDiagnosticsTest`（配置错误诊断同型实现参考，归 component/contract 层） |

## 运行方式

```bash
# ⬜ 待新建；缺省用例 hermetic，路由用例需 LLM
mvn test -Dtest=ExpenseReviewRedisConfigTest -Dtest.env=openjiuwen \
  -DLLM_API_KEY=... -DLLM_BASE_URL=... -DLLM_MODEL_NAME=...
```

## 覆盖追溯

| FEAT-003 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| §5.1.1 缺省配置兼容（不配 type 按单机） | A | ⬜ 待新建 |
| §3 命名连接引用、多使用方复用 | B | ⬜ 待新建 |

## 清理策略

- 方法级 `SutStack` try-with-resources 自动收尾；Testcontainers Redis 随方法结束销毁。

## 风险与备注

- **方法级栈选择**：配置变体需每方法不同栈配置，故不继承 `BaseManagedStackTest`（固定类级栈），采用方法级自管 `SutStack`（同 react_travel Feat003 风格）。
- **非法配置不在此**：缺 nodes / 非法 type / 缺 ref / 非法 TTL 等错误配置诊断归 component 层（`react_travel/Feat003RedisConfigurationAndDiagnosticsTest` 同型先例），SIT 不建非法输入用例（组织原则 2）。
- **路由用例的 database 断言**：DB0/DB2 分库探测须用 `RedisProbe` 分库能力；cluster 下 database 被 ignore（见 [cluster-config-semantics](FEAT-003-cluster-config-semantics.md)），路由用例限 standalone。
