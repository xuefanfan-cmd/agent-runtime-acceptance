# timeout-tiering-model-call-demo

一个面向开发者的 **三层模型调用超时** 示例：通过一个最小 ReAct Agent + 一台可控的
OpenAI 兼容 stub LLM 服务器，演示如何在 openJiuwen 上为自己的 Agent 启用
**connect / header-wait / stream-idle** 三条互相独立的超时控制通路，并通过
`MODEL_CALL_TIMEOUT (181008)` 错误负载 (`phase` / `layer` / `timeout_seconds` / `stream`)
对所有相关行为做可观测断言。

> 本 demo 覆盖 7 个场景，覆盖该特性的 **12 条 MUST**。有 1 条 MUST
> 的设计文档描述与代码实现存在偏差，README 第 "设计文档偏差说明" 一节单独声明。

---

## 目录

- [模块结构](#模块结构)
- [工程范式映射](#工程范式映射-openjiuwen-五段-golden-path)
- [场景 × 行为覆盖矩阵](#场景--行为覆盖矩阵)
- [轨迹断言矩阵](#轨迹断言矩阵)
- [设计文档偏差说明](#设计文档偏差说明)
- [构建](#构建)
- [运行](#运行)
- [环境变量清单](#环境变量清单)
- [开发者可改动的扩展点](#开发者可改动的扩展点)

---

## 模块结构

```
timeout-tiering-model-call-demo/
├── pom.xml                                  # 父 pom (packaging=pom)
├── timeout-probe-agent/                     # 探针 Agent (端口 18092)
│   ├── pom.xml                              # fat-jar via spring-boot-maven-plugin
│   └── src/main/java/com/openjiuwen/example/timeouttiering/
│       ├── agent/                           # 库层: 纯 Java, 不依赖 Spring
│       │   ├── TimeoutProbeAgentFactory.java
│       │   └── TimeoutProbeAgentProperties.java
│       └── service/                         # 服务层: Spring Boot
│           ├── TimeoutProbeAgentApplication.java
│           ├── TimeoutProbeAgentConfiguration.java
│           └── TimeoutProbeAgentRuntimeProperties.java
├── llm-stub-server/                         # OpenAI 兼容 stub LLM 服务器 (端口 18094)
│   └── src/main/java/com/openjiuwen/example/timeouttiering/stub/
│       ├── LlmStubApplication.java
│       ├── LlmStubConfiguration.java
│       ├── LlmStubRuntimeProperties.java
│       ├── LlmStubController.java           # /v1/chat/completions
│       └── TieredTimeoutHandlers.java       # 6 种可控 stall 行为
└── scripts/
    ├── run-scenarios.sh                     # 场景驱动脚本
    └── trace-asserter.sh                    # 轨迹断言脚本
```

### Stub 的 6 种可控模式

Stub 把超时制造能力放在 server 侧，profile 不需要去调 LLM 客户端的内部细节即可
构造出每一类 stall 场景。每种模式由 `LLM_STUB_DEFAULT_MODE` 环境变量选择：

| 模式 | 行为 | 触发的层 |
|------|------|---------|
| `ok` | 立即 200 返回 1-word JSON 响应 | 无 (baseline) |
| `header-wait-silent` | 接受 TCP，但长时间不写响应头 | stream-idle 在 BEFORE_FIRST_SEGMENT 相位触发 |
| `first-segment-idle` | 立即写 SSE 响应头，再 sleep 后首块到 | stream-idle 在 BEFORE_FIRST_SEGMENT 相位触发 |
| `after-first-segment-idle` | 立即写 SSE 头 + 首个 chunk，再 sleep 后续 chunk | stream-idle 在 AFTER_FIRST_SEGMENT 相位触发 |
| `long-answer-heartbeat` | 立即写 SSE 头 + 每 heartbeat 间隔吐一次 chunk，总时长可配 | 无 (证明没有"总时长硬上限") |
| `idle-silent-blocking` | 写 200 OK + 阻塞 Content-Type 头，sleep 后写 JSON body | stream-idle 在 blocking response 上同样生效 |
| `long-hold-for-cancel` | SSE 长流，缓慢 heartbeat (验证 caller 侧 `--max-time` 协同取消) | 无 (caller cancels first) |

---

## 工程范式映射 (openJiuwen 五段 Golden Path)

严格遵循 `agent-solution/guides/README.md` 所述的分层约束。

| 五段 | 本 demo 文件 | 依赖方向红线 |
|------|--------------|---------------|
| ① 描述问题 | 本 README 的"场景 × 行为覆盖矩阵"章节 | — |
| ② 技术方案 | `TimeoutProbeAgentFactory.java` 顶部的 javadoc | — |
| ③ 实现 agent 层 | `agent/` 目录（纯 Java，仅依赖 agent-core-java） | 不允许 `import org.springframework.*` |
| ④ 实现 service 层 | `service/` 目录（Spring Boot + agent-service-app） | 可以 `import` ③，不可反向 |
| ⑤ 可观测验证 | `scripts/trace-asserter.sh` + `src/test/java/**` | — |

> `TimeoutProbeAgentFactory` 没有依赖 service-app 内部的 `ExampleReActAgentFactory`：
> 这是一个有意为之的拷贝。原因是 openJiuwen 开发范式要求 agent/ 库层**只**依赖
> `agent-core-java`，而 `ExampleReActAgentFactory` 位于 `com.openjiuwen.service.demo.*` 包，
> 由 service-app 提供，不作为稳定 API 对外发布。将 wirer 逻辑在本 demo 本地拷贝，
> 换取 agent 层的可移植性。

---

## 场景 × 行为覆盖矩阵

本矩阵把特性文档中的 12 条 MUST 映射到 7 个场景。
"轨迹证据"列指明可以通过日志里的哪些关键字证明相应行为已被覆盖。

| 场景 | 名称 | 触发条件 (yaml 片段 / 驱动) | 覆盖的行为 | 轨迹证据 |
|------|------|------------------------------|-------------|---------|
| **S0** | 默认值可见 | 不配置任何三层超时覆写；stub 以 `ok` 模式启动 | 默认 connect=10s / stream-idle=600s | 启动日志出现 `Started TimeoutProbeAgent`；请求成功返回；无 `181008` |
| **S1** | 慢连接 | `connect-timeout=5s`（SDK 范围 `[5,15]s` 的下边界）；`api-base` 指向不可达地址 `10.255.255.1:81` | connect 层超时、`MODEL_CALL_TIMEOUT` 可观测、error 负载含相位 | `181008` + `BEFORE_CONNECT` + `layer=connect` |
| **S2** | 响应头空档（legacy 路径） | `timeout=8s`（遗留字段 → `isTimeoutExplicit=true` → `resolveHeaderWaitSeconds` 返回 8）；stub 以 `header-wait-silent` 模式启动 | 遗留 `timeout` 字段仍作为 header-wait 覆写、error 负载含相位 | `181008` + `BEFORE_FIRST_SEGMENT` + `layer=header_wait` |
| **S3** | 首块后空档 | `stream-idle-seconds=300s`（SDK 范围 `[300,900]s` 的下边界）；stub 以 `after-first-segment-idle` 模式启动，首块后停顿 310s | stream-idle 覆盖 inter-segment、config 可配、error 负载含相位 | `181008` + `AFTER_FIRST_SEGMENT` + `layer=stream_idle` |
| **S4** | 长答案无总上限 | `timeout=10s`（遗留字段）；stub 以 `long-answer-heartbeat` 模式启动，每 1.5s 一个 chunk，总 15s | 遗留 `timeout` 字段不构成总时长硬上限；流随 heartbeat 顺利跑完 | `Started TimeoutProbeAgent`；请求成功；无 `181008` |
| **S5** | 阻塞响应同样生效 | `timeout=8s`（legacy header-wait 路径）；stub 以 `idle-silent-blocking` 模式启动 | header-wait 对非 SSE 阻塞响应同样生效 | `181008` + `layer=header_wait` + 相位 `BEFORE_FIRST_SEGMENT` |
| **S6** | caller 侧协同取消 | 默认超时；curl `--max-time 3`；stub 以 `long-hold-for-cancel` 模式启动 | 调用方取消后，Agent 协同解绑；无 `MODEL_CALL_TIMEOUT` 虚警 | `Started TimeoutProbeAgent`；无 `181008` |

**MUST 覆盖汇总**：12 条 MUST 中 11 条由上述场景直接证明，1 条 (MUST#8)
见后文"设计文档偏差说明"。其中 MUST#12 (超时错误阶段信息三段口径：
`phase` / `layer` / `timeout_seconds`) 由 S1 / S2 / S3 / S5 的 `MODEL_CALL_TIMEOUT`
负载共同证明——轨迹断言矩阵中每条 `181008` 都伴随对应的 `phase` + `layer` 字段。

> **SDK 范围约束**：`ModelClientConfig$Builder.build()` 对 `connectTimeoutSeconds`
> 施加 `[5, 15]s` 校验，对 `streamIdleSeconds` 施加 `[300, 900]s` 校验。因此
> S3 必须等待 300s 以上才能触发（smoke 单跑 ~5.5 分钟）；想快速验证同层逻辑的
> 开发者可以直接看 S2（legacy `timeout` 路径走 `resolveHeaderWaitSeconds`，8s 内即触发）。

---

## 轨迹断言矩阵

`scripts/trace-asserter.sh` 把上述"轨迹证据"编码为必须命中 / 必须不出现的 extended regex。
覆盖面与场景一一对应——任意场景断言失败时脚本返回非零退出码。

```
S0: 必须出现 { Started TimeoutProbeAgent } ；禁止出现 { 181008, MODEL_CALL_TIMEOUT }
S1: 必须出现 { 181008, BEFORE_CONNECT, connect } ；禁止出现 { AFTER_FIRST_SEGMENT, stream_idle }
S2: 必须出现 { 181008, BEFORE_FIRST_SEGMENT, header_wait } ；禁止出现 { BEFORE_CONNECT, AFTER_FIRST_SEGMENT }
S3: 必须出现 { 181008, AFTER_FIRST_SEGMENT, stream_idle } ；禁止出现 { BEFORE_CONNECT }
S4: 必须出现 { Started TimeoutProbeAgent } ；禁止出现 { 181008, MODEL_CALL_TIMEOUT }
S5: 必须出现 { 181008, header_wait } ；禁止出现 { BEFORE_CONNECT, AFTER_FIRST_SEGMENT }
S6: 必须出现 { Started TimeoutProbeAgent } ；禁止出现 { 181008, MODEL_CALL_TIMEOUT }
```

---

## 设计文档偏差说明

设计文档中有 1 条 MUST 的描述与实际代码实现存在偏差：

> **原文假设**：主体 HTTP 栈 (OkHttp) 与推理亲和栈 (JDK HttpClient) 两栈并行闭环；
> provider 根据流量类型选择不同的栈，各自独立套用三层超时。
>
> **实际代码**：开发期发现"推理亲和栈"下线更干净，统一走单一的 JDK `HttpClient` 栈，
> 由 `OpenAI` / `OpenAiCompatible` / `DashScope` / `SiliconFlow` 四个 client 子类共享
> `ModelTimeoutResolver` 来落实三层超时。四个子类之间没有栈级差异，只有 provider 协议
> 适配上的差异。

**本 demo 对该偏差的处理**：
- 不单独做一个"两栈并行"场景——这不是实际代码可以观测到的行为；
- 场景 S0..S6 全部使用 `OpenAI` provider（LLM_PROVIDER=OpenAI），覆盖了
  `ModelTimeoutResolver` 的所有分支；
- 需要确认 DashScope / SiliconFlow 这两个 provider 适配也正确接入三层超时时，
  只需重跑任一个 stream-idle 场景 (推荐 S3)，并把 `LLM_PROVIDER` 分别改为 `DashScope`
  / `SiliconFlow`——事件应与 `OpenAI` provider 完全一致 (phase / layer / timeout_seconds
  字段相同；`provider` 字段体现差异)。

---

## 构建

### 前置条件

| 项 | 要求 | 说明 |
|----|------|------|
| JDK | 17 或 21 | **不要**用 JDK 24+，Lombok 的 `sun.misc.Unsafe` 反射在新 JDK 已失效，`agent-core-java` 编译会崩 |
| Maven | 3.9+ | 需要能访问到发布 `com.openjiuwen:agent-core-java:0.1.17` 与 `com.openjiuwen:agent-runtime-java:0.1.4` 的 Maven 仓库 |
| 依赖来源 | 内部 openJiuwen Maven registry **或** 本地 `mvn install` | 两个 artifact 位于 `agent-core-java` 与 `agent-runtime-java` 仓库的 develop 分支。若内网仓库不可达，可本地 clone 这两个仓库，切到 develop 后 `mvn install -DskipTests` 发布到本地 `~/.m2` |
| Spring Boot | 4.0.7 | 父 pom 已固定；注意 Boot 4 把 `@WebMvcTest` 搬到了 `org.springframework.boot.webmvc.test.autoconfigure` 并拆到独立模块 `spring-boot-starter-webmvc-test`，若新写 slice test 需要显式引入该 starter |

### 打包

从 demo 根目录：

```bash
mvn clean package
```

产物：

- `timeout-probe-agent/target/timeout-probe-agent-0.1.0.jar` — fat jar，端口 18092
- `llm-stub-server/target/llm-stub-server-0.1.0.jar` — fat jar，端口 18094

两个产物都是独立运行的 Spring Boot fat jar，可以直接 `scp` 到服务器用
`java -jar` 启动，无需 JVM 额外依赖。

---

## 运行

### 本地逐个场景

```bash
# 1) 可选：覆盖端口 / 不可达地址 (默认即可工作)
export TIMEOUT_PROBE_PORT=18092
export LLM_STUB_PORT=18094
# 若 10.255.255.1 在本机网络下不是黑洞，改为任意不可路由地址
export LLM_API_BASE_UNREACHABLE=http://10.255.255.1:81

# 2) 执行场景
./scripts/run-scenarios.sh s0            # 默认值可见
./scripts/run-scenarios.sh s3            # 单跑 AFTER_FIRST_SEGMENT 场景
./scripts/run-scenarios.sh                # 跑所有 7 个场景
```

每个场景会：

1. 用 `LLM_STUB_DEFAULT_MODE=<mode>` 启动 `llm-stub-server` 到 18094
2. 启动 `timeout-probe-agent` 到 18092，激活对应 Spring profile；S1 的 api-base 被覆盖为不可达地址
3. POST 一次 `/v1/query`（S6 用 `curl --max-time 3`）
4. 等待对应超时窗口以上的时长让轨迹落盘
5. 关闭两个进程
6. 调用 `trace-asserter.sh` 对日志做断言

日志按 timestamp + scenario 分文件存放到 `logs/`。

### 部署到服务器

```bash
# 1) scp 两个 fat jar
scp timeout-probe-agent/target/timeout-probe-agent-0.1.0.jar <host>:/opt/demo/
scp llm-stub-server/target/llm-stub-server-0.1.0.jar          <host>:/opt/demo/

# 2) 启 stub (模式按需换)
LLM_STUB_PORT=18094 LLM_STUB_DEFAULT_MODE=after-first-segment-idle \
    nohup java -jar /opt/demo/llm-stub-server-0.1.0.jar > /var/log/demo-stub.log 2>&1 &

# 3) 启 probe，指定 profile
TIMEOUT_PROBE_PORT=18092 LLM_API_BASE=http://127.0.0.1:18094 \
    nohup java -jar /opt/demo/timeout-probe-agent-0.1.0.jar \
    --spring.profiles.active=s3 > /var/log/demo-probe.log 2>&1 &
```

---

## 环境变量清单

### Probe Agent

| 变量 | 说明 | 默认 |
|------|------|------|
| `TIMEOUT_PROBE_PORT` | probe-agent 监听端口 | `18092` |
| `LLM_PROVIDER` | 模型 provider 名称 | `OpenAI` |
| `LLM_API_KEY` | 模型 API key（stub 不校验，占位即可） | `stub-key` |
| `LLM_API_BASE` | 模型 API 基址 | `http://127.0.0.1:18094` |
| `LLM_API_BASE_UNREACHABLE` | S1 用的不可达地址 | `http://10.255.255.1:81` |
| `LLM_MODEL` | 模型名 | `stub-model` |
| `LLM_SSL_VERIFY` | 是否校验 SSL | `false` |
| `LLM_TEMPERATURE` | 温度 | `0.0` |
| `LLM_TOP_P` | top-p | `0.8` |
| `LLM_CONTEXT_WINDOW_LIMIT` | 上下文窗口 | `32768` |

每个场景在 Spring profile 内部注入自己的 `connect-timeout` / `stream-idle-seconds` /
`timeout` 覆写。

### Stub Server

| 变量 | 说明 | 默认 |
|------|------|------|
| `LLM_STUB_PORT` | stub 监听端口 | `18094` |
| `LLM_STUB_DEFAULT_MODE` | 请求省略 `?mode=` 时的默认模式 | `ok` |
| `LLM_STUB_HEADER_SILENCE_MS` | header-wait-silent 的 sleep 毫秒 | `60000` |
| `LLM_STUB_FIRST_SEGMENT_MS` | first-segment-idle / after-first-segment-idle 的 sleep 毫秒 | `20000` |
| `LLM_STUB_LONG_ANSWER_MS` | long-answer-heartbeat 总时长 | `90000` |
| `LLM_STUB_LONG_ANSWER_HB_MS` | long-answer-heartbeat 每拍间隔 | `3000` |
| `LLM_STUB_BLOCKING_IDLE_MS` | idle-silent-blocking 的 sleep 毫秒 | `20000` |
| `LLM_STUB_LONG_HOLD_MS` | long-hold-for-cancel 总时长 | `60000` |

---

## 开发者可改动的扩展点

想在自己的 Agent 项目里复用这套三层超时，只需要两步：

### 1) 在 `application.yml` 配 llm.* 的三个超时字段

```yaml
openjiuwen:
  service:
    llm:
      provider: OpenAI
      api-base: https://your-llm/v1
      api-key: ${YOUR_API_KEY}
      model-name: your-model
      # ---- 三层超时 ----
      connect-timeout: 10s          # BEFORE_CONNECT 层；范围 [5s, 15s]
      stream-idle-seconds: 600s     # BEFORE_FIRST_SEGMENT / AFTER_FIRST_SEGMENT 层；范围 [300s, 900s]
      timeout: 120s                 # 遗留字段；仍保留作为 stream-idle 的向下兼容覆写
```

未声明的字段落回 SDK 默认 (connect=10s, stream-idle=600s)。

### 2) 在 Agent 工厂中把 Resolved 配置透传给 `ModelClientConfig.Builder`

参考本 demo 的 `TimeoutProbeAgentFactory.build(ResolvedLlmConfig, TimeoutProbeAgentProperties)`
实现：从 service-app 自动装配拿到 `LlmConfigResolver` → `ResolvedLlmConfig`，
把 `getConnectTimeout()` 和 `getStreamIdleSeconds()` 透传给 `ModelClientConfig.Builder`
的 `.connectTimeoutSeconds(...)` 和 `.streamIdleSeconds(...)` 即可。

```java
@Bean
ReActAgent myAgent(LlmConfigResolver llmResolver, MyProbeProps props) {
    return MyAgentFactory.build(llmResolver.resolveRequired(), props);
}
```

### 3) 通过错误负载观察超时相位

当发生超时时，agent-core 会抛出 `BaseError`：
- `StatusCode` = `MODEL_CALL_TIMEOUT (181008)`
- `details.phase` = `BEFORE_CONNECT` / `BEFORE_FIRST_SEGMENT` / `AFTER_FIRST_SEGMENT`
- `details.layer` = `connect` / `header_wait` / `stream_idle`
- `details.timeout_seconds` = 实际触发的超时阈值
- `details.stream` = 是否是流式请求

本 demo 的 `trace-asserter.sh` 展示了把这些字段编码成断言的一个最小可行方案。

---

## 相关资料

- openJiuwen 开发范式: `agent-solution/guides/README.md`
- 其它参考 demo:
    - `common/example/run-budget-elastic-research-demo` — 弹性 run-budget 示例，8 场景 18 MUST
    - `common/example/multi-deep-research-demo` — 多 Agent + 真实 Tavily + Mock MCP
