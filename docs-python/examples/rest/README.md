# REST Agent 工程

REST / SSE Agent 的协议闭环，按 `agent/`、`runtime/`、`resources/` 三层组织。

- `src/rest_agent/agent/`：Handler 语义实现。
- `src/rest_agent/runtime/`：REST 组合根、启动、配置和 session state。
- `resources/application.yml`：运行配置。
- `tests/`：REST/SSE wire 验证。

```bash
# runtime 尚未发包，先克隆：git clone --branch common https://gitcode.com/openJiuwen/agent-solution.git
export RUNTIME_ROOT=/path/to/agent-solution/common/agent-runtime-ext-python
PYTHONPATH=src:$RUNTIME_ROOT \
  python -m rest_agent.runtime.application
```

## 配置与 `.env`

`resources/application.yml` 中的 `runtime:` 是本宿主命名空间，承载 `RUNTIME_*` 旋钮，不是 runtime 的 `openjiuwen.service` 配置。runtime 使用 `OPENJIUWEN__SERVICE` 和双下划线层级；本工程目前不从环境变量绑定 runtime 配置。runtime 不自动读取 `.env`，启动方应复制 `deploy/.env.example` 为 `.env` 后显式加载。
