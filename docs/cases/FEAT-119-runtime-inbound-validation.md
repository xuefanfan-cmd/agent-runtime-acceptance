---
feature_id: FEAT-119
feature_title: FEAT-119 Runtime 入站校验与边界
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: Runtime 入站 trusted-context 校验、鉴权过滤、降级与恢复、错误码契约
status: runnable
owner: westmoreland
priority: P0
tags: [integration, feat-119, feat-064, unified-permission]
depends_on:
  - RDC/Gateway/Runtime 三组件部署（FRS-064 §4.1 拓扑）
  - Keycloak realm perm064 + PostgreSQL agent_rdc
  - 网关/Guard 探针脚本与 TC 构造（authflow-demo-context-secret）
related_docs:
  - FRS-064-端到端测试方案.md（FRS 台账 v1.29）
  - FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（192 用例源计划）
  - testplan-基线后观察记录-20261112.md（57 节执行证据）
---

# FEAT-064 统一权限 — FEAT-119 Runtime 入站校验与边界（测试用例设计）

> **一句话**：Runtime 入站 trusted-context 校验、鉴权过滤、降级与恢复、错误码契约。本组 14 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-IN-01` | P0 | 两段式合法上下文=放行+工具执行（119 A1） | 1.合法两段式头+flat query | 200；工具执行；②b 复核 hits+1 | — |
| `SC-IN-02` | P0 | 缺 x-trusted-context | 1.无头请求 | 401 AUTH_MISSING | — |
| `SC-IN-03` | P0 | wire 结构非法（三变体） | 1.坏 base64 2.缺 .none 3.多段 | 401 AUTH_INVALID | — |
| `SC-IN-04` | P0 | 过期上下文 | 1.expiresAt<now | 401 AUTH_EXPIRED（与 INVALID 可区分） | — |
| `SC-IN-05` | P0 | 自报 x-user-id≠claim | 1.伪造自报头 | 401 AUTH_INVALID | — |
| `SC-IN-06` | P1 | 必填字段缺失（originalUserId/traceId/expiresAt— | 1.逐一缺失三发 | originalUserId/traceId 缺失→401 AUTH_MISSING；expiresAt 缺失→401  | — |
| `SC-IN-07` | P0 | ②b 复核四态（119 A2；099 V05） | 1.不确认 2.确认 3.每请求一次 4.RDC 故障 | 401/放行/hits+1 无缓存/fail-closed 四态 | RdcStubServer |
| `SC-IN-08` | P0 | 会话生成块双分支（119 A4） | 1.claim 无 cid+body 无 cid→mint+Set-Cookie+注入 2.claim 无 cid+bo | 双分支按 L2 §4.1 设计 | — |
| `SC-IN-09` | P0 | 归属三态（120 A14~A16） | 1.同 cid 重入 2.跨 Agent 同 cid 3.新会话同码 | OWNED 续用/独立归属/200 防探测 | — |
| `SC-IN-10` | P0 | 开关四组合矩阵（119 A19/20） | 1.双关=放通 2.authn 单开=有身份无边界+匿名拒 3.authz 单开=启动失败 4.双开=正常 | 四态+Guard 语义 | — |
| `SC-IN-11` | P2 | 托管路径未知 agentId | 1./a2a/agents/未知 | 400 HOSTED_AGENT_INVALID（验收时按入口步序确认） | — |
| `SC-IN-12` | P1 | 角色装载异常=fail-closed（122 §3.2） | 1.装载异常+工具调用 | **语义化拒绝**（装载失败错误码族，retryable=false；不 500、不放行——**非** AUTHORIZ | failWith(404) |
| `SC-IN-13` | P1 | 跨层会话一致性（FEAT-122 §3.2） | 1.无 cid→生成 UUID→三视图同 UUID | 生成值贯通全部层（防分叉） | — |
| `SC-IN-14` | P0 | 三因子组合回归（P0 关门） | 1.无 cid+装载异常+工具调用 | **DENY**（fail-closed 全链封死） | — |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 14/14

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
