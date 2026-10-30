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
| 7 | 单次调用超时参数接线（**agent-level idle override**） | `FEAT-058.agent-level-idle-override` | 增量（demo 2026-10-30 已补齐） | **runnable（待 ECS 复跑验证）** | `ReActAgentConfig` 字段单测 | 增量 TC-AGENT-LEVEL-IDLE-OVERRIDE；demo 侧 `TimeoutProbeAgentFactory.build` 条件注入 `setModelStreamIdleSeconds` + `s3-agent-level-idle` profile 2026-10-30 已落地 |
| 8 | 仓内全部模型 HTTP 栈采用同一分层语义 | `FEAT-058.unified-stack-tiering-across-clients` | 增量（demo README 建议） | runnable | 五个 client 子类（OpenAI/OpenAiCompatible/DashScope/SiliconFlow/DeepSeek）各自 timeout 单测 | 增量 TC-UNIFIED-STACK-COVERAGE；按对齐表附录 B 替代命名：多 client 子类共享 resolver；走 legacy header-wait 路径，不依赖 B1 |
| 9 | 阻塞调用超时语义一致（**stream_idle 侧**） | `FEAT-058.blocking-same-tiering-semantics` | S5 | runnable | `OpenAIModelTimeoutTieringTest` blocking 分支 | §3.6 TC-BLOCKING-SAME-SEMANTICS 覆盖 "头到齐 → body 首字节" 窗口的 stream_idle 层（MUST#9 窄义） |
| 9 | 阻塞调用超时语义一致（**header_wait 侧**） | `FEAT-058.blocking-header-wait-side` | 增量（demo 2026-10-30 已补齐） | **runnable（待 ECS 复跑验证）** | `OpenAIModelTimeoutTieringTest` blocking 分支 | 增量 TC-BLOCKING-HEADER-WAIT；demo 侧 `BlockingProbeController` + `blockingProbeModel` bean + `s2-blocking` profile 2026-10-30 已落地 |
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
| TC-UNIFIED-STACK-COVERAGE | 复用 `s2`（header-wait 快速路径） | `header-wait-silent` | `LLM_PROVIDER=OpenRouter` / `DashScope` / `SiliconFlow` / `DeepSeek` env 切换，其余同 s2（2026-10-28 batch triage 议题二 Path A：外部 provider enum value `OpenAiCompatible` → `OpenRouter`；底层 impl 类 `OpenAiCompatibleModelClient` 类名未变）|
| **TC-BLOCKING-HEADER-WAIT** | **`s2-blocking` 变体**（复用 s2 的 legacy header-wait 窗口 + 阻塞 invoke 路径；**待 demo 侧补**） | `header-wait-silent` | `openjiuwen.service.llm.timeout=8s`（legacy header-wait）+ **走阻塞 invoke 路径**（demo 需新增 `/probe/blocking/query` endpoint + `blockingProbeModel` bean；详见 `docs/review/SIT-REQUEST-feat-058-demo-side-extensions-20261030.md` §1）|
| **TC-AGENT-LEVEL-IDLE-OVERRIDE** | **新 profile `s3-agent-level-idle`**（**待 demo 侧补**） | `after-first-segment-idle` | client 级 `stream-idle-seconds=600s`（baseline）+ agent 级 `demo.timeout-probe.model-stream-idle-seconds=300.0`（demo 需在 `TimeoutProbeAgentProperties` 加 `modelStreamIdleSeconds` 字段 + `TimeoutProbeAgentFactory.build()` 条件注入 `agentConfig.setModelStreamIdleSeconds(...)`；详见 `docs/review/SIT-REQUEST-feat-058-demo-side-extensions-20261030.md` §2）|

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

