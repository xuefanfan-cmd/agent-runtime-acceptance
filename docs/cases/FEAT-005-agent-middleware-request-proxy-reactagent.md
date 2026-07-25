---
feature_id: FEAT-005
feature_title: 启动态智能体中间件请求代理
sut: travel-demo-hotel ReactAgent（skillhub-remote profile）
scope: ReactAgent 黑盒；启动下载、认证、完整性、降级恢复、请求期首次注册、安全、诊断与边界
status: reviewed-twice
owner: TBD
priority: P0
tags: [integration, blackbox, openjiuwen, reactagent, feat-005]
depends_on:
  - travel-demo-hotel 可执行 jar 已按 application-openjiuwen.yml 坐标安装到 WSL Maven 本地仓库
  - agent-runtime-ext-java 与 agent-core-java 被测版本已打入上述外部 jar
  - WSL 可启动外部 Java 进程并访问测试进程内的 Mock Skill Hub
  - 需要验证 skill 最终业务效果的场景具备可用 LLM
related_docs:
  - FEAT-005-startup-agent-middleware-request-proxy.md
  - Feat-Func-005-agent-middleware-request-proxy.md
  - FEAT-003-agent-task-state-cache-reactagent.md
---

# FEAT-005 — 启动态智能体中间件请求代理测试用例设计

> **一句话**：由 acceptance 拉起 `travel-demo-hotel` 外部 ReactAgent jar 和类内 Mock Skill Hub，
> 从进程边界验证启动下载、认证、完整性校验、失败分层、后台恢复、请求期首次注册、稳定部署态、
> 日志脱敏、并发和生命周期。

> **仓库边界**：所有新增测试代码只写入 `agent-runtime-acceptance`；FEAT-005 产品仓库和
> `agent-core-java` 均为只读被测对象；测试不加载产品类，不调用或反射 Provider、Manager、Installer、
> Handler 及其私有状态，不为测试增加产品 HTTP/SPI 代理端点。

## 1. 状态定义

- **active**：当前 ReactAgent 外部 jar 有稳定黑盒入口，必须执行。
- **deferred-boundary**：L2 明确第一期不实现，只保留需求边界追溯，不生成空的 Java 测试方法。
- **component-test**：L2 内部实现合同，不是当前 ReactAgent 的公开黑盒能力；由 `agent-solution`
  组件测试覆盖，不在 acceptance 中生成空方法。
- **N/A-reactagent**：只适用于 DeepAgent 或其他 Agent 类型；不生成伪 ReactAgent 断言。
- **out-of-scope**：Skill Hub 服务端治理、agent-core 内部 skill 解析和其他中间件服务不属于
  FEAT-005 ReactAgent 客户端黑盒验收对象。

## 2. 覆盖矩阵

| 能力 | 子用例 ID | 状态 | 主要证据 |
|---|---|---|---|
| disabled/no Provider | `F005-BB-01` | active | Agent card + A2A + Mock HTTP 审计 |
| required endpoint 配置 | `F005-BB-02` | active | 外部进程启动失败 |
| bearer/system-token 认证 | `F005-BB-03` | active | Mock HTTP 认证头审计 + 增量日志 |
| 默认 Provider、两类完整性校验和首次注册 | `F005-BB-04` | active | HTTP 顺序 + 落盘材料 + A2A 业务标记 |
| required 认证/授权失败 | `F005-BB-05` | active | 401/403 + readiness + 脱敏诊断 |
| required skill 查找失败 | `F005-BB-06` | active | artifact 404 + readiness + 无下载 |
| 下载失败降级与请求链路外重试 | `F005-BB-07` | active | ready + Mock 增量请求 |
| 无效材料拒绝注册 | `F005-BB-08` | active | checksum/ZIP/SKILL.md 参数化 + 文件/日志 |
| 后台恢复后下一请求首次生效 | `F005-BB-09` | active | 无用户请求恢复 + A2A 业务标记 |
| 不重复下载/注册且不热刷新 | `F005-BB-10` | active | Mock 计数 + 注册日志 + v1/v2 响应 |
| 配置变更重启生效 | `F005-BB-11` | active | PID + 双 endpoint/token/localDir + 业务标记 |
| 凭据、endpoint path 和 skill 内容脱敏 | `F005-BB-12` | active | stdout/file log 内容探针 |
| query/streamQuery hook | `F005-BB-13` | active | 同步/流式 A2A 参数化 |
| 并发首次注册幂等 | `F005-BB-14` | active | 4 并发请求 + HTTP/注册日志计数 |
| Provider/Manager 生命周期 | `F005-BB-15` | active | 关闭进程后的 Mock 计数稳定 |
| 自定义 Provider 替换 | `F005-BB-16` | active | profile Bean + 生命周期日志 + A2A 业务标记 |
| required 移交失败 | `F005-BB-17` | active | 合法材料 + profile 故障注入 + 首次失败/后续恢复 |
| DeepAgent 安装 | L2 T14 | N/A-reactagent | 用户指定只测 ReactAgent |

