---
title: 版本兼容与依赖坐标
description: 三仓代码仓地址、生成工程推荐发布件（单一版本口径）、Java 与 Spring Boot 基线及已知版本漂移；源码 commit/tag 锚点登记于 internal/source-anchors.md
audience: both
---

# 版本兼容与上游锚点

生成 `pom.xml`、判断能力可用性时以本页为准。验证日期：**2026-08-09**。

## 版本口径（单一，避免歧义）

本页只保留**发布件口径**：各仓源码镜像的 commit / tag 属维护者回查信息，登记在
`internal/source-anchors.md`（内部文件），不在本页体现。生成工程时一律以下表为准，
不要从源码镜像反推版本。

## 代码仓与版本基线

| 产品（仓） | 代码仓地址 | 版本基线（以明确告知的发布件为准） |
| --- | --- | --- |
| agent-core-java | `https://gitcode.com/openJiuwen/agent-core-java.git` | **0.1.14.post1** |
| agent-runtime-java | `https://gitcode.com/openJiuwen/agent-runtime-java.git` | **0.1.1.post1** |
| agent-solution（扩展模块） | `https://gitcode.com/openJiuwen/agent-solution.git` | 扩展 artifact **0.1.0**；其对上游的引用已对齐上述 post 发布件 |

## 生成工程推荐发布件

agent-solution 当前对上游产品的引用已更新为 post 发布件。生成工程应保持同一版本族：

| 产品/模块 | 推荐版本 | 用途 |
| --- | --- | --- |
| agent-core-java | **0.1.14.post1** | core SDK；通常由 runtime adapter 传递引入 |
| agent-runtime-java 系列 artifact | **0.1.1.post1** | `agent-service-app`、`agent-service-adapters-agentcore` 等 runtime artifact |
| agent-solution 扩展 artifact | **0.1.0** | SkillHub、Custom REST、agentcore-ext、react-rails、versatile 等扩展 |

> ⚠️ 三仓上游 README / 示例中若出现无 `post1` 后缀的旧坐标，勿照抄——
> 以本页版本基线为准。

## 运行基线

| 项 | 版本 | 来源 |
| --- | --- | --- |
| Java | 17 | 各仓 POM |
| Spring Boot | 4.0.6 | agent-runtime-java / agent-solution POM |

## 依赖坐标速查（生成 pom 用）

| 需要的能力 | Maven 坐标 | 推荐版本 | 说明 |
| --- | --- | --- | --- |
| 服务托管骨架（任何 agent 服务必需） | `com.openjiuwen:agent-service-app` | 0.1.1.post1 | REST `/v1/query`、A2A 端点、生命周期与自动配置 |
| 本地 core agent 托管（ReAct / Workflow / DeepAgent） | `com.openjiuwen:agent-service-adapters-agentcore` | 0.1.1.post1 | 传递引入 core/runtime 基础模块，含中间件自动配置 |
| 远端 A2A 工具注入 / SkillHub | `com.openjiuwen:agent-service-adapters-agentcore-ext` | 0.1.0 | ext handler 与 SkillHub 注入链路 |
| SkillHub SPI | `com.openjiuwen:agent-service-spec-ext` | 0.1.0 | 自定义 `SkillHubProvider` 时直接使用 |
| 自定义 REST 协议入口 | `com.openjiuwen:agent-service-app-custom-rest` | 0.1.0 | `CustomRestProtocolAdapter` 桥接 |
| Versatile 工作流对接 | `com.openjiuwen:agent-service-adapters-versatile` | 0.1.0 | `VersatileAgentHandler` |
| react-rails 认知 rail | `com.openjiuwen:agent-core-ext-react-rails` | 0.1.0 | 纯 Java，无 Spring 依赖 |
| 直连 core SDK（不经 runtime 托管） | `com.openjiuwen:agent-core-java` | 0.1.14.post1 | 经 runtime adapter 传递引入时通常无需重复声明 |

> Maven 依赖片段不在每篇 how-to 中重复维护；能力页只标明 artifactId，版本统一从本表读取。

## 范围外模块坐标（当前文档消费范围外，仅供全景参考）

以下模块属于三仓全景（见 [agent-solution 技术架构](architecture/03-agent-solution技术架构.md)），
但不在本仓 how-to / 接口文档的覆盖范围内，生成典型 agent 服务工程时**不应引入**：

| Maven 坐标 | 版本 | Java | 模块归属 |
| --- | --- | --- | --- |
| `com.openjiuwen:agent-service-adapters-agentscope` | 0.1.0 | 17 | runtime-ext（AgentScope 适配） |
| `com.openjiuwen:agent-service-bus-consumer` | 0.1.0 | 17 | runtime-ext（事件总线消费，profile 激活） |
| `com.openjiuwen:agent-gateway` | 0.1.0 | 21 | agent-bus |
| `com.openjiuwen:event-bus-spi` / `event-bus-sdk` / `event-bus-testkit` / `event-bus-relay` | 0.1.0 | 17 | agent-bus |
| `com.openjiuwen:registry-discovery-center` | 0.1.0 | 21 | agent-bus |
| `com.openjiuwen:agent-client` / `agent-client-sdk-for-jvm` | 0.1.0 | 17 | agent-client |
| `com.openjiuwen:pev` / `edp-agent-java` / `edpa-alpha` / `adapter-versatile-agent-java` | 0.1.0 | 17 | agents（具体 Agent 实现，源码未完全开放） |

> 注：`agent-evolve`（evoagent / evoagent-adapter）为 Python 项目（pyproject.toml + uv），
> 无 Maven 坐标；evoagent 依赖 Python 包 `openjiuwen==0.1.13`。

## 使用注意

- 生成工程只看本页的版本基线与坐标速查表；源码实现回查（commit / tag / 镜像 POM）
  走内部维护文件 `internal/source-anchors.md`，两者不要混用。
- 版本随上游发布滚动更新；维护本页时以明确告知的发布件口径为准，同步核对三仓发布件。
