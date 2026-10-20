---
feature_id: FEAT-057
feature_title: 主循环运行预算与进度跟踪
sut: run-budget-elastic-research-demo 的 researcher-agent（ReActAgent，openjiuwen 范式，fat jar 独立运行；端口 18092 + mock-knowledge-base 端口 18093）
scope: 本档覆盖 FEAT-057 SIT 侧黑盒可观察（`POST /v1/query` body + SUT 结构化日志的 `turn_budget_*` / `time_budget_*` 事件，event_type 字段小写 snake_case）；UT 侧由 dev team 已交付 8 测试类 33 项（UT-C/B/S/R/T），本档不复制；MUST#18 overlay rail 优先级由 demo S7 构造 + SIT 独立 asserter 旁证，不再 scope out。
status: first-round-executed
status_note: fixture 与用例类已落地（b5ae95a）并经 2026-10-20 ECS 首轮执行修订（见下方「首轮实测勘误与判据修订」节）；S0/S1 Maven 正式 PASS；fat jar 为开发侧 2026-10-20 刷新版（MCP 装配 ReAct 模式 + rail 自我仪表化）。
owner: TBD
tags: [integration, reactagent, feat-057, run-budget, elastic-budget]
depends_on:
  - researcher-agent-0.1.0.jar 已就绪：`D:/openjiuwen-java/2012/agent-solution/common/example/run-budget-elastic-research-demo/researcher-agent/target/`（~183 MB，2026-10-19 15:39 重打）
  - mock-knowledge-base-0.1.0.jar 已就绪：`.../mock-knowledge-base/target/`（~22 MB）
  - LLM_* env（`source ~/.llmrc`）；外部 aliyun LLM 保 HTTPS_PROXY；LLM_TEMPERATURE 默认 0.2（demo README env 清单固定）
  - TAVILY_API_KEY（`source ~/.tavilyrc`）—— S1/S5 必需，S0/S2/S3/S4/S6/S7 可缺省（走 stub 或 mock-kb）
  - 端口 18092（researcher-agent）/ 18093（mock-knowledge-base）可用
related_docs:
  - 本方案 testplan（权威位置）：Technical-AF/docs:develop/04-testplan/FEAT-057-elastic-turn-budget-reactagent.md（PR #331）
  - 特性文档：develop/02-features/FEAT-057-elastic-turn-budget.md（updated 2026-10-10，18 条 MUST）
  - L2 设计：develop/03-architecture/L2-Low-Level-Design/agent-core/Feat-Func-057-elastic-turn-budget.md（updated 2026-10-08；§2.1 事件表 / §2.2 终止 Map / §3 11 条运行模型旅程 / §4.2 钩子 / §7.1 UT-ST 回填）
  - 三方对齐表：agent-solution/FEAT-057-alignment-table.md（updated 2026-10-17；18/18 ✓）
  - 代码口径（唯一事件名真源 + 序列化约定）：agent-core-java@origin/develop → core/.../runbudget/RunBudgetRail.java + .../common/logging/events/LogEventType.java:156-166（枚举名）+ core/.../common/logging/events/BaseLogEvent.toMap:81（小写 snake_case 序列化）
  - demo README：agent-solution/common/example/run-budget-elastic-research-demo/README.md（现版本：8 场景 S0..S7，18/18 MUST 全覆盖）
  - demo trace-asserter（已对齐代码真名小写 snake_case）：.../scripts/trace-asserter.sh
  - demo S7 overlay rail 实现：.../researcher-agent/src/main/java/com/openjiuwen/example/runbudget/agent/ForceFinishAtRoundRail.java
---

# FEAT-057 — 主循环运行预算与进度跟踪 用例设计

> **一句话**：以真实部署的 `researcher-agent` 为 SUT，把 FEAT-057 §2 的 18 条 MUST + L2 §3 的 11 条运行模型旅程 + §2.1 的 11 类事件契约 + §2.2 的终止结果表面，映射为可在 SIT 侧黑盒断言的子用例。demo 现覆盖 **S0..S7 共 8 个场景**（S6 补"有进展检查点延长"、S7 补 overlay rail 优先级），demo trace-asserter 已升级到代码真名的小写 snake_case 字符串（与 `BaseLogEvent.toMap` 的序列化约定一致），SIT 用独立 asserter 对等验证并增加 X1..X5 的严格断言（三阶段严格顺序 / 单射幂等 / reason 字面枚举等 demo asserter 不覆盖的维度）。

> **状态含义**：
> - **runnable**：fixture 就绪 + 可直接落地实现
> - **partial**：核心路径可测，某些维度受 LLM 抖动/工具变异性限制（分层断言、INCONCLUSIVE 兜底）
> - **blocked**：外部依赖缺失（fat jar 未构建、Tavily key 未配置、SUT 启动失败）
> - **deferred**：产品侧未就绪或 scope out（当前 FEAT-057 内无此类项）

---

## 0′. 2026-10-20 首轮实测：勘误与判据修订（先读本节再读后文）

证据底座：ECS 首轮全场景执行（脚本层证据总账 feat057-jars/FEAT-057-脚本层证据总账-2026-10-20.md）+ L2 回补（updated 2026-10-20，forceFinish 可观测性边界）。本节是后文 G/W/T 的覆盖性修订，冲突处以本节为准。

