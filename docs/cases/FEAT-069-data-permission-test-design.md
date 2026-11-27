---
title: FEAT-069 测试设计初稿
feature_id: FEAT-069
document_type: test-design
status: draft
automation_status: design-only
feature_source: 02-Code/_worktrees/docs-feat069-49d87c6d/develop/02-features/FEAT-069-deepanalyze-java-data-permission.md @ docs 49d87c6d, sha256 13dea491…969e
l2_source: 同 worktree develop/03-architecture/L2-Low-Level-Design/deepanalyze/Feat-Func-069-deepanalyze-java-data-permission.md @ docs 49d87c6d, sha256 f9f70110…f52
requirement_preview: ./FEAT-069-需求与测试预览.md
change_scope: ./FEAT-069-变更包范围卡.md
input_maturity: merged
updated: 2026-10-20
---

# FEAT-069 deepanalyze-java 数据面权限与写方迁移 · 测试设计初稿

## 0. 用户必读：需求与测试方案

- 一句话说明：把智能体执行数据的**写入方**从上游 DA 后端迁到智能体自己，"谁能碰哪张表"由数据库账号权限强制，上游进程停掉智能体照常跑。
- 关键链路：上游经 DA 兼容端点发起任务（可带 teamId）→ 宿主现读团队定义/执行任务 → 四个写机制把执行记录、会话记忆、报告、待办异步写进 agent 属主表 → 上游用 DA 账号直读直改直删；越权被 GRANT 拒绝；记录写失败不打挂执行。
- 本期测试：① 三账号权限矩阵与 schema 演进（PG 集成层）；② 四个写机制闭环（e2e，DA 账号直读断言）；③ 读方消失行为（controller 层 + 语句日志）；④ 团队通道与 R1 载荷（e2e）；⑤ 降级与故障注入（队列满/停库/权限收回）；⑥ 独立运行；⑦ 条件项：agent_todos（D1）、压缩触发（core 机制）。
- 本期不测：上游代码改造、部署面 GRANT 执行、知识工程组管线、agentevolving、A2A 入口契约变更、透传字段消费语义、多租户收窄（范围卡 §4）。
- 未决影响：DS-05（D1）决定三组 todos 用例与验收 5 是否进判定分母；DS-01/02/03 决定三项验收的判定形态——均已按默认口径先行设计，答复到达后只调判定不调设计骨架。
- 详细预览：[FEAT-069-需求与测试预览.md](./FEAT-069-需求与测试预览.md)；本期范围：[FEAT-069-变更包范围卡.md](./FEAT-069-变更包范围卡.md)
- 输入成熟度：merged（可针对正式制品执行、提 ISSUE 和收口）。
- 用户理解确认：`confirmed`，2026-10-20，用户确认范围卡与预览（对话"好，继续吧"）；DS-05 转设计确认单。

## 1. 依据、当前口径与范围

- 设计真相源：FEAT-069 需求文档 @ docs 49d87c6d（sha256 13dea491…969e）；worktree 固定不可变快照。
- L2 实现说明：Feat-Func-069 @ 同 worktree（sha256 f9f70110…f52）。
- 已确认裁决：① 范围卡与预览经用户确认（2026-10-20）；② 直接影响回归=0 项（三关核对见范围卡 §3）；③ D1 未定案默认处置（条件项，不阻塞）；④ 问题外抛走汇总确认清单（设计 DS-01~05 / 开发 L2-01~03），不零散打扰。
- 范围内：范围卡 §2 变更 C1~C11 对应的全部能力与错误面（§4 矩阵）。
- 范围外：范围卡 §4.1~§4.2（上游工程、部署面执行、知识管线、演进机制、A2A 契约、消费语义、多租户、静态扫描工具化）。
- 未决问题：设计问题清单 DS-01~05（责任人=设计）；L2 问题清单 L2-01~03（责任人=开发，均不阻塞）。逐项责任人门禁已标注，无重复提问设计已明确事项。

> **测试红线边界声明**：本项目红线"不直连 fixture 数据库做断言，通过 HTTP API 验证"针对的是**业务 fixture 状态**。FEAT-069 的被测面本身就是数据库（权限矩阵、GRANT 拒绝、落库内容、FK/schema 演进）——需求验收第 1/3/4/5/8 项**指名**以"DA 账号直读/直执 SQL"为判定通道。因此 PG 直连是验收指名的证据通道，不构成红线违反；e2e 主链仍全程经 HTTP/A2A 驱动，不读库驱动业务流。

## 2. SUT 适用性

