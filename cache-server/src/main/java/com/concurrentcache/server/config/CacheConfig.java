package com.concurrentcache.server.config;

import com.concurrentcache.core.Cache;
import com.concurrentcache.core.ConcurrentMapCache;
import com.concurrentcache.core.GlobalLockCache;
import com.concurrentcache.core.SegmentSweepable;
import com.concurrentcache.core.SegmentedLockCache;
import com.concurrentcache.core.SegmentedRwLockCache;
import com.concurrentcache.server.sweeper.TtlSweeper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Builds the single cache instance the application serves, chosen by the
 * {@code cache.implementation} property. Every implementation also implements
 * {@link SegmentSweepable}, so the sweeper can be wired from the same bean.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    @Bean
    public Cache<String, String> cache(CacheProperties properties) {
        return switch (properties.implementation()) {
            case GLOBAL -> new GlobalLockCache<>(properties.capacity(), properties.defaultTtl());
            case SEGMENTED_A -> new SegmentedLockCache<>(
                    properties.capacity(), properties.segments(), properties.defaultTtl());
            case SEGMENTED_B -> new SegmentedRwLockCache<>(
                    properties.capacity(), properties.segments(), properties.defaultTtl());
            case CONCURRENT_MAP -> new ConcurrentMapCache<>(properties.capacity(), properties.defaultTtl());
        };
    }

    @Bean
    public TtlSweeper ttlSweeper(Cache<String, String> cache, CacheProperties properties) {
        return new TtlSweeper((SegmentSweepable) cache, properties.sweepInterval());
    }
}
