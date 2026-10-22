---
feature_id: FEAT-058
feature_title: 模型调用三层超时分层（connect / header_wait / stream_idle）
sut: timeout-tiering-model-call-demo 的 timeout-probe-agent（ReActAgent，openjiuwen 范式，fat jar 独立运行；端口 18092 + llm-stub-server 端口 18094），对端用同 demo 的 llm-stub-server 可控制造 6 种 stall 模式；FEAT-058 测的是传输层超时，真实 LLM 无法可控卡 header / idle，所以 stub 是必要对端而非 mock 替身
scope: 本档覆盖 FEAT-058 SIT 侧黑盒可观察（`POST /v1/query` body + SUT 日志 `[181008] model call timeout at <PHASE>` 行 + HTTP response body 的 error payload `details.{phase, timeout_seconds, layer, stream}` 四字段）；UT 侧已由 dev team 交付 `OpenAIModelTimeoutTieringTest` + `ModelHttpClientsTest` + `ModelTimeoutConfigTest` + 四个 client 子类（OpenAiCompatible / DashScope / SiliconFlow）timeout 单测，本档不复制。
status: pending-implementation
status_note: 测试方案与子用例骨架已沉淀；fixture 代码（TimeoutDemoLauncher / TimeoutProbeHttpClient / SutLogTailer / ErrorEnvelopeAsserter）与用例类未落地；fat jar 已由 demo 侧 2026-10-22 本机构建完成（timeout-probe-agent ~180MB / llm-stub-server ~22MB），demo HANDOFF 自冒烟 5/7 PASS，S3/S4 FAIL 已归因到上游 bug（本档以 INCONCLUSIVE 兜底，blocked_by B1 跟踪）。
owner: TBD
tags: [integration, reactagent, feat-058, model-timeout-tiering]
depends_on:
  - timeout-probe-agent-0.1.0.jar 已就绪：`agent-solution/common/example/timeout-tiering-model-call-demo/timeout-probe-agent/target/`（~180 MB，2026-10-22 本机构建）
  - llm-stub-server-0.1.0.jar 已就绪：`.../llm-stub-server/target/`（~22 MB）
  - 不需要真实 LLM 凭据——demo 全部打本机 stub（127.0.0.1:18094）
  - `unset HTTP_PROXY HTTPS_PROXY`（stub 在本机 loopback，企业代理会干扰）
  - 跨 provider 等价性场景（TC-UNIFIED-STACK-COVERAGE）：`LLM_PROVIDER` env + 对应 provider 的 API_KEY 占位（stub 不校验）
  - 端口 18092（probe-agent）/ 18094（llm-stub-server）可用
  - S1 场景的不可达对端地址：默认 `http://10.255.255.1:81`；若本机网络下该地址不是黑洞，覆盖为 `LLM_API_BASE_UNREACHABLE=http://192.0.2.1:81`（TEST-NET-1）
related_docs:
  - 本方案 testplan（权威位置）：Technical-AF/docs:develop/04-testplan/FEAT-058-model-call-timeout-tiering.md（当前分支 feat/feat-058-testplan，PR 待开）
  - 特性文档：develop/02-features/FEAT-058-model-call-timeout-tiering.md（updated 2026-10-20，12 条 MUST；!340 已把 "阶段信息三段口径" 由 SHOULD 升为 MUST#12）
  - L2 设计：develop/03-architecture/L2-Low-Level-Design/agent-core/Feat-Func-058-model-call-timeout-tiering.md（updated 2026-10-19；附录 C "推理亲和栈下线" 显式声明）
  - 三方对齐表：agent-solution/FEAT-058-alignment-table.md（updated 2026-10-20；11/12 ✓ / 1 ⚠；附录 A/B/C/D 完备）
  - L1 刷新清单：SIT 本地评审 note（SIT → L1 文档负责人，不入仓；SIT 后续不等 L1 刷新完成）
  - 代码口径：agent-core-java@origin/develop @ 42b1fcfe；关键类 `ModelClientConfig` / `ModelTimeoutResolver` / `ModelHttpClients` / `IdleBoundInputStream` / `IdleGuardedBodyReader` / `TimeoutPhase` / `ModelTimeoutErrorFactory` / `StatusCode.MODEL_CALL_TIMEOUT(181008)` / `OpenAIModelClient` / `ModelCircuitBreaker`
  - demo README：agent-solution/common/example/timeout-tiering-model-call-demo/README.md（7 场景 S0..S6 + stub 6 种 stall 模式 + 轨迹断言矩阵 + MUST#8 偏差说明）
  - demo HANDOFF：.../HANDOFF.md（本机冒烟结果 5/7 PASS + S3/S4 上游归因）
  - demo trace-asserter（辅助冒烟锚，SIT 不依赖）：.../scripts/trace-asserter.sh
  - 已知阻塞 B1 上游 issue draft：.../UPSTREAM-ISSUE-DRAFT.md（OpenAIModelClient.buildRequest HttpRequest.timeout wire-up）
  - 已知非范围 B2 上游 issue draft：.../UPSTREAM-ISSUE-DRAFT-INTELLI-ROUTER-COVERAGE.md（IntelliRouterModelClient 不走三层超时合同；live-exposure latent）
