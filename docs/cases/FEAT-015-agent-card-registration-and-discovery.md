---
feature_id: FEAT-015
feature_title: Agent Card 注册与发现
sut: registry-discovery-center（rdc），Agent Card 逻辑注册与发现服务
scope: >
  本档覆盖 rdc 作为独立 SUT 的 REST API 黑盒行为 —— 结构化发现（DiscoverAgentCards）、
  错误表面、租户/调用方边界。Agent Card 注册/生命周期/对账子用例依赖 agent-runtime fixture，
  标记 partial 等待 fixture 就绪后落地。
  FEAT-016（运行时实例路由）不在本档范围；agent-runtime 的 A2A 入口面由 FEAT-001 独立覆盖。
status: designed
owner: TBD
tags: [integration, registry-discovery, feat-015]
depends_on:
  - rdc 已部署并可访问（端口 8092，jar 坐标 `com.openjiuwen:registry-discovery-center:0.1.0`，产物 `agent-rdc-0.1.0.jar`；已部署于 7.209.189.212，PG 库 `agent_rdc` 已建）
  - agent-runtime fixture 就绪：已有 travel-openjiuwen 项目（hotel:8093 / trip:8092 / mainplan:8091，ReactAgent 框架），启动方式推荐 SutStack（jar 坐标待 travel 负责人提供）
  - rdc 的 REST API 契约稳定（基于 L2 设计文档 PR #418；无独立 SDK，SIT 侧用 JDK `HttpClient` 直发请求）
  - SIT 需可访问 rdc DB（agent_rdc）用于部分子用例的数据准备/验证（reconciliation、provider-source-revision、STALE_SOURCE SQL 注入）
related_docs:
  - FEAT-015 需求文档：spring-ai-ascend `version-scope/FEAT-015-agent-card-registration-and-discovery.md`（PR #394，本地 `D:\01-文档资料\01-Projects\04-Test_Work\01-Design_File\spring-ai-ascend\version-scope\`，分支 `experimental`）
  - FEAT-015 L2 低层设计：spring-ai-ascend `architecture/L2-Low-Level-Design/agent-bus/Feat-Func-015-agent-card-registration-and-discovery.md`（PR #418，537 行）
  - FEAT-015 运行态设计：spring-ai-ascend `architecture/L2-Low-Level-Design/agent-bus/registry-discovery-runtime-design.cn.md`（911 行，⚠️ 旧版 push 注册模型，已被 PR #418 取代，仅作参考）
  - FEAT-015 开发确认回复：`D:\01-文档资料\01-Projects\04-Test_Work\01-Design_File\FEAT-015 SIT 测试 — 开发确认回复.mkd`（2026-07-22）
revision: v1.2
revision_date: 2026-07-22
---

# FEAT-015 — Agent Card 注册与发现 用例设计

> **一句话**：以 rdc 为 SUT，把 FEAT-015 需求文档 §2 的 14 条 MUST + 1 条 SHOULD 和 §4 的 10 条用户场景，映射为可在 SIT 侧黑盒断言的子用例。

> **⚠️ 协议差异**：rdc 暴露 **REST API**（`POST /api/registry/discover`），非 A2A JSON-RPC。现有 `A2aServiceClient` 基于 JSON-RPC 协议不适用，SIT 侧需以 JDK `HttpClient`（JDK 11+）或 Spring `RestTemplate` 直发 REST 请求。

> **⚠️ 状态标注**：
> - **runnable**：仅需 rdc 可达，REST 客户端直接调，无 fixture 依赖
> - **partial**：API 契约已明确，但需要 agent-runtime fixture（暴露 Agent Card 供 rdc 抓取）才能跑通主路径；或需 DB 访问权限
> - **blocked**：断言依据待评审澄清
> - **deferred**：依赖能力缺失或设计未定

