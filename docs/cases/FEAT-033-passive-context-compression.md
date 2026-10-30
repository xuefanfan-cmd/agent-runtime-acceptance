---
feature_id: FEAT-033
feature_title: 结构化被动压缩 (StructuredCompactProcessor)
sut: structured-compact-sit-demo 的 context-probe-agent（端口 18193）+ llm-stub-server（端口 18194）；两个均为独立 Spring Boot fat jar；SIT scope 临时含该 probe 载体，不改默认 deepagent 范围。SUT 侧走 `ContextEngine.createContext → session.getContextWindow` 完整装配链路，由 engine 内部完成触发判定；装配骨架含 `ReActAgent + ContextProcessorRail`（新端点 `POST /trigger-compact-via-rail` 可观察 rail_dispatch_begin/end 两行日志）。stub 可控注入 9 种摘要形状（r5：含 `oversized-summary` + `output-length-truncated`），OpenAI 兼容 `/v1/chat/completions` 端点 + `/__pause`/`/__ack`/`/__release` 可控同步点 + `/__stats` 按 mode 计数。
scope: 本档对齐 PR!376 r5 testplan，覆盖 L2 §1.2 对齐矩阵 7 行能力（触发与注册 / 分层保护 / 章节摘要 / 失败降级 / 标识符 / 可观测 / 简单接入）；共 30 条 TC（r4 的 13 条按 R01-R16 技术判决订正判据，新增 17 条覆盖 R01 四级装配链路证据 / R02 中间 system 拒绝 / R03 round-boundary 边界 / R06 二轮标识符修复 / R09 TraceBridge 卸载空窗口 / R10 saveState·loadState / R13 终止保护 NO_DECREASE + 摘要超限 + 输出截断 / R14 会话正文 sentinel / R15 异步取消可控同步点 / D2 messagesToKeep=0 工具调用尾 / D8 四级标识符优先级 / 构建期配置 fail-fast + 哨兵零泄漏）。L1 §2.1 单句压缩 3 MUST 按 L2 §1.1 明示 OUT，本档不承接。
status: pending-implementation
status_note: r5 testplan 已 push PR!376（commit 待记录）；本仓 fat jar 消费侧 Phase-1 已落 14 TC（Feat033PassiveCompactionTest / DetailsMapCompletenessTest / ConfigFailFastTest 共 20 @Test 方法），编译绿；仍需补 Phase-1 13 TC + Phase-2 3 TC = 16 TC；Fixture 侧 CompactProbeDemoLauncher / CompactProbeHttpClient / ProbeLogTailer / CompressionDetailsAsserter 已就绪，仍需补 SyncPointDriver / SpanEventsAsserter / StubStatsAsserter。
owner: TBD
tags: [integration, feat-033, context-compression, passive-compaction, structured-compact-processor]
depends_on:
  - `structured-compact-sit-demo` 两个 fat jar 已就绪（2026-10-28 批次：probe MD5 `3ddd91d1a44c2548b3a3e959f53c1b57` / stub MD5 `973fc0543ca4f926a6151f874c48ce1c`）
  - 不依赖 Runtime SUT（probe 内置 `ContextEngine.createContext` + `AssemblyRuntime` 装配 ReActAgent + ContextProcessorRail）
  - 不需要真实 LLM 凭据（所有 scenario 打本机 stub `127.0.0.1:18194/v1`，必须含 `/v1` 后缀）
  - `unset HTTP_PROXY HTTPS_PROXY`（stub 在本机 loopback，企业代理会干扰）
  - **JDK 17 或 21**（JDK 24+ 下 Lombok `sun.misc.Unsafe` 反射失效；与 FEAT-064 共用口径）
  - 端口 18193（probe-agent）/ 18194（llm-stub-server）可用
  - probe 启动 profile ∈ {s0..s11, s12..s24} — r4 的 s0..s11 + r5 新增 s12..s18 冒烟自动 + s19..s24 手动驱动
  - 可选 stub env：`LLM_STUB_MISSING_CHAPTER_RECOVER_AFTER=1`（s2）/ `LLM_STUB_INPUT_TOO_LARGE_RECOVER_AFTER=1`（s4）/ `LLM_STUB_SLOW_RESPONSE_MILLIS=10000`（s6）
related_docs:
  - 特性文档（L1）：Technical-AF/docs:develop/02-features/FEAT-033-core-context-compression-mechanisms.md
  - L2 设计：develop/03-architecture/L2-Low-Level-Design/agent-core-ext/Feat-Func-033-passive-context-compression.md
  - testplan：Technical-AF/docs:develop/04-testplan/FEAT-033-passive-context-compression.md（PR!376 r5）
  - 代码口径：`agent-core-ext-context-compression:0.1.3`
  - demo 根：agent-solution/common/example/structured-compact-sit-demo（2026-10-28 批次 HANDOFF.md）
  - demo trace-asserter：`scripts/trace-asserter.sh` s0..s11 + s16/s17/s18 的 required/forbidden pattern
  - 本仓相邻参考：`docs/cases/FEAT-064-model-call-error-taxonomy-and-retry.md`（fat jar 启停 fixture 模板）
---

# FEAT-033 — 结构化被动压缩 (StructuredCompactProcessor) 用例设计

> **一句话**：以 demo `context-probe-agent` 为 SUT、`llm-stub-server` 为可控对端（9 种摘要形状），把 FEAT-033 L2 §1.2 的 **7 行能力矩阵** + §5.3 可观测 + §6.2 配置 + §3.4 失败降级决策表的外部可观察行为，映射为 30 条 TC（r5 scope：r4 基础 13 条按 R01-R16 技术判决订正判据，新增 17 条覆盖 R01 四级装配链路 / R02 中间 system / R03 round-boundary / R06 二轮标识符修复 / R09 TraceBridge 卸载 / R10 save/load / R13 终止保护 + 摘要超限 + 输出截断 / R14 sentinel / R15 异步取消 / D2/D8 等）。probe 走 `ContextEngine.createContext → session.getContextWindow` 完整装配链路（由 engine 内部完成触发判定）；判据主通道走 `processor` finish 日志 `phase=complete outcome=... reason=... attempts=... llm_calls=... identifiers_*=...` + HTTP body `details` map + `/__compression-history` + `/__span-events` + `/__assembly-info` + `/__stats` 多通道组合。L1 §2.1 单句压缩按 L2 §1.1 OUT，本档不承接。

> **状态含义**：
> - **phase-1-landed**：Fixture + Java TC 已落地（当前 14 TC）
> - **phase-1-pending**：r5 新增判据，Fixture 已就绪、Java TC 待写（13 TC）
> - **phase-2**：需进一步稳定化的 fixture 组合（3 TC：DET-NO-DECREASE / IDENTIFIER-PRIORITY / MESSAGES-TO-KEEP-ZERO）
> - **out-of-scope**：L2 §1.1 明示 OUT（L1 §2.1 单句压缩）

---

## 0. 执行前置快速清单

- [ ] `structured-compact-sit-demo/llm-stub-server/target/llm-stub-server-*.jar` 存在（2026-10-28 批次 MD5 `973fc0543ca4f926a6151f874c48ce1c`）
- [ ] `structured-compact-sit-demo/context-probe-agent/target/context-probe-agent-*.jar` 存在（2026-10-28 批次 MD5 `3ddd91d1a44c2548b3a3e959f53c1b57`）
- [ ] **JDK 17 或 21**
- [ ] `unset HTTP_PROXY HTTPS_PROXY`
- [ ] 端口 18193 / 18194 空闲
- [ ] 可选：`export LLM_STUB_MISSING_CHAPTER_RECOVER_AFTER=1`（s2 / TC-INVALID-SUMMARY-RECOVER）
- [ ] 可选：`export LLM_STUB_INPUT_TOO_LARGE_RECOVER_AFTER=1`（s4 / TC-INPUT-TOO-LARGE-DROP-GROUP）
- [ ] 可选：`export LLM_STUB_SLOW_RESPONSE_MILLIS=10000`（s6 / TC-MODEL-TIMEOUT-COMPLETABLEFUTURE）

---

## 1. 覆盖矩阵（能力 → 子用例）

### 1.1 L1 §2.2 被动压缩 6 MUST + §2.3 可观测/配置 2 MUST → 30 TC 回指

