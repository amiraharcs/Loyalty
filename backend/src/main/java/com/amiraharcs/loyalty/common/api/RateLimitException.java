package com.amiraharcs.loyalty.common.api;

public final class RateLimitException extends RuntimeException {
    private final long retryAfter;
    public RateLimitException(long retryAfter) { super("Request rate limit exceeded"); this.retryAfter = retryAfter; }
    public long retryAfter() { return retryAfter; }
}