> **v1.2 更新（2026-07-22）**：基于开发确认回复更新 6 项——
> ① tenant-denied：跨租户 discover → 200 NO_MATCH（不泄露存在性），非 403；
> ② discover-pagination：token 绑定 tenant+caller+查询指纹，默认 pageSize=20；
> ③ card-validation：version 缺失 → AGENT_CARD_INVALID；
> ④ provider-stale-source：static provider 无法模拟 provider 不可用；黑盒变通方案为 SQL 注入 freshness；
> ⑤ provider-source-revision：blocked → partial，DB 表 `registry_source_state` 可查；
> ⑥ reconciliation：确认可清库，附 SQL 清理脚本。

---

## 1. 覆盖矩阵

对应 FEAT-015 需求文档 §2 能力表。

| FEAT-015 事实要求 | 本档子用例 ID | 现状 | 状态 | 备注 |
|---|---|---|---|---|
| 确定性结构化查询 | `FEAT-015.discover-query` | 未覆盖 | partial | 需已注册 Card 才能验证过滤语义 |
| 逻辑候选集合返回 | `FEAT-015.candidate-structure` | 未覆盖 | partial | 需已注册 Card 才能验证候选结构 |
| 明确结果与失败 — INVALID_QUERY | `FEAT-015.invalid-query` | 未覆盖 | runnable | REST 直接可测，无 fixture 依赖 |
| 明确结果与失败 — CALLER_NOT_AUTHORIZED | `FEAT-015.caller-unauthorized` | 未覆盖 | runnable | REST 直接可测，需 rdc allowlist 可配 |
| 明确结果与失败 — 跨租户隔离（不泄露存在性） | `FEAT-015.tenant-denied` | 未覆盖 | partial | 需 fixture 在另一租户有已注册 Card；跨租户 discover → 200 NO_MATCH |
| 明确结果与失败 — DEADLINE_EXCEEDED | `FEAT-015.deadline-exceeded` | 未覆盖 | runnable | REST 直接可测，格式已确认（ISO 8601，`context.deadline`） |
| Push 注册在 deployment-discovery 开启时被拒 | `FEAT-015.push-register-disabled` | 未覆盖 | runnable | REST 直接可测 |
| 部署发布事实接入 | `FEAT-015.card-registration` | 未覆盖 | partial | 需 agent-runtime fixture + provider 配置 |
| 部署发布事实接入 — 静态 Provider 全量快照 | `FEAT-015.provider-static-list` | 未覆盖 | runnable | 配置 `instances[]` → rdc 对账后可发现 Card |
| 部署发布事实接入 — Provider 空配置 | `FEAT-015.provider-empty` | 未覆盖 | runnable | `instances[]=[]` + 无 Bean → WARN，不对账 |
| 部署发布事实接入 — binding-defaults | `FEAT-015.provider-binding-defaults` | 未覆盖 | partial | 需自定义 Provider Bean 无 yml 条目，验证默认参数生效 |
| 部署发布事实接入 — source revision | `FEAT-015.provider-source-revision` | 未覆盖 | partial | DB 表 `registry_source_state` 可查 revision（开发确认黑盒无 HTTP API） |
| 标准 A2A Agent Card 主动抓取 | 并入 `FEAT-015.card-registration` | 未覆盖 | partial | 同上 |
| Agent Card 注册校验 | `FEAT-015.card-validation` | 未覆盖 | partial | 需 fixture 暴露非法 Card；version 缺失 → AGENT_CARD_INVALID |
| Agent Card 安全抓取 | `FEAT-015.card-fetch-security` | 未覆盖 | deferred | SIT 环境无法控制网络拓扑验证 CIDR/mTLS |
| 多实例发布去重 | `FEAT-015.multi-instance-dedup` | 未覆盖 | partial | 需 2+ 个相同 Card 的 fixture 实例 |
| 多版本共存 | `FEAT-015.multi-version-coexist` | 未覆盖 | partial | 需不同版本 Card 的 fixture（version 必填，缺失→拒） |
| 事件监听与周期全量对账 | `FEAT-015.reconciliation` | 未覆盖 | partial | 可清库（开发确认），附 SQL 清理脚本 |
| 更新、撤销与失效 | `FEAT-015.card-lifecycle` | 未覆盖 | partial | 需可控制 fixture 生命周期 |
| 最后有效快照 — STALE_CARD（Card 刷新失败） | `FEAT-015.stale-card-fallback` | 未覆盖 | partial | 需可控地触发 Card 抓取失败 |
| 最后有效快照 — STALE_SOURCE（provider 不可用） | `FEAT-015.provider-stale-source` | 未覆盖 | partial | static provider 无法模拟 provider 不可用；黑盒变通：SQL 注入 `freshness='STALE_SOURCE'` |
| 注册发现实现可替换 | `FEAT-015.provider-swap` | 未覆盖 | partial | 需自定义 Provider Bean |
| 租户与调用方边界 | `FEAT-015.tenant-isolation` | 未覆盖 | partial | 需跨租户已注册 Card |
| 审计与可观测 | `FEAT-015.audit` | 未覆盖 | deferred | SHOULD 级别；审计面暴露方式待定 |

