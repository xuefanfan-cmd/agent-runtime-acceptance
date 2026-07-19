package com.huawei.ascend.sit.conversation;

import com.huawei.ascend.sit.conversation.mid.dto.NextRequest;
import com.huawei.ascend.sit.conversation.mid.dto.StepUI;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单轮 flow：一次自然语言输入 + 自推进循环（STEP_UI 或 SCRIPT）。{@link #run()} 返回 {@link TurnResult}。
 * 选择为位置序数据：声明的 {@code select(kv)} 在每个 needsSelection 步逐个消费（STEP_UI）或按指令位置（SCRIPT）。
 */
public final class Turn {

    private final Conversation conv;
    private final String input;
    private final int maxInteractions;   // 安全护栏:单轮 SUT POST 上限(kick-off + 续轮),来自 conv
    private String intent = "";
    private final List<DeclaredSelection> selections = new ArrayList<>();
    private DriveMode mode = DriveMode.stepUi();

    private record DeclaredSelection(String label, Map<String, String> kv) {}

    /** 驱动循环的结果:终态 StepUI(SCRIPT=null) + 是否被交互上限熔断。 */
    private record DriveOutcome(StepUI terminal, boolean capped) {
        static DriveOutcome done(StepUI s) { return new DriveOutcome(s, false); }
        static DriveOutcome capped(StepUI s) { return new DriveOutcome(s, true); }
    }

    Turn(Conversation conv, String input) {
        this.conv = conv;
        this.input = input;
        this.maxInteractions = conv.maxInteractions();
    }

    public Turn intent(String i) { this.intent = i; return this; }
    public Turn select(Map<String, String> kv) { return select(null, kv); }
    public Turn select(String label, Map<String, String> kv) {
        selections.add(new DeclaredSelection(label, kv)); return this;
    }
    public Turn driveMode(DriveMode m) { this.mode = m; return this; }

    public TurnResult run() {
        Instant start = Instant.now();
        List<Step> steps = new ArrayList<>();
        // ---- Step 0: kick-off ----
        ConversationRequest body0 = ConversationRequest.from(conv.identity())
                .query(input).intent(intent).conversationId(conv.cidValue()).build();
        steps.add(post(0, null, null, Map.of(), body0));

        DriveOutcome outcome;
        if (mode instanceof DriveMode.StepUi) {
            outcome = driveStepUi(steps);
        } else if (mode instanceof DriveMode.Script sc) {
            outcome = driveScript(steps, sc);
        } else {
            outcome = DriveOutcome.done(null);   // 不可达:DriveMode 密封为 StepUi/Script
        }
        if (outcome.capped()) {
            // 故障信号:循环未到自然终态就被 maxInteractions 停住——被调业务疑似死循环。
            // 打 stderr 让故障在输出里可见(即便用例没断言 capped);已捕获的 steps/SSE 供诊断。
            System.err.println("[turn-safety] 交互上限 " + maxInteractions + " 熔断:本轮未到自然终态即停 "
                    + "(被调业务疑似故障/死循环)。steps=" + steps.size());
        }
        TurnResult result = new TurnResult(input, steps, outcome.terminal(), outcome.capped(),
                Duration.between(start, Instant.now()));
        conv.recordTurn(result);
        return result;
    }

    private DriveOutcome driveStepUi(List<Step> steps) {
        int idx = 1;
        int selIdx = 0;
        StepUI lastS = null;
        while (true) {
            // 安全护栏:被调业务故障(step-ui 永不完成 + next-request 永不空)会让此循环无限跑;
            // 到 maxInteractions 即熔断,标记 capped 让用例察觉,而非挂死测试。
            if (steps.size() >= maxInteractions) return DriveOutcome.capped(lastS);
            StepUI s = conv.mid().stepUi(conv.cidValue());
            lastS = s;
            if (s.isWorkflowComplete()) return DriveOutcome.done(s);
            Map<String, String> kv = Map.of();
            String label = null;
            if (s.needsSelection()) {
                DeclaredSelection ds = consumeSelection(selIdx++, s);   // 位置序消费
                kv = ds.kv();
                label = ds.label();   // consumeSelection 已校验 label==s.stepId()（若非空）
            }
            NextRequest nr = conv.mid().nextRequest(conv.cidValue(), kv);
            if (nr.query() == null) return DriveOutcome.done(s);
            ConversationRequest body = ConversationRequest.from(conv.identity())
                    .query(nr.query()).intent("LATEST").conversationId(conv.cidValue()).build();
            steps.add(post(idx++, s, label, kv, body));
        }
    }

    private DriveOutcome driveScript(List<Step> steps, DriveMode.Script sc) {
        int idx = 1;
        int cap = sc.stopAfter().orElse(Integer.MAX_VALUE);
        boolean untilDone = sc.stopAfter().isEmpty();   // untilDone=无 cap;stopsAfter(n)=硬上限
        int advanced = 0;
        // Phase 1:声明的 advance/select 序列(按 cap 截断)。
        for (DriveMode.ScriptInstruction instr : sc.instructions()) {
            if (advanced >= cap) return DriveOutcome.done(null);            // stopsAfter 上限到达
            if (steps.size() >= maxInteractions) return DriveOutcome.capped(null);  // 安全护栏
            NextRequest nr = conv.mid().nextRequest(conv.cidValue(), instr.kv());
            if (nr.query() == null) return DriveOutcome.done(null);         // 工作流自然结束
            ConversationRequest body = ConversationRequest.from(conv.identity())
                    .query(nr.query()).intent("LATEST").conversationId(conv.cidValue()).build();
            steps.add(post(idx++, null, instr.label(), instr.kv(), body));
            advanced++;
        }
        // Phase 2:untilDone 收口——指令耗尽后继续空 advance,直到 next-request 返回 null(工作流真正 END)。
        // 这是 untilDone 与 stopsAfter 的本质差别:末腿(如多腿转账的最后一笔)不会因"声明指令用完"而卡在
        // INPUT_REQUIRED,而是被推到回包。stopsAfter(n) 不收口:它是硬上限,到 n 即停。
        if (untilDone) {
            while (true) {
                if (steps.size() >= maxInteractions) return DriveOutcome.capped(null);  // 安全护栏
                NextRequest nr = conv.mid().nextRequest(conv.cidValue(), Map.of());
                if (nr.query() == null) return DriveOutcome.done(null);     // 工作流自然结束
                ConversationRequest body = ConversationRequest.from(conv.identity())
                        .query(nr.query()).intent("LATEST").conversationId(conv.cidValue()).build();
                steps.add(post(idx++, null, null, Map.of(), body));
            }
        }
        return DriveOutcome.done(null);
    }

    private DeclaredSelection consumeSelection(int selIdx, StepUI s) {
        if (selIdx >= selections.size()) {
            throw new IllegalStateException("manual 步骤 " + s.stepId()
                    + " 需要选择，但声明的 select 已耗尽（已用 " + selIdx + "，声明 " + selections.size() + "）");
        }
        DeclaredSelection ds = selections.get(selIdx);
        if (ds.label() != null && !ds.label().equals(s.stepId())) {
            throw new IllegalStateException("选择标注漂移：声明 step_id=" + ds.label()
                    + " 但实际=" + s.stepId());
        }
        return ds;
    }

    private Step post(int index, StepUI stepUi, String label, Map<String, String> kv, ConversationRequest body) {
        ConversationEventCollector c = new ConversationEventCollector();
        Instant s = Instant.now();
        ConversationOutbound out = new ConversationOutbound(
                conv.gatewayBaseUrl(),
                conv.identity().projectId(),
                conv.identity().agentId(),
                conv.cidValue(),
                conv.identity().workspaceId(),
                body.toJson(),
                conv.identity().roleName(),
                conv.identity().roleId(),
                String.valueOf(conv.timeout().getSeconds()));
        conv.transport().send(out, c);
        c.awaitStreamEnd(conv.timeout().toMillis());
        return new Step(index, stepUi, label, kv, body, c.snapshot(), Duration.between(s, Instant.now()));
    }
}
