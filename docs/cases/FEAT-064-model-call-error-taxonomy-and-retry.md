---
feature_id: FEAT-064
feature_title: 模型调用错误分类与弹性重试
sut: timeout-tiering-model-call-demo 的 timeout-probe-agent（与 FEAT-058 共用载体，ReActAgent，openjiuwen 范式，fat jar 独立运行；端口 18092 + llm-stub-server 端口 18094），对端 stub 在 FEAT-058 的 7 种 stall 模式之外**追加 7 种 FEAT-064 错误注入模式**（HTTP 429 / 402 / 401 / 500 / 400+context_length_exceeded / SSE error frame / 429→200 recover）；FEAT-064 测的是**分类规则 + 重试策略**，用真实 LLM 无法可控注入这些错误类型，所以 stub 是必要对端
scope: 本档覆盖 FEAT-064 SIT 侧黑盒可观察。T1 结构化错误码主通道走 `/v1/query` SSE 事件**顶层** `failure.{code,numericCode,isRetryable}`（新 wire schema，由 agent-core-java PR!437 + agent-runtime-java PR!236 + agent-runtime-java PR!242 于 2026-10-27 landed；注意是顶层 `failure.*` 而不是 r5 曾一度写错的 `payload.failure.*`）；`code` 字段的 OK 符号为 `MODEL_CALL_FAILED`（r5 曾一度误写 `MODEL_CALL_ERROR`）。L2 §2.5 定义的 A2A Task `message.metadata["openjiuwen.error"].{code,numericCode,retryable}` 为同形字段，仅承载通道不同。T2 走 SUT retry 日志 `Model call will retry. {category=<CAT>, ...}` 行计数（**可重试类** RATE_LIMITED/TEMPORARY，源于 L2 §3.10「每次重试」）。T3 走 SUT terminal 失败日志 `Model call failed. {category=<CAT>, reason=<REASON>}` 行（r7 订正按 core !449 真实常量：**永久类** reason=NOT_RETRYABLE 计数 1 / **可重试类预算耗尽** reason=RETRY_BUDGET_EXHAUSTED 计数 2（Retrier 内层 1 + Rail 整轮 1）/ **开关 OFF** reason=POLICY_DISALLOWED 计数 2（Rail 仍执行一轮）/ **maxRetries=0** reason=RETRY_BUDGET_EXHAUSTED 计数 1；源于 L1 §5.1.7，由 agent-core-java PR!449 于 2026-10-27 landed 落地到平台层）。分类明细（R1-R12 规则表）由 UT 承接，本档不复制。
status: pending-implementation (r7)
status_note: **r7 刷新**（2026-10-29 回应 PR!364 评审员 r6 reply 2 条执行性阻断反馈）：**(1) T3 reason 枚举 + 日志行数按 core !449 真实实现订正**：r6 曾写的 `PERMANENT_ERROR / RETRY_DISABLED_BY_SWITCH / RETRY_BUDGET_ZERO` 三个符号 **不存在**；真实常量为 `NOT_RETRYABLE / POLICY_DISALLOWED / RETRY_BUDGET_EXHAUSTED`（其中 `RETRY_BUDGET_EXHAUSTED` 兼管「可重试类预算耗尽」与「maxRetries=0」两种语义，不再有独立 `RETRY_BUDGET_ZERO`）；并且双层场景的 T3 日志行数需追加 Rail 整轮一次：永久类 1 行、switch-OFF 2 行（Rail 仍执行一轮）、maxRetries=0 1 行、默认可重试类预算耗尽含 Rail 一轮 2 行。相关 TC 的 Then/PASS 行与 §4 盘面表同步订正；fixture `ErrorEnvelopeAsserter` 常量重命名 `REASON_NOT_RETRYABLE / REASON_POLICY_DISALLOWED / REASON_RETRY_BUDGET_EXHAUSTED` + 删除 `REASON_RETRY_BUDGET_ZERO`。**(2) 181012 符号码订正**：core 中真实常量为 `MODEL_CONTEXT_LENGTH_EXCEEDED`（**不**带 `_CALL_`），r6 曾对齐 FEAT-064 其他常量命名误写 `MODEL_CALL_CONTEXT_LENGTH_EXCEEDED`；§3.6/§3.19/§3.9 分类表 + fixture 常量 + 3 个 Java TC 全链路统一订正。**r6 刷新**（保留）：对 PR!364 r5 reply 5 条评审意见落地（wire 顶层 failure.*、§3.18 显式 stream:false、§3.20 ST-S02→ST-S03 子型 relabel、§3.22 新增真 ST-S02、§3.21 DeepSeek→SiliconFlow、永久类 TC partial→runnable）。r5 刷新（保留）：方案 B 字段路径、双层强等 strong-eq、ST 编号订正。r3/r4 评审意见 (C1-C5) 保留；fixture 代码与 FEAT-058 共用；SIT 侧 TC 已在仓侧落地（`Feat064ErrorTaxonomyAndRetryTest` / `Feat064RetryConfigAndCrossProviderTest` / `Feat064RealEndpointSamplingTest`，r7 增量：fixture 常量 rename + T3 行数修正 + 181012 符号订正）。
owner: TBD
tags: [integration, reactagent, feat-064, model-call-error-taxonomy-and-retry]
depends_on:
  - **agent-core-java PR!437 (2026-10-27 landed)**：/v1/query SSE 顶层 structured failure 字段核心 (`data: {"type":"error", "error":"...", "failure":{"code":"MODEL_CALL_FAILED", "numericCode":18100N, "isRetryable":...}}`，T1 主通道源头)
  - **agent-runtime-java PR!236 (2026-10-27 landed)**：runtime 侧 structured failure 透传
  - **agent-runtime-java PR!242 (2026-10-27 landed)**：runtime 侧 SSE body 读取配套
  - **agent-core-java PR!449 (2026-10-27 landed)**：T3 平台层终态结构化日志通道 `Model call failed. {category=<CAT>, reason=<REASON>}` 落地
  - timeout-probe-agent-0.1.0.jar 已就绪（与 FEAT-058 共用 jar；s14-s17 profile 已在 2026-10-26 demo 下发的 fat jar 中就绪）
  - llm-stub-server-0.1.0.jar 已就绪（stub 已含 FEAT-064 7 种错误注入模式；s17 schema 开关 `LLM_STUB_RESPONSE_SCHEMA=openai|dashscope` 已在 2026-10-26 下发的 fat jar 中就绪；`GET /__stats` + `POST /__stats/reset` stats 通道已就绪）
  - **demo S21 stub mode `stream-rate-limited-first-segment-after`**（首段**后**连接按 429 等语义关闭，供 §3.20 ST-S03 子型承接）
  - **demo 新 stub mode `stream-disconnect-first-segment-after`**（r6 新增，首段**后** TCP close 无 payload，供 §3.22 ST-S02 strict 承接；demo-handoff 冒烟待确认）
  - SiliconFlow LLM 真实凭据或兼容 wire 的本地 stub 对接（§3.21 ST-O01 两族跨切；SiliconFlow 走 OpenAI 兼容 chat completions wire，llm-stub-server 可用；r5→r6：DeepSeek 不是 L2 §4.1 独立变化挂点栈，已改选 SiliconFlow）
  - 不需要真实 LLM 凭据——除 §3.16/§3.17（manual tag）外，demo 全部打本机 stub（127.0.0.1:18094）
  - §3.16/§3.17 真实端点 TC 需 `source ~/.llmrc` + unset proxy，CI 不自动跑
  - `unset HTTP_PROXY HTTPS_PROXY`（stub 在本机 loopback，企业代理会干扰；真实端点同样需要）
  - **JDK 17 或 21**（JDK 24+ 下 agent-core-java 的 Lombok `sun.misc.Unsafe` 反射失效，probe-agent fat jar 启动崩）
  - 端口 18092（probe-agent）/ 18094（llm-stub-server）可用
  - `LLM_PROVIDER=OpenAI`（统一分类载体，stub 实现 OpenAI 兼容协议；真实端点同样走 OpenAI 兼容协议）
  - 可选：`LLM_STUB_RATE_LIMITED_RECOVER_AFTER=1`（rate-limited-recover 恢复前返多少次 429，默认 1）
  - 可选：`LLM_STUB_RATE_LIMITED_RETRY_AFTER_SECONDS=1`（429 响应头 `Retry-After` 值，默认 1 秒）
  - **FEAT-064 TC 全部用默认 profile**（不覆写三层超时），避免与 FEAT-058 的 timeout 分类混淆
  - **s14/s15/s16/s17 新增 probe-agent 配置键**（新 jar 自带绑定，SIT 只传 profile）：
    - `demo.timeout-probe.retry-on-rate-limited: false`（s14）
    - `demo.timeout-probe.retry-on-temporary: false`（s15）
    - `demo.timeout-probe.max-retries-override: 0`（s16）
  - **s17 stub env**：`LLM_STUB_RESPONSE_SCHEMA=dashscope` + `LLM_STUB_DEFAULT_MODE=auth-failed`
related_docs:
  - 本方案 testplan（权威位置）：Technical-AF/docs:develop/04-testplan/FEAT-064-model-call-error-taxonomy-and-retry.md
  - 特性文档：develop/02-features/FEAT-064-model-call-error-taxonomy-and-retry.md（17 能力 = 15 MUST + 2 SHOULD + OUT；§4 11 旅程；§7 36 UT 计划；§8 11 ST 计划）
  - L2 设计：develop/03-architecture/L2-Low-Level-Design/agent-core/Feat-Func-064-model-call-error-taxonomy-and-retry.md（§1.2 能力矩阵、§2.1 ModelErrorCategory 表、§2.2 cancellation rules C1-C5、§5.1 R1-R12 规则表、§A 附录实现回灌）
  - 代码口径：agent-core-java@origin/develop @ f68f4da6（PR!420 合入 2026-10-21，60 files +4961/-1194）；关键类 `ModelErrorCategory` / `PermanentModelError` / `ModelCallRetrier` / `ClassifiedModelFailureRail` / `FirstSegmentRetryIterator` / `RuleBasedModelErrorClassifier` / `Rails.isFromOperation` / `StatusCode.{181009,181010,181011,181012}` / `ModelClientConfig.maxRetries(default=2)`
  - demo README：agent-solution/common/example/timeout-tiering-model-call-demo/README.md（S7..S13 覆盖矩阵 + stub 7 种 FEAT-064 模式 + 轨迹断言矩阵 + S14/S15 reserved 说明）
  - demo HANDOFF：.../HANDOFF.md（JDK 17/21 构建口径）
  - SIT 本地 FEAT-064 对齐表（L1↔L2↔代码，L2 §1.2 的 16 条非 OUT 能力对齐 ✅；仅 D1 R5 keyword set 小漂移不阻塞）
  - 关联 cases 文件（共用 fixture）：docs/cases/FEAT-058-model-call-timeout-tiering.md
  - 保留项依据：SIT-CLARIFY-REPLY-must11-cancel-definition.md（USER_CANCELLED 延迟到 FEAT-058 MUST#11 裁定落地）
---

# FEAT-064 — 模型调用错误分类与弹性重试 用例设计

> **一句话**：以 `timeout-probe-agent` 为 SUT（与 FEAT-058 共用）、`llm-stub-server` 为可控对端（追加 7 种 FEAT-064 错误注入模式），把 FEAT-064 §2 的 15 MUST + 2 SHOULD + default `maxRetries` 3→2 外部可观察行为变化，映射为 SIT 侧黑盒断言的子用例。T1 主通道走 **/v1/query SSE 事件顶层 `failure.{code,numericCode,isRetryable}`**（新 wire schema，由 agent-core-java PR!437 + agent-runtime-java PR!236 + agent-runtime-java PR!242 于 2026-10-27 landed；symbol OK 符号 `MODEL_CALL_FAILED`）；L2 §2.5 定义的 A2A Task `message.metadata["openjiuwen.error"].{code,numericCode,retryable}` 为同形字段，本档走 /v1/query SSE 侧，SIT 不改走 A2A entry。分类 category 走 SUT 日志：T2 可重试类 `Model call will retry.` 行；**T3 已 r6 landing**：永久类 / 预算耗尽 / 开关 OFF / maxRetries=0 走 terminal 日志 `Model call failed. {category=<CAT>, reason=<REASON>}` strong-eq 1 行（agent-core-java PR!449 2026-10-27 landed）。按 L2 §2.3 "Rail+Retrier 双层" 事实，持续可重试类 strong-eq `stub=6 / retry 日志=4`（`(maxRetries+1)×(railRetries+1) = 3×2`）；开关 OFF strong-eq `stub=2`；maxRetries=0 两层全关 strong-eq `stub=1`；永久类 strong-eq `stub=1`。USER_CANCELLED（C1-C5）SIT 覆盖已由 FEAT-058 §3.12/§3.13 承接。
>
> **r6 刷新**（2026-10-28 回应 PR!364 评审员 r5 reply 5 条评论）：
> - **(1) wire 字段路径订正**：T1 字段路径从 r5 的 `payload.failure.*` 订正为**顶层** `failure.*`；symbol OK 符号 `MODEL_CALL_FAILED`（r5 曾误写 `MODEL_CALL_ERROR`）；PR deps 订正为 `agent-core-java PR!437 + agent-runtime-java PR!236 + agent-runtime-java PR!242 + agent-core-java PR!449`（r5 的 `agent-runtime-java PR!449` 是 phantom）。
> - **(2) §3.18 ST-R02 请求体订正**：原"不带 stream:true"的隐式写法改为显式 `"stream":false`。
> - **(3) §3.20 ST-S02 relabel + §3.22 新增**：§3.20 relabel 为 ST-S03 子型 (first-segment-after + retryable semantics)；新增 §3.22 TC-STREAM-DISCONNECT-FIRST-SEGMENT-AFTER-STRICT 作为真正 ST-S02 (strict pin numericCode==181001 unique，stub 新 mode `stream-disconnect-first-segment-after` 首段后 TCP close 无 payload)。
> - **(4) §3.21 跨族订正**：OpenAI × DeepSeek → OpenAI × SiliconFlow（DeepSeek 反射委托 OpenAIModelClient 不独立；SiliconFlow 有独立 Retrier/`FirstSegmentRetryIterator`/handlers 栈，是真正第二族）。
> - **(5) 永久类 TC partial→runnable + T3 strong-eq**：T3 平台层终态结构化日志通道已由 agent-core-java PR!449（2026-10-27 landed）开放；§3.3/§3.4/§3.6/§3.13/§3.17/§3.19 全部升为 runnable，T3 strong-eq `category=<CAT>, reason=NOT_RETRYABLE` 严格 1 行；fixture `assertTerminalLogCategory` UOE 占位解除 + `SutLogTailer.countOfTerminalFailureLog(category, reason)` 新增。
>
> **r5 刷新**（保留，回应 2026-10-28 评审 4 条意见 + SIT 侧 a/b 两项补充）：
> - **(1) 方案 B 字段路径订正**（r6 再订正为顶层 failure.*）：T1 主通道从 `metadata.openjiuwen.error.numericCode` 订正为 /v1/query SSE 事件 failure.numericCode；`§2.2` Fixture 清理 `errorDetails()` category 残留 + deprecate 老 `assertCategory` 字串判据。
> - **(2) 双层重试 strong-eq 重锁**：按 L2 §2.3「全程调用上限 = (maxRetries+1)×(护栏重试次数+1)」确定公式，§3.1/§3.5 从 r4 区间 `[3,6]/[2,4]` 回锁 strong-eq `6/4`；§3.10/§3.11 从 `[1,2]` 回锁 strong-eq `2`；§1.5.2 待开发裁定段废弃，`assertStubCallCountBetween` fixture 弃用。
> - **(3) ST 编号订正 + 4 条新 TC**（r6 §3.22 补齐真正 ST-S02 strict + §3.20 relabel 为 ST-S03 子型 + §3.21 由 DeepSeek 改为 SiliconFlow）：§3.16→ST-R01、§3.17→ST-C01、§3.7→ST-S03；§3.14 不再认领 L1 ST-R01；新增 §3.18-§3.21。
> - **(4) 永久类 → partial**（r6 升为 runnable + T3 strong-eq）：§3.3/§3.4/§3.6/§3.17/§3.19 五条 TC 的 T3 category 终态日志通道 r5 时未落地走 partial，r6 由 PR!449 开通后升为 runnable；ST-O02（两族独立栈 real-endpoint）继续列作跟踪项不入 TC 骨架。
> - **(a) L1 ST-R04 层级范围脚注**（见 §5）：L1 §8.3 ST-R04「总调用=3」是 Retrier 内层单层描述；L2 §2.3「6」是双层总计；两者层级范围不同、各自正确，SIT 以 L2 §2.3 双层事实为锚。
> - **(b) fixture LLM_PROVIDER 覆写**（见 §2.2）：`TimeoutDemoLauncher` 默认 probe env 允许 TC 侧覆写 `LLM_PROVIDER`，支撑 §3.21 ST-O01 两族切换（r6 由 DeepSeek 改为 SiliconFlow）。

