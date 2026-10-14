package com.amiraharcs.loyalty.qr.service;

import com.amiraharcs.loyalty.common.api.*;

public final class QrFlowException extends ConflictException {
    private final ApiErrorCode code;
    public QrFlowException(ApiErrorCode code) {
        if (!java.util.Set.of(ApiErrorCode.QR_INVALID, ApiErrorCode.QR_EXPIRED, ApiErrorCode.QR_REVOKED,
                ApiErrorCode.SCAN_RESOLUTION_EXPIRED, ApiErrorCode.SCAN_RESOLUTION_CONSUMED).contains(code)) {
            throw new IllegalArgumentException("Invalid QR error code");
        }
        this.code = code;
    }
    @Override public ApiErrorCode code() { return code; }
}
