---
feature_id: FEAT-064
feature_title: 模型调用错误分类与弹性重试
sut: timeout-tiering-model-call-demo 的 timeout-probe-agent（与 FEAT-058 共用载体，ReActAgent，openjiuwen 范式，fat jar 独立运行；端口 18092 + llm-stub-server 端口 18094），对端 stub 在 FEAT-058 的 7 种 stall 模式之外**追加 7 种 FEAT-064 错误注入模式**（HTTP 429 / 402 / 401 / 500 / 400+context_length_exceeded / SSE error frame / 429→200 recover）；FEAT-064 测的是**分类规则 + 重试策略**，用真实 LLM 无法可控注入这些错误类型，所以 stub 是必要对端
scope: 本档覆盖 FEAT-064 SIT 侧黑盒可观察（`POST /v1/query` body + SUT 日志的分类错误行 `[181009/181010/181011/181012/181001]` + `category=<CAT>` 字段 + `"Model call will retry."` 行计数 + HTTP response body 的 error payload `details.category` 字段）；UT 侧已由 dev team 交付 73 UT（PR!420 @ f68f4da6）+ 容器 E2E 11/11 PASS，本档不复制。
status: pending-implementation
status_note: 测试方案与 **13 条 TC 骨架**（9 原 + 4 增量 s14/s15/s16/s17）已沉淀 + **1 条 deferred TC** 等平台 observability issue；fixture 代码（TimeoutDemoLauncher / TimeoutProbeHttpClient / SutLogTailer / ErrorEnvelopeAsserter）**与 FEAT-058 共用**，asserter 侧新增 FEAT-064 分类断言方法（`assertCategory` / `assertRetryLogCount` / `assertNoRetryLog`）；S7-S13 的 7 个 demo fat jar 已由 demo 侧 2026-10-22 本机构建完成，demo 本机冒烟 7/7 PASS；**s14/s15/s16/s17 四个新 profile 待 demo 下一轮 push 带新 fat jar + scripts 到位后再开跑**（见 `docs/review/FEAT-064-demo-coverage-gaps-feedback.md` 的开发侧 2026-10-26 答复）；SIT 侧用例类未落地。
owner: TBD
tags: [integration, reactagent, feat-064, model-call-error-taxonomy-and-retry]
depends_on:
  - timeout-probe-agent-0.1.0.jar 已就绪（与 FEAT-058 共用 jar；s14-s17 需等 demo 下一轮 push 带的新 fat jar，新增 3 个 probe-agent 配置透传字段）
  - llm-stub-server-0.1.0.jar 已就绪（stub 已含 FEAT-064 7 种错误注入模式；s17 需等 demo 下一轮 push 带新 stub jar，新增 `LLM_STUB_RESPONSE_SCHEMA=openai|dashscope` 开关）
  - 不需要真实 LLM 凭据——demo 全部打本机 stub（127.0.0.1:18094）
  - `unset HTTP_PROXY HTTPS_PROXY`（stub 在本机 loopback，企业代理会干扰）
  - **JDK 17 或 21**（JDK 24+ 下 agent-core-java 的 Lombok `sun.misc.Unsafe` 反射失效，probe-agent fat jar 启动崩）
  - 端口 18092（probe-agent）/ 18094（llm-stub-server）可用
  - `LLM_PROVIDER=OpenAI`（统一分类载体，stub 实现 OpenAI 兼容协议）
  - 可选：`LLM_STUB_RATE_LIMITED_RECOVER_AFTER=1`（rate-limited-recover 恢复前返多少次 429，默认 1）
  - 可选：`LLM_STUB_RATE_LIMITED_RETRY_AFTER_SECONDS=1`（429 响应头 `Retry-After` 值，默认 1 秒）
  - **FEAT-064 TC 全部用默认 profile**（不覆写三层超时），避免与 FEAT-058 的 timeout 分类混淆
  - **s14/s15/s16/s17 新增 probe-agent 配置键**（新 jar 自带绑定，SIT 只传 profile）：
    - `demo.timeout-probe.retry-on-rate-limited: false`（s14）
    - `demo.timeout-probe.retry-on-temporary: false`（s15）
    - `demo.timeout-probe.max-retries-override: 0`（s16）
  - **s17 新增 stub env**：`LLM_STUB_RESPONSE_SCHEMA=dashscope` + `LLM_STUB_DEFAULT_MODE=auth-failed`
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