| L1 条目 | 要求级别 | 承接 TC（r5 共 30 条） | 状态 |
|---|---|---|---|
| L1 §2.2.1 触发（窗口 token 估算超阈值触发，阈值可配） | MUST | TC-BELOW-THRESHOLD-NO-OP / TC-LLM-COMPACT-SUCCESS / TC-ASSEMBLY-CHAIN-E2E / TC-ACTIVE-TRIGGER-COMPRESS | phase-1 |
| L1 §2.2.2 分层保护（系统头原样 + 尾部按消息数预算 + 中间进摘要 + 截断点对齐 API 轮次边界） | MUST | TC-LLM-COMPACT-SUCCESS / TC-MESSAGES-TO-KEEP-ZERO / TC-SYSTEM-EXTERNAL-PRESERVE / TC-SYSTEM-MID-REJECTED / TC-ROUND-BOUNDARY-ALIGN | phase-1 + phase-2 |
| L1 §2.2.3 章节摘要结构（11 章节） | MUST | TC-LLM-COMPACT-SUCCESS / TC-INVALID-SUMMARY-RECOVER / TC-INVALID-ORDER-EXHAUSTED | phase-1 |
| L1 §2.2.4 摘要超限重试（逐级丢最老一组，重试次数设限） | MUST | TC-INPUT-TOO-LARGE-DROP-GROUP / TC-SUMMARY-TOO-LARGE-DROP / TC-OUTPUT-LENGTH-TRUNCATED | phase-1 |
| L1 §2.2.5 失败降级链（低质量/超限 → 确定性兜底 → 不下降 no-op） | MUST | TC-LOW-RETENTION-DETERMINISTIC / TC-MODEL-TIMEOUT-COMPLETABLEFUTURE / TC-MODEL-5XX-FALLBACK / TC-MODEL-NOT-CONFIGURED / TC-SUMMARY-BUDGET-CONFLICT / TC-SUMMARY-BUDGET-AUTO-DERIVE / TC-DET-NO-DECREASE-NO-OP / TC-SNAPSHOT-CHANGED-NOOP / TC-ASYNC-CANCEL-LATE-WRITEBACK | phase-1 + phase-2 |
| L1 §2.2.6 标识符保护三步闭环 | MUST | TC-IDENTIFIER-TRUNCATION / TC-IDENTIFIER-REPAIR / TC-REPEAT-COMPACT-GUARD / TC-IDENTIFIER-PRIORITY | phase-1 + phase-2 |
| L1 §2.3.1 可观测（算法标识 / 结果 / 失败归因 / token 计量） | MUST | TC-DETAILS-SCHEMA-COMPLETENESS / TC-TRACEBRIDGE-SPAN-EVENT / TC-STATE-SAVE-LOAD + 全 30 TC 的 processor 日志行 | phase-1 |
| L1 §2.3.2 压缩参数配置（可配 + 默认 + 构建期报错 + 无凭据泄露） | MUST | TC-CONFIG-FAIL-FAST（7 子场景 + 哨兵零出现判据） | phase-1 |

### 1.2 L2 §1.2 能力对齐矩阵 7 行 → 30 TC 回指

| L2 §1.2 行 | 需求要求 | L2 实现回指 | 承接 TC |
|---|---|---|---|
| 触发与注册 | FR-03 | §2、§3.1（绝对阈值，仅窗口钩子，严格超过）| TC-BELOW-THRESHOLD-NO-OP / TC-LLM-COMPACT-SUCCESS / TC-ASSEMBLY-CHAIN-E2E / TC-ACTIVE-TRIGGER-COMPRESS |
| 分层保护 | FR-04 | §3.2（分层保护 + 轮次边界）| TC-MESSAGES-TO-KEEP-ZERO / TC-SYSTEM-EXTERNAL-PRESERVE / TC-SYSTEM-MID-REJECTED / TC-ROUND-BOUNDARY-ALIGN |
| 章节摘要 | FR-05 | §3.3（11 章节、middle_retention 默认 0.10）| TC-LLM-COMPACT-SUCCESS / TC-INVALID-SUMMARY-RECOVER / TC-INVALID-ORDER-EXHAUSTED |
| 失败降级 | FR-06 | §3.4（有界重试 + 保留率下限 + 降级决策表）| TC-INPUT-TOO-LARGE-DROP-GROUP / TC-SUMMARY-TOO-LARGE-DROP / TC-OUTPUT-LENGTH-TRUNCATED / TC-LOW-RETENTION-DETERMINISTIC / TC-MODEL-TIMEOUT-COMPLETABLEFUTURE / TC-MODEL-5XX-FALLBACK / TC-MODEL-NOT-CONFIGURED / TC-SUMMARY-BUDGET-CONFLICT / TC-SUMMARY-BUDGET-AUTO-DERIVE / TC-DET-NO-DECREASE-NO-OP / TC-SNAPSHOT-CHANGED-NOOP / TC-ASYNC-CANCEL-LATE-WRITEBACK |
| 标识符 | FR-07 | §3.5（上限 300 / 优先级 / 审计补注）| TC-IDENTIFIER-TRUNCATION / TC-IDENTIFIER-REPAIR / TC-REPEAT-COMPACT-GUARD / TC-IDENTIFIER-PRIORITY |
| 可观测 | FR-08 | §5.3（事件/日志/轨迹映射，details map 字段清单以上游交付为准）| TC-DETAILS-SCHEMA-COMPLETENESS / TC-TRACEBRIDGE-SPAN-EVENT + 全 TC 的 processor 日志 |
| 简单接入 | FR-9 | §6.2（可配 + 默认 + 构建期 fail-fast）| TC-CONFIG-FAIL-FAST / TC-STATE-SAVE-LOAD |

### 1.3 本档不承接项（可追溯标记）

| L2 条款 | 能力 | 不承接原因 |
|---|---|---|
| L2 §1.1 | L1 §2.1 单句压缩（token 占位 / 原文卸载召回 / 保护集合 N 条） | **L2 §1.1 明示 OUT**："单句工具结果卸载、原文存储/召回、EDPA、异步压缩、SessionMemory 联动不属于本制品" |

---

## 2. 测试判据通道

| 通道 | 来源 | 字段/样式 | 用于 |
|---|---|---|---|
| **主通道**：processor finish 日志 | `com.openjiuwen.agents.contextcompression.StructuredCompactProcessor` | `phase=complete outcome=<X> reason=<Y> attempts=<N> llm_calls=<M> dropped_groups=<D> identifiers_selected=<S> identifiers_truncated=<T> identifiers_repaired=<R>` + 可能的 `phase=retry reason=<Z>` 行 | outcome / reason / attempt_count / llm_call_count / dropped_groups / identifiers_* 判据（demo trace-asserter.sh 对齐） |
| **辅通道**：probe_result controller echo | `com.openjiuwen.example.compactsit.probe.CompactProbeController` | `probe_result scenario=sN outcome=... reason=... attempt_count=... ...` | SIT 侧 fallback，不作独立判据 |
| **HTTP body**：`POST /trigger-compact` JSON | demo controller | `{ "scenario": "sN", "details": { "kind": "structured_compact", "algorithm_version": "1", "counter_id": "<ClassName>", "outcome": ..., "reason": ..., ... }, "compressionHistorySize": N, "windowSystemMessageCount": N, "windowContextMessageCount": N }` | TC-DETAILS-SCHEMA-COMPLETENESS 主判据 |
| **历史**：`GET /__compression-history?contextId=X` | demo controller | `{ "contextId": "X", "history": [ { "compression_usage": { "details": [ { "kind": "structured_compact", ... } ] } }, ... ] }` | 多轮触发（二轮标识符修复 / round-boundary / save-load）主判据 |
| **span 事件**：`GET /__span-events` | demo controller（OTel InMemorySpanExporter） | `{ "spans": [ { "span": "agent.trigger_compact", "events": [ { "name": "context.compression_state", "attributes": { "outcome": ..., "reason": ..., "algorithm_version": "1", "before_tokens": N, ... } } ] } ] }` | TC-TRACEBRIDGE-SPAN-EVENT 主判据 |
| **装配**：`GET /__assembly-info` | demo controller | `{ "registered_processors": [...], "callback_framework": "...", "trace_bridge_installed": true, "tracer_provider": "...", "processor_type": "StructuredCompactProcessor", "algorithm_version": "1" }` | TC-ASSEMBLY-CHAIN-E2E / TC-TRACEBRIDGE-SPAN-EVENT preflight |
| **stub 统计**：`GET /__stats` | llm-stub-server | `{ "stats": { "valid": N, "missing-chapter-then-valid": N, ... } }` | 各 TC 对账预期 LLM 调用次数 |
| **装配骨架日志**：rail_dispatch_begin/end | demo controller `/trigger-compact-via-rail` | `rail_dispatch_begin agent_id=context-probe-agent rail=ContextProcessorRail processors=[MessageSummaryOffloader, DialogueCompressor, CurrentRoundCompressor, RoundLevelCompressor, StructuredCompactProcessor] message_count_before=N` → `rail_dispatch_end agent_id=context-probe-agent status=ok message_count_after=N` | TC-ASSEMBLY-CHAIN-E2E 四级装配链路证据（R01）。**2026-10-29 ECS 实证订正**：`processors=[...]` 列表值用 `StructuredCompactProcessor.PROCESSOR_TYPE` 常量（PascalCase 类名），不是早期 HANDOFF 文档描述的 snake_case `[structured_compact]`；`rail_dispatch_end` 后先打 `agent_id=` 字段再打 `status=ok`，非紧邻 |

