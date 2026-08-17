# openJiuwen 开发指导手册

> 本文档给出基于 openJiuwen 框架做二次开发的**最优实现方式**。
> 每个章节标注核心仓库来源，附带正确/错误对照示例及理由。
>
> 适用仓库：`agent-core-java` / `agent-runtime-java` / `agent-solution`

---

## 1. Agent 引擎

> 核心仓库：**agent-core-java**

Agent 是框架的核心执行单元，负责接收用户输入、调用 LLM 推理、执行工具调用、返回结果。框架提供三种 Agent 类型，分别适用于不同任务场景。

### 1.1 ReActAgent

ReActAgent 基于 Reasoning-Acting 循环，每一轮迭代由 LLM 自主决定调用哪个工具，适合开放式任务——你不知道 Agent 会走几步、调什么工具，让 LLM 自由决策。

**✅ 推荐写法**

```java
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

// 1) 构建配置
ReActAgentConfig agentConfig = ReActAgentConfig.builder()
    .promptTemplate(List.of(Map.of("role", "system", "content", "你是理财助手...")))
    .maxIterations(10)
    .build()
    .configureModelClient("deepseek", "sk-xxx", "https://api.deepseek.com", "deepseek-chat", true);

// 2) 创建 Agent
AgentCard card = AgentCard.builder()
    .id("my-agent").name("助手").description("理财顾问").build();
ReActAgent agent = new ReActAgent(card);
agent.configure(agentConfig);
```

**❌ 不要这样**

```java
// 凭空 new，不调 configure — 模型未配置，运行时 NPE
ReActAgent agent = new ReActAgent(null);
```

**💡 为什么这是推荐的**

1. **ReActAgent 没有工厂类** — 框架设计如此，`new + configure` 就是唯一的官方创建路径（参考 `agent-core-java` 源码，不存在 `ReActAgentFactory`）
2. **`configure()` 是必需的** — 它负责绑定模型客户端、上下文引擎、工具注册表。跳过它 Agent 无法工作
3. **AgentCard 不是可选的** — 它决定 Agent 的唯一标识和 A2A 协议中的对外信息，传 null 会导致后续注册/发现环节报错

---

### 1.2 DeepAgent

DeepAgent 在 ReActAgent 基础上叠加了 TaskLoop 多任务编排、6 种内置 SubAgent 委派、Workspace 文件管理。适合复杂多步骤任务——比如"研究一个课题并生成报告"，LLM 会把任务拆解成子任务、委派给不同 SubAgent 并行执行。

**✅ 推荐写法**

```java
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;

// 1) 配置
DeepAgentConfig config = DeepAgentConfig.builder()
    .systemPrompt("你是深度研究助手...")
    .maxIterations(15)
    .language("zh-CN")
    .workspacePath("target/agents/my-agent")
    .model(model)
    .enableTaskLoop(true)
    .restrictToWorkDir(true)
    .rails(List.of(myRail))       // 可选：注册 Rail
    .build();

Workspace workspace = Workspace.builder()
    .rootPath("target/agents/my-agent")
    .language("zh-CN")
    .build();

AgentCard card = AgentCard.builder()
    .id("deep-agent").name("研究员").build();

// 2) 工厂创建
DeepAgent agent = HarnessFactory.createDeepAgent(card, config, workspace);
```

**❌ 不要这样**

```java
// 直接 new DeepAgent — 跳过了 Workspace 初始化、SubAgent 注册等内部装配
DeepAgent agent = new DeepAgent(card, config);
```

**💡 为什么这是推荐的**

1. **`HarnessFactory` 是官方工厂** — 来自 `agent-core-java` 的 `harness.factory.HarnessFactory`，不是 Demo 便利类
2. **`createDeepAgent()` 内部做了复杂装配** — Workspace 目录初始化、SubAgent（plan/explore/research/code/verify/browser）自动注册、TaskLoop 调度器初始化。直接 `new` 会跳过全部，功能残缺
3. **代码自文档化** — 看到 `HarnessFactory.createDeepAgent` 就知道在创建一个完整装配的 DeepAgent，而不是半成品

---

### 1.3 WorkflowAgent

WorkflowAgent 是确定性流程编排型 Agent——通过 `Workflow` DAG 预定义步骤顺序，不依赖 LLM 自主决策走哪条路。适合固定流程场景，如"先校验输入 → 再调用查询 → 最后格式化输出"，每步输入输出可审计。

**✅ 推荐写法**

```java
import com.openjiuwen.core.application.workflow.WorkflowAgent;
import com.openjiuwen.core.application.schema.WorkflowAgentConfig;
import com.openjiuwen.core.application.schema.WorkflowSchema;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.component.Start;
import com.openjiuwen.core.workflow.component.End;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 1) 构建 WorkflowSchema 元数据（声明 Agent 对外暴露哪些 workflow）
WorkflowSchema schema = WorkflowSchema.builder()
    .id("pipeline")
    .name("pipeline")
    .version("1.0")
    .description("固定流程：校验 → 查询 → 格式化")
    .build();

// 2) 构建 WorkflowAgentConfig（workflows 列表必须可变）
WorkflowAgentConfig config = WorkflowAgentConfig.builder()
    .id("pipeline")
    .description("固定流程编排 Agent")
    .workflows(new ArrayList<>(List.of(schema)))   // ★ 必须可变，否则 UnsupportedOperationException
    .build();

// 3) 创建 WorkflowAgent —— 唯一构造器接收 WorkflowAgentConfig
WorkflowAgent agent = new WorkflowAgent(config);

// 4) 构建实际 Workflow DAG（setStartComp / addWorkflowComp / setEndComp 链式装配）
Workflow workflow = new Workflow();
workflow.setStartComp("start", new Start(), Map.of("query", "${query}"), null);
workflow.addWorkflowComp("tool", toolComponent, Map.of("input", "${start.query}"), null);
workflow.setEndComp("end", new End(), Map.of("answer", "${tool.output}"), null);
workflow.addConnection("start", "tool");
workflow.addConnection("tool", "end");

// 5) 将 Workflow 添加到 Agent（列表必须可变）
agent.addWorkflows(new ArrayList<>(List.of(workflow)));
```

