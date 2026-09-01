---
title: 版本兼容与运行基线
description: 当前项目的依赖、运行前置、兼容范围和已知漂移。
audience: both
---

# 版本兼容与运行基线

生成 `pyproject.toml`、判断能力可用性时以本页为准。

## 版本口径（单一，避免歧义）

本页只保留**已安装发布件口径**：源码仓的分支与提交可能领先或滞后于已安装版本，不适用于生成工程，也不作为签名判据。生成工程与写文档结论时一律以下表为准，不要从源码仓或上游 README 反推版本。

`openjiuwen` 的源码仓 develop 分支与已安装的 `0.1.16` 之间实测存在签名漂移，这正是本条口径存在的原因。

## 交付分支的验证基线

本页记录当前交付分支在通过验证时使用的直接依赖和运行约束。新业务 Agent 默认使用 `openjiuwen` agent-core，当前项目作为 runtime 托管层接入。版本变化必须重新执行对应单测、静态检查和部署级 E2E，不能因为 API 看起来未变就放宽版本。

## 代码仓与版本基线

| 事实 | 取值 | 说明 |
|---|---|---|
| **本 runtime 的源码位置** | `https://gitcode.com/openJiuwen/agent-solution.git`，分支 `common`，路径 `common/agent-runtime-ext-python` | 这就是本文档树所讲的 runtime 本体。目录名的 `ext` 是历史命名，不是 Java runtime 的扩展，也不是 `openJiuwen/agent-runtime` 的延续。匿名可克隆 |
| 本 runtime 的包名与版本 | `openjiuwen-agent-runtime` `0.1.0` | 源码包自身的元数据。**尚未发布到任何包索引**，获取方式见下节 |
| agent-core 包与版本 | `openjiuwen` `0.1.16` | 本文档树的全部 agent-core 结论以该**已安装版本**为准，不以源码仓 develop 分支为准 |
| agent-core 源仓 | `openJiuwen/agent-core`，检出 `e1b4f5c5` | 仅作阅读参考；签名以已安装版本为准 |

**源码仓与已安装版本可能不一致**：agent-core 的 develop 分支与 `0.1.16` 之间存在签名漂移（例如工作流组件构造参数）。凡涉及签名的结论，判据是已安装版本上的实测，不是源码仓阅读。

## 直接依赖基线

| 组件 | 当前基线 | 作用 |
|---|---:|---|
| Python | >=3.11 | runtime SDK 的 `requires-python` |
| `openjiuwen` | `0.1.16` | agent-core / workflow 执行后端 |
| `a2a-sdk` | `1.0.0` | 标准 A2A wire 与 Task / EventQueue 类型 |
| `fastapi` | `0.139.2` | HTTP 应用与入口装配 |
| `uvicorn[standard]` | `0.51.0` | ASGI 运行器 |
| `sse-starlette` | `3.4.6` | 自定义 REST SSE 流 |
| `httpx` | `0.28.1` | 远端 Agent、Card、回调请求 |
| `redis` | `8.0.1` | Task、会话、缓存与总线后端 |
| `PyYAML` | `6.0.3` | 声明式配置 |
| `protobuf` | `7.36.0rc1` | A2A / 传递协议类型 |
| `pydantic` | `2.14.0a1` | FastAPI / A2A 传递依赖 |
| `starlette` | `1.3.1` | FastAPI / SSE 传递依赖 |

权威文件是 runtime 检出里的 `agent_runtime/requirements.txt` 与 `pyproject.toml`（runtime 位于 agent-solution 仓的 `common/agent-runtime-ext-python`）。

## 生成工程推荐发布件

生成新的 Agent 服务工程时保持同一版本族：

| 组件 | 推荐版本 | 用途 |
|---|---|---|
| `openjiuwen` | **0.1.16** | agent-core SDK：Agent 语义、工具、Rail、工作流 |
| `a2a-sdk` | **1.0.0** | 对外 wire 契约；换版本即换契约，必须精确锁定 |

上游 README 或其他分支的示例里若出现其他版本，不要照抄——以本页为准。

**本 runtime 不在这张表里**：它不是你要额外挑选的第三方组件，而是本文档树所讲的那个 runtime 本身，获取方式见下节。

## 安装本 runtime

本 runtime 尚未发布到 PyPI 或任何包索引，**从源码检出安装**：

```bash
git clone --branch common https://gitcode.com/openJiuwen/agent-solution.git
export RUNTIME_ROOT="$PWD/agent-solution/common/agent-runtime-ext-python"
python -m pip install -e "$RUNTIME_ROOT"
```

装完 `import agent_runtime` 即可用。要点三条：

