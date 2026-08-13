---
feature_id: FEAT-011
feature_title: 网关组件客户端调用路由转发
sut: 正式 agent-gateway（DIRECT）-> registry-discovery-center -> multi-react-travel-demo
status: designed-dependency-gated
tags: [blackbox, integration, gateway, feat-011]
---

# FEAT-011 - 网关组件客户端调用路由转发测试设计

> 从 Gateway 统一 A2A facade 调用真实 travel Agent，验证 730 交付的入口治理、按 agentId/默认 Agent 选路、同步与流式直连、SSE 释放、选路失败以及带 taskId 的粘滞续跑。

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| Feature | `D:\code-agent\feature-docs\develop\02-features\FEAT-011-client-invocation-route-forwarding.md` |
| L2 | `D:\code-agent\feature-docs\develop\03-architecture\L2-Low-Level-Design\agent-gateway\Feat-Func-011-client-invocation-route-forwarding.md` |
| Feature/L2 仓 | `main@7e1632dd96d49dad05747d8804631234be3cf457`，读取日期 2026-08-06 |
| acceptance 仓 | `main@eb5e3f20ca39f0a8bc647c1ca17b8a637370ce05`，读取日期 2026-08-06；本文为工作区设计变更 |
| 测试 Agent/RDC | travel 三 JAR 与 `com.openjiuwen:registry-discovery-center:0.1.0`，由 `application-openjiuwen.yml` 拉起 |

Feature 中查询、取消、重订阅、UNKNOWN 恢复是长期 MUST，L2 明确 730 不交付；本方案逐项标为 `deferred`，不生成空测试。未查阅产品源码。

### 1.2 范围

本方案只验证 L2 730 交付的 Gateway DIRECT 黑盒行为：认证鉴权、租户清洗、参数校验、创建幂等、结构化审计、显式/默认 Agent 选路、同步/流式直连、SSE 释放、选路失败，以及端侧工具结果和用户补充输入的同 Task 粘滞续跑。

Gateway 内部过滤器顺序、缓存/索引结构、路由算法代码、注册上架、Agent Card 语义发现、总线路径和 Agent 执行均不作为本特性断言对象。

正式 Gateway 可执行 artifact、DIRECT profile、测试 Bearer 与 RDC 接线可用前为 **dependency-gated**。直接调用 mainplan 的 `/a2a/` 不能证明 FEAT-011。

## 2. 拓扑与证据

```text
black-box client -> Gateway(DIRECT) -> RDC -> travel-mainplan -> travel-trip -> travel-hotel
```

- acceptance 负责拉起 PostgreSQL、RDC 和 hotel -> trip -> mainplan；两个 mainplan 实例用于验证选择与粘滞时，使用相同 agentId、不同 endpoint canary。
- 正常请求只访问 Gateway；client 不配置/获取 runtime endpoint 或 routeHandle。
- 主要证据为 Gateway HTTP/SSE 输出和被选 Agent 的唯一业务/请求 canary；RDC/Agent 请求计数与增量 wire log 是辅助证据。
- 认证、租户或校验失败必须证明 Agent 请求增量为 0；失败正文扫描 endpoint、routeHandle、topic、实例 canary。
- 多实例创建只要求采用 RDC 排序首项；本特性不重新测试 RDC 的排序正确性。
- runtime/RDC 连接重置使用 acceptance 现有 `FaultLink.resetPeer()/restore()`；连接/idle timeout 使用独立外部延迟代理（Toxiproxy latency toxic 或等价黑盒 HTTP/SSE 代理）并把 Gateway timeout 配为测试值，不能依赖现有 `FaultLink` 提供尚不存在的 latency API。所有日志证据只读取启动前记录的 offset 之后内容。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-011.direct.create-and-stream` | Feature §2/§4/§5.1.2；L2 IN-2-4 | blackbox | dependency-gated, P0 | design-only | 显式/默认路由、同步/流式超时、SSE、拓扑隐藏、断开释放 | Gateway HTTP/SSE、Agent canary、公开审计 | 启动就绪并入本例 |
| `FEAT-011.direct.governance-and-routing-failure` | Feature §2/§5.1.0/§5.1.3；L2 IN-1/5 | blackbox | dependency-gated, P0 | design-only | 鉴权、租户清洗、参数、创建幂等、审计、选路失败 | HTTP 错误、Agent 零增量、审计 | RDC 排序由 FEAT-016 负责 |
| `FEAT-011.direct.sticky-continuation` | Feature §5.1.2；L2 IN-6/7 | blackbox | dependency-gated, P0 | design-only | 用户输入/工具结果续跑粘滞和关联失败 | taskId、实例请求审计、Task 结果 | 工具治理不在本特性 |
| `FEAT-011.deferred.task-operations` | Feature §2/§4/§6；L2 IN-9/10 | blackbox | deferred | design-only | GetTask、CancelTask、SubscribeToTask、UNKNOWN 同键恢复 | 待 730 后正式接口 | 不生成任何占位测试 |
| `FEAT-011.deferred.gray-fallback` | Feature §2 SHOULD/§6；L2 730 边界 | blackbox | deferred | design-only | 租户/版本/比例/健康灰度与回退 | 待公开策略配置和稳定 Oracle | SHOULD 已处置，不混入基础选路 |

### 当前交付能力追踪

| L2 730 交付能力 | 覆盖用例 |
|---|---|
| 认证、租户、参数、创建幂等、结构化审计 | `FEAT-011.direct.governance-and-routing-failure` |
| 显式/default Agent、RDC 多实例首项、routeHandle 内部解析、选路失败 | 前两条用例合并覆盖 |
| SendMessage、转发/响应超时、SendStreamingMessage、idle timeout、SSE release | `FEAT-011.direct.create-and-stream` |
| 端侧工具结果和 continueInput 同 Task 粘滞、owner 缺失失败 | `FEAT-011.direct.sticky-continuation` |
| 统一 A2A 入口、Task owner 边界、拓扑隐藏 | 三条用例共同覆盖 |
| GetTask、CancelTask、SubscribeToTask、UNKNOWN 同键恢复 | `FEAT-011.deferred.task-operations`（L2 明确 730 不交付） |
| 灰度与回退 SHOULD | `FEAT-011.deferred.gray-fallback` |

## 4. 详细用例

### FEAT-011.direct.create-and-stream - 直连创建与 SSE 桥接

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
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

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
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

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
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

## 5. 文件、执行与退出标准

计划一个文件：`src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/Feat011GatewayDirectBlackboxTest.java`。

执行基线：JDK 21；PowerShell 使用 `.\mvnw.cmd`，WSL/Git Bash 使用 `./mvnw`；默认 Maven 仓库 `~/.m2/repository`；Docker 提供 PostgreSQL/Toxiproxy。travel/RDC 坐标见 §1.1。正式 Gateway 的 group/artifact/version/classifier、DIRECT profile、测试 Bearer 与 acceptance 服务别名尚未交付，是当前门禁。测试输入、证书替身、确定性 LLM 和故障规则由版本化资源自动准备。

```powershell
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=feat-011 test
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-011-direct-create-and-stream test
```

测试结束关闭 Gateway/Agent/RDC 和代理，恢复故障链路并确认端口、容器、线程和临时目录清理。退出标准：730 交付项全部通过或明确门禁，长期 MUST/SHOULD 均有 deferred 处置；不以直连 Agent、内部表或 fake Gateway 结果宣称 FEAT-011 通过。
