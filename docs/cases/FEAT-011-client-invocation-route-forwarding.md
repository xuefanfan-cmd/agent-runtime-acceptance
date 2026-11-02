---
feature_id: FEAT-011
feature_title: 网关组件客户端调用路由转发（端到端部署验证）
sut: agent-gateway（DIRECT 直连网关模式）+ RDC（nacos）+ agent-runtime（静态/动态两种接入）
status: active
status_note: 对齐开发侧端到端验证报告（2026-10-26）；覆盖场景 A 直连网关 + 静态/动态模式差异
tags: [blackbox, integration, e2e, gateway, feat-011, feat-014]
---

# FEAT-011/014 - agent 接入网关端到端部署验证测试设计

> 验证 agent 在直连状态下接入网关后是否正常工作。接入网关包括 A2A 协议中的**静态模式**与**动态模式**，
> 主要表现为 agent 启动参数（Spring profile）的不同。
> 依据：开发侧端到端验证报告 `FEAT-011-014-端到端部署验证报告-直连网关与总线模式.md`（2026-10-26）。

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| Feature | `develop/02-features/FEAT-011-client-invocation-route-forwarding.md`（PR #370，`updated: 2026-10-24`） |
| L2 | `develop/03-architecture/L2-Low-Level-Design/agent-gateway/Feat-Func-011-client-invocation-route-forwarding.md` |
| 开发侧端到端报告 | `FEAT-011-014-端到端部署验证报告-直连网关与总线模式.md`（2026-10-26，场景 A/B 全部真实进程验证通过） |
| 前序报告 | `FEAT-048-062-端到端真实部署验证报告-直连模式-nacos后端.md`（2026-10-22，直连模式 21 项全过） |
| acceptance 仓 | `main`；本文为用例设计与落地映射 |

### 1.2 范围

本方案验证 **agent 接入网关后的端到端正确性**，覆盖两条路径 + 三种接入模式：

| 场景 | 路径 | 核心验证点 |
|---|---|---|
| **场景 A：直连网关模式** | Client → agent-gateway（`--gateway.path-mode=direct`）→ RDC → Runtime | 治理面、发现端点、路径入口一致性、路由行为、路由降级、R2R 全链闭环 |

| 模式 | Spring profile | 接入网关方式 |
|---|---|---|
| **静态模式** | `hub-static` / `leaf-static` | `remote-agents` 静态声明网关路径地址，静态拉卡落在网关发现端点 |
| **动态模式** | `hub-dynamic` / `leaf-dynamic` | 注册到 RDC，D1/D4 解析实例直连转发 |
| **混合模式** | `hub-hybrid` | CatalogArbiter 仲裁，动态遮静态 |

### 1.3 明确不测

- CancelTask 正向转发（Feature 标为 OUT，只保留入口拒绝探针 A3）
- 创建跨实例安全重发/幂等（后续 DFX 承接）
- RDC 产品本身（FEAT-048/062 承接）
- Agent 模型/工具/记忆/知识库质量
- 多 Gateway 副本共享 owner 索引
- 精确 TTL 数值（只验行为级可定位性）

## 2. 部署拓扑

### 2.1 场景 A：直连网关模式

```text
基础设施：
  nacos-ai-e2e 容器 :8848    RDC 后端（注册数据面）
  rdc-pg 容器       :5433    RDC PostgreSQL

进程（5 个，全部 java -jar）：
  RDC      :8092   agent-rdc-0.1.3.jar          --openjiuwen.registry.backend=nacos
  gateway  :18082  agent-gateway-demo-0.1.2.jar --gateway.path-mode=direct
  hub-R2R  :18091  direct-e2e-demo-0.1.0.jar    --spring.profiles.active=hub-static
  hotel-A  :18093  direct-e2e-demo-0.1.0.jar    --spring.profiles.active=leaf-dynamic
  hotel-B  :18094  direct-e2e-demo-0.1.0.jar    --spring.profiles.active=leaf-dynamic
```

**关键部署纪律**：
- hub 选 `hub-static` 而非 `hub-hybrid`：hybrid 的动态目录会遮蔽静态同名声明，导致 hotel 绕过网关直连
- `E2E_HOTEL_URL` 指向 `:18082/a2a/agents/travel-hotel`（网关路径入口），不是 `:18093`（直连 hotel）
- hotel-A/B 用 `leaf-dynamic` 注册到 RDC，供 D1 双实例轮转
- `outbound-headers.Authorization: Bearer e2e-gateway-token`：指向网关路径地址的出站调用携带网关凭据

### 2.2 证据与 Oracle 约束

| 证据 | 用途 |
|---|---|
| Gateway HTTP/SSE 输出 | 状态码、media type、快照字段、终态 message |
| 网关卡 JSON | `supportedInterfaces` 指向网关路径、`preferredTransport=JSONRPC` |
| RDC D1/D2 响应 | 实例列表 ONLINE、卡 `{name, rawJson}` 包裹解包 |
| 进程日志 `A2A NEW task` | 双实例落点分布 |
| Surefire/Allure XML | 结果归档 |

约束：协议层错误（VALIDATION_*、ROUTE_*）以 HTTP 200 + JSON-RPC error 承载；仅传输层异常（AUTH 401、坏 JSON 400、超限 413、root 无候选 503）映射非 200。

## 3. 用例总矩阵

### 3.1 场景 A：直连网关模式（A/B/C/D/E/F 六组）

