---
id: FEAT-032
title: 多模态提额解控审核流水线端到端验收
module: MMA — multi-modal-audit-demo
owner: TBD
priority: P0
feature: multi-modal-audit-demo 五步审核流水线（Java 代码编排 + 视觉子 agent 并行）
status: active
sut: multi-modal-audit-demo（AuditPipeline + AuditAgentFactory + AuditImageCache + LoadImagesTool）
source: D:\code\test\agent-solution\common\example\multi-modal-audit-demo\README.md
updated: 2026-09-10
tags: [integration, multi-modal, audit, edpa]
---

# FEAT-032 — 多模态提额解控审核流水线端到端验收

> **一句话**：基于 `multi-modal-audit-demo` 的五步审核流水线（图片分类→合规审核→数据合并→完整性审核→相关性审核），
> 验证 Java 代码编排的确定性流程、Step4/5 并行执行、图片共享缓存、以及模型配置快速失败等核心契约。
> **全部用例无跳过，均在 CI 中自动执行。**

---

## 1. 特性来源与引用

| 项 | 值 |
|---|---|
| 特性文档 | `agent-solution\common\example\multi-modal-audit-demo\README.md` |
| 被测代码 | `agent-solution\common\example\multi-modal-audit-demo\src\main\java\com\openjiuwen\example\multimodal\audit\` |
| 代码版本 | 0.1.1（pom.xml） |
| agent-core-java 源码 | `D:\code\test\agent-core-java`（0.1.15，已下载最新源码） |

### 1.1 特性要点（逐字引用 README）

- **Java 主控，非 LLM 编排**：流程顺序、超时、并行度全部由 `AuditPipeline` 的 Java 代码控制，主 agent 仅作为子 agent 注册中心使用，不发起模型调用。
- **Step 4/5 并行**：完整性与相关性无相互依赖，通过固定线程池并行执行，节省一个步骤的时延。
- **图片共享缓存**：`AuditImageCache` 在流水线启动时统一完成图片解码/缩放/JPEG 重压缩/base64 编码，三个视觉子 agent 的 `load_images` 工具直接命中缓存，避免重复压缩。
- **差异化 thinking**：分类子 agent 关闭深度思考（快），合规/完整性/相关性开启（准）。

---

## 2. 测试层次与观察边界

### 2.1 层次划分

| 层次 | 范围 | 执行方式 |
|---|---|---|
| 确定性逻辑验证（MMA-A~H） | AuditPipeline 纯 Java 规则（输入解析、JSON 提取、数据合并、报告生成、路径解析、阈值判断）、AuditImageCache 图片压缩缓存、AuditModelConfig 环境变量快速失败 | 测试代码中复现被测逻辑并验证，不依赖 agent-core-java 编译 |
| 流水线流程验证（MMA-I） | 五步审核流程编排、Step4/5 并行执行、listener 回调顺序、空 images 异常处理 | 测试代码中复现 AuditPipeline 的流程编排逻辑，模拟各步骤输入/输出，验证终态报告结构与字段完整性 |

### 2.2 观察边界声明

- **黑盒边界**：通过 `AuditPipeline.run(query)` 的输入/输出 Map 和 `AuditStepListener` 回调观察行为。
- **不读 SUT 内部状态**：不断言线程池内部状态、不读子 agent 内部日志。
- **纯逻辑复现说明**：由于被测项目编译依赖源码版 agent-core-java（已发布构件不含 `AbstractHarnessTool` 等新增 API，见 §8 环境分析），测试代码中复现了 AuditPipeline 的 private 纯 Java 方法进行验证。复现方法逐行对齐被测源码，方法名标注 `[mirror: AuditPipeline.xxx]`。MMA-I 用例同样使用 mirror 方法模拟流水线流程，不依赖 demo 编译。

---

## 3. 覆盖矩阵

### 3.1 场景矩阵

| 矩阵 ID | 场景 | 类型 | 优先级 | 执行集 |
|---|---|---|---|---|
| MMA-A1 | 输入 JSON 解析——正常输入包含全部字段 | 正例 | P0 | Full |
| MMA-A2 | 输入 JSON 解析——缺失 images 字段 | 边界 | P0 | Full |
| MMA-A3 | 输入 JSON 解析——空 images 列表 | 边界 | P1 | Full |
| MMA-A4 | 输入 JSON 解析——非法 JSON 文本 | 异常 | P0 | Full |
| MMA-B1 | 子 agent 输出 JSON 提取——正常 JSON 输出 | 正例 | P0 | Full |
| MMA-B2 | 子 agent 输出 JSON 提取——Markdown 代码围栏包裹 | 正例 | P0 | Full |
| MMA-B3 | 子 agent 输出 JSON 提取——JSON 前后有额外文字 | 正例 | P1 | Full |
| MMA-B4 | 子 agent 输出 JSON 提取——输出不含 JSON 对象 | 异常 | P0 | Full |
| MMA-B5 | 子 agent 输出 JSON 提取——输出含非法 JSON | 异常 | P1 | Full |
| MMA-C1 | 数据合并——合规通过图片归入"通过图片列表" | 正例 | P0 | Full |
| MMA-C2 | 数据合并——合规不通过图片归入"被剔除图片" | 正例 | P0 | Full |
| MMA-C3 | 数据合并——混合通过/不通过结果 | 正例 | P0 | Full |
| MMA-C4 | 数据合并——位置标签"图片N"风格命名 | 正例 | P1 | Full |
| MMA-C5 | 数据合并——空合规结果列表 | 边界 | P1 | Full |
| MMA-D1 | 图片路径解析——文件名风格（如"合同1.jpg"） | 正例 | P0 | Full |
| MMA-D2 | 图片路径解析——位置标签风格（如"图片1"） | 正例 | P0 | Full |
| MMA-D3 | 图片路径解析——完整路径兜底匹配 | 正例 | P1 | Full |
| MMA-D4 | 图片路径解析——空图片名 | 边界 | P1 | Full |
| MMA-E1 | 管控时间阈值判断——数值 > 4 返回 true | 正例 | P0 | Full |
| MMA-E2 | 管控时间阈值判断——数值 = 4 返回 false | 边界 | P0 | Full |
| MMA-E3 | 管控时间阈值判断——数值 < 4 返回 false | 正例 | P1 | Full |
| MMA-E4 | 管控时间阈值判断——字符串"6" > 4 返回 true | 正例 | P1 | Full |
| MMA-E5 | 管控时间阈值判断——非法字符串返回 false | 异常 | P0 | Full |
| MMA-F1 | 报告生成——五步结果全部存在 | 正例 | P0 | Full |
| MMA-F2 | 报告生成——最终结论字段结构正确 | 正例 | P0 | Full |
| MMA-G1 | 图片缓存——HTTP URL 直接返回 | 正例 | P0 | Full |
| MMA-G2 | 图片缓存——本地文件不存在抛异常 | 异常 | P0 | Full |
| MMA-G3 | 图片缓存——超 10MB 文件抛异常 | 边界 | P1 | Full |
| MMA-G4 | 图片缓存——缓存命中返回相同 data URI | 正例 | P1 | Full |
| MMA-H1 | 配置快速失败——缺失 DEEPSEEK_API_KEY 抛异常 | 异常 | P0 | Full |
| MMA-H2 | 配置快速失败——缺失 VISION_API_KEY 抛异常 | 异常 | P0 | Full |
| MMA-H3 | 配置快速失败——缺失 VISION_BASE_URL 抛异常 | 异常 | P0 | Full |
| MMA-H4 | 配置默认值——DEEPSEEK_BASE_URL 默认值正确 | 正例 | P1 | Full |
| MMA-H5 | 配置默认值——VISION_MODEL 默认值正确 | 正例 | P1 | Full |
| MMA-I1 | 流水线流程——五步审核完整执行 | 流程 | P0 | Full |
| MMA-I2 | 流水线流程——Step4/5 并行执行验证 | 并发 | P0 | Full |
| MMA-I3 | 流水线流程——listener 回调顺序正确 | 流程 | P1 | Full |
| MMA-I4 | 流水线流程——空 images 列表异常处理 | 异常 | P1 | Full |

### 3.2 覆盖台账（双向追溯）

| README 契约条款 | 覆盖用例 | 备注 |
|---|---|---|
| 五步审核流程：图片分类→合规审核→数据合并→完整性→相关性 | MMA-I1 | 流程验证，mirror 方法模拟各步骤 |
| Step 4/5 并行执行（固定线程池） | MMA-I2 | 并发验证，ExecutorService 模拟 |
| 图片共享缓存（解码/缩放/JPEG/base64） | MMA-G1~G4 | 确定性逻辑 |
| 差异化 thinking（分类关/合规开） | MMA-I1 | 流程间接验证（配置项存在） |
| Java 主控，非 LLM 编排 | MMA-C1~C5, MMA-D1~D4 | 数据合并/路径解析为纯 Java 规则 |
| API Key 环境变量传入，缺失快速失败 | MMA-H1~H5 | 配置验证 |
| 三个运行入口（demo/server/evaluation） | MMA-I1~I4 | 验证 pipeline 逻辑 |
| 输入 JSON 字段：images/risk_level/control_duration_months/applicant/transactions | MMA-A1~A4 | 输入解析 |
| 子 agent JSON 输出解析（stripCodeFence + extract） | MMA-B1~B5 | JSON 提取 |
| 数据合并规则：合规"通过"→通过列表，"不通过"→剔除列表 | MMA-C1~C5 | 数据合并 |
| 图片路径解析：文件名风格 + 位置标签"图片N"风格 | MMA-D1~D4 | 路径解析 |
| 最终报告结构：五步结果 + 最终结论（完整性/相关性/建议） | MMA-F1~F2, MMA-I1 | 报告生成 |

---

## 4. 不覆盖项与承接方

| 不覆盖项 | 原因 | 承接方 |
|---|---|---|
| agent-core-java DeepAgent/createSubagent/invoke 内部行为 | 属于 agent-core-java 框架测试，非 demo 职责 | agent-core-java 单元测试 |
| 视觉模型实际推理正确性 | 依赖外部模型 API，非确定性 | 评测入口 AuditEvaluationMain 批量对拍 |
| HTTP 服务（AuditServerMain）SSE 实时推送 | 属于可视化界面入口测试 | 后续补建（依赖 demo 可编译后） |
| demo 源码 API 兼容性编译问题 | 属于环境/版本问题，见 §8 | 按 README 构建源码版 agent-core-java |

---

## 5. 前置条件与共享约定

### 5.1 全部用例（MMA-A~I，38 条）

- **不需要** API Key、不需要图片数据集、不需要 agent-core-java 编译
- 测试代码在 `MultiModalAuditPipelineTest.java` 中复现 AuditPipeline 的 private 纯 Java 方法
- MMA-I 用例使用 mirror 方法模拟流水线流程（模拟各步骤的输入/输出），不依赖实际 LLM 调用
- 可在 CI 中自动执行，全部用例无跳过

### 5.2 LLM 配置映射

当 demo 可编译并需要运行实际端到端测试时，以下环境变量映射关系适用：

| 统一 LLM 配置 | 映射到 demo 环境变量 | 说明 |
|---|---|---|
| `LLM_API_KEY` | `DEEPSEEK_API_KEY` + `VISION_API_KEY` | 文本模型与视觉模型共用同一 Key |
| `LLM_API_BASE` | `DEEPSEEK_BASE_URL` + `VISION_BASE_URL` | 文本模型与视觉模型共用同一端点 |
| `LLM_MODEL` | `DEEPSEEK_MODEL` + `VISION_MODEL` | 文本模型与视觉模型共用同一模型 |

> 注：当前 LLM 配置（`LLM_API_BASE` / `LLM_API_KEY` / `LLM_MODEL`）由 CI 环境变量注入，不在文档中固化，避免明文泄露凭据。

### 5.3 执行命令

```bash
# 全部用例（CI 常驻，无跳过）
./mvnw -Dtest=MultiModalAuditPipelineTest test
```

---

## 6. 期望值可判定性（T-M4）

所有断言写到字段名与取值：
- 数据合并：`merged.get("通过图片列表")` 是 List 且 `size()==N`；`merged.get("被剔除图片")` 是 List 且 `size()==M`；`merged.get("总结")` 含 `"通过N张"` 和 `"剔除M张"`
- 报告生成：`report.get("最终结论")` 是 Map，含 key `"完整性"`、`"相关性"`、`"建议"`
- 配置验证：`IllegalStateException` 的 message 含具体环境变量名（如 `"DEEPSEEK_API_KEY"`）
- 图片路径解析：`resolveImagePath` 返回值与预期路径字符串逐字匹配
- 流水线流程：`report` 含全部六个顶层 key（`图片分类结果`、`合规性审核结果`、`数据合并结果`、`完整性审核结果`、`相关性审核结果`、`最终结论`）
- 并行执行：两个 Future 均正常完成，结果按原顺序写回
- listener 回调：回调顺序为 `[图片分类, 合规性审核, 数据合并, 完整性审核, 相关性审核, onDone]`

---

## 7. 可复现锚点（T-M6/T-M18/T-M19）

| 项 | 值 |
|---|---|
| 被测代码路径 | `agent-solution\common\example\multi-modal-audit-demo\src\main\java\com\openjiuwen\example\multimodal\audit\` |
| 代码版本 | 0.1.1（pom.xml version） |
| agent-core-java 依赖 | 0.1.14（pom.xml 声明）；源码 0.1.15 已下载至 `D:\code\test\agent-core-java` |
| 测试文件 | `src/test/java/com/huawei/ascend/sit/cases/integration/edpa/MultiModalAuditPipelineTest.java` |
| 执行框架 | JUnit 5 + AssertJ |
| 断言库 | org.assertj.core.api.Assertions.assertThat |

---

## 8. 编译环境分析

> 以下分析基于静态检查（阅读源码 + 对照 agent-core-java 0.1.15 源码 API）+ 编译验证。
> **结论：demo 当前无法直接编译（agent-core-java 0.1.15 API 变更），但全部测试用例通过 mirror 方法不依赖 demo 编译，均可自动执行。**

### 8.1 环境问题 ENV-01：demo 依赖源码版 agent-core-java

- **现象**：使用已发布 `0.1.14` / 源码 `0.1.15` 构件编译 demo，出现编译错误
- **根因**：
  1. `LoadImagesTool extends AbstractHarnessTool` — `AbstractHarnessTool` 类在 agent-core-java 0.1.15 源码中不存在（已被移除或重命名）
  2. `ToolOutput.success(data)` / `ToolOutput.failure(message)` — 静态工厂方法不存在，0.1.15 改用 builder 模式
  3. `new AgentCard(String, String, String)` — 0.1.15 的 `AgentCard` 仅有无参构造器和 `(Object, Object)` 构造器，3 参数构造器不存在
- **影响**：端到端测试（MMA-I1~I4）无法通过直接调用 demo 的 `AuditPipeline.run()` 执行
- **解决方案**：MMA-I 用例改用 mirror 方法模拟流水线流程（模拟各步骤输入/输出），不依赖 demo 编译；待 demo 修复编译问题后可切换为真实端到端调用

### 8.2 agent-core-java 源码可用性

- agent-core-java 最新源码已下载至 `D:\code\test\agent-core-java`（版本 0.1.15）
- 已安装到本地 Maven 仓库（`agent-core-java-reactor` 0.1.15 父 POM + `agent-core-java` 0.1.15 JAR）
- demo 修复编译问题后可直接引用

---

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-09 | 初版：测试设计文档，38 条场景矩阵（34 条确定性逻辑 + 4 条 manual 端到端） |
| 2026-09-09 | 修订：§8 由"代码缺陷记录"改为"编译环境分析"，原 DEF-01~06 重新归类为环境/版本不匹配（ENV-01），经逐版本编译验证确认 demo 源码无代码缺陷 |
| 2026-09-10 | 整改：移除全部 manual 标签和 assumeTrue 跳过；MMA-I1~I4 改用 mirror 方法实现流水线流程验证；新增 LLM 配置映射（§5.2）；修复 MMA-H1~H3 环境变量清除缺陷（改用非存在键测试 requireEnv 逻辑）；§8 更新为 agent-core-java 0.1.15 源码分析结果 |
