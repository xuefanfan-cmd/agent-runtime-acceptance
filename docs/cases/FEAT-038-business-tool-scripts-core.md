---
用例编号: FEAT-038-business-tool-scripts-core
测试标题: 业务扩展工具话术（core）——卡片承载话术、前后事件优先级、动态 ui_notice、静默容错与事件合同
story: S1
优先级: P0
自动化状态: IMPLEMENTED
适用环境: openjiuwen
作者: TBD
创建日期: 2026-09-20
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, tscript, feat-038]
---

# FEAT-038-business-tool-scripts-core — 业务扩展工具话术（core）

> **一句话总结**：业务工具把自己的展示话术写在工具卡片上，执行框架在工具执行前后照配置发出话术事件；
> 话术解析在本地查表、不额外调用大模型；未配置或未注册的工具静默不发射，解析异常不干扰工具执行本身。
>
> **本轮范围**：FEAT-038 §2 全部 MUST、§3 Schema/优先级/事件字段、§4 场景表、§5.1 行为语义、
> §5.3 并发独立性、§7 验收要求，共 28 条用例（C01~C28）。其中 1 条
> （C17 非 EDPA 宿主通用性）因无现成 fixture 维持 `deferred`，1 条（C12 未注册工具静默）
> 因模型提供方不允许发出工具列表外函数名而记 `blocked` 留证。
>
> **机制一句话**：话术由工具卡片属性（`ToolCard.properties` 的 `tool_scripts` / `ui_notice` /
> 意图字段）承载，不进入模型提示；宿主启用通用话术能力后，`tool_start` 按「意图段 → 默认段 → 不发射」、
> `tool_end` 按「`ui_notice` → 意图段 → 默认段 → 不发射」查表，事件以
> `event/tool/content/timestamp/conversation_id` 五字段加 `OutputSchema("custom")` 包装投递。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-core-ext-react-rails** | 机制提供方 | 话术解析与发射（`ToolScriptRail`）、前后优先级链路、变量替换、blank 与逐段容错、话术事件构造 |
| **载体层 · agent-solution（edp-agent-java）** | 机制触发载体 | EDPA 宿主默认启用该能力；工具经三件套装配后进入工具链，本特性行为在此宿主上观察 |
| **测试数据层** | 探针与场景 | `tscript_probe`（按入参切换返回形态/结果形态）、场景配置变体（`default`、`coverage-probe` 等） |

## 关联特性

- **FEAT-039**：EDPA 侧装配启用、存量三工具回归与新旧链路双发为零（同批交付；装配与门禁行为归 FEAT-039 记账，
  本文只引用其拓扑，不重复断言）。
- **范围外相邻能力**：AG-UI 协议映射、话术国际化、存量话术迁移到卡片、工具调度与 ReAct 决策循环、
  `ui_notice.event` 扩展到 `tool_end` 之外。

## 关联架构约束 / FEAT-038 事实要求

- §2：话术由卡片属性承载、不进入模型 Prompt；`tool_start` 走「意图段 → 默认段」；
  `tool_end` 走「`ui_notice` → 意图段 → 默认段」；未配置/未注册不发射且不抛异常；
  话术能力不引入额外大模型调用。
- §3：话术事件含 `event/tool/content/timestamp/conversation_id` 五字段，wire 形态为
  `OutputSchema("custom", 0, payload)`；数值展示至少一位小数且不舍入；`content` 为空白时不发射。
- §4：非法 Schema 逐段容错（跳过该段并告警，不合成兜底话术）；非 EDPA 宿主宿主场景为范围决定项。
- §5.1：话术事件与 `tool_call` / `tool_result` 事实事件并存互补；§5.3：并发调用无共享可变状态。
- L2（已合并，frontmatter 仍为 `draft`）§3.1 治理 Rail 置 `_skip_tool` 时不发话术；
  §3.2 / §3.3 blank 判定与标量安全替换；§5.2 / §8.3 结果形态边界与运行时异常隔离。
- D8-A 设计裁决：普通 Java 技术异常后仍发一条**状态中性**的配对 `tool_end`，且该话术不代表成功。

## 前置条件

