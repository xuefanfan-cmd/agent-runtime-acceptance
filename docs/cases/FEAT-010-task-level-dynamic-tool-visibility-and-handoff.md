---
feature: FEAT-010
title: 任务级动态工具可见性与调用移交
status: verified
sut: multi-react-travel-demo/travel-mainplan + agent-runtime-ext-java
---

# FEAT-010 - 任务级动态工具可见性与调用移交测试设计

> 通过真实 Agent 的公开 A2A 行为，验证当前任务 ToolView 可见、服务侧工具保留、客户端工具只形成移交意图、恢复 observation 可继续执行且工具不跨任务泄漏。

## 1. 设计依据与测试范围

- 依据：`FEAT-010-task-level-dynamic-tool-visibility-and-handoff.md`（2026-07-21，SHA-256 `6FCF1423...1FF5AF`）；FEAT-010 无独立 L2，FEAT-009 L2 §1.1、§2、§4.1-4.4 明确承担其跨层交付；读取于 2026-08-12。
- 裁决：只采用 FEAT-009 L2 中落地 FEAT-010 外部语义的部分；所有类、Rail、session 匹配、优先级、注册/卸载方式都是内部实现，不作为 Oracle。
- 范围内：有/无/变化 ToolView 的本任务可见性；服务侧既有工具不受影响；选择客户端工具后产出带名称/参数/关联的调用意图且服务端不执行；恢复 observation 后 Agent 可继续。
- 范围外：Runtime Task 状态、投影与 continuation 校验（FEAT-009）；客户端治理（FEAT-007）；模型一般工具选择质量；全局目录、MCP、Skill Hub；新 API；内部对象传递方式。
- 源码查阅例外：同共享 Fixture 的 FEAT-009 方案，最小检索仅确认 demo 的公开 LLM endpoint 配置字段；未读取 Core 工具实现，缺少独立 L2 的部分只由跨层 L2 明示补足。

## 2. 黑盒拓扑、前置条件与证据

真实 `travel-mainplan` 外部 JAR 通过公开 `/a2a` 接收请求；确定性外部 LLM peer 记录每轮公开 OpenAI 请求中的 tool definitions，并按测试脚本选择客户端工具或既有 `request_user_input` 服务侧工具。主要证据是不同 Task 对应的 LLM 请求工具名集合与 A2A client-tool/用户中断投影；LLM peer 是外部观察点，不读取 Agent 内存。

SUT 坐标、制品来源、runId、生命周期和清理与 FEAT-009 相同。三个独立 Task 使用不同 context，避免对会话记忆作越界推断。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-010.visibility.handoff-isolation` | Feature §2-5；跨层 L2 §2、§4 | blackbox | runnable, P0 | verified | 有/无/变化 ToolView、服务侧工具保留、client-tool 移交、observation、跨 Task 隔离 | LLM tool definitions + A2A 投影 | 一条参数化旅程覆盖全部 MUST |

## 4. 详细用例

### FEAT-010.visibility.handoff-isolation - 动态可见、移交与任务隔离

- **状态/优先级**：runnable, P0；**自动化状态**：verified；**测试类型**：blackbox。
- **追溯**：Feature §2-5；FEAT-009 L2 §2.1、§4.1-4.4（仅外部语义）。
- **G**：真实 mainplan；外部 LLM peer 能记录收到的工具 schema。Task A 的 ToolView 仅含 `readLocalTripPolicy`，Task B 无 ToolView，Task C 仅含 `readLocalCalendar`；mainplan 原有 `request_user_input` 服务侧工具始终存在。
- **W**：A 请求并选择 policy 工具，提交 observation 后完成；B 发起不携带 ToolView 的独立请求并直接回答；C 请求选择 calendar。
- **T**：A 的模型工具面含 policy 和既有服务侧工具，工具名称、description、JSON object schema 在 Agent 所见工具定义中保持统一；client-tool 投影含实际名称、符合 schema 的 object 参数和非空调用关联；服务端不产出伪造工具结果，恢复 observation 后模型继续。B 不含 policy 且不能产生合法 policy client-tool 投影；C 含 calendar、不含 policy；既有服务侧工具定义在 A/B/C 中保持存在。A 的旧视图没有自动保留到 B/C。本例不触发或验收服务侧工具自身行为。
- **失败归类**：工具跨 Task 泄漏、服务侧工具消失、服务端直接执行或意图缺关键关联为 Failure；外部 peer/制品异常为 Error。
- **不应断言**：结构化上下文的 Java 类型、prompt 拼接细节、Rail 生命周期、全局注册表内部状态、模型必须在自然语言自由选择时调用工具。
- **方法/标签**：`feat010ScopesToolViewsAndHandsOffClientCalls()`；`@Story("FEAT-010.visibility.handoff-isolation: 动态可见、移交与任务隔离")`；`@Tag("story-feat-010-visibility-handoff-isolation")`；DisplayName `Feat-010 ToolView 仅影响当前 Task 且客户端调用只被移交`。

## 5. 文件与执行

- 与 FEAT-009 合并在 `ClientToolRuntimeBlackboxTest.java`，共享真实 Agent 与外部 LLM fixture，减少文件/启动次数；共享类只带层级标签，本方法单独使用 `@Feature("FEAT-010: 任务级动态工具可见性与调用移交")`、`@Tag("feat-010")` 与 Story tag，确保按 Feature 不串跑 FEAT-009 方法。
- 执行（PowerShell）：`$env:SUT_M2_REPO='D:\repository'; .\mvnw.cmd --% -Dtest.env=openjiuwen -Dgroups=feat-010 test`；Story 把 groups 改为 `story-feat-010-visibility-handoff-isolation`。
- 2026-08-12 验证：`feat-010` 标签仅执行本特性的 1 个方法，结果 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。

## 6. 风险、阻塞与待澄清项

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| FEAT-010 无独立 L2 | 不能断言内部/字段细节 | 已收窄 | 仅采用 Feature 与跨层 L2 明示外部事实 |
| 外部 LLM 请求是辅助观察面 | 只能证明 Agent 本次看见的工具面 | 可接受 | 最终以 A2A 移交/隔离行为闭环，不单独据此宣称 Runtime 能力 |

## 7. 退出标准

唯一旅程在真实 Agent 上证明当前 ToolView、服务侧工具保留、调用移交、结果观察和跨任务变化；没有测试 Runtime 状态机或 core 内部实现。
