---
title: Agent 源码用例（examples）
description: 完整 Agent 源码用例——每个目录覆盖一种 agent 类型或适配器的入口、语义定义、runtime 装配、配套类与 YAML；不重复携带 pom
audience: ai-coding
---

# Agent 源码用例（完整框架源码集）

本目录只存放**完整框架源码集**：每个目录是一种 agent 类型/适配器的能力闭环，
包含 Application 入口、Agent 装配、配套类与 application.yml，可作为新工程的源码起点。
它们不是独立 Maven 工程；“完整”指 Java/YAML 接线不省略，并不表示目录自身携带构建与打包配置。

对 AI coding 消费者，推荐流程是：先确定业务根包，按
[compatibility.md](../compatibility.md) 创建常规 Java 17 / Spring Boot 工程并生成依赖，
再参考对应用例复制代码职责。复制时必须按下节的 `agent/`、`runtime/`、`resources/`
结构落盘并替换示例 package；若既有用例尚未迁移到该结构，也不能据此生成平铺工程。
这样既保留高密度框架知识，又避免在每个用例中复制通用 pom 与版本号。规则：

1. **必须是真实且可编译的源码**：Java 含完整 package、import 与类声明，禁止「略」「// ...」式省略；维护时必须使用 compatibility 推荐发布件执行编译校验。
2. **类型/适配器专属**：一个目录演示一个类型闭环（WorkflowAgent、Versatile 对接、
   ReAct、DeepAgent），命名中性化，不含业务逻辑。
3. **不放机制片段**：类型无关的装配片段与叠加能力增量在
   [../snippets/](../snippets/)——那里是单文件片段，不是完整工程。
4. **被引用而存在**：每个目录至少被一篇 how-to 引用；md 中只摘录关键接线片段，
   完整代码以本目录为唯一来源（避免 md 与代码双副本漂移）。
5. **依赖说明**：示例目录不各自携带 pom.xml；三类基础 Agent 共享一份已验证的最小
   POM 模板（见下节），版本坐标唯一来源是
   [../compatibility.md](../compatibility.md) 的依赖坐标速查表；每个目录需要的 artifact
   见下方「目录 → artifact 映射」。

## 复制到标准工程的目录约定

生成**新的应用工程时必须**按「语义层 / 服务层 / 资源层」落盘。`examples/react/` 是目录
结构的规范示范；其他尚为平铺形式的既有示例只提供类型接线内容，生成时仍须按职责迁移，
不得照搬其平铺 package。框架本身不以 package 名限制编译，这是本 SPEC 为保持 core / runtime
边界而规定的生成规范；只有用户明确要求接入既有工程结构时才可适配，但职责边界仍应保留。

```text
src/main/java/<business-base-package-path>/agent/   ← Agent 语义能力层（Core / Harness）：定义、Tool、Rail、Workflow DAG；不依赖 Spring/runtime
src/main/java/<business-base-package-path>/runtime/ ← 程序级服务层：Application、@Configuration、Runner 注册、Handler/协议托管
src/main/resources/                            ← 资源配置层：application.yml 及模型、A2A、middleware、remote-agents 配置
```

以上三个一级边界是强约束；`agent/` 与 `runtime/` 内部的二级 package **不是固定脚手架**。
不要机械生成空的 `tool/`、`rail/`、`customrest/` 目录，也不要把所有类型长期堆在一级 package。
应根据业务内聚性选择下列任一组织方式：

- **按业务场景纵向组织**：同一场景中共同演进的 Agent 定义、Tool、Rail 适合放在
  `agent/expense/`、`agent/policy/` 等业务子包；若现有项目已有 `svcx_xxx` 一类分区约定，
  可遵循现有约定，但新项目优先使用简短、全小写、无下划线的 Java package 名。Tool 只是
  Agent 对业务能力的语义适配入口；被它调用的领域服务、客户端等仍可留在既有业务架构中，
  不必为了 Agent 分层全部迁入 `agent/`。
- **按能力类型横向组织**：跨场景复用或同类实现较多时，可使用 `agent/tool/`、
  `agent/rail/`、`agent/memory/`、`agent/workflow/`。
- **保持最小结构**：只有一个 Definition 和一个 Tool 的最小服务可以直接放在 `agent/`；
  一旦同层类开始增长，或某组 Tool/Rail 明显属于同一业务场景，就应拆分子包，避免语义层根包变成杂物箱。
