---
scope: v0.1.3
deployable_units: [agent-runtime-java]
sut: openJiuwen agent-runtime-java
features: [FEAT-043]
updated: 2026-09-08
---

# FEAT-043 A2A 安全通信与出站认证接入 验收测试方案

## 1. 测试目标

本方案验证 `agent-runtime` 在 FEAT-043 范围内的安全契约：A2A 入站通信复用 Runtime 已有的服务级 TLS/mTLS 与通用授权底座（不新增 A2A 专属安全体系），出站远端 A2A 调用接入已有 `ExternalAuthenticator` 认证 SPI 与通用目标级 TLS。验证重点落在「出站安全」这一新增能力面，同时确认「入站复用」未被安全改造破坏既有 A2A 协议表面。

本方案按测试层次分两层呈现（§5.1 集成层、§5.2 SIT 层），二者口径分离：集成层断言出站安全的技术契约（SPI 认证 Header 注入、认证材料缓存、目标地址一致性、目标 TLS、mTLS、协议限制、目标隔离），可由本地真实 HTTP/TLS 握手完整验证；SIT 层断言真实部署拓扑下的入站复用、认证失败前置拦截、callback 出站安全与端到端兼容。**集成层全绿不等于 SIT 验收通过**——两者在 §8 分列执行。

本方案要证明：入站不论调用来源都复用同一套 TLS/mTLS 与通用授权；出站默认路径对每个远端目标准备并缓存独立的安全材料、失败不降级、材料不进入 A2A 协议对象；未启用任何安全能力时保留存量 SDK provider 与匿名行为。

## 2. 范围与非范围

范围：

- 入站：Agent Card 双入口、`/a2a`、callback receiver 复用服务级 TLS/mTLS 与通用授权（`a2a/rpc`、`agent-card/read`、`a2a-push-callback/receive`），以及通用授权拒绝的 403 表面。
- 入站：认证失败前置拦截（需求 §5.5 SHOULD，部署接入认证层时）——认证失败请求在进入 Runtime A2A 业务处理前被拒绝，不创建 Task、不执行 Agent。
- 出站认证：`ExternalAuthenticator` 返回的 `AuthMaterial.headers` 注入实际 HTTP 请求（JSON-RPC 与 SSE）；认证材料缓存于目标客户端创建时一次取得；认证目标与实际有效 URL 一致；认证材料失败不回退匿名；query 认证参数发送前拒绝。
- 出站 TLS：目标 TrustStore/KeyStore 到达真实 TLS 握手；mTLS 客户端证书；`enabled-protocols` 限制实际握手协议；协议无交集时握手失败不绕过；目标间 TLS 材料隔离；证书链/主机名校验失败不降级明文。
- 协议隔离与诊断：认证材料不进入 A2A Message/Part/Task/Artifact 或业务 metadata；认证/TLS 失败诊断不携带凭据。
- callback 出站：复用既有 HTTPS sender 与 A2A 标准 token/authentication，不新增 callback 专属安全配置。
- 兼容：未启用任何安全能力时保留 SDK provider 与匿名行为；不新增 tenant 接收/传递能力。

非范围（及各自的承接方）：

