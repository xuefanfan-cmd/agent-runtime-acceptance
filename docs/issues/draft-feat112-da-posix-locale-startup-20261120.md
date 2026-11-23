# DA：POSIX locale 环境下因内置技能中文目录名启动失败（部署健壮性参考项）

日期：2026-11-20
性质：参考项（非 FEAT-112 条款，不计验收），接收方 solution/DA 开发。
提单版（2026-11-20 r2，按开发核查答复 §3.8 修订：触发条件精确化为“`sun.jnu.encoding` 为 ASCII 类编码”（受 LANG/LC_ALL/LC_CTYPE 共同决定），不泛化为“无 LANG 必现”；修法去掉不可落地的“Path 按 UTF-8 处理”）。开发已判定为真 Issue。

## 结论摘要

JVM `sun.jnu.encoding` 解析为 ASCII 类编码时（由 `LANG`、`LC_ALL`、`LC_CTYPE` 共同决定，典型如三者皆未设或为 `C`/`POSIX`），`DaBuiltinSkillInstaller.installOne` 对带中文名的内置技能目录执行 `Path.resolve` 抛 `java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters`，Spring 上下文构建失败，DA 启动即死。

## 场景说明与影响面

最小化容器镜像、`systemd` 服务、CI runner 等环境常常不设 locale。该环境下 DA 完全起不来，失败信息是一段乱码路径的 `InvalidPathException`，与 locale 的因果不直观，排障成本高。规避：部署保证 UTF-8 locale（如 `export LANG=C.UTF-8`；注意 `LC_ALL` 优先级最高，设了 `LC_ALL=C` 则 LANG 无效）。触发条件：`sun.jnu.encoding` 为 ASCII 类编码时在默认 Unix 文件系统上确定性失败；复现时记录实际值（`java -XshowSettings:properties -version 2>&1 | grep jnu`）。

## 实测证据

- 结果包 `target/acceptance-results/linux-rest-1/Feat112ProcessConfigurationTest.deliveredDefaultEnablesGate-6a33228f.json` 内嵌启动日志全文（2026-11-20 08:21 +08:00，EulerOS 2.0、Temurin 21.0.12.1）：`Caused by: java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters: ȫ��֪ʶ�����`（中文技能名按 ASCII 解码的乱码），栈顶 `DaBuiltinSkillInstaller.installOne(DaBuiltinSkillInstaller.java:132)`。
- 触发背景说明（I-M2，环境事实）：该现场来自测试夹具曾对子进程清空环境变量（含 LANG）；夹具已改为保留 locale。**产品问题本身与测试夹具无关**——任何不设 locale 的真实部署都会命中。

## 代码层定位（solution `96c23d4ea`）

`common/agents/deepanalyze-java/engine/src/main/java/com/openjiuwen/da/skill/DaBuiltinSkillInstaller.java:132`（`installOne` 内 `Path.resolve(技能名)`），调用链 `DaEngineConfiguration.prepareBuiltinSkills(:389)` → `daAgentHandler(:279)`，在 Spring 启动期执行，异常直接终止上下文。根因：内置技能目录名含中文 + JVM 文件路径编码跟随 locale。

## Pre-flight 自查、判据与历史

1. 全字段扫描：启动日志全文已读，无降级/跳过该技能的分支。2. 跨通道：进程退出码与日志一致；无接口可查（进程未起）。3. 判据不涉特性档条款——本稿即标注为参考项，不主张 MUST 违反。历史：首次提出；与 FEAT-112 诸 Issue 无机制关联。

## 复现条件

Linux 任意机器：`env -u LANG -u LC_ALL -u LC_CTYPE java -jar degradation-agent-runtime-0.1.0.jar --spring.config.location=<最小配置>`（或 `LC_ALL=C` 前缀）；先 `java -XshowSettings:properties -version 2>&1 | grep jnu` 确认 `sun.jnu.encoding` 非 UTF-8，启动即失败；`LC_ALL=C.UTF-8` 前缀则正常。载体 sha256 `cdb5c93e…`。

## 预答可能的反问

“部署规范要求 UTF-8 locale 即可？”——可以，这正是三个可选修法之一；但当前失败形态（乱码 + InvalidPathException）不自解释，至少应快速失败并提示 locale 要求。

## 修复建议与验收

可落地方向（标准 Java NIO 无法对单个 `Path` 指定编码，原稿“按 UTF-8 处理路径”不可行，撤回）：① 磁盘目录用稳定 ASCII ID，中文名留在 `catalog.json` 元数据；② 启动自检：检测到非 UTF-8 `sun.jnu.encoding` 快速失败，错误含实际编码值与 locale 指引；③ 部署文档明确 UTF-8 locale 要求（最弱）。验收：`LC_ALL=C` 下启动成功（①）或给出含编码值的明确错误（②）。
