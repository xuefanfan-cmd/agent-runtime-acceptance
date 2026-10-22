---
feature_id: FEAT-058
title: FEAT-058 L1 特性文档评审 note
reviewer: SIT
baseline:
  - L1: develop/02-features/FEAT-058-model-call-timeout-tiering.md (updated 2026-10-20, status draft)
  - L2: develop/03-architecture/L2-Low-Level-Design/agent-core/Feat-Func-058-model-call-timeout-tiering.md (updated 2026-10-19)
  - 代码: agent-core-java@origin/develop @ 42b1fcfe (!400 已合入)
  - 代码: agent-runtime-java@origin/develop @ 79ea437b (!227 已合入)
  - 开发对齐表: agent-solution/FEAT-058-alignment-table.md (updated 2026-10-20)
updated: 2026-10-22
---

# FEAT-058 L1 特性文档评审 note

## 评审目的与范围

本 note 的目的是：把 "L1 文档陈述" 与 "L2 定稿 + develop 代码" 之间仍存在的
不一致点，作为**文档评审产物**提给 L1 文档负责人，推动 L1 刷新。SIT 后续
设计用例时锚点取 "L2 附录 + develop 代码 + 开发对齐表"，不等 L1 刷新完成。

评审基线按 frontmatter 的四处 upstream 为准。10-20 版 L1 已通过 !340 把
原 SHOULD "超时错误阶段信息" 升为 MUST（§2 L51），本 note 不再把该项
视为需刷。

## 一、必修（涉及 MUST；开发对齐表附录 A/B 已承认漂移）

| # | L1 位置 | L1 陈述 | develop 事实 | 开发表立场 | 建议动作 |
|---|---|---|---|---|---|
| D1 | §1 L24 整段<br/>§1 L19-20 (旧现状二元对比)<br/>§2 L47 "两条 HTTP 栈覆盖(MUST)"<br/>§3 L66 "推理亲和客户端超时面"<br/>§5.1.4 整节<br/>§6 L141, L144<br/>§7.4 UT-I01~I04<br/>§9.1 "两条 HTTP 栈覆盖" 映射行 | "仓内存在两条独立 HTTP 栈：主力模型客户端 + 推理亲和客户端"；"两条栈必须同步改造"；"UT-I 组专测推理亲和栈一致性" | 推理亲和栈已随 core commit `1803950c7` 整体删除（L2 附录 C 显式声明）；develop `modelclients/` 目录核验：无 `InferenceAffinityModelClient.java`，仅剩 OpenAI / OpenAiCompatible / SiliconFlow / DashScope 四子类共享单一 JDK HttpClient 栈，由 `ModelTimeoutResolver` + `ModelHttpClients` 统一承载 | 对齐表附录 A 第 1 条承认漂移且涉及 MUST#8；附录 A 第 4 条承认 UT-I 系列 / ST-04 "对应能力不再存在"；附录 B 以独立章节说明"实现路径与 L1 假设的差异" | L1 §1 删除"两条栈"前置事实或改为"930 版本现状"；§2 MUST#8 改为"全部模型调用 HTTP 栈采用同一分层语义（当前为单一 JDK HttpClient 栈，推理亲和栈已整体下线）"；§3 删除"推理亲和客户端超时面"入口；§5.1.4 整节重写或删除；§7.4 UT-I 组改 scope 为"多 client 子类覆盖同一组 resolver"（对齐表附录 B 已给替代命名）；§9.1 对应行与 ST-04 同步刷新 |
| D2 | §3 L63 "单次调用超时参数" 入口行 | 入口类型 "模型调用 API 参数"；未显式提及新增字段的类型与声明位置 | runtime 侧 PR!227 新增 `LlmProperties.connectTimeout` + `LlmProperties.streamIdleSeconds`（二 Duration 字段）；core 侧 PR!400 新增 `ReActAgentConfig.modelStreamIdleSeconds`（Double 秒）；per-call `ModelInvokeOptions.timeout` 语义收窄为**仅覆盖 idle 层**（不覆盖 header-wait） | 对齐表附录 A 第 2 条承认漂移且涉及 MUST#7：建议 L1 §3 入口表补一行 per-call idle 覆盖字段 + runtime property 面 | L1 §3 入口表补两行："每智能体级 per-call idle 覆盖字段 `modelStreamIdleSeconds`（秒）"；"runtime 配置面 `LlmProperties.{connectTimeout,streamIdleSeconds}`"；并在 §2 MUST#7 事实要求行补一句"per-call 参数仅覆盖 idle 层，不覆盖 header-wait" |
| D3 | §2 MUST#8 "仓内全部模型 HTTP 栈采用同一分层语义"<br/>§9.1 "MUST#8 覆盖栈" 行 | 原文无 carve-out；MUST#8 字面"仓内全部"口径下 `IntelliRouterModelClient` 应落入覆盖范围 | `IntelliRouterModelClient` 作为注册 provider 存在于 `BUILTIN_CLASS_NAMES`，但走 `ReliableRouter` 不走 `ModelHttpClients` + `IdleBoundInputStream`，不读 `connect_timeout_seconds` / `stream_idle_seconds`，异常统一包为 `MODEL_CALL_FAILED(181001)` 而非 `MODEL_CALL_TIMEOUT(181008)`；production 当前不可达（SDK 无 `RouterFactory` 驱动绑定） | **2026-10-22 开发侧反馈**：方向是刷 L1/L2 显式 carve-out 而非扩展代码覆盖面；对齐表附录 A/B 未列此条，但 B2 跟踪在 demo 侧 `UPSTREAM-ISSUE-DRAFT-INTELLI-ROUTER-COVERAGE.md` 有完整背景 | L1 §2 MUST#8 补一句"仓内全部模型 HTTP 栈采用同一分层语义**（当前为单一 JDK HttpClient 栈；`IntelliRouterModelClient` 走独立 `ReliableRouter` 通道，不在 FEAT-058 分层覆盖面内，错误以 `MODEL_CALL_FAILED(181001)` 独立归档）**"；§9.1 "MUST#8 覆盖栈" 行同步补 carve-out；L2 §1.2 能力矩阵 MUST#8 行补一句同义 carve-out |

