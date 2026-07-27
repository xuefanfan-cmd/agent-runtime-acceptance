---
feature_id: FEAT-005
feature_title: 启动态智能体中间件请求代理 — DeepAgent 视角
sut: agent-deep-research DeepAgent（openjiuwen.service.middleware.skillhub.* 由测试注入）
scope: DeepAgent 黑盒；启动下载、认证、完整性、降级恢复、请求期首次注册、安全、诊断与边界
status: active
owner: TBD
priority: P0
tags: [integration, blackbox, openjiuwen, deepagent, feat-005]
depends_on:
  - agent-deep-research 可执行 jar 已按 application-local.yml 坐标安装到本地 Maven 仓库
  - agent-service-adapters-agentcore-ext（含 SkillHubManager/Installer/Provider/AutoConfig）随 jar 打入
  - 本地可启动外部 Java 进程并访问测试进程内的 Mock Skill Hub
  - 需要验证 skill 最终业务效果的场景具备可用 LLM（deepseek-chat 默认）
related_docs:
  - FEAT-005-agent-middleware-request-proxy-reactagent.md
  - version-scope FEAT-005-startup-agent-middleware-request-proxy.md
  - L2 Feat-Func-005-agent-middleware-request-proxy.md
---

# FEAT-005 — 启动态智能体中间件请求代理测试用例设计（DeepAgent）

> **一句话**：与 ReactAgent 版本 1:1 平行的黑盒验收，SUT 换为 `agent-deep-research` DeepAgent。
> `SkillHubInstaller.resolveBaseAgent` 走 `instanceof DeepAgent → deepAgent.getAgent()` 拿 inner
> ReActAgent 注册（L2 T14 核心事实），本文档验证该分支在完整生命周期下的黑盒行为一致性。

> **与 ReactAgent 版本的关系**：ReactAgent 版本已覆盖 SkillHubManager 通用协议（Mock HTTP、
> 完整性、降级、脱敏等）。DeepAgent 版本重点是**验证 T14 DeepAgent 分支自身可跑通**且不出现
> ReactAgent 版本发现不了的偏差（例如 `processedForAgent` 幂等 key 用错、DeepAgent 结构导致
> skill 无法到达 inner ReActAgent 的 prompt）。因此保持 17 个平行 case，但每个 case 的核心断言
> 与 ReactAgent 相同，仅在必要处增补 DeepAgent-specific 附加断言（用 **DA附加** 标识）。

> **仓库边界**：所有新增测试代码只写入 `agent-runtime-acceptance`；FEAT-005 产品仓库、
> `agent-core-java` 和 `agent-deep-research` example jar 均为只读被测对象；测试不加载产品类，
> 不反射 Provider/Manager/Installer/Handler 私有状态，不为测试新增产品 HTTP 端点。

## 1. 状态定义

沿用 ReactAgent 版本的四态：`active` / `deferred-boundary` / `component-test` / `N/A-reactagent` /
`out-of-scope`。DeepAgent 版本另引入：

- **deferred-fixture**：L2 分支存在且可测，但依赖 `agent-deep-research` example jar 尚未提供的
  测试 profile（如 `skillhub-custom-provider`、`skillhub-install-failure`）。当前不生成 Java 方法，
  case 保留需求追溯；补齐 example jar 侧 fixture 后即可转 active。

## 2. 覆盖矩阵

| 能力 | 子用例 ID | 状态 | 主要证据 | DA附加断言 |
|---|---|---|---|---|
| disabled/no Provider | `F005-DA-01` | active | Agent card + A2A + Mock HTTP 审计 | — |
| required endpoint 配置 | `F005-DA-02` | active | 外部进程启动失败 | — |
| bearer/system-token 认证 | `F005-DA-03` | active | Mock HTTP 认证头审计 + 增量日志 | — |
| 默认 Provider、两类完整性校验和首次注册 | `F005-DA-04` | active | HTTP 顺序 + 落盘材料 + A2A 业务标记 | marker 由 planner 直接产出（不走 sub-agent） |
| required 认证/授权失败 | `F005-DA-05` | active | 401/403 + readiness + 脱敏诊断 | — |
| required skill 查找失败 | `F005-DA-06` | active | artifact 404 + readiness + 无下载 | — |
| 下载失败降级与请求链路外重试 | `F005-DA-07` | active | ready + Mock 增量请求 | DeepAgent 降级 ready 后 planner 仍能响应普通请求 |
| 无效材料拒绝注册 | `F005-DA-08` | active | checksum/ZIP/SKILL.md 参数化 + 文件/日志 | — |
| 后台恢复后下一请求首次生效 | `F005-DA-09` | active | 无用户请求恢复 + A2A 业务标记 | 恢复后首个新请求 marker 生效于 DeepAgent planner 输出 |
| 不重复下载/注册且不热刷新 | `F005-DA-10` | active | Mock 计数 + 注册日志 + v1/v2 响应 | 二轮请求继续拿到 v1，DeepAgent 不重建触发重刷 |
| 配置变更重启生效 | `F005-DA-11` | active | PID + 双 endpoint/token/localDir + 业务标记 | — |
| 凭据、endpoint path 和 skill 内容脱敏 | `F005-DA-12` | active | stdout/file log 内容探针 | — |
| query/streamQuery hook | `F005-DA-13` | active | 同步/流式 A2A 参数化 | 同步/流式两种入口均在 planner 首次调用前完成注册 |
| 并发首次注册幂等 | `F005-DA-14` | active | 4 并发请求 + HTTP/注册日志计数 | 4 个并发首次请求对同一 DeepAgent 只产生一次注册（processedForAgent per-DeepAgent 幂等） |
| Provider/Manager 生命周期 | `F005-DA-15` | active | 关闭进程后的 Mock 计数稳定 | — |
| 自定义 Provider 替换 | `F005-DA-16` | **deferred-fixture** | 需 deep-research 侧 `skillhub-custom-provider` profile | — |
| required 移交失败 | `F005-DA-17` | **deferred-fixture** | 需 deep-research 侧 `skillhub-install-failure` profile | 一旦补齐：首次 install 失败后同一 DeepAgent 幂等；DeepAgent rebuild inner ReActAgent 场景不重复上报 |
| DeepAgent 安装（L2 T14 核心） | 融入 DA-04/DA-13/DA-14 | active | inner ReActAgent 获得 skill 且 marker 在 DeepAgent 输出中出现 | 无需独立方法；本文档通过 marker 断言链闭环覆盖 |