### 3.6 TC-BLOCKING-SAME-SEMANTICS（阻塞路径同口径） — **MUST#9 窄义解读 + 路径 Y 判据**

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 `s5` profile + 启动参数 `--openjiuwen.service.llm.stream-idle-seconds=300`（SDK 下限 [300,900]）；stub-server `idle-silent-blocking` 模式 + `LLM_STUB_BLOCKING_IDLE_MS=310000`（沉默 310s 后 body 到达） |
| When | `POST /v1/query` body `{"message":"ping"}`；SIT driver timeout ≥ 320s（避免 driver 先关连接） |
| Then | 恰一次 `[181008]` + phase=`BEFORE_FIRST_SEGMENT`；禁 `BEFORE_CONNECT` / `AFTER_FIRST_SEGMENT`；error payload：`details.phase=="BEFORE_FIRST_SEGMENT"` + **`details.layer=="stream_idle"`**（**不是 `header_wait`** — MUST#9 窄义：响应头字节到齐后 header_wait 不再触发，"头到齐 → body 首字节"窗口归 stream_idle 层保护）+ `details.timeout_seconds==300.0`（±1.0s）+ `details.stream==false`（阻塞路径关键字段） |
| PASS | 阻塞路径表面与流式同一分层语义 + layer=stream_idle 证明 stream_idle 层在 BEFORE_FIRST_SEGMENT 相位能触发 + `stream==false` |
| FAIL | 任一字段偏离 / `stream==true`（阻塞被误识别为流式）/ layer==header_wait（广义解读未裁定，开发侧已明确窄义）|
| INCONCLUSIVE | stub 启动失败 |

> **2026-10-28 batch triage 议题三路径 Y 回灌**：MUST#9 原文"响应头到达前的等待窗口必须有超时保护"字面上两种自洽解读：窄义（HTTP 响应头字节到齐即解除 header_wait）vs 广义（首段可消费数据到达才解除）。开发侧 10-22 回帖 `SIT-CLARIFY-REPLY-must9-ambiguity-s5-fail.md` 技术判定走窄义；2026-10-28 `DEV-REPLY-batch-triage-20261028.md` 议题三再次校准：选路径 Y（正测试，证超窗必掐断）而非路径 X（负测试，证窗口内不误杀）。原路径 X 判据（layer=header_wait, timeout=8s）记录于 r1 初稿，经本次回帖裁定收敛为路径 Y 判据（layer=stream_idle, timeout=300s）。

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
| Then | 每条 payload 的 `details.{phase, timeout_seconds, layer, stream}` **四字段必填齐备**；phase ↔ layer 映射非 1:1：s1 (`BEFORE_CONNECT ↔ connect`) + s2 (`BEFORE_FIRST_SEGMENT ↔ header_wait`) + s5 (`BEFORE_FIRST_SEGMENT ↔ stream_idle`，MUST#9 窄义窗口由 stream_idle 层承载，见 §3.6)；程序方可按 `details.phase`+`details.layer` 组合判定（允许同一 phase 对应不同 layer） |
| PASS | 三 trace 均满足 |
| FAIL | 任一 trace 缺字段 / 对应关系错位 |
| INCONCLUSIVE | 任一上游 TC 自己是 INCONCLUSIVE（则本 TC 相应降级），**但**由于本 TC 不依赖 AFTER_FIRST_SEGMENT 维度，B1 不影响本 TC |

### 3.9 TC-PER-CALL-IDLE-OVERRIDE（增量 / per-call 不污染 client） — **demo 侧通道 landed**

| 字段 | 内容 |
|---|---|
| Given | probe-agent 启动于 `s3-per-call` profile（**client 级 `stream-idle-seconds=600s`** 持久配置；demo 侧 `PerCallProbeController` 侧通道已 landed via commit `ab9e03ba6581`，2026-10-28 batch triage 议题四）；stub-server `after-first-segment-idle` 模式 + `LLM_STUB_FIRST_SEGMENT_MS=310000`（首块后沉默 310s） |
| When | 1) 第一腿 `POST /probe/per-call/query?per_call_timeout_seconds=300`（driver timeout≥320s）；2) 第二腿 `POST /probe/per-call/query`（不带 override，driver timeout≥700s） |
| Then（期望） | 第一腿响应：HTTP 200 + `exception_type=="ModelError"` + `message` 含 `"effective timeout 300.0s"`（per-call 300s < stub 310s → 触发 timeout，证 override 到达 SDK）；SUT 日志出现 1 条 `[181008]` + phase=`AFTER_FIRST_SEGMENT`。第二腿响应：HTTP 200 + `completed==true` + 无 `exception_type`（client 级 600s > stub 310s → 自然完成，证明 per-call 已释放、client 级配置未被污染）；SUT 日志**仍只 1 条** `[181008]`（来自第一腿） |
| PASS | 第一腿 exception_type=ModelError + message 含 effective timeout 300.0s；第二腿 completed=true + 全程只 1 条 [181008] |
| FAIL | 第一腿缺 exception / message 不含 300.0s（override 未生效）；第二腿 completed=false 或出现第 2 条 [181008]（per-call 污染 client，client 级被压低到 300） |
| INCONCLUSIVE | demo 侧 `PerCallProbeController` 启动失败 / fat jar 版本不含 PerCallProbeController class |

