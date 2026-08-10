---
title: agent-runtime-java 接口文档
description: AgentHandler SPI、DTO 契约、agent 托管与 A2A 配置的自包含参考
audience: ai-coding
---

# agent-runtime-java 接口文档

agent-runtime-java 是 OpenJiuwen 的 **agent 托管层**：一个 Spring Boot 服务骨架，
把任意 agent（本地 core agent 或外部协议 agent）统一暴露为 REST `/v1/query` 与 A2A 端点。
接入方式只有一种：提供一个 `AgentHandler` Bean。

## 模块划分

| 模块 | 职责 |
| --- | --- |
| `agent-service-spec` | SPI 与 DTO 契约（不含实现，可单独依赖） |
| `agent-service-app` | Spring Boot 托管：controller（query / reset / probe / a2a）、orchestrator、自动配置 |
| `agent-service-adapters-agentcore` | core agent 的托管 handler（`JiuwenCoreAgentHandler`） |
| `agent-service-adapters-common` | 外部服务调用、中间件（redis 会话等）适配 |

## AgentHandler SPI（唯一接入点）

```java
package com.openjiuwen.service.spec.spi;

public interface AgentHandler {
    /** 非流式查询：返回聚合后的完整响应。 */
    QueryResponse query(ServeRequest request);

    /** 流式查询：通过 observer 逐帧回吐 QueryChunk。 */
    void streamQuery(ServeRequest request, QueryStreamObserver observer);

    /** 服务启动后回调一次（如启动 core Runner）。默认空实现。 */
    default void start() {}

    /** 服务关闭时回调。默认空实现。 */
    default void stop() {}

    /** 清除某会话的持久化状态（对应 /v1/reset）。默认空实现。 */
    default void clearSession(String conversationId) {}
}
```

绝大多数场景**不需要自己实现这个接口**：本地 core agent 用库存 handler 包装即可（见下）。

## 关键 DTO 契约

- **ServeRequest**：入站请求。核心字段 `messages`、`conversationId`、`userId`、`tenantId`、`stream`。
- **QueryResponse**：非流式聚合响应，`Object result` + `conversationId`。
  DTO 声明类型是 `Object`（库存 core handler 的典型结果是 Map 结构），自定义
  handler 或客户端不要把它当作固定 `Map<String, Object>` 契约。
- **QueryChunk**：流式帧，`type` + `data`。type 取值：

| type | 含义 | 典型场景 |
| --- | --- | --- |
| `chunk` | 中间流式帧 | LLM token 增量、过程输出 |
| `interrupt` | 中断帧（需要人工输入） | HITL 审批、远端 INPUT_REQUIRED 透传 |
| `remote_agent_output` | 远端 agent 业务输出（带来源） | A2A 委派结果回传 |
| `error` | 错误帧 | 远端/本地执行失败 |

## 托管本地 core agent：JiuwenCoreAgentHandler

`JiuwenCoreAgentHandler`（`com.openjiuwen.service.adapters.agentcore.agentfw`）是
agent-core-java 的默认 handler，内部委托 core 的 `Runner` 执行，负责把 core 侧的
交互输出（含工具调用中断）翻译为 `QueryChunk` 帧：

```java
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyAgentConfiguration {

    @Bean
    AgentHandler myAgentHandler() {
        // agent 为 core 层的 ReActAgent / WorkflowAgent / DeepAgent 实例
        return new JiuwenCoreAgentHandler(agent);
    }
}
```

> 规则：**直接包装，不子类化**。需要远端 A2A 工具注入时改用 ext 版
> `JiuwenCoreAgentExtHandler`（见 [runtime-ext 文档](runtime-ext.md)）。

## 服务配置速查

| 配置 key | 作用 |
| --- | --- |
| `spring.application.name` | 本服务 A2A 卡片的 `name` 来源；调用方若通过 `remote-agents` 自动注入本 Agent，其 `name` 必须与该值相等 |
| `openjiuwen.service.agent-id` | agent 路由标识，与代码中的 AGENT_ID 常量保持一致 |
| `openjiuwen.service.handler` | handler 类型，默认 `agentcore`；仅该值触发基础 handler 自动装配，其他值不产生 handler |
| `openjiuwen.service.query.webflux.enabled` | 启用 WebFlux 流式应答 |
| `openjiuwen.service.llm.timeout` | LLM 调用超时（秒） |
| `openjiuwen.service.middleware.*` | 中间件家族：checkpointer / redis 端点 / 长期记忆，详见 [中间件配置](../how-to/middleware.md) |
| `openjiuwen.service.a2a.streaming` | 被调方卡片能力声明「支持流式」，默认 true，通常保持开启 |
| `openjiuwen.service.a2a.remote-agents[].streaming` | 主控方调用该远端 agent 时是否消费中间流式输出，默认 false（同步）；需透传过程输出给客户端时设 true |
| `openjiuwen.service.a2a.skills[]` | 暴露给远端的 skill（`id` / `name` / `description` / `tags`） |
| `openjiuwen.service.a2a.remote-agents[]` | 声明消费的远端 agent（`name` / `url`，需 ext handler） |

