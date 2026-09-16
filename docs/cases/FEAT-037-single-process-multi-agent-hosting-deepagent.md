---
feature_id: FEAT-037
test_type: deepagent
scope: 915-direct
deployable_units: [multi-deep-research-hosted-demo, agent-deep-research]
sut: multi-deep-research-hosted-demo 单进程托管两个真实 DeepAgent 实例
features: [FEAT-037]
updated: 2026-09-15
---

# multi-deep-research-hosted-demo 验收：单进程多智能体实例托管

## 1. 测试目标

以 `multi-deep-research-hosted-demo` 的一个 JVM 进程和其中两个真实 DeepAgent 为黑盒 SUT，验证 FEAT-037 的注册、发现、路由、默认实例、错误、协议兼容、Runtime Task/会话隔离、Core 共享会话清理边界、生命周期、共享额度、活动任务查询和单实例兼容。两个实例的注册 ID 固定为 `agent-a`、`agent-b`，业务回答由确定性 OpenAI 兼容端点回显实例身份与唯一 canary；测试只断言公开 HTTP/A2A/SSE、Card、Task、活动任务快照和进程日志。

本设计的每个矩阵行对应一个唯一 JUnit 方法、Allure Story、JUnit story tag 和 DisplayName，可直接据此生成或复核自动化代码。

## 2. 范围与非范围

范围：

- 单进程双 DeepAgent 静态注册、原子启动、实例名校验、有序清单、显式默认和首项回退。
- 实例 Card、实例路径 A2A、根 A2A、标准 REST 的默认及 `agent_id` 选路。
- SendMessage、SendStreamingMessage、GetTask、SubscribeToTask 的实例作用域、错误和防伪造。
- 内存与 Redis 下同步、流式、异步 Task 的实例隔离、重启恢复和清理边界。
- 原响应格式兼容、生命周期恰好一次、进程额度共享、进程/实例活动任务查询、影子 Task 归属、目标局部 TaskNotFound 和原单 Handler 兼容。

非范围：

- CancelTask、跨实例同名会话的全局 owner 拒绝、跨实例 Task owner 扫描和专用 owner-mismatch 错误不在本期范围，不生成对应测试方法。
- tenant 路由、运行期动态增删/启停、管理面鉴权、agent-bus/RDC/gateway、客户端 SDK 寻址。
- 实例独立并发额度和活动任务条目新增 `agentId`/`agent_id` 字段；实例身份由查询参数和筛选结果证明。
- 工作空间、凭据、模型资源继承、Core 完整同名会话/reset 隔离、内部 checkpoint/key、线程、缓存或私有注册表。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-037-单进程多智能体实例托管.md`（status: active，updated 2026-09-14） | 915 范围、MUST/OUT、实例寻址、Runtime/Core 隔离边界、响应兼容及错误 Oracle。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-037-single-process-multi-agent-hosting.md`（status: draft，updated 2026-09-10） | 公开入口、方法集合、默认配置、REST 字段、协议错误和当前存储边界。 |

## 4. 部署拓扑

```text
JUnit 黑盒驱动
  +-> 确定性 OpenAI 兼容 LLM（输入 Fixture）
  +-> Redis（T21-T23 外部存储）
  `-> multi-deep-research-hosted-demo（单 JVM / 随机端口）
        +-> agent-a -> 真实 DeepAgent -> HOSTED_DEEP_AGENT_A
        `-> agent-b -> 真实 DeepAgent -> HOSTED_DEEP_AGENT_B

T28：同一驱动 -> agent-deep-research（原单 Handler JAR）
```