1. **接口勘误（Q1 更新）**：`/v1/query` body 必填 `conversation_id`；响应为 SSE 流。fixture 已适配。
2. **泄漏断言口径更严**：「`turn_budget`/`time_budget` 子对象不穿透 body」在 SSE 形态下的忠实口径 = **扫描流内每一个事件的 payload**（原「响应顶层键」检查的超集）；S0/S1 已按新口径复核通过（历史结论不受影响：两局在新口径下同为零命中）。
3. **S7 判据锚替换（L2 2026-10-20 回补为契约依据）**：forceFinish 通道零框架级事件；rail 终止可观测性 = rail 自我仪表化，观测锚 = rail 自报日志行（`event_type=overlay_rail_force_finish`，纯文本行，用例以原始文本匹配）；`result_type` 框架取值集 = answer/error/interrupt/external_tool_call_required，rail 自定义值（demo 当前为 "finish"）属 rail 私约、不作框架枚举断言；output 键经内容白名单透传、断言非空。原「RuntimeContext.endInvoke finish 行」锚作废。
4. **S4 判据补全（MUST#6 两分支）**：契约原文「该轮之后仍未结束的才终止」——交卷轮内自然 answer（实测 2026-10-20：valve@5→final@6→第 6 轮 stop+answer，UT-S04 对应）与 TERMINATED 均为合法结局，用例按两分支判 PASS；未烧到 hard_limit 判 INCONCLUSIVE（未激活）。
5. **S3 判据分类修订（「提示→听话收尾→不终止」死结）**：临近提示的设计目的即促成收尾，模型服从提示自然完成为合规结局；单局无法同时采到「提示+终止」。用例：到期未触达 → INCONCLUSIVE（非 FAIL），提示单射仍硬校验。wire 证据采用**双局拼证**（A 局 35/15 采提示单射 remaining=8.19s；B 局 18/5 采终止 terminated_at=tool_dispatch + 时限钳制 14541.5ms），已归档。
6. **S2 blocked-on-demo**：停滞事实需宿主经 `RunBudgetSignals.reportNoProgress` 注入（特性档划层；core 默认启发式不做工具语义判定——对照实验：12 连空结果+零评论仍 progressed=true）；demo 未接信号源（docsReview SIT-FEEDBACK-demo-batch2 §A），STALL 结构性不可达，用例判 INCONCLUSIVE 并注明 blocked-on-demo。
7. **场景激活依赖模型个性（执行建议）**：flash 系批量并行调用且服从收尾（次数≠轮次、提前收敛），v4-pro 照章串行——轮次维场景（s2/s4/s6）建议以 `LLM_MODEL=deepseek-v4-pro-0813` 执行；并行批发问题详见 batch2 §C.3。
8′.（2026-10-21 增）**EXTENDED 事件 schema 勘误**：payload 仅 round/allowed_rounds/next_interval，无 hard_limit（源码 RunBudgetRail.java:266-270 + wire 实证）；原"allowed_rounds≤hard_limit"断言改为跨事件取 GUARANTEE 的 hard_limit 作基准。另：CHECKPOINT 事件新增 host_stall_reported 字段（10-21 jar，StallDetectorRail 配套）。
8. **s5 维持 blocked**：等待 core 侧 first-checkpoint 下限裁定（docsReview SIT-CLARIFY-core-first-checkpoint-floor.md，A1/A2 二选一）。

## 0. 执行前置快速清单

- [ ] `researcher-agent-0.1.0.jar` 存在，路径：`D:/openjiuwen-java/2012/agent-solution/common/example/run-budget-elastic-research-demo/researcher-agent/target/`
- [ ] `mock-knowledge-base-0.1.0.jar` 存在，路径：`.../mock-knowledge-base/target/`
- [ ] `source ~/.llmrc` 已执行；`LLM_API_KEY` 非空
- [ ] 代理策略已判定：`LLM_API_BASE=*.aliyuncs.com` → 保 `HTTPS_PROXY`；否则 `unset HTTP_PROXY HTTPS_PROXY`
- [ ] S1/S5 执行前：`source ~/.tavilyrc`
- [ ] S2/S3/S4/S6 执行前：确认 mock-knowledge-base 可拉起（端口 18093 空闲）
- [ ] 18092 / 18093 端口空闲

---

## 1. 覆盖矩阵（18 条 MUST → 子用例）

对应特性文档 §2 的 18 条 MUST（与 L2 §1.2 能力对齐矩阵、alignment table 一一对应）；本档每行一条子用例。SIT 侧状态填"runnable"表示方案已就绪、fixture 待落地；填"partial"表示方案可行但某些断言维度弱（LLM 抖动）；填"deferred"表示 SIT 不承接（demo scope out 或 UT 已覆盖）。

| # | FEAT-057 MUST | 本档子用例 ID | 覆盖场景 | 状态 | UT 承接（不复制） | 备注 |
|---|---|---|---|---|---|---|
| 1 | 保底轮次可配 | `FEAT-057.turn-guarantee-configured` | S1（suggested=20，configured） | runnable | UT-C02 / UT-C03 | 事件 `GUARANTEE_EFFECTIVE.source==configured` + `guaranteed_rounds==20` |
| 2 | 保底轮次语义（下限、保底内零干预） | `FEAT-057.turn-guarantee-no-interference` | S1 | runnable | UT-B01 / UT-B03 | 保底区间（round < max(10, 保底)）内仅出现 `GUARANTEE_EFFECTIVE` 一事件 |
| 3 | 保底后检查点评估与延长 | `FEAT-057.turn-checkpoint-extension` | S1（弱）/ S2 / S5 / **S6（"有进展延长"硬断言）** | runnable | UT-B03-B05 | S6 直接硬断言 `CHECKPOINT`+`EXTENDED`（每次工具返回不同内容，progressed=true）；S1/S2/S5 保留弱断言 |
| 4 | 停滞提示（含连续 3 升级 + 超保底×2 每 15 轮温和提醒） | `FEAT-057.turn-stall-prompt-escalation` | S2 + S5 | partial | UT-B06-B09 | S2 验连续 3 升级；S5 验温和提醒独立于停滞（只按轮次） |
| 5 | 干预只提示不硬砍 | `FEAT-057.turn-intervention-no-hard-cut` | S2 | runnable | UT-B06 / UT-B08 | S2 执行完整 trace 中禁出现 `TURN_BUDGET_TERMINATED`（除非自然到安全阀） |
| 6 | 绝对上限安全阀（指令→交卷轮→终止，三阶段幂等） | `FEAT-057.turn-safety-valve-three-phase` | S4 + X1 | runnable | UT-S01-S05 | X1 补三阶段严格顺序 + FINAL_ROUND_GRANTED 幂等单发 |
| 7 | 总时间预算可配（per-invoke 隔离、不落共享实例、恢复新周期） | `FEAT-057.time-per-invoke-isolated` | S3 / S5 | partial | UT-T01 / UT-T02 | 恢复周期在 demo scope 外；SIT 验 per-invoke 隔离（两次 invoke 独立计时） |
| 8 | 到期不派发新工作（迭代/模型调用/重试/工具派发四处） | `FEAT-057.time-no-new-dispatch-after-exhaust` | S3 + X5 | partial | UT-T03 | S3 验正常到期；X5 验到期在安全阀交卷轮前抢占 |
| 9 | 时限按剩余预算收紧（模型 timeout + 工具 orTimeout + 重试等待） | `FEAT-057.time-timeout-clamping` | 隐含在 S3（弱层） | partial | UT-T04 / UT-T05 | 黑盒无法直读 `ModelInvokeOptions.timeout`；只做"到期前后 model_call/tool_dispatch 时长包络"弱断言 |
| 10 | 临近截止收尾提示（一次且仅一次） | `FEAT-057.time-near-deadline-single-shot` | S3 + X2 | runnable | UT-T06 | X2 严格单射断言：trace 中 `NEAR_DEADLINE_PROMPTED` count == 1 |
| 11 | 到期终止语义（可识别、与 user_cancelled 可区分、同步/流式一致、已产出可读、尽力取消） | `FEAT-057.time-termination-identifiable` | S3 + X3 | runnable | UT-T07 | X3 验 reason 字符串完全枚举 + body 文案锚 |
| 12 | 时间维兼容（未声明零变化、两维独立启用） | `FEAT-057.time-dim-independent-enablement` | S0（否定）+ S1（只轮次）+ S5（两维） | runnable | UT-T01 | 三组合覆盖矩阵 |
| 13 | 两维并存规则（独立计数计时、硬边界例外、来源可区分、互不抑制） | `FEAT-057.both-dims-coexistence` | S5 + X5 | partial | UT-T08 | S5 验正常并存 + 温和提醒；X5 验时间硬边界否决交卷轮（INCONCLUSIVE 容忍） |
| 14 | 存量显式上限兼容（显式 `llm.max-iterations` 时轮次维硬截断不变） | `FEAT-057.legacy-max-iterations-compat` | S0 + X4 | runnable | UT-C04 | X4 补零事件断言（锚代码真名） |
| 15 | 新增配置只增量（既有配置项零改动） | `FEAT-057.config-additive-only` | 隐含在 S0 | runnable | UT-C01 | SIT 不做源码检查；S0 回归行为逐字节 |
| 16 | 未声明新配置行为不变（逐字节） | `FEAT-057.unset-config-byte-equivalence` | S0 + X4 | runnable | UT-C01 / UT-C05 | X4 零 `TURN_BUDGET_*` + `TIME_BUDGET_*` 事件；body 文案与 result_type 与基线相等 |
| 17 | 预算与进度事件（11 类事件 + 载荷契约） | `FEAT-057.events-contract-11-types` | 所有场景 | runnable | 各组事件断言 UT | 本档每个场景都做事件断言；锚代码真名 |
| 18 | 与 overlay rail 的优先级（rail forceFinish 优先、不抑制 rail 干预） | `FEAT-057.overlay-rail-priority` | S7 | runnable | UT-R01-R03 | demo 侧已造 `ForceFinishAtRoundRail`(priority=80, atRound=3)；SIT 验 `GUARANTEE_EFFECTIVE` 发射 + 禁 `TURN_BUDGET_TERMINATED` / `SAFETY_VALVE_TRIGGERED` / `STALL_PROMPTED` + 结果 Map 的 `reason=="overlay_rail_force_finish"` |

