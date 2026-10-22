# 冒烟交付清单 (HANDOFF)

**构建状态：首次本机 `mvn clean package` 已验证通过，自冒烟 5/7 PASS。本 demo 不需要真实 LLM 凭据——所有场景打到本机 stub LLM 服务器。**

> **S3 / S4 阻塞**：S3 FAIL、S4 asserter 过线但语义被 SDK bug 掩盖。根因是上游
> `agent-core-java 0.1.17` 的 `OpenAIModelClient.buildRequest` 把
> `HttpRequest.timeout()` 当作 header-wait 用，但 JDK 把它当成"总响应时长"执行，
> 导致流式响应整个被 `header_wait` 值截断。待上游 issue 修复后重跑 S3/S4。
> 详见 [`UPSTREAM-ISSUE-DRAFT.md`](UPSTREAM-ISSUE-DRAFT.md)。

## 产物

| 产物 | 大小 | MD5 (本机构建) | 仓内路径 |
|------|------|---------------|------|
| timeout-probe-agent | ~175 MB（拆成两卷） | `6466941ce443126c036613684473cb0c` | `timeout-tiering-model-call-demo/timeout-probe-agent/target/timeout-probe-agent-0.1.0.jar.tar.gz.part00` + `.part01` |
| llm-stub-server | ~22 MB | `45c09a6f39bb9096682a003f235611b1` | `timeout-tiering-model-call-demo/llm-stub-server/target/llm-stub-server-0.1.0.jar` |

两者都是 Spring Boot repackage 后的 fat jar，`scp` 到服务器后 `java -jar` 直接拉起。
MD5 来自 `2026-10-22` 本机构建；对端若自己打包，MD5 会不同是正常现象（classpath
顺序 + Spring Boot repackage 时间戳的影响）。

### 拼回 probe-agent fat jar

受仓单文件 100 MB 限制，`timeout-probe-agent-0.1.0.jar` 被拆成两个 92 MB tar.gz 分卷。
使用前先拼回：

```bash
cd timeout-tiering-model-call-demo/timeout-probe-agent/target
cat timeout-probe-agent-0.1.0.jar.tar.gz.part* | tar xzf -
# 产物: timeout-probe-agent-0.1.0.jar (~175 MB)
md5sum timeout-probe-agent-0.1.0.jar   # 应得 6466941ce443126c036613684473cb0c
```

---

## 一、环境准备 (换机器 / 重新构建时)

| 项 | 要求 |
|----|------|
| JDK | 17 或 21；**不要**用 24+ (Lombok 的 `sun.misc.Unsafe` 已失效，`agent-core-java` 编译会崩) |
| Maven | 3.9+ |
| openJiuwen 依赖 | 任选一种：<br/>**A.** 配好 `~/.m2/settings.xml` 让 `com.openjiuwen:*:0.1.17 / 0.1.4` 从内部 registry 解析；<br/>**B.** 本地 clone `agent-core-java` + `agent-runtime-java`，切到 develop 分支后 `mvn install -DskipTests` 发布到本地 `~/.m2` (本机已采用此方式，`~/.m2` 已就绪) |
| curl | `scripts/run-scenarios.sh` 用来 POST 任务；S6 的 `--max-time 3` 必须被 curl 版本支持 |
| 真实 LLM 凭据 | **不需要**。所有场景都打到本机 stub |

### 本机当前环境参考

- JDK17 位置：`D:\openjiuwen-java\2012\.tools\jdk17\jdk-17.0.20+8`
- Maven：Maven wrapper 缓存里的 3.9.16
- 构建命令：先 `export JAVA_HOME=D:/openjiuwen-java/2012/.tools/jdk17/jdk-17.0.20+8` 再 `mvn clean package`

---

## 二、打包

```bash
export JAVA_HOME=D:/openjiuwen-java/2012/.tools/jdk17/jdk-17.0.20+8   # Windows 本机示例
cd common/example/timeout-tiering-model-call-demo
mvn clean package
```

得到：

- `timeout-probe-agent/target/timeout-probe-agent-0.1.0.jar` (端口 18092)
- `llm-stub-server/target/llm-stub-server-0.1.0.jar` (端口 18094)

---

