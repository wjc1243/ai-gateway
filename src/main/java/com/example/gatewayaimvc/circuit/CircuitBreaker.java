package com.example.gatewayaimvc.circuit;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class CircuitBreaker {

    public enum State{
        CLOSED, OPEN, HALF_OPEN
    }

    private final String name;
    private final int failureThreshold;         // 连续失败几次跳闸
    private final long openWaitMs;              // OPEN 冷却多久转 HALF_OPEN
    private final int halfOpenSuccessThreshold; // HALF_OPEN 成功几次算恢复

    private AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);   // 连败几次
    private final AtomicLong openedAt = new AtomicLong(0);                    // 什么时候跳的闸
    private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);     // 试探成功几次

    public CircuitBreaker(String name, int failureThreshold, long openWaitMs, int halfOpenSuccessThreshold) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openWaitMs = openWaitMs;
        this.halfOpenSuccessThreshold = halfOpenSuccessThreshold;
    }

    public boolean allowRequest() {
        State s = state.get();
        if(s == State.CLOSED){
            return true;
        }
        if(s == State.OPEN){
            if(System.currentTimeMillis() - openedAt.get() >= openWaitMs){
                // 冷却时间到了 → 转 HALF_OPEN，放个请求去试探
                if(state.compareAndSet(State.OPEN, State.HALF_OPEN)){
                    halfOpenSuccesses.set(0);
                    log.info("[{}] OPEN → HALF_OPEN（开始试探）", name);
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    public void recordSuccess() {
        State s = state.get();
        if (s == State.HALF_OPEN) {
            int ok = halfOpenSuccesses.incrementAndGet();
            log.info("[{}] HALF_OPEN 试探成功 {}/{}", name, ok, halfOpenSuccessThreshold);
            if (ok >= halfOpenSuccessThreshold) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    consecutiveFailures.set(0);
                    log.info("[{}] HALF_OPEN → CLOSED（已恢复）", name);
                }
            }
        } else if (s == State.CLOSED) {
            consecutiveFailures.set(0);
        }
    }

    public void recordFailure() {
        State s = state.get();
        if (s == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                log.warn("[{}] HALF_OPEN → OPEN（试探又失败）", name);
            }
            return;
        }
        if (s == State.CLOSED) {
            int fails = consecutiveFailures.incrementAndGet();
            log.warn("[{}] 连续失败 {}/{}", name, fails, failureThreshold);
            if (fails >= failureThreshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    openedAt.set(System.currentTimeMillis());
                    log.error("[{}] CLOSED → OPEN（已跳闸）", name);
                }
            }
        }
    }

    public State getState() { return state.get(); }
}
