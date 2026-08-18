---
feature_id: FEAT-016
feature_title: 运行时实例路由查询
level: test-design
module: agent-bus
sut: registry-discovery-center
status: designed
tags: [blackbox, contract, integration, agent-bus, feat-016, test-design]
---

# FEAT-016 运行时实例路由查询 — 测试设计

> 本文把特性文档（`FEAT-016-runtime-instance-route-query.md`，需求基准）与设计文档（`Feat-Func-016-runtime-instance-route-query.md`，L2 实现基准）落地为一份**可执行的测试设计**。以 registry-discovery-center 的公开查询 / 解析入口为黑盒取证面，验证当前 MVP 交付的 by-agentId 候选、opaque route handle、转发层解析、租户隔离、反枚举、版本字段、可用性状态与中心显式失败；并把设计 §7 错误处理表逐条映射为可断言用例。

---

## 0. 文档定位

- **是什么**：一份针对 FEAT-016 的测试设计（Test Design），定义用例矩阵、详细用例（G/W/T）、脱敏断言策略、错误处理覆盖矩阵与覆盖追溯。
- **不是什么**：不是 AgentDemo（AgentDemo 是单进程自包含的探索性验证，见 `instance-route-query-demo`）；不是已交付的验收用例代码。本文是**设计与验收基线**，后续测试代码必须从本设计独立生成。
- **Oracle 来源**：特性文档的 MUST 项 + 设计文档的接口契约 / 行为承诺 / 错误处理表。**未查阅产品源码；仓库既有测试与实现不作为 Oracle**。

---

## 1. 设计依据与输入快照

### 1.1 输入产物

| 输入 | 锁定版本 / 路径 | 角色 |
|---|---|---|
| 特性文档（需求） | `FEAT-016-runtime-instance-route-query.md`（scope v0730，2026-07-21） | 需求基准；MUST 项是测试覆盖的来源 |
| 设计文档（L2） | `Feat-Func-016-runtime-instance-route-query.md`（status: draft，2026-07-10） | 实现基准；接口契约 / 行为承诺 / §7 错误处理表是断言 Oracle |
| SUT | `com.openjiuwen:registry-discovery-center:0.1.0`（外部 JAR；测试依赖 `classifier=lib`） | 被测对象 |
| 公开取证入口 | `GET /api/registry/instances/{tenantId}/{agentId}`；`POST /api/registry/route-handle/resolve` | 黑盒唯一观测面 |

### 1.2 交付状态对齐

设计文档（L2）当前为 `draft`，MVP 只交付：by-agentId 查询、ONLINE/DEGRADED 候选、opaque handle（`v2:` 6 字段）、转发层 resolve、版本字段（调用方判断）、租户隔离、反枚举、Task / 物理脱敏、中心不可用显式失败。

下列按 L2 §8 标为 **deferred / 阶段一**，本设计对应分支保持门禁或延迟：
- by `serviceId` / `capability` 维度查询
- 中心短时不可用降级（本地缓存 + 有效性窗口）
- 「有限可用」细分状态
- agent-runtime 代理投影层（投影层不在 registry 单元）
- 版本不匹配在 discovery 层强制过滤

---

## 2. 测试范围

### 2.1 覆盖范围（In-Scope）

- 已知目标（`tenantId + agentId`）路由查询：多实例候选、稳定排序、系统字段、每实例独立 handle；
- opaque route handle：对测试 / client 保持不透明；只把原值交 resolve；
- 转发层 resolve：合法 handle 一一恢复物理端点；非法样本返回文档化错误；
- 可用性状态：ONLINE / DEGRADED 作为候选；DRAINING / OFFLINE 排除；
- 版本字段：`contractVersion` / `capabilityVersion` 原样可见，调用方可据此排除；
- 租户隔离：跨 tenant resolve 拒绝；两租户结果不串扰；
- 反枚举：无权限 / 不可见 / 不存在 / 无可用候选统一为空结果，不泄漏存在性；
- 物理与 Task 脱敏：查询 / resolve / 错误正文递归扫描不含 endpointUrl / routeKey / instanceId / DB key / topic / 探活细节 / Task state；
- 错误语义：缺参 fail-fast、畸形 handle、entry 不存在、中心不可用显式失败与恢复。

### 2.2 显式排除（Out-of-Scope）

| 排除项 | 依据 | 处置 |
|---|---|---|
| Agent Card 语义发现 / 能力搜索 / 语义画像 | 特性 §5.2；属 FEAT-015 | 不测 |
| event-bus 查询 registry | 特性 §5.2 | 不测 |
| agent 直连 registry | 特性 §5.1.2 / §5.2 | 不测（agent 经 agent-runtime） |
| client 获得物理地址 | 特性 §5.2 | 不测 |
| route handle 编码内容 / codec / Repository 实现 | 设计 §2.3.3「RouteHandleCodec 不离开包」 | 黑盒不 Base64 解码 |
| 本地缓存降级实现 | 设计 §8 阶段一 | deferred |
| Gateway 内部缓存 / 选择器实现 | 设计 §4.1 | 只验选路结果一致性 |
| SQL / RLS / 数据库状态 | 设计 §2.3.3 | 不直查 DB |

---

## 3. 测试策略与方法论

### 3.1 测试类型分层

| 层 | 类型 | 取证方式 | 覆盖目标 |
|---|---|---|---|
| L1 | 契约（contract） | 转发层 resolve 的 HTTP 契约 | handle 一一对应、错误码、租户边界 |
| L2 | 黑盒（blackbox） | RDC 公开查询 / resolve HTTP JSON | 候选、排序、字段、脱敏、可用性、隔离、反枚举 |
| L3 | 端到端（e2e，dependency-gated） | client → gateway → RDC → 目标实例审计对齐 | Gateway 实际选路消费 opaque handle |

