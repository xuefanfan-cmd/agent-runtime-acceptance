---
feature_id: FEAT-005
feature_title: 智能体中间件请求代理
sut: multi-react-travel-demo hotel（远程 Skill Hub profile）
scope: Skill Hub middleware 配置、默认 Provider、自定义 SPI、下载、完整性校验、降级重试、请求期注册、诊断、安全与生命周期
status: designed
owner: TBD
priority: P0
tags: [integration, contract, openjiuwen, feat-005]
depends_on:
  - agent-service-spec-ext 与 agent-service-adapters-agentcore-ext 的 FEAT-005 artifacts 已安装到本地 Maven 仓库
  - multi-react-travel-demo agent-hotel 已增加独立 skillhub-remote profile、Skill runtime 接线并使用 JiuwenCoreAgentExtHandler
  - agent-runtime-acceptance application-openjiuwen.yml 已增加 hotel-skillhub 别名，继续指向 travel-demo-hotel:0.1.0
  - Feat005SkillHubHotelBlackboxTest 已提供类内可脚本化 JDK Mock Skill Hub
  - 仅验证 skill 最终业务效果的子用例需要可用 LLM
related_docs:
  - FEAT-005-agent-middleware-request-proxy-reactagent.md
  - Feat-Func-005-agent-middleware-request-proxy.md
---

# FEAT-005 — 智能体中间件请求代理测试用例设计

> **一句话**：由 acceptance 在测试 JVM 内启动 Mock Skill Hub，并拉起 FEAT-003 同源的 multi-react travel hotel 外部 JAR，验证默认/自定义 Provider、认证、下载、校验、降级重试、请求期首次注册、幂等、日志脱敏和生命周期；没有稳定外部观测面的 SPI、Manager 与 Installer 行为由 acceptance contract tests 验证。

> **裁决规则**：version-scope 与 L2 不一致时以 L2 为准；L2 标注“第一期不做”“当前项目无路径”“后续演进”的内容按当前期边界验证，不作为产品缺陷。

> **仓库边界**：FEAT-005 产品实现位于 `agent-runtime-ext-java`；可执行 SUT 位于 `agent-solution/common/example/multi-react-travel-demo/agent-hotel`；Mock Skill Hub 和本档生成的验收测试代码位于 `agent-runtime-acceptance`。Mock 只模拟远端 HTTP 服务，禁止在 acceptance 内复制 SkillHub Provider 产品实现。

## 1. 当前期事实口径

测试生成必须采用以下 L2 语义：

- 启动期执行 Provider start、skill 下载和完整性校验；请求期执行 `register(agent)`。
- required 的配置、连接、认证、授权、查找、下载或校验失败均降级 ready，并在请求链路外后台重试。
- required 的 `INSTALL_FAILED` 在请求线程抛出；optional 任一失败 warn + skip，Agent 保持 ready。
- 完整性校验算法由 Provider 自决；测试只强制“verify 未通过的材料不得进入未安装列表、不得移交或注册”。
- 后台线程只允许 download、verify 和维护未安装列表，不得调用 Installer 或接触 Agent SkillManager。
- 第一期不由 Agent 的 `skills:[{id,version,required}]` 驱动下载；Provider 决定应下载集合，Manager 处理所有下载且校验通过的路径。
- 注册成功后从未安装列表移入已安装列表；普通后续请求不得重复注册或自动刷新。
- `reregister(agent)` 是显式合同，可重新注册全部已安装和未安装路径；其存在不等于自动热刷新。
- agent-id 字符串场景不支持安装；当前主黑盒 SUT 必须传入 `ReActAgent`/`BaseAgent` 实例。

## 2. 状态定义

- **contract**：在 acceptance 中通过 test-scope FEAT-005 artifacts、fake Provider/Installer、测试 Agent 或 Mock HTTP Server 验证公开合同。
- **blackbox**：测试 JVM 先启动类内 Mock Skill Hub，再通过 `SutStack` 启动外部 hotel JAR，仅使用 A2A、进程状态、Mock 审计和增量日志取证。
- **env-gated**：依赖真实 openJiuwen Skill Hub、真实凭据或客户自定义 Provider JAR；不作为本地 P0 门禁。
- **deferred-l2**：L2 明确第一期不做或当前路径不支持；必须有边界用例证明当前实现没有暗中承诺该能力。

## 3. 总覆盖矩阵

