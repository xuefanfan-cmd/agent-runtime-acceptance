---
feature_id: FEAT-048
feature_title: Agent 注册中心统一 SPI 与 Nacos 对接
scope: v0915; Technical-AF/docs PR !172（FEAT-048 特性文档，2026-09-17 合入）
deployable_units: [registry-discovery-center(SPI+RDC 实现+Nacos 实现), agent-gateway, agent-runtime(注册组件)]
sut: 统一注册中心 SPI 双实现（RDC / Nacos 3.x AI A2A 通道）；Nacos 模式下 agent-runtime 自注册 + agent-gateway 发现面消费
status: executed-real-artifacts
features: [FEAT-048, FEAT-015, FEAT-016, FEAT-011, FEAT-012, FEAT-017]
updated: 2026-09-23
---

# FEAT-048 验收：Agent 注册中心统一 SPI 与 Nacos 对接

## 1. 测试目标

以 Nacos 3.x（AI 能力开启）与 RDC 为真实注册中心，以 Nacos 模式 agent-runtime（自注册组件）与 agent-gateway（发现面消费方）为 SUT，经公开表面——Nacos OpenAPI、runtime 标准 Agent Card 地址、gateway 统一 A2A facade、进程 stdout/启动行为——验证统一注册中心 SPI 的双实现事实：注册面（自注册、幂等、临时实例生命周期）、发现面（byAgentId/byServiceId 候选、路由引用解析、租户隔离）、失败语义（不可用/无候选/引用非法/能力不支持）、配置切换与快速失败、集群连接与凭据脱敏，以及服务标识一致性不变量（serviceId == agentId）。验收驱动不编译或调用 SPI 产品类，不读取注册中心内部模型、缓存或数据库表。

## 2. 范围与非范围

范围：

- Nacos 模式 runtime 自注册：Agent Card 发布（同 name + version 幂等、内容实例无关）与本实例 endpoint 注册（临时实例、单个注册语义）。
- 发现面消费语义：按 tenantId + agentId / tenantId + serviceId 查询存活实例候选；不透明路由引用（routeHandle）经消费方（gateway/runtime caller）解析并完成直连；候选不暴露物理 endpoint。
- 临时实例生命周期：进程崩溃超时自动摘除、优雅停机仅注销本实例 endpoint（多实例安全）、断线重连自动重放注册。
- 服务标识一致性不变量：实例候选 serviceId == agentId，且与目标 runtime 消费侧服务标识配置同源（bus 信封 targetServiceId 投递命中）。
- 租户隔离：namespace 承载租户域（默认恒等映射），跨租户查询/解析拒绝且不泄露存在性。
- 失败语义与降级韧性：注册中心不可用、无可用候选、路由引用非法、能力不支持（byCapability 在 Nacos 实现下明确拒绝）四类可区分失败；短时不可用回源 TTL 本地缓存。
- 实现切换与回滚：agent-registry.type（rdc | nacos，默认 rdc）切换；RDC 实现行为等价回归（零行为变化）；type 与包内可用实现不匹配启动快速失败。
- Nacos 门禁：服务端 3.x + AI 能力开关、agentId 命名约束不满足时启动快速失败并给出明确错误指引。
- 集群连接：服务端多地址列表配置接入；单节点故障自动切换其余节点，语义不中断。
- 接入安全：认证参数密文配置生效；日志、错误信息、审计与可观测输出脱敏，不出现明文凭据。
- 有界降级差异显式验证：二值健康（不健康实例从候选消失）、权重退化为均匀随机、变更感知为客户端轮询。

非范围：

- SPI 模块划分、架构守护测试（gateway 禁依赖注册面等）为开发侧白盒/守护测试，本档仅记录联动要求，不作 SIT 断言对象。
- 确定性结构化发现（能力约束匹配、标签与安全方案过滤、语义画像）保留为 RDC 模式能力（FEAT-015），Nacos 模式不验证其正向行为，仅验证不越界。
- 变更监听（watch）推送、灰度/双跑流量设计、实例权重原生表达、排水语义迁移、Python runtime 对接 Nacos、跨实现 routeHandle 互通。
- RDC 服务端自身的注册/对账行为（由 FEAT-015 既有用例覆盖，本档仅在切换回归中复用）。
- routeHandle 编解码内容（对消费方保持不透明，测试不 Base64 解码、不推断编码格式）。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| develop/02-features/FEAT-048-agent-registry-spi-nacos.md（Technical-AF/docs PR !172，2026-09-16/17） | 统一 SPI 契约、双实现事实要求（§2 能力表 15 条 MUST + 3 条 OUT）、§4 场景、§5 行为语义与边界、§6 测试要求 |
| develop/02-features/FEAT-015-agent-card-registration-and-discovery.md（PR !172 更新版） | Nacos 自注册模式下卡片注册事实、身份模型分态（agentName = agentId）、结构化发现保留 RDC 能力边界 |
| develop/02-features/FEAT-016-runtime-instance-route-query.md（PR !172 更新版） | 已知目标路由查询消费语义、capability 可选目标、Nacos 实现支撑边界（二值健康、serviceId 收敛） |
| develop/02-features/FEAT-011/012/013/014/017（PR !172 更新版） | 实现切换不改变路由转发、事件信封与投递过滤语义；服务标识一致性不变量 |
| L2 底层设计 | 待 FEAT-048 L2 文档评审合入后锁定接口键名与错误码（当前以 Feature 语义为 Oracle） |