---

# FEAT-058 — 模型调用三层超时分层 用例设计

> **一句话**：以 demo `timeout-probe-agent` 为 SUT、`llm-stub-server` 为可控对端，把 FEAT-058 §2 的 12 条 MUST + L2 的 `ModelTimeoutResolver` + `IdleBoundInputStream` + `ModelTimeoutErrorFactory` 实现响应 + 对齐表附录 B 的 MUST#8 单栈闭环，映射为可在 SIT 侧黑盒断言的子用例。demo 覆盖 **S0..S6 共 7 个场景**，SIT 在此基础上追加 4 条增量 TC（per-call override / range validation / 跨 provider 等价性 / 错误 payload 四字段聚合）。

> **状态含义**：
> - **runnable**：fixture 就绪 + 可直接落地实现
> - **partial**：核心路径可测，某些维度受 wire 层边界限制（layer 字段不进日志，走 HTTP body 取）
> - **blocked_by_upstream**：被上游 bug 阻塞；上游 PR 合入后转 runnable
> - **deferred**：产品侧未就绪或 scope out（如 IntelliRouter latent 覆盖面）

---

## 0. 执行前置快速清单

- [ ] `timeout-probe-agent-0.1.0.jar` 存在，路径：`agent-solution/common/example/timeout-tiering-model-call-demo/timeout-probe-agent/target/`
- [ ] `llm-stub-server-0.1.0.jar` 存在，路径：`.../llm-stub-server/target/`
- [ ] 两个 fat jar MD5 校验（demo HANDOFF 本机参考值；本机重打会不同是正常）
- [ ] `unset HTTP_PROXY HTTPS_PROXY`（stub 在 127.0.0.1:18094）
- [ ] 不可达对端地址判定：`curl --connect-timeout 2 http://10.255.255.1:81` 应 timeout；若本机下不是黑洞，`export LLM_API_BASE_UNREACHABLE=http://192.0.2.1:81`
- [ ] 端口 18092 / 18094 空闲
- [ ] 跨 provider 场景（TC-UNIFIED-STACK-COVERAGE）前：`export LLM_PROVIDER=OpenAI` / `DashScope` / `SiliconFlow`；API key 占位（stub 不校验）

---

## 1. 覆盖矩阵（12 条 MUST → 子用例）

对应特性文档 §2 的 12 条 MUST（与 L2 §1.2 能力矩阵、对齐表 11/12 ✓ 一一对应）。SIT 侧状态填 "runnable" 表示方案就绪、fixture 待落地；填 "blocked_by_upstream" 表示方案就绪但上游 bug 挡路；填 "deferred" 表示 SIT 不承接。

