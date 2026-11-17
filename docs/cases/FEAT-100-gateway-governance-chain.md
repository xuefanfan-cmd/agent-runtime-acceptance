---
feature_id: FEAT-100
feature_title: FEAT-100 网关四段门治理链
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: 网关 G1 材料→G2 身份→G3 白名单→G4 幂等四段门、流式透传/错误码、TC 注入、重放语义
status: runnable
owner: westmoreland
priority: P0
tags: [integration, feat-100, frs-064, unified-permission]
depends_on:
  - RDC/Gateway/Runtime 三组件部署（FRS-064 §4.1 拓扑）
  - Keycloak realm perm064 + PostgreSQL agent_rdc
  - 网关/Guard 探针脚本与 TC 构造（authflow-demo-context-secret）
related_docs:
  - FRS-064-端到端测试方案.md（FRS 台账 v1.29）
  - FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（192 用例源计划）
  - testplan-基线后观察记录-20261112.md（57 节执行证据）
---

# FRS-064 统一权限 — FEAT-100 网关四段门治理链（测试用例设计）

> **一句话**：网关 G1 材料→G2 身份→G3 白名单→G4 幂等四段门、流式透传/错误码、TC 注入、重放语义。本组 26 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-GW-01` | P0 | 两入口分类（100 V10） | 1.POST /a2a 2.POST /a2a/agents/agent-a 各一发合法 SendMessage | 两入口均正确解析 target 并判决（同态） | — |
| `SC-GW-02` | P0 | 必填两件+contextId 可选（AC-01）**两入口分别执行** | 1.缺 Authorization 2.缺 x-user-id 3.两件齐+无 contextId（每步在根入口与路径入 | 根入口=**401 扁平体** AUTH_MISSING；路径入口=**200+JSON-RPC error**（$.e | — |
| `SC-GW-03` | P0 | 空值/格式错（两入口分别执行） | 1.x-user-id 空串/空白 2.Basic 形态 Authorization | 根入口=401 AUTH_INVALID（malformed）；路径入口=200+JSON-RPC error（同码） | — |
| `SC-GW-04` | P0 | 判决拒绝的翻译（AC-36）**两入口分别执行** | 1.根入口 SendMessage 2.路径入口 SendMessage | 根入口=**HTTP 403** 扁平体 CALLER_NOT_AUTHORIZED；路径入口=**200+JSON-R | — |
| `SC-GW-05` | P1 | 错误双形态（L2-100 FaceMapping 口径） | 1.根入口 G1 拒绝 2.路径入口拒绝 | 根入口=401 扁平体；路径入口=200+JSON-RPC error（按入口的 FaceMapping 口径） | — |
| `SC-GW-06` | P0 | 同 messageId 重放**不缓存**（095 V11）**含跨入口等价重放 | 1.同 messageId 同载荷同入口重放 2.同用户同 messageId 的**等价请求换入口形态**重放（路径入 | 同入口重放=幂等返回且**重新判决**（决策数递增、不用缓存，**审计行 +N**）；**params 一致的跨入口重放 | — |
| `SC-GW-07` | P0 | 上下文签发（100 V08/AC-03） | 1.合法请求→捕获转发头 | 两段式 .none；七字段全；expiresAt=min(tokenExp,now+1800)；四头齐；token 原文 | 捕获器 |
| `SC-GW-08` | P1 | GetTask 异构（100 V11） | 1.GetTask | 走判决；OwnerIndex 按 task 绑定 | — |
| `SC-GW-09` | P2 | 子调用材料（123 A31） | 1.带受信头的请求 | 按子调用处理：材料齐备要求；不获额外权限 | — |
| `SC-GW-10` | P0 | 降级放行（100 V19：网关关闭+BYPASSED 运行日志） | 1.无材料请求 | 放行+审计 bypassed 标记；重复头仍拒 | — |
| `SC-GW-11` | P0 | RDC 不可达 fail-closed | 1.合法请求 | 503 RDC_UNAVAILABLE（-32603+retryable=true） | — |
| `SC-GW-12` | P1 | 错误码白名单 | 1.各拒绝场景 code 核对 | 仅白名单码；出现 AUTH_DISABLED 即 ISSUE | — |
| `SC-GW-13` | P1 | trace 贯通+错误体（traceId 贯通正主=SC-AUD-02） | 1.错误响应核对 | 带 traceId；503 带 retryable=true（**无 Retry-After=设计口径，偏差 24**） | — |
| `SC-GW-14` | P1 | OwnerIndex（100 V03/V11） | 1.GetTask 命中绑定→按原 Agent 判决 2.路径 agentId≠绑定→AUTH_INVALID 3.无  | 三态符合；转发目标=注册端点 | — |
| `SC-GW-15` | P1 | 方法覆盖矩阵（100 V10） | 1.同步/SSE/GetTask/Subscribe/恢复×两入口 | 每方法按当前模式判决；Card/回调不误套 invoke | — |
| `SC-GW-16` | P1 | SSE 按 expiresAt 截止（100 V15） | 1.流式→到 deadline | 关闭帧流+AUTH_EXPIRED 事件；重连重鉴 | — |
| `SC-GW-17` | P1 | 伪造受信头（L2-100 §2.4） | 1.Client 自带受信头 | 按子调用处理，不获额外权限 | — |
| `SC-GW-18` | P2 | RDC 响应核对（100 V06；REQ-006） | 1.requestId/目标/用户不匹配 | 503 RDC_UNAVAILABLE（回显校验） | 拦截桩 |
| `SC-GW-19` | P1 | BUS 模式防护（100 V22；BUS 面整体见 §2 非范围「Event-b | 1.授权开的 BUS 请求 | 503 BUS_PATH_UNSUPPORTED+启动 WARN | — |
| `SC-GW-20` | P1 | 幂等用户隔离（095 V11） | 1.异用户同 messageId | 不互相重放 | — |
| `SC-GW-21` | P2 | RDC 响应逐码翻译（L2-100 §5.4） | 1.各码逐条 | 逐码符合翻译表 | 拦截桩 |
| `SC-GW-22` | P2 | 构造失败不转发（AC-03/BR-02） | 1.构建/编码失败 | 不转发+错误面 | 注入桩 |
| `SC-GW-23` | P2 | V24 结果阶段审计故障（100 V24） | 1.结果审计失败 | 已执行事实保留/不重跑/SSE 按事件终止/关闭态日志失败非阻断 | DB 桩 |
| `SC-GW-24` | P2 | 单 Agent 部署形态转发（dev#2） | 1.转发到无 agentId 路径的 runtime | 按 Card 地址转发（部署形态兼容） | 单 agent 形态 |
| `SC-GW-25` | P1 | 流式响应透传（dev#5） | 1.鉴权开+流式请求 | 响应不被过滤器缓存（事件完整） | — |
| `SC-GW-26` | P1 | 判定顺序：G3 方法白名单先于 GA | 1.非法方法+伪造令牌 2.合法方法+伪造令牌 | 1.=VALIDATION_METHOD（方法错先报，**不进入 GA 判决**）；2.=AUTH_INVALID（L2 | — |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 26/26

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
