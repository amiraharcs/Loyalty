package com.amiraharcs.loyalty.auth.api;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.Set;
import java.util.UUID;

/** Opaque identity ID, optional customer display name, and explicit grants only. */
public record CurrentUserResponse(UUID userId, String displayName, Set<RoleCode> roles) {
    public CurrentUserResponse { roles = Set.copyOf(roles); }
}
