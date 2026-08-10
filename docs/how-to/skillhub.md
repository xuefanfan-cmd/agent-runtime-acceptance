---
title: SkillHub 技能注入：启动下载技能包，请求时注册进 Agent
description: 用 openjiuwen.service.middleware.skillhub 开启 SkillHub 中间件——启动期从 Skill Hub 下载并校验技能包，首个请求时注册进 Agent 的 SkillManager；含 ext handler 接线、sysOperationId 前置条件、失败语义与自定义 Provider SPI
audience: ai-coding
status: verified
snippets:
  - snippets/skillhub-agent-configuration.java
  - snippets/skillhub-middleware.yml
---

# SkillHub 技能注入：启动下载技能包，请求时注册进 Agent

SkillHub 中间件是 agent-solution（runtime-ext）的增量能力：开启后，框架在
**启动期**从 Skill Hub 下载技能包到本地目录并做完整性校验，在**每个请求执行前**
把已校验的技能注册进当前 Agent 的 core `SkillManager`——Agent 随即可以把这些
技能当作自己的能力调用。

整条链路由自动配置装配，你只需：**配置 5 个属性 + 用 ext handler 托管 Agent +
给 Agent 设置 `sysOperationId`**。

> ⚠️ 边界：SkillHub 只负责「技能包的获取与注册」，技能的形态是含 `SKILL.md` 的
> 本地目录（core 的技能契约）。它不做技能检索排序，也不改变 Agent 的执行逻辑——
> 技能何时被模型选用仍由 Agent 自身决定。

## 适用场景 / 不适用场景

| | 说明 |
| --- | --- |
| ✅ 适用 | Agent 需要运行期从统一技能市场（默认 `swarmskills.openjiuwen.com`）获取技能，而非打包进应用 |
| ✅ 适用 | 技能需要独立发布/更新，Agent 侧重启或后台重试后即可获得 |
| ✅ 适用 | 企业自建 Skill Hub——实现 `SkillHubProvider` SPI 接入自有技能源 |
| ❌ 不适用 | 只有一两个固定技能——直接在代码里 `BaseAgent.registerSkill(path)` 更简单，不需要 SkillHub |
| ❌ 不适用 | 用 agent-id 纯配置自动装配的基础 handler——SkillHub 注入只在 ext handler 上生效（见能力点 1） |
| ❌ 不适用 | 期望下载失败时静默可用——认证/权限类失败是 fail-fast 的（见能力点 3） |

## 最小完整示例

**接线增量**：在任一 agent 服务工程（装配机制见
[配置驱动 Agent](config-driven-agent.md)，完整用例见
[examples/workflow/](../examples/workflow/)）上叠加两个片段——
**[snippets/skillhub-agent-configuration.java](../snippets/skillhub-agent-configuration.java)**
（Agent 装配改为：agent 注册为 Spring Bean、ext handler 手动装配、设置
`sysOperationId`）与 **[snippets/skillhub-middleware.yml](../snippets/skillhub-middleware.yml)**
（`middleware.skillhub.*` 配置段，并入工程 application.yml）。核心接线摘录：

```java
// AgentConfiguration.java：两个 Bean——agent 实例 + ext handler
@Bean
ReActAgent assistantAgent(...) {
    ReActAgentConfig config = ReActAgentConfig.builder()
            // ……
            .sysOperationId(AGENT_ID)   // ⚠️ 前置条件：不设置则技能注册不生效
            .build()
            .configureModelClient("OpenAI", apiKey, apiBase, modelName, true);
    agent.configure(config);
    return agent;
}

@Bean
AgentHandler assistantHandler(ReActAgent assistantAgent) {
    return new JiuwenCoreAgentExtHandler(assistantAgent);   // SkillHub 注入只挂在 ext handler 上
}
```

```yaml
# application.yml
openjiuwen:
  service:
    middleware:
      skillhub:
        enabled: true
        endpoint: ${SKILLHUB_ENDPOINT:https://swarmskills.openjiuwen.com}
        auth-type: bearer                          # bearer | system-token
        encrypted-token: ${SKILLHUB_ENCRYPTED_TOKEN:}
        local-dir: ${SKILLHUB_LOCAL_DIR:./target/skillhub-skills}
```

所需 artifact 为 `com.openjiuwen:agent-service-adapters-agentcore-ext`；当前推荐版本见 [版本兼容与上游锚点](../compatibility.md)。

