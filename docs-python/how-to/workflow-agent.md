---
title: 构建 Workflow：core DSL 编排 DAG 并托管
description: 用 agent-core 的 Workflow 组件以代码编排 LLM 结构化输出、工具校验、分支与人工审批 DAG，注册为工作流资源后经 AgentCoreHandler 暴露为 A2A 服务
audience: ai-coding
status: verified
examples: ../examples/workflow/
---

# 构建 Workflow：core DSL 编排 DAG 并托管

## 适用场景 / 不适用场景

**适用**：步骤与分支条件在设计期就确定；需要人工审批（HITL）卡在特定节点；希望每步的输入输出可枚举、可断言。

**不适用**：

- 步骤依赖模型临场判断 —— 用 [ReAct](react-agent.md)。
- 目标导向、交付物需要多轮演进 —— 用 [DeepAgent](deepagent.md)。
- 编排逻辑已在远端系统里 —— 用 [Versatile 对接](versatile-agent.md)，不要在本地重画一遍 DAG。

## 最小完整示例

完整源码：[`docs/examples/workflow/`](../examples/workflow/README.md)，DAG 形态为
`start -> transform(LLM) -> check(Tool) -> route(Branch) -> {confirm(HITL) | finish(LLM)} -> end`。

语义层关键接线（`src/workflow_agent/agent/definition.py`）：

```python
flow = Workflow(card=WorkflowCard(id=WORKFLOW_ID, name="示例流水线", version="1.0"))

flow.set_start_comp("start", Start(), inputs_schema={"query": "${query}"})
flow.add_workflow_comp("transform", LLMComponent(llm_config),
                       inputs_schema={"query": "${start.query}"})
flow.add_workflow_comp("check", ToolComponent(ToolComponentConfig()).bind_tool(tool),
                       inputs_schema={"total": "${transform.total}"})

branch = BranchComponent()
branch.add_branch('${check.data.risk} == "high"', "confirm", "high")
branch.add_branch("true", "finish", "normal")          # 兜底分支必须存在
flow.add_workflow_comp("route", branch, inputs_schema={"risk": "${check.data.risk}"})

flow.set_end_comp("end", End(), inputs_schema={
    "manual_result": "${confirm.user_response}",
    "auto_result": "${finish.text}",
})
flow.add_connection("start", "transform")   # 分支到目标的边由 BranchComponent 自路由
```

服务层（`src/workflow_agent/runtime/`）：

```python
_require_ok(Runner.resource_mgr.add_workflow(defined.card, lambda: defined.workflow), "工作流")
handler = AgentCoreHandler(WORKFLOW_ID, Runner)
app = create_a2a_app(handler, name=WORKFLOW_ID, config=load_runtime_config(),
                     init_hooks=(make_init_hook(config),))
```

## 能力点逐个展开

### 托管形态：工作流资源，而不是 WorkflowAgent 包装层

`openjiuwen==0.1.16` 提供 `WorkflowAgent`（`openjiuwen.core.application.workflow_agent.workflow_agent`），可以包住若干 Workflow 再托管。**本指南不推荐这条路**：它的配置类 `WorkflowAgentConfig` 位于 `openjiuwen.core.single_agent.legacy.config`，构造时框架主动告警「AgentConfig is deprecated and will be removed in the future」，提示改用 `AgentCard` 加新配置形态。

推荐口径是把 Workflow 注册成**工作流资源**：runtime 的 `AgentCoreHandler` 执行期问运行资源「这个标识是不是工作流」，是就走工作流执行入口，否则走通用智能体入口。装配方不需要提前声明形态，也不依赖已废弃的配置类。这条路径是当前 runtime 参考宿主与部署级 E2E 已跑通的形态。

需要一个控制器型 Agent 同时编排多条工作流时，`WorkflowAgent` 仍是可选项；选它就要接受废弃告警，并在升级 `openjiuwen` 时重新验证。

### 引用语法：`${}` 与 `{{}}` 不是一回事

- `${节点.字段}` 是**图引擎的跨节点引用**，写在 `inputs_schema` 里。
- `{{键}}` 是**组件内的模板占位**，写在 prompt 模板里，取的是该组件的局部输入键。

非 RESTful 工具的返回被框架包在 `data` 键下，所以下游引用工具结果要写 `${check.data.risk}`，不是 `${check.risk}`。

### 分支：条件求值与兜底

`BranchComponent.add_branch(condition, target, branch_id)` 按声明顺序求值，命中即路由。**兜底分支必须存在**（条件写 `true`），否则条件都不命中时流程无处可去。分支到目标的连边由组件自路由，不要再 `add_connection`。

### 人工审批（HITL）