active = 15；deferred-fixture = 2。

## 3. 前置条件与共享约定

### 3.1 SUT 与配置

- 默认 `TestConfig.load()` 走 LOCAL（`application-local.yml`）；DeepAgent 版本不复用
  `-Dtest.env=openjiuwen`（因 openjiuwen profile 未声明 deep-research 别名）。
- 使用隔离别名 `deep-research-skillhub`（同 `agent-deep-research` 坐标），与 `deep-research` 别名
  区分，不干扰 `deepagent_deepresearch/` 目录下其他用例。
- 每个 active 方法使用新的外部 DeepAgent 进程、Mock Skill Hub、临时目录和 token canary。
- **不注入 `SEARCH_AGENT_URL` / `VERIFY_AGENT_URL`**：`agent-deep-research` 的 `application.yml`
  把 `remote-agents: [search-agent, verify-agent]` 硬编码（URL 用 `${SEARCH_AGENT_URL:...}` 占位
  取默认），不注入环境变量只是让 URL 走占位默认值，`remote-agents` 依然被注册到 planner tool 表；
  实测发现 planner 仍能对 marker 型 skill 请求直答（DA-04 marker 由 planner 直出）。因此本前提
  应理解为“**默认值路径下 planner 会直答**”，不是“sub-agent 不注册”。
- SkillHub 配置通过 `AgentBuilder.property/env` 注入；token 不作为命令行明文参数。

```yaml
# application-local.yml
deep-research:
  group: com.openjiuwen.example
  artifact: agent-deep-research
  version: 0.1.0

deep-research-skillhub:
  group: com.openjiuwen.example
  artifact: agent-deep-research
  version: 0.1.0
```

### 3.2 黑盒入口与证据

同 ReactAgent 版本：Agent card → readiness；同步/流式 A2A → 业务响应；Mock HTTP method/path/
query/header 类型和计数 → SkillHub 行为；测试指定 localDir → 落盘材料；当前进程增量 stdout/file
log → 诊断/生命周期/脱敏。证据优先级：**业务响应/ready > Mock HTTP 审计 + 落盘材料 > 日志**。

### 3.3 Mock Skill Hub

与 `SkillHubReactAgentBlackboxTest` 使用**完全相同**的 Mock 实现（唯一测试类内 JDK `HttpServer`）：

```text
GET /api/v1/plugins?plugin_type=skill&page=1&page_size=200
GET /api/v1/artifacts/{assetId}?version={version}
GET /downloads/{assetId}.zip
```

Mock 支持成功、无摘要、401、403、404、列表 5xx、下载 5xx、空包、损坏 ZIP、缺失 `SKILL.md`、
错误 SHA-256、前 N 次失败后恢复以及成功后远端内容切换。审计只保存 method、path、query、
认证类型、请求序号和响应场景，不保存 token 或认证头值。

### 3.4 数据、日志与 LLM

- 日志按 append 文件方式读取；启动前记录 offset，只读当前进程增量。
- 每方法使用唯一 localDir、marker、内容探针，防止顺序依赖。
- 脱敏失败只报告命中类别，不在断言消息或问题报告回显敏感值。
- 只有验证最终业务效果、同步/流式 hook 和并发的场景依赖 LLM；其余（disabled、fail-fast、
  下载/校验失败）不需要 LLM。
- LLM 参数仅由 shell 环境注入（`LLM_API_KEY` / `LLM_API_BASE` / `LLM_MODEL` /
  `LLM_PROVIDER` / `LLM_SSL_VERIFY`），不写入源码、YAML 或测试报告。
- 外部进程一律通过 `SutStack` 关闭；后台重试场景结束后验证 Mock 计数稳定。

## 4. 配置、认证与默认 Provider 子用例

框架落点：`SkillHubDeepAgentBlackboxTest.java`。

### F005-DA-01 — disabled/no Provider

