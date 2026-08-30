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
 *   <li>{@link ParallelStepUi} —— 并发扇出（FEAT-027 线格式）：某轮终态 interrupt 携带 ≥2 个待输入远端成员
 *       即判定扇出，经中台会话列表发现子会话 id 后并发驱动每个子会话。</li>
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
     * 并发扇出（FEAT-027 线格式）：某轮终态 interrupt 携带 ≥2 个待输入远端成员
     * （{@code _interrupt.items[].toolCallId}）即判定扇出；子会话 mid cid 经中台会话列表发现
     * （运行时推导 {@code parentCid_<batchId>_<toolCallId>}，不再上线可推导），续传按
     * {@code parts[].metadata.toolCallId} 路由，交织回复按生产者标签 {@code agentEvent.source} 归属。
     *
     * <p>选择按 <b>step_id 键控</b>：{@code selectionByStepId} 把每个需选择的人工步 step_id 映射到要注入的
     * kv。每个子会话在自己当前的 step 上按 step_id 查表取 kv——与腿序无关。这样当并发腿<b>非对称</b>时
     * （例如 parallel-transfer 里一腿因收款人未预解析而多一个 on_paycard_input 选卡步，另一腿没有），
     * 每条腿仍拿到<b>该步</b>正确的选择值，而不是被位置序错配（旧的位置序模型会把 accIndex 喂给一腿的
     * 确认步、把列表提前耗尽）。step_id 重复出现（选择未被推进而重提）也安全：同一 step_id → 同一 kv，
     * 由 maxPerChild 上限兜底。
     */
    record ParallelStepUi(Map<String, Map<String, String>> selectionByStepId) implements DriveMode {
        public ParallelStepUi {
            selectionByStepId = selectionByStepId == null ? Map.of() : Map.copyOf(selectionByStepId);
        }
    }

    static StepUi stepUi() { return new StepUi(); }

    static ScriptBuilder script() { return new ScriptBuilder(); }

    /** 按 step_id 键控构建 ParallelStepUi：step_id → 该步注入的 kv。 */
    static ParallelStepUi parallelStepUi(Map<String, Map<String, String>> selectionByStepId) {
        return new ParallelStepUi(selectionByStepId);
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