## 4. 部署拓扑

```text
Nacos 模式（主路径）:
  Nacos 3.x server (AI enabled, 集群多地址)  <-OpenAPI 观察- JUnit driver
    ^ 自注册(卡片+临时endpoint)                |
    |                                         +-- agent-gateway (agent-registry.type=nacos)
    +- agent-runtime (nacos profile, 注册组件) <-- 发现面SPI(候选+routeHandle解析) -+
                                             client -> gateway A2A facade -> runtime 直连

RDC 模式（等价回归）:
  registry-discovery-center (agent-registry.type=rdc) + 既有 FEAT-015/016 栈复跑
```

边界要求：

- Nacos 服务端为真实制品（3.x、AI 能力开启、namespace 已创建、账号权限就绪），测试不 mock 注册中心。
- Nacos 模式 runtime / gateway 为正式部署制品，经 agent-registry.type=nacos + 服务端地址（多地址列表）+ 凭据（密文）配置启动；SIT 不改产品装配代码。
- 观察 surface 限定：Nacos 公开 OpenAPI（实例列表/健康）、runtime /.well-known/agent.json、gateway 统一 A2A facade（HTTP/SSE）、进程启动日志与退出码。不读取 Nacos 内部存储、SPI 内部模型或本地缓存结构。
- 每个场景使用独立 tenantId（namespace 恒等映射）与 agentId canary；SIT 共用 Nacos 时按租户/agentId 隔离，测后注销测试实例。
- 断网/故障注入使用进程外手段（停进程、Toxiproxy/FaultLink），禁止修改产品对象或注册中心数据。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| F048-01 | Nacos 自注册与卡片发布幂等 | Nacos 就绪、AI 开启；runtime 以 nacos 模式启动且开启注册 | 启动 runtime → 经 Nacos OpenAPI 查实例列表与卡片；模拟同 name+version 重复发布（重启注册组件/再拉起一实例） | endpoint 以临时实例注册且 healthy；卡片可查询；同 name+version 重复发布无副作用（无重复卡片、无版本漂移）；卡片内容实例无关（不含本机地址等实例信息） | Nacos OpenAPI 探针、runtime Card 探针 |
| F048-02 | 发现面候选与 serviceId 一致性 | 同一 agentId 两个 runtime 实例已自注册 | gateway/runtime caller 侧按 byAgentId 与 byServiceId 分别查询候选 | 两种目标形态返回同一实例候选集合；每个候选 serviceId == agentId；候选含契约版本与健康状态，不含物理 endpoint/routeKey 明文 | 发现面探针（经 gateway 调用或 Nacos OpenAPI 旁证） |
| F048-03 | 路由引用解析与直连路由 | runtime 已注册；gateway 以 nacos 模式运行 | client 经 gateway 发 A2A 请求（显式 agentId） | gateway 经 SPI 解析 routeHandle 完成直连，返回真实 Task 结果；client 响应不含 endpoint、routeHandle、实例地址；routeHandle 对测试保持不透明 | gateway A2A facade、响应脱敏扫描 |
| F048-04 | 实例崩溃自动摘除 | runtime 实例 A 已注册，另一"僵尸"地址从未启动 | kill -9 runtime 实例 A 进程；轮询候选 | 实例 A 在临时实例超时窗口后从候选消失，消费方候选自动收敛；僵尸地址从未出现在候选中 | 进程控制、Nacos OpenAPI 轮询 |
| F048-05 | 优雅停机注销与多实例安全 | 同一 agentId 实例 A/B 均已注册 | 正常停机实例 A（SIGTERM，注册组件优雅注销） | 仅实例 A 的 endpoint 被移除，实例 B 仍为候选；卡片目录不受影响（卡片不随单实例下线消失） | 进程控制、候选轮询 |
| F048-06 | 多实例并发发布卡片幂等 | 同一 agent 两个实例并发启动并发布同 name+version 卡片 | 并发拉起 A/B，等待注册完成 | 卡片内容实例无关且重复发布幂等无副作用；A/B endpoint 相互独立注册、互不覆盖 | 并发进程 Fixture、OpenAPI 探针 |
| F048-07 | capability 目标能力不支持 | Nacos 模式 gateway/runtime 就绪 | 消费方以 byCapability 目标查询 | 返回明确的能力不支持语义（不静默降级为其他查询、不返回空候选冒充无匹配）；错误可区分"无候选" | 发现面探针 |
| F048-08 | 租户隔离与跨租户解析拒绝 | tenant-A / tenant-B（不同 namespace）各有已注册 agent | 以 tenant-A 上下文查询 tenant-B 的 agentId；以 tenant-A 身份解析携带 tenant-B 编码的 routeHandle | tenant-A 查询不返回 tenant-B 实例（不泄露存在性）；跨租户解析按引用非法/租户越界拒绝并审计，不得猜测路由 | 双租户 Fixture、错误断言 |
| F048-09 | 失败语义可区分 | Nacos 模式消费方就绪 | 分别触发：注册中心不可达（断网）、查询不存在的 agentId、提交畸形/跨实现 routeHandle | 三类失败语义可区分（service unavailable / 无可用候选 / 引用非法），均不返回候选或猜测 endpoint；不可用类不产生下游调用 | FaultLink/Toxiproxy、错误断言 |
| F048-10 | 短时不可用本地缓存回源 | 消费方已完成一次成功发现（本地缓存有数据） | 切断到 Nacos 的连接，在 TTL 内再次调用已知目标 | TTL 内已知目标调用继续维持（回源本地缓存）；TTL 过期或无本地信息时显式失败；不跨租户降级 | FaultLink、时间窗控制 |
| F048-11 | 实现切换与 RDC 行为等价 | 同一环境分别以 agent-registry.type=nacos 与 rdc 配置启动 gateway/runtime | 先跑 Nacos 模式核心用例 → 整体切换为 rdc → 复跑 FEAT-016/011 既有 RDC 套件 | RDC 模式下发现、解析、失败映射、超时与本地缓存行为与现状等价（零行为变化）；routeHandle 跨实现不互通（切换后旧引用解析按引用非法失败） | 既有 RDC 回归套件复用 |
| F048-12 | type 与包内实现不匹配快速失败 | 单实现部署包（仅含 RDC 或仅含 Nacos 实现） | 以与包内实现不匹配的 agent-registry.type 启动 | 启动快速失败（ready 前退出），错误信息明确指出 type 与包内可用实现不匹配及修正指引；不进入带病运行 | 负向进程探针 |
| F048-13 | Nacos 门禁失败快速失败 | Nacos 版本 < 3.x 或 AI 能力未开启，或 agentId 不满足命名约束 | 启动 Nacos 模式组件 | 启动快速失败并给出明确错误指引（含部署前置检查项：3.x、AI 开关、namespace、命名约束）；不进入带病运行 | 负向环境/配置变体、进程探针 |
| F048-14 | 启动时服务端网络不可达 | Nacos 实现部署包，启动时服务端网络不可达（非版本/开关/命名问题） | 启动并观察注册面与发现面 | 注册面启动快速失败并给出含网络诊断指引的错误；发现面按"注册中心短时不可用"语义处理（无本地缓存时显式失败） | 网络屏蔽 Fixture |
| F048-15 | 集群多地址与单节点故障切换 | Nacos 以集群部署（≥2 节点），客户端配置地址列表 | 正常注册/发现/解析 → 停掉其中一个节点 → 持续注册、发现与解析 | 按地址列表接入集群；单节点故障后自动切换其余节点，注册、发现与路由引用解析语义不中断 | Nacos 集群 Fixture、节点控制 |
| F048-16 | 凭据密文配置与脱敏 | 部署环境具备统一加密配置能力；认证参数以密文配置 | 以密文配置启动并连接 Nacos，采集日志/错误/审计/可观测输出 | 运行时解密使用凭据、连接成功；全部输出对敏感字段脱敏，明文凭据（含配置回显）不出现在任何日志、错误、审计与响应中 | 日志/响应递归脱敏扫描（canary 凭据） |
| F048-17 | 二值健康与权重退化差异 | 同一 agentId 注册健康实例与已停实例 | 观察候选集合健康表达；多实例等权重下统计选路分布 | 不健康实例直接从候选消失（无中间降级态、无 DEGRADED 表达）；候选内实例被均匀选择（权重退化记录性验证，不做加权分流断言） | 候选轮询、gateway 多次调用统计 |
| F048-18 | 注册幂等（注册面重注册） | runtime 已完成注册 | 触发同键重注册（重启注册组件或重放注册） | 同键重注册等价于刷新或无副作用；endpoint 不被批量语义覆盖其他实例 | OpenAPI 探针 |

