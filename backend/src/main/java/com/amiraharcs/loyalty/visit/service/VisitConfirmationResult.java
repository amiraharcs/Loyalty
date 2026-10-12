package com.amiraharcs.loyalty.visit.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Server-derived confirmation result, with a ledger-derived balance and no identity/credential data. */
public record VisitConfirmationResult(UUID visitId, Instant confirmedAt, LocalDate businessDate,
                                      long earnedPoints, long balance) { }
