---
feature_id: FEAT-100
feature_title: 主链/子调用/审计贯通
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: GW→RDC→Runtime 主链、A→B/C 子调用委托链、审计三级（GW/RDC/Runtime）贯通
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

# FRS-064 统一权限 — 主链/子调用/审计贯通（测试用例设计）

> **一句话**：GW→RDC→Runtime 主链、A→B/C 子调用委托链、审计三级（GW/RDC/Runtime）贯通。本组 14 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-CHAIN-01` | P0 | ①Client 取令牌并发起（095 V01） | 1.取令牌 2.携 Bearer 发 SendMessage | 令牌有效；请求进入网关入口（非直连 Runtime） | 真 Keycloak |
| `SC-CHAIN-02` | P0 | ②G1 材料面校验 | 1.分别缺 Authorization / x-user-id 各一发 | 401 AUTH_MISSING；材料齐备才进入后续 | — |
| `SC-CHAIN-03` | P0 | ③G2~G4 幂等/白名单 | 1.重放同 messageId 2.非白名单方法一发 | 重放=重新判决；非白名单=VALIDATION_METHOD | — |
| `SC-CHAIN-04` | P0 | ④GA 提交判决（两段） | 1.合法请求→观察 RDC 判决 | allAllowed=true；admission+agentPermission 两段 PASS | — |
| `SC-CHAIN-05` | P0 | ⑤GT 签发上下文 | 1.观察转发头 | 两段式 .none+七字段+expiresAt=min(tokenExp,now+1800) | 捕获器 |
| `SC-CHAIN-06` | P0 | ⑥GC 路由并转发 | 1.观察转发目标与四头 | 目标=注册端点；四头齐（tc/x-origin/x-user-id/traceparent） | — |
| `SC-CHAIN-07` | P0 | ⑦Runtime 入站本地校验 | 1.合法请求入站 | 验签+时效+提取绑定+自报一致性全过 | — |
| `SC-CHAIN-08` | P0 | ⑦b ②b RDC 复核 | 1.观察 runtime→RDC 调用 | 每请求一次、不缓存；valid=true 继续；不确认=401 | — |
| `SC-CHAIN-09` | P0 | ⑧会话生成块 | 1.首见请求 | mint+Set-Cookie+注入；body 带 cid=采纳路径 | — |
| `SC-CHAIN-10` | P0 | ⑨归属判定 | 1.同 cid 二次请求 | OWNED 续用；跨 Agent 独立归属 | — |
| `SC-CHAIN-11` | P0 | ⑩角色装载 | 1.Invoke 前观察 | roles 快照取自 RDC（直查不缓存） | — |
| `SC-CHAIN-12` | P0 | ⑪rail 三项 | 1.越界工具一发 2.合法工具一发 | 激活集=组并集；越界=AUTHORIZATION_DENIED | 变体 yml |
| `SC-CHAIN-13` | P0 | ⑫子调用出站（AND 三件=①原用户 token 经网关透传 ②受信上下文派生（ | 1.子 Agent 调用 | 派生六键+callerAgentId=B+expiresAt 不延长；网关再判决；C 独立激活 | 双实例 |
| `SC-CHAIN-14` | P0 | ⑬⑭审计与响应 | 1.核对双系统审计 2.核对响应 | decisionId/traceId 贯通；响应原样（错误面不改写） | 双系统 |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 14/14

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
