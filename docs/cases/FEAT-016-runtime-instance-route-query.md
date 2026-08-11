---
feature_id: FEAT-016
feature_title: 运行时实例路由查询
sut: registry-discovery-center + agent-gateway + multi-react-travel-demo hotel 实例
status: designed-mixed-gates
tags: [blackbox, contract, integration, agent-bus, feat-016]
---

# FEAT-016 - 运行时实例路由查询测试设计

> 以两个真实 `travel-hotel` 实例作为已知目标，通过 registry-discovery-center 的公开查询/解析入口及 Gateway 的实际选路结果，验证当前 L2 的 by-agentId 候选、路由引用、租户隔离、脱敏、版本字段、健康状态和中心显式失败。

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| Feature | `D:\code-agent\feature-docs\develop\02-features\FEAT-016-runtime-instance-route-query.md` |
| L2 | `D:\code-agent\feature-docs\develop\03-architecture\L2-Low-Level-Design\agent-bus\Feat-Func-016-runtime-instance-route-query.md` |
| Feature/L2 仓 | `main@7e1632dd96d49dad05747d8804631234be3cf457`，读取日期 2026-08-06 |
| acceptance 仓 | `main@eb5e3f20ca39f0a8bc647c1ca17b8a637370ce05`，读取日期 2026-08-06；本文为工作区设计变更 |
| SUT/Fixture | `com.openjiuwen:registry-discovery-center:0.1.0` 外部 JAR；测试依赖使用 `classifier=lib`；两个 `com.openjiuwen.example:travel-demo-hotel:0.1.0` 外部 JAR |

L2 状态为 `draft`，当前 MVP 只交付 by-agentId、系统路由视图、ONLINE/DEGRADED、opaque handle、租户隔离和显式中心失败。Feature 的 serviceId/capability 查询、agent-runtime 代理投影、有限可用/版本不匹配投影和中心短时缓存降级按 L2 标为 `deferred`。未查阅产品源码；仓库既有测试和实现不作为 Oracle。

### 1.2 范围

本方案只验证当前 L2 已交付的 `tenantId + agentId` 已知目标查询、ONLINE/DEGRADED 多实例候选、稳定排序、每实例 opaque routeHandle、转发层解析、版本字段、租户隔离、反枚举、物理/Task 信息脱敏以及中心不可用时的显式失败。注册行为仅用于准备 Given；Agent Card 语义发现、复杂调度、Task 查询和内部 Service/Repository/codec 不作为断言对象。

## 2. 前置条件与 Fixture

