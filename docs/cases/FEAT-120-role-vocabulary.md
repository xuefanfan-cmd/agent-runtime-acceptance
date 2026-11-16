---
feature_id: FEAT-120
feature_title: FEAT-120 角色词表与映射
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: RDC hyphen 词表、gate 角色检查、词表对齐（连字符口径）
status: runnable
owner: westmoreland
priority: P0
tags: [integration, feat-120, frs-064, unified-permission]
depends_on:
  - RDC/Gateway/Runtime 三组件部署（FRS-064 §4.1 拓扑）
  - Keycloak realm perm064 + PostgreSQL agent_rdc
  - 网关/Guard 探针脚本与 TC 构造（authflow-demo-context-secret）
related_docs:
  - FRS-064-端到端测试方案.md（FRS 台账 v1.29）
  - FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（192 用例源计划）
  - testplan-基线后观察记录-20261112.md（57 节执行证据）
---

# FRS-064 统一权限 — FEAT-120 角色词表与映射（测试用例设计）

> **一句话**：RDC hyphen 词表、gate 角色检查、词表对齐（连字符口径）。本组 8 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-ROLE-01` | P0 | 接收复核（121 A6；两端点分立=偏差 15） | 1.Invoke | verify（身份）+check（判决）分立；各审计行 | — |
| `SC-ROLE-02` | P0 | 复核不确认 | 1.Invoke | 401 AUTH_INVALID | — |
| `SC-ROLE-03` | P0 | 复核不可达 | 1.Invoke | fail-closed 拒 | — |
| `SC-ROLE-04` | P1 | 角色变更即时生效（121 A6） | 1.改→下 Invoke | 激活集随变（不缓存） | — |
| `SC-ROLE-05` | P1 | IAM_UNAVAILABLE（121 A26） | 1.provider 配成不存在名字→503 IAM_UNAVAILABLE（管理接口仍可用） 2.ext/ 放替身 j | 不查角色/无 RoleScope/不执行业务；与空角色可区分 | — |
| `SC-ROLE-06` | P1 | 恢复链角色直查（121 A29） | 1.恢复 | roles 重查（不复用） | — |
| `SC-ROLE-07` | P1 | 角色增授的运行时生效链（121 A6+偏差 16 预算）——含**撤销后再增授* | 1.基线：DELETE→DENIED+JSONL 无 delete_order schema 2.增授 order-de | ≤10s 生效；新工具进规划范围（schema 可见）；激活集扩容；快照更新；旧会话不中断；**撤销后再增授同 (use | 五类映射集 |
| `SC-ROLE-08` | P1 | 角色撤销的运行时收敛链（121 A6；恢复面连 REST-03） | 1.撤销 order-delete 2.进行中请求观察（已装载快照）3.下一 Invoke 4.恢复/GetTask 重 | 进行中按快照完成（不中断）；下一 Invoke=DENIED；恢复重判当前权限；快照更新 | 五类映射集 |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 8/8

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
