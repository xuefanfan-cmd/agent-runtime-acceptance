# A2A Agent 工程

标准 A2A Agent 的协议闭环，按 `agent/`、`runtime/`、`resources/` 三层组织。

- `src/a2a_agent/agent/`：AgentHandler 语义实现与领域输出。
- `src/a2a_agent/runtime/`：A2A 组合根、启动、配置和 TaskStore。
- `resources/`：运行配置，不存放 Python 业务代码。
- `tests/`：协议和生命周期测试。

启动：

```bash
# runtime 尚未发包，先克隆：git clone --branch common https://gitcode.com/openJiuwen/agent-solution.git
export RUNTIME_ROOT=/path/to/agent-solution/common/agent-runtime-ext-python
PYTHONPATH=src:$RUNTIME_ROOT \
  python -m a2a_agent.runtime.application
```

此工程复用当前 runtime 的 `create_a2a_app`，不修改 runtime 源码。

## 配置与 `.env`

`resources/application.yml` 中的 `runtime:` 是本宿主命名空间，承载 `RUNTIME_*` 旋钮，不是 runtime 的 `openjiuwen.service` 配置。runtime 使用 `OPENJIUWEN__SERVICE` 和双下划线层级；本工程目前不从环境变量绑定 runtime 配置。runtime 不自动读取 `.env`，启动方应复制 `deploy/.env.example` 为 `.env` 后显式加载。