### 3.2 黑盒边界

- 取证**只经** `GET /api/registry/instances/{tenantId}/{agentId}` 与 `POST /api/registry/route-handle/resolve`；
- **禁止**用 SQL、Repository、反射、修改产品 Java 对象制造状态或取证；
- 可用性状态（DEGRADED）通过**停止一个真实目标实例 + 轮询 RDC 公开查询**获得；DRAINING / OFFLINE 仅在产品提供**公开生命周期控制入口**时执行，否则该参数分支 dependency-gated。

### 3.3 断言策略：Canary + 递归脱敏扫描

> 脱敏是 FEAT-016 的核心机制契约（特性 §5.1.4、设计 §2.3.3）。单点字段断言不足以覆盖，必须用**唯一 canary + 递归扫描**。

- **Canary 注入**：每个 endpoint、routeKey、instanceId、Task 字段使用全局唯一 canary 字符串（如 `canary-ep-001`、`canary-rk-wealth-v1`）；
- **递归扫描**：对查询响应、resolve 结果、错误正文的 JSON 树递归扫描，断言**不得命中**任何其他 tenant 或物理 / Task canary；
- **扫描范围**：`endpointUrl` / `routeKey` / `instanceId` / 数据库 key / topic / 探活细节 / Task state / hierarchy / progress / context / orchestration；
- **routeHandle 例外**：handle 本身对测试保持 opaque，**不 Base64 解码**，只把原值交给 resolve；resolve 是转发层专用结果，可返回物理字段（但只对转发层可见，不进入 client / agent 响应）。

---

## 4. 测试架构与 Fixture

### 4.1 SUT 栈

```
SutStack
├── PostgreSQL（Docker / Testcontainers）          ── RLS + set_config 依赖
├── registry-discovery-center:0.1.0（外部 JAR）    ── 被测对象
├── travel-hotel 实例 ×2（外部 JAR，随机端口）     ── 真实目标，用于 ONLINE/DEGRADED
└── FaultLink / Testcontainers pause              ── 切断 RDC↔PG 制造中心不可用
```

### 4.2 测试替身策略

| 替身 | 用途 | 边界 |
|---|---|---|
| 真实 `travel-hotel` ×2 | 承载同 tenant/agentId/serviceId、不同实例 / weight / version | 不对其业务逻辑作 FEAT-016 断言 |
| `FaultLink` / Testcontainers pause | 制造 RDC→PG 断链 | 保持 registry 进程存活，只切断 DB 连接 |
| InMemory 仓储（仅 AgentDemo 用） | 单进程自包含探索 | **不作为本设计验收证据**，仅用于早期 Bug 探测 |

### 4.3 数据准备（Given 侧）

- 数据准备复用 registry 的**公开注册入口**（`POST /register`，属 setup，不对注册行为作 FEAT-016 断言）；
- tenant-A 注册两个 hotel 实例：相同 `agentId=travel-hotel` / `serviceId=hotel-svc`，不同 `instanceId`（host-port）、不同 `weight` / `contractVersion`，心跳顺序可区分；
- tenant-B 注册相同 `agentId`（用于跨租户隔离断言）；
- 另准备不存在 `agentId`（用于反枚举断言）；
- 记录每个 endpoint / routeKey / instanceId / Task 字段的 canary。

> 配置覆盖：`agent-bus.registry.mvp.probe-interval-ms` / `probe-stale-before-ms` / `probe-connect-timeout-ms` / `probe-read-timeout-ms` 设为有界短测试值（默认 600000ms 不适合状态用例）。

---

## 5. 测试环境与前置条件

1. JDK 21；Maven 离线（`-o`）或在线，视环境；
2. Docker 可用（PostgreSQL + Testcontainers pause）；
3. `registry-discovery-center:0.1.0` 外部 JAR 与 `classifier=lib` 测试合同就绪；
4. 两个 `travel-hotel` 外部 JAR 就绪；
5. `-Dtest.env=openjiuwen`（解析占位符）；
6. 查询本身**不依赖 LLM**（hotel 实例只作路由目标，不验业务）；
7. 正式 Gateway 坐标 / profile 与 RDC 接线加入前，Gateway 分支保持门禁。

---

## 6. 用例矩阵

> 子用例 ID 命名：`FEAT-016.<group>.<case>`。`状态` 列：runnable / dependency-gated / deferred。