1. `02_ForkCode/agent-solution` 的 `common/example/tool-scripts-sit-demo` 已构建并 install 至共享 Maven 仓库
   （`com.customer.acceptance:tool-scripts-edpa-sit:1.0.0`、`com.customer.acceptance:tool-scripts-edpa-malformed-provider-sit:1.0.0`）。
2. `src/test/resources/application-openjiuwen.yml` 已声明 `tool-scripts-edpa` 与
   `tool-scripts-edpa-malformed-provider` 两个 managed agent 坐标，并以 `-Dtest.env=openjiuwen` 选择 profile。
3. 真实模型可达（模型相关环境变量走运维侧注入，仓内不留明文）。
4. SUT 场景目录由 `EDP_AGENT_SCENARIO_HOME` 指向
   `tool-scripts-sit-demo/scenarios/<场景>`（可用 `-Dtscript.fixture.root` 覆盖根路径）；Redis 由测试栈以服务绑定注入。
5. 需要观察模型调用次数时启用 `LlmCountingProxy`：本地回环计数端点，逐字节转发真实模型请求与响应，只计数、不改写。
6. 每条用例使用唯一租户/任务/会话标识与 canary，断言按执行前后增量取值。

---

## 一、优先级与回落（C01~C03、C11）

**测试数据**：场景 `default` 的 `tscript_probe` 卡片含意图字段 `biz_type`、意图段 `财报` 与默认段；
对照工具 `tscript_plain` 有卡片但无 `tool_scripts`。

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS038-C01 | PASS / 高 | 卡片声明意图段与默认段 → 以 `biz_type=财报` 调用 → `tool_start` / `tool_end` 的 `content` 精确等于意图段文案，五字段齐全 | 内容落到默认段、缺事件或字段缺失 | `ToolScriptPriorityTest#intentHitUsesIntentScripts`（ts038.prio-intent-hit） |
| TS038-C02 | PASS / 高 | 入参不含 `biz_type` → 调用 → 前后事件均取默认段文案 | 仍发意图段文案或不发射 | `ToolScriptPriorityTest#missingIntentFallsFallsBackToDefaults`（ts038.prio-intent-missing） |
| TS038-C03 | PASS / 高 | `biz_type` 取不存在的意图值（含非字符串取值） → 调用 → 回落默认段；意图匹配仅按字符串相等 | 误命中意图段或抛异常 | `ToolScriptPriorityTest#unknownIntentFallsBackToDefaults`（ts038.prio-intent-nomatch） |
| TS038-C11 | PASS / 高 | 调用无 `tool_scripts` 的对照工具 → 零话术事件，同时 `tool_call` / `tool_result` 事实事件照常，无异常 | 发射话术或事实事件缺失 | `ToolScriptPriorityTest#unconfiguredToolEmitsNoScriptEvents`（ts038.silent-unconfigured） |

## 二、`ui_notice` 动态话术、变量与数值展示（C04~C10、C24、C25）

