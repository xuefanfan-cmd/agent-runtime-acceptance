针对 r3（头提交 `88266eff`）的复核。前两条评论的意见大部分已落实（判定态对齐、原因码表、事实来源分类与版本锚、观测顺序、编号规则含两处更正），谢谢。还剩 8 处，其中第 2、7 条是更正我前一条评论（201285073）自己的说法，以本条为准。

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| 1 | PR 说明 | 与正文不一致：第二组仍写“L2 与代码只作定位观测点”（正文 §5.1 已把 L2 列为期望来源）；第五组仍是旧正则 `(\.\d+)?(-…)?` 和“存量 ID 不改名”；行数写 351，实际 355 | 按最终正文更新 |
| 2 | §5.1 L2 定位 | 我前一条写“L2 与代码不作期望来源”，说得过头：L2 规定的字段、事件名、错误码本来就是判据要用的。保留“期望来源（协同）”，但需要写明边界 | 在“冲突处置”下补一条：L2 只给出特性档已规定行为的观测形式（字段、事件名、错误码），不能增加或放宽行为；特性档没写、只有 L2 或实现给出的行为，记“待特性设计确认”，不作期望 |
| 3 | §5.1 双向一致性例外 | “允许写 `testplan 未合入 / PR!NNN pending`”与同节“禁止写入 PR / Issue 链接”矛盾 | 例外只写“testplan 未合入”，不带 PR 号 |
| 4 | §5.3 原因码 | 表头写“D1 §9b”，但名称和归属与 D1 不一致：`observable_not_sampled` 即 D1 的 `trace_missing`；“概率事件未激活”在 D1 归 FAIL·`conditional_not_triggered`（模板 FAIL 下也已有此码，等于同一情况挂两个态），“LLM 抖动”归 FAIL·`model_variance`；FAIL 下缺 `model_variance`、`other_untestable` | `observable_not_sampled` 改为 `trace_missing`；删除 `probabilistic_event_not_activated`；FAIL 补 `model_variance`、`other_untestable`（后者必须带 issue 编号），FAIL 行“覆盖”说明同步；NOT_OBSERVED 的典型例改为“取证链路未打通、采集超时”。不要加 `protocol_unobservable`：D1 已修订为 v4.1，撤销了该码和“转组件级验收层”，与本模板 §5.2 一致。文末参考版本相应改为 D1 v4.1 |
| 5 | §5.2 兜底原则 | 写了“提可观测 issue、不写 NOT_OBSERVED 兜底”，但没说这种场景在报告里显示成什么 | 补一句：三面都观测不到不是判定态，报告显示为覆盖缺口（未承接）并关联 issue，该 MUST 阻塞放行 |
| 6 | §5.4 示例 | `FEAT-112-A5.1`、`A5-stream`、`A5-open`、`A5-stream.1`：A5.1 已在 PR 549 按本规则撤销（“撤销的 ID 永不复用”），A5 是非法阈值场景，没有 stream/open 变体 | 改用占位，如 `FEAT-NNN-B1.1`、`FEAT-NNN-D1-stream`、`FEAT-NNN-D1-stream.1` |
| 7 | §5.4 类型标签 | 我前一条写“放 level 或单独的类型列”，“放 level 列”是错的：level 承载 MUST/SHOULD/MAY/OUT，并决定 WARN 与 EXCLUDED，混入类型会破坏这层含义 | 改为“写在场景描述里（或单独的类型列）”，不放 level 列。PR 549 写在场景描述里，如 F5“冒烟保护：…” |
| 8 | §7 review 路径 | “按 SIT 仓 agent-runtime-acceptance 的 `docs/review/` 惯例”：该仓没有 `docs/review/` | 去掉“惯例”说法，只保留“路径由实施仓自定”与占位 `{review-dir}` |

PR 549（FEAT-112 testplan）已按 r3 对齐：来源写“路径 @ commit 链接”、被依赖 testplan 逐项标注、已撤销 ID 表四列、行内不写 PR 号、附录 A–C 按骨架补齐；原因码按上面第 4 条使用 D1 v4.1 名称。
