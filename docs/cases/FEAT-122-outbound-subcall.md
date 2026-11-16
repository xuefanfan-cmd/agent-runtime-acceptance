---
feature_id: FEAT-122
feature_title: FEAT-122 子调用出站
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: Runtime 发起的 A2A 子调用：TC 派生、凭证转发、对端校验
status: runnable
owner: westmoreland
priority: P0
tags: [integration, feat-122, frs-064, unified-permission]
depends_on:
  - RDC/Gateway/Runtime 三组件部署（FRS-064 §4.1 拓扑）
  - Keycloak realm perm064 + PostgreSQL agent_rdc
  - 网关/Guard 探针脚本与 TC 构造（authflow-demo-context-secret）
related_docs:
  - FRS-064-端到端测试方案.md（FRS 台账 v1.29）
  - FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（192 用例源计划）
  - testplan-基线后观察记录-20261112.md（57 节执行证据）
---

# FRS-064 统一权限 — FEAT-122 子调用出站（测试用例设计）

> **一句话**：Runtime 发起的 A2A 子调用：TC 派生、凭证转发、对端校验。本组 5 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-OUT-01` | P0 | 派生映射（123 A30~A32） | 1.子上下文 | 六键沿用+callerAgentId=B+currentAgent 不传播+expiresAt 不延长 | — |
| `SC-OUT-02` | P0 | 三头注入 | 1.检查 Header | 三头齐 | — |
| `SC-OUT-03` | P0 | Holder 跨阶段存活 | 1.阶段切换后读 | 存活 | — |
| `SC-OUT-04` | P1 | 构造失败不出站（123 A33） | 1.子调用发起 | 不发起/不降级/失败留痕 | — |
| `SC-OUT-05` | P2 | 子调用拒绝归类（dev#10） | 1.检查错误归类 | 原始码保留；A2A role=ROLE_USER | — |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 5/5

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
