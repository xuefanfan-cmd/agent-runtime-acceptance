---
title: Sandbox 沙箱：远程代码执行客户端
description: 用 openjiuwen.service.external.sandbox 声明式启用沙箱，经 AgentCoreSandboxClientFactory 获取带治理能力的 SandboxClient，执行代码并下载产物；暴露方式（Tool / Rail / 直接调用）由业务选择
audience: ai-coding
status: verified
snippets:
  - docs/snippets/sandbox.yml
---

# Sandbox 沙箱：远程代码执行客户端

Sandbox 为 Agent 提供远程代码执行环境：在沙箱内运行 Python 等脚本、渲染图表、
下载产物文件。与 checkpointer / Redis / 长期记忆不同，沙箱**不属于
`openjiuwen.service.middleware.*` 配置域**——它走 `openjiuwen.service.external.*`
外部服务配置域，由运行时按 `enabled` 条件装配客户端工厂：

- **能力面**：core `SandboxClient`（`com.openjiuwen.core.sysop.sandbox`）提供原子能力
  ——`code().executeCode(...)` 执行代码、`fs().downloadFile(...)` 下载文件，
  不绑定任何 Agent 类型；
- **配置/工厂面**：`AgentCoreSandboxClientFactory`（agent-runtime-java）解析
  `external.sandbox.*` 并构造带超时/重试/熔断/审计治理的 client；
- **业务面**：你注入工厂、拿 client，并选择如何暴露给 Agent（Tool / Rail / 直接调用）。

## 适用场景 / 不适用场景

| | 说明 |
| --- | --- |
| ✅ 适用 | Agent 需要执行不可信/动态生成的代码（数据分析、图表渲染、文件处理） |
| ✅ 适用 | 需要把执行产物（PNG / CSV 等）下载回本地或 workspace |
| ✅ 适用 | 多类型 Agent 共用沙箱——client 与 ReActAgent / DeepAgent / WorkflowAgent 无关 |
| ❌ 不适用 | 没有可用沙箱服务（`service-url` 指向的 jiuwenbox 服务需独立部署） |
| ❌ 不适用 | 期望框架自动把沙箱注册成 Agent 工具——框架只交付工厂与 client，暴露方式由你实现 |

**能力状态：**

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 声明式启用（`enabled` 条件装配工厂） | ✅ 已交付（runtime jar） | `enabled=false` 时工厂 Bean 不存在 |
| `SandboxClient` 代码执行 / 文件下载 | ✅ 已交付（core jar） | 经 jiuwenbox provider 实现 |
| 沙箱工具/Rail 封装 | 🔧 业务自实现 | 框架未内置沙箱工具注册器，按「能力点 3」自选路径 |

## 最小完整示例

**配置增量 + 工厂注入两段式**：在任一 agent 服务工程（装配机制见
[配置驱动 Agent](config-driven-agent.md)）的 `application.yml` 中并入
**[snippets/sandbox.yml](../snippets/sandbox.yml)** 的 `external.sandbox` 块，
然后在装配代码中经 `ObjectProvider` 取工厂：

```java
// enabled=false 时 provider 为空——沙箱是可选项，代码必须容忍工厂缺失
@Bean
AgentHandler agentHandler(ObjectProvider<AgentCoreSandboxClientFactory> sandboxFactoryProvider) {
    ReActAgent agent = /* 构造与 configure，见各 agent 类型指南 */;

    sandboxFactoryProvider.ifAvailable(factory -> {
        SandboxClient sandbox = factory.create("default");   // 按 server-id 选取
        // 包装为工具注册到 agent（见能力点 3）
    });

    return new JiuwenCoreAgentHandler(agent);
}
```

Maven 依赖同「配置驱动 Agent」（`agent-service-adapters-agentcore`，版本对照见
[版本兼容与上游锚点](../compatibility.md)）——工厂自动装配随该模块引入，无需额外依赖。

## 能力点逐个展开

### 1. 声明式启用与条件装配

- 工厂 Bean 仅在 `openjiuwen.service.external.sandbox.enabled=true` 时由自动配置创建；
  `enabled` 缺省/false 时 Bean 不存在——因此注入点必须用 `ObjectProvider` 而非直接注入。
- 自动配置带 `@ConditionalOnMissingBean`：声明自己的
  `AgentCoreSandboxClientFactory` Bean 即可整体替换默认实现。
- `enabled=true` 时 `servers` 必须非空（启动期校验，见「坑位与排错」）。
- 一律走工厂拿 client：工厂注入超时/重试/熔断/审计等治理能力，绕过工厂
  直接构造的裸 client 不含这些能力（原则见
  [开发指导 §3.1](../conventions/openjiuwen开发指导.md)）。

### 2. SandboxClient：执行代码与下载文件

```java
// 执行代码：参数为 (code, language, timeoutSeconds, environment, options)
ExecuteCodeResult r = sandbox.code().executeCode(code, "python", timeoutSeconds, null, null);

// 下载文件：参数为 (sourcePath, localPath, overwrite, createParentDirs,
//                  preservePermissions, chunkSize, options)
DownloadFileResult d = sandbox.fs().downloadFile(remotePath, localPath, true, true, false, 65536, null);
```

