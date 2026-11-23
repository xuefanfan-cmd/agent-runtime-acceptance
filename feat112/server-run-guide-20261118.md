# FEAT-112 服务器实跑指南（2026-11-18，离线部署）

用途：在 Linux 服务器上用新载体跑 FEAT-112 全量回归（17 个用例类），其中 8 个用例方法（6 个场景）只能在 Linux 上跑。被测对象不改，测试侧需要 Docker（PostgreSQL、Redis 容器）与 libfaketime（控制 DA 进程时钟）。

| 用例类 | 方法 | 场景 | 需要 |
|---|---|---|---|
| `Feat112ClockTest` | `lateTerminalInsideWindowCounts` | B6 | libfaketime |
| | `expiredFailureWhileOpenIsIgnored`、`expiredFailureWhileHalfOpenDoesNotVetoProbe` | B7（OPEN、HALF_OPEN 两种前置） | libfaketime |
| | `expiredProbeOnlyReleasesSlot` | E9 | libfaketime |
| `Feat112CheckpointStoreTest` | `deniedThenUnexplainedFailureCountsNumerator` | E15 | Docker（Redis） |
| `Feat112DataPlaneTest` | `reservedTenantPrecedesGate`、`unknownTeamPrecedesGate`、`failureBetweenClaimAndBindReleasesSlot` | F4、F3（team 不存在）、E11 | Docker（PostgreSQL） |

**部署方式**：
- 放在独立目录 `/home/feat112`，**不动** `/home/agent-runtime-acceptance`：那份是 FEAT-033 用的旧副本，`pom.xml` 被 FEAT-033 的解锁包改过，`/root/.m2` 里装了 FEAT-033 的制品；FEAT-112 用自己的依赖库，两边互不影响。
- 全程离线：服务器连不上 gitcode，也不需要 Maven Central。测试代码与全部依赖都在包里；Maven 用服务器上已有的（之前跑其他特性用过的），以离线模式只从随包的依赖库解析，不碰 `/root/.m2`。

## 1. 本机已打好的包

目录 `D:\feat112-offline-20261118`：

| 文件 | 大小 | 内容 |
|---|---|---|
| `feat112-sit-repo.tar.gz` | 32MB | 测试仓工作区快照（含未提交的 FEAT-112 用例、夹具、判据与本指南；不含 `.git`，基线提交与源码指纹记在 `feat112/test-revision.txt`） |
| `feat112-m2-full.tar.gz` | 768MB | 完整依赖库：载体、构建清单 `feat112-build-manifest.json`、openjiuwen 内部制品与全部公开依赖（已去掉远程仓库标记，离线可用） |
| `apache-maven-3.9.16.tar.gz` | 9MB | 备用 Maven 3.9.16（服务器上找不到 Maven 时才用） |
| `libfaketime-0.9.10.tar.gz` | 87KB | libfaketime 源码（服务器没有 faketime 包时现场编译） |
| `SHA256SUMS` | — | 以上四个文件的 sha256 |

载体 `com.openjiuwen.example:degradation-agent-runtime:0.1.0`，2026-11-18 由 core `origin/develop@f777aa244` 与 solution `origin/common@96c23d4ea` 重建（sha256 `cdb5c93e…`），与本机冒烟用的是同一个制品。用例按构建清单核对载体 sha256，不一致会拒绝运行。

重新打包时（本机 Git Bash，测试仓根目录）先记录基线提交与源码指纹，再打测试仓包：

```bash
M2=D:/m2-feat112 bash feat112/tools/run-feat112.sh snapshot
```

## 2. 传到服务器

服务器上先建目录：

```bash
mkdir -p /home/feat112/pkg /home/feat112/repo /home/feat112/m2
```

把 `D:\feat112-offline-20261118` 里的 5 个文件传到服务器 `/home/feat112/pkg/`（Xftp 拖过去即可；或在本机 Git Bash 执行，地址换成你的）：

```bash
scp /d/feat112-offline-20261118/* root@<server>:/home/feat112/pkg/
```

