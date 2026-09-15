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
        log.info("  ├ 转发开始 {} {}", request.method(), request.uri());

        log.info("当前线程: {}", Thread.currentThread());

        ServerResponse response = next.handle(request);

        long cost = System.currentTimeMillis() - start;
        log.info("  └ 转发完成 {} {}| 耗时 {}ms | 状态 {}",
                request.method(), request.uri(), cost, response.statusCode());
        return response;
    }
}