> **不在本档范围**（对齐需求文档 §5.2）：FEAT-016 运行时实例路由、语义检索/推荐排序、集成方自定义 Provider 的完整实现验证。

### 1.1 状态分布快照

| 状态 | 数量 | 说明 |
|---|---|---|
| runnable | 6 | REST API 错误路径 (4) + 静态 Provider 全量快照 + Provider 空配置 |
| partial | 15 | 主路径可测，但依赖 agent-runtime fixture / 自定义 Provider / DB 访问 |
| blocked | 0 | 全部已解除 |
| deferred | 2 | 审计可观测面待定 + 安全抓取 SIT 环境不可验证 |

**落地优先级**：runnable → partial（需 fixture 的子集先落地单 fixture 用例）→ deferred。

---

## 2. 前置条件与共享约定

### 2.1 SUT 部署前置

- **rdc jar 坐标**：`com.openjiuwen:registry-discovery-center:0.1.0`，产物 `agent-rdc-0.1.0.jar`，入口 `com.openjiuwen.rdc.AgentRdcApplication`
- **部署**：已部署于 `7.209.189.212:8092`，PG 库 `agent_rdc` 已建（`docker exec akdi-pg`）
- **启动方式**：`java -jar agent-rdc-0.1.0.jar`
- **数据库**：PostgreSQL `jdbc:postgresql://localhost:15432/agent_rdc`（通过 `SPRING_DATASOURCE_URL` 覆盖）
- **deployment-discovery**：SIT 需显式 `enabled: true`；provider 用静态 YAML（`instances[]`）
- **对账**：`ApplicationReadyEvent` 立即执行首次对账；后续按 `reconcile-interval` 周期（SIT 可调短至 `5s`）
- REST API 入口：`POST /api/registry/discover`（发现）、`POST /api/registry/register`（deployment-discovery 开启时返回 410）
- **认证**：无强制鉴权；`X-Caller-Ref` 可选，缺省顺序 Header → body `context.callerRef` → `"http-client"`
- **agent-runtime fixture**：已有 travel-openjiuwen 项目，含 3 个 ReactAgent 框架 agent，均暴露标准 Agent Card
- **DB 清理**（开发确认可操作）：清库后需**重启 rdc**（或等下一轮 reconcile）；改 `instances[]` 也需重启

```sql
-- SIT 用例隔离——建议按依赖顺序清理
DELETE FROM agent_card_source_ref;
DELETE FROM agent_card_registration;
-- 若测实例路由 / Feat-016 边界，可再清：
DELETE FROM agent_registry_mvp;
-- 改 instances / 冷启动对账前建议清 revision：
DELETE FROM registry_source_state;
```

> 注意：仅清 `registry_source_state` ≠ 清逻辑目录；要测「首次失败 / NO_MATCH」需连逻辑表一起清。SIT 共用库时请约定租户 / agentId，避免并行用例互相踩。

### 2.2 共享测试基础设施

