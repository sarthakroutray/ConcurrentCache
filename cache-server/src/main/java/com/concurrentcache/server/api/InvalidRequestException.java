package com.concurrentcache.server.api;

/**
 * Raised for request input the API rejects with 400: blank keys, empty or
 * oversized values, non-positive TTLs.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