- 不测 tenant 上下文、Task 归属、Task 级租户 ACL、A2A 专属 JWT/API Key 校验器、内置企业 IAM、自定义消息体加密、动态凭据/Token 刷新/OAuth2-OIDC——需求 §2 共七项 OUT（第 41/53/54/55/56/57/58 行）明确不实现；本方案仅以负面断言验证「不新增」（G2），不把 tenant 当验收能力，不实现项无功能用例（承接说明见 §6.3）。
- 不测 A2A Task 状态机与生命周期语义的**正向**行为（COMPLETED/FAILED 收束、`GetTask` 快照、SSE 断开续查等）——由 FEAT-001 / FEAT-003 / FEAT-004 测试方案承接，本特性仅声明「不因安全改造改变既有 A2A 语义」并回归关键入口可达性。
- 不测远端调用并发/限流/超时语义（`timeout-seconds`、`max-concurrency`、`max-queue-size`）——需求 §5.7 明确「本特性不新增安全专属容量或超时配置」，既有并发资源由 DFX-002 承接。
- 不测 `verify-hostname=false` 关闭主机名校验的语义——需求 §5.4 第 127 行明确「不保证关闭主机名校验，不列为当前支持能力」。
- 不测 Card 安全声明（`securitySchemes`/`securityRequirements`）的配置与解析——需求 §5.3 第 114 行「本特性不新增或配置…也不要求 Runtime 解析远端 Card 的安全声明」。
- 不测网关/服务网格/身份提供方的内部实现——需求 §5.6 第 153 行「不规定网关…的具体产品和配置方式」；B4 只从「部署接入认证层后 Runtime 侧的可观察行为」验证，不测认证层本身。
- 时序/并发维度：目标安全材料的串用防护在**并发**语义下的验证，以及远端调用并发控制，由 DFX-002 承接（L2 §6.5「资源规划复用 DFX-002 及实际部署配置」）；本方案仅验证**顺序**条件下的目标隔离（D6）——该维度归属已显式交代，非遗漏。
- 状态机非法迁移：本特性不改变 A2A Task 状态机（需求 §2「标准协议兼容」+「未启用兼容」），状态机非法迁移由 FEAT-001/003/004 承接；本方案无该维度适用场景。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-043-a2a-secure-communication-and-authentication.md`（2026-09-08 版） | 能力表（§2，第 37-58 行）、外部接口（§3）、行为语义与边界（§5.1~5.7）、下游约束（§6）——本方案断言的唯一契约基线。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-043-a2a-secure-communication-and-authentication.md`（2026-09-08 版） | 外部服务面（§2.1~2.4）、核心运行模型（§3.1）、出站安全材料映射（§5.4）、配置项（§6）、验收标准（§7）——用于把抽象安全语义钉到可断言行为与错误面。 |
| `openJiuwen/agent-runtime-java` @ `42be09d`（develop，含 !186） | 当前实现事实与集成层用例落点：`A2ARemoteAgentClient`、`A2ARemoteAgentClientSecurityTest`、`A2ARemoteAgentClientTlsTest`。 |
| `agent-solution-zhangdengjiecai/common/example/bank-intent-acceptance-demo`（`com.openjiuwen.example:bank-intent-acceptance-demo:0.1.0`，FEAT-020 既有 example 复用） | 端到端承载与落点：`feat043` profile 隔离 TLS/认证（不动 FEAT-020 默认明文链路），6 个 agent-runtime（intent 18200 + 下游 18201~18205）+ `acceptance-tests`，`mock_reranker.py`（127.0.0.1:18099）。 |

引用的需求/L2 行号基于 docs 本地 clone `D:\workspaceidea\docs` @ `b3cfc0c4`；章节号是稳定引用，行号仅作定位辅助，漂移以章节号为准。被测产物与源码的对应关系见 §8（T-M19 二进制指纹）。

## 4. 部署拓扑

### 4.1 SIT 验收拓扑（入站复用 + callback 出站）

```text
SIT test driver（A2A SDK client + 底层 HttpClient / 自制 TLS 探针）
  -> agent-runtime（真实 SUT：服务级 TLS + 通用授权 + A2A 入口 + /v1/query 对照入口）
  <- 下游远端 Agent（mock，MockRemoteAgentServer：HttpServer/HttpsServer 模拟 A2A 表面）
  <- MockCallbackReceiver（JDK HttpServer，观察 runtime 出站 callback POST）
```

- 入站断言对象是真实部署的 agent-runtime：`/.well-known/agent-card.json`、`/.well-known/agent.json`、`/a2a`、`/a2a/`、callback receiver 五个入口。
- `/v1/query` 作为对照入口，用于确认「同一服务端口的 TLS/授权与 A2A 共享同一底座」这一不变式（A1/B1 的对照面）。

### 4.2 出站安全集成拓扑（目标 TLS/mTLS/认证，本地可完整验证）

```text
SIT test driver / 集成测试
  -> agent-runtime（出站调用客户端 A2ARemoteAgentClient）
       -> MockRemoteAgentServer（受控远端：HttpsServer 自定义证书/协议/要求客户端证书，
                                  记录到达请求的 Authorization、path、协商协议）
  <- ExternalAuthenticator（应用 Bean，返回 AuthMaterial + 记录被调用的目标/次数）
```

- 出站安全的技术断言（C/D/E 族）在集成层通过「真实 socket + 真实 TLS 握手 + 应用 SPI Bean」验证，不依赖真实 LLM 或业务 Agent——这与需求 §5.4 的可判定契约（Header 注入、协议握手、失败不降级）直接对应。

边界要求：

