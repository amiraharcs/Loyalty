package com.amiraharcs.loyalty.common.api;

public final class IdempotencyKeyReusedException extends ConflictException {
    @Override public ApiErrorCode code() { return ApiErrorCode.IDEMPOTENCY_KEY_REUSED; }
}
