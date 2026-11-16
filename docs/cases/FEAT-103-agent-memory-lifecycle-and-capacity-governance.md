---
feature_id: FEAT-103
feature_title: agent-memory 生命周期与容量治理（软删除与保留期 + 容量与 LRU 淘汰）
scope: vNext; docs/develop/02-features/FEAT-103-agent-memory-生命周期与容量治理.md (updated 2026-10-27)
deployable_units: [agent-memory (Python, jiuwen_memory_entry.http_server)]
sut: agent-memory 生命周期与容量治理面（POST /v1/restore 新端点 + 清扫已清除路径 + space_usage 扩展 + LRU 淘汰）
status: verified-pass
features: [FEAT-103]
updated: 2026-11-11
---

# FEAT-103 — 生命周期与容量治理（软删除与保留期 + 容量与 LRU 淘汰）测试设计

## 1. 测试目标

以 agent-memory（Python，`jiuwen_memory_entry.http_server`，offline profile，dev auth，root 身份）为 SUT，
验证 FEAT-103 定义的两项子特性——**软删除与保留期**（M1/P0：FORGET 遗忘时间戳、PURGED 已清除终态、
restore 恢复接口、保留期策略、清扫已清除路径、purge 开关）与**容量与 LRU 淘汰**（M2/P2：
space_usage 按状态分类统计与配额、quotas 显式 opt-in 淘汰、LRU 淘汰产出遗忘、误淘汰可恢复）的
外部可观察行为。

观察 surface 限定为 HTTP wire 契约：`POST /v1/restore`（特性文档 §4.1 新端点）、
`POST /v1/space_usage`（§4.4 扩展端点）、既有 `POST /v1/add`、`/v1/search`、`/v1/get`、
`/v1/delete`、`/v1/update`、`/v1/admin_set`、`/v1/admin_get`、`/v1/get_space_policy`、
`/v1/set_space_policy`，以及清扫任务手动触发入口（FRS-103 DEC-07 / 附录 A.1 的 sweep 命令）。
本设计不编译/导入 SUT 内部类、不读取 SUT 内部状态或私有 SPI，全部断言落在 HTTP 请求/响应上。

## 2. 范围与非范围

### 范围

- 遗忘时间戳记录：delete(mode=forget) 后记忆单元 system_metadata.forgotten_at 为 ISO 时间戳、
  lifecycle=="forgotten"、实时检索不召回（A1 / FR-01）。
- 内核保留键保护：add/update 伪造 forgotten_at 必须拒绝（A1 / §6.1）。
- 恢复接口：保留期内 restore 将 FORGOTTEN 恢复回 ACTIVE 并重建检索索引，返回 restored_ids；
  非遗忘态归入 skipped_ids（A2 / FR-05/FR-06）。
- 恢复选择器校验：selector 全空返回 ValidationError（A11 / FR-05）。
- 清扫已清除路径：遗忘时间戳加保留期小于当前时间且 purge 开关开启时，标记 PURGED 再物理删除，
  KV 中单元消失（A4 / FR-03/FR-04）。
- PURGED 终态不可恢复：restore 归入 not_found_ids，不静默成功（A3 / FR-06 / C-08）。
- purge 开关：lifecycle.purge_forgotten.enabled=false 时清扫不扫描 FORGOTTEN 到期（A12 / FR-10）。
- 保留期策略键：全局 lifecycle.forgotten_retention_days 可运行时设置；空间策略
  retention.forgotten_retention_days 可经 set_space_policy 写入并读回（A6 / FR-07）。
- 历史检索过滤 PURGED：PURGED 态不出现在历史检索结果（A10 / FR-09）。
- space_usage 扩展：返回 active_count/forgotten_count/purged_count/superseded_count/
  archived_count/memory_count（五态之和）+ max_units/usage_rate（A8 / FR-16）。
- usage_rate 口径：active_count/max_units，遗忘积压不误报（A8 / DEC-05 / §6.4）。
- LRU 淘汰：quotas 显式配置 eviction_enabled=true + max_units=N 时清扫末尾按最近访问时间
  升序淘汰到 FORGOTTEN（非直接清除），误淘汰可 restore（A7 / FR-13/FR-15）。
- 淘汰 opt-in：quotas 未显式配置（缺 eviction_enabled 或 max_units）时不淘汰（FR-13 / §6.4）。
- 只淘汰 ACTIVE：ARCHIVED/SUPERSEDED 不被淘汰（FR-14）。
- restore 幂等：重复 restore 同一记忆归入 skipped_ids（FRS §8.2）。

### 非范围

- 崩溃恢复（A5 / FR-04 两步之间崩溃重试物理删除）：需进程级故障注入（kill -9 于标记与物理删除
  之间），offline profile 无注入点；由故障注入专项承接。
- 旧数据无遗忘时间戳跳过（FR-08）：黑盒无法构造"无 forgotten_at 的旧 FORGOTTEN 数据"（无法经
  wire 注入旧格式数据）；由 L2 白盒/数据迁移专项承接。