- SIT driver 只通过公开面观察 SUT：HTTP 状态码、TLS 握手结果（server 侧协商协议）、A2A JSON-RPC 响应、请求到达 Mock 远端时的 Header/path/body。对 SUT 内部状态、私有日志、私有 SPI 均不读；「未创建 Task」这类结论必须通过**后续 `GetTask` 返回 not_found** 这一 wire 观察点证明（B1/B3/B4），不直接断言内部状态。
- 「认证材料不进入协议对象」通过 Mock 远端收到的 body 逐字段扫描证明（body 不含 Authorization/凭据），而非读 SUT 内部对象（满足黑盒边界）。
- `ExternalAuthenticator` 是测试自建的应用 Bean，其「被调用次数/收到的 target」是**测试夹具的可观察面**（不读 SUT 内部状态），用于验证需求 §5.4「缓存项创建时调用一次、非逐请求」的对外语义。

### 4.3 example 端到端拓扑（复用 FEAT-020 `bank-intent-acceptance-demo` 多服务，承载出站安全完整链路）

FEAT-043 的端到端验证复用既有 `bank-intent-acceptance-demo`（FEAT-020 意图匹配 example），不新建独立 demo；以 `feat043` profile 叠加 TLS/认证配置、隔离 FEAT-020 默认明文链路。进程与端口：

```text
intent-agent-runtime            :18200  出站调用下游（出站安全面所在侧）
balance-agent-runtime           :18201
transfer-agent-runtime          :18202
wealth-advisor-agent-runtime    :18203
wealth-purchase-agent-runtime   :18204
general-assistant-agent-runtime :18205
mock_reranker.py                :18099  意图重排桩（真实 LLM/reranker 替身）
```

- 5 个下游（balance/transfer/wealth-advisor/wealth-purchase/general-assistant）在 `feat043` profile 下启用入站 HTTPS（服务级 TLS）；intent-agent 出站走 TLS + 认证 Header，对应 C/D 族「出站安全到真实下游」的端到端断言。
- TLS 材料由 `TlsMaterialGenerator` 在测试 setUp 运行期生成到 `<demo>/target/feat043-tls/`（`server.p12` + `client-trust.p12`），不硬编码固定 keystore 路径，用后清理。

## 5. 测试场景矩阵

场景 ID 的字母前缀表示能力族（A=入站传输、B=入站授权与拦截、C=出站认证、D=出站 TLS、E=协议隔离与诊断、F=callback、G=兼容与 tenant 边界），与测试层次无关；测试层次由所在子节决定。索引：

| 族 | 能力 | 测试层次 |
|---|---|---|
| A / B / F / G | 入站传输 · 入站授权与拦截 · callback · 兼容与 tenant 边界 | SIT（§5.2） |
| C / D / E | 出站认证 · 出站 TLS · 协议隔离与诊断 | 集成（§5.1） |

覆盖状态列：`已有` = 该断言已由 agent-runtime-java 上游合入的 `A2ARemoteAgentClientSecurityTest` / `A2ARemoteAgentClientTlsTest` 覆盖；`新增` = 本方案需补。

### 5.1 集成层场景矩阵（出站安全，本地真实 HTTP/TLS 握手可验证）

#### C 族 · 出站认证

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 覆盖 |
|---|---|---|---|---|---|
| C1 | 远端 Agent 认证 Header 注入 | 应用注册 `ExternalAuthenticator` 返回 `AuthMaterial.headers`（携带固定 `Authorization` 头值） | 分别发起 JSON-RPC 与 SSE 远端调用 | Mock 远端收到的 HTTP 请求头 `Authorization` = 该 Header 值（JSON-RPC 与 SSE 均注入）（需求 §5.4「生成 AuthMaterial」；L2 §2.4「注入认证 Header」） | 已有 |
| C2 | 认证材料缓存（非逐请求） | 同 C1，`ExternalAuthenticator` 记录被调用次数 | 同一目标先后发起多次 JSON-RPC/SSE 调用 | 同一目标同键仅首次调用 SPI，后续复用固定 Header；不同目标各自调用一次（需求 §5.4「SPI 在缓存项创建时调用…不是逐请求调用 SPI」） | 已有 |
| C3 | 认证目标地址一致性 | Card 含 gRPC 首个接口 + 多个 JSON-RPC 接口 | SPI 记录收到的 `target.url`，发起调用 | `target.url` = 第一个兼容 JSON-RPC 接口按 SDK 规则构造的有效 URL，非发现基址或非兼容接口 URL（需求 §5.4「按 SDK 规则选择第一个 JSON-RPC 接口…认证目标与缓存键使用 SDK 构造的有效 URL」） | 已有 |
| C4 | 无兼容 JSON-RPC 接口前置失败 | Card 无 JSON-RPC 接口 | 发起远端调用 | 抛出 `A2AClientException`；SPI 未被调用、Mock 远端未收到任何 HTTP 请求（L2 §2.4「抛出 A2AClientException，不调用认证 SPI、不发送 HTTP」） | 已有 |
| C5 | 认证器失败不回退匿名 | `ExternalAuthenticator` 抛异常 | 发起远端调用 | 调用失败（转为现有远端失败表面，具体异常类型由应用 SPI 决定，需求/L2 未指定）；Mock 远端未收到任何 HTTP 请求（证明未降级发起匿名调用）；失败结果不含 Authorization/凭据值（需求 §5.4「抛出不含凭据的异常」；L2 §2.4「不回退到无认证调用」） | 已有 |
| C6 | query 认证参数发送前拒绝 | `ExternalAuthenticator` 返回非空 `queryParams` | 发起远端调用 | 调用失败；Mock 远端未收到任何 HTTP 请求（发送前即拒绝，不静默忽略或降级）（需求 §5.4「非空 query 认证参数在发送前拒绝，不能静默忽略或降级」） | 已有 |
| C7 | 未提供认证 SPI 保留匿名 SDK provider 路径 | 未注册 `ExternalAuthenticator`、未启用目标 TLS | 发起远端调用 | 调用成功、请求到达远端，且**无** `Authorization` 请求头（保留 SDK provider 匿名路径）（需求 §5.4「未启用时保留 SDK provider」；L2 §2.4「保留 SDK provider…不新增认证 Header」） | 已有 |