| 能力 | 子用例 ID | 类型 | 优先级 | 主要证据 |
|---|---|---|---|---|
| disabled/no Provider 不影响 Agent | `FEAT-005.config.disabled` | contract + blackbox | P0 | Spring context、Agent card、A2A |
| 配置默认值与条件装配 | `FEAT-005.config.defaults-and-conditions` | contract | P0 | Properties 默认值、FilteredClassLoader |
| 默认配置绑定与默认 Provider | `FEAT-005.config.default-provider` | contract | P0 | Properties Bean、Provider Bean |
| 自定义 Provider 覆盖默认实现 | `FEAT-005.config.custom-provider` | contract | P0 | Bean back-off、调用审计 |
| 部署态配置不依赖请求上下文 | `FEAT-005.config.stable-deployment` | contract | P1 | 多请求入参和 Provider 调用参数 |
| 第一期 Provider 决定全部 skill 集合 | `FEAT-005.config.provider-selected-set` | contract + blackbox | P0 | 两个远端 skill 均下载/注册 |
| system-token 认证 | `FEAT-005.security.system-token` | contract | P0 | Mock 请求头 |
| bearer 认证 | `FEAT-005.security.bearer` | contract | P0 | Mock 请求头 |
| CredentialDecryptor 与日志脱敏 | `FEAT-005.security.credential-redaction` | contract + blackbox | P0 | decrypt spy、全日志 canary 扫描 |
| endpoint/内容脱敏 | `FEAT-005.security.endpoint-content-redaction` | contract + blackbox | P0 | 错误、日志、Mock 审计 |
| 安装与降级诊断字段 | `FEAT-005.diagnostics.fields` | contract + blackbox | P0 | tenantId/agentId/count/category/correlation |
| LocalSkillEntry 数据合同 | `FEAT-005.provider.local-entry` | contract | P1 | skillId/path 传递和顺序 |
| openJiuwen API 调用顺序和落盘 | `FEAT-005.provider.protocol-download` | contract | P0 | `/plugins`、artifact、localDir |
| 部分下载返回失败并重试 | `FEAT-005.provider.partial-download` | contract | P0 | download=false、成功项不误注册 |
| HTTP/连接错误分类 | `FEAT-005.provider.error-categories` | contract | P0 | 参数化 401/403/404/断连 |
| Provider 生命周期 | `FEAT-005.provider.lifecycle` | contract | P0 | start/stop 次数、资源关闭 |
| Provider SHA-256 校验成功 | `FEAT-005.integrity.sha256-success` | contract | P0 | verify=true、随后可注册 |
| 无摘要常规校验成功 | `FEAT-005.integrity.conventional-success` | contract | P0 | 非空/可读/SKILL.md fixture |
| 校验失败拒绝注册 | `FEAT-005.integrity.reject-invalid` | contract + blackbox | P0 | 未安装列表/Installer 调用为零 |
| 启动下载成功进入未安装列表 | `FEAT-005.manager.download-success` | contract | P0 | download→verify→pending 顺序 |
| required 启动失败降级重试 | `FEAT-005.manager.required-degraded` | contract + blackbox | P0 | ready、分类日志、retry |
| optional 任一失败跳过 | `FEAT-005.manager.optional-skip` | contract + blackbox | P0 | ready、warn、未注册 |
| 后台线程职责边界 | `FEAT-005.manager.retry-thread-boundary` | contract | P0 | 线程/调用审计 |
| 后台恢复后请求期首次注册 | `FEAT-005.manager.recovery-first-registration` | contract + blackbox | P0 | retry→verify→下一请求使用 |
| 未安装列表为空时请求透传 | `FEAT-005.manager.empty-pending` | contract + blackbox | P0 | Agent 正常响应、无 install |
| required 移交失败请求可见 | `FEAT-005.manager.required-install-failed` | contract + blackbox | P0 | `INSTALL_FAILED`、请求失败 |
| BaseAgent/DeepAgent/不支持类型 | `FEAT-005.installer.agent-types` | contract | P0 | 目标实例和 warn/skip |
| SkillUtil/SkillManager 未初始化 | `FEAT-005.installer.uninitialized-runtime` | contract | P1 | count=-1 诊断、不伪报成功 |
| 首次注册幂等 | `FEAT-005.manager.register-idempotent` | contract + blackbox | P0 | 多请求只注册一次 |
| 显式 reregister | `FEAT-005.manager.reregister` | contract | P1 | 已安装+未安装全部重新注册 |
| 并发首次请求不重复注册 | `FEAT-005.manager.concurrent-register` | contract | P1 | install 次数、列表一致性 |
| stop 终止后台重试 | `FEAT-005.manager.stop` | contract + blackbox | P0 | retry 停止、provider.stop |
| query 与 streamQuery 均执行 hook | `FEAT-005.handler.sync-stream-hooks` | contract | P0 | 两种入口调用顺序 |
| 不在请求中远端下载 | `FEAT-005.boundary.no-request-download` | contract + blackbox | P0 | Mock 请求计数稳定 |
| 首次注册后不自动热刷新 | `FEAT-005.boundary.no-hot-refresh` | contract + blackbox | P0 | 远端变更不下载/替换 |
| 配置变更仅重启生效 | `FEAT-005.boundary.config-change-on-restart` | blackbox | P1 | 两个 PID、endpoint/auth 审计 |
| 不直接注入 skill instructions | `FEAT-005.boundary.no-instruction-injection` | blackbox | P0 | 恢复前后语义差异、日志无内容 |
| agent-id 当前不支持 | `FEAT-005.boundary.agent-id-unsupported` | contract/deferred-l2 | P1 | 构造拒绝或 warn/skip |
| SkillHub 与 external registrar 相互独立 | `FEAT-005.integration.external-independence` | contract | P1 | registrar 调用顺序与故障隔离 |
| 默认 Provider 外部 JAR 全链成功 | `FEAT-005.blackbox.remote-skill-success` | blackbox | P0 | Mock+下载+校验+A2A+readFile |
| 真实 openJiuwen Skill Hub smoke | `FEAT-005.external.real-skillhub-smoke` | env-gated | P1 | 真实服务与脱敏日志 |

## 4. Fixture 与证据设计

### 4.1 hotel-skillhub SUT

新增独立 `hotel-skillhub` 坐标别名，指向：

```text
com.openjiuwen.example:travel-demo-hotel:0.1.0
```

必须使用独立 `skillhub-remote` profile：

- hotel POM 增加与 runtime 同版本的 `agent-service-adapters-agentcore-ext` 依赖；
- 仅在 `skillhub-remote` profile/配置启用时使用 `JiuwenCoreAgentExtHandler` 包装现有 `ReActAgent` 实例，默认 profile 保持当前 Handler 行为；
- 增加最小 Skill runtime support，配置 LOCAL sysop 及 readFile，但 hotel 业务代码不得调用 `registerSkill()`；
- 保持现有 system prompt，不写入远端 skill id、description、instructions 或结果模板；
- 不增加 bundled skill 或 classpath SKILL.md，所有远端 skill 证据只能来自 Mock/真实 Skill Hub；
- 每个测试通过 `@TempDir` 注入唯一 `openjiuwen.service.middleware.skillhub.local-dir`。

`application-openjiuwen.yml` 只增加同制品别名，不替换 FEAT-003 使用的 `hotel`：

