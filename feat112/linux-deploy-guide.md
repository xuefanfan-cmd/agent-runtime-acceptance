# FEAT-112 Linux 部署与执行指南

> **2026-11-18**：在服务器上实跑请直接按 [server-run-guide-20261118.md](server-run-guide-20261118.md)（打包、解包、编译、执行、判读、回传）。本文第 4.3 节命令与第 6 节传输清单仍是组件级撤掉之前的写法，以新指南为准。

本指南的每一步都在 2026-11-11 的 Windows 本机上验证过（构建、启动 stub 与 DA、执行用例），只是本机内存（7GB）不足以同时运行多个 DA 实例。Linux 上按同样步骤执行即可。各步骤的本机实测结果见文末。

## 0. 先决条件

| 项目 | 要求 | 说明 |
|---|---|---|
| JDK | 21（solution 以 `--release 17` 编译，测试仓用 21） | 本机为 Temurin 21.0.12 |
| Maven | 3.9.x；能访问 Maven Central | 测试仓 `./mvnw` 自带 3.9.16；solution 无 wrapper，需本机 `mvn` |
| 内存 | 建议 ≥16GB | 每个 DA 实例约 1–1.5GB；完整执行需同时运行 2–3 个实例 |
| Docker | `Feat112DataPlaneTest`（F3 team 不存在、F4、E11）与 `Feat112CheckpointStoreTest`（E5.3）需要 | 测试经 Testcontainers 自己拉起 PostgreSQL（`sut.services.postgres.image`，默认 postgres:16）与 Redis（`sut.services.redis.image`，默认 redis:7-alpine），不需要预先部署；无 Docker 时这些场景记 infra_failure |
| libfaketime | `Feat112ClockTest`（B6、B7、E9）需要，只能在 Linux 上用 | Debian/Ubuntu：`apt-get install faketime`，库默认在 `/usr/lib/x86_64-linux-gnu/faketime/libfaketime.so.1`，其他路径用 `sut.infra.libfaketime` 指定；缺失时记 infra_failure |
| 端口 | 28120–28150 | 见第 3 节 |

**被测制品必须从源码构建。** Maven Central 已发布的 `0.1.3`（2026-11-03）**不含降级门**：扩展包与 DA 引擎里都没有 `DegradationGate`、`DaProbeSlotHandle` 等类。用它运行，所有用例都会失败或在产品类加载时报 `infra_failure`。

## 1. 获取源码与本轮未提交文件

1. solution 仓：`agent-solution` 的 `common` 分支。本机验证使用的提交是 `0f5d17265`（2026-11-09）。
2. 本机 solution 工作区有他人未提交的改动（例如删除 `smartresearch-alpha` 模块、改动 `common/agents/pom.xml`），**不要带到 Linux**，使用干净 checkout。
3. FEAT-112 demo 目录 `common/example/task-degradation-gate-acceptance-demo/` 以及测试仓的 FEAT-112 代码目前都**未提交**，需要用本机打好的传输包 `feat112-transfer-20261111.tar.gz` 覆盖到对应仓库（包内文件清单见第 6 节）。

## 2. 构建被测 DA

**2026-11-13 起的做法**：用 `feat112/tools/build-carrier.sh` 一步完成。core 取 agent-core-java `origin/develop` 最新，solution 取本机 common 分支 HEAD，两者都从干净 worktree 构建（不带工作区里他人未提交的改动），并拷入未纳入版本控制的 demo 目录。脚本会校验载体内打包的 core 就是这次 develop 构建出的 jar，最后写出 `$M2/feat112-build-manifest.json`，记录两边 commit、demo 内容哈希、载体与扩展的 sha256。运行脚本发现清单后，会把来源写进每条结果；载体与清单不符时拒绝执行。下面是该脚本封装的原始步骤，供 Linux 上手工执行参考。

以下命令中 `M2` 为独立 Maven 仓库目录，避免与本机缓存的同号发布版 `0.1.3` 混淆。

```bash
export M2=$HOME/m2-feat112
```

在 solution 仓根目录（含根 `pom.xml` 的目录）只构建 DA 引擎及其依赖链。必须显式选 `mechanism,business` profile，否则默认 profile 会聚合全部模块：

