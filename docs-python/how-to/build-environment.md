---
title: 构建 Python 运行环境
description: 从空目录创建 .venv，安装当前 runtime 与 Agent 工程依赖，并验证导入链。
audience: both
status: verified
---

# 构建 Python 运行环境

## 适用场景 / 不适用场景

在新机器、CI 或全新 checkout 上运行 `docs/examples/` 工程时，先按本页创建隔离环境。不要假定机器已经存在参考仓的 `.venv`。

**不适用**：环境已建好只想跑起来 —— 见 [安装、检查与启动](setup-and-run.md)；要部署成服务 —— 见 [部署与切换](deployment.md)。

## 最小装配契约

从空目录到能跑通门禁，需要三件事：一个 Python 3.11 以上的虚拟环境、以可编辑方式安装的 runtime、各 Agent 工程自己的依赖。

## 前置条件

- Python `>=3.11`；推荐使用当前 CI/验收使用的 Python 3.12。
- 已 checkout 当前 runtime 源码仓和本交付仓。
- A2A/REST 入口需要完整协议依赖；只安装 FastAPI 和 httpx 不够。
- 真实模型场景还需要可访问的 OpenAI 兼容网关；契约验证默认不需要模型。

## 能力点逐个展开

### 从零创建 `.venv`

`RUNTIME_ROOT` 指向 **runtime 检出**：Python 侧的 runtime 本体在 agent-solution 仓的 `common/agent-runtime-ext-python`（目录名里的 `ext` 是历史命名，不是 Java runtime 的扩展）。已经把 runtime 以可编辑方式装进环境时，各工程的测试也能跑，不必设它。

```bash
export RUNTIME_ROOT=/path/to/agent-runtime-ext-python
export DELIVERY_ROOT=/path/to/agent-runtime-acceptance-python
export AGENT_ROOT="$DELIVERY_ROOT/docs/examples/a2a"
export VENV_ROOT="$DELIVERY_ROOT/.venv"

python3 -m venv "$VENV_ROOT"
"$VENV_ROOT/bin/python" -m pip install --upgrade pip setuptools wheel
"$VENV_ROOT/bin/python" -m pip install \
  -r "$RUNTIME_ROOT/agent_runtime/requirements-dev.txt"
"$VENV_ROOT/bin/python" -m pip install --editable "$RUNTIME_ROOT"
```

`requirements-dev.txt` 会包含 runtime 的精确依赖、pytest、静态检查和测试替身；`pip install --editable` 让 `import agent_runtime` 指向当前 runtime 源码。runtime 源码仍然是只读输入，安装不会向它写业务代码。

如果只运行不需要测试工具的服务，可先安装：

```bash
"$VENV_ROOT/bin/python" -m pip install \
  -r "$RUNTIME_ROOT/agent_runtime/requirements.txt"
"$VENV_ROOT/bin/python" -m pip install --editable "$RUNTIME_ROOT"
```

但 A2A、Redis、fake remote 和 validation 测试应使用 `requirements-dev.txt`。

### 安装 Agent 工程依赖

示例目录**不各自携带依赖清单**：依赖坐标统一从 [`compatibility.md`](../compatibility.md) 的速查表读取，可复制的完整基线是[共享最小工程模板](../examples/minimal-agent-service-pyproject.toml)。装依赖时：

```bash
cd "$AGENT_ROOT"
"$VENV_ROOT/bin/python" -m pip install openjiuwen==0.1.16 openjiuwen-agent-runtime==0.1.0 \
  a2a-sdk==1.0.0 fastapi uvicorn[standard] sse-starlette httpx PyYAML pytest pytest-asyncio
export PYTHONPATH="$AGENT_ROOT/src:$RUNTIME_ROOT"
```

其他工程只需替换 `AGENT_ROOT`：

```bash
export AGENT_ROOT="$DELIVERY_ROOT/docs/examples/rest"
export PYTHONPATH="$AGENT_ROOT/src:$RUNTIME_ROOT"
```

`PYTHONPATH` 是源码 checkout 运行方式；如果 runtime 已 editable 安装，可以只保留 `"$AGENT_ROOT/src"`。validation 报告中的绝对路径只代表当时机器，不应复制到新工程。