- **状态**：active，P0。
- **追溯**：version-scope 可选装配；L2 T11。
- **G**：DeepAgent 别名，`enabled=false`，Mock 可正常响应。
- **W**：启动 DeepAgent，读取 Agent card并发送普通同步 A2A 请求。
- **T**：Agent ready、请求完成；启动和业务请求期间均没有 Skill Hub HTTP。
- **方法**：`disabledSkillHubKeepsDeepAgentReady()`。

### F005-DA-02 — required endpoint 配置缺失

- **状态**：active，P0。
- **追溯**：required 配置 fail-fast；L2 T1 config。
- **G**：`enabled=true`，endpoint 为空，其他必要配置有效。
- **W**：启动外部 DeepAgent。
- **T**：启动失败或 readiness 不通过；诊断包含 endpoint 缺失语义，不输出凭据参数。
- **方法**：`missingEndpointFailsFast()`。

### F005-DA-03 — bearer 与 system-token 认证

- **状态**：active，P0，参数化两组。
- **追溯**：默认 bearer、system-token、配置归 runtime；L2 T10。
- **G**：分别省略 `auth-type` 和显式设置 `system-token`；每组使用唯一 token。
- **W**：启动 DeepAgent，Mock 审计列表和 artifact 请求的认证头。
- **T**：bearer 只出现 Authorization Bearer 与 OAuth provider；system-token 只出现
  `X-System-Token`；头值匹配但日志不含 token。
- **方法**：`configuredAuthenticationHeaderIsUsed(String authCase)`。

### F005-DA-04 — 默认 Provider、完整性和首次注册（DeepAgent 视角）

- **状态**：active，P0。**L2 T14 DeepAgent 分支主承载用例**。
- **追溯**：默认 openJiuwen adapter、Provider 全流程、SHA-256/常规校验、DeepAgent 分支移交；L2 T4/T5/T6/T14。
- **G**：Mock 返回两个 skill，一个带 SHA-256，一个无摘要但材料完整。
- **W**：启动 DeepAgent，核对 list→artifact→download，随后发送使用远程 skill 的新请求。
- **T**：两 skill 均落盘且通过对应校验；第一个新请求前完成注册；业务响应包含预期 marker；
  注册汇总为 2。
- **DA附加**：marker 必须由 planner 直接产出（无 `SEARCH_AGENT_URL` 时 planner 是唯一执行者），
  证明 skill 已通过 `deepAgent.getAgent()` 到达 inner ReActAgent 的 prompt。
- **方法**：`defaultProviderDownloadsVerifiesAndRegistersAllSkills()`。

### F005-DA-05 — required 认证/授权失败

- **状态**：active，P0，参数化 401/403。
- **追溯**：required 认证/授权 fail-fast；错误分类与脱敏。
- **G**：Mock 在已收到正确 bearer 头后分别返回 401、403。
- **W**：启动外部 DeepAgent并观察 readiness、异常和当前进程日志。
- **T**：Agent 不得 ready；诊断保留 `AUTH_FAILED` 或等价认证/授权语义；全链不含 token。
- **方法**：`requiredAuthenticationFailureBlocksReadiness(int status)`。
- **裁决**：version-scope、L2 §1.2 和 §2.1 规定 fail-fast；L2 T1/§4.9 的降级描述与其冲突，
  本用例采用规范正文，若实现降级 ready 则记录产品/设计一致性缺陷。

### F005-DA-06 — required skill 查找失败

- **状态**：active，P0。
- **追溯**：required 查找 fail-fast；L2 T1 lookup。
- **G**：列表返回 required skill，artifact 查询返回 404。
- **W**：启动外部 DeepAgent并观察 readiness、诊断和下载计数。
- **T**：Agent 不得 ready；诊断保留 `NOT_FOUND`；不请求下载 URL。
- **方法**：`requiredSkillNotFoundBlocksReadiness()`。
- **裁决**：同 DA-05，按 version-scope 与 L2 规范正文采用 fail-fast。

## 5. 下载、完整性与恢复子用例

框架落点：`SkillHubDeepAgentBlackboxTest.java`。

### F005-DA-07 — 下载失败降级与请求链路外重试

- **状态**：active，P0。
- **追溯**：下载失败降级；L2 T2/T15。
- **G**：列表和 artifact 成功，下载返回 5xx 或发生连接中断。
- **W**：启动 DeepAgent，不发送用户请求，等待 Mock 出现新的下载周期，再发送普通请求。
- **T**：DeepAgent 降级 ready且普通请求可用；skill 未注册；后台新增 HTTP 发生在无用户请求期间。
- **DA附加**：降级 ready 后 planner 仍能对普通请求返回合法终态（DeepAgent lifecycle 不受 skill 未就绪影响）。
- **方法**：`downloadFailureDegradesAndRetriesOutsideRequestPath()`。

### F005-DA-08 — 无效材料拒绝注册

- **状态**：active，P0，参数化四组。
- **追溯**：完整性拒绝和常规检查；L2 T3/T7。
- **G**：分别提供错误 checksum、损坏 ZIP、空 ZIP、缺失 `SKILL.md`。
- **W**：启动 DeepAgent，等待后台重试，检查当前日志和 localDir。
- **T**：DeepAgent 可降级 ready；诊断分类明确；无成功注册日志；不存在可用 `SKILL.md` 产物。
- **方法**：`invalidMaterialIsRejectedBeforeRegistration(FailureMode mode)`。