```yaml
hotel-skillhub:
  group: com.openjiuwen.example
  artifact: travel-demo-hotel
  version: 0.1.0
  profile: skillhub-remote
```

### 4.2 Mock Skill Hub

`Feat005SkillHubHotelBlackboxTest` 使用 private nested fixture 基于 JDK `HttpServer` 在随机端口提供：

```text
GET /api/v1/plugins?plugin_type=skill
GET /api/v1/plugins/{id}/versions/{version}
GET /api/v1/artifacts/{id}?version={version}
GET /downloads/{artifact-file}
```

每个测试方法创建全新 Mock 实例，通过构造参数选择不可变场景或独立原子请求计数驱动的恢复场景：

| 参数/场景 | 行为 |
|---|---|
| `success` | 返回两个合法 skill 和正确摘要 |
| `no-digest` | 不返回摘要，提供常规可读包 |
| `fail-first-n` | 前 N 次下载失败，之后返回成功材料 |
| `checksum-mismatch-first-n` | 前 N 次返回错误摘要，之后正确 |
| `auth-401` / `auth-403` | 固定认证失败 |
| `not-found` | plugin/artifact 404 |
| `truncated` / `empty` / `missing-skill-md` | 返回对应损坏材料 |
| `install-invalid` | 下载校验通过，但材料使 registerSkill 后 count 不增长 |

Mock 审计只记录 method、规范化 path、请求序号、认证类型、响应场景和服务端线程；不得记录 token 值。需要失败后恢复的用例通过该实例自己的请求计数自动切换，禁止测试线程直接调用产品 Manager。Mock 在 `SutStack` 之前启动，其随机 base URL 通过 `AgentBuilder.property(...)` 注入 hotel。

### 4.3 证据优先级

1. Mock 请求审计：证明实际访问、认证方式、调用次数和请求链路外重试。
2. 本次进程增量日志：证明 category、degraded、installed/skipped、thread 和脱敏。
3. Agent card/A2A 状态：证明 ready、请求成功或请求期失败。
4. 唯一 skill 行为标志：证明远端 skill 真正注册并可用。
5. contract spy：只用于无法从外部观察的列表、线程、Bean back-off 和生命周期。

所有日志均为 append 文件：启动前记录 offset，只读取本次增量。脱敏断言失败时只输出命中类别和日志路径，不回显 canary。

## 5. 配置、安全与 Provider 子用例

框架落点：`Feat005SkillHubConfigurationAndDiagnosticsTest.java`。

### FEAT-005.config.disabled — disabled/no Provider

- **G**：`enabled=false`，不提供自定义 Provider。
- **W**：加载 auto-configuration；黑盒启动 hotel 并执行普通酒店查询。
- **T**：无 Manager；Provider 不启动；Agent ready 且业务正常；没有远端 Skill Hub 请求。
- **方法**：`feat005DisabledSkillHubDoesNotAffectAgent()`。

### FEAT-005.config.defaults-and-conditions — 默认值与条件装配

- **G**：仅加载 Properties/auto-configuration，不提供任何 Skill Hub 属性。
- **W**：分别运行正常 classpath、隐藏 `RunnerConfig` 的 `FilteredClassLoader`、enabled=false、enabled=true 四个上下文。
- **T**：Properties 默认 `enabled=false`、`authType=system-token`、`provider=openjiuwen`，endpoint/encryptedToken/localDir 为空；缺少 RunnerConfig 或未启用时不创建链路；enabled=true 且依赖存在时才进入默认装配。
- **方法**：`feat005AutoConfigurationHonorsDefaultsAndActivationConditions()`。

### FEAT-005.config.default-provider — 默认配置绑定

- **G**：enabled=true，配置 endpoint/authType/encryptedToken/localDir，未提供 Provider Bean。
- **W**：使用 `ApplicationContextRunner` 加载 SkillHub auto-configuration。
- **T**：Properties 完整绑定；创建唯一默认 `OpenJiuwenSkillHubProvider` 和 Manager；配置对象中不出现请求级 user/session/task。
- **方法**：`feat005EnabledConfigurationCreatesDefaultProviderAndManager()`。

### FEAT-005.config.custom-provider — 自定义 SPI 替换

- **G**：注册 spying `SkillHubProvider` Bean，保持同一 hotel 业务配置。
- **W**：加载上下文并完成一次下载、校验和请求注册；复用 Manager 降级/恢复合同；再加载无 custom Bean 的独立上下文。
- **T**：默认 Provider back-off；调用全部落到 spy；custom 仍遵守校验失败不注册、后台重试、请求期注册和脱敏语义；Agent 业务代码不改变；第二个上下文恢复默认 Provider。
- **方法**：`feat005CustomProviderBacksOffDefaultWithoutBusinessChanges()`。

### FEAT-005.config.stable-deployment — 稳定部署态入参

- **G**：同一 Agent 连续发送不同 contextId、user 文本和 metadata。
- **W**：完成启动下载及两个请求。
- **T**：Provider 只收到同一 Properties/Decryptor；请求内容、contextId、taskId 不进入 Provider；稳定后请求不触发 download。
- **方法**：`feat005ProviderUsesStableDeploymentConfiguration()`。

### FEAT-005.config.provider-selected-set — 第一期全部 Provider 选择结果

- **G**：Mock 返回两个有效 skill；Agent card 仅保留原有 hotel skill 描述，不增加远端选择配置。
- **W**：启动并触发首次注册。
- **T**：两个路径均 verify、均交给 Installer；不得按 Agent card skill id 本地过滤；日志 installed=2。
- **方法**：`feat005FirstPhaseInstallsAllProviderSelectedSkills()`。

### FEAT-005.security.system-token / FEAT-005.security.bearer — 认证头

参数化两种认证方式：

| authType | Mock 必须收到 | 不得收到 |
|---|---|---|
| `system-token` | `X-System-Token: <canary>` | `Authorization` |
| `bearer` | `Authorization: Bearer <canary>` | `X-System-Token` |

