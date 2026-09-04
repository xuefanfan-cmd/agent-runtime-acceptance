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
     * 触发预期：模型只在当前轮生成 1 个 ToolCall；第 2 个 ToolCall 出现在第 1 个结果回灌之后的新一轮。
     *
     * <p><b>措辞层次说明</b>（2026-09-02）：这里的 ToolCall / 结果回灌指 <b>agent-core 内部推理轮次</b>
     * （原文写「第 1 个 tool_result 之后」易与 wire 事件混淆）。wire 上不存在 {@code tool_result}
     * 事件类型——FEAT-027 §3.1 的 {@code agentEvent.type} 是闭集 {@code delegation | output | status}。
     * 客户端黑盒面的等价投影是「两条 delegation 的时间窗不重叠」。
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

    // ------------------------------------------------------------------
    // 主题关键词候选集 —— 与 prompt 成对维护，不得散落到各用例类
    // ------------------------------------------------------------------
    //
    // 用途有两处，二者判据等级不同，别混用：
    //   (a) 主题覆盖断言（P3/P4 已落码）——终文本里两个主题都出现，证明"两件事都办了"。
    //   (b) 合并实体判据（P3/P4 ⬜→本次落码）——**单个** ToolCall 的重组后 arguments 里
    //       同时出现两个主题，即 EDPA L2 §7.3「模型合并多个实体 → 视为规划质量问题，验收判失败」。
    //
    // 设计约束（testplan §5 注）：只用关键词命中，**不做语义相似度、不引 LLM 裁判**——
    // 判据必须可复算、可人工复核。代价是措辞抖动会漏判（模型换词即命中不到），
    // 故 (b) 的失败方向是**保守的**：命中不到 → INCONCLUSIVE，不是 PASS 也不是 FAIL。
    //
    // 两个集合必须**互斥**（无共同词），否则任何单主题命中都会被误算成"两主题都命中"→ 误红。

    /** P1/P3/C1/C2/C3/N1 同类并行 —— 主题甲：虚拟线程。 */
    static final String[] HOMOG_TOPIC_A = {"虚拟线程", "Virtual Thread", "virtual thread", "VirtualThread"};

    /** P1/P3/C1/C2/C3/N1 同类并行 —— 主题乙：GC。与 {@link #HOMOG_TOPIC_A} 互斥。 */
    static final String[] HOMOG_TOPIC_B = {"GC", "ZGC", "G1", "Shenandoah", "垃圾回收", "垃圾收集"};

    /**
     * P2/P4 异构并行 —— 主题甲：搜索侧「查特性」。
     *
     * <p><b>为什么不能直接用「虚拟线程」</b>：P4 的两件事**都**围绕虚拟线程
     * （查特性 / 核查 OOM 说法），「虚拟线程」是两主题的共同词，用它做区分等于两边恒命中，
     * 合并实体判据会退化成恒红。故异构侧的区分词取**动作意图**而非**实体名**。
     */
    static final String[] HETERO_TOPIC_A = {"核心特性", "特性说明", "官方特性", "有哪些特性"};

    /** P2/P4 异构并行 —— 主题乙：验证侧「核查 OOM 说法」。与 {@link #HETERO_TOPIC_A} 互斥。 */
    static final String[] HETERO_TOPIC_B = {"OOM", "线程池", "核查", "是否准确", "这个说法"};

    /** P6 单实体 —— 唯一主题，用于「单实体不应被拆」的对照。 */
    static final String[] SINGLE_TOPIC = {"虚拟线程", "Virtual Thread", "virtual thread"};

    /** 大小写不敏感的关键词命中。 */
    static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String n : needles) {
            if (lower.contains(n.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    /** 返回 text 命中的关键词清单（诊断用，判红时必须打出来供人工复核）。 */
    static java.util.List<String> hits(String text, String... needles) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String n : needles) {
            if (lower.contains(n.toLowerCase(java.util.Locale.ROOT))) out.add(n);
        }
        return out;
    }

    // 2026-08-24：原 PROMPT_MIXED_TERMINAL_STATE / PROMPT_MIXED_TERMINAL_RESUME（P7 混合终态）
    // 已随 P7 用例一并删除——设计团队确认 FEAT-028 当前不考虑子任务 INPUT_REQUIRED 投影 +
    // 客户端接续场景，相关能力由 FEAT-008 方案在其成熟后另立条目验收。历史设计与三跑真机
    // 记录归档于 docs/cases/FEAT-028-*.md §5.2.3。
}
