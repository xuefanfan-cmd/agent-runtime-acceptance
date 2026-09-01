# Workflow Agent 工程

DAG 编排的完整能力闭环：LLM 处理 -> 工具校验 -> 分支 -> 人工（HITL）/ 自动收尾。

```text
src/workflow_agent/agent/definition.py    # DAG 编排：Start / LLM / Tool / Branch / Questioner / End
src/workflow_agent/agent/check_tool.py    # 本地工具：风险等级判定
src/workflow_agent/runtime/               # 宿主配置、工作流注册、Handler、组合根
resources/application.yml                 # 卡片元数据与技能项
```

## 三处装配约束

1. **不用 WorkflowAgent 包装层**：`openjiuwen==0.1.16` 有 `WorkflowAgent`，但其配置类 `WorkflowAgentConfig` 位于 `openjiuwen.core.single_agent.legacy.config`，构造时框架告警该配置形态已废弃。本工程改把 Workflow 注册成**工作流资源**，由 runtime 的 `AgentCoreHandler` 探到工作流标识后走工作流执行入口——这条路径不依赖废弃配置类。DAG 编排写法不受影响。
2. **工作流注册必须在事件循环内**：编译过程使用异步原语，模块导入期注册会失败，因此注册挂在组合根的 `init_hooks` 上。
3. **工具绑定用实例**：`ToolComponent(config)` 在构造期就会向运行资源要实例，只给 `tool_id` 而工具尚未注册时构造即失败；本工程用 `bind_tool(tool)` 绑实例，把顺序耦合去掉。

## 坑位

模型端点在**构造期**校验：provider 为 openai 时 `LLM_API_KEY` 与 `LLM_API_BASE` 必填，缺任一项在装配阶段即抛错，不是到调用时才失败。服务层把它翻译成一句指向 `deploy/.env.example` 的提示。

## 装配门禁

```bash
# runtime 尚未发包，先克隆：git clone --branch common https://gitcode.com/openJiuwen/agent-solution.git
export RUNTIME_ROOT=/path/to/agent-solution/common/agent-runtime-ext-python
PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests
```

七项：分层红线、工具阈值边界、缺端点的失败语义、服务层错误提示、DAG 可构造、工作流资源登记与执行入口择取、A2A 卡片技能项。

## 启动

```bash
cp deploy/.env.example deploy/.env && set -a; . deploy/.env; set +a
PYTHONPATH=src:$RUNTIME_ROOT python -m workflow_agent.runtime.application
```

HITL 分支触发后，A2A 侧表现为 input-required；恢复路径见 `docs/how-to/workflow-agent.md`。