| # | FEAT-058 MUST | 本档 TC ID | 覆盖场景 | 状态 | UT 承接（不复制） | 备注 |
|---|---|---|---|---|---|---|
| 1 | 连接超时分层（独立 + `[5,15]s` + 默认 10s） | `FEAT-058.connect-timeout-independent` | S1 | runnable | `OpenAIModelTimeoutTieringTest` connect 分支 | `connect-timeout=5s` 下边界 + TEST-NET-1 黑洞 |
| 2 | 响应头到达解除总计时 | `FEAT-058.header-wait-releases-total-timer` | S2（legacy 路径） | runnable | `OpenAIModelTimeoutTieringTest` header-wait 分支 | 由 TC-HEADER-WAIT-LEGACY 覆盖 header-wait 独立触发；MUST#2 "body 不受全程约束" 面由 TC-NO-TOTAL-CAP-STREAM 覆盖（**blocked_by B1**） |
| 3 | 数据段空闲超时放宽（独立 + `[300,900]s` + 默认 600s） | `FEAT-058.stream-idle-independent` | S3 | **blocked_by_upstream** | `OpenAIModelTimeoutTieringTest` idle 分支 | 上游 B1 bug：`HttpRequest.timeout` 当总时长执行，stream 被 header_wait 截断，AFTER_FIRST_SEGMENT 无法触发 |
| 4 | 不设全程总时长硬顶 | `FEAT-058.no-total-time-cap` | S4 | **blocked_by_upstream** | `OpenAIModelTimeoutTieringTest` long-answer 分支 | 上游 B1 bug：asserter 过线但语义被遮蔽（stream 死于 request-total 而非自然完成）；SIT 边界无法 wire 层分辨死因 |
| 5 | 空闲超时检出死链 | `FEAT-058.idle-timeout-detects-dead-link` | S3 | **blocked_by_upstream** | `OpenAIModelTimeoutTieringTest` idle + `IdleBoundInputStream` 单测 | 与 MUST#3 同根因；上游修复后一起转 runnable |
| 6 | 分层超时配置项（独立 + 默认回落） | `FEAT-058.layered-config-with-default-fallback` | S0 + S2（legacy 兼容） | runnable | `ModelTimeoutConfigTest` + resolver 回落链 UT | TC-DEFAULT-BASELINE 覆盖未声明回落；TC-HEADER-WAIT-LEGACY 覆盖 legacy `timeout` 兼容 |
| 7 | 单次调用超时参数接线（per-call idle override） | `FEAT-058.per-call-idle-override` | 增量（demo 无直接场景） | **blocked_by_upstream** | `OpenAIModelTimeoutTieringTest` per-call 分支 + core+memory 全部 `resolvePerCallIdleTimeout` 调用点 UT（PR!400 已覆盖） | 增量 TC-PER-CALL-IDLE-OVERRIDE；依赖 AFTER_FIRST_SEGMENT 可观察，blocked_by B1 |
| 8 | 仓内全部模型 HTTP 栈采用同一分层语义 | `FEAT-058.unified-stack-tiering-across-clients` | 增量（demo README 建议） | runnable | 五个 client 子类（OpenAI/OpenAiCompatible/DashScope/SiliconFlow/DeepSeek）各自 timeout 单测 | 增量 TC-UNIFIED-STACK-COVERAGE；按对齐表附录 B 替代命名：多 client 子类共享 resolver；走 legacy header-wait 路径，不依赖 B1 |
| 9 | 阻塞调用超时语义一致 | `FEAT-058.blocking-same-tiering-semantics` | S5 | runnable | `OpenAIModelTimeoutTieringTest` blocking 分支 | 阻塞路径 `IdleGuardedBodyReader` 承载 idle；本 TC 覆盖 header-wait 侧 |
| 10 | 超时错误表面可区分（三类错误 + 181008） | `FEAT-058.error-surface-distinguishable` | S1 + S2 + S3 + S5 | partial | `OpenAIModelTimeoutTieringTest` details assertion | S3 blocked_by B1；非 S3 的三类（connect / header-wait / blocking）全部 runnable；TC-ERROR-DETAILS-PAYLOAD 聚合断言 |
| 11 | 超时与取消区分 | `FEAT-058.cancel-not-timeout` | S6 | runnable | `OpenAIModelTimeoutTieringTest` + `ModelCircuitBreakerTest` | caller `--max-time 3` 取消 + stub `long-hold-for-cancel`；弱层断言 "无 [181008] 虚警" |
| 12 | 超时错误阶段信息三段口径（phase/timeout_seconds/layer/stream） | `FEAT-058.error-details-four-fields` | S1 + S2 + S5（S3 blocked） | partial | `OpenAIModelTimeoutTieringTest` details assertion | 由 TC-ERROR-DETAILS-PAYLOAD 聚合断言；S3 维度 blocked_by B1 |

### 非范围显式列出

- **IntelliRouterModelClient 覆盖面**（B2，live-exposure latent）：代码里是注册 provider，但走内部 `ReliableRouter` 不走 `ModelHttpClients` + `IdleBoundInputStream`，不读 `connect_timeout_seconds` / `stream_idle_seconds`，异常统一包为 `MODEL_CALL_FAILED(181001)` 不是 `MODEL_CALL_TIMEOUT(181008)`。当前 SDK 无 `RouterFactory` 驱动绑定，production path 不可达（fail-fast 在 `MODEL_SERVICE_CONFIG_ERROR` 之前）。**2026-10-22 开发侧反馈**：方向是刷 L1/L2 显式 carve-out（"MUST#8 的 '仓内全部模型 HTTP 栈' 不含推理亲和栈 `IntelliRouterModelClient`"），SIT 永不构造 TC-INTELLI-ROUTER-TIERING-COVERAGE，本行作为永久 non-scope 记录保留；L1/L2 刷新由上游文档负责人推进。
- **production 适配器 `JiuwenCoreAgentHandler.createCatalogModel` 透传**（对齐表附录 C 第 1 行）：当前只透传 legacy `timeout`，未透传 `connectTimeout` / `streamIdleSeconds`；但在 "未声明 → core 回落默认" 事实下仍满足 MUST#6。SIT 不构造失败型 TC；runtime 补齐后由 demo 工厂的现有透传路径天然覆盖。

### L1 回灌挂钩（PR#354 评审 3 回灌）