> **一句话**：以 demo `timeout-probe-agent` 为 SUT（与 FEAT-058 共用）、`llm-stub-server` 为可控对端（追加 7 种 FEAT-064 错误注入模式），把 FEAT-064 §2 的 15 MUST + 2 SHOULD + default `maxRetries` 3→2 外部可观察行为变化，映射为可在 SIT 侧黑盒断言的子用例。demo 覆盖 **S7..S13 共 7 个场景**，SIT 在此基础上追加 2 条增量 TC（maxRetries 负值 fail-fast + 错误 payload `details.category` 聚合）。USER_CANCELLED（C1-C5）SIT 覆盖延迟至 FEAT-058 MUST#11 裁定落地，UT 已承接。

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
| PermanentModelError.defaultRecoverable()=false | §1.2.2 失败表面分类化 (MUST) | `FEAT-064.permanent-short-circuit`（S9/S10/S12 的"无 Model call will retry"） | runnable | `PermanentModelErrorTest` | QUOTA_EXCEEDED / AUTH_FAILED / CONTEXT_LENGTH_EXCEEDED 三类短路 |
| ClassifiedModelFailureRail 启动期 fail-fast | §1.2.11 主循环护栏按类处置 (MUST) | （未独立 TC） | **UT only** | `ClassifiedModelFailureRailTest` init | SIT 不构造（Rail 存在于 probe-agent 装配即证明初始化通过） |
| ModelBackupRail presence yield + maxRetries>0 gate | §1.2.11 主循环护栏按类处置 (MUST) | （未独立 TC） | **UT only** | `ClassifiedModelFailureRailTest` presence yield | SIT 不构造 |
| ModelCallRetrier 唯一重试循环所有者 | §1.2.6 非流式自动重试 (MUST) | `FEAT-064.retrier-executes-retry-budget`（S7 耗尽 + S8 恢复） | runnable | `ModelCallRetrierTest` | TC-RATE-LIMITED-PERSISTENT / TC-RATE-LIMITED-RECOVER |
| OpenAIRetryingHttpClient 下线（代码层） | §1.2.6 非流式自动重试 (MUST，实现迁移) | （未独立 TC） | **UT only** | compilation 自然证明 | SIT 不构造"类消失"反证 TC |
| 流式重试首段分界（FirstSegmentRetryIterator） | §1.2.7 流式重试判定线 (MUST) | `FEAT-064.first-segment-boundary`（S13） | runnable | `FirstSegmentRetryIteratorTest` | TC-STREAM-ERROR-FRAME：首段后无重试日志 |
| retryOnRateLimit / retryOnTemporary 开关面 | §1.2.9 重试配置语义对齐 (MUST) | `FEAT-064.rate-limited-switch-off`（s14）/ `.temporary-switch-off`（s15） | runnable（**等 demo s14/s15 jar**） | `ModelCallRetrierTest` + `ModelClientConfigTest` | TC-RATE-LIMITED-SWITCH-OFF / TC-TEMPORARY-SWITCH-OFF（开发侧 2026-10-26 ack，走 probe-agent `demo.timeout-probe.*` 命名空间） |
| default maxRetries 3→2（外部可观察行为变化） | §1.2.9 重试配置语义对齐 (MUST) | `FEAT-064.default-max-retries-is-two`（S11） | runnable | `ModelClientConfigTest` default 测 | TC-TEMPORARY-EXHAUSTED 的"重试恰 2 次"既证重试路径也证默认值 |
| maxRetries 负值 setter 拒绝 | §1.2.9 重试配置语义对齐 (MUST，校验口径) | `FEAT-064.max-retries-negative-rejected`（增量，demo 无直接场景） | runnable | `ModelClientConfigTest` validateMaxRetries | TC-MAX-RETRIES-NEGATIVE-REJECTED |
| maxRetries=0 预算关断 wire-observable | §1.2.9 重试配置语义对齐 (MUST，配置消费 wire-observable 后果) | `FEAT-064.max-retries-zero-no-retry`（s16） | runnable（**等 demo s16 jar**） | `ModelCallRetrierTest` maxRetries=0 分支 | TC-MAX-RETRIES-ZERO-NO-RETRY（开发侧 2026-10-26 归属校正：从 §1.2.11 Rail 装配事实剥到 §1.2.9 wire-observable 后果） |
| 四新错误码 181009/181010/181011/181012 | §1.2.3 标准错误码映射 (MUST) | `FEAT-064.status-code-contract`（S7/S9/S10/S12） | runnable | `StatusCode` / `StatusMapping` 单测 | 由分类 TC 一一对应覆盖 |
| 181008 timeout 重归 ModelError + details.category 口径扩展 | §1.2.3 标准错误码映射 (MUST) + §1.2.2 失败表面分类化 (MUST) | `FEAT-064.details-category-field`（聚合 S7/S9/S10/S11/S12） | runnable | `StatusMappingTest` + `BaseError.details` 单测 | TC-DETAILS-CATEGORY-FIELD 跨 TC 聚合；FEAT-058 的 181008 timeout 本档不覆盖（归 FEAT-058） |
| Rails.isFromOperation gate（AgentInterrupt/AbortError 透传收窄） | §1.2.10 中断类异常识别放行 (MUST) | （未独立 TC） | **UT only** | `RailsAgentInterruptTest` | SIT 不构造 |
| USER_CANCELLED 三形态（C1-C5 cancellation 规则） | §1.2.8 用户取消不重试 (MUST) + §1.2.14 取消识别规则显式定义 (MUST) | TC-USER-CANCELLED-\*（S14/S15 reserved） | **deferred** | UT-C10 / UT-R06 + 容器 E2E | 待 FEAT-058 MUST#11 cancel 可观察出口裁定 |
| ModelCircuitBreaker.onFailure 消费 classification（分类不计熔断） | §1.2.16 熔断计数消费分类 (**SHOULD**) | `FEAT-064.classified-failure-not-counted` | **deferred（等平台 observability issue）** | `ModelCircuitBreakerTest` onFailure | TC-CLASSIFIED-FAILURE-NOT-COUNTED：等 agent-core-java 开放熔断器观测面（开发侧 2026-10-26 ack，三选一方案中偏好方案 1）；现行弱层佐证（TC-TEMPORARY-EXHAUSTED 日志无 `circuit open` 行）保留但不作为正式 TC |
| ModelRetryEvent 签名新增 category 字段 | 无独立 §1.2 条目（L2 §2.4 实现细节） | （未独立 TC） | **UT only** | `ModelRetryEventTest` | SIT 不读事件总线 |
| 跨 provider 分类差异（OpenAI vs DashScope schema，挂点覆盖） | §1.2.1 统一错误分类器 (**MUST**，挂点覆盖要素) | `FEAT-064.cross-provider-classification`（s17） | runnable（**等 demo s17 stub jar**） | `RuleBasedModelErrorClassifierTest` provider 分支 | TC-CROSS-PROVIDER-CLASSIFICATION：用 DashScope schema × AUTH_FAILED (401) 作代表性场景；R6 状态码直判天然 schema-agnostic；R7 关键词命中由 UT 承接（开发侧 2026-10-26 ack） |

