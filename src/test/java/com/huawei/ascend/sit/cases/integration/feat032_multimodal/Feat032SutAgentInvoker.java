/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat032_multimodal;

import java.util.List;
import java.util.Map;

/**
 * SUT 全链路直驱 SPI：真实 Agent 闭环场景（F032-01/02/12/13/14/15）的执行后端。
 *
 * <p>这些场景需要构造装配了 multimodal 入口与 ObservationToolSuite 的真实 ReActAgent，
 * 并以记录型 ModelInvoker 捕获每次进入模型调用的消息（L2 §7 验收方法学）。
 * 构造 API（Builder/模型客户端装配）不在 L2 逐字签名范围内，因此以本 SPI 解耦：
 * <ul>
 *   <li>测试侧只依赖本接口与 wire 契约（CapturedModelCall），断言全部落在捕获的消息上；</li>
 *   <li>执行阶段（skill 步骤四）由 provision 脚本注入实现类全名：
 *       系统属性 {@code feat032.sut.invokerClass} 或环境变量
 *       {@code FEAT032_SUT_INVOKER_CLASS}（须有无参构造器且在测试 classpath）；</li>
 *   <li>实现缺失或 SUT 制品未就绪时，相关用例按 dependency-gated SKIPPED 并写明原因。</li>
 * </ul>
 *
 * <p>实现约束：密钥仅经进程环境变量注入；实现内不得把密钥写入返回值或落盘。
 *
 * @since 0.1.2
 */
public interface Feat032SutAgentInvoker extends AutoCloseable {

    /** 一次被捕获的模型调用（wire 契约投影）。 */
    record CapturedModelCall(
            /** 该次调用的 UserMessage content parts（type=text / type=image_url{url,detail} 形态）。 */
            List<Map<String, Object>> userContentParts,
            /** 该次调用可见的 ToolMessage 文本内容列表（按对话顺序）。 */
            List<String> toolMessageTexts,
            /** 模型响应文本。 */
            String responseText) {
    }

    /** 一次 Agent 运行的捕获结果。 */
    record CapturedRun(List<CapturedModelCall> modelCalls, String finalAnswer,
                       /** 模型调用链路的显式失败原因（如网关对无视觉模型拒绝 image_url 的 4xx 报文）；
                        *  正常完成时为 null。F032-15 用其区分「显式不兼容错误」与「静默丢图继续作答」。 */
                       String modelCallError) {
    }

    /**
     * F032-01/02：入口直传结构化内容块（text + image raw/path 块），返回每次模型调用的捕获。
     * 实现须把 blocks 作为 ReActAgent.invoke/stream 的结构化输入提交。
     */
    CapturedRun invokeWithStructuredEntry(List<Map<String, Object>> contentBlocks);

    /**
     * F032-12/13：装配 ObservationToolSuite 后运行一个会触发指定工具（如 image_observe）的任务，
     * 返回完整对话轮的捕获（至少 2 次模型调用：工具调用前 + 工具图片回流后）。
     */
    CapturedRun invokeWithToolLoop(String toolId, Map<String, Object> toolArgs);

    /**
     * F032-14：提交结构化输入后触发会话持久化（saveState）与回载（messageFromMap），
     * 再发起一次模型调用，返回回载后那次调用的捕获（用于断言 List content 结构保全）。
     */
    CapturedRun invokeAfterPersistReload(List<Map<String, Object>> contentBlocks);

    /**
     * F032-15：向指定模型 ID 提交图文输入，返回捕获；模型不支持时应呈现
     * 特性档 §2 要求的显式不兼容错误（静默丢图/降级即缺陷）。
     */
    CapturedRun invokeAgainstModel(String modelId, List<Map<String, Object>> contentBlocks);

    @Override
    void close();
}