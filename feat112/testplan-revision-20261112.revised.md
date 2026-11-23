---
scope: v1130
module: deepanalyze-java + agent-runtime-ext-java
deployable_units: [agent-runtime-ext-java, deepanalyze-java]
sut: deepanalyze-agent（真实 DA 部署，双入口 `/run` + `/run-stream` + `/v1/health/detail`，模型调用由 stub 替身驱动，可注入故障）
features: [FEAT-112]

# 版本锚（跟 solution 仓 release 对齐；对齐 00-testplan-template.md §3）
since_solution_version: 0.1.4
last_modified_solution_version: 0.1.4
compatibility:
  agent-core-java: ">=0.1.16"
  agent-runtime-java: ">=0.1.3"
  agent-runtime-ext-java: "源码构建"   # 尚无包含降级门的发布版本（Central 0.1.3 不含）；被测物以构建清单记录的 solution/core commit、制品坐标与 sha256 标识
historical_regressions: []   # 首发无前置；升级时追加（见 00-testplan-template.md §3.2）

# 跨特性依赖（粒度自适应：被依赖 FEAT testplan 规范化后升级为 ID 级；见 00-testplan-template.md §2）
# §3.1 跨特性依赖表与此字段同步演进——详细使用说明在 §3.1 展开
# FEAT-001/008/064 的 testplan 已合入，FEAT-054/109/110/111 的 testplan 未合入（路径与 commit 见 §3）；
#    当前统一按 FEAT 级粒度，被依赖 testplan 有稳定 ID 后升级到 ID 级
depends_on:
  - FEAT-054   # DA 服务入口与健康面接线 [testplan 未合入]
  - FEAT-109   # 结构化护栏拦截事实（分类判据输入）[testplan 未合入]
  - FEAT-110   # /resume 续跑入口 [testplan 未合入]
  - FEAT-111   # 任务终态分类矩阵（K18–K20 代表走它）[testplan 未合入]
  - FEAT-001   # A2A 控制面 F5 冒烟保护
  - FEAT-008   # 用户交互入口 F5 冒烟保护
  - FEAT-064   # 重试策略（附录 E「重试成功」场景归属）

updated: 2026-11-17
# 本 testplan 按 D1 洞察定的格式（8 列场景矩阵 + level 列 + 域前缀 ID + 6 态结果 + 前置与激活证据）落地
# 应用参考：D1 §6/§6b/§6c（L1 测试语言化）/ §6c2（ID 的 4 层用途）/ §8（测试范围由契约圈定，FEAT-112 无契约可选能力）/ §9b（6 态 + reason_code）/ §14（L4 跨版本连续性）
# ⚠️ agent-card 承载边界见 D1 §8 lede：A2A 协议 4 项走 agent-card.yaml；平台特性能力承载机制未解决，候选为各 FEAT 诊断面

# 无契约可选能力：FEAT-112 全部场景为 MUST（D1 v4.1 §8：能力门只作用于契约定义为可选的能力）。
# 部署配置（窗口、阈值、认证、媒体白名单等）写在 §5 的“前置”列，由测试用例自行准备；前置不成立记
# FAIL/conditional_not_triggered，不 skip。不要求 demo 对配置键显式表态：配置键缺席时的交付默认行为本身是被测行为（A2）。
required_capabilities: []

# 测试过程中结果判定态（参考 D1 v4.1 §9b）
verdict_states: [PASS, FAIL, WARN, EXCLUDED, UNSUPPORTED, NOT_OBSERVED]
---

# FEAT-112 任务异常率降级门｜testplan

