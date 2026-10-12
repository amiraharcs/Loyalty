package com.amiraharcs.loyalty.visit.service;

import com.amiraharcs.loyalty.common.api.ApiErrorCode;
import com.amiraharcs.loyalty.common.api.ConflictException;

/** A safe daily-qualification conflict; never exposes another shop or staff identity. */
public final class VisitAlreadyRecordedException extends ConflictException {
    @Override public ApiErrorCode code() { return ApiErrorCode.VISIT_ALREADY_RECORDED; }
}