- eviction_strategy 装配期不可变（A9 / FR-17）：LRU 与 FIFO 在小规模 fixture 下区分需要精确
  控制 last_accessed_at 与摄入时序且当前仅实现 LRU，无第二策略可对照；由 L2 白盒承接。
- provenance 断链不阻断恢复的级联细节（§3.1）：依赖 evolve 合成记忆链路（LLM 依赖），由
  e2e 环境承接。
- 双段鉴权 403 路径（FRS §11.3）：dev auth 身份固定，由 required-auth e2e 承接。
- 访问时间追踪的 60 秒延迟批量回写间隔断言（FR-12）：内部定时器行为不可经 wire 观察，经
  F103-13 的 LRU 排序结果间接覆盖。
- 保留期按记忆粒度配置、跨空间配额、LFU/FIFO/TTL 淘汰策略（OUT）：不验证。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| docs/develop/02-features/FEAT-103-agent-memory-生命周期与容量治理.md（2026-10-27） | §3.1/§3.4/§3.5 能力要求、§4.1/§4.4 入口契约、§6.1/§6.4 行为语义、§9.2 A1~A12 验收口径 |
| docs/develop/01-scenarios/FRS-103-agent-memory-生命周期与容量治理.md（v0.1, 2026-10-27） | FR-01~FR-17 功能要求、AC-01~AC-15 验收标准、§8 异常与幂等、附录 A.1 sweep 触达方式、DEC-05/DEC-07 决策 |

## 4. 部署拓扑

```text
+--------------------------------------------------------------+
|  JUnit (Feat103MemoryLifecycleCapacityBlackboxTest)           |
|    |  ProcessBuilder 拉起 / 回收 SUT 进程                      |
|    v                                                          |
|  agent-memory (Python)  jiuwen_memory_entry.http_server       |
|    --auth-mode dev  --host 127.0.0.1  --port <free>           |
|    dev_identities: root-key -> Role.ROOT                      |
|    offline profile（KV=本地文件/内存，无外部中间件）             |
+--------------------------------------------------------------+
      ^ HTTP (127.0.0.1)                    真实 SUT（源码直接运行）
      |  /v1/add /v1/search /v1/get /v1/delete /v1/update
      |  /v1/restore /v1/space_usage /v1/admin_set /v1/admin_get
      |  /v1/get_space_policy /v1/set_space_policy /v1/sweep(手动触发)
```

边界要求：

- **真实依赖（不 mock）**：本地 KV 存储、检索索引——随 SUT 进程内真实装配，不做任何替换。
- **真实 SUT**：feature_source（D:/code/test/agent-memory）源码直接运行，不打桩、不加载
  测试替身；每次执行前从源码最新状态拉起。
- **观察 surface 限定**：仅 HTTP wire（上述端点）与 SUT 进程 stdout 日志（仅用于失败诊断，
  不作为断言依据）。不读 SUT 内部对象、KV 文件内容、私有 SPI。
- **隔离策略**：每个场景使用独立 space（f103-<case>），org 固定 local；避免空间级策略
  （retention/quotas）跨场景串扰。