## 3. 前置条件与共享约定

### 3.1 SUT 与配置

- 使用 `-Dtest.env=openjiuwen`，复用 `application-openjiuwen.yml`。
- 只启动与 FEAT-003 相同坐标的 `com.openjiuwen.example:travel-demo-hotel:0.1.0`。
- 使用隔离别名 `hotel-skillhub` 和 `skillhub-remote` profile，不改变 FEAT-003 的 `hotel` 配置。
- 每个 active 方法使用新的外部 ReactAgent 进程、Mock Skill Hub、临时目录和 token canary。
- 配置通过 `AgentBuilder.property/env/profile` 注入；不把 token 作为 Java 命令行明文参数。

```yaml
hotel:
  group: com.openjiuwen.example
  artifact: travel-demo-hotel
  version: 0.1.0

hotel-skillhub:
  group: com.openjiuwen.example
  artifact: travel-demo-hotel
  version: 0.1.0
  profile: skillhub-remote
```

### 3.2 黑盒入口与证据

- Agent ready：公开 Agent card。
- 业务入口：同步 A2A 与流式 A2A。
- Skill Hub 行为：Mock HTTP method/path/query/header 类型和请求计数。
- 材料状态：测试指定 localDir 下的下载和解压产物。
- 诊断与生命周期：当前外部进程的增量 stdout/file log、PID 和关闭后的 Mock 请求计数。

黑盒证据优先级为：业务响应/ready 状态 > Mock HTTP 审计与落盘材料 > 当前进程日志。
日志可以证明诊断、生命周期和脱敏，但不能单独证明 skill 已可用。

### 3.3 Mock Skill Hub

唯一测试类内使用 JDK `HttpServer` 提供公开协议：

```text
GET /api/v1/plugins?plugin_type=skill&page=1&page_size=200
GET /api/v1/artifacts/{assetId}?version={version}
GET /downloads/{assetId}.zip
```

Mock 支持：成功、无摘要、401、403、404、列表 5xx、下载 5xx、空包、损坏 ZIP、缺失
`SKILL.md`、错误 SHA-256、前 N 次失败后恢复以及成功后远端内容切换。审计只保存 method、path、
query、认证类型、请求序号和响应场景，不保存 token 或认证头值。

### 3.4 数据、日志与 LLM

- 日志是 append 文件：启动前记录 offset，只读取本次进程增量。
- 每个方法使用唯一 localDir、marker 和内容探针，防止顺序依赖。
- 脱敏失败只报告命中类别，不在断言消息或问题报告回显敏感值。
- 只有验证最终业务效果、同步/流式 hook 和并发请求的场景依赖 LLM。
- LLM 参数仅由 WSL 执行环境注入，不写入源码、YAML 或测试报告。
- 外部进程一律通过 `SutStack`/`RunningAgent` 关闭；后台重试场景结束后验证 Mock 计数稳定。

## 4. 配置、认证与默认 Provider 子用例

框架落点：`SkillHubReactAgentBlackboxTest.java`。

### F005-BB-01 — disabled/no Provider

