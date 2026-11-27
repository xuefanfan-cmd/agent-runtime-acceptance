---
title: FEAT-109 测试设计初稿
feature_id: FEAT-109
document_type: test-design
status: draft
automation_status: design-only
feature_source: docs develop/02-features/FEAT-109-dangerous-operation-guardrail-and-hooks.md @0aa2f57b（2026-11-10 credential/resume 澄清后版本；原钉 3f00e2bb 已过期）
l2_source: docs develop/03-architecture/L2-Low-Level-Design/deepanalyze/FEAT-109-dangerous-operation-guardrail-and-hooks.md @66068d0b（sha256 00f196d1…）
requirement_preview: ./FEAT-109-110-111-需求与测试预览.md
change_scope: ./FEAT-109-110-111-变更包范围卡.md
input_maturity: mixed
updated: 2026-11-16
---

# FEAT-109 危险操作拦截与 hooks 测试设计初稿

## 0. 用户必读：需求与测试方案

- 一句话说明：AI 助手执行有风险动作前先问用户（允许/拒绝），另支持 7 类任务钩子在任务边界自动触发外部脚本/HTTP，其中工具前置钩子能拦截危险调用。
- 关键链路：用户经 DA 兼容端点发起任务（runtimeConfig 携带 hooks）→ 工具调用经 PreToolUse hook → 权限链（actrule 取严 + DA 预检）→ ask 挂起 → 用户经 /answer /message /resume 作答 → 工具执行或拒绝 → 事件流/hook_result 可观测。
- 本期测试：①权限三态与确认闭环（允许执行一次/拒绝无副作用/deny 无确认入口）；②hooks 7 事件 × command/HTTP 全矩阵（§3.3 每格）；③快照生效边界；④凭据金丝雀。
- 本期不测：callback 与其余 20 事件、modifiedInput、编排/委托写能力、热重载、永久授权（全部 OUT）。
- 未决影响（2026-11-17 复审裁决落定，PR !512）：**DV-109-02 已裁决（唯一口径，双轨废止）**——目标契约=L1 原键名 `deepanalyze.hooks.timeout` / `deepanalyze.hooks.max-response-bytes` / `deepanalyze.hooks.http-allowed-hosts`；`deepanalyze.compat.hooks.http-host-whitelist` 仅为弃用兼容别名（一个 release），双键不一致→启动失败指冲突键；timeout/max-response-bytes 未绑定=开发补齐项——测试按 L1 新键验（默认值/合法非默认/非法 fail-fast），旧别名仅独立兼容性用例，实现未补齐场景标 blocked/开发缺口、不得按旧键通过验收。DV-109-06（matcher 忽略面）维持 L2 口径扩展正向断言。DV-109-01/03/04/05/07 已撤销（01/07 随实现 !1181；03/04/05 随评审核验与 L1 `0aa2f57b` 澄清），详见 §4。**现行 hooks 配置面用例（F109-13/14/15/16 的 SUT 配置注入）重分类为兼容别名用例**，L1 新键场景登记 blocked·开发缺口（对账表 §8c）。
- 详细预览：[FEAT-109-110-111-需求与测试预览.md](./FEAT-109-110-111-需求与测试预览.md) §1A
- 本期范围：[FEAT-109-110-111-变更包范围卡.md](./FEAT-109-110-111-变更包范围卡.md)
- 输入成熟度：mixed（L1 merged/draft；L2 merged/draft 且自曝差距）；正式收口以正式制品为准。
- 用户理解确认：`confirmed`（2026-11-16 用户确认评审修订版；此前 pending）

## 1. 依据、当前口径与范围

- 设计真相源：L1（02-features）**优先**；L2 用于实现合同（端点形态、错误码载体、配置键实际名）。
- 已确认裁决：暂无（A-02 受控模型、DV-109 系列待裁决）。
- 范围内：L1 §2 能力表 23 行（MUST 19 + OUT 4）+ 验收出口 16 项 + §3 接口契约 + §5 行为语义；子执行体（插件/编排成员/委托）hooks 事件面与快照一致性（评审补充）。
- 范围外：范围卡 §4.1/§4.2。
- 未决问题：设计问题清单 §A + 偏差清单 §2.1；L2 问题 L2-03。

## 2. SUT 适用性

