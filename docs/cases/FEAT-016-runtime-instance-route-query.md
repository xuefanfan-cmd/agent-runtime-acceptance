---
feature_id: FEAT-016
feature_title: 运行时实例路由查询
sut: registry-discovery-center；dependency-gated gateway 与 agent-runtime 调用方链路
scope: 已知目标查询、多实例、路由引用、版本和健康过滤、租户隔离、反枚举、视图脱敏、中心不可用降级及调用方路由 gate
status: designed
owner: TBD
priority: P0
tags: [integration, contract, agent-bus, feat-016]
depends_on:
  - com.openjiuwen:registry-discovery-center:0.1.0 可执行 artifact 已安装到本地 Maven 仓库
  - Docker/Testcontainers 可用，PostgreSQL 16 fixture 可启动
  - gateway 暴露 FEAT-016 直连路由表面后，dependency-gated gateway 子用例方可生成实现
  - agent-runtime 暴露 FEAT-016 查询工具、可用性投影和路由 gate 后，dependency-gated runtime 子用例方可生成实现
  - 需要 ReActAgent 的调用方用例使用 agent-solution/common/example/multi-react-travel-demo/agent-hotel
related_docs:
  - FEAT-016-runtime-instance-route-query.md
  - Feat-Func-016-runtime-instance-route-query.md
  - FEAT-005-agent-middleware-request-proxy-reactagent.md
---

# FEAT-016 - 运行时实例路由查询测试用例设计

> **一句话**：由 acceptance 拉起真实 registry-discovery-center 外部 JAR 和 PostgreSQL，验证三种已知目标查询、多实例、版本和健康过滤、opaque route handle、租户隔离、反枚举、错误与恢复；必须依赖 gateway 或 agent-runtime 的路由投影、缓存降级和最终转发场景完整保留为 dependency-gated 测试，待对应模块提供入口后生成代码。

> **当前实现裁决**：经开发确认并由源码核验，当前实现已经支持 by `agentId`、by `serviceId`、by exact `capability`、`contractVersion` 过滤，以及把 `DRAINING` 作为有限可用候选返回。L2 中“serviceId/capability 阶段一”“版本仅由调用方判断”“DRAINING 排除”的旧描述不作为当前测试预期。`capabilityVersion` 当前只随 DTO 返回，不是 registry 查询参数。

> **仓库边界**：测试设计和后续测试代码只写入 `agent-runtime-acceptance`。registry-discovery-center、gateway、agent-runtime 和 react-agent-demo 均为只读 SUT；不得为了测试增加产品端点。正常数据通过注册/注销 HTTP API准备；只有没有公开控制面的状态、心跳时间和 RLS 数据使用测试类内私有 fixture。

## 0. 执行前置条件

registry 测试不要求人工预先启动 registry-discovery-center 或 PostgreSQL。测试负责启动、等待就绪和停止二者，但执行环境必须先满足以下条件。

### 0.1 当前 registry 黑盒与 contract 必需条件

| 条件 | 要求 | 准备方式 |
|---|---|---|
| Java | WSL 内 JDK 21 | `java -version` |
| Docker | WSL 内 Docker daemon 可用，Testcontainers 可连接 | `docker version` |
| PostgreSQL 镜像 | 可拉取或已缓存 `postgres:latest` | `docker pull postgres:latest`，也可由 Testcontainers 首次自动拉取 |
| Maven 仓库 | 能解析 Spring Boot、Testcontainers、PostgreSQL driver、registry 及其传递依赖 | 接入公司 VPN/DNS/镜像仓；先执行 Maven dependency smoke |
| registry 可执行 artifact | WSL `~/.m2` 中存在 `com.openjiuwen:registry-discovery-center:0.1.0` | 在 `agent-solution/common/registry-discovery-center` 执行 `mvn clean install` |
| acceptance 配置 | `application-openjiuwen.yml` 有 `registry-center` 和 `postgres` 声明 | 本次测试实现已按 §4.1 增加 |
| acceptance contract 依赖 | test classpath 含同版本 registry artifact、PostgreSQL JDBC driver 和所需 Spring JDBC 类型 | 本次测试实现已更新 `pom.xml`，版本必须与外部 JAR 一致 |
| 进程权限 | 测试可启动/终止 Java 子进程、监听随机端口和读取 PID 监听端口 | 复用 ProcessLauncher 的既有环境权限 |

建议在 WSL 中从 `/mnt/d/code-agent/...` 执行构建和测试，保证 Maven 本地仓、Docker daemon 与 Testcontainers 位于同一执行环境。Windows PowerShell 中存在 Java 不代表 Windows 侧 Testcontainers 能访问仅运行于 WSL 的 Docker。

### 0.2 测试自动提供，不需要外部部署

- PostgreSQL 实例、数据库、用户和 Flyway schema 由类内 Testcontainers fixture 提供。
- registry-center 进程由 `SutStack`/`ProcessLauncher` 从本地 Maven artifact 拉起。
- 注册记录、租户、版本、能力、多实例和 routeHandle 均由每个测试方法动态创建。
- MockProbeTarget、ScriptedRegistryServer、MockRouteTarget、fake Repository 和 HandleFixtures 均定义在对应测试类内部。
- 当前 registry 黑盒/contract 不需要 LLM、真实 gateway、真实 agent-runtime、真实 Agent 或真实业务 endpoint。

### 0.3 dependency-gated 调用方用例附加条件

- gateway 提供可执行 artifact、启动配置、已知目标入口和稳定错误合同。
- agent-runtime 提供查询工具、路由可用性投影、版本判断、本地缓存和最终 route gate 的公开表面。
- `multi-react-travel-demo/agent-hotel` 构建并安装为 `com.openjiuwen.example:travel-demo-hotel:0.1.0`，且提供独立 `hotel-route-query` profile。
- 只有自然语言触发 ReActAgent 工具的场景需要有效 LLM 凭据；gateway、缓存和 runtime contract 不应依赖 LLM。
- “不可见目标/无权限目标”若依赖真实权限系统，需要该系统的测试租户、调用方身份和可重复权限 fixture；否则只执行 registry 的空结果与租户隔离部分。

这些附加条件未满足时，只跳过带 `dependency-gated`/`env-gated` 标签的调用方用例，不影响 registry 黑盒和 contract 测试生成与执行。

## 1. 测试事实口径

- FEAT-016 是已知目标查询，不是 Agent Card 候选发现。`agentId`、`serviceId` 或 `capability` 必须由调用方事先明确。
- registry 的三个查询维度是独立入口，不预设 `serviceId AND capability` 组合查询。
- 三个查询均返回候选集合，按 `weight DESC, last_heartbeat DESC` 排序，不在 registry 内替调用方完成最终实例选择。
- 系统路由视图返回 `serviceId`、opaque `routeHandle`、健康、版本和 selection hints，不返回 endpoint、routeKey、instanceId 明文或 Task 状态。
- `routeHandle` 是 `v2:` 六字段 opaque 引用；只有 gateway/agent-runtime 转发层可调用 resolve 获得物理端点。
- registry 当前支持 `contractVersion` 服务端过滤；`capabilityVersion` 由调用方读取 DTO 后判断。
- registry 系统视图以 `ONLINE`、`DEGRADED`、`DRAINING` 分别表达可用、可能不可用和有限可用；`OFFLINE` 不进入新查询结果。
- 无匹配、目标不存在、目标不可见和无可用实例在查询表面统一为 `200 []`，不得泄漏存在性差异。
- registry 中心不可用时的本地缓存与降级属于 gateway/agent-runtime 调用方，不在 registry 模块内实现，但属于 FEAT-016 系统验收范围。
- gateway 和 agent-runtime 是最终路由 gate；registry 只提供权威系统路由视图和 handle 解析。

