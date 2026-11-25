---
feature_id: FEAT-064
feature_title: 统一权限控制（FRS-064）验收用例索引
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime）
scope: FRS-064 全部 8 特性的 192 条验收用例索引（17 组文档）
status: runnable
owner: westmoreland
priority: P0
tags: [e2e, feat-064, unified-permission, index]
---

# FEAT-064 统一权限控制 — 验收用例索引

> **一句话**：FRS-064（工行 POC）统一权限 8 特性 **192 条用例**，按 17 个维度分组收录在本目录（17 个文档）。全部用例已在 ECS 121 环境（56ec4c3cc 构建）**实跑验证 100%**（2026-11-15，执行证据：testplan-基线后观察记录 57 节）。

## 组文档清单

| 文档 | 特性 | 维度 | 用例数 |
|---|---|---|---|
| [FEAT-099-rdc-decision-and-admin.md](FEAT-099-rdc-decision-and-admin.md) | FEAT-099 | RDC 判决面与管理面 | 25 |
| [FEAT-099-audit-face.md](FEAT-099-audit-face.md) | FEAT-099 | RDC 审计面 | 12 |
| [FEAT-100-gateway-governance-chain.md](FEAT-100-gateway-governance-chain.md) | FEAT-100 | 网关四段门治理链 | 26 |
| [FEAT-100-main-chain-subcall-audit.md](FEAT-100-main-chain-subcall-audit.md) | FEAT-100 | 主链/子调用/审计贯通 | 14 |
| [FEAT-100-rdc-degradation-recovery.md](FEAT-100-rdc-degradation-recovery.md) | FEAT-100 | RDC 降级与恢复 | 8 |
| [FEAT-100-lifecycle-upgrade.md](FEAT-100-lifecycle-upgrade.md) | FEAT-100 | 生命周期与平滑升级 | 10 |
| [FEAT-100-deep-dive-concurrency-idempotency.md](FEAT-100-deep-dive-concurrency-idempotency.md) | FEAT-100 | 深挖：并发/幂等/委派 | 17 |
| [FEAT-100-config-matrix.md](FEAT-100-config-matrix.md) | FEAT-100 | 配置矩阵 | 2 |
| [FEAT-119-runtime-inbound-validation.md](FEAT-119-runtime-inbound-validation.md) | FEAT-119 | Runtime 入站校验 | 14 |
| [FEAT-120-role-vocabulary.md](FEAT-120-role-vocabulary.md) | FEAT-120 | 角色词表与映射 | 8 |
| [FEAT-121-toolgroup-activation-boundary.md](FEAT-121-toolgroup-activation-boundary.md) | FEAT-121/122 | ToolGroup 激活与边界 | 15 |
| [FEAT-122-outbound-subcall.md](FEAT-122-outbound-subcall.md) | FEAT-122 | 子调用出站 | 5 |
| [FEAT-123-security-headers.md](FEAT-123-security-headers.md) | FEAT-123 | 安全头与敏感值 | 1 |
| [FEAT-095-e2e-full-chain.md](FEAT-095-e2e-full-chain.md) | FEAT-095 | 全链集成 E2E | 3 |
| [FEAT-095-fuzz-malformed-payload.md](FEAT-095-fuzz-malformed-payload.md) | FEAT-095 | 恶意报文 FUZZ | 21 |
| [FEAT-095-recovery-reliability.md](FEAT-095-recovery-reliability.md) | FEAT-095 | 恢复可靠性 | 4 |
| [FEAT-101-observability-capacity.md](FEAT-101-observability-capacity.md) | FEAT-101 | 可观测与容量 | 7 |

**合计：192 条用例 / 17 组文档。**

## 执行环境与基线

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 源计划：FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（v3.5）

## 关联缺陷（14 张，全部带【责任模块】+代码定位+实证+回归条件）

| 族 | Issues |
|---|---|
| Runtime 状态与授权 | #758(P1 领养竞态) #763(P1 RoleScope) #773(P1 任务 BOLA) #760 #762 #770 |
| Gateway 幂等与契约 | #767(G4 非原子) #774(无生命周期) #759 #764 |
| RDC 事务与治理 | #766(并发删除竞态) #771(租约 fencing) #769(URL 拼接) |
| demo 载体 | #761（词汇修复已落地）|

## 正向验证固化

- TEN-01 租户自报清洗（伪造无效）｜LD-10 平滑升级（V16 增量迁移实锤）｜OBS-03 OTel span 检索（判决链 trace 三 span）