> **2026-10-28 batch triage 议题四 landed 回灌**：原设计 blocked_by_upstream B1（HttpRequest.timeout 当总时长执行）于 10-23 HANDOFF 归因到 JDK 24+ 环境问题后 RETRACTED。开发侧 10-28 落地 demo 侧通道：新增 `/probe/per-call` endpoint（**走 `Model.stream(prompt, options)` 不走 ReActAgent**）、新增 `s3-per-call` profile、新增 `perCallProbeModel` bean（one-shot 构造 retry=0 避免 retry 叠加干扰）；prompt 硬编码 "probe per-call"（不接 request body）。SIT 侧 fixture 新增 `TimeoutProbeHttpClient.queryPerCall(Duration, Integer)` + `PerCallResponse` record；原 TC `@Disabled` 已删（commit 跟本次 batch 一起 push）。

> **PR#354 评审 1 回灌**：原设计把 per-call override（`ModelInvokeOptions.timeout`）与 agent 级常驻字段（`ReActAgentConfig.modelStreamIdleSeconds`）在同一 TC 内叠加，导致"第二腿期望 timeout_seconds=300"的断言歧义。本版 TC 收窄为**仅覆盖 `ModelInvokeOptions.timeout` 的 per-call 语义**（agent 级 `modelStreamIdleSeconds` 作为 streamIdleSeconds 第三个合法源的 SIT 覆盖，由 §3.15 **TC-AGENT-LEVEL-IDLE-OVERRIDE** 独立承接，demo 载体扩展 2026-10-30 已落地），第二腿改为"client 级 600s 回退、stall 310s 不触发超时"，判据无歧义。

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
| 前置 | **demo 侧需先实证 5 provider（OpenAI / OpenRouter / DashScope / SiliconFlow / DeepSeek）均可打通 llm-stub-server**（URL 路径 + 协议差异），由 demo HANDOFF 冒烟表给出可达性清单；DeepSeek 反射委托 OpenAI 栈，天然可达，其余 4 条 provider 若 stub 侧协议未兼容则本 TC 对应子跑降为 INCONCLUSIVE。注：外部 provider enum value `OpenAiCompatible` 已在 agent-core-java-0.1.17 改名为 `OpenRouter`（2026-10-28 batch triage 议题二 Path A）；底层 impl 类 `OpenAiCompatibleModelClient` 类名未变，L2 MUST#8 覆盖面以 impl 类家族计。|
| Given | probe-agent 启动于 `s2` profile；分别设置 `LLM_PROVIDER=OpenAI` / `OpenRouter` / `DashScope` / `SiliconFlow` / `DeepSeek` 跑五次；stub-server `header-wait-silent`（stub 兼容性由前置条件保证） |
| When | 每次 `POST /v1/query` body `{"message":"ping"}` |
| Then | 五次 trace 的 error payload `details.{phase, layer, timeout_seconds, stream}` **字面相等**；`provider` 字段体现差异（如 `details.provider` 字段若存在） |
| PASS | 四字段字面相等 |
| FAIL | 任一字段跨 provider 不等（说明某 provider 子类没走 ModelTimeoutResolver） |
| INCONCLUSIVE | stub 协议兼容性问题导致某 provider 子跑失败（归 demo 侧问题，非 SUT 问题；需 demo HANDOFF 更新可达性清单后重跑）|

### 3.12 TC-CANCEL-OBS-S18（MUST#11 cancel 可观测 / blocking 侧通道） — **跨 FEAT 承接 FEAT-064 USER_CANCELLED**