| SUT/参与者 | 角色 | 适用能力 | 不适用能力 | 结论与依据 |
|---|---|---|---|---|
| deepanalyze-java（DA 宿主 engine，`0.1.3-feat069` exec jar @ agent-solution 6864dc3dd） | 唯一被测 SUT | 全部（C1~C11） | — | 特性专属 deepanalyze-java（需求 §1）；L2 §1.1 全部落点在该工程 |
| 测试 PostgreSQL（postgres:16 容器，三账号 + TS 基线 schema） | 数据面被测对象 + 断言通道 | 权限矩阵、schema 演进、落库内容、自洁、删除级联前提 | 不模拟上游业务逻辑 | 验收 1/8 指名数据库层判定；L2 §7.1 建议形态"三账号连接直执 SQL" |
| 批次 Redis（独立容器/端口） | SUT 依赖（checkpointer/现状 todos） | 三段式推导的对照面（redis 并存现状不变） | 不测 Redis 本身 | L2 §3.6；ENV-1.9/1.10 口径 |
| 上游调用方（SIT 扮演） | compat/A2A 客户端 | R1 载荷、gating、读方消失行为 | 不承担上游代码改造义务 | L2 §2.1/§3.7 |
| 其他 SUT（deepagent/reactagent/workflow 等） | — | 无 | 全部 | 本特性被测件为 DA 宿主专属，不因存在多种架构而默认覆盖（RULES §SUT 适用性） |

## 3. 黑盒边界、拓扑与前置条件

```text
SIT(JUnit, acceptance 仓 sit-feat069 分支)
  ├─ PG 集成层：直连测试 PG（agent/DA/知识工程组三账号各自连接）
  │    · SUT 启动前：施加 TS 基线 schema fixture（含两处 FK 现状）
  │    · SUT 启动（黑盒拉起 exec jar，data.pg.enabled=true，jdbc-url=测试 PG）
  │    · 断言通道：information_schema、直执 SQL、SQLState、按账号语句日志
  ├─ controller 层：黑盒 HTTP 打 DA 兼容端点（run-stream/run/skills/providers）
  └─ e2e 层：SutStack 拉起 SUT + 真实 LLM（~/.llmrc，ENV-4.1；不 Mock LLM）
```

- **前置条件**：① 批次 PG 容器（postgres:16，建三账号，SUT 用 agent 账号连接串）；② 批次 Redis 容器（独立端口，与 `sit-redis-feat065067` 隔离）；③ TS 基线 schema fixture（模拟 DA 迁移现状：session_memory/reports 含对 sessions 的 FK、workflows 无 a2a_task_id 列——**FK 拆除的验证必须先让 FK 存在**，否则 DROP IF EXISTS 空转、校验空洞通过）；④ SUT 制品自 6864dc3dd 源码构建（revision `0.1.3-feat069`，不复用其他批次制品）；⑤ e2e 用 LLM 凭据（ENV-4.1 已就绪）。
- **形态限制（结论限定）**：本机无 DA(TS) 进程——验收 7 以"SUT 单独拉起、天然无上游"形态判定；验收 12 比对半边与验收 8 全路径标环境受限（DS-02/03）。
- **降级注入黑盒构造**：`queue-capacity` 调小构造队列满；任务中途 `docker stop` PG 构造连接故障；`REVOKE` agent 账号 INSERT 构造 42501；JSON 转义 `\u0000` 构造毒行。
- **数据唯一性**：每用例独立 sessionId（UUID 形态，同时规避 L2-02 键解析歧义）与唯一 skill name；共享 SUT 按用例前后增量断言调用次数。

## 4. 需求覆盖矩阵

验收 1~12 → 用例组映射（逐条可判定条目；L2 错误面 §7.3 与 gating §6.4 另列行）：