## 三、运行 7 个场景

```bash
# 1) 可选：若 10.255.255.1 在本机网络不是黑洞，换成任意不可路由地址
export LLM_API_BASE_UNREACHABLE=http://192.0.2.1:81   # TEST-NET-1 示例

# 2) 一键跑完 7 个场景并做轨迹断言
cd common/example/timeout-tiering-model-call-demo
./scripts/run-scenarios.sh

# 3) 若只想跑其中一个场景
./scripts/run-scenarios.sh s3
```

每个场景会：

1. 用 `LLM_STUB_DEFAULT_MODE=<mode>` 启动 `llm-stub-server` 到 18094；
2. 启动 `timeout-probe-agent` 到 18092，激活对应 Spring profile；S1 的 `api-base` 被覆盖为不可达地址；
3. POST 一次 `/v1/query`（S6 用 `curl --max-time 3`）；
4. 等待 N 秒落轨 (每个场景的等待时间见 `run-scenarios.sh:post_wait_for_scenario`)；
5. 关闭两个进程；
6. 调用 `trace-asserter.sh` 对日志做必须命中 / 必须不出现断言。

日志按 `yyyymmdd-HHMMSS-<scenario>-{probe,stub,request}.log` 落到 `logs/` 便于回看。

---

## 四、冒烟通过标准 (由 trace-asserter 自动判定)

错误负载按 `ModelTimeoutErrorFactory` 的实际落点匹配：
- `StatusCode.MODEL_CALL_TIMEOUT = 181008`
- `details.phase` ∈ { `BEFORE_CONNECT`, `BEFORE_FIRST_SEGMENT`, `AFTER_FIRST_SEGMENT` }
- `details.layer` ∈ { `connect`, `header_wait`, `stream_idle` }

> **日志匹配只认 phase，不认 layer**：
> 默认 logger 只打印 `BaseError.getMessage()` 的内容，形如
> `[181008] model call timeout at <PHASE>, effective timeout <N>s, reason: …`。
> 结构化的 `details.layer` 字段只存在于错误对象上（上层 API 消费方可读），日志
> 文本不会渲染；原始 layer 名（`connect` / `header_wait` / `stream_idle`）只会
> 在启动时 config dump 行里出现一次，每个场景都会打印、与所测层无关。因此断言
> 以 phase 文本作为 layer 的唯一代理（phase ↔ layer 1:1）：
>
> - `BEFORE_CONNECT ↔ connect`
> - `BEFORE_FIRST_SEGMENT ↔ header_wait`
> - `AFTER_FIRST_SEGMENT ↔ stream_idle`

> SDK 的 `ModelClientConfig$Builder.build()` 对 `connectTimeoutSeconds` 施加
> `[5,15]s`、对 `streamIdleSeconds` 施加 `[300,900]s` 校验，legacy `timeout`
> 字段无上界。S3（AFTER_FIRST_SEGMENT）因此必须等待 ≥300s（smoke 单跑 ~5.5
> 分钟）；S2/S5 的 header-wait 走 legacy `timeout` 路径，8s 内即可触发。

| 场景 | 必须出现 | 必须不出现 | 本机 10-22 结果 |
|------|---------|-----------|---------------|
| s0 | `Started TimeoutProbeAgent` | `\[181008\]` | PASS |
| s1 | `\[181008\]`, `timeout at BEFORE_CONNECT` | `timeout at BEFORE_FIRST_SEGMENT`, `timeout at AFTER_FIRST_SEGMENT` | PASS |
| s2 | `\[181008\]`, `timeout at BEFORE_FIRST_SEGMENT` | `timeout at BEFORE_CONNECT`, `timeout at AFTER_FIRST_SEGMENT` | PASS |
| s3 | `\[181008\]`, `timeout at AFTER_FIRST_SEGMENT` | `timeout at BEFORE_CONNECT` | **FAIL (上游 SDK bug，待修复)** |
| s4 | `Started TimeoutProbeAgent` | `\[181008\]` | PASS (asserter 过线；语义被上游 SDK bug 遮蔽) |
| s5 | `\[181008\]`, `timeout at BEFORE_FIRST_SEGMENT` | `timeout at BEFORE_CONNECT`, `timeout at AFTER_FIRST_SEGMENT` | PASS |
| s6 | `Started TimeoutProbeAgent` | `\[181008\]` | PASS |