- **不要写进工程的 `dependencies`**：`pip install openjiuwen-agent-runtime` 会失败，公共索引上没有这个包。
- **`agent-solution` 仓里还有别的模块**（`agent-bus`、`agent-client`、几个 Java 构件等），Python Agent 开发只需要 `common/agent-runtime-ext-python` 这一个路径，其余不必关心。
- **不装也能跑**：让 `PYTHONPATH` 包含 `$RUNTIME_ROOT` 同样可以 `import agent_runtime`，示例工程的装配门禁就是这么跑的。

## 依赖坐标速查（生成 pyproject 用）

> 本表只列**第三方依赖**——能从 PyPI 装到、且需要你在工程里声明的东西。本 runtime 不在其中，它的获取方式见上节「安装本 runtime」。

| 需要的能力 | 依赖项 | 推荐版本 | 说明 |
|---|---|---|---|
| Agent 语义层（任何 Agent 必需） | `openjiuwen` | 0.1.16 | ReAct / Workflow / DeepAgent、Tool、Rail、会话 |
| 标准 A2A 协议面 | `a2a-sdk` | 1.0.0 | 卡片、Task、事件类型；由托管 SDK 直接依赖 |
| HTTP 服务面 | `fastapi`、`uvicorn[standard]`、`sse-starlette` | 见依赖基线 | 应用装配、ASGI 运行器、SSE 流 |
| 出站 HTTP（远端 Agent、卡片、回调） | `httpx` | 见依赖基线 | 远端调用 |
| 状态外置（仅配了 middleware 段时） | `redis` | 8.0.1 | Task 快照、会话与缓存后端 |
| 配置载体 | `PyYAML` | 6.0.3 | `application.yml` 解析 |
| 技能注入 / Versatile / 自定义 REST | **无额外依赖** | — | 这些适配器就在 runtime 包内，靠配置段开关，不靠追加依赖 |

依赖片段不在每篇 how-to 中重复维护；能力页只说明需要哪一项，版本统一从本表读取。可复制的完整基线见[共享最小工程模板](examples/minimal-agent-service-pyproject.toml)。

## 范围外模块（当前文档消费范围外，仅供全景参考）

| 模块 | 语言 | 说明 |
|---|---|---|
| `agent-core-ext-java`（react-rails） | Java | Python 侧无对应包 |
| `agent-bus` | Java | Python runtime 只提供端口与适配位 |
| `agent-client` | Java | Python 侧无对应包 |
| `agent-evolve` | Python | 自演进引擎，当前 runtime 不承载 |

逐项边界见 [agent-solution 技术架构](architecture/03-agent-solution技术架构.md)。

## Agent 开发与 runtime 依赖矩阵

| 开发场景 | Agent 语义层 | Runtime 层 | 依赖说明 |
|---|---|---|---|
| ReAct / Workflow / DeepAgent 业务 Agent | `openjiuwen` agent-core | 当前项目 `agent_runtime` + AgentCore adapter | 首选路径；Agent 定义放 `agent/`，服务装配放 `runtime/` |
| AgentCore Agent 直接本地调试 | `openjiuwen` | 可暂不启动 runtime | 只验证 AgentCore `invoke`/`stream`，不等于服务已发布 |
| AgentCore Agent 服务化 | `openjiuwen` | `fastapi`、`a2a-sdk`、`uvicorn`、store 等 | 通过 `AgentCoreHandler` 和公开组合根接入 |
| AgentScope 等异构框架 | AgentScope | 当前项目 framework adapter | 兼容路径，不是 OpenJiuwen Agent 的首选语义层 |
| 远端 Agent / Versatile | 远端协议 | 当前项目 remote adapter | 不在本地重复实现 AgentCore |
| Runtime 协议/生命周期验收 | 确定性 fixture | 当前项目 runtime | 可不触发真实模型；报告必须标明是 fixture |

`openjiuwen` 是 Python 包名，AgentCore 能力位于 `openjiuwen.core.*` 及 `openjiuwen.harness.*`。当前 runtime 的发布依赖已经声明该包，因此完整 runtime 环境通常会安装它；这不改变职责边界：业务 Agent 仍应在 `agent/` 使用其原生 API，runtime 不应承载 Agent 推理逻辑。

## 配置来源和优先级

`ConfigLoader` 的合并顺序固定为：文件 → 机密目录 → 环境变量；环境变量最终覆盖前两者。配置文件采用 YAML / JSON 可解析文本，文件根可以是 `openjiuwen.service`；机密目录采用“一个文件一个值”，文件名用点号表达嵌套路径。

新环境变量前缀固定为 `OPENJIUWEN__SERVICE`，层级分隔符为双下划线，例如 `openjiuwen.service.lifecycle.shutdown_timeout_s` 对应 `OPENJIUWEN__SERVICE__LIFECYCLE__SHUTDOWN_TIMEOUT_S`；旧 `RUNTIME__*` 前缀仍兼容但会告警。两套前缀同时出现时新前缀胜出。字段校验失败应包含字段路径，便于部署方定位。