| 条目 | 来源锚点 | 承接用例 | 层次 | 形态 | 注记 |
|---|---|---|---|---|---|
| 验收 1 三账号权限生效（抽样） | 需求 §2 L71 / L2 §7.1 | f069-grant-01~05 | PG 集成 | 真执行 | 抽样规则照需求原文；知识工程组账号一期全负例（矩阵一期全"无"） |
| 验收 2 压缩后 session_memory agent 写、DA 直读直改回读 | 需求 L72 / L2 §3.3 | f069-mem-01~03 | e2e+PG | 真执行（条件） | 条件：e2e 中出现 `context.compression_state` chunk；未出现=机制未启用，验收 2 不进判定（L2 §1.3 事实 5） |
| 验收 3 workflows/workflow_logs 落库+DA 直读+保留期自洁 | 需求 L73 / L2 §3.2 | f069-rec-01,06,07；f069-deg-* | e2e+PG | 真执行 | 自洁按配置值构造超期数据 |
| 验收 4 报告落 reports/report_references + DA 直读 | 需求 L74 / L2 §3.4 | f069-rpt-01~04 | e2e+PG | 真执行 | 双装配点覆盖（两条任务发起形态） |
| 验收 5 待办落 agent_todos + DA 直读 | 需求 L75 / L2 §3.6 | f069-todo-01~04 | e2e+PG | 条件（D1） | DS-05 |
| 验收 6 agent 侧无对上游掌控表的读写 | 需求 L76 / L2 §7.1 | f069-read-01~03 | controller+PG | 真执行（运行期半边） | 静态半边归交付流程（DV-02）；语句日志为运行期证据 |
| 验收 7 DA 后端停掉不影响执行（含续跑） | 需求 L77 / L2 §7.1 | f069-iso-01 | e2e | 真执行（形态受限） | 本机形态=无上游进程（§3 形态限制） |
| 验收 8 上游删除会话→关联数据不可查 | 需求 L78 / L2 §7.1 | f069-del-01（-02 条件 D1） | PG 集成 | 真执行（数据库前提半边） | DS-02/DV-01 |
| 验收 9 agent_skills 双写纪律与选择规则 | 需求 L79 / L2 §3.5 | f069-skl-01~05 | PG 集成+e2e | 真执行 | DA 直写以 SQL 模拟六类路径（L2 §7.1 明示构造） |
| 验收 10 §3.5 过渡项均有退出条件 | 需求 L80 / L2 §7.1 | f069-tr-01 | 文档审查 | 文档审查 | 三行逐项核对 |
| 验收 11 载荷契约（teamId/回落/不经 sessions 读） | 需求 L81 / L2 §3.6-3.7 | f069-team-01~06；f069-read-03 | e2e+PG | 真执行 | 第三句运行期证据=语句日志（DV-04） |
| 验收 12 workflows.a2a_task_id 落键+agent_tasks 比对 | 需求 L82 / L2 §7.1 | f069-rec-02,03 | e2e+PG | 真执行（落键半边） | 比对半边 `partial`（DS-03/DV-03） |
| L2 错误面：503/404/42501/队列满/通道满/毒行/关停/writer 死亡/压缩未启用/演进失败/D1 误配回落 | L2 §7.3 | f069-team-03,04；f069-deg-01~04；f069-schema-03,04；f069-todo-04 | 各层 | 真执行 | writer 死亡自愈无黑盒观察面 → 标 `known-gap`（白盒钩子，归开发自测） |
| L2 gating：pg.enabled=false 行为一致；D1 未定案回落 | L2 §6.4 | f069-gate-01；f069-todo-03,04 | e2e | 真执行 | 行为一致断言=同请求新旧配置对照 |