### 5.1 显式 deferred / 白盒联动项

| 项 | 处置 | 说明 |
|---|---|---|
| 统一 SPI 契约测试套件（双实现绑定：注册→发现→解析→注销闭环、引用往返） | development-owned / SIT 复核 | 契约级套件由开发在 SPI 模块交付；SIT 以黑盒表面复核同等闭环（F048-01/02/03/05） |
| 架构守护测试（SPI 模块禁三方件、gateway 禁依赖注册面） | development-owned | 白盒守护测试不纳入 SIT 黑盒验收 |
| watch 变更监听 | deferred | 可选扩展能力，本期 TTL 轮询即可 |
| 灰度 / 双跑 | deferred | 本期不设计，双实现并存仅作回滚手段 |
| 确定性结构化发现（Nacos 模式不越界） | 归 FEAT-015 | Nacos 模式仅验证消费方发现走实例寻址语义，不验证结构化过滤正向行为 |
| 变更感知轮询时效对比 | partial | 与 RDC TTL 轮询量级对比需双实现同环境运行，待 L2 固化轮询参数后补充 |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| Nacos 3.x server | 真实依赖 | AI 能力开启、集群多地址；SIT 不 mock；测试数据按 tenant namespace 隔离 |
| agent-runtime（nacos profile） | 真实 SUT | 正式制品，agent-registry.type=nacos + 服务端地址列表 + 密文凭据启动；注册组件行为是被测对象 |
| agent-gateway（nacos 模式） | 真实 SUT | 发现面消费方；client 只见统一 A2A facade，拓扑不外泄 |
| registry-discovery-center（rdc 模式） | 真实依赖 | F048-11 等价回归复用 FEAT-015/016 既有栈与套件 |
| 负向启动变体 | Fixture | type 不匹配、门禁不满足、服务端不可达的配置变体；断言"目标错误 + ready 前退出"双条件 |
| FaultLink / Toxiproxy | Fixture | 注册中心不可达、短时不可用、集群单节点故障的网络层注入 |
| Canary 与脱敏扫描 | Fixture | 唯一 tenantId/agentId/endpoint/凭据 canary；响应与日志递归扫描不泄漏 |

