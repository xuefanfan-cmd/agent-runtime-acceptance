---
feature_id: FEAT-011
feature_title: 网关组件客户端调用路由转发
sut: 正式 agent-gateway（DIRECT）-> registry-discovery-center -> Runtime；ReAct/DeepAgent/WorkflowAgent 风险导向 E2E
status: partial
status_note: 当前重连合同 11 项中 7 PASS、4 项产品 blocked；ReAct 与 DeepAgent Gateway E2E 各 PASS 1/1
tags: [blackbox, integration, gateway, feat-011]
---

# FEAT-011 - 网关组件客户端调用路由转发测试设计

> 从 Gateway 统一 A2A facade 调用真实 travel Agent，保留既有入口治理、创建选路、同步/流式直连和
> 同 Task 业务续跑设计，并增量验证本期 `GetTask`、`SubscribeToTask`、Task owner 路由和断流释放行为。

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| Feature | `03-Upstream-Docs/develop/02-features/FEAT-011-client-invocation-route-forwarding.md` |
| L2 | `03-Upstream-Docs/develop/03-architecture/L2-Low-Level-Design/agent-gateway/Feat-Func-011-client-invocation-route-forwarding.md` |
| Feature/L2 仓 | `03-Upstream-Docs/main`，读取日期 2026-08-20 |
| acceptance 仓 | `main@0bafc628d07e5105b50a30f83022d27ac92c6d8d`；本文为隔离工作树设计变更 |
| Gateway fixture 仓 | `common@adc364a39d7153e1322f52474d9b2dc48b53ab47` |
| 测试 Agent/RDC | travel 三 JAR 与 `com.openjiuwen:registry-discovery-center:0.1.0`，由 `application-openjiuwen.yml` 拉起 |

当前权威设计/L2 和最新代码已包含 `GetTask`、`SubscribeToTask` 北向分发、owner 粘滞路由与 SSE Bridge，
不能再沿用“查询/重订阅 deferred”的旧结论。`CancelTask` 仍不交付；代码只用于确认入口与准备度，
SIT Oracle 仍来自权威设计，代码存在不等于真实链路已通过。

### 1.2 范围

本方案保留既有 Gateway DIRECT 黑盒行为，并增量验证：创建取得 taskId 后记录 owner、`GetTask` 只读路由、
`SubscribeToTask` 回原 owner、快照/SSE 透明桥接、断开只释放连接以及终态竞态错误透传。

Gateway 内部过滤器顺序、缓存/索引结构、路由算法代码、注册上架、Agent Card 语义发现、总线路径和 Agent 执行均不作为本特性断言对象。

正式 Gateway JAR 已可本地构建；真实 E2E 仍受 travel fixture JAR、LLM、测试凭据和 RDC 接线门禁。
直接调用 Runtime 只作定位对照，不能证明 FEAT-011 PASS。

## 2. 拓扑与证据

```text
black-box client -> Gateway(DIRECT) -> RDC -> travel-mainplan -> travel-trip -> travel-hotel
```