| ID | 用例 | 类型 | 优先级 | 自动化状态 | 覆盖 |
|---|---|---|---|---|---|
| `F011-A1` | 无凭据 401 | contract | P0 | implemented | AUTH_MISSING |
| `F011-A2` | 坏 Bearer 401 | contract | P0 | implemented | AUTH_INVALID |
| `F011-A3` | CancelTask 拒绝 | contract | P0 | implemented | VALIDATION_METHOD |
| `F011-A4` | 非法 JSON 400 | contract | P0 | implemented | 传输层错误 |
| `F011-A5` | GetTask 缺 taskId | contract | P0 | implemented | VALIDATION_TASK_ID |
| `F011-A6` | create 缺 agentId | contract | P0 | implemented | VALIDATION_AGENT_ID |
| `F011-A7` | agentId 空串 | contract | P1 | implemented | 同 A6 |
| `F011-A8` | 超大 payload 413 | contract | P0 | implemented | PAYLOAD_TOO_LARGE |
| `F011-B1` | 网关卡读取 | contract | P0 | implemented | 发现端点 |
| `F011-B2` | 改写幂等 | contract | P0 | implemented | 卡 md5 一致 |
| `F011-B3` | 未知 agentId 404 | contract | P0 | implemented | 防枚举 |
| `F011-C1` | 路径入口正常调用（3 变体） | blackbox | P0 | implemented | 路径入口闭环 |
| `F011-C2` | 声明与路径不一致 | contract | P0 | implemented | VALIDATION_AGENT_ID |
| `F011-C3` | 未知路径 agent | contract | P0 | implemented | ROUTE_NO_CANDIDATES |
| `F011-D1` | root 同步调用（中文） | blackbox | P0 | implemented | TASK_STATE_COMPLETED |
| `F011-D2` | GetTask 查询 | blackbox | P0 | implemented | 任务态返回 |
| `F011-D3` | sticky 续传 | blackbox | P0 | implemented | 同 taskId resume |
| `F011-D4` | 流式 SSE | blackbox | P0 | implemented | 12 帧完整序列 |
| `F011-D5` | 终态订阅 | blackbox | P0 | implemented | 终态帧 |
| `F011-D6` | HITL 中断续传链 | blackbox | P0 | implemented | 三条腿中断传播 |
| `F011-D7` | 双实例分布 | blackbox | P1 | implemented | 6 连发落点 |
| `F011-E1` | 无可路由候选 503 | contract | P0 | implemented | ROUTE_NO_CANDIDATES |
| `F011-E2a` | 任务归属未知 | contract | P0 | implemented | ROUTE_OWNERSHIP_UNKNOWN |
| `F011-E2b` | 对照验证 | blackbox | P0 | implemented | TASK_STATE_COMPLETED |
| `F011-F0` | R2R 前置核验 | blackbox | P0 | implemented | D1/D2/网关卡 |
| `F011-F1` | R2R 全链闭环 | e2e | P0 | implemented | TASK_STATE_COMPLETED + LLM |
| `F011-CB1` | CALLBACK 入口转发 | contract | P1 | implemented | push-notifications/callback 原样转发 |
| `F011-CB2` | CALLBACK 鉴权 | contract | P1 | implemented | 401/413/503/502 |
| `F011-D8` | 加权分布统计 | blackbox | P1 | implemented | 双实例二项分布 |
| `F011-D9` | Namespace 隔离 | blackbox | P1 | implemented | 跨租户不交叉 |
| `F011-D10` | TTL 收敛 | blackbox | P1 | implemented | 下线 TTL 内收敛 |
| `F011-D11` | 心跳保活 | blackbox | P1 | implemented | 跨 TTL 窗口可路由 |
| `F011-D12` | 候选耗尽 | blackbox | P0 | implemented | DIRECT_TRANSPORT_UNKNOWN |
| `F011-D13` | Scale-out 扩容 | blackbox | P1 | implemented | 新实例 TTL 内可路由 |
| `F011-D14` | Scale-in 缩容 | blackbox | P1 | implemented | 下线 TTL 内收敛 |
| `F011-D15` | Failover 连接拒绝 | blackbox | P1 | implemented | 死实例 failover |
| `F011-D16` | 首事件锁定 | blackbox | P1 | implemented | 流式首帧锁定实例 |
| `F011-D17` | 目录变更传播 | blackbox | P1 | implemented | 卡更新后新卡可见 |
| `F011-G1` | card-delete 停止路由 | blackbox | P1 | implemented | 注销后 503 |
| `F011-G2` | routeHandle 失效重发现 | blackbox | P1 | implemented | 全量注销后重注册 |
| `F011-G3` | routeHandle 缓存命中 | blackbox | P2 | implemented | 连续 5 次成功 |
| `F011-G4` | 无实例无静态兜底 | blackbox | P1 | implemented | 503 ROUTE_NO_CANDIDATES |
| `F011-G5` | 心跳保活实例查询 | blackbox | P2 | implemented | 跨 TTL 窗口仍可查 |
| `F011-G6` | RDC 不可用缓存续服 | blackbox | P1 | implemented | 缓存路由 |
| `F011-G7` | 转发超时配置生效 | blackbox | P1 | implemented | 慢实例超时 |
| `F011-G8` | streaming=false 降级 | blackbox | P2 | implemented | 同步降级 |
| `F011-G9` | 协议错误不 failover | contract | P1 | implemented | 400 无 failover |
| `F011-G10` | 业务失败不 failover | contract | P1 | implemented | JSON-RPC error |
| `F011-G11` | INPUT_REQUIRED 不 failover | blackbox | P1 | implemented | 中断不切换 |
| `F011-G12` | 3:1 加权分布 | blackbox | P1 | implemented | 权重比分布 |
| `F011-G13` | 首事件前超时 failover | blackbox | P1 | implemented | 死实例 failover |
| `F011-G14` | Namespace 变更不污染 | blackbox | P1 | implemented | 不交叉影响 |
| `F011-G15` | 进程崩溃 TTL 收敛 | blackbox | P1 | implemented | 崩溃后收敛 |
| `F011-G16` | 同 agentId 不误删 | blackbox | P1 | implemented | 不交叉删除 |
| `F011-G17` | 跨租户拒绝 | blackbox | P1 | implemented | 503 不可达 |
| `F011-G18` | 路径入口多身份隔离 | blackbox | P1 | implemented | GetTask 隔离 |
| `F011-BOUNDARY` | message/stream 不存在 | contract | P2 | implemented | 400 VALIDATION_METHOD |

### 3.2 静态/动态/混合模式差异（M1-M5）

