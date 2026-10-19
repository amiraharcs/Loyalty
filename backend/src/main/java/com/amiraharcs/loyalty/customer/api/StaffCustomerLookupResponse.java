package com.amiraharcs.loyalty.customer.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Service-only allowlist. The supplied member ID is deliberately not echoed. */
public record StaffCustomerLookupResponse(String displayName, long balance, int currentStreak,
        List<PendingRedemption> pendingRedemptions, boolean hasMorePendingRedemptions) {
    public StaffCustomerLookupResponse { pendingRedemptions = List.copyOf(pendingRedemptions); }
    public record PendingRedemption(UUID redemptionId, String rewardName, String rewardDescription,
            String status, Instant expiresAt) { }
}
