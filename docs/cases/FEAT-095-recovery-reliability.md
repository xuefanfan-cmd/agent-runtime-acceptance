---
feature_id: FEAT-095
feature_title: 恢复可靠性
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: 提交后响应丢弃、SIGSTOP/CONT 实例恢复、跨 agent 访问拒绝
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

# FEAT-064 统一权限 — 恢复可靠性（测试用例设计）

> **一句话**：提交后响应丢弃、SIGSTOP/CONT 实例恢复、跨 agent 访问拒绝。本组 4 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-REST-01` | P1 | 桩→真对照 | 1.装载切换 | 注入态/真装载一致 | 双模式 |
| `SC-REST-02` | P0 | 归属恢复+双通路（120 A17/偏差 9） | 路径入口按码族拆分：Runtime 401(AUTH_*)→-32600(retryable=false)；SCOPE_ | 两通路分立断言 | 会话存储 |
| `SC-REST-03` | P0 | 撤权时序（099 V10/VA-07） | 1.重放/恢复/GetTask | 判当前权限/不用旧 allow（≤10s） | 五类映射集 |
| `SC-REST-04` | P1 | 命中恢复+身份完整（120 A16） | 1.恢复 | 身份上下文完整 | — |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 4/4

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
