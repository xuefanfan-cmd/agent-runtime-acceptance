---
title: Python Agent Runtime 项目规范
description: 分层、依赖、组合根、配置、测试、示例和契约变更的强制约定。
audience: ai-coding
status: verified
---

# OpenJiuwen Python Agent 项目规范

## 分层与依赖方向

职责顺序固定：业务 Agent 先在 agent-core 语义层实现，再由 runtime 层托管为服务；adapter 用于承接扩展协议与异构框架。runtime 不是业务 Agent 的替代实现。

```text
solution / agents  ->  可运行业务 Agent、业务装配
        ↓
ext / adapters      ->  agent-core 扩展、异构框架和远端协议适配
        ↓
runtime             ->  AgentHandler、REST/A2A、Task/Session、生命周期
        ↓
agent-core          ->  ReActAgent、WorkflowAgent、DeepAgent、Workflow、Tool、Rail
```

新业务 Agent 的默认路径是：`agent-core → runtime AgentCore adapter → REST/A2A`。只有以下情况才直接实现 runtime Handler：纯 runtime 契约验证、接入非 OpenJiuwen 引擎、远端 Agent/协议适配，或开发 runtime 本身。

runtime 内部仍采用 ports-and-adapters/洋葱边界：

```text
domain <- ports <- application <- adapters <- bootstrap/deploy
```

| 层 | 可以依赖 | 不可以依赖 | 责任 |
|---|---|---|---|
| `domain/` | Python 标准库、领域类型 | FastAPI、A2A SDK、agent-core、Redis | 请求、结果、状态、值对象 |
| `ports/` | domain、typing | 具体框架和协议库 | Handler、store、bus、session 协议 |
| `application/` | domain、ports | HTTP wire、框架私有对象 | 编排、状态、批次、消费用例 |
| `adapters/` | domain、ports、必要 application | 其他 sibling adapter | 协议/框架/后端转换 |
| `bootstrap/` | 全部内部层 | 业务逻辑散落 | 组合根、生命周期、配置接线 |
| `deploy/` | bootstrap、公开端口 | 复制 runtime 实现 | 宿主、依赖注入、启动命令 |

禁止 `domain` import `fastapi`；禁止 A2A adapter import REST channel；禁止 Handler 直接构造 Redis client；禁止 deploy 复制一套私有执行链路。

### agent-core 与 runtime 的职责边界

| 问题 | 应放置的位置 | 不应放置的位置 |
|---|---|---|
| Agent 身份、Prompt、模型配置、Workflow DAG | `src/<package>/agent/`，调用 `openjiuwen.core` | `runtime/` 的协议处理函数 |
| Tool、Rail、SubAgent、上下文和记忆语义 | `agent/` 或其业务子包 | REST router、A2A event mapper |
| Agent 实例构造、Runner 注册、Handler Bean/对象 | `src/<package>/runtime/` | agent-core 领域定义中反向 import runtime |
| REST/A2A、Task/Session、取消、生命周期 | 当前 runtime 的公开工厂/端口 | Agent 定义中手工拼 wire 响应 |
| 外部框架或远端协议 | `runtime` 的 outbound adapter / ext | 修改 agent-core 或让 application 识别原生事件 |

`agent/` 默认不得导入 FastAPI、A2A SDK、Redis 或当前 runtime 的内部模块；`runtime/` 可以依赖 `agent/` 和 runtime 公开端口，依赖方向不能反过来。这两个一级职责边界是强约束，语言层面不强制，靠规范与门禁守。

## 生成工程目录约定

```text
agent_runtime/
  domain/       # QueryChunk, ServeRequest, Task/value semantics
  ports/        # AgentHandler, stores, bus, lifecycle contracts
  application/  # ServeOrchestrator, remote batch, bus consume
  adapters/
    inbound/    # a2a, rest, bus input and wire projection
    outbound/   # agentcore, agentscope, remote, skillhub, stores
  bootstrap/    # app factories, wiring, lifespan, readiness
deploy/         # host and socket probes
agent_runtime/tests/
```

新功能先选择所属层，再写代码；如果同一个类同时解析 HTTP、调用模型和写 Redis，说明边界没有拆开。

## 托管与装配规范

Handler 必须实现 `AgentHandler` 的五个生命周期/执行方法，产出 `QueryChunk`，并将框架私有类型限制在 adapter 内。入口工厂负责契约检查和 wiring，不负责业务 prompt、工具选择或模型调用。

组合根的最小检查项：

1. handler 满足 `AgentHandler`；
2. REST channel 满足 `RestChannel`；
3. Task/Session store 的类型和共用关系明确；
4. 同进程双入口复用 `ServeOrchestrator`；
5. lifespan 覆盖 start、ready、drain、stop；
6. readiness 导出同一个只读实例，而不是新建一个永远为空的视图。

组合根可以接受处理器注入。例如参考宿主 `deploy/host_app.py:create_app(handler=)` 在给定 `handler` 时替换内建后端，但 Task/Session、协议入口、Skill Hub、探针和生命周期等其余装配必须保持不变。SDK 方式应使用这种注入形态；不要为了接入自定义 Handler 复制一套宿主装配。存量兼容宿主 `agent_runtime.bootstrap.legacy_compat.host:create_app` 也通过同一参数把桥接处理器交给参考宿主。

