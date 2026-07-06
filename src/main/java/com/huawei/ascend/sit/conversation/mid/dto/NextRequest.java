package com.huawei.ascend.sit.conversation.mid.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NextRequest(
        String query,                                         // 渲染好的请求体 JSON 字符串；null=工作流结束
        String interaction,
        @JsonProperty("ui_hint") String uiHint,
        @JsonProperty("step_id") String stepId) {}