所有 acceptance/SUT/Mock 日志不得包含 canary。

- **方法**：`feat005DefaultProviderUsesConfiguredAuthenticationHeader(String authType)`。

### FEAT-005.security.credential-redaction — 解密与全链脱敏

- **G**：唯一 ciphertext canary 和不同 plaintext canary；spying decryptor 返回 plaintext。
- **W**：下载成功一次，再执行 401、403、断连和安装失败场景。
- **T**：decrypt 调用符合实现合同；认证成功场景 Mock 收到 plaintext；stdout/stderr/file log、异常 message、A2A 错误和 Mock 日志均不含任一 canary、完整认证头或 skill instructions。
- **方法**：`feat005CredentialsAreDecryptedForUseAndRedactedEverywhere()`。

### FEAT-005.security.endpoint-content-redaction — endpoint 与内容脱敏

- **G**：endpoint 包含敏感 path/query canary，skill 包含 content canary。
- **W**：触发下载失败、校验失败和注册失败。
- **T**：诊断保留 adapter/category/skillId/required/degraded/correlation；不输出完整 query、内部敏感路径或 content canary。
- **方法**：`feat005DiagnosticsRemainActionableWithoutSensitiveEndpointOrSkillContent()`。

### FEAT-005.diagnostics.fields — 稳定诊断字段

- **G**：成功、optional skip、required degraded、install failed 四种结果，均带唯一 correlation id。
- **W**：执行对应下载/注册流程并读取增量日志；若实现提供 Observation/metric，则同时读取测试 registry。
- **T**：Manager 汇总至少包含 tenantId、agentId、installed、skipped、degraded；Provider 失败诊断包含 skillId、required/optional、failureCategory、脱敏 reason 和 correlation；adapter/endpoint 只输出安全摘要；遥测标签不含凭据、内部地址和 skill 内容。
- **方法**：`feat005DiagnosticsExposeStableOperationalFieldsWithoutSecrets()`。

## 6. 默认 Provider 与完整性子用例

框架落点：`Feat005OpenJiuwenSkillHubProviderTest.java`。

### FEAT-005.provider.local-entry — LocalSkillEntry 合同

- **G**：两个不同 skillId 和规范化本地 Path。
- **W**：构造并读取两个 `LocalSkillEntry`，再将其 path 作为 Manager 扫描结果的对照数据。
- **T**：accessor 原样保留 skillId 与 localPath，测试不假定第一期 DTO 含 required/version 字段，也不虚构当前 SPI 未定义的 DTO 返回入口。
- **方法**：`feat005LocalSkillEntryPreservesSkillIdentityAndPath()`。

### FEAT-005.provider.protocol-download — API 映射与落盘

- **G**：Mock 返回 plugin 摘要、latest version、artifact metadata 和可下载包；localDir 为空目录。
- **W**：调用 `start()`、`download()`，再由 Manager 扫描并 verify。
- **T**：先请求 plugin 列表；需要完整定义时访问 `/plugins/{id}/versions/{version}`；随后请求 artifact metadata/下载 URL；版本按 L2 默认 latest；材料只写入 localDir；摘要响应阶段不记录或注入 instructions；download 成功返回 true。
- **方法**：`feat005OpenJiuwenProviderListsThenDownloadsLatestArtifacts()`。

### FEAT-005.provider.partial-download — 部分下载失败

- **G**：Mock 返回两个 skill，其中 A 下载成功、B 下载中断；Provider 合同规定部分失败时 download=false。
- **W**：执行首次 download 并等待后台 retry；第二次 A/B 均成功。
- **T**：首次结果为 false并进入降级重试；不得把一次“不完整批次”伪报为全部成功；B 未校验前绝不注册；重试完成后每个有效路径只进入未安装列表一次。
- **方法**：`feat005PartialDownloadRemainsDegradedUntilRetryCompletesBatch()`。

### FEAT-005.provider.error-categories — 错误分类

参数化场景及 L2 预期分类：

| 场景 | Category |
|---|---|
| endpoint 缺失/不可达 | `CONNECT_FAILED` |
| token 缺失、401、403 | `AUTH_FAILED` 或 L2 对 403 约定的安全分类 |
| 明确拒绝访问 | `ACCESS_DENIED` |
| plugin/artifact 404 | `NOT_FOUND` |
| 中断、空响应、非完整下载 | `DOWNLOAD_FAILED` |
| 服务不支持 artifact | `UNSUPPORTED` |
| 未分类异常 | `UNKNOWN` |

- **T**：异常为 `IllegalStateException`，message 前缀 `SkillHub[CATEGORY]`；message 已脱敏。
- **方法**：`feat005ProviderMapsRemoteFailuresToSanitizedCategories()`。

### FEAT-005.integrity.sha256-success — SHA-256 Provider 路径

- **G**：artifact metadata 携带匹配 SHA-256，包非空且含有效 SKILL.md。
- **W**：download + verify + register。
- **T**：verify=true，路径进入未安装列表并注册成功；诊断记录校验成功但不输出包内容。
- **方法**：`feat005Sha256VerifiedMaterialCanBeRegistered()`。

### FEAT-005.integrity.conventional-success — 无摘要常规校验

- **G**：metadata 无 digest；文件非空、大小一致、可完整读取且含必要文件。
- **W/T**：verify=true，材料可注册；日志能区分所采用的校验方式（具体算法名称不写死）。
- **方法**：`feat005ConventionallyVerifiedMaterialCanBeRegisteredWithoutDigest()`。

### FEAT-005.integrity.reject-invalid — 无效材料参数化

| 输入 | verify 预期 | 后续行为 |
|---|---|---|
| checksum mismatch | false/`CHECKSUM_MISMATCH` | 不移交，进入降级/重试 |
| 空文件 | false | 不移交 |
| 截断 zip/不可完整读取 | false | 不移交 |
| size 不一致 | false | 不移交 |
| 缺失 SKILL.md/必要文件 | false | 不移交 |
| verify 抛分类异常 | 异常被 Manager 分类处理 | 不移交 |