| TC# | 子用例 ID | 组 | 类型 | 状态 | 优先级 | 覆盖（scope §2 MUST） | 主要证据 |
|---|---|---|---|---|---|---|---|
| TC-01 | `query.known-agent-multi-instance` | 查询 | blackbox | runnable | P0 | 已知目标查询、多实例候选、运行时实例标识、路由引用 | RDC HTTP JSON |
| TC-02 | `query.candidate-ordering` | 查询 | blackbox | runnable | P0 | 多实例候选（稳定排序） | RDC HTTP JSON 顺序 |
| TC-03 | `query.availability-states` | 查询 | blackbox | runnable；lifecycle 分支 gated | P0 | 健康与可用性 | RDC HTTP JSON |
| TC-04 | `query.version-fields` | 查询 | blackbox | runnable | P1 | 版本约束 | RDC HTTP JSON 版本字段 |
| TC-05 | `resolve.forwarding-contract` | 解析 | contract | runnable | P1 | 路由引用（一一对应） | resolve HTTP 结果 |
| TC-06 | `resolve.handle-opaque` | 解析 | contract | runnable | P0 | 路由引用（opaque） | handle 不解码 |
| TC-07 | `tenant.isolation-and-cross-tenant` | 隔离 | blackbox | runnable | P0 | 租户隔离 | resolve 错误码 |
| TC-08 | `anti-enumeration.empty-results` | 反枚举 | blackbox | runnable | P0 | 反枚举保护 | RDC 空结果 |
| TC-09 | `desensitization.system-view` | 脱敏 | blackbox | runnable | P0 | 物理细节透明、Task 状态隔离 | 递归 canary 扫描 |
| TC-10 | `error.missing-params` | 错误 | blackbox | runnable；缺参表示 gated | P0 | 错误语义（fail-fast） | RDC HTTP 400 |
| TC-11 | `error.malformed-handle` | 错误 | contract | runnable | P0 | 错误语义 | resolve HTTP 400 |
| TC-12 | `error.entry-not-found` | 错误 | contract | runnable | P0 | 错误语义 | resolve HTTP 404 |
| TC-13 | `failure.center-down-and-recovery` | 可用性 | blackbox | runnable | P0 | 中心不可用显式失败、恢复 | RDC HTTP 5xx→200 |
| TC-14 | `version.incompatible-exclusion` | 版本 | blackbox | runnable | P1 | 版本约束（调用方判断） | DTO 版本字段 |
| TC-15 | `gateway.route-consumption` | 集成 | blackbox | dependency-gated | P0 | gateway 直连语义 | 审计对齐 |
| TC-16 | `deferred.service-capability-query` | 查询 | blackbox | deferred | — | serviceId/capability 查询 | 待阶段一 |
| TC-17 | `deferred.cached-degradation` | 可用性 | blackbox | deferred | — | 中心短时不可用降级 | 待阶段一 |
| TC-18 | `deferred.agent-runtime-projection` | 集成 | fixture-e2e | deferred | — | 路由可用性投影 | 投影层在 agent-runtime |

---

## 7. 错误处理覆盖矩阵

> 逐条映射设计文档 §7 错误处理表。`期望` 列是 Oracle（来自设计文档），`用例` 列指向第 8 节详细用例。

| # | 错误场景 | 触发条件 | 期望 HTTP | 期望 error code | 用例 | 备注 |
|---|---|---|---|---|---|---|
| E1 | 查询参数缺 `tenantId` | path variable 为空 | 400 | `invalid_request` | TC-10 | fail-fast，不进默认租户 |
| E2 | 查询参数缺 `agentId` | path variable 为空 | 400 | `invalid_request` | TC-10 | |
| E3 | 跨 tenant 解析 | resolve 调用方 tenant 与 handle 内 tenant 不一致 | 400 | `tenant_isolation_violation` | TC-07 | 不跨 tenant fallback |
| E4 | route handle 畸形 | 缺 `v2:` 前缀 / base64 损坏 / JSON 缺字段 / 旧 4 字段或 `v1:` 5 字段 | 400 | `malformed_handle` | TC-11 | baseline-breaking |
| E5 | entry 不存在 | handle 指向 PK 无记录 | 404 | `entry_not_found` | TC-12 | |
| E6 | 目标不存在 / 不可见 / 无可用实例 | search 无 ONLINE/DEGRADED 行 | 200 | （空 List） | TC-08 | 反枚举：与「不存在」不可区分 |
| E7 | 中心不可用（MVP） | PG 连接失败 / 超时 | 5xx | （调用方包装 `discovery_unavailable`） | TC-13 | 显式失败 |
| E8 | 中心不可用 + 本地信息可用（阶段一） | 同上 + 缓存命中 | 200 | （`degraded` 标记） | deferred | 阶段一 |
| E9 | 版本不匹配 | DTO contractVersion 不满足 | 200 | （DTO 含版本字段） | TC-14 | 调用方判断排除 |
| E10 | 路由失败 | 转发层投递失败 | （转发层语义） | `route_unavailable` | 不在本设计（转发层） | 不向 agent/client 暴露物理失败 |

---

## 8. 详细用例设计（G/W/T）

> 每个用例给出：状态 / 优先级 / 来源 / 类型 / Oracle / G-W-T / 不应断言 / 失败归类 / 方法名建议 / 标签 / DisplayName。
> PASS / FAIL / INCONCLUSIVE 三态：FAIL = 行为不符但语义不符（Failure）；Skipped = 前置缺失；Error = 环境异常。

---

### TC-01 `FEAT-016.query.known-agent-multi-instance` — 已知 Agent 多实例查询

- **状态 / 优先级**：runnable, P0。
- **来源**：特性 §2 / §4 / §5.1.1；设计 §2.1 / §4.3。
- **类型**：blackbox。
- **Oracle**：特性的已知目标、多实例、逻辑 / 实例区分、opaque handle、脱敏；L2 的 by-agentId、字段、排序公开合同。
- **G**：tenant-A 注册两个真实 hotel 实例，`agentId=travel-hotel`、`serviceId=hotel-svc` 相同，`weight` 与心跳顺序可区分；记录 endpoint / routeKey / instance / task canary。
- **W**：按 `tenant-A + travel-hotel` 经 `GET /api/registry/instances/tenant-A/travel-hotel` 查询；本例不调用 resolve，也不经 Gateway。
- **T**：
  - 返回**完整候选集合**（≥2 项），而非服务端代选单项；
  - 每项 `routeHandle` 非空且**互不相同**；
  - `serviceId` / `health` / `weight` / `region` / `maxConcurrency` / `agentName` / `frameworkType` / 版本字段按注册事实可见且**不串实例**；
  - 查询 JSON **不含** `endpointUrl` / `routeKey` / `instanceId` 明文，也不含 Task state / hierarchy / progress / context / orchestration；
  - `routeHandle` 对测试保持 opaque，不做 Base64 解码，也不从编码内容推导预期。