## 二、建议修（不涉及 MUST，但影响 L1 完整性）

| # | L1 位置 | L1 陈述 | develop 事实 | 开发表立场 | 建议动作 |
|---|---|---|---|---|---|
| S1 | §5.1.5 "响应头等待超时" 行 | 单独描述"响应头等待超时"子情形，错误表面标为"首段数据到达前" | 实现 `ModelTimeoutErrorFactory.fromSendException` 将 `HttpTimeoutException` 一律映射为 BEFORE_FIRST_SEGMENT + layer=header_wait（即 header-wait 窗口 与 header-arrived-but-no-segment 窗口合并归入 BEFORE_FIRST_SEGMENT） | 对齐表附录 A 第 3 条承认漂移但判定为"语义等效"：建议补一行"BEFORE_FIRST_SEGMENT 同时覆盖两窗口" | L1 §5.1.5 "响应头等待超时" 行改述为："响应头等待超时与首段数据到达前空闲超时合并归入 'BEFORE_FIRST_SEGMENT' 阶段，错误表面不单独区分两子情形" |
| S2 | §5.1.3 "单次调用覆盖不得污染客户端级配置" | 原文只写"不得污染共享配置"，不涉及实现如何保证 | 实现以栈内局部变量 `double idleSeconds` 承载 per-call 覆盖，不改 `modelClientConfig` 字段 | 对齐表附录 A 第 5 条承认漂移且判定"不涉及 MUST"：建议 L2 补一段显式说明 | L2 补即可，L1 可不改 |

## 三、补强（锚点加固；开发对齐表附录 A 未列）

L1 若把以下 develop 已固化的合同点写入正文，SIT 判据可直接从 L1 引证，不需要再绕一层 L2 附录。SIT 层面可以不等这些修改——附录 B 的"实现路径与 L1 假设的差异"已给出等价证据链——但 L1 的可读性与自治性会更好。

| # | L1 位置 | 建议补强 | develop 证据 |
|---|---|---|---|
| R1 | §2 MUST#10 "超时错误表面可区分" | 补合同码字面值 `StatusCode.MODEL_CALL_TIMEOUT(181008)` | `StatusCode.java:443` 原文：`MODEL_CALL_TIMEOUT(181008, "model call timeout at {phase}, effective timeout {timeout}s, reason: {error_msg}")` |
| R2 | §5.1.5 阶段表 | 补三值枚举字面值 `TimeoutPhase.{BEFORE_CONNECT, BEFORE_FIRST_SEGMENT, AFTER_FIRST_SEGMENT}` | `TimeoutPhase.java` 三值枚举 |
| R3 | §5.1.5 阶段表 | 新增 `layer` 字段三值合同：`connect` / `header_wait` / `stream_idle` | `ModelTimeoutErrorFactory` details 四字段：`phase` + `timeout_seconds` + `layer` + `stream` |
| R4 | §2 MUST#1 / MUST#3 "合理区间 5–15s / 300–900s" | 升级为"实现硬区间校验（越界被 `ModelClientConfig$Builder.build()` 拒绝）" | `ModelClientConfig.java:148-151, 159-162` Builder 校验 |

## 四、不建议下沉到 L1 的实现细节

以下属于 L2/代码层事实，SIT 可以直接引证代码或 L2 附录，不建议拉进 L1：

- `IdleBoundInputStream` + `BlockingQueue.poll(idleNanos)` 的具体机制（L2 附录 B 已记录）
- `IdleGuardedBodyReader` 阻塞路径的 interrupt 守卫（L2 §2.3 已覆盖）
- 四个 client 子类的具体文件行号映射（对齐表附录 D 已回填）

## 五、评审结论

- L1 10-20 版相对 10-10 版已通过 !340 修复 "阶段信息 SHOULD→MUST" 一处；
- 开发对齐表自己附录 A 列出 6 条 L2↔L1 漂移，其中 3 条涉及 MUST，并明确建议刷新 L1；
- 本 note 的 "必修" 两条与开发对齐表附录 A 完全一致，不是 SIT 额外发明；
- "补强" 四条超出开发对齐表附录 A 范畴，属 SIT 视角的锚点建议，是否采纳由 L1 文档负责人裁定；
- 本 note 不阻塞 SIT 用例设计——SIT 后续锚点取 "L2 附录 + develop 代码 + 对齐表附录 B"，L1 刷新按正常文档流程推进即可。

## 六、关联文档

- 开发给的三方对齐表：`agent-solution/FEAT-058-alignment-table.md`
- L1 特性文档：`develop/02-features/FEAT-058-model-call-timeout-tiering.md` @ updated 2026-10-20
- L2 LLD：`develop/03-architecture/L2-Low-Level-Design/agent-core/Feat-Func-058-model-call-timeout-tiering.md` @ updated 2026-10-19
- core PR!400 + runtime PR!227 + 文档 !340
