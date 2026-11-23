# FEAT-112 对外问题提交索引（2026-11-20，r2——已并入开发核查答复）

> **已正式提单（2026-11-20）**：4 真 Issue + 1 增强已建到 openJiuwen/agent-solution：#852（匿名诊断面）、#853（F4 租户校验）、#854（prepare_failed 分类）、#855（locale 启动）、#856（可观测性增强）。core 不提（7 已修复关闭），5 已关闭。


开发已核查八项草稿并回 `feat112-eight-issue-review-20261120.md`；测试侧逐条代码级复核后采纳，本索引为复核后状态。每份 Issue 按 `docs/review-checklist.md` 第 3 章写成完整草稿（结论摘要 / 场景 / 影响面 / 实测证据 / 代码层定位 / 判据 / Pre-flight 三问 / 历史关系 / 复现条件 / 预答反问 / 修复与验收转绿）。

载体基线：core `origin/develop@f777aa244` + solution `origin/common@96c23d4ea`（sha256 `cdb5c93e…`）；特性档 `05af9cc3`。证据在 `evidence/`（完整结果包 `results-all-20261120.tar.gz` + 抽出的关键交换与三轮 summary）。

## 处置总览（4 真 Issue + 1 增强 + 2 关闭）

| 原# | 主题 | 开发判定 | 测试侧复核 | 当前文件 |
|---|---|---|---|---|
| 1 | 默认配置下诊断面匿名可读（G5-missing, must_violation） | 真 Issue | 采纳（类名改 `DaHealthDetailController`，放行表述分离） | 匿名诊断 r2 定稿（本地留档） |
| 2 | 保留租户校验被降级门盖过（F4, must_violation） | 真 Issue（影响面夸大） | 采纳（收窄到门后校验；HALF_OPEN 占探针经开发证实） | 保留租户 r2 定稿（本地留档） |
| 6 | `prepare_failed` 分类归 NOT_ADMITTED | 真 Issue（需拆分） | 采纳（A 实现差距无需裁决 + B 测试承接三选一） | prepare_failed r2 定稿（本地留档） |
| 8 | POSIX locale 下中文技能目录启动失败 | 真 Issue（环境/修法需修正） | 采纳（触发条件精确到 `sun.jnu.encoding`；撤回不可落地修法） | locale 启动 r2 定稿（本地留档） |
| 3+4 | 门事件日志 / 代际值不可观测 | 增强建议、非缺陷 | 采纳（撤回“不可观测/MUST 阻塞”，合并为增强） | 可观测性增强合并稿（本地留档） |
| 5 | 取消窗口不可观测 + 落定语义 | 误报（有单测锁定顺序） | 接受关闭（裁决答案：落定=控制面终态事实链；E7 调用级粒度） | 关闭记录（本地留档） |
| 7 | core topP 可空 NPE | 误报（指定基线已修复） | 确认关闭（静态+动态双证；系我方漏查 solution 消费端整改） | 关闭记录（本地留档） |

验收转绿（真 Issue 修复后测试仓对应用例）：1 → `Feat112DiagnosticsTest#anonymousDiagnosticRejectedInDeliveredConfiguration`；2 → `Feat112DataPlaneTest#reservedTenantPrecedesGate`；6 → `prepare_failed` 段故障计入窗口（承接方式定后补场景）；8 → `LC_ALL=C` 下可启动或明确报错。

## 备注

- r2 草稿中的措辞修正逐条标注“按开发核查答复 §x.y”，便于与 `feat112-eight-issue-review-20261120.md` 对读。
- 原 r1 草稿（含 3/4/5/7，四份，本地留档）与早期简稿 `issues-unobservable-20261117.md`、`issue-core-topp-npe-20261116.md` 保留作底稿。内容以 r2 / 增强稿 / 关闭记录为准。
- 第 7 项关闭的动态证据：E16 在修复后载体上重跑——成员正常运行、workflow completed、无 NPE（本目录 `evidence/results-all-20261120.tar.gz` 内 `FEAT-112-E16-734993d7-…/002.json`；测试仓本地 `e16-fix-20261120` 复跑 PASS）。
- 此前另有对 PR 463 分类规格与特性档/DA L2 的差异对账评审（17 条，本地留档），与本次 Issue 草稿相互独立。
