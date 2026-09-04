---
feature_id: DFX-001
test_type: reactagent
scope: full-link-tracing
deployable_units: [agent-runtime-java, agent-solution]
sut: multi-react-travel-demo with full-link tracing and audit enabled
features: [DFX-001, FEAT-017, FEAT-022]
updated: 2026-09-04
---

# multi-react-travel-demo 验收：tracer 全链路与审计

## 1. 测试目标

验证 ReactAgent 在 A2A、Bus 与 Custom REST 入口下的 trace 单源传播、跨 Runtime 执行树、Task 续跑关联、append-only 审计、租户保护、关键决策留证，以及轨迹存储故障隔离。所有判定均来自公开请求、真实下游出站 header、只读轨迹/审计查询和业务响应。

## 2. 范围与非范围

范围：

- 合法、缺失或非法 `traceparent` 的入口处理及两跳 Runtime 传播。
- A2A、Bus、Custom REST 三类入口的 trace 单源；REST 入口边界（不产生可查询审计，特性档 §2 OUT 裁定）。
- 跨任务执行树、并行委托、Task 续跑和 Runtime 重启恢复。
- 多轮快照、缺洞、并发序号、租户保护和会话重置保留。
- 授权、工具、审批、跨边界交接和生命周期迁移的审计留证。
- 默认关闭、Redis 缺失/写失败、队列背压、非法请求和 TTL 到期的故障隔离。

非范围：

- 原始 Chain-of-Thought、固定自然语言、内部 Redis key、Filter/装饰器顺序和后台线程。
- 新建第二套 Run 生命周期管理接口；`/manage/trajectory/runs` 只作为只读执行树观察面。
- FEAT-017 的响应回流语义、FEAT-022 的业务入口完整合同，以及 Collector/Exporter 本身的正确性。
- 不断言 WARN 日志文本内容；唯一例外：背压丢弃事件经框架托管日志面（`ManagedSutInstance.logFile()`）断言 WARN 存在性、不断言具体字符串（见 C4）。§8「不用日志模拟通过条件」指 Given 侧不得用日志造假触发，与该观察面不冲突。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/DFX-001-trajectory-observability.md` | 定义 trace 单源、执行树、审计、隔离和非范围。 |
| `develop/03-architecture/L2-Low-Level-Design` 下 DFX-001 相关设计 | 定义 header、公开查询面、标识关系、存储与故障隔离语义。 |
| FEAT-001、FEAT-008、FEAT-017、FEAT-022 相关设计 | 定义 A2A Task、交互续接、Bus 信封和 REST 入口的公开触发边界。 |
| 测试仓 `FullLinkTracingReactAgentBlackboxTest` | 仅用于确认 Fixture、公开触发方式和场景到方法的映射。 |

## 4. 部署拓扑

```text
A2A / REST / Bus test driver
  -> travel-mainplan Runtime
       -> travel-trip Runtime
            -> travel-hotel Runtime
  -> edpa-plan-agent -> parallel completed A2A peers