**💡 为什么这是推荐的**

1. **构造器只收 WorkflowAgentConfig** — `WorkflowAgent` 唯一构造器是 `WorkflowAgent(WorkflowAgentConfig)`，不存在 `(AgentCard, List<Workflow>)` 重载；workflow 通过 `addWorkflows()` 事后注入
2. **WorkflowAgentConfig 用 Builder 组装** — `@Builder` 提供 `id/description/workflows` 等字段；`workflows` 必须传可变集合（`new ArrayList<>(...)`），否则 `addWorkflows()` 内部 add 时报 `UnsupportedOperationException`
3. **Workflow 用装配 API，不用 WorkflowSpec** — `WorkflowSpec` 无 Builder；`Workflow` 通过 `setStartComp` / `addWorkflowComp` / `setEndComp` / `addConnection` 链式装配 DAG

---

## 2. Agent 服务化

> 核心仓库：**agent-runtime-java**

将 Agent 引擎包装为 Spring Boot HTTP 服务，对外暴露 A2A 协议接口（JSON-RPC + SSE 流式），实现生产级部署。通过 `AgentHandler` SPI 将 Agent 实例注册到框架的 HTTP 编排链路中。

### 2.1 注册 AgentHandler

`AgentHandler` 是服务层的核心 SPI，负责将 HTTP 请求路由到 Agent 引擎。框架提供标准实现 `JiuwenCoreAgentHandler`，封装了完整的生命周期管理。

**✅ 推荐写法（方式 A：@Bean 手动装配）**

```java
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
public class MyAgentApplication {

    @Bean
    AgentHandler agentHandler(LlmConfigResolver llmConfigResolver) {
        // 1) 解析 LLM 配置（来自 apiconfig.json 或环境变量）
        ResolvedLlmConfig llmConfig = llmConfigResolver.resolveRequired();

        // 2) 创建 Agent 引擎
        ReActAgent agent = ExampleReActAgentFactory.build(
            "my-agent", "助手", "描述", llmConfig);

        // 3) 包装为 Handler
        return new JiuwenCoreAgentHandler(agent);
    }

    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }
}
```

**✅ 也可用方式 B（yaml 配置，适合标准化部署）**

```yaml
openjiuwen:
  service:
    agent-id: my-agent
    handler: agentcore
```

此时框架自动装配 `JiuwenCoreAgentHandler`，无需写 Java 代码。

**❌ 不要这样**

```java
// 自己 implements AgentHandler 从零写——
// 你需要自己管理 Runner.start/stop/query/stream/release 的全部生命周期
public class MyAgentHandler implements AgentHandler {
    @Override
    public QueryResponse query(ServeRequest request) {
        // 你要自己调 Runner，自己处理会话，自己管理线程...
    }
}
```

**💡 为什么这是推荐的**

1. **`JiuwenCoreAgentHandler` 已经封装了全部生命周期** — `start()` 时自动注册中间件和外部服务到 `RunnerConfig`，`stop()` 时自动释放资源，`clearSession()` 自动清理 Checkpointer 状态。自己写需要 200+ 行
2. **框架自动装配 Handler 时只接受一个 `@Bean`** — 自己写 `implements AgentHandler` 会和 `@Bean JiuwenCoreAgentHandler` 冲突，需要 `@Primary` 等额外配置
3. **唯一例外**：接入非 openjiuwen 引擎（如 AgentScope）时才需要自己实现 `AgentHandler`（参考 `agent-solution` 的 `AgentScopeAgentHandler`）

---

## 3. Agent 能力扩展

> 引擎侧 API：**agent-core-java**
> 客户端工厂：**agent-runtime-java**

Agent 通过各种工具扩展能力边界。工具分为两类：本地自定义工具（`ToolCard` + `LocalFunction`）和外部服务客户端（Sandbox 代码执行、MCP 协议工具、Remote 远端调用）。外部服务客户端必须通过工厂创建以注入横切能力。

### 3.1 外部服务客户端 — 一律走工厂

Sandbox / MCP / Remote 三类客户端，框架提供对应工厂。工厂负责注入超时配置、重试策略、熔断器和审计日志，直接 `new` 拿到的裸客户端不含这些能力。

**✅ 推荐写法**

```java
// 沙箱客户端（来自 agent-runtime-java 的 AgentCoreSandboxClientFactory）
@Bean
AgentHandler agentHandler(
        LlmConfigResolver llmConfigResolver,
        ObjectProvider<AgentCoreSandboxClientFactory> sandboxFactoryProvider) {

    ReActAgent agent = ExampleReActAgentFactory.build("id", "name", "desc", llmConfig);

    // 通过工厂创建客户端 — 自动装配超时/重试/熔断
    sandboxFactoryProvider.ifAvailable(factory -> {
        SandboxClient sandbox = factory.create("default");
        DecoratedSandboxToolRegistrar.register(agent, factory);
    });

    return new JiuwenCoreAgentHandler(agent);
}
```

