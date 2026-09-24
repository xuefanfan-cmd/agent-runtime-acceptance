---
feature_id: FEAT-XXX3
test_type: deepagent
scope: v0815; agent-core-java=930@20b1090e0106, agent-runtime-java=develop@4642486e1ed4
deployable_units: [agent-core-java, agent-service-adapters-agentcore]
sut: Runtime 0.1.3 Handler + Core 0.1.16 DeepAgent + Redis 7
features: [FEAT-XXX3]
updated: 2026-09-22
---

# Runtime/Core/Redis 验收：Todo 复用 Checkpointer Redis

## 1. 测试目标

使用正式 Runtime/Core 制品和真实 Redis，验证只配置 Runtime Redis Checkpointer 时，真实 `JiuwenCoreAgentHandler` 会在执行前为未初始化且未显式选择 Todo 存储的 DeepAgent 应用默认复用；随后真实 `todo_create` 和 Checkpointer 数据出现在同一 Redis 实例。

该场景不经 LLM 决策工具调用，避免模型波动；仍使用真实 DeepAgent、TaskPlanningRail、Handler、Runner/Checkpointer 和 Todo 工具，不直接构造 TodoStorage 绕过装配链路。

## 2. 范围与非范围

范围：

- Runtime 现有 Redis Checkpointer 配置作为唯一 Redis 连接配置。
- 尚未初始化、未显式指定 Todo 存储的真实 DeepAgent。
- Handler 准备、真实 Todo 工具写入和同端点 Redis 外部观察。

非范围：

- TTL 继承/覆盖/续期/自然过期、非法策略及命令失败矩阵。
- 显式 file/kv/custom、已初始化实例、旧构造器、EDP、Cluster、TLS 和故障恢复。
- Redis 私有 key 名、连接对象身份、内部调用顺序或通用资源管理。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-XXX-redis-middleware-architecture-optimization.md`（2026-09-20） | Runtime 一次配置、默认 Todo 复用同一 Redis 的主路径断言。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-core/Feat-Func-XXX-redis-middleware-architecture-optimization.md`（2026-09-20，draft 经本轮确认作为权威基线） | 未初始化/未显式选择的适配条件、Handler 时序和 Core 复用入口。 |

## 4. 部署拓扑

```text
JUnit deepagent driver
  +-> BackingServices -> Redis 7 container
  `-> MavenConsumerFixture -> redis-runtime-reuse 独立 JVM
        -> Runtime Redis middleware configuration
        -> JiuwenCoreAgentHandler.prepareAgentForExecution(actual DeepAgent)
        -> Runner/RedisCheckpointer + DeepAgent.ensureInitialized
        -> registered todo_create + checkpoint write
  <- 子 JVM 结构化结果 + 独立 RESP Redis scan
```

边界要求：

- 消费工程不设置 Todo 连接、`kvStoreConfig` 或显式 Todo 类型，也不向 DeepAgent 注入 Store。
- Redis 容器是真实依赖；JUnit 使用独立 RESP 客户端按本次唯一 session/canary 观察并清理数据。
- 子 JVM 只通过公开/受支持 Java 入口组装 Runtime 与 Core；不反射私有字段，不读取源码。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| REDIS-01 | Runtime 一次配置驱动 Todo 复用 | G：Redis 7 ready；Runtime 只配置 Redis Checkpointer；DeepAgent 含 TaskPlanningRail、未初始化且 `isTodoStorageTypeExplicit=false` | W：`runtimeRedisConfigurationMakesDefaultTodoReuseCheckpointerStore` 启动独立消费者，经真实 Handler 准备实际 Agent，启动 Runner、初始化 Agent，调用注册的 `todo_create` 写唯一 canary并写同会话 checkpoint | T：进程退出码为 0；准备后类型为 `checkpointer_redis`；工具 success=true；独立 Redis 观察器在同一端点看到包含 canary 的 Todo 数据和同 session 的非 Todo 数据；没有第二份 Todo Redis 配置 | Redis BackingServices、`redis-runtime-reuse`、RESP 探针 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| Runtime 0.1.3 Handler/Redis middleware | 真实 SUT | 来自 Runtime `develop@4642486e1ed4` 的正式 Maven 制品，负责现有 Redis 配置和执行前默认适配。 |
| Core 0.1.16 DeepAgent/Runner/Todo | 真实 SUT | 来自 Core `930@20b1090e0106`，负责实际 Checkpointer、Rail、工具注册和 Todo 写入。 |
| Redis 7 | 真实依赖 | 同时承载 Checkpoint 与 Todo；测试只通过 Redis 协议外部观察。 |
| `MavenConsumerFixture` 与 RESP 探针 | Fixture | 启动独立消费者、传递非敏感端点、观察唯一标识并清理；不创建产品替身或第二 Store。 |

## 7. 关键链路断言

- Feature 的“一次配置”要求由消费者参数清单和默认配置状态共同证明：仅存在 Checkpointer Redis 参数，Todo 后端未显式设置。
- L2 的 Runtime 适配条件由真实 Handler 对实际未初始化 DeepAgent 的可见配置结果证明，随后必须走真实工具写入，不能停留在类型字符串检查。
- 最终 Oracle 同时要求 Todo canary 和 Checkpoint 证据位于同一 Redis 端点；仅 Todo 或仅 Checkpoint 存在均失败。
- 不把具体 key 模板、RedisStore 对象身份或 TTL 精确值升级为本轮合同。

## 8. 执行策略

- Smoke：REDIS-01。
- Full suite：REDIS-01。
- 依赖门禁：Docker/Redis 不可用为 environment ERROR；Runtime/Core 制品缺失或版本不符为 artifact ERROR；不允许静默跳过。
- LLM 或异步场景：不调用 LLM；Redis 就绪由现有 Testcontainers 生命周期管理，Maven 和子 JVM 分别使用 300 秒与 45 秒 watchdog；消费者完成后同步检查本轮新增数据。
- 标识隔离：session 与 canary 使用 UUID；测试前记录匹配 key 集合，结束时只删除本次新增 key并关闭 Runner、Agent、客户端和容器。
- 实现映射：`src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RedisMiddlewareReuseAcceptanceTest.java` 对应唯一场景；类级使用 `@Tag("FEAT-XXX3")`、`@Tag("integration")`、`@Tag("spi-integration")`，Allure Feature 为 `FEAT-XXX3: Todo 复用 Checkpointer Redis`，Story 为 `FEAT-XXX3.redis.runtime-default-reuse: Runtime 一次配置驱动 Todo 复用`。

```bash
./scripts/run-pipeline.sh --env openjiuwen --skip-provision -- -Dtest=RedisMiddlewareReuseAcceptanceTest
```