#### D 族 · 出站 TLS

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 覆盖 |
|---|---|---|---|---|---|
| D1 | 目标 TrustStore 到达真实 TLS 握手 | 远端 HttpsServer 用自签证书；SUT 配置目标 `trust-store`（密码以加密形式提供，触发通用 `CredentialDecryptor` 解密） | 发起 HTTPS 远端调用 | TLS 握手成功、业务请求到达远端（需求 §5.4「目标 TLS 复用通用 inline/global 配置、证书加载」+ §2「凭据解密复用」） | 已有 |
| D2 | mTLS 客户端证书到达真实握手 | 远端要求客户端证书，SUT 配置目标 `key-store` | 发起远端调用 | 双方完成 mTLS 握手，请求到达远端（需求 §2「原生远端 Agent TLS」客户端证书；L2 §2.4「key-store」） | 已有 |
| D3 | mTLS 缺客户端证书失败于发送前 | 远端要求客户端证书，SUT 未配置客户端证书 | 发起远端调用 | 握手失败，Mock 远端未收到业务请求（L2 §7.1「缺少客户端证书时 mTLS 失败」） | 已有 |
| D4 | `enabled-protocols` 限制实际握手协议 | SUT 配置目标 TLS `enabled-protocols=[TLSv1.2]` | 发起远端调用，远端记录协商协议 | 实际协商协议为 TLSv1.2（∈ 配置列表）（需求 §5.4「通用 JDK 客户端将解析后的 enabled-protocols 应用到真实握手」） | 已有 |
| D5 | TLS 协议无交集握手失败不绕过 | SUT 仅允许 TLSv1.2，远端仅允许 TLSv1.3 | 发起远端调用 | 握手失败、Mock 远端未收到业务请求，不绕过配置重试其他协议、不降级明文（L2 §2.4「TLS 协议没有交集…握手失败，不绕过配置重试其他协议」） | 已有 |
| D6 | 目标间 TLS 材料隔离 | 目标 A 配置 TrustStore，目标 B 不配置（B 为自签证书目标） | 先调 A 成功，再调 B | B 调用握手失败（`SSLHandshakeException` 类），不串用 A 的 TrustStore（需求 §5.4「防止不同目标的 Token、证书或信任设置被意外串用」） | 已有 |
| D7 | 证书链/主机名校验失败不降级明文 | 目标证书不可信或主机名不匹配 | 发起远端 HTTPS 调用 | 调用失败；不降级为明文 HTTP、不自动关闭校验（需求 §5.4「默认执行证书链和主机名校验」；L2 §2.4「不降级为明文 HTTP，不自动关闭校验」） | 已有 |

#### E 族 · 协议隔离与诊断

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 覆盖 |
|---|---|---|---|---|---|
| E1 | 认证材料不进入协议对象 | C1 场景，Mock 远端记录收到的 body | 逐字段扫描远端收到的 `params`/Message/Part | body 全文不含 `Authorization`/凭据值（认证材料仅 HTTP 层）（需求 §5.4「安全材料不得进入 A2A Message、Task、Artifact 或业务 metadata」） | 已有 |
| E2 | 认证/TLS 失败诊断不含凭据 | C5/D7 失败场景 | 捕获调用失败抛出的异常对象与诊断内容 | 异常对象/诊断字符串不含 Authorization/API Key/私钥/密码值（需求 §5.5「应用 SPI/Client 应提供不含凭据的异常和诊断内容」+ §2「日志与诊断安全」） | 已有 |