- `SutStack` 拉起真实 `registry-discovery-center` JAR 和 PostgreSQL；再拉起两个随机端口的 `travel-hotel` 外部 JAR。查询本身不依赖 LLM。
- 数据准备复用 registry 的公开注册入口（属于 setup，不对注册行为作 FEAT-016 断言），把两个 hotel endpoint 注册为同一 tenant/agentId/serviceId、不同实例、不同 weight/version。
- 本测试类覆盖 `agent-bus.registry.mvp.probe-interval-ms`、`probe-stale-before-ms`、`probe-connect-timeout-ms`、`probe-read-timeout-ms` 为有界短测试值（当前通用 openjiuwen 配置的 probe interval 为 600000 ms，不适合该状态用例）。DEGRADED 通过停止其中一个真实 hotel 并轮询 registry 公开查询获得；DRAINING/OFFLINE 只在产品提供公开生命周期控制入口时执行，否则仅该参数分支为 dependency-gated。禁止用 SQL、Repository、反射或修改产品 Java 对象制造状态。
- 黑盒只通过 `GET /api/registry/instances/{tenantId}/{agentId}` 与 `POST /api/registry/route-handle/resolve` 取证。
- routeHandle 对测试/client 保持 opaque：黑盒测试不 Base64 解码；只把原值交给 resolve。
- 每个 endpoint、routeKey、instanceId、Task 字段使用唯一 canary，查询响应递归扫描不得泄漏；resolve 是转发层专用结果，可以返回物理字段。
- Gateway 实际选路参数分支仅在正式 Gateway 提供当前 L2 的 RDC 接线后执行；同租户“无权目标”参数分支仅在公开认证/授权策略可配置后执行。缺少入口时保持对应分支 dependency-gated，不用直调 Service 或数据库替代。
- 仓库现有两个 FEAT-016 测试类既不作为本方案覆盖证据，也不复用其 fixture 或预期；后续测试代码必须从本设计和公开契约独立生成。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-016.query.known-agent-multi-instance` | Feature §2/§4/§5.1.1；L2 §2.1/§4.3 | blackbox | runnable, P0 | design-only | by-agentId、多实例、排序、系统字段、handle、脱敏 | RDC HTTP JSON | 注册只作 Given |
| `FEAT-016.resolve.forwarding-contract` | Feature §2/§5.1.1；L2 §2.3.2-2.3.3 | contract | runnable, P1 | design-only | 转发层专用 resolve、handle 一一对应、跨租户拒绝 | RDC forwarding-only HTTP 结果 | 不冒充 agent/client 黑盒入口 |
| `FEAT-016.gateway.route-consumption` | Feature §4/§5.1.3；L2 §4.1 | blackbox | dependency-gated, P0 | design-only | Gateway 实际查询并消费 opaque handle、client 拓扑透明 | 带 correlation 的 RDC 查询审计、目标实例审计、Gateway 响应 | 仅预查询或 endpoint 命中不足以证明本例 |
| `FEAT-016.query.availability-and-isolation` | Feature §2/§5.1.4-5.1.7；L2 §4.4/§4.6 | blackbox | runnable；lifecycle/auth 分支 dependency-gated, P0 | design-only | ONLINE/DEGRADED、不可路由状态、tenant、反枚举、版本字段 | RDC HTTP JSON/错误 | DRAINING/OFFLINE 仅公开生命周期入口可用时执行 |
| `FEAT-016.failure.explicit-and-recovery` | Feature §4/§5.1.6-5.1.7；L2 §4.5/§7 | blackbox | runnable；缺参分支 dependency-gated, P0 | design-only | 参数/handle 错误、中心不可用显式失败、恢复 | RDC HTTP 错误、恢复后查询 | 不验证未交付本地缓存降级 |
| `FEAT-016.deferred.query-dimensions` | Feature §2/§6；L2 §2.1 | blackbox | deferred | design-only | by-serviceId、by-capability | 待阶段一公共接口 | 不预设方法名 |
| `FEAT-016.deferred.agent-projection` | Feature §2/§4/§6；L2 §2.1/§4.2 | fixture-e2e | deferred | design-only | agent-runtime 代理查询、脱敏五态和版本不匹配排除 | 真实 Agent 经 runtime 工具的投影与下游调用 | registry 直查不能等价覆盖 |
| `FEAT-016.deferred.cached-degradation` | Feature §2/§4/§5.1.6；L2 §4.5 | blackbox | deferred | design-only | 中心短时不可用且本地路由仍有效 | Gateway/runtime 降级事实和目标命中 | MVP 显式失败不能替代 |

### 当前交付能力追踪

| L2 当前交付能力 | 覆盖用例 |
|---|---|
| tenantId+agentId 查询、多实例、逻辑 serviceId/实例区分、稳定排序 | `FEAT-016.query.known-agent-multi-instance` |
| opaque routeHandle | `FEAT-016.query.known-agent-multi-instance` |
| 转发层 resolve | `FEAT-016.resolve.forwarding-contract` |
| Gateway 实际选路一致性 | `FEAT-016.gateway.route-consumption` |
| ONLINE/DEGRADED 候选、DRAINING/OFFLINE 排除、版本字段由调用方判断 | `FEAT-016.query.availability-and-isolation` |
| tenant 隔离、无权/不可见反枚举、跨 tenant 禁止 | `FEAT-016.query.availability-and-isolation` |
| Task/物理细节脱敏、错误脱敏 | 前两条用例及 failure 用例共同覆盖 |
| 参数/handle 错误、中心不可用显式失败、中心恢复 | `FEAT-016.failure.explicit-and-recovery` |
| serviceId/capability 查询 | `FEAT-016.deferred.query-dimensions` |
| agent-runtime 代理、路由可用性投影、有限可用/版本不匹配 | `FEAT-016.deferred.agent-projection` |
| 中心短时不可用且本地信息有效的降级 | `FEAT-016.deferred.cached-degradation` |

## 4. 详细用例

### FEAT-016.query.known-agent-multi-instance - 已知 Agent 多实例查询

- **状态/优先级**：runnable, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§4、§5.1.1；L2 §2.1/§4.3。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的已知目标、多实例、逻辑/实例区分、opaque handle 和脱敏；L2 MVP 的 by-agentId、字段和排序公开合同。
- **G**：tenant-A 注册两个真实 hotel 实例，agentId=`travel-hotel`、serviceId 相同，weight 和 heartbeat 顺序可区分；记录 endpoint/routeKey/instance/task canary。
- **W**：按 `tenant-A + travel-hotel` 通过 RDC HTTP 查询；本例不调用 resolve，也不经 Gateway。
- **T**：
  - 返回完整候选集合而非服务端代选单项，顺序为 `weight DESC, last_heartbeat DESC`；同 agentId/serviceId 多实例不覆盖；
  - 每项 routeHandle 非空且互不相同；serviceId、health、weight、region、maxConcurrency、agentName、frameworkType 和版本字段按注册事实可见且不串实例；
  - 查询 JSON 不含 endpointUrl、routeKey、instanceId 明文，也不含 Task state/hierarchy/progress/context/orchestration；
  - routeHandle 对测试保持 opaque，不做 Base64 解码，也不从编码内容推导预期。
- **不应断言**：SQL/RLS、handle 编码、Repository/codec 或 Gateway 选路。
- **失败归类**：候选、排序、字段或脱敏不符为 Failure；RDC/hotel 制品缺失为 Skipped；环境异常为 Error。
- **方法**：`feat016KnownAgentQueryReturnsEveryOpaqueRoutableInstance()`。
- **标签**：类级 `@Feature("FEAT-016: 运行时实例路由查询")`、`@Tag("feat-016")`、`@Tag("integration")`；方法级 `@Tag("blackbox")`、`@Story("FEAT-016.query.known-agent-multi-instance: 已知 Agent 多实例查询")`、`@Tag("story-feat-016-query-known-agent-multi-instance")`。
- **DisplayName**：`Feat-016 已知 agentId 返回全部脱敏多实例候选和独立路由引用`。

### FEAT-016.query.availability-and-isolation - 可用性、版本与反枚举

- **状态/优先级**：runnable；lifecycle/auth 分支 dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§5.1.4-§5.1.7；L2 §4.4/§4.6。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的租户隔离、反枚举和可用性边界；L2 MVP 的 ONLINE/DEGRADED、版本字段和 DRAINING/OFFLINE 排除。
- **G**：tenant-A 有 ONLINE 与可由停止真实 hotel 触发的 DEGRADED 候选且版本字段不同；tenant-B 注册相同 agentId；另准备不存在 agentId。公开认证和生命周期入口可用时，再准备同租户不可见目标及 DRAINING/OFFLINE 候选。
- **W**：分别以 tenant-A、tenant-B、未知 agentId 查询；用 tenant-B 解析 tenant-A 的 handle；调用方在结果侧比较一个不兼容 contractVersion/capabilityVersion；在上述公开入口就绪时参数化查询无权目标及 DRAINING/OFFLINE。
- **T**：
  - ONLINE/DEGRADED 作为候选并原样表达；DRAINING/OFFLINE 不进入查询结果；
  - 两租户结果不串扰，跨租户 resolve 返回 `tenant_isolation_violation`；未知/不可见/无可用候选统一为 `200 []`，不泄漏存在性；
  - 版本字段原样可见，调用方可据此排除不兼容候选；
  - 所有查询、空结果与错误正文递归扫描均不含 endpointUrl、routeKey、instanceId、数据库 key、topic、探活细节、Task state/hierarchy/progress/context/orchestration 或其他 tenant canary。
- **不应断言**：未交付的五态投影、版本自动过滤、探活实现或数据库状态。
- **失败归类**：隔离/反枚举/可用性合同不符为 Failure；生命周期或授权入口缺失的参数分支为 Skipped；环境异常为 Error。
- **方法**：`feat016AvailabilityVersionAndTenantSemanticsAreProjectedWithoutEnumeration()`。
- **标签**：`@Story("FEAT-016.query.availability-and-isolation: 可用性版本与反枚举")`、`@Tag("story-feat-016-query-availability-and-isolation")`、`@Tag("blackbox")`。
- **DisplayName**：`Feat-016 可用性和版本保持脱敏且查询严格隔离租户`。

### FEAT-016.failure.explicit-and-recovery - 错误与中心恢复

- **状态/优先级**：runnable；缺参分支 dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §4、§5.1.6/§5.1.7；L2 §4.5/§7。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的显式中心失败/恢复和错误脱敏；L2 MVP 的具体公开错误码。
- **G**：保存一个合法 handle；准备畸形/旧 handle、已注销实例 handle。只有当正式 HTTP 契约提供可被该 endpoint 接收的“缺 tenantId/agentId”传输表示时，才增加缺参分支；不得把路由器 404 误判成 L2 的 `invalid_request`。
- **W**：参数化请求错误输入；保持 registry-center 进程存活，通过 Testcontainers pause 或 `FaultLink` 切断 RDC 到 PostgreSQL 的连接后查询已知 hotel；恢复 PostgreSQL/连接并重试。
- **T**：在正式缺参表示可达时，缺参数 fail-fast 为 `invalid_request`；畸形/旧 handle 为 `malformed_handle`；已删除实例为 `entry_not_found`；中心依赖不可用时 HTTP 明确返回 `discovery_unavailable` 或等价 5xx，不返回候选或猜测 endpoint；恢复后重新查询得到权威候选。错误正文不包含其他 tenant 或物理 canary。
- **不应断言**：本地缓存降级、连接池实现、内部异常字符串或 Repository 状态。
- **失败归类**：错误语义、恢复或脱敏不符为 Failure；正式缺参表示不可达的分支为 Skipped；容器/网络异常为 Error。
- **方法**：`feat016ErrorsAreExplicitAndQueriesRecoverAfterRegistryRestart()`。
- **标签**：`@Story("FEAT-016.failure.explicit-and-recovery: 错误与中心恢复")`、`@Tag("story-feat-016-failure-explicit-and-recovery")`、`@Tag("blackbox")`。
- **DisplayName**：`Feat-016 中心不可用显式失败且恢复后回到权威查询`。

### FEAT-016.resolve.forwarding-contract - 转发层路由引用解析

- **状态/优先级**：runnable, P1；**自动化状态**：design-only。
- **Story/来源**：Feature §2/§5.1.1；L2 §2.3.2-§2.3.3。
- **测试类型**：contract。
- **Oracle 来源**：Feature 的 opaque route handle 边界与 L2 的 forwarding-only resolve 公开合同。
- **G**：通过公开查询取得两个实例的 opaque handle，并准备跨 tenant、畸形、旧版和已注销 handle。
- **W**：以转发层测试身份把每个 handle 原值提交公开 resolve 入口。
- **T**：合法 handle 一一恢复对应 instanceId、endpointUrl、routeKey、contractVersion；非法样本返回文档化错误；解析结果不进入 client/agent 响应。
- **不应断言**：handle 编码内容、codec/Repository 实现或 client 可直接使用 resolve。
- **失败归类**：映射/隔离/错误合同不符为 Failure；RDC 制品缺失为 Skipped；夹具异常为 Error。
- **方法**：`feat016ForwardingResolveKeepsHandleOpaqueAndTenantScoped()`。
- **标签**：`@Tag("contract")`、`@Story("FEAT-016.resolve.forwarding-contract: 转发层路由引用解析")`、`@Tag("story-feat-016-resolve-forwarding-contract")`。
- **DisplayName**：`Feat-016 转发层解析 opaque handle 且严格保持租户边界`。

### FEAT-016.gateway.route-consumption - Gateway 消费路由查询

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §4/§5.1.3；L2 §4.1。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature/L2 的 Gateway 已知目标查询、opaque handle 消费和 client 拓扑透明合同。
- **G**：RDC 注册同 tenant/agentId 的两个可区分实例；正式 Gateway 只接收逻辑 agentId；查询审计和目标实例请求审计可按 correlation 对齐。
- **W**：client 经 Gateway 发起一次已知目标调用，不由测试侧预注入 routeHandle 或 endpoint。
- **T**：同一 correlation 同时存在 Gateway 发起的 RDC 公开查询和排序首项的目标请求；client 响应不含 routeHandle、endpoint 或 instance。仅预查询或仅 endpoint 命中均不足以通过。
- **不应断言**：Gateway 缓存、选择器实现、resolve 调用次数或 routeHandle 内部结构。
- **失败归类**：绕过 RDC、命中错误实例或拓扑泄漏为 Failure；正式 Gateway-RDC 接线缺失为 Skipped；审计夹具异常为 Error。
- **方法**：`feat016GatewayQueriesRdcAndConsumesOpaqueRoute()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-016.gateway.route-consumption: Gateway 消费路由查询")`、`@Tag("story-feat-016-gateway-route-consumption")`。
- **DisplayName**：`Feat-016 Gateway 查询 RDC 并消费 opaque 路由且不泄漏拓扑`。

