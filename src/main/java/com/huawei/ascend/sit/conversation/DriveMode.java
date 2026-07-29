package com.huawei.ascend.sit.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 驱动模式。spec §5.2：
 * <ul>
 *   <li>{@link StepUi} —— 反应式：每步查中台 step-ui 裁定 auto/manual/终态（默认，本期）。</li>
 *   <li>{@link Script} —— 步计数：按声明的 advance/select 序列推进，不查 step-ui、不依赖外部 YAML。</li>
 *   <li>{@link ParallelStepUi} —— 并发扇出：kickoff 后从 _remote_invocation 元数据派生子会话 id，并发驱动每个子会话。</li>
 * </ul>
 */
public sealed interface DriveMode permits DriveMode.StepUi, DriveMode.Script, DriveMode.ParallelStepUi {

    /** 反应式（查 step-ui）。单例即可。 */
    record StepUi() implements DriveMode {}

    /** 声明式步计数脚本。 */
    record Script(List<ScriptInstruction> instructions, Optional<Integer> stopAfter) implements DriveMode {}

    /** 单条指令：advance(kv=空) 或 select(kv)。label 为可选 step_id 标注（SCRIPT 下仅记录）。 */
    record ScriptInstruction(Map<String, String> kv, String label) {
        public boolean isSelect() { return kv != null && !kv.isEmpty(); }
    }

    /**
     * 并发扇出：kickoff 后从 kickoff 流的 _remote_invocation 元数据派生子会话 id，并发驱动每个子会话。
     * sharedSelections 是单一列表，原样应用到每个子会话（每个子会话消费自己的位置序副本）——
     * 当所有并发腿需要相同的手动步时合法。
     */
    record ParallelStepUi(List<DeclaredSelection> sharedSelections) implements DriveMode {
        public ParallelStepUi {
            sharedSelections = sharedSelections == null ? List.of() : List.copyOf(sharedSelections);
        }
    }

    static StepUi stepUi() { return new StepUi(); }

    static ScriptBuilder script() { return new ScriptBuilder(); }

    /** 从原始 kv map（label 为 null）构建 ParallelStepUi。 */
    static ParallelStepUi parallelStepUi(List<Map<String, String>> sharedSelections) {
        return new ParallelStepUi(sharedSelections.stream()
                .map(kv -> new DeclaredSelection(null, kv))
                .toList());
    }

    /** SCRIPT 构建器：advance()/advance(n)/select(kv)/select(label,kv)；终态 stopsAfter(n)/untilDone()→Script。 */
    final class ScriptBuilder {
        private final List<ScriptInstruction> instructions = new ArrayList<>();
        private Optional<Integer> stopAfter = Optional.empty();

        public ScriptBuilder advance() { instructions.add(new ScriptInstruction(Map.of(), null)); return this; }
        public ScriptBuilder advance(int n) { for (int i = 0; i < n; i++) advance(); return this; }
        public ScriptBuilder select(Map<String, String> kv) { return select(null, kv); }
        public ScriptBuilder select(String label, Map<String, String> kv) {
            instructions.add(new ScriptInstruction(kv == null ? Map.of() : kv, label)); return this;
        }
        /** 硬上限:最多推进 {@code total} 步(不超过声明指令数),到 total 即停——不尾随收口。 */
        public Script stopsAfter(int total) { this.stopAfter = Optional.of(total); return new Script(List.copyOf(instructions), stopAfter); }
        /** 无 cap:先跑完声明的 advance/select,再<b>尾随空 advance</b>直到 next-request 返回 null(工作流自然 END)。
         *  末腿(如多腿转账的最后一笔)不会因"声明指令用完"而卡在 INPUT_REQUIRED——这是 untilDone 与 {@link #stopsAfter} 的本质差别。 */
        public Script untilDone() { return new Script(List.copyOf(instructions), Optional.empty()); }
    }
}
