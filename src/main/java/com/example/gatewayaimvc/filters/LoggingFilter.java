package com.example.gatewayaimvc.filters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Slf4j
@Component
public class LoggingFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {
    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        long start = System.currentTimeMillis();
        log.info("请求进入: {} {}", request.method(), request.uri());

        ServerResponse response = next.handle(request);

        long cost = System.currentTimeMillis() - start;
        log.info("请求完成: {} {} | 耗时 {}ms | 状态码 {}",
                request.method(), request.uri(), cost, response.statusCode());
        return response;
    }
}