**测试数据**：`tscript_probe` 卡片声明 `ui_notice` 各状态键（`calc_success` / `calc_failed` / `calc_degraded`）
与含占位符的模板；探针按入参返回直接 Map、JSON 对象字符串、JSON 数组、普通文本与自定义包装五类结果形态。

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS038-C04 | PASS / 高 | 卡片模板 `计算完成，结果为 {result}`；工具成功返回 `ui_notice{key:calc_success, result:42}` → `tool_end` 精确等于 `计算完成，结果为 42.0`，`tool_start` 不受影响 | 落意图/默认段、变量未替换、整数未显示为 `42.0` | `ToolScriptPriorityTest#successNoticeOverridesIntentEndScript`（ts038.uinotice-success） |
| TS038-C05 | PASS / 高 | 工具返回业务失败结果并携带 `key=calc_failed` → `tool_end` 取失败态文案（通道与业务结果状态无关） | 落意图段/默认段文案 | `ToolScriptPriorityTest#businessFailureNoticeOverridesDefaultEnd`（ts038.uinotice-failed） |
| TS038-C06 | PASS / 高 | 工具返回降级结果并携带 `key=calc_degraded` → `tool_end` 取降级态文案 | 落意图段/默认段文案 | `ToolScriptPriorityTest#degradedNoticeOverridesDefaultEnd`（ts038.uinotice-degraded） |
| TS038-C07 | PASS / 高 | 返回体 `ui_notice.event` 缺失或取值非 `tool_end` → 忽略 `ui_notice`，`tool_end` 回落意图/默认链路 | 发射了查表话术或完全不发射 | `ToolScriptPriorityTest#missingOrWrongNoticeEventFallsBack`（ts038.uinotice-event-fallback） |
| TS038-C08 | PASS / 高 | 返回体 `ui_notice.key` 在卡片内查表未命中 → 回落意图级 `tool_end`，再无则回落默认段 | 直接静默丢失已有回落话术 | `ToolScriptPriorityTest#unknownNoticeKeyFallsBack`（ts038.uinotice-key-miss） |
| TS038-C09 | PASS / 高 | 模板含 `{result}` 但 `ui_notice` 未携带该变量 → `content` 原样保留 `{result}` 字面量并输出告警 | 抛异常、替换为空串或吞掉告警 | `ToolScriptPriorityTest#unresolvedVariableRemainsLiteral`（ts038.var-missing-literal） |
| TS038-C10 | PASS / 高 | 直接 Map 与 JSON 对象字符串两条路径分别携带整数、小数、高精度小数、大整数与预格式化字符串 → 整数补 `.0`，已有小数位原样保留、不舍入、不丢精度 | 只做前缀比较、减少小数位、四舍五入、科学计数法泄漏或精度丢失 | `ToolScriptBoundaryTest#numericPlaceholdersPreserveExactDecimalText`（ts038.numeric-display） |
| TS038-C24 | PASS / 高 | 模板含多个占位符，变量覆盖字符串（含 `$`、反斜杠、`{nested}`）、数值、布尔、Map/List 与 `null` → 标量按字面量单次替换（整数显示为 `42.0`），变量内花括号不递归展开，复杂值与 `null` 保留占位符并告警，日志不含完整敏感 `content` | 替换被正则破坏、递归展开、复杂对象直出或日志泄漏敏感内容 | `ToolScriptBoundaryTest#scalarSubstitutionIsLiteralAndSinglePass`（ts038.literal-safe-substitution） |
| TS038-C25 | PASS / 中 | 同一语义分别由直接 Map、JSON 对象字符串、JSON 数组、普通文本与自定义包装返回 → 前两者解析顶层 `ui_notice` 且整数精确为 `42.0`，后三者不猜测解包并回落意图/默认 `tool_end` | 包装被猜测解析、合法 JSON 对象未命中、只做宽松前缀断言或异常外抛 | `ToolScriptBoundaryTest#onlyObjectShapesDriveDynamicNotices`（ts038.result-shape-boundary） |

## 三、静默、逐段容错与入参/配置边界（C12、C13、C19、C26、C27、C28）

**测试数据**：`coverage-probe` 场景的非对象入参探针（`tscript_arg_array` / `tscript_arg_scalar`）；
非法卡片变体工具（意图段类型错误、`ui_notice` 段非法、默认段非法）；空白话术变体。

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS038-C12 | blocked / 中 | 工具名不存在任何注册卡片，且 fixture Rail 以 `dispatch.tool.<name>` 计数证明调用进入 Rail 链 → 零话术发射、该工具无执行增量、请求仍返回 200 | Rail 抛异常或发射话术；观测计数为 0 时记 `blocked` 留证，不用「沉默即通过」充数 | 无自动化（`ToolScriptCoverageProbeTest` 中未实现，探针与拒答原文留证）；Story ts038.silent-unregistered |
| TS038-C13 | PASS / 高 | 三个非法卡片变体（意图段类型错误 / `ui_notice` 段非法 / 默认段本身非法）分别调用 → 跳过非法段并回落，默认段非法时该次事件不发射，全程无异常且工具真实执行不受影响 | 异常外抛、工具未执行或合成兜底话术 | `ToolScriptBoundaryTest#invalidCardSectionsAreIsolated`（ts038.schema-invalid-a/b/c） |
| TS038-C19 | PASS / 中 | 话术解析结果为 `null`、空串或纯空白 → 该事件不发射且不抛异常（`tool_start` / `tool_end` 各自独立判定） | 空白话术仍被发射或抛异常 | `ToolScriptBoundaryTest#blankScriptsEmitNothing`（ts038.empty-content） |
| TS038-C26 | PASS / 高 | 工具实参为非 JSON 对象的数组与标量字符串（由 fixture Rail 注入并双份计数留证） → 按无意图处理、回落默认段、工具事实事件照常、无异常 | 抛异常、合成兜底话术、错误解析出意图，或模型实际传了对象（Given 未成立） | `ToolScriptCoverageProbeTest#nonObjectArgumentsFallBackToDefaultScripts`（ts038.arg-shape-fallback） |
| TS038-C27 | PASS / 中 | 卡片同时声明意图段与默认段，意图字段为 `""` 或 `null` → 空值按不命中处理，前后事件回落默认段 | 误命中意图段、不发射或抛异常 | `ToolScriptBoundaryTest#emptyIntentFallsBackToDefaults`（ts038.intent-empty-fallback） |
| TS038-C28 | PASS / 中 | 变体 A：意图段 `tool_start` 为空串而默认段有值；变体 B：意图段与默认段 `tool_start` 均为空 → A 回落默认段，B 该事件不发射，`tool_end` 各自独立判定 | 空段被当作命中发射、未回落或抛异常 | `ToolScriptBoundaryTest#blankIntentSegmentFallsBackAndBlankChainSkips`（ts038.empty-segment-fallback） |

