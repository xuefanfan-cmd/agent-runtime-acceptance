---
feature_id: DFX-005
test_type: deepagent
scope: v0915; agent-core-java=930, agent-runtime-java=develop, agent-solution=common
deployable_units: [edp-agent-engine]
sut: edp-agent-engine exec JAR 通过 runtime per-Task 模式创建 edp-agent-java DeepAgent
status: designed-runnable
features: [DFX-005]
updated: 2026-09-22
---

# edp-agent-engine 验收：agent-core 运行时任务级并发

## 1. 测试目标

以正式 `com.openjiuwen:edp-agent-engine:0.1.2:exec` 为真实 SUT，从公开 A2A/Task 边界并发驱动 `EdpAgentFactory` 的 per-Task DeepAgent 创建、执行和销毁，验证多实例执行隔离、先完成 Task 后的共享能力存活、重复生命周期后的持续可用、单 Agent 兼容，以及受控模型故障下的异常隔离。

本设计只承接本次交付范围内、可由正式 EDPA Agent 触发并由公开 A2A 行为判定的场景。网络黑盒无法精确观察的注册、回调、MCP、同实例初始化和归属状态等 core 内部不变量不生成测试场景，也不计入用例总数；波 2 能力、人工合同审查和 JDK 17 专属路径同样不计入本次交付。

## 2. 范围与非范围

范围：正式 EDPA Agent 的 per-Task 多实例并发请求、唯一 canary 隔离、交错完成/销毁、重复波次健康、single profile 兼容，以及通过受控 OpenAI-compatible 模型依赖观察的单 Task 异常隔离。

非范围：波 2 的 `RegistrationScope.INSTANCE`、实例内工具域、本地创建和前置 fail-fast；API/Javadoc 人工合同审查；runtime Task 准入/额度/限流；单实例重叠执行；销毁与在飞执行并发；直接读取 `ResourceMgr`、归属表、回调表、MCP 客户端、线程、缓存或 Redis 私有 key；用 fake Agent 或反射替代真实黑盒；JDK 17 有界池专属行为。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/DFX-005-core-concurrent-agent-lifecycle.md`（2026-09-14） | 本轮波 1 的生命周期、错误、资源和兼容承诺。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-core/Feat-DFX-005-core-concurrent-agent-lifecycle.md` | 波 1 交付边界、公开入口、状态机、错误表面和 EDPA V1 适配说明。 |

## 4. 部署拓扑

```text
JUnit deepagent driver
  -> classifier-aware process fixture
       -> edp-agent-engine:0.1.2:exec (single/multi profile)
            -> hosted A2A ingress -> EdpAgentFactory -> per-Task DeepAgent
            -> OpenAI-compatible LLM -> Redis
  <- A2A JSON-RPC/SSE/Task results, readiness and stdout
```