- 当前 L1 10-20 版 §1/§2 MUST#8/§3/§5.1.4/§6/§7.4 UT-I01~I04/§9.1 仍保留 "两条 HTTP 栈" 原文；本档以 L2 附录 B 的 "实现路径与 L1 假设差异" 为锚点绕行通过，并在 §4 B2 把 IntelliRouter 永久 carve-out。
- **挂钩口径**：本档不阻塞合入，但建议 L1 文档负责人把 MUST#8 "两栈叙事" 与 UT-I01~I04 作为 D1 必修条目正式立项（原文建议见 SIT 本地评审 note，未入仓）。L1 若长期不刷新，04-testplan 与 02-features 会在"亲和栈"维度持续不自洽——SIT 不承接 L1 刷新，但本挂钩作为可审计的转交记录。

---

## 2. 承载载体与 Fixture

### 2.1 载体选型

| 载体 | 对应 demo 产物 | 职责 |
|---|---|---|
| SUT (probe-agent) | `timeout-probe-agent-0.1.0.jar`（端口 18092） | ReActAgent 真实 openjiuwen 范式；装配 agent-core-java `ModelTimeoutResolver` + `ModelHttpClients` + `IdleBoundInputStream`；Spring profile `s0..s6` 切换三层超时覆写（profile 定义见 demo `application-sN.yml`，SIT **不改 demo 内容**，原样复用） |
| 对端 (llm-stub) | `llm-stub-server-0.1.0.jar`（端口 18094） | OpenAI 兼容 `/v1/chat/completions`；6 种 stall 模式：`ok` / `header-wait-silent` / `first-segment-idle` / `after-first-segment-idle` / `long-answer-heartbeat` / `idle-silent-blocking` / `long-hold-for-cancel`；由 `LLM_STUB_DEFAULT_MODE` env 切换 |

### 2.2 Fixture 落点（🆕 本仓待实现）

| Fixture | 职责 | 位置建议（SIT 仓） |
|---|---|---|
| `TimeoutDemoLauncher` | 启停两个 fat jar；端口 ready probe（18092 / 18094）；注入 `LLM_STUB_DEFAULT_MODE` + `--spring.profiles.active=sN` + S1 的 `LLM_API_BASE_UNREACHABLE` 覆写；超时 kill | `src/test/java/com/huawei/ascend/sit/fixture/.../TimeoutDemoLauncher.java` |
| `TimeoutProbeHttpClient` | 底层 `java.net.http.HttpClient` 直发 `POST http://127.0.0.1:18092/v1/query`，body `{"conversation_id":"sit-<uuid>","message":"<prompt>"}`（demo API 10-19 起 `conversation_id` 为必填字段）；返回原始 JSON；暴露 `resultType()` / `output()` / `errorStatusCode()` / `errorDetails()` 返回 Map 包含 `phase/timeout_seconds/layer/stream` 四字段 | `src/test/java/.../TimeoutProbeHttpClient.java` |
| `SutLogTailer` | tail probe-agent 日志，解析 `[181008] model call timeout at <PHASE>, effective timeout <N>s, reason: ...` 行 → `TimeoutErrorLogRecord(phase, timeoutSeconds, reason)`；注意 `layer` + `stream` 字段**不进日志**，走 HTTP body 取路径 | `src/test/java/.../SutLogTailer.java`（可复用 FEAT-057 已有 SutLogTailer 的 tail 骨架） |
| `ErrorEnvelopeAsserter` | 对 HTTP response body error payload 做结构化断言：`assertStatusCode(181008)` / `assertPhase(TimeoutPhase)` / `assertLayer(String)` / `assertTimeoutSeconds(double, ±tolerance)` / `assertStream(boolean)` / `assertAllFourFieldsPresent()` / `classify(expected, actual) -> {PASS, FAIL, INCONCLUSIVE}` | `src/test/java/.../ErrorEnvelopeAsserter.java` |

### 2.3 Spring profile 到场景映射（**SIT 复用 demo profile，不新造**）

