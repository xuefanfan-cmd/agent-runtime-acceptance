# Interactive Agent 工程

交互式 Agent 的完整能力闭环：`agent/` 保存中断与恢复语义，`runtime/` 保存 REST 组合根和配置，`resources/` 保存运行配置，`tests/` 保存直接调用和 HTTP 验收。

```bash
export RUNTIME_ROOT=/path/to/agent-runtime-ext-python
PYTHONPATH=src:$RUNTIME_ROOT \
  python -m pytest -q tests
```

## 配置与 `.env`

`resources/application.yml` 中的 `runtime:` 是本宿主命名空间，承载宿主旋钮，不是 runtime 的 `openjiuwen.service` 配置。本工程没有从环境变量绑定 runtime 配置。runtime 使用 `OPENJIUWEN__SERVICE` 和双下划线层级，且不自动读取 `.env`；启动方应复制 `deploy/.env.example` 为 `.env` 后显式加载。
