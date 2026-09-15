package com.example.gatewayaimvc.config;

import com.example.gatewayaimvc.filters.LoggingFilter;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;

@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> deepseekRoute(LoggingFilter filter){
        return GatewayRouterFunctions.route("deepseek-route")
                .POST("/v1/**", HandlerFunctions.http())
                .before(uri("https://api.deepseek.com"))
                .filter(filter)
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> mockApiRoute(LoggingFilter filter){
        return GatewayRouterFunctions.route("mock-ai-route")
                .GET("/mock/ai", HandlerFunctions.http())
                .before(uri("http://localhost:9000"))
                .build();
    }
}
