---
title: 维护锚点（内部）
description: 用户可见文档结论与 third_party 源码文件的维护映射，仅供仓库维护者回查
audience: maintainer
---

# 维护锚点（内部文件，不面向文档用户）

> **校验优先级**：生成工程使用的公开 API 以 `compatibility.md` 推荐版本的发布 jar 为最终准绳；
> `third_party/` 源码用于解释行为和定位实现。两者签名不一致时，示例必须先通过发布 jar 编译，
> 并在本文件登记漂移，不能仅按源码快照改写。

> ⚠️ **内部维护专用**：本文件仅供有 third_party 源码权限的框架维护者使用。
> third_party 源码对文档用户（AI coding 消费者）不可见，因此这些路径
> **禁止出现在任何用户可见页面**（含 frontmatter）。源码演进时按本清单回查修订对应页面。
> 新增/修改页面时，在对应小节登记其结论依赖的源码文件。

## how-to/react-agent.md

行为与装配参考：`third_party/react&deep-agent-demos/meeting-agent-demos/`（meeting-react-agent；用户不可见，仅作维护回查）

- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/singleagent/agents/ReActAgent.java`（new + configure 路径）
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/singleagent/agents/ReActAgentConfig.java`（builder 方法集、configureModelClient(provider, apiKey, apiBase, modelName, verifySsl)、getModelConfigObj()）
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/foundation/llm/schema/ModelRequestConfig.java`（@Data → setTemperature/setTopP）
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/singleagent/AbilityManager.java`（add）
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/runner/resourcemanager/ResourceMgr.java`（addTool(Tool, Collection<String>, boolean)）
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/runner/Runner.java`（resourceMgr() 静态）

## how-to/deepagent.md

行为对照表来源：`third_party/react&deep-agent-demos/meeting-agent-demos/README.md`（meeting-deep-agent；用户不可见，仅作维护回查）

- `third_party/agent-core-java/src/main/java/com/openjiuwen/harness/factory/HarnessFactory.java`（createDeepAgent 三重载；装配内容：Workspace 解析、SecurityRail/TaskCompletionRail/TaskPlanningRail 默认注入、general-purpose 子代理、工具注册）
- `third_party/agent-core-java/src/main/java/com/openjiuwen/harness/schema/config/DeepAgentConfig.java`（Lombok @Builder；completionTimeout Double、tools List<Object>、model/backend Object）
- `third_party/agent-core-java/src/main/java/com/openjiuwen/harness/rails/TaskCompletionRail.java`（源码快照为 0/2/4/7 参）；推荐发布 jar 为 0/6/7 参，timeout 使用 `Duration`
- `third_party/agent-core-java/src/main/java/com/openjiuwen/harness/workspace/Workspace.java`（源码快照存在多组构造器；推荐发布 jar 使用 `Workspace.builder()` 或三参构造器）
- `third_party/agent-core-java/src/main/java/com/openjiuwen/harness/deep_agent/DeepAgent.java`（ensureInitialized() 第 346 行、shutdown() 第 833 行——无 close()；model/backend Map 键消费第 124~213 行：model/model_name、top_p/topP、api_base/apiBase/base_url、verify_ssl/verifySsl、timeout）
- 注意：此处存在源码快照与推荐发布 jar 的公开签名漂移；用户示例按发布 jar 的 `Duration` 与 `Workspace.builder()` 编写并做编译校验

## how-to/workflow-agent.md

- `third_party/agent-solution/common/example/versatile-orchestration-demo/expense-review/src/main/java/com/openjiuwen/example/versatile/orchestration/expensereview/ExpenseReviewConfiguration.java`
- `third_party/agent-solution/common/example/versatile-orchestration-demo/expense-review/src/main/java/com/openjiuwen/example/versatile/orchestration/expensereview/tool/CompanyPolicyTool.java`
- `third_party/agent-solution/common/example/versatile-orchestration-demo/expense-review/src/main/resources/application.yml`
- `third_party/agent-runtime-java/service/agent-service-spec/src/main/java/com/openjiuwen/service/spec/dto/QueryRequest.java`

## how-to/versatile-agent.md

- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-versatile/src/main/java/com/openjiuwen/service/adapters/versatile/agentfw/VersatileAgentHandler.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-versatile/src/main/java/com/openjiuwen/service/adapters/versatile/autoconfigure/VersatileProperties.java`
- `third_party/agent-solution/common/agents/versatile-agent-java/src/main/java/com/openjiuwen/versatile/VersatileAgentConfiguration.java`
- `third_party/agent-solution/common/example/versatile-orchestration-demo/expense-review-main/src/main/resources/application.yml`

## how-to/config-driven-agent.md

- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/autoconfigure/AgentCoreAdaptersAutoConfiguration.java`
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/agentfw/JiuwenCoreAgentHandler.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/agentfw/JiuwenCoreAgentExtHandler.java`
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/runner/resourcemanager/ResourceMgr.java`
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/runner/resourcemanager/Result.java`（2 参 addAgent 返回，isErr/msg）
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/runner/base/Result.java`（3 参 addAgent(card, supplier, tag) 返回，isError/getError——用户页现按此口径）

## how-to/a2a.md

- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/agentfw/JiuwenCoreAgentExtHandler.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/external/RemoteA2aToolInstaller.java`
- `third_party/agent-runtime-java/service/agent-service-app/src/main/java/com/openjiuwen/service/app/config/A2AProperties.java`
- `third_party/agent-runtime-java/service/agent-service-spec/src/main/java/com/openjiuwen/service/spec/dto/QueryChunk.java`

## how-to/middleware.md

- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/MiddlewareProperties.java`
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/redis/RedisMiddlewareAutoConfiguration.java`
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/redis/RedisConnectionAssembler.java`
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/redis/RedisJedisClientFactory.java`（内部实现，不进用户可见页；2026-08-09 外部文档核实时登记）
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/redis/JedisPooledRuntimeRedisClient.java`（同上，standalone 实现）
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/redis/JedisClusterRuntimeRedisClient.java`（同上，cluster 实现）
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/middleware/MiddlewareAdapterRegistrar.java`
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/middleware/DefaultMiddlewareAdapterRegistrar.java`
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/middleware/AgentCoreCheckpointerConfigAssembler.java`
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/autoconfigure/MiddlewareAdaptersAutoConfiguration.java`（registrar Bean 无条件注册 + 创建时即 applyToRunnerConfig——「启动期应用、与 handler 声明方式无关」的依据）
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/autoconfigure/MemoryAdaptersAutoConfiguration.java`
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/memory/MemoryStore.java`
- `third_party/agent-runtime-java/service/agent-service-spec/src/main/java/com/openjiuwen/service/spec/spi/RuntimeRedisClient.java`
- `third_party/agent-runtime-java/service/agent-service-app/src/main/java/com/openjiuwen/service/app/autoconfigure/A2AAutoConfiguration.java`
- `third_party/agent-runtime-java/service/agent-service-demo/example/config/application-base.yml`

## how-to/sandbox.md

上游：`third_party/MIDDLEWARE_ANALYSIS.md` §2（2026-08-09 合并，仅 Sandbox 部分；
Redis 部分与仓内重叠未采纳，版本口径 / registrar 说法 / pip-index-url 断言已按源码
剔除或修正；demo 私有类 SandboxOps/SandboxRail/UrlVerifyRail/ExecResult 不导入）

- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/AgentCoreSandboxClientFactory.java`（接口：create() / create(serverId) / configFor()）
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/DefaultAgentCoreSandboxClientFactory.java`（默认实现，内部类不进用户可见页）
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/external/AgentCoreExternalProperties.java`（前缀 openjiuwen.service.external；SandboxPolicy.timeoutMs 默认 30000 且 > 0；SandboxServer 字段与默认值 jiuwenbox / pre_deploy / delete / "."；enabled=true 时 servers 非空校验、sandbox-type 非空校验）
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/autoconfigure/AgentCoreAdaptersAutoConfiguration.java`（@ConditionalOnProperty external.sandbox.enabled=true + @ConditionalOnMissingBean 装配工厂 Bean）
- `third_party/agent-core-java/documents/zh/2.开发指南/API文档/com.openjiuwen.core/sysop/BaseCodeOperation.md` 与 `BaseFsOperation.md`（executeCode / downloadFile 签名）
- `third_party/agent-core-java/examples/sandbox/SandboxExample.java`（code()/fs() 用法参照）

## how-to/skillhub.md

- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/middleware/skillhub/SkillHubMiddlewareAutoConfiguration.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/middleware/skillhub/SkillHubMiddlewareProperties.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/middleware/skillhub/SkillHubManager.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/middleware/skillhub/SkillHubInstaller.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/agentfw/JiuwenCoreAgentExtHandler.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-spec-ext/src/main/java/com/openjiuwen/service/spec/ext/skillhub/spi/SkillHubProvider.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-spec-ext/src/main/java/com/openjiuwen/service/spec/ext/skillhub/SkillHubConfig.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-spec-ext/src/main/java/com/openjiuwen/service/spec/ext/skillhub/SkillHubErrorCategory.java`
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/singleagent/BaseAgent.java`
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/singleagent/agents/ReActAgentConfig.java`
- `third_party/agent-solution/common/example/skillhub-runtime-demo/src/main/resources/application.yml`