launcher 只解析 `exec` classifier、随机端口、注入标准环境变量、就绪探测和 teardown，不替换 Agent/runtime/core；只断言协议终态、唯一 canary、任务隔离和后续可用性。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| D005-01 | 多 Task 并发执行与结果隔离 | runnable/P0/blackbox；multi、LLM、Redis ready，每请求唯一 canary | 同时提交不少于 4 个独立 A2A 请求并等待终态 | 全部成功；每个结果含自身 canary、不含对端 canary；进程 ready | 正式 exec JAR、并发驱动 |
| D005-02 | 先完成 Task 销毁不破坏在飞实例 | runnable/P0/blackbox；不少于 3 个异步 Task 重叠 | 观察首个成功终态，继续轮询其余并提交后继探针 | 其余 Task 和后继探针成功，结果不串扰 | Task client、有界轮询 |
| D005-03 | 重复生命周期波次后的服务健康 | runnable/P0/blackbox；multi ready | 执行 3 轮、每轮不少于 4 个并发请求，轮后探针 | 各轮成功且不串扰；最终探针成功；服务不退出 | 分波驱动、进程探针 |
| D005-04 | 单 Agent 既有路径兼容 | runnable/P0/blackbox；single ready | 经 legacy Card/A2A 执行同步和流式请求 | Card、入口、成功终态和响应结构有效，canary 可识别 | single launcher、A2A client |
| D005-09 | 单 Task 执行异常隔离 | runnable/P0/blackbox；模型 fixture 只拒绝故障 canary | 故障 Task 与正常 Task 并发，随后发送后继探针 | 故障 Task 的公开结果包含受控错误语义，不被误判为正常答案；其他 Task 和后继探针成功且不含故障 canary。A2A Task 平台终态由 agent-runtime 负责，不作为 agent-core Oracle | OpenAI-compatible 模型 fixture、正式 exec JAR |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| `edp-agent-engine` | 真实 SUT | 当前分支构建的 `com.openjiuwen:edp-agent-engine:0.1.2:exec`；通过带创建锁的 `EdpAgentFactory` 创建 per-Task DeepAgent，验证本轮波 1 行为。 |
| hosted A2A ingress | 真实依赖 | 提供 Card、JSON-RPC、SSE 和 Task；Task 准入不是本特性 Oracle。 |
| Redis | 真实依赖 | 正式 engine 启动/会话依赖，不读取内部 key。 |
| OpenAI-compatible LLM/fixture | 真实或受控外部依赖 | 正常 canary 返回合法 completion；D005-09 的故障 canary 返回确定性 HTTP 5xx；通过 `EDP_AGENT_MODEL_*` 注入。 |
| classifier-aware launcher | Fixture | 解析 exec JAR、随机端口、就绪、stdout 和 teardown；不依赖产品内部类。 |
| 并发 A2A/Task client | Fixture | 并发提交、轮询和结果归集；不实现 Agent 生命周期或注册语义。 |

## 7. 关键链路断言

- D005-01/02/03 证明外部并发、交错销毁存活和重复波次健康。
- D005-09 证明模型依赖故障只影响目标 Task 的结果；故障结果保留结构化错误语义，peer 与后继请求不受影响。A2A Task 终态映射由 agent-runtime 负责，不在本特性 Oracle 内。
- 每个成功结果必须含本请求 canary 且不得含其他并发请求 canary；自然语言不逐字匹配。

## 8. 执行策略

- Smoke：D005-01、D005-04。
- 当前 Full suite：D005-01..04、D005-09，共 5 项。
- P0 必须全绿：5 项。
- LLM/Redis/Docker/WSL 缺失为环境 ERROR；内部公开观察面不足的场景已排除出本轮矩阵。
- 每请求 watchdog 180 秒、Task 500 ms 有界轮询；明确成功终态以外的失败/超时为 FAIL。
- `DFX005_<场景>_<轮次>_<UUID>` 作为 canary，使用唯一 contextId/taskId；密钥只经进程环境传递。
- 自动化状态：`verified`。2026-09-22 已在 WSL 使用正式 `0.1.2:exec` 制品精确执行：5 项运行，D005-01..04 和 D005-09 全部通过。D005-09 按 agent-core 责任边界断言受控错误结果和实例间隔离，不把 agent-runtime 的 A2A Task 终态映射作为 core Oracle。当前自动化为一个黑盒类，五个 runnable 场景各自有唯一方法、Story、JUnit story tag 和 DisplayName；内部不变量、波 2 和 JDK 17 专属路径不生成 design-only 用例。

```bash
./.agents/skills/feature-acceptance-testing/scripts/run-wsl-tests.ps1 -TestSelector Dfx005ConcurrentAgentLifecycleBlackboxTest -Environment openjiuwen
```

## 附录 A. 差异、门禁与待澄清项

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| A2A 无注册、回调、MCP 连接和残留计数观察面 | 内部不变量不纳入本次 Agent 验收 | out-of-scope | 另行提供诊断/故障注入面后设计 contract 测试 |
| 默认 JDK 21 虚拟线程路径 | JDK17 有界池专属行为不属于正式 Agent 验收 | out-of-scope | 另行建立 JDK17 专项测试交付 |