### F005-DA-09 — 后台恢复与下一请求首次生效

- **状态**：active，P0。
- **追溯**：降级恢复和下一轮请求生效；L2 T16。
- **G**：首次下载失败，后续后台下载返回有效 skill。
- **W**：不发送用户请求直到日志确认后台恢复，再发送一轮新的 skill 请求。
- **T**：恢复发生在请求链路外；下载次数至少 2；恢复后的下一轮新请求注册并使用 skill。
- **DA附加**：marker 出现在 DeepAgent 首次成功注册后的第一次新请求响应中。
- **方法**：`backgroundRecoveryActivatesSkillOnFollowingRequest()`。

## 6. 稳定部署、安全、请求 hook、并发与生命周期子用例

框架落点：`SkillHubDeepAgentBlackboxTest.java`。

### F005-DA-10 — 不重复下载/注册且不热刷新

- **状态**：active，P0。
- **追溯**：稳定部署态、非请求下载、首次有效注册幂等、不热刷新；L2 T8。
- **G**：启动下载 v1 并首次注册成功。
- **W**：连续发送请求，随后把 Mock 内容切换为 v2，再次请求 v1 marker。
- **T**：请求期无新增 Skill Hub HTTP；有效注册一次；响应继续使用 v1，不出现 v2。
- **DA附加**：DeepAgent 多轮请求不因 planner 内部 rebuild inner ReActAgent 而触发二次注册。
- **方法**：`requestsDoNotDownloadAgainOrHotRefreshRegisteredSkill()`。

### F005-DA-11 — 配置变更重启生效

- **状态**：active，P0。
- **追溯**：endpoint/token/localDir 为稳定部署配置；配置重启生效；生命周期。
- **G**：准备 v1/v2 两个 Mock、两个 token 和两个 localDir。
- **W**：启动 v1、完成请求并关闭；用 v2 配置重新启动同一坐标。
- **T**：PID 变化；新进程只访问新 endpoint并使用新目录；旧 Mock 计数不再增长；v2 业务标记生效。
- **方法**：`deploymentConfigurationChangesTakeEffectAfterRestart()`。

### F005-DA-12 — 凭据、endpoint path 与 skill 内容脱敏

- **状态**：active，P0。
- **追溯**：凭据与敏感信息保护、启动诊断；L2 T10。
- **G**：token、endpoint path 和 `SKILL.md` 正文分别放置唯一内容探针。
- **W**：启动、注册、读取并实际使用远程 skill，读取当前进程完整增量日志。
- **T**：业务响应成功；stdout/file log 不含三类探针；日志只允许 `credential=provided` 等摘要。
- **方法**：`diagnosticsRedactCredentialsEndpointPathAndSkillContent()`。
- **产品 gap 观察（非新 issue，不写 bug）**：`agent-deep-research` 0.1.0 example 的 ability_manager
  未注册 `readFile` tool（SUT 明确 WARN：`skill prompt requires tool 'readFile' but it is not found
  in ability_manager. existing_tools=[edit_memory, write_memory, memory_search, read_memory,
  memory_get]`），且 memory tool 根路径是 `workspace/memory/`、被 basename 扁平化，无法读取 skill
  安装目录下的 `SKILL.md`。planner 只能靠 `SkillHubInstaller` 抽取到系统 prompt 里的 skill
  description 兜出 marker，SKILL.md body 永远不进入 tool result / LLM messages 日志。因此本用例
  当前 PASS 是**忠实反映 DeepAgent example 尚未打通 skill 完整读取链路**，并非产品已解决 issue #30
  的日志泄露路径；一旦 example 补齐 `readFile` 或引入能读 skill 目录的 tool，需重新评估——预期
  会因 issue #30（`AbilityManager.logToolResult` + `BaseModelClient` 完整 messages INFO）而变红。

### F005-DA-13 — 同步与流式请求 hook

- **状态**：active，P0，参数化同步/流式。
- **追溯**：`query()`/`streamQuery()` 请求期首次注册 hook；DeepAgent 分支两条 hook 一致性。
- **G**：每种协议分别启动一个已下载有效 skill 的 DeepAgent。
- **W**：发送对应协议的第一个业务请求。
- **T**：两种入口均在业务处理前完成首次 skill 注册，并返回合法终态及对应 marker。
- **DA附加**：DeepAgent 的两条 hook（`query` / `streamQuery`）均能触发 `install(DeepAgent, paths)`
  → `deepAgent.getAgent()` 路径，marker 通过 planner 输出可见。
- **方法**：`syncAndStreamingRequestsApplySkillHook(MessageProtocol protocol)`。

### F005-DA-14 — 并发首次请求幂等（per-DeepAgent processedForAgent）

- **状态**：active，P0。
- **追溯**：并发首次注册；L2 T17 外部可观察部分；DeepAgent 分支的 processedForAgent 幂等。
- **G**：启动阶段已下载并校验一个 skill，尚未发送业务请求。
- **W**：同时发送 4 个同步 A2A 首次请求。
- **T**：四个请求均正常完成；请求阶段无重复下载；同一 DeepAgent 只出现一次有效注册；
  无 `ConcurrentModificationException`。