- **状态**：active，P0。
- **追溯**：version-scope 可选装配；L2 T11。
- **G**：`hotel-skillhub` profile，设置 `enabled=false`，Mock 可正常响应。
- **W**：启动 ReactAgent，读取 Agent card并发送普通同步 A2A 请求。
- **T**：Agent ready、请求完成；启动和业务请求期间均没有 Skill Hub HTTP。
- **方法**：`disabledSkillHubKeepsReactAgentReady()`。

### F005-BB-02 — required endpoint 配置缺失

- **状态**：active，P0。
- **追溯**：required 配置 fail-fast；L2 T1 config。
- **G**：`enabled=true`，endpoint 为空，其他必要配置有效。
- **W**：通过 acceptance 启动外部 ReactAgent。
- **T**：启动失败或 readiness 不通过；诊断包含 endpoint 缺失语义，不输出凭据参数。
- **方法**：`missingEndpointFailsFast()`。

### F005-BB-03 — bearer 与 system-token 认证

- **状态**：active，P0，参数化两组。
- **追溯**：默认 bearer、system-token、配置归 runtime、L2 T10。
- **G**：分别省略 `auth-type` 和显式设置 `system-token`；每组使用唯一 token。
- **W**：启动 Agent，由 Mock 审计列表和 artifact 请求的认证头。
- **T**：bearer 只出现 Authorization Bearer 与 OAuth provider；system-token 只出现
  `X-System-Token`；头值匹配但日志不含 token。
- **方法**：`configuredAuthenticationHeaderIsUsed(String authCase)`。

### F005-BB-04 — 默认 Provider、完整性和首次注册

- **状态**：active，P0。
- **追溯**：默认 openJiuwen adapter、全部 Provider、下载、SHA-256/常规校验、移交；L2 T4/T5/T6。
- **G**：Mock 返回两个 skill，一个带 SHA-256，一个无摘要但材料完整。
- **W**：启动 Agent，核对 list→artifact→download，随后发送使用远程 skill 的新请求。
- **T**：两个 skill 均落盘且通过对应校验；第一个新请求前完成注册；业务响应包含预期 marker；
  注册汇总为 2。
- **方法**：`defaultProviderDownloadsVerifiesAndRegistersAllSkills()`。

### F005-BB-05 — required 认证/授权失败

- **状态**：active，P0，参数化 401/403。
- **追溯**：required 认证/授权 fail-fast；错误分类与脱敏。
- **G**：Mock 在已收到正确 bearer 头后分别返回 401、403。
- **W**：启动外部 ReactAgent并观察 readiness、异常和当前进程日志。
- **T**：Agent 不得 ready；诊断保留 `AUTH_FAILED` 或等价认证/授权语义；全链不含 token。
- **方法**：`requiredAuthenticationFailureBlocksReadiness(int status)`。
- **裁决**：version-scope、L2 §1.2 和 §2.1 规定 fail-fast；L2 T1/§4.9 的降级描述与其冲突，
  本用例采用规范正文，若实现降级 ready 则记录产品/设计一致性缺陷。

### F005-BB-06 — required skill 查找失败

- **状态**：active，P0。
- **追溯**：required 查找 fail-fast；L2 T1 lookup。
- **G**：列表返回 required skill，artifact 查询返回 404。
- **W**：启动外部 ReactAgent并观察 readiness、诊断和下载计数。
- **T**：Agent 不得 ready；诊断保留 `NOT_FOUND`；不请求下载 URL。
- **方法**：`requiredSkillNotFoundBlocksReadiness()`。
- **裁决**：同 BB-05，按 version-scope 和 L2 规范正文采用 fail-fast。

## 5. 下载、完整性与恢复子用例

框架落点：`SkillHubReactAgentBlackboxTest.java`。

### F005-BB-07 — 下载失败降级与请求链路外重试

- **状态**：active，P0。
- **追溯**：下载失败降级；L2 T2/T15。
- **G**：列表和 artifact 成功，下载返回 5xx 或发生连接中断。
- **W**：启动 Agent，不发送用户请求，等待 Mock 出现新的下载周期，再发送普通请求。
- **T**：Agent 降级 ready且普通请求可用；skill 未注册；后台新增 HTTP 发生在无用户请求期间。
- **方法**：`downloadFailureDegradesAndRetriesOutsideRequestPath()`。

