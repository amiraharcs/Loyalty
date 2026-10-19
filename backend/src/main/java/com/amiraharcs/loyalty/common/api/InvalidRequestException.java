package com.amiraharcs.loyalty.common.api;

/** Safe validation failure for manually parsed, sensitive input. Never retains the input. */
public final class InvalidRequestException extends RuntimeException {
    public InvalidRequestException() { super("Invalid request"); }
}
