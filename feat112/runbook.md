# FEAT-112 测试执行说明

2026-11-12 起的执行机制。构建与完整命令见 [Linux 部署与执行指南](linux-deploy-guide.md) 第 2–4 节；结构与判定机制见 [cases](../docs/cases/FEAT-112-task-degradation-gate-deepagent.md) §1.2、§5、§6。

## 用例怎么拿到被测物

- 载体坐标只在 `src/test/resources/application-openjiuwen.yml` 的 `sut.agents.feat112` / `feat112-peer` 声明；`sut.m2.repo`（运行脚本传 `-Dsut-m2-repo=$M2`）指向供给后的 Maven 仓库。
- 每个用例类声明部署形态，测试自己拉起 demo stub 进程与托管 DA，并在每条结果里记录载体路径、sha256 与构建来源（`-Dfeat112-source-revision`）。
- 不再有 `feat112.sut-url`、`feat112.stub-url`、`feat112.correlated-port`、`feat112.default-properties`、`feat112.executable-jar` 等系统属性，也不再读 `FEAT112_ENGINE_SECRET`：认证形态的密钥由测试每次生成。`Feat112ArchitectureTest` 会拦下重新引入这类外部假定的代码。

## 运行

```bash
M2=<供给后的 Maven 仓库> feat112/tools/run-feat112.sh compile
```

```bash
RUN_ID=<名称> SOURCE_REVISION=<载体构建来源> M2=<同上> feat112/tools/run-feat112.sh run target/feat112-run/<结果文件>.txt <类或类#方法>...
```

```bash
M2=<同上> feat112/tools/run-feat112.sh report target/acceptance-results/<RUN_ID>
```

选单个方法时要写出参数类型（用例方法都接收 `Scenario`），例如 `com.huawei.ascend.sit.cases.integration.task_degradation_gate.Feat112InstancesTest#restartDiscardsLiveProbe(com.huawei.ascend.sit.judge.Scenario)`。复跑用新的 `RUN_ID`，保留首轮失败记录。

离线部分（不启动 DA）：`ScenarioVerdictTest`、`EvidenceChecksTest`、`Feat112ArchitectureTest`、`judge/*Test`、`drive/*Test`、`deploy/*Test`。2026-11-17 起只做 SIT：组件级用例与 `feat112-product` profile 已撤掉，公开面观测不到的要求按“日志 → 持久化数据 → 特性不可观测 Issue”处理（见 cases §1.2）。

## 读结果

`target/acceptance-results/<RUN_ID>/summary.md` 给出 65 个场景逐个的结论。FAIL 的原因码：`must_violation` 产品违反特性档；`conditional_not_triggered` 前置或激活未成立，该次结论无效；`infra_failure` 环境或执行中断。只有 `must_violation` 能作为产品缺陷提交。