- **不应断言**：SQL / RLS、handle 编码、Repository / codec、Gateway 选路。
- **失败归类**：候选 / 排序 / 字段 / 脱敏不符 = Failure；RDC / hotel 制品缺失 = Skipped；环境异常 = Error。
- **方法名**：`feat016KnownAgentQueryReturnsEveryOpaqueRoutableInstance()`。
- **标签**：类级 `@Feature("FEAT-016: 运行时实例路由查询")` `@Tag("feat-016")` `@Tag("integration")`；方法级 `@Tag("blackbox")` `@Story("FEAT-016.query.known-agent-multi-instance")`。
- **DisplayName**：`Feat-016 已知 agentId 返回全部脱敏多实例候选和独立路由引用`。
- **PASS**：T 全满足。**FAIL**：候选被代选单项 / handle 重复 / 字段串实例 / 泄漏物理或 Task 字段。**INCONCLUSIVE**：RDC 不可达。

---

### TC-02 `FEAT-016.query.candidate-ordering` — 多实例稳定排序

- **状态 / 优先级**：runnable, P0。
- **来源**：设计 §2.3.1（`ORDER BY weight DESC, last_heartbeat DESC`）。
- **类型**：blackbox。
- **G**：tenant-A 注册 3 个实例：`weight=100/100/80`，高 weight 两条心跳新鲜度可区分。
- **W**：查询 `tenant-A + travel-hotel`，取响应数组顺序。
- **T**：
  - 顺序为 `weight DESC, last_heartbeat DESC`（高 weight 在前；同 weight 时新鲜心跳在前）；
  - 多次查询顺序稳定一致（naive pick-first 落在高权重、新鲜心跳实例）。
- **不应断言**：选择器算法实现、Gateway 缓存。
- **失败归类**：顺序不符 / 不稳定 = Failure。
- **方法名**：`feat016CandidatesAreStablyOrderedByWeightThenHeartbeat()`。
- **PASS**：顺序与稳定均满足。**FAIL**：顺序错乱或不稳定。

---

### TC-03 `FEAT-016.query.availability-states` — 可用性状态过滤

- **状态 / 优先级**：runnable；lifecycle（DRAINING/OFFLINE）分支 dependency-gated, P0。
- **来源**：特性 §2 / §5.1.4-5.1.5；设计 §4.4。
- **类型**：blackbox。
- **G**：tenant-A 有 ONLINE 与可由**停止真实 hotel**触发的 DEGRADED 候选；公开生命周期入口可用时再准备 DRAINING / OFFLINE 候选。
- **W**：分别以 ONLINE / DEGRADED / DRAINING / OFFLINE（后者仅公开入口可用时）查询。
- **T**：
  - `ONLINE` / `DEGRADED` 作为候选原样表达；
  - `DRAINING` / `OFFLINE` **不进入**查询结果（SQL `status IN ('ONLINE','DEGRADED')` 排除）；
  - 响应中 `health` 字段值与状态一致。
- **不应断言**：未交付的「有限可用」五态投影、探活实现。
- **失败归类**：状态过滤不符 = Failure；生命周期入口缺失 = Skipped（该分支）。
- **方法名**：`feat016OnlyOnlineAndDegradedAreDiscoverable()`。
- **PASS**：ONLINE/DEGRADED 可见、DRAINING/OFFLINE 排除。**FAIL**：DRAINING/OFFLINE 泄漏进候选。

---

### TC-04 `FEAT-016.query.version-fields` — 版本字段可见性

- **状态 / 优先级**：runnable, P1。
- **来源**：特性 §2 版本约束；设计 §2.3.2 / §4.4。
- **类型**：blackbox。
- **G**：tenant-A 两实例 `contractVersion` / `capabilityVersion` 不同。
- **W**：查询并读每项的 `contractVersion` / `capabilityVersion`。
- **T**：
  - 版本字段原样可见，与注册事实一致；
  - 调用方可据此排除不兼容候选（MVP 不在 discovery 层强制过滤，DTO 携带字段即可）。
- **不应断言**：discovery 层强制版本过滤（阶段一）。
- **失败归类**：版本字段缺失 / 错值 = Failure。
- **方法名**：`feat016VersionFieldsAreVisibleForCallerJudgement()`。

---

### TC-05 `FEAT-016.resolve.forwarding-contract` — 转发层路由引用解析

- **状态 / 优先级**：runnable, P1。
- **来源**：特性 §2 / §5.1.1；设计 §2.3.2-2.3.3。
- **类型**：contract。
- **G**：通过公开查询取得两个实例的 opaque handle；另准备跨 tenant、畸形、旧版、已注销 handle 样本。
- **W**：以**转发层测试身份**把每个 handle 原值提交 `POST /api/registry/route-handle/resolve`（body 含 `routeHandle` + `tenantId`）。
- **T**：
  - 合法 handle **一一恢复**对应 `instanceId` / `endpointUrl` / `routeKey` / `contractVersion`（与注册事实对齐）；
  - 两个实例的 resolve 结果互不串扰；
  - 非法样本返回文档化错误（见 TC-07 / TC-11 / TC-12）；
  - resolve 结果**不进入 client / agent 响应**（本测试只验转发层合同）。
- **不应断言**：handle 编码内容、codec / Repository 实现、client 可直接使用 resolve。
- **失败归类**：映射 / 隔离 / 错误合同不符 = Failure；RDC 制品缺失 = Skipped；夹具异常 = Error。
- **方法名**：`feat016ForwardingResolveRestoresPhysicalEndpointPerHandle()`。
- **PASS**：合法一一恢复、非法文档化错误。**FAIL**：handle 错配实例 / 物理字段串扰。

---

### TC-06 `FEAT-016.resolve.handle-opaque` — route handle 不透明性

