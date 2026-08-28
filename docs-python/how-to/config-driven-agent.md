---
title: 配置驱动装配：运行资源登记与配置消费链
description: runtime 与 Agent 类型无关的托管逻辑——代码构造并登记 Agent 或工作流，配置决定卡片、生命周期与状态后端；含配置来源、优先级与「配置是否真的生效」的检查方法
audience: ai-coding
status: verified
snippets: ../snippets/config-env.sh
---

# 配置驱动装配：运行资源登记与配置消费链

## 两层开发模型：core 语义层 + runtime 服务层

托管逻辑与 Agent 类型无关，分工固定为两层：

```text
core 语义层（agent-core）      代码构造 Agent 或工作流，声明能力
        ↓  运行资源登记
runtime 服务层（本 SDK）        按标识托管，配置决定卡片、生命周期与状态后端
```

**配置不构造 Agent，只选择与暴露**：Agent 由代码构造并登记，配置负责挑出已登记的标识、装配基础 Handler、发布服务。这条边界决定了下面所有配置项的作用范围。

## 适用场景 / 不适用场景

**适用**：要把一个已经构造好的 Agent 或工作流托管成服务，并用配置决定卡片元数据、生命周期参数与状态后端。

**不适用**：

- 想用配置**选择 Agent 类型或构造 Agent** —— Python 侧不支持，Agent 由代码构造后登记，配置只做选择与暴露。
- 业务前缀配置 —— runtime 只绑 `openjiuwen.service` 配置树，未声明的键会被忽略并告警，业务旋钮由宿主自己读。

## 最小装配契约

两层开发模型：**代码构造语义、配置决定暴露**。

```python
# 1. 代码构造并登记运行资源（类型相关）
Runner.resource_mgr.add_agent(card, lambda: agent)        # 或 add_workflow(card, provider)

# 2. 装配 Handler（类型无关：标识 + 执行器）
handler = AgentCoreHandler(agent_id, Runner)

# 3. 用配置暴露服务（类型无关）
config = ConfigLoader().load(RuntimeConfig,
                             sources=(ConfigSource(SourceKind.FILE, "resources/application.yml"),))
app = create_a2a_app(handler, name=agent_id, config=config)
```

`AgentCoreHandler` 不区分 Agent 形态：执行期问运行资源「这个标识是不是工作流」，是就走工作流入口，否则走通用智能体入口。装配方不必先分类。

## 能力点逐个展开

### 配置来源与优先级

加载顺序：**配置文件 -> secret 目录 -> 环境变量**，后者覆盖前者。环境变量用双下划线表达层级：

```bash
OPENJIUWEN__SERVICE__LIFECYCLE__SHUTDOWN_TIMEOUT_S=30
```

旧的 `RUNTIME__*` 前缀仍会读取并逐键告警；两者同时存在时新前缀胜出。

### 配置树的形态

```yaml
openjiuwen:
  service:
    lifecycle:
      init_fail_fast: true
      shutdown_timeout_s: 30
    credential:
      decryptor: ""
    extensions:
      example:
        impl: package.module:factory
    a2a_access:
      public_url: https://agent.example.com/a2a/
      json_rpc_path: /a2a
      description: mobile-bank
      version: 1.0.0
      capabilities:
        streaming: true
        push_notifications: false
      skills: []
      default_input_modes: [text]
      default_output_modes: [text]
```

### 凭据

secret 目录里一个文件对应一个值，路径必须最终对应已声明的配置字段。密钥不写进 YAML、不写进代码。runtime **不读 `.env` 文件**：由启动方装进进程（`set -a; . deploy/.env; set +a`，或容器的 `--env-file`）。

### 宿主命名空间与 runtime 配置树分域

`application.yml` 里的 `runtime:` 段是宿主自己的命名空间，由宿主代码读取（示范工程的 `HostConfig` 读它，环境变量再覆盖）；`openjiuwen.service:` 才是 runtime 配置根，由 `ConfigLoader` 绑定。两者不能混写。

**YAML 里不写 `${VAR}` 占位符**：`ConfigLoader` 不做插值，写了只会得到字面量字符串。密钥与端点一律走环境变量或 secret 目录。

## 配置项参考

- **`lifecycle.init_fail_fast`**：启动钩子失败时是否立即失败。
- **`lifecycle.shutdown_timeout_s`**：停机排水上限，默认 30。
- **`credential.decryptor`**：凭据解密器标识，留空即不解密。
- **`extensions.<name>.impl`**：扩展点实现的导入路径，形如 `package.module:factory`。
- **`a2a_access.*`**：卡片元数据、技能项与能力位，逐项说明见 [A2A](a2a.md)。
- **`a2a.remote_invocation.*`**：远端委派的开关与上限。
- **`runtime_db.*`**：Task 快照的数据库档，默认关闭。

## 坑位与排错

**注意：配置里有，不等于运行时生效。** `ConfigLoader` 只负责读取与绑定，消费方是各自的工厂与装配代码：

| 配置段 | 当前消费方 | 说明 |
|---|---|---|
| `lifecycle.*` | 生命周期装配 | 宿主必须把加载后的配置传进应用工厂 |
| `a2a_access.*` | `create_a2a_app` | 用于卡片、公开地址、技能与能力位；显式关键字参数优先 |
| REST 业务配置 | 宿主与 `RestChannel` | `create_rest_app` 不会自行读 `application.yml` |
| Redis / secret | 宿主的 wiring | 读到配置不等于已经建好客户端或 Store |

验证方法：沿着 `ConfigLoader -> 宿主 -> 工厂 / wiring` 检查消费链，任一环断开该配置就是死的。

**注意：状态缓存的启用判据是「配置里有没有 middleware 这一段」**，不是某个布尔开关。见 [中间件与状态缓存](middleware.md)。

**注意：登记返回值必须检查。** 运行资源对重复标识返回 `Error` 且保留先登记的实例，不抛异常。

## 端到端校验

```bash
# 1. 配置能否绑定
python -c "
from agent_runtime.bootstrap.config.loader import ConfigLoader, ConfigSource, SourceKind
from agent_runtime.bootstrap.config.runtime_config import RuntimeConfig
print(ConfigLoader().load(RuntimeConfig, sources=(ConfigSource(SourceKind.FILE, 'resources/application.yml'),)))
"
# 2. 配置有没有到达对外行为：看卡片
curl -fsS "$BASE_URL/.well-known/agent-card.json"
```

卡片里出现配置声明的技能与描述，才算这段配置真的生效。示范工程用 `test_a2a_app_assembles_with_card_skills` 把这条判据固定下来。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.bootstrap.config.loader.ConfigLoader` / `ConfigSource` / `SourceKind`
- `agent_runtime.bootstrap.config.runtime_config.RuntimeConfig`（`lifecycle` / `credential` / `extensions` / `a2a_access` / `a2a` / `runtime_db`）
- `agent_runtime.adapters.outbound.agentcore.handler.AgentCoreHandler`
- `openjiuwen.core.runner.Runner.resource_mgr`

## See also

- [安装、检查与启动](setup-and-run.md)
- [A2A 跨智能体调用机制](a2a.md)
- [中间件与状态缓存](middleware.md)
- [Runtime 公开接口](../api/agent-runtime-python.md)