| TC | demo profile | demo stub mode | 关键配置覆写 |
|---|---|---|---|
| TC-DEFAULT-BASELINE | `s0` | `ok` | 不配 FEAT-058 字段 |
| TC-CONNECT-TIMEOUT | `s1` | 任意（不走到对端） | `connect-timeout=5s` + api-base=TEST-NET-1 黑洞 |
| TC-HEADER-WAIT-LEGACY | `s2` | `header-wait-silent` | `timeout=8s`（legacy 路径） |
| TC-AFTER-FIRST-SEGMENT-IDLE | `s3` | `after-first-segment-idle` | `stream-idle-seconds=300s`；stub 首块后停 310s |
| TC-NO-TOTAL-CAP-STREAM | `s4` | `long-answer-heartbeat` | `timeout=10s`；stub 每 1.5s chunk，总 15s |
| TC-BLOCKING-SAME-SEMANTICS | `s5` | `idle-silent-blocking` | `timeout=8s`（header-wait） |
| TC-CANCEL-NOT-TIMEOUT | `s6` | `long-hold-for-cancel` | 默认超时；caller 侧 `HttpRequest.timeout(3s)` |
| TC-ERROR-DETAILS-PAYLOAD | 聚合 s1/s2/s5 | 聚合 | 跨 TC 聚合断言 |
| TC-PER-CALL-IDLE-OVERRIDE | **新 profile** `s3-per-call`（**待开发侧补**，或 SIT 侧用 override 注入） | `after-first-segment-idle` | client 级 `stream-idle-seconds=600s` + per-call `ModelInvokeOptions.timeout=300s` 或 `ReActAgentConfig.modelStreamIdleSeconds=300` |
| TC-RANGE-VALIDATION | **新 profile** `s-range-invalid`（**待开发侧补**，或 SIT 用临时 yaml） | n/a（不启动 stub） | `connect-timeout=3s`（低于 5s）或 `stream-idle-seconds=1200s`（高于 900s）；期望 probe-agent 启动失败 |
| TC-UNIFIED-STACK-COVERAGE | 复用 `s2`（header-wait 快速路径） | `header-wait-silent` | `LLM_PROVIDER=OpenAiCompatible` / `DashScope` / `SiliconFlow` / `DeepSeek` env 切换，其余同 s2 |

> **开发侧需要补的 profile**：`s3-per-call` 与 `s-range-invalid` 两份；若开发侧不便补，SIT 侧可以用 `application-sit-feat058-*.yml` 临时写入本仓 test resources 作为过渡（**不改 demo 内容**），但首选是开发侧在 demo 内补齐，便于 demo 冒烟同步覆盖这两条增量断言。

---

## 3. 用例细档（G/W/T + PASS/FAIL/INCONCLUSIVE）

### 3.1 TC-DEFAULT-BASELINE（兼容基线 / 默认值可见）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 `s0` profile（无 FEAT-058 字段覆写）；stub-server 启动于 `ok` 模式 |
| When | SIT driver `POST /v1/query` body `{"message":"ping"}` |
| Then | HTTP 200；`result_type==answer`；无 `[181008]` 日志行；probe-agent 启动日志出现 `Started TimeoutProbeAgent`；config dump 行可确认 SDK 默认 `connect=10s` / `streamIdleSeconds=600s` |
| PASS | 上述 Then 全部满足 |
| FAIL | 出现 `[181008]` 或 config dump 显示非默认值 |
| INCONCLUSIVE | probe-agent 或 stub-server 启动失败（端口冲突 / JDK 版本错） |

### 3.2 TC-CONNECT-TIMEOUT（连接层独立）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 `s1` profile；配置覆写 `connect-timeout=5s` + `api-base=http://192.0.2.1:81`（TEST-NET-1 黑洞）；stub-server 启动（但本场景不走到） |
| When | `POST /v1/query` body `{"message":"ping"}` |
| Then | 日志出现恰一次 `[181008]` + phase=`BEFORE_CONNECT`；禁出现 `BEFORE_FIRST_SEGMENT` / `AFTER_FIRST_SEGMENT`；HTTP response body error payload：`details.phase=="BEFORE_CONNECT"` + `details.layer=="connect"` + `details.timeout_seconds==5.0`（±0.5s 容差）；`details.stream` 字段存在（值不作强断言） |
| PASS | 四字段齐备 + phase/layer 对应 + timeout_seconds 匹配 |
| FAIL | 任一字段缺失 / phase ≠ BEFORE_CONNECT / layer ≠ connect / timeout_seconds 不匹配 |
| INCONCLUSIVE | TEST-NET-1 本机下不是黑洞（预检步骤发现）→ 换 `LLM_API_BASE_UNREACHABLE` 后重试一次仍不通 |

### 3.3 TC-HEADER-WAIT-LEGACY（响应头空档 / legacy 路径）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 `s2` profile；配置覆写 `timeout=8s`（显式写 legacy 字段使 `isTimeoutExplicit=true`，走 `resolveHeaderWaitSeconds`）；stub-server `header-wait-silent` 模式（接受 TCP 但不写响应头，默认 60s silence） |
| When | `POST /v1/query` body `{"message":"ping"}` |
| Then | 恰一次 `[181008]` + phase=`BEFORE_FIRST_SEGMENT`；禁 `BEFORE_CONNECT` / `AFTER_FIRST_SEGMENT`；error payload：`details.phase=="BEFORE_FIRST_SEGMENT"` + `details.layer=="header_wait"` + `details.timeout_seconds==8.0`（±0.5s） |
| PASS | 四字段齐备 + 对应关系 + 数值匹配 |
| FAIL | 任一字段偏离 |
| INCONCLUSIVE | stub 启动失败 / 日志抓不到 `[181008]` 但 HTTP 500 出现（走到了别的错误码路径） |