<!-- coverage:scenarios:start -->
| 用例 ID | 场景 | 计划验证方式 |
|---|---|---|
| f069-schema-01 | 启动后 agent_todos 表（含 expires_at）与 workflows.a2a_task_id 列+索引在场 | 真执行（PG 集成，information_schema） |
| f069-schema-02 | 基线含两处 FK 的库启动后 FK 确不存在（001/026 约束名） | 真执行（PG 集成） |
| f069-schema-03 | 预置漂移约束名 FK → 启动失败且错误指明实际约束名 | 真执行（PG 集成，fail-fast 断言） |
| f069-schema-04 | pg.enabled=true 且 jdbc-url 为空 → 启动失败（现状语义） | 真执行（e2e 启动断言） |
| f069-schema-05 | 预置 status='running' 僵尸行 → 启动收口为 failed（error 含 writer interrupted at restart） | 真执行（PG 集成） |
| f069-schema-06 | 重复启动幂等不报错 | 真执行（PG 集成，二次启动） |
| f069-grant-01 | 按需求抽样规则：各账号权限内操作成功（agent 属主域抽 agent+作战部表、知识库/系统域抽作战部表） | 真执行（PG 集成，三账号直执 SQL） |
| f069-grant-02 | 越权被数据库层拒绝（SQLState 42501，非应用层拦截） | 真执行（PG 集成） |
| f069-grant-03 | agent 账号对作战部属主表默认无权限（GRANT 脚本未列=无权限，逐表验证） | 真执行（PG 集成） |
| f069-grant-04 | KB 一期例外：agent 账号对 wiki_pages/wiki_links SELECT 成功、写 42501 | 真执行（PG 集成） |
| f069-grant-05 | 知识工程组账号一期全负例（矩阵一期全"无"） | 真执行（PG 集成） |
| f069-rec-01 | 单 Agent 任务后 workflows（running→terminal）与 workflow_logs 事件序列完整，DA 账号直读与执行事实一致 | 真执行（e2e+PG） |
| f069-rec-02 | A2A 路径：workflows.a2a_task_id=SDK 分配 taskId（metadata[runtime.parentTaskId]） | 真执行（e2e+PG） |
| f069-rec-03 | compat 路径：a2a_task_id=da.run.task_id；agent_tasks 比对半边标 partial | 真执行（e2e+PG） |
| f069-rec-04 | tenant_id 落键：X-Da-Tenant-Id 携带→该值；缺省→default-user | 真执行（e2e+PG） |
| f069-rec-05 | skill_invoke 调用后 skill_usage.use_count+1、last_used_at 更新、生命周期列不触碰（按列分写） | 真执行（e2e+PG） |
| f069-rec-06 | 保留期自洁：retention-days=N 构造超期行→删除（含 workflow_logs/reports 级联），未超期保留 | 真执行（PG 集成，purge 节拍等待/触发） |
| f069-rec-07 | retention-days<=0 → 自洁不执行 | 真执行（PG 集成） |
| f069-deg-01 | queue-capacity=1 压测：可丢弃类按类偏好丢弃+按租户×类×workflow 计数+恢复后补 drop_marker 行 | 真执行（e2e+PG，配置构造） |
| f069-deg-02 | 同构造下受保护类（answer/tool_call/tool_result/interaction）事件不丢 | 真执行（e2e+PG） |
| f069-deg-03 | 任务执行中 stop PG 容器→任务照常完成；恢复后按分诊续写/记账 | 真执行（e2e+PG，故障注入） |
| f069-deg-04 | REVOKE agent 账号 INSERT→任务照常完成、ERROR 日志、无记录行（42501 分诊不打挂执行） | 真执行（e2e+PG） |
| f069-deg-05 | 含 \u0000 的 payload（JSON 转义构造）→消毒防线不卡批、无 22xxx 隔离 | 真执行（e2e+PG） |
| f069-mem-01 | （条件：chunk 出现）压缩触发后 session_memory upsert，DA 账号直读可见 | 真执行（e2e+PG，条件项） |
| f069-mem-02 | DA 直改（SET content,updated_at=now()）→下次任务回读改后内容 | 真执行（e2e+PG，条件项） |
| f069-mem-03 | session-memory-enabled=false 或 chunk 不出现→表空无报错 | 真执行（e2e+PG） |
| f069-rpt-01 | push_content 成功→reports 落库（tenant_id 落键）+DA 直读可见 | 真执行（e2e+PG） |
| f069-rpt-02 | 去重拒绝的推送不落库 | 真执行（e2e+PG） |
| f069-rpt-03 | references[] 非空→report_references 逐条落库；空/null→不落 | 真执行（e2e+PG） |
| f069-rpt-04 | 两条任务发起形态（模板路径/factory 路径）各推送→均有落库（双 attach 点） | 真执行（e2e+PG） |
| f069-skl-01 | Hub 运行期安装→agent_skills 直写（source=hub）+物化目录+下一任务生效（物化即生效） | 真执行（e2e+PG） |
| f069-skl-02 | DA 直写 manual 行（SQL 模拟）→对账节拍后物化追平 | 真执行（PG 集成，等待/触发对账） |
| f069-skl-03 | 同 name 多 source：仅取 active、source 优先级 builtin>hub>plugin>manual、同 source created_at 降序 | 真执行（PG 集成，SQL 构造多行矩阵） |
| f069-skl-04 | DA DELETE 行→反向对账目录移除（删除盲区覆盖） | 真执行（PG 集成+文件断言） |
| f069-skl-05 | 只追平不反转：物化目录手工变动→下轮对账以 DB 应生效集重建 | 真执行（PG 集成+文件断言） |
| f069-read-01 | sessionId 不存在于 sessions→不再 404、正常受理执行（对照旧行为） | 真执行（controller 层） |
| f069-read-02 | providers 探测端点返回宿主 deepanalyze.providers[] 配置（协议形态不变） | 真执行（controller 层） |
| f069-read-03 | 全任务周期语句日志：agent 账号连接无 sessions/messages/settings 访问语句 | 真执行（PG 语句日志断言，运行期证据） |
| f069-read-04 | agent_skills 三个读端点换账号后语义/结构不变 | 真执行（controller 层） |
| f069-read-05 | 旧调用方（不带三新字段）零影响：响应与 SSE 词汇现状一致 | 真执行（controller 层） |
| f069-team-01 | agent_teams 直写两成员+带 teamId 发起→按蓝图执行，workflows.team_name/mode 正确、a2a_task_id 落键 | 真执行（e2e+PG） |
| f069-team-02 | 成员事件于执行结束后按拓扑序补推 SSE（执行期静默=一期声明限制） | 真执行（e2e，SSE 帧） |
| f069-team-03 | teamId 不存在→404 {error:"team not found"} | 真执行（controller 层） |
| f069-team-04 | 数据源未启用+teamId→503 {error:"data plane unavailable"}，不回落单 Agent | 真执行（controller 层） |
| f069-team-05 | A2A 路径 metadata 携带 da.run.team_id→不被消费，按单 Agent 执行、无团队字段回填 | 真执行（e2e+PG） |
| f069-team-06 | 不带 teamId→回落宿主自带模板基线（既有行为不变） | 真执行（e2e） |
| f069-team-07 | council 团队→按 dag 形态执行（一期声明限制：成员级配置不编译） | 真执行（e2e+PG） |
| f069-iso-01 | 无上游进程拓扑：任务执行+续跑不受影响 | 真执行（e2e，形态受限声明） |
| f069-del-01 | FK 拆除+DA 账号 DELETE 权限就绪→按 session_id 直删五表成功、删后不可查 | 真执行（PG 集成） |
| f069-del-02 | （条件：D1）agent_todos 删除（**可执行 oracle**，2026-11-18 评审细化）：完整 sessionId + 固定 `:todo` 后缀精确匹配（允许键前完整租户前缀）；禁冒号切段反解；`%`/`_` 作字面量不作 LIKE 通配符；负向数据（近似 sessionId/租户前缀同名键/`%`/`_` 字面量键）不误删 | 真执行（PG 集成，条件项） |
| f069-todo-01 | （条件：D1）任务执行中待办落 agent_todos（键 {租户}{sessionId}:todo），DA 直读可见 | 真执行（e2e+PG，条件项） |
| f069-todo-02 | （条件：D1）todo-ttl-seconds>0→expires_at 承载 TTL、过期行清理 | 真执行（PG 集成，条件项） |
| f069-todo-03 | 三段式推导黑盒：da_own_pg+数据源可用→PG 有 todo 键；redis 并存未启用→Redis 有键、PG 无行（现状不变） | 真执行（e2e+PG+Redis，条件项） |
| f069-todo-04 | da_own_pg 配置但数据源不可用→启动告警+回落既有推导（不 fail-fast） | 真执行（e2e 启动断言，条件项） |
| f069-gate-01 | pg.enabled=false：任务执行行为与未引入本特性一致（同请求对照）+teamId 显式 503 | 真执行（e2e） |
| f069-tr-01 | §3.5 过渡登记三行（KB 直读/旧接线退役/历史数据）逐项有目标态退出条件 | 文档审查（检查单留证） |
<!-- coverage:scenarios:end -->