- **状态 / 优先级**：runnable, P0。
- **来源**：特性 §2 路由引用；设计 §2.3.3「route handle 对 agent/client 不透明」。
- **类型**：contract。
- **G**：取得合法 handle。
- **W**：把 handle 原值（不解码）交 resolve；同时尝试从 handle 字符串肉眼 / 正则推断物理字段。
- **T**：
  - handle 字符串本身**不含明文** endpoint / routeKey / instanceId（应只有 `v2:` + base64 编码）；
  - 测试**不**对 handle 做 Base64 解码来推导预期，只把原值交 resolve 取证。
- **不应断言**：handle 编码格式细节（编码格式是实现细节，仅验不透明性）。
- **失败归类**：handle 明文泄漏物理字段 = Failure。
- **方法名**：`feat016RouteHandleStaysOpaqueToCaller()`。

---

### TC-07 `FEAT-016.tenant.isolation-and-cross-tenant` — 租户隔离与跨租户拒绝

- **状态 / 优先级**：runnable, P0。
- **来源**：特性 §2 租户隔离 / §5.1.7；设计 §2.3.3 / §4.6 / §7（E3）。
- **类型**：blackbox。
- **G**：tenant-A 与 tenant-B 各注册相同 `agentId=travel-hotel`；取得 tenant-A 某 handle。
- **W**：
  1. 以 tenant-B 调 `GET /instances/tenant-B/travel-hotel`（查自己）；
  2. 以 tenant-B 身份对 tenant-A 的 handle 调 `POST /route-handle/resolve`（body `tenantId=tenant-B`）。
- **T**：
  - 两租户各自查询结果**不串扰**（tenant-B 看不到 tenant-A 的实例 / canary）；
  - 跨 tenant resolve 返回 **400 `tenant_isolation_violation`**，不跨 tenant fallback；
  - 错误正文递归扫描不含 tenant-A 的任何 canary。
- **不应断言**：RLS 实现细节、连接池。
- **失败归类**：隔离 / 错误码不符 = Failure。
- **方法名**：`feat016CrossTenantResolveIsRejectedAndResultsDoNotLeak()`。
- **PASS**：跨租户被拒 + 不串扰。**FAIL**：跨租户返回物理端点 / 错误码不符 / canary 泄漏。
- **风险备注**：实测中若返回 403 而非 400、或 error code 命名偏离 `tenant_isolation_violation`，记为 Bug（错误码 / 状态码语义不符设计 §7）。

---

### TC-08 `FEAT-016.anti-enumeration.empty-results` — 反枚举保护

- **状态 / 优先级**：runnable, P0。
- **来源**：特性 §2 反枚举保护 / §5.1.7；设计 §4.6。
- **类型**：blackbox。
- **G**：tenant-A 有 ONLINE 候选；另准备「无权限目标」「不可见目标」「不存在 agentId」「全部 OFFLINE」四类样本。
- **W**：分别查询这四类。
- **T**：
  - 四类均返回 **`200 []`**（空数组），与「目标不存在」**不可区分**；
  - 不返回「存在但不可访问」暗示；响应正文不泄漏存在性；
  - 空结果正文递归扫描不含任何其他 tenant / 物理 canary。
- **不应断言**：授权策略实现（公开认证入口可用前 gated）。
- **失败归类**：反枚举不符 = Failure；授权入口缺失 = Skipped。
- **方法名**：`feat016InvisibleAndAbsentTargetsReturnIndistinguishableEmptyResults()`。
- **PASS**：四类统一空结果。**FAIL**：任一类返回存在性暗示或非空。

---

### TC-09 `FEAT-016.desensitization.system-view` — 系统路由视图脱敏

- **状态 / 优先级**：runnable, P0。
- **来源**：特性 §5.1.4 / §2 物理细节透明 / Task 状态隔离；设计 §2.3.2 / §2.3.3。
- **类型**：blackbox。
- **G**：tenant-A 注册实例，注入全局唯一 canary 到 endpoint / routeKey / instanceId / Task 字段。
- **W**：查询 `GET /instances/tenant-A/travel-hotel`；对响应 JSON 树递归扫描。
- **T**：
  - 查询 JSON 递归扫描**不含**：`endpointUrl` / `routeKey` / `instanceId` 明文、数据库 key、topic、探活细节、Task state / hierarchy / progress / context / orchestration；
  - `serviceId`（逻辑服务标识，多实例共享）**可见**——这是设计 §2.3.2 允许的；
  - 错误正文（TC-07 / TC-11 / TC-12 的错误响应）同样递归扫描不含其他 tenant 或物理 canary。
- **不应断言**：投影层（投影在 agent-runtime，不在 registry）。
- **失败归类**：脱敏不符 = Failure。
- **方法名**：`feat016SystemViewIsDesensitizedAndTaskStateIsolated()`。
- **PASS**：物理 / Task canary 全部未命中。**FAIL**：任一 canary 命中。

---

### TC-10 `FEAT-016.error.missing-params` — 缺参数 fail-fast

- **状态 / 优先级**：runnable；缺参传输表示分支 dependency-gated, P0。
- **来源**：特性 §5.1.7；设计 §2.3.3 / §7（E1/E2）。
- **类型**：blackbox。
- **G**：保存合法 handle。
- **W**：参数化请求错误输入：缺 `tenantId`、缺 `agentId`。
- **T**：
  - 缺 `tenantId` → **400 `invalid_request`**，fail-fast，不进默认租户、不跨租户搜索；
  - 缺 `agentId` → **400 `invalid_request`**；
  - **不得**把路由器 404 误判成 L2 的 `invalid_request`（缺参表示必须是正式 HTTP 契约可被 endpoint 接收的传输表示；缺该表示时分支 gated）。
- **不应断言**：本地缓存、连接池、内部异常字符串。
- **失败归类**：错误语义不符 = Failure；正式缺参表示不可达 = Skipped。
- **方法名**：`feat016MissingTenantOrAgentIdFailsFastWithInvalidRequest()`。
- **PASS**：缺参即 400 `invalid_request`。**FAIL**：进入默认租户 / 返回 404 误判 / 错误码偏离。

