---
title: ReAct Agent 指南
description: ReActAgent 推理循环（模型自主决策工具调用）的创建、工具两步注册与 runtime 托管——语义层构造、服务层登记、Handler 按标识择取执行入口
audience: ai-coding
status: verified
examples: ../examples/react/
---

# ReAct Agent 指南

## 适用场景 / 不适用场景

**适用**：任务边界开放，需要模型自己决定「要不要调工具、调哪个、调几次」；工具数量有限且都能同步给出结果；单轮内可收敛。

**不适用**：

- 步骤固定、分支条件明确 —— 用 [WorkflowAgent](workflow-agent.md)，DAG 比让模型每轮重新推理更省 token、更可预测。
- 目标导向的长任务、需要工作区文件持续演进 —— 用 [DeepAgent](deepagent.md)。
- 远端已有成品工作流，只想把它包成 Agent —— 用 [Versatile 对接](versatile-agent.md)。
- 只想验证 runtime 协议接线、不需要真实模型 —— 用 `docs/examples/a2a/`、`docs/examples/rest/` 的确定性替身。

## 最小完整示例

完整源码：[`docs/examples/react/`](../examples/react/README.md)。以下只摘录两层的关键接线。

语义层（`src/react_agent/agent/definition.py`）—— 只依赖 `openjiuwen`，构造期不出网：

```python
config = ReActAgentConfig(
    model_name=model_name,
    model_provider=model_provider,
    api_key=api_key,
    api_base=api_base,
    prompt_template=[{"role": "system", "content": SYSTEM_PROMPT}],
    max_iterations=6,
    model_config_obj=ModelRequestConfig(model=model_name, temperature=0.1, top_p=0.8),
)
card = AgentCard(id=AGENT_ID, name=AGENT_ID, description="ReAct 推理循环 + 本地工具调用")
agent = ReActAgent(card=card).configure(config)

tool = create_text_stats_tool()
agent.ability_manager.add(tool.card)     # 语义层只声明能力
```

服务层（`src/react_agent/runtime/configuration.py`）—— 登记运行资源并托管：

```python
_require_ok(Runner.resource_mgr.add_tool(defined.tool, skip_if_exists=True), "工具")
_require_ok(Runner.resource_mgr.add_agent(defined.card, lambda: defined.agent), "Agent")
handler = AgentCoreHandler(defined.agent_id, Runner)   # 位置参数：标识 + 执行器
```

组合根（`src/react_agent/runtime/application.py`）：

```python
app = create_a2a_app(handler, name=AGENT_ID, config=load_runtime_config())
```

## 能力点逐个展开

### 工具的两步注册

一个工具要在 ReAct 循环里被调用，需要两处登记，缺一不可：

- **能力声明**：`agent.ability_manager.add(tool.card)` —— 只放 ToolCard 元数据，属语义层，决定模型「知道有这个工具」。
- **执行体注册**：`Runner.resource_mgr.add_tool(tool)` —— 属服务层运行资源，决定框架「能真的把它跑起来」。

只做前者，模型会选中工具但执行期找不到实现；只做后者，模型根本不知道有它。

### 工具函数的形参必须与 schema 对齐

`LocalFunction.invoke` 以 `func(**inputs)` 调用执行函数，因此函数形参名必须与 `ToolCard.input_params` 的属性名逐字一致：

```python
input_params={"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}

def execute(text: str = "") -> dict[str, Any]:   # 形参名 text 与 schema 对齐
    ...
```

### Agent 实例的登记形态

`AgentCoreHandler` 持有的是「标识 + 执行器」，不是实例；执行期按标识向运行资源要实例。所以实例必须先登记：

```python
Runner.resource_mgr.add_agent(card, lambda: agent)
```

provider 是**零参可调用**：运行资源以 `resource_provider()` 取实例。类型注解写的是 `Callable[[AgentCard], BaseAgent]`，按注解写成 `lambda c: agent` 会在解析时报「missing 1 required positional argument」。

### 模型参数的两种写法

`ReActAgentConfig` 同时接受扁平字段（`model_name` / `model_provider` / `api_key` / `api_base`）与 `model_config_obj` 采样参数对象。本指南用扁平字段声明后端、用 `ModelRequestConfig` 声明采样参数。**扁平字段在构造期不校验端点**，这是 ReAct 与 Workflow / DeepAgent 的重要差异（见坑位）。

## 配置项参考（application.yml，完整文件见示例目录）

