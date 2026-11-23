# core：工作流成员 topP 可空化后 NPE，成员一律无法构建（阻塞 FEAT-112 E16–E18）

日期：2026-11-20（首记 2026-11-16，详稿 `feat112/issue-core-topp-npe-20261116.md`）
性质：上游缺陷，接收方 core（openJiuwen/agent-core-java），solution 侧有对应整改点。
草稿（checklist I-M7），按 Issue 模板补齐；2026-11-20 在 core 最新 develop 复核仍存在。

## 结论摘要

core `e24bcb74d`（2026-11-16）把 `ModelRequestConfig.topP` 从 `private double topP = 0.1` 改为可空，solution `DaWorkflowRunner.memberRequestConfig`（约 :1012）`double topPFallback = ModelRequestConfig.builder().build().getTopP()` 自动拆箱 null 抛 NPE。所有经 `memberRuntime` 构建的成员（`workflow_run`、`delegate_task`、技能子 Agent、team 成员）在该组合下无法运行。core 最新 develop `f777aa244`（新增 4 提交）未修。

## 场景说明

主 Agent 调用 `workflow_run`（`mode=single`，1 个成员）。成员构建即失败，未发出任何模型调用：

```
event: workflow_event
data: {"type":"workflow_complete", ... "status":"failed","resultCount":0,
       "error":"Cannot invoke \"java.lang.Double.doubleValue()\" because the return value of
                \"...ModelRequestConfig.getTopP()\" is null", ...}
```

主 Agent 自身不受影响（不走成员路径）。

## 影响面

- 波及：该 core+solution 组合下**一切成员路径**——workflow、delegate、技能子 Agent、team；与 `top_p` 是否配置无关（fallback 构建即触发）。确定性，非偶发。
- 对 FEAT-112：E16 的前置（成员被护栏拒绝耗尽）走不到，判 `conditional_not_triggered`；E17、E18 的裁决验证同被阻塞。对其它依赖成员路径的特性测试同理。
- 版本治理面：两边 pom 均 `0.1.17`，依赖版本号对不出这次不兼容——可空化属破坏性契约变更但未体现在版本/发布说明。

## 实测证据

- NPE 原文：测试仓 `target/feat112-evidence/FEAT-112-E5.4-ee286ff3-6c60-453a-9971-44400fb9953f/002.json`（运行 `e54-20261116`，SSE 全文）。
- 2026-11-20 Linux 复核：结果包 `target/acceptance-results/linux-rest-1/` E16 记录（`Feat112MemberGuardrailTest#memberExhaustedByDenialsThenTaskCompletes`，前置不成立）。
- 载体：core `f777aa244` + solution `96c23d4ea`（清单 `feat112-build-manifest.json`，sha256 `cdb5c93e…`）。

## 代码层定位

- core：`core/src/main/java/com/openjiuwen/core/foundation/llm/schema/ModelRequestConfig.java`，`e24bcb74d` 起 `topP` 无缺省、getter 返回可空 `Double`。
- solution：`common/agents/deepanalyze-java/engine/.../workflow/DaWorkflowRunner.java` `memberRequestConfig`（约 :1012）`double topPFallback = ...getTopP()` ——拆箱点。
- 调用链：`workflow_run` → `memberRuntime` 构建成员 → `memberRequestConfig` → NPE；`e24bcb74d..f777aa244` 的 4 个新提交不涉 topP（已逐一核对）。

## 判据

契约层面：core 对外 schema 的缺省值移除属破坏性变更；消费方（solution 已合入 common 的代码）因此崩溃。非“预期行为”。

## Pre-flight 自查与历史

1. 全字段扫描：SSE `workflow_complete` 与 `tool_result` 两通道均为同一 NPE，无其它字段携带成功结果。2. 跨通道：SSE、任务终态、DA 日志三处一致。3. 字段核对：引用的均为对外 SSE 契约字段。历史：2026-11-16 发现并成稿，无更早相关 issue；与 FEAT-112 自身诸问题无机制关联。

## 复现条件

core `origin/develop@f777aa244` + solution `origin/common@96c23d4ea` 构建载体（或任一 `e24bcb74d` 之后的 core），主 Agent 发起带一个成员的 `workflow_run`。或跑测试仓 `Feat112MemberGuardrailTest`（E16）。

## 预答可能的反问

- “为何不固定用已发布 core 0.1.17？”——FEAT-112 验收约定载体用 core develop 最新 + solution common 最新源码构建（构建清单随包）；该组合正是集成现状。
- “solution 改一行就好，算 core 的问题吗？”——两侧都可修（见下）；提给 core 是因为破坏性契约变更未声明，同类拆箱点可能不止一处。

## 修复建议与验收

- solution 侧：`Double fallback = ...getTopP()`，null 用本地缺省（原 0.1）或不设置；或 core 侧恢复缺省/构建器补默认，并在发布说明声明可空化。
- 验收：`Feat112MemberGuardrailTest#memberExhaustedByDenialsThenTaskCompletes`（E16）从 `conditional_not_triggered` 变为实判（前置可达）；E17/E18 的裁决验证解除阻塞。