**SIT 补盲场景汇总**：

| X ID | 补盲重点 | 对应 MUST | 覆盖场景 | 状态 |
|---|---|---|---|---|
| X1 | 安全阀三阶段事件严格顺序 + FINAL_ROUND_GRANTED 幂等单发 | #6 | 复用 S4 | runnable |
| X2 | 临近截止提示严格单射 | #10 | 复用 S3 或自造长执行 | runnable |
| X3 | 终止 reason 字符串完全枚举（time_budget vs turn_budget vs user_cancelled 可区分） | #11 | S3 + S4 trace 对照 | runnable |
| X4 | 存量 S0 的事件零出现：严格对 **11 枚代码真名事件** 做 assertAbsent（demo asserter 虽已升级名字，但只断言 2 枚核心事件；本 X4 覆盖全 11 枚） | #14 / #16 | 复用 S0 | runnable |
| X5 | 两维交互：时间到期在 safety-valve 收尾序列中抢占交卷轮派发 | #8 / #13 | 自造叠加配置（S4 base + 短 time 预算） | partial（INCONCLUSIVE 容忍） |

---

## 2. 子用例 G/W/T 详细

### 2.1 S0 — 兼容基线（MUST #14 #15 #16）

**ID**：`FEAT-057.legacy-max-iterations-compat`（主）+ `FEAT-057.unset-config-byte-equivalence` + `FEAT-057.config-additive-only`

- **G**（Given）：
  - researcher-agent 以 `--spring.profiles.active=s0` 启动；profile 配置 `openjiuwen.service.llm.max-iterations=10`，未声明 `run-budget.turn.*` 与 `run-budget.time.*`。
  - web_search 走 stub（`USE_STUB_WEB_SEARCH=true`），不依赖 Tavily；mock-kb 不启动。
  - LLM env 就绪。
- **W**（When）：
  - `POST /v1/query`，body `{"message":"<研究类 prompt>"}`（demo HTTP 入口，已由开发团队 2026-10-19 回复确认）。
- **T**（Then）：
  - **硬层**：
    - `/v1/query` HTTP 200；response body `result_type ∈ {"answer","error"}`。
    - 若 `result_type=="error"`，`output == "Max iterations reached without completion"`（存量超轮文案，字节相等）。
    - **SUT 日志事件零 `TURN_BUDGET_*` + `TIME_BUDGET_*`**（锚 11 枚代码真名，详见 §2.11 fixture 的 assertAbsent 枚举集）。
    - response body 不含 `turn_budget` / `time_budget` 顶层字段。
  - **弱层**：
    - 若自然完成（`result_type=="answer"`），`output` 非空（≥50 字符的研究结果）。
- **PASS**：硬层全过 + 弱层若适用全过。
- **FAIL**：出现任一代码真名事件；或 body 含预算子对象；或超轮 output 文案被改。
- **INCONCLUSIVE**：SUT 启动失败；LLM 返回 429/502。

---

### 2.2 S1 — 保底顺畅（MUST #1 #2 #3）

**ID**：`FEAT-057.turn-guarantee-configured` + `FEAT-057.turn-guarantee-no-interference` + `FEAT-057.turn-checkpoint-extension`

- **G**：profile=s1；`run-budget.turn.enabled=true` + `suggested-rounds=20`；Tavily 可用；mock-kb 不启动。
- **W**：发研究类 prompt（预期 LLM 在 20 轮内自然收敛）。
- **T**：
  - **硬层**：
    - 事件 `TURN_BUDGET_GUARANTEE_EFFECTIVE` 恰出现 1 次；payload: `guaranteed_rounds==20`、`source=="configured"`、`hard_limit==max(40,200)==200`。
    - 事件集**禁出现**：`TURN_BUDGET_SAFETY_VALVE_TRIGGERED` / `TURN_BUDGET_FINAL_ROUND_GRANTED` / `TURN_BUDGET_TERMINATED` / `TIME_BUDGET_EFFECTIVE` / `TIME_BUDGET_*`。
    - 若自然完成（期望路径）：无 `TURN_BUDGET_STALL_PROMPTED`、`TURN_BUDGET_GENTLE_REMINDER`。
    - `/v1/query` body `result_type=="answer"` + `output` 非空。
  - **弱层**：
    - 若执行超过 `firstCheckpoint=max(10,20)==20` 轮：`TURN_BUDGET_CHECKPOINT` 出现 ≥1 次（progressed=true 或 false 不硬断，LLM 吞吐依赖）。
    - 若有进展：`TURN_BUDGET_EXTENDED` 可能出现（间隔递增至 50 封顶）。
- **PASS**：硬层全过。
- **FAIL**：预算未激活（无 GUARANTEE_EFFECTIVE）；或出现终止类事件；或 body 有 `result_type=="error"` 但不是存量路径的其他 reason。
- **INCONCLUSIVE**：Tavily 配额耗尽；LLM 多次 timeout。

---

