---
feature_id: FEAT-099
feature_title: FEAT-099 统一授权中枢：判决面与管理面
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: RDC 判决链（身份/准入/许可/角色直查）、管理 CRUD/幂等/修订、降级恢复、实例注册与心跳
status: runnable
owner: westmoreland
priority: P0
tags: [integration, feat-099, feat-064, unified-permission]
depends_on:
  - RDC/Gateway/Runtime 三组件部署（FRS-064 §4.1 拓扑）
  - Keycloak realm perm064 + PostgreSQL agent_rdc
  - 网关/Guard 探针脚本与 TC 构造（authflow-demo-context-secret）
related_docs:
  - FRS-064-端到端测试方案.md（FRS 台账 v1.29）
  - FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（192 用例源计划）
  - testplan-基线后观察记录-20261112.md（57 节执行证据）
---

# FEAT-064 统一权限 — FEAT-099 统一授权中枢：判决面与管理面（测试用例设计）

> **一句话**：RDC 判决链（身份/准入/许可/角色直查）、管理 CRUD/幂等/修订、降级恢复、实例注册与心跳。本组 25 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-RDC-01` | P0 | 身份核验+角色回传（099 V02/V04；AC-32/33） | 1.POST /v2/authorization/identities:verify，头=Authorization(c | 200；identity.result=OK+userId+tokenExpiresAt(ISO-8601)；roleQ | 真 Keycloak |
| `SC-RDC-02` | P0 | 用户准入判决（100 V03/099 V05；AC-35） | 1.POST invocations:check（requestId/callerAgentId=**null**/ta | 200；allAllowed=true；results[0]=allowed＋admission(ROLE_AGENT  | 目录就绪 |
| `SC-RDC-03` | P0 | 判决拒绝（空角色用户） | 1.check | 200；allAllowed=false；results[0].allowed=false+stage=USER_ADM | 目录就绪 |
| `SC-RDC-04` | P0 | exp 回传 ISO-8601（R18③） | 1.verify | tokenExpiresAt=ISO-8601 UTC 字符串（非数字） | — |
| `SC-RDC-05` | P0 | 同组互调（PEER 语义，偏差 6：L2 口径） | 1.check（caller=**agent-b**→target=**agent-c**，同组成员）2.对照：call | 1.同组成员=allAllowed=true 且 agentPermission.via 含 **AGENT_GROUP | 组映射 |
| `SC-RDC-06` | P1 | 五路径预览（099 V06） | 1.POST /effective-permissions:preview | 200；返回命中角色路径（via 明细） | — |
| `SC-RDC-07` | P0 | verify 失败族（材料缺失/伪造/aud 边界） | 1.缺 x-user-id（正常模式可选项→期望 200，用户以 token 主体为准） 2.伪造 x-user-id  | 1=200（x-user-id 可选）；2~3=401 AUTH_MISSING/AUTH_INVALID（对应语义码） | — |
| `SC-RDC-08` | P1 | 管理 CRUD+修订冲突+幂等台账+停用失效+部分撤权（099 V11/V12） | 1.角色/组 CRUD 2.并发修订（expectedRevision 冲突）3.同键重放/同键异内容 4.停用角色→v | 各步 200/409/422 语义化；直读生效；不误伤保留映射 | — |
| `SC-RDC-09` | P1 | 管理面调用方认证 | 1.非 admin 凭证调 admin API | 403（admin 面拒绝） | — |
| `SC-RDC-10` | P1 | DISABLED 状态语义（**分端点**） | 1.sync（status=DISABLED）2.identities:verify 3.invocations:che | verify=200 且 roles 为空（不因 DISABLED 拒鉴身份）；拒绝面在 check 的用户准入（语义可 | — |
| `SC-RDC-11` | P1 | IAM 不可达（负例） | 1.verify | 503 IAM_UNAVAILABLE（fail-closed，非 500） | KC 停/坏 issuer |
| `SC-RDC-12` | P1 | requestId 回显校验（L2-100 REQ-006 的 **RDC 侧* | 1.check 带 requestId → RDC 侧回显一致性 | ①requestId 原样回显 ②超 128 字符→400 INVALID_ARGUMENT 且不写审计（核对一致性=调 | — |
| `SC-RDC-13` | P1 | 审计行结构（7.10/AC-24） | 1.查 authz_audit 全字段 | 行结构完整（16 字段）；recordType 三家族在 | — |
| `SC-RDC-14` | P1 | 判决 roles 快照 | 1.改映射 2.再 check | 审计行 roles=判决时快照（前后不同） | — |
| `SC-RDC-15` | P1 | users:sync 目录同步（锚点=V1.9/对外接口变更说明；FEAT-09 | 1.FULL（缺席→REMOVED 级联）2.INCREMENTAL 3.DISABLED 4.重复 sync（幂等 u | upserted/removed 计数；级联软删除；DISABLED→admission 拒；**重复 sync 无重复 | — |
| `SC-RDC-16` | P2 | roleId 治理（099 V11） | 1.正则负例×4 2.创建后不可变 3.改名不改匹配 4.新建不继承 5.停用后 via 消失 | 400×4+语义各断言 | — |
| `SC-RDC-17` | P2 | Agent 授权清理（099 V12） | 1.DELETE authorizations（幂等）2.该 agent 映射消失、其他保留 3.恢复 | 幂等同码；不误伤 | — |
| `SC-RDC-18` | P1 | 幂等台账状态机（L2 §3.3.2） | 1.同键同载荷→同 changeId 2.同键异载荷→422 3.失败写入留痕 4.by-key 查询 | 各步语义化（200/422/404） | — |
| `SC-RDC-19` | P1 | 生效时延≤10s（099 REQ-007/AC-38） | 1.改映射→计时→下一 verify | ≤10s 生效；并发替换无新旧混合 | — |
| `SC-RDC-20` | P2 | RDC 降级细化（099 V14~V16） | 0.启动时写 SWITCH 记录（record_type=SWITCH=开关状态快照） 1.缺 x-user-id→AU | 各步语义化；BYPASSED 落审计 | — |
| `SC-RDC-21` | P2 | SPI 机制可插拔（099 V19/AC-33；**机制+接口=我方，实现=客户 | 1.替身在=身份核验经 SPI 2.替身摘除=IAM_UNAVAILABLE（机制降级）3.多实现=明确拒绝（机制冲突处 | 机制的负例 fail-closed；正向经 SPI；**接口契约稳定=客户可自实现** | 替身 jar |
| `SC-RDC-22` | P1 | 批量负例（L2 §2.4.2） | 1.65 目标 2.重复目标 3.空 callerAgentId | 400；不鉴定 token、不写审计、无 decisionId | — |
| `SC-RDC-23` | P1 | 租户头配对校验 | 1.缺 x-tenant-id 2.x-tenant-id 与令牌租户不匹配 | 缺头=400 INVALID_ARGUMENT；不匹配=403（单租户部署仅验头级校验，不验多租户隔离——见 §2 非范 | — |
| `SC-RDC-24` | P1 | 多目标混合判决（语义正例） | 1.check（targetAgentIds=[agent-a, agent-d]） | allAllowed=false；results **逐目标并存**（agent-a=true、agent-d=fals | — |
| `SC-RDC-25` | P1 | 组成员未注册的处置（负例） | 组成员未注册的处置（负例） | 1.PUT 含 agent-d 2.观察既有成员 3.对照：曾注册后下线的 agent（卡快照在）仍可配置 | 422 REFERENCE_INVALID+**整集合原子拒绝**；**曾注册（ |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 25/25

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
