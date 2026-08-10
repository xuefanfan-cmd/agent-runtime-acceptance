---
title: core-ext 接口文档（agent-core-ext-java）
description: react-rails——给 ReActAgent 补 verify / replan / self-heal 三条认知 rail（自包含参考）
audience: ai-coding
---

# core-ext 接口文档（react-rails）

react-rails 是纯 Java SDK（无 Spring、无自动配置），给 agent-core-java 的 `ReActAgent`
补三条**认知 rail**。背景：ReActAgent 原生只有 reason+act 循环，没有独立的 verify 环节；
react-rails 通过 `afterModelCall` 钩子的 `forceFinish` gate 补上外部裁判验证、
replan 计数与故障自愈。所有 rail 由应用**显式注册**。

所需 artifact 为 `com.openjiuwen:agent-core-ext-react-rails`；当前推荐版本见
[版本兼容与上游锚点](../compatibility.md)。

## 最小用法

```java
import com.openjiuwen.agents.reactrails.enforcing.CriteriaVerificationRail;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.selfheal.RootCauseRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;

import java.util.List;

// agent 为已配置好 LLM 的 ReActAgent
agent.registerRail(new CriteriaVerificationRail(
        new RuleBasedCriteriaVerifier(),
        List.of("必须包含金额", "必须引用风险评估")));   // rail 1：外部裁判
ReplanRail replanRail = new ReplanRail(2);             // rail 2：最多 2 次 replan
agent.registerRail(replanRail);
agent.registerRail(new RootCauseRail());               // rail 3：故障自愈降级
ReplanTool.registerOnto(agent);                        // 让 LLM 能显式表达 replan 意图

Object result = agent.invoke("分析这个投资组合", null);
```

> ⚠️ forceFinish 路径下 `invoke` 返回的是**结构化 map**（含 `verified` / `degraded` /
> `unmet` 等键），不是纯字符串——消费方要按 map 处理。

## 三条 rail 对照

| Rail | 补的能力 | 机制 | 关键类 |
| --- | --- | --- | --- |
| 外部裁判验证 | 最终答案须满足 criteria，否则判 degraded | `afterModelCall` 检测最终答案 → `CriteriaVerifier.verify()` → PASS `forceFinish(verified=true)` / FAIL `forceFinish(degraded, unmet=[...])` | `CriteriaVerificationRail`、`RuleBasedCriteriaVerifier`（规则版，零 LLM） |
| Replan 计数 | LLM 反复换策略不收敛时 escalate | 检测 `__replan__` tool_call 并计数，超限 `forceFinish(degraded)` | `ReplanRail(maxReplan)`、`ReplanTool` |
| 根因自愈 | 工具/设备故障时分析根因并降级收尾 | 故障信号 → 根因分析 → 受控结束 | `RootCauseRail` |

## 模块边界

`JiuwenCoreAgentExtHandler` 不属于 core-ext；它位于 agent-solution 的
`agent-service-adapters-agentcore-ext` artifact。远端 A2A 工具与 SkillHub 注入见
[runtime-ext 接口文档](runtime-ext.md)，不要因为名称中含 `Ext` 就从 react-rails artifact 引入。

## API 锚点（jar 内类，按依赖可查）

- rail：`com.openjiuwen.agents.reactrails.enforcing.CriteriaVerificationRail`、
  `...reactrails.replan.ReplanRail` / `ReplanTool`、`...reactrails.selfheal.RootCauseRail`、
  `...reactrails.verification.RuleBasedCriteriaVerifier`

## See also

- [agent-core-java 接口文档](agent-core-java.md)
- [runtime-ext 接口文档](runtime-ext.md)