### 3.4 TC-AFTER-FIRST-SEGMENT-IDLE（首块后 idle 独立） — **blocked_by_upstream B1**

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 `s3` profile；`stream-idle-seconds=300s`（下边界）；stub-server `after-first-segment-idle` 模式，首块后停顿 310s |
| When | `POST /v1/query` body `{"message":"ping"}`，等待 ≥ 320s |
| Then（期望） | 恰一次 `[181008]` + phase=`AFTER_FIRST_SEGMENT`；禁 `BEFORE_CONNECT`；error payload：`details.phase=="AFTER_FIRST_SEGMENT"` + `details.layer=="stream_idle"` + `details.timeout_seconds==300.0`（±1s）+ `details.stream==true` |
| Then（当前事实） | 日志出现 `[181008]` 但 phase=`BEFORE_FIRST_SEGMENT`（因 `HttpRequest.timeout` 把 stream 整段截在 header_wait 时间点，JDK 把它当 "总响应时长"）；asserter 的 "必须出现 AFTER_FIRST_SEGMENT" 失败 |
| PASS | 期望满足（上游修复后） |
| FAIL | **本 TC 当前无 FAIL 判据**（事实已被上游 bug 遮蔽） |
| INCONCLUSIVE | 当前默认立场；`blocked_by: upstream agent-core-java HttpRequest.timeout wire-up`（见 demo UPSTREAM-ISSUE-DRAFT.md）；上游 PR 合入后恢复为 PASS/FAIL 判据 |

### 3.5 TC-NO-TOTAL-CAP-STREAM（长回答不受总时长硬顶） — **blocked_by_upstream B1**

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 `s4` profile；`timeout=10s`（legacy header-wait）；stub-server `long-answer-heartbeat`，每 1.5s 一个 chunk，总 15s |
| When | `POST /v1/query` body `{"message":"ping"}`，等待 ≥ 20s |
| Then（期望） | HTTP 200 + `result_type==answer`；无 `[181008]`；stream 自然完成 |
| Then（当前事实） | asserter 过线（无 `[181008]`），但语义被遮蔽：stream 实际被 `HttpRequest.timeout`（当 "总响应时长" 执行）杀，而非自然完成；SIT 边界无法 wire 层分辨死因（需读 SDK 内部字段） |
| PASS | 期望满足**且**能证明 stream 自然完成（上游修复后，可通过 debug 端点 / event 新增字段证明） |
| FAIL | 出现 `[181008]` |
| INCONCLUSIVE | 当前默认立场；`blocked_by B1`；上游修复后恢复判据 |

### 3.6 TC-BLOCKING-SAME-SEMANTICS（阻塞路径同口径）

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 `s5` profile；`timeout=8s`（legacy header-wait）；stub-server `idle-silent-blocking` 模式（200 OK 阻塞 Content-Type 头） |
| When | `POST /v1/query` body `{"message":"ping"}` |
| Then | 恰一次 `[181008]` + phase=`BEFORE_FIRST_SEGMENT`；禁 `BEFORE_CONNECT` / `AFTER_FIRST_SEGMENT`；error payload：`details.phase=="BEFORE_FIRST_SEGMENT"` + `details.layer=="header_wait"` + `details.timeout_seconds==8.0`（±0.5s）+ `details.stream==false`（阻塞路径关键字段） |
| PASS | 阻塞路径表面与流式一致 + `stream==false` |
| FAIL | 任一字段偏离 / `stream==true`（阻塞被误识别为流式） |
| INCONCLUSIVE | stub 启动失败 |

### 3.7 TC-CANCEL-NOT-TIMEOUT（调用方取消 ≠ 超时） — **弱层回归，MUST#11 判定力归 UT**

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 `s6` profile（默认超时宽泛，idle=600s）；stub-server `long-hold-for-cancel` 模式（SSE 长流缓慢 heartbeat） |
| When | SIT driver 用 `HttpRequest.newBuilder().timeout(Duration.ofSeconds(3))` 发请求（或等价 `curl --max-time 3`） |
| Then | caller 侧请求被取消（`HttpTimeoutException` on driver side）；SUT 日志**无** `[181008]` |
| 弱层说明 | 本 TC 观察窗口（3s）远小于 SUT 内部 idle 窗口（600s），断言"无 `[181008]`"在观察窗口内恒真——即使取消未被 SUT 正确识别为"非超时"，窗口内也不会出现 181008 行。**MUST#11 "超时与取消区分" 的判定力归 UT**（`OpenAIModelTimeoutTieringTest` + `ModelCircuitBreakerTest`，覆盖 `ModelCircuitBreaker.isConnectFailure` 不记取消的内部分支）。SIT 本 TC 仅作弱层端到端回归，防止"取消被误识别为超时且立刻射出 181008"这类硬回归。 |
| PASS | SUT 日志无 `[181008]`（弱层保护生效） |
| FAIL | SUT 日志出现 `[181008]`（取消被误识别为超时并立即射出） |
| INCONCLUSIVE | caller 侧 timeout 没触发（stub 响应太快） / 日志抓取窗口不够长（扩窗 retry 一次） |

