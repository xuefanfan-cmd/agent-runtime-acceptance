---
scope: v0730
deployable_units: [agent-solution, agent-runtime-java]
sut: agent-service-adapters-versatile-controller-handoff（生产代码在上游 openJiuwen/agent-solution；验收 example versatile-controller-handoff-acceptance-demo，fork zhangdengjiecai/agent-solution）
features: [FEAT-002]
updated: 2026-08-27
---

# Versatile 控制器意图转调（FEAT-002）—— 测试计划

## 1. 测试目标

验证 FEAT-002「Versatile 控制器意图转调」子能力：adapter 直接对接包含控制器的 Versatile 低码应用时，识别控制器返回的意图转调消息、解析目标智能体、产出 `a2a_delegate` 中断移交 runtime 协调器执行出站 A2A 调用、并把下游结果归一为 runtime 既有 `QueryChunk` 与终态语义。覆盖一级→二级转调、二级→一级退回（upstream-signal）、循环保护、下游结果归一，以及 handoff 回归（`handoff.enabled=false` 走基线 Versatile 代理）。

验收视角：单 jar 多 profile e2e（`scripts/local-e2e.sh` 驱动场景旅程）+ 单元测试兜底（上游取消等 e2e 无法可靠触发的路径）。

## 2. 范围与非范围

范围（设计文档 §1.1「包含」）：

- 控制器报文分类（普通业务结果 / 意图转调消息 / 执行异常）。
- 意图转调消息识别与字段提取（三选一非空解析来源判定）。
- 目标智能体解析（直接目标标识 / 意图映射 / 业务域映射，按 `resolution-priority`）。
- 目标允许范围校验。
- 转调命中产出单 item `a2a_delegate` 中断（`resume=true`）移交 runtime 协调器。
- re-invoke 轮入口短路（失败码映射 / 终答直通 / not-in-scope 信封重识别）。
- 二级退回一级 upstream-signal（not-in-scope 标记信封应答调用方）。
- 弹回目标无状态 `DUPLICATE_TARGET` 循环保护。
- 出站 contextId 前缀改写（可选）、下游结果归一、转调链路可观测与失败处理。

非范围（设计文档 §1.1「不包含」）：

- adapter 不做用户输入语义识别（归属 Versatile 控制器及低码意图识别工作流）。
- adapter 不建立父子 Task 编排、不维护跨 Agent 任务树（影子任务持久化由 runtime 协调器 FEAT-004 承载）。
- 不改变单 Agent runtime 执行模型（不注册多 Handler、不按 agentId 路由）。
- 不把客户业务路由规则硬编码进 runtime 通用核心模块。
- 不新增 northbound HTTP 入口，不修改 `AgentHandler`/`ServeRequest`/`QueryChunk` 语义。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-002-heterogeneous-agent-framework-compatibility.md` | §1.1 控制器意图转调扩展定位、§2 能力要求表、§6.1「至少覆盖」13 条测试约束（第 408–420 行）。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-002-versatile-controller-intent-message-routing.md` | §1.1 特性范围、§1.2 能力对齐矩阵、§2.2 识别条件、§2.3 出站契约、§2.4 结果归一、§7.2 场景旅程 15 个、§7.3 错误表面、§7.4 最小测试套件。 |
| 验收 example `versatile-controller-handoff-acceptance-demo/README.md` | 拓扑（三 profile 端口）、运行命令、目录结构。 |
| example `scripts/local-e2e.sh`、`application*.yml` | 场景旅程的脚本实现、转调识别条件与意图映射、运行方式。 |

## 4. 部署拓扑

单 jar + 多 profile，每个实例一个 runtime：

| 实例 | profile | 端口 | 角色 |
|---|---|---|---|
| layer1 | `layer1,mock-controller` | 18091 | 一级控制器：命中本域 / 意图转调二级 |
| layer2 | `layer2,mock-controller` | 18092 | 二级控制器：命中本域 / 退回一级 |
| handoff-disabled | `handoff-disabled,mock-controller` | 18093 | 回归：`handoff.enabled=false` 走基线 Versatile 代理 |

`mock-controller` profile 挂载进程内 mock Versatile 控制器（`MockControllerController`），发出真实 `node_finished` 线格式。层间转调走 runtime 默认 `RemoteAgentCaller`（A2A）静态发现。

边界要求：

- e2e 驱动只通过三实例公开 A2A 入口观察；mock 控制器确定性产出，隔离真实工作流波动。
- `handoff.enabled=false` 时 demo 需显式注册基线 `VersatileAgentHandler`（`HandoffDisabledVersatileConfiguration`），否则报 `agent not loaded`。

## 5. 测试场景矩阵