**❌ 不要这样**

```java
// 直接 new SandboxClient — 缺少超时、重试、熔断、审计等横切能力
SandboxClient sandbox = new SandboxClient("http://localhost:8321");
```

**💡 为什么这是推荐的**

1. **工厂负责注入横切能力** — `AgentCoreSandboxClientFactory.create()` 返回的是经过 `DecoratingSandboxClient` 包装的客户端，自动附加超时配置、重试策略、熔断器和审计日志。直接 `new` 拿裸客户端缺少全部
2. **配置与代码分离** — 工厂从 `openjiuwen.service.external.sandbox` yaml 读取配置，改环境无需改代码
3. **MCP/Remote 同样适用** — `AgentCoreMcpClientDecoratorFactory`、`AgentCoreRemoteClientFactory` 遵循相同模式

---

### 3.2 自定义工具注册

本地工具通过 `ToolCard`（描述元数据）和 `LocalFunction`（执行体）两个组件定义，注册时分两步——元数据写 AbilityManager、执行体写 ResourceMgr。

> 引擎侧注册 API 来自 **agent-core-java**

**✅ 推荐写法**

```java
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.LocalFunction;

// 1) 定义工具描述（LLM 可见）
ToolCard card = ToolCard.builder()
    .id("hotel_search")
    .name("hotel_search")
    .description("搜索酒店")
    .inputParams(Map.of(
        "type", "object",
        "properties", Map.of("city", Map.of("type", "string", "description", "城市名")),
        "required", List.of("city")
    ))
    .build();

// 2) 定义执行逻辑
LocalFunction tool = new LocalFunction(card, inputs -> {
    String city = (String) inputs.get("city");
    List<Hotel> hotels = hotelService.search(city);
    return Map.of("hotels", hotels);
});

// 3) 注册到 Agent（两个步骤缺一不可）
agent.getAbilityManager().add(tool.getCard());              // 让 LLM 知道有这个工具
Runner.resourceMgr().addTool(tool, agentId, true);          // 绑定执行函数
```

**❌ 不要这样**

```java
// 只注册到 AbilityManager，忘记 addTool — LLM 会调但这个工具但找不到执行函数，运行时抛异常
agent.getAbilityManager().add(card);
```

**💡 为什么分两步**

1. `getAbilityManager().add()` — 把工具的 **元数据**（名称、描述、参数 schema）写入 Agent 的 tool manifest，影响 LLM 的 tool_choice
2. `Runner.resourceMgr().addTool()` — 把工具的 **执行体**（`LocalFunction`）绑定到 Agent，影响运行时调度
3. 这两者解耦，允许同一个工具描述被不同执行体替换（如切换 sandbox / remote 后端）

---

## 4. Rail 拦截器

> 基类来自：**agent-core-java**
> 业务示范来自：**agent-solution**（edp-agent-java）

Rail 是 Agent 推理循环中的拦截器链，在工具调用前后插入自定义逻辑。适用于日志、权限校验、中断控制、限流等横切关注点。多个 Rail 可组合、按优先级排序、可跨 Agent 复用。

### 4.1 通用 Rail — 拦截工具调用

继承 `AgentRail`，重写 `onBeforeToolCall` / `onAfterToolCall` 等方法。框架在每次工具调用前后依次回调注册的 Rail 链。

**✅ 推荐写法**

```java
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.rail.RailDecision;

/**
 * 记录每次工具调用的耗时和参数。
 */
public class LogRail extends AgentRail {

    public LogRail() {
        setPriority(100);          // 越低越先执行
    }

    @Override
    public RailDecision onBeforeToolCall(AgentCallbackContext ctx, ToolCallInputs inputs) {
        log.info("工具调用开始: {} 参数: {}", inputs.getToolName(), inputs.getArguments());
        return RailDecision.proceed();    // 放行
    }

    @Override
    public RailDecision onAfterToolCall(AgentCallbackContext ctx, ToolCallInputs inputs, Object result) {
        log.info("工具调用结束: {} 耗时: {}ms", inputs.getToolName(), ctx.getElapsedMs());
        return RailDecision.proceed();
    }
}

// 注册
agent.registerRail(new LogRail());
// 或 DeepAgent 用: DeepAgentConfig.builder().rails(List.of(new LogRail()))
```

### 4.2 中断 Rail — 需要用户输入时暂停

**✅ 推荐写法**

```java
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.core.singleagent.interrupt.InterruptDecision;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;

/**
 * 当 LLM 调用 ask_user 工具时，暂停并等待用户输入。
 */
public class AskUserTemplateRail extends BaseInterruptRail {

    public AskUserTemplateRail() {
        super(List.of("ask_user"));       // 声明拦截的工具名
    }

    @Override
    protected InterruptDecision resolveInterrupt(
            AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {

        if (resumeInput != null) {
            // 用户已输入 → 恢复执行
            return reject(resumeInput);
        }

        // 发起中断
        InterruptRequest req = InterruptRequest.builder()
            .message(toolCall.getArguments().get("question").toString())
            .build();
        return interrupt(req);
    }
}
```