### 2.3 S2 — 停滞升级（MUST #4 #5）

**ID**：`FEAT-057.turn-stall-prompt-escalation` + `FEAT-057.turn-intervention-no-hard-cut`

- **G**：profile=s2；`turn.enabled=true` + `suggested-rounds=10`；mock-kb 启动 + `MOCK_KB_EMPTY_ENABLED=true`（空结果工具迫停）。
- **W**：发触发反复 MCP 检索的 prompt（LLM 预期多次尝试 `empty_knowledge_search`）。
- **T**：
  - **硬层**：
    - 事件 `TURN_BUDGET_GUARANTEE_EFFECTIVE` 恰 1 次；`guaranteed_rounds==10`。
    - 超保底后出现 `TURN_BUDGET_CHECKPOINT` 事件，至少 1 次 `progressed==false`。
    - 出现 `TURN_BUDGET_STALL_PROMPTED` 事件 ≥1 次。
    - 若连续 3 停滞达成：出现 `TURN_BUDGET_STALL_PROMPTED` with `escalated==true`。
    - **禁出现**：`TURN_BUDGET_TERMINATED`（干预只提示不硬砍——除非 LLM 消耗到 hard_limit=200，S2 场景正常不应达到）。
    - response body `result_type=="answer"` 或 `result_type=="error"` 为自然收尾/模型主动放弃；若为 `error` 且 output 是存量超轮文案，判 INCONCLUSIVE（LLM 决定继续到 maxIterations=hardLimitMax+1=201）。
  - **弱层**：
    - `escalated==true` 的 `STALL_PROMPTED` 若未出现（LLM 中途产出一次新内容打断停滞计数），记录但不判 FAIL。
- **PASS**：硬层全过（含 STALL_PROMPTED 出现）。
- **FAIL**：出现 `TURN_BUDGET_TERMINATED` 且 round < hard_limit；或 CHECKPOINT 永不出现。
- **INCONCLUSIVE**：LLM 一轮内就 answer（无停滞）；mock-kb 不可达。

---

### 2.4 S3 — 时间维完整（MUST #7-#11 #13）

**ID**：`FEAT-057.time-per-invoke-isolated` + `FEAT-057.time-no-new-dispatch-after-exhaust` + `FEAT-057.time-near-deadline-single-shot`（基础）+ `FEAT-057.time-termination-identifiable`

- **G**：profile=s3；`run-budget.time.total-budget-seconds=90` + `near-deadline-threshold-seconds=30`；mock-kb 启动 + `MOCK_KB_SLOW_ENABLED=true, MOCK_KB_SLOW_MS=20000`。
- **W**：发触发多次慢工具调用的 prompt（每次工具 sleep 20s，预期 90s 内会到期）。
- **T**：
  - **硬层**（事件严格顺序）：
    - `TIME_BUDGET_EFFECTIVE` 恰 1 次；payload: `total_budget_seconds==90`、`near_deadline_threshold_seconds==30`、`source=="configured"`。
    - `TIME_BUDGET_NEAR_DEADLINE_PROMPTED` 恰 1 次（**X2 严格单射断言**）；payload `remaining_seconds <= 30`、`threshold_seconds==30`。
    - `TIME_BUDGET_TERMINATED` 恰 1 次；payload: `reason=="time_budget_exhausted"`、`terminated_at ∈ {"model_call", "tool_dispatch"}`、`elapsed_seconds >= 90`。
    - 事件顺序：`EFFECTIVE` → `NEAR_DEADLINE_PROMPTED` → `TERMINATED`。
    - **禁出现**：`TURN_BUDGET_*`（profile=s3 未启用轮次维）。
    - `/v1/query` body: `result_type=="error"` + `output=="本次执行的时间预算已到期，任务被终止。"`；**body 不含** `time_budget` 顶层字段（L2 §2.2 不穿透）。
  - **弱层**：
    - 工具实际 timeout 行为（`orTimeout` 钳制到剩余预算）：到期前最后一次工具调用的 wall-time 应 < 20s（被钳制），弱断言——难以精确抓取 wall-time，可从日志时序弱推断。
- **PASS**：硬层全过。
- **FAIL**：事件缺失任一；顺序错；NEAR_DEADLINE_PROMPTED 多次出现；body 含 `time_budget` 子对象；output 文案不符。
- **INCONCLUSIVE**：LLM 一开始就不调工具自然 answer（时间未到期）；mock-kb 不可达。

---

### 2.5 S4 — 安全阀（MUST #6）

**ID**：`FEAT-057.turn-safety-valve-three-phase`（主）+ `FEAT-057.events-contract-11-types`（事件 payload 契约）

- **G**：profile=s4；`turn.enabled=true` + `suggested-rounds=10` + `hard-limit=15`；mock-kb 启动 + `MOCK_KB_CRITIC_ENABLED=true`（永不满足工具）。
- **W**：发触发反复要"再查"的 prompt（LLM 预期进入死循环直到 hard_limit=15）。
- **T**：
  - **硬层**（事件严格顺序，X1 补盲重点）：
    1. `TURN_BUDGET_GUARANTEE_EFFECTIVE`（round=0, `guaranteed_rounds==10`, `hard_limit==15`）
    2. 中间可能出现 `CHECKPOINT` / `STALL_PROMPTED` / `GENTLE_REMINDER`（弱断言）
    3. `TURN_BUDGET_SAFETY_VALVE_TRIGGERED`（round==15, `final_round==16`, `instruction_injected==true`）
    4. `TURN_BUDGET_FINAL_ROUND_GRANTED`（round==16，**整个 trace 恰出现 1 次**）
    5. `TURN_BUDGET_TERMINATED`（round==16, `reason=="turn_budget_exhausted"`, `hard_limit==15`）
    - **步骤 3→4→5 严格顺序**，中间不得插入 `CHECKPOINT`/`STALL_PROMPTED`/`GENTLE_REMINDER`（L2 §4.2 "安全阀接管后检查点评估停止"）。
    - `/v1/query` body: `result_type=="error"` + `output=="已达到轮次预算绝对上限，任务被终止。"`；body 不含 `turn_budget` 顶层字段。
  - **弱层**：
    - 保底区间内（round < max(10,10)=10）事件数 == 1（仅 GUARANTEE_EFFECTIVE）。
- **PASS**：硬层全过。
- **FAIL**：三阶段任一缺失；FINAL_ROUND_GRANTED 出现 ≥2 次；SAFETY_VALVE_TRIGGERED 后出现 CHECKPOINT；body 含子对象；output 文案不符。
- **INCONCLUSIVE**：LLM 中途主动放弃 answer（未到 hard_limit）；mock-kb 不可达。

---

### 2.6 S5 — 两维并存 + 温和提醒（MUST #12 #13 + 温和提醒独立于停滞）

**ID**：`FEAT-057.both-dims-coexistence` + `FEAT-057.turn-stall-prompt-escalation`（温和提醒子项）

