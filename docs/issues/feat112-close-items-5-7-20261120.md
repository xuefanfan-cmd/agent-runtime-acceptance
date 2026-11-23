# FEAT-112 第 5、7 项关闭记录（2026-11-20）

按开发核查答复 `feat112-eight-issue-review-20261120.md` §3.5/§3.7 关闭，测试侧独立核实后确认。两份原稿保留，内容以本记录为准。

## 第 5 项：取消窗口不可观测及落定语义（原稿 `draft-feat112-cancel-window-observability-20261120.md`）——关闭

- 原稿性质是“不可观测 + 请裁决‘实际落定’语义”，不是缺陷断言；开发的答复即裁决答案：**“实际落定”= 控制面终态事实链落定**。实现顺序 `landTerminalPair`（markFinished）→ 发终态事实 → 门收 `USER_CANCELLED` 才释放名额，不存在“先释放、后落定”的路径——与测试侧对 `TerminalClosure.java` 的走读一致。
- 开发仓有 `DegradationGateTest#probeCancellation_releasesSlotOnlyAtSettlement`（`DegradationGateTest.java:330-345`）锁定该顺序，测试侧已核实该用例存在。按本仓口径，开发仓单测仅供参考、不计入 SIT 验收；SIT 维持调用级粒度判定（E7：落定后释放、保持 HALF_OPEN、下一任务可成为探针——PASS）。
- 处置：不修改生产代码，不新增落定延迟点；E7 覆盖登记的“部分承接”说明改为“瞬间窗口的语义经 L2/开发确认为控制面落定，SIT 以调用级粒度验收”；原“已提/待提特性不可观测 Issue”的指向撤销。
- 遗留一个小核实项：开发称“现有 L2 已把实际落定锚在终态事实链”引自 PR 538 版 L2，待 PR 538 合入后在合入版 L2 上确认一次措辞（不阻塞关闭）。

## 第 7 项：core topP 可空 NPE（原稿 `draft-feat112-core-topp-npe-20261120.md`）——关闭（历史 solution 兼容性缺陷，指定基线已修复）

- 测试侧三线核实：
  1. 静态：solution `96c23d4ea` 的 `DaWorkflowRunner` 已为 `Double topPFallback`，`samplingDouble` fallback 形参为 `Object` 不拆箱，`topP != null` 才设置 builder；修复提交 `6502e58d6`、`5abda1013`、`1728a2f8d` 均为 `96c23d4ea` 祖先。
  2. 动态（Linux `linux-rest-1`，2026-11-20）：E16 证据 `FEAT-112-E16-734993d7-…/002.json`——`workflow_complete status="completed"、resultCount=1`，成员 m1 `skipped=false` 有输出，SSE 全文无 NPE 无 error；替身统计 5/5 完成、0 错误。与 2026-11-16 旧载体证据（`status="failed"、resultCount=0`、NPE 原文）成对照。
  3. E16 本轮失败详情为“激活未建立（stub 服务计数不符）”，与 NPE 无关，系测试侧待办（见下）。
- 历史定性：core `e24bcb74d` 的可空化属有意设计（有单测锁定），solution 消费端未适配是历史兼容性缺陷，已在 `1728a2f8d`（align sampling fallback）等提交修复。不再对 core 提单；“破坏性契约变更未在版本号体现”一点留作版本治理观察，不单独成单。
- 派生测试侧待办：E16 激活逻辑按新载体成员调用模式重写（成员现在直接拿到替身普通应答，没进入脚本化护栏拒绝序列）；cases §8.0 与覆盖登记中 E16 的原因由“core topP NPE”更正为“成员已可运行，激活败在替身剧本未命中成员请求”。
