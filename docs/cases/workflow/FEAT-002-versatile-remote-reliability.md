---
用例编号: FEAT-002-versatile-remote-reliability
测试标题: 远程 Versatile 控制器故障收敛机制——agent-runtime-java 在远端不可达/超时/中断下不静默挂起、不假成功
story: S2
优先级: P1
自动化状态: TOBUILD（`VersatileRemoteFailureTest` 4 方法待新建；依赖 Versatile SUT 部署形态明确）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, versatile, fault, feat-002]
---

# FEAT-002-versatile-remote-reliability — 远程 Versatile 控制器故障收敛机制

> **机制一句话**：远程 Versatile 控制器故障收敛是 **agent-runtime-java 的可靠性机制**（FEAT-002 §6
> FR-REL-01 中断检测 MUST）——当父智能体经远程 Versatile 协同执行时，若远端不可达 / 超时 / 运行中被杀，
> runtime 必须**把父侧任务收敛到失败终态（FAILED）或可辨识的中断语义，绝不静默挂起、绝不假成功收敛为
> COMPLETED**。本机制验证四类故障面的收敛行为：正常链路（对照基线）、不可达、超时、运行中中断——
> 后者是 feature 明名 MUST 的「无 End 消息不得关闭为 completed」核心语义。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 远程控制器故障下的任务终态收敛与中断检测（FR-REL-01 MUST）——不静默挂起、不假成功 |
| **载体层 · agent-solution** | 机制触发载体 | 父智能体（经 Versatile 控制器协同）→ 远程 Versatile 控制器（唯一带 downstream=模型/工具的被测对象） |
| **测试数据层** | 载体 agent 的实现逻辑 | 多轮模型/工具协同的业务消息（深度研究 / 复合查询）——触发 Versatile downstream 调用的载体夹具 |

## 关联特性

- **FEAT-002（异构智能体框架兼容适配）**：story 2「兼容远程 Versatile 控制器」；§6 FR-REL-01 中断检测（MUST）。

## 关联架构约束 / FEAT-002 事实要求

- FEAT-002 §2.2：远程 Versatile 控制器正常链路 + 网络故障 + 调用超时 + 运行中中断（4 个子用例）。
- FEAT-002 §6 FR-REL-01（MUST）：**无 End 消息不得关闭为 completed**——杀掉 Versatile 端点后，父侧任务不得被错误收敛为 COMPLETED。
- FEAT-002 §5.1 验收标准：远程 Versatile 不可达/中断时，父侧任务收敛到失败终态而非静默挂起或假成功；正常链路不受影响。

## 前置条件

1. 被测 jar 就绪：父智能体 + 远程 Versatile 控制器（**部署形态待明确——独立进程 or 内嵌依赖**，这是本用例最大落地前置）。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。
3. 故障注入手段就绪：「不可达」= 配置死地址 或 `SutStack.stop()`；「超时」= 框架 `FaultLink`（Toxiproxy latency）注入，链路不可代理时退化为超短超时阈值配置。

## 测试数据

- 载体输入：需多轮模型/工具协同的业务消息（深度研究 / 复合查询），目的是触发 Versatile 的 downstream 调用，使运行中中断场景成立（任务须先进入多轮协同执行中）。
- 故障注入点：Versatile 端点地址（不可达 / latency 注入 / 进程 kill）。

## 故障参数表（同一父智能体，四类故障面）

| 方法 | 故障面 | 注入手段 | 预期父侧终态 |
|---|---|---|---|
| `versatileNormalFlowReachesCompleted` | 无故障（对照基线） | — | COMPLETED（含有效产出） |
| `versatileUnreachableFailsTask` | 不可达 | 死地址 / 就绪后关停进程 | FAILED（或等义失败终态） |
| `versatileTimeoutBounded` | 超时 | `FaultLink` latency / 超短超时阈值 | FAILED 或可辨识超时终态（界限内） |
| `versatileKilledMidRunNotCompleted` | 运行中中断 | 执行中 `SutStack.stop()` | **非 COMPLETED**（FAILED/中断语义） |

## 测试步骤

> 四类故障面合并为一个测试类（`VersatileRemoteFailureTest`）的四个 `@Test` 方法；正常链路方法同时充当故障方法的对照基线。

| # | 动作 | 预期 |
|---|------|------|
| 1 | openJiuwen profile：父智能体 + Versatile SUT 拉起；客户端经 A2A_STREAM 提交多轮协同业务消息（**基线**） | 事件流含中间过程帧，终态 COMPLETED，无 FAILED/CANCELED/REJECTED |
| 2 | Versatile 端点配置为不可达地址（或就绪后关停），客户端提交同一消息（**不可达**） | 任务在可接受时限内收敛 FAILED；**不**静默挂起（无限 WORKING）/ 假成功 COMPLETED |
| 3 | Versatile 端点可达但经 `FaultLink` 注入 latency（**超时**） | 任务在超时界限内收敛 FAILED/明确终态；超时语义可辨识（区别于即时失败） |
| 4 | 任务已进入多轮协同执行中，执行中途 `SutStack.stop()` 杀掉 Versatile 端点（**运行中中断**） | 任务终态**不为 COMPLETED**（FAILED/中断语义）；事件流无伪造的正常收尾 |