```bash
mvn -B -ntp -Dmaven.repo.local=$M2 -P mechanism,business -pl common/agents/deepanalyze-java/engine -am -DskipTests install
```

本机耗时约 8.5 分钟，构建 13 个模块。构建 demo，产出可执行包 `degradation-agent-runtime/target/degradation-agent-runtime-0.1.0.jar`（约 140MB，内含 stub）：

```bash
cd common/example/task-degradation-gate-acceptance-demo && mvn -B -ntp -Dmaven.repo.local=$M2 -DskipTests install
```

确认构建产物确实包含降级门：

```bash
unzip -l $M2/com/openjiuwen/agent-service-adapters-agentcore-ext/0.1.3/agent-service-adapters-agentcore-ext-0.1.3.jar | grep DegradationGate.class
```

测试仓整体编译还依赖 FEAT-032 的 `multi-modal-tools-demo:0.1.3`。它的 POM 设置了 `maven.install.skip=true`，需要先构建、再手工登记到 `$M2`：

```bash
cd common/example/multi-modal-tools-demo && mvn -B -ntp -Dmaven.repo.local=$M2 -DskipTests package && mvn -B -ntp -Dmaven.repo.local=$M2 install:install-file -Dfile=target/multi-modal-tools-demo-0.1.3.jar -DpomFile=pom.xml
```

## 3. 部署形态由用例自己启动

2026-11-12 起，所有 DA 用例都由测试自己托管启动载体，**不需要也不应该预先手工部署 DA 或 stub**：

- 载体是第 2 节 install 到 `$M2` 的 `com.openjiuwen.example:degradation-agent-runtime:0.1.0`，坐标声明在 `src/test/resources/application-openjiuwen.yml` 的 `sut.agents.feat112`（多实例场景另用同坐标的 `feat112-peer`）。也可以用仓库供给链 `scripts/sut/provision-sut.sh openjiuwen` 构建，`sut-sources.yml` 已加 FEAT-112 步骤（需 agent-solution 源含 FEAT-112 改动）。
- 每个用例类声明一种部署形态（`DeploymentForm`）：先从同一载体包拉起 demo 的 `LlmFaultInjectionStub` 进程（或测试内的关联模型替身），再经 `SutStack` 托管启动 DA 并把模型地址接到该替身，等诊断面就绪后才开始场景。窗口、阈值、静默期、认证、禁用、媒体目录都由用例注入；结论依赖这些参数、重启或两进程的场景（A7–A9、B1–B5、C5/C6/F-reg、H、I）每个场景单独启动部署。
- 不支持远端模式：若为 `feat112` 配置了 `url`，部署直接报错。
- 本机 7GB 内存同时运行两个 DA 偏紧，H1–H3 在 Linux 上更稳。
- 2026-11-17 起部署形态可声明测试托管环境（`DeploymentForm.withRedisCheckpointer()` / `withPostgresDataPlane()` / `withClockControl()`）：Redis、PostgreSQL 由测试启动容器并注入 DA 配置（数据面先回放 `src/test/resources/deepanalyze/da-dataplane-baseline.sql`）；时钟控制给 DA 进程加 `LD_PRELOAD` libfaketime，用例运行中改偏移文件回拨 DA 时钟，回拨是否生效以 DA 自报时间（任务 `createdAt`、响应 `Date` 头）核对。
- 首次在 Linux 上跑 `Feat112ClockTest` 时，先确认 libfaketime 对 JVM 生效（B6 的激活证据“createdAt 落后约 60s”成立）；不生效时这三个场景按口径改提特性不可观测 Issue。

手工排查时仍可按下面的方式单独起 stub 与 DA（**模型地址必须以 `/v1` 结尾**，否则 DA 的模型调用全部 404、stub 收不到请求，门照样打开，看起来像注入成功）：

```bash
java -Dloader.main=com.openjiuwen.example.degradation.LlmFaultInjectionStub -cp $JAR org.springframework.boot.loader.launch.PropertiesLauncher 28121
```

```bash
DA_MODEL_BASE_URL=http://127.0.0.1:28121/v1 java -jar $JAR --openjiuwen.service.degradation-gate.window=60s --openjiuwen.service.degradation-gate.min-sample-size=5
```

## 4. 执行用例