- acceptance 负责拉起 PostgreSQL、RDC 和 hotel -> trip -> mainplan；两个 mainplan 实例用于验证选择与粘滞时，使用相同 agentId、不同 endpoint canary。
- 本期部署前提已确认：单 Gateway 实例，测试期间不重启。多 Gateway 共享 owner、Gateway 重启恢复均不在本期范围。
- 正常请求只访问 Gateway；client 不配置/获取 runtime endpoint 或 routeHandle。
- 主要证据为 Gateway HTTP/SSE 输出和被选 Agent 的唯一业务/请求 canary；RDC/Agent 请求计数与增量 wire log 是辅助证据。
- 认证、租户或校验失败必须证明 Agent 请求增量为 0；失败正文扫描 endpoint、routeHandle、topic、实例 canary。
- 多 Runtime 合同场景只断言后续查询/订阅回到创建所得 Task 的原 owner；不把 RDC 的候选排序或加权算法作为本特性 Oracle。
- runtime/RDC 连接重置使用 acceptance 现有 `FaultLink.resetPeer()/restore()`；连接/idle timeout 使用独立外部延迟代理（Toxiproxy latency toxic 或等价黑盒 HTTP/SSE 代理）并把 Gateway timeout 配为测试值，不能依赖现有 `FaultLink` 提供尚不存在的 latency API。所有日志证据只读取启动前记录的 offset 之后内容。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-011.direct.create-and-stream` | Feature §2/§4/§5.1.2；L2 IN-2-4 | blackbox | blocked, P0 | implemented | 显式/默认路由、同步/流式超时、SSE、拓扑隐藏、断开释放 | Gateway HTTP/SSE、Agent canary、公开审计 | 需外部 DIRECT 栈 URL |
| `FEAT-011.direct.governance-and-routing-failure` | Feature §2/§5.1.0/§5.1.3；L2 IN-1/5 | blackbox | blocked, P0 | implemented | 鉴权、租户清洗、参数、创建幂等、审计、选路失败 | HTTP 错误、Agent 零增量、审计 | 需外部 DIRECT 栈 URL |
| `FEAT-011.direct.sticky-continuation` | Feature §5.1.2；L2 IN-6/7 | blackbox | blocked, P0 | implemented | 用户输入/工具结果续跑粘滞和关联失败 | taskId、实例请求审计、Task 结果 | 需外部 DIRECT 栈 URL |
| `F011-R01-R03` | 当前 Feature/L2 查询恢复 | contract + E2E | partial, P0 | partial；GetTask owner/无重建、完整快照、未知 owner、失效 routeHandle、Runtime TaskNotFound 透传 PASS；owner TTL 与 Runtime 不可达 blocked | owner 路由、快照透明、重复 GetTask 无副作用、路由解析受控失败、下游 `-32001` 原样保留 | Gateway/Runtime 请求序列、Task 快照、canary | 配置 1 秒 TTL 后仍转发到原 owner，产品缺口保留证据 |
| `F011-S01-S04` | 当前 Feature/L2 SSE 重订阅 | contract + E2E | partial, P0 | partial；owner/无重建与主动断开释放 PASS 2/2，SSE media type 与终态错误 2 blocked | 回原 owner、首帧快照、无新建、断开不 Cancel、终态竞态 | 原始 SSE、下游 method 序列、taskId、Bridge 释放日志 | 两项产品缺口保留证据 |
| `F011-E01` | 当前 Feature 真实链路 | E2E | runnable, P0 | implemented，Failsafe PASS 1/1 | ReAct travel 经 Gateway 断点重连 | 正式制品、业务 canary、拓扑清洗 | 92.995 秒，0 skipped/failure/error |
| `F011-E02` | DeepAgent 长流真实链路 | E2E | runnable, P1 | implemented，Failsafe PASS 1/1 | DeepAgent 经 Gateway 恢复原 owner Task | 正式制品、原 taskId、业务 marker | 120.017 秒；远程节点恰好一次 Oracle partial |
| `FEAT-011.deferred.cancel` | 当前 L2 未实现 | boundary | deferred | design-only | CancelTask | 白名单拒绝证据 | 不生成成功路径测试 |
| `FEAT-011.deferred.gray-fallback` | Feature §2 SHOULD/§6；L2 730 边界 | blackbox | deferred | design-only | 租户/版本/比例/健康灰度与回退 | 待公开策略配置和稳定 Oracle | SHOULD 已处置，不混入基础选路 |

### 当前交付能力追踪

| L2 730 交付能力 | 覆盖用例 |
|---|---|
| 认证、租户、参数、创建幂等、结构化审计 | `FEAT-011.direct.governance-and-routing-failure` |
| 显式/default Agent、RDC 多实例首项、routeHandle 内部解析、选路失败 | 前两条用例合并覆盖 |
| SendMessage、转发/响应超时、SendStreamingMessage、idle timeout、SSE release | `FEAT-011.direct.create-and-stream` |
| 端侧工具结果和 continueInput 同 Task 粘滞、owner 缺失失败 | `FEAT-011.direct.sticky-continuation` |
| 统一 A2A 入口、Task owner 边界、拓扑隐藏 | 三条用例共同覆盖 |
| GetTask 查询、owner 路由、快照透明和重复查询 | `F011-R01-R03` |
| SubscribeToTask、SSE Bridge、断开释放和终态竞态 | `F011-S01-S04` |
| ReAct travel Gateway 真实链路 | `F011-E01` |
| CancelTask | `FEAT-011.deferred.cancel` |
| 灰度与回退 SHOULD | `FEAT-011.deferred.gray-fallback` |

## 4. 详细用例

### FEAT-011.direct.create-and-stream - 直连创建与 SSE 桥接

- **状态/优先级**：blocked, P0；**自动化状态**：implemented，等待外部 DIRECT 栈执行。
- **Story/来源**：Feature §2、§4、§5.1.2；L2 IN-2-4、S2。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的统一 A2A、agentId/default 路由、SSE 桥接和拓扑隐藏；L2 730 DIRECT 交付边界。
- **G**：Gateway `path-mode=DIRECT`；RDC 注册 travel-mainplan；默认 Agent 也指向 travel-mainplan；三 Agent 就绪。
- **W**：
  1. 带 `params.metadata.agentId=travel-mainplan` 发 SendMessage 完整差旅请求，并从 Gateway 到 runtime 的外部网络边界制造转发连接超时；
  2. 不带 agentId 发 SendStreamingMessage，参数化正常持续输出、流式 idle timeout 和 client 主动关闭。
- **T**：同步请求到达显式目标并返回 A2A 兼容完成结果；转发连接超时返回确定的 route/service timeout，不伪造 Task；流式请求到达默认 Agent，帧逐步到达且未被聚合；若 idle timeout 发生在已收到含 taskId 的帧之后，返回明确流错误且不得改报创建 `UNKNOWN`；Gateway 不生成业务 token；client 关闭后桥接资源在轮询窗口释放，但 runtime Task 不被自动取消；所有 client 响应均无 endpoint、routeHandle、实例地址。
- **不应断言**：Gateway 路由算法、连接池、缓存、固定 token/帧数量或 Agent 自然语言全文。
- **失败归类**：目标/响应/SSE 合同不符为 Failure；正式 Gateway/RDC 接线缺失为 Skipped；代理或环境异常为 Error。
- **方法**：`feat011DirectGatewayRoutesExplicitAndDefaultAgentForSyncAndStreaming()`。
- **标签**：类级 `@Feature("FEAT-011: 网关组件客户端调用路由转发")`、`@Tag("feat-011")`、`@Tag("integration")`、`@Tag("blackbox")`；方法级 `@Story("FEAT-011.direct.create-and-stream: 直连创建与 SSE 桥接")`、`@Tag("story-feat-011-direct-create-and-stream")`。
- **DisplayName**：`Feat-011 Gateway 按显式或默认 agentId 直连并桥接 SSE`。

### FEAT-011.direct.governance-and-routing-failure - 治理与选路失败

- **状态/优先级**：blocked, P0；**自动化状态**：implemented，等待外部 DIRECT 栈执行。
- **Story/来源**：Feature §2、§5.1.0/§5.1.3；L2 IN-1/5、S1/S5。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的入口治理、幂等、确定失败、审计和拓扑隐藏；L2 730 公开错误边界。
- **G**：记录 Agent 请求基线；准备合法/无效 Bearer、分别绑定 tenant-A/tenant-B 的合法凭证、tenant-A 请求中的 tenant-B 自报 canary、空候选、默认 Agent 配置缺失和失效 routeHandle 场景。
- **W**：参数化发送无认证、越权、伪造 tenant、空字符串 agentId、其他缺失/非法字段、同 tenant 同 `messageId` 同文/异文重试、另一合法 tenant 使用相同 `messageId`、缺失 `messageId` 的单次请求、未知 agentId、RDC 不可用/handle 失效及无默认 Agent 的创建请求；合法与拒绝请求各增加一行不携带 `traceparent`。
- **T**：
  - 未认证/越权/非法请求返回稳定 4xx，Agent 增量为 0；client 自报 tenant 被丢弃，下游只看到凭证解析的 tenant-A；
  - 同键同文重试回放同一结果且 Agent 只受理一次；同键异文返回幂等冲突；
  - 相同 `messageId` 在另一 tenant 中是独立幂等空间；缺失 `messageId` 的请求不被 Gateway 幂等门禁拒绝，但本用例不对其执行自动重试或声称去重；
  - 无候选、无默认 Agent、RDC 不可用或 handle 无法解析返回 route_not_found/configuration_error/service_unavailable/route_unavailable 等确定错误，不伪造 Task；
  - 治理通过和拒绝均产生结构化审计，包含当时已解析出的关联字段、outcome/rejectStage/目标逻辑标识；G1 拒绝允许 principal/tenant 为空，但必须有自生成 traceId，不得伪造身份字段；审计不包含 Bearer、凭证、完整业务正文、endpoint、routeHandle 或内部异常；缺 `traceparent` 时主路径不因此失败。
- **不应断言**：过滤器顺序、RDC SQL、handle 编码、内部异常文本或特定路由算法。
- **失败归类**：非法请求触达 Agent、幂等冲突错误或信息泄漏为 Failure；公开测试凭证/正式 Gateway 缺失为 Skipped；夹具异常为 Error。
- **方法**：参数化 `feat011GatewayRejectsBeforeForwardingAndReturnsSanitizedRouteFailures()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-011.direct.governance-and-routing-failure: 治理与选路失败")`、`@Tag("story-feat-011-direct-governance-and-routing-failure")`。
- **DisplayName**：`Feat-011 治理或选路失败不触达 Agent 且不泄漏拓扑`。

