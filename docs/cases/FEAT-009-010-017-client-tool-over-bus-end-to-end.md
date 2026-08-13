# FEAT-009/010/017 - BUS 端侧工具整体测试设计

## 1. 整体边界与链路

```text
client ToolView
  -> formal Agent Bus CLIENT_INVOCATION_REQUESTED
  -> FEAT-017 Runtime consumer / standard Task control plane
  -> FEAT-010 current-task tool visibility and client-tool handoff
  -> FEAT-009 INPUT_REQUIRED response projection
  -> FEAT-017 INVOCATION_INPUT_REQUIRED
  -> client executes/declines locally
  -> new continuation event
  -> FEAT-017 same Task control plane
  -> FEAT-009 validates/resumes original Task
  -> FEAT-010 consumes observation
  -> FEAT-017 RESPONSE + TERMINAL -> client
```

本方案只验证三个目标特性的契约衔接。FEAT-012/013 提供上游事件形成与转发时只作为 Given，不在本方案验收其路由、outbox/inbox 或 Gateway 五态；FEAT-006/007 只提供 invocation 与本地 outcome，不断言其独立职责。

## 2. 执行门禁

- 正式 BUS-enabled `travel-mainplan` fat JAR 必须同时包含 Runtime、AgentCore-ext Client Tools 与 `agent-service-bus-consumer`，不能用测试 fixture/专用 echo demo替代。
- 正式 broker/relay/adapter 与客户端事件入口可用；Runtime 的逻辑 serviceId、tenant 和 response producer 完整接线。
- 当前 travel demo POM 缺 consumer 模块，且本机无 Docker CLI。Runtime/ext/travel 坐标已安装到 Maven settings 指定的 `D:\repository`，但这不能补足 BUS assembly。因此本例当前为 `dependency-gated + env-gated`，仅记录真实门禁条件，不生成假通过路径。
- L2 当前只支持 inline payload；本例保持小载荷。FEAT-017 CancelTask 缺口不阻塞本条 happy-path，但仍阻止 FEAT-017 全特性通过。

## 3. 统一 Fixture 与分层证据

- 唯一 runId 派生 tenant、messageId、idempotencyKey、correlation、trace、context 与 canary；所有敏感值脱敏。
- 确定性外部 LLM peer 让真实 Agent 首轮只选择 `readLocalTripPolicy`，恢复后只输出包含客户端 canary 的最终答复。
- 业务证据：最终 Agent 文本使用客户端 observation；工具未在服务端执行。
- Task 证据：同一 runtime taskId 依次为 `INPUT_REQUIRED`、恢复、completed。
- FEAT-010 证据：当前模型调用看见指定 client tool；下一 Task 不携带 ToolView 时不再看见。
- BUS 证据：同 correlation 的 requested、accepted、input-required、response、terminal；BUS 消息不含 token/SSE frame/物理 endpoint。
- fixture 只生成输入、托管外部 LLM 和观察公开边界，不消费产品内部 SPI，不直接修改 TaskStore。

## 4. 最小整体用例

| 子用例 ID | 状态 | 自动化状态 | 串联特性 | 业务价值 |
|---|---|---|---|---|
| `FEAT-E2E.client-tool.bus-round-trip` | dependency-gated + env-gated, P0 | design-only | FEAT-017 -> FEAT-010 -> FEAT-009 -> FEAT-017 | 客户端能力经 BUS 触发真实 Agent，客户端本地执行后恢复同一 Task 并返回终态 |

## 5. 详细用例

### FEAT-E2E.client-tool.bus-round-trip - 端侧工具经 BUS 完整往返

- **G**：上述正式拓扑已就绪；client 注册并只向本 invocation 暴露 `readLocalTripPolicy`；LLM peer 脚本已绑定唯一 canary。
- **W**：client 通过正式 BUS 入口发送含 ToolView 的旅行请求；收到 input-required 后在本地返回 policy observation；再以新的 continuation invocation 经 BUS 提交；随后发起不带 ToolView 的新 Task。
- **T（按链路）**：FEAT-017 消费请求并发布 accepted，真实 Task 可查询；FEAT-010 只在首 Task 让 Agent 看见并选择该工具，产出名称/参数/调用关联且不服务端执行；FEAT-009 使同一 Task 非终态挂起并完整响应投影；FEAT-017 发布同 taskId/correlation 的 input-required；continuation 被 FEAT-017 送回标准 Task 控制面，FEAT-009 校验后只恢复原 Task，FEAT-010 消费 observation；FEAT-017 发布 response 与唯一 terminal；client 得到使用 canary 的最终答复；新 Task 不继承旧工具。
- **失败归类**：任一正式核心环节缺失为门禁；链路可运行但契约断裂为 Failure；环境异常为 Error。不得切换 DIRECT 后声称整体通过。
- **不应断言**：Gateway 路由选择、broker topic/offset、内部 rail/store、固定自然语言、客户端 SDK 内部线程或 outbox。
- **方法/标签**：`clientToolRoundTripsOverBusThroughRealTravelAgent()`；`@Feature("FEAT-E2E: BUS 端侧工具整体链路")`；`@Story("FEAT-E2E.client-tool.bus-round-trip: 端侧工具经 BUS 完整往返")`；方法标签 `feat-e2e,feat-009,feat-010,feat-017,story-feat-e2e-client-tool-bus-round-trip,e2e,blackbox`。
- **DisplayName**：`Feat-E2E BUS 端侧工具挂起并恢复同一真实 Travel Task`。

## 6. 文件、标签与执行

- 计划文件：`ClientToolBusEndToEndIT.java`，仅一条整体旅程。
- 解锁后执行：`.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-e2e-client-tool-bus-round-trip verify`。
- 失败必须按业务、Task、工具可见性和事件四层证据定位；测试结束关闭 Agent/LLM 进程并清理本 runId 消息。

## 7. 退出标准

不绕过 BUS、Runtime consumer、真实 travel Agent 或 client-tool 挂起/恢复任一环节；一个 runId 形成从请求到唯一 terminal 的闭环，且新 Task 证明 ToolView 不泄漏。
