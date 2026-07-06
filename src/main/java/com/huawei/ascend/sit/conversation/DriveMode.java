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
 * </ul>
 */
public sealed interface DriveMode permits DriveMode.StepUi, DriveMode.Script {

    /** 反应式（查 step-ui）。单例即可。 */
    record StepUi() implements DriveMode {}

    /** 声明式步计数脚本。 */
    record Script(List<ScriptInstruction> instructions, Optional<Integer> stopAfter) implements DriveMode {}

    /** 单条指令：advance(kv=空) 或 select(kv)。label 为可选 step_id 标注（SCRIPT 下仅记录）。 */
    record ScriptInstruction(Map<String, String> kv, String label) {
        public boolean isSelect() { return kv != null && !kv.isEmpty(); }
    }

    static StepUi stepUi() { return new StepUi(); }

    static ScriptBuilder script() { return new ScriptBuilder(); }

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
        public Script stopsAfter(int total) { this.stopAfter = Optional.of(total); return new Script(List.copyOf(instructions), stopAfter); }
        public Script untilDone() { return new Script(List.copyOf(instructions), Optional.empty()); }
    }
}