> **状态含义**：
> - **runnable**：fixture 就绪 + 可直接落地实现
> - **partial**：核心路径可测，某些维度受 wire 层边界限制
> - **deferred**：产品侧未就绪（如 USER_CANCELLED 待 MUST#11 裁定）

---

## 0. 执行前置快速清单

- [ ] `timeout-probe-agent-0.1.0.jar` 存在（与 FEAT-058 共用）
- [ ] `llm-stub-server-0.1.0.jar` 存在；stub 支持 FEAT-064 7 种模式（demo HANDOFF 冒烟 7/7 PASS 后方可开跑）
- [ ] **JDK 17 或 21**（JDK 24+ 启动会崩）
- [ ] `unset HTTP_PROXY HTTPS_PROXY`
- [ ] 端口 18092 / 18094 空闲
- [ ] `export LLM_PROVIDER=OpenAI`
- [ ] 可选：`export LLM_STUB_RATE_LIMITED_RECOVER_AFTER=1`（TC-RATE-LIMITED-RECOVER 用）
- [ ] 可选：`export LLM_STUB_RATE_LIMITED_RETRY_AFTER_SECONDS=1`

---

## 1. 覆盖矩阵（能力 → 子用例）

本表每行是本档一个"实现要素"粒度子项（粒度细于 L2 §1.2 的"能力"级），用 "L2 §1.2 条目" 列显式回指 L2 §1.2 的能力编号 + 要求级别（**照抄 L2，不自造标注**；条目号即 L2 §1.2 表格从上往下的 1..17）。SIT 侧状态 "runnable" 表示方案就绪、fixture 待落地；"deferred" 表示 SIT 不承接或延迟承接；"UT only" 表示能力 100% 由 UT 承接。

| 实现要素 | L2 §1.2 条目 (要求) | 本档 TC ID | 状态 | UT 承接（不复制） | 备注 |
|---|---|---|---|---|---|
| ModelErrorCategory 6 值枚举（分类合同） | §1.2.1 统一错误分类器 (MUST) + §1.2.2 失败表面分类化 (MUST) | `FEAT-064.category-rate-limited` / `.quota-exceeded` / `.auth-failed` / `.temporary` / `.context-length-exceeded`（S7-S12） | runnable | `RuleBasedModelErrorClassifierTest` R1-R12 | 5 类 wire 层可观察；USER_CANCELLED 延迟 |
| PermanentModelError.defaultRecoverable()=false | §1.2.2 失败表面分类化 (MUST) | `FEAT-064.permanent-short-circuit`（S9/S10/S12 的"无 Model call will retry"） | **partial** | `PermanentModelErrorTest` | r5 降级 runnable→partial：§3.3/§3.4/§3.6 T3 category 字段待平台层终态日志通道开放，当前仅断 T1 numericCode；QUOTA/AUTH/CTX_LEN 三类短路事实本身（T2 零 retry + stub==1）仍走 strong-eq |
| ClassifiedModelFailureRail 启动期 fail-fast | §1.2.11 主循环护栏按类处置 (MUST) | （未独立 TC） | **UT only** | `ClassifiedModelFailureRailTest` init | SIT 不构造（Rail 存在于 probe-agent 装配即证明初始化通过） |
| ModelBackupRail presence yield + maxRetries>0 gate | §1.2.11 主循环护栏按类处置 (MUST) | （未独立 TC） | **UT only** | `ClassifiedModelFailureRailTest` presence yield | SIT 不构造 |
| ModelCallRetrier 唯一重试循环所有者 | §1.2.6 非流式自动重试 (MUST) | `FEAT-064.retrier-executes-retry-budget`（S7 耗尽 + S8 恢复） | runnable | `ModelCallRetrierTest` | TC-RATE-LIMITED-PERSISTENT / TC-RATE-LIMITED-RECOVER |
| OpenAIRetryingHttpClient 下线（代码层） | §1.2.6 非流式自动重试 (MUST，实现迁移) | （未独立 TC） | **UT only** | compilation 自然证明 | SIT 不构造"类消失"反证 TC |
| 流式重试首段分界（FirstSegmentRetryIterator，首段**后**不整次重试） | §1.2.7 流式重试判定线 (MUST) 首段**后**分支 | `FEAT-064.first-segment-boundary`（S13，承接 L1 §8.3 ST-S03） | runnable | `FirstSegmentRetryIteratorTest` | TC-STREAM-ERROR-FRAME（§3.7）：首段后无重试日志；r5 订正 ST 编号 S02→**S03**（首段**后**错误帧契约） |
| retryOnRateLimit / retryOnTemporary 开关面 | §1.2.9 重试配置语义对齐 (MUST) | `FEAT-064.rate-limited-switch-off`（s14）/ `.temporary-switch-off`（s15） | runnable | `ModelCallRetrierTest` + `ModelClientConfigTest` | TC-RATE-LIMITED-SWITCH-OFF / TC-TEMPORARY-SWITCH-OFF（开发侧 2026-10-26 ack，走 probe-agent `demo.timeout-probe.*` 命名空间） |
| default maxRetries 3→2（外部可观察行为变化） | §1.2.9 重试配置语义对齐 (MUST) | `FEAT-064.default-max-retries-is-two`（S11） | runnable | `ModelClientConfigTest` default 测 | TC-TEMPORARY-EXHAUSTED 的"重试恰 2 次"既证重试路径也证默认值 |
| maxRetries 负值 setter 拒绝 | §1.2.9 重试配置语义对齐 (MUST，校验口径) | `FEAT-064.max-retries-negative-rejected`（增量，demo 无直接场景） | runnable | `ModelClientConfigTest` validateMaxRetries | TC-MAX-RETRIES-NEGATIVE-REJECTED |
| maxRetries=0 预算关断 wire-observable | §1.2.9 重试配置语义对齐 (MUST，配置消费 wire-observable 后果) | `FEAT-064.max-retries-zero-no-retry`（s16） | runnable | `ModelCallRetrierTest` maxRetries=0 分支 | TC-MAX-RETRIES-ZERO-NO-RETRY（开发侧 2026-10-26 归属校正：从 §1.2.11 Rail 装配事实剥到 §1.2.9 wire-observable 后果） |
| 四新错误码 181009/181010/181011/181012 | §1.2.3 标准错误码映射 (MUST) | `FEAT-064.status-code-contract`（S7/S9/S10/S12） | runnable | `StatusCode` / `StatusMapping` 单测 | 由分类 TC 一一对应覆盖 |
| 181008 timeout 重归 ModelError + numericCode 聚合口径 | §1.2.3 标准错误码映射 (MUST) + §1.2.2 失败表面分类化 (MUST) | `FEAT-064.numeric-code-field`（聚合 S7/S9/S10/S11/S12） | runnable | `StatusMappingTest` + `BaseError.details` 单测 | TC-NUMERIC-CODE-FIELD 跨 TC 聚合（r3 重命名，原 TC-DETAILS-CATEGORY-FIELD）；FEAT-058 的 181008 timeout 本档不覆盖（归 FEAT-058） |
| Rails.isFromOperation gate（AgentInterrupt/AbortError 透传收窄） | §1.2.10 中断类异常识别放行 (MUST) | （未独立 TC） | **UT only** | `RailsAgentInterruptTest` | SIT 不构造 |
| USER_CANCELLED 三形态（C1-C5 cancellation 规则） | §1.2.8 用户取消不重试 (MUST) + §1.2.14 取消识别规则显式定义 (MUST) | TC-USER-CANCELLED-\*（S14/S15 reserved） | **deferred** | UT-C10 / UT-R06 + 容器 E2E | 待 FEAT-058 MUST#11 cancel 可观察出口裁定 |
| ModelCircuitBreaker.onFailure 消费 classification（分类不计熔断） | §1.2.16 熔断计数消费分类 (**SHOULD**) | `FEAT-064.classified-failure-not-counted` | runnable（**r4 升级**，详见 §4 保留项） | `ModelCircuitBreakerTest` onFailure | TC-CLASSIFIED-FAILURE-NOT-COUNTED：平台层已提供 `Model.getCircuitBreakerSnapshot()` 公开只读快照（isOpen + consecutiveFailures 二态），SIT 可按正-反对照组方式构造；SHOULD 等级，不阻塞合入 |
| ModelRetryEvent 签名新增 category 字段 | 无独立 §1.2 条目（L2 §2.4 实现细节） | （未独立 TC） | **UT only** | `ModelRetryEventTest` | SIT 不读事件总线 |
| 跨 provider 分类差异（OpenAI vs DashScope schema，挂点覆盖） | §1.2.1 统一错误分类器 (**MUST**，挂点覆盖要素) | `FEAT-064.cross-provider-classification`（s17） | runnable | `RuleBasedModelErrorClassifierTest` provider 分支 | TC-CROSS-PROVIDER-CLASSIFICATION：用 DashScope schema × AUTH_FAILED (401) 作代表性场景；R6 状态码直判天然 schema-agnostic；R7 关键词命中由 UT 承接（开发侧 2026-10-26 ack） |
| 成功路径零干扰（stub CI 补充 / 回归锚点） | §1.2.1 统一错误分类器 (MUST) + §1.2.6 非流式自动重试 (MUST) 的"不干扰" negative branch | `FEAT-064.success-no-interference` | runnable | — | TC-SUCCESS-NO-INTERFERENCE（§3.14）：stub `ok` → HTTP 200 + answer + 零分类错误码 + 零 retry + stub==1；**r5 定位订正**：不认领 L1 §8.3 ST-R01（后者归 §3.16 真实端点 happy path），本 TC 仅作 stub CI 回归锚点 |
| 流式首段**前**错误 → 整次重试（L1 §8.3 ST-S01） | §1.2.7 流式重试判定线 (MUST) 首段**前**分支 | `FEAT-064.stream-recover-before-first` | runnable | `FirstSegmentRetryIteratorTest` first-segment-before 分支 | TC-STREAM-RECOVER-BEFORE-FIRST（§3.15）：stub `rate-limited-recover` + `stream:true` body；首段尚未到达时整次重试合法；与 §3.7（首段**后**不重试）互补 |
| 真实端点成功路径（L1 §8.3 **ST-R01**） | §1.2.1 统一错误分类器 (MUST，真实端点抽样 happy path) | `FEAT-064.real-endpoint-ok` | runnable | — | TC-REAL-ENDPOINT-OK（§3.16）：**r5 ST 订正** C01→R01；`@Tag("real-endpoint")` + `assumeTrue(LLM_API_BASE 等 env)` |
| 非流式一次恢复（L1 §8.3 **ST-R02**） | §1.2.6 非流式自动重试 (MUST) 的"重试一次即恢复"正向路径 | `FEAT-064.non-stream-rate-limited-recover` | **runnable（r5 新增）** | `ModelCallRetrierTest` recover 分支 | TC-NON-STREAM-RATE-LIMITED-RECOVER（§3.18）：stub `rate-limited-recover`，**不带** `stream:true`（blocking 单 JSON 路径）；与 §3.15 流式版互补，证明 Retrier 对 blocking 路径同样一次 recover |
| 真实端点认证失败（L1 §8.3 **ST-C01**） | §1.2.1 统一错误分类器 (MUST，真实端点抽样 bad-key) + §1.2.3 标准错误码映射 (MUST) | `FEAT-064.real-endpoint-auth-failed` | **partial** | — | TC-REAL-ENDPOINT-AUTH-FAILED（§3.17）：**r5 ST 订正** C02→C01；T3 category 字段待平台层通道开放，仅断 T1 numericCode==181011 |
| 真实端点上下文超窗（L1 §8.3 **ST-C02**） | §1.2.1 统一错误分类器 (MUST，真实端点抽样 context-length) + §1.2.3 标准错误码映射 (MUST) | `FEAT-064.real-endpoint-context-length` | **partial（r5 新增）** | — | TC-REAL-ENDPOINT-CONTEXT-LENGTH（§3.19）：构造超长 prompt 触发真实服务商 `context_length_exceeded`；T1 strong-eq numericCode==181012；T3 category 待通道开放 |
| 流式首段**后**可重试错误不整次重试（L1 §8.3 **ST-S02**） | §1.2.7 流式重试判定线 (MUST) 首段**后**分支 的 "`rate_limited` 等语义" 子分支 | `FEAT-064.stream-rate-limited-first-segment-after` | **runnable（r5 新增）** | `FirstSegmentRetryIteratorTest` first-segment-after 分支 | TC-STREAM-RATE-LIMITED-FIRST-SEGMENT-AFTER（§3.20）：demo S21 stub mode `stream-rate-limited-first-segment-after`（首段正常后 429 语义关闭）；判据同 §3.7（零 retry + stub==1） |
| 跨 provider 两族（L1 §8.3 **ST-O01**，OpenAI × **SiliconFlow**，LLM_PROVIDER 切换） | §1.2.1 统一错误分类器 (MUST) 的 "挂点覆盖多族" 要素 | `FEAT-064.cross-family-openai-siliconflow` | **runnable（r5 新增；r6 订正 DeepSeek→SiliconFlow）** | `RuleBasedModelErrorClassifierTest` provider 分支 | TC-CROSS-FAMILY-OPENAI-VS-SILICONFLOW（§3.21）：probe env `LLM_PROVIDER=OpenAI` vs `LLM_PROVIDER=SiliconFlow` 两跑；**r6 订正**：DeepSeek 反射代理到 OpenAIModelClient 不独立，SiliconFlow 自带 Retrier/SseIterator/handlers 才是真二族；llm-stub-server `auth-failed` mode 可同时服务两族 OpenAI 兼容 wire；判据 strong-eq numericCode==181011 两族齐 |
| 流式首段**后**纯 disconnect 无 payload（L1 §8.3 **ST-S02** 真型，**r6 新增**） | §1.2.7 流式重试判定线 (MUST) 首段**后**分支 的 "纯 TCP close 无 wire payload" 子分支 | `FEAT-064.stream-disconnect-first-segment-after-strict` | **runnable（r6 新增）** | `FirstSegmentRetryIteratorTest` first-segment-after + disconnect 分支 | TC-STREAM-DISCONNECT-FIRST-SEGMENT-AFTER-STRICT（§3.22）：stub mode `stream-disconnect-first-segment-after`（首段正常 chunk 后 TCP close 无任何 payload）；strict pin numericCode==181001 唯一 + T3 `category=TEMPORARY, reason=NOT_RETRYABLE` + stub==1 |

