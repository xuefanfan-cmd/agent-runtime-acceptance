---
feature_id: FEAT-099
feature_title: FEAT-099 审计面
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: 审计行触发（4 record_type）、字段契约、stage 词表（G-27 decisionId 关联口径）、映射对照
status: runnable
owner: westmoreland
priority: P0
tags: [integration, feat-099, frs-064, unified-permission]
depends_on:
  - RDC/Gateway/Runtime 三组件部署（FRS-064 §4.1 拓扑）
  - Keycloak realm perm064 + PostgreSQL agent_rdc
  - 网关/Guard 探针脚本与 TC 构造（authflow-demo-context-secret）
related_docs:
  - FRS-064-端到端测试方案.md（FRS 台账 v1.29）
  - FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（192 用例源计划）
  - testplan-基线后观察记录-20261112.md（57 节执行证据）
---

# FRS-064 统一权限 — FEAT-099 审计面（测试用例设计）

> **一句话**：审计行触发（4 record_type）、字段契约、stage 词表（G-27 decisionId 关联口径）、映射对照。本组 12 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-AUD-01` | P0 | decisionId 串联（095 V08） | 1.网关审计行↔RDC 表检索 | 同 id 贯通 | 双系统 |
| `SC-AUD-02` | P0 | traceId 贯通（FR-08/AC-24） | 1.双系统检索 | 一致 | — |
| `SC-AUD-03` | P2 | stage 词表映射（099 审计面）——**按 G-27 重定义**：两套审计 | 1.decisionId 关联贯通验证 | **已验证 ✓**（E11 AUD-07：响应↔审计行 decisionId 贯通；词表映射对照=待裁决增强项） | G-27 口径 |
| `SC-AUD-04` | P0 | **敏感原文最小暴露**（AC-26/095 V08/121 A27/123 A | 1.五观察面检索 | token 与 authorizationRef 原文 **0 命中**+不代刷新 | 五观察面 |
| `SC-AUD-05` | P1 | 请求头检查失败不写审计（099 L2） | 1.触发→查表 | 无审计行+WARN | — |
| `SC-AUD-06` | P1 | callerVerified 显式标记 | 1.核对 | caller 非空时=false（POC 口径，文档化） | — |
| `SC-AUD-07` | P2 | 拒绝后未评估阶段标记 | 1.核对 | NOT_EVALUATED | — |
| `SC-AUD-08` | P1 | hitSources=via JSON 一致 | 1.核对 | via 类型族与判决一致 | — |
| `SC-AUD-09` | P2 | 审计只插不改不删（L2 §3.7） | 1.操作后查 | 只插 | — |
| `SC-AUD-10` | P1 | 参数 400 无 decisionId 不写审计 | 1.触发→查 | 无审计行 | — |
| `SC-AUD-11` | P2 | verify 行结构（1~2 行） | 1.核对 | IDENTITY+ROLE_QUERY | — |
| `SC-AUD-12` | P1 | AC-24 七必填字段对勾 | 1.逐字段对勾 | 全字段在案 | — |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 12/12

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
