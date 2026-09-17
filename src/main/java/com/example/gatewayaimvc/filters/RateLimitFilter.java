package com.example.gatewayaimvc.filters;

import com.example.gatewayaimvc.service.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private final RateLimitService rateLimitService;

    private static final double RATE     = 10.0 / 60;   // 每分钟 10 次
    private static final int    CAPACITY = 3;           // 桶容量 3

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        String clientIp = request.servletRequest().getRemoteAddr();
        String key = "ratelimit:ip:" + clientIp;

        RateLimitService.Result result = rateLimitService.tryAcquire(key, RATE, CAPACITY, 1);
        log.info("限流判定 | key={} | allowed={} | remaining={}", key, result.allowed(), result.remaining());

        if(!result.allowed()){
            log.warn("限流触发 | ip={} | remaining={}", clientIp, result.remaining());
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .header("X-RateLimit-Remaining", "0")
                    .build();
        }

        return next.handle(request);
    }
}