### 4.1 未承接与降级条目

- 状态页（覆盖台账 manifest）在 TestPlan 发布阶段随 `coverage_contract` 生成，路径回填此处。
- 设计期已知条件项：f069-mem-01/02（core 压缩 chunk 是否出现，执行期核实）、f069-todo-* 与 f069-del-02（DS-05）。已知 `known-gap`：writer 死亡自愈（无黑盒观察面，L2 §7.4 归宿主自测）。均登记台账并带恢复条件，确认延期不解除验收阻塞。

## 5. 端到端场景

1. **主链（验收 3/12）**：发起单 Agent 任务（A2A 与 compat 各一）→ 等终态 → DA 账号直读 workflows/workflow_logs，比对事件序列与 a2a_task_id/tenant_id 落键。
2. **报告链（验收 4）**：任务中调 push_content（带/不带 references、重复推送）→ DA 账号直读 reports/report_references。
3. **团队链（验收 11）**：DA 账号直写 agent_teams（两成员，pipeline/graph/parallel/council 各一）→ run-stream 带 teamId → SSE 帧 + workflows 行断言；对照组：无 teamId 模板任务、A2A 路径带 metadata、pg 关闭 503、不存在 teamId 404。
4. **运维纠错链（验收 2，条件）**：压缩触发落记忆 → DA 账号 UPDATE content → 再次发起任务 → 断言装配上下文含改后内容（可观察面：任务行为/日志留痕）。
5. **技能双写链（验收 9）**：SQL 构造多 source 行矩阵 → 对账 → 物化目录断言 → DELETE → 反向移除。
6. **降级链（L2 §7.3）**：小队列压测 / 停库 / REVOKE / \u0000 毒行，四构造各自断言"执行面不受影响 + 记录面可观察降级"。
7. **删除级联链（验收 8）**：造五表关联数据 → DA 账号按 session_id 直删 → 逐表不可查。

