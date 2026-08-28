---
title: 安装、检查与启动
description: 建立环境、检查依赖并启动参考宿主。
audience: both
status: verified
---

# 安装、检查与启动

## 适用场景 / 不适用场景

**适用**：环境已经建好，要跑检查、起参考宿主、确认入口可达。

**不适用**：

- 新机器从空目录开始 —— 先读 [构建 Python 运行环境](build-environment.md)。
- 要部署成服务 —— 见 [部署与切换](deployment.md)。
- 要系统地验证写出来的东西 —— 见 [验证你写的 Agent](verification.md)。

## 最小装配契约

```bash
make setup
make check
make e2e
```

新机器从零创建 `.venv` 以及安装 runtime/Agent 依赖，先读 [`build-environment.md`](build-environment.md)。本页的 `make` 命令默认在当前 runtime 源仓执行，不会替交付仓自动创建虚拟环境。

Windows 等价入口是 `python tools/tasks.py check`；`make` 只是类 Unix 转调。`make e2e` 需要 Docker，含真实 LLM 的场景另行设置网关变量。

## 能力点逐个展开

### 启动参考宿主

```bash
RUNTIME_BACKEND=fixture \
RUNTIME_PORT=8090 \
uvicorn deploy.host_app:app --host 0.0.0.0 --port 8090
```

fixture 默认产出固定的 thought、tool_start、final_answer_chunk 和终答，适合检查 wire；真实 workflow 使用参考宿主自己的 `RUNTIME_BACKEND=agentcore`，并提供宿主旋钮 `RUNTIME_WORKFLOW_ID` 及对应 openjiuwen 运行环境；这些 `RUNTIME_*` 不是 runtime 的 `openjiuwen.service` 配置项。

### 启动前的导入检查

```bash
python -c 'import fastapi, httpx, yaml; import a2a.types.a2a_pb2; print("runtime imports ok")'
python -c 'from agent_runtime.bootstrap.rest_app import create_rest_app; print("REST factory import ok")'
```

如果第一条失败，优先安装完整 `agent_runtime/requirements-dev.txt`；如果第二条失败，查看完整 traceback 中的协议 SDK、protobuf 或 grpc 缺失，而不是先修改应用代码。

### 检查顺序

先跑单元测试与架构门禁，再跑目标入口的部署级 E2E。协议修改至少执行对应入口的 E2E 脚本；兼容性修改还要执行差分判据。

## 配置项参考

- **`RUNTIME_BACKEND`**（宿主环境变量）：参考宿主的执行后端档位，`fixture` 为确定性档，`agentcore` 接真实执行后端。
- **`RUNTIME_PORT` / `RUNTIME_HOST`**（宿主环境变量）：监听地址。
- **`RUNTIME_WORKFLOW_ID`**（宿主环境变量）：真实后端档下要执行的工作流标识。
- **`E2E_BACKEND`**：部署级 E2E 的运行档位。

这些 `RUNTIME_*` 都是**宿主旋钮**，不是 runtime 的 `openjiuwen.service` 配置项。

## 坑位与排错

**注意：协议 SDK 缺失表现为导入期报错，不是启动期报错。** 第一条导入检查失败时先补全依赖，不要改应用代码。

**排错：`make` 在非类 Unix 环境不可用** —— 用等价的 `python tools/tasks.py check`。

**排错：`make e2e` 失败且提示缺容器运行时** —— 部署级 E2E 需要容器；只做协议形态验收时可先跑装配门禁。

## 端到端校验

```bash
curl -fsS http://127.0.0.1:8090/health
curl -N -X POST http://127.0.0.1:8090/query -H 'content-type: application/json' \
  -d '{"message":"hello","conversation_id":"c1"}'
```

确定性档下预期看到固定的思考、工具开始与终答帧序。Agent 工程侧的门禁命令见 [验证与交付判定](verification.md)。

## API 锚点（包内符号，按依赖可查）

- `deploy/host_app.py:create_app`（参考宿主，非 SDK 的一部分）
- `agent_runtime.bootstrap.rest_app.create_rest_app`
- `agent_runtime.bootstrap.a2a_app.create_a2a_app`

## See also

- [构建 Python 运行环境](build-environment.md)
- [配置驱动装配](config-driven-agent.md)
- [部署与切换](deployment.md)