| ID | 场景 | 类型 | 优先级 | 自动化状态 | 覆盖 |
|---|---|---|---|---|---|
| `F011-M1` | 静态模式接入网关（组合 1） | e2e | P0 | to-implement | hub-static + leaf-static，走网关 |
| `F011-M2` | 动态模式接入网关（组合 2） | e2e | P0 | to-implement | hub-static + leaf-dynamic，走网关 |
| `F011-M3` | 混合模式-动态遮蔽（组合 3） | e2e | P1 | to-implement | hub-hybrid + leaf-dynamic，**不走网关**（负向） |
| `F011-M3b` | 混合模式-静态保留（组合 4） | e2e | P1 | to-implement | hub-hybrid + leaf-static，走网关（静态保留） |
| `F011-M4` | 升级窗不回静态 | blackbox | P1 | to-implement | D1 空 + 显式失败 |
| `F011-M5` | RDC 不可用降级 | blackbox | P1 | to-implement | 静态兜底 |

## 4. 详细用例

### 4.1 A 组：治理面

#### F011-A1 无凭据

- **Given**：Gateway 运行中，`gateway.test-credential.token=e2e-gateway-token`
- **When**：`POST /a2a` 无 Authorization 头
- **Then**：HTTP **401**，`{"code":"AUTH_MISSING","message":"Missing Authorization"}`
- **FAIL 判定**：返回 200 或其他错误码；响应含 endpoint/routeHandle

#### F011-A2 坏 Bearer

- **Given**：Gateway 运行中
- **When**：`POST /a2a` + `Authorization: Bearer wrong-token`
- **Then**：HTTP **401**
- **FAIL 判定**：返回 200

#### F011-A3 CancelTask 拒绝

- **Given**：Gateway 运行中，CancelTask 在白名单外
- **When**：`POST /a2a` + `{"method":"CancelTask",...}` + 有效 Bearer
- **Then**：HTTP **200** + JSON-RPC error `-32600`，`VALIDATION_METHOD: "Method not supported: CancelTask"`
- **FAIL 判定**：返回 404/501；或实际转发到 Runtime

#### F011-A4 非法 JSON

- **Given**：Gateway 运行中
- **When**：`POST /a2a` + 报文体非 JSON + 有效 Bearer
- **Then**：HTTP **400**
- **FAIL 判定**：返回 200

#### F011-A5 GetTask 缺 params.id

- **Given**：Gateway 运行中
- **When**：`POST /a2a` + `{"method":"GetTask"}` + 有效 Bearer
- **Then**：HTTP 200 + `VALIDATION_TASK_ID: "params.id is required for GetTask"`
- **FAIL 判定**：返回 400；或实际转发到 Runtime

#### F011-A6 create 缺 agentId

- **Given**：Gateway 运行中
- **When**：`POST /a2a` + `{"method":"SendMessage","params":{...}}`（无 `metadata.agentId`）+ 有效 Bearer
- **Then**：HTTP 200 + `-32602` `VALIDATION_AGENT_ID: "agentId is required for create-type requests; obtain the agent path entry url via the gateway discovery endpoint"`
- **FAIL 判定**：返回 400；或默认选某个 agent

#### F011-A7 agentId 空串

- **Given**：Gateway 运行中
- **When**：`POST /a2a` + `metadata.agentId=""`
- **Then**：同 A6
- **FAIL 判定**：与 A6 行为不一致

#### F011-A8 超大 payload

- **Given**：Gateway 运行中，payload 上限 65536 B
- **When**：`POST /a2a` + 内联报文 66188 字节
- **Then**：HTTP **413** + `PAYLOAD_TOO_LARGE: "inline payload exceeds 65536 bytes (UTF-8): 66188; use payloadRef for larger payloads"`
- **FAIL 判定**：返回 200；或实际转发到 Runtime

### 4.2 B 组：发现端点

#### F011-B1 网关卡读取

- **Given**：Gateway 运行中，`gateway.discovery.auth-required=false`，travel-hotel 已注册到 RDC
- **When**：`GET /a2a/agents/travel-hotel/.well-known/agent-card.json`（匿名）
- **Then**：HTTP 200，卡 JSON；`supportedInterfaces` 指向网关路径 `{gw}/a2a/agents/travel-hotel`、`preferredTransport=JSONRPC`
- **D2 包裹归一断言**：RDC D2 返回 `{name, rawJson}` 包裹，网关 unwrap 后改写；响应体不得出现 `{name, rawJson}` 包裹结构（缺陷 #4 修复后的行为）
- **FAIL 判定**：卡内 url 指向 Runtime 直连地址；`protocolBinding` 缺失；返回 RDC `{name, rawJson}` 包裹未解包

#### F011-B2 改写幂等

- **Given**：B1 已执行一次
- **When**：二次读卡
- **Then**：两次卡体 md5 一致
- **FAIL 判定**：md5 不一致（改写链叠加前缀）

#### F011-B3 未知 agentId

- **Given**：Gateway 运行中
- **When**：`GET /a2a/agents/no-such-agent/.well-known/agent-card.json`
- **Then**：HTTP **404**
- **FAIL 判定**：返回 200 空卡；或错误消息泄露存在性信息

### 4.3 C 组：路径入口一致性

#### F011-C1 路径入口正常调用（3 变体）

- **Given**：Gateway 运行中，travel-hotel 已注册
- **When**：`POST /a2a/agents/travel-hotel` + SendMessage，三个变体：
  - c1：`metadata.agentId=travel-hotel`（声明一致）
  - c1b：无 `metadata.agentId`（声明缺失）
  - c1c：`params.agentId=travel-hotel`（声明于 params）
- **Then**：均 HTTP 200 任务创建成功
- **FAIL 判定**：声明缺失被拒绝；声明于 params 被忽略

#### F011-C2 声明与路径不一致

