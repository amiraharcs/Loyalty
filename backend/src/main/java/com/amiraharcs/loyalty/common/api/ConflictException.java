package com.amiraharcs.loyalty.common.api;

/** A valid request conflicts with current state; subclasses may provide a stable domain conflict code. */
public class ConflictException extends RuntimeException {
    public ApiErrorCode code() { return ApiErrorCode.CONFLICT; }
    public ConflictException() { super("Resource state conflict"); }
}
