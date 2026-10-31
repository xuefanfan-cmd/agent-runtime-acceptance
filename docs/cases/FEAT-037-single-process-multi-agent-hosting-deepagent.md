---
feature_id: FEAT-037
test_type: deepagent
scope: 915-direct
deployable_units: [multi-deep-research-hosted-demo, agent-deep-research, multi-mixed-agents-hosted-demo]
sut: 双 DeepAgent 托管及独立 mixed demo 单进程托管真实 ReActAgent 与 DeepAgent
features: [FEAT-037]
updated: 2026-10-30
---

# multi-deep-research-hosted-demo 验收：单进程多智能体实例托管

## 1. 测试目标

以 `multi-deep-research-hosted-demo` 的一个 JVM 进程和其中两个真实 DeepAgent 为黑盒 SUT，验证 FEAT-037 的注册、发现、路由、默认实例、错误、协议兼容、Runtime Task/会话隔离、Core 共享会话清理边界、生命周期、共享额度、活动任务查询、实例级远端 Agent 目录和单实例兼容。两个实例的注册 ID 固定为 `agent-a`、`agent-b`，业务回答由确定性 OpenAI 兼容端点回显实例身份与唯一 canary；远端工具及 Intent 场景由确定性模型端点驱动，测试只断言公开 HTTP/A2A/SSE、Card、Task、活动任务快照、远端请求审计和进程日志。

本设计的每个矩阵行对应一个唯一 JUnit 方法、Allure Story、JUnit story tag 和 DisplayName，可直接据此生成或复核自动化代码。

混合类型增量以 `multi-mixed-agents-hosted-demo` 的同一 JVM/Runtime 为 SUT，同时注册真实 ReActAgent 预算助手 `react-budget` 与真实 DeepAgent 行程助手 `deep-itinerary`。依据 Feature §6.3/6.4/9.2 与 L2 §2.2.2/9，验证不同执行类型共存时的注册发现、业务执行、状态隔离和共享资源。真实 LLM 完成一项北京到上海的三天出差准备任务：先核算费用，再形成行程和风险报告；不使用问候请求或预置答案作为通过证据。

## 2. 范围与非范围

范围：

- T44–T53：同进程 ReActAgent + DeepAgent 混合托管，两个实例均参与业务执行；含同步、SSE、REST、同名会话、Task、启停、共享额度和活动任务查询。
- 单进程双 DeepAgent 静态注册、原子启动、实例名校验、有序清单、显式默认和首项回退。
- 实例 Card、实例路径 A2A、根 A2A、标准 REST 的默认及 `agent_id` 选路。
- SendMessage、SendStreamingMessage、GetTask、SubscribeToTask 的实例作用域、错误和防伪造。
- 内存与 Redis 下同步、流式、异步 Task 的实例隔离、重启恢复和清理边界。
- 原响应格式兼容、生命周期恰好一次、进程额度共享、进程/实例活动任务查询、影子 Task 归属、目标局部 TaskNotFound 和原单 Handler 兼容。
- 全局远端目录继承、局部追加/同名整条覆盖、发现失败隔离与重试恢复、全局发现传播、Intent 候选/委派、TLS Client 隔离、局部配置校验和自定义 Caller 兼容。

非范围：

- CancelTask、跨实例同名会话的全局 owner 拒绝、跨实例 Task owner 扫描和专用 owner-mismatch 错误不在本期范围，不生成对应测试方法。
- tenant 路由、运行期动态增删/启停、管理面鉴权、agent-bus/RDC/gateway、客户端 SDK 寻址。
- 实例独立并发额度和活动任务条目新增 `agentId`/`agent_id` 字段；实例身份由查询参数和筛选结果证明。
- 工作空间、凭据、模型资源继承、Core 完整同名会话/reset 隔离、内部 checkpoint/key、线程、缓存或私有注册表。
- 运行期目录热更新、删除全局继承项、实例级 Intent 匹配策略、Card 发现 TLS 和循环委派不在范围内。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-037-单进程多智能体实例托管.md`（status: active，updated 2026-09-14） | 915 范围、MUST/OUT、实例寻址、Runtime/Core 隔离边界、响应兼容及错误 Oracle。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-037-single-process-multi-agent-hosting.md`（status: draft，updated 2026-09-18） | 公开入口、方法集合、默认配置、REST 字段、协议错误和当前存储边界。 |

