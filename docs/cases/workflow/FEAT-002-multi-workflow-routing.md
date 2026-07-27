---
用例编号: FEAT-002-multi-workflow-routing
测试标题: 多 workflow intent 路由机制——agent-runtime-java versatile adapter 按 intent 路由到多条 workflow URL
story: S2
优先级: P1
自动化状态: DISABLED（已建 `MultiWorkflowDirectStreamingTest`，`@Disabled("There is issue about this scenario")`——FEAT-002 Allure 主注册类）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, versatile, feat-002]
---

# FEAT-002-multi-workflow-routing — 多 workflow intent 路由机制

> **机制一句话**：多 workflow intent 路由是 **agent-runtime-java versatile adapter 的机制能力**（FEAT-002
> story 2）——同一 adapter 经 plan-agent 携带的 `intent`（`查询账户余额` / `快速转账`）命中配置的多条
> workflow URL，把请求路由到对应 workflow 端点。本机制验证：两条 workflow URL 经编程式 `serviceBinding`
> 去重指向同一 envexplorer 容器（框架只起一个容器），REST 线上禁掉 `type` 查询参后仍按 `workspace_id`
> 路由，业务最终完成查余额 + 转账。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | versatile adapter 的 intent 路由：多条 workflow URL 按 intent 分发 + `serviceBinding` 去重 + REST 查询参裁剪 |
| **载体层 · agent-solution** | 机制触发载体 | `edpa-adapter-multi-workflow`（`multi-workflow` profile）→ envexplorer 两条 workflow 端点（余额 / 转账） |
| **测试数据层** | 载体 agent 的实现逻辑 | `"先查下余额，再给李四和王五各转50元"`——经 plan-agent 自分解为 余额查询 + 转账两 intent |

## 关联特性

- **FEAT-002**：story 2「多 workflow intent 路由到远程 Versatile 控制器」。

## 关联架构约束 / FEAT-002 事实要求

- FEAT-002 §2.2 / §5.0：多 workflow intent 路由机制（adapter 路由能力）。
- FEAT-002 §5.0 验收标准：plan-agent 携带正确 intent 命中 adapter 配置的两条 workflow URL；两条 URL 经编程式 `serviceBinding` 指向同一 envexplorer；REST 线上禁 `type` 查询参后仍按 `workspace_id` 路由。

## 前置条件

1. 被测 jar 就绪：`edpa-adapter`（跑 `multi-workflow` profile）、`edpa-plan-agent`；envexplorer 容器由 service-bindings 自动拉起（去重后仅一个）。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。
3. 真机校准 6 点（见风险与备注）解除后方能移除 `@Disabled`。

## 测试数据

- 载体输入：`"先查下余额，再给李四和王五各转50元"`（plan-agent 自分解为余额查询 + 转账两 intent）。
- 两条 workflow URL 常量（envexplorer 侧 `project_id/agent_id` 固定，`workflow_id` 区分余额/转账），经 `serviceBinding` 注入 `VERSATILE_BALANCE_WORKFLOW_URL` / `VERSATILE_TRANSFER_WORKFLOW_URL` 占位符。

## 路由参数表（同一 adapter，两 intent × 两协议）

| 协议 | intent 路由 | 查询参 | 预期路由命中 |
|---|---|---|---|
| `A2A_STREAM` | plan-agent 携 `intent` → adapter 按 intent 分发 | A2A metadata.query 带 `workspace_id` | 命中对应 workflow 端点 |
| `REST_QUERY` | 同上 | 禁 `type`、带 `workspace_id` | 按 `workspace_id` 路由命中 |

## 测试步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | `edpa-adapter-multi-workflow`（multi-workflow profile）+ `edpa-plan-agent`（downstream→adapter）构栈；两条 workflow URL 经 `serviceBinding` 指向同一 envexplorer | 框架去重拉一个 envexplorer；两条占位符解析为 `host:mappedPort` |
| 2 | `openConversation` override 给 adapter 加 `.disableQueryParam("type")` | REST 线禁 `type`，保留 `workspace_id` |
| 3 | 客户端提交 `"先查下余额，再给李四和王五各转50元"`（A2A_STREAM / REST_QUERY 参数化） | plan-agent 自分解为余额 + 转账两 intent |
| 4 | stepUi 自推进（5 manual select：李四 3 + 王五 2） | intent 命中对应 workflow 端点，envexplorer 推进 |
| 5 | 断言核心语义 | 见「预期结果」 |

