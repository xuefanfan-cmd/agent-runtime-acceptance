---
feature_id: FEAT-104
feature_title: agent-memory 记忆批量运维操作（批量删除扩展 + 批量更新）
scope: vNext; docs/develop/02-features/FEAT-104-agent-memory-批量管理.md (updated 2026-10-23)
deployable_units: [agent-memory (Python, jiuwen_memory_entry.http_server)]
sut: agent-memory 批量运维面（POST /v1/delete 选择器扩展 after/limit/keep_latest + POST /v1/batch_update 双模式批量更新）
status: verified-pass
features: [FEAT-104]
updated: 2026-11-11
---

# FEAT-104 — 记忆批量运维操作（批量删除扩展 + 批量更新）测试设计

## 1. 测试目标

以 agent-memory（Python，jiuwen_memory_entry.http_server，offline profile，dev auth，root 身份）为 SUT，
验证 FEAT-104 定义的两项子特性——**批量删除扩展**（DeleteSelector 新增 after/limit/keep_latest
三字段，支持时间范围交集、条数截断、保留最新 N 条及其组合与排序兜底）与**批量更新**
（POST /v1/batch_update：统一补丁 / 逐条对应双模式、continue_on_error 逐项隔离、
内核保留键拒绝、limit 截断）的外部可观察行为。

观察 surface 限定为 HTTP wire 契约：扩展端点 POST /v1/delete（§4.2，选择器新增三可选参数）、
新端点 POST /v1/batch_update（§4.3），以及既有 POST /v1/add（含 occurred_at 注入
t_message）、/v1/get、/v1/delete、/v1/create_space。本设计不编译/导入 SUT 内部类、
不读取 SUT 内部状态或私有 SPI，全部断言落在 HTTP 请求/响应上。

## 2. 范围与非范围

### 范围

- 批量删除时间范围：after 与 before 组合取时间交集，t_message 早于 after 的候选排除
  （A1 / FR-01 / AC-01）。
- 批量删除条数截断：limit 在候选集上截断到 N 条，limit 为空行为不变
  （A1 / FR-02 / AC-03）。
- 批量删除保留最新：keep_latest 在活跃态候选按 t_message 倒序取前 N 条为保留集，
  从全部匹配移除保留集后删除；FORGOTTEN 不参与保留集计算但仍留在删除集中
  （A2 / FR-03 / AC-02）。
- 排序兜底：t_message 为空的候选按 t_ingest 兜底排序参与保留集计算
  （FR-04 / AC-11）。
- keep_latest 与 limit 组合：keep_latest 优先产出删除集，limit 在该删除集上截断
  （FR-05 / AC-02）。
- 兼容路径：selector 不带新参数时行为不变（§6.7 / A1）。
- batch_update 端点存在且统一补丁模式：patches 长度 1，按 unit_ids 或 scope 条件筛选候选
  统一应用，结果按扫描顺序对齐（A3 / FR-06 / FR-07 / AC-04）。
- batch_update 逐条对应模式：patches>1 必须有 unit_ids 且数量一致，每条独立应用对应补丁
  （A4 / FR-08 / AC-05）。
- batch_update 拒绝内核保留键：patch 含 forgotten_at 等内核键必须拒绝且不落值
  （A4 / FR-09 / AC-06）。
- batch_update 逐条模式无 unit_ids 拒绝（AC-07）、unit_ids 与 patches 数量不一致拒绝（AC-08）。
- continue_on_error 逐项隔离：true（默认）失败项记 error 不中止后续（AC-09）；
  false 遇失败 break、后续项标记跳过（A5 / FR-10 / AC-10）。
- batch_update limit 截断：最多更新 N 条，其余候选不动（FR-11 / AC-12）。

### 非范围

- 批量删除跨空间（OUT，§6.9）：选择器作用域为单一作用域，由产品边界保证，不构造跨空间用例。
- 批量更新并发执行（OUT，§6.9）：保序不并发是内部执行语义，黑盒无法经 wire 观察，
  由 L2 白盒承接。
- 双段鉴权 403 路径（FRS §11.3）：dev auth 身份固定为 root，由 required-auth e2e 承接。
- batch_delete 逐条鉴权 vs batch_update 预判定鉴权的差异（DEC-01）：鉴权成功路径行为
  不可区分，403 路径同上由 required-auth e2e 承接。