本次契约基线：特性设计文档与 DA L2 均取 [`05af9cc3`](https://gitcode.com/Technical-AF/docs/commit/05af9cc3796ac953aee157e9c0b35621552232f4)（2026-11-11，“define expired terminal fact semantics”）。特性档 §5.1.4 已正式规定过期豁免，B7/E9 不再是待裁决分歧；设计修订不代表测试实现或执行通过。

## 1. 测试目标

验证 deepanalyze-java 任务级异常自动降级门的全部外部可观察行为：**滑动窗口异常率 + 最小样本量 + 真实任务半开探针 + 故障代际**。SIT 作为 DA 自有 HTTP / SSE 接口的调用方，通过公开面（`/run`、`/run-stream`、`/v1/health/detail`、在途控制面）观察 SUT 行为，不读内部状态、不依赖强制开门端点、不合成假探针，通过**公开配置缩短窗口和静默期 + 真实故障注入**制造测试条件。

本 testplan 对齐 FEAT-112 §2 的 20 项验收出口、FEAT-112 §4 的 12 个场景旅程，**保证无损覆盖**。L2 §1.3 列的 11 项当前实现差距，testplan 中以**反例/负向场景**形式显式覆盖。

### 1.1 范围严格对齐本特性验收（D1 §8e 原则）

本 testplan **只测 FEAT-112 自己承诺的能力**；以下场景**不在本 testplan 范围**，由相关 FEAT 的 testplan 承接（**不重写用例，跑相关 FEAT 的 testplan**）：

| 不属于 FEAT-112 的能力 | 归属 | SIT 验证方式 |
|---|---|---|
| **结构化任务终态分类矩阵**（原 K 组 K1-K17 九态分类覆盖）| FEAT-111 "轨迹反思与成本追踪" 共享的 `TerminalClass` 分类机制 + agent-runtime-ext-java 内部 | **跑 FEAT-111 的 testplan** 验证分类正确；本 FEAT 只以**少数代表场景**（见 §5 K 组保留的 K18–K20）验证"**降级门确实消费了该分类**" |
| **OPEN 下 /status、/cancel、/inject、/answer、/resume 在途控制面不阻断**（原 F6-F12）| 各入口由 FEAT-001 / FEAT-008 / FEAT-110 分别定义 | **跑对应 FEAT 的 testplan** 作回归保护；本 FEAT 以 §5 F5 一条冒烟保护代表（验证 OPEN 不改写控制面语义） |
| **B7「过期事实不改代际」** | **本 testplan 保留**（关键状态机契约）| 按特性档 §5.1.2/§5.1.4 的正式过期豁免验收；采用测试仓独立受控时间/事实载体验证，不以开发用例代替验收 |

> 出口 19 的非法配置值（A3–A6）与窗口内乱序到达（B6）属于本特性验收，由 §5 承接：开发侧单元测试不能替代测试仓独立验收。

## 2. 范围与非范围

**范围**：

- `POST /api/agents/run` 与 `POST /api/agents/run-stream` 的降级准入（HTTP 503 `ENGINE_DEGRADED`、`Retry-After`、零副作用、SSE 不先 200）
- 异常率滑动窗口（最小样本、阈值、乱序插入、过期剪枝、残窗开门）
- 状态机（CLOSED / OPEN / HALF_OPEN）与故障代际防迟到
- 真实任务半开探针（唯一占用、健康关门、无结论释放、过期落定、并发竞争、认领所有权）
- 校验顺序（入站认证 / 参数 / 媒体 / team 先于降级准入；无效请求不占探针）
- 在途控制面不被阻断（`/status`、`/stream` 重连、`/cancel`、`/inject`、`/answer`、`/resume`、`/task`、`/tasks`、`/message`）
- 唯一结构化任务终态分类（L2 §3.5 九值 `TerminalClass` 分类矩阵，含 `COMPLETED` / `BUSINESS_FAILURE` / `INPUT_REJECTED` / `GUARDRAIL_DENIED` / `USER_CANCELLED` / `NOT_ADMITTED` / `CREDENTIAL_OR_QUOTA_FAILURE` / `MODEL_SERVICE_FAILURE` / `INFRASTRUCTURE_FAILURE`）
- 传输层不独立记账（断流、慢消费者、SSE 重连）
- 健康诊断面 `GET /v1/health/detail`（三态均 HTTP 200、状态字段、`instanceId`、服务间认证、不泄露敏感信息）
- 多实例隔离（独立窗口、独立探针、独立 `instanceId`、D4 提示按 instanceId 关联）
- 进程重启语义（新进程 CLOSED 空窗口初始化不算旧实例恢复）
- 配置项与非法值校验（启动期失败 + 指出键名；边界值合法）

**非范围（OUT，带机理）**：

| OUT 项 | 机理 | 对应 reason_code |
|---|---|---|
| 其他 runtime 入口（`/v1/query`、WebFlux、标准 A2A、Agent Bus、custom-rest） | FEAT-112 §2 明示本期只覆盖 `/run` 和 `/run-stream`；通用化后另行扩展 | `out_future_feature` |
| 集群级降级（共享窗口、跨实例单探针、人工强制恢复端点、状态广播） | FEAT-112 §2 明示不做 | `out_vendor_decision` |
| 分桶降级（按租户 / 会话 / 模型 / Agent 分桶） | FEAT-112 §2 明示本期是实例级准入自保 | `out_vendor_decision` |
| 前端提示展示与消除 | FEAT-112 §2 / §5.2 明示由 D4 承接；引擎只保证错误和诊断状态一致 | `out_domain_moved` |
| 请求级 fallback 链（备用模型、免费重试、改写真实任务结果） | FEAT-112 §5.2 / L2 §1.1 明示不改写真实任务 | `out_vendor_decision` |
| 持久化恢复状态与强制门控测试端点（`/force-close`、`/force-open` 等） | FEAT-112 §5.2 / L2 §1.1 明示不持久化、不提供人工强制开关 | `out_vendor_decision` |
| 内部合成假探针（引擎定时发合成任务探测） | FEAT-112 §1.1 否决该候选；FEAT-112 §5.2 明示不合成模型调用 | `out_vendor_decision` |
| 展示文案分类（根据 error 文本反推分类） | FEAT-112 §5.2 / L2 §3.5 明示必须用结构化终态事实 | `out_not_testable_at_protocol`（协议层不可观察展示文案推断） |

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| 特性设计文档 `develop/02-features/FEAT-112-task-degradation-gate.md` @ [`05af9cc3`](https://gitcode.com/Technical-AF/docs/commit/05af9cc3796ac953aee157e9c0b35621552232f4)（2026-11-11，scope=v1130） | §2 能力表、§3 外部接口、§4 场景与用户旅程、§5 行为语义与边界、§6 下游约束——本 testplan 全部断言的唯一契约基线 |
| L2 设计 `develop/03-architecture/L2-Low-Level-Design/deepanalyze/FEAT-112-task-degradation-gate.md` @ [`05af9cc3`](https://gitcode.com/Technical-AF/docs/commit/05af9cc3796ac953aee157e9c0b35621552232f4)（2026-11-11） | 特性档已规定行为的观测形式（字段、事件名、错误码）、当前实现状态与代码指针、分类实现顺序（§3.5）；不增加或放宽特性档行为，冲突时以特性档为准 |
| 特性设计文档 `develop/02-features/FEAT-054-deepanalyze-host-agent.md` @ [`9623fd0b`](https://gitcode.com/Technical-AF/docs/commit/9623fd0bbde3d260c4cf0b1bca517617e3e46a4f)（2026-11-09） | DA 服务入口、健康面接线、稳定错误码 181012 上下文过长事实基线 |
| 特性设计文档 `develop/02-features/FEAT-109-dangerous-operation-guardrail-and-hooks.md` @ [`0aa2f57b`](https://gitcode.com/Technical-AF/docs/commit/0aa2f57bc4eab4c4555ab08ad132eeb84d38989f)（2026-11-10） | 结构化护栏拦截/用户拒绝事实前置（用于 GUARDRAIL_DENIED 判定） |
| 特性设计文档 `develop/02-features/FEAT-110-task-checkpoint-resume.md` @ [`0aa2f57b`](https://gitcode.com/Technical-AF/docs/commit/0aa2f57bc4eab4c4555ab08ad132eeb84d38989f)（2026-11-10） | `/resume` 续跑入口前置 |
| 特性设计文档 `develop/02-features/FEAT-111-trajectory-reflection-and-cost-tracking.md` @ [`9623fd0b`](https://gitcode.com/Technical-AF/docs/commit/9623fd0bbde3d260c4cf0b1bca517617e3e46a4f)（2026-11-09） | 共享 agent-runtime-ext-java 任务终态分类 |
| 特性设计文档 `develop/02-features/FEAT-064-model-call-error-taxonomy-and-retry.md` @ [`9623fd0b`](https://gitcode.com/Technical-AF/docs/commit/9623fd0bbde3d260c4cf0b1bca517617e3e46a4f)（2026-11-09） | 模型调用错误分类与重试（重试成功、重试耗尽的归属） |
| 被依赖 testplan `develop/04-testplan/FEAT-001-standardized-agent-service-entrypoint-deepagent.md` @ [`9623fd0b`](https://gitcode.com/Technical-AF/docs/commit/9623fd0bbde3d260c4cf0b1bca517617e3e46a4f)（2026-11-09） | F5 冒烟所依赖的控制面入口既有语义 |
| 被依赖 testplan `develop/04-testplan/FEAT-008-user-interaction-interrupt-and-response-deepagent.md` @ [`9623fd0b`](https://gitcode.com/Technical-AF/docs/commit/9623fd0bbde3d260c4cf0b1bca517617e3e46a4f)（2026-11-09） | F5 冒烟所依赖的交互入口既有语义 |
| 被依赖 testplan `develop/04-testplan/FEAT-064-model-call-error-taxonomy-and-retry.md` @ [`9623fd0b`](https://gitcode.com/Technical-AF/docs/commit/9623fd0bbde3d260c4cf0b1bca517617e3e46a4f)（2026-11-09） | 重试成功等场景的归属（附录 E） |
| 被依赖 testplan：FEAT-054、FEAT-109、FEAT-110、FEAT-111 | testplan 未合入；合入后补路径与 commit，`depends_on` 相应项升级为 ID 级 |
| 代码 agent-solution `common` @ [`1127a9ec`](https://gitcode.com/openJiuwen/agent-solution/commit/1127a9ece7b2994e0a96f13e45ad943bc83ad9a9)（被测载体构建来源） | 锚定实现事实（类名、配置键、日志字段）；不作为期望来源 |

### 3.1 FEAT-112 依赖的 FEAT 清单（跨特性『跑而不写』归属表）

对齐 D1 §8e 原则：**testplan 对齐本特性验收，不属于本 FEAT 的能力跑相关 FEAT 的 testplan，不重写**。本节一次性列明 FEAT-112 的所有跨特性依赖，以及每类依赖对应的 testplan。

**粒度自适应**：本表当前粒度为 **FEAT 级**（`FEAT-NNN`），因为被依赖 FEAT 的 testplan 尚未全面按 D1 §6c 规范化（没有稳定 §5 ID 矩阵）。待对方规范化后，本表"依赖 FEAT"列中具体引用项将细化为 **ID 级**（`FEAT-NNN-xxx`，如 `FEAT-111-K1`）——frontmatter `depends_on` 字段与本表同步演进（对齐 D1 §6c2 ID 的 4 层用途：第 ③ 层跨 FEAT 关联键 + §8d Job E 影响分析）。

**依赖就绪状态**：FEAT-001（deepagent 版）、FEAT-008、FEAT-064 的 testplan 已合入（路径与 commit 见 §3）；FEAT-054、FEAT-109、FEAT-110、FEAT-111 的 testplan 尚未合入，相应的“跑 FEAT-xxx testplan”委托在其合入前暂不可执行。本 testplan 自身的 §5 矩阵不依赖这些委托，可独立执行。

**前置依赖**（frontmatter `depends_on` 字段）：

| 依赖 FEAT | FEAT-112 依赖它的什么 | 本 testplan 承接范围 | 跑相关 FEAT testplan 承接范围 |
|---|---|---|---|
| **FEAT-054** deepanalyze-host-agent | DA 服务入口（`/run` / `/run-stream`）、健康面接线、稳定错误码 **181012**（上下文过长事实基线） | D 组双入口拒收契约 / G 组诊断面（**FEAT-112 自己的新增行为**：503 契约、诊断面 degradationGate 块） | `/run` / `/run-stream` **入口本身的完整语义**（参数校验、taskId 注册、SSE 契约）→ 跑 FEAT-054 testplan |
| **FEAT-109** dangerous-operation-guardrail-and-hooks | 结构化护栏拦截/用户拒绝事实（用于 `GUARDRAIL_DENIED` 分类判定） | 无（本 testplan 不直接测护栏，分类走 K18–K20） | 护栏拦截的详细语义、denied 证据结构、因果边界 → 跑 FEAT-109 testplan |
| **FEAT-110** task-checkpoint-resume | `/resume` 续跑入口；探针 awaiting → 续跑的最终终态唯一记账 | E6 探针 awaiting 关门不记样本、E10 回答续跑与 inject 后同一逻辑任务只记一次（**FEAT-112 契约**，出口 13） | `/resume` 入口本身的续跑语义、checkpoint 恢复 → 跑 FEAT-110 testplan |
| **FEAT-111** trajectory-reflection-and-cost-tracking | 共享 agent-runtime-ext-java 的 `TerminalClass` 结构化任务终态分类机制 | K18–K20 代表场景（**验证降级门消费分类结果**） | **九态分类矩阵全覆盖**（原 K1-K17）、分类因果规则、denied 证据的归类边界 → 跑 FEAT-111 testplan |
| **FEAT-064** model-call-error-taxonomy-and-retry | 模型调用错误分类与重试策略（重试耗尽才形成任务终态，重试成功只计正常样本） | K18 只验重试耗尽后的记账 | 重试次数、退避与错误分类本身 → 跑 FEAT-064 testplan |

**在途控制面依赖**（本 FEAT 保护"不改写既有行为"，不验证控制面本身）：

| 依赖 FEAT | FEAT-112 依赖它的什么 | 本 testplan 承接范围 | 跑相关 FEAT testplan 承接范围 |
|---|---|---|---|
| **FEAT-001** standardized-agent-service-entrypoint | A2A 协议层入口 `/status`、`/task`、`/tasks`、`/stream` 的既有语义 | F5 冒烟（**只验证 OPEN 下这些端点仍 200，不验证语义正确**）| `/status` / `/task` / `/tasks` / `/stream` 的**完整响应语义**（快照字段、状态序列、SSE 契约、重连回放）→ 跑 FEAT-001 testplan |
| **FEAT-008** user-interaction-interrupt-and-response | 用户交互入口 `/inject`、`/answer`、`/message` 的既有语义 | F5 冒烟 | `/inject` / `/answer` / `/message` 的**完整交互语义**（awaiting 转换、权限确认、事件流）→ 跑 FEAT-008 testplan |

**不在本 testplan 范围的能力**（已在 §1.1 列出剔除项，重新索引到归属 testplan）：

| 剔除的场景 | 由哪个 testplan 承接 |
|---|---|
| 原 K 组 K1-K17 九态分类矩阵全覆盖 | **FEAT-111 testplan** 分类矩阵测试 |
| 原 F6 / F7-F8 `/status` / `/task` / `/stream` 不被阻断的完整语义 | **FEAT-001 testplan** 控制面入口测试 |
| 原 F9-F11 `/cancel` / `/inject` / `/answer` 不被阻断的完整语义 | **FEAT-008 testplan** 用户交互测试 |
| 原 F12 `/resume` 不被阻断的完整语义 | **FEAT-110 testplan** 续跑入口测试 |

**测试执行时的依赖顺序**（CI 流水线编排用）：

```text
FEAT-109 testplan       FEAT-111 testplan（分类矩阵）
      ↓                       ↓
      └──── 分类机制与护栏事实已验证 ────┐
                                      ↓
                              FEAT-112 testplan（本 testplan）
                                      ↓
                          FEAT-054 / FEAT-001 / FEAT-008 / FEAT-110 testplan
                          （控制面详细语义，可并行）
```

**依赖冲突处置**：如果 FEAT-111 分类机制 testplan 失败 → **先停跑本 testplan**（因为 K18–K20 代表场景假设分类正确；分类错了本 FEAT 测不出真问题）→ 修复 FEAT-111 后再跑 FEAT-112。

## 4. 部署拓扑

### 4.1 整体拓扑

```text
SIT test driver（HTTP / SSE 客户端，调用 DA 自有 REST 接口；不是 A2A）
  --/run + /run-stream--> deepanalyze-agent (DA 真实部署，instanceId=deepanalyze-agent-xxxxxx)
                            |
                            |-- 模型 endpoint：deepanalyze.model.backend.base_url 指向 stub（地址带 /v1）
                            |-- 测试按用例声明的部署形态托管启停（SutStack / 隔离进程）
                            |-- 服务间认证 X-Engine-Secret（可选启用）

  --/v1/health/detail--> 同一实例（三态诊断，带服务间认证）

  --既有控制面 (/status /cancel /inject /answer /resume 等，DA 已实现)-->
      同一实例（F5 冒烟验证：OPEN 下不受降级门阻断——FEAT-112 §2「只拦新增负担」契约）

多实例拓扑（H 组用）：
  deepanalyze-agent-A（instanceId=A，独立 LlmFaultInjectionStub）
  deepanalyze-agent-B（instanceId=B，独立 LlmFaultInjectionStub）

窗口、阈值、静默期由每个用例类声明的部署形态设置（不是全局固定值），例如
  标准形态：window 60s、min-sample-size 5、open-quiet-period 10s；C7 需要 window 短于 open-quiet-period
```

> **注**：场景都通过**模型层替身**驱动（故障注入、按请求关联的时序控制、持续流），不需要下游 agent Mock 或 callback receiver Mock，见 §4.2。

### 4.2 外部依赖与可控对端（模型替身）

**原则**（D1 §8f）：
- 真实 LLM 不会返回故障——故障必须经 stub 构造
- Stub 类 Fixture 必须 **测试组 demo / stub 开发者**开发（不是开发组），因为 stub 深度嵌入 demo 依赖链，需要 demo 提供配置切换机制
- testplan 作者负责**规格 + 协同**（告诉测试组内 demo/stub 开发者要开发哪些 stub、切换点在哪、testing API 长什么样）

本 FEAT 需要三种模型层替身（都由测试组开发，均不是产品代码）：

```yaml
stubs:
  - name: LlmFaultInjectionStub
    purpose: 按序返回正常、工具调用或故障响应，驱动窗口记账、状态迁移与探针结果
    demo_switch: 部署属性 deepanalyze.model.backend.base_url 指向 stub，地址必须带 /v1（缺 /v1 时所有模型调用 404，任务照样失败，会造成“已注入”的假象）
    testing_api: [set-next-response, set-next-error, set-pattern, reset, stats]
    activation_evidence: stub 的 totalRequests / errorResponses 计数变化（注入失败必须伴随 errorResponses 增长）
    owner: 测试组；代码在 solution 仓 common/example/task-degradation-gate-acceptance-demo/degradation-demo-common/
  - name: CorrelatedModelStub
    purpose: 按请求关联的屏障，精确控制并发时序（C2、C5、C6、C8、C9、E1、F5 等）
    owner: 测试仓 fixtures/task_degradation_gate/drive
  - name: SustainedModelStream
    purpose: 持续流式输出，制造慢消费者下的订阅溢出（J4）
    owner: 测试仓 fixtures/task_degradation_gate/drive
```

**错误格式要求**：凡是与分类相关的场景，stub 必须按真实模型提供方的错误格式返回（例如上下文超长须为 `error.code=context_length_exceeded` 的 400）。stub 的简易错误码只是 HTTP 状态加简化错误体，不一定被 core 正确分类（例如配额不足被映射成 429，core 会按限流处理），不能据此宣称分类已激活。

**工具调用要与产品实际工具一致**：业务失败由模型调用 `business_failure(output)` 产生（普通工具的空结果不是业务失败）；护栏拒绝用 `write_file` 写工作区外路径（DA 预检拒绝）或工作区内路径触发确认后经 `/answer` 拒绝构造。DA 没有 `shell_exec`，也没有注册 `delete_file`。

**不需要的 Mock**（曾经计划但剔除）：
- `MockSearchAgent`：下游 agent 层故障可通过模型层故障注入等效触发
- `MockCallbackReceiver`：FEAT-112 不是 push notifications 特性，没有 callback 场景
- `CredentialSpoofer`：上游 401/403 由 stub 按真实提供方格式返回

### 4.3 Fixture 清单

| Fixture | 类别 | 作用 |
|---|---|---|
| `DeploymentForm` / `GateDeployment` | 环境控制 | 每个用例类声明部署形态（窗口、阈值、认证、白名单等），经 `SutStack` 托管启动；缺部署记 infra_failure，不跳过 |
| `IsolatedDefaultProcess` / `SafeProcessCapture` | 环境控制 | 交付默认（不带任何门配置）启动、非法配置启动失败的有界脱敏采集、进程输出监听 |
| `Feat112Artifacts` | 环境控制 | 按坐标解析载体，记录 sha256 与构建清单中的 solution/core commit |
| `DaEvidenceClient` | 协议驱动 | 调用 DA 自有 REST 与 SSE 接口，每次交换脱敏落盘为证据 |
| `GateDriver` | 协议驱动 | 建立门状态、带激活证明的故障注入、读取诊断快照 |
| 时钟控制（libfaketime） | 环境控制 | Linux 下以 `LD_PRELOAD` 控制 DA 进程时钟，运行中调整偏移，构造窗口内乱序与过期事实（B6、B7、E9）；回拨是否生效以任务 `createdAt`、响应 `Date` 头等 DA 自报时间核对。已实现（`ClockControl`），尚未在 Linux 上实跑，须先确认对 JVM 生效 |
| 数据面部署形态 | 环境控制 | 测试托管 PostgreSQL（`deepanalyze.data.pg.enabled=true`）用于 F3 的 team 不存在、F4、E11；测试托管 Redis（core Redis checkpointer）用于 E15。已实现（`PostgresDataPlane`、`RedisCheckpointStore`，Testcontainers），需要 Docker，尚未实跑 |

### 4.4 边界要求

- 只做 SIT。判定证据按顺序取：① 对外契约（HTTP、SSE、诊断面）；② 契约观测不到时取进程日志；③ 仍不行时取持久化数据（Redis、数据库）；④ 都观测不到则提“特性不可观测”Issue。不以直接调用产品内部类或测试自造的内部事实代替。前置与激活证据可以来自 stub 计数和进程输出（例如 J4 的溢出日志、I1 的启动记录）
- 可用日志：启动记录 `da degradation gate wired enabled= instanceId= (delivery default applied=)`（INFO）；逐任务终态分类 `terminal fact taskId= class= code=`（DEBUG，需部署声明 `logging.level.com.openjiuwen.da.compat.DaDegradationSupport=DEBUG`）；`da_reflection_result`（INFO，只在六类失败事实上输出，可区分“记为失败”与完成、取消、未受理）。门的状态迁移、探针认领与释放、过期事实目前没有日志
- 门状态、窗口与终态事实只在内存，不落 Redis 或数据库；PG `workflows` 的状态是流级别，不等于终态分类，不作分类证据
- 判定时机：`done` 事件先于记账发出，判定前轮询诊断面直到样本数变化
- 诊断查询有副作用：每次查询推进窗口水位，可能触发 CLOSED→OPEN、OPEN→HALF_OPEN；B5、C7 等依赖迁移时机的场景在关键时段不插入诊断查询
- 不使用强制开关门端点（本特性不提供）；不合成假探针
- 由测试托管进程启停制造重启语义（I 组）
- **故障注入必走 stub**——真实 LLM 不会按需返错，故障场景由 §4.2 的模型层替身驱动
- Stub 的测试 API 调用由 SIT test driver 发起，不污染 SUT 配置
- 服务间认证 `X-Engine-Secret` 启用后，测试必须携带有效 token
- J1/J2 **客户端断流**通过测试驱动端主动 close SSE connection 实现，不需要下游 Mock

### 4.5 开发优先级与协同对齐（D1 §8e / §8f —— 测试组内部分工）

**分工澄清**（D1 §8e）：开发组**只产出 agent runtime**（FEAT-112 降级门机制本身）；**demo / stub / Fixture / 测试代码 / CI 全部由测试组内部开发**。testplan 作者的 stub 规格清单（§4.2）是**测试组内部的协同契约**，**不是**向开发组提的需求。

**testplan 作者给测试组内部的清单**：

| 动作 | 内容 | 对齐角色 |
|---|---|---|
| ① 识别 | 以 LlmFaultInjectionStub 注入 LLM 层故障为主；时序竞争（C5/C6/F5）另需按请求关联的模型替身，慢消费者（J4）另需持续流替身。stub 规格先推定，首轮真实执行后按实际调用与错误格式回写 | testplan 作者自识别 |
| ② 规格 | §4.2 已明确三种替身的用途、切换方式、测试接口与错误格式要求（全是测试组内角色） | testplan 作者产出 |
| ③ 对齐 | 和测试组内 demo 开发者确认：部署属性 `deepanalyze.model.backend.base_url` 指向 stub 且带 `/v1` | 测试组内 demo / stub 开发者 |
| ④ 场景矩阵标注 | §5 场景矩阵的 Fixture 列写测试仓承接类并标验收层（均为 `[SIT]`；无承接用例的写“未承接：原因”），由代码 `@Covers` 回填 | 测试组 CI 维护者 |

## 5. 测试场景矩阵

**ID 规范**（参考 D1 §6c）：`FEAT-112-{域}{序号}[-{变体}][.{测试点}]`

- **域**（大写字母）：A 配置 / B 窗口与分类 / C 状态机与故障代际 / D 双入口拒收 / E 探针闭环 / F 校验顺序与在途控制面 / G 健康诊断面 / H 多实例 / I 进程重启 / J 传输层 / K 分类消费。新增域按字母顺序追加，已用字母不改含义。
- **场景**：表中一行，`{域}{序号}`。前置或步骤不同即为不同场景，取新序号。
- **变体**：同一期望、不同输入或部署形态，`{场景}-{变体名}`，变体名用小写短词（如 `D1-run`、`D1-stream`）；每个变体单独一行、单独给出结论。
- **测试点**：同一场景、同一前置与步骤下需要分别给出结论的检查，`{场景}.{n}`（如 `B1.1`–`B1.3`）；场景结论取各测试点中最严重的。
- **序号**：从 1 开始按新增顺序递增，不按表内位置、不重排，允许空号；新增场景取本域下一个未用序号，不借用测试点或变体写法。
- **撤销与改名**：撤销或改名后的原 ID 不再使用，登记在本节末“已撤销 ID 与历史写法”。
- **类型不进 ID**：代表场景、冒烟保护等类型写在场景描述里。
- **代码绑定**：测试代码以全称 `FEAT-112-{ID}` 绑定（`@Covers`、`@DisplayName`），双向对账；格式 `^FEAT-112-[A-Z]\d+(-[a-z][a-z0-9]*)?(\.\d+)?$`。

### 5.1 A 组｜配置与装配

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| A1 | MUST | — | ext 默认关闭不产生降级 | ext 单独装配（不经 DA 装配层），配置中不写 `enabled` 键 | 连续发起系统异常任务 | 诊断 `enabled=false`，`/run` 不返 503（特性档“ext 默认关闭”）。仓内除 DA 外没有装配降级门的可运行宿主，本场景在 SIT 无法构造；DA 宿主内键缺席的处理见 A2 | 未承接：仓内除 DA 外没有装配降级门的可运行宿主（ext 无自动装配），SIT 无法构造；待特性设计确认 ext 单独装配是否另行验收 |
| A2 | MUST | — | DA 交付默认开启 | DA 自动装配，`degradation-gate` 键缺席 | 启动并访问诊断 | `enabled=true`，`state=CLOSED`。键缺席时如何处理特性档未规定（只写“DA 交付配置显式开启”），待特性设计确认；确认前本场景的结论只说明实现行为，不能当作满足特性档 | `[SIT] Feat112ProcessConfigurationTest` |
| A3 | MUST | — | 非法 `window` 启动失败 | 设 `window: 0s` | 启动 DA | 启动失败（非零退出），输出指出键名 `openjiuwen.service.degradation-gate.window`，不静默替换默认值（特性档 §3.3、验收出口19） | `[SIT] Feat112ProcessConfigurationTest` |
| A4 | MUST | — | 非法 `min-sample-size` 启动失败 | 设 `min-sample-size: 0` | 启动 DA | 启动失败，输出指出键名 `openjiuwen.service.degradation-gate.min-sample-size`（验收出口19） | `[SIT] Feat112ProcessConfigurationTest` |
| A5-zero | MUST | — | 非法阈值 0 启动失败 | 设 `failure-rate-threshold: 0` | 启动 DA | 启动失败，输出指出键名 `openjiuwen.service.degradation-gate.failure-rate-threshold`（验收出口19） | `[SIT] Feat112ProcessConfigurationTest` |
| A5-gt1 | MUST | — | 非法阈值大于 1 启动失败 | 设 `failure-rate-threshold: 1.5` | 启动 DA | 同 A5-zero | `[SIT] Feat112ProcessConfigurationTest` |
| A5-nan | MUST | — | 非法阈值 NaN 启动失败 | 设 `failure-rate-threshold: NaN` | 启动 DA | 同 A5-zero | `[SIT] Feat112ProcessConfigurationTest` |
| A6 | MUST | — | 非法 `open-quiet-period` 启动失败 | 设 `open-quiet-period: -1s` | 启动 DA | 启动失败，输出指出键名 `openjiuwen.service.degradation-gate.open-quiet-period`（验收出口19） | `[SIT] Feat112ProcessConfigurationTest` |
| A7 | MUST | — | 合法边界 `min-sample-size=1` | 设 `min-sample-size: 1` | 启动 + 单条系统异常 | 启动成功；第 1 条异常达到阈值即 CLOSED→OPEN | `[SIT] Feat112WindowParametersTest` |
| A8 | MUST | — | 合法边界 `failure-rate-threshold=1.0` | 设阈值为 1.0 | 启动 + 构造 100% 异常率 | 100% 异常率才开门；99% 不开 | `[SIT] Feat112WindowParametersTest` |
| A9 | MUST | — | 合法边界 `open-quiet-period=0` | 设为 0s | OPEN 状态下立刻查诊断 | 下一次访问可进 HALF_OPEN（不需等待） | `[SIT] Feat112WindowParametersTest` |
| A10 | MUST | — | 畸形 enabled 布尔绑定启动失败 | 设 `enabled: yesnot`（非法字符串） | 启动 DA | 启动失败，错误指出键名 `openjiuwen.service.degradation-gate.enabled`（特性档 §3.3；能力表“配置与默认值”MUST） | `[SIT] Feat112ProcessConfigurationTest` |

> **注**：出口 19 的“启动期失败并指出键名”在 DA 进程层面验证，开发侧单元测试不能替代。

### 5.2 B 组｜窗口与分类

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| B1.1 | MUST | — | 最小样本 20 — 第 19 个异常**不拒新** | 配置 `min-sample-size: 20`，窗口清空 | 连续发 19 条系统异常任务 | 第 19 条记账完成后 `state=CLOSED`；第 20 条新任务不被拒 | `[SIT] Feat112WindowParametersTest` |
| B1.2 | MUST | — | 第 20 个有效异常**记账达标** | 接 B1.1，第 20 条正在发送 | 第 20 条系统异常任务完成 | 第 20 条记账后，诊断显示 `sampleCount=20`、`systemFailureCount=20`、`systemFailureRate=1.0`，`state=OPEN` | `[SIT] Feat112WindowParametersTest` |
| B1.3 | MUST | — | 阈值达标后第 21 个**新任务被拒** | 接 B1.2 | 发第 21 条新任务 | 返回 HTTP 503 `ENGINE_DEGRADED` | `[SIT] Feat112WindowParametersTest` |
| B2 | MUST | — | 异常率 25%（10/40）不开门 | 配置默认阈值 0.50 | 构造 10 系统异常 + 30 正常终态 | `systemFailureRate=0.25`，`state=CLOSED` | `[SIT] Feat112WindowParametersTest` |
| B3 | MUST | — | 异常率 50%（10/20）开门 | 默认阈值 0.50 | 构造 10 系统异常 + 10 正常终态 | `state=OPEN` | `[SIT] Feat112WindowParametersTest` |
| B4 | MUST | — | 窗口过期剪枝 | 缩短 `window: 30s` | 构造足量异常 → OPEN → 等 30s+ 窗口过期 | 旧样本从快照中剪枝（`sampleCount` 下降）；但 `state` 不自动 CLOSED | `[SIT] Feat112WindowParametersTest` |
| B5 | MUST | — | 残窗双阈值达标后新任务到达即开门 | 窗口曾有足量样本但比例未达标；较早的健康样本先过期 | 健康样本过期后发起新任务 | 残窗样本量与异常率同时达标时，新任务到达即转 OPEN 并被 503 `ENGINE_DEGRADED` 拒收（特性档 §5.1.2 滑动窗口、§4“异常率超标”） | `[SIT] Feat112WindowParametersTest` |
| B6 | MUST | — | 窗口内乱序到达的终态照常计入 | Linux 部署，DA 进程时钟受 libfaketime 控制；CLOSED，窗口内已有一条正常样本 | 把 DA 时钟回拨 Δ（0 < Δ < window，以任务 `createdAt` 核对回拨生效），再让一个任务以系统异常结束——其终态时刻早于已有样本、仍在窗口内 | 样本数与异常数各 +1（特性档 §5.1.2：统计窗口内全部有效终态，只有越过下界的才豁免） | `[SIT] Feat112ClockTest` |
| B7 | MUST | — | 过期事实豁免（特性档 §5.1.2/§5.1.4） | 同 B6 的时钟控制；缩短 `window`；分别建立 OPEN 与 HALF_OPEN（探针在途），两种前置下各有一个 OPEN 前受理的旧任务在途 | 把 DA 时钟回拨 window+Δ（以拒收响应的 `Date` 头核对），立即让旧任务以系统异常结束；HALF_OPEN 组再等实际时间越过窗口下界后让当前探针正常完成 | 旧任务的失败按过期事实处理：样本数、异常数与事实到达前相同，门状态不迁移（OPEN 保持 OPEN、HALF_OPEN 保持 HALF_OPEN 且探针仍在途）；HALF_OPEN 下当前探针随后正常完成仍能 CLOSED，证明该事实没有递增故障代际。`retryAfterSeconds` 不作比较：静默期按原始时钟计算，回拨本身就会改变它 | `[SIT] Feat112ClockTest` |
| B8 | MUST | — | 空窗口 `systemFailureRate=0`，禁 NaN | DA 刚启动，无样本 | 查询诊断 | `sampleCount=0`、`systemFailureCount=0`、`systemFailureRate=0`（不是 NaN） | `[SIT] Feat112WindowAccountingTest` |

> **注**：公开面造不出窗口内乱序到达与过期事实，B6、B7、E9 经时钟回拨构造（§4.3 时钟控制）。

### 5.3 C 组｜状态机与故障代际

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| C1 | MUST | — | CLOSED → OPEN 条件（样本 + 阈值同时满足） | `min-sample=20`、`threshold=0.5` | 构造 20 条样本中 10 条系统异常 | 第 20 条记账后 `state=OPEN` | `[SIT] Feat112WindowParametersTest` |
| C2 | MUST | — | OPEN → HALF_OPEN，首个有效新任务正常受理（特性档 §5.1.6） | 静默期已满，无探针在途；控制获准任务保持执行中 | 发起有效真实任务，在其仍在途时查询健康诊断并发起竞争请求 | 首个任务按原任务接口契约被受理并进入真实执行链；在途诊断为 HALF_OPEN、probeInFlight=true；重叠竞争请求返回503。成功响应不要求 claimId 或门状态，不暴露探针身份；任务最终结果仍按原契约返回 | `[SIT] Feat112ProbeTest` |
| C3 | MUST | — | HALF_OPEN → CLOSED（探针健康 + 代际未变） | HALF_OPEN，探针已 claim | 探针任务正常完成 | `state=CLOSED`，窗口清空 | `[SIT] Feat112StateMachineTest` |
| C4 | MUST | — | HALF_OPEN 任一受理任务系统异常 → OPEN + 递增代际 + 重算静默期 | HALF_OPEN，探针在途 | 探针任务系统异常终态 | `state=OPEN`；静默期重新计时（`retryAfterSeconds` 回到接近完整静默期）；拒收响应不暴露探针身份。故障代际值不在诊断面与日志中（已提不可观测 Issue），“递增代际”的外部效果——迟到的探针成功不得 CLOSED——由 C5、C8 验证 | `[SIT] Feat112StateMachineTest` |
| C5 | MUST | — | **故障代际防迟到**：旧任务故障先记账则回 OPEN，迟到探针成功不覆盖 | HALF_OPEN，探针 + 旧 CLOSED 期在途任务并存 | 旧任务先系统失败（计入代际）→ 探针后成功 | `state=OPEN`（旧故障已递增代际）；迟到探针成功不能 CLOSED | `[SIT] Feat112OrderedControlTest` |
| C6 | MUST | — | 故障代际防迟到：CLOSED 先成立后，新故障按正常 CLOSED 窗口记账 | HALF_OPEN 探针健康成功后 CLOSED 已成立 | 后续故障到达 | 按 CLOSED 窗口正常记账，不追溯改写已完成迁移 | `[SIT] Feat112OrderedControlTest` |
| C7 | MUST | — | 旧样本过期不自动 CLOSED | OPEN；`window` 短于 `open-quiet-period` | 等待样本全部过期，再等静默期满，期间不发任何任务 | 分三段：样本在 OPEN 期间过期完毕时严格保持 OPEN、样本数 0；静默期满后转 HALF_OPEN；之后无流量时保持 HALF_OPEN、无探针在途、无模型调用（特性档 验收出口4、§5.1.7） | `[SIT] Feat112WindowParametersTest` |
| C8 | MUST | — | 探针取消落定后，旧任务未过期系统故障仍回 OPEN | HALF_OPEN，探针与一个 OPEN 前受理的旧任务都在途 | 取消探针并等落定（保持 HALF_OPEN）；随后旧任务以系统异常结束 | 门回 OPEN 并重新计静默期（特性档 验收出口15“探针取消后旧任务的未过期故障同样回 OPEN”） | `[SIT] Feat112OrderedControlTest` |
| C9 | MUST | — | OPEN 中旧任务的未过期系统故障重算静默期 | OPEN，一个 OPEN 前受理的旧任务在途 | 旧任务以系统异常结束 | 最近故障时间前移：诊断 `retryAfterSeconds` 回到接近完整静默期，转 HALF_OPEN 的时间相应推迟（特性档 §5.1.2） | `[SIT] Feat112OrderedControlTest` |

### 5.4 D 组｜双入口拒收

> 场景 ID“D4”与特性档中的“D4”同名不同义：特性档的 D4 指前端提示（第 55、90、98、176 行），本节 D4 指 HALF_OPEN 拒收省略恢复时间。

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| D1-run | MUST | — | `/run` OPEN 返回 503 `ENGINE_DEGRADED` | OPEN | POST `/api/agents/run` | HTTP 503，body 含 `code=ENGINE_DEGRADED`、`degraded=true`、`retryable=true`、`state=OPEN`、`instanceId`、`retryAfterSeconds`、`error="Agent is temporarily not accepting new tasks"` | `[SIT] Feat112AdmissionTest` |
| D1-stream | MUST | — | `/run-stream` OPEN SSE 建立前返回 JSON（非 200 假 SSE） | OPEN | POST `/api/agents/run-stream` | HTTP 503 JSON；**不先 200 再 SSE**；无 `start`/`error`/`done` 假任务流；JSON body 字段与 D1-run 完全一致 | `[SIT] Feat112AdmissionTest` |
| D3 | MUST | — | OPEN 返回 `Retry-After` 与 `retryAfterSeconds` 一致 | OPEN，剩余静默 87s | 请求头比对 | `Retry-After: 87` 与 body `retryAfterSeconds=87` 相同（同向上取整秒数） | `[SIT] Feat112AdmissionTest` |
| D4 | MUST | — | HALF_OPEN 省略 `retryAfterSeconds` 和 `Retry-After` | HALF_OPEN，探针已占用 | 请求 `/run` | body 不含 `retryAfterSeconds` 字段；response header 无 `Retry-After` | `[SIT] Feat112ProbeTest` |
| D5 | MUST | — | 503 带 `state` + `instanceId` 两字段 | OPEN 或 HALF_OPEN | 读 response body | 两字段非空且语义正确 | `[SIT] Feat112AdmissionTest`、`[SIT] Feat112ProbeTest` |
| D6 | MUST | — | 拒收不创建 taskId、任务记录、start 事件、不进入窗口 | OPEN | `/run` 被拒后查询任务列表 | 任务列表无新增；执行链无调用；窗口 `sampleCount` 不变 | `[SIT] Feat112AdmissionTest` |

### 5.5 E 组｜探针闭环

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| E1.1 | MUST | — | 半开唯一探针，恰一真实任务执行 | HALF_OPEN，无探针在途；获准任务保持执行中直到竞争请求完成 | 同时发5条有效新任务，记录重叠区间 | 恰1条正常受理并进入真实执行链，任务按原接口契约出现在任务列表；健康诊断 probeInFlight=true，其余503；不要求公开响应包含 claimId 或探针身份 | `[SIT] Feat112ProbeTest` |
| E1.2 | MUST | — | 半开唯一探针 — **并发中其余 4 条被拒** | 同 E1.1 | 同 E1.1 | 其余 4 条返回 HTTP 503 `state=HALF_OPEN`；不进任务列表、不占探针 | `[SIT] Feat112ProbeTest` |
| E2 | MUST | — | 探针 `COMPLETED` 正常健康关门 | HALF_OPEN，探针 claim | 探针任务正常完成（最终 success） | `state=CLOSED`；窗口清空；后续任务正常接收 | `[SIT] Feat112StateMachineTest` |
| E3 | MUST | — | 探针以预期业务失败结束时健康关门 | HALF_OPEN；`business_failure` 工具无需用户确认即可执行的部署形态（出厂配置下该工具落入 actrule 默认 ask，探针会先停在待确认并按 E6 关门，本场景在出厂形态下走不到；该工具是否需要用户确认待特性设计确认，确认不需要后前置改为交付配置） | 模型调用 `business_failure(output)` 宣告业务失败，任务以 failed 结束 | `state=CLOSED`、窗口清空（特性档 §5.1.6、验收出口12）；探针自身样本在清空后是否保留特性档未写清，不作期望 | `[SIT] Feat112ProbeTest` |
| E4 | MUST | — | 结构化 181012 上下文过长只计分母 | CLOSED 空窗口 | 任务以真实提供方格式的上下文超长错误失败，产品归类为 181012 | `sampleCount+1`、`systemFailureCount` 不变（特性档 §5.1.3、验收出口5）。探针以该结果结束后的门状态特性档未规定（§5.1.6 健康结果只含正常完成、预期业务失败、awaiting_answer），不作期望 | `[SIT] Feat112WindowAccountingTest` |
| E4-formats | MUST | — | 不同提供方格式的上下文超长同样只计分母 | CLOSED 空窗口 | 两种提供方格式各发一次：① `error.code=context_length_exceeded`、提示文案不含长度字样；② `error.code=invalid_parameter_error`、文案为输入长度范围（如 `Range of input length should be [1, 8192]`） | 两次都按结构化 181012 处理：每次 `sampleCount+1`、`systemFailureCount` 不变，与提供方格式和提示文案无关（特性档 §5.1.3 第 258 行） | `[SIT] Feat112WindowAccountingTest` |
| E13 | MUST | — | 主链曾被护栏拒绝、随后正常完成：只计分母 | CLOSED 空窗口 | 模型调用 `write_file` 写工作区外路径被 DA 预检拒绝，随后给出最终回答 | 任务 completed；`sampleCount+1`、`systemFailureCount` 不变（特性档 §6：曾出现 denied 不得改写分类） | `[SIT] Feat112WindowAccountingTest` |
| E14 | MUST | — | 主链曾被护栏拒绝、随后系统异常：计分子 | CLOSED 空窗口 | 同 E13 先被拒，之后的模型调用持续 5xx 直到重试耗尽 | 任务 failed；`sampleCount+1`、`systemFailureCount+1`（特性档 §6） | `[SIT] Feat112WindowAccountingTest` |
| E15 | MUST | — | 主链曾被护栏拒绝、随后原因不明失败：按系统异常计分子 | CLOSED 空窗口；部署使用 Redis checkpointer，由测试托管 Redis（checkpointer 只在 agent 运行开始、中断、结束时读写存储） | 模型先调用 `write_file` 写工作区外路径被拒，再调用 `ask_user` 使任务停在 awaiting_answer（检查点写入 Redis）；停掉 Redis 后经 `/answer` 续跑，续跑开始时恢复检查点失败，任务以无错误码、无类目、非 core 错误通道的方式失败 | 计入分子：`sampleCount+1`、`systemFailureCount+1`（特性档 §5.1.3 第 258 行、§6）；DEBUG 终态日志 `class=INFRASTRUCTURE_FAILURE`、`code=` 为空作激活证据 | `[SIT] Feat112CheckpointStoreTest` |
| E16 | MUST | — | 成员调用因护栏拒绝耗尽、主任务正常完成：只计分母 | CLOSED 空窗口；成员工具的确认请求按 fail-closed 拒绝 | 模型发起成员调用，成员的工具尝试全部被拒并以 error 结束；主任务随后给出最终回答 | 任务 completed；`sampleCount+1`、`systemFailureCount` 不变 | `[SIT] Feat112MemberGuardrailTest` |
| E17 | MUST | — | 成员调用因护栏拒绝耗尽、主任务另因无关原因失败：按系统异常计分子 | 同 E16，主任务随后以无错误码、无类目的方式失败 | — | 计入分子（特性档 §6：最终失败不是由护栏导致）；当前实现会归 GUARDRAIL_DENIED（成员失败到任务失败的因果未建立，待特性设计确认） | 未承接：公开面造不出“无错误码、无类目、非 core”的任务失败，成员失败到任务失败的因果未建立；待特性设计确认 |
| E18 | MUST | — | 护栏正常拦截导致任务最终失败：只计分母 | 存在“护栏拦截导致任务失败”的确定路径 | — | `sampleCount+1`、`systemFailureCount` 不变（特性档 §5.1.3 第 248 行）；探针以此结束时门状态特性档未规定，不作期望 | 未承接：“护栏拦截导致任务失败”的确定路径尚未建立；待特性设计确认 |
| E6 | MUST | — | 探针 `awaiting_answer` → CLOSED 释放名额不记样本 | HALF_OPEN，探针代际未变 | 探针任务进入 awaiting | `state=CLOSED`；窗口清空；**不记样本**；名额释放 | `[SIT] Feat112ProbeTest` |
| E7 | MUST | — | 探针 `USER_CANCELLED` — 取消实际落定才释放，保持 HALF_OPEN | HALF_OPEN，探针在途 | 用户发 `/cancel` → 等落定 | 落定后保持 HALF_OPEN、释放名额，下一真实任务可以成为探针。“取消请求到达的瞬间不提前放出第二个探针”发生在一次同步 `/cancel` 调用内部，对外与日志均不可观测，已提不可观测 Issue | `[SIT] Feat112ProbeTest` |
| E8 | MUST | — | 探针 `NOT_ADMITTED`（认领后执行池拒绝）落定后释放 | HALF_OPEN 且探针名额空闲；`deepanalyze.compat.max-concurrent-tasks: 1`；开门前已有一个任务停在 awaiting_answer | 经 `/answer` 让该任务续跑并占住唯一执行线程（模型调用被替身挡住），随后发起新任务——认领探针后在提交执行池时被拒 | 新任务返回既有 503 `ENGINE_BUSY`（too many running tasks），不是 `ENGINE_DEGRADED`；门保持 HALF_OPEN、`probeInFlight=false`、样本不变；续跑任务结束后下一个真实任务可以成为探针（特性档 验收出口18、§5.1.6） | `[SIT] Feat112OrderedControlTest` |
| E9 | MUST | — | 当前探针过期落定仅释放（特性档 §5.1.4/§5.1.6） | 同 B6 的时钟控制；缩短 `window`；HALF_OPEN，当前探针在途 | 把 DA 时钟回拨 window+Δ，立即让当前探针结束（正常完成、系统异常各一组），再发起下一真实任务 | 不增加样本，门保持 HALF_OPEN，只释放当前探针名额（`probeInFlight=false`）；过期的健康结果不得解释为恢复而 CLOSED，过期的失败不得回 OPEN；下一真实任务可以成为探针 | `[SIT] Feat112ClockTest` |
| E10 | MUST | — | 探针待答关门后，回答续跑与 inject 不重复记账 | HALF_OPEN，探针进入 awaiting_answer 并已关门（E6） | 经 `/answer` 回答，续跑中注入系统异常使任务最终失败；另一变体在回答前经 `/inject` 追加消息 | 该逻辑任务只记一次：`sampleCount+1`、`systemFailureCount+1`；回答、inject 不各自新增样本（特性档 验收出口13、能力表“每任务只记一次”） | `[SIT] Feat112ProbeTest` |
| E11 | MUST | — | 认领后、绑定前异常窗口不泄漏 | HALF_OPEN；PostgreSQL 数据面部署形态（`deepanalyze.data.pg.enabled=true`） | 发起带 `X-Da-Tenant-Id: *` 的新任务——认领探针后、绑定任务前，数据面查询抛出校验异常 | 请求以既有校验错误失败；探针名额被释放（`probeInFlight=false`、保持 HALF_OPEN），下一个真实任务可以成为探针（特性档 §5.1.4“避免永久占用探针名额”） | `[SIT] Feat112DataPlaneTest` |
| E12 | MUST | — | 认领后待答任务数触顶被拒，释放名额 | HALF_OPEN；`deepanalyze.compat.max-awaiting-tasks: 1`，已有一个任务停在 awaiting_answer | 发起新任务（认领探针后因待答触顶被拒） | 返回既有的 `ENGINE_BUSY` 拒绝，不是 `ENGINE_DEGRADED`；门保持 HALF_OPEN、`probeInFlight=false`；下一个真实任务可以成为探针（特性档 验收出口18“认领后并发/执行池拒绝按无结论释放名额”） | `[SIT] Feat112OrderedControlTest` |


### 5.6 F 组｜校验顺序与在途控制面回归冒烟

**范围收敛说明**（D1 §8e 原则）：OPEN 下 `/status`、`/cancel`、`/inject`、`/answer`、`/resume` 等**在途控制面本身的行为**由其所属 FEAT 的 testplan 承接（FEAT-001 控制面入口、FEAT-008 用户交互中断、FEAT-110 续跑）——本 FEAT 不重写。这里只保留 **「入站校验先于降级门」** 本特性契约 + **「OPEN 不改写控制面语义」** 一条冒烟保护。

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| F1 | MUST | — | 入站认证失败先于降级门 | OPEN 与 HALF_OPEN；部署已配置服务间认证 | 分别以缺失与错误 `X-Engine-Secret` 发 `/run`、`/run-stream` | 与门 CLOSED 时同一请求的既有认证错误相同，不是 503 `ENGINE_DEGRADED`；不占探针（特性档 §3.1、§5.1.1） | `[SIT] Feat112AuthenticatedTest` |
| F2 | MUST | — | 参数非法先于降级门 | OPEN 与 HALF_OPEN | 分别发缺 `sessionId`、`input` 与 `mediaIds` 均空、`runtimeConfig.version` 不受支持的 `/run`、`/run-stream` | 与门 CLOSED 时同一请求的既有校验错误相同，不是 `ENGINE_DEGRADED`；不占探针、不建任务（特性档 §5.1.9、验收出口18） | `[SIT] Feat112ValidationPrecedenceTest` |
| F3 | MUST | — | 媒体（不存在、白名单外）与 team（不存在、通道不可用）校验先于降级门 | OPEN 与 HALF_OPEN；部署配置媒体目录与白名单；“team 不存在”需要 PostgreSQL 数据面部署形态，否则只能走到“通道不可用” | 分别发：媒体不存在 / 白名单外 / team 不存在 / team 通道不可用 | 每项与门 CLOSED 时同一请求的既有校验错误相同，不是 `ENGINE_DEGRADED`；不占探针、不建任务、不进窗口（特性档 §5.1.9、验收出口18） | `[SIT] Feat112DataPlaneTest`、`[SIT] Feat112ValidationPrecedenceTest` |
| F4 | MUST | — | 租户头校验先于降级门 | OPEN 与 HALF_OPEN；PostgreSQL 数据面部署形态 | 发带 `X-Da-Tenant-Id: *`（保留租户）的 `/run`、`/run-stream` | 与门 CLOSED 时同一请求的既有校验错误相同，不是 `ENGINE_DEGRADED`；不占探针（特性档 §5.1.9、验收出口18）。当前实现在降级门之后才校验租户头，OPEN 下预计先返回 503；租户头是否属于出口 18 的“基本参数”在结论中注明 | `[SIT] Feat112DataPlaneTest` |
| F5 | MUST | — | **冒烟保护**：OPEN 下在途控制面入口**保持各自原契约**（不被降级门改写） | OPEN；根据各入口原契约准备对应前置状态 | 分别调用特性档 §3.1 列出的 9 个入口：`/status`、`/task`、`/tasks`、`/stream` 重连、`/cancel`、`/inject`、`/message`（活跃任务），`/answer`、`/resume`（awaiting-input 任务） | 每次响应与门 CLOSED 时同一请求的响应相同（状态码与错误码）；**关键**：响应 body 不含 `ENGINE_DEGRADED`、不含 `degraded=true`——即 OPEN 没把原契约改写为降级 503。**控制面本身行为的详细验证跑 FEAT-001/008/110 的 testplan**，本条只做"没被阻断"的冒烟 | `[SIT] Feat112OrderedControlTest` |

### 5.7 G 组｜健康诊断面

> 部署方的监控告警（特性档 §3.2 第 177 行）依赖的产品输出由本组与其他场景覆盖：`state`、`probeInFlight`、`instanceId` → G1–G3、C2、E7、E8、C7；进程启动记录 → I1。告警规则本身由部署验收承接，不在本 testplan 范围。

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| G1 | MUST | — | `/v1/health/detail` CLOSED 返 200 + healthy | CLOSED | GET `/v1/health/detail` | HTTP 200；`status=healthy`、`state=CLOSED`、无 `retryAfterSeconds` | `[SIT] Feat112DiagnosticsTest` |
| G2 | MUST | — | OPEN 返 200 + degraded + retryAfterSeconds | OPEN | GET | HTTP 200；`status=degraded`、`state=OPEN`、`retryAfterSeconds` 非空 | `[SIT] Feat112DiagnosticsTest` |
| G3 | MUST | — | HALF_OPEN 返 200 + degraded + 无 retryAfterSeconds | HALF_OPEN | GET | HTTP 200；`status=degraded`、`state=HALF_OPEN`、**无 `retryAfterSeconds`** | `[SIT] Feat112DiagnosticsTest` |
| G4 | MUST | — | 禁用态返 healthy + enabled=false + state=CLOSED + 零计数 | `enabled=false` | GET | `status=healthy`、`enabled=false`、`state=CLOSED`、`sampleCount=0`、`systemFailureCount=0`、`systemFailureRate=0`、`probeInFlight=false`（验收出口16） | `[SIT] Feat112DisabledGateTest` |
| G5-missing | MUST | — | 诊断面拒绝匿名访问 | 交付默认配置；已配置服务间认证的部署 | GET `/v1/health/detail` 不带 `X-Engine-Secret` | 被服务间认证拒绝（401/403），不返回诊断内容；已配置认证时与其它受保护端点对同一请求的拒绝相同（特性档 §3.2“纳入既有服务间认证，不像 /ready 一样豁免”、验收出口16） | `[SIT] Feat112AuthenticatedTest`、`[SIT] Feat112DiagnosticsTest` |
| G5-invalid | MUST | — | 诊断面拒绝错误凭据 | 已配置服务间认证 | GET 带错误 `X-Engine-Secret` | 与其它受保护端点对同一请求的拒绝相同，不返回诊断内容（特性档 §3.2、验收出口16） | `[SIT] Feat112AuthenticatedTest` |
| G11 | MUST | — | 合法服务间认证可读取诊断 | 已配置服务间认证 | GET 带正确 `X-Engine-Secret` | HTTP 200，诊断字段完整（特性档 §3.2、验收出口16） | `[SIT] Feat112AuthenticatedTest` |
| G8 | MUST | — | 诊断不泄露原始异常/用户输入/工具参数/供应商密钥 | 模型 API key 配置为哨兵值 | 用户输入带哨兵；模型调用以带哨兵的 500 错误体持续失败直至开门；模型发起参数带哨兵的 `write_file`（工作区外路径，被拒） | OPEN 下整段诊断响应不含任一哨兵，也不含错误堆栈（特性档 §3.2、验收出口16） | `[SIT] Feat112AuthenticatedTest` |
| G9 | MUST | — | `/health` 不因门状态变化 | OPEN 和 CLOSED 分别 | GET `/health` | 两次响应相同（门状态不影响） | `[SIT] Feat112DiagnosticsTest` |
| G10 | MUST | — | `/ready` 不新增门状态 down 分量 | OPEN 和 CLOSED 分别 | GET `/ready` | 两次响应相同（原组件就绪不变） | `[SIT] Feat112DiagnosticsTest` |

### 5.8 H 组｜多实例隔离

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| H1 | MUST | — | 两实例独立窗口（A OPEN B CLOSED） | 启两实例，仅向 A 注入故障 | 查 A、B 诊断 | A `state=OPEN`；B `state=CLOSED` | `[SIT] Feat112InstancesTest` |
| H2 | MUST | — | A 的 503/诊断带 A 的 instanceId | A OPEN | 请求 A 的 `/run` 和诊断 | 503 body 和诊断响应均 `instanceId=A` | `[SIT] Feat112InstancesTest` |
| H3 | MUST | — | A 的 CLOSED 不清除 B 的降级状态（特性档“D4 提示”在引擎侧的依据） | A CLOSED，B OPEN | 查 B 诊断 | B 仍 OPEN；A 的状态不影响 B | `[SIT] Feat112InstancesTest` |
| H4 | MUST | — | 两实例半开名额互不影响 | A、B 均 HALF_OPEN | A 被一个慢任务占住探针名额后，查 B 诊断并向 B 发新任务，再向 A 发新任务 | B `probeInFlight=false`、B 的新任务被受理为 B 的探针；A 的新任务仍 503 `state=HALF_OPEN`（验收出口17） | `[SIT] Feat112InstancesTest` |

### 5.9 I 组｜进程重启语义

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| I1 | MUST | — | 重启后新进程 CLOSED 空窗口初始化 | 旧进程 OPEN，部署监控保留原故障告警 | 停止并重启进程，读取新进程的启动记录与诊断 | 新 `instanceId`（和旧不同）、`state=CLOSED`、零计数、`probeInFlight=false`；**此结果只能记为"新进程初始化"，不得记为旧实例探针恢复**；新进程输出启动记录，同一行带 `enabled=true`、`delivery default applied=` 与 `instanceId`，其中 `instanceId` 与诊断一致，供监控关联（正式载体以 DA L2 合入版本为准）。“监控保留原故障告警、不因重启清除”属部署监控，由部署验收承接，本场景不覆盖 | `[SIT] Feat112InstancesTest` |
| I2 | MUST | — | 新进程不持久化旧窗口和探针状态 | 旧进程 OPEN，正在 HALF_OPEN 探针 | 重启 | 新进程无旧窗口样本、无旧探针 claim | `[SIT] Feat112InstancesTest` |

### 5.10 J 组｜传输层

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| J1 | MUST | — | 断流后任务最终成功只计分母 | 正常任务 | SSE 断开后任务后台继续成功 | 事实到达后：`sampleCount+1`、`systemFailureCount` 不变 | `[SIT] Feat112TransportTest` |
| J2 | MUST | — | 断流后任务最终系统失败计分子+分母 | 任务预期系统异常 | 经 `/run-stream` 发起，收到 start 后断开原始流，任务后台继续并最终失败 | `sampleCount+1`、`systemFailureCount+1` | `[SIT] Feat112TransportTest` |
| J3 | MUST | — | SSE 重连不新增样本 | 已受理任务在途 | 经 `/api/agents/stream/{taskId}` 在任务执行中多次重连并主动断开；任务结束后再重连一次 | 无额外样本、无额外模型调用；窗口 `sampleCount` 只因任务终态 +1 | `[SIT] Feat112TransportTest` |
| J4 | MUST | — | **慢消费者不改变任务终态分类**（补齐） | 任务执行中，客户端消费慢；部署声明 `deepanalyze.answer-gate.stream-buffering-enabled=false`（交付默认整段模型回复结束后才下发，慢消费条件不成立）与 `deepanalyze.compat.max-events-per-task=64` | 客户端缓冲堵塞，订阅溢出被断开；任务后台继续 | 任务最终按真实终态分类；慢消费者不独立记账 | `[SIT] Feat112ProcessConfigurationTest` |

### 5.11 K 组｜降级门消费分类结果的代表性验证（收敛后）

**范围收敛说明**（D1 §8e 原则）：**九态 `TerminalClass` 结构化分类机制本身**是 agent-runtime-ext-java 的共享能力，由 FEAT-111 "轨迹反思与成本追踪" 共享的分类点统一产生——**跑 FEAT-111 的 testplan** 验证分类覆盖和正确性（含 `must_violation` / `should_violation` / 稳定错误码 181012 / GUARDRAIL_DENIED 因果反例等 17 项），本 FEAT 不重写。

本节只保留 **3 条代表性场景**，验证"**降级门确实消费了 FEAT-111 产出的分类，并且分子/分母记账正确**"——这是 FEAT-112 自己的契约边界。

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| K18 | MUST | — | **代表系统异常类**：模型 5xx 重试耗尽 → 计分子+分母 | SIT 启动 FEAT-111 跑一遍确认 `TerminalClass=MODEL_SERVICE_FAILURE` 分类正确 | `LlmFaultInjectionStub.setNextError(ENGINE_MODEL_ERROR)` + 发一条新任务 | 诊断 `sampleCount+1` + `systemFailureCount+1` + `systemFailureRate` 更新 | `[SIT] Feat112WindowAccountingTest` |
| K19 | MUST | — | **代表只计分母类**：正常完成 → 只计分母不计分子 | 同上 | `LlmFaultInjectionStub.reset()` + 发一条正常任务 | 诊断 `sampleCount+1` + `systemFailureCount` 不变 | `[SIT] Feat112WindowAccountingTest` |
| K20 | MUST | — | **代表不记账类**：用户取消 → 不计分子不计分母 | 同上 | 发任务 → 立即 `/cancel` → 等 CANCELED 终态 | 诊断 `sampleCount` 不变 + `systemFailureCount` 不变 | `[SIT] Feat112ProbeTest` |

> **注**：原 K1-K17 分类矩阵全覆盖**不在本 testplan 范围**——跑 FEAT-111 的 testplan（分类矩阵测试）替代。本节 3 条代表场景只为验证"降级门正确消费分类结果"这一本 FEAT 契约。

### 已撤销 ID 与历史写法

| 原 ID | 原因 | 替代 ID | 级别 |
|---|---|---|---|
| A5.1、A5.2、A5.3 | 同一期望（启动失败并指出键名）、不同阈值取值 | A5-zero、A5-gt1、A5-nan | 变体 |
| D1a、D1b | 同一拒收契约、不同入口 | D1-run、D1-stream | 变体 |
| E4.1 | 期望同 E4，换提供方错误格式 | E4-formats | 变体 |
| E5 | 原“护栏拦截”一行拆成前置、步骤各不相同的六个场景 | E13–E18 | 场景 |
| E5.1–E5.6 | 前置、步骤各不相同 | E13–E18 | 场景 |
| F-reg | 类型（冒烟保护）不进 ID | F5 | 场景 |
| F6–F12 | 在途控制面的完整语义由 FEAT-001/008/110 testplan 承接，本 FEAT 只留 F5 冒烟 | — | 场景 |
| G5a、G5b | 同一期望（被服务间认证拒绝）、不同凭据 | G5-missing、G5-invalid | 变体 |
| G5c | 期望不同（合法凭据读取完整诊断） | G11 | 场景 |
| K1–K17 | 分类矩阵全覆盖由 FEAT-111 testplan 承接，编号不复用 | — | 场景 |
| K-rep1、K-rep2、K-rep3 | 类型（代表场景）不进 ID；K1–K17 不复用 | K18、K19、K20 | 场景 |
| D2、G6、G7 | 空号，docs 历史中未出现 | — | — |

## 6. 判定结果分类（6 顶层态 + reason_code）

对齐 D1 洞察 v4.1 §9b。本 testplan 使用以下结果分类：

**顶层 6 态**：

| 态 | 本 testplan 典型场景 | 处置 |
|---|---|---|
| **PASS** | 子特性被观测且通过 | 绿灯 |
| **FAIL** | 判定失败（含子特性违反 + 平台观察失败），下含 reason_code | 红灯；按 reason_code 分流（见下） |
| **WARN** | SHOULD/MAY 违反——FEAT-112 场景均为 MUST，不适用 | — |
| **EXCLUDED** | `level=OUT` 场景——§2 非范围项不进入 §5，本特性不会产生 | — |
| **UNSUPPORTED** | 不适用：FEAT-112 全部为 MUST，无契约可选能力 | 部署形态缺失记 FAIL/infra_failure，不记 UNSUPPORTED |
| **NOT_OBSERVED** | 原则上可观测、本次未采到证据 | 见下 |

**FAIL.reason_code 5 类分两组**：

- A 组｜子特性违反（**开发修**）：
  - `must_violation`：MUST 契约违反（例：D1 OPEN 返回非 503、E1 并发不限制单探针）
- B 组｜测试平台观察失败（**平台/prompt/基建修**）：
  - `infra_failure`：基建问题（SUT jar 启动超时、网络、Mock 挂）→ 重试 + 修基建
  - `model_variance`：LLM 非确定。本特性由确定性 stub 驱动，不适用；改用真实模型时再启用
  - `conditional_not_triggered`：前置或激活未成立（例：E3 探针停在待确认、没走到业务失败；J4 首轮订阅没有溢出）→ 结论无效，修前置后重跑（记 FAIL，不降为 WARN）
  - `other_untestable`：兜底必挂 issue

**NOT_OBSERVED.reason_code**（只有 `trace_missing` 一类）：

- 三面都观测不到（不是 reason_code，D1 v4.1 已撤销 `protocol_unobservable`）：对外契约、日志、持久化数据都观测不到的 MUST 不算判定态：报告中显示为覆盖缺口（未承接），同时提“特性不可观测”Issue，该 MUST 阻塞放行（例：E7 取消请求到达瞬间的窗口、C4 的故障代际值）；诊断面不泄露密钥（G8）是可观察的负向检查，不属于此类
- `trace_missing`：原则上可观察、但本次没有采集到证据——例：判定所需的诊断快照或 HTTP 响应因超时、落盘失败没有留下。判据不得把缺证据当作违规或通过；补齐采集后重跑（I1 的启动记录属验收判定：新进程须输出带 `instanceId` 的启动记录，缺失记 FAIL；监控告警关联属部署验收，不在本 testplan）

**报告**：每次运行生成逐场景的 `summary.md`，报告头列出口径（范围内场景数、按结论与按原因码的分布）、来源（测试仓提交、特性档基线、testplan 修订、载体构建清单）与被测物。没有任何承接用例的场景显示为 `NOT_EXECUTED`，它表示覆盖缺口，不是判定态，不计入 6 态。

## 7. Test Agent 与 Fixture

本 testplan 不使用确定性 Test Agent：SUT 是 deep-analyze 真实 Agent（模型由 stub 替身驱动，进程为真实部署）。受控状态通过部署形态声明的窗口、静默期等配置，加模型层故障注入与按请求关联的时序控制制造。替身见 §4.2，环境与协议驱动见 §4.3。

**开发侧测试**（`DegradationGateTest`、`DegradationGatePropertiesTest`、`DaProbeSlotHandleTest`、`DaDegradationAdmissionTest`、`DaDegradationDeliveryDefaultTest`、`DaHealthDetailE2ETest` 等）：仅供参考，不计入本 testplan 验收。本 testplan 的缺口由 §5 Fixture 列的“未承接”与测试仓覆盖登记表达。

## 8. 关键链路断言

- OPEN 下 `/run` 和 `/run-stream` 必须返回同一 503 契约（`ENGINE_DEGRADED` + `degraded=true` + `retryable=true` + `state` + `instanceId`）；`/run-stream` **必须在 SSE 建立前返回 JSON**
- `Retry-After` 和 `retryAfterSeconds` 必须使用相同向上取整秒数；HALF_OPEN 必须省略
- 降级拒收必须在 `taskId` 分配、任务注册、属主绑定、start 事件、执行提交**之前**；拒收后任务列表无记录、执行链无调用、窗口不变
- 入站校验（认证、参数、媒体、team）必须先于降级门；无效请求不占探针
- 分类矩阵必须由 `TerminalClass` 决定分子/分母；Controller、Translator、反思和健康面**不得**重新解析展示文本
- 唯一记账点：每个逻辑任务最多产生一次终态事实（`DaTaskRecord` CAS）；回答、恢复、inject、内部重试、工具、子 Agent 不各自放大样本
- 故障代际：按特性档 §5.1.2/§5.1.4 验收。HALF_OPEN 中未过期系统故障递增代际并回 OPEN，迟到探针成功不得覆盖（C5/C6）；过期事实不记样本、不改代际、最近故障时间或门状态（B7），当前探针过期落定只释放名额并保持 HALF_OPEN（E9）。判据来源是正式契约而非实现；单调窗口下界相等属于过期，窗口内新故障不得被豁免。
- 多实例：窗口、探针、`instanceId` 必须按实例隔离；A 的 CLOSED 不得清除 B 的降级状态（特性档“D4 提示”的引擎侧依据）
- 进程重启：新进程初始化不等同于真实探针恢复；监控必须结合进程启动事件保留原故障上下文
- 前置与激活：每个场景记录前置与激活证据（如注入故障确经声明的 stub 处理）；前置不成立记 FAIL/conditional_not_triggered，该次结论无效

## 9. 执行策略

**Smoke**（PR 门禁 + 日常 CI）：

- A1、A2、A7、A8（配置默认与边界）
- B1.1、B1.2、B1.3、B3（窗口基本行为）
- C1、C2、C3（状态机 happy path）
- D1-run、D1-stream、D4、D6（双入口拒收）
- E1.1、E1.2、E2、E6、E7（探针闭环）
- F1、F2、F5（校验顺序 + 控制面回归冒烟）
- G1、G2、G3、G5-missing、G5-invalid、G11（诊断面三态 + 认证）
- K18、K19、K20（降级门消费分类的代表场景——**全量分类矩阵跑 FEAT-111 testplan**）

**Full suite**（每日/周期 + release 前）：

- A1-A4、A5-zero、A5-gt1、A5-nan、A6-A10、B1.1-B1.3、B2-B8、C1-C9、D1-run、D1-stream、D3-D6、E1.1-E1.2、E2-E4、E4-formats、E6-E18、F1-F5、G1-G4、G5-missing、G5-invalid、G8-G11、H1-H4、I1-I2、J1-J4、K18-K20（**全量分类矩阵跑 FEAT-111 testplan**；原 F6-F12 控制面详细行为跑 FEAT-001/008/110 testplan）

**P0 必须全绿**（阻塞合入）：

- D1-run、D1-stream、D3、D4、D5、D6（双入口拒收契约）
- E1.1、E1.2、E6、E7（半开唯一探针 + awaiting + cancel 正确释放）
- F1-F2（基本校验顺序）
- G1-G3、G5-missing、G5-invalid、G11（诊断三态 + 认证）
- K18、K19、K20（降级门消费分类——全量分类走 FEAT-111）

**长运行 / 多进程的**（自动执行，随 Full suite 运行，不另标 manual）：

- 等待时间类：C7、B4（等样本过期再等静默期满，约 2.5 分钟）、C9、A9——按用例声明的部署形态缩短参数，留足余量
- 多进程与重启：H1-H4、I1-I2——测试自行托管两个 DA 进程或真实停启进程
- 竞争与完成顺序：C5、C6、C8、E8、E12——用按请求关联的模型替身控制各任务的完成顺序，不靠墙钟等待
- 终态时刻相关的 B6、B7、E9：Linux 下经 libfaketime 回拨 DA 进程时钟构造；E11、F4 与 F3 的 team 不存在分支：PostgreSQL 数据面部署形态；E15：Redis checkpointer 部署形态
- 不在本 testplan：部署侧监控告警联动（告警规则与“保留原故障告警”属部署验收）

**配置缩短原则**（遵循 FEAT-112 §6）：

- 只通过**公开配置**按用例声明的部署形态缩短 `window` 和 `open-quiet-period`（生产 5m/2m；测试按场景设定，如标准形态 60s/10s，C7 需 window 短于静默期）
- 只通过**故障注入**制造真实任务结果（下游 Mock 返 5xx、模型 stub 返超时、SutStack 停下游）
- **不得**依赖强制开门、假探针或直接篡改内部状态——本特性明示不提供

**结果判定按 §6 六态**：PASS / FAIL（含 reason_code）/ WARN / EXCLUDED / UNSUPPORTED / NOT_OBSERVED；报告形式见 §6。

---

## 附录 A｜测试代码目录结构

测试代码在 agent-runtime-acceptance 仓 `src/test/java/com/huawei/ascend/sit/` 下：

```text
cases/integration/task_degradation_gate/   SIT 用例（按部署形态与主题分类）
fixtures/task_degradation_gate/
  Feat112Contract / Feat112Coverage / Feat112ArchitectureTest
                                           条款出处、覆盖登记与 ID 对账、结构规则
  deploy/                                  部署形态与测试托管环境
  drive/                                   协议驱动与模型替身
  judge/                                   判据族及其自测（只消费已采集证据）
judge/                                     通用结果层（场景结论推导、报告）
```

## 附录 B｜Fixture 库索引

| Fixture | 代码位置 |
|---|---|
| `LlmFaultInjectionStub` | agent-solution 仓 `common/example/task-degradation-gate-acceptance-demo/degradation-demo-common/` |
| `CorrelatedModelStub`、`SustainedModelStream`、`DaEvidenceClient`、`GateDriver`、`OrderedControlRun`、`SlowSseConsumer` | `fixtures/task_degradation_gate/drive/` |
| `DeploymentForm`、`GateDeployment`、`IsolatedDefaultProcess`、`SafeProcessCapture`、`Feat112Artifacts`、`RedisCheckpointStore`、`PostgresDataPlane`、`ClockControl` | `fixtures/task_degradation_gate/deploy/` |
| `RejectionJudge`、`DiagnosticJudge`、`PrecedenceJudge`、`SequenceJudge`、`GateSnapshotChecks` | `fixtures/task_degradation_gate/judge/` |

## 附录 C｜历史契约回归

首发（`since_solution_version: 0.1.4`），`historical_regressions: []`，暂无条目。

## 附录 D｜§2 验收出口 20 项 → testplan ID 对照

| §2 验收出口 | 对应 ID |
|---:|---|
| 1. ext 未开启不产生降级 | A1 |
| 2. 最小样本 20 边界 | B1.1、B1.2、B1.3 |
| 3. 异常率 25% 不开、50% 开 | B2、B3 |
| 4. 窗口过期 + OPEN 无流量保持 OPEN + 静默期无假探针 | B4、C7 |
| 5. 分类矩阵全覆盖 | **跑 FEAT-111 testplan 的分类矩阵**（本 testplan 不含；§1.1 范围声明）+ 本 testplan K18–K20 代表场景、E4、E4-formats、E13–E18 |
| 6. 断流分类 | J1-J4 |
| 7. OPEN 下双入口 503 契约 + Retry-After + HALF_OPEN 省略 | D1-run、D1-stream、D3、D4、D5 |
| 8. 拒收零副作用 + OPEN 前任务继续 | D6、F5（在途控制面详细行为跑 FEAT-001/008/110 testplan） |
| 9. 静默期后只转 HALF_OPEN，真实探测前不 CLOSED | C2、C7、E1、G3 |
| 10. 半开竞争仅一个探针 | E1 |
| 11. 半开探针系统异常回 OPEN + 重算静默期 | C4 |
| 12. 半开真实任务完成 + 业务失败可 CLOSED + 窗口清空 | E2、E3 |
| 13. 半开 awaiting + 续跑回答唯一记账 | E6、E10 |
| 14. 半开取消等落定释放 | E7 |
| 15. 故障代际防迟到（旧任务先故障 vs CLOSED 先成立；探针取消后旧任务故障；过期事实不改写状态或代际） | C5、C6、C8、B7、E9 |
| 16. 启动空窗口 + 恢复清窗口 + 禁用态诊断 + 认证 + 不泄露敏感 | B8、G1-G8 |
| 17. 多实例独立 | H1-H4 |
| 18. OPEN/HALF_OPEN 下校验先于门 + 认领后资源拒绝释放 | F1、F2、F3、F4、F5、E8、E12 |
| 19. 非法配置启动失败指出键名 + 合法边界 | A3-A6、A7-A9、A10 |
| 20. 重启后新进程 CLOSED 空窗口 + 不得记为旧实例恢复 | I1、I2（监控告警关联由部署验收承接） |

## 附录 E｜§4 场景旅程 12 项 → testplan ID 对照

| §4 场景旅程 | 对应 ID |
|---|---|
| 小样本故障 | B1.1、B1.2 |
| 异常率超标 | B3、C1 |
| 重试成功 | **跑 FEAT-064 testplan**（重试策略非本 FEAT 职责） |
| 客户端断流 | J1、J2 |
| 在途任务收尾 | F5（冒烟）+ 相关 FEAT testplan |
| 静默期结束 | C2 |
| 半开并发竞争 | E1.1、E1.2 |
| 探针待答 | E6 |
| 探针业务失败 | E3 |
| 旧任务故障竞争 | C5 |
| 探针取消 | E7 |
| 多实例部署 | H1-H4 |

## 附录 F｜本 testplan 应用的 D1 洞察要点

1. **8 列场景矩阵**（ID / level / required_capability / 场景 / 前置 / 步骤 / 期望 / Fixture）——参考 D1 §6b
2. **ID 规范**（`FEAT-112-{domain}{seq}[-{variant}][.{point}]`）——参考 D1 §6c
3. **域前缀 A-K**，每 FEAT 文件头在 YAML frontmatter 列出含义——本文 §2 和 §5 分组
4. **无契约可选能力**：`required_capabilities` 为空，部署配置写在前置列，不要求 demo 对配置键表态——参考 D1 v4.1 §8
5. **6 态结果 + reason_code**（PASS / FAIL(5 类) / WARN / EXCLUDED / UNSUPPORTED / NOT_OBSERVED(1 类)）——参考 D1 v4.1 §9b
6. **显式 OUT + 机理 + reason_code**（见 §2 非范围表）——参考 D1 §5 治理栈 L1 层的"原文保真 + 显式排除"
7. **三件绑死稳定 ID 作为连接键**（testplan ID ↔ 判据代码 `@DisplayName` ↔ 违规样本 fixture）——参考 D1 §15 L5-a
8. **前置与激活证据**（场景未激活记 FAIL/conditional_not_triggered，结论无效）——参考 D1 v4.1 §9b
9. **CI 四道一致性检查**（静态配置 / wire 探测 / ID round-trip / 月度破坏演练）——参考 D1 §8d，本 testplan 由 CI 运行时承接
10. **合法不判定必须有主动行为**：EXCLUDED 只用于 `level=OUT`；三面都观测不到的 MUST 不判定，登记为覆盖缺口并挂 Issue，不以 NOT_OBSERVED 兜底——参考 D1 v4.1 §9b