### 5.2 SIT 层场景矩阵（入站复用 · 认证失败前置拦截 · callback · 兼容，真实部署）

#### A 族 · 入站传输安全复用

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 覆盖 |
|---|---|---|---|---|---|
| A1 | A2A 与 `/v1/query` 共享服务端口与 TLS | SUT 启用服务级 TLS | 分别以 HTTPS 访问 `/.well-known/agent-card.json`、`/a2a`、`/v1/query`；再以明文 HTTP 访问三者 | 三者同端口且均受 TLS 保护：HTTPS 三入口均返回 200/可达；明文 HTTP 访问不返回业务 A2A 响应（连接失败或非 2xx，且响应体不含 A2A Task/message 结构）（需求 §5.1「同一 Runtime 服务端口上的不同协议入口」） | 新增 |
| A2 | mTLS 客户端证书控制 | SUT 配置 `client-auth=need` | 无合法客户端证书访问 Agent Card / `/a2a` / `/v1/query` | TLS 握手失败（连接被拒/握手错误），请求不进入业务、无 A2A 响应（需求 §5.5「TLS/mTLS 握手失败由传输层直接拒绝，不进入 Agent 执行」） | 新增 |
| A3 | 不新增 A2A 专属端口/过滤链 | SUT 正常启动 | 枚举 SUT 监听端口 | 仅一个服务端口承载 A2A 与 `/v1/query`，无独立 A2A 监听端口（需求 §5.1「不新增独立 TLS 配置、独立证书生命周期或默认独立端口」） | 新增 |

#### B 族 · 入站授权复用与认证失败前置拦截

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 覆盖 |
|---|---|---|---|---|---|
| B1 | `a2a/rpc` 通用授权拒绝返回 403 | 启用通用授权并配置 `a2a/rpc` 拒绝策略 | 向 `/a2a` 发合法 JSON-RPC `SendMessage`（授权策略判定拒绝） | 返回 HTTP 403（Runtime 统一 403 表面）；随后以该请求应有的 taskId 查 `GetTask` 返回 not_found（证明未创建 Task）（需求 §5.5「通用授权拒绝继续使用 Runtime 统一的 HTTP 403 表面」；L2 §3.1「不进入后续 Task 创建或 Agent 执行」以 GetTask not_found 作 wire 侧印证） | 新增 |
| B2 | `agent-card/read` 授权门控 | 启用通用授权，配置 `agent-card/read` 拒绝 | GET `/.well-known/agent-card.json` | 返回 403；允许策略时返回 200 + AgentCard JSON（L2 §2.2「agent-card/read 是否允许匿名仍由通用授权策略决定」） | 新增 |
| B3 | callback receiver 复用入站授权 | 启用通用授权 + callback receiver 暴露 | 未授权 POST 合法 shape 的 `/a2a/push-notifications/callback` | 返回 403；该通知对应 Task 状态不因本次投递改变（后续 `GetTask` 返回原状态）（L2 §2.1「复用同一入站安全底座」+ §3.1「callback 接收入口复用该机制」） | 新增 |
| B4 | 认证失败前置拦截 | 部署接入外部认证层 | 认证失败请求发往 `/a2a` | 返回认证层定义的失败响应（如 401）；不产生 Task（后续 `GetTask` 返回 not_found）（需求 §5.5；L2 §3.1 三种失败类型表）。**未接入外部认证层时本条判 INCONCLUSIVE**（L2 §3.1「未接入外部认证层时，该部署集成验收项标记为不适用」） | 新增 |

#### F 族 · callback 出站安全

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 覆盖 |
|---|---|---|---|---|---|
| F1 | callback 复用 HTTPS sender + A2A 标准 token | SUT 启用 Push Notification，callback URL 为 HTTPS | 触发一次需 callback 的 Task 完成 | callback POST 经 HTTPS 到达 MockCallbackReceiver（URL scheme=https，非明文 HTTP）；不新增 callback 级 trust-store/mTLS 配置（需求 §2「callback 出站安全 MUST（已有能力）」+ §4「复用现有 HTTPS/TLS 和 A2A 标准 token/authentication」；具体 authentication 字段由 FEAT-017 callback 既有用例定义） | 新增 |