### FEAT-011.direct.sticky-continuation - 同 Task 粘滞续跑

- **状态/优先级**：blocked, P0；**自动化状态**：implemented，等待外部 DIRECT 栈执行。
- **Story/来源**：Feature §5.1.2；L2 IN-6/7、S3/S4。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的 Task owner 转发边界；L2 730 的原 taskId 粘滞续跑合同。
- **G**：同一 agentId 注册两个 mainplan 实例；测试侧确定性 LLM endpoint 使首轮稳定返回 INPUT_REQUIRED/taskId，经排序选择实例 A；随后改变新建请求的首选候选为实例 B；每次 HTTP 都准备独立 Bearer 和新 messageId。
- **W**：通过同一 Gateway 分别发送带原 taskId/contextId 的用户补充 TextPart，以及 client 实际执行所得的单工具 observation TextPart；再发送缺 Bearer、不存在 taskId、其他 tenant taskId 和无 taskId 的伪 continuation。
- **T**：两类合法续跑都只到实例 A，contextId、parts/metadata 原样保留且两个新 messageId 不混用，原 Task 从 INPUT_REQUIRED 推进至 COMPLETED；不得按当前候选重选实例 B；缺 Bearer 在路由前拒绝；非法关联明确失败且不静默按新建请求成功；Gateway 不解析补充输入或工具结果业务语义。
- **不应断言**：粘滞索引/缓存实现、工具治理或 TaskStore 内部状态。
- **失败归类**：续跑到错误实例、创建新 Task 或越权成功为 Failure；确定性 LLM/正式 Gateway 缺失为 Skipped；测试环境异常为 Error。
- **方法**：`feat011ContinuationReturnsToOriginalTaskOwnerAndRejectsUnknownTask()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-011.direct.sticky-continuation: 同 Task 粘滞续跑")`、`@Tag("story-feat-011-direct-sticky-continuation")`。
- **DisplayName**：`Feat-011 带 taskId 的续跑回到原 Task owner`。