Runtime tracing -> Redis
observer -> /manage/trajectory/runs + /manage/trajectory/audit
```

- 两个透明转发探针分别观察 mainplan→trip 和 trip→hotel 的真实出站 `traceparent`，只转发流量，不生成 Task 或轨迹。
- Bus 场景只向 hotel 投递请求并查询 DFX 执行树，不把响应回流作为前提。
- 故障代理只影响 `runtime:*` 轨迹写入，业务 Redis 与公开业务链路保持可观察。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| A1 | 上游 traceparent 为唯一源 | G：三 Agent、轨迹开关和首跳透明探针就绪 | W：携带合法 W3C `traceparent` 调用 mainplan | T：真实出站 header 合法且 trace_id 等于入口值，入口未另造 trace | A2A driver + header probe |
| A2 | 缺失或非法 traceparent 降级 | G：三 Agent和审计查询就绪 | W：参数化发送无 header 与非法 header 请求 | T：业务不因格式失败；生成合法 trace_id 且相关记录一致标记降级 | A2A driver + audit query |
| A3 | header 优先于兼容 metadata | G：header 与 metadata 携带不同合法 trace | W：触发真实委托并观察出站 header | T：出站仅继承入口 header trace，不使用 metadata trace | raw A2A + header probe |
| A4 | Runtime 到 Runtime 两跳传播 | G：三跳委托和两处透明探针就绪 | W：mainplan 调 trip、trip 调 hotel | T：两跳均携带合法 `traceparent`，trace_id 与入口一致 | three Agents + two probes |
| A5 | 三跳跨任务执行树 | G：三跳委托真实发生 | W：按 traceId 查询执行树 | T：至少三个不同 run_id，parent_run_id 可还原 mainplan→trip→hotel | runs query probe |
| A6 | 标识职责分离 | G：记录同时含 trace、run 与 toolCallId | W：查询节点、边和审计 | T：三类标识稳定且不互相替代，子执行锚定父 Task 树 | runs/audit probe |
| A7 | 同轮并行委托执行树 | G：计划 Agent 可同轮发起两个远端委托 | W：提交两笔独立操作并查询父节点直接子集 | T：每次委托产生独立子 run，父子边完整且不串接 | edpa-plan-agent + completed peers |
| A8 | Bus trace 单源进入执行轨迹 | G：hotel Bus Runtime、broker/relay 和 runs 查询就绪 | W：发布带指定 traceId 的客户端调用事件到 hotel | T：hotel 消费并执行；执行树节点使用信封 traceId | Bus producer + hotel + runs query |
| A9 | REST 入口 trace 单源 | G：Custom REST 入口和首跳探针就绪 | W：携带合法 `traceparent` 发送可触发委托的 REST 请求 | T：REST 业务响应有效，出站 header trace_id 等于 REST 入口值 | REST driver + header probe |
| A10 | 首跳 contextId 与 taskId 续跑 | G：首轮消息不带 taskId/contextId | W：首轮后仅携带返回 taskId 续跑 | T：首轮生成并持久化 contextId；续跑不新建 context，trace 与 Task 不串键 | A2A/query probe |
| A11 | Runtime 重启后 trace 恢复 | G：首轮已进入等待并持久化 | W：重启 mainplan 后以 taskId 续跑且不带 traceparent | T：恢复原 trace_id，轮次序号递增，不降级生成新 trace | managed restart + Redis |
| A12 | 合法 trace 的非降级审计 | G：三 Agent、Redis 与审计查询就绪 | W：携带合法 header 调用并查询审计 | T：审计使用传入 trace_id 且降级标志为 false | audit query |
| A13 | REST 入口边界（不产生可查询审计） | G：REST、Redis 与审计查询就绪 | W：携带合法 `traceparent` 调用 REST 并查询 runs/audit | T：REST 请求成功；出站委托沿用入口 trace_id；`/manage/trajectory/runs` 与 `/manage/trajectory/audit` 不返回 REST 执行记录（特性档 §2 OUT，2026-08-29 裁定：REST 审计无外部查询面）。注：落地测试代码 `restIngressIsStoredWithChannelAndUpstreamTrace` 当前仍断言 REST 审计可查询（裁定前旧口径，2026-09-04 代码核实），与本行期望冲突，按 testplan 附录 B.3 登记处置，代码修正前该用例实跑结果不作验收依据 | REST driver + audit query |
| B1 | 多轮 trace 一致与快照追加 | G：首轮进入等待 | W：同 Task/context 续接并回放 | T：各轮 trace_id 相同、run_id 不同、seq 递增，旧快照不覆盖 | multi-round journey + audit query |
| B2 | 多轮审批恢复回放 | G：旅程包含工具和审批中断恢复 | W：完成多轮后查询回放 | T：快照有序，审批发起/恢复及工具委托摘要归属正确轮次 | approval journey |
| B3 | 审计缺洞显式标记 | G：受控制造序号预占后记录缺失 | W：查询会话回放 | T：响应明确标记缺洞，不静默跳号或伪造快照 | Redis fault preparation + audit query |
| B4 | 错租户查询拒绝 | G：tenant-A 已有快照 | W：tenant-B 与 tenant-A 分别查询同一 conversation | T：错误租户被拒绝且无记录内容，正确租户可读取 | tenant-scoped audit query |
| B5 | 安全决策留证 | G：可确定触发一次允许和一次拒绝 | W：执行授权请求并查询对应轮次 | T：每次决策包含 outcome、resource、reason、tenant 与 trace 关联 | scoped authorizer + audit query |
| B6 | 不可逆工具调用留证 | G：真实差旅工具可用 | W：执行完整业务并查询轮次 | T：记录工具名、入参摘要、耗时与成败，不包含原始 CoT | travel Agents + audit query |
| B7 | 审批决策留证 | G：Task 可进入等待并恢复 | W：触发审批发起与恢复后查询 | T：分别记录内容摘要、时间和恢复输入类别 | approval journey + audit query |
| B8 | 跨边界交接留证 | G：mainplan 委托 trip | W：执行委托并查询决策 | T：记录源 run、目标 Agent、目标 run、toolCallId，且可关联执行树边 | delegation journey + audit query |
| B9 | 生命周期迁移留证 | G：Task 从提交进入结果性终态 | W：执行并查询审计 | T：公开状态迁移含 from/to、时间和 run_id，无越序回退 | lifecycle query |
| B10 | 会话重置保留审计 | G：目标和对照会话已有快照 | W：调用公开 reset 后重复查询 | T：两会话旧快照均保留至 TTL，reset 不主动销毁审计 | reset/query probe |
| B11 | 并发轮次 seq 唯一有序 | G：同一会话可并发续轮 | W：并发发送两个续轮并回放 | T：成功落盘轮次 seq 唯一递增且互不覆盖，竞争不破坏业务调用 | concurrent A2A driver |
| B13 | runs 查询租户保护 | G：runs 查询使用公开租户身份 | W：错误租户与正确租户查询同一 trace | T：错误租户被拒绝且不泄露节点，正确租户可读取 | tenant auth/query probe |
| B14 | seq 竞争超限隔离 | G：预占连续候选 seq | W：首轮后制造竞争并续跑 | T：业务续跑不受影响；无法落盘的轮次不伪造 seq，既有快照不变 | Redis contention fixture |
| C1 | 默认关闭零行为 | G：不启用轨迹能力 | W：携带唯一 trace 执行业务并查询 | T：业务正常，且不产生该 trace 的执行树或审计记录 | isolated default stack |
| C2 | 开启但 Redis 未配置 | G：轨迹启用且无 Redis client | W：启动、执行业务并查询 | T：SUT 和业务可用，不产生轨迹数据 | isolated no-Redis stack |
| C3 | Redis 写失败隔离与恢复 | G：故障代理只拒绝轨迹写 | W：故障期间执行业务，恢复后再次执行 | T：故障请求业务结果不变；恢复后新轨迹可查询，无需重启 | RESP fault proxy |
| C4 | 写队列背压隔离 | G：小队列且轨迹写可被暂停 | W：并发执行直至队列满，解除压力后再执行 | T：Agent 响应不被轨迹写阻塞：8 并发全部返回 taskId，业务无失败；框架托管日志面（`ManagedSutInstance.logFile()`）出现背压丢弃 WARN（存在性断言，不断言具体字符串；L2 批二 §7.2「背压丢弃 + WARN，绝不阻塞执行线程」）；压力解除后新轨迹记录可查 | pressure proxy + concurrent driver |
| C5 | 非 JSON-RPC 采集失败隔离 | G：轨迹启用 | W：向 A2A 入口发送非 JSON-RPC 载荷 | T：轨迹采集不引入额外 5xx，入口保持原协议错误语义 | raw HTTP probe |
| C6 | 轨迹 TTL 到期一致性 | G：独立栈配置短 TTL | W：先查询到记录，等待 TTL 后重复查询 | T：记录与索引一致到期，不返回残缺跨租户数据，业务不受影响 | short-TTL stack |

注：B12 缺号沿用，不重排（编号只增不改）。

失败归类（每条挂了说明什么）：

- **A 组（标识单源与传播）**：挂了说明链路标识契约被破坏——出站 header 缺失 / 格式非法 / trace_id 不等于入口值、执行树节点缺失或父子边错误、降级标记与预期不符，均判 Failure；SUT 部署未就绪、探针未启动、委托链路未建立为环境 Error，判 INCONCLUSIVE，不记缺陷。
- **B 组（执行树与审计）**：挂了说明执行树 / 审计契约被破坏——快照缺失、seq 跳号无显式标记、错租户可读、决策记录缺字段、旧快照被覆盖，均判 Failure；Redis 非注入性不可达、查询端点未部署、journey 夹具未就绪为环境 Error（INCONCLUSIVE）。
- **C 组（故障隔离）**：挂了说明故障隔离契约被破坏——故障期间业务结果改变、产生额外 5xx、恢复后新轨迹不可查、关闭档产生轨迹数据，均判 Failure；故障注入未生效（代理未到达轨迹写路径）或隔离栈未拉起为环境 Error（INCONCLUSIVE），注入未生效时不得判 Failure。

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| travel-demo-mainplan / trip / hotel | 真实 SUT | 从 mainplan 入口触发跨 Runtime 委托，承载 A2A、REST 和 Bus trace 断言。 |
| edpa-plan-agent | 真实辅助 SUT | 触发同轮并行委托，目标为测试内标准 A2A 完成端点。 |
| Redis | 真实依赖 | 承载轨迹与审计；故障场景仅控制轨迹前缀写入。 |
| A2A/REST/Bus drivers | Fixture | 生成隔离标识并通过公开入口触发场景。 |
| forwarding header probes | Fixture | 透明转发并记录真实出站 `traceparent`，不生成协议结果。 |
| runs/audit/reset observers | Fixture | 仅使用公开只读查询与重置入口，不读取内部存储。 |
| RESP fault/pressure proxy | Fixture | 控制轨迹写失败、延迟和竞争，不影响业务 Redis。 |
| managed restart | Fixture | A11 的 Runtime 受控重启；重启后 Redis 记录仍在。 |

## 7. 关键链路断言

- 合法上游 trace 是唯一源；缺失或非法时生成合法 trace 并显式标记降级。
- header 传播、执行树和审计快照是三个独立观察面，不得互相替代。
- trace_id 串联业务，run_id 表达执行因果，toolCallId 关联具体委托，三者不能混用。
- 快照 append-only、seq 唯一递增、租户不匹配拒绝，关键安全与业务决策独立留证。
- 轨迹存储、队列或采集失败不得改变 Agent 业务结果。
- 不记录凭证、完整业务正文、内部地址、异常堆栈或原始 Chain-of-Thought。

## 8. 执行策略

- 先验证入口 trace 单源和真实出站 header，再验证执行树、审计、多轮恢复和故障隔离。
- 每个场景生成唯一 tenant、conversation、message、task、trace 和 canary；并发场景仍保持输入可区分。
- LLM 场景只断言状态、标识、结构和唯一 canary，不逐字匹配自然语言。
- 查询使用有界轮询；故障场景先建立正常基线，并验证代理只影响目标轨迹路径。
- Bus、REST、审批、安全决策和并行委托场景必须具备对应公开入口或正式 Fixture，不能用内部调用或日志模拟通过条件。
