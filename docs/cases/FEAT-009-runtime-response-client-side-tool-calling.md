---
feature: FEAT-009
title: 运行时通过响应调用客户端本地工具
status: verified
sut: multi-react-travel-demo/travel-mainplan + agent-runtime-java-bus + agent-runtime-ext-java
---

# FEAT-009 - 运行时通过响应调用客户端本地工具测试设计

> 通过真实 `travel-mainplan` 的 JSON-RPC A2A 阻塞/流式入口，验证 Runtime 对 client-tool 的挂起、响应投影、查询、continuation 校验和原 Task 恢复事实。

## 1. 设计依据与测试范围

- 依据：`FEAT-009-runtime-response-client-side-tool-calling.md`（2026-07-24，SHA-256 `89AE96D1...794F6D6`）；`Feat-Func-009-runtime-response-client-side-tool-calling.md`（2026-07-24，SHA-256 `3D7EC549...FC340A`）；读取于 2026-08-12。
- 裁决：Feature 是范围权威，L2 细化当前 JSON-RPC `SendMessage`、`SendStreamingMessage`、`GetTask` 和 client-tool wire。Feature 将等待期间取消列为条件 SHOULD，L2 明确本期不实现取消，因此不生成取消用例。
- 范围内：当前 ToolView 随调用进入；client-tool 请求使 Task 进入 `INPUT_REQUIRED` 且通过本次响应完整投影；`GetTask` 可重观察；合法 continuation 恢复原 Task；异常 outcome 作为 observation；错关联、缺项、未知/重复结果不得恢复 Agent 或创建新 Task，Runtime 可按 Feature §5.4 将协议非法推进为标准失败；同步与流式等价。
- 范围外：客户端注册/授权/真实本地执行（FEAT-007）；core 如何装配工具和产出意图（FEAT-010）；Gateway/BUS（FEAT-012/017）；纯用户输入中断（FEAT-008）；REST query；类、Rail、优先级、内部 metadata key、checkpoint/store 算法。
- 源码查阅例外：为确认测试托管的 OpenAI-compatible peer 可由公开配置切换，最小检索命中了 `TravelMainplanLlmProperties.java` 的 `provider/apiBase` 字段声明；必要性是排除隐藏的固定 LLM endpoint 后再启动外部 JAR。测试预期仍全部来自 Feature/L2，未读取工具、Runtime 或 Core 实现，也未从源码新增断言。
- 示例使用：仅参考本仓文档结构、G/W/T 和标签规范，不继承旧范围或结论。

## 2. 黑盒拓扑、前置条件与证据

```text
test-owned OpenAI-compatible peer <-HTTP- travel-mainplan(real external JAR)
acceptance test -JSON-RPC/SSE-> /a2a -> Runtime Task -> client-tool response
acceptance test -continuation-> /a2a -> same Runtime Task -> final response
```