## 3. 服务器自检

| 项目 | 要求 | 检查 |
|---|---|---|
| 系统 | Linux x86_64 | libfaketime 只能在 Linux 上用 |
| JDK | 21 | 测试 JVM 与被拉起的 DA 进程用同一个 JDK；`java -version` 不是 21 时告诉我，我把 Linux 版 JDK 21 加进离线包 |
| 内存 | ≥ 8GB，建议 16GB | 每个 DA 实例约 1–1.5GB，另有 stub、容器 |
| Docker | 守护进程运行中 | Testcontainers 经 `/var/run/docker.sock` 起容器 |
| 镜像 | `postgres:16.2`、`redis:7-alpine` | 拉不到时见下文“镜像” |
| Maven | 3.6 及以上 | 用服务器上已有的，见下文“Maven” |
| 编译工具 | `gcc`、`make` | 只在需要现场编译 libfaketime 时用 |

```bash
java -version && docker ps && free -g && docker image ls | grep -E 'postgres|redis'
```

**Maven**：之前跑其他特性用过，找出它的位置（任一条有输出即可）：

```bash
mvn -v; ls -d /root/.m2/wrapper/dists/apache-maven-*/*/bin/mvn 2>/dev/null
```

`mvn -v` 有版本号就用 `mvn`；否则用第二条列出的路径（`./mvnw` 之前下载的 Maven）。两条都没有输出时才用随包的备用 Maven（见第 4 节）。

**镜像**：本机没有 Docker，镜像没有随包。先试：

```bash
docker pull postgres:16.2 && docker pull redis:7-alpine
```

拉不到时，用服务器能访问的镜像源（如华为云 SWR 镜像仓库或内网仓库）里的同版本镜像，记下完整镜像名，第 5 节用环境变量指定即可（任意镜像名都可以，不要求叫 `postgres`）。Testcontainers 的清理容器 ryuk 也要拉镜像，离线时直接关掉（第 5 节已包含）。

**libfaketime**：先看系统里有没有：

```bash
find / -name 'libfaketime.so.1' 2>/dev/null | head -3
```

没有就用包里的源码编译（只需 gcc、make，不联网）：

```bash
cd /home/feat112 && tar -xzf pkg/libfaketime-0.9.10.tar.gz && cd libfaketime-0.9.10 && make && ls -l src/libfaketime.so.1
```

验证可用（两行时间应相差约 90 秒，路径换成实际的 `libfaketime.so.1`）：

```bash
LD_PRELOAD=/home/feat112/libfaketime-0.9.10/src/libfaketime.so.1 FAKETIME="-90" date && date
```

## 4. 解包

```bash
cd /home/feat112/pkg && sha256sum -c SHA256SUMS
```

五个文件都应显示 `OK`。然后：

```bash
cd /home/feat112 && tar -xzf pkg/feat112-sit-repo.tar.gz -C repo && tar -xzf pkg/feat112-m2-full.tar.gz -C m2
```

Windows 打的包可能带 CRLF 行尾、丢执行位，修正一下：

```bash
cd /home/feat112/repo && sed -i 's/\r$//' mvnw feat112/tools/*.sh && chmod +x mvnw feat112/tools/*.sh
```

只有服务器上找不到 Maven 时，才解备用 Maven（路径 `/home/feat112/apache-maven-3.9.16/bin/mvn`）：

```bash
cd /home/feat112 && tar -xzf pkg/apache-maven-3.9.16.tar.gz && chmod +x apache-maven-3.9.16/bin/mvn
```

## 5. 环境变量（每次新开终端都先执行）

```bash
export M2=/home/feat112/m2 MVN=mvn MVN_OFFLINE=1 TESTCONTAINERS_RYUK_DISABLED=true SUT_INFRA_LIBFAKETIME=/home/feat112/libfaketime-0.9.10/src/libfaketime.so.1
```

