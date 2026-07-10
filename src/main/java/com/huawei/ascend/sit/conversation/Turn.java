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
    private String intent = "";
    private final List<DeclaredSelection> selections = new ArrayList<>();
    private DriveMode mode = DriveMode.stepUi();

    private record DeclaredSelection(String label, Map<String, String> kv) {}

    Turn(Conversation conv, String input) { this.conv = conv; this.input = input; }

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

        StepUI terminal = null;
        if (mode instanceof DriveMode.StepUi) {
            terminal = driveStepUi(steps);
        } else if (mode instanceof DriveMode.Script sc) {
            terminal = driveScript(steps, sc);
        }
        TurnResult result = new TurnResult(input, steps, terminal, Duration.between(start, Instant.now()));
        conv.recordTurn(result);
        return result;
    }

    private StepUI driveStepUi(List<Step> steps) {
        int idx = 1;
        int selIdx = 0;
        while (true) {
            StepUI s = conv.mid().stepUi(conv.cidValue());
            if (s.isWorkflowComplete()) return s;
            Map<String, String> kv = Map.of();
            String label = null;
            if (s.needsSelection()) {
                DeclaredSelection ds = consumeSelection(selIdx++, s);   // 位置序消费
                kv = ds.kv();
                label = ds.label();   // consumeSelection 已校验 label==s.stepId()（若非空）
            }
            NextRequest nr = conv.mid().nextRequest(conv.cidValue(), kv);
            if (nr.query() == null) return s;
            ConversationRequest body = ConversationRequest.from(conv.identity())
                    .query(nr.query()).intent("LATEST").conversationId(conv.cidValue()).build();
            steps.add(post(idx++, s, label, kv, body));
        }
    }

    private StepUI driveScript(List<Step> steps, DriveMode.Script sc) {
        int idx = 1;
        int cap = sc.stopAfter().orElse(Integer.MAX_VALUE);
        boolean untilDone = sc.stopAfter().isEmpty();   // untilDone=无 cap;stopsAfter(n)=硬上限
        int advanced = 0;
        // Phase 1:声明的 advance/select 序列(按 cap 截断)。
        for (DriveMode.ScriptInstruction instr : sc.instructions()) {
            if (advanced >= cap) return null;            // stopsAfter 上限到达
            NextRequest nr = conv.mid().nextRequest(conv.cidValue(), instr.kv());
            if (nr.query() == null) return null;         // 工作流自然结束
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
                NextRequest nr = conv.mid().nextRequest(conv.cidValue(), Map.of());
                if (nr.query() == null) return null;     // 工作流自然结束
                ConversationRequest body = ConversationRequest.from(conv.identity())
                        .query(nr.query()).intent("LATEST").conversationId(conv.cidValue()).build();
                steps.add(post(idx++, null, null, Map.of(), body));
            }
        }
        return null;
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
        conv.gatewayClient().postConversation(conv.gatewayBaseUrl(),
                conv.identity().projectId(), conv.identity().agentId(), conv.cidValue(),
                conv.identity().workspaceId(), body.toJson(), c);
        c.awaitStreamEnd(conv.timeout().toMillis());
        return new Step(index, stepUi, label, kv, body, c.snapshot(), Duration.between(s, Instant.now()));
    }
}
