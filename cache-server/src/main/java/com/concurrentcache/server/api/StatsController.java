package com.concurrentcache.server.api;

import com.concurrentcache.core.Cache;
import com.concurrentcache.core.CacheStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the cache counters plus the current live size.
 */
@RestController
public class StatsController {

    private final Cache<String, String> cache;

    public StatsController(Cache<String, String> cache) {
        this.cache = cache;
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        CacheStats stats = cache.stats();
        return new StatsResponse(
                stats.hits(),
                stats.misses(),
                stats.evictions(),
                stats.expirations(),
                stats.hitRatio(),
                cache.size());
    }
}