## how-to/custom-rest.md

- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-app/agent-service-app-custom-rest/src/main/java/com/openjiuwen/service/app/custom/rest/CustomRestProtocolAdapter.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-app/agent-service-app-custom-rest/src/main/java/com/openjiuwen/service/app/custom/rest/CustomRestAutoConfiguration.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-app/agent-service-app-custom-rest/src/main/java/com/openjiuwen/service/app/custom/rest/CustomRestA2ABridge.java`
- `third_party/agent-solution/common/example/agentcore-ext-remote-a2a-tool-demo/agent-a-deepagent-runtime/src/main/java/com/openjiuwen/example/agentcoreext/agenta/CustomRestDemoAdapter.java`
- `third_party/agent-solution/common/example/agentcore-ext-remote-a2a-tool-demo/agent-a-deepagent-runtime/src/main/resources/application.yml`

## architecture/00-OpenJiuwen技术架构总览.md（原 framework.md 并入节）

- `third_party/agent-runtime-java/service`
- `third_party/agent-solution/common/agent-core-ext-java`
- `third_party/agent-solution/common/agent-runtime-ext-java`

## architecture/01-agent-core-java技术架构.md

> 与 `third_party/代码架构文档/01-*.md` 对齐；包结构/继承树/图引擎/Runner/SPI/Rail 变化时逐点回查。

- 顶层包清单：`third_party/agent-core-java/src/main/java/com/openjiuwen/core/`（application / common / context / context_engine / controller / foundation / graph / memory / multi_agent / multiagent / operator / retrieval / runner / security / session / singleagent / sys_operation / sysop / workflow）
- 继承体系：`.../core/singleagent/BaseAgent.java`、`.../core/singleagent/agents/ReActAgent.java`、`.../core/application/`（ControllerAgent / LLMAgent / WorkflowAgent 所在包）、`.../core/multiagent/`（CommunicableAgent / ContainerAgent）
- 图执行引擎：`.../core/graph/`（Pregel BSP、Channel、路由、中断恢复）
- Runner/Session：`.../core/runner/Runner.java`、`.../core/runner/resourcemanager/`、`.../core/session/`（checkpointer 命名空间与生命周期）
- 记忆/上下文：`.../core/memory/`、`.../core/context_engine/`
- SPI 扩展点：`third_party/agent-core-java/src/main/resources/META-INF/services/`（MCP / RemoteClient / Checkpointer / KV / Object / Vector 六个服务文件）；模型客户端工厂为内嵌接口 `.../core/foundation/llm/Model.java`（`ModelClientFactory`）
- Rail 机制：`.../core/singleagent/`（rail 钩子与 forceFinish gate）

## architecture/02-agent-runtime-java技术架构.md

> 模块依赖链 spec ← adapters-common ← adapters-agentcore ← app ← demo；Controller 不绕过 Orchestrator。

- 模块聚合：`third_party/agent-runtime-java/service/`（agent-service-spec / agent-service-adapters/agent-service-adapters-common / agent-service-adapters-agentcore / agent-service-app / agent-service-demo）
- 契约层：`.../agent-service-spec/src/main/java/com/openjiuwen/service/spec/spi/AgentHandler.java`、`.../spec/spi/ServeOrchestrator.java`
- 编排实现：`.../agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/`（DefaultServeOrchestrator / A2AEnabledServeOrchestrator）、`.../app/controller/query/`（QueryMvcController / QueryWebFluxController）
- 适配层：`.../agent-service-adapters/agent-service-adapters-agentcore/`（RunnerConfig 注入、出站 SPI 绑定）
- 根 POM 版本锁定：`third_party/agent-runtime-java/pom.xml`

## architecture/03-agent-solution技术架构.md

> 七模块全景；范围外模块只保留模块级事实（见页面 ⚠️ 注记）。

- 顶层模块：`third_party/agent-solution/common/`（agent-core-ext-java / agent-runtime-ext-java / agent-bus / agent-client / agent-evolve / agents / example）
- agent-bus：`.../common/agent-bus/`（agent-gateway / event-bus / registry-discovery-center）
- agents：`.../common/agents/`（pev / edp-agent-java / edpa-alpha / versatile-agent-java）
- agent-evolve：`.../common/agent-evolve/`（evoagent / evoagent-adapter，Python）
- 示例拓扑：`.../common/example/`（multi-react-travel-demo / multi-deep-research-demo 等）

## architecture/04-三仓协作与扩展体系.md

> 四类扩展点矩阵与端到端调用链。

- Java SPI：`third_party/agent-core-java/src/main/resources/META-INF/services/`
- AgentHandler SPI：`third_party/agent-runtime-java/service/agent-service-spec/src/main/java/com/openjiuwen/service/spec/spi/AgentHandler.java`
- Rail 注册：`third_party/agent-core-java/src/main/java/com/openjiuwen/core/singleagent/BaseAgent.java`（registerRail）
- Spring 自动装配：`third_party/agent-solution/common/agent-runtime-ext-java/**/src/main/resources/META-INF/spring/`（各 adapter 的 AutoConfiguration.imports）
- installBeforeRun 生命周期：`third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/agentfw/JiuwenCoreAgentExtHandler.java`

## architecture/05-关键技术机制总结.md

> 六个机制各自回查点（与 01~03 锚点有重叠，按机制索引）。

- Pregel 图执行：`third_party/agent-core-java/src/main/java/com/openjiuwen/core/graph/`
- Checkpointer 生命周期：`.../core/session/checkpointer/`、`.../core/runner/Runner.java`
- Rail 钩子链：`.../core/singleagent/`
- AgentHandler SPI 单向依赖：`third_party/agent-runtime-java/service/agent-service-spec/`、`.../agent-service-app/orchestrator/`
- 事件总线两跳转发：`third_party/agent-solution/common/agent-bus/event-bus/`
- 自演进闭环：`third_party/agent-solution/common/agent-evolve/`

## compatibility.md

源码镜像锚点（维护者回查专用；compatibility.md 用户可见页只保留发布件口径，
commit / tag / 镜像 POM 版本不在该页体现——2026-08-09 移入本文件）：

| 仓 | 镜像 POM 版本 | 上游 commit | 上游 tag |
| --- | --- | --- | --- |
| agent-core-java | 0.1.14 | `406188a1b421` | `v0.1.13.post1`（tag 落后于镜像 POM） |
| agent-runtime-java | 0.1.1 | `acd12ce7259f` | `v0.1.1` |
| agent-solution | 0.1.0 | `bf7a851d0a96` | 无 |

- `third_party/agent-core-java/pom.xml`
- `third_party/agent-runtime-java/pom.xml`
- `third_party/agent-solution/common/agent-runtime-ext-java/pom.xml`
- `third_party/agent-runtime-java/service/agent-service-app/pom.xml`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/pom.xml`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-versatile/pom.xml`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-app/agent-service-app-custom-rest/pom.xml`
- `third_party/agent-solution/common/agent-core-ext-java/agent-core-ext-react-rails/pom.xml`