- **REST HTTP 客户端**：封装 `RegistryClient`（待新建，基于 JDK `HttpClient`）
- **DB 访问**：`RegistryDbProbe`（待新建，基于 JDBC 直连 rdc PG），用于 reconciliation / source-revision 等需查 DB 的子用例
- **JSON 解析**：Jackson `ObjectMapper`
- **断言库**：AssertJ + Awaitility
- **标签**：`@Tag("integration") @Tag("registry-discovery") @Tag("feat-015")`
- **fixture 模式**：rdc 是 SUT，agent-runtime 是 fixture

### 2.3 共享命名约定

- `tenantId`：fixture 相关子用例用 `sit-tenant-<uuid8>`，runnable 子用例用 `tenant-A`
- `callerRef`：`X-Caller-Ref` Header（可选）；SIT 建议显式传 `X-Caller-Ref: gateway`
- `agentId` / `serviceId`：`agentId = serviceId.trim()`
- **base URL**：提取为常量 `RDC_BASE_URL`，通过环境变量或 test profile 注入

---

## 3. 子用例设计

> 约定：每条子用例表头对齐 FEAT-015 事实要求；步骤用 G/W/T；结论分 PASS/FAIL/INCONCLUSIVE。

### 3.1 REST API 错误表面

#### FEAT-015.invalid-query — 请求字段非法 → INVALID_QUERY

- **状态**：runnable
- **FEAT 依据**：需求 §5.1.6 + L2 §7.2「400」
- **G**：rdc 已启动，无前置数据要求。
- **W**：`POST /api/registry/discover`，依次发 3 种非法请求——缺 `context` / 缺 `context.tenantId` / 缺 `agentId`+`serviceId`+`a2aSkillId` 全部三项
- **T**：HTTP 400；响应体含 `"error":"INVALID_QUERY"`，另有 `message` / `retryable` / `traceId`；`retryable=false`
- **PASS**：三种请求均返回 400 + `"error":"INVALID_QUERY"`。**FAIL**：返回 200 / 500 / 错误码不匹配。**INCONCLUSIVE**：rdc 不可达。
- **框架落点**：待新建 `DiscoverErrorTest`。

#### FEAT-015.caller-unauthorized — 未授权 caller → CALLER_NOT_AUTHORIZED

- **状态**：runnable
- **FEAT 依据**：需求 §5.1.6 + L2 §7.2「403」
- **G**：rdc 配置 `caller-allowlist`（如 `{tenant-A: [gateway]}`），只允许 `gateway` 这个 caller。
- **W**：`POST /api/registry/discover`，`callerRef=sit-unauthorized-caller`，带合法 `tenantId` + `agentId`
- **T**：HTTP 403；响应体含 `"error":"CALLER_NOT_AUTHORIZED"`
- **PASS**：403 + `"error":"CALLER_NOT_AUTHORIZED"`。**FAIL**：返回 200 泄露数据 / 返回其他错误码。**INCONCLUSIVE**：allowlist 无法配置。
- **框架落点**：同上 `DiscoverErrorTest`。

#### FEAT-015.tenant-denied — 跨租户查询不泄露存在性 → 200 NO_MATCH

- **状态**：partial（需要 fixture 在另一租户提供已注册 Card）
- **FEAT 依据**：需求 §5.1.6 + 开发确认：**跨租户 discover → 200 `NO_MATCH`**（不泄露 agent 存在性，非 403）；FEAT-016 跨租户 resolve 才是 403 `TENANT_SCOPE_DENIED`
- **G**：fixture 已在 `tenant-B` 注册 Card
- **W**：`POST /api/registry/discover`，以 `tenantId=tenant-A` 查询 `agentId`（该 agent 在 `tenant-B` 下存在）
- **T**：HTTP **200**（非 403）；`outcome=NO_MATCH`；不通过 HTTP 状态码或业务 outcome 泄露 agent 在另一租户的存在性
- **PASS**：200 + NO_MATCH。**FAIL**：返回 403 泄露存在性 / 返回 SUCCESS 带了跨租户数据。**INCONCLUSIVE**：fixture 未就绪。
- **框架落点**：待新建 `TenantDeniedTest`（或并入 `TenantIsolationTest`）。

