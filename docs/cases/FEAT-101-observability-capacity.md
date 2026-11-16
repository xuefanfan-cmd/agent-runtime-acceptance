---
feature_id: FEAT-101
feature_title: 可观测与容量
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: OTel span 导出与检索、幂等记录生命周期容量、日志增长与轮转
status: runnable
owner: westmoreland
priority: P1
tags: [performance, feat-101, frs-064, unified-permission]
depends_on:
  - RDC/Gateway/Runtime 三组件部署（FRS-064 §4.1 拓扑）
  - Keycloak realm perm064 + PostgreSQL agent_rdc
  - 网关/Guard 探针脚本与 TC 构造（authflow-demo-context-secret）
related_docs:
  - FRS-064-端到端测试方案.md（FRS 台账 v1.29）
  - FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（192 用例源计划）
  - testplan-基线后观察记录-20261112.md（57 节执行证据）
---

# FRS-064 统一权限 — 可观测与容量（测试用例设计）

> **一句话**：OTel span 导出与检索、幂等记录生命周期容量、日志增长与轮转。本组 7 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-OBS-01` | P1 | traceId 全链贯通（095 V08 材料面） | 1.全链请求→检索 | traceId 贯通（材料契约面） | — |
| `SC-OBS-02` | P2 | 权限台账查询 | 1.GET /changes | 台账查询面可用 | — |
| `SC-OBS-03` | P2 | OTel 上报 | 1.请求→span 检索 | **挂起**：FEAT-101 域，不在本计划验证范围（见 §2 非范围「审计查询/回放视图」），本行不构成断言 | — |
| `SC-OBS-04` | P1 | 指标回归 | 1.采样对照 | 对照预算基线（偏差 16） | — |
| `SC-OBS-05` | P2 | 与平台节流交互 | 1.权限开启下 | 交互正常 | — |
| `SC-OBS-06` | P1 | 超时 fail-fast | 1.触发超时 | 预算内 fail-fast（L2 §6.5） | — |
| `SC-OBS-07` | P2 | filter 链顺序（119 §3.2） | 1.观察过滤器执行序 2.核对 trace 投影读取的身份 | AuthnAuthzFilter（order=-20）先于 TraceIdentityFilter（order=-10） | — |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 7/7

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