## 能力点逐个展开

### 1. 注入链路：ext handler + 生命周期

- SkillHub 链路只在 `JiuwenCoreAgentExtHandler` 上生效：它按
  `@Autowired(required = false)` 接收 `SkillHubManager`——`enabled=true` 时
  自动配置创建该 Bean 并注入；未开启时 handler 照常运行，只是没有技能。
- 生命周期：**handler `start()`** → manager 启动 provider 并触发首次
  下载 + 校验；**每个 `query` / `streamQuery` 请求前** → 把已校验技能注册进
  当前 agent（按 agent 幂等，同一 agent 不重复注册）；**handler `stop()`** →
  停止后台重试与 provider。
- ext handler 构造器只接受 **Agent 实例**，因此必须手动声明 Handler Bean
  （同「配置驱动 Agent」能力点 3 的接线规则），agent-id 纯配置自动装配路径
  无法获得 SkillHub。
- 支持的 agent 类型：`BaseAgent` 子类（如 ReActAgent）；`DeepAgent` 会注册到
  其内部 agent 上。

### 2. 前置条件：`sysOperationId` 与技能格式

- core 的技能基础设施（`SkillUtil`）以 `sysOperationId` 初始化——**Agent 配置
  必须设置它**，否则 `registerSkill` 无法生效：

  ```java
  ReActAgentConfig.builder().sysOperationId(AGENT_ID)   // 一般用 agent 的注册 ID
  ```

- 技能包格式：provider 下载 zip → 校验 → 解压为**含 `SKILL.md` 的目录**
  （core `registerRoot` 契约）；manager 在 `local-dir` 下扫描这些目录并逐个
  `provider.verify(...)`，通过校验的才会进入待注册清单。
- 注册动作最终落在 core 公开 API `BaseAgent.registerSkill(String)` 上——
  你自己写的代码也可以用同一 API 注册本地技能目录，二者可共存。

### 3. 失败语义：哪些 fail-fast，哪些降级重试

| 阶段 | 失败类别 | 行为 |
| --- | --- | --- |
| 启动 · endpoint 为空 | 配置错误 | fail-fast：启动期抛 `IllegalArgumentException` |
| 启动 · token 解密失败 | 凭证配置错误 | fail-fast：启动期抛 `IllegalStateException`（不肯降级为匿名） |
| 启动 · provider.start | 配置/认证 | fail-fast |
| 首次下载 | `AUTH_FAILED` / `ACCESS_DENIED` / `NOT_FOUND` | fail-fast：Agent 不就绪 |
| 首次下载 | `DOWNLOAD_FAILED` / `CHECKSUM_MISMATCH` / `CONNECT_FAILED` | 降级：Agent 就绪但无技能，**后台每 30 s 重试**直至成功 |
| 请求时注册 | `INSTALL_FAILED`（registerSkill 未生效） | 请求线程抛错，该请求可感知；同一 agent 后续请求不再重复抛 |

凭证用 `encrypted-token` 配置**密文**，由运行时凭证解密器在使用点还原；
token 为空表示匿名访问。

### 4. 自定义 SkillHubProvider（接入自有技能源）

默认 provider 连接 `endpoint` 指向的 OpenJiuwen Skill Hub。接入企业自建
技能源时，实现 SPI 并声明为 Bean，默认 provider 自动让位：

```java
@Bean
SkillHubProvider skillHubProvider() {
    return new SkillHubProvider() {
        @Override public void start(SkillHubConfig config, String decryptedToken) { /* 建连 */ }
        @Override public boolean download(SkillHubConfig config, String decryptedToken) { /* 下载到 config.getLocalDir() */ }
        @Override public boolean verify(Path skillPath) { /* 完整性校验 */ return true; }
        @Override public void stop() { /* 释放资源 */ }
    };
}
```

- SPI 收到的是**已解密的明文 token**——实现类不得记录或持久化它。
- 失败分类约定：抛 `IllegalStateException` 且消息以 `SkillHub[CATEGORY]`
  开头（`CATEGORY` 取自 `SkillHubErrorCategory`），manager 据此决定
  fail-fast 还是降级重试；未分类的按 `UNKNOWN` 处理。

## 配置项参考

前缀 `openjiuwen.service.middleware.skillhub`：

