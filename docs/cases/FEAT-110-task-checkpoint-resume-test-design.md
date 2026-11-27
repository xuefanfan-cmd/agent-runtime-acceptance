---
title: FEAT-110 测试设计初稿
feature_id: FEAT-110
document_type: test-design
status: draft
automation_status: design-only
feature_source: docs develop/02-features/FEAT-110-task-checkpoint-resume.md @0aa2f57b（2026-11-10 credential/resume 澄清后版本；原钉 d4ab64ad 已过期）
l2_source: docs develop/03-architecture/L2-Low-Level-Design/deepanalyze/FEAT-110-task-checkpoint-resume.md @66068d0b（sha256 6a7e9ead…）
requirement_preview: ./FEAT-109-110-111-需求与测试预览.md
change_scope: ./FEAT-109-110-111-变更包范围卡.md
input_maturity: mixed
updated: 2026-11-16
---

# FEAT-110 任务断点续传测试设计初稿

## 0. 用户必读：需求与测试方案

- 一句话说明：任务等用户回答时，新增正式恢复端点 `/resume`——带幂等键重试不会重复执行，等待期间追加的指令不丢失，事件流接着原序号走。
- 关键链路：任务挂起（ask/权限确认）→ 状态面显示 resumable=true → /resume（Idempotency-Key）受理 202 → 原任务续跑 → 事件流连挂 → 唯一业务终态；同键重试 200 duplicate。
- 本期测试：①恢复端点全错误矩阵（400/404/409/503）；②幂等（同键重放/不同载荷冲突/受理前失败不烧键/终态重放）；③单飞；④事件流连挂与游标；⑤steering FIFO 与终态线性化；⑥early answer 兼容；⑦金丝雀。
- 本期不测：进程重启恢复、跨实例、终态复活、团队/子 Agent 独立恢复（OUT）；出口 14 的 404 负向属本期。
- 未决影响（2026-11-17 复审裁决落定，PR !513）：**两项均已裁决（唯一口径，双轨废止）**——① 202 后 CONVERSATION_BUSY=准入/调度回滚非执行失败：不发 error/done{failed}、不产生失败终态 fact、保持 awaiting_answer/resumable=true、重发原 ask_user（同 toolCallId 新 seq）、幂等键不删（同键重放 200 duplicate）、取消竞态仍 cancelled；仅续跑段回滚，首轮撞 busy 仍落终态。F110-23 按此**单轨冻结**，已实现（Feat110AdjudicatedE2EIT，run-05c/05d/05e 三连绿）。② 多问题 answers=结构化模型：问题键→回答对象、与 questions 全集精确匹配（缺/额外/空白/超长/权限误用 400）、answer 选填展示（缺席生成编号摘要）、幂等摘要覆盖 answers。F110-24 按此**冻结断言**，已实现（同类，run-05e 绿）。L1/L2 回灌待设计 owner（落地后台账绑定一并做）。原 DV-110-01~06 全部撤销：01/02/05 随实现 !1181；03 随 L1 `0aa2f57b` 契约对齐；04 随 L1 `0aa2f57b` 收编明文契约；06 随 !1181 读面同步逐出。
- 详细预览：[需求预览](./FEAT-109-110-111-需求与测试预览.md) §1B；范围卡 §5。
- 输入成熟度：mixed；正式收口以正式制品为准。
- 用户理解确认：`confirmed`（2026-11-16 用户确认评审修订版；此前 pending）

## 1. 依据、当前口径与范围

- 设计真相源：L1 优先；L2 为实现合同。**偏差面 Oracle 按「L1+proposal+L2 并集」双栏设计**（DV-110-08），裁决前标 partial。
- 已确认裁决：暂无。
- 范围内：L1 §2 能力表 17 行（MUST 13 + OUT 4）+ 出口 17 项 + §3 全部端点契约 + §5 语义；proposal 已实装表面（回滚重发 ask_user、TTL、max-awaiting-tasks、inject 满桶 409）；评审补充覆盖见 §4 F110-23~28。
- 范围外：范围卡 §4。
- 未决问题：偏差清单 §2.2；L2-01/02。

## 2. SUT 适用性