## 7. 关键链路断言

- F048-01/02/03/05/18 是 Nacos 模式注册-发现-解析-注销主门禁；任一失败即 FEAT-048 Nacos 实现不通过。
- F048-09/10 的失败语义必须与 RDC 既有语义同构（不可用 / 无候选 / 引用非法三分），不得出现新的模糊失败或猜测路由。
- F048-11 是切换与回滚门禁：RDC 模式行为等价以既有 FEAT-016/011 套件零失败回归判定；routeHandle 跨实现不互通按"旧引用解析 → 引用非法"断言。
- F048-12/13/14 负向场景必须同时命中目标错误语义与 ready 前退出；任意启动失败不得判为通过。
- 服务标识一致性不变量（F048-02 + FEAT-012/017 联动）：Nacos 模式下 serviceId == agentId 且与消费侧服务标识配置同源。F017-N02 漂移防护用例的期望结果随 SUT 类型而分：
  - **真实制品**（serviceId == agentId，部署不变量满足）：不存在漂移，投递过滤命中、消息成功投递——此为部署不变量的可观测终局证据（`TASK_STATE_COMPLETED`）。
  - **Fixture SUT**（agentId != serviceId，漂移模拟）：漂移实例不消费该事件（投递过滤 miss），调用侧得到确定失败而非错误实例的业务成功。
  两种结果均判定通过。
- 脱敏断言：client 响应、gateway/runtime/Nacos 客户端日志、错误与审计输出递归扫描均不含 endpoint/routeKey/instanceId 明文与明文凭据 canary。
- 不断言 SPI 接口名、routeHandle 编码格式、Nacos 内部存储结构、缓存实现或自动装配内部顺序。

## 8. 执行策略

