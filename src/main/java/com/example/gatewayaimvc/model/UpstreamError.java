package com.example.gatewayaimvc.model;

/** 上游返回的业务错误（可能是 HTTP 200 但 body 含 error） */
public record UpstreamError(String message, String type, String code) {}