边界要求：双实例场景必须确认两个实例共用同一 PID；确定性 LLM 只供应输入和可识别结果，不实现 Runtime 路由或隔离；Redis 由现有 service binding 自动启动和回收；所有断言来自公开网络响应或生命周期日志。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| T01 | 双实例注册和目录 | Given hosted-deep 以 A 后 B 的顺序声明两个 Handler | When 启动并 GET `/a2a/agents`、两张实例 Card、检查 PID | Then `agents` 严格为 `[agent-a,agent-b]`，默认 A，两张 Card 可用且只有一个进程 | `hosted-deep`、HTTP/Card/PID 探针 |
| T02 | 非法实例名 | Given 依次覆盖为空白、连字符开头、含斜杠、含点号 | When 每种配置单独启动 hosted-deep | Then 每次均在 ready 前退出，不能发布服务 | 临时负向 `SutStack` |
| T03 | 重复实例名 | Given agent-b-id 覆盖为 `agent-a` | When 启动 hosted-deep | Then 在 ready 前 fail-fast，无部分服务 | 临时负向 `SutStack` |
| T04 | 部分装配失败回滚 | Given agent-b 的公开装配配置无效 | When 启动 hosted-deep | Then 整进程不就绪，agent-a 不单独发布 | 故障启动配置 |
| T05 | 默认规则 | Given 基础栈未显式 defaultAgent，临时栈显式为 B | When 查询目录并向根 A2A SendMessage | Then 基础栈顺序 A/B 且命中 A；显式栈默认 B 且命中 B | 目录探针、临时 `SutStack` |
| T06 | 每实例 Card | Given 双实例 ready | When 获取 `/a2a/agents/{id}/.well-known/agent-card.json` 并调用 Card URL | Then name/skill/URL 属于目标实例，响应只含该实例身份 | HTTP/A2A、身份 canary |
| T07 | 未知 Card | Given 双实例 ready | When 获取 missing-agent Card | Then HTTP 404，body 不泄漏 A/B 身份，真实 Card 仍为 200 | HTTP 探针 |
| T08 | 路径同步路由 | Given A/B 使用不同系统身份 | When 分别向实例路径 SendMessage | Then HTTP 200，响应含请求 canary 和目标身份，不含对端身份 | 确定性 LLM |
| T09 | 路径流式路由 | Given A/B 使用不同系统身份 | When 分别 SendStreamingMessage 并解析 `data:` 帧 | Then content-type 为 SSE、JSON 帧非空，结果只含目标身份和 canary | 确定性 LLM、SSE 解析器 |
| T10 | GetTask 作用域 | Given 在 A 创建 Task 并取得 taskId | When A、B 分别 GetTask | Then A 返回该 Task 且无 error；B 返回 JSON-RPC error 且不泄漏 A 内容 | Task 探针 |
| T11 | SubscribeToTask 作用域 | Given 在 A 创建 Task | When A、B 分别 SubscribeToTask | Then A 返回对应 Task；B 返回 error 且不泄漏 A 内容 | SSE/Task 探针 |
| T13 | 根 A2A 默认实例 | Given A 为默认 | When 根 `/a2a` 发送同步和流式请求 | Then 两个结果均只含 A 身份及各自 canary | 确定性 LLM |
| T14 | REST 选择与默认回退 | Given A 默认、B 非默认 | When 对 `/v1/query`、`/query`、`/v1/query/reactive` 分别省略 agent_id 或指定 B，并发送非法/未知值 | Then 省略命中 A、指定命中 B；非法返回 400，未知返回 404 且不泄漏身份 | REST/SSE 探针 |
| T15 | 未知实例明确错误且不列目录 | Given 目录含 A、B | When 向 missing-agent 实例路径 SendMessage | Then HTTP 200、JSON-RPC `error.code=-32602`、message 非空，无 result/data/details/metadata，不执行、不回退且 body 不列 A/B | JSON-RPC 探针 |
| T16 | metadata 防伪造 | Given 路径目标 A、根默认 A | When metadata 中同时自报 agent-b/agentId/hostedAgent | Then 两个请求仍命中 A，不命中 B | 定制 A2A payload |
| T17 | 原响应格式兼容 | Given 分别调用 A、B | When 检查同步 A2A 响应结果和 metadata | Then result、业务 canary 与目标执行结果保持，Runtime 不统一注入 agent-a/agent-b 托管身份 metadata | JSON 与业务 canary 探针 |
| T18 | 内存同步同 contextId 隔离 | Given A、B 使用相同 contextId 写入不同 marker | When 分别同步回忆 | Then A 只含 markerA，B 只含 markerB | 确定性 LLM、内存 profile |
| T19 | 内存流式同 contextId 隔离 | Given A、B 使用相同 contextId 流式写入不同 marker | When 分别流式回忆 | Then 两条 SSE 结果只含各自 marker | 确定性 LLM、SSE 解析器 |
| T20 | 内存异步 Task 隔离 | Given A、B 使用相同 contextId 创建 Task | When 交叉 GetTask | Then taskId 不同，各自可查，对端查询均 error | Task 探针 |
| T21 | Redis 同步隔离 | Given Redis profile 且 A/B 同 contextId 写入不同 marker | When 重启同一 hosted SUT 后分别同步回忆 | Then 各自历史恢复且无对端 marker/身份 | Redis service binding、确定性 LLM |
| T22 | Redis 流式隔离 | Given Redis profile 且 A/B 同 contextId 流式写入 marker | When 重启后分别流式回忆 | Then 两条流分别恢复本实例历史且不串扰 | Redis、SSE 解析器 |
| T23 | Redis Task 归属 | Given A/B 各自创建持久化 Task | When 重启后本实例和对端交叉 GetTask | Then 本实例恢复原 Task，对端查询 error | Redis、Task 探针 |
| T25 | 生命周期恰好一次 | Given 独立 hosted 栈 | When 正常启动后关闭并读取日志 | Then A/B 的 start/stop begin/success 各出现且仅出现一次 | 临时 `SutStack`、日志计数 |
| T26 | 共享会话清理边界 | Given A/B 以同一 contextId 写入各自历史 | When REST reset 指定 A，再使用同一 contextId 调用 B | Then reset 成功，后续请求仍由 B 执行且不转投 A；B 的旧 marker 是否保留只记录为 Core 会话级 release 证据，不作为通过条件 | REST reset、确定性 LLM |
| T27 | 共享进程额度 | Given 进程最大并发任务为 1 且 LLM 延时 750ms | When A/B 并发发起流式请求 | Then 状态仅为 200/503 且两个结果包含一个 200 和一个 503，PID 仍有效 | 慢响应 LLM、并发 HTTP |
| T28 | 单 Handler 兼容 | Given 原 `agent-deep-research` 不声明 hosted 集合 | When 获取根 Card、检查实例目录、同步和流式请求 | Then Card/两种调用成功且 taskId 不同；实例目录 404 | 原单实例 JAR、确定性 LLM |
| T29 | 影子 Task 归属 | Given 正式远端 A2A fixture/profile 可启动 | When A/B 以相同 parentTaskId 委托并恢复 | Then 影子 Task 不覆盖且回到各自父 Task；依赖未交付时明确 SKIPPED | 受控远端 A2A；dependency-gated |
| T30 | 目标局部 TaskNotFound | Given Task 只属于 A | When B GetTask、与随机不存在 Task 比较错误，并用原 taskId 从 B 续跑 | Then 两类错误使用相同既有 TaskNotFound code/message/data/details；B 不创建或转投 Task，A 的原 Task 仍存在 | JSON-RPC Task/错误探针 |
| T31 | 活跃任务进程汇总 | Given A/B 各有一个唯一 contextId 的流式任务，模型 Fixture 已确认两个请求到达并保持未完成 | When 无参数 GET `/v1/current_active_tasks` | Then HTTP 200；`maxConcurrentTasks=4`、`currentActiveTasks=2`；列表恰含两个 contextId，条目均有 taskId/status/startedAt 且无 agentId/agent_id | `OpenAiEchoFixture` 双到达门闩、HTTP JSON 探针 |
| T32 | 活跃任务按实例筛选 | Given A/B 各有一个已被门闩保持的活动任务 | When 分别 GET `?agentId=agent-a` 与 `?agentId=agent-b` | Then 两个响应均 200、共享上限均为 4、活动数均为 1；列表只含目标实例 contextId 且条目不新增实例字段 | `OpenAiEchoFixture` 双到达门闩、HTTP JSON 探针 |
| T33 | 已注册空闲实例负载 | Given A 的任务已到达模型并保持，B 已注册但无任务 | When GET `?agentId=agent-b` | Then HTTP 200、`maxConcurrentTasks=4`、`currentActiveTasks=0`、`tasks=[]` | `OpenAiEchoFixture` 单到达门闩、HTTP JSON 探针 |
| T34 | 空白实例负载参数 | Given hosted-deep ready | When 分别 GET `?agentId=` 和 URL 编码的纯空白值 | Then 两次均 HTTP 400，不返回进程或默认实例快照 | HTTP 状态探针 |
| T35 | 未知实例负载查询 | Given 目录只有 agent-a、agent-b | When GET `?agentId=missing-agent` | Then HTTP 404，响应不包含 A/B 业务身份且不回退进程/默认实例 | HTTP 状态与响应体探针 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| `hosted-deep` | 真实 SUT | 正式 fat JAR `com.openjiuwen.example:multi-deep-research-hosted-demo:0.1.1`；一个进程注册 A/B 两个真实 DeepAgent。 |
| `deep-research` | 真实 SUT | 正式 `agent-deep-research:0.1.1` 单 Handler 制品，只用于 T28 回归。 |
| `OpenAiEchoFixture` | Fixture | 本地随机端口的 OpenAI 兼容 HTTP 服务，回显 system identity 和用户文本，并提供可观测请求到达、30 秒 watchdog 和显式释放门闩；不实现被测能力。 |
| Redis | 真实依赖 | 由现有 `redis` service binding 提供；仅从公开重启后行为断言，不读取内部 key。 |
| HTTP/A2A/SSE/活动任务/日志探针 | Fixture | 生成唯一 payload，解析公开响应与负载快照，核对 PID/日志；共享驱动不改变每个场景的一对一追溯。 |