- **G**：profile=s5；`turn.suggested-rounds=5` + `time.total-budget-seconds=240`；Tavily 可用；mock-kb 不启动（走 Tavily 真实检索）。
- **W**：发中等复杂度研究 prompt（预期执行超过 保底×2=10 轮触发温和提醒，但不到 hard_limit=200，不到 time 到期 240s）。
- **T**：
  - **硬层**：
    - 同时出现 `TURN_BUDGET_GUARANTEE_EFFECTIVE` + `TIME_BUDGET_EFFECTIVE`（两维独立启用的证据）。
    - 若执行超过保底×2=10 轮：出现 `TURN_BUDGET_GENTLE_REMINDER`（**独立于停滞判定**，L2 §4.2 "只按轮次驱动"）。
    - **禁出现**：`SAFETY_VALVE_TRIGGERED` / `TIME_BUDGET_TERMINATED`（两维都未触底）。
    - `/v1/query` body `result_type=="answer"`。
  - **弱层**：
    - 温和提醒间隔 == 15（L2 默认 `gentle-reminder-interval`）：相邻两次 `GENTLE_REMINDER.round` 差 ≈ 15 ±1（LLM 轮次计数边界）。
- **PASS**：硬层全过。
- **FAIL**：只出现一维事件；出现终止类事件（除非 LLM 消耗到 hard_limit=200/time=240s，判 INCONCLUSIVE）。
- **INCONCLUSIVE**：LLM 一轮答完（未超保底×2，无温和提醒）；Tavily 不可达。

---

### 2.7 S6 — 有进展检查点延长（MUST #3 "有进展延长"分支硬断言）

**ID**：`FEAT-057.turn-checkpoint-extension`（硬层主承载）

- **G**：profile=s6；`run-budget.turn.enabled=true` + `suggested-rounds=10` + `checkpoint.first-checkpoint=10`；mock-kb 启动 + `MOCK_KB_SLOW_ENABLED=true`（slow_knowledge_fetch 每次返回不同内容，确保 progressed=true）。
- **W**：发触发多次慢检索的 prompt，每轮 LLM 调用 `slow_knowledge_fetch` 收到新 uri 内容。
- **T**：
  - **硬层**：
    - 事件 `TURN_BUDGET_GUARANTEE_EFFECTIVE` 恰 1 次；`guaranteed_rounds==10`、`source=="configured"`。
    - 事件 `TURN_BUDGET_CHECKPOINT` ≥1 次；至少一次 `progressed==true`。
    - 事件 `TURN_BUDGET_EXTENDED` ≥1 次，紧随 `CHECKPOINT.progressed==true`；payload: `allowed_rounds <= hard_limit`（钳制在上界内）。
    - **禁出现**：`TURN_BUDGET_STALL_PROMPTED` / `TURN_BUDGET_SAFETY_VALVE_TRIGGERED` / `TURN_BUDGET_TERMINATED` / `TIME_BUDGET_EFFECTIVE`。
  - **弱层**：
    - 检查点间隔随 index 递增（相邻 CHECKPOINT.round 差呈 step=10 增长，直到封顶 50）—— LLM 吞吐依赖，弱断言。
- **PASS**：硬层全过。
- **FAIL**：`EXTENDED` 未出现（有进展但未延长）；或 `allowed_rounds` 越过 hard_limit；或出现 STALL_PROMPTED（有进展不应判停滞）。
- **INCONCLUSIVE**：LLM 一轮答完未进入 checkpoint；mock-kb 不可达。

---

### 2.8 S7 — overlay rail 优先于 run-budget（MUST #18）

**ID**：`FEAT-057.overlay-rail-priority`

- **G**：profile=s7；`run-budget.turn.enabled=true` + `suggested-rounds=10`；demo 侧注册 `ForceFinishAtRoundRail`(priority=80, atRound=3)；web_search 走 stub。
- **W**：发任意研究类 prompt（LLM 预期至少执行 3+ 轮进入 overlay rail 短路点）。
- **T**：
  - **硬层**：
    - 事件 `TURN_BUDGET_GUARANTEE_EFFECTIVE` 恰 1 次（预算仍启用，证明不是预算未装配导致不发射终止）。
    - **禁出现**：`TURN_BUDGET_TERMINATED` / `TURN_BUDGET_SAFETY_VALVE_TRIGGERED` / `TURN_BUDGET_FINAL_ROUND_GRANTED` / `TURN_BUDGET_STALL_PROMPTED` / `TIME_BUDGET_EFFECTIVE`（run-budget 不得越位发终止）。
    - SUT 日志的 finish 行（`RuntimeContext.endInvoke`）或终止结果 Map 中出现字符串 `overlay_rail_force_finish`（证据：overlay rail 的 reason 承载通到终止侧）。
    - `/v1/query` body 的 `output` 可能是 overlay rail 定义的 summary 文案或 answer；**不含**运行预算三文案（"轮次预算绝对上限"/"时间预算已到期"）。
  - **弱层**：
    - 本次 invoke 的实际轮次 ≥ 3（证明 overlay rail 的 atRound=3 确实触发）—— 弱断言，LLM 可能更早答完使 overlay 不触发。
- **PASS**：硬层全过。
- **FAIL**：`TURN_BUDGET_TERMINATED` 或 `SAFETY_VALVE_TRIGGERED` 出现（预算越位）；或 body 含预算文案。
- **INCONCLUSIVE**：LLM 一轮答完（overlay 未触发，无从验证）；SUT 启动失败。

---

### 2.9 X1 — 【补盲】安全阀三阶段严格顺序 + FINAL_ROUND_GRANTED 幂等单发

**ID**：`FEAT-057.turn-safety-valve-three-phase` 的二次断言

- **G/W**：复用 S4 trace（本用例对 S4 执行输出做独立 asserter 验证）。
- **T**：
  - `SAFETY_VALVE_TRIGGERED.round == 15` 且 `instruction_injected == true` 且 `final_round == 16`。
  - 紧随 `FINAL_ROUND_GRANTED.round == 16`；trace 全局 count == 1（幂等）。
  - 紧随 `TERMINATED.round == 16, reason == "turn_budget_exhausted"`。
  - 三事件在 trace 时间戳上严格递增（SUT 日志行号序即可）。
  - 三事件之间不得插入 `CHECKPOINT`/`EXTENDED`/`STALL_PROMPTED`/`GENTLE_REMINDER`。
- **PASS/FAIL**：同 S4，但本用例独立计算 count 与顺序。
- **INCONCLUSIVE**：S4 本身 INCONCLUSIVE 时级联。

---

### 2.10 X2 — 【补盲】临近截止提示严格单射

**ID**：`FEAT-057.time-near-deadline-single-shot`