---

### TC-11 `FEAT-016.error.malformed-handle` — 畸形 handle

- **状态 / 优先级**：runnable, P0。
- **来源**：特性 §5.1.7；设计 §2.3.3 / §7（E4）。
- **类型**：contract。
- **G**：准备畸形样本：缺 `v2:` 前缀、base64 损坏、JSON 缺字段、旧 4 字段格式、`v1:` 5 字段格式。
- **W**：以转发层身份对每个样本调 resolve。
- **T**：
  - 全部返回 **400 `malformed_handle`**；
  - baseline-breaking：旧 4 字段 / `v1:` 5 字段**立即失效**，不接受兼容回退；
  - 错误正文不含物理 / tenant canary。
- **不应断言**：codec 内部异常字符串。
- **失败归类**：错误码 / 状态码不符 = Failure。
- **方法名**：`feat016MalformedAndLegacyHandlesAreRejectedAsMalformedHandle()`。
- **PASS**：全部 400 `malformed_handle`。**FAIL**：返回 404 / 500 / 错误码命名偏离（如 `MALFORMED_HANDLE` 大写或 `route_not_found`）。
- **风险备注**：实测中畸形 handle 返回 404 而非 400 `malformed_handle`，记为 Bug（状态码 / 错误码语义不符设计 §7）。

---

### TC-12 `FEAT-016.error.entry-not-found` — entry 不存在

- **状态 / 优先级**：runnable, P0。
- **来源**：特性 §5.1.7；设计 §7（E5）。
- **类型**：contract。
- **G**：取得合法 handle 后，**注销**其指向的实例（经公开 deregister 入口），使 handle 指向的 PK 无记录。
- **W**：对已注销 handle 调 resolve（同 tenant）。
- **T**：
  - 返回 **404 `entry_not_found`**；
  - 错误正文不含其他 tenant / 物理 canary。
- **不应断言**：handle 编码、Repository 状态。
- **失败归类**：错误码 / 状态码不符 = Failure。
- **方法名**：`feat016DeregisteredHandleReturnsEntryNotFound()`。
- **PASS**：404 `entry_not_found`。**FAIL**：错误码命名偏离（如 `ENTRY_NOT_FOUND` 大写）或返回 400 / 500。
- **风险备注**：实测中 error code 大小写偏离（`ENTRY_NOT_FOUND` vs `entry_not_found`），记为 Bug（错误码命名不符设计 §7 约定）。

---

### TC-13 `FEAT-016.failure.center-down-and-recovery` — 中心不可用显式失败与恢复

- **状态 / 优先级**：runnable, P0。
- **来源**：特性 §4 / §5.1.6-5.1.7；设计 §4.5 / §7（E7）。
- **类型**：blackbox。
- **G**：保存一个合法 handle；保持 registry-center 进程存活；记录已知 hotel 的可路由 handle。
- **W**：
  1. 通过 Testcontainers pause 或 `FaultLink` 切断 RDC→PostgreSQL 连接；
  2. 查询已知 hotel；
  3. 恢复 PostgreSQL / 连接；
  4. 重试查询。
- **T**：
  - 中心依赖不可用时 HTTP 明确返回 **5xx**（调用方包装 `discovery_unavailable`），**不返回候选或猜测 endpoint**；
  - **不跨 tenant fallback**；
  - 恢复后重新查询得到**权威候选**（回到正常路径）；
  - 错误正文不含其他 tenant 或物理 canary。
- **不应断言**：本地缓存降级（阶段一）、连接池实现、内部异常字符串。
- **失败归类**：错误语义 / 恢复 / 脱敏不符 = Failure；容器 / 网络异常 = Error。
- **方法名**：`feat016ErrorsAreExplicitAndQueriesRecoverAfterRegistryRestart()`。
- **PASS**：不可用时显式 5xx + 恢复后权威查询。**FAIL**：不可用时猜测路由 / 跨租户 fallback / 恢复后仍失败。

---

### TC-14 `FEAT-016.version.incompatible-exclusion` — 版本不匹配排除

- **状态 / 优先级**：runnable, P1。
- **来源**：特性 §2 版本约束；设计 §4.4 / §7（E9）。
- **类型**：blackbox。
- **G**：tenant-A 两实例 `contractVersion` 不同（如 `v1` 与 `v2`）。
- **W**：查询得到两项；调用方侧比对 `contractVersion`，排除不兼容项。
- **T**：
  - DTO 携带版本字段，调用方可据此排除；
  - MVP **不**在 discovery 层强制过滤（不兼容项仍在候选中，由调用方判断）——这是设计 §4.4 的当前行为，**不是缺陷**；
  - 阶段一加版本过滤后该断言需同步调整。
- **不应断言**：discovery 层强制版本过滤（阶段一）。
- **失败归类**：版本字段缺失 = Failure。
- **方法名**：`feat016IncompatibleVersionExcludableByCallerJudgement()`。

---

### TC-15 `FEAT-016.gateway.route-consumption` — Gateway 消费路由查询（dependency-gated）

- **状态 / 优先级**：dependency-gated, P0。
- **来源**：特性 §4 / §5.1.3；设计 §4.1。
- **类型**：blackbox。
- **G**：RDC 注册同 tenant/agentId 两个可区分实例；正式 Gateway 只接收逻辑 `agentId`；查询审计与目标实例请求审计可按 correlation 对齐。
- **W**：client 经 Gateway 发起一次已知目标调用，**不由测试侧预注入** routeHandle 或 endpoint。
- **T**：
  - 同一 correlation 同时存在：① Gateway 发起的 RDC 公开查询；② 排序首项的目标实例请求；
  - client 响应**不含** routeHandle / endpoint / instance；
  - 仅预查询或仅 endpoint 命中**均不足以通过**本例。