### 非范围显式列出

- **USER_CANCELLED 分类（L2 §1.2.8 用户取消不重试 + §1.2.14 取消识别规则显式定义 的 C1-C5）**：L1 本身在 UT 层已 ✅；SIT 覆盖延迟至 FEAT-058 MUST#11 "cancel 可观察出口" 裁定落地（见 SIT-CLARIFY-REPLY-must11-cancel-definition.md），不阻塞 FEAT-064 特性合入。
- **R1-R12 规则表细节**：UT 承接；SIT 仅在 wire 层证明 "stub 发出某类错误 → SUT 分类正确"，不枚举规则。
- **ModelCircuitBreaker 熔断阈值 / 滑窗 / half-open**：FEAT-008 / FEAT-057 承接；本方案仅覆盖 "分类不计熔断" 的 pass-through 事实（TC-CLASSIFIED-FAILURE-NOT-COUNTED 等平台 observability issue 落地后承接）。

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
| `TimeoutDemoLauncher` | 启停两个 fat jar；端口 ready probe；FEAT-064 TC 注入 `LLM_STUB_DEFAULT_MODE` + **不传** `--spring.profiles.active`（默认 profile） | `src/test/java/com/huawei/ascend/sit/fixture/.../TimeoutDemoLauncher.java`（FEAT-058 已设计，复用） |
| `TimeoutProbeHttpClient` | 底层 `java.net.http.HttpClient` 直发 `POST /v1/query`，body `{"conversation_id":"sit-<uuid>","message":"<prompt>"}`；返回原始 JSON；暴露 `resultType()` / `output()` / `errorStatusCode()` / `errorDetails()` 返回 Map 包含 `category` 字段 | `src/test/java/.../TimeoutProbeHttpClient.java`（FEAT-058 已设计，复用） |
| `SutLogTailer` | tail probe-agent 日志，解析 `[181009/181010/181011/181012/181001] ... category=<CAT> ...` 行；新增 API：`countOfRetryLog()` 匹配 `"Model call will retry."` 行、`countOfStatusCode(int)` 匹配各错误码、`categoryOfLastError()` 从日志行解析 category 字段 | `src/test/java/.../SutLogTailer.java`（FEAT-058 已设计，**本档扩展**） |
| `ErrorEnvelopeAsserter` | 对 HTTP response body error payload 做结构化断言；新增 API：`assertCategory(ModelErrorCategory expected)` / `assertStatusCode(int)` 覆盖 181009/181010/181011/181012/181001；`assertRetryLogCount(SutLogTailer, int expected)` / `assertNoRetryLog(SutLogTailer)` | `src/test/java/.../ErrorEnvelopeAsserter.java`（FEAT-058 已设计，**本档扩展**） |

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
| TC-DETAILS-CATEGORY-FIELD | 聚合上述 5 个分类 stub mode | 默认 | 跨 TC 聚合断言 |
| TC-RATE-LIMITED-SWITCH-OFF | `rate-limited-persistent` | **s14** | `demo.timeout-probe.retry-on-rate-limited: false`（demo 新 jar 自带绑定） |
| TC-TEMPORARY-SWITCH-OFF | `server-5xx` | **s15** | `demo.timeout-probe.retry-on-temporary: false`（demo 新 jar 自带绑定） |
| TC-MAX-RETRIES-ZERO-NO-RETRY | `server-5xx` | **s16** | `demo.timeout-probe.max-retries-override: 0`（demo 新 jar 自带绑定） |
| TC-CROSS-PROVIDER-CLASSIFICATION | `auth-failed` | **s17** | stub env `LLM_STUB_RESPONSE_SCHEMA=dashscope`（demo 新 stub jar 自带） |

