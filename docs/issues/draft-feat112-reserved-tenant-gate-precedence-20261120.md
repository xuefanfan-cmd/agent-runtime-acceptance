# FEAT-112：OPEN/HALF_OPEN 下保留租户头的既有校验被降级门盖过（F4，must_violation）

日期：2026-11-20
性质：产品违反特性档，接收方 solution/DA 开发。
提单版（2026-11-20 r2，按开发核查答复 `feat112-eight-issue-review-20261120.md` §3.2 修订：影响面收窄到仍位于门后的校验；HALF_OPEN 占探针由推断转为开发证实）。开发已判定为真 Issue。

## 结论摘要

带保留租户头 `X-Da-Tenant-Id: *` 的 `/api/agents/run`，门 CLOSED 时返回既有校验错误 400 `ENGINE_VALIDATION`；门 OPEN 时同一请求返回 503 `ENGINE_DEGRADED`。特性档 §5.1.9 与验收出口 18 要求既有校验先于降级门、拒绝方式与门状态无关。原因是保留租户校验位于降级门认领之后（`enrichFromDataPlane`），门 OPEN 时请求在走到校验之前即被拒。

## 场景说明

多租户部署中，一个带非法租户头（保留租户 `*`）的任务提交请求到达 DA：

1. 门 CLOSED：请求进入既有校验，立即得到 400 `ENGINE_VALIDATION`（`tenant id '*' is reserved`）——终定错误，客户端据此修正请求。
2. 随后该实例因连续系统异常进入 OPEN。
3. 同一个非法请求再次到达：返回 503 `ENGINE_DEGRADED`、`retryable: true`、`retryAfterSeconds: 20`。
4. 客户端按提示退避重试一个**本应立即失败**的请求；门恢复 CLOSED 后它又变回 400。

同一请求的错误语义随门状态漂移：非法请求在降级期间被表述成“稍后可成功”。

## 影响面

- 触发条件：门 OPEN/HALF_OPEN + 请求带任何会被既有校验拒绝的参数。确定性触发，非偶发。
- 波及（收窄）：仅限**仍位于降级门之后**的校验——保留租户校验（`DaSharedPgRepository.java:87-90`）及同位置的同类校验。普通参数、媒体与 team 校验已在准入之前（F2、F3 实测通过，门 OPEN 下仍返回各自的既有错误），不受影响。依赖 4xx/5xx 区分“请求错误 vs 服务降级”的调用方在这些门后校验上会收到误导性的可重试 503。
- 性质：不是静默失败（有显式错误），但错误分类错误，诱导无效重试，放大降级期间的负载。
- 验收：MUST 级（验收出口 18），阻塞 FEAT-112 放行。
- 规避：无——调用方无法区分“真降级”与“被盖掉的校验错误”。

## 实测证据（观察，均为同一实例 `degradation-agent-runtime-a5ed77`，运行 `linux-env-2`，2026-11-19 08:42 +08:00）

结果包 `feat112/logs/results-all-20261120.tar.gz` 内 `target/feat112-evidence/FEAT-112-F4-72eda9f8-62fe-4556-b601-82b40ef37f7c/`：

- `002.json`（08:42:08，门 CLOSED）：`POST /api/agents/run`，头 `X-Da-Tenant-Id: *` → **400** `{"code":"ENGINE_VALIDATION","error":"tenant id '*' is reserved"}`；`003.json` 为 `/run-stream` 同结果。
- `022.json`（08:42:32，门 OPEN）：同请求 → **503** `{"error":"Agent is temporarily not accepting new tasks","code":"ENGINE_DEGRADED","degraded":true,"retryable":true,"state":"OPEN","instanceId":"degradation-agent-runtime-a5ed77","retryAfterSeconds":20}`。
- 判定记录：`target/acceptance-results/linux-env-2/summary.md` F4 行（FAIL / must_violation）。