## 2. 状态与证据定义

- **blackbox**：拉起真实 registry JAR 和 PostgreSQL，只通过 HTTP 输入输出断言产品行为。允许使用 setup-only 数据 fixture 制造没有公开入口的状态，但断言证据仍来自 HTTP。
- **contract**：在 acceptance 中使用与 JAR 同版本的 test-scope registry artifact，验证公开 Service/DTO/Repository 合同、RLS 和 codec 边界；不冒充外部 JAR 黑盒。
- **dependency-gated**：测试设计、方法名、fixture 和断言完整，但因 gateway 或 agent-runtime 尚未提供可执行入口，暂不生成具体测试代码或以条件标签禁用。
- **env-gated**：依赖部署环境真实 gateway、真实 agent-runtime、真实权限系统或客户网络；不进入本地 P0 门禁。
- **known-gap**：规格要求明确且可设计测试，但静态核验发现当前实现可能不满足；测试应保留为缺陷哨兵，不降低预期迎合实现。

## 3. 总覆盖矩阵

| 能力 | 子用例 ID | 类型 | 优先级 | 主要证据 |
|---|---|---|---|---|
| by agentId 查询 | `FEAT-016.query.agent-id` | blackbox | P0 | HTTP 200 + DTO 数组 |
| by serviceId 查询 | `FEAT-016.query.service-id` | blackbox | P0 | HTTP 200 + 跨 agent 实例集合 |
| by exact capability 查询 | `FEAT-016.query.capability` | blackbox | P0 | 精确数组包含、大小写/模糊负向 |
| 三维查询统一语义 | `FEAT-016.query.unified-semantics` | blackbox + dependency-gated | P0 | 相同候选、排序、过滤 |
| 无匹配永不返回 null | `FEAT-016.query.empty` | blackbox + contract | P0 | `200 []`、不可变空 List |
| 同 agentId 多实例 | `FEAT-016.multi.agent` | blackbox | P0 | 多 DTO、独立 handle |
| 同 serviceId 跨 agent 多实例 | `FEAT-016.multi.service` | blackbox | P0 | 多 agent 候选集合 |
| serviceId/instanceId 分离 | `FEAT-016.identity.logical-physical` | blackbox + contract | P0 | serviceId 明文、instanceId 仅进 handle |
| server-derived instanceId | `FEAT-016.identity.server-derived` | blackbox | P0 | caller 注入无效、resolve 恢复 host-port |
| 排序 | `FEAT-016.selection.sorting` | blackbox | P0 | weight、heartbeat 顺序 |
| registry 不做最终调度 | `FEAT-016.selection.no-server-pick` | blackbox/boundary | P1 | 返回完整候选而非单项 |
| DTO 完整字段 | `FEAT-016.view.system-fields` | blackbox + contract | P0 | 字段逐项断言 |
| 物理信息脱敏 | `FEAT-016.view.no-physical-leak` | blackbox | P0 | JSON key + canary 扫描 |
| Task 状态隔离 | `FEAT-016.view.no-task-state` | blackbox | P0 | JSON schema/递归 key 扫描 |
| opaque v2 handle | `FEAT-016.handle.opaque-v2` | blackbox + contract | P0 | 前缀、六字段 codec 合同 |
| 每实例独立 handle | `FEAT-016.handle.per-instance` | blackbox | P0 | 唯一性及一一解析 |
| handle 正常解析 | `FEAT-016.handle.resolve` | blackbox | P0 | RouteResolution |
| 畸形/旧 handle 拒绝 | `FEAT-016.handle.malformed` | blackbox + contract | P0 | 400 `malformed_handle` |
| v2 handle 扩展字段兼容 | `FEAT-016.handle.forward-compatible` | contract | P1 | 必需六字段正常解析、未知字段忽略 |
| handle 指向已删除实例 | `FEAT-016.handle.deleted` | blackbox | P0 | 404 `entry_not_found` |
| OFFLINE 旧 handle 禁止新路由 | `FEAT-016.handle.offline` | blackbox/known-gap | P0 | 404 或等价不可路由结果 |
| contractVersion 不过滤 | `FEAT-016.version.contract-unfiltered` | blackbox | P0 | 多版本并存 |
| contractVersion 精确过滤 | `FEAT-016.version.contract-filter` | blackbox | P0 | 三维入口参数化 |
| capabilityVersion 可见 | `FEAT-016.version.capability-visible` | blackbox | P0 | DTO 原值 |
| capabilityVersion 调用方排除 | `FEAT-016.version.capability-caller-filter` | dependency-gated | P0 | gateway/runtime 不转发不兼容候选 |
| ONLINE 可用 | `FEAT-016.health.online` | blackbox | P0 | health=ONLINE |
| DEGRADED 可能不可用 | `FEAT-016.health.degraded` | blackbox | P0 | health=DEGRADED |
| DRAINING 有限可用 | `FEAT-016.health.draining` | blackbox | P0 | health=DRAINING |
| OFFLINE 暂不可用 | `FEAT-016.health.offline` | blackbox | P0 | 查询结果排除 |
| 版本不匹配不可用 | `FEAT-016.health.version-incompatible` | blackbox + dependency-gated | P0 | registry 空结果/调用方投影 |
| 探活驱动 ONLINE/DEGRADED | `FEAT-016.health.probe-transition` | blackbox | P0 | 类内探活服务、状态和 heartbeat |
| 重注册状态机 | `FEAT-016.health.reregister` | blackbox + contract | P0 | DRAINING 保留、其他状态回 ONLINE |
| 探活配置影响窗口 | `FEAT-016.config.probe-window` | blackbox + contract | P1 | interval/stale/connect/read timeout |
| 同租户隔离 | `FEAT-016.tenant.query-isolation` | blackbox + contract | P0 | 相同 ID 不串租户 |
| bound TenantContext 交叉校验 | `FEAT-016.tenant.context-mismatch` | contract | P0 | TenantIsolationViolationException |
| 跨租户 resolve | `FEAT-016.tenant.resolve-mismatch` | blackbox | P0 | 400 `tenant_isolation_violation` |
| 禁止跨租户缓存 fallback | `FEAT-016.tenant.no-cache-fallback` | dependency-gated | P0 | caller cache 审计 |
| 反枚举 | `FEAT-016.security.anti-enumeration` | blackbox + dependency-gated | P0 | 不存在/不可见响应等价 |
| 缺少参数 fail-fast | `FEAT-016.error.invalid-request` | blackbox + contract | P0 | 400 `invalid_request` |
| 中心不可用显式失败 | `FEAT-016.failure.registry-unavailable` | blackbox | P0 | 5xx、无猜测路由 |
| 中心恢复 | `FEAT-016.failure.registry-recovery` | blackbox | P0 | 后续查询重新成功 |
| 本地有效路由降级 | `FEAT-016.caller.cached-route` | dependency-gated | P0 | degraded/cached 标记、成功转发 |
| 无本地路由显式失败 | `FEAT-016.caller.no-cache` | dependency-gated | P0 | discovery_unavailable/no_route |
| 缓存有效性窗口 | `FEAT-016.caller.cache-expiry` | dependency-gated | P0 | TTL 内成功、过期失败 |
| gateway 直连路由 | `FEAT-016.gateway.known-target` | dependency-gated | P0 | query -> resolve -> forward |
| gateway 无路由 | `FEAT-016.gateway.no-route` | dependency-gated | P0 | 可编程错误、不泄漏物理细节 |
| gateway 路由失败包装 | `FEAT-016.gateway.route-unavailable` | dependency-gated | P0 | `route_unavailable` |
| agent-runtime 代理查询 | `FEAT-016.runtime.proxy-query` | dependency-gated | P0 | ReActAgent 工具 + Mock 审计 |
| agent 可用性投影脱敏 | `FEAT-016.runtime.projection` | dependency-gated | P0 | 五态、无 endpoint/instance |
| agent 不直连 registry | `FEAT-016.runtime.no-direct-registry` | dependency-gated | P0 | 调用链审计 |
| agent 决策后 runtime 分发 | `FEAT-016.runtime.route-gate` | dependency-gated | P0 | agentId 输入、runtime 转发 |
| Agent Card 发现与路由查询分离 | `FEAT-016.boundary.not-card-discovery` | blackbox + dependency-gated | P0 | exact query、无目录/画像 |
| event-bus 不查询 registry | `FEAT-016.boundary.no-event-bus-query` | contract/boundary | P1 | 依赖与调用审计 |
| 不承载 token/payload | `FEAT-016.boundary.no-payload-channel` | blackbox | P1 | 响应 schema |
| 不暴露存储/探活实现 | `FEAT-016.boundary.storage-transparent` | blackbox | P1 | 响应和错误脱敏 |
| 不承诺复杂全局调度 | `FEAT-016.boundary.no-global-scheduling` | blackbox | P1 | 候选集合、selection hints 原样返回 |
| 不承诺无界离线 | `FEAT-016.boundary.no-unbounded-offline` | dependency-gated | P0 | cache 过期明确失败 |

