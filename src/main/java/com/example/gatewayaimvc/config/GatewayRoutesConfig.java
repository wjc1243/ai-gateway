package com.example.gatewayaimvc.config;

import com.example.gatewayaimvc.filters.CircuitBreakerFilter;
import com.example.gatewayaimvc.filters.FailoverFilter;
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
    public RouterFunction<ServerResponse> mockApiRoute(LoggingFilter loggingFilter,
                                                       RateLimitFilter  rateLimitFilter,
                                                       CircuitBreakerFilter circuitBreakerFilter,
                                                       FailoverFilter failoverFilter){
        return GatewayRouterFunctions.route("mock-ai-route")
                .GET("/mock/ai", HandlerFunctions.http())
                .before(uri("http://localhost:9000"))
                .filter(rateLimitFilter)
                .filter(failoverFilter)
                .filter(circuitBreakerFilter)
                .filter(loggingFilter)
                .build();
    }

    @Bean
    @Order(3)
    public RouterFunction<ServerResponse> mockFailRoute(CircuitBreakerFilter circuitBreakerFilter) {
        return GatewayRouterFunctions.route("mock-fail-route")
                .GET("/mock/fail/**", HandlerFunctions.http())
                .before(uri("http://localhost:9000"))
                .filter(circuitBreakerFilter)
                .build();
    }

    @Bean
    @Order(4)
    public RouterFunction<ServerResponse> mockSlowRoute(CircuitBreakerFilter circuitBreakerFilter,
                                                        FailoverFilter failoverFilter) {
        return GatewayRouterFunctions.route("mock-slow-route")
                .GET("/mock/slow", HandlerFunctions.http())
                .before(uri("http://localhost:9000"))
                .filter(failoverFilter)
                .filter(circuitBreakerFilter)
                .build();
    }
}