### 非范围显式列出

- **USER_CANCELLED 分类（L2 §1.2.8 用户取消不重试 + §1.2.14 取消识别规则显式定义 的 C1-C5）**：L1 本身在 UT 层已 ✅；SIT 覆盖已由 FEAT-058 §3.12/§3.13 TC-CANCEL-OBS-S18/S19 承接（跨 FEAT 双 Tag `@Tag("feat-058") @Tag("feat-064")`），不阻塞 FEAT-064 特性合入。
- **R1-R12 规则表细节**：UT 承接；SIT 仅在 wire 层证明 "stub 发出某类错误 → SUT 分类正确"，不枚举规则。
- **ModelCircuitBreaker 熔断阈值 / 滑窗 / half-open**：FEAT-008 / FEAT-057 承接；本方案仅覆盖 "分类不计熔断" 的 pass-through 事实（TC-CLASSIFIED-FAILURE-NOT-COUNTED，L2 §1.2.16，走 `Model.getCircuitBreakerSnapshot()` 公开读路径，详见 §4 保留项）。
- **L1 §8.3 ST-O02 "两族 real-endpoint 跨族抽样"**：ST-O01（§3.21）走 LLM_PROVIDER 切换 + stub `auth-failed` 已覆盖"两族挂点均能分类"；ST-O02 要求两族**真实端点**跨族抽样抽真实 LLM 调用，需 OpenAI + **SiliconFlow**（r6 订正，非 DeepSeek）两套真实凭据并行配置，不入 r6 TC 骨架，归 §4 跟踪项。

---

## 1.4 判据边界 — 可观测通道约束（L2 §2.5 现状保留）

**关键事实**：T1 结构化错误码主通道由 agent-core-java PR!242 + agent-runtime-java PR!449（2026-10-27 landed）落地到 `/v1/query` SSE 事件 `payload.failure.{code,numericCode,isRetryable}` 字段上；L2 §2.5 同口径定义了 A2A Task `message.metadata["openjiuwen.error"].{code,numericCode,retryable}` 为**同形字段**，仅承载通道不同（本档 SIT 侧走 `/v1/query` SSE，不改走 A2A entry）。SIT 侧判据必须按以下三通道落地，**不得断言** HTTP body 内老的 `details.category` / `details.status_code` 字段（这两个字段在 wire 层结构性不存在）。

| # | 通道 | 载体 | 覆盖的分类口径 | 固化依据 |
|---|------|------|---------------|---------|
| **T1** | `/v1/query` SSE 事件 `data: {"type":"error", "error":"...", "failure":{...}}` 内的 `failure.{code,numericCode,isRetryable}` 三字段 | 新 wire schema（PR!242+PR!449 2026-10-27 landed）；同口径 A2A Task `message.metadata["openjiuwen.error"]` 由 FEAT-053 metadata schema 承接 | 任何类（五码一一映射）| L2 §2.5；agent-core-java PR!242；agent-runtime-java PR!449 |
| **T2** | SUT retry 日志 `Model call will retry.` | 日志行结构化携带 `category=<CAT>` 字段 | **可重试类**（RATE_LIMITED / TEMPORARY）| L2 §3.10「每次重试」；`ModelCallRetrier.publishRetry` |
| **T3** | SUT terminal 日志 `Model call failed.` | 日志行结构化携带 `category=<CAT>` 字段 | **永久类**（QUOTA/AUTH/CTX_LEN）+ 可重试类**预算耗尽** | L1 §5.1.7「每次模型调用失败」要求平台层结构化终态日志落地后启用 |

### 1.4.1 判据组合规则

| 场景类别 | 主通道（必断） | 辅通道（必断） | 禁出 |
|---------|-------------|-------------|------|
| 可重试类重试中 → 耗尽（S7/S11/S13） | T1 `failure.numericCode` 匹配 | T2 retry 行计数 strong-eq `maxRetries×(railRetries+1) = 4`（L2 §2.3 双层事实：Retrier 内层 `maxRetries=2` × Rail 整轮重试一次） | T2 count ≠ 4 |
| 可重试类重试中 → 恢复（S8 / ST-S01 / ST-R02） | T1 不出错（HTTP 200）| T2 retry 行计数 strong-eq 1 | T2 count ≠ 1 |
| 永久类直接短路（S9/S10/S12/ST-C01/ST-C02）| T1 `failure.numericCode` 匹配 | **当前（partial）**：T2 零 retry 行；**T3 平台层通道开放后**：T3 terminal 行 `category=<对应 CAT>` | T2 任何 retry 行 |
| 可重试类 + 开关 OFF（s14/s15） | T1 `failure.numericCode` 匹配 | T2 零 retry 行 **且** stub 调用次数 strong-eq 2（Retrier 关短路 + Rail `DEFAULT_MAX_GUARDRAIL_RETRIES=1` 不消费开关仍整轮一次）| T2 ≥1 retry 行 / stub ≠ 2 |
| maxRetries=0 两层全关（s16） | T1 `failure.numericCode` 匹配 | T2 零 retry 行 **且** stub 调用次数 strong-eq 1（两层都通过 gate `maxRetries>0` 关断）| stub ≠ 1 |
| 成功路径零干扰（ST-R01 / 本档 §3.14 stub CI 补充）| T1 不出错（HTTP 200 + `result_type=answer` + output 非空）| T2 零 retry 行 **且** 零分类错误码 numericCode 出现 **且** stub 调用次数 strong-eq 1 | 任何分类错误码出现 / 任何 retry 行 / stub ≠ 1 |
| 跨族挂点（ST-O01，OpenAI × DeepSeek）| 两族各自 T1 `failure.numericCode` 均匹配 | 两族各自 T2 零 retry 行 + stub 调用次数 strong-eq 1 | 任一族偏离即 FAIL |

### 1.4.2 为什么 T3 需要等平台层通道落地

L1 §5.1.7 要求「每次模型调用失败」在日志稳定呈现分类结果（含永久类），L2 §3.10 实现只覆盖「每次重试」事件。两者是规格缺口（L1↔L2），永久类当前**只有** `OpenAI API async invoke error.` 行 + `HTTP <code>:` 文本片段，**无** 结构化 `category=X` 字段。SIT 侧 `assertTerminalLogCategory` helper 首版 throw `UnsupportedOperationException`，等平台层结构化终态日志通道落地后解禁；在此之前永久类 TC 的 T3 判据暂 defer，仅断 T1 numericCode。

---

## 1.5 双层重试与 stub 调用次数判据（L2 §2.3 两层事实）

**关键事实**：FEAT-064 真实承载是 **ModelCallRetrier**（可重试类 inner retry）+ **ClassifiedModelFailureRail**（Rail guardrail retry）两层。单纯观察 retry 日志行会在部分场景产生**假阴性**，判据必须补 stub 侧 request-count 维度。

### 1.5.1 两层的消费口径差异（源：`ClassifiedModelFailureRail` + `ModelCallRetrier` + L2 §2.3）

| 层 | 消费的配置 | 不消费的配置 | 请求发起方 |
|---|---------|------------|---------|
| ModelCallRetrier (inner) | `maxRetries` + `retryOnRateLimit` + `retryOnTemporary`（三项全消费）| — | 自己发 HTTP |
| ClassifiedModelFailureRail (outer guardrail) | `maxRetries>0` 作 gate（仅 presence 判定）| `retryOnRateLimit` + `retryOnTemporary`（**不消费**，这是 L2 §2.3 既定事实）| 通过 `context.requestRetry(delay)` 请求 agent 主循环**重调** model |

### 1.5.2 场景化 stub 调用次数预期（**r5 strong-eq 口径锁定**）

> **r5 判据重锁**：按 L2 §2.3 原文「全程调用上限 = (maxRetries+1)×(护栏重试次数+1) = 3×2 = 6」，§3.1/§3.5 持续可重试类 strong-eq `stub=6 / retry 日志=4`（r4 区间 `[3,6]/[2,4]` 作废）；§3.10/§3.11 开关 OFF strong-eq `stub=2`（Retrier 关短路 1 次 + Rail `DEFAULT_MAX_GUARDRAIL_RETRIES=1` 不消费开关仍整轮一次再 1 次；r4 区间 `[1,2]` 作废）；§3.12 maxRetries=0 strong-eq `stub=1`（两层通过 `maxRetries>0` gate 全关）；永久类 strong-eq `stub=1`。原 "待开发裁定" 段废弃，`assertStubCallCountBetween` fixture 弃用（见 §2.2 Fixture 清单）。

| 场景 | Retrier 消费 | Rail 消费 | stub 实际收到次数（r5 strong-eq）| 对应 TC |
|------|---------|---------|-----------------|---------|
| 默认 profile + 可重试类耗尽 | 2 次重试 | Rail 整轮重试 1 次（Retrier 耗尽后 Rail requestRetry 再触发整轮 Retrier）| **6**（`(maxRetries+1)×(railRetries+1) = 3×2`）| TC-RATE-LIMITED-PERSISTENT / TC-TEMPORARY-EXHAUSTED |
| 默认 profile + 可重试类恢复 | 1 次重试即成功 | 不触发（成功路径）| **2**（1 首发 + 1 Retrier 重试，Rail 不触发）| TC-RATE-LIMITED-RECOVER / TC-STREAM-RECOVER-BEFORE-FIRST / TC-NON-STREAM-RATE-LIMITED-RECOVER |
| 默认 profile + 永久类 | 0（永久类短路）| 0（Rail 对永久类 return）| **1** | TC-QUOTA-EXCEEDED / TC-AUTH-FAILED / TC-CONTEXT-LENGTH-EXCEEDED / TC-REAL-ENDPOINT-AUTH-FAILED / TC-REAL-ENDPOINT-CONTEXT-LENGTH |
| 开关 OFF (s14/s15) + `maxRetries>0` | 0（开关关 Retrier 内层短路）| Rail 不消费开关仍整轮一次（`DEFAULT_MAX_GUARDRAIL_RETRIES=1` 独立消费 `maxRetries>0` gate）| **2**（1 首发 Retrier 短路 + 1 Rail requestRetry 整轮）| TC-RATE-LIMITED-SWITCH-OFF / TC-TEMPORARY-SWITCH-OFF |
| maxRetries=0 (s16) | 0（预算关断）| 0（Rail gate `maxRetries>0` 不通过）| **1** | TC-MAX-RETRIES-ZERO-NO-RETRY |
| 成功路径（stub `ok` 或真实端点 200）| 0（无需重试）| 不触发 | **1** | TC-SUCCESS-NO-INTERFERENCE / TC-REAL-ENDPOINT-OK / TC-CROSS-FAMILY-OPENAI-VS-SILICONFLOW |

### 1.5.3 为什么 "retry 日志零条" 单条判据可能假阴性

Rail 若走 `context.requestRetry(delay)` 让 agent 主循环重调 model，**不经过** `ModelCallRetrier.publishRetry()`，所以不出 `Model call will retry.` 行。开关 OFF 场景（s14/s15）如果只断 "retry 日志零条"，看起来通过，但 Rail 不消费开关仍走 requestRetry，stub 实际会被打 2 次（偷跑）。因此 s14/s15/s16 **必须** 用 `assertStubCallCount` 作为主判据之一，retry 日志零条作辅判据。r5 已锁定 strong-eq：s14/s15 `stub=2`、s16 `stub=1`，不再走区间判据。

### 1.5.4 `assertStubCallCount` 的通道

依赖 llm-stub-server 开放 request-count 可观测接口（`GET /__stats` + `POST /__stats/reset`，demo 侧已落地），SIT 侧 asserter 封装 `assertStubCallCount(stubMode, int expected)` strong-equality helper。r5 口径下所有场景均 strong-eq 可断，不再提供 `assertStubCallCountBetween` 区间 helper（r4 曾引入，r5 弃用）。

---

## 2. 承载载体与 Fixture

### 2.1 载体选型

| 载体 | 对应 demo 产物 | 职责 |
|---|---|---|
| SUT (probe-agent) | `timeout-probe-agent-0.1.0.jar`（端口 18092） | 与 FEAT-058 共用载体；装配 agent-core-java `ModelCallRetrier` + `ClassifiedModelFailureRail` + `RuleBasedModelErrorClassifier` + `FirstSegmentRetryIterator`；**FEAT-064 TC 不覆写三层超时，使用默认 profile**（避免分类混淆） |
| 对端 (llm-stub) | `llm-stub-server-0.1.0.jar`（端口 18094） | 与 FEAT-058 共用；追加 7 种 FEAT-064 错误注入模式：`rate-limited-persistent` / `rate-limited-recover` / `quota-exceeded` / `auth-failed` / `server-5xx` / `context-length-exceeded` / `stream-error-frame`；由 `LLM_STUB_DEFAULT_MODE` env 切换 |

### 2.2 Fixture 落点（**与 FEAT-058 共用**，asserter 侧扩展 FEAT-064 分类断言方法）

| Fixture | 职责 | 位置建议（SIT 仓） |
|---|---|---|
| `TimeoutDemoLauncher` | 启停两个 fat jar；端口 ready probe；FEAT-064 TC 注入 `LLM_STUB_DEFAULT_MODE` + **不传** `--spring.profiles.active`（默认 profile）；**r5 新增**：probe-agent env map 允许 TC 侧覆写 `LLM_PROVIDER`（默认 `OpenAI`，§3.21 ST-O01 两族切换到 `DeepSeek` 时覆写） | `src/test/java/com/huawei/ascend/sit/fixture/.../TimeoutDemoLauncher.java`（FEAT-058 已设计，复用） |
| `TimeoutProbeHttpClient` | 底层 `java.net.http.HttpClient` 直发 `POST /v1/query`，body `{"conversation_id":"sit-<uuid>","message":"<prompt>"}`（**r5 新增** `query(prompt, timeout, stream=true)` 重载以支持 §3.15/§3.20 流式 TC）；解析 /v1/query SSE 事件流（含 `data:` 行拼接 + `type=error` 事件内 `failure.{code,numericCode,isRetryable}` 字段）；返回 `QueryResponse` 暴露 `resultType()` / `output()` / `errorStatusCode()`（即 `failure.numericCode`）/ `errorCode()`（即 `failure.code` 枚举字串）/ `category()`（经 `mapFailureCodeToCategory` 从 `failure.code` 映射） | `src/test/java/.../TimeoutProbeHttpClient.java`（FEAT-058 已设计，**r5 扩展**） |
| `SutLogTailer` | tail probe-agent 日志，解析 `[181009/181010/181011/181012/181001] ... category=<CAT> ...` 行；API：`countOfRetryLog()` 匹配 `"Model call will retry."` 行、`countOfStatusCode(int)` 匹配各错误码、`categoryOfLastError()` 从日志行解析 category 字段 | `src/test/java/.../SutLogTailer.java`（FEAT-058 已设计，**本档扩展**） |
| `ErrorEnvelopeAsserter` | 对 SSE `failure` 事件做结构化断言；API：`assertStatusCode(int)` 覆盖 181009/181010/181011/181012/181001；`assertCategory(ModelErrorCategory)` 读 `failure.code` 经 fixture 映射到 category 字串（**非** 老的 HTTP body `details.category` 字段，老路径 r2 已 deprecate，r5 彻底清理）；`assertRetryLogCount(SutLogTailer, int)` / `assertNoRetryLog(SutLogTailer)`；`assertStubCallCount(StubStatsClient, String, int)` strong-eq。**r5 清理**：移除 `errorDetails()` 老路径的 category 返回残留；移除 `assertStubCallCountBetween` 区间 helper（§1.5.4 已 strong-eq 全锁） | `src/test/java/.../ErrorEnvelopeAsserter.java`（FEAT-058 已设计，**r5 清理 + 扩展**） |
| `StubStatsClient` | 对接 llm-stub-server `GET /__stats` + `POST /__stats/reset`，提供 `countOf(stubMode)` + `reset()` | `src/test/java/.../StubStatsClient.java`（r4 新增） |