## 6. 详细用例（判定规则按组给出，逐用例 G/W/T）

> 每组表格列：ID | Given | When | Then（主断言） | PASS/FAIL/INCONCLUSIVE 判据。共用字段不再逐行重复：**层次**见 §4；**Oracle 来源**=需求/L2 锚点列；**配置生效证据**=配置注入断言 + 下游触达（SQL 直查/语句日志/SSE 帧）；**唯一数据**=UUID sessionId、唯一 skill name、前后增量断言；**不应断言**=范围卡 §4 所列不承接面；**自动化**=`@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")` + `@Story("<用例ID>: <场景>")` + `@Tag("feat-069")`。

### G1 schema 演进（f069-schema-*）

| ID | Given | When | Then 主断言 |
|---|---|---|---|
| schema-01 | TS 基线库（无 agent_todos/无 a2a_task_id） | 启动 SUT | information_schema：agent_todos（k TEXT PK, v TEXT, expires_at）与 a2a_task_id 列、idx_workflows_a2a_task_id 在场 |
| schema-02 | 基线含 `session_memory_session_id_fkey`、`reports_session_id_fkey`（FK 存在已预检） | 启动 SUT | 两约束不存在；report_references→reports 的 FK **仍在** |
| schema-03 | 基线含漂移名 FK（如 `session_memory_session_id_fkey_old`） | 启动 SUT | 启动失败，错误含实际约束名（L2 §4.3 防静默残留） |
| schema-04 | pg.enabled=true、jdbc-url 空 | 启动 SUT | 启动失败（现状语义，L2 §6.2） |
| schema-05 | 预置 workflows status='running' 行 | 启动 SUT | 该行 status=failed、error 含 writer interrupted at restart；词表内取值 |
| schema-06 | 已启动过一次 | 二次启动 | 不报错、无重复 DDL 副作用 |

FAIL 判据示例：schema-02 若 FK 残留（DROP 静默 no-op）而启动成功 → FAIL（产品缺陷：防漂移校验失效）；INCONCLUSIVE：无法区分"约束本就不存在"时（预检失败）不判，修 fixture 重跑。

### G2 三账号 GRANT（f069-grant-*）

- 构造：从 SUT jar 解出 `db/grant-three-accounts.sql`（交付物本体），替换占位符后执行；抽样按需求 L71 原文规则选表（agent 属主域每域 1 agent 属主+1 作战部属主；知识库/系统域各 1）。
- grant-01/02：每账号×抽样表做权限内四操作与越权操作；主断言=权限内成功、越权 SQLState=42501（连错误来源都是数据库层——断言非宿主 HTTP 错误）。
- grant-03：对矩阵中"不授权"语义逐表验证 agent 账号无任何权限（四操作全 42501）。
- grant-04：wiki_pages SELECT 成功 + INSERT 42501（一期例外只读）。
- grant-05：知识工程组账号对全部抽样表四操作全 42501（一期矩阵全"无"，正例集为空的显式声明）。
- 覆盖对账口径：本组承载验收 1 的**脚本语义**验证；客户环境实际部署正确性不在判定内（预览 §7 知悉点 2）。

### G3 执行记录（f069-rec-*）

- rec-01：任务终态后 DA 账号直查：workflows 恰 1 行、status 终态、started_at<completed_at；workflow_logs 行覆盖该任务全部 chunk（对照 SSE 帧序列，缺 SSE 侧已声明丢弃的除外）；事件分级词表与 L2 §5.2 一致。
- rec-02/03：a2a_task_id 分别等于 A2A 路径 `runtime.parentTaskId`、compat 路径 `da.run.task_id`；agent_tasks 比对半边不判（partial 登记台账，DS-03）。
- rec-04：两任务（带头/不带头）→ tenant_id 分别=头值 / 'default-user'。
- rec-05：skill 调用前后 skill_usage 行：use_count 恰 +1、last_used_at 更新、生命周期列字节不变；非 skill 调用不产生行。
- rec-06/07：配置 retention-days=N → 构造 completed_at=now-N-1d 的终态行+未超期行 → purge 后超期行（含 workflow_logs/reports 级联）删除、未超期保留；retention<=0 → 无删除发生。

