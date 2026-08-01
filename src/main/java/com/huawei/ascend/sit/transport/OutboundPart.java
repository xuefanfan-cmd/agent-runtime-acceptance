package com.huawei.ascend.sit.transport;

import java.util.Map;

/**
 * One A2A message part for a multi-part (batch) outbound: the part {@code text} plus its
 * {@code metadata} (the parallel-resume routing channel — {@code {toolCallId: <child>}}).
 * Transport builds one {@code TextPart} per {@code OutboundPart} into {@code Message.parts}.
 */
public record OutboundPart(String text, Map<String, Object> metadata) {
}
