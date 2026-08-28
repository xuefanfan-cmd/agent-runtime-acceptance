---
title: Rail：模型与工具调用的钩子链
description: 用 AgentRail 在推理循环的关键位置插入回调——护栏、强制收尾、异常接管；以及 runtime 提供的客户端工具轨与远端委派轨
audience: ai-coding
status: verified
snippets: ../snippets/lifecycle-hook.py
---

# Rail：模型与工具调用的钩子链

## 适用场景 / 不适用场景

**适用**：需要在 Agent 每一轮的固定位置插入横切逻辑——调用前改写输入、调用后校验输出、异常时接管、达成条件时强制收尾。

**不适用**：

- 只是给 Agent 增加一项能力 —— 那是 [Tool](tools.md)。
- 步骤与分支在设计期已确定 —— 用 [Workflow](workflow-agent.md) 的分支组件表达，比在 Rail 里写状态机清楚。
- 需要跨请求持久化 —— Rail 是请求内的钩子链，跨请求状态属于会话与状态层。

## 最小装配契约

`AgentRail` 是钩子集合，实现需要的钩子并注册到 Agent：

```python
from openjiuwen.core.single_agent.rail import AgentRail

class FinalAnswerGuardRail(AgentRail):
    priority = 10                       # 数值决定同类钩子的执行顺序

    async def after_model_call(self, inputs):
        ...                             # 校验模型输出，必要时请求强制收尾

agent.register_rail(FinalAnswerGuardRail())
```

可用钩子（`openjiuwen==0.1.16`）：`before_invoke` / `after_invoke`、`before_model_call` / `after_model_call`、`before_tool_call` / `after_tool_call`、`before_task_iteration` / `after_task_iteration`、`on_model_exception` / `on_tool_exception`，以及 `init` / `uninit` 两个生命周期钩子。

## 能力点逐个展开

### 钩子链的位置语义

- **invoke 级**：整次调用的进出口，适合做请求级的审计与上下文准备。
- **model_call 级**：每次模型调用前后，适合改写提示、校验结构化输出。
- **tool_call 级**：每次工具调用前后，适合做审批、参数脱敏、结果裁剪。
- **task_iteration 级**：任务循环的每一轮，DeepAgent 的完成判定就在这一层（`TaskCompletionRail`）。

### 强制收尾

`ForceFinishRequest` 是 rail 请求终止循环的表达方式，用于「答案已经足够好」或「越界必须停」两类判断。它由 rail 提出、由框架执行，不是 rail 自己 return 一个终答。

### 异常接管

`on_model_exception` 与 `on_tool_exception` 让 rail 能把一次失败转成可继续的状态（重试、降级、给模型一条错误说明），而不是让整次调用失败。接管不等于吞掉：无法恢复时要让异常继续传播。

### runtime 提供的两条轨

runtime 侧已经实现了两条与协议强相关的轨，装配方直接用，不要重写：

- **客户端工具轨**（`agent_runtime.adapters.outbound.agentcore.client_tool_rail`）：把「工具执行体在客户端」这件事表达成挂起与续接，并区分「本轮不是续接」与「续接对不上本次调用」。
- **远端委派占位体**（`agent_runtime.adapters.outbound.remote.delegation_rail`）：只产出委派、不直接调远端，让远端调用统一走 Task 流程。

### 工具审批与结构化追问怎么做

`openjiuwen==0.1.16` 的 `openjiuwen.core.single_agent.rail` **不导出**现成的中断 rail 与追问工具。要这类能力有两条路径：工作流用 `QuestionerComponent` 做人工审批（见 [Workflow 编排](workflow-agent.md)）；单 Agent 场景自己实现 `before_tool_call` 钩子加挂起。

## 配置项参考

Rail 没有独立配置段，它是代码装配对象。与之相关的运行配置：

- **`openjiuwen.service.lifecycle.shutdown_timeout_s`**：停机排水上限。挂起中的会话在窗口内不被强杀。
- **DeepAgent 的 `max_rounds` / `timeout_seconds`**（`TaskCompletionRail` 构造参数）：任务循环的兜底上限。

## 坑位与排错

**注意：Rail 的注册顺序与 `priority` 共同决定执行顺序。** 多条 rail 都改写模型输入时，顺序不同结果不同——顺序要显式记录，不要依赖注册代码的书写次序。

**注意：钩子里不要做阻塞 IO。** 钩子在推理循环的关键路径上，同步阻塞会拖住整条流。

**注意：DeepAgent 的默认 rail 由工厂注入。** 传 `rails=[...]` 是追加，不是替换；重复注入同类 rail 会让判定逻辑执行两次。

**排错：强制收尾不生效** —— 检查是不是在 rail 里直接 return 了结果而没有发出 `ForceFinishRequest`。

## 端到端校验

Rail 的行为要在不出网的前提下可测：构造 Agent、注册 rail、直接驱动钩子，断言它对输入输出的改写与异常路径。DeepAgent 的完成判定轨已由 `docs/examples/deepagent/tests/test_assembly.py` 的构造门禁覆盖装配面。

真实推理循环中的 rail 行为需要模型可达，按 [ReAct 指南](react-agent.md)的端到端校验执行，观察工具调用前后的钩子日志。

## API 锚点（包内符号，按依赖可查）

- `openjiuwen.core.single_agent.rail.AgentRail`（钩子面见上文）
- `openjiuwen.core.single_agent.rail.ForceFinishRequest`
- `openjiuwen.core.single_agent.legacy.agent.BaseAgent.register_rail` / `unregister_rail`
- `openjiuwen.harness.rails.TaskCompletionRail` / `TaskPlanningRail` / `HeartbeatRail`
- `agent_runtime.adapters.outbound.agentcore.client_tool_rail`
- `agent_runtime.adapters.outbound.remote.delegation_rail`

版本口径：`openjiuwen==0.1.16`。

## See also

- [Tool 定义与跨类型注册](tools.md)
- [DeepAgent 指南](deepagent.md)
- [取消、中断与续接](interrupt-and-resume.md)
- [关键技术机制总结](../architecture/05-关键技术机制总结.md)