## 7. 关键链路断言

- A/B 的 Card、请求路径、执行身份、Task、历史、SSE 和 Redis 恢复必须一致，任何对端 canary 泄漏均为 FAIL。
- 未指定默认取首个注册者，显式 defaultAgent 可为非首项；标准 REST 的 agent_id 可选择非默认实例。
- 未知实例必须返回明确错误、不回退且不列举可用实例；metadata 自报实例不能覆盖可信入口。
- A2A/REST 保持原响应格式，不要求或统一注入托管实例 metadata；实例身份通过目录、Card、调用 URL、业务结果和日志观察。
- Runtime Task/轨迹等按实例隔离；同名 Core session 的 reset 允许保留原会话级 release 影响，T26 只断言 reset 后仍按目标实例路由并记录实际历史保留情况。
- 目标实例不存在显式 Task 时统一返回既有 TaskNotFound，不扫描其他实例、不创建替代 Task，也不要求专用 owner-mismatch 表面。
- 生命周期日志只用于已承诺的每实例 start/stop 次数；内部对象、存储 key 和线程不做断言。
- 活动任务无参汇总必须同时看到 A/B；按注册 ID 查询只看到目标实例，已注册空闲实例为 200 空列表，空白和未知 ID 分别为 400/404，均不得回退。
- 实例筛选不拆分进程并发额度；所有快照返回相同共享上限，任务条目保持 taskId/conversationId/status/startedAt 合同且不新增实例字段。
- LLM 输出只检查身份、唯一 canary 和历史隔离，不逐字匹配自然语言。