## 5. 本期断点重连增量详细设计

### 5.1 当前实施切片与范围边界

Gateway 公共合同用可控 Runtime 深测 owner 路由且不按 Agent 类型复制；真实 E2E 采用风险导向组合：

```text
对照：Client -> Runtime
主证据：Client -> Gateway -> Runtime
```

两条路径执行相同的“创建流式 Task -> 取得 taskId -> 断流 -> 查询/重订阅 -> 最终收敛”旅程。
FEAT-011 PASS 必须来自 Gateway 主链路；直连结果只用于判断问题位于 Runtime/Client 还是 Gateway。

| SUT 架构 | 当前处置 | 架构特有 Gateway 证据 |
|---|---|---|
| ReActAgent travel | Gateway 真实 E2E，P0 | Gateway 回原 owner；trip/hotel canary 不重复；外部无拓扑字段 |
| DeepAgent | Gateway 长流风险 E2E，P1 | 回原 owner；search/verify 不重复；长流断开只释放 Bridge |
| WorkflowAgent | Runtime 直连风险 E2E；Gateway 以合同层透明快照覆盖 | Workflow 快照字段透明；节点/审批不重复维度由 FEAT-001 直连探针承担 |

本期明确不测：`CancelTask`、创建安全重发/幂等、BUS/Event Bus、多 Gateway 副本、Gateway/Runtime 重启、
owner 迁移、Redis 共享 owner 索引、历史事件逐帧重放和 FEAT-016 注册发现本身。