- **G/W**：复用 S3 trace。
- **T**：
  - `TIME_BUDGET_NEAR_DEADLINE_PROMPTED` 在整个 trace 中 **count == 1**（L2 §4.2 state 字段 `nearDeadlinePromptSent` 幂等）。
  - 即使 remaining_seconds 持续低于 threshold（多次模型调用检查点都满足 remaining < 30），事件也只发射一次。
- **PASS/FAIL**：严格 count。count≠1 判 FAIL（除非 count==0 且 S3 整体 INCONCLUSIVE）。
- **INCONCLUSIVE**：S3 INCONCLUSIVE 级联。

---

### 2.11 X3 — 【补盲】终止 reason 完全可区分

**ID**：`FEAT-057.time-termination-identifiable`

- **G/W**：S3 + S4 执行后 trace 对照。
- **T**：
  - S3 的 `TIME_BUDGET_TERMINATED.reason == "time_budget_exhausted"`（字符串相等）。
  - S4 的 `TURN_BUDGET_TERMINATED.reason == "turn_budget_exhausted"`（字符串相等）。
  - 两 reason 字符串字面不同；与 `"user_cancelled"` 字面也不同（弱断言，本档不构造取消路径）。
  - **容忍 reason 名漂移如果 L2 更新**：当前锚 `turn_budget_exhausted` / `time_budget_exhausted`，若未来 L2 §2.2 变更，asserter 需同步。
- **PASS/FAIL**：字符串完全枚举。
- **INCONCLUSIVE**：S3/S4 任一 INCONCLUSIVE。

---

### 2.12 X4 — 【补盲】存量 S0 事件零出现（代码真名锚）

**ID**：`FEAT-057.unset-config-byte-equivalence`（真名事件维度）

- **G/W**：复用 S0 trace。
- **T**：
  - trace 中 `TURN_BUDGET_GUARANTEE_EFFECTIVE` + `TURN_BUDGET_CHECKPOINT` + `TURN_BUDGET_EXTENDED` + `TURN_BUDGET_STALL_PROMPTED` + `TURN_BUDGET_GENTLE_REMINDER` + `TURN_BUDGET_SAFETY_VALVE_TRIGGERED` + `TURN_BUDGET_FINAL_ROUND_GRANTED` + `TURN_BUDGET_TERMINATED` + `TIME_BUDGET_EFFECTIVE` + `TIME_BUDGET_NEAR_DEADLINE_PROMPTED` + `TIME_BUDGET_TERMINATED` **共 11 枚事件全部出现 0 次**。
  - 本用例的 asserter 用 `assertAbsent(ALL_11_EVENTS)`；demo asserter S0 场景只断言 `turn_budget_guarantee_effective` + `time_budget_effective` 两枚，本 X4 补齐其余 9 枚（CHECKPOINT/EXTENDED/STALL_PROMPTED/GENTLE_REMINDER/SAFETY_VALVE_TRIGGERED/FINAL_ROUND_GRANTED/TURN_BUDGET_TERMINATED/NEAR_DEADLINE_PROMPTED/TIME_BUDGET_TERMINATED）的 absent 严格断言。
- **PASS**：11 枚全部 0 count。
- **FAIL**：任一事件出现（表示 gating 失效 / rail 被意外注册）。
- **INCONCLUSIVE**：S0 本身 INCONCLUSIVE。

---

### 2.13 X5 — 【补盲】两维交互：时间到期抢占交卷轮派发

**ID**：`FEAT-057.both-dims-coexistence` 时间硬边界子项

- **G**：自造 overlay profile `s4-time`（SIT 侧 `--spring.config.import` 或 env 覆盖）：base=s4（`turn.enabled=true, suggested-rounds=10, hard-limit=15, critic 工具`）+ overlay `time.total-budget-seconds=60`（短）。期望 LLM 在 hard-limit=15 轮前（但轮次已经进入安全阀注入指令后、交卷轮派发前）到期。
- **W**：发触发反复 critic 工具调用的 prompt。
- **T**：
  - **硬层**：
    - 事件 trace 中出现 `TIME_BUDGET_TERMINATED`。
    - 若 `TURN_BUDGET_SAFETY_VALVE_TRIGGERED` 也出现（LLM 吞吐够快进入安全阀）：`TIME_BUDGET_TERMINATED` 的时间戳 < `TURN_BUDGET_FINAL_ROUND_GRANTED` 的时间戳（或 FINAL_ROUND_GRANTED 根本不出现——时间维否决了交卷轮派发）。
    - `/v1/query` body `output` 为时间维终止文案（`"本次执行的时间预算已到期，任务被终止。"`），**不是**轮次维文案。
  - **容忍 INCONCLUSIVE**：时间维与轮次维赛跑受 LLM 吞吐 + 工具 wall-time 影响：
    - 若反向胜出（轮次先触底、`TURN_BUDGET_TERMINATED` 先于时间到期）→ retry 一次；仍反向 → INCONCLUSIVE（非 FAIL）。
    - 若两维都未触发（LLM 一轮答完）→ INCONCLUSIVE。
- **PASS**：硬层全过。
- **FAIL**：出现 `TURN_BUDGET_FINAL_ROUND_GRANTED` 之后 `TIME_BUDGET_TERMINATED`（硬边界失效）。
- **INCONCLUSIVE**：见"容忍"条款。

---

## 3. demo S0..S7 scenario × 本档子用例对照

> **事件名漂移已解除**：demo trace-asserter 已由开发团队 2026-10-19 升级到代码真名的小写 snake_case（与 `BaseLogEvent.toMap:81` 的序列化约定一致），下表 "demo asserter 期望事件" 列已对齐。SIT 侧 asserter 使用相同小写 snake_case 字符串做 wire 层匹配，内部 API 入口以大写 SNAKE_CASE 枚举名（契约可读）暴露，由 asserter 内部做小写转写。SIT 的增量价值在于补盲 X1..X5 的严格断言维度（三阶段严格顺序 / 幂等单发 / reason 字面枚举 / 存量零出现严格 11 枚锚 / 两维交互硬边界），demo asserter 不覆盖这些维度。

