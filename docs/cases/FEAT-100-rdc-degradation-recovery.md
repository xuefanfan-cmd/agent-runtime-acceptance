---
feature_id: FEAT-100
feature_title: RDC 降级与恢复
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: RDC 暂停/不可达/慢响应下网关与 Runtime 的降级行为与恢复语义
status: runnable
owner: westmoreland
priority: P1
tags: [integration, feat-100, feat-064, unified-permission]
depends_on:
  - RDC/Gateway/Runtime 三组件部署（FRS-064 §4.1 拓扑）
  - Keycloak realm perm064 + PostgreSQL agent_rdc
  - 网关/Guard 探针脚本与 TC 构造（authflow-demo-context-secret）
related_docs:
  - FRS-064-端到端测试方案.md（FRS 台账 v1.29）
  - FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（192 用例源计划）
  - testplan-基线后观察记录-20261112.md（57 节执行证据）
---

# FEAT-064 统一权限 — RDC 降级与恢复（测试用例设计）

> **一句话**：RDC 暂停/不可达/慢响应下网关与 Runtime 的降级行为与恢复语义。本组 8 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-DEG-01` | P1 | 判决面双关（网关关×RDC 关） | 1.经网关请求 | 网关 BYPASSED 运行日志留痕（无 RDC 判决调用）；网关照常构建上下文与转发 Header（L2-100 §3 | 开关 |
| `SC-DEG-02` | P1 | 判决面降级（网关关）×接收面正常 | 1.经网关请求 2.观察 Runtime 入站 | 网关旁路判决直接转发（**不调 RDC**）；网关降级时照常构建受信上下文与转发头（§3.7）：请求带合法 token→ | 开关 |
| `SC-DEG-03` | P1 | GW=true+RDC=false | 1.请求 | RDC BYPASSED+网关放行；runtime ②b 对 BYPASSED 的消费语义=验收要点 | — |
| `SC-DEG-04` | P1 | 双开基线 | 1.请求 | 全链正常判决 | — |
| `SC-DEG-05` | P2 | 降级期间业务约束 | 1.原任务/幂等/恢复 | 不取消业务约束（100 V21） | — |
| `SC-DEG-06` | P2 | 恢复重判 | 1.恢复后请求 | 正常判决+BYPASSED 不计正常通过（095 V17） | — |
| `SC-DEG-07` | P2 | 降级+DB 故障 | 1.请求 | 仍 503（存储纪律） | 桩 DB |
| `SC-DEG-08` | P2 | 限流/过载 | 1.429（注册面——授权面不限流=反证）2.并发上限 503 | 429/503 分离；网关 503 无 Retry-After=设计口径（偏差 24） | — |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 8/8

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