- `factory.create()` 使用默认配置的 server；`factory.create(serverId)` 按
  `servers[].server-id` 显式选取。
- client 是普通 Java 对象，不以任何 Agent 类型为前提；多 Agent 可共享同一 client。

### 3. 暴露给 Agent 的三条路径（按场景自选）

| 路径 | 做法 | 适用场景 |
| --- | --- | --- |
| 注册为 Tool | 将沙箱调用封装为工具方法（匹配 `LocalFunction` 签名），两步注册进 Agent（注册机制见[开发指导 §3.2](../conventions/openjiuwen开发指导.md)） | Agent 推理时自主决定何时执行代码 |
| 挂 Rail | 实现 rail，在 Agent 推理流程的固定钩子点自动触发沙箱调用 | 每轮固定动作（如报告生成前自动渲染图表） |
| 业务直接调用 | 业务代码直接调 `sandbox.code().executeCode(...)`，结果作为输入传给 Agent | 沙箱是内部辅助能力，不暴露给 LLM |

> 框架未内置沙箱工具/Rail 封装，以上封装代码均为业务自实现；
> 三条路径可组合（如 Tool 为主、Rail 做兜底校验）。

## 配置项参考

- **openjiuwen.service.external.sandbox.enabled**：默认 `false`；`true` 才装配工厂 Bean。
- **openjiuwen.service.external.sandbox.timeout-ms**：单次调用超时（毫秒），默认 30000，必须 > 0。
- **openjiuwen.service.external.sandbox.servers[]**：沙箱服务端点清单，`enabled=true` 时必填。
  - **server-id**：端点标识，`factory.create(serverId)` 按它选取。
  - **service-url**：沙箱服务地址（jiuwenbox 服务需独立部署）。
  - **sandbox-type**：默认 `jiuwenbox`，不能为空。
  - **launcher-type**：默认 `pre_deploy`。
  - **on-stop**：默认 `delete`。
  - **root-path**：默认 `.`。
  - **idle-ttl-seconds**：空闲回收 TTL（秒），可选。

## 坑位与排错

> ⚠️ **`enabled=true` 但 `servers` 为空**：启动期抛
> `IllegalArgumentException: Sandbox servers must not be empty when sandbox is
> enabled`。修复：补齐 `servers` 清单或关闭 `enabled`。

> ⚠️ **`sandbox-type` 配成空串**：启动期抛
> `IllegalArgumentException: Sandbox sandbox-type must not be blank`。
> 不配置时默认 `jiuwenbox`，不要显式置空。

> ⚠️ **`timeout-ms` 配成 0 或负数**：配置绑定期抛 `IllegalArgumentException`
> （值必须 > 0）。

> ⚠️ **直接 `@Autowired` 工厂而 `enabled=false`**：容器中没有该 Bean，启动失败。
> 必须用 `ObjectProvider<AgentCoreSandboxClientFactory>` 注入并判空。

> ⚠️ **绕过工厂直接构造 client**：裸 client 不含超时/重试/熔断/审计治理能力，
> 生产环境不要这样做（见[开发指导 §3.1](../conventions/openjiuwen开发指导.md)）。

## 端到端校验

1. 准备可达的沙箱服务（jiuwenbox，默认 `http://127.0.0.1:8321`），将其地址配入
   `service-url`。
2. 启动应用，确认启动日志无 sandbox 装配异常（`servers` 空、`sandbox-type` 空等
   会在启动期直接暴露）。
3. 通过你封装的路径（Tool/Rail/业务调用）执行一次代码：
   `executeCode("print('hello')", "python", 30, null, null)`，
   断言返回的 `ExecuteCodeResult` 标准输出含 `hello`。
4. 如启用了文件产物：执行生成文件的脚本后用 `fs().downloadFile(...)` 拉回本地，
   断言文件存在且内容完整。

## API 锚点（jar 内类，按依赖可查）

- 客户端工厂：`com.openjiuwen.service.adapters.agentcore.external.AgentCoreSandboxClientFactory`（`create()` / `create(serverId)`）
- 配置绑定：`com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties`（前缀 `openjiuwen.service.external`）
- 沙箱客户端：`com.openjiuwen.core.sysop.sandbox.SandboxClient`（`code()` / `fs()`）
- 结果类型：`com.openjiuwen.core.sysop.result.ExecuteCodeResult` / `DownloadFileResult`
- 完整片段：[../snippets/sandbox.yml](../snippets/sandbox.yml)（本工程自有）

## See also

- [开发指导 §3.1 / §3.2](../conventions/openjiuwen开发指导.md)：外部服务一律走工厂的原则与工具两步注册机制
- [中间件配置](middleware.md)：checkpointer / Redis / 长期记忆（`middleware.*` 配置域，与沙箱分属两处）
- [配置驱动 Agent](config-driven-agent.md)：agent 构造/注册与 handler 装配的三层职责
- [版本兼容与上游锚点](../compatibility.md)：artifact 版本基线与坐标速查
