---
title: Tool 定义与跨 Agent 类型注册
description: 用 ToolCard 与 LocalFunction 定义工具，并按 ReAct、DeepAgent、Workflow 的不同执行模型正确装配
audience: ai-coding
status: verified
examples:
  - examples/react
  - examples/deepagent
  - examples/workflow
---

# Tool 定义与跨 Agent 类型注册

OpenJiuwen 的本地工具以 `ToolCard` 描述语义契约，以 `Tool`/`LocalFunction` 提供执行体。三类 Agent 共用这组基础类型，但**注册位置不同**；生成代码时不能把 ReAct 的两步注册机械套到 DeepAgent 或 Workflow。

## 适用场景 / 不适用场景

| | 说明 |
| --- | --- |
| ✅ 适用 | 为 ReActAgent、DeepAgent 或 Workflow 增加本地 Java 工具 |
| ✅ 适用 | 统一约束工具名称、输入 Schema、执行结果和注册边界 |
| ❌ 不适用 | 注入远端 A2A Agent——见 [A2A 指南](a2a.md) |
| ❌ 不适用 | MCP、Sandbox 客户端的创建与治理——见对应 runtime 指南 |

## 最小装配契约

工具类放在 `agent/` 语义层，不依赖 Spring：

```java
ToolCard card = ToolCard.builder()
        .id("text_stats").name("text_stats")
        .description("统计文本")
        .inputParams(Map.of(
                "type", "object",
                "properties", Map.of("text", Map.of("type", "string")),
                "required", List.of("text")))
        .build();
Tool tool = new LocalFunction(card, inputs -> Map.of("length",
        String.valueOf(inputs.getOrDefault("text", "")).length()));
```

完整实现分别见 [ReAct 示例](../examples/react/)、[DeepAgent 示例](../examples/deepagent/) 与 [Workflow 示例](../examples/workflow/)。

## 能力点逐个展开

### 1. ToolCard 是给模型和编排器看的契约

- `id` 与 `name` 使用稳定、唯一、可读的蛇形命名；不要在运行时随机生成。
- `description` 同时说明能力、调用时机和限制，避免只复述工具名。
- `inputParams` 是 `Map<String, Object>`，框架原样透传；最小可移植集合为 `type/properties/required`。`enum/oneOf/$ref` 等高级关键字取决于目标模型端支持。
- 执行体必须校验必填值、类型、路径边界和外部系统错误；不要假设模型参数永远正确。

### 2. 按 Agent 类型选择装配方式

| Agent 类型 | 元数据进入模型/图的位置 | 执行体进入运行链的位置 |
| --- | --- | --- |
| ReActAgent | `agent.getAbilityManager().add(tool.getCard())` | `Runner.resourceMgr().addTool(tool, List.of(agentId), true)` |
| DeepAgent | `DeepAgentConfig.tools(List<Object>)` | `HarnessFactory.createDeepAgent(...)` 创建时统一装配 |
| Workflow | `new ToolComponent(...).bindTool(tool)` | ToolComponent 作为 DAG 节点直接执行 |

ReAct 的第一步属于 core 语义定义，第二步属于程序级运行资源注册；代表性目录分层见 [examples/react](../examples/react/)。

### 3. 结果契约要利于后续节点和模型消费

优先返回结构化 `Map`，键名稳定；错误也应返回可判断的状态或抛出明确异常。Workflow 下游引用 ToolComponent 输出时注意组件包装层，例如示例使用 `${check.data.risk}`。

## 配置项参考

Tool 本身通常没有统一 YAML；地址、超时、凭据等应用参数由 runtime 配置读取后传给工具构造器。凭据使用环境变量占位，不写入 ToolCard 描述或日志。远端工具、SkillHub、Sandbox 等配置见各自 how-to。

## 坑位与排错

> ⚠️ **ReAct 只注册 ToolCard**：模型能选择工具，但 ResourceMgr 找不到执行体。两步注册必须成对，并使用同一 `AgentCard.id` 作为绑定标识。

> ⚠️ **把 ReAct 注册方式套给 DeepAgent**：DeepAgent 由 HarnessFactory 统一装配，工具放入 `DeepAgentConfig.tools(...)`；不要在创建后假设它暴露 BaseAgent 的注册 API。

> ⚠️ **Schema 与执行函数不一致**：Schema 声明字符串，执行体却强转数字，会在真实模型调用时失败。对 ToolCard 和执行函数做同一组参数测试。

## 端到端校验

1. 使用推荐发布件执行 `mvn compile`，确认 Tool/Agent 类型和方法签名正确。
2. 对执行函数做纯 Java单测：合法输入、缺字段、错误类型、外部系统失败各一例。
3. ReAct 触发一次明确需要工具的请求，确认模型可见且执行体被调用；DeepAgent 检查工厂装配后的工具清单；Workflow 运行经过 ToolComponent 的分支并断言 `data` 输出。

## API 锚点（jar 内类，按依赖可查）

- `com.openjiuwen.core.foundation.tool.Tool`
- `com.openjiuwen.core.foundation.tool.ToolCard`
- `com.openjiuwen.core.foundation.tool.function.LocalFunction`
- `com.openjiuwen.core.singleagent.AbilityManager`
- `com.openjiuwen.core.runner.Runner` / `com.openjiuwen.core.runner.resourcemanager.ResourceMgr`
- `com.openjiuwen.harness.schema.config.DeepAgentConfig`
- `com.openjiuwen.core.workflow.component.tool.ToolComponent` / `ToolComponentConfig`

## See also

- [ReAct Agent 指南](react-agent.md)
- [DeepAgent 指南](deepagent.md)
- [WorkflowAgent 指南](workflow-agent.md)
- [Agent Rail 与工具中断](rails.md)