---

## 3. TC 详述

> TC ID 风格：`FEAT-033.TC-<capability-slug>`。slug 对齐 r5 testplan §5 测试场景矩阵表。

### 3.1 `TC-BELOW-THRESHOLD-NO-OP` — 阈值以下 NO_OP（L2 §3.1）

- **profile**：`s0`（trigger-total-tokens=10_000_000，fixture-kind=standard）
- **stub mode**：任意（不打）
- **操作**：`POST /trigger-compact?scenario=s0`（必经 ContextEngine）
- **判据**（required）：
  - processor 日志：`outcome=NO_OP` / `reason=BELOW_THRESHOLD` / `attempts=0` / `llm_calls=0`
  - stub `/__stats` 零命中
- **判据**（forbidden）：
  - `outcome=LLM_COMPACTED` / `outcome=DETERMINISTIC_COMPACTED` / `phase=attempt`
- **Java 落点**：`Feat033PassiveCompactionTest.belowThresholdNoOp()`
- **状态**：phase-1-landed

### 3.2 `TC-LLM-COMPACT-SUCCESS` — 单次 11 章节 LLM_COMPACTED（L2 §3.3）

- **profile**：`s1`（trigger-total-tokens=1000）
- **stub mode**：`valid`
- **判据**（required）：
  - `outcome=LLM_COMPACTED` / `reason=SUCCESS` / `attempts=1` / `llm_calls=1`
  - `after_tokens < before_tokens` 严格下降
  - stub `/__stats` 命中 `valid:1`
- **判据**（forbidden）：
  - `outcome=DETERMINISTIC_COMPACTED` / `reason=INVALID_SUMMARY` / `reason=LOW_QUALITY`
- **Java 落点**：`Feat033PassiveCompactionTest.llmCompactSuccess()`
- **状态**：phase-1-landed

### 3.3 `TC-ASSEMBLY-CHAIN-E2E` — 四级装配链路 E2E（L2 §2.2 + R01）

- **profile**：`s1`（trigger-total-tokens=1000，fixture-kind=standard）
- **stub mode**：`valid`
- **操作**：
  1. preflight `GET /__assembly-info` → 核 `trace_bridge_installed=true`、`registered_processors` 含 `StructuredCompactProcessor`、`algorithm_version="1"`
  2. 主请求 `POST /trigger-compact-via-rail?scenario=s1` → 四级 ReActAgent → ContextProcessorRail → ContextEngine → StructuredCompactProcessor 完整链路
  3. 对照组（仅观察，不作通过凭据）`POST /__direct-handler?scenario=s1` → 直接调 `processor.onGetContextWindow`
- **判据**（required）：
  - preflight：`/__assembly-info` 字段齐全
  - 主请求：probe 日志出现 `rail_dispatch_begin agent_id=context-probe-agent processors=[... StructuredCompactProcessor] message_count_before=N` + `rail_dispatch_end agent_id=context-probe-agent status=ok message_count_after=N` 两行（**2026-10-29 实证订正**：`processors=[...]` 列表值用 `PROCESSOR_TYPE` 常量 PascalCase 类名；`rail_dispatch_end` 后 `agent_id=` 跟 `status=ok` 中间隔字段非紧邻）
  - HTTP body `trigger_source=rail_dispatch` + `rail_dispatch_status=ok`
  - `/__compression-history` 产出 1 条 kind=`structured_compact` details
  - `outcome=LLM_COMPACTED`
- **判据**（forbidden）：
  - `rail_dispatch_status=exception`
  - `/__assembly-info` 返回 `trace_bridge_installed=false`
- **回指**：R01 四级装配链路证据
- **Java 落点**：`Feat033PassiveCompactionTest.assemblyChainE2E()`（新建，phase-1-pending）
- **状态**：phase-1-pending

### 3.4 `TC-ACTIVE-TRIGGER-COMPRESS` — 主动触发压缩路径（L2 §2.1）

- **profile**：`s1`
- **stub mode**：`valid`
- **操作**：`POST /__trigger-active?scenario=s1`
- **判据**（required）：
  - HTTP body 含 `activeTriggerResultType` + `compressionHistory` + `details`
  - `/__compression-history` 新增 1 条 kind=`structured_compact` details
  - details 字段清单与 passive 路径一致
- **判据**（forbidden）：
  - `/__compression-history` 未新增（证明主动路径未触发压缩）
- **Java 落点**：`Feat033PassiveCompactionTest.activeTriggerCompress()`（新建，phase-1-pending）
- **状态**：phase-1-pending

### 3.5 `TC-MESSAGES-TO-KEEP-ZERO` — 边界仍保护未完成后缀（L2 §3.2 + D2）

- **profile**：`s20`（messagesToKeep=0 + fixture-kind=tool-call-tail）
- **stub mode**：`valid`
- **判据**（required）：
  - `outcome=LLM_COMPACTED`
  - details `messages_to_keep=0`
  - after-window 中未完成后缀 tool_call 消息仍原样保留（SIT 侧核 `windowContextMessageCount >= 2`，且保留 trailing user + tool_call pair）
- **判据**（forbidden）：
  - after-window 丢失 trailing tool_call 消息
- **Java 落点**：`Feat033LayerProtectionTest.messagesToKeepZero()`（新类，phase-2）
- **状态**：phase-2（需 fixture `tool-call-tail` 稳定命中）

### 3.6 `TC-SYSTEM-EXTERNAL-PRESERVE` — 外部位置 system 独立原样保留（L2 §3.2）

- **profile**：`s12`（fixture-kind=system-external-only）
- **stub mode**：`valid`
- **判据**（required）：
  - `outcome=LLM_COMPACTED` / `reason=SUCCESS`
  - HTTP body `windowSystemMessageCount >= 1`（SIT 侧核 `GET /__compression-history` 返回体不含 SystemMessage）
- **判据**（forbidden）：
  - `windowSystemMessageCount=0`
- **Java 落点**：`Feat033LayerProtectionTest.systemExternalPreserve()`（新类，phase-1-pending）
- **状态**：phase-1-pending

### 3.7 `TC-SYSTEM-MID-REJECTED` — history 中间 SystemMessage → NO_OP（L2 §3.2 拒绝路径）

- **profile**：`s13`（fixture-kind=system-mid-injection）
- **stub mode**：`valid`
- **判据**（required）：
  - `outcome=NO_OP` / `reason=UNSUPPORTED_MESSAGES` / `llm_calls=0`
  - stub `/__stats` 零命中
  - `compressionHistorySize == before`（history 原样保留）
- **判据**（forbidden）：
  - `outcome=LLM_COMPACTED` / `outcome=DETERMINISTIC_COMPACTED` / `phase=attempt`
- **Java 落点**：`Feat033LayerProtectionTest.systemMidRejected()`（新类，phase-1-pending）
- **状态**：phase-1-pending

### 3.8 `TC-ROUND-BOUNDARY-ALIGN` — 截断点对齐 API 轮次边界（L2 §3.2 + R03）

- **profile**：`s22`（messagesToKeep=2 + fixture-kind=round-boundary，偶数边界）+ `s23`（messagesToKeep=3，奇数向前补整组）
- **stub mode**：`valid`
- **判据**（s22 required）：
  - `outcome=LLM_COMPACTED`
  - after-window message_count 偶数（trailing user+assistant pair 完整保留）
- **判据**（s23 required）：
  - `outcome=LLM_COMPACTED`
  - after-window message_count=4（向前补一整组 → protectedTail=4）
- **判据**（forbidden）：
  - 半轮工具调用错位边界（只保留 tool_call 不保留 tool_result）
- **Java 落点**：`Feat033LayerProtectionTest.roundBoundaryEven()` + `.roundBoundaryOddExtendBackward()`（新类，phase-1-pending）
- **状态**：phase-1-pending

### 3.9 `TC-INVALID-SUMMARY-RECOVER` — 首次缺章节重试恢复（L2 §3.4）