- CLI/MCP/SDK 接入形态对齐（§7.2）：本轮以 HTTP wire 为观察面，其余形态由对应入口的
  接入专项承接。
- 批量删除失败项不重试（§8）：黑盒无法在单次调用内注入部分失败后重试的时序，
  由故障注入专项承接。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| docs/develop/02-features/FEAT-104-agent-memory-批量管理.md（2026-10-23） | §3.2/§3.3 能力要求、§4.2/§4.3 入口契约、§6.2/§6.3/§6.6/§6.7 行为语义、§9.2 A1~A5 验收口径 |
| docs/develop/01-scenarios/FRS-104-agent-memory-批量管理.md（v0.1, 2026-10-23） | FR-01~FR-11 功能要求、AC-01~AC-12 验收标准、§8 异常与幂等、§10 输入输出契约、附录 A.1 请求示例 |

## 4. 部署拓扑

```text
+--------------------------------------------------------------+
|  JUnit (Feat104MemoryBatchManagementBlackboxTest)             |
|    |  ProcessBuilder 拉起 / 回收 SUT 进程                      |
|    v                                                          |
|  agent-memory (Python)  jiuwen_memory_entry.http_server       |
|    --auth-mode dev  --host 127.0.0.1  --port <free>           |
|    dev_identities: root-key -> Role.ROOT                      |
|    offline profile（KV=本地文件/内存，无外部中间件）             |
|    配置层 memory_api.engine.default.target=agent（空间隔离）     |
+--------------------------------------------------------------+
      ^ HTTP (127.0.0.1)                    真实 SUT（源码直接运行）
      |  /v1/add /v1/get /v1/delete /v1/batch_update /v1/create_space
```

边界要求：

- **真实依赖（不 mock）**：本地 KV 存储、检索索引——随 SUT 进程内真实装配，不做任何替换。
- **真实 SUT**：feature_source（D:/code/test/agent-memory）源码直接运行，不打桩、不加载
  测试替身；每次执行前从源码最新状态拉起。