## 运行前置

- 契约档可以使用 `deploy/host_app.py` 的确定性 fixture，不依赖模型。
- 贯通档需要安装 `openjiuwen` 执行后端，并按宿主配置注册 Agent 或 Workflow；业务 Agent 服务化应先完成 agent-core 语义层验证。
- REST 入口虽然不直接暴露 A2A 协议，但当前导入链会经过 `a2a.types.a2a_pb2`；因此 REST 应用也必须安装与 `a2a-sdk` 匹配的协议依赖。
- A2A 应用工厂的完整路径还可能加载 `grpc`；如果选择 A2A 入口，按 `agent_runtime/requirements-dev.txt` 安装完整依赖，不要只安装 FastAPI。
- Redis 是状态外置、TaskStore、会话快照和部分总线场景的运行前置。
- 容器级 E2E 需要 Docker；真实 LLM 场景还需要 OpenAI 兼容网关凭据。
- `RUNTIME_PORT` 默认 `8090`；它是参考宿主 `deploy/host_app.py` 的监听旋钮，不是 runtime 配置项。A2A 卡片的公开 URL 应配置为调用方可达地址，不能依赖 localhost 默认值。
- 若选择存量 EDPAgent 兼容方式，必须让 `applications/a2a_service`（含 `agents.EDPAgent`）位于 `PYTHONPATH`，并让参考宿主的 `deploy` 可导入；模型变量 `LLM_BASE`、`LLM_API_KEY`、`LLM_MODEL` 与 Redis 变量 `REDIS_HOST`、`REDIS_PORT` 必须可用。
- 存量兼容方式必须不设置 `RUNTIME_BACKEND`，否则兼容宿主会跳过 `agents.EDPAgent`，改用参考宿主内建后端。默认工厂为 `agent_runtime.bootstrap.legacy_compat.host:create_app`，可用 `RUNTIME_LEGACY_APP` 覆盖；导入名默认 `agents.EDPAgent`，可用 `RUNTIME_LEGACY_AGENT` 覆盖。
- 真 EDPAgent 部署级验证由 `deploy-e2e/run-legacy-edpagent.sh` 执行，`E2E_BACKEND=local|docker|auto` 与同目录 `_backend.sh` 约定一致；`local` 在信任测试环境的宿主进程中直跑，`docker` 使用 `deploy-e2e/Dockerfile.legacy-edpagent`，两种都需要真实模型与 Redis。

推荐安装方式：

```bash
make setup
# 或在已有虚拟环境中：
python -m pip install -r agent_runtime/requirements-dev.txt
```

仅安装 `fastapi`、`httpx` 和 `sse-starlette` 不能保证当前 REST 导入链可用。

AgentCore 开发入口见 [`api/agent-core-python.md`](api/agent-core-python.md)；从空环境创建 `.venv` 和安装 runtime 的完整命令见 [`how-to/build-environment.md`](how-to/build-environment.md)。

## 已知兼容边界

1. `QueryChunk` 的成功完成由流正常结束表达，终答内容必须先作为 `final_answer_chunk` 发送；不能增加虚构的 `COMPLETED` chunk。
2. `QueryChunk` 有 `chunk`、`interrupt`、`error`、`remote_agent_output` 四类类型；`remote_agent_output` 是远端业务输出，不等同于委托发起信号。
3. 标准 A2A `TaskStatus.timestamp` 当前实现会填充时间戳，而存量某些路径不填；严格字段集客户端必须允许该可选字段。
4. JSON 数组请求体在当前实现中按 `invalid_body` 处理；存量曾有先取 `.get()` 而崩溃的路径，该缺陷不作为本版契约复刻。
5. 断连语义因入口而异：标准 A2A 客户端断连要求任务继续，某些自定义 REST 等待 / 中断路径则按其专门判据处理，不能套用一个全局规则。

## 使用注意

1. **升级 `openjiuwen` 必须重跑装配门禁**：三个语义类型工程的 `tests/` 覆盖构造、登记与卡片形态，签名漂移会在那里暴露；只跑导入检查不够。
2. **协议 SDK 不可放宽为范围约束**：`a2a-sdk` 决定对外 wire 契约，换版本即换契约。其余依赖按范围声明，确切版本由宿主锁文件决定。
3. **预发布版依赖是待整改项**：`protobuf`、`pydantic` 当前落在预发布版上，升到稳定版必须重跑部署级 E2E 与全量单测后才能改。
4. **本页是版本事实的唯一来源**：其他页面出现版本字面量时以本页为准；共享最小构建模板是唯一允许携带版本字面量的例外文件，升级时与本页同步。