- **故障注入手段**：无（崩溃恢复场景不在本轮范围）。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture / 依据 |
|---|---|---|---|---|---|
| F103-01 | FORGET 记录遗忘时间戳并退出检索（正例） | 空间 f103-timestamp 有 1 条 ACTIVE 记忆 M1 | 1) POST /v1/delete selector unit_ids=[M1] mode=forget；2) POST /v1/get M1；3) POST /v1/search 原文关键词 | 1) 200 且响应含 M1；2) 200 且 lifecycle=="forgotten" 且 system_metadata.forgotten_at 为非空 ISO-8601 时间戳；3) 200 且 items 中不含 unit_id==M1 | root token |
| F103-02 | 伪造遗忘时间戳拒绝（权限/保留键） | 无 | 1) POST /v1/add system_metadata 含 forgotten_at；2) 对既有 ACTIVE 记忆 POST /v1/update patch system_metadata.forgotten_at | 1) 4xx 且 error=="ValidationError"；2) 4xx 且 error=="ValidationError"；两次均不得使 forgotten_at 落到记忆单元 | root token |
| F103-03 | 保留期内 restore 恢复回 ACTIVE 并重建索引（正例） | 空间 f103-restore 记忆 M1 已 forget | 1) POST /v1/restore selector unit_ids=[M1]；2) POST /v1/get M1；3) POST /v1/search 原文关键词 | 1) 200 且 restored_ids==[M1] 且 not_found_ids 空 且 skipped_ids 空；2) 200 且 lifecycle=="active" 且 system_metadata.forgotten_at 不存在；3) items 含 unit_id==M1 | root token |
| F103-04 | restore 非遗忘态跳过（边界） | 空间 f103-skip 有 ACTIVE M1 | POST /v1/restore selector unit_ids=[M1] | 200 且 skipped_ids==[M1] 且 restored_ids 空 | root token |
| F103-05 | restore selector 全空拒绝（异常） | 无 | POST /v1/restore selector 空 | 400 且 error=="ValidationError" 且 message 含至少一项选择条件提示 | root token |
| F103-06 | 清扫已清除路径：到期 PURGED 并物理删除（正例） | 空间 f103-purge 记忆 M1 已 forget；全局保留期设 0 天；purge 开关开启 | 1) 触发清扫（手动 sweep 入口）；2) POST /v1/get M1 | 1) 200；2) 404 且 error=="NotFoundError"（本体已物理删除） | root token |
| F103-07 | PURGED 终态不可恢复（状态机非法迁移） | M1 已被清扫标记 PURGED | POST /v1/restore selector unit_ids=[M1] | 200 且 not_found_ids==[M1] 且 restored_ids 空；不降级为恢复成功 | root token |
| F103-08 | purge 开关关闭暂停物理删除（正例） | 空间 f103-purgeoff M1 已 forget；lifecycle.purge_forgotten.enabled=false；保留期 0 天 | 1) 触发清扫；2) POST /v1/get M1 | 1) 200；2) 200 且 lifecycle=="forgotten"（未被物理删除） | root token |
| F103-09 | 保留期策略键与空间级覆盖（正例） | 空间 f103-retention 已创建 | 1) admin_set lifecycle.forgotten_retention_days=90；2) admin_get 同键；3) set_space_policy 写 retention.forgotten_retention_days=30；4) get_space_policy 读回 | 1) 200；2) 200 且值为 "90"；3) 200；4) 200 且 retention.forgotten_retention_days=="30" | root token |
| F103-10 | 历史检索过滤 PURGED（正例） | F103-06 场景：M1 已走完 PURGED 物理删除 | POST /v1/search query+context+as_of 当前时间 | 200 且 items 不含 unit_id==M1 | root token |
| F103-11 | space_usage 按状态分类统计与配额字段（正例） | 空间 f103-usage 有 2 条 ACTIVE + 1 条 FORGOTTEN；quotas 未配置 | POST /v1/space_usage | 200 且 active_count==2 且 forgotten_count==1 且 purged_count==0 且 superseded_count==0 且 archived_count==0 且 memory_count==3 且 max_units 为空/null 且 usage_rate 为空/null | root token |
| F103-12 | usage_rate 口径=active/max_units（边界） | 空间 f103-usage-rate：1 条 ACTIVE + 2 条 FORGOTTEN；quotas max_units=2 | POST /v1/space_usage | 200 且 max_units==2 且 usage_rate==0.5（遗忘积压不误报） | root token |
| F103-13 | LRU 淘汰产出遗忘且可恢复（正例） | 空间 f103-lru：quotas max_units=2, eviction_enabled=true, eviction_batch_size=10；M1/M2/M3 三条 ACTIVE，M3 最久未访问 | 1) 触发清扫；2) get M1/M2/M3；3) restore 按 scope selector 恢复；4) get M3 | 1) 200；2) M1/M2 lifecycle=="active"，M3 lifecycle=="forgotten"（按最近访问时间升序淘汰 1 条，非直接清除）；3) 200 且 restored_ids 含 M3；4) 200 且 lifecycle=="active" | root token |
| F103-14 | quotas 未显式配置不淘汰（异常/opt-in） | 空间 f103-noquota：3 条 ACTIVE，quotas 未配置 eviction_enabled/max_units | 1) 触发清扫；2) get 三条记忆 | 1) 200；2) 三条均 200 且 lifecycle=="active"（无淘汰发生） | root token |
| F103-15 | 只淘汰 ACTIVE，不淘汰 ARCHIVED/SUPERSEDED（状态机） | 空间 f103-onlyactive：quotas max_units=1, eviction_enabled=true；2 条 ACTIVE（A1/A2）+ 1 条 ARCHIVED（B） | 1) 触发清扫；2) get A1/A2/B | 1) 200；2) B lifecycle=="archived" 未被淘汰；A1/A2 中恰一条 lifecycle=="active"、一条 lifecycle=="forgotten"（超限 1 条只从 ACTIVE 中淘汰，B 不进入淘汰候选） | root token |
| F103-16 | restore 幂等：重复 restore 归入 skipped（时序/幂等） | F103-03 场景：M1 已恢复 ACTIVE | 再次 POST /v1/restore selector unit_ids=[M1] | 200 且 skipped_ids==[M1] 且 restored_ids 空 | root token |
| F103-17 | restore 按 tags 定位批量恢复 | 空间 f103-gap-tags 2 条同 tag FORGOTTEN | POST /v1/restore selector {tags} | restored_ids 含 2 条、skipped 空、get 均 active | root token；§4.1 tags |
| F103-18 | restore 按 before/after 时间范围 | 2 条 occurred_at 分别早/晚于分界的 FORGOTTEN（t_message 口径） | restore {tags, before} → forget → restore {tags, after} | before 仅恢复早于分界者、after 仅恢复晚于分界者 | root token；§4.1 before/after |
| F103-19 | restore 按 filters 谓词定位 | 2 条 user_metadata.dept=sales/hr 的 FORGOTTEN | restore {tags, filters: user_metadata.dept eq sales} | 仅 dept=sales 被 restored，dept=hr 保持 forgotten | root token；§4.1 filters |
| F103-20 | restore limit 截断 | 2 条同 tag FORGOTTEN | restore {tags, limit:1} | restored_ids 恰 1 条，另一条保持 forgotten | root token；§4.1 limit |
| F103-21 | 空间级保留期覆盖在清扫判定生效 | 全局保留期 0 天；A 空间 retention 90 天 | forget A1/B1 → set_space_policy → sweep | A1 保持 forgotten（90 天未到期）；B1 物理删除（get 404） | root token；§3.1/§5.1 空间级保留期覆盖 |
| F103-22 | eviction_batch_size 批量约束 | quotas max_units=1, batch_size=2；4 条 ACTIVE | sweep | 恰 2 条 forgotten、2 条 active（to_evict=3 被 batch 截断） | root token；§3.5 淘汰批量 |
| F103-23 | eviction_headroom 预留 | quotas max_units=10, headroom=0.5；6 条 ACTIVE | sweep | 恰 1 条 forgotten、5 条 active（有效目标=5） | root token；§4.4 eviction_headroom |
| F103-24 | eviction_enabled 显式 false 不淘汰 | quotas max_units=1, eviction_enabled=false；3 条 ACTIVE | sweep | 3 条均 active | root token；§3.5 淘汰开关 |
| F103-25 | LRU 空值按摄入时间兜底 | quotas max_units=2；3 条 ACTIVE 依序写入且无访问 | sweep | 最早摄入者 forgotten，其余 active | root token；§3.5 LRU 扫描 |
| F103-26 | ARCHIVED 态 restore 跳过 | 1 条 ARCHIVED | restore {unit_ids} | skipped_ids 含该 id、restored 空、lifecycle 保持 archived | root token；§5.1 非遗忘态跳过（归档态） |
| F103-27 | 遗忘与恢复操作审计留痕 | 1 条记忆 | forget → audit {action:delete}；restore → audit {action:restore} | delete 事件 detail.before_unit_ids 含 id 且 actor.user=developer；restore 事件 detail.restored_ids 含 id | root token；§5.1 合规审计留痕 |