### 装载部署环境

runtime 不自动读取 `.env`；`.env.example` 是 Agent 部署层的样例。首次运行时复制一份并按需编辑，然后由启动方装入进程：

```bash
test -f "$AGENT_ROOT/deploy/.env" || cp "$AGENT_ROOT/deploy/.env.example" "$AGENT_ROOT/deploy/.env"
set -a
. "$AGENT_ROOT/deploy/.env"
set +a
```

容器启动时可使用等价的 `--env-file "$AGENT_ROOT/deploy/.env"`。`.env` 不提交到 Git，也不在其中填写密钥。

### 导入和版本检查

```bash
"$VENV_ROOT/bin/python" - <<'PY'
import sys
import fastapi
import httpx
import a2a.types.a2a_pb2
import agent_runtime

print(sys.version)
print("fastapi", fastapi.__version__)
print("httpx", httpx.__version__)
print("agent_runtime", agent_runtime.__file__)
print("runtime imports: OK")
PY
```

如果出现 `No module named a2a`，说明使用了系统 Python 或只安装了应用依赖；检查 `which python`、`VENV_ROOT/bin/python -m pip show a2a-sdk` 和 `PYTHONPATH`。

### 运行 canonical Agent

```bash
cd "$DELIVERY_ROOT/docs/examples/a2a"
PYTHONPATH="$PWD/src:$RUNTIME_ROOT" \
  "$VENV_ROOT/bin/python" -m pytest -q tests
PYTHONPATH="$PWD/src:$RUNTIME_ROOT" \
  "$VENV_ROOT/bin/python" -m a2a_agent.runtime.application
```

REST、Interactive、Versatile 和 DeepSeek 工程的启动模块见各自 README；它们都通过当前 runtime 的公开组合根接入，不复制 runtime 实现。


### 真实模型环境

模型凭据只通过环境变量注入；`<模型网关地址>` 和 Pi provider 属于运行环境秘密与宿主配置，不写入 Git。设置方式以实际 Agent/网关约定为准；先完成本页的导入和 fixture 验证，再做真实模型调用。验证报告中标记为 `<模型名>` 的部分，若 Handler 是确定性 fixture，只证明 runtime 接线，不等于发生了真实 LLM 推理。

## 配置项参考

- **`RUNTIME_ROOT`**（本仓约定的环境变量）：runtime 源仓检出路径，各 Agent 工程用它拼 `PYTHONPATH`。
- **`PYTHONPATH`**：`src:$RUNTIME_ROOT`，让工程源码与只读 runtime 同时可导入。
- **`openjiuwen` 版本**：`0.1.16`，唯一来源是 [`compatibility.md`](../compatibility.md) 的依赖坐标速查表。

## 坑位与排错

| 现象 | 原因/处理 |
|---|---|
| `No module named a2a` | 没用 `.venv` 或未装完整 runtime 依赖 |
| `No module named agent_runtime` | 未 editable 安装且未设置 runtime 根路径 |
| REST 导入时缺 protobuf/grpc | REST 当前导入链会经过 A2A 协议类型，安装 dev 依赖 |
| 测试收集数异常少 | 检查 pytest-asyncio、可选依赖和测试路径 |
| 真实模型失败但 fixture 通过 | 检查网关、provider、模型名、凭据和网络；不要先改 runtime |

See also: [`setup-and-run.md`](setup-and-run.md)、[`../compatibility.md`](../compatibility.md)、[`../examples/overview.md`](../examples/overview.md)。

## 端到端校验

```bash
python -c 'import openjiuwen, fastapi, a2a.types.a2a_pb2; print("imports ok")'
(cd docs/examples/react && PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests)
```

两条都通过，说明环境足以跑通装配门禁。真实模型调用另需凭据。

## API 锚点（包内符号，按依赖可查）

本页不绑定公开 API。依赖基线见 [版本兼容与依赖基线](../compatibility.md)。

## See also

- [安装、检查与启动](setup-and-run.md)
- [Agent 开发路径](agent-development-path.md)
- [版本兼容与依赖基线](../compatibility.md)
