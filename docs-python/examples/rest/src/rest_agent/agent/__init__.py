# coding: utf-8
"""语义能力层（agent-core / Harness）。

**本工程的语义层刻意为空**：它是协议与验收闭环，语义位置由确定性替身占据，
而那个替身实现的是 runtime 的 `AgentHandler` SPI，属于服务层对象，
因此放在 `runtime/handler.py`，不放在本包——把它放进 `agent/` 会让语义层
反向依赖 runtime，违反 `docs/conventions/project-conventions.md` 的分层红线
（`agent/` 不得 import runtime 与 Web 框架）。

真实业务 Agent 的语义层写法见规范示范工程 `docs/examples/react/`，
DAG 与任务循环形态分别见 `docs/examples/workflow/`、`docs/examples/deepagent/`。
"""