- **方法**：`feat005InvalidMaterialNeverReachesInstaller(InvalidMaterialCase input)`。

### FEAT-005.provider.lifecycle — Provider start/stop

- **G**：spying Provider 和可关闭 HTTP client。
- **W**：创建 Manager、执行请求、关闭 Handler/Manager；重复 close。
- **T**：start 恰好一次；stop 恰好一次；连接/线程资源释放；重复 close 不产生额外副作用。
- **方法**：`feat005ProviderLifecycleStartsAndStopsExactlyOnce()`。

## 7. Manager、Installer 与 Handler 合同子用例

框架落点：`Feat005SkillHubLifecycleAndRecoveryTest.java`。

### FEAT-005.manager.download-success — 下载、校验、待安装

- **G**：download=true；目录包含两个路径；verify 分别返回 true。
- **W**：构造 Manager 并执行 Handler.start。
- **T**：Provider.start 和首次有效 download 不重复；两个路径加入未安装列表；Installer 尚未调用；Runner.start 正常。
- **方法**：`feat005StartupDownloadVerifiesAndQueuesUninstalledSkills()`。

### FEAT-005.manager.required-degraded — required 启动失败降级

参数化 `CONNECT_FAILED`、`AUTH_FAILED`、`ACCESS_DENIED`、`NOT_FOUND`、`DOWNLOAD_FAILED`、`CHECKSUM_MISMATCH`、`UNSUPPORTED`、`UNKNOWN`：

- **W**：Handler.start。
- **T**：start 正常返回、Runner ready、未安装列表无失败路径、后台 retry 启动、日志包含 required/category/degraded 且脱敏。
- **方法**：`feat005RequiredStartupFailureDegradesAndStartsBackgroundRetry(Category category)`。

### FEAT-005.manager.optional-skip — optional 任一失败

- **G**：一条 optional 失败、一条有效 skill 成功。
- **W**：下载、校验并执行请求注册。
- **T**：Agent ready；失败项 warn+skip 且不注册；成功项照常注册；installed/skipped 计数准确。
- **方法**：`feat005OptionalFailureSkipsOnlyFailedSkill()`。

### FEAT-005.manager.retry-thread-boundary — 后台线程边界

- **G**：首次 download=false，第二次成功；spy 记录线程名和调用序列。
- **W**：等待未安装列表出现，不发送用户请求。
- **T**：后台只调用 download/verify/列表维护；Installer、registerSkill、Runner query 均为零；重试不携带用户请求对象。
- **方法**：`feat005BackgroundRetryNeverTouchesInstallerOrAgentSkillManager()`。

### FEAT-005.manager.recovery-first-registration — 恢复后首次生效

- **G**：首次失败并降级 ready；请求 R1 在恢复前执行；随后后台 retry 成功并 verify。
- **W**：发送新的请求 R2，再发送 R3。
- **T**：R1 无 skill；R2 请求开始时注册并可使用 skill；R3 不重复注册；远端下载不发生在 R1/R2/R3 请求线程。
- **方法**：`feat005RecoveredSkillBecomesAvailableOnNextNewRequest()`。

### FEAT-005.manager.empty-pending — 空列表请求透传

- **G**：download 尚未成功或所有 verify 均失败。
- **W**：分别调用 query 和 streamQuery。
- **T**：Manager.register 直接返回；父类请求正常执行；Installer 不调用。
- **方法**：`feat005RequestsProceedWhenNoVerifiedSkillIsPending()`。

### FEAT-005.manager.required-install-failed — required 移交失败

- **G**：verify 通过，registerSkill 后 skillCount 不增长。
- **W**：首个 query/streamQuery 触发安装。
- **T**：抛/返回 `INSTALL_FAILED` 的脱敏错误，请求线程感知；进程保持存活；路径不得移入已安装列表，后续行为保持可诊断。
- **方法**：`feat005RequiredInstallFailureIsVisibleToRequestThread()`。

### FEAT-005.installer.agent-types — Agent 类型

参数化：

- BaseAgent/ReActAgent：直接注册目标实例；
- DeepAgent：取 inner ReActAgent 注册；
- 普通 Object：warn + skip，不抛未分类异常。

- **方法**：`feat005InstallerTargetsSupportedAgentType(AgentType input)`。

### FEAT-005.boundary.agent-id-unsupported — agent-id 字符串边界

- **G**：向 ExtHandler 传入 agent-id String，ResourceMgr 中即使存在同名 Supplier 也不取临时实例安装。
- **W**：构造 Handler 或执行其明确的接线入口。
- **T**：按当前限制立即给出稳定、脱敏的 unsupported/agent instance 诊断；不得启动下载后再静默跳过，也不得对 Supplier 临时实例调用 registerSkill。
- **方法**：`feat005AgentIdStringIsRejectedBeforeSkillInstallation()`。

### FEAT-005.installer.uninitialized-runtime — Skill runtime 未初始化

- **G**：BaseAgent 的 SkillUtil 或 SkillManager 为 null，使安装前后 count 均为 -1。
- **W**：Installer 尝试安装 required/optional 路径。
- **T**：按 L2 安装结果表输出 warn，明确说明 skill runtime 未初始化，不得输出“注册成功”；本用例不把 -1/-1 擅自等同于 N/N 的 `INSTALL_FAILED`；不得产生空指针或 UNKNOWN 覆盖根因。
- **方法**：`feat005UninitializedSkillRuntimeIsDiagnosedWithoutFalseSuccess()`。

### FEAT-005.manager.register-idempotent — 普通请求幂等

- **G**：一个已 verify 路径。
- **W**：连续执行 query、streamQuery、query。
- **T**：首次成功后路径移至已安装列表；Installer/registerSkill 总计一次；skillCount 只增长一次。
- **方法**：`feat005VerifiedSkillIsRegisteredOnlyOnceAcrossRequests()`。