### 2.3 Stub 模式到 TC 映射（**SIT 复用 demo stub 模式，不新造**）

| TC | demo stub mode | probe profile | 关键配置 |
|---|---|---|---|
| TC-RATE-LIMITED-PERSISTENT | `rate-limited-persistent` | 默认 | `maxRetries` 默认 2 |
| TC-RATE-LIMITED-RECOVER | `rate-limited-recover` | 默认 | `maxRetries` 默认 2；`LLM_STUB_RATE_LIMITED_RECOVER_AFTER=1` |
| TC-QUOTA-EXCEEDED | `quota-exceeded` | 默认 | `maxRetries` 默认 2 |
| TC-AUTH-FAILED | `auth-failed` | 默认 | `maxRetries` 默认 2 |
| TC-TEMPORARY-EXHAUSTED | `server-5xx` | 默认 | **不显式配置 `maxRetries`**（裸跑 SDK 默认 2） |
| TC-CONTEXT-LENGTH-EXCEEDED | `context-length-exceeded` | 默认 | `maxRetries` 默认 2 |
| TC-STREAM-ERROR-FRAME | `stream-error-frame` | 默认 | `maxRetries` 默认 2 |
| TC-MAX-RETRIES-NEGATIVE-REJECTED | n/a（不启动 stub） | **临时 yaml** `max-retries: -1`（SIT 侧 `application-sit-feat064-neg.yml`，**不改 demo 内容**） | 期望 probe-agent 启动失败 |
| TC-NUMERIC-CODE-FIELD | 聚合上述 5 个分类 stub mode | 默认 | 跨 TC 聚合断言（`metadata.numericCode` + T2/T3 日志通道，r3 重命名自 TC-DETAILS-CATEGORY-FIELD）|
| TC-RATE-LIMITED-SWITCH-OFF | `rate-limited-persistent` | **s14** | `demo.timeout-probe.retry-on-rate-limited: false`（demo 新 jar 自带绑定） |
| TC-TEMPORARY-SWITCH-OFF | `server-5xx` | **s15** | `demo.timeout-probe.retry-on-temporary: false`（demo 新 jar 自带绑定） |
| TC-MAX-RETRIES-ZERO-NO-RETRY | `server-5xx` | **s16** | `demo.timeout-probe.max-retries-override: 0`（demo 新 jar 自带绑定） |
| TC-CROSS-PROVIDER-CLASSIFICATION | `auth-failed` | **s17** | stub env `LLM_STUB_RESPONSE_SCHEMA=dashscope`（demo 新 stub jar 自带） |
| TC-SUCCESS-NO-INTERFERENCE | `ok`（stub 正常返回 200 + answer） | 默认 | 无额外配置；证明错误分类/重试栈对成功路径零干扰 |
| TC-STREAM-RECOVER-BEFORE-FIRST | `rate-limited-recover` | 默认 | `LLM_STUB_RATE_LIMITED_RECOVER_AFTER=1`；代表性 "首段**前**可重试错误 → 恢复" 场景（与 §3.7 首段**后**不重试互补） |
| TC-REAL-ENDPOINT-OK (ST-R01) | **不启动 stub** | 默认 | `assumeTrue(LLM_API_BASE / LLM_API_KEY / LLM_MODEL env 非空)`；真实服务商 happy path |
| TC-REAL-ENDPOINT-AUTH-FAILED (ST-C01) | **不启动 stub** | 默认 | 覆写 `LLM_API_KEY` 为无效值；真实服务商 401 → 181011 |
| TC-NON-STREAM-RATE-LIMITED-RECOVER (ST-R02) | `rate-limited-recover` | 默认 | **不带** `stream:true`（blocking 单 JSON 路径）；1 首发 429 + Retrier 1 次重试 200 恢复 |
| TC-REAL-ENDPOINT-CONTEXT-LENGTH (ST-C02) | **不启动 stub** | 默认 | 构造超长 prompt（≥ 200K 字符）触发真实服务商 `context_length_exceeded` → 181012 |
| TC-STREAM-RATE-LIMITED-FIRST-SEGMENT-AFTER (ST-S02) | `stream-rate-limited-first-segment-after`（demo S21）| 默认 | `stream:true` body；首段正常 chunk 后 429 关闭连接；首段分界合同 "首段**后** 429 不整次重试" 分支 |
| TC-CROSS-FAMILY-OPENAI-VS-SILICONFLOW (ST-O01) | `auth-failed` | 默认 | **两跑**：第 1 跑 `LLM_PROVIDER=OpenAI`、第 2 跑 `LLM_PROVIDER=SiliconFlow`（**r6 订正**：SiliconFlow 自带独立 Retrier/SseIterator/handlers 栈才是真二族，DeepSeek 反射代理到 OpenAI 栈不独立）；llm-stub-server `auth-failed` mode OpenAI 兼容 wire 可同时服务两族；两族各自 strong-eq numericCode==181011 + stub==1 |

---

## 3. 用例细档（G/W/T + PASS/FAIL/INCONCLUSIVE）

### 3.1 TC-RATE-LIMITED-PERSISTENT（S7 / 限流持续 / 可重试类耗尽）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile（`maxRetries=2` 默认）；stub-server 启动于 `rate-limited-persistent` 模式（持续返 HTTP 429 + `rate_limit_exceeded` 错误体 + `Retry-After: 1` 响应头） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：`/v1/query` SSE `failure.numericCode == 181009` + `failure.code == MODEL_CALL_RATE_LIMITED`；**T2**：SUT retry 日志 `Model call will retry. {category=RATE_LIMITED, ...}` **strong-eq 4 行**（`maxRetries×(railRetries+1) = 2×2 = 4`，L2 §2.3 双层事实：Retrier 内层 2 次 + Rail 整轮再 2 次）；**stub 调用计数**：`assertStubCallCount("rate-limited-persistent", 6)`（`(maxRetries+1)×(railRetries+1) = 3×2 = 6`）；禁 `[181001/181010/181011/181012/181008]` numericCode |
| PASS | T1 numericCode 181009 + T2 retry 行 strong-eq 4 + stub 次数 strong-eq 6 |
| FAIL | 任一偏离：numericCode 非 181009 / retry 行 category ≠ RATE_LIMITED / retry 次数 ≠ 4 / stub 次数 ≠ 6 / 出现其他分类 numericCode |
| INCONCLUSIVE | probe-agent 或 stub-server 启动失败（端口冲突 / JDK 版本错 / stub 不识别 `rate-limited-persistent` 模式） |

### 3.2 TC-RATE-LIMITED-RECOVER（S8 / 限流一次恢复）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `rate-limited-recover` 模式（首次 429，之后 200；`LLM_STUB_RATE_LIMITED_RECOVER_AFTER=1`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：HTTP 200 + `result_type==answer`（无 `failure` 事件）；**T2**：SUT retry 日志 `Model call will retry. {category=RATE_LIMITED, ...}` **恰 1 次**；**stub 调用计数**：`assertStubCallCount("rate-limited-recover", 2)`（见 §1.5.2 "默认 profile + 可重试类恢复"）；禁出任何 numericCode ∈ {181001/181009/181010/181011/181012/181008} |
| PASS | HTTP 200 + T2 retry 行恰 1 次 + stub 次数 == 2 |
| FAIL | 重试次数 ≠ 1 / 最终失败（出现 numericCode）/ stub 次数 ≠ 2 |
| INCONCLUSIVE | stub 的 recover 计数不生效（二次仍返 429）；重跑一次仍不恢复则 INCONCLUSIVE |

### 3.3 TC-QUOTA-EXCEEDED（S9 / 欠费永久类，**r6 runnable**：T3 通道已开放）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile（`maxRetries=2`）；stub-server 启动于 `quota-exceeded` 模式（HTTP 402 + `insufficient_quota`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：SSE 顶层 `failure.numericCode == 181010` + `failure.code == MODEL_CALL_QUOTA_EXCEEDED`；**T2**：**无** `Model call will retry.` 行（PermanentModelError 短路，次数不消耗）；**T3**：SUT terminal 日志 `Model call failed. {category=QUOTA_EXCEEDED, reason=NOT_RETRYABLE}` **严格 1 行**；**stub 调用计数**：`assertStubCallCount("quota-exceeded", 1)`；禁 `[181001/181009/181011/181012/181008]` numericCode |
| PASS | T1 + T2 零 retry 行 + stub 次数 == 1 + T3 strong-eq 1 行 `category=QUOTA_EXCEEDED, reason=NOT_RETRYABLE` |
| FAIL | 任一偏离：numericCode 非 181010 / 出现 retry 日志（表明 PermanentModelError 未短路）/ stub 次数 ≠ 1 / T3 不符 |
| INCONCLUSIVE | stub 启动失败 |

### 3.4 TC-AUTH-FAILED（S10 / 认证失效永久类，**r6 runnable**：T3 通道已开放）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `auth-failed` 模式（HTTP 401 + `invalid_api_key`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：SSE 顶层 `failure.numericCode == 181011` + `failure.code == MODEL_CALL_AUTH_FAILED`；**T2**：**无** `Model call will retry.` 行；**T3**：SUT terminal 日志 `Model call failed. {category=AUTH_FAILED, reason=NOT_RETRYABLE}` **严格 1 行**；**stub 调用计数**：`assertStubCallCount("auth-failed", 1)`；禁 `[181001/181009/181010/181012/181008]` numericCode |
| PASS | T1 + T2 零 retry 行 + stub 次数 == 1 + T3 strong-eq 1 行 `category=AUTH_FAILED, reason=NOT_RETRYABLE` |
| FAIL | 任一偏离或 T3 不符 |
| INCONCLUSIVE | stub 启动失败 |

### 3.5 TC-TEMPORARY-EXHAUSTED（S11 / 临时故障耗尽 + 证明 default maxRetries=2）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；**不显式配置 `maxRetries`**（裸跑 SDK 默认值）；stub-server 启动于 `server-5xx` 模式（持续 HTTP 500 + `server_error`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：SSE 顶层 `failure.numericCode == 181001` + `failure.code == MODEL_CALL_FAILED`；**T2**：SUT retry 日志 `Model call will retry. {category=TEMPORARY, ...}` **strong-eq 4 行**（Retrier 内层 2 + Rail 整轮再 2，L2 §2.3 双层事实；4 行既证重试路径也证默认 `maxRetries=2`）；**T3**（r7 订正）：SUT terminal 日志 `Model call failed. {category=TEMPORARY, reason=RETRY_BUDGET_EXHAUSTED}` **严格 2 行**（Retrier 内层预算耗尽触发一次 + Rail 整轮再触发一次）；**stub 调用计数**：`assertStubCallCount("server-5xx", 6)`（`(maxRetries+1)×(railRetries+1) = 3×2 = 6`）；禁 `[181008/181009/181010/181011/181012]` numericCode |
| PASS | T1 + T2 retry 行 category=TEMPORARY strong-eq 4 + T3 strong-eq 2 + stub 次数 strong-eq 6 |
| FAIL | 任一偏离：numericCode 非 181001 / category 错位 / 重试次数 ≠ 4（≠2 证明 Retrier 内层层级异常或默认值未切换；≠4 整体证明双层事实破坏）/ stub 次数 ≠ 6 / T3 ≠ 2 |
| INCONCLUSIVE | stub 启动失败 / 日志抓取窗口不够长 |

> **本 TC 的双重作用**：default maxRetries 3→2（L2 §1.2.9）是外部可观察行为变化，但没有直接 getter 可读；通过 "不显式配置 + 观察重试日志行数恰等于 4"（= 2×2，内层 maxRetries=2 × Rail 整轮一次）以**单一 TC 同时证明 TEMPORARY 走重试路径（L2 §1.2.6）+ 默认值已切换（L2 §1.2.9）**。

### 3.6 TC-CONTEXT-LENGTH-EXCEEDED（S12 / 上下文超限永久类，扩展分类，**r6 runnable**：T3 通道已开放）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `context-length-exceeded` 模式（HTTP 400 + `code=context_length_exceeded`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：SSE 顶层 `failure.numericCode == 181012` + `failure.code == MODEL_CONTEXT_LENGTH_EXCEEDED`；**T2**：**无** `Model call will retry.` 行；**T3**：SUT terminal 日志 `Model call failed. {category=CONTEXT_LENGTH_EXCEEDED, reason=NOT_RETRYABLE}` **严格 1 行**；**stub 调用计数**：`assertStubCallCount("context-length-exceeded", 1)`；禁 `[181001/181009/181010/181011/181008]` numericCode |
| PASS | T1 + T2 零 retry 行 + stub 次数 == 1 + T3 strong-eq 1 行 `category=CONTEXT_LENGTH_EXCEEDED, reason=NOT_RETRYABLE` |
| FAIL | 任一偏离或 T3 不符 |
| INCONCLUSIVE | stub 启动失败 |

### 3.7 TC-STREAM-ERROR-FRAME（S13 / 流中错误帧 → 首段**后**不整次重试，**r5 ST 订正** ST-S02→ST-S03）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `stream-error-frame` 模式（SSE 首段正常返回，首段后发 `data: {"error":...}` 帧） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping","stream":true}` |
| Then | **T1**：SSE 顶层 `failure.numericCode == 181001` + `failure.code == MODEL_CALL_FAILED`；**T2**：**无** `Model call will retry.` 行（首段已到达，`FirstSegmentRetryIterator` 不整次重试）；**stub 调用计数**：`assertStubCallCount("stream-error-frame", 1)`；禁 `[181008/181009/181010/181011/181012]` numericCode |
| PASS | T1 numericCode 181001 + T2 零 retry 行（证明首段分界合同生效）+ stub strong-eq 1 |
| FAIL | 出现 `Model call will retry.`（表明首段分界破坏，违反 L2 §1.2.7 流式重试判定线）/ numericCode 非 181001 / stub ≠ 1 |
| INCONCLUSIVE | stub 不发错误帧（流直接完成）/ 流被底层超时截断为 181008 |

> **r5 ST 订正**：本 TC 对应 L1 §8.3 **ST-S03**（r4 曾写 ST-S02，已订正）；ST-S02 的 "首段**后** `rate_limited` 等语义不整次重试" 由新增 §3.20 承接。

