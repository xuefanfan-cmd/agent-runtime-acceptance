---
feature_id: FRS-jiuwen_memory-mem2.0-001
test_type: deepagent
scope: memory20-交接验收-2026-10
deployable_units: [personal-assistant-deepagent-demo, jiuwen-memory-http-8137]
sut: A2A JSON-RPC 生活助手 DeepAgent + 纯内存记忆 HTTP 服务
status: known-gap
features: [FRS-jiuwen_memory-mem2.0-001]
updated: 2026-10-22
---

# 生活助手 DeepAgent 验收：Memory 2.0 15 条交接用例

## 1. 测试目标

通过真实 Demo 的公开 A2A 入口验证 Excel 规定的 15 条记忆场景。测试请求前准备记忆服务公开数据，请求后观察 Task/artifact 和公开 search 结果，覆盖信息提取、多会话推理、时间推理、知识更新和拒绝回答。

本轮是交接验收，未提供标准 Feature/L2 设计文档；Excel、公开 API 和部署说明是事实基线。该偏离会在附录记录，不把未列出的能力当作本轮要求。

## 2. 范围与非范围

范围：15 个独立场景 MI-01～MI-05、MC-01～MC-04、MT-01～MT-02、KU-01、MR-01～MR-03，以及 A2A/记忆 HTTP 的公开可达性。

非范围：内部类、数据库、检索 score 阈值、LLM 固定原文、未列入 Excel 的 MCP/加密/锁/多模态/生产部署。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| 交接 Excel《记忆服务场景验收用例_agent-memory2.0》 | 场景步骤和外部 Oracle |
| agent-memory mem2.0 HTTP API/部署说明 | 公开请求、响应和 8137 服务边界 |
| Agent-Memory 特性需求说明书（交接材料） | scope、隔离和双形态背景 |

## 4. 部署拓扑

```text
JUnit driver -> managed personal-assistant-deepagent-demo -> A2A /a2a
                                   -> HTTP memory service :8137
JUnit driver -> memory service public /v1/* for fixture and oracle
```

只通过 HTTP/A2A 公开面观察。`scope`、canary 和时间戳由测试 Fixture 生成，不能替代产品合同。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| MI-01 | 空记忆不召回无关信息 | G：唯一空 scope | W：写入天气并问天气、张三 | T：天气相关；无关问题不引用天气 | MemoryHttpFixture |
| MI-02 | 明确历史饮食召回 | G：写入四条饮食过程 | W：新会话问原本想吃什么 | T：返回早期计划事实 | MemoryHttpFixture |
| MI-03 | 用户隔离预检 | G：dev 认证固定 user 下使用 A/B 独立 session（公开 API 不允许测试用户直写） | W：两侧各问项目代号 | T：各自只返回自身值；结果仅证明 session 隔离，不能替代不同 user 身份隔离 | MemoryHttpFixture |
| MI-04 | 删除后不可召回 | G：写入并取得 unit id | W：delete 后 search | T：canary 不存在 | MemoryHttpFixture |
| MI-05 | 长期日期召回 | G：准备多日期事件 | W：查指定日期 | T：日期对应事实；全量与定向复测稳定失败，已确认为 product confirmed | DateFixture |
| MC-01 | 矛盾事实最新优先 | G：独立 session，依次直存上海晴天、上海下雨天，记录各自 unit ID | W：公开 search 查询上海天气，随后通过 Agent 查询当前天气结论 | T：检索首条为最新下雨记录；Agent 当前结论为下雨，不把旧晴天当作当前天气；分别保存检索和回答证据 | 冲突天气 Fixture |
| MC-02 | 单主题精准召回及污染归因 | G：独立 scope 组成行程记忆有/无 × 历史有/无四组；另有五轮 Agent 写入回放 | W：上下文组先写 seed，公开 list/purge 清除写回后重建精确 fixture；所有组只问“上海今天是什么天气？”；记录同问句 top5/top10；历史组在测量后清空记忆并回问随机口令 | T：天气候选优先、回答不混入行程；记忆完整性及历史口令为归因前置条件，不成立则 INCONCLUSIVE；五轮写入回放核对公开落库及最终回答 | 单主题对照 Fixture |
| MC-03 | 复合记忆单维度查询 | G：写入复合知识 | W：先通过公开 search 查询，再由 Agent 回答对内职责 | T：L2 search 按指导返回命中的完整原文；Agent 最终回答包含对内职责且不复述远程银行等无关维度 | MemoryHttpFixture |
| MC-04 | 补充信息优先 | G：写入评审补充信息 | W：问参加者 | T：返回主讲/评审人 | MemoryHttpFixture |
| MT-01 | 时间上下文准确 | G：跨日饮食和天气 | W：后续日期查询 | T：事件日期语义正确 | DateFixture |
| MT-02 | 指定日期过滤 | G：独立 session，写入 6 月 8 日夜宵及其他日期记录，保存 unit ID 与 occurred_at 对应关系 | W：查询“我6月8日的记忆有什么” | T：完整结果 ID 集合仅包含 6 月 8 日记录；其他日期所有记录均不得返回，不使用部分关键词排除 | 日期 Fixture |
| KU-01 | 最新知识覆盖旧值 | G：独立 session，依次写入四条饮食过程及最后“实际吃了汉堡王”的纠正，记录各条 ID | W：公开 search 查询今天吃了什么，再由 Agent 给出当前结论 | T：首条必须为最后纠正的 unit ID，不能由早期“想吃汉堡王”替代；Agent 回答实际吃了汉堡王 | 知识更新 Fixture |
| MR-01 | 敏感词拒答 | G：存在普通 canary | W：风险请求 | T：拒答且无 canary | RefusalFixture |
| MR-02 | 权限不足拒答 | G：普通用户 | W：客户数据/核心系统请求 | T：拒答且无 canary | RefusalFixture |
| MR-03 | 保护意图冲突拒答 | G：不保存对话意图 | W：查询历史 | T：拒答且无 canary | RefusalFixture |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| personal-assistant-deepagent-demo | 真实 SUT | Maven 坐标 `com.openjiuwen.example:personal-assistant-deepagent-demo:0.1.2`，公开 `/health`、`/.well-known/agent-card.json`、`/a2a` |
| MemoryHttpFixture | Fixture | Java HttpClient 调用 `/v1/add`、`/v1/search`、`/v1/delete`，不访问内部存储 |
| A2aMemoryClient | Fixture | 发送公开 JSON-RPC SendMessage，提取 Task/artifact |
| ScopeFixture | Fixture | 生成 runId、org、user、session 和唯一 canary |