- **profile**：`s2`
- **stub mode**：`missing-chapter-then-valid` + `LLM_STUB_MISSING_CHAPTER_RECOVER_AFTER=1`
- **判据**（required）：
  - `phase=retry reason=INVALID_SUMMARY`
  - `outcome=LLM_COMPACTED` / `reason=SUCCESS` / `attempts=2` / `llm_calls=2`
  - details `attempt_reasons=["INVALID_SUMMARY","SUCCESS"]`
- **判据**（forbidden）：
  - `outcome=DETERMINISTIC_COMPACTED`
- **Java 落点**：`Feat033PassiveCompactionTest.invalidSummaryRecover()`
- **状态**：phase-1-landed

### 3.10 `TC-INVALID-ORDER-EXHAUSTED` — 三次重试耗尽本地摘录（L2 §3.4）

- **profile**：`s3`
- **stub mode**：`invalid-order`
- **判据**（required）：
  - `phase=retry reason=INVALID_SUMMARY`（出现 3 次）
  - `outcome=DETERMINISTIC_COMPACTED` / `reason=INVALID_SUMMARY` / `attempts=3` / `llm_calls=3`
- **判据**（forbidden）：
  - `outcome=LLM_COMPACTED` / `reason=SUCCESS`
- **Java 落点**：`Feat033PassiveCompactionTest.invalidOrderExhausted()`
- **状态**：phase-1-landed

### 3.11 `TC-INPUT-TOO-LARGE-DROP-GROUP` — 服务报输入过长丢组后重试成功（L2 §3.4）

- **profile**：`s4`
- **stub mode**：`input-too-large-then-valid` + `LLM_STUB_INPUT_TOO_LARGE_RECOVER_AFTER=1`
- **判据**（required）：
  - `phase=retry reason=INPUT_TOO_LARGE dropped_groups=1`
  - `outcome=LLM_COMPACTED` / `reason=SUCCESS`
  - details `attempt_reasons=["INPUT_TOO_LARGE","SUCCESS"]`（`attempt_count` 与 `llm_call_count` 可不相等——预检失败计 attempt 但不发 LLM 请求）
- **判据**（forbidden）：
  - `outcome=DETERMINISTIC_COMPACTED` / `reason=MODEL_ERROR`
- **Java 落点**：`Feat033PassiveCompactionTest.inputTooLargeDropGroup()`
- **状态**：phase-1-landed

### 3.12 `TC-SUMMARY-TOO-LARGE-DROP` — 超长摘要三次耗尽本地摘录（L2 §3.4 + R13）

- **profile**：`s15`（fixture-kind=tiny-middle）
- **stub mode**：`oversized-summary`
- **判据**（required）：
  - `outcome=DETERMINISTIC_COMPACTED` / `reason=SUMMARY_TOO_LARGE` / `attempts=3` / `llm_calls=3`
  - details `attempt_reasons=["SUMMARY_TOO_LARGE","SUMMARY_TOO_LARGE","SUMMARY_TOO_LARGE"]` / `dropped_groups=3`
  - stub `/__stats` 命中 `oversized-summary:3`
- **判据**（forbidden）：
  - `outcome=LLM_COMPACTED` / `reason=SUCCESS`
- **Java 落点**：`Feat033RetryAndFallbackTest.summaryTooLargeDrop()`（新类，phase-1-pending）
- **状态**：phase-1-pending

### 3.13 `TC-OUTPUT-LENGTH-TRUNCATED` — finish_reason=length 丢组重试（L2 §3.4 + R13）

- **profile**：`s16`
- **stub mode**：`output-length-truncated`
- **判据**（required）：
  - details `attempt_reasons` 包含 `"OUTPUT_LENGTH"`
  - 每次仅丢最老一完整组 → `attempt_count ≤ 3`
  - `outcome=DETERMINISTIC_COMPACTED` + `llm_calls >= 1`
- **判据**（forbidden）：
  - `attempt_reasons` 内出现 `MODEL_ERROR` / `MODEL_TIMEOUT`
  - `outcome=LLM_COMPACTED` / `reason=SUCCESS`
- **Java 落点**：`Feat033RetryAndFallbackTest.outputLengthTruncated()`（新类，phase-1-pending）
- **状态**：phase-1-pending

### 3.14 `TC-LOW-RETENTION-DETERMINISTIC` — 连续两次低保留率降级（L2 §3.4）

- **profile**：`s5`（min-middle-retention-ratio=0.20）
- **stub mode**：`short-retention`
- **判据**（required）：
  - `phase=retry reason=LOW_QUALITY`
  - `outcome=DETERMINISTIC_COMPACTED` / `reason=LOW_QUALITY` / `llm_calls=2`
- **判据**（forbidden）：
  - `outcome=LLM_COMPACTED` / `reason=SUCCESS`
- **Java 落点**：`Feat033PassiveCompactionTest.lowRetentionDeterministic()`
- **状态**：phase-1-landed

### 3.15 `TC-MODEL-TIMEOUT-COMPLETABLEFUTURE` — processor `.orTimeout()` 本地截断 → MODEL_TIMEOUT（L2 §3.4）

- **profile**：`s6`（client-timeout-seconds=2.0）
- **stub mode**：`slow` + `LLM_STUB_SLOW_RESPONSE_MILLIS=10000`
- **判据**（required）：
  - `outcome=DETERMINISTIC_COMPACTED` / `reason=MODEL_TIMEOUT` / `llm_calls=1`
- **判据**（forbidden）：
  - `reason=MODEL_ERROR` / `reason=SUCCESS`
  - 业务日志出现 `header_wait timeout` 原文（processor 刻意不输出）
- **S6 known-gap 注释**：当前 upstream OpenAIModelClient 的 header-wait 超时抛的是自定义异常，经分类器归到 `MODEL_ERROR`；demo trace-asserter.sh 以 `MODEL_ERROR + header_wait timeout` 为口径。SIT 判据 r5 对齐 L2 §3.4 `MODEL_TIMEOUT` 分支，若 upstream 不补齐 processor 侧 `.orTimeout()` 期限暴露，本 TC 执行时按 known-gap 允许接收 `MODEL_ERROR` 作部分通过。
- **Java 落点**：`Feat033PassiveCompactionTest.modelTimeoutFallback()`
- **状态**：phase-1-landed（known-gap）

### 3.16 `TC-MODEL-5XX-FALLBACK` — 5xx 持续降级（L2 §3.4）

- **profile**：`s7`
- **stub mode**：`server-5xx`
- **判据**（required）：
  - `outcome=DETERMINISTIC_COMPACTED` / `reason=MODEL_ERROR` / `llm_calls=1`
- **判据**（forbidden）：
  - `outcome=LLM_COMPACTED` / `reason=MODEL_TIMEOUT`
- **Java 落点**：`Feat033PassiveCompactionTest.modelFiveXxFallback()`
- **状态**：phase-1-landed

### 3.17 `TC-MODEL-NOT-CONFIGURED` — 缺 model 直接本地摘录（L2 §3.4 + §6.2）

- **profile**：`s8`（model-configured=false）
- **stub**：不启动
- **判据**（required）：
  - `outcome=DETERMINISTIC_COMPACTED` / `reason=MODEL_NOT_CONFIGURED` / `llm_calls=0`
- **判据**（forbidden）：
  - `outcome=LLM_COMPACTED` / `phase=attempt`
- **Java 落点**：`Feat033PassiveCompactionTest.modelNotConfigured()`
- **状态**：phase-1-landed

### 3.18 `TC-SUMMARY-BUDGET-CONFLICT` — 预算冲突零 LLM 调用（L2 §3.4）

- **profile**：`s9`（summary-max-tokens=50，min-middle-retention-ratio=0.9）
- **stub**：不打
- **判据**（required）：
  - `outcome=DETERMINISTIC_COMPACTED` / `reason=SUMMARY_BUDGET_CONFLICT` / `llm_calls=0`
- **判据**（forbidden）：
  - `outcome=LLM_COMPACTED` / `phase=attempt`
- **Java 落点**：`Feat033PassiveCompactionTest.summaryBudgetConflict()`
- **状态**：phase-1-landed

### 3.19 `TC-SUMMARY-BUDGET-AUTO-DERIVE` — 未设 `model.maxTokens` 自动推导（L2 §6.2 对照组）

- **profile**：`s1`（未显式配 `model.maxTokens`）
- **stub mode**：`valid`
- **判据**（required）：
  - `outcome=LLM_COMPACTED` / `reason=SUCCESS`
  - details 字段齐全，含 `compression_call_max_tokens`（上游自动推导的默认值）
- **判据**（forbidden）：
  - `reason=SUMMARY_BUDGET_CONFLICT`
- **Java 落点**：`Feat033RetryAndFallbackTest.summaryBudgetAutoDerive()`（新类，phase-1-pending）
- **状态**：phase-1-pending

