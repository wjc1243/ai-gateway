package com.example.gatewayaimvc.filters;

import com.example.gatewayaimvc.config.RateLimitProps;
import com.example.gatewayaimvc.service.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
//@RequiredArgsConstructor
public class RateLimitFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private final RateLimitService rateLimitService;
    private final RateLimitProps props;

    public RateLimitFilter(RateLimitService rateLimitService, RateLimitProps props) {
        this.rateLimitService = rateLimitService;
        this.props = props;
        log.info("限流配置加载 | enabled={} | dimensions={}", props.isEnabled(), props.dimensions());
    }

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        if (!props.isEnabled()) {
            return next.handle(request);
        }

        List<RateLimitService.Dimension> dims = new ArrayList<>();
        addDim(dims, "ip", "ratelimit:ip:" + resolveClientIp(request));

        String apiKey = resolveApiKey(request);
        if (apiKey != null) {
            addDim(dims, "key", apiKey);
        }

        if (dims.isEmpty()) {
            return next.handle(request);
        }

        RateLimitService.Result r = rateLimitService.tryAcquireAll(dims, 1);
        log.info("限流判定 | dims={} | allowed={} | remaining={}", dims.size(), r.allowed(), r.remaining());

        if (!r.allowed()) {
            log.warn("限流触发 | blockedBy={}", r.blockedBy());
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .header("X-RateLimit-Blocked-By", r.blockedBy())
                    .build();
        }
        return next.handle(request);
    }

    private void addDim(List<RateLimitService.Dimension> dims, String name, String key) {
        if (props.dimensions() == null) return;
        RateLimitProps.Dimension cfg = props.dimensions().get(name);
        if (cfg != null && cfg.rate() != null && cfg.capacity() != null) {
            dims.add(new RateLimitService.Dimension(key, cfg.rate(), cfg.capacity()));
        }
    }

    private String resolveClientIp(ServerRequest request) {
        String xff = request.headers().firstHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.servletRequest().getRemoteAddr();
    }

    private String resolveApiKey(ServerRequest request) {
        String auth = request.headers().firstHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String key = auth.substring(7);
            return "ratelimit:key:" + key.substring(0, Math.min(8, key.length()));
        }
        return null;
    }
}