## 7. 关键链路断言

- 每个矩阵行映射一个唯一 Java 方法、Allure Story、JUnit tag 和 DisplayName。
- 断言业务事实、scope 隔离、删除可见性和拒答无泄露；MC-03 分别验证 L2 完整原文合同与 Agent 单维度回答；不逐字比较 LLM 全文。
- 公开接口错误、连接失败和 LLM 超时分别归类为 FAIL、ERROR 或 INCONCLUSIVE。

## 8. 执行策略

该测试类默认关闭。只有显式指定 `-Dmemory.acceptance.enabled=true` 才执行；未指定或值不是 `true` 时，JUnit 在类级条件检查中跳过全部 15 条用例，不执行基类的 Agent 启动生命周期。SKIPPED 不表示验收通过。启用后的缺少 Demo 制品、记忆服务不可达和业务断言失败仍按原规则报错，不自动跳过。

记忆 Agent 的 Maven 坐标、端口和密钥属性名仅由本测试类的 Fixture 声明，通过现有 `TestConfig.fromMap()` / `SutStack` 接口使用；不写入公共 Agent 配置、不加入公共构建步骤，不修改公共 POM、测试基类或运行脚本。启动器继续使用所选环境的 Maven 仓、JVM 配置、日志路径及启动超时。没有新增专项配置文件。

执行前由测试人员准备：本地记忆 HTTP 服务 `http://127.0.0.1:8137`，已构建并安装到所选 Maven 仓的 `com.openjiuwen.example:personal-assistant-deepagent-demo:0.1.2`，以及运行时环境变量 `LLM_API_KEY`、`LLM_API_BASE`、`LLM_MODEL`、`LLM_PROVIDER`、`LLM_SSL_VERIFY`。Demo 按其源码 POM 的匹配依赖构建，不通过公共构建参数强制改版本；历史本地源码制品不能视为正式发布版本的兼容性证据。记忆服务需单独启动和停止，本测试管理 Demo 进程。

依赖准备完成后，在 WSL 仓库根目录使用现有入口显式执行：

```bash
./scripts/run-pipeline.sh --env openjiuwen --skip-provision -- \
  -Dtest=Memory20DeepAgentAcceptanceTest \
  -Dmemory.acceptance.enabled=true
```

