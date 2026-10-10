package com.amiraharcs.loyalty.common.api;

/** Missing and out-of-scope resources deliberately share one public response. */
public final class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() { super("Resource not found"); }
}