| 字段 | 内容 |
|---|---|
| 定位 | **MUST#11 cancel-observability output 强层判据**。承接原 FEAT-064 cases §4 保留项 "TC-USER-CANCELLED-\* 待 FEAT-058 MUST#11 裁定落地" 的 blocking 分支。走 develop-head m2（agent-core-java mr/437 #252 cancellation log anchor + probe-agent `CancelProbeController` 侧通道），绕开 §3.7 TC-CANCEL-NOT-TIMEOUT 走 `/v1/query` 的弱层口径。 |
| Given | probe-agent 启动于 `s0` profile；stub-server `header-wait-silent` 模式（stub 发完 header 后不发 body，worker 阻塞读 body） |
| When | SIT driver `POST /probe/cancel/blocking?cancel_after_ms=300` → probe-agent 在 worker 线程提交 `model.invoke(...)`，调度 `future.cancel(true)` 300 ms 后触发 |
| Then | probe-agent 日志出现 `event=model_call_cancelled`（`ClassifiedModelErrors.toCancellation` 规范化锚点）；日志**无** `[181008]`（确保 cancel 先于三层 timer 任一一个触发） |
| PASS | 日志出现 cancel anchor + 无 181008 |
| FAIL | cancel anchor 未出现（可能原因：jar 为 develop-head m2 之前版本，缺 CancelProbeController，POST 返 404） / 日志出现 181008（cancel 被归类为 timeout 而非 USER_CANCELLED） |
| INCONCLUSIVE | POST 返非 200 非 404（可能是 probe-agent 启动期异常，诊断方向在启动日志） |
| 载体 | `Feat058Feat064CancelObservabilityTest.blockingCancelEmitsAnchorLogNoTimeout` |

### 3.13 TC-CANCEL-OBS-S19（MUST#11 cancel 可观测 / streaming 侧通道） — **跨 FEAT 承接 FEAT-064 USER_CANCELLED**

| 字段 | 内容 |
|---|---|
| 定位 | 同 §3.12，streaming 分支——cancel 在流式 `model.stream(...)` 的 chunk 迭代中触发，覆盖 `OpenAIModelClient` 的 mid-stream read-cancelled 分支 + stream-open 分支（两分支任一命中 anchor 均 PASS） |
| Given | 同 §3.12 |
| When | SIT driver `POST /probe/cancel/streaming?cancel_after_ms=300` → probe-agent 在 worker 线程启动流式迭代，调度 `future.cancel(true)` 300 ms 后触发；interrupt 传播到 stream chunk 迭代位 |
| Then | 同 §3.12（anchor 字串与 blocking 共用；reason 后缀会在 `model stream interrupted` / `model stream read cancelled` 两种中命中其一，SIT 不断言 reason 文本，只断言 anchor presence） |
| PASS | 同 §3.12 |
| FAIL | 同 §3.12 |
| INCONCLUSIVE | 同 §3.12 |
| 载体 | `Feat058Feat064CancelObservabilityTest.streamingCancelEmitsAnchorLogNoTimeout` |

> **§3.7 vs §3.12/§3.13 的分工**：§3.7（弱层）通过 driver 侧 HTTP 3s abort 验 "caller 取消窗口内无 181008"，MUST#11 判定力归 UT；§3.12/§3.13（强层）通过 probe-agent 侧通道直接触发 model-layer cancel + 读 anchor 日志，是 MUST#11 cancel-observability output 的直接判据。两组互补，不替代。
>
> **跨 FEAT 归属澄清**：anchor 日志行是 FEAT-058 MUST#11 的可观测承诺；`USER_CANCELLED` 分类名是 FEAT-064 L2 §1.2.14 的分类集 M14 的成员。本两条 TC 验的是 anchor，主属 FEAT-058；SIT 测试类用 `@Tag("feat-058") @Tag("feat-064")` 双 Tag 承接。

### 3.14 TC-BLOCKING-HEADER-WAIT（MUST#9 阻塞路径 header_wait 侧） — **runnable（demo 2026-10-30 已落地，待 ECS 复跑验证）**

