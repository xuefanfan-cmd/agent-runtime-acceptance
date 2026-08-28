---
title: Tool 定义与跨 Agent 类型注册
description: 用 ToolCard 与 LocalFunction 定义工具，并按 ReAct、Workflow、DeepAgent 三种执行模型正确装配；含由 runtime 发起的客户端工具回传
audience: ai-coding
status: verified
examples: ../../docs/examples/react/、../../docs/examples/workflow/、../../docs/examples/deepagent/
snippets: ../snippets/client-tool-outcome.py
---

# Tool 定义与跨 Agent 类型注册

## 适用场景 / 不适用场景

**适用**：给 Agent 增加一项确定性能力——查询、计算、读写、调用内部服务。工具是 Agent 对业务能力的语义适配入口。

**不适用**：

- 能力本身是一个完整 Agent —— 用 [Versatile 对接](versatile-agent.md)或 A2A 远端调用。
- 需要在模型每轮之间插钩子（审批、护栏、强制收尾）—— 那是 [Rail](rails.md)，不是工具。
- 工具执行体在客户端而不是服务端 —— 见下文「客户端工具」。

## 最小装配契约

工具由两部分构成，缺一不可：

```python
card = ToolCard(
    id="text_stats", name="text_stats",
    description="统计输入文本的字符数、词数与行数",
    input_params={"type": "object",
                  "properties": {"text": {"type": "string", "description": "待统计的文本"}},
                  "required": ["text"]},
)

def execute(text: str = "") -> dict[str, Any]:      # 形参名必须与 schema 属性名一致
    return {"chars": len(text), "words": len(text.split()), "lines": len(text.splitlines())}

tool = LocalFunction(card=card, func=execute)
```

`LocalFunction.invoke` 以 `func(**inputs)` 调用执行函数，所以形参名与 `input_params` 的属性名必须逐字对齐；对不上时报的是缺参数，不是 schema 校验错。

## 能力点逐个展开

### 三种类型的装配方式不同

| Agent 类型 | 装配方式 | 关键点 |
|---|---|---|
| ReAct | `agent.ability_manager.add(tool.card)` + `Runner.resource_mgr.add_tool(tool)` | 两步缺一：只加卡片则执行期找不到实现，只注册执行体则模型不知道它存在 |
| Workflow | `ToolComponent(ToolComponentConfig()).bind_tool(tool)` | 绑实例。只给 `tool_id` 会在**构造期**向运行资源要实例，工具未注册时 DAG 构造即失败 |
| DeepAgent | `create_deep_agent(..., tools=[tool])` | 工厂一次装配；工具标识会被改写成 `<tool_id>_<agent_id>` 做 agent 级作用域 |

### 工具返回值的引用形态

工作流里，非 RESTful 工具的返回被框架包在 `data` 键下，下游要写 `${check.data.risk}`。ReAct 则把工具返回直接交回推理循环，无此包装。

### 重复注册的语义

`Runner.resource_mgr.add_tool` 默认拒绝重复标识并返回 `Error` 对象——**不抛异常**，先注册的实例继续服务。无状态工具用 `skip_if_exists=True` 让重复注册成为幂等空操作；绑定到特定实例的有状态工具用 `refresh=True` 让重启能重新绑定。返回值必须检查。

### 客户端工具：执行体在调用方

有一类工具的执行体不在服务端，而在客户端：runtime 产生工具请求、挂起会话，客户端执行后回传结果，会话据此续接。它由 runtime 侧的请求级轨承载（`agent_runtime.adapters.outbound.agentcore.client_tool_rail`），装配方要保证：

- 每次调用有可关联的调用标识；
- 「本轮不是续接」与「续接对不上本次调用」必须分开处理——后者要拒绝，对它挂起会让客户端反复收到同一请求形成循环；
- 工具失败要成为结构化结果，不能吞掉异常后返回成功。

验收维度：正常结果、参数错误、工具超时、重复回传、用户取消、模型决定不调用工具；流式入口还要确认工具事件与终答的顺序。

## 配置项参考

- **`ToolCard.id`**：工具标识，模型选工具与运行资源登记共用。
- **`ToolCard.input_params`**：JSON Schema，决定框架如何把模型给的参数转成函数入参。
- **`ToolCard.stateless`**：是否无状态，影响重复注册策略的选择。
- **`add_tool(skip_if_exists=)` / `add_tool(refresh=)`**：重复标识的两种处理策略。

## 坑位与排错

**注意：工具注册返回值不能丢。** 三种类型都可能因标识撞车而静默失效。

**排错：模型选中工具但执行期报找不到实现** —— ReAct 漏了 `Runner.resource_mgr.add_tool`。

**排错：工作流构造期报「tool component not bind a valid tool」** —— 只给了 `tool_id` 而工具未注册；改用 `bind_tool(tool)`。

**排错：DeepAgent 里按原标识找不到工具** —— 工厂改写了标识，用工厂返回实例上的标识。

**排错：工具收不到参数** —— 执行函数形参名与 schema 属性名不一致。

客户端工具结果的结构化回传形态见 [`snippets/client-tool-outcome.py`](../snippets/client-tool-outcome.py)。

## 端到端校验

三个语义类型工程的装配门禁各自覆盖本页的装配路径：

```bash
for p in react workflow deepagent; do
  (cd docs/examples/$p && PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests)
done
```

工具执行体本身应有纯函数级测试（不经模型）：`docs/examples/react/tests/test_assembly.py::test_tool_executes_without_model`、`docs/examples/workflow/tests/test_assembly.py::test_check_tool_risk_boundary`。

## API 锚点（包内符号，按依赖可查）

- `openjiuwen.core.foundation.tool.ToolCard` / `LocalFunction` / `RestfulApi`
- `openjiuwen.core.runner.Runner.resource_mgr.add_tool`
- `openjiuwen.core.single_agent.ability_manager.AbilityManager.add` / `list`
- `openjiuwen.core.workflow.ToolComponent.bind_tool`
- `agent_runtime.adapters.outbound.agentcore.client_tool` / `client_tool_rail`

版本口径：`openjiuwen==0.1.16`。

## See also

- [ReAct Agent 指南](react-agent.md)
- [Workflow 编排](workflow-agent.md)
- [DeepAgent 指南](deepagent.md)
- [Rail：模型与工具调用的钩子链](rails.md)