## 4. Fixture 与证据设计

### 4.1 registry-center 外部 JAR

在 `application-openjiuwen.yml` 增加独立非 A2A SUT 别名：

```yaml
sut:
  agents:
    registry-center:
      group: com.openjiuwen
      artifact: registry-discovery-center
      version: 0.1.0
      ready-mode: tcp
      service-bindings:
        postgres:
          url-key: spring.datasource.url
          url-template: "jdbc:postgresql://{{url}}/agent_rdc"
  services:
    postgres:
      image: postgres:latest
      port: 5432
      env:
        POSTGRES_DB: agent_rdc
        POSTGRES_USER: agent_rdc
        POSTGRES_PASSWORD: agent_rdc
```

每个黑盒类使用 `BackingServices` + `TestContainerFactory` 启动真实 PostgreSQL，再通过 `SutStack`/`ProcessLauncher` 以 `readyMode(TCP)` 拉起 registry-center，并注入：

```text
spring.datasource.username=agent_rdc
spring.datasource.password=agent_rdc
spring.flyway.enabled=true
```

TCP ready 只证明端口已监听。测试必须继续轮询一个稳定 HTTP 查询，直到不再返回连接失败/启动中错误，确认 Flyway 已完成后才能准备数据。禁止自行实现 ProcessBuilder、端口探测和进程销毁。

### 4.2 类内 RegistryHttpFixture

`Feat016RegistryRouteQueryBlackboxTest` 内定义 private nested `RegistryHttpFixture`，使用 JDK `HttpClient`：

- 生成每方法唯一 tenantId、agentId、serviceId、capability、endpoint canary 和版本；
- `register(EntrySpec)` 调用真实 `POST /api/registry/register`；
- `queryByAgent/queryByService/queryByCapability` 返回 JsonNode，避免 acceptance 复制产品 DTO；
- `resolve(handle, tenantId)` 和三个粒度的 `deregister`；
- 记录 HTTP 状态、响应头和原始 JsonNode，但失败输出必须脱敏 endpoint canary；
- 不实现任何候选过滤、排序、handle 编码或租户规则，避免在 fixture 中复制被测逻辑。

正常测试数据全部通过该 HTTP fixture 注册。测试类内 private `RegistryStateFixture` 仅用于以下无公开控制面的准备动作：

- 把指定四字段 PK 的状态置为 `DEGRADED`、`DRAINING` 或 `OFFLINE`；
- 设置不同 `last_heartbeat` 以稳定验证次级排序；
- 创建受限数据库角色验证 RLS；
- 暂停/恢复 PostgreSQL 容器模拟中心不可用。

直接 SQL 只能出现在 Given/故障注入阶段，Then 必须通过公开 HTTP 或公开合同取证。禁止读取内部 key 作为产品成功断言。

### 4.3 contract 类内 fake

`Feat016RegistryRouteQueryContractTest` 内定义 private nested：

- `FakeRegistryRepository`：以不可变 `RegistryRow` 列表脚本化三维查询、排序前输入和 endpoint lookup；
- `SpyingTenantContext`：控制 bound/unbound/mismatch；
- `RecordingObservability`：只记录安全字段和 outcome；
- `HandleFixtures`：生成合法 v2、旧 v1、无前缀四字段、缺字段、坏 Base64 和额外字段样本；
- `RestrictedRoleFixture`：在真实 PostgreSQL fixture 中验证 RLS，不在 fake 内伪造数据库隔离。

所有 fake/mock 优先留在测试类内。只有至少两个 FEAT-016 测试文件出现完全相同、稳定且跨黑盒/合同都适用的代码后，才允许提取通用 TestSupport。

### 4.4 gateway/agent-runtime 调用方 fixture

`Feat016RouteQueryCallerIntegrationTest` 先保留设计，待调用方模块入口就绪后实现。类内 private nested fixture：

- `ScriptedRegistryServer`：JDK `HttpServer`，按 agent/service/capability 返回候选、五态、版本不匹配、fail-first-N、持续 5xx 和恢复；记录 method/path/query/tenant/请求序号，不记录 endpoint/token 明文。
- `MockRouteTarget`：JDK `HttpServer`，可返回成功、连接断开、超时和 5xx，证明调用方错误包装；不需要业务语义时优先使用它，不启动额外 Agent。
- `FakeClock` 或调用方公开的可控时钟/短 TTL 配置：验证缓存有效性窗口，禁止真实等待默认周期。