> ⚠️ **远端工具注入边界**：配置项存在不代表所有 Agent 类型都能自动获得远端工具。
> 当前安装器只覆盖 BaseAgent/ReActAgent 与 DeepAgent 内部 BaseAgent；WorkflowAgent 主控需在 DAG 中显式建模 Tool/组件。详见 [A2A 指南](../how-to/a2a.md)。

> ⚠️ **配置驱动边界**：上述配置只完成 Service/Handler 装配、已注册 agent 的选择
> （`agent-id`）与运行时资源/远端工具注入——**不会仅凭 YAML 构造 Agent 实例**。
> Agent 必须先由 Java Builder / Spring Bean 等程序化入口构造注册；
> `JiuwenCoreAgentExtHandler` 同样要求传入实例，不支持只传 ID 字符串。

## LLM 配置的两种接线方式

模型接入的**最终生效点只有一个**：core 侧的
`ReActAgentConfig...configureModelClient(provider, apiKey, apiBase, modelName, sslVerify)`。
区别只在配置**来源**，两种方式不冲突、可按工程习惯选择：

| | 方式一：手动接线 | 方式二：runtime LLM 配置体系 |
| --- | --- | --- |
| 配置来源 | 自定义配置键（本工程示例用 `llm.*` + `@Value`） | `openjiuwen.service.llm.*`（`LlmProperties`） |
| 解析 | 代码自行读取后直接传给 `configureModelClient` | 注入 `LlmConfigResolver` Bean → `resolve()` 得 `ResolvedLlmConfig`（provider / apiKey / apiBase / modelName / systemPrompt / temperature / timeout / maxIterations 等），再映射进 `configureModelClient` |
| 凭证 | 自行管理 | `api-key` 经运行时 `CredentialDecryptor` 解密（可接 KMS） |
| 附加能力 | 无 | `auto-discover: true` 时可从约定路径的 api 配置文件加载（`config-file` 显式指定优先）；多 agent 共享同一份模型配置 |
| 适用 | 单 agent 小工程、希望显式直观 | 配置统一归入 `openjiuwen.service.*` 家族、密钥托管、多 agent 共享 |

> ⚠️ `LlmConfigResolver` 的解析结果**有缓存**（凭证解密可能走外部 KMS，启动期
> 一次性解析）——运行期改 `LlmProperties` 不生效；要调整 systemPrompt 等值，
> 必须在首次 `resolve()` 之前完成。

## 生命周期

handler Bean 注册后由框架驱动：init hooks → `start()` → 服务流量 → `stop()`；
`/v1/reset` 请求触发 `clearSession(conversationId)`。探针（probe）端点由
`agent-service-app` 自带，无需业务代码。

## API 锚点（jar 内类，按依赖可查）

- SPI：`com.openjiuwen.service.spec.spi.AgentHandler` / `QueryStreamObserver`
- DTO：`com.openjiuwen.service.spec.dto.*`（ServeRequest、QueryResponse、QueryChunk、HealthResponse）
- 路径常量：`com.openjiuwen.service.spec.paths.AgentServicePaths` / `A2AServicePaths`
- 托管实现：`com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler`
- LLM 配置：`com.openjiuwen.service.app.config.llm.LlmProperties` / `LlmConfigResolver` / `ResolvedLlmConfig`

## See also

- [配置驱动 Agent](../how-to/config-driven-agent.md)：YAML 装配与代码构造的边界（handler 自动装配三条件）
- [中间件配置](../how-to/middleware.md)：checkpointer / Redis / 长期记忆的 `middleware.*` 配置
- [A2A 跨智能体调用机制](../how-to/a2a.md)：a2a.* 配置的完整机制
- [WorkflowAgent 编排指南](../how-to/workflow-agent.md)：WorkflowAgent 经本 handler 托管的完整示例
- [runtime-ext 接口文档](runtime-ext.md)：外部协议 handler（versatile 等）
