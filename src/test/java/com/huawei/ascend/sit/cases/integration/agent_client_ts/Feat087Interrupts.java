package com.huawei.ascend.sit.cases.integration.agent_client_ts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.huawei.ascend.sit.cases.integration.agent_client_ts.Feat087MockA2aServer.JSON;

/** _interrupt 待办构造（L2 §5.7 分类输入）。 */
final class Feat087Interrupts {
    private Feat087Interrupts() { }

    static ObjectNode clientTool(String toolCallId, String toolName, ObjectNode arguments, boolean withKind) {
        ObjectNode item = JSON.createObjectNode();
        item.put("toolCallId", toolCallId).put("toolName", toolName);
        ObjectNode context = item.putObject("context");
        if (arguments != null) {
            context.set("arguments", arguments);
        }
        if (withKind) {
            context.put("_interrupt_kind", "client_tool");
        }
        return item;
    }

    static ObjectNode userInput(String pendingId, String prompt) {
        ObjectNode item = JSON.createObjectNode();
        if (pendingId != null) {
            item.put("toolCallId", pendingId);
        }
        item.putObject("context").put("_interrupt_kind", "user_input");
        item.put("message", prompt);
        return item;
    }

    static ObjectNode noKindNoToolName(String message) {
        ObjectNode item = JSON.createObjectNode();
        item.put("toolCallId", "call-orphan");
        item.put("message", message);
        return item;
    }

    static ObjectNode confirmation(String toolCallId, String toolName, String risk, ArrayNode actions) {
        ObjectNode item = JSON.createObjectNode();
        item.put("toolCallId", toolCallId).put("toolName", toolName);
        ObjectNode context = item.putObject("context");
        context.put("_interrupt_kind", "tool_permission_ask");
        ObjectNode ask = context.putObject("guardrail_ask");
        ask.put("command_summary", "delete file /tmp/x");
        ask.putArray("matched_rules");
        ask.putArray("file_guard_hits");
        ask.put("decision", "ask_user");
        if (risk != null) {
            ask.put("risk_hint", risk);
        }
        ask.set("actions", actions == null ? defaultActions() : actions);
        return item;
    }

    static ArrayNode defaultActions() {
        ArrayNode actions = JSON.createArrayNode();
        actions.add("allow").add("reject").add("cancel");
        return actions;
    }

    static ObjectNode items(JsonNode... items) {
        ObjectNode interrupt = JSON.createObjectNode();
        ArrayNode arr = interrupt.putArray("items");
        for (JsonNode item : items) {
            arr.add(item);
        }
        return interrupt;
    }

    static ObjectNode single(JsonNode item) {
        return item.deepCopy();
    }
}