#### FEAT-015.deadline-exceeded — 超时 → DEADLINE_EXCEEDED

- **状态**：runnable
- **G**：rdc 已启动
- **W**：`POST /api/registry/discover`，`context.deadline` 传入已过期 ISO 8601 时间戳（如 `"2020-01-01T00:00:00Z"`）
- **T**：HTTP 503；`"error":"DEADLINE_EXCEEDED"`；`retryable=true`
- **PASS**：503。**FAIL**：返回 200 / 忽略 deadline。
- **框架落点**：并入 `DiscoverErrorTest`。

#### FEAT-015.push-register-disabled — deployment-discovery 开启时 push 注册被拒

- **状态**：runnable
- **G**：rdc 已启动且 `deployment-discovery.enabled=true`
- **W**：`POST /api/registry/register`，携带任意 JSON body
- **T**：HTTP 410；`"error":"push_registration_disabled"`
- **PASS**：410。**FAIL**：返回 200 接受 push。
- **框架落点**：待新建 `PushRegisterDisabledTest`。

### 3.2 结构化发现（partial — 需要已注册 Agent Card）

#### FEAT-015.discover-query — 按 agentId / serviceId / a2aSkillId 发现

- **状态**：partial
- **G**：travel-openjiuwen fixture 已通过 provider 注册；rdc 对账完成
- **W**：`POST /api/registry/discover`，依次按 `agentId` / `serviceId` / `a2aSkillId` 查询
- **T**：HTTP 200；`outcome=SUCCESS`；`registrationStatus=REGISTERED`；`freshness=FRESH`
- **PASS**：三种查询方式均正确匹配。**FAIL**：任一种查不到。
- **框架落点**：待新建 `DiscoverQueryTest`。

#### FEAT-015.discover-no-match — 无匹配 → NO_MATCH

- **状态**：partial
- **W**：查询不存在的 `agentId=nonexistent-agent`
- **T**：HTTP 200；`outcome=NO_MATCH`；`candidates=[]`
- **框架落点**：并入 `DiscoverQueryTest`。

#### FEAT-015.discover-version-unavailable — 版本约束无满足 → VERSION_UNAVAILABLE

- **状态**：partial
- **W**：`agentId` 正确但 `constraints.capabilityVersion=9.9.9`
- **T**：HTTP 200；`outcome=VERSION_UNAVAILABLE`；`candidates=[]`
- **框架落点**：并入 `DiscoverQueryTest`。

#### FEAT-015.discover-constraint-unavailable — 声明约束无满足 → CONSTRAINT_UNAVAILABLE

- **状态**：partial
- **W**：`agentId` 正确但 `constraints.requiredCapabilities` 要求 Card 不支持的 capability
- **T**：HTTP 200；`outcome=CONSTRAINT_UNAVAILABLE`；`candidates=[]`
- **框架落点**：并入 `DiscoverQueryTest`。

#### FEAT-015.discover-pagination — 分页

- **状态**：partial
- **G**：rdc 有 5+ 个已注册 Card
- **W**：第一页 `limit=3` → 拿 3 个候选 + `nextToken` → 用 token 取第二页
- **T**：分页无重复/遗漏；**token 绑定 `tenantId` + `callerRef` + 查询指纹**（含 `agentId`/`serviceId`/`limit`/constraints）；换租户/改条件复用 token → HTTP 400 `INVALID_QUERY`；**不传 `limit` 默认 pageSize=20**（开发确认）
- **框架落点**：待新建 `DiscoverPaginationTest`。

### 3.3 注册生命周期（partial）

#### FEAT-015.card-registration — 首次注册 → REGISTERED 可发现

- **状态**：partial
- **G**：rdc provider 配置指向 travel-openjiuwen fixture；fixture 已启动
- **W**：rdc 对账后 `POST /api/registry/discover` 查询
- **T**：HTTP 200；`outcome=SUCCESS`；`registrationStatus=REGISTERED`；`freshness=FRESH`
- **框架落点**：待新建 `CardRegistrationTest`。