### 3.20 `TC-DET-NO-DECREASE-NO-OP` — 终止保护 NO_DECREASE（L2 §3.4 + R13 ①）

- **profile**：`s14`（fixture-kind=det-no-decrease，messagesToKeep=2，model-configured=false）
- **stub**：不打（兜底路径）
- **判据**（required）：
  - `outcome=NO_OP` / `reason=NO_DECREASE`
  - 源 history 原样保留（before == after）
- **判据**（forbidden）：
  - after < before 却标 NO_DECREASE（字段错位）
- **Java 落点**：`Feat033RetryAndFallbackTest.deterministicNoDecreaseNoOp()`（新类，phase-2）
- **状态**：phase-2（当前 fixture 规模下仍能下降；MVP 以 MODEL_NOT_CONFIGURED 兜底对照 identifiers 保留契约）

### 3.21 `TC-SNAPSHOT-CHANGED-NOOP` — 快照变化 no-op（L2 §3.4 + R15）

- **profile**：`s19`
- **stub mode**：`valid` + 可控同步点 `/__pause`
- **操作**：`/__pause` → `/__trigger-async?opId=op2&contextId=probe-sync` → `/__ack?timeoutMs=10000`（acked=true）→ `/__source-mutate?contextId=probe-sync&op=append-user` → `/__release` → `/__async-result?opId=op2&waitMs=10000`
- **判据**（required）：
  - 异步结果 `details.outcome=NO_OP` / `details.reason=SNAPSHOT_CHANGED`
  - 源 context 不被压缩结果覆盖
- **判据**（forbidden）：
  - `outcome=LLM_COMPACTED`
  - 固定 sleep 时序作判据（必须走可控同步点）
- **Java 落点**：`Feat033ConcurrencyTest.snapshotChangedNoOp()`（新类，phase-1-pending）
- **状态**：phase-1-pending

### 3.22 `TC-ASYNC-CANCEL-LATE-WRITEBACK` — 异步取消 + 迟到结果不写回（L2 §3.4 + R15）

- **profile**：`s19`
- **stub mode**：`valid` + 可控同步点
- **操作**：`/__pause` → `/__trigger-async?opId=op1` → `/__ack?timeoutMs=10000`（acked=true）→ `/__cancel?opId=op1` → `/__release` → `/__async-result?opId=op1&waitMs=3000`
- **判据**（required）：
  - 异步结果 `cancelled=true` / `done=true`
  - 源 context 不出现压缩结果
- **判据**（forbidden）：
  - 固定 sleep 时序作判据
- **Java 落点**：`Feat033ConcurrencyTest.asyncCancelLateWriteback()`（新类，phase-1-pending）
- **状态**：phase-1-pending

### 3.23 `TC-IDENTIFIER-TRUNCATION` — 标识符总上限 300 截断（L2 §3.5）

- **profile**：`s10`（fixture-kind=identifier-heavy，候选 >300）
- **stub mode**：`valid`
- **判据**（required）：
  - `identifiers_selected=300`
  - `identifiers_truncated == 候选去重数 - 300 >= 1`
- **判据**（forbidden）：
  - `identifiers_truncated=0`
- **Java 落点**：`Feat033PassiveCompactionTest.identifierTruncation()`
- **状态**：phase-1-landed

### 3.24 `TC-IDENTIFIER-REPAIR` — 摘要未提及审计补注（L2 §3.5）

- **profile**：`s11`（fixture-kind=identifier-sparse）
- **stub mode**：`valid`
- **判据**（required）：
  - `identifiers_repaired >= 1`
  - after-window `<preserved_identifiers>` 块含保留标识符文本
- **判据**（forbidden）：
  - `identifiers_repaired=0`
- **Java 落点**：`Feat033PassiveCompactionTest.identifierRepair()`
- **状态**：phase-1-landed

### 3.25 `TC-REPEAT-COMPACT-GUARD` — 重复压缩：第二轮识别保护块并补注（L2 §3.5 + R06 ②）

- **profile**：`s17`（fixture-kind=identifier-sparse）
- **stub mode**：`valid`
- **操作**：`POST /trigger-compact?scenario=s17&contextId=probe-s17-r1` → `POST /__source-mutate?contextId=probe-s17-r1&op=append-user` → `POST /trigger-compact?scenario=s17&contextId=probe-s17-r1` → `GET /__compression-history?contextId=probe-s17-r1`
- **判据**（required）：
  - round 2 `before_tokens > trigger_total_tokens` 严格超阈值
  - round 2 details `attempt_count>=1` / `identifiers_repaired>=1`
  - round 2 保护块含锚点文本
- **判据**（forbidden）：
  - round 2 `identifiers_repaired=0`
  - round 2 `outcome=DETERMINISTIC_COMPACTED`
- **Java 落点**：`Feat033IdentifierTest.repeatCompactGuard()`（新类，phase-1-pending）
- **状态**：phase-1-pending

### 3.26 `TC-IDENTIFIER-PRIORITY` — 4 级优先级裁剪（L2 §3.5 + D8）

- **profile**：`s21`（fixture-kind=identifier-tiered，候选 650=offload-handle 100 + file-path 150 + doc-id 200 + uuid-url 200，identifier-limit=300）
- **stub mode**：`valid`
- **判据**（required）：
  - `identifiers_selected=300`
  - `identifiers_truncated == 650 - 300 = 350`
  - 从 `/__compression-history` 保护块文本断言入选集合：offload-handle 100 + file-path 150 全保留，剩 50 席给 doc-id，uuid-url 全 truncated
- **判据**（forbidden）：
  - 优先级低者挤入入选集合
  - 断言不存在的 `details.selected 类型分布` 字段
- **Java 落点**：`Feat033IdentifierTest.identifierPriority()` + N-1/N/N+1 边界参数化（新类，phase-2）
- **状态**：phase-2（需 fixture `identifier-tiered` 稳定命中）

### 3.27 `TC-DETAILS-SCHEMA-COMPLETENESS` — details map 字段完整性（L2 §5.3）

- **profile**：`s1`（阳性路径，全字段都有值）
- **stub mode**：`valid`
- **判据**（required，字段清单以上游 kind=structured_compact 实际交付为准）：
  - 固定枚举：`kind == "structured_compact"` / `algorithm_version == "1"`（**字符串**） / `counter_id`（TokenCounter 实现类名字符串）
  - 结果三元：`outcome` / `reason` / `attempt_reasons`（list）
  - token 计量：`before_tokens`（>0 int） / `after_tokens`（>0 int，< before） / `above_trigger_tokens`（>=0 int）
  - 配置快照：`trigger_total_tokens` 与配置一致 / `compression_call_max_tokens` 与配置或自动推导一致 / `messages_to_keep` 与配置一致
  - 保留率：`middle_retention`（0 ≤ x ≤ 1，no-op 分支允许 null） / `global_retention`（0 ≤ x ≤ 1）
  - 过程计量：`attempt_count` / `llm_call_count` / `dropped_groups` / `duration_ms`（>=0）
  - 标识符三元：`identifiers_selected` / `identifiers_truncated` / `identifiers_repaired`
- **判据**（forbidden）：
  - 字段值出现对话正文 / 摘要内容 / 工具输入输出 / 凭据哨兵 `SENTINEL-CREDENTIAL-DO-NOT-LEAK-7f3a9c`
  - `FAILED` outcome（processor 不产生该值）
- **Java 落点**：`Feat033DetailsMapCompletenessTest.detailsMapAllFieldsPresent()`
- **状态**：phase-1-landed

### 3.28 `TC-TRACEBRIDGE-SPAN-EVENT` — TraceBridge 事件挂宿主 recording span（L2 §5.3 + R09）

- **profile**：`s1`（阳性路径）
- **stub mode**：`valid`
- **操作**：
  1. preflight `/__assembly-info` → `trace_bridge_installed=true`
  2. `POST /__reset-span-exporter`
  3. 主请求 `POST /trigger-compact?scenario=s1`
  4. `GET /__span-events`
  5. 空窗口测试：`POST /__trace-bridge-close` → `/__assembly-info` 核 `trace_bridge_installed=false` → `POST /__reset-span-exporter` → 主请求 → `/__span-events` 验证无 `context.compression_state` 事件
  6. 重装：`POST /__trace-bridge-reinstall` → `/__assembly-info` 核 `trace_bridge_installed=true` → 主请求 → `/__span-events` 验证 event 回归
- **判据**（required）：
  - 存在 span `agent.trigger_compact`，其上有 event `context.compression_state`
  - event attribute 含 `outcome` / `reason` / `algorithm_version` / `before_tokens` / `after_tokens` / `attempt_count` / `llm_call_count` 等固定结果码
  - close 后：无 `context.compression_state` 事件
  - reinstall 后：event 恢复