---

## 3. 用例细档（G/W/T + PASS/FAIL/INCONCLUSIVE）

### 3.1 TC-RATE-LIMITED-PERSISTENT（S7 / 限流持续 / 可重试类耗尽）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile（`maxRetries=2` 默认）；stub-server 启动于 `rate-limited-persistent` 模式（持续返 HTTP 429 + `rate_limit_exceeded` 错误体 + `Retry-After: 1` 响应头） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | 日志出现 `[181009]` + `category=RATE_LIMITED`；`"Model call will retry."` 恰 **2 次**（default maxRetries=2，共 3 次调用）；最终以 181009 落盘；禁 181010/181011/181012；禁 181008；HTTP response body error payload `details.category=="RATE_LIMITED"` + `details.status_code==181009` |
| PASS | 四项全满足（错误码 + category + 重试次数 + payload 字段齐） |
| FAIL | 任一偏离：错误码非 181009 / category 非 RATE_LIMITED / 重试次数 ≠ 2 / 缺 details.category 字段 / 出现其他分类错误码 |
| INCONCLUSIVE | probe-agent 或 stub-server 启动失败（端口冲突 / JDK 版本错 / stub 不识别 `rate-limited-persistent` 模式） |

### 3.2 TC-RATE-LIMITED-RECOVER（S8 / 限流一次恢复）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `rate-limited-recover` 模式（首次 429，之后 200；`LLM_STUB_RATE_LIMITED_RECOVER_AFTER=1`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | `"Model call will retry."` 恰 **1 次** + `category=RATE_LIMITED`；**无** `[181009]`（最终成功）；HTTP 200 + `result_type==answer`；禁 `[181010]/[181011]/[181012]/[181001]/[181008]` |
| PASS | 单次重试日志 + 最终成功 + 无任何分类错误码 |
| FAIL | 重试次数 ≠ 1 / 最终失败（出现 181009）/ 出现其他错误码 |
| INCONCLUSIVE | stub 的 recover 计数不生效（二次仍返 429）；重跑一次仍不恢复则 INCONCLUSIVE |