只有验证 agent 工具表面、agent 决策或 runtime 分发时才启动 ReActAgent。沿用 FEAT-005 的方式，使用：

```text
agent-solution/common/example/multi-react-travel-demo/agent-hotel
com.openjiuwen.example:travel-demo-hotel:0.1.0
```

增加独立 `hotel-route-query` acceptance 别名，使用调用方实现提供的独立 profile；不得修改 FEAT-003 的 `hotel` 或 FEAT-005 的 `hotel-skillhub` 别名。Mock Registry 必须先于 hotel 启动，随机 URL 通过 `AgentBuilder.property(...)` 注入。只有需要自然语言触发工具的用例依赖 LLM；其余 runtime contract 使用 fake Agent/tool invocation，减少模型不确定性。

registry 黑盒类另内置 private `MockProbeTarget`，只提供稳定的 2xx、5xx、断连和受控超时健康端点。它用于验证 registry 自身探活状态转换，与调用方转发用的 `MockRouteTarget` 分开计数，避免把探活请求误当作业务投递。

### 4.5 证据优先级

1. registry/gateway/runtime 的公开 HTTP 响应及实际转发结果。
2. Mock Registry/MockRouteTarget 请求审计，证明调用链、缓存命中和重试次数。
3. PostgreSQL 数据仅作为 setup 和隔离/状态的辅助证据。
4. 外部进程增量日志用于错误分类、恢复和脱敏，不作为唯一成功证据。
5. contract spy 用于无稳定 HTTP 入口的 TenantContext、RLS、Service 和 codec 合同。

## 5. 已知目标查询、多实例与视图子用例

框架落点：`Feat016RegistryRouteQueryBlackboxTest.java`。

### FEAT-016.query.agent-id / service-id / capability - 三个已知目标维度

- **G**：同租户注册 A1/S1/I1、A1/S1/I2、A2/S1/I3、A3/S2/I4；能力数组有交集和差集。
- **W**：分别按 A1、S1 和一个精确 capability 查询；再用不存在、不同大小写、前缀和自然语言模糊词查询。
- **T**：agentId 返回 I1/I2；serviceId 返回 I1/I2/I3；capability 只返回数组精确包含项；不存在及模糊词均为 `200 []`；三个入口均不返回 null。
- **方法**：`feat016KnownTargetQueriesSupportAgentServiceAndExactCapability()`。

### FEAT-016.query.unified-semantics - registry 三维及调用方统一语义

- **G**：一组记录同时被 agentId、serviceId、capability 命中，包含三种可见状态、两个版本和不同权重。
- **W**：对三个 registry 端点执行相同 contractVersion 查询；调用方就绪后再经 gateway 和 runtime 查询同一数据集。
- **T**：registry 三维的状态集合、版本过滤、排序和空结果语义一致；gateway/runtime 使用同一系统事实，只允许权限上下文和呈现视图不同。
- **方法**：`feat016QueryDimensionsShareFilteringOrderingAndEmptySemantics()`；调用方部分 dependency-gated。

### FEAT-016.multi.agent / multi.service - 多实例是正常形态

- **G**：同 agentId+serviceId 注册三个不同 endpoint；另一个 agentId 共享相同 serviceId。
- **W**：按 agentId 和 serviceId 查询。
- **T**：不发生覆盖或唯一键冲突；agentId 返回三个实例，serviceId 返回四个实例；每项 routeHandle 唯一；serviceId 可跨实例和 agent 共享。
- **方法**：`feat016QueriesReturnEveryConcreteInstanceWithoutAssumingUniqueAgentOrService()`。

### FEAT-016.identity.logical-physical / server-derived - 标识分层

- **G**：显式 serviceId=`wealth-svc`、endpoint 为两个 host-port；请求体额外带伪造 instanceId canary。
- **W**：注册、查询并解析每个 handle。
- **T**：DTO 显示逻辑 serviceId；查询 JSON 无 instanceId；resolve 恢复服务端从 endpoint 派生的真实 host-port，不接受 caller 伪造值；两个实例可区分。
- **方法**：`feat016ServiceIdIsLogicalAndInstanceIdIsServerDerivedAndOpaque()`。

### FEAT-016.selection.sorting / no-server-pick - 排序而非代选

- **G**：四实例权重分别为 80/100/100/120；两个 100 实例 heartbeat 不同。
- **W**：按三个维度查询。
- **T**：顺序严格为 weight 降序，同权重 heartbeat 新者优先；返回全部候选，不只返回首项；registry 不根据 region/maxConcurrency 做额外隐藏选择。
- **方法**：`feat016CandidatesAreSortedByWeightThenHeartbeatWithoutServerSideSelection()`。

### FEAT-016.view.system-fields - 系统路由视图

- **G**：注册包含所有可选字段的记录和省略可选字段的记录。
- **W**：读取三个查询入口 JSON。
- **T**：每项稳定包含 routeHandle、serviceId、health、contractVersion、capabilityVersion、weight、region、maxConcurrency；agentName/frameworkType 按合同可空；默认 weight/maxConcurrency 正确。
- **方法**：`feat016SystemRouteViewContainsStableRoutingAndSelectionFields()`。

### FEAT-016.view.no-physical-leak / no-task-state - 脱敏与 Task 隔离

- **G**：endpointUrl、routeKey、instanceId、数据库标识和伪 Task 字段均使用唯一 canary。
- **W**：查询并递归扫描字段名和值；触发空结果和错误响应。
- **T**：DTO 不含 endpointUrl、routeKey、instanceId、topic、databaseKey、probe、task、taskState、hierarchy、progress、contextMemory 或 orchestration；响应值不含物理 canary。只有 resolve 的转发层响应可出现 endpoint/routeKey/instanceId。
- **方法**：`feat016QueryViewLeaksNeitherPhysicalRoutingDetailsNorTaskState()`。

## 6. 版本、健康与 route handle 子用例

### FEAT-016.version.contract-unfiltered / contract-filter - 契约版本

- **G**：同一已知目标存在 contractVersion v1/v2，均处于可见状态。
- **W**：三个查询入口分别省略版本、传 v1、传不存在版本。
- **T**：省略时返回 v1/v2；传 v1 只返回 v1；不存在版本返回 `200 []`；版本不匹配候选不进入结果。
- **方法**：参数化 `feat016ContractVersionFilterAppliesToEveryQueryDimension()`。

### FEAT-016.version.capability-visible / capability-caller-filter - 能力版本

- **G**：同一 capability 注册 capabilityVersion 1 和 2。
- **W**：registry 查询；调用方就绪后请求 capabilityVersion=2 的路由可用性。
- **T**：registry DTO 原样返回 capabilityVersion；registry 不虚构未定义的 capabilityVersion query 参数；gateway/runtime 必须在最终路由 gate 排除不兼容版本并投影为 version-incompatible，而不是转发错误版本。
- **方法**：`feat016CapabilityVersionIsExposedForCallerCompatibilityDecision()`；调用方过滤部分 dependency-gated。

