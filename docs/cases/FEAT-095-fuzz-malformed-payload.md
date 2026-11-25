---
feature_id: FEAT-095
feature_title: 恶意报文 FUZZ 电池
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: 畸形 JSON/字段变异/类型混淆/超限载荷/注入向量对全入口的鲁棒性
status: runnable
owner: westmoreland
priority: P0
tags: [integration, feat-095, feat-064, unified-permission]
depends_on:
  - RDC/Gateway/Runtime 三组件部署（FRS-064 §4.1 拓扑）
  - Keycloak realm perm064 + PostgreSQL agent_rdc
  - 网关/Guard 探针脚本与 TC 构造（authflow-demo-context-secret）
related_docs:
  - FRS-064-端到端测试方案.md（FRS 台账 v1.29）
  - FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（192 用例源计划）
  - testplan-基线后观察记录-20261112.md（57 节执行证据）
---

# FEAT-064 统一权限 — 恶意报文 FUZZ 电池（测试用例设计）

> **一句话**：畸形 JSON/字段变异/类型混淆/超限载荷/注入向量对全入口的鲁棒性。本组 21 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-FUZZ-01` | P1 | 载荷=JSON null | 1.null 载荷头 | 401 AUTH_INVALID | — |
| `SC-FUZZ-02` | P1 | 载荷=数组/字符串/数字/布尔 | 1.四形态 | 401 AUTH_INVALID | — |
| `SC-FUZZ-03` | P1 | 载荷=空对象 | 1.{} 载荷 | 401（expiresAt 缺失） | — |
| `SC-FUZZ-04` | P1 | expiresAt 类型混淆 | 1.字符串/null 型 | 401 AUTH_INVALID | — |
| `SC-FUZZ-05` | P2 | expiresAt 无生存期上限 | 1.远期时间 | 设计观察（生存期上限为可选加固项） | — |
| `SC-FUZZ-06` | P2 | base64 padding | 1.带 padding | 200 容忍（文档宽限口径） | — |
| `SC-FUZZ-07` | P1 | 重复身份头（同值） | 1.四头各一双 | 401（脱敏：消息只含头名） | — |
| `SC-FUZZ-08` | P1 | 空 x-user-id | 1.空头 | 401（自报不匹配） | — |
| `SC-FUZZ-09` | P1 | originalUserId 含 CRLF | 1.CRLF claim | 401 AUTH_INVALID | — |
| `SC-FUZZ-10` | P1 | traceId 含 CRLF | 1.CRLF traceId | 401 AUTH_INVALID | — |
| `SC-FUZZ-11` | P1 | 单字符/空载荷段 | 1.边界分段 | 401 | — |
| `SC-FUZZ-12` | P0 | claim cid≠body cid 错配 | 1.错配+越权工具 | **DENY**（FEAT-122 §3.2 收口裁决：会话键错位=激活集未发布） | — |
| `SC-FUZZ-13` | P2 | 跨用户会话接管（claim 途径） | 1.攻击者 claim 同 cid | **挂起**：D49 裁决项（见 §2 非范围「跨用户会话隔离的实现」），裁决后转可执行 | 双用户 |
| `SC-FUZZ-14` | P2 | 跨用户接管（body 采纳途径） | 1.攻击者 body=受害者 cid | **挂起**：D49 裁决项（见 §2 非范围「跨用户会话隔离的实现」） | — |
| `SC-FUZZ-15` | P2 | 会话锚 cookie 安全属性 | 1.检查 Set-Cookie | HttpOnly/Secure/SameSite（网关侧定稿必选项） | — |
| `SC-FUZZ-16` | P1 | verify 200 非 JSON 体 | 1.畸形响应 | 503 fail-closed | — |
| `SC-FUZZ-17` | P1 | verify 200 缺 identity 节点 | 1.缺节点 | 503 | — |
| `SC-FUZZ-18` | P1 | verify 401 非 AUTH 码 | 1.非 AUTH 码 | 503（映射正确） | — |
| `SC-FUZZ-19` | P2 | roles 非数组形态 | 1.对象形态 | 越界拒（RDC 自构造字段——已知取舍） | — |
| `SC-FUZZ-20` | P1 | verify 慢响应（>预算） | 1.超预算响应 | 503 fail-fast | — |
| `SC-FUZZ-21` | P1 | 缺 x-origin-authorization | 1.缺凭证 | 401 fail-closed | — |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 21/21

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
