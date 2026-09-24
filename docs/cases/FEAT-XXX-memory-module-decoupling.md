---
feature_id: FEAT-XXX2
test_type: deepagent
scope: v0815; agent-core-java=930@20b1090e0106
deployable_units: [agent-core-java, agent-core-memory-java]
sut: 两个独立 Maven 消费工程中的 Core-only 与 Memory-enabled 0.1.16 正式制品
features: [FEAT-XXX2]
updated: 2026-09-22
---

# Core 与 Memory 制品验收：Memory 模块拆分与依赖架构优化

## 1. 测试目标

通过两个独立 Maven 消费工程，仅改变是否声明 `com.openjiuwen:agent-core-memory-java:0.1.16`，验证 Core-only Agent 可以打包运行且不携带 Memory 实现，Memory-enabled Agent 只引入一个 Memory 主制品即可发现宿主，同时同一最小 DeepAgent 的稳定输出保持一致。

公开观察面为 Maven 解析的运行时 classpath、`MemoryRuntimeResolver` 返回状态、DeepAgent 公开调用结果和子进程退出码。本设计不把 Memory 业务功能矩阵混入模块拆分验收。

## 2. 范围与非范围

范围：

- Core-only 工程的独立打包、运行、Memory JAR/实现排除和宿主为空。
- Memory-enabled 工程的单一主 JAR 消费、宿主发现和相同 DeepAgent 运行。
- `agent_name`、`mode` 和规范化输入的跨工程对照。

非范围：

- Memory CRUD/search、迁移、Graph、Lite、Team、Workflow、Harness rail、Provider 和存储后端矩阵。
- 拆分前历史制品的第三方依赖版本全量对比、性能、容量和并发。
- LLM 自然语言输出、Memory 私有数据、内部 Provider 类名和 ServiceLoader 顺序。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-XXX-memory-module-decoupling.md`（2026-09-20） | Core 独立交付、Memory 按需引入、依赖方向和行为兼容的断言基线。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-core/Feat-Func-XXX-memory-module-decoupling.md`（2026-09-20，draft 经本轮确认作为权威基线） | Memory 主制品坐标、宿主契约、Core-only 和 Memory-enabled 当前交付边界。 |

## 4. 部署拓扑

```text
JUnit deepagent driver
  -> MavenConsumerFixture
       +-> memory-core/pom.xml -> agent-core-java:0.1.16 -> MemoryConsumer main
       `-> memory-enabled/pom.xml -> agent-core-memory-java:0.1.16 -> MemoryConsumer main
  <- 两份 classpath、MemoryRuntimeResolver 状态、DeepAgent 稳定输出
```

边界要求：

- 两个消费者的源码、输入与 DeepAgent 配置相同，唯一依赖差异是 Memory 主制品。
- DeepAgent 显式关闭 task loop、task planning 和模型调用，避免 LLM 或 Memory 业务行为扩大测试范围。
- Fixture 不实现 MemoryRuntime，不读取 Memory 内部状态，只使用 Core 公开 resolver 观察按需装配。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| MEM-01 | Core-only Agent 打包和运行 | G：`memory-core` 只声明 `agent-core-java:0.1.16`，使用确定性 DeepAgent 配置和唯一 query | W：`coreOnlyAgentPackagesAndRunsWithoutMemory` 打包、生成 classpath并启动 main | T：Maven/进程退出码为 0；classpath 不含 `agent-core-memory-java`；stdout 报告 `MEMORY_RUNTIME=absent`、`agent_name=deep_agent`、`mode=normal` 和原 query | `memory-core`、MavenConsumerFixture |
| MEM-02 | Memory-enabled Agent 对照 | G：`memory-enabled` 仅增加 `agent-core-memory-java:0.1.16`，使用与 MEM-01 相同源码/配置/query | W：`memoryEnabledAgentPackagesAndPreservesCoreBehavior` 打包、启动 main 并与 MEM-01 结果对照 | T：Maven/进程退出码为 0；classpath 含一个 Memory 主制品；stdout 报告 `MEMORY_RUNTIME=present`；`agent_name`、`mode` 和 query 与 MEM-01 完全一致 | `memory-enabled`、输出对照探针 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| Core/Memory 0.1.16 | 真实 SUT | 来自 Core `930@20b1090e0106` 构建安装的正式 Maven JAR；按需依赖和公开宿主解析是验收面。 |
| `MavenConsumerFixture` | Fixture | 隔离构建两个消费者、生成 classpath、启动子 JVM 和返回结构化 stdout；不实现产品逻辑。 |
| 共享 `MemoryConsumer` 源码 | Fixture | 创建关闭 task loop 的最小 DeepAgent并输出稳定字段；两个工程源码保持一致。 |

## 7. 关键链路断言

- MEM-01 以真实 classpath、resolver 和 Agent 运行三层证据证明 Core-only 可用，不能只断言 Memory 类不可加载。
- MEM-02 以单一 Memory 主制品、resolver 存在和同一 Agent 输出证明按需引入不改变基础运行表现。
- 跨工程只比较稳定字段，不比较 workspace 绝对路径、日志顺序、对象类型名或 LLM 文本。
- 用户确认本轮不执行 Memory 业务矩阵；未覆盖的 CRUD/Graph/Team/Provider 能力不计入本轮结论。

## 8. 执行策略

- Smoke：MEM-01、MEM-02。
- Full suite：MEM-01、MEM-02。
- 依赖门禁：缺少 0.1.16 正式制品属于 artifact ERROR；Maven/JDK 不可用属于 environment ERROR；不得用跳过抵扣。
- LLM 或异步场景：显式关闭模型调用，无 LLM 依赖；Maven 和子 JVM 分别使用 300 秒与 45 秒 watchdog。
- 标识隔离：每个消费者使用独立 target/workspace，query 含同一 runId；完成后关闭 DeepAgent。
- 实现映射：`src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/MemoryModuleDecouplingAcceptanceTest.java` 包含两个唯一方法；类级使用 `@Tag("FEAT-XXX2")`、`@Tag("integration")`、`@Tag("contract")`，Allure Feature 为 `FEAT-XXX2: Memory 模块拆分与依赖架构优化`，Story 分别使用两个场景 ID。

```bash
./scripts/run-pipeline.sh --env openjiuwen --skip-provision -- -Dtest=MemoryModuleDecouplingAcceptanceTest
```