### FEAT-016.health.online / degraded / draining / offline - 四种注册状态

- **G**：用真实注册创建四个实例，再由 state fixture 精确置为 ONLINE、DEGRADED、DRAINING、OFFLINE。
- **W**：按可命中全部实例的 serviceId/capability 查询。
- **T**：ONLINE、DEGRADED、DRAINING 均返回且 health 原样；DRAINING 不被误过滤；OFFLINE 不进入新候选；排序规则不因状态被偷偷改写。
- **方法**：`feat016DiscoveryReturnsOnlineDegradedAndDrainingButExcludesOffline()`。

### FEAT-016.health.version-incompatible - 第五种可用性语义

- **G**：目标存在，但所有候选 contractVersion 与请求不兼容。
- **W**：registry 带版本查询；调用方投影就绪后执行同一请求。
- **T**：registry 返回空列表，不把不兼容候选当作可路由；调用方可以表达 version-incompatible，但不得泄漏 endpoint 或实例信息。
- **方法**：`feat016VersionMismatchNeverProducesARoutableCandidate()`；投影部分 dependency-gated。

### FEAT-016.health.probe-transition / config.probe-window - 探活和新鲜度窗口

- **G**：registry 使用短且唯一的 probe interval/stale-before/connect/read timeout；类内 MockProbeTarget 为不同实例脚本化 2xx、5xx、断连、超时和恢复；state fixture 把 heartbeat 调整到 due 窗口。
- **W**：等待调度器完成探活并通过公开查询轮询状态，不使用固定 `Thread.sleep`。
- **T**：2xx 把具体实例置 ONLINE 并刷新 heartbeat；5xx/断连/超时只把对应实例置 DEGRADED 且不刷新成功 heartbeat；恢复 2xx 后回 ONLINE；配置窗口变化可观察地影响判定时间；其他实例和 tenant 不受影响。
- **方法**：`feat016HealthProbeTransitionsEachConcreteInstance()`、`feat016HealthProbeTimeoutAndConnectFailureRemainBoundedPerInstance()`、`feat016ProbeIntervalAndStaleWindowAreExternallyConfigurable()`；connect/read 外部配置由 known-gap `feat016ProbeConnectAndReadTimeoutsAreExternallyConfigurable()` 保留规格预期。

### FEAT-016.health.reregister - 状态机与重注册

- **G**：同一 agent/service 下准备 DRAINING、DEGRADED、OFFLINE 三个不同 instance；记录 heartbeat。
- **W**：对三实例以相同四字段身份重新 POST register；contract 中验证 DRAINING -> OFFLINE 的实例级 updateStatus。
- **T**：DRAINING 重注册后保持 DRAINING；DEGRADED/OFFLINE 重置为 ONLINE 并刷新注册事实；运维把 DRAINING 置 OFFLINE 后查询排除；任何转换只作用于指定 instanceId。
- **方法**：`feat016ReregistrationPreservesDrainingAndResetsOtherStatesToOnline()`。

### FEAT-016.handle.opaque-v2 / per-instance - 引用格式和唯一性

- **G**：同一逻辑目标三个实例。
- **W**：读取 handle，逐个 resolve；contract 中通过公开 Service 生成后由 codec 合同验证。
- **T**：每项 handle 非空、`v2:` 前缀且互不相同；解码合同恰含 tenantId/agentId/serviceId/instanceId/routeKey/contractVersion 六字段；黑盒不得自行解码后把内部 JSON 当成功证据。
- **方法**：`feat016EveryInstanceReceivesAUniqueOpaqueV2RouteHandle()`。

### FEAT-016.handle.resolve - 转发层解析

- **G**：从三个不同查询维度各取一个合法 handle。
- **W**：POST resolve，tenantId 与 handle 一致。
- **T**：返回正确 instanceId、endpointUrl、routeKey、contractVersion；不会返回 Task、capability 画像或其他实例数据；三个入口产生的 handle 解析语义相同。
- **方法**：`feat016ForwardingLayerResolvesHandlesFromEveryQueryDimension()`。

### FEAT-016.handle.malformed - 畸形和旧格式

参数化输入：null/blank、无 `v2:`、未知前缀、坏 Base64、非 JSON、缺任一六字段、非文本字段、空字段、旧无前缀四字段、旧 `v1:` 五字段。

- **T**：HTTP 400；稳定 `error=malformed_handle`，请求体本身缺 routeHandle/tenantId 时为 `invalid_request`；不输出解码后的物理 canary。
- **方法**：`feat016MalformedAndLegacyHandlesAreRejected(String caseId)`。

### FEAT-016.handle.forward-compatible - v2 扩展字段

- **G**：contract fixture 构造包含必需六字段及未来 `consulAddr`、`consulPort` 等未知字段的合法 v2 handle。
- **W**：通过公开 resolve Service/codec 合同解析。
- **T**：必需六字段正常解析，未知字段被忽略且不进入 RouteResolution；缺少任一必需字段仍失败。只允许同一 v2 envelope 的附加字段，不恢复 v1/无前缀兼容。
- **方法**：`feat016V2HandleIgnoresFutureFieldsButStillRequiresCurrentContract()`。

### FEAT-016.handle.deleted - 已删除实例

- **G**：查询取得 handle 后，通过四字段注销单实例。
- **W**：再次查询并 resolve 旧 handle。
- **T**：其他副本仍可查询；旧 handle 返回 404 `entry_not_found`；不得 fallback 到同 serviceId 的另一个实例。
- **方法**：`feat016DeletedInstanceHandleDoesNotFallbackToAnotherReplica()`。

### FEAT-016.handle.offline - OFFLINE 旧 handle

- **状态**：known-gap，P0。
- **G**：取得 handle 后把对应实例置 OFFLINE，记录同 serviceId 其他在线副本。
- **W**：查询目标并 resolve 旧 handle。
- **T**：新查询排除 OFFLINE；旧 handle 不得解析为可用于新路由的 endpoint，期望 404 `entry_not_found` 或稳定不可路由错误；不得 fallback 到其他副本。
- **方法**：`feat016OfflineInstanceHandleCannotBeResolvedForNewRouting()`。
- **已知风险**：当前 `findEndpoint` SQL 未带状态条件，本用例可能暴露缺陷；禁止把预期改成成功解析。

## 7. 租户、反枚举与错误子用例

### FEAT-016.tenant.query-isolation - 同名目标隔离

- **G**：tenant-A/B 使用相同 agentId/serviceId/capability，物理 canary 和版本不同。
- **W**：分别通过三个维度查询。
- **T**：每次只返回参数 tenant 的记录；routeHandle 不可跨 tenant 使用；结果、排序和版本过滤不混入另一租户。
- **方法**：`feat016AllQueryDimensionsRemainTenantScopedForIdenticalTargetIds()`。

### FEAT-016.tenant.context-mismatch - TenantContext 与 RLS

