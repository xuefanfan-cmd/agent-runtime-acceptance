---
title: Agent 源码用例（examples）
description: 完整 Agent 源码用例——每个目录覆盖一种 Agent 类型或协议闭环的语义层、服务层、资源与装配门禁；不重复携带构建配置，共用一份最小工程模板
audience: ai-coding
---

# Agent 源码用例（完整源码集）

本目录只存放**完整源码集**：每个目录是一种 Agent 类型或协议闭环的能力闭环，包含语义层、服务层、资源配置与装配门禁，可作为新工程的源码起点。

它们不是独立发布件：「完整」指 Python 与 YAML 接线不省略，并不表示目录自身携带发布与打包配置。规则：

1. **必须是真实且可运行的源码**：含完整 import 与类型声明，禁止「略」「# ...」式省略；维护时必须跑通该目录的装配门禁。
2. **类型或协议专属**：一个目录演示一个闭环，命名中性化，不含业务逻辑。
3. **不放机制片段**：类型无关的装配片段与叠加能力增量在 [../snippets/](../snippets/overview.md)——那里是单文件片段，不是完整工程。
4. **被引用而存在**：每个目录至少被一篇 how-to 引用；正文只摘录关键接线，完整代码以本目录为唯一来源，避免文档与代码双副本漂移。
5. **依赖说明**：各目录不各自携带发布配置；共享一份已验证的最小工程模板（见下节），版本坐标唯一来源是 [../compatibility.md](../compatibility.md)。

## 复制到标准工程的目录约定

生成新的应用工程时**必须**按「语义层 / 服务层 / 资源层」落盘。`react/` 是目录结构的规范示范：

```text
src/<business_package>/agent/       ← Agent 语义能力层：定义、Tool、Rail、Workflow DAG；不依赖 runtime 与 Web 框架
src/<business_package>/runtime/     ← 程序级服务层：宿主配置、运行资源注册、Handler、协议托管、组合根
resources/                          ← 资源配置层：application.yml 及模型、A2A、状态相关配置
deploy/.env.example                 ← 部署环境样例，不含密钥
tests/                              ← 装配门禁
```

本目录的示例**不携带 `pyproject.toml` 与依赖清单**：生成新工程时从[共享最小工程模板](minimal-agent-service-pyproject.toml)复制一份，版本坐标从 [compatibility.md](../compatibility.md) 读。示例目录里跑门禁用 `PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests`，不需要先安装成包。

这三个一级职责边界是强约束；`agent/` 与 `runtime/` 内部的二级包**不是固定脚手架**。不要机械生成空的 `tool/`、`rail/` 目录，也不要把所有模块长期堆在一级包。按业务内聚性选择：

- **按业务场景纵向组织**：同一场景中共同演进的 Agent 定义、Tool、Rail 放进 `agent/expense/` 一类业务子包。Tool 只是 Agent 对业务能力的语义适配入口；被它调用的领域服务、客户端仍可留在既有业务架构里，不必为了分层全部迁入 `agent/`。
- **按能力类型横向组织**：跨场景复用或同类实现较多时，用 `agent/tool/`、`agent/rail/`。
- **保持最小结构**：只有一个定义与一个工具的最小工程可以直接放在 `agent/` 根包；一旦同层模块开始增长就拆子包。
- **自定义 REST 同样按内聚性组织**：它始终属于 `runtime/`，二级目录可按协议放 `runtime/protocol/rest/`，也可按业务场景放。不得把协议适配、DTO 放进 `agent/`。

### 命名约定

模块与符号按职责命名，一眼能看出它属于哪一层：

| 位置 | 命名 | 职责 |
|---|---|---|
| `agent/definition.py` | `create(...)` 与 `Defined<X>` 数据类 | 语义层构造入口；只有真正承担参数化重复构造时才叫 `Factory` |
| `agent/<tool_name>.py` | `create_<tool>_tool()` 与纯函数 `execute()` | 工具的卡片与执行体 |
| `runtime/application.py` | `build_app()` / `main()` | 组合根与服务入口 |
| `runtime/configuration.py` | `HostConfig`、`register_resources()`、`build_handler()` | 宿主配置、运行资源登记、Handler 装配 |

语义层的配置用纯数据类，不反向依赖服务层设施；宿主旋钮统一收在 `HostConfig`，与 runtime 配置树分域。

复制或重命名后必须同步修改：包名与模块内的相对导入、从共享模板复制来的 `pyproject.toml` 的 `name` 与打包范围、`resources/application.yml` 的 Agent 标识与技能项、`deploy/.env.example` 的端口与模型旋钮。

### 分层红线（可执行守护）

`agent/` 只依赖 `openjiuwen` agent-core、业务纯 Python 类型与标准库，不得 import `agent_runtime`、FastAPI、Starlette、Uvicorn 或 a2a-sdk；`runtime/` 可以依赖 `agent/`，反向依赖禁止；`resources/` 不放 Python 业务代码。

这条红线不靠自觉：三个语义类型工程的 `tests/test_assembly.py::test_semantic_layer_does_not_depend_on_runtime` 用 AST 检查逐个模块的 import，越界即失败。

## 共享最小工程模板

[minimal-agent-service-pyproject.toml](minimal-agent-service-pyproject.toml) 是三类语义 Agent 工程共享的工程基线。复制为目标工程的 `pyproject.toml` 后改三处：`name`、打包范围、入口模块。

它固化了四个容易推错的构建契约：agent-core 与 runtime SDK 的版本配对、协议 SDK 精确锁定（换版本即换对外 wire 契约）、状态外置依赖是可选组（不配缓存段就不需要）、测试依赖与运行依赖分离。

```bash
python -m venv .venv && . .venv/bin/activate
pip install -e ".[test]"
python -m pytest -q tests
```