| SUT/参与者 | 角色 | 适用能力 | 不适用能力 | 结论与依据 |
|---|---|---|---|---|
| deepanalyze（DA 宿主，agent-solution） | 被测 SUT | 全部：权限链装配、确认闭环、hooks 执行 | — | L1 scope=deepanalyze-java（模块声明）；L2 类级设计全在 da 包 |
| 插件子 Agent（DA 内） | SUT 内参与者 | 写操作确认；hooks 触发 | 独立恢复（110 OUT） | L1 §1「本期确认覆盖主 Agent及已有 nested-resume 基础的插件子 Agent」 |
| 编排成员/委托执行体 | SUT 内参与者 | 只读工具经权限与 hooks 链；写工具不可达 | 写能力、interrupt 传播（OUT） | L1 §1/出口 7 |
| 受控 command/HTTP 接收端 | 测试装置 | hook 执行目标 | — | L1 §3.3 输入协议需要受控接收端取证 |
| 其他 SUT（reactagent/workflow/travel） | 不适用 | — | 全部 | 护栏装配是 DA 特有（DaPermissionHost/DaHookRail），归属唯一 |

## 3. 黑盒边界、拓扑与前置条件

- 拓扑：SutStack 拉起 deepanalyze-engine exec jar（批次专属 revision 源码构建，配方 ENV-3.5/ENV-3.20）→ HTTP 兼容端点（/run /run-stream /answer /message /resume /wait-user-answer /cancel /status /stream）→ SSE 观察事件流。
- SUT 配置（L2 §6 实际键）：`deepanalyze.compat.hooks.command-root`（受控工作目录根）、`http-host-whitelist`（指向测试侧接收端 host）、`max-reader-threads`、`wait-answer-timeout-ms`（缩短）、`awaiting-task-ttl-ms`、`deepanalyze.auth.enabled=true`（认证负向用例需要）。
- 前置：fixture 提供 write_file（ask）与 bash（ask）工具路径；actrule 资产已有 `delete_file: deny`（L2 §6.3）；受控 HTTP 接收端（记录 headers/body/重定向请求）；受控 command hook 脚本目录。
- LLM：真模型经 `~/.llmrc` 透传（ENV-4.1/4.2），不 Mock；模型自发旁路按铁律处置。

## 4. 需求覆盖矩阵

> 分母 = L1 §2 能力表 23 行 + 验收出口 16 项 + §3.3 结果矩阵逐格。偏差标注（DV-nn）指向偏差清单。实际结果与缺口只在覆盖台账维护，本节是设计视图。