- **G**：contract 测试绑定 tenant-A 后调用 tenant-B；真实 PG restricted role 下设置 `app.tenant_id=tenant-A` 并准备 A/B 数据。
- **W**：调用三个 Service 方法和 repository 查询。
- **T**：Service 抛 TenantIsolationViolationException；RLS 只能读 A；无 bound context 的 HTTP 路径仍由显式 tenantId + SQL WHERE 约束。
- **方法**：`feat016BoundTenantContextAndDatabaseRlsProvideDefenseInDepth()`。

### FEAT-016.tenant.resolve-mismatch - 跨租户 handle

- **G**：tenant-A 取得合法 handle。
- **W**：以 tenant-B 调 resolve。
- **T**：400 `tenant_isolation_violation`；不查询或返回 A 的 endpoint；错误不泄漏 A 的目标是否在线。
- **方法**：`feat016RouteHandleCannotBeResolvedByAnotherTenant()`。

### FEAT-016.security.anti-enumeration - 反枚举等价

- **G**：准备不存在目标、另一租户同名目标、OFFLINE-only 目标、版本不匹配目标；权限系统就绪后增加不可见目标。
- **W**：对外查询各场景，规范化 traceId/timestamp 后比较。
- **T**：registry 查询均为 `200 []`，不出现“存在但不可访问”、真实 tenant、endpoint、状态或版本暗示；权限表面允许显式权限失败时，其消息仍不得证明目标存在。
- **方法**：`feat016NoMatchInvisibleOfflineAndIncompatibleTargetsDoNotLeakExistence()`。

### FEAT-016.error.invalid-request - 参数 fail-fast

- **G/W**：对三个查询入口参数化空白/编码空白 tenant 和目标；resolve 请求体缺字段；contract 直接传 null/blank。
- **T**：HTTP 400 `invalid_request`；Service/repository 抛稳定参数异常；不进入默认 tenant，不执行跨租户扫描。缺失 path segment 若被 Spring 路由层映射为 404，单独记录框架路由语义，不伪报为 controller 的 400。
- **方法**：`feat016BlankRequiredParametersFailFastWithoutDefaultTenant()`。

## 8. 中心故障、恢复与生命周期子用例

### FEAT-016.failure.registry-unavailable - registry 中心不可用

- **G**：registry 正常完成一次查询；记录 PostgreSQL 容器和进程基线。
- **W**：暂停 PostgreSQL，再发真实查询和 resolve。
- **T**：registry 显式 5xx/连接失败，不返回历史候选、不猜测 endpoint、不跨租户；日志可诊断但不泄漏 datasource 密码或 endpoint canary。
- **方法**：与恢复场景合并到 `feat016RegistryQueriesRecoverAfterPostgresReturns()`，在同一 registry PID 下先验证显式失败再验证恢复。

### FEAT-016.failure.registry-recovery - 中心恢复

- **G**：沿用上一个故障模型。
- **W**：恢复 PostgreSQL，轮询后再次查询并 resolve。
- **T**：无需重启 registry 即恢复权威结果；结果仍遵守 tenant、状态、版本和排序；连接数不单调泄漏。
- **方法**：`feat016RegistryQueriesRecoverAfterPostgresReturns()`。

### FEAT-016.registry.lifecycle - registry 进程生命周期

- **G**：PostgreSQL 保持运行并注册数据。
- **W**：三轮 stop/start registry-center，每轮执行三维查询和一次 resolve。
- **T**：PID 改变、端口按框架复用/重新解析、Flyway 幂等、数据仍在、无旧进程监听或连接泄漏。
- **方法**：`feat016RegistryRestartPreservesRoutesWithoutLeakingProcessesOrConnections()`。

## 9. gateway 与 agent-runtime 依赖用例

框架落点：`Feat016RouteQueryCallerIntegrationTest.java`。本节所有用例必须保留在设计和追溯中；调用方入口未交付前不生成空壳 `@Test` 假通过。

### FEAT-016.gateway.known-target - gateway 直连

- **G**：真实 registry 或 ScriptedRegistryServer 返回多个候选；MockRouteTarget 对选中 endpoint 返回唯一结果。
- **W**：client 通过 gateway 调用已知 agentId/serviceId/capability。
- **T**：gateway 查询、按自身策略选择、resolve 并转发；client 只看到业务结果，不看到候选集合、endpoint、instanceId、topic 或数据库信息。
- **方法**：`feat016GatewayRoutesKnownTargetWithoutExposingPhysicalAddress()`。

### FEAT-016.gateway.no-route / route-unavailable - 可编程失败

- **G**：分别配置空候选、版本不匹配、resolve 404、目标连接失败/超时/5xx。
- **W**：client 发起同一已知目标调用。
- **T**：空候选返回 no_route；物理投递失败包装为 `route_unavailable` 或稳定等价错误；错误不包含 endpoint、IP、实例 ID 或内部异常栈；不猜测其他 tenant/target。
- **方法**：`feat016GatewayReturnsProgrammableNoRouteAndRouteUnavailableErrors()`。

### FEAT-016.caller.cached-route / no-cache / cache-expiry - 短时自治

- **G**：gateway/runtime 先成功获取并使用 tenant-A 路由；配置短且可控的有效性窗口。
- **W**：registry 变为不可用；分别在 TTL 内命中缓存、从未查询过目标、TTL 后重试；再恢复 registry。
- **T**：TTL 内仅同 tenant 已知目标可继续并可观察为 degraded/cached；无缓存返回 discovery_unavailable/no_route；过期后明确失败，不无限离线；恢复后重新获取权威结果并替换旧缓存。
- **方法**：`feat016CallerUsesOnlyFreshSameTenantCachedRouteAndRecoversToAuthority()`。

### FEAT-016.tenant.no-cache-fallback - 缓存租户隔离

- **G**：tenant-A 缓存命中，tenant-B 使用相同 agentId 且中心不可用。
- **W**：B 发起路由请求。
- **T**：B 不得复用 A 缓存，必须显式失败；MockRouteTarget 不收到请求；诊断不泄漏 A 存在性。
- **方法**：`feat016CallerNeverFallsBackAcrossTenantCaches()`。

### FEAT-016.runtime.proxy-query / projection - ReActAgent 工具表面

- **G**：先启动类内 ScriptedRegistryServer，再以独立 `hotel-route-query` profile 启动 travel-demo-hotel；Mock 返回五种投影输入和物理 canary。
- **W**：通过 A2A 向 hotel 发送明确已知 agentId/capability 的查询指令；必要时使用可用 LLM。
- **T**：Mock 审计证明请求由 agent-runtime 代理；agent 输出只包含已知目标、可选 serviceId、简化状态和版本可用性，不含 routeHandle、endpoint、instanceId、候选画像、Task 内部状态或探活实现。
- **方法**：`feat016RuntimeProjectsKnownTargetAvailabilityToReactAgentWithoutPhysicalDetails()`。

### FEAT-016.runtime.no-direct-registry / route-gate - 代理和最终分发