### G4 降级与故障注入（f069-deg-*）

- deg-01/02：`queue-capacity=1` + 连续多 chunk 任务：断言可丢弃类丢弃有计数（租户×类×workflow 维度）+ WARN、受保护类帧全部到达 SSE、恢复后 drop_marker 行（turn=最大 turn+1、content 含窗口与数量）。
- deg-03：任务执行中 `docker stop` 批次 PG：任务照常返回终态（主断言=执行不打挂）；恢复后按分诊观察续写/隔离计数。
- deg-04：`REVOKE INSERT ON workflows FROM agent 账号`：任务完成不受影响 + 宿主 ERROR 日志 + 无记录行（42501→永久类隔离语义）。
- deg-05：输入经工具链产生含 `\u0000` payload（JSON 转义构造）：无 22xxx 隔离、行正常落库（消毒为常态防线）。

### G5 会话记忆（f069-mem-*，条件项）

- 前置核实：任务轨迹日志出现 `context.compression_state` chunk（L2 §1.3 事实 5 判据）；不出现 → 组内用例标条件未触发，验收 2 不进判定（需求 §2 前置声明），不视为 FAIL。
- mem-02 直改 SQL 显式含 `updated_at=now()`（L2-01 建议形态，判定构造自控）；断言下次任务装配上下文读到改后内容（可观察面：轨迹日志留痕/任务行为）。

### G6 报告（f069-rpt-*）/ G7 技能（f069-skl-*）

- rpt 按场景表 G/W/T 直执行；rpt-04 双 attach 点=两条任务发起形态各推一次。
- skl-03 选择规则用 SQL 构造 {active×source} 矩阵（builtin/hub/plugin/manual 各行+同 source 双行不同 created_at+inactive 行），物化目录按规则唯一选中行重建——逐规则断言。
- skl-04/05 断言对象是物化目录（文件系统）：删除行→目录移除；手工改目录→下轮对账按 DB 重建（只追平不反转）。

### G8 读方消失（f069-read-*）

- read-01：对照构造——同一不存在 sessionId 在旧制品（FEAT-054 基线 jar）返回 404、新制品正常受理（双制品对照留证，行为变化证据闭环）。
- read-03：测试 PG 设 `log_statement=all`（仅测试实例），采集 agent 账号连接全任务周期语句日志，断言无 sessions/messages/settings 访问（主断言；同时支撑验收 6/11 运行期半边）。

### G9 团队（f069-team-*）/ G10 独立运行 / G11 删除级联 / G12 todos / G13 文档审查

- 按场景表 G/W/T 直执行。team-05 断言 A2A 路径 `params.metadata` 的 `da.run.team_id` 不产生团队装配且 workflows.team_name 为 null（边界声明的正面判据）。
- iso-01：SUT 独立部署（本机天然无上游），执行+中断续跑断言；结论标注形态限定（预览 §7 知悉点 1）。
- del-01：构造五表关联数据（含 report_references 级联保留验证）→ DA 账号 DELETE → 逐表 SELECT 计数=0。
- todo 组：DS-05 定案前仅实现与预执行（接线语义），不进验收判定；D1 定案后精确复跑升级。
- tr-01：文档审查检查单（§3.5 三行 × "退出条件明确且可判定"），留证批次目录。

## 7. Fixture、环境与确认门禁

| 缺口 | 为什么需要 | 拟改内容 | 影响 | 当前授权状态 |
|---|---|---|---|---|
| F1 TS 基线 schema fixture | FK 拆除/自洁/删除级联都需要"现状含 FK 的库"作起点（否则校验空洞通过） | 新增测试资源 SQL（批次分支内），模拟 001/026 迁移的最小表集+FK | 仅测试侧使用，不触产品代码 | 待授权（随实现启动） |
| F2 GRANT 脚本执行 | 验收 1 的被测交付物 | 从 SUT jar 解出执行（只读使用） | 无 | 无需（使用交付物） |
| F3 批次容器（PG 三账号 + Redis 独立端口） | 与并行批次资源隔离（并行对话在用 6379 与 m2repo） | docker 起独立命名容器 | 不触碰 `sit-redis-feat065067` 与 AKDI 栈 | 待授权 |
| F4 acceptance 仓测试代码 | G1~G13 落点 | 新分支 `sit-feat069` + worktree（参照 sit-feat065067 先例），新增测试类 | 不改既有用例与共享检出 | 待授权 |
| F5 SUT 制品构建 | 被测件固定 6864dc3dd | agent-solution worktree 源码构建 `0.1.3-feat069` install 至工作区 m2repo | 批次专属 revision，不覆盖其他批次坐标 | 待授权 |
| F6 语句日志 | read-03 运行期证据 | 测试 PG 实例参数（仅测试容器） | 无 | 含在 F3 |