### 3.8 TC-MAX-RETRIES-NEGATIVE-REJECTED（增量 / setter 启动期 fail-fast）

| 字段 | 内容 |
|---|---|
| Given | 临时 yaml（SIT 侧 `application-sit-feat064-neg.yml`，**不改 demo 内容**）：`max-retries: -1`；stub 不启动（本 TC 不走 wire） |
| When | 启动 probe-agent fat jar，激活临时 yaml |
| Then | probe-agent 启动失败；错误日志出现 `ModelClientConfig` 的 `setMaxRetries` setter 抛出的 `IllegalArgumentException`（或 Spring Boot 配置绑定失败并包装为 `BindException`）；进程退出码非零 |
| PASS | 启动失败 + 错误日志匹配 validateMaxRetries 校验异常 |
| FAIL | 启动成功（校验被绕过，Rail 用负值 `maxRetries` 跑起来）/ 启动失败但错误原因不是 setter 校验 |
| INCONCLUSIVE | 启动失败但错误日志抓取失败（Spring Boot 吞异常） |

### 3.9 TC-NUMERIC-CODE-FIELD（原 TC-DETAILS-CATEGORY-FIELD / 聚合 5 类 numericCode + category ↔ numericCode 1:1 映射）

> **r3 重命名理由**：原 `TC-DETAILS-CATEGORY-FIELD` 命名假设 HTTP body 内有 `details.category` 字段。按 L2 §2.5「BaseError details 不上浮客户端 (现状保留)」，该字段结构性不存在；聚合判据改为 `/v1/query` SSE `failure.numericCode` 的 5 类齐备 + category ↔ numericCode 的 1:1 映射（映射矩阵固化在 L2 §5.1 R6 规则表）。
>
> **r5 字段路径订正**：T1 主通道从 `metadata.openjiuwen.error.numericCode` 订正为 SSE 事件 `failure.numericCode`（PR!242+PR!449 2026-10-27 落地）；同口径 A2A Task `message.metadata["openjiuwen.error"]` 由 FEAT-053 承接，本档不改走 A2A entry。

| 字段 | 内容 |
|---|---|
| Given | 已跑完 TC-RATE-LIMITED-PERSISTENT + TC-QUOTA-EXCEEDED + TC-AUTH-FAILED + TC-TEMPORARY-EXHAUSTED + TC-CONTEXT-LENGTH-EXCEEDED 五条 trace（覆盖 5 类分类） |
| When | 聚合五条 trace 的 `/v1/query` SSE `failure.numericCode` 字段 + SUT 日志通道（T2 或 T3）内的 `category=X` 字段 |
| Then | 每条 trace 的 `failure.numericCode` 字段**必填齐备**；取值 ∈ {181009, 181010, 181011, 181001, 181012}；每条 trace 的 T2 或 T3 日志行内 `category` 字段取值 ∈ {RATE_LIMITED, QUOTA_EXCEEDED, AUTH_FAILED, TEMPORARY, CONTEXT_LENGTH_EXCEEDED}；category ↔ numericCode 1:1 对应（RATE_LIMITED↔181009 / QUOTA_EXCEEDED↔181010 / AUTH_FAILED↔181011 / TEMPORARY↔181001 / CONTEXT_LENGTH_EXCEEDED↔181012）；程序方可按 `failure.numericCode` 做分支判定 |
| PASS | 五 trace 均满足 numericCode 齐备 + 日志 category 齐备 + 1:1 对应 |
| FAIL | 任一 trace 缺 numericCode / 日志 category 字段缺失（可重试类走 T2 无、永久类待 T3 通道开放）/ 1:1 映射错位 |
| INCONCLUSIVE | 任一上游 TC 自己是 INCONCLUSIVE（本 TC 相应降级）；永久类三条（181010/181011/181012）的 category 字段部分在 T3 通道开放前仅能验 numericCode，整体结论降为 PARTIAL |

### 3.10 TC-RATE-LIMITED-SWITCH-OFF（增量 s14 / 限流开关 OFF 分支，L2 §1.2.9）

> **r5 strong-eq 口径**：按 §1.5「双层重试与 stub 调用次数判据」，`ClassifiedModelFailureRail` **不消费** `retryOnRateLimit` 开关（L2 §2.3 既定事实），仅检查 `maxRetries>0` 作 presence gate；Rail `DEFAULT_MAX_GUARDRAIL_RETRIES=1` 整轮一次。因此开关 OFF 场景 strong-eq `stub=2`（1 首发 Retrier 内层开关 OFF 短路 + 1 Rail requestRetry 整轮）。r4 待开发裁定点 + 区间判据作废。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 **s14 profile**（`demo.timeout-probe.retry-on-rate-limited: false`，绑定到 `ModelClientConfig.Builder.retryOnRateLimit(false)`）；stub-server 启动于 `rate-limited-persistent` 模式 |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：SSE `failure.numericCode == 181009` + `failure.code == MODEL_CALL_RATE_LIMITED`；**T2**：`Model call will retry.` **零次**（Retrier 开关 OFF 绕过内层重试循环；Rail requestRetry 不走 publishRetry）；**T3**（r7-fix 追加）：SUT terminal 日志 `Model call failed. {category=RATE_LIMITED, reason=POLICY_DISALLOWED}` **strong-eq 2 行**（Retrier 内层 policy.allows=false 1 次 + Rail 整轮再触发 Retrier 1 次，Rail 不消费 `retry-on-rate-limited` 开关仅检查 `maxRetries>0` + `DEFAULT_MAX_GUARDRAIL_RETRIES=1`）；**stub 调用计数**：`assertStubCallCount("rate-limited-persistent", 2)`；禁 `[181001/181010/181011/181012]` numericCode |
| PASS | T1 numericCode 181009 + T2 零 retry 行 + T3 strong-eq 2 + stub strong-eq 2 |
| FAIL | 出现 retry 日志（证明 Retrier 开关 OFF 未生效，L2 §1.2.9 FAIL）/ numericCode 非 181009 / T3 category 或 reason 错位或行数 ≠ 2 / stub ≠ 2 |
| INCONCLUSIVE | s14 profile 未被 probe-agent 识别（载体层 `demo.timeout-probe.*` 绑定未到位） |

### 3.11 TC-TEMPORARY-SWITCH-OFF（增量 s15 / 临时故障开关 OFF 分支，L2 §1.2.9）

> **r5 strong-eq 口径**：同 §3.10，`retryOnTemporary` 开关也不被 Rail 消费；strong-eq `stub=2`。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 **s15 profile**（`demo.timeout-probe.retry-on-temporary: false`，绑定到 `ModelClientConfig.Builder.retryOnTemporary(false)`）；stub-server 启动于 `server-5xx` 模式 |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：SSE `failure.numericCode == 181001`；**T2**：`Model call will retry.` **零次**；**T3**（r7-fix 追加）：SUT terminal 日志 `Model call failed. {category=TEMPORARY, reason=POLICY_DISALLOWED}` **strong-eq 2 行**（Retrier 内层 policy.allows=false 1 次 + Rail 整轮再触发 Retrier 1 次，Rail 不消费 `retry-on-temporary` 开关仅检查 `maxRetries>0`）；**stub 调用计数**：`assertStubCallCount("server-5xx", 2)`；禁 `[181008/181009/181010/181011/181012]` numericCode |
| PASS | T1 numericCode 181001 + T2 零 retry 行 + T3 strong-eq 2 + stub strong-eq 2 |
| FAIL | 出现 retry 日志（L2 §1.2.9 FAIL）/ numericCode 非 181001 / T3 category 或 reason 错位或行数 ≠ 2 / stub ≠ 2 |
| INCONCLUSIVE | s15 profile 未被识别 |

### 3.12 TC-MAX-RETRIES-ZERO-NO-RETRY（增量 s16 / maxRetries=0 预算关断 wire-observable，L2 §1.2.9）

> **r3 补注**：按 L2 §2.3「maxRetries=0 等价于关闭全部自动重试（客户端包装层与主循环护栏两层同时关闭）」—— Rail gate `maxRetries>0` 不通过，Retrier 预算关断，两层全关。SIT 侧验证 **wire-observable 后果**（stub 调用次数 == 1 + 无重试日志 + 首次失败即终 numericCode），不验证 Rail 装配事实本身（后者 UT 承接）。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 **s16 profile**（`demo.timeout-probe.max-retries-override: 0`，绑定到 `ModelClientConfig.Builder.maxRetries(0)`）；stub-server 启动于 `server-5xx` 模式 |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：SSE `failure.numericCode == 181001` + 日志 `max_retries=0` 字段；**T2**：`Model call will retry.` **零次**（预算关断，首次失败即终 numericCode）；**T3**（r7-fix 追加）：SUT terminal 日志 `Model call failed. {category=TEMPORARY, reason=RETRY_BUDGET_EXHAUSTED}` **strong-eq 1 行**（Retrier 内层 `tryConsumeRetry` 失败 1 次；Rail gate `currentModelAllowsRetry` 查 `maxRetries>0` 不通过 → 不触发整轮，无第二行）；**stub 调用计数**：`assertStubCallCount("server-5xx", 1)`（两层全关）；禁 `[181008/181009/181010/181011/181012]` numericCode |
| PASS | T1 numericCode 181001 + `max_retries=0` 字段 + T2 零 retry 行 + T3 strong-eq 1 + stub 次数 == 1 |
| FAIL | 出现 retry 日志（证明预算关断未生效）/ numericCode 非 181001 / `max_retries` 字段 ≠ 0 / T3 category 或 reason 错位或行数 ≠ 1 / stub 次数 ≠ 1（2 次表明 Rail gate 未阻断，证明装配偏离 L2 §2.3） |
| INCONCLUSIVE | s16 profile 未被识别；或 stub 调用计数通道未开放时降级 |

### 3.13 TC-CROSS-PROVIDER-CLASSIFICATION（增量 s17 / OpenAI 挂点 schema-agnostic R6 验证，L2 §1.2.1）

> **r3 定位修正**（回应评审 C4）：本 TC **不认领** L1 §8.3 ST-O01「两族覆盖」。L2 版本口径明确两族 = OpenAI 族（含 OpenAiCompatible、DashScope chat 反射委托 OpenAIModelClient）× SiliconFlow 族（`BaseModelClient` 直接子类，独立 HTTP/SSE 栈，L2 §4.1 独立改）；本 TC 固定 `LLM_PROVIDER=OpenAI`，仅覆盖 OpenAI 挂点在陌生 schema body 下的 **R6 规则 schema-agnostic 鲁棒性**（R6 按 HTTP status 直判），不触发 SiliconFlowModelClient 代码路径。SiliconFlow 挂点覆盖缺口归 L1 §8.3 ST-O01 跟踪项（见 §4 跟踪表）。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile（固定 `LLM_PROVIDER=OpenAI`）；stub-server 启动于 `auth-failed` mode + env `LLM_STUB_RESPONSE_SCHEMA=dashscope`（HTTP 401 + DashScope 风格响应体 `{"code":"InvalidApiKey","message":"...","request_id":"..."}`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：SSE `failure.numericCode == 181011` + `failure.code == MODEL_CALL_AUTH_FAILED`；**T2**：**无** `Model call will retry.` 行（永久类短路）；**T3**（**平台层通道开放后启用**）：SUT terminal 日志 `Model call failed. {category=AUTH_FAILED, ...}`；**stub 调用计数**：`assertStubCallCount("auth-failed", 1)`；禁 `[181001/181009/181010/181012]` numericCode |
| PASS | T1 numericCode 181011 + T2 零 retry 行 + stub 次数 == 1；T3 待通道开放后转必断 |
| FAIL | OpenAI 挂点吞掉 401 退化为 181001（R6 挂点漏接证据）/ numericCode 非 181011 / 出现 retry 日志（永久类未短路）|
| INCONCLUSIVE | stub schema 开关未被识别（载体层 `LLM_STUB_RESPONSE_SCHEMA` 环境变量未绑定） |

> **覆盖面声明**：本 TC 以 AUTH_FAILED (401) 为代表性场景——R6 规则按状态码直判，天然 schema-agnostic；证明"挂点不会在陌生 schema body 上崩溃或静默吞掉状态码"。更强的 schema-dependent 场景（如 CONTEXT_LENGTH_EXCEEDED 需从错误体关键词 R7 识别）由 UT `RuleBasedModelErrorClassifierTest` provider 分支承接。

### 3.14 TC-SUCCESS-NO-INTERFERENCE（stub CI 补充 / 成功路径零干扰）

> **r5 定位订正**：本 TC **不再认领** L1 §8.3 ST-R01（后者已由 §3.16 真实端点 happy path 承接）；本档保留作 **"stub CI 补充"**——FEAT-064 合同的 negative-branch 承诺（错误分类/重试栈不干扰成功路径）在 stub 上的 CI 可执行回归锚点，与 §3.16 real-endpoint happy path 互补：stub 可无凭据地在 CI 中跑，real-endpoint 需手动 `source ~/.llmrc`。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `ok` 模式（HTTP 200 + 正常 OpenAI chat completion 响应） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：HTTP 200 + `result_type == "answer"` + `output` 非空；**T2**：零 `Model call will retry.` 行；**stub 调用计数**：`assertStubCallCount("ok", 1)`（首次即成功，重试栈不触发）；**禁出**：任何分类 numericCode（181001/181008/181009/181010/181011/181012）在 SSE 事件和日志中都不得出现 |
| PASS | HTTP 200 + answer 非空 + 零 retry 行 + stub 次数 == 1 + 零分类错误码 |
| FAIL | HTTP 非 200 / result_type ≠ answer / output 空 / 出现任何分类 numericCode（证明错误分类栈干扰了成功路径）/ 出现 retry 日志（证明重试栈在无错时误触发）/ stub 次数 ≠ 1 |
| INCONCLUSIVE | stub `ok` 模式未就绪（HANDOFF 冒烟 `ok` 分支 FAIL）/ probe-agent 启动失败 |

### 3.15 TC-STREAM-RECOVER-BEFORE-FIRST（L1 §8.3 ST-S01 / 流式首段**前**可重试错误 → 整次重试恢复）

> **r4 新增**（原 r3 §4 跟踪项 ST-S01 收回）：L1 §8.3 ST-S01 要求验证"流式入口在**首段到达前**遇到可重试错误时，`FirstSegmentRetryIterator` 允许整次重试"。本档以 stub `rate-limited-recover` 模式作代表性场景（首次 429 + Retrier 整次重试 + 二次 200 恢复），与 §3.7 TC-STREAM-ERROR-FRAME（首段**后**错误帧不重试）互补——两者共同覆盖首段分界合同的双侧。
>
> **r5 请求 body 更新**：加 `"stream":true` 并断言 answer 以 SSE `data:` 流式帧返回（证明走的是流式路径而非 blocking 单 JSON 路径）；§3.18（新增 ST-R02）承接 blocking 单 JSON 路径的一次恢复测试。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `rate-limited-recover` 模式（首次 429，之后 200；`LLM_STUB_RATE_LIMITED_RECOVER_AFTER=1`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping","stream":true}` |
| Then | **T1**：HTTP 200 + `result_type == "answer"` + `output` 非空 + 响应为 **SSE 流式帧**（≥ 1 条 `data:` 行，非单条 JSON 整体返回）；**T2**：SUT retry 日志 `Model call will retry. {category=RATE_LIMITED, ...}` **恰 1 次**（首段尚未到达，重试合法）；**stub 调用计数**：`assertStubCallCount("rate-limited-recover", 2)`（1 首发 429 + 1 Retrier 重试 200）；**禁出**：任何分类 numericCode（181001/181008/181009/181010/181011/181012） |
| PASS | HTTP 200 + answer 非空 + SSE 流式帧确认 + T2 retry 行恰 1 次 + category=RATE_LIMITED + stub 次数 == 2 |
| FAIL | HTTP 非 200 / 最终失败（出现 numericCode）/ retry 次数 ≠ 1 / category 错位 / stub 次数 ≠ 2 / 响应非流式帧（走到了 blocking 路径，证明 `stream:true` 未被识别） |
| INCONCLUSIVE | stub recover 计数不生效（二次仍返 429）；重跑一次仍不恢复则 INCONCLUSIVE |

