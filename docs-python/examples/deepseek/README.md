# DeepSeek Agent 工程

模型配置型 Agent 的协议闭环，按三层组织：`agent/` 保存语义身份，`runtime/` 保存 Handler 和 REST 组合根，`resources/` 保存运行配置，`tests/` 保存服务验证。

```bash
export RUNTIME_ROOT=/path/to/agent-runtime-ext-python
PYTHONPATH=src:$RUNTIME_ROOT \
  python -m pytest -q tests
```

## 配置与 `.env`

`resources/application.yml` 中的 `runtime:` 是本宿主命名空间，承载 `RUNTIME_*` 旋钮，不是 runtime 的 `openjiuwen.service` 配置；当前 runtime 配置示例是 `openjiuwen.service.lifecycle.shutdown_timeout_s`。runtime 使用 `OPENJIUWEN__SERVICE` 和双下划线层级，本工程不从环境变量绑定 runtime 配置。runtime 不自动读取 `.env`，启动方应复制 `deploy/.env.example` 为 `.env` 后显式加载。