#### G 族 · 兼容与 tenant 边界

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | 覆盖 |
|---|---|---|---|---|---|
| G1 | 未启用安全能力保留存量行为 | 未注册认证 SPI、未启用目标 TLS | 发起远端调用 + 入站 `SendMessage` | 出站：调用成功、远端收到的请求无 Authorization 头（匿名 SDK provider 路径）；入站：`SendMessage` 返回标准 A2A Task 结构且 message/parts 字段齐全（数据结构兼容不变）（需求 §2「未启用安全能力兼容 MUST」+ §5.5「未配置安全能力时保持现有 A2A method 和数据结构兼容」） | 已有+新增 |
| G2 | 不新增 tenant 接收/传递 | SUT 就绪 | `SendMessage` 携 `params.tenant=xxx` 与不带 tenant 各发一次；`GetTask` 携 tenant；访问 `/a2a/{tenant}` | 带 tenant 与不带 tenant 的 `SendMessage` 返回同结构 Task（messageId/parts 一致，不因 tenant 值改变结果或报错）；`GetTask` 携 tenant 返回与不带相同的快照；`/a2a/{tenant}` 路径返回不支持（404/405 类，不新增该路由）（需求 §5.2 第 105/108 行） | 新增 |

## 6. 覆盖台账（用例 ↔ 契约 双向）

### 6.1 契约 → 用例

| 需求 §2 能力（级别） | 覆盖用例 |
|---|---|
| 复用通用入站传输安全（MUST） | A1, A2 |
| 不新增 A2A 专属安全端口（MUST） | A3 |
| 复用通用入站授权（MUST） | B1, B2, B3 |
| 可信身份前提（MUST） | 不新增用例（见 §6.3 排除理由） |
| A2A tenant 上下文适配（OUT） | G2（仅负面断言） |
| 原生远端 Agent TLS（SHOULD，配置后） | D1~D7 |
| 原生远端 Agent 认证（MUST，认证时） | C1~C7 |
| 认证目标地址一致性（MUST） | C3 |
| callback 出站安全（MUST，已有能力） | F1 |
| 远端调用出站安全（SHOULD，配置后） | C1, D1~D7 |
| 凭据解密复用（SHOULD，配置后） | D1（加密口令触发 CredentialDecryptor 解密） |
| 认证材料协议隔离（MUST） | E1 |
| 日志与诊断安全（MUST，责任边界内） | E2 |
| 标准协议兼容（MUST） | E1, G1 |
| 认证失败前置拦截（SHOULD，部署接入认证时） | B4 |
| 未启用安全能力兼容（MUST） | G1 |
| Task 与 tenant 服务端绑定（OUT） | G2（仅负面断言） |
| Task 级租户访问控制与跨调用方保密性（OUT） | 不新增用例（见 §6.3） |
| A2A 专属 JWT/API Key 校验器（OUT） | 不新增用例（见 §6.3） |
| 内置企业 IAM（OUT） | 不新增用例（见 §6.3） |
| 自定义消息体加密（OUT） | 不新增用例（见 §6.3） |
| 动态凭据/Token 刷新/OAuth2-OIDC（OUT，SDK 边界） | 不新增用例（见 §6.3） |

### 6.2 用例 → 契约

| ID | 断言的不变式 | 对应需求 §2 能力 |
|---|---|---|
| A1 | 同一服务端口共享 TLS | 复用通用入站传输安全 |
| A2 | mTLS 握手失败不入业务 | 复用通用入站传输安全 |
| A3 | 无独立 A2A 端口 | 不新增 A2A 专属安全端口 |
| B1 | a2a/rpc 授权 403 + 无 Task | 复用通用入站授权 |
| B2 | card/read 授权门控 | 复用通用入站授权 |
| B3 | callback receive 授权门控 | 复用通用入站授权 |
| B4 | 认证失败前置拦截 | 认证失败前置拦截 |
| C1 | 认证 Header 注入 | 原生远端 Agent 认证 / 远端调用出站安全（认证面） |
| C2 | 认证材料缓存一次 | 原生远端 Agent 认证 |
| C3 | 认证目标地址一致 | 认证目标地址一致性 |
| C4 | 无兼容接口前置失败 | 原生远端 Agent 认证 |
| C5 | 认证失败不回退 | 原生远端 Agent 认证 |
| C6 | query 参数拒绝 | 原生远端 Agent 认证 |
| C7 | 无 SPI 保留 provider | 未启用安全能力兼容 |
| D1 | 目标 TrustStore 生效 + 凭据解密 | 原生远端 Agent TLS / 凭据解密复用 / 远端调用出站安全（TLS 面） |
| D2 | mTLS 客户端证书 | 原生远端 Agent TLS / 远端调用出站安全（TLS 面） |
| D3 | 缺证书发送前失败 | 原生远端 Agent TLS / 远端调用出站安全（TLS 面） |
| D4 | 协议限制生效 | 原生远端 Agent TLS / 远端调用出站安全（TLS 面） |
| D5 | 协议无交集不绕过 | 原生远端 Agent TLS / 远端调用出站安全（TLS 面） |
| D6 | TLS 材料目标隔离 | 原生远端 Agent TLS / 远端调用出站安全（TLS 面） |
| D7 | 校验失败不降级 | 原生远端 Agent TLS / 远端调用出站安全（TLS 面） |
| E1 | 材料不入协议对象 | 认证材料协议隔离 / 标准协议兼容 |
| E2 | 诊断不含凭据 | 日志与诊断安全 |
| F1 | callback 复用 HTTPS+token | callback 出站安全 |
| G1 | 未启用保留存量行为 | 未启用安全能力兼容 / 标准协议兼容 |
| G2 | 不新增 tenant 能力 | A2A tenant 上下文适配（OUT） |