补充场景 F103-17~27 为 2026-11-09 覆盖缺口分析后并入本矩阵（原 5.2 补充矩阵），
针对特性档 §3/§4/§5 与主矩阵（F103-01~16）的比对缺口补充，事实来源与主矩阵一致；
F103-21~25 依赖 sweep 触达入口，前置失败时以失败呈现、逐项定性，不静默跳过。
"最老遗忘天数"（§5.4）因特性档 §4.4/§5.4 内部不一致（见
issues/draft-feat-103-space-usage-oldest-forgotten-days-doc-ambiguity.md）暂缓生成用例，
待文档澄清后补充。

### 5.1 显式 deferred / 白盒联动项

| 项 | 不覆盖原因 | 承接方 |
|---|---|---|
| A5 崩溃恢复（PURGED 标记后物理删除前崩溃重试） | 需进程级故障注入，offline profile 无注入点 | 故障注入专项 |
| FR-08 旧数据无遗忘时间戳跳过 | 黑盒无法经 wire 构造无时间戳的旧 FORGOTTEN 数据 | L2 白盒 / 数据迁移专项 |
| A9 eviction_strategy 装配期不可变 | 当前仅实现 LRU，无对照策略；策略实例注入为装配期内部行为 | L2 白盒 |
| provenance 断链级联细节 | 依赖 evolve 合成链路（LLM） | e2e 环境 |
| 双段鉴权 403 | dev auth 身份固定 | required-auth e2e |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| jiuwen_memory_entry.http_server | 真实 SUT | feature_source 源码直接运行；--auth-mode dev + root 身份配置文件；offline profile |
| root-key dev 身份 | Fixture | 测试进程写 target/sit-logs/feat103-dev-identities.json，root-key→Role.ROOT，ADMINISTER_SYSTEM/UPDATE 权限前提 |
| 每场景独立 space | Fixture | f103-<slug> 空间经 create_space 创建，隔离空间级 retention/quotas 策略 |
| 本地 KV + 检索索引 | 真实依赖 | SUT 进程内 offline 装配，不 mock |
| 清扫手动触发入口 | 真实 SUT | FRS-103 DEC-07/附录 A.1 约定的手动 sweep 触达方式（HTTP verb sweep）；缺失即实现缺口 |

## 7. 关键链路断言

主门禁（任一失败即特性不通过）：

- **F103-01**：FORGET 写入遗忘时间戳——保留期机制的计算起点（A1）。forgotten_at 缺失时
  保留期、清扫已清除路径、LRU 淘汰保留期全部失去事实基础。
- **F103-03**：restore 恢复遗忘态——M1 生命周期的核心新增入口（A2）。404 UnknownVerb 即端点缺失。
- **F103-06**：清扫已清除路径——FORGOTTEN 不再永久停留的前提（A4）。依赖手动 sweep 触达入口存在。
- **F103-11**：space_usage 扩展字段——容量治理的观察面（A8）。
- **F103-13**：LRU 淘汰产出遗忘——淘汰必须走 FORGET 保留期而非直接清除（A7/C-05）。