HALF_OPEN 行为（开发核查 §3.2 证实，不再是推断）：探针空闲时请求会**短暂认领探针**、再因租户校验返回 400（`409.json`/`414.json`，08:42:52），异常退出即释放、不会永久占用；探针已占用时在校验之前收到 503。“无效请求不占探针”（出口 18）在 HALF_OPEN 下被短暂违反。

## 代码层定位（solution `origin/common@96c23d4ea`）

- 调用顺序（开发核查 §3.2 确认）：普通参数/媒体/team 校验 → `DaCompatController.java:1558-1561` 降级准入或探针认领 → `DaCompatController.java:1596` 数据面 enrichment → `DaSharedPgRepository.java:87-90` 保留租户校验（tenant `*` 抛 `DaCompatValidationException`）。
- 即：保留租户校验在准入之后；门 OPEN 时请求在走到该校验之前被 503 短路。
- 2026-11-17 testplan 修订时已由代码走读预判此结果并写入 F4 期望行；本轮实跑证实，判定与预判一致。

## 判据

特性档 `05af9cc3`：§5.1.9（既有校验先于降级门）、§2 验收出口 18（“基本参数校验……与门状态无关；不占探针”）。判据比较的是对外契约字段（HTTP status、`code`、`error`），非内部标识。

## Pre-flight 自查

1. 全字段扫描：OPEN 响应 `022.json` 全文已读，无任何字段携带被盖掉的校验结论。
2. 跨通道核对：对外契约（上证）；进程日志无该请求的校验痕迹（校验未执行，与代码定位一致）；数据面无写入。
3. 特性档字段核对：`ENGINE_VALIDATION`/`ENGINE_DEGRADED` 均为特性档与 FEAT-054 既有契约中的对外错误码。

## 与历史 issue 的关系

首次提出。testplan（docs PR 549）F4 行自 2026-11-17 起记录了该预判；此前无人提过此问题。与 G5-missing（诊断面匿名可读）同属“既有横切面与降级门的优先级”类问题，但机制不同、互相独立。

## 复现条件

- 环境：Linux x86_64（实测华为云 EulerOS 2.0 ECS）、JDK 21（Temurin 21.0.12.1）、Docker（`registry:8501/postgres:16`）。
- 载体：`com.openjiuwen.example:degradation-agent-runtime:0.1.0`，2026-11-18 由 core `f777aa244` + solution `96c23d4ea` 构建，sha256 `cdb5c93e…`（清单 `feat112-build-manifest.json`）。
- 步骤：① PostgreSQL 灌数据面基线（测试仓 `src/test/resources/deepanalyze/da-dataplane-baseline.sql`）并以 `deepanalyze.data.pg.*` 接入；② 发带 `X-Da-Tenant-Id: *` 的 `/api/agents/run`，得 400；③ 连续注入模型故障至 `/v1/health/detail` 显示 OPEN；④ 重发同一请求，得 503。
- 或直接跑测试仓 `Feat112DataPlaneTest#reservedTenantPrecedesGate`（SIT，自动完成上述步骤并判定）。

## 预答可能的反问

- “门 OPEN 本来就该拒绝一切新任务？”——特性档 §5.1.9/出口 18 明确把既有校验排在门前：非法请求不是“新任务”，不应消耗降级语义；403/401/400 类终定错误被 503 替换后客户端行为错误。
- “保留租户是内部约定，不算基本参数？”——testplan F4 行已注明“租户头是否属于出口 18 的‘基本参数’在结论中注明”，如特性设计裁定不属于，请在特性档写明，F4 将按裁定调整；但“同一请求错误语义随门状态漂移”仍需要一个明确口径。
- “是否测试环境数据面不对？”——CLOSED 下 400 证明校验路径与数据面均正常，仅门状态改变后行为不同。

## 修复建议与验收

- 建议：把保留租户（及同类既有入参校验）移到降级门准入之前；或在准入拒绝前先跑无副作用的快速校验。
- 验收：`Feat112DataPlaneTest#reservedTenantPrecedesGate`（FEAT-112-F4）转绿：OPEN/HALF_OPEN 与 CLOSED 对同一非法请求返回相同的 status/code/error，且 HALF_OPEN 下不占探针。