- **DA附加**：验证 `processedForAgent` key 稳定绑定 DeepAgent 实例（不是 inner ReActAgent），
  4 并发请求对同一 DeepAgent 只产生 1 次 `SkillHub skill registered skillPath=` 日志。
- **方法**：`concurrentFirstRequestsDoNotDuplicateDownloadOrRegistration()`。

### F005-DA-15 — Provider/Manager 生命周期

- **状态**：active，P1。
- **追溯**：Provider/Manager `stop` 生命周期。
- **G**：下载持续失败并已启动后台重试。
- **W**：确认至少一次后台重试后关闭外部 DeepAgent，持续观察 Mock 请求计数。
- **T**：进程退出后计数在观察窗口保持稳定；日志不泄露资源敏感信息。
- **方法**：`closingAgentStopsBackgroundRetries()`。

### F005-DA-18 — AES-GCM 凭据解密路径（不外联真实 Skill Hub）

- **状态**：active，P1。
- **追溯**：version-scope 凭据保护、`credential-ref`/加密凭据；L2 T10 redaction；DeepAgent 侧
  `DemoAesGcmCredentialDecryptor` 部署态解密路径。
- **G**：本地 Mock Skill Hub（127.0.0.1，**不访问** `swarmskills.openjiuwen.com`）；测试每次
  运行随机生成 32 字节 AES-256 key（hex 编码）和随机 plaintext bearer canary；用
  `AES/GCM/NoPadding`、12 字节 IV、128 位 tag 加密，wire 格式 `base64(IV || CT || TAG)`；
  设置 `openjiuwen.demo.deep-research.credential.mode=aes-gcm`；ciphertext 通过 encrypted-token
  env 传入，key hex 通过独立 env 传入（-D 只承载 `${...:}` 占位，进程命令行不含明文密钥）。
- **W**：启动 DeepAgent，Mock 用 `expectAuth(BEARER, plaintext)` 断言收到的头值等于解密后
  明文；随后发送一次流式 marker 请求。
- **T**：`SkillHubMiddlewareAutoConfiguration` 激活；`DemoAesGcmCredentialDecryptor` 报告
  `active` 生命周期日志；Mock 所有 API audit 命中 `BEARER + authMatched=true`（等价于
  “解密后的 plaintext 到达了 HTTP 头”）；业务响应包含 marker；进程 stdout 日志既不含
  keyHex、也不含 base64 ciphertext、也不含 plaintext bearer。
- **方法**：`aesGcmDecryptorSuppliesPlaintextBearerToProvider()`。
- **安全约束**：key hex、ciphertext 和 plaintext canary 只在 JVM 内构造，不写入源码、不
  提交仓库、不打印到测试日志；Mock 只做“收到即断言”，不回显敏感头值。

### F005-DA-19 — AES-GCM 凭据生命周期：解密层契约 + HTTP 401 传导

- **状态**：active，P1，参数化四组（**3 组 decrypt 层契约 + 1 组端到端 HTTP 层**）：
  `WRONG_KEY` / `MALFORMED_CIPHERTEXT` / `MISSING_KEY` / `DECRYPTED_BUT_HUB_REJECTS`。
- **追溯**：version-scope §4 + §5.1.2 + §5.1.5 required 凭据无效必须在**认证阶段** fail-fast；
  L2 T10 redaction；`DemoAesGcmCredentialDecryptor` 失败路径；
  `SkillHubMiddlewareAutoConfiguration.credentialSupplier` 的 `catch(Exception)` 降级策略；
  issue #29（HTTP 401/403/404 分类在 Provider→Manager 之间的透传）。
- **两类断言的清晰分工**：
  - **Decrypt/config 层契约**（模式 1-3）：Mock 用**非严格模式**（bearer 匹配才 200，
    不匹配也 200，只做审计）。凭据配置态无效属于"认证阶段失败"，Provider 必须**根本不构造**、
    Skill Hub 一次也不能被联系。若产品仍走 `catch(Exception)→credential=absent` 降级并构造
    Provider，Provider 会带空 bearer 打 Mock、Mock 回 200、SUT ready，`expectStartupFailure`
    以 `unexpectedly became ready` 报错——**这就是 §5.1.2/§5.1.5 违反的直接执行信号**，
    不会被 strictAuth 401 掩盖。
  - **HTTP 层 #29 传导**（模式 4）：Mock 用 **strictAuth**（bearer 不等于 canary 返回 401）。
    encrypted-token 用真 key 加密"错的 plaintext"，Provider 解密成功 → Bearer=wrongPlaintext →
    Mock 401 → Manager 分类 AUTH_FAILED → fail-fast。用 AES 语境端到端验证 #29 修复。
- **G**：本地 Mock Skill Hub，测试内生成 32 字节 real key、canary、wrongPlaintext；
  同时预备 wrong key 和 `base64([0x01,0x02,0x03])` 形态的 malformed 密文；
  `credential.mode=aes-gcm` 恒定。四组共享同一 `AesGcmFailureMode` 枚举参数化。
- **W**：启动 DeepAgent（`enabled=true`，required 语义），期望四组模式**均 fail-fast 不进入 ready**；
  不发送业务请求。