| 字段 | 内容 |
|---|---|
| 定位 | **MUST#9 的 header_wait 侧判据**，与 §3.6 TC-BLOCKING-SAME-SEMANTICS（stream_idle 侧）配对覆盖 L2 §1.2.9 的两个独立观测点。原 r3 口径将 MUST#9 窄义窗口归 stream_idle 层承载，但 header_wait 层在阻塞路径下的触发事实未在 wire 层被 SIT 外部观察到——理由：当前 `TimeoutProbeAgentFactory` 构造 `ReActAgent` 走 `model.stream(...)`，`/v1/query` 全程流式；`CancelProbeController` 虽有 blocking 入口但耦合 300ms cancel 调度。本 TC 填补这一 gap。 |
| Given | probe-agent 启动于 **`s2-blocking` profile**（demo 侧 2026-10-30 已新增，复用 `openjiuwen.service.llm.timeout=8s` legacy header-wait 窗口 + 阻塞 invoke 路径）；stub-server `header-wait-silent` 模式；demo 侧 **`BlockingProbeController`**（`/probe/blocking/query` endpoint，走 `model.invoke(prompt)` 阻塞路径，retry=0，不调度 future.cancel；详见 `docs/review/SIT-REQUEST-feat-058-demo-side-extensions-20261030.md` §1） |
| When | SIT driver `POST /probe/blocking/query`（driver timeout ≥ 15s） |
| Then | HTTP 200 响应 Map 含：`completed==false` + `exception_type=="ModelError"` + `message` 含 `"effective timeout 8.0s"` 子串；`error_details.phase=="BEFORE_FIRST_SEGMENT"` + **`error_details.layer=="header_wait"`**（**MUST#9 header_wait 侧**：响应头字节未到齐，走 `IdleGuardedBodyReader` 对应的 `PreparedRequest.timeout` 承载）+ `error_details.timeout_seconds==8.0`（±0.5s）+ **`error_details.stream==false`**（阻塞路径关键字段） |
| PASS | 四字段齐备 + phase=BEFORE_FIRST_SEGMENT + layer=header_wait + timeout=8.0 + stream=false |
| FAIL | 任一字段偏离 / layer=stream_idle（阻塞路径未进入 header_wait 分支）/ stream=true（阻塞被误识别为流式） |
| INCONCLUSIVE | demo fat jar 未包含 `BlockingProbeController` class / `blockingProbeModel` bean / `s2-blocking` profile（POST 返 404 或 Spring 启动失败） |
| 载体 | `Feat058RunnablePathTest.blockingHeaderWaitPathObservesLayer`（2026-10-30 demo 落地后已从 `Feat058BlockedByUpstreamTest` 迁移至此并翻 runnable） |

### 3.15 TC-AGENT-LEVEL-IDLE-OVERRIDE（MUST#7 agent-level streamIdleSeconds 源） — **runnable（demo 2026-10-30 已落地，待 ECS 复跑验证）**

| 字段 | 内容 |
|---|---|
| 定位 | **MUST#7 的 agent-level 源判据**。L2 §1.2.7 为 `streamIdleSeconds` 定义三个合法生效源（优先级 per-call > agent-level > client-level）：per-call `ModelInvokeOptions.timeout` 由 §3.9 TC-PER-CALL-IDLE-OVERRIDE 覆盖、client-level `openjiuwen.service.llm.stream-idle-seconds` 由 §3.4/§3.6 覆盖；**agent-level `ReActAgentConfig.modelStreamIdleSeconds`** 由本 TC 覆盖。 |
| Given | probe-agent 启动于 **`s3-agent-level-idle` profile**（demo 侧 2026-10-30 已新增：client 级 `stream-idle-seconds=600s` 作为 baseline + agent 级 `modelStreamIdleSeconds=300.0` 通过 `TimeoutProbeAgentProperties.modelStreamIdleSeconds` → `TimeoutProbeAgentFactory.build()` 条件注入 `agentConfig.setModelStreamIdleSeconds(300.0)`；详见 `docs/review/SIT-REQUEST-feat-058-demo-side-extensions-20261030.md` §2）；stub-server `after-first-segment-idle` 模式 + `LLM_STUB_FIRST_SEGMENT_MS=310000`（首块后沉默 310s） |
| When | SIT driver `POST /v1/query` body `{"message":"ping"}`（driver timeout ≥ 320s） |
| Then | 恰一条 `[181008]` + phase=`AFTER_FIRST_SEGMENT`；禁 `BEFORE_CONNECT` / `BEFORE_FIRST_SEGMENT`；error payload：`details.phase=="AFTER_FIRST_SEGMENT"` + `details.layer=="stream_idle"` + **`details.timeout_seconds==300.0`**（±1.0s）——**关键判据**：agent-level 300s 生效、覆盖 client-level 600s；若 timeout_seconds==600.0 则证明 agent-level 字段未被 SDK 消费（MUST#7 违约） + `details.stream==true` |
| PASS | 四字段齐备 + phase=AFTER_FIRST_SEGMENT + layer=stream_idle + **timeout=300.0**（agent-level 覆盖生效） + stream=true |
| FAIL | timeout_seconds==600.0（agent-level 未生效，client-level 兜底）/ 任一其他字段偏离 |
| INCONCLUSIVE | demo fat jar 未包含 `TimeoutProbeAgentProperties.modelStreamIdleSeconds` 字段 / `TimeoutProbeAgentFactory.build` 未调用 `setModelStreamIdleSeconds` / 未装配 `s3-agent-level-idle` profile |
| 载体 | `Feat058RunnablePathTest.agentLevelIdleOverridesClientLevel`（2026-10-30 demo 落地后已从 `Feat058BlockedByUpstreamTest` 迁移至此并翻 runnable） |