所有场景 `RESULT [sN]: PASS` 即算冒烟通过。任意场景打印 `MISS` 或 `LEAK` 都会以
非零退出码失败。本次本机运行 **S0/S1/S2/S4/S5/S6 PASS，S3 FAIL (已归因到上游 SDK)**。

### S3 / S4 的上游依赖

S3 的 FAIL、S4 的"PASS-but-misleading"同一根因：

- `agent-core-java 0.1.17` 的 `OpenAIModelClient.buildRequest` 对每个
  `HttpRequest.Builder` 都调用 `.timeout(header_wait_seconds)`。SDK 设计意图是
  让这个只作为响应头到达前的 header-wait 上限。
- JDK 的 `java.net.http.HttpRequest.timeout()` 实际是把该值用作**总响应接收时长**
  —— 从 `send()` 到响应体完全接收完毕的硬帽，由
  `jdk.internal.net.http.ResponseTimerEvent.handle` 兑现。
- 结论：流式响应的整个 body 都被 header-wait 值截断，首字节到达后的
  per-chunk idle 永远拿不到执行权。S3 的 310s 首字节前停滞 → 300s 请求总帽先
  触发 → `[181001] HttpTimeoutException`，不会出现 `[181008] AFTER_FIRST_SEGMENT`。
  S4 的 15s 心跳流 → 10s legacy timeout 请求总帽先触发 → 流被打断，与 S4 预期
  "stream completes under heartbeat" 相反。

SDK 侧修复思路见 [`UPSTREAM-ISSUE-DRAFT.md`](UPSTREAM-ISSUE-DRAFT.md)。上游修复到位后，
S3 / S4 不需要改 asserter，直接重跑即可。

---

## 五、冒烟失败时的现场提交清单

若轨迹断言失败，把下面材料回传即可定位：

- `logs/<timestamp>-<scenario>-probe.log` (必须)
- `logs/<timestamp>-<scenario>-stub.log` (必须)
- `logs/<timestamp>-<scenario>-request.log` (`/v1/query` 的响应体)
- `trace-asserter.sh` 对应那次运行的屏幕输出
- 构建时 `mvn -X clean package 2>&1 | tail -200` (若是打包阶段失败)

---

## 六、SIT 环境常见坑位

`run-scenarios.sh` 已经把下面两件事包起来了；若 SIT 团队脱离脚本手工拉起 probe
或改接真实 LLM 时才需要重新注意。

### 6.1 HTTP 代理会污染 loopback 调用

公司内网机器一般有 `HTTP_PROXY` / `HTTPS_PROXY` 环境变量；JDK `HttpClient` 若打开
system-proxy 自动发现，会把 `127.0.0.1:18094` 的调用也送去 MITM 代理，拿到的
是代理返回的 5xx，而非 stub 的 header-wait 行为 —— 三层超时根本不触发。

脚本内已做的事：

- shell 侧 `unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy`，并设置
  `NO_PROXY=127.0.0.1,localhost,10.255.255.1,192.0.2.1`。
- JVM 侧 `-Djava.net.useSystemProxies=false -Dhttp.proxyHost= -Dhttps.proxyHost=`。
- curl 侧 `--noproxy '127.0.0.1,localhost'`。

脱离脚本运行时，务必在当前 shell 中照搬这三件事；否则 probe 的首调会先打到代理，
日志里出现的是代理的 502 而非超时分层。

### 6.2 `api-base` 需要带 `/v1` 后缀

Stub 的实际路由是 `POST /v1/chat/completions`（见 `LlmStubController`）；OpenAI 风格
的 `OpenAIModelClient` 只会在 base 上追加 `/chat/completions`，所以 `LLM_API_BASE`
必须是 `http://host:port/v1`，否则对端 404。

脚本内已经把 `LLM_API_BASE=http://127.0.0.1:18094/v1` 固化在 `STUB_URL_DEFAULT`。
若 SIT 想接到真实 OpenAI-兼容 endpoint（例如内部网关），同样要显式带上 `/v1`
后缀，不要只写到 host:port。
