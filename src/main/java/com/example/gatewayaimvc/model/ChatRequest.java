package com.example.gatewayaimvc.model;

import java.util.List;

/** OpenAI 兼容的聊天请求（网关对外统一入参） */
public record ChatRequest (String model, List<Message> messages, Double temperature){
    public record Message(String role, String content){}
}
