package com.huawei.ascend.sit.cases.integration.edpa;

/**
 * FEAT-028 EDPA 并行验收用例的 <b>prompt 库常量</b>。
 *
 * <p>每个 prompt 对应 testplan §5 场景矩阵的一个或多个 ID，硬编码 planrule.yaml
 * 「并行判定准则」（独立性 + 无副作用冲突 + 收益显著 + 目标可异）的触发形态。
 *
 * <p><b>为什么把 prompt 集中在常量类</b>：LLM 抖动是本方案的主要不确定性来源；把 prompt 集中
 * 便于统一微调（如「同时」→「并行」→「务必同轮」的措辞演进），避免在多用例类里散落。
 *
 * <p>见 {@code docs/testplan/FEAT-028-...-edpa.md} §4 部署拓扑「场景规则驱动 prompt 库」。
 */
final class EdpaParallelPrompts {

    private EdpaParallelPrompts() {}

    /**
     * 同类型批量并行（P1/P3/C1/C2/C3/N1）—— 用户显式要求同轮 2 个独立查询，均由 search-agent 承接。
     * 触发预期：模型同一轮生成 2 个 {@code call_subagent(agent_name="search-agent", ...)}。
     */
    static final String PROMPT_HOMOG_PARALLEL =
            "请同时用搜索工具帮我查两件事：Java 21 虚拟线程的核心特性；Java 21 GC 的核心变化。"
                    + "两件事互不依赖，请并行进行。";

    /**
     * 异构混合并行（P2/P4）—— 用户显式要求同轮 search + verify 两类不同工具，各自独立。
     * 触发预期：模型同一轮生成 1 个 {@code call_subagent(search-agent)} + 1 个 {@code call_subagent(verify-agent)}。
     */
    static final String PROMPT_HETERO_PARALLEL =
            "请同时做两件事：用搜索工具查 Java 21 虚拟线程的特性；"
                    + "用验证工具核查『Java 21 虚拟线程能解决线程池 OOM 问题』这个说法。两件事互不依赖。";

    /**
     * 依赖串行反证（P5）—— 用户显式要求「先搜再验证」，第 2 步依赖第 1 步的结果。
     * 触发预期：模型只在当前轮生成 1 个 ToolCall；第 2 个 ToolCall 出现在第 1 个 tool_result 之后的新一轮。
     */
    static final String PROMPT_DEPENDENT_SERIAL =
            "请先用搜索工具查 Java 21 虚拟线程的官方特性说明，"
                    + "然后根据搜索到的第一条结论，用验证工具核查该结论是否准确。";

    /**
     * 单实体单委托（P6）—— 仅一件事、单工具。
     * 触发预期：模型生成单 ToolCall，走单成员兼容路径。
     */
    static final String PROMPT_SINGLE_ENTITY =
            "请用搜索工具查 Java 21 虚拟线程的核心特性。";

    // 2026-08-24：原 PROMPT_MIXED_TERMINAL_STATE / PROMPT_MIXED_TERMINAL_RESUME（P7 混合终态）
    // 已随 P7 用例一并删除——设计团队确认 FEAT-028 当前不考虑子任务 INPUT_REQUIRED 投影 +
    // 客户端接续场景，相关能力由 FEAT-008 方案在其成熟后另立条目验收。历史设计与三跑真机
    // 记录归档于 docs/cases/FEAT-028-*.md §5.2.3。
}