失败语义要求：负向场景（F103-02 伪造保留键、F103-05 selector 全空、F103-07 PURGED 恢复）
必须同时命中目标错误类型（ValidationError / not_found_ids）与拒绝动作（不落值/不恢复），
不允许降级兜底（§6.6：恢复 PURGED 不可降级为恢复遗忘态；LRU 淘汰不可降级为直接清除）。

## 8. 执行策略

- **Smoke 集合**：F103-01、F103-03、F103-11（三个主门禁的最小可跑子集）。
- **Full suite**：F103-01~F103-27 全部。
- **P0 必须全绿**：F103-01、F103-02、F103-03、F103-04、F103-05（M1 软删除与保留期子特性）。
- **依赖门禁**：F103-07/F103-10 依赖 F103-06 的清扫产出；F103-16 依赖 F103-03。场景在同一个
  有序测试类中按依赖顺序执行，前置失败时后续场景以失败呈现（不静默跳过，最终报告须逐项定性）。
- **推荐 agent**：D:/code/test/agent-solution/common/example/personal-assistant-deepagent-demo
  （见 feat_test.yaml recommended_agent；本轮场景全部为直连 HTTP 黑盒，不依赖该 agent 链路）。

执行命令：

```bash
./mvnw test -Dtest.env=openjiuwen -Dtest=Feat103MemoryLifecycleCapacityBlackboxTest -Dmaven.test.failure.ignore=true
# 或按特性 Tag 全量
./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-103 -Dmaven.test.failure.ignore=true
```

## 附录：执行记录（2026-11-06）

- 被测制品：agent-memory 源码直接运行（commit `e82584a`），offline profile + 配置层
  `memory_api.engine.default.target=agent`（offline 默认 InMemoryEngine 不支持 scope.space，
  FEAT-103 场景依赖 org/space 隔离）+ dev auth（root-key→root）。
- 执行结果：**16 run / 16 FAIL / 0 SKIP**（`Feat103MemoryLifecycleCapacityBlackboxTest`）。
- 定性：首轮 16 失败中 16 例为 fixture 问题（引擎装配不支持空间维），整改用例（加引擎配置层）
  后重跑；末轮 16 失败全部定性为**代码实现问题**（产品行为与特性档不符），用例不再改动。
- 失败分布与 issue 对应：

| 场景 | 失败点 | 定性 | Issue |
|---|---|---|---|
| F103-01 | system_metadata 无 forgotten_at（lifecycle=forgotten 本身通过） | 实现缺口 | draft-feat-103-forget-missing-forgotten-at |
| F103-02 | add 伪造 forgotten_at 返回 200 且落值 | 实现缺口 | draft-feat-103-forgotten-at-not-protected |
| F103-03/04/05/07/16 | POST /v1/restore → 404 UnknownVerb | 实现缺口 | draft-feat-103-restore-endpoint-missing |
| F103-06/08/09 | admin_set 保留期/purge 开关键 → 400 PolicyError unknown policy key | 实现缺口 | draft-feat-103-retention-policy-keys-missing |
| F103-10 | 依赖门禁（F103-06 未产出已删除 id） | 传播（根因同上） | 同上 |
| F103-11/12 | space_usage 响应缺 8 个扩展字段 | 实现缺口 | draft-feat-103-space-usage-not-extended |
| F103-13/14/15 | POST /v1/sweep → 404 UnknownVerb | 实现缺口 | draft-feat-103-sweep-trigger-missing |

- 结论：FEAT-103 两项子特性（软删除与保留期、容量与 LRU 淘汰）均处于"规划中"未实现状态，
  生命周期 4 态/清扫既有能力正常（遗忘流转、点读、检索、空间策略序列化可用）。特性验收结论：
  **不通过**，待 6 项 issue 修复后按本设计回归。

## 附录：执行记录（2026-11-09 回归，第二轮）

- 被测制品：agent-memory 源码直接运行（commit `9e474e7`，较上轮基线 `e82584a` 新增
  5 个提交：软删除优化与保留期 `a660386`、容量管理与 LRU 淘汰 `8679adf`、策略运行时热调
  `6be6ca6`、修复 `19742f9`、合并 `9e474e7`）。offline profile + 引擎配置层
  `memory_api.engine.default.target=agent` + dev auth（root-key→root），与首轮一致。
- 执行结果：**16 run / 7 FAIL / 0 SKIP**（`Feat103MemoryLifecycleCapacityBlackboxTest`）。
- 上轮 6 项 issue 中 4 项修复验证通过：restore 端点（F103-03/04/05/16 绿）、
  保留期策略键（F103-09 绿）、space_usage 扩展（F103-11/12 绿）、forgotten_at 伪造拒绝
  （F103-02 绿）。
- 本轮失败分布与定性：

