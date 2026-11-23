# FEAT-112 服务器实跑指南（2026-11-17）

用途：在 Linux 服务器上实跑本机跑不了的 8 个用例方法（6 个场景），也可顺带做一次全量回归。被测对象不改，只需要测试侧的环境：Docker（PostgreSQL、Redis 容器）与 libfaketime（控制 DA 进程时钟）。

| 用例类 | 方法 | 场景 | 需要 |
|---|---|---|---|
| `Feat112ClockTest` | `lateTerminalInsideWindowCounts` | B6 | libfaketime |
| | `expiredFailureWhileOpenIsIgnored`、`expiredFailureWhileHalfOpenDoesNotVetoProbe` | B7（OPEN、HALF_OPEN 两种前置） | libfaketime |
| | `expiredProbeOnlyReleasesSlot` | E9 | libfaketime |
| `Feat112CheckpointStoreTest` | `deniedThenUnexplainedFailureCountsNumerator` | E5.3 | Docker（Redis） |
| `Feat112DataPlaneTest` | `reservedTenantPrecedesGate`、`unknownTeamPrecedesGate`、`failureBetweenClaimAndBindReleasesSlot` | F4、F3（team 不存在）、E11 | Docker（PostgreSQL） |

## 1. 服务器先决条件

| 项目 | 要求 | 说明 |
|---|---|---|
| 系统 | Linux x86_64 | libfaketime 只能在 Linux 上用 |
| JDK | 21 | 测试 JVM 与被拉起的 DA 进程用同一个 JDK |
| 内存 | ≥ 8GB，建议 16GB | 每个 DA 实例约 1–1.5GB，另有 stub、容器 |
| 网络 | 能访问 Maven Central（或在 `~/.m2/settings.xml` 配镜像） | 编译时解析公开依赖；openjiuwen 系列制品随包带过去，不从网上取 |
| Docker | 守护进程运行中，当前用户能直接执行 `docker ps`（不用 sudo） | Testcontainers 经 `/var/run/docker.sock` 起容器 |
| 镜像 | `postgres:16.2`、`redis:7-alpine`、`testcontainers/ryuk` | 不能访问 Docker Hub 时先 `docker pull`/`docker load` 好；拉不到 ryuk 时设 `TESTCONTAINERS_RYUK_DISABLED=true` |
| libfaketime | Debian/Ubuntu：`sudo apt-get install -y faketime` | 库默认在 `/usr/lib/x86_64-linux-gnu/faketime/libfaketime.so.1`；其他发行版没有包时从源码构建（见第 8 节），并设 `SUT_INFRA_LIBFAKETIME` |
| 端口 | 本机回环端口可用 | DA、stub 用随机端口；容器端口由 Docker 随机映射 |

自检：

```bash
java -version
```

```bash
docker ps && docker image ls | grep -E 'postgres|redis|ryuk'
```

```bash
ls -l /usr/lib/x86_64-linux-gnu/faketime/libfaketime.so.1 && LD_PRELOAD=/usr/lib/x86_64-linux-gnu/faketime/libfaketime.so.1 FAKETIME="-90" date && date
```

最后一条两行时间应相差约 90 秒，说明 libfaketime 可用。

## 2. 在本机打包（Windows Git Bash，在测试仓根目录执行）

两个包：测试仓工作区（含未提交的 FEAT-112 改动），以及 Maven 仓库里的 openjiuwen 制品（载体、构建清单与内部依赖）。

```bash
tar -C /d/agent-runtime-acceptance-main -czf /d/feat112-sit-repo-20261117.tar.gz --exclude=./target --exclude=./.git --exclude='./*.tar.gz' --exclude='./feat0*-jars' --exclude=./feat112/.src-cache --exclude=./insight --exclude='./.insight*' --exclude=./.tmp-insight-dump --exclude='./.restructure*' .
```

```bash
tar -C /d/m2-feat112 -czf /d/feat112-m2-openjiuwen-20261117.tar.gz com/openjiuwen feat112-build-manifest.json
```

载体是 `com/openjiuwen/example/degradation-agent-runtime/0.1.0`，由 core `origin/develop@e24bcb74d` 与 solution `origin/common@1127a9ece7b` 构建（sha256 `2ff9090a…`），与本机各轮实跑是同一个制品。构建清单 `feat112-build-manifest.json` 必须一起带上：用例按清单核对载体 sha256，不一致会拒绝运行。

## 3. 服务器上解包

```bash
mkdir -p ~/feat112/repo ~/m2-feat112 && tar -xzf feat112-sit-repo-20261117.tar.gz -C ~/feat112/repo && tar -xzf feat112-m2-openjiuwen-20261117.tar.gz -C ~/m2-feat112
```

Windows 打的包可能带 CRLF 行尾、丢执行位，先修正脚本：

```bash
cd ~/feat112/repo && sed -i 's/\r$//' mvnw feat112/tools/*.sh && chmod +x mvnw feat112/tools/*.sh
```

## 4. 编译与离线自测

```bash
cd ~/feat112/repo && M2=$HOME/m2-feat112 bash feat112/tools/run-feat112.sh compile
```

第一次会把公开依赖下载进 `~/m2-feat112`。然后跑离线自测（不启动 DA，确认代码与夹具在这台机器上正常）：

```bash
cd ~/feat112/repo && P=com.huawei.ascend.sit.fixtures.task_degradation_gate; RUN_ID=linux-selftest M2=$HOME/m2-feat112 bash feat112/tools/run-feat112.sh run target/feat112-run/linux-selftest.txt $P.Feat112ArchitectureTest $P.deploy.DeploymentEnvironmentTest $P.deploy.Feat112ArtifactsTest
```