- **Given**：Gateway 运行中
- **When**：`POST /a2a/agents/travel-hotel` + `metadata.agentId="travel-trip"`
- **Then**：HTTP 200 + `-32602` `VALIDATION_AGENT_ID: "metadata.agentId does not match the path agentId"`
- **FAIL 判定**：实际转发到 travel-trip

#### F011-C3 未知路径 agent

- **Given**：Gateway 运行中
- **When**：`POST /a2a/agents/no-such-agent`
- **Then**：HTTP 200 + `-32603` `ROUTE_NO_CANDIDATES: "No routable instance for agent no-such-agent"`（404 归一为空候选，防枚举）
- **FAIL 判定**：返回 404（泄露存在性）

### 4.4 D 组：网关路由行为

#### F011-D1 root 同步调用（中文报文）

- **Given**：Gateway 运行中，travel-hotel 双实例 ONLINE
- **When**：`POST /a2a` + SendMessage + `metadata.agentId=travel-hotel` + 中文 parts（UTF-8 文件投递）
- **Then**：HTTP 200 `TASK_STATE_COMPLETED`，中文往返无损
- **FAIL 判定**：中文乱码；或任务失败
- **代码落点**：`GatewayDirectBlackboxTest`

#### F011-D2 GetTask 查询

- **Given**：D1 已创建 taskId
- **When**：`POST /a2a` + `{"method":"GetTask","params":{"id":taskId}}`
- **Then**：HTTP 200 返回任务态
- **代码落点**：`GatewayDirectBlackboxTest`

#### F011-D3 sticky 续传

- **Given**：D1 已创建 taskId（owner = hotel-A 或 B）
- **When**：`POST /a2a` + SendMessage + 同 taskId + 新 messageId
- **Then**：HTTP 200，续传到原 owner
- **FAIL 判定**：续传到另一实例
- **代码落点**：`GatewayDirectBlackboxTest`

#### F011-D4 流式 SSE

- **Given**：Gateway 运行中
- **When**：`POST /a2a` + `SendStreamingMessage` + `metadata.agentId=travel-hotel`
- **Then**：SSE 12 帧完整序列（SUBMITTED→WORKING→artifactUpdate→chunk×N→COMPLETED）
- **FAIL 判定**：帧聚合；缺 SUBMITTED/COMPLETED
- **代码落点**：`GatewayDirectBlackboxTest`

#### F011-D5 终态订阅

- **Given**：D1 已创建 taskId 并已到终态
- **When**：`POST /a2a` + `SubscribeToTask` + taskId
- **Then**：HTTP 200 终态帧
- **代码落点**：`GatewayDirectBlackboxTest`

#### F011-D6 HITL 中断续传链

- **Given**：Gateway 运行中，hub-R2R（mainplan + trip 同进程）
- **When**：三条腿分别触发 INPUT_REQUIRED → 续传：
  - root → mainplan
  - mainplan → hotel（走网关路径入口）
  - hotel 直入
- **Then**：三条腿均 200，中断传播与续传符合预期
- **FAIL 判定**：网关腿中断传播失败

#### F011-D7 双实例分布

- **Given**：travel-hotel 双实例 ONLINE（hotel-A :18093 + hotel-B :18094，等权 weight=100）
- **When**：root 入口对 travel-hotel 连发 6 次 SendMessage
- **Then**：6/6 COMPLETED；按实例落点分布（如 4:2），属等权轮转正常波动
- **FAIL 判定**：6 次全落同一实例（轮转失效）

### 4.5 E 组：路由降级

#### F011-E1 无可路由候选

- **Given**：Gateway 运行中，unknown-agent 未注册
- **When**：`POST /a2a` + SendMessage + `metadata.agentId=unknown-agent`
- **Then**：HTTP **503** + `-32603` `ROUTE_NO_CANDIDATES`
- **FAIL 判定**：返回 200；或泄露拓扑

#### F011-E2a 任务归属未知

- **Given**：Gateway 运行中，taskId 不在粘滞索引中
- **When**：`POST /a2a` + GetTask + `params.id=unknown-taskId`
- **Then**：HTTP 200 + `-32055` `ROUTE_OWNERSHIP_UNKNOWN: "cannot establish the owner of task ..."`
- **FAIL 判定**：伪造 Task 返回；或泄露拓扑

#### F011-E2b 对照验证

- **Given**：E2a 的 taskId 实际存在（经路径入口创建）
- **When**：经正确路径入口 `POST /a2a/agents/travel-hotel` + GetTask
- **Then**：`TASK_STATE_COMPLETED`
- **FAIL 判定**：路径入口也查不到

### 4.6 F 组：R2R 经网关全链闭环

#### F011-F0 前置核验

- **Given**：RDC/gateway/hub-R2R/hotel-A/hotel-B 全部启动
- **When**：查 RDC D1 `GET /v2/agents/travel-hotel/instances`；D2 读卡；网关卡读取
- **Then**：D1 双实例 ONLINE；D2 返回卡（`{name, rawJson}` 解包后）；网关卡改写正确（`supportedInterfaces` 指向网关路径）
- **FAIL 判定**：实例不 ONLINE；网关卡未改写

#### F011-F1 R2R 全链

- **Given**：F0 通过
- **When**：用户 → hub(mainplan) → 同进程 trip → **网关路径入口** → hotel-A/B
- **Then**：`TASK_STATE_COMPLETED` + 真实 LLM 推理 + 中文无损；网关审计 `principalId=e2e-principal outcome=PASSED pathAgentId=travel-hotel`
- **FAIL 判定**：`REMOTE_UNAVAILABLE`；网关 `outcome=REJECTED`
- **代码落点**：`ClientGatewayRuntimeReconnectIT`

### 4.6b CB 组：CALLBACK 入口（推送回调代理）

> PR #886 新增第四入口 `PushNotificationCallbackController`，开发报告未验证。列为 P1，待开发确认回调触发方式后补充。

#### F011-CB1 CALLBACK 入口转发