#### FEAT-015.card-fetch-failure — 抓取失败 → PENDING，不可发现

- **状态**：partial
- **G**：provider 指向不可达的 agent-runtime
- **W**：rdc 对账后查询
- **T**：该 Card 不出现在 discover 结果中
- **框架落点**：并入 `CardRegistrationTest`。

#### FEAT-015.card-validation — 非法 Card 被拒绝

- **状态**：partial
- **G**：fixture 暴露非法 Card（如 `AgentCard.version` 缺失、skills 为空等）
- **W**：rdc 对账抓取后查询
- **T**：**`version` 缺失 → `AGENT_CARD_INVALID`**（开发确认：`AgentCardValidator` 要求 version 为非空文本，不给默认值）；discover → `NO_MATCH`
- **PASS**：拒绝注册。**FAIL**：非法 Card 被接受。**INCONCLUSIVE**：无法修改 fixture 配置。
- **框架落点**：待新建 `CardValidationTest`。

#### FEAT-015.card-fetch-security — 安全抓取边界

- **状态**：deferred
- **框架落点**：待安全测试环境就绪后新建 `CardFetchSecurityTest`。

#### FEAT-015.multi-instance-dedup — 多实例发布同一 Card → 一个候选

- **状态**：partial
- **框架落点**：待新建 `MultiInstanceDedupTest`。

#### FEAT-015.multi-version-coexist — 多版本共存

- **状态**：partial
- **G**：2 个 fixture 实例，同一 `serviceId` 但 `AgentCard.version` 分别为 `1.0.0` 和 `2.0.0`
- **W**：不加版本约束 → 得 2 个候选；加 `constraints.capabilityVersion=1.0.0` → 只得 1 个
- **T**：多版本共存 + 版本过滤正确；⚠️ **version 必填**（缺失→拒，不给默认值）
- **框架落点**：待新建 `MultiVersionCoexistTest`。

#### FEAT-015.card-lifecycle — Card 更新/撤销

- **状态**：partial
- **框架落点**：待新建 `CardLifecycleTest`。

#### FEAT-015.source-removal — 发布来源撤销 → 退出目录

- **状态**：partial
- **框架落点**：并入 `CardLifecycleTest`。

#### FEAT-015.stale-card-fallback — 刷新失败 → STALE_CARD 仍可发现

- **状态**：partial
- **框架落点**：待新建 `StaleCardFallbackTest`。

#### FEAT-015.reconciliation — 周期全量对账

- **状态**：partial
- **G**：rdc 对账间隔调短至 5s；fixture 已注册
- **W**：按 DB 清理脚本删除 `agent_card_registration` 中该 Card 记录 → 等待对账周期
- **T**：Card 在对账后重新出现，`freshness=FRESH`
- **DB 清理**（开发确认）：清 `agent_card_source_ref` → 清 `agent_card_registration` → 重启 rdc 或等下一轮 reconcile
- **框架落点**：待新建 `ReconciliationTest`（需 `RegistryDbProbe` 直连 DB）。

### 3.4 租户与安全边界（partial）

#### FEAT-015.tenant-isolation — 跨租户数据隔离

- **状态**：partial
- **G**：`tenant-A` 有 hotel agent，`tenant-B` 有 trip agent
- **W**：以 `tenantId=tenant-A` 查询 trip agent
- **T**：HTTP 200；`outcome=NO_MATCH`；候选列表不含 trip agent；不泄露跨租户存在性
- **框架落点**：待新建 `TenantIsolationTest`。

### 3.5 候选结构完整性（partial）

#### FEAT-015.candidate-structure — 候选字段完整且不含 FEAT-016 字段

- **状态**：partial
- **框架落点**：待新建 `CandidateStructureTest`。

#### FEAT-015.card-json-integrity — agentCardJson 与原始 Card 一致

- **状态**：partial
- **框架落点**：同上 `CandidateStructureTest`。

