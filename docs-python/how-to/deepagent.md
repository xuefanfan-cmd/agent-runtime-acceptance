---
title: DeepAgent 指南
description: DeepAgent 目标导向任务循环的创建与托管——create_deep_agent 工厂、TaskCompletionRail 完成判定、受限工作区与文件工具、与 ReAct 的行为对照
audience: ai-coding
status: verified
examples: ../examples/deepagent/
---

# DeepAgent 指南

## 适用场景 / 不适用场景

**适用**：目标明确但路径不定，需要多轮推进直到交付物就绪；产出落在文件系统上（报告、代码、配置）；需要框架提供受限工作区与文件工具。

**不适用**：

- 单轮问答或少量工具调用即可收敛 —— 用 [ReAct](react-agent.md)，任务循环的额外轮次是纯开销。
- 步骤固定 —— 用 [Workflow](workflow-agent.md)。
- 没有可写工作区的部署环境 —— 任务循环的交付物无处落盘。

## 最小完整示例

完整源码：[`docs/examples/deepagent/`](../examples/deepagent/README.md)。

语义层（`src/deepagent/agent/definition.py`）：

```python
model = Model(
    model_client_config=ModelClientConfig(
        client_provider=model_provider, api_key=api_key, api_base=api_base,
        verify_ssl=verify_ssl, ssl_cert=ssl_cert),
    model_config=ModelRequestConfig(model=model_name, temperature=0.1, top_p=0.8),
)

completion_rail = TaskCompletionRail(
    task_instruction="持续维护工作区交付物。根据当前请求创建或更新文件；当前请求如下：\n{query}",
    completion_promise="ARTIFACTS_READY",
    required_confirmations=1,
    allow_promise_details=False,
    max_rounds=3,
    timeout_seconds=300.0,
)

agent = create_deep_agent(
    model,
    card=AgentCard(id=AGENT_ID, name=AGENT_ID, description="任务循环 + 受限工作区文件工具"),
    system_prompt=SYSTEM_PROMPT,
    tools=[create_list_artifacts_tool(root)],
    rails=[completion_rail],
    enable_task_loop=True,
    max_iterations=8,
    workspace=str(root),
    restrict_to_work_dir=True,
    language="cn",
)
```

服务层与 ReAct 相同：登记进运行资源，再由 `AgentCoreHandler(agent_id, Runner)` 托管。

## 能力点逐个展开

### 任务循环与完成判定

`enable_task_loop=True` 打开任务循环；`TaskCompletionRail` 决定何时算完成：

- **`task_instruction`**：每轮注入的任务指令，`{query}` 会被当前请求替换。
- **`completion_promise`**：完成信号字面量。模型发出它、且达到 `required_confirmations` 次确认，循环才结束。
- **`max_rounds` / `timeout_seconds`**：兜底上限。两者都到不了完成信号时强制收尾，避免无限循环。

### 工作区与受限文件工具

`workspace=<path>` + `restrict_to_work_dir=True` 让框架内建的读写与目录工具（`openjiuwen.harness.tools` 的 `ReadFileTool`、`EditFileTool`、`ListDirTool`）只能作用于工作区目录内。业务侧不需要重写这组工具，只在框架没覆盖的地方补自己的工具。

**业务工具的越界防护要自己做**：`restrict_to_work_dir` 约束的是框架内建工具，自定义工具里一个 `../` 就能绕出工作区。示范工程的清单工具用解析后比较父目录的方式挡住它，并有对应测试。

### 与 ReAct 的行为对照

| 维度 | ReAct | DeepAgent |
|---|---|---|
| 结束条件 | 模型给出终答，或达到 `max_iterations` | 完成信号被确认，或 `max_rounds` / 超时兜底 |
| 状态载体 | 会话上下文 | 会话上下文 + 工作区文件 |
| 典型轮数 | 1 至数轮 | 多轮，每轮都可能改文件 |
| 工具装配 | 能力声明 + 执行体注册两步 | 经工厂 `tools=[...]` 一次装配 |

