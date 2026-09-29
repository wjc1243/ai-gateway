package com.example.gatewayaimvc.provider;

import com.example.gatewayaimvc.model.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class QwenProvider extends AbstractModelProvider {

    public QwenProvider(RestClient.Builder builder) {
        super(builder);
    }

    @Override
    public String name() {
        return "qwen";
    }

    @Override
    public String endpoint() {
        return "http://localhost:9000/qwen/chat";
    }

    @Override
    protected ChatResponse normalize(JsonNode root) {
        JsonNode output = root.path("output");
        JsonNode usage = root.path("usage");

        int inputTokens  = usage.path("input_tokens").asInt();
        int outputTokens = usage.path("output_tokens").asInt();

        return new ChatResponse(
                root.path("request_id").asText(),      // ← 通义叫 request_id
                "chat.completion",
                System.currentTimeMillis() / 1000,      // ← 通义没有 created 字段，自己造
                name(),
                List.of(new ChatResponse.Choice(
                        0,                              // ← 通义没有 choices 数组，只有一条
                        new ChatResponse.Message("assistant", output.path("text").asText()),
                        output.path("finish_reason").asText("stop")
                )),
                new ChatResponse.Usage(
                        inputTokens,
                        outputTokens,
                        inputTokens + outputTokens       // ← 字段名不同，自己算
                )
        );
    }
}