---
feature_id: FEAT-121
feature_title: FEAT-121/122 ToolGroup 激活与执行边界
sut: openjiuwen 统一权限链路（Gateway/RDC/Runtime，FRS-064 §4.1 标准拓扑）
scope: ToolGroup 角色→工具映射、规划侧灰门（工具不可见）与执行侧硬门（拦截）双防线
status: runnable
owner: westmoreland
priority: P0
tags: [integration, feat-121, frs-064, unified-permission]
depends_on:
  - RDC/Gateway/Runtime 三组件部署（FRS-064 §4.1 拓扑）
  - Keycloak realm perm064 + PostgreSQL agent_rdc
  - 网关/Guard 探针脚本与 TC 构造（authflow-demo-context-secret）
related_docs:
  - FRS-064-端到端测试方案.md（FRS 台账 v1.29）
  - FEAT-095&099&100&119~123-unified-permission-authz-testplan.md（192 用例源计划）
  - testplan-基线后观察记录-20261112.md（57 节执行证据）
---

# FRS-064 统一权限 — FEAT-121/122 ToolGroup 激活与执行边界（测试用例设计）

> **一句话**：ToolGroup 角色→工具映射、规划侧灰门（工具不可见）与执行侧硬门（拦截）双防线。本组 15 条用例已在 121 环境（56ec4c3cc 构建）实跑验证（2026-11-15，执行证据见基线后观察记录对应节）。

## 1. 覆盖矩阵

| 子用例 ID | 优先级 | 用例名 | 步骤要点 | 期望 | 证据/关联 |
|---|---|---|---|---|---|
| `SC-TG-01` | P0 | 满角色=组并集激活（122 A9/A11） | 1.DELETE 请求 | delete_order 执行（并集） | — |
| `SC-TG-02` | P0 | 越界+粒度（122 A11） | 1.DELETE→拒 2.QUERY→执行 | 拒/执行分立；消息回喂（偏差 10） | — |
| `SC-TG-03` | P0 | **LLM 视线**（122 A12/AC-09） | 1.只读+DELETE 意图 | 非激活工具 schema 不进模型输入 | — |
| `SC-TG-04` | P1 | --ALL-- 恒放行（122 A13） | 1.任意工具 | 全放行 | 变体 yml |
| `SC-TG-05` | P0 | 空角色=无工具可见（122 A11/AC-31） | 1.Invoke | 越界拒（allowed:[]；**仅桩通道**） | 桩通道 |
| `SC-TG-06` | P1 | ToolIdentity 脱敏（D60） | 1.toString | [redacted]；四字段最小暴露 | — |
| `SC-TG-07` | P1 | 归一化四族+exact 兜底 | 1.子会话/嵌套/任意串 | 取根/exact | — |
| `SC-TG-08` | P2 | miss 计数可观测 | 1.读计数 | 可见 | — |
| `SC-TG-09` | P2 | 挂载自检 | 1.启动日志 | self-check 通过（组件测试承载+人工核对单） | — |
| `SC-TG-10` | P1 | 未分组工具=任何角色都拒（偏差 19） | 1.满角色+未分组工具 | 拒（不在激活集） | 变体 yml |
| `SC-TG-11` | P1 | 配置缺失 fail-closed（122 A13/AC-31） | 1.启动 2.Invoke | 启动不失败+运行期全拒（不回退全量） | nogroups yml |
| `SC-TG-12` | P1 | Skill 越界（AC-09/F9） | 1.skill 场景调用 | DENIED；skillNames≠toolNames | — |
| `SC-TG-13` | P1 | 逐 hop 独立激活（122 A9） | 1.同角色跨 hop | 各按本实例配置 | 双实例 |
| `SC-TG-14` | P1 | 同进程多实例（122 A15/D37） | 1.同角色激活不同 | 各按本配置 | — |
| `SC-TG-15` | P1 | 校验前提缺失 fail-closed（122 A21/A18 继承） | 1.直接调用 | 不进 ∈ 判定，直接 fail-closed | — |

## 2. 状态定义

- **runnable**：探针/脚本已在 FRS-064 测试交付目录实跑并留证，可在同环境复跑；
- **env-gated**：依赖特定环境装配（如 FEAT-101 span 导出端），装配后可跑。

## 3. 执行环境

- ECS 121.36.206.139:9831（RDC 8092 / GW 8090 / Runtime 18081-18083 / Keycloak 8081 / PG 15432）
- 代码基线：56ec4c3cc（构建）；源树演进至 eac86e855（判决链零变更）
- 执行日期：2026-11-15；执行率 15/15

## 4. 关联缺陷（本组发现）

- 详见 FRS 台账 v1.29 与对应 issue（全部带【责任模块】+代码定位+实证+回归条件）。