- **T**：
  - `WRONG_KEY`（decrypt-layer 契约）：`SutStack.start()` 抛 `IllegalStateException`；
    诊断含 `AES-GCM decrypt failed: Tag mismatch`；**`hub.apiAudits()` 必须为空**——
    Skill Hub 一次都不能被联系。
  - `MALFORMED_CIPHERTEXT`（decrypt-layer 契约）：同上，诊断含 `ciphertext too short to contain
    a 12-byte IV`；`hub.apiAudits()` 必须为空。
  - `MISSING_KEY`（bean 构造契约）：`DemoAesGcmCredentialDecryptor` 构造函数即抛，诊断含
    `aes-key-hex is required when credential.mode=aes-gcm`；`hub.apiAudits()` 必须为空。
  - `DECRYPTED_BUT_HUB_REJECTS`（HTTP 层 #29 传导）：Provider 用解密后的 wrongPlaintext 打 Mock；
    `hub.apiAudits()` **非空**且**全部 `authMatched=false`**、`headerKind=BEARER`；
    诊断合并含 `AUTH_FAILED`（Manager 从 HTTP 401 得出的分类）。
  - 四组通用：`realKeyHex` / `wrongKeyHex` / valid & wrong ciphertext / canary /
    wrongPlaintext 均不出现在诊断日志；redaction 覆盖 decrypt fail-fast 分支和 HTTP fail-fast 分支。
- **方法**：`aesGcmDecryptionFailures(AesGcmFailureMode mode)`；类内私有 `aesGcmEncrypt(key, plaintext,
  rng)` 助手供 DA-18 与 DA-19 复用。
- **spec 定位与产品缺陷映射**：
  - 模式 1-3 若变红（Mock 被联系 / Agent ready）**= `SkillHubMiddlewareAutoConfiguration.credentialSupplier`
    的 `catch(Exception)→return absent` 违反 §4/§5.1.2/§5.1.5**：认证阶段失败必须 fail-fast，
    不允许静默降级到 credential=absent 后再依赖下游 401 补救。
  - 模式 4 若变红 = **issue #29 未修**（Provider 拿到 hub 401 后分类没有透传到 Manager）。
  - 前 3 组与第 4 组独立：修 #29 不能顺带修 decrypt-layer 缺陷，反之亦然；每一组的红/绿对应一个
    独立缺陷的定位信号。

### F005-DA-16 — 自定义 Provider 替换 [deferred-fixture]

- **状态**：**deferred-fixture**，P0（补齐后转 active）。
- **追溯**：version-scope 可替换 Skill Hub SPI；L2 自定义 `@Bean SkillHubProvider` 覆盖默认实现。
- **fixture 缺失**：`agent-deep-research` example jar 未提供 `skillhub-custom-provider` profile 或
  等价的 hotel-custom Provider 生命周期日志标记。ReactAgent 版本 BB-16 依赖 hotel example 侧的
  fixture Bean。
- **补齐条件**：`agent-deep-research` 侧新增 `skillhub-custom-provider` profile，profile 中提供
  自定义 `SkillHubProvider` Bean，输出 `DeepResearch custom SkillHub provider started
  adapter=deep-research-custom` 等生命周期标记；补齐后按 ReactAgent 版本 BB-16 相同 G/W/T 设计
  可执行方法。
- **当前**：不生成 Java 方法，保留需求追溯。

### F005-DA-17 — required 移交失败 [deferred-fixture]

- **状态**：**deferred-fixture**，P0（补齐后转 active）。
- **追溯**：version-scope required 移交失败；L2 T13、T21；DeepAgent 分支的 per-agent processed 幂等。
- **fixture 缺失**：`agent-deep-research` example jar 未提供 `skillhub-install-failure` profile 或
  等价的 `registerSkill()` 抛出确定性异常的 DeepAgent variant。ReactAgent 版本 BB-17 依赖 hotel
  example 侧的 fixture profile。
- **补齐条件**：`agent-deep-research` 侧新增 `skillhub-install-failure` profile，profile 让
  DeepAgent 的 inner ReActAgent `registerSkill()` 首次调用抛 `IllegalStateException`；补齐后按
  ReactAgent 版本 BB-17 相同 G/W/T 并额外验证：**DeepAgent 幂等**——`processedForAgent` 用
  DeepAgent 作 key，第二次请求（同一 DeepAgent、同一 inner ReActAgent）不重复抛
  `SkillHub[INSTALL_FAILED]`。
- **当前**：不生成 Java 方法，保留需求追溯。

### 非黑盒边界

以下边界不生成 Java 空方法（与 ReactAgent 版本一致）：

- Agent skill 选择、optional/required 选择和 agent-id 安装为 `deferred-boundary`；L2 已明确第一期
  不实现或不支持。
- reregister、多 Agent 共享 Manager 和后台重试重新启动为 `component-test`（L2 T18/T19/T20），
  当前 DeepAgent 也没有公开黑盒入口触发这些能力。
- ReactAgent-only 语义（T14 反向：单纯 `BaseAgent` 而非 DeepAgent）不适用，已由 ReactAgent
  版本承载。

## 7. 框架落点汇总

| Java 类 | active 子用例 | deferred-fixture | 类内私有 fixture |
|---|---|---|---|
| `SkillHubDeepAgentBlackboxTest` | DA-01～DA-15、DA-18、DA-19 | DA-16、DA-17 | Mock Skill Hub、ZIP/SKILL.md 构造、日志切片、进程启动和 HTTP 审计、类内 AES-GCM 加解密辅助（与 ReactAgent 版本同构） |

