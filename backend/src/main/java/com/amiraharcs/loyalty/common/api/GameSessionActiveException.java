package com.amiraharcs.loyalty.common.api;

public final class GameSessionActiveException extends ConflictException {
    @Override public ApiErrorCode code() { return ApiErrorCode.GAME_SESSION_ACTIVE; }
}