### 3.8 TC-ERROR-DETAILS-PAYLOAD（错误 payload 四字段聚合）

| 字段 | 内容 |
|---|---|
| Given | 已跑完 TC-CONNECT-TIMEOUT + TC-HEADER-WAIT-LEGACY + TC-BLOCKING-SAME-SEMANTICS 三条 trace |
| When | 聚合三条 trace 的 HTTP response body error payload |
| Then | 每条 payload 的 `details.{phase, timeout_seconds, layer, stream}` **四字段必填齐备**；三 phase 对应三 layer（`BEFORE_CONNECT ↔ connect` / `BEFORE_FIRST_SEGMENT ↔ header_wait`）；程序方可按 `details.phase` 做分支判定（三 phase 字面值互斥） |
| PASS | 三 trace 均满足 |
| FAIL | 任一 trace 缺字段 / 对应关系错位 |
| INCONCLUSIVE | 任一上游 TC 自己是 INCONCLUSIVE（则本 TC 相应降级），**但**由于本 TC 不依赖 AFTER_FIRST_SEGMENT 维度，B1 不影响本 TC |

### 3.9 TC-PER-CALL-IDLE-OVERRIDE（增量 / per-call 不污染 client） — **blocked_by_upstream B1**

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于**新 profile** `s3-per-call`（需开发侧补或 SIT 临时 yaml）：**client 级 `stream-idle-seconds=600s`**（持久配置）；per-call 入口 `ModelInvokeOptions.timeout=300s`（仅第一次调用携带）；stub-server `after-first-segment-idle` 首块后停 310s |
| When | 1) **带 per-call override=300s** 发第一次 `POST /v1/query`；2) **不带 override** 发第二次（client 级 600s 回退生效） |
| Then（期望） | 第一次 trace：出现 `[181008]` + `details.timeout_seconds==300.0`（per-call override 生效，300s < 310s stall → 触发）；第二次 trace：HTTP 200 + `result_type==answer` + **无** `[181008]`（client 级 600s > 310s stall → 自然完成，证明 per-call 已释放、client 级配置未被污染） |
| PASS | 第一次 timeout_seconds=300.0 + 第二次成功完成 |
| FAIL | 第一次 timeout_seconds≠300（override 未生效）或第二次出现 `[181008]`（per-call 污染 client 配置，client 级被压低到 300） |
| INCONCLUSIVE | 当前默认立场；`blocked_by B1`（第一次依赖 AFTER_FIRST_SEGMENT 可观察）|

> **PR#354 评审 1 回灌**：原设计把 per-call override（`ModelInvokeOptions.timeout`）与 agent 级常驻字段（`ReActAgentConfig.modelStreamIdleSeconds`）在同一 TC 内叠加，导致"第二腿期望 timeout_seconds=300"的断言歧义。本版 TC 收窄为**仅覆盖 `ModelInvokeOptions.timeout` 的 per-call 语义**（agent 级 `modelStreamIdleSeconds` 的持久字段验证由 UT 的 `ReActAgentConfig` 单测承接），第二腿改为"client 级 600s 回退、stall 310s 不触发超时"，判据无歧义。

### 3.10 TC-RANGE-VALIDATION（配置区间 fail-fast）

| 字段 | 内容 |
|---|---|
| Given | 临时 yaml：`connect-timeout=3s`（低于 `[5,15]` 下界）**或** `stream-idle-seconds=1200s`（高于 `[300,900]` 上界）；stub 不启动（本 TC 不走 wire） |
| When | 启动 probe-agent fat jar |
| Then | probe-agent 启动失败；错误日志出现 `ModelClientConfig$Builder.build()` 的区间校验异常；进程退出码非零 |
| PASS | 启动失败 + 错误日志匹配 |
| FAIL | 启动成功（校验被绕过） / 启动失败但错误原因不是区间校验 |
| INCONCLUSIVE | 启动失败但错误日志抓取失败（可能 Spring Boot 层把异常吞了） |

### 3.11 TC-UNIFIED-STACK-COVERAGE（增量 / 跨 provider 等价性）