### F005-BB-08 — 无效材料拒绝注册

- **状态**：active，P0，参数化四组。
- **追溯**：完整性拒绝和常规检查；L2 T3/T7。
- **G**：分别提供错误 checksum、损坏 ZIP、空 ZIP、缺失 `SKILL.md`。
- **W**：启动 Agent，等待后台重试，检查当前日志和 localDir。
- **T**：Agent 可降级 ready；诊断分类明确；无成功注册日志；不存在可用 `SKILL.md` 产物。
- **方法**：`invalidMaterialIsRejectedBeforeRegistration(FailureMode mode)`。

### F005-BB-09 — 后台恢复与下一请求首次生效

- **状态**：active，P0。
- **追溯**：降级恢复和下一轮请求生效；L2 T16。
- **G**：首次下载失败，后续后台下载返回有效 skill。
- **W**：不发送用户请求直到日志确认后台恢复，再发送一轮新的 skill 请求。
- **T**：恢复发生在请求链路外；下载次数至少 2；恢复后的下一轮新请求注册并使用 skill。
- **方法**：`backgroundRecoveryActivatesSkillOnFollowingRequest()`。

## 6. 稳定部署、安全、请求 hook、并发与生命周期子用例

框架落点：`SkillHubReactAgentBlackboxTest.java`。

### F005-BB-10 — 不重复下载/注册且不热刷新

- **状态**：active，P0。
- **追溯**：稳定部署态、非请求下载、首次有效注册幂等、不热刷新；L2 T8。
- **G**：启动下载 v1 并首次注册成功。
- **W**：连续发送请求，随后把 Mock 内容切换为 v2，再次请求 v1 marker。
- **T**：请求期无新增 Skill Hub HTTP；有效注册一次；响应继续使用 v1，不出现 v2。
- **方法**：`requestsDoNotDownloadAgainOrHotRefreshRegisteredSkill()`。

### F005-BB-11 — 配置变更重启生效

- **状态**：active，P0。
- **追溯**：endpoint/token/localDir 为稳定部署配置；配置重启生效；生命周期。
- **G**：准备 v1/v2 两个 Mock、两个 token 和两个 localDir。
- **W**：启动 v1、完成请求并关闭；用 v2 配置重新启动同一坐标。
- **T**：PID 变化；新进程只访问新 endpoint并使用新目录；旧 Mock 计数不再增长；v2 业务标记生效。
- **方法**：`deploymentConfigurationChangesTakeEffectAfterRestart()`。

### F005-BB-12 — 凭据、endpoint path 与 skill 内容脱敏

- **状态**：active，P0。
- **追溯**：凭据与敏感信息保护、启动诊断；L2 T10。
- **G**：token、endpoint path 和 `SKILL.md` 正文分别放置唯一内容探针。
- **W**：启动、注册、读取并实际使用远程 skill，读取当前进程完整增量日志。
- **T**：业务响应成功；stdout/file log 不含三类探针；日志只允许 `credential=provided` 等摘要。
- **方法**：`diagnosticsRedactCredentialsEndpointPathAndSkillContent()`。

### F005-BB-13 — 同步与流式请求 hook

- **状态**：active，P0，参数化同步/流式。
- **追溯**：`query()`/`streamQuery()` 请求期首次注册 hook。
- **G**：每种协议分别启动一个已下载有效 skill 的 ReactAgent。
- **W**：发送对应协议的第一个业务请求。
- **T**：两种入口均在业务处理前完成首次 skill 注册，并返回合法终态及对应 marker。
- **方法**：`syncAndStreamingRequestsApplySkillHook(MessageProtocol protocol)`。

### F005-BB-14 — 并发首次请求幂等

