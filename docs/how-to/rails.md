---
title: Agent Rail 与工具中断拦截
description: 用 AgentRail 实现执行回调，用 BaseInterruptRail 或内建 AskUserTool/AskUserRail 建立工具审批与结构化追问
audience: ai-coding
status: verified
snippets:
  - snippets/custom-rail.java
  - snippets/tool-interrupt-rail.java
  - snippets/ask-user-interrupt.java
---

# Agent Rail 与工具中断拦截

Rail 是 core Agent 执行链上的横切回调，适合护栏、观测、重试、受控收尾和工具审批。普通回调继承 `AgentRail`；需要暂停并等待外部输入时继承 `BaseInterruptRail`。Rail 不是 Tool，也不是 runtime `AgentHandler`。

## 适用场景 / 不适用场景

| | 说明 |
| --- | --- |
| ✅ 适用 | 在模型或工具调用前后记录、校验、重试、引导或提前结束 |
| ✅ 适用 | 高风险工具执行前暂停，恢复后批准或拒绝本次调用 |
| ✅ 适用 | 信息不足时由模型调用 `ask_user`，暂停当前执行点并发出结构化追问 |
| ❌ 不适用 | 实现业务动作本身——应写 Tool |
| ❌ 不适用 | 改写 HTTP/A2A 协议——应写或复用 runtime adapter/handler |
| ❌ 不适用 | WorkflowAgent DAG 节点拦截——WorkflowAgent 不走 AgentRail 回调链 |

## 最小装配契约

普通护栏复制 [custom-rail.java](../snippets/custom-rail.java)；工具审批复制 [tool-interrupt-rail.java](../snippets/tool-interrupt-rail.java)；模型主动追问复制 [ask-user-interrupt.java](../snippets/ask-user-interrupt.java)。注册入口按 Agent 类型区分：

```java
// ReActAgent / BaseAgent
reactAgent.registerRail(new FinalAnswerGuardRail(List.of("敏感词")));

// DeepAgent：必须在 HarnessFactory.createDeepAgent(...) 前写入配置
DeepAgentConfig config = DeepAgentConfig.builder()
        .rails(List.of(new ConfirmToolExecutionRail()))
        .build();
```

DeepAgent 本身不是 BaseAgent 子类，没有 `registerRail`；工厂会把配置中的 Rail 装配到内部执行 Agent。

## 能力点逐个展开

### 1. AgentRail 回调面

推荐发布件的回调均返回 `void`：

- invoke：`beforeInvoke` / `afterInvoke`；
- 模型：`beforeModelCall` / `afterModelCall` / `onModelException`；
- 工具：`beforeToolCall` / `afterToolCall` / `onToolException`；
- 生命周期：`init` / `uninit`。

`AgentCallbackContext.getInputs()` 随事件类型变化；例如模型事件可转为 `ModelCallInputs`。控制方法包括 `requestForceFinish(Map)`、`requestRetry(delaySeconds)` 与 `pushSteering(message)`。

### 2. TaskIterationRail 是另一套接口

DeepAgent 任务循环的 `com.openjiuwen.harness.rails.TaskIterationRail` 不继承 `AgentRail`。推荐发布件只提供 `afterTaskIteration(TaskIterationContext)` 默认方法。不要生成不存在的 `AgentRail.beforeTaskIteration` 或 `AgentRail.afterTaskIteration` 覆写。

### 3. BaseInterruptRail 的三种决策

`resolveInterrupt(context, toolCall, resumeInput)` 返回 `InterruptDecision`：

- `interrupt(InterruptRequest)`：首次命中工具时暂停并携带提示/上下文；
- `approve()` / `approve(value)`：恢复后放行工具；
- `reject(value)`：恢复后拒绝，并把替代结果送回执行链。

`ToolCall.getArguments()` 返回 JSON 字符串，不是 `Map`；若要读取字段，显式选用项目已有 JSON 库解析。最小片段直接把原始字符串放入中断上下文，避免引入隐式依赖。


<a id="ask-user-interrupt"></a>
### 4. AskUserTool + AskUserRail：模型主动发起结构化追问

推荐发布件已经提供 `ask_user` 的工具和中断 Rail，**不要为通用追问重新实现一个
`BaseInterruptRail` 子类**：

```java
AskUserBinding askUser = AskUserInterruptSupport.attach(reactAgent, "cn");

// runtime/：与其他 ReAct 工具一样注册执行体
Runner.resourceMgr().addTool(askUser.tool(), List.of(askUser.agentId()), true);
```

`AskUserInterruptSupport` 的完整类见
[ask-user-interrupt.java](../snippets/ask-user-interrupt.java)。它使用推荐发布件中的：

- `com.openjiuwen.harness.rails.interrupt.AskUserTool`：向模型暴露名称为 `ask_user` 的
  ToolCard；框架通过 ToolCard 提供 `questions` 输入契约；
- `com.openjiuwen.harness.rails.interrupt.AskUserRail`：首次工具调用生成
  `AskUserRequest`，恢复后把人工答案作为该次工具调用的结果送回 Agent 继续推理；
- `AskUserTool` 与 `AskUserRail` 必须成对装配。ReAct/BaseAgent 仍遵守 Tool 的两步注册：
  语义层把 ToolCard 加入 AbilityManager，runtime 层把执行体加入 ResourceMgr。

System prompt 只需说明业务触发条件，例如“缺少必要信息时调用 `ask_user`，不要自行猜测”；
不要再定义一个同名 `ask_user` Tool，也不要复制框架内部的 questions Schema。

DeepAgent 不使用上述 BaseAgent helper，而是在工厂创建前把两个内建对象同时写入配置：