落点目录：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/
```

不新增 FEAT-005 TestSupport 或独立测试数据文件；`MockSkillHub` 结构与 ReactAgent 版本相同但
类内私有，两个测试类互相独立，避免共享 fixture 造成隐式依赖。

## 8. 需求与 L2 追溯

### 8.1 version-scope 追溯

| 能力/边界 | 用例 | 状态 |
|---|---|---|
| 部署/启动阶段访问 | DA-04、DA-07、DA-10 | active |
| runtime 持有 endpoint/认证/凭据 | DA-03、DA-11、DA-12 | active |
| Agent skill 选择及 required/optional | 当前版本不生成 Java 用例 | deferred-boundary |
| 可替换 SPI | DA-16 | deferred-fixture |
| 默认 openJiuwen adapter | DA-03～DA-10 | active |
| 下载、摘要与常规完整性 | DA-04、DA-08、DA-09 | active |
| 校验后移交和首次生效 | DA-04、DA-09、DA-17 | 15 active + 1 deferred |
| required 配置/认证/授权/查找 fail-fast | DA-02、DA-05、DA-06 | active |
| required 移交失败传播 | DA-17 | deferred-fixture |
| 下载/校验失败降级重试 | DA-07～DA-09 | active |
| 凭据和内容保护、错误诊断 | DA-02、DA-05～DA-08、DA-12、DA-18、DA-19 | active |
| 不在请求中下载、不热刷新 | DA-10 | active |
| 不自主获取、不独立授权 | DA-03、DA-05、DA-10 | active |
| 不直接注入 instructions | DA-04 业务效果与日志内容扫描 | active |
| 服务端治理、agent-core 语义、其他中间件 | 不作内部断言 | out-of-scope |

### 8.2 L2 T1～T21 追溯

L2 原编号没有 T12，保持原样：

| L2 | 用例/裁决 |
|---|---|
| T1 config/auth/lookup | DA-02/05/06；采用 version-scope 与 L2 §1.2/§2.1 的 fail-fast 正文 |
| T2 download failure | DA-07、DA-09 |
| T3 checksum mismatch | DA-08、DA-09 |
| T4 download then register | DA-04 |
| T5 SHA-256 | DA-04 |
| T6 conventional verify | DA-04 |
| T7 reject invalid | DA-08 |
| T8 no duplicate registration | DA-10 |
| T9 optional | L2 第一期无 required 字段；deferred-boundary，不生成 Java 空方法 |
| T10 redaction | DA-03、DA-12、DA-18（加密凭据解密成功路径）、DA-19（解密失败 fail-fast + HTTP 401 fail-fast 两条分支） |
| T11 no Provider/disabled | DA-01 |
| T13 INSTALL_FAILED | DA-17（deferred-fixture）；补齐后追加 DeepAgent per-agent 幂等断言 |
| T14 **DeepAgent adapter** | **DA-04**（主承载）+ DA-13（hook 两条路径）+ DA-14（并发幂等） |
| T15 empty verified set | DA-07/08 |
| T16 retry then register | DA-09 |
| T17 concurrent register/retry | DA-14，验证外部可观察部分 |
| T18 reregister | component-test；无 DeepAgent 公开管理入口，不生成 acceptance 空方法 |
| T19 multi-agent Manager | component-test；当前坐标每 JVM 一个 DeepAgent，不生成 acceptance 空方法 |
| T20 retry restart | component-test；属于 Manager 内部重试标志合同，不生成 acceptance 空方法 |
| T21 INSTALL_FAILED no repeat | DA-17（deferred-fixture）；补齐后 per-DeepAgent processed 幂等 |

## 9. 标签与报告

```java
@Feature("FEAT-005: Agent middleware request proxy")
@Tag("feat-005")
@Tag("integration")
@Tag("blackbox")
@Tag("deepagent")
class SkillHubDeepAgentBlackboxTest {
    @Test
    @Story("da.skillhub.disabled: no provider or enabled=false keeps DeepAgent ready without Skill Hub HTTP")
    @DisplayName("F005-DA-01: disabled Skill Hub keeps the DeepAgent ready and makes no remote request")
    void disabledSkillHubKeepsDeepAgentReady() { }

    @Test
    @Story("da.skillhub.default-provider: default openJiuwen Provider downloads, verifies, and DeepAgent adapter registers skills to inner ReActAgent")
    @DisplayName("F005-DA-04: default Provider downloads digest and conventional skills before first use (DeepAgent adapter)")
    void defaultProviderDownloadsVerifiesAndRegistersAllSkills() { }

    // ... 其余 15 个 active + 2 个 deferred-fixture（不生成 Java 方法）
}
```

Allure 报告必须能通过 `feat-005` + `deepagent` 交叉标签发现 17 个 active 方法（DA-01～DA-15、
DA-18、DA-19，其中 DA-19 参数化 4 组：WRONG_KEY / MALFORMED_CIPHERTEXT / MISSING_KEY /
DECRYPTED_BUT_HUB_REJECTS）。
黑盒 failure/error 经复跑和夹具校准后，若确认是产品偏差，另行按 FEAT003 问题模板生成问题文档；
本文件只维护预置场景、预期、约束和追溯关系。

## 10. 运行方式

```bash
# Windows / Linux — 默认 LOCAL 环境
./mvnw -Dtest=SkillHubDeepAgentBlackboxTest test