## 配置规范

显式函数参数优先于宿主加载的配置，宿主配置优先于安全默认值。runtime 库不能擅自读取当前目录、覆盖宿主环境变量或打印 secret。

配置项必须记录：完整路径、类型、默认值、是否敏感、适用入口和缺失行为。新增配置必须同步更新：配置类、组合根、示例 YAML、验证判据和 `docs/compatibility.md`。

## 脱敏规范

个人开发环境的大模型网关地址、网关产品名、任何 API key，以及测试方环境的公网地址，一律不得写入文档或代码；模型名、路由名和环境细节也应按需脱敏。文档和示例统一使用 `<模型网关地址>`、`<模型名>`、`<已脱敏>`、`<测试环境地址>`，或使用“外部 OpenAI 兼容网关”这类泛化描述。凭据只允许通过环境变量注入，不能出现在源码、Markdown、日志样例、测试夹具或提交历史新增内容中。

## 状态与存储规范

TaskStore、SessionStore、cache 和 event projection 是不同语义，即使都使用 Redis 也必须分开接口。进程内实现只用于单进程测试，文档不得把它写成生产默认。

写状态必须定义幂等键、TTL、并发覆盖、重启行为和跨租户读取规则。任何“读状态时顺便触发执行”的实现都违反查询边界。

## 协议规范

领域事件与 wire 事件分离：`QueryChunk` 不承载 A2A Event 或 SSE framing。对外变更必须同时更新 parser、formatter、error projection、原始 wire 测试和兼容性说明。

终答是内容事件，完成是流正常结束；不要新增 `completed` 领域类型掩盖执行结束。未知事件必须有明确的降级或失败策略。

## 测试与证据规范

每项能力至少分四层证据：

| 层 | 内容 |
|---|---|
| 源码 | 公开签名、组合根、状态路径 |
| 单测 | parser、mapper、store、异常 |
| 部署 E2E | 真实 socket/进程/依赖 |
| wire | 原始 HTTP、SSE、A2A、错误信封 |

测试报告必须写命令、环境、时间、退出码、通过数和未覆盖项。`pytest` 全绿不能替代 socket 和协议证据。

## 文档与示例规范

`docs/examples/` 放完整能力闭环，必须包含 README、配置、启动方式、验证方式与限制。`docs/snippets/` 只放可叠加的一文件片段，不能冒充可运行工程。未跑通的东西不写成已通过。

当前仓库的 canonical Agent 源码集位于 `docs/examples/`。每个工程必须采用：

```text
docs/examples/<name>/
├── src/<package>/agent/       # 语义层
├── src/<package>/runtime/     # 服务层
├── resources/                 # 配置/提示词/Schema
└── tests/                     # 工程测试
```

示例目录不携带构建配置；新工程从[共享最小工程模板](../examples/minimal-agent-service-pyproject.toml)复制一份 `pyproject.toml`。

`docs/examples/` 是新工程的复制起点：`agent/` 应先完成真实 agent-core 语义装配，`runtime/` 再通过 `AgentCoreHandler` 或等价公开 adapter 接入当前 runtime。协议闭环工程里的确定性替身只验证 runtime 契约，必须明确标注，不得冒充真实 agent-core 应用。任何工程都不复制 runtime 源码。

## 契约变更流程

修改公开签名、wire 字段、Task 状态、错误码、事件顺序或生命周期时：

1. 写出旧/新契约和兼容策略；
2. 更新受影响的架构页或就地写明边界；
3. 修改 domain/port；
4. 更新所有 adapter 和组合根；
5. 添加正例、反例和失败测试；
6. 重跑部署和 wire 验证；
7. 更新 API、How-to、coverage matrix、known gaps 和 acceptance report。

本批 `ServeRequest.metadata` 的闭集变更是一个具体范例：REST 通道原先登记的三个关联事实键扩为四个——`trace_id`、`agent_id`、`caller_params`、`request_body`；`request_body` 对位原始请求体，只透传、不解释、不是控制面。契约变更时应同步更新 `ServeRequest.REQUEST_BODY_META_KEY` 的 API 说明、REST/A2A adapter 事实、`agent_runtime/tests/test_inbound_metadata_boundary.py` 的集合判据，以及兼容性和验证记录，避免只改正文而漏掉闭集测试。

禁止只改文档使未实现能力看起来已交付，也禁止只改实现而不更新 wire 证据。

## AI Coding 反模式

- 看到接口存在就宣称能力已接线；
- 用 mock/stub 替代真实 runtime 路径而不标注；
- 把一次测试通过写成生产可用；
- 为了让示例变短而省略 TaskStore、生命周期和错误处理；
- 用 `Any`/`dict` 绕过已有端口，而不记录原因；
- 复制一套“临时 orchestrator”让入口绕过组合根；
- 把本地路径或当前机器的 secret 写入公共文档。

## See also

- [openJiuwen Python 开发指导](openjiuwen开发指导.md)
- [Agent Runtime Python 技术架构总览](../architecture/00-OpenJiuwen技术架构总览.md)
- [Agent 源码用例](../examples/overview.md)
- [任务导向指南](../how-to/overview.md)