若使用隔离 Maven 仓，需让 `SUT_M2_REPO` 与 `-Dmaven.repo.local` 指向同一目录。通过现有 skill runner 执行时，可在该次调用的进程环境中用 `JAVA_TOOL_OPTIONS=-Dmemory.acceptance.enabled=true` 传递开关，并通过 `WSLENV` 转发；结束后恢复原环境。不要将开关写入公共配置或持久化到用户全局环境。

2026-10-22 接入隔离验证：未覆盖仓库 POM 的依赖版本，Windows 与 WSL 均完成现有 351 个测试源文件编译。未设置启用开关的精确执行为 15 SKIPPED、0 FAIL、0 ERROR，没有生成 SUT 运行日志；这是默认关闭验证，不是 15 条业务用例通过。现有 skill runner 将全跳过判为非通过，属于预期统计规则。显式启用并指定不存在的 SUT 制品目录后，单方法选择在原启动生命周期中报 `SUT jar not found`，结果为 1 ERROR、0 SKIPPED，确认缺依赖没有被隐藏为跳过。上述两轮未启动 Demo、未调用 LLM 或记忆业务接口，没有重跑业务验收。公共构建和 Agent 配置均已恢复原状；新增测试仅使用现有编译依赖，没有新增 POM 依赖。

2026-10-20 四条修订后的有效结果：

| 场景 | 结果与有效次数 | 自动化状态 / 诊断 |
|---|---|---|
| MC-01 | 3/3 FAIL：旧晴天始终排新下雨之前 | known-gap / product confirmed；Agent 回答另有波动 |
| MC-02 | 2026-10-20 三次诊断通过，但四组 top5 均未召回行程 | 仅证明原输入下未跑题；2026-10-21 已识别归因前置不足 |
| MT-02 | 3/3 FAIL：返回 5 条，包含 4 条其他日期记录 | known-gap / product confirmed；并入原日期问题 |
| KU-01 | 5 次中 4 FAIL、1 PASS；检索首位 5/5 正确 | implemented-unverified / nondeterministic；不可宣称通过或稳定检索缺陷 |

三次主要有效运行编号为 20261020-113357、20261020-113953、20261020-114514；KU-01 追加 20261020-114839、20261020-115335 后停止，五次有效运行是本轮波动确认上限。20261020-114215 因错误运行凭据导致 LLM HTTP 401，整轮排除有效计数。原始 FAIL 保留，不改写成 PASS。MC-02 后续扩展为四组对照并三次通过；这些结果不能否定其他输入下的主题污染。

本轮只补测这四条，其余 11 条复用历史证据；不将 2026-10-17 的 14 PASS / 1 FAIL 作为修订后的全量结论。offline 纯内存检索仍只是该配置的预检，不替代生产语义检索；MI-03 仅验证 session 隔离，MT-01 未完整模拟跨日自然语言推理，拒答结果不能单独证明记忆服务内部权限策略。Fixture 通过公开 REST 直接准备记忆，不证明用户对话自动提取/写入链路。

四个方法在原类原地更新：`latestConflictingFactWins`、`singleTopicRecall`、`dateFilterExcludesOtherDays`、`knowledgeUpdateUsesLatestValue`。MC-02 最终记录长期记忆有/无行程 × 历史有/无行程四组结果；上下文 seed 自动写回后通过公开 list/delete 清理，再写入相同混合 fixture，避免把长期记忆差异与会话历史差异混为一个变量。fixture ID、完整 search 响应与 Agent 最终回答保存到 Allure 附件及运行日志。

编译预检成功，9 步 SUT 构建成功；测试通过 skill 的 run-wsl-tests.ps1 在 WSL 精确执行。前三次有效运行确认稳定问题，其后仅 KU-01 追加两次检查波动，30 分钟运行预算内结束。MC-02 通过后没有重复运行。结果依据 Surefire XML，不以 Maven BUILD SUCCESS 代替测试结果；Allure CLI 未安装，保留 JSON 及附件，无 HTML 报告。

执行现场：隔离 Maven 仓 `target/memory20-maven-20261020`；输入快照 `target/feature-acceptance-testing/memory20-four-inputs.json`；逐轮证据和分类汇总 `target/feature-acceptance-testing/memory20-four-results.json`。LLM 参数仅运行时环境注入。每轮 managed SUT 正常 teardown，本轮结束关闭自建 8137 服务。正式问题报告写入 skill 配置的当月固定目录；非确定性问题仅保留本执行记录和证据。


