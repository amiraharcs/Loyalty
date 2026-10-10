package com.amiraharcs.loyalty.common.api;

/** A valid request conflicts with current state; no domain rules are implemented here. */
public final class ConflictException extends RuntimeException {
    public ConflictException() { super("Resource state conflict"); }
}