### FEAT-005.manager.reregister — 显式重新注册

- **G**：已安装 A，未安装 B。
- **W**：调用 `reregister(agent)`。
- **T**：A+B 均传给 Installer；已安装列表按合同重建；普通 query 不自动触发相同行为。
- **方法**：`feat005ExplicitReregisterReinstallsKnownSkills()`。

### FEAT-005.manager.concurrent-register — 并发首次请求

- **G**：一个 pending skill，两个请求同时到达。
- **W**：并发调用 query/streamQuery。
- **T**：不得发生重复注册、列表丢失或并发修改异常；两个请求均得到定义明确的结果。
- **方法**：`feat005ConcurrentFirstRequestsDoNotDuplicateRegistration()`。

### FEAT-005.manager.stop — 停止重试和资源释放

- **G**：Provider 持续 download=false，后台 retry 已开始。
- **W**：调用 Handler/Manager stop，记录调用计数后等待至少一个重试窗口。
- **T**：不再产生 download；Provider.stop 一次；线程终止；JVM 可正常退出。
- **方法**：`feat005StopCancelsRetryAndClosesProvider()`。

### FEAT-005.handler.sync-stream-hooks — 同步与流式入口

- **G**：分别创建全新 Manager/Agent，均有 pending skill。
- **W**：调用 query 或 streamQuery。
- **T**：两条入口都先 register 再委托父类；失败均在对应请求线程可见。
- **方法**：
  - `feat005SyncQueryRegistersBeforeAgentExecution()`；
  - `feat005StreamQueryRegistersBeforeAgentExecution()`。

### FEAT-005.integration.external-independence — external registrar 独立性

- **G**：fake SkillHubManager、MiddlewareAdapterRegistrar 和 ExternalSvcAdapterRegistrar；分别配置 Skill Hub 成功、降级和 install failure。
- **W**：调用 ExtHandler.start/query，记录 middleware、Skill Hub、external registrar 和 Runner 的调用顺序。
- **T**：启动阶段 middleware→SkillHub download→external registrar→Runner 的顺序可诊断；Skill Hub 启动降级不跳过 external registrar；external 自身行为不被 SkillHub 改写；请求期 install failure 不伪装为 external failure。
- **方法**：`feat005SkillHubFailureDoesNotChangeExternalRegistrarSemantics()`。

## 8. 外部 JAR 黑盒子用例

框架落点：`Feat005SkillHubHotelBlackboxTest.java`。

### FEAT-005.blackbox.remote-skill-success — 默认 Provider 全链成功

- **G**：类内 Mock success；hotel-skillhub 使用唯一 localDir、system-token canary 和 `skillhub-remote` profile；JAR 内没有 bundled skill。
- **W**：启动 Mock→hotel；读取 Agent card；发送明确要求使用唯一远端 skill 的流式请求。
- **T**：启动期 Mock 收到 list/artifact/download；hotel ready；首请求日志显示 verify 和 registered；终态 COMPLETED；回答命中远端 SKILL.md 独有业务标志；readFile(SKILL.md) 成功；日志无 canary。
- **方法**：`feat005RemoteSkillIsDownloadedVerifiedRegisteredAndUsed()`。

### FEAT-005.blackbox.degraded-before-recovery — 恢复前不可用

- **G**：Mock fail-first-n，重试窗口足以先完成一个用户请求。
- **W**：hotel ready 后立即发送 R1。
- **T**：R1 正常处理但不得出现远端 skill 独有标志；Mock/日志表明 skill 尚未注册；Agent card 仍可访问。
- **方法**：`feat005DegradedAgentServesRequestsWithoutUnavailableSkill()`。

### FEAT-005.blackbox.retry-recovery — 后台恢复和下一请求

- **G**：承接降级场景，Mock 后续返回有效包。
- **W**：等待 retry+verify 日志后发送 R2、R3。
- **T**：R2 首次出现远端 skill 行为；R3 仍可用但无重复 register；Mock 请求时间不落在 A2A 请求窗口内。
- **方法**：`feat005BackgroundRecoveryActivatesSkillOnFollowingRequest()`。

### FEAT-005.blackbox.checksum-recovery — 校验失败恢复

- **G**：Mock checksum-mismatch-first-n。
- **W**：启动、发送恢复前请求、等待正确包、发送恢复后请求。
- **T**：错误摘要材料未注册；Agent 降级 ready；正确包 verify 后才首次生效。
- **方法**：`feat005ChecksumFailureNeverRegistersUntilValidRetry()`。

### FEAT-005.blackbox.required-failure-categories — L2 降级矩阵

按独立进程参数化缺失 endpoint、401、403、404、拒绝访问和连接不可达：

- **T**：每种场景 Agent 都 ready；category/degraded 明确；skill 不可用；后台重试存在；日志脱敏。
- **方法**：`feat005RequiredRemoteFailureStillAllowsDegradedReadiness(FailureScenario input)`。

### FEAT-005.blackbox.optional-skip — optional 黑盒跳过

- **G**：Mock 同时提供成功 required 和失败 optional。
- **W**：启动并执行业务。
- **T**：Agent ready；required skill 可用；optional 不可用；日志 installed/skipped 与 required 标记准确。
- **方法**：`feat005OptionalFailureDoesNotHideSuccessfulSkills()`。

### FEAT-005.blackbox.install-failed — 请求期安装失败

- **G**：Mock 返回 install-invalid required skill。
- **W**：启动完成后发送首个请求，再探测 Agent card。
- **T**：启动 ready；首请求明确失败且分类 `INSTALL_FAILED`；进程仍存活；错误和日志脱敏。
- **方法**：`feat005RequiredInstallFailureFailsRequestButKeepsProcessObservable()`。

### FEAT-005.boundary.no-request-download — 请求链路无远端下载