**❌ 不要这样**

```java
// 在 Handler 层用 if-else 判断工具名然后硬编码中断逻辑
public class MyAgentHandler implements AgentHandler {
    public QueryResponse query(ServeRequest req) {
        if (req.getMessage().contains("问用户")) {
            return new QueryResponse(/* 手动构造中断响应 */);
        }
        // ... 散落的业务判断
    }
}
```

**💡 为什么推荐 Rail 模式**

1. **关注点分离** — Rail 只负责"拦截什么 + 怎么处理"，Agent 引擎专注于推理循环。Handler 层不知道工具的存在
2. **可组合** — 一个 Agent 可以注册多个 Rail（日志 + 权限 + 中断 + 限流），框架按时序链式调用
3. **可复用** — `AskUserTemplateRail` 写一次，所有 Agent 都可以用 `agent.registerRail(new AskUserTemplateRail())`
4. **优先级控制** — `setPriority()` 决定执行顺序，不需要在 Handler 里维护 if-else 顺序
5. **`BaseInterruptRail` 封装了中断/恢复状态机** — 你只需实现 `resolveInterrupt()`，框架自动处理 `ToolInterruptException` → `commitInterrupt` → `loadInterruptionState` → `resume` 的完整链路

---

## 5. Agent 装配模式

> 示范来自：**agent-solution**（edp-agent-java 的 EdpaAgentEnhancer）

业务 Agent 通常需要注册大量工具和 Rail。增强器（Enhancer）模式提供集中装配入口——将所有工具和 Rail 的注册逻辑集中到一个类的 `enhance()` 方法中，避免散落在 Handler 各处。

### 5.1 集中注册，不散落

**✅ 推荐写法**

```java
/**
 * 我的业务 Agent 增强器：集中注册业务工具和 Rails。
 */
public class MyAgentEnhancer {

    public void enhance(DeepAgent agent, MyConfig config) {
        // 1) 注册业务工具
        List<Tool> tools = buildTools(config);
        for (Tool tool : tools) {
            agent.getAbilityManager().add(tool.getCard());
            Runner.resourceMgr().addTool(tool, agent.getCard().getId(), true);
        }

        // 2) 注册业务 Rails
        List<AgentRail> rails = buildRails(config);
        for (AgentRail rail : rails) {
            agent.registerRail(rail);
        }
    }

    private List<Tool> buildTools(MyConfig config) {
        // 集中定义全部业务工具的 ToolCard + LocalFunction
        return List.of(
            createHotelSearchTool(config),
            createFlightSearchTool(config),
            createOrderQueryTool(config)
        );
    }

    private List<AgentRail> buildRails(MyConfig config) {
        return List.of(
            new LogRail(),
            new ExecutionLimitRail(50),
            new AskUserTemplateRail()
        );
    }
}

// 在 @Bean Handler 中调用
@Bean
AgentHandler agentHandler(...) {
    DeepAgent agent = HarnessFactory.createDeepAgent(card, config, workspace);
    new MyAgentEnhancer().enhance(agent, myConfig);     // 一行搞定
    return new JiuwenCoreAgentHandler(agent);
}
```

**❌ 不要这样**

```java
@Bean
AgentHandler agentHandler() {
    DeepAgent agent = HarnessFactory.createDeepAgent(...);

    // 工具注册散落在各处
    agent.getAbilityManager().add(hotelCard);
    Runner.resourceMgr().addTool(hotelTool, agentId, true);

    agent.getAbilityManager().add(flightCard);          // 漏了 addTool
    // ... 50 行后
    agent.registerRail(new LogRail());
    // ... 100 行后
    agent.registerRail(new ExecutionLimitRail(50));
    // 新增一个工具要找半天该插哪

    return new JiuwenCoreAgentHandler(agent);
}
```

**💡 为什么推荐 Enhancer 模式**

1. **单一入口** — `enhance()` 一行调用即完成全部装配，代码结构清晰
2. **可测试** — `buildTools()` 和 `buildRails()` 方法可独立单测，验证工具描述、Rail 行为
3. **配置驱动** — Enhancer 接收 `MyConfig` 对象，可以从 yaml 配置驱动工具行为，不改代码
4. **不会漏注册** — 工具在 `buildTools()` 中集中定义，不会出现"加了 ToolCard 忘记 `addTool`"的半注册状态

---

## 6. 生命周期钩子

> SPI 定义来自：**agent-runtime-java**
> 业务示范来自：**agent-solution**（edp-agent-java 的 SandboxInitHook / SandboxShutdownHook）

框架通过 SPI 提供 Agent 服务生命周期的扩展点，在启动/关闭阶段回调注册的钩子。与 Spring 的 `@PostConstruct`/`@PreDestroy` 不同，钩子的执行时序与 `AgentHandler.start()/stop()` 严格对齐。

**✅ 推荐写法**

```java
import com.openjiuwen.service.spec.lifecycle.AgentInitHook;
import com.openjiuwen.service.spec.lifecycle.AgentLifecycleContext;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
@Order(1)                  // 数值越小越先执行，沙箱初始化通常放前面
public class SandboxInitHook implements AgentInitHook {

    @Override
    public void onInit(AgentLifecycleContext context) throws Exception {
        // 在 AgentHandler.start() 之前执行
        // 适合：预热连接池、加载缓存、初始化外部资源
        sandboxClient.ping();
        log.info("沙箱连接就绪");
    }
}

@Component
@Order(100)                // 关闭时后执行
public class SandboxShutdownHook implements AgentShutdownHook {

    @Override
    public void onShutdown(AgentLifecycleContext context) {
        sandboxClient.close();
        log.info("沙箱连接已关闭");
    }
}
```