| SUT/参与者 | 角色 | 适用能力 | 不适用能力 | 结论与依据 |
|---|---|---|---|---|
| deepanalyze（DA 宿主） | 被测 SUT | /resume、/answer、/message、/inject、/cancel、/status、/tasks、/stream 全部 | 跨实例/重启恢复 | L1 module=deepanalyze-java |
| D2 前端 | 调用方（测试代打） | 携带幂等键与游标 | 按钮实现 | L1 §1.2 D2 只做展示；测试以 HTTP 客户端等价代打 |
| 旧客户端 | 调用方 | /answer /message 无键 | — | 兼容面 MUST |
| 其他 SUT | 不适用 | — | 全部 | 恢复链是 DA compat 面特有 |

## 3. 黑盒边界、拓扑与前置条件

- 拓扑同 109；SUT 配置追加：`max-concurrent-tasks`（饱和注入用，可调小）、`max-injections-per-task`、`awaiting-task-ttl-ms`（TTL 用例缩短）、`completed-ttl-ms`（过期用例缩短）、`deepanalyze.auth.enabled=true`（出口 9 认证负向）。
- 关键构造手段：
  - ask_user 挂起：fixture 任务用真模型触发提问（或工具 ask）。
  - 执行池饱和：将 `max-concurrent-tasks` 调小（如 1）+ 占位任务在途。
  - steering 满桶：同任务连续 inject 至 max-injections-per-task。
  - 终态竞态窗口：模型响应后收尾前的窗口——用长收尾工具/大事件流放大窗口（受控手段待执行期验证，若不可控标 partial）。
  - 进程重启 404（出口 14）：SutStack 重启 SUT 进程能力待核实（B-04）；若不可行，以"新 sessionId 引用不存在 taskId"等价构造 404 面并登记降级。
- 双队列/超长载荷（出口 17）：~~七配置键未实现（DV-110-02）~~ 已随 !1181 实现——按现行契约执行：超长一律 400、steering/early answer 队列满 409 `ENGINE_INJECTION_QUEUE_FULL`、执行池饱和/同键等待超时 503 `ENGINE_BUSY`（分族断言）。

## 4. 需求覆盖矩阵

