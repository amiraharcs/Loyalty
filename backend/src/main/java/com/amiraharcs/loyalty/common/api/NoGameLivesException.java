package com.amiraharcs.loyalty.common.api;

public final class NoGameLivesException extends ConflictException {
    @Override public ApiErrorCode code() { return ApiErrorCode.NO_GAME_LIVES; }
}