- **状态**：active，P0。
- **追溯**：并发首次注册；L2 T17 可从外部观察部分。
- **G**：启动阶段已下载并校验一个 skill，尚未发送业务请求。
- **W**：同时发送 4 个同步 A2A 首次请求。
- **T**：四个请求均正常完成；请求阶段无重复下载；同一 ReactAgent 只出现一次有效注册；
  无 `ConcurrentModificationException`。
- **方法**：`concurrentFirstRequestsDoNotDuplicateDownloadOrRegistration()`。

### F005-BB-15 — Provider/Manager 生命周期

- **状态**：active，P1。
- **追溯**：Provider/Manager `stop` 生命周期。
- **G**：下载持续失败并已启动后台重试。
- **W**：确认至少一次后台重试后关闭外部 ReactAgent，持续观察 Mock 请求计数。
- **T**：进程退出后计数在观察窗口保持稳定；日志不泄露资源敏感信息。
- **方法**：`closingAgentStopsBackgroundRetries()`。

### F005-BB-16 — 自定义 Provider 替换

- **状态**：active，P0。
- **追溯**：version-scope 可替换 Skill Hub SPI；L2 自定义 `@Bean SkillHubProvider` 覆盖默认实现。
- **G**：使用相同 hotel Maven 坐标，同时启用 `skillhub-remote,skillhub-custom-provider`；Mock 返回
  checksum、ZIP 和 `SKILL.md` 均有效的 skill。
- **W**：启动外部 ReactAgent，核对 Mock 协议顺序，发送 marker 请求后关闭进程。
- **T**：日志包含 hotel custom Provider 的 start/download/verify/stop 生命周期标记；业务响应包含
  marker；日志不包含 token。自定义 Provider 仍通过真实 SPI、Manager 和 Installer 完成链路。
- **方法**：`customProviderReplacementNeedsReactAgentFixture()`。

### F005-BB-17 — required 移交失败只报告一次

- **状态**：active，P0。
- **追溯**：version-scope required 移交失败；L2 T13、T21。
- **G**：使用相同 hotel Maven 坐标，同时启用 `skillhub-remote,skillhub-install-failure`；Mock 返回
  完全合法并能通过默认 Provider 校验的单一 skill；example 中的 ReactAgent profile 在
  `registerSkill()` 抛出确定性异常。
- **W**：第一次同步 A2A 请求触发请求期移交；确认失败后发送第二次普通同步请求。
- **T**：第一次请求失败或进入 FAILED 终态，日志包含 `SkillHub[INSTALL_FAILED]`；同一 Agent 的路径已
  标记 processed，第二次请求正常完成且不重复输出该移交错误；两个请求都不重新访问 Skill Hub。
- **方法**：`requiredInstallFailureNeedsExternalFixture()`。

### 非黑盒边界

以下边界不生成 Java 空方法：

- Agent skill 选择、optional/required 选择和 agent-id 安装为 `deferred-boundary`；L2 已明确第一期
  不实现或不支持，待能力进入当前版本后重新设计可执行用例。
- reregister、多 Agent 共享 Manager 和后台重试重新启动为 `component-test`；它们分别对应 L2
  T18、T19、T20，但当前 ReactAgent 没有公开黑盒入口，应在 `agent-solution` 验证内部合同。
- DeepAgent 安装（T14）标记为 `N/A-reactagent`，不生成会加载 DeepAgent 的测试方法。

## 7. 框架落点汇总

| Java 类 | active 子用例 | disabled 子用例 | 类内私有 fixture |
|---|---|---|---|
| `SkillHubReactAgentBlackboxTest` | BB-01～BB-17 | 无 | Mock Skill Hub、ZIP/SKILL.md 构造、日志切片、进程启动和 HTTP 审计 |