本特性场景矩阵以设计文档 §7.2「场景旅程验收」15 场景 + §7.3「错误表面验收」为本源；全部 e2e 场景共用 mock 控制器（`MockControllerController`）+ L1/L2 双 runtime + `scripts/local-e2e.sh`。脚本场景编号与 S 编号对应：S1→场景1，S2→场景2/2b，S3→场景3b，S4→场景3，S5→场景4，S6→场景5，S7→场景7，S8→场景10，S9→场景8，S10→场景11，S11→场景2（re-invoke 直通断言），S12→场景9，S13→场景15，S14→场景6，S15→场景16（`ControllerHandoffCancellationTest` 兜底）。

### 5.1 场景旅程（设计 §7.2）

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| S1 | 一级命中本地工作流 | 一级控制器下挂意图识别工作流与本地业务工作流，目标在本地 | 用户经 A2A 调用一级控制器 | 控制器不产生转调消息；adapter 按 FEAT-002 返回业务结果，不产出委派中断 | `local-e2e.sh` |
| S2 | 一级转调二级 | 一级与目标二级控制器已封装为独立智能体并注册 A2A 网关 | 一级返回指向外部二级的意图转调消息 | adapter 识别并消费转调消息，控制器流结束后产出 `a2a_delegate` 中断（先完成后转发，L2 收到调用 ≥ L1 workflow finished）；协调器经 A2A 网关调用目标二级控制器，下游结果经 re-invoke 入口短路映射到当前执行；转调消息未被映射为 `FAILED`；流式轮次下游增量以 `TYPE_REMOTE_AGENT_OUTPUT` 投影；启用前缀改写时 L2 收到 `<agentId>-<原contextId>` | `local-e2e.sh` |
| S3 | 二级命中本域工作流 | 二级控制器下挂本域工作流，输入属于本域 | 输入进入二级控制器 | 控制器不产生转调消息；adapter 按 FEAT-002 返回业务结果，不调用一级控制器 | `local-e2e.sh` |
| S4 | 二级退回一级重新路由 | 二级可返回「不属于本业务域」转调消息，该类型已配置在 `handoff.signal.handoff-types` | 二级判断输入不属于本域 | 二级 adapter 不出站，以 not-in-scope 标记信封应答；信封随 remote 结果回传，一级 re-invoke 入口检测到标记后抑制信封、重跑本层控制器重新识别（同一 `conversationId`），可继续转调正确二级；一级再次转调已弹回的同一目标时产出 `VERSATILE_HANDOFF_DUPLICATE_TARGET` | `local-e2e.sh` |
| S5 | 控制器返回真正异常 | 控制器发生系统/网络/工作流/业务异常，返回不满足转调识别条件 | adapter 接收控制器异常 | adapter 按 FEAT-002 错误映射进入 `FAILED`，不误识别为转调 | `local-e2e.sh` |
| S6 | 转调消息缺少目标信息 | 已识别转调但按 `resolution-priority` 所有来源均未解析出目标 | adapter 尝试解析下游目标 | 不产出无目标中断，不返回空 `COMPLETED`，产出 `VERSATILE_HANDOFF_TARGET_MISSING` 并记录缺失路由信息 | `local-e2e.sh` |
| S7 | 目标调用失败（不可用） | 已解析目标但目标未注册/出站连接失败，协调器以非超时 `REMOTE_*` 失败回传 | re-invoke 轮进入 handler | 映射为 `VERSATILE_HANDOFF_TARGET_UNAVAILABLE`（`reason` 保留原始 `REMOTE_*` 码），保留目标与调用错误信息，不返回空 `COMPLETED` | `local-e2e.sh` |
| S8 | 目标调用失败（超时） | 出站调用超过 remote-agents `timeout-seconds`，协调器以 `REMOTE_TIMEOUT` 回传 | re-invoke 轮进入 handler | 映射为 `VERSATILE_HANDOFF_TIMEOUT` | `local-e2e.sh` |
| S9 | 下游请求用户输入（中断呈现） | 已通过委派中断调用目标，下游返回 `INPUT_REQUIRED` | runtime 处理下游中断 | 中断以 `publicInterrupt` 形状呈现客户端（`inputPrompt` + `handoff:<agentId>:` 前缀 toolCallId），不伪装失败、不静默；转调链由 runtime 影子任务挂起 | `local-e2e.sh` |
| S10 | 下游中断续接（多轮恢复） | 第一轮下游 `input-required` 已呈现，客户端发起第二轮 | 第二轮 `SendMessage` 以 `message.taskId` 引用第一轮 task | 影子任务命中后协调器直呼下游续调同一远端 task（L1 控制器全程仅调用一次），终答在同一 task 上 `completed`；不带 taskId 时影子任务查不到、静默降级为全新执行 | `local-e2e.sh` |
| S11 | happy-path re-invoke 终答直通 | 委派出站成功且无信封，协调器 re-invoke 本层 handler | handler 入口短路消费 `runtime.remoteToolResults` | 流式轮次内容已由协调器投影、handler 仅 `onComplete()`；非流式轮次 `joinedResults` 作为 `TYPE_CHUNK` 下发后 `onComplete()` | `local-e2e.sh` |
| S12 | 弹回后重复转调 | re-invoke 重识别后再次转调已弹回的同一目标 | handler 检查弹回目标集合 | 产出 `VERSATILE_HANDOFF_DUPLICATE_TARGET`，不形成无限调用 | `local-e2e.sh` |
| S13 | 普通 Versatile 兼容 | Versatile 服务不含控制器或控制器不产生转调消息，且 `handoff.enabled=false` | 调用方按原有方式调用 | adapter 继续使用 FEAT-002 原有 REST/SSE 代理与结果处理，结果不变 | `local-e2e.sh` |
| S14 | 未授权目标 | 控制器直接返回的目标不在 `allowed-agents` | adapter 解析目标 | 不产出中断，产出 `VERSATILE_HANDOFF_TARGET_NOT_ALLOWED` | `local-e2e.sh` |
| S15 | 上游取消 | 消费控制器 SSE 流过程中上游取消 | `observer.isCancelled()` 返回 true | adapter 停止消费控制器输出（协作式取消）；委派中断之后的取消由 runtime 协调器承载 | `ControllerHandoffCancellationTest` |

