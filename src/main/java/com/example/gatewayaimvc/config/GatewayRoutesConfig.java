package com.example.gatewayaimvc.config;

import com.example.gatewayaimvc.filters.LoggingFilter;
import com.example.gatewayaimvc.filters.RateLimitFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;

@Configuration
@Slf4j
public class GatewayRoutesConfig {

    @Bean
    @Order(1)
    public RouterFunction<ServerResponse> deepseekRoute(LoggingFilter filter){
        return GatewayRouterFunctions.route("deepseek-route")
                .POST("/v1/chat/completions", HandlerFunctions.http())
                .before(uri("https://api.deepseek.com"))
                .filter(filter)
                .build();
    }

    @Bean
    @Order(2)
    public RouterFunction<ServerResponse> mockApiRoute(LoggingFilter loggingFilter, RateLimitFilter  rateLimitFilter){
        return GatewayRouterFunctions.route("mock-ai-route")
                .GET("/mock/ai", HandlerFunctions.http())
                .before(uri("http://localhost:9000"))
                .filter(rateLimitFilter)
                .filter(loggingFilter)
                .build();
    }
}