### 5.2 F011-R01-R03 - GetTask 路由与透明快照

- **Given**：单实例 Gateway 通过 DIRECT 路径把创建请求发送给 Runtime A，并从响应/SSE 取得 taskId；
  Gateway 未重启，可控合同场景另有 Runtime B。
- **When**：通过同一 Gateway 连续或并发调用 `GetTask`，只携带原 taskId。
- **Then**：请求只到 Runtime A，不按 agentId 重新选路；Task 快照的 status、artifacts、history、metadata
  和 terminal message 语义等价透传；响应不含 endpoint、routeHandle 或实例地址；重复查询不触发 Agent、
  不创建新 Task，快照只允许随 Runtime 自然前进。
- **FAIL**：随机路由到 B、owner miss 后静默成功、快照被 Gateway 改写、查询触发执行或泄漏拓扑。

`GW-S02` 在同一方法中用 WORKING 与 COMPLETED 两次 `GetTask` 验证完整快照：`artifacts`、`history`、
`metadata` 在状态推进前后保持语义相等，COMPLETED 的 `status.message` canary 原样返回；查询仍只到原 owner，
无第二次创建、无 `CancelTask`、无拓扑泄漏。Surefire XML PASS 1/1。

`GW-R03` 黑盒变体先经 Gateway 创建 Task 并形成 sticky owner，再通过 RDC 公开注销接口移除该 agent，
随后对原 taskId 发 `GetTask`。当前正式 Gateway 返回 HTTP 503 + `ROUTE_RESOLVE_FAILED`，两个 Runtime
请求计数均不增加，响应不泄漏 routeHandle、endpoint 或实例地址；Surefire XML PASS 1/1。

### 5.3 F011-S01-S04 - SSE 重订阅桥接

- **Given**：原客户端 SSE 已断，Task 仍活动，Gateway 持有 taskId 到 Runtime A 的 owner 映射。
- **When**：通过 Gateway 调用 `SubscribeToTask(params.id=taskId)`，读取首帧后再次主动断开；另覆盖 Task
  在订阅前后进入终态的竞态。
- **Then**：Gateway 只向 Runtime A 发送标准 `SubscribeToTask`；首帧当前快照与挂接后新事件按序透明桥接；
  下游不出现第二个 `SendStreamingMessage` 或 `CancelTask`；taskId 不变；client 断开只释放桥接；终态的
  `UnsupportedOperation`/等价错误被保留，Client 可回退 `GetTask`。
- **FAIL**：重新创建 Task、路由到 B、断开触发 Cancel、伪造空成功流或把终态错误吞掉。

### 5.4 F011-E01 - ReAct Gateway 真实 E2E