## 5. 文件、执行与退出标准

黑盒计划集中到一个文件：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/agent_bus/
  Feat016RouteQueryExternalBlackboxTest.java
```

不得引用、调用或复制仓库现有两个 FEAT-016 测试类来实现上述用例；其通过结果也不能替代本方案验收。

执行基线：JDK 21；PowerShell 使用 `.\mvnw.cmd`，WSL/Git Bash 使用 `./mvnw`；默认 Maven 仓库 `~/.m2/repository`；Docker 提供 PostgreSQL。RDC 使用 `com.openjiuwen:registry-discovery-center:0.1.0` 外部可执行 JAR和 `classifier=lib` 测试合同；travel hotel 坐标见 §1.1。Gateway 分支在正式 Gateway 坐标/profile 和 RDC 接线加入 acceptance 前保持门禁。

独立验收必须按新类精确执行，避免 `feat-016` 分组把仓库已有用例混入本方案证据：

```bash
.\mvnw.cmd -Dtest.env=openjiuwen -Dtest=Feat016RouteQueryExternalBlackboxTest test
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-016-query-known-agent-multi-instance test
```

测试结束恢复 PostgreSQL/RDC 连接，关闭 RDC/hotel/Gateway 进程和容器，删除临时注册数据并确认端口释放。退出标准：MVP 能力通过或明确门禁，Feature 其余 MUST 均有 deferred 处置；resolve contract、RDC 黑盒和 Gateway 黑盒分别统计，不以直查 RDC 替代 agent-runtime 代理投影或整体选路。
