---
feature_id: FEAT-XXX1
test_type: workflow
scope: v0815; agent-core-java=930@20b1090e0106
deployable_units: [agent-core-java, agent-core-retrieval-java]
sut: 独立 Maven 消费工程中的 Core-only 与 Retrieval 0.1.16 正式制品
features: [FEAT-XXX1]
updated: 2026-09-22
---

# Core 与 Retrieval 制品验收：KnowledgeBase 模块依赖解耦

## 1. 测试目标

通过独立 Maven 消费工程和独立 JVM，把 `com.openjiuwen:agent-core-java:0.1.16` 与 `com.openjiuwen:agent-core-retrieval-java:0.1.16` 当作正式制品验收。公开观察面为消费工程构建结果、解析后的运行时 classpath、公开 Java API 的进程输出和退出码。

本设计证明 Core-only 可独立消费、Retrieval 的本地知识库主流程可用、Workflow 知识检索组件随 Retrieval 交付；不宣称外部向量存储和全部知识库能力已做全量回归。

## 2. 范围与非范围

范围：

- Core-only 构建/运行和 Retrieval 专用制品、实现类、依赖排除。
- InMemoryVectorStore + HashEmbedding 下的 `Document -> addDocuments -> retrieve` 代表性链路。
- `KnowledgeRetrievalComponent` 在 Retrieval classpath 可编译和加载、在 Core-only classpath 不存在。

非范围：

- Milvus、PGVector、Elasticsearch、Chroma 等后端矩阵及其端点、凭据和故障语义。
- 全文件格式解析、URL 解析、重排、查询改写、图检索、性能和容量。
- 产品内部类关系、SPI 描述文件内容、索引结构和私有状态。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-XXX-knowledgebase-module-decoupling.md`（2026-09-20） | Core 解耦、按需依赖、KnowledgeBase 主要流程与 Workflow 兼容的断言基线。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-core/Feat-Func-XXX-knowledgebase-module-decoupling.md`（2026-09-20） | 正式制品坐标、公开包名、Core/Retrieval 边界及本地 InMemory 运行入口。 |

## 4. 部署拓扑

```text
JUnit workflow driver
  -> MavenConsumerFixture
       +-> knowledgebase-core/pom.xml -> agent-core-java:0.1.16 -> CoreOnlyConsumer main
       `-> knowledgebase-retrieval/pom.xml -> agent-core-retrieval-java:0.1.16
              -> RetrievalConsumer main (flow | workflow-component)
  <- Maven exit、classpath.txt、子 JVM stdout/stderr 与退出码
```

边界要求：

- Fixture 复制版本化消费工程到独立 target 目录，使用 `SUT_M2_REPO`/`maven.repo.local` 解析本轮制品。
- 只调用公开 Java API；classpath 检查以 Maven 实际解析结果为准，不读取产品源码。
- 每个进程设置有界超时，失败保留命令脱敏输出；测试不读取或写入任何 LLM 配置。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| KB-01 | Core-only 独立消费 | G：`agent-core-java:0.1.16` 已安装到指定 Maven 仓；独立工程只声明该依赖 | W：`coreOnlyConsumerPackagesAndRunsWithoutRetrieval` 打包、生成运行时 classpath并启动 main | T：Maven/进程退出码为 0，stdout 含 `CORE_ONLY_OK`；classpath 不含 Retrieval、Milvus、PGVector、PDFBox、POI、DashScope；`com.openjiuwen.retrieval.SimpleKnowledgeBase` 不可加载 | `knowledgebase-core` 消费工程、MavenConsumerFixture |
| KB-02 | Retrieval 本地主流程兼容 | G：独立工程声明 `agent-core-retrieval-java:0.1.16`，使用本地 InMemory/HashEmbedding | W：`retrievalConsumerAddsAndRetrievesLocalDocument` 以唯一 canary 启动 `flow` 模式 | T：进程退出码为 0，stdout 含 `RETRIEVAL_FLOW_OK` 与 canary；至少返回一个命中结果，不断言精确分数 | `knowledgebase-retrieval` 消费工程、本地 Document |
| KB-03 | Workflow 组件交付 | G：Retrieval 工程已完成依赖/import 迁移，Core-only classpath 已隔离 | W：`retrievalConsumerLoadsWorkflowKnowledgeComponent` 启动 `workflow-component` 模式 | T：进程退出码为 0，stdout 含 `WORKFLOW_COMPONENT_OK`；目标类型由 Retrieval classpath 加载且 Core-only 不提供该类型 | 两个消费工程的类型加载探针 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| Core/ Retrieval 0.1.16 | 真实 SUT | 来自 Core `930@20b1090e0106` 构建安装的正式 Maven JAR；公开 API 和传递依赖是验收面。 |
| `MavenConsumerFixture` | Fixture | 复制工程、运行 Maven、读取 classpath、启动子 JVM、实施超时并返回脱敏结果；不包含产品实现。 |
| `knowledgebase-core` | Fixture | 只声明 Core，调用基础公开类型并探测 Retrieval 类型缺失。 |
| `knowledgebase-retrieval` | Fixture | 声明 Retrieval，使用 InMemoryVectorStore、HashEmbedding 和版本化 Document 输入。 |

## 7. 关键链路断言

- Feature 的 Core 解耦和按需依赖由 KB-01 的真实消费 classpath 与进程结果共同断言；只检查源码/POM 不计通过。
- Feature 的主要业务兼容由 KB-02 的公开 `addDocuments`/`retrieve` 结果断言，L2 只提供迁移后坐标和包名。
- Feature 的 Workflow 兼容由 KB-03 的消费工程编译/加载断言，不进一步执行完整 Workflow。
- 不断言精确相似度、内部 Provider、索引 key、ServiceLoader 顺序或外部后端行为。

## 8. 执行策略

- Smoke：KB-01、KB-02、KB-03。
- Full suite：KB-01、KB-02、KB-03。
- 依赖门禁：缺少 0.1.16 正式制品属于 artifact ERROR；Maven/JDK 不可用属于 environment ERROR；不得跳过后计为通过。
- LLM 或异步场景：无 LLM、无远程后端；Maven 和子 JVM 分别使用 300 秒与 45 秒 watchdog。
- 标识隔离：KB-02 的知识库名、集合名和 canary 使用 UUID；每个消费者复制到独立 target 目录。
- 实现映射：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/KnowledgeBaseModuleDecouplingAcceptanceTest.java` 包含三个与矩阵一一对应的方法；类级使用 `@Tag("FEAT-XXX1")`、`@Tag("integration")`、`@Tag("contract")`，Allure Feature 为 `FEAT-XXX1: KnowledgeBase 模块依赖解耦`，Story 分别使用三个场景 ID。

```bash
./scripts/run-pipeline.sh --env openjiuwen --skip-provision -- -Dtest=KnowledgeBaseModuleDecouplingAcceptanceTest
```