- **Given**：Gateway 运行中，travel-hotel 已注册且有活跃 taskId（粘滞索引命中）
- **When**：`POST /a2a/push-notifications/callback/travel-hotel` + 回调报文（含 taskId）
- **Then**：网关原样转发至归属 runtime；HTTP 状态语义（非 JSON-RPC error 信封）
- **FAIL 判定**：转发到非 owner 实例；或回调报文被修改

#### F011-CB2 CALLBACK 鉴权与降级

- **Given**：Gateway 运行中
- **When**：分别测试无凭据 401 / 超限 413 / 上游不可用 503 / 上游失败 502
- **Then**：对应 HTTP 状态码（CALLBACK 维持 HTTP 状态语义，非 JSON-RPC error 信封）
- **FAIL 判定**：返回 200 + JSON-RPC error（CALLBACK 面 不走双错误面）

### 4.8 M 组：静态/动态/混合模式差异（4 种排列组合）

> 4 种排列组合 = hub 模式（static/hybrid）× leaf 注册方式（static/dynamic）。
> 核心验证点：`remote-agents` URL 指向网关路径时，哪些组合走网关、哪些绕过网关。
> hub-dynamic（纯动态）不走网关（RDC D1/D4 解析直连地址），不在"走网关"测试范围内。

**核心配置参数对照**（与开发对齐的两个关键调节点）：

| 组合 | `remote-agents[].url`<br>（hub 侧静态声明） | `a2a.public-url`<br>（leaf 侧注册到 RDC） | RDC 静态注入<br>（leaf-static 时） | 走网关? |
|---|---|---|---|---|
| 1 hub-static + leaf-static | 网关路径 `{gw}/a2a/agents/{agentId}` | 不生效（不自注册） | leaf 直连地址 `http://127.0.0.1:{port}` | ✓ |
| 2 hub-static + leaf-dynamic | 网关路径 `{gw}/a2a/agents/{agentId}` | leaf 直连地址 `http://127.0.0.1:{port}` | — | ✓ |
| 3 hub-hybrid + leaf-dynamic | 网关路径（被动态遮蔽，**不生效**） | leaf 直连地址（动态发现用此地址） | — | ✗ |
| 4 hub-hybrid + leaf-static | 网关路径（静态保留，生效） | 不生效（不自注册） | — | ✓ |

> **`remote-agents[].url`**（如 `E2E_HOTEL_URL`）：hub 静态声明的远端 agent 调用地址。走网关时填网关路径 `{gw}/a2a/agents/{agentId}`；直连模式填 leaf 直连地址。4 种组合中均填网关路径，但组合 3 被动态目录遮蔽不生效。
>
> **`a2a.public-url`**（如 `E2E_PUBLIC_URL`）：agent 注册到 RDC 时声明的自身公开地址。leaf-dynamic 时填 leaf 直连地址（网关通过 RDC D1/D4 查到此地址后转发）；leaf-static 时不自注册，此参数不生效。
>
> **走网关 vs 不走网关的决定因素**：不是这两个参数本身的值，而是 hub discovery 是否开启 + leaf 是否自注册到 RDC 的组合：
> - hub discovery 关 → remote-agents 是唯一目录 → 走网关（组合 1/2）
> - hub discovery 开 + leaf 自注册 → 动态遮蔽静态 → 不走网关（组合 3）
> - hub discovery 开 + leaf 不自注册 → 动态目录找不到 → 静态保留 → 走网关（组合 4）

| # | hub 模式 | leaf 模式 | remote-agents→网关 | 走网关? | 用例 |
|---|---|---|---|---|---|
| 1 | hub-static | leaf-static | ✓ | ✓ 走网关 | M1 |
| 2 | hub-static | leaf-dynamic | ✓ | ✓ 走网关 | M2 |
| 3 | hub-hybrid | leaf-dynamic | ✓ | ✗ 不走网关（动态遮蔽静态） | M3 |
| 4 | hub-hybrid | leaf-static | ✓ | ✓ 走网关（静态保留） | M3b |

#### F011-M1 静态模式接入网关（组合 1：hub-static + leaf-static）

- **Given**：hub(hub-static) + hotel-A(leaf-static)；`remote-agents` 填网关路径地址 `{gw}/a2a/agents/travel-hotel`；RDC `rdc-instances.yml` 静态注入 hotel 实例（`target-agent → http://127.0.0.1:18093`），RDC 拉卡并发布
- **When**：执行 D1-D5 全链路（同步/流式/GetTask/HITL/Subscribe），均经网关路径入口
- **Then**：静态拉卡落在网关发现端点；RDC D1 可查到静态注入的实例；网关 D1/D4 路由到 hotel；全链通过
- **FAIL 判定**：静态拉卡落在 Runtime 直连地址（绕过网关）；D1 查不到实例
- **部署参数（agent 侧）**：`registry.registration.enabled=false`、`discovery.enabled=false`（agent 不自注册，由 RDC 静态注入）
- **部署参数（RDC 侧）**：`rdc.deployment-discovery.instances[]` 静态注入实例地址
- **走网关原理**：hub discovery 全关，remote-agents 是唯一目录来源，URL 指向网关路径 → 所有调用走网关

#### F011-M2 动态模式接入网关（组合 2：hub-static + leaf-dynamic）

- **Given**：hub(hub-static) + hotel-A/B(leaf-dynamic)；hotel 自注册到 RDC（A1 API）；hub 的 `remote-agents` 仍填网关路径地址
- **When**：执行 D1-D5 全链路，均经网关路径入口
- **Then**：hotel A1 自注册到 RDC；网关 D1 查实例 → D4 解析 route-handle → 直连转发到 hotel；全链通过
- **FAIL 判定**：D1 查不到实例；route-handle 解析失败；hotel 未注册
- **部署参数（agent 侧）**：`registry.registration.enabled=true`、`discovery.enabled=true`、`call-address-policy=resolve`（agent 自注册到 RDC）
- **注意**：hub 侧始终用 `hub-static`（remote-agents 填网关路径地址），确保调用走网关而非直连 hotel
- **走网关原理**：hub discovery 全关，remote-agents 是唯一目录来源，URL 指向网关路径 → 所有调用走网关

