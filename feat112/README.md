# FEAT-112 测试资产入口

本目录只保留测试代码运行与验收所依赖的文件；其余工作文档（评审记录、issue 草稿、运行指南等）一律本地留档，不入仓。

## Testplan 同步副本

[FEAT-112-task-degradation-gate-testplan.md](FEAT-112-task-degradation-gate-testplan.md) 保存 Technical-AF/docs 对应源分支的 testplan 原文镜像，不在此副本独立修改需求。

- 仓库内路径：`develop/04-testplan/FEAT-112-task-degradation-gate-testplan.md`
- 已推送并回读的源分支 commit：`a1db9d866c1531cbedd3b3c4c144030f8f92f700`
- 文件 SHA-256：`ab6290403c3f52444500324168ec76cb693134f481738e2d6d161385f1952e40`
- 后续更新顺序：获取远端源分支，检查本地副本是否有独立修改，再同步原文并更新上述 commit 与校验值。

[testplan-revision-20261112.revised.md](testplan-revision-20261112.revised.md) 为按"特性档是唯一预期来源"复核后的修订全文（三轮修订，§5 共 84 行，已提交 docs PR 549 待合入）；测试代码按它执行（`Feat112Coverage.testplan()` 优先读取），并以 [expectation-fingerprints.txt](expectation-fingerprints.txt) 锁定已复核版本。PR 549 合入并回读后同步本副本。

## 设计基线快照

[baseline-05af9cc3/](baseline-05af9cc3/)：特性档与 DA L2 从 docs 提交 `05af9cc3` 原样提取，只作版本证据，不独立编辑。`Feat112ArchitectureTest` 校验 `feature.md` 在位。

## 执行工具

[tools/](tools/)：`run-feat112.sh`（解析 classpath、定向编译、运行并生成逐场景报告，含 `fingerprints` 子命令）、`build-carrier.sh`（按 core develop + solution common 最新源码构建被测载体并记录双方 commit）、`RunSelected.java`。

## Cases

完整 cases 见 [docs/cases/FEAT-112-task-degradation-gate-deepagent.md](../docs/cases/FEAT-112-task-degradation-gate-deepagent.md)（84 个场景的设计及证据边界，第 8 节登记实现与实跑结果）。离线自测通过不代表产品验收通过。