### 4.1 为什么不能直接 `./mvnw test`

测试仓是单一 Maven 模块，其他特性的部分用例依赖尚未声明的在研制品，整仓编译目前失败。在此之前使用 `feat112/tools/run-feat112.sh`：用 `pom.xml` 解析完整测试 classpath，只编译 FEAT-112 与通用结果层源码，再用 JUnit Platform 运行。依赖解析与运行的测试类都与 Maven 一致，区别只在于不编译其他特性的源码。

### 4.2 编译

```bash
M2=$HOME/m2-feat112 bash feat112/tools/run-feat112.sh compile
```

### 4.3 执行

一次运行可以选任意类或方法；`RUN_ID` 命名结果目录，`SOURCE_REVISION` 写入每条结果的被测物来源：

```bash
C=com.huawei.ascend.sit.cases.integration.task_degradation_gate; RUN_ID=linux-1 SOURCE_REVISION="$(git -C $SOLUTION rev-parse --short HEAD)" M2=$HOME/m2-feat112 bash feat112/tools/run-feat112.sh run target/feat112-run/linux-1.txt com.huawei.ascend.sit.fixtures.task_degradation_gate.Feat112ArchitectureTest com.huawei.ascend.sit.cases.component.task_degradation_gate.Feat112MechanismTest $C.Feat112WindowAccountingTest $C.Feat112AdmissionTest $C.Feat112StateMachineTest $C.Feat112ProbeTest $C.Feat112DiagnosticsTest $C.Feat112AuthenticatedTest $C.Feat112DisabledGateTest $C.Feat112ValidationPrecedenceTest $C.Feat112TransportTest $C.Feat112ProcessConfigurationTest $C.Feat112OrderedControlTest $C.Feat112WindowParametersTest $C.Feat112InstancesTest
```

结束后看三处：

- `target/feat112-run/<结果文件>`：每个测试方法的 JUnit 状态；
- `target/acceptance-results/<RUN_ID>/summary.md`：65 个场景逐个的六态结论、原因与承接用例，没有执行记录的场景显示 NOT_EXECUTED；
- `target/acceptance-results/<RUN_ID>/*.json`：每次执行的前置、激活与验收记录、证据路径、载体坐标与 sha256。

FAIL 的原因码决定谁来修：`must_violation` 是产品违反特性档；`conditional_not_triggered` 是前置或激活没成立，该次结论无效，先修部署、stub 或用例；`infra_failure` 是环境或执行中断。

## 5. 已知不能在本指南内完成的部分

| 场景 | 原因 | 解除条件 |
|---|---|---|
| E5 | 出厂配置下没有“护栏正常拦截”的确定可达路径；已登记为未承接 | 开发给出确定路径后补记账验收 |
| F3 团队可用分支 | 需开启 PostgreSQL 数据面并存在团队服务；本部署只能走到“通道不可用”分支 | 部署 PostgreSQL，`deepanalyze.data.pg.enabled=true` |
| I1 告警关联 | 测试环境没有监控与告警资产 | 接入监控后补告警采集 |
| E3、G8 | `business_failure` 在 DA 框架权限资产中默认 `ask`，任务停在待确认；用例会记为 conditional_not_triggered | 开发确认默认放行该工具，或 testplan 改写 E3 前置 |

## 6. 传输包

2026-11-11 的 `feat112-transfer-20261111.tar.gz` 对应旧目录结构，已不适用。迁移到 Linux 时按仓库相对路径带上：测试仓 `pom.xml`、`scripts/sut/sut-sources.yml`、`src/main/java/com/huawei/ascend/sit/judge/`、`src/main/java/com/huawei/ascend/sit/lifecycle/ProcessLauncher.java`、`src/test/resources/application-openjiuwen.yml`、`src/test/java/com/huawei/ascend/sit/judge/`、`src/test/java/com/huawei/ascend/sit/fixtures/task_degradation_gate/`、`src/test/java/com/huawei/ascend/sit/cases/component/task_degradation_gate/`、`src/test/java/com/huawei/ascend/sit/cases/integration/task_degradation_gate/`、`docs/cases/FEAT-112-task-degradation-gate-deepagent.md`、`feat112/`（不含 `.src-cache/`）；solution 仓 `common/example/task-degradation-gate-acceptance-demo/`（不含 `target/`）。