- **G**：skill 已成功注册，记录 Mock 请求计数和日志 offset。
- **W**：连续执行三个不同 context 的请求。
- **T**：Mock 计数不增长；无 download/verify 日志；请求只走已安装 skill。
- **方法**：`feat005StableRequestsNeverFetchSkillsFromRemoteHub()`。

### FEAT-005.boundary.no-hot-refresh — 不自动热刷新

- **G**：v1 已注册；Mock 在不重启 hotel 的情况下对后续 list 返回 v2。
- **W**：等待多个重试窗口并发送新请求。
- **T**：未出现新远端请求、替换、卸载或第二次注册；仍使用 v1。重启/显式 reregister 不属于本用例。
- **方法**：`feat005RegisteredSkillIsNotAutomaticallyRefreshed()`。

### FEAT-005.boundary.config-change-on-restart — 配置重启生效

- **G**：阶段 A 使用 endpoint/token/localDir A 并成功注册 v1；不修改运行中 Agent。
- **W**：关闭阶段 A 的 SutStack；使用 endpoint/token/localDir B 创建全新的阶段 B SutStack 和新 PID，注册 v2。
- **T**：阶段 A 运行中不读取 B；重启后只访问 B 且使用新凭据类型/目录；不要求迁移、替换或卸载阶段 A 的内存状态。
- **方法**：`feat005DeploymentConfigurationChangesTakeEffectAfterRestart()`。

### FEAT-005.boundary.no-instruction-injection — 不由 runtime 注入 instructions

- **G**：远端 SKILL.md 含唯一 content canary；恢复前 skill 未注册。
- **W**：比较恢复前后同构请求，并扫描 prompt/日志可观察面。
- **T**：恢复前回答不含 skill 独有行为；恢复后通过 Agent 自身 skill 机制出现；runtime 日志、错误和 middleware prompt 诊断不含 instructions/content canary。
- **方法**：`feat005RuntimeDoesNotExposeOrDirectlyInjectSkillInstructions()`。

### FEAT-005.blackbox.lifecycle — 外部进程生命周期

- **G**：后台 retry 正在运行。
- **W**：`stack.stop(hotel-skillhub)`，保持 Mock 存活，再以相同端口启动 `hotel-skillhub`。
- **T**：停止后 Mock 不再收到旧进程请求；新 PID 启动新的 Provider 生命周期；无旧线程继续重试。
- **方法**：`feat005AgentRestartDoesNotLeakProviderOrRetryThreads()`。

### FEAT-005.external.real-skillhub-smoke — 真实服务 smoke

- **状态**：env-gated，P1，不进入本地合入门禁。
- **G**：现场提供真实 endpoint、凭据和隔离的已发布测试 skill；凭据只通过环境安全注入。
- **W**：以 `skillhub-remote` profile 启动 hotel，完成一次真实下载、校验和请求期注册。
- **T**：Agent ready，测试 skill 可用，默认 Provider 的认证和 API 映射与真实服务兼容，全部诊断脱敏；不验证 Skill Hub 的审批、发布、存储、审计或清理流程。
- **方法**：`feat005RealOpenJiuwenSkillHubSmoke()`。

## 9. 框架落点汇总

| Java 类 | blackbox | contract | 私有 fixture |
|---|---|---|---|
| `Feat005SkillHubConfigurationAndDiagnosticsTest` | disabled、custom-provider、安全 | auto-config、配置、decryptor | LogOffsets、canary、fake Provider |
| `Feat005OpenJiuwenSkillHubProviderTest` | — | HTTP 协议、认证、错误分类、校验 | 类内 JDK Mock HTTP Server、artifact fixtures |
| `Feat005SkillHubLifecycleAndRecoveryTest` | — | Manager、Installer、Handler、并发、stop | fake Provider/Installer、测试 Agent、可控 executor |
| `Feat005SkillHubHotelBlackboxTest` | 成功、降级、恢复、边界、生命周期 | — | 类内 JDK Mock Skill Hub、唯一 localDir、日志切片 |

