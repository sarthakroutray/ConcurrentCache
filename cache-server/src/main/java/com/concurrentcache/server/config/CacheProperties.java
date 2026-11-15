package com.concurrentcache.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the cache instance exposed over HTTP. Values bind from the
 * {@code cache.*} keys of application.yml and can be overridden per environment.
 */
@ConfigurationProperties(prefix = "cache")
public record CacheProperties(
        @DefaultValue("global") Implementation implementation,
        @DefaultValue("10000") int capacity,
        @DefaultValue("16") int segments,
        @DefaultValue("60s") Duration defaultTtl,
        @DefaultValue("4096") int maxValueLength,
        @DefaultValue("30s") Duration sweepInterval) {

    public enum Implementation {
        GLOBAL, SEGMENTED_A, SEGMENTED_B, CONCURRENT_MAP
    }
}