### 3.3 TC-QUOTA-EXCEEDED（S9 / 欠费永久类）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile（`maxRetries=2`）；stub-server 启动于 `quota-exceeded` 模式（HTTP 402 + `insufficient_quota`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | 日志出现 `[181010]`；**无** `"Model call will retry."`（PermanentModelError 短路，次数不消耗）；禁 `[181009]/[181011]/[181012]/[181001]/[181008]`；HTTP response body error payload `details.category=="QUOTA_EXCEEDED"` + `details.status_code==181010` |
| PASS | 181010 + 无重试日志 + payload 字段齐 |
| FAIL | 任一偏离：错误码非 181010 / 出现重试日志（表明 PermanentModelError 未短路）/ category 字段错位 |
| INCONCLUSIVE | stub 启动失败 |

### 3.4 TC-AUTH-FAILED（S10 / 认证失效永久类）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `auth-failed` 模式（HTTP 401 + `invalid_api_key`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | 日志出现 `[181011]`；**无** `"Model call will retry."`；禁 `[181009]/[181010]/[181012]/[181001]/[181008]`；HTTP response body error payload `details.category=="AUTH_FAILED"` + `details.status_code==181011` |
| PASS | 181011 + 无重试日志 + payload 字段齐 |
| FAIL | 任一偏离 |
| INCONCLUSIVE | stub 启动失败 |

### 3.5 TC-TEMPORARY-EXHAUSTED（S11 / 临时故障耗尽 + 证明 default maxRetries=2）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；**不显式配置 `maxRetries`**（裸跑 SDK 默认值）；stub-server 启动于 `server-5xx` 模式（持续 HTTP 500 + `server_error`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | 日志出现 `[181001]` + `category=TEMPORARY`；`"Model call will retry."` **恰 2 次**（既证重试路径也证默认值已切换为 2，共 3 次调用）；禁 `[181008]/[181009]/[181010]/[181011]/[181012]`；HTTP response body error payload `details.category=="TEMPORARY"` + `details.status_code==181001` |
| PASS | 181001 + category=TEMPORARY + 重试日志恰 2 次 |
| FAIL | 任一偏离：错误码非 181001 / category 错位 / 重试次数 ≠ 2（若为 3 则证明默认值未变更，是 L2 §1.2.9 FAIL；若为 0/1 则证明 TEMPORARY 未走重试路径，是 L2 §1.2.6 FAIL） |
| INCONCLUSIVE | stub 启动失败 / 日志抓取窗口不够长 |

> **本 TC 的双重作用**：default maxRetries 3→2（L2 §1.2.9）是外部可观察行为变化，但没有直接 getter 可读；通过 "不显式配置 + 观察重试日志行数恰等于 2" 以**单一 TC 同时证明 TEMPORARY 走重试路径（L2 §1.2.6）+ 默认值已切换（L2 §1.2.9）**，与 demo README S11 定位一致。

### 3.6 TC-CONTEXT-LENGTH-EXCEEDED（S12 / 上下文超限永久类，扩展分类）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `context-length-exceeded` 模式（HTTP 400 + `code=context_length_exceeded`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | 日志出现 `[181012]`；**无** `"Model call will retry."`；禁 `[181009]/[181010]/[181011]/[181001]/[181008]`；HTTP response body error payload `details.category=="CONTEXT_LENGTH_EXCEEDED"` + `details.status_code==181012` |
| PASS | 181012 + 无重试日志 + payload 字段齐 |
| FAIL | 任一偏离 |
| INCONCLUSIVE | stub 启动失败 |

### 3.7 TC-STREAM-ERROR-FRAME（S13 / 流中错误帧 → 首段后不整次重试）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile；stub-server 启动于 `stream-error-frame` 模式（SSE 首段正常返回，首段后发 `data: {"error":...}` 帧） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | 日志出现 `[181001]`；**无** `"Model call will retry."`（首段已到达，`FirstSegmentRetryIterator` 不整次重试）；禁 `[181008]/[181009]/[181010]/[181011]/[181012]` |
| PASS | 181001 + 无重试日志（证明首段分界合同生效） |
| FAIL | 出现 `"Model call will retry."`（表明首段分界破坏，违反 L2 §1.2.7 流式重试判定线）/ 错误码非 181001 |
| INCONCLUSIVE | stub 不发错误帧（流直接完成）/ 流被底层超时截断为 181008 |