**❌ 不要这样**

```java
@Component
public class MyService {
    @PostConstruct
    public void init() {
        // 用 Spring 注解管理 Agent 生命周期 — 可能在 AgentHandler 还没 start 时就执行
        sandboxClient.ping();
    }

    @PreDestroy
    public void destroy() {
        // 可能在 AgentHandler 还没 stop 时就关闭资源，导致正在处理的请求失败
        sandboxClient.close();
    }
}
```

**💡 为什么推荐 Hook 而非 `@PostConstruct`/`@PreDestroy`**

1. **时序保证** — `AgentInitHook` 在 `AgentHandler.start()` 之前执行，`AgentShutdownHook` 在 `AgentHandler.stop()` 之后执行。Spring 的 `@PostConstruct` 时序不确定
2. **框架感知** — Hook 有 `AgentLifecycleContext` 参数，可以访问 `AgentHandler` 实例和运行状态
3. **可排序** — `@Order` 控制多个 Hook 的执行顺序，`@PostConstruct` 的顺序依赖 Spring 容器内部逻辑
4. **统一的错误处理** — `onInit()` 抛异常时框架根据 `init-fail-fast` 配置决定是否阻止启动，`@PostConstruct` 抛异常直接导致容器启动失败

---

## 7. 异构引擎接入

> 全部来自：**agent-solution**（agent-runtime-ext-java）

支持将非 openjiuwen 原生引擎（低码平台 Versatile 工作流、AgentScope 框架）接入到 A2A 协议体系中。通过实现 `AgentHandler` SPI 并注册为 Spring Bean 完成适配。

### 7.1 Versatile 工作流代理

```java
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;

@Bean
AgentHandler agentHandler() {
    VersatileProperties props = new VersatileProperties();
    props.setUrlTemplate("http://workflow:31113/v1/conversations/{conversation_id}");
    props.setTimeout(Duration.ofSeconds(600));
    props.setResultNodeName("GXZQAResponseNode");
    props.setAmbiguousIntentId("");        // ⚠️ 关键：手动构造必须清空默认值"1"

    return new VersatileAgentHandler(props);
}
```

**⚠️ 关键陷阱**：手动 `new VersatileProperties()` 时 `ambiguousIntentId` 默认值是 `"1"`，会导致所有 `intent_id=1` 的请求被误判为模糊意图。Spring yaml 绑定时自动覆盖，但代码中 `new` 时必须显式设为 `""`。

### 7.2 SkillHub 接入

```java
import com.openjiuwen.service.spec.ext.skillhub.spi.SkillHubProvider;
import com.openjiuwen.service.spec.ext.skillhub.SkillHubConfig;

import java.nio.file.Path;

@Component
public class MySkillHubProvider implements SkillHubProvider {
    @Override
    public void start(SkillHubConfig config, String decryptedToken) {
        // 建立连接池、预热鉴权（token 已是明文，勿打印/持久化）
    }

    @Override
    public boolean download(SkillHubConfig config, String decryptedToken) {
        // 下载技能到 config.getLocalDir()，返回是否全部成功
        return true;
    }

    @Override
    public boolean verify(Path skillPath) {
        // 校验已下载技能的完整性（SHA-256 / 文件校验均可）
        return true;
    }

    @Override
    public void stop() {
        // 关闭连接池、释放资源
    }
}
```

**⚠️ SkillHubProvider 只有 4 个方法**：`start` / `download` / `verify` / `stop`（无 `loadSkills` / `refresh`）。`start`/`stop` 由 `SkillHubManager` 在自身生命周期中调用，`download`/`verify` 由 manager 编排。

---

## 8. SubAgent 体系

> 核心仓库：**agent-core-java**

DeepAgent 内置 6 种预置 SubAgent，各自配备专用工厂和内置工具。DeepAgent 在运行时按需将子任务委派给对应 SubAgent 执行。

### 8.1 六种 SubAgent 及工厂

| SubAgent | 工厂 | 能力 |
|----------|------|------|
| Code Agent | `CodeAgentFactory.createCodeAgent(language, workspace)` | 代码编写/修改/调试 |
| Explore Agent | `ExploreAgentFactory.createExploreAgent(language, workspace)` | 文件系统探索 |
| Plan Agent | `PlanAgentFactory.createPlanAgent(language, workspace)` | 任务规划/分解 |
| Research Agent | `ResearchAgentFactory.createResearchAgent(language, workspace)` | 信息检索/汇总 |
| Verification Agent | `VerificationAgentFactory.createVerificationAgent(language, workspace)` | 结果校验 |
| Browser Agent | `BrowserAgentFactory.createBrowserAgent(settings, language, workspace, headless)` | 网页浏览 |

全部工厂位于 `com.openjiuwen.harness.subagents` 包。

**✅ 推荐写法**

