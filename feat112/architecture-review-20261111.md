# FEAT-112 测试代码架构评审（对照 D1/D2 洞察）

## 2026-11-12 改造结果与更正

**更正**：下文第 1 节“分层骨架合理”与第 2 节 E“激活证明已修”偏乐观。当时目录与标签编码的是部署方式（连远端 vs 自己拉起进程），不是验收层；激活证明只是一个可选辅助方法。此外，本评审没有发现 testplan 预期取自 L2/实现代码的问题（G5、E4/E5、F1–F3），这一点由 2026-11-12 对照特性档复查发现。

**改造后**（结构与运行方式见 [cases](../docs/cases/FEAT-112-task-degradation-gate-deepagent.md) §1.2、§5、§6）：

| 原问题 | 现状 |
|---|---|
| A 判定未与执行分离 | 用例只经 `Scenario` 记录前置、激活与验收 Finding，结论由 `AcceptanceExtension` 推导；`Feat112ArchitectureTest` 禁止用例直接断言 |
| B 结果不可追溯 | `@Covers` 声明场景 ID 与层级，多 ID 方法逐 ID 记录；每次执行写结果 JSON，运行汇总为逐场景 `summary.md`；规则出处只能经 `Feat112Contract` 引特性档快照 `05af9cc3`；载体坐标、sha256、构建来源随结果记录 |
| C 未接入供给→执行机制 | `application-openjiuwen.yml` 声明 `feat112` / `feat112-peer`，`sut-sources.yml` 加构建步骤；用例按部署形态经 SutStack 自行托管启动，删去远端地址与约 10 个 `feat112.*` 系统属性 |
| D 部署前置矩阵只在注释里 | `DeploymentForm` 在代码里声明每个类或场景的部署形态；缺部署记 infra_failure，不再 SKIPPED |
| E 激活证明缺口 | 激活证据由驱动记录进结论；前置/激活不成立记 conditional_not_triggered，与产品违规分开；SSE 采集返回结果交调用方判定，不再抛断言 |
| F fixtures 职责混杂 | 拆为 `judge/`、`deploy/`、`drive/`；判据不依赖驱动与部署，夹具不依赖用例（均有结构规则） |
| G 整仓编译耦合 | 未变，属仓库维护范围；继续用 `run-feat112.sh` 定向编译运行 |

---

日期：2026-11-11。范围：测试仓 FEAT-112 新增代码（公共判据 1 + 自测 1，特性 fixtures 26，component 用例 10 类，e2e 用例 7 类）、demo 载体、执行接线。评审看结构是否支撑 D1/D2 主张，不重复逐条用例正确性。

## 结论

**分层骨架合理，判定与执行接线两处不合理。**判据模型、取证卫生、按可观测边界分层这三件事做对了；但“判据独立于执行、结果可追溯到场景 ID”这一 D2 核心主张在用例层基本没有落地，且 FEAT-112 没有接入测试仓既有的供给→执行机制。本轮实跑把后一个问题暴露成了真实缺陷（见第 3 节）。

## 1. 与洞察一致、应保留的结构

| 结构 | 对应洞察 | 证据 |
|---|---|---|
| 公共判据模型在 `src/main/.../judge`，与特性无关；六态结果 + reason；观察边界 × 用途 × 证据来源三轴分类；非法组合构造期拒绝 | D2 G1/G2：判据声明、归因 | `EvidenceChecks`，12 项自测 |
| 按可观测边界分层：公开面不可观测的机制（代际、terminalAt、claim）放到对真实产品类的组件级验收，不在 HTTP 上虚构字段 | D2 可观测性限制证明范围而非伪造证据 | `Feat112MechanismTest` 10 项对真实制品通过 |
| 取证卫生：HTTP 交换脱敏落盘、有界采集、只终止自建进程、清理失败不掩盖主失败 | D2 证据链 | `DaEvidenceClient`、`SafeProcessCapture`、`ProcessLauncher` 修复及回归 |
| 时序确定性：按请求关联的屏障替代 sleep/全局 FIFO | D2 激活可控 | `CorrelatedModelStub` |
| 验收用例只在测试仓；demo 是载体；testplan 镜像与 docs 源分支逐字节同步 | D1 契约归属、版本连续 | `feat112/README.md` |

## 2. 不合理之处（按影响排序）

### A. 判定没有与执行分离（D2 核心主张未落地）

本轮新增 E4 之前的 16 个用例类中约 310 处直接断言，经独立判据的仅约 10 处（拒收、健康面、副作用三个判据）。后果：

- 结果只剩 JUnit 的通过/失败，六态与 reason 只存在于断言消息字符串里；无法离线重评已采集证据。
- **前置与验收用同一个 `assertTrue`**，前置失败在报告里与产品违规同样显示为 FAILED。本轮实跑 20 条“失败”全部是前置（载体）问题，若无人工逐条读证据，会被误报为 20 个产品缺陷。
- 判据的 `Purpose.PRECONDITION` 只在拒收判据里用过，其余前置没有走判据。

### B. 场景结果不可追溯（D1 ID 用途未落地）

- 场景 ID 只写在 `@DisplayName` 字符串里，无机器可读映射；一个方法承载多个 ID（如 C2/D4/E1.1/E1.2/D5 在同一方法），前置失败会让多个 ID 一起失败，报告无法逐 ID 给结果。
- 判据产出的 `Finding` 不落盘、不汇总；`DaEvidenceClient` 存了原始交换，但没有“场景 ID → 证据 → 判据 → 六态结果”的清单。
- 规则的 `contractRef` 写的是“FEAT-112 §3.2 diagnostic contract”，没有钉到设计提交（05af9cc3）；运行证据也未记录被测制品坐标、构建 commit、校验和。本轮证实单个版本号标识不了被测物：Central 已发布的 `0.1.3` 完全不含降级门。