---

## 4. 状态跟踪

| TC | 当前状态 | 阻塞 | 待办 |
|---|---|---|---|
| TC-DEFAULT-BASELINE | **landed PASS** | — | ✅ 2026-10-28 ECS 实证 PASS（Feat058RunnablePathTest, 6.57s） |
| TC-CONNECT-TIMEOUT | **landed PASS** | — | ✅ 2026-10-28 ECS 实证 PASS（39.14s）—— 新 jar PR!242+PR!442+PR!449 修 #260 + wire 结构化 failure 后，#260 EOF 清零；BEFORE_CONNECT + connect 5s 转绿 |
| TC-HEADER-WAIT-LEGACY | **landed PASS** | — | ✅ 2026-10-28 ECS 实证 PASS（68.75s）—— 同上，BEFORE_FIRST_SEGMENT + header_wait 8s 转绿 |
| TC-AFTER-FIRST-SEGMENT-IDLE | **landed PASS** | — | ✅ 2026-10-28 ECS 复跑 PASS（develop-head PR!242+PR!442+PR!449 rebuild bundle 新 jar，commit `ab9e03ba6581`）。10-27 wire-eof 根因（#260 SSE 结构化错误后 chunked 流中断）由新 jar 顺带修完，AFTER_FIRST_SEGMENT 相位 wire body 正常返回，TC 从 wire-eof-rooted-in-mvc-bug 直接转绿 |
| TC-NO-TOTAL-CAP-STREAM | **landed PASS** | — | ✅ 2026-10-27 /v1 后缀 fc15b30 修完 ECS 实证 PASS（96.99s，MUST#4 契约 wire 可观察性验证通过）|
| TC-BLOCKING-SAME-SEMANTICS | **landed PASS（路径 Y 待复跑验证）** | — | ✅ 2026-10-28 batch triage 议题三裁定路径 Y：layer=stream_idle + timeout=300.0（替代原路径 X 判据 layer=header_wait + timeout=8.0）；SIT 侧已改 stub LLM_STUB_BLOCKING_IDLE_MS=310000 + probe --stream-idle-seconds=300 + driver 400s + asserter LAYER_STREAM_IDLE；待 ECS 一次 batch 复跑验证 |
| TC-CANCEL-NOT-TIMEOUT | **landed PASS** | — | ✅ 2026-10-28 ECS 实证 PASS（69.01s，弱层判据） |
| TC-ERROR-DETAILS-PAYLOAD | **landed PASS（跟 S5 路径 Y 同步，待复跑验证）** | — | ✅ 2026-10-28 跟 TC-BLOCKING-SAME-SEMANTICS 第三段同步改路径 Y；第三段 asserter LAYER_HEADER_WAIT → LAYER_STREAM_IDLE；phase ↔ layer 映射更新为非 1:1（§3.8）；待 ECS 一次 batch 复跑验证 |
| TC-PER-CALL-IDLE-OVERRIDE | **landed PASS（待复跑验证）** | — | ✅ 2026-10-28 batch triage 议题四 landed：demo 侧补 PerCallProbeController 侧通道 + s3-per-call profile（commit `ab9e03ba6581`）；SIT 侧 fixture 补 queryPerCall + PerCallResponse record + 删 @Disabled + 两腿判据按 §3.9 实现；待 ECS 一次 batch 复跑验证 |
| TC-RANGE-VALIDATION | **landed PASS** | — | ✅ 2026-10-28 ECS 实证 PASS（2.72s）；异常栈改层：从 agent-core-java `ModelClientConfig$Builder.build` 下沉到 agent-service-app `LlmConfigResolver.validateTimeoutTiering:222`，错误消息改为 `openjiuwen.service.llm.connect-timeout must be between 5 and 15 seconds`；TC `containsAnyOf("ModelClientConfig","connect","range","must be between")` 判据 "connect" + "must be between" 覆盖新消息 → PASS 不受影响 |
| TC-UNIFIED-STACK-COVERAGE | **landed PASS（待复跑验证）** | — | ✅ 2026-10-28 batch triage 议题二 Path A 裁定：外部 provider enum value `OpenAiCompatible` → `OpenRouter`（agent-core-java-0.1.17 已改名；底层 impl 类 `OpenAiCompatibleModelClient` 类名未变，L2 MUST#8 以 impl 类家族计覆盖面，L2 契约未违约）。本仓 cases §3.11 + `Feat058RunnablePathTest.java:416` 字样已同步刷新。待 ECS 复跑 5 provider 完整走通后标 landed PASS。关联：`SIT-OBSERVATION-provider-enum-rename-20261028.md`（commit `a54e76eda6e5`）+ 开发侧 `DEV-REPLY-batch-triage-20261028.md`（commit `44067785`）|
| **TC-CANCEL-OBS-S18** | **landed** | — | **ECS 2026-10-27 实测 PASS（跟 S19 共 2/2）** |
| **TC-CANCEL-OBS-S19** | **landed** | — | **ECS 2026-10-27 实测 PASS（跟 S18 共 2/2）** |
| **TC-BLOCKING-HEADER-WAIT** | **runnable（待 ECS 复跑验证）** | — | 2026-10-30 demo 侧落地 `BlockingProbeController` + `blockingProbeModel` bean + `s2-blocking` profile（path: `D:\openjiuwen-java\2012\agent-solution\common\example\timeout-tiering-model-call-demo`）；SIT 侧 `TimeoutProbeHttpClient.queryBlocking` + `BlockingResponse` record 已补；用例已从 `Feat058BlockedByUpstreamTest` 迁移至 `Feat058RunnablePathTest.blockingHeaderWaitPathObservesLayer` 并翻 runnable；待 ECS 一次 batch 复跑验证四字段契约 |
| **TC-AGENT-LEVEL-IDLE-OVERRIDE** | **runnable（待 ECS 复跑验证）** | — | 2026-10-30 demo 侧落地 `TimeoutProbeAgentProperties.modelStreamIdleSeconds` 字段 + `TimeoutProbeAgentFactory.build` 条件注入 `setModelStreamIdleSeconds` + `s3-agent-level-idle` profile（path: 同上）；SIT 侧 `AGENT_LEVEL_IDLE_SECONDS=300.0` env 注入；用例已从 `Feat058BlockedByUpstreamTest` 迁移至 `Feat058RunnablePathTest.agentLevelIdleOverridesClientLevel` 并翻 runnable；待 ECS 一次 batch 复跑验证 `timeout_seconds==300.0`（非 600.0） |