| demo 场景 | demo asserter 期望事件（代码真名小写 snake_case） | 本档对应子用例 | 本档增量断言 |
|---|---|---|---|
| S0 | 必现 `max_iterations`；禁 `turn_budget_guarantee_effective` / `time_budget_effective` / `[轮次预算]` / `[时间预算]` | 本档 S0 + X4 | X4 严格 11 枚代码真名事件集合 absent 断言 |
| S1 | 必现 `turn_budget_guarantee_effective`；禁 `turn_budget_safety_valve_triggered` / `turn_budget_terminated` / `time_budget_effective` / `time_budget_terminated` | 本档 S1 | GUARANTEE_EFFECTIVE payload 严格校验（guaranteed_rounds==20、source=="configured"） |
| S2 | 必现 `turn_budget_guarantee_effective` + `turn_budget_checkpoint` + `turn_budget_stall_prompted` + `[轮次预算]`；禁 `turn_budget_safety_valve_triggered` / `turn_budget_terminated` / `time_budget_effective` | 本档 S2 | STALL_PROMPTED.escalated==true 严格断言（demo asserter 不校验 payload） |
| S3 | 必现 `time_budget_effective` → `time_budget_near_deadline_prompted` → `time_budget_terminated` + `time_budget_exhausted` + `[时间预算]`；禁 `turn_budget_guarantee_effective` | 本档 S3 + X2 + X3 | X2 严格单射 count==1；X3 reason 字面与 user_cancelled 对比 |
| S4 | 必现 `turn_budget_guarantee_effective` + `turn_budget_safety_valve_triggered` + `turn_budget_final_round_granted` + `turn_budget_terminated`；禁 `time_budget_effective` | 本档 S4 + X1 + X3 | X1 三阶段严格时间戳递增 + FINAL_ROUND_GRANTED count==1 幂等 |
| S5 | 必现 `turn_budget_guarantee_effective` + `time_budget_effective` + `turn_budget_gentle_reminder` + `[轮次预算]`；禁 `turn_budget_safety_valve_triggered` / `turn_budget_terminated` / `time_budget_terminated` | 本档 S5 | GENTLE_REMINDER 独立于停滞判定（相邻 round 差 ≈ 15） |
| S6 | 必现 `turn_budget_guarantee_effective` + `turn_budget_checkpoint` + `turn_budget_extended`；禁 `turn_budget_stall_prompted` / `turn_budget_safety_valve_triggered` / `turn_budget_terminated` / `time_budget_effective` | 本档 S6 | EXTENDED.allowed_rounds 钳制在 hard_limit 内；progressed==true 事件对齐 |
| S7 | 必现 `turn_budget_guarantee_effective` + `overlay_rail_force_finish`；禁 `turn_budget_terminated` / `turn_budget_safety_valve_triggered` / `turn_budget_stall_prompted` / `time_budget_effective` | 本档 S7 | run-budget 不越位发终止；预算仍启用；overlay reason 承载至终止表面 |

**结论**：demo 场景与 SIT 子用例一对一对齐；SIT 补盲集中在 payload schema 严格校验 + 事件顺序/幂等/数量的 asserter 侧 count 断言，demo asserter 做 presence/absence 粗断言、SIT asserter 做 payload/count/sequence 细断言，两套互补。

---

## 4. Fixture 落点规划（未实现）

| Fixture | 职责 | 落点（规划） |
|---|---|---|
| `DemoFatJarLauncher` | ProcessBuilder 拉起 `researcher-agent-0.1.0.jar`；注入 env + Spring profile；端口 ready probe；参考 `LayeredLauncher`（CustomRestReactAgentBlackboxTest.java:742-874） | `src/test/java/com/huawei/ascend/sit/cases/integration/run_budget_react/fixture/DemoFatJarLauncher.java` |
| `MockKbLauncher` | ProcessBuilder 拉起 `mock-knowledge-base-0.1.0.jar`；按 env 开关三工具 | `.../fixture/MockKbLauncher.java` |
| `ReactAgentHttpClient` | HttpClient 直发 `POST /v1/query`（body `{"message":"<prompt>"}`，由开发团队 2026-10-19 回复确认）；返回 `V1QueryResponse(output, result_type, turn_budget?, time_budget?)`；body 子对象只做"存在性"检查断言不穿透 | `.../fixture/ReactAgentHttpClient.java` |
| `SutLogTailer` | tail SUT 日志，解析结构化事件 → `List<RunBudgetLogEvent>`；提供 `collectAll(timeout)` / `blockingWaitFor(type, timeout)` | `.../fixture/SutLogTailer.java` |
| `RunBudgetEventAsserter` | 按代码真名事件做断言：`assertPresent/absent/count/sequence/payload` | `.../fixture/RunBudgetEventAsserter.java` |
| `TavilyEnvGuard` | S1/S5 用例前 `assumeTrue(TAVILY_API_KEY != null)` + ping | `.../fixture/TavilyEnvGuard.java` |
| `Feat057DemoScenarioTest` | 8 条场景方法 s0..s7（覆盖 demo 全量场景） | `src/test/java/com/huawei/ascend/sit/cases/integration/run_budget_react/Feat057DemoScenarioTest.java` |
| `Feat057BlindspotTest` | X1..X5 的二次断言与叠配置 overlay 场景 | `.../run_budget_react/Feat057BlindspotTest.java` |

---

## 5. 待确认 / 待澄清

### 5.1 已反馈（可直接落地）

- **Q1（已反馈 2026-10-19）**：researcher-agent 的 HTTP 入口是 **`POST /v1/query`**，body shape 为 **`{"message":"<prompt>"}`**（demo README §运行 + HANDOFF.md + run-scenarios.sh 中 curl 构造一致）。**2026-10-20 勘误**：10-19 重打 jar 起 body 必填 `conversation_id`（缺则 `{"error":"conversation_id is required"}`）；且响应为 **SSE 流**（`data: {...}` 行）而非一次性 JSON。`ReactAgentHttpClient` fixture 已按实测适配（conversation_id + SSE 解析锚最后 answer 事件）。
- **Q2（已反馈 2026-10-19）**：SUT 日志结构化事件由 `DefaultLogger.logEvent` 发射为 JSON 行，字段 `"event_type"` 的值 = `LogEventType` 枚举 value 的**小写 snake_case**字符串（如 `turn_budget_guarantee_effective` / `time_budget_terminated`），由 `BaseLogEvent.toMap:81` 的序列化约定规定。SIT asserter 的 API 入口以大写 SNAKE_CASE 枚举名暴露（契约可读），内部做小写转写与日志行匹配。
- **Q3（已反馈 2026-10-19）**：LLM `temperature=0.2` 默认由 demo README env 清单固定；S2/S4 的死循环/停滞场景由 **prompt 工程硬塞**构造 —— `run-scenarios.sh` 中 S2 prompt 显式写 "MUST retry at least 12 more times..."；S4 prompt 显式写 "MUST call `infinite_demand_critic`...never decide on your own..."。SIT 侧沿用 demo 的 prompt 组合，LLM 吞吐导致的"一轮答完"仍判 INCONCLUSIVE。

### 5.2 已决策（可直接落地）

- **Q4（已决）**：X5 overlay profile 注入方式 = **方案 B**，用环境变量 `OPENJIUWEN_SERVICE_RUN_BUDGET_TIME_TOTAL_BUDGET_SECONDS=60` 覆盖，**不改 demo yml**。`DemoFatJarLauncher` fixture 在 X5 场景启动命令前注入该 env 即可。
- **Q5（已决）**：INCONCLUSIVE 判定 = **渐进策略**。
  - **初版**：asserter 产出"事件集合实际值 vs 期望值"诊断 diff，人工 review 判 INCONCLUSIVE vs FAIL。
  - **迭代版**：asserter 侧加规则 ——
    - 必现事件缺失**且**无 extra 事件 → `INCONCLUSIVE`（判为 LLM 抖动跳过场景）
    - 必现事件缺失**但**出现错位事件（如期望 `TERMINATED` 收到 `COMPLETED`）→ `FAIL`（判为产品行为违约）
    - 事件齐全但 payload 字段缺失/类型错 → `FAIL`
  - 规则落点：`RunBudgetEventAsserter.classify(expected, actual) -> {PASS, FAIL, INCONCLUSIVE}`。