- **Given**：正式 Client、Gateway、RDC、Runtime 与 ReAct travel fixture 使用目标新构建；Gateway 单实例且不重启。
- **When**：经 Gateway 创建长任务、取得 taskId、断流、恢复并等待终态；直连 Runtime 执行同一旅程作对照。
- **Then**：Gateway 恢复回原 owner；业务结果和 Task 生命周期与直连对照一致；无重复 trip/hotel 动作；
  Gateway 外部响应无内部拓扑。
- **PASS**：Gateway/Runtime 请求序列、taskId、原始 SSE、业务 canary 和 Surefire/Allure 证据齐全。
- **INCONCLUSIVE**：真实 Agent 未形成活动窗口或 LLM 不可用；公共合同单独判定，不得写成 PASS。
- **blocked/not-run**：目标 JAR、公开方法或环境不可用；开发单测不能替代。

### 5.5 错误判定

| 场景 | 预期 |
|---|---|
| owner 不存在 | 明确 owner unknown/`CONTINUATION_FAILED`，不另选 Runtime |
| sticky routeHandle 无法解析 | HTTP 503 + `ROUTE_RESOLVE_FAILED`，不触达 Runtime、不泄漏拓扑 |
| owner Runtime 不可达 | 明确 route unavailable/forward failure，不伪造快照或空流 |
| Runtime TaskNotFound | 保留 JSON-RPC TaskNotFound，不能只按 HTTP 200 判成功 |
| Task 已终态不可订阅 | 保留 UnsupportedOperation/stream not available，允许 Client 回退 GetTask |
| client 断开 | 释放桥接，不改变 Task 生命周期，不发送 CancelTask |

## 6. 文件、执行与退出标准

保留并复跑既有 `GatewayDirectBlackboxTest` 三个历史测试；当前已存在以下 Java 初稿：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/agent_bus/
  GatewayReconnectBlackboxTest.java
src/test/java/com/huawei/ascend/sit/cases/e2e/reconnect/
  ClientGatewayRuntimeReconnectIT.java
```

执行基线：JDK 21；PowerShell 使用 `.\mvnw.cmd`，WSL/Git Bash 使用 `./mvnw`；默认 Maven 仓库
`~/.m2/repository`；Docker 提供 PostgreSQL/Toxiproxy。正式 Gateway 已可本地构建；当前门禁是 travel fixture
JAR、LLM、测试 Bearer/RDC 接线和真实场景执行。测试输入、证书替身、唯一 canary 和故障规则由测试资源准备。

```powershell
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=feat-011 test
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-011-direct-create-and-stream test
```

测试结束关闭 Gateway/Agent/RDC 和代理，恢复故障链路并确认端口、容器、线程和临时目录清理。
历史 XML：`GatewayReconnectBlackboxTest` 4 项中 2 PASS、2 产品 blocked；改用 WSL 原生 Docker + WSL Maven 后当前 11 项执行结果为
7 PASS、4 skipped/blocked、0 failure、0 error。缺失 `taskId`、未知 `taskId`、完整 Task 快照、失效 routeHandle、Runtime TaskNotFound 透传和 Client 主动断开释放场景 PASS；TTL 失效与 Runtime 不可达场景 blocked。TTL 场景实际启动参数包含 `--gateway.routing.sticky-ttl-ms=1000`，等待 1.3 秒后 `GetTask` 仍到达原 Runtime 并返回 WORKING 快照，不能写成 PASS。
跨租户 `tenantId + taskId` 尚未执行：当前测试 Gateway 只有一个可信 credential→tenant 映射，不能用自报 tenant header/body 伪造第二租户。
ReAct Gateway E2E 已实际执行 PASS 1/1（92.995 秒），DeepAgent Gateway E2E PASS 1/1（120.017 秒），
均为 0 skipped/failure/error；这些结果不覆盖上述 Gateway 合同 blocked。退出标准：Gateway 公共合同、
ReAct/DeepAgent 风险 E2E 与最小受影响回归通过或有明确 INCONCLUSIVE/blocked 证据；
不以直连 Runtime、内部表、开发单测或 fake Gateway 结果宣称 FEAT-011 通过。