- `MVN` 换成第 3 节找到的 Maven：`mvn`，或 wrapper 下载的完整路径，或备用的 `/home/feat112/apache-maven-3.9.16/bin/mvn`。
- `SUT_INFRA_LIBFAKETIME` 换成第 3 节找到或编译出的实际路径。
- 镜像用了其他名字时再加（换成实际镜像名）：

```bash
export SUT_SERVICES_POSTGRES_IMAGE=<postgres 16 镜像全名> SUT_SERVICES_REDIS_IMAGE=<redis 7 镜像全名>
```

## 6. 编译与离线自测

```bash
cd /home/feat112/repo && bash feat112/tools/run-feat112.sh compile
```

末行应为 `compiled 67 sources into …`。然后跑离线自测（不启动 DA，确认代码与夹具在这台机器上正常）：

```bash
cd /home/feat112/repo && P=com.huawei.ascend.sit.fixtures.task_degradation_gate; RUN_ID=linux-selftest bash feat112/tools/run-feat112.sh run target/feat112-run/linux-selftest.txt $P.Feat112ArchitectureTest $P.deploy.DeploymentEnvironmentTest $P.deploy.Feat112ArtifactsTest
```

`target/feat112-run/linux-selftest.txt` 末行应为 `failed=0`。

## 7. 全量回归（17 个用例类，约 40–60 分钟）

断开 SSH 也不中断，用 `nohup` 后台跑，日志写到 `/home/feat112/linux-full-1.log`：

```bash
cd /home/feat112/repo && C=com.huawei.ascend.sit.cases.integration.task_degradation_gate; RUN_ID=linux-full-1 nohup bash feat112/tools/run-feat112.sh run target/feat112-run/linux-full-1.txt $C.Feat112ProcessConfigurationTest $C.Feat112DiagnosticsTest $C.Feat112DisabledGateTest $C.Feat112AdmissionTest $C.Feat112StateMachineTest $C.Feat112ProbeTest $C.Feat112WindowAccountingTest $C.Feat112ValidationPrecedenceTest $C.Feat112TransportTest $C.Feat112OrderedControlTest $C.Feat112AuthenticatedTest $C.Feat112WindowParametersTest $C.Feat112MemberGuardrailTest $C.Feat112InstancesTest $C.Feat112ClockTest $C.Feat112CheckpointStoreTest $C.Feat112DataPlaneTest > /home/feat112/linux-full-1.log 2>&1 &
```

看进度（每完成一个方法追加一行；末行出现 `SUMMARY` 即结束）：

```bash
tail -3 /home/feat112/repo/target/feat112-run/linux-full-1.txt
```

只想先确认 Linux 专属的 8 个方法（约 15–25 分钟），可以先跑这一条，结果目录换成 `linux-env-1`：

```bash
cd /home/feat112/repo && C=com.huawei.ascend.sit.cases.integration.task_degradation_gate; RUN_ID=linux-env-1 bash feat112/tools/run-feat112.sh run target/feat112-run/linux-env-1.txt $C.Feat112ClockTest $C.Feat112CheckpointStoreTest $C.Feat112DataPlaneTest
```

## 8. 判读

结果在三处：`target/feat112-run/linux-full-1.txt`（每个方法的 JUnit 状态）、`target/acceptance-results/linux-full-1/summary.md`（逐场景六态结论，报告头写明载体来源）、`target/acceptance-results/linux-full-1/*.json`（前置、激活、验收记录与证据路径）。