---

## 6. 执行进度跟踪（填写时更新）

| 用例 ID | 状态 | 最近执行时间 | 执行环境 | PASS/FAIL/INCONCLUSIVE | 证据 | 备注 |
|---|---|---|---|---|---|---|
| FEAT-057.legacy-max-iterations-compat | done | 2026-10-20 17:31 | ECS Maven | **PASS** | surefire s0（2/2 F0E0S0） | 自然完成分支；11 事件零出现（X4 口径） |
| FEAT-057.unset-config-byte-equivalence | done | 2026-10-20 17:31 | ECS Maven | **PASS** | 同上 | 与 s0 同局 |
| FEAT-057.config-additive-only | done | 2026-10-20 | ECS 脚本层旁证 | PASS（旁证） | 配置五校验链实测（s5 fail-fast + s4 覆盖组合过检） | SIT 不做源码检查 |
| FEAT-057.turn-guarantee-configured | done | 2026-10-20 17:31 | ECS Maven | **PASS** | surefire s1：payload 20/configured/200 恰一次 | — |
| FEAT-057.turn-guarantee-no-interference | done | 2026-10-20 17:31 | ECS Maven | **PASS** | 同上，其余十枚全零 | — |
| FEAT-057.turn-checkpoint-extension | done | 2026-10-21 21:26 | ECS Maven | **PASS** | cp@10/13、EXTENDED×2、allowed≤hard(跨事件基准) | EXTENDED schema 勘误后首绿 |
| FEAT-057.turn-stall-prompt-escalation | done | 2026-10-21 21:43 | ECS Maven | **PASS** | host_stall_reported=true→STALL_PROMPTED→consecutive_stalls=1 | 经临时 env 覆盖绕开检测器工具名失配（batch3 §A）；开发修复后删覆盖 |
| FEAT-057.turn-intervention-no-hard-cut | done | 2026-10-20 | ECS 脚本层 | PASS（旁证） | 各局 forbidden leaks 全零 | — |
| FEAT-057.turn-safety-valve-three-phase | evidence-ready | 2026-10-20 18:08 | ECS 脚本层 | 预判 PASS（交卷分支） | valve@5→final@6→自然 answer（log 180820）；x/16 工厂抬升 | TERMINATED 分支 UT-S03 承接；判据已补两分支 |
| FEAT-057.time-per-invoke-isolated | partial | 2026-10-20 | ECS 脚本层 | 预判 PASS | A/B 两局独立计时 | — |
| FEAT-057.time-no-new-dispatch-after-exhaust | evidence-ready | 2026-10-20 18:25 | ECS 脚本层 | 预判 PASS | B 局：terminated_at=tool_dispatch、elapsed=18.0 | — |
| FEAT-057.time-timeout-clamping | evidence-ready | 2026-10-20 18:25 | ECS 脚本层 | 预判 PASS（硬证） | 20s 工具实跑 14541.5ms=剩余预算 | 原规划弱层，实测拿到硬证 |
| FEAT-057.time-near-deadline-single-shot | evidence-ready | 2026-10-20 18:17 | ECS 脚本层 | 预判 PASS | A 局恰一次，remaining=8.19<15 | X2 口径 |
| FEAT-057.time-termination-identifiable | evidence-ready | 2026-10-20 18:25 | ECS 脚本层 | 预判 PASS | reason 字面+文案逐字+无泄漏 | X3 口径 |
| FEAT-057.time-dim-independent-enablement | done | 2026-10-20 | ECS 脚本层 | PASS（旁证） | s1 仅轮次 / s3 仅时间交叉 | — |
| FEAT-057.both-dims-coexistence | done | 2026-10-21 20:50 | ECS Maven | **PASS** | 双 EFFECTIVE 单射+零串扰零终止 | demo 删 first-checkpoint:5 解锁；#236 仍开（契约对齐独立推进） |
| FEAT-057.events-contract-11-types | partial | 2026-10-20 | ECS 脚本层 | 8/11 类已见 | 证据总账 §一 MUST#17 行 | TURN_TERMINATED/STALL/GENTLE 随阻塞项解锁 |
| FEAT-057.overlay-rail-priority | done | 2026-10-21 21:24 | ECS Maven | **PASS**（两连绿） | rail 自报行+output 非空+预算事件零越位 | 新锚首战即绿 |

---

## 7. 版本

- 2026-10-19（二版，dev-reply 集成 + overlay rail 补入）：
  - 开发团队 2026-10-19 回复落地：
    - Q1 → HTTP 入口 `POST /v1/query`，body `{"message":"<prompt>"}`（README/HANDOFF/run-scenarios.sh 一致）；`ReactAgentHttpClient` 替代 `V1ChatClient`。
    - Q2 → 日志 `event_type` 字段 = `LogEventType` 枚举 value 小写 snake_case（锚 `BaseLogEvent.toMap:81`）；asserter 内部做大写↔小写转写。
    - Q3 → `temperature=0.2` 固定，S2/S4 由 prompt 工程硬塞构造死循环/停滞。
  - demo 侧 2026-10-19 新增：
    - S6 "有进展检查点延长"（硬断言 `CHECKPOINT`+`EXTENDED`）；
    - S7 "overlay rail 优先"（`ForceFinishAtRoundRail(priority=80, atRound=3)`，MUST#18 scope out → scope in）；
    - trace-asserter 全量升级到代码真名小写 snake_case（事件名漂移解除）。
  - 本档对齐更新：§1 覆盖矩阵 row #3 加 S6、row #18 翻为 runnable；§2 新增 §2.7 S6 + §2.8 S7；§3 "demo 对照"表加 S6/S7 行，header 改为"漂移已解除"；§4 fixture `V1ChatClient` → `ReactAgentHttpClient`，`Feat057DemoScenarioTest` 6→8 方法；§5.1 Q1-Q3 转"已反馈"；§6 加 overlay-rail-priority 跟踪行。
- 2026-10-19（初稿）：基于 L2 2026-10-08 + 特性文档 2026-10-10 + alignment table 2026-10-17 + 代码 agent-core-java@develop fetch 2026-10-19 共同校定；初稿时 demo asserter 使用旧名（`*_ACTIVATED` / `*_HARD_LIMIT_REACHED` / `*_EXHAUSTED`），本档断言用代码真名；二版 dev 升级后漂移解除。
