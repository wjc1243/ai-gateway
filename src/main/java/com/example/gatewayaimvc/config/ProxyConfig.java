package com.example.gatewayaimvc.config;

import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.handler.ProxyExchange;
import org.springframework.cloud.gateway.server.mvc.handler.RestClientProxyExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class ProxyConfig {

    /** 连接超时：1 秒连不上就判定上游不可达 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    /** 读取超时：3 秒没响应就判定上游太慢（正常 /mock/ai 是 2 秒，不会误伤） */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public ProxyExchange proxyExchange(RestClient.Builder builder, GatewayMvcProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);

        RestClient client = builder.requestFactory(factory).build();
        return new RestClientProxyExchange(client, properties);
    }
}