### 6.3 不覆盖项与承接方

| 不覆盖项 | 原因（技术可行性已逐条检验） | 承接方 |
|---|---|---|
| 可信身份前提（MUST，第 40 行） | 这是**部署边界声明**而非 Runtime 可执行能力：用户/空间/租户 Header 的「不可伪造性」由网关/mTLS/部署安全边界保证，Runtime 侧只能「不宣称已认证」——该不变式以「文档声明 + 不新增反向能力」表达，在 agent-runtime 模块内无代码路径可测 | 部署平台安全验收（网关/mTLS 证书链），非 agent-runtime 特性测试对象 |
| Task 级租户 ACL（OUT）、A2A 专属 JWT/API Key 校验器（OUT）、内置企业 IAM（OUT）、自定义消息体加密（OUT）、动态凭据/OAuth2（OUT） | 需求 §2 明确 OUT（不实现）。OUT 项的正确验收形态是「验证不新增」（负面断言，由 G2、A3 部分承载），不存在可测试对象 | 无（不实现即无测试对象）；未来若转 MUST 需另立特性 |
| A2A Task 状态机正向语义 | 本特性不改 Task 语义（需求 §2「标准协议兼容」+「未启用兼容」）；状态机已被 FEAT-001/003/004 覆盖，本方案仅回归入口可达性 | FEAT-001 / FEAT-003 / FEAT-004 testplan |
| 远端调用并发/限流/超时 | 需求 §5.7 声明「本特性不新增安全专属容量或超时配置」，既有参数非本特性契约 | DFX-002 testplan |
| 目标安全材料的并发串用防护 | L2 §6.5「资源规划复用 DFX-002 及实际部署配置」；并发维度由 DFX-002 承接，本方案仅验证顺序目标隔离（D6） | DFX-002 testplan |
| `verify-hostname=false` 关闭校验语义 | 需求 §5.4 第 127 行明确「不保证关闭主机名校验，不列为当前支持能力」 | 无（不承诺能力） |

## 7. 关键链路断言

- **入站复用**：Agent Card、`/a2a`、`/v1/query` 共用同一服务端口的 TLS/mTLS 与通用授权；通用授权拒绝统一 403 且「未创建 Task」（以 `GetTask` not_found 作 wire 侧印证）；`a2a/rpc`、`agent-card/read`、callback receive 三个入口同机制。
- **出站认证**：认证 Header 由 `ExternalAuthenticator` 按目标一次取得并缓存，注入实际 HTTP 请求；认证目标与 SDK 实际有效 URL 一致；认证失败/无材料要求认证时不得回退匿名（以 Mock 远端未收到请求作 wire 侧印证）；query 认证参数发送前拒绝。
- **出站 TLS**：目标 TLS 材料到达真实握手；`enabled-protocols` 实际限制协商；mTLS 缺客户端证书与协议无交集均应在发送业务请求前失败；证书链/主机名校验失败不得降级明文；不同目标的 TLS/认证材料不得串用。
- **协议隔离与诊断**：认证材料只存在于 HTTP/TLS 传输层，绝不进入 A2A Message/Part/Task/Artifact 或业务 metadata；认证/TLS 失败诊断不携带凭据值。
- **兼容**：未启用任何安全能力时，出站保留 SDK provider + 匿名（无 Authorization 头）、入站保持标准 A2A 数据结构；本特性不新增 tenant 接收/传递（带/不带 tenant 结果同构）。
- SIT 侧对观察不到的能力（未接入外部认证层、无需 tenant 功能）判 INCONCLUSIVE 而非 FAIL，判定条件见各用例行。

