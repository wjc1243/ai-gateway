package com.example.gatewayaimvc.provider;

import com.example.gatewayaimvc.model.ChatRequest;
import com.example.gatewayaimvc.model.ChatResponse;

/** 模型适配器：把一个「网关统一请求」变成「统一响应」 */
public interface ModelProvider {

    /** 模型标识，对应请求体里的 model 字段 */
    String name();

    /** 上游地址 */
    String endpoint();

    /** 调用并归一化；失败抛异常 */
    ChatResponse call(ChatRequest request);
}