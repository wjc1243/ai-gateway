package com.example.gatewayaimvc.provider;

import com.example.gatewayaimvc.model.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class DeepSeekProvider extends AbstractModelProvider {

    public DeepSeekProvider(RestClient.Builder builder) {
        super(builder);
    }

    @Override
    public String name() {
        return "deepseek-chat";
    }

    @Override
    public String endpoint() {
        return "http://localhost:9000/openai/chat";
    }

    @Override
    protected ChatResponse normalize(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode usage = root.path("usage");

        return new ChatResponse(
                root.path("id").asText(),
                "chat.completion",
                root.path("created").asLong(),
                root.path("model").asText(name()),
                List.of(new ChatResponse.Choice(
                        choice.path("index").asInt(0),
                        new ChatResponse.Message(
                                choice.path("message").path("role").asText("assistant"),
                                choice.path("message").path("content").asText()
                        ),
                        choice.path("finish_reason").asText("stop")
                )),
                new ChatResponse.Usage(
                        usage.path("prompt_tokens").asInt(),
                        usage.path("completion_tokens").asInt(),
                        usage.path("total_tokens").asInt()
                )
        );
    }
}