- **`openjiuwen.service.a2a_access.skills`**：Card 声明的技能项。无技能的 Card 不应被远端工具安装链当作可调用工具集合，需要被别的 Agent 发现时必须配。
- **`openjiuwen.service.a2a_access.capabilities.streaming`**：是否声明流式能力。默认 `true`。
- **`openjiuwen.service.a2a_access.public_url`**：卡片对外声明的可达地址。留空即按请求地址推导，不是缺省成 localhost。
- **`openjiuwen.service.lifecycle.shutdown_timeout_s`**：停机排水上限，默认 30。
- **`runtime.server.host` / `runtime.server.port`**（宿主命名空间）：监听地址，由 `HostConfig` 读取，环境变量 `RUNTIME_HOST` / `RUNTIME_PORT` 覆盖。
- **`LLM_API_KEY` / `LLM_API_BASE` / `LLM_MODEL` / `LLM_PROVIDER`**（环境变量）：模型端点。凭据不写进 YAML。

配置取值顺序：默认值 < `resources/application.yml` 的 `runtime:` 段 < 环境变量。`openjiuwen.service` 段由 runtime 的 `ConfigLoader` 绑定，**不参与占位符插值**——写 `${LLM_API_KEY}` 只会得到字面量字符串。

## 坑位与排错

**注意：重复登记不会抛异常，但第二个实例不会生效。** 运行资源对重复标识返回 `Error` 对象并只在框架日志里记一条，先登记的实例继续服务。不检查返回值就会出现「改了配置重新装配、跑的还是旧实例」。

正确：

```python
result = Runner.resource_mgr.add_agent(card, lambda: agent)
if isinstance(result, Error):
    raise RuntimeError("标识已被占用，本次构造的实例不会生效")
```

错误：

```python
Runner.resource_mgr.add_agent(card, lambda: agent)   # 返回值被丢弃，偏差静默发生
```

**注意：ReAct 的扁平模型字段在构造期不校验端点。** 空 `api_key` 也能构造成功，失败推迟到第一次真实调用。这与 Workflow / DeepAgent 相反（那两者用 `ModelClientConfig`，构造期即校验）。因此 ReAct 工程的装配门禁能在完全没有凭据的环境里跑通，但**装配通过不等于凭据齐备**。

**注意：语义层不得 import runtime。** `agent/` 只依赖 agent-core 与标准库。把实现 `AgentHandler` 的替身放进 `agent/` 是最常见的越界方式——那是服务层对象，应放 `runtime/handler.py`。`docs/examples/react/tests/test_assembly.py` 用 AST 机械守护这条红线。

**排错：模型选中了工具但报找不到实现** —— 漏了 `Runner.resource_mgr.add_tool`。

**排错：`<lambda>() missing 1 required positional argument`** —— provider 写成了带参形式，改成零参 `lambda: agent`。

## 端到端校验

装配门禁（不需要凭据）：

```bash
cd docs/examples/react
export RUNTIME_ROOT=/path/to/agent-solution/common/agent-runtime-ext-python
PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests
```

预期 7 项通过，覆盖分层红线、工具执行体、无凭据构造、配置取值顺序、登记幂等与重复登记语义、Handler 择取执行入口、A2A 卡片技能项。

启动与真实对话：

```bash
cp deploy/.env.example deploy/.env    # 填 LLM_API_KEY / LLM_API_BASE
set -a; . deploy/.env; set +a
PYTHONPATH=src:$RUNTIME_ROOT python -m react_agent.runtime.application
curl -s http://127.0.0.1:18091/.well-known/agent-card.json | head -20
```

预期卡片 `name` 为 `notes-react`，`skills[0].id` 为 `analyze_text`。真实推理循环还需要模型可达；模型不可达时表现为调用期报错，不是启动期。

## API 锚点（包内符号，按依赖可查）

- `openjiuwen.core.single_agent.AgentCard` / `ReActAgent` / `ReActAgentConfig`
- `openjiuwen.core.foundation.llm.ModelRequestConfig`
- `openjiuwen.core.foundation.tool.LocalFunction` / `ToolCard`
- `openjiuwen.core.runner.Runner.resource_mgr.add_tool` / `add_agent` / `get_agent`
- `agent_runtime.adapters.outbound.agentcore.handler.AgentCoreHandler`
- `agent_runtime.bootstrap.a2a_app.create_a2a_app`

版本口径：`openjiuwen==0.1.16`。

## See also

- [Agent 开发路径](agent-development-path.md)
- [Tool 定义与跨类型注册](tools.md)
- [WorkflowAgent 编排](workflow-agent.md)
- [DeepAgent 任务循环](deepagent.md)
- [agent-core Python 接口](../api/agent-core-python.md)
