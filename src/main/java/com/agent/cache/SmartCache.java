package com.agent.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Multi-level TTL cache — no external dependencies.
 *
 * Why not Caffeine/Guava/Redis?
 * This is a local LLM app. The cache is small, in-process, and simple.
 * Adding a dependency for 500 lines of ConcurrentHashMap+TTL is over-engineering.
 *
 * Levels:
 *   1. Workspace summary cache (TTL: 60s) — file tree doesn't change every second
 *   2. Route cache (TTL: 5min) — same query = same agent
 *   3. Context cache (TTL: 30s) — avoid re-scanning files for rapid follow-up queries
 *
 * Background eviction thread cleans expired entries every 30s.
 */
@Slf4j
@Service
public class SmartCache {

    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor;

    public SmartCache() {
        // Background eviction — virtual thread, runs every 30s
        this.evictor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("cache-evictor");
            return t;
        });
        evictor.scheduleAtFixedRate(this::evictExpired, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Get a cached value, or null if expired/missing.
     */
    public String get(String key) {
        CacheEntry entry = store.get(key);
        if (entry == null) return null;
        if (entry.isExpired()) {
            store.remove(key);
            return null;
        }
        return entry.value;
    }

    /**
     * Put a value with TTL.
     */
    public void put(String key, String value, Duration ttl) {
        store.put(key, new CacheEntry(value, Instant.now().plus(ttl)));
    }

    /**
     * Convenience methods for specific cache levels.
     */
    public void cacheWorkspaceSummary(String key, String value) {
        put("ws:" + key, value, Duration.ofSeconds(60));
    }

    public String getWorkspaceSummary(String key) {
        return get("ws:" + key);
    }

    public void cacheContext(String queryHash, String context) {
        put("ctx:" + queryHash, context, Duration.ofSeconds(30));
    }

    public String getCachedContext(String queryHash) {
        return get("ctx:" + queryHash);
    }

    /**
     * Invalidate all workspace-related caches (called when files change).
     */
    public void invalidateWorkspace() {
        store.entrySet().removeIf(e -> e.getKey().startsWith("ws:") || e.getKey().startsWith("ctx:"));
        log.debug("Workspace cache invalidated");
    }

    public int size() {
        return store.size();
    }

    private void evictExpired() {
        int before = store.size();
        store.entrySet().removeIf(e -> e.getValue().isExpired());
        int evicted = before - store.size();
        if (evicted > 0) {
            log.debug("Cache eviction: removed {} expired entries", evicted);
        }
    }

    private record CacheEntry(String value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