#### F011-M3 混合模式-动态遮蔽（组合 3：hub-hybrid + leaf-dynamic，**不走网关**）

- **Given**：hub(hub-hybrid)，`discovery.enabled=true` + `remote-agents` 静态声明 trip/hotel（URL 指向网关路径 `{gw}/a2a/agents/travel-hotel`）；hotel-A/B(leaf-dynamic) 自注册到 RDC
- **When**：hub 调用 hotel
- **Then**：CatalogArbiter 条目级仲裁：动态条目存在即遮蔽同名静态声明；`candidates=2` 为动态 A+B（RDC D1/D4 解析的直连地址，非网关路径）
- **FAIL 判定**：静态声明未被遮蔽；候选混入网关路径地址
- **关键验证**：**调用绕过网关直连 hotel**（动态目录解析的地址是 hotel 直连地址 `http://127.0.0.1:18093`，非网关路径 `http://127.0.0.1:18082/a2a/agents/travel-hotel`）。此为负向验证——证明 hub-hybrid + leaf-dynamic 不走网关
- **对应开发报告**：FEAT-048-062 S3-2（动态遮蔽静态）+ FEAT-011-014 §2.2.3（"hub-hybrid 的动态目录会遮蔽静态同名声明导致 hotel 绕过网关直连"）

#### F011-M3b 混合模式-静态保留（组合 4：hub-hybrid + leaf-static，**走网关**）

- **Given**：hub(hub-hybrid)，`discovery.enabled=true` + `remote-agents` 静态声明 static-echo（URL 指向网关路径 `{gw}/a2a/agents/static-echo`）；static-echo(leaf-static) **不自注册到 RDC**（`registration.enabled=false`、`discovery.enabled=false`）
- **When**：hub 调用 static-echo
- **Then**：动态目录查不到 static-echo（RDC D1 无此实例）→ CatalogArbiter 保留静态声明 → 调用走网关路径 → 网关 D1/D4 路由到 static-echo
- **FAIL 判定**：动态目录找不到时回退报错而非用静态声明；或静态声明被错误遮蔽
- **关键验证**：**调用走网关**（静态声明 URL 指向网关路径，动态目录找不到 → 静态保留 → 走网关）
- **对应开发报告**：FEAT-048-062 S3-1（static-echo 走静态配置委派成功）

#### F011-M4 升级窗不回静态

- **Given**：hub(hub-hybrid)，hotel 动态实例全部注销
- **When**：查 D1 + D2 + 调用 hotel
- **Then**：D1 `instances=[]` + D2 卡仍可读；调用 hotel → 显式失败 `no live instance candidate`，不回静态声明
- **FAIL 判定**：回退到静态声明

#### F011-M5 RDC 短时不可用降级

- **Given**：hub(hub-hybrid)，static-echo 静态声明存在
- **When**：kill RDC → 调用 static-echo
- **Then**：`WARN Unified registry service unavailable, serving last known dynamic entries`；static-echo 委派 COMPLETED
- **FAIL 判定**：异常穿透到用户

### 4.9 D 组补充：多实例路由行为（D8-D17）

> D8-D17 覆盖 FEAT-062 联动场景：加权分布、Namespace 隔离、TTL 收敛、心跳保活、候选耗尽、扩缩容、failover、首事件锁定、目录变更。多数需双实例部署（`AGENT_BUS_SOURCE_RUNTIME_URL_2`）。
> **代码落点**：`GatewayDirectBlackboxTest`

#### F011-D8 加权分布统计

- **Given**：travel-hotel 双实例 ONLINE（等权 weight=100）
- **When**：root 入口连发 60 次 SendMessage
- **Then**：≥2 个不同 taskId（双实例均承接请求）；大多数请求成功
- **FAIL 判定**：全部落同一实例（轮转失效）；成功率 < 50%

#### F011-D9 Namespace 隔离

- **Given**：source-agent（tenant A）+ source-agent-ns2（tenant B）分别注册
- **When**：经网关调用两个 agent + 查 RDC D1 验证实例列表
- **Then**：两 agent 各自可路由；RDC D1 实例列表不交叉（ns1 不含 ns2 实例）
- **FAIL 判定**：跨 namespace 实例泄漏

#### F011-D10 TTL 收敛

- **Given**：双实例 ONLINE，已验证双 taskId 落点
- **When**：注销第二实例（by instanceId），等待 TTL（8s）
- **Then**：注销后第一实例仍可路由（200 + COMPLETED）
- **FAIL 判定**：注销后全部不可路由（DEV-007 card URL 被覆写未修复）

#### F011-D11 心跳保活

- **Given**：单实例 ONLINE
- **When**：首次调用成功 → 等待 8s（超过默认 TTL 5s）→ 再次调用
- **Then**：两次均 200 + COMPLETED（心跳续期使实例跨 TTL 窗口仍可路由）
- **FAIL 判定**：第二次 503（心跳未续期）

#### F011-D12 候选耗尽

- **Given**：注册 ephemeral-agent 到不可达端口 `:59999`
- **When**：经网关调用 ephemeral-agent
- **Then**：HTTP 200 + JSON-RPC error `-32053` `DIRECT_TRANSPORT_UNKNOWN`（retryable=false）；不泄漏 endpointUrl/routeHandle/taskId
- **FAIL 判定**：返回 503；或泄漏 taskId 值

#### F011-D13 Scale-out 扩容

- **Given**：单实例 ONLINE，8 次请求确认单一 taskId 集合
- **When**：注册第二实例 → 等待 TTL → 12 次请求
- **Then**：扩容后 taskId 集合 > 扩容前（新实例被网关发现并路由）
- **FAIL 判定**：扩容后 taskId 集合无增长

#### F011-D14 Scale-in 缩容

- **Given**：双实例 ONLINE，已验证双 taskId 落点
- **When**：注销第二实例 → 重新注册第一实例刷新 card URL → 等待 TTL
- **Then**：缩容后第一实例仍可路由（200）
- **FAIL 判定**：缩容后不可路由（DEV-007 未修复）