落点目录：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/
```

Mock HTTP Server、fake Provider、fake Installer 和日志工具优先作为对应测试类的 private nested fixture。只有在至少两个 FEAT-005 类出现完全相同且稳定的实现后，才允许抽取通用 TestSupport。

## 10. L2 T1～T16 追溯

L2 原矩阵没有 T12，本档保持原编号，不补造 T12。

| L2 ID | 本档落点 |
|---|---|
| T1 config/auth/lookup required 降级 | `manager.required-degraded`、`blackbox.required-failure-categories` |
| T2 download required 降级重试 | `manager.required-degraded`、`blackbox.retry-recovery` |
| T3 checksum mismatch | `integrity.reject-invalid`、`blackbox.checksum-recovery` |
| T4 下载成功后请求期注册 | `manager.download-success`、`blackbox.remote-skill-success` |
| T5 SHA-256 verify success | `integrity.sha256-success` |
| T6 常规 verify success | `integrity.conventional-success` |
| T7 校验失败拒绝注册 | `integrity.reject-invalid` |
| T8 首次注册后不重复 | `manager.register-idempotent`、`boundary.no-hot-refresh` |
| T9 optional 任一失败 | `manager.optional-skip`、`blackbox.optional-skip` |
| T10 日志脱敏 | `security.credential-redaction`、`security.endpoint-content-redaction` |
| T11 无 Provider | `config.disabled` |
| T13 required INSTALL_FAILED | `manager.required-install-failed`、`blackbox.install-failed` |
| T14 DeepAgent | `installer.agent-types` |
| T15 未安装列表为空 | `manager.empty-pending`、`blackbox.degraded-before-recovery` |
| T16 后台重试成功后注册 | `manager.recovery-first-registration`、`blackbox.retry-recovery` |

## 11. version-scope 能力与边界追溯

| 能力/边界 | 本档落点 | L2 裁决说明 |
|---|---|---|
| 部署/启动阶段代理访问 | provider.protocol-download、manager.download-success | 启动下载，非请求下载 |
| runtime 配置持有凭据 | config.default-provider、security.* | Agent 侧不保存明文凭据 |
| Agent skill 选择配置 | config.provider-selected-set | 第一期由 Provider 决定集合 |
| 可替换 SPI | config.custom-provider | Bean back-off + 业务不变 |
| 默认 openJiuwen 实现 | provider.*、blackbox.remote-skill-success | Mock 协议 + 外部 JAR |
| skill 下载 | provider.protocol-download | list→artifact→localDir |
| 完整性校验 | integrity.* | 算法按 Provider 自决 |
| 注册材料移交 | manager.download-success、manager.required-install-failed | 请求期注册按 L2 |
| required 失败策略 | manager.required-degraded | 启动失败均降级；install 请求失败 |
| 下载失败降级与首次生效 | recovery-first-registration、blackbox.retry-recovery | 后台重试，下一请求注册 |
| optional 降级 | manager.optional-skip | warn + skip |
| 凭据保护 | security.* | 全链 canary |
| 错误诊断 | provider.error-categories、blackbox.required-failure-categories | category + sanitized message |
| 不请求级动态获取 | boundary.no-request-download | Mock 计数 |
| 不自动热刷新 | boundary.no-hot-refresh | v1 保持 |
| 配置通过重新部署/重启生效 | boundary.config-change-on-restart | 双阶段 PID 和 endpoint 审计 |
| 不自主获取新 skill | config.provider-selected-set、boundary.no-request-download | 只由 Provider 部署态集合 |
| 不维护独立授权模型 | provider.error-categories | 授权结果来自 Skill Hub |
| 不定义服务端治理 | external.real-skillhub-smoke | 不测试审批/发布/存储 |
| 不解释/注入 skill 内容 | boundary.no-instruction-injection | 下游 Agent 机制负责 |
| 不接管 external/tool 执行语义 | integration.external-independence | fake registrar 证明职责与故障隔离 |
| 不固定缓存/分页/断点续传 | 无内部实现断言 | 只验可观察合同 |
| 不代理其他中间件 | config.stable-deployment | 不扩展到 memory/knowledge |

## 12. 标签与运行方式

```java
@Feature("005")
@Tag("feat-005")
@Tag("integration")
class Feat005SkillHubHotelBlackboxTest {
    @Test
    @Tag("blackbox")
    @Story("下载失败恢复与首次生效")
    @DisplayName("Feat-005 后台恢复后远端 skill 从下一轮新请求开始可用")
    void feat005BackgroundRecoveryActivatesSkillOnFollowingRequest() { }
}
```

```bash
# 全部 FEAT-005
./mvnw -Dtest.env=openjiuwen -Dgroups=feat-005 test

# 指定测试类
./mvnw -Dtest.env=openjiuwen \
  -Dtest=Feat005SkillHubHotelBlackboxTest test

# contract / blackbox（以当前 Surefire groups 表达式为准）
./mvnw -Dtest.env=openjiuwen -Dgroups=contract test
./mvnw -Dtest.env=openjiuwen -Dgroups=blackbox test
```

## 13. 代码生成约束

1. L2 是行为断言权威；不得把 version-scope 的 fail-fast 或强制 SHA-256 重新写入当前测试预期。
2. contract artifacts 版本必须与 hotel JAR 内 ext/runtime/agent-core 版本一致；不一致直接失败。
3. 不直接使用 FEAT-003 的 `hotel` 别名作为 FEAT-005 进程；必须使用同一制品的 `hotel-skillhub` 别名隔离 profile、配置、日志和生命周期。
4. 每个黑盒测试先创建类内 Mock Skill Hub，再启动 `hotel-skillhub`；不得增加或依赖外部 `mock-skillhub` Maven 制品/Agent 别名。
5. Mock JSON 字段必须按开发完成后的 Provider DTO/HTTP 实现固化；不得仅凭 L2 示例猜测响应结构。
6. 重试测试必须使用可配置的短间隔或可控 executor；禁止真实等待默认长周期和 `Thread.sleep`。
7. 不通过反射读取 Manager 私有列表；contract 使用公开行为或测试可替换协作者取证。
8. 不把“Provider.download 被调用”当作“skill 可用”；必须同时证明 verify、registerSkill 和下游行为。
9. 日志是补充证据，不得作为唯一成功证据；失败诊断与脱敏例外，必须结合 Agent/Mock 状态。
10. 每个黑盒方法使用全新 Mock 实例、hotel 进程、localDir、skill id、token canary 和日志 offset。
11. Mock 不记录 token、完整认证头和 skill instructions；失败报告也不得回显 canary。
12. 黑盒凭据通过环境变量/凭据注入提供，不作为明文命令行参数或提交到 profile；Agent card 和 Agent 业务配置不得携带该凭据。
13. 只创建本档列出的四个 Java 测试类；fixture 优先类内私有实现，不提前增加通用框架抽象。

## 14. 退出标准

- 四个测试类均可通过 `feat-005` 标签发现，Allure 能区分 contract、blackbox 和 env-gated。
- L2 T1～T16（原文缺 T12）全部有明确测试方法和证据来源。
- version-scope 的全部能力项与显式边界均有当前期用例、负向边界或 L2 裁决说明。
- 默认 Provider、自定义 Provider、两种认证、全部错误分类、完整性成功/失败和日志脱敏有自动化证据。
- 启动降级、后台重试、请求期首次注册、幂等、显式 reregister、stop 和线程边界有 contract 证据。
- hotel 外部 JAR 能证明远端 skill 在恢复前不可用、恢复后可用，且证据不来自 bundled skill 或硬编码 prompt。
- required/optional、BaseAgent/DeepAgent/不支持 Agent 类型均有明确结果。
- 请求级下载、自动热刷新、runtime instructions 注入和其他中间件代理均有负向边界证据。
