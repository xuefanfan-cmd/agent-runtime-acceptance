---
scope: v1130
module: deepanalyze-java + agent-runtime-ext-java
deployable_units: [agent-runtime-ext-java, deepanalyze-java]
sut: deepanalyze-agent（真实 DA 部署，双入口 `/run` + `/run-stream` + `/v1/health/detail`，真实 LLM，可注入故障）
features: [FEAT-112]

# 版本锚（跟 solution 仓 release 对齐；对齐 00-testplan-template.md §3）
since_solution_version: 0.1.4
last_modified_solution_version: 0.1.4
compatibility:
  agent-core-java: ">=0.1.16"
  agent-runtime-java: ">=0.1.3"
  agent-runtime-ext-java: "0.1.2+"
historical_regressions: []   # 首发无前置；升级时追加（见 00-testplan-template.md §3.2）

# 跨特性依赖（粒度自适应：被依赖 FEAT testplan 规范化后升级为 ID 级；见 00-testplan-template.md §2）
# §3.1 跨特性依赖表与此字段同步演进——详细使用说明在 §3.1 展开
# ⚠️ 当前所有 upstream FEAT 的 testplan 在 develop/04-testplan 下尚未就绪（[upstream_not_ready]）
#    —— CI Job E 影响分析暂退回到 FEAT 级粒度，不触发具体 ID 回归；就绪后升级到 ID 级
depends_on:
  - FEAT-054   # DA 服务入口与健康面接线 [upstream_not_ready]
  - FEAT-109   # 结构化护栏拦截事实（分类判据输入）[upstream_not_ready]
  - FEAT-110   # /resume 续跑入口 [upstream_not_ready]
  - FEAT-111   # 任务终态分类矩阵（K-rep 代表走它）[upstream_not_ready]
  - FEAT-001   # A2A 控制面 F-reg 冒烟保护 [upstream_not_ready]
  - FEAT-008   # 用户交互入口 F-reg 冒烟保护 [upstream_not_ready]
  - FEAT-064   # 重试策略（附录 B「重试成功」场景归属）[upstream_not_ready]

updated: 2026-11-11
# 本 testplan 按 D1 洞察定的格式（8 列场景矩阵 + level 列 + 域前缀 ID + 6 态结果 + required_capabilities 反查）落地
# 应用参考：D1 §6/§6b/§6c（L1 测试语言化）/ §6c2（ID 的 4 层用途）/ §8/§8b/§8c/§8d（L2 能力门 + SIT 反查 + CI 一致性）/ §9b（6 态 + reason_code）/ §14（L4 跨版本连续性）
# ⚠️ agent-card 承载边界见 D1 §8 lede：A2A 协议 4 项走 agent-card.yaml；平台特性能力承载机制未解决，候选为各 FEAT 诊断面

# demo 侧必须对以下配置项显式表态（SIT 启动前 schema 反查用）
# 参考 D1 §8c 启动前 schema 覆盖反查
required_capabilities:
  - degradation-gate.enabled                 # 必填布尔
  - degradation-gate.window                  # 必填时长
  - degradation-gate.min-sample-size         # 必填正整数
  - degradation-gate.failure-rate-threshold  # 必填 (0,1]
  - degradation-gate.open-quiet-period       # 必填 ≥0 时长
  - service-auth.enabled                     # 必填布尔（健康面认证开关）
  - media-whitelist                          # 必填清单（校验顺序前置需要）

# 测试过程中结果判定态（参考 D1 §9b v2.5）
verdict_states: [PASS, FAIL, WARN, EXCLUDED, UNSUPPORTED, NOT_OBSERVED]
---

# FEAT-112 任务异常率降级门｜testplan

