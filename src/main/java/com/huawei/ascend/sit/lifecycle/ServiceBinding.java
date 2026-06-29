package com.huawei.ascend.sit.lifecycle;

/**
 * One per-agent binding of a backing service's dynamic address into a Spring property key, read from
 * {@code sut.agents.<name>.service-bindings.<service>}. The {@code urlTemplate} wraps the resolved
 * address ({@code {{url}}} = {@code host:mappedPort} managed, or the remote url); default {@code "{{url}}"}.
 */
public record ServiceBinding(String serviceName, String urlKey, String urlTemplate) {
    public ServiceBinding {
        if (urlTemplate == null || urlTemplate.isBlank()) {
            urlTemplate = "{{url}}";
        }
    }
}