### 3.16 TC-REAL-ENDPOINT-OK（L1 §8.3 **ST-R01** / 真实端点成功路径抽样）

> **r5 ST 订正**：从 r4 的 ST-C01 订正为 **ST-R01**（L1 §8.3 ST 编号中 R-series 是 real-endpoint happy-path/recover 抽样，C-series 是 real-endpoint classification 抽样；happy-path 归 R01 而非 C01）。TC 走 `@Tag("real-endpoint")` + `@Tag("manual")`，通过 `assumeTrue(LLM_API_BASE / LLM_API_KEY / LLM_MODEL env 非空)` 门控凭据——未配置则 SKIP 不 FAIL。配置来源 `~/.llmrc`（见仓侧 memory `reference_llmrc.md`）。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；shell 已 `source ~/.llmrc` 使 `LLM_API_BASE` / `LLM_API_KEY` / `LLM_MODEL` 从真实服务商环境变量注入；**不启动 stub**；corporate proxy 已 unset（内网 LLM 端点避开 HIS 代理 504） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：HTTP 200 + `result_type == "answer"` + `output` 非空；**T2**：零 `Model call will retry.` 行；**禁出**：任何分类 numericCode（181001/181008/181009/181010/181011/181012）；（无 stub 侧通道，不断 stub 调用数） |
| PASS | HTTP 200 + answer 非空 + 零 retry 行 + 零分类错误码 |
| FAIL | HTTP 非 200 / result_type ≠ answer / output 空 / 出现分类错误码（证明真实端点上分类栈误触发）/ 出现 retry 行 |
| INCONCLUSIVE | 任一 `LLM_API_*` env 未设置（SKIP via `assumeTrue`，不计入 FAIL）；或企业代理未清，内网端点返 504 HTML |

### 3.17 TC-REAL-ENDPOINT-AUTH-FAILED（L1 §8.3 **ST-C01** / 真实端点认证失败抽样，**r6 runnable**：T3 通道已开放）

> **r5 ST 订正**：从 r4 的 ST-C02 订正为 **ST-C01**（L1 §8.3 ST 编号中 C-series 是 real-endpoint classification 抽样，C01 是 auth-failed 为 bad-key 抽样代表，C02 是 context-length-exceeded 由新增 §3.19 承接）。TC 使用真实 `LLM_API_BASE` + `LLM_MODEL`，但强制覆写 `LLM_API_KEY` 为无效值 `sk-sit-feat064-intentionally-invalid-key`，期望真实服务商回 HTTP 401 → OpenAI 挂点分类为 AUTH_FAILED → 181011。
>
> **r6 升级**：T3 平台层终态结构化日志通道已 landed（agent-core-java PR!449，2026-10-27），不再 partial；本 TC 转 runnable，T3 strong-eq `category=AUTH_FAILED, reason=NOT_RETRYABLE` 严格 1 行。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；shell 已 `source ~/.llmrc`（提供 `LLM_API_BASE` / `LLM_MODEL`）；**但覆写 `LLM_API_KEY=sk-sit-feat064-intentionally-invalid-key`**（强制触发真实服务商 401）；**不启动 stub**；corporate proxy 已 unset |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **T1**：SSE 顶层 `failure.numericCode == 181011` + `failure.code == MODEL_CALL_AUTH_FAILED`；**T2**：零 `Model call will retry.` 行（永久类短路）；**T3**：SUT terminal 日志 `Model call failed. {category=AUTH_FAILED, reason=NOT_RETRYABLE}` **严格 1 行**；**禁出**：`[181001/181009/181010/181012/181008]` numericCode（证明挂点正确识别真实 401，而非误判为其他类）|
| PASS | T1 numericCode 181011 + T2 零 retry 行 + T3 strong-eq 1 行 `category=AUTH_FAILED, reason=NOT_RETRYABLE` |
| FAIL | numericCode 非 181011（OpenAI 挂点在真实 401 上退化为 181001 / 其他码 = R6 挂点漏接证据）/ 出现 retry 日志（永久类未短路）/ T3 不符 |
| INCONCLUSIVE | 任一 `LLM_API_*` env 未设置（SKIP via `assumeTrue`）；或真实服务商对无效 key 返非 401（如网关 403 / 502）—— 这反映服务商侧而非 FEAT-064 合同问题 |

> **覆盖面声明**（§3.16 + §3.17 + §3.19 合流）：三条 real-endpoint TC 构成 FEAT-064 分类合同在真实端点上的最小闭环——正向抽样（§3.16 ST-R01 happy path）证明分类/重试栈不干扰 200，反向抽样（§3.17 ST-C01 bad-key 401 + §3.19 ST-C02 context-length 400）证明 R6 分类规则 schema-agnostic。更丰富的真实端点场景（真实 429 限流等）由 FEAT-064 发布前的手动抽样承接，不入 testplan 骨架。

### 3.18 TC-NON-STREAM-RATE-LIMITED-RECOVER（L1 §8.3 **ST-R02** / 非流式一次恢复，**r5 新增**，**r6 显式 stream:false**）

> **r5 新增背景**：L1 §8.3 ST-R02 要求验证"非流式入口在遇到可重试错误后一次重试即恢复"。与 §3.15 TC-STREAM-RECOVER-BEFORE-FIRST（流式路径）互补，证明 Retrier 对 **blocking 单 JSON 路径**同样支持一次 recover。承载载体 stub `rate-limited-recover` 已就绪（demo 侧 FEAT-058 S8 共用），无阻塞。
>
> **r6 请求体订正**：原 "不带 stream:true" 的隐式写法改为**显式** `"stream":false`，避免 wire 协议假设漂移时 TC 的 blocking 路径判定失效。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `rate-limited-recover` 模式（首次 429，之后 200；`LLM_STUB_RATE_LIMITED_RECOVER_AFTER=1`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping","stream":false}`（**显式 stream=false**，走 blocking 单 JSON 路径） |
| Then | **T1**：HTTP 200 + `result_type == "answer"` + `output` 非空 + 响应为 **单条 JSON**（非 SSE 流式帧，证明走的是 blocking 路径）；**T2**：SUT retry 日志 `Model call will retry. {category=RATE_LIMITED, ...}` **恰 1 次**；**stub 调用计数**：`assertStubCallCount("rate-limited-recover", 2)`（1 首发 429 + 1 Retrier 重试 200）；**禁出**：任何分类 numericCode |
| PASS | HTTP 200 + answer 非空 + 单条 JSON 响应确认 + T2 retry 行恰 1 次 + category=RATE_LIMITED + stub 次数 == 2 |
| FAIL | HTTP 非 200 / 最终失败 / retry 次数 ≠ 1 / category 错位 / stub 次数 ≠ 2 / 响应为 SSE 流式帧（走到了流式路径，证明 blocking 路径未被识别） |
| INCONCLUSIVE | stub recover 计数不生效 |

### 3.19 TC-REAL-ENDPOINT-CONTEXT-LENGTH（L1 §8.3 **ST-C02** / 真实端点上下文超窗抽样，**r5 新增**，**r6 runnable**）

> **r5 新增背景**：L1 §8.3 ST-C02 要求验证"R6 分类规则对真实服务商 400 context_length_exceeded 响应仍成立"。TC 使用真实 `LLM_API_BASE` + `LLM_API_KEY` + `LLM_MODEL`，构造超长 prompt（≥ 200K 字符）触发真实服务商的 context-length 校验，期望响应为 HTTP 400 + code=`context_length_exceeded` → OpenAI 挂点分类为 CONTEXT_LENGTH_EXCEEDED → 181012。
>
> **r6 升级**：T3 通道已 landed；本 TC 转 runnable，T3 strong-eq `category=CONTEXT_LENGTH_EXCEEDED, reason=NOT_RETRYABLE` 严格 1 行。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；shell 已 `source ~/.llmrc`（提供 `LLM_API_BASE` / `LLM_API_KEY` / `LLM_MODEL`）；**不启动 stub**；corporate proxy 已 unset；构造 prompt = `"ping ".repeat(50000)`（≥ 200K 字符，远超模型 context window） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"<超长 prompt>"}` |
| Then | **T1**：SSE 顶层 `failure.numericCode == 181012` + `failure.code == MODEL_CONTEXT_LENGTH_EXCEEDED`；**T2**：零 `Model call will retry.` 行（永久类短路）；**T3**：SUT terminal 日志 `Model call failed. {category=CONTEXT_LENGTH_EXCEEDED, reason=NOT_RETRYABLE}` **严格 1 行**；**禁出**：`[181001/181009/181010/181011/181008]` numericCode |
| PASS | T1 numericCode 181012 + T2 零 retry 行 + T3 strong-eq 1 行 `category=CONTEXT_LENGTH_EXCEEDED, reason=NOT_RETRYABLE` |
| FAIL | numericCode 非 181012（OpenAI 挂点在真实 context-length 响应上退化 = R6/R7 挂点漏接证据）/ 出现 retry 日志（永久类未短路）/ T3 不符 |
| INCONCLUSIVE | 任一 `LLM_API_*` env 未设置（SKIP via `assumeTrue`）；或真实服务商对超长 prompt 返非 400（如网关 413 / 504）—— 这反映服务商侧而非 FEAT-064 合同问题；或模型实际 context window ≥ 200K，prompt 没触发超窗 |

### 3.20 TC-STREAM-RATE-LIMITED-FIRST-SEGMENT-AFTER（L1 §8.3 **ST-S03** 子型 / 流式首段**后**可重试错误不整次重试，**r5 新增**，**r6 relabel**）

> **r5 新增背景**：L1 §8.3 ST-S02 的 "`rate_limited` 等语义不整次重试" 分支（首段**后**遇可重试错误，`FirstSegmentRetryIterator` 不触发整次重试，与 §3.7 ST-S03 "首段**后**错误帧" 分支互补）。承载载体 demo S21 新增 stub mode `stream-rate-limited-first-segment-after`（首段正常 chunk 后发 429 关闭连接）。
>
> **r6 relabel 说明**：reviewer 指出"真正的 ST-S02 应 strict pin numericCode==181001 unique（stub 纯 disconnect without payload）"。本 TC 的 "accept 181009/181001 二态" 实为 ST-S03 "首段后错误帧" 的**可重试子型**（相对 §3.7 "首段后错误帧" 的永久子型），故 r6 重标为 **ST-S03 子型 (first-segment-after + retryable semantics)**，真正的 ST-S02 strict 版由新增 §3.22 承接。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `stream-rate-limited-first-segment-after` 模式（demo S21：SSE 首段正常 chunk 返回后，连接按 429 等语义关闭） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping","stream":true}` |
| Then | **T1**：SSE 顶层 `failure.numericCode == 181009`（或 `181001` 若 stub 的关闭动作被下游归类为 TEMPORARY——以 stub 实际行为为准，两种编号都可接受只要不整次重试）；**T2**：**无** `Model call will retry.` 行（首段已到达，`FirstSegmentRetryIterator` 不整次重试，L2 §1.2.7 流式重试判定线首段**后**分支）；**stub 调用计数**：`assertStubCallCount("stream-rate-limited-first-segment-after", 1)`（strong-eq 1，证明没有整次重试）；禁 `[181010/181011/181012/181008]` numericCode |
| PASS | T1 numericCode ∈ {181009, 181001} + T2 零 retry 行 + stub strong-eq 1 |
| FAIL | 出现 `Model call will retry.`（首段分界破坏，违反 L2 §1.2.7）/ numericCode ∈ {181010/181011/181012/181008} / stub ≠ 1 |
| INCONCLUSIVE | stub 不发首段（流直接以 429 响应，退化为 ST-S01 场景）/ stub mode S21 未就绪 |

### 3.21 TC-CROSS-FAMILY-OPENAI-VS-SILICONFLOW（L1 §8.3 **ST-O01** / 跨族挂点覆盖，OpenAI × SiliconFlow，LLM_PROVIDER 切换，**r5 新增**，**r6 订正**）

> **r5 新增背景**：L1 §8.3 ST-O01 要求"两族挂点均能分类"。
>
> **r6 订正**：r5 原选 DeepSeek 作为"第二族"是误判。代码层证据表明 `DeepSeekModelClient.openAiDelegate()` 通过反射创建 `OpenAIModelClient` 实例并全权委托（见 `agent-core-java/.../DeepSeekModelClient.java`），**不构成 L2 §4.1 定义的"独立变化挂点栈"**；反而 `SiliconFlowModelClient` 拥有独立 Retrier / `FirstSegmentRetryIterator` / 异常 handler 栈，是真正的第二族挂点。reviewer r5 评论采纳；本 TC 切换为 **OpenAI × SiliconFlow** 两族比对。
>
> **载体层变更**：llm-stub-server 兼容 OpenAI wire，SiliconFlow 栈 wire 协议亦兼容 OpenAI（基于反射证据与 UT `SiliconFlowModelClientTest` 覆盖，SiliconFlow 走 OpenAI 兼容 chat completions 接口）；无需 demo 侧额外改造，仅通过 `LLM_PROVIDER=SiliconFlow` 切换走到独立挂点代码路径。
>
> **r6 升级**：T3 通道已 landed，本 TC 新增 T3 strong-eq `category=AUTH_FAILED, reason=NOT_RETRYABLE` 严格 1 行断言（两跑各断一次）。

| 字段 | 内容 |
|---|---|
| Given | **两跑配对**：第 1 跑 probe-agent env `LLM_PROVIDER=OpenAI`、第 2 跑 `LLM_PROVIDER=SiliconFlow`；两跑均启动 stub-server 于 `auth-failed` 模式（HTTP 401 + `invalid_api_key`）；两跑共用默认 profile |
| When | 两跑各发 SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | **两跑各自**：**T1**：SSE 顶层 `failure.numericCode == 181011` + `failure.code == MODEL_CALL_AUTH_FAILED`；**T2**：零 `Model call will retry.` 行；**T3**：SUT terminal 日志 `Model call failed. {category=AUTH_FAILED, reason=NOT_RETRYABLE}` **严格 1 行**；**stub 调用计数**：`assertStubCallCount("auth-failed", 1)`；禁 `[181001/181009/181010/181012]` numericCode |
| PASS | 两跑均 strong-eq numericCode==181011 + 零 retry + T3 strong-eq 1 行 + stub==1 |
| FAIL | 任一族偏离（OpenAI 挂点正常但 SiliconFlow 挂点吞 401 退化 / 反之；或两族 numericCode 不一致；或任一族 T3 缺失/错位）|
| INCONCLUSIVE | probe-agent `LLM_PROVIDER=SiliconFlow` 启动失败（SiliconFlow 挂点代码路径不可达，归载体层缺口）；或 stub-server 对 SiliconFlow wire 的处理与 OpenAI 不一致（llm-stub-server 侧 bug，归载体层） |