- **不应断言**：Gateway 缓存、选择器实现、resolve 调用次数、handle 内部结构。
- **失败归类**：绕过 RDC / 命中错误实例 / 拓扑泄漏 = Failure；正式 Gateway-RDC 接线缺失 = Skipped；审计夹具异常 = Error。
- **方法名**：`feat016GatewayQueriesRdcAndConsumesOpaqueRoute()`。
- **解锁条件**：正式 Gateway 坐标 / profile 与 RDC 接线加入 acceptance。

---

### TC-16 `FEAT-016.deferred.service-capability-query` — serviceId/capability 维度查询（deferred）

- **状态 / 优先级**：deferred。
- **来源**：特性 §2；设计 §2.1 / §8。
- **覆盖**：by `serviceId` / `capability` 查询。
- **说明**：MVP 仅 by `agentId`；阶段一随 `capability` 字段重建引入 `searchByServiceId` / `searchByCapability`，**不预设方法名**。届时本用例激活。

---

### TC-17 `FEAT-016.deferred.cached-degradation` — 中心短时不可用降级（deferred）

- **状态 / 优先级**：deferred。
- **来源**：特性 §2 / §5.1.6；设计 §4.5 / §8。
- **覆盖**：中心短时不可用且本地信息仍有效时维持已知目标调用。
- **说明**：MVP 显式失败**不能替代**本例；阶段一引入本地缓存 + 有效性窗口（handle TTL = 一个探活周期）后激活，验 Gateway/runtime 降级事实与目标命中。

---

### TC-18 `FEAT-016.deferred.agent-runtime-projection` — agent-runtime 代理投影（deferred）

- **状态 / 优先级**：deferred。
- **来源**：特性 §2 / §5.1.2 / §5.1.4；设计 §2.3.2 / §4.2。
- **覆盖**：路由可用性投影（可用 / 可能不可用 / 有限可用 / 暂不可用 / 版本不匹配）、agent 不直连 registry。
- **说明**：投影层在 `agent-runtime` 对 agent 表面，**不在 registry 单元**；registry 直查不能等价覆盖。需真实 Agent 经 runtime 工具的投影与下游调用激活。

---

## 9. 脱敏断言专项（Canary 递归扫描规范）

> 本节是 TC-09 / TC-07 / TC-08 / TC-11 / TC-12 / TC-13 的共用断言基线。

### 9.1 Canary 注入清单

| Canary 类别 | 注入位置 | 期望不可见视图 |
|---|---|---|
| `canary-ep-{n}` | 注册时 endpointUrl | 查询响应、错误正文 |
| `canary-rk-{n}` | 注册时 routeKey | 查询响应、错误正文 |
| `canary-inst-{n}` | 注册时 instanceId（host-port） | 查询响应（仅 resolve 可见） |
| `canary-tenant-{x}` | tenant 标识 | 跨租户查询 / 错误正文 |
| `canary-task-{n}` | Task 字段（若 fixture 注入） | 所有路由查询响应 |

### 9.2 递归扫描算法（伪代码）

```
assertNoLeak(jsonNode, forbiddenCanaries):
    if node is String:
        for c in forbiddenCanaries:
            assert !node.contains(c)   # 命中即 FAIL
    elif node is Object:
        for k, v in node:
            assert k not in {endpointUrl, routeKey, instanceId}  # 查询响应专属
            assertNoLeak(v, forbiddenCanaries)
    elif node is Array:
        for e in node: assertNoLeak(e, forbiddenCanaries)
```

### 9.3 视图边界对照

| 视图 | 取证入口 | 可见 | 不可见（canary 必须未命中） |
|---|---|---|---|
| 系统路由视图 | `GET /instances` | routeHandle(opaque)、serviceId、health、weight、region、maxConcurrency、agentName、frameworkType、版本字段 | endpointUrl、routeKey、instanceId 明文、Task*、DB key、topic |
| 转发层 resolve | `POST /resolve` | instanceId、endpointUrl、routeKey、contractVersion | （只对转发层可见，不进入 client/agent） |
| 错误正文 | 任意错误响应 | error code、message | 其他 tenant canary、物理 canary |

---

## 10. 测试数据设计

| 数据集 | 内容 | 用途 |
|---|---|---|
| `tenant-A` 双实例 | `agentId=travel-hotel`, `serviceId=hotel-svc`, weight=100/80, version=v1/v2, 心跳新鲜度可区分 | TC-01/02/04/05/09/14 |
| `tenant-B` 同 agentId | 与 tenant-A 同 agentId 不同实例 | TC-07（跨租户） |
| 不存在 agentId | `unknown-agent` | TC-08（反枚举） |
| 畸形 handle 集 | 缺前缀 / 坏 base64 / 缺字段 / 旧 4 字段 / `v1:` 5 字段 | TC-11 |
| 已注销 handle | deregister 后的合法 handle | TC-12 |
| 断链 fixture | Testcontainers pause / FaultLink | TC-13 |

> 所有 canary 全局唯一，便于泄漏归因。

---

## 11. 框架落点与运行方式

### 11.1 文件落点

```
src/test/java/com/huawei/ascend/sit/cases/integration/agent_bus/
  Feat016RouteQueryExternalBlackboxTest.java      # 黑盒 + 契约（TC-01~14）
  Feat016GatewayRouteConsumptionTest.java          # Gateway 集成（TC-15，gated）
```

> 不得引用、调用或复制仓库现有 FEAT-016 测试类来实现上述用例；其通过结果不能替代本方案验收。

### 11.2 运行基线