> **装配通过不等于环境就绪。** 装配门禁验证类型、导入闭包、分层红线、运行资源登记与协议卡片形态；真实运行还依赖模型凭据、网络与状态后端，按对应 how-to 的「端到端校验」执行。

## 示例索引

**语义类型闭环**——一个目录一种 Agent 类型，可作为新业务 Agent 的源码起点：

| 目录 | 能力闭环 | 引用它的 how-to | 装配门禁 |
|---|---|---|---|
| [react/](react/README.md) | ReActAgent：推理循环 + 本地工具两步注册；**目录结构规范示范** | [../how-to/react-agent.md](../how-to/react-agent.md) | 7 项通过 |
| [workflow/](workflow/README.md) | Workflow DAG：LLM 结构化输出 -> 工具校验 -> 分支 -> 人工/自动收尾 | [../how-to/workflow-agent.md](../how-to/workflow-agent.md) | 7 项通过 |
| [deepagent/](deepagent/README.md) | DeepAgent：完成判定 Rail 任务循环 + 受限工作区文件工具 | [../how-to/deepagent.md](../how-to/deepagent.md) | 7 项通过 |
| [versatile/](versatile/README.md) | 远端 versatile 工作流包成 Agent，含 fake remote 与探针 | [../how-to/versatile-agent.md](../how-to/versatile-agent.md) | 18 项通过 |

**协议闭环**——Python 侧特有，语义位置由确定性替身占据，用于证明 runtime 接线与协议形态：

| 目录 | 验收对象 | 引用它的 how-to | 装配门禁 |
|---|---|---|---|
| [a2a/](a2a/README.md) | A2A 协议、卡片与 TaskStore | [../how-to/a2a.md](../how-to/a2a.md) | 33 项通过 |
| [rest/](rest/README.md) | REST / SSE wire 与会话状态 | [../how-to/custom-rest.md](../how-to/custom-rest.md) | 12 项通过 |
| [interactive/](interactive/README.md) | 中断与续接（input-required 到 completed） | [../how-to/interrupt-and-resume.md](../how-to/interrupt-and-resume.md) | 11 项通过 |
| [deepseek/](deepseek/README.md) | 模型配置形态与 REST 宿主装配 | [../how-to/setup-and-run.md](../how-to/setup-and-run.md) | 12 项通过 |

后四个目录的 `agent/` 包**刻意为空**：它们的语义替身实现的是 runtime 的 `AgentHandler` SPI，属服务层对象，放在 `runtime/handler.py`。把它放进 `agent/` 会让语义层反向依赖 runtime，越过分层红线。

## 目录到依赖的映射

| 目录 | 需要的依赖 | 说明 |
|---|---|---|
| react / workflow / deepagent | 基线依赖 | agent-core + runtime SDK + 协议 SDK |
| versatile | 基线依赖 | 远端适配在 runtime SDK 内，无额外包 |
| a2a / rest / interactive / deepseek | 基线依赖 | 协议闭环，确定性替身不需要 agent-core 执行后端 |
| 叠加：状态外置 | 追加 `redis` 可选组 | 仅配了 `openjiuwen.service.middleware` 段时需要 |
| 叠加：技能注入 | 无额外包 | 技能中心客户端在 runtime SDK 内，见 [skillhub.md](../how-to/skillhub.md) |

版本坐标的唯一来源是 [compatibility.md](../compatibility.md)。

## 门禁分层

三级门禁，各证明不同的事：

| 级别 | 命令 | 证明什么 |
|---|---|---|
| 装配门禁 | `PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests` | 类型、导入闭包、分层红线、运行资源登记、协议卡片形态 |
| 启动门禁 | `python -m <package>.runtime.application` | 组合根与 web 栈就绪 |
| 真实模型 | 各 how-to 的「端到端校验」 | 需要模型凭据与网络，不进测试套件 |

装配门禁**不需要模型凭据**：ReAct 用扁平模型字段，构造期不校验端点；Workflow 与 DeepAgent 的模型客户端配置在构造期校验 `api_key` / `api_base`（DeepAgent 开启证书校验时还要证书路径），测试用占位端点满足它，不出网。

## 宿主接入方式

| 方式 | 选择条件 | 接入形态 |
|---|---|---|
| SDK 方式（默认） | 新工程、真实 agent-core Agent，或宿主已有自己的处理器 | 实现 `AgentHandler`，或用 `AgentCoreHandler(agent_id, Runner)` 托管已登记的实例，再注入公开入口工厂 |
| 存量兼容方式 | 原有存量应用代码必须原位运行 | `python -m agent_runtime.bootstrap.legacy_compat`；这是迁移过渡形态，见 [部署与切换](../how-to/deployment.md) |

## 配置与 `.env`

runtime 配置的环境变量前缀是 `OPENJIUWEN__SERVICE`，层级用双下划线。各工程的 `RUNTIME_*`、`LLM_*`、`DEEP_WORKSPACE` 是宿主自己的旋钮，不是 runtime 配置项；`resources/application.yml` 的 `runtime:` 同样是宿主命名空间，由各工程的 `HostConfig` 读取，环境变量再覆盖。runtime 配置只能放在声明过的 `openjiuwen.service` 下，未声明的键会被忽略并告警。

`application.yml` 里**不写 `${VAR}` 占位符**：配置加载器不做插值，写了只会得到字面量字符串。密钥与端点一律走环境变量。

runtime 不自动加载 `.env`。每个工程的 `deploy/.env.example` 只提供部署样例；启动前复制为 `.env`，由 shell 的 `set -a; . deploy/.env; set +a` 或容器 `--env-file` 显式装载。`.env` 被 Git 忽略，样例不得填写密钥。