- **观察 surface 限定**：仅 HTTP wire（上述端点）。不读 SUT 内部对象、KV 文件内容、私有 SPI。
- **隔离策略**：每个场景使用独立 space（f104-<case>），org 固定 local，避免候选集串扰。
- **故障注入手段**：无。逐项失败经 wire 构造（batch_update 的 unit_ids 混入不存在的 id）。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| F104-01 | 批量删除时间范围 after+before 交集（正例） | 空间 f104-range 有 4 条 ACTIVE：M1(t_message=75天前) M2(60天前) M3(45天前) M4(10天前) | POST /v1/delete selector={scope, after:70天前, before:30天前} mode=forget | 200 且响应 deleted 恰为 [M2,M3]（after/before 交集，M1 早于 after 被排除、M4 晚于 before 被排除）；get M1/M4 lifecycle=="active" | root token |
| F104-02 | 批量删除 limit 截断（正例） | 空间 f104-limit 有 5 条 ACTIVE | POST /v1/delete selector={scope, limit:2} mode=forget | 200 且响应 deleted 恰 2 条；剩余 3 条 get 均 lifecycle=="active" | root token |
| F104-03 | 批量删除 keep_latest 保留最新（正例/状态机） | 空间 f104-keep 有 4 条 ACTIVE（A1~A4，t_message 递增）+ 1 条已 forget 的 F | POST /v1/delete selector={scope, keep_latest:2} mode=forget | 200 且响应 deleted 恰为 {A1,A2,F}（保留集=A3,A4；F 为遗忘态不参与保留集计算但留在删除集）；get A3/A4 lifecycle=="active" | root token |
| F104-04 | keep_latest 与 limit 组合（正例/边界） | 空间 f104-keeplimit 有 5 条 ACTIVE（B1~B5，t_message 递增） | POST /v1/delete selector={scope, keep_latest:2, limit:2} mode=forget | 200 且响应 deleted 恰 2 条且均属于 {B1,B2,B3}（keep_latest 先产出 3 条删除集，limit 截断到 2）；get B4/B5 lifecycle=="active" 且删除集第 3 条（未入选截断）get lifecycle=="active" | root token |
| F104-05 | 排序兜底：t_message 为空按 t_ingest 参与保留集（边界） | 空间 f104-fallback：C1/C2/C3 带 t_message（30/20/10 天前）；D1/D2 无 occurred_at（t_message 为 null、t_ingest 为最新） | POST /v1/delete selector={scope, keep_latest:2} mode=forget | 200 且响应 deleted 恰为 {C1,C2,C3}（保留集=D1,D2，t_ingest 兜底排序最新）；get D1/D2 lifecycle=="active" 且 get 响应 temporal.t_message 为 null | root token |
| F104-06 | 兼容路径：selector 不带新参数行为不变（正例） | 空间 f104-compat 有 3 条带 tag compat 的 ACTIVE + 1 条不带 | POST /v1/delete selector={scope, tags:["compat"]} mode=forget | 200 且响应 deleted 恰为 3 条带 compat 标签的 id；第 4 条 get lifecycle=="active"（无新参数时现有语义不受影响） | root token |
| F104-07 | batch_update 端点存在 + 统一补丁按 unit_ids（正例） | 空间 f104-batch-u 有 M1/M2/M3 三条 ACTIVE | POST /v1/batch_update {selector:{unit_ids:[M1,M2,M3]}, patches:[{tags:["summary"]}]} | 200 且 outcomes 恰 3 项且按扫描顺序对齐（第 i 项 unit_id==M{i}）且每项 updated_unit 非 null 且 error 为空且 updated_unit.tags==["summary"] | root token |
| F104-08 | batch_update 统一补丁按 scope 条件筛选（正例） | 空间 f104-batch-s 有 2 条带 tag note + 1 条不带 | POST /v1/batch_update {selector:{scope, tags:["note"]}, patches:[{tags:["summary"]}]} | 200 且 outcomes 恰 2 项且每项 updated_unit.tags==["summary"]；不带 note 标签的那条 get tags 不含 "summary" | root token |
| F104-09 | batch_update 逐条对应模式（正例） | 空间 f104-peritem 有 M1/M2/M3 | POST /v1/batch_update {selector:{unit_ids:[M1,M2,M3]}, patches:[{content:"fixed-one"},{content:"fixed-two"},{content:"fixed-three"}]} | 200 且 outcomes 恰 3 项且按扫描顺序对齐（第 i 项 updated_unit.content=="fixed-{i}"）且每项 error 为空 | root token |
| F104-10 | 逐条模式无 unit_ids 拒绝（异常/fail-fast） | 无 | POST /v1/batch_update {selector:{scope}, patches:[{tags:["a"]},{tags:["b"]}]} | 400 且 error=="ValidationError" 且 message 提示逐条对应模式必须有 unit_ids；不产生任何更新 | root token |
| F104-11 | unit_ids 与 patches 数量不一致拒绝（异常/fail-fast） | 空间 f104-count 有 2 条 | POST /v1/batch_update {selector:{unit_ids:[M1,M2]}, patches:[3 个补丁]} | 400 且 error=="ValidationError" 且 message 提示数量必须一致 | root token |
| F104-12 | 内核保留键拒绝（异常/保留键保护） | 空间 f104-kernel 有 M1 | POST /v1/batch_update {selector:{unit_ids:[M1]}, patches:[{system_metadata:{forgotten_at:"2026-01-01T00:00:00Z"}}]} | 400 且 error=="ValidationError" 且 message 提示内核保留键不可覆盖；get M1 的 system_metadata 不含 forgotten_at（不静默合并） | root token |
| F104-13 | continue_on_error=true 逐项隔离（时序） | 空间 f104-coe-true 有 M1/M2 | POST /v1/batch_update {selector:{unit_ids:[M1,"f104-no-such-unit",M2]}, patches:[3 个补丁], continue_on_error:true} | 200 且 outcomes 恰 3 项且按扫描顺序对齐；第 2 项 error 非空；第 1/3 项 updated_unit 非 null（失败不中止后续） | root token |
| F104-14 | continue_on_error=false 遇失败中止后续标跳过（时序） | 空间 f104-coe-false 有 M1/M2 | POST /v1/batch_update {selector:{unit_ids:[M1,"f104-no-such-unit",M2]}, patches:[3 个补丁], continue_on_error:false} | 第 2 项 error 非空且第 3 项标记跳过（Skipped，updated_unit 为空）；get M2 保持更新前内容（未被更新） | root token |
| F104-15 | batch_update limit 截断（边界） | 空间 f104-uplimit 有 5 条带 tag ul 的 ACTIVE | POST /v1/batch_update {selector:{scope, tags:["ul"], limit:2}, patches:[{tags:["ul-done"]}]} | 200 且 outcomes 恰 2 项且每项 updated_unit.tags==["ul-done"]；其余 3 条 get tags 仍为 ["ul"]（未被动） | root token |
| F104-16 | keep_latest 保留集排除非 ACTIVE（状态机/边界） | 空间 f104-archk 有 4 条 ACTIVE（A1~A4，t_message 40/30/20/10 天前）+ 1 条已 archive 的 G | POST /v1/delete selector={scope, keep_latest:2} mode=forget | 200 且响应 deleted 恰为 {A1,A2,G}（保留集=A3,A4 仅 ACTIVE 参与；G 为 archived 不参与保留集但留在删除集）；get A3/A4 lifecycle=="active" 且 get G lifecycle=="forgotten" | root token |
| F104-17 | delete mode 三态语义（正例/状态机） | 空间 f104-modes 有 u1/u2/u3 三条 ACTIVE | POST /v1/delete selector={scope, unit_ids:[u1]} mode=archive；selector={scope, unit_ids:[u2]} mode=downweight；selector={scope, unit_ids:[u3]} mode=purge | archive→get u1 lifecycle=="archived"（可检索留痕）；downweight→get u2 lifecycle=="active" 且 system_metadata.importance=="0.5"（降权不删除）；purge→get u3 返回 404 NotFoundError（物理删除） | root token |
| F104-18 | batch_update 时间/标签/过滤三条件选择器（正例，§3.1） | 空间 f104-uptemporal 有 W1(30天前)/W2(15天前，先单条更新产生 superseded 旧版)/W3(40天前)；f104-upbefore 有 V1(45天前)/V2(10天前)；f104-upfilter 有 U1/U2（user_metadata.src=="crm"）/U3（src=="web"） | 各空间独立发起 batch_update：{selector:{scope, after:25天前}}；{selector:{scope, before:30天前}}；{selector:{scope, filters:{field:"user_metadata.src",op:"eq",value:"crm"}}}，统一补丁 | after→outcomes 恰命中 {W2',W3}（outcomes 数与当前有效记忆数一致，不得把 W2 的 superseded 旧版再匹配）；before→恰命中 {V1}；filters→恰命中 {U1,U2}，U3 不动 | root token |
| F104-19 | delete after+before+tags 三条件交集（正例，§5.3 运营场景） | 空间 f104-tri 有 T1(75天前,tag p90)/T2(60天前,tag p90)/T3(45天前,无 tag)/T4(10天前,tag p90) | POST /v1/delete selector={scope, after:70天前, before:30天前, tags:["p90"]} mode=forget | 200 且响应 deleted 恰为 [T2]（三条件同时满足，交集语义）；T1/T3/T4 get lifecycle=="active" | root token |
| F104-20 | batch_update 内容变更重算嵌入并可检索（正例，§5.3） | 空间 f104-embed 有 1 条内容为旧词表（与更新后词表完全不相交）的 ACTIVE，索引就绪可检索 | 基线检索命中→POST /v1/batch_update {selector:{unit_ids:[E1]}, patches:[{content:新词表}]}→按新内容检索、按旧内容检索（均含索引异步就绪轮询） | 新内容检索命中 updated 新版本 id（嵌入重算）；旧内容检索不命中该记忆任何版本（旧索引随更新退出） | root token |

### 5.1 显式 deferred / 白盒联动项

| 项 | 不覆盖原因 | 承接方 |
|---|---|---|
| 批量删除跨空间（OUT） | 产品边界不承诺，无跨空间用例 | 产品边界评审 |
| 批量更新并发执行（OUT） | 保序语义黑盒不可观察 | L2 白盒 |
| 双段鉴权 403 / batch_update 真源 scope 预判定拒绝路径 | dev auth 身份固定为 root | required-auth e2e |
| CLI/MCP/SDK 三形态对齐 | 本轮观察面为 HTTP wire | 各入口接入专项 |
| 批量删除失败项不重试 | 单次调用内无法注入部分失败时序 | 故障注入专项 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| jiuwen_memory_entry.http_server | 真实 SUT | feature_source 源码直接运行；--auth-mode dev + root 身份配置文件；offline profile + engine.default.target=agent 配置层 |
| root-key dev 身份 | Fixture | 测试进程写 target/sit-logs/feat104-dev-identities.json，root-key→Role.ROOT |
| 每场景独立 space | Fixture | f104-<slug> 空间经 create_space 创建，隔离候选集 |
| occurred_at 注入 | Fixture | 经 /v1/add 的 occurred_at 参数控制 t_message（无 occurred_at 时 t_message 为 null），支撑时间范围/保留最新/排序兜底场景 |
| 本地 KV + 检索索引 | 真实依赖 | SUT 进程内 offline 装配，不 mock |

## 7. 关键链路断言

主门禁（任一失败即特性不通过）：

- **F104-01**：批量删除时间范围交集——after 是本特性对 DeleteSelector 的首要扩展（A1/FR-01）。
  选择器拒绝新字段（ValidationError unknown field）即扩展未实现。
- **F104-03**：keep_latest 保留最新——运营"保留最新 1000 条"场景的核心语义（A2/FR-03）。
- **F104-07**：batch_update 端点存在 + 统一补丁——本特性第二个子特性的入口前提（A3/FR-06）。
  404 UnknownVerb 即端点缺失。
- **F104-12**：内核保留键拒绝——批量更新不可绕过内核写入边界（A4/FR-09）。

失败语义要求：负向场景（F104-10 无 unit_ids、F104-11 数量不一致、F104-12 内核键）
必须同时命中目标错误类型（400 + ValidationError）与拒绝动作（不产生任何更新/不落值），
不允许静默合并或部分执行（§6.6 fail-fast）。

## 8. 执行策略

- **Smoke 集合**：F104-01、F104-03、F104-07（两个子特性各取最小主门禁子集）。
- **Full suite**：F104-01~F104-20 全部。
- **P0 必须全绿**：F104-01、F104-02、F104-03、F104-04、F104-07（两项子特性的核心正例）。
- **依赖门禁**：场景间无硬依赖，各自独立 space；F104-13/F104-14 各自内嵌失败项构造。
- **推荐 agent**：D:/code/test/agent-solution/common/example/personal-assistant-deepagent-demo
  （见 feat_test.yaml recommended_agent；本轮场景全部为直连 HTTP 黑盒，不依赖该 agent 链路）。

执行命令：

```bash
./mvnw test -Dtest.env=openjiuwen -Dtest=Feat104MemoryBatchManagementBlackboxTest -Dmaven.test.failure.ignore=true
# 或按特性 Tag 全量
./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-104 -Dmaven.test.failure.ignore=true
```

## 附录：执行记录（2026-11-06）

- 被测制品：agent-memory 源码直接运行（commit `a9011c7`，mem2.0 分支当日 fast-forward 至
  远端最新），offline profile + 配置层 `memory_api.engine.default.target=agent`（空间隔离）
  + dev auth（root-key→root）。
- 执行命令：`./mvnw test -Dtest.env=openjiuwen -Dtest=Feat104MemoryBatchManagementBlackboxTest
  -Dmaven.test.failure.ignore=true`
- 执行结果：**15 run / 14 FAIL / 0 SKIP / 1 PASS**。
- 定性：14 例失败全部定性为**代码实现问题**（产品行为与特性档不符），用例未做任何
  适配性修改；1 例通过（F104-06 兼容路径，存量删除语义未被破坏）。
- 失败分布与 issue 对应：

| 场景 | 失败点 | 定性 | Issue |
|---|---|---|---|
| F104-01~05 | selector 携带 after/limit/keep_latest 任一字段即 400 ValidationError "unknown field(s) for selector" | 实现缺口（DeleteSelector 无三字段） | draft-feat-104-delete-selector-extension-missing |
| F104-07~15 | POST /v1/batch_update → 404 UnknownVerb | 实现缺口（端点四形态均无入口，全仓零实现） | draft-feat-104-batch-update-endpoint-missing |

- 交叉通道：MCP 无 batch_update 工具、CLI 无 batch_update 命令、无 keep_latest 参数（静态核实）。
- 结论：FEAT-104 两项子特性（批量删除扩展、批量更新）在当前可获取源码树中均未实现
  （特性档 §9.1 声称"已实现于 feat/batch-management 分支"，该分支在远端
  develop/main/mem2.0/stable 均不存在）。存量删除/读取/写入/空间管理能力正常（F104-06 通过，
  各场景前置操作全部成功）。特性验收结论：**不通过**，待 2 项 issue 修复后按本设计回归。

## 附录：执行记录（2026-11-09，第二轮回归）

- 被测制品：agent-memory 源码直接运行，commit `9e474e7`（mem2.0，新增
  `f1f7e5b 支持批量更新与删除功能扩展`、`19742f9 批量更新修复`、
  `9e474e7 !1 merge feat/memory-ops`；执行前 fast-forward 校验 HEAD==origin/mem2.0）。
- 执行命令同第一轮。
- 首轮结果：15 run / 10 FAIL / 5 PASS——两项子特性入口均已实现（unknown field / 404 消失）。
- 用例整改（4 处，均有探针实证支撑，非适配产品缺陷）：
  - F104-07/F104-09：统一补丁模式 outcomes 顺序断言改为按 unit_id 映射断言
    （契约只要求"按扫描顺序对齐"，扫描序为存储扫描序、方向未钉）；
    F104-09 content 改读 `segments[0].content`（wire 无顶层 content，实证）。
  - F104-11：错误 message 关键词补 "equal"（FRS AC-08 未钉措辞，
    实测 message "len(unit_ids)=2 must equal len(patches)=3" 语义明确）。
  - F104-15："其余候选不动"改以 outcomes[].unit_id 为准判定
    （supersede 更新后按旧 id get 返回旧版本快照，旧 id 无法区分已更新/未更新，实证）。
  - F104-14：content 字段路径同 F104-09 整改（outcome 语义断言未动）。
- 整改后重跑（同命令）：**15 run / 9 PASS / 6 FAIL / 0 SKIP**。
- 剩余 6 例失败全部定性为**代码实现问题**，分属 2 个缺陷：

| 场景 | 失败点 | 定性 | Issue |
|---|---|---|---|
| F104-02~05 | selector={scope, limit}/{scope, keep_latest} 400 "DeleteSelector requires unit_ids, tags, before, or filters"——空选择器守卫未把 limit/keep_latest 计为选择条件（API 层 + 两引擎三份拷贝） | 实现缺口（守卫计口未随字段扩展同步） | draft-feat-104-delete-selector-guard-rejects-limit-keep-latest |
| F104-13/14 | 逐条模式 unit_ids 混入不存在 id 被静默过滤：无 error outcome（FR-10/AC-09/AC-10 不可达），且补丁按过滤后位置对应导致 M2 被写入他人补丁（AC-05 逐条对应破坏，静默数据损坏） | 实现缺口（扫描式候选获取与逐条身份对应冲突） | draft-feat-104-batch-update-missing-id-silent-drop |

- 本轮通过：F104-01（时间范围交集）、F104-06（兼容路径）、F104-07/08（统一补丁）、
  F104-09（逐条对应，id 全存在时）、F104-10/11（fail-fast 校验）、F104-12（内核保留键）、
  F104-15（batch_update limit 截断）。
- 交叉通道（静态核实）：MCP `memory_batch_update` 工具已存在（委托同一实现，
  缺陷 B 同样命中）；MCP `memory_delete` 未暴露 after/limit/keep_latest；
  CLI 无 batch_update 命令。
- 结论：FEAT-104 两项子特性主体已落地（9/15 通过，含全部 P0 门禁中的
  F104-01/02 以外项——F104-02 仍红）。特性验收结论：**不通过**（较第一轮显著收敛），
  待 2 项 issue 修复后按本设计回归；用例侧无遗留问题。

## 附录：执行记录（2026-11-09，第三轮回归——缺口用例补齐）

- 背景：对特性档 §3（当前能力要求）、§4（外部接口与入口要求）、§5（场景与用户旅程）
  做覆盖缺口分析，识别 7 项缺口（6 项用例缺口 + 1 项特性档措辞问题），本轮全部补齐：
  1. keep_latest 保留集是否排除非 ACTIVE（§3.2）→ 新增 F104-16；
  2. delete mode 三态语义 archive/downweight/purge（§4.2）→ 新增 F104-17；
  3. batch_update after/before/filters 三条件选择器（§3.1/§4.3）→ 新增 F104-18；
  4. batch_update user_metadata 合并语义（补丁不整包覆盖）→ F104-12 扩展断言；
  5. delete after+before+tags 三条件交集（§5.3 运营场景）→ 新增 F104-19；
  6. 内容变更重算嵌入并可检索（§5.3）→ 新增 F104-20；
  7. 特性档 §4.3 "limit（保留参数，当前未使用）" 措辞与 FRS FR-11（limit 截断
     MUST）矛盾 → 特性档侧修订（见特性档更新说明）。
- 被测制品：同第二轮（commit `9e474e7`，mem2.0）。执行命令同前。
- 用例整改（3 处，均有探针实证，非适配产品缺陷）：
  - F104-18 filters：selector.filters 为严格 FilterExpr 结构化形态
    （{"field","op","value"}），search 侧的 dict DSL 简写（{"src":"crm"}）在
    selector.filters 上 400（"unknown field(s) for selector.filters: 'src'"）——
    两处 wire 语法不同，已按 api_contract 实证改为结构化谓词（field 需带
    user_metadata. 前缀走 canonical）；
  - F104-20：/v1/search 响应结构为 items[].unit_id（非 hits[].item_id）；
    索引为异步构建（add 后立即检索 0 命中），新增轮询断言辅助；新旧内容改用
    完全不相交词表（哈希嵌入下部分 token 重叠会带来合法低分相似，原词表
    "ZEBRA-QUARK-104" 检索会低分命中新版本，不构成索引残留证据）；
  - F104-12：扩展 user_metadata 合并语义断言（patch 只带 merged-key 时，
    更新后 user_metadata 应同时含原 orig-key 与 merged-key——合并而非整包覆盖）。
- 结果：**20 run / 13 PASS / 7 FAIL / 0 SKIP**。新增 5 用例（F104-16~20）中
  F104-17/18/19/20 通过，F104-16 红归缺陷 A。
- 剩余 7 例失败全部归因既有 2 缺陷，无新增产品缺陷面：
  F104-02/03/04/05/16 → 缺陷 A（delete 守卫不认 limit/keep_latest，5 例）；
  F104-13/14 → 缺陷 B（缺失 id 静默丢弃 + 补丁错位，2 例）。
- **新发现缺陷 C（F104-18 首版 before 子步运行时实证，已单独成文）**：选择器批量
  更新/删除的候选扫描（agent_engine.py permission_contexts_for_update :530-543）
  不按 lifecycle 过滤，superseded 历史版本被重新匹配为候选——W2 一条记忆的两个
  版本同时入选并各自生成新版本（2 条有效记忆返回 3 项 outcome），且从陈旧版本
  派生的新版本 t_invalid 早于其 t_valid（异常时间态）；delete 扫描同构疑似同病
  （静态）。已按 review-checklist 第 3 章整理：
  draft-feat-104-batch-update-superseded-version-rescanned（P1）。
  F104-18 现行通过系因三子空间设计刻意规避了"选择器批次前发生过 supersede"
  的组合；缺陷 C 修复后该组合应作为 red-first 看守断言纳入 F104-18
  （outcomes 数==当前有效记忆数）。
- 结论：缺口用例全部落地并通过（除 F104-16 依赖缺陷 A 修复）。特性验收结论：
  **不通过**（13/20，待缺陷 A/B/C 修复后回归）。

## 附录：执行记录（2026-11-11，第四轮回归——缺陷 A/B/C 修复后验证）

- 被测制品：agent-memory 源码直接运行，commit `2a19dff`（mem2.0 tip == origin/mem2.0，
  含修复 `f118b34 fix(memory): 修复 issue #6/#7（batch_update 补丁错位 + delete 空守卫误拒）`、
  `616b1ec fix(memory): 选择器批次过滤 lifecycle，排除 SUPERSEDED 历史版本（Issue #8）`、
  `4495bb9 fix(memory): 更新 continue_on_error HTTP 测试并统一引擎选择器扫描写法`，
  即缺陷 A/B/C 的上游修复）。执行前冒烟拉起校验 `/healthz` 200。
- 执行命令同前。首轮结果：**20 run / 19 PASS / 1 FAIL**——缺陷 A/B/C 的失败场景
  （F104-02/03/05/16、F104-13/14、F104-18 现行形态）全部转绿，仅 F104-04 新形态红。
- F104-04 失败定性：**测试用例问题**（断言强于契约钉点），非产品缺陷：
  - 实测证据（独立探针，keep_latest=2 + limit=2，B1~B5 t_message 50/40/30/20/10 天前递增）：
    200；deleted 恰 2 条且 ⊆ {B1,B2,B3}（实际删除 B2/B3，留下 B1）；B4/B5 保持 active。
    keep_latest 保留集计算与删除集产出均正确，仅删除集内部截断方向与用例钉死的
    "最旧优先（删 B1/B2）"不同。
  - 契约核对：FRS FR-05（"keep_latest 优先产出删除集，limit 在该删除集上截断"）、
    AC-02（"保留最新 1000 条，删除 500 条较旧记忆"——较旧系相对保留集而言）、
    FRS §6.2 步骤 4（仅"截断到 500 条"，未钉删除集内部方向）、FEAT-104 §6.2
    （"limit 在该删除集上截断"）均未钉截断方向；开发侧单测
    `tests/unit/api/test_batch_delete.py::test_keep_latest_and_limit_combined` 亦仅断言条数。
    设计矩阵 F104-04 原文即"deleted 恰 2 条且均属于 {B1,B2,B3}"。
  - 运营语义复核：FRS §6.2 "剩余 500 条保留为 ACTIVE 待下次操作"——SUT 语义下重复调用
    逐批收敛（下一轮最旧者必入选删除集），"保留最新 1000 条，本次最多删 500 条"场景成立。
- 用例整改（2 处，均有探针/契约实证，非适配产品缺陷）：
  - F104-04：断言收敛回设计矩阵钉点——deleted 恰 2 条且 ⊆ {B1,B2,B3}、保留集 B4/B5
    不被删、{B1,B2,B3} 中未入选截断者保持 active；注释记录契约未钉删除集内部截断方向。
  - F104-18：按第三轮附录计划，缺陷 C 已修复（616b1ec），在 spaceA 补入 red-first
    看守断言——同选择器看守批次 outcomes 数==当前有效记忆数（恰 2 项）且 superseded
    旧版（旧 id W2/W3）不得重新匹配，命中的有效版本应用看守补丁。
- 整改后重跑（同命令）：**20 run / 20 PASS / 0 FAIL / 0 SKIP**。
- 既有 3 个 issue（缺陷 A/B/C）上游修复已全部经 wire 实证闭环，issue 草稿已随修复移除。
- 交叉通道：本轮未复测 MCP/CLI 形态（缺陷 A/B/C 修复位于引擎选择器扫描与守卫层，
  MCP `memory_batch_update` 委托同一实现，随修复一并生效；CLI 无 batch_update 命令，
  维持第三轮静态核实结论）。
- 结论：FEAT-104 两项子特性（批量删除扩展 + 批量更新）20 个场景全部通过，
  含全部 P0 门禁（F104-01/02/03/04/07）。特性验收结论：**通过**。

## 附录：执行记录（2026-11-11，复跑确认）

- 被测制品：agent-memory 最新源码，HEAD `2a19dff331bd514e83599f37cb50189b1c23a44b`
  （2026-11-10，`!3 merge feat/issue-6-7 into mem2.0`），工作区无未提交改动。
- 重新部署：`python -m pip install -e . --no-deps --no-build-isolation` 重装
  JiuwenMemory 0.2.0；冒烟启动后 `GET /healthz` = 200（offline profile）。
- 执行命令：`./mvnw test -Dtest=Feat104MemoryBatchManagementBlackboxTest
  -Dtest.env=openjiuwen -Dmaven.test.failure.ignore=true`
- 执行结果：**20 run / 20 PASS / 0 FAIL / 0 SKIP**（用例耗时 7.204s）。
- 定性：无代码实现问题失败，未生成新 issue；历史缺陷 A/B/C 修复保持有效。
- 结论：特性验收结论维持 **通过**。报告见
  `build-logs/FEAT-104-test-report-20261111.md`。
