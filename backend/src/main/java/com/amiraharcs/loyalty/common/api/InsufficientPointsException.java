package com.amiraharcs.loyalty.common.api;

public final class InsufficientPointsException extends ConflictException {
    @Override public ApiErrorCode code() { return ApiErrorCode.INSUFFICIENT_POINTS; }
}