### 3.22 TC-STREAM-DISCONNECT-FIRST-SEGMENT-AFTER-STRICT（L1 §8.3 **ST-S02** / 流式首段**后**纯 disconnect 无 payload，严格固定 181001 唯一，**r6 新增**）

> **r6 新增背景**：reviewer r5 评论指出 "真正的 ST-S02 定义" 应为 **stub 纯 disconnect 不带 payload 时严格固定 numericCode==181001 唯一**（TEMPORARY/UNKNOWN，`SseConnectionClosedException` 或等效路径），而非 §3.20 "首段后 retryable semantics (429 等)" 的 二态可接受变体。
>
> **载体层新增**：llm-stub-server 新增 stub mode `stream-disconnect-first-segment-after`——首段正常 chunk 返回后 **直接关闭 TCP 连接**（不返回任何 HTTP 状态码、不发错误帧），走 SseConnectionClosedException 路径（r6 demo-handoff 需确认 stub mode）。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `stream-disconnect-first-segment-after` 模式（SSE 首段正常 chunk 返回后，连接直接 TCP close，不发任何 payload） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping","stream":true}` |
| Then | **T1**：SSE 顶层 `failure.numericCode == 181001` **strict unique**（TEMPORARY/UNKNOWN，禁出 181009/181010/181011/181012/181008）+ `failure.code == MODEL_CALL_FAILED`；**T2**：**无** `Model call will retry.` 行（首段已到达，`FirstSegmentRetryIterator` 不整次重试）；**stub 调用计数**：`assertStubCallCount("stream-disconnect-first-segment-after", 1)` strong-eq 1 |
| PASS | T1 numericCode == 181001 严格唯一 + T2 零 retry 行 + stub == 1 |
| FAIL | numericCode ≠ 181001 / 出现 `Model call will retry.` 行（首段分界破坏）/ stub ≠ 1 |
| INCONCLUSIVE | stub 不发首段（流直接以 disconnect 响应，退化为 ST-S01 场景）/ stub mode `stream-disconnect-first-segment-after` 未就绪 |

---

## 4. 状态跟踪

| TC | 当前状态 | 阻塞 | 待办 |
|---|---|---|---|
| TC-RATE-LIMITED-PERSISTENT | **runnable（r5 strong-eq 6/4）** | 无（r4 待裁定点已废弃；L2 §2.3 双层事实为锚） | SIT 侧 `Feat064ErrorTaxonomyAndRetryTest.rateLimitedPersistentExhaustsRetryBudget` 已落地，r5 更新 strong-eq 常量 |
| TC-RATE-LIMITED-RECOVER | runnable | 无 | SIT 侧 `rateLimitedRecoverSucceedsAfterRetry` 已落地 |
| TC-QUOTA-EXCEEDED | **runnable（r6：T3 通道已开放）** | 无（agent-core-java PR!449 2026-10-27 landed，T3 结构化终态日志 strong-eq 开通） | SIT 侧 `quotaExceededShortCircuitsWithoutRetry` 已落地；r6 待追加 T3 `category=QUOTA_EXCEEDED, reason=NOT_RETRYABLE` strong-eq 1 行断言 |
| TC-AUTH-FAILED | **runnable（r6：T3 通道已开放）** | 同 QUOTA | SIT 侧 `authFailedShortCircuitsWithoutRetry` 已落地；r6 待追加 T3 `category=AUTH_FAILED, reason=NOT_RETRYABLE` strong-eq 1 行断言 |
| TC-TEMPORARY-EXHAUSTED | **runnable（r5 strong-eq 6/4；r7 T3 strong-eq 2）** | 无（r4 待裁定点已废弃） | SIT 侧 `temporaryExhaustsAtDefaultMaxRetriesTwo` 已落地，r5 更新 strong-eq 常量；r7 追加 T3 `category=TEMPORARY, reason=RETRY_BUDGET_EXHAUSTED` strong-eq **2 行**断言（Retrier 内层预算耗尽 1 + Rail 整轮 1） |
| TC-CONTEXT-LENGTH-EXCEEDED | **runnable（r6：T3 通道已开放）** | 同 QUOTA | SIT 侧 `contextLengthExceededShortCircuitsWithoutRetry` 已落地；r6 待追加 T3 `category=CONTEXT_LENGTH_EXCEEDED, reason=NOT_RETRYABLE` strong-eq 1 行断言 |
| TC-STREAM-ERROR-FRAME | **runnable（r5 ST-S03）** | — | SIT 侧 `streamErrorFrameAfterFirstSegmentShortCircuits` 已落地，r5 更新 ST 编号 S02→S03 |
| TC-MAX-RETRIES-NEGATIVE-REJECTED | runnable | — | SIT 侧 `maxRetriesNegativeIsRejectedAtStartup` 已落地 |
| TC-NUMERIC-CODE-FIELD | runnable | 永久类 category 字段部分待 T3 通道开放 | SIT 侧 `numericCodeFieldIsPresentForAllCategories` 已落地（跨 TC 聚合），r5 更新字段路径 metadata→SSE failure |
| TC-RATE-LIMITED-SWITCH-OFF | **runnable（r5 strong-eq 2；r7-fix T3 strong-eq 2）** | 无（r4 待裁定点已废弃；Rail 不消费开关 + `DEFAULT_MAX_GUARDRAIL_RETRIES=1` 为锚） | SIT 侧 `Feat064RetryConfigAndCrossProviderTest.rateLimitedSwitchOffBypassesRetry` 已落地，r5 更新 strong-eq 常量；r7-fix 追加 T3 `category=RATE_LIMITED, reason=POLICY_DISALLOWED` strong-eq **2 行**断言（Retrier 内层 1 + Rail 整轮 1） |
| TC-TEMPORARY-SWITCH-OFF | **runnable（r5 strong-eq 2；r7-fix T3 strong-eq 2）** | 同 s14 | SIT 侧 `temporarySwitchOffBypassesRetry` 已落地，r5 更新 strong-eq 常量；r7-fix 追加 T3 `category=TEMPORARY, reason=POLICY_DISALLOWED` strong-eq **2 行**断言 |
| TC-MAX-RETRIES-ZERO-NO-RETRY | **runnable（r7-fix T3 strong-eq 1）** | — | SIT 侧 `maxRetriesZeroShutsDownRetryBudget` 已落地；r7-fix 追加 T3 `category=TEMPORARY, reason=RETRY_BUDGET_EXHAUSTED` strong-eq **1 行**断言（Rail gate `maxRetries>0` 阻断整轮，无第二行） |
| TC-CROSS-PROVIDER-CLASSIFICATION | runnable | — | SIT 侧 `crossProviderSchemaHookpointCoverage` 已落地（s17 schema=dashscope） |
| TC-SUCCESS-NO-INTERFERENCE | runnable | — | SIT 侧 `successPathIsNotInterferedWith` 已落地，r5 Javadoc 更新为 "stub CI 补充" 不认领 ST-R01 |
| TC-STREAM-RECOVER-BEFORE-FIRST | **runnable（r5 加 stream:true body + stream-answer 断言）** | — | SIT 侧 `streamRecoverBeforeFirstSegmentPreservesAnswer` 已落地，r5 更新请求 body + 响应断言 |
| TC-REAL-ENDPOINT-OK | **runnable（r5 ST-R01，manual tag）** | `@Tag("real-endpoint","manual")`，CI 不自动跑；需 shell `source ~/.llmrc` + unset proxy | SIT 侧 `Feat064RealEndpointSamplingTest.realEndpointHappyPath` 已落地，r5 更新 Javadoc ST 编号 C01→R01 |
| TC-REAL-ENDPOINT-AUTH-FAILED | **runnable（r6：T3 通道已开放，manual tag）** | 同 ST-R01（`@Tag("real-endpoint","manual")`，CI 不自动跑） | SIT 侧 `realEndpointAuthFailureClassifiesAs181011` 已落地，r5 更新 ST 编号 C02→C01；r6 升 runnable + 追加 T3 `category=AUTH_FAILED, reason=NOT_RETRYABLE` strong-eq 1 行断言 |
| TC-NON-STREAM-RATE-LIMITED-RECOVER | **runnable（r5 新增 ST-R02；r6 显式 `stream:false`）** | — | **SIT 待新增**：§3.18 新 TC `nonStreamRateLimitedRecoverSucceedsAfterRetry`；r6 request body 显式 `"stream":false` 走 blocking 单 JSON 路径 |
| TC-REAL-ENDPOINT-CONTEXT-LENGTH | **runnable（r6：T3 通道已开放，manual tag）** | 同 ST-C01 real-endpoint（`@Tag("real-endpoint","manual")`） | **SIT 待新增**：§3.19 新 TC `realEndpointContextLengthClassifiesAs181012`；r6 升 runnable + 追加 T3 `category=CONTEXT_LENGTH_EXCEEDED, reason=NOT_RETRYABLE` strong-eq 1 行断言 |
| TC-STREAM-RATE-LIMITED-FIRST-SEGMENT-AFTER | **runnable（r5 新增 ST-S03 子型，r6 relabel S02→S03）** | demo S21 stub mode `stream-rate-limited-first-segment-after` 需 demo-handoff 冒烟确认 | **SIT 待新增**：§3.20 新 TC `streamRateLimitedFirstSegmentAfterShortCircuits`；r6 Javadoc ST 编号 S02→S03 子型 |
| TC-CROSS-FAMILY-OPENAI-VS-SILICONFLOW | **runnable（r5 新增 ST-O01；r6 订正 DeepSeek→SiliconFlow）** | 载体层：`TimeoutDemoLauncher.probeAgent()` 需支持 TC 侧覆写 `LLM_PROVIDER`（r5 新增，见 §2.2） | **SIT 待新增**：§3.21 新 TC `crossFamilyOpenAIAndSiliconFlowClassifyAuthFailureIdentically` + fixture `LLM_PROVIDER` override 开孔；r6 订正：DeepSeek 反射代理到 OpenAIModelClient 非独立栈，SiliconFlow 自带 Retrier/SseIterator/handlers 才是真二族 |
| TC-STREAM-DISCONNECT-FIRST-SEGMENT-AFTER-STRICT **r6 新增** | **runnable（r6 新增 ST-S02 真型，strict pin 181001 唯一）** | demo stub mode `stream-disconnect-first-segment-after` 需 demo-handoff 新增（首段后纯 TCP close 无 payload） | **SIT 待新增**：§3.22 新 TC `streamDisconnectFirstSegmentAfterStrictPinsTemporary`；strict pin numericCode==181001 唯一 + stub count==1 + T3 `category=TEMPORARY, reason=NOT_RETRYABLE`（纯 disconnect 判为 permanent-error 终态） |

### 2026-10-30 实证小结（PR!472+PR!478 新 jar 全量跑）

**ECS 跑测结果**：**3 FAIL 持续 / RetryConfig 5/5 PASS / RealEndpoint §3.19 根因订正**

- 使用 jar：`agent-core-java-0.1.17.jar`（md5 `7c5813ecb551b708b83bf7ebccc6d54c`，PR!472 C-4 + PR!478 SSE EOF 修复）；`timeout-probe-agent-0.1.0.jar`（md5 `5c3549317e63744b092ee2d5116303ed`）；`llm-stub-server-0.1.0.jar`（md5 `4a9934126bd01bd57307d865a1d0a54e`）
- 使用 model（real-endpoint 侧）：`qwen-flash-character`（阿里云百炼，`https://llm-nj0zwkkqemzwtqlu.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`）

**Feat064RetryConfigAndCrossProviderTest**：**5/5 PASS**（rateLimitedSwitchOff / temporarySwitchOff / maxRetriesZero / crossProvider / negativeMaxRetries）

**Feat064RealEndpointSamplingTest**：**2/3 PASS**（realEndpointHappyPath / realEndpointAuthFailure PASS；§3.19 FAIL）

**Feat064ErrorTaxonomyAndRetryTest**：**11/13 PASS**（§3.20 / §3.22 FAIL）

**3 条 FAIL 跟踪项（本轮持续，均非 SIT fixture bug）**：

| TC | FAIL 表现 | 10-30 新发现 | 等待动作 |
|---|---|---|---|
| §3.19 realEndpointContextLengthClassifiesAs181012 | `Expected 181012 but got 181001` | **根因订正（10-29 推断有误）**：10-29 认为是 context window 未超限（model 128K+），但 10-30 换 `qwen-flash-character`（8K context window）仍 FAIL；真实根因是 `RuleBasedModelErrorClassifier` 的 `CONTEXT_LENGTH_KEYWORDS` 未覆盖阿里云百炼 Qwen 格式（`"Range of input length should be [1, 8192]"` / `code=invalid_parameter_error`）。**观察报告已推 docsReview**（commit `a6bc0d3981e5`） | 等 demo 侧确认 Qwen 错误格式是否在覆盖范围内 + 修复 `RuleBasedModelErrorClassifier`；SIT 复跑 §3.19 验证 |
| §3.20 streamRateLimitedFirstSegmentAfterShortCircuits | `stub==2`（期望 1） | PR!472 C-4 合入后仍 FAIL（C-4 修的是 FEAT-058 S5 session interval 公式，不修 Rail 首段边界检查）。根因：`ClassifiedModelFailureRail.onModelException` 无首段已到达感知，Rail 挽留 1 次 → stub 被调 2 次。**观察报告已推 docsReview**（commit `0e7ae3fe`） | 等 demo 侧分析 Rail 首段边界感知能力后修复；SIT 复跑验证 stub==1 |
| §3.22 streamDisconnectFirstSegmentAfterStrictPinsTemporary | `stub==2`（期望 1） | PR!478 修复了 Issue #276（pure TCP close 现在被正确检测为 error，不再沉默），stub==2 证明 error IS 被检测到；但 Rail 仍在首段后触发整轮重试（stub 被调 2 次）。根因与 §3.20 同：Rail 无首段边界感知。**观察报告已推 docsReview**（commit `0e7ae3fe`，与 §3.20 合并在同一份报告中） | 同 §3.20，等 Rail 首段边界修复 |

**待开发侧动作**（SIT 侧 PR 落地前不动用例代码）：
1. demo 侧确认 Qwen `"Range of input length"` / `invalid_parameter_error` 格式是否应被 `CONTEXT_LENGTH_KEYWORDS` 覆盖，修复分类器 → 影响 §3.19
2. demo/core 侧修复 `ClassifiedModelFailureRail.onModelException` 增加首段边界感知 → 影响 §3.20 + §3.22

**本件相关 docsReview 推送（2026-10-30）**：
- `feat064-error-taxonomy-retry/SIT-OBSERVATION-feat064-rail-first-segment-retry-20261030.md`（commit `0e7ae3fe`，§3.20/§3.22 stub==2 Rail 首段边界缺陷）
- `feat064-error-taxonomy-retry/SIT-OBSERVATION-feat064-context-length-classifier-miss-20261030.md`（commit `a6bc0d39`，§3.19 Qwen 分类器覆盖缺口）

### 2026-10-29 实证小结（Feat064 22 TC 全量跑结果）