- **判据**（forbidden）：
  - 新建名为 "processor span" 的 span
  - event attribute 含 `counter_id`
- **Java 落点**：`Feat033TraceBridgeTest.spanEventOnHostSpan()` + `.closeAndReinstall()`（新类，phase-1-pending）
- **状态**：phase-1-pending

### 3.29 `TC-STATE-SAVE-LOAD` — saveState / loadState 生命周期（L2 §6.2 + R10）

- **profile**：`s18`
- **stub mode**：`valid`
- **操作**：
  1. 一轮 s1 压缩，记录 `counter_id` 与 `compressionHistorySize`
  2. `POST /__save-state?contextId=X` → 返回 `{messages, offload_messages}` 结构
  3. `/__load-state?contextId=X&scenario=s18` → 内部 clearContext + createContext + loadState
  4. load 后再触发（未重新超阈值条件下）
- **判据**（required）：
  - `saveState` 响应结构：`{"saved": true, "state": {...}}`
  - `/__load-state` 响应 `message_count_before == message_count_after`
  - 同一 TokenCounter 实现下 round-trip 后 `counter_id` 字符串一致
  - round-trip 后未重新超阈值时 `/__compression-history` 不新增
- **判据**（forbidden）：
  - `message_count_before != message_count_after`
  - `counter_id` 字符串变化（证明 TokenCounter 实现漂移）
- **Java 落点**：`Feat033StateLifecycleTest.saveLoadRoundTrip()`（新类，phase-1-pending）
- **状态**：phase-1-pending

### 3.30 `TC-CONFIG-FAIL-FAST` — 构建期非法配置 + 哨兵零泄漏（L2 §2.3 + §7.3 + R14）

- **执行方式**：**不启动 probe-agent**；SIT 直接依赖 `agent-core-ext-context-compression:0.1.3`，Java TC 内构造 `StructuredCompactProcessorConfig`（setter + builder.build() + `new StructuredCompactProcessor(config)` 整段）
- **判据**（required）：下述每子场景在 `setter + validate + construct` **任意一步**必抛 `IllegalArgumentException`（assertThrows 范围覆盖整段），且异常 message / 异常链 / 堆栈文本 / 对应可观察日志不出现哨兵字符串 `SENTINEL-CREDENTIAL-DO-NOT-LEAK-7f3a9c`：
  - `triggerTotalTokens = 0`
  - `triggerTotalTokens = -1`
  - `messagesToKeep = -1`
  - `compressionCallMaxTokens = 0`
  - `identifierLimit = 0`
  - `minMiddleRetentionRatio ∈ {0.0, 1.0, -0.1, 1.5}`（L2 §6.2 "有限 (0,1)"）
  - 单独设 `model` 不设 `modelClient`（或反之） → 构建期拒
- **判据**（forbidden）：
  - 任一子场景能构造成功
  - 哨兵 `SENTINEL-CREDENTIAL-DO-NOT-LEAK-7f3a9c` 出现在异常 message / 堆栈 / 日志
- **判据不基于参数名正则**（允许 `compressionCallMaxTokens / maxTokens` 等参数名出现在 message）
- **Java 落点**：`Feat033ConfigFailFastTest`（已有 7 @Test，phase-1-landed；哨兵判据待追加）
- **状态**：phase-1-landed（哨兵零泄漏子判据待补判）

---

## 4. SIT fixture 预设

| fixture 组件 | 职责 | 状态 |
|---|---|---|
| `CompactProbeDemoLauncher` | 启停两个 fat jar；传 scenario profile + stub env 覆写；端口 18193/18194 探活；JVM 退出 hook kill 子进程 | **landed** |
| `CompactProbeHttpClient` | 直发 probe 入口：`/trigger-compact` / `/trigger-compact-via-rail` / `/__trigger-active` / `/__trigger-async` / `/__cancel` / `/__source-mutate` / `/__save-state` / `/__load-state` / `/__compression-history` / `/__span-events` / `/__assembly-info` / `/__reset-span-exporter` / `/__trace-bridge-close` / `/__trace-bridge-reinstall` | **landed**（基础版） — 待扩 `__trigger-active` / `__trigger-async` / `__cancel` / `__source-mutate` / `__save-state` / `__load-state` / `__span-events` / `__assembly-info` / `__trace-bridge-*` / `trigger-compact-via-rail` 等 r5 新增端点 |
| `SyncPointDriver` | 封装 `/__pause` + `/__ack(timeoutMs)` + `/__release` + `/__async-result(opId,waitMs)` 调用序列 | **pending**（r5 新建） |
| `ProbeLogTailer` | 过滤 `StructuredCompactProcessor` 行 + `rail_dispatch_begin/end` 行 + `probe_result` 行；按 `operation` + `phase` 关联断言 | **landed**（基础版） — 待扩 rail_dispatch_* 支持 |
| `CompressionDetailsAsserter` | 核 HTTP body + `/__compression-history` details map；`assertAlgorithmVersionString()` / `assertCounterIdIsClassName()` / `assertNoWhitelistViolation()` / `assertNoLeakedSentinel(sentinelValue)` | **landed**（基础版） — 待扩哨兵判据 |
| `SpanEventsAsserter` | 调 `GET /__span-events`，断言宿主 recording span 上 `context.compression_state` event 存在，event attribute 含固定结果码；拒 "processor span" / `counter_id` 属性 | **pending**（r5 新建） |
| `StubStatsAsserter` | 调 `GET /__stats`，按 mode 对账预期调用次数（如 `oversized-summary:3`） | **pending**（r5 新建） |

**TC 文件布局**：

```
src/test/java/com/huawei/ascend/sit/cases/integration/context_compression/
├── Feat033PassiveCompactionTest.java        — §3.1~§3.4、§3.9~§3.11、§3.14~§3.18、§3.23~§3.24 已落（12 @Test）
├── Feat033DetailsMapCompletenessTest.java   — §3.27 已落（1 @Test）
├── Feat033ConfigFailFastTest.java           — §3.30 已落（7 @Test），哨兵判据待追加
├── Feat033LayerProtectionTest.java          — §3.5~§3.8（新建，4 @Test，phase-1-pending + phase-2）
├── Feat033RetryAndFallbackTest.java         — §3.12/§3.13/§3.19/§3.20（新建，4 @Test，phase-1-pending + phase-2）
├── Feat033ConcurrencyTest.java              — §3.21/§3.22（新建，2 @Test，phase-1-pending）
├── Feat033IdentifierTest.java               — §3.25/§3.26（新建，2 @Test，phase-1-pending + phase-2）
├── Feat033TraceBridgeTest.java              — §3.28（新建，2 @Test，phase-1-pending）
├── Feat033StateLifecycleTest.java           — §3.29（新建，1 @Test，phase-1-pending）
└── fixture/
    ├── CompactProbeDemoLauncher.java
    ├── CompactProbeHttpClient.java          — 待扩新端点
    ├── ProbeLogTailer.java                   — 待扩 rail_dispatch_* 支持
    ├── CompressionDetailsAsserter.java      — 待扩哨兵判据
    ├── SyncPointDriver.java                  — 新建
    ├── SpanEventsAsserter.java               — 新建
    └── StubStatsAsserter.java                — 新建
```

---

## 5. Status 表