## 预期结果（机制断言）

### A — 正常链路达 COMPLETED（对照基线）
- **Given**：父智能体与 Versatile SUT 均就绪。
- **When**：客户端经 A2A_STREAM 提交多轮协同业务消息。
- **Then**：事件流含中间过程帧（TEXT/STATUS 非终态），终态 COMPLETED，结果含有效产出，全程无 FAILED/CANCELED/REJECTED。
- **PASS**：正常链路达 COMPLETED。**FAIL**：正常链路即异常（故障注入手段本身破坏了正常路径）。

### B — 不可达收敛 FAILED（不静默挂起、不假成功）
- **Given**：Versatile 端点不可达（死地址或就绪后关停）。
- **When**：客户端提交业务消息。
- **Then**：任务在可接受时限内收敛到 FAILED（或等义失败终态），错误语义可辨识；**不**出现无限 WORKING / 错误收敛为 COMPLETED。
- **PASS**：收敛 FAILED。**FAIL**：静默挂起 / 假成功（可靠性机制缺陷）。

### C — 超时界限内收敛（超时可观测）
- **Given**：Versatile 端点可达但响应被人为延迟。
- **When**：客户端提交消息并等待。
- **Then**：任务在超时界限内收敛到 FAILED/明确终态；超时行为可观测（状态语义可区分超时与即时失败）。
- **PASS**：超时界限内收敛。**FAIL**：无限挂起 / 超时不可观测。

### D — 运行中中断不得假成功（FR-REL-01 MUST 核心）
- **Given**：任务已进入多轮协同执行中。
- **When**：执行中途 `SutStack.stop()` 杀掉 Versatile 端点。
- **Then**：**无 End 消息不得关闭为 completed**——任务终态不为 COMPLETED（应为 FAILED/中断语义）；事件流未出现伪造的正常收尾。
- **PASS**：中断被正确检测为非 COMPLETED。**FAIL**：被错误收敛为 COMPLETED（FR-REL-01 MUST 违反）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | ⬜ `VersatileRemoteFailureTest`（新建，落 `cases/integration/workflow_call`） |
| 标签 | `@Tag("integration")`；Allure `@Feature("FEAT-002")` + `@Story("wf.versatile-remote-reliability")` |
| 基类 | `BaseManagedStackTest` + `SutStack`（类级生命周期，`stop()` 杀进程，`FaultLink` 注入网络故障） |
| 客户端 | `A2aServiceClient` / `InteractionFlow`（A2A_STREAM 提交 + 事件流收集） |
| 故障注入 | 「不可达」死地址/`stop()`；「超时」`FaultLink`(Toxiproxy latency) 或超短超时阈值；「中断」执行中 `stop()` |

## 运行方式

```bash
# ⬜ 待新建；依赖 Versatile SUT 部署形态明确后实现
mvn test -Dtest=VersatileRemoteFailureTest -Dtest.env=openjiuwen \
  -DLLM_BASE_URL=... -DLLM_API_KEY=... -DLLM_MODEL_NAME=...
```

## 覆盖追溯

| FEAT-002 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| story 2：远程 Versatile 正常链路 | A | ⬜ 待新建 |
| story 2：远程 Versatile 网络故障 | B | ⬜ 待新建 |
| story 2：远程 Versatile 调用超时 | C | ⬜ 待新建 |
| story 2：远程 Versatile 运行中中断（FR-REL-01 MUST） | D | ⬜ 待新建 |

## 清理策略

- 栈由类级生命周期管理；FaultLink 毒代理随栈销毁；被 kill 的 Versatile 进程由 `SutStack.stop()` 收尾。

## 风险与备注

- **最大落地前置——Versatile SUT 部署形态**：本用例全部依赖远程 Versatile 控制器的部署方式（独立服务进程 or 内嵌依赖）与配置入口。落地前须与特性落地侧确认 SUT 拓扑（见 [FEAT-002 设计文档 §6.2 风险 2](../FEAT-002-heterogeneous-agent-framework-compatibility-workflow.md)，其指向的 `docs/framework/STATUS.md` 为待建文档）。
- **故障注入手段对齐**：「不可达」经死地址/`stop()`；「超时」优先 `FaultLink`(Toxiproxy latency)，链路不可代理时退化为超短超时阈值配置，避免过度建设；「中断」复用 `SutStack.stop()` 杀进程（deepagent 档已验证可行）。
- **与近端 workflow 故障边界**：纯 workflow_call 链路上 WorkflowAgent 处于调用链最下游（对外仅 LLM 依赖），无 downstream 可注，故近端 workflow 不提 downstream 故障（FEAT-002 组织原则 2）；Versatile 是唯一带 downstream=模型/工具的合理故障注入对象。
- **与 component 层取消先例**：中断检测（D）与协作式取消（[cooperative-cancel](FEAT-002-cooperative-cancel.md)）是两个不同机制——前者断「被动中断不得假成功」，后者断「主动取消收敛 CANCELED」。