本次契约基线：Technical-AF/docs [PR 498](https://gitcode.com/Technical-AF/docs/pull/498)，设计提交 `05af9cc3796ac953aee157e9c0b35621552232f4`（已合入 main）。特性档 §5.1.4 已正式规定过期豁免，B7/E9 不再是待裁决分歧；设计修订不代表测试实现或执行通过。

## 1. 测试目标

验证 deepanalyze-java 任务级异常自动降级门的全部外部可观察行为：**滑动窗口异常率 + 最小样本量 + 真实任务半开探针 + 故障代际**。SIT 作为裸 A2A / HTTP 调用方，通过公开面（`/run`、`/run-stream`、`/v1/health/detail`、在途控制面）观察 SUT 行为，不读内部状态、不依赖强制开门端点、不合成假探针，通过**公开配置缩短窗口和静默期 + 真实故障注入**制造测试条件。

本 testplan 对齐 FEAT-112 §2 的 20 项验收出口、FEAT-112 §4 的 12 个场景旅程，**保证无损覆盖**。L2 §1.3 列的 11 项当前实现差距，testplan 中以**反例/负向场景**形式显式覆盖。

### 1.1 范围严格对齐本特性验收（D1 §8e 原则）

本 testplan **只测 FEAT-112 自己承诺的能力**；以下场景**不在本 testplan 范围**，由相关 FEAT 的 testplan 承接（**不重写用例，跑相关 FEAT 的 testplan**）：

| 不属于 FEAT-112 的能力 | 归属 | SIT 验证方式 |
|---|---|---|
| **结构化任务终态分类矩阵**（原 K 组 K1-K17 九态分类覆盖）| FEAT-111 "轨迹反思与成本追踪" 共享的 `TerminalClass` 分类机制 + agent-runtime-ext-java 内部 | **跑 FEAT-111 的 testplan** 验证分类正确；本 FEAT 只以**少数代表场景**（见 §5 K 组保留的 K-rep）验证"**降级门确实消费了该分类**" |
| **OPEN 下 /status、/cancel、/inject、/answer、/resume 在途控制面不阻断**（原 F6-F12）| 各入口由 FEAT-001 / FEAT-008 / FEAT-110 分别定义 | **跑对应 FEAT 的 testplan** 作回归保护；本 FEAT 以 §5 F-reg 一条冒烟保护代表（验证 OPEN 不改写控制面语义） |
| **配置项值校验细节**（原 A3-A6 非法阈值的 threshold=0/1.5/NaN 等三类非法值） | 开发侧单元测试 `DegradationGatePropertiesTest`（L2 §7.4 已列） | 单元测试承接；本 testplan 以 §5 A 组保留 1 条代表场景 |
| **窗口数据结构内部行为**（原 B6 乱序插入——UT 范畴） | 开发侧单元测试 `DegradationGateTest`（L2 §7.4 已列） | 单元测试承接；本 testplan 不含 |
| **B7「过期事实不改代际」** | **本 testplan 保留**（关键状态机契约）| 按特性档 §5.1.2/§5.1.4 的正式过期豁免验收；采用测试仓独立受控时间/事实载体验证，不以开发用例代替验收 |

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
| `develop/02-features/FEAT-112-task-degradation-gate.md`（2026-11-05 版，scope=v1130） | §2 能力表、§3 外部接口、§4 场景与用户旅程、§5 行为语义与边界、§6 下游约束——本 testplan 全部断言的唯一契约基线 |
| `develop/03-architecture/L2-Low-Level-Design/deepanalyze/FEAT-112-task-degradation-gate.md`（2026-11-09 版） | 当前实现状态、代码类/行号指针（基准 `agent-solution@origin/common` 4e952c23）、分类矩阵实现顺序（§3.5）、测试资产清单（§7.4） |
| `develop/02-features/FEAT-054-deepanalyze-host-agent.md` | DA 服务入口、健康面接线、稳定错误码 181012 上下文过长事实基线 |
| `develop/02-features/FEAT-109-dangerous-operation-guardrail-and-hooks.md` | 结构化护栏拦截/用户拒绝事实前置（用于 GUARDRAIL_DENIED 判定） |
| `develop/02-features/FEAT-110-task-checkpoint-resume.md` | `/resume` 续跑入口前置 |
| `develop/02-features/FEAT-111-trajectory-reflection-and-cost-tracking.md` | 共享 agent-runtime-ext-java 任务终态分类 |
| 代码 PR #1030、#1065（本特性变更） | 实现落地证据（已 merge 到 common 分支） |

### 3.1 FEAT-112 依赖的 FEAT 清单（跨特性『跑而不写』归属表）

对齐 D1 §8e 原则：**testplan 对齐本特性验收，不属于本 FEAT 的能力跑相关 FEAT 的 testplan，不重写**。本节一次性列明 FEAT-112 的所有跨特性依赖，以及每类依赖对应的 testplan。

**粒度自适应**：本表当前粒度为 **FEAT 级**（`FEAT-NNN`），因为被依赖 FEAT 的 testplan 尚未全面按 D1 §6c 规范化（没有稳定 §5 ID 矩阵）。待对方规范化后，本表"依赖 FEAT"列中具体引用项将细化为 **ID 级**（`FEAT-NNN-xxx`，如 `FEAT-111-K1`）——frontmatter `depends_on` 字段与本表同步演进（对齐 D1 §6c2 ID 的 4 层用途：第 ③ 层跨 FEAT 关联键 + §8d Job E 影响分析）。

**依赖就绪状态 `[upstream_not_ready]`**：下列所有 upstream FEAT 当前在 `develop/04-testplan/` 下**均无对应 testplan 文件**——评审人指出"跨特性执行依赖未闭环"（PR #483 评审问题 7）。本 testplan 的"跑而不写"策略在对方 testplan 就绪前**暂不可执行**；CI Job E 影响分析暂退回到 FEAT 级粒度（粗粒度回退）。实际 SIT 执行时，本 testplan 自身的 §5 矩阵仍可独立跑（降级门自身契约）；"跑 FEAT-xxx testplan"类委托属于**待后续批次补齐**。

**前置依赖**（frontmatter `depends_on` 字段）：

| 依赖 FEAT | FEAT-112 依赖它的什么 | 本 testplan 承接范围 | 跑相关 FEAT testplan 承接范围 |
|---|---|---|---|
| **FEAT-054** deepanalyze-host-agent | DA 服务入口（`/run` / `/run-stream`）、健康面接线、稳定错误码 **181012**（上下文过长事实基线） | D 组双入口拒收契约 / G 组诊断面（**FEAT-112 自己的新增行为**：503 契约、诊断面 degradationGate 块） | `/run` / `/run-stream` **入口本身的完整语义**（参数校验、taskId 注册、SSE 契约）→ 跑 FEAT-054 testplan |
| **FEAT-109** dangerous-operation-guardrail-and-hooks | 结构化护栏拦截/用户拒绝事实（用于 `GUARDRAIL_DENIED` 分类判定） | 无（本 testplan 不直接测护栏，分类走 K-rep） | 护栏拦截的详细语义、denied 证据结构、因果边界 → 跑 FEAT-109 testplan |
| **FEAT-110** task-checkpoint-resume | `/resume` 续跑入口；探针 awaiting → 续跑的最终终态唯一记账（E10 原本覆盖此） | E6 探针 awaiting 释放名额（**FEAT-112 契约**）；E10 续跑唯一记账已剔除（分类层走 FEAT-111） | `/resume` 入口本身的续跑语义、checkpoint 恢复 → 跑 FEAT-110 testplan |
| **FEAT-111** trajectory-reflection-and-cost-tracking | 共享 agent-runtime-ext-java 的 `TerminalClass` 结构化任务终态分类机制 | K-rep1/2/3 代表场景（**验证降级门消费分类结果**） | **九态分类矩阵全覆盖**（原 K1-K17）、分类因果规则、denied 证据的归类边界 → 跑 FEAT-111 testplan |

**在途控制面依赖**（本 FEAT 保护"不改写既有行为"，不验证控制面本身）：

| 依赖 FEAT | FEAT-112 依赖它的什么 | 本 testplan 承接范围 | 跑相关 FEAT testplan 承接范围 |
|---|---|---|---|
| **FEAT-001** standardized-agent-service-entrypoint | A2A 协议层入口 `/status`、`/task`、`/tasks`、`/stream` 的既有语义 | F-reg 冒烟（**只验证 OPEN 下这些端点仍 200，不验证语义正确**）| `/status` / `/task` / `/tasks` / `/stream` 的**完整响应语义**（快照字段、状态序列、SSE 契约、重连回放）→ 跑 FEAT-001 testplan |
| **FEAT-008** user-interaction-interrupt-and-response | 用户交互入口 `/inject`、`/answer`、`/message` 的既有语义 | F-reg 冒烟 | `/inject` / `/answer` / `/message` 的**完整交互语义**（awaiting 转换、权限确认、事件流）→ 跑 FEAT-008 testplan |

**不在本 testplan 范围的能力**（已在 §1.1 列出剔除项，重新索引到归属 testplan）：

| 剔除的场景 | 由哪个 testplan 承接 |
|---|---|
| 原 K 组 K1-K17 九态分类矩阵全覆盖 | **FEAT-111 testplan** 分类矩阵测试 |
| 原 F6 / F7-F8 `/status` / `/task` / `/stream` 不被阻断的完整语义 | **FEAT-001 testplan** 控制面入口测试 |
| 原 F9-F11 `/cancel` / `/inject` / `/answer` 不被阻断的完整语义 | **FEAT-008 testplan** 用户交互测试 |
| 原 F12 `/resume` 不被阻断的完整语义 | **FEAT-110 testplan** 续跑入口测试 |
| 原 A3-A6 配置项非法值校验（threshold=0 / 1.5 / NaN、window≤0、min=0、quiet<0） | 开发侧单元测试 `DegradationGatePropertiesTest`（L2 §7.4） |
| 原 B6/B7 窗口数据结构内部（乱序插入、过期事实不改代际） | 开发侧单元测试 `DegradationGateTest`（L2 §7.4） |

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

**依赖冲突处置**：如果 FEAT-111 分类机制 testplan 失败 → **先停跑本 testplan**（因为 K-rep 代表场景假设分类正确；分类错了本 FEAT 测不出真问题）→ 修复 FEAT-111 后再跑 FEAT-112。

## 4. 部署拓扑

### 4.1 整体拓扑

```text
SIT test driver (A2A SDK client + 底层 HttpClient)
  --/run + /run-stream--> deepanalyze-agent (DA 真实部署，instanceId=deepanalyze-agent-xxxxxx)
                            |
                            |-- LLM endpoint (可切换：真实 LLM 或 LlmFaultInjectionStub)
                            |-- SutStack 控制进程启停
                            |-- 服务间认证 X-Engine-Secret（可选启用）

  --/v1/health/detail--> 同一实例（三态诊断，带服务间认证）

  --既有控制面 (/status /cancel /inject /answer /resume 等，DA 已实现)-->
      同一实例（F-reg 冒烟验证：OPEN 下不受降级门阻断——FEAT-112 §2「只拦新增负担」契约）

多实例拓扑（H 组用）：
  deepanalyze-agent-A（instanceId=A，独立 LlmFaultInjectionStub）
  deepanalyze-agent-B（instanceId=B，独立 LlmFaultInjectionStub）

配置缩短窗口与静默期用于加速测试：
  openjiuwen.service.degradation-gate:
    window: 30s           # 原默认 5m
    open-quiet-period: 10s # 原默认 2m
```

> **注**：FEAT-112 验证的所有场景（含 K-rep 分类 / C 代际 / E 探针 / J1/J2 断流 / H 多实例 / I 重启）都能通过 **LLM 层故障注入**驱动。不需要下游 agent Mock 或 callback receiver Mock——见 §4.2 "为什么只需要一个 stub"。

### 4.2 Stub 规格清单（按 D1 §8f Fixture 三类划分）

**原则**（D1 §8f）：
- 真实 LLM 不会返回故障——故障必须经 stub 构造
- Stub 类 Fixture 必须 **测试组 demo / stub 开发者**开发（不是开发组），因为 stub 深度嵌入 demo 依赖链，需要 demo 提供配置切换机制
- testplan 作者负责**规格 + 协同**（告诉测试组内 demo/stub 开发者要开发哪些 stub、切换点在哪、testing API 长什么样）

本 FEAT 需要的 **Mock / Stub 类 Fixture**——**只需要一个**：

```yaml
stubs:
  - name: LlmFaultInjectionStub
    purpose: 构造 LLM 层故障（5xx / 超时 / 认证失败 / 配额不足）—— 驱动 FEAT-112 所有系统异常场景
    demo_switch: `openjiuwen.llm.endpoint`（通过 `DA_MODEL_BASE_URL` 环境变量覆盖，DA 已支持）
    testing_api:
      - setNextResponse(httpCode, delay)           # 下一次调用返回指定 http code
      - setNextError(errorCode)                    # 返回 DA 结构化错误码：ENGINE_MODEL_ERROR / ENGINE_TIMEOUT / ENGINE_AUTH_FAILED / ENGINE_QUOTA_EXCEEDED / ENGINE_RATE_LIMITED / ENGINE_CONTEXT_TOO_LONG
      # setPattern 的 responses[] 的每一项 body 是**任意合法 JSON**——不限于故障响应：
      #   - 故障序列（重试场景）：[{"code":500}, {"code":500}, {"code":200,"body":"..."}]
      #   - 合法工具调用（驱动 E3 BUSINESS_FAILURE）：
      #       [{"code":200, "body":"{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"sql_query\",\"arguments\":\"SELECT * FROM orders WHERE id=999\"}}]}}]}"}]
      #   - 危险工具调用（驱动 E5 GUARDRAIL_DENIED）：
      #       [{"code":200, "body":"{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"shell_exec\",\"arguments\":\"rm -rf /\"}}]}}]}"}]
      # 实际 body 需符合 SUT 所用 LLM provider 的响应 schema（OpenAI chat completion / Anthropic messages / ...）
      - setPattern(responses[])                    # 预设响应序列，body 支持任意合法 LLM 响应（故障/正常/工具调用/危险工具）
      - reset()                                    # 回到默认通过
    owner: 测试组 demo / stub 开发者（见 D1 §8f；开发组不参与）
    implementation_ref: >
      solution 仓 `common/example/task-degradation-gate-acceptance-demo/degradation-demo-common/`
      ——testplan 只声明规格，开发进度由 solution 仓代码和 issue 跟踪，不在本文管理。
```

**为什么只需要一个 stub**（关键认识）：

FEAT-112 验证所有场景的**触发点都在 LLM 层**：

| 场景组 | 驱动方式 |
|---|---|
| K-rep1/2/3 分类矩阵代表 | LlmFaultInjectionStub 注入错误码 |
| C 组状态机 + 代际防迟到 | LlmFaultInjectionStub 注入错误切换驱动窗口状态 |
| E 组探针闭环 | LlmFaultInjectionStub reset / 注入切换探针终态 |
| J1/J2 断流（传输层）| 测试驱动端 close SSE connection（无需下游 Mock）|
| H 组多实例 | 两个 SUT 实例各指向一个 LlmFaultInjectionStub 端口 |
| I 组进程重启 | `SutStack` 控制（Fixture 类，不是 Stub）|

**不需要的 Mock**（曾经计划但剔除）：
- `MockSearchAgent`：下游 agent 层故障可通过 LLM 层故障注入等效触发——`TerminalClass=INFRASTRUCTURE_FAILURE` 不区分来源
- `MockCallbackReceiver`：FEAT-112 不是 push notifications 特性，没有 callback 场景验证
- `CredentialSpoofer`：401/403 通过 `LlmFaultInjectionStub.setNextError(ENGINE_AUTH_FAILED)` 即可

### 4.3 环境控制类 + 协议/数据驱动类 Fixture（基础设施团队维护）

| Fixture | 类别 | 作用 |
|---|---|---|
| `SutStack` | 环境控制 | 启停 DA jar、注入 env、重启验收（I 组用） |
| `A2aHttpProbe` | 协议驱动 | 底层 HttpClient 发 JSON-RPC payload，绕过 SDK 做 wire 级断言 |
| `A2aServiceClient` | 协议驱动 | A2A SDK 驱动 blocking/streaming/查询 |
| `A2aEventCollector` | 协议驱动 | 收集 SSE 事件 / 等待终态 / 抽取 artifact |
| `DegradationHealthProbe`（新） | 协议驱动 | 统一封装 `GET /v1/health/detail` 带 `X-Engine-Secret`；G 组专用 | 

### 4.4 边界要求

- SIT 只通过公开面观察，不读 SUT 内部状态、日志或存储
- 不使用强制开关门端点（本特性不提供）；不合成假探针
- 通过 `SutStack` 控制进程启停以制造重启语义测试（I 组）
- **故障注入必走 stub**——真实 LLM 不会按需返错，所有故障场景必须用 `LlmFaultInjectionStub`（本 FEAT 唯一 stub）
- Stub 的测试 API 调用由 SIT test driver 发起，不污染 SUT 配置
- 服务间认证 `X-Engine-Secret` 启用后，测试必须携带有效 token
- J1/J2 **客户端断流**通过测试驱动端主动 close SSE connection 实现，不需要下游 Mock

### 4.5 开发优先级与协同对齐（D1 §8e / §8f —— 测试组内部分工）

**分工澄清**（D1 §8e）：开发组**只产出 agent runtime**（FEAT-112 降级门机制本身）；**demo / stub / Fixture / 测试代码 / CI 全部由测试组内部开发**。testplan 作者的 stub 规格清单（§4.2）是**测试组内部的协同契约**，**不是**向开发组提的需求。

**testplan 作者给测试组内部的清单**：

| 动作 | 内容 | 对齐角色 |
|---|---|---|
| ① 识别 | 本 FEAT 全场景只需 1 个 stub（LlmFaultInjectionStub）—— LLM 层故障注入即可驱动 FEAT-112 所有系统异常场景；见 §4.2 的"为什么只需要一个 stub" | testplan 作者自识别 |
| ② 规格 | §4.2 已明确 LlmFaultInjectionStub 的 purpose / demo_switch / testing_api / owner（全是测试组内角色） | testplan 作者产出 |
| ③ 对齐 | **本 testplan 定稿前**，和**测试组内 demo 开发者** confirm：`LlmFaultInjectionStub` 的 demo_switch（demo agent 侧通过 `DA_MODEL_BASE_URL` 环境变量将 DA 的模型 endpoint 指向 stub） | 测试组内 demo / stub 开发者 |
| ④ 场景矩阵标注 | §5 场景矩阵的 Fixture 列，用到 stub 时加 `[stub]` 前缀；CI 阶段检查 stub 可用性 | 测试组 CI 维护者 |
- 服务间认证 `X-Engine-Secret` 启用后，测试必须携带有效 token

## 5. 测试场景矩阵

**ID 规范**（参考 D1 §6c）：`FEAT-112-{domain}{seq}[-{variant}][.{point}]`

- domain（大写字母）：A 配置 / B 窗口与分类 / C 状态机与故障代际 / D 双入口拒收 / E 探针闭环 / F 校验顺序与在途控制面 / G 健康诊断面 / H 多实例 / I 进程重启 / J 传输层 / K 分类矩阵

### 5.1 A 组｜配置与装配

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| A1 | MUST | degradation-gate.enabled | ext 默认关闭不产生降级 | ext 单独装配，无 DA 补默认 | 连续发起系统异常任务 | 诊断 `enabled=false`，`/run` 不返 503 | `DegradationGatePropertiesTest`、`DaDegradationDeliveryDefaultTest` |
| A2 | MUST | degradation-gate.enabled | DA 交付默认开启 | DA 自动装配，`degradation-gate` 键缺席 | 启动并访问诊断 | `enabled=true`，`state=CLOSED` | `DaDegradationDeliveryDefaultTest` |
| A7 | MUST | degradation-gate.min-sample-size | 合法边界 `min-sample-size=1` | 设 `min-sample-size: 1` | 启动 + 单条系统异常 | 启动成功；第 1 条异常达到阈值即 CLOSED→OPEN | `DegradationGateTest` |
| A8 | MUST | degradation-gate.failure-rate-threshold | 合法边界 `failure-rate-threshold=1.0` | 设阈值为 1.0 | 启动 + 构造 100% 异常率 | 100% 异常率才开门；99% 不开 | `DegradationGateTest` |
| A9 | MUST | degradation-gate.open-quiet-period | 合法边界 `open-quiet-period=0` | 设为 0s | OPEN 状态下立刻查诊断 | 下一次访问可进 HALF_OPEN（不需等待） | `DegradationGateTest` |
| A10 | SHOULD | degradation-gate.enabled | 畸形 enabled 布尔绑定启动失败 | 设 `enabled: yesnot`（非法字符串） | 启动 DA | 启动失败，错误包含键名 | **补齐测试**（L2 §7.1 指出未单独覆盖） |

> **注**：原 A3/A4/A5.1/A5.2/A5.3/A6（非法配置值启动失败：window=0、min-sample=0、threshold=0/1.5/NaN、quiet=-1s）**已移至 UT**（`DegradationGatePropertiesTest`），对齐 §1.1 剔除项与评审人问题 6（拆 UT/集成/SIT 层级）。

### 5.2 B 组｜窗口与分类

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| B1.1 | MUST | degradation-gate.min-sample-size | 最小样本 20 — 第 19 个异常**不拒新** | 配置 `min-sample-size: 20`，窗口清空 | 连续发 19 条系统异常任务 | 第 19 条记账完成后 `state=CLOSED`；第 20 条新任务不被拒 | `DegradationGateTest`、`DaDegradationAdmissionTest` |
| B1.2 | MUST | degradation-gate.min-sample-size | 第 20 个有效异常**记账达标** | 接 B1.1，第 20 条正在发送 | 第 20 条系统异常任务完成 | 第 20 条记账后，诊断显示 `sampleCount=20`、`systemFailureCount=20`、`systemFailureRate=1.0`，`state=OPEN` | 同上 |
| B1.3 | MUST | degradation-gate.min-sample-size | 阈值达标后第 21 个**新任务被拒** | 接 B1.2 | 发第 21 条新任务 | 返回 HTTP 503 `ENGINE_DEGRADED` | 同上 |
| B2 | MUST | degradation-gate.failure-rate-threshold | 异常率 25%（10/40）不开门 | 配置默认阈值 0.50 | 构造 10 系统异常 + 30 正常终态 | `systemFailureRate=0.25`，`state=CLOSED` | `DegradationGateTest` |
| B3 | MUST | degradation-gate.failure-rate-threshold | 异常率 50%（10/20）开门 | 默认阈值 0.50 | 构造 10 系统异常 + 10 正常终态 | `state=OPEN` | `DegradationGateTest` |
| B4 | MUST | degradation-gate.window | 窗口过期剪枝 | 缩短 `window: 30s` | 构造足量异常 → OPEN → 等 30s+ 窗口过期 | 旧样本从快照中剪枝（`sampleCount` 下降）；但 `state` 不自动 CLOSED | `DegradationGateTest` |
| B5 | MUST | degradation-gate.window | **残窗双阈值达标自动开门**（反例补齐） | 窗口曾有足量样本比例未达标 | 健康样本随时间过期，准入或诊断推进 | 残窗若同时满足样本量 + 比例则 OPEN（不只是"新故障到达"才评估） | **补齐测试**（L2 §7.1 指出需补） |
| B7 | MUST | degradation-gate.window | 过期事实豁免（特性档 §5.1.2/§5.1.4） | 通过受控时间与事实载体建立单调 windowAnchor，固定观察时间；分别建立 OPEN 与 HALF_OPEN 有在途探针的前置 | 注入 terminalAt 小于及等于 windowAnchor-window 的系统异常事实；以刚大于下界的未过期事实作对照 | 过期事实不增加样本、不递增故障代际、不改变最近故障时间，不因该事实迁移门状态；旧任务过期事实不否决当前探针；正常静默期推进不被禁止；未过期系统异常仍按故障契约处理 | 测试仓组件/集成受控时间与事实载体；公开诊断不提供代际，不能伪造 HTTP 字段 |
| B8 | MUST | （内部） | 空窗口 `systemFailureRate=0`，禁 NaN | DA 刚启动，无样本 | 查询诊断 | `sampleCount=0`、`systemFailureCount=0`、`systemFailureRate=0`（不是 NaN） | `DaHealthDetailE2ETest` |

> **注**：原 B6（窗口内乱序样本有序插入）**已移至 UT**（`DegradationGateTest`，纯数据结构内部），对齐 §1.1 剔除项。

### 5.3 C 组｜状态机与故障代际

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| C1 | MUST | （内部） | CLOSED → OPEN 条件（样本 + 阈值同时满足） | `min-sample=20`、`threshold=0.5` | 构造 20 条样本中 10 条系统异常 | 第 20 条记账后 `state=OPEN` | `DegradationGateTest` |
| C2 | MUST | degradation-gate.open-quiet-period | OPEN → HALF_OPEN，首个有效新任务正常受理（特性档 §5.1.6） | 静默期已满，无探针在途；控制获准任务保持执行中 | 发起有效真实任务，在其仍在途时查询健康诊断并发起竞争请求 | 首个任务按原任务接口契约被受理并进入真实执行链；在途诊断为 HALF_OPEN、probeInFlight=true；重叠竞争请求返回503。成功响应不要求 claimId 或门状态，不暴露探针身份；任务最终结果仍按原契约返回 | 真实任务/执行依赖/健康诊断与重叠时序证据；对应 E1.1、E1.2 |
| C3 | MUST | （内部） | HALF_OPEN → CLOSED（探针健康 + 代际未变） | HALF_OPEN，探针已 claim | 探针任务正常完成 | `state=CLOSED`，窗口清空 | `DegradationGateTest` |
| C4 | MUST | （内部） | HALF_OPEN 任一受理任务系统异常 → OPEN + 递增代际 + 重算静默期 | HALF_OPEN，探针在途 | 探针任务系统异常终态 | `state=OPEN`；`failureGeneration` 递增；静默期重新计时 | `DegradationGateTest` |
| C5 | MUST | （内部） | **故障代际防迟到**：旧任务故障先记账则回 OPEN，迟到探针成功不覆盖 | HALF_OPEN，探针 + 旧 CLOSED 期在途任务并存 | 旧任务先系统失败（计入代际）→ 探针后成功 | `state=OPEN`（旧故障已递增代际）；迟到探针成功不能 CLOSED | `DegradationGateTest` |
| C6 | MUST | （内部） | 故障代际防迟到：CLOSED 先成立后，新故障按正常 CLOSED 窗口记账 | HALF_OPEN 探针健康成功后 CLOSED 已成立 | 后续故障到达 | 按 CLOSED 窗口正常记账，不追溯改写已完成迁移 | `DegradationGateTest` |
| C7 | MUST | degradation-gate.window | 旧样本过期不自动 CLOSED | OPEN 状态持续 | 等待窗口过期（样本全部过期） | `state=OPEN` 保持（不自动回 CLOSED） | `DegradationGateTest`、L2 §7.2 "OPEN 无流量" |

### 5.4 D 组｜双入口拒收

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| D1a | MUST | （内部） | `/run` OPEN 返回 503 `ENGINE_DEGRADED` | OPEN | POST `/api/agents/run` | HTTP 503，body 含 `code=ENGINE_DEGRADED`、`degraded=true`、`retryable=true`、`state=OPEN`、`instanceId`、`retryAfterSeconds`、`error="Agent is temporarily not accepting new tasks"` | `DaDegradationAdmissionTest` |
| D1b | MUST | （内部） | `/run-stream` OPEN SSE 建立前返回 JSON（非 200 假 SSE） | OPEN | POST `/api/agents/run-stream` | HTTP 503 JSON；**不先 200 再 SSE**；无 `start`/`error`/`done` 假任务流；JSON body 字段与 D1a 完全一致 | 同上 |
| D3 | MUST | （内部） | OPEN 返回 `Retry-After` 与 `retryAfterSeconds` 一致 | OPEN，剩余静默 87s | 请求头比对 | `Retry-After: 87` 与 body `retryAfterSeconds=87` 相同（同向上取整秒数） | 同上 |
| D4 | MUST | （内部） | HALF_OPEN 省略 `retryAfterSeconds` 和 `Retry-After` | HALF_OPEN，探针已占用 | 请求 `/run` | body 不含 `retryAfterSeconds` 字段；response header 无 `Retry-After` | 同上 |
| D5 | MUST | （内部） | 503 带 `state` + `instanceId` 两字段 | OPEN 或 HALF_OPEN | 读 response body | 两字段非空且语义正确 | 同上 |
| D6 | MUST | （内部） | 拒收不创建 taskId、任务记录、start 事件、不进入窗口 | OPEN | `/run` 被拒后查询任务列表 | 任务列表无新增；执行链无调用；窗口 `sampleCount` 不变 | 同上 |

### 5.5 E 组｜探针闭环

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| E1.1 | MUST | （内部） | 半开唯一探针，恰一真实任务执行 | HALF_OPEN，无探针在途；获准任务保持执行中直到竞争请求完成 | 同时发5条有效新任务，记录重叠区间 | 恰1条正常受理并进入真实执行链，任务按原接口契约出现在任务列表；健康诊断 probeInFlight=true，其余503；不要求公开响应包含 claimId 或探针身份 | 测试仓并发驱动、真实执行证据和健康诊断 |
| E1.2 | MUST | （内部） | 半开唯一探针 — **并发中其余 4 条被拒** | 同 E1.1 | 同 E1.1 | 其余 4 条返回 HTTP 503 `state=HALF_OPEN`；不进任务列表、不占探针 | 同上 |
| E2 | MUST | （内部） | 探针 `COMPLETED` 正常健康关门 | HALF_OPEN，探针 claim | 探针任务正常完成（最终 success） | `state=CLOSED`；窗口清空；后续任务正常接收 | `DegradationGateTest` |
| E3 | MUST | （内部） | 探针 `BUSINESS_FAILURE`（专用业务终态）健康关门 | HALF_OPEN | 探针任务 `DaBusinessOutcomeRail` 生成 `business_failure` | `state=CLOSED`；只计分母；后续任务正常 | `DegradationGateTest`、`DaBusinessOutcomeRail` |
| E4 | MUST | （内部） | **探针 `INPUT_REJECTED`（181012 上下文过长）健康关门**（补齐） | HALF_OPEN | 探针任务达到上下文上限，返回 ENGINE_CONTEXT_TOO_LONG | `state=CLOSED`；只计分母 | **补齐测试**（L2 §7.1 指出只有记账属性证据，关门需补） |
| E5 | MUST | （内部） | **探针 `GUARDRAIL_DENIED` 健康关门**（补齐） | HALF_OPEN | 探针任务触发护栏拦截 | `state=CLOSED`；只计分母 | **补齐测试**（L2 §7.1 指出需补） |
| E6 | MUST | （内部） | 探针 `awaiting_answer` → CLOSED 释放名额不记样本 | HALF_OPEN，探针代际未变 | 探针任务进入 awaiting | `state=CLOSED`；窗口清空；**不记样本**；名额释放 | `DegradationGateTest` |
| E7 | MUST | （内部） | 探针 `USER_CANCELLED` — 取消实际落定才释放，保持 HALF_OPEN | HALF_OPEN，探针在途 | 用户发 `/cancel` → 等落定 | 请求到达瞬间不提前放第二探针；落定后保持 HALF_OPEN、释放名额 | `DegradationGateTest`、`DaProbeSlotHandleTest` |
| E8 | MUST | （内部） | 探针 `NOT_ADMITTED`（执行池满/会话忙）落定后释放 | HALF_OPEN，探针 claim 后执行池饱和 | 探针被 pool_saturated 拒 | 保持 HALF_OPEN；名额通过事实流释放 | `DaDegradationAdmissionTest`、`DaProbeSlotHandleTest` |
| E9 | MUST | degradation-gate.window | 当前探针过期落定仅释放（特性档 §5.1.4/§5.1.6） | HALF_OPEN 且当前探针占用；受控单调水位使其 terminalAt 小于或等于 windowAnchor-window | 分别送达当前探针的过期系统失败与过期健康终态，并发起下一真实任务 | 不增加样本、不递增代际、不改变最近故障时间；保持 HALF_OPEN，只释放当前探针名额，允许下一真实探针；不得将过期健康结果解释为恢复而 CLOSED | 测试仓组件/集成受控事实与时间载体；不得以延迟模型响应代替过期事实 |
| E11 | MUST | （内部） | 认领后、绑定前异常窗口不泄漏 | HALF_OPEN | 构造 claim 后 bindProbeTask 前抛异常 | `DaProbeSlotHandle` 释放 claim；名额不泄漏 | `DaProbeSlotHandleTest` |

> **注**：原 E10（HALF_OPEN awaiting 续跑系统异常只记一次分子+分母）已剔除——"唯一记账点"契约在 §8 关键链路断言层统一验证，不在 §5 单独列场景；对齐 §3.1 剔除项与评审人问题 6。

### 5.6 F 组｜校验顺序与在途控制面回归冒烟

**范围收敛说明**（D1 §8e 原则）：OPEN 下 `/status`、`/cancel`、`/inject`、`/answer`、`/resume` 等**在途控制面本身的行为**由其所属 FEAT 的 testplan 承接（FEAT-001 控制面入口、FEAT-008 用户交互中断、FEAT-110 续跑）——本 FEAT 不重写。这里只保留 **「入站校验先于降级门」** 本特性契约 + **「OPEN 不改写控制面语义」** 一条冒烟保护。

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| F1 | MUST | service-auth.enabled | 入站认证失败先于降级门 | OPEN，认证启用 | 发无效 `X-Engine-Secret` 的 `/run` | 401 认证错误（不是 503 ENGINE_DEGRADED）；不占探针 | `DaServiceAuthFilter`、`DaDegradationAdmissionTest` |
| F2 | MUST | （内部） | 参数非法先于降级门 | OPEN | 发缺 `method` 字段的 `/run` | 400 invalid request；不占探针 | `DaDegradationAdmissionTest` |
| F3 | MUST | media-whitelist | 媒体校验（存在+白名单+team）先于降级门——4 项合并 | OPEN | 分别发：媒体不存在 / 白名单外 / team 不存在 / team 通道不可用 | 四次均为 4xx 既有校验错误；不占探针 | `DaDegradationAdmissionTest`（覆盖 4 项前置校验） |
| F-reg | SHOULD | （内部） | **冒烟保护**：OPEN 下在途控制面入口**保持各自原契约**（不被降级门改写） | OPEN；根据各入口原契约准备对应前置状态 | 分别调用：`/status`（活跃任务）、`/stream` 重连（活跃任务）、`/cancel`（活跃任务）、`/inject`（**awaiting-input 任务**）、`/answer`（**awaiting-input 任务**） | 每次响应的 HTTP 码等于不在 OPEN 时该入口对该前置状态**原本应返的码**（活跃任务 200、非活跃入口按 FEAT-001/008 原契约 404/4xx）；**关键**：响应 body 不含 `ENGINE_DEGRADED`、不含 `degraded=true`——即 OPEN 没把原契约改写为降级 503。**控制面本身行为的详细验证跑 FEAT-001/008/110 的 testplan**，本条只做"没被阻断"的冒烟 | `DaDegradationAdmissionTest`（断言 body 无 `ENGINE_DEGRADED`；响应码对齐原契约） |

### 5.7 G 组｜健康诊断面

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| G1 | MUST | （内部） | `/v1/health/detail` CLOSED 返 200 + healthy | CLOSED | GET `/v1/health/detail` | HTTP 200；`status=healthy`、`state=CLOSED`、无 `retryAfterSeconds` | `DaHealthDetailE2ETest` |
| G2 | MUST | （内部） | OPEN 返 200 + degraded + retryAfterSeconds | OPEN | GET | HTTP 200；`status=degraded`、`state=OPEN`、`retryAfterSeconds` 非空 | 同上 |
| G3 | MUST | （内部） | HALF_OPEN 返 200 + degraded + 无 retryAfterSeconds | HALF_OPEN | GET | HTTP 200；`status=degraded`、`state=HALF_OPEN`、**无 `retryAfterSeconds`** | `DaHealthDetailHalfOpenE2ETest` |
| G4 | MUST | degradation-gate.enabled | 禁用态返 healthy + enabled=false + state=CLOSED + 零计数 | `enabled=false` | GET | `enabled=false`、`state=CLOSED`、`sampleCount=0`、`probeInFlight=false` | `DaHealthDetailE2ETest` |
| G5a | MUST | service-auth.enabled | 认证启用时**无** `X-Engine-Secret` 返 401 | `deepanalyze.auth.enabled=true` | GET 无 header | 401 `{error:"unauthorized"}` | `DaServiceAuthFilter:63-92` |
| G5b | MUST | service-auth.enabled | 认证启用时**错误** `X-Engine-Secret` 返 401 | 同上 | GET 带错误 secret | 401 `{error:"unauthorized"}` | 同上 |
| G5c | MUST | service-auth.enabled | 认证启用时**对的** `X-Engine-Secret` 返 200 | 同上 | GET 带对的 secret | 200 + 诊断字段完整 | 同上 |
| G8 | MUST | （内部） | 诊断不泄露原始异常/用户输入/工具参数/密钥 | OPEN，窗口含真实异常 | GET + 字段检查 | 响应不含错误堆栈、prompt、tool args、secret 任一 | `DaHealthDetailE2ETest` |
| G9 | MUST | （内部） | `/health` 不因门状态变化 | OPEN 和 CLOSED 分别 | GET `/health` | 两次响应相同（门状态不影响） | `DaHealthController:103-117` |
| G10 | MUST | （内部） | `/ready` 不新增门状态 down 分量 | OPEN 和 CLOSED 分别 | GET `/ready` | 两次响应相同（原组件就绪不变） | 同上 |

### 5.8 H 组｜多实例隔离

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| H1 | MUST | （内部） | 两实例独立窗口（A OPEN B CLOSED） | 启两实例，仅向 A 注入故障 | 查 A、B 诊断 | A `state=OPEN`；B `state=CLOSED` | `DegradationGateTest`（同 JVM 独立对象）+ **真实多进程补齐**（L2 §7.1 指出不是真实多进程证明） |
| H2 | MUST | （内部） | A 的 503/诊断带 A 的 instanceId | A OPEN | 请求 A 的 `/run` 和诊断 | 503 body 和诊断响应均 `instanceId=A` | `DaDegradationAdmissionTest` |
| H3 | MUST | （内部） | A 的 CLOSED 不清除 B 的 D4 提示 | A CLOSED，B OPEN | 查 B 诊断 | B 仍 OPEN；A 的状态不影响 B | 同上 |

### 5.9 I 组｜进程重启语义

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| I1 | MUST | （内部） | 重启后新进程 CLOSED 空窗口初始化 | 旧进程 OPEN，部署监控保留原故障告警 | `SutStack` 停止 + 重启 + 启动事件 + GET 诊断 | 新 `instanceId`（和旧不同）、`state=CLOSED`、零计数、`probeInFlight=false`；**此结果只能记为"新进程初始化"，不得记为旧实例探针恢复**；监控仍保留原故障上下文 | `SutStack` + 诊断对比 + **补齐真实多进程启停**（L2 §7.1 指出不是真实多进程证明） |
| I2 | MUST | （内部） | 新进程不持久化旧窗口和探针状态 | 旧进程 OPEN，正在 HALF_OPEN 探针 | 重启 | 新进程无旧窗口样本、无旧探针 claim | 同 I1 |

### 5.10 J 组｜传输层

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| J1 | MUST | （内部） | 断流后任务最终成功只计分母 | 正常任务 | SSE 断开后任务后台继续成功 | 事实到达后：`sampleCount+1`、`systemFailureCount` 不变 | `DaDegradationAdmissionTest` |
| J2 | MUST | （内部） | 断流后任务最终系统失败计分子+分母 | 任务预期系统异常 | SSE 断开后任务最终失败 | `sampleCount+1`、`systemFailureCount+1` | 同上 |
| J3 | MUST | （内部） | SSE 重连不新增样本 | 已受理任务在途 | 客户端 `SubscribeToTask` 多次 | 无额外样本；窗口 `sampleCount` 不变 | 同上 |
| J4 | MUST | （内部） | **慢消费者不改变任务终态分类**（补齐） | 任务执行中，客户端消费慢 | 客户端缓冲堵塞；任务后台继续 | 任务最终按真实终态分类；慢消费者不独立记账 | **补齐测试**（L2 §7.1 指出需补聚焦） |

### 5.11 K 组｜降级门消费分类结果的代表性验证（收敛后）

**范围收敛说明**（D1 §8e 原则）：**九态 `TerminalClass` 结构化分类机制本身**是 agent-runtime-ext-java 的共享能力，由 FEAT-111 "轨迹反思与成本追踪" 共享的分类点统一产生——**跑 FEAT-111 的 testplan** 验证分类覆盖和正确性（含 `must_violation` / `should_violation` / 稳定错误码 181012 / GUARDRAIL_DENIED 因果反例等 17 项），本 FEAT 不重写。

本节只保留 **3 条代表性场景**，验证"**降级门确实消费了 FEAT-111 产出的分类，并且分子/分母记账正确**"——这是 FEAT-112 自己的契约边界。

| ID | level | required_capability | 场景 | 前置 | 步骤 | 期望 | Fixture |
|---|---|---|---|---|---|---|---|
| K-rep1 | MUST | （内部） | **代表系统异常类**：模型 5xx 重试耗尽 → 计分子+分母 | SIT 启动 FEAT-111 跑一遍确认 `TerminalClass=MODEL_SERVICE_FAILURE` 分类正确 | `LlmFaultInjectionStub.setNextError(ENGINE_MODEL_ERROR)` + 发一条新任务 | 诊断 `sampleCount+1` + `systemFailureCount+1` + `systemFailureRate` 更新 | `DegradationGateTest` + 本 testplan 的 `LlmFaultInjectionStub` |
| K-rep2 | MUST | （内部） | **代表只计分母类**：正常完成 → 只计分母不计分子 | 同上 | `LlmFaultInjectionStub.reset()` + 发一条正常任务 | 诊断 `sampleCount+1` + `systemFailureCount` 不变 | 同上 |
| K-rep3 | MUST | （内部） | **代表不记账类**：用户取消 → 不计分子不计分母 | 同上 | 发任务 → 立即 `/cancel` → 等 CANCELED 终态 | 诊断 `sampleCount` 不变 + `systemFailureCount` 不变 | 同上 |

> **注**：原 K1-K17 分类矩阵全覆盖**不在本 testplan 范围**——跑 FEAT-111 的 testplan（分类矩阵测试）替代。本节 3 条代表场景只为验证"降级门正确消费分类结果"这一本 FEAT 契约。

## 6. 判定结果分类（6 顶层态 + reason_code）

对齐 D1 洞察 §9b v2.5。本 testplan 使用以下结果分类：

**顶层 6 态**：

| 态 | 本 testplan 典型场景 | 处置 |
|---|---|---|
| **PASS** | 子特性被观测且通过 | 绿灯 |
| **FAIL** | 判定失败（含子特性违反 + 平台观察失败），下含 reason_code | 红灯；按 reason_code 分流（见下） |
| **WARN** | SHOULD/MAY 违反——当前 testplan 大部分为 MUST，WARN 主要承载 A10 畸形绑定 | 降级报警 |
| **EXCLUDED** | `level=OUT` 场景——本 testplan §2 非范围 8 项 | 合法 skip |
| **UNSUPPORTED** | L2 能力声明 false——本特性 `required_capabilities` 中有未声明项 | 合法 skip（通过 §8c SIT 反查发现漏声明会报给 demo 侧） |
| **NOT_OBSERVED** | 观察不到，下含 reason_code 两类 | 见下 |

**FAIL.reason_code 5 类分两组**：

- A 组｜子特性违反（**开发修**）：
  - `must_violation`：MUST 契约违反（例：D1 OPEN 返回非 503、E1 并发不限制单探针）
- B 组｜测试平台观察失败（**平台/prompt/基建修**）：
  - `infra_failure`：基建问题（SUT jar 启动超时、网络、Mock 挂）→ 重试 + 修基建
  - `model_variance`：LLM 非确定（本特性较少直接触及，主要在 K 组的分类测试中可能遇到）→ 标模型版本 + 升 prompt
  - `conditional_not_triggered`：条件未触发（例：试图构造 K8 网络超时但下游稳定）→ WARN testplan 维护方
  - `other_untestable`：兜底必挂 issue

**NOT_OBSERVED.reason_code 2 类**：

- `protocol_unobservable`：协议结构性不可观察（永远观察不到）——例：**诊断面是否未泄露密钥是通过字段清单反查**（不是协议暴露"确实没含密钥"的正向观察，而是对缺失的负向观察——边界用例） / **引擎内部状态变化本身不暴露**
- `trace_missing`：观察能力不足（原则上可观察但本次没留痕）——例：**I1 新进程启动语义**若监控系统没关联启动事件，仅看诊断就无法区分"新进程初始化"vs"旧实例探针恢复"→ 报 DFX-001 可观测性建设

**Allure 报告聚合**（参考 D1 §9b）：按 `(态, reason_code)` 二级聚合——跨 20 项 §2 验收出口看结果分布，"开发该修多少" vs "平台该修多少" 一眼可见。

## 7. Test Agent 与 Fixture

本 testplan 不使用确定性 Test Agent：SUT 是 deep-analyze 真实 Agent（真实 LLM + 真实下游）。受控状态通过配置缩短 window/quiet-period + 下游故障注入 + prompt 构造特定路径制造。

| 对象 | 类型 | 设计说明 |
|---|---|---|
| deepanalyze-agent | 真实 SUT | DA 真实部署，双入口 `/run` + `/run-stream` + `/v1/health/detail`；缩短 window 到 30s、quiet-period 到 10s 加速测试 |
| 模型 stub / stub provider | 故障注入 Fixture | 可控制返回 5xx / 超时 / 401 / 403 / 配额满 / 正常，用于 K 组分类矩阵 |
| Mock / Stub | 故障注入 Fixture | 模拟 LLM 层故障（HTTP 错 / 超时 / 结构化错误码 / 合法响应序列含工具调用）；见 §4.2 的 `LlmFaultInjectionStub` 唯一 stub |
| `SutStack` | 进程控制 Fixture | 启动、停止、重启 SUT jar，用于 I1/I2 多进程语义；注入 env（如 DA_ENGINE_SECRET） |
| 双实例 `SutStack` | 多实例 Fixture | 同 JVM 独立对象（现有）+ **真实多进程补齐**（H 组 真实隔离证明） |
| `A2aHttpProbe` / `A2aServiceClient` / `A2aEventCollector` | A2A 协议驱动 Fixture | 底层 HTTP 发 JSON-RPC payload + 收集 SSE 事件 + 等待终态 |
| `DegradationHealthProbe` | 新 Fixture | 统一封装 `GET /v1/health/detail` 带 `X-Engine-Secret`；对齐 G 组 |

**代码级引用**（L2 已有测试资产，复用）：

- `DegradationGateTest`（核心机制：窗口、阈值、九类记账、三态、探针、代际、乱序、过期落定、实例隔离）
- `DegradationGatePropertiesTest`（配置约束、非法值、合法边界）
- `DaProbeSlotHandleTest`（claim/bind/commit/close、重复关闭、并发）
- `DaDegradationAdmissionTest`（双入口 503、零副作用、校验顺序、控制面、多实例、资源拒绝释放）
- `DaDegradationDeliveryDefaultTest`（DA 键缺席默认开、显式 false 优先）
- `DaHealthDetailE2ETest` / `DaHealthDetailHalfOpenE2ETest`（三态诊断、认证、探活不变）
- `DaTerminalClassificationTest`（分类矩阵九值）
- `DaBusinessOutcomeRail` / `DaBusinessOutcomeTools`（专用业务终态）

**需补齐测试资产**（L2 §7.4 已指出）：

- 残窗双阈值达标自动开门（B5）
- 仅有 denied 的未知最终失败因果反例——**由 FEAT-111 testplan 承接**（原 K17 已从本 testplan 剔除；跨特性分类职责）
- 原 OPEN 下 `/cancel` / `/inject` / `/answer` / `/resume` 聚焦（原 F9-F12）——**跑 FEAT-001/008/110 的 testplan**，本 FEAT 只留 F-reg 冒烟
- 完整媒体与 team 前置校验矩阵（F3 内含媒体存在/白名单/team 存在/team 通道可用 4 项合并；原 F4/F5 不单列）
- 慢消费者下终态分类不变（J4）
- INPUT_REJECTED / GUARDRAIL_DENIED 探针关门（E4、E5）
- 畸形 enabled 布尔绑定启动失败（A10）
- 真实 Spring 非法绑定启动、真实模型故障注入、真实多进程实例亲和与告警联动

## 8. 关键链路断言

- OPEN 下 `/run` 和 `/run-stream` 必须返回同一 503 契约（`ENGINE_DEGRADED` + `degraded=true` + `retryable=true` + `state` + `instanceId`）；`/run-stream` **必须在 SSE 建立前返回 JSON**
- `Retry-After` 和 `retryAfterSeconds` 必须使用相同向上取整秒数；HALF_OPEN 必须省略
- 降级拒收必须在 `taskId` 分配、任务注册、属主绑定、start 事件、执行提交**之前**；拒收后任务列表无记录、执行链无调用、窗口不变
- 入站校验（认证、参数、媒体、team）必须先于降级门；无效请求不占探针
- 分类矩阵必须由 `TerminalClass` 决定分子/分母；Controller、Translator、反思和健康面**不得**重新解析展示文本
- 唯一记账点：每个逻辑任务最多产生一次终态事实（`DaTaskRecord` CAS）；回答、恢复、inject、内部重试、工具、子 Agent 不各自放大样本
- 故障代际：按特性档 §5.1.2/§5.1.4 验收。HALF_OPEN 中未过期系统故障递增代际并回 OPEN，迟到探针成功不得覆盖（C5/C6）；过期事实不记样本、不改代际、最近故障时间或门状态（B7），当前探针过期落定只释放名额并保持 HALF_OPEN（E9）。判据来源是正式契约而非实现；单调窗口下界相等属于过期，窗口内新故障不得被豁免。
- 多实例：窗口、探针、`instanceId` 必须按实例隔离；A 的 CLOSED 不得清除 B 的 D4 提示
- 进程重启：新进程初始化不等同于真实探针恢复；监控必须结合进程启动事件保留原故障上下文
- SIT 侧按 D1 §8c 做两道反查：**启动前 schema 覆盖反查**（`required_capabilities` 在 YAML 必列齐）+ **跑完后行为反查**（skip 分布异常时触发核对）

## 9. 执行策略

**Smoke**（PR 门禁 + 日常 CI）：

- A1、A2、A7、A8（配置默认与边界）
- B1.1、B1.2、B1.3、B3（窗口基本行为）
- C1、C2、C3（状态机 happy path）
- D1a、D1b、D4、D6（双入口拒收）
- E1.1、E1.2、E2、E6、E7（探针闭环）
- F1、F2、F-reg（校验顺序 + 控制面回归冒烟）
- G1、G2、G3、G5a-G5c（诊断面三态 + 认证）
- K-rep1、K-rep2、K-rep3（降级门消费分类的代表场景——**全量分类矩阵跑 FEAT-111 testplan**）

**Full suite**（每日/周期 + release 前）：

- A1、A2、A7-A10、B1.1-B1.3、B2-B5、B7、B8、C1-C7、D1a-D1b、D3-D6、E1.1-E1.2、E2-E9、E11、F1-F3、F-reg、G1-G4、G5a-G5c、G8-G10、H1-H3、I1-I2、J1-J4、K-rep1-K-rep3（**全量分类矩阵跑 FEAT-111 testplan**；原 F6-F12 控制面详细行为跑 FEAT-001/008/110 testplan；原 A3-A6/B6/E10 移 UT，见 §1.1）

**P0 必须全绿**（阻塞合入）：

- D1a、D1b、D3、D4、D5、D6（双入口拒收契约）
- E1.1、E1.2、E6、E7（半开唯一探针 + awaiting + cancel 正确释放）
- F1-F2（基本校验顺序）
- G1-G3、G5a-G5c（诊断三态 + 认证）
- K-rep1、K-rep2、K-rep3（降级门消费分类——全量分类走 FEAT-111）

**长运行 / 需真实资源的**（标 `@Tag("manual")`）：

- C5、E9（时间窗口 + 竞争）—— 需要精确时间同步
- H1-H3（真实多进程部署）
- I1-I2（真实多进程重启）
- L2 §7.4 列的"部署补充验证"——真实 Spring 启动、真实模型故障、真实多进程告警联动

**配置缩短原则**（遵循 FEAT-112 §6）：

- 只通过**公开配置** 缩短 `window` 和 `open-quiet-period`（生产 5m/2m → 测试 30s/10s）
- 只通过**故障注入**制造真实任务结果（下游 Mock 返 5xx、模型 stub 返超时、SutStack 停下游）
- **不得**依赖强制开门、假探针或直接篡改内部状态——本特性明示不提供

**结果判定按 §6 六态**：PASS / FAIL（含 reason_code）/ WARN / EXCLUDED / UNSUPPORTED / NOT_OBSERVED。Allure 报告按 `(态, reason_code)` 二级聚合输出。

---

## 附录 A｜§2 验收出口 20 项 → testplan ID 对照

| §2 验收出口 | 对应 ID |
|---:|---|
| 1. ext 未开启不产生降级 | A1 |
| 2. 最小样本 20 边界 | B1.1、B1.2、B1.3 |
| 3. 异常率 25% 不开、50% 开 | B2、B3 |
| 4. 窗口过期 + OPEN 无流量保持 OPEN + 静默期无假探针 | B4、C7、E1（无真实任务时 HALF_OPEN 不生成假探针） |
| 5. 分类矩阵全覆盖 | **跑 FEAT-111 testplan 的分类矩阵**（本 testplan 不含；§1.1 范围声明）+ 本 testplan K-rep1/2/3 代表场景（只验证降级门消费分类结果） |
| 6. 断流分类 | J1-J4 |
| 7. OPEN 下双入口 503 契约 + Retry-After + HALF_OPEN 省略 | D1a、D1b、D3、D4、D5 |
| 8. 拒收零副作用 + OPEN 前任务继续 | D6、F-reg（在途控制面详细行为跑 FEAT-001/008/110 testplan） |
| 9. 静默期后只转 HALF_OPEN，真实探测前不 CLOSED | C2、E1 |
| 10. 半开竞争仅一个探针 | E1 |
| 11. 半开探针系统异常回 OPEN + 重算静默期 | C4 |
| 12. 半开真实任务完成 + 业务失败可 CLOSED + 窗口清空 | E2、E3 |
| 13. 半开 awaiting + 续跑回答唯一记账 | E6、E10 |
| 14. 半开取消等落定释放 | E7 |
| 15. 故障代际防迟到（旧任务先故障 vs CLOSED 先成立） | C5、C6 |
| 16. 启动空窗口 + 恢复清窗口 + 禁用态诊断 + 认证 + 不泄露敏感 | B8、G1-G8 |
| 17. 多实例独立 | H1-H3 |
| 18. OPEN/HALF_OPEN 下校验先于门 + 认领后资源拒绝释放 | F1、F2、F3、F-reg、E8 |
| 19. 非法配置启动失败指出键名 + 合法边界 | A7-A9（A3-A6 非法值已移 UT，见 §1.1） |
| 20. 重启后新进程 CLOSED 空窗口 + 不得记为旧实例恢复 | I1、I2 |

## 附录 B｜§4 场景旅程 12 项 → testplan ID 对照

| §4 场景旅程 | 对应 ID |
|---|---|
| 小样本故障 | B1.1、B1.2 |
| 异常率超标 | B3、C1 |
| 重试成功 | **跑 FEAT-064 testplan**（重试策略非本 FEAT 职责）[upstream_not_ready] |
| 客户端断流 | J1、J2 |
| 在途任务收尾 | F-reg（冒烟）+ 相关 FEAT testplan |
| 静默期结束 | C2 |
| 半开并发竞争 | E1.1、E1.2 |
| 探针待答 | E6 |
| 探针业务失败 | E3 |
| 旧任务故障竞争 | C5 |
| 探针取消 | E7 |
| 多实例部署 | H1-H3 |

## 附录 C｜本 testplan 应用的 D1 洞察要点

1. **8 列场景矩阵**（ID / level / required_capability / 场景 / 前置 / 步骤 / 期望 / Fixture）——参考 D1 §6b
2. **ID 规范**（`FEAT-112-{domain}{seq}[-{variant}][.{point}]`）——参考 D1 §6c
3. **域前缀 A-K**，每 FEAT 文件头在 YAML frontmatter 列出含义——本文 §2 和 §5 分组
4. **`required_capabilities`** 在 YAML frontmatter 列出，强制 demo 侧对每个 key 显式表态——参考 D1 §8c 启动前 schema 反查
5. **6 态结果 + reason_code**（PASS / FAIL(5 类) / WARN / EXCLUDED / UNSUPPORTED / NOT_OBSERVED(2 类)）——参考 D1 §9b v2.5
6. **显式 OUT + 机理 + reason_code**（见 §2 非范围表）——参考 D1 §5 治理栈 L1 层的"原文保真 + 显式排除"
7. **三件绑死稳定 ID 作为连接键**（testplan ID ↔ 判据代码 `@DisplayName` ↔ 违规样本 fixture）——参考 D1 §15 L5-a
8. **SIT 反查漏声明**（demo 漏声明通过启动前 schema 反查拦截 + 跑完后行为反查发现高 skip 分布）——参考 D1 §8c
9. **CI 四道一致性检查**（静态配置 / wire 探测 / ID round-trip / 月度破坏演练）——参考 D1 §8d，本 testplan 由 CI 运行时承接
10. **合法 skip 必须有主动行为**（EXCLUDED/UNSUPPORTED/NOT_OBSERVED(protocol_unobservable)），不是默认兜底——参考 D1 §9b