### 工具标识会被工厂改写

`create_deep_agent` 把传入工具的标识改写成 `<tool_id>_<agent_id>` 做 agent 级作用域。构造后按原标识查找会落空——如果业务代码要按标识索引工具，用工厂返回的实例上的标识。

## 配置项参考（application.yml，完整文件见示例目录）

- **`openjiuwen.service.a2a_access.skills`**：Card 技能项，本用例声明 `maintain_artifacts`。
- **`openjiuwen.service.lifecycle.shutdown_timeout_s`**：停机排水上限，默认 30。长任务循环需要评估这个值是否够一轮收尾。
- **`runtime.workspace.path`**（宿主命名空间）：工作区根，环境变量 `DEEP_WORKSPACE` 覆盖。
- **`LLM_VERIFY_SSL` / `LLM_SSL_CERT`**（环境变量）：模型端点的证书校验开关与证书路径。开启校验时证书路径必填，见坑位。
- **`LLM_API_KEY` / `LLM_API_BASE`**（环境变量）：**构造期必填**。

## 坑位与排错

**注意：模型端点有三条构造期硬约束。** provider 为 `openai` 时 `api_key`、`api_base` 必填；`verify_ssl` 为真时 `ssl_cert` 必填。三条都在 `Model` 构造时校验（它会当场创建模型客户端），不是到调用时才失败。开发环境如果没有证书，必须把 `LLM_VERIFY_SSL` **显式**置为 `false` 并接受随之而来的传输风险，而不是留空 `ssl_cert` 指望它宽松处理。

**注意：工作区目录要可写且存在。** 示范工程在构造期 `mkdir(parents=True, exist_ok=True)`；只读文件系统上任务循环会在第一次写文件时失败，而那时已经消耗了模型调用。

**注意：构造后工具标识不等于你传进去的标识。** 见上文的工厂改写规则。

**注意：DeepAgent 持有工作区资源。** 由 runtime 生命周期停机阶段触发 Handler 的 `stop` 释放。宿主另有清理需求时，在组合根的关闭路径里显式释放，不要依赖进程退出。

**排错：任务循环不收敛** —— 检查 `completion_promise` 是否在 system prompt 里被明确要求发出；`required_confirmations` 大于 1 时模型要连续确认多次。

## 端到端校验

装配门禁（占位端点 + 临时工作区，不出网）：

```bash
cd docs/examples/deepagent
export RUNTIME_ROOT=/path/to/agent-runtime-ext-python
PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests
```

预期 7 项通过，含「越界路径被拒绝」「证书缺失时给出可操作错误」两项关键判据。

启动与真实任务循环：

```bash
cp deploy/.env.example deploy/.env && set -a; . deploy/.env; set +a
PYTHONPATH=src:$RUNTIME_ROOT python -m deepagent.runtime.application
curl -s http://127.0.0.1:18092/.well-known/agent-card.json
```

预期 `name` 为 `notes-deep`、`skills[0].id` 为 `maintain_artifacts`。真实任务循环需要模型可达与可写工作区；完成后应能在工作区看到被创建或更新的 Markdown 文件。

## API 锚点（包内符号，按依赖可查）

- `openjiuwen.harness.create_deep_agent` / `DeepAgent`
- `openjiuwen.harness.rails.TaskCompletionRail`
- `openjiuwen.harness.tools.ReadFileTool` / `EditFileTool` / `ListDirTool`
- `openjiuwen.core.foundation.llm.Model` / `ModelClientConfig` / `ModelRequestConfig`
- `openjiuwen.core.foundation.tool.LocalFunction` / `ToolCard`
- `agent_runtime.adapters.outbound.agentcore.handler.AgentCoreHandler`

版本口径：`openjiuwen==0.1.16`。

## See also

- [ReAct Agent 指南](react-agent.md)
- [Workflow 编排](workflow-agent.md)
- [Tool 定义与跨类型注册](tools.md)
- [agent-core Python 接口](../api/agent-core-python.md)
