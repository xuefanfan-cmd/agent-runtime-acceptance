package com.huawei.ascend.sit.conversation.mid.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InteractionData(
        String title,
        @JsonProperty("selection_key") String selectionKey,   // 存在=需选择，null=仅展示
        @JsonProperty("ui_hint") String uiHint,
        List<Column> columns,
        List<Map<String, String>> items,
        List<Field> fields) {                                  // confirm-card 变体用 fields

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Column(String key, String display) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(String label, String value) {}
}