<!-- coverage:scenarios:start -->
| 用例 ID | 场景（对齐出口） | 计划验证方式 | Oracle 口径 |
|---|---|---|---|
| F109-01 | 出口1a write_file 允许：确认前文件不存在→允许→文件只写一次、事件序号连续 | e2e 真机 | L1 §2/出口1；L2 §7.2 |
| F109-02 | 出口1b 重连不重复执行、重放事件保持原 ID | e2e | L1 出口1；L2 §5.1.5 |
| F109-03 | 出口2 拒绝：文件仍不存在、permissionDecision=denied、旧回答不授权新 toolCallId | e2e | L1 出口2/§5.1.1 |
| F109-04 | 出口3 受控 delete_file deny（无允许选项、非 UnknownTool）；内置关机 deny | e2e | L1 出口3；actrule `delete_file: deny`（B-01 已核） |
| F109-05 | 出口4 确认载荷（kind=permission_confirm/toolCallId/脱敏 guardrail）、旧式 approved Map/纯文本不能批准、唯一省略 ID 可恢复、多项省略 409 | e2e+integration | L1 出口4/§3.4；L2 §2.2 |
| F109-06 | 出口5 两工具并行 ask + 首轮/continuation early answer（queued、无双 continuation、ID 不串线、重复/无挂起 404） | e2e | L1 出口5；L2 §3.2 |
| F109-07 | 出口6a wait-user-answer timeout:true 工具未执行、任务仍待答、之后可续跑 | e2e | L1 出口6/§5.1.6 |
| F109-08 | 出口6b 待答 /cancel → cancelled；执行池饱和下清理失败不污染下一任务 | e2e | L1 出口6；110 L2 §3.6「尽力提交」→ 清理失败注入面 **blocked·待开发**（L2-03 关联） |
| F109-09 | 出口7 插件子 Agent 写确认/拒绝无副作用；编排成员只读可达、写不可达、interrupt 不伪装成功 | e2e | L1 出口7/§5.2 |
| F109-10 | 出口8 hooks 快照：T0 全程 A、T1 为 B、T1 空配置不执行 A；四类执行体快照一致 | e2e | L1 出口8/§5.1.2 |
| F109-11 | 出口9 PreToolUse deny 工具计数 0、后续 hook 与 PostToolUse 不执行；ask 恢复 PreToolUse 总次数仍 1；hook_result denied 完整字段 | e2e+integration | L1 出口9；L2 §3.4 |
| F109-12 | 出口10 AgentStart/Stop/StopFailure/AgentComplete 正向与顺序、matcher 精确/前缀/缺省、数组序、10s 超时、非 blocking 失败后继续、失败/权限拒绝→PostToolUseFailure | e2e+integration | L1 出口10/§5.1.4；L2 §2.4/§3.3 |
| F109-13 | 出口11a command stdin JSON 环境白名单（8 变量、无值不注入）、真实工具输入、受控工作目录 | integration | L1 出口11/§3.3；L2 §5.2 |
| F109-14 | 出口11b HTTP POST JSON、白名单两段式、凭据头不出界 | integration | L1 出口11b；初始非法目标受理前 400、零出网；合法初始目标的重定向出界执行时阻断（DV-109-03 撤销，两段式分场景断言） |
| F109-15 | 出口12 §3.3 结果矩阵逐格（空/空白/`{}`/allowed true·false/非 JSON/非对象/类型非法/modifiedInput/非零退出/超时/非 2xx/超限）+ 64KiB 正反用例 | integration（受控执行器逐格构造） | L1 §3.3；L2 §3.5；恰好 64KiB 正常、超 1 字节有界失败（DV-109-05 撤销，评审核实两条读取路径均有超限探测） |
| F109-16 | 出口13 enabled callback/未知事件/workdir 越界 → 建档前 400 无副作用；disabled 忽略；非法 matcher 仅 PreToolUse/PostToolUse 前置、其他事件忽略 | e2e | L1 出口13；**DV-109-06**：matcher 忽略面按 L2 口径扩展为正向断言 |
| F109-17 | 出口14 指纹轮换：MCP 类型/URL/env/authHeaders/hooks 各自变化 → 自算指纹变化重挂；完全不变才复用 | integration+e2e | L1 出口14；L2 §3.6（24 hex、前 24 字符） |
| F109-18 | 出口15 快速通道：push_content type!=file、db_query connectionId=list 仅放宽通用默认 ask；叠加 deny 必拒；框架 deny+场景 allow 保持 deny；工作区/联网/未知工具/edit 预检不回归 | e2e | L1 出口15/§5.1.1；L2 §3.1 |
| F109-19 | 出口16 金丝雀全表面：合成敏感语料（已登记凭据口径）放输入/配置/工具结果/hook 输入，覆盖正常/确认/拒绝/hook 失败/malformed 场景，§5.1.8 全表面检索 | e2e+全表面取证 | L1 出口16/§5.1.8（`0aa2f57b` 后按「已登记凭据」口径）；正常红绿验收：已测表面零命中 + 各副本脱敏一致（DV-109-04 撤销） |
| F109-20 | DV-109-01 衍生：重复 Rail/actrule 缺失 fail-fast | integration | L1 MUST；**DV-109-01 撤销**——!1181 已改为装配失败，转 runnable 执行验证 |
| F109-21 | DV-109-07 衍生：符号链接/realpath 逃逸 | integration | L1 出口11；**DV-109-07 撤销**——!1181 已做词法+真实路径双重 containment，转 runnable；动态符号链接场景按首跑对待（评审环境该用例被权限跳过） |
| F109-22 | L2 §7.4 点名：URI 构造异常隔离（畸形 URL/非法字符 hook → 不产生未捕获异常，按非法静态定义或 Execution 失败面处理） | integration | L2 §1.3.4「URI 构造异常未统一转换为 Execution」→ !1181 后畸形 URI 进入有界失败（评审二），按现行契约单栏断言 |
| F109-23 | 评审补充：插件/编排成员/委托三型 PreToolUse/PostToolUse（含失败事件）逐型触发 | e2e | 事件逐型到达、与父任务关联可溯、失败不导致会话中断 |
| F109-24 | 评审补充：同任务族三型执行体 hooks 快照一致性（全程与续跑） | e2e | 各执行体全程共用发起时快照；空配置不复用（**实现落点**：成员型经 workflow_run 路径承接——Feat109SubExecutorHooksE2EIT；插件/委托两型登记构造面缺口） |
| F109-25 | DV-109-02 裁决：L1 新键三值校验（timeout/max-response-bytes/http-allowed-hosts 默认值/合法非默认/非法 fail-fast） | integration | L1 契约；**blocked·开发缺口**（三键当前未绑定实现，恢复条件=开发绑定三键；不得按旧别名键通过验收） |
| F109-26 | DV-109-02 裁决：旧别名兼容与双键冲突启动失败 | integration | 别名独立配置按兼容语义生效（**现行为兼容别名用例族承接**——F109-13/14/15/16 的 SUT 配置面）；两键并存冲突→启动失败指冲突键（**待新键落地**，partial） |
<!-- coverage:scenarios:end -->