### 3.8 TC-MAX-RETRIES-NEGATIVE-REJECTED（增量 / setter 启动期 fail-fast）

| 字段 | 内容 |
|---|---|
| Given | 临时 yaml（SIT 侧 `application-sit-feat064-neg.yml`，**不改 demo 内容**）：`max-retries: -1`；stub 不启动（本 TC 不走 wire） |
| When | 启动 probe-agent fat jar，激活临时 yaml |
| Then | probe-agent 启动失败；错误日志出现 `ModelClientConfig` 的 `setMaxRetries` setter 抛出的 `IllegalArgumentException`（或 Spring Boot 配置绑定失败并包装为 `BindException`）；进程退出码非零 |
| PASS | 启动失败 + 错误日志匹配 validateMaxRetries 校验异常 |
| FAIL | 启动成功（校验被绕过，Rail 用负值 `maxRetries` 跑起来）/ 启动失败但错误原因不是 setter 校验 |
| INCONCLUSIVE | 启动失败但错误日志抓取失败（Spring Boot 吞异常） |

### 3.9 TC-DETAILS-CATEGORY-FIELD（增量 / 错误 payload `details.category` 字段聚合）

| 字段 | 内容 |
|---|---|
| Given | 已跑完 TC-RATE-LIMITED-PERSISTENT + TC-QUOTA-EXCEEDED + TC-AUTH-FAILED + TC-TEMPORARY-EXHAUSTED + TC-CONTEXT-LENGTH-EXCEEDED 五条 trace（覆盖 5 类分类） |
| When | 聚合五条 trace 的 HTTP response body error payload |
| Then | 每条 payload 的 `details.category` 字段**必填齐备**；取值 ∈ {RATE_LIMITED, QUOTA_EXCEEDED, AUTH_FAILED, TEMPORARY, CONTEXT_LENGTH_EXCEEDED}；category ↔ StatusCode 1:1 对应（RATE_LIMITED↔181009 / QUOTA_EXCEEDED↔181010 / AUTH_FAILED↔181011 / TEMPORARY↔181001 / CONTEXT_LENGTH_EXCEEDED↔181012）；程序方可按 `details.category` 做分支判定 |
| PASS | 五 trace 均满足 category 字段齐备 + 1:1 对应 |
| FAIL | 任一 trace 缺字段 / 取值不在枚举 / category ↔ StatusCode 错位 |
| INCONCLUSIVE | 任一上游 TC 自己是 INCONCLUSIVE（本 TC 相应降级） |

### 3.10 TC-RATE-LIMITED-SWITCH-OFF（增量 s14 / 限流开关 OFF 分支，L2 §1.2.9）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 **s14 profile**（`demo.timeout-probe.retry-on-rate-limited: false`，由 demo 新 jar 的 `TimeoutProbeAgentProperties` 绑定到 `ModelClientConfig.Builder.retryOnRateLimit(false)`）；stub-server 启动于 `rate-limited-persistent` 模式 |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | 日志出现 `[181009]`；`"Model call will retry."` **零次**（开关 OFF 绕过重试循环）；禁 `[181001]/[181010]/[181011]/[181012]`；HTTP response body error payload `details.status_code==181009`（不断言 `details.category` 字串，因为开关 OFF 时平台"Model call will retry"日志行不出现，category 字串随之不在日志中；错误码 181009 ↔ RATE_LIMITED 1:1 对应已由 §5.1 R6 规则确定） |
| PASS | 181009 + 无重试日志 |
| FAIL | 出现重试日志（证明开关 OFF 未生效，§1.2.9 FAIL）/ 错误码非 181009 |
| INCONCLUSIVE | s14 profile 未被识别（demo 新 jar 的 `demo.timeout-probe.*` 绑定没到位） |

### 3.11 TC-TEMPORARY-SWITCH-OFF（增量 s15 / 临时故障开关 OFF 分支，L2 §1.2.9）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 **s15 profile**（`demo.timeout-probe.retry-on-temporary: false`，绑定到 `ModelClientConfig.Builder.retryOnTemporary(false)`）；stub-server 启动于 `server-5xx` 模式 |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | 日志出现 `[181001]`；`"Model call will retry."` **零次**（开关 OFF 绕过重试循环）；禁 `[181008]/[181009]/[181010]/[181011]/[181012]`；HTTP response body error payload `details.status_code==181001`（不断言 `details.category` 字串，原因同 §3.10） |
| PASS | 181001 + 无重试日志 |
| FAIL | 出现重试日志（§1.2.9 FAIL）/ 错误码非 181001 |
| INCONCLUSIVE | s15 profile 未被识别 |

