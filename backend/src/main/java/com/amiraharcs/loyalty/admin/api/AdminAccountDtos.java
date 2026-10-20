package com.amiraharcs.loyalty.admin.api;

import com.amiraharcs.loyalty.auth.model.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.*;

/** Allowlisted operational projections; never serialize identity entities. */
public final class AdminAccountDtos {
    private AdminAccountDtos() { }
    public record Page<T>(List<T> items, String nextCursor, boolean hasNext) { }
    public record Customer(UUID customerId, UUID userId, String displayName, AccountStatus status,
            Instant createdAt, List<RoleCode> roles, long balance, int currentStreak) { }
    public record Staff(UUID userId, String displayName, AccountStatus status, Instant createdAt,
            List<RoleCode> roles, List<UUID> activeStoreIds) { }
    public record UserState(UUID userId, AccountStatus status, List<RoleCode> roles) { }
    public record RoleCommandBody() { }
    public record StatusPatch(@NotNull AccountStatus status) { }
    public record AssignmentRequest(@NotNull UUID staffUserId, @NotNull UUID storeId) { }
    public record AssignmentPatch(@NotNull Boolean active) { }
    public record Assignment(UUID staffUserId, UUID storeId, boolean active, UUID assignedBy,
            Instant assignedAt, Instant updatedAt) { }
}