### 4.1 未承接与降级条目

- F109-08 清理失败注入面：blocked，恢复条件=开发提供故障注入面（110 L2 §3.6 明示"不能宣称清理提交必然成功"）。
- ~~F109-19 partial~~：已撤销（2026-11-16 评审），改正常红绿验收（已登记凭据口径）。
- ~~F109-20 blocked / F109-21 partial~~：已撤销（!1181 修复），转 runnable；F109-21 动态符号链接场景按首跑对待。
- F109-22：!1181 后畸形 URI 有界失败，按现行契约单栏断言（原对账缺口 #1 关闭）。

## 5. 端到端场景

旅程一（确认闭环）：用户发起"创建报告文件"任务（runtimeConfig 无 hooks）→ write_file 触发 ask → SSE 收到 kind=permission_confirm → 用户 /answer 允许 → 文件落盘一次 → 任务 completed，事件序号连续。
旅程二（hooks 全链）：任务携带 hooks（PreToolUse command + PostToolUse HTTP + 生命周期）→ 工具调用依次经过：hook A deny/allow → 权限 ask → 恢复（不重放 PreToolUse）→ 工具 → PostToolUse HTTP 收到真实输入 → Stop/AgentComplete 顺序 → hook_result 事件全程可查。
旅程三（多任务快照）：T0 带 hooks A 启动并挂起 → 修改配置为 B → T0 恢复（仍用 A）→ 发 T1（用 B）→ 发 T2（空配置，无 hook）。

## 6. 详细用例（关键判定规则，其余按矩阵要点执行）

### F109-01 write_file 允许闭环

- 可执行性/优先级：runnable / P0
- 计划验证形态：execution
- Oracle 来源：L1 §2 确认闭环 + 出口 1；L2 §7.2 写操作允许
- Given：actrule write_file=ask 的 SUT；目标文件路径唯一
- When：任务触发 write_file → 收 ask_user → /answer{"answer":"允许","toolCallId":...}
- Then：确认前文件不存在；允许后存在且内容唯一；工具执行计数=1；事件流 seq 连续无重复
- PASS：全部成立。FAIL：文件写 0 次或 2 次、序号跳变。INCONCLUSIVE：模型旁路（未发 write_file 调用）→ 按证据门禁 10 处置
- 配置生效/下游触达证据：SUT 日志确认 actrule 装载；SSE ask_user 原文
- 调用增量与唯一数据：唯一 taskId/文件名；文件系统状态前后对照
- 不应断言：模型答复措辞；deny 路径行为（归 F109-03）
- 自动化类/方法：`Feat109ConfirmLoopE2EIT` / `allowWriteFileExecutesOnce` / @Feature("FEAT-109: 危险操作拦截与 hooks") / @Story("confirm.allow: 允许后只执行一次") / @Tag("feat-109")

### F109-15 hooks 结果矩阵（参数化）

- 可执行性/优先级：runnable / P0
- Oracle 来源：L1 §3.3 矩阵 12 格 × PreToolUse/非 blocking 两列；L2 §3.5（含 NO_OPINION/INVALID/DENY 唯一判定）
- Given：受控 command hook（可编程 stdout/退出码/延迟）与受控 HTTP 端点（可编程 status/body）
- When：逐格构造（空 stdout、纯空白、`{}`、allowed true/false、非 JSON、顶层数组、allowed 类型非法、modifiedInput、非零退出、10s 超时、非 2xx、网络错误）
- Then：PreToolUse 列按 allow/deny 落到工具执行计数；非 blocking 列按 completed/denied/failed/timeout 落 hook_result 且业务结果不变
- 64KiB 边界：恰好 64KiB 正常执行；超 1 字节有界失败（DV-109-05 撤销，两读取路径均有探测）
- 自动化：`Feat109HookMatrixIT`（@Story 按 `hook.matrix.<格名>`）

