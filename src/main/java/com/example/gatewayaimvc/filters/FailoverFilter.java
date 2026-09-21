package com.example.gatewayaimvc.filters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.http.HttpClient;
import java.time.Duration;

@Slf4j
@Component
public class FailoverFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private static final String BACKUP_BASE = "http://localhost:9001";

    private final RestClient client;

    public FailoverFilter() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        ServerResponse response = next.handle(request);

        // 主模型正常，直接返回
        if (!response.statusCode().is5xxServerError()){
            return response;
        }

        String query = request.uri().getQuery();
        String backupUri = BACKUP_BASE + "/mock/backup" + (query != null ? "?" + query : "");

        try {
            log.warn("主模型失败({})，failover 到备用: {}",
                    response.statusCode().value(), backupUri);

            String body = client.get()
                    .uri(backupUri)
                    .retrieve()
                    .body(String.class);

            return ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Served-By", "backup-model")     // ★ 标记由备用提供
                    .header("X-Original-Status", String.valueOf(response.statusCode().value()))
                    .body(body);

        }catch (Exception e){
            log.error("备用模型也失败，返回主模型错误", e);
            return response;   // 备用也挂 → 不雪崩，原样返回主的错误
        }
    }
}