### 发布件编译回归（2026-08-09，人工）

使用 Java 17、Spring Boot 4.0.6，在系统临时目录生成 Maven 工程，不向用户示例目录写入 pom：

| 校验对象 | 依赖版本 | 结果 |
| --- | --- | --- |
| `examples/workflow` | runtime `0.1.1.post1`（app + agentcore adapter） | `mvn clean compile` 通过 |
| `examples/react` | runtime `0.1.1.post1`（app + agentcore adapter） | `mvn clean compile` 通过 |
| `examples/deepagent` | runtime `0.1.1.post1`（app + agentcore adapter，传递 core `0.1.14.post1`） | `mvn clean compile` 通过 |
| `examples/versatile` | runtime app `0.1.1.post1` + solution versatile `0.1.0` | `mvn clean compile` 通过 |
| Custom REST Java snippets | runtime `0.1.1.post1` + custom-rest `0.1.0` | `mvn clean compile` 通过 |
| SkillHub Java snippet | runtime app `0.1.1.post1` + agentcore-ext `0.1.0` | `mvn clean compile` 通过 |

该记录是一次性人工回归，不代表已接入 CI；后续修改 examples/snippets 后必须重新执行。

## api/agent-core-java.md

- `third_party/agent-solution/common/example/versatile-orchestration-demo/plan-agent/src/main/java/com/openjiuwen/example/versatile/orchestration/planagent/PlanAgentConfiguration.java`
- `third_party/agent-solution/common/example/versatile-orchestration-demo/expense-review/src/main/java/com/openjiuwen/example/versatile/orchestration/expensereview/ExpenseReviewConfiguration.java`
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/runner/resourcemanager/ResourceMgr.java`
- `third_party/agent-core-java/src/main/java/com/openjiuwen/core/singleagent/BaseAgent.java`

## api/agent-runtime-java.md

- `third_party/agent-runtime-java/service/agent-service-spec/src/main/java/com/openjiuwen/service/spec/spi/AgentHandler.java`
- `third_party/agent-runtime-java/service/agent-service-spec/src/main/java/com/openjiuwen/service/spec/dto/QueryChunk.java`
- `third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/agentfw/JiuwenCoreAgentHandler.java`
- `third_party/agent-runtime-java/service/agent-service-app/src/main/java/com/openjiuwen/service/app/config/llm/LlmConfigResolver.java`
- `third_party/agent-runtime-java/service/agent-service-app/src/main/java/com/openjiuwen/service/app/config/llm/ResolvedLlmConfig.java`
- `third_party/agent-runtime-java/service/agent-service-app/src/main/java/com/openjiuwen/service/app/config/llm/LlmProperties.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/external/RemoteA2aToolInstaller.java`

## api/core-ext.md

- `third_party/agent-solution/common/agent-core-ext-java/agent-core-ext-react-rails/src/main/java/com/openjiuwen/agents/reactrails`
- `third_party/agent-solution/common/agent-core-ext-java/agent-core-ext-react-rails/pom.xml`

## api/runtime-ext.md

- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-app/agent-service-app-custom-rest/src/main/java/com/openjiuwen/service/app/custom/rest/CustomRestProtocolAdapter.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-spec-ext/src/main/java/com/openjiuwen/service/spec/ext/skillhub/spi/SkillHubProvider.java`
- `third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/agentfw/JiuwenCoreAgentExtHandler.java`

