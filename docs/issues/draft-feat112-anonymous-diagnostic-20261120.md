# FEAT-112：交付默认配置下降级诊断面匿名可读（G5-missing，must_violation）

日期：2026-11-20
性质：产品违反特性档，接收方 solution/DA 开发（修复方向需特性设计裁定）。
提单版（2026-11-20 r2，按开发核查答复 `feat112-eight-issue-review-20261120.md` §3.1 修订：控制器类名更正为 `DaHealthDetailController`；“阻塞放行”表述移至验收口径节）。开发已判定为真 Issue。

## 结论摘要

交付默认配置（`deepanalyze.auth.enabled=false`）下，不带任何凭据 GET `/v1/health/detail` 返回 HTTP 200 和完整降级门状态。特性档 §3.2 要求诊断面“纳入既有服务间认证，不像 /ready 一样豁免”，验收出口 16 要求“合法服务间认证”。已配置认证的部署上拒绝行为正确（G5-invalid、G11 通过），问题只在交付默认形态。

## 场景说明

按交付默认配置部署 DA（未显式配置服务间认证——这正是默认安装的状态）。网络内任何可达方，不带 `X-Engine-Secret`：

```
GET /v1/health/detail
→ 200 {"status":"degraded","degradationGate":{"enabled":true,"state":"HALF_OPEN",
   "instanceId":"degradation-agent-runtime-e9bd1f","sampleCount":5,"systemFailureCount":5,
   "systemFailureRate":1.0,"probeInFlight":false}, "modelConfig":{...},"tasks":{...},...}
```

实例标识、失败率、门状态、模型配置来源、待答任务数等运维信息对匿名方完全可读。

## 影响面

- 触发：交付默认部署即触发，无需任何额外条件；确定性。
- 波及：所有未显式开启认证的部署；暴露的是只读诊断信息（无写操作、不泄露密钥——G8 核查通过），但实例拓扑与健康状况可被用于探测降级窗口。
- 测试侧验收口径（与代码事实分开表述）：此项为 MUST 级（出口 16），在 FEAT-112 验收账目中记 FAIL/must_violation；是否阻塞发布由发布流程裁量。
- 规避：显式开启服务间认证（已验证拒绝行为正确）；但特性档的要求对象正是“交付默认不得匿名可读”。

## 实测证据（观察）

- 运行 `linux-rest-1`（2026-11-20 08:22 +08:00，Linux）：结果包 `target/feat112-evidence/FEAT-112-G5-missing-anonymous-e9acdda7-bf30-4128-af6c-c3dde506e246/001.json`——匿名 GET → 200 全量门状态（上引）。
- 判定记录：`target/acceptance-results/linux-rest-1/Feat112DiagnosticsTest.anonymousDiagnosticRejectedInDeliveredConfiguration-*.json`（FAIL / must_violation）。
- DA 自己的启动日志印证默认形态：`WARN c.o.d.a.DaCompatAutoConfiguration : deepanalyze.auth.enabled=false: /agent/** endpoints accept requests without X-Engine-Secret; enable service auth before exposing beyond trusted network segment`（结果包 `target/sit-logs/feat112/stdout.log`）。
- 对照（已配置认证的部署形态）：缺凭据/错误凭据被拒，与其它受保护端点一致；合法凭据读取完整诊断——G5-invalid、G11 PASS（同 run 记录）。

## 代码层定位（solution `origin/common@96c23d4ea`；含推断，分别标注）

- 运行时验证：交付默认 `deepanalyze.auth.enabled=false`；该开关控制整个服务间认证（启动 WARN 为证）。
- 开发核查确认（本仓 `feat112-eight-issue-review-20261120.md` §3.1）：`application.yml:264-267` 认证默认关闭；`DaCompatAutoConfiguration.java:271-282` 过滤器经 `@ConditionalOnProperty` 仅在 `true` 时注册；`DaHealthDetailController.java:117-150` 诊断端点无独立凭据检查、直接返回 200。DA L2 已把“交付默认关闭认证”列为当前差距。

## 判据

特性档 `05af9cc3` §3.2（诊断面纳入既有服务间认证、不豁免）、验收出口 16。判据字段均为对外契约（HTTP status 与响应体）。

## Pre-flight 自查

1. 全字段扫描：200 响应全文已读（证据 001.json），不存在“已脱敏/受限视图”字段——返回的就是完整诊断。
2. 跨通道核对：对外契约（200）；日志（启动 WARN 自述匿名可达）；配置（交付默认值 false）。三通道一致。
3. 特性档字段核对：判据只用 HTTP status 与特性档点名的诊断面端点，无内部标识。

## 与历史 issue 的关系

2026-11-12 首轮实跑即发现（当时场景号 G5a），此后三个载体（solution `ec105dfb9` → `1127a9ec` → `96c23d4e`）每轮复现，未见修复动作。此前以 cases 登记（§8.2/§8.3）跟踪，未正式提单。与 F4（校验被门盖过）同属“既有横切面与降级门交界”，机制独立。

## 复现条件

- 载体：`degradation-agent-runtime:0.1.0`（core `f777aa244` + solution `96c23d4ea`，sha256 `cdb5c93e…`）；任意 OS（Linux/Windows 均已复现）。
- 步骤：交付默认配置起载体 → `curl http://127.0.0.1:<port>/v1/health/detail`（无任何头）→ 200 全量门状态。
- 或跑测试仓 `Feat112DiagnosticsTest#anonymousDiagnosticRejectedInDeliveredConfiguration`（FEAT-112-G5-missing）。

## 预答可能的反问

- “交付默认认证整体关闭，是 FEAT-054 的部署口径，不归降级门管？”——特性档 §3.2 对**本特性的诊断面**单独提出了“纳入认证、不豁免”的要求；即便全局开关归 FEAT-054，诊断面在默认形态下的行为仍违反本条。若特性设计裁定“默认关闭时诊断面可读属预期”，请修订 §3.2 措辞，G5-missing 按裁定关闭。
- “只读信息，危害有限？”——影响面一节已按实际暴露内容论证，不主张超出事实的严重性；定级交由接收方，但 MUST 条款的符合性判定不受危害大小影响。

## 修复建议与验收

- 方向（三选一，待特性设计定）：交付默认开启服务间认证；或诊断面单独要求凭据（不随全局开关豁免）；或修订特性档承认默认形态例外。
- 验收：前两种方向下 `Feat112DiagnosticsTest#anonymousDiagnosticRejectedInDeliveredConfiguration` 转绿（匿名请求被拒，拒绝方式与其它受保护端点一致），已配置认证形态的 G5-invalid、G11 保持通过。