## 4. 部署拓扑

```text
JUnit 黑盒驱动
  +-> 确定性 OpenAI 兼容 LLM（输入 Fixture）
  +-> Redis（T21-T23 外部存储）
  `-> multi-deep-research-hosted-demo（单 JVM / 随机端口）
        +-> agent-a -> 真实 DeepAgent -> HOSTED_DEEP_AGENT_A
        `-> agent-b -> 真实 DeepAgent -> HOSTED_DEEP_AGENT_B

T28：同一驱动 -> agent-deep-research（原单 Handler JAR）

T44–T53：JUnit -> 透明模型转发/到达门闩 -> 真实 OpenAI 兼容 LLM
            `-> multi-mixed-agents-hosted-demo（唯一 JVM / 唯一 Runtime / 随机端口）
                  +-> react-budget -> ReActAgent -> BUDGET_ANALYST
                  `-> deep-itinerary -> DeepAgent -> ITINERARY_REVIEWER
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
| T29 | 影子 Task 归属 | Given 可启动的正式远端 A2A profile | When A/B 以相同 parentTaskId 委托并恢复 | Then 影子 Task 不覆盖且回到各自父 Task | 受控远端 A2A |
| T30 | 目标局部 TaskNotFound | Given Task 只属于 A | When B GetTask、与随机不存在 Task 比较错误，并用原 taskId 从 B 续跑 | Then 两类错误使用相同既有 TaskNotFound code/message/data/details；B 不创建或转投 Task，A 的原 Task 仍存在 | JSON-RPC Task/错误探针 |
| T31 | 活跃任务进程汇总 | Given A/B 各有一个唯一 contextId 的流式任务，模型 Fixture 已确认两个请求到达并保持未完成 | When 无参数 GET `/v1/current_active_tasks` | Then HTTP 200；`maxConcurrentTasks=4`、`currentActiveTasks=2`；列表恰含两个 contextId，条目均有 taskId/status/startedAt 且无 agentId/agent_id | `OpenAiEchoFixture` 双到达门闩、HTTP JSON 探针 |
| T32 | 活跃任务按实例筛选 | Given A/B 各有一个已被门闩保持的活动任务 | When 分别 GET `?agentId=agent-a` 与 `?agentId=agent-b` | Then 两个响应均 200、共享上限均为 4、活动数均为 1；列表只含目标实例 contextId 且条目不新增实例字段 | `OpenAiEchoFixture` 双到达门闩、HTTP JSON 探针 |
| T33 | 已注册空闲实例负载 | Given A 的任务已到达模型并保持，B 已注册但无任务 | When GET `?agentId=agent-b` | Then HTTP 200、`maxConcurrentTasks=4`、`currentActiveTasks=0`、`tasks=[]` | `OpenAiEchoFixture` 单到达门闩、HTTP JSON 探针 |
| T34 | 空白实例负载参数 | Given hosted-deep ready | When 分别 GET `?agentId=` 和 URL 编码的纯空白值 | Then 两次均 HTTP 400，不返回进程或默认实例快照 | HTTP 状态探针 |
| T35 | 未知实例负载查询 | Given 目录只有 agent-a、agent-b | When GET `?agentId=missing-agent` | Then HTTP 404，响应不包含 A/B 业务身份且不回退进程/默认实例 | HTTP 状态与响应体探针 |
| T36 | 远端目录继承、追加和覆盖闭环 | Given 全局目录含 shared/global-only，A 局部含同名 shared 与 local-only，B 无局部配置 | When 由确定性模型让 A/B 分别调用可见远端工具 | Then A 的 shared 命中局部完整条目且使用局部默认值，A 可调用 local-only/global-only；B 的 shared/global-only 命中全局端点，所有 Card 与 A2A 请求计数符合目标归属 | `ToolCallingOpenAiFixture`、`RemoteA2aCatalogFixture` |
| T37 | 局部发现失败不回退且独立恢复 | Given 全局 shared 可达、A 同名局部 shared 初始不可达、B 继承全局 | When 启动后调用 B，确认 A 无同名工具调用，再恢复 A 端点并有界等待重试后调用 A | Then B 始终调用全局目标；A 恢复前不请求全局同名 A2A，恢复后只请求局部目标；两者重试与结果互不影响 | 可启停 `RemoteA2aCatalogFixture`、逐路径计数 |
| T38 | 全局后续发现传播不覆盖局部名称 | Given A 局部覆盖 shared 且继承 global-late，B 仅继承全局；全局 Card 初始不可达 | When 恢复全局端点并有界等待，再让 A/B 调用 global-late 和 shared | Then global-late 对 A/B 可用，B 的 shared 使用全局目标，A 的 shared 仍使用局部目标，调用体和计数无跨实例覆盖 | 可恢复远端 Card/A2A、请求审计 |
| T39 | Intent 候选、委派和恢复使用实例有效目录 | Given Intent 已启用，A/B 同名局部目标指向不同端点且 matcher 策略相同 | When 分别提交唯一语义触发 Intent 委派，再令 A 目标短暂故障并恢复后重试 | Then A/B 的候选、委派校验、实际请求、返回 canary 和恢复均只属于本实例目录，B 不受 A 故障影响 | 确定性 reranker/LLM、可恢复远端 A2A |
| T40 | 同名同 URL 的 TLS Client 缓存隔离 | Given A/B 的同名局部目标经同一 HTTP Card 地址指向同一 HTTPS A2A，A 配置正确 truststore、B 无信任配置 | When A 成功调用后再从 B 调用 | Then B 失败且不产生第二次 HTTPS 业务请求，证明未复用 A 的已信任 Client；Card 发现仍走 HTTP | HTTP Card/HTTPS A2A、临时 PKCS12 |
| T41 | 非法局部远端配置启动失败 | Given 依次配置局部重复 name、空白 name、空白 url | When 每个变体独立启动 hosted-deep | Then 均在 ready 前失败且不发布任一实例目录，不回退全局配置 | 参数化临时 `SutStack`、HTTP/进程探针 |
| T42 | 不支持目录绑定的 Caller 拒绝局部配置 | Given 启用可真实转发但未实现 `bindCatalog` 的兼容 Caller，并给 A 配置局部目标 | When 启动 hosted-deep | Then 在 ready 前失败，不静默忽略局部目录、不发布部分实例 | demo 兼容 Caller 开关、负向 `SutStack` |
| T43 | 同一旧式 Caller 保持全局配置兼容 | Given 启用与 T42 相同 Caller，仅配置全局远端目标 | When 启动并让 A/B 调用全局工具 | Then 进程 ready，A/B 均从全局目录成功完成真实 A2A 调用，未要求 `bindCatalog` | demo 兼容 Caller 开关、工具调用 LLM、远端 A2A |
| T44 | 混合类型注册与发现 | Given 一个 Runtime 注册预算 ReActAgent 和行程 DeepAgent，Core ID 不同 | When 查询清单和两张 Card，核对受管 PID及端口 | Then 清单依次为 react-budget、deep-itinerary，默认前者；Card 技能及 URL 各属目标实例且共用一个受管进程和端口 | hosted-mixed、HTTP/Card/PID 探针 |
| T45 | 混合类型同步完成出差准备 | Given 两个真实 Agent 和真实 LLM ready | When 向预算助手提交三天出差费用核算，再将其预算结论交给行程助手完成执行报告 | Then 两个 Task 完成；预算包含总额 3250、余额 750；行程报告包含首日最晚到站 08:45 和风险应对；输出分别包含业务单号及本职身份，无对端身份 | 业务请求资源、真实 LLM、A2A 探针 |
| T46 | 混合类型流式执行 | Given 同一混合 Runtime ready | When 分别向两实例 SendStreamingMessage 请求费用表及行程风险报告 | Then 两响应为 SSE、有效帧非空、Task 收束为完成；重建最终业务文本符合各自职责且无对端身份 | 真实 LLM、SSE/业务事实探针 |
| T47 | 混合类型 REST 默认与显式选择 | Given 默认 react-budget，deep-itinerary 已注册 | When /v1/query 省略 agent_id 请求费用核算，再显式指定 deep-itinerary 请求行程评审 | Then 默认执行预算助手，显式执行行程助手；业务事实和单号正确且互不串路 | REST 探针、真实业务请求 |
| T48 | 混合类型同步同名会话隔离 | Given 两实例使用相同 contextId，分别提交不同保密业务单号及预算/会议约束 | When 同步完成首轮，再分别仅依据本会话续轮生成预算建议/行程风险补充 | Then 各自续轮只保留本实例业务单号，不泄漏对端单号/身份；不测试同名 Core session reset 完全隔离 | 真实 LLM、同步多轮探针 |
| T49 | 混合类型流式同名会话隔离 | Given 两实例使用相同 contextId，分别流式提交不同业务单号 | When 分别流式续轮请求本项目的预算建议/行程补充 | Then 两条流完成，只含本实例首轮业务单号及角色，不含对端单号/身份 | 真实 LLM、SSE 多轮探针 |
| T50 | 混合类型 Task 归属隔离 | Given 两实例在相同 contextId 完成不同业务请求，取得各自 taskId | When 本实例和对端分别 GetTask | Then 两个 Task 在本实例可查且保留各自业务结果；交叉查询为既有 TaskNotFound，不返回对端内容；原 Task 仍可查询 | A2A Task 探针、真实 LLM |
| T51 | 混合类型正常生命周期 | Given 独立混合栈和隔离工作区 | When 两实例分别完成业务任务后优雅关闭进程 | Then 每实例 start/stop 的 begin/success 均恰好一次，进程退出、HTTP 端口关闭；不以此声称所有线程严格排空或证明内部共享对象关闭次数 | 独立 SutStack、公开生命周期日志、HTTP 探针 |
| T52 | 混合类型共享额度与许可释放 | Given 进程并发额度为 1，透明模型门闩保持预算请求，两个 Agent 均已注册 | When DeepAgent 并发请求行程报告，再释放门闩并重新调用 DeepAgent | Then 持有期间预算任务占用唯一额度，行程请求 HTTP 503；释放后预算与后续行程均完成，证明额度不按类型复制且许可恢复 | 透明真实 LLM 转发门闩、并发 HTTP |
| T53 | 混合类型活动任务汇总与筛选 | Given 额度为 4，两实例业务请求均已到达透明模型门闩并保持 | When 查询无参及各实例 agentId 的 current_active_tasks，再释放并等待完成 | Then 进程汇总为 2、各实例为 1且只包含目标 contextId；共享上限均为 4，条目不新增实例字段；完成后归零 | 双到达门闩、HTTP 快照探针 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| `hosted-mixed` | 真实 SUT | 正式 fat JAR `com.openjiuwen.example:multi-mixed-agents-hosted-demo:0.1.1`；同一 Runtime 托管 ReActAgent 和 DeepAgent。Core 0.1.17、Runtime 0.1.4、扩展 0.1.3；用户确认分支 develop/develop/common。 |
| 真实模型及透明门闩 | 外部依赖/Fixture | 标准 LLM_* 环境变量提供凭据。代理只转发真实请求及响应、记录到达数并有界保持并发，不生成模型答案；所有业务答案来自实际 LLM。工作区由测试自动创建并清理。 |
| 业务请求资源 | Fixture | `src/test/resources/testdata/feat037/mixed-business-requests.json` 固定费用与会议事实，运行时生成独立业务单号，模型输出按稳定事实判定，不逐字匹配。 |
| `hosted-deep` | 真实 SUT | 正式 fat JAR `com.openjiuwen.example:multi-deep-research-hosted-demo:0.1.1`；一个进程注册 A/B 两个真实 DeepAgent。 |
| `deep-research` | 真实 SUT | 正式 `agent-deep-research:0.1.1` 单 Handler 制品，只用于 T28 回归。 |
| `OpenAiEchoFixture` | Fixture | 本地随机端口的 OpenAI 兼容 HTTP 服务，回显 system identity 和用户文本，并提供可观测请求到达、30 秒 watchdog 和显式释放门闩；不实现被测能力。 |
| `ToolCallingOpenAiFixture` | Fixture | 暴露 SUT 提交的工具定义，按测试指令返回一次标准 `tool_calls`，接收工具结果后返回最终 canary；同时提供确定性 reranker 响应以驱动真实 Intent 路径。 |
| `RemoteA2aCatalogFixture` | Fixture | 随机或固定端口提供带 skills 的 Agent Card、同步/流式 JSON-RPC、路由启停、调用计数和请求体捕获；TLS 变体以临时 PKCS12 提供 HTTPS A2A。 |
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
- 局部同名条目必须完整覆盖全局条目；发现失败不回退，全局后续更新只传播未覆盖名称，工具、Intent 候选/委派和实际 A2A 请求必须使用同一有效目录。
- 同名同 URL 的目录不得跨 TLS 配置复用 Client；非法局部配置和不支持绑定的 Caller 在 ready 前失败，同一 Caller 的全局配置仍可实际调用。

- 混合增量保持唯一宿主 PID/端口；两种 Agent 的完成结果须经真实模型链路获得。T48/T49 使用同名会话但不同 Core ID，断言本职历史，不增加 Core reset 全隔离承诺。
- T52/T53 以代理到达作为并发前置证据；不依赖固定 sleep 或模型主动延时，代理不替代任何 Runtime/Agent 行为。

## 8. 执行策略

- 2026-10-30 混合增量：T44–T53 由 `MixedAgentHostingBusinessBlackboxTest` 实现；方法映射为 T44=`t44MixedCatalogAndCards`、T45=`t45MixedSyncBusinessTask`、T46=`t46MixedStreamingBusinessTasks`、T47=`t47MixedRestSelection`、T48=`t48MixedSyncConversationIsolation`、T49=`t49MixedStreamingConversationIsolation`、T50=`t50MixedTaskOwnership`、T51=`t51MixedLifecycle`、T52=`t52MixedSharedQuota`、T53=`t53MixedActiveTaskViews`，逐行绑定 Story `FEAT-037.Txx`、tag `story-feat-037-txx` 和 DisplayName。设计状态 designed-runnable；JUnit 自动化状态 verified。标准 WSL 精确执行 run `20261030-161240`，Surefire 确认 10 PASS、0 FAIL、0 ERROR、0 SKIPPED，类耗时 422.168 秒。首次全仓 testCompile 被无关 `Feat033ConfigFailFastTest` 的 `com.openjiuwen.agents.contextcompression` 缺包阻断；经用户明确授权，仅补充 `com.openjiuwen:agent-core-ext-context-compression:0.1.3` test 依赖后全仓编译及上述执行通过。未改测试框架或排除规则。旧 T01–T43 保留历史证据，本轮不回归。
- 2026-10-30 混合业务证据：PowerShell 完成匹配分支依赖和 fat JAR 构建，WSL 使用同一 Maven 仓执行新类。标准 JUnit 的 T45 使用同一业务单号 `TRIP_T45_ccb48d2557`，先取得 ReActAgent 实际预算结果，再传给 DeepAgent 形成行程报告；成果含预算总额 3250、余额 750、最晚到站 08:45 及风险措施。标准证据为 `target/feature-acceptance-testing/runs/20261030-161240-wsl-summary-mixed-junit.yml`、复制的 Surefire XML、SUT 日志和 `target/feature-acceptance-testing/feat037-mixed/verification-report.md`。此前 WSL 直接公开 HTTP 诊断 T44–T53 同样为 10/10 PASS，保存在 `verification-summary.json`，与标准 JUnit 证据分别记录。环境缺少 Allure CLI，未生成 HTML；原始 Allure 结果和业务附件已保留。
- 新增 Smoke：T44、T45、T51；增量 Full suite：T44–T53。精确执行 `.agents/skills/feature-acceptance-testing/scripts/run-wsl-tests.ps1 -TestSelector MixedAgentHostingBusinessBlackboxTest -Environment openjiuwen`；单场景可用 `MixedAgentHostingBusinessBlackboxTest#t45MixedSyncBusinessTask`。
- 构建在 PowerShell，执行在 WSL JDK 21；共用 `SUT_M2_REPO` 与 `maven.repo.local` 指向同一 Maven 仓。新增模块为 `common/example/multi_mixed_agents_hosted_demo`，使用其 POM 构建安装，无 classifier。凭据仅经 LLM_* 运行环境传入，不写文件/报告；缺凭据时启动前明确失败。HTTP/模型调用 240 秒，门闩 watchdog 180 秒；模型或网络故障按环境/非确定性分类，不改 Oracle。
- 真实业务以项目单号贯穿预算与行程；请求资源自动加载，不需要人工导入。共享栈只启动混合 demo；T51/T52/T53 使用隔离栈和临时工作区。完成后关闭栈、代理、执行器并删除测试临时工作区，核对无新增宿主进程残留。