# 按标签
./mvnw -Dgroups=feat-005 -Dtest="*DeepAgent*" test
```

需要 LLM 的场景（DA-04、DA-09、DA-10、DA-11、DA-12、DA-13、DA-14、DA-18）在执行前通过当前 shell
注入 `LLM_API_KEY`、`LLM_API_BASE`、`LLM_MODEL`、`LLM_PROVIDER` 和 `LLM_SSL_VERIFY`；
文档和命令示例不保存真实凭据。**执行前先 unset HTTP_PROXY/HTTPS_PROXY**（内网代理会拦截
LLM 请求，参见 memory 记录）。

## 11. 风险、代码生成约束与两轮审视

### 11.1 风险与代码生成约束

1. acceptance 使用的 `agent-deep-research` jar 必须包含 `agent-service-adapters-agentcore-ext`
   （已验证 0.1.0 版本内嵌）；SkillHubMiddlewareAutoConfiguration 依赖 `@ConditionalOnProperty
   openjiuwen.service.middleware.skillhub.enabled=true` + `RunnerConfig` classpath，缺一不能激活。
2. version-scope/L2 规范正文与 L2 T1/§4.9 对 required auth/lookup 的语义冲突；测试固定采用
   fail-fast。
3. **`SEARCH_AGENT_URL` / `VERIFY_AGENT_URL` 走默认值即可**：`agent-deep-research` 的
   `application.yml` 把 `remote-agents: [search-agent, verify-agent]` 硬编码（URL 位取
   `${SEARCH_AGENT_URL:...}` 占位默认），不注入环境变量并**不会**让 sub-agent 从 planner tool 表
   消失；实测 marker 型 skill 请求 planner 会直答（DA-04/09/10/13/14 marker 断言链闭环）。此
   前提早期误写为“不注入即 sub-agent 不注册”，现修正——测试行为不变，只是原因描述更准确。
4. LLM 输出字段在同步/流式协议间可能不同，业务断言按合法终态提取，不以字段差异误报产品问题。
5. 日志为 append 文件，必须使用 offset slice；并发和生命周期只读取当前外部进程增量。
6. 并发用例存在调度差异，断言外部幂等语义，不以一次未复现证明线程安全。
7. 不通过加载产品 artifact、反射私有状态或 fake Manager/Installer 来补足黑盒不可观察项。
8. 测试中不得写入真实 LLM key、Skill Hub token、认证头值或敏感 `SKILL.md` 正文。
9. **DA-16、DA-17 fixture 缺失**：`agent-deep-research` example 侧没有 `skillhub-custom-provider`
   / `skillhub-install-failure` profile。补齐前保持 deferred-fixture，不生成会失败的空方法。

### 11.2 与 ReactAgent 版本的互补性审视

- ReactAgent 版本已充分验证 SkillHubManager 通用协议（下载、校验、降级、脱敏、生命周期）。
- DeepAgent 版本的独立价值在于：**L2 T14 分支（`instanceof DeepAgent → deepAgent.getAgent()`）
  在完整生命周期下可用**——这一断言只能通过真实 DeepAgent SUT 承载，不能由 ReactAgent 版本
  借代。
- 保留 17 个平行 case 而非精简：DeepAgent 的 planner 结构、多轮请求 rebuild inner ReActAgent、
  processedForAgent 幂等 key 归属等边界，仅在完整场景压测下才会暴露不一致。
- **哪些用例最具 DeepAgent 独立价值**：DA-04（T14 主承载）、DA-13（两条 hook 均通过 DeepAgent
  路径）、DA-14（processedForAgent per-DeepAgent 幂等）、DA-10（DeepAgent 请求不触发重刷）、
  DA-17（deferred-fixture，per-DeepAgent 幂等错误报告）。

## 12. 退出标准

- 唯一 Java 类可由 `feat-005` + `deepagent` 双标签发现，包含 17 个 active 场景方法（DA-01～DA-15、
  DA-18、DA-19，DA-19 参数化 4 组）；DA-16、DA-17 在本文档保留追溯但不生成 Java 方法，直至
  example jar 侧 fixture 补齐。
- version-scope 的 DeepAgent 能力和 L2 T1～T21 均有 active、deferred-boundary、deferred-fixture、
  component-test、N/A 或 out-of-scope 追溯。
- active 用例只使用外部 DeepAgent jar 和公开黑盒证据，不加载或反射 FEAT-005 产品类。
- bearer/system-token、默认 Provider、下载、两类完整性校验、失败分层、恢复、首次生效、稳定
  部署态、同步/流式 hook、并发、脱敏和生命周期均有明确 G/W/T，且 T14 DeepAgent 分支通过
  DA-04/DA-13/DA-14 marker 断言链闭环。
- 所有外部进程可关闭；关闭后后台重试不再产生 Mock 请求。
- 测试及 SUT 日志不得包含 LLM key、Skill Hub token、认证头值、敏感 endpoint path 或敏感 skill
  内容。
- 测试自身问题修正后重跑；确认的产品偏差另行生成问题报告，不回写测试设计源文档。