落点目录：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/
```

不新增 FEAT-005 TestSupport 或独立测试数据文件。可合并的认证、无效材料、同步/流式入口均使用
参数化方法；所有场景保持在唯一 Java 文件，减少重复启动辅助代码和外部 jar fixture。

## 8. 需求与 L2 追溯

### 8.1 version-scope 追溯

| 能力/边界 | 用例 | 状态 |
|---|---|---|
| 部署/启动阶段访问 | BB-04、BB-07、BB-10 | active |
| runtime 持有 endpoint/认证/凭据 | BB-03、BB-11、BB-12 | active |
| Agent skill 选择及 required/optional | 当前版本不生成 Java 用例 | deferred-boundary |
| 可替换 SPI | BB-16 | active |
| 默认 openJiuwen adapter | BB-03～BB-10 | active |
| 下载、摘要与常规完整性 | BB-04、BB-08、BB-09 | active |
| 校验后移交和首次生效 | BB-04、BB-09、BB-17 | active |
| required 配置/认证/授权/查找 fail-fast | BB-02、BB-05、BB-06 | active |
| required 移交失败传播 | BB-17 | active |
| 下载/校验失败降级重试 | BB-07～BB-09 | active |
| 凭据和内容保护、错误诊断 | BB-02、BB-05～BB-08、BB-12 | active |
| 不在请求中下载、不热刷新 | BB-10 | active |
| 不自主获取、不独立授权 | BB-03、BB-05、BB-10 | active |
| 不直接注入 instructions | BB-04 业务效果与日志内容扫描 | active |
| 服务端治理、agent-core 语义、其他中间件 | 不作内部断言 | out-of-scope |

### 8.2 L2 T1～T21 追溯

L2 原编号没有 T12，保持原样：

| L2 | 用例/裁决 |
|---|---|
| T1 config/auth/lookup | BB-02/05/06；采用 version-scope 与 L2 §1.2/§2.1 的 fail-fast 正文 |
| T2 download failure | BB-07、BB-09 |
| T3 checksum mismatch | BB-08、BB-09 |
| T4 download then register | BB-04 |
| T5 SHA-256 | BB-04 |
| T6 conventional verify | BB-04 |
| T7 reject invalid | BB-08 |
| T8 no duplicate registration | BB-10 |
| T9 optional | L2 第一期没有 required 字段；deferred-boundary，不生成 Java 空方法 |
| T10 redaction | BB-03、BB-12 |
| T11 no Provider/disabled | BB-01 |
| T13 INSTALL_FAILED | BB-17；`registerSkill()` 抛异常后由默认 Installer 分类并传播 |
| T14 DeepAgent | N/A-reactagent |
| T15 empty verified set | BB-07/08 |
| T16 retry then register | BB-09 |
| T17 concurrent register/retry | BB-14，验证外部可观察部分 |
| T18 reregister | component-test；无 ReactAgent 公开管理入口，不生成 acceptance 空方法 |
| T19 multi-agent Manager | component-test；当前坐标每 JVM 一个 Agent，不生成 acceptance 空方法 |
| T20 retry restart | component-test；属于 Manager 内部重试标志合同，不生成 acceptance 空方法 |
| T21 INSTALL_FAILED no repeat | BB-17；第二次请求不重复移交或抛同一错误 |

## 9. 标签与报告

```java
@Feature("FEAT-005: Agent middleware request proxy")
@Tag("feat-005")
@Tag("integration")
@Tag("blackbox")
class SkillHubReactAgentBlackboxTest {
    @Test
    @Story("F005-BB-01 disabled/no Provider")
    @DisplayName("F005-BB-01: disabled Skill Hub keeps the ReactAgent ready and makes no remote request")
    void disabledSkillHubKeepsReactAgentReady() { }

    @Test
    @Story("F005-BB-16 custom Provider replacement")
    void customProviderReplacementNeedsReactAgentFixture() { }

    @Test
    @Story("F005-BB-17 required handover failure")
    void requiredInstallFailureNeedsExternalFixture() { }
}
```

Allure 报告必须能通过 `feat-005` 发现全部 17 个 active 方法。
黑盒 failure/error 经复跑和夹具校准后，若确认是产品偏差，另行按 FEAT003 问题模板生成问题文档；
本文件只维护预置场景、预期、约束和追溯关系。

## 10. 运行方式

```bash
cd /mnt/d/code-agent/agent-runtime-acceptance

# 全部 FEAT-005
./mvnw -Dtest.env=openjiuwen -Dgroups=feat-005 test