- **G**：ReActAgent 只获得 runtime 注入工具；已知目标返回两个候选，MockRouteTarget 记录最终调用。
- **W**：agent 用 agentId 表达下游目标并提交任务。
- **T**：agent 不持有 registry 原始 client/endpoint，不自行 resolve handle；runtime 完成 query、最终兼容性判断、resolve 和转发；agent 决策不能绕过 runtime route gate。
- **方法**：`feat016ReactAgentSelectsByLogicalTargetWhileRuntimeOwnsPhysicalRouting()`。

## 10. 显式边界子用例

### FEAT-016.boundary.not-card-discovery - 不替代 Agent Card 发现

- **G**：registry 有多个 capability 相似但不精确相同的目标；react agent card 另有业务 skill 描述。
- **W**：调用 exact capability 路由查询；再尝试自然语言、前缀、schema、画像和“列出所有 agent”式输入。
- **T**：registry 只做精确已知目标查询，不返回自由文本候选目录、工具 schema、Agent Card 画像或“谁会做什么”；runtime 工具不把路由查询包装为候选发现。
- **方法**：`feat016RouteQueryDoesNotBecomeAgentCardOrSemanticDiscovery()`。

### FEAT-016.boundary.no-event-bus-query - event-bus 边界

- **G**：contract 架构扫描和调用审计。
- **W**：执行 registry 查询及 event-bus 独立路径。
- **T**：registry 查询合同不依赖 event-bus；FEAT-013/014 路径不被本特性改写为 event-bus 查询 registry；不为本测试启动 broker。
- **方法**：`feat016RegistryQueryContractDoesNotDependOnEventBus()`。

### FEAT-016.boundary.no-payload-channel / storage-transparent - 数据面和物理实现边界

- **G**：注册内容含大 payload/token/数据库/探活 canary。
- **W**：查询、resolve、错误和恢复。
- **T**：查询 DTO 不承载 token、消息正文、多模态内容、topic、outbox、worker、PG/Consul/PGVector、数据库 key 或探活细节；resolve 只返回转发所需四字段。
- **方法**：与 selection hints 边界合并到 `feat016RouteQueryIsMetadataOnlyAndReturnsHintsWithoutGlobalScheduling()`。

### FEAT-016.boundary.no-global-scheduling - 不承诺复杂调度

- **G**：候选带不同 region/maxConcurrency/weight，并提供产品未承诺的资源水位/成本输入。
- **W**：查询候选。
- **T**：registry 只按合同排序并返回 selection hints；不隐藏候选、不执行容量预留、全局限流、跨区域成本计算或运维驱动调度。
- **方法**：复用 `feat016RouteQueryIsMetadataOnlyAndReturnsHintsWithoutGlobalScheduling()`。

### FEAT-016.boundary.no-unbounded-offline - 不无限离线

- **G/W/T**：由 `caller.cache-expiry` 覆盖；缓存过期后必须失败，不能持续使用旧 endpoint。
- **方法**：复用 `feat016CallerUsesOnlyFreshSameTenantCachedRouteAndRecoversToAuthority()`。

## 11. 框架落点汇总

| Java 文件 | blackbox | contract/dependency | 类内私有 fixture |
|---|---|---|---|
| `Feat016RegistryRouteQueryBlackboxTest.java` | 查询、多实例、视图、版本、状态、handle、错误、故障、生命周期 | 少量 setup-only DB 控制 | RegistryHttpFixture、RegistryStateFixture、Postgres 生命周期、日志切片 |
| `Feat016RegistryRouteQueryContractTest.java` | - | Service、DTO、Repository、RLS、TenantContext、codec、架构边界 | FakeRegistryRepository、SpyingTenantContext、HandleFixtures、RestrictedRoleFixture |
| `Feat016RouteQueryCallerIntegrationTest.java` | gateway/runtime/E2E（入口就绪后） | dependency-gated 缓存、投影、route gate | ScriptedRegistryServer、MockRouteTarget、FakeClock、react-agent 启动配置 |

落点目录：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/agent_bus/
```

只生成上述三个 Java 文件。每个文件使用 `@Nested` 按 query、view、handle、tenant、failure、gateway、runtime 分组；不把每个子特性拆成独立类。Mock、fake、JSON builder、数据规格 record 和审计记录优先放在使用它的测试类中。

## 12. version-scope 全量追溯

| version-scope 要求 | 本档落点 |
|---|---|
| 已知目标路由查询 | query.agent-id/service-id/capability |
| 统一查询语义 | query.unified-semantics |
| agent-runtime 代理查询 | runtime.proxy-query、runtime.no-direct-registry |
| 多实例候选 | multi.agent、multi.service |
| 运行时实例标识 | identity.logical-physical、identity.server-derived |
| 路由引用 | handle.* |
| 路由可用性投影 | runtime.projection |
| 租户隔离 | tenant.*、caller.no-cache-fallback |
| 版本约束 | version.*、health.version-incompatible |
| 健康与可用性 | health.online/degraded/draining/offline/version-incompatible |
| 中心短时不可用降级 | caller.cached-route/no-cache/cache-expiry |
| 反枚举保护 | security.anti-enumeration |
| Task 状态隔离 | view.no-task-state |
| 物理细节透明 | view.no-physical-leak、boundary.storage-transparent |
| gateway 直连已知 agent | gateway.known-target |
| gateway 无可用路由 | gateway.no-route |
| agent 下发已知目标任务 | runtime.route-gate |
| 中心不可用且本地有效 | caller.cached-route |
| 中心不可用且本地缺失 | caller.no-cache |
| 路由可用性反馈给 agent | runtime.projection |
| 中心恢复 | failure.registry-recovery、caller.cached-route |
| 查询参数错误 | error.invalid-request |
| tenant 不匹配 | tenant.context-mismatch、tenant.resolve-mismatch |
| 无权限/目标不存在 | security.anti-enumeration、query.empty |
| 路由失败 | gateway.route-unavailable |
| 非 Agent Card 发现 | boundary.not-card-discovery |
| 不经 event-bus 查询 | boundary.no-event-bus-query |
| agent 不直连 registry | runtime.no-direct-registry |
| client 不获得物理地址 | gateway.known-target、runtime.projection |
| 不做复杂全局调度 | boundary.no-global-scheduling |
| 不规定具体缓存结构 | caller 只断言 TTL/行为，不断言 key/介质/算法 |
| 禁止跨 tenant fallback | tenant.no-cache-fallback |
| 不查询 Task 状态 | view.no-task-state |
| 不承载 token/payload | boundary.no-payload-channel |
| 不绑定对外存储产品 | boundary.storage-transparent |
| 不承诺无界离线 | boundary.no-unbounded-offline |

## 13. L2 设计追溯

| L2 设计点 | 本档落点 |
|---|---|
| AgentDiscoveryService 三维查询合同 | query.agent-id/service-id/capability、query.unified-semantics |
| AgentCardDto 系统路由视图 | view.system-fields、view.no-physical-leak、view.no-task-state |
| RouteResolution 转发层视图 | handle.resolve、gateway.known-target、runtime.route-gate |
| v2 六字段 opaque handle | handle.opaque-v2、handle.malformed、handle.forward-compatible |
| 多实例四字段 PK | multi.agent、multi.service、identity.* |
| weight/heartbeat 排序 | selection.sorting |
| RLS set_config 与 TenantContext | tenant.query-isolation、tenant.context-mismatch |
| ONLINE/DEGRADED 探活状态转换 | health.probe-transition、config.probe-window |
| DRAINING/OFFLINE 状态机与重注册 | health.draining/offline/reregister、handle.offline |
| probe interval/stale/connect/read timeout | config.probe-window |
| 中心不可用与恢复 | failure.registry-unavailable/recovery、caller.* |
| gateway 直连流程 | gateway.known-target/no-route/route-unavailable |
| agent-runtime 代理与投影 | runtime.proxy-query/projection/no-direct-registry/route-gate |
| HTTP 查询和 resolve 入口 | query.*、handle.resolve |
| invalid_request/tenant/malformed/not-found 错误 | error.invalid-request、tenant.resolve-mismatch、handle.malformed/deleted |
| 限制与不承诺项 | boundary.*、caller.cache-expiry |

## 14. 当前实现与 L2 差异追溯

| 事项 | 当前测试口径 | 处理 |
|---|---|---|
| serviceId/capability 查询 | 已实现独立查询入口 | blackbox P0 覆盖；L2 待更新 |
| contractVersion | 三维入口均支持可选精确过滤 | blackbox P0 覆盖；L2 待更新 |
| capabilityVersion | DTO 返回，由调用方判断；registry 无过滤参数 | registry 验字段，调用方 dependency-gated 验最终排除 |
| DRAINING | discovery 返回并表示有限可用 | blackbox P0 覆盖；L2 待更新 |
| OFFLINE | 新查询排除 | blackbox P0 覆盖 |
| OFFLINE 旧 handle | 应不可用于新路由；当前实现疑似仍可解析 | known-gap 缺陷哨兵 |
| probe connect/read timeout | 文档声明可配置；当前公开构造路径固定为 2000 ms | 默认有界行为可执行验证；外部配置能力保留 known-gap 缺陷哨兵 |
| 本地缓存 | gateway/runtime 职责 | dependency-gated，不进入 registry 缺陷 |
| gateway/agent-runtime | 不在 registry 模块 | 完整保留调用方测试，当前不生成假实现 |

## 15. 标签与运行方式

```java
@Feature("016")
@Tag("feat-016")
@Tag("integration")
class Feat016RegistryRouteQueryBlackboxTest {
    @Test
    @Tag("blackbox")
    @Story("多实例已知目标查询")
    @DisplayName("Feat-016 agentId/serviceId/capability 查询返回全部可路由实例")
    void feat016KnownTargetQueriesSupportAgentServiceAndExactCapability() { }
}
```

dependency-gated 用例在入口未交付时使用专用标签和条件，不得以空断言通过：

```java
@Tag("dependency-gated")
@EnabledIfSystemProperty(named = "feat016.callers.enabled", matches = "true")
```

运行命令：

```bash
# 当前可执行的 registry 黑盒 + contract
./mvnw -Dtest.env=openjiuwen -Dgroups=feat-016 test