### F109-19 凭据金丝雀

- 可执行性/优先级：runnable / P1
- Oracle 来源：L1 出口 16 + §5.1.8 表面清单（`0aa2f57b` 后为「已登记凭据」口径）
- Given：runtimeConfig（stdio env、authHeaders）、hooks 输入、工具参数中埋合成金丝雀（已登记凭据）
- When：跑正常/确认/拒绝/hook 失败/malformed 五场景
- Then：对 §5.1.8 逐表面（应用日志、SSE、hook_result、workflow_event、workflow_logs、session_memory、trajectory Redis、checkpoint、OTel span）grep 金丝雀
- 判定：正常红绿验收——已测表面命中即 FAIL；未命中 + 存储调用/SSE/结果卡/特殊事件各副本脱敏值一致 → PASS（DV-109-04 撤销，预埋命中探针删除）；确实不可达的表面如实登记 blocked 并给恢复条件
- 自动化：`Feat109CanaryE2EIT` + 金丝雀检索脚本落 evidence/

## 7. Fixture、环境与确认门禁

| 缺口 | 为什么需要 | 拟改内容 | 影响 | 当前授权状态 |
|---|---|---|---|---|
| 受控 command hook 脚本目录 | F109-13/15 需要可编程 hook | 测试侧脚本目录（SUT command-root 指向） | 无业务代码改动 | 待用户确认（执行阶段落位） |
| 受控 HTTP hook 接收端 | F109-13/14/15 HTTP 格 | 测试进程内 HTTP server | 无 | 待用户确认 |
| SUT 启动配置注入 | F109-07 缩短 wait-answer-timeout、F109-14 白名单、auth.enabled | SutStack 配置覆盖 | 无业务改动 | 既有能力（FEAT-069 先例），执行时列清单 |
| deepanalyze-engine 批次制品 | 全部真机用例 | 源码构建批次 revision | 隔离 m2repo | 执行阶段按 ENV-3.5/3.20 |
| 模型凭据 | 全部 e2e | `~/.llmrc`（已具备，ENV-4.1） | — | 已解除 |

## 8. 自动化落点与精确执行规划

- 测试仓：`agent-runtime-acceptance` 批次隔离 worktree（ENV-5.8 纪律，run-spec 记路径）；新类落 `cases/e2e/deepanalyze_guardrail/`（e2e，failsafe）与 `cases/integration/...`。
- 命令形态按 ENV-5.9/ENV-5.10（failsafe 直连 + SUT_M2_REPO + 必需变量名清单）。
- Allure/Tag：@Tag("feat-109")；类级 @Feature("FEAT-109: 危险操作拦截与 hooks")。
- 执行前置核实：SUT 配置键生效证据（yml 注入）、受控接收端监听、actrule 资产指纹。

## 9. 状态汇总与退出标准

- 状态维度：输入成熟度 mixed；可执行性 runnable（偏差项 partial/blocked）；自动化 design-only。
- 退出标准：矩阵 23 行 + 16 出口逐条有承接方与执行形态证据；partial/blocked 项在覆盖台账登记缺口与恢复条件；DV-109-02（配置键归属）待契约裁决，裁决后单栏化；F109-21 动态符号链接场景按首跑取证。

## 10. 负结论归因记录

| 结论 ID | 结论 | 候选解释 | 排除依据 | 仍未排除项 | 下一步探针 |
|---|---|---|---|---|---|
| （设计期无负结论） | — | — | — | — | — |

## 最小可执行例子

- 机制 A（确认闭环）：输入声明=write_file 请求（唯一路径）→ 拟发 POST /run-stream + runtimeConfig → 入口=DA 兼容端点+actrule 资产 → 可观察=ask_user SSE、文件系统、SSE seq → 证据=e2e 日志+事件流 dump。
  代表性：材料来自 L1 §4 场景表首行；保留真实模型驱动（模型自发调用 write_file）；差异=文件路径固定唯一。
  故障与主断言：若确认后工具执行 0 次或 2 次，F109-01 主断言（执行计数=1 且文件存在）应失败。
- 机制 B（hooks 执行器）：输入声明=hooks 数组（7 事件 × command/HTTP）→ 拟发同上+受控接收端 → 可观察=stdin/body 原文、hook_result 事件 → 证据=接收端 dump+SSE。
  故障与主断言：若 PreToolUse deny 后工具仍执行，F109-11 主断言（计数=0）应失败。