```java
import com.openjiuwen.harness.subagents.CodeAgentFactory;
import com.openjiuwen.harness.subagents.ResearchAgentFactory;
import com.openjiuwen.harness.factory.HarnessFactory;

Workspace workspace = Workspace.builder()
    .rootPath("target/agents/my-agent")
    .language("zh-CN")
    .build();

// 主 Agent（自动注册全部 6 种 SubAgent）
DeepAgent agent = HarnessFactory.createDeepAgent(card, config, workspace);
// HarnessFactory 内部会自动调用各 SubAgentFactory 注册

// 如果只想注册特定 SubAgent（用重载版本）：
DeepAgent agent2 = HarnessFactory.createDeepAgent(card, config, workspace,
    List.of(CodeAgentFactory.createCodeAgent("zh-CN", workspace),
            ResearchAgentFactory.createResearchAgent("zh-CN", workspace)));
```

**❌ 不要这样**

```java
// 自己 new SubAgent，缺少 Rail/工具注册
DeepAgent codeAgent = new DeepAgent(card, config);
// 然后手动注册到主 Agent — 顺序、优先级、Rail 管够不全
```

**💡 为什么这是推荐的**

1. **SubAgent 不是裸 DeepAgent** — 每个工厂内部做了三件事：创建 AgentCard + 绑定专用工具（读写文件/搜索/校验）+ 注册专用 Rail（权限/日志）。自己 `new` 拿不到这些
2. **HarnessFactory 已经集成** — `createDeepAgent()` 全自动注册 6 种 SubAgent，你不需要手动管理
3. **可裁剪** — 重载版本接受 `List<DeepAgent>` 参数，只注册需要的

---

## 9. 存储抽象层

> 核心仓库：**agent-core-java**

框架通过 Provider + Factory 模式抽象向量存储、KV 存储、对象存储三层 SPI。业务代码只依赖 `VectorStore`/`KVStore`/`ObjectStorage` 接口，切换后端只需改配置，不动代码。

### 9.1 三种存储 SPI

| 存储类型 | SPI 接口 | 工厂 | 内置实现 |
|---------|---------|------|---------|
| 向量存储 | `VectorStoreProvider` | `VectorStoreFactory` | InMemory / Milvus / PGVector / Elasticsearch / Chroma |
| KV 存储 | `KVStoreProvider` | `KVStoreFactory` | InMemoryKVStore |
| 对象存储 | `ObjectStorageProvider` | `ObjectStorageFactory` | BaseObjectStorageClient |

全部位于 `com.openjiuwen.spi.store` 包。

**✅ 推荐写法（检索层 — 业务代码依赖这一层）**

```java
import com.openjiuwen.core.retrieval.vector_store.VectorStore;
import com.openjiuwen.core.retrieval.vector_store.VectorStoreFactory;
import com.openjiuwen.core.retrieval.common.VectorStoreConfig;
import com.openjiuwen.core.retrieval.common.SearchResult;

// 1) 配置（storeProvider + collectionName）
VectorStoreConfig config = new VectorStoreConfig("milvus", "my_vectors");

// 2) 工厂创建 — 按 storeType 路由到 Milvus/Chroma/PGVector/Elasticsearch
VectorStore store = VectorStoreFactory.createVectorStore(config);

// 3) 写入（方法名是 add，不是 insert）
store.add(List.of(Map.of("id", "doc-1", "vector", List.of(0.1f, 0.2f, 0.3f))), 100, Map.of());

// 4) 检索（search 返回 List<SearchResult>，不是 VectorSearchResult）
List<SearchResult> results = store.search(List.of(0.1f, 0.2f, 0.3f), 10, Map.of(), Map.of());
```

**✅ 推荐写法（底层 SPI — 扩展新存储后端时才实现这一层）**

```java
import com.openjiuwen.spi.store.vector.VectorStoreFactory;
import com.openjiuwen.spi.store.vector.BaseVectorStore;
import com.openjiuwen.spi.store.vector.VectorSearchResult;

// 底层工厂 create 返回 BaseVectorStore（不是检索层 VectorStore）
BaseVectorStore store = VectorStoreFactory.create("milvus", Map.of("uri", "http://localhost:19530"));

// addDocs / search 的签名与检索层不同
store.addDocs("my_vectors", docs, Map.of());
List<VectorSearchResult> results = store.search("my_vectors", queryVector, "vector_field", 10, Map.of(), Map.of());
```

> ⚠️ **两层务必区分**：`spi.store.vector.VectorStoreFactory.create(type, conf)` 返回底层 `BaseVectorStore`（`addDocs` / `search` 返回 `VectorSearchResult`）；`core.retrieval.vector_store.VectorStoreFactory.createVectorStore(config)` 返回检索层 `VectorStore`（`add` / `search` 返回 `SearchResult`）。业务代码依赖检索层，扩展新后端才实现底层 SPI。

**❌ 不要这样**

```java
// 直接 new Milvus 客户端 — 绕过 SPI，无法通过配置切换后端
MilvusClient client = new MilvusClient("localhost", 19530);

// 也不要混用：把底层 create(...) 的返回值赋给检索层 VectorStore 类型
VectorStore store = VectorStoreFactory.create("milvus", config); // ❌ 类型不匹配，create 返回 BaseVectorStore
```

**💡 为什么这是推荐的**

1. **两层解耦** — 底层 `VectorStoreProvider.typeName()` + `create(conf)` 由 `VectorStoreFactory` 用 ServiceLoader 发现并注册；检索层 `createVectorStore(VectorStoreConfig)` 按 `storeType` 路由。切换向量库只需改 `storeProvider`，不碰业务代码
2. **统一接口** — 检索层工厂返回 `core.retrieval.vector_store.VectorStore` 接口，业务代码只依赖接口，不感知底层实现
3. **同样适用于 KV/Object** — 检索层另有 `KVStore` / `ObjectStorage` 抽象，底层 `spi.store` 是扩展点，模式一致