### 3.12 TC-MAX-RETRIES-ZERO-NO-RETRY（增量 s16 / maxRetries=0 预算关断 wire-observable，L2 §1.2.9）

> **归属校正**：开发侧 2026-10-26 答复把本 TC 从"§1.2.11 Rail 装配事实"剥到"§1.2.9 wire-observable 后果"。L2 §2.3 原文：`maxRetries=0 等价于关闭全部自动重试（客户端包装层与主循环护栏两层同时关闭）`。SIT 侧测的是 **wire-observable 后果**（无重试日志 + 首次失败即终错误码），不验证 Rail 装配事实本身（后者 UT 承接）。

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 **s16 profile**（`demo.timeout-probe.max-retries-override: 0`，绑定到 `ModelClientConfig.Builder.maxRetries(0)`）；stub-server 启动于 `server-5xx` 模式 |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | 日志出现 `[181001]` + `max_retries=0` 字段；`"Model call will retry."` **零次**（预算关断，首次失败即终错误码）；禁 `[181008]/[181009]/[181010]/[181011]/[181012]` |
| PASS | 181001 + max_retries=0 + 无重试日志 |
| FAIL | 出现重试日志（证明预算关断未生效）/ 错误码非 181001 / `max_retries` 字段 ≠ 0 |
| INCONCLUSIVE | s16 profile 未被识别 |

### 3.13 TC-CROSS-PROVIDER-CLASSIFICATION（增量 s17 / 跨 provider 分类挂点覆盖，L2 §1.2.1）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于默认 profile（与 s10 共用 probe-agent 配置）；stub-server 启动于 `auth-failed` mode + env `LLM_STUB_RESPONSE_SCHEMA=dashscope`（HTTP 401 + DashScope 风格响应体 `{"code":"InvalidApiKey","message":"...","request_id":"..."}`） |
| When | SIT driver `POST /v1/query` body `{"conversation_id":"sit-<uuid>","message":"ping"}` |
| Then | 日志出现 `[181011]` + `category=AUTH_FAILED`；`"Model call will retry."` 零次（永久类短路）；HTTP response body error payload `details.category=="AUTH_FAILED"` + `details.status_code==181011` |
| PASS | 181011 + AUTH_FAILED + 无重试日志 + payload 字段齐 |
| FAIL | 挂点吞掉状态码退化为 181001（挂点漏接证据） / category 非 AUTH_FAILED / 错误码非 181011 |
| INCONCLUSIVE | stub schema 开关未被识别（新 stub jar 的 `LLM_STUB_RESPONSE_SCHEMA` 环境变量未绑定） |

> **覆盖面声明**：本 TC 以 AUTH_FAILED (401) 为代表性场景——R6 规则按状态码直判，天然 schema-agnostic；证明"挂点不会在陌生 schema body 上崩溃或静默吞掉状态码"。更强的 schema-dependent 场景（如 CONTEXT_LENGTH_EXCEEDED 需从错误体关键词 R7 识别）由 UT `RuleBasedModelErrorClassifierTest` provider 分支承接（开发侧 2026-10-26 ack 的覆盖面取舍）。

---

## 4. 状态跟踪