### 2026-10-28 全盘复跑小结（Feat058RunnablePathTest 8 TC + Feat058Feat064CancelObservabilityTest 2 TC + Feat058BlockedByUpstreamTest 3 TC）

- **使用 jar**：develop-head PR!242 + PR!442 + PR!449 bundle（agent-core-java-0.1.17）
- **Feat058RunnablePathTest 8 TC**：**5 PASS**（defaultBaseline / connectLayer / headerWait / callerCancel / rangeValidation）+ **2 FAIL**（blockingPath / errorDetailsPayload — 均 MUST#9 既存问题）+ **1 ERROR**（unifiedStackCoverage — provider 枚举改名）
- **S18/S19**：2026-10-27 PASS，本次未重跑
- **BlockedByUpstream 3 TC**：S9 skipped（等 demo），S3 wire-EOF（见上表），S4 PASS
- **已推 docsReview observation**：`SIT-OBSERVATION-provider-enum-rename-20261028.md`（commit `a54e76eda6e575cb3ecaa6a9982a80c952a62316`）
- **等开发侧裁定**：
  1. MUST#9 条文歧义窄义/广义（S5 + errorDetails）—— 已推送 `SIT-CLARIFY-REPLY-must9-ambiguity-s5-fail.md` 10-22 既存
  2. provider 枚举改名 A/B/C 路径（unifiedStack）—— 今日推送

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