## conventions/project-conventions.md

- `third_party/agent-solution/common/example/versatile-orchestration-demo/README.md`

## conventions/openjiuwen开发指导.md

上游：`third_party/openjiuwen开发指导.md`（2026-08-09 合并；合并时全部 API 断言已回下列源码逐条核实，编造项已重写或剔除）

- §1.1 ReActAgent new+configure：`third_party/agent-core-java/src/main/java/com/openjiuwen/core/singleagent/agents/ReActAgent.java`、`.../ReActAgentConfig.java`（configureModelClient(provider, apiKey, apiBase, modelName, verifySsl)）
- §1.2/§8 DeepAgent 装配：`third_party/agent-core-java/src/main/java/com/openjiuwen/harness/factory/HarnessFactory.java`（仅注入 general-purpose 子代理 + SecurityRail/TaskPlanningRail/TaskCompletionRail）、`.../harness/schema/config/DeepAgentConfig.java`（Lombok @Builder）、`.../harness/subagents/SubAgentConfig.java`
- §1.3 WorkflowAgent：`third_party/agent-core-java/src/main/java/com/openjiuwen/core/application/workflow/WorkflowAgent.java`（构造仅 WorkflowAgentConfig）；已验证 DSL 见 how-to/workflow-agent.md 锚点
- §2.1 服务化：`third_party/agent-runtime-java/service/agent-service-adapters/agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/autoconfigure/AgentCoreAdaptersAutoConfiguration.java`（agent-id + handler:agentcore 自动装配条件）、`.../agent-service-app/.../config/llm/ResolvedLlmConfig.java`（@Getter/@Builder 字段）、`.../config/ServiceProperties.java`
- §3.1 外部服务工厂：`.../agent-service-adapters-agentcore/.../external/AgentCoreSandboxClientFactory.java`（create()/create(serverId)/configFor()）、`AgentCoreMcpClientDecoratorFactory.java`、`AgentCoreRemoteClientFactory.java`、`DecoratingSandboxClient.java`；DecoratedSandboxToolRegistrar 在 demo（example/support），不在 jar
- §3.2 工具注册：`third_party/agent-core-java/src/main/java/com/openjiuwen/core/foundation/tool/ToolCard.java`（builder() 存在）、`.../tool/function/LocalFunction.java`（(ToolCard, Function) 构造）、`.../core/singleagent/AbilityManager.java`（add）、`.../core/runner/resourcemanager/ResourceMgr.java`（addTool(Tool, Collection<String>, boolean)）、`.../core/runner/Runner.java`（resourceMgr() 静态）
- §4 Rail：`third_party/agent-core-java/src/main/java/com/openjiuwen/core/singleagent/rail/AgentRail.java`（beforeToolCall 等返回 CompletionStage<Void>，无 RailDecision）、`.../rail/AgentCallbackContext.java`、`.../harness/rails/interrupt/BaseInterruptRail.java` + `InterruptDecision.java`（sealed）；resolveInterrupt 覆写形态以 demo `third_party/agent-runtime-java/service/agent-service-demo/example/a2a/.../A2aDelegateRail.java` 与 `.../agent_teams/rails/TeamToolApprovalRail.java` 为准（源码快照内 BaseInterruptRail 未声明该方法，以发布 jar 为准）
- §6 生命周期钩子：`third_party/agent-runtime-java/service/agent-service-spec/src/main/java/com/openjiuwen/service/spec/lifecycle/AgentInitHook.java`（onInit(AgentLifecycleContext) throws Exception）、`AgentShutdownHook.java`、`AgentLifecycleContext.java`
- §7 异构接入：`third_party/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-versatile/.../VersatileAgentHandler.java`、`.../autoconfigure/VersatileProperties.java`（ambiguousIntentId 默认 "1"，setUrlTemplate/setTimeout/setResultNodeName 存在）、`.../agent-service-spec-ext/.../skillhub/spi/SkillHubProvider.java`、`.../agent-service-adapters-agentscope/.../AgentScopeAgentHandler.java`
- §8 SubAgent 工厂：`third_party/agent-core-java/src/main/java/com/openjiuwen/harness/subagents/`（CodeAgentFactory.createCodeAgent(Object model)、ExploreAgentFactory.createExploreAgent(String, Workspace)、PlanAgentFactory.createPlanAgent(String, Workspace)、ResearchAgentFactory.createResearchAgent(Object model)、VerificationAgentFactory.createVerificationAgent(Object model)、BrowserAgentFactory.createBrowserAgent(model, tools, mcps, rails, card, language, settings)、MobileGuiAgentFactory）
- §9 存储：`third_party/agent-core-java/src/main/java/com/openjiuwen/spi/store/vector/VectorStoreFactory.java`（create(storeType, conf)）、`.../spi/store/vector/provider/`（InMemory/Milvus/PGVector/Elasticsearch/Chroma 五 Provider 均完整）、`.../spi/store/KVStoreFactory.java`、`.../extensions/store/kv/`（RedisStore/JedisClusterRedisStore）、`.../spi/store/object/`
- §10 会话持久化：`.../agent-service-adapters-common/.../middleware/MiddlewareProperties.java`（ttl-seconds 默认 604800、redis-ref、encrypted-password、memory 段字段全集）、`.../AgentCoreCheckpointerConfigAssembler.java`（仅 in_memory/redis，其他 type 抛错——无 persistence 后端）、`third_party/agent-core-java/src/main/java/com/openjiuwen/core/session/checkpointer/PersistenceCheckpointer.java` 与 `.../extensions/checkpointer/redis/RedisCheckpointer.java`（postAgentExecute 只 save 不清 key）、`.../extensions/checkpointer/redis/RedisTTLConfig.java`（refresh_on_read 默认 false）
- §11 A2A：`third_party/agent-runtime-java/service/agent-service-app/src/main/java/com/openjiuwen/service/app/controller/a2a/client/A2AAgentCardDiscovery.java`、`A2ARemoteAgentClient.java`、`A2AEnabledServeOrchestrator.java`、`.../app/config/A2AProperties.java`（remote-agents 条含 timeoutSeconds 默认 300）；delegate_to_xxx 工具命名为 demo 约定（agent-service-demo/example/a2a），非框架自动注册
- §12 记忆：`third_party/agent-core-java/src/main/java/com/openjiuwen/core/memory/external/MemoryProvider.java`（方法返回 CompletableFuture，含 initialize/prefetch/syncTurn/getToolSchemas/handleToolCall/systemPromptBlock/shutdown/onSessionEnd）、`.../agentcore/memory/MemoryStoreFactory.java` 与 `.../memory/jiuwen/JiuwenMemoryStoreProvider.java`、`.../memory/mem0/Mem0MemoryStoreProvider.java`（provider 取值 mem0/jiuwen）；MemoryToolRegistrar 在 demo（agent-service-demo/example/memory），不在 jar