- **Custom REST 同样按内聚性组织**：它始终属于 `runtime/` 服务层，但二级目录可按协议放在
  `runtime/protocol/rest/`，也可按业务场景放在 `runtime/expense/rest/`；只有一个很小的适配器时
  可直接放在 `runtime/`。不得把 REST Controller、协议 DTO 或
  `CustomRestProtocolAdapter` 放入 `agent/`，也不要求无 Custom REST 的工程创建占位目录。

下面是**可选结构示意**，不是要求每个工程逐项创建的固定模板：

```text
src/main/java/<business-base-package-path>/
├── agent/
│   ├── <AgentName>Definition.java  # 最小结构时可直接放根包
│   ├── <business-scenario>/        # 可选方案 A：Definition 与相关 Tool / Rail 纵向聚合
│   ├── tool/                       # 可选方案 B：跨场景或同类 Tool 横向分组
│   └── rail/                       # 可选方案 B：跨场景或同类 Rail 横向分组
└── runtime/
    ├── <AgentName>RuntimeApplication.java
    ├── <AgentName>RuntimeConfiguration.java
    ├── config/                     # 可选：Spring 配置绑定
    └── protocol/rest/              # 可选：Custom REST 协议适配
src/main/resources/
└── application.yml
```

通用创建入口优先命名为 `<AgentName>Definition`；只有类型确实承担可重复创建、参数化构造职责时才命名为
`Factory`。如果语义层与 runtime 都需要配置类型，应区分纯 Java 的 `<AgentName>Options` /
`<AgentName>Spec` 与使用 Spring 绑定的 `<AgentName>RuntimeProperties`，不要让语义层配置反向依赖 Spring。

`<business-base-package-path>` 表示业务根包把 `.` 替换为 `/` 后的源码目录；
`<business-base-package>` 表示 Java `package`/组件扫描中使用的点分名称。两者都必须替换为
用户业务自己的命名，不是 Maven `groupId` 的别名，更不是要原样保留的占位符。例如：

```text
Maven groupId:       com.acme
业务根包:            com.acme.expense
语义层源码目录:      src/main/java/com/acme/expense/agent
语义层 package:      com.acme.expense.agent
服务层源码目录:      src/main/java/com/acme/expense/runtime
服务层 package:      com.acme.expense.runtime
资源目录:            src/main/resources
```

`com.openjiuwen.examples.*` **仅用于本 SPEC 示例自身的命名空间**。生成业务工程时不得沿用，
应按职责映射到业务根包。例如：

```text
SPEC 示例                                              业务工程
com.openjiuwen.examples.react.agent.ReactAgentDefinition  → com.acme.expense.agent.ExpenseAgentDefinition
com.openjiuwen.examples.react.runtime.ReactAgentApplication → com.acme.expense.runtime.ExpenseAgentApplication
```

复制或重命名后必须同步修改：

1. 所有 Java 文件的 `package`、`import` 和类型名；
2. `@SpringBootApplication(scanBasePackages = "<business-base-package>")`（Application 位于
   `runtime/` 子包时不要依赖默认扫描范围）；
3. POM 中的 `groupId`、`artifactId` 与 `spring-boot-maven-plugin.mainClass`；
4. YAML 中互相约束的 `spring.application.name`、`openjiuwen.service.agent-id` 及相关 Agent ID。

分层时，Application 与 runtime 装配类放入 `runtime/`；Agent 定义、Tool、Rail、DAG 构造等
放入 `agent/`；`application.yml` 等配置放入 `src/main/resources/`。如果参考的既有示例把
Agent 构造写在 `@Configuration` 内，应先抽取为 `agent/` 中的定义类，再由 `runtime/`
调用。`runtime/` 可以依赖 `agent/`，反向依赖禁止。一个服务托管多个 Agent 时，可在
`agent/` 下按 Agent 名或业务场景继续分子包，并在 `runtime/` 中按 Agent 或暴露协议拆分装配类；
不要为了追求统一外观而打散本来共同演进的一组业务 Tool/Rail。