| TC ID | 覆盖 L2 §1.2 行 | 状态 |
|---|---|---|
| `TC-BELOW-THRESHOLD-NO-OP` | 触发与注册 | phase-1-landed |
| `TC-LLM-COMPACT-SUCCESS` | 触发与注册 / 分层保护 / 章节摘要 | phase-1-landed |
| `TC-ASSEMBLY-CHAIN-E2E` | 触发与注册 / 装配契约 | **phase-1-pending** |
| `TC-ACTIVE-TRIGGER-COMPRESS` | 触发与注册 | **phase-1-pending** |
| `TC-MESSAGES-TO-KEEP-ZERO` | 分层保护 | **phase-2** |
| `TC-SYSTEM-EXTERNAL-PRESERVE` | 分层保护 | **phase-1-pending** |
| `TC-SYSTEM-MID-REJECTED` | 分层保护 | **phase-1-pending** |
| `TC-ROUND-BOUNDARY-ALIGN` | 分层保护 | **phase-1-pending** |
| `TC-INVALID-SUMMARY-RECOVER` | 章节摘要 / 失败降级 | phase-1-landed |
| `TC-INVALID-ORDER-EXHAUSTED` | 章节摘要 / 失败降级 | phase-1-landed |
| `TC-INPUT-TOO-LARGE-DROP-GROUP` | 失败降级 | phase-1-landed |
| `TC-SUMMARY-TOO-LARGE-DROP` | 失败降级 | **phase-1-pending** |
| `TC-OUTPUT-LENGTH-TRUNCATED` | 失败降级 | **phase-1-pending** |
| `TC-LOW-RETENTION-DETERMINISTIC` | 失败降级 | phase-1-landed |
| `TC-MODEL-TIMEOUT-COMPLETABLEFUTURE` | 失败降级 | phase-1-landed（known-gap） |
| `TC-MODEL-5XX-FALLBACK` | 失败降级 | phase-1-landed |
| `TC-MODEL-NOT-CONFIGURED` | 失败降级 | phase-1-landed |
| `TC-SUMMARY-BUDGET-CONFLICT` | 失败降级 | phase-1-landed |
| `TC-SUMMARY-BUDGET-AUTO-DERIVE` | 失败降级 / 简单接入 | **phase-1-pending** |
| `TC-DET-NO-DECREASE-NO-OP` | 失败降级 | **phase-2** |
| `TC-SNAPSHOT-CHANGED-NOOP` | 失败降级 | **phase-1-pending** |
| `TC-ASYNC-CANCEL-LATE-WRITEBACK` | 失败降级 | **phase-1-pending** |
| `TC-IDENTIFIER-TRUNCATION` | 标识符 | phase-1-landed |
| `TC-IDENTIFIER-REPAIR` | 标识符 | phase-1-landed |
| `TC-REPEAT-COMPACT-GUARD` | 标识符 | **phase-1-pending** |
| `TC-IDENTIFIER-PRIORITY` | 标识符 | **phase-2** |
| `TC-DETAILS-SCHEMA-COMPLETENESS` | 可观测 | phase-1-landed |
| `TC-TRACEBRIDGE-SPAN-EVENT` | 可观测 | **phase-1-pending** |
| `TC-STATE-SAVE-LOAD` | 简单接入 | **phase-1-pending** |
| `TC-CONFIG-FAIL-FAST` | 简单接入 | phase-1-landed（哨兵零泄漏子判据待补） |

**Phase-1 待补**：13 TC（ASSEMBLY-CHAIN-E2E / ACTIVE-TRIGGER-COMPRESS / SYSTEM-EXTERNAL-PRESERVE / SYSTEM-MID-REJECTED / ROUND-BOUNDARY-ALIGN / SUMMARY-TOO-LARGE-DROP / OUTPUT-LENGTH-TRUNCATED / SUMMARY-BUDGET-AUTO-DERIVE / SNAPSHOT-CHANGED-NOOP / ASYNC-CANCEL-LATE-WRITEBACK / REPEAT-COMPACT-GUARD / TRACEBRIDGE-SPAN-EVENT / STATE-SAVE-LOAD）
**Phase-2 待补**：3 TC（MESSAGES-TO-KEEP-ZERO / DET-NO-DECREASE-NO-OP / IDENTIFIER-PRIORITY）

---

### 5.1 2026-10-29 ECS 首轮验收实证小结

**跑测环境**：develop-head PR!242+PR!442+PR!449 bundle rebuild（docsReview commit `ab9e03ba6581`）+ PR!464 upstream `OpenAIModelClient.parseResponse` finish_reason 修复 rebuild（demo 10-29 批次 probe MD5 `6b6ff5862b6496ad32b607436187fa75` / stub MD5 `fe8f493642d429a8f9c35475e0f8739c`）。

**首轮跑测盘面**：**40 Tests / 12 FAIL / 0 Error / 4 min**（Feat033ConfigFailFastTest 11 + Feat033PassiveCompactionTest 14 + Feat033TraceBridgeTest 2 + Feat033IdentifierTest 2 + Feat033ConcurrencyTest 2 + Feat033RetryAndFallbackTest 3 + Feat033DetailsMapCompletenessTest 1 + Feat033LayerProtectionTest 4 + Feat033StateLifecycleTest 1）。

**本次 session SIT 侧累积 7 处 asserter/TC 判据改动**（都有 L2 line 号支撑或产品实证）：

| # | 文件 | 改动 | L2/实证支撑 |
|---|---|---|---|
| 1 | `fixture/CompressionDetailsAsserter.java` EXPECTED_KEYS | 加 `summary_max_tokens` 字段 | L2 Line 263 "完成事件附加数值 summary_max_tokens" |
| 2 | `fixture/CompressionDetailsAsserter.assertAlgorithmVersion` | Number cast → String.valueOf | L2 Line 211 "algorithmVersion=1" + span events JSON `"algorithm_version":"1"` 实证 |
| 3 | `Feat033PassiveCompactionTest.s0` log Pattern | `attempts/llm_calls` → `attempt_count/llm_call_count` | L2 Line 226 §5.3 对外字段表 |
| 4 | `Feat033PassiveCompactionTest.s0` details asserter | 删 `.assertKind()` NO_OP 场景 | L2 Line 215 — compression_usage.details.kind 只在实际 compact 发生时产生 |
| 5 | `Feat033PassiveCompactionTest.assemblyChainE2E` | processor_type `"structured_compact"` → `"StructuredCompactProcessor"` + rail_dispatch_end 完整 Pattern 带 agent_id | L2 Line 240 PROCESSOR_TYPE 常量 + jar `javap` 实证 |
| 6 | `Feat033TraceBridgeTest` 两 TC | processor_type + span events contains + rail_dispatch_end Pattern | L2 Line 215, 240 |
| 7 | `Feat033DetailsMapCompletenessTest.assertConfigSnapshot` | messages_to_keep 10→4 | demo s1 profile `application.yml` 实证 |

**s17/s18 contextId 不同步 bug 修复**：扩展 `CompactProbeHttpClient.trigger` 加 contextId override 重载 + 3 处 TC（Feat033IdentifierTest.s17 / Feat033ConcurrencyTest.s17SnapshotChange / Feat033StateLifecycleTest.s18）全程使用显式 contextId。实证 Round-2 before_tokens 从 1638 变成 1649（Round-1 after + 1 条 mutate user 消息），证明 mutate 操作真到达同一 context。

**s18 删 `compression_history_size >= 1` 判据**（违反 L2 Line 209 "运行中状态不跨进程恢复；saveState/loadState 仅维护算法状态版本，稳定摘要存在消息历史中"）+ **补 `assertForbidden("outcome=NO_OP")` 能红判据**（防原 r5 判据 "log tail 整体搜索" 的假绿 — Round-1 outcome=LLM_COMPACTED 让原 TC 过，但 Round-2 实际走 NO_OP NO_COMPRESSIBLE_MIDDLE，TC 看不见）。

**v7 跑测盘面**（经 7 处 fix + s17/s18 contextId + s18 删/加判据后）：**40 Tests / 5 FAIL** — 转绿 7 条，剩 5 条跟踪项。

**剩余 5 条 FAIL 根因分析**（都是跨仓问题，SIT 侧自己改不了）：

| TC | 根因 | 归属 |
|---|---|---|
| **s17RepeatCompactRepairsIdentifiers** | Round-2 outcome=NO_OP NO_COMPRESSIBLE_MIDDLE — ScenarioFixtures.mutateSource append-user 只加 1 条 user 消息（+11 tokens），Round-1 compact 后保护尾部 + 摘要 user 消息已占满，Round-2 分区后没有可压缩中间段（L2 §3.2 "没有中间段时 no-op"）| **demo 侧** — fixture 结构问题 |
| **s18SaveLoadRoundTripRestoresHistoryAndKeepsMessageCount** | 补 `assertForbidden("outcome=NO_OP")` 后 FAIL — 跟 s17 同根（Round-2 restored state 结构同样没中间段）| **demo 侧** — 跟 s17 同根 fixture |
| **s23RoundBoundaryOddExtendBackward** | 产品 "1 次 LLM NO_DECREASE → fallback deterministic → DETERMINISTIC_COMPACTED" 跟 L2 §3.4 决策表 "连续两次不足" + L2 Line 99 "无法下降返回 no-op" 两处契约有偏差 | **跟 core 对账产品行为是否符合 L2** |
| **activeTriggerCompress** | `/__trigger-active` endpoint `compressContext(contextId, null, null, List.of(PROCESSOR_TYPE), ...)` 调用没真触发 processor（log tail 完全没 phase=\* 日志）；passive path 正常 | **demo 侧** — endpoint bug |
| **s19AsyncCancelDoesNotMutateHistory** | `/__async-result` 顶层无 status 字段（嵌套 result.status）+ completed 状态 buildResponse 不打 status + cancel 不真中止 CompletableFuture | **TC+demo 两侧 schema 不同步** |