---

## 10. 会话持久化

> 核心仓库：**agent-runtime-java**

Checkpointer 负责 Agent 会话状态的快照持久化，是生产环境中中断/恢复机制的基石。支持 InMemory（开发期）和 Redis（生产）两种后端，纯 yaml 配置，零代码接入。

### 10.1 三种后端

| 后端 | type 配置 | 适用场景 |
|------|----------|---------|
| InMemory | `in_memory` | 开发/测试（JVM 内存，不序列化，重启丢失） |
| Redis | `redis` | 生产（持久化，7 天 TTL，跨请求恢复） |
| Persistence | `persistence` | 自定义持久化（需实现 `KVStoreProvider`） |

**✅ 推荐写法（Redis 后端）**

```yaml
openjiuwen:
  service:
    middleware:
      checkpointer:
        type: redis
        ttl-seconds: 604800        # 7 天
        redis-ref: default
      redis:
        default:
          type: standalone
          host: 127.0.0.1
          port: 6379
          database: 0
          timeout-ms: 3000
          encrypted-password: ""   # 经 CredentialDecryptor 解密
```

**✅ 也可用集群模式**

```yaml
openjiuwen:
  service:
    middleware:
      checkpointer:
        type: redis
        redis-ref: cluster
      redis:
        cluster:
          type: cluster
          nodes:
            - 10.10.1.11:6379
            - 10.10.1.12:6379
          timeout-ms: 3000
```

**💡 为什么这是推荐的（以及关键行为）**

1. **纯配置，零代码** — 不需要写一行 Java。框架在 `AgentHandler.start()` 时自动组装 `CheckpointerConfig` → `CheckpointerFactory.create()` → 注册到 `RunnerConfig`
2. **中断恢复自动完成** — Agent 执行中触发中断时，Checkpointer 自动保存快照到 Redis（Key: `{sessionId}:agent:{agentId}:agent_state_blobs`）。下一次请求时自动检测并恢复
3. **Redis Key 生命周期** — 默认 TTL 7 天，`refresh_on_read=false`（读不刷新）。正常完成时 `postAgentExecute` 自动清理 key，避免残留
4. **⚠️ InMemory 的局限** — 仅适合单机开发。不序列化到外部存储，进程重启全丢，不支持跨请求恢复。上生产必须切 Redis

---

## 11. A2A 远程调用

> 核心仓库：**agent-runtime-java**

框架通过 `RemoteAgentCaller` + `RemoteAgentCardResolver` 两个 SPI 支持 Agent 间互相发现与调用，实现多 Agent 编排。远端 Agent 通过 yaml 声明式配置，框架自动完成卡发现、工具注册、调用路由和中断透传。

### 11.1 架构

```
Agent A                       Agent B
  │                              │
  │ LLM 决定调 delegate 工具      │
  ▼                              │
Rail 拦截 ──► InterruptRequest   │
  │           {agentName: "b"}    │
  ▼                              │
A2AEnabledServeOrchestrator      │
  │                              │
  ├─ RemoteAgentCardResolver     │
  │   .resolveJsonRpcUrl("b")    │
  │                              │
  ├─ RemoteAgentCaller           │
  │   .callOutcome(...)  ──────►  POST /a2a/ (JSON-RPC)
  │                              │
  ▼                              ▼
结果注入 Agent A 上下文          Agent B 执行并返回
```

### 11.2 配置远端 Agent

**✅ 推荐写法**

```yaml
openjiuwen:
  service:
    a2a:
      remote-agents:
        - name: hotel-agent
          url: http://localhost:18091/a2a/
          timeout-seconds: 300
        - name: flight-agent
          url: http://localhost:18092/a2a/
          timeout-seconds: 300
```

框架启动时自动：
1. `A2AAgentCardDiscovery` 向每个 agent 的 `/.well-known/agent-card.json` 拉取 AgentCard
2. 缓存到 `A2ARemoteAgentCardRegistry`
3. 将 `delegate_to_{agentName}` 工具注册到本地 Agent

**❌ 不要这样**

```java
// 在业务代码中直接 new HttpClient 调远端 agent —
// 绕过 A2A 协议的状态管理、Task 生命周期、SSE 流式处理、中断恢复
HttpClient client = HttpClient.newHttpClient();
HttpResponse<String> resp = client.send(request, BodyHandlers.ofString());
```

**💡 为什么这是推荐的**

1. **协议完整性** — `A2ARemoteAgentClient` 封装了完整的 A2A 协议：AgentCard 发现 → SendStreamingMessage → Task 状态跟踪 → 终端状态（COMPLETED/INPUT_REQUIRED/FAILED）→ 结果提取
2. **中断透传** — 远端 Agent 返回 `INPUT_REQUIRED` 时，本地 Orchestrator 自动转换为本地中断类型（`a2a_delegate`），用户输入后自动 resume
3. **SPI 可替换** — `RemoteAgentCaller` 和 `RemoteAgentCardResolver` 是 SPI，部署环境可通过 `A2AGatewayRemoteAgentCaller` 走网关路由，不需要直接访问远端 agent
4. **纯 yaml 配置** — 加一个 agent 只需两行 yaml，不需要写 Java 代码

