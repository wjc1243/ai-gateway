package com.example.gatewayaimvc.model;

import java.util.List;

/** OpenAI 兼容的聊天响应（网关对外统一出参） */
public record ChatResponse(
        String id,
        String object,
        Long created,
        String model,
        List<Choice> choices,
        Usage usage
) {
    public record Choice(Integer index, Message message, String finishReason) {}
    public record Message(String role, String content) {}
    public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}
}