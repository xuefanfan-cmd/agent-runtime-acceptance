---
title: agent-runtime Python 技术架构
description: 托管运行时的模块依赖链、各层职责、两类入口调用链、Handler 选型、生命周期与状态分层、协议与兼容性边界
audience: both
---

# agent-runtime Python 技术架构

## 一、工程定位

`openjiuwen-agent-runtime` 是**嵌入宿主进程的 SDK**，不是独立服务：提供执行契约、标准服务入口、状态与生命周期编排。它不承载 Agent 的推理循环（那是 agent-core），也不承载进程、鉴权与部署策略（那是宿主）。

## 二、模块结构与依赖链

```text
domain  <-  ports  <-  application  <-  adapters  <-  bootstrap / deploy
```

| 层 | 可以依赖 | 职责 |
|---|---|---|
| `domain/` | 标准库、领域类型 | 请求、结果、Task 状态机、续接输入 |
| `ports/` | domain、typing | Handler、状态、缓存、远端、总线、技能中心的协议 |
| `application/` | domain、ports | 执行编排、在途流登记、远端批次、总线消费 |
| `adapters/` | domain、ports、必要的 application | 入站协议与出站框架、远端、后端的转换 |
| `bootstrap/` | 全部内部层 | 组合根：配置加载、扩展发现、契约检查、生命周期、应用工厂 |

依赖方向单向。框架私有对象必须在适配器内部消化：`application` 与 `domain` 只接收端口与领域类型。

## 三、各层职责

- **入站适配器**：A2A（卡片、协议路由、执行器、Task）、自定义 REST（通道解析、SSE 投影、聚合出口）、总线（准入与投影）。
- **出站适配器**：agent-core 通用适配器、异构框架、远端与 Versatile、状态与缓存、技能中心。
- **应用层**：`ServeOrchestrator` 负责选 Handler、登记在途流、绑定会话与 Task；`remote_batch` 负责深度与预算准入、成员屏障。
- **组合根**：把上述件按配置装配起来，并在启动期拒绝不满足契约的对象。

## 四、两类入口的调用链

```text
HTTP body
  → 入站通道 / 协议适配器
  → ServeRequest + 执行上下文
  → ServeOrchestrator
  → AgentHandler.stream_query
  → QueryChunk
  → 协议投影（A2A 事件队列 / REST SSE 帧）
```

每一跳只负责自己的边界转换：领域聚合件不解析 HTTP 请求，也不理解协议库的类型。

## 五、Handler 选型

| 场景 | Handler |
|---|---|
| agent-core 的 ReAct / Workflow / DeepAgent | `AgentCoreHandler`（通用，按运行资源登记形态择取执行入口） |
| 异构本地框架 | 自写适配器实现 `AgentHandler` 五方法 |
| 远端 Agent 或非标准远端服务 | Versatile 与远端适配器 |
| 协议与生命周期验收 | 确定性替身，报告中必须写明替身边界 |

## 六、生命周期与状态分层

### 生命周期阶段

```text
构造 → contract check → init hooks → handler.start()
  → readiness=ready → 接收请求 → active stream / Task 推进
  → 关闭入口 → 停止接收 → 排水 → handler.stop()
```

`bootstrap/lifespan.py` 统一管理启动与关闭。REST 和 A2A 应使用同一套语义：每个生命周期只启动 / 停止一次；关闭先停止新请求，再等待在途流，超时后按既定失败 / 取消策略收尾。

### Task 状态

领域状态机位于 `domain/task/state_machine.py`。协议层状态由 `adapters/inbound/a2a/state_bridge.py` 转换；TaskStore 只保存可恢复的协议 Task，不把框架私有对象写入 Redis。

典型路径：`submitted → working → completed | failed | canceled`；用户交互或远端继续路径进入 `input_required`，续接请求携带 `ResumeInput` 回到同一会话。

### 状态分层

| 状态 | 位置 | 说明 |
|---|---|---|
| Handler 健康与 active stream | 进程内 | 运行时瞬时事实，重启后重建 |
| 等待输入 registry | 进程内有界结构 | 只保存当前进程可续接的等待窗口 |
| A2A Task | Redis TaskStore | 按 Task ID 与 context ID 读写，带 TTL |
| 会话 → Task / request snapshot | 共享键面 | 两侧同时在线时必须使用相同键模板 |
| 总线 admission / projection | Redis 或实现指定后端 | 用于幂等、投影和跨进程消费 |

### 多副本注意事项

进程内 registry 不能作为跨副本事实源；需要续接、回调、任务查询或去重的事实必须外置。事件 ID / revision 的来源要能在多副本下保持单调或可去重。

### 失败和排水

排水超时不是“静默放弃”：应记录在途流、触发 adapter 的清理钩子，并将仍未完成的外部可见任务映射为约定的失败 / 取消状态。客户端断连与服务器关停是不同事件，分别遵守各自入口的判据。

## 七、协议与兼容性边界

### 三个协议面

| 面 | 入口 / 组件 | 主要输出 |
|---|---|---|
| 标准 A2A | `bootstrap/a2a_app.py`、`adapters/inbound/a2a/` | Agent Card、Task、TaskStatusUpdateEvent、Artifact |
| 自定义 REST | `bootstrap/rest_app.py`、`adapters/inbound/rest/` | JSON 错误信封、SSE 事件帧、聚合响应 |
| 南向远端 | `adapters/outbound/remote/`、`versatile/` | Card 请求、文本 / 数据片段、回调和失败终态 |

### QueryChunk 归一

`QueryChunk` 只有 `type` 与 `data` 两个顶层字段。内容事件的 `data` 记录 `event_type`、`content`、`plugin` 和可选业务数据；错误记录 `message`、`code`、`kind`；中断记录 `content`、`interaction_id` 和可选 delegation。

终答必须作为内容 chunk 发出；完成由 async iterator 正常结束表达。断流无显式错误时默认按 interrupt 解释，显式异常才落 error。此规则同时保护 A2A Task 状态和 REST SSE 尾帧。

### 兼容性验证层次

1. 函数级：投影器、键构造、错误信封和结果映射。
2. 应用往返级：路由匹配、判定顺序、状态码和完整响应。
3. 流级：SSE 原始字节、帧分隔、中文 / 转义、哨兵帧和媒体类型。
4. 真 socket / 容器级：真实服务器、依赖、启动命令、远端报文和断连。
5. 差分级：同一输入送存量与本版，比较约定兼容面；未纳入清单的面不得宣称已兼容。

### 兼容性清单维度

入口、事件类型、响应信封、HTTP 错误、南向出向、共享键、远端行为和部署契约共同构成兼容面。错误形态不能用通用错误类推，字段名、字段顺序、空值和状态码都可能是调用方契约。

## 八、与 agent-core 的集成

接触面只有三处：运行资源登记、执行入口、原生输出帧到 `QueryChunk` 的转换。逐项见 [agent-core Python 技术架构](01-agent-core-python技术架构.md)。

## See also

- [agent-core Python 技术架构](01-agent-core-python技术架构.md)
- [agent-solution 技术架构](03-agent-solution技术架构.md)
- [关键技术机制总结](05-关键技术机制总结.md)
- [Runtime 公开接口](../api/agent-runtime-python.md)