```java
AskUserTool askUserTool = new AskUserTool("cn");
DeepAgentConfig config = DeepAgentConfig.builder()
        .tools(List.of(askUserTool))
        .rails(List.of(new AskUserRail()))
        .build();
```

#### 普通多轮澄清与中断追问如何选择

不要固定把任一方案标成无条件“推荐”。先判断调用方是要“下一轮重新推理”，还是要“保留当前待决执行点并恢复”。

| 目标 | 选择 |
| --- | --- |
| 本轮先返回一句自然语言问题，下一轮基于同一 `conversation_id` 重新推理 | 普通多轮澄清 |
| 保留当前待决工具调用，以结构化中断帧等待外部输入后从执行点继续 | `AskUserTool` + `AskUserRail` |
| core SDK 直连 Runner，调用方能够提交 `InteractiveInput` | 优先使用 AskUser 中断；可按 `toolCallId` 提交结构化回答 |
| 使用推荐 runtime 的公共 REST `/v1/query` | 两种方式都可闭环；恢复仍提交同一 `conversation_id` + 下一条 `message` |

> **`/v1/query` 不需要独立 `resume` 接口。** runtime 用 `conversation_id` 选择 Core
> Session。若该 Session 没有待决 `ToolInterruptionState`，本次 `message` 作为普通
> `UserMessage` 进入会话上下文；若存在待决工具中断，推荐版 ReAct 内核会把同一条 String
> 自动归一化为 `InteractiveInput`，按待决 `toolCallId` 交给 `BaseInterruptRail` /
> `AskUserRail`，原执行链从中断点继续。adapter 本身只传递 String，转换发生在 Core，
> 因此不能只检查请求 DTO 或 adapter 就判断恢复不成立。
>
> ⚠️ **边界**：同一 Session 的待决工具中断会优先消费下一条消息；若要放弃中断并开始新
> 问题，应结束或重置原 Session，而不是继续复用该 `conversation_id`。当一次中断包含多个
> 待决工具时，普通 String 会被应用到全部待决 `toolCallId`；若各工具需要不同回答，应由
> SDK 调用方提交结构化 `InteractiveInput`。当前公共 REST `QueryRequest` 没有公开
> `toolCallId → answer` 的结构化字段。

## 配置项参考

Rail 通常由 Java 代码装配，没有统一 YAML。高风险工具名清单、关键词、阈值等可从应用配置读取后传给构造器；不要让 Rail 自己读取 Spring 环境。中断跨请求/跨进程恢复依赖 checkpointer，见 [中间件指南](middleware.md)。

## 坑位与排错

> ⚠️ **使用旧版 CompletionStage 签名**：推荐发布件的 AgentRail 回调返回 `void`。按其他源码快照生成 `CompletionStage<Void>` 会编译失败。

> ⚠️ **对 DeepAgent 调 registerRail**：DeepAgent 没有该方法；用 `DeepAgentConfig.rails(...)` 并交给 `HarnessFactory`。

> ⚠️ **把 arguments 当 Map**：`toolCall.getArguments().get("x")` 无法编译；它返回字符串。

> ⚠️ **把中断只写在 Handler**：Handler 层 if-else 无法复用 Agent 的待决中断与恢复语义，应把工具级决策放到 BaseInterruptRail。

> ⚠️ **导入快照中的同名类**：推荐发布 jar 中应使用
> `com.openjiuwen.harness.rails.interrupt.AskUserTool`；不要导入源码快照中的
> `com.openjiuwen.harness.tools.AskUserTool`，后者不在推荐发布件中。

## 端到端校验

1. 使用 `agent-core-java` 推荐版本对三个 Java snippet 执行 `mvn compile`。
2. 普通 Rail：构造一个终态 `AssistantMessage`，断言命中规则时 context 收到 force-finish 请求。
3. 中断 Rail：首次传 `resumeInput=null` 断言得到 `InterruptResult`；以 `approve` 恢复得到 `ApproveResult`；其他值得到 `RejectResult`。
4. AskUser：断言 ToolCard 名称为 `ask_user`，首次调用得到携带 questions 的
   `AskUserRequest`，以字符串或 `AskUserPayload` 恢复后得到工具结果并继续推理。
5. 服务化场景启用 checkpointer：先触发 `type=interrupt`，再以同一
   `conversation_id` 和下一条 `message` 恢复，确认从待决工具调用继续且不会重新执行前序副作用；
   另校验换用新 `conversation_id` 不会误恢复原 Session。

## API 锚点（jar 内类，按依赖可查）

- `com.openjiuwen.core.singleagent.rail.AgentRail`
- `com.openjiuwen.core.singleagent.rail.AgentCallbackContext`
- `com.openjiuwen.core.singleagent.rail.ModelCallInputs`
- `com.openjiuwen.harness.rails.TaskIterationRail`
- `com.openjiuwen.harness.rails.interrupt.BaseInterruptRail`
- `com.openjiuwen.harness.rails.interrupt.InterruptDecision`
- `com.openjiuwen.harness.rails.interrupt.AskUserTool`
- `com.openjiuwen.harness.rails.interrupt.AskUserRail`
- `com.openjiuwen.harness.rails.interrupt.AskUserPayload`
- `com.openjiuwen.core.singleagent.interrupt.InterruptRequest`
- `com.openjiuwen.core.singleagent.interrupt.AskUserRequest`
- `com.openjiuwen.core.session.interaction.InteractiveInput`
- `com.openjiuwen.core.foundation.llm.schema.ToolCall`

## See also

- [Tool 定义与跨 Agent 类型注册](tools.md)
- [DeepAgent 指南](deepagent.md)
- [中间件配置](middleware.md)
- [agent-core-java 接口文档](../api/agent-core-java.md)