### 3.6 Provider 可替换性（partial）

#### FEAT-015.provider-swap — 自定义 Provider Bean 集成

- **状态**：partial
- **框架落点**：待新建 `CustomProviderIntegrationTest`。

### 3.7 部署发布事实接入 — SPI / Provider

#### FEAT-015.provider-static-list — 静态 Provider 全量快照 → Card 可发现

- **状态**：runnable
- **框架落点**：并入 `CardRegistrationTest`。

#### FEAT-015.provider-empty — Provider 空配置 → 不对账

- **状态**：runnable
- **框架落点**：待新建 `ProviderConfigTest`。

#### FEAT-015.provider-stale-source — Provider 不可用 → STALE_SOURCE

- **状态**：partial
- **FEAT 依据**：需求 §5.1.3 + §5.1.4
- **⚠️ 开发确认**：**static provider 无法在运行时模拟「provider 不可用」**。删 yml 条目→REMOVED（非 STALE_SOURCE）；停 fixture→STALE_CARD（非 provider 中断）；无内部 API 触发 outage。正式验收口为自动化集成测试 `ReconciliationScenarioIntegrationTest#provider_outage_marks_stale_without_mass_delete_then_recovers`
- **SIT 黑盒变通方案**：SQL 注入 `freshness='STALE_SOURCE'` 后验证 discover 响应能带出该字段（**不算** provider 中断场景通过，仅验证 discover 正确返回 freshness 字段）
  ```sql
  UPDATE agent_card_registration SET freshness='STALE_SOURCE' WHERE service_id='billing-svc';
  -- 测完请改回 FRESH 或清库恢复
  ```
- **框架落点**：待新建 `ProviderStaleSourceTest`（SQL 注入变通 + 验证 discover freshness 字段）。

#### FEAT-015.provider-binding-defaults — 自定义 Provider Bean 无 yml 条目 → binding-defaults 生效

- **状态**：partial
- **框架落点**：并入 `CustomProviderIntegrationTest`。

#### FEAT-015.provider-source-revision — source revision 递增 → 对账正确

- **状态**：partial（原 blocked，开发确认 DB 可查）
- **FEAT 依据**：需求 §5.1.1 + §5.1.3
- **观测方式**（开发确认）：无专用 HTTP API；黑盒查 `registry_source_state` 表
  ```sql
  SELECT source_id, last_processed_revision, last_success_at FROM registry_source_state;
  ```
  不便连库时可用 discover 结果间接验证；revision 严格断言建议查表
- **框架落点**：待新建 `ProviderSourceRevisionTest`（需 `RegistryDbProbe` 直连 DB）。

---

## 4. 框架落点汇总

| 子用例 ID | 落点 Java 类 | 状态 |
|---|---|---|
| invalid-query / caller-unauthorized / deadline-exceeded | `DiscoverErrorTest` | runnable |
| tenant-denied | `TenantDeniedTest` | partial |
| push-register-disabled | `PushRegisterDisabledTest` | runnable |
| discover-query / no-match / version-unavailable / constraint-unavailable | `DiscoverQueryTest` | partial |
| discover-pagination | `DiscoverPaginationTest` | partial |
| card-registration / card-fetch-failure / provider-static-list | `CardRegistrationTest` | partial |
| card-validation | `CardValidationTest` | partial |
| card-fetch-security | — | deferred |
| multi-instance-dedup | `MultiInstanceDedupTest` | partial |
| multi-version-coexist | `MultiVersionCoexistTest` | partial |
| card-lifecycle + source-removal | `CardLifecycleTest` | partial |
| stale-card-fallback | `StaleCardFallbackTest` | partial |
| reconciliation | `ReconciliationTest` | partial |
| tenant-isolation | `TenantIsolationTest` | partial |
| candidate-structure + card-json-integrity | `CandidateStructureTest` | partial |
| provider-swap + provider-binding-defaults | `CustomProviderIntegrationTest` | partial |
| provider-empty | `ProviderConfigTest` | runnable |
| provider-stale-source | `ProviderStaleSourceTest` | partial |
| provider-source-revision | `ProviderSourceRevisionTest` | partial |
| audit | — | deferred |

