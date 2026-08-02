# agent-runtime-acceptance

Agent 运行时（spring-ai-ascend / openJiuwen 等）的 **SIT 验收测试框架**：把被测 agent 当作黑盒
（本地 `java -jar` 拉起或直连远端部署），通过 A2A / REST 多种协议驱动多轮交互，
在 component / integration / e2e / performance 四个层级断言外部可观测行为，并产出 Allure 报告。

## 快速开始

```bash
# 供给 SUT（懒克隆源码 + 幂等构建到 ~/.m2）→ 跑回归套件 → 生成 Allure 报告
./scripts/run-pipeline.sh --env openjiuwen

# 看报告（Allure 3 CLI）
allure serve target/allure-results
```

远端跑测试、本机 Windows 看报告？用 `ssh -L` 端口转发即可，见
[docs/quickstart.md §4.4](docs/quickstart.md#44-远端-linux-跑测试本机-windows-看报告ssh--l)。

## 文档地图

| 文档 | 路径 | 读它如果你想…… |
| --- | --- | --- |
| [docs/quickstart.md](docs/quickstart.md) | 操作路径 | 跑用例、装 Allure、起本地服务、`ssh -L` 远端看报告 |
| [docs/framework-design.md](docs/framework-design.md) | 认知路径 | 理解框架能力总览（含总体架构图）：SutStack 部署、InteractionFlow/Conversation 客户端、MessageTransport 传输抽象 |
| [docs/write-testcase.md](docs/write-testcase.md) | 实战路径 | 基于抽象层写一个新用例（五步流程 + 校验清单） |
| [docs/ci-integration.md](docs/ci-integration.md) | 集成路径 | 定时/CI 接入、报告归档、历史趋势 |
| [docs/cases/](docs/cases/) | 用例设计 | 逐场景设计文档（FEAT-NNN），与报告 feature 树对应 |

## 仓库布局

```
scripts/
  run-pipeline.sh        # 主入口：provision → mvnw test → allure 报告
  sut/                   # Stage 1 供给（sut-sources.yml 声明源码与构建步骤）
  triggers/              # systemd timer / cron 样例
src/main/java/com/huawei/ascend/sit/
  lifecycle/             # SutStack / ProcessLauncher / BackingServices（部署层）
  client/                # InteractionFlow / A2aServiceClient（文本交互 DSL）
  conversation/          # Conversation / Turn / DriveMode（结构化会话）
  transport/             # MessageTransport + A2A_*/REST_* 协议适配（传输层）
src/test/java/com/huawei/ascend/sit/
  base/                  # BaseManagedStackTest
  cases/{component,integration,e2e,performance}/
  suites/                # Smoke / SubLinkRegression / E2E / Performance
src/test/resources/
  application-{local,openjiuwen,sit,uat}.yml   # 环境配置（-Dtest.env 选择）
  testdata/              # 外置输入与契约样本
allurerc.mjs             # Allure 3 报告配置（功能模块视图 + 包结构视图）
```