`QuestionerComponent` 执行到时挂起并抛出中断，A2A 侧表现为 `input-required`；用户回话后从该节点续接。`response_type="reply_directly"` 表示直接把用户回复作为节点输出，不再做字段抽取。续接机制与状态边界见 [取消、中断与续接](interrupt-and-resume.md)。

### 工具绑定：绑实例，不绑标识

`ToolComponent(ToolComponentConfig(tool_id="check"))` 在**构造期**就会向运行资源要实例；工具尚未注册时，DAG 构造即失败。用 `bind_tool(tool)` 绑实例把顺序耦合去掉。

## 配置项参考（application.yml，完整文件见示例目录）

- **`openjiuwen.service.a2a_access.skills`**：Card 技能项，本用例声明 `run_pipeline`。
- **`openjiuwen.service.a2a_access.capabilities.streaming`**：流式能力位，默认 `true`。
- **`openjiuwen.service.lifecycle.shutdown_timeout_s`**：停机排水上限，默认 30。HITL 挂起的会话在排水窗口内不会被强杀。
- **`runtime.server.port`**（宿主命名空间）：监听端口，环境变量 `RUNTIME_PORT` 覆盖。
- **`LLM_API_KEY` / `LLM_API_BASE`**（环境变量）：**构造期必填**，见坑位。

## 坑位与排错

**注意：工作流注册必须在事件循环内。** 编译过程使用异步原语，模块导入期注册会失败。把注册挂进组合根的 `init_hooks`，不要在模块顶层执行。同样的坑记在当前 runtime 参考宿主 `deploy/host_app.py` 的 `_register_workflow` 注释里。

**注意：模型端点在构造期就校验。** `ModelClientConfig` 在构造期校验 provider 与端点的搭配：provider 为 `openai` 时 `api_key`、`api_base` 缺任一项直接抛 `ValidationError`，报的是「api_key is required for provider OpenAI」，不会告诉你该去哪份 `.env` 补。示范工程在服务层先做前置校验，把它翻译成一句可操作提示。

正确：

```python
missing = [n for n, v in (("LLM_API_KEY", api_key), ("LLM_API_BASE", api_base)) if not v]
if missing:
    raise ValueError(f"缺少模型端点配置 {missing}；请复制 deploy/.env.example 为 .env 后装载")
```

错误：把空端点直接传进去，等框架在装配中途抛一句上下文不足的校验错。

**注意：`ToolComponent` 只给 `tool_id` 会当场解析。** 报错文本是「tool component not bind a valid tool」，发生在构造期而不是执行期。用 `bind_tool` 或确保工具先于 DAG 构造注册。

**排错：分支都不命中** —— 缺兜底分支，或条件里引用了未加 `.data` 的工具字段。

## 端到端校验

装配门禁（用占位端点，不出网）：

```bash
cd docs/examples/workflow
export RUNTIME_ROOT=/path/to/agent-solution/common/agent-runtime-ext-python
PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests
```

预期 7 项通过，含「缺端点即失败」「工作流资源登记后 Handler 走工作流入口」两项关键判据。

启动与卡片核对：

```bash
cp deploy/.env.example deploy/.env && set -a; . deploy/.env; set +a
PYTHONPATH=src:$RUNTIME_ROOT python -m workflow_agent.runtime.application
curl -s http://127.0.0.1:18090/.well-known/agent-card.json
```

预期 `name` 为 `pipeline`、`skills[0].id` 为 `run_pipeline`。真实执行需要模型可达；风险值超阈值时应观察到 A2A Task 进入 `input-required`。

## API 锚点（包内符号，按依赖可查）

- `openjiuwen.core.workflow.Workflow` / `WorkflowCard` / `Start` / `End`
- `openjiuwen.core.workflow.LLMComponent` / `LLMCompConfig`
- `openjiuwen.core.workflow.ToolComponent` / `ToolComponentConfig`（`bind_tool`）
- `openjiuwen.core.workflow.BranchComponent`（`add_branch`）
- `openjiuwen.core.workflow.QuestionerComponent` / `QuestionerConfig`
- `openjiuwen.core.foundation.llm.ModelClientConfig` / `ModelRequestConfig`
- `openjiuwen.core.runner.Runner.resource_mgr.add_workflow` / `get_workflow`
- `agent_runtime.adapters.outbound.agentcore.handler.AgentCoreHandler`

版本口径：`openjiuwen==0.1.16`。

## See also

- [ReAct Agent 指南](react-agent.md)
- [取消、中断与续接](interrupt-and-resume.md)
- [Tool 定义与跨类型注册](tools.md)
- [agent-core Python 接口](../api/agent-core-python.md)