| 场景 | 失败点 | 定性 | Issue |
|---|---|---|---|
| F103-01 | system_metadata 无 forgotten_at（lifecycle=forgotten 仍通过） | 实现缺口（残留面：仅 AgentEngine 未写，Cloud/InMemory 已修） | draft-feat-103-forget-missing-forgotten-at |
| F103-06 | POST /v1/sweep → 404 UnknownVerb（引擎 sweep_expired 已实现但无对外入口） | 实现缺口（残留面：入口层） | draft-feat-103-sweep-trigger-missing |
| F103-08/13/14/15 | 同上（sweep 404） | 同上 | 同上 |
| F103-07 | 依赖门禁：F103-06 未产出 PURGED，M1 仍为 FORGOTTEN 被 restore 恢复 | 传播（根因 sweep 入口缺失） | 同上 |

- F103-10 本轮"通过"为**空转通过**：其前置（F103-06 产出 PURGED 物理删除）未成立，
  M1 实际处于 FORGOTTEN 态（遗忘时已退出检索索引），断言命中但未真实覆盖
  "历史检索过滤 PURGED"。定性为依赖未达成，不作为 PASS 计入主门禁。
- 结论：两项子特性中，**软删除与保留期**主干已实现（仅 AgentEngine 漏写 forgotten_at），
  **容量与 LRU 淘汰**引擎侧已实现但清扫入口不可达，对外完全无法触达。特性验收结论：
  **不通过**，待 2 项残留 issue（draft-feat-103-forget-missing-forgotten-at、
  draft-feat-103-sweep-trigger-missing）修复后按本设计回归。

## 附录：执行记录（2026-11-09 补充覆盖轮，第三轮）

- 被测制品同第二轮（agent-memory @ `9e474e7`，源码直接运行）。
- 执行补充套件 `Feat103LifecycleCapacityGapBlackboxTest`（F103-17~27）：
  **11 run / 7 FAIL / 0 SKIP**。首轮编译失败 6 处（用例问题：JSON 链式调用类型/
  受检异常），整改后重跑至当前结果。
- 结果与定性：

| 场景 | 结果 | 定性 | Issue |
|---|---|---|---|
| F103-17 tags 选择器 | ✅ | — | — |
| F103-18 before/after 时间范围 | ✅ | — | — |
| F103-19 filters 谓词 | ✅ | — | — |
| F103-20 limit 截断 | ❌ restored_ids=2 | **新发现实现缺口**：AgentEngine.restore 未读 selector.limit（Cloud/InMemory 已实现） | draft-feat-103-restore-limit-not-applied |
| F103-21 空间保留期覆盖清扫 | ❌ sweep 404 | 传播（sweep 入口缺失） | draft-feat-103-sweep-trigger-missing |
| F103-22 batch_size 批量约束 | ❌ sweep 404 | 同上 | 同上 |
| F103-23 headroom 预留 | ❌ sweep 404 | 同上 | 同上 |
| F103-24 eviction_enabled=false | ❌ sweep 404 | 同上 | 同上 |
| F103-25 LRU 空值兜底 | ❌ sweep 404 | 同上 | 同上 |
| F103-26 ARCHIVED restore 跳过 | ❌ 归入 not_found_ids | **新发现实现缺口**：AgentEngine.restore else 分支误分类（RestoreResult 契约 not_found=PURGED 专属） | draft-feat-103-restore-archived-misclassified-not-found |
| F103-27 审计留痕 | ✅ | — | — |

- 过程新发现：执行前契约探针实证了 occurred_at→t_message 映射、filters 小写枚举
  反序列化、audit 事件结构（action/actor/detail.before_unit_ids），并首次发现
  restore limit 不生效缺陷。
- "最老遗忘天数"（§5.4）暂缓生成用例：特性档 §4.4/§5.4 内部不一致，文档澄清
  issue 已提交（draft-feat-103-space-usage-oldest-forgotten-days-doc-ambiguity），
  澄清后补充。
- 累计开放 issue 5 项（2 项二轮残留 + 2 项本轮新发现 + 1 项文档澄清）。
  根因模式高度集中：**AgentEngine 与 Cloud/InMemory 引擎生命周期路径不同步**
  （3 例）+ sweep 对外入口缺失（1 例）+ 文档内部矛盾（1 例）。

## 附录：执行记录（2026-11-10 回归，第四轮）

- 被测制品：agent-memory 源码直接运行（commit `2a19dff`，较上轮基线 `9e474e7` 新增 5 个提交：
  AgentEngine 四项修复 `3afa73c`（issue #1/#2/#3/#5）、批量更新修复 `f118b34`（#6/#7）、
  选择器 lifecycle 过滤 `616b1ec`（#8）、HTTP 测试修正 `4495bb9`、合并 `2a19dff`）。
  offline profile + 引擎配置层 `memory_api.engine.default.target=agent` + dev auth（root-key→root），
  与前三轮一致。