# 唯一测试类
./mvnw -Dtest.env=openjiuwen \
  -Dtest=SkillHubReactAgentBlackboxTest test
```

需要 LLM 的场景在执行前通过当前 WSL shell 注入 `LLM_API_KEY`、`LLM_API_BASE`、
`LLM_MODEL`、`LLM_PROVIDER` 和 `LLM_SSL_VERIFY`；文档和命令示例不保存真实凭据。

## 11. 风险、代码生成约束与两轮审视

### 11.1 风险与代码生成约束

1. acceptance 使用的 `travel-demo-hotel` jar 必须包含待测 FEAT-005 实现；坐标相同但 jar 未重打包会造成假结果。
2. version-scope/L2 规范正文与 L2 T1/§4.9 对 required auth/lookup 的语义冲突；测试固定采用 fail-fast。
3. LLM 输出字段在同步/流式协议间可能不同，公共业务断言必须按合法终态提取，不以字段差异误报产品问题。
4. 日志为 append 文件，必须使用 offset slice；并发和生命周期只读取当前外部进程增量。
5. 并发用例存在调度差异，断言外部幂等语义，不以一次未复现证明线程安全。
6. 不通过加载产品 artifact、反射私有状态或 fake Manager/Installer 来补足黑盒不可观察项。
7. 后续代码修改只能保持一个 FEAT-005 Java 文件和 17 active/0 disabled 设计，不拆分 TestSupport。
8. 测试中不得写入真实 LLM key、Skill Hub token、认证头值或敏感 `SKILL.md` 正文。

### 11.2 第一轮：需求覆盖审视

- 对 version-scope §2、§4、§5、§5.1、§5.2 和 L2 §2、§4、§5、§6、§8 逐项追溯。
- 补入旧方案遗漏的 L2 T17～T21。
- 修正旧方案把 required 认证/查找失败都预期为降级的问题。
- 将 DeepAgent 从 ReactAgent 执行范围移除，但保留 N/A 追溯。
- 将第一期未实现项记录为 `deferred-boundary`，不生成没有执行价值的 `@Disabled` 空方法。

结论：两个设计文档中的 ReactAgent 能力和显式边界均有 active、deferred-boundary、component-test、
N/A 或 out-of-scope 裁决。

### 11.3 第二轮：黑盒性与可执行性审视

- 删除所有 `ApplicationContextRunner`、fake Provider/Installer、内部列表、反射和产品类依赖。
- 将原规划的多个 Java 类收敛为一个类，合并重复启动和 fixture。
- 每个 active 用例只依赖外部 jar、公开协议、Mock 服务、文件系统产物和日志。
- 将 reregister、多 Agent Manager 和重试重启裁决为 solution 组件测试；通过 hotel example profile
  为自定义 Provider 和 required 移交异常提供稳定外部黑盒夹具。
- 检查 token 注入、Mock 审计和失败消息，确保敏感值不进入命令行或测试输出。

结论：方案满足“只测 ReactAgent、纯黑盒、测试文件尽量少、复用 FEAT-003 坐标”的要求。

## 12. 退出标准

- 唯一 Java 类可由 `feat-005` 标签发现，包含 17 个 active 场景方法且没有 `@Disabled` 方法。
- version-scope 的 ReactAgent 能力和 L2 T1～T21 均有 active、deferred-boundary、component-test、
  N/A 或 out-of-scope 追溯。
- active 用例只使用外部 ReactAgent jar 和公开黑盒证据，不加载或反射 FEAT-005 产品类。
- bearer/system-token、默认 Provider、下载、两类完整性校验、失败分层、恢复、首次生效、稳定部署态、
  同步/流式 hook、并发、脱敏和生命周期均有明确 G/W/T。
- 所有外部进程可关闭；关闭后后台重试不再产生 Mock 请求。
- 测试及 SUT 日志不得包含 LLM key、Skill Hub token、认证头值、敏感 endpoint path 或敏感 skill 内容。
- 测试自身问题修正后重跑；确认的产品偏差另行生成问题报告，不回写测试设计源文档。
