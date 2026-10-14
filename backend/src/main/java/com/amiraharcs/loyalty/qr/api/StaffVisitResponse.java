package com.amiraharcs.loyalty.qr.api;

import com.amiraharcs.loyalty.visit.service.VisitConfirmationResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StaffVisitResponse(UUID visitId, Instant confirmedAt, LocalDate businessDate, long earnedPoints, long updatedBalance) {
    public static StaffVisitResponse from(VisitConfirmationResult result) {
        return new StaffVisitResponse(result.visitId(), result.confirmedAt(), result.businessDate(), result.earnedPoints(), result.balance());
    }
}
