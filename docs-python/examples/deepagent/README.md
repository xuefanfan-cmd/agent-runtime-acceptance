# DeepAgent 工程

目标导向任务循环的完整能力闭环：`TaskCompletionRail` 驱动任务循环 + 受限工作区文件工具 + 官方工厂装配。

```text
src/deepagent/agent/definition.py       # DeepAgent 构造：模型、完成判定 Rail、工作区、工具
src/deepagent/agent/workspace_tools.py  # 业务工具：工作区内的交付物清单（自带越界防护）
src/deepagent/runtime/                  # 宿主配置、运行资源登记、Handler、组合根
resources/application.yml               # 卡片元数据与技能项
```

## 三个装配要点

| 事项 | 本工程怎么做 |
|---|---|
| 构造 | 官方工厂 `create_deep_agent(model, card=…, rails=[…], workspace=…, …)` 一次完成工作区初始化、默认 Rail 注入与工具注册 |
| 文件工具 | `workspace` + `restrict_to_work_dir=True` 即挂上框架内建的读写与目录工具，业务侧只补框架没有的清单工具 |
| 资源释放 | DeepAgent 持有工作区资源，由 runtime 生命周期的停机阶段触发 Handler 的 `stop`；宿主另有清理需求时在组合根的关闭路径里显式释放 |

## 坑位

1. **模型端点在构造期校验**：provider 为 openai 时 `LLM_API_KEY`、`LLM_API_BASE` 必填；`LLM_VERIFY_SSL` 为真时还要求 `LLM_SSL_CERT`。服务层把这三条翻译成一句可操作提示。
2. **工具标识会被改写**：`create_deep_agent` 把工具标识改成 `<tool_id>_<agent_id>` 做 agent 级作用域，按原标识查找会落空。
3. **越界防护要自己做**：`restrict_to_work_dir` 约束的是框架内建工具；业务工具的路径解析必须自己挡 `..`，本工程的清单工具已带该防护并有测试。

## 装配门禁

```bash
export RUNTIME_ROOT=/path/to/agent-runtime-ext-python
PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests
```

七项：分层红线、工作区工具行为与越界拒绝、无真实凭据构造、证书缺失的失败语义、运行资源登记与执行入口、A2A 卡片技能项。

## 启动

```bash
cp deploy/.env.example deploy/.env && set -a; . deploy/.env; set +a
PYTHONPATH=src:$RUNTIME_ROOT python -m deepagent.runtime.application
```

任务循环的真实运行需要模型凭据与可写工作区，按 `docs/how-to/deepagent.md` 的端到端校验执行。
