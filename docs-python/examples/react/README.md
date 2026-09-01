# ReAct Agent 工程（目录结构规范示范）

ReAct Agent 的完整能力闭环，也是本仓的**目录结构规范示范**：新业务 Agent 从这里复制起步。

```text
src/react_agent/agent/       # 语义能力层：AgentCard、prompt、模型参数、Tool 元数据
src/react_agent/runtime/     # 程序级服务层：宿主配置、运行资源注册、Handler、组合根
resources/application.yml    # 资源配置层：宿主旋钮与 runtime 配置树分域
deploy/.env.example          # 部署样例，不含密钥
tests/                       # 装配门禁
```

## 两条容易写错的装配要点

1. **Handler 持有的是标识，不是实例**：`AgentCoreHandler(agent_id, Runner)` 执行期按标识向运行资源要实例，所以实例必须先经 `Runner.resource_mgr.add_agent(card, provider)` 登记，且 provider 是**零参可调用**。
2. **业务旋钮不走 runtime 配置树**：runtime 只绑 `openjiuwen.service` 配置树，未声明的键会被忽略并告警。模型端点、端口这类宿主旋钮由 `HostConfig` 读 YAML 的 `runtime:` 段与环境变量，两个命名空间分开。

## 分层红线

`agent/` 只依赖 `openjiuwen` agent-core 与标准库，不得 import `agent_runtime`、FastAPI、Uvicorn 或 a2a-sdk；`runtime/` 可以依赖 `agent/`，反向依赖禁止。这条红线由 `tests/test_assembly.py::test_semantic_layer_does_not_depend_on_runtime` 机械守护。

## 装配门禁

验证类型、导入闭包、分层红线、运行资源登记与协议形态，不需要模型凭据：

```bash
# runtime 尚未发包，先克隆：git clone --branch common https://gitcode.com/openJiuwen/agent-solution.git
export RUNTIME_ROOT=/path/to/agent-solution/common/agent-runtime-ext-python
PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests
```

覆盖分层红线、工具执行体、无凭据构造、运行资源登记、Handler 择取执行入口、A2A 卡片技能项六项。

## 启动

```bash
cp deploy/.env.example deploy/.env    # 填入 LLM_API_KEY / LLM_API_BASE
set -a; . deploy/.env; set +a
PYTHONPATH=src:$RUNTIME_ROOT python -m react_agent.runtime.application
```

启动成功只证明装配与 web 栈就绪；真实推理循环还需要模型凭据与网络，按 `docs/how-to/react-agent.md` 的端到端校验执行。

## 配置与 `.env`

`resources/application.yml` 的 `runtime:` 是宿主命名空间，`openjiuwen.service:` 才是 runtime 配置根（经 `ConfigLoader` 装入，本工程用它承载 A2A 卡片元数据与技能项）。runtime 不自动读取 `.env`，启动方显式装载。本文件不写 `${VAR}` 占位符——Python 侧的 `ConfigLoader` 不做插值，写了只会得到字面量。
