package com.concurrentcache.server.api;

/**
 * Raised when an increment is attempted on a value that is not an integer; the
 * handler answers 409 because the request conflicts with the stored value.
 */
public class NonNumericValueException extends RuntimeException {

    public NonNumericValueException(String message) {
        super(message);
    }
}