包：`com.huawei.ascend.sit.cases.integration.registry_discovery`

### 4.1 落地优先级

**P0 · REST 错误表面（runnable，纯 REST，无 fixture 依赖）**
- `DiscoverErrorTest` + `PushRegisterDisabledTest`

**P1 · 单 fixture + 静态 Provider**
- `DiscoverQueryTest` / `CardRegistrationTest` / `CandidateStructureTest` / `ProviderConfigTest` / `CustomProviderIntegrationTest` / `ProviderSourceRevisionTest`

**P2 · 多 fixture / 生命周期 / DB 依赖**
- `CardLifecycleTest` / `MultiInstanceDedupTest` / `MultiVersionCoexistTest` / `StaleCardFallbackTest` / `ProviderStaleSourceTest` / `ReconciliationTest` / `DiscoverPaginationTest` / `TenantIsolationTest` / `TenantDeniedTest` / `CardValidationTest`

**Deferred**
- `FEAT-015.audit` / `FEAT-015.card-fetch-security`

---

## 5. 运行方式

```bash
# 全部 FEAT-015 相关用例
./mvnw -Dtest.env=SIT -Dgroups=feat-015 test

# 仅 P0 错误表面
./mvnw -Dtest.env=SIT -Dtest=DiscoverErrorTest,PushRegisterDisabledTest test
```

环境配置（`src/test/resources/application-sit.yml`）：
```yaml
rdc:
  base-url: http://7.209.189.212:8092
  db-url: jdbc:postgresql://7.209.189.212:15432/agent_rdc
  db-username: postgres
  db-password: akdi123
```

---

## 6. 风险与备注

### 6.1 阻塞级 —— 待闭环

| # | 阻塞项 | 影响子用例 |
|---|--------|-----------|
| 1 | travel agent 的 SutStack jar 坐标（`groupId:artifactId:version`） | 全部 partial（15 条） |
| 2 | rdc 部署启动并配置 fixture agent（`instances[]` 填 travel agent 地址） | 全部 partial |

### 6.2 已确认（开发答复）

| # | 事项 | 结论 |
|---|------|------|
| 1 | tenant-denied 跨租户行为 | 200 NO_MATCH（不泄露存在性），非 403 |
| 2 | continuationToken 绑定 | tenantId+callerRef+查询指纹；默认 pageSize=20 |
| 3 | AgentCard.version 缺失 | AGENT_CARD_INVALID，不给默认值 |
| 4 | provider 不可用模拟 | static provider 无法模拟；黑盒变通 SQL 注入 freshness |
| 5 | source revision 观测 | 无 HTTP API；查 `registry_source_state` 表 |
| 6 | SIT 清 PG 表 | 可以，附 SQL 顺序；清后重启 rdc |
| 7 | caller-allowlist 语义 | 空 `{}` = 仅要求非空 callerRef |
| 8 | error 响应字段名 | `error`（非 `failureCode`），另有 `message`/`retryable`/`traceId` |

### 6.3 实现层风险

**REST 协议适配**：rdc 使用 REST，非 A2A JSON-RPC；SIT 侧用 JDK `HttpClient` 自封装 `RegistryClient`。

**DB 直连**：部分子用例（reconciliation、source-revision、STALE_SOURCE SQL 注入）需 `RegistryDbProbe` 直连 rdc PG。注意：仅用于测试数据准备/验证，测试断言仍以 HTTP API 为主。

### 6.4 与 FEAT-016 边界

- FEAT-016（运行时实例路由）不在本档
- 本档断言候选**不含** `routeHandle` / `instanceId` / `endpointUrl`
- 015 + 016 联合场景属于 E2E，不在本档

### 6.5 Deferred

- **audit**：审计面暴露方式待定
- **card-fetch-security**：SIT 环境无法验证网络层安全策略
- **内置 K8s Provider**：产品可选里程碑