| 场景 | 先看的激活证据 | 不成立时说明什么 |
|---|---|---|
| B6 | “failed task was stamped about 60s behind … createdAt lags the test clock by …s” | libfaketime 对 JVM 没生效。先确认第 3 节 libfaketime 验证通过；仍不行则按口径提“特性不可观测”Issue，B7、E9 同样受影响 |
| B7、E9 | “DA clock moved back about 90s (response Date lags …)” | 同上；若 B6 成立而这里不成立，回传 DA 日志排查 |
| E15 | “task is parked on awaiting_answer”、“resumed task failed”、“terminal fact without a stable code” | 停 Redis 后续跑没有失败或分类不是无码的 INFRASTRUCTURE_FAILURE：说明这条构造在当前代码上不成立，回传后改构造或提 Issue |
| F3（team 不存在） | “teamNotFound … refused by validation with the gate CLOSED (HTTP 404)” | 不是 404：数据面基线与当前 DA 版本不匹配，看 DA 日志里的 SQL 报错 |
| F4 | CLOSED 时带保留租户头被既有校验拒绝 | 按代码走读，OPEN 下预计先返回 503 `ENGINE_DEGRADED`，会判 `must_violation`——这是要记录的产品结论，不是环境问题 |
| E11 | CLOSED 时同一请求被既有校验拒绝；HALF_OPEN 下得到相同错误 | 若 HALF_OPEN 下返回 503 HALF_OPEN，说明请求没走到认领之后，回传证据 |

全量回归中，以下非 PASS 结论在本机已经出现过，是已知结论，不是 Linux 环境问题：

| 场景 | 本机结论 | 原因 |
|---|---|---|
| G5-missing | FAIL / `must_violation` | 产品问题：交付默认配置下匿名请求可读诊断面（已配置认证的部署上通过） |
| E3 | FAIL / `conditional_not_triggered` | 出厂配置下 `business_failure` 落入默认确认，探针停在待确认，走不到业务失败 |
| E16 | FAIL / `conditional_not_triggered` | core 成员调用路径 topP 为空时 NPE（core 最新 develop 仍未修），走不到护栏拒绝 |
| A1、E17、E18 | NOT_EXECUTED | 未承接（覆盖缺口），没有用例 |

新载体上这几项结论若变了（例如 E16 变 PASS），照样回传，我核对后更新登记。

原因码：`must_violation` 是产品违反特性档；`conditional_not_triggered` 是前置或激活没成立，结论无效，先修构造；`infra_failure` 是环境缺失或执行中断（如 Docker、libfaketime 不可用）。

## 9. 回传

```bash
cd /home/feat112/repo && tar -czf /home/feat112/feat112-linux-full-1-results.tar.gz target/feat112-run/linux-full-1.txt target/acceptance-results/linux-full-1 target/feat112-evidence target/sit-logs /home/feat112/linux-full-1.log
```

只跑了第 7 节第二条时，把命令里的 `linux-full-1` 都换成 `linux-env-1`（没有 nohup 日志就去掉最后一项）。把这个包传回本机即可，我据此更新 cases §8、覆盖登记与进展记录。

## 10. 常见问题

- **`mvn: command not found`**：`MVN` 没设成第 3 节找到的 Maven 路径。
- **编译报 “Cannot access central … in offline mode”**：依赖库没解到 `/home/feat112/m2`，或 `M2` 没设对。检查 `ls /home/feat112/m2/com/openjiuwen /home/feat112/m2/feat112-build-manifest.json`。
- **`$'\r': command not found`**：脚本带 CRLF，执行第 4 节的 `sed` 修正。
- **Testcontainers 报 “Could not find a valid Docker environment”**：Docker 守护进程没起，或 `DOCKER_HOST` 指向不对。
- **容器起不来、提示拉镜像失败**：见第 3 节“镜像”，用 `SUT_SERVICES_POSTGRES_IMAGE`、`SUT_SERVICES_REDIS_IMAGE` 指定能拉到的镜像；确认已设 `TESTCONTAINERS_RYUK_DISABLED=true`。
- **时钟用例报 libfaketime 不存在**：`SUT_INFRA_LIBFAKETIME` 路径不对，按第 3 节重新找或编译。
- **用例报 “carrier … sha256 … does not match build manifest”**：依赖库里的载体被覆盖了，重新解压第 4 节的依赖库包。
- **报告头“测试代码”一栏**：服务器副本没有 `.git`，显示 `feat112/test-revision.txt` 里记录的基线提交，加上现场计算的源码指纹；指纹应与该文件第二行一致，不一致说明服务器上的代码被改过。