### C. 没有接入测试仓既有供给→执行机制

测试仓已有 `scripts/sut/provision-sut.sh` + `sut-sources.yml`（按环境声明源码与构建顺序、`mvn install`、与 `application-<env>.yml` 声明的制品核对）、`SutStack`（按 agent 声明托管或远端）、Suite 选择。FEAT-112：

- 未在 `sut-sources.yml` 声明构建步骤，未在 `application-openjiuwen.yml` 声明 `feat112`/`feat112b`/`gate-media` 等 agent，未按惯例用带后缀版本（如 `0.1.3-feat112-r1`）区分批次制品；
- 三种启动路径并存（远端 `GateDriver`、托管 `ManagedGate`、自管 `IsolatedDefaultProcess`），配置散落在约 10 个 `feat112.*` 系统属性与环境变量里；
- `ManagedGate` 要求 stub 地址，但没有任何环节供给 stub。

### D. 部署前置矩阵只存在于注释里

用例实际需要至少 8 种 DA 部署：默认、鉴权开启、门禁用、团队通道可用/不可用、关联 stub、F-reg 长静默期、媒体白名单、隔离默认配置。没有用标签或 Suite 声明，前置不符时有的 `assumeTrue` 跳过、有的直接失败，两者混用。按类整批运行到单一部署上，必然出现与产品无关的失败（本轮 G4/G5 跳过、F1/G8/F3 不可在默认部署运行）。

### E. 激活证明缺口（本轮实跑证实）

开门前置只检查“任务失败、失败计数上涨”，未检查故障来自声明的注入点。demo 的模型地址少了 `/v1`，DA 所有模型调用 404，任务照样失败、门照样打开，**stub 收到的请求数为 0**。已修：`runThroughStub` 要求注入失败伴随 stub `errorResponses` 增长、健康任务伴随 `totalRequests` 增长，否则记 `conditional_not_triggered`。同类问题：stub 的 `ENGINE_CONTEXT_TOO_LONG` 快捷错误不含提供方关键字，激活不了 E4，已改用真实提供方格式。

### F. fixtures 包职责混杂

26 个文件把判据、驱动、stub、进程控制、取证、配置、自测平铺在一个包里；`CorrelatedModelStubTest` 反射依赖用例类内部类（反向依赖）；目录分层检查写在 `SafeProcessCaptureTest` 里，且依赖当前工作目录（本轮从 `target` 目录运行即失败）。

### G. 测试仓整体编译耦合（非 FEAT-112 引入，但阻断执行）

测试仓是单一 Maven 模块。FEAT-056 用例依赖未声明的在研制品（原经已注释的 `deepanalyze-engine` 依赖传递带入），当前提交下 `./mvnw test` 在编译阶段失败，FEAT-112 也随之无法经标准入口执行。

## 3. 本轮已做的结构修正

- `feat112-product` Maven profile：按测试作用域引入真实降级门扩展包，版本显式给出；机制验收经此针对真实制品运行。
- `runThroughStub` 激活证明；托管实例模型地址与 demo 默认地址补 `/v1`；`contextTooLong()` 用真实提供方错误格式。
- 新增 E4（`Feat112InputRejectedTest`）及 B7/E9/E11 组件级验收。
- `feat112/tools/run-feat112.sh`：在测试仓整体编译受阻时，按 pom 解析 classpath 定向编译运行；已在本机验证。

真实运行还暴露了三处测试自身缺陷，均已修正，也印证了上面的结论：

- **时序假设**：C2/E1 在拒收返回的瞬间检查 stub 在途请求，没有等获准任务真正发出模型调用，可复现地失败；改为有界等待。
- **前置不自恢复**：C6 的前置只断言 CLOSED，接在 C5（结束于 OPEN）之后必然失败；改为经真实健康探针恢复，与 `prepareEmptyClosed` 一致。
- **部署参数与产品节奏不匹配**：demo 默认窗口 30s、最小样本 20，而每个注入失败的任务因重试退避耗时约 4.7s，计数在 7 处封顶，门永远开不了；这类约束只写在注释里，没有任何前置检查能提示，属于第 2 节 D 的直接后果。

## 4. 建议的后续改造（按优先级）

1. **结果记录层**：一个 JUnit 扩展按场景 ID 收集 `Finding`、证据引用、前置上下文，写出每次运行的 JSON；用例改为“采集 → 判据 → 由结果派生断言”，前置全部走 `Purpose.PRECONDITION`。从使用最多的窗口计数、状态迁移、副作用三类开始迁移。
2. **逐 ID 结果**：拆分多 ID 方法，或在方法内按 ID 记录 Finding。
3. **接入供给链**：`sut-sources.yml` 增加 FEAT-112 构建步骤（`-Drevision=0.1.3-feat112-r1`）；`application-openjiuwen.yml` 声明 agent 条目；stub 作为可供给服务。
4. **部署前置声明**：按部署类型打标签并提供对应 Suite；前置不满足统一记 `NOT_OBSERVED`/`conditional_not_triggered` 并在报告中可见，不混用跳过与失败。
5. **版本钉住**：规则 `contractRef` 带设计提交；运行证据记录被测制品坐标、构建 commit、校验和。
6. **整理 fixtures**：按判据/驱动/stub/进程/取证分子包；去掉反向依赖；结构检查独立并用仓库根定位。
7. **测试仓编译隔离**：由仓库维护者决定拆模块或恢复依赖；在此之前 FEAT-112 只能用解析出的 classpath 定向编译运行。