- Smoke（Nacos 主门禁）：F048-01、F048-02、F048-03、F048-05、F048-09。
- Full suite：F048-01..18；deferred 项不计通过。
- P0 必须全绿：F048-01..03、F048-05、F048-07..09、F048-11..14、F048-16、F048-18 中当前可执行项。
- 依赖门禁：Nacos 服务端（3.x + AI）与 Nacos 模式 runtime/gateway 正式制品已就绪，33/33 用例以真实制品全量执行通过（PRODUCT-PASS，2026-09-23）。L2 合入后锁定 agent-registry.* 配置键名与错误码再修正断言精度。
- 与 FEAT-015/016/011/012/017 的联动增量见各特性测试文档"FEAT-048 联动"章节；其结果计入各自特性，不重复计入本档。
- 标识隔离：每次 F048_<场景>_<UUID> canary、独立 tenant namespace；测试结束注销测试实例并确认端口/进程/容器清理。

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=Feat048AgentRegistrySpiNacosBlackboxTest test
./mvnw -Dtest.env=openjiuwen -Dtest=Feat048NacosGatewaySwapDeltaBlackboxTest test
# RDC 等价回归（F048-11）复跑：
./mvnw -Dtest.env=openjiuwen -Dgroups=feat-016 test
```

## 附录 A. 差异、门禁与待澄清项

> 2026-09-23 更新：FEAT-048 特性代码已合入 agent-solution v0.1.2 源码并重新打包，
> 以真实产品制品（agent-gateway-demo-0.1.2-nacos.jar / agent-bus-consumer-callee-demo-0.1.2.jar /
> event-bus-relay-0.1.2.jar / registry-discovery-center-0.1.2.jar）替换 Fixture 替身，
> 33/33 用例全量执行、全部通过（**PRODUCT-PASS，产品验收**）。
> BUS 路径（F012-N01）经修复 tenant 大小写配置后打通完整 RocketMQ → Relay → Runtime 链路。
> F012-N02 断言关键词补全 `NO_CANDIDATES` / `No routable`（与 F011-N02 一致）。
> F017-N02 增加对真实制品 `TASK_STATE_COMPLETED`（部署不变量满足）的接受分支。
> 见 `build-logs/FEAT048-test-report-20260923-final.md`。
>
> 2026-09-21 更新：Nacos 服务端与 Nacos 模式 SUT 验收环境已构建（`fixtures/feat048-sut/`，
> 含 Nacos 3.0.0 podman 容器、runtime/gateway 验收替身制品、真实 RDC 对照链路）。
> 33/33 用例以替身制品全量执行、全部判定通过（**FIXTURE-PASS，联通验证，非产品验收**——
> 产品 Nacos 实现尚未在 com.openjiuwen 制品中交付；见
> `build-logs/FEAT048-test-report-20260921.md` 页首声明）。执行后
> `application-openjiuwen.yml` 的 `sut.external.nacos` 块已注释恢复门禁：
> 套件回到 dependency-gated（整类 SKIPPED，不计 PASS），产品制品合入后取消注释
> （或指向真实制品地址）再回归。

| 项目 | 影响 | 当前状态 | 解锁条件 |
|---|---|---|---|
| Nacos 3.x 服务端部署 | 全部 Nacos 模式用例 | 已解锁（产品验收：单节点 standalone + namespace tenant-A/B；集群拓扑以 F048-15 窗口语义覆盖） | SIT 环境部署正式 Nacos 集群后回归 |
| Nacos 模式 runtime / gateway 制品 | F048-01..10、F048-12..18 | **已验证**（产品正式制品 agent-gateway-demo-0.1.2-nacos.jar / agent-bus-consumer-callee-demo-0.1.2.jar，33/33 PASS） | — |
| agent-registry.* 配置键名与错误码未固化（L2 未合入） | 断言精度 | partial | FEAT-048 L2 评审合入后按键名/错误码修正 |
| 密文配置依赖统一密钥管理服务 | F048-16 | 验收环境以测试 canary 覆盖脱敏扫描；密钥管理接入待部署 | 部署环境提供加密配置能力或提供密文接入样例 |
| 集群 ≥2 节点资源 | F048-15 | 验收环境以单节点 + 窗口轮询覆盖；正式集群待 SIT | SIT 环境提供 Nacos 集群拓扑 |
| routeHandle 解析面仅转发层可达 | 跨租户解析拒绝的黑盒观测 | partial | 经 gateway 构造跨租户调用链路观测，或 L2 提供转发层探针口径 |