#### F011-D15 Failover 连接拒绝

- **Given**：live 实例 + dead 实例（不可达端口 `:59998`），等权
- **When**：连发 10 次 SendMessage
- **Then**：部分请求成功（网关 failover 从 dead 到 live）
- **FAIL 判定**：全部失败（无 failover）

#### F011-D16 首事件锁定

- **Given**：双实例 ONLINE
- **When**：发起流式请求（SendStreamingMessage）
- **Then**：200 + SSE 首帧存在（首事件锁定到一个实例，不切换）
- **FAIL 判定**：流式请求失败；或多实例切换

#### F011-D17 目录变更传播

- **Given**：读取当前网关卡 → 重新注册（version 升级到 2.0）→ 等待 TTL
- **When**：再次读取网关卡
- **Then**：卡仍指向网关路径（`/a2a/agents/{agentId}`），不暴露 Runtime 直连地址
- **FAIL 判定**：卡更新后指向 Runtime 直连地址

### 4.10 G 组：遗漏场景补充（G1-G18）

> G 组覆盖 PR#366 后增量场景：card-delete、routeHandle 刷新/缓存、静态兜底、心跳查询、RDC 缓存续服、转发超时、streaming 降级、协议/业务错误不 failover、加权分布、超时 failover、namespace 变更、进程崩溃、同 agentId 不误删、跨租户拒绝、多身份隔离。
> **代码落点**：`GatewayDirectBlackboxTest`

#### F011-G1 card-delete 后停止路由

- **Given**：source-agent 可路由
- **When**：从 RDC 注销实例 → 等待 TTL（8s）→ 再次调用
- **Then**：503 + `ROUTE_NO_CANDIDATES`（拓扑净化）
- **FAIL 判定**：注销后仍可路由

#### F011-G2 routeHandle 失效后重新发现

- **Given**：source-agent 可路由
- **When**：全量注销 → 等待 TTL → 重新注册（version 2.0）→ 等待 TTL → 调用
- **Then**：200 + `TASK_STATE_COMPLETED`（网关重新发现并路由）
- **FAIL 判定**：重新注册后仍 503

#### F011-G3 routeHandle 缓存命中

- **Given**：source-agent 可路由
- **When**：连续 5 次 SendMessage
- **Then**：5/5 均 200（routeHandle 缓存命中，不重复解析）
- **FAIL 判定**：部分请求失败

#### F011-G4 无实例注册的 agent 无静态兜底

- **Given**：ghost-agent 从未注册
- **When**：经网关调用 ghost-agent
- **Then**：503 + `ROUTE_NO_CANDIDATES`
- **FAIL 判定**：返回 200（错误兜底）

#### F011-G5 心跳保活实例查询验证

- **Given**：source-agent 单实例 ONLINE
- **When**：查 RDC D1 实例 → 等待 8s（超过 TTL）→ 再查 D1
- **Then**：两次查询均返回 ≥1 实例（心跳保活使实例持续可见）
- **FAIL 判定**：TTL 后实例消失

#### F011-G6 RDC 不可用时网关缓存继续服务

- **Given**：source-agent 可路由（网关已缓存 card/routeHandle）
- **When**：触发 RDC 故障（`AGENT_BUS_RDC_FAULT_URL`）→ 调用 source-agent
- **Then**：200 或 503（网关用缓存继续服务或降级）
- **FAIL 判定**：异常穿透到用户

#### F011-G7 网关转发超时配置生效

- **Given**：注册 slow-agent（慢响应实例，`AGENT_BUS_SLOW_RUNTIME_URL`）
- **When**：经网关调用 slow-agent
- **Then**：200/504/503（超时返回结构化错误）
- **FAIL 判定**：无限等待

#### F011-G8 streaming=false 时降级为同步

- **Given**：注册 sync-agent（卡声明 `streaming=false`）
- **When**：经网关 SendStreamingMessage 调用 sync-agent
- **Then**：200 或 503（streaming=false 时降级为同步）
- **FAIL 判定**：返回 SSE 但实例不支持流式

#### F011-G9 协议错误不触发候选切换

- **Given**：source-agent 可路由
- **When**：`POST /a2a` + `method=UnknownMethod`
- **Then**：400 + `VALIDATION_METHOD`（协议错误不 failover）
- **FAIL 判定**：返回 200 或触达 Runtime

#### F011-G10 业务失败不触发候选切换

- **Given**：source-agent 可路由
- **When**：SendMessage + 空文本
- **Then**：200/400 + 非 `ROUTE_NO_CANDIDATES`/`REMOTE_UNAVAILABLE`（业务级错误不 failover）
- **FAIL 判定**：触发 failover

#### F011-G11 INPUT_REQUIRED 不触发候选切换

- **Given**：hub-R2R 部署（`AGENT_BUS_HUB_R2R_URL`）
- **When**：SendMessage 触发 INPUT_REQUIRED
- **Then**：200 + 非 `REMOTE_UNAVAILABLE`/`ROUTE_NO_CANDIDATES`（中断状态不 failover）
- **FAIL 判定**：INPUT_REQUIRED 触发 failover

#### F011-G12 3:1 加权分布

- **Given**：双实例，A weight=100、B weight=33（3:1 比）
- **When**：连发 40 次 SendMessage
- **Then**：大多数成功（>20）；≥2 个不同 taskId（加权分布生效）
- **FAIL 判定**：全部落同一实例

#### F011-G13 首事件前超时触发 failover

- **Given**：live 实例 + dead 实例（`:59997`），等权
- **When**：连发 8 次 SendMessage
- **Then**：部分成功（dead 实例超时后 failover 到 live）
- **FAIL 判定**：全部失败

#### F011-G14 Namespace 变更不污染

- **Given**：source-agent 可路由
- **When**：注册 + 注销另一个 other-agent → 调用 source-agent
- **Then**：200 + result（其他 agent 的注册/注销不影响 source-agent 路由）
- **FAIL 判定**：source-agent 受其他 agent 变更影响

