package com.concurrentcache.server.api;

public record ErrorResponse(int status, String error, String message) {
}