<!-- coverage:scenarios:start -->
| 用例 ID | 场景（对齐出口） | 计划验证方式 | Oracle 口径 |
|---|---|---|---|
| F110-01 | 出口1 /resume 基础恢复：202、原 taskId、原始输入不重跑、从等待点继续完成 | e2e | L1 出口1；L2 §7.2 |
| F110-02 | 出口2 事件流连挂：断连重连 seq 连续、无第二个 start、历史实时交界无重复、唯一业务 done | e2e | L1 出口2/§3.4 |
| F110-03 | 出口3a steering FIFO：等待期两条 inject → 恢复后模型请求记录各消费一次 | e2e | L1 出口3/§5.1.7 |
| F110-04 | 出口3b 终态竞态：最后模型响应后、终态落定前 inject → 暂扣终态续跑；终态后 inject 拒绝；重复恢复/重连不重复注入 | e2e | L1 出口3b/§5.1.7；L2 §3.5 同锁线性化（主链）；**窗口构造受控性执行期验证，不可控降 partial** |
| F110-05 | 出口4 活跃非待答 /resume → 409 TASK_NOT_RESUMABLE、无第二次执行；仅 /stream 可观察 | e2e | L1 出口4 |
| F110-06 | 出口5 同键重放四态：在途（等首发落定不重复执行）、完成后、失败后、取消后重试 → 200 duplicate:true 不新增副作用；规范化等价同载荷（map 序）；不同载荷 409 CONFLICT；两 taskId 同键隔离；幂等记录随终态留 30 分钟、过期 404 | e2e+integration | L1 出口5 现行口径（`0aa2f57b` 后明文）；L2 §3.2/§5.2（digest、规范化）——DV-110-01 撤销（等待可配置、超时 503） |
| F110-07 | 出口6 不同键并发单飞：恰一个 202/continuation；旧入口待答续跑不双执行 | e2e | L1 出口6/§5.1.6 |
| F110-08 | 出口7 恢复后再中断仍可恢复（无业务 done）；唯一省略 ID 可恢复、多项省略 409 TARGET_REQUIRED、未知/跨任务/已答 ID 404 且不消耗资格 | e2e | L1 出口7；L2 §2.1 |
| F110-09 | 出口8 状态发现：awaiting（awaiting_answer/true）→ 在途（resume_in_flight/false）→ 再次待答 → 终态（none/false）；lastEventId 可空游标契约（未分配=null、GC 后=null 表示游标不可用非序号归零、历史逐出不回退） | e2e | L1 出口8 现行口径（DV-110-04 撤销，`0aa2f57b` 收编为明文契约） |
| F110-10 | 出口9 错误面：空回答/空键/非法 toolCallId 400 VALIDATION；错误 session/未知/过期同形 404；活跃/在途/终态新键 409；认证（auth.enabled=true 时无密钥被拒） | e2e | L1 出口9；L2 §2.1/§7.3；auth 负向需部署开启（DV-110-09 已列部署项） |
| F110-11 | 出口10 执行池饱和：503 BUSY+Retry-After 5、回滚预约/单飞/重发原 ask_user、原任务仍可恢复无 error+done；释放后同键同载荷成功 | e2e | L1 出口10；L2 §3.2/§7.2（重发原提问=proposal ②已实装）；L1 未含重发面 → Oracle 按 L2+proposal，标 partial |
| F110-12 | 出口11 受理后模型失败：SSE error+done{failed}；同键 200 duplicate 重放；新键 409；不建新 taskId | e2e | L1 出口11；L2 §2.1 |
| F110-13 | 出口12 游标重放：缓冲内精确后缀；未知/过旧游标重放留存事件、客户端按 ID 去重、可判"历史不完整"；GC 后 null 按契约断言 | e2e | L1 出口12/§3.4 现行口径（DV-110-04 撤销）；L2 §1.3.6 受保护事件逐出差异登记 |
| F110-14 | 出口13 同步 /run 任务待答后同一恢复端点、事件仍归原 taskId | e2e | L1 出口13 |
| F110-15 | 出口14 进程重启/无本地记录 → 同形 404 不新建执行（负向边界用例） | e2e | L1 出口14；B-04 SUT 重启能力待核实——不可行时降级为"不存在 taskId 404"等价构造并登记降级 |
| F110-16 | 出口15 early answer：首轮与 continuation 中对另一已发确认 /answer /message → queued 不启第二 continuation；到等待点消费不丢失；队列满 409 `ENGINE_INJECTION_QUEUE_FULL` 不返回 queued（DV-110-05 撤销，!1181 独立容量） | e2e | L1 出口15 现行口径/§5.1.3；L2 §3.4 |
| F110-17 | 出口16 金丝雀：首次受理/幂等命中/503/受理后失败路径放合成回答与 steering 金丝雀 → 恢复日志与新增幂等记录无明文 | e2e+日志取证 | L1 出口16/§5.1.8；L2 §4.3（只存 64hex digest） |
| F110-18 | 出口17 现行口径全部可执行：超长请求体/回答/幂等键/工具调用 ID → 400 不消耗恢复资格；steering/early answer 队列满 → 409 `ENGINE_INJECTION_QUEUE_FULL`；执行池饱和/同键等待超时 → 503 `ENGINE_BUSY`（分族断言） | integration | L1 出口17 现行口径（DV-110-01/02/03 撤销：01/02 随 !1181、03 随 `0aa2f57b` 409 专用码契约对齐） |
| F110-19 | proposal ⑤ 衍生：awaiting-task-ttl 到期取消（落 cancelled 完整收口、非静默消失）；还原待答重置 TTL 锚；同会话其他任务不受牵连 | e2e | proposal-110 ⑤+109 §5.1.6 预授权；L2 §3.6；**L2-02**：扫描周期未述 → 只断言"最终取消"，标 partial |
| F110-20 | L2-01 衍生：max-awaiting-tasks 满载行为 | e2e | 503 `ENGINE_BUSY` + `Retry-After`、不建档、名额释放后恢复受理（blocked 解除：错误面已明确，评审三确认） |
| F110-21 | DV-110-07 旁路观察：镜像事件与终态非严格线性化 | 观察 | L2 §1.3.10 → 不做强断言，登记观察项 |
| F110-22 | L2 §7.4 点名：取消槽位所有权——abort 仅当执行槽明确归属本任务；槽位缺失/他任务占用时不发 abort，由任务级取消标记协作收口 | e2e | L2 §3.6/§7.4 → L1 无对应行，Oracle 按 L2、标 partial（对账表缺口 #3）；关联 G-110-b |
| F110-23 | 评审补充：受理后 busy 独立分区——回滚、原 ask_user 原样重发、同键可重试，与普通模型失败分区断言 | e2e | **11-17 裁决冻结（!513）**：busy=准入回滚无终态（不发 error/done{failed}、保持 awaiting、重发原 ask_user、同键 200 duplicate）；已实现 Feat110AdjudicatedE2EIT#busyContinuationRollsBackWithoutTerminal（run-05e 绿） |
| F110-24 | 评审补充：多问题 answers 结构断言 | e2e | **11-17 裁决冻结（!513）**：问题键→回答对象、与 questions 全集精确匹配、缺/额外/空白/超长/权限误用 400、answer 选填（缺席编号摘要）；已实现 Feat110AdjudicatedE2EIT#answersStructuredContract（run-05e 绿） |
| F110-25 | 评审补充：回答类型分流（权限确认 vs 普通提问） | e2e | 两种等待点消费语义互不串扰 |
| F110-26 | 评审补充：待答直接 cancel | e2e | 取消收口完整、幂等键失效、重放不复活 |
| F110-27 | 评审补充：回答与 TTL 竞态 | e2e | 到期前受理则消费、到期后拒绝收口、无中间态 |
| F110-28 | 评审补充：父任务 TOOL_INTERRUPT 时委托保留 | e2e | 委托子任务保留可续、不随父任务清理 |
| F110-29 | 对账 GAP-110-cfg：启动期非法配置（容量/等待参数）启动失败并指键名 | 启动验证 | L1 现行散布条款（容量为正数、等待超时>0）；七键已实现后可黑盒验证 |
| F110-21 | DV-110-07 旁路观察：镜像事件与终态非严格线性化 | 观察 | L2 §1.3.10 → 不做强断言，登记观察项 |
| F110-22 | L2 §7.4 点名：取消槽位所有权——abort 仅当执行槽明确归属本任务；槽位缺失/他任务占用时不发 abort，由任务级取消标记协作收口 | e2e | L2 §3.6/§7.4 → L1 无对应行，Oracle 按 L2、标 partial（对账表缺口 #3）；关联 G-110-b |
<!-- coverage:scenarios:end -->

