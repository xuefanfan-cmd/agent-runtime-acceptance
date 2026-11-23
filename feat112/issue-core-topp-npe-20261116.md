# core develop `e24bcb74d` 与 solution common `1127a9ece7b` 组合下工作流成员无法构建（2026-11-16）

FEAT-112 实跑 E5.4 时发现，不属于降级门本身。未对外提交，待用户决定转给谁。

## 现象

载体按约定由 core `origin/develop`（`e24bcb74dfa2b68ac674ec94c4f4b861cd881850`）与 solution `origin/common`（`1127a9ece7b2994e0a96f13e45ad943bc83ad9a9`）构建（构建清单 `D:/m2-feat112/feat112-build-manifest.json`）。主 Agent 调用 `workflow_run`（`mode=single`，一个成员）后，工作流在构建成员时即失败，成员没有发出任何模型调用：

```
event: workflow_event
data: {"type":"workflow_complete", ... "status":"failed","resultCount":0,
       "error":"Cannot invoke \"java.lang.Double.doubleValue()\" because the return value of
                \"com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig.getTopP()\" is null", ...}
event: tool_result
data: {"toolName":"workflow_run","output":"Ability execution error: Cannot invoke \"java.lang.Double.doubleValue()\" ..."}
```

证据：`target/feat112-evidence/FEAT-112-E5.4-ee286ff3-6c60-453a-9971-44400fb9953f/002.json`（运行 `e54-20261116`）。

## 原因

- core `e24bcb74d`（2026-11-16 10:55，“fix: 完善模型客户端恢复与请求用量契约”）把 `core/src/main/java/com/openjiuwen/core/foundation/llm/schema/ModelRequestConfig.java` 的 `private double topP = 0.1;` 改为 `private Double topP;`，默认值由 0.1 变为 null。
- solution `agents/deepanalyze-java/engine/.../workflow/DaWorkflowRunner.java:1012`（`memberRequestConfig`）：`double topPFallback = ModelRequestConfig.builder().build().getTopP();` 自动拆箱 null → NPE。该行在 `top_p` 是否配置之前无条件执行，部署侧配置 `top_p` 也绕不过。
- 两边 pom 版本仍都是 `0.1.17`，依赖版本对不出这次不兼容。

## 影响

- 所有经 `memberRuntime` 构建的成员（`workflow_run` 成员、`delegate_task`、技能子 Agent、team 成员）在该组合下都无法运行；主 Agent 自身不受影响（FEAT-112 其余场景不经成员路径）。
- FEAT-112：E5.4（成员因护栏拒绝耗尽、主任务正常完成）在该载体上无法激活，结论记 conditional_not_triggered，不是降级门违规。

## 修复方向（供开发判断）

- solution 侧按可空处理：`Double fallback = ModelRequestConfig.builder().build().getTopP();` 为 null 时用本地缺省（原 0.1）或不设置 `topP`；或
- core 侧保留 0.1 缺省或在构建器中补默认值，并在发布说明中说明可空化。
