---
feature_id: FEAT-095
feature_title: FEAT-095 全链集成（GW→RDC→Runtime）
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: 端到端标准链路：网关四段门→RDC 判决→Runtime 执行→审计贯通
status: runnable
owner: westmoreland
priority: P0
tags: [e2e, feat-095, frs-064, unified-permission]
depends_on:
  - RDC/Gateway/Runtime 三组件部署（FRS-064 §4.1 拓扑）
  - Keycloak realm perm064 + PostgreSQL agent_rdc
related_docs:
  - FRS-064-端到端测试方案.md（FRS 台账 v1.29）
  - FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（192 用例源计划）
---

# FRS-064 统一权限 — FEAT-095 全链集成（测试用例设计）

> **一句话**：端到端标准链路三真用例（真网关+真 RDC+真 Runtime）。本组 3 条用例已在 121 环境实跑验证。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-E2E-01` | P0 | 三真全链（095 V01/100 V06/119 A1） | 1.客户端→网关→判决→签发→runtime（②b+rail）→工具→回包 | 200；业务回包；全链审计行在 | 基线后观察记录 |
| `SC-E2E-02` | P0 | 拒绝传播 | 1.全链请求（根入口；路径入口另发一发对照） | 根入口=403 CALLER_NOT_AUTHORIZED（stage=USER_ADMISSION）穿透；路径入口=2 | 基线后观察记录 |
| `SC-E2E-03` | P0 | 越界传播（122 §3.2 fail-closed） | 1.全链 DELETE | AUTHORIZATION_DENIED（fail-closed 封死） | 基线后观察记录 |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083）
- 代码基线：56ec4c3cc（构建）；执行日期：2026-11-15；执行率 3/3