- T01-T35 保持在 `DeepAgentMultiInstanceHostingBlackboxTest` 且既有 33 个方法已通过，本轮不修改、不选择执行。T36-T43 落在新类 `DeepAgentHostedRemoteCatalogBlackboxTest`，方法名 `txx<ScenarioName>`、Story `FEAT-037.Txx: <场景>`、tag `story-feat-037-txx`、DisplayName `FEAT-037 Txx DeepAgent <场景>`，共 8 个新增唯一方法。
- 自动化状态：T01-T35 的 33 个方法均为 `verified`；T36-T43 的 8 个方法均为 `verified`。
- 2026-09-20 WSL 增量执行：首轮 T41 的 3 个参数化变体与 T42 为 4 PASS；修正测试侧 Agent Card SDK 合同及 ApplicationReady 后发现竞态后，精确重跑受影响的 T36-T40、T43，为 6 PASS、0 FAIL、0 ERROR、0 SKIPPED。两阶段合计 8 个方法、10 次 JUnit invocation 全部通过；首轮其余失败均分类为 `test-code error`，未形成产品缺陷。
- 2026-09-15 WSL 精确复测：T15/T17/T26/T30 为 4 PASS、0 FAIL、0 ERROR、0 SKIPPED；T26 记录 `peerHistoryRetained=false`，与 L2 规定的会话级 release 边界一致。2026-09-12 的四项 FAIL 使用了已被最新 Feature/L2 明确废止的 Oracle，重新分类为 `design error` 历史证据，不再作为产品缺陷。T12、T24 对应的旧需求不属于本期合同。
- 2026-09-20 远端目录增量 Smoke：T36、T37、T39-T43；对应 Full suite 为 `DeepAgentHostedRemoteCatalogBlackboxTest` 的 8 个方法。2026-10-30 混合增量不选择该类或旧类，也不使用 `feat-037` 分组扩展执行范围。
- T01–T35 运行由 `OpenAiEchoFixture` 先启动，再启动 hosted SUT；类结束关闭 SUT 和 fixture。Redis 场景单独启动临时栈并在 finally 中回收。
- 既有 T01–T43 使用 `FEAT037_DEEP_<Txx>_<UUID>` canary 和唯一 contextId；HTTP 60s、模型链路 180s。T44–T53 使用 `TRIP_<场景>_<随机值>` 业务单号，只有同名会话隔离用例刻意在两实例复用 contextId；均有界请求/轮询。
- 标准 JUnit 本地验收必须通过 WSL runner 精确执行，不直接使用 PowerShell Surefire 结果。历史远端目录类的执行命令如下，混合类使用前述精确选择器：

