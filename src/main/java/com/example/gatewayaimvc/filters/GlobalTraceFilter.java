package com.example.gatewayaimvc.filters;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(0)
@Slf4j
public class GlobalTraceFilter implements Filter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest)req;
        HttpServletResponse response = (HttpServletResponse)resp;

        // 上游传了就透传，没传就生成（分布式链路追踪的基础）
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put(REQUEST_ID_HEADER, requestId);
        long start = System.currentTimeMillis();
        try {
            log.info("→ 进入 {} {}", request.getMethod(), request.getRequestURI());
            chain.doFilter(req, resp);
        } finally {
            log.info("← 完成 {} {} | 总耗时 {}ms | 状态 {}",
                    request.getMethod(), request.getRequestURI(),
                    System.currentTimeMillis() - start, response.getStatus());
            MDC.clear();
        }
    }
}