## 8. 执行策略

- 测试类为 `DeepAgentMultiInstanceHostingBlackboxTest`；Txx 映射方法 `txx<ScenarioName>`、Story `FEAT-037.Txx: <场景>`、tag `story-feat-037-txx`、DisplayName `FEAT-037 Txx DeepAgent <场景>`，矩阵 33 行均已实现为唯一方法。
- 自动化状态：除 dependency-gated/`SKIPPED` 的 T29 外，矩阵场景均为 `verified`；T15、T17、T26、T30 已于 2026-09-15 按新合同精确复测通过。
- 2026-09-15 WSL 精确复测：T15/T17/T26/T30 为 4 PASS、0 FAIL、0 ERROR、0 SKIPPED；T26 记录 `peerHistoryRetained=false`，与 L2 规定的会话级 release 边界一致。2026-09-12 的四项 FAIL 使用了已被最新 Feature/L2 明确废止的 Oracle，重新分类为 `design error` 历史证据，不再作为产品缺陷。T12、T24 对应的旧需求不属于本期合同。
- Smoke：T01、T05、T06、T08、T09、T13-T16、T28、T31、T32、T34、T35。Full suite：整个测试类，共 33 个方法。
- 每次运行由 `OpenAiEchoFixture` 先启动，再启动 hosted SUT；类结束关闭 SUT 和 fixture。Redis 场景单独启动临时栈并在 finally 中回收。
- 每个请求使用 `FEAT037_DEEP_<Txx>_<UUID>` canary 和唯一 contextId；HTTP 60s、模型链路 180s，异步均使用有界请求/轮询。
- 本地验收必须通过 WSL runner 精确执行，不直接使用 PowerShell Surefire 结果：

```powershell
.\.agents\skills\feature-acceptance-testing\scripts\run-wsl-tests.ps1 `
  -TestSelector "DeepAgentMultiInstanceHostingBlackboxTest#t15UnknownInstanceReturnsProtocolErrorWithoutCatalog,DeepAgentMultiInstanceHostingBlackboxTest#t17ResponsePreservesProtocolWithoutHostedMetadata,DeepAgentMultiInstanceHostingBlackboxTest#t26SharedSessionResetKeepsTargetRouting,DeepAgentMultiInstanceHostingBlackboxTest#t30WrongInstanceUsesTaskNotFoundWithoutOwnerLookup" `
  -Environment openjiuwen
```

## 附录 A. 差异、门禁与待澄清项

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| 历史 Oracle 变更 | T15/T17/T26/T30 的旧预期已被最新 Feature/L2 替换 | 原 product confirmed 结论撤销；DeepAgent 四个目标方法 4/4 PASS | 保留本轮 WSL/Surefire 证据，后续按新合同回归 |
| 远端影子 Task fixture | T29 当前不能真实触发 | dependency-gated | 交付可由测试自动启动的正式远端 A2A profile/制品 |
| CancelTask | 不生成 T12 方法 | OUT；本期四方法集合不包含 CancelTask | 新 Feature 明确纳入时重新设计独立场景 |
| 全局 owner/同名会话拒绝 | 不生成 T24 方法；按目标实例局部作用域处理 | OUT；不得恢复旧全局 owner Oracle | 新 Feature 明确改变目标局部语义时重新设计 |