| 字段 | 内容 |
|---|---|
| 前置 | **demo 侧需先实证 5 provider（OpenAI / OpenAiCompatible / DashScope / SiliconFlow / DeepSeek）均可打通 llm-stub-server**（URL 路径 + 协议差异），由 demo HANDOFF 冒烟表给出可达性清单；DeepSeek 反射委托 OpenAI 栈，天然可达，其余 4 条 provider 若 stub 侧协议未兼容则本 TC 对应子跑降为 INCONCLUSIVE。 |
| Given | probe-agent 启动于 `s2` profile；分别设置 `LLM_PROVIDER=OpenAI` / `OpenAiCompatible` / `DashScope` / `SiliconFlow` / `DeepSeek` 跑五次；stub-server `header-wait-silent`（stub 兼容性由前置条件保证） |
| When | 每次 `POST /v1/query` body `{"message":"ping"}` |
| Then | 五次 trace 的 error payload `details.{phase, layer, timeout_seconds, stream}` **字面相等**；`provider` 字段体现差异（如 `details.provider` 字段若存在） |
| PASS | 四字段字面相等 |
| FAIL | 任一字段跨 provider 不等（说明某 provider 子类没走 ModelTimeoutResolver） |
| INCONCLUSIVE | stub 协议兼容性问题导致某 provider 子跑失败（归 demo 侧问题，非 SUT 问题；需 demo HANDOFF 更新可达性清单后重跑）|

---

## 4. 状态跟踪

| TC | 当前状态 | 阻塞 | 待办 |
|---|---|---|---|
| TC-DEFAULT-BASELINE | runnable | — | fixture 落地 + 实现 |
| TC-CONNECT-TIMEOUT | runnable | — | fixture 落地 + 实现 + 预检 TEST-NET-1 可达性 |
| TC-HEADER-WAIT-LEGACY | runnable | — | fixture 落地 + 实现 |
| TC-AFTER-FIRST-SEGMENT-IDLE | blocked_by_upstream | B1 | 等 agent-core-java upstream PR（见 demo UPSTREAM-ISSUE-DRAFT.md）；fixture 可先落 |
| TC-NO-TOTAL-CAP-STREAM | blocked_by_upstream | B1 | 同上 |
| TC-BLOCKING-SAME-SEMANTICS | runnable | — | fixture 落地 + 实现 |
| TC-CANCEL-NOT-TIMEOUT | runnable | — | fixture 落地 + driver 侧 `HttpRequest.timeout(3s)` 支持 |
| TC-ERROR-DETAILS-PAYLOAD | runnable | — | 依赖 3 条前置 TC 完成 |
| TC-PER-CALL-IDLE-OVERRIDE | blocked_by_upstream | B1 + 需开发侧补 profile | 两件事：① B1 修复；② `s3-per-call` profile（demo 或 SIT yaml 二选一） |
| TC-RANGE-VALIDATION | runnable | — | fixture 落地 + 临时 yaml 制造越界 |
| TC-UNIFIED-STACK-COVERAGE | runnable | — | fixture 落地 + 跨 provider env 切换；API key 占位获取 |

### 已知阻塞 B1 跟踪

- **根因**：`agent-core-java` `OpenAIModelClient.buildRequest` 把 `HttpRequest.timeout()` 当 header-wait 用，但 JDK 执行为 "总响应时长"；详见 `agent-solution/common/example/timeout-tiering-model-call-demo/UPSTREAM-ISSUE-DRAFT.md`
- **影响面**：TC-AFTER-FIRST-SEGMENT-IDLE / TC-NO-TOTAL-CAP-STREAM / TC-PER-CALL-IDLE-OVERRIDE 三条 TC；对应 MUST#3 / MUST#4 / MUST#5 / MUST#7 的部分断言面
- **跟踪动作**：开发侧 2026-10-22 已起草 upstream issue draft，是否提交 gitcode 由开发决定；SIT 不前置这一步，上游 PR 合入后：
  1. 把 B1 三条 TC 从 blocked_by_upstream 翻到 runnable；
  2. 跑一次 demo S3/S4 冒烟确认恢复 PASS；
  3. SIT 侧补齐 fixture + 实现用例类，走 Full suite 验收。

### 已知非范围 B2 跟踪

- **根因**：`IntelliRouterModelClient` 作为注册 provider 但不走三层超时合同；live-exposure latent（SDK 无 `RouterFactory` 绑定）
- **影响面**：MUST#8 "全部模型 HTTP 栈采用同一分层语义" 的覆盖面；但 production 不可达
- **立场（2026-10-22 开发确认）**：L1/L2 显式 carve-out（MUST#8 的"仓内全部模型 HTTP 栈"不含 IntelliRouter），SIT 永不构造 TC-INTELLI-ROUTER-TIERING-COVERAGE；L1/L2 刷新由上游文档负责人推进。