“一个 `examples/<name>/` 目录 = 一个能力闭环”描述的是 **SPEC 的示例收录边界**，不表示
目录内部 Java 文件应平铺；包内细化为 `agent/`、`runtime/` 与 `resources/` 不会破坏能力闭环。
不要把 Java 文件放进 `src/main/resources/`，也不要把 YAML 放进 Java package。

> **可编译不等于环境就绪。** 编译门禁验证公开类型、方法签名和依赖闭包；真实启动还依赖
> LLM 凭据、网络、Redis/SkillHub/远端 Agent 等环境条件，应继续执行对应 how-to 的
> 「端到端校验」。

## 共享最小 POM

**[minimal-agent-service-pom.xml](minimal-agent-service-pom.xml)** 是三类基础 Agent
（react / deepagent / workflow）共享的工程基线，已用推荐发布件完成 `mvn package` 与
fat jar 启动验证。复制为目标工程的 `pom.xml` 后改三处：`groupId` / `artifactId` /
`mainClass`；Versatile 对接按「目录 → artifact 映射」**替换** agentcore adapter，
SkillHub / custom-rest / A2A 自动注入等叠加能力在该映射中追加 artifact。

它固化了四个容易推错的构建契约：Spring Boot parent 版本、runtime 两个 artifact 的
直接声明（`agent-core-java` 经 adapter 传递引入，**不要直接声明**）、
`spring-boot-maven-plugin`（缺失时编译正常但 `java -jar` 失败）、版本配对。

```bash
mvn -DskipTests package
java -jar target/<artifactId>-<version>.jar
```

> **编译、启动、真实 LLM 调用是三个不同门禁**：`package` 通过只证明类型与依赖闭包
> 正确；fat jar 启动成功只证明装配与 web 栈就绪；真实对话仍需 LLM 凭据与网络，
> 按各 how-to「端到端校验」执行。

## 示例索引

| 目录 | 能力闭环 | 引用它的 how-to |
| --- | --- | --- |
| [workflow/](workflow/) | WorkflowAgent DAG：LLM 结构化输出 → 分支 → HITL/自动收尾 → 托管 + A2A 暴露 | [../how-to/workflow-agent.md](../how-to/workflow-agent.md) |
| [versatile/](versatile/) | VersatileAgentHandler：远端 versatile 工作流包成 Agent（含中断翻译配置） | [../how-to/versatile-agent.md](../how-to/versatile-agent.md) |
| [react/](react/) | ReActAgent：推理循环 + 本地工具两步注册 → 托管 + A2A 暴露；同时示范标准 `agent/runtime/resources` 分层 | [../how-to/react-agent.md](../how-to/react-agent.md) |
| [deepagent/](deepagent/) | DeepAgent：TaskCompletionRail 任务循环 + 受限工作区文件工具 → 托管 + A2A 暴露 | [../how-to/deepagent.md](../how-to/deepagent.md) |

> 各类型共享 [配置驱动 Agent](../how-to/config-driven-agent.md) 中的 Runner / runtime
> 托管契约；类型构造细节与 solution 增量能力仍以各自指南为准。

## 目录 → artifact 映射（版本一律从 compatibility.md 速查表读取）

| 目录 | 需要引入的 artifact | 说明 |
| --- | --- | --- |
| workflow/ | `agent-service-app` + `agent-service-adapters-agentcore` | adapter 传递引入 agent-core-java，含中间件自动配置 |
| react/ | `agent-service-app` + `agent-service-adapters-agentcore` | 同上 |
| deepagent/ | `agent-service-app` + `agent-service-adapters-agentcore` | 同上 |
| versatile/ | `agent-service-app` + `agent-service-adapters-versatile` | VersatileAgentHandler 所在 adapter；**替换** agentcore adapter（非叠加），仅同一服务托管两类 Handler 时两者并存 |
| 叠加：远端 A2A 工具注入 | 追加 `agent-service-adapters-agentcore-ext` | 仅主控侧需要自动注入时（见 [../how-to/a2a.md](../how-to/a2a.md)） |
| 叠加：SkillHub 技能注入 | 追加 `agent-service-adapters-agentcore-ext`（+ `agent-service-spec-ext` 自定义 Provider 时） | 见 [../how-to/skillhub.md](../how-to/skillhub.md) |
| 叠加：自定义 REST 入口 | 追加 `agent-service-app-custom-rest` | 见 [../how-to/custom-rest.md](../how-to/custom-rest.md) |
