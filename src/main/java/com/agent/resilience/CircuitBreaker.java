package com.agent.resilience;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Lightweight circuit breaker — no external dependencies.
 *
 * Why: Ollama can go down, hang, or OOM. Without a circuit breaker,
 * every request piles up waiting for a dead service, killing the whole app.
 *
 * States:
 *   CLOSED  → normal operation, requests pass through
 *   OPEN    → Ollama is down, fail fast without calling it
 *   HALF_OPEN → test with one request to see if Ollama recovered
 *
 * This is a CTO-level pattern: protect your system from cascading failures.
 */
@Slf4j
public class CircuitBreaker {

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final Duration openDuration;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicReference<Instant> openedAt = new AtomicReference<>();

    public CircuitBreaker(String name, int failureThreshold, Duration openDuration) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    /**
     * Execute a supplier through the circuit breaker.
     * If the circuit is open, throws immediately without calling the supplier.
     */
    public <T> T execute(Supplier<T> action) {
        State current = state.get();

        if (current == State.OPEN) {
            // Check if enough time has passed to try again
            if (Instant.now().isAfter(openedAt.get().plus(openDuration))) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("[CircuitBreaker:{}] Transitioning to HALF_OPEN", name);
                }
            } else {
                throw new CircuitBreakerOpenException(
                        "Circuit breaker '" + name + "' is OPEN. Ollama appears to be down. " +
                        "Will retry after " + openDuration.toSeconds() + "s.");
            }
        }

        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure(e);
            throw e;
        }
    }

    private void onSuccess() {
        failureCount.set(0);
        if (state.get() == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            if (successes >= 2) {
                state.set(State.CLOSED);
                successCount.set(0);
                log.info("[CircuitBreaker:{}] Recovered → CLOSED", name);
            }
        }
    }

    private void onFailure(Exception e) {
        successCount.set(0);
        int failures = failureCount.incrementAndGet();
        log.warn("[CircuitBreaker:{}] Failure #{}: {}", name, failures, e.getMessage());

        if (failures >= failureThreshold && state.get() != State.OPEN) {
            state.set(State.OPEN);
            openedAt.set(Instant.now());
            log.error("[CircuitBreaker:{}] OPENED after {} failures. Blocking calls for {}s.",
                    name, failures, openDuration.toSeconds());
        }
    }

    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    public String getState() {
        return state.get().name();
    }

    /**
     * Runtime exception when circuit is open.
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
}
