# FEAT-112 本地文档入口

## Testplan 同步副本

[FEAT-112-task-degradation-gate-testplan.md](FEAT-112-task-degradation-gate-testplan.md) 保存 Technical-AF/docs 对应源分支的 testplan 原文镜像，与下列远端 commit 中的文件逐字节一致，不在此副本独立修改需求。

- PR：https://gitcode.com/Technical-AF/docs/pull/483
- 仓库：Technical-AF/docs
- 源分支：`feat/FEAT-112-task-degradation-gate-testplan`
- 仓库内路径：`develop/04-testplan/FEAT-112-task-degradation-gate-testplan.md`
- 最近同步日期：2026-11-11
- 已推送并回读的源分支 commit：`a1db9d866c1531cbedd3b3c4c144030f8f92f700`（上一基点 `5a5ff06`）
- 文件 SHA-256：`ab6290403c3f52444500324168ec76cb693134f481738e2d6d161385f1952e40`

2026-11-11 已将 C2/E1.1 公开响应边界与 B7/E9 正式过期豁免的修订推送到 PR 483，并在评论区回复第二轮评审（评论 ID `200011007`）。已通过 GitCode API 确认 PR 498 合入，设计提交为 `05af9cc3796ac953aee157e9c0b35621552232f4`。

固定设计快照：[特性档](baseline-05af9cc3/feature.md)、[L2设计](baseline-05af9cc3/design-L2.md)。二者从该提交原样提取，分别对应 docs 的 `develop/02-features/FEAT-112-task-degradation-gate.md` 和 `develop/03-architecture/L2-Low-Level-Design/deepanalyze/FEAT-112-task-degradation-gate.md`；只作版本证据，不独立编辑。B7/E9契约分歧已关闭，测试实现/执行缺口未自动关闭。

**PR 483 已合入（2026-11-12 核对）**：docs main 上的本文件等于 `a1db9d86`，即本副本。

**待提交修订（2026-11-12）**：按“特性档是唯一预期来源”复查 testplan 后的修订（G5a–G5c、E4/E5、F1–F3 改为对照判法、A10/F-reg 级别、C7、J3、B5、`required_capability`、Fixture 与 frontmatter 等）未提交、未推送，需从 main 新开分支另提 PR。逐项清单、补丁与修订后全文见 [testplan-revision-20261112.md](testplan-revision-20261112.md)；理由另见 [cases §1.1](../docs/cases/FEAT-112-task-degradation-gate-deepagent.md)。2026-11-16 按 [逐条复核记录](testplan-item-review-20261112.md) 做了第二轮修订（§5 由 65 行增至 82 行），2026-11-17 第三轮按“只做 SIT”口径修订（§5 共 84 行），已于 2026-11-17 提交为 [docs PR 549](https://gitcode.com/Technical-AF/docs/merge_requests/549)（未合入）；测试代码按修订后全文执行（`Feat112Coverage.testplan()` 优先读取它），并以 [期望文本指纹](expectation-fingerprints.txt) 锁定已复核的版本。合入并回读后再同步本副本与上述 commit、校验值。

后续更新顺序：获取远端源分支，检查本地副本是否有独立修改，再同步原文并更新上述版本与校验值。`.src-cache/docs-repo` 仅是取源缓存；阅读入口使用本目录的文件。此约定不是后台自动同步任务。

## Cases 与样板

- [完整 cases](../docs/cases/FEAT-112-task-degradation-gate-deepagent.md)：84 个场景的设计及证据边界，第 8 节登记实现与实跑结果。
- [运行说明](runbook.md)：既有框架下的远端/托管接线、Maven选择及尚未验证的边界。
- [Linux 部署与执行指南](linux-deploy-guide.md)：从源码构建被测 DA、各部署形态启动命令、分批执行命令与本机实测结果。
- [测试代码架构评审](architecture-review-20261111.md)：对照 D1/D2 洞察的结构评审；2026-11-12 的改造结果与更正见文首。
- [执行工具](tools/)：`run-feat112.sh`（按 pom 解析 classpath、定向编译 FEAT-112 与通用结果层源码、运行并生成逐场景报告）与 `RunSelected.java`。
- [D1a 样板](cases-section-1-sample.md)：早期方法论样板，不作为独立实施状态台账。

代码覆盖情况、真实 DA 执行结果与 testplan 原文分开维护；离线自测通过不代表产品验收通过。