## 四、事件合同、并发、异常闭环与零额外模型调用（C14~C16、C18、C20~C23）

**测试数据**：Wire 客户端采集的原始事件帧；`LlmCountingProxy` 计数端点；治理 Rail `_skip_tool` 变体；
抛 Java 异常的工具变体；并发同名调用与重试尝试变体。

| 用例 ID | 状态 | Given → When → Then（主断言） | FAIL 判据 | 自动化落点（Story） |
|---|---|---|---|---|
| TS038-C14 | PASS / 高 | 任一次话术发射 → 事件含 `event/tool/content/timestamp/conversation_id` 五字段；`timestamp` 为 Unix 毫秒量级；wire 为 `OutputSchema("custom", 0, payload)` 且可被存量消费形态解析；`conversation_id` 与会话上下文一致 | 字段缺失、类型错误或包装不可解析 | `ToolScriptBoundaryTest#eventContractExposesRequiredFields`（ts038.event-contract，contract 层） |
| TS038-C15 | PASS / 高 | 会话标识缺失或 `getSessionId()` 抛非白名单运行时异常 → `conversation_id` 回落 `unknown`，话术仍按卡片配置发射，工具结果不受影响（日志出现 `SESSION_ID_FAILED` 诊断） | 异常外抛导致话术丢失或工具结果被改写 | `ToolScriptBoundaryTest#unexpectedSessionIdFailureFallsBackToUnknown`（ts038.conversation-fallback） |
| TS038-C16 | PASS / 高 | 同一宿主内分别调用「带话术卡片」与「无话术卡片」工具各一次 → 两形态的模型调用增量相等且均大于 0，工具事实事件均存在 | 带话术形态增量更大/更小；计数端点未观测到任何调用 | `ToolScriptLlmCallCountTest#scriptedToolAddsNoExtraModelCall`（ts038.zero-llm） |
| TS038-C18 | PASS / 高 | 同一次工具调用 → `tool_call` / `tool_result` 事实事件与 `tool_start` / `tool_end` 话术事件共存、互不覆盖、语义不同 | 事实事件被话术覆盖或缺失 | `ToolScriptBoundaryTest#scriptAndToolFactEvidenceCoexist`（ts038.coexist-facts） |
| TS038-C20 | PASS / 高 | 高优先级治理 Rail 在工具执行前置 `_skip_tool=true`，且工具卡片话术完整 → 工具真实调用增量为 0，前后话术事件增量均为 0 | 任一话术发射或工具实际执行；无法证明治理 Rail 命中时记 `INCONCLUSIVE` | `ToolScriptBoundaryTest#governanceRejectionProducesNoScriptsOrExecution`（ts038.skip-tool-silent） |
| TS038-C21 | PASS / 高 | `tool_start` 已发射后工具抛普通 Java 技术异常 → 仍发一条配对的状态中性 `tool_end`，不伪造动态成功 key，异常语义仍由原链路承载 | 缺失或错配 `tool_end`、结束文案宣称成功、话术覆盖异常语义 | `ToolScriptBoundaryTest#javaExceptionKeepsNeutralPairedEndEvent`（ts038.exception-neutral-end） |
| TS038-C22 | PASS / 高 | 同一会话并发触发两个同名工具调用，各自 `tool_call_id` 唯一、返回内容使用不同 canary → 恰有两组前后事件且 ID 与内容一一对应，五字段不变 | 缺 ID、错配、串值或出现额外事件 | `ToolScriptBoundaryTest#concurrentSameNameCallsRetainDistinctCallIds`（ts038.concurrent-call-id） |
| TS038-C23 | PASS / 中 | 第一次尝试进入通用命中域并完成结束话术；第二次尝试在更高优先级 Rail 提前异常 → 第一次的标记已清除，第二次不沿用旧标记、不产生孤立 `tool_end` | 第二次出现沿用状态的结束话术 | `ToolScriptBoundaryTest#preemptedAttemptCannotInheritPreviousEndEvent`（ts038.retry-isolation） |

