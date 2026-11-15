package com.concurrentcache.server.api;

public record StatsResponse(
        long hits,
        long misses,
        long evictions,
        long expirations,
        double hitRatio,
        long size) {
}