```powershell
.\.agents\skills\feature-acceptance-testing\scripts\run-wsl-tests.ps1 `
  -TestSelector "DeepAgentHostedRemoteCatalogBlackboxTest" `
  -Environment openjiuwen
```

## 附录 A. 差异、门禁与待澄清项

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| 混合 JUnit 编译基线 | 首次 FEAT-033 现有测试依赖缺包 | 用户授权补齐该 test 依赖，已解除；全仓 testCompile 通过，标准 WSL 新类 10/10 PASS | 已满足；依赖保留以支持正常编译 |
| 历史 Oracle 变更 | T15/T17/T26/T30 的旧预期已被最新 Feature/L2 替换 | 原 product confirmed 结论撤销；DeepAgent 四个目标方法 4/4 PASS | 保留本轮 WSL/Surefire 证据，后续按新合同回归 |
| 既有场景证据 | T01-T35 不属于本轮执行范围 | 33/33 已通过并复用原证据；代码保持不变 | 产品、环境或 Oracle 变化时另行确定最小影响集 |
| Feature/L2 远端配置范围 | Feature §6.6 仍称实例 YAML 用于 Card，L2 已定义实例 `remote-agents` | 按用户确认的新增特性和 L2 设计 T36-T43；等待 Feature 反刷 | Feature 明确加入实例远端目录合同 |
| CancelTask | 不生成 T12 方法 | OUT；本期四方法集合不包含 CancelTask | 新 Feature 明确纳入时重新设计独立场景 |
| 全局 owner/同名会话拒绝 | 不生成 T24 方法；按目标实例局部作用域处理 | OUT；不得恢复旧全局 owner Oracle | 新 Feature 明确改变目标局部语义时重新设计 |