`target/feat112-run/linux-selftest.txt` 末行应为 `failed=0`。

## 5. 实跑 8 个方法

libfaketime 不在默认路径时先指定（配置项 `sut.infra.libfaketime`，环境变量形式如下）：

```bash
export SUT_INFRA_LIBFAKETIME=/path/to/libfaketime.so.1
```

执行（约 15–25 分钟；每个时钟用例单独起一个 DA，`Feat112DataPlaneTest` 三个方法共用一个带 PostgreSQL 的 DA）：

```bash
cd ~/feat112/repo && C=com.huawei.ascend.sit.cases.integration.task_degradation_gate; RUN_ID=linux-env-1 M2=$HOME/m2-feat112 bash feat112/tools/run-feat112.sh run target/feat112-run/linux-env-1.txt $C.Feat112ClockTest $C.Feat112CheckpointStoreTest $C.Feat112DataPlaneTest
```

## 6. 判读

结果在三处：`target/feat112-run/linux-env-1.txt`（每个方法的 JUnit 状态）、`target/acceptance-results/linux-env-1/summary.md`（逐场景六态结论，报告头写明载体来源）、`target/acceptance-results/linux-env-1/*.json`（前置、激活、验收记录与证据路径）。

| 场景 | 先看的激活证据 | 不成立时说明什么 |
|---|---|---|
| B6 | “failed task was stamped about 60s behind … createdAt lags the test clock by …s” | libfaketime 对 JVM 没生效。先确认第 1 节自检通过；仍不行则按口径提“特性不可观测”Issue，B7、E9 同样受影响 |
| B7、E9 | “DA clock moved back about 90s (response Date lags …)” | 同上；若 B6 成立而这里不成立，回传 DA 日志排查 |
| E5.3 | “task is parked on awaiting_answer”、“resumed task failed”、“terminal fact without a stable code” | 停 Redis 后续跑没有失败或分类不是无码的 INFRASTRUCTURE_FAILURE：说明这条构造在当前代码上不成立，回传后改构造或提 Issue |
| F3（team 不存在） | “teamNotFound … refused by validation with the gate CLOSED (HTTP 404)” | 不是 404：数据面基线与当前 DA 版本不匹配，看 DA 日志里的 SQL 报错 |
| F4 | CLOSED 时带保留租户头被既有校验拒绝 | 按代码走读，OPEN 下预计先返回 503 `ENGINE_DEGRADED`，会判 `must_violation`——这是要记录的产品结论，不是环境问题 |
| E11 | CLOSED 时同一请求被既有校验拒绝；HALF_OPEN 下得到相同错误 | 若 HALF_OPEN 下返回 503 HALF_OPEN，说明请求没走到认领之后，回传证据 |

原因码：`must_violation` 是产品违反特性档；`conditional_not_triggered` 是前置或激活没成立，结论无效，先修构造；`infra_failure` 是环境缺失或执行中断（如 Docker、libfaketime 不可用）。

## 7. 回传

```bash
cd ~/feat112/repo && tar -czf ~/feat112-linux-env-1-results.tar.gz target/feat112-run/linux-env-1.txt target/acceptance-results/linux-env-1 target/feat112-evidence target/sit-logs
```

把这个包传回本机即可，我据此更新 cases §8、覆盖登记与进展记录。

可选：同一台机器上做全量回归（约 40–60 分钟，内存 16GB 更稳），把第 5 节的类列表换成全部 17 个类：

```bash
cd ~/feat112/repo && C=com.huawei.ascend.sit.cases.integration.task_degradation_gate; RUN_ID=linux-full-1 M2=$HOME/m2-feat112 bash feat112/tools/run-feat112.sh run target/feat112-run/linux-full-1.txt $C.Feat112ProcessConfigurationTest $C.Feat112DiagnosticsTest $C.Feat112DisabledGateTest $C.Feat112AdmissionTest $C.Feat112StateMachineTest $C.Feat112ProbeTest $C.Feat112WindowAccountingTest $C.Feat112ValidationPrecedenceTest $C.Feat112TransportTest $C.Feat112OrderedControlTest $C.Feat112AuthenticatedTest $C.Feat112WindowParametersTest $C.Feat112MemberGuardrailTest $C.Feat112InstancesTest $C.Feat112ClockTest $C.Feat112CheckpointStoreTest $C.Feat112DataPlaneTest
```

## 8. 常见问题

- **Testcontainers 报 “Could not find a valid Docker environment”**：当前用户不能访问 Docker。把用户加入 `docker` 组并重新登录，或确认 `DOCKER_HOST` 指向正确的守护进程。
- **拉镜像超时**：提前 `docker pull postgres:16.2 redis:7-alpine`；ryuk 拉不到时 `export TESTCONTAINERS_RYUK_DISABLED=true`（容器在用例结束时仍会被关闭）。镜像名可用 `SUT_SERVICES_POSTGRES_IMAGE`、`SUT_SERVICES_REDIS_IMAGE` 覆盖。
- **没有 faketime 包**（如 openEuler、CentOS）：

```bash
git clone https://github.com/wolfcw/libfaketime.git && cd libfaketime && make && export SUT_INFRA_LIBFAKETIME=$PWD/src/libfaketime.so.1
```

- **`$'\r': command not found`**：脚本带 CRLF，执行第 3 节的 `sed` 修正。
- **编译时 openjiuwen 依赖下载失败**：确认第 2 节的 M2 包已解到 `~/m2-feat112`（应有 `com/openjiuwen/` 目录与 `feat112-build-manifest.json`）。
- **用例报 “carrier … sha256 … does not match build manifest”**：M2 里的载体被其他构建覆盖了，重新解压第 2 节的 M2 包。