**ECS 跑测结果**：**22 TC / 19 PASS / 3 FAIL / 0 Errors / 0 Skipped / Total time 3:43 min**

- 使用 jar：develop-head PR!242+PR!442+PR!449 bundle rebuild（docsReview commit `ab9e03ba6581`，10-28 14:02 推）
- 使用 model：qwen-doc-turbo（阿里云百炼 OpenAI 兼容 chat completions）
- SIT 侧 session 累积 4 处 fixture / TC 代码 fix（见 `docs/issues/draft-feat064-r7-fix-handoff-notes-20261029.md`）：
  1. `StubStatsClient.java` Void 类型 bug（JDK 21 release 下 BodyHandlers.discarding() 返回 BodyHandler<Void>）
  2. `SutLogTailer.java` TERMINAL_LINE Pattern bug（r7 刷了 Asserter.REASON_* 常量但 Pattern 没同步 + 中间字段紧邻 bug）
  3. `TimeoutProbeHttpClient.java` parseSingleJson 补 blocking wire schema 识别（§3.18 需要）
  4. `Feat064ErrorTaxonomyAndRetryTest.java` §3.18 判据订正（删 resultType，改读 output，对齐 blocking 真实 wire schema）

**3 条 FAIL 跟踪项（都不是 SIT bug）**：

| TC | FAIL 根因 | 等待动作 | 关联 observation |
|---|---|---|---|
| §3.22 streamDisconnectFirstSegmentAfterStrictPinsTemporary | 10-28 rebuild stub jar 实证**没有** `handleStreamDisconnectFirstSegmentAfter` handler；cases §r6 自己标注 "demo-handoff 冒烟待确认" | 等 demo owner 补 stub mode + rebuild jar 推 docsReview | — |
| §3.20 streamRateLimitedFirstSegmentAfterShortCircuits | `Expected stub==1 but got 2` — Rail 挽留吃 TEMPORARY（跟 S5 observation 同根）| 等 agent-core-java 侧 **C-4 PR** 落地（Rail 发 wire `model_retry` 事件 + `retry_tier` 字段 + session interval 配置联动），SIT 升级判据 | **docsReview `SIT-OBSERVATION-s5-rail-swallows-timeout-20261029.md`**（commit `a4415aae9a7d`）+ 开发侧答复 `DEV-REPLY-s5-rail-timeout-coordination-20261029.md`（commit `630251da18a1`，裁定 C-4） |
| §3.19 realEndpointContextLengthClassifiesAs181012 | glm-5.3-prime (context 128K) 和 qwen-doc-turbo (context 256K) 都远超 128 KiB prompt → 不触发 context_length_exceeded；input_tokens 16407-16420 远 < 256K | 换真正 ≤16K context 的 model（阿里云百炼平台待确认 `qwen-turbo` 标准 8K 等）；或接受 §3.19 的 **partial** 状态（cases §r6 本就标 partial，T3 category 待通道开放）| — |<br>**⚠ 2026-10-30 根因订正**：上述分析有误，换 `qwen-flash-character`（8K context window）仍 FAIL；真实根因是分类器关键字表未覆盖 Qwen `"Range of input length"` 格式，见 10-30 小结。

**9 条 FAIL → PASS 的 fix 转化**（SIT 本次 session 实证得到的转绿清单）：
- 分类 B T3 Pattern bug 修完 → §3.3/§3.4/§3.5/§3.6/§3.17 real-endpoint auth-failed + Feat064RetryConfig 5 TC（含 §3.10/§3.11/§3.12/§3.13/§3.21）**合计 10 条**
- 分类 D §3.18 blocking schema 识别 + 判据订正 → **1 条**（nonStreamRateLimitedRecoverSucceedsAfterRetry）

**待开发侧动作**（SIT 侧 PR 落地前不动用例代码）：
1. 核心侧 C-4 PR（修 S5 Rail 挽留与 session interval 协调缺陷）—— 影响 §3.20 + 议题三 S5 blockingPath + errorDetails 第 3 段，PR 落地后 SIT 升级判据
2. demo 侧补 `stream-disconnect-first-segment-after` stub mode + rebuild jar —— 影响 §3.22

**本件相关 docsReview 推送**：
- 2026-10-29 09:03 `SIT-OBSERVATION-s5-rail-swallows-timeout-20261029.md`（commit `a4415aae9a7d`，SIT 实证 + 四条可能解法）
- 2026-10-29 10:17 `DEV-REPLY-s5-rail-timeout-coordination-20261029.md`（commit `630251da18a1`，开发侧裁定 C-4 新方向）

### 跟踪项（L1 §8.3 ST-O02 两族 real-endpoint 跨族抽样，r6 唯一剩项）

> r5 ST-R01/R02/C01/C02/S01/S02/S03/O01 已全部收回入 §3.14-§3.22（r6 §3.22 新增 ST-S02 真型）。唯一剩项为 ST-O02 "两族 real-endpoint 跨族抽样"——要求 OpenAI × **SiliconFlow** 两族各挂真实端点凭据跑同一 happy-path/bad-key 组合，需两套真实凭据并行配置。不入 r6 TC 骨架，归跟踪项。

| L1 ST | 承接策略 | 阻塞 |
|---|---|---|
| **ST-O02**（两族 real-endpoint 跨族抽样）| 需 OpenAI + **SiliconFlow** 两套真实凭据并行配置（r6 订正：DeepSeek 反射代理到 OpenAIModelClient 非独立栈；SiliconFlow 自带 Retrier/SseIterator/handlers 才是真二族），手动抽样执行；不入 CI 骨架 | 凭据层：需 CI 侧或开发侧配置 OpenAI + SiliconFlow 两套真实 LLM 凭据，并提供手动抽样流程 |

### 保留项（deferred，暂不构造）

- **TC-USER-CANCELLED-\***（L2 §1.2.8 用户取消不重试 + §1.2.14 取消识别规则显式定义 的 C1-C5 cancellation）：**2026-10-27 升级为 landed**——FEAT-058 MUST#11 "cancel 可观察出口" 已通过 agent-core-java mr/437 #252 cancellation log anchor + probe-agent `CancelProbeController` 侧通道落地（develop-head m2），SIT TC 实体承接到 FEAT-058 cases §3.12 TC-CANCEL-OBS-S18 + §3.13 TC-CANCEL-OBS-S19（跨 FEAT 双 Tag `@Tag("feat-058") @Tag("feat-064")`）。ECS 2026-10-27 实测 2/2 PASS。本 FEAT-064 cases 不再独立新增 TC-USER-CANCELLED-\* 条目，USER_CANCELLED 分类的 wire 可观测承诺由 FEAT-058 §3.12/§3.13 覆盖；UT-C10 / UT-R06 继续承接 "CancellationException 恢复 + 不计熔断 + 不整次重试" 的内部断言。
- **TC-CLASSIFIED-FAILURE-NOT-COUNTED**（L2 §1.2.16 熔断计数消费分类，**SHOULD**）：**从 deferred 升级为 runnable**——平台层已提供只读快照 API `Model.getCircuitBreakerSnapshot()` 返回 `Optional<Snapshot>`，快照含 `isOpen` + `consecutiveFailures` 两字段（二态；两字段独立采样，不承诺并发期间原子一致性；未转发观测的客户端返回 empty Optional）。TC 构造策略按 §1.2.16 原文——驱动若干次 QUOTA_EXCEEDED / AUTH_FAILED / CONTEXT_LENGTH_EXCEEDED 永久类调用后读快照，断言 `isPresent()` + `isOpen()==false` + `consecutiveFailures()==0`；对照组用 TEMPORARY 类驱动，断言 `consecutiveFailures()>0`（证明 breaker 本身在工作，排除"所有调用都不计数"的假阳性）；R6 判据 numericCode 继续按 §3.3/§3.4/§3.6 主判据覆盖。r3 "只刷判据不新增 TC"，§3.X 不展开骨架，SIT 侧 fixture 新增 `assertCircuitBreakerSnapshotClean()` / `assertCircuitBreakerConsecutiveFailuresGt(int)` 两 helper（首版 UOE，等后续窗口回灌）。SHOULD 等级，SIT 不阻塞特性合入。

### S1/S2 规格层问题（L1↔L2 口径，建议向特性文档作者澄清）

| # | 位置 | 问题 | 本档影响 |
|---|------|------|------|
| **S1** | L1 §5.1.7「每次模型调用失败」 vs L2 §3.10「每次重试」| **范围不一致**：L1 要求含永久类日志记录，L2 实现仅覆盖重试事件 | §1.4.2 "T3 通道待平台层落地" 的根因；SIT 侧 `assertTerminalLogCategory` 首版 throw UOE，不影响 §3.3/§3.4/§3.6 的 numericCode 主判据 |
| **S2** | L1 §8.3 ST-O01「两族」组成未点名 | **r6 订正**：L2 版本口径（Feat-Func-064 line 29）已明确两族 = OpenAI 族 × **SiliconFlow** 族，L1 未同口径；代码层验证：DeepSeekModelClient.openAiDelegate() 反射创建 OpenAIModelClient 实例并代理所有调用，不是独立 Retrier/SseIterator 栈，不构成真二族；SiliconFlowModelClient 自带 Retrier + FirstSegmentRetryIterator + SseIterator + 独立 error handlers，才是真正的第二族 | §3.21 **r6 订正**以 OpenAI × SiliconFlow 两族承接 ST-O01；llm-stub-server `auth-failed` mode 作为 OpenAI 兼容 wire 可同时服务两族 LLM_PROVIDER 切换；DeepSeek 挂点代码路径由 UT 承接，不入 SIT 骨架 |
| **S3**（**r5 新增**）| L1 §8.3 ST-R04「总调用=3」 vs L2 §2.3「全程调用上限 = (maxRetries+1)×(护栏重试次数+1) = 3×2 = 6」 | **层级范围不同、各自正确**：L1 ST-R04 "总调用=3" 是 Retrier **内层单层**描述（`maxRetries=2` + 首发 = 3）；L2 §2.3 "6" 是 Retrier × Rail **双层总计** | SIT 以 L2 §2.3 双层事实为锚（§3.1/§3.5 strong-eq stub=6 + retry 日志=4）；L1 ST-R04 的 3 作为内层参考不写入 SIT 判据。两份文档不需要修订，仅做层级范围脚注说明（§5） |

### 已知设计偏差（不阻塞）

- **D1（L2 vs 代码小漂移）**：`RuleBasedModelErrorClassifier` 的 R5 keyword set 代码常量与 L2 §5.1 文本枚举略有差异（见 SIT 本地 FEAT-064 对齐表）；由 UT 的 `RuleBasedModelErrorClassifierTest` R5 分支以代码口径为准承接；SIT 不构造反证 TC。
- **D2-D4**：L1 口径补强，已入 L2，不影响 SIT 实现。

### Fixture 扩展清单（相对 FEAT-058）

| Fixture | 新增 API | r3/r4/r5 变更点 |
|---|---|---|
| `SutLogTailer` | `countOfRetryLog()` / `countOfStatusCode(int)` / `categoryOfLastError()` / `countOfTerminalLog()` / `terminalLogCategoryOf()` | 后两个方法用于 T3 通道，首版可返 Optional.empty 等平台层通道落地后真实解析 |
| `StubStatsClient` | `countOf(stubMode)` / `reset()` 对接 llm-stub-server `GET /__stats` + `POST /__stats/reset` 侧通道 | **r4 新增**（demo 侧 stub stats 通道已落地，SIT 侧 helper 已就绪） |
| `ErrorEnvelopeAsserter` | `assertNumericCode(int)` 覆盖五码 / `assertRetryLogCategory(SutLogTailer, String)` / `assertTerminalLogCategory(SutLogTailer, String)` / `assertRetryLogCount(SutLogTailer, int)` / `assertNoRetryLog(SutLogTailer)` / `assertStubCallCount(StubStatsClient, String, int)` strong-equality / `assertCircuitBreakerSnapshotClean()` / `assertCircuitBreakerConsecutiveFailuresGt(int)` | r2 的 `assertCategory` + `assertStatusCode` 两方法原路径（HTTP body `details.*`）**已 deprecate**；r3 改走 numericCode + 日志双通道；r4 的 `assertStubCallCountBetween` 区间断言 **r5 弃用**（全部场景已 strong-eq）；`assertCategory` **r5 重定义**：读 `/v1/query` SSE `failure.code` 经 fixture `mapFailureCodeToCategory` 映射到 category 字串；`assertTerminalLogCategory` 首版 throw `UnsupportedOperationException` 等平台层 T3 通道落地；`assertCircuitBreakerSnapshot*` 两 helper 读 `Model.getCircuitBreakerSnapshot()` 公开 API |
| `TimeoutProbeHttpClient` | `errorNumericCode()` / `category()` 从 `/v1/query` SSE `failure.{numericCode,code}` 解析 + fixture 映射；**r5 新增 `query(prompt, timeout, stream=true)` 重载** 支持 §3.15/§3.18/§3.20 的 stream 开关 | r3 新增；r5 字段路径从 `metadata.openjiuwen.error.*` 订正为 SSE `failure.*`；`errorDetails()` 老 Map 返回路径 r5 清理（残留的 category 返回移除，Javadoc 标 deprecated） |
| `TimeoutDemoLauncher` | **r5 新增**：`probeAgent()` 默认 env 允许 TC 侧覆写 `LLM_PROVIDER`（原本硬编码 `OpenAI`），支撑 §3.21 ST-O01 两族切换；其他 FEAT-064 TC 继续默认 `OpenAI` 不受影响 | r5 增量 |

---

## 5. 规格层脚注

### 5.1 L1 §8.3 ST-R04「总调用=3」 vs L2 §2.3「6 = (maxRetries+1)×(护栏重试次数+1)」层级范围

**结论**：两份文档层级范围不同、**各自正确**，SIT 以 L2 §2.3 双层事实为锚。

**解释**：
- **L1 §8.3 ST-R04 「总调用=3」** 描述 `ModelCallRetrier` **内层单层**的调用总数：首发 1 次 + 重试 `maxRetries=2` 次 = 3 次。这是 "Retrier 侧视角" 的单层正确描述，用于说明 "retry 预算在 Retrier 内层如何展开"。
- **L2 §2.3 原文「全程调用上限 = (maxRetries+1)×(护栏重试次数+1) = 3×2 = 6」** 描述 Retrier × Rail **双层总计** 的调用上限：Retrier 内层发 3 次（首发+2 次重试）× Rail 整轮一次（`DEFAULT_MAX_GUARDRAIL_RETRIES=1`，重触发 Retrier 内层完整循环再 3 次）= 6 次。这是 "调用方视角" 的双层总观察。

**SIT 侧选择**：SIT 以 L2 §2.3 双层事实为锚（§3.1/§3.5 strong-eq `stub=6 / retry 日志=4`、§3.10/§3.11 strong-eq `stub=2`），因为 SIT 看到的 wire-observable 调用次数是 stub-server 实际收到的 HTTP 请求数，天然涵盖双层。L1 ST-R04 的 "3" 作为 Retrier 内层设计参考值不写入 SIT 判据。

**两份文档无需修订**：L1 和 L2 的层级范围表述在各自上下文中都是准确的，仅在跨层级引用时需明确说明"内层单层" vs "双层总计"的区别。本 testplan 以 L2 为锚，不涉及对 L1 的修订请求。
