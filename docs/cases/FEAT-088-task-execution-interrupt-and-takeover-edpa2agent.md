---
feature_id: FEAT-088
feature_title: 任务执行中断与接管 — EDPA2agent 语义暂停与恢复
sut: moa-agent-demo（com.moaagent:moa-agent-demo:0.1.3，l1/l2/mock 三进程）+ agent-runtime-java（含 PR!257，develop 分支）+ agent-solution common（含 PR!1077 + MR!1101）
scope: 语义级暂停、程序级暂停（A2A DataPart / Custom REST）、安全边界挂起、恢复确认、接管、远端暂停、装配兼容、故障容错、边界与已知问题
status: designed
status_note: 用例设计已落地（69 场景），测试代码与环境供给尚未实现；当前仓库无 FEAT-088 用例、application-openjiuwen.yml 无 EDPA2.0 agent 声明
owner: TBD
priority: P0
tags: [integration, e2e, edpa2agent, feat-088, pause-resume, semantic-control, datapart, rest]
depends_on:
  - agent-runtime-java develop 含 PR!257（feat(a2a): 支持入口 Hook 与 Task 串行后续输入）
  - agent-solution common 含 PR!1077（feat(moa): 会话暂停、本地规划恢复与 Demo 演示）+ MR!1101（程序级入口补充文档）
  - L2 设计 MR!464 + MR!489 已合入 docs main
  - moa-agent-demo 0.1.3 已构建并安装到本地 Maven 仓库（依赖 moa-agent lib 先 install）
  - L1 应用显式导入 MoaPauseIngressConfiguration
  - LLM_* 环境变量由 shell 注入，本地跑前 unset 代理
  - 初版运行中控制限单实例，或部署确保同一授权会话始终路由到同一进程
related_docs:
  - develop/04-testplan/FEAT-088-task-execution-interrupt-and-takeover-testplan.md（测试设计，2026-11-12）
  - develop/03-architecture/L2-Low-Level-Design/moa-agent/Feat-Func-088-task-execution-interrupt-and-takeover.md（L2，MR!464+!489，2026-11-10）
  - develop/02-features/FEAT-088-task-execution-interrupt-and-takeover.md（Feature，2026-10-29）
  - develop/01-scenarios/FRS-088-任务中断、暂停与恢复.md（FRS）
  - FEAT-089-session-context-persistence-long-pause-recovery-edpa2agent.md（联动特性）
  - ../write-testcase.md（框架用例落地五步法）
  - agent-solution common/agents/moa-agent/README.md
  - agent-solution common/agents/moa-agent/docs/suspended-tasks.md
  - agent-solution common/agents/moa-agent-demo/README.md
---

# FEAT-088 — 任务执行中断与接管测试用例设计（EDPA2agent）

> **一句话**：以 `moa-agent-demo`（EDPA2.0 Runtime 宿主，L1/L2/mock 三进程）为 SUT，把 FEAT-088 的「语义暂停 → 安全边界挂起 → 新任务接管 → 恢复确认 → 远端暂停 → 装配兼容 → 故障容错」映射为本仓可执行的黑盒验收用例，共 69 个场景，覆盖 L2 §1.1–§7.4 全部可外部验证行为约束。

> **仓库边界**：新增测试代码只写入 `agent-runtime-acceptance`；`moa-agent`/`moa-agent-demo`、`agent-runtime-java`、`agent-core-java` 均为只读被测对象。不加载产品私有类、不反射内部状态、不新增 Agent HTTP/SPI 测试端点。

> **与产品侧 UT/IT 的分工**：开发侧 EDPA2.0 全模块测试 1023 项、Custom REST 86 项、Demo Surefire 5 项均通过；本仓只做**外部可观测**的验收与契约复核，不把产品 UT 通过计为验收通过。

## 1. 设计依据与输入快照

### 1.1 输入快照