#### F011-G15 进程崩溃后 TTL 收敛

- **Given**：双实例（live + `:59996` dead）
- **When**：连发 10 次 SendMessage
- **Then**：部分成功（dead 实例经 TTL 收敛后请求落到 live）
- **FAIL 判定**：全部失败

#### F011-G16 同 agentId 不误删

- **Given**：双实例（A + B）同 agentId
- **When**：注销 A（by address）→ 查 RDC D1 → 调用
- **Then**：D1 仍含 B 实例；调用 200（同 agentId 另一副本不受影响）
- **FAIL 判定**：注销 A 导致 B 也消失

#### F011-G17 跨租户通过网关时拒绝

- **Given**：cross-tenant-agent 注册到 tenant-b（与网关 tenant 不同）
- **When**：经网关（tenant A）调用 cross-tenant-agent
- **Then**：503 + `ROUTE_NO_CANDIDATES`（跨租户不可达）
- **FAIL 判定**：返回 200（跨租户泄漏）

#### F011-G18 路径入口多身份任务隔离

- **Given**：source-agent + second-agent 均注册，经各自路径入口创建任务
- **When**：分别 GetTask 查询各自 taskId
- **Then**：两个 taskId 不同；各自 GetTask 返回 `TASK_STATE_COMPLETED`，无 `ROUTE_OWNERSHIP_UNKNOWN`
- **FAIL 判定**：GetTask 交叉（A 的 taskId 在 B 的 owner 查到或反之）

#### F011-BOUNDARY message/stream 方法不存在

- **Given**：source-agent 可路由
- **When**：`POST /a2a` + `method=message/stream`
- **Then**：400 + `VALIDATION_METHOD`（非白名单方法拒绝，不转发）
- **FAIL 判定**：返回 200 或触达 Runtime

## 5. 框架落点与执行

### 5.1 测试类与场景映射

| 测试类 | 场景 | 标签 | @Test 数 | 说明 |
|---|---|---|---|---|
| `GatewayDirectBlackboxTest` | A/B/C/D/E/G 组（集成层） | `feat-011, integration, blackbox` | 54 | 直连网关全量黑盒：A1-A8/B1-B3/C1-C3/D1-D17/E1-E2b/F1/G1-G18/BOUNDARY/CB1-CB2 |

### 5.2 SUT 选取

| 场景 | 角色 | 制品 | profile |
|---|---|---|---|
| A 直连网关 | 网关 | `agent-gateway-demo:0.1.2` | `--gateway.path-mode=direct` |
| A 直连网关 | hub | `direct-e2e-demo:0.1.0` | `hub-static` |
| A 直连网关 | hotel 叶子 | `direct-e2e-demo:0.1.0` | `leaf-dynamic` |
| A 直连网关 | RDC | `agent-rdc:0.1.3` | `--openjiuwen.registry.backend=nacos` |

### 5.3 执行命令

```powershell
# 集成层（需外部 DIRECT 栈：RDC + Gateway + Runtime）
.\mvnw.cmd -Dtest.env=openjiuwen -Dtest=GatewayDirectBlackboxTest test

# 按 tag 跑全部 FEAT-011
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=feat-011 test

# 流水线
./scripts/run-pipeline.sh --env openjiuwen --skip-provision -- -Dtest=GatewayDirectBlackboxTest
```

### 5.4 已知缺口

| # | 缺口 | 影响场景 | 处置 |
|---|---|---|---|
| 1 | 制品版本错位 | 全部 | yml 钉 0.1.0/0.1.1，m2 实际 0.1.2；需统一版本 |
| 2 | 接口语义断言已对齐 | E2a miss | PR #886 后所有 miss（GetTask/Subscribe/续跑）统一为 `ROUTE_OWNERSHIP_UNKNOWN(-32055)`，旧 `-32001`/`-32051` 分化已消除；断言按 `-32055` 对齐 |
| 3 | 双实例部署门禁 | D8/D13/G12/G15/G16 | 需 `AGENT_BUS_SOURCE_RUNTIME_URL_2` 环境变量 |
| 4 | RDC 故障门禁 | G6 | 需 `AGENT_BUS_RDC_FAULT_URL` |
| 5 | 慢实例门禁 | G7 | 需 `AGENT_BUS_SLOW_RUNTIME_URL` |
| 6 | hub-R2R 门禁 | D6/F1/G11 | 需 `AGENT_BUS_HUB_R2R_URL` |
| 7 | CALLBACK 门禁 | CB1/CB2 | 需 `AGENT_BUS_CALLBACK_TEST=true` |
| 8 | 跨租户凭据（仅一组） | 租户隔离 | 需产品补 `gateway.test-credentials[i].*` 列表 |

### 5.5 行为口径与断言规则

1. **网关 JSON-RPC 错误的 HTTP 承载**：协议层错误以 HTTP 200 + JSON-RPC error 承载；仅传输层异常映射非 200（AUTH 401、坏 JSON 400、超限 413、root 无候选 503）
2. **hub-static 选型理由**：hub-hybrid 的动态目录会遮蔽静态同名声明，hotel 走直连绕过网关；hub-static 保证 hotel 唯一地址 = 网关路径入口
3. **网关卡端点默认匿名**：静态拉卡不带凭据；调用面 JSON-RPC 才带 Bearer
4. **等权多实例分布**：D7 六连发可能 4:2 偏斜，属等权轮转正常波动
5. **流式 method 名**：`SendMessage`/`SendStreamingMessage`；`message/stream` 返回 -32601

## 6. 证据归档

每条用例的证据文件按开发报告格式归档到 `evidence/` 目录：
- 请求报文：`{id}-req.json`
- 响应报文：`{id}-resp.json` / `{id}-sse-raw.txt`
- 关键日志：`{id}-keylogs.txt`
- 部署命令：`deploy-process-cmdlines.txt`
- 日志目录：`logs/{rdc,gateway,hub,hotel-a,hotel-b}.out.log`