---

## 12. 跨会话记忆

> 引擎侧 SPI：**agent-core-java**  
> 服务层适配：**agent-runtime-java**

Memory 提供跨 session 的持久记忆能力（基于向量库）。与 Checkpointer（单 session 上下文，TTL 过期）互补：Checkpointer 管"这一轮对话接到上一轮"，Memory 管"这个用户之前说过什么"。

### 12.1 配置

```yaml
openjiuwen:
  service:
    middleware:
      memory:
        enabled: true
        timeout-ms: 15000
        provider: mem0                # 或 jiuwen
        endpoint: https://api.mem0.ai
        encrypted-api-key: ${MEM0_API_KEY:}
```

### 12.2 使用

**✅ 推荐写法**：通过 `MemoryToolRegistrar` 自动注册记忆工具

Agent 自动获得两个工具：
- `memory_search` — 向量搜索历史记忆
- `memory_add` — 存储新记忆

LLM 在对话中自主决定何时调用。业务代码通常不需要手动操作 Memory。

**✅ 如需自定义 Memory 实现**

```java
import com.openjiuwen.core.memory.external.MemoryProvider;

@Component
public class MyMemoryProvider implements MemoryProvider {

    @Override
    public String getName() { return "my-memory"; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public void initialize(Map<String, Object> kwargs) {
        // 连接自建向量库
    }

    @Override
    public String prefetch(String query, Map<String, Object> kwargs) {
        // 查询相关记忆，注入 system prompt
        return memoryStore.search(query);
    }

    @Override
    public void syncTurn(String userMsg, String assistantMsg, Map<String, Object> kwargs) {
        // 每轮对话结束后存储
        memoryStore.add(userMsg, assistantMsg);
    }

    @Override
    public List<Map<String, Object>> getToolSchemas() { return List.of(); }

    @Override
    public String handleToolCall(String toolName, Map<String, Object> args) {
        return "ok";
    }
}
```

**❌ 不要这样**

```java
// 绕过 MemoryProvider SPI，在 Rail 中直接调向量库
public class MemoryRail extends AgentRail {
    @Override
    public RailDecision onBeforeAgentCall(...) {
        // QueryContext 中硬编码 prompt 拼接记忆内容
        ctx.appendSystemPrompt(milvusClient.search(userMsg));
    }
}
```

**💡 为什么这是推荐的**

1. **SPI 统一入口** — `MemoryProvider` 定义了 7 个抽象方法（`getName` / `isAvailable` / `initialize` / `getToolSchemas` / `handleToolCall` / `prefetch` / `syncTurn`）+ 4 个 default 方法（`systemPromptBlock` / `shutdown` / `onSessionEnd` / `isInitialized`），框架自动在合适时机回调
2. **框架管理生命周期** — `initialize()` / `shutdown()` / `onSessionEnd()` 由框架统一调度，不需要手动管理
3. **工具自动暴露** — 实现 `getToolSchemas()` / `handleToolCall()` 后，框架自动注册为 Agent 工具，LLM 可见
4. **不要和 Checkpointer 混淆** — Memory 跨 session 用，Checkpointer 单 session 用。不要用 Memory 存 checkpoint 快照

---

## 附录：反模式速查表

| ❌ 不要这样 | ✅ 应该这样 | 仓库 |
|------------|------------|------|
| `new DeepAgent(...)` | `HarnessFactory.createDeepAgent(...)` | agent-core-java |
| `new SubAgent(...)` | `XxxAgentFactory.createXxxAgent(...)` | agent-core-java |
| `new MilvusClient(...)` 直接操作向量库 | `VectorStoreFactory.createVectorStore(new VectorStoreConfig("milvus", "my_vectors"))` | agent-core-java |
| `new SandboxClient(...)` / `new McpClient(...)` | `sandboxClientFactory.create(serverId)` | agent-runtime-java |
| 自己 `implements AgentHandler`（接入 openjiuwen 引擎时） | `new JiuwenCoreAgentHandler(agent)` 注册为 `@Bean` | agent-runtime-java |
| Checkpointer 用 InMemory 跑生产 | 配置 `type: redis` | agent-runtime-java |
| 用 `HttpClient` 手写远端 agent 调用 | yaml 配 `remote-agents`，框架自动走 A2A 协议 | agent-runtime-java |
| 绕过 `MemoryProvider` SPI 直接调向量库 | `implements MemoryProvider` + `@Component` | agent-core-java |
| `agent.getAbilityManager().add(card)` 然后忘记 `addTool` | 两步都做：`add(card)` + `addTool(fn, id, true)` | agent-core-java |
| 在 Handler 层 `if-else` 判断工具名 | `extends BaseInterruptRail` 拦截指定工具 | agent-core-java |
| `@PostConstruct` 管理沙箱初始化 | `implements AgentInitHook` + `@Bean` | agent-runtime-java |
| 工具/Rail 注册散落在 Handler 各处 | Enhancer 模式集中注册 | agent-solution |
| `new VersatileProperties()` 忘记设 `ambiguousIntentId=""` | 显式 `setAmbiguousIntentId("")` | agent-solution |
| 跨 agent 硬编码 `if (text.equals("信用卡"))` 路由 | 让 agent 声明 `isUnhandledInput`，路由层只做优先级调度 | agent-solution |
