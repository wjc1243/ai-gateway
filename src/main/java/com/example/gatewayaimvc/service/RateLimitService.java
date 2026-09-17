package com.example.gatewayaimvc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redis;

    private static final RedisScript<List> SCRIPT = RedisScript.of(
            new ClassPathResource("lua/token_bucket.lua"), List.class);

    /** 限流判定结果 */
    public record Result(boolean allowed, long remaining){}

    /**
     * 尝试获取令牌
     * @param key       限流维度 key，如 ratelimit:ip:127.0.0.1
     * @param rate      每秒生成令牌数（长期速率）
     * @param capacity  桶容量（突发上限）
     * @param requested 本次消耗令牌数
     */
    public Result tryAcquire(String key, double rate, int capacity, int requested) {
        try {
            List<Object> res = redis.execute(SCRIPT,
                    List.of(key),
                    String.valueOf(rate),
                    String.valueOf(capacity),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(requested));

            if (res == null || res.isEmpty()) {
                return new Result(true, capacity);   // 脚本异常 → 放行
            }
            long allowed   = ((Number) res.get(0)).longValue();
            long remaining = ((Number) res.get(1)).longValue();
            return new Result(allowed == 1, remaining);

        } catch (Exception e) {
            // ★ 降级策略：Redis 故障时 fail-open（放行），避免网关整体不可用
            log.error("限流脚本执行失败，降级放行 key={}", key, e);
            return new Result(true, -1);
        }
    }
}
