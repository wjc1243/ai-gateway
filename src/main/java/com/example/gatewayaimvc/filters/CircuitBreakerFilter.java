package com.example.gatewayaimvc.filters;

import com.example.gatewayaimvc.circuit.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Slf4j
@Component
public class CircuitBreakerFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {
    private final CircuitBreaker breaker;

    public CircuitBreakerFilter(){
        this.breaker = new CircuitBreaker("mock-upstream", 3, 10_000, 2);
    }


    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        if(!breaker.allowRequest()){
            log.warn("熔断开启，快速失败（未转发上游）");
            return unavailable("上游服务熔断中，请稍后重试");
        }
        try {
            ServerResponse response = next.handle(request);
            if(response.statusCode().is5xxServerError()){
                breaker.recordFailure();
            }else{
                breaker.recordSuccess();
            }
            return response;
        } catch (Exception e) {
            breaker.recordFailure();
            log.error("上游调用异常", e);
            return unavailable("上游服务连接失败");
        }
    }

    /** OpenAI 兼容的错误响应体 */
    private ServerResponse unavailable(String message) {
        String body = """
                {"error":{"message":"%s","type":"upstream_error","code":"service_unavailable"}}
                """.formatted(message).trim();
        return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