## 7. 本机实测结果（2026-11-11，旧结构，留作对照）

被测物：solution `common` 分支 `0f5d17265` 源码构建（本次 `-am` 构建链上的模块无本地改动），demo 修正模型地址后的可执行包。结果文件与 HTTP 取证在测试仓 `target/feat112-da-run/`、`target/feat112-evidence/`（不入库）。

| 批次 | 结果 | 说明 |
|---|---|---|
| ① 离线自测 | 58/58 通过 | 首次在 `target` 目录运行时 1 项结构检查因工作目录失败，从仓库根运行通过 |
| ② 真实产品机制验收 | 10/10 通过 | A1、B7×2、C4、E7、E8、E9×2、E11×2；也经 `run-feat112.sh` 复验 |
| ③ default 形态 | 28 个场景 ID 通过 | B8、K-rep1/2/3、J1、J3、D1a、D1b、D3、D5、G1–G3、C2、C3、C4、C7、B4、D4、D6、E1.1、E1.2、E2、E4、E7、F2、G9、G10；F3 仅“通道不可用”分支 |
| ④ auth / disabled 形态 | F1、G5a、G5b、G5c、G4 通过；G8 失败于前置 | G8 原因同 E3，见下 |
| ⑤ correlated / freg 形态 | C5、C6、F-reg 通过 | — |
| E3 | 失败于前置 | 见下 |

**合计：65 个场景 ID 中，36 个在真实 DA 上执行通过；F3 只执行了一个分支；A1、B7、E8、E9、E11 只完成产品类组件级验收；E3、G8 前置未触发；E5 无可达路径；20 个 e2e 场景未执行。**“通过”指测试断言在该构建上成立，强度以各用例断言为准（例如 D6 只覆盖公开面副作用）。

执行过程中修正的问题（都不是降级门缺陷）：

1. demo 模型地址缺 `/v1`，模型调用全部 404，stub 收到 0 个请求，但门照样被打开。已修 demo 默认值、托管实例模型地址，并在开门前置中加入“注入故障必须经过 stub”的激活校验。
2. 窗口 30s 与最小样本 20 不兼容 Core 重试节奏，改为窗口 60s、最小样本 5。
3. C2/E1 用例在拒收返回的瞬间检查 stub 在途请求，未等获准任务真正发出模型调用，属测试时序错误，已改为有界等待。
4. C6 前置只断言 CLOSED 而不恢复，C5 之后连跑必然失败，已改为通过真实健康探针自恢复。

需要开发或 testplan 确认的产品配置问题：

- **E3、G8：`business_failure` 默认需要用户确认。**DA 框架权限资产 `governance/actrule.yaml` 采用 `defaults.* = ask`，未把 `business_failure` 列为放行；场景级配置只能取更严，无法放宽。任务因此停在 `awaiting_answer`（`turnsUsed=0`），而按 E6 语义，探针进入等待回答即健康关门，所以出厂配置下 E3 描述的“以 BUSINESS_FAILURE 终态关门”走不到。需确认是产品应默认放行该专用终态工具，还是 testplan 改写 E3 前置。
- **运行时护栏与 DA 自带权限不能同时启用。**设置 `openjiuwen.service.guardrail.enabled=true` 后每个任务都以 `ENGINE_TOOL_ERROR` 失败（提示 DeepAgentConfig 已注册权限 rail，需移除 `permissions`），且被计为系统异常。
- **E5 无可达路径。**`GUARDRAIL_DENIED` 要求任务失败、出现过护栏拒绝、且没有任何系统异常证据。出厂工具中没有默认拒绝的已注册工具（`delete_file` 未注册，调用得到“Unknown tool”后任务正常完成）；被拒绝后模型下一轮正常回答，任务就是 `completed`。需开发给出确定性的“拒绝导致任务失败”路径。

**E4 的激活方式**：stub 的 `errorCode: ENGINE_CONTEXT_TOO_LONG` 快捷错误不含提供方关键字，Core 不会归为 181012。用例改为返回真实提供方格式的 400（`error.code=context_length_exceeded`），由 Core 分类、DA 映射为 `INPUT_REJECTED`；实测只计分母、不计分子，作为半开探针时关门。