| TC | 当前状态 | 阻塞 | 待办 |
|---|---|---|---|
| TC-RATE-LIMITED-PERSISTENT | runnable | — | fixture 落地 + 实现 |
| TC-RATE-LIMITED-RECOVER | runnable | — | fixture 落地 + 实现 |
| TC-QUOTA-EXCEEDED | runnable | — | fixture 落地 + 实现 |
| TC-AUTH-FAILED | runnable | — | fixture 落地 + 实现 |
| TC-TEMPORARY-EXHAUSTED | runnable | — | fixture 落地 + 实现 + 确认 probe 不显式配 `max-retries` |
| TC-CONTEXT-LENGTH-EXCEEDED | runnable | — | fixture 落地 + 实现 |
| TC-STREAM-ERROR-FRAME | runnable | — | fixture 落地 + 实现 |
| TC-MAX-RETRIES-NEGATIVE-REJECTED | runnable | — | fixture 落地 + 临时 yaml `application-sit-feat064-neg.yml` 制造负值 |
| TC-DETAILS-CATEGORY-FIELD | runnable | — | 依赖 5 条前置 TC 完成 |
| TC-RATE-LIMITED-SWITCH-OFF | runnable（等 demo s14 jar） | demo 下一轮 push（新增 `demo.timeout-probe.retry-on-rate-limited` 绑定） | fixture 落地 + 实现 + 等 demo s14 fat jar + scripts 到位 |
| TC-TEMPORARY-SWITCH-OFF | runnable（等 demo s15 jar） | demo 下一轮 push（新增 `demo.timeout-probe.retry-on-temporary` 绑定） | fixture 落地 + 实现 + 等 demo s15 fat jar + scripts 到位 |
| TC-MAX-RETRIES-ZERO-NO-RETRY | runnable（等 demo s16 jar） | demo 下一轮 push（新增 `demo.timeout-probe.max-retries-override` 绑定） | fixture 落地 + 实现 + 等 demo s16 fat jar + scripts 到位 |
| TC-CROSS-PROVIDER-CLASSIFICATION | runnable（等 demo s17 stub jar） | demo 下一轮 push（新增 stub env `LLM_STUB_RESPONSE_SCHEMA=dashscope` 绑定） | fixture 落地 + 实现 + 等 demo s17 stub fat jar + scripts 到位 |

### 保留项（deferred，暂不构造）

- **TC-USER-CANCELLED-\***（L2 §1.2.8 用户取消不重试 + §1.2.14 取消识别规则显式定义 的 C1-C5 cancellation）：待 FEAT-058 MUST#11 "cancel 可观察出口" 裁定落地（见 SIT-CLARIFY-REPLY-must11-cancel-definition.md）；UT-C10 / UT-R06 当前已承接 "CancellationException 恢复 + 不计熔断 + 不整次重试" 的内部断言。L1 本身在 UT 层已 ✅，SIT 延迟不阻塞特性合入。
- **TC-CLASSIFIED-FAILURE-NOT-COUNTED**（L2 §1.2.16 熔断计数消费分类，**SHOULD**）：等 agent-core-java 平台开放 `ModelCircuitBreaker` 观测面（开发侧 2026-10-26 答复给出三选一方案：公开 `isOpen()`/`consecutiveFailures()` modifier / breaker 注入 Spring bean / Micrometer gauge；偏好方案 1）。平台 issue 落地后 TC 构造策略按 §1.2.16 原文——驱动若干次 QUOTA_EXCEEDED / AUTH_FAILED / CONTEXT_LENGTH_EXCEEDED 分类失败，断言 `state=CLOSED` + `failureCount=0`（RATE_LIMITED / TEMPORARY **计入**熔断，不是"不计入"的构造标的）；UT `ModelCircuitBreakerTest.onFailure` 当前承接。SHOULD 等级，SIT 延迟不阻塞特性合入。

### 已知设计偏差（不阻塞）

- **D1（L2 vs 代码小漂移）**：`RuleBasedModelErrorClassifier` 的 R5 keyword set 代码常量与 L2 §5.1 文本枚举略有差异（见 SIT 本地 FEAT-064 对齐表）；由 UT 的 `RuleBasedModelErrorClassifierTest` R5 分支以代码口径为准承接；SIT 不构造反证 TC。
- **D2-D4**：L1 口径补强，已入 L2，不影响 SIT 实现。

### Fixture 扩展清单（相对 FEAT-058）

| Fixture | 新增 API |
|---|---|
| `SutLogTailer` | `countOfRetryLog()` / `countOfStatusCode(int)` / `categoryOfLastError()` |
| `ErrorEnvelopeAsserter` | `assertCategory(ModelErrorCategory)` / `assertStatusCode(int)` 覆盖五码 / `assertRetryLogCount(SutLogTailer, int)` / `assertNoRetryLog(SutLogTailer)` |
| `TimeoutProbeHttpClient` | `errorDetails().get("category")` 可取（已有的 Map 返回天然支持，无需 API 变更） |
| `TimeoutDemoLauncher` | 无需变更（FEAT-064 TC 全部用默认 profile，不传 `--spring.profiles.active`） |