# 指定类
./mvnw -Dtest.env=openjiuwen \
  -Dtest=Feat016RegistryRouteQueryBlackboxTest test

# 调用方模块交付后
./mvnw -Dtest.env=openjiuwen \
  -Dfeat016.callers.enabled=true \
  -Dtest=Feat016RouteQueryCallerIntegrationTest test
```

## 16. 代码生成约束

1. version-scope 的所有能力和边界必须有测试、依赖用例或明确负向证据，不得因当前模块未实现 gateway/runtime 而删除。
2. 当前实现口径采用开发确认的三维查询、contractVersion 过滤和 DRAINING 可见语义；同时保留 L2 差异表，禁止悄悄混用旧接口。
3. registry 黑盒必须拉起真实外部 JAR 和真实 PostgreSQL；禁止用 Mock Service/Repository 替代 SUT 后标为 blackbox。
4. 正常数据通过 `/register`、`/deregister` 准备；只有无公开入口的状态、heartbeat、RLS 和数据库故障允许类内 setup fixture。
5. contract artifact 版本必须与外部 registry JAR 一致；不一致直接失败。
6. 只创建本档列出的三个 Java 文件；通过 `@Nested` 合并相关场景，不新增按单用例拆分的测试类。
7. Mock Registry、MockRouteTarget、fake Repository、数据 builder 和审计器优先作为对应测试类的 private nested fixture。
8. Mock 只模拟上下游依赖，不复制 registry 的排序、过滤、租户或 handle 产品逻辑。
9. 需要 ReActAgent 时只使用 `multi-react-travel-demo/agent-hotel` 的独立 `hotel-route-query` 别名/profile，参考 FEAT-005 的 hotel-skillhub 启动方式；不得修改已有 hotel/hotel-skillhub 语义。
10. 只有自然语言触发 runtime 工具的用例依赖 LLM；可通过公开 API/fake Agent 触发的合同不得引入 LLM。
11. 缓存测试使用可控时钟或短配置窗口，禁止等待默认长周期和直接 `Thread.sleep`。
12. 黑盒断言使用 JsonNode/HTTP，不在 acceptance 复制产品 DTO；contract 测试可以直接使用公开类型。
13. 不解析 opaque handle 作为黑盒主要证据；格式细节只在 codec contract 中验证。
14. 日志使用启动前 offset 切片；脱敏失败只报告命中类别和日志路径，不回显 endpoint、tenant 或凭据 canary。
15. OFFLINE 旧 handle 与 probe connect/read timeout 外部配置用例保持规格预期；当前失败应报告产品缺陷，不得改成接受现有行为。
16. dependency-gated 用例未实现时不得生成空 `@Test`、恒真断言或假 Mock 自测；保留设计和追溯即可。
17. 不为了测试修改 registry、gateway、agent-runtime 或 react-agent-demo 产品源码，不增加测试专用产品端点。

## 17. 退出标准

- 三个测试文件均可由 `feat-016` 标签发现，Allure 能区分 blackbox、contract、dependency-gated 和 known-gap。
- version-scope 的全部 MUST、用户旅程、错误语义、显式边界和下游约束均在全量追溯表中有落点。
- registry 当前三维查询、多实例、排序、系统视图、contractVersion、四状态、handle、租户、反枚举、故障恢复和生命周期均有真实外部证据。
- Service/DTO/Repository/RLS/TenantContext/codec 中无稳定外部入口的合同有直接 contract 证据。
- gateway、agent-runtime、缓存降级、可用性投影、最终 route gate 和 route failure 没有被遗漏；对应模块交付后无需重写设计即可生成测试。
- Mock/fake/数据 builder 均优先收敛在三个测试类内部，没有为单特性提前创建通用 TestSupport。
- ReActAgent 场景明确使用 `multi-react-travel-demo/agent-hotel` 独立 profile，且不污染 FEAT-003/005 已有别名。
- OFFLINE 旧 handle 风险有 P0 缺陷哨兵，probe connect/read timeout 配置缺口有 P1 缺陷哨兵；capabilityVersion 的 registry/caller职责有清晰分层。