### 5.2 错误表面（设计 §7.3）

| ID | 错误场景 | 验收标准 | 落点 |
|---|---|---|---|
| E1 | 转调识别条件不完整 | `handoff.enabled=true` 但必需识别条件（`classify.field-path`/`field-value`）缺失在**启动期失败**，明确报告缺失项，不进入运行时路径 | `ControllerHandoffConfigTest`（启动失败两用例） |
| E2 | 可用解析来源全空（必要字段缺失） | 配置完整、消息满足识别条件但 `resolution-priority` 参与来源全空且非 signal（如生产意图回显帧）时 `IGNORED` 整行抑制 + WARN 日志，不产出报错、不透传给最终用户 | `ControllerHandoffMaskingTest`（IGNORED 分支） |
| E3 | 目标信息缺失/映射不存在 | 产出 `VERSATILE_HANDOFF_TARGET_MISSING`，记录缺失路由信息 | e2e S6（`TARGET_MISSING` 码断言） |
| E4 | 目标不在允许范围 | 产出 `VERSATILE_HANDOFF_TARGET_NOT_ALLOWED`，不产出中断 | e2e S14（`TARGET_NOT_ALLOWED`） |
| E5 | 目标不可用/网关失败 | 协调器非超时 `REMOTE_*` 失败映射 `VERSATILE_HANDOFF_TARGET_UNAVAILABLE`，保留目标与错误信息（`reason` 含原始码） | e2e S7（`TARGET_UNAVAILABLE`） |
| E6 | 调用超时 | 协调器 `REMOTE_TIMEOUT` 映射 `VERSATILE_HANDOFF_TIMEOUT`（remote-agents `timeout-seconds`） | e2e S8（`TIMEOUT`） |
| E7 | 弹回后重复目标 | 产出 `VERSATILE_HANDOFF_DUPLICATE_TARGET`，不形成无限调用 | e2e S12（`DUPLICATE_TARGET`） |
| E8 | 错误形状 | 所有 `VERSATILE_HANDOFF_*` 失败与基线 extractor 契约一致（`{"code":"VERSATILE_HANDOFF_*","reason":"..."}` JSON）：流式以 `TYPE_ERROR` chunk 下发并 `onError()`，非流式以同 JSON 异常上抛；客户端无需识别协调器 `REMOTE_*` 原始码（无分层错误码） | `MockWireFormatExtractionTest` + e2e 错误码断言 |
| E9 | 真正异常（未命中转调） | 走 FEAT-002 原有 `TYPE_ERROR`/`FAILED`，不误判为转调 | `MockWireFormatExtractionTest` / e2e S5 |
| E10 | 上游取消 | 停止消费控制器输出；不返回空 `COMPLETED` | `ControllerHandoffCancellationTest` |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| `MockControllerController` | mock 控制器 | 进程内 mock Versatile 控制器，发出真实 `node_finished` 线格式；确定性产出转调/业务/异常报文。 |
| `HandoffDemoApplication` | 入口（runtime 层） | 三 profile 启动入口，加载转调识别条件与意图映射配置。 |
| `HandoffDisabledVersatileConfiguration` | 回归装配 | `handoff.enabled=false` 时注册基线 `VersatileAgentHandler`，否则报 `agent not loaded`。 |
| `scripts/local-e2e.sh` | e2e 脚本 | 驱动场景旅程，含 NO_PROXY 绕过公司代理、Redis 配置传参。 |
| `ControllerHandoffCancellationTest` | 单元测试 | mock `observer.isCancelled()==true` 断言协作式停止消费（上游取消路径）。 |
| `ControllerHandoffMaskingTest` | 单元测试 | DFX-001 日志脱敏（IGNORED 分支不泄露原始行）。 |
| `ControllerHandoffConfigTest` / `ControllerHandoffReuseContractTest` | 单元测试 | 配置外置驱动 + resolution-priority 收敛；复用类 public 可见性契约。 |
| `MockWireFormatExtractionTest` | 单元测试 | mock 控制器 wire-format 解析基元：answer/end 线产出 legacy answer、异常线转失败、message-format 转调线分类（E8/E9 底层覆盖）。 |

