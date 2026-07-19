package com.huawei.ascend.sit.lifecycle;

/**
 * One per-agent binding of a backing service's dynamic address into a Spring property key, read from
 * {@code sut.agents.<name>.service-bindings.<service>}. The {@code urlTemplate} wraps the resolved
 * address with these placeholders: {@code {{url}}} = full {@code host:mappedPort} (managed) or the
 * remote url; {@code {{host}}}/{@code {{port}}} = the managed host/port split (undefined for remote
 * urls — use {@code {{url}}} there). Default template {@code "{{url}}"}.
 */
public record ServiceBinding(String serviceName, String urlKey, String urlTemplate) {
    public ServiceBinding {
        if (urlTemplate == null || urlTemplate.isBlank()) {
            urlTemplate = "{{url}}";
        }
    }
}
