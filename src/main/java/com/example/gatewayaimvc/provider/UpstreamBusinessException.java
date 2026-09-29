package com.example.gatewayaimvc.provider;

/** 上游业务错误：HTTP 可能还是 200，但 body 里含 error（如余额不足） */
public class UpstreamBusinessException extends RuntimeException {

    private final String provider;

    public UpstreamBusinessException(String message, String provider) {
        super(message);
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
    }
}