- **enabled**：总开关，默认 `false`；`true` 时整条链路（provider / installer / manager）才装配。
- **endpoint**：Skill Hub 服务地址；enabled 时必填，为空启动期 fail-fast。
- **auth-type**：认证方式，`bearer`（默认）/ `system-token`，按所连 Hub 要求设置。
- **encrypted-token**：访问令牌**密文**，使用点经凭证解密器还原；留空 = 匿名访问。
- **local-dir**：技能包本地下载目录；下载的 zip 在此解压为含 `SKILL.md` 的目录。

> ⚠️ 可设置属性边界：以上 5 个是全部对外属性。下载调度（重试间隔）、
> 扫描深度等是框架内部常量，不开放配置，不要尝试设置。

## 坑位与排错

> ⚠️ **用了基础 handler 或 agent-id 自动装配**：SkillHub 只挂在
> `JiuwenCoreAgentExtHandler` 上。`handler: agentcore` 的自动装配路径
> 永远不会注入 SkillHubManager——必须手动声明 ext handler Bean（能力点 1）。

> ⚠️ **忘设 `sysOperationId`**：core 的 `SkillUtil` 用它初始化；未设置时
> 技能注册不生效，表现为「下载成功但 agent 没有技能」。修复：
> `ReActAgentConfig.builder().sysOperationId(<agentId>)`。

> ⚠️ **`endpoint` 留空**：启动期抛
> `IllegalArgumentException: SkillHub endpoint is not configured`。
> 本地联调没有 Hub 时，把 `enabled` 关掉，而不是留空 endpoint。

> ⚠️ **明文 token 写进 `encrypted-token`**：该字段按密文处理、使用点解密。
> 解密失败会 fail-fast；默认 passthrough 解密器场景下才可等价当明文用，
> 但生产环境应配置真实解密器并写入密文。

> ⚠️ **把下载失败当致命错误排查启动**：`DOWNLOAD_FAILED` / `CONNECT_FAILED`
> 类失败是**降级**语义——应用正常启动、后台每 30 s 重试，日志有
> `SkillHub background retry started`。只有 `AUTH_FAILED` / `ACCESS_DENIED` /
> `NOT_FOUND` 才阻止就绪。

## 端到端校验

1. 配置 `SKILLHUB_ENDPOINT`（与有效 token，若 Hub 要求），启动应用。
2. 看启动日志：`SkillHub manager started credential=provided|absent`，
   且无 `IllegalArgumentException` / 解密失败异常。
3. 检查 `local-dir`：下载成功后其下出现含 `SKILL.md` 的技能目录
   （形如 `local-dir/<asset_id>/<extracted_name>/SKILL.md`）。
4. 发起请求触发注册（注册发生在请求线程）：

   ```bash
   curl -X POST http://localhost:18094/v1/query \
     -H 'Content-Type: application/json' \
     -d '{"conversation_id":"s1","message":"你有哪些技能可以用？","stream":false}'
   ```

   预期：日志出现 `SkillHub register completed ... registered=N`；
   agent 回答中可见已注册技能。
5. 若日志出现 `SkillHub background retry started`：属降级重试，检查网络/
   endpoint 后等下一轮重试即可，无需重启。

## API 锚点（jar 内类，按依赖可查）

- 托管：`com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler`
- SPI（自定义技能源）：`com.openjiuwen.service.spec.ext.skillhub.spi.SkillHubProvider`、
  `com.openjiuwen.service.spec.ext.skillhub.SkillHubConfig`、
  `com.openjiuwen.service.spec.ext.skillhub.SkillHubErrorCategory`
- core 技能面：`com.openjiuwen.core.singleagent.BaseAgent`（`registerSkill`）、
  `com.openjiuwen.core.singleagent.agents.ReActAgentConfig`（`Builder.sysOperationId`）
- 完整片段：[../snippets/skillhub-agent-configuration.java](../snippets/skillhub-agent-configuration.java)、[../snippets/skillhub-middleware.yml](../snippets/skillhub-middleware.yml)（本工程自有）

## See also

- [配置驱动 Agent](config-driven-agent.md)：ext handler 为什么必须手动声明 Bean、构造器边界
- [中间件配置](middleware.md)：runtime 侧 `openjiuwen.service.middleware.*` 家族（checkpointer / Redis / 记忆）——SkillHub 是 solution 在同前缀下的增量
- [runtime-ext 接口文档](../api/runtime-ext.md)：ext handler 与 remote-agents 速览
- [版本兼容与依赖坐标](../compatibility.md)：artifact 版本基线与坐标速查