- 执行结果：全量 27 run / 6 FAIL / 0 SKIP（主套件 `Feat103MemoryLifecycleCapacityBlackboxTest`
  16 run / 4 FAIL；补充套件 `Feat103LifecycleCapacityGapBlackboxTest` 11 run / 2 FAIL）。
- 上轮 5 项开放 issue 修复验证：全部通过——sweep 触达入口（F103-06 sweep 200）、
  AgentEngine 写 forgotten_at（F103-01 绿）、restore limit（F103-20 绿）、
  ARCHIVED restore 误分类（F103-26 绿）、保留期/space_usage 既有修复无回归
  （F103-02/03/04/05/09/11/12/16/17/18/19/20/22/24/25/26/27 全绿）。
- 本轮失败分布与定性（用例整改 1 项后重跑的最终结果）：

| 场景 | 失败点 | 定性 | Issue |
|---|---|---|---|
| F103-06 | admin_set 全局保留期 0 天后 sweep 仍 `pending=0`，M1 未物理删除 | **实现缺口**：API 层与 lifecycle 层各自匿名新建 DictPolicyManager（Factory.dep 缺省路径不入缓存），admin_set 写实例 A、sweep 读实例 B，全局策略热调对清扫不可见 | draft-feat-103-global-policy-hot-adjust-not-reaches-sweep |
| F103-21 | B1（全局 0 天、空间无覆盖）sweep 后未清除；A1（空间级 90 天）正确保留 | 同上（全局通道失效、空间通道正常） | 同上 |
| F103-13 | LRU 淘汰 M1（检索刚命中）而非 M3（最久未访问） | **实现缺口**：AccessTracker 60s 延迟回写，sweep 淘汰决策前未同步访问时间，last_accessed_at=null 回退 t_ingest | draft-feat-103-lru-eviction-stale-access-time |
| F103-23 | headroom=0.5、6 条活跃（有效目标 5）时 0 淘汰 | **实现缺口**：超限守卫用原始 max_units 在 headroom 折算前短路（kv_lifecycle_manager.py:323） | draft-feat-103-eviction-headroom-guard-short-circuit |
| F103-07 | 依赖门禁：purgedId 未被 PURGED（仍 FORGOTTEN）被 restore 恢复 | 传播（根因全局策略热调不可见） | draft-feat-103-global-policy-hot-adjust-not-reaches-sweep |
| F103-10 | 依赖门禁：同上，M1 被 F103-07 的 restore 复活为 ACTIVE 后进入历史检索 | 传播（同上；上轮"空转通过"本轮真实暴露） | 同上 |

- 用例整改记录：F103-07 原实现新建一条刚 forget 的 eta 并立即 restore（期望 not_found_ids），
  与设计矩阵前置"M1 已被清扫标记 PURGED"不符，属用例问题；整改为复用 F103-06 的 purgedId
  后重跑，失败按依赖门禁传播如实呈现。
- 空转通过标注：F103-08（purge 开关关闭）本轮通过为**空转通过**——全局保留期未生效时
  sweep 按 30 天默认判定同样不清除，开关功能未被真实覆盖；随
  draft-feat-103-global-policy-hot-adjust-not-reaches-sweep 一并复核。
- 结论：软删除与保留期主干（forget 时间戳、restore 全选择器族、PURGED 语义、审计留痕、
  space_usage 扩展）已实现且四轮稳定；容量与 LRU 淘汰的批量/开关/兜底语义已实现，但
  **全局策略热调传播、淘汰对象新鲜度、headroom 折算守卫**三处实现缺口使清扫主门禁
  （F103-06）与 LRU 主门禁（F103-13）未通过。特性验收结论：**不通过**，待 3 项新 issue
  修复后按本设计回归。

## 附录：执行记录（2026-11-11 回归，第五轮）

- 被测制品：agent-memory 源码直接运行（commit `ebf482b`，origin/mem2.0 tip；较上轮基线
  `2a19dff` 新增 2 个提交：sweep 清扫链路三处缺陷纠正 `c4a0823`（issue #9/#10/#11，对应
  第四轮 3 项 issue）、合并 `ebf482b`）。执行前从 origin/mem2.0 拉取最新源码并以
  editable 方式重新打包部署（pip install -e .），SUT 冒烟（/healthz 200）后执行。
  offline profile + 引擎配置层 `memory_api.engine.default.target=agent` + dev auth
  （root-key→root），与前四轮一致。
- 执行命令：`./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-103 -Dmaven.test.failure.ignore=true`
- 执行结果：全量 **27 run / 2 FAIL / 0 SKIP**（主套件 `Feat103MemoryLifecycleCapacityBlackboxTest`
  16 run / 2 FAIL；补充套件 `Feat103LifecycleCapacityGapBlackboxTest` 11 run / 0 FAIL）。
- 上轮 3 项开放 issue 修复验证：**全部通过**——全局策略热调对清扫可见（F103-06 绿，
  purge 主门禁首次真实通过）、LRU 淘汰对象新鲜度（F103-13 淘汰步正确选中 M3）、
  headroom 折算守卫（F103-23 绿）；同时第二轮残留 2 项（forgotten_at 写入 F103-01、
  sweep 触达入口 F103-06/08）与第三轮 2 项（restore limit F103-20、ARCHIVED 误分类
  F103-26）无回归。历史 7 项代码 issue 全部闭环。