## 7. 关键链路断言

- 只有控制器返回的消息满足意图转调识别条件时，adapter 才映射为跨智能体调用；未匹配消息继续走原有 `TYPE_ERROR`/`FAILED` 映射（设计 §1.1/§2.2）。
- 转调成立按三选一非空解析来源判定（`intent-id` / `business-domain` / `target-agent-id` 任一非空，仅计 `resolution-priority` 参与的来源）（设计 §2.2）。
- 一级向二级转调产出单 item `a2a_delegate` 中断（`resume=true`，先完成后转发），出站调用由 runtime 协调器承载（设计 §1.1/§2.3）。
- 二级「不属于本业务域」不出站调用，以 not-in-scope 标记信封应答调用方（upstream-signal），一级 re-invoke 入口重跑本层控制器（设计 §2.3/§3.4）。
- 弹回目标以无状态 `DUPLICATE_TARGET` 循环保护（设计 §1.1）。
- 下游失败成员映射 `VERSATILE_HANDOFF_*` 错误码；成功无信封时终答直通；含信封时抑制并重跑（设计 §2.4）。
- `handoff.enabled=false` 必须走基线 Versatile 代理（回归，场景 S13）；未命中转调时严格保持原有 Versatile 行为（设计 §1.2「原有结果处理兼容」）。

## 8. 执行策略

- Smoke：S1、S2、S5（一级本地命中 / 一级转调二级 / 异常与转调区分三条最基础链路）。
- Full suite：S1~S15 + E1~E10（25 条场景旅程 + 错误表面）。
- P0 必须全绿：S1、S2、S4、S5、S6、S10、S13、S14、E1、E3、E4、E5、E6、E7（设计 §1.2 MUST 项对应）。

```bash
export JAVA_HOME="D:\Program Files\Java\jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
export NO_PROXY="127.0.0.1,localhost,::1"
export no_proxy="127.0.0.1,localhost,::1"

# 打包 + 全量验收
MSYS_NO_PATHCONV=1 cmd.exe /c "mvn clean package -DskipTests"
SKIP_BUILD=1 bash scripts/local-e2e.sh
```

- 上游取消（场景 S15）受 demo legacy servlet 传输层限制，e2e 不覆盖，以 JUnit 单测（mock `observer.isCancelled()==true`）覆盖。
- 下游中断续接（场景 S10）依赖 runtime 影子任务（`message.taskId` 引用）；断点续跑 Redis 态依赖 `openjiuwen.service.middleware.checkpointer.type=redis` + `7.213.199.153:6379`。

## 附录 A. 相对设计基线的差异

| 变化 | 对用例的影响 |
|---|---|
| 出站机制迁移（2026-08-20）：转调命中不出站调用，adapter 只产出 `a2a_delegate` 中断（`resume=true`），出站 A2A 调用、影子任务持久化、中断-续跑链由 runtime 协调器（`RemoteInvocationBatchCoordinator`）承载 | 场景断言从「adapter 自行出站」改为「adapter 产出 a2a_delegate 中断 + 协调器续调」；早期模块内 `CrossAgentResumePort` 三条件门控、`cross-agent-resume.*` 配置与 `VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED` / `_RESULT_INVALID` / `_CALLER_UNAVAILABLE` 错误码随之退役。因此需求 §2.1「下游中断处理边界」MUST 的「续接能力未启用 → 返回可诊断的不支持跨 Agent 续接失败」分支（含 §6.1 测试约束第 9 条）在当前实现已不存在——续接由 runtime 协调器 FEAT-004 恒承载、不再有 adapter 侧门控开关，故场景矩阵不再单列该场景 |
| 复用模块 `agent-service-adapters-versatile` 的基线变更：四个包级私有类放大为 `public`（`VersatileHttpClient`/`VersatileRequestExtractor`/`VersatileResponseExtractor`/`IntentAgentResolver`），行为零变更 | `ControllerHandoffReuseContractTest` 以 javap 反编译确认 public 可见性契约 |