### 4.1 未承接与降级条目

- F110-18：~~blocked→partial（探针替代）~~ 已撤销（DV-110-01/02 随 !1181 实现、03 随契约对齐），按现行口径全量执行；F110-04/11/19 partial（构造/口径）维持；F110-22 partial 维持。
- ~~出口 17 的"双队列满 503"部分（early answer 容量）无实现 → 不承接~~：已撤销（DV-110-05 随 !1181 实现）。
- **G-110-a（2026-11-09 对账补登记）**：跨实例接管 OUT——单实例部署下无可执行负向构造；收口按 OUT+「无 runnable 构造」登记，不冒充已验证。
- **G-110-b（2026-11-09 对账补登记）**：drain 后饱和不重试（L2 §3.6「待答清理 continuation 尽力提交且不推送；池饱和只记日志不重试」）——与 F109-08 共用"清理失败注入面"（同源 blocked），不重复建用例；恢复条件同 F109-08（开发提供注入面）。

## 5. 端到端场景

旅程一（恢复主干）：任务 ask_user 挂起 → status 显示 awaiting_answer/true → /resume 202 → 事件流续挂 → completed 唯一终态；客户端重试同键 → 200 duplicate。
旅程二（故障面）：占满执行池 → /resume 503 → 原任务仍待答且原 ask_user 重发 → 释放容量 → 同键成功。
旅程三（steering）：挂起 → inject 两条 → 恢复 → 模型请求含两条（FIFO）；终态前 inject → 终态暂扣续跑。

## 6. 详细用例（关键判定规则）

### F110-06 同键幂等族（参数化）

