package com.amiraharcs.loyalty.common.api;

public final class LastAdminRequiredException extends ConflictException {
    @Override public ApiErrorCode code() {return ApiErrorCode.LAST_ADMIN_REQUIRED;}
}