- 本轮失败分布与定性（两例均经 HTTP + 进程内 SDK 双通道复现，定性为代码实现问题，
  用例不改动）：

| 场景 | 失败点 | 定性 | Issue |
|---|---|---|---|
| F103-13 | LRU 淘汰步已正确（M3 forgotten），但按 scope-only 选择器 restore 被 400 ValidationError 拒绝（"RestoreSelector requires unit_ids, tags, before, or filters"——谓词漏算 scope，违反 §4.1/FR-05/SC-05） | **实现缺口**：API 层 query_ops.py:958-966 与三引擎 restore 空校验谓词漏算 `selector.scope` | draft-feat-103-restore-scope-only-selector-rejected |
| F103-07 | restore 显式指名已物理删除（PURGED）的 purgedId，返回 200 三列表全空（restored/not_found/skipped 均空），违反 AC-04 "不静默成功" | **实现缺口**：restore 候选仅从 `_list_units` 现存单元收集，PURGED 物理删除后永不入选；agent_engine.py:702-703 PURGED 分支为死代码 | draft-feat-103-restore-purged-silently-ignored |
| F103-10 | 通过 | 本轮前置真实达成（purgedId 已物理删除，F103-06 绿），历史检索不含 M1 断言在可观察面上有效覆盖 | — |

- 过程新发现（非本特性阻断项，已记录）：CLI HTTP 客户端（jiuwen_memory_entry/cli/client.py
  HttpClient._read_json）对 dev-auth 服务端的 JSON 响应报 "server returned invalid JSON"，
  CLI 通道无法到达服务端语义层；两 issue 的交叉通道验证改以进程内 SDK（InProcessClient→
  invoke_api）通道完成，结果与 HTTP wire 一致。
- 结论：软删除与保留期、容量与 LRU 淘汰两条链路的主干行为（forget 时间戳、PURGED 物理删除、
  sweep 触达、全局/空间策略、LRU 选点/批量/开关/headroom、restore unit_ids/tags/before/
  after/filters/limit、space_usage 扩展、审计留痕）均已实现且五轮稳定。剩余 2 项失败同属
  restore 恢复语义族（scope-only 选择器被拒、PURGED 静默忽略），根因集中、修复面明确。
  特性验收结论：**不通过**，待 2 项新 issue 修复后按本设计回归。

## 附录：执行记录（2026-11-11 回归，第六轮）

- 被测制品：agent-memory 源码直接运行（commit `6e9c85b`，mem2.0 tip；较上轮基线 `ebf482b`
  新增 2 个提交：restore 链路与生命周期清扫治理完善 `2464948`（issue #12/#13，对应第五轮
  2 项 issue）、合并 `6e9c85b`）。执行前以 editable 方式从最新源码重新打包部署
  （pip install -e . --no-deps --no-build-isolation，JiuwenMemory 0.2.0），SUT 冒烟
  （/healthz 200，offline profile）后执行。offline profile + 引擎配置层
  `memory_api.engine.default.target=agent` + dev auth（root-key→root），与前五轮一致。
- 执行命令：`./mvnw test -Dtest.env=openjiuwen -Dgroups=feat-103 -Dmaven.test.failure.ignore=true`
- 执行结果：全量 **27 run / 27 PASS / 0 FAIL / 0 SKIP**（主套件
  `Feat103MemoryLifecycleCapacityBlackboxTest` 16/16；补充套件
  `Feat103LifecycleCapacityGapBlackboxTest` 11/11），运行耗时 12.0s。surefire 报告：
  `target/surefire-reports/TEST-*.Feat103*.xml`（2026-11-11 16:25）。
- 第五轮 2 项开放 issue 修复验证：**全部通过**——
  - F103-13：按 scope-only 选择器 restore 不再被 400 ValidationError 拒绝，restored_ids
    正确回收（scope 纳入选择器谓词）；
  - F103-07：restore 显式指名已物理删除（PURGED）的 purgedId 返回 not_found_ids==[id]，
    不再静默成功（AC-04 "不静默成功"达成）。
- 历史回归核对：前五轮 7 项代码 issue（restore 端点缺失、sweep 触达缺失、保留期策略键缺失、
  space_usage 未扩展、forgotten_at 保留键未保护、全局策略热调不可达清扫、LRU 访问时间新鲜度、
  headroom 折算守卫）与既有通过项均无回归，F103-01~27 全绿。
- 失败用例：无。本轮无新增 issue（issue_dir 无未闭环 draft）。
- 过程说明：f10317 用例 surefire XML 中带 system-err 输出（SUT 进程内日志噪声），测试状态
  为 PASS，不影响结论。
- 结论：FEAT-103 两项子特性（软删除与保留期、容量与 LRU 淘汰）全量场景在真实 SUT 上
  六轮回归全部通过，历史 issue 全部闭环。特性验收结论：**通过**。