- 可执行性/优先级：runnable / P0
- Oracle 来源：L1 出口 5 + §3.1 处理优先级；L2 §3.2 状态机 + §7.2 三未落定场景
- Given：待答任务 + 幂等键 K
- When/Then 分支：
  1. 首次 202 `{taskId,status:"resumed",duplicate:false,streamUrl}`（响应回显值口径，见 A-01 留档）；
  2. 固化后同键同载荷 200 duplicate:true，重放首次 202 对应受理语义，即使任务已终态；
  3. 同键不同载荷（answer 改一字/加 toolCallId）409 CONFLICT；
  4. 规范化等价（首尾空白差异）同载荷重放；大小写不同=不同载荷；
  5. 在途分支（按现行 L1 §3.1）：恢复在途的同键请求**等待首次受理落定**，落定后按其结果重放（同载荷 200 duplicate / 异载荷 409）——原 L2 §7.2 三场景（provisional 竞争 409 / 已认领 409 IN_FLIGHT / 终态资格 409 NOT_RESUMABLE）中"在途等待"两分支已被现行契约取代，409 NOT_RESUMABLE 保留于 F110-05（活跃非待答）；L2 §7.2 与新契约的对齐项登记偏差清单复核；
  6. 跨 taskId 同键互不影响；
  7. 503 后（受理前失败）同键重发可成功、原任务仍 awaiting（衔接 F110-11）。
- INCONCLUSIVE：时序构造未达目标分支（外部观察无法区分）→ 标记并记录窗口证据，不猜测分支
- 自动化：`Feat110ResumeIdempotencyE2EIT` / @Story("resume.idempotency.*")

### F110-11 执行池饱和与回滚

- Oracle 来源：L1 出口 10；L2 §3.2（回滚待答/目标/确认登记/provisional + 重发原提问）、§6.4（池拒绝映射 503）
- Given：max-concurrent-tasks 调小且占满
- When：/resume 新键提交
- Then：503 BUSY + retryAfterSeconds=5 + Retry-After 头；原任务 awaiting_answer=true；原 ask_user 事件重发（同 toolCallId 新 seq）；无 error/done；释放容量后同键同载荷 202 成功
- 不应断言：重发事件的措辞（应与原 ask_user 载荷一致——按事件 ID/seq 断言）
- 自动化：`Feat110PoolSaturationE2EIT`

## 7. Fixture、环境与确认门禁

| 缺口 | 为什么需要 | 拟改内容 | 影响 | 当前授权状态 |
|---|---|---|---|---|
| SUT 重启能力 | F110-15 | SutStack 停止/重拉（B-04 核实后定） | 无业务改动 | 待核实；不可行则降级登记 |
| SUT 配置注入（池容量/TTL/认证） | F110-10/11/19 | 配置覆盖清单 | 无 | 既有能力 |
| ask_user 触发语料 | 挂起构造 | 真模型语料（禁 Mock） | — | 既有 |
| 终态竞态窗口受控手段 | F110-04b | 长收尾工具/事件量放大；不足则 partial | 无 | 执行期验证 |

## 8. 自动化落点与精确执行规划

- 测试仓/worktree/命令形态同 109（ENV-5.8/5.9/5.10）；新类 `cases/e2e/deepanalyze_resume/`。
- @Tag("feat-110")；并发类用例注意 SUT 状态隔离（唯一 taskId/sessionId，测试间无顺序依赖——铁律）。
- 503/409 竞态类用例每条独立 SUT 实例或充分隔离，防饱和残留污染相邻用例。

## 9. 状态汇总与退出标准

- 可执行性：runnable 主体；partial=F110-04b/11（Oracle 双栏）/19/22；blocked=G-110-b（同源注入面）；降级候选=F110-15。
- 退出标准：17 能力行 + 17 出口逐条承接；busy 终态 oracle 与 answers 回灌**已于 11-17 裁决冻结（!513）并实现**（Feat110AdjudicatedE2EIT）；金丝雀与幂等族证据完整。

## 10. 负结论归因记录

| 结论 ID | 结论 | 候选解释 | 排除依据 | 仍未排除项 | 下一步探针 |
|---|---|---|---|---|---|
| （设计期无负结论） | — | — | — | — | — |

## 最小可执行例子

- 机制 A（幂等受理）：输入声明=POST /resume + Idempotency-Key → 可观察=202/200/409/503 响应体与事件流 → 证据=HTTP 交互 dump + SSE。
  故障与主断言：若同键重试产生第二个 continuation 或第二次工具执行，F110-06 主断言（事件流无第二 start、执行计数）应失败。
- 机制 B（steering 线性化）：输入声明=inject 消息 → 可观察=模型请求记录中消息数与顺序 → 证据=SUT 请求日志（脱敏）。
  故障与主断言：若恢复后两条 inject 只被消费一次或顺序颠倒，F110-03 主断言应失败。
