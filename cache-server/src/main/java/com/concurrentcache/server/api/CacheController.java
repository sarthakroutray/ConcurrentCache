package com.concurrentcache.server.api;

import com.concurrentcache.core.Cache;
import com.concurrentcache.server.config.CacheProperties;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP view of the cache. The controller owns only transport concerns
 * (validation, status codes, JSON shapes); all state lives in the
 * {@link Cache} bean, so the same HTTP contract works with every
 * implementation.
 */
@RestController
@RequestMapping("/keys")
public class CacheController {

    private final Cache<String, String> cache;
    private final CacheProperties properties;

    public CacheController(Cache<String, String> cache, CacheProperties properties) {
        this.cache = cache;
        this.properties = properties;
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> put(
            @PathVariable String key,
            @RequestParam(name = "ttl", required = false) Long ttlSeconds,
            @RequestBody(required = false) String value) {
        validateKey(key);
        validateValue(value);
        Duration ttl = ttlOrDefault(ttlSeconds);
        if (cache.putIfAbsent(key, value, ttl)) {
            return ResponseEntity.status(HttpStatus.CREATED).build();
        }
        cache.put(key, value, ttl);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{key}")
    public ResponseEntity<String> get(@PathVariable String key) {
        validateKey(key);
        return cache.get(key)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new KeyNotFoundException(key));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        validateKey(key);
        if (!cache.delete(key)) {
            throw new KeyNotFoundException(key);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{key}/increment")
    public IncrementResponse increment(@PathVariable String key) {
        validateKey(key);
        String updated = cache.compute(key, (k, current) -> Long.toString(parseCounter(current) + 1));
        return new IncrementResponse(key, Long.parseLong(updated));
    }

    @RequestMapping(path = "", method = {RequestMethod.PUT, RequestMethod.POST, RequestMethod.GET, RequestMethod.DELETE})
    public ResponseEntity<Void> rejectMissingKey() {
        throw new InvalidRequestException("key must not be empty");
    }

    private static void validateKey(String key) {
        if (key.isBlank()) {
            throw new InvalidRequestException("key must not be blank");
        }
    }

    private void validateValue(String value) {
        if (value == null || value.isEmpty()) {
            throw new InvalidRequestException("value must not be empty");
        }
        if (value.length() > properties.maxValueLength()) {
            throw new InvalidRequestException(
                    "value exceeds the maximum length of " + properties.maxValueLength() + " characters");
        }
    }

    private Duration ttlOrDefault(Long ttlSeconds) {
        if (ttlSeconds == null) {
            return properties.defaultTtl();
        }
        if (ttlSeconds <= 0) {
            throw new InvalidRequestException("ttl must be positive");
        }
        return Duration.ofSeconds(ttlSeconds);
    }

    private static long parseCounter(String current) {
        if (current == null) {
            return 0L;
        }
        try {
            return Long.parseLong(current);
        } catch (NumberFormatException e) {
            throw new NonNumericValueException("value is not an integer counter: " + current);
        }
    }
}
