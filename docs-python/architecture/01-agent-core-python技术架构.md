---
title: agent-core Python 技术架构
description: openjiuwen 包的顶层结构、三种 Agent 形态、执行入口与运行资源、Rail 钩子链、会话与原生流，以及它与 runtime 的接触面
audience: both
---

# agent-core Python 技术架构

## 一、工程定位

`openjiuwen` 是 Python 侧的 Agent 执行核心：Agent 语义、模型调用、工具、Rail、工作流编排、会话与记忆都在这一层。它**不承载服务化**——没有 HTTP 入口、没有协议投影、没有 Task 状态机，这些是 runtime 的职责。

版本口径：本页事实取自已安装的 `openjiuwen==0.1.16`。升级后模块路径与签名可能变化，必须重做导入检查与装配门禁。

## 二、顶层包结构

| 包 | 承载 |
|---|---|
| `openjiuwen.core.single_agent` | 单 Agent：`AgentCard`、`ReActAgent`、`ReActAgentConfig`、能力管理、`rail/`、`skills/`、`interrupt/`、`legacy/` |
| `openjiuwen.core.workflow` | 工作流引擎：`Workflow`、组件（LLM、工具、分支、追问、循环、HTTP、知识检索）、条件与连接 |
| `openjiuwen.core.foundation` | 基础设施：`llm`（模型客户端与请求配置）、`tool`（`ToolCard`、`LocalFunction`、`RestfulApi`） |
| `openjiuwen.core.runner` | 执行入口与运行资源管理：`Runner`、`resources_manager` |
| `openjiuwen.core.session` | 会话、状态与原生流（`OutputSchema`） |
| `openjiuwen.core.graph` | 图执行引擎，工作流的底座 |
| `openjiuwen.core.memory` / `context_engine` / `retrieval` | 记忆、上下文工程与检索 |
| `openjiuwen.core.sys_operation` | 系统操作面，含沙箱注册与工作目录 |
| `openjiuwen.core.multi_agent` / `operator` / `security` / `application` | 多 Agent、算子、安全护栏、应用层控制器（含 `workflow_agent`） |
| `openjiuwen.harness` | DeepAgent 及其设施：`deep_agent`、`factory`、`rails/`、`tools/`、`workspace/`、`subagents/`、`task_loop/` |
| `openjiuwen.extensions` / `agent_teams` / `agent_evolving` / `auto_harness` / `dev_tools` | 扩展与上层能力，当前 runtime 不承载 |

## 三、三种 Agent 形态

| 形态 | 构造入口 | 结束条件 | 状态载体 |
|---|---|---|---|
| ReAct | `ReActAgent(card).configure(ReActAgentConfig(...))` | 模型给出终答或达到 `max_iterations` | 会话上下文 |
| 工作流 | `Workflow(card=...)` 逐节点编排，或 `WorkflowAgent` 控制器包装 | DAG 走到终点组件 | 节点输入输出 + 会话 |
| DeepAgent | `create_deep_agent(model, card=..., rails=[...], workspace=...)` | 完成信号被确认，或轮次与超时兜底 | 会话上下文 + 工作区文件 |

`WorkflowAgent` 位于 `core.application.workflow_agent.workflow_agent`，但其配置类 `WorkflowAgentConfig` 在 `core.single_agent.legacy.config`，构造时框架告警该配置形态已废弃。托管单条工作流时更稳妥的形态是把工作流登记为运行资源，见 [Workflow 指南](../how-to/workflow-agent.md)。

## 四、执行入口与运行资源

`Runner` 同时是执行入口与运行资源的持有者：

```text
Runner.resource_mgr.add_tool / add_agent / add_workflow      ← 登记（provider 为零参可调用）
Runner.resource_mgr.get_agent / get_workflow                 ← 按标识解析（协程）
Runner.run_agent / run_agent_streaming                       ← 通用智能体执行入口
Runner.run_workflow / run_workflow_streaming                 ← 工作流执行入口
```

**登记结果是 `Ok` / `Error` 对象而不是异常**：重复标识返回 `Error`，先登记的实例继续服务。装配层必须检查返回值，否则会出现「改了配置重新装配、跑的还是旧实例」。

工具的元数据与执行体分属两处：`ability_manager.add(tool.card)` 让 Agent 知道有这项能力，`resource_mgr.add_tool(tool)` 让框架能真的执行它。

## 五、Rail 钩子链

`AgentRail`（`core.single_agent.rail`）在推理循环的固定位置提供钩子：invoke 级、model_call 级、tool_call 级、task_iteration 级，外加模型与工具的异常钩子和 `init` / `uninit`。`priority` 决定同类钩子的执行顺序。

`ForceFinishRequest` 是 rail 请求终止循环的表达方式——由 rail 提出、框架执行，不是 rail 自己返回终答。

harness 侧的 `TaskCompletionRail`、`TaskPlanningRail`、`HeartbeatRail` 是 DeepAgent 任务循环的判定与节奏机制，属同一钩子体系。

0.1.16 未导出现成的中断 rail 与追问工具；工具审批与结构化追问的做法见 [Rail 指南](../how-to/rails.md)。

## 六、会话与原生流

会话由 `core.session` 承载，`create_agent_session` 创建；执行产出的是 `OutputSchema` 原生流帧。**原生帧不能直接对外**：它是框架私有结构，字段随版本演进，直接返回给客户端等于把内部结构变成对外契约。

## 七、与 runtime 的接触面

两层的接触面只有三处，其余互不感知：

```text
1. 运行资源登记      Runner.resource_mgr.add_agent / add_workflow / add_tool
2. 执行入口          Runner.run_agent_streaming / run_workflow_streaming
3. 输出转换          OutputSchema  ->  QueryChunk（runtime 的 stream_adapter）
```

runtime 的 `AgentCoreHandler` 就站在这三处之上：它持有「标识 + 执行器」，执行期按资源登记形态择取入口，把原生帧转成领域块，并统一处理会话、终答、错误、中断与清理。

## 八、限制与待补

- **模型端点在构造期校验**：`ModelClientConfig` 要求 provider 与 `api_key` / `api_base` 匹配，开启证书校验时还要证书路径。ReAct 的扁平模型字段不走这条校验，两者行为不同。
- **DeepAgent 工厂会改写工具标识**为 `<tool_id>_<agent_id>`，按原标识索引会落空。
- **`extensions` / `agent_teams` / `agent_evolving` / `auto_harness` 当前 runtime 不承载**：它们存在于 agent-core，但没有对应的托管接线，不要据此宣称 runtime 支持这些能力。

## See also

- [agent-core Python 接口](../api/agent-core-python.md)
- [Agent Runtime Python 技术架构总览](00-OpenJiuwen技术架构总览.md)
- [协作与扩展体系](04-协作与扩展体系.md)