## 预期结果（机制断言）

### A — intent 正确路由到对应 workflow 端点
- **Given**：adapter multi-workflow 栈就绪，两条 workflow URL 指向同一 envexplorer。
- **When**：plan-agent 携 `查询账户余额` / `快速转账` intent 调 adapter。
- **Then**：adapter 按 intent 命中对应 workflow 端点（余额 / 转账），不走 fallback。
- **PASS**：两 intent 各命中对应端点。**FAIL**：intent 路由错位 / 走 fallback（路由机制失效）。

### B — serviceBinding 去重（两 key 指向同一容器）
- **Given**：两条 workflow URL 占位符经 `serviceBinding` 都指向 `envexplorer`。
- **When**：框架收集 service-bindings。
- **Then**：去重后仅起一个 envexplorer 容器；两条占位符均解析为该容器的 `host:mappedPort`。
- **PASS**：单容器承载两 workflow。**FAIL**：起两个容器 / 占位符未解析（去重机制失效）。

### C — REST 禁 type 后仍按 workspace_id 路由
- **Given**：REST 线 `.disableQueryParam("type")`。
- **When**：REST 请求带 `workspace_id`（不带 `type`）。
- **Then**：adapter 下游按 intent 路由（不经 controller 类型），仍正确命中 workflow 端点。
- **PASS**：禁 type 不影响路由。**FAIL**：禁 type 后路由失败（路由依赖了 type）。

### D — 业务完成（查余额 + 转账）
- **Given**：A/B/C 通过。
- **When**：stepUi 跑完两腿转账。
- **Then**：汇总含余额 8200、收款人李四/王五；转账完成态（首轮软捕获，确认后提升为硬断言）；无堆栈泄露。
- **PASS**：业务完成。**FAIL**：余额/收款人缺失 / 堆栈泄露。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [MultiWorkflowDirectStreamingTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/MultiWorkflowDirectStreamingTest.java)（继承 `AbstractBalanceThenTransfersTest`） |
| 标签 | `@Tag("integration")`；Allure `@Feature("FEAT-002")` + `@Story("wf.workflow-direct")` |
| 两个 seam | `buildStack`：`serviceBinding("envexplorer", "VERSATILE_*_WORKFLOW_URL", ...)` 两条；`openConversation`：`.disableQueryParam("type")` |
| 参数化 | `@ParameterizedTest` A2A_STREAM / REST_QUERY（继承基类 final 模板） |
| 客户端 | `ConversationInteractionAdapter`（桥 Conversation 动态驱动到 plan-agent 线） |

## 运行方式

```bash
# ⏸ 当前 @Disabled；解除前需真机校准 6 点
mvn test -Dtest=MultiWorkflowDirectStreamingTest -Dtest.env=openjiuwen \
  -DLLM_API_KEY=... -DLLM_BASE_URL=... -DLLM_MODEL_NAME=...
```

## 覆盖追溯

| FEAT-002 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| story 2：多 workflow intent 路由 | A/B/C/D | ⏸ 已建禁用 |

## 清理策略

- 栈由类级生命周期管理；envexplorer 容器随栈销毁。

## 风险与备注

- **当前 `@Disabled`**：scenario 存在未解问题。解除禁用前需真机校准 6 点：① plan-agent 接受标准 A2A 文本一轮输入 ② taskId 续轮 ③ 直连无需 EDPA inputs 富化 ④ plan-agent `/v1/query` 可达 ⑤ intent 正确命中 workflow 端点（非 fallback）⑥ `multi-workflow` profile 正确叠加 base 配置。
- **serviceBinding 去重的机制意义**：YAML service-bindings 按服务名 1:1，做不到「两 key 指同一服务」；编程式 `serviceBinding` 让两占位符指向同一 `envexplorer`，框架按服务名去重只拉一个容器——这是 adapter 路由机制的载体侧实现。
- **与单 workflow 对照**：[PlanAgentDirectStreamingTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingTest.java) 是单 workflow（无 profile）同场景对照；本用例是其多 workflow 路由变体。