- SUT：`com.openjiuwen.example:travel-demo-mainplan:0.1.0` fat JAR，依赖 `com.openjiuwen:agent-service-app:0.1.1` 与 `agent-service-adapters-agentcore-ext:0.1.0`；来源分别为 `agent-runtime-java-bus@aaa812a4`、`agent-solution-bus@addf3233`。
- 测试只从 `/a2a`、SSE 与 `GetTask` 观察。外部确定性 LLM peer 仅稳定地产生工具选择/最终答复，不实现 Runtime、Agent 或工具逻辑，不能证明 LLM 质量。
- 每次使用唯一 context/message/request canary。首轮声明 `readLocalTripPolicy`，提示模型必须读取本地差旅政策；测试在客户端形成成功或拒绝 observation 后提交。
- 主要证据：响应/帧中的 Task state、`taskId`、`contextId`、`_interrupt` 的 `client_tool`、`toolName`、`toolCallId`、arguments，以及 continuation 后同一 Task 的终态/最终文本。日志和 wire 仅辅助诊断。
- 启动即证明 ext Handler 装配的事实并入业务旅程，不单列启动用例。测试结束关闭 Agent 与 LLM peer，不遗留进程。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-009.lifecycle.project-resume` | Feature §2-5；L2 §2.3、§5 | blackbox | runnable, P0 | verified | sync/stream 挂起、响应收束、查询、成功/拒绝 outcome、原 Task 恢复 | A2A/SSE Task 与 interrupt | 参数化两种传输；启动等价覆盖 |
| `FEAT-009.resume.validation` | Feature §5.3-5.5；L2 §2.3.3、§5.4 | blackbox | runnable, P0 | verified | 多 pending 完整集合；缺项/未知/重复/错 Task 不恢复 Agent或新建 Task；任务隔离 | 错误/failed 表面 + 模型调用计数 + taskId | 终态迟到结果并入本例；取消排除 |

## 4. 详细用例

### FEAT-009.lifecycle.project-resume - client-tool 挂起、投影与恢复

- **状态/优先级**：runnable, P0；**自动化状态**：verified。
- **追溯**：Feature §2-5；L2 §2.3、§5.1-5.3、§6；**测试类型**：blackbox。
- **G**：真实 mainplan 已就绪；确定性 LLM 在看到本次 ToolView 后请求 `readLocalTripPolicy(city)`，收到 observation 后输出包含唯一 canary 的最终答案；分别选择 `SendMessage` 与 `SendStreamingMessage`，并参数化成功、用户拒绝两类 outcome。测试不向服务端暴露任何本地工具 HTTP/插件/文件端点。拒绝是 Runtime 视角下异常业务 outcome 的代表等价类；权限不足、工具不可用、未声明、参数非法、执行失败和超时在线上都以 observation 文本或受治理引用进入，Runtime 不解释其客户端治理子类型，因此不为每种文案重复建例。
- **W**：携带 `metadata.clientTools` 发起首轮；接收本次响应；以返回的 taskId 执行 `GetTask`；随后用相同 task/context 发起新的 continuation invocation，提交结果文本（单 pending 可省略 toolCallId）。
- **T**：首轮在有界时间内返回/收束，Task 为 `INPUT_REQUIRED` 而非 completed；响应及查询保留同一 taskId 和完整 client-tool 投影；没有客户端结果时服务端既不访问不存在的客户端资源，也不伪造结果或继续完成；continuation 恢复原 Task，成功和拒绝文本均先作为模型 observation，再由 Agent 决定终态；最终完成且包含 peer 基于该 observation 生成的 canary。
- **失败归类**：合同不符为 Failure；SUT 制品或 LLM peer 不可启动为 Error；无可用 ext 制品为 dependency-gated。
- **不应断言**：Rail、Handler、checkpoint、prompt token、固定自然语言或内部调用次数。
- **方法/标签**：`feat009ProjectsAndResumesClientToolAcrossSyncAndStream()`；`@Story("FEAT-009.lifecycle.project-resume: client-tool 挂起、投影与恢复")`；`@Tag("story-feat-009-lifecycle-project-resume")`；DisplayName `Feat-009 同步与流式调用均挂起并恢复原 Task`。

### FEAT-009.resume.validation - 结果集合、关联与终态保护

- **状态/优先级**：runnable, P0；**自动化状态**：verified。
- **追溯**：Feature §5.3-5.5；L2 §2.3.3、§5.4；**测试类型**：blackbox。
- **G**：真实 mainplan；为缺项、未知 ID、同请求重复 ID 和合法完整集合分别建立一次包含两个不同 `toolCallId` 的 client-tool pending，避免一个非法请求使 Task 失败后污染后续变体；另建并保持一个独立等待 Task。
- **W**：对前三个 Task 分别提交对应非法集合并查询；对干净 Task 一次完整精确提交全部 pending，完成后重放同一 continuation；再向完全未知 taskId 提交结果。
- **T**：所有非法提交均出现明确标准错误/状态冲突，或把原 Task 推进为可诊断 `FAILED`；两种合法外部表面都不得调用恢复后的模型、不得隐式创建新 Task，且独立等待 Task 不变化。完整提交只恢复并完成其原 Task一次；终态重放不能再次恢复或创建新 Task；未知 taskId 必须被拒绝。
- **失败归类/不应断言**：既未明确拒绝也未将原 Task 推进为可诊断 `FAILED`、非法集合触发模型恢复/新建 Task、或串扰其他 Task 才判 Failure；不绑定内部异常类、HTTP 状态的未承诺细节或存储结构。
- **方法/标签**：`feat009RejectsInvalidResumeSetsWithoutResumingAgent()`；`@Story("FEAT-009.resume.validation: 结果集合、关联与终态保护")`；`@Tag("story-feat-009-resume-validation")`；DisplayName `Feat-009 非法结果集合不恢复 Agent 或串扰 Task`。

## 5. 文件与执行

- 计划测试文件：`ClientToolRuntimeBlackboxTest.java`，两种传输和 outcome 用参数化合并。
- 共享测试类只放 `integration,blackbox,openjiuwen`；两个 FEAT-009 方法各自放 `@Feature("FEAT-009: 运行时通过响应调用客户端本地工具")` 与 `@Tag("feat-009")`，避免合并文件中的 FEAT-010 方法被 `-Dgroups=feat-009` 误选。
- 执行（PowerShell）：`$env:SUT_M2_REPO='D:\repository'; .\mvnw.cmd --% -Dtest.env=openjiuwen -Dgroups=feat-009 test`；Story 把 groups 改为 `story-feat-009-lifecycle-project-resume`。
- 基线：JDK 21、PowerShell；Maven settings 的实际本地仓库为 `D:\repository`（不是默认 `~/.m2/repository`）。测试自动生成 prompt、canary 和结果，不需人工导入。
- 本次实际制品 SHA-256：`travel-demo-mainplan-0.1.0.jar` = `B5FC3ABD5B486CD602C8AC527D67DE8A56E59FA8EB44B6D65DFF332A697AFA5F`；`agent-service-app-0.1.1.jar` = `F0DACC40E11508E3ED3C8E4A8221348220B1399BB955F01007FA5DF2467B82CA`；`agent-service-adapters-agentcore-ext-0.1.0.jar` = `AFF5D431F34D67B78E799D9CCC7D97BDF6780412C711D470447633305CA28582`。
- 2026-08-12 验证：`feat-009` 标签仅执行本特性的 2 个方法，结果 `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`；其中 `resume.validation` 修订后单独复跑为 1/1 通过。

## 6. 风险、阻塞与待澄清项

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| 实际 `D:\repository` 初始缺少 Runtime 与 travel 坐标 | 无法启动 | 已从指定 SUT 仓重装 | 保留构建提交与 JAR SHA-256 |
| 多工具同轮依赖确定性模型响应格式 | 可能不稳定 | 测试 peer 可控 | 使用公开 OpenAI wire 的多 tool_calls 响应 |

## 7. 退出标准

两例的全部参数通过真实外部 JAR；每个等待点均可从公开响应和查询观察，非法结果不恢复 Agent或创建新 Task；没有使用内部 API、数据库或 fake Runtime 声称通过。