agent-solution 产品代码**零修改**（被测件按合入版使用）；如执行期缺口确需 fixture 调整，另行按门禁申请。

## 8. 自动化落点与精确执行规划

- 仓库/分支：`agent-runtime-acceptance` 批次 worktree，分支 `sit-feat069`（基线=10ad62d main）；不触碰共享检出与其他分支。
- 目录与层次：PG 集成层与 controller 层放 `cases/integration/deepanalyze_dataplane/`（surefire，@Tag("integration")）；e2e 放 `cases/e2e/deepanalyze_dataplane/`（failsafe，@Tag("e2e")，ENV-5.6 命令形态与 `SUT_M2_REPO` 环境变量，ENV-5.7）。
- SUT 接线：`application-openjiuwen.yml` **新增** agent 条目（deepanalyze 数据面版 0.1.3-feat069，不动 FEAT-054 既有条目，ENV-3.11 口径）；批次配置经环境变量注入 jdbc-url/凭据（铁律 5：不落值）。
- 执行顺序：F1/F3/F5 就绪 → G1/G2（PG 层先行，失败即环境/制品问题快速反馈）→ G3~G11 e2e → 条件组视前置 → G13 随时。
- 精确执行命令在 TestPlan 发布时冻结（含 `run-sit-macos.sh --check-only` 预检、failsafe 形态、XML 归档路径）。

## 9. 状态汇总与退出标准

- 执行状态、用例结论、ISSUE 状态、特性收口状态：唯一数据源为覆盖台账 manifest（TestPlan 阶段建立），本文件不手工维护动态结果。
- 退出标准：§4 矩阵每条目获得承接与适当形态证据；条件项（D1/压缩）按裁决处置；任何 `unassigned`/`degraded`/未验证条目存在时收口状态只能为 `open/partial-open`；确认延期不等于能力通过。已知形态限定（DS-02/03、iso-01）在收口结论中显式声明。
- 对抗性探针（铁律 B）：执行阶段对本批 7 风险面另做一轮非文档 Oracle 探针（重点：①跨会话隔离——writer 单队列并发任务交错；③资源边界——队列/计数；⑤失败注入——已含 G4；⑥重启恢复——schema-05/06 与对账启动全量），探针不进覆盖台账、跑完即删、只留证据目录。

## 10. 负结论归因记录

| 结论 ID | 结论 | 候选解释 | 排除依据 | 仍未排除项 | 下一步探针 |
|---|---|---|---|---|---|
| （暂无） | — | — | — | — | — |

## 最小可执行例子

1. **e2e 主链**：输入=compat run-stream（sessionId=UUID，简单输入触发 1 次工具调用）→ 入口=DA 兼容端点 + SutStack 拉起 `0.1.3-feat069` jar（data.pg.enabled=true）→ 可观察输出=SSE 终态帧 + DA 账号 SQL 查 workflows/workflow_logs → 证据落点=批次 evidence 目录（XML + SQL 输出留证）。
   - 代表性：材料来源=FEAT-054 既有 e2e 用例形态 → 保留真实 LLM/真实兼容端点条件 → 差异=新增数据面配置与 PG 断言通道。
   - 故障与主断言：若写机制漏装配（无 workflows 行），主断言"DA 账号直读恰 1 行终态记录"应失败。
2. **PG 三账号**：输入=jar 内 GRANT 脚本+抽样表清单 → 入口=三账号 psql 直执 → 可观察输出=成功计数+42501 SQLState → 证据落点=SQL 输出留证。
   - 故障与主断言：若 GRANT 漏授/多授，"权限内成功/越权 42501"矩阵断言应失败。
3. **停库注入**：输入=长任务+执行中 `docker stop` 批次 PG → 入口=compat 端点 → 可观察输出=任务终态正常+宿主 ERROR 日志 → 证据落点=XML+宿主日志。
   - 故障与主断言：若记录面打挂执行面（任务失败/挂起），主断言"任务照常终态"应失败。
4. **直改回读**：输入=压缩触发后的会话 + DA 账号 UPDATE content → 入口=再次发起任务 → 可观察输出=装配上下文/轨迹留痕含改后内容 → 证据落点=轨迹日志+SQL 前后对照。
   - 故障与主断言：若 agent 缓存旧值（违反"每次现读"），主断言"读到改后内容"应失败。
