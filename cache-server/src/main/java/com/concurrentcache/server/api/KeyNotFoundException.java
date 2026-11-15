package com.concurrentcache.server.api;

/**
 * Raised when a key is unknown or already expired; the handler answers 404.
 */
public class KeyNotFoundException extends RuntimeException {

    public KeyNotFoundException(String key) {
        super("no value stored for key: " + key);
    }
}
