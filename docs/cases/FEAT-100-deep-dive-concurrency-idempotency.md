---
feature_id: FEAT-100
feature_title: 深挖专项：并发/幂等/委派
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: 同会话并发单飞、幂等登记竞争与生命周期、子调用环（A→B→A）防环、真实 LLM 在途撤权
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

# FRS-064 统一权限 — 深挖专项：并发/幂等/委派（测试用例设计）

> **一句话**：同会话并发单飞、幂等登记竞争与生命周期、子调用环（A→B→A）防环、真实 LLM 在途撤权。本组 17 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-M-01` | P1 | **重复身份头异值**（FUZZ-07 只测同值） | 1.x-user-id: u-1001 ＋ x-user-id: u-1002 双头（值不同） | 401 AUTH_INVALID（重复拒且**脱敏**）；不同值不改变拒绝语义 | — |
| `SC-M-02` | P1 | x-user-id 大小写变体 | 1.x-user-id: U-1001（大写）+ Bearer（主体=u-1001） | 401 AUTH_INVALID（主体匹配大小写敏感）或 200（若 IAM 主体大小写不敏感）——**按实测定口径** | — |
| `SC-M-03` | P1 | expiresAt 三边界 | 1.expiresAt=now（恰好） 2.=now-1s 3.=now+1s | now=AUTH_EXPIRED（≤now 判过期）或 200（>now 语义）——按实测定口径；now-1s=AUTH | — |
| `SC-M-05` | P1 | 未知 claim 字段前向兼容 | 1.载荷加 extraField:"x" 2.全链观察 | ②b/装载/工具不炸；字段被忽略（不进审计/不进工具输入） | — |
| `SC-M-06` | P1 | metadata.agentId 与路径 agentId 不一致 | 1.路径入口 /a2a/agents/agent-a + params.metadata.agentId=agent-b | （已定案）**400 通用页=fail-closed**（无语义化拒绝=已知取舍，非缺陷） | — |
| `SC-M-07` | P1 | 同工具多组重叠去重 | 1.满角色 DELETE | 激活集去重（delete_order 单实例）；执行一次 | 重叠造数 |
| `SC-M-08` | P1 | --ALL-- 与显式组并存 | 1.显式组内工具 2.显式组外工具 | 并集语义或 --ALL-- 优先——按实测定口径 | 变体 yml |
| `SC-M-09` | P1 | 未注册 Agent 作为判决目标 | 1.check（target=agent-never） | 按 RDC 契约断言（REFERENCE_INVALID 或 NO_MATCH——**按实测定口径**，≠200 放行） | — |
| `SC-M-10` | P0 | **审计第三关联键：requestId 对账** | 1.网关审计行的 requestId ↔ RDC 审计行 | requestId 贯通（decisionId/traceId 之外的第三关联键） | 双系统 |
| `SC-M-11` | P1 | REVISION_CONFLICT 真实并发 | 1.10 并发修订（同 expectedRevision） | **恰好 1 成功，其余 409**；无数据损坏 | — |
| `SC-M-12` | P1 | 幂等 messageId 边界 | 1.256 字符 messageId 2.含 emoji/换行的 messageId | 语义化处理（正常幂等或 400——按实测定口径）；无 500 | — |
| `SC-M-13` | P1 | 子调用环 A→B→A | 1.A 发起→B→A 环调用 | 防环或有界深度（按实测定口径；无环防护=STACK_OVERFLOW 即发现（如实记录，先不判 ISSUE）） | 互配 |
| `SC-M-14` | P1 | token 剩余 1799s 的 GT 签发 | 1.签发观察 | expiresAt=tokenExp（<now+1800，**min 取小方向正确**） | — |
| `SC-M-15` | P2 | 多实例 weight 分派 | 1.20 次请求统计分派 | 分派比例近似 weight | — |
| `SC-M-16` | P2 | SSE 流中 RDC 宕 | 1.流已开→RDC 停 | 流继续（判决已过）或语义化中断——按实测定口径 | — |
| `SC-M-17` | P1 | callable-agents 多规则语义 | 1.分别命中两规则的目标 | 并集或 AND——按实测定口径（L2 口径核对） | — |
| `SC-M-18` | P1 | 审计失败不阻断业务（AUDIT_UNAVAILABLE 面） | 1.业务请求 | 按契约断言（AUDIT_UNAVAILABLE 停止后续动作=095 V07 口径——与 DEG-07 的存储纪律交叉验 | 注入 |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 17/17

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
