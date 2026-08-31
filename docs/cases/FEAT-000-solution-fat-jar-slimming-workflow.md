---
feature_id: FEAT-000
test_type: workflow
scope: solution-common
deployable_units: [edp-agent-engine, customer-agent-app, adapter-versatile-agent-java]
sut: EDP Agent executable JAR and customer integration application against AgentEnvExplorer
features: [FEAT-000]
updated: 2026-08-29
---

# EDP Agent 验收：Solution 层 Fat Jar 瘦身

## 1. 测试目标

验证 Solution 层瘦身后的正式构建产物满足体积、发布形态和禁包约束，同时证明 EDP Agent 在 AgentEnvExplorer 受控依赖下能够启动并完成两条理财业务旅程。构建产物检查与业务黑盒检查分别取证，不能以依赖树或编译成功替代服务启动和业务结果。

## 2. 范围与非范围

范围：

- engine 与 integration fat jar 的体积预算、versatile 主 artifact 与 `-exec` artifact 的发布形态。
- L2 指定重型依赖及包路径不得进入 fat jar。
- EDP Agent 启动、Agent Card/公开入口可达。
- 余额充足时的理财购买，以及余额不足后转账再完成购买。

非范围：

- 未在 L2 声明的性能、并发、生产银行凭证、真实资金交易和第三方系统 SLA。
- 其他 Solution 模块、内部 POM 排序、Spring Bean、线程或数据库实现细节。
- 未定义的 FEAT 行为；本主题没有独立 Feature 文档，以 L2 为设计依据。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/03-architecture/L2-Low-Level-Design/edpa/Feat-000-solution-fat-jar-slimming-design.md` | 定义 exclusions、整体排除策略、versatile `classifier=exec`、体积预算、禁包清单、启动和业务验证要求。 |
| 测试仓同主题 workflow 用例 | 仅用于确认公开触发入口、Fixture 和可观察证据，不改变 L2 Oracle。 |

## 4. 部署拓扑

```text
artifact inspector
  -> engine / integration / versatile build artifacts

SIT workflow/A2A client
  -> EDP Agent public endpoint
       -> adapter-versatile-agent-java
            -> AgentEnvExplorer wealth-invest mock SSE
       -> Redis/runtime dependencies
  <- Task/SSE states, balance/transfer evidence, purchase result
```

- jar 检查只读取正式构建产物、依赖清单和归档内容。
- 业务检查只访问 EDP Agent 公开入口与 AgentEnvExplorer mock HTTP。
- 每条旅程使用独立 conversation/context，启动探针先于业务请求。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| FEAT-000.artifact.slimming | 瘦身产物体积与禁包 | G：按 L2 指定坐标构建正式产物 | W：检查 engine/integration fat jar 字节数、versatile 主/exec jar、依赖清单和 `jar -tf` 内容 | T：engine/integration fat jar 均小于 60 MB；versatile 主 artifact 小于 1 MB 且 `-exec.jar` 存在；禁包不出现在依赖或归档内容中 | isolated Maven repository + artifact inspector |
| FEAT-000.edpa.startup | 修改后 EDP Agent 启动闭环 | G：瘦身后的 exec jar、Redis 和 AgentEnvExplorer 就绪 | W：启动 EDP Agent，等待端口和 Agent Card，调用无副作用公开探针 | T：进程在有界时间内保持运行，公开入口返回合法响应，无缺类或依赖加载错误 | process fixture + AgentEnvExplorer |
| FEAT-000.wealth.purchase.sufficient | 余额充足的理财购买 | G：EDP Agent 就绪，mock 载入确定性账户和产品数据 | W：请求理财推荐，选择产品和金额并确认购买，按公开中断语义续接 | T：产品、选择和确认过程可观察；同一业务上下文最终购买成功，无内部堆栈泄漏 | wealth-invest mock + A2A stream driver |
| FEAT-000.wealth.purchase.transfer | 余额不足后转账再购买 | G：购买金额高于理财账户余额，活期账户有可转资金 | W：请求购买，观察余额不足，确认转账，等待转账成功，再继续原购买 | T：余额不足、转账确认、转账成功和购买完成按顺序可观察；原 Task/context 不丢失 | wealth-invest/transfer mock + bounded resume driver |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| EDP Agent engine/integration application | 真实 SUT | 使用 L2 指定坐标构建的正式 exec/fat jar，承载启动和业务入口断言。 |
| AgentEnvExplorer | Fixture | 提供确定性产品、余额、转账和购买 SSE，不实现 EDP 路由或购买决策。 |
| artifact/process/workflow driver | Fixture | 检查归档、管理进程并采集公开 A2A/SSE 证据，不修改构建产物。 |

## 7. 关键链路断言

- engine 与 integration fat jar 均小于 60 MB；versatile 主 artifact 小于 1 MB，fat jar 通过 `-exec` classifier 发布。
- 禁包覆盖 L2 列出的重型依赖及其传递包，依赖清单与归档内容双重检查。
- 启动判定同时要求进程存活和公开协议入口可达，编译或打包成功不能替代启动证据。
- 正常旅程必须完成购买；余额不足旅程必须先完成转账，再在同一业务上下文完成原购买目标。
- 不断言固定 LLM 文案、内部 Bean、POM XML 排序或 Fixture 实现类名。

## 8. 执行策略

- 先检查产物发布形态和禁包，再启动 EDP Agent，避免把不可启动的产物带入业务旅程。
- 启动、正常购买和余额不足旅程分别使用独立标识与数据种子。
- 异步响应采用有界等待，并以公开 Task/SSE 状态、业务 canary 和最终购买状态作为判定依据。
- 缺少正式产物、AgentEnvExplorer、Redis 或业务场景数据时，记录缺失前提，不用静态检查替代相应业务场景。