**v7 轮 FEAT-033 转绿 TC 清单**（原来 12 FAIL 的 TC 中转绿的 7 条）：
- `Feat033DetailsMapCompletenessTest.detailsMapMatchesL2Schema`（Fix 1+2 修）
- `Feat033LayerProtectionTest.s12ExternalSystemMessagePreserved`（Fix 1 修）
- `Feat033PassiveCompactionTest.s0BelowThresholdNoOp`（Fix 3+4 修）
- `Feat033PassiveCompactionTest.assemblyChainE2E`（Fix 5 修）
- `Feat033TraceBridgeTest.traceBridgeEmitsSpanEventOnCompact`（Fix 6 修）
- `Feat033TraceBridgeTest.traceBridgeCloseAndReinstall`（Fix 6 修）
- `Feat033RetryAndFallbackTest.s16OutputLengthTruncatedFallsBackDeterministic`（demo 10-29 批次 PR!464 upstream OpenAIModelClient.parseResponse finish_reason 修复实证）

**本次 session 关联对账材料**：
- `docs/issues/draft-feat033-r5-asserter-fix-handoff-20261029.md`（本仓 draft — SIT 侧 7 处 asserter fix 对账 r5）
- docsReview `structured-compact-sit-demo/SIT-FEAT033-R1-ANALYSIS-20261029.md`（commit `8d51f1ee` — 跨仓分析 + 5 FAIL 跟踪项详细归属）
- docsReview `structured-compact-sit-demo/SIT-REQUEST-m2-artifacts-20261029.md`（commit `60971ec9` — context-compression artifact 请求，已到位）
- demo 侧 `DEV-REPLY-feat033-pr464-rebuild-20261029.md`（docsReview，demo 15/15 scenario 本机 PASS）
- 本件 memory：待立 `feat033-sit-campaign`

---



### 5.2 2026-10-30 R2 对账回帖落码（demo 侧 R1 答复后的 batch 修正）

> **背景**：demo 侧 2026-10-29 对 SIT R1 分析（见 `docs/issues/draft-feat033-cross-repo-analysis-20261029.md`）回帖
> `DEV-REPLY-feat033-sit-r1-reply-20261029.md`（docsReview `structured-compact-sit-demo/`），
> demo 侧 4 处 endpoint 整改 + HANDOFF.md 一处文档更正；本档随 R2 batch 刷新 TC 本地判据对齐新 schema。

**本次 batch 6 处 SIT 判据改动**（5 文件 +243/-73 行，commit 待 push）：

| # | TC / 组件 | 改动摘要 | 契约/文档支撑 |
|---|---|---|---|
| 1 | `CompressionDetailsAsserter.assertKindAbsentOrStructuredCompact()` | 新加条件判据方法 — null 可接受；非 null 必须 `"structured_compact"` | L2 Line 215 "kind 只在实际 compact 发生时产生"；demo R1 §三 Fix 4 建议 |
| 2 | `CompactProbeHttpClient.loadState(contextId, scenario, injectAfterLoad, timeout)` | 新加重载签名；对接 demo 2026-10-29 新 query param `injectAfterLoad=N` | demo R1 §2.4 新 endpoint 契约 |
| 3 | `Feat033PassiveCompactionTest.s0BelowThresholdNoOp` | 昨天直接删 `.assertKind()` → 升级为条件判据 `.assertKindAbsentOrStructuredCompact()`；防字段 leak / 命名漂移 | 同 1 |
| 4 | `Feat033PassiveCompactionTest.activeTriggerCompress` | 加 `tailer.assertRequired("trigger_source=active_window")` 能红证明判据 | demo R1 §2.2 新 endpoint `getContextWindow + compression_trigger=manual` kwarg + 日志新增 `probe_begin trigger_source=active_window` |
| 5 | `Feat033StateLifecycleTest.s18SaveLoadRoundTripIsStrictlyIdempotent` **（R10.1 严格 round-trip）** | TC 重命名 + 判据重设：撤 `assertRequired("outcome=LLM_COMPACTED")` + 撤 `assertForbidden("outcome=NO_OP")`；改 `assertRequired("outcome=NO_OP")`（Round-2 必须 NO_OP） | demo R1 §2.4 "R10.1 不传 injectAfterLoad → round-2 可观察 NO_OP 或 BELOW_THRESHOLD"；L2 Line 209 "运行中状态不跨进程恢复" |
| 6 | `Feat033StateLifecycleTest.s18R10_2RestoredStateCanContinueCompact` **（新 TC）** | 新 TC — `loadState(contextId, "s18", 6, SHORT)`；验 `messages_injected_after_load == 6` + Round-2 真走 `LLM_COMPACTED SUCCESS` | demo R1 §2.4 "R10.2 injectAfterLoad=N → round-2 真能跑 LLM_COMPACTED SUCCESS" |
| 7 | `Feat033LayerProtectionTest.s23RoundBoundaryOddExtendBackward` **（outcome 放宽）** | outcome 从 `LLM_COMPACTED` 放宽到 `{LLM_COMPACTED, DETERMINISTIC_COMPACTED}`；撤 `assertRequired("outcome=LLM_COMPACTED")` + 撤 `assertRequired("reason=SUCCESS")`；保留 token 下降 + `messages_to_keep=3` 核验 | SIT 独立核对 L2 FEAT-033 文档 sha `844ab3c`：§3.4 决策表 "连续两次不足" subject = LOW_QUALITY 非 NO_DECREASE；§3.4 Line 125 "此降级可能比被拒绝的模型候选更短"；§3.1 flowchart 兼容 "LLM candidate NO_DECREASE → fallback deterministic" |

**关联对账材料**：
- demo R1 答复：`DEV-REPLY-feat033-sit-r1-reply-20261029.md`（docsReview `structured-compact-sit-demo/`，281 行）
- SIT 独立核对：L2 FEAT-033 文档 sha `844ab3c`（Technical-AF/docs `develop/03-architecture/L2-Low-Level-Design/agent-core-ext/Feat-Func-033-passive-context-compression.md`）
- 新 demo jar（本次 batch 待 ECS 实证）：
  - `context-probe-agent-0.1.0.jar` MD5 **`d68a0708178a6f00f3146a619217bea5`**（旧 `6b6ff5862b6496ad32b607436187fa75`）
  - `llm-stub-server-0.1.0.jar` MD5 **`3f978d2e82f2a1771e368de844f11ce3`**（旧 `fe8f493642d429a8f9c35475e0f8739c`）

**本次 batch 盘面预期**（待 ECS 实证）：

| 原 FAIL | 本次改动后预期 |
|---|---|
| `s17RepeatCompactRepairsIdentifiers` | ✅ demo `/__source-mutate` 默认追加 6 条 → Round-2 真跑 LLM_COMPACTED（TC 代码不改即受益） |
| `activeTriggerCompress` | ✅ demo 新 endpoint 走 `getContextWindow` 钩子 + TC 加 `trigger_source=active_window` 能红判据 |
| `s19AsyncCancelDoesNotMutateHistory` | ✅ demo `/__async-result` 顶层加 computed status（TC 代码本来就读顶层，不改即受益） |
| `s18 R10.1` | ✅ 本次 TC 重命名 + 判据重设 — Round-2 本就应该 NO_OP（符合 L2 Line 209） |
| `s18 R10.2`（新） | ✅ 本次新 TC — `injectAfterLoad=6` → Round-2 真跑 LLM_COMPACTED |
| `s16OutputLengthTruncatedFallsBackDeterministic` | ✅ upstream PR!464 已合入（`commit 4cd131a`），demo 侧 jar 已含修复 |
| `s23RoundBoundaryOddExtendBackward` | ✅ outcome 放宽；两条路径 `{LLM, DETERMINISTIC}` 都 PASS |

---



## 6. 回归触发口径（本档不定义 CI 口径，仅给判据边界）

- **主通道回归**：30 条 TC 的 `phase=complete` 日志行（含 r5 新 TC 的 `rail_dispatch_begin/end` + `context.compression_state` span event + `/__compression-history` 多轮快照）required/forbidden 全命中
- **已知可容忍差异**：`TC-MODEL-TIMEOUT-COMPLETABLEFUTURE` 的 `reason=MODEL_ERROR` vs L2 §3.4 期望 `MODEL_TIMEOUT` 系 upstream known-gap（header_wait 超时分类）
- **凭据泄漏红线（R14）**：任一 TC 的 processor 日志 / HTTP body / span event attribute / stub 日志出现哨兵 `SENTINEL-CREDENTIAL-DO-NOT-LEAK-7f3a9c` → **零容忍红**；判据不基于参数名正则（`compressionCallMaxTokens / maxTokens` 不触发）
