package com.amiraharcs.loyalty.common.api;

public final class GameScoreConflictException extends ConflictException {
    private final ApiErrorCode code;
    public GameScoreConflictException(ApiErrorCode code) {
        if(code!=ApiErrorCode.SESSION_EXPIRED && code!=ApiErrorCode.SCORE_ALREADY_SUBMITTED && code!=ApiErrorCode.SCORE_IMPLAUSIBLE) {
            throw new IllegalArgumentException("Unsupported score conflict");
        }
        this.code=code;
    }
    @Override public ApiErrorCode code() {return code;}
}