- JDK 21；PowerShell 用 `.\mvnw.cmd`，WSL/Git Bash 用 `./mvnw`；
- 默认 Maven 仓库 `~/.m2/repository`；
- Docker 提供 PostgreSQL；
- RDC 用 `com.openjiuwen:registry-discovery-center:0.1.0` 外部可执行 JAR + `classifier=lib` 测试合同；
- travel-hotel 坐标见 §1.1。

### 11.3 独立验收命令

```bash
# 黑盒 + 契约全量
.\mvnw.cmd -Dtest.env=openjiuwen -Dtest=Feat016RouteQueryExternalBlackboxTest test

# 按 story 分组精确执行（避免与仓库已有 feat-016 用例混入）
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-016-query-known-agent-multi-instance test
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-016-resolve-forwarding-contract test
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-016-tenant-isolation-and-cross-tenant test
```

---

## 12. 退出标准与验收

### 12.1 退出标准

- MVP 能力（TC-01~05、07~14）通过或明确门禁；
- 特性其余 MUST 均有 deferred 处置（TC-16/17/18）；
- resolve contract、RDC 黑盒、Gateway 黑盒**分别统计**，不以直查 RDC 替代 agent-runtime 代理投影或整体选路；
- 测试结束恢复 PostgreSQL/RDC 连接，关闭 RDC/hotel/Gateway 进程和容器，删除临时注册数据并确认端口释放。

### 12.2 验收检查清单

- [ ] 「真 AgentDemo 三要件」对照（若用 AgentDemo 早期探测）：AgentHandler Bean + agent-service-app 依赖 + A2A 客户端协议；
- [ ] 黑盒只经两个公开入口取证；
- [ ] Canary 递归扫描覆盖查询 / resolve / 错误正文；
- [ ] 错误处理矩阵 §7 全部 E1~E7 有对应用例（E8/E9/E10 分别 deferred / 调用方 / 转发层）；
- [ ] deferred 项有明确解锁条件。

---

## 13. 风险与依赖

| 风险 / 依赖 | 影响 | 缓解 |
|---|---|---|
| L2 为 draft，MVP 子集 | serviceId/capability、降级、五态投影缺 | deferred 分支明确门禁，不强行用直查替代 |
| 公开生命周期入口缺失 | DRAINING/OFFLINE 分支无法触发 | 该参数分支 dependency-gated，不用 SQL/反射造假状态 |
| 正式缺参传输表示不可达 | 缺参 fail-fast 分支无法触发 | 缺该表示时分支 gated，不把路由器 404 误判为 `invalid_request` |
| Gateway-RDC 接线未加入 acceptance | Gateway 选路消费无法验 | TC-15 dependency-gated，接线下沉后激活 |
| 实测错误码命名 / 状态码偏离 | 期望 400 实际 404、期望小写实际大写 | 记为 Bug，按设计 §7 Oracle 判定；测试断言文档化行为，Bug 修复后翻转 |
| Probe interval 默认过大 | DEGRADED 新鲜度窗口不适合短测试 | 覆盖 probe-* 配置为有界短值 |
| 仓库既有 FEAT-016 测试类 | 混入本方案证据 | 按新类独立执行，不复用 fixture / 预期 |

---

## 14. 附录：覆盖追溯矩阵

### 14.1 scope §2 MUST → 用例

| scope §2 MUST | 设计落地 | 覆盖用例 | 状态 |
|---|---|---|---|
| 已知目标路由查询 | §4.1/§4.2/§2.3.1 | TC-01 | ✅ runnable |
| 统一查询语义 | §3.2/§4.1/§4.2 | TC-01/05/15 | TC-15 gated |
| agent-runtime 代理查询 | §3.2/§4.2 | TC-18 | deferred |
| 多实例候选 | §4.3 | TC-01/02 | ✅ |
| 运行时实例标识 | §4.3/§2.3.2 | TC-01/05 | ✅ |
| 路由引用（opaque） | §2.3.2/§4.1/§4.2 | TC-05/06 | ✅ |
| 路由可用性投影 | §2.3.2/§4.2 | TC-18 | deferred（投影在 agent-runtime） |
| 租户隔离 | §2.3.3/§4.6/§7 | TC-07 | ✅ |
| 版本约束 | §2.3.2/§4.4 | TC-04/14 | ✅ |
| 健康与可用性 | §4.4 | TC-03 | ✅（lifecycle 分支 gated） |
| 中心短时不可用降级 | §4.5/§8 | TC-17 | deferred |
| 反枚举保护 | §4.6 | TC-08 | ✅ |
| Task 状态隔离 | §2.3.2 | TC-09 | ✅ |
| 物理细节透明 | §2.3.2 | TC-09 | ✅ |

### 14.2 设计 §7 错误处理 → 用例

| §7 行 | 用例 | 状态 |
|---|---|---|
| E1 缺 tenantId | TC-10 | ✅（缺参表示 gated 分支） |
| E2 缺 agentId | TC-10 | ✅（同上） |
| E3 跨 tenant 解析 | TC-07 | ✅ |
| E4 handle 畸形 | TC-11 | ✅ |
| E5 entry 不存在 | TC-12 | ✅ |
| E6 无可用候选 | TC-08 | ✅ |
| E7 中心不可用(MVP) | TC-13 | ✅ |
| E8 中心不可用+本地可用 | TC-17 | deferred |
| E9 版本不匹配 | TC-14 | ✅（调用方判断） |
| E10 路由失败 | — | 转发层语义，不在本设计 |

---

> **红线**：本设计验收证据必须来自上述用例的独立执行。直查 RDC 不能替代 agent-runtime 代理投影（TC-18）；MVP 显式失败不能替代中心降级（TC-17）；预查询或 endpoint 命中不能替代 Gateway 实际选路消费（TC-15）。任何分支不满足 Oracle 即记为 Bug，并在修复后同步翻转断言。