## 8. 执行策略

被测产物与源码对应（T-M19）：集成层用例运行于 `openJiuwen/agent-runtime-java` @ `42be09d` 构建的 `agent-service-app` 模块 jar；运行前核对 jar 内 `BOOT-INF/lib/agent-service-app-*.jar` 版本与本次 develop 合入 commit 一致，防止跑旧产物假绿。

- 集成层（C/D/E 族，现可执行）：`MockRemoteAgentServer`（HttpServer/HttpsServer 自定义证书、协议、`setNeedClientAuth`）+ 应用 `ExternalAuthenticator` Bean 驱动；`A2ARemoteAgentClientSecurityTest` / `A2ARemoteAgentClientTlsTest` 已在上游合入。

```bash
mvn -o -pl service/agent-service-app -Dtest=A2ARemoteAgentClientSecurityTest,A2ARemoteAgentClientTlsTest test
```

- example 端到端层（复用 §4.3 `bank-intent-acceptance-demo`）：承载出站安全到真实下游的完整链路。启动 6 个 agent-runtime（`SPRING_PROFILES_ACTIVE=feat043`）+ `mock_reranker.py`（127.0.0.1:18099）；TLS 材料由 `TlsMaterialGenerator` 测试 setUp 运行期生成到 `<demo>/target/feat043-tls/`（`server.p12` + `client-trust.p12`）。

```bash
mvn -pl acceptance-tests test -Dtest=Feat043A2aSecurityAcceptanceTest -Dgroups=feat-043
```

- SIT 层（A/B/F/G 族）：依赖真实部署（服务级 TLS、mTLS 证书、通用授权策略、HttpsServer skill），在 §4.3 的 `bank-intent-acceptance-demo` 上新增部署级依赖桩（通用授权策略 Bean、HttpsServer skill、callback receiver）后补齐测试用例；本方案已定格场景断言（§5.2），其测试用例与执行命令待依赖桩就绪后补充——列为存疑项（见下）。
- B4（认证失败前置拦截）未接入外部认证层时判 INCONCLUSIVE（L2 §3.1 明确），不计 FAIL。
- 每条用例使用独立目标名与 callback id，避免缓存/回调串扰；TLS 材料用临时生成的测试证书，不硬编码固定 keystore 路径，用后清理。
- P0 准入必跑集（集成层，须当前可跑且全绿）：C1、C4、C5、C6、C7、D1、D3、D4、D5、D7、E1；其余为全量集。

存疑项（T-M8）：

1. SIT 层 A/B/F/G 族用例在 §4.3 的 `bank-intent-acceptance-demo` 上补齐（含服务级 TLS、mTLS、通用授权策略、HttpsServer skill 依赖桩），其测试用例与启动参数/命令一并补充；本方案只定格场景断言，不虚构未存在的执行命令。
2. F1 的 callback `authentication` 具体字段由 FEAT-017 既有 callback 用例定义，FEAT-043 不新增专属字段；本方案不重复定义该 wire 字段。

## 附录 A. 相对设计基线的关键取舍

| 取舍 | 对用例的影响 |
|---|---|
| 出站安全为「接入既有通用 SPI/TLS」而非「新建 A2A 专属安全体系」 | C/D 族断言挂在通用 `ExternalAuthenticator`/`ExternalTlsConfig` 契约上，不发明 A2A 私有配置字段 |
| 认证目标地址 = 第一个兼容 JSON-RPC 接口 + SDK `Utils.buildBaseUrl` | C3 断言 `target.url` 等于 SDK 构造的有效 URL（含尾斜杠/Card 接口路径行为），而非 Card 发现基址 |
| `verify-hostname=false` 不承诺关闭校验 | 不设 D 族「关闭校验」负例；D7 只断言「默认执行校验、失败不降级」 |
| tenant 七项 OUT 表示不实现 | G2 只做「不新增」负面断言，不写 tenant 功能用例 |
| 认证失败前置拦截为部署接入认证层时的 SHOULD | B4 按部署条件判 INCONCLUSIVE/通过，不写成 Runtime 新增认证能力 |
| 层次分离 | 集成层（出站安全）全绿不替代 SIT 层（入站复用/端到端）验收结论，§8 分列执行与门禁口径 |