| 输入 | 锁定版本/状态 |
|---|---|
| 需求 | `develop/02-features/FEAT-088-task-execution-interrupt-and-takeover.md`（updated 2026-10-29） |
| 测试设计 | `develop/04-testplan/FEAT-088-task-execution-interrupt-and-takeover-testplan.md`（updated 2026-11-12，67 场景） |
| L2 设计 | `develop/03-architecture/L2-Low-Level-Design/moa-agent/Feat-Func-088-task-execution-interrupt-and-takeover.md`（MR!464 + MR!489，updated 2026-11-10） |
| Runtime 代码 | PR [agent-runtime-java!257](https://gitcode.com/openJiuwen/agent-runtime-java/merge_requests/257)（已合入 `origin/develop`） |
| Solution 代码 | PR [agent-solution!1077](https://gitcode.com/openJiuwen/agent-solution/pull/1077)（已合入 `origin/common`）+ MR [!1101](https://gitcode.com/openJiuwen/agent-solution/merge_requests/1101)（程序级入口补充文档） |
| 框架 | `agent-runtime-acceptance` main（2026-11-12，含 feat033 同步） |
| 现状 | 本仓无 FEAT-088 用例、`application-openjiuwen.yml` 无 EDPA2.0 agent 声明 |

### 1.2 转测验证证据（开发侧）

| 验证项 | 结果 | 说明 |
|---|---|---|
| EDPA2.0 全模块测试 | 1023 项，0 失败/0 错误/6 跳过 | 2 项发布隔离 + 4 项 OPENGAUSS_TEST_URL 未配置 |
| Custom REST 测试 | 86 项通过 | |
| Demo Surefire | 5 项通过 | Demo 默认 package 不执行 JUnit |
| 真实模型手工验收 | 通过 | 杭州 A 补问→暂停→北京 B 完成→恢复 A→确认→原问题重现→补充日期人数→杭州完成 |
| 程序级入口补充 | 203 项回归通过 | A2A 普通/流式、REST JSON/SSE、模型执行中受理、本地确认恢复、远端正常完成 |
| Runtime 前序 | 944 项 + 5 条 A2A smoke | 前序验证，非最新版本远端 CI |

### 1.3 已知问题与范围边界

| 项 | 说明 | 链接 |
|---|---|---|
| Issue #612 | 恢复确认文案直接展示归档 Todo RUNNING 状态，可能使用户误解为已开始执行 | `https://gitcode.com/openJiuwen/agent-solution/issues/612` |
| 非法控制错误分类 | 返回 A2A -32603 / REST HTTP 500，缺少专用参数错误分类 | 本轮未修改 Runtime |
| 远端恢复 | 支持远端暂停，不支持未完成远端 Todo 执行现场的恢复 | |
| 不承诺 | 强取消、即时输出抑制、跨节点控制队列、进程重启后未消费控制的可靠投递 | |
| 安全边界 | 暂停在安全边界生效；Plan 已完成时保留完成结果，不生成虚构挂起项 | |

## 2. 范围

### 2.1 范围

- 暂停入口（语义级消息 + 程序级 A2A DataPart / Custom REST 无文本控制，受理即返回独立回执）
- 暂停生效（本地用户等待、规划确认或 Todo 返回边界，不承诺模型轮次结束即停止）
- 挂起 Plan（完整快照归档，保留原 Todo ID/revision/依赖/结果/候选绑定）
- 暂停后新任务接管（A 挂起后 B 使用新 Plan，A 快照保留）
- 恢复确认（展示已完成/未完成/待确认→用户确认→恢复原 Plan，已完成项不重做）
- 远端暂停（L1→L2 等待时暂停，L2 handoff 后 L1 挂起；远端程序暂停后正常回答）
- 异常（无活动 Plan、重复暂停、非法控制、终结后迟到回调、并发登记等）
- 故障（挂起保存失败/超限、恢复版本过期、队列清理等）
- 装配（显式导入才启用，默认不装配含恢复确认工具不注册）
- 边界（GetTask 限制、REST success 语义、进程重启、Hosted EDPA2.0 限制等）
- 已知问题（Issue #612 恢复确认展示 RUNNING 状态）
- 联动（与 FEAT-089 超 TTL 恢复联动、REST 暂停→A2A 恢复跨协议）

### 2.2 非范围

- 会话上下文持久化与长时恢复（超 TTL）→ 归 FEAT-089
- 远端执行现场恢复（L2 handoff 后旧远端 Task 已终结，不重建远端原问题和参数）
- Core/Runtime 源码修改、任意位置线程强杀、受理后立即截断所有输出
- 跨实例控制队列和长期持久化恢复
- 客户端经网关发起的中断/暂停/恢复 → FRS-088 明确不纳入本版本
- 编排图/工作流类任务的打断 → FRS-088 明确不在本版本

## 3. 测试文档与代码落点

| 产物 | 落点 |
|---|---|
| 本文（用例设计） | `docs/cases/FEAT-088-task-execution-interrupt-and-takeover-edpa2agent.md` |
| 正常/远端/异常/规划用例 | `src/test/java/com/huawei/ascend/sit/cases/integration/edpa2_pause_resume/` |
| 故障/边界/装配用例 | `src/test/java/com/huawei/ascend/sit/cases/integration/edpa2_pause_resume/` |
| 联动用例 | `src/test/java/com/huawei/ascend/sit/cases/integration/edpa2_pause_resume/` |
| 环境声明 | `src/test/resources/application-openjiuwen.yml` 新增 EDPA2.0 agent 声明 |
| 构建供给 | `scripts/sut/sut-sources.yml` 新增 `common/agents/moa-agent`、`common/agents/moa-agent-demo` 构建步骤 |

标签约定：

- 类级 `@Feature("FEAT-088: 任务执行中断与接管")`；
- 方法级 `@Story("<用例 ID 的 slug>: <一句话>")`、`@DisplayName("<用例 ID>: <场景>")`；
- 分层标签：`integration` / `e2e`；
- 环境缺失统一 `Assumptions.abort/assumeTrue` 跳过，不伪绿；故障注入类额外打 `@Tag("manual")`。

## 4. SUT 与部署拓扑

### 4.1 SUT

| 进程 | profile | 端口 | 用途 |
|---|---|---|---|
| `moa-mock` | `mock` | 3001 | 共享 Mock Agent，承接景点/酒店/预订等 Skill 下游调用，无需模型 |
| `moa-l2` | `l2` | 18229 | 二级酒店 EDPA2.0，不安装 Hook；验证远端暂停/handoff/正常完成 |
| `moa-l1` | `l1` | 18219 | 一级旅行 EDPA2.0，显式导入 MoaPauseIngressConfiguration，验收主对象 |

- 坐标：`com.moaagent:moa-agent-demo:0.1.3`，读取 `~/.m2`；`agent-solution-latest` 下构建产物亦可。
- 启动顺序：mock → l2 → l1；`SutStack` 按依赖顺序拉起，端口以 `application-openjiuwen.yml` 声明为准。
- L1 必须通过 `@Import(MoaPauseIngressConfiguration.class)` 显式导入暂停入口。
- 初版运行中控制限单实例，或部署确保同一授权会话始终路由到同一进程。

### 4.2 配置要点

```yaml
# application-openjiuwen.yml 新增
sut:
  agents:
    moa-l1:
      group: com.moaagent
      artifact: moa-agent-demo
      version: 0.1.3
      java:
        system-properties:
          spring.profiles.active: l1
    moa-l2:
      group: com.moaagent
      artifact: moa-agent-demo
      version: 0.1.3
      java:
        system-properties:
          spring.profiles.active: l2
    moa-mock:
      group: com.moaagent
      artifact: moa-agent-demo
      version: 0.1.3
      java:
        system-properties:
          spring.profiles.active: mock
```

- LLM 凭据经 `LLM_API_KEY` / `LLM_API_BASE` / `LLM_MODEL` / `LLM_PROVIDER` 环境变量注入。
- 本地跑前 `unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy`，避免内网模型被代理拦截。

## 5. 前置条件与共享约定

### 5.1 环境供给缺口

| 缺口 | 说明 | 落地建议 |
|---|---|---|
| moa-agent-demo 制品 | 需从 agent-solution common 源码构建安装到 ~/.m2 | `cd agent-solution-latest/common/agents/moa-agent && mvn install -DskipTests` → `cd ../moa-agent-demo && mvn install -DskipTests` |
| Runtime 制品 | 需含 PR!257 的 develop 构建 | `cd agent-runtime-java && mvn install -DskipTests` |
| 长任务 Test Agent | 测试设计要求确定性多 Todo + ask_user 等待的长任务 | 在 `common/example` 新增 fixture agent（脚本工具 + 可控模型），不改 EDPA2.0 产品代码 |
| 故障注入 | 存储不可用/超时/归档超限 | 复用 `ToxiproxyFaultLink`；DB 侧用连接串断开/账号回收 |
| application-openjiuwen.yml | 无 EDPA2.0 agent 声明 | 按 §4.2 新增 `moa-l1`/`moa-l2`/`moa-mock` 三进程声明 |

### 5.2 共享约定

- 每个用例生成唯一 `contextId`（`ctx-f088-<slug>-<8 位随机>`），隔离场景使用独立身份/租户/agent_id。
- 断言不依赖具体话术：以 turn1 独有 token（进度、关键事实）在 turn2 输入中被承接为准。
- 故障场景使用测试专用故障注入构造前置条件，验收结果必须从公开响应、SSE、日志/指标表面观察。
- OUT 负面用例仅记录行为是否符合"不承诺"声明，不作为阻断项。
- P0 使用可编程 Model、确定性长任务 Tool，确保暂停时有正在执行的 Todo 或用户等待；真实 LLM 仅作为 smoke test。

## 6. 覆盖矩阵总览

| 测试类 | 分层 / Tag | 用例数 | 对应设计 ID | 状态 |
|---|---|---|---|---|
| `PauseResumeAcceptanceTest` | integration | 14 | §5.1 正常场景全部 | env-gated |
| `RemotePauseAcceptanceTest` | integration | 5 | §5.2 远端场景全部 | env-gated |
| `PauseErrorAcceptanceTest` | integration | 18 | §5.3 异常场景全部 | env-gated |
| `PlanningPauseAcceptanceTest` | integration | 2 | §5.4 规划阶段全部 | env-gated |
| `PauseFaultAcceptanceTest` | integration | 10 | §5.5 故障场景全部 | env-gated / manual |
| `PauseBoundaryAcceptanceTest` | integration | 12 | §5.6 边界场景全部（含 Issue #612） | env-gated |
| `AssemblyAcceptanceTest` | integration / e2e | 4 | §5.7 装配场景全部 | env-gated |
| `Feat089LinkageAcceptanceTest` | integration | 4 | §5.8 联动场景全部 | blocked（依赖 FEAT-089 fixture） |
| **合计** | | **69** | | |

## 7. 用例详细设计

### 7.1 `PauseResumeAcceptanceTest`（integration，env-gated）

SUT：`moa-l1` + 长任务 Fixture Agent。

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-088.normal.semantic-pause | P0 | 任务执行中，已有活动 Plan | 分别发送"暂停""请暂停""暂停一下""先暂停""停止""请停止""先停一下""挂起当前任务" | 受理即返回独立回执（`ACCEPTED`）；不启动第二次 DeepAgent 执行；安全边界后挂起 Plan；后继 Todo 不启动；挂起记录可查 |
| FEAT-088.normal.datapart-pause | P0 | 任务执行中，已有活动 Plan | 发送仅含 DataPart `{"moa.control":{"action":"pause"}}` 的 A2A 消息（无 TextPart）；`messageId`/`contextId`/`role` 齐全；分别测试 `SendMessage` 和 `SendStreamingMessage` | 确定性解析受理；不调用模型；返回独立回执；无伪造"暂停"文本；原业务 Task 不改变；`messageId` 用于重试去重；两种 JSON-RPC 方法均返回独立回执 |
| FEAT-088.normal.rest-pause | P0 | 任务执行中，已有活动 Plan，Custom REST 已启用 | POST `{"moa.control":{"action":"pause"},"stream":false}`；分别测试 `stream:false`（JSON）和 `stream:true`（SSE） | `success:true` 仅表示处理成功，须检查 `code`/`delivery`；JSON 响应含 `custom_rsp_data`；SSE 回执可显示；控制成功时绕过原业务 reservation |
| FEAT-088.normal.rest-non-control-passthrough | P1 | Custom REST 已启用，任务执行中 | 向同一 REST URL 发送普通业务请求（含 `input.query`，不含 `moa.control`） | 非控制请求沿原业务路径处理；走原占用检查；正常返回业务结果 |
| FEAT-088.normal.local-wait-pause | P0 | 本地 Skill ask_user 等待用户输入 | 发起语义或程序暂停 | 暂停不当作日期/人数/确认答案；原问题及完成结果可恢复；Plan 挂起；独立回执 |
| FEAT-088.normal.takeover | P0 | 任务 A 暂停已生效，Plan 已挂起 | 同会话发送新指令 B | B 使用新 Plan 独立完成；A 快照保留；同会话无并发 Plan 写入；B 不进入 A 旧等待关联 |
| FEAT-088.normal.resume | P0 | 任务 A 暂停已挂起，B 已完成或无 B | 发送"继续""继续上一次任务""恢复上一次任务""恢复任务" | 展示已完成/未完成/待确认事项；用户确认后恢复 A；已完成项不重做；恢复确认不替代业务授权；恢复后公开挂起列表移除对应条目 |
| FEAT-088.normal.observability | P1 | 暂停全流程执行 | 查看日志/指标/事件 | 受理/生效/挂起/恢复均可查；关联会话标识、控制 ID、挂起 ID |
| FEAT-088.normal.saved-context-complete | P1 | 任务执行中（已输出部分内容） | 发起暂停，生效后检查保存的快照 | 快照包含原 Plan 完整状态（phase/revision/todos/依赖/结果/候选绑定） |
| FEAT-088.normal.non-stream-pause | P1 | 任务非流式执行中 | 经语义级与程序级各发起一次暂停 | 受理即返回独立回执；暂停受理后该轮输出不再展示；当前 Todo 完成后退出 |
| FEAT-088.normal.receipt-delivery | P1 | 任务执行中 | 分别测试本地/远端控制；验证 A2A 和 REST 两种回执外壳 | `code` 为 `ACCEPTED`/`DUPLICATE`/`NO_ACTIVE_PLAN`/`CONTROL_LIMIT_REACHED`；`delivery` 为 `LOCAL_CONTROL`/`ACCEPTED`/`DUPLICATE`/`NO_TARGET`/`FULL`/`CLOSED`；`event` 为 `pause_requested`；`content` 字段存在且正确 |
| FEAT-088.normal.punctuation-trim | P1 | 任务执行中，已有活动 Plan | 分别发送"暂停。""请暂停！""先停一下 "" 停止 " | 去除首尾空白及末尾 `。！!` 后精确匹配；含标点的暂停词仍生效 |
| FEAT-088.normal.task-id-irrelevant | P1 | 任务执行中，已有活动 Plan，此前 Task 已终结 | 发送携带此前已终结 Task ID 的暂停控制 | 控制语义仍是当前会话活跃 Plan；不对旧 Task 操作；正常受理当前 Plan 暂停 |
| FEAT-088.normal.streaming-pause | P1 | 任务执行中，已有活动 Plan | 发送仅含 DataPart `{"moa.control":{"action":"pause"}}` 的 A2A 流式消息（SendStreamingMessage） | 流式模式下确定性解析受理；返回独立回执；原业务 Task 不改变 |

### 7.2 `RemotePauseAcceptanceTest`（integration，env-gated）

SUT：`moa-l1` + `moa-l2`。

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-088.remote.l2-wait-pause | P0 | L1 委派 L2，L2 等待用户输入 | 发送语义暂停消息 | L2 返回 handoff；L1 挂起 Plan；不误记 Todo 成功；A 的远端关联不污染 B |
| FEAT-088.remote.program-pause-normal-answer | P0 | L1 委派 L2，L2 等待用户输入 | 发送程序暂停（DataPart），随后正常回答 L2 问题 | 程序控制不转发 L2；L2 收到真实答案正常执行；L1 保留结果并停止待执行后继项 |
| FEAT-088.remote.resume-rejected | P1 | L1→L2 暂停后，Plan 包含 RUNNING 远端 Todo | 发送"继续" | 直接提示暂不支持远端现场恢复（不先展示确认项）；保留记录；不重新委派 |
| FEAT-088.remote.normal-complete | P1 | L1 委派 L2，L2 正常完成业务 | L2 返回成功结果 | 先回填结果，再判断是否有待暂停后继项；整个 Plan 已完成则保持完成 |
| FEAT-088.remote.no-fake-paused-result | P1 | L1 委派 L2，L2 等待用户输入，语义暂停已投递 | 观察 L2 对暂停语义输入的响应 | L2 路由层返回原信号（handoff），不能用"已暂停"答复冒充业务结果 |

### 7.3 `PauseErrorAcceptanceTest`（integration，env-gated）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-088.error.no-active-plan | P1 | 会话无活动 Plan | 发起暂停 | 返回 `NO_ACTIVE_PLAN`；不创建新业务；不调用模型 |
| FEAT-088.error.duplicate-pause | P1 | 任务运行中，已受理一次暂停 | 同一 messageId 再次投递 | 返回 `DUPLICATE`；不重复投递/归档；不产生重复挂起 |
| FEAT-088.error.invalid-control | P1 | 任务运行中 | 发送缺动作/未知动作（含 `resume`/`cancel`）/多动作冲突/混合 TextPart/含额外字段/缺 contextId | 明确拒绝（A2A `-32603 / Internal error`/REST HTTP 500，不承诺 4xx）；不调用模型；不退回普通任务处理 |
| FEAT-088.error.business-mixed | P2 | 任务运行中 | 发送同时包含控制 DataPart 和业务 data 的消息 | 拒绝，不执行其中一部分 |
| FEAT-088.error.already-completed | P1 | 任务所有 Todo 已完成（Plan 已终结） | 发起暂停 | 返回 `NO_ACTIVE_PLAN`；不生成虚假的待恢复项；正常保留最终结果 |
| FEAT-088.error.late-callback | P1 | 任务已终结 | 迟到的控制回调或业务回调到达 | 不修改完成结果/台账；不重新挂起或执行业务 |
| FEAT-088.error.cross-target | P2 | 任务 A 已受理暂停，随后 B 成为活动 Plan | A 的迟到控制/回调到达 | 身份或代次不符被拒绝；B 不改变 |
| FEAT-088.error.expired-structured-control | P2 | 本地 Skill 续接核对 revision，控制标记对应旧 revision | 提交过期 revision 的结构化控制 | 重新展示当前问题，不交给业务工具 |
| FEAT-088.error.concurrent-registration | P1 | 任务运行中，已有活动 Plan | 同时发送两个不同 messageId 的暂停控制 | 两个控制均被登记；按到达顺序处理；均返回 ACCEPTED；不同 messageId 独立去重 |
| FEAT-088.error.semantic-exclude | P2 | 任务运行中 | 发送"查询暂停营业的景点"等含暂停词的业务句 | 不触发控制；沿普通业务链处理 |
| FEAT-088.error.stop-not-cancel | P2 | 涉及订单/预订的业务任务执行中 | 发送"停止"触发暂停 | "停止"表示挂起，不等价于取消订单；恢复后订单仍保留 |
| FEAT-088.error.multi-cycle | P1 | 任务可多轮执行 | 暂停→恢复→再暂停→再恢复，循环 2~3 次 | 每次恢复都承接上一次进度；revision 单调递增（A=v1→B=v2→恢复 A→C=v3） |
| FEAT-088.error.control-limit-reached | P2 | 同一 Plan 已登记 64 个 messageId | 再次发送不同 messageId 的暂停 | 返回 `CONTROL_LIMIT_REACHED`；不受理新控制；不影响已受理控制 |
| FEAT-088.error.cross-tenant | P1 | 租户 A 的任务运行中 | 租户 B 对 A 的会话发起暂停 | 无法操作或枚举他人任务；控制事件不泄露内部快照 |
| FEAT-088.error.multi-target-no-guess | P2 | 同一 context 下存在多个可选活动目标 | 发起暂停控制 | 返回 `delivery=NO_TARGET`；不猜测投递对象 |
| FEAT-088.error.no-auto-skip-unsupported | P2 | 同一会话有 2+ 份挂起记录，最近一份不可恢复 | 发送"继续" | 直接对最近记录提示暂不支持恢复；不自动跳过到较早的可恢复记录 |
| FEAT-088.error.rest-no-user-override | P2 | 任务执行中，Custom REST 已启用 | 在控制请求体中添加 `user`/`user_id` 字段 | 沿用 URL headers/鉴权上下文中的身份；不使用请求体指定其他用户 |
| FEAT-088.error.rest-control-input-mixed | P2 | 任务执行中，Custom REST 已启用 | POST 同时包含 `moa.control` 和 `input.query` | 拒绝执行；不部分执行控制或业务；不退回普通业务处理 |

### 7.4 `PlanningPauseAcceptanceTest`（integration，env-gated）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-088.planning.candidate-pause | P1 | 任务在规划候选选择或绑定阶段 | 发起暂停 | 候选选择安全边界挂起；恢复确认不当作候选选择或业务批准；首次候选选择递增 revision |
| FEAT-088.planning.risk-confirm-pause | P1 | 任务在高风险整单确认阶段 | 发起暂停 | 恢复确认不等于 `stage_finalize` 或 `confirm_action`；恢复时重新确认 |

### 7.5 `PauseFaultAcceptanceTest`（integration，env-gated / manual）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-088.fault.save-failure | P1 | 任务运行中，上下文存储故障 | 发起暂停 | 后继业务不执行；明确不可保证恢复；不覆盖唯一现场执行 B；保留当前唯一现场；发送保存失败通知 |
| FEAT-088.fault.save-limit | P2 | 归档已达容量上限（32 份/4,000,000 字符） | 发起暂停 | 超限拒绝；不淘汰未完成任务的现场 |
| FEAT-088.fault.resume-version-expired | P1 | 挂起记录在确认期间发生变化 | 用户确认恢复 | 旧确认失效；不恢复错误版本；重新确认 |
| FEAT-088.fault.interrupt-success-save-failure | P1 | 任务运行中 | 暂停成功生效但同一时刻保存失败 | 暂停不回滚；保存失败有日志；后续按实际持久化结果恢复 |
| FEAT-088.fault.producer-not-released | P1 | 暂停已受理，原执行者仍在执行当前 Todo | 尝试启动恢复续接执行者 | 不并发启动续接执行者；等待原执行者到达安全边界释放执行权后才恢复 |
| FEAT-088.fault.long-wait-accumulate | P2 | 多个 Plan 先后挂起，部分长期未恢复 | 持续挂起不恢复；触发空闲 scope 索引回收重建 | 不耗尽新增执行容量（4096 scope）；不得丢弃已受理输入；索引重建后已处理 messageId 不被重新投递；索引重建不启动 Agent、不回写业务现场 |
| FEAT-088.fault.cancel-executing-task-cleanup | P2 | 任务执行中，延迟输入队列有待投递输入 | 取消执行中的 Task | 取消执行中 Task 保留执行权直到原轮次释放，丢弃其待投递输入；空闲 Task 取消或 close 清理队列 |
| FEAT-088.fault.normal-termination-cleanup | P2 | Task A 有待投递延迟输入，A 所有 Todo 正常完成 | A 正常终结 | A 的延迟输入被清理；不转投给同会话后续新 Task B |
| FEAT-088.fault.capacity-rejection-requeue | P2 | 延迟输入队列已满（FULL） | 观察被拒绝输入的处理 | 回置队首并有限重试；不丢失已受理输入；不无限重试 |

### 7.6 `PauseBoundaryAcceptanceTest`（integration，env-gated）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-088.boundary.delay-max | P1 | 任务刚进入新 Todo | 在 Todo 最开端发起暂停 | 暂停延迟 = 当前 Todo 剩余时长；安全边界生效，非任意点退出 |
| FEAT-088.boundary.no-instant-interrupt | P2 | 任务执行中 | 发起暂停 | 确认不是任意点立即退出，而是安全边界后挂起（OUT 负面） |
| FEAT-088.boundary.no-output-gate | P2 | 任务执行中，当前 Todo 正在输出 | 发起暂停 | 确认受理后当前 Todo 进度/结果仍可能输出；生效后停止推进（OUT 负面） |
| FEAT-088.boundary.gettask-limit | P2 | 任务已暂停挂起 | 调用 GetTask 查询；查询挂起任务列表 | GetTask 不保证包含 `SUSPENDED` 字段；挂起查询可只返回任务描述（OUT 负面） |
| FEAT-088.boundary.rest-success-semantics | P2 | 任务执行中，REST 控制受理后 | 观察 REST 响应 `success:true` | `success:true` 仅表示请求处理成功，不代表暂停已生效（OUT 负面） |
| FEAT-088.boundary.gateway-path | P2 | 客户端经网关连接 EDPA2agent | 经网关发起暂停 | FRS-088 明确不纳入本版本（OUT 负面） |
| FEAT-088.boundary.workflow-task | P2 | 编排图/工作流类任务执行中 | 发起暂停 | FRS-088 明确不在本版本（OUT 负面） |
| FEAT-088.boundary.hitl-confusion | P2 | 任务挂起等待用户输入 | 观察是否误触发暂停逻辑 | 确认两种机制不混淆（OUT 负面） |
| FEAT-088.boundary.process-restart | P2 | 暂停已受理但未生效 | 进程重启或打断请求路由到另一实例 | 不假报受理/生效；进程内控制记录不承担跨进程恢复（OUT 负面） |
| FEAT-088.boundary.no-queue-promise | P2 | 暂停已受理，当前 Todo 未完成 | 同会话立即发送新指令 B | 不提供"暂停尚未生效时无损排队"承诺（OUT 负面） |
| FEAT-088.boundary.hosted-edpa2-limit | P2 | 多 Agent hosted 部署环境 | 观察 hosted EDPA2.0 是否自动支持暂停控制 | 不宣称已自动支持 hosted EDPA2.0 装配（OUT 负面） |
| FEAT-088.boundary.issue-612-resume-display | P1 | 任务 A 暂停已挂起，归档快照中包含 RUNNING 状态的 Todo | 发送"继续"触发恢复确认，检查确认文案中展示的 Todo 状态 | 确认文案展示的 Todo 状态应准确反映归档快照时的状态，但须包含上下文标识表明为暂停时刻快照而非当前执行状态；当前 Issue #612 未修复——直接展示 RUNNING 状态，可能使用户误解为已开始执行；测试时记录实际行为作为已知问题证据 |

### 7.7 `AssemblyAcceptanceTest`（integration / e2e，env-gated）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-088.assembly.default-off | P1 | 未显式导入 MoaPauseIngressConfiguration | 发起暂停控制；再发送"继续" | 暂停入口不受理；沿普通业务链处理；不分配控制索引；恢复确认工具不注册——"继续"不触发恢复确认流程 |
| FEAT-088.assembly.explicit-import | P1 | 通过 `@Import` 显式导入 | 发起暂停控制 | 暂停入口受理控制；使用现有 StagePlanningRail、Runtime 协议适配器及执行器 Bean |
| FEAT-088.assembly.l2-exclude | P1 | L2 未声明导入 | 发起暂停控制 | L2 沿正常业务续接和意图切换；不受理暂停控制；L2 的业务仍可发生意图切换 |
| FEAT-088.assembly.missing-dependency | P2 | 显式导入但缺少规划 Bean 等直接装配依赖 | 启动应用 | 启动失败；不静默降级 |

### 7.8 `Feat089LinkageAcceptanceTest`（integration，blocked）

| 用例 ID | 优先级 | Given | When | Then |
|---|---|---|---|---|
| FEAT-088.cross.interrupt-then-longpause-recovery | P1 | 任务已暂停生效，Plan 已挂起 | 暂停时长超 TTL，原会话发"继续" | 走 FEAT-089 无 TTL 持久化路径恢复；续写承接前文 |
| FEAT-088.cross.interrupt-success-save-failure | P1 | 任务运行中 | 暂停成功生效，同时保存失败 | 暂停不回滚；保存失败有日志；后续按实际持久化结果恢复 |
| FEAT-088.cross.interrupt-then-longpause-newcmd | P1 | 任务已暂停生效，Plan 已挂起 | 暂停时长超 TTL，原会话发新指令 B（非"继续"） | 先恢复已保存上下文，再基于恢复的上下文执行 B |
| FEAT-088.cross.rest-pause-a2a-resume | P1 | 任务执行中，Custom REST 和 A2A 均已启用 | 经 Custom REST URL POST 暂停控制；待生效后经原 A2A 会话发送"继续" | REST 控制受理→本地挂起；A2A 会话恢复确认→恢复原 Plan 原问题；跨协议衔接正常 |

> 阻塞原因：FEAT-089 的长暂停恢复 fixture 在本仓尚无对应用例，待 FEAT-089 用例落地后解除。

## 8. 执行策略与门禁

### 8.1 分级

- **Smoke**（每次改动必跑）：`FEAT-088.normal.semantic-pause`、`FEAT-088.normal.resume`。
- **P0**（必须通过）：`semantic-pause`、`datapart-pause`、`rest-pause`、`local-wait-pause`、`takeover`、`resume`、`remote.l2-wait-pause`、`remote.program-pause-normal-answer`（共 8 条）。
- **P1**：可观测、快照完整、非流式暂停、回执 delivery 字段、标点修剪、Task ID 不改变暂停目标、REST 非控制请求走原路径、远端恢复拒绝、远端正常完成、L2 不冒充业务结果、无活动 Plan、全部 Todo 已完成后暂停（NO_ACTIVE_PLAN）、重复暂停、非法控制、终结后迟到回调、并发登记、跨租户隔离、多循环、候选选择暂停、高风险确认暂停、保存失败容错、恢复版本过期、暂停生效+保存失败联动、中断生产者未释放、延迟边界、恢复确认展示准确性（Issue #612）、装配（默认不装配/显式导入/L2 隔离）、联动场景（共 33 条）。
- **P2**：业务混合、跨目标、过期结构化控制、多目标时不猜测投递、多份挂起记录不自动跳过、REST 请求体不可指定其他用户、REST 控制+业务输入混合拒绝、语义排除、"停止"≠取消订单、CONTROL_LIMIT_REACHED、超限、长期等待积累、取消执行中 Task 队列清理、Task 正常终结时队列清理、容量拒绝回置重试、OUT 负面用例（任意点即时、输出不截断、GetTask/挂起查询限制、REST success:true 语义、网关、编排图、HITL 混淆、进程重启、无损排队、Hosted EDPA2.0 装配限制）、缺少依赖启动失败（共 26 条）。
- **manual**：真实模型 Demo、故障注入类。

### 8.2 命令

```bash
# 单类
./mvnw -Dtest.env=openjiuwen -Dtest=PauseResumeAcceptanceTest test

# 全量 FEAT-088
./mvnw -Dtest.env=openjiuwen -Dtest='*088*' test

# 仅 Smoke
./mvnw -Dtest.env=openjiuwen -Dtest='PauseResumeAcceptanceTest#feat088NormalSemanticPause+feat088NormalResume' test
```

- EDPA2.0 agent 制品未就绪时，类级 `Assumptions.abort` 整类 SKIPPED，不计 PASS。
- 失败必须区分：产品缺陷（红）/ 环境缺失（跳过）/ 用例缺陷（修正）；不允许把跳过当通过写入验收结论。

### 8.3 断言门禁

- 暂停受理：独立回执 `ACCEPTED`/`DUPLICATE`/`NO_ACTIVE_PLAN` + 回执 Task 为 COMPLETED 不改原业务 Task。
- 安全边界生效：受理后当前 Todo 进度/结果仍可能输出，生效后停止推进；不启动第二次 DeepAgent 执行。
- 恢复确认：展示已完成/未完成/待确认→用户确认→恢复原 Plan；已完成项不重做；恢复确认不替代业务授权。
- revision 单调：A=v1 挂起，B=v2 执行后，恢复 A 仍为 v1；后续 C 必须是 v3。
- 远端暂停：L2 无 Hook，handoff 后 L1 挂起；程序控制不转发 L2；L2 不冒充"已暂停"业务结果。
- Issue #612：记录实际行为作为已知问题证据，不作为阻断项。

## 9. 可观测性与断言约定

| 观测面 | 工具/方法 | 用途 |
|---|---|---|
| A2A 回执 | `InteractionFlow` / `A2aEventCollector` | 回执 Task 状态、`code`/`delivery`/`event`/`content` 字段 |
| REST 回执 | HTTP response / SSE collector | `custom_rsp_data.data`、`success` 语义、`code`/`delivery` |
| 原业务 Task | `GetTask` | 原业务 Task 状态独立、不被回执改变 |
| 挂起记录 | 挂起查询 / `MOA-SUSPEND` 日志 | 挂起记录存在、恢复后移除 |
| 日志/指标 | `ProbeLogTailer` + SUT stdout | 受理/生效/挂起/恢复事件可观测 |
| revision | `revision tracker` fixture | Plan revision 单调递增 |

## 10. 风险、阻塞与后续

| 项 | 影响 | 处置 |
|---|---|---|
| moa-agent-demo 制品未构建安装 | 全部用例 SKIPPED | 先 `mvn install` moa-agent lib + moa-agent-demo |
| Runtime develop 不含 PR!257 | SUT 无暂停入口 Hook | 确认 develop 分支已含 PR!257，否则切到含 #257 的构建 |
| 长任务 Fixture Agent 未实现 | 进度断言无法落地 | 新增 example fixture，保持产品代码零改动 |
| application-openjiuwen.yml 无 EDPA2.0 agent 声明 | SUT 无法启动 | 按 §4.2 新增三进程声明 |
| Issue #612 未修复 | 恢复确认展示 RUNNING 状态 | 记录实际行为作为已知问题证据，不阻断 |
| FEAT-089 fixture 未就绪 | 联动场景无法执行 | blocked，待 FEAT-089 用例落地 |
| 真实模型 Demo 无法在 CI 完成 | 端到端验证缺失 | 单独 manual 环境执行 |

## 11. See also

- FEAT-088 测试设计：`D:\docs\docs\develop\04-testplan\FEAT-088-task-execution-interrupt-and-takeover-testplan.md`
- L2 设计（MR!464+!489）：`D:\docs\docs\develop\03-architecture\L2-Low-Level-Design\moa-agent\Feat-Func-088-task-execution-interrupt-and-takeover.md`
- [FEAT-089 联动特性](FEAT-089-session-context-persistence-long-pause-recovery-edpa2agent.md)
- [write-testcase.md](../write-testcase.md)（用例落地五步法）
- EDPA2.0 接入说明：`agent-solution common/agents/moa-agent/README.md`
- 挂起任务说明：`agent-solution common/agents/moa-agent/docs/suspended-tasks.md`
- Demo 说明：`agent-solution common/agents/moa-agent-demo/README.md`