2026-10-20 下午 MC-02 归因补测：初版三组探索运行（20261020-164818、20261020-165801）均未输出行程，但未验证历史实际保留，且诊断 search 与 Agent 问句标点不同，因此仅作为探索证据，不能据此作因果结论。最终诊断改为四组：M0H0 仅天气/新会话，M1H0 混合记忆/新会话，M0H1 仅天气/历史 seed，M1H1 混合记忆/历史 seed。各组独立 scope；history seed 不给天气问题增加答案提示。清理只作用于本次测试创建的 scope 和公开 list 返回的 ID，完整核验数量及集合；本轮不修改产品源码。

最终天气问句保持“上海今天是什么天气？”，诊断记录同问句的 top5（对齐 prefetch）和 top10。长尾存在本身不判错，核心断言是无关行程不得进入最终回答；晴雨最新事实正确性另属 MC-01，当前诊断 PASS 不表示天气结论正确。历史组在天气回答后清空长期记忆，再询问未出现在问题中的随机口令，证明历史可观察；若失败，归因为诊断前置条件不足，报告 INCONCLUSIVE，不直接报 Bug 3。另经 Agent 连续写入 Excel 的五条记忆并查询，核对落库；同事未提供逐轮原始输入，不能声称完全复刻其输入。

两轮探索之后最多三次最终版本运行、合计不超过 30 分钟；若首次发现历史前置条件不成立，停止相同现场重复运行并记录限制。只有新的稳定产品疑似失败才进入三次确认；不把所有无污染运行当作证明不存在 Bug。若污染偶现，记录发生次数和对应组。自动化状态：最终诊断 verified；三次独立运行通过，Bug 3 在当前 DeepAgent 制品下未复现。

2026-10-21 ReactAgent / DeepAgent 对比诊断：交接方现已提供逐轮原文。固定相同依赖制品、记忆服务、模型与六轮输入，先保留原始断言执行 ReactAgent，再把同一用例切换至 DeepAgent；必要时仅改变 A2A 流式/非流式。记录模型公开 HTTP 请求体及响应（不保存授权头），核对真实模型、参数、system prompt、历史消息与 memory-context。模型请求记录器只透明转发，不生成回答、不修改请求。对已捕获最终问句做历史行程/长期记忆行程四组消融，各组独立回放至少五次；响应有效性、输入分组完整性不成立则归因 INCONCLUSIVE。低分结果返回、实际注入与最终跑题分别报告；只有稳定产品疑似失败才进入三次确认。其他未改变条件的既有结果复用。

2026-10-21 历史证据复核更正：四组诊断中 M1H0/M1H1 虽然存入行程，但最终同问句 top5 均只返回两条天气，行程没有进入候选；因此四组 PASS 对“无关记忆实际注入后是否污染回答”的归因是 INCONCLUSIVE。五轮 Agent 回放的 top10 则确有两条行程，须单独保留其未复现结论。前述“verified”仅指既定断言执行通过，不代表完成因果归因。今后的记忆因素对照必须记录实际模型输入并断言分组成立，不能用库存有行程代替注入有行程。

2026-10-21 相同交接原文对照：历史 Deep 流式和当前 Deep 流式各一次端到端 PASS；二者各四组模型请求回放、每组十次均未出现行程。实际业务 system prompt 与 React 相同，模型均为 deepseek-v4-pro；top_p 分别为 Deep 0.8、React 0.1。历史 Deep 流式只写回用户文本，当前 Deep 与 React 会写回助手文本；旧非流式 Deep 同样写回助手，不应把流式现象泛化。旧结果只适用于当时输入和制品。

## 附录 A. 差异、门禁与待澄清项

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| 无传统 Feature/L2 | 需求追溯只能使用交接材料 | 已接受的交接偏离 | 补发正式 Feature/L2 后重新比对 |
| 纯内存服务 | 重启后历史消失 | 本轮允许 | 生产验收改用持久化部署 |
| Windows JDK loopback 异常 | Windows 直接启动不可作为运行证据 | WSL 启动路径可用 | 修复运行环境后复测 |
| MI-05 日期检索 | `occurred_at` 已写入但按日期查询未召回目标事实；全量执行及两次定向复测稳定复现 | product confirmed | 记忆服务补齐自然语言日期与正确时间轴筛选；本轮不通过 Agent 补偿改变验收边界 |
| MC-03 复合维度过滤 | 指导文档要求 L2 返回完整原文；原测试错误地要求 search 裁剪无关维度。现已分别检查 search 与 Agent 回答并通过 | resolved test-oracle correction | 保持分层断言 |

