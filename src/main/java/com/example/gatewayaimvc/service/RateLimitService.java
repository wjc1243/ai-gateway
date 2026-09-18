package com.example.gatewayaimvc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redis;

    private static final RedisScript<List> MULTI_SCRIPT = RedisScript.of(
//            new ClassPathResource("lua/token_bucket.lua"), List.class);
            new ClassPathResource("lua/token_bucket_multi.lua"), List.class);

    /** 限流判定结果 */
    public record Result(boolean allowed, long remaining, String blockedBy){}

    public record Dimension(String key, double rate, int capacity) {}

    public Result tryAcquireAll(List<Dimension> dims, int requested) {
        if (dims == null || dims.isEmpty()) {
            return new Result(true, -1, "");
        }
        try {
            List<String> keys = new ArrayList<>();
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(System.currentTimeMillis()));
            args.add(String.valueOf(requested));
            for (Dimension d : dims) {
                keys.add(d.key());
                args.add(String.valueOf(d.rate()));
                args.add(String.valueOf(d.capacity()));
            }

            List<Object> res = redis.execute(MULTI_SCRIPT, keys, args.toArray());
            if (res == null || res.isEmpty()) {
                return new Result(true, -1, "");
            }
            long allowed   = ((Number) res.get(0)).longValue();
            long remaining = ((Number) res.get(1)).longValue();
            String blockedBy = res.size() > 2 && res.get(2) != null ? res.get(2).toString() : "";
            return new Result(allowed == 1, remaining, blockedBy);

        } catch (Exception e) {
            log.error("多维度限流脚本执行失败，降级放行", e);
            return new Result(true, -1, "");
        }
    }
}