## 五、跨宿主通用性（C17，范围决定项）

| 用例 ID | 状态 | 场景 | 处置 |
|---|---|---|---|
| TS038-C17 | deferred / 中 | 启用该扩展的**非 EDPA 宿主**行为语义与 EDPA 宿主一致 | 当前 acceptance 仓无启用该扩展的非 EDPA fixture；是否新建专用 fixture 属范围决定，未裁决前维持 `deferred`，不作为已覆盖能力 |

## 自动化落点汇总

| 测试类 | 层次 | 覆盖用例 | 最近一轮 testcase 数 |
|---|---|---|---|
| `cases/integration/tscript/ToolScriptPriorityTest` | integration | C01~C09、C11 | 10 |
| `cases/integration/tscript/ToolScriptBoundaryTest` | integration | C10、C13~C15、C18~C25、C27、C28 | 14 |
| `cases/integration/tscript/ToolScriptCoverageProbeTest` | integration | C26 | 1 |
| `cases/integration/tscript/ToolScriptLlmCallCountTest` | integration | C16 | 1 |
| `cases/integration/tscript/LlmCountingProxy` | 测试基础设施 | C16 观测手段（本地计数端点，逐字节转发） | — |
| `cases/integration/tscript/AbstractToolScriptSitTest`、`ToolScriptFixtureSupport`、`ToolScriptWireClient` | 测试基础设施 | 场景目录绑定、SUT 栈构造、A2A 流式采集与探针计数读取 | — |

Allure 标注：类级 `@Feature("FEAT-038: 业务扩展工具话术")`；方法级 `@Story("TS038-Cxx …")`；
Surefire 选择标签 `integration`、`tscript`、`feat-038`。

## 执行与结论

- 执行分组：本特性用例与其他 FEAT-038/039 用例编入同一矩阵（按测试类分组）；破坏性装配类用例单独分组。
- 最近一轮全量：Run `feat-038-039-20260917-105053` 覆盖 63 个 testcase（FEAT-038 组 26 个），
  `selected 63 / executed 63 / PASS 63 / FAIL 0 / ERROR 0 / skipped 0`。
- 缺口（不视为通过）：TS038-C12 `blocked`（模型提供方不允许发出工具列表外的函数名，拒答原文留证）、
  TS038-C17 `deferred`（无现成非 EDPA 宿主 fixture）。
- 说明：上述全量结果来自被测制品为**候选/最新代码组合**的轮次；正式发布制品确定后需按受影响范围复跑，
  本文不把候选轮结果升级为发布基线结论。

## 不覆盖

| 内容 | 依据 |
|---|---|
| AG-UI 协议映射、话术国际化/多语言 | §5.2 明确不承诺 |
| 存量话术迁移到 `ToolCard.properties` | FEAT-039 §2 范围外 |
| 工具调度与 ReAct 决策循环本身 | 两文档明示范围外 |
| 返回体包装形态（自定义 wrapper）的自动解包 | §5.2 归实现/宿主适配，本特性只断言直接 Map 与 JSON 对象字符串 |
| 每个内部工具或下游副作用的 exactly-once | 无权威承诺，且黑盒无可稳定计数证据 |
| 模型决策过程、事件绝对时间 | 不作为判定对象 |
