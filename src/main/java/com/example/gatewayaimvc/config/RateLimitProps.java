package com.example.gatewayaimvc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

@ConfigurationProperties(prefix = "gateway.ratelimit")
public record RateLimitProps(
        Boolean enabled,
        Map<String, Dimension> dimensions
) {
    public record Dimension(Double rate, Integer capacity) {}

    public boolean isEnabled() {
        return enabled != null && enabled;
    }
}