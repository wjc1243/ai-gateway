package com.example.gatewayaimvc.provider;

import com.example.gatewayaimvc.model.ChatRequest;
import com.example.gatewayaimvc.model.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Slf4j
public abstract class AbstractModelProvider implements ModelProvider {

    protected final RestClient client;
    protected final ObjectMapper mapper = new ObjectMapper();

    protected AbstractModelProvider(RestClient.Builder builder) {
        this.client = buildClient(builder);
    }

    /** AI 生成慢，读超时给 30 秒（区别于 M5 的 3 秒） */
    private static RestClient buildClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return builder.requestFactory(factory).build();
    }

    @Override
    public ChatResponse call(ChatRequest request) {
        String raw = client.post()
                .uri(endpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = mapper.readTree(raw);

            // ★ 关键：HTTP 200 但 body 含 error（余额不足等），按失败处理
            if (root.has("error")) {
                String msg = root.path("error").path("message").asText("上游业务错误");
                log.warn("[{}] HTTP 200 但含 error，按失败处理 | {}", name(), msg);
                throw new UpstreamBusinessException(msg, name());
            }

            return normalize(root);

        } catch (UpstreamBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] 响应解析失败 | raw={}", name(), raw, e);
            throw new IllegalStateException("响应解析失败: " + name(), e);
        }
    }

    /** 子类实现：把各家格式转成 OpenAI 统一格式 */
    protected abstract ChatResponse normalize(JsonNode root);
}